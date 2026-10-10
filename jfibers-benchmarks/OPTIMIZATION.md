# Scheduler profiling and optimization, 10 October 2026

The selected runtime (`d5840d6`) adds opt-in JFR diagnostics, skips clock reads
when no deadline or poll is needed, and skips waking the owner from its own
thread. Disabled profiling takes the direct execution path, without the profiling
wrapper. Fibers use build-time bytecode transformation; no agent is required.

External completions keep direct `LockSupport.unpark` delivery. LockSupport
already coalesces permits, including completions racing with park. The owner
checks mailboxes before parking to observe work queued by its own callbacks.
There is no new wakeup flag or spin loop. An earlier prototype added its own
atomic wakeup gate; the measurements below explain why it was rejected.

The complete build with benchmarks, Checkstyle and SpotBugs passed **82 tests**,
including new JFR-on/JFR-off subprocess checks and completion/cancellation bursts
from four producers across 40 scheduler runs. The five existing production
SpotBugs findings remain; no new production finding was introduced.

## Runtime provenance

Within each comparison, all runtimes use one common benchmark artifact with a selected core
JAR first on the classpath. The benchmark commands, source/artifact hashes and
environment are recorded in each dataset. Core artifacts were saved before the
next build, outside `target`.

| Runtime | Revision | Contents | Core JAR SHA-256 |
| --- | --- | --- | --- |
| original | `482d058` | Previously published runtime | `c39f917639b5af6e242e857d1a5ae44effd9d0e0c0d6f8354205e61e1eb13ca0` |
| baseline | `c51ffdf` | JFR hooks, original scheduling algorithm | `344f987ed2147d483054ab9fffa7a9443f11d68094b4979ff59a5a6f30c06299` |
| optimized (rejected prototype) | `80a28c9` | JFR hooks, clock guards and coalesced wakeups | `4ecce7109cdcf330b9106b347767bfe27444c198c617614d9092e7bbbb525166` |
| final | `d5840d6` | Clock guards, skip self-wakeups, direct external wakeups and unprofiled execution | `b0dc268aa17d10c8f26519e72faaa7e43afd1b10ee7c12ab9bc2d0ea61d6ba7f` |

Measurements ran sequentially on Temurin 27+35, AMD EPYC 9V74, Linux, with a shared
four-core CPU quota and the harness's fixed JVM settings. No builds or tests ran
concurrently. The clock-only and wakeup changes were measured together, so these
results do not assign an isolated percentage to each change.

## Selected runtime: measured results

[Final JMH data](results/2026-10-10-scheduler-final) compare `original` and `final`
in three fresh JVMs per case, with diagnostics off. Values are medians [min, max]
of JVM means. One batch contains 256 fibers; the ranges are not confidence intervals.

| Scenario | Original, µs/batch | Final, µs/batch | Final allocation, B/fiber |
| --- | ---: | ---: | ---: |
| Already completed futures | 24.82 [24.61–25.06] | 14.24 [14.24–14.83] | 136.31 |
| Futures completed on owner | 44.42 [42.93–44.77] | 25.85 [25.84–26.49] | 232.78 |
| Futures completed externally | 186.48 [96.81–215.51] | 108.37 [102.59–128.70] | 232.97 |

The first two cases consistently take **42.6% and 41.8% less time**, respectively.
Allocation per fiber is unchanged at this precision. Manager construction is
outside the measured invocation. External handoff varies widely between forks
and between this run and the earlier prototype dataset. Its favorable median
does not establish a portable speedup: one of the three final paired repetitions
was slower, and the observed ranges overlap.

The [final unprofiled HTTP comparison](results/2026-10-10-http-final) used 256
persistent flows, the same benchmark JAR for both cores, 15 seconds of warmup and
10 seconds of measurement per fresh JVM. Median throughput [min, max]:

| Delay/request | Original, ops/s | Final, ops/s | Original / final p99, ms |
| --- | ---: | ---: | ---: |
| 0 ms | 23,105 [21,766–24,288] | 25,138 [21,683–25,449] | 22.02 / 23.55 |
| 5 ms | 18,383 [18,164–19,839] | 17,868 [17,686–19,133] | 27.90 / 29.70 |

The zero-delay median is 8.8% higher; the 5-ms median is **2.8% lower**. Paired
results change direction and ranges overlap. This does **not** establish a stable
HTTP throughput or tail-latency improvement. The p99 values are medians of each
run's histogram upper bound, not pooled percentiles. A fixed-arrival-rate test
on controlled hardware is needed before making an application SLO claim.

Across this investigation's 42 unprofiled HTTP runs, the harness validated
**8,812,856 operations / 17,625,712 requests**, with zero client/server errors,
timeouts or byte-count mismatches. The original 30-run experiment and the final
12-run comparison are separate datasets, not pooled into one performance score.

## Diagnostic overhead and final profile

[The overhead comparison](results/2026-10-10-profiling-overhead) uses the final
runtime's owner-completion microbenchmark, rotating three modes across three
fresh JVMs. JFR recordings use the same `profile` configuration:

| Mode | Median [min, max], µs/256 fibers | B/fiber |
| --- | ---: | ---: |
| No recording, fiber diagnostics off | 25.83 [25.70–26.02] | 232.78 |
| Standard JFR, fiber diagnostics off | 26.35 [26.28–27.81] | 232.78 |
| Standard JFR plus fiber diagnostics | 58.58 [58.24–59.60] | 236.53 |

Standard JFR alone adds about 2% to the median here. Enabling the extra clocks,
counters and sampled wait tracking raises batch time to **2.27×** the no-recording
case, or 2.22× standard JFR. This is a very short-task workload with one scheduler
run/final event per batch; it is not an estimate of HTTP profiling overhead.
The `on`-without-recording mode is supported by the runner but was not measured
in this comparison. Diagnostics remain explicitly opt-in and disabled by default.

