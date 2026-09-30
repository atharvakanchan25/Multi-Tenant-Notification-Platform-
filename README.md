# Notification Service

A production-grade, multi-tenant notification microservice built with Spring Boot 3.2. It handles email, SMS, and push notifications with fair multi-tenant scheduling, per-tenant rate limiting, automatic provider failover, idempotency, and a full observability stack.

---

## Why This Project Exists

Sending notifications sounds simple — call an API, done. But at scale, across multiple tenants and channels, several hard problems appear:

- A noisy FREE-tier tenant should never starve a PAID tenant's messages
- A provider outage (e.g. Twilio goes down) should not drop messages
- The same notification must never be sent twice, even if the client retries
- A recipient who opted out must never receive a message regardless of who sends it
- Every delivery attempt must be auditable end-to-end

This service solves all of the above in a single deployable unit backed by battle-tested infrastructure.

---

## Architecture

![Architecture Diagram](architecture%20diagram.png)

### How It All Fits Together

```
Client
  │
  ▼
REST API (Spring Boot :8080)
  │  saves to DB + publishes event
  ▼
PostgreSQL ◄──────────────────────────────────────┐
                                                   │ status updates
Kafka (Redpanda)                                   │
  ├── notification-requests-paid (4 partitions)    │
  └── notification-requests-free (2 partitions)    │
          │                                        │
          ▼                                        │
  Kafka Consumers                                  │
  ├── Rate Limiter (Redis)                         │
  ├── Opt-out check (PostgreSQL)                   │
  └── DispatchService                              │
        ├── Retry + Circuit Breaker ───────────────┘
        ├── SES (Email)
        ├── Twilio (SMS) ──► Fallback SMS if CB open
        └── Push Stub

Webhooks (/webhooks/ses, /webhooks/twilio)
  └── StatusTrackingService → PostgreSQL

Observability
  ├── Prometheus  → scrapes /actuator/prometheus
  ├── Grafana     → dashboards on :3000
  └── Jaeger      → traces on :16686
```

---

## Key Features

| Feature | How It Works |
|---|---|
| Multi-tenant isolation | PAID tenants get dedicated Kafka partitions and consumer threads; FREE tenants cannot starve them |
| Per-tenant rate limiting | Redis Lua atomic token bucket — PAID: 100 msg/s, FREE: 10 msg/s |
| Idempotency | Client-supplied or server-generated key; duplicate requests return the existing record |
| Retry with backoff | Resilience4j — 4 attempts, exponential backoff (500ms → 1s → 2s), ±30% jitter |
| Circuit breaker | Opens at 50% failure rate over 20 calls; auto half-open probe after 30s |
| SMS failover | If Twilio circuit breaker is OPEN, traffic is silently rerouted to a secondary SMS provider |
| Opt-out suppression | Per-tenant, per-channel opt-out table; suppressed messages are recorded but never sent |
| Webhook delivery tracking | HMAC-SHA256 verified callbacks from SES and Twilio update final delivery status |
| Template engine | Handlebars templates stored per tenant/channel in PostgreSQL |
| Dead Letter Queue | All exhausted retries are published to `dlq-notifications` for replay |
| Observability | Micrometer metrics, OTel traces, Prometheus + Grafana + Jaeger — all pre-wired |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Database | PostgreSQL 16 + Flyway migrations |
| Messaging | Redpanda (Kafka-compatible) |
| Caching / Rate limit | Redis 7 |
| Email | AWS SES (SDK v2) |
| SMS | Twilio |
| Resilience | Resilience4j (Retry + CircuitBreaker) |
| Templates | Handlebars (jknack) |
| Metrics | Micrometer + Prometheus |
| Tracing | OpenTelemetry + Jaeger |
| Dashboards | Grafana |
| Containerisation | Docker + Docker Compose |
| Build | Maven 3.9 |

---

## Project Structure

