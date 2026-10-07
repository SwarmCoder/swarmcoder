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

import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.runtime.PromptBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker that only ever looks is interrupted and pointed at what it already has (§32).
 *
 * <p>Sometimes taking a compiled library apart IS the right move — no source, no documentation,
 * nothing else to go on. It is not forbidden here. What is bounded is never stopping: ten workers
 * once spent a whole run inside {@code jar xf} and {@code javap} and wrote nothing at all,
 * finishing at fourteen to twenty-two turns against a thirty-turn cap.
 */
class InvestigationSpiralTest {

    @TempDir
    Path worktree;

    private Task task() {
        return new Task(UUID.randomUUID(), 1, "t", "i", Set.of("src"), Set.of("src"),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    /** The threshold sits between what an honest worker uses and where the spiral shows. */
    @Test
    void theThresholdIsEightLookingOnlyToolCalls() {
        EarlyKillEnforcer enforcer = new EarlyKillEnforcer();

        assertThat(EarlyKillEnforcer.INVESTIGATION_TOOL_CALLS_BEFORE_NUDGE).isEqualTo(8);
        // A worker that reads a few files and then writes is never touched.
        for (int calls = 1; calls < 8; calls++) {
            assertThat(enforcer.shouldNudgeOutOfInvestigation(calls, false))
                .as("looking-only call %d", calls).isFalse();
        }
        assertThat(enforcer.shouldNudgeOutOfInvestigation(8, false)).isTrue();
        // On the threshold calls only — not on every turn in between.
        assertThat(enforcer.shouldNudgeOutOfInvestigation(9, false)).isFalse();
        assertThat(enforcer.shouldNudgeOutOfInvestigation(15, false)).isFalse();
    }

    /**
     * Steering twice, then stopping — because once was not a policy.
     *
     * <p>The nudge used to fire on the eighth call and never again, and a worker that had ever
     * written anything was excluded from it for the rest of its life. The worker that cost 105,529
     * tokens on 2026-09-02 went through both holes: one steer at turn 8, two files written at turn
     * 52, then forty-one more tool calls inside {@code javap} with nothing watching at all.
     */
    @Test
    void aWorkerIsSteeredTwiceAndThenStopped() {
        EarlyKillEnforcer enforcer = new EarlyKillEnforcer();

        assertThat(EarlyKillEnforcer.INVESTIGATION_TOOL_CALLS_BEFORE_SECOND_NUDGE).isEqualTo(16);
        assertThat(EarlyKillEnforcer.INVESTIGATION_TOOL_CALLS_BEFORE_KILL).isEqualTo(24);

        assertThat(enforcer.shouldNudgeOutOfInvestigation(16, false)).isTrue();
        // Having written earlier no longer buys immunity: the count is calls SINCE the last write.
        assertThat(enforcer.shouldNudgeOutOfInvestigation(8, true)).isTrue();
        assertThat(enforcer.shouldNudgeOutOfInvestigation(16, true)).isTrue();

        for (int calls = 0; calls < 24; calls++) {
            assertThat(enforcer.checkProgress(calls, 0))
                .as("looking-only call %d is still allowed", calls).isNull();
        }
        assertThat(enforcer.checkProgress(24, 0)).isEqualTo(KillReason.NO_PROGRESS);
        assertThat(enforcer.progressDiagnosis(24, 0))
            .isEqualTo("24 tool calls in a row without changing any file");
    }

    /**
     * A worker killed at the twenty-four call backstop always passed through the read-pause first
     * — it starts at seventeen, one past the second nudge, and the count since the last write only
     * ever climbs between writes. The diagnosis says so in that case, so the operator reading a
     * kill line does not have to work out for themselves whether the pause was ever reached.
     */
    @Test
    void theDiagnosisNamesThePauseForAWorkerKilledAfterIt() {
        EarlyKillEnforcer enforcer = new EarlyKillEnforcer();

        assertThat(enforcer.progressDiagnosis(24, 0, true))
            .isEqualTo("24 tool calls in a row without changing any file - it was asked to write "
                + "after 16 reads and read on instead");
        // Not asserted for the pause when it never engaged — the fruitless-repeat path can kill a
        // worker well before it ever reaches sixteen calls, and saying it read on after being
        // paused would be false for that worker.
        assertThat(enforcer.progressDiagnosis(3, 6, true))
            .isEqualTo("6 tool calls in a row that came back with nothing");
        // The unqualified overload is unchanged — existing callers see the same text they always
        // did.
        assertThat(enforcer.progressDiagnosis(24, 0))
            .isEqualTo(enforcer.progressDiagnosis(24, 0, false));
    }

    /**
     * Six tool calls in a row that returned nothing is guessing, and guessing is stopped early.
     *
     * <p>The tail of the 121-turn run was the same {@code javap} output filtered six different
     * ways, each producing no output at all. Nothing about the COMMANDS was detectable — they all
     * looked different. What was detectable is that none of them said anything.
     */
    @Test
    void sixCallsInARowThatReturnNothingStopTheWorker() {
        EarlyKillEnforcer enforcer = new EarlyKillEnforcer();

        assertThat(EarlyKillEnforcer.FRUITLESS_CALLS_BEFORE_KILL).isEqualTo(6);
        assertThat(enforcer.checkProgress(3, 5)).isNull();
        assertThat(enforcer.checkProgress(3, 6)).isEqualTo(KillReason.NO_PROGRESS);
        assertThat(enforcer.progressDiagnosis(3, 6))
            .isEqualTo("6 tool calls in a row that came back with nothing");
    }

    /** Missing and repeated results are fruitless; a real answer clears the streak. */
    @Test
    void aResultThatSaysNothingCountsAsFruitlessAndARealOneResetsIt() throws Exception {
        Files.writeString(worktree.resolve("a.txt"), "hello");
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task());

        // A file that is not there says nothing, three times running.
        for (int i = 1; i <= 3; i++) {
            toolbox.read("missing-" + i + ".txt");
        }
        assertThat(toolbox.fruitlessCallsInARow()).isEqualTo(3);

        // A real file says something, once.
        toolbox.read("a.txt");
        assertThat(toolbox.fruitlessCallsInARow()).isZero();

        // The SAME file again says nothing new.
        toolbox.read("a.txt");
        assertThat(toolbox.fruitlessCallsInARow()).isEqualTo(1);
    }

    /**
     * When the documentation search answers different questions with the same section, the RUN
     * says so — and the worker reaching for a decompiler is recorded as the search failing.
     *
     * <p>This is the distinction the 2026-09-02 incident turned on. From outside, that worker
     * looked like one ignoring five separate rules telling it not to unpack jars. What actually
     * happened is that it obeyed the nudge, asked four well-formed questions, got the same
     * irrelevant page every time, and only then did the one thing left to it. Nothing in the run
     * recorded that, so the evidence pointed at the model instead of at the search. It does now.
     */
    @Test
    void whenTheSearchAnswersEveryQuestionTheSameWayTheRunSaysSo() {
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task(),
            query -> "Documentation for \"" + query + "\":\n\n#### stack/AGENTS.md > Running the "
                + "examples\nAll twelve examples bind a port of their own.\n");

        assertThat(toolbox.documentationLookupIsNotDiscriminating()).isFalse();
        toolbox.lookupApi("how do I handle a button click");
        toolbox.lookupApi("ClickEvent ValueChangeEvent listener");
        assertThat(toolbox.documentationLookupIsNotDiscriminating())
            .as("two overlapping questions are not yet evidence").isFalse();

        String third = toolbox.lookupApi("obtain a service stub from the client");

        assertThat(toolbox.documentationLookupIsNotDiscriminating()).isTrue();
        assertThat(third)
            .contains("[orchestrator]")
            .contains("3 different questions with the same section")
            .contains("do not start unpacking jars");
        assertThat(toolbox.drainSteer()).contains("same section");
    }

