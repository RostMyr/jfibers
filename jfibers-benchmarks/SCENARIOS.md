# Four asynchronous use cases

These probes compare the same `CompletableFuture` sources without HTTP API or
network differences. They exercise the build-time transformed public fiber API,
ordinary virtual-thread `get()`, and explicit CompletableFuture continuations.
They are synthetic workload measurements, not HTTP, JDBC or production SLO claims.

```sh
./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify
python3 jfibers-benchmarks/scenarios_run.py --quick
python3 jfibers-benchmarks/scenarios_run.py --output /path/to/new/results
python3 jfibers-benchmarks/scenarios_summarize.py /path/to/new/results
```

Python 3 and the project's JDK are required. Existing output directories are
rejected. The normal protocol uses three independent JVMs per case, rotates
backend order, and runs sequentially. Timed workloads use five seconds of warmup
and five seconds of measurement plus draining. No JFR recording or custom fiber
profiling is enabled. Each JVM uses G1, a fixed 1 GiB heap, four active processors
and four virtual-thread carriers. Environment, source/artifact hashes, commands,
warmup results and each measured repetition are saved. `--quick` checks
correctness and is not performance evidence.

## 1. Mass waiting and a completion burst

10,000 and 100,000 tasks wait on independent, incomplete futures. Twenty 512-task
batches warm up each process. After the measured batch reaches its waits, two
explicit GCs with settling intervals precede the heap/RSS sample. Idle process CPU
is sampled for one second. One producer then releases all sources sequentially:
75% complete with a checked result, 25% are cancelled. Every task must finish with
the expected value or cancellation, and the scheduler must drain completely.

The retained heap delta includes input futures, task/result handles and the common
per-task timestamp/result bookkeeping. It is not the exact size of a fiber or
virtual thread. Linux process RSS also includes touched heap pages, native stacks,
code and JVM structures; RSS does not fall in lockstep with live heap after GC.
Both the full waiting RSS and its change from the warmed baseline are recorded.

Signal-to-finish latency starts immediately before completing/cancelling each
source and ends in the task body. The producer takes time to release the whole
batch; this is a completion **burst**, not literally simultaneous notification.
CompletableFuture uses an inline `handle` here, so its small continuation runs on
the producer. Its low notification latency must be read alongside the complete
release-and-drain time. Cancellation is of the awaited source, not interruption
of a task handle. Setup time is reported but warmup uses fewer tasks; large-batch
setup can include additional JIT/infrastructure growth.

## 2. Fan-out, deadlines and cancellation cleanup

256 concurrent flows each start four asynchronous branches, with delays of
2/4/6/8 ms. Half the operations join all four and check the sum. One quarter keep
the fourth branch pending and receive explicit operation cancellation after
5 ms; the remaining quarter keep it pending and hit a 20 ms deadline.

All backends use the same aggregate-future deadline/cancellation mechanism. In
their finalization path they cancel every unfinished source and remove its timer.
Counts, result checksums, expected outcomes and zero residual sources/timers are
validated. Cancellation/timeout latency includes cleanup. These are operation
deadlines; this probe does not compare `Fiber.withTimeout`, native owned-child
unwinding, virtual-thread interruption or a structured-concurrency API. Existing
library tests cover those fiber lifecycle features separately.

## 3. Fixed arrival rates and bounded overload

Requests are scheduled at 2,000, 10,000 and 30,000 arrivals/s, independently of
completion. Every intended arrival is attempted; a delayed producer catches up
without resetting the schedule or silently skipping requests. A shared FIFO has
256 waiting slots and 64 consumers. Arrivals to a full queue are rejected. Each
accepted operation waits on the common 1 ms service and performs 25,000 identical
integer-mixing iterations.

The end-to-end budget is 100 ms from the **intended** arrival, including generator
lag, admission queueing, service, dispatch and computation. Expired queued work
is discarded when dequeued. Pending service receives a deadline; computation is
cooperative and a late final result also counts as timed out. No preemption is
claimed. Every accepted operation is drained before the process exits.

The output includes offered/successful rates, rejection and timeout counts,
queue delay, successful and timed-out latency distributions, and arrival-generator
lag. Successful latency alone is censored by the deadline and cannot describe
all traffic. High generator lag means that run did not sustain evenly paced
arrivals; its intended-arrival latency still includes the lag. This is an
in-process deterministic arrival generator, not an isolated external load machine.

## 4. Mixed waiting and computation

256 concurrent flows perform three rounds of a 1 ms asynchronous wait followed by
deterministic integer work. Light tasks do 1,000 iterations per round. The two
cases contain either 0% or 10% heavy tasks, which do 100,000 iterations per round.
The heavy-task pattern is a fixed, dispersed permutation of each 100-operation
block, identical across backends. Checksums prevent dead-code elimination.

Per-class latency shows what happens to light tasks next to heavy ones. No elapsed
CPU duration is assumed for a fixed iteration count. The additional `JFIBERS_4`
control partitions the same 256 flows across four independent `FiberManager`
owners; ordinary `JFIBERS` has one owner. Both use the same source and service.
This controls for the library's per-manager execution width without changing its
scheduler or pretending that one manager executes user code on four cores.

## Execution and interpretation

Timed workloads share a four-thread scheduled service. CompletableFuture uses an
explicit four-thread continuation executor, keeping computation off both service
and arrival threads. This is different from the inline continuation in the mass
waiting probe and the older tiny-task benchmarks. Executor dispatch is part of
the measured implementation. Virtual threads use four carriers; a normal fiber
manager executes all user code on its single owner.

Throughput includes final draining. JVM-wide allocation is an approximate
`ThreadMXBean` total; common sample arrays are allocated before the measurement
counters start. CPU and GC are whole-process counters, including the synthetic
service, arrival generator and JVM activity. Full GCs precede timed measurement
and are excluded from its counters; mass-waiting GC counters explicitly include
the forced collections. Measurements include fixed harness overhead and should
not be presented as isolated library costs.

The summarizer reports medians and min/max across JVMs. It does not pool latency
samples or present a median of per-run p99 values as the p99 of all runs. Three
repetitions on a shared VM are descriptive evidence, not a statistical confidence
interval. Raw per-run results should accompany any speedup claim.
