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

import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.CompileFailureCause;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a failed compile stage's output and says whose fault it is.
 *
 * <p>The compile stage is one command, and for every JVM contract in this project that one command
 * is {@code compile test-compile} — Maven's {@code test-compile} phase runs {@code compile} first,
 * so the two are not separable by exit code and never were. They are separable by what the compiler
 * prints: every error names the file it is in, Maven names the goal that failed
 * ({@code testCompile} or {@code compile}), and the candidate's own diff says which files it wrote.
 * That is enough to tell four candidates that "the candidate does not compile" from four whose
 * files were fine in a tree whose acceptance tests import classes that exist nowhere.
 *
 * <p>Understood formats: Maven's {@code [ERROR] /path/File.java:[line,col] message} (with the
 * {@code symbol:} / {@code location:} lines that follow a missing symbol), javac and Gradle's
 * {@code /path/File.java:line: error: message}, Kotlin's {@code e: file:///path/File.kt:line:col
 * message}, and tsc's {@code path/file.ts(line,col): error TSnnnn: message}. Anything else yields
 * {@link CompileFailureCause#UNATTRIBUTED}, which keeps the old sentence: not knowing is reported
 * as not knowing, never as a guess.
 *
 * <p><b>What it does not know.</b> It sees the files the candidate added or changed, not the ones
 * it deleted, so a candidate that removed a class an untouched test imports would be read as a
 * pre-existing failure. Workers write files; they do not delete them; the hole is narrow and it is
 * named here rather than papered over.
 */
public final class CompileFailureAttribution {

    private CompileFailureAttribution() {}

    /** Maven: {@code [ERROR] /path/File.java:[9,13] cannot find symbol}. */
    private static final Pattern MAVEN =
        Pattern.compile("^\\[ERROR\\]\\s+(.+?):\\[(\\d+),\\d+\\]\\s*(.*)$");
    /** javac / Gradle: {@code /path/File.java:12: error: cannot find symbol}. */
    private static final Pattern JAVAC =
        Pattern.compile("^(?:\\[ERROR\\]\\s+)?(.+?\\.(?:java|scala|groovy)):(\\d+):\\s*error:\\s*(.*)$");
    /** Kotlin: {@code e: file:///path/File.kt:12:5 unresolved reference}. */
    private static final Pattern KOTLIN =
        Pattern.compile("^e:\\s*(?:file://)?(.+?\\.kts?):(\\d+):\\d+\\s*(.*)$");
    /** tsc: {@code src/app.ts(3,5): error TS2304: Cannot find name 'Book'.} */
    private static final Pattern TSC =
        Pattern.compile("^(.+?\\.(?:ts|tsx|js|jsx|mts|cts))\\((\\d+),\\d+\\):\\s*error\\s+(.*)$");
    /** javac's follow-up to a missing symbol: {@code   symbol:   class Book}. */
    private static final Pattern SYMBOL =
        Pattern.compile("^(?:\\[ERROR\\])?\\s+symbol:\\s+(\\S+)\\s+(.+?)\\s*$");
    /** javac's follow-up to a missing symbol: {@code   location: package swarm}. */
    private static final Pattern LOCATION =
        Pattern.compile("^(?:\\[ERROR\\])?\\s+location:\\s+(.+?)\\s*$");
    private static final Pattern PACKAGE_MISSING =
        Pattern.compile("^package\\s+(\\S+)\\s+does not exist$");

    /** Maven says which compiler goal failed; Gradle says which task. Test first: it is the rarer word. */
    private static final Pattern TEST_STAGE = Pattern.compile(
        "compiler-plugin\\S*:testCompile|kotlin-maven-plugin\\S*:test-compile|Task :\\S*compileTest\\w* FAILED");
    private static final Pattern MAIN_STAGE = Pattern.compile(
        "compiler-plugin\\S*:compile\\b|kotlin-maven-plugin\\S*:compile\\b|Task :\\S*compile(?!Test)\\w* FAILED");

    private static final int MAX_EXPLANATION = 240;

    private record Err(String raw, String rel, int line, String message) {}

    /**
     * @param output            everything the failed compile command printed
     * @param changedFiles      repo-relative paths the candidate added or changed; empty means the
     *                          caller does not know, and then nothing is attributed
     * @param acceptanceTestDir the task's protected acceptance-test directory, repo-relative; may
     *                          be null
     * @param localRoot         the workspace root when the target has one, so absolute paths can be
     *                          made repo-relative; may be null
     * @param compiledRoots     the repo-relative source roots the build was found to compile, used
     *                          the same way when there is no local root (a sandbox); may be null
     */
    public static CompileFailure attribute(String output, Set<String> changedFiles, String acceptanceTestDir,
                                           Path localRoot, List<String> compiledRoots) {
        return attribute(output, changedFiles, acceptanceTestDir, localRoot, compiledRoots, null);
    }

    /**
     * The same, told what the compile stage did on the tree the candidate was cut from.
     *
     * <p>Live run 74, 2026-10-03: a candidate added two methods to an interface, the class that
     * implements it - a file the candidate did not touch - stopped compiling, and the verdict
     * said "the tree does not compile before this candidate's change ... not its work". The tree
     * had compiled. Without a measurement of the start tree an error in an untouched file can
     * only be guessed at; with one, an error in a file that was clean there is caused by the
     * change, whichever file reports it, and the verdict says so, names what was changed and
     * names the untouched file that has to change with it.
     *
     * @param startCompile what the start tree's compile did; null or not established keeps the
     *                     old reading
     */
    public static CompileFailure attribute(String output, Set<String> changedFiles, String acceptanceTestDir,
                                           Path localRoot, List<String> compiledRoots,
                                           VerificationBaseline.StartCompile startCompile) {
        Set<String> changed = normalizeAll(changedFiles);
        String acceptDir = acceptanceTestDir == null || acceptanceTestDir.isBlank()
            ? null : BuildLayout.normalize(acceptanceTestDir);
        List<String> roots = new ArrayList<>();
        if (compiledRoots != null) {
            for (String root : compiledRoots) {
                String n = BuildLayout.normalize(root);
                if (n != null && !n.isEmpty()) {
                    roots.add(n);
                }
            }
        }
        String root = localRoot == null ? null
            : localRoot.toAbsolutePath().normalize().toString().replace('\\', '/');

        // Asked FIRST, because a resolution failure happens BEFORE any compiler runs: there are no
        // file-and-line errors in that output to find, so without this the whole thing reads as
        // "the candidate does not compile, and the compiler named no file" — which tells a judge
        // nothing about the one thing that actually went wrong.
        CompileFailure unresolvable = unresolvableDependency(output == null ? "" : output, changed);
        if (unresolvable != null) {
            return unresolvable;
        }

        List<Err> errors = parse(output == null ? "" : output, changed, acceptDir, root, roots);
        List<String> failingFiles = new ArrayList<>();
        for (Err err : errors) {
            if (!failingFiles.contains(err.rel())) {
                failingFiles.add(err.rel());
            }
        }
        boolean testStageFailed = TEST_STAGE.matcher(output == null ? "" : output).find();
        boolean mainStageFailed = !testStageFailed && MAIN_STAGE.matcher(output == null ? "" : output).find();

        if (errors.isEmpty()) {
            return new CompileFailure(CompileFailureCause.UNATTRIBUTED, null, 0, null, List.of(),
                false, false, testStageFailed, firstErrorLine(output));
        }
        boolean everyFileIsATest = true;
        for (Err err : errors) {
            if (!isTest(err.rel(), acceptDir)) {
                everyFileIsATest = false;
            }
        }
        boolean mainCompiled = testStageFailed || (!mainStageFailed && everyFileIsATest);

        if (changed.isEmpty()) {
            Err first = errors.get(0);
            return new CompileFailure(CompileFailureCause.UNATTRIBUTED, first.rel(), first.line(),
                first.message(), failingFiles, isTest(first.rel(), acceptDir),
                isAcceptance(first.rel(), acceptDir), mainCompiled,
                "which files this candidate changed is not known, so whether this is the "
                    + "candidate's fault or the tree's could not be established");
        }

        Err ownMain = null;
        Err ownTest = null;
        for (Err err : errors) {
            if (!isChanged(err, changed)) {
                continue;
            }
            if (isTest(err.rel(), acceptDir)) {
                if (ownTest == null) {
                    ownTest = err;
                }
            } else if (ownMain == null) {
                ownMain = err;
            }
        }
        if (ownMain != null) {
            return new CompileFailure(CompileFailureCause.CANDIDATE, ownMain.rel(), ownMain.line(),
                ownMain.message(), failingFiles, false, false, false,
                "an error in a file this candidate added or changed");
        }
        if (ownTest != null) {
            return new CompileFailure(CompileFailureCause.TEST_TREE, ownTest.rel(), ownTest.line(),
                ownTest.message(), failingFiles, true, isAcceptance(ownTest.rel(), acceptDir), true,
                "main code compiled; a test file this candidate changed does not");
        }
        if (startCompile != null && startCompile.established()) {
            List<String> brokenFiles = new ArrayList<>();
            Err broken = null;
            for (Err err : errors) {
                if (startCompile.wasCleanOnTheStartTree(err.rel(), isTest(err.rel(), acceptDir))) {
                    if (broken == null) {
                        broken = err;
                    }
                    if (!brokenFiles.contains(err.rel())) {
                        brokenFiles.add(err.rel());
                    }
                }
            }
            if (broken != null) {
                return new CompileFailure(CompileFailureCause.CAUSED_BY_CHANGE, broken.rel(),
                    broken.line(), broken.message(), failingFiles,
                    isTest(broken.rel(), acceptDir), isAcceptance(broken.rel(), acceptDir),
                    mainCompiled, causedByChange(broken, brokenFiles, changed));
            }
        }
        Err first = errors.get(0);
        return new CompileFailure(CompileFailureCause.PRE_EXISTING, first.rel(), first.line(),
            first.message(), failingFiles, isTest(first.rel(), acceptDir),
            isAcceptance(first.rel(), acceptDir), mainCompiled,
            "every error is in a file this candidate did not add or change");
    }

    /**
     * What the candidate changed that the broken file depends on, and what has to happen: the
     * changed files whose type the compiler's message names, else every file it changed.
     */
    private static String causedByChange(Err broken, List<String> brokenFiles, Set<String> changed) {
        List<String> named = new ArrayList<>();
        String message = broken.message() == null ? "" : broken.message();
        for (String path : changed) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            int dot = name.indexOf('.');
            String stem = dot <= 0 ? name : name.substring(0, dot);
            if (!stem.isEmpty() && Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(stem)
                    + "(?![A-Za-z0-9_$])").matcher(message).find()) {
                named.add(stem + " (" + path + ")");
            }
        }
        String what = named.isEmpty()
            ? "it changed " + String.join(", ", changed.stream().limit(4).toList())
                + (changed.size() > 4 ? " and " + (changed.size() - 4) + " more" : "")
            : "it changed " + String.join(", ", named);
        String others = brokenFiles.size() <= 1 ? broken.rel()
            : String.join(", ", brokenFiles.stream().limit(6).toList())
                + (brokenFiles.size() > 6 ? " and " + (brokenFiles.size() - 6) + " more" : "");
        return what + ", and " + others + " must change with it - "
            + (brokenFiles.size() <= 1 ? "a file" : "files") + " this candidate did not change";
    }

    /**
     * What a failed compile of the START tree says, as the baseline candidates are read against.
     *
     * @param failedOutput what the failed compile command printed; null when the stage passed
     */
    public static VerificationBaseline.StartCompile startTree(String failedOutput, Path localRoot) {
        if (failedOutput == null) {
            return new VerificationBaseline.StartCompile(true, true, true, Set.of());
        }
        CompileFailure read = attribute(failedOutput, Set.of(), null, localRoot, List.of());
        List<String> files = read.failingFiles() == null ? List.of() : read.failingFiles();
        if (files.isEmpty()) {
            // It failed and named no file: nothing can be said about any file.
            return VerificationBaseline.StartCompile.UNKNOWN;
        }
        return new VerificationBaseline.StartCompile(true, false, read.mainCompiled(),
            new LinkedHashSet<>(files));
    }

    // ------------------------------------------------------- a dependency that would not resolve

    /**
     * Maven saying it could not get an artifact. Every wording since 3.6 lands on one of these.
     * {@code Non-resolvable} covers a parent or an imported BOM, which fail before the reactor
     * even starts.
     */
    private static final Pattern RESOLUTION_FAILED = Pattern.compile(
        "(?i)could not resolve dependencies|artifacts? could not be resolved"
        + "|in offline mode and the artifact|non-resolvable (?:parent|import) pom");

    /**
     * A Maven coordinate as the resolver prints it: {@code group:artifact:type[:classifier]:version}.
     * The group is required to look like a group (a dot in it), which is what keeps this off the
     * {@code project:file:line} shapes elsewhere in a build log.
     */
    private static final Pattern COORDINATE = Pattern.compile(
        "\\b([A-Za-z][A-Za-z0-9_\\-]*(?:\\.[A-Za-z0-9_\\-]+)+)"
        + ":([A-Za-z0-9_][A-Za-z0-9_.\\-]*)"
        + ":(?:jar|pom|war|ear|test-jar|zip|bundle|maven-plugin)"
        + "(?::[A-Za-z0-9_\\-]+)?"
        + ":([A-Za-z0-9][A-Za-z0-9_.\\-]*)\\b");

    /** {@code Could not resolve dependencies for project <coord>} — the project, not the miss. */
    private static final Pattern FOR_PROJECT = Pattern.compile("(?i)for project \\S+");

    /** The build files a candidate may have written; a change to one is what makes this its fault. */
    private static boolean isBuildFile(String path) {
        String name = path == null ? "" : path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = slash < 0 ? name : name.substring(slash + 1);
        return "pom.xml".equals(name) || "build.gradle".equals(name)
            || "build.gradle.kts".equals(name);
    }

    /**
     * The failure a worker can now cause and could not before: it declared a dependency the offline
     * Maven repository does not hold.
     *
     * <p>Workers may edit a module's build file since 2026-09-03 — the change that let a swarm add
     * its own dependency instead of waiting for a person to add one line to a pom. The cost of that
     * is this failure, and it is worth naming exactly: the build says which coordinate it could not
     * find, the candidate's diff says which build file it wrote, and the two together are a
     * complete account.
     *
     * <p>When the candidate changed no build file the coordinate is still named, but nothing is
     * blamed on the candidate: the declaration was in the tree before it arrived, and the repository
     * is simply short of an artifact. That is not something the candidate can fix and it must not
     * read as though it were.
     *
     * @return null when the output is not a resolution failure at all — every other reading of the
     *         compile stage is unchanged
     */
    static CompileFailure unresolvableDependency(String output, Set<String> changed) {
        if (!RESOLUTION_FAILED.matcher(output).find()) {
            return null;
        }
        String coordinate = null;
        for (String line : output.split("\r?\n")) {
            if (!RESOLUTION_FAILED.matcher(line).find()) {
                continue;
            }
            // The project whose build failed is printed in the same sentence as the artifact that
            // could not be found; naming the project instead would be exactly backwards.
            Matcher m = COORDINATE.matcher(FOR_PROJECT.matcher(line).replaceAll("for project"));
            if (m.find()) {
                coordinate = m.group(1) + ":" + m.group(2) + ":" + m.group(3);
                break;
            }
        }
        if (coordinate == null) {
            return null;    // it said something went wrong but never said what; nothing to add
        }
        String buildFile = null;
        for (String path : changed) {
            if (isBuildFile(path) && (buildFile == null || path.length() < buildFile.length())) {
                buildFile = path;
            }
        }
        String message = "`" + coordinate + "`";
        if (buildFile == null) {
            return new CompileFailure(CompileFailureCause.UNATTRIBUTED, null, 0, null, List.of(),
                false, false, false, changed.isEmpty()
                    ? "the build could not resolve " + coordinate + " from the offline Maven "
                        + "repository; which files this candidate changed is not known, so whether "
                        + "it declared that dependency could not be established"
                    : "the build could not resolve " + coordinate + " from the offline Maven "
                        + "repository, and this candidate changed no build file — the declaration "
                        + "was already in the tree, so this is the repository being short of an "
                        + "artifact rather than anything this candidate did");
        }
        return new CompileFailure(CompileFailureCause.UNRESOLVABLE_DEPENDENCY, buildFile, 0,
            message, List.of(buildFile), false, false, false,
            "this candidate changed " + buildFile + " and the build then could not resolve "
                + coordinate + " offline; declare only a library the knowledge brief lists as "
                + "available");
    }

    /**
     * Every type the compiler said it could not find, fully qualified, in the order it said them.
     *
     * <p>Read out of the SAME follow-up lines {@link #prose} already reads — {@code symbol: class
     * Rating} and {@code location: package com.acme.shop} — so the sentence a person sees and the
     * list a gate decides on cannot disagree. It answers only for a class-like symbol whose
     * package the compiler named, plus a package that does not exist at all; a missing method or
     * variable is not a type and is not reported here.
     *
     * <p>Empty means "we could not tell", never "nothing is missing". Every caller treats it that
     * way: an unparsed compiler output must not stop a run.
     */
    public static List<String> missingTypes(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        String[] lines = output.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String message = messageOf(lines[i]);
            if (message == null) {
                continue;
            }
            Matcher missingPackage = PACKAGE_MISSING.matcher(message.strip());
            if (missingPackage.matches()) {
                found.add(missingPackage.group(1));
                continue;
            }
            if (!message.toLowerCase(Locale.ROOT).strip().startsWith("cannot find symbol")) {
                continue;
            }
            String kind = null;
            String name = null;
            String location = null;
            for (int j = i + 1; j < lines.length && j <= i + 3; j++) {
                Matcher symbol = SYMBOL.matcher(lines[j]);
                if (symbol.matches()) {
                    kind = symbol.group(1);
                    name = symbol.group(2);
                    continue;
                }
                Matcher where = LOCATION.matcher(lines[j]);
                if (where.matches()) {
                    location = where.group(1);
                    continue;
                }
                if (kind != null || location != null) {
                    break;
                }
            }
            boolean typeLike = "class".equals(kind) || "interface".equals(kind)
                || "enum".equals(kind) || "record".equals(kind);
            if (typeLike && name != null && location != null && location.startsWith("package ")) {
                found.add(location.substring("package ".length()).strip() + "." + name.strip());
            }
        }
        return List.copyOf(found);
    }

    /** The compiler's message on one error line, whichever of the four formats it is in. */
    private static String messageOf(String line) {
        for (Pattern pattern : List.of(MAVEN, JAVAC, KOTLIN, TSC)) {
            Matcher m = pattern.matcher(line);
            if (m.matches()) {
                return m.group(3);
            }
        }
        return null;
    }

    private static List<Err> parse(String output, Set<String> changed, String acceptDir,
                                   String localRoot, List<String> roots) {
        List<Err> errors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String[] lines = output.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String path = null;
            int at = 0;
            String message = null;
            Matcher m = MAVEN.matcher(line);
            if (m.matches()) {
                path = m.group(1);
                at = Integer.parseInt(m.group(2));
                message = m.group(3);
            } else if ((m = JAVAC.matcher(line)).matches()) {
                path = m.group(1);
                at = Integer.parseInt(m.group(2));
                message = m.group(3);
            } else if ((m = KOTLIN.matcher(line)).matches()) {
                path = m.group(1);
                at = Integer.parseInt(m.group(2));
                message = m.group(3);
            } else if ((m = TSC.matcher(line)).matches()) {
                path = m.group(1);
                at = Integer.parseInt(m.group(2));
                message = m.group(3);
            }
            if (path == null) {
                continue;
            }
            String raw = path.trim().replace('\\', '/');
            if (raw.startsWith("file:")) {
                raw = raw.substring("file:".length());
            }
            String prose = prose(message.trim(), lines, i);
            String key = raw + ":" + at + ":" + prose;
            if (!seen.add(key)) {
                continue; // Maven prints every error twice: once as it happens, once in the summary
            }
            errors.add(new Err(raw, relativize(raw, changed, acceptDir, localRoot, roots), at, prose));
        }
        return errors;
    }

    /**
     * The compiler's message as a sentence an operator can read without knowing javac. Only the
     * two messages that account for nearly every failure this project has seen are rewritten; the
     * rest pass through as the compiler said them.
     */
    private static String prose(String message, String[] lines, int index) {
        Matcher pkg = PACKAGE_MISSING.matcher(message);
        if (pkg.matches()) {
            return "refers to package " + pkg.group(1) + ", which does not exist";
        }
        if (!message.toLowerCase(Locale.ROOT).startsWith("cannot find symbol")) {
            return message;
        }
        String symbolKind = null;
        String symbolName = null;
        String location = null;
        for (int j = index + 1; j < lines.length && j <= index + 3; j++) {
            Matcher s = SYMBOL.matcher(lines[j]);
            if (s.matches()) {
                symbolKind = s.group(1);
                symbolName = s.group(2);
                continue;
            }
            Matcher l = LOCATION.matcher(lines[j]);
            if (l.matches()) {
                location = l.group(1);
                continue;
            }
            if (symbolKind != null || location != null) {
                break;
            }
        }
        if (symbolName == null) {
            return message;
        }
        if (location != null && location.startsWith("package ")
                && ("class".equals(symbolKind) || "interface".equals(symbolKind)
                    || "enum".equals(symbolKind) || "record".equals(symbolKind))) {
            return "refers to " + location.substring("package ".length()).trim() + "." + symbolName
                + ", which does not exist";
        }
        return "cannot find symbol: " + symbolKind + " " + symbolName
            + (location == null ? "" : " in " + location);
    }

    /**
     * The compiler prints an absolute path — {@code /workspace/...} in a sandbox, the worktree's
     * own path locally — and the sentence needs the repo-relative one, because that is what the
     * operator and the candidate's diff both use. Each strategy below is tried in turn; when none
     * applies the path is shown as the compiler printed it, which is still true.
     */
    private static String relativize(String raw, Set<String> changed, String acceptDir,
                                     String localRoot, List<String> roots) {
        if (localRoot != null && !localRoot.isEmpty()) {
            String prefix = localRoot.endsWith("/") ? localRoot : localRoot + "/";
            if (raw.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return BuildLayout.normalize(raw.substring(prefix.length()));
            }
        }
        for (String c : changed) {
            if (raw.equals(c) || raw.endsWith("/" + c)) {
                return c;
            }
        }
        if (acceptDir != null) {
            int at = raw.indexOf("/" + acceptDir + "/");
            if (at >= 0) {
                return raw.substring(at + 1);
            }
            if (raw.startsWith(acceptDir + "/")) {
                return raw;
            }
        }
        for (String root : roots) {
            int at = raw.indexOf("/" + root + "/");
            if (at >= 0) {
                return raw.substring(at + 1);
            }
        }
        int workspace = raw.indexOf("/workspace/");
        if (workspace >= 0) {
            return raw.substring(workspace + "/workspace/".length());
        }
        return raw;
    }

    private static boolean isChanged(Err err, Set<String> changed) {
        if (changed.contains(err.rel())) {
            return true;
        }
        for (String c : changed) {
            if (err.raw().equals(c) || err.raw().endsWith("/" + c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAcceptance(String rel, String acceptDir) {
        return acceptDir != null && (rel.equals(acceptDir) || rel.startsWith(acceptDir + "/"));
    }

    private static boolean isTest(String rel, String acceptDir) {
        return isAcceptance(rel, acceptDir) || rel.startsWith("src/test/") || rel.contains("/src/test/");
    }

    private static Set<String> normalizeAll(Collection<String> paths) {
        Set<String> out = new LinkedHashSet<>();
        if (paths != null) {
            for (String p : paths) {
                String n = BuildLayout.normalize(p);
                if (n != null && !n.isEmpty()) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /** The first line that looks like the build tool saying what went wrong, for the sentence. */
    private static String firstErrorLine(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith("[ERROR]")) {
                t = t.substring("[ERROR]".length()).trim();
            }
            if (t.isEmpty() || t.startsWith("->") || t.startsWith("[Help") || t.startsWith("To see ")
                    || t.startsWith("Re-run ") || t.startsWith("For more ") || t.startsWith("After correcting")
                    || t.startsWith("mvn ") || t.startsWith("COMPILATION ERROR")) {
                continue;
            }
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("fail") || lower.contains("could not")) {
                return t.length() > MAX_EXPLANATION ? t.substring(0, MAX_EXPLANATION) + "…" : t;
            }
        }
        return null;
    }
}
