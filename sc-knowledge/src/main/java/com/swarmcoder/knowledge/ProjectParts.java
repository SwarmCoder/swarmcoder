/*
 * Copyright 2026 Franz Schöning
 * Project: https://github.com/SwarmCoder/swarmcoder
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.swarmcoder.knowledge;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The parts of a project a rule can be recorded for, read from the project's object graph
 * (section 65): its modules, and every folder the graph holds a type or a build file under.
 *
 * <p>No model and no wording: a folder is a part of the project when the graph has something in
 * it. A project the graph does not hold yet (nothing written, or no index) has no parts, and
 * every rule stated for it is a rule of the whole project.
 */
public final class ProjectParts {

    private final Set<String> modules = new TreeSet<>();
    private final Set<String> folders = new TreeSet<>();

    private ProjectParts() {}

    /** The parts of {@code project} as the curator's graph holds them now. */
    public static ProjectParts of(KnowledgeCurator curator, Path project) {
        ProjectParts parts = new ProjectParts();
        if (curator == null || project == null) {
            return parts;
        }
        try {
            String label = null;
            Path wanted = project.toAbsolutePath().normalize();
            for (KnowledgeCurator.Root root : curator.roots()) {
                if (root.path() != null && root.path().toAbsolutePath().normalize().equals(wanted)) {
                    label = root.label();
                }
            }
            SemanticIndex index = curator.semanticIndex();
            if (label == null || index == null || !index.available()) {
                return parts;
            }
            for (SemanticIndex.Declared type : index.declaredTypes()) {
                if (label.equals(type.rootLabel())) {
                    parts.file(type.file(), "/src/");
                }
            }
            for (SemanticIndex.Ref build : index.buildOf("")) {
                if (label.equals(build.rootLabel())) {
                    parts.file(build.file(), null);
                }
            }
        } catch (Throwable noGraph) {                                      // noqa
            parts.modules.clear();
            parts.folders.clear();
        }
        return parts;
    }

    /**
     * The parts a list of files makes, as paths from the repository root: source files and build
     * files ({@code pom.xml}, {@code build.gradle}, {@code build.gradle.kts}) - for a caller that
     * already holds the graph's files.
     */
    public static ProjectParts ofFiles(List<String> files) {
        ProjectParts parts = new ProjectParts();
        for (String file : files == null ? List.<String>of() : files) {
            String name = file == null ? "" : file.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            boolean build = name.equals("pom.xml") || name.startsWith("build.gradle");
            parts.file(file, build ? null : "/src/");
        }
        return parts;
    }

    /**
     * Takes one file of the graph: every folder above it is a part, and its module is the folder
     * of a build file, or what stands before the source root of a source file.
     */
    private void file(String file, String sourceRoot) {
        String path = file == null ? "" : file.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String folder = slash < 0 ? "" : path.substring(0, slash);
        for (String up = folder; !up.isEmpty();
                up = up.contains("/") ? up.substring(0, up.lastIndexOf('/')) : "") {
            folders.add(up);
        }
        String module = folder;
        if (sourceRoot != null) {
            int cut = ("/" + path).indexOf(sourceRoot);
            module = cut <= 0 ? "" : path.substring(0, cut - 1);
        }
        if (!module.isEmpty()) {
            modules.add(module);
        }
    }

    /** The module folders, sorted - what whoever states a rule is offered. */
    public List<String> modules() {
        return List.copyOf(modules);
    }

    /** True when the graph holds a type or a build file in this folder or under it. */
    public boolean holds(String folder) {
        return folder != null && folders.contains(folder);
    }
}
