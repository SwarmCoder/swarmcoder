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

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.RepairIndex;
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
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Harness runs 53 and 55 (2026-10-01, HamBook on ZeroZ Stack 0.9.1) parked hours in with "every
 * candidate broke the stated rule ...", telling the operator to fix the plan, when the rule was
 * what did not fit the library. Through the real engine against a scripted {@link FakeVllm}:
 *
 * <ul>
 *   <li>a unanimous HARD-rule break raises a question about the RULE — keep, reword or allow, with
 *       the evidence — not a code failure; answered later, the task re-selects from the candidates
 *       it already has instead of swarming again;</li>
 *   <li>under the unattended policy the rule is reworded on the spot and the run carries on, with
 *       the decision and its evidence on the run's record;</li>
 *   <li>two workers disputing the same hard rule raise the question with no repair round;</li>
 *   <li>a broken PREFERENCE never parks anything.</li>
 * </ul>
 */
@ModelCodeOnThisPc
class AQuestionAboutARuleIsNotACodeFailureTest {

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private static final String RULE_TITLE = "Text in bundles";
    private static final String RULE_BODY = "All user-visible text lives in resource bundles.";

    private static final String PATCH = """
        diff --git a/README.md b/README.md
        --- a/README.md
        +++ b/README.md
        @@ -1 +1,2 @@
         hello
        +improved by worker
        """;

    private static final String JUDGE_FLAGS_THE_RULE = "{\"score\": 0.6, \"rationale\": \"works\", "
        + "\"violations\": [\"Text in bundles: the label is written straight into README.md\"]}";

    private final UUID projectId = UUID.randomUUID();

