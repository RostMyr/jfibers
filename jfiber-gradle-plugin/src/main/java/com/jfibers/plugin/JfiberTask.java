package com.jfibers.plugin;

import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.readAllBytes;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.gradle.api.DefaultTask;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.TaskAction;

import com.github.rostmyr.jfibers.instrumentation.FiberTransformer;
import com.github.rostmyr.jfibers.instrumentation.FiberTransformerResult;


/**
 * @author Andronov Aleksey
 *
 */
public class JfiberTask extends DefaultTask {

	static final Logger log = Logging.getLogger(JfiberTask.class);

	@TaskAction
	public void processFiberSource() {
        String outputDirectory = getProject().getBuildDir().getAbsolutePath() + "/classes/java/main";
        try {
            getFileTreeWalker(outputDirectory)
                .filter(path -> isRegularFile(path))
                .forEach(processCompiledClasses());
        } catch (IOException e) {
            log.error("Error during processing the files", e);
        }
	}

	private Consumer<Path> processCompiledClasses() {
		return path -> {
			try {
				FiberTransformerResult result = FiberTransformer.instrument(readAllBytes(path), false);
				if (result.getMainClass() == null) {
					return;
				}

				for (Map.Entry<String, byte[]> fiber : result.getFibers().entrySet()) {
					Files.write(path.resolveSibling(fiber.getKey() + ".class"), fiber.getValue());
				}
				Files.write(path, result.getMainClass(), StandardOpenOption.TRUNCATE_EXISTING,
						StandardOpenOption.WRITE);
			} catch (Exception e) {
				log.error("Error during processing the file: " + path, e);
			}
		};
	}

	private Stream<Path> getFileTreeWalker(String outputDirectory) throws IOException {
		return Files.walk(Paths.get(outputDirectory), Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS);
	}

}
