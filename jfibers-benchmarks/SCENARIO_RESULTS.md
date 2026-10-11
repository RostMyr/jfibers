# Results: four asynchronous use cases, 10 October 2026

Fibers retain substantially less heap than parked virtual threads in this workload.
Fan-out throughput is almost identical. With significant computation, a single
`FiberManager` becomes the limiting execution width: distributing the same mixed
work across four managers raises throughput from 11,794 to 43,854 operations/s,
compared with 42,504 for virtual threads. These measurements support parallel
scheduling or compute offload for this workload. They do not establish a general
performance ranking of compile-time fibers and JVM virtual threads.

[Methodology and commands](SCENARIOS.md) ·
[Per-JVM results](results/2026-10-10-scenarios/results.json) ·
[Machine, commands and hashes](results/2026-10-10-scenarios/environment.json) ·
[Machine-readable summary](results/2026-10-10-scenarios/summary.json)

The source is commit `b31ab322c105bb0c083a48f10fa7ffbab9165558`; production runtime
code is unchanged from `9d1f8a5`. The series ran sequentially from 19:39 to 19:50
America/New_York (23:39–23:50 UTC), using Temurin 27+35, G1, a 1 GiB heap and a
shared AMD EPYC 9V74 VM with a four-core CPU quota and 16 GiB memory limit.

There are **78 fresh JVM runs: 26 configurations, three repetitions each**. Timed
phases use 5 s warmup and 5 s measurement, followed by complete draining. JFR and
custom fiber diagnostics are off. Tables show **median [minimum, maximum] across
JVMs**. A reported latency p99 is the median and range of the three per-run p99s,
not a percentile pooled across all runs. These are descriptive ranges, not
confidence intervals.

## 1. Mass waiting: memory advantage, different notification behavior

The burst completes 75% of sources successfully and cancels 25%. Heap delta
includes the common timestamp/result bookkeeping and task handles. RSS is the
whole waiting process, not the heap delta.

| Tasks | Backend | Extra live heap, MiB | Waiting process RSS, MiB | Whole release + drain, ms |
| ---: | --- | ---: | ---: | ---: |
| 10,000 | Fibers, one manager | 2.44 [2.44, 2.44] | 78.82 [78.74, 79.32] | 10.36 [8.13, 14.54] |
| 10,000 | Virtual threads | 17.60 [12.94, 17.93] | 110.58 [108.06, 113.33] | 14.76 [13.78, 21.63] |
| 10,000 | CompletableFuture | 1.33 [1.33, 1.33] | 72.27 [72.23, 72.39] | 5.64 [3.87, 6.80] |
| 100,000 | Fibers, one manager | 23.68 [23.68, 23.68] | 93.65 [93.63, 93.77] | 101.31 [84.70, 142.71] |
| 100,000 | Virtual threads | 148.31 [92.26, 157.75] | 227.54 [217.82, 233.46] | 88.21 [75.89, 127.26] |
| 100,000 | CompletableFuture | 13.02 [13.02, 13.02] | 84.05 [83.82, 84.22] | 32.52 [31.53, 37.61] |

At 100,000 waits, median extra live heap is **6.3 times lower for fibers** than for
virtual threads; whole-process RSS is about 2.4 times lower. The virtual-thread
heap range is wide (92–158 MiB), so 148 MiB is not a fixed per-100,000-thread size.
CompletableFuture is smaller still. Median idle CPU is zero for every combination;
individual readings reach 0.01 cores, close to the process counter's resolution.

Notification timestamps need care:

| Backend, 100,000 tasks | Producer release loop, ms | Success signal-to-finish p99, ms | Cancellation signal-to-finish p99, ms |
| --- | ---: | ---: | ---: |
| Fibers, one manager | 99.61 [83.19, 141.07] | 0.3656 [0.2335, 0.7540] | 0.3668 [0.2344, 0.7554] |
| Virtual threads | 51.96 [50.06, 58.87] | 36.1167 [25.7079, 73.4892] | 36.1175 [25.7110, 73.4898] |
| CompletableFuture | 28.58 [27.66, 31.83] | 0.0002 [0.0002, 0.0002] | 0.0021 [0.0019, 0.0024] |

Fibers resume an already-notified task quickly, but their producer also takes
longer to release all the sources. Virtual threads receive the burst faster and
then have more queued work to drain. Consequently, these signal-relative p99s do
not compare an identical simultaneous arrival schedule. CompletableFuture runs
the tiny continuation inline on the producer. The whole-burst ranges overlap for
fibers and virtual threads; no general burst-speed advantage is established.

## 2. Fan-out: similar throughput; deadlines dominate this workload

256 concurrent flows start four branches. Half succeed, one quarter cancel after
5 ms and one quarter time out after 20 ms; finalization cancels unfinished sources
and removes their timers. Throughput below counts **all terminal outcomes**.

