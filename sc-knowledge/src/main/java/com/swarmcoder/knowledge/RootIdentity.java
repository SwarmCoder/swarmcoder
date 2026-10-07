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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * WHICH version of a reference folder the librarian is answering from, and WHETHER that folder has
 * changed since the index was built.
 *
 * <p><b>Why this is not optional.</b> A reference folder is somebody's live checkout. It moves: a
 * pull, a branch switch, a release tag, an uncommitted experiment. An index built from one state
 * and reused for another answers with confidence out of a version the project does not have — the
 * worst failure a documentation search can produce, because a wrong answer that compiles is worse
 * than no answer.
 *
 * <p><b>The fingerprint.</b> For a git checkout it is the HEAD commit, plus a hash of
 * {@code git status --porcelain} when the tree is dirty, so an operator's uncommitted edits get
 * their own index rather than quietly joining the committed one. For anything else it is a hash of
 * every file's path, size and modification time — ALL files, not a capped sample. The old
 * fingerprint hashed path and SIZE over the first 400 files in path order, which meant an edit that
 * kept a file's length was invisible and most of the folder could not influence it at all.
 *
 * <p><b>Except for a root the swarm writes to.</b> The project's own repository is rewritten by
 * workers every few seconds, so its dirty hash would change constantly and every change would mean
 * a new index directory and a full rebuild. For a writable root the fingerprint is the commit
 * alone, and working-tree changes are picked up incrementally by each file's own stamp — which is
 * size AND modification time, so a same-size edit is caught.
 */
final class RootIdentity {

    private static final Logger log = LoggerFactory.getLogger(RootIdentity.class);
    private static final long TTL_MS = 60_000;
    private static final long GIT_TIMEOUT_SECONDS = 10;

    /**
     * @param version     what to call this folder's contents: {@code git describe --tags --always},
     *                    else the nearest pom's version, else "unknown"
     * @param fingerprint what changes when the folder's content changes
     * @param commit      the HEAD commit id, or "" when the folder is not a git checkout
     * @param dirty       whether the checkout has uncommitted changes
     */
    record Identity(String version, String fingerprint, String commit, boolean dirty) {

        /** How the brief names this folder: "0.9.0-SNAPSHOT (commit ab12cd3, uncommitted edits)". */
        String describe() {
            StringBuilder sb = new StringBuilder(version);
            if (!commit.isBlank()) {
                sb.append(" (commit ").append(commit);
                if (dirty) {
                    sb.append(", uncommitted edits");
                }
                sb.append(')');
            }
            return sb.toString();
        }
    }

    private record Cached(Identity identity, long at) {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private RootIdentity() {
    }

    static Identity of(Path root, boolean writable) {
        if (root == null) {
            return new Identity("unknown", "none", "", false);
        }
        String key = root.toAbsolutePath() + "|" + writable;
        Cached cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < TTL_MS) {
            return cached.identity();
        }
        Identity identity = compute(root, writable);
        CACHE.put(key, new Cached(identity, System.currentTimeMillis()));
        return identity;
    }

    /** Forgets what it knows — for a test that changes a folder inside one TTL window. */
    static void forget() {
        CACHE.clear();
    }

    private static Identity compute(Path root, boolean writable) {
        if (!Files.isDirectory(root)) {
            return new Identity("unknown", "missing", "", false);
        }
        if (Files.isDirectory(root.resolve(".git")) || Files.isRegularFile(root.resolve(".git"))) {
            String commit = git(root, "rev-parse", "--short=10", "HEAD");
            if (!commit.isBlank()) {
                String porcelain = git(root, "status", "--porcelain");
                boolean dirty = !porcelain.isBlank();
                String described = git(root, "describe", "--tags", "--always");
                String version = described.isBlank() ? pomVersion(root) : described;
                String fingerprint = commit
                    + (dirty && !writable ? "-" + hash(porcelain).substring(0, 8) : "");
                return new Identity(version.isBlank() ? "unknown" : version, fingerprint,
                    commit, dirty);
            }
        }
        String version = pomVersion(root);
        return new Identity(version.isBlank() ? "unknown" : version, walkFingerprint(root), "", false);
    }

    /** Every file's path, size and modification time — all of them, not a capped sample. */
    private static String walkFingerprint(Path root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).sorted().forEach(file -> {
                    String name = file.toString().replace('\\', '/');
                    if (name.contains("/target/") || name.contains("/.git/")
                        || name.contains("/node_modules/") || name.contains("/build/")) {
                        return;
                    }
                    try {
                        digest.update(root.relativize(file).toString().replace('\\', '/')
                            .getBytes(StandardCharsets.UTF_8));
                        digest.update((Files.size(file) + ":"
                            + Files.getLastModifiedTime(file).toMillis())
                            .getBytes(StandardCharsets.UTF_8));
                    } catch (Exception vanished) {
                        digest.update((byte) '?');
                    }
                });
            }
            return hex(digest.digest()).substring(0, 12);
        } catch (Exception e) {
            log.warn("Could not fingerprint {}: {}", root, e.getMessage());
            return "unknown";
        }
    }

    private static final Pattern VERSION =
        Pattern.compile("<version>([^<$][^<]*)</version>");

    /** The nearest pom's own version — its {@code <version>}, else its parent's. */
    private static String pomVersion(Path root) {
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return "";
        }
        try {
            String text = Files.readString(pom);
            int dependencies = text.indexOf("<dependencies>");
            String head = dependencies > 0 ? text.substring(0, dependencies) : text;
            Matcher matcher = VERSION.matcher(head);
            return matcher.find() ? matcher.group(1).strip() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String git(Path root, String... arguments) {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toAbsolutePath().toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            Process process = builder.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                in.transferTo(buffer);
                output = buffer.toString(StandardCharsets.UTF_8);
            }
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return process.exitValue() == 0 ? output.strip() : "";
        } catch (Exception e) {
            log.debug("git {} in {} failed: {}", String.join(" ", arguments), root, e.getMessage());
            return "";
        }
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "0000000000";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(Integer.toHexString((b >> 4) & 0xf)).append(Integer.toHexString(b & 0xf));
        }
        return sb.toString();
    }
}
