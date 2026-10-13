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

import com.swarmcoder.domain.ArchDecision;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.CloudGate.Cap;
import com.swarmcoder.runtime.CloudGate.Limits;
import com.swarmcoder.runtime.CloudGate.Where;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.UsageLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reviewer's readings of one run are one conversation, it starts from what the architect
 * established, and a spent budget stops it instead of approving (section 94). No model is called:
 * the endpoint is scripted.
 *
 * <p>Build of 2026-10-13, planning roles on a paid server: the reviewer made 121 calls for one
 * small story, 9.1 million tokens. Each of its four readings opened a session of its own and
 * learned the project again (34, 28, 51 and 8 calls; 370 lookups), the second reading of the
 * design included. The run's budget of 3,000,000 did not stop it.
 */
class TheReviewerReadsInOneConversationARunTest {

    private static final String RULES = "RULE 'No hand-built services' [HARD]: a test never "
        + "constructs a service by hand.";

    @TempDir
    Path repo;
    @TempDir
    Path cache;

    private final UUID project = UUID.randomUUID();
    private final UUID story = UUID.randomUUID();

    @AfterEach
    void noRecorderIsLeftBehind() {
        RunMeter.recordTo(null);
    }

    private DesignReviewerClient reviewer(ScriptedAgentLlm llm, CloudGate gate) throws Exception {
        Files.createDirectories(repo.resolve("src/main/java"));
        Files.writeString(repo.resolve("src/main/java/BookService.java"),
            "public class BookService { public BookService(Db db) {} }\n");
        VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
        DesignReviewerClient reviewer = new DesignReviewerClient(client, gate);
        reviewer.setLookupAgent(new LookupAgent(new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", repo, "local")), null, cache),
            null, repo, gate, new KoogAgentRuntime(), null, null));
        return reviewer;
    }

