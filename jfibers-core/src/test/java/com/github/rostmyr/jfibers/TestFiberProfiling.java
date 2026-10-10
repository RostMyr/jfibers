package com.github.rostmyr.jfibers;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class TestFiberProfiling {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 30000)
    public void shouldRecordCountersAndWaitPhasesOnlyWhenEnabled() throws Exception {
        List<RecordedEvent> events = record(true);
        List<RecordedEvent> schedulers = events.stream()
            .filter(event -> event.getEventType().getName().endsWith(".Scheduler")).toList();
        RecordedEvent burst = schedulers.stream().filter(event -> event.getLong("completionStageWaits") == 64)
            .findFirst().orElseThrow();
        assertThat(burst.getBoolean("finalSnapshot")).isTrue();
        assertThat(burst.getLong("executions")).isEqualTo(129);
        assertThat(burst.getLong("callbackSignals")).isEqualTo(64);
        assertThat(burst.getLong("wakeRequests")).isEqualTo(64);
        assertThat(burst.getLong("parks")).isZero();
        assertThat(burst.getInt("pendingFibers")).isZero();
        assertThat(burst.getInt("queuedFibers")).isZero();
        assertThat(burst.getInt("peakQueuedFibers")).isEqualTo(65);
        assertThat(burst.getLong("waitSamplesRecorded")).isEqualTo(64);
        assertThat(schedulers.stream().anyMatch(event -> event.getLong("waitSamplesAbandoned") > 0)).isTrue();
        List<RecordedEvent> waits = events.stream()
            .filter(event -> event.getEventType().getName().endsWith(".Await")).toList();
        assertThat(waits).hasSize(68);
        assertThat(waits.stream().map(event -> event.getString("waitKind")).distinct().toList())
            .containsExactlyInAnyOrder("completionStage", "child", "poll");
        assertThat(waits.stream().anyMatch(event -> "cancelled".equals(event.getString("outcome")))).isTrue();
        for (RecordedEvent event : waits) {
            assertThat(event.getStackTrace()).isNull();
            assertThat(event.getString("fiberClass")).startsWith(FiberProfileProbe.class.getName());
            assertThat(event.getDuration("observedWaitNanos").toNanos()).isNotNegative();
            assertThat(event.getDuration("signalToRunnableNanos").toNanos()).isNotNegative();
            assertThat(event.getDuration("runnableToExecutionNanos").toNanos()).isNotNegative();
            assertThat(event.getDuration("readyToExecutionNanos"))
                .isEqualTo(event.getDuration("signalToRunnableNanos").plus(event.getDuration("runnableToExecutionNanos")));
        }
    }

    @Test(timeout = 30000)
    public void shouldLeaveFiberEventsAbsentByDefault() throws Exception {
        assertThat(record(false)).isEmpty();
    }

    private List<RecordedEvent> record(boolean enabled) throws Exception {
        Path recording = temporary.getRoot().toPath().resolve("fiber-" + enabled + ".jfr");
        Path log = temporary.getRoot().toPath().resolve("probe-" + enabled + ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djfibers.jfr=" + enabled, "-Djfibers.jfr.sampleEvery=1", "-Xverify:all", "-cp",
            System.getProperty("java.class.path"), FiberProfileProbe.class.getName(), recording.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("Probe timed out; log: %s", log).isTrue();
            assertThat(process.exitValue()).as("Probe log: %s", java.nio.file.Files.readString(log)).isZero();
            return RecordingFile.readAllEvents(recording).stream()
                .filter(event -> event.getEventType().getName().startsWith("com.github.rostmyr.jfibers.")).toList();
        } finally {
            process.destroyForcibly();
        }
    }
}
