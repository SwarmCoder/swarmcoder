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

import com.swarmcoder.domain.AuthoredTest;
import com.swarmcoder.domain.AuthoredTests;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.CompileFailureCause;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.knowledge.ProjectRules;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.verify.Verdicts;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything a run writes about itself while it is being driven has to still be there after the
 * process is restarted.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Two different ways of losing a write were found on 2026-09-03, and this test walks a run
 * through both of them.
 *
 * <p><b>One.</b> Most of what a run records about itself is set on the SAME {@code Run} object the
 * store already holds — parked, unparked, stamped, given the commit its finished waves are on.
 * EclipseStore's default storer stops at an instance it already knows, so storing the map that
 * contains the run does not reach any of it: the field is right in memory for the rest of the
 * process and simply absent after a restart. {@link ArtifactStore#updateRun} is the one helper that
 * stores the run object itself, and every one of those sites now goes through it.
 *
 * <p><b>Two.</b> Closing the store used to abandon whatever was still queued to be written, which
 * is why the loss looked intermittent rather than constant. That is pinned separately, in
 * {@code QueuedWritesSurviveCloseTest} in the sc-store module.
 *
 * <p>The four fields added on 2026-09-03 — the commit a run's finished waves are on, what the test
 * author wrote for a task and which files it claims, the document sentence a rule was drawn from,
 * and why a candidate would not compile — are each asserted here after a restart, because a field
 * added the day the fault was found is exactly the field most likely to have been given the wrong
 * kind of write.
 */
class WhatARunWritesDownSurvivesARestartTest {

    @TempDir
    Path storeDir;

    @Test
    void aRunParkedUnparkedAdvancedAndTestAuthoredKeepsAllOfItAcrossARestart() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            RunPersister persister = new RunPersister(store);
            RunHeartbeat heartbeat = new RunHeartbeat(store);

            // --- the run exists ---------------------------------------------------------------
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.PLAN,
                projectId, null, null, null, null, Instant.now(),
                new RunReport(runId, "build the thing"));
            persister.save(run);

            // --- it parks: the same instance the store already holds is mutated in place -------
            run.setParkedAt(Instant.parse("2026-09-03T10:00:00Z"));
            run.setParkReason("The planner could not produce a plan that passes the checks.");
            persister.save(run);

            // --- something takes it up again, and the mark is cleared -------------------------
            run.setParkedAt(null);
            run.setParkReason(null);
            persister.save(run);

            // --- it parks a second time, with a different reason ------------------------------
            run.setParkedAt(Instant.parse("2026-09-03T11:00:00Z"));
            run.setParkReason("The acceptance tests are not in a valid red state.");
            persister.save(run);

            // --- a wave finished, so the next one builds on its merge commit ------------------
            run.setProgressCommit("abc1234def5678");
            store.updateRun(run);

            // --- a worker step stamps the run, from the trace hub, without waiting -------------
            heartbeat.sessionStarted(agentSession(runId));

            // --- the test author wrote files for a task, and says which ones are its own ------
            Task task = new Task(taskId, 1, "Write the parser", "instructions",
                new HashSet<>(List.of("src/main/java/**")), new HashSet<>(), List.of(),
                "src/test/java", null, null, null, TaskState.PENDING);
            store.saveTask(task);
            AuthoredTests authored = AuthoredTests.started(2, Instant.parse("2026-09-03T12:00:00Z"));
            authored.setFiles(List.of("src/test/java/ParserTest.java"));
            authored.setTests(List.of(new AuthoredTest(
                "ParserTest#parsesAnEmptyDocument", "src/test/java/ParserTest.java", "R1:C1",
                "An empty document parses to an empty tree")));
            task.setAuthoredTests(authored);
            task.setAuthoredTestPaths(List.of("src/test/java/ParserTest.java"));
            store.saveTask(task);

            // --- a candidate failed to compile, and why ---------------------------------------
            VerificationReport report = new VerificationReport();
            report.setId(UUID.randomUUID());
            report.setCompiles(false);
            report.setCompileFailure(new CompileFailure(
                CompileFailureCause.TEST_TREE, "src/test/java/ParserTest.java", 12,
                "cannot find symbol: Parser", List.of("src/test/java/ParserTest.java"),
                true, true, true, "The test names a class the candidate never wrote."));
            CandidateSolution candidate = new CandidateSolution(candidateId, taskId, 0,
                "swarm/c0", null, "", report, null, null, CandidateState.FAILED, null);
            store.append(() -> {
                store.root().candidateArchives.put(candidateId, Lazy.Reference(candidate));
                return null;
            }).get();

            // --- a rule was stated, keeping the document's own sentence -----------------------
            store.append(() -> {
                store.root().guidelines.put(ruleId, new LearnedGuideline(ruleId, 1,
                    GuidelineScope.PROJECT, "persistence-eclipsestore",
                    "Persistence via the EclipseStore object graph",
                    new Provenance(ProjectRules.STATED_SOURCE, null, "tech-requirements.md",
                        "Persistence uses EclipseStore through zerozstack-store-eclipsestore"),
                    1.0, Instant.now(), 0, GuidelineStatus.ACTIVE, projectId, null, 0));
                return null;
            }).get();
        }

        // --- restart --------------------------------------------------------------------------
        try (ArtifactStore reopened = new ArtifactStore(storeDir)) {
            Run run = reopened.root().runs.get(runId);
            assertThat(run).as("the run itself").isNotNull();
            assertThat(run.state()).as("the stage it stopped at").isEqualTo(RunState.PLAN);
            assertThat(run.parkedAt())
                .as("when it stopped — the SECOND park, not the first")
                .isEqualTo(Instant.parse("2026-09-03T11:00:00Z"));
            assertThat(run.parkReason())
                .as("why it stopped — the reason, not just the fact")
                .isEqualTo("The acceptance tests are not in a valid red state.");
            assertThat(run.progressCommit())
                .as("the commit the finished waves are on, so a resumed run does not rebuild them")
                .isEqualTo("abc1234def5678");
            assertThat(run.heartbeatAt())
                .as("the stamp a worker step left, written without waiting for it")
                .isNotNull();

            Task task = reopened.root().tasks().get(taskId);
            assertThat(task).as("the task").isNotNull();
            assertThat(task.authoredTestPaths())
                .as("which test files are this task's own")
                .containsExactly("src/test/java/ParserTest.java");
            assertThat(task.authoredTests()).as("what the test author wrote").isNotNull();
            assertThat(task.authoredTests().checksOffered()).isEqualTo(2);
            assertThat(task.authoredTests().tests()).hasSize(1);
            assertThat(task.authoredTests().tests().get(0).testRef())
                .isEqualTo("ParserTest#parsesAnEmptyDocument");
            assertThat(task.authoredTests().tests().get(0).provesRef()).isEqualTo("R1:C1");
            assertThat(task.authoredTests().files())
                .containsExactly("src/test/java/ParserTest.java");

            CandidateSolution candidate =
                (CandidateSolution) Lazy.get(reopened.root().candidateArchives.get(candidateId));
            assertThat(candidate).as("the archived candidate").isNotNull();
            assertThat(candidate.verification().compileFailure())
                .as("why it would not compile").isNotNull();
            assertThat(candidate.verification().compileFailure().cause())
                .isEqualTo(CompileFailureCause.TEST_TREE);
            assertThat(candidate.verification().compileFailure().explanation())
                .isEqualTo("The test names a class the candidate never wrote.");

            LearnedGuideline rule = reopened.root().guidelines.get(ruleId);
            assertThat(rule).as("the stated rule").isNotNull();
            assertThat(rule.provenance()).isNotNull();
            assertThat(rule.provenance().excerpt())
                .as("the document's own sentence the rule was drawn from")
                .isEqualTo("Persistence uses EclipseStore through zerozstack-store-eclipsestore");
        }
    }

    /**
     * Harness run 22, 00:50: a candidate's acceptance test threw, and nothing downstream said which
     * exception, in which method, at which line — because the trace was never carried past a bare
     * pass/fail count. The fix put the filtered stack and the exception's message onto
     * {@code TestFailure.truncatedTrace} and {@code .message()}, both fields that already existed
     * and were already being persisted; this pins that the RICHER content in them is not something
     * EclipseStore's lazy storer drops, and that {@code Verdicts.summarizeTestFailures} — read by
     * the verdict, the judge and the repair prompt alike — renders the reopened data identically to
     * how it rendered it before the store ever saw it.
     */
    @Test
    void anAcceptanceTestsFilteredStackAndMessageSurviveARestart() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        String trace = """
            java.lang.NullPointerException: Cannot invoke "String.length()" because "title" is null
            \tat com.swarmcoder.demo.bookshelf.server.BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)
            \tat swarm.accept.BookRatingTest.assignsRatingToBook(BookRatingTest.java:23)""";
        TestFailure failure = new TestFailure("swarm.accept.BookRatingTest#assignsRatingToBook",
            "Cannot invoke \"String.length()\" because \"title\" is null", trace);
        TestResults acceptance = new TestResults(0, 0, 1, 0, List.of(failure));
        String sentenceBeforeStore = Verdicts.summarizeTestFailures("acceptance", acceptance)
            .oneLineSentence();

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            VerificationReport report = new VerificationReport();
            report.setId(UUID.randomUUID());
            report.setCompiles(true);
            report.setAcceptance(acceptance);
            CandidateSolution candidate = new CandidateSolution(candidateId, taskId, 0,
                "swarm/c0", null, "", report, null, null, CandidateState.FAILED, null);
            store.append(() -> {
                store.root().candidateArchives.put(candidateId, Lazy.Reference(candidate));
                return null;
            }).get();
        }

        try (ArtifactStore reopened = new ArtifactStore(storeDir)) {
            CandidateSolution candidate =
                (CandidateSolution) Lazy.get(reopened.root().candidateArchives.get(candidateId));
            assertThat(candidate).as("the archived candidate").isNotNull();
            TestFailure reopenedFailure = candidate.verification().acceptance().failures().get(0);

            assertThat(reopenedFailure.testId())
                .isEqualTo("swarm.accept.BookRatingTest#assignsRatingToBook");
            assertThat(reopenedFailure.message())
                .as("the exception's message")
                .isEqualTo("Cannot invoke \"String.length()\" because \"title\" is null");
            assertThat(reopenedFailure.truncatedTrace())
                .as("the filtered stack — the candidate's own frame, kept")
                .contains("BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)");

            assertThat(Verdicts.summarizeTestFailures("acceptance",
                    candidate.verification().acceptance()).oneLineSentence())
                .as("the verdict/judge/repair sentence must read the same before and after a restart")
                .isEqualTo(sentenceBeforeStore);
        }
    }

    private static com.swarmcoder.domain.AgentSessionRecord agentSession(UUID runId) {
        com.swarmcoder.domain.AgentSessionRecord record =
            new com.swarmcoder.domain.AgentSessionRecord();
        record.setId(UUID.randomUUID());
        record.setRunId(runId);
        return record;
    }
}
