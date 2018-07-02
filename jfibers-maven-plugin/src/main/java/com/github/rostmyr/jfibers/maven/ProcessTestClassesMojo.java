package com.github.rostmyr.jfibers.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Rostyslav Myroshnychenko
 * on 28.06.2018.
 */
@Mojo(
    name = "process-fiber-test-classes",
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    requiresDependencyResolution = ResolutionScope.TEST
)
public class ProcessTestClassesMojo extends ProcessClassesAbstractMojo {

    @Override
    protected String getOutputDirectory() {
        return project.getBuild().getTestOutputDirectory();
    }
}
