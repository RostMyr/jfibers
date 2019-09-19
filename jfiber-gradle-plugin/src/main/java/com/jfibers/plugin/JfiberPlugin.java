package com.jfibers.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;

/**
 * @author Andronov Aleksey
 *
 */
public class JfiberPlugin implements Plugin<Project> {

	@Override
	public void apply(Project project) {
		JfiberTask task = project.getTasks().create("jfiber", JfiberTask.class);
		Task compileJava = project.getTasks().getByName("classes");
		task.dependsOn(compileJava);		
	}

}
