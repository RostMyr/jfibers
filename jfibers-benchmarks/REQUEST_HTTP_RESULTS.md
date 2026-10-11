# One root per HTTP request: 10 October 2026

At 4,000 offered mixed requests/s, median timely throughput was **3,748/s for
virtual threads, 3,462/s for offload, 3,137/s for four fiber owners and 1,280/s
for one owner**. Four owners delivered 2.45 times the one-owner goodput in this
case. Virtual threads had the highest median, but their range overlaps offload's
and offload led one of the three repetitions; this is not a universal ranking.

The median light-success p99 was **23.5 ms** for offload versus **38.6 ms** for
virtual threads, while only **54.8% of offered heavy requests** completed within the
client SLO, versus **93.8%** for virtual threads. The useful next target is
overload admission and work distribution, rather than an assumption that
offloading makes every request faster.

This experiment submits a fresh task for every accepted HTTP request. It measures
the public pool's admission and task lifecycle alongside real loopback transport,
complementing the earlier [long-lived-flow comparison](POOL_RESULTS.md).
The detailed [HTTP methodology](REQUEST_HTTP.md) defines the wire protocol,
deadlines, accounting and metric boundaries.

## Code and measurement protocol

Measured implementation and harness:
`7e07759bdc77bb0826f274cce65fce004faad227`. The source tree was clean when the
series started. Application fiber methods use build-time instrumentation; no
runtime agent or custom JFR recording is enabled.

Temurin 27+35, Maven 3.10.0, Linux x86-64, AMD EPYC 9V74 VM, five host logical
CPUs, a **shared four-core cgroup quota** and a 16 GiB memory limit. Client and
server run in separate JVMs on this same machine. Each JVM uses G1, a fixed
512 MiB heap, four active processors and four virtual-thread carriers. Those
settings do not allocate four dedicated cores to each process.

The protocol comprises **48 client/server JVM pairs**: four backends, four
workload cases and three repetitions. Each pair runs lifecycle checks, a
15-second warmup and a 10-second measured arrival window, then drains every
dispatched request. Pairs run sequentially; case and backend order rotate
between repetitions. The raw [results directory](results/2026-10-10-request-http)
preserves environment metadata, source/artifact hashes, commands, lifecycle
checks, warmup, measurements and server counter snapshots. Metadata timestamps
are UTC; the report date is the corresponding date in New York.

Every request performs three rounds of a 1 ms asynchronous wait and deterministic
integer computation. Light steps have 1,000 iterations. Ten percent of IDs are
designated heavy, using either 1,000 iterations in the equal-work control or
1,000,000 in the mixed cases. Checksums validate successful execution. Intended
arrival rates are 1,000 requests/s for the equal-work control and 1,000, 2,000 and
4,000 requests/s for mixed work. These are offered rates, not claims about each
backend's maximum sustainable capacity.

`FIBER_POOL_1` and `FIBER_POOL_4` have one or four cooperative owners, respectively.
`FIBER_POOL_OFFLOAD` has one owner and three compute threads; only the designated
heavy requests offload their steps. This includes those designated requests in
the equal-work control. Virtual threads execute one new virtual thread per
accepted request. All variants share the same synthetic sources, four timer
threads, checksum work, HTTP adapter and response format.

Admission capacity is **64 roots**, including waiting and cancellation cleanup.
Virtual threads use a nonblocking semaphore with the same capacity and release
permits only after body cleanup. The offload executor has a 64-entry waiting
queue. Four HTTP handler threads and four response writer threads use separate
1,024-entry queues; socket writes never run on an owner. The common client
allows at most 512 in-flight requests, counts every intended arrival and records
local rejections instead of silently changing to a closed-loop workload.

The **100 ms server deadline** starts at root submission, including its queue,
execution and cleanup. The separate **150 ms client SLO** starts at intended
arrival and includes generator lag, transport and response delivery. Goodput
counts only HTTP 200 responses received within that client SLO. HTTP 503 means
admission rejection; HTTP 504 means server deadline expiry. HTTP 200 responses
outside the client SLO remain successful responses but are classified as late.
Both throughput and goodput include final drain time in their denominators.

## Results

Values are medians **[minimum, maximum]** across three JVM pairs. Percentiles
are computed within each pair, then summarized; samples are not pooled.
Columns are summarized independently, so their medians need not sum exactly.

### Equal-work control: 1,000 requests/s

All 120,000 measured requests succeeded within the client SLO. Every backend
served approximately 999.7 requests/s including final drain. The one-owner
pool had the lowest median successful-response p99 in this control.

