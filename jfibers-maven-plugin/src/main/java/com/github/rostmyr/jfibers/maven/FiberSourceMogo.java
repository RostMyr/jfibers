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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
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

        try (URLClassLoader loader = projectLoader(); Stream<Path> paths = Files.walk(outputDirectory)) {
            Map<Path, byte[]> changes = new LinkedHashMap<>();
            // Collect first so that generated classes are not visited during this execution.
            for (Path path : paths.filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".class")).toList()) {
                processCompiledClass(path, loader, changes);
            }
            // A validation error must leave all original classes intact.
            for (Map.Entry<Path, byte[]> change : changes.entrySet()) {
                Files.write(change.getKey(), change.getValue());
            }
        } catch (IOException | UncheckedIOException e) {
            throw new MojoExecutionException("Error during processing the files in " + outputDirectory, e);
        }
    }

    private URLClassLoader projectLoader() throws MojoExecutionException {
        try {
            var elements = project.getCompileClasspathElements();
            URL[] urls = new URL[elements.size()];
            for (int i = 0; i < urls.length; i++) {
                urls[i] = Paths.get(elements.get(i)).toUri().toURL();
            }
            return new URLClassLoader(urls, getClass().getClassLoader());
        } catch (Exception e) {
            throw new MojoExecutionException("Cannot resolve the project classpath for fiber instrumentation", e);
        }
    }

    private void processCompiledClass(Path path, ClassLoader loader, Map<Path, byte[]> changes) throws MojoExecutionException {
        try {
            FiberTransformerResult result = FiberTransformer.instrument(readAllBytes(path), false, loader);
            if (result.getMainClass() == null) {
                return;
            }

            for (Map.Entry<String, byte[]> fiber : result.getFibers().entrySet()) {
                changes.put(path.resolveSibling(fiber.getKey() + ".class"), fiber.getValue());
            }
            changes.put(path, result.getMainClass());
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("Error during processing the file: " + path + ": " + e.getMessage(), e);
        }
    }
}
