package com.notification.service;

import com.notification.domain.Tenant;
import com.notification.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier-aware token-bucket rate limiter using an atomic Redis Lua script.
 *
 * Performance fix: tenant tier is resolved via an in-process ConcurrentHashMap cache.
 * At 10k msg/s the previous approach hit the DB on every message (10k queries/s).
 * Tier is immutable at runtime so a simple unbounded cache is safe; the map grows
 * to at most the number of distinct tenants (50 in load tests, thousands in prod).
 *
 * Lua script runs atomically on Redis — no INCR/EXPIRE race condition possible.
 */
@Service
public class RateLimiterService {

    private static final String LUA_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            end
            if current <= tonumber(ARGV[1]) then
                return 1
            else
                return 0
            end
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    private final int paidLimit;
    private final int freeLimit;
    private final int windowSeconds;
    private final TenantRepository tenantRepo;

    // Tier cache: tenantId → limit value. Populated on first access per tenant.
    private final ConcurrentHashMap<String, Integer> tierCache = new ConcurrentHashMap<>();

    public RateLimiterService(StringRedisTemplate redis,
                               TenantRepository tenantRepo,
                               @Value("${rate-limit.paid-per-window:100}") int paidLimit,
                               @Value("${rate-limit.free-per-window:10}")  int freeLimit,
                               @Value("${rate-limit.window-seconds:1}")    int windowSeconds) {
        this.redis = redis;
        this.tenantRepo = tenantRepo;
        this.paidLimit = paidLimit;
        this.freeLimit = freeLimit;
        this.windowSeconds = windowSeconds;

        this.script = new DefaultRedisScript<>();
        this.script.setScriptText(LUA_SCRIPT);
        this.script.setResultType(Long.class);
    }

    public boolean tryConsume(String tenantId) {
        int limit = tierCache.computeIfAbsent(tenantId, this::resolveLimit);
        long window = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = "rate_limit:" + tenantId + ":" + window;

        Long result = redis.execute(script,
                List.of(key),
                String.valueOf(limit),
                String.valueOf(windowSeconds * 2));

        return Long.valueOf(1L).equals(result);
    }

    /** Evict a tenant from the cache if their tier changes at runtime. */
    public void evictTierCache(String tenantId) {
        tierCache.remove(tenantId);
    }

    private int resolveLimit(String tenantId) {
        return tenantRepo.findByTenantId(tenantId)
                .map(t -> t.getTier() == Tenant.Tier.PAID ? paidLimit : freeLimit)
                .orElse(freeLimit);
    }
}
