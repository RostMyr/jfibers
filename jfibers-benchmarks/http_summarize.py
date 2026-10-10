#!/usr/bin/env python3
"""Print HTTP medians and observed ranges; profiled trials are deliberately excluded."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
from statistics import median


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    metadata = json.loads((args.directory / "environment.json").read_text())
    if metadata["quick_smoke_only"]:
        print("SMOKE RUN ONLY: these timings are not performance evidence.\n")
    if "finished_utc" not in metadata:
        print("INCOMPLETE RUN: some scenarios or profiles may be missing.\n")
    groups = defaultdict(list)
    for row in json.loads((args.directory / "http.json").read_text()):
        if row["profiled"]:
            raise ValueError("Profiled runs must be kept out of the performance dataset")
        groups[row["delay_ms"], row["concurrency"], row["backend"], row.get("variant", "bundled")].append(row)

    def metric(rows, field, scale=1):
        values = [row[field] * scale for row in rows]
        return f"{median(values):.2f} [{min(values):.2f}, {max(values):.2f}]"

    print("Medians [min, max] across fresh JVMs. Operation = two sequential HTTP requests.")
    print("p95/p99: median of each run's histogram upper bound, not pooled percentiles.\n")
    print("| Delay ms | Clients | Backend / runtime | Runs | Ops/s | p95 ms | p99 ms | Client CPU cores | Server CPU cores | Sampled mean heap MiB | Errors |")
    print("| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for (delay, concurrency, backend, variant), rows in sorted(groups.items()):
        values = [metric(rows, field, scale) for field, scale in (
            ("successful_operations_per_second", 1), ("latency_p95_upper_ms", 1),
            ("latency_p99_upper_ms", 1), ("client_cpu_cores", 1), ("server_cpu_cores", 1),
            ("heap_mean_sampled_bytes", 1 / 1024 ** 2))]
        print(f"| {delay} | {concurrency} | {backend} / {variant} | {len(rows)} | " + " | ".join(values)
              + f" | {sum(row['errors'] + row['server_errors'] for row in rows)} |")


if __name__ == "__main__":
    main()
