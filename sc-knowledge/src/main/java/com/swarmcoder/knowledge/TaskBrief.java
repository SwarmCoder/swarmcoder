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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of a worker's prompt that answers "how does THIS codebase do this?" — worked example
 * files in full, the dependency its build is missing, the call sequence the example follows, and
 * the framework types it uses.
 *
 * <p>It replaces a document list and a search tool with code. The measurement that produced it:
 * given prose documentation and a search tool for an unfamiliar framework, the model read the docs
 * and then disassembled jars for three hours and wrote nothing; given a plain-Java task of the same
 * shape it was writing at turn 5 and green at turn 10. Prose does not become an API in a model's
 * head. A file that already compiles does.
 *
 * <p>Every section is optional and every section is derived — nothing here knows the name of a
 * framework, and the same code run against a Spring codebase renders a Spring example.
 */
public final class TaskBrief {

    private static final Logger log = LoggerFactory.getLogger(TaskBrief.class);

    /**
     * Room kept back from the code for the framing sentence and the build line under it. The
     * build line is the shortest thing in the brief and the one most likely to decide whether
     * the work can be done at all, so it must never be what the budget squeezes out.
     */
    private static final int HEADER_AND_BUILD_ALLOWANCE = 1_600;

    /** Which sections to render. Each is measured on its own before it is kept. */
    public record Ingredients(boolean workedExamples, boolean missingDependencies, boolean recipe,
                              boolean apiCards, boolean skeletonNote) {

        public static Ingredients all() {
            return new Ingredients(true, true, true, true, true);
        }

        /** The one that was measured to matter: whole files, and the build line that follows. */
        public static Ingredients examplesOnly() {
            return new Ingredients(true, true, false, false, false);
        }
    }

    private TaskBrief() {}

    // -------------------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------------------

    /**
     * @param budgetChars a ceiling on the whole thing. Sections are rendered in the order they are
     *                    most worth having, and the first one that would cross the ceiling is
     *                    dropped whole rather than cut off in the middle of a Java file — half a
     *                    class teaches nothing and reads as if the API stopped there.
     */
    public static String render(Path targetRoot, WorkedExamples.Selection selection,
                                Ingredients want, int budgetChars) {
        return render(targetRoot, selection, want, budgetChars, Set.of());
    }

