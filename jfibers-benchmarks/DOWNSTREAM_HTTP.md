# Asynchronous downstream HTTP with disjoint CPU affinity

This experiment replaces each synthetic business wait in the
[request-per-root harness](REQUEST_HTTP.md) with a real asynchronous HTTP/1.1
call to a separate dependency JVM. The dependency delays its response without
blocking its handler, then returns a small validated payload. A timer-only
control uses the same application CPU allocation and compute width. This
distinguishes the cost of real transport and its client callbacks from a change
in admission policy or CPU budget.
The [recorded 60-run comparison](DOWNSTREAM_HTTP_RESULTS.md) includes both
compute widths, raw data, the rejected initial series and the cancellation
regression corrected before the final measurements.

## Request and cancellation contract

Successful normal requests perform three rounds of waiting and computation.
With `--wait-modes http`, each round calls
`GET /step?id=N&step=0..2&delayMs=N` using `HttpClient.sendAsync`. A successful
response contains exactly three big-endian 64-bit integers: request ID, step,
and the value 42. HTTP status, length and all values must match. The CPU work and
final checksum remain the same as in timer mode; the network response is
validated before the corresponding computation begins.

The body observes a cancellable future. Cancelling or expiring the root wakes
that observer, but does **not** cancel the underlying HTTP exchange. Cleanup
waits for the physical call to complete, so the global and heavy-request slots
continue to cover outstanding I/O. This deliberately tests a dependency that
must drain after cancellation; it is not a comparison of native HTTP abort
mechanisms. The independent five-second HTTP safety timeout bounds a stuck
dependency. Transport, status or payload failures invalidate a benchmark run,
including when they occur after the root has already decided on cancellation
or deadline expiry.

The 100 ms server deadline requests cooperative termination; it does not promise
an immediate response while I/O or computation is still finishing. The 150 ms
client SLO measures from intended arrival through the complete response. Late
HTTP 200, server 504 and early admission 503 remain distinct outcomes.

`hold` lifecycle probes retain their local synthetic source so they can fill
global/heavy capacity deterministically. Separate engine tests exercise real
HTTP cancellation, deadline expiry, malformed responses and cleanup. The normal
successful probes and all normal measured requests use the selected wait mode.

## CPU allocation

The runner accepts explicit `--client-cpus`, `--server-cpus` and
`--dependency-cpus` lists. They must be disjoint subsets of the CPUs available
to the runner. Each JVM starts through `taskset`; its JVM processor count follows
its CPU-set width, as does the application's virtual-thread carrier count.
The runner verifies the allowed CPU sets of all observed JVM threads at phase
boundaries and records the observations and CPU topology.

For the current environment, the intended layout is:

| Role | Logical CPUs | JVM active processors |
| --- | --- | ---: |
| Load generator | 0 | 1 |
| Application | 1,2 | 2 |
| Downstream dependency | 3 | 1 |

`FIBER_POOL_OFFLOAD` uses one fiber owner plus **one** compute thread under this
two-CPU application allocation (`--compute-threads 1`). Virtual threads use two
carriers. Timer controls keep the same allocation while the dependency CPU is
unused. All HTTP handler, writer, timer and HTTP-client executor work stays
within its JVM's CPU affinity.

One compute worker limits heavy computation to one concurrent CPU job, whereas
the two VT carriers can both compute. The recorded follow-up tests two offload
compute workers within the same two-CPU mask, with fresh VT controls. The owner
and workers then compete for those CPUs; this does not reserve a core for the
owner or add capacity. The two-worker setting did not establish a consistent
throughput improvement at the tested HTTP load.

This is **disjoint affinity in one guest**, not separate machines, exclusive
physical cores or independent CPU quotas. The guest exposes five logical CPUs,
and the processes still share one four-CPU cgroup quota. The four assigned CPUs
fit that quota, but other activity, kernel work, quota periods and the host can
still affect measurements. CPU 4 is not assigned to a measured JVM. Cgroup
throttling and per-process CPU counters remain necessary diagnostics.

Previous reports gave the application four active processors and three offload
threads, sharing CPUs with the load generator. Their absolute throughput is
not a matched baseline for this experiment. New timer and disabled-heavy-gate
controls are required under the new allocation.

## Running the experiment

Build with the benchmark profile, using the project's JDK and Maven:

```sh
./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify

# Portable correctness smoke, without a required CPU topology.
python3 jfibers-benchmarks/request_http_run.py --quick \
  --backends FIBER_POOL_OFFLOAD VIRTUAL_THREADS \
  --cases 500:100000 --heavy-capacities 0 6 \
  --wait-modes http --compute-threads 1 \
  --output /path/to/new/downstream-smoke

# Load points selected by the archived pilot; recalibrate on another machine.
python3 jfibers-benchmarks/request_http_run.py \
  --backends FIBER_POOL_OFFLOAD VIRTUAL_THREADS \
  --cases 500:1000000 2000:1000000 --heavy-capacities 0 6 \
  --wait-modes timer http --compute-threads 1 --dependency-delay-ms 1 \
  --client-cpus 0 --server-cpus 1,2 --dependency-cpus 3 \
  --warmup-ms 15000 --measure-ms 10000 --forks 3 \
  --output /path/to/new/downstream-results

# Compute-width follow-up, with fresh VT controls under the same CPU mask.
python3 jfibers-benchmarks/request_http_run.py \
  --backends FIBER_POOL_OFFLOAD VIRTUAL_THREADS \
  --cases 2000:1000000 --heavy-capacities 0 6 --wait-modes http \
  --compute-threads 2 --dependency-delay-ms 1 \
  --client-cpus 0 --server-cpus 1,2 --dependency-cpus 3 \
  --warmup-ms 15000 --measure-ms 10000 --forks 3 \
  --output /path/to/new/two-workers

python3 jfibers-benchmarks/request_http_summarize.py \
  /path/to/new/downstream-results
```

The CPU lists above apply to this guest; choose available, disjoint lists on
another machine. Output directories must be new. Two modes, two rates, two
backends, two heavy caps and three repetitions produce 48 fresh application/
client JVM pairs; HTTP mode adds a fresh third dependency JVM to each pair.
Startup and lifecycle checks precede each pair's own warmup and measurement.

The original defaults remain timer mode, three compute threads and no explicit
affinity. No retry loop is added to business requests. The workload retains its
exact synthetic cost classifier: 90% light, 10% heavy, with 1,000 versus
1,000,000 iterations per step in the illustrative full command.

## Independent I/O accounting

Application counters distinguish started, completed, successful and failed
physical downstream calls, active calls and executor work. The dependency has
its own request, response, status, byte, timer, queue and active-exchange
counters. Before and after each phase the runner waits for both processes to
drain; a future completing in the application does not itself prove the
dependency's response bookkeeping has finished.

The runner reconciles physical-call completions with dependency responses,
requires 24 response bytes per successful call, and rejects transport errors,
dependency overload or malformed responses. Successful normal roots make
exactly three validated calls. Expired roots may make fewer; completed CPU steps
cannot exceed started downstream calls, and normal accepted roots can start at
most three calls each. A business admission rejection makes no downstream call.

Compare timely success against **all offered requests**, separately for light
and heavy classes, along with goodput, successful-response latency, rejection
reasons and computation on requests ending in 504. Use dependency CPU, queue
drain, failure counters and client arrival lag to check whether an observed
limit belongs to the dependency or generator. Even with real sockets, this
controlled small-response service does not model a database, external network
latency, retries or production request-cost uncertainty.
