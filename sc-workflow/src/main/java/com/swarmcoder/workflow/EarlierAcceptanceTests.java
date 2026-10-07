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
package com.swarmcoder.workflow;

import com.swarmcoder.knowledge.TestMethods;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * An acceptance test an earlier story committed is never destroyed by a later story's test
 * (DEVELOPER_CORRECTIONS section 59). The test author used to write its class under a name an
 * earlier story already used, and the write replaced the earlier story's passing test.
 *
 * <p>"Earlier" is what the run's pinned base commit holds under the acceptance-test directory:
 * a draft being written, an attempt of a parallel task, or this story's own committed tests are
 * not in that commit and so are never taken for an earlier test. A hand-in aimed at such a file
 * must keep every test method the file has, unchanged (compared on the syntax tree,
 * {@link TestMethods}); it may add methods or use a new class name instead. Without git there is
 * no baseline and nothing is held back.
 *
 * <p><b>Read from the commit, not remembered (section 60).</b> The sources used to be copied
 * into memory when authoring started, so a process restart between authoring and a repair lost
 * them and the repair was held to nothing. What is kept now is only WHICH commit is the base -
 * the workflow says so from the persisted run on every step ({@link #pin}), so it is said
 * again after a restart - and every answer is read from that commit. What a commit holds never
 * changes, so the cache of what was read is only a saving.
 */
final class EarlierAcceptanceTests {

    /** A tree, or the repository a tree belongs to (its git common directory): its base commit. */
    private static final Map<Path, String> BASE = new ConcurrentHashMap<>();
    /** "commit:path" to the source that commit holds there; "" when it holds none. */
    private static final Map<String, String> AT_COMMIT = new ConcurrentHashMap<>();

    private EarlierAcceptanceTests() {}

    /**
     * Says which commit is the base of the run working in {@code tree}: for that tree, and for
     * every other tree of the same repository that is not pinned itself (the red-check and repair
     * worktrees are cut after this is called). Called by the workflow with the run's own pinned
     * base on every step; a commit that cannot be resolved pins nothing.
     */
    static void pin(Path tree, String baseCommit) {
        if (tree == null || baseCommit == null || baseCommit.isBlank()) {
            return;
        }
        String sha = git(tree, "rev-parse", "--verify", "--quiet", baseCommit.strip() + "^{commit}");
        if (sha == null || sha.isBlank()) {
            return;
        }
        BASE.put(tree.toAbsolutePath().normalize(), sha.strip());
        Path repository = repositoryOf(tree);
        if (repository != null) {
            BASE.put(repository, sha.strip());
        }
    }

    /** Forgets every pin and everything read, as a process restart does. For tests. */
    static void forget() {
        BASE.clear();
        AT_COMMIT.clear();
        REPOSITORY.clear();
    }

    /** The base commit of {@code tree}: its own pin, else its repository's; null when neither. */
    static String baseOf(Path tree) {
        if (tree == null) {
            return null;
        }
        String own = BASE.get(tree.toAbsolutePath().normalize());
        if (own != null) {
            return own;
        }
        Path repository = repositoryOf(tree);
        return repository == null ? null : BASE.get(repository);
    }

    private static final Map<Path, Path> REPOSITORY = new ConcurrentHashMap<>();

    private static Path repositoryOf(Path tree) {
        Path key = tree.toAbsolutePath().normalize();
        Path known = REPOSITORY.get(key);
        if (known == null) {
            known = findRepositoryOf(tree);
            if (known != null) {
                REPOSITORY.put(key, known);
            }
        }
        return known;
    }

    private static Path findRepositoryOf(Path tree) {
        String common = git(tree, "rev-parse", "--git-common-dir");
        if (common == null || common.isBlank()) {
            return null;
        }
        try {
            return tree.toAbsolutePath().normalize().resolve(common.strip()).toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The .java files {@code commit} holds under {@code dir}: repo-relative path to source. */
    static Map<String, String> committed(Path tree, String dir, String commit) {
        Map<String, String> files = new LinkedHashMap<>();
        if (tree == null || dir == null || dir.isBlank() || commit == null) {
            return files;
        }
        String listing = git(tree, "ls-tree", "-r", "--name-only", commit, "--",
            dir.replace('\\', '/'));
        if (listing == null) {
            return files;
        }
        for (String path : listing.split("\\R")) {
            if (path.endsWith(".java")) {
                String source = at(tree, commit, path);
                if (source != null) {
                    files.put(path, source);
                }
            }
        }
        return files;
    }

    /** What {@code commit} holds at {@code path}, or null when it holds no such file. */
    private static String at(Path tree, String commit, String path) {
        String source = AT_COMMIT.computeIfAbsent(commit + ":" + path, key -> {
            String shown = git(tree, "show", key);
            return shown == null ? "" : shown;
        });
        return source.isEmpty() ? null : source;
    }

    /**
     * Called when a story's authoring starts. A tree the workflow has pinned keeps its pin; one
     * nobody pinned (a caller outside a run) takes its HEAD as the base, as it always did.
     */
    static void remember(Path tree, String dir) {
        if (tree != null && baseOf(tree) == null) {
            pin(tree, "HEAD");
        }
    }

    /** The committed source at {@code path}, or null when none was held at the start. */
    static String earlier(Path tree, String path) {
        String base = baseOf(tree);
        return base == null || path == null ? null : at(tree, base, relative(tree, path));
    }

    /**
     * What handing in {@code content} at {@code path} would take from the earlier test there, as
     * the message that goes back to the author; null when it takes nothing.
     */
    static String objection(Path tree, String path, String content) {
        String earlier = earlier(tree, path);
        if (earlier == null || content == null) {
            return null;
        }
        List<String> lost = TestMethods.lostBy(earlier, content);
        if (lost.isEmpty()) {
            return null;
        }
        return "`" + path + "` already holds an earlier story's passing acceptance test, and what "
            + "you gave would take it away: " + String.join("; ", lost) + ". An earlier test is "
            + "never removed or changed. Either keep every existing test method of that file "
            + "exactly as it is and ADD yours, or give your test a NEW class name in a file of "
            + "its own.";
    }

    /**
     * Test ids (class#method) of the earlier tests under {@code dir}, as the base commit holds
     * them. Not read from HEAD: a red-check tree can hold this story's own committed tests, and
     * those are the ones expected red.
     */
    static Set<String> ids(Path tree, String dir) {
        Set<String> ids = new LinkedHashSet<>();
        String base = baseOf(tree);
        if (base == null || dir == null || dir.isBlank()) {
            return ids;
        }
        for (String source : committed(tree, dir, base).values()) {
            ids.addAll(TestMethods.ids(source));
        }
        return ids;
    }

    /** Puts the earlier test back at {@code path}; false when there is none to put back. */
    static boolean restore(Path tree, String path) {
        String earlier = earlier(tree, path);
        if (earlier == null) {
            return false;
        }
        try {
            Files.writeString(tree.resolve(path.replace('\\', '/')), earlier);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** {@code path} as the repository names it: relative to the tree, forward slashes. */
    private static String relative(Path tree, String path) {
        String cleaned = path.replace('\\', '/');
        try {
            Path root = tree.toAbsolutePath().normalize();
            Path file = root.resolve(cleaned).normalize();
            return file.startsWith(root) ? root.relativize(file).toString().replace('\\', '/')
                : cleaned;
        } catch (RuntimeException e) {
            return cleaned;
        }
    }

    private static String git(Path tree, String... args) {
        try {
            java.util.ArrayList<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(tree.toAbsolutePath().toString());
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(false)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
