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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A story left saying "building now" by a stopped process is freed at startup.
 *
 * <p>The defect in the operator's words: three stories reading "building now" for fifteen hours
 * after a restart, with no button on any of them. A story leaves RUNNING only when its workflow
 * reaches the delivery check, so a process that dies mid-run strands it there — and
 * {@code promoteStory} refuses anything but DRAFT while {@code startSession} refuses anything but
 * READY, which left nothing in the product able to move it.
 *
 * <p>The discrimination is the point, and it is what the second test pins: a story whose run is
 * still going must NOT be freed, or restarting the Console would quietly authorise a second run
 * against work already in flight.
 */
class StrandedStoryRecoveryTest {

    @TempDir
    Path dir;

    private ArtifactStore store;
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void openStore() {
        store = new ArtifactStore(dir);
    }

    @AfterEach
    void closeStore() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void aStoryWhoseRunDiedWithTheProcessIsFreedToBeBuiltAgain() {
        // The run reached a terminal state, or never came back; either way nothing is driving it.
        Run dead = run(RunState.ABORTED);
        Story stranded = story("S1", StoryState.RUNNING, dead.id());

        engine().reconcileStrandedStories();

        Story freed = store.root().stories().get(stranded.id());
        assertThat(freed.state())
            .as("nothing in the product could move this, so startup has to")
            .isEqualTo(StoryState.READY);
        // READY rather than BLOCKED: being interrupted says nothing about whether the work is sound.
        assertThat(store.changeHistory(projectId, freed.id()))
            .as("the journal must say why it moved, or it looks like the state changed by itself")
            .isNotEmpty();
    }

    @Test
    void aStoryWhoseRunIsStillGoingIsLeftAlone() {
        // Mid-workflow: resumeAll will have revived this one, so it is genuinely building.
        Run live = run(RunState.EXECUTING);
        Story building = story("S2", StoryState.RUNNING, live.id());
        // Waiting out a model-endpoint outage is NOT abandoned: something is driving it, and it
        // resumes by itself. Freeing this story would put it back under "Ready to build" and invite
        // the operator to pay for a second swarm over a model server that is merely restarting.
        // (This case used to be a run parked at APPROVAL — a state that no longer exists.)
        Run paused = run(RunState.EXECUTING);
        paused.setPausedSince(Instant.now());
        paused.setPauseReason("paused — your model server is not answering");
        store.append(() -> store.root().runs.put(paused.id(), paused));
        Story waiting = story("S3", StoryState.RUNNING, paused.id());

        engine().reconcileStrandedStories();

        assertThat(store.root().stories().get(building.id()).state())
            .isEqualTo(StoryState.RUNNING);
        assertThat(store.root().stories().get(waiting.id()).state())
            .as("a run waiting for its endpoint is paused, not lost")
            .isEqualTo(StoryState.RUNNING);
    }

    @Test
    void aStoryThatIsNotRunningIsNeverTouched() {
        Story ready = story("S4", StoryState.READY, null);
        Story done = story("S5", StoryState.DONE, null);

        engine().reconcileStrandedStories();

        assertThat(store.root().stories().get(ready.id()).state()).isEqualTo(StoryState.READY);
        assertThat(store.root().stories().get(done.id()).state()).isEqualTo(StoryState.DONE);
    }

    private WorkflowEngine engine() {
        return new WorkflowEngine(null, null, null, store);
    }

    private Run run(RunState state) {
        UUID id = UUID.randomUUID();
        Run run = new Run(id, WorkflowKind.GREENFIELD, state, projectId, null, null, null, null,
            Instant.now(), new RunReport(id, "a goal"));
        store.append(() -> store.root().runs.put(id, run));
        return run;
    }

    private Story story(String key, StoryState state, UUID runId) {
        Story story = new Story();
        story.setId(UUID.randomUUID());
        story.setProjectId(projectId);
        story.setKey(key);
        story.setKind(StoryKind.DELIVERY);
        story.setTitle("story " + key);
        story.setState(state);
        story.setOrigin(StoryOrigin.BACKLOG);
        story.setRunIds(runId == null ? new ArrayList<>() : new ArrayList<>(List.of(runId)));
        story.setCreatedAt(Instant.now());
        store.saveStory(story);
        return story;
    }
}
