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
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a role handed in and was sent back for goes to the SAME conversation (section 54): its
 * history, with every lookup it made, is still there, so the role does not read the project again.
 * Run 80 paid about 690,000 of 2,237,000 tokens for new sessions that did.
 *
 * <p>The model is scripted ({@link ScriptedAgentLlm}); the agent runtime, the lookups and the
 * tools are the real ones. Covered: the architect (a design sent back by review) and the test
 * author (a test sent back as broken). The planner shares the architect's path
 * ({@code ArchitectClient.firstReply}); what its retry sends is {@code retryFeedback}.
 */
class ARejectedHandInContinuesTheSameConversationTest {

    @TempDir
    Path app;
    @TempDir
    Path cache;

    @BeforeEach
    void world() throws Exception {
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(app.resolve("src/main/java/com/acme/shop/Catalog.java"), """
            package com.acme.shop;

            import java.util.List;

            public class Catalog {
                public List<String> titles() { return List.of(); }
            }
            """);
    }

    @Test
    void aDesignSentBackIsDeliveredToTheArchitectsOwnConversationWithItsLookupsIntact()
            throws Exception {
        AtomicInteger oneReplies = new AtomicInteger();
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> {
                    oneReplies.incrementAndGet();
                    return design("com.acme.oneshot.Wrong");
                },
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("read_file",
                        Map.of("path", "project/src/main/java/com/acme/shop/Catalog.java"));
                    case 2 -> ScriptedAgentLlm.Turn.call("check_design",
                        Map.of("design", design("com.acme.shop.Rating")));
                    case 3 -> ScriptedAgentLlm.Turn.call("report_done", Map.of("finalJson", ""));
                    // The continued conversation: a corrected design, checked and handed in.
                    case 4 -> ScriptedAgentLlm.Turn.call("check_design",
                        Map.of("design", design("com.acme.shop.Rating")));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("finalJson", ""));
                })) {
            VllmClient client = new VllmClient(llm.baseUrl(), null, "scripted", true);
            CloudGate gate = new CloudGate(10_000_000, null);
            ArchitectClient architect = new ArchitectClient(client, gate);
            architect.setLookupAgent(new LookupAgent(curator(), null, app, gate,
                new KoogAgentRuntime(), null, null));

            try (ArchitectClient.Scope checking = architect.checkingDraftsWith(noObjections())) {
                DesignDocument design = architect.design("Let a customer rate a title");
                assertThat(design).isNotNull();
                assertThat(llm.sessionRequests).as("the first session: a lookup, a draft, hand-in")
                    .hasSize(3);

                ArchitectClient.ReviseAttempt attempt = architect.revise(design,
                    List.of("the rating needs a range, say it in the contract"));

                assertThat(attempt.design()).isNotNull();
            }

            assertThat(llm.sessionRequests)
                .as("the revision was two more turns of the SAME session, not a new one")
                .hasSize(5);
            String continued = llm.sessionRequests.get(3);
            assertThat(continued)
                .as("the lookup the architect made in its first turns is still in the conversation")
                .contains("public List<String> titles()");
            assertThat(continued)
                .as("and what it is told is that this is the same conversation, with the objection")
                .contains("THIS IS THE SAME CONVERSATION")
                .contains("the rating needs a range, say it in the contract");
            assertThat(oneReplies.get()).as("no one-reply fallback").isZero();
        }
    }

    @Test
    void aTestSentBackAsBrokenIsDeliveredToTheTestAuthorsOwnConversation() throws Exception {
        Task task = task();
        String dir = "src/test/java/swarm/accept";
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"files\":[],\"wrote\":[]}",
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("read_file",
                        Map.of("path", "project/src/main/java/com/acme/shop/Catalog.java"));
                    case 2 -> ScriptedAgentLlm.Turn.call("compile_test",
                        Map.of("path", dir + "/RatingTest.java", "content", testSource("First")));
                    case 3 -> ScriptedAgentLlm.Turn.call("report_done",
                        Map.of("wrote", "a title can be rated => swarm.accept.RatingTest#rates"));
                    case 4 -> ScriptedAgentLlm.Turn.call("compile_test",
                        Map.of("path", dir + "/RatingTest.java", "content", testSource("Second")));
                    default -> ScriptedAgentLlm.Turn.call("report_done",
                        Map.of("wrote", "a title can be rated => swarm.accept.RatingTest#rates"));
                })) {
            VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
            CloudGate gate = new CloudGate(0, null);
            TestAuthorClient author = new TestAuthorClient(client, gate);
            author.setLookupAgent(new LookupAgent(curator(), null, app, gate,
                new KoogAgentRuntime(), null, null));
            Map<String, String> existing = Map.of(dir + "/RatingTest.java", testSource("First"));

            // The first repair has no earlier conversation for this task: a session is opened.
            TestAuthorClient.Authored first = author.repairBrokenTest(app, task, null, existing,
                "It does not compile: Rating is not a type this build can see.");
            assertThat(first.failureReason()).isNull();
            assertThat(llm.sessionRequests).hasSize(3);

            // The second goes to the conversation that handed the first in.
            TestAuthorClient.Authored second = author.repairBrokenTest(app, task, null, existing,
                "It still does not compile: Rating is still not a type this build can see.");
            assertThat(second.failureReason()).isNull();

            assertThat(llm.sessionRequests).as("two more turns of the same session").hasSize(5);
            assertThat(llm.sessionRequests.get(3))
                .contains("public List<String> titles()")
                .contains("THIS IS THE SAME CONVERSATION")
                .contains("It still does not compile")
                .contains("FirstMarker");
        }
    }

    @Test
    void theArchitectsRulesAboutExistingCodeCarryNoInventoryOfTypes() {
        ExistingProjectTypes existing = ExistingProjectTypes.of(app);

        assertThat(existing.architectRules())
            .as("section 54: the rules stay, the type list is the role's own lookup")
            .contains("EXISTING name and package").contains("only the members this story ADDS")
            .doesNotContain("com.acme.shop.Catalog");
        assertThat(existing.architectBrief("catalog", 6_000))
            .as("the paragraph with the inventory is unchanged for a role that is not an agent")
            .contains("com.acme.shop.Catalog").contains("EXISTING name and package");
    }

    @Test
    void aConversationThatIsGoneIsNotContinuedAndABoundedNumberAreKept() {
        KeptConversations kept = new KeptConversations();
        assertThat(kept.take("plan:none")).isNull();
        assertThat(KeptConversations.MAX_KEPT).isPositive();
        assertThat(kept.size()).isZero();
    }

    @Test
    void theNextRoundOfADraftToolIsJudgedOnItsOwn() {
        // A new round has a fresh allowance of checks, no remembered hand-in and the new checks.
        DraftTools tools = new DraftTools(null, "plan", 3, json -> new DraftTools.Checked(true,
            List.of("old objection")));
        tools.reportDone("{\"tasks\":[]}");
        assertThat(tools.done()).isTrue();
        assertThat(tools.submission()).isEqualTo("{\"tasks\":[]}");

        tools.nextRound(json -> new DraftTools.Checked(true, List.of()));

        assertThat(tools.done()).isFalse();
        assertThat(tools.checks()).isZero();
        assertThat(tools.submission()).as("what was rejected is forgotten").isNull();
    }

    // -------------------------------------------------------------------------------------------

    private KnowledgeCurator curator() {
        return new KnowledgeCurator(List.of(new KnowledgeCurator.Root("project", app, "local")),
            null, cache);
    }

    private static ArchitectClient.DraftChecks noObjections() {
        return new ArchitectClient.DraftChecks() {
            @Override
            public List<String> design(DesignDocument draft) {
                return List.of();
            }

            @Override
            public List<String> plan(TaskGraph draft, DesignDocument design) {
                return List.of();
            }
        };
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Rate a title", "Let a customer rate a title.",
            Set.of("src/main"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "a title can be rated",
                "swarm.accept.RatingTest#rates")),
            "src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
    }

    private static String testSource(String name) {
        return "package swarm.accept;\n\npublic class RatingTest {\n    // class " + name
            + "Marker\n    public void rates() { }\n}\n";
    }

    private static String design(String contractType) {
        String simple = contractType.substring(contractType.lastIndexOf('.') + 1);
        return """
            {"requirements":[{"text":"A customer can rate a title","priority":"HIGH"}],
             "decisions":[{"decision":"Keep ratings beside the catalog","rationale":"one module"}],
             "contracts":[{"name":"%s","description":"NEW: what the story adds",
               "signature":"class %s","type":"%s","members":["int stars()"]}],
             "risks":[]}
            """.formatted(simple, simple, contractType);
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