| Backend | Completed operations/s | Success p99, ms | Cancellation + cleanup p99, ms | Timeout + cleanup p99, ms |
| --- | ---: | ---: | ---: | ---: |
| Fibers, one manager | 24,713 [24,663, 24,746] | 8.61 [8.21, 9.53] | 6.15 [5.24, 6.32] | 20.98 [20.21, 21.54] |
| Virtual threads | 24,744 [24,730, 24,746] | 8.21 [8.17, 8.31] | 5.25 [5.21, 5.32] | 20.22 [20.20, 20.34] |
| CompletableFuture | 24,675 [24,588, 24,714] | 9.25 [8.82, 9.91] | 6.09 [5.86, 6.79] | 21.33 [20.83, 22.18] |

The throughput medians differ by less than 0.3%. This scenario provides no useful
throughput winner. Virtual threads have lower measured cancellation/timeout tails
here. Every operation had its expected outcome, and all source/timer counts were
zero after draining. This checks cleanup of shared asynchronous operations, not
native structured-concurrency or task-interruption semantics.

## 3. Fixed-rate arrivals: one manager reaches its compute limit first

64 active flows, a FIFO of 256 waiting slots, 1 ms source delay and 25,000 integer
iterations per accepted request. A 100 ms budget starts at intended arrival.
Offered traffic is independent of completions; rejected work is retained in the
counts. Throughput includes draining; latency below covers successful traffic only.

| Offered/s | Backend | Successful/s | Rejected, % of offered | Successful p99, ms |
| ---: | --- | ---: | ---: | ---: |
| 2,000 | Fibers, one manager | 2,000 [2,000, 2,000] | 0.000 [0.000, 0.000] | 1.82 [1.34, 2.79] |
| 2,000 | Virtual threads | 2,000 [1,999, 2,000] | 0.000 [0.000, 0.000] | 1.37 [1.36, 1.41] |
| 2,000 | CompletableFuture | 2,000 [2,000, 2,000] | 0.000 [0.000, 0.000] | 1.78 [1.38, 2.10] |
| 10,000 | Fibers, one manager | 9,997 [9,997, 9,997] | 0.000 [0.000, 0.000] | 3.84 [2.03, 4.52] |
| 10,000 | Virtual threads | 9,997 [9,996, 9,998] | 0.000 [0.000, 0.000] | 1.86 [1.52, 3.56] |
| 10,000 | CompletableFuture | 9,997 [9,996, 9,998] | 0.000 [0.000, 0.000] | 6.55 [4.63, 8.18] |
| 30,000 | Fibers, one manager | 14,755 [13,122, 14,819] | 50.591 [50.399, 55.993] | 37.46 [27.80, 54.03] |
| 30,000 | Virtual threads | 29,986 [29,949, 29,992] | 0.002 [0.000, 0.109] | 5.38 [2.76, 6.09] |
| 30,000 | CompletableFuture | 28,843 [27,788, 28,880] | 3.684 [3.552, 7.175] | 15.45 [14.83, 15.53] |

All measured runs at 2,000 and 10,000 arrivals/s accepted every request and met the
deadline. At 30,000/s, one manager rejects roughly half the offered work. Its second
repetition also has **128 timed-out accepted operations**; the other two have zero.
Both other backends have zero measured deadline misses, while their nonzero
rejections remain visible above. A median timeout count of zero would hide this
fiber outlier, so it must not be used to claim every accepted request met its budget.

| Backend at 30,000/s | Intended-arrival to worker-start p99, ms | Generator lag p99, ms |
| --- | ---: | ---: |
| Fibers, one manager | 33.08 [25.09, 50.16] | 0.15 [0.07, 0.25] |
| Virtual threads | 3.34 [1.03, 4.08] | 0.56 [0.34, 0.92] |
| CompletableFuture | 12.42 [12.03, 12.71] | 1.04 [0.97, 1.13] |

The FIFO never exceeds its 256-slot bound. Generator lag is included in response
latency; the generator shares the process and machine with the workers. Therefore
this is evidence about this bounded in-process workload, not a production open-loop
SLO guarantee. Four-manager fibers were not measured in this scenario.

## 4. Mixed waits and computation: execution width changes the result

Each operation performs three rounds of a 1 ms wait and integer work. Light tasks
do 1,000 iterations per round; heavy tasks do 100,000. Total concurrency remains
256, including the four-manager control (64 flows per manager).