    /** Workers write the patch; the judge flags the rule on every diff it ever sees. */
    private static FakeVllm.Reply obeyingWorkers(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text(JUDGE_FLAGS_THE_RULE);
        }
        if (conversation.contains("applied cleanly")) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"added a line\"}");
        }
        return FakeVllm.Reply.toolCall("apply_diff",
            new ObjectMapper().createObjectNode().put("unifiedDiff", PATCH).toString());
    }

    /** As above, but each worker first disputes the rule with evidence. */
    private static FakeVllm.Reply disputingWorkers(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text(JUDGE_FLAGS_THE_RULE);
        }
        if (conversation.contains("applied cleanly")) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"added a line\"}");
        }
        if (conversation.contains("Recorded. Your dispute")) {
            return FakeVllm.Reply.toolCall("apply_diff",
                new ObjectMapper().createObjectNode().put("unifiedDiff", PATCH).toString());
        }
        return FakeVllm.Reply.toolCall("dispute_rule", new ObjectMapper().createObjectNode()
            .put("rule", RULE_TITLE)
            .put("reason", "README.md is not shipped to users and has no bundle")
            .put("evidence", "README.md").toString());
    }

    /** Unattended: every candidate breaks the hard rule; it is reworded and the run carries on. */
    @Test
    void underTheUnattendedPolicyAUnanimousBreakRewordsTheRuleAndTheRunContinues() throws Exception {
        initRepo();
        try (FakeVllm fake = new FakeVllm(AQuestionAboutARuleIsNotACodeFailureTest::obeyingWorkers);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = statedRules(store, true);
            Fixture f = fixture(fake, store, rules, com.swarmcoder.domain.OpinionPolicy.WARN_AND_CARRY_ON);

            f.engine.executeRun(f.run);

            assertThat(selected(store)).as("the run carried on and delivered").isNotNull();
            LearnedGuideline rule = rules.activeRules().get(0);
            assertThat(rule.markdownBody())
                .startsWith(RULE_BODY)
                .contains("This does not apply where")
                .contains("the label is written straight into README.md");
            assertThat(rule.statedWording()).isEqualTo(RULE_BODY);
            Decision decision = ruleDecision(store);
            assertThat(decision.state()).isEqualTo(DecisionState.RESOLVED);
            assertThat(decision.humanResponse()).startsWith("Decided without a person");
            assertThat(f.run.carriedWarnings()).as("and it is on the list printed at the end")
                .anySatisfy(w -> assertThat(w.objection()).contains("Decided without a person"));
            assertThat(decision.briefMarkdown())
                .as("the decision keeps its evidence on the run's record")
                .contains("A QUESTION ABOUT A RULE")
                .contains("every candidate broke the stated rule 'Text in bundles'")
                .contains("worker 0: Text in bundles: the label is written straight");
            assertThat(store.root().decisions.values())
                .noneMatch(d -> d.state() == DecisionState.PENDING);
        }
    }

    /**
     * Unattended, and the rule cannot be reworded (nothing is wired that may change the rules):
     * the judge's reading is still an opinion, so the run carries on with the best candidate and
     * the question is a warning on the run's record rather than a park (owner decision, 2026-10-01).
     */
    @Test
    void underTheUnattendedPolicyAQuestionThatCannotBeAnsweredIsCarriedAsAWarning() throws Exception {
        initRepo();
        try (FakeVllm fake = new FakeVllm(AQuestionAboutARuleIsNotACodeFailureTest::obeyingWorkers);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = statedRules(store, true);
            Fixture f = fixture(fake, store, rules, com.swarmcoder.domain.OpinionPolicy.WARN_AND_CARRY_ON);
            f.engine.setRuleAmendments(RuleQuestions.Amendments.NONE);

            f.engine.executeRun(f.run);

            assertThat(selected(store)).as("the run carried on and delivered").isNotNull();
            assertThat(rules.activeRules().get(0).markdownBody()).as("the rule is untouched")
                .isEqualTo(RULE_BODY);
            assertThat(f.run.carriedWarnings()).isNotEmpty().allSatisfy(w -> {
                assertThat(w.stage()).isEqualTo("EXECUTING");
                assertThat(w.check()).contains("Text in bundles");
                assertThat(w.objection()).contains("Improve README");
            });
            assertThat(store.root().decisions.values())
                .noneMatch(d -> d.state() == DecisionState.PENDING);
        }
    }

    /**
     * Attended: the same break raises a question about the rule, not a code failure. Answered
     * "allow", the task re-selects from the candidates it already has — nothing is swarmed again.
     */
    @Test
    void aUnanimousBreakAsksAboutTheRuleAndTheAnswerResumesTheTask() throws Exception {
        initRepo();
        try (FakeVllm fake = new FakeVllm(AQuestionAboutARuleIsNotACodeFailureTest::obeyingWorkers);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = statedRules(store, true);
            Fixture f = fixture(fake, store, rules, com.swarmcoder.domain.OpinionPolicy.ASK_THE_OPERATOR);

            assertThatThrownBy(() -> f.engine.executeRun(f.run))
                .isInstanceOf(RunMustPark.class)
                .satisfies(thrown -> assertThat(((RunMustPark) thrown).questionAlreadyRaised())
                    .as("the question is the decision — no second, code-shaped one").isTrue());

            Decision question = ruleDecision(store);
            assertThat(question.state()).isEqualTo(DecisionState.PENDING);
            assertThat(question.briefMarkdown())
                .contains("A QUESTION ABOUT A RULE, not about the code")
                .contains("Why it exists: so the text can be translated")
                .contains("keep —").contains("reword:").contains("allow —")
                .contains(RuleQuestions.SUGGESTED_PREFIX);
            assertThat(store.root().decisions.values())
                .noneMatch(d -> d.kind() == DecisionKind.BLOCKED_TASK);
            int archivedBefore = store.root().candidateArchives.size();
            int requestsBefore = fake.requests.size();

            // Unanswered, a resumed run stops behind the same question rather than swarming again.
            assertThatThrownBy(() -> f.engine.executeRun(f.run)).isInstanceOf(RunMustPark.class);

            store.append(() -> {
                store.root().decisions.put(question.id(), new Decision(question.id(),
                    question.runId(), question.kind(), question.briefMarkdown(),
                    DecisionState.RESOLVED, "allow", question.createdAt()));
                return null;
            }).get();
            f.engine.executeRun(f.run);

            assertThat(selected(store)).as("re-selected under the answer").isNotNull();
            assertThat(store.root().candidateArchives).hasSize(archivedBefore);
            assertThat(fake.requests).as("no worker and no judge was asked anything again")
                .hasSize(requestsBefore);
            assertThat(rules.activeRules().get(0).markdownBody())
                .as("the answer is remembered for the project")
                .contains("Exception: allowed for 'Improve README'");
        }
    }

    /** Two workers dispute the same hard rule with evidence: the question comes with no repair round. */
    @Test
    void twoDisputesRaiseTheQuestionWithoutARepairRound() throws Exception {
        initRepo();
        try (FakeVllm fake = new FakeVllm(AQuestionAboutARuleIsNotACodeFailureTest::disputingWorkers);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = statedRules(store, true);
            Fixture f = fixture(fake, store, rules, com.swarmcoder.domain.OpinionPolicy.WARN_AND_CARRY_ON);

            f.engine.executeRun(f.run);

            assertThat(selected(store)).isNotNull();
            assertThat(archived(store)).as("no repair round: the workers already said why")
                .noneMatch(c -> RepairIndex.isRepair(c.workerIndex()));
            assertThat(archived(store)).allSatisfy(c ->
                assertThat(c.ruleDisputes()).as("each dispute is recorded on its candidate")
                    .singleElement().satisfies(d -> assertThat(d.rule()).isEqualTo(RULE_TITLE)));
            assertThat(ruleDecision(store).briefMarkdown())
                .contains("2 candidates independently disputed the stated rule 'Text in bundles'")
                .contains("README.md is not shipped to users");
            assertThat(rules.activeRules().get(0).markdownBody())
                .contains("README.md is not shipped to users and has no bundle");
        }
    }

    /** A broken PREFERENCE lowers the score and nothing else: no repair, no question, no park. */
    @Test
    void aPreferenceBreakNeverParks() throws Exception {
        initRepo();
        try (FakeVllm fake = new FakeVllm(AQuestionAboutARuleIsNotACodeFailureTest::obeyingWorkers);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = statedRules(store, false);
            Fixture f = fixture(fake, store, rules, com.swarmcoder.domain.OpinionPolicy.ASK_THE_OPERATOR);

            f.engine.executeRun(f.run);

            CandidateSolution winner = selected(store);
            assertThat(winner).isNotNull();
            assertThat(winner.judge().brokenRules()).isEmpty();
            assertThat(winner.judge().preferenceBreaks()).isNotEmpty();
            assertThat(archived(store)).noneMatch(c -> RepairIndex.isRepair(c.workerIndex()));
            assertThat(store.root().decisions).isEmpty();
            assertThat(rules.activeRules().get(0).markdownBody()).isEqualTo(RULE_BODY);
        }
    }

    // --- fixtures ---------------------------------------------------------------------------

    private record Fixture(SwarmEngineImpl engine, Run run) {}

    private ProjectRules statedRules(ArtifactStore store, boolean hard) {
        ProjectRules rules = new ProjectRules(store, projectId);
        assertThat(rules.stateRule(RULE_TITLE, RULE_BODY, "hambook-tech.md", null,
            "so the text can be translated", hard)).isEmpty();
        return rules;
    }

    private Fixture fixture(FakeVllm fake, ArtifactStore store, ProjectRules rules,
                            com.swarmcoder.domain.OpinionPolicy policy) throws Exception {
        UUID graphId = UUID.randomUUID();
        Task task = new Task(UUID.randomUUID(), 1, "Improve README", "Add a line to README.md",
            Set.of("README.md"), Set.of(), List.of(), null, null,
            new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
        TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());
        store.append(() -> {
            store.root().taskGraphs.put(graphId, graph);
            return null;
        }).get();
        ModelProfileRegistry profiles = new ModelProfileRegistry(List.of(new ModelProfile("fake",
            new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
            ModelProfile.Kind.WORKER, 0, 0)));
        SwarmEngineImpl engine = new SwarmEngineImpl(
            new VllmClient(fake.baseUrl(), "", "fake-judge", true), store,
            new InferenceScheduler(8, 1024 * 1024 * 1024, 1024),
            new KoogAgentRuntime(new TraceHub(null)), profiles, new GitService(repoDir),
            content -> null, new CloudGate(1_000_000, null));
        engine.setActiveRules(rules::activeRules);
        engine.setRuleAmendments(RuleQuestions.Amendments.over(rules));
        engine.setOpinionPolicy(policy);
        Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
            null, null, null, graphId, null, Instant.now(), null);
        return new Fixture(engine, run);
    }

    private static List<CandidateSolution> archived(ArtifactStore store) {
        return store.root().candidateArchives.values().stream()
            .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
    }

    private static CandidateSolution selected(ArtifactStore store) {
        return archived(store).stream().filter(c -> c.state() == CandidateState.SELECTED)
            .findFirst().orElse(null);
    }

    private static Decision ruleDecision(ArtifactStore store) {
        List<Decision> found = store.root().decisions.values().stream()
            .filter(d -> d.kind() == DecisionKind.GUIDELINE_REVIEW).toList();
        assertThat(found).as("exactly one question about the rule").hasSize(1);
        return found.get(0);
    }

    private void initRepo() throws Exception {
        run("git init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        Files.writeString(repoDir.resolve(".swarmcoder/verify.yaml"), """
            toolchain: gradle
            compile:
              - "echo compile-ok"
            timeoutSeconds: 60
            """);
        run("git add -A");
        run("git -c user.email=t@t -c user.name=t commit -q -m init");
    }

    private void run(String command) throws Exception {
        ProcessBuilder pb = System.getProperty("os.name").toLowerCase().contains("win")
            ? new ProcessBuilder("cmd", "/c", command) : new ProcessBuilder("sh", "-c", command);
        Process process = pb.directory(repoDir.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as(command).isZero();
    }
}
