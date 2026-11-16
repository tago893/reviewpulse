package com.reviewpulse.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.reviewpulse.search.SnapshotStore;
import com.reviewpulse.search.index.IndexSnapshotCodec;
import com.reviewpulse.search.index.IndexedReview;
import com.reviewpulse.search.index.InvertedIndex;
import com.reviewpulse.search.query.Operator;
import com.reviewpulse.search.query.QueryExecutor;
import com.reviewpulse.search.query.SearchFilters;
import com.reviewpulse.search.query.SearchPage;
import com.reviewpulse.search.query.SearchQuery;
import com.reviewpulse.search.ranking.Bm25Parameters;
import com.reviewpulse.search.ranking.SortOrder;
import com.reviewpulse.search.tokenizer.Tokenizer;

import tools.jackson.databind.json.JsonMapper;

/**
 * In-process benchmark of the custom index against a naive PostgreSQL scan on the SAME rows.
 * Not a unit test: run explicitly (see benchmarks/README.md). Usage:
 * <pre>java ... IndexBenchmark &lt;sizes,comma-separated|0=all&gt; &lt;outDir&gt;</pre>
 */
public final class IndexBenchmark {

    private static final Pattern ASCII_TERM = Pattern.compile("^[a-z][a-z0-9]{2,}$");
    private static final int QUERIES_PER_CATEGORY = 40;
    private static final int BASELINE_QUERIES_PER_CATEGORY = 8;
    private static final int WARMUP_QUERIES = 3_000;
    private static final int ROUNDS = 5;
    private static final long SEED = 20261115L;

    private record Doc(String title, String body, String parentAsin) {
    }

    private record Bench(String category, SearchQuery query, String sqlWhere, List<Object> sqlParams) {
    }

