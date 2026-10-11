#!/usr/bin/env python3
"""Report the median and range of per-JVM HTTP measurements; never pool percentiles."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
from statistics import median


def flatten(value, prefix=""):
    for name, item in value.items():
        key = f"{prefix}.{name}" if prefix else name
        if isinstance(item, dict):
            yield from flatten(item, key)
        elif isinstance(item, (int, float)) and not isinstance(item, bool):
            yield key, item


def summarize(directory):
    groups = defaultdict(list)
    for record in json.loads((directory / "results.json").read_text()):
        if not record.get("validated"):
            raise ValueError("Refusing to summarize incomplete or unvalidated JVM pairs")
        groups[record["rate"], record["heavy_iterations"], record["backend"]].append(record["measurement"]["result"])
    summary = []
    for (rate, iterations, backend), records in sorted(groups.items()):
        values = defaultdict(list)
        for record in records:
            for name, number in flatten(record):
                values[name].append(number)
            offered = record["offered"]
            values["on_time_percent_of_offered"].append(100 * record["on_time_success"] / offered)
            values["late_success_percent_of_offered"].append(100 * record["late_success"] / offered)
            values["server_rejected_percent_of_offered"].append(100 * record["http_status"]["503"] / offered)
            values["timeout_percent_of_offered"].append(100 * record["http_status"]["504"] / offered)
            values["local_rejected_percent_of_offered"].append(100 * record["local_rejected"] / offered)
        summary.append({"rate": rate, "heavy_iterations": iterations, "backend": backend, "forks": len(records),
                        "metrics": {name: {"median": median(numbers), "min": min(numbers), "max": max(numbers),
                                           "samples": len(numbers)}
                                    for name, numbers in sorted(values.items())}})
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    rows = summarize(args.directory)
    if args.json:
        print(json.dumps(rows, indent=2))
        return
    for row in rows:
        print(f"rate={row['rate']} heavy={row['heavy_iterations']} {row['backend']} (n={row['forks']})")
        for name in ["successful_per_second", "goodput_per_second", "on_time_percent_of_offered",
                     "server_rejected_percent_of_offered", "timeout_percent_of_offered", "local_rejected_percent_of_offered",
                     "successful_latency.p99_ms", "light_success_latency.p99_ms", "heavy_success_latency.p99_ms",
                     "arrival_lag.p99_ms", "all_server_queue.p99_ms", "server_cpu_cores", "client_cpu_cores"]:
            if name in row["metrics"]:
                metric = row["metrics"][name]
                print(f"  {name}: {metric['median']:.3f} [{metric['min']:.3f}, {metric['max']:.3f}]")


if __name__ == "__main__":
    main()
