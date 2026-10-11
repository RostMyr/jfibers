# HTTP requests through the bounded task pool

This benchmark creates a fresh root task for every accepted HTTP request. It
exercises admission, scheduling, asynchronous waits, computation, deadlines,
cancellation and cleanup through the public `FiberPool`, `FiberTask` and
`FiberFutures` APIs. It complements the mixed-workload probe, whose long-lived
flows process many operations after a single pool submission.
The [recorded comparison](REQUEST_HTTP_RESULTS.md) includes three repetitions,
per-class outcomes and the limitations of the shared-machine measurements.
The optional [heavy admission experiment](HEAVY_ADMISSION.md) extends the current
protocol with a separate limit for heavy requests and completed-work counters.
Historical reports and raw data retain their recorded source/protocol versions.
The [downstream HTTP extension](DOWNSTREAM_HTTP.md) adds a separate dependency
JVM and optional disjoint CPU affinity. The defaults described here retain local
timer waits; explicit modes and CPU/compute options are recorded separately.
The [downstream comparison](DOWNSTREAM_HTTP_RESULTS.md) records 48 timer/HTTP
runs and 12 follow-ups with two compute workers on the same two application CPUs.

The client and server run in separate JVMs on the same machine. The transport is
real loopback HTTP/1.1; the work behind the endpoint uses synthetic asynchronous
sources, not a database or an external HTTP service. Results therefore describe
this complete application harness, not isolated scheduler costs or production
service capacity.

## Build and run

Use Python 3 and the project's JDK. Build-time instrumentation is part of the
benchmark Maven profile.

```sh
./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify

# Correctness smoke for all four backends; not performance evidence.
python3 jfibers-benchmarks/request_http_run.py --quick \
  --cases 1000:100000 \
  --output jfibers-benchmarks/target/request-http-smoke

# Full default matrix: three fresh JVM pairs per backend and case.
python3 jfibers-benchmarks/request_http_run.py \
  --output jfibers-benchmarks/target/request-http-full

# Select a matrix while retaining the same admission and latency budgets.
python3 jfibers-benchmarks/request_http_run.py \
  --backends FIBER_POOL_1 FIBER_POOL_4 FIBER_POOL_OFFLOAD VIRTUAL_THREADS \
  --cases 1000:1000 1000:1000000 4000:1000000 \
  --capacity 64 --max-in-flight 512 --deadline-ms 100 --slo-ms 150 \
  --warmup-ms 15000 --measure-ms 10000 --forks 3 \
  --output /path/to/new/results

python3 jfibers-benchmarks/request_http_summarize.py /path/to/new/results
```

Output directories must be new. `RATE:HEAVY_ITERATIONS` specifies intended
requests per second and integer-mixing iterations per heavy step. The default
cases are `1000:1000`, `1000:1000000`, `2000:1000000` and `4000:1000000`.
`--quick` selects one repetition, 300 ms of warmup and 400 ms of measurement;
it retains the selected cases and all lifecycle checks.
`--heavy-capacities 0` is the default and disables the separate heavy limit.
Supply a list such as `--heavy-capacities 0 3 6 12` to compare policies; positive
values must not exceed `--capacity`.

The normal protocol uses 15 seconds of warmup and 10 seconds of measurement,
then drains every dispatched request. Each case/backend/repetition gets a fresh
client/server JVM pair. Cases, heavy capacities and backends rotate between the
three repetitions; each case interleaves the selected capacities and backends,
and pairs execute sequentially. Both JVMs use G1, fixed 512 MiB heaps, four active
processors and four virtual-thread carriers. JFR and custom fiber events are
disabled. Client full GC precedes the measured phase and is outside its counters;
the server is not forced through a full GC between phases.

## Common workload and implementations

Each normal request performs three rounds of a 1 ms asynchronous wait followed
by deterministic integer mixing. Light requests use 1,000 iterations per round.
Ten percent of IDs are heavy, selected by `floorMod(id * 37, 100) < 10`, and use
the configured heavy iteration count. A fixed iteration count is not a promised
CPU duration. With `HEAVY_ITERATIONS=1000`, both classes perform equal work.

Every backend uses the same four-thread timer service, sources, computation,
deadline accounting, result format and HTTP adapter. Computation uses a fixed
seed per step; the client precomputes the two expected checksums outside timed
traffic instead of repeating heavy computation while validating responses.

