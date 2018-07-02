package com.github.rostmyr.jfibers;

import org.junit.Test;

import static com.github.rostmyr.jfibers.Fiber.nothing;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rostyslav Myroshnychenko
 * on 29.06.2018.
 */
public class TestFiberManager {

    @Test
    public void shouldRunAndStopFiberManager() {
        // GIVEN
        FiberManager fiberManager = FiberManagers.current();

        // WHEN-THEN
        fiberManager.schedule(assertFiberManagerStatus());
        fiberManager.run();

        assertThat(fiberManager.isRunning()).isFalse();
    }

    private Fiber<Void> assertFiberManagerStatus() {
        FiberManager fiberManager = FiberManagers.current();
        assertThat(fiberManager.isRunning()).isTrue();
        fiberManager.stop();
        return nothing();
    }
}
