# jfibers performance comparison

This module measures the existing implementation without changing its scheduler
or transformer. It is included only with the Maven `benchmarks` profile.

For the bounded HTTP workload, latency percentiles and separate JFR profiling,
see [HTTP.md](HTTP.md). The task microbenchmarks below measure different work.
The [four-use-case probes](SCENARIOS.md) cover mass waiting, fan-out cleanup,
fixed-rate overload, and mixed waiting/computation with per-class latency.
Their [recorded results](SCENARIO_RESULTS.md) include a comparison with four
independent fiber managers under mixed waiting and computation.
The [pool API comparison](POOL_RESULTS.md) measures bounded scheduler owners and
explicit compute offload, including repeated before/after scheduler checks.
The [request-per-task HTTP benchmark](REQUEST_HTTP.md) adds one fresh root per
accepted request, bounded admission, cancellation cleanup, server deadlines and
an independently measured client latency objective.
Its [recorded results](REQUEST_HTTP_RESULTS.md) separate light and heavy request
success from total throughput under overload.
The [heavy-request admission experiment](HEAVY_ADMISSION.md) adds a separate
whole-request budget and compares the same limits with virtual threads.
Its [48-pair results](HEAVY_ADMISSION_RESULTS.md) report per-class availability,
rejection reasons and completed computation on timed-out requests.
The [downstream HTTP experiment](DOWNSTREAM_HTTP.md) adds real asynchronous
dependency calls, physical I/O cleanup and disjoint CPU affinity for JVM roles.
Its [60-run results](DOWNSTREAM_HTTP_RESULTS.md) show the tradeoffs between
heavy admission, compute width and light-request latency, including the
cancellation regression found and fixed during validation.

## Run

Requires the project's JDK and Maven versions, plus Python 3. Build from the
repository root so that the actual Maven plugin instruments `FiberWorkload`:

```sh
./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify
python3 jfibers-benchmarks/run.py --quick
python3 jfibers-benchmarks/run.py
python3 jfibers-benchmarks/summarize.py jfibers-benchmarks/target/results/RUN_DIRECTORY
```

`--quick` only checks that the harness runs and verifies results. Its timings
are not performance evidence. Full runs take several minutes. An optional
`--output PATH` selects a new results directory; existing directories are rejected.

The runner records JMH's raw iteration data, all waiting-probe samples, JVM
arguments, source and artifact hashes, Java version, hardware details, CPU quota,
and exact commands. It runs measurements sequentially. Avoid other workloads on
the machine while collecting data.

## Workloads

Every task awaits a `CompletableFuture<Integer>` input and returns its value plus
one. Results are checked, including propagated cancellation. Small cached integer
values keep the useful work and payload allocation constant across backends.

| Backend | Task implementation |
| --- | --- |
| jfibers | The public `Fiber.call`/`Fiber.result` API, instrumented at build time and driven by `FiberManager` |
| Virtual threads | One task submitted to a reused virtual-thread-per-task executor; waits with `Future.get()` |
| CompletableFuture | One `thenApply` dependent stage; executes on the caller or input-completion thread |

The `CompletableFuture` implementation uses its normal synchronous continuations.
Virtual threads use four carrier threads. jfibers uses its existing single-thread
scheduler. The measured work is waiting plus one addition; it does not exercise
CPU-intensive parallel work, real network I/O, JDBC, or application frameworks.

### JMH: completed inputs and delayed completion

Each invocation creates, submits, and collects a batch of 256 tasks. Inputs are
either already completed or released by one timer thread after a 1 ms delay.
For delayed inputs, the timer starts after all tasks are submitted or registered.
Completing the batch's inputs sequentially on that timer models a completion burst.

JMH 1.37 runs three forks per scenario, each with three 1-second warmup iterations
and five 1-second measurement iterations. There is one JMH worker thread. All JVMs
use G1, a fixed 512 MiB heap, four active processors, and four virtual-thread
carriers. The `gc` profiler collects allocation counters across the JVM.

Raw JMH time is normalized by 256 operations per invocation. `summarize.py`
converts it back to total batch time and retains JMH's reported 99.9% confidence
interval. Allocation is reported per completed task, including input futures,
task handles, result collection, and timer overhead where applicable.

**Batch time is not individual-request latency.** In particular, dividing a
1 ms wait among 256 operations does not mean an individual task waited only a
few microseconds. The harness does not report latency percentiles.

### Waiting: CPU, retained heap, and source cancellation

Each backend is tested at 1,000 and 10,000 simultaneously pending tasks in three
fresh JVMs. Execution order rotates between repetitions. Each JVM first warms
up successful and cancelled batches. All tasks reach their wait before sampling.

Two explicit full GCs and settling intervals precede each heap sample. The
reported heap delta is the increase from a warmed baseline to the pending batch.
It includes input futures, task representations, result handles, and parked
virtual-thread stack chunks. It is an estimate of **retained Java heap**, not
process RSS, native memory, or the exact object size of one task. The warmup's
executor infrastructure is part of the baseline.

