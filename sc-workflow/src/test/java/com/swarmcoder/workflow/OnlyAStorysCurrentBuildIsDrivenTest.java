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

import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.SupersededRuns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A story has one build at a time. An earlier build of a story that was sent back or started again
 * is never driven again, asks nothing and writes nothing onto the story.
 *
 * <p>The shapes are the ones a store written before stories ended their earlier builds holds: the
 * earlier run still at its stage with its question open, beside the story's newer run. No model is
 * called; the swarm is a stand-in that records which run it was handed.
 */
class OnlyAStorysCurrentBuildIsDrivenTest {

    @TempDir
    Path tmp;

    private ArtifactStore store;
    private ScriptedLlm llm;
    private Project project;
    private Path repo;
    private final List<UUID> executed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        store = new ArtifactStore(tmp.resolve("store"));
        llm = new ScriptedLlm(conversation -> "I decline to produce JSON.");
        repo = Files.createDirectories(tmp.resolve("repo"));
        project = store.ensureProject("library", repo.toString(), List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (llm != null) {
            llm.close();
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    void startUpResumesTheCurrentBuildAndEndsTheOnesItsStoryReplaced() throws Exception {
        // Sent back, then started again: the earlier run kept its stage and its question.
        Story rebuilt = story("S1", StoryState.RUNNING);
        Run earlier = run(rebuilt.id(), null);
        Run current = run(rebuilt.id(), Instant.now().minusSeconds(60));
        attach(rebuilt, earlier, current);
        UUID earlierQuestion = ask(earlier.id(), "FINAL_INTEGRATION failed on the earlier build");
        ask(current.id(), "a task of the current build is blocked");
        // Sent back and not started again yet: no build of it is current.
        Story waiting = story("S2", StoryState.READY);
        Run sentBack = run(waiting.id(), null);
        attach(waiting, sentBack);
        UUID sentBackQuestion = ask(sentBack.id(), "the build that was sent back asked this");

        WorkflowEngine engine = engine(run -> {
            executed.add(run.id());
            throw new RunMustPark("still blocked");
        });
        List<RunResumer.Resumed> resumed = new RunResumer(store, id -> engine).resumeAll();

        assertThat(resumed).extracting(RunResumer.Resumed::runId)
            .as("only the story's current build is picked up")
            .containsExactly(current.id());
        awaitParkedWith(current.id(), "still blocked");
        assertThat(executed).containsExactly(current.id());
        for (UUID over : List.of(earlier.id(), sentBack.id())) {
            Run ended = store.root().runs.get(over);
            assertThat(ended.state()).isEqualTo(RunState.ABORTED);
            assertThat(ended.parkedAt()).isNull();
        }
        for (UUID closed : List.of(earlierQuestion, sentBackQuestion)) {
            Decision decision = store.root().decisions.get(closed);
            assertThat(decision.state()).isEqualTo(DecisionState.RESOLVED);
            assertThat(decision.humanResponse()).startsWith(SupersededRuns.CLOSED_PREFIX);
        }
        assertThat(store.root().decisions.get(earlierQuestion).humanResponse())
            .contains("S1 has a newer build");
        assertThat(store.root().decisions.get(sentBackQuestion).humanResponse())
            .contains("S2 was sent back");
        assertThat(pending())
            .as("one question is open: the current build's, asked once")
            .singleElement()
            .satisfies(d -> assertThat(d.runId()).isEqualTo(current.id()));
        assertThat(store.getStory(rebuilt.id()).state()).isEqualTo(StoryState.RUNNING);
        assertThat(store.getStory(waiting.id()).state()).isEqualTo(StoryState.READY);
    }

    @Test
    void aBuildReplacedWhileItIsWorkingAsksNothingAndStaysEnded() throws Exception {
        Story story = story("S1", StoryState.RUNNING);
        Run first = run(story.id(), null);
        attach(story, first);
        UUID second = UUID.randomUUID();

        WorkflowEngine engine = engine(run -> {
            executed.add(run.id());
            // The story is sent back and started again while this build's workers are busy.
            Story now = store.getStory(story.id());
            now.setRunIds(new ArrayList<>(List.of(first.id(), second)));
            store.saveStory(now);
            SupersededRuns.supersede(store, now, second, "S1 has a newer build.");
            throw new RunMustPark("wave 1 would not merge");
        });
        engine.advanceAsync(store.root().runs.get(first.id())).get(60, TimeUnit.SECONDS);

        Run ended = store.root().runs.get(first.id());
        assertThat(ended.state())
            .as("the thread that was driving it may not write it back at its old stage")
            .isEqualTo(RunState.ABORTED);
        assertThat(ended.parkedAt()).isNull();
        assertThat(pending()).as("an earlier build asks nothing").isEmpty();
        Story after = store.getStory(story.id());
        assertThat(after.state()).isEqualTo(StoryState.RUNNING);
        assertThat(after.waitingReason()).isNull();

        // And handing it to the engine again, as an answer or a start-up would, drives nothing.
        engine.advanceAsync(ended).get(60, TimeUnit.SECONDS);
        assertThat(executed).containsExactly(first.id());
    }

    @Test
    void anUnfinishedEarlierBuildHandedToTheEngineIsEndedInsteadOfDriven() throws Exception {
        Story story = story("S1", StoryState.BLOCKED);
        story.setWaitingReason("the current build's own reason");
        Run earlier = run(story.id(), null);
        Run current = run(story.id(), Instant.now().minusSeconds(60));
        attach(story, earlier, current);
        UUID question = ask(earlier.id(), "the earlier build's question");

        WorkflowEngine engine = engine(run -> {
            executed.add(run.id());
            return run;
        });
        engine.advanceAsync(store.root().runs.get(earlier.id())).get(60, TimeUnit.SECONDS);

        assertThat(executed).isEmpty();
        assertThat(store.root().runs.get(earlier.id()).state()).isEqualTo(RunState.ABORTED);
        assertThat(store.root().runs.get(current.id()).state()).isEqualTo(RunState.EXECUTING);
        assertThat(store.root().decisions.get(question).state())
            .isEqualTo(DecisionState.RESOLVED);
        Story after = store.getStory(story.id());
        assertThat(after.state()).isEqualTo(StoryState.BLOCKED);
        assertThat(after.waitingReason()).isEqualTo("the current build's own reason");
    }

    @Test
    void aBuildPickedUpAgainDoesNotLeaveItsOldQuestionOpenBesideTheNewOne() throws Exception {
        // Unfinished with a question open and no "stopped" mark: the process died while one task
        // had asked and others were still building.
        Run run = run(null, null);
        ask(run.id(), "wave 1 would not merge");

        WorkflowEngine engine = engine(driven -> {
            throw new RunMustPark("wave 1 would not merge");
        });
        engine.advanceAsync(store.root().runs.get(run.id())).get(60, TimeUnit.SECONDS);

        assertThat(store.root().runs.get(run.id()).parkedAt()).isNotNull();
        assertThat(pending()).as("asked once, not twice").hasSize(1);
    }

    private WorkflowEngine engine(SwarmEngine swarm) {
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        return new WorkflowEngine(unusedRuntime, swarm,
            new VllmClient(llm.baseUrl(), "", "test-model", true), store,
            new CloudGate(0, null), repo, null, null, null, List.of());
    }

    private Story story(String key, StoryState state) {
        Story story = new Story(UUID.randomUUID(), project.id(), key, StoryKind.DELIVERY,
            "Lend a book", null, state, new ArrayList<>(), new ArrayList<>(), null, 0,
            StoryOrigin.BACKLOG, null, null, "agent", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
        return store.saveStory(story);
    }

    private void attach(Story story, Run... runs) {
        List<UUID> ids = new ArrayList<>();
        for (Run run : runs) {
            ids.add(run.id());
        }
        story.setRunIds(ids);
        store.saveStory(story);
    }

    /** An unfinished run at EXECUTING, where the next thing done is to hand it to the swarm. */
    private Run run(UUID storyId, Instant parkedAt) throws Exception {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING, project.id(),
            storyId, null, UUID.randomUUID(), null, Instant.now().minusSeconds(executedOrder()),
            new RunReport(runId, "goal"));
        if (parkedAt != null) {
            run.setParkedAt(parkedAt);
            run.setParkReason("stopped to ask");
        }
        store.append(() -> store.root().runs.put(runId, run)).get();
        return run;
    }

    private int order = 1_000;

    /** Earlier-created runs started earlier, so start-up meets them oldest first. */
    private int executedOrder() {
        return order--;
    }

    private UUID ask(UUID runId, String brief) throws Exception {
        UUID id = UUID.randomUUID();
        store.append(() -> store.root().decisions.put(id, new Decision(id, runId,
            DecisionKind.BLOCKED_TASK, brief, DecisionState.PENDING, null, Instant.now()))).get();
        return id;
    }

    private List<Decision> pending() {
        return store.root().decisions.values().stream()
            .filter(d -> d.state() == DecisionState.PENDING).toList();
    }

    private void awaitParkedWith(UUID runId, String reason) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.parkedAt() != null && reason.equals(run.parkReason())
                    && pending().stream().anyMatch(d -> reason.equals(d.briefMarkdown()))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " never stopped with: " + reason);
    }
}
