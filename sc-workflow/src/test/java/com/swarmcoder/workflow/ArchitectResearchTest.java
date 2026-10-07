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
import com.swarmcoder.inference.EndpointOutage;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Architect learning a framework it has never seen from the project's read-only context
 * folders, before it designs against it.
 *
 * <p>The research phase is deliberately separate from the design call: the design is
 * schema-constrained, so the model physically cannot emit a tool line inside it. These tests pin
 * that separation, the tool plumbing, and the two ways research can fail: research that RUNS and
 * finds nothing degrades to designing from the primer, while research that cannot reach the model at
 * all is an outage the caller must be allowed to wait out (UX v3 §2.4).
 */
class ArchitectResearchTest {

    /** Records what was asked of it and answers from a fixed map. */
    private static final class FakeResearch implements ArchitectResearch {
        final List<String> calls = new ArrayList<>();
        String reference = "zeroz4j is a Java-to-Wasm RMI framework. Services are @RmiService.";

        @Override
        public String reference(int maxChars) {
            return reference;
        }

        @Override
        public String lookupApi(String query) {
            calls.add("lookup_api:" + query);
            return "Signals.shared(String key, T initial) -> ValueSignal<T>";
        }

        @Override
        public String searchCode(String query) {
            calls.add("search_code:" + query);
            return "BinarySerializer.java:107 writeValue(...)";
        }

        @Override
        public String readFile(String address) {
            calls.add("read_file:" + address);
            return "public final class Signals { public static <T> ValueSignal<T> shared(...) }";
        }

        @Override
        public String listFolder(String address) {
            calls.add("list_folder:" + address);
            return "Signals.java\nValueSignal.java";
        }
    }

    @Test
    void theArchitectResearchesTheFrameworkBeforeDesigning() throws Exception {
        FakeResearch research = new FakeResearch();
        AtomicReference<String> designPrompt = new AtomicReference<>("");
        // The scripted model issues one tool call, then reports notes, then designs.
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            if (conversation.contains("RESULT for")) {
                return "NOTES: shared signals are created with Signals.shared(key, initial) and "
                    + "returned as ValueSignal<T>.";
            }
            if (conversation.contains("research what you do not know")) {
                return "TOOL lookup_api how do I create a shared signal";
            }
            designPrompt.set(conversation);
            return """
                {"decisions":[{"decision":"Use a shared signal for the board","rationale":"server-authoritative"}],
                 "contracts":[],"risks":[],"missingRequirements":[]}
                """;
        })) {
            var scoped = architect(llm, research).design("Show a live board", scope());

            // It used the tool…
            assertThat(research.calls).containsExactly("lookup_api:how do I create a shared signal");
            // …and what it learned reached the design call, so the design is grounded in the real
            // API rather than in a guess about it.
            assertThat(designPrompt.get()).contains("WHAT YOU ESTABLISHED");
            assertThat(designPrompt.get()).contains("Signals.shared(key, initial)");
            assertThat(scoped.design().decisions()).hasSize(1);
        }
    }

    @Test
    void researchIsSkippedEntirelyWhenThereIsNoReferenceMaterial() throws Exception {
        FakeResearch research = new FakeResearch();
        research.reference = "";   // no context folders configured
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            calls.updateAndGet(n -> n + 1);
            return """
                {"decisions":[],"contracts":[],"risks":[],"missingRequirements":[]}
                """;
        })) {
            architect(llm, research).design("Show a live board", scope());

            // Exactly one model round trip: no research phase, no wasted latency, no tools.
            assertThat(calls.get()).isEqualTo(1);
            assertThat(research.calls).isEmpty();
        }
    }

    @Test
    void anUnparseableToolLineIsAnsweredRatherThanDerailingTheDesign() throws Exception {
        FakeResearch research = new FakeResearch();
        AtomicReference<String> designPrompt = new AtomicReference<>("");
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            if (conversation.contains("unknown tool")) {
                return "NOTES: nothing further needed.";
            }
            if (conversation.contains("research what you do not know")) {
                return "TOOL summon_daemon everything";
            }
            designPrompt.set(conversation);
            return """
                {"decisions":[],"contracts":[],"risks":[],"missingRequirements":[]}
                """;
        })) {
            var scoped = architect(llm, research).design("Show a live board", scope());

            // The model is told what it did wrong and carries on; a bad tool name must not cost
            // the design.
            assertThat(scoped.design()).isNotNull();
            assertThat(designPrompt.get()).contains("WHAT YOU ESTABLISHED");
        }
    }

    @Test
    void researchAgainstADeadEndpointIsAnOutage_notADesignWithoutResearch() {
        FakeResearch research = new FakeResearch();
        // Nothing listening: research cannot run at all.
        ArchitectClient architect = new ArchitectClient(
            new VllmClient("http://localhost:1", null, "test-model", true),
            new CloudGate(1_000_000, null),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()),
            research);

        // This used to proceed with a requirements-only design. Research failing because the model is
        // unreachable is not the same as research finding nothing: the design call that comes next
        // needs the same endpoint and would fail the same way, so the honest move is to say the
        // endpoint is down and let the workflow wait for it.
        assertThatThrownBy(() -> architect.design("Show a live board", scope()))
            .isInstanceOf(EndpointOutage.class);
    }

    /** Research that runs but yields nothing usable still must not lose the run. */
    @Test
    void researchThatAnswersUselesslyStillProducesADesign() throws Exception {
        FakeResearch research = new FakeResearch();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "NOTES: nothing useful found.")) {
            var scoped = architect(llm, research).design("Show a live board", scope());

            // Losing the run because the reference lookup found nothing would be far worse than
            // designing from the requirements alone.
            assertThat(scoped.design()).isNotNull();
            assertThat(scoped.design().requirements()).hasSize(1);
        }
    }

    private static ArchitectClient architect(ScriptedLlm llm, ArchitectResearch research) {
        return new ArchitectClient(
            new VllmClient(llm.baseUrl(), null, "test-model", true),
            new CloudGate(1_000_000, null),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()),
            research);
    }

    /** A one-criterion scope, so the scoped design path is the one under test. */
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
