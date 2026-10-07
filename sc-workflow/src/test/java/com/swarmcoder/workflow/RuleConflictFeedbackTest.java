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
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.Librarian;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit coverage of {@link RuleConflictFeedback} — the piece that turns a bare "conflicts with
 * rule '&lt;title&gt;'" objection into the rule's own wording plus the nearest existing code, so an
 * architect asked to revise a second time is not just told "no" again in different words (harness
 * run 19, 2026-09-04).
 */
class RuleConflictFeedbackTest {

    @TempDir
    Path repo;
    @TempDir
    Path primerCache;

    private static final String RULES_BRIEF = ConstraintBrief.render(List.of(
        rule("Storage is an object graph",
            "Persistence uses EclipseStore through zerozstack-store-eclipsestore. The server "
                + "keeps the live Java objects in memory and writes that object graph to disk. "
                + "Nothing is ever persisted in the browser.")));

    @Test
    void detectsTheSharedObjectionShapeAndExtractsTheRuleTitle() {
        String objection = "task 'Add client persistence' conflicts with rule "
            + "'Storage is an object graph': it stores ratings in the browser";

        assertThat(RuleConflictFeedback.isRuleConflict(objection)).isTrue();
        assertThat(RuleConflictFeedback.ruleTitleOf(objection)).isEqualTo("Storage is an object graph");
    }

    @Test
    void anObjectionThatNamesNoRuleIsNotRuleConflictShaped() {
        String objection = "no overflow contract";

        assertThat(RuleConflictFeedback.isRuleConflict(objection)).isFalse();
        assertThat(RuleConflictFeedback.ruleTitleOf(objection)).isNull();
    }

    @Test
    void aNonRuleObjectionIsCarriedThroughUnchanged() {
        List<String> enriched = RuleConflictFeedback.enrich(
            List.of("no overflow contract"), RULES_BRIEF, null);

        assertThat(enriched).containsExactly("no overflow contract");
    }

    /** No librarian wired: the rule's own wording still rides along, just no example. */
    @Test
    void withNoLibrarianTheRuleExcerptStillRidesAlongButNoExample() {
        String objection = "task 'Add client persistence' conflicts with rule "
            + "'Storage is an object graph': it stores ratings in the browser";

        List<String> enriched = RuleConflictFeedback.enrich(List.of(objection), RULES_BRIEF, null);

        assertThat(enriched).hasSize(1);
        assertThat(enriched.get(0))
            .as("the original objection is kept, in full")
            .contains(objection)
            .as("the rule's own body is quoted back, not just its title")
            .contains("zerozstack-store-eclipsestore")
            .as("no librarian was wired, so no example section is added")
            .doesNotContain("NEAREST EXISTING EXAMPLE");
    }

    /**
     * With a librarian wired at a folder that holds a file matching the rule's subject, the
     * enriched objection carries the nearest existing example — a real path and a quote of it —
     * not just the rule's own wording.
     */
    @Test
    void withALibrarianWiredTheNearestExistingExampleIsAppended() throws Exception {
        Files.writeString(repo.resolve("Storage.java"), """
            package com.example.store;

            /** How this project keeps its object graph — through EclipseStore, on the server. */
            public class Storage {
                public void save() {
                    // persist through EclipseStore
                }
            }
            """);
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(repo), null, null, primerCache);

        String objection = "task 'Add client persistence' conflicts with rule "
            + "'Storage is an object graph': it stores ratings in the browser";
        List<String> enriched = RuleConflictFeedback.enrich(List.of(objection), RULES_BRIEF, librarian);

        assertThat(enriched).hasSize(1);
        assertThat(enriched.get(0))
            .contains(objection)
            .contains("zerozstack-store-eclipsestore")
            .as("the nearest existing example names a real file")
            .contains("## Source: ")
            .contains("Storage.java")
            .as("and quotes enough of it to see the idiom")
            .contains("EclipseStore");
    }

    private static LearnedGuideline rule(String title, String body) {
        LearnedGuideline g = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            "rule", body, new Provenance("stated", null, "tech-requirements.md"), 1.0,
            Instant.now(), 0, GuidelineStatus.ACTIVE, UUID.randomUUID(), null, 0);
        g.setTitle(title);
        return g;
    }
}
