"""Indexing throughput and lag under replayed live traffic.

Starts the replay simulator on a slice at a target rate, samples /api/metrics/summary once per second
until the replay finishes and the backlog drains, and records achieved write rate, max backlog and the
server-side commit->indexed delay percentiles. Target rates are workload settings, not claims.

  python benchmarks/replay_throughput.py --file live-500 --rate 500 --label replay-500
"""

from __future__ import annotations

import argparse
import json
import os
import time
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:8080"


def admin_token() -> str:
    env = Path(__file__).resolve().parents[1] / ".env"
    for line in env.read_text(encoding="utf-8").splitlines():
        if line.startswith("REVIEWPULSE_ADMIN_TOKEN="):
            return line.split("=", 1)[1].strip()
    return os.environ.get("REVIEWPULSE_ADMIN_TOKEN", "")


def call(method: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode("utf-8") if body is not None else None,
                                 headers={"Content-Type": "application/json", "X-Admin-Token": admin_token()})
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))["data"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", required=True)
    parser.add_argument("--rate", type=float, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--writers", type=int, default=1, help="concurrent writer threads in the simulator")
    args = parser.parse_args()

    before = call("GET", "/api/metrics/summary")["indexing"]["eventsConsumed"]
    start = time.perf_counter()
    call("POST", "/api/admin/replay/start", {"file": args.file, "eventsPerSecond": args.rate, "seed": 42,
                                             "writers": args.writers})
    max_backlog = 0
    samples = []
    while True:
        time.sleep(1)
        status = call("GET", "/api/admin/replay/status")
        summary = call("GET", "/api/metrics/summary")
        backlog = max(0, summary["indexing"]["lagEvents"]) + summary["reviews"]["outboxPending"]
        max_backlog = max(max_backlog, backlog)
        samples.append({"t": round(time.perf_counter() - start, 1), "position": status["position"],
                        "achievedRate": status["achievedRate"], "backlog": backlog,
                        "indexEventsPerSecond": summary["indexing"]["eventsPerSecond"]})
        if status["state"] in ("COMPLETED", "CANCELLED") and backlog == 0:
            break
    elapsed = time.perf_counter() - start
    final = call("GET", "/api/metrics/summary")
    status = call("GET", "/api/admin/replay/status")
    consumed = final["indexing"]["eventsConsumed"] - before
    result = {
        "label": args.label, "file": args.file, "targetRate": args.rate, "writers": args.writers,
        "created": status["created"], "skippedExisting": status["skippedExisting"], "failures": status["failures"],
        "elapsedSecondsUntilDrained": round(elapsed, 1),
        "averageEndToEndEventsPerSecond": round(consumed / elapsed, 1),
        "peakAchievedWriteRate": max(s["achievedRate"] for s in samples),
        "maxBacklogEvents": max_backlog,
        "serverCommitToIndexedMillis": {k: final["indexing"][k] for k in ("delayP50Millis", "delayP95Millis", "delayP99Millis")},
        "samples": samples,
    }
    out = Path(__file__).resolve().parent / "results" / f"{args.label}.json"
    out.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "samples"}, indent=2))


if __name__ == "__main__":
    main()