| Heavy tasks | Backend | Operations/s | Light-task p99, ms | Heavy-task p99, ms | Whole-process CPU, cores |
| ---: | --- | ---: | ---: | ---: | ---: |
| 0% | Fibers, one manager | 66,290 [66,222, 71,337] | 7.10 [4.89, 9.34] | — | 1.31 [1.26, 1.35] |
| 0% | Fibers, four managers | 76,043 [73,383, 76,821] | 4.63 [4.06, 5.26] | — | 1.53 [1.38, 1.63] |
| 0% | Virtual threads | 75,616 [75,347, 76,379] | 4.62 [3.62, 5.18] | — | 1.20 [1.18, 1.22] |
| 0% | CompletableFuture | 73,290 [72,371, 73,774] | 5.54 [5.06, 5.62] | — | 1.28 [1.13, 1.49] |
| 10% | Fibers, one manager | 11,794 [11,739, 12,009] | 23.90 [22.64, 24.07] | 24.24 [23.28, 24.62] | 1.11 [1.08, 1.11] |
| 10% | Fibers, four managers | 43,854 [42,706, 44,091] | 10.32 [10.19, 10.68] | 10.79 [10.78, 11.15] | 3.94 [3.89, 3.95] |
| 10% | Virtual threads | 42,504 [42,180, 42,648] | 11.46 [10.97, 12.25] | 12.75 [11.88, 13.12] | 3.96 [3.93, 3.97] |
| 10% | CompletableFuture | 41,168 [41,024, 42,091] | 10.71 [9.80, 11.26] | 11.18 [10.36, 11.64] | 3.84 [3.84, 3.85] |

With 10% heavy tasks, four managers produce **3.72 times** the throughput of one
and reduce light-task p99 from **23.90 to 10.32 ms**. The four-manager median is
close to virtual threads (43.9k vs 42.5k operations/s). This does not establish a
universal fiber advantage: the shared CPU budget is nearly exhausted in these
parallel runs. A single manager remains cooperative, so a long compute section
delays every other runnable fiber assigned to that owner.

| Backend, 10% heavy | Allocated bytes/accepted operation | Measured GC time, ms |
| --- | ---: | ---: |
| Fibers, one manager | 989 [989, 990] | 0 [0, 0] |
| Fibers, four managers | 983 [983, 984] | 0 [0, 0] |
| Virtual threads | 907 [906, 907] | 0 [0, 0] |
| CompletableFuture | 1,667 [1,667, 1,668] | 1 [0, 2] |

Virtual threads are reused as long-lived flows here; creating one thread per
operation is not part of this workload. Their lower allocation per operation in
this table is compatible with their higher retained heap in the separate mass
waiting test. CompletableFuture uses a four-thread continuation executor for timed
work, keeping computation off the source timers and load generator; its dispatch
cost is included. The mass-waiting continuation is inline instead.

## Validation and practical direction

- All **82 library/build tests** passed. The 20-case quick benchmark smoke passed
  and is now included in CI. No new findings were reported for handwritten
  benchmark code; existing production findings and generated JMH padding warnings
  remain. Remote CI status is not part of this local validation record.
- Measured timed phases attempted **9,485,450 operations**: 9,228,191 were accepted,
  257,259 rejected, and every outcome was accounted for. Of accepted operations,
  8,670,035 succeeded, 279,016 were deliberately cancelled, and 279,140 timed out
  (including the deliberate fan-out deadlines and 128 overload misses).
- Warmup plus measurement attempted **18,825,341 timed operations**. The separate
  measured mass-waiting runs validated **990,000 tasks**. Warmup mass batches are
  excluded from that task count. There were no unexpected failures or residual
  asynchronous sources/timers.
- All source hashes, the measured JAR hash, 26 case groups and three repetitions
  per group were checked. JSON stores each JVM's aggregate results and percentiles;
  individual per-task timestamp arrays are not persisted.
- Cgroup counters increased by **585 throttled periods and 5.706 seconds of
  throttled time** during the complete series. These are aggregate container
  counters; they do not identify the affected runs. Shared-host scheduling and
  the four-core quota limit small-difference and tail-latency conclusions.

The clearest project niche in these measurements is many mostly waiting tasks
with compact state and readable sequential code. The next implementation work
should expose a bounded pool of scheduler owners, or an explicit way to await
CPU work submitted to a separate executor. Preserve per-fiber ownership and make
admission limits visible. This report measures a four-manager control; it does
not add such a runtime API or prove that every application needs it.

Follow-up: the bounded pool and explicit compute API are now implemented. Their
[separate comparison](POOL_RESULTS.md) measures the public API, its offload
tradeoff and existing-manager performance; the data in this report is unchanged.

For application decisions, virtual threads remain a strong baseline, especially
with blocking libraries. Fibers have a measured memory advantage in these async
waits; their scheduling width and behavior under compute load should be chosen
explicitly. HTTP/TLS, JDBC, real service failures and multi-host deployments are
outside these four controlled scenarios.
