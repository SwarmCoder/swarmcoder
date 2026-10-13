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

import com.swarmcoder.app.WorkerLookups;
import com.swarmcoder.domain.ConstraintBrief;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.knowledge.ProjectRules;
import com.swarmcoder.knowledge.SharedAnswers;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.PromptBundle;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 100. A worker's opening is sent again on every call it makes: 26,000 characters on each
 * of 2,300 calls in the audited build, about 30% of all tokens. And of a first-round worker's
 * lookups 621 were ones a sibling candidate of the same task also made.
 *
 * <p>The opening keeps what every call needs and names the rest, which a lookup returns; the
 * candidates of a task are given each other's lookups; and neither changes a character of the
 * opening the candidates share.
 */
class AWorkersOpeningAndItsLookupsAreSharedTest {

    @TempDir
    Path world;

    private Path app;
    private Librarian librarian;

    @BeforeEach
    void aProjectWithDocuments() throws Exception {
        app = world.resolve("app");
        write(app.resolve("pom.xml"), "<project><groupId>com.shop</groupId>"
            + "<artifactId>shop</artifactId><version>1</version></project>");
        write(app.resolve("src/main/java/com/shop/Basket.java"), """
            package com.shop;

            public class Basket {
                public int size() {
                    return 0;
                }
            }
            """);
        for (String guide : List.of("architecture", "testing", "persistence", "screens",
                "services", "security", "deployment", "messages")) {
            write(app.resolve("docs/" + guide + ".md"), "# How this project does " + guide
                + "\n\n## The rule\n\nEverything about " + guide + " is done one way here.\n\n"
                + "## An example\n\nSee the server module.\n");
        }
        librarian = new Librarian(null, null, List.of(), app, null, world.resolve("primers"));
    }

