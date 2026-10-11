package com.github.rostmyr.jfibers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** A pool submission. Completion is published only after the fiber and its owned children finish cleanup. */
public final class FiberTask<T> {
    final FiberPool pool;
    final FiberPool.Worker worker;
    // Admission/lifecycle state is guarded by pool's private lock, including during factory execution.
    Supplier<? extends Fiber<T>> factory;
    Fiber<T> fiber;
    boolean queued = true;
    boolean cancellationRequested;
    boolean finished;
    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private final CompletionStage<T> view = completion.minimalCompletionStage();

    FiberTask(FiberPool pool, FiberPool.Worker worker, Supplier<? extends Fiber<T>> factory) {
        this.pool = pool;
        this.worker = worker;
        this.factory = factory;
    }

    /**
     * Request cooperative cancellation. A true result acknowledges the request, not completed cleanup.
     * This handle intentionally does not implement Future's immediate-cancellation contract.
     */
    public boolean cancel() {
        return pool.cancel(this);
    }

    public boolean isCancellationRequested() {
        return pool.isCancellationRequested(this);
    }

    public boolean isDone() {
        return completion.isDone();
    }

    /**
     * Read-only completion stage; toCompletableFuture() returns an independent observer.
     * Cancelling/completing that observer does not change this task. Use cancel() on this handle.
     * Non-async dependent actions run on the completing thread and must not block a pool owner.
     */
    public CompletionStage<T> completion() {
        return view;
    }

    void publish(T result, Throwable failure) {
        if (failure == null) {
            completion.complete(result);
        } else {
            completion.completeExceptionally(failure);
        }
    }
}
