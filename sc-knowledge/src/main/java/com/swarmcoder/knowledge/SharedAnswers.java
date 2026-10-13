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

import com.swarmcoder.domain.EstablishedAnswer;
import com.swarmcoder.store.ArtifactStore;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * What a project has already established, kept once for every role, every worker, every run and
 * every story that works on it while its syntax tree is held (section 100).
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
 *       its files by path, size and time, the reference folders like the project;</li>
 *   <li>for an answer the syntax tree gave, which reading of the tree gave it.</li>
 * </ul>
 * An answer whose files are not all as they were is dropped and computed again, so a changed
 * file takes exactly the answers built on it. One store belongs to one {@link KnowledgeCurator}
 * and so to one reading of the syntax tree: an answer is never carried to another tree.
 *
 * <p><b>Findings</b> - what a role concluded, as distinct from what a lookup returned - are kept
 * beside the answers with who found them and the lookup they came from, on the same footing: a
 * finding whose lookup stood on a file that has changed is shown as no longer certain.
 *
 * <p><b>One fact for every reader</b> (section 101). A role and a worker write a file's address
 * differently, and the fact under their answers is the same. Where a lookup computes the same
 * thing for both it is computed once with its addresses marked, kept in that form, and written
 * for each reader when it is given ({@link Addresses}, {@link #answerFor}).
 *
 * <p><b>Between starts of the application</b> the answers and the findings are kept in the
 * product's own store, per project, beside the delivered stories' findings ({@link #keptIn}).
 * What is read back is held against the disk exactly as an answer of this process is, before
 * anything is served: a file that changed while the application was closed takes its answers.
 * An answer the syntax tree gave is kept with which reading of the tree gave it, and is served
 * only by the same reading.
 *
 * <p>Nothing here calls a model.
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
     * @param tree  the reading of the syntax tree that was asked, or null when it was not
     */
    public record Basis(Map<Path, String> files, String roots, String tree) {

        public Basis(Map<Path, String> files, String roots) {
            this(files, roots, null);
        }
    }

    /** Who an answer is written for, as part of its question: a role, with a role's addresses. */
    public static final String ROLE = "role";
    /** A worker, with the addresses of its checkout. */
    public static final String WORKER = "worker";
    /** Every reader: the addresses are marked and written for each when the answer is given. */
    static final String EVERYONE = "everyone";

    /** The tag of a kept fact that says "this is not the one to answer". */
    private static final String NONE = "none";

    /**
     * The answers kept between starts: the ones written for a kind of reader. A role's
     * {@code read_file} is cut to its session's room and shaped by its latest documentation
     * question; that is a session's answer, and it lives as long as this process.
     */
    private static final Set<String> KEPT_BETWEEN_STARTS = Set.of(ROLE, WORKER, EVERYONE);

    /** The most answers read back at a start; the newest are the ones kept. */
    static final int ANSWERS_READ_BACK = 5_000;

    /** What the lookup running on a thread has read, and what was said about its answer. */
    private static final class Reading {
        private final Map<Path, String> files = new LinkedHashMap<>();
        private boolean folder;
        private boolean tree;
        private boolean unsettled;
    }

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

    /** What the lookup running on this thread has read through the curator. */
    private static final ThreadLocal<Reading> READING = new ThreadLocal<>();

    private final KnowledgeCurator curator;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicLong asked = new AtomicLong();
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong servedChars = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private final List<KnownFacts.Fact> findings = Collections.synchronizedList(new ArrayList<>());
    private final Map<KnownFacts.Fact, Basis> findingBasis = new ConcurrentHashMap<>();
    private volatile Supplier<KnownFacts> delivered;

    /** Questions whose computation for every reader did not come out as one. */
    private final Set<String> notForEveryone = ConcurrentHashMap.newKeySet();

    private volatile ArtifactStore disk;
    private volatile UUID projectId;
    /** False from {@link #keptIn} until what the disk holds has been read back. */
    private volatile boolean readBack = true;
    private final Object readingBack = new Object();
    private final AtomicLong fromBefore = new AtomicLong();

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
        return answer(form, query, argument, reach, by, compute, true);
    }

    /** @param counted false for a part of a lookup, which is not a lookup of its own */
    private Served answer(String form, String query, String argument, Reach reach, String by,
                          Supplier<Computed> compute, boolean counted) {
        readBack();
        if (counted) {
            asked.incrementAndGet();
        }
        String asks = argument == null ? "" : argument.strip();
        String question = form + "\u0000" + query + "\u0000" + asks;
        Slot slot = slots.computeIfAbsent(question, key -> new Slot());
        // A lookup that asks for a kept fact stands on what that fact stands on.
        Reading outer = READING.get();
        synchronized (slot) {
            Entry kept = slot.entry;
            if (kept != null) {
                if (holds(kept.basis())) {
                    if (counted) {
                        served.incrementAndGet();
                        servedChars.addAndGet(Addresses.plain(kept.text()).length());
                    }
                    log.debug("{} {}('{}') is given the answer the {} established, {} chars",
                        by, query, ExpertTools.forTheLog(asks), kept.by(), kept.text().length());
                    taken(outer, kept.basis());
                    return new Served(kept.text(), true, kept.tag(), kept.basis());
                }
                dropped.incrementAndGet();
                slot.entry = null;
                forget(form, question);
                log.debug("{}('{}'): what the kept answer stood on has changed; computed again",
                    query, ExpertTools.forTheLog(asks));
            }
            // The roots' state is taken BEFORE the lookup runs and a file's state before it is
            // read: a change that lands in between makes the kept state older than the answer,
            // which costs a computation later and can never serve a stale one.
            String rootsBefore = rootsNow();
            Reading read = new Reading();
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
            Map<Path, String> files = new LinkedHashMap<>(read.files);
            for (Path named : filesNamedIn(asks + "\n" + Addresses.plain(text))) {
                files.putIfAbsent(named, stateOf(named));
            }
            // A folder was read, or nothing at all names a file: the answer stands on more
            // than can be listed, so it stands on everything.
            boolean everywhere = reach == Reach.EVERYWHERE || read.folder || files.isEmpty();
            Basis basis = new Basis(Map.copyOf(files), everywhere ? rootsBefore : null,
                read.tree && curator != null ? curator.treeStamp() : null);
            String tag = computed == null ? null : computed.tag();
            taken(outer, basis);
            if (read.unsettled) {
                // The lookup could not be made - no tree, no server: that is not an answer
                // about the project, and the next asker makes the lookup again.
                if (outer != null) {
                    outer.unsettled = true;
                }
                return new Served(text, false, tag, basis);
            }
            slot.entry = new Entry(text, tag, basis, by);
            keep(form, question, slot.entry);
            return new Served(text, false, tag, basis);
        }
    }

    /** What an enclosing lookup read by being given an answer that stands on {@code basis}. */
    private static void taken(Reading outer, Basis basis) {
        if (outer == null || basis == null) {
            return;
        }
        basis.files().forEach(outer.files::putIfAbsent);
        outer.folder |= basis.roots() != null;
        outer.tree |= basis.tree() != null;
    }

    /**
     * The answer to one question for {@code reader}: computed once for roles and workers alike
     * where the lookup is the same fact for both ({@link ForEveryReader}), and otherwise
     * {@link #answer} under {@code ownForm} exactly as before.
     *
     * @param ownForm who the answer is written for when it is this reader's alone
     * @param own     the lookup as this reader makes it
     */
    public Served answerFor(Addresses reader, String ownForm, String query, String argument,
                            Reach reach, String by, Supplier<Computed> own) {
        String asks = reader == null || curator == null || !reader.sharesWith(curator) ? null
            : ForEveryReader.questionOf(curator, reader, query, argument);
        String key = query + "\u0000" + asks;
        if (asks != null && !notForEveryone.contains(key)) {
            try {
                Served one = answer(EVERYONE, query, asks, reach, by, () -> new Computed(
                    Addresses.forEveryone(() -> ForEveryReader.answer(curator, query, asks)),
                    null));
                return new Served(reader.render(one.text()), one.fromStore(),
                    ForEveryReader.tagFor(reader, query), one.basis());
            } catch (Addresses.NotForEveryone notOne) {
                notForEveryone.add(key);
                asked.decrementAndGet();
                log.debug("{}('{}') is not one text for every reader ({}); computed for its "
                    + "asker", query, ExpertTools.forTheLog(asks), notOne.getMessage());
            }
        }
        return answer(ownForm, query, argument, reach, by, own);
    }

    /**
     * One part of a lookup that is the same for every reader - what the language server said -
     * kept once and written for {@code reader}. Null when the part is "not the one to answer".
     *
     * @param marked the part with its addresses marked; may give null
     * @param own    the part as this reader computes it, when it cannot be one text for all
     */
    String fact(String name, String about, Addresses reader, Supplier<String> marked,
                Supplier<String> own) {
        String key = name + "\u0000" + (about == null ? "" : about.strip());
        if (!notForEveryone.contains(key)) {
            try {
                Served one = answer(EVERYONE, name, about, Reach.EVERYWHERE, "a lookup", () -> {
                    String text = Addresses.forEveryone(marked);
                    return new Computed(text, text == null ? NONE : null);
                }, false);
                return NONE.equals(one.tag()) ? null : reader.render(one.text());
            } catch (Addresses.NotForEveryone notOne) {
                notForEveryone.add(key);
            }
        }
        return own.get();
    }

    /** True when everything {@code basis} names is as it was. */
    public boolean holds(Basis basis) {
        return holds(basis, SharedAnswers::stateOf, this::rootsNow);
    }

    private boolean holds(Basis basis, java.util.function.Function<Path, String> state,
                          Supplier<String> roots) {
        if (basis == null) {
            return false;
        }
        if (basis.tree() != null && (curator == null
                || !basis.tree().equals(curator.treeStamp()))) {
            return false;
        }
        for (Map.Entry<Path, String> file : basis.files().entrySet()) {
            if (!file.getValue().equals(state.apply(file.getKey()))) {
                return false;
            }
        }
        return basis.roots() == null || basis.roots().equals(roots.get());
    }

    // -------------------------------------------------------------------------------------------
    // Between starts of the application

    /**
     * Keeps this project's answers and findings in the product's store, so that a restart does
     * not lose them (section 101). What the store holds is read back at the next question, and
     * each answer is held against the disk then: one whose files, roots or reading of the tree
     * are not as recorded is forgotten, in memory and in the store.
     */
    public void keptIn(ArtifactStore store, UUID project) {
        if (store == null || project == null) {
            return;
        }
        synchronized (readingBack) {
            this.disk = store;
            this.projectId = project;
            this.readBack = false;
        }
    }

    private void readBack() {
        if (readBack) {
            return;
        }
        synchronized (readingBack) {
            if (readBack) {
                return;
            }
            try {
                read();
            } catch (RuntimeException e) {                                 // noqa
                log.warn("what this project established before this start could not be read "
                    + "({}); it is established again", e.toString());
            } finally {
                readBack = true;
            }
        }
    }

    private void read() {
        List<List<EstablishedAnswer>> stored = disk.established(projectId);
        List<EstablishedAnswer> answers = new ArrayList<>(stored.get(0));
        answers.sort(java.util.Comparator.comparingLong(EstablishedAnswer::keptAtMillis)
            .reversed());
        // One look at each file and one walk of the roots for everything read back.
        Map<Path, String> states = new HashMap<>();
        String[] roots = new String[1];
        List<String> gone = new ArrayList<>();
        int held = 0;
        for (EstablishedAnswer one : answers) {
            Basis basis = basisOf(one);
            if (one.question() == null || one.text() == null) {
                continue;
            }
            if (held >= ANSWERS_READ_BACK || !holds(basis,
                    file -> states.computeIfAbsent(file, SharedAnswers::stateOf),
                    () -> roots[0] == null ? roots[0] = rootsNow() : roots[0])) {
                gone.add(one.question());
                continue;
            }
            Slot slot = slots.computeIfAbsent(one.question(), key -> new Slot());
            synchronized (slot) {
                if (slot.entry == null) {
                    slot.entry = new Entry(one.text(), one.tag(), basis, one.by());
                    held++;
                }
            }
        }
        for (EstablishedAnswer one : stored.get(1)) {
            if (one.lookup() == null || one.text() == null) {
                continue;
            }
            KnownFacts.Fact fact = new KnownFacts.Fact(one.about(), one.lookup(), one.text(),
                null, null, null, one.by());
            synchronized (findings) {
                if (!findings.contains(fact)) {
                    findings.add(fact);
                }
            }
            findingBasis.putIfAbsent(fact, basisOf(one));
        }
        fromBefore.set(held);
        if (!gone.isEmpty()) {
            dropped.addAndGet(gone.size());
            disk.recordEstablished(projectId, List.of(), gone, List.of());
        }
        log.info("this project had established {} answer(s) and {} finding(s) before this "
            + "start; {} answer(s) no longer hold and were forgotten", held,
            stored.get(1).size(), gone.size());
    }

    private static Basis basisOf(EstablishedAnswer stored) {
        Map<Path, String> files = new LinkedHashMap<>();
        stored.files().forEach((file, state) -> {
            try {
                files.put(Path.of(file), state);
            } catch (RuntimeException notAPath) {                          // noqa
                // a path this machine cannot name: a state no file has, so it never holds
                files.put(Path.of("unreadable"), "never");
            }
        });
        return new Basis(Map.copyOf(files), stored.roots(), stored.tree());
    }

    private static EstablishedAnswer stored(String question, String text, String tag, String by,
                                            String about, String lookup, Basis basis) {
        Map<String, String> files = new HashMap<>();
        basis.files().forEach((file, state) -> files.put(file.toString(), state));
        return new EstablishedAnswer(question, text, tag, by, about, lookup, files,
            basis.roots(), basis.tree(), System.currentTimeMillis());
    }

    private void keep(String form, String question, Entry entry) {
        ArtifactStore store = disk;
        if (store != null && KEPT_BETWEEN_STARTS.contains(form)) {
            store.recordEstablished(projectId, List.of(stored(question, entry.text(),
                entry.tag(), entry.by(), null, null, entry.basis())), List.of(), List.of());
        }
    }

    private void forget(String form, String question) {
        ArtifactStore store = disk;
        if (store != null && KEPT_BETWEEN_STARTS.contains(form)) {
            store.recordEstablished(projectId, List.of(), List.of(question), List.of());
        }
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
        Reading read = READING.get();
        if (read == null || path == null) {
            return;
        }
        Path file = path.toAbsolutePath().normalize();
        if (Files.isDirectory(file)) {
            read.folder = true;
        } else if (!read.files.containsKey(file)) {
            read.files.put(file, stateOf(file));
        }
    }

    /** Called by the curator when a lookup asks the syntax tree. */
    static void readingTheTree() {
        Reading read = READING.get();
        if (read != null) {
            read.tree = true;
        }
    }

    /**
     * Called by a lookup that could not be made - the tree is not available, the language
     * server did not answer. What it says is about the tool and not about the project, so it is
     * given to its asker and not kept: with the store kept between starts it would otherwise
     * be served long after the tool was back.
     */
    static void unsettled() {
        Reading read = READING.get();
        if (read != null) {
            read.unsettled = true;
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
     * The state of every root: its files walked, by path, size and time, on every call.
     *
     * <p>A reference folder was taken by {@link RootIdentity} until section 101. That value is
     * remembered for 60 seconds, and for a checkout with uncommitted changes it is the commit
     * and the list of changed paths - so a second edit of a path already on that list never
     * moved it, and any other change moved it up to a minute late. A whole-project answer
     * could be served across such an edit. Every root is walked now, like the project.
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
            state.append(root.label()).append('=').append(walked(root.path())).append(';');
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
        readBack();
        // A conclusion is held against the files its lookup read, not against which reading
        // of the tree answered: it is marked when a file changes, as before.
        Basis files = new Basis(basis.files(), basis.roots(), null);
        boolean added;
        synchronized (findings) {
            added = !findings.contains(fact);
            if (added) {
                findings.add(fact);
            }
        }
        findingBasis.put(fact, files);
        ArtifactStore store = disk;
        if (added && store != null) {
            store.recordEstablished(projectId, List.of(), List.of(), List.of(stored(null,
                fact.note(), null, fact.by(), fact.about(), fact.source(), files)));
        }
    }

    /** What the roles of this project's sessions concluded, oldest first. */
    public List<KnownFacts.Fact> findings() {
        readBack();
        synchronized (findings) {
            return List.copyOf(findings);
        }
    }

    /**
     * What became of what a kept finding stood on; null for a fact that was not kept here (a
     * delivered story's finding is held against the lines it carries instead).
     */
    KnownFacts.Source heldSince(KnownFacts.Fact fact) {
        readBack();
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
            + findings().size() + " finding(s) kept, " + fromBefore.get() + " answer(s) held "
            + "from before this start";
    }
}
