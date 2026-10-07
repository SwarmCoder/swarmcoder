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
import com.swarmcoder.domain.OpinionPolicy;
import com.swarmcoder.domain.RuleDispute;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
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
 * Harness run 65 (2026-10-02): an early task delivered a text class nobody could call; a later
 * task, bound by the rule that says to use it, had both its workers dispute the rule with that
 * file as evidence — and the unattended policy reworded the RULE for the whole project. The rule
 * was fine. Here:
 *
 * <ul>
 *   <li>disputes that name a file an earlier task of the run delivered are read as a defect in
 *       that file ({@link SiblingDefects});</li>
 *   <li>unattended, through the real engine: the rule is untouched and no question is recorded.
 *       Since 2026-10-02 the later task is NOT built again when it has candidates that passed
 *       verification: the best of them is delivered and the run carries a warning naming the
 *       earlier file (no rebuild round on a task that already has a passing candidate);</li>
 *   <li>attended: the question is still asked, with "repair" as an answer, and answering it
 *       widens the task instead of changing the rule.</li>
 * </ul>
 */
@ModelCodeOnThisPc
class AnEarlierTasksDefectIsNotAQuestionAboutTheRuleTest {

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private static final String RULE_TITLE = "Text in the text file";
    private static final String RULE_BODY = "All user-visible text is read from TEXTS.md.";

    private static final String TEXTS_PATCH = """
        diff --git a/TEXTS.md b/TEXTS.md
        --- a/TEXTS.md
        +++ b/TEXTS.md
        @@ -1 +1,2 @@
         texts
        +save=Save
        """;

    private static final String README_PATCH = """
        diff --git a/README.md b/README.md
        --- a/README.md
        +++ b/README.md
        @@ -1 +1,2 @@
         hello
        +improved by worker
        """;

    private final UUID projectId = UUID.randomUUID();

