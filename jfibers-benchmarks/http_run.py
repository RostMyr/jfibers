#!/usr/bin/env python3
"""Run isolated HTTP client/server JVM pairs; retain measurements and optional JFR profiles."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import selectors
import shutil
import subprocess
import sys
import time

from run import JVM_FLAGS, MODULE, REPOSITORY, capture, read_optional


PACKAGE = "com.github.rostmyr.jfibers.benchmarks."
CLIENT_FLAGS = JVM_FLAGS + ["-Djava.util.concurrent.ForkJoinPool.common.parallelism=4"]
SERVER_FLAGS = [flag.replace("512m", "256m") for flag in CLIENT_FLAGS] + ["-Dsun.net.httpserver.nodelay=true"]
SCENARIOS = [(32, 0), (256, 0), (32, 5), (256, 5)]
BACKENDS = ["JFIBERS", "VIRTUAL_THREADS", "VIRTUAL_THREADS_ASYNC", "COMPLETABLE_FUTURE"]


def parse_variants(specifications):
    variants = []
    for specification in specifications or []:
        label, separator, filename = specification.partition("=")
        path = Path(filename).resolve()
        if not separator or not re.fullmatch(r"[a-zA-Z0-9_-]+", label) or not path.is_file():
            raise ValueError("Variant must be LABEL=/path/to/core.jar with a simple, unique label")
        if any(previous == label for previous, _ in variants):
            raise ValueError(f"Duplicate variant label: {label}")
        variants.append((label, path))
    return variants or [("bundled", None)]


def duration_nanos(value):
    if isinstance(value, (int, float)):
        return value
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?", value)
    if not match:
        raise ValueError(f"Unsupported JFR duration: {value}")
    hours, minutes, seconds = (float(part or 0) for part in match.groups())
    return (hours * 3600 + minutes * 60 + seconds) * 1e9


def fiber_profile(events):
    snapshots = []
    waits = {}
    for event in events:
        values = event["values"]
        if event["type"] == "com.github.rostmyr.jfibers.Scheduler":
            snapshots.append({key: duration_nanos(value) if key.endswith("Nanos") else value
                              for key, value in values.items()
                              if key not in {"startTime", "duration", "eventThread", "stackTrace"}})
        elif event["type"] == "com.github.rostmyr.jfibers.Await":
            group = waits.setdefault(values["waitKind"] + "/" + values["outcome"], [])
            group.append(values)
    summary = {}
    for kind, rows in waits.items():
        summary[kind] = {"samples": len(rows), "latency_us": {}}
        for field in ("observedWaitNanos", "signalToRunnableNanos", "runnableToExecutionNanos", "readyToExecutionNanos"):
            values = sorted(duration_nanos(row[field]) / 1000 for row in rows)
            summary[kind]["latency_us"][field] = {
                name: values[min(len(values) - 1, max(0, math.ceil(len(values) * quantile) - 1))]
                for name, quantile in (("p50", .5), ("p95", .95), ("p99", .99), ("max", 1))}
    return {"cumulative_scheduler_snapshots": snapshots, "sampled_waits_by_kind_and_outcome": summary}


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def read_record(process, label, timeout):
    # One response per command: no buffered multi-line protocol or timing-dependent sleeps.
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        if not selector.select(timeout):
            raise TimeoutError(f"Timed out waiting for {label}; inspect its stderr log")
        line = process.stdout.readline()
    if not line:
        raise RuntimeError(f"{label} exited without a response; inspect its stderr log")
    return json.loads(line)


def server_stats(server):
    deadline = time.monotonic() + 5
    peak = 0
    while True:
        server.stdin.write("stats\n")
        server.stdin.flush()
        result = read_record(server, "server statistics", 5)
        peak = max(peak, result["peak_active"])
        if result["active"] == 0:
            result["peak_active"] = peak
            return result
        if time.monotonic() >= deadline:
            raise RuntimeError("Server did not drain its handlers")
        time.sleep(0.01)


def stop(process):
    if process is None:
        return
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    for stream in (process.stdin, process.stdout):
        if stream is not None:
            stream.close()


def profile_summary(java, recording):
    jfr = str(Path(java).with_name("jfr"))
    command = [jfr, "print", "--json", "--stack-depth", "64", "--events",
               "jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample,"
               "jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.GarbageCollection,"
               "com.github.rostmyr.jfibers.Scheduler,com.github.rostmyr.jfibers.Await",
               str(recording)]
    raw = json.loads(subprocess.check_output(command, text=True, timeout=60))
    events = raw["recording"]["events"]
    counts = Counter(event["type"] for event in events)
    stacks = {kind: Counter() for kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample")}
    leaves = {kind: Counter() for kind in stacks}
    inclusive = {kind: Counter() for kind in stacks}
    allocations = Counter()
    for event in events:
        kind = event["type"]
        if kind == "jdk.ObjectAllocationSample":
            values = event["values"]
            allocations[values["objectClass"]["name"].replace("/", ".")] += values["weight"]
        if kind not in stacks:
            continue
        trace = event["values"].get("stackTrace") or {}
        frames = []
        for frame in trace.get("frames", []):
            method = frame["method"]
            owner = method["type"]["name"].replace("/", ".")
            frames.append(owner + "." + method["name"])
        if not frames:
            continue
        stacks[kind][tuple(frames)] += 1
        leaves[kind][frames[0]] += 1
        categories = {
            "jdk_http": any("jdk.internal.net.http." in frame for frame in frames),
            "socket_io": any(frame.startswith(("sun.nio.", "java.net.", "java.io.")) for frame in frames),
            "fiber_runtime": any(frame.startswith("com.github.rostmyr.jfibers.")
                                 and ".benchmarks." not in frame for frame in frames),
            "compiled_http_workload": any("HttpFiberWorkload" in frame for frame in frames),
            "fork_join_or_virtual_thread": any("ForkJoin" in frame or "VirtualThread" in frame for frame in frames),
        }
        inclusive[kind].update(name for name, present in categories.items() if present)
    return {
        "recording": recording.name,
        "recording_sha256": hashlib.sha256(recording.read_bytes()).hexdigest(),
        "extraction_command": command,
        "event_counts": dict(counts),
        "fiber_diagnostics": fiber_profile(events),
        "sample_types": {kind: {
            "samples_with_stack": sum(stacks[kind].values()),
            "inclusive_categories_samples_overlap": dict(inclusive[kind]),
            "top_sampled_leaf_methods": [{"method": method, "samples": count}
                                         for method, count in leaves[kind].most_common(20)],
            "top_sampled_stacks": [{"frames": list(stack), "samples": count}
                                   for stack, count in stacks[kind].most_common(20)],
        } for kind in stacks},
        "total_allocation_sample_weight_bytes": sum(allocations.values()),
        "top_allocation_classes_by_sample_weight": [{"class": owner, "weight_bytes": weight}
                                                     for owner, weight in allocations.most_common(20)],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quick", action="store_true", help="Short correctness smoke; not performance evidence")
    parser.add_argument("--profiles", action="store_true", help="Also run one separate profiled JVM per backend")
    parser.add_argument("--profiles-only", action="store_true", help="Collect profiles without ordinary measurements")
    parser.add_argument("--standard-jfr", action="store_true", help="Leave fiber events disabled in profile runs")
    parser.add_argument("--backends", nargs="+", choices=BACKENDS, default=BACKENDS)
    parser.add_argument("--scenarios", nargs="+", help="CONCURRENCY:DELAY_MS pairs")
    parser.add_argument("--forks", type=int, help="Fresh JVM repetitions (default: 3; quick: 1)")
    parser.add_argument("--variant", action="append", help="JFIBERS core override LABEL=/path/core.jar; repeat for A/B")
    parser.add_argument("--output", type=Path, help="New output directory; never overwrites previous results")
    args = parser.parse_args()
    jar = MODULE / "target/jfibers-benchmarks-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
    if not jar.is_file():
        parser.error("Build first: ./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java:
        parser.error("Java is unavailable; set JAVA_HOME")
    output = (args.output or MODULE / "target/http-results" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")).resolve()
    output.mkdir(parents=True, exist_ok=False)
    cpuinfo = read_optional("/proc/cpuinfo") or ""
    warmup_ms, measure_ms = (1000, 1000) if args.quick else (15000, 10000)
    forks = args.forks if args.forks is not None else (1 if args.quick else 3)
    if forks < 1:
        parser.error("--forks must be positive")
    scenarios = [tuple(map(int, value.split(":"))) for value in args.scenarios] if args.scenarios else (
        [(8, 0), (8, 5)] if args.quick else SCENARIOS)
    if any(len(pair) != 2 or not 1 <= pair[0] <= 4096 or not 0 <= pair[1] <= 1000 for pair in scenarios):
        parser.error("Scenarios require concurrency 1..4096 and delay 0..1000 ms")
    variants = parse_variants(args.variant)
    metadata = {
        "started_utc": datetime.now(timezone.utc).isoformat(),
        "quick_smoke_only": args.quick,
        "git_commit": capture(["git", "rev-parse", "HEAD"]),
        "git_status": capture(["git", "status", "--short"]),
        "java": capture([java, "-version"]),
        "platform": platform.platform(),
        "cpu_model": next((line.split(":", 1)[1].strip() for line in cpuinfo.splitlines()
                           if line.startswith("model name")), None),
        "host_logical_cpus": os.cpu_count(),
        "cpu_quota": read_optional("/sys/fs/cgroup/cpu.max"),
        "memory_limit": read_optional("/sys/fs/cgroup/memory.max"),
        "cpu_stat_before": read_optional("/sys/fs/cgroup/cpu.stat"),
        "client_jvm_flags": CLIENT_FLAGS,
        "server_jvm_flags": SERVER_FLAGS,
        "warmup_ms": warmup_ms,
        "measurement_ms": measure_ms,
        "forks": forks,
        "backends": args.backends,
        "fiber_events_in_profiles": not args.standard_jfr,
        "variants": {label: {"core_jar": str(path) if path else None,
                             "sha256": hashlib.sha256((path or jar).read_bytes()).hexdigest()}
                     for label, path in variants},
        "scenarios_concurrency_delay_ms": scenarios,
        "workload": "closed-loop, two sequential HTTP/1.1 GETs, 1024-byte checked bodies, reused connections",
        "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "source_sha256": {
            str(path.relative_to(REPOSITORY)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted([*MODULE.glob("*.py"), *MODULE.glob("src/main/java/**/*.java")])
        },
        "commands": [],
    }
    records = []
    profiles = []

    def save():
        write_json(output / "environment.json", metadata)
        write_json(output / "http.json", records)
        write_json(output / "profiles.json", profiles)

    def run_pair(backend, concurrency, delay, fork, variant, profiled=False):
        variant_name, core = variant
        label = f"{'profile' if profiled else 'measure'}-{fork}-{concurrency}-{delay}-{backend.lower()}-{variant_name}"
        print(label, flush=True)
        server = None
        client = None
        recording = output / (label + ".jfr")
        before_cgroup = read_optional("/sys/fs/cgroup/cpu.stat")
        with (output / (label + "-server.log")).open("w") as server_log, \
                (output / (label + "-client.log")).open("w") as client_log:
            try:
                server_command = [java, *SERVER_FLAGS, "-cp", str(jar), PACKAGE + "HttpTestServer", str(delay)]
                metadata["commands"].append(server_command)
                save()
                server = subprocess.Popen(server_command, cwd=REPOSITORY, stdin=subprocess.PIPE,
                                          stdout=subprocess.PIPE, stderr=server_log, text=True, bufsize=1)
                ready = read_record(server, "server startup", 15)
                if not ready.get("ready"):
                    raise RuntimeError(f"Unexpected server startup: {ready}")
                classpath = os.pathsep.join(str(path) for path in (core, jar) if path)
                flags = ["-Djfibers.jfr=true"] if profiled and not args.standard_jfr and backend == "JFIBERS" else []
                client_command = [java, *CLIENT_FLAGS, *flags, "-Xverify:all", "-cp", classpath, PACKAGE + "HttpLoadProbe",
                                  backend, f"http://127.0.0.1:{ready['port']}", str(concurrency),
                                  str(warmup_ms), str(measure_ms)]
                if profiled:
                    client_command.append(str(recording))
                metadata["commands"].append(client_command)
                save()
                client = subprocess.Popen(client_command, cwd=REPOSITORY, stdin=subprocess.PIPE,
                                          stdout=subprocess.PIPE, stderr=client_log, text=True, bufsize=1)
                ready = read_record(client, "client warmup", warmup_ms / 1000 + 40)
                if not ready.get("ready"):
                    raise RuntimeError(f"Unexpected client warmup: {ready}")
                server_before = server_stats(server)
                client.stdin.write("measure\n")
                client.stdin.flush()
                message = read_record(client, "client measurement", measure_ms / 1000 + 40)
                server_after = server_stats(server)
                client.wait(timeout=15)
                if client.returncode != 0:
                    raise RuntimeError("Client failed during shutdown")
                result = message["result"]
                successful = result["successful_operations"]
                expected_requests = successful * 2
                actual_requests = server_after["requests"] - server_before["requests"]
                actual_bytes = server_after["body_bytes"] - server_before["body_bytes"]
                server_failures = server_after["failures"] - server_before["failures"]
                record = {
                    "fork": fork, "delay_ms": delay, "profiled": profiled, "variant": variant_name,
                    "warmup": ready["warmup"],
                    **result,
                    "server_before": server_before, "server_after": server_after,
                    "server_http_requests": actual_requests, "server_body_bytes": actual_bytes,
                    "server_errors": server_failures,
                    "server_cpu_cores": (server_after["cpu_nanos"] - server_before["cpu_nanos"])
                        / 1e9 / result["elapsed_seconds_including_drain"],
                    "cpu_stat_before": before_cgroup,
                    "cpu_stat_after": read_optional("/sys/fs/cgroup/cpu.stat"),
                }
                if profiled:
                    profiles.append({"measurement": record, "jfr": profile_summary(java, recording)})
                else:
                    records.append(record)
                save()
                if result["errors"] or server_failures or actual_requests != expected_requests \
                        or actual_bytes != expected_requests * 1024:
                    raise RuntimeError(f"HTTP correctness/accounting failed in {label}; raw result retained")
                if sum(result["successful_completions_per_second"]) != successful:
                    raise RuntimeError("Incomplete per-second accounting")
                print(f"  {result['successful_operations_per_second']:.0f} ops/s, "
                      f"p99 <= {result['latency_p99_upper_ms']:.2f} ms, "
                      f"client CPU {result['client_cpu_cores']:.2f}, server CPU {record['server_cpu_cores']:.2f}", flush=True)
                server.stdin.write("stop\n")
                server.stdin.flush()
                server.wait(timeout=10)
                if server.returncode != 0:
                    raise RuntimeError("Server failed during shutdown")
            finally:
                stop(client)
                stop(server)

    save()
    try:
        for fork in range(0 if args.profiles_only else forks):
            # Rotate both scenarios and backends between fresh JVM repetitions.
            offset = fork % len(scenarios)
            ordered_scenarios = scenarios[offset:] + scenarios[:offset]
            offset = fork % len(args.backends)
            backends = args.backends[offset:] + args.backends[:offset]
            for concurrency, delay in ordered_scenarios:
                for backend in backends:
                    choices = variants if backend == "JFIBERS" else [("bundled", None)]
                    offset = fork % len(choices)
                    for variant in choices[offset:] + choices[:offset]:
                        run_pair(backend, concurrency, delay, fork + 1, variant)
        if args.profiles or args.profiles_only:
            for concurrency, delay in (scenarios if args.scenarios else [(8 if args.quick else 256, 5)]):
                for backend in args.backends:
                    for variant in variants if backend == "JFIBERS" else [("bundled", None)]:
                        run_pair(backend, concurrency, delay, 1, variant, profiled=True)
        metadata["finished_utc"] = datetime.now(timezone.utc).isoformat()
    finally:
        metadata["cpu_stat_after"] = read_optional("/sys/fs/cgroup/cpu.stat")
        save()
    print(f"Results: {output}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.SubprocessError, RuntimeError, TimeoutError, ValueError) as failure:
        print(f"HTTP benchmark failed: {failure}", file=sys.stderr)
        sys.exit(1)
