package com.github.rostmyr.jfibers;

/**
 * Rostyslav Myroshnychenko
 * on 31.05.2018.
 */
@SuppressWarnings("unchecked")
public class FiberManager {
    private boolean interrupted;

    private Fiber current;
    private Fiber last;
    private int size;

    /**
     * Schedules a given fiber adding it to the queue
     *
     * @param fiber a fiber to be scheduled
     */
    public void schedule(Fiber fiber) {
        if (last == null) {
            last = fiber;
            current = last;
        } else {
            last.next = fiber;
            last = fiber;
        }
        fiber.scheduler = this;
        size++;
    }

    /**
     * Gets a queue size
     *
     * @return the number of schedules fibers
     */
    public int getSize() {
        return size;
    }

    /**
     * Starts the execution of the scheduled fibers
     */
    public void run() {
        while (!interrupted) {
            while (current != null) {
                int newState = current.update();
                current.setState(newState);
                if (newState != -1) {
                    last.next = current;
                    last = current;
                    current = current.next;
                    last.next = null;
                } else {
                    current.scheduler = null;
                    size--;
                    if (current.next == null && current.exception != null) {
                        throw new FiberExecutionException("Unhandled fiber exception", current.getException());
                    }
                    current = current.next;
                }
            }
            last = null;
        }
    }

    /**
     * Interrupts the manager execution
     */
    public void stop() {
        interrupted = true;
    }
}
