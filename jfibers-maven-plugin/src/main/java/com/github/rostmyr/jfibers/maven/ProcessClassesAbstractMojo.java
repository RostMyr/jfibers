package com.github.rostmyr.jfibers.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.github.rostmyr.jfibers.instrumentation.FiberTransformer;
import com.github.rostmyr.jfibers.instrumentation.FiberTransformerResult;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.readAllBytes;

/**
 * Rostyslav Myroshnychenko
 * on 28.06.2018.
 */
public abstract class ProcessClassesAbstractMojo extends AbstractMojo  {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * Set this to "true" to skip plugin execution.
     */
    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() {
        if (skip) {
            getLog().info("Skip fibers processing.");
            return;
        }

        String outputDirectory = getOutputDirectory();
        getLog().debug("Output directory: " + outputDirectory);

        try {
            getFileTreeWalker(outputDirectory)
                .filter(path -> isRegularFile(path))
                .forEach(processCompiledClasses());
        } catch (IOException e) {
            getLog().error("Error during processing the files", e);
        }
    }

    protected abstract String getOutputDirectory();

    private Consumer<Path> processCompiledClasses() {
        return path -> {
            try {
                FiberTransformerResult result = FiberTransformer.transform(readAllBytes(path), false);
                if (result.getMainClass() == null) {
                    return;
                }

                for (Map.Entry<String, byte[]> fiber : result.getFibers().entrySet()) {
                    Files.write(path.resolveSibling(fiber.getKey() + ".class"), fiber.getValue());
                }
                Files.write(path, result.getMainClass(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (Exception e) {
                getLog().error("Error during processing the file: " + path, e);
            }
        };
    }

    private Stream<Path> getFileTreeWalker(String outputDirectory) throws IOException {
        return Files.walk(Paths.get(outputDirectory), Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS);
    }
}
