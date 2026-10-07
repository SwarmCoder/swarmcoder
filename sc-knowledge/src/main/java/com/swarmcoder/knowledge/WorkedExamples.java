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

import com.swarmcoder.domain.ApiContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The nearest worked example: for every type a task has to deliver, the existing file in this
 * codebase or its reference folders that most resembles it — chosen by SHAPE, not by keyword.
 *
 * <p><b>Why this exists.</b> Measured on 2026-09-03 in a harness with nothing but native tools and
 * the repository: given an unfamiliar framework, prose documentation and a search tool, the model
 * read 43 documents, disassembled the framework's jars with {@code javap} for 106 shell calls, and
 * over three runs and 467 turns wrote <b>zero files</b>. The same model, the same harness, the same
 * shaped task on plain Java it already knew, was writing at turn 5 and green in ten turns. The
 * missing thing is not reasoning and it is not context size — it is a piece of code in this
 * codebase's own idiom that the new code can be shaped after. The framework's own agent
 * instructions say the same thing in one line: copy the reference example's directory tree, keep
 * the structure, replace the domain code.
 *
 * <p><b>How "nearest" is decided, and why none of it names a framework.</b> A contract carries a
 * fully-qualified type name and the members an acceptance test will touch
 * ({@link ApiContract#typeName()}, {@link ApiContract#members()}). That is a SHAPE: so many fields
 * of such-and-such kinds, so many methods of such-and-such arities and return kinds, in a package
 * whose last segment says what role the type plays. Every Java file under the roots has the same
 * shape, read out of its source by {@link JavaSourceFacts}. The score is the agreement between the
 * two — role, form, members, name tokens — and nothing in it knows what a framework is.
 *
 * <p><b>One example, not four.</b> Scoring each contract independently picks the best file for each
 * and they come from four different projects, which teaches four different idioms that do not fit
 * together — measured: a data class from one, an enum from a second, a screen from a third, and
 * the persistence one from nowhere. So there is a second pass over PROJECTS. Every ancestor that
 * declares a build competes: the folder of examples, one example, one module of it. The folder
 * always has the highest total because it contains everything, and the smallest module always has
 * the fewest files, so neither total nor size decides it alone. What decides it is resemblance per
 * file read — how much of the task a project covers, how well, against how much of it a reader has
 * to hold at once — with each match's score CUBED, so that one near-exact counterpart outweighs
 * three vague ones. Then every winner is taken from inside that one project, one file per
 * contract, and the files those files reach come too.
 *
 * <p><b>The honest limit.</b> On a framework that ships a gallery of a dozen three-module CRUD
 * examples, the top four are within about 10% of each other, and any of them teaches a working
 * idiom. The mechanism narrows 681 files to a coherent example; it does not claim there is only
 * one right answer when there are four.
 */
public final class WorkedExamples {

    private static final Logger log = LoggerFactory.getLogger(WorkedExamples.class);

    /** Directories that never hold a worked example, only build output or history. */
    private static final Set<String> SKIP_DIRS = Set.of(
        "target", "build", "out", "bin", ".git", ".idea", ".gradle", "node_modules",
        ".swarmcoder", "generated-sources", "generated-test-sources");

    /** Files this big are generated or vendored; nobody learns an idiom from them. */
    private static final int MAX_EXAMPLE_BYTES = 60_000;

    /**
     * How many candidates per contract survive into the project-choosing pass.
     *
     * <p>Generous on purpose, and it was measured: at twelve, the shape scores are so flat — every
     * data class in a framework's examples resembles a data-class contract to within a few
     * hundredths — that the twelve best for one contract and the twelve best for another share no
     * project at all. The coherent example then never reaches the second pass and a module that
     * happens to be small wins. The second pass is where the real decision is made, so it should
     * see everything that plausibly resembles anything.
     */
    private static final int SHORTLIST = 250;

    /** A score below this is not a resemblance, it is the least bad of a bad set. */
    private static final double MIN_SCORE = 0.20;

    /**
     * How a design says a contract names a type the change TOUCHES rather than one it creates.
     *
     * <p>Written by the architect, which {@code ArchitectClient.EXISTING_CODE_CONTRACT_RULE} asks
     * it to do in as many words on a change to code that already exists: begin an existing type's
     * description with this, and a new one's with "NEW:". It is the one fact the chooser cannot
     * work out for itself — a file with the contracted name exists in both cases, and only the
     * design knows whether the task is about to write that file or to change it.
     *
     * <p>An unmarked contract behaves exactly as every contract behaved before this existed, so a
     * greenfield build is untouched and a design that ignores the instruction loses nothing it had.
     */
    static final String EXISTING_MARKER = "EXISTING:";

    /**
     * Low enough to keep a candidate in the project-choosing pass, too low to SHOW as "this is how
     * the codebase does it". An enum contract in a project with no enum in it scored 0.36 against
     * a data class, and presenting that as the thing to copy is worse than presenting nothing: the
     * contract's own type name and members already say more.
     */
    private static final double WORTH_SHOWING = 0.45;

    /**
     * The size at which a project stops being one worked example and starts being a library of
     * them. Used only to divide by, so nothing turns on the exact number: it sets how hard a
     * project has to work, in resemblance, to justify each further file a reader must hold.
     */
    private static final double TYPICAL_PROJECT_FILES = 25.0;

    /** How far a reference chases from a winner: implementation → commands → the store root. */
    private static final int MAX_HOPS = 3;

    /** How much of a project's own documentation counts toward the tie-break. */
    private static final int PROSE_CHARS = 6_000;

    /** What the rest of the example may cost. Roughly a dozen ordinary files. */
    private static final int NEIGHBOUR_BUDGET_BYTES = 60_000;

    private WorkedExamples() {}

    // ---------------------------------------------------------------------------------------
    // What a file is, structurally
    // ---------------------------------------------------------------------------------------

    /**
     * One Java file's shape.
     *
     * @param groups every ancestor below the root that declares a build (a {@code pom.xml},
     *               {@code build.gradle} or {@code package.json}), deepest first. A file belongs to
     *               all of them at once — the module, the example it is part of, the folder of
     *               examples — and which of those is "the worked example" is decided per task, not
     *               per file.
     * @param module the nearest ancestor declaring a build: whose dependency list a copy of this
     *               file would need
     */
    public record Shape(Path file, Path root, String rootLabel, List<Path> groups, Path module,
                        String packageName, String simpleName, List<String> imports,
                        boolean isInterface, boolean isEnum, boolean isAbstract,
                        List<String> annotations, List<JavaSourceFacts.Declared> members,
                        List<String> supertypes, List<String> frameworkFields, int bytes) {

        /** The last segment of the package — {@code model}, {@code api}, {@code server}, {@code store}. */
        public String role() {
            int dot = packageName.lastIndexOf('.');
            return dot < 0 ? packageName : packageName.substring(dot + 1);
        }

        public String fullName() {
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }

        public String relative() {
            try {
                return root.relativize(file).toString().replace('\\', '/');
            } catch (Exception e) {                                        // noqa
                return file.toString().replace('\\', '/');
            }
        }
    }

    /** One contract and the existing code that most resembles what it asks for. */
    public record Match(ApiContract contract, Shape example, double score, String why) {}

    /**
     * What the task should be shown.
     *
     * @param project    the chosen worked example's own project directory, or null when nothing
     *                   resembled anything
     * @param matches    one per contract that found a resemblance, in the contracts' own order
     * @param neighbours files inside the same project that a winner references and that are needed
     *                   to read the winners at all — the commands, the store root, the provider
     * @param modules    the build files of the winners' modules, for the dependency comparison
     */
    public record Selection(Path project, String projectLabel, List<Match> matches,
                            List<Shape> neighbours, List<Path> modules) {

        public boolean isEmpty() {
            return matches.isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reading the roots
    // ---------------------------------------------------------------------------------------

    /** Every Java file under these roots, as shapes. Cached by nothing: callers hold the result. */
    public static List<Shape> shapes(List<KnowledgeCurator.Root> roots) {
        List<Shape> all = new ArrayList<>();
        for (KnowledgeCurator.Root root : roots == null ? List.<KnowledgeCurator.Root>of() : roots) {
            if (root == null || root.path() == null || !Files.isDirectory(root.path())) {
                continue;
            }
            collect(root, all);
        }
        return all;
    }

    private static void collect(KnowledgeCurator.Root root, List<Shape> into) {
        Path base = root.path();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return SKIP_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!file.toString().endsWith(".java") || attrs.size() > MAX_EXAMPLE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    Shape shape = shapeOf(root, file, (int) attrs.size());
                    if (shape != null) {
                        into.add(shape);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("could not walk {}: {}", base, e.getMessage());
        }
    }

    static Shape shapeOf(KnowledgeCurator.Root root, Path file, int bytes) {
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {                                            // noqa
            return null;
        }
        JavaSourceFacts facts = JavaSourceFacts.of(source);
        JavaOutline outline = JavaOutline.of(source);
        JavaOutline.Member primary = outline.primaryType();
        if (primary == null) {
            return null;
        }
        String header = primary.header();
        String bare = JavaOutline.stripAnnotations(header);
        List<Path> groups = buildsAbove(file, root.path());
        Path module = groups.isEmpty() ? null : groups.get(0);
        return new Shape(file, root.path(), root.label(), groups, module,
            facts.packageName(), primary.name(), facts.imports(),
            bare.contains("interface "), bare.contains("enum "), bare.contains("abstract "),
            annotationsOf(header), facts.membersOf(primary.name()), supertypesOf(bare),
            frameworkFieldsOf(primary, facts), bytes);
    }

    /** Type-level annotation names, {@code @} and arguments stripped: {@code DataModel}. */
    static List<String> annotationsOf(String header) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < header.length(); i++) {
            if (header.charAt(i) != '@') {
                continue;
            }
            int j = i + 1;
            while (j < header.length()
                && (Character.isJavaIdentifierPart(header.charAt(j)) || header.charAt(j) == '.')) {
                j++;
            }
            String name = header.substring(i + 1, j);
            int dot = name.lastIndexOf('.');
            if (!name.isEmpty()) {
                found.add(dot < 0 ? name : name.substring(dot + 1));
            }
            i = j;
        }
        return List.copyOf(found);
    }

    /** The simple names after {@code extends} and {@code implements}, in order. */
    static List<String> supertypesOf(String bareHeader) {
        List<String> found = new ArrayList<>();
        for (String keyword : List.of("extends ", "implements ")) {
            int at = bareHeader.indexOf(keyword);
            if (at < 0) {
                continue;
            }
            String tail = bareHeader.substring(at + keyword.length());
            for (String other : List.of(" extends ", " implements ", "{")) {
                int end = tail.indexOf(other);
                if (end >= 0) {
                    tail = tail.substring(0, end);
                }
            }
            for (String name : tail.split(",")) {
                String simple = name.strip();
                int generic = simple.indexOf('<');
                if (generic >= 0) {
                    simple = simple.substring(0, generic);
                }
                int dot = simple.lastIndexOf('.');
                if (dot >= 0) {
                    simple = simple.substring(dot + 1);
                }
                if (!simple.isBlank()) {
                    found.add(simple.strip());
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * The example's fields whose type comes from OUTSIDE its own code — as written, annotations
     * included: {@code @Inject private ZeroZDbNode db;}
     *
     * <p>This is the framework wiring, and it is the part a model cannot derive from a contract or
     * find in prose. A contract says a service must have three methods; nothing in it says the
     * service reaches its data through an injected node of a type it has never heard of. The
     * example says exactly that, in one line, and the line is copyable.
     */
    static List<String> frameworkFieldsOf(JavaOutline.Member type, JavaSourceFacts facts) {
        List<String> own = new ArrayList<>();
        String root = facts.packageName();
        String[] parts = root.split("[.]");
        String prefix = parts.length <= 3 ? root : String.join(".", parts[0], parts[1], parts[2]);
        for (String imported : facts.imports()) {
            String owner = imported.startsWith("static ") ? imported.substring(7) : imported;
            if (!owner.startsWith(prefix) && !owner.startsWith("java.")) {
                own.add(JavaSourceFacts.importedSimpleName(imported));
            }
        }
        List<String> fields = new ArrayList<>();
        for (JavaOutline.Member member : type.children()) {
            if (member.kind() != JavaOutline.Kind.FIELD) {
                continue;
            }
            String text = JavaOutline.collapse(member.header());
            if (text.contains("static final")) {
                continue;
            }
            // The field's TYPE has to be the foreign one, not merely something in the line. An
            // earlier version asked whether any foreign name appeared anywhere in the declaration,
            // and copied the example's whole domain — `@NotBlank private String name;` counted,
            // because the validation annotation is a framework import. The skeleton then carried
            // a warehouse product's fields into a book, the annotation processor generated a
            // serializer for them, and the build failed on generated code nobody had written.
            String declared = JavaSourceFacts.fieldOrReturnType(member.header(), member.name(),
                false);
            String simple = declared.contains("<")
                ? declared.substring(0, declared.indexOf('<')) : declared;
            simple = simple.contains(".") ? simple.substring(simple.lastIndexOf('.') + 1) : simple;
            if (own.contains(simple.strip())) {
                fields.add(text.endsWith(";") ? text : text + ";");
            }
        }
        return List.copyOf(fields);
    }

    /** Names a build declares — {@code pom.xml}, {@code build.gradle}, {@code package.json}. */
    private static final List<String> BUILD_FILES =
        List.of("pom.xml", "build.gradle", "build.gradle.kts", "package.json");

    /**
     * Every ancestor below the root that declares a build, deepest first — and the root itself when
     * nothing below it declares one.
     *
     * <h2>Why the root is a candidate at all</h2>
     *
     * <p>These are the "projects" pass two chooses between: a knowledge root is somebody's folder of
     * examples, and each example inside it is a project of its own. Ancestors below the root are the
     * right answer for that shape, and the root is not — a gallery of twelve unrelated demos is not
     * one project to copy.
     *
     * <p><b>But a single-module repository has its build file AT the root, and nowhere else</b>, and
     * for one of those this returned an empty list for every single file. That is not "no preference
     * between projects"; it is <b>no projects</b>. Pass two groups the shortlist by these paths and
     * chooses the best group, so an empty list means an empty map, which means no group is chosen,
     * which means <b>no worked example is ever offered, whatever the contracts say</b>.
     *
     * <p><b>Measured, 2026-09-05, on jsoup — one Maven module, one pom.xml, at the root.</b> The
     * architect stated four contracts and the structural index found evidence for all four; the log
     * read "structural evidence from the semantic index for 4 of 4 contract(s)" and then "worked
     * example: null (value 0.0, of <b>0</b> candidate projects)". Nothing was wrong with the
     * contracts, the index, or the scoring. There was simply nowhere for a file to belong. Design
     * §2.1 states the opposite — that on a brownfield target with no reference folders the chooser
     * is entirely about the target — and it was not true for the commonest shape of target there is.
     *
     * <p>Greenfield never met this: the demo project is multi-module and so is the reference
     * checkout beside it, so every file always had at least one ancestor with a build file.
     *
     * <p><b>The root is added only when nothing below it declared a build</b>, so a multi-module
     * repository and a folder of examples both behave exactly as they did — this cannot change
     * which project is chosen anywhere it was already choosing one.
     */
    static List<Path> buildsAbove(Path file, Path root) {
        List<Path> found = new ArrayList<>();
        Path dir = file.getParent();
        while (dir != null && dir.startsWith(root) && !dir.equals(root)) {
            for (String build : BUILD_FILES) {
                if (Files.isRegularFile(dir.resolve(build))) {
                    found.add(dir);
                    break;
                }
            }
            dir = dir.getParent();
        }
        if (found.isEmpty()) {
            for (String build : BUILD_FILES) {
                if (Files.isRegularFile(root.resolve(build))) {
                    found.add(root);
                    break;
                }
            }
        }
        return List.copyOf(found);
    }

    // ---------------------------------------------------------------------------------------
    // Scoring one contract against one file
    // ---------------------------------------------------------------------------------------

    /** A contract read as a shape: what form it asks for and what members it promises. */
    /**
     * @param alreadyExists true when the design says this type is one the change TOUCHES rather
     *                      than one it creates — see {@link #EXISTING_MARKER}
     */
    record Wanted(String simpleName, String packageName, String role, List<String> nameTokens,
                  List<JavaSourceFacts.Declared> members, boolean allMethods, boolean allConstants,
                  boolean isTest, boolean alreadyExists) {

        static Wanted of(ApiContract contract) {
            String simple = contract.simpleTypeName();
            String pkg = contract.packageName();
            int dot = pkg.lastIndexOf('.');
            String role = dot < 0 ? pkg : pkg.substring(dot + 1);
            List<JavaSourceFacts.Declared> members = new ArrayList<>();
            boolean allMethods = !contract.members().isEmpty();
            boolean allConstants = !contract.members().isEmpty();
            for (String promised : contract.members()) {
                if (promised == null || promised.isBlank()) {
                    continue;
                }
                String text = promised.strip();
                boolean method = text.contains("(");
                String head = method ? text.substring(0, text.indexOf('(')).strip() : text;
                String name = JavaSourceFacts.lastToken(head);
                if (name.isEmpty()) {
                    continue;
                }
                String type = JavaSourceFacts.fieldOrReturnType(text, name, method);
                members.add(new JavaSourceFacts.Declared(name, method, type,
                    method ? JavaSourceFacts.paramTypes(text) : List.of()));
                allMethods &= method;
                // A constant is written with no type and no parentheses, in the enum's own
                // spelling: NOT_STARTED. That is the only form an enum contract can take.
                allConstants &= !method && type.isEmpty()
                    && name.equals(name.toUpperCase(Locale.ROOT));
            }
            boolean test = simple.endsWith("Test") || simple.endsWith("Tests")
                || simple.startsWith("Test") || role.equals("test");
            String description = contract.description() == null ? "" : contract.description().strip();
            return new Wanted(simple, pkg, role, tokens(simple), List.copyOf(members),
                allMethods, allConstants, test,
                description.regionMatches(true, 0, EXISTING_MARKER, 0, EXISTING_MARKER.length()));
        }
    }

    /**
     * How much this file looks like what the contract asks for, from 0 to about 1.
     *
     * <p>Six independent agreements, weighted. None of them mentions a library, a framework or a
     * language feature this codebase invented; every one of them would score a Spring project's
     * repository against a Spring contract exactly as it scores this one.
     */
    static double score(Wanted wanted, Shape shape, Set<String> taskWords) {
        // A file that IS one of the types the task must CREATE is not an example of it: on a
        // greenfield build a file with that exact name is either a leftover or a coincidence, and
        // showing it as "the nearest existing implementation of what you must build" is circular.
        //
        // <b>Unless the design says the type already exists.</b> On a change to code that already
        // exists most contracts name types written years ago, and for those this file is not a
        // coincidence — it is the code the change is about, and design §2.3 says showing it is
        // right: "a worker reading the current Evaluator.java before changing it is right". It
        // must be labelled as the file to change rather than as a pattern to copy elsewhere, and
        // TaskBrief.render does exactly that out of the task's own write set.
        if (shape.simpleName().equalsIgnoreCase(wanted.simpleName())
            && shape.packageName().equals(wanted.packageName())
            && !wanted.alreadyExists()) {
            return 0;
        }
        double members = memberAgreement(wanted, shape);
        double form = formAgreement(wanted, shape);
        double role = roleAgreement(wanted, shape);
        double name = tokenOverlap(wanted.nameTokens(), tokens(shape.simpleName()));
        double words = taskWords.isEmpty() ? 0 : tokenOverlap(new ArrayList<>(taskWords),
            tokens(shape.simpleName() + " " + shape.role()));
        double size = shape.members().isEmpty() ? 0 : 1;

        return 0.40 * members + 0.20 * form + 0.15 * role + 0.15 * name + 0.05 * words
            + 0.05 * size;
    }

    /**
     * The heart of it: how well the members the contract promises map onto the members this file
     * declares. Greedy best-match, each declared member used once, scored on what a member IS
     * rather than what it is called — field or method, how many arguments, and what KIND of thing
     * it returns.
     */
    private static double memberAgreement(Wanted wanted, Shape shape) {
        if (wanted.members().isEmpty()) {
            return 0;
        }
        List<JavaSourceFacts.Declared> pool = new ArrayList<>(shape.members());
        double total = 0;
        for (JavaSourceFacts.Declared promise : wanted.members()) {
            double best = 0;
            int bestAt = -1;
            for (int i = 0; i < pool.size(); i++) {
                double s = memberSimilarity(promise, pool.get(i), wanted, shape);
                if (s > best) {
                    best = s;
                    bestAt = i;
                }
            }
            if (bestAt >= 0) {
                pool.remove(bestAt);
            }
            total += best;
        }
        double covered = total / wanted.members().size();
        // A file with fifty members that happens to contain three matching ones is a worse example
        // than a file with four. Penalise surplus gently, never to zero.
        int surplus = Math.max(0, shape.members().size() - wanted.members().size() * 3);
        return covered * (1.0 / (1.0 + surplus / 30.0));
    }

    private static double memberSimilarity(JavaSourceFacts.Declared promise,
                                           JavaSourceFacts.Declared declared,
                                           Wanted wanted, Shape shape) {
        double s = 0;
        // A promised field is delivered by a field OR by its getter, so a bean-shaped example
        // matches a field-shaped contract. That is the same generosity ContractDelivery applies.
        boolean sameForm = promise.method() == declared.method()
            || (!promise.method() && declared.method() && declared.paramTypes().isEmpty());
        if (!sameForm) {
            return 0;
        }
        s += 0.45;
        if (promise.method() && promise.paramTypes().size() == declared.paramTypes().size()) {
            s += 0.15;
        }
        String promisedKind = typeKind(promise.type(), wanted.simpleName());
        String declaredKind = typeKind(declared.type(), shape.simpleName());
        if (!promisedKind.isEmpty() && promisedKind.equals(declaredKind)) {
            s += 0.25;
        }
        s += 0.15 * tokenOverlap(tokens(promise.name()), tokens(declared.name()));
        return Math.min(1.0, s);
    }

    /**
     * What KIND of thing a declared type is, with the type's own name folded away.
     *
     * <p>{@code List<Book>} in a Book contract and {@code List<Product>} in the Product example are
     * the same kind — "a collection of the type this file is about" — and comparing the spellings
     * would say they are different. That fold is the whole reason this method exists.
     */
    static String typeKind(String type, String selfName) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String text = type.strip().replaceAll("\\s+", "");
        if (text.equals("void")) {
            return "void";
        }
        String raw = text.contains("<") ? text.substring(0, text.indexOf('<')) : text;
        String simple = raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
        boolean collection = simple.equals("List") || simple.equals("Set")
            || simple.equals("Collection") || simple.equals("Iterable") || raw.endsWith("[]");
        String inner = "";
        if (text.contains("<")) {
            String args = text.substring(text.indexOf('<') + 1, text.lastIndexOf('>'));
            String first = args.contains(",") ? args.substring(0, args.indexOf(',')) : args;
            inner = first.contains(".") ? first.substring(first.lastIndexOf('.') + 1) : first;
        }
        if (collection) {
            return "collection-of:" + nameless(inner, selfName);
        }
        return nameless(simple, selfName);
    }

    /** "self" when the name is the file's own type, the primitive/String otherwise, else "entity". */
    private static String nameless(String simple, String selfName) {
        if (simple.isEmpty()) {
            return "any";
        }
        if (simple.equalsIgnoreCase(selfName)) {
            return "self";
        }
        return switch (simple) {
            case "int", "long", "short", "byte", "double", "float", "boolean", "char",
                 "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character" ->
                "number-or-flag";
            case "String", "CharSequence" -> "text";
            case "void", "Void" -> "void";
            default -> Character.isUpperCase(simple.charAt(0)) ? "entity" : "any";
        };
    }

    /** Interface asked for, interface found; enum asked for, enum found; test asked for, test found. */
    private static double formAgreement(Wanted wanted, Shape shape) {
        boolean testFile = shape.file().toString().replace('\\', '/').contains("/src/test/");
        if (wanted.isTest() != testFile) {
            return 0;
        }
        if (wanted.allConstants()) {
            return shape.isEnum() ? 1 : 0;
        }
        if (shape.isEnum()) {
            return 0;
        }
        if (wanted.allMethods()) {
            // All methods and no fields promised: an interface is the cleanest match, an ordinary
            // class that implements one is the next. A data class is not this shape.
            return shape.isInterface() ? 1 : 0.5;
        }
        return shape.isInterface() ? 0.2 : 1;
    }

    /** {@code model} beside {@code model}, {@code server} beside {@code server}. */
    private static double roleAgreement(Wanted wanted, Shape shape) {
        if (wanted.role().isEmpty() || shape.role().isEmpty()) {
            return 0;
        }
        if (wanted.role().equalsIgnoreCase(shape.role())) {
            return 1;
        }
        // Not the same word, but the same position in a path that says the same thing:
        // com.x.demo.server and com.y.example.server differ only in whose it is.
        return tokenOverlap(tokens(wanted.packageName().replace('.', ' ')),
            tokens(shape.packageName().replace('.', ' ')));
    }

    // ---------------------------------------------------------------------------------------
    // Choosing one project and taking its winners
    // ---------------------------------------------------------------------------------------

    /**
     * The nearest worked example for every contract, all from one project.
     *
     * @param taskWords words from the task's own description; a weak tie-break only, so that a
     *                  codebase with two equally-shaped examples prefers the one whose names are
     *                  about the same subject
     */
    /** The top {@code n} candidates for one contract, ignoring every other contract. Diagnostics. */
    public static List<Match> shortlistFor(List<Shape> shapes, ApiContract contract,
                                           Set<String> taskWords, int n) {
        Wanted wanted = Wanted.of(contract);
        List<Match> scored = new ArrayList<>();
        for (Shape shape : shapes) {
            double score = score(wanted, shape, taskWords == null ? Set.of() : taskWords);
            if (score > 0) {
                scored.add(new Match(contract, shape, score, why(wanted, shape)));
            }
        }
        scored.sort(Comparator.comparingDouble(Match::score).reversed());
        return scored.subList(0, Math.min(n, scored.size()));
    }

    public static Selection selectFromRoots(List<KnowledgeCurator.Root> roots,
                                            List<ApiContract> contracts, Set<String> taskWords) {
        return select(shapes(roots), contracts, taskWords);
    }

    /**
     * The nearest worked example decided on SHAPE alone — what this did before the structural
     * index existed, and what it still does wherever there is no index to consult.
     */
    public static Selection select(List<Shape> shapes, List<ApiContract> contracts,
                                   Set<String> taskWords) {
        return select(shapes, contracts, taskWords, null);
    }

    /**
     * The nearest worked example, preferring structural evidence over resemblance.
     *
     * <p><b>What changed and why.</b> "Nearest" used to mean "most similarly shaped": how many
     * members, of what kind, returning what sort of thing, in a package with a similar last
     * segment. That is a good guess, and it is a guess about form. What a worker copying an example
     * actually needs is a file that DOES THE SAME THING — one that uses the framework types the
     * contract's own members name, carries the annotations that make the framework find it, and
     * calls the members the contract will have to call.
     *
     * <p>So when a {@link SemanticIndex} is available, each contract's demands are resolved to
     * fully-qualified types and {@link SemanticIndex#filesUsingAll} is asked which files use ALL of
     * them. That is an intersection, not a score — a file either does all of it or it does not.
     * Files that do are preferred; among them, and among everything else when no file does, the
     * order is exactly today's shape similarity. Structure decides which candidates are in the
     * running; shape decides which of them wins.
     *
     * <p><b>The fallback is the whole of the old behaviour, unchanged.</b> No index, an index that
     * could not be built, a language OpenRewrite cannot parse, or a contract whose demands resolve
     * to nothing the reference material holds — any of those and this is the method above,
     * line for line. The log line says which of the two happened, every time.
     *
     * @param semantic nullable; an unavailable index is the same as none
     */
    public static Selection select(List<Shape> shapes, List<ApiContract> contracts,
                                   Set<String> taskWords, SemanticIndex semantic) {
        List<ApiContract> named = new ArrayList<>();
        for (ApiContract contract : contracts == null ? List.<ApiContract>of() : contracts) {
            if (contract != null && contract.namesAType()) {
                named.add(contract);
            }
        }
        if (named.isEmpty() || shapes.isEmpty()) {
            return new Selection(null, "", List.of(), List.of(), List.of());
        }
        Set<String> words = taskWords == null ? Set.of() : taskWords;
        boolean structural = semantic != null && semantic.available();

        // The structural evidence per contract, read once. It is used in pass three — which FILE
        // stands for which contract — and deliberately not in pass two, which chooses the project.
        //
        // <b>Measured, and it is why this is not simply a better score.</b> Boosting the shortlist
        // moved the chosen project from one covering all three of the demo's contracts to one
        // covering two, because the project value is strength × coverage and an inflated strength
        // on one contract outbid a whole contract's worth of coverage. Which project to copy is a
        // question about the project as a whole; which of its files answers a contract is a
        // question about the file. Structure belongs to the second.
        Map<ApiContract, Set<String>> evidence = new LinkedHashMap<>();
        int contractsWithEvidence = 0;
        for (ApiContract contract : named) {
            Set<String> files = structural ? structuralEvidenceFor(semantic, contract) : Set.of();
            evidence.put(contract, files);
            if (!files.isEmpty()) {
                contractsWithEvidence++;
            }
        }

        // Pass one: a shortlist per contract, scored independently, on shape alone.
        Map<ApiContract, List<Match>> shortlists = new LinkedHashMap<>();
        for (ApiContract contract : named) {
            Wanted wanted = Wanted.of(contract);
            List<Match> scored = new ArrayList<>();
            for (Shape shape : shapes) {
                double score = score(wanted, shape, words);
                if (score >= MIN_SCORE) {
                    scored.add(new Match(contract, shape, score, ""));
                }
            }
            scored.sort(Comparator.comparingDouble(Match::score).reversed());
            shortlists.put(contract, scored.subList(0, Math.min(SHORTLIST, scored.size())));
        }
        if (!structural) {
            log.info("nearest example: structural evidence was not available ({}), so the choice "
                + "is shape similarity alone", semantic == null
                    ? "no semantic index was passed" : semantic.unavailableReason());
        } else if (contractsWithEvidence == 0) {
            log.info("nearest example: the semantic index is available but no file in the "
                + "reference material uses everything any contract asks for, so the choice falls "
                + "back to shape similarity alone");
        } else {
            log.info("nearest example: structural evidence from the semantic index for {} of {} "
                + "contract(s); shape similarity breaks the ties", contractsWithEvidence,
                named.size());
        }

        // Pass two: the SMALLEST project that still covers most of the task.
        //
        // Independently-best files come from four different projects and teach four idioms that do
        // not fit together — measured: a data class from one example, an enum from a second, a
        // screen from a third, and the persistence one nowhere. The framework's own instruction to
        // agents is the opposite: copy ONE example's tree and replace the domain code.
        //
        // Every ancestor that declares a build is a candidate, so the folder of examples, one
        // example, and one module of it all compete. The folder always has the highest total — it
        // contains everything — and the smallest module always has the fewest files, so neither
        // total nor size can decide this alone. What decides it is resemblance per file read:
        // how much of the task this project covers, how well, against how much of it a reader has
        // to hold in their head at once.
        Map<Path, List<Match>> byGroup = new LinkedHashMap<>();
        for (List<Match> shortlist : shortlists.values()) {
            for (Match match : shortlist) {
                for (Path group : match.example().groups()) {
                    byGroup.computeIfAbsent(group, g -> new ArrayList<>()).add(match);
                }
            }
        }
        Map<Path, Integer> sizes = new HashMap<>();
        Map<Path, Set<String>> vocabulary = new HashMap<>();
        for (Shape shape : shapes) {
            for (Path group : shape.groups()) {
                sizes.merge(group, 1, Integer::sum);
                Set<String> into = vocabulary.computeIfAbsent(group, g -> new LinkedHashSet<>());
                into.addAll(tokens(shape.simpleName()));
                into.addAll(tokens(shape.packageName().replace('.', ' ')));
                for (JavaSourceFacts.Declared member : shape.members()) {
                    into.addAll(tokens(member.name()));
                }
            }
        }
        // A project's own README says what it is FOR, in the words a person would use — which is
        // the only place several identically-shaped examples differ at all.
        for (Path group : new ArrayList<>(vocabulary.keySet())) {
            vocabulary.get(group).addAll(prose(group));
        }

        Path chosen = null;
        double chosenValue = 0;
        for (Map.Entry<Path, List<Match>> entry : byGroup.entrySet()) {
            Path group = entry.getKey();
            Assignment assignment = assign(entry.getValue());
            double coverage = assignment.count() / (double) named.size();
            double size = sizes.getOrDefault(group, 1);
            // What the task is ABOUT, as a tie-break between projects that are equally shaped.
            // A framework's example gallery holds a dozen three-module CRUD apps whose shapes agree
            // to within a few hundredths; their words do not.
            double relevance = words.isEmpty() ? 0
                : tokenOverlap(new ArrayList<>(words),
                    new ArrayList<>(vocabulary.getOrDefault(group, Set.of())));
            double value = assignment.strength() * coverage * (1 + relevance)
                / (1 + size / TYPICAL_PROJECT_FILES);
            log.debug("candidate example {}: {} files, covers {}, strength {}, relevance {}, "
                + "value {}", group, size, assignment.count(), assignment.strength(), relevance,
                value);
            if (System.getenv("BRIEFLAB_TRACE") != null) {
                System.err.printf("   group %-72s files=%-4.0f covers=%-2d strength=%.2f "
                    + "rel=%.2f value=%.3f%n", group, size, assignment.count(),
                    assignment.strength(), relevance, value);
            }
            if (value > chosenValue) {
                chosenValue = value;
                chosen = group;
            }
        }
        log.info("worked example: {} (value {}, of {} candidate projects)", chosen, chosenValue,
            byGroup.size());
        if (chosen == null) {
            return new Selection(null, "", List.of(), List.of(), List.of());
        }

        // One file may stand for one contract, and the strongest pair is settled first — not the
        // first contract in the list. Taking them in contract order let a 0.36 resemblance claim
        // the file that was a 0.68 match for the next contract, and that next contract was then
        // shown nothing at all.
        //
        // And here, and only here, structure outranks resemblance. A candidate must first clear
        // WORTH_SHOWING on its own shape — evidence may not promote a file that does not resemble
        // what is being asked for at all — and among the ones that do, a file that actually uses
        // everything the contract demands is preferred over one that merely looks like it.
        List<Match> candidates = new ArrayList<>();
        for (List<Match> shortlist : shortlists.values()) {
            for (Match match : shortlist) {
                if (match.example().groups().contains(chosen) && match.score() >= WORTH_SHOWING) {
                    boolean proven = evidence.getOrDefault(match.contract(), Set.of())
                        .contains(match.example().relative());
                    candidates.add(proven
                        ? new Match(match.contract(), match.example(),
                            match.score() * STRUCTURAL_PREFERENCE, "")
                        : match);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Match::score).reversed());
        List<Match> winners = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        Set<Path> taken = new LinkedHashSet<>();
        for (Match match : candidates) {
            if (claimed.add(match.contract().typeName()) & taken.add(match.example().file())) {
                winners.add(new Match(match.contract(), match.example(), match.score(),
                    why(Wanted.of(match.contract()), match.example())));
            }
        }
        // Back into the order the design states them in, so the brief reads as the design reads.
        List<String> order = new ArrayList<>();
        for (ApiContract contract : named) {
            order.add(contract.typeName());
        }
        winners.sort(Comparator.comparingInt(m -> order.indexOf(m.contract().typeName())));
        List<Shape> inProject = new ArrayList<>();
        for (Shape shape : shapes) {
            if (shape.groups().contains(chosen)) {
                inProject.add(shape);
            }
        }
        List<Shape> neighbours = neighbours(winners, inProject);
        List<Path> modules = new ArrayList<>();
        for (Match match : winners) {
            if (match.example().module() != null && !modules.contains(match.example().module())) {
                modules.add(match.example().module());
            }
        }
        for (Shape neighbour : neighbours) {
            if (neighbour.module() != null && !modules.contains(neighbour.module())) {
                modules.add(neighbour.module());
            }
        }
        return new Selection(chosen, label(chosen), List.copyOf(winners), neighbours,
            List.copyOf(modules));
    }

    // ---------------------------------------------------------------------------------------
    // Structural evidence
    // ---------------------------------------------------------------------------------------

    /**
     * How much a file that does everything the contract asks for is preferred over one that merely
     * looks like it.
     *
     * <p>A multiplier and not an override, and that is the point. A file with full structural
     * evidence and a 0.30 resemblance still loses to a 0.65 resemblance with none, because a file
     * that uses the right types but has none of the right shape is a different kind of thing
     * wearing the right imports. What this number buys is the case the old selector got wrong: two
     * files of nearly identical shape, one of which actually uses the framework the contract's
     * members name.
     */
    private static final double STRUCTURAL_PREFERENCE = 1.6;

    /**
     * The reference-material files that use EVERY type this contract's own text names.
     *
     * <p><b>What a contract's "imports and annotations" are.</b> A contract is a type name, the
     * members an acceptance test will touch, and a sentence of description — it has no import
     * block. What it has instead are the types those members mention: {@code List<Book> list()}
     * demands {@code java.util.List}; {@code void save(LedgerEntry e)} demands whatever
     * {@code LedgerEntry} resolves to. Every capitalised name in the members, the signature sketch
     * and the description is resolved against the index, and the ones the reference material
     * actually holds become the demand set. A name the material has never heard of is dropped
     * rather than making the intersection empty — the task's own new domain types are exactly such
     * names, and demanding them would mean no example ever qualifies.
     *
     * <p>Returns file paths relative to their root, which is how the index addresses them and how
     * {@link Shape#relative()} spells them.
     */
    /**
     * A type this common distinguishes nothing, so it is not a demand.
     *
     * <p>Measured, and the reason this constant exists: the demo's three contracts name
     * {@code String} and {@code List} and nothing else, and intersecting on those returned 487 and
     * 190 files out of 674 — a list of most of the checkout, presented as evidence. A demand has to
     * narrow the field to be worth anything.
     */
    private static final double TOO_COMMON_TO_BE_EVIDENCE = 0.25;

    static Set<String> structuralEvidenceFor(SemanticIndex semantic, ApiContract contract) {
        Set<String> candidates = new LinkedHashSet<>();
        StringBuilder text = new StringBuilder();
        for (String member : contract.members()) {
            text.append(member == null ? "" : member).append(' ');
        }
        text.append(contract.signatureSketch() == null ? "" : contract.signatureSketch())
            .append(' ')
            .append(contract.description() == null ? "" : contract.description());
        candidates.addAll(semantic.namesKnownIn(text.toString()));
        // And what the code already in this contract's own package carries: the marker annotation
        // that makes the framework find such a type, the interface such a type implements. A
        // contract has no import block; its package's existing members are the nearest thing to
        // one, and on a project with any code in it they are the strongest demand available.
        candidates.addAll(semantic.idiomOf(contract.packageName()));

        Set<String> demanded = new LinkedHashSet<>();
        for (String name : candidates) {
            // Never the type the contract itself fixes: a file that already IS what the task must
            // deliver is not an example of how to write it.
            if (name.equals(contract.simpleTypeName()) || name.equals(contract.typeName())) {
                continue;
            }
            if (name.startsWith("java.lang.")) {
                continue;
            }
            if (semantic.howCommon(name) > TOO_COMMON_TO_BE_EVIDENCE) {
                continue;
            }
            demanded.add(name);
        }
        if (demanded.isEmpty()) {
            return Set.of();
        }
        List<String> files = semantic.filesUsingAll(demanded);
        // Every demand at once is the answer worth having. When nothing does all of it, the
        // largest subset that some file does is still evidence, and dropping one demand at a time
        // from the least distinctive end is how the old text tiers would have degraded anyway.
        List<String> demands = new ArrayList<>(demanded);
        while (files.isEmpty() && demands.size() > 1) {
            demands.remove(demands.size() - 1);
            files = semantic.filesUsingAll(demands);
        }
        return Set.copyOf(files);
    }

    /** The words of a project's own top-level documentation, for the tie-break. */
    static Set<String> prose(Path group) {
        Set<String> words = new LinkedHashSet<>();
        try (var stream = Files.list(group)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".md") && !name.endsWith(".txt") && !name.endsWith(".adoc")) {
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                words.addAll(tokens(text.length() > PROSE_CHARS
                    ? text.substring(0, PROSE_CHARS) : text));
            }
        } catch (Exception e) {                                            // noqa
            return words;
        }
        return words;
    }

    /** How many of a task's types one project has a SEPARATE counterpart for, and how good. */
    record Assignment(int count, double strength) {}

    /**
     * Greedy one-to-one: each contract may claim one file and each file may stand for one contract.
     *
     * <p>Without it a two-file module of shared types is credited with covering five contracts,
     * because the same service interface answers "the interface" and "its implementation" and the
     * same data class answers "the entity" and "the enum". Measured: that module beat the example
     * that actually implements the feature, on a score built entirely out of double counting.
     */
    static Assignment assign(List<Match> candidates) {
        List<Match> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Match::score).reversed());
        Set<String> contractsTaken = new LinkedHashSet<>();
        Set<Path> filesTaken = new LinkedHashSet<>();
        double strength = 0;
        for (Match match : sorted) {
            if (contractsTaken.contains(match.contract().typeName())
                || filesTaken.contains(match.example().file())) {
                continue;
            }
            contractsTaken.add(match.contract().typeName());
            filesTaken.add(match.example().file());
            // Cubed, so that one near-exact counterpart outweighs three vague ones. A framework's
            // example gallery is a dozen three-module CRUD applications whose average resemblance
            // to any CRUD contract is the same to within a few hundredths; summing raw scores
            // therefore ranks them by how many files they have, and the winner changed whenever
            // the shortlist changed. What actually distinguishes them is the best single match —
            // the service whose three methods ARE list, save and delete.
            strength += match.score() * match.score() * match.score();
        }
        return new Assignment(contractsTaken.size(), strength);
    }

    /**
     * The rest of the example: every file inside the chosen project that a winner reaches, and
     * then every file THOSE reach, until nothing new turns up or the budget is spent.
     *
     * <p>One hop is not enough and it was measured: the service implementation names its command
     * and query classes, and only those name the store root the commands mutate. Two hops reach
     * the root. Nothing reaches the provider class that registers the root with the container,
     * because nothing in the code refers to it — the container finds it by annotation. So when the
     * project is small the rest of the winners' modules comes too. That is the framework's own
     * instruction, literally: copy the tree.
     */
    private static List<Shape> neighbours(List<Match> winners, List<Shape> inProject) {
        Set<Path> already = new LinkedHashSet<>();
        for (Match match : winners) {
            already.add(match.example().file());
        }
        List<Shape> found = new ArrayList<>();
        List<Shape> frontier = new ArrayList<>();
        for (Match match : winners) {
            frontier.add(match.example());
        }
        int budget = NEIGHBOUR_BUDGET_BYTES;
        for (int hop = 0; hop < MAX_HOPS && !frontier.isEmpty() && budget > 0; hop++) {
            List<Shape> next = new ArrayList<>();
            for (Shape from : frontier) {
                String code;
                try {
                    code = JavaOutline.of(Files.readString(from.file(), StandardCharsets.UTF_8))
                        .withoutCommentsOrLiterals();
                } catch (Exception e) {                                    // noqa
                    continue;
                }
                for (Shape candidate : inProject) {
                    if (already.contains(candidate.file()) || candidate.bytes() > budget
                        || !mentions(code, candidate.simpleName())) {
                        continue;
                    }
                    already.add(candidate.file());
                    budget -= candidate.bytes();
                    found.add(candidate);
                    next.add(candidate);
                }
            }
            frontier = next;
        }
        // Whatever is left of the modules the winners live in — the wiring nothing refers to.
        Set<Path> modules = new LinkedHashSet<>();
        for (Match match : winners) {
            if (match.example().module() != null) {
                modules.add(match.example().module());
            }
        }
        List<Shape> rest = new ArrayList<>();
        for (Shape candidate : inProject) {
            if (!already.contains(candidate.file()) && modules.contains(candidate.module())) {
                rest.add(candidate);
            }
        }
        // Smallest first. The wiring classes nothing refers to — the provider, the resolver, the
        // socket configuration — are the small ones, and they are exactly what a copy of this
        // example cannot run without; a single 19 KB screen would otherwise spend the budget on
        // its own and leave every one of them out.
        rest.sort(Comparator.comparingInt(Shape::bytes));
        for (Shape candidate : rest) {
            if (candidate.bytes() <= budget) {
                already.add(candidate.file());
                budget -= candidate.bytes();
                found.add(candidate);
            }
        }
        // Discovery order, deliberately NOT sorted: what a winner names directly comes before what
        // that names, which comes before the wiring nothing names. When the brief cannot afford
        // the whole example, that order is what decides which half it keeps.
        return List.copyOf(found);
    }

    private static boolean mentions(String code, String simpleName) {
        int at = code.indexOf(simpleName);
        while (at >= 0) {
            boolean leftClear = at == 0 || !Character.isJavaIdentifierPart(code.charAt(at - 1));
            int end = at + simpleName.length();
            boolean rightClear = end >= code.length()
                || !Character.isJavaIdentifierPart(code.charAt(end));
            if (leftClear && rightClear) {
                return true;
            }
            at = code.indexOf(simpleName, at + 1);
        }
        return false;
    }

    private static String why(Wanted wanted, Shape shape) {
        List<String> reasons = new ArrayList<>();
        if (!shape.annotations().isEmpty()) {
            reasons.add("annotated @" + String.join(", @", shape.annotations()));
        }
        reasons.add(shape.isInterface() ? "an interface" : shape.isEnum() ? "an enum" : "a class");
        reasons.add("with " + shape.members().size() + " members");
        if (wanted.role().equalsIgnoreCase(shape.role())) {
            reasons.add("in a `" + shape.role() + "` package, like this contract");
        }
        return String.join(", ", reasons);
    }

    private static String label(Path project) {
        return project == null || project.getFileName() == null ? ""
            : project.getFileName().toString();
    }

    // ---------------------------------------------------------------------------------------
    // Small shared helpers
    // ---------------------------------------------------------------------------------------

    /** camelCase, PascalCase, snake_case and dotted names, split into lower-case word tokens. */
    static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean boundary = !Character.isLetterOrDigit(c)
                || (Character.isUpperCase(c) && current.length() > 0
                    && !Character.isUpperCase(text.charAt(i - 1)));
            if (boundary && current.length() > 0) {
                out.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Jaccard-ish overlap: shared tokens over the smaller set, so a short name is not punished. */
    static double tokenOverlap(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> a = new LinkedHashSet<>(left);
        Set<String> b = new LinkedHashSet<>(right);
        int shared = 0;
        for (String token : a) {
            if (b.contains(token)) {
                shared++;
            }
        }
        return (double) shared / Math.min(a.size(), b.size());
    }
}
