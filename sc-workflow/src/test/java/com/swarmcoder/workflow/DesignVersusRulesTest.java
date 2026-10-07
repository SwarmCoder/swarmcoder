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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.ConstraintBrief;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.ReviewVerdict;
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
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DESIGN_REVIEW, addendum (2026-09-04, harness run 19): a design that conflicts with a stated rule
 * is sent back to the architect BEFORE PLAN ever sees it.
 *
 * <p><b>What this closes.</b> A design that decided to keep ratings in browser {@code localStorage}
 * against a rule requiring server-side EclipseStore persistence was reviewed by the rubric
 * ({@link DesignReviewerClient#review} — completeness, testability, partitionability, none of which
 * reads a rule), recorded no objection to the decision itself, and reached PLAN unchanged. PLAN's
 * own rules check ({@link DesignReviewerClient#reviewPlan}, {@link ForbiddenTechGuard}) then
 * rejected three planner attempts in a row for a defect none of them could fix: the design that fed
 * every attempt still said to build the forbidden thing. These tests prove the design is now
 * checked against the rules too ({@link DesignReviewerClient#reviewDesign}), revised with the rule's
 * own wording fed back, and parked — never silently carried forward — if it still conflicts after
 * {@link GreenfieldWorkflow#MAX_DESIGN_RULE_REVISIONS} revisions.
 *
 * <p>No paid model call anywhere: every LLM role is pointed at {@link ScriptedLlm}.
 */
class DesignVersusRulesTest {

    @TempDir
    Path storeDir;

    private static final String RULES_BRIEF = ConstraintBrief.render(List.of(
        rule("Storage is an object graph",
            "Persistence uses EclipseStore through zerozstack-store-eclipsestore. The server "
                + "keeps the live Java objects in memory and writes that object graph to disk. "
                + "Nothing is ever persisted in the browser.")));

    private static final String DESIGN_WITH_LOCAL_STORAGE_JSON = """
        {"decisions":[{"decision":"Keep ratings in browser localStorage",
          "rationale":"avoids a server round trip"}],
         "contracts":[{"name":"BookApi","description":"list books","signature":"List<Book> list()"}],
         "risks":[],"missingRequirements":[]}
        """;

    private static final String CLEAN_DESIGN_JSON = """
        {"decisions":[{"decision":"Persist ratings through the server-side EclipseStore object graph",
          "rationale":"the project's rule requires it"}],
         "contracts":[{"name":"BookApi","description":"list books","signature":"List<Book> list()"}],
         "risks":[],"missingRequirements":[]}
        """;

    // -------------------------------------------------------------- 1. one revision, then proceeds

    @Test
    void aDesignConflictingWithARuleIsRevisedOnceThenProceeds() throws Exception {
        AtomicInteger ruleReviewCalls = new AtomicInteger();
        AtomicInteger rubricCalls = new AtomicInteger();
        AtomicInteger reviseCalls = new AtomicInteger();
        List<String> reviseConversations = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("software architect") && !conversation.contains("revising a design")) {
                    return DESIGN_WITH_LOCAL_STORAGE_JSON;
                }
                if (conversation.contains("checking a DESIGN against this project's standing rules")) {
                    int n = ruleReviewCalls.incrementAndGet();
                    return n == 1
                        ? """
                            {"approved":false,"objections":["decision 'Keep ratings in browser \
                          localStorage' conflicts with rule 'Storage is an object graph': it stores \
                          ratings in the browser instead of the server-side EclipseStore object graph"]}
                            """
                        : "{\"approved\":true,\"objections\":[]}";
                }
                if (conversation.contains("Critique the design against this rubric")) {
                    rubricCalls.incrementAndGet();
                    return "{\"approved\":true,\"objections\":[]}";
                }
                if (conversation.contains("revising a design")) {
                    reviseCalls.incrementAndGet();
                    reviseConversations.add(conversation);
                    return CLEAN_DESIGN_JSON;
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Fixture fx = fixture(store);
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime,
                run -> {
                    throw new AssertionError("a swarm was dispatched — PLAN never accepted a plan "
                        + "in this test, on purpose, so DESIGN_REVIEW's own behaviour is all this "
                        + "checks");
                },
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);
            engine.setProjectRules(() -> RULES_BRIEF);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.DESIGN,
                fx.projectId, fx.storyId, null, null, null, Instant.now(),
                new RunReport(runId, "keep books and ratings across a restart"));
            engine.advance(run);

            awaitDesignReviewed(store, runId);
            Run afterDesign = store.root().runs.get(runId);
            DesignDocument design = store.root().designs.get(afterDesign.designId());

            assertThat(ruleReviewCalls.get())
                .as("checked once, found the conflict, revised, checked again, clean")
                .isEqualTo(2);
            assertThat(reviseCalls.get())
                .as("exactly one revision fixed it")
                .isEqualTo(1);
            assertThat(reviseConversations).hasSize(1);
            assertThat(reviseConversations.get(0))
                .as("the architect is told which rule, and its own wording — not just its name")
                .contains("conflicts with rule 'Storage is an object graph'")
                .contains("zerozstack-store-eclipsestore");
            assertThat(design.decisions())
                .as("the revised, clean design is what got persisted")
                .noneMatch(d -> d.decision() != null && d.decision().contains("localStorage"));
            assertThat(design.review()).isEqualTo(ReviewVerdict.APPROVED);
            assertThat(afterDesign.state())
                .as("a clean design proceeds to PLAN")
                .isEqualTo(RunState.PLAN);
        }
    }

    // --------------------------------------------------------- 2. still conflicting after two: park

    @Test
    void aDesignStillConflictingAfterTheLimitParksAtDesignReview() throws Exception {
        AtomicInteger ruleReviewCalls = new AtomicInteger();
        AtomicInteger reviseCalls = new AtomicInteger();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("software architect") && !conversation.contains("revising a design")) {
                    return DESIGN_WITH_LOCAL_STORAGE_JSON;
                }
                if (conversation.contains("checking a DESIGN against this project's standing rules")) {
                    ruleReviewCalls.incrementAndGet();
                    return """
                        {"approved":false,"objections":["decision 'Keep ratings in browser \
                        localStorage' conflicts with rule 'Storage is an object graph': it stores \
                        ratings in the browser instead of the server-side EclipseStore object graph"]}
                        """;
                }
                if (conversation.contains("Critique the design against this rubric")) {
                    return "{\"approved\":true,\"objections\":[]}";
                }
                if (conversation.contains("revising a design")) {
                    reviseCalls.incrementAndGet();
                    // Never actually fixes it — the architect keeps producing the same conflict.
                    return DESIGN_WITH_LOCAL_STORAGE_JSON;
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Fixture fx = fixture(store);
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime,
                run -> {
                    throw new AssertionError("a swarm was dispatched — a still-conflicting design "
                        + "must never reach PLAN, let alone EXECUTING");
                },
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);
            engine.setProjectRules(() -> RULES_BRIEF);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.DESIGN,
                fx.projectId, fx.storyId, null, null, null, Instant.now(),
                new RunReport(runId, "keep books and ratings across a restart"));
            engine.advance(run);

            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);

            assertThat(reviseCalls.get())
                .as("revised up to the limit, never more")
                .isEqualTo(GreenfieldWorkflow.MAX_DESIGN_RULE_REVISIONS);
            assertThat(ruleReviewCalls.get())
                .as("checked once, then once again after each revision")
                .isEqualTo(GreenfieldWorkflow.MAX_DESIGN_RULE_REVISIONS + 1);
            assertThat(parked.state())
                .as("a park does not move the run on — it stays resumable at DESIGN_REVIEW")
                .isEqualTo(RunState.DESIGN_REVIEW);
            assertThat(parked.parkReason())
                .contains("conflicts with rule 'Storage is an object graph'")
                .contains(String.valueOf(GreenfieldWorkflow.MAX_DESIGN_RULE_REVISIONS));
        }
    }

    // --------------------------------------------------- 3. a completeness-only objection: unchanged

    @Test
    void aCompletenessOnlyObjectionStillGetsOneRevisionAndProceedsAsBefore() throws Exception {
        AtomicInteger rubricCalls = new AtomicInteger();
        AtomicInteger ruleReviewCalls = new AtomicInteger();
        AtomicInteger reviseCalls = new AtomicInteger();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("software architect") && !conversation.contains("revising a design")) {
                    return CLEAN_DESIGN_JSON;
                }
                if (conversation.contains("Critique the design against this rubric")) {
                    return rubricCalls.incrementAndGet() == 1
                        ? "{\"approved\":false,\"objections\":[\"no overflow contract\"]}"
                        : "{\"approved\":true,\"objections\":[]}";
                }
                if (conversation.contains("checking a DESIGN against this project's standing rules")) {
                    ruleReviewCalls.incrementAndGet();
                    return "{\"approved\":true,\"objections\":[]}"; // clean against the rules, always
                }
                if (conversation.contains("revising a design")) {
                    reviseCalls.incrementAndGet();
                    return CLEAN_DESIGN_JSON;
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Fixture fx = fixture(store);
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime,
                run -> {
                    throw new AssertionError("a swarm was dispatched — PLAN never accepted a plan "
                        + "in this test, on purpose");
                },
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);
            engine.setProjectRules(() -> RULES_BRIEF);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.DESIGN,
                fx.projectId, fx.storyId, null, null, null, Instant.now(),
                new RunReport(runId, "keep books and ratings across a restart"));
            engine.advance(run);

            awaitDesignReviewed(store, runId);
            Run afterDesign = store.root().runs.get(runId);
            DesignDocument design = store.root().designs.get(afterDesign.designId());

            assertThat(rubricCalls.get())
                .as("one rubric revision loop ran, exactly as before this addendum")
                .isEqualTo(2);
            assertThat(reviseCalls.get()).isEqualTo(1);
            assertThat(ruleReviewCalls.get())
                .as("the design was also checked against the rules, and found clean")
                .isEqualTo(1);
            assertThat(design.review()).isEqualTo(ReviewVerdict.APPROVED);
            assertThat(afterDesign.state())
                .as("DESIGN_REVIEW itself never parked over a completeness-only objection — it "
                    + "moved the run on to PLAN, exactly as before this addendum (whatever PLAN "
                    + "goes on to do next, unscripted here, is not this test's concern)")
                .isEqualTo(RunState.PLAN);
        }
    }

    // ------------------------------------------------------------------------------- fixtures

    private record Fixture(UUID projectId, UUID storyId) {}

    /** A one-requirement, one-criterion BRD and story — the same shape {@code PlanVersusRulesTest}
     *  uses, so a scoped design (not the unscoped goal-only path) is what these tests exercise. */
    private static Fixture fixture(ArtifactStore store) {
        BrdRequirement r1 = requirement("R1", "Preserve state",
            "Books and ratings survive a browser restart",
            "books and ratings are still present after the browser is closed and reopened");
        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Bookshelf",
            new ArrayList<>(List.of(r1)), new ArrayList<>(), Instant.now(), Instant.now());
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Books and ratings survive a restart", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(r1.criteria().get(0).id())), null, 0, StoryOrigin.BACKLOG,
            null, null, "human", new ArrayList<>(), null, null, null, null, Instant.now(),
            Instant.now());
        store.saveBrd(brd);
        store.saveStory(story);
        return new Fixture(brd.projectId(), story.id());
    }

    private static BrdRequirement requirement(String handle, String title, String statement,
                                              String... criteriaText) {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), handle, title, statement,
            Priority.HIGH, RequirementStatus.ACTIVE, null);
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (String text : criteriaText) {
            AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), text,
                "swarm.accept." + handle + "C" + (criteria.size() + 1) + "AcceptTest#proves");
            criterion.setStatus(CriterionStatus.ACCEPTED);
            criteria.add(criterion);
        }
        requirement.setCriteria(criteria);
        return requirement;
    }

    private static LearnedGuideline rule(String title, String body) {
        LearnedGuideline g = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            "rule", body, new Provenance("stated", null, "bookshelf-tech-requirements.md"), 1.0,
            Instant.now(), 0, GuidelineStatus.ACTIVE, UUID.randomUUID(), null, 0);
        g.setTitle(title);
        return g;
    }

    /** Reached DESIGN_REVIEW's own outcome — either it parked there, or it moved past it. */
    private static void awaitDesignReviewed(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.designId() != null
                    && (run.parkedAt() != null || run.state() != RunState.DESIGN_REVIEW)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " never got past DESIGN_REVIEW");
    }

    private static void awaitParked(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.parkedAt() != null) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " never parked");
    }
}
