#!/usr/bin/env python3
"""Compare core JARs with one benchmark artifact, rotating order between fresh JMH JVMs."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

from http_run import parse_variants, write_json
from run import JVM_FLAGS, MODULE, REPOSITORY, capture, read_optional


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", action="append", help="LABEL=/path/to/core.jar; repeat for paired comparisons")
    parser.add_argument("--scenarios", nargs="+", choices=["ready", "owner", "external"],
                        default=["ready", "owner", "external"])
    parser.add_argument("--diagnostics", nargs="+", choices=["off", "on", "standard", "recording"], default=["off"],
                        help="on: hooks only; standard: JDK profile JFR only; recording: hooks and profile JFR")
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--quick", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    variants = parse_variants(args.variant)
    jar = MODULE / "target/jfibers-benchmarks-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
    if not jar.is_file() or args.forks < 1:
        parser.error("Build the benchmarks first; --forks must be positive")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java:
        parser.error("Java is unavailable")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    metadata = {
        "started_utc": datetime.now(timezone.utc).isoformat(),
        "quick_smoke_only": args.quick,
        "git_commit": capture(["git", "rev-parse", "HEAD"]),
        "git_status": capture(["git", "status", "--short"]),
        "java": capture([java, "-version"]),
        "platform": platform.platform(),
        "cpu_model": next((line.split(":", 1)[1].strip() for line in (read_optional("/proc/cpuinfo") or "").splitlines()
                           if line.startswith("model name")), None),
        "host_logical_cpus": os.cpu_count(),
        "cpu_quota": read_optional("/sys/fs/cgroup/cpu.max"),
        "memory_limit": read_optional("/sys/fs/cgroup/memory.max"),
        "cpu_stat_before": read_optional("/sys/fs/cgroup/cpu.stat"),
        "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "source_sha256": {str(path.relative_to(REPOSITORY)): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in sorted([*MODULE.glob("*.py"), *MODULE.glob("src/main/java/**/*.java")])},
        "variants": {label: {"core_jar": str(path) if path else None,
                             "sha256": hashlib.sha256((path or jar).read_bytes()).hexdigest()}
                     for label, path in variants},
        "forks": 1 if args.quick else args.forks,
        "scenarios": args.scenarios,
        "diagnostics": args.diagnostics,
        "batch_size": 256,
        "score_unit": "us/op (one fiber, not the entire batch)",
        "commands": [],
    }
    results = []

    def save():
        write_json(output / "environment.json", metadata)
        write_json(output / "jmh.json", results)

    save()
    try:
        choices = [(variant, mode) for variant in variants for mode in args.diagnostics]
        for fork in range(metadata["forks"]):
            offset = fork % len(choices)
            for scenario in args.scenarios:
                for (label, core), mode in choices[offset:] + choices[:offset]:
                    name = f"{fork + 1}-{scenario}-{label}-{mode}"
                    print(name, flush=True)
                    flags = [*JVM_FLAGS, "-Xverify:all", "-Djfibers.jfr=" + str(mode in {"on", "recording"}).lower()]
                    if mode in {"standard", "recording"}:
                        flags.append(f"-XX:StartFlightRecording=settings=profile,filename={output / (name + '.jfr')}")
                    benchmark, parameter = ("TaskBenchmarks.jfibers", "delayMillis=0") if scenario == "ready" else (
                        "NotificationBenchmarks.notifications", "producer=" + scenario.upper())
                    command = [java, *JVM_FLAGS, "-cp", os.pathsep.join(str(path) for path in (core, jar) if path),
                               "org.openjdk.jmh.Main", benchmark, "-p", parameter, "-f", "1", "-prof", "gc",
                               "-foe", "true", "-jvmArgs", " ".join(flags), "-rf", "json", "-rff",
                               str(output / (name + ".json"))]
                    if args.quick:
                        command += ["-wi", "1", "-i", "1", "-w", "200ms", "-r", "200ms"]
                    metadata["commands"].append(command)
                    save()
                    with (output / (name + ".log")).open("w") as log:
                        subprocess.run(command, cwd=REPOSITORY, check=True, stdout=log,
                                       stderr=subprocess.STDOUT, timeout=120)
                    rows = json.loads((output / (name + ".json")).read_text())
                    if len(rows) != 1 or "gc.alloc.rate.norm" not in rows[0]["secondaryMetrics"]:
                        raise RuntimeError("Expected one successful JMH result with allocation data")
                    results.append({"variant": label, "diagnostics": mode, "scenario": scenario,
                                    "fork": fork + 1, **rows[0]})
                    save()
                    print(f"  {rows[0]['primaryMetric']['score']:.4f} us/fiber", flush=True)
        metadata["finished_utc"] = datetime.now(timezone.utc).isoformat()
    finally:
        metadata["cpu_stat_after"] = read_optional("/sys/fs/cgroup/cpu.stat")
        save()


if __name__ == "__main__":
    main()
