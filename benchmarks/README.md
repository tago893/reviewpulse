# Benchmarks

* `../backend/src/test/java/com/reviewpulse/bench/IndexBenchmark.java`: in-process index vs PostgreSQL
  scan on identical rows.
* `k6/search-load.js`: end-to-end HTTP load (mixed / popular / cold).
* `freshness.py`: write → searchable delay.
* `replay_throughput.py`: indexing throughput and lag under replayed traffic.
* `results/`: raw JSON from the runs reported in `../docs/benchmark-results.md`.

How each one is run and what it measures: `../docs/benchmark-methodology.md`.
