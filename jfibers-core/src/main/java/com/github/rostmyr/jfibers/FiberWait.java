package com.github.rostmyr.jfibers;

import java.util.function.BiConsumer;

/** A completion callback that can release its references before the source completes. */
final class FiberWait implements BiConsumer<Object, Throwable> {
    private volatile FiberManager manager;
    volatile Fiber<?> fiber;

    FiberWait(FiberManager manager, Fiber<?> fiber) {
        this.manager = manager;
        this.fiber = fiber;
    }

    @Override
    public void accept(Object result, Throwable failure) {
        FiberManager owner = manager;
        if (owner != null) {
            owner.signal(this);
        }
    }

    void dispose() {
        manager = null;
        fiber = null;
    }
}