    /**
     * The first task writes TEXTS.md. The second disputes the rule over that file until it is
     * told to repair it; then it just does its work. The judge never objects to anything.
     */
    private static FakeVllm.Reply workers(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.8, \"rationale\": \"works\", \"violations\": []}");
        }
        if (conversation.contains("applied cleanly")) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"done\"}");
        }
        if (conversation.contains("Task: Write the texts")) {
            return diff(TEXTS_PATCH);
        }
        if (conversation.contains("REPAIR FIRST") || conversation.contains("Recorded. Your dispute")) {
            return diff(README_PATCH);
        }
        return FakeVllm.Reply.toolCall("dispute_rule", new ObjectMapper().createObjectNode()
            .put("rule", RULE_TITLE)
            .put("reason", "TEXTS.md as an earlier task wrote it cannot be read from, and it is "
                + "outside my write set")
            .put("evidence", "TEXTS.md").toString());
    }

    private static FakeVllm.Reply diff(String patch) {
        return FakeVllm.Reply.toolCall("apply_diff",
            new ObjectMapper().createObjectNode().put("unifiedDiff", patch).toString());
    }

    @Test
    void unattendedTheRuleStandsAndATaskWithPassingCandidatesIsNotBuiltAgain() throws Exception {
        initRepo();
        try (FakeVllm fake = new FakeVllm(SwarmEngineFakeVllmTest.finishingInPairs(
                 AnEarlierTasksDefectIsNotAQuestionAboutTheRuleTest::workers));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = statedRules(store);
            Fixture f = fixture(fake, store, rules, OpinionPolicy.WARN_AND_CARRY_ON);

            f.engine.executeRun(f.run);

            assertThat(rules.activeRules().get(0).markdownBody()).as("the rule was not reworded")
                .isEqualTo(RULE_BODY);
            assertThat(store.root().decisions.values()).as("and no question about it was recorded")
                .noneMatch(d -> d.kind() == DecisionKind.GUIDELINE_REVIEW);
            assertThat(f.later.writeSet()).as("the task was not given the earlier file")
                .containsExactly("README.md");
            assertThat(f.later.siblingRepairPaths()).isEmpty();
            assertThat(fake.requests).as("and it was not built a second time")
                .noneMatch(r -> r.contains("REPAIR FIRST"));
            assertThat(archived(store).stream().filter(c -> c.state() == CandidateState.SELECTED)
                .map(CandidateSolution::taskId)).as("both tasks delivered")
                .containsExactlyInAnyOrder(f.earlier.id(), f.later.id());
            assertThat(f.run.carriedWarnings()).as("and it is on the list printed at the end")
                .anySatisfy(w -> assertThat(w.objection())
                    .contains("TEXTS.md (delivered by 'Write the texts')")
                    .contains("was not built again"));
        }
    }

    @Test
    void attendedTheQuestionOffersTheRepairAndTheAnswerWidensTheTask() throws Exception {
        initRepo();
        try (FakeVllm fake = new FakeVllm(SwarmEngineFakeVllmTest.finishingInPairs(
                 AnEarlierTasksDefectIsNotAQuestionAboutTheRuleTest::workers));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = statedRules(store);
            Fixture f = fixture(fake, store, rules, OpinionPolicy.ASK_THE_OPERATOR);

            assertThatThrownBy(() -> f.engine.executeRun(f.run)).isInstanceOf(RunMustPark.class);

            Decision question = store.root().decisions.values().stream()
                .filter(d -> d.kind() == DecisionKind.GUIDELINE_REVIEW).findFirst().orElseThrow();
            assertThat(question.state()).isEqualTo(DecisionState.PENDING);
            assertThat(question.briefMarkdown())
                .contains("THE EVIDENCE POINTS AT AN EARLIER TASK'S OUTPUT, NOT AT THE RULE: "
                    + "TEXTS.md (delivered by 'Write the texts')")
                .contains("repair —").contains("keep —").contains("allow —")
                .contains(RuleQuestions.REPAIR_FILES_PREFIX + "TEXTS.md");
            assertThat(f.later.siblingRepairPaths()).as("nothing is widened until it is answered")
                .isEmpty();

            store.append(() -> {
                store.root().decisions.put(question.id(), new Decision(question.id(),
                    question.runId(), question.kind(), question.briefMarkdown(),
                    DecisionState.RESOLVED, "repair", question.createdAt()));
                return null;
            }).get();
            f.engine.executeRun(f.run);

            assertThat(rules.activeRules().get(0).markdownBody()).isEqualTo(RULE_BODY);
            assertThat(f.later.siblingRepairPaths()).containsExactly("TEXTS.md");
            assertThat(f.later.writeSet()).contains("TEXTS.md");
            assertThat(archived(store).stream().filter(c -> c.state() == CandidateState.SELECTED)
                .map(CandidateSolution::taskId)).contains(f.later.id());
        }
    }

    /** The evidence of run 65 itself, word for word, against what the earlier winners changed. */
    @Test
    void theDisputesOfRunSixtyFiveNameTheTextClassAndOnlyIt() {
        String texts = "hambook-client/src/main/java/com/hambook/client/i18n/LogbookTexts.java";
        String table = "hambook-client/src/main/java/com/hambook/client/LogbookTable.java";
        String screen = "hambook-client/src/main/java/com/hambook/client/LogbookEditScreen.java";
        Task textsTask = task("LogbookTexts constants", Set.of(texts));
        Task tableTask = task("LogbookTable", Set.of(table));
        Task screenTask = task("LogbookEditScreen client screen",
            Set.of("hambook-client/pom.xml", screen));
        List<SiblingDefects.Delivered> delivered = List.of(
            new SiblingDefects.Delivered(textsTask, winner(textsTask, texts)),
            new SiblingDefects.Delivered(tableTask, winner(tableTask, table)));
        RuleDispute one = new RuleDispute("Screen text must live in i18n string classes",
            "The rule requires every label to come from LogbookTexts, but the LogbookTexts.java in "
                + "my checkout (written by a prior task) cannot be used. The existing "
                + "LogbookTable.java also avoids the class.",
            texts + ": \"public final class LogbookTexts\", \"private LogbookTexts()\". Write set "
                + "is only [hambook-client/pom.xml, " + screen + "].");
        RuleDispute two = new RuleDispute("Screen text must live in i18n string classes",
            "LogbookTexts (written by a sibling task) declares a private constructor.",
            texts + ": `private LogbookTexts() { }`. " + table + " already uses a private static "
                + "`fieldLabel(String)` switch.");

        SiblingDefects.Defect defect = SiblingDefects.find(screenTask, delivered, List.of(one, two));

        assertThat(defect).isNotNull();
        assertThat(defect.files()).as("the file both name, not the one cited as a precedent")
            .containsExactly(texts);
        assertThat(defect.named()).contains("delivered by 'LogbookTexts constants'");

        // A dispute that names no earlier task's file is about the rule, as before.
        RuleDispute library = new RuleDispute("Screen text must live in i18n string classes",
            "the library has no message catalog", "docs/guides/language.md says so");
        assertThat(SiblingDefects.find(screenTask, delivered, List.of(one, library))).isNull();
        assertThat(SiblingDefects.find(screenTask, delivered, List.of(library))).isNull();

        // And the widening is bounded to one per task.
        screenTask.setSiblingRepairPaths(List.of(texts));
        assertThat(SiblingDefects.find(screenTask, delivered, List.of(one, two))).isNull();
    }

    // --- fixtures ---------------------------------------------------------------------------

    private record Fixture(SwarmEngineImpl engine, Run run, Task earlier, Task later) {}

    private static Task task(String title, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "Do it.", writeSet, Set.of(), List.of(), null,
            null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
    }

    private static CandidateSolution winner(Task task, String path) {
        String diff = "diff --git a/" + path + " b/" + path + "\n--- a/" + path + "\n+++ b/" + path
            + "\n@@ -0,0 +1 @@\n+x\n";
        return new CandidateSolution(UUID.randomUUID(), task.id(), 0, "b", null, diff, null, null,
            null, CandidateState.SELECTED, null);
    }

    private ProjectRules statedRules(ArtifactStore store) {
        ProjectRules rules = new ProjectRules(store, projectId);
        assertThat(rules.stateRule(RULE_TITLE, RULE_BODY, "tech.md", null,
            "so the text can be translated", true)).isEmpty();
        return rules;
    }

    private Fixture fixture(FakeVllm fake, ArtifactStore store, ProjectRules rules,
                            OpinionPolicy policy) throws Exception {
        UUID graphId = UUID.randomUUID();
        Task earlier = task("Write the texts", Set.of("TEXTS.md"));
        Task later = task("Improve README", Set.of("README.md"));
        TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(earlier, later),
            List.of(new TaskEdge(earlier.id(), later.id())));
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
        return new Fixture(engine, run, earlier, later);
    }

    private static List<CandidateSolution> archived(ArtifactStore store) {
        return store.root().candidateArchives.values().stream()
            .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
    }

    private void initRepo() throws Exception {
        run("git init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        Files.writeString(repoDir.resolve("TEXTS.md"), "texts\n");
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
