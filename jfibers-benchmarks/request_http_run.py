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
from request_http_profile import prepare_recording, recording_command, summarize_recording
from run import JVM_FLAGS, MODULE, REPOSITORY, capture, read_optional


PACKAGE = "com.github.rostmyr.jfibers.benchmarks."
BACKENDS = ["FIBER_POOL_1", "FIBER_POOL_4", "FIBER_POOL_OFFLOAD", "VIRTUAL_THREADS"]
FLAGS = [*JVM_FLAGS, "-Djfibers.jfr=false", "-Djava.util.concurrent.ForkJoinPool.common.parallelism=4"]
SERVER_FLAGS = [*FLAGS, "-Dsun.net.httpserver.nodelay=true"]
DRAIN_FIELDS = ["active_roots", "active_heavy", "pending_completions", "pending_sources", "pending_timers", "active_compute",
                "compute_queue_size", "timer_queue_size", "active_exchanges", "response_queue", "response_active",
                "downstream_active", "downstream_executor_queue", "downstream_executor_active"]
DEPENDENCY_DRAIN_FIELDS = ["active_exchanges", "active_admitted", "pending_timers", "timer_queue_size",
                           "response_queue", "response_active", "handler_queue", "handler_active"]
DEFAULT_CASES = ["1000:1000", "1000:1000000", "2000:1000000", "4000:1000000"]


def command(process, value, label, timeout=15):
    process.stdin.write(value + "\n")
    process.stdin.flush()
    return read_record(process, label, timeout)


def process_stats(process, fields, label):
    limit = time.monotonic() + 15
    while True:
        snapshot = command(process, "stats", label + " statistics")
        if all(snapshot[field] == 0 for field in fields):
            return snapshot
        if time.monotonic() >= limit:
            raise RuntimeError(f"{label} did not drain: {snapshot}")
        time.sleep(.005)


def server_stats(server):
    return process_stats(server, DRAIN_FIELDS, "server")


def dependency_stats(dependency):
    return process_stats(dependency, DEPENDENCY_DRAIN_FIELDS, "dependency")


def parse_cpu_list(value):
    """Accept the Linux list format emitted by /proc, including ranges."""
    cpus = set()
    for part in value.split(","):
        if "-" in part:
            first, last = map(int, part.split("-"))
            if first < 0 or last < first:
                raise ValueError("Invalid CPU range")
            cpus.update(range(first, last + 1))
        else:
            cpu = int(part)
            if cpu < 0:
                raise ValueError("CPU IDs must be nonnegative")
            cpus.add(cpu)
    return sorted(cpus)


def role_flags(role, cpus):
    flags = list(SERVER_FLAGS if role in ("server", "dependency") else FLAGS)
    if cpus is not None:
        flags = [f"-XX:ActiveProcessorCount={len(cpus)}" if flag.startswith("-XX:ActiveProcessorCount=") else flag
                 for flag in flags]
        if role == "server":
            flags = [f"-Djdk.virtualThreadScheduler.parallelism={len(cpus)}"
                     if flag.startswith("-Djdk.virtualThreadScheduler.parallelism=") else flag for flag in flags]
    return flags


def affinity_snapshot(process, expected_cpus):
    task_directory = Path(f"/proc/{process.pid}/task")
    if not task_directory.is_dir():
        if process.poll() is not None:
            raise RuntimeError(f"Process {process.pid} exited before affinity verification")
        return {"supported": False, "pid": process.pid}
    masks = {}
    checked = 0
    for task in sorted(task_directory.iterdir()):
        try:
            status = (task / "status").read_text()
        except FileNotFoundError:
            continue  # A completed JVM helper thread can disappear during the snapshot.
        raw = next(line.split(":", 1)[1].strip() for line in status.splitlines()
                   if line.startswith("Cpus_allowed_list:"))
        actual = parse_cpu_list(raw)
        if actual != expected_cpus:
            raise RuntimeError(f"Affinity mismatch: PID {process.pid}, TID {task.name}: {actual}, expected {expected_cpus}")
        masks[raw] = masks.get(raw, 0) + 1
        checked += 1
    if not checked:
        raise RuntimeError(f"No live threads checked for PID {process.pid}")
    return {"supported": True, "pid": process.pid, "threads_checked": checked,
            "expected_cpus": expected_cpus, "observed_thread_masks": masks}


