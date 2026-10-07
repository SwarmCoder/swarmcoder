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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * When every first candidate of a task wrote the same source file outside its write set, and that
 * file belongs to a task of the plan that has not run yet, the plan ran the two in the wrong
 * order. A repair round cannot put that right and is not started.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 90, 2026-10-07. "Use UtcDateTime in add/edit contact screens" was planned in the
 * first wave and the task that creates {@code UtcDateTime} in the second. Both first candidates
 * compiled, and both failed verification with "the candidate changed a source file outside the
 * task's write set: .../UtcDateTime.java": they had to create the type to compile at all. A
 * repair round of four workers followed (640,071 input and 52,320 output tokens, 30 minutes) with
 * the same write set and the same order, and failed the same way.
 *
 * <h2>What happens instead</h2>
 *
 * <p>The task stops before the repair round with a message that blames the plan and names the
 * pair: which task owns the file and that it must run first. Nothing is reordered here: the
 * waves are fixed when the run starts, and the acceptance tests are already written against the
 * plan's tasks. {@code TypeDependencyOrder} is what keeps such a plan from being accepted; this
 * is what stops the spending when one gets through.
 *
 * <p>Nothing is asked of a single candidate's choice: one worker writing into another task's
 * file is that worker's mistake (live run 74). It takes two candidates that agree on the file,
 * or one whose task itself names that file in its read set.
 *
 * <p>Pure functions over what verification recorded and the plan; nothing here reads a tree or
 * calls a model.
 */
final class FileOfATaskNotYetRun {

    private FileOfATaskNotYetRun() {
    }

    /**
     * @param files      the source files every counted candidate wrote outside the write set and
     *                   that a task not yet run owns, with the title of that task
     * @param candidates how many candidates were verified and failed this way
     */
    record Finding(Map<String, String> files, int candidates) {

        /** The titles of the tasks that own the files, in the order found. */
        List<String> owners() {
            return List.copyOf(new LinkedHashSet<>(files.values()));
        }
    }

    /**
     * The finding, or null.
     *
     * @param verified the first round's candidates as verification left them
     * @param waves    the plan's waves in order, as {@link SwarmEngineImpl#topologicalWaves} gives
     *                 them; null when the plan cannot be read
     */
    static Finding find(Task task, List<CandidateSolution> verified, List<List<Task>> waves) {
        if (task == null || verified == null || waves == null || task.writeSet() == null
                || task.writeSet().isEmpty()) {
            return null;
        }
        Set<String> common = null;
        int counted = 0;
        for (CandidateSolution candidate : verified) {
            if (candidate == null) {
                continue;
            }
            if (candidate.state() == CandidateState.SURVIVED) {
                return null;
            }
            List<String> outside = SourceOutsideWriteSet.paths(task, candidate);
            if (outside.isEmpty()) {
                if (candidate.verification() == null) {
                    continue; // stopped, or no change: not evidence either way
                }
                return null; // it failed, and not for this
            }
            if (common == null) {
                common = new LinkedHashSet<>(outside);
            } else {
                common.retainAll(outside);
            }
            counted++;
        }
        if (counted == 0 || common == null || common.isEmpty()) {
            return null;
        }
        Map<String, String> owned = new LinkedHashMap<>();
        for (String file : common) {
            String owner = ownerNotYetRun(task, waves, file);
            if (owner != null) {
                owned.put(file, owner);
            }
        }
        if (owned.isEmpty()) {
            return null;
        }
        if (counted < 2 && owned.keySet().stream().noneMatch(file -> inReadSet(task, file))) {
            return null;
        }
        return new Finding(Map.copyOf(owned), counted);
    }

    /** The title of the task beside or after {@code task} whose write set holds {@code file}. */
    private static String ownerNotYetRun(Task task, List<List<Task>> waves, String file) {
        boolean reached = false;
        for (List<Task> wave : waves) {
            boolean own = wave.stream().anyMatch(t -> t.id().equals(task.id()));
            if (own || reached) {
                for (Task other : wave) {
                    if (!other.id().equals(task.id()) && other.writeSet() != null
                            && RepairCannotHelp.covered(other.writeSet(), file)) {
                        return other.title();
                    }
                }
            }
            reached = reached || own;
        }
        return null;
    }

    private static boolean inReadSet(Task task, String file) {
        return task.readSet() != null && !task.readSet().isEmpty()
            && RepairCannotHelp.covered(task.readSet(), file);
    }

    /** The stop message: what happened, which task must run first, and that the plan is at fault. */
    static String planBlame(Task task, Finding finding) {
        List<String> lines = new ArrayList<>();
        finding.files().forEach((file, owner) -> lines.add("  " + file + " - in the write set of '"
            + owner + "', which the plan runs beside or after this task"));
        boolean one = finding.files().size() == 1;
        return "Task BLOCKED by its plan, not by its candidates: '" + task.title() + "'\n\n"
            + "Every candidate that was verified (" + finding.candidates() + ") wrote "
            + (one ? "a source file" : "source files") + " outside the task's write set that "
            + "another task of this plan owns and has not run yet:\n" + String.join("\n", lines)
            + "\n\nThe task cannot be done without " + (one ? "that file" : "those files")
            + ", and " + (one ? "it does" : "they do") + " not exist until "
            + String.join(", ", finding.owners().stream().map(o -> "'" + o + "'").toList())
            + " has run. A repair round with the same write set and the same order cannot "
            + "succeed, so none was started.\n\nThe plan ran the tasks in the wrong order. Plan "
            + "it again so that '" + task.title() + "' depends on "
            + String.join(" and ", finding.owners().stream().map(o -> "'" + o + "'").toList())
            + " (an edge from the task that must finish first to the task that waits for it), "
            + "or give this task the " + (one ? "file" : "files") + " and take "
            + (one ? "it" : "them") + " from the other.\n\n";
    }
}