| Backend | Successful-response p99, ms |
| --- | ---: |
| Pool, one owner | 4.97 [4.95, 5.19] |
| Pool, four owners | 5.95 [5.40, 7.26] |
| Pool + offload | 5.48 [4.48, 6.79] |
| Virtual threads | 7.34 [6.97, 7.45] |

### Mixed work: 90% light, 10% heavy

Each measured pair offers 10,000, 20,000 or 40,000 requests according to rate.
Goodput counts timely HTTP 200 responses per second, including drain.

| Offered /s | Backend | Goodput /s | HTTP 503, % offered | HTTP 504, count |
| ---: | --- | ---: | ---: | ---: |
| 1,000 | Pool, one owner | 999 [998, 999] | 0.00 [0.00, 0.00] | 0 [0, 0] |
| 1,000 | Pool, four owners | 999 [999, 999] | 0.00 [0.00, 0.00] | 0 [0, 0] |
| 1,000 | Pool + offload | 999 [999, 999] | 0.00 [0.00, 0.00] | 0 [0, 0] |
| 1,000 | Virtual threads | 998 [998, 999] | 0.00 [0.00, 0.00] | 0 [0, 0] |
| 2,000 | Pool, one owner | 1,281 [1,276, 1,308] | 35.45 [34.45, 35.99] | 0 [0, 53] |
| 2,000 | Pool, four owners | 1,997 [1,994, 1,997] | 0.00 [0.00, 0.14] | 0 [0, 0] |
| 2,000 | Pool + offload | 1,998 [1,998, 1,998] | 0.00 [0.00, 0.00] | 0 [0, 0] |
| 2,000 | Virtual threads | 1,998 [1,998, 1,998] | 0.00 [0.00, 0.00] | 0 [0, 0] |
| 4,000 | Pool, one owner | 1,280 [1,264, 1,296] | 67.82 [67.40, 68.30] | 23 [0, 56] |
| 4,000 | Pool, four owners | 3,137 [3,095, 3,465] | 21.35 [13.12, 22.42] | 27 [0, 33] |
| 4,000 | Pool + offload | 3,462 [3,448, 3,608] | 8.58 [6.77, 9.81] | 1,406 [962, 1,725] |
| 4,000 | Virtual threads | 3,748 [3,571, 3,959] | 6.18 [0.90, 10.61] | 0 [0, 1] |

At 2,000 requests/s, offload and virtual threads completed every offered
request in every repetition. Four owners rejected 27 requests in one pair
and none in the other two. One owner remained capacity-limited.

| At 2,000/s | Light success p99, ms | Heavy success p99, ms |
| --- | ---: | ---: |
| Pool, one owner | 67.3 [65.9, 73.0] | 68.3 [66.3, 74.3] |
| Pool, four owners | 25.8 [24.3, 29.7] | 35.5 [29.3, 36.8] |
| Pool + offload | 10.3 [8.4, 11.0] | 21.1 [17.3, 22.4] |
| Virtual threads | 10.2 [9.2, 10.9] | 20.8 [17.9, 21.6] |

### Overload: outcomes and latency by class at 4,000/s

Every pair offers 36,000 light and 4,000 heavy requests. The fractions below
include rejected and timed-out requests in the denominator.

| Backend | Light timely success, % offered | Heavy timely success, % offered | Light success p99, ms | Heavy success p99, ms |
| --- | ---: | ---: | ---: | ---: |
| Pool, one owner | 32.1 [31.7, 32.5] | 31.9 [31.8, 32.2] | 71.4 [67.1, 77.3] | 73.6 [67.6, 78.1] |
| Pool, four owners | 78.6 [77.5, 86.9] | 78.6 [77.7, 86.5] | 70.4 [53.6, 72.1] | 76.9 [60.9, 77.0] |
| Pool + offload | 91.4 [90.2, 93.3] | 54.8 [48.7, 68.8] | 23.5 [23.4, 23.6] | 110.8 [108.0, 111.9] |
| Virtual threads | 93.8 [89.3, 99.1] | 93.8 [89.9, 99.0] | 38.6 [20.8, 40.8] | 48.6 [28.8, 50.3] |

All 962–1,725 offload deadline responses per pair belonged to heavy requests.
A median **39.1% [25.9%, 47.0%] of admitted heavy requests** expired after
acceptance. Heavy HTTP 503 rates were **8.15% [7.18%, 10.03%] of offered
heavy traffic**. Thus early admission rejection and expiration after acceptance
are materially different outcomes; completed CPU steps per expired request
were not counted.

