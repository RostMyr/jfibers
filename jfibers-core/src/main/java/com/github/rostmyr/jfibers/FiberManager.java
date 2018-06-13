package com.github.rostmyr.jfibers;

/**
 * Rostyslav Myroshnychenko
 * on 31.05.2018.
 */
public class FiberManager {
    private boolean interrupted;

    private Fiber current;
    private Fiber last;
    private int size;

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

    public int getSize() {
        return size;
    }

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
                    current = current.next;
                }
            }
            last = null;
        }
    }

    public void stop() {
        interrupted = true;
    }
}
