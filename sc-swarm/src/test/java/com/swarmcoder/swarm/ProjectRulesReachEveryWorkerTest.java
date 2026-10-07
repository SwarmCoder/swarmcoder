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

import com.swarmcoder.domain.ConstraintBrief;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.runtime.PromptBundle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last place the project's rules can still be said, and until 2026-08-31 nowhere said them.
 *
 * <p>A worker is handed a task, a write set and a checkout, and is otherwise on its own: it works
 * out the stack from whatever it can see, and where that is not enough it uses the stack a project
 * of that shape usually has. That is not a fault in the worker — nothing had ever told it. This
 * asserts that the rules are in the SHARED prefix every worker of a group receives, above the task,
 * and that a project with no rules gets the byte-identical prompt it always got.
 *
 * <p>The rules are {@link LearnedGuideline}s: one mechanism, one segment, one rendering. For a few
 * hours they were a second kind of BRD requirement in a second prompt segment beside this one,
 * which would have sent every rule to every worker twice.
 */
class ProjectRulesReachEveryWorkerTest {

    @Test
    void everyWorkerIsToldHowTheProjectMustBeBuiltBeforeItIsToldItsTask() {
        String rules = ConstraintBrief.render(bookshelfRules());
        assertThat(rules).isNotEmpty();

        PromptBundle bundle = SwarmDispatcher.buildBundle(task(), null, null, rules, List.of());

        String shared = bundle.sharedText();
        assertThat(shared).contains("PROJECT_CONSTRAINTS")
            .contains("Do not use Spring, JPA, Flyway or SQL")
            .contains("every level of nesting you changed needs its own save call");
        assertThat(shared.indexOf("PROJECT_CONSTRAINTS"))
            .describedAs("a worker that reads its task first has already picked its frameworks")
            .isLessThan(shared.indexOf("TASK_INSTRUCTIONS"));

        // The shared prefix is what every worker in the group gets, byte for byte — which is the
        // whole point of it: the rules cannot reach four workers and miss the fifth.
        assertThat(bundle.forWorker("minimal-diff", null)).startsWith(shared);
        assertThat(bundle.forWorker("defensive-edges", null)).startsWith(shared);
    }

    /**
     * A rule that declares a proof command says so, in the words the worker reads.
     *
     * <p>An unstated check is a trap: the command decides whether the candidate survives, and a
     * worker that was never told it can run the command itself is a worker that finds out by
     * failing.
     */
    @Test
    void aRuleThatIsCheckedSaysSo() {
        LearnedGuideline checked = rule("offline-build",
            "The build runs offline from the repository root.",
            "mvn -o -q -DskipTests package");

        String shared = SwarmDispatcher.buildBundle(task(), null, null,
            ConstraintBrief.render(List.of(checked)), List.of()).sharedText();

        assertThat(shared).contains("This rule is CHECKED")
            .contains("mvn -o -q -DskipTests package");
    }

    /**
     * A project with no rules must produce the prompt it produced before rules existed — same
     * bytes, therefore the same prefix hash, therefore a prefill cache that stays warm.
     */
    @Test
    void aProjectWithNoRulesGetsTheExactPromptItAlwaysGot() {
        PromptBundle without = SwarmDispatcher.buildBundle(task(), null, null, null, List.of());
        PromptBundle blank = SwarmDispatcher.buildBundle(task(), null, null, "", List.of());
        PromptBundle empty = SwarmDispatcher.buildBundle(task(), null, null,
            ConstraintBrief.render(List.of()), List.of());

        assertThat(blank.prefixHash()).isEqualTo(without.prefixHash());
        assertThat(empty.prefixHash()).isEqualTo(without.prefixHash());
        assertThat(without.sharedText()).doesNotContain("PROJECT_CONSTRAINTS");
    }

    /** Two of the real rules from {@code dev/bookshelf-tech-requirements.md}. */
    private static List<LearnedGuideline> bookshelfRules() {
        return List.of(
            rule("what-this-project-must-not-use", "What this project must not use",
                "Do not use Spring, JPA, Flyway or SQL migrations, a relational database, "
                    + "REST or JSON, JavaScript, or Vaadin.", null),
            rule("storage-is-an-object-graph", "Storage is an object graph",
                "Persistence is EclipseStore. Saving an object does not save the objects "
                    + "inside it — every level of nesting you changed needs its own save call, "
                    + "and getting it wrong loses the edit silently.", null));
    }

    private static LearnedGuideline rule(String slug, String body, String check) {
        return rule(slug, null, body, check);
    }

    private static LearnedGuideline rule(String slug, String title, String body, String check) {
        LearnedGuideline g = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            slug, body, new Provenance("stated", null, "bookshelf-tech-requirements.md"), 1.0,
            Instant.now(), 0, GuidelineStatus.ACTIVE, UUID.randomUUID(), check, 0);
        g.setTitle(title);
        return g;
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Add the reading list", "List a reader's books.",
            Set.of("bookshelf-demo-server/src/main/java"), Set.of(), List.of(),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(2, false, 0.2, 0.6, List.of()), TaskState.PENDING);
    }
}