def validate_downstream(result, server_difference, dependency_before, dependency_after, wait_mode, lifecycle_check=False):
    completed = server_difference["downstream_completed"]
    if server_difference["downstream_started"] != completed \
            or server_difference["downstream_succeeded"] != completed or server_difference["downstream_failed"]:
        raise RuntimeError("Downstream physical completion accounting or response validation failed")
    if wait_mode == "timer":
        if completed or dependency_before is not None or dependency_after is not None:
            raise RuntimeError("Timer control unexpectedly used the HTTP dependency")
        return None
    if lifecycle_check:
        if completed != 3 * result["http_status"]["200"]:
            raise RuntimeError("Lifecycle checks made an unexpected number of dependency calls")
    elif not server_difference["cpu_steps"] <= completed <= 3 * server_difference["accepted"]:
        raise RuntimeError("Dependency call count is outside the accepted-work bounds")
    difference = delta(dependency_before, dependency_after)
    expected = {"requests": completed, "responses": completed, "status_200": completed,
                "response_bytes": 24 * completed, "status_400": 0, "status_503": 0,
                "errors": 0, "response_errors": 0}
    for key, count in expected.items():
        if difference[key] != count:
            raise RuntimeError(f"Dependency {key}={difference[key]}, expected {count}")
    if any(dependency_after[field] for field in DEPENDENCY_DRAIN_FIELDS):
        raise RuntimeError("Dependency resources did not drain")
    if dependency_after["peak_admitted"] > dependency_after["capacity"]:
        raise RuntimeError("Dependency admission exceeded its capacity")
    return difference


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
        detail = after.get("first_error")
        raise RuntimeError("Unexpected error/cancellation in normal traffic"
                           + (": " + json.dumps(detail, sort_keys=True) if detail else ""))
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
    parser.add_argument("--profiles-only", action="store_true", help="Separate diagnostic runs; never produce a performance summary")
    parser.add_argument("--standard-jfr", action="store_true", help="Leave custom fiber events off in diagnostic runs")
    parser.add_argument("--jfr-sample-every", type=int, default=256, help="Sample every Nth fiber wait (diagnostics only)")
    parser.add_argument("--backends", nargs="+", choices=BACKENDS, default=BACKENDS)
    parser.add_argument("--cases", nargs="+", default=DEFAULT_CASES, help="RATE:HEAVY_ITERATIONS pairs")
    parser.add_argument("--capacity", type=int, default=64)
    parser.add_argument("--heavy-capacities", nargs="+", type=int, default=[0],
                        help="Concurrent heavy-root limits; zero disables the heavy gate")
    parser.add_argument("--wait-modes", nargs="+", choices=["timer", "http"], default=["timer"])
    parser.add_argument("--compute-threads", type=int, default=3)
    parser.add_argument("--dependency-delay-ms", type=int, default=1,
                        help="Delay used by both timer and HTTP wait sources")
    parser.add_argument("--server-cpus", help="Comma-separated CPU IDs for the application server")
    parser.add_argument("--client-cpus", help="Comma-separated CPU IDs for the load generator")
    parser.add_argument("--dependency-cpus", help="Comma-separated CPU IDs for the HTTP dependency")
    parser.add_argument("--max-in-flight", type=int, default=512)
    parser.add_argument("--deadline-ms", type=int, default=100)
    parser.add_argument("--slo-ms", type=int, default=150)
    parser.add_argument("--warmup-ms", type=int, default=15000)
    parser.add_argument("--measure-ms", type=int, default=10000)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if (args.standard_jfr or args.jfr_sample_every != 256) and not args.profiles_only:
        parser.error("JFR options require --profiles-only")
    if args.jfr_sample_every < 1:
        parser.error("JFR sampling interval must be positive")
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
    if not 1 <= args.compute_threads <= 64 or not 0 <= args.dependency_delay_ms <= 1000 \
            or len(set(args.wait_modes)) != len(args.wait_modes):
        parser.error("Compute threads require 1..64, dependency delay 0..1000 ms, and wait modes must be unique")
    inherited_cpus = sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else list(range(os.cpu_count() or 1))
    affinity = {"server": None, "client": None, "dependency": None}
    raw_affinity = {"server": args.server_cpus, "client": args.client_cpus, "dependency": args.dependency_cpus}
    affinity_enabled = any(value is not None for value in raw_affinity.values())
    taskset = shutil.which("taskset") if affinity_enabled else None
    if affinity_enabled:
        if not taskset or not hasattr(os, "sched_getaffinity"):
            parser.error("CPU affinity requires Linux sched_getaffinity and taskset")
        if args.server_cpus is None or args.client_cpus is None or ("http" in args.wait_modes and args.dependency_cpus is None):
            parser.error("Affinity requires server and client CPU sets, plus dependency CPUs when HTTP mode is selected")
        used = set()
        for role, value in raw_affinity.items():
            if value is None:
                continue
            parts = value.split(",")
            if any(not part.strip().isdigit() for part in parts):
                parser.error("CPU options require nonempty comma-separated nonnegative integer IDs")
            cpus = sorted(set(map(int, parts)))
            if len(cpus) != len(parts) or not set(cpus) <= set(inherited_cpus) or used.intersection(cpus):
                parser.error("Role CPU sets must be unique, pairwise disjoint and within the current allowed CPU set")
            affinity[role] = cpus
            used.update(cpus)
    flags_by_role = {role: role_flags(role, cpus) for role, cpus in affinity.items()}
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
    recording_settings = prepare_recording(java, output) if args.profiles_only else None
    cpuinfo = read_optional("/proc/cpuinfo") or ""
    metadata = {
        "started_utc": datetime.now(timezone.utc).isoformat(), "quick_smoke_only": args.quick,
        "profiles_only": args.profiles_only,
        "fiber_events_in_profiles": args.profiles_only and not args.standard_jfr,
        "jfr_sample_every": args.jfr_sample_every if args.profiles_only else None,
        "recording_settings": recording_settings,
        "git_commit": capture(["git", "rev-parse", "HEAD"]), "git_status": capture(["git", "status", "--short"]),
        "java": capture([java, "-version"]), "platform": platform.platform(),
        "cpu_model": next((line.split(":", 1)[1].strip() for line in cpuinfo.splitlines()
                           if line.startswith("model name")), None),
        "host_logical_cpus": os.cpu_count(), "cpu_quota": read_optional("/sys/fs/cgroup/cpu.max"),
        "memory_limit": read_optional("/sys/fs/cgroup/memory.max"),
        "cpu_stat_before": read_optional("/sys/fs/cgroup/cpu.stat"),
        "client_jvm_flags": flags_by_role["client"], "server_jvm_flags": flags_by_role["server"],
        "dependency_jvm_flags": flags_by_role["dependency"],
        "cpu_affinity": affinity, "inherited_allowed_cpus": inherited_cpus,
        "cpu_topology": {str(cpu): {name: read_optional(f"/sys/devices/system/cpu/cpu{cpu}/topology/{name}")
                                   for name in ("physical_package_id", "core_id", "thread_siblings_list")}
                         for cpu in inherited_cpus},
        "affinity_verification": "All live JVM threads inspected at startup and before/after each phase via /proc/PID/task/TID/status",
        "warmup_ms": warmup, "measure_ms": measure, "forks": forks, "backends": args.backends,
        "cases_rate_heavy_iterations": cases, "capacity": args.capacity, "max_in_flight": args.max_in_flight,
        "heavy_capacities": args.heavy_capacities,
        "wait_modes": args.wait_modes, "compute_threads": args.compute_threads,
        "dependency_delay_ms": args.dependency_delay_ms, "dependency_capacity": 1024,
        "deadline_ms": args.deadline_ms, "slo_ms": args.slo_ms,
        "workload": "open-loop HTTP/1.1, one fresh root per accepted request; 3 x (configured timer/HTTP wait + CPU), 10% heavy",
        "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "source_sha256": {str(path.relative_to(REPOSITORY)): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in sorted([*MODULE.glob("*.py"), *MODULE.glob("src/main/java/**/*.java")])},
        "commands": [],
    }
    records = []

    def save():
        write_json(output / "environment.json", metadata)
        write_json(output / ("profiles.json" if args.profiles_only else "results.json"), records)

    def java_command(role, main_class, arguments, properties=()):
        invocation = [java, *flags_by_role[role], *properties, "-Xverify:all", "-cp", str(jar), PACKAGE + main_class,
                      *map(str, arguments)]
        cpus = affinity[role]
        return [taskset, "--cpu-list", ",".join(map(str, cpus)), *invocation] if cpus is not None else invocation

    def run_pair(backend, rate, iterations, fork, heavy_capacity, wait_mode):
        label = f"{fork}-{rate}-{iterations}-heavycap{heavy_capacity}-{wait_mode}-compute{args.compute_threads}-{backend.lower()}"
        print(label, flush=True)
        server = client = dependency = None
        before_cgroup = read_optional("/sys/fs/cgroup/cpu.stat")
        record = {"fork": fork, "backend": backend, "rate": rate, "heavy_iterations": iterations,
                  "profiled": args.profiles_only,
                  "heavy_capacity": heavy_capacity,
                  "wait_mode": wait_mode, "compute_threads": args.compute_threads,
                  "dependency_delay_ms": args.dependency_delay_ms, "cpu_affinity": affinity,
                  "cpu_stat_before": before_cgroup, "affinity_checks": []}
        records.append(record)

        def verify_affinities(boundary):
            observations = {}
            for role, process in (("server", server), ("client", client), ("dependency", dependency)):
                if process is not None:
                    observations[role] = affinity_snapshot(process, affinity[role] or inherited_cpus)
                    if affinity_enabled and not observations[role]["supported"]:
                        raise RuntimeError("Cannot verify requested CPU affinity via /proc")
            record["affinity_checks"].append({"boundary": boundary, "roles": observations})

        with (output / (label + "-server.log")).open("w") as server_log, \
                (output / (label + "-client.log")).open("w") as client_log, \
                (output / (label + "-dependency.log")).open("w") as dependency_log:
            try:
                properties = [f"-Djfibers.benchmark.computeThreads={args.compute_threads}",
                              f"-Djfibers.benchmark.dependencyDelayMillis={args.dependency_delay_ms}"]
                if args.profiles_only:
                    fiber_events = not args.standard_jfr and backend != "VIRTUAL_THREADS"
                    properties.extend(["-Djfibers.jfr=" + str(fiber_events).lower(),
                                       f"-Djfibers.jfr.sampleEvery={args.jfr_sample_every}"])
                    record["fiber_events_enabled"] = fiber_events
                if wait_mode == "http":
                    dependency_command = java_command("dependency", "RequestDependencyServer", [1024])
                    metadata["commands"].append(dependency_command)
                    save()
                    dependency = subprocess.Popen(dependency_command, cwd=REPOSITORY, stdin=subprocess.PIPE,
                                                  stdout=subprocess.PIPE, stderr=dependency_log, text=True, bufsize=1)
                    ready = read_record(dependency, "dependency startup", 20)
                    if not ready.get("ready"):
                        raise RuntimeError(f"Unexpected dependency startup: {ready}")
                    properties.append(f"-Djfibers.benchmark.dependencyUrl=http://127.0.0.1:{ready['port']}")
                server_command = java_command("server", "RequestHttpServer",
                                              [backend, args.capacity, iterations, heavy_capacity], properties)
                metadata["commands"].append(server_command)
                save()
                server = subprocess.Popen(server_command, cwd=REPOSITORY, stdin=subprocess.PIPE,
                                          stdout=subprocess.PIPE, stderr=server_log, text=True, bufsize=1)
                ready = read_record(server, "server startup", 20)
                if not ready.get("ready"):
                    raise RuntimeError(f"Unexpected server startup: {ready}")
                client_command = java_command("client", "RequestHttpProbe",
                    [f"http://127.0.0.1:{ready['port']}", rate, warmup, measure, iterations, args.deadline_ms,
                     args.slo_ms, args.max_in_flight, args.capacity, heavy_capacity])
                metadata["commands"].append(client_command)
                save()
                client = subprocess.Popen(client_command, cwd=REPOSITORY, stdin=subprocess.PIPE,
                                          stdout=subprocess.PIPE, stderr=client_log, text=True, bufsize=1)
                if not read_record(client, "client startup", 20).get("ready"):
                    raise RuntimeError("Unexpected client startup")
                verify_affinities("startup_before_check")
                record["check_server_before"] = server_stats(server)
                if dependency is not None:
                    record["check_dependency_before"] = dependency_stats(dependency)
                record["check"] = command(client, "check", "lifecycle self-checks", 45)["check"]
                record["check_server_after"] = server_stats(server)
                if dependency is not None:
                    record["check_dependency_after"] = dependency_stats(dependency)
                verify_affinities("after_check")
                if not record["check"]["passed"]:
                    raise RuntimeError("Lifecycle self-checks failed")
                check_delta = delta(record["check_server_before"], record["check_server_after"])
                check_result = {**record["check"], "dispatched": record["check"]["offered"],
                                "local_rejected": 0, "transport_errors": 0, "heavy_iterations": iterations}
                validate(check_result, record["check_server_before"], record["check_server_after"],
                         args.capacity, heavy_capacity, lifecycle_check=True)
                record["check_server_delta"] = check_delta
                dependency_difference = validate_downstream(check_result, check_delta,
                    record.get("check_dependency_before"), record.get("check_dependency_after"), wait_mode, lifecycle_check=True)
                if dependency_difference is not None:
                    record["check_dependency_delta"] = dependency_difference
                save()
                for phase, duration in (("warmup", warmup), ("measurement", measure)):
                    if args.profiles_only and phase == "measurement":
                        record["jfr_start"] = recording_command(java, server.pid, "start", output / (label + ".jfr"),
                                                                 output / "request-profile.jfc")
                        save()
                    verify_affinities("before_" + phase)
                    before = server_stats(server)
                    dependency_before = dependency_stats(dependency) if dependency is not None else None
                    result = command(client, "warmup" if phase == "warmup" else "measure",
                                     phase, duration / 1000 + 105)["result"]
                    after = server_stats(server)
                    dependency_after = dependency_stats(dependency) if dependency is not None else None
                    verify_affinities("after_" + phase)
                    result.update(wait_mode=wait_mode, compute_threads=args.compute_threads,
                                  dependency_delay_ms=args.dependency_delay_ms)
                    phase_record = {"result": result, "server_before": before, "server_after": after,
                                    "server_delta": delta(before, after)}
                    if dependency is not None:
                        phase_record.update(dependency_before=dependency_before, dependency_after=dependency_after,
                                            dependency_delta=delta(dependency_before, dependency_after))
                    record[phase] = phase_record
                    save()
                    expected_first_id = 1_000_000 + (0 if phase == "warmup" else (rate * warmup + 999) // 1000)
                    if result["first_request_id"] != expected_first_id or result["offered"] != (rate * duration + 999) // 1000:
                        raise RuntimeError("Client arrival trace differs from the fixed phase schedule")
                    if result["heavy_capacity"] != heavy_capacity:
                        raise RuntimeError("Client heavy-capacity metadata mismatch")
                    for snapshot in (before, after):
                        for key, expected in (("wait_mode", wait_mode), ("compute_threads", args.compute_threads),
                                              ("dependency_delay_ms", args.dependency_delay_ms)):
                            if snapshot[key] != expected:
                                raise RuntimeError(f"Server {key} differs from the configured experiment")
                        expected_compute = args.compute_threads if backend == "FIBER_POOL_OFFLOAD" else 0
                        if snapshot["compute_executor_threads"] != expected_compute:
                            raise RuntimeError("Compute executor width differs from the configured backend")
                    difference = validate(result, before, after, args.capacity, heavy_capacity)
                    dependency_difference = validate_downstream(result, difference, dependency_before, dependency_after, wait_mode)
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
                    for field in ("downstream_started", "downstream_completed", "downstream_succeeded", "downstream_failed"):
                        result["server_" + field] = difference[field]
                    if dependency_difference is not None:
                        result["dependency_window_seconds"] = dependency_difference["snapshot_nanos"] / 1e9
                        result["dependency_cpu_seconds"] = dependency_difference["cpu_nanos"] / 1e9
                        result["dependency_cpu_cores"] = dependency_difference["cpu_nanos"] / dependency_difference["snapshot_nanos"]
                        result["dependency_allocated_bytes"] = dependency_difference["allocated_bytes"]
                        result["dependency_allocated_bytes_per_call"] = dependency_difference["allocated_bytes"] / max(1, dependency_difference["responses"])
                        result["dependency_gc_count"] = dependency_difference["gc_count"]
                        result["dependency_gc_millis"] = dependency_difference["gc_millis"]
                    if args.profiles_only and phase == "measurement":
                        record["jfr_stop"] = recording_command(java, server.pid, "stop", output / (label + ".jfr"))
                        save()
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
                if dependency is not None:
                    dependency.stdin.write("stop\n")
                    dependency.stdin.flush()
                    dependency.wait(timeout=20)
                    if dependency.returncode != 0:
                        raise RuntimeError("Dependency failed during shutdown")
                record["validated"] = True
            finally:
                stop(client)
                stop(server)
                stop(dependency)
                record["cpu_stat_after"] = read_optional("/sys/fs/cgroup/cpu.stat")
                save()
        if args.profiles_only:
            # Extract only after every workload JVM has stopped; no analysis competes with load.
            record["jfr"] = summarize_recording(java, output / (label + ".jfr"))
            if record["fiber_events_enabled"] and not record["jfr"]["event_counts"].get("com.github.rostmyr.jfibers.Await"):
                raise RuntimeError("Custom fiber diagnostics produced no sampled waits")
            record["profile_validated"] = True
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
                    for backend_index, backend in enumerate(args.backends[backend_offset:] + args.backends[:backend_offset]):
                        mode_offset = (fork + case_index + cap_index + backend_index) % len(args.wait_modes)
                        for wait_mode in args.wait_modes[mode_offset:] + args.wait_modes[:mode_offset]:
                            run_pair(backend, rate, iterations, fork + 1, heavy_capacity, wait_mode)
        metadata["finished_utc"] = datetime.now(timezone.utc).isoformat()
        if not args.profiles_only:
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
