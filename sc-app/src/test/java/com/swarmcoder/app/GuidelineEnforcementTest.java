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
package com.swarmcoder.app;

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.ProjectRules;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.FakeVllm;
import com.swarmcoder.swarm.SwarmEngineImpl;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Is a guideline ever ENFORCED, or is it only ever a suggestion in a prompt?
 *
 * <p>Both tests make a worker disobey. A house rule banning Java records is ACTIVE among the
 * project's rules and demonstrably reaches the worker's prompt; the worker writes a record
 * anyway. Everything downstream then runs for real — a real Maven compile, the real
 * verification pipeline, the real clustering, the real judge, the real selection — and the
 * violating candidate is followed all the way to the end.
 *
 * <p>The two tests are the two halves of the author's decision (§21), and the difference between
 * them is whether the rule carries a check command:
 *
 * <ul>
 *   <li><b>A rule with no check</b> is guidance. The candidate can still be selected —
 *       with one candidate there is nothing to prefer over it — but the judge is now SHOWN the
 *       rule, which it never was. Before, it was handed the diff, the task and a verification
 *       summary and could not have marked a breach down if it had wanted to.</li>
 *   <li><b>A rule with a check</b> is enforced. The command decides survival exactly as a
 *       test does: the candidate fails verification, never reaches the judge, and cannot be
 *       merged.</li>
 * </ul>
 */
@ModelCodeOnThisPc
class GuidelineEnforcementTest {

    private static final String RULE =
        "Never declare a Java record. Java records are banned in this repository. "
        + "Use a final class with explicit accessor methods instead.";

    /** The worker's deliberate violation: exactly the construct the ACTIVE rule forbids. */
    private static final String MONEY_AS_A_RECORD = """
        package com.example.calc;

        /** An immutable amount of money. */
        public record Money(long amount, String currency) {
        }
        """;

    /** A second, structurally different violation of the same rule. */
    private static final String MONEY_AS_A_RECORD_WITH_A_METHOD = """
        package com.example.calc;

        /** An immutable amount of money that can say whether it is zero. */
        public record Money(long amount, String currency) {
            public boolean isZero() {
                return amount == 0;
            }
        }
        """;

    private static final java.util.concurrent.atomic.AtomicInteger WRITES =
        new java.util.concurrent.atomic.AtomicInteger();

    @TempDir
    Path work;

