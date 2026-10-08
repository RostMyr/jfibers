package com.github.rostmyr.jfibers.maven;

import com.github.rostmyr.jfibers.Fiber;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestFiberSourceMojo {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldInstrumentClassesAndPreserveResources() throws Exception {
        Path output = temporaryFolder.newFolder().toPath();
        Path resource = output.resolve("application.properties");
        Files.writeString(resource, "message=hello");
        String name = SampleFiber.class.getName();
        try (InputStream bytes = SampleFiber.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            Files.copy(bytes, output.resolve("SampleFiber.class"));
        }

        FiberSourceMogo mojo = mojo(output);
        mojo.execute();
        mojo.execute();

        assertThat(output.resolve("TestFiberSourceMojo$SampleFiber$success_Fiber.class")).exists();
        assertThat(Files.readString(resource)).isEqualTo("message=hello");
    }

    @Test
    public void shouldFailTheBuildForInvalidClassFiles() throws Exception {
        Path output = temporaryFolder.newFolder().toPath();
        Path invalidClass = output.resolve("Invalid.class");
        Files.write(invalidClass, new byte[] {0, 1, 2});

        assertThatThrownBy(() -> mojo(output).execute())
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining(invalidClass.toString());
    }

    @Test
    public void shouldAllowModulesWithoutCompiledClasses() throws Exception {
        mojo(temporaryFolder.getRoot().toPath().resolve("missing")).execute();
    }

    private FiberSourceMogo mojo(Path output) throws Exception {
        MavenProject project = new MavenProject();
        Build build = new Build();
        build.setOutputDirectory(output.toString());
        project.setBuild(build);
        FiberSourceMogo mojo = new FiberSourceMogo();
        Field field = FiberSourceMogo.class.getDeclaredField("project");
        field.setAccessible(true);
        field.set(mojo, project);
        return mojo;
    }

    public static class SampleFiber {
        public Fiber<String> success() {
            return Fiber.result("ok");
        }
    }
}
