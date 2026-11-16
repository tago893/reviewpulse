package com.reviewpulse.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.reviewpulse.api.RequestValidationException;
import com.reviewpulse.caching.SearchResultCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.reviewpulse.config.ReviewPulseProperties;
import com.reviewpulse.observability.IndexerProgress;
import com.reviewpulse.reviews.Review;
import com.reviewpulse.reviews.ReviewRepository;
import com.reviewpulse.search.highlight.Highlighter;
import com.reviewpulse.search.index.SearchIndex;
import com.reviewpulse.search.query.CursorCodec;
import com.reviewpulse.search.query.Hit;
import com.reviewpulse.search.query.QueryExecutor;
import com.reviewpulse.search.query.SearchPage;
import com.reviewpulse.search.query.SearchQuery;
import com.reviewpulse.search.ranking.Bm25Parameters;
import com.reviewpulse.search.tokenizer.Tokenizer;

/**
 * Search use case: validate, rank with the custom index, hydrate from PostgreSQL, highlight.
 *
 * <p>Hydration is what keeps results honest while the index is eventually consistent: a hit whose
 * review is deleted in PostgreSQL but not yet in the index is dropped (and counted in
 * {@code staleHitsDropped}), and displayed fields always come from the authoritative row.
 */
@Service
public class SearchService {

    private final SearchIndex index;
    private final IndexStatus status;
    private final ReviewRepository reviews;
    private final Tokenizer tokenizer;
    private final Highlighter highlighter;
    private final Bm25Parameters bm25;
    private final ReviewPulseProperties.Search config;
    private final IndexerProgress progress;
    private final SearchResultCache cache;
    private final Timer searchTimer;
    private final Timer indexTimer;

    public SearchService(SearchIndex index, IndexStatus status, ReviewRepository reviews, Tokenizer tokenizer,
                         Bm25Parameters bm25, ReviewPulseProperties properties, IndexerProgress progress,
                         SearchResultCache cache, MeterRegistry registry) {
        this.progress = progress;
        this.cache = cache;
        this.searchTimer = Timer.builder("reviewpulse.search.latency")
                .description("end-to-end search time: validation, index (or cache), hydration, highlighting")
                .publishPercentiles(0.5, 0.95, 0.99).register(registry);
        this.indexTimer = Timer.builder("reviewpulse.search.index_latency")
                .description("time spent ranking in the index or reading the cache")
                .publishPercentiles(0.5, 0.95, 0.99).register(registry);
        this.index = index;
        this.status = status;
        this.reviews = reviews;
        this.tokenizer = tokenizer;
        this.highlighter = new Highlighter(tokenizer);
        this.bm25 = bm25;
        this.config = properties.search();
    }

    /** Payload plus metadata for the response envelope. */
    public record Outcome(SearchResponse response, Map<String, Object> meta) {
    }

    public Outcome search(SearchRequest request) {
        long started = System.nanoTime();
        SearchRequest.Valid valid = request.validate(config.defaultPageSize());
        IndexStatus.Snapshot indexStatus = status.snapshot();
        if (!indexStatus.state().servesQueries() || !indexStatus.hasServedIndex()) {
            throw new IndexUnavailableException("search index is not available: " + indexStatus.detail());
        }
        List<String> terms = tokenizer.queryTerms(valid.q());
        SearchQuery query = new SearchQuery(clampTerms(terms), valid.operator(), valid.filters(), valid.sort(),
                valid.pageSize(), null);
        if (valid.cursor() != null) {
            query = query.withAfter(CursorCodec.decode(valid.cursor(), query));
        }
        SearchQuery finalQuery = query;
        long indexStart = System.nanoTime();
        long generationNow = index.generation();
        SearchResultCache.Lookup lookup = cache.get(SearchResultCache.key(finalQuery, generationNow));
        IndexedPage indexed;
        if (lookup.page().isPresent()) {
            indexed = new IndexedPage(lookup.page().get(), generationNow);
        } else {
            // The generation is read under the same read lock as the query, so the cache entry is keyed by
            // exactly the index state that produced it.
            indexed = index.read(idx -> new IndexedPage(
                    QueryExecutor.execute(idx, finalQuery, bm25, config.queryBudgetMillis()), index.generation()));
            if (lookup.outcome() == SearchResultCache.Outcome.MISS) {
                cache.put(SearchResultCache.key(finalQuery, indexed.generation()), indexed.page());
            }
        }
        long indexNanos = System.nanoTime() - indexStart;
        long indexMicros = indexNanos / 1_000;
        indexTimer.record(indexNanos, TimeUnit.NANOSECONDS);

        Hydrated hydrated = hydrate(indexed.page().hits(), new HashSet<>(terms));
        SearchPage page = indexed.page();
        String next = page.hasMore() && !page.hits().isEmpty()
                ? CursorCodec.encode(finalQuery, page.hits().getLast().sortKey()) : null;
        SearchResponse response = new SearchResponse(echo(valid, terms), page.totalHits(), hydrated.results(),
                next, page.hasMore());

        long totalNanos = System.nanoTime() - started;
        searchTimer.record(totalNanos, TimeUnit.NANOSECONDS);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tookMicros", totalNanos / 1_000);
        meta.put("indexMicros", indexMicros);
        meta.put("cache", lookup.outcome().name());
        meta.put("staleHitsDropped", hydrated.dropped());
        meta.put("indexState", indexStatus.state().name());
        meta.put("indexGeneration", indexed.generation());
        meta.put("indexLagEvents", progress.lagEvents());
        meta.put("possiblyIncomplete", indexStatus.state() != IndexState.LIVE || progress.lagEvents() != 0);
        if (terms.isEmpty()) {
            meta.put("warning", "query contains no searchable terms (only stop words or punctuation)");
        }
        return new Outcome(response, meta);
    }

    private record IndexedPage(SearchPage page, long generation) {
    }

    private record Hydrated(List<SearchResponse.Result> results, int dropped) {
    }

    private static List<String> clampTerms(List<String> terms) {
        if (terms.size() > SearchQuery.MAX_TERMS) {
            throw new RequestValidationException(
                    List.of("query has " + terms.size() + " distinct terms; at most " + SearchQuery.MAX_TERMS + " are allowed"));
        }
        return terms;
    }

    private Hydrated hydrate(List<Hit> hits, Set<String> terms) {
        Map<Long, Review> byId = reviews.findByIds(hits.stream().map(Hit::reviewId).toList()).stream()
                .collect(Collectors.toMap(Review::id, Function.identity()));
        List<SearchResponse.Result> results = new ArrayList<>(hits.size());
        int dropped = 0;
        for (Hit hit : hits) {
            Review review = byId.get(hit.reviewId());
            if (review == null || review.deleted()) {
                dropped++;
                continue;
            }
            results.add(new SearchResponse.Result(Long.toString(review.id()), review.parentAsin(), review.rating(),
                    review.helpfulVotes(), review.verifiedPurchase(), review.reviewedAt(), review.version(),
                    hit.version(), hit.score(), highlighter.highlightAll(review.title(), terms),
                    highlighter.snippet(review.body(), terms)));
        }
        return new Hydrated(results, dropped);
    }

    private static SearchResponse.QueryEcho echo(SearchRequest.Valid valid, List<String> terms) {
        return new SearchResponse.QueryEcho(valid.q(), terms, valid.operator().name(), valid.sort().name(),
                valid.filters().ratingMin(), valid.filters().ratingMax(), valid.filters().parentAsin(),
                valid.filters().verifiedPurchase(), valid.pageSize());
    }
}