| Backend | Execution of each accepted request |
| --- | --- |
| `FIBER_POOL_1` | A fresh transformed fiber submitted to a pool with one owner; computation runs on that owner. |
| `FIBER_POOL_4` | A fresh transformed fiber submitted to a pool with four owners; pool assignment is round-robin and fibers retain their owner. |
| `FIBER_POOL_OFFLOAD` | One fiber owner; heavy steps use `FiberFutures.submit` with three compute threads and a bounded compute queue. Light steps run on the owner. |
| `VIRTUAL_THREADS` | A fresh virtual thread per accepted request, with a semaphore enforcing the same root capacity; waits use `CompletableFuture.get()` and computation runs on the virtual thread. |

The default root capacity is 64. It includes queued, running and waiting roots,
and roots still performing cancellation cleanup. The offload executor has three
threads and a queue whose capacity equals the root capacity. Root admission is
nonblocking: overload returns HTTP 503. The virtual-thread semaphore does not
wait for a permit either.

When enabled, the harness's heavy admission gate limits the number of accepted
heavy roots throughout their entire lifetime: root queueing, every asynchronous
wait, all three compute steps and cancellation cleanup. It releases the heavy
slot only after terminal cleanup and any offloaded computation's actual exit.
Heavy admission is checked before global root admission; if both limits are
full, a heavy request reports the heavy reason. A global rejection rolls back
any provisional heavy reservation. Light requests consume only global capacity.
The same policy wraps fibers and virtual threads. It is implemented by this
benchmark's request engine, not a change to the public `FiberPool` policy.

The common JDK `HttpServer` adapter has four handler threads and four response
writer threads, each with a 1,024-entry queue. Socket response writes do not run
on a fiber owner or business-task thread. These transport queues are distinct
from root capacity: a request can spend time in HTTP processing before root
submission, and a completed root can await its response write after releasing
its slot. The client permits at most 512 in-flight requests by default; the
runner bounds this option to 512 to stay within the common adapter's limits.
Adapter failures invalidate a run rather than being counted as successful work.

## Open-loop arrivals and the two time budgets

One client generator follows a fixed monotonic schedule, independent of response
completion. Every intended arrival is counted. If the generator falls behind,
it catches up against the original schedule instead of resetting the clock or
discarding arrivals. `arrival_lag` measures intended arrival to the dispatch
attempt. Large lag means the client did not sustain evenly spaced arrivals;
catch-up bursts can then stress admission differently from smoothly paced traffic.

Admission to the client is also nonblocking. If its common in-flight semaphore
has no permit, that arrival becomes `local_rejected`; it is not sent over HTTP
and is not silently delayed. Admitted requests use the same JDK
`HttpClient.sendAsync` pipeline, HTTP/1.1, reused connections and a four-thread
client executor for every server backend.

Lifecycle checks use negative request IDs. After all checks drain, the first
warmup request ID is reset to `1_000_000`; measurement starts immediately after
the warmup's complete sequence of offered IDs. Each phase records
`first_request_id`, and the runner validates this sequence and its offered count.
Thus different heavy capacities and backends see the same intended light/heavy
arrival pattern even though their admission outcomes differ.

The default **server deadline is 100 ms**, starting at the server's attempt to
submit the root. It includes root queueing, waits, computation and cleanup, but
not earlier client/transport/HTTP-handler delay or the later response write.
Timers request termination, and monotonic checks at execution boundaries also
detect expiry when timer execution is delayed. Cancellation and deadlines are
cooperative: integer computation is deliberately non-preemptible in every
backend. An elapsed deadline does not guarantee an immediate HTTP response.

This is a request-level deadline implemented by the harness, including time
before the root body starts; it does not use `Fiber.withTimeout`. Fiber
termination requests use `FiberTask.cancel` and cancel the current source.
Virtual-thread requests wake by cancellation of the same awaited future, rather
than by interrupting the virtual thread. This compares the chosen application
termination contract, not every native cancellation mechanism of each runtime.

The separate **client SLO is 150 ms**, measured from intended arrival through
receipt and validation of the complete response. It therefore includes generator
lag, client dispatch, transport, all server processing and response delivery.
An HTTP 200 arriving after this budget remains a successful response but counts
as `late_success`. Only timely HTTP 200 responses count toward
`on_time_success` and goodput. The client does not abort requests when the SLO
expires. A separate 10-second HTTP safety timeout bounds transport failures;
those failures invalidate the run.

Only elapsed durations cross the wire; client and server `nanoTime` values are
never subtracted across processes. The requested arrival window is always kept
in the elapsed denominator, even at a rate low enough for the last response to
finish early. Throughput and goodput include any final drain beyond that window.

## Wire protocol and accounting

