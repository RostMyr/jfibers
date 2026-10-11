#!/usr/bin/env python3
"""JFR collection and reproducible, descriptive attribution for the request HTTP harness."""

from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import subprocess

from http_run import duration_nanos, fiber_profile


PREFIX = "com.github.rostmyr.jfibers."
EVENTS = ["jdk.ExecutionSample", "jdk.NativeMethodSample", "jdk.ObjectAllocationSample",
          "jdk.ThreadPark", "jdk.JavaMonitorEnter", "jdk.GarbageCollection", "jdk.ThreadCPULoad",
          "jdk.DataLoss", PREFIX + "Scheduler", PREFIX + "Await"]
COUNTERS = ["elapsedNanos", "executionNanos", "parkedNanos", "executions", "parks", "callbackSignals",
            "wakeRequests", "unparkCalls", "completionStageWaits", "childWaits", "polledWaits",
            "staleSignals", "waitSamplesRecorded", "waitSamplesAbandoned"]


def run_tool(command):
    return subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=120).stdout


def prepare_recording(java, output):
    java = Path(java).resolve()
    jfr = java.with_name("jfr")
    jcmd = java.with_name("jcmd")
    if not jfr.is_file() or not jcmd.is_file():
        raise ValueError("JFR profiles require a full JDK with jfr and jcmd")
    settings = output / "request-profile.jfc"
    command = [str(jfr), "configure", "--input", str(java.parent.parent / "lib/jfr/profile.jfc"),
               "--output", str(settings), "jdk.ThreadPark#threshold=1ms", "jdk.JavaMonitorEnter#threshold=1ms",
               "jdk.ThreadCPULoad#period=1s", "jdk.InitialEnvironmentVariable#enabled=false",
               "jdk.InitialSystemProperty#enabled=false", "+" + PREFIX + "Await#enabled=true",
               "+" + PREFIX + "Await#threshold=0ms", "+" + PREFIX + "Await#stackTrace=false",
               "+" + PREFIX + "Scheduler#enabled=true"]
    output_text = run_tool(command)
    return {"command": command, "output": output_text, "file": settings.name,
            "sha256": hashlib.sha256(settings.read_bytes()).hexdigest(),
            "scope": "application JVM only; after warmup, before phase snapshots through final drain",
            "await_threshold_ms": 0, "park_and_monitor_threshold_ms": 1}


def recording_command(java, pid, action, recording, settings=None):
    command = [str(Path(java).resolve().with_name("jcmd")), str(pid), "JFR." + action, "name=request-http"]
    if action == "start":
        command += ["settings=" + str(settings), "disk=true", "maxsize=256m", "dumponexit=true",
                    "filename=" + str(recording)]
    elif action == "stop":
        command += ["filename=" + str(recording)]
    else:
        raise ValueError("Unknown recording action")
    started = datetime.now(timezone.utc).isoformat()
    output = run_tool(command)
    expected = "Started recording" if action == "start" else "Stopped recording"
    # jcmd can exit zero even when the diagnostic command itself fails.
    if expected not in output:
        raise RuntimeError("JFR command failed: " + output)
    if action == "stop" and (not recording.is_file() or not recording.stat().st_size):
        raise RuntimeError("JFR recording was not written")
    return {"command": command, "started_utc": started,
            "finished_utc": datetime.now(timezone.utc).isoformat(), "output": output}


def frames_of(values):
    return [frame["method"]["type"]["name"].replace("/", ".") + "." + frame["method"]["name"]
            for frame in (values.get("stackTrace") or {}).get("frames", [])]


def thread_role(thread):
    thread = thread or {}
    name = thread.get("javaName") or thread.get("osName") or "unnamed"
    if thread.get("virtual"):
        return "virtual-thread"
    if name.startswith("jfibers-compute-"):
        return "fiber-compute"
    if name.startswith("jfibers-pool-"):
        return "fiber-owner"
    if name.startswith("request-dependency-client-"):
        return "http-client-callback"
    if name.startswith("HttpClient-"):
        return "http-client-selector"
    if name.startswith("ForkJoinPool.commonPool-"):
        return "fork-join-common"
    if name.startswith("ForkJoinPool-"):
        return "fork-join-carrier"
    if name.startswith("request-http-handler-"):
        return "http-server-handler"
    if name.startswith("request-http-writer-"):
        return "http-server-writer"
    if name.startswith("pool-") and "-thread-" in name:
        return "scheduled-timer-pool"
    return name


