#!/usr/bin/env python3
"""Run sequential JVM measurements and retain raw data plus the exact commands."""

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


MODULE = Path(__file__).resolve().parent
REPOSITORY = MODULE.parent
JVM_FLAGS = [
    "-Xms512m",
    "-Xmx512m",
    "-XX:+UseG1GC",
    "-XX:ActiveProcessorCount=4",
    "-Djdk.virtualThreadScheduler.parallelism=4",
]
BACKENDS = ["JFIBERS", "VIRTUAL_THREADS", "COMPLETABLE_FUTURE"]


def read_optional(path):
    source = Path(path)
    return source.read_text().strip() if source.is_file() else None


def capture(command):
    return subprocess.run(command, cwd=REPOSITORY, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quick", action="store_true", help="Smoke check only; not performance evidence")
    parser.add_argument("--output", type=Path, help="New output directory; existing directories are rejected")
    args = parser.parse_args()
    jar = MODULE / "target/jfibers-benchmarks-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
    if not jar.is_file():
        parser.error("Build first: ./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java:
        parser.error("Java is unavailable; set JAVA_HOME")
    output = (args.output or MODULE / "target/results" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")).resolve()
    output.mkdir(parents=True, exist_ok=False)
    cpuinfo = read_optional("/proc/cpuinfo")
    cpu_model = next((line.split(":", 1)[1].strip() for line in (cpuinfo or "").splitlines()
                      if line.startswith("model name")), None)
    metadata = {
        "started_utc": datetime.now(timezone.utc).isoformat(),
        "quick_smoke_only": args.quick,
        "git_commit": capture(["git", "rev-parse", "HEAD"]),
        "git_status": capture(["git", "status", "--short"]),
        "java": capture([java, "-version"]),
        "platform": platform.platform(),
        "cpu_model": cpu_model,
        "host_logical_cpus": os.cpu_count(),
        "cpu_quota": read_optional("/sys/fs/cgroup/cpu.max"),
        "memory_limit": read_optional("/sys/fs/cgroup/memory.max"),
        "cpu_stat_before": read_optional("/sys/fs/cgroup/cpu.stat"),
        "jvm_flags": JVM_FLAGS,
        "batch_size": 256,
        "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "benchmark_source_sha256": {
            str(path.relative_to(REPOSITORY)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(MODULE.rglob("*.java")) if "target" not in path.parts
        },
        "commands": [],
    }

    def save_metadata():
        (output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")

    jmh = [java, *JVM_FLAGS, "-jar", str(jar), "TaskBenchmarks", "-prof", "gc", "-foe", "true",
           "-jvmArgs", " ".join(JVM_FLAGS), "-rf", "json", "-rff", str(output / "jmh.json")]
    if args.quick:
        jmh += ["-f", "1", "-wi", "1", "-i", "1", "-w", "200ms", "-r", "200ms"]
    metadata["commands"].append(jmh)
    save_metadata()
    print(f"Running JMH; progress log: {output / 'jmh.log'}", flush=True)
    with (output / "jmh.log").open("w") as log:
        subprocess.run(jmh, cwd=REPOSITORY, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=900)
    results = json.loads((output / "jmh.json").read_text())
    if len(results) != 6 or any("gc.alloc.rate.norm" not in result["secondaryMetrics"] for result in results):
        raise RuntimeError("Expected six successful JMH scenarios with allocation measurements")

    records = []
    counts = [1000] if args.quick else [1000, 10000]
    forks = 1 if args.quick else 3
    idle_millis = "250" if args.quick else "1000"
    for fork in range(forks):
        # Rotate the order between independent JVM repetitions.
        backends = BACKENDS[fork:] + BACKENDS[:fork]
        for count in counts:
            for backend in backends:
                command = [java, *JVM_FLAGS, "-cp", str(jar),
                           "com.github.rostmyr.jfibers.benchmarks.WaitingProbe", backend, str(count), idle_millis]
                metadata["commands"].append(command)
                save_metadata()
                print(f"Waiting probe: fork={fork + 1}, tasks={count}, backend={backend}", flush=True)
                completed = subprocess.run(command, cwd=REPOSITORY, check=True, text=True,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
                record = json.loads(completed.stdout)
                record["fork"] = fork + 1
                records.append(record)
                (output / "waiting.json").write_text(json.dumps(records, indent=2) + "\n")
    metadata["finished_utc"] = datetime.now(timezone.utc).isoformat()
    metadata["cpu_stat_after"] = read_optional("/sys/fs/cgroup/cpu.stat")
    save_metadata()
    print(f"Results: {output}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as failure:
        print(f"Benchmark failed: {failure}", file=sys.stderr)
        if getattr(failure, "stderr", None):
            print(failure.stderr, file=sys.stderr)
        sys.exit(1)
