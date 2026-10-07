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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.ArchDecision;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Requirement;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rubric design review is told the project's rules, and that they outrank the story goal's
 * wording about how anything is built.
 *
 * <p>Harness run 39, 2026-09-25 (and 37, 38). The goal said "save … to localStorage"; the rules
 * mandate EclipseStore on the server; the architect designed EclipseStore; and the reviewer — told
 * the goal but not the rules — objected that the design "replaces the explicit localStorage
 * persistence with server-side EclipseStore, deviating from the story's requirement".
 */
class TheDesignReviewerPutsRulesAboveTheGoalTest {

    private static final String RULES = "HOW THIS PROJECT MUST BE BUILT\n\n- Storage as an object "
        + "graph\n  Persistence uses EclipseStore through zerozstack-store-eclipsestore.\n";

    private static final String GOAL = "Persist books and ratings across browser restarts\n\n"
        + "Add a persistence layer that saves the current books and their ratings to localStorage "
        + "whenever they change.";

    @Test
    void withRulesTheReviewerReadsThemFirstAndIsToldTheyOutrankTheGoal() throws Exception {
        String conversation = reviewAndCapture(RULES);

        assertThat(conversation).contains("Critique the design against this rubric");
        assertThat(conversation).contains(DesignReviewerClient.RULES_OUTRANK_THE_GOAL.strip());
        assertThat(conversation).contains("NEVER object to a design for following a rule");
        assertThat(conversation.indexOf("Persistence uses EclipseStore"))
            .as("the rules come before the goal they outrank")
            .isGreaterThan(-1)
            .isLessThan(conversation.indexOf("Goal: Persist books"));
        assertThat(conversation)
            .as("never mistaken for the design-versus-rules call by anything routing on it")
            .doesNotContain("checking a DESIGN against this project's standing rules");
    }

    @Test
    void withoutRulesThePromptIsExactlyWhatItWas() throws Exception {
        String conversation = reviewAndCapture(null);

        assertThat(conversation).contains("Critique the design against this rubric");
        assertThat(conversation).contains("disjoint file ownership. Respond ONLY with JSON");
        assertThat(conversation).doesNotContain("OUTRANK");
        assertThat(conversation).contains("\nGoal: Persist books");
        assertThat(reviewAndCapture("   ")).isEqualTo(conversation);
    }

    private static String reviewAndCapture(String rules) throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                seen.add(conversation);
                return "{\"approved\": true, \"objections\": []}";
            })) {
            DesignReviewerClient reviewer = new DesignReviewerClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));
            DesignReviewerClient.Review review = reviewer.review(design(), rules);
            assertThat(review.approved).isTrue();
        }
        assertThat(seen).hasSize(1);
        return seen.get(0);
    }

    private static DesignDocument design() {
        return new DesignDocument(UUID.randomUUID(), 1, GOAL,
            List.of(new Requirement(UUID.randomUUID(),
                "Books and ratings are still there after the browser is restarted", Priority.HIGH)),
            List.of(new ArchDecision(UUID.randomUUID(), "Persist the object graph with EclipseStore "
                + "on the server", "the project's rules mandate it", List.of())),
            List.of(new ApiContract(UUID.randomUUID(), "BooksService", "persists books", "",
                "com.swarmcoder.demo.bookshelf.shared.BooksService",
                List.of("List<Book> getBooks()"))),
            List.of(), null, Instant.now());
    }
}