def categories(frames):
    return {name: any(test(frame) for frame in frames) for name, test in {
        "workload_cpu": lambda f: f.endswith(".cpuWork") or f.endswith(".computeInline"),
        "fiber_runtime": lambda f: f.startswith(PREFIX) and ".benchmarks." not in f,
        "jdk_http_client": lambda f: f.startswith("jdk.internal.net.http."),
        "jdk_http_server": lambda f: f.startswith("sun.net.httpserver."),
        "socket_io": lambda f: f.startswith(("sun.nio.", "java.net.")),
        "future_completion": lambda f: f.startswith("java.util.concurrent.CompletableFuture."),
        "virtual_thread_or_fork_join": lambda f: "VirtualThread" in f or "ForkJoin" in f,
        "diagnostics": lambda f: f.startswith(("jdk.jfr.", "jdk.internal.event.")) or "FiberProfiler" in f,
    }.items()}


def quantiles(values):
    values = sorted(values)
    return {name: values[max(0, math.ceil(len(values) * q) - 1)]
            for name, q in (("p50", .5), ("p95", .95), ("p99", .99), ("max", 1))} if values else {}


def top(counter, label, limit=20):
    return [{label: key, "count": value} for key, value in counter.most_common(limit)]


def analyze_events(events):
    counts = Counter(event["type"] for event in events)
    samples = defaultdict(lambda: {"count": 0, "no_stack": 0, "leaves": Counter(), "stacks": Counter(),
                                   "inclusive": Counter()})
    allocations = defaultdict(lambda: {"count": 0, "weight": 0, "classes": Counter(), "sites": Counter()})
    waits = defaultdict(list)
    blocking = defaultdict(list)
    blocking_sites = defaultdict(Counter)
    cpu_load = defaultdict(list)
    snapshots = defaultdict(list)
    for event in events:
        kind, values = event["type"], event["values"]
        role = thread_role(values.get("sampledThread") if kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample")
                           else values.get("eventThread"))
        frames = frames_of(values)
        if kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
            group = samples[kind, role]
            group["count"] += 1
            if frames:
                group["leaves"][frames[0]] += 1
                group["stacks"][tuple(frames)] += 1
                group["inclusive"].update(k for k, present in categories(frames).items() if present)
            else:
                group["no_stack"] += 1
        elif kind == "jdk.ObjectAllocationSample":
            group = allocations[role]
            group["count"] += 1
            group["weight"] += values["weight"]
            group["classes"][values["objectClass"]["name"].replace("/", ".")] += values["weight"]
            group["sites"][" > ".join(frames[:8]) or "no stack"] += values["weight"]
        elif kind in ("jdk.ThreadPark", "jdk.JavaMonitorEnter"):
            blocking[kind, role].append(duration_nanos(values["duration"]) / 1e6)
            blocking_sites[kind, role][" > ".join(frames[:10]) or "no stack"] += 1
        elif kind == "jdk.ThreadCPULoad":
            cpu_load[role].append(values["user"] + values["system"])
        elif kind == PREFIX + "Await":
            waits[values["fiberClass"], values["continuation"], values["waitKind"], values["outcome"]].append(values)
        elif kind == PREFIX + "Scheduler":
            snapshots[values["runId"]].append(values)
    scheduler = []
    for run_id, rows in snapshots.items():
        rows.sort(key=lambda row: duration_nanos(row["elapsedNanos"]))
        # Recording starts after warmup. Never sum lifetime snapshots or subtract peaks.
        first, last = rows[0], rows[-1]
        scheduler.append({"run_id": run_id, "snapshots": len(rows), "first_time": first["startTime"],
                          "last_time": last["startTime"], "delta_first_to_last": {
                              key: (duration_nanos(last[key]) - duration_nanos(first[key]) if key.endswith("Nanos")
                                    else last[key] - first[key]) for key in COUNTERS},
                          "maximum_observed_queue": max(row["queuedFibers"] for row in rows),
                          "lifetime_peak_queue": last["peakQueuedFibers"]})
    return {
        "event_counts": dict(counts),
        "samples_by_type_and_thread_role": [
            {"type": kind, "role": role, "samples": g["count"], "samples_without_stack": g["no_stack"],
             "inclusive_categories_overlap": dict(g["inclusive"]), "top_leaf_methods": top(g["leaves"], "method"),
             "top_stacks": [{"frames": list(stack), "samples": count} for stack, count in g["stacks"].most_common(12)]}
            for (kind, role), g in sorted(samples.items())],
        "allocation_samples_by_thread_role": [
            {"role": role, "samples": g["count"], "weight_bytes": g["weight"],
             "top_classes_weight_bytes": top(g["classes"], "class"),
             "top_sites_weight_bytes": top(g["sites"], "stack")}
            for role, g in sorted(allocations.items())],
        "blocking_events": [{"type": kind, "role": role, "count": len(rows), "sum_duration_ms": sum(rows),
                             "duration_ms": quantiles(rows), "top_stacks": top(blocking_sites[kind, role], "stack")}
                            for (kind, role), rows in sorted(blocking.items())],
        "thread_cpu_load_samples": [{"role": role, "samples": len(rows), "mean_reported_load": sum(rows) / len(rows),
                                     "max_reported_load": max(rows)} for role, rows in sorted(cpu_load.items())],
        "sampled_awaits_by_continuation": [
            {"fiber_class": cls, "continuation": state, "kind": kind, "outcome": outcome, "samples": len(rows),
             "latency_us": {field: quantiles([duration_nanos(row[field]) / 1000 for row in rows])
                            for field in ("observedWaitNanos", "signalToRunnableNanos", "runnableToExecutionNanos",
                                          "readyToExecutionNanos")}}
            for (cls, state, kind, outcome), rows in sorted(waits.items())],
        "scheduler_intervals": scheduler,
        "fiber_diagnostics": fiber_profile(events),
        "semantics": {"cpu_samples": "Java execution and native samples are separate; counts are not CPU nanoseconds",
                      "allocations": "sample weights estimate allocations, not retained heap; class/site counts are bytes",
                      "blocking": "thresholded wall-time events, overlapping across threads; idle parks are not necessarily contention",
                      "cpu_load": "descriptive per-event mean, not role CPU time; short-lived virtual threads may be absent",
                      "scheduler": "first-to-last snapshots within recording; excludes edges, counters include diagnostics",
                      "awaits": "systematically sampled explicit waits, threshold 0; not all requests or HTTP round trips"},
    }


def summarize_recording(java, recording):
    jfr = str(Path(java).resolve().with_name("jfr"))
    command = [jfr, "print", "--json", "--stack-depth", "64", "--events", ",".join(EVENTS), str(recording)]
    raw = json.loads(run_tool(command))
    result = analyze_events(raw["recording"]["events"])
    if result["event_counts"].get("jdk.DataLoss"):
        raise RuntimeError("JFR reports lost events; reject this diagnostic recording")
    if not result["event_counts"].get("jdk.ExecutionSample"):
        raise RuntimeError("JFR contains no Java execution samples")
    result.update(recording=recording.name, recording_bytes=recording.stat().st_size,
                  recording_sha256=hashlib.sha256(recording.read_bytes()).hexdigest(), extraction_command=command)
    views = recording.with_suffix(".views.txt")
    view_commands = [[jfr, "summary", str(recording)],
                     *[[jfr, "view", "--width", "180", "--verbose", name, str(recording)]
                       for name in ("hot-methods", "allocation-by-site", "thread-cpu-load")]]
    views.write_text("\n\n".join("Command: " + " ".join(cmd) + "\n" + run_tool(cmd) for cmd in view_commands))
    result["views"] = views.name
    return result
