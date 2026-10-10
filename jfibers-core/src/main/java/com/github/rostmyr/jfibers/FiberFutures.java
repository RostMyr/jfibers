package com.github.rostmyr.jfibers;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/** Optional notification adapter for a Future implementation without callbacks. */
public final class FiberFutures {
    private FiberFutures() {
    }

    /**
     * Runs a blocking get on the supplied executor (a virtual-thread executor is suitable).
     * The caller owns that executor and the source's lifetime. Cancelling the returned
     * future does not cancel the source or interrupt the executor task.
     */
    public static <T> CompletableFuture<T> observe(Future<T> source, Executor executor) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(executor, "executor");
        CompletableFuture<T> completion = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                completion.complete(source.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                completion.completeExceptionally(e);
            } catch (ExecutionException e) {
                completion.completeExceptionally(e.getCause());
            } catch (RuntimeException e) {
                completion.completeExceptionally(e);
            }
        });
        return completion;
    }
}
