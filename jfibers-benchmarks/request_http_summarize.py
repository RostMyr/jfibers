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
    environment = directory / "environment.json"
    if environment.exists() and json.loads(environment.read_text()).get("profiles_only"):
        raise ValueError("Diagnostic recordings are not performance measurements")
    groups = defaultdict(list)
    for record in json.loads((directory / "results.json").read_text()):
        if record.get("profiled"):
            raise ValueError("Diagnostic recordings are not performance measurements")
        if not record.get("validated"):
            raise ValueError("Refusing to summarize incomplete or unvalidated JVM pairs")
        configuration = {key: record[key] for key in ("wait_mode", "compute_threads", "dependency_delay_ms", "cpu_affinity")
                         if key in record}
        groups[record["rate"], record["heavy_iterations"], record["backend"], record.get("heavy_capacity", -1),
               json.dumps(configuration, sort_keys=True)].append(record["measurement"]["result"])
    summary = []
    for (rate, iterations, backend, heavy_capacity, configuration), records in sorted(groups.items()):
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
            # Preserve the previous summary schema exactly for archived data without this field.
            if heavy_capacity >= 0:
                for kind in ("light", "heavy"):
                    outcomes = record[kind + "_outcomes"]
                    count = outcomes["offered"]
                    if count:
                        for label, numerator in (("on_time", outcomes["on_time_success"]),
                                                 ("late_success", outcomes["late_success"]),
                                                 ("server_rejected", outcomes["503"]),
                                                 ("timeout", outcomes["504"]),
                                                 ("local_rejected", outcomes["local_rejected"]),
                                                 ("heavy_gate_rejected", outcomes["rejected_heavy"]),
                                                 ("global_rejected", outcomes["rejected_global"])):
                            values[f"{kind}_{label}_percent_of_offered"].append(100 * numerator / count)
                    accepted = sum(outcomes[status] for status in ("200", "409", "504", "500"))
                    if accepted:
                        values[f"{kind}_timeout_percent_of_accepted"].append(100 * outcomes["504"] / accepted)
                for terminal, status in (("success", "200"), ("cancelled", "409"), ("timed_out", "504"), ("errors", "500")):
                    completed = record["http_status"][status]
                    if completed:
                        for unit in ("steps", "iterations"):
                            metric = f"server_cpu_{unit}_{terminal}"
                            if metric in record:
                                values[f"mean_cpu_{unit}_per_{terminal}"].append(record[metric] / completed)
                total_iterations = record.get("server_cpu_iterations", 0)
                if total_iterations:
                    unsuccessful = sum(record["server_cpu_iterations_" + terminal]
                                       for terminal in ("cancelled", "timed_out", "errors"))
                    values["non_success_cpu_iteration_percent"].append(100 * unsuccessful / total_iterations)
        row = {"rate": rate, "heavy_iterations": iterations, "backend": backend, "forks": len(records),
               "metrics": {name: {"median": median(numbers), "min": min(numbers), "max": max(numbers),
                                  "samples": len(numbers)} for name, numbers in sorted(values.items())}}
        if heavy_capacity >= 0:
            row["heavy_capacity"] = heavy_capacity
        row.update(json.loads(configuration))
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
        cap = f" heavy_capacity={row['heavy_capacity']}" if "heavy_capacity" in row else ""
        configuration = "" if "wait_mode" not in row else (
            f" wait={row['wait_mode']} compute_threads={row['compute_threads']} delay_ms={row['dependency_delay_ms']}")
        if "cpu_affinity" in row:
            assignments = [f"{role}:{','.join(map(str, cpus))}" for role, cpus in row["cpu_affinity"].items() if cpus is not None]
            configuration += " cpus=" + ("/".join(assignments) if assignments else "inherited")
        print(f"rate={row['rate']} heavy={row['heavy_iterations']} {row['backend']}{cap}{configuration} (n={row['forks']})")
        for name in ["successful_per_second", "goodput_per_second", "on_time_percent_of_offered",
                     "server_rejected_percent_of_offered", "timeout_percent_of_offered", "local_rejected_percent_of_offered",
                     "successful_latency.p99_ms", "light_success_latency.p99_ms", "heavy_success_latency.p99_ms",
                     "arrival_lag.p99_ms", "all_server_queue.p99_ms", "server_cpu_cores", "client_cpu_cores",
                     "light_on_time_percent_of_offered", "heavy_on_time_percent_of_offered",
                     "heavy_server_rejected_percent_of_offered", "heavy_timeout_percent_of_accepted",
                     "mean_cpu_steps_per_timed_out", "mean_cpu_iterations_per_timed_out",
                     "non_success_cpu_iteration_percent", "server_downstream_completed", "dependency_cpu_cores"]:
            if name in row["metrics"]:
                metric = row["metrics"][name]
                print(f"  {name}: {metric['median']:.3f} [{metric['min']:.3f}, {metric['max']:.3f}]")


if __name__ == "__main__":
    main()
