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
package com.swarmcoder.console.api;

import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.Iteration;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.Task;
import com.zeroz4j.api.DataModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One project's backlog as the panel renders it: its iterations, its stories, and the tasks
 * currently under them.
 *
 * <p>An aggregate, not a mirror — {@link Iteration}, {@link Story} and {@link Task} are the domain
 * objects travelling directly (the direct-model pattern); only the composition is assembled here,
 * because the panel needs all three lists together and one signal carrying one value is what keeps
 * the view consistent.
 *
 * <p>Tasks used to be the one exception: a hand-written {@code BacklogTask} projection, because the
 * serializer could not carry a {@code Set} and a {@code Task} holds three of them. ZeroZ Stack 0.4.0
 * added {@code Set} to the wire format, so the projection is gone and this carries the real object.
 */
@DataModel
public class Backlog {
    private UUID projectId;
    private List<Iteration> iterations;
    private List<Story> stories;
    private List<Task> tasks;
    /**
     * The questions builds stopped to ask, so the board can put each one on the card of the story it
     * belongs to (UX v3 §2.3).
     *
     * <p>Carried on THIS aggregate rather than on a signal of its own, for the reason the aggregate
     * exists: the board needs stories and their questions together, and one signal carrying one value
     * is what stops it rendering a story from one publish beside a question from another. A decision
     * used to be reachable only from a standalone Approval Center — a destination the operator had to
     * know about, holding thirty-seven cards that named runs rather than stories.
     */
    private List<Decision> decisions;

    public Backlog() {}

    public Backlog(UUID projectId, List<Iteration> iterations, List<Story> stories,
                   List<Task> tasks) {
        this(projectId, iterations, stories, tasks, new ArrayList<>());
    }

    public Backlog(UUID projectId, List<Iteration> iterations, List<Story> stories,
                   List<Task> tasks, List<Decision> decisions) {
        this.projectId = projectId;
        this.iterations = iterations;
        this.stories = stories;
        this.tasks = tasks;
        this.decisions = decisions;
    }

    /** An empty backlog — the signal's initial value, and what a project with no plan yet looks like. */
    public static Backlog empty() {
        return new Backlog(null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    /** Null-safe read; pre-populated lists are replaced wholesale on publish. */
    public List<Iteration> iterations() { return iterations == null ? List.of() : iterations; }
    public List<Iteration> getIterations() { return iterations(); }
    public void setIterations(List<Iteration> iterations) { this.iterations = iterations; }
    public List<Story> stories() { return stories == null ? List.of() : stories; }
    public List<Story> getStories() { return stories(); }
    public void setStories(List<Story> stories) { this.stories = stories; }
    public List<Task> tasks() { return tasks == null ? List.of() : tasks; }
    public List<Task> getTasks() { return tasks(); }
    public void setTasks(List<Task> tasks) { this.tasks = tasks; }
    /** Null-safe: absent on a value published before questions travelled with the backlog. */
    public List<Decision> decisions() { return decisions == null ? List.of() : decisions; }
    public List<Decision> getDecisions() { return decisions(); }
    public void setDecisions(List<Decision> decisions) { this.decisions = decisions; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Backlog that = (Backlog) o;
        return Objects.equals(this.projectId, that.projectId)
            && Objects.equals(this.iterations(), that.iterations())
            && Objects.equals(this.stories(), that.stories())
            && Objects.equals(this.tasks(), that.tasks())
            // In equality, or a question being answered would compare EQUAL to the value the signal
            // already holds and the card would keep asking it. Read through the null-safe accessor
            // for the same reason every other list here is: the wire serializer reads that way too.
            && Objects.equals(this.decisions(), that.decisions());
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, iterations(), stories(), tasks(), decisions());
    }
}
