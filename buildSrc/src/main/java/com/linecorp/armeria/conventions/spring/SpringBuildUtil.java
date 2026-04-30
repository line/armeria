/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.conventions.spring;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.plugins.quality.Checkstyle;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.jvm.tasks.Jar;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.jspecify.annotations.Nullable;

import net.ltgt.gradle.errorprone.ErrorProneOptions;

import groovy.lang.Closure;

public final class SpringBuildUtil {

    private static final Pattern SPLIT_PATTERN = Pattern.compile("[^A-Za-z0-9]+");

    private SpringBuildUtil() {
    }

    private static String toCamelId(String... parts) {
        final StringBuilder sb = new StringBuilder();
        for (String raw : parts) {
            final String[] tokens = SPLIT_PATTERN.split(raw);
            for (String p : tokens) {
                if (p.isEmpty()) {
                    continue;
                }
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) {
                    sb.append(p.substring(1));
                }
            }
        }
        final String base = sb.toString();
        if (base.isEmpty()) {
            throw new IllegalArgumentException("Cannot build camel id from empty input");
        }
        // lower-camel
        return Character.toLowerCase(base.charAt(0)) + base.substring(1);
    }

    enum SourceSetType {
        TEST(SourceSet.TEST_SOURCE_SET_NAME, "compileTestJava", "processTestResources"),
        MAIN(SourceSet.MAIN_SOURCE_SET_NAME, "compileJava", "processResources");

        private final String sourceName;
        private final String compileTaskName;
        private final String processResourcesTaskName;
        SourceSetType(String sourceName, String compileTaskName, String processResourcesTaskName) {
            this.sourceName = sourceName;
            this.compileTaskName = compileTaskName;
            this.processResourcesTaskName = processResourcesTaskName;
        }
    }

    private static Set<File> sourceDirs(Project project, SourceSetType sourceSetType) {
        final SourceSetContainer sourceSets =
                project.getExtensions().findByType(SourceSetContainer.class);
        assert sourceSets != null : "Couldn't find source set for " + project.getName();
        final SourceSet sourceTest = sourceSets.getByName(sourceSetType.sourceName);
        final Set<File> sourceDirs = new HashSet<>(sourceTest.getAllJava().getSrcDirs());
        final String[] additionalSourceDirs;
        if (sourceSetType == SourceSetType.TEST) {
            additionalSourceDirs = new String[] {
                    "build/generated/sources/proto/test/grpc",
                    "build/generated/sources/proto/test/java",
                    "gen-src/test/java",
                    "gen-src/test/javaThrift"
            };
        } else {
            assert sourceSetType == SourceSetType.MAIN;
            additionalSourceDirs = new String[] {
                    "build/generated/sources/proto/main/grpc",
                    "build/generated/sources/proto/main/java",
                    "gen-src/main/java",
                    "gen-src/main/javaThrift"
            };
        }
        for (String path : additionalSourceDirs) {
            final File f = new File(project.getProjectDir(), path);
            sourceDirs.add(f);
        }
        return sourceDirs;
    }

    private static Set<File> resourceDirs(Project project, SourceSetType sourceSetType) {
        final SourceSetContainer sourceSets =
                project.getExtensions().findByType(SourceSetContainer.class);
        assert sourceSets != null : "Couldn't find source set for " + project.getName();
        final SourceSet sourceSet = sourceSets.getByName(sourceSetType.sourceName);
        return sourceSet.getResources().getSrcDirs();
    }

    public static TaskProvider<Copy> copyTestSources(
            Project fromProject,
            Project toProject,
            @Nullable Closure<? extends Copy> extraConfig) {
        final TaskProvider<Copy> copyTask = copySourceTask(
                fromProject, toProject, extraConfig, SourceSetType.TEST);
        return copyTask;
    }

    private static TaskProvider<Copy> copySourceTask(
            Project fromProject, Project toProject,
            @Nullable Closure<? extends Copy> extraConfig, SourceSetType sourceSetType) {
        final String taskName = toCamelId("copy", sourceSetType.sourceName,
                                          fromProject.getName(), "to", toProject.getName());
        final String outputRootName = "gen-src/" + sourceSetType.sourceName;
        final Directory outputRootDir =
                toProject.getLayout().getProjectDirectory().dir(outputRootName);
        final Directory outputJavaDirProvider = outputRootDir.dir("java");
        final Directory outputResourcesDirProvider = outputRootDir.dir("resources");

        final TaskProvider<Copy> copyTask = toProject.getTasks().register(taskName, Copy.class, t -> {
            t.into(outputRootDir);
            t.from(sourceDirs(fromProject, sourceSetType), spec -> {
                spec.into("java");
            });
            t.from(resourceDirs(fromProject, sourceSetType), spec -> {
                spec.into("resources");
            });

            if (extraConfig != null) {
                extraConfig.setDelegate(t);
                extraConfig.setResolveStrategy(Closure.DELEGATE_FIRST);
                extraConfig.call(t);
            }
        });
        copyTask.configure(t -> {
            t.dependsOn(fromProject.getTasks().named(sourceSetType.compileTaskName));
            t.dependsOn(fromProject.getTasks().named(sourceSetType.processResourcesTaskName));
        });
        toProject.getTasks()
                 .named(sourceSetType.compileTaskName, JavaCompile.class)
                 .configure(it -> it.dependsOn(copyTask));
        toProject.getTasks()
                 .named(sourceSetType.processResourcesTaskName, ProcessResources.class)
                 .configure(it -> it.dependsOn(copyTask));

        final SourceSetContainer targetSourceSets =
                toProject.getExtensions().findByType(SourceSetContainer.class);
        assert targetSourceSets != null : "Couldn't find source set for " + toProject.getName();
        final SourceSet targetSourceSet = targetSourceSets.getByName(sourceSetType.sourceName);
        targetSourceSet.getJava().srcDir(outputJavaDirProvider);
        targetSourceSet.getResources().srcDir(outputResourcesDirProvider);
        return copyTask;
    }

    public static void useTestSources(
            Project fromProject,
            Project toProject,
            @Nullable Closure<?> extraConfig) {
        useSources(fromProject, toProject, extraConfig, SourceSetType.TEST);
        useFromProjectResources(fromProject, toProject, SourceSetType.TEST);
    }

    public static void useTestSources(Project fromProject, Project toProject) {
        useTestSources(fromProject, toProject, null);
    }

    private static void useFromProjectResources(Project fromProject, Project toProject, SourceSetType sourceSetType) {
        final SourceSetContainer targetSourceSets =
                toProject.getExtensions().findByType(SourceSetContainer.class);
        assert targetSourceSets != null : "Couldn't find source set for " + toProject.getName();
        final SourceSet target = targetSourceSets.getByName(sourceSetType.sourceName);

        final Set<File> resourceDirs = resourceDirs(fromProject, sourceSetType);
        for (final File resDir : resourceDirs) {
            target.getResources().srcDir(resDir);
        }
        toProject.getTasks().named(sourceSetType.processResourcesTaskName).configure(it -> {
            it.dependsOn(fromProject.getTasks().named(sourceSetType.processResourcesTaskName));
        });
    }

    public static void useMainSources(
            Project fromProject,
            Project toProject,
            @Nullable Closure<?> extraConfig) {

        useSources(fromProject, toProject, extraConfig, SourceSetType.MAIN);
        useMainJavadoc(fromProject, toProject, extraConfig);
        toProject.getTasks().named("sourcesJar").configure(it -> {
            it.dependsOn(fromProject.getTasks().named("sourcesJar"));
        });
        useFromProjectResources(fromProject, toProject, SourceSetType.MAIN);

        disableErrorProne(toProject, SourceSetType.MAIN);
        disableCheckstyle(toProject);
    }

    public static void useMainSources(Project fromProject, Project toProject) {
        useMainSources(fromProject, toProject, null);
    }

    private static void useSources(Project fromProject, Project toProject,
                                   @Nullable Closure<?> extraConfig,
                                   SourceSetType sourceSetType) {
        final Set<File> sourceDirs = sourceDirs(fromProject, sourceSetType);

        toProject.getTasks()
                 .named(sourceSetType.compileTaskName, JavaCompile.class)
                 .configure(compileJava -> {
                     for (final File sourceDir : sourceDirs) {
                         compileJava.source(
                                 fromProject.fileTree(sourceDir, ft -> {
                                     if (extraConfig != null) {
                                         extraConfig.setDelegate(ft);
                                         extraConfig.setResolveStrategy(Closure.DELEGATE_FIRST);
                                         extraConfig.call(ft);
                                     }
                                 })
                         );
                     }
                     compileJava.dependsOn(fromProject.getTasks().named(sourceSetType.compileTaskName));
                 });
    }

    private static void useMainJavadoc(
            final Project fromProject,
            final Project toProject,
            @Nullable final Closure<?> extraConfig) {

        final Set<File> sourceDirs = sourceDirs(fromProject, SourceSetType.MAIN);

        toProject.getTasks()
                 .named("javadoc", Javadoc.class)
                 .configure(javadoc -> {
                     for (final File sourceDir : sourceDirs) {
                         javadoc.source(
                                 fromProject.fileTree(sourceDir, ft -> {
                                     if (extraConfig != null) {
                                         extraConfig.setDelegate(ft);
                                         extraConfig.setResolveStrategy(Closure.DELEGATE_FIRST);
                                         extraConfig.call(ft);
                                     }
                                 })
                         );
                     }
                 });
        toProject.getTasks()
                 .named("sourcesJar", Jar.class)
                 .configure(it -> {
                     for (final File sourceDir : sourceDirs) {
                         it.from(fromProject.fileTree(sourceDir, ft -> {
                                     if (extraConfig != null) {
                                         extraConfig.setDelegate(ft);
                                         extraConfig.setResolveStrategy(Closure.DELEGATE_FIRST);
                                         extraConfig.call(ft);
                                     }
                                 })
                         );
                     }
                 });
    }

    public static TaskProvider<Copy> copyMainSources(
            Project fromProject,
            Project toProject,
            @Nullable Closure<? extends Copy> extraConfig) {

        final TaskProvider<Copy> copyTask = copySourceTask(
                fromProject, toProject, extraConfig, SourceSetType.MAIN);
        toProject.getTasks().named("sourcesJar").configure(it -> {
            it.dependsOn(fromProject.getTasks().named("sourcesJar"));
            it.dependsOn(copyTask);
        });
        return copyTask;
    }

    public static TaskProvider<Copy> copyMainSources(
            Project fromProject,
            Project toProject) {
        return copyMainSources(fromProject, toProject, null);
    }

    private static void disableErrorProne(final Project project, SourceSetType sourceSetType) {
        project.getTasks()
               .named(sourceSetType.compileTaskName, JavaCompile.class)
               .configure(compile -> {
                   final ExtensionContainer extensions =
                           ((ExtensionAware) compile.getOptions()).getExtensions();
                   final ErrorProneOptions errorprone =
                           extensions.findByType(ErrorProneOptions.class);
                   if (errorprone != null) {
                       errorprone.getEnabled().set(false);
                   }
               });
    }

    private static void disableCheckstyle(Project project) {
        final TaskCollection<Checkstyle> checkstyleTasks =
                project.getTasks().withType(Checkstyle.class);
        checkstyleTasks.configureEach(task -> {
            task.setEnabled(false);
        });
    }
}