    private static DesignDocument design(String decision) {
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "add a book", List.of(),
            List.of(new ArchDecision(UUID.randomUUID(), decision, "", List.of())), List.of(),
            List.of(), null, Instant.EPOCH);
        design.setFindings(List.of(new DesignFinding(UUID.randomUUID(), "BookService",
            "public_shape BookService", "the service takes its database in its constructor",
            "public BookService(Db db)")));
        return design;
    }

    private static Task task(String title, String instructions) {
        return new Task(UUID.randomUUID(), 1L, title, instructions, Set.of(), Set.of(), List.of(),
            null, null, null, null, TaskState.PENDING);
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    void theSecondReadingAndThePlanReadingAreTheNextMessagesOfTheSameConversation()
            throws Exception {
        String objection = "decision 'build the service by hand' conflicts with rule 'No "
            + "hand-built services': it constructs one.";
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"approved\": true, \"objections\": []}",
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("read_file",
                        Map.of("path", "project/src/main/java/BookService.java"));
                    case 2 -> ScriptedAgentLlm.Turn.call("report_done",
                        Map.of("approved", "false", "objections", objection + "\n"));
                    default -> ScriptedAgentLlm.Turn.call("report_done",
                        Map.of("approved", "true", "objections", ""));
                })) {
            DesignReviewerClient reviewer = reviewer(llm, new CloudGate(0, null));
            UUID run = UUID.randomUUID();
            DesignReviewerClient.Review first;
            DesignReviewerClient.Review second;
            DesignReviewerClient.Review plan;
            try (var in = CloudGate.enter(new Where(project, story, run))) {
                first = reviewer.reviewDesign(RULES, design("build the service by hand"));
                second = reviewer.reviewDesign(RULES, design("inject the service"));
                plan = reviewer.reviewPlan(RULES, List.of(task("T", "Inject the service.")));
            }

            assertThat(first.approved).isFalse();
            assertThat(first.objections).containsExactly(objection);
            assertThat(second.approved).as("the verdict of the second reading, not the first's")
                .isTrue();
            assertThat(second.objections).isEmpty();
            assertThat(plan.approved).isTrue();
            assertThat(llm.oneReplyRequests).isEmpty();
            assertThat(llm.sessionRequests)
                .as("a lookup and a verdict, then one call for each further reading: before, "
                    + "each reading opened a session and looked everything up again")
                .hasSize(4);

            String opening = llm.sessionRequests.get(0);
            assertThat(opening)
                .as("the first reading starts from what the architect established")
                .contains(DesignReviewerClient.FACTS_HEADING.strip())
                .contains("the service takes its database in its constructor")
                .contains("(from public_shape BookService)")
                .contains("Look a fact up only when an objection you are about to raise "
                    + "depends on it");

            String revised = llm.sessionRequests.get(2);
            assertThat(revised)
                .as("the second reading is the next message of the same conversation")
                .contains("ANOTHER READING, IN THIS SAME CONVERSATION")
                .contains("public class BookService { public BookService(Db db) {} }")
                .contains("build the service by hand")
                .contains("inject the service")
                .contains("The project's rules are the ones at the start of this conversation.");
            assertThat(count(revised, RULES)).as("the rules are not sent a second time")
                .isEqualTo(1);
            assertThat(count(revised, DesignReviewerClient.FACTS_HEADING.strip()))
                .as("nor are the facts").isEqualTo(1);

            String planned = llm.sessionRequests.get(3);
            assertThat(planned).as("and so is the plan's reading, with its own instructions")
                .contains("checking a PLAN against this project's standing rules")
                .contains("The plan's tasks:")
                .contains("public class BookService { public BookService(Db db) {} }");
            assertThat(count(planned, RULES)).isEqualTo(1);
        }
    }

    @Test
    void anotherRunsReadingStartsItsOwnConversation() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"approved\": true, \"objections\": []}",
                (turn, conversation) -> ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("approved", "true", "objections", "")))) {
            DesignReviewerClient reviewer = reviewer(llm, new CloudGate(0, null));
            try (var in = CloudGate.enter(new Where(project, story, UUID.randomUUID()))) {
                reviewer.reviewDesign(RULES, design("inject the service"));
            }
            try (var in = CloudGate.enter(new Where(project, story, UUID.randomUUID()))) {
                reviewer.reviewDesign(RULES, design("inject the service again"));
            }
            // And a reading for no run at all keeps nothing.
            reviewer.reviewDesign(RULES, design("inject it a third time"));
            reviewer.reviewDesign(RULES, design("inject it a fourth time"));

            assertThat(llm.sessionRequests).hasSize(4);
            assertThat(llm.sessionRequests)
                .allSatisfy(request -> assertThat(request).doesNotContain("ANOTHER READING"));
        }
    }

    /**
     * The guard that would have stopped the build of 2026-10-13: every call of a session is
     * counted with the server's own figures (section 93), the session is stopped before the call
     * after the one that passed the run's limit, and the reading does not come back "approved,
     * review unavailable" - which is what a refused reviewer was turned into, so the run went on
     * to its next stage. The exception is what the workflow parks the run on, with the question
     * the gate has raised.
     */
    @Test
    void aReviewerThatKeepsCallingIsStoppedByTheRunsBudgetAndTheReadingIsNotApproved()
            throws Exception {
        List<CloudGate.Breach> raised = new ArrayList<>();
        CloudGate gate = new CloudGate(new Limits(new Cap(0, 250, 0), Cap.NONE, Cap.NONE),
            raised::add);
        gate.countFromUsage();
        RunMeter.recordTo(new UsageLedger(gate, null, address -> false)); // every server is paid
        UUID run = UUID.randomUUID();

        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"approved\": true, \"objections\": []}",
                // It never hands in: one more lookup on every turn, each call 100 tokens in.
                (turn, conversation) -> ScriptedAgentLlm.Turn.call("read_file",
                    Map.of("path", "project/src/main/java/BookService.java:" + turn)))) {
            DesignReviewerClient reviewer = reviewer(llm, gate);

            try (var in = CloudGate.enter(new Where(project, story, run))) {
                assertThatThrownBy(() -> reviewer.reviewPlan(RULES,
                    List.of(task("T", "Construct the service by hand."))))
                    .as("a spent budget is not a review that was unavailable")
                    .isInstanceOf(CloudGate.BudgetExhaustedException.class);
            }

            assertThat(llm.sessionRequests)
                .as("100, 200, 300 input tokens: over 250 after its third call, no fourth")
                .hasSize(3);
            assertThat(llm.oneReplyRequests)
                .as("and no one-shot review is paid for in its place").isEmpty();
            assertThat(gate.spendOfRun(run).input()).isEqualTo(300);
            assertThat(raised).as("the budget question is raised once, for this run").hasSize(1);
            assertThat(raised.get(0).level()).isEqualTo(CloudGate.Level.RUN);
            assertThat(raised.get(0).runId()).isEqualTo(run);
        }
    }

    /** The same for the session that could not even be opened, and for the one-shot review. */
    @Test
    void aReadingAskedForWithTheBudgetAlreadySpentIsNotApprovedEither() throws Exception {
        CloudGate gate = new CloudGate(new Limits(new Cap(0, 250, 0), Cap.NONE, Cap.NONE),
            breach -> { });
        gate.countFromUsage();
        UUID run = UUID.randomUUID();
        gate.recordUsage(new Where(project, story, run), 400, 10);

        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"approved\": true, \"objections\": []}",
                (turn, conversation) -> ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("approved", "true", "objections", "")))) {
            DesignReviewerClient reviewer = reviewer(llm, gate);
            try (var in = CloudGate.enter(new Where(project, story, run))) {
                assertThatThrownBy(() -> reviewer.reviewDesign(RULES, design("inject it")))
                    .isInstanceOf(CloudGate.BudgetExhaustedException.class);
                assertThatThrownBy(() -> reviewer.review(design("inject it"), RULES))
                    .isInstanceOf(CloudGate.BudgetExhaustedException.class);
            }
            assertThat(llm.sessionRequests).isEmpty();
            assertThat(llm.oneReplyRequests).isEmpty();
        }
    }
}
