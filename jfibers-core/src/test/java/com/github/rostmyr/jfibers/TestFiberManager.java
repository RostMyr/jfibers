package com.github.rostmyr.jfibers;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestFiberManager {
    @Test(timeout = 2000)
    public void shouldReturnWhenTheQueueIsEmpty() {
        FiberManager manager = new FiberManager();
        manager.run();
        Fiber<Integer> fiber = completedOnUpdate(42);
        manager.schedule(fiber);
        manager.run();

        assertThat(fiber.isReady()).isTrue();
        assertThat(fiber.getResult()).isEqualTo(42);
        assertThat(manager.getSize()).isZero();
        manager.schedule(completedOnUpdate(7));
        manager.run();
        assertThat(manager.getSize()).isZero();
    }

    @Test(timeout = 2000)
    public void shouldStopBeforeUpdatingTheNextFiber() {
        FiberManager manager = new FiberManager();
        AtomicInteger nextUpdates = new AtomicInteger();
        Fiber<Void> waiting = new Fiber<>() {
            @Override
            public int update() {
                manager.stop();
                return 0;
            }
        };
        manager.schedule(waiting);
        manager.schedule(new Fiber<Void>() {
            @Override
            public int update() {
                nextUpdates.incrementAndGet();
                return -1;
            }
        });
        manager.run();

        assertThat(nextUpdates).hasValue(0);
        assertThat(waiting.isReady()).isFalse();
        assertThat(manager.getSize()).isEqualTo(2);
    }

    @Test
    public void shouldStopFromAnotherThread() throws Exception {
        FiberManager manager = new FiberManager();
        CountDownLatch updating = new CountDownLatch(1);
        manager.schedule(new Fiber<Void>() {
            @Override
            public int update() {
                updating.countDown();
                return 0;
            }
        });
        Thread runner = new Thread(manager::run);
        runner.setDaemon(true);
        try {
            runner.start();
            assertThat(updating.await(2, TimeUnit.SECONDS)).isTrue();
            manager.stop();
            runner.join(2000);
            assertThat(runner.isAlive()).isFalse();
        } finally {
            manager.stop();
            runner.interrupt();
        }
    }

    @Test(timeout = 2000)
    public void shouldContinueAfterAnUpdateThrows() {
        FiberManager manager = new FiberManager();
        IllegalStateException failure = new IllegalStateException("broken fiber");
        Fiber<Void> broken = new Fiber<>() {
            @Override
            public int update() {
                throw failure;
            }
        };
        Fiber<Integer> healthy = completedOnUpdate(42);
        manager.schedule(broken);
        manager.schedule(healthy);
        manager.run();

        assertThat(broken.isReady()).isTrue();
        assertThat(broken.getException()).isSameAs(failure);
        assertThat(healthy.getResult()).isEqualTo(42);
        assertThat(manager.getSize()).isZero();
    }

    @Test
    public void shouldRejectDuplicateScheduling() {
        FiberManager manager = new FiberManager();
        Fiber<Integer> fiber = completedOnUpdate(42);
        manager.schedule(fiber);

        assertThatThrownBy(() -> manager.schedule(fiber)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FiberManager().schedule(fiber)).isInstanceOf(IllegalStateException.class);
        assertThat(manager.getSize()).isEqualTo(1);
        manager.run();
        assertThat(manager.getSize()).isZero();
        assertThatThrownBy(() -> manager.schedule(fiber)).isInstanceOf(IllegalStateException.class);
    }

    private Fiber<Integer> completedOnUpdate(int value) {
        return new Fiber<>() {
            @Override
            public int update() {
                return resultLiteral(value);
            }
        };
    }
}
