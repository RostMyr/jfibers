package com.github.rostmyr.jfibers.maven;

import com.github.rostmyr.jfibers.instrumentation.FiberTransformer;
import com.github.rostmyr.jfibers.instrumentation.FiberTransformerResult;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.file.Files.readAllBytes;

/**
 * Rostyslav Myroshnychenko
 * on 02.06.2018.
 */
@Mojo(
    name = "process-fiber-sources",
    threadSafe = true,
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.TEST
)
public class FiberSourceMogo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Set this to "true" to skip plugin execution.
     */
    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skip fibers processing.");
            return;
        }

        Path outputDirectory = Paths.get(project.getBuild().getOutputDirectory());
        getLog().debug("Output directory: " + outputDirectory);
        if (!Files.isDirectory(outputDirectory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(outputDirectory)) {
            // Collect first so that generated classes are not visited during this execution.
            for (Path path : paths.filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".class")).toList()) {
                processCompiledClass(path);
            }
        } catch (IOException | UncheckedIOException e) {
            throw new MojoExecutionException("Error during processing the files in " + outputDirectory, e);
        }
    }

    private void processCompiledClass(Path path) throws MojoExecutionException {
        try {
            FiberTransformerResult result = FiberTransformer.instrument(readAllBytes(path), false);
            if (result.getMainClass() == null) {
                return;
            }

            for (Map.Entry<String, byte[]> fiber : result.getFibers().entrySet()) {
                Files.write(path.resolveSibling(fiber.getKey() + ".class"), fiber.getValue());
            }
            Files.write(path, result.getMainClass(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("Error during processing the file: " + path, e);
        }
    }
}
