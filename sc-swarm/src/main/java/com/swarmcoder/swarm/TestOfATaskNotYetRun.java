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
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.Task;
import com.swarmcoder.verify.TestOwnership;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * When a candidate's only compile failure is in its task's own protected acceptance test, on a
 * type that a task not yet run writes, the plan is at fault and not the candidate. The task
 * stops at once; no further candidate is waited for and no repair round is started (section 83).
 *
 * <h2>The build this exists because of</h2>
 *
 * <p>Live supervised build, 2026-10-12: see {@link TestOwnership}. The first task's four
 * candidates each compiled; the task's test, put over them for verification, imported three
 * types the second task writes. Four repair workers followed, each unable to change the test and
 * with nothing of their own to change.
 *
 * <p>The red check is the first line and gives such a test to the task that can pass it before
 * any worker starts. This is the second line: a plan saved before that rule, or a need the red
 * check could not read.
 *
 * <p>One candidate is evidence enough. Nothing a candidate of this task writes can make the test
 * compile, so what the others did does not change the finding - unless one of them compiled
 * with the test, which shows the reading wrong.
 *
 * <p>Pure functions over what verification recorded, the plan and the test's text; nothing here
 * reads a tree or calls a model.
 */
final class TestOfATaskNotYetRun {

    private TestOfATaskNotYetRun() {
    }

    /**
     * @param file       the task's protected test the compile failed in, repo-relative
     * @param line       the line of the first error; 0 when the compiler named none
     * @param error      the compiler's message for it
     * @param type       the type the test needs
     * @param writer     the title of the task that writes it and has not run yet
     * @param candidates how many verified candidates failed this way
     */
    record Finding(String file, int line, String error, String type, String writer,
                   int candidates) {

        String where() {
            return file + (line > 0 ? ":" + line : "");
        }
    }

    /**
     * The finding, or null.
     *
     * @param verified   the candidates verification has a verdict for so far
     * @param waves      the plan's waves in order, as {@link SwarmEngineImpl#topologicalWaves}
     *                   gives them; null when the plan cannot be read
     * @param testSource the text of one of the run's acceptance tests by its repo-relative
     *                   path, null when it cannot be read
     */
    static Finding find(Task task, List<CandidateSolution> verified, List<List<Task>> waves,
                        Function<String, String> testSource) {
        if (task == null || verified == null || waves == null || testSource == null
                || task.authoredTestPaths().isEmpty()) {
            return null;
        }
        int ownWave = -1;
        List<Task> planOrder = new ArrayList<>();
        for (int i = 0; i < waves.size(); i++) {
            for (Task other : waves.get(i)) {
                if (other.id().equals(task.id())) {
                    ownWave = i;
                }
                planOrder.add(other);
            }
        }
        if (ownWave < 0) {
            return null;
        }
        Finding found = null;
        int counted = 0;
        for (CandidateSolution candidate : verified) {
            if (candidate == null || candidate.verification() == null) {
                continue;
            }
            if (candidate.verification().compiles()) {
                return null; // the test compiled for somebody
            }
            CompileFailure failure = candidate.verification().compileFailure();
            String test = failure == null ? null : onlyInItsOwnTest(task, failure);
            if (test == null) {
                continue; // it failed somewhere of its own; no evidence about the test
            }
            Finding one = needsATaskNotYetRun(task, failure, test, testSource.apply(test),
                planOrder, waves, ownWave);
            if (one != null) {
                found = found == null ? one : found;
                counted++;
            }
        }
        return found == null ? null : new Finding(found.file(), found.line(), found.error(),
            found.type(), found.writer(), counted);
    }

    /**
     * The task's own test the failure is in, when every file the compiler reported an error in
     * is one of the task's protected tests; else null.
     */
    private static String onlyInItsOwnTest(Task task, CompileFailure failure) {
        if (failure.file() == null) {
            return null;
        }
        List<String> files = failure.failingFiles() == null || failure.failingFiles().isEmpty()
            ? List.of(failure.file()) : failure.failingFiles();
        for (String file : files) {
            if (ownTest(task, file) == null) {
                return null;
            }
        }
        return ownTest(task, failure.file());
    }

    private static String ownTest(Task task, String file) {
        if (file == null) {
            return null;
        }
        String wanted = file.replace('\\', '/');
        for (String path : task.authoredTestPaths()) {
            String own = path.replace('\\', '/');
            if (own.equals(wanted) || own.endsWith("/" + wanted) || wanted.endsWith("/" + own)) {
                return path;
            }
        }
        return null;
    }

    private static Finding needsATaskNotYetRun(Task task, CompileFailure failure, String test,
                                               String source, List<Task> planOrder,
                                               List<List<Task>> waves, int ownWave) {
        if (source == null) {
            return null;
        }
        String message = failure.message() == null ? "" : failure.message();
        Map<String, List<Task>> named = TestOwnership.namedTypes(planOrder);
        for (String type : TestOwnership.imported(source, named.keySet())) {
            List<Task> writers = named.get(type);
            boolean allNotYetRun = true;
            for (Task writer : writers) {
                if (writer.id().equals(task.id()) || waveOf(waves, writer) < ownWave) {
                    allNotYetRun = false;
                }
            }
            if (!allNotYetRun) {
                continue;
            }
            // The compiler's own words must be about this type: its name, or its package.
            int dot = type.lastIndexOf('.');
            String simple = type.substring(dot + 1);
            boolean named1 = Pattern.compile("(?<![\\w$])" + Pattern.quote(simple) + "(?![\\w$])")
                .matcher(message).find();
            boolean itsPackage = dot > 0 && Pattern.compile("(?<![\\w$.])"
                + Pattern.quote(type.substring(0, dot)) + "(?![\\w$.])").matcher(message).find();
            if (named1 || itsPackage) {
                return new Finding(test.replace('\\', '/'), failure.line(), message.strip(), type,
                    writers.get(0).title(), 1);
            }
        }
        return null;
    }

    private static int waveOf(List<List<Task>> waves, Task task) {
        for (int i = 0; i < waves.size(); i++) {
            for (Task other : waves.get(i)) {
                if (other.id().equals(task.id())) {
                    return i;
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    /** The stop message: short, the reason first, and the plan - not the candidates - blamed. */
    static String planBlame(Task task, Finding finding) {
        return "Task BLOCKED by its plan, not by its candidates: '" + task.title() + "'\n\n"
            + finding.where() + " " + BlockedReason.shortened(finding.error()) + "\n"
            + "That file is this task's own protected acceptance test. It needs `"
            + finding.type() + "`, which task '" + finding.writer() + "' writes, and that task "
            + "has not run yet. No candidate of this task can make the test compile, so no "
            + "repair round was started.\n\n"
            + "Give the check and its test to '" + finding.writer() + "', or make this task "
            + "wait for it, then run it again.\n\n";
    }
}
