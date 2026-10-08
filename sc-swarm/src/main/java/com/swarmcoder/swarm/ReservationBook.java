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

import com.swarmcoder.domain.Task;
import com.swarmcoder.runtime.PathPolicy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which task of a run's plan holds which file, while the run executes (owner's decision,
 * 2026-10-08; section 73).
 *
 * <h2>Why</h2>
 *
 * <p>A write set used to be a wall guessed by the planner model: a candidate that changed a
 * source file outside it failed verification outright, whatever the file was. Run 90 lost a
 * whole build to that. The owner's rule: what a task was planned to write is a RESERVATION. It
 * is extended with no dialogue and no model when a worker writes a file outside it:
 *
 * <ul>
 *   <li>no other task of the plan holds the file: allowed. The file becomes the task's, from
 *       that moment and for the rest of the run, so a task built at the same time is refused
 *       it;</li>
 *   <li>a task built at the same time holds it: refused, naming that task;</li>
 *   <li>a task the plan runs later holds it: refused, naming that task, and remembered here -
 *       that is the plan running two tasks in the wrong order
 *       ({@link FileOfATaskNotYetRun}), not a worker's failure;</li>
 *   <li>a task of an earlier wave held it: allowed. That task's work is merged and this task's
 *       checkout is cut from it, so there is nothing to collide with.</li>
 * </ul>
 *
 * <p>What is protected - the acceptance tests and journeys, {@code .swarmcoder/}, {@code .git/},
 * locked modules - is refused by {@link PathPolicy} before this is asked, whoever holds what.
 *
 * <p>Build files and files that are not source are nobody's here, as before: two tasks of one
 * module share its build file by construction, and a stray file is dropped at integration.
 *
 * <p>Read from the plan's own tasks, live: a write set the product widened is seen as it is
 * now. Held in memory by the engine for one run; nothing here is read from a worker's tree.
 */
final class ReservationBook {

    enum Standing { FREE, HELD_NOW, HELD_LATER }

    /** @param holder the task that holds the file; null when it is free */
    record Decision(Standing standing, Task holder) {
        static final Decision FREE = new Decision(Standing.FREE, null);
    }

    private final List<List<Task>> waves;
    /** What each task took beyond what the plan reserved for it, in the order taken. */
    private final Map<UUID, Set<String>> taken = new LinkedHashMap<>();
    /** Task, then file, then the workers of the task that were refused it for a later task. */
    private final Map<UUID, Map<String, Set<String>>> refusedForLater = new LinkedHashMap<>();
    private final Map<String, String> laterHolder = new LinkedHashMap<>();

    private ReservationBook(List<List<Task>> waves) {
        this.waves = waves == null ? List.of() : waves;
    }

    /**
     * @param waves the plan's waves in order, as {@link SwarmEngineImpl#topologicalWaves} gives
     *              them; null (the plan cannot be read) holds nothing, so every file is free
     */
    static ReservationBook of(List<List<Task>> waves) {
        return new ReservationBook(waves);
    }

    /** Who holds {@code path} as far as {@code task} is concerned. Changes nothing. */
    synchronized Decision standing(Task task, String path) {
        if (task == null || path == null || path.isBlank()) {
            return Decision.FREE;
        }
        String file = path.strip().replace('\\', '/');
        if (StrayFileCheck.isStray(file) || SourceOutsideWriteSet.isBuildFile(file)) {
            return Decision.FREE;
        }
        int own = waveOf(task.id());
        if (own < 0) {
            return Decision.FREE; // not a task of this plan: nothing is known to collide
        }
        Task later = null;
        for (int index = own; index < waves.size(); index++) {
            for (Task other : waves.get(index)) {
                if (other == null || other.id().equals(task.id()) || !holds(other, file)) {
                    continue;
                }
                if (index == own) {
                    return new Decision(Standing.HELD_NOW, other);
                }
                if (later == null) {
                    later = other;
                }
            }
        }
        return later == null ? Decision.FREE : new Decision(Standing.HELD_LATER, later);
    }

    /**
     * The decision for a worker of {@code task} that is writing {@code path}: taken when it is
     * free, refused otherwise.
     *
     * @param worker which worker of the task asks, so two workers refused the same file count
     *               as two
     * @return null when the file is now the task's; otherwise the refusal for the worker
     */
    synchronized String take(Task task, String worker, String path) {
        Decision decision = standing(task, path);
        String file = path == null ? "" : path.strip().replace('\\', '/');
        if (decision.standing() == Standing.FREE) {
            if (task != null && !file.isEmpty() && waveOf(task.id()) >= 0
                    && !StrayFileCheck.isStray(file)
                    && !SourceOutsideWriteSet.isBuildFile(file)) {
                taken.computeIfAbsent(task.id(), id -> new LinkedHashSet<>()).add(file);
            }
            return null;
        }
        String holder = decision.holder().title();
        if (decision.standing() == Standing.HELD_NOW) {
            return file + " is held by the task '" + holder + "', which is being built at the "
                + "same time as yours, so it was not written: two tasks of one wave changing "
                + "one file cannot both be merged. Do your task without changing that file. If "
                + "it cannot be done without it, say so in your report - that is a fault in "
                + "the plan, not in your work.";
        }
        refusedForLater.computeIfAbsent(task.id(), id -> new LinkedHashMap<>())
            .computeIfAbsent(file, f -> new LinkedHashSet<>()).add(worker == null ? "" : worker);
        laterHolder.put(task.id() + "|" + file, holder);
        return file + " is to be written by the task '" + holder + "', which the plan runs "
            + "after yours, so it was not written. Do not make your own copy of it somewhere "
            + "else. If your task cannot compile without it, the plan runs the two tasks in the "
            + "wrong order: say so in your report and stop - that is a fault in the plan, not "
            + "in your work.";
    }

    /** The decision as the path policy asks it, for one worker of one task. */
    PathPolicy.OtherTasks forWorker(Task task, String worker) {
        return path -> take(task, worker, path);
    }

    /** What {@code task} took beyond what the plan reserved for it, in the order taken. */
    synchronized List<String> takenBy(UUID task) {
        Set<String> files = taken.get(task);
        return files == null ? List.of() : List.copyOf(files);
    }

    /**
     * The files workers of {@code task} were refused because a task not yet run holds them,
     * with that task's title and how many workers were refused each.
     */
    synchronized Map<String, RefusedForLater> refusedForLater(UUID task) {
        Map<String, RefusedForLater> files = new LinkedHashMap<>();
        Map<String, Set<String>> refused = refusedForLater.get(task);
        if (refused != null) {
            refused.forEach((file, workers) -> files.put(file,
                new RefusedForLater(laterHolder.get(task + "|" + file), Set.copyOf(workers))));
        }
        return files;
    }

    /**
     * @param holder the title of the task that holds the file and has not run yet
     * @param by     the workers of the asking task that were refused it
     */
    record RefusedForLater(String holder, Set<String> by) {
    }

    private boolean holds(Task other, String file) {
        if (other.writeSet() != null && !other.writeSet().isEmpty()
                && RepairCannotHelp.covered(other.writeSet(), file)) {
            return true;
        }
        Set<String> extra = taken.get(other.id());
        return extra != null && extra.contains(file);
    }

    private int waveOf(UUID task) {
        for (int index = 0; index < waves.size(); index++) {
            for (Task planned : waves.get(index)) {
                if (planned != null && planned.id().equals(task)) {
                    return index;
                }
            }
        }
        return -1;
    }
}
