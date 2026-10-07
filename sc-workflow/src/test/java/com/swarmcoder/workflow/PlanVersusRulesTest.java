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
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.domain.ConstraintBrief;
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
 * PLAN, once {@link TaskGraphValidator} accepts a plan's shape: does the plan itself obey the
 * project's stated rules?
 *
 * <p><b>What this closes.</b> Harness run 10 (2026-09-03) planned Wave 1 to declare {@code
 * zerozstack-store-eclipsestore} — correctly, the rule's own enabler — and Wave 2 to "implement
 * client-side localStorage persistence for books and ratings", against rules that require
 * EclipseStore and forbid REST/JSON among others. The architect was told the rules and ignored
 * them; {@code DESIGN_REVIEW} reviews the design document, never a task; {@link
 * TaskGraphValidator} checks shape, never content. Nothing between PLAN and dispatch asked "does
 * this plan obey the rules?" — so a wave was spent on work the rules forbid before anything ever
 * asked. These tests prove the two checks that now ask it: {@link ForbiddenTechGuard} (free, for a
 * the technologies the rules forbid by name) and {@link DesignReviewerClient#reviewPlan} (one model
 * call, for everything else), wired into PLAN's own retry loop in {@link GreenfieldWorkflow}.
 *
 * <p>No paid model call anywhere: every LLM role is pointed at {@link ScriptedLlm}.
 */
class PlanVersusRulesTest {

    @TempDir
    Path storeDir;

    /** The demo's own two rules, rendered exactly as every worker reads them. */
    private static final String RULES_BRIEF = ConstraintBrief.render(List.of(
        rule("storage-is-an-object-graph", "Storage is an object graph",
            "Persistence uses EclipseStore through zerozstack-store-eclipsestore. The server "
                + "keeps the live Java objects in memory and writes that object graph to disk."),
        rule("explicitly-forbidden", "Explicitly forbidden",
            "Do not use, add, import, or write tests against any of these. None of them is "
                + "present in this project and none of them will be added: Spring or Spring "
                + "Boot; JPA, Hibernate, or any ORM; Flyway, Liquibase, or SQL migrations; a "
                + "relational database of any kind; REST endpoints, HTTP controllers, or JSON; "
                + "JavaScript or TypeScript source files; Vaadin, or any other web UI framework.")));

    // ---------------------------------------------------------------- 1. the reviewer catches it

