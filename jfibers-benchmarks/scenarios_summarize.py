#!/usr/bin/env python3
"""Summarize per-JVM results; percentiles are never pooled across JVMs."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
from statistics import median


def summarize(directory):
    groups = defaultdict(list)
    for raw in json.loads((directory / "results.json").read_text()):
        result = raw.get("result", raw)
        key = (result["scenario"], result.get("tasks", result.get("parameter")), result["backend"])
        groups[key].append(result)
    summary = []
    for (scenario, parameter, backend), records in sorted(groups.items()):
        row = {"scenario": scenario, "parameter": parameter, "backend": backend, "forks": len(records)}
        values = defaultdict(list)
        for record in records:
            for name, value in record.items():
                if isinstance(value, (int, float)):
                    values[name].append(value)
                elif isinstance(value, dict):
                    for metric, number in value.items():
                        if isinstance(number, (int, float)):
                            values[f"{name}.{metric}"].append(number)
            if scenario != "MASS_WAITING":
                values["rejected_percent"].append(100 * record["rejected"] / record["offered"])
                values["timeout_percent_of_accepted"].append(100 * record["timed_out"] / record["accepted"])
        row["metrics"] = {name: {"median": median(numbers), "min": min(numbers), "max": max(numbers)}
                          for name, numbers in sorted(values.items())}
        summary.append(row)
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
        print(f"{row['scenario']} {row['parameter']} {row['backend']} (n={row['forks']})")
        names = ["heap_delta_bytes", "rss_waiting_bytes", "idle_cpu_cores", "burst_and_drain_ms",
                 "success_signal_to_finish.p99_ms"] if row["scenario"] == "MASS_WAITING" else [
                     "completed_per_second", "successful_per_second", "successful_latency.p99_ms",
                     "light_latency.p99_ms", "cpu_cores", "allocated_bytes_per_accepted", "gc_millis"]
        if row["scenario"] == "OPEN_LOOP":
            names += ["rejected_percent", "timeout_percent_of_accepted", "queue_delay.p99_ms", "arrival_lag.p99_ms"]
        for name in names:
            if name in row["metrics"]:
                value = row["metrics"][name]
                print(f"  {name}: {value['median']:.3f} [{value['min']:.3f}, {value['max']:.3f}]")


if __name__ == "__main__":
    main()
