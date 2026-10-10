package com.github.rostmyr.jfibers.instrumentation;

/** Public so isolated fixture class loaders can use the same resource. */
public final class TestResource implements AutoCloseable {
    private final StringBuilder events;
    private final boolean fail;

    public TestResource(StringBuilder events, boolean fail) {
        this.events = events;
        this.fail = fail;
    }

    @Override
    public void close() {
        events.append("close;");
        if (fail) {
            throw new IllegalStateException("close failure");
        }
    }
}
