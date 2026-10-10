package com.github.rostmyr.jfibers;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;
import jdk.jfr.Timespan;

import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in diagnostics. The static gate lets the JIT remove hooks in ordinary runs. */
final class FiberProfiler {
    static final boolean ENABLED = Boolean.getBoolean("jfibers.jfr");
    private static final int SAMPLE_EVERY = Math.max(1, Integer.getInteger("jfibers.jfr.sampleEvery", 256));
    private static final AtomicLong NEXT_RUN = new AtomicLong();
    private final long runId = NEXT_RUN.incrementAndGet();
    private final long started = System.nanoTime();
    private final OwnerCounters owner = new OwnerCounters(started);
    private final LongAdder callbackSignals = new LongAdder();
    private final LongAdder wakeRequests = new LongAdder();
    private final LongAdder unparkCalls = new LongAdder();
    private final ConcurrentHashMap<FiberWait, AwaitEvent> waitingSamples = new ConcurrentHashMap<>();
    private final IdentityHashMap<Fiber<?>, AwaitEvent> readySamples = new IdentityHashMap<>();

    FiberProfiler(Fiber<?> firstReady) {
        for (Fiber<?> fiber = firstReady; fiber != null; fiber = fiber.next) {
            owner.queued++;
        }
        owner.peakQueued = owner.queued;
    }

    void enqueue() {
        owner.peakQueued = Math.max(owner.peakQueued, ++owner.queued);
    }

    void dequeue() {
        owner.queued--;
    }

    long beforeExecute(Fiber<?> fiber) {
        long now = System.nanoTime();
        AwaitEvent event = readySamples.remove(fiber);
        if (event != null) {
            event.end();
            if (event.shouldCommit()) {
                event.observedWaitNanos = Math.max(0, event.readyAt - event.startedAt);
                event.signalToRunnableNanos = Math.max(0, event.enqueuedAt - event.readyAt);
                event.runnableToExecutionNanos = Math.max(0, now - event.enqueuedAt);
                event.readyToExecutionNanos = Math.max(0, now - event.readyAt);
                event.commit();
                owner.samplesRecorded++;
            }
        }
        owner.executions++;
        return now;
    }

    void afterExecute(long start, int pending) {
        long now = System.nanoTime();
        owner.executionNanos += now - start;
        snapshot(now, pending, false);
    }

    long beforePark() {
        owner.parks++;
        return System.nanoTime();
    }

    void afterPark(long start, int pending) {
        long now = System.nanoTime();
        owner.parkedNanos += now - start;
        snapshot(now, pending, false);
    }

    void waiting(FiberWait wait, String kind) {
        switch (kind) {
            case "completionStage" -> owner.completionStageWaits++;
            case "child" -> owner.childWaits++;
            case "poll" -> owner.polledWaits++;
            default -> throw new IllegalArgumentException("Unknown wait kind: " + kind);
        }
        long sequence = owner.completionStageWaits + owner.childWaits + owner.polledWaits;
        if ((sequence - 1) % SAMPLE_EVERY != 0 || !Types.AWAIT.isEnabled()) {
            return;
        }
        AwaitEvent event = new AwaitEvent();
        event.runId = runId;
        event.fiberClass = wait.fiber.getClass().getName();
        event.continuation = wait.fiber.state;
        event.waitKind = kind;
        event.startedAt = System.nanoTime();
        event.begin();
        waitingSamples.put(wait, event);
    }

    void signal(FiberWait wait) {
        callbackSignals.increment();
        AwaitEvent event = waitingSamples.get(wait);
        if (event != null) {
            event.signalledAt = System.nanoTime();
        }
    }

    void wakeRequested() {
        wakeRequests.increment();
    }

    void unparked() {
        unparkCalls.increment();
    }

    void staleSignal() {
        owner.staleSignals++;
    }

    void resumed(FiberWait wait, Fiber<?> fiber, boolean cancelled) {
        AwaitEvent event = waitingSamples.remove(wait);
        if (event != null) {
            event.enqueuedAt = System.nanoTime();
            event.readyAt = event.signalledAt;
            if (cancelled || event.readyAt == 0) {
                // For children/polled Future, readiness can only be observed on the owner thread.
                event.readyAt = event.enqueuedAt;
            }
            event.outcome = cancelled ? "cancelled" : "ready";
            readySamples.put(fiber, event);
        }
    }

