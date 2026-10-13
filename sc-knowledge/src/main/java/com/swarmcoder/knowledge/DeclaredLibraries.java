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

import com.swarmcoder.domain.LibraryDoc;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The libraries a project's build files declare, and nothing else: the one fact an outside
 * documentation lookup may be built from.
 *
 * <p><b>Why this exists.</b> The documentation server used to be asked about whatever name
 * reached it: the artifact id of one of the project's OWN modules (which a parent build file lists
 * beside real dependencies), or the first word of a question a model wrote. A project called
 * after an everyday word then had that word sent out, and the documentation of an unrelated
 * library of the same name came back and was indexed as if it were the project's.
 *
 * <p>So a library is looked up only when a build file names it as a dependency and it is not a
 * module the build itself makes. That is read from the build files with {@link ManifestParser};
 * no model and no text search has a say in it.
 */
public final class DeclaredLibraries {

    /** How far below the root a module's build file is looked for. */
    private static final int MAX_DEPTH = 4;

    /** One declared dependency. {@code groupId} is empty for a build system that has none. */
    public record Library(String groupId, String artifactId, String version) {

        /** {@code group:artifact}, or the bare name when there is no group. */
        public String coordinate() {
            return groupId.isEmpty() ? artifactId : groupId + ":" + artifactId;
        }
    }

    /** One entry of a documentation server's answer to "which library is this?". */
    public record Candidate(String title, String id) {
    }

    private final Map<String, Library> byCoordinate = new LinkedHashMap<>();

    private DeclaredLibraries() {
    }

    /** Nothing declared: every lookup is refused. */
    public static DeclaredLibraries none() {
        return new DeclaredLibraries();
    }

    /**
     * Reads every build file of the project: the root's and each module's below it.
     *
     * @param repoRoot the project; null or a folder with no build file declares nothing
     */
    public static DeclaredLibraries of(Path repoRoot) {
        DeclaredLibraries declared = new DeclaredLibraries();
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return declared;
        }
        List<Path> buildDirs = buildDirs(repoRoot);
        Set<String> own = new LinkedHashSet<>();
        for (Path dir : buildDirs) {
            ManifestParser.Coordinate coordinate = ManifestParser.ownCoordinate(dir);
            if (coordinate != null) {
                own.add(key(coordinate.groupId(), coordinate.artifactId()));
            }
        }
        for (Path dir : buildDirs) {
            for (LibraryDoc library : ManifestParser.parse(dir)) {
                String coordinate = library.coordinate() == null ? "" : library.coordinate().strip();
                int colon = coordinate.indexOf(':');
                String groupId = colon < 0 ? "" : coordinate.substring(0, colon);
                String artifactId = colon < 0 ? coordinate : coordinate.substring(colon + 1);
                // An unresolved ${property} is not a name; sending it would send nothing useful.
                if (artifactId.isBlank() || coordinate.contains("${")) {
                    continue;
                }
                String key = key(groupId, artifactId);
                if (own.contains(key)) {
                    continue;
                }
                String version = library.version() == null ? "" : library.version();
                Library known = declared.byCoordinate.get(key);
                if (known == null || (known.version().isBlank() && !version.isBlank())) {
                    declared.byCoordinate.put(key, new Library(groupId, artifactId, version));
                }
            }
        }
        return declared;
    }

    public boolean isEmpty() {
        return byCoordinate.isEmpty();
    }

    public List<Library> all() {
        return List.copyOf(byCoordinate.values());
    }

    /**
     * The declared library with exactly this name: {@code group:artifact}, or the artifact id
     * alone. Letter case aside, the match is exact - a name that merely resembles one is refused.
     */
    public Optional<Library> named(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        Library exact = byCoordinate.get(wanted);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (Library library : byCoordinate.values()) {
            if (library.artifactId().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(library);
            }
        }
        return Optional.empty();
    }

    /** The first declared library a question names outright as one of its words. */
    public Optional<Library> namedIn(String question) {
        if (question == null) {
            return Optional.empty();
        }
        for (String word : question.strip().split("\\s+")) {
            Optional<Library> library = named(word);
            if (library.isPresent()) {
                return library;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a documentation server's candidate can be tied to a declared library by its name.
     *
     * <p>The parts of the declared name are the artifact id and the group id without its first
     * segment (the reversed top-level domain, which names nobody). A candidate is tied when its
     * title, or the last segment of its id, is made only of those parts - compared as whole
     * parts, never as fragments. Anything else is some other library that shares a word.
     */
    public static boolean tied(Library library, Candidate candidate) {
        if (library == null || candidate == null) {
            return false;
        }
        Set<String> parts = new LinkedHashSet<>(partsOf(library.artifactId()));
        Set<String> whole = new LinkedHashSet<>();
        whole.add(joined(library.artifactId()));
        String[] group = library.groupId().split("\\.");
        for (int i = 1; i < group.length; i++) {
            parts.addAll(partsOf(group[i]));
            whole.add(joined(group[i]));
        }
        whole.remove("");
        String id = candidate.id() == null ? "" : candidate.id();
        String last = id.substring(id.lastIndexOf('/') + 1);
        for (String name : new String[] {candidate.title(), last}) {
            List<String> named = partsOf(name);
            if (named.isEmpty()) {
                continue;
            }
            if (parts.containsAll(named) || whole.contains(joined(name))) {
                return true;
            }
        }
        return false;
    }

    /** The candidates of a server's answer that are tied to the library, in the server's order. */
    public static List<Candidate> tiedTo(Library library, List<Candidate> candidates) {
        List<Candidate> tied = new ArrayList<>();
        for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
            if (tied(library, candidate)) {
                tied.add(candidate);
            }
        }
        return tied;
    }

    private static List<String> partsOf(String name) {
        List<String> parts = new ArrayList<>();
        if (name == null) {
            return parts;
        }
        for (String part : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String joined(String name) {
        return String.join("", partsOf(name));
    }

    private static String key(String groupId, String artifactId) {
        String group = groupId == null ? "" : groupId.strip();
        String artifact = artifactId == null ? "" : artifactId.strip();
        return (group.isEmpty() ? artifact : group + ":" + artifact).toLowerCase(Locale.ROOT);
    }

    /** The root and every folder below it, to {@link #MAX_DEPTH}, that holds a build file. */
    private static List<Path> buildDirs(Path repoRoot) {
        List<Path> dirs = new ArrayList<>();
        try {
            Files.walkFileTree(repoRoot, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    // Build output and installed packages hold other projects' build files.
                    if (!dir.equals(repoRoot) && (name.startsWith(".") || name.equals("target")
                        || name.equals("node_modules") || name.equals("build"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (Files.isRegularFile(dir.resolve("pom.xml"))
                        || Files.isRegularFile(dir.resolve("package.json"))) {
                        dirs.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // an unreadable tree declares nothing more than was read
        }
        return dirs;
    }
}
