package com.github.rostmyr.jfibers;

import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * Rostyslav Myroshnychenko
 * on 31.05.2018.
 */
@SuppressWarnings("unchecked")
public abstract class Fiber<E> {
    protected volatile FiberManager scheduler;
    protected Object result;
    protected Exception exception;
    protected volatile int state;
    protected Fiber<?> next;
    FiberWait wait;
    List<FiberWait> dependents;
    Fiber<?> activePrevious;
    Fiber<?> activeNext;
    Fiber<?> parent;
    List<Fiber<?>> children;
    boolean started;
    boolean queued;
    boolean finishing;
    boolean cancellationDelivered;
    boolean deadlineQueued;
    long deadlineNanos;
    private long timeoutNanos = -1;
    private volatile FiberStatus status = FiberStatus.NEW;
    private volatile Exception termination;
    private Throwable failure;

    /** Public lifecycle status, independent of the generated continuation number. */
    public FiberStatus getStatus() {
        return status;
    }

    public boolean isDone() {
        return status.isTerminal();
    }

    public boolean isCancellationRequested() {
        return termination != null;
    }

    /** An already-completed value; also used to preserve pending Java return values through finally. */
    public static <T> Fiber<T> completed(T value) {
        Fiber<T> fiber = new Fiber<>() {
            @Override
            public int update() {
                return -1;
            }
        };
        fiber.result = value;
        fiber.state = -1;
        fiber.status = FiberStatus.COMPLETED;
        return fiber;
    }

    /** Request cancellation from any thread. Cleanup runs cooperatively on the scheduler. */
    public boolean cancel() {
        return requestTermination(new CancellationException("Fiber cancelled"));
    }