The process CPU counter is sampled during a 1-second idle interval. A value of
`1.0` means one CPU core was occupied on average. Small values are affected by
counter granularity and JVM background activity.

Finally, all input futures are cancelled with `cancel(false)`. The probe measures
time until the batch finishes and all cancellation results have been observed.
This includes exception delivery and result collection. It measures **cancellation
of the awaited operation and draining its dependents**, not interruption latency
or direct cancellation of the task handle. The original baseline predates the task-level
`cancel()` API added later; this probe still cancels only input futures.
`CompletableFuture.cancel()` does not automatically interrupt a supplier's execution.

Raw data for the recorded comparison is in [results/2026-10-10](results/2026-10-10).

## Recorded results: 10 October 2026

Temurin 27+35, Linux x86-64, AMD EPYC 9V74 VM, CPU quota equivalent to four cores,
G1 and a 512 MiB heap. The cgroup counters show no additional CPU throttling during
this run. The production library is commit `c8928dc`; the raw environment record
includes hashes of the benchmark sources used alongside it.

Each batch contains 256 tasks. Times below are JMH means with the reported 99.9%
confidence interval half-width; allocation is per completed task.

| Backend | Ready inputs: batch time, us | Ready: bytes/task | 1 ms source delay: batch time, us | Delayed: bytes/task |
| --- | ---: | ---: | ---: | ---: |
| jfibers | 8.27 ± 0.15 | 72.3 | 1092.80 ± 5.69 | 73.0 |
| CompletableFuture | 2.99 ± 0.14 | 40.3 | 1114.46 ± 9.56 | 81.2 |
| Virtual threads | 112.07 ± 26.62 | 369.0 | 1250.35 ± 22.24 | 859.1 |

Waiting results below are medians for 10,000 pending tasks. Brackets show the
observed minimum and maximum across the three independent JVMs.

| Backend | Extra live heap, MiB | Idle CPU, cores | Cancel input sources and drain, ms |
| --- | ---: | ---: | ---: |
| jfibers | 0.688 [0.688, 0.688] | 1.000 [1.000, 1.000] | 8.527 [8.207, 23.110] |
| CompletableFuture | 0.764 [0.764, 0.764] | 0.000 [0.000, 0.010] | 24.727 [22.645, 26.925] |
| Virtual threads | 21.816 [14.969, 21.836] | 0.010 [0.000, 0.010] | 37.537 [30.200, 41.404] |

At 1,000 pending tasks, jfibers also consumes approximately one CPU core while
waiting. Both other implementations remain near zero in the idle CPU counter.
The complete 1,000-task results and all individual samples are in `waiting.json`.

These measurements support three architectural conclusions:

- Fiber task representations are compact, with retained heap close to the
  `CompletableFuture` pipeline for this workload. Virtual threads retain more heap
  while parked; their footprint varies noticeably between JVM repetitions, so the
  measured range should not be treated as a fixed per-thread size.
- Repeated polling was the main observed waiting cost in the baseline scheduler.
  Its slightly shorter delayed-batch time comes with approximately one occupied
  core. Completion-driven wakeups are the first performance change to investigate.
- Synchronous `CompletableFuture` continuations have the lowest cost for these
  tiny already-completed operations. Virtual-thread task creation and scheduling
  cost more here, while supporting ordinary blocking Java code and full stack
  semantics. These numbers do not establish application throughput rankings for
  HTTP, JDBC, CPU-heavy workloads, or more complex coroutine bodies.

Cancellation results describe input cancellation plus exception observation.
They do not measure the later task-cancellation API; the overlap and variation
between samples should be retained when interpreting the timings.
Runtime-agent startup overhead is outside these measurements.


## Completion notifications: 10 October 2026

A second complete run, using library commit `0beb313` and unchanged benchmark
sources/JVM settings, is retained in
[results/2026-10-10-notifications](results/2026-10-10-notifications).
The original measurements above remain the polling baseline. CPU throttling
counters did not increase during this run.

| jfibers measurement | Polling baseline | Completion notifications |
| --- | ---: | ---: |
| Ready 256-task batch, us (99.9% CI) | 8.27 ± 0.15 | 10.66 ± 0.24 |
| Ready allocation, bytes/task | 72.3 | 80.3 |
| 1 ms delayed batch, us (99.9% CI) | 1092.80 ± 5.69 | 1120.79 ± 10.01 |
| Delayed allocation, bytes/task | 73.0 | 169.0 |
| 10,000 waiting tasks: retained heap, MiB | 0.688 | 1.483 |
| 10,000 waiting tasks: idle CPU, cores | 1.000 | 0.000 |
| Cancel sources + drain, median ms | 8.527 | 20.382 |

