# Dataset

## Source

| | |
|---|---|
| Dataset | Amazon Reviews 2023, McAuley Lab (UC San Diego) |
| Hub page | https://huggingface.co/datasets/McAuley-Lab/Amazon-Reviews-2023 |
| Category used | `raw_review_All_Beauty` |
| Pinned revision | `269765acc4057f11566d9e16106d109409e4c7c8` |
| File | `raw_review_All_Beauty/full-00000-of-00001.parquet` |
| Size / SHA-256 | 139,089,758 bytes / `b40ae565f1073e0b4960e2f424fe0c82f3d270b7c4d00bc9272ddd6a8b7411d7` |
| Rows | 701,528 (702 Parquet row groups) |

The checksum equals the Hub's `X-Linked-ETag` for the file and is verified by `data-tools` on every
download and before every import. The Parquet file is downloaded directly; the dataset's remote Python
loading script is never executed.

## Terms of use and attribution

The dataset card at the pinned revision **declares no license** (no `license:` field, no license tag in the
Hub metadata). Review texts are written by Amazon customers. This project therefore:

* uses the data only for non-commercial research and education,
* never commits it (or any derived file) to Git; `data/` is ignored,
* never redistributes it; the README tells users to download it from the source,
* does not store the reviewer `user_id`. It is only used, hashed, to derive a deterministic record key,
* does not show reviewer identifiers in logs, screenshots or the UI.

Citation requested by the authors:

```bibtex
@article{hou2024bridging,
  title={Bridging Language and Items for Retrieval and Recommendation},
  author={Hou, Yupeng and Li, Jiacheng and He, Zhankui and Yan, An and Chen, Xiusi and McAuley, Julian},
  journal={arXiv preprint arXiv:2403.03952},
  year={2024}
}
```

## Preparation (`data-tools/`)

| Step | Rule |
|---|---|
| Streaming | `pyarrow.ParquetFile.iter_batches(batch_size)`; only one batch in memory; the `images` column is never read |
| Text cleanup | `<br />` → newline, other real HTML tags removed (but `I <3 this` survives), entities unescaped, NFC, C0 control characters removed (PostgreSQL rejects NUL) |
| Timestamps | epoch milliseconds; values < 1e11 are treated as seconds and converted (counted); outside 1995–2030 rejected |
| Validation | rating integral 1–5; ASINs `^[A-Z0-9]{10}$`; helpful votes 0–10M; verified flag boolean; title ≤ 500 and body ≤ 32,000 chars; not both empty |
| Identity | `source_key = sha256("All_Beauty|user_id|asin|timestamp_ms")`, `id = (top 62 bits) | 2^62`; API-created ids come from a sequence below 2^62, so ranges never collide |
| Duplicates | same source key = same review: dropped within a batch, `ON CONFLICT DO NOTHING` across batches and reruns |
| Collisions | a different source key hashing to an existing id is rejected with reason `id_collision` (never silently merged) |
| Reporting | processed / accepted / duplicates / rejected (+ reasons) per run, in `import_runs` and `data/import-stats/` |

Measured on this machine for the first 100,000 rows: 89,691 accepted on top of the 9,987 already imported
from the first 10,000 rows, 10,309 duplicates in total (9,987 from the earlier run + 322 true duplicates in
the source), 0 rejected, 31.8 s. A full re-run of the same import inserts nothing.

## Subsets

* **Bulk** (`import --limit N`): the first N rows in file order. The first rows already span many products
  (1,677 distinct parent ASINs in the first 2,000 rows).
* **Replay slices** (`export-replay --offset O --limit L`): rows *after* the bulk-imported range, validated,
  then sorted by original timestamp, so the simulator can play them as "new" arrivals.
* **Benchmarks** copy a deterministic pseudo-random sample (ordered by hash-derived id) into
  `bench_reviews_<n>` tables so the index and the SQL baseline see identical rows.

Synthetic text is used only in unit/integration test fixtures, never for benchmarks.
