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
package com.swarmcoder.verify;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of the two things "the acceptance test does not compile" means: the code it needs has not
 * been written yet (a healthy red state — TDD), or the test itself is wrong in a way no task in the
 * plan can ever fix (a broken test).
 *
 * <p><b>Why this exists (brownfield harness run 43, 2026-09-26).</b> Target jsoup, an existing
 * plain-Java library; one task, "Guard Document.ensureMetaCharsetElement against empty XML
 * documents", writing {@code Document.java}. The test author wrote
 * {@code src/test/java/swarm/accept/DocumentTest.java}, and line 16 of it passed a
 * {@code Parser} where the constructor it called takes a {@code String}: javac said
 * {@code incompatible types: org.jsoup.parser.Parser cannot be converted to java.lang.String}.
 * {@link RedChecker} read ANY compile failure of the acceptance stage as "they reference
 * not-yet-implemented symbols — red state confirmed", so the test sailed through the red check.
 * Both workers then wrote the right guard, both candidates failed verification on that same line
 * ("the tree does not compile before this candidate's change"), four repair workers finished
 * without a change because the guard was already there and the test was not theirs to touch, and
 * the task was BLOCKED. Every model-hour after test authoring went on a test that could never
 * compile for anybody.
 *
 * <p>On a greenfield run almost every compile failure of a fresh test is a missing symbol, because
 * almost everything the test names is new. On an existing codebase the opposite holds: the test
 * names types that are already there, and the common way to get it wrong is to MISUSE one — the
 * wrong argument type, the wrong number of arguments, a method that exists with a different
 * signature, an exception it does not declare. None of those is "not written yet".
 *
 * <p><b>The rule.</b> Only the errors located in the acceptance test files are read — an error
 * elsewhere is the tree's, not the test's, and is left to the existing reading. Each one is either
 * an ABSENCE (javac could not find something) or a MISUSE (everything it names exists, and the
 * test uses it wrongly). An absence is healthy when some task still to run could supply it:
 * <ul>
 *   <li>a missing type or package — {@link TypeDeliverability}, unchanged: a contract names it, or
 *       a write set covers its package;</li>
 *   <li>a missing member of a type ({@code symbol: method foo(..)}, {@code location: variable doc
 *       of type org.jsoup.nodes.Document}) — a still-to-run task's contract names that owner type,
 *       or its write set can hold the owner's source file. A task that edits {@code Document.java}
 *       can add a method to it; nothing in a plan says which methods, so this fails open;</li>
 *   <li>a name the test's own class could not resolve (no import, no declaration) — a contract or
 *       a write-set file supplies a type of that simple name, or a contract member of that name
 *       (a static wildcard import of a delivered type). Otherwise it is a missing import or a
 *       helper nobody wrote, and no task can fix the test's own imports.</li>
 * </ul>
 * A misuse is healthy only when the plan promises to change the shape of the existing type
 * involved: a still-to-run task's contract names a member the pre-change tree does not carry
 * ({@link PlannedChanges}, read by the caller from {@code ContractDelivery}), and that member's
 * name appears in the compiler's message or on the offending source line. A write set alone is
 * NOT enough for a misuse, and that asymmetry is deliberate: run 43's task wrote exactly the file
 * whose constructor the test misused, so "the task may edit that file" would have waved run 43
 * straight through again. Adding a member is what editing a file routinely does; changing the
 * signature of a member that already exists breaks every existing caller, and is never incidental
 * to a task — a plan that means it says so in a contract.
 *
 * <p><b>Ambiguous cases, and which way they go.</b> A "cannot find symbol" whose symbol and
 * location lines cannot be read is healthy (fails open, the same generosity as
 * {@link TypeDeliverability}). A missing member of an existing type that no task writes and no
 * contract names — the case the author decision singled out — is BROKEN: nothing in the plan can
 * put it there, and the test author, told the real members of that type, can. A missing member of
 * a JDK type ({@code java.}, {@code javax.}) is always broken. The cost of each mistake was
 * weighed: calling a healthy red broken costs one re-ask of the test author (and a park only if the
 * author insists twice); calling a broken test red costs a whole swarm, a repair round and a
 * BLOCKED task, which is run 43.
 *
 * <p>Pure text: no compiler, no files. The one thing it needs from outside is the offending source
 * line, for the misuse rule, and that is a function the caller supplies (null means "not
 * available", never "empty").
 */
public final class AcceptanceCompileErrors {

    private AcceptanceCompileErrors() {}

    /** What one compiler error in a test file says is wrong. */
    public enum Kind {
        /** {@code cannot find symbol: class X, location: package p} — a type nobody wrote yet, or ever. */
        MISSING_TYPE,
        /** {@code package p does not exist}. */
        MISSING_PACKAGE,
        /** {@code cannot find symbol: method/variable/class x}, located on another type. */
        MISSING_MEMBER,
        /** {@code cannot find symbol}, located on the test's own class: no import, no declaration. */
        UNRESOLVED_NAME,
        /** {@code cannot find symbol} whose follow-up lines could not be read. */
        UNREADABLE_ABSENCE,
        /** Everything else: the names exist and the test uses them wrongly. */
        MISUSE
    }

    /**
     * One compiler error.
     *
     * @param file       the path as the compiler printed it, slashes normalised
     * @param line       the line, or 0 when the compiler did not say
     * @param message    the compiler's own message, first line, as printed
     * @param detail     the follow-up lines javac attaches ({@code symbol:}, {@code location:},
     *                   {@code required:}, {@code found:}, {@code reason:}), trimmed, in order
     * @param kind       what it says is wrong
     * @param symbolKind for an absence, javac's word for the missing thing ({@code class},
     *                   {@code method}, {@code variable}, {@code static}); else null
     * @param symbolName for an absence, the missing name without its argument list; else null
     * @param owner      for {@link Kind#MISSING_MEMBER}, the type javac looked on (generics
     *                   removed); for {@link Kind#MISSING_TYPE}/{@link Kind#MISSING_PACKAGE}, the
     *                   fully-qualified missing name; else null
     */
    public record CompileError(String file, int line, String message, List<String> detail, Kind kind,
                               String symbolKind, String symbolName, String owner) {

        public CompileError {
            detail = detail == null ? List.of() : List.copyOf(detail);
        }

        /** {@code file:line: message}, then each follow-up line indented — the compiler's own words. */
        public String quote() {
            StringBuilder sb = new StringBuilder(file).append(line > 0 ? ":" + line : "")
                .append(": ").append(message);
            for (String d : detail) {
                sb.append("\n    ").append(d);
            }
            return sb.toString();
        }

        /** The file's last segment, e.g. {@code DocumentTest.java}. */
        public String fileName() {
            int slash = file.lastIndexOf('/');
            return slash < 0 ? file : file.substring(slash + 1);
        }
    }

    /**
     * One broken error and, in plain words, why no task can fix it.
     */
    public record Broken(CompileError error, String why) {}

    /**
     * What the plan will change about types that ALREADY exist, member by member.
     *
     * <p>Keyed by the owner type's fully-qualified name; the value is the simple names of the
     * members a still-to-run task's contract promises and the pre-change tree does not carry. It
     * is computed by the caller, which can see {@code ContractDelivery} (module
     * {@code sc-knowledge}); this module cannot. {@link #NONE} is the honest value when it could
     * not be computed: every misuse is then broken, which is the reading run 43 needed.
     */
    public record PlannedChanges(Map<String, Set<String>> membersByType) {
        public static final PlannedChanges NONE = new PlannedChanges(Map.of());

        public PlannedChanges {
            membersByType = membersByType == null ? Map.of() : Map.copyOf(membersByType);
        }

        /** Every promised member name, across every type. */
        Set<String> allMemberNames() {
            Set<String> all = new LinkedHashSet<>();
            membersByType.values().forEach(all::addAll);
            return all;
        }
    }

    /**
     * A package the test imports that is not on its module's classpath yet, and a task that can put
     * it there: the task's write set holds the module's build file.
     *
     * <p><b>Why (live run 64, 2026-10-02).</b> The test for a task that writes the server module
     * imported a class from a library module the server did not depend on yet. The task had the
     * server's build file in its write set precisely so it could add that dependency, and the red
     * check parked the run anyway: "no task's write set can create it". A dependency is not a type
     * any task creates, it is a line in a build file, and a task that owns the file can write it.
     *
     * @param packageName the package javac said does not exist
     * @param types       the simple names the test imports from it, as far as the import lines can
     *                    be read; may be empty
     * @param moduleDir   the directory of the module the test is in, repo-relative; "" for the root
     * @param buildFile   the module's build file, repo-relative, as it appears in the write sets
     * @param owners      every task still to run that may write {@code buildFile}, in plan order
     */
    public record DependencyNeed(String packageName, List<String> types, String moduleDir,
                                 String buildFile, List<Task> owners) {

        public DependencyNeed {
            types = types == null ? List.of() : List.copyOf(types);
            owners = owners == null ? List.of() : List.copyOf(owners);
        }
    }

    /**
     * The reading of one compile output against one set of test files.
     *
     * @param inTestFiles every error located in one of the test files, in the compiler's order
     * @param broken      the subset no still-to-run task can make go away, each with its reason;
     *                    empty means every error in the test files is a healthy red (or there
     *                    were none)
     * @param dependencyNeeds the packages the test imports that are not on its module's classpath
     *                    yet, each of which a task still to run can supply because it may write that
     *                    module's build file; the errors they cause are NOT in {@code broken}
     */
    public record Reading(List<CompileError> inTestFiles, List<Broken> broken,
                          List<DependencyNeed> dependencyNeeds) {

        public Reading {
            inTestFiles = inTestFiles == null ? List.of() : List.copyOf(inTestFiles);
            broken = broken == null ? List.of() : List.copyOf(broken);
            dependencyNeeds = dependencyNeeds == null ? List.of() : List.copyOf(dependencyNeeds);
        }

        public Reading(List<CompileError> inTestFiles, List<Broken> broken) {
            this(inTestFiles, broken, List.of());
        }

        /** True when at least one error in the test files is one no task can ever fix. */
        public boolean isBroken() {
            return !broken.isEmpty();
        }

        /**
         * Every broken error quoted as the compiler said it, each followed by why no task can fix
         * it. The text a test author, an operator's park brief and a log line all read.
         */
        public String quoted() {
            StringBuilder sb = new StringBuilder();
            int named = Math.min(broken.size(), MAX_NAMED);
            for (int i = 0; i < named; i++) {
                Broken b = broken.get(i);
                sb.append(sb.isEmpty() ? "" : "\n").append(b.error().quote())
                    .append("\n    -> ").append(b.why());
            }
            if (broken.size() > named) {
                sb.append("\n(and ").append(broken.size() - named)
                    .append(" more compile errors, not shown)");
            }
            return sb.toString();
        }

        /** The most errors one log line or park brief names before it says how many it left out. */
        public static final int MAX_NAMED = 20;

        /**
         * The text for the log: every broken error, {@code file:line message}, up to
         * {@link #MAX_NAMED}; "(and N more)" only beyond that.
         */
        public String headline() {
            if (broken.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int named = Math.min(broken.size(), MAX_NAMED);
            for (int i = 0; i < named; i++) {
                CompileError error = broken.get(i).error();
                sb.append(i == 0 ? "" : "; ").append(error.fileName())
                    .append(error.line() > 0 ? ":" + error.line() : "").append(' ')
                    .append(error.message());
            }
            if (broken.size() > named) {
                sb.append(" (and ").append(broken.size() - named).append(" more)");
            }
            return sb.toString();
        }
    }

    // --------------------------------------------------------------------------------- parsing

    /** Maven: {@code [ERROR] /path/File.java:[9,13] cannot find symbol}. */
    private static final Pattern MAVEN =
        Pattern.compile("^\\[ERROR\\]\\s+(.+?\\.java):\\[(\\d+),\\d+\\]\\s*(.*)$");
    /** javac / Gradle: {@code /path/File.java:12: error: cannot find symbol}. */
    private static final Pattern JAVAC =
        Pattern.compile("^(?:\\[ERROR\\]\\s+)?(.+?\\.java):(\\d+):\\s*error:\\s*(.*)$");
    /**
     * A follow-up line javac attaches to an error, with Maven's optional prefix: the {@code symbol:}
     * / {@code location:} / {@code required:} / {@code found:} / {@code reason:} lines, and for an
     * overloaded call the {@code method X is not applicable} line of each candidate with its
     * parenthesised reason - every one of them, because the test author needs all the overloads.
     */
    private static final Pattern DETAIL =
        Pattern.compile("^(?:\\[ERROR\\])?\\s+((?:(?:symbol|location|required|found|reason):.*?)"
            + "|(?:(?:method|constructor)\\s.*\\sis not applicable)|(?:\\(.*\\)))\\s*$");
    /** How far past an error line its follow-up lines are read. */
    private static final int DETAIL_WINDOW = 40;
    private static final Pattern SYMBOL = Pattern.compile("^symbol:\\s+(\\S+)\\s+(.+)$");
    private static final Pattern LOCATION = Pattern.compile("^location:\\s+(.+)$");
    private static final Pattern PACKAGE_MISSING =
        Pattern.compile("^package\\s+(\\S+)\\s+does not exist$");
    private static final Pattern OF_TYPE = Pattern.compile("^variable\\s+\\S+\\s+of type\\s+(.+)$");
    private static final Pattern TYPE_LOCATION =
        Pattern.compile("^(?:class|interface|enum|record|@interface)\\s+(.+)$");

    /**
     * Every Java compiler error in {@code output}, deduplicated (Maven prints each one twice: once
     * as it happens and once in the goal's summary), in the order first printed.
     */
    public static List<CompileError> parse(String output) {
        List<CompileError> errors = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return errors;
        }
        Set<String> seen = new LinkedHashSet<>();
        String[] lines = output.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = MAVEN.matcher(lines[i]);
            if (!m.matches()) {
                m = JAVAC.matcher(lines[i]);
                if (!m.matches()) {
                    continue;
                }
            }
            String file = m.group(1).trim().replace('\\', '/');
            int line = Integer.parseInt(m.group(2));
            String message = m.group(3).trim();
            List<String> detail = new ArrayList<>();
            for (int j = i + 1; j < lines.length && j <= i + DETAIL_WINDOW; j++) {
                if (MAVEN.matcher(lines[j]).matches() || JAVAC.matcher(lines[j]).matches()) {
                    break;
                }
                Matcher d = DETAIL.matcher(lines[j]);
                if (d.matches()) {
                    detail.add(d.group(1).trim().replaceAll("\\s+", " "));
                }
            }
            if (!seen.add(file + ":" + line + ":" + message)) {
                continue;
            }
            errors.add(read(file, line, message, detail));
        }
        return errors;
    }

    private static CompileError read(String file, int line, String message, List<String> detail) {
        Matcher pkg = PACKAGE_MISSING.matcher(message);
        if (pkg.matches()) {
            return new CompileError(file, line, message, detail, Kind.MISSING_PACKAGE, null, null,
                pkg.group(1));
        }
        if (!message.toLowerCase(Locale.ROOT).startsWith("cannot find symbol")) {
            return new CompileError(file, line, message, detail, Kind.MISUSE, null, null, null);
        }
        String symbolKind = null;
        String symbolName = null;
        String location = null;
        for (String d : detail) {
            Matcher s = SYMBOL.matcher(d);
            if (s.matches()) {
                symbolKind = s.group(1);
                String name = s.group(2).trim();
                int paren = name.indexOf('(');
                symbolName = paren < 0 ? name : name.substring(0, paren).trim();
                continue;
            }
            Matcher l = LOCATION.matcher(d);
            if (l.matches()) {
                location = l.group(1).trim();
            }
        }
        if (symbolKind == null || symbolName == null || location == null) {
            return new CompileError(file, line, message, detail, Kind.UNREADABLE_ABSENCE,
                symbolKind, symbolName, null);
        }
        boolean typeLike = isTypeWord(symbolKind);
        if (location.startsWith("package ")) {
            String p = location.substring("package ".length()).trim();
            return typeLike
                ? new CompileError(file, line, message, detail, Kind.MISSING_TYPE, symbolKind,
                    symbolName, p + "." + symbolName)
                : new CompileError(file, line, message, detail, Kind.UNREADABLE_ABSENCE,
                    symbolKind, symbolName, null);
        }
        String owner = ownerOf(location);
        if (owner == null) {
            return new CompileError(file, line, message, detail, Kind.UNREADABLE_ABSENCE,
                symbolKind, symbolName, null);
        }
        if (isTheTestItself(owner, file)) {
            return new CompileError(file, line, message, detail, Kind.UNRESOLVED_NAME, symbolKind,
                symbolName, owner);
        }
        return new CompileError(file, line, message, detail, Kind.MISSING_MEMBER, symbolKind,
            symbolName, owner);
    }

    /** The type named by a {@code location:} line, generics removed; null when unreadable. */
    private static String ownerOf(String location) {
        String type = null;
        Matcher ofType = OF_TYPE.matcher(location);
        if (ofType.matches()) {
            type = ofType.group(1);
        } else {
            Matcher t = TYPE_LOCATION.matcher(location);
            if (t.matches()) {
                type = t.group(1);
            }
        }
        if (type == null) {
            return null;
        }
        int generic = type.indexOf('<');
        String raw = (generic < 0 ? type : type.substring(0, generic)).trim();
        if (raw.endsWith("[]")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        return raw.isEmpty() ? null : raw;
    }

    /** True when {@code owner} is the class declared by the test file the error is in. */
    private static boolean isTheTestItself(String owner, String file) {
        int slash = file.lastIndexOf('/');
        String simple = (slash < 0 ? file : file.substring(slash + 1)).replaceFirst("\\.java$", "");
        String ownerSimple = owner.substring(owner.lastIndexOf('.') + 1);
        return ownerSimple.equals(simple) || owner.contains("." + simple + ".")
            || owner.startsWith(simple + ".");
    }

    private static boolean isTypeWord(String word) {
        return "class".equals(word) || "interface".equals(word) || "enum".equals(word)
            || "record".equals(word);
    }

    // --------------------------------------------------------------------------- classifying

    /**
     * Reads {@code output} against the acceptance test files and the tasks still to run.
     *
     * @param output     everything the failed acceptance stage printed
     * @param testFiles  the acceptance test files, repo-relative; an error counts as the test's
     *                   when its printed path is one of these or ends with {@code /} + one of these
     * @param stillToRun every task that has not run yet and so could still supply a missing name
     * @param changes    what the plan will change about existing types; {@link PlannedChanges#NONE}
     *                   when unknown
     * @param sourceLine the offending source line for an error, or null when it cannot be read; may
     *                   itself be null
     */
    public static Reading classify(String output, Collection<String> testFiles, List<Task> stillToRun,
                                   PlannedChanges changes, Function<CompileError, String> sourceLine) {
        return classify(output, testFiles, stillToRun, changes, sourceLine, null);
    }

    /**
     * {@link #classify(String, Collection, List, PlannedChanges, Function)}, also told which types
     * have their source in the project.
     *
     * <p><b>A member missing from a library type is never deliverable (live run 57, 2026-10-01).</b>
     * The test called {@code Builder.storeDir(Path)} on a class that lives in a jar on the
     * classpath. No task can add a method to a jar, whatever its write set or its contracts say, so
     * a missing member of a type whose source is not in the project is broken outright.
     *
     * @param sourceInProject true when the source file of the given top-level type (fully
     *                        qualified) is in the project tree; null means "not known", and the
     *                        reading is then the write-set and contract one alone
     */
    public static Reading classify(String output, Collection<String> testFiles, List<Task> stillToRun,
                                   PlannedChanges changes, Function<CompileError, String> sourceLine,
                                   Predicate<String> sourceInProject) {
        return classify(output, testFiles, stillToRun, changes, sourceLine, sourceInProject, null);
    }

    /**
     * {@link #classify(String, Collection, List, PlannedChanges, Function, Predicate)}, also able
     * to read a missing PACKAGE as a missing dependency.
     *
     * <p>A {@code package P does not exist} error (and the {@code cannot find symbol: class Y}
     * errors of the types imported from {@code P}) is a healthy red when a task still to run has,
     * in its write set, the build file of the module the test is in: that task can add the
     * dependency which provides {@code P}. It stays the test's own broken import when
     * {@code packageInModule} says the test's own module already has code in {@code P} or in a
     * package {@code P} is nested in (a mistyped sub-package of the project's own code is not a
     * dependency), and when no task owns the build file, which is the unchanged reading.
     *
     * @param packageInModule {@code (moduleDir, package)}: true when that module's own sources hold
     *                        the package or a package it is nested in; null means "not known"
     */
    public static Reading classify(String output, Collection<String> testFiles, List<Task> stillToRun,
                                   PlannedChanges changes, Function<CompileError, String> sourceLine,
                                   Predicate<String> sourceInProject,
                                   BiPredicate<String, String> packageInModule) {
        List<CompileError> inTests = new ArrayList<>();
        for (CompileError error : parse(output)) {
            if (inTestFiles(error.file(), testFiles)) {
                inTests.add(error);
            }
        }
        List<Task> tasks = stillToRun == null ? List.of() : stillToRun;
        PlannedChanges planned = changes == null ? PlannedChanges.NONE : changes;
        Set<CompileError> supplied = new LinkedHashSet<>();
        List<DependencyNeed> needs = dependencyNeeds(inTests, testFiles, tasks, planned, sourceLine,
            packageInModule, supplied);
        // Types the compiler could not find and a task will create. javac reports the import, and
        // then every static use of the type (`Qso_Rules.validate(..)`) as a missing VARIABLE of
        // the test class; that second error is the same absence, not a second fault.
        Set<String> pendingTypes = new LinkedHashSet<>();
        for (CompileError error : inTests) {
            if (error.kind() == Kind.MISSING_TYPE && error.symbolName() != null
                    && whyNoTaskCanFix(error, tasks, planned, sourceLine) == null) {
                pendingTypes.add(error.symbolName());
            }
        }
        List<Broken> broken = new ArrayList<>();
        for (CompileError error : inTests) {
            if (supplied.contains(error)) {
                continue;
            }
            if (error.kind() == Kind.UNRESOLVED_NAME && pendingTypes.contains(error.symbolName())) {
                continue;
            }
            String why = null;
            if (error.kind() == Kind.MISSING_MEMBER) {
                boolean noSourceHere = hasNoSourceHere(error.owner(), sourceInProject);
                if (memberDeliverable(error.owner(), tasks, !noSourceHere)) {
                    continue; // a task delivers or writes that type: it may add the member
                }
                if (noSourceHere) {
                    if (writesBeside(error.owner(), tasks)) {
                        // No hand-written source, in a package a task writes a file in: a type
                        // the build generates from that file (harness run 72: Qso_Rules, made
                        // from Qso). Whether the task's change gives it the member is not
                        // something a reading of the compiler's words can establish.
                        continue;
                    }
                    why = libraryOwner(error);
                }
            }
            if (why == null) {
                why = whyNoTaskCanFix(error, tasks, planned, sourceLine);
            }
            if (why != null) {
                broken.add(new Broken(error, why));
            }
        }
        return new Reading(inTests, broken, needs);
    }

    private static final String[] BUILD_FILE_NAMES = {"pom.xml", "build.gradle", "build.gradle.kts"};

    private static final Pattern IMPORT_LINE =
        Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;.*$");

    /** One need while it is being collected: its types grow as more imports are read. */
    private record Collected(String packageName, Set<String> types, String moduleDir,
                             String buildFile, List<Task> owners) {}

    /**
     * The dependencies the test's missing packages are, and (in {@code supplied}) every error they
     * explain: the missing-package error itself and the {@code cannot find symbol: class Y} errors
     * of the types it imports.
     */
    private static List<DependencyNeed> dependencyNeeds(List<CompileError> inTests,
            Collection<String> testFiles, List<Task> tasks, PlannedChanges planned,
            Function<CompileError, String> sourceLine, BiPredicate<String, String> packageInModule,
            Set<CompileError> supplied) {
        Map<String, Collected> byKey = new LinkedHashMap<>();
        Map<String, Set<String>> importedByFile = new LinkedHashMap<>();
        Set<String> wildcardFiles = new LinkedHashSet<>();
        for (CompileError error : inTests) {
            if (error.kind() != Kind.MISSING_PACKAGE || error.owner() == null) {
                continue;
            }
            if (whyNoTaskCanFix(error, tasks, planned, sourceLine) == null) {
                continue; // a task already creates it: nothing to rescue
            }
            String testFile = testFileOf(error.file(), testFiles);
            String moduleDir = testFile == null ? null : moduleDirOf(testFile);
            if (moduleDir == null) {
                continue;
            }
            if (packageInModule != null && packageInModule.test(moduleDir, error.owner())) {
                continue;
            }
            String buildFile = null;
            List<Task> owners = new ArrayList<>();
            for (String name : BUILD_FILE_NAMES) {
                String candidate = moduleDir.isEmpty() ? name : moduleDir + "/" + name;
                for (Task task : tasks) {
                    if (task != null && writes(task, candidate)) {
                        buildFile = buildFile == null ? candidate : buildFile;
                        if (buildFile.equals(candidate) && !owners.contains(task)) {
                            owners.add(task);
                        }
                    }
                }
            }
            if (buildFile == null) {
                continue;
            }
            supplied.add(error);
            String src = sourceLine == null ? null : sourceLine.apply(error);
            Matcher imp = src == null ? null : IMPORT_LINE.matcher(src);
            String imported = null;
            if (imp != null && imp.matches()) {
                if (imp.group(2) != null && imp.group(1).equals(error.owner())) {
                    wildcardFiles.add(error.file());
                } else if (imp.group(1).startsWith(error.owner() + ".")) {
                    String rest = imp.group(1).substring(error.owner().length() + 1);
                    int dot = rest.indexOf('.');
                    imported = dot < 0 ? rest : rest.substring(0, dot);
                    importedByFile.computeIfAbsent(error.file(), k -> new LinkedHashSet<>())
                        .add(imported);
                }
            }
            final String file = buildFile;
            Collected entry = byKey.computeIfAbsent(error.owner() + "|" + file,
                k -> new Collected(error.owner(), new LinkedHashSet<>(), moduleDir, file, owners));
            if (imported != null) {
                entry.types().add(imported);
            }
        }
        if (byKey.isEmpty()) {
            return List.of();
        }
        for (CompileError error : inTests) {
            if (error.kind() == Kind.UNRESOLVED_NAME && isTypeWord(error.symbolKind())
                    && (importedByFile.getOrDefault(error.file(), Set.of()).contains(error.symbolName())
                        || wildcardFiles.contains(error.file()))) {
                supplied.add(error);
            }
        }
        List<DependencyNeed> needs = new ArrayList<>();
        for (Collected c : byKey.values()) {
            needs.add(new DependencyNeed(c.packageName(), List.copyOf(c.types()), c.moduleDir(),
                c.buildFile(), c.owners()));
        }
        return needs;
    }

    /** The test file (repo-relative, as given) the printed path is one of; null when none. */
    private static String testFileOf(String printed, Collection<String> testFiles) {
        if (testFiles == null || printed == null) {
            return null;
        }
        String f = printed.replace('\\', '/');
        for (String t : testFiles) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String n = t.replace('\\', '/').strip();
            if (f.equals(n) || f.endsWith("/" + n)) {
                return n;
            }
        }
        return null;
    }

    /** {@code app-server/src/test/java/x/T.java} to {@code app-server}; "" for a root module; null when unreadable. */
    static String moduleDirOf(String repoRelativeFile) {
        if (repoRelativeFile.startsWith("src/")) {
            return "";
        }
        int at = repoRelativeFile.indexOf("/src/");
        return at < 0 ? null : repoRelativeFile.substring(0, at);
    }

    private static boolean writes(Task task, String path) {
        if (task.writeSet() == null) {
            return false;
        }
        for (String entry : task.writeSet()) {
            if (entry == null) {
                continue;
            }
            String n = entry.replace('\\', '/').strip();
            if (n.startsWith("./")) {
                n = n.substring(2);
            }
            if (n.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why a missing member can never be added, when its owner is a type whose source is not in the
     * project (it comes from a jar, or the JDK); null when the owner's source is in the project, or
     * nothing is known about where it lives.
     */
    /** Whether the type an error is about has no hand-written source in this project. */
    private static boolean hasNoSourceHere(String owner, Predicate<String> sourceInProject) {
        if (sourceInProject == null || owner == null || owner.isBlank()) {
            return false; // could not tell
        }
        return !sourceInProject.test(topLevelOf(owner.replace('$', '.').strip()));
    }

    /**
     * Whether a task writes a source file in the package of this type, or the folder it is in: the
     * one thing that can make the build generate the type differently.
     */
    private static boolean writesBeside(String owner, List<Task> tasks) {
        String top = topLevelOf(owner.replace('$', '.').strip());
        if (top.startsWith("java.") || top.startsWith("javax.")) {
            return false;
        }
        int dot = top.lastIndexOf('.');
        String pkg = dot < 0 ? "" : top.substring(0, dot);
        for (Task task : tasks) {
            if (task == null || task.writeSet() == null) {
                continue;
            }
            if (TypeDeliverability.mayWriteNarrowly(task, top)) {
                return true;
            }
            for (String entry : task.writeSet()) {
                String written = TypeDeliverability.typeNamed(entry);
                if (written == null) {
                    continue;
                }
                int at = written.lastIndexOf('.');
                if ((at < 0 ? "" : written.substring(0, at)).equals(pkg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String libraryOwner(CompileError error) {
        String owner = error.owner();
        return "`" + owner + "` is a library type: its source is not in this project, so no task can "
            + "ever add a " + error.symbolKind() + " `" + error.symbolName() + "` to it - the "
            + "test must use only what `" + owner + "` really offers";
    }

    /**
     * True when {@code message}, a failed candidate's first compile error as the verifier words it
     * ({@code cannot find symbol: method foo(int) in class a.b.C}), is a missing MEMBER of an
     * existing type that {@code task} - the one whose candidates all failed on it - can never
     * supply: it does not write that type's file and names it in no contract.
     *
     * <p>The mid-run counterpart of the red check's reading (live run 57, 2026-10-01): when every
     * candidate dies on this, the acceptance test is wrong and no repair worker can touch it. Only a
     * task with a write set is judged - an empty one means "unrestricted" to the rest of the
     * system, and for it nothing can be ruled out. A missing TYPE is not judged here: another task
     * of the same wave may deliver it.
     */
    public static boolean absenceNoTaskCanSupply(String message, String file, Task task) {
        if (message == null || task == null || task.writeSet() == null || task.writeSet().isEmpty()) {
            return false;
        }
        Matcher m = PROSE_ABSENCE.matcher(message.strip());
        if (!m.matches()) {
            return false;
        }
        List<String> detail = List.of("symbol: " + m.group(1) + " " + m.group(2).strip(),
            "location: " + m.group(3).strip());
        CompileError error = read(file == null ? "" : file.replace('\\', '/'), 0,
            "cannot find symbol", detail);
        return error.kind() == Kind.MISSING_MEMBER && !memberDeliverable(error.owner(), List.of(task));
    }

    /** The verifier's wording of an absence: {@code cannot find symbol: method foo(int) in class a.B}. */
    private static final Pattern PROSE_ABSENCE =
        Pattern.compile("^cannot find symbol:\\s+(\\S+)\\s+(.+?)\\s+in\\s+"
            + "((?:class|interface|enum|record|@interface|variable)\\s+.+)$");

    /** Null when some task still to run could make this error go away; otherwise why none can. */
    static String whyNoTaskCanFix(CompileError error, List<Task> tasks, PlannedChanges planned,
                                  Function<CompileError, String> sourceLine) {
        return switch (error.kind()) {
            case UNREADABLE_ABSENCE -> null; // could not tell: never read as broken
            case MISSING_TYPE, MISSING_PACKAGE ->
                TypeDeliverability.undeliverable(List.of(error.owner()), tasks).isEmpty() ? null
                    : "`" + error.owner() + "` does not exist, no contract in this plan names it, and "
                        + "no task's write set can create it";
            case MISSING_MEMBER -> memberDeliverable(error.owner(), tasks) ? null
                : "`" + error.owner() + "` exists and has no " + error.symbolKind() + " `"
                    + error.symbolName() + "`; no task in this plan writes `" + error.owner()
                    + "` or names it in a contract, so nothing will ever add it";
            case UNRESOLVED_NAME -> unresolvedNameDeliverable(error, tasks, planned) ? null
                : "`" + error.symbolName() + "` is not declared in the test, not imported, and "
                    + "nothing in this plan delivers a " + (isTypeWord(error.symbolKind())
                        ? "type" : "member") + " of that name — a missing import or a helper "
                    + "nobody wrote, which no task can fix";
            case MISUSE -> misuseIsPlanned(error, planned, sourceLine) ? null
                : "everything this line names already exists, and the test uses it in a way it "
                    + "does not accept; no task in this plan promises to change that signature, so "
                    + "no candidate can ever make this line compile";
        };
    }

    /**
     * True when the owner type of a missing member is one a still-to-run task could add it to: a
     * contract names the owner, or a write set can hold the owner's file. JDK owners never.
     */
    static boolean memberDeliverable(String owner, List<Task> tasks) {
        return memberDeliverable(owner, tasks, true);
    }

    /**
     * @param rootCoversAll whether a task owning a whole source root counts as able to write the
     *                      type; false for a type with no source in the project, which no such
     *                      task can reach (live run 57)
     */
    static boolean memberDeliverable(String owner, List<Task> tasks, boolean rootCoversAll) {
        if (owner == null || owner.isBlank()) {
            return true; // could not tell
        }
        String normalized = owner.replace('$', '.').strip();
        if (normalized.startsWith("java.") || normalized.startsWith("javax.")) {
            return false;
        }
        for (Task task : tasks) {
            if (task == null) {
                continue;
            }
            for (ApiContract contract : task.deliveredContracts()) {
                if (contract != null && contract.namesAType() && sameType(
                        contract.typeName().replace('$', '.').strip(), normalized)) {
                    return true;
                }
            }
            if (rootCoversAll ? TypeDeliverability.mayWrite(task, topLevelOf(normalized))
                    : TypeDeliverability.mayWriteNarrowly(task, topLevelOf(normalized))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A name the test's own class could not resolve. A type: some task supplies a type of that
     * simple name (contract, or a write-set file). A member: some contract lists a member of that
     * name, or the plan changes an existing type by adding one.
     */
    private static boolean unresolvedNameDeliverable(CompileError error, List<Task> tasks,
                                                     PlannedChanges planned) {
        String name = error.symbolName();
        if (name == null || name.isBlank()) {
            return true;
        }
        boolean type = isTypeWord(error.symbolKind());
        if (!type && planned.allMemberNames().contains(name)) {
            return true;
        }
        // `Texts.TITLE`, `Rules.validate(..)`: a type used through a static member is reported as
        // a missing variable, so a capitalised name is also looked for among the planned types.
        boolean typeLike = type || Character.isUpperCase(name.charAt(0));
        Pattern word = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        for (Task task : tasks) {
            if (task == null) {
                continue;
            }
            for (ApiContract contract : task.deliveredContracts()) {
                if (contract == null) {
                    continue;
                }
                if (typeLike && contract.namesAType() && name.equals(contract.simpleTypeName())) {
                    return true;
                }
                if (!type) {
                    for (String member : contract.members()) {
                        if (member != null && word.matcher(member).find()) {
                            return true;
                        }
                    }
                }
            }
            if (typeLike && task.writeSet() != null) {
                for (String entry : task.writeSet()) {
                    String named = TypeDeliverability.typeNamed(entry);
                    if (named != null && named.substring(named.lastIndexOf('.') + 1).equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * True when a misuse involves a member the plan promises to add or change on an existing type:
     * that member's simple name (or, for a promised constructor, the type's simple name) appears as
     * a whole word in the compiler's message, its follow-up lines, or the offending source line.
     */
    private static boolean misuseIsPlanned(CompileError error, PlannedChanges planned,
                                           Function<CompileError, String> sourceLine) {
        if (planned.membersByType().isEmpty()) {
            return false;
        }
        StringBuilder text = new StringBuilder(error.message());
        error.detail().forEach(d -> text.append('\n').append(d));
        String src = sourceLine == null ? null : sourceLine.apply(error);
        if (src != null) {
            text.append('\n').append(src);
        }
        String haystack = text.toString();
        for (String name : planned.allMemberNames()) {
            if (name != null && !name.isBlank()
                    && Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(haystack).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for a compile failure's message that is a misuse rather than an absence — for the
     * verification side, which holds only the first error's message as {@code CompileFailure}
     * words it (so "refers to X, which does not exist" and "cannot find symbol: ..." are
     * absences, alongside javac's own spellings). Blank is not a misuse: nothing was said.
     */
    public static boolean isMisuseMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.strip().toLowerCase(Locale.ROOT);
        return !(lower.startsWith("cannot find symbol")
            || PACKAGE_MISSING.matcher(message.strip()).matches()
            || (lower.startsWith("refers to ") && lower.endsWith("which does not exist")));
    }

    private static boolean inTestFiles(String file, Collection<String> testFiles) {
        if (testFiles == null || file == null) {
            return false;
        }
        String f = file.replace('\\', '/');
        for (String t : testFiles) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String n = t.replace('\\', '/').strip();
            if (f.equals(n) || f.endsWith("/" + n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameType(String a, String b) {
        return a.equalsIgnoreCase(b) || b.startsWith(a + ".") || a.startsWith(b + ".");
    }

    /**
     * The top-level type holding a (possibly nested) type: {@code org.jsoup.nodes.Document.OutputSettings}
     * lives in {@code Document.java}. A segment starting with an upper-case letter is taken as the
     * first type segment — Java's own naming convention, which is all a compiler message gives.
     */
    static String topLevelOf(String fullName) {
        String[] parts = fullName.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(sb.isEmpty() ? "" : ".").append(part);
            if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
                return sb.toString();
            }
        }
        return fullName;
    }
}
