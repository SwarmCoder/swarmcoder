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

import com.swarmcoder.swarm.HarnessSandbox;
import com.swarmcoder.domain.GuidelineCheck;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.knowledge.ProjectRules;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.SwarmDispatcher;
import com.swarmcoder.swarm.WorkerResult;
import com.swarmcoder.verify.CommandPipelineVerifier;
import com.swarmcoder.verify.Verdicts;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does a house rule that declares how to prove itself actually stop a real model's work?
 *
 * <p>{@link LiveGuidelineEffectTest} established that a rule changes what the model WRITES. This
 * establishes the other half: that when the model breaks a checked rule, the real verification
 * pipeline fails the candidate, and when it obeys, the same pipeline passes it. Both arms use the
 * same live model, the same task, the same repository and the same rule — the one difference is
 * whether the rule reached the worker's prompt.
 *
 * <p>That difference is the honest way to get both outcomes from a model that mostly complies. It
 * is also a real situation, not a contrivance: it is exactly what happens when an operator writes
 * a rule about code that already exists. In both arms the rule and its command are the OPERATOR'S
 * decision, held in the store, never read from the worktree being examined.
 *
 * <p>Interleaved, so a slow or busy shared endpoint cannot land on one arm only.
 *
 * <pre>
 * mvn -o test -pl sc-app -am -Dtest=LiveGuidelineEnforcementTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://192.168.0.10:8002/v1 \
 *   -Dswarmcoder.live.model=qwen3.8-27b -Dswarmcoder.live.reps=2
 * </pre>
 */
class LiveGuidelineEnforcementTest {

    private static final String RULE = """
        Never declare a Java record. Java records are banned in this repository.
        Write a final class with private final fields, a public constructor, and explicit
        accessor methods, plus equals, hashCode and toString written out by hand.
        """;

    private static final String INSTRUCTIONS = """
        Create a new file src/main/java/com/example/calc/Money.java in package com.example.calc.

        Money is a small immutable value type with exactly two components:
          - amount: a long, the value in minor units (cents)
          - currency: a String, a three-letter ISO currency code

        Keep it short and idiomatic for modern Java. Do not modify any existing file.
        You do NOT need to run a build. Write the file with write_file, then call report_done.
        """;

    @TempDir
    Path work;

