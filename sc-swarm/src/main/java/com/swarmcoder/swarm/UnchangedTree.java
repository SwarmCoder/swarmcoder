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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.VerificationReport;

import java.util.List;

/**
 * A round in which no worker of a task changed anything (DEVELOPER_CORRECTIONS section 89).
 *
 * <p>Live supervised build, 2026-10-13. A story's one task was built on code that earlier
 * stories had delivered. Every worker ended without a change and reported that the existing
 * code already met the check and that the acceptance test passed. None of them had run that
 * test: a worker's checkout does not hold it. A worker that changes nothing was recorded as
 * failed and never verified, so nothing measured the unchanged tree; the task was BLOCKED with
 * a question that gave no reason, and the story ended with its check unproved.
 *
 * <p>What a worker says about a test is not a measurement. When no candidate of a round left a
 * change, the product runs the task's acceptance tests on that unchanged tree itself, through
 * the same verification a candidate gets. Green, and accepted as proof ({@link #refusal}): the
 * task needs no change. Red: the candidate carries the report, so everything that reads a
 * failed candidate - the question's reason, the test going back to its author, the repair
 * round's evidence - reads what was measured.
 *
 * <p>Only facts are read here: whether a diff is empty, how a worker ended, what the test
 * runner reported, which files the task claims. No text a worker wrote is read.
 */
final class UnchangedTree {

    private UnchangedTree() {}

    /** True when the candidate's worker left the tree as it found it. */
    static boolean changedNothing(CandidateSolution candidate) {
        return candidate != null
            && (candidate.diffUnified() == null || candidate.diffUnified().isBlank());
    }

    /**
     * The candidate whose tree is measured: the first worker of the round that ended by itself
     * without a change. Null unless NO candidate of the round left a change, and null when
     * every worker was stopped - a worker stopped before it could decide has said nothing
     * about whether a change is needed.
     */
    static CandidateSolution toMeasure(List<CandidateSolution> round) {
        if (round == null || round.isEmpty()) {
            return null;
        }
        CandidateSolution first = null;
        for (CandidateSolution candidate : round) {
            if (!changedNothing(candidate)) {
                return null;
            }
            if (first == null && candidate.state() == CandidateState.FAILED
                    && candidate.killReason() == null && candidate.verification() == null) {
                first = candidate;
            }
        }
        return first;
    }

    /** True when the acceptance stage ran tests on the tree and every one of them passed. */
    static boolean green(VerificationReport report) {
        TestResults acceptance = report == null ? null : report.acceptance();
        return acceptance != null && acceptance.stageOutcome() == TestStageOutcome.EXECUTED
            && acceptance.passed() > 0 && acceptance.failed() == 0 && acceptance.errored() == 0;
    }

    /**
     * Why a green run of the task's acceptance tests on an unchanged tree does not show that the
     * task needs no change; null when it does. A green run on a tree nobody changed is also what
     * a test that measured nothing looks like, so each of these must hold:
     *
     * <ul>
     *   <li>the task claims at least one acceptance test file;</li>
     *   <li>the stage executed tests, at least one passed, none failed and none errored;</li>
     *   <li>every test file the task claims is among the tests reported as passed - a file the
     *       runner never executed has proved nothing;</li>
     *   <li>the task claims no journey - a journey is carried out in a browser at final
     *       integration, and no test run shows what it shows.</li>
     * </ul>
     *
     * That the contracts the task was to deliver are in the tree is asked by the verification
     * that produced the report, and is not asked again here.
     */
    static String refusal(Task task, VerificationReport report) {
        if (task == null || task.authoredTestPaths() == null
                || task.authoredTestPaths().isEmpty()) {
            return "the task claims no acceptance test, so no test run measured it";
        }
        if (!green(report)) {
            return "the acceptance stage did not run the task's tests to a clean pass";
        }
        List<String> passed = report.acceptance().passedIds();
        if (passed.isEmpty()) {
            return "the test runner did not report which tests passed";
        }
        for (String path : task.authoredTestPaths()) {
            String simpleName = simpleNameOf(path);
            if (passed.stream().noneMatch(id -> classOf(id).equals(simpleName))) {
                return "no test of " + path + " is among the tests reported as passed, so "
                    + "nothing shows it was executed";
            }
        }
        if (!task.journeyPaths().isEmpty()) {
            return "the task claims the journey(s) " + task.journeyPaths() + ", which are "
                + "carried out in a browser and which no test run shows";
        }
        return null;
    }

    /**
     * The first lines of the question of a task that stopped after such a round: that no worker
     * changed anything, and what the product's own run of the tests on the unchanged tree
     * counted. The reason line that follows it names the first failing test and its message.
     */
    static String headline(String taskTitle, CandidateSolution measured) {
        VerificationReport report = measured == null ? null : measured.verification();
        StringBuilder sb = new StringBuilder("Task BLOCKED after swarm + repair round: '")
            .append(taskTitle).append("'\n\nNo worker of the first round changed anything. The "
                + "product then ran the task's acceptance test(s) on that unchanged tree itself");
        if (report == null) {
            return sb.append(".\n\n").toString();
        }
        if (!report.compiles()) {
            sb.append(", and the tree with those test(s) in it does not compile");
        } else if (report.acceptance() != null) {
            sb.append(": ").append(BlockedReason.counts(report.acceptance()))
                .append(" (passed/failed/errored)");
        }
        sb.append(". So the check is not met by the code as it stands, whatever a worker "
            + "reported, or the test is wrong.\n\n");
        return sb.toString();
    }

    private static String simpleNameOf(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** The simple class name of a reported test id ({@code a.b.FooTest$Nested#method}). */
    private static String classOf(String testId) {
        String type = testId.contains("#") ? testId.substring(0, testId.indexOf('#')) : testId;
        type = type.substring(type.lastIndexOf('.') + 1);
        return type.contains("$") ? type.substring(0, type.indexOf('$')) : type;
    }
}
