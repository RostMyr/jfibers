package com.github.rostmyr.jfibers;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Rostyslav Myroshnychenko
 * on 31.05.2018.
 */
public abstract class Fiber<E> {
    protected FiberManager scheduler;
    protected Object result;
    protected Throwable exception;
    protected int state;
    protected Fiber<?> next;

    // the current fiber we are waiting for
    protected Fiber current;
    // the current future we are waiting for
    protected Future future;

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public E getResult() {
        return (E) result;
    }

    public abstract int update();

    public int awaitFor(Fiber fiber) {
        this.current = fiber;
        if (current.scheduler == null) {
            scheduler.schedule(current);
        }
        return state + 1;
    }

    public int awaitFor(Future future) {
        this.future = future;
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
     *    awaitFor(call(fiber))
     *    return 1
     * case 1:
     *    return awaitFiber(); // returns 1 while current.isReady() returns false
     * case 2:
     *    this.someVariable = this.result;
     * ...
     */
    public int awaitFiber() {
        if (!current.isReady()) {
            return state;
        }
        this.result = current.result;
        return state + 1;
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
     *    awaitFor(future)
     *    return 1
     * case 1:
     *    return awaitFuture(); // returns 1 while future.isDone() returns false
     * case 2:
     *    this.someVariable = this.result;
     * ...
     */
    public int awaitFuture() {
        if (!future.isDone()) {
            return state;
        }
        try {
            this.result = future.get();
        } catch (InterruptedException | ExecutionException e) {
            this.exception = e;
            return -1;
        }
        return state + 1;
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
     *    return this.resultLiteral(literal)
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
     *    return awaitFor(call(fiber))
     * case n + 1:
     *    return waitForFiberResult(); // returns n + 1 while current.isReady() returns false
     * ...
     */
    public int waitForFiberResult() {
        if (!current.isReady()) {
            return state;
        }
        this.result = current.result;
        return -1;
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
     *    return awaitFor(call(fiber))
     * case n + 1:
     *    return waitForFutureResult(); // returns n + 1 while current.isReady() returns false
     * ...
     */
    public int waitForFutureResult() {
        if (!future.isDone()) {
            return state;
        }
        try {
            this.result = future.get();
        } catch (InterruptedException | ExecutionException e) {
            this.exception = e;
        }
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
     *    return nothingInternal();
     * ...
     *
     */
    public int nothingInternal() {
        return -1;
    }

    public boolean isReady() {
        return state == -1;
    }
}
