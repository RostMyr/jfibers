# Profiling fibers with JFR

Bytecode transformation remains a build-time operation. Runtime diagnostics use
JDK Flight Recorder and need no agent or additional dependency. They are off by
default. Set the switch before the first use of `FiberManager`:

```sh
java -Djfibers.jfr=true \
  -XX:StartFlightRecording=settings=profile,filename=fibers.jfr \
  -jar application.jar
jfr print --events 'com.github.rostmyr.jfibers.*' fibers.jfr
```

The static switch allows the JIT to eliminate diagnostic hooks in ordinary runs.
Enabling it adds clock reads, counters and sample tracking even when no recording
is active. Measure production throughput with diagnostics disabled; collect
separate recordings when investigating a problem. A custom recording can enable
or disable the events by name. Changing the system property after class
initialization does not change the switch.

## Events

`com.github.rostmyr.jfibers.Scheduler` reports cumulative counters for one `run()`
invocation, identified by `runId`, approximately once per second while the owner
is making progress, plus a final snapshot. Empty runs emit nothing. A parked
owner emits its next snapshot on waking; there is no diagnostics timer thread.
Use the last snapshot per run, or differences between snapshots, **not their sum**.

Fields include executed steps, waits by kind, callback signals, wake requests,
actual `unpark` calls, park count/time, ready-queue size/peak, remaining fibers,
and recorded/abandoned samples. `executionNanos` is elapsed time inside execution
steps, including library and diagnostic work; it is **not CPU time**. Counters
updated by callbacks are concurrent observations, not an atomic transaction with
the owner's snapshot. CompletionStage callback registration and execution can
overlap with the last snapshot. Correlate with standard JFR CPU, allocation and
thread-park events.

`com.github.rostmyr.jfibers.Await` samples waits from suspension to the next actual
execution of the fiber. It records the generated fiber class, continuation state,
wait kind and whether the owner resumed it normally or because of cancellation.
It contains no awaited values, exceptions, HTTP addresses or application payloads.
Stack traces are disabled by default.

| Field | Meaning |
| --- | --- |
| `observedWaitNanos` | Suspension to observed completion callback; for child/plain-Future waits, to the owner's observation of readiness |
| `signalToRunnableNanos` | Completion callback to the owner putting the fiber back on its ready queue |
| `runnableToExecutionNanos` | Time on the ready queue before the next execution step |
| `readyToExecutionNanos` | Sum of the previous two scheduling delays |

For cancellation, readiness is measured when the owner processes cancellation,
not when another thread requests it. Plain Futures and child fibers have no
external completion timestamp, so their `signalToRunnableNanos` is zero and their
observed wait includes polling/observation delay. The duration also includes the
short diagnostic work at either end. These fields do not isolate network time.
Counters cover explicit wait registrations; a finishing parent waiting for
automatic cleanup of its owned children is not recorded as a sampled await.

The default takes every 256th newly registered wait and commits it only if its
total duration exceeds JFR's default 1 ms threshold. Set
`-Djfibers.jfr.sampleEvery=1` for every wait and override the event threshold in a
custom recording for short waits. Sampling is systematic, so periodic workloads
can bias it. Percentiles describe recorded samples, not all operations. Sampling
stores only selected waits in per-run maps; the default path adds no profiling
fields to individual fibers or wait registrations. Stop/termination clears those
maps; unfinished samples are counted as abandoned.

## Reproduce an investigation

The [HTTP harness](jfibers-benchmarks/HTTP.md) collects JDK profiles and these events
in separate runs, extracting scheduler counters and sampled scheduling-delay
percentiles into `profiles.json`. `--standard-jfr` leaves fiber diagnostics off
to help assess their cost. `--profiles-only --backends JFIBERS` collects just the
diagnostic run. Timing comparisons always exclude these recordings.

The [scheduler harness](jfibers-benchmarks/README.md) compares core JARs with the
same JMH artifact and can separately measure diagnostic hooks with and without
an active recording. Keep raw data and exact JVM flags when reporting a change.
The [measured overhead and optimization report](jfibers-benchmarks/OPTIMIZATION.md)
includes the cost of these events on very short tasks and the HTTP comparison.

The [request-per-root downstream HTTP harness](jfibers-benchmarks/DOWNSTREAM_HTTP.md#separate-jfr-diagnostics)
also supports `--profiles-only`, with recording after warmup, CPU affinity for
all three roles, per-thread stack attribution, allocation weights, parks and
sampled awaits by continuation. Use `--standard-jfr` controls to separate library
hooks from standard JFR diagnostics. Its profile files cannot be summarized as
ordinary performance measurements.
