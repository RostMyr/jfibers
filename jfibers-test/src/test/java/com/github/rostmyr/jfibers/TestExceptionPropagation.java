package com.github.rostmyr.jfibers;

import org.junit.Ignore;
import org.junit.Test;

import static com.github.rostmyr.jfibers.Fiber.call;
import static com.github.rostmyr.jfibers.Fiber.nothing;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rostyslav Myroshnychenko
 * on 28.06.2018.
 */
@SuppressWarnings("all")
public class TestExceptionPropagation {

    @Test
    public void shouldPropagateUnhandledExceptionToFiberManager() {
        // GIVEN
        FiberManager fiberManager = FiberManagers.current();
        fiberManager.schedule(throwException("Propagate"));

        // WHEN-THEN
        assertThatThrownBy(fiberManager::run)
            .isInstanceOf(FiberExecutionException.class)
            .hasMessage("Unhandled fiber exception")
            .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Ignore
    @Test
    public void shouldNotPropagateHandledExceptionToFiberManager() {
        // GIVEN
        FiberManager fiberManager = FiberManagers.current();
        fiberManager.schedule(handleException());

        // WHEN-THEN
        fiberManager.run();
    }

    private Fiber<Void> throwException(String exceptionMessage) {
        if (exceptionMessage != null) {
            throw new IllegalStateException(exceptionMessage);
        }
        return nothing();
    }

    private Fiber<Void> handleException() {
        Void meh = call(throwException("Meh"));
        return nothing();
    }
}