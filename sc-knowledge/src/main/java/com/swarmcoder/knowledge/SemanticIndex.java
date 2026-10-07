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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The structural index: what the code MEANS, asked as questions instead of searched as text.
 *
 * <p><b>What was wrong with searching text.</b> Every free answer this system gives — the help
 * desk's call sites, the reference index's ranked files, the nearest worked example — was built on
 * words. "Which classes implement this interface" was answered by finding files whose text
 * contains the interface's name, which finds the import line of a file that mentions it in a
 * comment and misses the class that implements a sub-interface of it. "Which file is the nearest
 * example of this contract" was answered by the SHAPE of a declaration: how many methods, taking
 * how many arguments, returning what kind of thing. Those are good guesses and they are guesses.
 *
 * <p>This is not a guess. Every root is parsed by OpenRewrite into a Lossless Semantic Tree with
 * type attribution — the compiler's own answer to what every name in the file refers to — and the
 * relations are read out of it: this class implements that interface, this method calls that
 * method on that type, this file uses these resolved types, this build file declares this
 * artifact. A question with a type, a method, an annotation or an artifact in it can then be
 * ANSWERED rather than matched, with the file and the line the answer is on.
 *
 * <p><b>Apache-2.0 only.</b> OpenRewrite the library is Apache-2.0; Moderne's hosted platform,
 * which is what serialises and shares LSTs, is commercial and is not used. The consequence is
 * {@link SemanticFacts}: the tree cannot be written to disk, so the relations are, keyed by the
 * same root fingerprint and living in the same cache directory as the text index.
 *
 * <p><b>It fails open, always.</b> A language OpenRewrite cannot parse, a missing classpath entry,
 * a JVM that will not host the parser, a root too big to compile — every one of them leaves this
 * index unavailable or partial, says why exactly once, and lets the caller fall back to the text
 * index. Nothing in a run may block on it. The one thing it never does is answer wrongly and stay
 * quiet about it: an index that could not be built reports {@link #available()} false, and every
 * query returns nothing.
 */
public final class SemanticIndex {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndex.class);

    /** How long one root's parse may take before what has been read is kept and the rest dropped. */
    private static final long BUILD_BUDGET_MILLIS = 10 * 60 * 1000L;

    /** How far {@link #callChain} looks before giving up. A chain longer than this is not an answer. */
    public static final int MAX_CHAIN_DEPTH = 6;

    /** How many answers one query returns. A worker reads the first few; the rest are noise. */
    private static final int MAX_RESULTS = 40;

    /** One thing the index found, and where a person can go and look at it. */
    public record Ref(String rootLabel, String file, int line, String detail) {

        /** {@code zerozstack-server-core/src/main/java/…/Foo.java:42} — quotable as it stands. */
        public String where() {
            return line > 0 ? file + ":" + line : file;
        }

        @Override
        public String toString() {
            return where() + (detail == null || detail.isBlank() ? "" : "  " + detail);
        }
    }

    /** What the index cost and what is in it — reported, never guessed at. */
    public record Stats(int roots, int javaFiles, int parsedFiles, int types, int uses, int calls,
                        int declaredDependencies, long buildMillis, long bytesOnDisk) {}

    private final List<SemanticFacts.RootFacts> roots;
    private final String unavailable;
    private final Stats stats;
    private volatile String degraded = "";

    // Read once, on construction, because every query needs them and a run asks many.
    private final Map<String, List<SemanticFacts.TypeFact>> typesByFqn = new HashMap<>();
    private final Map<String, List<SemanticFacts.TypeFact>> typesBySimpleName = new HashMap<>();
    private final Map<String, List<Located<SemanticFacts.UseFact>>> usesByFqn = new HashMap<>();
    private final Map<String, List<Located<SemanticFacts.CallFact>>> callsByTarget = new HashMap<>();
    private final Map<String, List<Located<SemanticFacts.CallFact>>> callsByCaller = new HashMap<>();
    private final Map<String, Set<String>> typesInFile = new LinkedHashMap<>();
    private final Map<String, String> rootOfFile = new HashMap<>();

    private record Located<T>(String rootLabel, T fact) {}

    private SemanticIndex(List<SemanticFacts.RootFacts> roots, String unavailable, long bytes) {
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        this.unavailable = unavailable == null ? "" : unavailable;
        int javaFiles = 0;
        int parsed = 0;
        int types = 0;
        int uses = 0;
        int calls = 0;
        int poms = 0;
        long millis = 0;
        for (SemanticFacts.RootFacts facts : this.roots) {
            javaFiles += facts.javaFiles;
            parsed += facts.parsedFiles;
            types += facts.types.size();
            uses += facts.uses.size();
            calls += facts.calls.size();
            poms += facts.poms.size();
            millis += facts.buildMillis;
            index(facts);
        }
        this.stats = new Stats(this.roots.size(), javaFiles, parsed, types, uses, calls, poms,
            millis, bytes);
    }

    private void index(SemanticFacts.RootFacts facts) {
        for (SemanticFacts.TypeFact type : facts.types) {
            typesByFqn.computeIfAbsent(type.fqn(), k -> new ArrayList<>()).add(type);
            typesBySimpleName.computeIfAbsent(type.simpleName(), k -> new ArrayList<>()).add(type);
            rootOfFile.put(type.file(), facts.label);
        }
        for (SemanticFacts.UseFact use : facts.uses) {
            usesByFqn.computeIfAbsent(use.fqn(), k -> new ArrayList<>())
                .add(new Located<>(facts.label, use));
            rootOfFile.put(use.file(), facts.label);
        }
        for (SemanticFacts.CallFact call : facts.calls) {
            Located<SemanticFacts.CallFact> located = new Located<>(facts.label, call);
            callsByTarget.computeIfAbsent(call.target(), k -> new ArrayList<>()).add(located);
            callsByTarget.computeIfAbsent(call.method(), k -> new ArrayList<>()).add(located);
            callsByCaller.computeIfAbsent(call.caller(), k -> new ArrayList<>()).add(located);
            rootOfFile.put(call.file(), facts.label);
        }
        for (Map.Entry<String, List<String>> entry : facts.typesInFile.entrySet()) {
            typesInFile.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            rootOfFile.put(entry.getKey(), facts.label);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Building it
    // ---------------------------------------------------------------------------------------

    /** The index that answers nothing, and says why. Every failure path ends here. */
    static SemanticIndex unavailable(String why) {
        return new SemanticIndex(List.of(), why, 0L);
    }

    /**
     * The index over these roots, from the cache when the roots have not moved and from a parse
     * when they have.
     *
     * <p>Keyed exactly as the text index is: one directory per root, named for the root's label
     * and a hash of its path and its version fingerprint, under the same
     * {@code ~/.swarmcoder/rag/refs} the reference index uses. Per ROOT rather than per root set,
     * because a reference checkout is the same checkout for every project that points at it and
     * paying for it once is the difference between this being usable and not.
     *
     * <p><b>Never throws.</b> Anything that goes wrong comes back as an unavailable index.
     */
    public static SemanticIndex over(List<KnowledgeCurator.Root> roots, Path cacheRoot) {
        if (roots == null || roots.isEmpty()) {
            return unavailable("no reference roots were given");
        }
        if (Boolean.getBoolean("swarmcoder.semanticIndex.off")) {
            return unavailable("the semantic index is switched off "
                + "(-Dswarmcoder.semanticIndex.off=true)");
        }
        Path base = cacheRoot != null ? cacheRoot
            : Path.of(System.getProperty("user.home"), ".swarmcoder", "rag", "refs");
        List<SemanticFacts.RootFacts> built = new ArrayList<>();
        long bytes = 0L;
        List<String> problems = new ArrayList<>();
        for (KnowledgeCurator.Root root : roots) {
            if (root == null || root.path() == null || !Files.isDirectory(root.path())) {
                continue;
            }
            Path file = cacheFileFor(base, root);
            SemanticFacts.RootFacts facts = SemanticFacts.read(file);
            if (facts == null) {
                try {
                    facts = LstReader.read(root, BUILD_BUDGET_MILLIS);
                    SemanticFacts.write(file, facts);
                    pruneOtherFingerprints(base, root, file);
                } catch (Throwable e) {                                    // noqa
                    // OutOfMemoryError included, on purpose: a root too big to compile must not
                    // take the run with it. See the class javadoc — this fails open.
                    problems.add("'" + root.label() + "' could not be parsed ("
                        + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
                    continue;
                }
            }
            if (!facts.degraded.isEmpty()) {
                problems.add("'" + root.label() + "' is partial: " + facts.degraded);
            }
            built.add(facts);
            try {
                bytes += Files.isRegularFile(file) ? Files.size(file) : 0L;
            } catch (Exception e) {                                        // noqa
                // a size is a report, not a result
            }
        }
        if (built.isEmpty()) {
            return unavailable(problems.isEmpty()
                ? "none of the reference roots exist on this machine"
                : String.join("; ", problems));
        }
        SemanticIndex index = new SemanticIndex(built, "", bytes);
        index.degraded = String.join("; ", problems);
        if (!problems.isEmpty()) {
            // ONCE, and loudly enough to find, because a partial index is the state a person will
            // want to know about and the state nothing else will make visible.
            log.warn("the semantic index is not complete: {}", String.join("; ", problems));
        }
        log.info("semantic index ready: {} root(s), {} type(s), {} call(s) over {} parsed file(s), "
                + "{} KB on disk", index.stats.roots(), index.stats.types(), index.stats.calls(),
            index.stats.parsedFiles(), index.stats.bytesOnDisk() / 1024);
        return index;
    }

    /** {@code <cache>/<label>-<hash>/semantic.json.gz} — one directory per root, as the text index. */
    private static Path cacheFileFor(Path base, KnowledgeCurator.Root root) {
        return base.resolve(sanitise(root.label()) + "-" + fingerprintOf(root))
            .resolve("semantic.json.gz");
    }

    private static String fingerprintOf(KnowledgeCurator.Root root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(root.label().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(String.valueOf(root.path()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(root.identity().fingerprint().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(SemanticFacts.FORMAT.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Integer.toHexString((b >> 4) & 0xf)).append(Integer.toHexString(b & 0xf));
            }
            return hex.substring(0, 12);
        } catch (Exception e) {                                            // noqa
            return "default";
        }
    }

    private static String sanitise(String label) {
        return label == null ? "root" : label.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** A checkout that moves every week must not leave a copy of its index on disk every week. */
    private static void pruneOtherFingerprints(Path base, KnowledgeCurator.Root root, Path keep) {
        Path mine = keep.getParent();
        try (Stream<Path> siblings = Files.list(base)) {
            siblings.filter(Files::isDirectory)
                .filter(dir -> dir.getFileName().toString()
                    .startsWith(sanitise(root.label()) + "-"))
                .filter(dir -> !dir.equals(mine))
                .forEach(dir -> {
                    Path stale = dir.resolve("semantic.json.gz");
                    try {
                        Files.deleteIfExists(stale);
                    } catch (Exception e) {                                // noqa
                        log.debug("could not delete the stale semantic index {}", stale);
                    }
                });
        } catch (Exception e) {                                            // noqa
            log.debug("could not prune old semantic indexes under {}: {}", base, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------
    // What it is
    // ---------------------------------------------------------------------------------------

    /** False when nothing was parsed. Every caller checks this and falls back to the text index. */
    public boolean available() {
        return !roots.isEmpty() && stats.types() > 0;
    }

    /** Why it is not available, or "" when it is. Said in a log line, never swallowed. */
    public String unavailableReason() {
        if (available()) {
            return "";
        }
        return unavailable.isEmpty()
            ? "the reference roots parsed to no types at all" : unavailable;
    }

    /**
     * What is MISSING from an index that is otherwise available, or "".
     *
     * <p>Separate from {@link #unavailableReason()} on purpose, and the difference matters: an
     * unavailable index answers nothing and the caller falls back wholesale, while a degraded one
     * answers everything it holds and is simply not the whole checkout. A framework's archetype
     * carries template sources with {@code ${placeholder}} in them that are not Java and never
     * will be; those files are skipped, this says so, and no query is wrong because of them.
     */
    public String degradedReason() {
        return degraded;
    }

    public Stats stats() {
        return stats;
    }

    /** Every type the index knows by this simple name, as fully-qualified names. */
    public List<String> resolve(String simpleOrQualifiedName) {
        if (simpleOrQualifiedName == null || simpleOrQualifiedName.isBlank()) {
            return List.of();
        }
        String asked = simpleOrQualifiedName.strip();
        if (typesByFqn.containsKey(asked)) {
            return List.of(asked);
        }
        List<SemanticFacts.TypeFact> bySimple = typesBySimpleName.get(asked);
        if (bySimple != null && !bySimple.isEmpty()) {
            Set<String> names = new LinkedHashSet<>();
            bySimple.forEach(type -> names.add(type.fqn()));
            return List.copyOf(names);
        }
        // A type that is USED here but declared in a jar: the import line resolved it for us.
        Set<String> imported = new LinkedHashSet<>();
        for (String fqn : usesByFqn.keySet()) {
            if (fqn.equals(asked) || fqn.endsWith("." + asked)) {
                imported.add(fqn);
            }
        }
        return List.copyOf(imported);
    }

    // ---------------------------------------------------------------------------------------
    // The queries
    // ---------------------------------------------------------------------------------------

    /**
     * Every type that can stand in for this one: implementations of an interface, subclasses of a
     * class, transitively.
     *
     * <p>The compiler's answer, not a text one. A class implementing {@code AutoCloseable} through
     * two intermediate interfaces is here; a class whose javadoc names the interface is not.
     */
    public List<Ref> implementationsOf(String type) {
        Set<String> wanted = new LinkedHashSet<>(resolve(type));
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<Ref> found = new ArrayList<>();
        for (List<SemanticFacts.TypeFact> declared : typesByFqn.values()) {
            for (SemanticFacts.TypeFact fact : declared) {
                for (String assignable : fact.assignableTo()) {
                    if (wanted.contains(assignable)) {
                        found.add(new Ref(rootOfFile.getOrDefault(fact.file(), ""), fact.file(),
                            fact.line(), fact.kind() + " " + fact.fqn()));
                        break;
                    }
                }
            }
        }
        return cut(found);
    }

    /**
     * Everywhere a type is named or a method is called.
     *
     * <p>{@code usagesOf("com.acme.Ledger")} answers with the files that name the type;
     * {@code usagesOf("Ledger#append")} or {@code usagesOf("append")} answers with the CALLS, each
     * one saying which method it was called from — which is the thing a stuck worker is actually
     * asking for and the thing a text search cannot separate from a definition.
     */
    public List<Ref> usagesOf(String typeOrMethod) {
        if (typeOrMethod == null || typeOrMethod.isBlank()) {
            return List.of();
        }
        String asked = typeOrMethod.strip().replace("()", "");
        if (asked.contains("#") || asked.contains("::")) {
            String key = asked.replace("::", "#");
            String owner = key.substring(0, key.indexOf('#'));
            String method = key.substring(key.indexOf('#') + 1);
            List<Ref> found = new ArrayList<>();
            for (String fqn : resolve(owner)) {
                found.addAll(callRefs(callsByTarget.get(fqn + "#" + method)));
            }
            return cut(found);
        }
        List<Ref> found = new ArrayList<>();
        for (String fqn : resolve(asked)) {
            for (Located<SemanticFacts.UseFact> use : usesByFqn.getOrDefault(fqn, List.of())) {
                found.add(new Ref(use.rootLabel(), use.fact().file(), use.fact().line(),
                    use.fact().kind() + " " + fqn));
            }
        }
        if (found.isEmpty()) {
            found.addAll(callRefs(callsByTarget.get(asked)));
        }
        return cut(found);
    }

    private List<Ref> callRefs(List<Located<SemanticFacts.CallFact>> calls) {
        List<Ref> refs = new ArrayList<>();
        for (Located<SemanticFacts.CallFact> call : calls == null ? List.<Located<
                SemanticFacts.CallFact>>of() : calls) {
            SemanticFacts.CallFact fact = call.fact();
            refs.add(new Ref(call.rootLabel(), fact.file(), fact.line(),
                fact.declaringType() + "#" + fact.method() + "() called from "
                    + fact.enclosingMethod() + "() of " + fact.enclosingType()));
        }
        return refs;
    }

    /**
     * The files whose RESOLVED types include every one of these — the structural intersection.
     *
     * <p>This is what makes "the nearest example" a fact rather than a resemblance. A file that
     * uses the framework's session type AND its repository annotation AND the collection the
     * contract returns is doing the thing the contract describes, whatever its domain names are.
     * The empty set is a real answer and means "no file in the reference material does all of
     * that"; the caller falls back rather than lowering the bar.
     */
    public List<String> filesUsingAll(Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String type : types) {
            wanted.addAll(resolve(type));
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : typesInFile.entrySet()) {
            if (entry.getValue().containsAll(wanted)) {
                files.add(entry.getKey());
            }
        }
        files.sort(Comparator.naturalOrder());
        return List.copyOf(files);
    }

    /** {@link #filesUsingAll(Collection)}, spelled the way a call site with two types reads. */
    public List<String> filesUsingAll(String... types) {
        return filesUsingAll(List.of(types));
    }

    /** The resolved types one file uses. The structural fingerprint of a file, for overlapping. */
    public Set<String> typesUsedIn(String file) {
        return Set.copyOf(typesInFile.getOrDefault(file, Set.of()));
    }

    /**
     * What fraction of the indexed files use this type, from 0 to 1.
     *
     * <p>Evidence has to be distinctive to be evidence. {@code java.lang.String} is used by nearly
     * every file in every codebase, so "this file uses String" narrows nothing and putting it in
     * an intersection produces a list of almost everything, which then looks like a result. This
     * is how a caller tells the two apart.
     */
    public double howCommon(String type) {
        if (typesInFile.isEmpty()) {
            return 0;
        }
        Set<String> wanted = new LinkedHashSet<>(resolve(type));
        if (wanted.isEmpty()) {
            return 0;
        }
        int using = 0;
        for (Set<String> inFile : typesInFile.values()) {
            for (String fqn : wanted) {
                if (inFile.contains(fqn)) {
                    using++;
                    break;
                }
            }
        }
        return (double) using / typesInFile.size();
    }

    /**
     * The framework markers the code ALREADY IN a package carries: its annotations and the types
     * it extends or implements.
     *
     * <p>This is what a contract's "imports and annotations" are, for a contract that has neither.
     * A contract fixes a fully-qualified type name, which places it in a package, and the files
     * already in that package are the strongest available statement of what a type belonging there
     * has to look like — which marker annotation makes the framework find it, which interface it
     * has to implement. A contract for a package with nothing in it yet contributes nothing here,
     * which is the honest answer and not a failure.
     */
    public Set<String> idiomOf(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return Set.of();
        }
        String prefix = packageName.strip() + ".";
        Set<String> idiom = new LinkedHashSet<>();
        for (List<SemanticFacts.TypeFact> declared : typesByFqn.values()) {
            for (SemanticFacts.TypeFact fact : declared) {
                if (!fact.fqn().startsWith(prefix)) {
                    continue;
                }
                idiom.addAll(fact.annotations());
                idiom.addAll(fact.assignableTo());
            }
        }
        return Set.copyOf(idiom);
    }

    /**
     * Every type carrying this annotation, resolved.
     *
     * <p>Which is how a framework's wiring is actually found: "what does this server discover" is
     * "what is annotated {@code @ApplicationScoped}", and the annotation's own package is part of
     * the answer, because two frameworks in one checkout both have one called {@code Service}.
     */
    public List<Ref> typesAnnotatedWith(String annotation) {
        Set<String> wanted = new LinkedHashSet<>(resolve(annotation));
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<Ref> found = new ArrayList<>();
        for (List<SemanticFacts.TypeFact> declared : typesByFqn.values()) {
            for (SemanticFacts.TypeFact fact : declared) {
                for (String carried : fact.annotations()) {
                    if (wanted.contains(carried) || wanted.stream()
                            .anyMatch(w -> w.endsWith("." + carried) || carried.endsWith("." + w))) {
                        found.add(new Ref(rootOfFile.getOrDefault(fact.file(), ""), fact.file(),
                            fact.line(), fact.kind() + " " + fact.fqn() + " @" + carried));
                        break;
                    }
                }
            }
        }
        return cut(found);
    }

    /**
     * The shortest path of calls from one method to anything on a type, or nothing.
     *
     * <p>Breadth-first over the resolved call graph, bounded — a chain longer than
     * {@link #MAX_CHAIN_DEPTH} is not something anybody reads as an explanation, and an unbounded
     * walk of a framework's call graph does not terminate usefully.
     *
     * @param fromMethod {@code com.acme.Service#save} or {@code Service#save}
     * @param toType     the type the chain must reach a call on
     */
    /** {@link #callChain(String, String, int)} at the default bound. */
    public List<Ref> callChain(String fromMethod, String toType) {
        return callChain(fromMethod, toType, MAX_CHAIN_DEPTH);
    }

    public List<Ref> callChain(String fromMethod, String toType, int maxDepth) {
        if (fromMethod == null || toType == null) {
            return List.of();
        }
        Set<String> destination = new LinkedHashSet<>(resolve(toType));
        if (destination.isEmpty()) {
            return List.of();
        }
        int depth = Math.min(maxDepth <= 0 ? MAX_CHAIN_DEPTH : maxDepth, MAX_CHAIN_DEPTH);
        List<String> starts = new ArrayList<>();
        String asked = fromMethod.strip().replace("::", "#").replace("()", "");
        if (asked.contains("#")) {
            String owner = asked.substring(0, asked.indexOf('#'));
            String method = asked.substring(asked.indexOf('#') + 1);
            for (String fqn : resolve(owner)) {
                starts.add(fqn + "#" + method);
            }
            if (starts.isEmpty()) {
                starts.add(asked);
            }
        } else {
            for (String caller : callsByCaller.keySet()) {
                if (caller.endsWith("#" + asked)) {
                    starts.add(caller);
                }
            }
        }
        Deque<List<Located<SemanticFacts.CallFact>>> queue = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>(starts);
        for (String start : starts) {
            for (Located<SemanticFacts.CallFact> call
                    : callsByCaller.getOrDefault(start, List.of())) {
                queue.add(List.of(call));
            }
        }
        while (!queue.isEmpty()) {
            List<Located<SemanticFacts.CallFact>> path = queue.removeFirst();
            SemanticFacts.CallFact last = path.get(path.size() - 1).fact();
            if (destination.contains(last.declaringType())) {
                return callRefs(path);
            }
            if (path.size() >= depth) {
                continue;
            }
            String next = last.target();
            if (!seen.add(next)) {
                continue;
            }
            for (Located<SemanticFacts.CallFact> call
                    : callsByCaller.getOrDefault(next, List.of())) {
                List<Located<SemanticFacts.CallFact>> longer = new ArrayList<>(path);
                longer.add(call);
                queue.add(longer);
            }
        }
        return List.of();
    }

    /**
     * A type's public members, with the types the COMPILER resolved rather than the ones its
     * import lines suggested. Empty when the index does not hold the type.
     */
    public String publicShape(String type) {
        for (String fqn : resolve(type)) {
            List<SemanticFacts.TypeFact> facts = typesByFqn.get(fqn);
            if (facts == null || facts.isEmpty()) {
                continue;
            }
            SemanticFacts.TypeFact fact = facts.get(0);
            StringBuilder sb = new StringBuilder(fact.isAbstract() ? "abstract " : "")
                .append(fact.kind()).append(' ').append(fact.fqn());
            if (!fact.assignableTo().isEmpty()) {
                sb.append("\n  is a: ").append(String.join(", ", fact.assignableTo()));
            }
            if (!fact.annotations().isEmpty()) {
                sb.append("\n  annotated: @").append(String.join(" @", fact.annotations()));
            }
            for (String member : fact.shape()) {
                sb.append("\n  ").append(member);
            }
            sb.append("\n  declared in ").append(fact.file()).append(':').append(fact.line());
            return sb.toString();
        }
        return "";
    }

    /**
     * Where a type is DECLARED — the file and the line, with what kind of thing it is.
     *
     * <p>{@link #publicShape} already ends with this, as one sentence under a page of members; a
     * caller that wants only the location had to either quote the whole shape or parse that
     * sentence back out of it. Null when the index does not hold the type, which is the same answer
     * {@code publicShape} gives as "".
     */
    public Ref declarationOf(String type) {
        for (String fqn : resolve(type)) {
            List<SemanticFacts.TypeFact> facts = typesByFqn.get(fqn);
            if (facts == null || facts.isEmpty()) {
                continue;
            }
            SemanticFacts.TypeFact fact = facts.get(0);
            return new Ref(rootOfFile.getOrDefault(fact.file(), ""), fact.file(), fact.line(),
                (fact.isAbstract() ? "abstract " : "") + fact.kind() + " " + fact.fqn());
        }
        return null;
    }

    /**
     * Every build file that DECLARES this artifact, with the line the block starts on.
     *
     * <p>"Which pom already has this" is a question the text index answers by finding files
     * containing the word, which finds the release note and the README. This finds the
     * {@code <dependency>} element.
     */
    public List<Ref> dependencyDeclaring(String artifact) {
        if (artifact == null || artifact.isBlank()) {
            return List.of();
        }
        String asked = artifact.strip();
        String group = asked.contains(":") ? asked.substring(0, asked.indexOf(':')) : "";
        String id = asked.contains(":") ? asked.substring(asked.indexOf(':') + 1) : asked;
        List<Ref> found = new ArrayList<>();
        for (SemanticFacts.RootFacts facts : roots) {
            for (SemanticFacts.PomFact pom : facts.poms) {
                if (!pom.artifactId().equals(id)) {
                    continue;
                }
                if (!group.isEmpty() && !group.equals(pom.groupId())) {
                    continue;
                }
                found.add(new Ref(facts.label, pom.file(), pom.line(),
                    (pom.managed() ? "manages " : "declares ") + pom.groupId() + ":"
                        + pom.artifactId()
                        + (pom.version() == null || pom.version().isBlank()
                            ? " (version managed elsewhere)" : ":" + pom.version())));
            }
        }
        return cut(found);
    }

    /**
     * One declared type as the graph holds it, without its members.
     *
     * @param members how many public members its shape lists
     * @param isA     the supertypes this graph also declares - the relations inside the material
     */
    public record Declared(String rootLabel, String file, int line, String kind, String fqn,
                           int members, List<String> isA, List<String> shape) {

        /** {@code interface com.x.Ledger, 4 members, is a com.x.Book} */
        public String summary() {
            return kind + " " + fqn + ", " + members + (members == 1 ? " member" : " members")
                + (isA.isEmpty() ? "" : ", is a " + String.join(", ", isA));
        }
    }

    /** Every type the graph declares, by root, file and line - the same order every time. */
    public List<Declared> declaredTypes() {
        List<Declared> all = new ArrayList<>();
        for (SemanticFacts.RootFacts facts : roots) {
            for (SemanticFacts.TypeFact type : facts.types) {
                List<String> isA = new ArrayList<>();
                for (String parent : type.assignableTo()) {
                    if (!parent.equals(type.fqn()) && typesByFqn.containsKey(parent)) {
                        isA.add(parent);
                    }
                }
                all.add(new Declared(facts.label, type.file().replace('\\', '/'), type.line(),
                    (type.isAbstract() ? "abstract " : "") + type.kind(), type.fqn(),
                    type.shape().size(), List.copyOf(isA), type.shape()));
            }
        }
        all.sort(Comparator.comparing(Declared::rootLabel).thenComparing(Declared::file)
            .thenComparingInt(Declared::line));
        return all;
    }

    /**
     * The types declared in a package (and its sub-packages), a module or a folder: "what is in
     * here" answered from the graph, where a folder listing gives file names and a search gives
     * whatever matched the words.
     *
     * @param where a package name ({@code com.x.server}), or a module or folder as a path, with
     *              or without its root ({@code app-server}, {@code project/app-server/src/main})
     */
    public List<Declared> typesIn(String where) {
        if (where == null || where.isBlank()) {
            return List.of();
        }
        String asked = where.strip().replace('\\', '/');
        while (asked.startsWith("/")) {
            asked = asked.substring(1);
        }
        while (asked.endsWith("/") || asked.endsWith(".")) {
            asked = asked.substring(0, asked.length() - 1);
        }
        if (asked.isEmpty()) {
            return List.of();
        }
        List<Declared> found = new ArrayList<>();
        for (Declared type : declaredTypes()) {
            int cut = type.fqn().lastIndexOf('.');
            String pkg = cut < 0 ? "" : type.fqn().substring(0, cut);
            boolean inPackage = pkg.equals(asked) || pkg.startsWith(asked + ".");
            boolean inFolder = ("/" + type.rootLabel() + "/" + type.file())
                .contains("/" + asked + "/");
            if (inPackage || inFolder) {
                found.add(type);
            }
        }
        return found;
    }

    /**
     * What the build files of a module or folder declare: every dependency block, with the
     * line it starts on - the part of a build file a plan is written from, without the file.
     *
     * @param where a module or folder as a path, with or without its root; blank is every
     *              build file
     */
    public List<Ref> buildOf(String where) {
        String asked = where == null ? "" : where.strip().replace('\\', '/');
        while (asked.startsWith("/")) {
            asked = asked.substring(1);
        }
        while (asked.endsWith("/")) {
            asked = asked.substring(0, asked.length() - 1);
        }
        List<Ref> found = new ArrayList<>();
        for (SemanticFacts.RootFacts facts : roots) {
            for (SemanticFacts.PomFact pom : facts.poms) {
                String path = "/" + facts.label + "/" + pom.file().replace('\\', '/');
                if (!asked.isEmpty() && !path.contains("/" + asked + "/")) {
                    continue;
                }
                found.add(new Ref(facts.label, pom.file(), pom.line(),
                    (pom.managed() ? "manages " : "declares ") + pom.groupId() + ":"
                        + pom.artifactId()
                        + (pom.version() == null || pom.version().isBlank()
                            ? "" : ":" + pom.version())));
            }
        }
        found.sort(Comparator.comparing(Ref::file).thenComparingInt(Ref::line));
        return found;
    }

    // ---------------------------------------------------------------------------------------

    /** Deterministic to the last place: two runs over the same roots must agree exactly. */
    private static List<Ref> cut(List<Ref> found) {
        found.sort(Comparator.comparing(Ref::file).thenComparingInt(Ref::line)
            .thenComparing(Ref::detail));
        List<Ref> unique = new ArrayList<>();
        String previous = null;
        for (Ref ref : found) {
            String key = ref.file() + ":" + ref.line() + ":" + ref.detail();
            if (!key.equals(previous)) {
                unique.add(ref);
                previous = key;
            }
        }
        return unique.size() > MAX_RESULTS ? List.copyOf(unique.subList(0, MAX_RESULTS))
            : List.copyOf(unique);
    }

    /** Every capitalised dotted-or-simple identifier in a question that this index actually knows. */
    public List<String> namesKnownIn(String text) {
        if (text == null) {
            return List.of();
        }
        Set<String> known = new LinkedHashSet<>();
        for (String token : text.split("[^A-Za-z0-9_.]+")) {
            if (token.length() < 3) {
                continue;
            }
            String simple = token.contains(".")
                ? token.substring(token.lastIndexOf('.') + 1) : token;
            if (simple.isEmpty() || !Character.isUpperCase(simple.charAt(0))
                || simple.equals(simple.toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (!resolve(token).isEmpty()) {
                known.add(token);
            } else if (!resolve(simple).isEmpty()) {
                known.add(simple);
            }
        }
        return List.copyOf(known);
    }

    /**
     * Every METHOD this text names that the index has seen called.
     *
     * <p>Deliberately narrow. A method name is a lowercase word, and a question is full of
     * lowercase words — matching every one of them against a call graph would answer "how do I get
     * the root object" with every call to anything named {@code get}. So a word counts as naming a
     * method only when it is written as one: with parentheses after it, or in camelCase with an
     * interior capital. A question that does neither names no method, and this returns nothing,
     * which is the honest answer rather than a noisy one.
     */
    public List<String> methodNamesKnownIn(String text) {
        if (text == null) {
            return List.of();
        }
        Set<String> named = new LinkedHashSet<>();
        for (String raw : text.split("[^A-Za-z0-9_.()#]+")) {
            boolean called = raw.contains("(");
            String token = raw.replace("()", "").replace("(", "");
            if (token.contains("#")) {
                token = token.substring(token.lastIndexOf('#') + 1);
            } else if (token.contains(".")) {
                token = token.substring(token.lastIndexOf('.') + 1);
            }
            if (token.length() < 3 || !Character.isLowerCase(token.charAt(0))) {
                continue;
            }
            boolean camel = false;
            for (int at = 1; at < token.length(); at++) {
                if (Character.isUpperCase(token.charAt(at))) {
                    camel = true;
                    break;
                }
            }
            if ((called || camel) && callsByTarget.containsKey(token)) {
                named.add(token);
            }
        }
        return List.copyOf(named);
    }

    /** Every artifactId this index knows a build file declares, that this text names. */
    public List<String> artifactsNamedIn(String text) {
        if (text == null) {
            return List.of();
        }
        Set<String> declared = new LinkedHashSet<>();
        for (SemanticFacts.RootFacts facts : roots) {
            for (SemanticFacts.PomFact pom : facts.poms) {
                declared.add(pom.artifactId());
            }
        }
        Set<String> named = new LinkedHashSet<>();
        for (String raw : text.split("[^A-Za-z0-9_.:-]+")) {
            String token = raw.contains(":") ? raw.substring(raw.lastIndexOf(':') + 1) : raw;
            if (declared.contains(token)) {
                named.add(token);
            }
        }
        return List.copyOf(named);
    }
}
