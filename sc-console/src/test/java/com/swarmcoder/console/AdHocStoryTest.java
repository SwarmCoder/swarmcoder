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

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The freeform escape hatch — the only way in on a project that has no requirement graph yet.
 *
 * <p>The important assertion is the ORDER. The work item has to exist before the run starts,
 * because the workflow engine advances a run on its own thread the instant it is handed over and
 * rebuilds it from its own copy at every transition. A storyId attached after {@code startRun}
 * returns is written to an object nobody reads again, so the run finishes without ever moving its
 * story out of "building" — which is how every freeform run's backlog card came to sit there for
 * ever. That is what this file stops coming back.
 */
class AdHocStoryTest {

    private final UUID projectId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
    }

    @Test
    void theRunIsStartedAlreadyCarryingItsWorkItem(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            // What the engine sees at the moment it is handed the run — captured inside the
            // starter, which is the only place the ordering bug is observable.
            AtomicReference<UUID> storyIdAtStart = new AtomicReference<>();
            AtomicReference<Boolean> storyExistedAtStart = new AtomicReference<>(false);

            install(store, (goal, kind, storyId) -> {
                storyIdAtStart.set(storyId);
                storyExistedAtStart.set(storyId != null && store.getStory(storyId) != null);
                return persistRun(store, goal, kind, storyId);
            });

            AdHocStory.Started started = AdHocStory.start(ConsoleContext.get(), projectId,
                WorkflowKind.ENHANCEMENT.name(), "Make the footer say the right year");

            assertThat(started).isNotNull();
            assertThat(storyIdAtStart.get())
                .as("the run must be handed to the engine already bound to its work item")
                .isEqualTo(started.story().id());
            assertThat(storyExistedAtStart.get())
                .as("and the work item must already be in the store when that happens")
                .isTrue();
            assertThat(store.root().runs.get(started.runId()).storyId())
                .isEqualTo(started.story().id());
        }
    }

    @Test
    void theWorkItemIsAnEnablerThatAnswersToNoRequirement(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, (goal, kind, storyId) -> persistRun(store, goal, kind, storyId));

            AdHocStory.Started started = AdHocStory.start(ConsoleContext.get(), projectId,
                WorkflowKind.ENHANCEMENT.name(), "Make the footer say the right year");

            Story story = store.getStory(started.story().id());
            assertThat(story.kind()).isEqualTo(StoryKind.ENABLER);
            assertThat(story.origin()).isEqualTo(StoryOrigin.AD_HOC);
            assertThat(story.state()).isEqualTo(StoryState.RUNNING);
            // NO criteria, deliberately. It delivers no requirement until somebody triages it — and
            // that is exactly what lets an existing project be worked on before anyone has written
            // a requirement down.
            assertThat(story.criterionIds()).isEmpty();
            assertThat(story.requirementIds()).isEmpty();
            assertThat(story.originRunId()).isEqualTo(started.runId());
            assertThat(story.runIds()).containsExactly(started.runId());
        }
    }

    @Test
    void aLongGoalBecomesAReadableCardTitle() {
        String goal = "x".repeat(200);
        assertThat(AdHocStory.title(goal)).hasSize(78).endsWith("…");
        assertThat(AdHocStory.title("  short goal  ")).isEqualTo("short goal");
    }

    @Test
    void aWorkItemIsNotLeftClaimingWorkThatNeverStarted(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, (goal, kind, storyId) -> {
                throw new IllegalStateException("the endpoint is down");
            });

            try {
                AdHocStory.start(ConsoleContext.get(), projectId,
                    WorkflowKind.ENHANCEMENT.name(), "Make the footer say the right year");
                throw new AssertionError("the failure must reach the caller, not be swallowed");
            } catch (IllegalStateException expected) {
                assertThat(expected).hasMessageContaining("endpoint is down");
            }

            List<Story> stories = store.listStories(projectId);
            assertThat(stories).hasSize(1);
            // Left in "building" it would say, for ever, that a run is working on it.
            assertThat(stories.get(0).state()).isEqualTo(StoryState.BLOCKED);
            assertThat(stories.get(0).rationale()).contains("endpoint is down");
        }
    }

    @Test
    void withNoProjectNothingIsStartedAndTheCallerIsTold(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, (goal, kind, storyId) -> persistRun(store, goal, kind, storyId));
            assertThat(AdHocStory.start(ConsoleContext.get(), null,
                WorkflowKind.ENHANCEMENT.name(), "anything")).isNull();
            assertThat(AdHocStory.start(ConsoleContext.get(), projectId,
                WorkflowKind.ENHANCEMENT.name(), "   ")).isNull();
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private void install(ArtifactStore store, ConsoleContext.StoryRunStarter starter) {
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> starter.start(goal, kind, null), runId -> { }, runId -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withStoryRuns(starter));
    }

    /** Stands in for the workflow engine: persists the run exactly as the real starter does. */
    private UUID persistRun(ArtifactStore store, String goal, String kind, UUID storyId) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.valueOf(kind), RunState.INTAKE, projectId, storyId,
            null, null, null, Instant.now(), new RunReport(runId, goal));
        try {
            store.append(() -> {
                store.root().runs.put(runId, run);
                return null;
            }).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return runId;
    }
}
