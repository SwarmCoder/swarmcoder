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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ClusterId;
import com.swarmcoder.domain.Task;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second defect this branch fixes (harness run 13's wave-2 selection). A judge with no access
 * to the project's own documentation invented a framework fact — "an {@code EmbeddedStorageManager}
 * that is not a valid CDI bean in this stack" — that the project's own persistence guide
 * contradicts on its first page ({@code @Inject private EmbeddedStorageManager storage;}), and it
 * picked the candidate that keeps books in memory and never saves over the one that follows the
 * guide.
 *
 * <p>The fix hands the judge the SAME "Documentation relevant to this task" slice
 * {@code KnowledgeCurator} already chose for the worker's own knowledge brief — re-read out of
 * that already-rendered brief text, not re-selected — so the judge is reading the same authority
 * the worker was, and not guessing from training data.
 */
class JudgeSeesTheDocumentationTest {

    private static final String OK = "{\"score\": 0.8, \"rationale\": \"fine\"}";

    /**
     * The fixture's guide: a persistence guide's "Saving data" section, carrying the exact
     * sentence the judge contradicted in harness run 13 — {@code EmbeddedStorageManager} IS the
     * documented, injectable way to save.
     */
    private static final String PERSISTENCE_GUIDE_SECTION =
        "#### project/docs/guides/persistence.md › Saving data\n"
        + "Persistence is provided by an EclipseStore `EmbeddedStorageManager`. It IS a valid CDI "
        + "bean in this stack, produced by the framework's own producer:\n\n"
        + "```java\n"
        + "@Inject\n"
        + "private EmbeddedStorageManager storage;\n\n"
        + "void save(Book book) {\n"
        + "    shelf.books().add(book);\n"
        + "    storage.storeAll(shelf, shelf.books());\n"
        + "}\n"
        + "```\n"
        + "Store every nesting level you changed — a store call does not cascade into an object "
        + "that is already persisted.\n";

    /** A worker's knowledge brief, shaped exactly the way {@code Librarian.assembleBrief} renders it. */
    private static String knowledgeBriefFixture() {
        return "### Available libraries (exact versions — use these APIs, do not invent)\n"
            + "- com.zeroz4j:zeroz4j-store 0.8.0\n\n"
            + "### Conventions, rules & best practices (curated — follow these)\n"
            + "Keep entities small.\n\n"
            + "### Reference documentation you can read (call lookup_api with a topic — it "
            + "searches ALL of these; do NOT decompile jars to find an API)\n"
            + "- project/docs/guides/persistence.md — Persistence guide\n\n"
            + "### Documentation relevant to this task (authoritative — follow it)\n"
            + PERSISTENCE_GUIDE_SECTION
            + "\n## Framework primer: zeroz4j\n"
            + "Purpose: an embedded object-graph store.\n\n"
            + "### Reference sources relevant to this task (read-only — real code, use these APIs)\n"
            + "## Source: project/BookStorage.java\n```java\nclass BookStorage {}\n```\n";
    }

    private static Task task() {
        Task t = new Task();
        t.setId(UUID.randomUUID());
        t.setTitle("Save a book to the shelf");
        t.setInstructions("Add the book and persist the shelf.");
        return t;
    }

    private static CandidateSolution candidate() {
        return new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 0, "swarm/w0", null,
            "--- a/BookStorage.java\n+++ b/BookStorage.java\n+class BookStorage {}\n", null,
            new ClusterId("hash", 1), null, CandidateState.SURVIVED, null);
    }

    @Test
    void theJudgeIsGivenTheSameDocumentationSliceTheWorkerWas() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(OK))) {
            new JudgeClient(new VllmClient(fake.baseUrl(), "", "fake-judge", true), null,
                null, null, knowledgeBriefFixture()).judge(candidate(), task());

            assertThat(fake.requests).hasSize(1);
            String sent = fake.requests.get(0);
            assertThat(sent)
                .as("the judge must be told the documentation is authoritative")
                .contains("DOCUMENTATION FOR THIS TASK: The framework's own documentation for "
                    + "this task. When your opinion of an API disagrees with it, the "
                    + "documentation is right.")
                .as("and must actually carry the guide's own words, not just the claim")
                .contains("EmbeddedStorageManager storage")
                .contains("Store every nesting level you changed");
        }
    }

    /** No brief wired in — the brief degrades to exactly what it was before this fix. */
    @Test
    void withNoKnowledgeBriefWiredInTheJudgeIsUnchanged() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(OK))) {
            new JudgeClient(new VllmClient(fake.baseUrl(), "", "fake-judge", true))
                .judge(candidate(), task());

            assertThat(fake.requests.get(0)).doesNotContain("DOCUMENTATION FOR THIS TASK");
        }
    }

    @Test
    void aBriefWithNoMatchingSectionProducesNoDocumentationBlock() {
        assertThat(JudgeClient.documentationSection(null)).isEmpty();
        assertThat(JudgeClient.documentationSection("no matching headings in this brief")).isEmpty();
    }

    /** The number promised in the brief: measured, not asserted on faith. */
    @Test
    void theDocumentationSliceOnTheFixtureIsWellUnder1500Tokens() {
        String section = JudgeClient.documentationSection(knowledgeBriefFixture());

        assertThat(section).isNotEmpty();
        assertThat(CloudGate.estimateTokens(section))
            .as("measured on the fixture guide with CloudGate.estimateTokens")
            .isLessThan(1500);
    }

    /**
     * The cap has to hold even when a project's matched section is huge — a guide page is not
     * bounded by anything the judge controls.
     */
    @Test
    void anOversizedDocumentationSectionIsStillCappedUnder1500Tokens() {
        String hugeBrief = "### Documentation relevant to this task (authoritative — follow it)\n"
            + "#### project/docs/guides/persistence.md › Saving data\n"
            + "word ".repeat(4_000) // ~20,000 characters — far past the per-slice cap
            + "\n## Framework primer: zeroz4j\nirrelevant\n";

        String section = JudgeClient.documentationSection(hugeBrief);

        assertThat(section).isNotEmpty();
        assertThat(CloudGate.estimateTokens(section))
            .as("the cap must hold even for a guide the judge did not choose the size of")
            .isLessThan(1500);
    }
}
