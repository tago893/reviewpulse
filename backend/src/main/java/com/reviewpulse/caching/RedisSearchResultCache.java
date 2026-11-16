package com.reviewpulse.caching;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.reviewpulse.search.query.SearchPage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis-backed {@link SearchResultCache} that can never make search fail or slow down for long:
 * every Redis error opens a circuit for {@code bypassMillis}, during which the cache is skipped
 * entirely; command timeouts are short (see spring.data.redis.timeout).
 */
public class RedisSearchResultCache implements SearchResultCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSearchResultCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;
    private final long bypassMillis;
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter errorCounter;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong bypassed = new AtomicLong();
    private volatile long circuitOpenUntil;

    public RedisSearchResultCache(StringRedisTemplate redis, JsonMapper json, Duration ttl, long bypassMillis,
                                  MeterRegistry registry) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
        this.bypassMillis = bypassMillis;
        this.hitCounter = Counter.builder("reviewpulse.cache.lookups").tag("result", "hit").register(registry);
        this.missCounter = Counter.builder("reviewpulse.cache.lookups").tag("result", "miss").register(registry);
        this.errorCounter = Counter.builder("reviewpulse.cache.errors").register(registry);
    }

    @Override
    public Lookup get(String key) {
        if (circuitOpen()) {
            bypassed.incrementAndGet();
            return new Lookup(Outcome.BYPASSED, Optional.empty());
        }
        try {
            String value = redis.opsForValue().get(key);
            if (value == null) {
                misses.incrementAndGet();
                missCounter.increment();
                return new Lookup(Outcome.MISS, Optional.empty());
            }
            SearchPage page = json.readValue(value, SearchPage.class);
            hits.incrementAndGet();
            hitCounter.increment();
            return new Lookup(Outcome.HIT, Optional.of(page));
        } catch (RuntimeException e) {
            fail(e);
            return new Lookup(Outcome.ERROR, Optional.empty());
        }
    }

    @Override
    public void put(String key, SearchPage page) {
        if (circuitOpen()) {
            return;
        }
        try {
            redis.opsForValue().set(key, json.writeValueAsString(page), ttl);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private boolean circuitOpen() {
        return System.currentTimeMillis() < circuitOpenUntil;
    }

    private void fail(RuntimeException e) {
        errors.incrementAndGet();
        errorCounter.increment();
        circuitOpenUntil = System.currentTimeMillis() + bypassMillis;
        log.warn("Redis cache unavailable, bypassing for {} ms: {}", bypassMillis, e.getMessage());
    }

    @Override
    public Stats stats() {
        return new Stats(true, hits.get(), misses.get(), errors.get(), bypassed.get(), circuitOpen());
    }
}
