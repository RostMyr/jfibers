# Bounded execution and CPU offload

`FiberManager` remains a single-owner, cooperative scheduler. Use `FiberPool` to
admit roots from arbitrary threads and distribute independent fibers across
several owners. This requires no runtime agent; the application's fiber methods
still use build-time instrumentation.

```java
try (FiberPool pool = new FiberPool(4, 256)) {
    FiberTask<User> task = pool.submit(() -> service.loadUser()
        .withTimeout(Duration.ofSeconds(2)));
    User user = task.completion().toCompletableFuture().get(); // external caller
    pool.shutdown();
    pool.awaitTermination(Duration.ofSeconds(5));
}
```

The factory runs on the selected owner and must return a fresh, unscheduled
fiber. Keep both the factory and each uninterrupted section of fiber code short.
Roots are assigned round-robin. A root and its owned children stay on the same
owner; there is no migration, work stealing or preemption. `FiberManagers.current()`
returns that owner's manager inside a factory or fiber. Let the pool manage its
owner's run/stop/shutdown lifecycle. Use completion stages to communicate across
owners; awaiting a fiber scheduled on another manager is unsupported.

Capacity counts accepted roots throughout queueing, execution, asynchronous waits
and cancellation cleanup. Owned children retain their root's slot and are not
individually counted. This is not a bound on allocations, child creation or work
submitted directly to a manager. A full or shut-down pool throws
`RejectedExecutionException` synchronously without running the rejected factory.
It never blocks the submitter or executes a rejected fiber on the submitter.
Timeouts start at actual scheduling, after the factory, not at pool submission.

`FiberTask.cancel()` requests cooperative cancellation. `true` means the request
was accepted; `isDone()` and `completion()` become terminal only after the fiber
and its owned children finish cleanup. Cancellation of a queued submission prevents
its factory from running. Cancellation during an executing factory cannot preempt
the factory; its returned fiber will not enter its body. Native timeouts and
failures propagate through the completion stage, preserving their original cause.

The completion stage is read-only. Its `toCompletableFuture()` gives an independent
observer: completing or cancelling that observer does not change the task or free
capacity. Use `task.cancel()` to cancel execution. Non-async dependent callbacks
can execute on a pool owner and must remain short and nonblocking. Within a fiber,
use `call(task.completion().toCompletableFuture())` instead of blocking `get()`.

`shutdown()` rejects new work and drains accepted work normally. `shutdownNow()`
requests cancellation of all submissions. `close()` cancels and waits for cleanup;
on one of the pool's own owners it only requests cancellation, avoiding self-join.
`awaitTermination(Duration)` is for external callers and rejects calls from the
pool's own owners. Threads are non-daemon. Blocking code, a loop without suspension,
or cleanup that never completes can prevent termination. External awaited futures
remain caller-owned and are never cancelled implicitly.

## Explicit compute offload

Use `FiberFutures.submit(Callable<T>, Executor)` for CPU work that should not occupy
an owner. The returned `CompletableFuture` uses completion notifications, with no
polling and no extra thread blocking on `Future.get()`.

```java
// This method must be instrumented, just like any other fiber method.
static Fiber<Long> calculate(Input input, Executor executor) {
    CompletableFuture<Long> pending = FiberFutures.submit(() -> expensive(input), executor);
    try {
        Long value = Fiber.call(pending);
        return Fiber.result(value);
    } finally {
        pending.cancel(true);
    }
}

// Resources are closed in reverse order: drain fibers before closing their executor.
try (ExecutorService cpu = FiberFutures.newComputeExecutor(3, 256);
     FiberPool pool = new FiberPool(1, 256)) {
    FiberTask<Long> task = pool.submit(() -> calculate(input, cpu));
    Long value = task.completion().toCompletableFuture().get();
}
```

The factory creates a fixed platform-thread executor with a bounded waiting queue
and `AbortPolicy`: saturation throws `RejectedExecutionException`, never runs CPU
work on the submitting owner. A caller-supplied executor controls its own policy;
for example, a direct executor does not offload anything. Budget the owner threads,
compute threads and other application work against the available CPU quota.

Cancelling the returned computation future propagates to its `FutureTask`.
`cancel(true)` requests interruption of running computation; `cancel(false)` does
not interrupt it. Both prevent queued work from starting and remove it from a
`ThreadPoolExecutor` queue. Unlike `FiberTask`, this future completes on cancellation
without waiting for the running computation to exit; computations must cooperate
with interruption when early termination is required. Cancelling an awaiting fiber
does not implicitly cancel the computation, which is why the example uses `finally`.
The executor remains caller-owned. The supplied compute executor's `shutdownNow()`
also cancels drained queued futures so their observers do not wait forever.

Start with a pool sized to the CPU budget when independent tasks mix waits and
short computation. Consider separate compute execution when long CPU sections
delay unrelated fibers. Offloading introduces a queue, a future and cross-thread
notifications per operation; measure the actual workload before adopting it for
small computations. The [scenario harness](jfibers-benchmarks/SCENARIOS.md) includes
both approaches using the same application work and checksums.
The [recorded comparison](jfibers-benchmarks/POOL_RESULTS.md) includes repeated
measurements and the limits of the long-lived-flow benchmark.
