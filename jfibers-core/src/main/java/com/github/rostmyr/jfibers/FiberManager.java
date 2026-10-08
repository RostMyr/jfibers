package com.github.rostmyr.jfibers;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rostyslav Myroshnychenko
 * on 31.05.2018.
 */
public class FiberManager {
    private volatile boolean interrupted;

    private Fiber<?> current;
    private Fiber<?> last;
    private final AtomicInteger size = new AtomicInteger();

    /**
     * Schedules a given fiber adding it to the queue
     *
     * @param fiber a fiber to be scheduled
     */
    public void schedule(Fiber<?> fiber) {
        Objects.requireNonNull(fiber, "fiber");
        if (fiber.scheduler != null) {
            throw new IllegalStateException("Fiber is already scheduled");
        }
        if (fiber.isReady()) {
            throw new IllegalStateException("Fiber has already completed");
        }
        fiber.next = null;
        enqueue(fiber);
        fiber.scheduler = this;
        size.incrementAndGet();
    }

    private void enqueue(Fiber<?> fiber) {
        if (last == null) {
            current = fiber;
        } else {
            last.next = fiber;
        }
        last = fiber;
    }

    /**
     * Gets the number of scheduled fibers, including a fiber currently being updated.
     * This count may also be read from another thread.
     *
     * @return the number of schedules fibers
     */
    public int getSize() {
        return size.get();
    }

    /**
     * Runs scheduled fibers until the queue is empty or {@link #stop()} is called.
     * Scheduling and execution belong to one thread; stop may be called by another thread.
     */
    public void run() {
        while (!interrupted && current != null) {
            Fiber<?> fiber = current;
            current = fiber.next;
            if (current == null) {
                last = null;
            }
            fiber.next = null;

            int newState;
            try {
                newState = fiber.update();
            } catch (RuntimeException e) {
                newState = fiber.fail(e);
            }
            fiber.setState(newState);
            if (fiber.isReady()) {
                fiber.scheduler = null;
                size.decrementAndGet();
            } else {
                enqueue(fiber);
            }
        }
    }

    /**
     * Interrupts the manager execution
     */
    public void stop() {
        interrupted = true;
    }
}
