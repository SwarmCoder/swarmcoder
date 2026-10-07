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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where everything in the project is, as one short text every planning role's session opens with
 * (2026-10-04, live runs 79 and 80).
 *
 * <p><b>Why.</b> The architect, the planner and the test author each start a story knowing
 * nothing of the repository's shape and find it out with paid calls: 17 folder listings across
 * three roles in those runs, and searches whose only purpose was to learn which folder holds
 * what. Each role did it again in its second session, and every story does it again. The shape
 * of the repository is the same for all of them.
 *
 * <p><b>What it is.</b> Every directory that holds files, with the types and other files in it,
 * read from the working tree with no model. It names; it does not quote: a type's members are
 * {@code public_shape}, a file's text is {@code read_file}, and nothing here stands in for
 * either. In a project too large for the names to fit the reader's room it falls back, for the
 * whole map, to names without kinds, then to counts per directory, then to counts per top-level
 * folder - so a large project gets a coarser map, never a cut-off one.
 *
 * <p><b>Built once, the same for everyone.</b> The text depends only on the files (sorted, no
 * date, no story), so it is byte-for-byte the same for every role and every story until a file
 * is added, removed or changed - which is also what lets a server that caches prompt prefixes
 * reuse it. It is kept per repository and size and rebuilt only when the tree's paths, sizes or
 * modification times differ.
 *
 * <p>{@code -Dswarmcoder.roles.projectMap=false} leaves it out.
 */
public final class ProjectMap {

    /** The map's size at the baseline room; callers scale it by the reader's own room. */
    public static final int MAP_CHARS = 5_000;

    /** A tree with more files than this is described by what was walked, and says so. */
    static final int MAX_FILES = 50_000;

    private static final Set<String> SKIP = Set.of(".git", ".gradle", ".idea", "node_modules",
        ".m2", ".mvn", ".vscode", ".settings");
    /** Build output, skipped only outside a source tree - under src/ these are package names. */
    private static final Set<String> BUILD_OUTPUT = Set.of("target", "build", "out", "bin", "dist");

    private record Kept(String fingerprint, String text) {}

    private static final Map<String, Kept> KEPT = new ConcurrentHashMap<>();

    private ProjectMap() {}

    /** Whether sessions are opened with the map. */
    public static boolean enabled() {
        return !"false".equals(System.getProperty("swarmcoder.roles.projectMap"));
    }

    /**
     * The map of {@code root} in at most {@code maxChars} characters; "" for no tree, an empty
     * tree, or a tree that could not be walked - a session is then opened as it always was.
     */
    public static String of(Path root, int maxChars) {
        return of(root, maxChars, null);
    }