Heap and idle CPU values were the same to three decimals across all three new
waiting-probe JVMs; cancellation ranged from 19.993 to 21.974 ms. For 1,000 tasks,
retained heap was 0.146 MiB and idle CPU 0.000 cores in all three probes.
Notifications remove the busy wait, at a measurable allocation, memory and
scheduling cost. Near-zero CPU is limited by the process counter's resolution.
These tests use CompletableFuture inputs: ordinary Future implementations without
callbacks use the documented bounded polling fallback or an explicit adapter.
Use `summarize.py` to inspect the full comparison, including both other backends.


## Control-flow compiler and lifecycle: 10 October 2026

The complete final run uses library commit `0c72708`, including the JVM-frame
compiler, public lifecycle states, cooperative cancellation, deadlines and owned
child cleanup. Raw data is in
[results/2026-10-10-lifecycle](results/2026-10-10-lifecycle). All benchmark Java source
hashes match the original baseline; JVM settings and machine are unchanged. The
artifact hash is recorded, all six JMH scenarios and 18 waiting probes completed,
and cgroup throttling counters did not increase.

| Backend | Ready 256-task batch, us | Ready: bytes/task | 1 ms delayed batch, us | Delayed: bytes/task |
| --- | ---: | ---: | ---: | ---: |
| jfibers | 25.23 ± 0.41 | 136.3 | 1127.31 ± 7.45 | 233.0 |
| CompletableFuture | 2.92 ± 0.03 | 40.3 | 1116.92 ± 8.20 | 81.2 |
| Virtual threads | 107.20 ± 16.25 | 369.0 | 1236.10 ± 19.76 | 852.2 |

The errors are JMH's 99.9% confidence interval half-widths. Waiting measurements
below are medians for 10,000 pending tasks, with min/max ranges across three JVMs.

| Backend | Extra live heap, MiB | Idle CPU, cores | Cancel sources + drain, ms |
| --- | ---: | ---: | ---: |
| jfibers | 2.094 [2.094, 2.094] | 0.000 [0.000, 0.010] | 19.139 [15.978, 21.264] |
| CompletableFuture | 0.764 [0.764, 0.764] | 0.000 [0.000, 0.000] | 22.483 [22.041, 23.000] |
| Virtual threads | 21.797 [15.036, 21.847] | 0.000 [0.000, 0.000] | 34.368 [33.408, 34.485] |

For 1,000 waiting fibers, retained heap is 0.207 MiB; idle CPU is 0.010 cores
[0.000, 0.010], and source cancellation plus drain is 2.098 ms [1.954, 2.801].

Compared with the original restricted compiler/polling scheduler, idle CPU falls
from approximately one core to the counter's noise floor. Ready-batch time rises
from 8.27 to 25.23 us (about 3.05x), and waiting heap from 0.688 to 2.094 MiB.
The intermediate notification-only run isolates part of that cost; the remaining
changes add frame preservation and lifecycle bookkeeping. The final library trades
some of its tiny-operation advantage for correct control flow, cleanup and idle
behavior. These measurements do not identify the cost of each individual feature.

CompletableFuture remains cheapest for this small inline-continuation workload.
Virtual threads use more parked heap here but support ordinary blocking Java
stacks. This comparison does not establish application throughput or tail latency,
and source cancellation remains distinct from the new task-cancellation API.
## Scheduler notification comparisons

The [profiling and optimization report](OPTIMIZATION.md) includes the final
before/after results, JFR overhead, matched HTTP API controls and a rejected
prototype. It distinguishes stable short-task gains from variable I/O results.

`NotificationBenchmarks` measures a batch of 256 transformed fibers awaiting
CompletableFutures. A sentinel runs after all fibers suspend, then completes the
inputs either on the scheduler owner (`OWNER`) or on one reused executor thread
(`EXTERNAL`). There is no fixed timer delay masking notification overhead. Every
result is checked; task creation, completion callbacks, queues and resume costs
are included. These are microbenchmarks, not HTTP throughput estimates.

After building the benchmark module, compare compatible runtime JARs while
keeping the benchmark artifact fixed:

```sh
python3 jfibers-benchmarks/scheduler_run.py \
  --variant before=/path/before-core.jar --variant after=/path/after-core.jar \
  --output jfibers-benchmarks/target/scheduler-comparison
```

The default runs ready futures and both notification producers, rotating the
runtime order across three fresh JVMs. Each JVM has three 1-second warmup and
five 1-second measurement iterations, with JMH's GC profiler. Scores are
microseconds **per fiber**; multiply by 256 for a batch. Raw fork/iteration data,
commands, hashes and environment are retained. `--quick` is only a smoke test.

`--diagnostics off standard recording` separately compares normal execution,
JDK JFR's `profile` recording alone, and the same recording with fiber events.
`--diagnostics on` enables hooks without an active recording.
These costs depend on workload, event thresholds and sampling frequency; do not
combine them into ordinary throughput results. See [PROFILING.md](../PROFILING.md).
