#!/usr/bin/env python3
"""Isolated request-per-root HTTP JVM pairs, with open-loop arrivals and strict accounting."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time

from http_run import read_record, stop, write_json
from request_http_summarize import summarize
from run import JVM_FLAGS, MODULE, REPOSITORY, capture, read_optional


PACKAGE = "com.github.rostmyr.jfibers.benchmarks."
BACKENDS = ["FIBER_POOL_1", "FIBER_POOL_4", "FIBER_POOL_OFFLOAD", "VIRTUAL_THREADS"]
FLAGS = [*JVM_FLAGS, "-Djfibers.jfr=false", "-Djava.util.concurrent.ForkJoinPool.common.parallelism=4"]
SERVER_FLAGS = [*FLAGS, "-Dsun.net.httpserver.nodelay=true"]
DRAIN_FIELDS = ["active_roots", "active_heavy", "pending_completions", "pending_sources", "pending_timers", "active_compute",
                "compute_queue_size", "timer_queue_size", "active_exchanges", "response_queue", "response_active"]
DEFAULT_CASES = ["1000:1000", "1000:1000000", "2000:1000000", "4000:1000000"]


def command(process, value, label, timeout=15):
    process.stdin.write(value + "\n")
    process.stdin.flush()
    return read_record(process, label, timeout)


def server_stats(server):
    limit = time.monotonic() + 15
    while True:
        snapshot = command(server, "stats", "server statistics")
        if all(snapshot[field] == 0 for field in DRAIN_FIELDS):
            return snapshot
        if time.monotonic() >= limit:
            raise RuntimeError(f"Server did not drain: {snapshot}")
        time.sleep(.005)


def delta(before, after):
    return {name: value - before[name] for name, value in after.items()
            if isinstance(value, (int, float)) and name in before
            and isinstance(before[name], (int, float))}


def validate(result, before, after, capacity, heavy_capacity=0, lifecycle_check=False):
    difference = delta(before, after)
    statuses = result["http_status"]
    offered = result["offered"]
    dispatched = result["dispatched"]
    if offered != result["local_rejected"] + result["transport_errors"] + sum(statuses.values()):
        raise RuntimeError("Incomplete client outcome accounting")
    if dispatched != offered - result["local_rejected"] or result["transport_errors"]:
        raise RuntimeError("Client transport/dispatch failure")
    if statuses["500"] or (statuses["409"] and not lifecycle_check):
        raise RuntimeError("Unexpected error/cancellation in normal traffic")
    if not lifecycle_check and (result["successful"] != statuses["200"] \
            or result["successful"] != result["on_time_success"] + result["late_success"] \
            or sum(result["successful_completions_per_second"]) != result["successful"]):
        raise RuntimeError("Incomplete success/SLO accounting")
    reasons = result["rejection_reasons"]
    if set(reasons) != {"heavy", "global"} or sum(reasons.values()) != statuses["503"]:
        raise RuntimeError("Incomplete admission rejection reason accounting")
    if "light_outcomes" in result:
        for name, expected in {"offered": offered, "local_rejected": result["local_rejected"],
                               "transport_errors": result["transport_errors"],
                               "on_time_success": result["on_time_success"], "late_success": result["late_success"],
                               "rejected_heavy": reasons["heavy"], "rejected_global": reasons["global"],
                               **statuses}.items():
            if result["light_outcomes"][name] + result["heavy_outcomes"][name] != expected:
                raise RuntimeError(f"Incomplete light/heavy outcome accounting: {name}")
        for name in ("light_outcomes", "heavy_outcomes"):
            outcomes = result[name]
            if outcomes["rejected_heavy"] + outcomes["rejected_global"] != outcomes["503"]:
                raise RuntimeError(f"Incomplete {name} admission rejection reasons")
        if result["light_outcomes"]["rejected_heavy"]:
            raise RuntimeError("Heavy admission gate rejected a light request")
    if difference["requests"] != dispatched or difference["responses"] != dispatched \
            or difference["response_bytes"] != 56 * dispatched:
        raise RuntimeError("Server/client request/response accounting differs")
    for status, count in statuses.items():
        if difference["status_" + status] != count:
            raise RuntimeError(f"Server/client HTTP {status} accounting differs")
    expected = {"accepted": dispatched - statuses["503"], "completed": dispatched - statuses["503"],
                "success": statuses["200"], "cancelled": statuses["409"], "timed_out": statuses["504"],
                "rejected": statuses["503"], "rejected_heavy": reasons["heavy"], "rejected_global": reasons["global"],
                "rejected_closed": 0, "errors": 0, "server_errors": 0, "response_errors": 0,
                "cpu_steps_success": 3 * statuses["200"]}
    if lifecycle_check:
        expected["cpu_steps_success"] = result["expected_cpu_steps_success"]
        expected["cpu_iterations_success"] = result["expected_cpu_iterations_success"]
        for outcome in ("cancelled", "timed_out", "errors"):
            expected["cpu_steps_" + outcome] = 0
            expected["cpu_iterations_" + outcome] = 0
    else:
        expected["cpu_iterations_success"] = 3 * (result["light_outcomes"]["200"] * 1000
            + result["heavy_outcomes"]["200"] * result["heavy_iterations"])
    for key, count in expected.items():
        if difference[key] != count:
            raise RuntimeError(f"Server {key}={difference[key]}, expected {count}")
    for prefix in ("cpu_steps", "cpu_iterations"):
        if difference[prefix] != sum(difference[prefix + "_" + outcome]
                                     for outcome in ("success", "cancelled", "timed_out", "errors")):
            raise RuntimeError(f"Incomplete terminal {prefix} accounting")
    for outcome, status in (("success", "200"), ("cancelled", "409"), ("timed_out", "504"), ("errors", "500")):
        steps = difference["cpu_steps_" + outcome]
        iterations = difference["cpu_iterations_" + outcome]
        if not 0 <= steps <= 3 * statuses[status] or not steps * min(1000, result["heavy_iterations"]) \
                <= iterations <= steps * max(1000, result["heavy_iterations"]):
            raise RuntimeError(f"Impossible completed CPU work for {outcome} outcomes")
    if after["heavy_capacity"] != heavy_capacity or (heavy_capacity and after["peak_heavy"] > heavy_capacity):
        raise RuntimeError("Heavy capacity exceeded or incorrectly configured")
    if after["peak_roots"] > capacity or any(after[field] for field in DRAIN_FIELDS):
        raise RuntimeError("Capacity exceeded or asynchronous resources not drained")
    return difference


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quick", action="store_true", help="Correctness smoke only; not performance evidence")
    parser.add_argument("--backends", nargs="+", choices=BACKENDS, default=BACKENDS)
    parser.add_argument("--cases", nargs="+", default=DEFAULT_CASES, help="RATE:HEAVY_ITERATIONS pairs")
    parser.add_argument("--capacity", type=int, default=64)
    parser.add_argument("--heavy-capacities", nargs="+", type=int, default=[0],
                        help="Concurrent heavy-root limits; zero disables the heavy gate")
    parser.add_argument("--max-in-flight", type=int, default=512)
    parser.add_argument("--deadline-ms", type=int, default=100)
    parser.add_argument("--slo-ms", type=int, default=150)
    parser.add_argument("--warmup-ms", type=int, default=15000)
    parser.add_argument("--measure-ms", type=int, default=10000)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        cases = [tuple(map(int, case.split(":"))) for case in args.cases]
    except ValueError:
        parser.error("Cases must be RATE:HEAVY_ITERATIONS integer pairs")
    if any(len(case) != 2 or not 1 <= case[0] <= 100000 or not 1 <= case[1] <= 10000000 for case in cases):
        parser.error("Cases require rate 1..100000 and heavy iterations 1..10000000")
    if not 1 <= args.capacity <= 512 or not 1 <= args.max_in_flight <= 512 \
            or not 1 <= args.deadline_ms <= 5000 or not 1 <= args.slo_ms <= 10000 \
            or not 100 <= args.warmup_ms <= 60000 or not 100 <= args.measure_ms <= 60000 or args.forks < 1:
        parser.error("Invalid bounds, durations or forks")
    if any(not 0 <= cap <= args.capacity for cap in args.heavy_capacities) \
            or len(set(args.heavy_capacities)) != len(args.heavy_capacities):
        parser.error("Heavy capacities must be unique integers from zero through root capacity")
    forks = 1 if args.quick else args.forks
    warmup, measure = (300, 400) if args.quick else (args.warmup_ms, args.measure_ms)
    if any(rate * max(warmup, measure) > 2_000_000_000 for rate, _ in cases):
        parser.error("At most two million arrivals per phase")
    jar = MODULE / "target/jfibers-benchmarks-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
    if not jar.is_file():
        parser.error("Build first: ./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java:
        parser.error("Java is unavailable; set JAVA_HOME")
    output = (args.output or MODULE / "target/request-http" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")).resolve()
    output.mkdir(parents=True, exist_ok=False)
    cpuinfo = read_optional("/proc/cpuinfo") or ""
    metadata = {
        "started_utc": datetime.now(timezone.utc).isoformat(), "quick_smoke_only": args.quick,
        "git_commit": capture(["git", "rev-parse", "HEAD"]), "git_status": capture(["git", "status", "--short"]),
        "java": capture([java, "-version"]), "platform": platform.platform(),
        "cpu_model": next((line.split(":", 1)[1].strip() for line in cpuinfo.splitlines()
                           if line.startswith("model name")), None),
        "host_logical_cpus": os.cpu_count(), "cpu_quota": read_optional("/sys/fs/cgroup/cpu.max"),
        "memory_limit": read_optional("/sys/fs/cgroup/memory.max"),
        "cpu_stat_before": read_optional("/sys/fs/cgroup/cpu.stat"),
        "client_jvm_flags": FLAGS, "server_jvm_flags": SERVER_FLAGS,
        "warmup_ms": warmup, "measure_ms": measure, "forks": forks, "backends": args.backends,
        "cases_rate_heavy_iterations": cases, "capacity": args.capacity, "max_in_flight": args.max_in_flight,
        "heavy_capacities": args.heavy_capacities,
        "deadline_ms": args.deadline_ms, "slo_ms": args.slo_ms,
        "workload": "open-loop HTTP/1.1, one fresh root per accepted request; 3 x (1ms async wait + CPU), 10% heavy",
        "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "source_sha256": {str(path.relative_to(REPOSITORY)): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in sorted([*MODULE.glob("*.py"), *MODULE.glob("src/main/java/**/*.java")])},
        "commands": [],
    }
    records = []

    def save():
        write_json(output / "environment.json", metadata)
        write_json(output / "results.json", records)

    def run_pair(backend, rate, iterations, fork, heavy_capacity):
        label = f"{fork}-{rate}-{iterations}-heavycap{heavy_capacity}-{backend.lower()}"
        print(label, flush=True)
        server = client = None
        before_cgroup = read_optional("/sys/fs/cgroup/cpu.stat")
        record = {"fork": fork, "backend": backend, "rate": rate, "heavy_iterations": iterations,
                  "heavy_capacity": heavy_capacity,
                  "cpu_stat_before": before_cgroup}
        records.append(record)
        with (output / (label + "-server.log")).open("w") as server_log, \
                (output / (label + "-client.log")).open("w") as client_log:
            try:
                server_command = [java, *SERVER_FLAGS, "-Xverify:all", "-cp", str(jar), PACKAGE + "RequestHttpServer",
                                  backend, str(args.capacity), str(iterations), str(heavy_capacity)]
                metadata["commands"].append(server_command)
                save()
                server = subprocess.Popen(server_command, cwd=REPOSITORY, stdin=subprocess.PIPE,
                                          stdout=subprocess.PIPE, stderr=server_log, text=True, bufsize=1)
                ready = read_record(server, "server startup", 20)
                if not ready.get("ready"):
                    raise RuntimeError(f"Unexpected server startup: {ready}")
                client_command = [java, *FLAGS, "-Xverify:all", "-cp", str(jar), PACKAGE + "RequestHttpProbe",
                                  f"http://127.0.0.1:{ready['port']}", str(rate), str(warmup), str(measure),
                                  str(iterations), str(args.deadline_ms), str(args.slo_ms),
                                  str(args.max_in_flight), str(args.capacity), str(heavy_capacity)]
                metadata["commands"].append(client_command)
                save()
                client = subprocess.Popen(client_command, cwd=REPOSITORY, stdin=subprocess.PIPE,
                                          stdout=subprocess.PIPE, stderr=client_log, text=True, bufsize=1)
                if not read_record(client, "client startup", 20).get("ready"):
                    raise RuntimeError("Unexpected client startup")
                record["check_server_before"] = server_stats(server)
                record["check"] = command(client, "check", "lifecycle self-checks", 45)["check"]
                record["check_server_after"] = server_stats(server)
                if not record["check"]["passed"]:
                    raise RuntimeError("Lifecycle self-checks failed")
                check_delta = delta(record["check_server_before"], record["check_server_after"])
                check_result = {**record["check"], "dispatched": record["check"]["offered"],
                                "local_rejected": 0, "transport_errors": 0, "heavy_iterations": iterations}
                validate(check_result, record["check_server_before"], record["check_server_after"],
                         args.capacity, heavy_capacity, lifecycle_check=True)
                record["check_server_delta"] = check_delta
                save()
                for phase, duration in (("warmup", warmup), ("measurement", measure)):
                    before = server_stats(server)
                    result = command(client, "warmup" if phase == "warmup" else "measure",
                                     phase, duration / 1000 + 105)["result"]
                    after = server_stats(server)
                    phase_record = {"result": result, "server_before": before, "server_after": after,
                                    "server_delta": delta(before, after)}
                    record[phase] = phase_record
                    save()
                    expected_first_id = 1_000_000 + (0 if phase == "warmup" else (rate * warmup + 999) // 1000)
                    if result["first_request_id"] != expected_first_id or result["offered"] != (rate * duration + 999) // 1000:
                        raise RuntimeError("Client arrival trace differs from the fixed phase schedule")
                    if result["heavy_capacity"] != heavy_capacity:
                        raise RuntimeError("Client heavy-capacity metadata mismatch")
                    difference = validate(result, before, after, args.capacity, heavy_capacity)
                    result["server_window_seconds"] = difference["snapshot_nanos"] / 1e9
                    result["server_cpu_seconds"] = difference["cpu_nanos"] / 1e9
                    result["server_cpu_cores"] = difference["cpu_nanos"] / difference["snapshot_nanos"]
                    result["server_allocated_bytes"] = difference["allocated_bytes"]
                    result["server_gc_count"] = difference["gc_count"]
                    result["server_gc_millis"] = difference["gc_millis"]
                    result["server_allocated_bytes_per_accepted"] = difference["allocated_bytes"] / max(1, difference["accepted"])
                    result["server_allocated_bytes_per_dispatched"] = difference["allocated_bytes"] / max(1, result["dispatched"])
                    for prefix in ("cpu_steps", "cpu_iterations"):
                        for suffix in ("", "_success", "_cancelled", "_timed_out", "_errors"):
                            result["server_" + prefix + suffix] = difference[prefix + suffix]
                result = record["measurement"]["result"]
                print(f"  success {result['successful_per_second']:.0f}/s, goodput {result['goodput_per_second']:.0f}/s; "
                      f"HTTP {result['http_status']}, local rejects {result['local_rejected']}", flush=True)
                client.stdin.write("stop\n")
                client.stdin.flush()
                client.wait(timeout=15)
                if client.returncode != 0:
                    raise RuntimeError("Client failed during shutdown")
                server.stdin.write("stop\n")
                server.stdin.flush()
                server.wait(timeout=20)
                if server.returncode != 0:
                    raise RuntimeError("Server failed during shutdown")
                record["validated"] = True
            finally:
                stop(client)
                stop(server)
                record["cpu_stat_after"] = read_optional("/sys/fs/cgroup/cpu.stat")
                save()

    save()
    try:
        for fork in range(forks):
            case_offset = fork % len(cases)
            for case_index, (rate, iterations) in enumerate(cases[case_offset:] + cases[:case_offset]):
                cap_offset = (fork + case_index) % len(args.heavy_capacities)
                caps = args.heavy_capacities[cap_offset:] + args.heavy_capacities[:cap_offset]
                for cap_index, heavy_capacity in enumerate(caps):
                    backend_offset = (fork + case_index + cap_index) % len(args.backends)
                    for backend in args.backends[backend_offset:] + args.backends[:backend_offset]:
                        run_pair(backend, rate, iterations, fork + 1, heavy_capacity)
        metadata["finished_utc"] = datetime.now(timezone.utc).isoformat()
        write_json(output / "summary.json", summarize(output))
    finally:
        metadata["cpu_stat_after"] = read_optional("/sys/fs/cgroup/cpu.stat")
        save()
    print(f"Results: {output}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.SubprocessError, RuntimeError, TimeoutError, ValueError, KeyError) as failure:
        print(f"Request HTTP benchmark failed: {failure}", file=sys.stderr)
        sys.exit(1)