    /** Exits 0 when no Java record is declared under src/main/java. */
    private static String noRecordsCommand() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return "findstr /S /C:\"record \" src\\main\\java\\*.java >nul "
                + "& if errorlevel 1 (exit /b 0) else (exit /b 1)";
        }
        return "! grep -rq 'record ' src/main/java";
    }

    @Test
    @EnabledIfSystemProperty(named = "swarmcoder.live.baseUrl", matches = ".+",
        disabledReason = "needs a live model endpoint; see the class javadoc")
    void aCheckedRuleFailsTheWorkThatBreaksItAndPassesTheWorkThatDoesNot() throws Exception {
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        String model = System.getProperty("swarmcoder.live.model", "qwen3.8-27b");
        int reps = Integer.getInteger("swarmcoder.live.reps", 2);

        Path repo = DemoRepo.create(work.resolve("repo"));

        // The rule and its command are the operator's decision, held in the store and resolved
        // once here. A worker's worktree never contributes one — .swarmcoder/ is
        // PathPolicy.ALWAYS_PROTECTED, and nothing reads a rule from a checkout any more.
        List<GuidelineCheck> checks = List.of(new GuidelineCheck("no-java-records", "PROJECT",
            RULE, noRecordsCommand(), 120));

        Optional<VerifySpec> spec = VerifySpecLoader.load(repo);
        assertThat(spec).as("the demo repo declares how it builds").isPresent();

        List<Outcome> unaware = new ArrayList<>();
        List<Outcome> aware = new ArrayList<>();

        try (ArtifactStore store = new ArtifactStore(work.resolve("store"))) {
            GitService git = new GitService(repo);
            SwarmDispatcher dispatcher = new SwarmDispatcher(
                new InferenceScheduler(16, 1024L * 1024 * 1024, 1024), git, new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile(model,
                    new AgentRuntime.ModelEndpoint(baseUrl, "", model, 65536),
                    ModelProfile.Kind.WORKER, 0, 0))));
            // A real model chooses these commands: they run in a container, or not at all.
            dispatcher.setSandbox(HarnessSandbox.required());
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());

            for (int rep = 0; rep < reps; rep++) {
                // Arm 1 — the rule exists and is CHECKED, but the worker was never told it.
                for (LearnedGuideline rule : rules.activeRules()) {
                    rules.setStatus(rule.id(), GuidelineStatus.RETIRED);
                }
                unaware.add(attempt(dispatcher, git, rules.renderActive(12_000), spec.get(), checks,
                    "not told  #" + rep));

                // Arm 2 — the same rule, same command, and this time the worker is told. A fresh
                // wording each rep keeps the two arms' prompts from being cached into each other.
                rules.stateRule("No Java records", RULE + "\n<!-- attempt " + rep + " -->", null);
                aware.add(attempt(dispatcher, git, rules.renderActive(12_000), spec.get(), checks,
                    "told      #" + rep));
            }
        }

        long unawareFailed = unaware.stream().filter(o -> !o.survived).count();
        long awareSurvived = aware.stream().filter(o -> o.survived).count();

        System.out.println("[ENFORCE-LIVE] " + reps + " attempts each way against " + model);
        System.out.println("[ENFORCE-LIVE] rule NOT in prompt, check on: "
            + unawareFailed + "/" + unaware.size() + " candidates FAILED verification");
        System.out.println("[ENFORCE-LIVE] rule IN prompt,     check on: "
            + awareSurvived + "/" + aware.size() + " candidates SURVIVED verification");
        unaware.forEach(o -> System.out.println("[ENFORCE-LIVE]   " + o));
        aware.forEach(o -> System.out.println("[ENFORCE-LIVE]   " + o));

        assertThat(unawareFailed)
            .as("a candidate that broke the checked rule was failed by verification, live — "
                + "otherwise the check proved nothing")
            .isGreaterThan(0);
        assertThat(unaware.stream().filter(o -> !o.survived).allMatch(o -> o.reason != null
                && o.reason.contains("no-java-records")))
            .as("and the operator is told which rule it broke")
            .isTrue();
        assertThat(awareSurvived)
            .as("and a candidate that obeyed it passed the very same command — otherwise the "
                + "check is simply failing everything")
            .isGreaterThan(0);
    }

    /** One live worker, then the real verification pipeline over the worktree it produced. */
    private static Outcome attempt(SwarmDispatcher dispatcher, GitService git, String guidelines,
                                   VerifySpec spec, List<GuidelineCheck> checks, String label) {
        Task task = new Task(UUID.randomUUID(), 1, "Add a Money value type", INSTRUCTIONS,
            Set.of("src/main"), Set.of("src"), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 500000, 12),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of("minimal-diff")), TaskState.READY);

        Outcome outcome = new Outcome(label, guidelines != null);
        for (WorkerResult result : dispatcher.dispatch(task, UUID.randomUUID(), null, guidelines)) {
            if (result.workspace() == null) {
                continue;
            }
            try (com.swarmcoder.verify.BuildBoxes.Box box = HarnessSandbox.boxes()
                    .open(result.workspace(), "Verification of a live candidate")) {
                VerificationReport report = new CommandPipelineVerifier()
                    .verify(box.target(), task, spec, checks);
                Verdicts.Verdict verdict = Verdicts.assess(report, List.of());
                outcome.survived = verdict.survived();
                outcome.reason = verdict.reason();
                outcome.declaredRecord = report.guidelineChecks() != null
                    && report.guidelineChecks().stream().anyMatch(c -> !c.passed());
                outcome.produced = result.candidate() != null
                    && result.candidate().diffUnified() != null
                    && !result.candidate().diffUnified().isBlank();
            } finally {
                git.removeWorktree(result.workspace());
            }
        }
        System.out.println("[ENFORCE-LIVE] " + outcome);
        return outcome;
    }

    private static final class Outcome {
        final String label;
        final boolean toldTheRule;
        boolean produced;
        boolean survived;
        boolean declaredRecord;
        String reason;

        Outcome(String label, boolean toldTheRule) {
            this.label = label;
            this.toldTheRule = toldTheRule;
        }

        @Override
        public String toString() {
            return label + ": told=" + toldTheRule + " produced=" + produced
                + " brokeTheRule=" + declaredRecord + " survived=" + survived;
        }
    }
}
