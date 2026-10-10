#!/usr/bin/env python3
"""Summarize a run without dropping individual forks from the underlying JSON files."""

import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
from statistics import median


LABELS = {
    "jfibers": "jfibers",
    "virtualThreads": "Virtual threads",
    "completableFuture": "CompletableFuture",
    "JFIBERS": "jfibers",
    "VIRTUAL_THREADS": "Virtual threads",
    "COMPLETABLE_FUTURE": "CompletableFuture",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    environment = json.loads((args.directory / "environment.json").read_text())
    jmh = json.loads((args.directory / "jmh.json").read_text())
    waiting = json.loads((args.directory / "waiting.json").read_text())
    if environment["quick_smoke_only"]:
        print("SMOKE RUN: these numbers are not performance evidence.\n")
    batch_size = environment["batch_size"]
    print(f"Batch completion time includes creating, submitting, and collecting {batch_size} tasks.")
    print("JMH error is the reported 99.9% confidence interval half-width.\n")
    print("| Backend | Source delay, ms | Batch time, us | Allocated bytes/task |")
    print("| --- | ---: | ---: | ---: |")
    for row in sorted(jmh, key=lambda row: (int(row["params"]["delayMillis"]), row["benchmark"])):
        metric = row["primaryMetric"]
        if metric["scoreUnit"] != "us/op":
            raise ValueError("Unexpected JMH unit: " + metric["scoreUnit"])
        backend = LABELS[row["benchmark"].rsplit(".", 1)[-1]]
        score = metric["score"] * batch_size
        error = float(metric["scoreError"]) * batch_size
        spread = f" ± {error:.2f}" if math.isfinite(error) else ""
        allocation = row["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]
        print(f"| {backend} | {row['params']['delayMillis']} | {score:.2f}{spread} | {allocation:.1f} |")
    groups = defaultdict(list)
    for row in waiting:
        groups[(row["tasks"], row["backend"])].append(row)
    print("\nWaiting probes: medians; square brackets show the range across fresh JVMs.\n")
    print("| Backend | Waiting tasks | Extra live heap, MiB | Idle CPU, cores | Cancel source + drain, ms |")
    print("| --- | ---: | ---: | ---: | ---: |")
    for (tasks, backend), rows in sorted(groups.items()):
        values = []
        for key, scale in [("heap_delta_bytes", 1 / 1024**2), ("idle_cpu_cores", 1),
                           ("cancel_source_and_drain_ms", 1)]:
            samples = [row[key] * scale for row in rows]
            values.append(f"{median(samples):.3f} [{min(samples):.3f}, {max(samples):.3f}]")
        print(f"| {LABELS[backend]} | {tasks} | {' | '.join(values)} |")


if __name__ == "__main__":
    main()