    void detached(FiberWait wait) {
        if (waitingSamples.remove(wait) != null) {
            owner.samplesAbandoned++;
        }
    }

    void finish(int pending) {
        owner.samplesAbandoned += waitingSamples.size() + readySamples.size();
        waitingSamples.clear();
        readySamples.clear();
        snapshot(System.nanoTime(), pending, true);
    }

    private void snapshot(long now, int pending, boolean finished) {
        if (!finished && now - owner.lastSnapshot < 1_000_000_000L) {
            return;
        }
        owner.lastSnapshot = now;
        if (!Types.SCHEDULER.isEnabled()) {
            return;
        }
        SchedulerEvent event = new SchedulerEvent();
        event.runId = runId;
        event.elapsedNanos = now - started;
        event.executionNanos = owner.executionNanos;
        event.parkedNanos = owner.parkedNanos;
        event.executions = owner.executions;
        event.parks = owner.parks;
        event.callbackSignals = callbackSignals.sum();
        event.wakeRequests = wakeRequests.sum();
        event.unparkCalls = unparkCalls.sum();
        event.completionStageWaits = owner.completionStageWaits;
        event.childWaits = owner.childWaits;
        event.polledWaits = owner.polledWaits;
        event.staleSignals = owner.staleSignals;
        event.queuedFibers = owner.queued;
        event.peakQueuedFibers = owner.peakQueued;
        event.pendingFibers = pending;
        event.waitSamplesRecorded = owner.samplesRecorded;
        event.waitSamplesAbandoned = owner.samplesAbandoned;
        event.sampleEvery = SAMPLE_EVERY;
        event.finalSnapshot = finished;
        event.commit();
    }

    /** Only the run() owner accesses these fields; callbacks use the adders and concurrent map above. */
    private static final class OwnerCounters {
        private long lastSnapshot;
        private long executions;
        private long executionNanos;
        private long parks;
        private long parkedNanos;
        private long completionStageWaits;
        private long childWaits;
        private long polledWaits;
        private long staleSignals;
        private long samplesRecorded;
        private long samplesAbandoned;
        private int queued;
        private int peakQueued;

        private OwnerCounters(long started) {
            lastSnapshot = started;
        }
    }

    // Lazy registration: no JFR initialization is needed with -Djfibers.jfr=false (the default).
    private static final class Types {
        private static final EventType SCHEDULER = EventType.getEventType(SchedulerEvent.class);
        private static final EventType AWAIT = EventType.getEventType(AwaitEvent.class);
    }

    @Name("com.github.rostmyr.jfibers.Scheduler")
    @Label("Fiber scheduler counters")
    @Category("jfibers")
    @StackTrace(false)
    static final class SchedulerEvent extends Event {
        public long runId;
        @Timespan(Timespan.NANOSECONDS)
        public long elapsedNanos;
        @Label("Execution wall time (not CPU time)")
        @Timespan(Timespan.NANOSECONDS)
        public long executionNanos;
        @Timespan(Timespan.NANOSECONDS)
        public long parkedNanos;
        public long executions;
        public long parks;
        public long callbackSignals;
        public long wakeRequests;
        public long unparkCalls;
        public long completionStageWaits;
        public long childWaits;
        public long polledWaits;
        public long staleSignals;
        public int queuedFibers;
        public int peakQueuedFibers;
        public int pendingFibers;
        public long waitSamplesRecorded;
        public long waitSamplesAbandoned;
        public int sampleEvery;
        public boolean finalSnapshot;
    }

    @Name("com.github.rostmyr.jfibers.Await")
    @Label("Fiber wait and scheduling delay (sampled)")
    @Category("jfibers")
    @StackTrace(false)
    @Threshold("1 ms")
    static final class AwaitEvent extends Event {
        public long runId;
        public String fiberClass;
        public int continuation;
        public String waitKind;
        public String outcome;
        @Timespan(Timespan.NANOSECONDS)
        public long observedWaitNanos;
        @Timespan(Timespan.NANOSECONDS)
        public long signalToRunnableNanos;
        @Timespan(Timespan.NANOSECONDS)
        public long runnableToExecutionNanos;
        @Timespan(Timespan.NANOSECONDS)
        public long readyToExecutionNanos;
        private transient long startedAt;
        private transient volatile long signalledAt;
        private transient long readyAt;
        private transient long enqueuedAt;
    }
}
