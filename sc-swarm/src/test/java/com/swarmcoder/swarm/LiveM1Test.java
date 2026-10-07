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

import com.swarmcoder.domain.*;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;

/**
 * The M1 acceptance run (spec §19): typed Task in, verified green diff on a branch out —
 * against a REAL vLLM endpoint and a real target repo. Opt-in:
 *
 * <pre>
 * mvn test -pl sc-swarm -am -Dtest=LiveM1Test -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://spark:8000/v1 \
 *   -Dswarmcoder.live.model=qwen36-27b \
 *   -Dswarmcoder.live.repo=/abs/path/to/dev/demo-repo
 * </pre>
 */
class LiveM1Test {

    @TempDir
    Path storeDir;

    @Test
    @RunsWhen(Need.LIVE_MODEL)
    void liveWorkerProducesVerifiedGreenDiff() throws Exception {
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        String model = System.getProperty("swarmcoder.live.model", "qwen36-27b");
        Path repo = Path.of(System.getProperty("swarmcoder.live.repo"));

        Task task = new Task(UUID.randomUUID(), 1,
            "Add multiply to Calculator",
            """
            Add a public method `multiply(int a, int b)` to the Calculator class at
            src/main/java/com/example/calc/Calculator.java. It returns the product of a and b.
            Do not change any other behavior. Verify your change compiles and the existing
            tests still pass by running: mvn -q -B test
            Then call report_done with a one-line summary.
            """,
            Set.of("src/main"), Set.of("src"),
            List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 500000, 20),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of("minimal-diff")), TaskState.READY);

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            store.append(() -> {
                store.root().taskGraphs.put(graphId,
                    new TaskGraph(graphId, 1, null, List.of(task), List.of()));
                return null;
            }).get();

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(baseUrl, "", model, true), // judge on the same endpoint for M1
                store,
                new InferenceScheduler(16, 1024 * 1024 * 1024, 1024),
                new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(
                    new ModelProfile(model,
                        new AgentRuntime.ModelEndpoint(baseUrl, "", model, 65536),
                        ModelProfile.Kind.WORKER, 0, 0))),
                new GitService(repo),
                content -> null,
                new CloudGate(3_000_000, null));
            // A real model chooses the commands: they run in a container, or not at all.
            engine.setSandbox(HarnessSandbox.required());

            engine.executeRun(new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graphId, null, Instant.now(), null));

            List<CandidateSolution> archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy))
                .toList();
            assertThat(archived).isNotEmpty();

            CandidateSolution winner = archived.stream()
                .filter(c -> c.state() == CandidateState.SELECTED)
                .findFirst().orElse(null);

            // Evidence for the human reading the test output:
            for (CandidateSolution c : archived) {
                System.out.println("=== candidate " + c.workerIndex() + " state=" + c.state()
                    + " kill=" + c.killReason()
                    + (c.judge() != null ? " judge=" + c.judge().score() + " (" + c.judge().rationale() + ")" : ""));
                if (c.verification() != null) {
                    System.out.println("    compiles=" + c.verification().compiles()
                        + " existing: " + c.verification().existing().passed() + " passed / "
                        + c.verification().existing().failed() + " failed");
                }
                System.out.println("--- diff ---\n" + c.diffUnified() + "\n------------");
            }

            assertThat(winner).as("M1 acceptance: a verified candidate must be selected").isNotNull();
            assertThat(winner.verification().compiles()).isTrue();
            assertThat(winner.verification().existing().failed()).isZero();
            assertThat(winner.diffUnified()).contains("multiply");
        }
    }
}