The server binds an ephemeral loopback port. Its standard-output startup record
contains `ready` and `port`; standard-input `stats` returns a snapshot and `stop`
drains/stops the process. The client accepts `check`, `warmup`, `measure` and
`stop` commands, allowing the supervisor to snapshot server counters between
phases.

The work route is:

```text
GET /work?id=N&mode=normal|hold&deadlineMs=N&cleanupMs=N
```

Each work response is exactly 56 bytes: seven big-endian signed 64-bit integers
in this order: `status`, `id`, `checksum`, `queueNanos`, `serviceNanos`,
`cleanupNanos`, `computeQueueNanos`. The HTTP status must equal the encoded
status, and the ID and successful checksum must match the request.
HTTP 503 additionally carries exactly one `X-Admission-Rejection` header whose
value is `heavy` or `global`; all other statuses must omit it. The binary body
remains 56 bytes. The client reports `rejection_reasons` for the whole phase and
`rejected_heavy`/`rejected_global` inside each class's outcome counters. A closed
engine or an unexpected execution failure is a failed run, not an extra
ordinary overload category.

| HTTP status | Meaning |
| --- | --- |
| 200 | Successful computation and cleanup within the server deadline. |
| 503 | Root admission rejected because capacity is unavailable. |
| 504 | Server deadline expired. |
| 409 | Explicit cancellation won before deadline expiry. |
| 500 | Unexpected execution or adapter failure; invalidates the run. |

`POST /cancel?id=N` returns 202 when a termination request is accepted, 404 when
there is no matching active entry, or 409 when cancellation is already requested
or completion has won the race. Its JSON control response is separate from the
original work response. A deadline can win a cancellation race, so acceptance of
a cancel request does not universally imply a final HTTP 409. `GET /stats`
provides the current counters. Control requests do not count as work traffic.

The runner validates warmup and measurement separately after complete draining:

- `offered = local_rejected + transport_errors + sum(HTTP outcomes)`.
- Dispatched client requests equal server work requests and completed responses.
- Each server HTTP status counter equals its client counterpart; response bytes
  equal dispatched requests times 56.
- Accepted roots equal completed roots and dispatched requests minus HTTP 503;
  root success, cancellation and timeout counters match the HTTP outcomes.
- Successful responses split exactly into timely and late success, per-second
  completions sum correctly, and light/heavy counts reconcile with the totals.
- Heavy/global rejection reasons sum to HTTP 503, match server counters, and
  reconcile within each request class; light work cannot be rejected by the
  heavy gate.
- Roots, heavy reservations, pending completion publication, sources, timers, compute work, compute
  and timer queues, active exchanges and response writers drain to zero.

Any transport error, unexpected HTTP 409 during normal traffic, HTTP 500,
malformed payload, checksum mismatch, counter discrepancy or residual resource
fails the run. Rejections and deadline responses are expected overload outcomes
and are preserved in the measurements.

## Lifecycle checks outside measured traffic

Every JVM pair runs these common checks before warmup, using only light IDs so
that the optional heavy gate cannot mask global-capacity behavior:

1. Start a `hold` request with a five-second deadline, wait until its source is
   pending, cancel it, and verify HTTP 409 after the requested 20 ms asynchronous
   cleanup.
2. Start a `hold` request with a 100 ms deadline and 20 ms asynchronous cleanup;
   verify HTTP 504 and cleanup before response publication.
3. Fill every root slot with pending `hold` requests. One extra request must
   receive HTTP 503. Cancel the admitted requests, verify all original responses
   and their cleanup, then require every tracked resource to drain.

With a positive heavy capacity, the client additionally fills that many slots
with heavy `hold` requests and verifies that an extra heavy request receives
HTTP 503 with reason `heavy`. A normal light request must still succeed when
global capacity remains. The held roots are cancelled, their cleanup is checked,
and a subsequent heavy request must succeed after draining. If heavy and global
capacities are equal, the light probe correctly receives reason `global` while
all slots are occupied, then must succeed after release. Check responses and
their expected successful compute work are counted dynamically and reconciled
separately from timed traffic.

`hold` sources do not complete by themselves. The checks use their own IDs and
counter intervals; deliberate cancellation and timeout do not enter normal
traffic statistics. A queued request cancelled before its body starts has no
body cleanup to perform. Started roots retain admission capacity through cleanup.
For offloaded computation, cleanup waits for actual worker exit even if the
completion future has already been cancelled.

Peak root/heavy/exchange counters are cumulative over the process lifetime. The
saturation check intentionally raises them before warmup, so they cannot be
interpreted as measured-traffic peaks. The root peak is used only to verify that
the configured capacity was never exceeded; the heavy peak is similarly checked
against a positive heavy capacity. With the gate disabled, heavy lifecycle
bookkeeping can briefly outlive release of a native root slot, so its peak is
not an independent bound on native scheduler concurrency.

