package com.github.rostmyr.jfibers;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Rostyslav Myroshnychenko
 * on 31.05.2018.
 */
@SuppressWarnings("unchecked")
public abstract class Fiber<E> {
    protected FiberManager scheduler;
    protected Object result;
    protected Exception exception;
    protected int state;
    protected Fiber<?> next;

    // the current fiber we are waiting for
    protected Fiber<?> current;
    // the current future we are waiting for
    protected Future<?> future;

    /**
     * Gets a current fiber's state
     *
     * @return state
     */
    public int getState() {
        return state;
    }

    /**
     * Sets the new state for the fiber
     *
     * @param state new state
     */
    public void setState(int state) {
        this.state = state;
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
        this.current = Objects.requireNonNull(fiber, "fiber");
        if (!current.isReady() && current.scheduler == null) {
            scheduler.schedule(current);
        } else if (!current.isReady() && current.scheduler != scheduler) {
            throw new IllegalStateException("Awaited fiber belongs to another scheduler");
        }
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
        return call;
    }

    /**
     * A marker method
     */
    public static <T> T call(Fiber<T> call) {
        return null;
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
        return null;
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
        return null;
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
        return null;
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
        return null;
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

    protected int fail(Exception failure) {
        exception = failure;
        result = null;
        current = null;
        future = null;
        return -1;
    }

    /**
     * A marker method
     */
    public static Fiber<Void> nothing() {
        return null;
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
