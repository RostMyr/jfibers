# jfibers performance comparison

This module measures the existing implementation without changing its scheduler
or transformer. It is included only with the Maven `benchmarks` profile.

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
or direct cancellation of the task handle. jfibers currently has no task-level
`cancel()` API; `CompletableFuture.cancel()` does not automatically interrupt a
supplier's execution.

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
- Repeated polling is the main observed waiting cost in the current scheduler.
  Its slightly shorter delayed-batch time comes with approximately one occupied
  core. Completion-driven wakeups are the first performance change to investigate.
- Synchronous `CompletableFuture` continuations have the lowest cost for these
  tiny already-completed operations. Virtual-thread task creation and scheduling
  cost more here, while supporting ordinary blocking Java code and full stack
  semantics. These numbers do not establish application throughput rankings for
  HTTP, JDBC, CPU-heavy workloads, or more complex coroutine bodies.

Cancellation results describe input cancellation plus exception observation.
They do not supply a missing task-cancellation API for jfibers, and the overlap
and variation between samples should be retained when interpreting the timings.
Runtime-agent startup overhead is outside these measurements.