## Metrics and interpretation limits

Client latency is intended-arrival to completion. `all_latency` includes local
rejection and every HTTP/transport outcome; success-only latency excludes those
outcomes and is censored by rejection and deadlines. Read its percentiles
together with offered counts, goodput, late success, HTTP 503/504 and local
rejections. Per-class outcomes and latencies separate light and heavy traffic.

Server timing fields have narrower boundaries:

- `queueNanos`: root submission attempt to body start. It includes admission and
  scheduler dispatch, not client or HTTP-handler delay.
- `serviceNanos`: body start to the end of body cleanup. It already includes
  `cleanupNanos`; do not add the two as disjoint durations.
- `cleanupNanos`: start of cleanup through body completion, including any wait
  for an offloaded computation to exit.
- `computeQueueNanos`: the sum of enqueue-to-start waits for compute steps that
  actually started. A queued computation cancelled before starting contributes
  no observed start delay.

Queue and service durations are `-1` for rejected/unstarted work and are omitted
from those timing distributions. Success distributions include HTTP 200 only;
the server `light`/`heavy` distributions include responses of that class with a
valid value for the particular timing field. Server timings exclude final task
publication and transport writes, which remain part of client latency.

The engine also records completed synthetic CPU work by terminal request outcome:
`cpu_steps_success/cancelled/timed_out/errors` and the matching
`cpu_iterations_*`, plus their totals. One step means a complete integer-mixing
loop. Counters are accumulated once when a request terminates, after cleanup has
waited for any running offloaded computation to exit. Cancelling a compute future
does not erase work that its worker actually finishes. Rejected requests perform
no synthetic CPU steps, and pending `hold` checks perform none either.

The runner independently requires exactly three completed steps per HTTP 200
and exactly `3 * (light_successes * 1000 + heavy_successes * heavy_iterations)`
successful iterations. Each other outcome permits zero through three steps per
request. Its iteration count must lie between the completed-step count times
the smaller and larger configured iteration counts; outcome totals must also
sum exactly. Lifecycle hold cancellation/timeouts require zero completed work.

The current summarizer derives per-class timely-success, rejection and timeout
fractions against offered traffic, heavy timeout fraction against accepted heavy
traffic, mean completed steps/iterations per terminal outcome, and
`non_success_cpu_iteration_percent`. The last metric is the fraction of recorded
iterations belonging to cancelled, timed-out or failed requests. These are work
proxies, not measured CPU-time shares: they exclude transport, scheduling, GC
and other runtime work. Archived datasets without `heavy_capacity` retain their
original summary schema and are not populated with inferred new metrics.

Client CPU, allocation and GC counters cover its timed phase, with sample arrays
allocated beforehand and percentile sorting afterward. Server counters span
the supervisor's before/after snapshots, a slightly wider window including
control processing and the client's phase setup/summary work. Server CPU cores
use this separate `server_window_seconds` denominator. The same shared-machine
contention can affect both processes even while only one counter is being read.

Allocation comes from JVM-wide `ThreadMXBean` totals and includes HTTP machinery,
timers, result bookkeeping, JVM activity and rejected requests. Server allocation
is reported per dispatched request and per accepted root; neither quantity is
an isolated fiber allocation cost. Snapshot heap usage is not a retained-memory
measurement: there is no controlled population of waiting tasks and no server
post-GC retained-heap protocol here. This benchmark also records no profiler
samples. Earlier mass-waiting memory or JFR conclusions cannot be inferred again
from these HTTP measurements.

Both JVMs, their timer/HTTP threads and the load generator share the host CPU
quota. Four configured processors/carriers do not reserve four cores for each
process. Client CPU, arrival lag, local rejections and recorded cgroup throttling
must therefore accompany server comparisons. The common HTTP adapter, writers,
connection management or client can become the limiting factor before task
execution does. A transport-limited result does not establish a scheduler's
maximum throughput. A separate load machine and a representative downstream
service are needed before making deployment-capacity claims.

`environment.json` preserves the source commit/status, source and artifact SHA-256
hashes, exact commands, JVM settings, machine limits and cgroup CPU statistics.
`results.json` retains lifecycle checks, warmup, every measured JVM pair and
before/after server snapshots; stderr logs are separate. `summary.json` reports
the median and min/max of each per-JVM metric, with its contributing sample
count. It never pools latency samples across JVMs. Three repetitions on a shared
host provide descriptive ranges, not statistical confidence intervals.
