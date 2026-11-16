package com.reviewpulse.caching;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.reviewpulse.config.ReviewPulseProperties;
import com.reviewpulse.search.query.SearchPage;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class CacheConfig {

    @Bean
    SearchResultCache searchResultCache(ReviewPulseProperties properties, ObjectProvider<StringRedisTemplate> redis,
                                        JsonMapper json, MeterRegistry registry) {
        ReviewPulseProperties.Cache cache = properties.cache();
        if (!cache.enabled()) {
            return new DisabledCache();
        }
        return new RedisSearchResultCache(redis.getObject(), json, Duration.ofSeconds(cache.ttlSeconds()),
                cache.bypassMillis(), registry);
    }

    /** Used when caching is switched off: every lookup reports DISABLED. */
    static final class DisabledCache implements SearchResultCache {
        @Override
        public Lookup get(String key) {
            return new Lookup(Outcome.DISABLED, Optional.empty());
        }

        @Override
        public void put(String key, SearchPage page) {
            // caching disabled
        }

        @Override
        public Stats stats() {
            return new Stats(false, 0, 0, 0, 0, false);
        }
    }
}
