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

import com.swarmcoder.domain.ConstraintBrief;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit coverage of {@link ForbiddenTechGuard}, independent of the model calls
 * {@link PlanVersusRulesTest} exercises around it.
 */
class ForbiddenTechGuardTest {

    private static final String FORBIDS_REST = ConstraintBrief.render(List.of(
        rule("Explicitly forbidden",
            "Do not use, add, import, or write tests against any of these. None of them is "
                + "present in this project and none of them will be added: REST endpoints, "
                + "HTTP controllers, or JSON; Spring or Spring Boot; JavaScript or TypeScript.")));

    @Test
    void aTaskThatIntroducesAForbiddenTermIsAnObjection() {
        Task task = task("Add book API", "implement a REST endpoint for saving a book");

        List<String> objections = ForbiddenTechGuard.check(FORBIDS_REST, List.of(task));

        assertThat(objections).hasSize(1);
        assertThat(objections.get(0))
            .contains("Add book API")
            .contains("Explicitly forbidden")
            .contains("REST");
    }

    @Test
    void aTaskThatDoesNotMentionAForbiddenTermIsClean() {
        Task task = task("Add book API", "implement server-side persistence using EclipseStore");

        assertThat(ForbiddenTechGuard.check(FORBIDS_REST, List.of(task))).isEmpty();
    }

    /**
     * A task instruction that RESTATES the rule back at the worker ("do not use REST, use the
     * WebSocket transport") contains the very same "use REST" substring a genuine violation would.
     * Only the cue-word position plus a nearby negation is what tells the two apart — see the class
     * javadoc.
     */
    @Test
    void aTaskThatCorrectlyRefusesTheForbiddenTermIsNotAnObjection() {
        Task task = task("Add book API",
            "Do not use REST for this — use the existing WebSocket transport instead.");

        assertThat(ForbiddenTechGuard.check(FORBIDS_REST, List.of(task))).isEmpty();
    }

    /** A term the rules do not name is never flagged, no matter how it reads. */
    @Test
    void aTermTheRulesDoNotNameIsNeverFlagged() {
        Task task = task("Add client persistence",
            "implement client-side localStorage persistence for books and ratings");

        assertThat(ForbiddenTechGuard.check(FORBIDS_REST, List.of(task))).isEmpty();
    }

    /** A rule that merely MENTIONS a term, without reading as a prohibition, is not a forbidden list. */
    @Test
    void aRuleThatDoesNotProhibitAnythingNeverTripsTheGuard() {
        String mentionsButAllows = ConstraintBrief.render(List.of(
            rule("How the browser talks to the server",
                "There are no REST calls and no JSON. A service interface lives in the shared "
                    + "module and the client calls it as if it were local.")));
        Task task = task("Add book API", "implement a REST endpoint for saving a book");

        assertThat(ForbiddenTechGuard.check(mentionsButAllows, List.of(task))).isEmpty();
    }

    @Test
    void blankInputsFindNothing() {
        Task task = task("Add book API", "implement a REST endpoint for saving a book");

        assertThat(ForbiddenTechGuard.check("", List.of(task))).isEmpty();
        assertThat(ForbiddenTechGuard.check(FORBIDS_REST, List.of())).isEmpty();
    }

    // ---- the forbidden names are the project's, not the product's (owner decision 2026-10-03) ----

    /** A technology this product has never heard of is forbidden the moment a rule forbids it. */
    @Test
    void aTechnologyOnlyThisProjectForbidsIsCaught() {
        String rules = ConstraintBrief.render(List.of(
            rule("No reflection helpers", "Do not use Lombok, MapStruct or log4j.")));

        assertThat(ForbiddenTechGuard.check(rules,
                List.of(task("Add the mapper", "Map the entity to its view with MapStruct."))))
            .singleElement().asString().contains("MapStruct").contains("No reflection helpers");
        assertThat(ForbiddenTechGuard.check(rules,
                List.of(task("Add logging", "Write the audit line using log4j."))))
            .hasSize(1);
    }

    /** The old fixed list is gone: a project whose rules forbid nothing forbids nothing. */
    @Test
    void aProjectWithNoForbiddingRuleForbidsNothing() {
        String rules = ConstraintBrief.render(List.of(
            rule("Layout", "Controllers stay thin and must not be renamed. The web layer uses "
                + "Spring and talks JSON over REST.")));
        Task task = task("Add book API", "implement a REST endpoint with Spring, returning JSON");

        assertThat(ForbiddenTechGuard.check(rules, List.of(task))).isEmpty();
    }

    /** What the rule prescribes in the same breath as what it forbids is not forbidden. */
    @Test
    void whatARuleSaysToUseInsteadIsNotForbidden() {
        String rules = ConstraintBrief.render(List.of(
            rule("Persistence", "Never use Hibernate; use EclipseStore instead. The build is "
                + "Maven on Java 21.")));

        assertThat(ForbiddenTechGuard.forbiddenTerms(ForbiddenTechGuard.ruleChunks(rules).get(0)))
            .containsExactly("Hibernate");
    }

    /** Only the name that leads a list item is taken; a description names nothing. */
    @Test
    void onlyTheNamesTheProhibitionListsAreTaken() {
        String chunk = "- Forbidden frameworks and libraries\n"
            + "  Do not use, add, import, or write tests against: Spring or Spring Boot; JPA, "
            + "Hibernate or any ORM; any relational database; REST endpoints, HTTP controllers or "
            + "JSON between the browser and the server (JSON is allowed only inside adapters); "
            + "JavaScript source files, and switching the TeaVM target to WebAssembly; Vaadin or "
            + "any other web UI framework; maven-shade-plugin (merging jars breaks discovery) and "
            + "any fat jar; the Acme or Zeta API, anywhere in the product.\n"
            + "  Why it exists: So the project does not revert to Struts habits.\n"
            + "  A HARD rule: breaking it stops the work.\n";

        assertThat(ForbiddenTechGuard.forbiddenTerms(chunk)).containsExactly(
            "Spring", "JPA", "Hibernate", "ORM", "REST", "HTTP", "JSON", "JavaScript", "Vaadin",
            "maven-shade-plugin", "Acme", "Zeta");
    }

    /** A prohibition the rule confines to one place is not a ban on the technology. */
    @Test
    void aProhibitionConfinedToAPlaceForbidsNothingProjectWide() {
        String chunk = "- Wire types\n  Not allowed in a wire field: object arrays, ZonedDateTime, "
            + "OffsetDateTime. The three modules must not be renamed.\n";

        assertThat(ForbiddenTechGuard.forbiddenTerms(chunk)).isEmpty();
    }

    /** "A and B are forbidden" names its list before the word. */
    @Test
    void aListStatedBeforeTheProhibitionIsRead() {
        assertThat(ForbiddenTechGuard.forbiddenTerms("- Stack\n  Kafka and gRPC are forbidden.\n"))
            .containsExactly("Kafka", "gRPC");
        assertThat(ForbiddenTechGuard.forbiddenTerms("- Explicitly forbidden\n")).isEmpty();
    }

    private static Task task(String title, String instructions) {
        return new Task(UUID.randomUUID(), 1, title, instructions, Set.of("src/main/java"),
            Set.of(), List.of(), ArchitectClient.ACCEPTANCE_TEST_DIR, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static LearnedGuideline rule(String title, String body) {
        LearnedGuideline g = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            "rule", body, new Provenance("stated", null, "tech-requirements.md"), 1.0,
            Instant.now(), 0, GuidelineStatus.ACTIVE, UUID.randomUUID(), null, 0);
        g.setTitle(title);
        return g;
    }
}
