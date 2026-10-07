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

import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides what the machine may finish on its own while the operator sleeps, and the
 * queue that then keeps going.
 *
 * <p>The point of every assertion here is the same: unattended running must not lower the bar for
 * what counts as done, only remove the wait for a person to agree with something that has already
 * been proved. A story where anything at all is unclear stays exactly where it is until morning.
 */
class UnattendedRunningTest {

    private final UUID projectId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
    }

    // --- what may be accepted without a person --------------------------------------------------

    @Test
    void aStoryThatProvedEveryCheckItPromisedIsAccepted(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = delivered(store, "S1", "Store a book record");
            assertThat(UnattendedAcceptance.judge(store, story).acceptable()).isTrue();
        }
    }

    @Test
    void aStoryThatPromisedNoChecksIsLeftForAPerson(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            // The freeform escape hatch reaches "came back for a verdict" on a vacuous truth: every
            // check passed because there were none. Nothing was proved, so there is nothing to
            // rubber-stamp — this is precisely the story a person has to look at.
            Story story = delivered(store, "S2", "Tidy the footer");
            story.setCriterionIds(new ArrayList<>());
            UnattendedAcceptance.Verdict verdict = UnattendedAcceptance.judge(store, story);
            assertThat(verdict.acceptable()).isFalse();
            assertThat(verdict.reason()).contains("promised no checks");
        }
    }

    @Test
    void aStoryWhoseBuildStoppedToAskSomethingIsLeftForAPerson(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = delivered(store, "S3", "Lend a book");
            UUID runId = story.runIds().get(0);
            store.append(() -> {
                store.root().decisions.put(UUID.randomUUID(), new Decision(UUID.randomUUID(), runId,
                    DecisionKind.APPROVAL, "The acceptance tests are not red.",
                    DecisionState.PENDING, null, Instant.now()));
                return null;
            }).get();
            UnattendedAcceptance.Verdict verdict = UnattendedAcceptance.judge(store, story);
            assertThat(verdict.acceptable()).isFalse();
            assertThat(verdict.reason()).contains("stopped to ask");
        }
    }

    @Test
    void aStoryWhoseBuildIsStillGoingIsNotAccepted(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = delivered(store, "S4", "Search the shelf");
            UUID runId = story.runIds().get(0);
            store.append(() -> {
                Run run = store.root().runs.get(runId);
                store.root().runs.put(runId, run.withState(RunState.EXECUTING));
                return null;
            }).get();
            assertThat(UnattendedAcceptance.judge(store, story).acceptable()).isFalse();
        }
    }

    @Test
    void aStoryWithNoFinishedCodeIsNotAccepted(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = delivered(store, "S5", "Report on lending");
            story.setIntegrationCommit(null);
            story.setDeliveredCommit(null);
            UnattendedAcceptance.Verdict verdict = UnattendedAcceptance.judge(store, story);
            assertThat(verdict.acceptable()).isFalse();
            assertThat(verdict.reason()).contains("nothing to");
        }
    }

    // --- what the record says afterwards --------------------------------------------------------

    @Test
    void anAutomaticAcceptanceIsNotDressedUpAsAHumanOne(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, true);
            Story story = delivered(store, "S1", "Store a book record");

            assertThat(BacklogServiceImpl.accept(store, projectId, story, "unattended")).isEmpty();

            assertThat(story.state()).isEqualTo(StoryState.DONE);
            assertThat(story.acceptedUnattended()).isTrue();
            assertThat(store.changeHistory(projectId, story.id()).stream()
                .anyMatch(e -> "unattended".equals(e.actor()))).isTrue();
        }
    }

    @Test
    void codeThatCouldNotBePutWithTheRestIsNotAccepted(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            // The delivery refuses — the operator's checkout has uncommitted work, say. Accepting
            // anyway would mark the story done while its code sat on a branch nobody merges, and
            // every story waiting for it would then start against a tree without it.
            installWithDelivery(store, id -> "your project has changes that are not committed.");
            Story story = delivered(store, "S1", "Store a book record");

            String result = BacklogServiceImpl.accept(store, projectId, story, "unattended");
            assertThat(result).startsWith("error:").contains("not committed");
            assertThat(story.state()).isEqualTo(StoryState.REVIEW);
        }
    }

    // --- the queue ------------------------------------------------------------------------------

    @Test
    void nothingHappensWhileUnattendedRunningIsOff(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, false);
            Story story = delivered(store, "S1", "Store a book record");

            new UnattendedPilot(store, 1).tick();

            assertThat(store.getStory(story.id()).state()).isEqualTo(StoryState.REVIEW);
        }
    }

    @Test
    void oneStoryFailingDoesNotStopTheOnesThatDoNotDependOnIt(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicBoolean started = new AtomicBoolean(false);
            install(store, true, (goal, kind, storyId) -> {
                started.set(true);
                return persistRun(store, goal, kind, storyId, RunState.EXECUTING);
            });
            // One story stopped at two in the morning; another has nothing to do with it.
            Story stopped = ready(store, "S1", "Lend a book");
            stopped.setState(StoryState.BLOCKED);
            store.saveStory(stopped);
            Story independent = ready(store, "S2", "Tidy the footer");

            new UnattendedPilot(store, 1).tick();

            assertThat(started).isTrue();
            assertThat(store.getStory(independent.id()).state()).isEqualTo(StoryState.RUNNING);
            // The stopped one is left exactly as it was: nothing retries it on its behalf.
            assertThat(store.getStory(stopped.id()).state()).isEqualTo(StoryState.BLOCKED);
        }
    }

    @Test
    void aStoryWaitingForAnotherIsNotStarted(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, true, (goal, kind, storyId) ->
                persistRun(store, goal, kind, storyId, RunState.EXECUTING));
            Story first = ready(store, "S1", "Store a book record");
            Story second = ready(store, "S2", "Search the shelf");
            second.setDependsOnStoryIds(List.of(first.id()));
            store.saveStory(second);

            new UnattendedPilot(store, 1).tick();

            assertThat(store.getStory(first.id()).state()).isEqualTo(StoryState.RUNNING);
            assertThat(store.getStory(second.id()).state()).isEqualTo(StoryState.READY);
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private void install(ArtifactStore store, boolean unattended) {
        install(store, unattended, (goal, kind, storyId) ->
            persistRun(store, goal, kind, storyId, RunState.EXECUTING));
    }

    private void install(ArtifactStore store, boolean unattended,
                         ConsoleContext.StoryRunStarter starter) {
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> starter.start(goal, kind, null), runId -> { }, runId -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withStoryRuns(starter)
            .withUnattended(() -> unattended));
    }

    private void installWithDelivery(ArtifactStore store,
                                     ConsoleContext.StoryDeliverer deliverer) {
        install(store, true);
        ConsoleContext.get().withStoryDelivery(deliverer);
    }

    /** A story that has come back for a verdict with everything it promised proved. */
    private Story delivered(ArtifactStore store, String key, String title) throws Exception {
        Story story = story(key, title, StoryState.REVIEW);
        UUID runId = persistRun(store, title, WorkflowKind.GREENFIELD.name(), story.id(),
            RunState.DELIVERED);
        story.setRunIds(new ArrayList<>(List.of(runId)));
        story.setCriterionIds(new ArrayList<>(List.of(UUID.randomUUID())));
        story.setIntegrationCommit("0123456789abcdef0123456789abcdef01234567");
        story.setDeliveredCommit("0123456789abcdef0123456789abcdef01234567");
        store.saveStory(story);
        return story;
    }

    private Story ready(ArtifactStore store, String key, String title) {
        Story story = story(key, title, StoryState.READY);
        story.setCriterionIds(new ArrayList<>(List.of(UUID.randomUUID())));
        store.saveStory(story);
        return story;
    }

    private Story story(String key, String title, StoryState state) {
        return new Story(UUID.randomUUID(), projectId, key, StoryKind.DELIVERY, title, null,
            state, new ArrayList<>(), new ArrayList<>(), null, 0, StoryOrigin.BACKLOG, null, null,
            "agent", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
    }

    private UUID persistRun(ArtifactStore store, String goal, String kind, UUID storyId,
                            RunState state) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.valueOf(kind), state, projectId, storyId,
            null, UUID.randomUUID(), null, Instant.now(), new RunReport(runId, goal));
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
