#!/usr/bin/env python3
"""Run the four controlled use cases, sequentially, with fresh JVMs and raw results."""

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

from run import BACKENDS, MODULE, REPOSITORY, capture, read_optional

JVM_FLAGS = ["-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-XX:ActiveProcessorCount=4",
             "-Djdk.virtualThreadScheduler.parallelism=4", "-Djfibers.jfr=false"]
PACKAGE = "com.github.rostmyr.jfibers.benchmarks."


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quick", action="store_true", help="Correctness smoke only; not performance evidence")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--scenarios", nargs="+", choices=["mass", "fanout", "open", "mixed"],
                        default=["mass", "fanout", "open", "mixed"])
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmup-ms", type=int, default=5000)
    parser.add_argument("--measure-ms", type=int, default=5000)
    parser.add_argument("--counts", nargs="+", type=int, default=[10000, 100000])
    parser.add_argument("--rates", nargs="+", type=int, default=[2000, 10000, 30000])
    parser.add_argument("--weights", nargs="+", type=int, default=[0, 10])
    parser.add_argument("--backends", nargs="+", choices=[*BACKENDS, "JFIBERS_4", "FIBER_POOL_1",
                        "FIBER_POOL_4", "FIBER_POOL_OFFLOAD"], help="Override backends for timed scenarios")
    args = parser.parse_args()
    if args.forks < 1 or not 100 <= args.warmup_ms <= 60000 or not 100 <= args.measure_ms <= 60000:
        parser.error("Invalid forks or duration")
    if any(not 1 <= n <= 200000 for n in args.counts + args.rates) or any(not 0 <= w <= 100 for w in args.weights):
        parser.error("Invalid counts, rates or percentages")
    if args.backends and "mass" in args.scenarios:
        parser.error("--backends applies to timed scenarios; select --scenarios fanout open mixed")
    if args.backends and "FIBER_POOL_OFFLOAD" in args.backends and any(s != "mixed" for s in args.scenarios):
        parser.error("FIBER_POOL_OFFLOAD is a mixed-workload experiment; select --scenarios mixed")
    jar = MODULE / "target/jfibers-benchmarks-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
    if not jar.is_file():
        parser.error("Build first: ./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java:
        parser.error("Java is unavailable")
    output = (args.output or MODULE / "target/scenarios" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")).resolve()
    output.mkdir(parents=True, exist_ok=False)
    forks = 1 if args.quick else args.forks
    warmup, measure = (300, 400) if args.quick else (args.warmup_ms, args.measure_ms)
    cases = []
    if "mass" in args.scenarios:
        cases += [("MASS_WAITING", count, BACKENDS) for count in ([1000] if args.quick else args.counts)]
    if "fanout" in args.scenarios:
        cases += [("FANOUT", 0, BACKENDS)]
    if "open" in args.scenarios:
        cases += [("OPEN_LOOP", rate, BACKENDS) for rate in ([2000, 30000] if args.quick else args.rates)]
    if "mixed" in args.scenarios:
        cases += [("MIXED", weight, [*BACKENDS, "JFIBERS_4"]) for weight in args.weights]
    if args.backends:
        cases = [(scenario, parameter, args.backends) for scenario, parameter, _ in cases]
    cpuinfo = read_optional("/proc/cpuinfo") or ""
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
        "jvm_flags": JVM_FLAGS,
        "forks": forks,
        "warmup_ms": warmup,
        "measure_ms": measure,
        "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "source_sha256": {
            str(path.relative_to(REPOSITORY)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted([*MODULE.rglob("*.java"), *MODULE.glob("*.py")]) if "target" not in path.parts
        },
        "commands": [],
    }
    records = []

    def save():
        (output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
        (output / "results.json").write_text(json.dumps(records, indent=2) + "\n")

    for fork in range(forks):
        for scenario, parameter, backends in cases:
            offset = fork % len(backends)
            for backend in backends[offset:] + backends[:offset]:
                if scenario == "MASS_WAITING":
                    probe = [PACKAGE + "MassWaitingProbe", backend, str(parameter), "100" if args.quick else "1000"]
                else:
                    probe = [PACKAGE + "ScenarioProbe", backend, scenario, str(warmup), str(measure),
                             str(parameter), "64" if scenario == "OPEN_LOOP" else "256"]
                command = [java, *JVM_FLAGS, "-cp", str(jar), *probe]
                metadata["commands"].append(command)
                save()
                name = f"{scenario.lower()}-{parameter}-{backend.lower()}-{fork + 1}"
                print(f"[{len(records) + 1}] {name}", flush=True)
                completed = subprocess.run(command, cwd=REPOSITORY, check=True, text=True,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
                (output / f"{name}.stderr.log").write_text(completed.stderr)
                raw = json.loads(completed.stdout)
                result = raw.get("result", raw)
                if result["backend"] != backend or result["scenario"] != scenario or result["errors"] != 0:
                    raise RuntimeError(f"Invalid probe result: {raw}")
                if scenario == "MASS_WAITING":
                    if result["successful"] + result["cancelled"] != parameter:
                        raise RuntimeError("Missing waiting results")
                else:
                    for phase in [raw["warmup"], result]:
                        if phase["offered"] != phase["successful"] + phase["cancelled"] + phase["timed_out"] + phase["rejected"]:
                            raise RuntimeError("Missing operation results")
                        if phase["pending_sources"] or phase["pending_timers"] or phase["errors"]:
                            raise RuntimeError("Leaked operations or incorrect result")
                    if scenario == "OPEN_LOOP" and result["offered"] != measure * parameter // 1000:
                        raise RuntimeError("Incorrect arrival count")
                record = {"fork": fork + 1, "case": name, **raw}
                records.append(record)
                save()
    metadata["finished_utc"] = datetime.now(timezone.utc).isoformat()
    metadata["cpu_stat_after"] = read_optional("/sys/fs/cgroup/cpu.stat")
    save()
    print(f"Results: {output}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as failure:
        print(f"Benchmark failed: {failure}", file=sys.stderr)
        if getattr(failure, "stdout", None):
            print(failure.stdout, file=sys.stderr)
        if getattr(failure, "stderr", None):
            print(failure.stderr, file=sys.stderr)
        sys.exit(1)
