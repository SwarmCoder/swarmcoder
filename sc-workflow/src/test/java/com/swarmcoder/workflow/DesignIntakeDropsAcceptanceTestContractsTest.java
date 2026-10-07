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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fix for harness run 19 (2026-09-03): the architect listed the acceptance test class
 * itself, {@code swarm.accept.PersistenceTest}, as a design contract. {@link TaskGraphValidator}'s
 * "every contract that names a type is delivered by exactly one task" rule then rejected the plan
 * for not assigning that impossible task, sending the planner in a circle it could not escape.
 *
 * <p>These tests pin the two-part fix at the source: the design is normalised the moment it comes
 * back from the model, so neither the PLAN prompt nor the validator ever sees the contract again;
 * and the architect is told, in the contract-vocabulary rule itself, not to list one in the first
 * place.
 */
class DesignIntakeDropsAcceptanceTestContractsTest {

    @TempDir
    Path repo;

    private static ArchitectClient architect(ScriptedLlm llm) {
        return new ArchitectClient(
            new VllmClient(llm.baseUrl(), null, "test-model", true),
            new CloudGate(1_000_000, null),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()));
    }

    @Test
    void thePromptTellsTheArchitectTheAcceptanceTestClassesAreNotContracts() {
        assertThat(ArchitectClient.CONTRACT_VOCABULARY_RULE).contains(
            "The acceptance test classes named by the checks are written by the test author and "
            + "are NOT contracts; do not list them.");
    }

    @Test
    void anUnscopedDesignDropsTheAcceptanceTestClassAndKeepsTheRealContract() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> """
                {"decisions":[],
                 "contracts":[
                   {"name":"BookApi","description":"the book type","signature":"",
                    "type":"com.acme.demo.bookshelf.Book","members":["String title()"]},
                   {"name":"PersistenceTest","description":"the acceptance test","signature":"",
                    "type":"swarm.accept.PersistenceTest","members":[]}
                 ],
                 "risks":[]}
                """)) {
            ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ArchitectClient.class);
            ListAppender<ILoggingEvent> captured = new ListAppender<>();
            captured.start();
            logger.addAppender(captured);
            try {
                DesignDocument design = architect(llm).design("Store a book");

                assertThat(design.contracts()).hasSize(1);
                assertThat(design.contracts().get(0).typeName()).isEqualTo("com.acme.demo.bookshelf.Book");
                assertThat(captured.list.stream().map(ILoggingEvent::getFormattedMessage))
                    .anyMatch(message -> message.equals("dropped contract swarm.accept.PersistenceTest "
                        + "from the design: it is the acceptance test the test author writes, not a "
                        + "type a task delivers"));
            } finally {
                logger.detachAppender(captured);
            }
        }
    }

    @Test
    void aScopedDesignAlsoDropsTheAcceptanceTestClass() throws Exception {
        AtomicReference<String> prompt = new AtomicReference<>("");
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            prompt.set(conversation);
            return """
                {"decisions":[],
                 "contracts":[
                   {"name":"BookApi","description":"the book type","signature":"",
                    "type":"com.acme.demo.bookshelf.Book","members":["String title()"]},
                   {"name":"PersistenceTest","description":"the acceptance test","signature":"",
                    "type":"swarm.accept.PersistenceTest","members":[]}
                 ],
                 "risks":[],"missingRequirements":[]}
                """;
        })) {
            StoryScope scope = scope();

            ArchitectClient.ScopedDesign scoped = architect(llm).design("Store a book", scope);

            assertThat(scoped.design().contracts()).hasSize(1);
            assertThat(scoped.design().contracts().get(0).typeName())
                .isEqualTo("com.acme.demo.bookshelf.Book");
            // The prompt legitimately shows the criterion's OWN test reference — that is the
            // check's definition, given to the architect on purpose. What must also be there is
            // the rule telling it not to turn that reference into a contract of its own.
            assertThat(prompt.get()).contains(
                "The acceptance test classes named by the checks are written by the test author "
                + "and are NOT contracts; do not list them.");
        }
    }

    /**
     * The other half of the fix has to keep working: once the contract is dropped, the test
     * author's own file — which necessarily DECLARES the very class the dropped contract named —
     * must still be accepted by {@link AcceptanceTestVocabulary}. It is, because a self-declared
     * type was never something that check flagged; this pins that the drop does not change that.
     */
    @Test
    void theTestAuthorMayStillDeclareTheAcceptanceTestClassItsOwnFileIsNamedAfter() throws Exception {
        var book = new com.swarmcoder.domain.ApiContract(UUID.randomUUID(), "BookApi",
            "the book type", "", "com.acme.demo.bookshelf.Book", List.of("String title()"));
        DesignDocument normalisedDesign = new DesignDocument(UUID.randomUUID(), 1, "Store a book",
            List.of(), new ArrayList<>(), List.of(book), List.of(), null, Instant.now());

        Path testFile = repo.resolve("swarm/accept/PersistenceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
            package swarm.accept;
            import com.acme.demo.bookshelf.Book;
            class PersistenceTest {
                void retainsDataAfterRestart() {
                    Book b = new Book();
                }
            }
            """);

        AcceptanceTestVocabulary.Check check = AcceptanceTestVocabulary.check(
            repo, normalisedDesign, List.of("swarm/accept/PersistenceTest.java"));

        assertThat(check.ok()).as(check.unknowns().toString()).isTrue();
    }

    /** One requirement whose accepted criterion's test reference is the acceptance test class. */
    private static StoryScope scope() {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R1", "Persistence",
            "Data survives a restart", Priority.HIGH, RequirementStatus.ACTIVE, null);
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "data survives a restart", "swarm.accept.PersistenceTest#retainsDataAfterRestart");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));

        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "BRD",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now());
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Store a book", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());

        return StoryScope.resolve(brd, story);
    }
}
