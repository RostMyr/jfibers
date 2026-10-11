# Limiting concurrent heavy HTTP requests

This experiment asks whether rejecting excess heavy work at admission improves
useful service under overload. It compares an explicit heavy-request limit with
the disabled-gate baseline, using the same policy for `FIBER_POOL_OFFLOAD` and
`VIRTUAL_THREADS`. It extends the [request-per-root HTTP protocol](REQUEST_HTTP.md);
the transport, workload, deadlines, arrival schedule and measurements described
there still apply. This document defines the policy and experiment, without
claiming measured results.

## Policy

Global admission permits at most 64 accepted roots. The optional heavy limit is
0, 3, 6 or 12; zero disables the heavy gate. A heavy root owns one heavy slot for
its entire accepted lifetime, including queueing, asynchronous waits, all three
compute steps and cleanup. Cancellation releases the slot only after cleanup
and actual exit of any running computation. A slot therefore represents an
outstanding heavy request, not a CPU thread or a single compute step.

The gate is checked before global admission. Excess heavy requests immediately
receive HTTP 503 with `X-Admission-Rejection: heavy`; global exhaustion produces
the same status with reason `global`. Heavy takes precedence when both limits
are full. Rejection creates no runnable root and performs no synthetic CPU work.
If global admission rejects after a heavy reservation, that reservation is
rolled back. Light requests bypass the heavy gate and retain access to whatever
global capacity remains. There is no waiting queue or retry behind the heavy
limit and no automatic borrowing based on light traffic.

This is application admission policy in the benchmark's `RequestTaskEngine`.
It does not add a priority class or weighted scheduler to `FiberPool`. The
virtual-thread backend has the same heavy gate and global capacity, allowing
the experiment to separate admission-policy effects from execution-backend
effects.

`FIBER_POOL_OFFLOAD` keeps one fiber owner and three compute threads; heavy
compute stages use the bounded executor, while light stages stay on the owner.
`VIRTUAL_THREADS` creates one virtual thread per accepted request and performs
computation on its virtual-thread carriers. The configured limit does not change
these execution widths or the global capacity.

## Controlled sweep

Normal requests alternate three 1 ms synthetic asynchronous waits with compute
steps. Ten percent are heavy, doing 1,000,000 integer-mixing iterations per step;
light requests do 1,000. Classification follows the same deterministic ID
pattern for every backend and cap. Heavy work is known at admission in this
experiment; predicting real request cost is a separate application concern.

The main sweep consists of two arrival rates, four heavy limits, two backends
and three independent repetitions: **48 fresh client/server JVM pairs**.

```sh
./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify

# Short smoke; correctness only.
python3 jfibers-benchmarks/request_http_run.py --quick \
  --backends FIBER_POOL_OFFLOAD VIRTUAL_THREADS \
  --cases 2000:1000000 \
  --heavy-capacities 0 3 6 12 \
  --output jfibers-benchmarks/target/heavy-admission-smoke

# 2 rates x 4 heavy limits x 2 backends x 3 repetitions = 48 JVM pairs.
python3 jfibers-benchmarks/request_http_run.py \
  --backends FIBER_POOL_OFFLOAD VIRTUAL_THREADS \
  --cases 2000:1000000 4000:1000000 \
  --heavy-capacities 0 3 6 12 \
  --capacity 64 --max-in-flight 512 \
  --deadline-ms 100 --slo-ms 150 \
  --warmup-ms 15000 --measure-ms 10000 --forks 3 \
  --output /path/to/new/heavy-admission-results

python3 jfibers-benchmarks/request_http_summarize.py \
  /path/to/new/heavy-admission-results
```

Each pair runs unmeasured lifecycle/admission checks, then its own warmup and
measurement with complete draining. The runner interleaves cap/backend variants
within cases and rotates their order across repetitions; it does not collect
every disabled-gate run as a separate initial block. Results, labels and
metadata record `heavy_capacity`, while the previous default remains `[0]`.

Lifecycle checks use a separate negative-ID range. Timed traffic starts from
the same positive ID sequence for every variant, independent of the number of
checks performed. `first_request_id` and offered counts allow auditing that
the intended light/heavy arrivals match. Admission outcomes are allowed to
differ; arrivals are never suppressed because an earlier request was rejected.

The common checks fill global capacity using light requests and verify global
rejection. Positive heavy caps add a separate saturation check: fill heavy slots,
verify heavy rejection, admit light work while global space remains, cancel the
held roots with asynchronous cleanup, then prove heavy admission works again.
Every tracked source, timer, task, heavy reservation, computation and response
must drain. The equal-global-and-heavy-capacity edge case is also handled by the
check protocol, although it is not part of this four-cap sweep.

## What would make a policy useful?

Judge the admission tradeoff against all offered requests, separately for light
and heavy classes. A lower heavy-success p99 alone is insufficient: rejecting
more work can improve the latency distribution of the survivors.

The primary comparisons are:

- Light timely-success fraction and light successful p99: does the policy protect
  ordinary traffic at both offered rates?
- Heavy timely-success fraction, heavy/global rejection fractions and heavy
  timeout fraction among accepted heavy requests: does it reject early instead
  of accepting work that will expire, and how much heavy demand is refused?
- Overall goodput and per-class late success: do more offered requests receive
  useful responses within the 150 ms client SLO?
- Completed work attributed to timed-out/cancelled/failed requests: does the
  policy reduce computation that fails to produce a successful response?

The server deadline remains 100 ms from root submission, while client SLO
latency starts at intended arrival. Both matter. A server-successful response
may still miss the client SLO because of generator lag, transport or response
queueing. HTTP 503 is a rejected request, never a useful completion, even when
it arrives quickly. The client does not retry rejected work in this experiment;
real retry behavior could materially change the offered load.

Completed-step and iteration counters are aggregated at terminal request
completion after cleanup, including compute work that finishes after a future
has been cancelled. The runner checks successful work against class counts and
bounds each other outcome to zero through three completed steps per request.
`mean_cpu_steps_per_timed_out` and `mean_cpu_iterations_per_timed_out` describe
how much synthetic work timed-out requests actually completed.
`non_success_cpu_iteration_percent` divides iterations from cancelled, timed-out
or failed requests by all completed iterations. These metrics quantify the
workload's integer loops, not CPU seconds or the cost of scheduling, timers,
HTTP, GC or early rejection. Use them alongside process CPU rather than as a
replacement for it.

Compare each positive cap with cap 0 on the **same current source/artifact**;
then compare the two backends at the same cap and arrival rate. The archived
initial HTTP results use their own source version and are contextual evidence,
not interchangeable control repetitions for this new sweep. The current
summary keeps caps separate and reports medians and ranges of per-JVM metrics
without pooling percentiles.

## Limits on interpretation

Client and server share a four-core quota. Arrival lag, client CPU, local
in-flight rejections and cgroup throttling can expose a generator or transport
limit instead of a task-execution limit. All variants retain the same HTTP
adapter and writer queues. A cap that works here is not automatically suitable
for another machine, heavy-task mix, downstream service or deadline.

The request classifier is exact and synthetic; a production policy needs an
application-defined cost signal, acceptable rejection behavior and monitoring
for misclassified or unexpectedly expensive work. The sweep does not implement
adaptive limits, priorities, tenant fairness, retries or a maximum wait queue.
It tests one explicit concurrent-heavy-request limit before considering those
extensions.

Three repetitions provide descriptive variation on this machine. A cap should
not be selected from a single best run or from a marginal median improvement
whose ranges overlap substantially. Preserve the complete per-class outcomes,
raw counters and commands with any recommendation. The experiment records no
new retained-memory comparison or profiler samples.
