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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The library jars a checkout compiles against, as far as this machine holds them offline: the
 * same list the syntax tree is parsed with ({@link LstReader#classpathFor}), without the
 * checkout's own compiled folders. Read from the build files and the local repository; nothing is
 * run and nothing is downloaded. What the Java language server is given as its libraries.
 */
public final class ProjectClasspath {

    private ProjectClasspath() {}

    /**
     * The jars of a checkout, or - when its own build files name none, as in a project that has
     * no build file yet - the jars the build files of the curator's other roots (the reference
     * material) name (section 113). A project built on a reference library is meant to use that
     * library's dependencies, and a language server given no jar at all answered "no type" for
     * every class of them. Read when a server starts; a server re-reads it when the build files
     * of what it reads change ({@code JdtLanguageServer}).
     */
    public static java.util.function.Function<Path, List<Path>> orTheReferencesOf(
            KnowledgeCurator curator) {
        return checkout -> {
            List<Path> own = jarsOf(checkout);
            if (!own.isEmpty() || curator == null) {
                return own;
            }
            return referenceJars(curator, checkout);
        };
    }

    /** The jars the build files of every root of the curator other than {@code checkout} name. */
    static List<Path> referenceJars(KnowledgeCurator curator, Path checkout) {
        Path self = checkout == null ? null : checkout.toAbsolutePath().normalize();
        java.util.LinkedHashSet<Path> jars = new java.util.LinkedHashSet<>();
        try {
            for (KnowledgeCurator.Root root : curator.roots()) {
                Path path = root.path() == null ? null : root.path().toAbsolutePath().normalize();
                if (path != null && !path.equals(self)) {
                    jars.addAll(jarsOf(path));
                }
            }
        } catch (RuntimeException e) {
            return List.copyOf(jars);
        }
        return List.copyOf(jars);
    }

    /**
     * Where the jars of {@link #orTheReferencesOf} come from for {@code checkout}, as a phrase
     * for an answer that found nothing: the asker is told where the lookup looked.
     */
    public static String whereTheJarsComeFrom(KnowledgeCurator curator, Path checkout) {
        if (!jarsOf(checkout).isEmpty()) {
            return "the jars the project's build files name";
        }
        return curator == null || referenceJars(curator, checkout).isEmpty()
            ? "none: neither the project's build files nor the reference material's name a "
                + "library the offline repository holds"
            : "the jars the reference material's build files name, since no build file of the "
                + "project names a library yet";
    }

    /** Never null and never throws; empty when nothing could be resolved. */
    public static List<Path> jarsOf(Path checkout) {
        if (checkout == null || !Files.isDirectory(checkout)) {
            return List.of();
        }
        try {
            return LstReader.classpathFor(checkout).stream().filter(Files::isRegularFile).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
