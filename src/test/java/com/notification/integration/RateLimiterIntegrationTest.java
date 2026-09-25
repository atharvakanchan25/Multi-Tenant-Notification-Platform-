package com.notification.integration;

import com.notification.config.KafkaConfig;
import com.notification.domain.NotificationStatus;
import com.notification.dto.NotificationEvent;
import com.notification.repository.NotificationRequestRepository;
import com.notification.service.RateLimiterService;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class RateLimiterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notifications")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Use the same limit for both tiers so assertions are predictable
        registry.add("rate-limit.paid-per-window", () -> "10");
        registry.add("rate-limit.free-per-window", () -> "10");
        registry.add("rate-limit.window-seconds", () -> "1");
        // Disable Twilio adapter auto-wiring in tests
        registry.add("twilio.account-sid", () -> "test-sid");
        registry.add("twilio.auth-token", () -> "test-token");
        registry.add("twilio.from-number", () -> "+10000000000");
    }

    @MockBean
    SesClient sesClient;

    @Autowired NotificationRequestRepository notificationRepo;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired RateLimiterService rateLimiterService;

    private KafkaTemplate<String, NotificationEvent> producer;

    @BeforeEach
    void setUp() {
        // Stub SES to return a fake message ID so SesEmailAdapter.send() succeeds
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("mock-msg-id").build());

        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class
        )));

        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    // ── Unit-level rate limiter tests ────────────────────────────────────────

    @Test
    void rateLimiter_allows_only_limitPerWindow_within_one_second() {
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (rateLimiterService.tryConsume("tenant-test")) allowed++;
        }
        // Only 10 tokens should be granted within a single 1-second window
        assertThat(allowed).isEqualTo(10);
    }

    @Test
    void rateLimiter_resets_after_window_expires() throws InterruptedException {
        for (int i = 0; i < 10; i++) rateLimiterService.tryConsume("tenant-window");
        assertThat(rateLimiterService.tryConsume("tenant-window")).isFalse();

        TimeUnit.SECONDS.sleep(2); // wait for window + TTL to expire

        assertThat(rateLimiterService.tryConsume("tenant-window")).isTrue();
    }

    // ── End-to-end consumer throttle test ───────────────────────────────────

    @Test
    void consumer_throttles_100_messages_and_eventually_processes_all() throws InterruptedException {
        String tenantId = "tenant-demo";
        int total = 100;

        for (int i = 1; i <= total; i++) {
            var req = new com.notification.domain.NotificationRequest();
            req.setTenantId(tenantId);
            req.setChannel(com.notification.domain.Channel.EMAIL);
            req.setRecipient("user" + i + "@example.com");
            req.setTemplateId("welcome");
            req.setIdempotencyKey("idem-e2e-" + i);
            notificationRepo.save(req);

            producer.send(KafkaConfig.TOPIC_PAID, tenantId, new NotificationEvent(
                    req.getId(), req.getIdempotencyKey(),
                    tenantId, com.notification.domain.Channel.EMAIL,
                    req.getRecipient(), "welcome", Map.of("name", "User" + i, "appName", "TestApp")
            ));
        }

        // All 100 messages must eventually leave PENDING (SENT, FAILED, or SUPPRESSED)
        // The rate limiter will nack throttled messages; Kafka re-delivers them across windows
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    long pending = notificationRepo.findAll().stream()
                            .filter(r -> r.getStatus() == NotificationStatus.PENDING)
                            .count();
                    assertThat(pending).isZero();
                });

        // Confirm all reached SENT (SES mock never throws)
        long sent = notificationRepo.findAll().stream()
                .filter(r -> r.getStatus() == NotificationStatus.SENT)
                .count();
        assertThat(sent).isEqualTo(total);
    }
}
