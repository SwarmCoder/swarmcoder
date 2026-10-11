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

import com.swarmcoder.console.api.AttentionItem;
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
import com.swarmcoder.store.SupersededRuns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sending a story back, or starting it again, ends its earlier builds for good: their questions
 * are closed with the reason, they are not counted or shown as owed, and an answer to one of them
 * is refused instead of handing the old build back to its engine.
 */
class AStoryHasOneBuildTest {

    private final UUID projectId = UUID.randomUUID();
    private final SupervisorDesk desk = new SupervisorDesk();
    private final List<UUID> handedBack = new ArrayList<>();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
        DecisionAnswers.reset();
    }

    @Test
    void sendingAStoryBackEndsItsBuildAndClosesItsQuestion(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store);
            Story story = story(store, "S1", StoryState.BLOCKED);
            UUID stopped = run(store, story.id(), RunState.FINAL_INTEGRATION,
                Instant.now().minusSeconds(30));
            attach(store, story, stopped);
            UUID question = ask(store, stopped, "FINAL_INTEGRATION failed");

            assertThat(desk.sendBack("S1", "store the due date")).isEmpty();

            Run ended = store.root().runs.get(stopped);
            assertThat(ended.state()).isEqualTo(RunState.ABORTED);
            assertThat(ended.parkedAt()).isNull();
            Decision closed = store.root().decisions.get(question);
            assertThat(closed.state()).isEqualTo(DecisionState.RESOLVED);
            assertThat(closed.humanResponse())
                .startsWith(SupersededRuns.CLOSED_PREFIX)
                .contains("S1 was sent back to be built again: store the due date");
            assertThat(PendingDecisions.all(store)).isZero();

            String refused = desk.answerDecision(question.toString(), "retry", "try again");
            assertThat(refused).startsWith("error:").contains("earlier build of S1")
                .contains("newer build");
            assertThat(handedBack).isEmpty();
            assertThat(store.root().decisions.get(question).humanResponse())
                .as("the refused answer is not written over the recorded reason")
                .startsWith(SupersededRuns.CLOSED_PREFIX);
        }
    }

    @Test
    void startingAStoryAgainEndsABuildThatWasStillUnfinished(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store);
            // Ready again with its earlier build never ended: what a store from before holds.
            Story story = story(store, "S1", StoryState.READY);
            UUID earlier = run(store, story.id(), RunState.EXECUTING, null);
            attach(store, story, earlier);
            UUID question = ask(store, earlier, "a task was blocked");

            String started = new BacklogServiceImpl().startSession(story.id().toString());

            assertThat(started).doesNotStartWith("error:");
            UUID current = UUID.fromString(started);
            assertThat(store.getStory(story.id()).runIds()).containsExactly(earlier, current);
            assertThat(store.root().runs.get(earlier).state()).isEqualTo(RunState.ABORTED);
            assertThat(store.root().runs.get(current).state()).isEqualTo(RunState.EXECUTING);
            assertThat(store.root().decisions.get(question).humanResponse())
                .startsWith(SupersededRuns.CLOSED_PREFIX).contains("S1 has a newer build");
        }
    }

    @Test
    void onlyTheCurrentBuildsQuestionIsCountedShownAndAnswerable(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store);
            // The earlier build still stopped with its question open beside the newer one: what
            // was in the store when the application was restarted.
            Story story = story(store, "S1", StoryState.RUNNING);
            UUID earlier = run(store, story.id(), RunState.FINAL_INTEGRATION,
                Instant.now().minusSeconds(90));
            UUID current = run(store, story.id(), RunState.EXECUTING,
                Instant.now().minusSeconds(30));
            attach(store, story, earlier, current);
            UUID old = ask(store, earlier, "FINAL_INTEGRATION failed on the earlier build");
            UUID live = ask(store, current, "a task of the current build is blocked");

            assertThat(PendingDecisions.all(store)).isEqualTo(1);
            assertThat(PendingDecisions.forProject(store, projectId, List.of(story))).isEqualTo(1);
            AttentionItem first = desk.nextAttention(0);
            assertThat(first.kind()).isEqualTo("RUN_QUESTION");
            assertThat(first.question()).contains("a task of the current build is blocked");
            assertThat(first.evidence()).doesNotContain("other question(s)");
            assertThat(desk.attention()).as("nothing shown comes from the earlier build")
                .noneMatch(item -> (item.question() + item.evidence()).contains("earlier build"));

            String refused = desk.answerDecision(old.toString(), "retry", "");
            assertThat(refused).startsWith("error:").contains("earlier build of S1")
                .contains("newer build");
            assertThat(store.root().decisions.get(old).state())
                .isEqualTo(DecisionState.PENDING);
            assertThat(handedBack).as("the earlier build is not handed back").isEmpty();
            try {
                new ControlServiceImpl().resolveDecision(old.toString(), "try it again");
                throw new AssertionError("an answer on screen to an earlier build was accepted");
            } catch (IllegalStateException e) {
                assertThat(e.getMessage()).contains("newer build");
            }
            assertThat(handedBack).isEmpty();

            assertThat(desk.answerDecision(live.toString(), "retry", ""))
                .doesNotStartWith("error:");
            assertThat(handedBack).containsExactly(current);
        }
    }

    private void install(ArtifactStore store) {
        ConsoleContext.StoryRunStarter starter = (goal, kind, storyId) ->
            run(store, storyId, RunState.EXECUTING, null);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> starter.start(goal, kind, null), runId -> { }, runId -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withStoryRuns(starter)
            .withUnattended(() -> false)
            .withSupervised(() -> false)
            .withRunResume(id -> {
                handedBack.add(id);
                return "";
            }));
    }

    private Story story(ArtifactStore store, String key, StoryState state) {
        return store.saveStory(new Story(UUID.randomUUID(), projectId, key, StoryKind.DELIVERY,
            "Lend a book", null, state, new ArrayList<>(), new ArrayList<>(), null, 0,
            StoryOrigin.BACKLOG, null, null, "agent", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now()));
    }

    private static void attach(ArtifactStore store, Story story, UUID... runs) {
        story.setRunIds(new ArrayList<>(List.of(runs)));
        store.saveStory(story);
    }

    private UUID run(ArtifactStore store, UUID storyId, RunState state, Instant parkedAt) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, state, projectId, storyId,
            null, UUID.randomUUID(), null, Instant.now(), new RunReport(runId, "goal"));
        if (parkedAt != null) {
            run.setParkedAt(parkedAt);
            run.setParkReason("stopped to ask");
        }
        try {
            store.append(() -> store.root().runs.put(runId, run)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return runId;
    }

    private static UUID ask(ArtifactStore store, UUID runId, String brief) throws Exception {
        UUID id = UUID.randomUUID();
        store.append(() -> store.root().decisions.put(id, new Decision(id, runId,
            DecisionKind.BLOCKED_TASK, brief, DecisionState.PENDING, null, Instant.now()))).get();
        return id;
    }
}