Heavy-success p99 uses the following number of responses per pair: one owner
1,270–1,290; four owners 3,109–3,460; offload 1,949–2,751; virtual threads
3,595–3,961.
The latency advantage for light offload requests therefore does not imply
better availability for heavy requests.

Even for light traffic, offload's median timely-success fraction was below
virtual threads' (91.4% versus 93.8%). The light-success p99 ranges overlap and
virtual threads had the lower p99 in one repetition. Lower median latency among
successful light requests is therefore a narrower claim than better service
for every light request.

### Load-generator and CPU observations at 4,000/s

| Backend | Server CPU, cores | Client CPU, cores | Generator lag p99, ms |
| --- | ---: | ---: | ---: |
| Pool, one owner | 1.26 [1.25, 1.27] | 0.79 [0.69, 0.85] | 0.75 [0.12, 3.53] |
| Pool, four owners | 2.83 [2.82, 2.96] | 0.60 [0.50, 0.61] | 2.53 [0.44, 2.88] |
| Pool + offload | 3.21 [3.19, 3.21] | 0.76 [0.72, 0.79] | 12.16 [10.62, 13.21] |
| Virtual threads | 3.35 [3.32, 3.42] | 0.55 [0.48, 0.64] | 11.14 [3.65, 13.75] |

Server snapshot windows were 0.25–0.84% wider than client measurement/drain
windows across the complete series. These CPU averages use their respective
windows; they are not isolated business-task CPU measurements.
Nonzero generator lag means the intended fixed-rate schedule was sometimes
delivered in catch-up bursts. No measured arrivals were locally rejected.

## What these measurements can establish

Compare total goodput with **timely success as a fraction of offered traffic for
each class**. Each successful request contributes one unit to total goodput,
despite the large difference in computational cost. A policy can therefore
increase total goodput by protecting light work while completing fewer heavy
requests. That is a service-quality tradeoff, not evidence that it executes both
classes faster. Also distinguish a rejected heavy request from an admitted one
that consumes resources and then expires.

Success-only latency excludes rejected and timed-out requests. In particular,
heavy p99 describes only surviving heavy successes; when few survive, it is a
small and strongly selected population. All-outcome latency is not a substitute
for outcome rates either: promptly rejected requests can make its percentiles
look better. Read both distributions alongside light/heavy outcomes and their
sample counts. An HTTP 200 can exceed 100 ms end-to-end without violating the
server deadline because the two budgets have different starting and ending
points.

The results describe this complete HTTP application harness. Work behind the
endpoint uses synthetic futures, not a real database or external service. The
common HTTP adapter, connection management, response writers and client can
limit throughput before the scheduler does. At a load all backends sustain,
similar observed throughput mainly confirms that they served the offered rate;
it does not establish equal maximum capacity.

## Limitations

- **Shared CPU and generator lag.** Both JVMs, their timers and HTTP threads
  compete within one four-core quota. Arrival lag and client CPU must accompany
  backend comparisons. A delayed generator catches up against the original
  schedule, producing bursts that can change admission pressure. Cgroup
  throttling describes the container's contention, not isolated scheduler CPU.
- **Three repetitions.** Tables report medians and minimum/maximum values across
  three fresh JVM pairs. These are descriptive ranges, not confidence intervals.
  A median of per-run p99 values is not a percentile pooled across all requests.
- **Owner assignment and deterministic traffic.** Heavy IDs follow
  `floorMod(id * 37, 100) < 10`. With strictly ordered admission, their positions
  modulo four have a 3/3/2/2 distribution, which can align with the four-owner
  pool's round-robin assignment. HTTP handler ordering and rejected requests
  perturb that alignment, but actual per-owner work was not recorded. Do not
  assume equal owner utilization or attribute a small Pool4/virtual-thread gap
  solely to runtime efficiency. The comparison includes the shipped assignment
  policy and this particular traffic pattern.
- **Cooperative deadlines.** Integer computation is deliberately non-preemptible
  in every backend. Deadline expiry requests termination and is checked at
  execution boundaries; it does not guarantee immediate response delivery.
  Offloaded cleanup waits for actual computation exit, even if its result future
  has already been cancelled. Virtual-thread cancellation wakes the shared
  awaited future; this is not a comparison of every native interruption API.
- **Timing boundaries.** Server queue delay is submission-to-body-start and
  excludes HTTP-handler delay. Roots terminated before their body starts have
  no queue/service sample. Service time already includes cleanup. Compute queue
  time sums the observed enqueue-to-start delays of the request's steps; it is
  not a percentile for individual executor enqueues, and cancelled unstarted
  steps have no observed start delay.
