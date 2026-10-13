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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * What a project has already established, kept once for every role, every worker, every run and
 * every story that works on it while its syntax tree is held (section 98).
 *
 * <p><b>Why.</b> In a five-story build 1,345 of 6,031 lookups were the first time anybody asked
 * about that target; 101 targets were each looked up by three roles or more, 3,460 lookups. Each
 * session computed the answer again, and candidates of one task made the same first lookups side
 * by side. A deterministic lookup has one answer for one state of what it reads, so that answer
 * is kept here and the next asker is given it.
 *
 * <p><b>How an answer is keyed.</b> By the question: who it is written for (a role's addresses
 * are {@code <root>/<path>}, a worker's are paths of its checkout), the query and its argument.
 *
 * <p><b>What an answer stands on</b>, recorded with it and held against the disk before it is
 * served:
 * <ul>
 *   <li>every file the lookup read through the curator while it was computed, and every file
 *       its question or its answer names, each with a hash of its bytes as it was then;</li>
 *   <li>for a question whose answer can change with any file - where a type is used, what a
 *       folder holds - and for an answer that names no file at all, the state of every root:
 *       the project's files by path, size and time, a reference folder by the identity the
 *       product already knows it by ({@link RootIdentity}).</li>
 * </ul>
 * An answer whose files are not all as they were is dropped and computed again, so a changed
 * file takes exactly the answers built on it. One store belongs to one {@link KnowledgeCurator}
 * and so to one reading of the syntax tree: an answer is never carried to another tree.
 *
 * <p><b>Findings</b> - what a role concluded, as distinct from what a lookup returned - are kept
 * beside the answers with who found them and the lookup they came from, on the same footing: a
 * finding whose lookup stood on a file that has changed is shown as no longer certain.
 *
 * <p>Nothing here calls a model, and nothing is written to disk: the store lives as long as the
 * project's context in this process.
 */
public final class SharedAnswers {

    private static final Logger log = LoggerFactory.getLogger(SharedAnswers.class);

    /** What a question's answer can change with. */
    public enum Reach {
        /** The files it reads and names: one type, one member, one file, one section. */
        ONE_PLACE,
        /** Any file of the project or its reference material: a usage, a listing, a search. */
        EVERYWHERE
    }

    /** What a computation returned: the answer, and a word of the caller's own about it. */
    public record Computed(String text, String tag) {}

    /**
     * What an asker is given.
     *
     * @param fromStore true when nothing was computed for this call
     * @param tag       the word the computation gave with the answer
     * @param basis     what the answer stands on
     */
    public record Served(String text, boolean fromStore, String tag, Basis basis) {}

    /**
     * What an answer stands on.
     *
     * @param files each file with the state it had; never null
     * @param roots the state of every root, or null when the files are all it stands on
     */
    public record Basis(Map<Path, String> files, String roots) {}

    private record Entry(String text, String tag, Basis basis, String by) {}

    /** One question's place: taken while its answer is computed, so it is computed once. */
    private static final class Slot {
        private Entry entry;
    }

    /** A file larger than this is told apart by its size and time, not by its bytes. */
    private static final long HASHED_UP_TO_BYTES = 4L * 1024 * 1024;

    /** Folders that hold no material: build output and tool state. */
    private static final Set<String> SKIPPED = Set.of("target", ".git", "node_modules", ".idea",
        ".swarmcoder", ".gradle", "build");

    private static final Set<String> ONE_PLACE = Set.of("public_shape", "shape_of", "body_of",
        "read_file", "outline_of", "doc_outline", "doc_section");

    private static final Set<String> EVERYWHERE = Set.of("find_usages", "usages_of",
        "find_implementations", "implementations_of", "files_using", "types_annotated_with",
        "call_chain", "dependency_declaring", "supertypes_of", "callers_of", "callees_of",
        "find_symbol", "types_in", "resources_of", "build_of", "texts_of", "doc_of",
        "list_files", "doc_search");

    /** The files the lookup running on this thread has read through the curator. */
    private static final ThreadLocal<Map<Path, String>> READING = new ThreadLocal<>();

    private final KnowledgeCurator curator;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicLong asked = new AtomicLong();
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong servedChars = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private final List<KnownFacts.Fact> findings = Collections.synchronizedList(new ArrayList<>());
    private final Map<KnownFacts.Fact, Basis> findingBasis = new ConcurrentHashMap<>();
    private volatile Supplier<KnownFacts> delivered;

    SharedAnswers(KnowledgeCurator curator) {
        this.curator = curator;
    }

    /**
     * What a query's answer can change with, or null for a query whose answer is not kept: a
     * search, a documentation question and an example are chosen by what the session has
     * already been given, and a question to the expert is answered by a model.
     */
    public static Reach reachOf(String query, String argument) {
        if (query == null) {
            return null;
        }
        boolean named = argument != null && !argument.isBlank();
        if (ONE_PLACE.contains(query)) {
            return named ? Reach.ONE_PLACE : Reach.EVERYWHERE;
        }
        return EVERYWHERE.contains(query) ? Reach.EVERYWHERE : null;
    }

    /**
     * The answer to one question: the one kept, when everything it stands on is as it was, and
     * otherwise the one {@code compute} gives now, which is then kept. Two askers of the same
     * question at the same moment cause one computation; the second is given the first's answer.
     *
     * @param form    who the answer is written for, and anything else of the asker that changes
     *                its text; part of the question
     * @param by      who asks, for the log
     * @param compute the lookup itself; what it throws is thrown on and nothing is kept
     */
    public Served answer(String form, String query, String argument, Reach reach, String by,
                         Supplier<Computed> compute) {
        asked.incrementAndGet();
        String asks = argument == null ? "" : argument.strip();
        Slot slot = slots.computeIfAbsent(form + "\u0000" + query + "\u0000" + asks,
            key -> new Slot());
        synchronized (slot) {
            Entry kept = slot.entry;
            if (kept != null) {
                if (holds(kept.basis())) {
                    served.incrementAndGet();
                    servedChars.addAndGet(kept.text().length());
                    log.debug("{} {}('{}') is given the answer the {} established, {} chars",
                        by, query, ExpertTools.forTheLog(asks), kept.by(), kept.text().length());
                    return new Served(kept.text(), true, kept.tag(), kept.basis());
                }
                dropped.incrementAndGet();
                slot.entry = null;
                log.debug("{}('{}'): what the kept answer stood on has changed; computed again",
                    query, ExpertTools.forTheLog(asks));
            }
            // The roots' state is taken BEFORE the lookup runs and a file's state before it is
            // read: a change that lands in between makes the kept state older than the answer,
            // which costs a computation later and can never serve a stale one.
            String rootsBefore = rootsNow();
            Map<Path, String> read = new LinkedHashMap<>();
            Map<Path, String> outer = READING.get();
            READING.set(read);
            Computed computed;
            try {
                computed = compute.get();
            } finally {
                if (outer == null) {
                    READING.remove();
                } else {
                    READING.set(outer);
                }
            }
            String text = computed == null || computed.text() == null ? "" : computed.text();
            Map<Path, String> files = new LinkedHashMap<>();
            boolean folder = false;
            for (Map.Entry<Path, String> one : read.entrySet()) {
                if ("folder".equals(one.getValue())) {
                    folder = true;
                } else {
                    files.put(one.getKey(), one.getValue());
                }
            }
            for (Path named : filesNamedIn(asks + "\n" + text)) {
                files.putIfAbsent(named, stateOf(named));
            }
            // A folder was read, or nothing at all names a file: the answer stands on more
            // than can be listed, so it stands on everything.
            boolean everywhere = reach == Reach.EVERYWHERE || folder || files.isEmpty();
            Basis basis = new Basis(Map.copyOf(files), everywhere ? rootsBefore : null);
            slot.entry = new Entry(text, computed == null ? null : computed.tag(), basis, by);
            return new Served(text, false, slot.entry.tag(), basis);
        }
    }

    /** True when everything {@code basis} names is as it was. */
    public boolean holds(Basis basis) {
        if (basis == null) {
            return false;
        }
        for (Map.Entry<Path, String> file : basis.files().entrySet()) {
            if (!file.getValue().equals(stateOf(file.getKey()))) {
                return false;
            }
        }
        return basis.roots() == null || basis.roots().equals(rootsNow());
    }

    /** What a text that no kept answer stands behind stands on now: the files it names. */
    Basis basisNow(String text) {
        Map<Path, String> files = new LinkedHashMap<>();
        for (Path named : filesNamedIn(text)) {
            files.put(named, stateOf(named));
        }
        return new Basis(Map.copyOf(files), files.isEmpty() ? rootsNow() : null);
    }

    // -------------------------------------------------------------------------------------------
    // What was read

    /** Called by the curator for every path a lookup resolves. */
    static void reading(Path path) {
        Map<Path, String> read = READING.get();
        if (read == null || path == null) {
            return;
        }
        Path file = path.toAbsolutePath().normalize();
        if (!read.containsKey(file)) {
            read.put(file, Files.isDirectory(file) ? "folder" : stateOf(file));
        }
    }

    /** One file's state: a hash of its bytes; "absent" when it is not a file. */
    static String stateOf(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "absent";
            }
            long size = Files.size(file);
            if (size > HASHED_UP_TO_BYTES) {
                return size + ":" + Files.getLastModifiedTime(file).toMillis();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(Files.readAllBytes(file)));
        } catch (Exception unreadable) {                                   // noqa
            return "unreadable";
        }
    }

    /** The files of the roots that {@code text} names, by any address the tools print. */
    private List<Path> filesNamedIn(String text) {
        List<Path> files = new ArrayList<>();
        if (curator == null || text == null) {
            return files;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String word : text.split("[\\s`'\"()\\[\\]<>,;|]+")) {
            String name = word.replace('\\', '/');
            int hash = name.indexOf('#');
            if (hash >= 0) {
                name = name.substring(0, hash);
            }
            name = name.replaceAll(":\\d+(-\\d+)?:?$", "");
            if (name.startsWith("/reference/")) {
                name = name.substring("/reference/".length());
            }
            while (name.endsWith(".") || name.endsWith(":")) {
                name = name.substring(0, name.length() - 1);
            }
            // An address has a folder or an extension; a bare word is not looked for on disk.
            if (name.length() < 3 || name.length() > 400 || name.startsWith("/")
                    || (name.indexOf('/') < 0 && name.lastIndexOf('.') <= 0) || !seen.add(name)) {
                continue;
            }
            for (KnowledgeCurator.Root root : curator.roots()) {
                if (root == null || root.path() == null) {
                    continue;
                }
                try {
                    Path base = root.path().toAbsolutePath().normalize();
                    String inside = name.startsWith(root.label() + "/")
                        ? name.substring(root.label().length() + 1) : name;
                    Path file = base.resolve(inside).normalize();
                    if (file.startsWith(base) && !file.equals(base) && Files.isRegularFile(file)
                            && !files.contains(file)) {
                        files.add(file);
                    }
                } catch (RuntimeException notAPath) {                      // noqa
                    // a word that is no path names no file
                }
            }
        }
        return files;
    }

    /**
     * The state of every root. The project's own files are walked, by path, size and time, on
     * every call: it is the root that changes under a build. A reference folder is taken by
     * {@link RootIdentity}, which is what the product's indexes over it are kept by.
     */
    String rootsNow() {
        if (curator == null) {
            return "";
        }
        StringBuilder state = new StringBuilder();
        for (KnowledgeCurator.Root root : curator.roots()) {
            if (root == null || root.path() == null) {
                continue;
            }
            state.append(root.label()).append('=')
                .append("project".equals(root.label()) ? walked(root.path())
                    : RootIdentity.of(root.path(), false).fingerprint())
                .append(';');
        }
        return state.toString();
    }

    private static String walked(Path root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (!Files.isDirectory(root)) {
                return "missing";
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    return !dir.equals(root) && name != null && SKIPPED.contains(name.toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    digest.update((root.relativize(file).toString().replace('\\', '/') + "|"
                        + attrs.size() + "|" + attrs.lastModifiedTime().toMillis() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException gone) {
                    digest.update((byte) '?');
                    return FileVisitResult.CONTINUE;
                }
            });
            return hex(digest.digest());
        } catch (Exception e) {                                            // noqa
            // A state that never equals an earlier one: nothing kept is served on it.
            return "unreadable-" + System.nanoTime();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(32);
        for (int i = 0; i < 16 && i < bytes.length; i++) {
            hex.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16))
                .append(Character.forDigit(bytes[i] & 0xF, 16));
        }
        return hex.toString();
    }

    // -------------------------------------------------------------------------------------------
    // What the roles concluded

    /** Where the findings of the project's delivered stories are read from (section 97). */
    public void delivered(Supplier<KnownFacts> facts) {
        this.delivered = facts;
    }

    /**
     * Keeps what a role concluded from a lookup, for every later session of the project.
     *
     * @param basis what that lookup's answer stood on
     */
    void keep(KnownFacts.Fact fact, Basis basis) {
        if (fact == null || basis == null) {
            return;
        }
        synchronized (findings) {
            if (!findings.contains(fact)) {
                findings.add(fact);
            }
        }
        findingBasis.put(fact, basis);
    }

    /** What the roles of this project's sessions concluded, oldest first. */
    public List<KnownFacts.Fact> findings() {
        synchronized (findings) {
            return List.copyOf(findings);
        }
    }

    /**
     * What became of what a kept finding stood on; null for a fact that was not kept here (a
     * delivered story's finding is held against the lines it carries instead).
     */
    KnownFacts.Source heldSince(KnownFacts.Fact fact) {
        Basis basis = fact == null ? null : findingBasis.get(fact);
        if (basis == null) {
            return null;
        }
        return holds(basis) ? KnownFacts.Source.UNCHANGED : KnownFacts.Source.CHANGED;
    }

    /** Everything the project knows: its delivered stories' findings, then the kept ones. */
    public KnownFacts known() {
        KnownFacts base = KnownFacts.none();
        Supplier<KnownFacts> supplier = delivered;
        if (supplier != null) {
            try {
                KnownFacts read = supplier.get();
                base = read == null ? KnownFacts.none() : read;
            } catch (RuntimeException e) {                                 // noqa
                log.warn("what the project's delivered stories established could not be read "
                    + "({})", e.toString());
            }
        }
        return base.and(findings());
    }

    /** {@code known_about} for an asker with no session of lookups of its own: a worker. */
    public String knownAbout(String what) {
        ExpertTools reader = new ExpertTools(curator, null, null, null);
        return known().about(what, reader::sourceOf);
    }

    // -------------------------------------------------------------------------------------------
    // What it did

    /** Questions put to the store. */
    public long asked() {
        return asked.get();
    }

    /** Questions answered with a kept answer: nothing was computed for them. */
    public long served() {
        return served.get();
    }

    /** Characters of the answers given without a computation. */
    public long servedChars() {
        return servedChars.get();
    }

    /** Kept answers dropped because something they stood on had changed. */
    public long dropped() {
        return dropped.get();
    }

    /** One line for a log or a report. */
    public String summary() {
        return asked() + " lookup(s) of a kind that is kept, " + served() + " given an answer "
            + "already established (" + servedChars() + " characters not computed again), "
            + dropped() + " kept answer(s) dropped because a file they stood on changed, "
            + findings().size() + " finding(s) kept";
    }
}