    @AfterEach
    void theBriefAsItIsByDefault() {
        System.clearProperty(Librarian.DOCUMENT_MAP_PROPERTY);
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Count the items of a basket",
            "Basket#size returns the number of items added.",
            Set.of("src/main/java/com/shop/Basket.java"), Set.of(), List.of(), null, null, null,
            new SwarmPolicy(2, false, 0.2, 0.6, List.of()), TaskState.PENDING);
    }

    /** Rules of the three kinds a project holds, with the wording a real rule has. */
    private static void stateRules(ProjectRules rules) {
        String why = "A screen that reads the store directly sees a record half written, and "
            + "the next storage change breaks every screen at once.";
        for (int i = 1; i <= 4; i++) {
            rules.stateRule("Hard rule " + i, "Every read and write of a stored record goes "
                + "through the repository interfaces of the server module; a screen or a "
                + "service never opens the store itself, in any module, for any reason (" + i
                + ").", null, null, why, true);
        }
        for (int i = 1; i <= 5; i++) {
            rules.stateRule("Preference " + i, "Client screens take colour, spacing and type "
                + "from the shared design tokens and never write a literal value into a "
                + "component's own stylesheet; a token that is missing is added to the shared "
                + "catalogue with a sentence saying what it is for, so the next screen finds "
                + "it there (" + i + ").", null, null, "A screen that looks right today drifts "
                + "from the rest of the application tomorrow.", false);
        }
        for (int i = 1; i <= 3; i++) {
            rules.stateRule("Unclassified rule " + i, "A due date is computed by the server "
                + "from the loan's start and the configured period; the client shows what the "
                + "server sent and never computes a date of its own (" + i + ").", null, null);
        }
    }

    @Test
    void theOpeningKeepsWhatEveryCallNeedsAndNamesWhatALookupReturns() throws Exception {
        String rulesBefore;
        String rulesAfter;
        String onePreference;
        try (ArtifactStore store = new ArtifactStore(world.resolve("store"))) {
            Project project = store.ensureProject("shop", app.toString(), List.of());
            ProjectRules rules = new ProjectRules(store, project.id());
            stateRules(rules);
            rulesBefore = rules.briefingFor(List.of("src"), 12_000).text();
            rulesAfter = rules.briefingForWorkers(List.of("src"), 12_000).text();
            onePreference = rules.ruleByName("Preference 3");
            assertThat(rules.ruleByName("")).contains("- Hard rule 1", "- Preference 5",
                "- Unclassified rule 3");
            assertThat(rules.ruleByName("no such rule")).startsWith("No rule in force is called");
        }
        Task task = task();
        System.setProperty(Librarian.DOCUMENT_MAP_PROPERTY, "true");
        String briefBefore = librarian.assembleBrief(app, task).renderedMarkdown();
        System.clearProperty(Librarian.DOCUMENT_MAP_PROPERTY);
        String briefAfter = librarian.assembleBrief(app, task).renderedMarkdown();

        PromptBundle before = SwarmDispatcher.buildBundle(task, null, briefBefore, rulesBefore,
            List.of(), "");
        PromptBundle after = SwarmDispatcher.buildBundle(task, null, briefAfter, rulesAfter,
            List.of(), "");
        System.out.println("worker opening, characters, before -> after: rules "
            + rulesBefore.length() + " -> " + rulesAfter.length() + ", knowledge brief "
            + briefBefore.length() + " -> " + briefAfter.length() + ", whole "
            + before.sharedText().length() + " -> " + after.sharedText().length());

        // Every rule is still named, and a rule that can stop the work is still whole.
        for (int i = 1; i <= 4; i++) {
            assertThat(rulesAfter).contains("- Hard rule " + i, "never opens the store itself, "
                + "in any module, for any reason (" + i + ")");
        }
        assertThat(rulesAfter).contains(ConstraintBrief.HARD_RULE_LINE);
        for (int i = 1; i <= 3; i++) {
            assertThat(rulesAfter).as("nobody said it is a preference, so it is sent whole")
                .contains("- Unclassified rule " + i, "never computes a date of its own (" + i + ")");
        }
        for (int i = 1; i <= 5; i++) {
            assertThat(rulesAfter).contains("- Preference " + i)
                .doesNotContain("so the next screen finds it there (" + i + ")");
        }
        assertThat(rulesAfter).contains(ConstraintBrief.BY_NAME_LINE, "rule_of")
            .doesNotContain("Why it exists:");
        assertThat(rulesBefore).as("every other role reads the rules as before")
            .contains("Why it exists:", "so the next screen finds it there (3)");
        // What was taken out is one lookup away, as every other role reads it.
        assertThat(onePreference).contains("- Preference 3",
            "so the next screen finds it there (3)", "Why it exists: A screen that looks right");

        // The documents are counted in the brief and listed by the lookup.
        assertThat(briefBefore).contains("docs/persistence.md");
        assertThat(briefAfter).contains("Reference documentation you can read", " document(s). doc_outline",
            "doc_outline").doesNotContain("docs/persistence.md");
        WorkerLookups lookups = new WorkerLookups(librarian, app, false, null);
        assertThat(lookups.tree("doc_outline", "")).contains("docs/persistence.md",
            "docs/architecture.md");
        assertThat(after.sharedText()).contains("doc_outline with an empty string lists the "
            + "documents by name");

        assertThat(rulesAfter.length()).isLessThan(rulesBefore.length() * 2 / 3);
        assertThat(briefAfter.length()).isLessThan(briefBefore.length());
        assertThat(after.sharedText().length()).isLessThan(before.sharedText().length() * 3 / 4);
    }

    @Test
    void aCheckedRuleKeepsItsCommandAndAShortPreferenceStaysWhole() {
        LearnedGuideline checked = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            "no-sql", "The server module holds no SQL: every query is a method of the store, "
                + "and a string that reads like a statement fails the build of the module.",
            null, 1.0, null, 0, GuidelineStatus.ACTIVE, UUID.randomUUID(),
            "mvn -q -o -pl server verify -Pno-sql", 60);
        checked.setTitle("No SQL in the server");
        checked.setPurpose("The store is an object graph; SQL means a second database.");
        LearnedGuideline brief = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            "tabs", "Indent with four spaces.", null, 1.0, null, 0, GuidelineStatus.ACTIVE,
            UUID.randomUUID(), null, 0);
        brief.setTitle("Indentation");
        brief.setPurpose("One look for all files.");

        String sent = ConstraintBrief.renderForWorkers(List.of(checked, brief), 12_000);

        assertThat(sent).contains("- No SQL in the server", "fails the build of the module",
            "mvn -q -o -pl server verify -Pno-sql", "- Indentation", "Indent with four spaces.")
            .doesNotContain("Why it exists:");
        assertThat(ConstraintBrief.ruleOf(List.of(checked, brief), "no-sql"))
            .as("by its short name too").contains("Why it exists: The store is an object graph");
    }

    @Test
    void candidatesOfATaskAreGivenEachOthersLookupsAndTheirOpeningDoesNotMove() throws Exception {
        WorkerLookups lookups = new WorkerLookups(librarian, app, false, null);
        SharedAnswers answers = librarian.curator().answers();
        Task task = task();
        String opening = SwarmDispatcher.buildBundle(task, null, "the brief", "the rules",
            List.of(), "").sharedText();
        assertThat(lookups.answers("known_about")).isFalse();
        assertThat(lookups.answers("rule_of")).isFalse();

        // The first candidate looks the type up; its sibling asks the same.
        String first = lookups.tree("body_of", "com.shop.Basket#size");
        String shape = lookups.tree("shape_of", "com.shop.Basket");
        long computed = answers.asked() - answers.served();
        String sibling = lookups.tree("body_of", "com.shop.Basket#size");
        String siblingShape = lookups.tree("shape_of", "com.shop.Basket");

        assertThat(first).contains("return 0;", "src/main/java/com/shop/Basket.java");
        assertThat(sibling).isEqualTo(first);
        assertThat(siblingShape).isEqualTo(shape);
        assertThat(answers.asked() - answers.served()).as("nothing was computed for the sibling")
            .isEqualTo(computed);
        assertThat(answers.served()).isEqualTo(2);

        // A role's answer carries a role's addresses; a worker is never given it.
        ExpertTools planner = new ExpertTools(librarian.curator(), null, null,
            new CloudGate(0, null), MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User("planner", "hand in", 0, null, 0, 0));
        assertThat(planner.bodyOf("com.shop.Basket#size"))
            .contains("project/src/main/java/com/shop/Basket.java").isNotEqualTo(first);

        // The project's file changes (a task was merged): the next worker is not told the old.
        Path basket = app.resolve("src/main/java/com/shop/Basket.java");
        Files.writeString(basket, Files.readString(basket).replace("return 0;", "return 1;"));
        assertThat(lookups.tree("body_of", "com.shop.Basket#size")).contains("return 1;");

        // What the store holds never enters the opening: it is the same bytes for every
        // candidate, whatever has been looked up or kept since.
        planner.keepFinding("com.shop.Basket", "body_of com.shop.Basket#size",
            "A basket counts nothing yet.");
        assertThat(lookups.answers("known_about")).isTrue();
        assertThat(lookups.tree("known_about", "Basket")).contains("A basket counts nothing yet.",
            "found by: the planner");
        assertThat(SwarmDispatcher.buildBundle(task, null, "the brief", "the rules", List.of(), "")
            .sharedText()).isEqualTo(opening);

        lookups.rulesByName(name -> "the rule " + name);
        assertThat(lookups.answers("rule_of")).isTrue();
        assertThat(lookups.tree("rule_of", "Preference 3")).isEqualTo("the rule Preference 3");
    }
}
