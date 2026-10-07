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
package com.swarmcoder.app;

import com.swarmcoder.swarm.HarnessSandbox;
import com.swarmcoder.verify.VerifySpec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>The maintainer's own test, applied to the tree the swarm produced, and run there.</b>
 *
 * <p>This is the independent check design §4 exists for, and the reason the whole brownfield
 * harness is pointed at a project with closed issues rather than at a demo. The swarm wrote its own
 * reproduction from the same issue text, so "the swarm's test passes" only proves it satisfied its
 * own reading of the report. <b>The maintainer's test was written from the fix</b> and encodes the
 * reading that turned out to be right. The two agreeing is the evidence; the swarm's alone is not.
 *
 * <h2>What is applied, and what is never applied</h2>
 *
 * <p><b>Only the test-file half of the fix commit.</b> {@link #testOnlyHalfOf} asks git for the
 * commit's diff restricted to the case's declared test files and refuses outright if what comes
 * back touches anything else. The commit's SOURCE half — the actual fix — is never applied, never
 * read, never rendered, and never reaches any tree the swarm can see. If it were, the oracle would
 * be testing the maintainer's fix rather than the swarm's.
 *
 * <p>The application happens in a <b>separate scratch worktree</b> cut from the swarm's integration
 * commit, after the run has finished. Nothing here runs while a worker is alive, and the tree the
 * workers had is not modified.
 *
 * <h2>The four lines it reports</h2>
 *
 * <table>
 *   <tr><td>{@code swarm test}</td><td>the reproduction the swarm's own test author wrote, on the
 *       swarm's tree — passed / failed</td></tr>
 *   <tr><td>{@code human test}</td><td>the maintainer's test from the fix commit, on the swarm's
 *       tree — passed / failed / could not apply</td></tr>
 *   <tr><td>{@code existing suite}</td><td>the project's whole suite on the swarm's tree — green or
 *       N failures</td></tr>
 *   <tr><td>{@code files}</td><td>the source files the swarm touched, beside the source files the
 *       human touched, as two lists</td></tr>
 * </table>
 *
 * <p><b>Files touched is reported and never gated</b> (design §4). There is more than one correct
 * fix for most of these issues and a swarm that fixed the same bug one layer up has not failed. The
 * list is there so a person can see HOW it was fixed, and so a systematic pattern across five cases
 * becomes visible rather than argued from one.
 *
 * <h2>The pass rule, and the outcome that is more interesting than a pass</h2>
 *
 * <p>A case passes when <b>all three</b> hold: the swarm's own test passes, the human's test passes,
 * and the whole suite is green. Anything else is a fail, named by which line broke — and design
 * decision 5 asks for both halves of that: a fail for the count, and a NAMED outcome in the report,
 * because "the swarm's test passes but the maintainer's fails" is a different and far more
 * interesting failure than a run that never delivered. {@link Outcome} is that name.
 *
 * <p>Test-only, on purpose: <b>the product must never contain code that reads a known-good
 * answer.</b>
 */
final class HumanFixOracle {

    /** How a case ended, in the words a report line uses. Design decision 5(b). */
    enum Outcome {
        /** All three green: the swarm's own test, the maintainer's, and the whole suite. */
        FIXED_THE_THING,
        /** The swarm's test passes and the maintainer's does not — it fixed SOMETHING else. */
        FIXED_SOMETHING_NOT_THE_THING,
        /** The maintainer's test could not be applied to this tree at all. */
        ORACLE_UNAPPLIABLE,
        /** The swarm's own reproduction does not pass on the merged tree. */
        DID_NOT_FIX_IT,
        /** Both tests pass and the swarm broke something else in the project. */
        BROKE_SOMETHING_ELSE,
        /** The run never got far enough to have a merged tree to check. */
        NEVER_DELIVERED;

        /** One line a person reads, with no shared vocabulary assumed. */
        String sentence() {
            return switch (this) {
                case FIXED_THE_THING -> "the swarm's own test, the maintainer's own test and the "
                    + "project's whole suite are all green on the tree it produced";
                case FIXED_SOMETHING_NOT_THE_THING -> "the swarm's own test passes and the "
                    + "maintainer's does not: it changed something, and not the thing the issue "
                    + "was about";
                case ORACLE_UNAPPLIABLE -> "the maintainer's test could not be applied to this "
                    + "tree, so there was no independent check — this is not a pass";
                case DID_NOT_FIX_IT -> "the swarm's own reproduction still fails on the tree it "
                    + "merged, so by its own definition of done it did not fix it";
                case BROKE_SOMETHING_ELSE -> "both tests pass and the project's own suite does "
                    + "not: the change works and it broke something else";
                case NEVER_DELIVERED -> "the run never reached a merged tree, so there was "
                    + "nothing to check the maintainer's test against";
            };
        }
    }

    /** How long a targeted test class may take before the oracle stops waiting for it. */
    private static final int SINGLE_CLASS_TIMEOUT_SECONDS = 900;

    private HumanFixOracle() {
    }

    // ------------------------------------------------------------------------------------
    // The patch
    // ------------------------------------------------------------------------------------

    /**
     * The test-only half of the maintainer's fix, and the proof that it is only that.
     *
     * @param diff  the unified diff, ready for {@code git apply}, or "" when there is none
     * @param files the paths it touches, as git reports them
     * @param note  plain English: what was extracted, or why nothing could be
     */
    record Patch(String diff, List<String> files, String note) {

        boolean usable() {
            return !diff.isBlank() && !files.isEmpty();
        }
    }

    /**
     * {@code git show <fixSha> -- <the case's test files>} — the commit's diff restricted to its
     * test files, and nothing else.
     *
     * <p><b>The restriction is enforced twice.</b> git is asked for those paths only, and then what
     * comes back is read again: every file the diff names must be one of the declared test files.
     * A patch touching anything else is refused with its note rather than applied, because a single
     * source hunk slipping in here would silently turn the oracle into a test of the maintainer's
     * fix — the one failure mode this whole class exists to prevent, and one that would look like a
     * spectacular success.
     *
     * @param repo any worktree of the clone; the fix commit's objects are shared across them all
     */
    static Patch testOnlyHalfOf(Path repo, BrownfieldCases.Case one) {
        List<String> declaredTests = one.humanTestFiles();
        if (declaredTests == null || declaredTests.isEmpty()) {
            return new Patch("", List.of(), "the case names no test files, so there is no "
                + "independent oracle to apply at all");
        }
        List<String> argv = new ArrayList<>(List.of("git", "show", "--no-color", one.fixCommit(), "--"));
        argv.addAll(declaredTests);
        String diff;
        try {
            diff = git(repo, argv);
        } catch (Exception e) {
            return new Patch("", List.of(), "git could not show the test half of "
                + shortSha(one.fixCommit()) + ": " + e.getMessage());
        }
        // git show prints the commit MESSAGE above the diff, and a maintainer's message routinely
        // describes the fix. Everything above the first "diff --git" is dropped: it is not part of
        // the patch, it never reaches a prompt, and it must not reach a file on any tree either.
        int firstHunk = diff.indexOf("diff --git ");
        if (firstHunk < 0) {
            return new Patch("", List.of(), "the fix " + shortSha(one.fixCommit()) + " changes "
                + "none of the test files this case declares, so the case file and the commit "
                + "disagree about where the maintainer's test lives");
        }
        String patch = diff.substring(firstHunk);
        List<String> touched = filesIn(patch);
        List<String> unexpected = new ArrayList<>(touched);
        unexpected.removeAll(normalise(declaredTests));
        if (!unexpected.isEmpty()) {
            return new Patch("", touched, "the extracted patch touches " + unexpected
                + ", which the case does not declare as test files. Refusing to apply it: a "
                + "source hunk in the oracle's patch would make this a test of the maintainer's "
                + "fix rather than of the swarm's.");
        }
        return new Patch(patch, touched, "the test half of " + shortSha(one.fixCommit())
            + " touches " + touched.size() + " file(s) " + touched + " and nothing else");
    }

    // ------------------------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------------------------

    /**
     * Everything the oracle measured, in the order design §4 reports it.
     *
     * @param scratch          the throwaway worktree everything below ran in
     * @param swarmTest        the acceptance stage — the swarm's own reproduction — on that tree
     * @param humanTest        the maintainer's test class, after the patch was applied
     * @param wholeSuite       the project's whole suite, human test included
     * @param patch            what was applied, or why nothing was
     * @param applied          true when {@code git apply} accepted it
     * @param methodsOnTheTree the maintainer's test method names that really are in the file now
     * @param swarmSourceFiles the source files the swarm's integration commit changed
     */
    record Report(Path scratch, TargetRepository.SuiteRun swarmTest,
                  TargetRepository.SuiteRun humanTest, TargetRepository.SuiteRun wholeSuite,
                  Patch patch, boolean applied, List<String> methodsOnTheTree,
                  List<String> swarmSourceFiles, BrownfieldCases.Case one, Duration duration) {

        boolean swarmTestPassed() {
            return swarmTest != null && swarmTest.greenAndNotEmpty();
        }

        /**
         * The maintainer's test passed — which requires that it was applied, that its methods are
         * really on the tree, that the class ran, and that it was green.
         *
         * <p>The middle two matter as much as the last. A build tool that selected nothing exits 0
         * and reads exactly like a suite that ran and was green; that is this codebase's oldest
         * verification bug, and an oracle is the last place it may be repeated.
         */
        boolean humanTestPassed() {
            return applied && !methodsOnTheTree.isEmpty()
                && humanTest != null && humanTest.greenAndNotEmpty();
        }

        boolean wholeSuiteGreen() {
            return wholeSuite != null && wholeSuite.greenAndNotEmpty();
        }

        /** All three green — design §4's pass rule, and nothing else counts. */
        boolean passed() {
            return swarmTestPassed() && humanTestPassed() && wholeSuiteGreen();
        }

        /** Which shape of result this is — the named outcome of decision 5(b). */
        Outcome outcome() {
            if (scratch == null) {
                return Outcome.NEVER_DELIVERED;
            }
            if (passed()) {
                return Outcome.FIXED_THE_THING;
            }
            if (!applied) {
                return Outcome.ORACLE_UNAPPLIABLE;
            }
            if (!swarmTestPassed()) {
                return Outcome.DID_NOT_FIX_IT;
            }
            if (!humanTestPassed()) {
                return Outcome.FIXED_SOMETHING_NOT_THE_THING;
            }
            return Outcome.BROKE_SOMETHING_ELSE;
        }

        /** Line 1 of four: the swarm's own reproduction. */
        String swarmTestLine() {
            return "swarm test    : " + (swarmTest == null
                ? "never run — the run produced no merged tree to run it on"
                : (swarmTestPassed() ? "PASSED" : "FAILED") + " — " + swarmTest.describe()
                    + " (" + String.join(" ; ", swarmTest.commands()) + ")");
        }

        /** Line 2 of four: the maintainer's own test, the independent oracle. */
        String humanTestLine() {
            if (!applied) {
                return "human test    : COULD NOT APPLY — " + patch.note()
                    + ". That is not a pass: nothing independent checked this change.";
            }
            String methods = methodsOnTheTree.isEmpty()
                ? "and NONE of its method(s) " + one.humanTests() + " are on the tree, so whatever "
                    + "ran was not the maintainer's test"
                : "adding " + methodsOnTheTree.size() + " method(s) " + methodsOnTheTree;
            return "human test    : " + (humanTestPassed() ? "PASSED" : "FAILED") + " — "
                + patch.files() + " applied, " + methods + "; "
                + (humanTest == null ? "it was never run" : humanTest.describe());
        }

        /** Line 3 of four: everything the project already asserted about itself. */
        String suiteLine() {
            return "existing suite: " + (wholeSuite == null ? "never run"
                : (wholeSuiteGreen() ? "GREEN" : "NOT GREEN") + " — " + wholeSuite.describe());
        }

        /**
         * Line 4 of four: what each of them changed. Reported, never gated — there is more than
         * one right place to fix most of these.
         */
        String filesLine() {
            return "files         : swarm touched " + swarmSourceFiles
                + "; the human touched " + one.humanSourceFiles()
                + (swarmSourceFiles.equals(normalise(one.humanSourceFiles()))
                    ? " — the same file(s)"
                    : " — different files, which is not by itself a fault");
        }

        List<String> fourLines() {
            return List.of(swarmTestLine(), humanTestLine(), suiteLine(), filesLine());
        }

        /** The one line somebody reads instead of the four. */
        String verdict() {
            return (passed() ? "PASS" : "FAIL") + " [" + outcome() + "] " + one.name() + ": "
                + outcome().sentence() + " (oracle took " + duration.toSeconds() + "s)";
        }
    }

    /**
     * Cuts a scratch worktree from the swarm's integration commit and measures the four lines.
     *
     * @param caseTree          the case's own worktree — the tree the run worked in
     * @param one               the case, for its fix commit and its declared test files
     * @param spec              the verification contract, read back from the operator's tree
     * @param integrationCommit the commit {@code FinalIntegrator} merged the winner into
     * @param testsCommit       the run's acceptance-tests ref, so the swarm's own reproduction can
     *                          be put back on the tree exactly as verification put it there; null
     *                          when the run never authored any
     * @param baseCommit        the commit the tree was cut at, for the files-touched line
     * @param suiteSeconds      how long the whole suite may take
     */
    static Report run(Path caseTree, BrownfieldCases.Case one, VerifySpec spec,
                      String integrationCommit, String testsCommit, String baseCommit,
                      int suiteSeconds) throws Exception {
        Instant start = Instant.now();
        // The scratch tree holds what the swarm delivered, so every suite below runs in a
        // container that sees that tree and nothing else of this PC.
        com.swarmcoder.verify.BuildBoxes boxes = HarnessSandbox.boxes();
        Path scratch = scratchFor(one);
        deleteWorktree(caseTree, scratch);
        Files.createDirectories(scratch.getParent());
        git(caseTree, List.of("git", "worktree", "add", "--detach",
            scratch.toString(), integrationCommit));
        System.out.println("[ORACLE] scratch worktree at " + scratch + " cut from "
            + shortSha(integrationCommit));

        // 1. the swarm's own reproduction. Put back exactly where verification had it: on the
        //    run's tests ref, which is what every candidate was verified against.
        String testsNote = restoreTheSwarmsTests(scratch, testsCommit);
        System.out.println("[ORACLE] " + testsNote);
        TargetRepository.SuiteRun swarmTest = TargetRepository.runSuite(scratch,
            asExistingStage(spec, spec.acceptance(), spec.acceptanceReportDirs()),
            SINGLE_CLASS_TIMEOUT_SECONDS, boxes);
        System.out.println("[ORACLE] the swarm's own test: " + swarmTest.describe());

        // 2. the maintainer's test — the test half only, and the class it lives in.
        Patch patch = testOnlyHalfOf(caseTree, one);
        boolean applied = patch.usable() && apply(scratch, patch);
        List<String> methods = applied ? maintainersMethodsOn(scratch, one) : List.of();
        TargetRepository.SuiteRun humanTest = null;
        if (applied) {
            humanTest = TargetRepository.runSuite(scratch,
                asExistingStage(spec, List.of(humanTestCommand(spec, one)),
                    spec.existingReportDirs()),
                SINGLE_CLASS_TIMEOUT_SECONDS, boxes);
            System.out.println("[ORACLE] the maintainer's test: " + humanTest.describe());
        } else {
            System.out.println("[ORACLE] the maintainer's test could not be applied: "
                + patch.note());
        }

        // 3. the project's whole suite, with the maintainer's test now in it.
        TargetRepository.SuiteRun wholeSuite = TargetRepository.runSuite(scratch, spec, suiteSeconds, boxes);
        System.out.println("[ORACLE] the project's whole suite: " + wholeSuite.describe());

        List<String> swarmFiles = sourceFilesChanged(caseTree, baseCommit, integrationCommit);
        return new Report(scratch, swarmTest, humanTest, wholeSuite, patch, applied, methods,
            swarmFiles, one, Duration.between(start, Instant.now()));
    }

    /** The report for a run that never got as far as a merged tree — still four honest lines. */
    static Report neverDelivered(BrownfieldCases.Case one, String why) {
        return new Report(null, null, null, null, new Patch("", List.of(), why), false, List.of(),
            List.of(), one, Duration.ZERO);
    }

    // ------------------------------------------------------------------------------------
    // The pieces
    // ------------------------------------------------------------------------------------

    /**
     * Puts the swarm's own acceptance tests back on the tree, from the run's own tests ref.
     *
     * <p>The same source {@code FinalIntegrator} takes them from when it verifies each merge, so
     * "the swarm's test passes" here measures the same file the run's verification measured. A run
     * with no tests ref gets a note rather than an exception: that is a real finding about the run
     * and the other three lines are still worth having.
     */
    private static String restoreTheSwarmsTests(Path scratch, String testsCommit) {
        if (testsCommit == null || testsCommit.isBlank()) {
            return "the run recorded no acceptance-tests commit, so the swarm's own reproduction "
                + "is whatever the integration commit happens to carry";
        }
        try {
            git(scratch, List.of("git", "checkout", testsCommit, "--", "src/test/java/swarm"));
            return "the swarm's own acceptance tests restored from " + shortSha(testsCommit)
                + ", the ref every candidate was verified against";
        } catch (Exception e) {
            return "the run's tests commit " + shortSha(testsCommit) + " carries nothing under "
                + "src/test/java/swarm, so the swarm's own reproduction is whatever the "
                + "integration commit carries (" + e.getMessage().strip() + ")";
        }
    }

    /**
     * The command that runs only the maintainer's test class.
     *
     * <p>Selected by <b>path</b>, not by dotted class name. Wave 1 measured that a dotted package
     * selector silently matches nothing in surefire and a path pattern works, and a selector that
     * matches nothing exits 0 — which would read as an oracle that passed.
     */
    static String humanTestCommand(VerifySpec spec, BrownfieldCases.Case one) {
        String selector = String.join(",", testPathSelectors(one));
        if (!"maven".equals(spec.toolchain())) {
            // Honest rather than clever: nothing here knows how another build tool selects one
            // class, and inventing a selector that matches nothing is the worst possible answer.
            return "echo the oracle does not know how to run a single test class on a "
                + spec.toolchain() + " project && exit 1";
        }
        return "mvn -B test \"-Dtest=" + selector + "\" -Dsurefire.failIfNoSpecifiedTests=false";
    }

    /** {@code src/test/java/org/jsoup/nodes/DocumentTest.java} → {@code **}{@code /DocumentTest.java} */
    private static List<String> testPathSelectors(BrownfieldCases.Case one) {
        Set<String> selectors = new LinkedHashSet<>();
        for (String file : normalise(one.humanTestFiles())) {
            String name = file.substring(file.lastIndexOf('/') + 1);
            selectors.add("**/" + name);
        }
        return new ArrayList<>(selectors);
    }

    /**
     * Which of the maintainer's test methods are genuinely in the files on this tree now.
     *
     * <p>Read off the disk after the patch, not inferred from git's exit code. An applied patch
     * whose methods are not there is not a thing that should be possible, and that is exactly why
     * it is checked: the alternative is a green class that never contained the oracle.
     */
    private static List<String> maintainersMethodsOn(Path tree, BrownfieldCases.Case one) {
        List<String> found = new ArrayList<>();
        for (String test : one.humanTests()) {
            String method = test.contains("#") ? test.substring(test.indexOf('#') + 1) : test;
            for (String file : normalise(one.humanTestFiles())) {
                Path path = tree.resolve(file);
                try {
                    if (Files.isRegularFile(path)
                            && Files.readString(path, StandardCharsets.UTF_8).contains(method)) {
                        found.add(method);
                        break;
                    }
                } catch (Exception e) {                                    // noqa
                    // An unreadable file is the same answer for this question: not found here.
                }
            }
        }
        return found;
    }

    /** {@code git apply} in the scratch tree, through a file so no shell quoting is involved. */
    private static boolean apply(Path scratch, Patch patch) {
        try {
            Path file = Files.createTempFile("human-fix-test-half-", ".patch");
            Files.writeString(file, patch.diff(), StandardCharsets.UTF_8);
            git(scratch, List.of("git", "apply", "--whitespace=nowarn", file.toString()));
            Files.deleteIfExists(file);
            return true;
        } catch (Exception e) {
            System.out.println("[ORACLE] git apply refused the maintainer's test half: "
                + e.getMessage());
            return false;
        }
    }

    /**
     * The contract with one stage's commands moved into the {@code existing} slot.
     *
     * <p>{@link TargetRepository#runSuite} runs the {@code existing} stage and counts its reports,
     * and that counting — a suite that selected nothing exits 0 and reads green — is the whole
     * reason to reuse it rather than shell out. So the acceptance stage and the single-class run
     * are handed to it wearing that slot.
     */
    private static VerifySpec asExistingStage(VerifySpec spec, List<String> commands,
                                              List<String> reportDirs) {
        return new VerifySpec(spec.toolchain(), spec.compile(), spec.acceptance(),
            commands == null ? List.of() : commands, spec.lint(), spec.lintReports(),
            new VerifySpec.TestReportsSpec(List.of(), reportDirs),
            spec.timeoutSeconds(), spec.browser());
    }

    /** The files under a main source root that the swarm's merged commit changed. */
    private static List<String> sourceFilesChanged(Path repo, String from, String to) {
        if (from == null || to == null) {
            return List.of();
        }
        try {
            List<String> changed = new ArrayList<>();
            for (String line : git(repo, List.of("git", "diff", "--name-only", from, to)).split("\\R")) {
                String path = line.strip().replace('\\', '/');
                // Main sources only: the swarm's own acceptance tests live under a test root and
                // comparing them against the maintainer's source files would be a category error.
                if (!path.isEmpty() && path.contains("/main/")) {
                    changed.add(path);
                }
            }
            return changed;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Where the scratch worktree goes: beside the case trees, never inside the SwarmCoder repo. */
    private static Path scratchFor(BrownfieldCases.Case one) {
        return BrownfieldTarget.targetsDir()
            .resolve(one.target() + "-oracle").resolve(String.valueOf(one.issue()));
    }

    private static void deleteWorktree(Path caseTree, Path scratch) {
        try {
            git(caseTree, List.of("git", "worktree", "remove", "--force", scratch.toString()));
        } catch (Exception e) {                                            // noqa
            // Nothing there to remove is the ordinary case, not a finding.
        }
        try {
            if (Files.exists(scratch)) {
                try (var walk = Files.walk(scratch)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {                      // noqa
                            // A file that will not go is not worth failing an oracle over.
                        }
                    });
                }
            }
        } catch (Exception e) {                                            // noqa
            // Same: the worktree add below will say so if the directory is genuinely in the way.
        }
    }

    /**
     * Every file a unified diff names, from both its header lines.
     *
     * <p>A set, not a list: git prints {@code --- a/File} and {@code +++ b/File} for the same file,
     * so a list would name every changed file twice and a "touches exactly these files" check
     * against it could never hold.
     *
     * <p>Both header lines are read rather than only {@code +++}, because a DELETED file's
     * {@code +++} line is {@code /dev/null} and its real path appears only on the {@code ---}
     * line — and a patch that deletes something is exactly the shape this class must be able to
     * refuse.
     */
    private static List<String> filesIn(String patch) {
        Set<String> files = new LinkedHashSet<>();
        for (String line : patch.split("\\R")) {
            if (line.startsWith("+++ b/") || line.startsWith("--- a/")) {
                files.add(line.substring(6).strip().replace('\\', '/'));
            }
        }
        return new ArrayList<>(files);
    }

    private static List<String> normalise(List<String> paths) {
        List<String> out = new ArrayList<>();
        for (String path : paths == null ? List.<String>of() : paths) {
            out.add(path.replace('\\', '/').strip());
        }
        return out;
    }

    /**
     * git, with the arguments passed as arguments.
     *
     * <p>Not through a shell: this class passes file paths and a commit range to git, and a shell
     * in between is one quoting rule away from applying a patch nobody meant to apply.
     */
    private static String git(Path dir, List<String> argv) throws Exception {
        Process process = new ProcessBuilder(argv).directory(dir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", argv) + " failed in " + dir + ":\n"
                + out);
        }
        return out;
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? String.valueOf(sha) : sha.substring(0, 8);
    }
}
