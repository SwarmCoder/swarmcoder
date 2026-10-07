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
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does a house rule an operator writes actually change the code the machine produces?
 *
 * <p>The same task is given to the same live model twice over: once with the rule ACTIVE among the
 * project's rules, once with it retired. Nothing else differs
 * — same repository, same instructions, same temperature, same endpoint, interleaved so a slow or
 * busy shared server cannot land on one arm only. The task asks for a small immutable value type,
 * which a modern Java model writes as a {@code record} unless told otherwise, so the rule has
 * something real to overturn.
 *
 * <p>The check is mechanical, not a judgement: the produced diff either declares a record or it
 * does not.
 *
 * <pre>
 * mvn -o test -pl sc-app -am -Dtest=LiveGuidelineEffectTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://192.168.0.10:8002/v1 \
 *   -Dswarmcoder.live.model=qwen3.8-27b -Dswarmcoder.live.reps=4
 * </pre>
 */
class LiveGuidelineEffectTest {

    /** The house rule. Deliberately the one this repository already enforces on its own types. */
    private static final String RULE = """
        Never declare a Java record. Java records are banned in this repository.
        Write a final class with private final fields, a public constructor, and explicit
        accessor methods, plus equals, hashCode and toString written out by hand.
        """;

    /** A record is the idiomatic answer here, so obeying the rule costs the model something. */
    private static final String INSTRUCTIONS = """
        Create a new file src/main/java/com/example/calc/Money.java in package com.example.calc.

        Money is a small immutable value type with exactly two components:
          - amount: a long, the value in minor units (cents)
          - currency: a String, a three-letter ISO currency code

        Keep it short and idiomatic for modern Java. Do not modify any existing file.
        You do NOT need to run a build. Write the file with write_file, then call report_done.
        """;

    /** Matches a record declaration on an ADDED line of a unified diff. */
    private static final Pattern RECORD_DECLARATION =
        Pattern.compile("(?m)^\\+\\s*(public\\s+|final\\s+|static\\s+)*record\\s+\\w+");

    @TempDir
    Path work;

    @Test
    @EnabledIfSystemProperty(named = "swarmcoder.live.baseUrl", matches = ".+",
        disabledReason = "needs a live model endpoint; see the class javadoc")
    void aProjectRuleChangesTheCodeTheModelWrites() throws Exception {
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        String model = System.getProperty("swarmcoder.live.model", "qwen3.8-27b");
        int reps = Integer.getInteger("swarmcoder.live.reps", 4);

        Path repo = DemoRepo.create(work.resolve("repo"));

        List<String> withRule = new ArrayList<>();
        List<String> withoutRule = new ArrayList<>();

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
                // Interleaved: the two arms share whatever the shared server is doing.
                for (LearnedGuideline rule : rules.activeRules()) {
                    rules.setStatus(rule.id(), GuidelineStatus.RETIRED);
                }
                withoutRule.add(oneAttempt(dispatcher, git, rules, "no rule  #" + rep));

                // A fresh wording every rep keeps the two arms' prompts from being cached into
                // each other.
                rules.stateRule("No Java records", RULE + "\n<!-- attempt " + rep + " -->", null);
                withRule.add(oneAttempt(dispatcher, git, rules, "with rule #" + rep));
            }
        }

        int violationsWith = (int) withRule.stream().filter(LiveGuidelineEffectTest::declaresRecord).count();
        int violationsWithout = (int) withoutRule.stream().filter(LiveGuidelineEffectTest::declaresRecord).count();
        int producedWith = (int) withRule.stream().filter(d -> !d.isBlank()).count();
        int producedWithout = (int) withoutRule.stream().filter(d -> !d.isBlank()).count();

        System.out.println("[GUIDELINE] " + reps + " attempts each way against " + model);
        System.out.println("[GUIDELINE] rule ABSENT : " + producedWithout + " produced code, "
            + violationsWithout + " declared a record");
        System.out.println("[GUIDELINE] rule PRESENT: " + producedWith + " produced code, "
            + violationsWith + " declared a record");

        assertThat(producedWithout + producedWith)
            .as("the live model produced code in at least some attempts — otherwise nothing "
                + "below measures the rule, only an unreachable or confused endpoint")
            .isGreaterThan(0);
        assertThat(violationsWith)
            .as("with the house rule among the project's rules, no attempt declared a "
                + "record (rule absent: " + violationsWithout + "/" + producedWithout + ")")
            .isZero();
        assertThat(violationsWithout)
            .as("and without it at least one attempt did — otherwise the rule was never tested, "
                + "because the model was going to comply anyway")
            .isGreaterThan(0);
    }

    private static boolean declaresRecord(String diff) {
        return RECORD_DECLARATION.matcher(diff).find();
    }

    /** One single-worker dispatch; returns the diff it produced (possibly empty). */
    private static String oneAttempt(SwarmDispatcher dispatcher, GitService git,
                                     ProjectRules rules, String label) {
        String guidelines = rules.renderActive(12_000);
        Task task = new Task(UUID.randomUUID(), 1, "Add a Money value type", INSTRUCTIONS,
            Set.of("src/main"), Set.of("src"), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 500000, 12),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of("minimal-diff")), TaskState.READY);

        String diff = "";
        for (WorkerResult result : dispatcher.dispatch(task, UUID.randomUUID(), null, guidelines)) {
            if (result.candidate() != null && result.candidate().diffUnified() != null) {
                diff = result.candidate().diffUnified();
            }
            if (result.workspace() != null) {
                git.removeWorktree(result.workspace());
            }
        }
        System.out.println("=== " + label + " (guidelines segment "
            + (guidelines == null ? "ABSENT" : "PRESENT") + ", record declared: "
            + declaresRecord(diff) + ") ===");
        System.out.println(diff.isBlank() ? "(no change produced)" : diff);
        return diff;
    }
}
