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

import com.swarmcoder.domain.GuidelineCheck;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What is established once per run on the untouched tree the run starts from, before any worker
 * runs, so that it is never mistaken for something a candidate did (owner decisions after the
 * audit of 2026-10-02).
 *
 * <p><b>A house-rule check command that cannot run.</b> A rule's command that the container
 * cannot execute at all - the tool is not installed, the command is written for another
 * operating system's shell - used to exit non-zero on every candidate and read as "the candidate
 * broke the rule". It says nothing about any candidate. {@link #unrunnable} runs each command
 * once on the untouched tree and names the ones that could not run, with the reason; a command
 * that runs is not mentioned here whatever it exits with, and still decides candidates.
 *
 * <p><b>Tests that already fail.</b> {@link #existingTests} runs the existing-tests stage on the
 * same tree and names the tests that fail there - see {@link VerificationBaseline}.
 *
 * <p>Both run through the {@link ExecTarget} they are given, which is a container wherever
 * candidate verification runs in one.
 */
public final class BaseTreeChecks {

    private BaseTreeChecks() {}

    /** A rule's check command that could not be executed, and why. */
    public record UnrunnableCheck(GuidelineCheck check, String reason) {

        /** One sentence naming the rule, the command and the reason. */
        public String sentence() {
            return "the check command of the house rule '" + check.slug() + "' cannot run in the "
                + "build container: " + reason + ". The command is: " + check.command();
        }
    }

    /**
     * @param established false when the stage could not be run on the start tree at all (nothing
     *                    configured, the tree does not compile, no reports); nothing is then
     *                    known about it and candidates are judged as they were before
     * @param failing     the tests that fail on the start tree; empty when none do
     * @param note        why it was not established, or "" when it was
     */
    public record ExistingBaseline(boolean established, List<TestFailure> failing, String note) {

        static ExistingBaseline notEstablished(String note) {
            return new ExistingBaseline(false, List.of(), note);
        }

        public List<String> failingIds() {
            return failing.stream().map(TestFailure::testId).toList();
        }
    }

    /** The shell itself said it could not run what it was given. */
    private static final Pattern SHELL_REFUSED = Pattern.compile(
        "(?m)^(?:/\\S*/)?(?:sh|bash|dash|ash|zsh|ksh)(?:\\.exe)?: (.*(?:not found|[Ss]yntax error"
            + "|unexpected|No such file or directory|[Pp]ermission denied|bad substitution"
            + "|cannot execute).*)$");

    private static final Pattern NOT_A_COMMAND = Pattern.compile(
        "(?im)^.*(?:command not found|is not recognized as an internal or external command"
            + "|is not recognized as the name of a cmdlet).*$");

    /**
     * Runs every rule's check command once and returns the ones that could not be executed.
     *
     * @param fullLog told what was run and what came of it; may be null
     * @return empty when every command could be executed, or when the target itself could not be
     *         reached (nothing was established, so nothing is reported)
     */
    public static List<UnrunnableCheck> unrunnable(ExecTarget target, List<GuidelineCheck> checks,
                                                   StringBuilder fullLog) {
        List<UnrunnableCheck> found = new ArrayList<>();
        if (checks == null) {
            return found;
        }
        for (GuidelineCheck check : checks) {
            if (check == null || check.command() == null || check.command().isBlank()) {
                continue;
            }
            append(fullLog, "[base tree] rule " + check.slug() + " $ " + check.command());
            ExecResult result;
            try {
                result = target.exec(check.command(), check.effectiveTimeoutSeconds());
            } catch (IOException e) {
                append(fullLog, "[base tree] EXEC TARGET ERROR: " + e.getMessage());
                return List.of();
            }
            String reason = cannotRun(result);
            append(fullLog, "[base tree] rule " + check.slug() + " exit=" + result.exitCode()
                + (reason == null ? " - the command can run" : " - CANNOT RUN: " + reason));
            if (reason != null) {
                found.add(new UnrunnableCheck(check, reason));
            }
        }
        return found;
    }

    /**
     * Why a command could not be executed, or null when it was executed - whatever it exited with.
     * A command that ran and failed has said something about the tree; one that could not run has
     * said nothing about anything.
     */
    public static String cannotRun(ExecResult result) {
        if (result == null || result.timedOut() || result.exitCode() == 0) {
            return null;
        }
        String output = result.output() == null ? "" : result.output();
        Matcher shell = SHELL_REFUSED.matcher(output);
        Matcher notACommand = NOT_A_COMMAND.matcher(output);
        String said = shell.find() ? shell.group().strip()
            : notACommand.find() ? notACommand.group().strip() : null;
        if (result.exitCode() == 127 || result.exitCode() == 9009) {
            return "a command it calls was not found (exit " + result.exitCode() + ")"
                + (said == null ? "" : ": " + said);
        }
        if (result.exitCode() == 126) {
            return "a command it calls was found but cannot be executed (exit 126)"
                + (said == null ? "" : ": " + said);
        }
        if (said != null) {
            return "the shell could not run it (exit " + result.exitCode() + "): " + said;
        }
        return null;
    }

    /**
     * Runs the compile stage on the start tree and says what it did - see
     * {@link VerificationBaseline.StartCompile}. Not established when nothing is configured or
     * the target could not be reached.
     */
    public static VerificationBaseline.StartCompile compile(ExecTarget target, VerifySpec spec,
                                                            StringBuilder fullLog) {
        if (spec == null || spec.compile() == null || spec.compile().isEmpty()) {
            return VerificationBaseline.StartCompile.UNKNOWN;
        }
        for (String command : spec.compile()) {
            append(fullLog, "[base tree] compile $ " + command);
            try {
                ExecResult result = target.exec(command, spec.effectiveTimeoutSeconds());
                append(fullLog, "[base tree] compile exit=" + result.exitCode());
                if (result.timedOut()) {
                    return VerificationBaseline.StartCompile.UNKNOWN;
                }
                if (!result.succeeded()) {
                    return CompileFailureAttribution.startTree(
                        result.output() == null ? "" : result.output(),
                        target.localRoot().orElse(null));
                }
            } catch (IOException e) {
                append(fullLog, "[base tree] EXEC TARGET ERROR: " + e.getMessage());
                return VerificationBaseline.StartCompile.UNKNOWN;
            }
        }
        return CompileFailureAttribution.startTree(null, null);
    }

    /**
     * Runs the compile and existing-tests stages on the start tree and names what fails there.
     */
    public static ExistingBaseline existingTests(ExecTarget target, VerifySpec spec,
                                                 StringBuilder fullLog) {
        return existingTests(target, spec, fullLog, null);
    }

    /**
     * The same, told what {@link #compile} already found on this tree so the compile stage is
     * not run a second time.
     */
    public static ExistingBaseline existingTests(ExecTarget target, VerifySpec spec,
                                                 StringBuilder fullLog,
                                                 VerificationBaseline.StartCompile compiled) {
        if (spec == null || spec.existing() == null || spec.existing().isEmpty()) {
            return ExistingBaseline.notEstablished("no existing-tests stage is configured");
        }
        StringBuilder log = fullLog == null ? new StringBuilder() : fullLog;
        boolean compileKnown = compiled != null && compiled.established();
        if (compileKnown && !compiled.compiles()) {
            return ExistingBaseline.notEstablished(
                "the tree the run starts from does not compile");
        }
        if (spec.compile() != null && !compileKnown) {
            for (String command : spec.compile()) {
                append(log, "[base tree] compile $ " + command);
                try {
                    ExecResult result = target.exec(command, spec.effectiveTimeoutSeconds());
                    append(log, "[base tree] compile exit=" + result.exitCode());
                    if (!result.succeeded()) {
                        return ExistingBaseline.notEstablished(
                            "the tree the run starts from does not compile");
                    }
                } catch (IOException e) {
                    return ExistingBaseline.notEstablished(
                        "the build container could not be reached: " + e.getMessage());
                }
            }
        }
        TestStageRunner.StageOutcome outcome = TestStageRunner.run(target, "existing",
            spec.existing(), spec.existingReportDirs(), spec.effectiveTimeoutSeconds(), log);
        if (outcome.infrastructureError()) {
            return ExistingBaseline.notEstablished(
                "the existing-tests stage produced no result on the tree the run starts from");
        }
        TestResults results = outcome.results();
        List<TestFailure> failing = new ArrayList<>();
        if (results.failures() != null) {
            for (TestFailure failure : results.failures()) {
                if (failure != null && failure.testId() != null && !failure.testId().isBlank()) {
                    failing.add(failure);
                }
            }
        }
        return new ExistingBaseline(true, List.copyOf(failing), "");
    }

    private static void append(StringBuilder fullLog, String line) {
        if (fullLog != null) {
            fullLog.append(line).append('\n');
        }
    }
}