- **Counter windows and allocation.** Client counters exclude its sample-array
  allocation and percentile sorting. Server CPU/allocation counters span the
  supervisor's wider snapshot window, including client setup/summary time, and
  server CPU cores use that separate window. Server allocation includes HTTP,
  timers, bookkeeping and rejected traffic; allocation per accepted root is not
  isolated fiber allocation. Peak counters also include the capacity self-check
  before warmup, so they do not describe measured-traffic peaks.
- **No retained-memory or profile attribution.** Heap snapshots here have no
  controlled waiting population or server post-GC protocol. No JFR samples were
  collected. These measurements cannot establish retained handle size, identify
  CPU hot spots or reproduce earlier mass-waiting memory conclusions.

## Validation

Before the series, `./mvnw -Pbenchmarks,code-analysis verify` passed
**105 local tests**, and the HTTP smoke passed for all four backends. Coverage
includes request deadlines, cancellation through asynchronous cleanup, bounded
admission, actual offloaded-compute exit, completion publication and HTTP handle
registration racing with cancellation. These are local validations; a successful
GitHub Actions run on main has not been independently confirmed.

Each measured JVM pair also runs unmeasured HTTP lifecycle checks: explicit
cancellation, deadline expiry and saturation followed by cancellation of every
accepted root. The supervisor reconciles client/server outcomes and bytes after
each phase, and requires roots, completion publication, sources, timers, compute
work, exchanges and writers to drain. Transport errors, unexpected failures,
checksum mismatches and inconsistent counts invalidate a run.

All **48 pairs** completed and validated. The measured windows offered and
sent **960,000 requests**: 813,202 HTTP 200, 142,512 HTTP 503 and 4,286 HTTP 504.
Of the HTTP 200 responses, **813,201 met the client SLO and one was late**.
There were zero measured local rejections, transport errors, unexpected
cancellations or HTTP 500 responses. Every final resource counter drained.

Warmup separately offered 1,440,000 arrivals, including 5,739 client-local
rejections and 4,347 late successful responses. These startup transients are
preserved in raw data and excluded from the result tables. All 3,216 unmeasured
work requests used for lifecycle checks also reconciled with server counters.
Recorded Java/Python source hashes and the measured JAR hash match the inspected
artifacts; the source commit is recorded above.

Across the entire series, including startup and warmup, cgroup counters increased
by 1,906 throttled periods out of 12,801 periods and 100.73 aggregate throttled
seconds. This counter is not a measurement of lost application wall time or of
throttling confined to the timed phases. The experiment ran from 00:46:26 to
01:07:49 UTC on 11 October 2026. No concurrent builds or other load tests ran in
the container during measurements.

Static analysis retained the five existing medium production findings and
JMH-generated-field findings; no new finding remained in the handwritten
benchmark code. It is a reporting profile, not a clean-analysis gate.

## Next experiment: admission before expensive work

The next architectural experiment should place an explicit budget on admitted
**heavy requests as a whole**, alongside the existing global root limit. Hold
that budget across all three compute steps and cancellation cleanup, and reject
excess heavy requests before they begin consuming work. Start with limits of
3, 6 and 12 heavy requests while preserving the same overall CPU budget, root
capacity, offered traffic and deadlines. Report separate reasons for rejection
by the heavy-work budget and by the global root limit.

This differs from merely shrinking the executor queue. Queue rejection during a
later compute step can discard an already partially executed request; a whole
request budget acts before that work begins. The decision should be explicit
application overload handling, not an unexpected HTTP 500. Measure timely
success/offered for both classes, heavy admission-to-timeout rate, compute queue
delay and work spent on requests that eventually expire. A larger queue is not
automatically more useful capacity when requests have finite deadlines.

Apply the same heavy-request admission policy to a virtual-thread control to
separate the policy's benefit from the execution mechanism. Start with a service
example and benchmark before turning an application-specific policy into a
general core-library API. If the application requires a different balance
between light and heavy success, make that service objective explicit.

Also record per-owner light/heavy admissions and execution load, and repeat a
control with heavy positions rotated or randomized between ID blocks. That
would distinguish assignment imbalance from other scheduling costs without
assuming work stealing or fiber migration is the required solution. Isolate the
load generator on a separate machine before making server-capacity claims.
These experiments need explicit admission and observability; they do not by
themselves require runtime bytecode instrumentation.
