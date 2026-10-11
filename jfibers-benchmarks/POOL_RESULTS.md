# Bounded scheduler pool and compute offload: 10 October 2026

The public `FiberPool` API preserves the throughput of the earlier four-manager
control in this mixed workload. Four owners reached **42.81k successful jobs/s**,
against **43.10k for virtual threads** and **11.89k for one pool owner**. The
four-owner pool is 3.60 times the single-owner pool's median; its difference from
virtual threads is smaller than the variation between runs. This supports using
several owners for this workload, not a claim that fibers generally beat virtual
threads.

Explicit compute offload has a different tradeoff: **36.88k jobs/s**, with lower
light-task p99 (**6.86 ms**) but much higher heavy-task p99 (**50.37 ms**). It is
available as an opt-in API, not an automatic scheduler optimization.

## Code and measurement protocol

Implementation and harness: `43169c0921f5b09e86dd754755b1fb65f00df74f`.
See [execution contracts](../EXECUTION.md) and the [scenario methodology](SCENARIOS.md).
Application fiber methods use build-time instrumentation; no runtime agent is used.

Temurin 27+35, Maven 3.10.0, Linux x86-64, AMD EPYC 9V74 VM, five host logical
CPUs and a four-core cgroup quota, 16 GiB memory limit. Timed scenarios use G1,
a fixed 1 GiB heap, four active processors and four virtual-thread carriers.
Library JFR hooks are disabled. Runs execute sequentially and backend order
rotates between repetitions.

The mixed series contains **42 fresh JVM measurements**: seven backends, 0% and
10% heavy jobs, three repetitions each. Every JVM has a five-second warmup and
five-second measured phase plus final draining. All variants process the same
256 concurrent flows, three 1 ms asynchronous waits per job and identical integer
work/checksums. Light steps have 1,000 iterations; heavy steps have 100,000.
The scheduled source service has four threads for every backend.

`JFIBERS` and `JFIBERS_4` directly schedule flows on one or four managers, as in
the previous report. `FIBER_POOL_1` and `FIBER_POOL_4` use the public pool with
capacity 256. `FIBER_POOL_OFFLOAD` has one owner, three fixed compute threads and
a 256-entry compute queue; only heavy steps use `FiberFutures.submit`. Light work
stays on the owner. CompletableFuture uses four explicit continuation threads.

Each pool submission is a **long-lived flow**, not a single measured job. This
experiment measures steady application work and includes startup/draining; it
does not establish the cost of submitting a new root for each request. The 0%
offload case executes no computation in its compute executor and controls for
the alternative source method/branch. All CPU figures include the synthetic
service, JVM and harness, not just the library's owner threads.

Raw data: [mixed](results/2026-10-10-pool-mixed),
[scheduler comparison](results/2026-10-10-pool-scheduler). Every directory has
environment metadata, exact commands, source/artifact hashes, raw results and a
summary. Times in metadata are UTC: scheduler 00:10:16–00:12:52 and mixed
00:13:00–00:20:20 on 11 October; these are the evening of 10 October in New York.
The mixed series started with only the earlier scheduler result directory
untracked; the tracked source tree matched the implementation commit.

Tables show medians and **[minimum, maximum]** across three JVMs. A median of
per-JVM p99 values is not a pooled percentile. These ranges are descriptive,
not confidence intervals or proof of small performance differences.

## Mixed workload: 10% heavy jobs

| Backend | Successful jobs/s | Light p99, ms | Heavy p99, ms |
| --- | ---: | ---: | ---: |
| Direct manager, one owner | 11,715 [11,694, 11,896] | 23.48 [23.09, 28.67] | 23.99 [23.56, 28.90] |
| Direct managers, four owners | 43,239 [43,073, 44,275] | 10.92 [10.03, 11.50] | 11.69 [10.53, 12.18] |
| Pool, one owner | 11,889 [11,851, 12,090] | 23.10 [22.51, 24.47] | 24.82 [23.44, 25.07] |
| Pool, four owners | 42,807 [41,915, 44,516] | 12.03 [9.53, 14.08] | 12.46 [10.15, 15.29] |
| Pool, one owner + three compute threads | 36,880 [34,444, 38,631] | 6.86 [6.57, 7.65] | 50.37 [49.50, 55.63] |
| Virtual threads | 43,104 [42,100, 43,163] | 11.71 [11.29, 11.95] | 12.73 [12.03, 12.74] |
| CompletableFuture | 41,543 [41,508, 42,537] | 9.87 [9.23, 10.37] | 10.94 [10.07, 12.10] |

The pool consumed 1.09 process CPU cores with one owner and 3.91 with four;
virtual threads consumed 4.00. This agrees with the earlier diagnosis: heavy
sections on one owner serialize runnable fibers. Distribution across owners
addresses that limit without changing their cooperative execution semantics.
The four-owner pool and direct four-manager control differ by about 1% in median
throughput and have overlapping ranges. Pool tail latency varies more in this
series; three repetitions do not establish tail-latency equivalence.

