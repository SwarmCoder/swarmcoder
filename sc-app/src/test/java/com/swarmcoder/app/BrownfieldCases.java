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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>The cases the brownfield harness runs: a real issue against a real repository, and the fix a
 * human actually shipped for it.</b>
 *
 * <p>Read from {@code dev/brownfield/jsoup-cases.json}. Each case is one closed GitHub issue whose
 * fix commit is known, together with the commit's parent — the tree the swarm is given — and the
 * test the maintainer added, which is the independent oracle a later wave checks the swarm's work
 * against.
 *
 * <h2>Why the issue text is stored and not fetched</h2>
 *
 * <p>Two reasons, and both are about the measurement being repeatable. The harness must be runnable
 * with no network, because a case that cannot run offline is a case that stops being runnable the
 * first time GitHub is slow. And a case must not change because somebody edited a GitHub issue: a
 * stored body is the same prompt in six months, so a run today and a run then are comparable. The
 * text is copied verbatim from the issue body — not the maintainer's commit message, which was
 * written knowing the answer.
 *
 * <h2>What the swarm is never shown</h2>
 *
 * <p>{@link Case#issueText} is the whole input. {@link Case#fixCommit},
 * {@link Case#humanSourceFiles}, {@link Case#humanTestFiles} and {@link Case#humanTests} exist so
 * the harness can cut the tree at the right commit and, later, run the maintainer's test — none of
 * them may enter a prompt, a brief, or any file on the tree the workers see. The tree itself is cut
 * at {@link Case#parentCommit}, so the fix is not on it and cannot be read.
 *
 * <h2>Selecting one</h2>
 *
 * <p>{@code -Dswarmcoder.brownfield.case=2187} runs that issue alone; {@code all} (the default) runs
 * every case in the file. One at a time is the ordinary way to run a live case — the GPU is shared.
 */
final class BrownfieldCases {

    /** Where the case file lives, relative to the repository root. */
    static final String CASE_FILE = "dev/brownfield/jsoup-cases.json";

    /** Selects one case by issue number, or {@code all}. */
    static final String CASE_PROPERTY = "swarmcoder.brownfield.case";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrownfieldCases() {
    }

    /**
     * One issue, the tree it is asked against, and the fix a human shipped for it.
     *
     * @param target            the repository this case belongs to, matching {@link File#target}
     * @param issue             the GitHub issue number — the case's name in every log line
     * @param title             the issue title, used as the change request's short name
     * @param kind              {@code BUGFIX} or {@code ENHANCEMENT}; the run kind
     * @param issueText         the body of the issue, verbatim — the swarm's whole input
     * @param fixCommit         the commit that fixed it; never shown to the swarm
     * @param fixCommitDate     when it landed, for reading the case file
     * @param parentCommit      the fix's parent — the commit every case worktree is cut at
     * @param humanSourceFiles  the files under a main source root the fix changed; reported beside
     *                          the swarm's, never gated on
     * @param humanTestFiles    the test files the fix added to, which a later wave applies alone
     * @param humanTests        the maintainer's tests as {@code pkg.Class#method}
     * @param why               one sentence on why this case qualifies
     */
    record Case(
        String target,
        int issue,
        String title,
        String kind,
        String issueText,
        String fixCommit,
        String fixCommitDate,
        String parentCommit,
        List<String> humanSourceFiles,
        List<String> humanTestFiles,
        List<String> humanTests,
        String why
    ) {
        /** The case's name in a log line or a worktree directory. */
        String name() {
            return target + "#" + issue;
        }

        /** The test classes the maintainer's tests live in, as {@code pkg.Class}. */
        List<String> humanTestClasses() {
            List<String> classes = new ArrayList<>();
            for (String test : humanTests) {
                String cls = test.contains("#") ? test.substring(0, test.indexOf('#')) : test;
                if (!classes.contains(cls)) {
                    classes.add(cls);
                }
            }
            return classes;
        }

        @Override
        public String toString() {
            // Surfaces as the @ParameterizedTest display name, so it must read as the case.
            return name() + " " + title;
        }
    }

    /**
     * A line of the detected contract this target needs replaced, and why.
     *
     * <p>{@code ToolchainDetector} proposes and never decides; the console screen exists so a
     * person can correct a proposed command before anything depends on it. A harness has no person,
     * so the correction is stated here as data — and the {@code reason} travels with it into the
     * committed contract's own comments, because a regression gate that has been narrowed for
     * reasons nobody can see is how a suite quietly stops covering what it was there to cover.
     *
     * @param commands the replacement existing-test commands
     * @param reason   why, at length, with the measurement that justified it
     */
    record ExistingCorrection(List<String> commands, String reason) {}

    /**
     * The whole file: which repository these cases are against, where to clone it from, and any
     * correction its detected contract needs.
     */
    record File(String target, String cloneUrl, ExistingCorrection existingCorrection,
                List<Case> cases) {}

    /** Everything in the case file, in file order. */
    static File file() throws IOException {
        Path path = locate();
        if (path == null) {
            throw new IllegalStateException(CASE_FILE + " was not found above "
                + Paths.get("").toAbsolutePath() + " or above the main checkout. It is part of "
                + "this repository, so a checkout without it is incomplete rather than a machine "
                + "this harness cannot run on.");
        }
        File parsed = MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8), File.class);
        if (parsed.cases() == null || parsed.cases().isEmpty()) {
            throw new IllegalStateException(path + " parsed but names no cases at all — a harness "
                + "that runs zero cases and reports success is the failure this whole thing exists "
                + "to catch.");
        }
        return parsed;
    }

    /** Every case in the file. */
    static List<Case> all() throws IOException {
        return file().cases();
    }

    /**
     * The cases this run was asked for — every one, or the single case
     * {@code -Dswarmcoder.brownfield.case} names.
     *
     * @throws IllegalStateException when a case was named and the file does not have it, rather
     *                               than quietly running nothing
     */
    static List<Case> selected() throws IOException {
        List<Case> cases = all();
        String wanted = System.getProperty(CASE_PROPERTY, "all").strip();
        if (wanted.isEmpty() || "all".equalsIgnoreCase(wanted)) {
            return cases;
        }
        List<Case> matched = new ArrayList<>();
        for (Case one : cases) {
            if (String.valueOf(one.issue()).equals(wanted)) {
                matched.add(one);
            }
        }
        if (matched.isEmpty()) {
            List<String> known = new ArrayList<>();
            cases.forEach(c -> known.add(String.valueOf(c.issue())));
            throw new IllegalStateException("-D" + CASE_PROPERTY + "=" + wanted + " names no case "
                + "in " + CASE_FILE + "; it has " + String.join(", ", known));
        }
        return matched;
    }

    /**
     * The one case the end-to-end walk builds when nobody names one.
     *
     * <p><b>#2266 — setting {@code charset} on an empty XML document throws.</b> It is the easiest
     * of the five on every measurement waves 1 and 2 made, and each of those matters to a walk that
     * has to survive seventeen links on a local 27B:
     *
     * <ul>
     *   <li><b>The report says where the fault is.</b> It carries three lines of Java and the full
     *       stack trace, and the trace names the method — so the work does not begin with a
     *       search.</li>
     *   <li><b>The machine already reads it correctly.</b> Wave 2 measured that this case's
     *       neighbourhood names the source file the maintainer changed AND their test file, and
     *       ranks that test class first. One other case does both, and this is the smaller
     *       change.</li>
     *   <li><b>The fix is two changed lines in one method</b> — a guard. Every other case is a
     *       larger edit; #2476's is five lines inside a loop.</li>
     *   <li><b>One test method to satisfy, not three.</b> #2476 adds three, including a
     *       suppressed-enforcement edge case, so its oracle has three ways to come back red.</li>
     *   <li><b>Its suite takes 20 seconds</b> (wave 1) against #2476's 50 — and in this wave the
     *       whole suite is paid per candidate, with no slicing (design decision 3).</li>
     * </ul>
     *
     * <p>The other four are not harder in kind, only in degree, and wave 4 runs all five.
     */
    static final int EASIEST_CASE = 2266;

    /**
     * The single case to walk end to end: whichever {@code -Dswarmcoder.brownfield.case} names, or
     * {@link #EASIEST_CASE}.
     *
     * <p>Deliberately not "the first of the five". The end-to-end walk dispatches live workers
     * against a shared GPU, and the experiment's own instruction stands — one at a time — so a
     * default that quietly ran whichever case sorted first would be a default nobody chose.
     *
     * @throws IllegalStateException when more than one case is selected, rather than silently
     *                               walking one of them
     */
    static Case theOneToWalkEndToEnd() throws IOException {
        String wanted = System.getProperty(CASE_PROPERTY, "all").strip();
        if (wanted.isEmpty() || "all".equalsIgnoreCase(wanted)) {
            for (Case one : all()) {
                if (one.issue() == EASIEST_CASE) {
                    return one;
                }
            }
            throw new IllegalStateException(CASE_FILE + " no longer has issue " + EASIEST_CASE
                + ", which is the case the end-to-end walk defaults to. Name one with -D"
                + CASE_PROPERTY + "=<issue>, or change the default and say why in the ledger.");
        }
        List<Case> matched = selected();
        if (matched.size() != 1) {
            throw new IllegalStateException("-D" + CASE_PROPERTY + "=" + wanted + " selects "
                + matched.size() + " case(s); the end-to-end walk builds exactly one at a time, "
                + "because its workers share one GPU.");
        }
        return matched.get(0);
    }

    /**
     * The case file, wherever this harness is being run from.
     *
     * <p>Walks up from the working directory and then from the main checkout, the way
     * {@link BookshelfFixture} does and for a related reason: an agent session runs this from a
     * git worktree, and the working directory there is not always the repository root.
     */
    private static Path locate() {
        String declared = System.getProperty("swarmcoder.brownfield.cases");
        if (declared != null && !declared.isBlank()) {
            Path path = Path.of(declared);
            return Files.isRegularFile(path) ? path : null;
        }
        for (Path start : new Path[] {Paths.get("").toAbsolutePath(), mainCheckout()}) {
            for (Path dir = start; dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve(CASE_FILE);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Path mainCheckout() {
        try {
            String common = BookshelfFixture.git(Paths.get("").toAbsolutePath(),
                "rev-parse --path-format=absolute --git-common-dir").strip();
            return common.isEmpty() ? null : Path.of(common).getParent();
        } catch (Exception e) {
            return null;
        }
    }
}
