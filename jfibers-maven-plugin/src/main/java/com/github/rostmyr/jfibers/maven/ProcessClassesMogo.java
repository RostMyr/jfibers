package com.github.rostmyr.jfibers.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Rostyslav Myroshnychenko
 * on 02.06.2018.
 */
@Mojo(
    name = "process-fiber-classes",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.TEST
)
public class ProcessClassesMogo extends ProcessClassesAbstractMojo {

    @Override
    protected String getOutputDirectory() {
        return project.getBuild().getOutputDirectory();
    }
}