Offload trades about 14% of the four-owner pool's median throughput for a 43%
lower light-task p99, while heavy-task p99 is about four times higher. Heavy
work crosses an additional bounded queue and notification boundary. This test
does not separately time the compute queue, so it cannot attribute every part
of that tail increase to queueing. The one-owner/three-compute configuration is
one explicit CPU budget, not a tuned optimum for every workload.

Median approximate allocation per job was 990 B for one pool owner, 984 B for
four owners, 1,049 B for offload, 907 B for virtual threads and 1,668 B for
CompletableFuture. These are JVM-wide allocation deltas including common source
and harness objects; they are neither retained memory nor allocation per new
pool submission. The earlier 100,000-waiting-task memory result used direct
managers and must not be presented as a measurement of the new pool handles.

## Control: no heavy jobs

| Backend | Successful jobs/s | Light p99, ms |
| --- | ---: | ---: |
| Direct manager, one owner | 68,124 [63,639, 73,903] | 6.93 [5.41, 9.46] |
| Direct managers, four owners | 75,375 [73,470, 75,979] | 5.21 [4.68, 5.53] |
| Pool, one owner | 68,014 [67,075, 72,115] | 6.04 [4.77, 6.77] |
| Pool, four owners | 74,837 [72,474, 75,701] | 4.92 [4.77, 6.01] |
| Pool offload method, no heavy steps | 69,766 [67,408, 71,831] | 5.88 [5.41, 6.45] |
| Virtual threads | 76,421 [75,765, 76,459] | 3.73 [3.62, 4.34] |
| CompletableFuture | 73,953 [72,654, 74,469] | 4.78 [3.76, 6.26] |

There is no comparable multi-core speedup when little computation follows each
wait. Virtual threads had the highest median throughput and lowest p99 here.
The single-owner results vary appreciably, so small differences between the
direct and pool APIs should not be interpreted as intrinsic scheduler speedups.

## Existing manager: before/after regression check

The only integration into the existing manager is an optional completion listener
after terminal bookkeeping and owned-child cleanup. A normal manager has no
listener. Eighteen JMH measurement forks compare the core JAR from `e9a0a4f`
with the new core from `43169c0`, using one unchanged benchmark artifact.
Each fork has three one-second warmup iterations and five one-second measurement
iterations, G1, a fixed 512 MiB heap and `-Xverify:all`. The GC profiler is enabled.

| Scenario | Before, µs/fiber | After, µs/fiber | Approx. B/fiber, before / after |
| --- | ---: | ---: | ---: |
| Already completed input | 0.0553 [0.0546, 0.0576] | 0.0553 [0.0547, 0.0571] | 136.313 / 136.313 |
| Owner-thread notification | 0.1039 [0.1010, 0.1217] | 0.1015 [0.1012, 0.1073] | 232.782 / 232.782 |
| External-thread notification | 0.5182 [0.2565, 0.6624] | 0.5052 [0.4721, 0.6105] | 232.972 / 232.972 |

JMH normalizes a batch of 256 fibers; these numbers are **per fiber**, not batch
times or request-latency percentiles. No systematic regression is apparent in
this sample, and normalized allocation is essentially unchanged. External
notification timing is noisy; its small median change is not evidence of an
improvement. This check concerns existing direct-manager use, not pool admission.

## Validation and next experiment

- `./mvnw -Pbenchmarks,code-analysis clean verify`: **95 passing tests**. Thirteen
  added tests cover concurrent submission, owner affinity, cancellation races,
  bounded capacity, observer isolation, shutdown, executor rejection/interruption
  and cleanup in actually transformed `finally` blocks. Production SpotBugs
  retains the same five existing medium findings; no new production findings.
- Correctness smoke runs passed for pool mixed work, fan-out, open-loop overload,
  the existing HTTP comparison, and the example application. The mixed pool
  smoke is also included in the CI workflow. These validations were run locally;
  GitHub Actions success on main has not been independently confirmed.
- All **11,085,187 measured jobs** succeeded; warmup plus measurement validated
  **21,880,643 jobs**. There were no unexpected failures, rejected jobs, timeouts,
  cancellations or residual sources/timers in this mixed series. Cancellation
  and overload behavior are exercised separately by tests and smoke scenarios.
- All 14 mixed groups have three repetitions. Source hashes and the measured
  artifact hash were verified; all 20 core classes in the measured fat JAR match
  the after-core snapshot. The scheduler series recorded no cgroup throttling.
  The mixed series recorded 877 throttled periods and 7.890 seconds of aggregate
  throttled time. Those container totals cannot identify individual affected runs.

For this workload, start with four cooperative owners within the available CPU
budget. Use explicit compute offload when protecting light-task latency justifies
its queueing and heavy-task latency costs. Neither mechanism preempts blocking
fiber code or eliminates the need to size admission limits.

The next experiment should exercise **one new pool submission per HTTP request**,
with bounded admission, deadline/cancellation cleanup and a sweep of CPU-work
sizes. Measure submission throughput, retained handle memory, owner/compute queue
delay and light/heavy p99 against virtual threads. That tests the public API's
admission cost and helps find when offloading becomes worthwhile; repeating only
the long-lived-flow test would not answer those questions.
