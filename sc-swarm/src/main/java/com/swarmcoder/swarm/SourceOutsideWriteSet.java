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
import com.swarmcoder.domain.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * A candidate that changed source files outside its task's write set does not pass.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 74, 2026-10-03. A repair worker of "add two methods to the service interface"
 * implemented the methods in the implementing class - a file of the next wave's task - and
 * created its own copies of two command classes two other tasks were writing. It passed
 * verification, was selected, and the delivery now holds dead duplicate classes.
 *
 * <h2>What the guard did before</h2>
 *
 * <p>A write outside the write set was recorded on the candidate and the worker told; the judge
 * was shown the list; selection used the count only to break a tie between candidates the judge
 * could not separate; and the integrators dropped the files that are not source (a stray note, a
 * script). A source file outside the write set was merged like any other.
 *
 * <h2>The rule from 2026-10-03 to 2026-10-08</h2>
 *
 * <p>Every source change outside the write set failed the candidate at verification. That
 * stopped run 74's fault and, with write sets guessed by the planner model, also stopped work
 * that was right: run 90 lost a whole build to it.
 *
 * <h2>The rule now (owner's decision, 2026-10-08; section 73)</h2>
 *
 * <p>A write set is a reservation. A source file outside it that NO OTHER TASK of the plan
 * holds is the task's to take: the candidate passes, the judge sees the real diff, and the file
 * is recorded on the task when the candidate is selected. A source file that another task holds
 * - one built at the same time, or one the plan runs later - still fails the candidate here,
 * which is exactly run 74's case. The worker is refused such a write when it makes it
 * ({@link ReservationBook}); this is the second place it is checked, for what a shell command
 * wrote past the tools.
 *
 * <p>The write set is the one the task has NOW: when the product widened it
 * ({@link RepairCannotHelp}, {@link SiblingDefects}), the files it was widened to are inside
 * it. Files that are not source keep being dropped at integration, and a build file is left to
 * the judge as before - a task may need to declare a dependency.
 */
final class SourceOutsideWriteSet {

    private static final int MAX_NAMED = 8;

    private SourceOutsideWriteSet() {
    }

    /**
     * The source files this candidate's change touches outside the task's write set: recorded by
     * its toolbox as outside, still part of its change, not covered by the write set as it is
     * now, and neither a build file nor a non-source stray.
     */
    static List<String> paths(Task task, CandidateSolution candidate) {
        List<String> outside = new ArrayList<>();
        if (task == null || candidate == null || task.writeSet() == null
                || task.writeSet().isEmpty()) {
            return outside; // an empty write set is unrestricted
        }
        String diff = candidate.diffUnified() == null ? "" : candidate.diffUnified();
        for (String recorded : candidate.outOfWriteSetPaths()) {
            if (recorded == null || recorded.isBlank()) {
                continue;
            }
            String path = recorded.strip().replace('\\', '/');
            if (StrayFileCheck.isStray(path) || isBuildFile(path)
                    || RepairCannotHelp.covered(task.writeSet(), path)) {
                continue;
            }
            // Written and then put back as it was: not part of the change any more.
            if (!diff.isBlank() && !diff.contains(path)) {
                continue;
            }
            if (!outside.contains(path)) {
                outside.add(path);
            }
        }
        return outside;
    }

    /**
     * The source files outside the task's write set that another task of the plan holds, each
     * with the title of that task. Empty when the candidate took only files nobody else holds.
     *
     * @param book who holds what in this run's plan; null knows of no other task, so nothing
     *             is held
     */
    static java.util.Map<String, String> heldByOthers(Task task, CandidateSolution candidate,
                                                       ReservationBook book) {
        java.util.Map<String, String> held = new java.util.LinkedHashMap<>();
        if (book == null) {
            return held;
        }
        for (String path : paths(task, candidate)) {
            ReservationBook.Decision decision = book.standing(task, path);
            if (decision.standing() != ReservationBook.Standing.FREE) {
                held.put(path, "'" + decision.holder().title() + "', "
                    + (decision.standing() == ReservationBook.Standing.HELD_NOW
                        ? "built at the same time" : "which the plan runs later"));
            }
        }
        return held;
    }

    /**
     * The verdict sentence, or null when every source file the candidate changed outside its
     * write set is one no other task of the plan holds.
     */
    static String objection(Task task, CandidateSolution candidate, ReservationBook book) {
        java.util.Map<String, String> held = heldByOthers(task, candidate, book);
        if (held.isEmpty()) {
            return null;
        }
        List<String> named = new ArrayList<>();
        held.forEach((path, holder) -> {
            if (named.size() < MAX_NAMED) {
                named.add(path + " (held by " + holder + ")");
            }
        });
        boolean one = held.size() == 1;
        return "the candidate changed " + (one ? "a source file" : held.size() + " source files")
            + " that another task of this plan holds: " + String.join(", ", named)
            + (held.size() > MAX_NAMED ? " and " + (held.size() - MAX_NAMED) + " more" : "")
            + ". A change there is never selected, whatever else it passes: the other task's "
            + "work and this one could not both be merged. Make the task's change without "
            + (one ? "that file and leave it as it was" : "those files and leave them as they "
            + "were") + "; if the task cannot be done without " + (one ? "it" : "them")
            + ", say so in your report - that is a fault in the plan";
    }

    /**
     * The reservation of a task grows by what its selected candidate took: the source files it
     * changed outside the write set - every one held by no other task, or verification would
     * have failed it. They are added to the task's write set and to its record of files taken
     * beyond the plan. Two candidates of one task may have taken different files; only the
     * selected one's count.
     *
     * @return the files added now; empty when the candidate stayed inside the reservation
     */
    static List<String> growReservation(Task task, CandidateSolution selected) {
        List<String> beyond = paths(task, selected);
        if (beyond.isEmpty()) {
            return beyond;
        }
        java.util.Set<String> grown = new java.util.LinkedHashSet<>(task.writeSet());
        grown.addAll(beyond);
        List<String> recorded = new ArrayList<>(task.takenBeyondPlan());
        for (String path : beyond) {
            if (!recorded.contains(path)) {
                recorded.add(path);
            }
        }
        task.setWriteSet(grown);
        task.setTakenBeyondPlan(recorded);
        return beyond;
    }

    static boolean isBuildFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return "pom.xml".equals(name) || "build.gradle".equals(name)
            || "build.gradle.kts".equals(name) || "settings.gradle".equals(name)
            || "settings.gradle.kts".equals(name) || "package.json".equals(name);
    }
}