    /**
     * The exact run-10 shape: a plan whose task instructs browser-side {@code localStorage}. Not
     * among the names the rules forbid, which is all {@link ForbiddenTechGuard} reads — it never claims to catch this — so this
     * is caught by the reviewer, and the objection is fed back to the planner on the very next
     * attempt, through the same re-ask path a shape violation already uses.
     */
    @Test
    void aPlanNamingBrowserLocalStorageIsSentBackWithTheReviewersObjectionAndReAsked()
            throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        AtomicInteger testAuthorCalls = new AtomicInteger();
        List<String> plannerConversations = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("You are a test author")) {
                    testAuthorCalls.incrementAndGet();
                    return "I decline to produce JSON.";
                }
                if (conversation.contains("AI planner")) {
                    plannerCalls.incrementAndGet();
                    plannerConversations.add(conversation);
                    return LOCAL_STORAGE_PLAN_JSON;
                }
                if (conversation.contains("checking a PLAN")) {
                    reviewerCalls.incrementAndGet();
                    return """
                        {"approved":false,"objections":["task 'Add client persistence' conflicts \
                        with rule 'Storage is an object graph': its instructions add localStorage \
                        in the browser, but persistence must go through EclipseStore"]}
                        """;
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
                    throw new AssertionError("a swarm was dispatched — the plan was never clean");
                },
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);
            engine.setProjectRules(() -> RULES_BRIEF);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.PLAN,
                fx.projectId, fx.storyId, null, null, null, Instant.now(),
                new RunReport(runId, "keep books and ratings across a restart"));
            engine.advance(run);

            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);

            assertThat(plannerCalls.get())
                .as("every attempt is a real planner call, never fewer")
                .isEqualTo(GreenfieldWorkflow.MAX_PLAN_ATTEMPTS);
            assertThat(reviewerCalls.get())
                .as("the deterministic guard finds nothing here (localStorage is not on its "
                    + "vocabulary), so the reviewer is asked every attempt")
                .isEqualTo(GreenfieldWorkflow.MAX_PLAN_ATTEMPTS);
            assertThat(testAuthorCalls.get())
                .as("nothing was ever accepted, so the test author must never have been reached")
                .isZero();

            // The re-ask: attempt 2's own request carries attempt 1's rejection, verbatim.
            assertThat(plannerConversations).hasSizeGreaterThanOrEqualTo(2);
            assertThat(plannerConversations.get(1))
                .as("the planner is re-asked WITH the reviewer's objection in the request")
                .contains("rejected")
                .contains("localStorage")
                .contains("Storage is an object graph");

            assertThat(parked.state()).isEqualTo(RunState.PLAN);
            assertThat(parked.parkReason())
                .contains("Last objections:")
                .contains("localStorage");
        }
    }

    // ------------------------------------------------------- 2. the deterministic guard, no model

    /**
     * "Implement a REST endpoint" against a rule that forbids REST: caught by {@link
     * ForbiddenTechGuard} alone, with no reviewer call at all — the cheap check runs first and is
     * decisive.
     */
    @Test
    void aTaskThatNamesAForbiddenTechnologyIsRejectedWithoutAModelCall() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("AI planner")) {
                    plannerCalls.incrementAndGet();
                    return REST_PLAN_JSON;
                }
                if (conversation.contains("checking a PLAN")) {
                    reviewerCalls.incrementAndGet();
                    return """
                        {"approved":true,"objections":[]}
                        """;
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime,
                run -> {
                    throw new AssertionError("a swarm was dispatched — PLAN never accepted a plan");
                },
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);
            engine.setProjectRules(() -> RULES_BRIEF);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(),
                new RunReport(runId, "add an API"));
            engine.advance(run);

            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);

            assertThat(plannerCalls.get())
                .isEqualTo(GreenfieldWorkflow.MAX_PLAN_ATTEMPTS);
            assertThat(reviewerCalls.get())
                .as("the guard alone found REST on every attempt — the reviewer was never needed")
                .isZero();
            assertThat(parked.parkReason())
                .contains("REST")
                .contains("forbids");
        }
    }

    // ------------------------------------------- 2b. nobody watching: opinion warns, fact stops

    /**
     * Owner decision, 2026-10-01. The reviewer model's objection is an opinion: with nobody
     * watching, the planner is still re-asked on every earlier attempt, and on the last one the
     * objection is recorded on the run as a warning and the plan is accepted.
     */
    @Test
    void withNobodyWatchingTheReviewersObjectionIsCarriedAsAWarningAfterTheReAsks()
            throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("AI planner")) {
                    plannerCalls.incrementAndGet();
                    return LOCAL_STORAGE_PLAN_JSON;
                }
                if (conversation.contains("checking a PLAN")) {
                    return """
                        {"approved":false,"objections":["task 'Add client persistence' conflicts \
                        with rule 'Storage is an object graph': its instructions add localStorage \
                        in the browser, but persistence must go through EclipseStore"]}
                        """;
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Fixture fx = fixture(store);
            WorkflowEngine engine = new WorkflowEngine(
                spec -> {
                    throw new UnsupportedOperationException("not exercised by this test");
                },
                nobodyWatching(), new VllmClient(llm.baseUrl(), "", "test-model", true), store);
            engine.setProjectRules(() -> RULES_BRIEF);
            engine.setEventLogger(events::add);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.PLAN,
                fx.projectId, fx.storyId, null, null, null, Instant.now(),
                new RunReport(runId, "keep books and ratings across a restart"));
            engine.advance(run);

            awaitStopped(store, runId);
            Run stopped = store.root().runs.get(runId);

            assertThat(plannerCalls.get())
                .as("the bounded correction still happens: every attempt is used first")
                .isEqualTo(GreenfieldWorkflow.MAX_PLAN_ATTEMPTS);
            assertThat(stopped.taskGraphId())
                .as("the plan was accepted — the run did not park in PLAN over an opinion")
                .isNotNull();
            assertThat(stopped.state()).isNotEqualTo(RunState.PLAN);
            assertThat(stopped.carriedWarnings()).as("on the run's record")
                .singleElement().satisfies(w -> {
                    assertThat(w.stage()).isEqualTo("PLAN");
                    assertThat(w.check()).contains("plan against the project's rules");
                    assertThat(w.objection()).contains("localStorage")
                        .contains("Storage is an object graph");
                });
            assertThat(events).as("said when carried, and listed again where the run stops")
                .anyMatch(e -> e.contains("WARNING CARRIED") && e.contains("localStorage"))
                .anyMatch(e -> e.contains("carried 1 warning(s)"));
        }
    }

    /** A forbidden technology named outright is a fact: it stops the run whoever is watching. */
    @Test
    void withNobodyWatchingAForbiddenTechnologyStillParksTheRun() throws Exception {
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation ->
                conversation.contains("AI planner") ? REST_PLAN_JSON
                    : "I decline to produce JSON.");
             ArtifactStore store = new ArtifactStore(storeDir)) {
            WorkflowEngine engine = new WorkflowEngine(
                spec -> {
                    throw new UnsupportedOperationException("not exercised by this test");
                },
                nobodyWatching(), new VllmClient(llm.baseUrl(), "", "test-model", true), store);
            engine.setProjectRules(() -> RULES_BRIEF);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(),
                new RunReport(runId, "add an API"));
            engine.advance(run);

            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);

            assertThat(parked.state()).isEqualTo(RunState.PLAN);
            assertThat(parked.parkReason()).contains("REST").contains("forbids");
            assertThat(parked.carriedWarnings()).isEmpty();
        }
    }

    /** A swarm engine built for a run nobody is watching, as the live harness builds its own. */
    private static com.swarmcoder.runtime.SwarmEngine nobodyWatching() {
        return new com.swarmcoder.runtime.SwarmEngine() {
            @Override public Run executeRun(Run run) {
                return run; // never reached — TEST_AUTHORING declines first
            }
            @Override public com.swarmcoder.domain.OpinionPolicy opinionPolicy() {
                return com.swarmcoder.domain.OpinionPolicy.WARN_AND_CARRY_ON;
            }
        };
    }

    // ------------------------------------------------------------------------ 3. a clean plan

    /** A plan that obeys the rules costs exactly one reviewer call and proceeds. */
    @Test
    void aCleanPlanCostsOneReviewerCallAndProceeds() throws Exception {
        AtomicInteger reviewerCalls = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("AI planner")) {
                    return CLEAN_PLAN_JSON;
                }
                if (conversation.contains("checking a PLAN")) {
                    reviewerCalls.incrementAndGet();
                    return """
                        {"approved":true,"objections":[]}
                        """;
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime, run -> run, // never reached — TEST_AUTHORING declines first
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);
            engine.setProjectRules(() -> RULES_BRIEF);
            engine.setEventLogger(events::add);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(),
                new RunReport(runId, "keep books using EclipseStore"));
            engine.advance(run);

            awaitStopped(store, runId);
            Run finished = store.root().runs.get(runId);

            assertThat(reviewerCalls.get())
                .as("exactly one reviewer call for the one attempt this plan needed")
                .isEqualTo(1);
            assertThat(events)
                .as(String.join("\n", events))
                .anyMatch(e -> e.contains("PLAN: reviewed against 2 rule(s): no objections"));
            assertThat(finished.taskGraphId())
                .as("the plan was accepted and persisted — it proceeded past PLAN")
                .isNotNull();
        }
    }

    // ---------------------------------------------------------- 4. a broken reviewer never blocks

    /** A reviewer that never returns valid JSON must not be the reason PLAN cannot proceed. */
    @Test
    void aReviewerThatNeverParsesLogsAWarningAndThePlanProceedsAnyway() throws Exception {
        AtomicInteger reviewerRawCalls = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("AI planner")) {
                    return CLEAN_PLAN_JSON;
                }
                if (conversation.contains("checking a PLAN")) {
                    reviewerRawCalls.incrementAndGet();
                    return "that is not JSON, sorry";
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime, run -> run,
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);
            engine.setProjectRules(() -> RULES_BRIEF);
            engine.setEventLogger(events::add);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(),
                new RunReport(runId, "keep books using EclipseStore"));
            engine.advance(run);

            awaitStopped(store, runId);
            Run finished = store.root().runs.get(runId);

            assertThat(reviewerRawCalls.get())
                .as("one call, then one retry — the reviewer's own reply-with-only-JSON retry")
                .isEqualTo(2);
            assertThat(events)
                .as(String.join("\n", events))
                .anyMatch(e -> e.contains("plan review unavailable"));
            assertThat(finished.taskGraphId())
                .as("a broken reviewer must not be the reason PLAN cannot proceed")
                .isNotNull();
        }
    }

    // ------------------------------------------------ 5. no loopholes (author decision, §21 addendum)

    /**
     * Harness run 14: "Implement BookService on the server" allowed an in-memory map "if the
     * infrastructure is already set up" against a rule that mandates EclipseStore outright — a
     * loophole the plan itself wrote, not one the worker invented. The planner is now told, in the
     * same standing-rules sentence that already says rules are not work, that a task's instructions
     * may not carve out an exception like that either.
     */
    @Test
    void thePlannerIsToldNotToRelaxAStatedRulePerTask() throws Exception {
        AtomicInteger testAuthorCalls = new AtomicInteger();
        List<String> plannerConversations = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("You are a test author")) {
                    testAuthorCalls.incrementAndGet();
                    return "I decline to produce JSON.";
                }
                if (conversation.contains("AI planner")) {
                    plannerConversations.add(conversation);
                    return CLEAN_PLAN_JSON;
                }
                if (conversation.contains("checking a PLAN")) {
                    return """
                        {"approved":true,"objections":[]}
                        """;
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
                    throw new AssertionError("a swarm was dispatched — TEST_AUTHORING never accepts "
                        + "in this test, on purpose, so PLAN's own prompt is all this checks");
                },
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);
            engine.setProjectRules(() -> RULES_BRIEF);

            // RunState.PLAN, with a story already scoped — the same entry point
            // aPlanNamingBrowserLocalStorageIsSentBackWithTheReviewersObjectionAndReAsked uses above,
            // which is what actually reaches the SCOPED planner (criterionRefs, ArchitectClient's
            // PLAN_SYSTEM_PROMPT) rather than the older unscoped goal-decomposition prompt that a
            // bare RunState.INTAKE run reaches first.
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.PLAN,
                fx.projectId, fx.storyId, null, null, null, Instant.now(),
                new RunReport(runId, "keep books and ratings across a restart"));
            engine.advance(run);

            awaitStopped(store, runId);

            assertThat(plannerConversations)
                .as("the planner must actually have been called")
                .isNotEmpty();
            assertThat(plannerConversations)
                .as("the planner must be told a task's own instructions cannot relax a stated rule")
                .anyMatch(c -> c.contains("MAY NOT RELAX A STATED RULE")
                    && c.contains("not negotiable per task"));
        }
    }

    /**
     * The plan-versus-rules reviewer ({@link DesignReviewerClient#reviewPlan}) is the one place that
     * reads a task's own words against the rules before it is dispatched; it must be told that a
     * task carving out an exception ("in-memory is fine if the store isn't set up yet") is exactly
     * as much a conflict as a flat contradiction, not a nuance it should let through as reasonable.
     */
    @Test
    void theReviewerIsToldToFlagATaskThatCarvesOutALoophole() throws Exception {
        List<String> reviewerConversations = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("AI planner")) {
                    return CLEAN_PLAN_JSON;
                }
                if (conversation.contains("checking a PLAN")) {
                    reviewerConversations.add(conversation);
                    return """
                        {"approved":true,"objections":[]}
                        """;
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime, run -> run,
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);
            engine.setProjectRules(() -> RULES_BRIEF);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(),
                new RunReport(runId, "keep books using EclipseStore"));
            engine.advance(run);

            awaitStopped(store, runId);

            assertThat(reviewerConversations)
                .as("the reviewer must actually have been called")
                .isNotEmpty();
            assertThat(reviewerConversations)
                .as("the reviewer must be told a rule-relaxing loophole is a conflict, not an "
                    + "exception it should wave through")
                .anyMatch(c -> c.contains("relaxes a rule") && c.contains("loophole"));
        }
    }

    // ------------------------------------------------------------------------------- fixtures

    private static final String LOCAL_STORAGE_PLAN_JSON = """
        {"tasks":[{"id":"t1","title":"Add client persistence",
          "instructions":"implement client-side localStorage persistence for books and ratings",
          "writeSet":["bookshelf-demo-client/src/main/java"],"readSet":[],
          "criteria":[{"text":"books and ratings are still present after the browser is closed \
        and reopened","testClassOrFile":"swarm.accept.R1C1AcceptTest#proves"}],
          "criterionRefs":["R1:C1"]}],
         "edges":[]}
        """;

    private static final String REST_PLAN_JSON = """
        {"tasks":[{"id":"t1","title":"Add book API",
          "instructions":"implement a REST endpoint for saving a book",
          "writeSet":["src/main/java"],"readSet":[],"criteria":[]}],
         "edges":[]}
        """;

    private static final String CLEAN_PLAN_JSON = """
        {"tasks":[{"id":"t1","title":"Keep the reading list",
          "instructions":"implement server-side persistence for the reading list using \
        EclipseStore",
          "writeSet":["src/main/java"],"readSet":[],"criteria":[]}],
         "edges":[]}
        """;

    private record Fixture(UUID projectId, UUID storyId) {}

    /** A one-requirement, one-criterion BRD and story, sliced to exactly what the run answers for. */
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

    private static LearnedGuideline rule(String slug, String title, String body) {
        LearnedGuideline g = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            slug, body, new Provenance("stated", null, "bookshelf-tech-requirements.md"), 1.0,
            Instant.now(), 0, GuidelineStatus.ACTIVE, UUID.randomUUID(), null, 0);
        g.setTitle(title);
        return g;
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

    /** Parked, or ran clean out the other side of a stage this test does not script further. */
    private static void awaitStopped(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && (run.parkedAt() != null || run.state() == RunState.DELIVERED
                    || run.state() == RunState.ABORTED)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " never stopped");
    }
}
