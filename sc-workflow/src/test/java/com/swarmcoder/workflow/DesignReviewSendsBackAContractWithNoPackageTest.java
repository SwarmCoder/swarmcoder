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
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gap named by the coordinator alongside the harness-run-46 fix to
 * {@code ArchitectClient.resolveTypeName}: recovering a contract's name from {@code name} or
 * {@code signature} when the model answered {@code "type"} with a kind word can legitimately land
 * on a simple name with no package to combine it with. Left unchecked, a contract like that
 * reaches its task as {@code typeName} "Book" with no package — {@code
 * com.swarmcoder.knowledge.ContractDelivery} and {@link TaskGraphValidator} both key delivery off
 * that exact string, so a worker who correctly writes {@code com.acme.shop.Book} would be judged
 * as never delivering it, wasting the run. This pins {@link ContractsNameAQualifiedType} being
 * wired into DESIGN_REVIEW through the exact same revision loop {@link ContractsNameRealTypes}
 * uses: through the real {@link WorkflowEngine}, no model called except {@link ScriptedLlm}.
 */
class DesignReviewSendsBackAContractWithNoPackageTest {

    @TempDir
    Path storeDir;

    private static final String REQUIREMENT = "The user can look up a book";

    private static String design(String bookType) {
        return """
            {"requirements":[{"text":"%s","priority":"HIGH"}],
             "decisions":[{"decision":"Books are looked up by id","rationale":"simplest"}],
             "contracts":[{"name":"BookApi","description":"a single book","signature":"",
               "type":"%s","members":[]}],
             "risks":[],"missingRequirements":[]}
            """.formatted(REQUIREMENT, bookType);
    }

    @Test
    void aRevisionThatAddsThePackageIsAcceptedAndTheRunProceedsPastDesign() throws Exception {
        List<String> revisions = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("revising a design against review objections")) {
                    revisions.add(conversation);
                    return design("com.acme.shop.Book");
                }
                if (conversation.contains("requirements are FIXED")) {
                    return design("Book");
                }
                if (conversation.contains("Critique the design against this rubric")) {
                    return "{\"approved\": true, \"objections\": []}";
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            WorkflowEngine engine = engine(llm, store);
            Story story = seedStory(store);
            engine.advance(new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                story.projectId(), story.id(), null, null, null, Instant.now(),
                new RunReport(runId, "Look up a book")));

            Run parked = awaitParked(store, runId);

            assertThat(revisions).as("one revision was enough").hasSize(1);
            assertThat(revisions.get(0))
                .contains("the contract `Book` gives no package")
                .contains("fully-qualified name");
            assertThat(parked.state())
                .as("past DESIGN_REVIEW: this scripted planner answers nothing, so PLAN parks")
                .isEqualTo(RunState.PLAN);
            DesignDocument stored = store.root().designs.get(parked.designId());
            ApiContract api = stored.contracts().get(0);
            assertThat(api.typeName()).isEqualTo("com.acme.shop.Book");
        }
    }

    @Test
    void aDesignThatKeepsTheContractUnqualifiedParksAtDesignReviewSayingWhy() throws Exception {
        List<String> revisions = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("revising a design against review objections")) {
                    revisions.add(conversation);
                    return design("Book");
                }
                if (conversation.contains("requirements are FIXED")) {
                    return design("Book");
                }
                if (conversation.contains("Critique the design against this rubric")) {
                    return "{\"approved\": true, \"objections\": []}";
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            WorkflowEngine engine = engine(llm, store);
            Story story = seedStory(store);
            engine.advance(new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                story.projectId(), story.id(), null, null, null, Instant.now(),
                new RunReport(runId, "Look up a book")));

            Run parked = awaitParked(store, runId);

            assertThat(revisions).hasSize(GreenfieldWorkflow.MAX_DESIGN_RULE_REVISIONS);
            assertThat(parked.state()).isEqualTo(RunState.DESIGN_REVIEW);
            assertThat(parked.parkReason())
                .contains("the contract `Book` gives no package")
                .contains("Fix the contract in the design yourself");
        }
    }

    private static WorkflowEngine engine(ScriptedLlm llm, ArtifactStore store) {
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        return new WorkflowEngine(
            unusedRuntime,
            run -> {
                throw new AssertionError("a swarm was dispatched — this test never expects EXECUTING");
            },
            new VllmClient(llm.baseUrl(), "", "test-model", true),
            store);
    }

    private static Story seedStory(ArtifactStore store) {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R1",
            "Look up a book", REQUIREMENT, Priority.HIGH, RequirementStatus.ACTIVE, null);
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "A book can be looked up by id.", "swarm.accept.BookLookupTest#byId");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Bookshelf",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now());
        store.saveBrd(brd);
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Look up a book", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        store.saveStory(story);
        return story;
    }

    private static Run awaitParked(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.parkedAt() != null) {
                return run;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " never parked");
    }
}
