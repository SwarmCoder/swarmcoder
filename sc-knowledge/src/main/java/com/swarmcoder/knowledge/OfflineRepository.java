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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * {@code offline_artifacts}: which artifacts, and which versions of them, the offline Maven
 * repository holds - the one a candidate's build resolves from in its container, read on this
 * machine from the same folder ({@link DeclarableArtifacts#defaultLocalRepository()}, section 113).
 *
 * <p>A worker asked this with {@code find ~/.m2}, {@code find /opt/m2-cache} and {@code find /}
 * 65 times in one build, because its rules said "only an artifact your brief lists as available"
 * and a project with no build file yet has nothing to list. The answer is read from the
 * repository's folders: nothing is resolved, downloaded or run.
 *
 * <p>Asked with a group, the artifacts of the group and their versions, and the groups under it;
 * with {@code group:artifact}, that artifact's versions; with an artifact id alone, every group
 * that has an artifact of that id. A version whose folder holds no jar is said to be a pom only.
 */
public final class OfflineRepository {

    /** How many artifact ids are named when a name matches nothing exactly. */
    static final int SIMILAR_NAMED = 40;

    /** What every answer starts with: what was read, without a path of this machine. */
    static final String HEAD = "In the offline Maven repository your build resolves from (the "
        + "build has no network; a version not listed here cannot be resolved):\n";

    private static final long INDEX_KEPT_MILLIS = 10 * 60 * 1000L;

    /** Artifact id to its groups, per repository, read once in a while. */
    private static final Map<Path, Index> INDEXES = new ConcurrentHashMap<>();

    private record Index(long madeAt, Map<String, TreeSet<String>> groupsOfArtifact) {}

    private OfflineRepository() {}

    /** The answer for the repository the containers mount. Never null and never throws. */
    public static String answer(String asked) {
        return answer(DeclarableArtifacts.defaultLocalRepository(), asked);
    }

    static String answer(Path repository, String asked) {
        String what = asked == null ? "" : asked.strip();
        if (repository == null || !Files.isDirectory(repository)) {
            return "There is no offline Maven repository on this machine, so no library can be "
                + "resolved by the build.";
        }
        try {
            if (what.isEmpty()) {
                return HEAD + topGroups(repository);
            }
            String[] parts = what.split(":");
            if (parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                return HEAD + artifact(repository, parts[0].strip(), parts[1].strip(),
                    parts.length >= 3 ? parts[2].strip() : "");
            }
            String one = parts[0].strip();
            Path group = groupDir(repository, one);
            if (group != null && Files.isDirectory(group)) {
                String listed = group(repository, one, group);
                if (!listed.isEmpty()) {
                    return HEAD + listed;
                }
            }
            return HEAD + byArtifactId(repository, one);
        } catch (IOException | RuntimeException unreadable) {
            return "The offline Maven repository could not be read: " + unreadable.getMessage();
        }
    }

    // ------------------------------------------------------------------------------- the parts

    private static String topGroups(Path repository) throws IOException {
        StringBuilder sb = new StringBuilder("Its top-level group folders: ");
        List<String> names = childDirs(repository);
        sb.append(names.isEmpty() ? "(none)" : String.join(", ", names)).append(".\n");
        return sb.append("Ask with a groupId (org.example), a groupId:artifactId, or an artifactId "
            + "alone for its versions.\n").toString();
    }

    /** The artifacts of a group with their versions, then the groups under it; "" for none. */
    private static String group(Path repository, String groupId, Path dir) throws IOException {
        List<String> artifacts = new ArrayList<>();
        List<String> subgroups = new ArrayList<>();
        for (String child : childDirs(dir)) {
            List<String> versions = versionsOf(dir.resolve(child), child);
            if (versions.isEmpty()) {
                subgroups.add(groupId + "." + child);
            } else {
                artifacts.add(groupId + ":" + child + "  " + String.join(", ", versions));
            }
        }
        if (artifacts.isEmpty() && subgroups.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!artifacts.isEmpty()) {
            sb.append("Group ").append(groupId).append(": ").append(artifacts.size())
                .append(" artifact(s), each with the versions held:\n");
            artifacts.forEach(line -> sb.append("  ").append(line).append('\n'));
        }
        if (!subgroups.isEmpty()) {
            sb.append("Groups under ").append(groupId).append(": ")
                .append(String.join(", ", subgroups)).append('\n');
        }
        return sb.toString();
    }

    private static String artifact(Path repository, String groupId, String artifactId,
                                   String version) throws IOException {
        Path group = groupDir(repository, groupId);
        Path dir = group == null ? null : group.resolve(artifactId);
        List<String> versions = dir == null || !Files.isDirectory(dir) ? List.of()
            : versionsOf(dir, artifactId);
        if (versions.isEmpty()) {
            return groupId + ":" + artifactId + " is not there.\n" + byArtifactId(repository,
                artifactId);
        }
        StringBuilder sb = new StringBuilder(groupId + ":" + artifactId + "  "
            + String.join(", ", versions) + "\n");
        if (!version.isEmpty()) {
            boolean held = versions.stream().anyMatch(v -> v.equals(version)
                || v.startsWith(version + " "));
            sb.append("Version ").append(version).append(held ? " is held."
                : " is NOT held; declare one of the versions above.").append('\n');
        }
        return sb.toString();
    }

    /** Every group with an artifact of exactly that id; else the ids that contain it. */
    private static String byArtifactId(Path repository, String artifactId) throws IOException {
        Map<String, TreeSet<String>> index = index(repository);
        TreeSet<String> groups = index.get(artifactId);
        if (groups != null && !groups.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String groupId : groups) {
                sb.append(artifact(repository, groupId, artifactId, ""));
            }
            return sb.toString();
        }
        String lower = artifactId.toLowerCase(Locale.ROOT);
        List<String> similar = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> entry : index.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).contains(lower)) {
                for (String groupId : entry.getValue()) {
                    similar.add(groupId + ":" + entry.getKey());
                }
            }
        }
        if (similar.isEmpty()) {
            return "No group and no artifact `" + artifactId + "` is held. Ask with a groupId to "
                + "see what one group has.\n";
        }
        StringBuilder sb = new StringBuilder("No artifact is called exactly `" + artifactId
            + "`. " + similar.size() + " have it in their id; ask with one for its versions:\n");
        similar.stream().limit(SIMILAR_NAMED).forEach(c -> sb.append("  ").append(c).append('\n'));
        if (similar.size() > SIMILAR_NAMED) {
            sb.append("  ... and ").append(similar.size() - SIMILAR_NAMED)
                .append(" more; a longer part of the name narrows them.\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------- the folders

    private static Path groupDir(Path repository, String groupId) {
        if (groupId.isEmpty() || groupId.contains("/") || groupId.contains("\\")
                || groupId.contains("..")) {
            return null;
        }
        Path dir = repository;
        for (String segment : groupId.split("\\.")) {
            if (segment.isEmpty()) {
                return null;
            }
            dir = dir.resolve(segment);
        }
        return dir.normalize().startsWith(repository.normalize()) ? dir : null;
    }

    /**
     * The versions of the artifact folder {@code dir}, oldest first, each marked when it holds
     * no jar; empty when {@code dir} is not an artifact's folder (it is then a group).
     */
    static List<String> versionsOf(Path dir, String artifactId) throws IOException {
        List<String[]> found = new ArrayList<>();
        for (String version : childDirs(dir)) {
            Path versionDir = dir.resolve(version);
            String stem = artifactId + "-" + version;
            boolean pom = Files.isRegularFile(versionDir.resolve(stem + ".pom"));
            boolean jar = Files.isRegularFile(versionDir.resolve(stem + ".jar"));
            if (!pom && !jar && version.endsWith("-SNAPSHOT")) {
                pom = hasFile(versionDir, artifactId + "-*.pom");
                jar = hasFile(versionDir, artifactId + "-*.jar");
            }
            if (pom || jar) {
                found.add(new String[] {version, jar ? "" : " (pom only)"});
            }
        }
        found.sort(Comparator.comparing((String[] v) -> v[0], OfflineRepository::compareVersions));
        List<String> versions = new ArrayList<>();
        for (String[] v : found) {
            versions.add(v[0] + v[1]);
        }
        return versions;
    }

    private static boolean hasFile(Path dir, String glob) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, glob)) {
            return files.iterator().hasNext();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static List<String> childDirs(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path child : children) {
                names.add(child.getFileName().toString());
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    /** Numbers compared as numbers, the rest as text: 1.10 after 1.9. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-]");
        String[] y = b.split("[.\\-]");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean xn = x[i].matches("\\d{1,9}");
            boolean yn = y[i].matches("\\d{1,9}");
            int c = xn && yn ? Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]))
                : x[i].compareTo(y[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    /** Artifact id to groups, from every {@code .pom} under the repository; kept a while. */
    private static Map<String, TreeSet<String>> index(Path repository) throws IOException {
        Path key = repository.toAbsolutePath().normalize();
        Index kept = INDEXES.get(key);
        long now = System.currentTimeMillis();
        if (kept != null && now - kept.madeAt() < INDEX_KEPT_MILLIS) {
            return kept.groupsOfArtifact();
        }
        Map<String, TreeSet<String>> index = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(key)) {
            walk.filter(p -> p.getFileName() != null
                    && p.getFileName().toString().endsWith(".pom"))
                .forEach(pom -> {
                    Path version = pom.getParent();
                    Path artifact = version == null ? null : version.getParent();
                    Path group = artifact == null ? null : artifact.getParent();
                    if (group == null || !group.startsWith(key) || group.equals(key)) {
                        return;
                    }
                    String artifactId = artifact.getFileName().toString();
                    if (!pom.getFileName().toString().startsWith(artifactId + "-")) {
                        return;
                    }
                    String groupId = key.relativize(group).toString()
                        .replace('\\', '/').replace('/', '.');
                    index.computeIfAbsent(artifactId, a -> new TreeSet<>()).add(groupId);
                });
        }
        INDEXES.put(key, new Index(now, index));
        return index;
    }
}
