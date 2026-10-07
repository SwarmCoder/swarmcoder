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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which production code of a tree the application can arrive at, read from the object graph: no
 * model, no build, nothing run.
 *
 * <h2>The runs this exists because of</h2>
 *
 * <p>Seven stories of one small web application were delivered and accepted (the last was run 88,
 * 2026-10-05). The owner opened the application and saw the placeholder pages it started with.
 * The runs had added three screen classes that nothing references; the routed page still returned
 * its placeholder; no existing source file had been changed. The acceptance tests called the
 * server's service, so every check was green on code no user can reach. Design, plan, test
 * author, judge and final acceptance all passed it, because nothing in the product asked "is what
 * was added connected to anything".
 *
 * <h2>The question, and how it is answered</h2>
 *
 * <p>A file of production code is <b>reachable</b> when it is an entry point or when a reachable
 * file uses a type it declares. The uses are the compiler's ({@link LstReader}: the resolved types
 * each file names), so a type mentioned in a comment or a string is not used, and a test's use
 * does not count because test files are not part of the walk.
 *
 * <p><b>Entry points are learned from the project, not from a list of frameworks.</b> A file is
 * one when it
 * <ol>
 *   <li>declares a {@code main(String[])};</li>
 *   <li>declares a type that a build file or a non-Java file of a main source tree names by its
 *       full name (a service-loader file, a deployment descriptor, a configuration file);</li>
 *   <li>carries an annotation that this project's own framework-discovered types carry; or</li>
 *   <li>has a supertype that this project's own framework-discovered types have, when those
 *       carry no annotation at all.</li>
 * </ol>
 *
 * <p>"Framework-discovered" is established from the code that was there before the run: a
 * pre-existing production file that no other pre-existing production file uses, and that neither
 * rule 1 nor rule 2 explains, is alive only because something outside the source finds it. What
 * it carries that could be found by - its type's annotations, else the annotations inside it, else
 * its supertypes, leaving out everything in {@code java.*} - is what discovery in this project
 * looks like. A routed page with a route annotation and no caller teaches the route annotation; a
 * bean that is only ever injected by its interface teaches its scope annotation. A new class with
 * neither a user nor such a mark is an orphan.
 *
 * <h2>What this cannot see</h2>
 *
 * <ul>
 *   <li><b>Files, not members.</b> A reachable class with a method nothing calls, a screen with no
 *       button for an action, a button whose handler does nothing: not seen. Members are not
 *       judged because the call graph has no receiver for interface dispatch, and accessors used
 *       by serialisation, templates and reflection have no caller in source; a hard stop on those
 *       would be wrong too often.</li>
 *   <li><b>A use is not a use by the user.</b> A type that reachable code names in a field it
 *       never reads counts as reached.</li>
 *   <li><b>Dead code that was already there excuses the same kind of dead code.</b> A pre-existing
 *       unused class teaches its annotation or supertype as "discovered", and a new class carrying
 *       it passes.</li>
 *   <li><b>A library's public surface.</b> Types meant for callers outside the project have no
 *       user inside it. A source root in which more than half of the pre-existing files are
 *       already unreachable is taken for one and not judged; a library with fewer such files
 *       gets a wrong objection, and the switch below is the way out.</li>
 *   <li><b>Reflection by a name that is built at run time</b>, and names in files outside the
 *       build files and main source trees.</li>
 *   <li><b>Other languages.</b> Only Java sources are in the graph. A tree whose graph could not
 *       be built, or built only in part, is not judged at all ({@link Status#UNDETERMINED}).</li>
 * </ul>
 *
 * <p>{@code -Dswarmcoder.verify.unreachableAddedCode=off} switches every use of this off.
 */
public final class ReachableCode {

    private static final Logger log = LoggerFactory.getLogger(ReachableCode.class);

    public static final String SWITCH = "swarmcoder.verify.unreachableAddedCode";

    /** How long one tree's parse may take; a tree that takes longer is not judged. */
    private static final long BUDGET_MILLIS = 3 * 60 * 1000L;
    private static final long MAX_NAMING_FILE_BYTES = 512 * 1024L;
    private static final int MAX_NAMING_FILES = 4_000;
    private static final int MAX_NAMED = 12;

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle",
        "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
    private static final Set<String> SKIP_DIRS =
        Set.of("target", "build", "out", "node_modules", ".git", ".idea", ".gradle", "bin");

    /** A dotted or slashed name of at least two parts, as a resource would write a type. */
    private static final Pattern QUALIFIED =
        Pattern.compile("[A-Za-z_][\\w$]*(?:[./][A-Za-z_$][\\w$]*)+");

    private ReachableCode() {
    }

    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on").strip());
    }

    public enum Status { ALL_REACHABLE, UNREACHABLE, UNDETERMINED }

    /**
     * One added file nothing reaches.
     *
     * @param types         the types it declares, simple names, outermost first
     * @param usedByNothing false when other production code uses it but that code is itself
     *                      unreachable
     */
    public record Orphan(String file, List<String> types, boolean usedByNothing) {
    }

    /**
     * @param orphans    the added files nothing reaches, by path
     * @param note       why nothing was judged, or what was left out; "" otherwise
     * @param discovered how discovery looks in this project: annotation and supertype names
     */
    public record Finding(Status status, List<Orphan> orphans, String note,
                          List<String> discovered) {

        public boolean unreachable() {
            return status == Status.UNREACHABLE;
        }

        /** The same finding about fewer files: a candidate answers for what it added itself. */
        public Finding only(Predicate<String> file) {
            if (status != Status.UNREACHABLE) {
                return this;
            }
            List<Orphan> kept = orphans.stream().filter(o -> file.test(o.file())).toList();
            return new Finding(kept.isEmpty() ? Status.ALL_REACHABLE : Status.UNREACHABLE, kept,
                note, discovered);
        }
    }

    /** One production file as the graph holds it. */
    private record Node(String file, List<String> types, Set<String> typeAnnotations,
                        Set<String> fileAnnotations, Set<String> supertypes, boolean hasMain,
                        Set<String> uses) {
    }

    /**
     * The graph of one tree on disk. Building it parses the tree once; every question after that
     * is answered from memory.
     */
    public static final class Graph {

        private final String undetermined;
        private final Map<String, Node> nodes = new TreeMap<>();
        /** A file to the production files that use a type it declares. */
        private final Map<String, Set<String>> usedBy = new LinkedHashMap<>();
        private final Set<String> namedOutsideJava = new LinkedHashSet<>();

        private Graph(String undetermined) {
            this.undetermined = undetermined;
        }

        /** False when the tree could not be read well enough to say anything about it. */
        public boolean determined() {
            return undetermined.isEmpty();
        }

        /** Why not, in a sentence; "" when {@link #determined()}. */
        public String note() {
            return undetermined;
        }

        /** True when the graph holds this path as a production source file declaring a type. */
        public boolean holds(String file) {
            return nodes.containsKey(normalise(file));
        }

        /**
         * Which of the files {@code addedByTheRun} accepts nothing reaches. Everything else in
         * the tree is the code that was there before, and is what discovery is learned from.
         */
        public Finding judge(Predicate<String> addedByTheRun) {
            if (!determined()) {
                return new Finding(Status.UNDETERMINED, List.of(), undetermined, List.of());
            }
            Walk walk = walk(addedByTheRun == null ? file -> false : addedByTheRun);
            if (walk.anchors.isEmpty()) {
                return new Finding(Status.UNDETERMINED, List.of(), "the tree has no entry point "
                    + "this can recognise (no main method, no type named in a build or resource "
                    + "file, no discovered type), so what is reachable in it cannot be said",
                    List.of());
            }
            List<Orphan> orphans = new ArrayList<>();
            Set<String> leftOut = new TreeSet<>();
            for (Node node : nodes.values()) {
                if (!walk.added.contains(node.file()) || walk.reachable.contains(node.file())) {
                    continue;
                }
                String root = sourceRootOf(node.file());
                if (walk.surfaceRoots.contains(root)) {
                    leftOut.add(root);
                    continue;
                }
                orphans.add(new Orphan(node.file(), simpleNames(node.types()),
                    usedBy.getOrDefault(node.file(), Set.of()).isEmpty()));
            }
            String note = leftOut.isEmpty() ? "" : "not judged, because more than half of the "
                + "code already there is used by nothing in this project (a library's surface): "
                + String.join(", ", leftOut);
            return new Finding(orphans.isEmpty() ? Status.ALL_REACHABLE : Status.UNREACHABLE,
                List.copyOf(orphans), note, walk.discovered());
        }

        /**
         * The reachable production files, as the tree stands, nothing counted as added. Empty
         * when the graph is not {@link #determined()} or has no entry point.
         */
        public Set<String> reachableFiles() {
            return determined() ? Set.copyOf(walk(file -> false).reachable) : Set.of();
        }

        /** The entry-point files, with the same reading. */
        public Set<String> entryPointFiles() {
            return determined() ? Set.copyOf(walk(file -> false).anchors) : Set.of();
        }

        /**
         * Why a file counts as reached, in a line: what makes it an entry point, or which
         * production files use it. For a log line or a person asking; "" for an unknown file.
         */
        public String whyReached(String path) {
            Node node = nodes.get(normalise(path));
            if (node == null || !determined()) {
                return "";
            }
            Walk walk = walk(file -> false);
            List<String> why = new ArrayList<>();
            if (node.hasMain()) {
                why.add("declares main");
            }
            if (namedOutsideJava.contains(node.file())) {
                why.add("named in a build or resource file");
            }
            for (String annotation : walk.annotations) {
                if (node.typeAnnotations().contains(annotation)
                        || node.fileAnnotations().contains(annotation)) {
                    why.add("carries @" + simpleName(annotation));
                }
            }
            for (String supertype : walk.supertypes) {
                if (node.supertypes().contains(supertype)) {
                    why.add("is a " + simpleName(supertype));
                }
            }
            Set<String> users = usedBy.getOrDefault(node.file(), Set.of());
            if (!users.isEmpty()) {
                why.add("used by " + String.join(", ", users));
            }
            return (walk.reachable.contains(node.file()) ? "reached: " : "not reached: ")
                + String.join("; ", why);
        }

        /**
         * How code on one side of a project can show what a change to {@code file} does: the
         * production files {@code user} accepts that use a type the file declares, or that use
         * a type one of the file's types IS - an interface in a shared module, say, whose
         * implementation the file holds, so the user calls into the file through it (live run
         * 89, DEVELOPER_CORRECTIONS section 64: a screen drawn in the browser from a descriptor
         * the server's service returns).
         *
         * @return one line a connection, at most {@code MAX_NAMED}; empty when nothing connects
         *         them, the graph does not hold the file, or it is not {@link #determined()}
         */
        public List<String> usersThroughItsTypes(String file, Predicate<String> user) {
            Node node = nodes.get(normalise(file));
            if (node == null || !determined() || user == null) {
                return List.of();
            }
            List<String> found = new ArrayList<>();
            for (String by : usedBy.getOrDefault(node.file(), Set.of())) {
                if (user.test(by) && found.size() < MAX_NAMED) {
                    found.add(by + " uses " + String.join(", ", simpleNames(node.types())));
                }
            }
            for (String supertype : node.supertypes()) {
                String declaredIn = fileDeclaring(supertype);
                if (declaredIn == null) {
                    continue;
                }
                for (String by : usedBy.getOrDefault(declaredIn, Set.of())) {
                    if (user.test(by) && found.size() < MAX_NAMED) {
                        found.add(by + " uses " + simpleName(supertype) + ", which "
                            + String.join(", ", simpleNames(node.types())) + " is");
                    }
                }
            }
            return List.copyOf(found);
        }

        private String fileDeclaring(String type) {
            for (Node node : nodes.values()) {
                if (node.types().contains(type)) {
                    return node.file();
                }
            }
            return null;
        }

        /** The simple names of the annotations discovery is learned to go by in this tree. */
        public Set<String> discoveryAnnotationNames() {
            if (!determined()) {
                return Set.of();
            }
            Set<String> names = new TreeSet<>();
            for (String annotation : walk(file -> false).annotations) {
                names.add(simpleName(annotation));
            }
            return names;
        }

        /**
         * The source roots whose code uses, directly or through others, code of this one - this
         * root included. Empty when the tree holds no production file under it (a module the
         * plan is about to create: nothing is known about it).
         */
        public Set<String> rootsUsing(String sourceRoot) {
            Set<String> found = new LinkedHashSet<>();
            boolean known = false;
            Map<String, Set<String>> usersOfRoot = new LinkedHashMap<>();
            for (Node node : nodes.values()) {
                String root = sourceRootOf(node.file());
                known |= root.equals(sourceRoot);
                for (String used : node.uses()) {
                    usersOfRoot.computeIfAbsent(sourceRootOf(used), k -> new LinkedHashSet<>())
                        .add(root);
                }
            }
            if (!known) {
                return found;
            }
            Deque<String> open = new ArrayDeque<>(List.of(sourceRoot));
            found.add(sourceRoot);
            while (!open.isEmpty()) {
                for (String user : usersOfRoot.getOrDefault(open.poll(), Set.of())) {
                    if (found.add(user)) {
                        open.add(user);
                    }
                }
            }
            return found;
        }

        /** The source roots taken for a library's surface, which are not judged. */
        public Set<String> surfaceRoots() {
            return determined() ? Set.copyOf(walk(file -> false).surfaceRoots) : Set.of();
        }

        private Walk walk(Predicate<String> addedByTheRun) {
            Walk walk = new Walk();
            for (Node node : nodes.values()) {
                if (addedByTheRun.test(node.file())) {
                    walk.added.add(node.file());
                }
            }
            // What discovery looks like here: read off the code that was there before the run
            // and that nothing there uses.
            for (Node node : nodes.values()) {
                if (walk.added.contains(node.file()) || node.hasMain()
                        || namedOutsideJava.contains(node.file())) {
                    continue;
                }
                boolean used = false;
                for (String user : usedBy.getOrDefault(node.file(), Set.of())) {
                    if (!walk.added.contains(user)) {
                        used = true;
                        break;
                    }
                }
                if (used) {
                    continue;
                }
                if (!node.typeAnnotations().isEmpty()) {
                    walk.annotations.addAll(node.typeAnnotations());
                } else if (!node.fileAnnotations().isEmpty()) {
                    walk.annotations.addAll(node.fileAnnotations());
                } else {
                    walk.supertypes.addAll(node.supertypes());
                }
            }
            for (Node node : nodes.values()) {
                if (node.hasMain() || namedOutsideJava.contains(node.file())
                        || intersects(node.typeAnnotations(), walk.annotations)
                        || intersects(node.fileAnnotations(), walk.annotations)
                        || intersects(node.supertypes(), walk.supertypes)) {
                    walk.anchors.add(node.file());
                }
            }
            Deque<String> open = new ArrayDeque<>(walk.anchors);
            walk.reachable.addAll(walk.anchors);
            while (!open.isEmpty()) {
                Node node = nodes.get(open.poll());
                for (String used : node.uses()) {
                    if (walk.reachable.add(used)) {
                        open.add(used);
                    }
                }
            }
            Map<String, int[]> byRoot = new LinkedHashMap<>();
            for (Node node : nodes.values()) {
                if (walk.added.contains(node.file())) {
                    continue;
                }
                int[] counts = byRoot.computeIfAbsent(sourceRootOf(node.file()), k -> new int[2]);
                counts[0]++;
                if (!walk.reachable.contains(node.file())) {
                    counts[1]++;
                }
            }
            for (Map.Entry<String, int[]> root : byRoot.entrySet()) {
                if (root.getValue()[1] * 2 > root.getValue()[0]) {
                    walk.surfaceRoots.add(root.getKey());
                }
            }
            return walk;
        }
    }

    private static final class Walk {
        final Set<String> added = new LinkedHashSet<>();
        final Set<String> annotations = new TreeSet<>();
        final Set<String> supertypes = new TreeSet<>();
        final Set<String> anchors = new LinkedHashSet<>();
        final Set<String> reachable = new LinkedHashSet<>();
        final Set<String> surfaceRoots = new TreeSet<>();

        List<String> discovered() {
            List<String> all = new ArrayList<>();
            annotations.forEach(a -> all.add("@" + simpleName(a)));
            supertypes.forEach(s -> all.add(simpleName(s)));
            return List.copyOf(all);
        }
    }

    // -------------------------------------------------------------------------------------------

    /** The graph of the tree at {@code tree}. Never throws: a tree that cannot be read says so. */
    public static Graph of(Path tree) {
        if (tree == null || !Files.isDirectory(tree)) {
            return new Graph("there is no tree to read");
        }
        SemanticFacts.RootFacts facts;
        try {
            facts = LstReader.readTree(tree, BUDGET_MILLIS);
        } catch (Throwable e) {                                            // noqa
            return new Graph("the tree could not be parsed (" + e + ")");
        }
        if (!facts.degraded.isEmpty()) {
            // A file that did not parse may hold the one use that makes a type reachable.
            return new Graph("the tree was parsed only in part (" + facts.degraded + "), and a "
                + "use in what is missing would be taken for no use");
        }
        if (facts.types.isEmpty()) {
            return new Graph("the tree declares no Java type");
        }
        Graph graph = new Graph("");
        Map<String, List<SemanticFacts.TypeFact>> typesOfFile = new LinkedHashMap<>();
        Map<String, String> fileOfType = new LinkedHashMap<>();
        for (SemanticFacts.TypeFact type : facts.types) {
            String file = normalise(type.file());
            if (!isProduction(file)) {
                continue;
            }
            typesOfFile.computeIfAbsent(file, k -> new ArrayList<>()).add(type);
            fileOfType.put(type.fqn(), file);
        }
        Map<String, Set<String>> annotationsInFile = new LinkedHashMap<>();
        for (SemanticFacts.UseFact use : facts.uses) {
            if ("annotation".equals(use.kind()) && notTheLanguages(use.fqn())) {
                annotationsInFile.computeIfAbsent(normalise(use.file()),
                    k -> new LinkedHashSet<>()).add(use.fqn());
            }
        }
        Map<String, Set<String>> usesOfFile = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : facts.typesInFile.entrySet()) {
            String file = normalise(entry.getKey());
            if (!typesOfFile.containsKey(file)) {
                continue;
            }
            Set<String> uses = new LinkedHashSet<>();
            for (String type : entry.getValue()) {
                String declaredIn = fileOfType.get(type);
                if (declaredIn != null && !declaredIn.equals(file)) {
                    uses.add(declaredIn);
                    graph.usedBy.computeIfAbsent(declaredIn, k -> new LinkedHashSet<>()).add(file);
                }
            }
            usesOfFile.put(file, uses);
        }
        for (Map.Entry<String, List<SemanticFacts.TypeFact>> entry : typesOfFile.entrySet()) {
            List<String> types = new ArrayList<>();
            Set<String> typeAnnotations = new LinkedHashSet<>();
            Set<String> supertypes = new LinkedHashSet<>();
            boolean hasMain = false;
            for (SemanticFacts.TypeFact type : entry.getValue()) {
                types.add(type.fqn());
                type.annotations().stream().filter(ReachableCode::notTheLanguages)
                    .forEach(typeAnnotations::add);
                type.assignableTo().stream().filter(ReachableCode::notTheLanguages)
                    .filter(parent -> !entry.getKey().equals(fileOfType.get(parent)))
                    .forEach(supertypes::add);
                hasMain |= type.shape().contains("void main(java.lang.String[])");
            }
            graph.nodes.put(entry.getKey(), new Node(entry.getKey(), List.copyOf(types),
                typeAnnotations, annotationsInFile.getOrDefault(entry.getKey(), Set.of()),
                supertypes, hasMain, usesOfFile.getOrDefault(entry.getKey(), Set.of())));
        }
        readNamesOutsideJava(tree, fileOfType, graph.namedOutsideJava);
        log.info("reachable-code graph of {}: {} production file(s), {} named in build or "
            + "resource files, parsed in {} ms", tree, graph.nodes.size(),
            graph.namedOutsideJava.size(), facts.buildMillis);
        return graph;
    }

    /**
     * The files declaring a type that a build file, or a non-Java file of a main source tree,
     * names in full: {@code com.x.Foo}, {@code com/x/Foo.class}, {@code com.x.Foo#bar}, and a
     * service-loader file's own name.
     */
    private static void readNamesOutsideJava(Path tree, Map<String, String> fileOfType,
                                             Set<String> into) {
        Map<String, String> byDottedName = new LinkedHashMap<>();
        fileOfType.forEach((type, file) -> byDottedName.put(type.replace('$', '.'), file));
        List<Path> naming = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(tree)) {
            walk.filter(Files::isRegularFile)
                .filter(file -> namesTypes(normalise(tree.relativize(file).toString())))
                .limit(MAX_NAMING_FILES)
                .forEach(naming::add);
        } catch (Exception e) {                                            // noqa
            log.debug("could not walk {} for build and resource files: {}", tree, e.toString());
        }
        for (Path file : naming) {
            String text;
            try {
                if (Files.size(file) > MAX_NAMING_FILE_BYTES) {
                    continue;
                }
                text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            } catch (Exception e) {                                        // noqa
                continue;
            }
            Matcher matcher = QUALIFIED.matcher(file.getFileName() + "\n" + text);
            while (matcher.find()) {
                String name = matcher.group().replace('/', '.').replace('$', '.');
                while (true) {
                    String declaredIn = byDottedName.get(name);
                    if (declaredIn != null) {
                        into.add(declaredIn);
                        break;
                    }
                    int dot = name.lastIndexOf('.');
                    if (dot < 0) {
                        break;
                    }
                    name = name.substring(0, dot);
                }
            }
        }
    }

    /** A build file anywhere, or a file other than Java source inside a main source tree. */
    private static boolean namesTypes(String path) {
        for (String segment : path.split("/")) {
            if (SKIP_DIRS.contains(segment)) {
                return false;
            }
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (BUILD_FILES.contains(name)) {
            return true;
        }
        return !name.endsWith(".java") && ("/" + path).contains("/src/main/");
    }

    // -------------------------------------------------------------------------------------------

    /**
     * The verdict sentence for added files nothing reaches, or null when there are none.
     *
     * @param whose "the candidate adds" or "this run adds": who is being told
     */
    public static String objection(Finding finding, String whose) {
        if (finding == null || !finding.unreachable()) {
            return null;
        }
        List<Orphan> orphans = finding.orphans();
        StringBuilder named = new StringBuilder();
        for (Orphan orphan : orphans.subList(0, Math.min(MAX_NAMED, orphans.size()))) {
            named.append(named.length() == 0 ? "" : ", ")
                .append(orphan.types().isEmpty() ? orphan.file() : orphan.types().get(0))
                .append(" (").append(orphan.file())
                .append(orphan.usedByNothing() ? "" : ", used only by other unreachable code")
                .append(')');
        }
        if (orphans.size() > MAX_NAMED) {
            named.append(" and ").append(orphans.size() - MAX_NAMED).append(" more");
        }
        boolean one = orphans.size() == 1;
        return whose + " production code that nothing in the application can reach: " + named
            + ". No code that the application's entry points lead to uses "
            + (one ? "it" : "them") + ", so nobody using the application can ever arrive at "
            + (one ? "it" : "them") + " - a test calling " + (one ? "it" : "them")
            + " directly does not change that. In this project code is reached from a main "
            + "method, from a type named in a build or resource file"
            + (finding.discovered().isEmpty() ? "" : ", or by being found by the framework ("
                + String.join(", ", finding.discovered().subList(0,
                    Math.min(8, finding.discovered().size()))) + ")")
            + ". Connect " + (one ? "it" : "each of them") + ": change the existing code the user "
            + "already arrives at so that it opens, calls or registers what was added (a page that "
            + "still shows what it showed before the story is the usual place). If the file that "
            + "must change is outside what the task may write, say so in your report - that is a "
            + "fault in the plan, not something to work around";
    }

    // -------------------------------------------------------------------------------------------

    /**
     * What a plan must be told when it can only produce unreachable code, or null.
     *
     * <p>A plan is objected to when it writes a new production source file and no task may change
     * anything the application already reaches from which that file could be used: a reachable
     * source file, or a non-Java file of a main source tree (where a type can be registered by
     * name), in the new file's own source root or in one whose code already uses that root's
     * code. A new file whose task names one of the project's discovery annotations is taken to
     * be found by the framework and is not counted. Such a plan can be carried out perfectly
     * and still deliver nothing a user can arrive at.
     *
     * <p>Build files do not count as a place to connect from: every task is given its module's
     * build file whether it needs it or not.
     *
     * @param graph         the tree the run starts from
     * @param tasks         each task's title, write set and everything it is told (instructions
     *                      and contracts), in plan order
     * @param exists        whether a repository path is a file of the start tree
     */
    public static String planObjection(Graph graph, List<PlannedTask> tasks,
                                       Predicate<String> exists) {
        if (graph == null || !graph.determined() || tasks == null || tasks.isEmpty()) {
            return null;
        }
        Set<String> reachable = graph.reachableFiles();
        if (reachable.isEmpty()) {
            return null;
        }
        Set<String> surface = graph.surfaceRoots();
        Set<String> discovery = graph.discoveryAnnotationNames();
        // Where the plan may change something the application already reaches, by source root.
        Set<String> footholds = new LinkedHashSet<>();
        List<String> planned = new ArrayList<>();
        for (PlannedTask task : tasks) {
            boolean saysDiscovered = false;
            for (String annotation : discovery) {
                if (Pattern.compile("@" + Pattern.quote(annotation) + "\\b")
                        .matcher(task.told() == null ? "" : task.told()).find()) {
                    saysDiscovered = true;
                    break;
                }
            }
            for (String entry : task.writeSet() == null ? List.<String>of() : task.writeSet()) {
                String path = normalise(entry);
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (path.endsWith("/") || path.contains("*") || !name.contains(".")) {
                    // A folder or a pattern: what will be added under it is not known, but
                    // everything reachable under it may be changed.
                    String prefix = path.contains("*") ? path.substring(0, path.indexOf('*'))
                        : path.endsWith("/") ? path : path + "/";
                    for (String file : reachable) {
                        if (file.startsWith(prefix)) {
                            footholds.add(sourceRootOf(file));
                        }
                    }
                    continue;
                }
                if (!path.endsWith(".java")) {
                    int main = ("/" + path).lastIndexOf("/src/main/");
                    if (!BUILD_FILES.contains(name) && main >= 0) {
                        // a resource: a type can be registered there by name
                        footholds.add(path.substring(0, main) + "src/main/java/");
                    }
                    continue;
                }
                if (!isProduction(path)) {
                    continue;
                }
                if (exists.test(path)) {
                    if (reachable.contains(path)) {
                        footholds.add(sourceRootOf(path));
                    }
                } else if (!saysDiscovered && !surface.contains(sourceRootOf(path))) {
                    planned.add(path);
                }
            }
        }
        // A new file can be connected from its own source root, or from one whose code already
        // uses that root's code (a client or a server module for a shared one).
        List<String> added = new ArrayList<>();
        for (String path : planned) {
            Set<String> from = graph.rootsUsing(sourceRootOf(path));
            if (!from.isEmpty() && !intersects(from, footholds)) {
                added.add(path);
            }
        }
        if (added.isEmpty()) {
            return null;
        }
        // The nearest places a user already arrives at: entry points of the same source roots.
        Set<String> roots = new LinkedHashSet<>();
        added.forEach(path -> roots.add(sourceRootOf(path)));
        List<String> near = new ArrayList<>();
        List<String> far = new ArrayList<>();
        for (String entry : new TreeSet<>(graph.entryPointFiles())) {
            (roots.contains(sourceRootOf(entry)) ? near : far).add(entry);
        }
        near.addAll(far);
        return "the plan adds new production source files ("
            + String.join(", ", added.subList(0, Math.min(MAX_NAMED, added.size())))
            + (added.size() > MAX_NAMED ? " and " + (added.size() - MAX_NAMED) + " more" : "")
            + ") and no task may change a file the application already reaches from which they "
            + "could be used, so nothing "
            + "could ever open, call or register what is added: the story would be built and no "
            + "user could arrive at it. Put the existing file the new code is reached from into "
            + "the write set of the task that finishes the feature, and say in that task's "
            + "instructions what it must change there"
            + (near.isEmpty() ? "" : " (where the application is entered today: "
                + String.join(", ", near.subList(0, Math.min(6, near.size()))) + ")")
            + (discovery.isEmpty() ? "" : "; or, when a new type is found by the framework "
                + "itself, name the annotation it carries in its task (in this project: "
                + String.join(", ", discovery.stream().map(a -> "@" + a).limit(8).toList()) + ")");
    }

    /**
     * One task of a plan, as far as this needs it.
     *
     * @param told the task's instructions and the contracts it delivers, as one text
     */
    public record PlannedTask(String title, Collection<String> writeSet, String told) {
    }

    // -------------------------------------------------------------------------------------------

    /** True for a Java source file that ships: not a test tree, not a package or module note. */
    public static boolean isProduction(String path) {
        String p = "/" + normalise(path);
        if (!p.endsWith(".java") || p.endsWith("/package-info.java")
                || p.endsWith("/module-info.java")) {
            return false;
        }
        int src = p.lastIndexOf("/src/");
        if (src >= 0) {
            String after = p.substring(src + 5);
            int slash = after.indexOf('/');
            String set = slash < 0 ? "" : after.substring(0, slash);
            // src/main/java ships; src/test/java, src/it/java and the like do not. A layout
            // with packages directly under src/ has no such folder and ships.
            if (after.startsWith(set + "/java/") && !set.equals("main")) {
                return false;
            }
        }
        return !p.contains("/test/java/");
    }

    /** The folder a file's packages start in, e.g. {@code app/src/main/java/}; "" when unknown. */
    static String sourceRootOf(String file) {
        String path = normalise(file);
        int at = ("/" + path).lastIndexOf("/src/main/java/");
        return at < 0 ? "" : path.substring(0, at + "src/main/java/".length());
    }

    private static boolean notTheLanguages(String type) {
        return type != null && !type.startsWith("java.") && !type.isBlank()
            // an annotation the classpath could not resolve is kept by its simple name; these
            // are the ones of java.lang, which mark nothing a framework finds
            && !Set.of("Override", "Deprecated", "SuppressWarnings", "SafeVarargs",
                "FunctionalInterface").contains(type);
    }

    private static boolean intersects(Set<String> one, Set<String> other) {
        for (String each : one) {
            if (other.contains(each)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> simpleNames(List<String> types) {
        return types.stream().map(ReachableCode::simpleName).toList();
    }

    private static String simpleName(String type) {
        return type.substring(Math.max(type.lastIndexOf('.'), type.lastIndexOf('$')) + 1);
    }

    private static String normalise(String path) {
        String p = path == null ? "" : path.strip().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }
}
