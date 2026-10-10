package com.github.rostmyr.jfibers;

/** User-visible lifecycle; continuation numbers are an instrumentation detail. */
public enum FiberStatus {
    NEW, RUNNABLE, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED, TIMED_OUT;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}
