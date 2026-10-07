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
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which tasks of a wave may be dispatched, and which must wait because something they depend on
 * has no winner.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 39, 2026-09-25 (run {@code d51ee25e}). Task B, "Create shared BooksService
 * interface", ended BLOCKED after its swarm and its repair round, and raised its question: "Task
 * BLOCKED after swarm + repair round … Re-decompose the task, fix the environment, or adjust the
 * acceptance tests". Task A was merged in the same moment. Then the engine dispatched the next
 * wave — task C, "Implement server-side BooksService with EclipseStore", which depends on BOTH A
 * and B. Workers started writing an implementation of an interface that did not exist and, with B
 * blocked, never would, spending a whole task's worth of workers on code that could not compile.
 *
 * <p>The cause was the wave loop in {@code SwarmEngineImpl.executeRun}: waves are levels of the
 * dependency graph, computed once, and every wave was dispatched in turn whatever had happened to
 * the one before it. "A wave with no winner at all does not stop the run" was deliberate — every
 * task in it is already BLOCKED with its own decision — but nothing asked whether a LATER task
 * needed one of those blocked tasks' work.
 *
 * <h2>The rule</h2>
 *
 * <p>A task is dispatched only when every task it depends on, directly or transitively, has a
 * winner (merged onto the run's progress branch when git is on — the wave integrator does that
 * before the next wave is cut, or parks the run). A task one of whose prerequisites finished with
 * no winner, or is itself waiting, waits too, and is left exactly as it was: PENDING, no workers,
 * nothing archived. Tasks that do NOT depend on the blocked one still run — their work is not in
 * question. When the walk is over and anything waited, the run parks behind the blocked task's
 * own question rather than raising a second one ({@code RunMustPark(brief, true)}); resuming it,
 * once that question is settled, re-dispatches the blocked task and then the ones that waited, and
 * every task that already has a winner keeps it.
 */
final class WaitingOnDependencies {

    /** Each task's direct prerequisites. */
    private final Map<UUID, List<UUID>> prerequisites = new HashMap<>();
    private final Map<UUID, String> titles = new HashMap<>();
    /** Tasks that ran and finished with no winner — each already raised its own question. */
    private final Set<UUID> noWinner = new HashSet<>();
    /** Tasks not dispatched, and the title of the task with no winner they are waiting on. */
    private final Map<UUID, String> waiting = new LinkedHashMap<>();

    WaitingOnDependencies(TaskGraph graph) {
        for (Task task : graph.tasks()) {
            prerequisites.put(task.id(), new ArrayList<>());
            titles.put(task.id(), task.title());
        }
        if (graph.dependencies() != null) {
            for (TaskEdge edge : graph.dependencies()) {
                if (edge != null && prerequisites.containsKey(edge.to())
                        && titles.containsKey(edge.from())) {
                    prerequisites.get(edge.to()).add(edge.from());
                }
            }
        }
    }

    /**
     * The tasks of {@code wave} that may be dispatched now, in wave order. Every other task of the
     * wave is recorded as waiting. Waves are topological levels, so every prerequisite of a task
     * here was settled in an earlier wave — a direct check is transitive.
     */
    List<Task> dispatchable(List<Task> wave) {
        List<Task> ready = new ArrayList<>();
        for (Task task : wave) {
            String blockedBy = null;
            for (UUID prerequisite : prerequisites.getOrDefault(task.id(), List.of())) {
                if (noWinner.contains(prerequisite)) {
                    blockedBy = titles.get(prerequisite);
                    break;
                }
                if (waiting.containsKey(prerequisite)) {
                    blockedBy = waiting.get(prerequisite);
                    break;
                }
            }
            if (blockedBy == null) {
                ready.add(task);
            } else {
                waiting.put(task.id(), blockedBy);
            }
        }
        return ready;
    }

    /** Records that {@code task} ran and finished with no winner. */
    void finishedWithoutWinner(Task task) {
        noWinner.add(task.id());
    }

    /** True when some task was held back because a task it needs has no winner. */
    boolean anyWaiting() {
        return !waiting.isEmpty();
    }

    /** Titles of the tasks that were held back, in the order they were met. */
    List<String> waitingTitles() {
        List<String> out = new ArrayList<>();
        for (UUID id : waiting.keySet()) {
            out.add(titles.get(id));
        }
        return out;
    }

    /**
     * What the operator reads when the run stops behind a blocked task: which task has no
     * winner, which tasks waited for it, and that its own question — already raised — is the one
     * to answer.
     */
    String parkBrief() {
        Map<String, List<String>> byBlocker = new LinkedHashMap<>();
        waiting.forEach((id, blocker) ->
            byBlocker.computeIfAbsent(blocker, k -> new ArrayList<>()).add(titles.get(id)));
        StringBuilder sb = new StringBuilder();
        List<String> blockers = new ArrayList<>(byBlocker.keySet());
        sb.append(blockers.size() == 1
                ? "The run stopped behind task '" + blockers.get(0) + "', which has no winner."
                : "The run stopped behind " + blockers.size() + " tasks with no winner: '"
                    + String.join("', '", blockers) + "'.")
            .append(" Its own question, already raised, says why and is the one to answer.\n\n");
        byBlocker.forEach((blocker, held) -> sb.append("Not started, because they need '")
            .append(blocker).append("' to exist first: '").append(String.join("', '", held))
            .append("'.\n"));
        sb.append("\nNo workers were spent on them: their code could not have compiled without that "
            + "work. Settle the blocked task — re-decompose it, fix the environment, or adjust its "
            + "acceptance tests — then resume the run. Every task that already has a winner keeps "
            + "it; the blocked task is built again, and the tasks that waited follow it.");
        return sb.toString();
    }
}
