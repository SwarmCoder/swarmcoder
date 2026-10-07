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
package com.swarmcoder.store;

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.ChatMessage;
import com.swarmcoder.domain.ChatSession;
import com.swarmcoder.domain.CriterionVerification;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowProposalKind;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.FlowQuestionKind;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowKind;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.Iteration;
import com.swarmcoder.domain.IterationState;
import com.swarmcoder.domain.KnowledgeBrief;
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.VerificationResult;
import com.swarmcoder.domain.WorkflowKind;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a project clears every per-project store root for that id — and leaves a SECOND
 * project's records completely intact.
 *
 * <p>The second assertion is the one that matters. A delete that under-reaches leaves ghost rows
 * keyed to a dead project id, which is a slow leak; a delete that OVER-reaches silently destroys
 * another project's requirements, backlog and history, which is unrecoverable. So every root is
 * asserted with {@code containsOnlyKeys(keeper…)} rather than merely "the doomed key is gone".
 *
 * <p>Nothing here touches a working tree, and neither does the code under test: {@link ArtifactStore}
 * has no filesystem reach beyond its own EclipseStore directory, and a project's path is only ever a
 * {@code String} on the {@code Project} record.
 */
class ProjectDeleteStoreTest {

    @Test
    void deleteClearsEveryPerProjectRootAndLeavesTheOtherProjectUntouched(@TempDir Path dir)
            throws Exception {
        Seed doomed;
        Seed keeper;

        try (ArtifactStore store = new ArtifactStore(dir)) {
            doomed = seed(store, "doomed", "C:/work/doomed");
            keeper = seed(store, "keeper", "C:/work/keeper");

            // The confirm step's numbers come from here, and counting must not delete anything.
            ProjectDeletionSummary preview = store.projectContents(doomed.projectId);
            assertThat(preview.requirements()).isEqualTo(1);
            assertThat(preview.stories()).isEqualTo(1);
            assertThat(preview.chats()).isEqualTo(1);
            assertThat(preview.runs()).isEqualTo(1);
            assertThat(preview.sourceDocuments()).isEqualTo(1);
            assertThat(store.getProject(doomed.projectId)).isNotNull();

            ProjectDeletionSummary removed = store.deleteProject(doomed.projectId);
            assertThat(removed.count(ProjectDeletionSummary.PROJECTS)).isEqualTo(1);
            // Every root that held something reports it, so the INFO log is a real receipt.
            assertThat(removed.counts()).containsKeys(
                ProjectDeletionSummary.RUNS, ProjectDeletionSummary.DESIGNS,
                ProjectDeletionSummary.TASK_GRAPHS, ProjectDeletionSummary.TASKS,
                ProjectDeletionSummary.KNOWLEDGE_BRIEFS, ProjectDeletionSummary.DECISIONS,
                ProjectDeletionSummary.CANDIDATE_ARCHIVES, ProjectDeletionSummary.AGENT_SESSIONS,
                ProjectDeletionSummary.CHATS, ProjectDeletionSummary.CHAT_MESSAGES,
                ProjectDeletionSummary.KNOWLEDGE_DOCS, ProjectDeletionSummary.BRDS,
                ProjectDeletionSummary.BRD_REVISIONS, ProjectDeletionSummary.REQUIREMENTS,
                ProjectDeletionSummary.STORIES, ProjectDeletionSummary.ITERATIONS,
                ProjectDeletionSummary.SOURCE_DOCUMENTS, ProjectDeletionSummary.CHANGE_EVENTS,
                ProjectDeletionSummary.CRITERION_VERIFICATIONS, ProjectDeletionSummary.GUIDED_FLOWS,
                ProjectDeletionSummary.FLOW_QUESTIONS, ProjectDeletionSummary.FLOW_PROPOSALS,
                ProjectDeletionSummary.RULES);

            assertOnlyKeeperRemains(store, keeper);
            assertKeeperIsIntact(store, keeper);
        }

        // The removals are durable, not just in-memory: reopen and check the same two things.
        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            assertThat(reopened.getProject(doomed.projectId)).isNull();
            assertOnlyKeeperRemains(reopened, keeper);
            assertKeeperIsIntact(reopened, keeper);
        }
    }

    /**
     * Rules left behind by projects deleted BEFORE rules were deleted with them are swept.
     *
     * <p>Until 2026-09-02 a rule was a file in the checkout and the store row only indexed it, so
     * deleting the project deleted none of its rules; the operator's store carries rows from four
     * such deletions. Rows with no owner at all (from before rules had one) are equally
     * unreachable and go the same way. A rule of a living project is never touched.
     */
    @Test
    void rulesOfProjectsDeletedEarlierAreSwept(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Seed keeper = seed(store, "keeper", "C:/work/keeper");
            UUID deadProject = UUID.randomUUID();
            UUID orphan = UUID.randomUUID();
            UUID ownerless = UUID.randomUUID();
            store.append(() -> {
                store.root().guidelines.put(orphan, rule(orphan, deadProject, "orphan"));
                store.root().guidelines.put(ownerless, rule(ownerless, null, "ownerless"));
                return null;
            }).get();

            assertThat(store.removeRulesOfDeletedProjects()).isEqualTo(2);
            assertThat(store.root().guidelines).containsOnlyKeys(keeper.ruleId);
            assertThat(store.removeRulesOfDeletedProjects())
                .describedAs("a second sweep finds nothing — the keeper's rule is not an orphan")
                .isZero();
        }
    }

    private static LearnedGuideline rule(UUID id, UUID projectId, String slug) {
        return new LearnedGuideline(id, 1, GuidelineScope.PROJECT, slug, "Rule " + slug,
            new Provenance("human", null), 1.0, Instant.now(), 0, GuidelineStatus.ACTIVE,
            projectId, null, 0);
    }

    /**
     * The "reopen where I was" bookmark does not outlive the project it points at.
     *
     * <p>It survived a deletion, leaving a durable pointer at a record that no longer exists. It is
     * also what the Console offers as the pre-selected project when it asks which project to work
     * on, so a stale one is a suggestion to open something that is gone.
     */
    @Test
    void deletingTheRememberedProjectForgetsIt(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Seed doomed = seed(store, "doomed", "C:/work/doomed");
            Seed keeper = seed(store, "keeper", "C:/work/keeper");

            store.setLastProject(keeper.projectId);
            store.deleteProject(doomed.projectId);
            assertThat(store.lastProjectId())
                .describedAs("deleting a different project leaves the bookmark alone")
                .isEqualTo(keeper.projectId);

            store.deleteProject(keeper.projectId);
            assertThat(store.lastProjectId())
                .describedAs("deleting the remembered project forgets it, rather than leaving a "
                    + "durable pointer at a record that is gone")
                .isNull();
        }
    }

    @Test
    void deletingAnUnknownProjectRemovesNothing(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Seed keeper = seed(store, "keeper", "C:/work/keeper");
            assertThat(store.deleteProject(UUID.randomUUID()).isEmpty()).isTrue();
            assertThat(store.deleteProject(null).isEmpty()).isTrue();
            assertOnlyKeeperRemains(store, keeper);
            assertKeeperIsIntact(store, keeper);
        }
    }

    /**
     * Every per-project root holds EXACTLY the keeper's entries: nothing of the deleted project
     * survived, and nothing of the surviving project was taken with it.
     */
    private static void assertOnlyKeeperRemains(ArtifactStore store, Seed keeper) {
        StoreRoot root = store.root();
        assertThat(root.projects).containsOnlyKeys(keeper.projectId);
        assertThat(root.runs).containsOnlyKeys(keeper.runId);
        assertThat(root.designs).containsOnlyKeys(keeper.designId);
        assertThat(root.taskGraphs).containsOnlyKeys(keeper.graphId);
        assertThat(root.briefs).containsOnlyKeys(keeper.briefId);
        assertThat(root.decisions).containsOnlyKeys(keeper.decisionId);
        assertThat(root.candidateArchives).containsOnlyKeys(keeper.candidateId);
        assertThat(root.agentSessions()).containsOnlyKeys(keeper.sessionId);
        assertThat(root.chats()).containsOnlyKeys(keeper.chatId);
        assertThat(root.chatMessages()).containsOnlyKeys(keeper.messageId);
        assertThat(root.knowledgeDocs()).containsOnlyKeys(keeper.knowledgeDocId);
        assertThat(root.brds()).containsOnlyKeys(keeper.projectId);
        assertThat(root.brdRevisions()).containsOnlyKeys(keeper.projectId);
        assertThat(root.stories()).containsOnlyKeys(keeper.storyId);
        assertThat(root.iterations()).containsOnlyKeys(keeper.iterationId);
        assertThat(root.tasks()).containsOnlyKeys(keeper.taskId);
        assertThat(root.sourceDocuments()).containsOnlyKeys(keeper.documentId);
        assertThat(root.changeEvents()).containsOnlyKeys(keeper.projectId);
        assertThat(root.criterionVerifications()).containsOnlyKeys(keeper.criterionId);
        assertThat(root.guidedFlows()).containsOnlyKeys(keeper.flowId);
        assertThat(root.flowQuestions()).containsOnlyKeys(keeper.flowId);
        assertThat(root.flowProposals()).containsOnlyKeys(keeper.flowId);
        assertThat(root.guidelines).containsOnlyKeys(keeper.ruleId);
    }

    /** The surviving project still reads back through the public API, whole. */
    private static void assertKeeperIsIntact(ArtifactStore store, Seed keeper) {
        assertThat(store.listProjects()).extracting(p -> p.id()).containsExactly(keeper.projectId);
        Brd brd = store.getBrd(keeper.projectId);
        assertThat(brd).isNotNull();
        assertThat(brd.requirements()).hasSize(1);
        assertThat(brd.requirements().get(0).criteria()).extracting(AcceptanceCriterion::id)
            .containsExactly(keeper.criterionId);
        assertThat(store.listBrdRevisions(keeper.projectId)).hasSize(1);
        assertThat(store.listStories(keeper.projectId)).extracting(Story::id)
            .containsExactly(keeper.storyId);
        assertThat(store.listIterations(keeper.projectId)).hasSize(1);
        assertThat(store.listChats(keeper.projectId)).hasSize(1);
        assertThat(store.chatTranscript(keeper.chatId)).hasSize(1);
        assertThat(store.listKnowledgeDocs(keeper.projectId)).hasSize(1);
        assertThat(store.listSourceDocuments(keeper.projectId)).hasSize(1);
        assertThat(store.listChangeEvents(keeper.projectId)).hasSize(1);
        assertThat(store.listVerifications(keeper.criterionId)).hasSize(1);
        assertThat(store.listGuidedFlows(keeper.projectId)).hasSize(1);
        assertThat(store.listFlowQuestions(keeper.flowId)).hasSize(1);
        assertThat(store.listFlowProposals(keeper.flowId)).hasSize(1);
        assertThat(store.getTask(keeper.taskId)).isNotNull();
        assertThat(store.storyTasks(keeper.storyId)).hasSize(1);
    }

    /** One row in every per-project root, so the delete has something to miss. */
    private static Seed seed(ArtifactStore store, String name, String path) throws Exception {
        Seed s = new Seed();
        s.projectId = store.ensureProject(name, path, List.of()).id();

        s.criterionId = UUID.randomUUID();
        Brd brd = store.ensureBrd(s.projectId);
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R1", "Login",
            "email + password", Priority.HIGH, RequirementStatus.ACTIVE, "Auth");
        requirement.setCriteria(new ArrayList<>(List.of(
            new AcceptanceCriterion(s.criterionId, "a known user can log in", "LoginTest#ok"))));
        brd.setRequirements(new ArrayList<>(List.of(requirement)));
        store.saveBrd(brd, "human", "added R1 (Login)");

        store.recordVerification(new CriterionVerification(UUID.randomUUID(), s.criterionId,
            requirement.id(), null, null, null, "a1b2c3d", VerificationResult.PASSING,
            "LoginTest#ok", Instant.now()));

        s.iterationId = UUID.randomUUID();
        store.saveIteration(new Iteration(s.iterationId, s.projectId, "MVP", "prove login", 1,
            IterationState.ACTIVE, Instant.now(), null));

        s.runId = UUID.randomUUID();
        s.storyId = UUID.randomUUID();
        store.saveStory(new Story(s.storyId, s.projectId, "S1", StoryKind.DELIVERY, "Login",
            null, StoryState.READY, new ArrayList<>(List.of(requirement.id())),
            new ArrayList<>(List.of(s.criterionId)), s.iterationId, 1, StoryOrigin.BACKLOG, null,
            null, "human", new ArrayList<>(List.of(s.runId)), null, null, null, null,
            Instant.now(), Instant.now()));

        s.chatId = UUID.randomUUID();
        store.saveChat(new ChatSession(s.chatId, s.projectId, name + " chat", Instant.now(),
            null, false));
        s.messageId = UUID.randomUUID();
        store.appendChatMessage(new ChatMessage(s.messageId, s.chatId, 0, "user", "text",
            "hello", null, null, Instant.now(), 3));

        s.knowledgeDocId = UUID.randomUUID();
        store.saveKnowledgeDoc(new KnowledgeDoc(s.knowledgeDocId, s.projectId, name + "-doc",
            "How auth works", "body", "ACTIVE", "human", Instant.now(), Instant.now()));

        s.documentId = UUID.randomUUID();
        store.saveSourceDocument(new SourceDocument(s.documentId, s.projectId, name + "-spec.md",
            "text/markdown", "sha-" + name, "the spec", "passthrough", 8, Instant.now()));

        store.recordChange(s.projectId, "human", ChangeEntityType.STORY, s.storyId,
            ChangeKind.CREATED, "created S1 (Login)");

        // A rule of the project. Rules were files in the checkout until 2026-09-02 and survived
        // every deletion; they are store objects now and go with the project.
        s.ruleId = UUID.randomUUID();
        store.append(() -> {
            store.root().guidelines.put(s.ruleId, rule(s.ruleId, s.projectId, name + "-rule"));
            return null;
        }).get();

        s.flowId = UUID.randomUUID();
        store.saveGuidedFlow(new GuidedFlow(s.flowId, s.projectId,
            GuidedFlowKind.REQUIREMENTS_INTAKE, GuidedFlowState.AWAITING_ANSWERS, 1, 3,
            "asking", null, new ArrayList<>(), Instant.now(), Instant.now()));
        store.saveFlowQuestions(s.flowId, List.of(new FlowQuestion(UUID.randomUUID(), s.flowId,
            "Auth", "Which providers?", null, null, null, FlowQuestionKind.TEXT,
            new ArrayList<>(), null, null, false)));
        store.saveFlowProposals(s.flowId, List.of(new FlowProposal(UUID.randomUUID(), s.flowId,
            FlowProposalKind.ADD, "R2", "OAuth", null, "Google sign-in", "asked for", null, false)));

        // A run and everything it owns: design, task graph, task, brief, decision, the bulky
        // candidate archive and the agent session trace.
        s.designId = UUID.randomUUID();
        s.graphId = UUID.randomUUID();
        s.taskId = UUID.randomUUID();
        s.briefId = UUID.randomUUID();
        s.decisionId = UUID.randomUUID();
        s.candidateId = UUID.randomUUID();
        s.sessionId = UUID.randomUUID();

        Task task = new Task(s.taskId, 1, "Add LoginController", "…", new HashSet<>(),
            new HashSet<>(), new ArrayList<>(), "src/test/java/swarm", s.briefId, null, null,
            TaskState.SELECTED);
        task.setStoryId(s.storyId);
        task.setSelectedCandidateId(s.candidateId);
        TaskGraph graph = new TaskGraph(s.graphId, 1, s.designId,
            new ArrayList<>(List.of(task)), new ArrayList<>());
        Run run = new Run(s.runId, WorkflowKind.GREENFIELD, RunState.EXECUTING, s.projectId,
            s.storyId, s.designId, s.graphId, null, Instant.now(), null);

        store.append(() -> {
            store.root().runs.put(s.runId, run);
            store.root().designs.put(s.designId, new DesignDocument(s.designId, 1, "login",
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                null, Instant.now()));
            store.root().taskGraphs.put(s.graphId, graph);
            store.root().briefs.put(s.briefId, new KnowledgeBrief(s.briefId, 1, s.taskId,
                new ArrayList<>(), new ArrayList<>(), "# brief"));
            store.root().decisions.put(s.decisionId, new Decision(s.decisionId, s.runId,
                DecisionKind.APPROVAL, "approve?", DecisionState.PENDING, null, Instant.now()));
            store.root().candidateArchives.put(s.candidateId, Lazy.Reference(
                new CandidateSolution(s.candidateId, s.taskId, 0, "swarm/w0", null, "diff",
                    null, null, null, CandidateState.SELECTED, null)));
            store.root().agentSessions().put(s.sessionId, Lazy.Reference(
                new AgentSessionRecord(s.sessionId, s.runId, s.taskId, s.candidateId, 0, "coder",
                    "local-coder", 0.2, Instant.now(), Instant.now(), "ok", null, 2, 100,
                    new ArrayList<>())));
            return null;
        }).get();
        store.indexTasks(graph.tasks());
        return s;
    }

    /** The ids of one seeded project — every root gets exactly one row, so misses are visible. */
    private static final class Seed {
        UUID projectId;
        UUID criterionId;
        UUID iterationId;
        UUID storyId;
        UUID chatId;
        UUID messageId;
        UUID knowledgeDocId;
        UUID documentId;
        UUID flowId;
        UUID runId;
        UUID designId;
        UUID graphId;
        UUID taskId;
        UUID briefId;
        UUID decisionId;
        UUID candidateId;
        UUID sessionId;
        UUID ruleId;
    }
}