    /**
     * @param curator nullable; when its object graph holds {@code root}, each type is named with
     *                what the graph knows of it - kind, how many members, what it extends or
     *                implements among the project's own types. Without one the kinds are read
     *                from the source text.
     */
    public static String of(Path root, int maxChars, KnowledgeCurator curator) {
        if (root == null || maxChars <= 0 || !Files.isDirectory(root)) {
            return "";
        }
        try {
            Path absolute = root.toAbsolutePath().normalize();
            List<Seen> files = walk(absolute);
            if (files.isEmpty()) {
                return "";
            }
            StringBuilder print = new StringBuilder();
            for (Seen file : files) {
                print.append(file.relative()).append('|').append(file.size()).append('|')
                    .append(file.modified()).append('\n');
            }
            String fingerprint = Integer.toHexString(print.toString().hashCode()) + ":"
                + files.size() + ":" + print.length();
            String key = absolute + "|" + maxChars;
            Kept kept = KEPT.get(key);
            if (kept != null && kept.fingerprint().equals(fingerprint)) {
                return kept.text();
            }
            String text = render(absolute, files, maxChars, curator);
            KEPT.put(key, new Kept(fingerprint, text));
            return text;
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    private record Seen(String relative, long size, long modified) {

        String directory() {
            int cut = relative.lastIndexOf('/');
            return cut < 0 ? "" : relative.substring(0, cut);
        }

        String name() {
            return relative.substring(relative.lastIndexOf('/') + 1);
        }
    }

    private static List<Seen> walk(Path root) throws IOException {
        List<Seen> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString();
                if (SKIP.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (BUILD_OUTPUT.contains(name)) {
                    boolean underSources = false;
                    for (Path part : root.relativize(dir)) {
                        underSources |= part.toString().equals("src");
                    }
                    if (!underSources) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (files.size() >= MAX_FILES) {
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.isRegularFile()) {
                    files.add(new Seen(root.relativize(file).toString().replace('\\', '/'),
                        attrs.size(), attrs.lastModifiedTime().toMillis()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(java.util.Comparator.comparing(Seen::relative));
        return files;
    }

    /** What the object graph says of each top-level type of {@code root}, by file. */
    private static Map<String, String> fromTheGraph(Path root, KnowledgeCurator curator) {
        Map<String, String> notes = new HashMap<>();
        if (curator == null) {
            return notes;
        }
        try {
            String label = null;
            for (KnowledgeCurator.Root known : curator.roots()) {
                if (known.path() != null
                        && known.path().toAbsolutePath().normalize().equals(root)) {
                    label = known.label();
                }
            }
            SemanticIndex index = label == null ? null : curator.semanticIndex();
            if (index == null || !index.available()) {
                return notes;
            }
            for (SemanticIndex.Declared type : index.declaredTypes()) {
                String simple = type.fqn().substring(type.fqn().lastIndexOf('.') + 1);
                if (!type.rootLabel().equals(label) || !type.file().endsWith("/" + simple + ".java")) {
                    continue;
                }
                List<String> parts = new ArrayList<>();
                if (!type.kind().equals("class")) {
                    parts.add(type.kind());
                }
                parts.add(String.valueOf(type.members()));
                String note = String.join(" ", parts);
                if (!type.isA().isEmpty()) {
                    List<String> parents = new ArrayList<>();
                    for (String parent : type.isA()) {
                        parents.add(parent.substring(parent.lastIndexOf('.') + 1));
                    }
                    note += " is-a " + String.join("+", parents);
                }
                notes.put(type.file(), note);
            }
        } catch (Throwable noGraph) {                                      // noqa
            notes.clear();
        }
        return notes;
    }

    private static String render(Path root, List<Seen> files, int maxChars,
                                 KnowledgeCurator curator) {
        // What each source file declares: from the object graph when it holds this tree, else
        // the kind alone, from the source-text index the checks already use.
        Map<String, String> kindOfFile = fromTheGraph(root, curator);
        boolean graph = !kindOfFile.isEmpty();
        if (!graph) {
          try {
            ProjectTypes types = ProjectTypes.of(root);
            for (String fullName : types.fullNames()) {
                Path file = types.fileOf(fullName);
                if (file == null) {
                    continue;
                }
                String simple = fullName.substring(fullName.lastIndexOf('.') + 1);
                if (file.getFileName().toString().equals(simple + ".java")) {
                    kindOfFile.put(root.relativize(file.toAbsolutePath().normalize()).toString()
                        .replace('\\', '/'), types.kindOf(fullName));
                }
            }
          } catch (RuntimeException noIndex) {
            // names without kinds, then
          }
        }
        TreeMap<String, List<Seen>> byDirectory = new TreeMap<>();
        for (Seen file : files) {
            byDirectory.computeIfAbsent(file.directory(), d -> new ArrayList<>()).add(file);
        }
        String head = "PROJECT MAP - where everything in this repository is, generated from its "
            + "files as they are now and the same for every role. Paths are from the repository "
            + "root. It names things; it does not quote them: public_shape <Type> lists a type's "
            + "members, doc_outline <document> a document's sections, read_file <path> reads "
            + "any file whole. Look here before list_files.\n"
            + (graph ? "After a type, from the project's object graph: its kind when it is not a "
                + "class, how many public members it has, and is-a with the project types it "
                + "extends or implements.\n" : "");
        String truncated = files.size() >= MAX_FILES
            ? "(Only the first " + MAX_FILES + " files are in this map.)\n" : "";
        for (int level = 0; level <= 2; level++) {
            StringBuilder sb = new StringBuilder(head).append(truncated);
            for (Map.Entry<String, List<Seen>> entry : byDirectory.entrySet()) {
                sb.append(entry.getKey().isEmpty() ? "(root)" : entry.getKey() + "/").append(": ")
                    .append(level == 2 ? counts(entry.getValue())
                        : names(entry.getValue(), kindOfFile, level == 0))
                    .append('\n');
            }
            if (sb.length() <= maxChars) {
                return sb.toString();
            }
        }
        // Too large even as one line per directory: one line per top-level folder.
        TreeMap<String, List<Seen>> byTop = new TreeMap<>();
        for (Seen file : files) {
            int cut = file.relative().indexOf('/');
            byTop.computeIfAbsent(cut < 0 ? "" : file.relative().substring(0, cut),
                d -> new ArrayList<>()).add(file);
        }
        StringBuilder sb = new StringBuilder(head).append(truncated)
            .append("(This project is too large to list by directory here: ")
            .append(byDirectory.size()).append(" directories. list_files <folder> lists one.)\n");
        for (Map.Entry<String, List<Seen>> entry : byTop.entrySet()) {
            String line = (entry.getKey().isEmpty() ? "(root)" : entry.getKey() + "/") + ": "
                + counts(entry.getValue()) + "\n";
            if (sb.length() + line.length() > maxChars) {
                sb.append("(and more top-level folders; list_files lists them)\n");
                break;
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static String names(List<Seen> files, Map<String, String> kindOfFile,
                                boolean withKinds) {
        List<String> names = new ArrayList<>();
        for (Seen file : files) {
            String name = file.name();
            if (name.endsWith(".java")) {
                String kind = kindOfFile.get(file.relative());
                name = name.substring(0, name.length() - ".java".length());
                // "class" is what a reader assumes; only the other kinds are worth the room.
                if (withKinds && kind != null && !kind.isEmpty() && !kind.equals("class")) {
                    name += " (" + kind + ")";
                }
            } else if (withKinds && DocumentOutline.isDocument(name)) {
                // A document's size, so that reading it whole is a choice made knowing its cost.
                name += " (" + file.size() + " chars)";
            }
            names.add(name);
        }
        return String.join(", ", names);
    }

    private static String counts(List<Seen> files) {
        long java = files.stream().filter(f -> f.name().endsWith(".java")).count();
        long tests = files.stream().filter(f -> f.name().endsWith(".java")
            && ("/" + f.relative()).contains("/src/test/")).count();
        long other = files.size() - java;
        List<String> parts = new ArrayList<>();
        if (java > 0) {
            parts.add(java + " Java type(s)" + (tests > 0 ? ", " + tests + " of them tests" : ""));
        }
        if (other > 0) {
            parts.add(other + " other file(s)");
        }
        return String.join("; ", parts);
    }
}