    public static void main(String[] args) throws Exception {
        List<Integer> sizes = Arrays.stream(args.length > 0 ? args[0].split(",") : new String[] {"10000", "100000"})
                .map(Integer::parseInt).toList();
        Path out = Path.of(args.length > 1 ? args[1] : "../benchmarks/results");
        Files.createDirectories(out);
        String url = env("BENCH_JDBC_URL", "jdbc:postgresql://localhost:15432/reviewpulse");
        try (Connection c = DriverManager.getConnection(url, env("POSTGRES_USER", "reviewpulse"),
                env("POSTGRES_PASSWORD", "reviewpulse-local-only"))) {
            for (int size : sizes) {
                Map<String, Object> result = runSize(c, size);
                String name = "index-benchmark-" + (size == 0 ? "all" : size) + ".json";
                Files.writeString(out.resolve(name), JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result));
                System.out.println("wrote " + out.resolve(name));
            }
        }
    }

    private static Map<String, Object> runSize(Connection c, int size) throws Exception {
        String table = "bench_reviews_" + (size == 0 ? "all" : size);
        prepareTable(c, table, size);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("generatedAt", Instant.now().toString());
        r.put("machine", machine());
        r.put("table", table);

        // ---- build
        long heapBefore = usedHeapAfterGc();
        List<Doc> sample = new ArrayList<>();
        long t0 = System.nanoTime();
        InvertedIndex index = build(c, table, sample);
        long buildMillis = (System.nanoTime() - t0) / 1_000_000;
        long heapAfter = usedHeapAfterGc();
        var stats = index.stats();
        r.put("documents", stats.liveDocs());
        r.put("build", Map.of("millis", buildMillis, "docsPerSecond", Math.round(stats.liveDocs() / (buildMillis / 1000.0)),
                "terms", stats.terms(), "postings", stats.postings(), "products", stats.products(),
                "estimatedIndexBytes", stats.approximateBytes(), "measuredHeapDeltaBytes", heapAfter - heapBefore));

        // ---- checkpoint write / load
        Path dir = Files.createTempDirectory("rp-bench");
        SnapshotStore store = new SnapshotStore(dir, Tokenizer.DEFAULT);
        InvertedIndex compact = index.compact();
        long w0 = System.nanoTime();
        long bytes = store.write(compact, new IndexSnapshotCodec.Header(Tokenizer.FINGERPRINT, "bench", 0,
                System.currentTimeMillis(), 0, Map.of(0, 0L)));
        long writeMillis = (System.nanoTime() - w0) / 1_000_000;
        long l0 = System.nanoTime();
        SnapshotStore.LoadResult loaded = store.load();
        long loadMillis = (System.nanoTime() - l0) / 1_000_000;
        r.put("checkpoint", Map.of("bytes", bytes, "writeMillis", writeMillis, "loadMillis", loadMillis,
                "loadedOk", loaded instanceof SnapshotStore.Found));
        compact = null;
        loaded = null;
        deleteQuietly(dir);

        // ---- workload
        List<Bench> workload = workload(index, sample);
        Map<String, List<Bench>> byCategory = new LinkedHashMap<>();
        workload.forEach(b -> byCategory.computeIfAbsent(b.category(), k -> new ArrayList<>()).add(b));
        warmUp(index, workload);

        Map<String, Object> indexLatency = new LinkedHashMap<>();
        Map<String, Object> indexRecency = new LinkedHashMap<>();
        Map<String, Object> baseline = new LinkedHashMap<>();
        Map<String, Object> baselineIlike = new LinkedHashMap<>();
        Map<String, Object> agreement = new LinkedHashMap<>();
        List<Long> allIndex = new ArrayList<>();
        for (var e : byCategory.entrySet()) {
            List<Long> relevance = new ArrayList<>();
            List<Long> recency = new ArrayList<>();
            long hits = 0;
            for (int round = 0; round < ROUNDS; round++) {
                for (Bench b : e.getValue()) {
                    long s = System.nanoTime();
                    SearchPage page = QueryExecutor.execute(index, b.query(), Bm25Parameters.DEFAULT, 60_000);
                    relevance.add(System.nanoTime() - s);
                    hits += page.totalHits();
                    SearchQuery rq = new SearchQuery(b.query().terms(), b.query().operator(), b.query().filters(),
                            SortOrder.RECENCY, 20, null);
                    long s2 = System.nanoTime();
                    QueryExecutor.execute(index, rq, Bm25Parameters.DEFAULT, 60_000);
                    recency.add(System.nanoTime() - s2);
                }
            }
            allIndex.addAll(relevance);
            Map<String, Object> m = percentiles(relevance);
            m.put("meanTotalHits", hits / (ROUNDS * e.getValue().size()));
            indexLatency.put(e.getKey(), m);
            indexRecency.put(e.getKey(), percentiles(recency));
            baseline.put(e.getKey(), baselineLatency(c, table, e.getValue(), false));
            baselineIlike.put(e.getKey(), baselineLatency(c, table, e.getValue(), true));
            agreement.put(e.getKey(), agreement(c, table, index, e.getValue()));
        }
        r.put("indexRelevanceTop20", indexLatency);
        r.put("indexRecencyTop20", indexRecency);
        r.put("indexAllQueries", percentiles(allIndex));
        r.put("baselineRegexScanRecencyTop20", baseline);
        r.put("baselineIlikeScanRecencyTop20", baselineIlike);
        r.put("matchCountAgreementWithRegexScan", agreement);
        r.put("throughput", throughput(index, workload));
        r.put("workloadExamples", byCategory.entrySet().stream().map(e -> e.getKey() + ": "
                + e.getValue().getFirst().query().operator() + " " + e.getValue().getFirst().query().terms()
                + " " + e.getValue().getFirst().query().filters().canonical()).toList());
        System.out.println(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(r));
        return r;
    }

    // ------------------------------------------------------------------ data

    private static void prepareTable(Connection c, String table, int size) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + table);
            // Rows ordered by the hash-derived id: a deterministic pseudo-random sample of the corpus.
            s.execute("CREATE TABLE " + table + " AS SELECT id, parent_asin, title, body, rating, helpful_votes, "
                    + "verified_purchase, reviewed_at, version FROM reviews WHERE NOT deleted AND source = 'DATASET' ORDER BY id"
                    + (size > 0 ? " LIMIT " + size : ""));
            s.execute("ANALYZE " + table);
        }
    }

    private static InvertedIndex build(Connection c, String table, List<Doc> sample) throws SQLException {
        InvertedIndex index = new InvertedIndex(Tokenizer.DEFAULT);
        Random random = new Random(SEED);
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table + " ORDER BY id")) {
            ps.setFetchSize(2_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    IndexedReview r = new IndexedReview(rs.getLong("id"), rs.getLong("version"), rs.getString("parent_asin"),
                            rs.getString("title"), rs.getString("body"), rs.getInt("rating"), rs.getInt("helpful_votes"),
                            rs.getBoolean("verified_purchase"), rs.getTimestamp("reviewed_at").getTime());
                    index.upsert(r);
                    if (sample.size() < 3_000 && random.nextInt(10) == 0) {
                        sample.add(new Doc(r.title(), r.body(), r.parentAsin()));
                    }
                }
            }
        } finally {
            c.commit();
            c.setAutoCommit(true);
        }
        return index;
    }

    // ------------------------------------------------------------------ workload

    private static List<Bench> workload(InvertedIndex index, List<Doc> sample) {
        Random random = new Random(SEED);
        Map<String, Integer> df = new LinkedHashMap<>();
        for (Doc d : sample) {
            Tokenizer.DEFAULT.forEachTerm(d.title() + " " + d.body(), t -> {
                if (ASCII_TERM.matcher(t).matches()) {
                    df.computeIfAbsent(t, index::documentFrequency);
                }
            });
        }
        List<String> byDf = df.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).map(Map.Entry::getKey).toList();
        int live = index.liveDocCount();
        List<String> common = byDf.subList(0, Math.min(QUERIES_PER_CATEGORY, byDf.size()));
        List<String> rare = byDf.stream().filter(t -> df.get(t) >= 2 && df.get(t) <= Math.max(5, live / 5_000)).toList();
        List<String> mid = byDf.stream().filter(t -> df.get(t) >= live / 1_000 && df.get(t) <= live / 20).toList();

        List<Bench> out = new ArrayList<>();
        for (int i = 0; i < QUERIES_PER_CATEGORY; i++) {
            out.add(bench("common-1-term", List.of(common.get(i % common.size())), Operator.AND, SearchFilters.NONE));
            out.add(bench("rare-1-term", List.of(rare.get(random.nextInt(rare.size()))), Operator.AND, SearchFilters.NONE));
            Doc d = sample.get(random.nextInt(sample.size()));
            List<String> fromDoc = docTerms(d, df, mid, random, 3);
            out.add(bench("and-2-terms", fromDoc.subList(0, 2), Operator.AND, SearchFilters.NONE));
            out.add(bench("and-3-terms", fromDoc, Operator.AND, SearchFilters.NONE));
            out.add(bench("or-3-terms", List.of(mid.get(random.nextInt(mid.size())), mid.get(random.nextInt(mid.size())),
                    mid.get(random.nextInt(mid.size()))), Operator.OR, SearchFilters.NONE));
            out.add(bench("and-2-filtered(rating>=4,verified)", fromDoc.subList(0, 2), Operator.AND,
                    new SearchFilters(4, null, null, true)));
            out.add(bench("selective(product+term)", fromDoc.subList(0, 1), Operator.AND,
                    new SearchFilters(null, null, d.parentAsin(), null)));
            out.add(bench("large-or(top-3-common)", List.of(common.get(0), common.get(1), common.get(2)), Operator.OR,
                    SearchFilters.NONE));
        }
        return out;
    }

    private static List<String> docTerms(Doc d, Map<String, Integer> df, List<String> mid, Random random, int n) {
        Set<String> terms = new HashSet<>();
        Tokenizer.DEFAULT.forEachTerm(d.title() + " " + d.body(), t -> {
            if (df.containsKey(t)) {
                terms.add(t);
            }
        });
        List<String> candidates = new ArrayList<>(terms);
        candidates.sort(String::compareTo);
        List<String> picked = new ArrayList<>();
        while (picked.size() < n && !candidates.isEmpty()) {
            picked.add(candidates.remove(random.nextInt(candidates.size())));
        }
        // Short reviews may not have n eligible terms; pad with distinct mid-frequency terms.
        for (String m : mid) {
            if (picked.size() >= n) {
                break;
            }
            if (!picked.contains(m)) {
                picked.add(m);
            }
        }
        return List.copyOf(picked);
    }

    private static Bench bench(String category, List<String> terms, Operator op, SearchFilters f) {
        SearchQuery q = new SearchQuery(terms, op, f, SortOrder.RELEVANCE, 20, null);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("TRUE");
        if (op == Operator.AND) {
            for (String t : terms) {
                where.append(" AND (title || ' ' || body) ~* ?");
                params.add("\\m" + t + "\\M");
            }
        } else {
            where.append(" AND (title || ' ' || body) ~* ?");
            params.add("\\m(" + String.join("|", terms) + ")\\M");
        }
        if (f.ratingMin() != null) {
            where.append(" AND rating >= ?");
            params.add(f.ratingMin());
        }
        if (f.verifiedPurchase() != null) {
            where.append(" AND verified_purchase = ?");
            params.add(f.verifiedPurchase());
        }
        if (f.parentAsin() != null) {
            where.append(" AND parent_asin = ?");
            params.add(f.parentAsin());
        }
        return new Bench(category, q, where.toString(), params);
    }

    // ------------------------------------------------------------------ measurement

    private static void warmUp(InvertedIndex index, List<Bench> workload) {
        for (int i = 0; i < WARMUP_QUERIES; i++) {
            QueryExecutor.execute(index, workload.get(i % workload.size()).query(), Bm25Parameters.DEFAULT, 60_000);
        }
    }

    private static Map<String, Object> baselineLatency(Connection c, String table, List<Bench> benches, boolean ilike)
            throws SQLException {
        List<Long> nanos = new ArrayList<>();
        for (Bench b : benches.subList(0, Math.min(BASELINE_QUERIES_PER_CATEGORY, benches.size()))) {
            String where = b.sqlWhere();
            List<Object> params = b.sqlParams();
            if (ilike) {
                where = where.replace("(title || ' ' || body) ~* ?", "(title ILIKE ? OR body ILIKE ?)");
                List<Object> p2 = new ArrayList<>();
                for (Object o : params) {
                    if (o instanceof String s && s.startsWith("\\m")) {
                        String term = s.substring(2, s.length() - 2).replace("(", "").replace(")", "").split("\\|")[0];
                        p2.add("%" + term + "%");
                        p2.add("%" + term + "%");
                    } else {
                        p2.add(o);
                    }
                }
                params = p2;
            }
            String sql = "SELECT id FROM " + table + " WHERE " + where + " ORDER BY reviewed_at DESC, id LIMIT 20";
            for (int run = 0; run < 3; run++) {
                long s = System.nanoTime();
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    bind(ps, params);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getLong(1);
                        }
                    }
                }
                if (run > 0) {
                    nanos.add(System.nanoTime() - s);
                }
            }
        }
        Map<String, Object> m = percentiles(nanos);
        m.put("note", ilike ? "substring semantics (first term only for OR); latency reference only" : "word-boundary regex");
        return m;
    }

    private static Map<String, Object> agreement(Connection c, String table, InvertedIndex index, List<Bench> benches)
            throws SQLException {
        int equal = 0;
        long absDiff = 0;
        int n = Math.min(BASELINE_QUERIES_PER_CATEGORY, benches.size());
        for (Bench b : benches.subList(0, n)) {
            long indexCount = QueryExecutor.execute(index, b.query(), Bm25Parameters.DEFAULT, 60_000).totalHits();
            long sqlCount;
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + b.sqlWhere())) {
                bind(ps, b.sqlParams());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    sqlCount = rs.getLong(1);
                }
            }
            if (indexCount == sqlCount) {
                equal++;
            }
            absDiff += Math.abs(indexCount - sqlCount);
        }
        return Map.of("queries", n, "identicalCounts", equal, "meanAbsoluteDifference", n == 0 ? 0 : (double) absDiff / n);
    }

    private static Map<String, Object> throughput(InvertedIndex index, List<Bench> workload) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int threads : List.of(1, 4)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong done = new AtomicLong();
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int offset = t * 7;
                fs.add(pool.submit(() -> {
                    int i = offset;
                    while (!stop.get()) {
                        QueryExecutor.execute(index, workload.get(i++ % workload.size()).query(), Bm25Parameters.DEFAULT, 60_000);
                        done.incrementAndGet();
                    }
                }));
            }
            long start = System.nanoTime();
            Thread.sleep(10_000);
            stop.set(true);
            for (Future<?> f : fs) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            double seconds = (System.nanoTime() - start) / 1e9;
            m.put(threads + "-thread-qps", Math.round(done.get() / seconds));
        }
        m.put("note", "mixed workload, all categories round-robin, index only (no HTTP, no hydration)");
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    static Map<String, Object> percentiles(List<Long> nanos) {
        long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", sorted.length);
        if (sorted.length == 0) {
            return m;
        }
        m.put("p50Millis", round(sorted[(int) Math.floor(0.50 * (sorted.length - 1))] / 1e6));
        m.put("p95Millis", round(sorted[(int) Math.floor(0.95 * (sorted.length - 1))] / 1e6));
        m.put("p99Millis", round(sorted[(int) Math.floor(0.99 * (sorted.length - 1))] / 1e6));
        m.put("maxMillis", round(sorted[sorted.length - 1] / 1e6));
        m.put("meanMillis", round(Arrays.stream(sorted).average().orElse(0) / 1e6));
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static Map<String, Object> machine() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        m.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        m.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        m.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        return m;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static void deleteQuietly(Path dir) {
        try (var files = Files.walk(dir)) {
            files.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // temp directory; ignore
        }
    }
}
