package com.github.rostmyr.jfibers.instrumentation;

/** An actionable error in a method containing fiber markers. */
public final class FiberInstrumentationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    FiberInstrumentationException(String message) {
        super(message);
    }

    FiberInstrumentationException(String message, Throwable cause) {
        super(message, cause);
    }
}
