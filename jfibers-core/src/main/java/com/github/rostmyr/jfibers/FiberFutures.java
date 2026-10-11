package com.github.rostmyr.jfibers;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Notification adapters and explicit offloading to caller-owned executors. */
public final class FiberFutures {
    private static final AtomicInteger IDS = new AtomicInteger();
    private FiberFutures() {
    }

    /**
     * Fixed platform-thread executor with a bounded waiting queue and synchronous rejection.
     * Overload never executes work on the submitting thread. The caller must close the executor.
     * shutdownNow also cancels queued Future tasks, completing their observers.
     */
    public static ExecutorService newComputeExecutor(int parallelism, int queueCapacity) {
        if (parallelism < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("Parallelism and queue capacity must be positive");
        }
        return new ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofPlatform().name("jfibers-compute-" + IDS.incrementAndGet() + "-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy()) {
            @Override
            public List<Runnable> shutdownNow() {
                List<Runnable> queued = super.shutdownNow();
                for (Runnable task : queued) {
                    if (task instanceof Future<?> future) {
                        future.cancel(false);
                    }
                }
                return queued;
            }
        };
    }

    /**
     * Submit computation and return a notification-capable future. Rejection is synchronous.
     * Cancelling this future cancels the task, optionally interrupting its executor thread;
     * cancellation removes queued work from ThreadPoolExecutor queues. As with FutureTask,
     * cancellation completes immediately and does not wait for running computation to exit.
     * Awaiting fibers do not own this future: cancel it explicitly in finally when appropriate.
     * Execution policy belongs to the supplied executor; newComputeExecutor always offloads.
     */
    public static <T> CompletableFuture<T> submit(Callable<T> computation, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        Computation<T> task = new Computation<>(Objects.requireNonNull(computation, "computation"), executor);
        executor.execute(task);
        return task.completion;
    }

    private static final class Computation<T> extends FutureTask<T> {
        private final Executor executor;
        private final TaskCompletion<T> completion = new TaskCompletion<>(this);

        Computation(Callable<T> computation, Executor executor) {
            super(computation);
            this.executor = executor;
        }

        @Override
        protected void done() {
            // A retained result must not retain the executor and its other queued computations.
            completion.task = null;
            if (isCancelled()) {
                if (executor instanceof ThreadPoolExecutor pool) {
                    pool.remove(this);
                }
                completion.cancelFromTask();
            } else {
                try {
                    completion.complete(get());
                } catch (ExecutionException failure) {
                    completion.completeExceptionally(failure.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    completion.completeExceptionally(interrupted);
                }
            }
        }
    }

    private static final class TaskCompletion<T> extends CompletableFuture<T> {
        private volatile Computation<T> task;

        TaskCompletion(Computation<T> task) {
            this.task = task;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            Computation<T> pending = task;
            return isCancelled() || (!isDone() && pending != null && pending.cancel(mayInterruptIfRunning));
        }

        void cancelFromTask() {
            super.cancel(false);
        }
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