```
notification-service/
├── src/main/java/com/notification/
│   ├── controller/
│   │   ├── NotificationController.java       # POST /api/v1/notifications
│   │   └── DeliveryWebhookController.java    # POST /webhooks/ses|twilio
│   ├── service/
│   │   ├── NotificationService.java          # Accept, dedup, persist, publish
│   │   ├── NotificationEventPublisher.java   # Routes to PAID or FREE topic
│   │   ├── NotificationConsumer.java         # Kafka listeners (paid + free)
│   │   ├── RateLimiterService.java           # Redis token bucket
│   │   ├── PreferenceService.java            # Opt-out suppression
│   │   ├── DispatchService.java              # Retry, CB, failover, send
│   │   ├── TemplateService.java              # Handlebars rendering
│   │   ├── StatusTrackingService.java        # Webhook → DB status update
│   │   ├── DlqPublisher.java                 # Publish to DLQ topic
│   │   └── NotificationMetrics.java          # Micrometer facade
│   ├── adapter/
│   │   ├── ProviderAdapter.java              # Interface
│   │   ├── SesEmailAdapter.java              # AWS SES
│   │   ├── TwilioSmsAdapter.java             # Twilio
│   │   ├── PushStubAdapter.java              # Stub (logs only)
│   │   └── SecondarySmsFallbackAdapter.java  # SMS fallback
│   ├── domain/                               # JPA entities + enums
│   ├── dto/                                  # Request/response/event records
│   ├── repository/                           # Spring Data JPA interfaces
│   └── config/
│       ├── KafkaConfig.java                  # Topics, producer, consumers
│       └── AwsSesConfig.java                 # SesClient bean
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/                         # Flyway V1–V5 scripts
├── src/test/
│   └── ...                                   # Integration tests (Testcontainers)
├── observability/
│   ├── prometheus.yml
│   └── grafana/provisioning/                 # Auto-provisioned dashboards
├── load-test/
│   └── notification_load_test.js             # k6 load test
├── Dockerfile
├── docker-compose.yml
├── start.bat
└── .env.example
```

---

## Database Schema

```
tenants                notification_requests         notification_templates
──────────────────     ─────────────────────────     ──────────────────────────
id                     id                            id
tenant_id (unique)     tenant_id                     tenant_id
name                   channel                       template_id
sender_email           recipient                     channel
tier (PAID|FREE)       template_id                   subject
created_at             data (jsonb)                  body
                       status
                       error_message
                       idempotency_key (unique)       opt_outs
                       created_at                    ──────────────────────────
                       updated_at                    id
                                                     tenant_id
delivery_events                                      recipient
──────────────────                                   channel
id                                                   created_at
notification_id
provider
provider_message_id
event_type
raw_payload (jsonb)
received_at
```

---

## Request Lifecycle

```
POST /api/v1/notifications
        │
        ├─► Validate request (Bean Validation)
        ├─► Lookup tenant (404 if unknown)
        ├─► Idempotency check → return existing if duplicate
        ├─► Save NotificationRequest (status = PENDING)
        ├─► Publish to Kafka (PAID or FREE topic)
        └─► Return HTTP 202 { notificationId, status: PENDING }

Kafka Consumer
        │
        ├─► Rate limit check (Redis)
        │       └─► Over limit → nack, requeue after 1s
        ├─► Opt-out check
        │       └─► Suppressed → status = SUPPRESSED, ack
        └─► DispatchService
                ├─► SMS + Twilio CB open? → use fallback adapter
                ├─► Render Handlebars template
                ├─► adapter.send() with @Retry + @CircuitBreaker
                │       ├─► Success → status = SENT, record latency
                │       └─► All retries fail → DLQ + status = FAILED
                └─► Ack Kafka offset

Webhook Callback
        │
        ├─► Verify HMAC-SHA256 signature
        ├─► Save DeliveryEvent (raw payload)
        └─► Update status → DELIVERED or FAILED
```

---

## Getting Started

### Prerequisites

- Docker Desktop
- Java 17+ and Maven (only needed if building outside Docker)
- k6 (optional, for load testing)

### 1. Configure environment

```bash
cp .env.example .env
```

Edit `.env` and fill in your real values:

```env
AWS_ACCESS_KEY_ID=your-access-key
AWS_SECRET_ACCESS_KEY=your-secret-key
AWS_REGION=us-east-1

TWILIO_ACCOUNT_SID=your-twilio-sid
TWILIO_AUTH_TOKEN=your-twilio-token
TWILIO_FROM_NUMBER=+1xxxxxxxxxx

WEBHOOK_SECRET=your-hmac-secret

POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
POSTGRES_DB=notifications
```

> For local development without real AWS/Twilio credentials, the app falls back to stub/dummy values. Email calls will fail gracefully and be routed to the DLQ; the Push stub always succeeds.

### 2. Start everything

**Windows:**
```bat
start.bat
```

**Mac / Linux:**
```bash
docker compose up --build
```