    /**
     * The rule's own proof command, spelled for whichever shell verification runs under.
     *
     * <p>Passes (exit 0) when no Java record is declared under {@code src/main/java}. Written the
     * way an operator would write it, as a shell string, because that is exactly what
     * {@code verify.yaml} commands are and a rule check deliberately reuses that machinery rather
     * than inventing a second, safer-looking one.
     */
    private static String noRecordsCommand() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (windows) {
            return "findstr /S /C:\"public record\" src\\main\\java\\*.java >nul "
                + "& if errorlevel 1 (exit /b 0) else (exit /b 1)";
        }
        return "! grep -rq 'public record' src/main/java";
    }

    @Test
    void aRuleWithNoCheckIsGuidanceAndTheJudgeIsNowShownIt() throws Exception {
        try (Fixture fixture = new Fixture(null, 2)) {
        fixture.run();

        List<CandidateSolution> candidates = fixture.candidates();
        assertThat(candidates)
            .as("two workers wrote different changes, so two candidates reach the judge")
            .hasSize(2);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.diffUnified())
                .as("each worker wrote the one construct the rule forbids")
                .contains("public record Money");
            assertThat(candidate.verification().compiles())
                .as("it builds — a rule with no check has nothing to do with the build")
                .isTrue();
            assertThat(candidate.verification().guidelineChecks())
                .as("and no rule declared a command, so nothing was checked")
                .isEmpty();
        });

        assertThat(fixture.judgePrompt())
            .as("the judge is SHOWN the house rules now, so a breach is something it can mark down")
            .contains(RULE)
            .contains("HOUSE RULES");

        assertThat(candidates.stream().filter(c -> c.state() == CandidateState.SELECTED))
            .as("with no declared check, the judge's opinion is all there is and one candidate "
                + "is still selected — this is the honest limit of guidance")
            .hasSize(1);
        System.out.println("[ENFORCEMENT] no check declared: one of " + candidates.size()
            + " candidates SELECTED");
        }
    }

    @Test
    void aRuleThatDeclaresItsCheckKillsTheCandidateThatBreaksIt() throws Exception {
        try (Fixture fixture = new Fixture(noRecordsCommand())) {
        fixture.run();

        List<CandidateSolution> candidates = fixture.candidates();
        assertThat(candidates)
            .as("the first worker plus the repair round it triggered")
            .isNotEmpty();
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.state())
                .as("no candidate that broke a checked rule survives")
                .isNotEqualTo(CandidateState.SELECTED);
        });

        CandidateSolution first = candidates.stream()
            .filter(c -> c.verification() != null && c.verification().guidelineChecks() != null
                && !c.verification().guidelineChecks().isEmpty())
            .findFirst().orElseThrow();
        assertThat(first.verification().guidelineChecks().get(0).passed()).isFalse();
        assertThat(first.verification().guidelineChecks().get(0).slug()).isEqualTo("no-java-records");
        assertThat(first.verification().logTail())
            .as("and the operator is told which rule, in its own words, in the report the "
                + "Gallery renders")
            .contains("no-java-records")
            .contains("Never declare a Java record");
        assertThat(first.state()).isEqualTo(CandidateState.FAILED);

        assertThat(fixture.endpoint.requests.stream().anyMatch(r -> r.contains("code-review judge")))
            .as("a candidate that broke a checked rule never reaches the judge at all")
            .isFalse();
        System.out.println("[ENFORCEMENT] check declared: " + candidates.size()
            + " candidate(s), none selected — " + first.verification().guidelineChecks().get(0).describe());
        }
    }

    /** The worker's prompt really did carry the rule, in both arms. */
    private static void assertRuleReachedTheWorker(FakeVllm endpoint) {
        String workerPrompt = endpoint.requests.stream()
            .filter(r -> r.contains("software engineering worker agent"))
            .findFirst().orElseThrow();
        assertThat(workerPrompt)
            .as("the ACTIVE rule was in the bytes the worker received")
            .contains(RULE);
    }

    /** One real repo, one real run, one deliberately disobedient worker. */
    private final class Fixture implements AutoCloseable {

        private final ArtifactStore store;
        final FakeVllm endpoint;
        private final Path repo;
        private final ProjectRules rules;
        private final int workers;

        /** @param checkCommand the rule's proof command, or null for a rule that is advice only */
        Fixture(String checkCommand) throws Exception {
            this(checkCommand, 1);
        }

        /** @param workers how many workers try the task; two or more are needed for the judge to be called */
        Fixture(String checkCommand, int workers) throws Exception {
            this.workers = workers;
            this.repo = DemoRepo.create(work.resolve("repo"));
            this.store = new ArtifactStore(work.resolve("store"));
            this.endpoint = new FakeVllm(GuidelineEnforcementTest::reply);
            // The rule as the operator states it: ACTIVE, decided by a person, with or without
            // the command that proves it. A store object — nothing is written into the checkout.
            this.rules = new ProjectRules(store, UUID.randomUUID());
            assertThat(rules.stateRule("No Java records", RULE, null)).isEmpty();
            if (checkCommand != null) {
                assertThat(rules.setCheck(rules.activeRules().get(0).id(), checkCommand, 0))
                    .isEmpty();
            }
        }

        void run() throws Exception {
            String model = "fake-vllm";

            Task task = new Task(UUID.randomUUID(), 1, "Add a Money value type",
                "Create src/main/java/com/example/calc/Money.java holding an amount and a "
                + "currency code. Write it with write_file, then call report_done.",
                Set.of("src/main"), Set.of("src"), List.of(), "src/test/java/swarm", null,
                new TokenBudget(32000, 4000, 500000, 8),
                new SwarmPolicy(workers, false, 0.2, 0.2, List.of("minimal-diff")), TaskState.READY);

            UUID graphId = UUID.randomUUID();
            store.append(() -> {
                store.root().taskGraphs.put(graphId,
                    new TaskGraph(graphId, 1, null, List.of(task), List.of()));
                return null;
            }).get();

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(endpoint.baseUrl(), "", model, true), // the judge
                store,
                new InferenceScheduler(16, 1024L * 1024 * 1024, 1024),
                new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile(model,
                    new AgentRuntime.ModelEndpoint(endpoint.baseUrl(), "", model, 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                new GitService(repo),
                content -> null,
                new CloudGate(50_000_000, null),
                () -> rules.renderActive(12_000));
            // Exactly the production wiring: the checks come from the store, never a worktree.
            engine.setGuidelineChecks(rules::activeChecks);

            engine.executeRun(new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
                UUID.randomUUID(), null, null, graphId, null, Instant.now(), null));

            assertRuleReachedTheWorker(endpoint);
        }

        List<CandidateSolution> candidates() {
            return store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy))
                .toList();
        }

        CandidateSolution onlyCandidate() {
            List<CandidateSolution> all = candidates();
            assertThat(all).hasSize(1);
            return all.get(0);
        }

        String judgePrompt() {
            return endpoint.requests.stream()
                .filter(r -> r.contains("code-review judge"))
                .findFirst().orElseThrow();
        }

        @Override
        public void close() throws Exception {
            endpoint.close();
            store.close();
        }
    }

    /** The worker disobeys once and reports done; the judge likes what it sees. */
    private static FakeVllm.Reply reply(String conversation) {
        if (conversation.contains("software engineering worker agent")) {
            if (conversation.contains("wrote src/main/java/com/example/calc/Money.java")) {
                return FakeVllm.Reply.toolCall("report_done",
                    "{\"summary\": \"added Money\"}");
            }
            // Alternate between two structurally different records, so that when two workers run
            // their changes differ and are judged separately; one worker always gets the first.
            String content = WRITES.getAndIncrement() % 2 == 0
                ? MONEY_AS_A_RECORD : MONEY_AS_A_RECORD_WITH_A_METHOD;
            return FakeVllm.Reply.toolCall("write_file", new ObjectMapper().createObjectNode()
                .put("path", "src/main/java/com/example/calc/Money.java")
                .put("content", content)
                .toString());
        }
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text(
                "{\"score\": 0.9, \"rationale\": \"clean, minimal, implements the task\"}");
        }
        return FakeVllm.Reply.text("{}");
    }
}
