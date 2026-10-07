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
import com.swarmcoder.domain.CompileFailureCause;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * When every first candidate of a task fails on a compile error in a file the task may not write,
 * a repair round with the same write set cannot succeed and is not started.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 74, 2026-10-03. A task was told to add two methods to an existing interface and
 * could write only the interface's file; the class implementing it belonged to a task of the next
 * wave. Both first candidates failed on that class no longer compiling. Two repair rounds of four
 * workers followed, 74 and 66 minutes, each reasoning about a file it was not allowed to change.
 *
 * <h2>What happens instead</h2>
 *
 * <ol>
 *   <li>the file belongs to a later task of the plan, or to no task: this task's write set is
 *       widened to include it and the task is built again once, told why (the mechanism of
 *       {@link SiblingDefects}: one widening per task);</li>
 *   <li>otherwise - the file belongs to an earlier task or one running beside this one, the task
 *       was already widened once, or the file is protected - the task stops with a message that
 *       blames the plan, not the candidates.</li>
 * </ol>
 *
 * <p>Pure functions over what verification recorded; nothing here reads a tree or calls a model.
 */
final class RepairCannotHelp {

    private RepairCannotHelp() {
    }

    /**
     * @param file     the file every candidate's compile failed in, repo-relative
     * @param files    every non-test file outside the write set that the candidates' compiles
     *                 failed in, the named one first
     * @param error    the compiler's message for the named file, as the verdict has it
     * @param verdict  one candidate's whole compile verdict
     * @param candidates how many candidates were verified and failed this way
     */
    record Finding(String file, List<String> files, String error, String verdict, int candidates) {

        /** What the task is told when it is built again with these files in its write set. */
        String instructions() {
            return "\n\nTHIS TASK ALSO CHANGES " + String.join(", ", files) + ". The first attempt "
                + "at this task could not write "
                + (files.size() == 1 ? "that file" : "those files") + ", and every candidate "
                + "failed because the change this task makes stops "
                + (files.size() == 1 ? "it" : "them") + " compiling: " + file + ": " + error
                + ". " + (files.size() == 1 ? "It has" : "They have") + " been added to the paths "
                + "you may modify. Make this task's change AND change "
                + (files.size() == 1 ? "that file" : "those files")
                + " so the whole build compiles again; change only what that needs.";
        }
    }

    /**
     * The finding, or null: every candidate that was verified failed its compile on an error in
     * the same file, the task may not write that file, and it is not a test the test author owns.
     * At least one verified candidate is required; a candidate that produced no verdict (stopped,
     * or no change) is not evidence either way.
     */
    static Finding find(Task task, List<CandidateSolution> verified, List<String> protectedPaths) {
        if (task == null || verified == null || task.writeSet() == null
                || task.writeSet().isEmpty()) {
            return null; // an empty write set is unrestricted: nothing is outside it
        }
        String file = null;
        String error = null;
        String verdict = null;
        Set<String> files = new LinkedHashSet<>();
        int counted = 0;
        for (CandidateSolution candidate : verified) {
            if (candidate == null || candidate.verification() == null) {
                continue;
            }
            if (candidate.verification().compiles()) {
                return null;
            }
            CompileFailure failure = candidate.verification().compileFailure();
            if (failure == null || failure.file() == null || failure.testFile()
                    || failure.acceptanceTest()
                    || (failure.cause() != CompileFailureCause.CAUSED_BY_CHANGE
                        && failure.cause() != CompileFailureCause.PRE_EXISTING)) {
                return null;
            }
            String named = normalize(failure.file());
            if (covered(task.writeSet(), named) || isProtected(task, protectedPaths, named)) {
                return null;
            }
            if (file == null) {
                file = named;
                error = failure.message() == null ? "" : failure.message().strip();
                verdict = failure.describe();
                files.add(named);
            } else if (!file.equals(named)) {
                return null;
            }
            if (failure.failingFiles() != null) {
                for (String other : failure.failingFiles()) {
                    String path = other == null ? "" : normalize(other);
                    if (!path.isEmpty() && !isTest(path) && !covered(task.writeSet(), path)
                            && !isProtected(task, protectedPaths, path)) {
                        files.add(path);
                    }
                }
            }
            counted++;
        }
        if (counted == 0 || files.size() > MAX_FILES) {
            return null;
        }
        return new Finding(file, List.copyOf(files), error, verdict, counted);
    }

    /** More files than this is not a write set one file short; it is another plan. */
    static final int MAX_FILES = 8;

    /**
     * Why the task's write set may NOT be widened to these files, or null when it may: each file
     * belongs to a later task of the plan or to no task.
     *
     * @param waves the plan's waves in order, as {@link SwarmEngineImpl#topologicalWaves} gives
     *              them; null when the plan cannot be read
     */
    static String whyNotWidened(Task task, List<List<Task>> waves, List<String> files) {
        if (!task.siblingRepairPaths().isEmpty()) {
            return "its write set was already widened once (" + String.join(", ",
                task.siblingRepairPaths()) + ") and a task is widened only once";
        }
        if (waves == null) {
            return "the plan could not be read to see which task owns the file";
        }
        boolean reached = false;
        for (List<Task> wave : waves) {
            boolean own = wave.stream().anyMatch(t -> t.id().equals(task.id()));
            for (Task other : wave) {
                if (other.id().equals(task.id()) || other.writeSet() == null) {
                    continue;
                }
                for (String file : files) {
                    if (!covered(other.writeSet(), file)) {
                        continue;
                    }
                    if (own) {
                        return file + " is in the write set of '" + other.title() + "', which "
                            + "runs beside this task; two tasks of one wave changing one file "
                            + "cannot both be merged";
                    }
                    if (!reached) {
                        return file + " is in the write set of the earlier task '" + other.title()
                            + "', whose work is already merged";
                    }
                }
            }
            reached = reached || own;
        }
        return reached ? null : "the task is not in the plan";
    }

    /** The stop message: what happened, and that the plan - not the candidates - is at fault. */
    static String planBlame(Task task, Finding finding, String whyNotWidened) {
        return "Task BLOCKED by its plan, not by its candidates: '" + task.title() + "'\n\n"
            + "Every candidate that was verified (" + finding.candidates() + ") made the change "
            + "the task asks for and failed on the same compile error in a file the task may not "
            + "write: " + finding.file() + ": " + finding.error() + "\n\n"
            + "The task's write set is " + task.writeSet() + ". A repair round with the same "
            + "write set cannot succeed, so none was started. The write set was not widened "
            + "because " + whyNotWidened + ".\n\n"
            + "The plan split a change from the existing code it breaks. Plan it again with "
            + String.join(", ", finding.files()) + " in the write set of this task (or merge "
            + "this task with the one that owns " + (finding.files().size() == 1 ? "it" : "them")
            + "), then run it again.\n\nWhat verification said: " + finding.verdict() + "\n\n";
    }

    static boolean covered(Set<String> writeSet, String path) {
        String wanted = normalize(path);
        for (String entry : writeSet) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String e = normalize(entry);
            if (wanted.equals(e) || wanted.startsWith(e + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProtected(Task task, List<String> protectedPaths, String path) {
        List<String> guarded = new ArrayList<>();
        if (task.acceptanceTestDir() != null && !task.acceptanceTestDir().isBlank()) {
            guarded.add(task.acceptanceTestDir());
        }
        if (protectedPaths != null) {
            guarded.addAll(protectedPaths);
        }
        return !guarded.isEmpty() && covered(new LinkedHashSet<>(guarded), path);
    }

    private static boolean isTest(String path) {
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }

    private static String normalize(String path) {
        String p = path.strip().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }
}
