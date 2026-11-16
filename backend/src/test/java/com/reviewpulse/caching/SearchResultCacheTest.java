package com.reviewpulse.caching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.reviewpulse.search.query.Operator;
import com.reviewpulse.search.query.SearchFilters;
import com.reviewpulse.search.query.SearchPage;
import com.reviewpulse.search.query.SearchQuery;
import com.reviewpulse.search.query.SortKey;
import com.reviewpulse.search.ranking.SortOrder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class SearchResultCacheTest {

    private final SearchQuery first = new SearchQuery(List.of("dry", "skin"), Operator.AND, SearchFilters.NONE,
            SortOrder.RELEVANCE, 20, null);

    @Test
    void keysSeparateGenerationsPagesAndPageSizes() {
        String k = SearchResultCache.key(first, 7);
        assertThat(k).startsWith("rp:search:v1:7:").isEqualTo(SearchResultCache.key(first, 7));
        assertThat(SearchResultCache.key(first, 8)).isNotEqualTo(k);
        assertThat(SearchResultCache.key(first.withAfter(new SortKey(1.5, 10, 2, 99)), 7)).isNotEqualTo(k);
        assertThat(SearchResultCache.key(new SearchQuery(first.terms(), Operator.AND, SearchFilters.NONE,
                SortOrder.RELEVANCE, 10, null), 7)).isNotEqualTo(k);
    }

    @Test
    void disabledCacheNeverStoresAnything() {
        SearchResultCache cache = new CacheConfig.DisabledCache();
        cache.put("k", SearchPage.EMPTY);
        assertThat(cache.get("k").outcome()).isEqualTo(SearchResultCache.Outcome.DISABLED);
        assertThat(cache.stats().enabled()).isFalse();
        assertThat(cache.stats().hitRate()).isZero();
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisErrorsOpenTheCircuitAndAreCounted() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        RedisSearchResultCache cache = new RedisSearchResultCache(redis, JsonMapper.builder().build(),
                Duration.ofSeconds(60), 60_000, new SimpleMeterRegistry());

        assertThat(cache.get("k").outcome()).isEqualTo(SearchResultCache.Outcome.ERROR);
        assertThat(cache.get("k").outcome()).isEqualTo(SearchResultCache.Outcome.BYPASSED);
        cache.put("k", SearchPage.EMPTY); // skipped while the circuit is open
        assertThat(cache.stats().errors()).isEqualTo(1);
        assertThat(cache.stats().circuitOpen()).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedWriteAlsoOpensTheCircuit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        doThrow(new IllegalStateException("write failed")).when(ops).set(anyString(), anyString(), any(Duration.class));
        RedisSearchResultCache cache = new RedisSearchResultCache(redis, JsonMapper.builder().build(),
                Duration.ofSeconds(60), 60_000, new SimpleMeterRegistry());

        cache.put("k", SearchPage.EMPTY);
        assertThat(cache.stats().circuitOpen()).isTrue();
        assertThat(cache.get("k").outcome()).isEqualTo(SearchResultCache.Outcome.BYPASSED);
    }
}
