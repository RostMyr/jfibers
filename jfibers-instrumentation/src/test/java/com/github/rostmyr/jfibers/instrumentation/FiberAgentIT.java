package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.instrumentation.fixtures.AgentApplication;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class FiberAgentIT {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldInstrumentTheSystemLoader() throws Exception {
        runAgent("system");
    }

    @Test
    public void shouldDefineFibersInACustomLoader() throws Exception {
        runAgent("custom");
    }

    @Test
    public void shouldHandleParallelCustomLoaders() throws Exception {
        runAgent("parallel");
    }

    private void runAgent(String scenario) throws Exception {
        Path agentJar = Path.of(System.getProperty("jfibers.agentJar"));
        String classes = System.getProperty("jfibers.testClasses");
        Path log = temporaryFolder.newFile().toPath();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = classes + File.pathSeparator + agentJar;
        Process process = new ProcessBuilder(java, "-Xverify:all", "-javaagent:" + agentJar,
            "-cp", classpath, AgentApplication.class.getName(), scenario, classes)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("agent subprocess completed").isTrue();
            String output = Files.readString(log);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("agent scenario passed: " + scenario);
        } finally {
            process.destroyForcibly();
        }
    }
}
