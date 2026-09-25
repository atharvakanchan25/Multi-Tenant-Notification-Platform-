package com.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///notifications",
        "spring.flyway.enabled=true",
        "aws.accessKeyId=dummy",
        "aws.secretAccessKey=dummy",
        "aws.region=us-east-1"
})
class NotificationServiceApplicationTests {

    @Test
    void contextLoads() {}
}
