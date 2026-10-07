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
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>On a change to code that already exists, the architect states a contract for every type the
 * change touches — and the code it is about reaches the brief.</b>
 *
 * <p>These are two prompt lines, and a prompt line is exactly the kind of change that can be true
 * in the source and absent from the wire: a flag nobody reads, a brief assembled and dropped. So
 * both are read off the conversation the model was actually sent.
 *
 * <h2>Why the contract line is worth anything</h2>
 *
 * <p>Left to itself the architect states contracts for the types a plan will CREATE. On a
 * greenfield build that is every type there is; on a change to an existing repository it is usually
 * none of them, so the design comes back with an empty contract list. {@code Librarian.workedExample}
 * short-circuits on a task delivering no contract, and the whole worked-example channel then
 * collapses into a documentation guess — the channel the unseen-code experiment measured as the
 * difference between 467 turns and zero files written, and 34 turns and green.
 *
 * <h2>And why greenfield must not move by a character</h2>
 *
 * <p>The design prompt sits in a shared prefill prefix. Adding a sentence to every build's prompt
 * to serve one kind of run would cost every greenfield project a cold cache for nothing, so the
 * greenfield prompt is asserted here to be byte-for-byte what it was.
 */
class AChangeToExistingCodeContractsEveryTypeItTouchesTest {

    @Test
    void theArchitectIsToldToContractEveryTypeAChangeTouches() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>("");
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            sent.set(conversation);
            return "{\"decisions\":[],\"contracts\":[],\"risks\":[],\"missingRequirements\":[]}";
        })) {
            architect(llm).design("Setting charset on an empty XML document throws", scope(), true);

            assertThat(sent.get())
                .as("the architect must be told this is a change to code that already exists")
                .contains("THIS CHANGE IS AGAINST A CODEBASE THAT ALREADY EXISTS")
                .contains("the ones that already exist as much as the ones you are creating")
                .as("and told how to say which is which, so the planner and the red-check can "
                    + "tell a type to modify from a type to create")
                .contains("EXISTING:")
                .contains("NEW:");
            assertThat(sent.get())
                .as("the rule that makes the design a vocabulary is not replaced by the new one")
                .contains("EVERY TYPE AN ACCEPTANCE TEST WILL TOUCH MUST BE A CONTRACT HERE");
        }
    }

    @Test
    void aGreenfieldDesignPromptIsByteForByteWhatItWas() throws Exception {
        AtomicReference<String> greenfield = new AtomicReference<>("");
        AtomicReference<String> change = new AtomicReference<>("");
        String reply = "{\"decisions\":[],\"contracts\":[],\"risks\":[],\"missingRequirements\":[]}";

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            greenfield.set(conversation);
            return reply;
        })) {
            architect(llm).design("Guests can pay", scope());
        }
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            change.set(conversation);
            return reply;
        })) {
            architect(llm).design("Guests can pay", scope(), true);
        }

        assertThat(greenfield.get())
            .as("nothing about the existing-code rule may reach a greenfield build's prompt: it "
                + "sits in a shared prefill prefix that every worker of every run pays for")
            .doesNotContain("THIS CHANGE IS AGAINST A CODEBASE THAT ALREADY EXISTS");
        assertThat(change.get().length())
            .as("and the only difference between the two is that one sentence")
            .isEqualTo(greenfield.get().length()
                + ArchitectClient.EXISTING_CODE_CONTRACT_RULE.length() + 1);
    }

    /**
     * The code the change is about reaches the design roles' brief — under a heading that says
     * where it came from, and with the sentence that turns "which tests already cover this" into
     * an instruction rather than a fact.
     */
    @Test
    void theCodeTheChangeIsAboutReachesTheDesignBrief() {
        String neighbourhood = """
            Types this report is about:
            - org.jsoup.nodes.Document (class) — src/main/java/org/jsoup/nodes/Document.java:31

            Tests that already cover this area:
            - src/test/java/org/jsoup/nodes/DocumentTest.java""";

        String bugfix = RunBrief.forKind(WorkflowKind.BUGFIX, "charset() throws", neighbourhood);
        assertThat(bugfix)
            .contains("charset() throws")
            .contains("THIS IS A BUGFIX RUN")
            .contains(RunBrief.NEIGHBOURHOOD_HEADING)
            .contains("src/main/java/org/jsoup/nodes/Document.java:31")
            .as("naming the tests that already cover the area is the most valuable line in it, "
                + "and it has to be an instruction rather than a list")
            .contains("they are the nearest worked example there is");

        assertThat(RunBrief.forKind(WorkflowKind.ENHANCEMENT, "text() needs a space", neighbourhood))
            .as("an enhancement against existing code is as much a change as a bugfix is")
            .contains(RunBrief.NEIGHBOURHOOD_HEADING);
    }

    /**
     * Fail open, everywhere. A greenfield project's structural index knows none of the types a goal
     * string names, so the neighbourhood comes back empty — and an empty one must leave every brief
     * exactly as it was, rather than leaving a heading above nothing.
     */
    @Test
    void nothingChangesWhenThereIsNoNeighbourhoodToShow() {
        for (String nothing : new String[] {null, "", "   "}) {
            assertThat(RunBrief.forKind(WorkflowKind.BUGFIX, "the login button does nothing", nothing))
                .isEqualTo(RunBrief.forKind(WorkflowKind.BUGFIX, "the login button does nothing"));
            assertThat(RunBrief.forKind(WorkflowKind.GREENFIELD, "add a multiply method", nothing))
                .isEqualTo("add a multiply method");
        }
    }

    /**
     * A refactor's brief is untouched (design §6). Its promise is that behaviour did not change and
     * its criteria are structural; the types its sentence happens to name are not what shapes it.
     */
    @Test
    void aRefactorsBriefDoesNotCarryTheNeighbourhood() {
        String withIt = RunBrief.forKind(WorkflowKind.REFACTOR, "split the giant service class",
            "Types this report is about:\n- com.acme.Giant (class) — src/main/java/Giant.java:9");
        assertThat(withIt)
            .isEqualTo(RunBrief.forKind(WorkflowKind.REFACTOR, "split the giant service class"))
            .doesNotContain("Giant.java:9");
    }

    // --- fixture ---------------------------------------------------------------------------

    private static ArchitectClient architect(ScriptedLlm llm) {
        return new ArchitectClient(
            new VllmClient(llm.baseUrl(), null, "test-model", true),
            new CloudGate(1_000_000, null),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()));
    }

    /** One agreed requirement with one accepted check — the shape a change request produces. */
    private static StoryScope scope() {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R1",
            "charset on an empty XML document", "Calling charset() on an empty XML document "
            + "throws; it must complete and report the charset", Priority.HIGH,
            RequirementStatus.ACTIVE, null);
        AcceptanceCriterion check = new AcceptanceCriterion(UUID.randomUUID(),
            "charset(UTF_8) completes and charset() answers UTF_8",
            "swarm.accept.Issue2266Test#charsetOnAnEmptyXmlDocument");
        check.setStatus(CriterionStatus.ACCEPTED);
        requirement.setCriteria(new ArrayList<>(List.of(check)));

        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Business Requirements",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now());
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "charset on an empty XML document", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(check.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        return StoryScope.resolve(brd, story);
    }
}
