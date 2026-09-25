/**
 * k6 load test — notification-service fair multi-tenant scheduling
 *
 * HOW TO RUN
 * ──────────
 * # Baseline (no noisy burst) — capture BEFORE screenshots
 *   k6 run -e SCENARIO=baseline load-test/notification_load_test.js
 *
 * # Full test with noisy FREE burst — capture AFTER screenshots
 *   k6 run -e SCENARIO=full load-test/notification_load_test.js
 *
 * # Stream metrics to Prometheus via remote-write (recommended for Grafana)
 *   k6 run --out experimental-prometheus-rw \
 *          -e K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
 *          -e SCENARIO=full \
 *          load-test/notification_load_test.js
 *
 * THROUGHPUT MATH
 * ───────────────
 * PAID:  200 VUs, sleep=0ms  → each VU does ~50 req/s (limited by server RTT ~20ms)
 *        200 × 50 = ~10 000 req/s peak from PAID alone
 * FREE:  100 VUs, sleep=10ms → 100 × ~40 req/s = ~4 000 req/s steady
 * NOISY: 500 VUs, sleep=0ms  → ~25 000 req/s attempted; rate limiter caps at 10/s per tenant
 *        Combined peak: ~10 000 accepted msg/s (PAID) + throttled FREE
 *
 * TENANT SETUP (seeded by V5 migration)
 * ──────────────────────────────────────
 * tenant-paid-01 … tenant-paid-25  (PAID tier, 100 msg/s limit each)
 * tenant-free-01 … tenant-free-25  (FREE tier, 10 msg/s limit each)
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

// ── Custom metrics ────────────────────────────────────────────────────────────
const sentPaid    = new Counter("notifications_sent_paid");
const sentFree    = new Counter("notifications_sent_free");
const failedPaid  = new Counter("notifications_failed_paid");
const failedFree  = new Counter("notifications_failed_free");
const paidLatency = new Trend("paid_request_duration_ms", true);
const freeLatency = new Trend("free_request_duration_ms", true);
const successRate = new Rate("success_rate");

const SCENARIO = __ENV.SCENARIO || "full";
const BASE_URL  = __ENV.BASE_URL  || "http://localhost:8080";

// ── Scenario definitions ──────────────────────────────────────────────────────

const BASELINE_SCENARIOS = {
  paid_steady: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: "20s", target: 200 },
      { duration: "2m",  target: 200 },
      { duration: "10s", target: 0   },
    ],
    exec: "paidScenario",
    tags: { scenario: "paid_steady" },
  },
  free_steady: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: "20s", target: 100 },
      { duration: "2m",  target: 100 },
      { duration: "10s", target: 0   },
    ],
    exec: "freeScenario",
    tags: { scenario: "free_steady" },
  },
};

const FULL_SCENARIOS = {
  ...BASELINE_SCENARIOS,
  // Noisy FREE burst fires at t=60s — this is the starvation attempt
  free_noisy_burst: {
    executor: "ramping-vus",
    startVUs: 0,
    startTime: "60s",
    stages: [
      { duration: "5s",  target: 500 },  // sudden spike
      { duration: "90s", target: 500 },  // sustained burst
      { duration: "5s",  target: 0   },
    ],
    exec: "noisyFreeScenario",
    tags: { scenario: "free_noisy_burst" },
  },
};

export const options = {
  scenarios: SCENARIO === "baseline" ? BASELINE_SCENARIOS : FULL_SCENARIOS,

  thresholds: {
    // PAID p99 must stay under 500ms even during FREE burst — this is the SLA
    paid_request_duration_ms: ["p(99)<500", "p(95)<200"],
    // FREE can be slower but must not hard-error (nacks are transparent to the client)
    free_request_duration_ms: ["p(99)<2000"],
    success_rate:   ["rate>0.99"],
    http_req_failed: ["rate<0.01"],
  },
};

// ── Helpers ───────────────────────────────────────────────────────────────────

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function pad(n) {
  return n < 10 ? "0" + n : "" + n;
}

function sendNotification(tenantId, isPaid) {
  const payload = JSON.stringify({
    tenantId,
    channel:    "EMAIL",
    recipient:  `user-${randomInt(1, 100000)}@example.com`,
    templateId: Math.random() > 0.5 ? "welcome" : "otp",
    data: {
      name:    "LoadUser",
      appName: "NotifApp",
      code:    String(randomInt(100000, 999999)),
    },
    // Unique key per request — prevents idempotency dedup from masking failures
    idempotencyKey: `${tenantId}-${Date.now()}-${randomInt(1, 1_000_000)}`,
  });

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/notifications`, payload, {
    headers: { "Content-Type": "application/json" },
    tags:    { tenant: tenantId, tier: isPaid ? "paid" : "free" },
    timeout: "5s",
  });
  const duration = Date.now() - start;

  const ok = check(res, { "status 202": (r) => r.status === 202 });
  successRate.add(ok);

  if (isPaid) {
    paidLatency.add(duration);
    ok ? sentPaid.add(1) : failedPaid.add(1);
  } else {
    freeLatency.add(duration);
    ok ? sentFree.add(1) : failedFree.add(1);
  }
}

// ── Scenario executors ────────────────────────────────────────────────────────

export function paidScenario() {
  sendNotification(`tenant-paid-${pad(randomInt(1, 25))}`, true);
  // No sleep — let server RTT naturally throttle; 200 VUs × ~50 rps = ~10k msg/s
}

export function freeScenario() {
  sendNotification(`tenant-free-${pad(randomInt(1, 25))}`, false);
  sleep(0.01); // 100 VUs × ~40 rps = ~4k msg/s steady
}

export function noisyFreeScenario() {
  // Concentrated on tenants 01-05 to trigger per-tenant rate limiter clearly
  sendNotification(`tenant-free-${pad(randomInt(1, 5))}`, false);
  // No sleep — maximum pressure to prove starvation prevention
}

// ── End-of-test summary ───────────────────────────────────────────────────────

export function handleSummary(data) {
  const m = data.metrics;
  const paidP99  = m.paid_request_duration_ms?.values?.["p(99)"]?.toFixed(1) ?? "N/A";
  const paidP95  = m.paid_request_duration_ms?.values?.["p(95)"]?.toFixed(1) ?? "N/A";
  const freeP99  = m.free_request_duration_ms?.values?.["p(99)"]?.toFixed(1) ?? "N/A";
  const paidSent = m.notifications_sent_paid?.values?.count ?? 0;
  const freeSent = m.notifications_sent_free?.values?.count ?? 0;
  const sr       = ((m.success_rate?.values?.rate ?? 0) * 100).toFixed(2);

  const lines = [
    "",
    "════════════════════════════════════════════════",
    `  Scenario:        ${SCENARIO}`,
    "════════════════════════════════════════════════",
    `  PAID sent:       ${paidSent}`,
    `  FREE sent:       ${freeSent}`,
    `  PAID p95 / p99:  ${paidP95} ms / ${paidP99} ms`,
    `  FREE p99:        ${freeP99} ms`,
    `  Success rate:    ${sr}%`,
    "════════════════════════════════════════════════",
    "",
  ];
  lines.forEach((l) => console.log(l));

  return {
    [`load-test/results-${SCENARIO}.json`]: JSON.stringify(data, null, 2),
  };
}
