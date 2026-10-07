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
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rule about how the project is built must not stop the project being built.
 *
 * <p><b>What would happen without this.</b> Every requirement's checks must be claimed by some task
 * or the plan is rejected and the run parks for a person. A technical rule filed as an ordinary
 * requirement therefore demands that some task DELIVER "this project is pure Java and contains no
 * SQL", which no task can do and no planner can be talked into doing — so a project that wrote its
 * technical rules down would park on every single story, for ever, and the more carefully it stated
 * them the sooner it would stop.
 *
 * <p><b>How that is prevented now.</b> Not by a third kind of requirement with an exemption chain
 * around it — that was tried for a few hours on 2026-08-31 and needed nine separate carve-outs to
 * keep rules out of machinery that assumes everything in it gets delivered. A rule is a
 * {@link LearnedGuideline}: it is never in the requirement graph at all, so there is nothing to
 * exempt it from. It reaches the architect, the test author and every worker as briefing text.
 *
 * <p>What is asserted here is the mechanism, on both sides: the rules are carried to the architect,
 * and they are carried to NO task and NO check.
 */
class ProjectRulesAreNeverDeliveredTest {

    private final TaskGraphValidator validator = new TaskGraphValidator();

    @Test
    void aPlanThatDeliversTheStoryPassesWithNoTaskAnsweringForTheRules() {
        StoryScope scope = bookshelfScope();
        assertThat(scope.constraintBrief()).isNotEmpty();

        // Every check in the slice is claimed. Not one task mentions a rule, and none can.
        Task task = task("Add the reading list", Set.of("bookshelf-demo-server/src/main/java"));
        task.setCriterionIds(Set.of(scope.criteria().get(0).id()));

        TaskGraphValidator.Verdict verdict =
            validator.validate(graph(List.of(task)), scope, null);

        assertThat(verdict.ok())
            .describedAs("a plan that ignores the rules entirely is a CORRECT plan — %s",
                verdict.violations())
            .isTrue();
        assertThat(verdict.violations()).isEmpty();
    }

    /**
     * The rules contribute nothing the plan has to cover, and they cannot: they are not in the
     * requirement graph, so there is no criterion of theirs for a story to slice or a task to
     * claim. This is the property the nine exemptions were simulating.
     */
    @Test
    void theRulesAreNotInTheSliceSoNothingIsEverAskedToDeliverThem() {
        StoryScope scope = bookshelfScope();

        assertThat(scope.criteria())
            .describedAs("only the story's own check is deliverable")
            .hasSize(1);
        assertThat(scope.requirements()).extracting(BrdRequirement::handle).containsExactly("R1");
        assertThat(scope.gatingNfrs())
            .describedAs("a rule is not a quality gate either — it has nothing to be judged by")
            .isEmpty();
        assertThat(scope.constraintBrief())
            .describedAs("and every rule in the project applies to this story without an edge")
            .contains("Do not use Spring, JPA, Flyway or SQL")
            .contains("EclipseStore");
    }

    /** The architect is told the rules, in full, before it is told anything else. */
    @Test
    void theArchitectIsToldTheRulesBeforeTheStory() {
        String briefing = ArchitectClient.scopeBriefing("Readers can list their books",
            bookshelfScope());

        assertThat(briefing).contains("EclipseStore")
            .contains("Do not use Spring, JPA, Flyway or SQL");
        assertThat(briefing.indexOf("EclipseStore"))
            .describedAs("the rules decide what every answer may be made of, so they come first")
            .isLessThan(briefing.indexOf("Readers can list their books"));
        assertThat(briefing)
            .describedAs("and it is told plainly that no task delivers them")
            .contains("Nothing delivers");
    }

