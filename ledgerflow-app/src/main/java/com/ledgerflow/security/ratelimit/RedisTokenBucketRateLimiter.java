package com.ledgerflow.security.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Per-key token bucket stored in Redis (see {@code ratelimit/token_bucket.lua}).
 *
 * <p>Design principle 2: Redis is non-critical. It only throttles request volume and never
 * takes part in a money decision. If Redis is slow or down the limiter FAILS OPEN (request
 * allowed), logs, and counts {@code ledgerflow.ratelimit.errors}, because rejecting all payments
 * because a cache is down would be worse than briefly not throttling. Idempotency and balance
 * checks in Postgres still protect money either way.
 */
@Component
public class RedisTokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds, boolean degraded) {}

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script =
            RedisScript.of(new ClassPathResource("ratelimit/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final int capacity;
    private final double refillPerMs;
    private final Counter rejected;
    private final Counter errors;

    public RedisTokenBucketRateLimiter(
            StringRedisTemplate redis,
            MeterRegistry meters,
            @Value("${ledgerflow.ratelimit.payments.capacity:20}") int capacity,
            @Value("${ledgerflow.ratelimit.payments.refill-per-minute:20}") int refillPerMinute
    ) {
        if (capacity < 1 || refillPerMinute < 1) {
            throw new IllegalArgumentException("rate limit capacity and refill must be >= 1");
        }
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerMs = refillPerMinute / 60_000.0;
        this.rejected = Counter.builder("ledgerflow.ratelimit.rejected").description("Requests rejected with 429").register(meters);
        this.errors = Counter.builder("ledgerflow.ratelimit.errors").description("Redis failures; request allowed (fail-open)").register(meters);
    }

    public int capacity() {
        return capacity;
    }

    public Decision tryConsume(String key) {
        try {
            List<?> r = redis.execute(script, List.of("ratelimit:payments:" + key),
                    String.valueOf(capacity), String.valueOf(refillPerMs));
            boolean allowed = ((Number) r.get(0)).longValue() == 1L;
            long remaining = ((Number) r.get(1)).longValue();
            long retrySeconds = (long) Math.ceil(((Number) r.get(2)).longValue() / 1000.0);
            if (!allowed) rejected.increment();
            return new Decision(allowed, remaining, Math.max(retrySeconds, allowed ? 0 : 1), false);
        } catch (RuntimeException e) {
            errors.increment();
            log.warn("Rate limiter unavailable, allowing request (fail-open): {}", e.toString());
            return new Decision(true, capacity, 0, true);
        }
    }
}