    /**
     * @param writeSet the paths this task may write, repo-relative — used for one line of
     *                 provenance and nothing else.
     *
     *                 <p>On a change to code that already exists the nearest example very often IS
     *                 the file being changed, and that is right: a worker reading the current
     *                 {@code Evaluator.java} before changing it is doing the correct thing. But
     *                 "copy this shape into your own files" and "this is the file you are about to
     *                 edit" are opposite instructions, and a brief that gives the first about the
     *                 second teaches a worker to write a parallel copy of the code it was supposed
     *                 to modify. An empty set means the caller does not know, and the line is then
     *                 not written at all rather than guessed at.
     */
    public static String render(Path targetRoot, WorkedExamples.Selection selection,
                                Ingredients want, int budgetChars, Set<String> writeSet) {
        if (selection == null || selection.isEmpty()) {
            return "";
        }
        String code = want.workedExamples()
            ? examples(selection, budgetChars - HEADER_AND_BUILD_ALLOWANCE) : "";
        if (code.isBlank()) {
            // No heading without code under it. A heading that promises "everything below is real
            // code" above nothing is a worse brief than no section at all.
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n### How this codebase does this — the nearest working example, in full\n\n")
          .append("Everything below is real code that compiles today, from `")
          .append(selection.projectLabel())
          .append("`. It is the closest existing implementation of what you have been asked to "
              + "build. **Copy its shape** — the same annotations, the same imports, the same "
              + "structure, the same sequence of calls — and put your own domain names in it. "
              + "Do not work an API out from anywhere else: everything you need is used here.\n")
          .append(whoseFilesTheseAre(selection, writeSet))
          .append(code);


        if (want.missingDependencies()) {
            appendIfItFits(sb, missingDependencies(targetRoot, selection), budgetChars);
        }
        if (want.recipe()) {
            appendIfItFits(sb, recipe(selection), budgetChars);
        }
        if (want.apiCards()) {
            appendIfItFits(sb, apiCards(selection), budgetChars);
        }
        return sb.toString();
    }

    /**
     * One focused file, chosen by what the task USES rather than by what it delivers — the brief
     * section for a task whose own types resemble nothing, or that delivers no contract at all.
     * Same heading as {@link #render}, because to the reader it is the same thing.
     *
     * @param targetRoot nullable; when given, the build line the task's module is missing follows
     * @param source     the "from which checkout, at what version" line, or ""
     */
    static String renderFocused(Path targetRoot, WorkedExamples.Shape example, String why,
                                String source) {
        String code = fence(example);
        if (code.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n### How this codebase does this — the nearest working example, in full\n")
          .append(source == null || source.isBlank() ? "" : source + "\n")
          .append("\nThe file below is real code that compiles today: `")
          .append(example.relative()).append("` from `").append(example.rootLabel())
          .append("`. It was chosen because ").append(why).append(". **Copy its shape** — the "
              + "same annotations, the same imports, the same injected fields, the same sequence "
              + "of calls — and put your own domain names in it. Do not work an API out from "
              + "anywhere else first: what this file uses is what you are to use.\n"
              + "\n**It is not yours to change.** It is a pattern: copy its shape into the files "
              + "your task actually owns.\n\n")
          .append(code);
        if (targetRoot != null && example.module() != null) {
            sb.append(missingDependencies(targetRoot, new WorkedExamples.Selection(
                example.module(), "", List.of(), List.of(), List.of(example.module()))));
        }
        return sb.toString();
    }

    /** One real test, for whoever writes or repairs an acceptance test. */
    static String renderTest(WorkedExamples.Shape example, String why, String source) {
        String code = fence(example);
        if (code.isBlank()) {
            return "";
        }
        // A test of the project itself (section 58): it is the recipe for this project, and it
        // belongs to earlier work, so the new test is a new class beside it.
        boolean own = "project".equals(example.rootLabel());
        return "\n\n" + (own ? "A TEST THIS PROJECT ALREADY HAS, WHICH PASSES TODAY"
            : "A REAL TEST THAT PASSES TODAY") + " — `" + example.relative() + "` from `"
            + example.rootLabel() + "`, chosen because " + why + ".\n"
            + (own ? "It proves earlier work and stays as it is: your test is a new class with "
                + "its own name, never this file written again.\n" : "")
            + (source == null || source.isBlank() ? "" : source + "\n")
            + "Write your test the way this one is written: obtain the code under test the way it "
            + "does, start and stop what it starts and stops, and call the framework only in ways "
            + "you can see here. Its domain names are its own — use this project's contracts "
            + "instead, and keep your own package and class name.\n\n" + code;
    }

    /**
     * The provenance line design §2.3 asks for: is the example below a file you are about to
     * change, or a pattern to copy into files you own?
     *
     * <p>Nothing is written when the caller stated no write set, because "no overlap" and "nobody
     * told me" are different facts and only the first is worth a sentence.
     */
    static String whoseFilesTheseAre(WorkedExamples.Selection selection, Set<String> writeSet) {
        if (writeSet == null || writeSet.isEmpty()) {
            return "";
        }
        List<String> yours = new ArrayList<>();
        List<String> others = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WorkedExamples.Match match : selection.matches()) {
            String relative = match.example().relative();
            if (seen.add(relative)) {
                (isInWriteSet(relative, writeSet) ? yours : others).add(relative);
            }
        }
        for (WorkedExamples.Shape neighbour : selection.neighbours()) {
            String relative = neighbour.relative();
            if (seen.add(relative)) {
                (isInWriteSet(relative, writeSet) ? yours : others).add(relative);
            }
        }
        if (yours.isEmpty()) {
            return "\n**None of the files below is yours to change.** They are patterns: copy their "
                + "shape into the files your task actually owns.\n";
        }
        StringBuilder sb = new StringBuilder("\n**")
            .append(yours.size() == 1 ? "`" + yours.get(0) + "` is a file you are changing"
                : yours.size() + " of the files below are files you are changing (" + yours + ")")
            .append(".** Read ")
            .append(yours.size() == 1 ? "it" : "them")
            .append(" as the code as it stands today and CHANGE ")
            .append(yours.size() == 1 ? "it" : "them")
            .append(" in place — do not write a parallel copy somewhere else.");
        if (!others.isEmpty()) {
            sb.append(" The other file(s) here — ").append(others)
              .append(" — are not yours to write; they are there to show you the idiom.");
        }
        return sb.append("\n").toString();
    }

    /** True when this example path is the write set, is under one of its dirs, or holds one. */
    private static boolean isInWriteSet(String relative, Set<String> writeSet) {
        String file = normalisePath(relative);
        for (String entry : writeSet) {
            String owned = normalisePath(entry);
            if (owned.isEmpty()) {
                continue;
            }
            // A write-set entry is "repo-relative dirs or files" (ArchitectClient's own prompt), so
            // both shapes have to match: the exact file, or any file under a named directory.
            if (file.equals(owned) || file.startsWith(owned + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String normalisePath(String path) {
        String cleaned = path == null ? "" : path.replace('\\', '/').strip();
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private static void appendIfItFits(StringBuilder sb, String section, int budgetChars) {
        if (section.isBlank()) {
            return;
        }
        if (sb.length() + section.length() > budgetChars) {
            log.info("dropped a {}-character brief section: it would cross the {}-character budget",
                section.length(), budgetChars);
            return;
        }
        sb.append(section);
    }

    /**
     * One contract, one file, whole — and never half a file.
     *
     * <p>Files go in until the budget is spent: the per-contract winners first, in the design's own
     * order, then the rest of the example in the order it was discovered, which puts what a winner
     * names directly ahead of the wiring nothing names. A file that does not fit is named and
     * skipped, because half a class teaches nothing and reads as if the API stopped there.
     *
     * <p>Measured on the first attempt at a budget: the section was assembled whole and then
     * dropped whole for being 20,548 characters against a 12,000 ceiling, which left a heading
     * promising real code above no code at all.
     */
    static String examples(WorkedExamples.Selection selection, int budgetChars) {
        StringBuilder sb = new StringBuilder();
        Set<Path> rendered = new LinkedHashSet<>();
        List<String> skipped = new ArrayList<>();
        int budget = budgetChars;

        for (WorkedExamples.Match match : selection.matches()) {
            if (!rendered.add(match.example().file())) {
                continue;
            }
            String head = "\n#### To build `" + match.contract().describe() + "`\n\n"
                + "The nearest existing code is `" + match.example().relative() + "` — "
                + match.why() + ".\n\n";
            String body = fence(match.example());
            if (head.length() + body.length() > budget) {
                skipped.add(match.example().relative());
                continue;
            }
            budget -= head.length() + body.length();
            sb.append(head).append(body);
        }
        if (sb.isEmpty()) {
            return "";
        }
        StringBuilder rest = new StringBuilder();
        for (WorkedExamples.Shape neighbour : selection.neighbours()) {
            if (!rendered.add(neighbour.file())) {
                continue;
            }
            String body = fence(neighbour);
            if (body.length() + 60 > budget) {
                skipped.add(neighbour.relative());
                continue;
            }
            budget -= body.length();
            rest.append('\n').append(body);
        }
        if (!rest.isEmpty()) {
            sb.append("\n#### The rest of that example — the files those use\n").append(rest);
        }
        if (!skipped.isEmpty()) {
            sb.append("\nThe rest of that example did not fit here. Read it with the file "
                + "tools if you need it: ").append(String.join(", ", skipped)).append("\n");
        }
        return sb.toString();
    }

    private static String fence(WorkedExamples.Shape shape) {
        String body;
        try {
            body = JavaOutline.of(Files.readString(shape.file(), StandardCharsets.UTF_8))
                .withoutLicense.strip();
        } catch (Exception e) {                                            // noqa
            return "";
        }
        return "```java\n// " + shape.relative() + "\n" + body + "\n```\n";
    }

    // -------------------------------------------------------------------------------------
    // What the target build does not declare yet
    // -------------------------------------------------------------------------------------

    /**
     * The dependencies the example's own module declares and the module this task writes into does
     * not — with the file to add them to.
     *
     * <p>This is the single line that most often decides whether the work can be done at all. A
     * worker that has been shown code using a persistence API, in a module whose build does not
     * declare it, will write that code and watch it fail to resolve, and has no way to tell "I used
     * the wrong API" from "this build is missing a line".
     */
    static String missingDependencies(Path targetRoot, WorkedExamples.Selection selection) {
        if (targetRoot == null) {
            return "";
        }
        Map<Path, Set<String>> exampleDeps = new LinkedHashMap<>();
        for (Path module : selection.modules()) {
            exampleDeps.put(module, dependenciesOf(module.resolve("pom.xml")));
        }
        List<Path> targetModules = mavenModulesOf(targetRoot);
        if (targetModules.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Set<String> said = new LinkedHashSet<>();
        for (Map.Entry<Path, Set<String>> entry : exampleDeps.entrySet()) {
            Path counterpart = counterpartModule(entry.getKey(), targetModules);
            if (counterpart == null) {
                continue;
            }
            Set<String> have = dependenciesOf(counterpart.resolve("pom.xml"));
            List<String> missing = new ArrayList<>();
            for (String dependency : entry.getValue()) {
                // Only the example's OWN framework dependencies matter. Its sibling modules are
                // named after itself and the target has its own; naming those would be noise.
                if (!have.contains(dependency) && !isSiblingOf(dependency, entry.getKey())
                    && managed(targetRoot, dependency) && said.add(dependency)) {
                    missing.add(dependency);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            String pom = targetRoot.relativize(counterpart.resolve("pom.xml")).toString()
                .replace('\\', '/');
            sb.append("\n#### `").append(pom).append("` does not declare what that example uses\n\n")
              .append("The example's `")
              .append(entry.getKey().getFileName()).append("/pom.xml` declares these and yours "
                  + "does not. Add the ones your code needs — the version is managed by the "
                  + "parent, so no `<version>` element:\n\n");
            for (String dependency : missing) {
                sb.append("- `").append(dependency).append("`\n");
            }
        }
        return sb.toString();
    }

    /**
     * Whether the target's own build already says which version of this artifact to use — through
     * an imported BOM or a {@code dependencyManagement} entry.
     *
     * <p>Without this the list is every line of the example's build: its application server, its
     * logging binding, its annotation-processing helpers, twelve entries of which one matters. And
     * every one of the others is a dependency the target has no managed version for, so a worker
     * that took the advice would have to invent a version number and the offline build would fail
     * on it.
     */
    static boolean managed(Path root, String dependency) {
        String group = dependency.substring(0, dependency.indexOf(':'));
        String artifact = dependency.substring(dependency.indexOf(':') + 1);
        try {
            String text = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
            int at = text.indexOf("<dependencyManagement>");
            if (at < 0) {
                return false;
            }
            int end = text.indexOf("</dependencyManagement>", at);
            String managed = text.substring(at, end < 0 ? text.length() : end);
            // Named outright.
            if (managed.contains("<artifactId>" + artifact + "</artifactId>")) {
                return true;
            }
            // Or covered by a bill of materials imported for the same group. Both halves matter:
            // an earlier version of this asked only whether the group appeared anywhere and
            // whether any import existed, and answered yes for two JUnit artifacts a
            // com.zeroz4j BOM has never heard of. The build then failed on a missing version
            // before a single class was compiled.
            Matcher matcher = Pattern.compile(
                "<dependency>(?:(?!</dependency>).)*?<groupId>" + Pattern.quote(group)
                    + "</groupId>(?:(?!</dependency>).)*?<scope>import</scope>", Pattern.DOTALL)
                .matcher(managed);
            return matcher.find();
        } catch (Exception e) {                                            // noqa
            return false;
        }
    }

    private static boolean isSiblingOf(String dependency, Path module) {
        String artifact = dependency.contains(":")
            ? dependency.substring(dependency.indexOf(':') + 1) : dependency;
        Path parent = module.getParent();
        return parent != null && Files.isDirectory(parent.resolve(artifact));
    }

    /**
     * The module in the target project that plays the same part as this example module.
     *
     * <p>By the contract's package when the target already has it — that is exact. Otherwise by
     * name tokens: {@code inventory-crud-server} and {@code bookshelf-demo-server} share the word
     * that says what each one is for.
     */
    static Path counterpartModule(Path exampleModule, List<Path> targetModules) {
        Set<String> exampleTokens = new LinkedHashSet<>(
            WorkedExamples.tokens(exampleModule.getFileName().toString()));
        Path best = null;
        double bestScore = 0;
        for (Path module : targetModules) {
            double score = WorkedExamples.tokenOverlap(
                new ArrayList<>(exampleTokens),
                WorkedExamples.tokens(module.getFileName().toString()));
            if (score > bestScore) {
                bestScore = score;
                best = module;
            }
        }
        return bestScore > 0 ? best : null;
    }

    /** Every directory under the root that declares a Maven module, root itself excluded. */
    static List<Path> mavenModulesOf(Path root) {
        List<Path> modules = new ArrayList<>();
        try (var stream = Files.walk(root, 3)) {
            stream.filter(p -> p.getFileName() != null
                    && p.getFileName().toString().equals("pom.xml"))
                .map(Path::getParent)
                .filter(p -> p != null && !p.equals(root))
                .filter(p -> !p.toString().contains("target"))
                .forEach(modules::add);
        } catch (Exception e) {                                            // noqa
            log.debug("could not list the modules under {}: {}", root, e.getMessage());
        }
        return modules;
    }

    private static final Pattern DEPENDENCY = Pattern.compile(
        "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>",
        Pattern.DOTALL);

    /** {@code groupId:artifactId} for every dependency a pom declares directly. */
    static Set<String> dependenciesOf(Path pom) {
        Set<String> found = new LinkedHashSet<>();
        if (pom == null || !Files.isRegularFile(pom)) {
            return found;
        }
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
            Matcher matcher = DEPENDENCY.matcher(text);
            while (matcher.find()) {
                found.add(matcher.group(1).strip() + ":" + matcher.group(2).strip());
            }
        } catch (Exception e) {                                            // noqa
            log.debug("could not read {}: {}", pom, e.getMessage());
        }
        return found;
    }

    // -------------------------------------------------------------------------------------
    // The recipe
    // -------------------------------------------------------------------------------------

    /**
     * The sequence of framework calls the example makes, as numbered steps quoting its own lines.
     *
     * <p>Mechanical: a line is a step when it names a type the file imported from outside its own
     * project. Nothing is paraphrased, so nothing can be paraphrased wrong.
     */
    static String recipe(WorkedExamples.Selection selection) {
        StringBuilder sb = new StringBuilder();
        for (WorkedExamples.Match match : selection.matches()) {
            List<String> steps = frameworkLines(match.example());
            if (steps.size() < 2) {
                continue;
            }
            sb.append("\n#### The steps `").append(match.example().simpleName())
              .append("` takes, in order\n\n");
            int n = 1;
            for (String step : steps) {
                sb.append(n++).append(". `").append(step).append("`\n");
            }
        }
        return sb.isEmpty() ? "" : "\n### The call sequence, pulled out of that example\n" + sb;
    }

    private static final int MAX_RECIPE_STEPS = 14;

    static List<String> frameworkLines(WorkedExamples.Shape shape) {
        Set<String> foreign = new LinkedHashSet<>();
        for (String imported : shape.imports()) {
            String simple = JavaSourceFacts.importedSimpleName(imported);
            String owner = imported.startsWith("static ") ? imported.substring(7) : imported;
            // A type from this file's own package tree is domain code, not the framework.
            if (!simple.isEmpty() && !owner.startsWith(rootPackage(shape.packageName()))) {
                foreign.add(simple);
            }
        }
        List<String> steps = new ArrayList<>();
        if (foreign.isEmpty()) {
            return steps;
        }
        try {
            String code = JavaOutline.of(Files.readString(shape.file(), StandardCharsets.UTF_8))
                .withoutCommentsOrLiterals();
            for (String line : code.split("\n")) {
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("import ") || text.startsWith("package ")) {
                    continue;
                }
                for (String type : foreign) {
                    if (text.contains(type) && steps.size() < MAX_RECIPE_STEPS) {
                        steps.add(text.replaceAll("\\s+", " "));
                        break;
                    }
                }
            }
        } catch (Exception e) {                                            // noqa
            return List.of();
        }
        return steps;
    }

    /** {@code com.zeroz4j.example} for {@code com.zeroz4j.example.server.store} — three segments. */
    private static String rootPackage(String packageName) {
        String[] parts = packageName.split("\\.");
        return parts.length <= 3 ? packageName
            : String.join(".", parts[0], parts[1], parts[2]);
    }

    // -------------------------------------------------------------------------------------
    // API cards
    // -------------------------------------------------------------------------------------

    private static final int MAX_CARDS = 10;

    /**
     * For every framework type the example imports, its public surface — signatures only, no
     * bodies — when its source is somewhere under the same reference root.
     */
    static String apiCards(WorkedExamples.Selection selection) {
        Set<String> wanted = new LinkedHashSet<>();
        List<WorkedExamples.Shape> all = new ArrayList<>(selection.neighbours());
        for (WorkedExamples.Match match : selection.matches()) {
            all.add(match.example());
        }
        Path root = null;
        for (WorkedExamples.Shape shape : all) {
            root = shape.root();
            for (String imported : shape.imports()) {
                String owner = imported.startsWith("static ") ? imported.substring(7) : imported;
                if (!owner.startsWith(rootPackage(shape.packageName())) && !owner.startsWith("java.")
                    && !owner.endsWith("*")) {
                    wanted.add(owner.strip());
                }
            }
        }
        if (root == null || wanted.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int cards = 0;
        for (String fullName : wanted) {
            if (cards >= MAX_CARDS) {
                break;
            }
            String card = card(root, fullName);
            if (!card.isBlank()) {
                sb.append(card);
                cards++;
            }
        }
        return sb.isEmpty() ? ""
            : "\n### The framework types that example uses — their public methods\n" + sb;
    }

    private static String card(Path root, String fullName) {
        int dot = fullName.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String simple = fullName.substring(dot + 1);
        String pathTail = fullName.replace('.', '/') + ".java";
        Path found = null;
        try (var stream = Files.walk(root)) {
            found = stream.filter(p -> p.toString().replace('\\', '/').endsWith(pathTail))
                .filter(p -> !p.toString().contains("target"))
                .findFirst().orElse(null);
        } catch (Exception e) {                                            // noqa
            return "";
        }
        if (found == null) {
            return "";
        }
        try {
            JavaOutline outline = JavaOutline.of(Files.readString(found, StandardCharsets.UTF_8));
            JavaOutline.Member type = outline.primaryType();
            if (type == null || !type.name().equals(simple)) {
                return "";
            }
            StringBuilder sb = new StringBuilder("\n```java\n// " + fullName + "\n"
                + JavaOutline.collapse(type.header()) + "\n");
            for (JavaOutline.Member member : type.children()) {
                if (!member.exposed() || member.kind() == JavaOutline.Kind.FIELD) {
                    continue;
                }
                sb.append("    ").append(JavaOutline.collapse(member.header())).append(";\n");
            }
            return sb.append("}\n```\n").toString();
        } catch (Exception e) {                                            // noqa
            return "";
        }
    }

    // -------------------------------------------------------------------------------------
    // Words from the task, for the tie-break
    // -------------------------------------------------------------------------------------

    /** The distinctive words of a task's own text — its tie-break vote between two equal shapes. */
    public static Set<String> taskWords(String... texts) {
        Set<String> words = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.length() >= 4) {
                    words.add(token);
                }
            }
        }
        return words;
    }
}