    /**
     * The nudge given when the search is not discriminating names the SECTION it keeps returning,
     * not just that there is one — so the worker (and anyone reading the transcript later) can
     * recognise it if it comes up again instead of being told only "the same section".
     */
    @Test
    void theNudgeNamesTheSectionThatKeepsComingBack() {
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task(),
            query -> "Documentation for \"" + query + "\":\n\n#### stack/AGENTS.md > Running the "
                + "examples\nAll twelve examples bind a port of their own.\n");

        toolbox.lookupApi("how do I handle a button click");
        toolbox.lookupApi("ClickEvent ValueChangeEvent listener");
        String third = toolbox.lookupApi("obtain a service stub from the client");

        assertThat(third).contains("stack/AGENTS.md > Running the examples");
    }

    /**
     * What {@code WorkerLoop} turns {@code toolbox.lookupHistory()} into for the stall guard's WARN
     * and for the candidate's diagnosis: numbered lines of "question → section", the same format
     * whichever of the two reads it. This is the part of the stall guard that does not need a live
     * model session to test — the WARN's dynamic suffix is exactly this text.
     */
    @Test
    void workerLoopNumbersTheLookupHistoryForTheKillLineAndTheDiagnosis() {
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task(),
            query -> "Documentation for \"" + query + "\":\n\n#### docs/guides/persistence.md "
                + "› Saving data\nCall storeAll() when you are done.\n");

        toolbox.lookupApi("EclipseStore root store setup");
        toolbox.lookupApi("how to get storage instance");
        toolbox.lookupApi("ZeroZDbNode embedded factory");

        List<String> lines = WorkerLoop.formatLookupEvidence(toolbox.lookupHistory());

        assertThat(lines).containsExactly(
            "1. \"EclipseStore root store setup\" → docs/guides/persistence.md › Saving data",
            "2. \"how to get storage instance\" → docs/guides/persistence.md › Saving data",
            "3. \"ZeroZDbNode embedded factory\" → docs/guides/persistence.md › Saving data");
    }

    /** A question that never matched any section still gets a line — it says so plainly. */
    @Test
    void aQuestionWithNoMatchingSectionIsStillListed() {
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task(),
            query -> "No documentation matched \"" + query + "\".");

        toolbox.lookupApi("something nobody wrote about");

        List<String> lines = WorkerLoop.formatLookupEvidence(toolbox.lookupHistory());

        assertThat(lines).containsExactly(
            "1. \"something nobody wrote about\" → (no matching section)");
    }

    /** The same question asked twice proves nothing about the search, so it is not counted. */
    @Test
    void repeatingOneQuestionIsNotEvidenceThatTheSearchIsBroken() {
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task(),
            query -> "Documentation for \"" + query + "\":\n\nThe same page every time.\n");

        for (int i = 0; i < 5; i++) {
            toolbox.lookupApi("ClickEvent");
        }

        assertThat(toolbox.documentationLookupIsNotDiscriminating()).isFalse();
    }

    /** The steer arrives in the tool result the worker is reading, and names its documentation. */
    @Test
    void aWorkerThatOnlyLooksIsToldWhatItHasAndToStartWriting() throws Exception {
        Files.writeString(worktree.resolve("a.txt"), "hello");
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task());
        toolbox.setReferenceHint("Your knowledge brief lists real documentation for this "
            + "project, including demo/docs/UI_COMPONENTS.md.");

        String result = "";
        for (int i = 0; i < 8; i++) {
            result = toolbox.read("a.txt");
        }

        assertThat(result)
            .contains("[orchestrator]")
            .contains("have not changed a single file")
            .contains("UI_COMPONENTS.md")
            .contains("Do not work APIs out from compiled classes")
            .contains("Start writing now");
        assertThat(toolbox.investigationToolCalls()).isEqualTo(8);
        assertThat(toolbox.hasWritten()).isFalse();
    }

    /** With no documentation anywhere, the advice is different — and it is still bounded. */
    @Test
    void withNoDocumentationTheWorkerIsToldToWriteAndLetTheBuildCorrectIt() throws Exception {
        Files.writeString(worktree.resolve("a.txt"), "hello");
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task());
        toolbox.setReferenceHint("");

        String result = "";
        for (int i = 0; i < 8; i++) {
            result = toolbox.read("a.txt");
        }

        assertThat(result)
            .contains("no reference documentation")
            .contains("let the build tell you what is wrong")
            .doesNotContain("lookup_api with a class name");
    }

    /**
     * Writing resets the count — and only writing does.
     *
     * <p>Reading a few files after a write is normal work and is never steered. Twenty-four of them
     * is not: the worker has stopped changing anything, which is the state that cost a whole
     * budget. So the count resumes from zero after every write and is steered, then stopped, on
     * exactly the thresholds that apply before the first one.
     */
    @Test
    void aSuccessfulWriteResetsTheCountAndTheGuardResumesAfterIt() throws Exception {
        Files.writeString(worktree.resolve("a.txt"), "hello");
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task());

        for (int i = 0; i < 5; i++) {
            toolbox.read("a.txt");
        }
        assertThat(toolbox.writeFile("src/New.java", "class New {}")).startsWith("wrote ");
        assertThat(toolbox.investigationToolCalls()).isZero();
        assertThat(toolbox.hasWritten()).isTrue();

        // Normal work after a write: not steered.
        for (int i = 0; i < 7; i++) {
            assertThat(toolbox.read("a.txt")).doesNotContain("[orchestrator]");
        }
        // The eighth without another change is steered again, which it never used to be.
        assertThat(toolbox.read("a.txt")).contains("[orchestrator]");
    }

    /** The second steer says what happens if it keeps going, in turns it can count. */
    @Test
    void theSecondSteerSaysWhatHappensIfItKeepsGoing() throws Exception {
        Files.writeString(worktree.resolve("a.txt"), "hello");
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task());

        String result = "";
        for (int i = 0; i < 16; i++) {
            result = toolbox.read("a.txt");
        }

        assertThat(result)
            .contains("last warning")
            .contains("24 tool calls without a change")
            .contains("stopped and thrown away");
    }

    /** The steer is drained once, for the run transcript, and does not repeat. */
    @Test
    void theSteerIsReportedOnceToTheTranscript() throws Exception {
        Files.writeString(worktree.resolve("a.txt"), "hello");
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task());

        assertThat(toolbox.drainSteer()).isNull();
        for (int i = 0; i < 8; i++) {
            toolbox.read("a.txt");
        }
        assertThat(toolbox.drainSteer()).contains("[orchestrator]");
        assertThat(toolbox.drainSteer()).isNull();
    }

    /** The hint is read back out of the brief, so there is one source of truth for what exists. */
    @Test
    void theHintNamesTheDocumentsTheBriefListed() {
        PromptBundle bundle = PromptBundle.builder()
            .systemRole("worker")
            .knowledgeBrief("""
                ### Reference documentation you can read (call lookup_api …)
                - demo/docs/UI_COMPONENTS.md — DemoStack UI components
                - demo/docs/ROUTING.md — Routing
                """)
            .build();

        assertThat(WorkerLoop.referenceHint(bundle))
            .contains("demo/docs/UI_COMPONENTS.md")
            .contains("demo/docs/ROUTING.md");

        PromptBundle bare = PromptBundle.builder().systemRole("worker")
            .knowledgeBrief("### Available libraries\n- org.junit:junit 5\n").build();
        assertThat(WorkerLoop.referenceHint(bare)).isEmpty();
    }

    /**
     * The whole point of a group is one shared prefill, so the extra guidance must be the SAME
     * bytes for every worker. It is static text in the shared segments; only the persona differs,
     * and that is appended strictly after.
     */
    @Test
    void theAntiDecompilingRuleIsInTheSharedPrefixAndDoesNotDivergePerWorker() {
        PromptBundle bundle = PromptBundle.builder()
            .systemRole("worker")
            .workflowRules("call lookup_api FIRST. Do NOT unpack or decompile jars")
            .knowledgeBrief("- demo/docs/UI_COMPONENTS.md — components\n")
            .build();

        assertThat(bundle.sharedText()).contains("Do NOT unpack or decompile jars");
        String hash = bundle.prefixHash();
        for (String persona : List.of("minimal-diff", "defensive-edges", "test-first")) {
            assertThat(bundle.forWorker(persona, null)).startsWith(bundle.sharedText());
            assertThat(bundle.prefixHash()).isEqualTo(hash);
        }
    }

    /**
     * The read-pause is not a surprise mid-run: every worker's own shared prefix says, once and
     * plainly, that it is coming — the same text SwarmDispatcher hands to every worker of a task,
     * so it costs nothing against the prefill cache.
     */
    @Test
    void theSharedPrefixSaysReadingWillPauseAfterSixteenReads() {
        Task task = new Task(UUID.randomUUID(), 1, "t", "i", Set.of("src"), Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);

        PromptBundle bundle = SwarmDispatcher.buildBundle(task, null, "", null, List.of());

        assertThat(bundle.sharedText())
            .contains("After sixteen reads without a write, reading pauses until you write");
    }
}