This starts: PostgreSQL, Redpanda, Redis, the Spring Boot app, Prometheus, Grafana, and Jaeger.

### 3. Verify it's running

```bash
curl http://localhost:8080/actuator/health
```

---

## API Reference

### Send a Notification

```
POST /api/v1/notifications
Content-Type: application/json
```

```json
{
  "tenantId": "tenant-demo",
  "channel": "EMAIL",
  "recipient": "user@example.com",
  "templateId": "welcome",
  "data": {
    "name": "Alice",
    "appName": "MyApp"
  },
  "idempotencyKey": "optional-unique-key"
}
```

**Response `202 Accepted`:**
```json
{
  "notificationId": 1,
  "status": "PENDING",
  "message": "Notification queued"
}
```

**Channels:** `EMAIL` | `SMS` | `PUSH`

**Seeded templates (tenant-demo):** `welcome`, `otp`

### Webhook Callbacks

```
POST /webhooks/ses
POST /webhooks/twilio
Header: X-Webhook-Signature: sha256=<hmac>
```

---

## Observability

| Tool | URL | What You'll Find |
|---|---|---|
| Grafana | http://localhost:3000 | Pre-built dashboard — sent/failed/DLQ rates, p99 latency, failover events. Login: `admin` / `admin` |
| Prometheus | http://localhost:9090 | Raw metrics, query with PromQL |
| Jaeger | http://localhost:16686 | Distributed traces per notification |
| Actuator | http://localhost:8080/actuator/prometheus | Raw Micrometer metrics |

### Key Metrics

```
notification_sent_total{tenant, channel, provider}
notification_failed_total{tenant, channel, provider}
notification_failover_total{channel, from_provider, to_provider}
notification_dlq_total{tenant, reason}
notification_dlq_depth
notification_suppressed_total{tenant, channel}
notification_dispatch_seconds{tenant, channel, provider}
```

---

## Load Testing

Requires [k6](https://k6.io/docs/get-started/installation/).

```bash
# Baseline — steady PAID + FREE traffic
k6 run -e SCENARIO=baseline load-test/notification_load_test.js

# Full — includes a noisy FREE burst at t=60s to prove PAID isolation
k6 run -e SCENARIO=full load-test/notification_load_test.js

# Stream metrics live into Prometheus
k6 run --out experimental-prometheus-rw \
       -e K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
       -e SCENARIO=full \
       load-test/notification_load_test.js
```

**What the load test proves:**
- PAID tenants maintain p99 < 500ms even when FREE tenants burst to 500 VUs
- Per-tenant rate limiter caps FREE at 10 msg/s, PAID at 100 msg/s
- No messages are dropped — throttled messages are requeued and eventually processed

---

## Configuration Reference

All values can be overridden via environment variables.

| Property | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka/Redpanda address |
| `REDIS_HOST` | `localhost` | Redis host |
| `AWS_REGION` | `us-east-1` | AWS region for SES |
| `TWILIO_FROM_NUMBER` | `+10000000000` | Twilio sender number |
| `WEBHOOK_SECRET` | _(blank)_ | HMAC secret; blank = skip verification in dev |
| `rate-limit.paid-per-window` | `100` | PAID tenant msg/s limit |
| `rate-limit.free-per-window` | `10` | FREE tenant msg/s limit |
| `rate-limit.window-seconds` | `1` | Rate limit window size |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | Jaeger OTLP endpoint |

---

## Running Tests

```bash
mvn test
```

Tests use Testcontainers — Docker must be running. No external services needed.

**Test coverage includes:**
- Spring context loads correctly
- Rate limiter allows exactly N requests per window
- Rate limiter resets after window expiry
- End-to-end: 100 messages published → all eventually reach `SENT` status

---

## Production Considerations

- **Credentials:** Leave `AWS_ACCESS_KEY_ID` blank in production — the app automatically uses the IAM task/instance role via `DefaultCredentialsProvider`
- **Webhook secret:** Always set `WEBHOOK_SECRET` in production to prevent spoofed delivery callbacks
- **Tracing sampling:** Set `management.tracing.sampling.probability=0.1` in prod (currently 100% for dev)
- **Kafka replicas:** Increase topic replicas from 1 to 3 for production clusters
- **DLQ replay:** Consume `dlq-notifications` topic and re-POST to `/api/v1/notifications` with the same idempotency key
- **Tenant tier cache:** `RateLimiterService` caches tier in-memory; call `evictTierCache(tenantId)` if a tenant's tier changes at runtime
