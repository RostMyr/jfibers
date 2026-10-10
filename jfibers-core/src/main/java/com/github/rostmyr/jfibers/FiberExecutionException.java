package com.github.rostmyr.jfibers;

/** Compatibility wrapper for a non-Exception failure; Fiber.getFailure() returns its original cause. */
public final class FiberExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    FiberExecutionException(Throwable cause) {
        super("Fiber failed with " + cause.getClass().getName(), cause);
    }
}
