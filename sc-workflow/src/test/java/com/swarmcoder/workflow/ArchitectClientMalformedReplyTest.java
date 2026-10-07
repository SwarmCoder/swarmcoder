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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.verify.BlobSink;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architect surviving a reply that will not parse — the run {@code 9e69a8ec…} incident
 * (2026-09-03), where a paid endpoint's revision reply was rejected for a single unexpected
 * field, and where a research-phase reply ran straight from its own tool line into the harness's
 * "RESULT for" framing with no newline in between, and the whole tail became a file path. Both
 * are exercised through the real call path — {@link ArchitectClient#design(String)} and the
 * research loop behind {@link ArchitectClient#design(String, StoryScope)} — never by calling a
 * private parser directly.
 */
class ArchitectClientMalformedReplyTest {

    @Test
    void aMalformedReplyIsRetriedOnceWithTheErrorFedBack() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondConversation = new AtomicReference<>("");
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                // Genuinely unparseable, even by LlmJson's own leniency (fences, unwrapping,
                // unknown fields) — this must fall through to the retry.
                return "Sorry, I cannot help with that.";
            }
            secondConversation.set(conversation);
            return """
                {"decisions":[{"decision":"Use a shared signal","rationale":"server-authoritative"}],
                 "contracts":[],"risks":[]}
                """;
        })) {
            ArchitectClient architect = new ArchitectClient(
                new VllmClient(llm.baseUrl(), null, "test-model", true),
                new CloudGate(1_000_000, null));

            DesignDocument design = architect.design("Show a live board");

            assertThat(calls.get()).as("exactly one retry, not more").isEqualTo(2);
            assertThat(design).isNotNull();
            assertThat(design.decisions()).hasSize(1);
            // The retry carries the bad reply and the parser's own complaint back to the model,
            // rather than just asking the same question again.
            assertThat(secondConversation.get())
                .contains("Sorry, I cannot help with that.")
                .contains("That was not valid JSON")
                .contains("Reply with only the JSON object");
        }
    }

    @Test
    void aSecondMalformedReplyIsFinalAndKeptAsABlob() throws Exception {
        List<byte[]> stored = new ArrayList<>();
        BlobSink capture = content -> {
            stored.add(content);
            return "deadbeef";
        };
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            calls.incrementAndGet();
            return "still not json, even after being told so";
        })) {
            ArchitectClient architect = new ArchitectClient(
                new VllmClient(llm.baseUrl(), null, "test-model", true),
                new CloudGate(1_000_000, null), null, ArchitectResearch.NONE, capture);

            DesignDocument design = architect.design("Show a live board");

            // The architect's own catch degrades to null on a design failure — this asserts the
            // retry-then-give-up path completes cleanly rather than throwing out of design().
            assertThat(design).isNull();
            assertThat(calls.get()).as("one call, one retry, then stop").isEqualTo(2);
            assertThat(stored).hasSize(1);
            assertThat(new String(stored.get(0), StandardCharsets.UTF_8))
                .isEqualTo("still not json, even after being told so");
        }
    }

    /**
     * The exact shape from the 07:10:02 research failure: the model's reply for
     * {@code TOOL read_file <path>} ran straight into the harness's own "RESULT for `TOOL ...`:"
     * framing on the same line, no newline in between — the model kept generating instead of
     * stopping, and effectively predicted its own next prompt. Before the fix, the whole tail
     * became part of the path argument and the read failed on a string that was never a file
     * address; the path passed to {@code read_file} must be exactly the file, nothing appended.
     */
    @Test
    void aToolLineIsNotSwallowedByASelfEchoedResultMarker() throws Exception {
        FakeResearch research = new FakeResearch();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            if (conversation.contains("RESULT for")) {
                return "NOTES: nothing further needed.";
            }
            // No newline between the tool line and the harness's own result framing — reproduced
            // verbatim from the incident log.
            return "TOOL read_file zeroz4j/docs/UI_COMPONENTS.md"
                + "RESULT for `TOOL read_file zeroz4j/docs/UI_COMPONENTS.md`:";
        })) {
            ArchitectClient architect = new ArchitectClient(
                new VllmClient(llm.baseUrl(), null, "test-model", true),
                new CloudGate(1_000_000, null),
                new SwarmPolicy(1, false, 0.2, 0.2, List.of()), research);

            architect.design("Show a live board", scope());

            assertThat(research.calls)
                .containsExactly("read_file:zeroz4j/docs/UI_COMPONENTS.md");
        }
    }

    /** Records what was asked of it and answers from a fixed map — mirrors ArchitectResearchTest. */
    private static final class FakeResearch implements ArchitectResearch {
        final List<String> calls = new ArrayList<>();

        @Override
        public String reference(int maxChars) {
            return "zeroz4j is a Java-to-Wasm RMI framework. Services are @RmiService.";
        }

        @Override
        public String lookupApi(String query) {
            calls.add("lookup_api:" + query);
            return "";
        }

        @Override
        public String searchCode(String query) {
            calls.add("search_code:" + query);
            return "";
        }

        @Override
        public String readFile(String address) {
            calls.add("read_file:" + address);
            return "public final class Signals {}";
        }

        @Override
        public String listFolder(String address) {
            calls.add("list_folder:" + address);
            return "";
        }
    }

    /** A one-criterion scope, so the scoped design path — the one with a research phase — runs. */
    private static StoryScope scope() {
        var requirement = new com.swarmcoder.domain.BrdRequirement(java.util.UUID.randomUUID(),
            "R7", "Live board", "The board updates without a refresh",
            com.swarmcoder.domain.Priority.HIGH,
            com.swarmcoder.domain.RequirementStatus.ACTIVE, null);
        var criterion = new com.swarmcoder.domain.AcceptanceCriterion(java.util.UUID.randomUUID(),
            "the board updates without a refresh", "BoardTest#live");
        criterion.setStatus(com.swarmcoder.domain.CriterionStatus.ACCEPTED);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        var brd = new com.swarmcoder.domain.Brd(java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(), 1, "BRD", new ArrayList<>(List.of(requirement)),
            new ArrayList<>(), java.time.Instant.now(), java.time.Instant.now());
        var story = new com.swarmcoder.domain.Story(java.util.UUID.randomUUID(), brd.projectId(),
            "S1", com.swarmcoder.domain.StoryKind.DELIVERY, "Live board", null,
            com.swarmcoder.domain.StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0,
            com.swarmcoder.domain.StoryOrigin.BACKLOG, null, null, "human", new ArrayList<>(),
            null, null, null, null, java.time.Instant.now(), java.time.Instant.now());
        return StoryScope.resolve(brd, story);
    }
}