    /** Set a timeout before scheduling; elapsed time starts when the fiber is scheduled. */
    public synchronized Fiber<E> withTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }
        if (scheduler != null || isReady()) {
            throw new IllegalStateException("Configure timeout before scheduling");
        }
        try {
            timeoutNanos = Math.min(timeout.toNanos(), Long.MAX_VALUE / 4);
        } catch (ArithmeticException e) {
            timeoutNanos = Long.MAX_VALUE / 4;
        }
        return this;
    }

    synchronized boolean requestTermination(Exception reason) {
        if (isReady() || termination != null) {
            return false;
        }
        termination = reason;
        if (scheduler == null) {
            fail(reason);
            clearFrame();
            state = -1;
            status = terminalStatus();
        } else {
            scheduler.cancelRequested(this);
        }
        return true;
    }

    long timeoutNanos() {
        return timeoutNanos;
    }

    Exception termination() {
        return termination;
    }

    void status(FiberStatus value) {
        status = value;
    }

    FiberStatus terminalStatus() {
        if (termination instanceof TimeoutException) {
            return FiberStatus.TIMED_OUT;
        }
        if (termination instanceof CancellationException || exception instanceof CancellationException) {
            return FiberStatus.CANCELLED;
        }
        return exception == null ? FiberStatus.COMPLETED : FiberStatus.FAILED;
    }

    /** Compiler hook: cancellation is injected once, at the source suspension point. */
    public void checkCancellation() {
        Exception requested = termination;
        if (requested != null && !cancellationDelivered) {
            cancellationDelivered = true;
            current = null;
            future = null;
            Fiber.<RuntimeException>throwUnchecked(requested);
        }
    }

    /** Generated continuations override this to run source catch/finally during cancellation. */
    protected boolean supportsCancellationUnwind() {
        return false;
    }

    /** Generated fibers release captured object references here after termination. */
    protected void clearFrame() {
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    /** Original failure, including an Error; getException() remains the compatibility accessor. */
    public Throwable getFailure() {
        return failure;
    }

    // the current fiber we are waiting for
    protected Fiber<?> current;
    // the current future we are waiting for
    protected Future<?> future;

    /**
     * Gets a current fiber's state
     *
     * @return state
     */
    @Deprecated
    public int getState() {
        return state;
    }

    /**
     * Sets the new state for the fiber
     *
     * @param state new state
     */
    @Deprecated
    public void setState(int state) {
        this.state = state;
        if (state == -1) {
            status = terminalStatus();
        }
    }

    /**
     * Returns computed fiber's result
     *
     * @return an object link
     */
    public E getResult() {
        return (E) result;
    }

    /**
     * Gets an exception if any has been arise during fiber's execution
     *
     * @return exception, may be null
     */
    public Exception getException() {
        return exception;
    }

    /**
     * Called by {@link FiberManager} on each iteration.
     *
     * @return current/next state of the fiber
     */
    public abstract int update();

    /**
     * Waits until the given fiber is not {@link #isReady()}
     *
     * @param fiber a fiber
     * @return the next state
     */
    public int awaitFor(Fiber<?> fiber) {
        Objects.requireNonNull(fiber, "fiber");
        for (Fiber<?> ancestor = this; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor == fiber) {
                throw new IllegalStateException("Cannot await an owning ancestor fiber");
            }
        }
        for (Fiber<?> dependency = fiber; dependency != null; dependency = dependency.current) {
            if (dependency == this) {
                throw new IllegalStateException("Cyclic fiber dependency");
            }
        }
        if (!fiber.isReady() && fiber.scheduler == null) {
            if (scheduler == null) {
                throw new IllegalStateException("Schedule the parent before awaiting an incomplete child");
            }
            scheduler.scheduleChild(this, fiber);
        } else if (!fiber.isReady() && fiber.scheduler != scheduler) {
            throw new IllegalStateException("Awaited fiber belongs to another scheduler");
        }
        current = fiber;
        return state + 1;
    }

    /**
     * Waits until the given future is not {@link Future#isDone()}
     *
     * @param future a future
     * @return the next state
     */
    public int awaitFor(Future<?> future) {
        this.future = Objects.requireNonNull(future, "future");
        return state + 1;
    }

    /**
     * A marker method
     */
    public static <T> T call(T call) {
        throw uninstrumented();
    }

    /**
     * A marker method
     */
    public static <T> T call(Fiber<T> call) {
        throw uninstrumented();
    }

    /**
     * Should be called by {@link #call(Fiber)}}
     *
     * case 0:
     * awaitFor(call(fiber))
     * return 1
     * case 1:
     * return awaitFiber(); // returns 1 while current.isReady() returns false
     * case 2:
     * this.someVariable = this.result;
     * ...
     */
    public int awaitFiber() {
        if (!current.isReady()) {
            return state;
        }
        return completeFiber(state + 1);
    }

    /**
     * A marker method
     */
    public static <T, F extends Future<T>> T call(F call) {
        throw uninstrumented();
    }

    /**
     * Should be called by {@link #call(Future)}}
     *
     * case 0:
     * awaitFor(future)
     * return 1
     * case 1:
     * return awaitFuture(); // returns 1 while future.isDone() returns false
     * case 2:
     * this.someVariable = this.result;
     * ...
     */
    public int awaitFuture() {
        if (!future.isDone()) {
            return state;
        }
        return completeFuture(state + 1);
    }

    /**
     * A marker method
     */
    public static <T> Fiber<T> result(T result) {
        throw uninstrumented();
    }

    /**
     * Should be called by {@link #result(Object)}}
     *
     * ...
     * case n:
     * return this.resultLiteral(literal)
     */
    public <T> int resultLiteral(T result) {
        this.result = result;
        return -1;
    }

    /**
     * A marker method
     */
    public static <T, F extends Fiber<T>> F result(F fiber) {
        throw uninstrumented();
    }

    /**
     * Should be called by {@link #result(Fiber)}}
     *
     * ...
     * case n:
     * return awaitFor(call(fiber))
     * case n + 1:
     * return waitForFiberResult(); // returns n + 1 while current.isReady() returns false
     * ...
     */
    public int waitForFiberResult() {
        if (!current.isReady()) {
            return state;
        }
        return completeFiber(-1);
    }

    /**
     * A marker method
     */
    public static <T, F extends Future<T>> Fiber<T> result(F call) {
        throw uninstrumented();
    }

    /**
     * Should be called by {@link #result(Future)}}
     *
     * ...
     * case n:
     * return awaitFor(call(fiber))
     * case n + 1:
     * return waitForFutureResult(); // returns n + 1 while current.isReady() returns false
     * ...
     */
    public int waitForFutureResult() {
        if (!future.isDone()) {
            return state;
        }
        return completeFuture(-1);
    }

    /** Resume at the source await instruction so Java catch/finally handlers can observe failures. */
    public Object resumeAwait() throws Exception {
        checkCancellation();
        if (current != null) {
            Fiber<?> completed = current;
            current = null;
            if (!completed.isReady()) {
                throw new IllegalStateException("Child resumed before completion");
            }
            if (completed.failure != null) {
                Fiber.<RuntimeException>throwUnchecked(completed.failure);
            } else if (completed.exception != null) {
                throw completed.exception;
            }
            return completed.result;
        }
        Future<?> completed = future;
        future = null;
        if (completed == null || !completed.isDone()) {
            throw new IllegalStateException("Future resumed before completion");
        }
        try {
            return completed.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private int completeFiber(int nextState) {
        Fiber<?> completed = current;
        current = null;
        if (completed.exception != null) {
            return fail(completed.exception);
        }
        result = completed.result;
        return nextState;
    }

    private int completeFuture(int nextState) {
        try {
            result = future.get();
            future = null;
            return nextState;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(e);
        } catch (ExecutionException | CancellationException e) {
            return fail(e);
        }
    }

    protected int fail(Throwable failure) {
        this.failure = failure;
        exception = failure instanceof Exception checked ? checked : new FiberExecutionException(failure);
        result = null;
        current = null;
        future = null;
        return -1;
    }

    protected int fail(Exception failure) {
        return fail((Throwable) failure);
    }

    /**
     * A marker method
     */
    public static Fiber<Void> nothing() {
        throw uninstrumented();
    }

    private static IllegalStateException uninstrumented() {
        return new IllegalStateException("Fiber marker executed without instrumentation; enable the jfibers Maven plugin"
            + " at process-classes or use the jfibers javaagent");
    }

    /**
     * Should be called by {@link #nothing()}}
     *
     * ...
     * case n:
     * return nothingInternal();
     * ...
     */
    public int nothingInternal() {
        result = null;
        return -1;
    }

    public boolean isReady() {
        return state == -1;
    }
}