    /** A project with no rules gets exactly the briefing it got before any of this existed. */
    @Test
    void aProjectThatStatesNoRulesIsUnchanged() {
        Brd brd = bookshelfBrd();
        StoryScope scope = StoryScope.resolve(brd,
            story(brd, List.of(brd.requirements().get(0).criteria().get(0).id())));

        assertThat(scope.constraintBrief()).isEmpty();
        assertThat(ArchitectClient.scopeBriefing("Readers can list their books", scope))
            .startsWith("Story: Readers can list their books");
    }

    /**
     * A rule the operator turned off stops being sent.
     *
     * <p>Which rules are live is {@code ProjectRules}'s decision and not this class's, so what is
     * checked here is that the scope carries exactly what it was handed, and that a briefing built
     * from no live rules is empty rather than a heading with nothing under it.
     */
    @Test
    void aRetiredRuleIsNoLongerToldToAnyone() {
        Brd brd = bookshelfBrd();
        StoryScope allOff = StoryScope.resolve(brd,
            story(brd, List.of(brd.requirements().get(0).criteria().get(0).id())),
            ConstraintBrief.render(List.of()));

        assertThat(allOff.constraintBrief()).isEmpty();
        assertThat(ArchitectClient.scopeBriefing("Readers can list their books", allOff))
            .doesNotContain("HOW THIS PROJECT MUST BE BUILT");
    }

    // --- fixtures ---------------------------------------------------------------------------

    private static StoryScope bookshelfScope() {
        Brd brd = bookshelfBrd();
        return StoryScope.resolve(brd,
            story(brd, List.of(brd.requirements().get(0).criteria().get(0).id())),
            ConstraintBrief.render(bookshelfRules()));
    }

    /** The BRD holds the DELIVERABLE work and nothing else — the rules are not requirements. */
    private static Brd bookshelfBrd() {
        BrdRequirement feature = new BrdRequirement(UUID.randomUUID(), "R1", "Reading list",
            "A reader can see the books they have added.", Priority.HIGH,
            RequirementStatus.ACTIVE, null);
        feature.setCriteria(new ArrayList<>(List.of(criterion("a book added is listed",
            "swarm.accept.ReadingListTest#listsAnAddedBook"))));
        return new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "BRD",
            new ArrayList<>(List.of(feature)), new ArrayList<>(), Instant.now(), Instant.now());
    }

    /** Two of the real rules from {@code dev/bookshelf-tech-requirements.md}. */
    private static List<LearnedGuideline> bookshelfRules() {
        return List.of(
            rule("what-this-project-must-not-use", "What this project must not use",
                "Do not use Spring, JPA, Flyway or SQL migrations, a relational database, REST or "
                    + "JSON, JavaScript, or Vaadin. None of them is present and none will be added."),
            rule("storage-is-an-object-graph", "Storage is an object graph",
                "Persistence is EclipseStore. Saving an object does not save the objects inside "
                    + "it — every level of nesting you changed needs its own save call, and "
                    + "getting it wrong loses the edit silently."));
    }

    private static LearnedGuideline rule(String slug, String title, String body) {
        LearnedGuideline g = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            slug, body, new Provenance("stated", null, "bookshelf-tech-requirements.md"), 1.0,
            Instant.now(), 0, GuidelineStatus.ACTIVE, UUID.randomUUID(), null, 0);
        g.setTitle(title);
        return g;
    }

    private static AcceptanceCriterion criterion(String text, String test) {
        AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), text, test);
        c.setStatus(CriterionStatus.ACCEPTED);
        return c;
    }

    private static Story story(Brd brd, List<UUID> criterionIds) {
        return new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Readers can list their books", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(criterionIds), null, 0, StoryOrigin.BACKLOG, null, null, "human",
            new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
    }

    private static Task task(String title, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", writeSet, Set.of(),
            List.of(), ArchitectClient.ACCEPTANCE_TEST_DIR, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static TaskGraph graph(List<Task> tasks) {
        return new TaskGraph(UUID.randomUUID(), 1, null, new ArrayList<>(tasks),
            new ArrayList<>());
    }
}