The [final HTTP profile](results/2026-10-10-scheduler-profile-final) records 359,768
completion notifications, 1,406 sampled waits, zero stale signals and zero
remaining fibers. Sampled completion-to-execution p50/p95/p99 is 31/336/999 µs.
The owner spent 8.53 of 10.01 seconds in park calls and 1.36 seconds in execution
steps; these are elapsed times, not CPU utilization. External notifications use
direct `unpark`, so the final runtime deliberately retains one call per external
notification in this recording. The prototype's 82% call reduction below is
**not a property of the selected runtime**.

## Rejected prototype: scheduler microbenchmarks

The `optimized` label in these historical datasets means the **coalesced
prototype**, not the selected runtime. Its fast microbenchmarks and much lower
wake-call count did not predict its HTTP performance.

[Raw JMH data and summary](results/2026-10-10-scheduler) contain three fresh JVMs
per scenario/runtime, each with three 1-second warmup and five 1-second measured
iterations. JFR diagnostics were disabled. Values below are median **batch times
for 256 fibers**, with min/max of independent JVM means; they are not confidence
intervals. JMH's raw score is per fiber and must be multiplied by 256.

| Scenario | Original, µs/batch | Hooks disabled, µs/batch | Optimized, µs/batch |
| --- | ---: | ---: | ---: |
| Already completed futures | 25.08 [24.83–25.88] | 24.92 [24.78–25.45] | 14.21 [14.06–14.24] |
| Futures completed on owner thread | 43.53 [43.48–44.15] | 44.17 [44.00–45.16] | 25.77 [25.48–25.86] |
| Futures completed by an external producer | 76.18 [71.87–137.39] | 99.34 [78.65–105.49] | 85.91 [60.23–133.52] |

The ready and owner-completion cases take about **43% and 41% less time** than
the original runtime. Disabled profiling has similar timings in these stable
cases. The external-producer case has a wide, overlapping range and its optimized
median is worse than the original in this first comparison; it does **not**
establish an improvement or negligible hook overhead for cross-thread workloads.
There is no added allocation per fiber in these measurements; profiler state and
the wakeup flag belong to the manager, and manager construction is outside JMH's
measured invocation. These are tiny-task results, not an HTTP throughput claim.

## Rejected prototype: JFR and HTTP findings

Separate ten-second recordings at 256 HTTP flows and 5 ms server delay/request
compare the [instrumented baseline](results/2026-10-10-scheduler-profile-before)
and [optimized runtime](results/2026-10-10-scheduler-profile-after). Profiles are
excluded from ordinary performance measurements.

| Final scheduler counter | Before | After |
| --- | ---: | ---: |
| Completion notifications | 354,186 | 367,188 |
| `unpark` calls | 354,186 | 66,583 |
| `unpark` calls per notification | 1.000 | 0.181 |
| Calls to park | 93,245 | 66,599 |
| Pending fibers at end | 0 | 0 |
| Recorded wait samples | 1,384 | 1,435 |

This demonstrates **82% fewer `unpark` calls per notification** in the optimized
recording. It does not mean 82% less CPU or fewer kernel context switches:
`unpark` and `park` may return without an OS transition. The exact ratio depends
on completion timing and batching.

Sampled completion-to-next-execution latency was p50/p95/p99
29/342/844 µs before and 27/293/777 µs after. These are just two diagnostic
recordings, with every 256th wait sampled and a 1 ms total-wait threshold; they
are not a latency improvement confidence interval. The maximum sampled delay
was worse after (5.85 versus 3.64 ms), illustrating why individual percentiles
must not be treated as a general tail-latency guarantee.

The [profiling guide](../PROFILING.md) explains counter aggregation, sampling,
cancellation and the distinction between observed waiting and scheduling delay.
Execution-step time is wall time, not CPU time. The profiles show an owner that
spends much of the interval parked; they do not establish owner CPU saturation
or justify a scheduler rewrite for parallel execution.

The [unprofiled HTTP comparison](results/2026-10-10-http-scheduler) then ran three
fresh JVMs per case with 15-second warmup and 10-second measurement phases:

| Server delay/request | Original, median ops/s | Coalesced prototype, median ops/s | Change |
| --- | ---: | ---: | ---: |
| 0 ms | 23,492 | 21,798 | −7.2% |
| 5 ms | 18,312 | 18,380 | +0.4% |

The zero-delay case regressed in all three paired repetitions, including the
reversed execution order. This is why the extra atomic wakeup gate was removed.
These are combined implementation comparisons; they do not isolate CAS,
inlining or permit behavior as the sole cause of the difference.

## Matched HTTP API control

That same dataset also compares conventional virtual-thread `send()` calls with
virtual threads awaiting **the same public `sendAsync().get()` API** as fibers.
These controls use 256 persistent flows and the same checked response bodies.
One operation is two sequential requests. Values are medians of three JVMs:

| Delay/request | Original fibers | Virtual threads + send | Virtual threads + sendAsync/get | CompletableFuture |
| --- | ---: | ---: | ---: | ---: |
| 0 ms | 23,492 | 24,925 | 22,677 | 25,202 |
| 5 ms | 18,312 | 18,953 | 18,087 | 18,716 |

The matched-API control is below ordinary `send()` by about 9% and 5% in these
medians. The fiber/virtual-thread difference becomes small with overlapping
observed ranges when both use `sendAsync`. The API choice contributes materially
to the comparison; it does not prove that either scheduling primitive is always
faster. The extra async/executor path is explained in [HTTP.md](HTTP.md).
