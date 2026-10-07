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
package com.swarmcoder.console;

import com.swarmcoder.console.api.Backlog;
import com.swarmcoder.console.api.BacklogSignals;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.Iteration;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.Task;
import com.swarmcoder.store.ArtifactStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds and publishes the current project's backlog onto {@link BacklogSignals#CURRENT}.
 *
 * <p>Every element is DEEP-COPIED. This is not defensive habit, it is the contract: the signal's
 * {@code set()} dedups by {@code equals}, so if a published {@link Backlog} held the canonical
 * {@link Story} instances, mutating a story in place and republishing would compare EQUAL to the
 * retained value — the same objects on both sides — and the update would be silently dropped. The
 * panel would then sit there showing stale state with no error anywhere.
 */
final class BacklogPublisher {

    private BacklogPublisher() {}

    /** Builds the aggregate for a project; null projectId yields an empty backlog. */
    static Backlog build(ArtifactStore store, UUID projectId) {
        if (projectId == null) {
            return Backlog.empty();
        }
        List<Iteration> iterations = new ArrayList<>();
        for (Iteration iteration : store.listIterations(projectId)) {
            iterations.add(ArtifactStore.copyOf(iteration));
        }
        List<Story> stories = new ArrayList<>();
        List<Task> tasks = new ArrayList<>();
        for (Story story : store.listStories(projectId)) {
            stories.add(ArtifactStore.copyOf(story));
            for (Task task : store.storyTasks(story.id())) {
                // A FRESH instance, like every other element here: the signal dedups by equals,
                // so publishing the canonical Task would compare equal to the retained value and
                // the update would be dropped in silence.
                tasks.add(ArtifactStore.copyOf(task));
            }
        }
        // The project's questions, so each can be shown on the card of the story it belongs to.
        // Scoped by run: a decision names the run it was raised in, and a run belongs to a story.
        java.util.Set<UUID> projectRuns = new java.util.HashSet<>();
        for (Story story : stories) {
            projectRuns.addAll(story.runIds());
        }
        List<Decision> decisions = new ArrayList<>();
        for (Decision decision : store.root().decisions.values()) {
            boolean mine = decision.runId() == null
                ? runlessBelongsHere(store, projectId)
                : projectRuns.contains(decision.runId())
                    || ownedByProject(store, decision.runId(), projectId);
            if (mine) {
                // A FRESH instance, like every other element here: the signal dedups by equals, so
                // publishing the canonical object means a decision answered in place compares equal
                // to the retained value and the card goes on asking a question already answered.
                decisions.add(new Decision(decision.id(), decision.runId(), decision.kind(),
                    decision.briefMarkdown(), decision.state(), decision.humanResponse(),
                    decision.createdAt()));
            }
        }
        return new Backlog(projectId, iterations, stories, tasks, decisions);
    }

    /**
     * A decision with no run is process-wide — a budget exhaustion, minted once by the cloud gate.
     *
     * <p>It genuinely belongs to no story, so it cannot go on a card. It is published to whichever
     * project is being looked at and the board shows it as a banner: rule 1 says nothing may vanish,
     * and dropping it because it does not fit the shape would be exactly that.
     */
    private static boolean runlessBelongsHere(ArtifactStore store, UUID projectId) {
        return projectId != null;
    }

    /** A run claimed by this project but not yet named in any story's run list. */
    private static boolean ownedByProject(ArtifactStore store, UUID runId, UUID projectId) {
        com.swarmcoder.domain.Run run = store.root().runs.get(runId);
        return run != null && projectId.equals(run.projectId());
    }

    /** Publishes the project's backlog. Best-effort: publishing must never break a mutation. */
    static void publish(ArtifactStore store, UUID projectId) {
        try {
            BacklogSignals.CURRENT.set(build(store, projectId));
        } catch (Exception ignored) {
            // the store stays the source of truth; a missed publish costs a redraw, not data
        }
        // The next-step bar is derived from the backlog too — the first story planned, and the
        // first one made READY, both change what the operator should do next.
        ReadinessPublisher.refresh();
    }
}
