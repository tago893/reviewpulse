"""Write-to-searchable delay, measured from the client.

Creates N reviews through POST /api/reviews at a fixed rate and, for each, polls GET /api/reviews/{id}
until `searchable` is true. The client-observed delay includes HTTP, the outbox poll interval (100 ms),
Kafka, indexing and the polling granularity (20 ms). Also records the server-side commit->indexed
percentiles from /api/metrics/summary for comparison.

  python benchmarks/freshness.py --count 200 --rate 5 --label freshness-100k
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = "http://127.0.0.1:8080"


def call(method: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode("utf-8") if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def one(i: int, run_tag: str) -> float:
    start = time.perf_counter()
    created = call("POST", "/api/reviews", {"parentAsin": "B0FRESH001", "title": f"fresh {run_tag} {i}",
                                            "body": f"freshness probe {run_tag}x{i}", "rating": 4})
    review_id = created["data"]["id"]
    deadline = start + 30
    while time.perf_counter() < deadline:
        if call("GET", f"/api/reviews/{review_id}")["data"]["searchable"]:
            return (time.perf_counter() - start) * 1000
        time.sleep(0.02)
    raise RuntimeError(f"review {review_id} not searchable within 30 s")


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(p * (len(ordered) - 1)))]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=200)
    parser.add_argument("--rate", type=float, default=5.0)
    parser.add_argument("--label", default="freshness")
    args = parser.parse_args()
    run_tag = f"r{int(time.time())}"
    delays: list[float] = []
    with ThreadPoolExecutor(max_workers=32) as pool:
        futures = []
        for i in range(args.count):
            futures.append(pool.submit(one, i, run_tag))
            time.sleep(1.0 / args.rate)
        delays = [f.result() for f in futures]
    server = call("GET", "/api/metrics/summary")["data"]["indexing"]
    result = {
        "label": args.label, "count": args.count, "ratePerSecond": args.rate,
        "clientObservedMillis": {"p50": round(percentile(delays, 0.50), 1), "p95": round(percentile(delays, 0.95), 1),
                                 "p99": round(percentile(delays, 0.99), 1), "max": round(max(delays), 1),
                                 "mean": round(statistics.mean(delays), 1)},
        "serverCommitToIndexedMillis": {"p50": server["delayP50Millis"], "p95": server["delayP95Millis"],
                                        "p99": server["delayP99Millis"]},
        "under10Seconds": sum(1 for d in delays if d < 10_000) / len(delays),
    }
    out = Path(__file__).resolve().parent / "results" / f"{args.label}.json"
    out.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
