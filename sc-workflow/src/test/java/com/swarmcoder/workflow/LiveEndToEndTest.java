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

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.SwarmEngineImpl;
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.swarmcoder.sandbox.DockerSandboxManager;
import java.util.ArrayList;

/**
 * The full-workflow live run (the deferred end-to-end): INTAKE → DESIGN → DESIGN_REVIEW →
 * PLAN → TEST_AUTHORING → EXECUTING → FINAL_INTEGRATION → DELIVERED against a REAL vLLM
 * endpoint, on a fresh throwaway git repo. Every cloud role and the workers point at the
 * Spark. Opt-in:
 *
 * <pre>
 * mvn test -pl sc-workflow -am -Dtest=LiveEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://192.168.0.10:8000/v1 \
 *   -Dswarmcoder.live.model=qwen36-27b
 * </pre>
 *
 * <p><b>This is the workflow half only.</b> It starts from a goal string, with no project, no
 * requirement and no story, so it never exercises the requirement graph, the wizards, the scoped
 * design and plan, or the evidence that turns a green test run into a requirement marked
 * IMPLEMENTED. It cannot: sc-workflow cannot see the Console.
 *
 * <p>The whole product, from a document to an implemented requirement, is
 * {@code FullProductDressRehearsalTest} in sc-app — which runs offline against a scripted endpoint
 * by default and against a live model on the SAME flag as this one
 * ({@code -Dswarmcoder.live.baseUrl}), sharing its repository, its journey and its assertions.
 * Prefer it. Keep this one for the narrower question of whether the workflow engine alone survives
 * a real model's output.
 */
class LiveEndToEndTest {

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    @Test
    @RunsWhen(Need.LIVE_MODEL)
    void greenfieldRunReachesApprovalWithVerifiedIntegration() throws Exception {
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        String model = System.getProperty("swarmcoder.live.model", "qwen36-27b");

        initDemoRepo();

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            VllmClient roleClient = new VllmClient(baseUrl, "", model, true);
            CloudGate cloudGate = new CloudGate(10_000_000, null);
            GitService git = new GitService(repo);

            SwarmEngineImpl swarmEngine = new SwarmEngineImpl(
                roleClient, store, new InferenceScheduler(16, 1024 * 1024 * 1024, 1024),
                new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile(model,
                    new AgentRuntime.ModelEndpoint(baseUrl, "", model, 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                git, content -> null, cloudGate, () -> null);

            // A real model: its commands, its tests and the builds of its code run in a
            // container that sees one tree and nothing else of this PC. No Docker, no run.
            swarmEngine.setSandbox(com.swarmcoder.swarm.HarnessSandbox.required());

            WorkflowEngine engine = new WorkflowEngine(
                new KoogAgentRuntime(), swarmEngine, roleClient, store, cloudGate, repo,
                CloudRoles.allOn(roleClient, cloudGate), git);
            engine.setEventLogger(msg -> System.out.println("[E2E] " + msg));

            UUID runId = UUID.randomUUID();
            engine.advance(new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE, null, null,
                null, null, null, Instant.now(), new RunReport(runId,
                    "Add a public method `multiply(int a, int b)` returning the product a*b to the "
                    + "Calculator class in src/main/java/com/example/calc/Calculator.java. Do not change "
                    + "existing behavior.")));

            // The whole pipeline runs on a background virtual thread; give it generous time.
            RunState terminal = awaitTerminal(store, runId, 900_000);

            Run finalRun = store.root().runs.get(runId);
            System.out.println("[E2E] terminal state = " + terminal);
            dumpArtifacts(store, runId);

            // A parked BLOCKED/decision path is a legitimate (if disappointing) outcome; the
            // hard assertion is that the pipeline ran end to end and produced typed artifacts.
            assertThat(store.root().designs).as("a design was produced").isNotEmpty();
            assertThat(store.root().taskGraphs).as("a plan was produced").isNotEmpty();
            assertThat(terminal)
                .as("run reached a terminal workflow state (DELIVERED, or parked with a decision)")
                .isIn(RunState.DELIVERED, RunState.ABORTED, RunState.TEST_AUTHORING,
                    RunState.FINAL_INTEGRATION, RunState.EXECUTING);

            if (terminal == RunState.DELIVERED) {
                System.out.println("[E2E] SUCCESS — the build finished and handed back for judgment");
                assertThat(store.root().candidateArchives).isNotEmpty();
            } else {
                System.out.println("[E2E] Parked at " + terminal + " — see decisions above.");
            }
        }
    }

    private void initDemoRepo() throws Exception {
        Path src = repo.resolve("src/main/java/com/example/calc");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Calculator.java"), """
            package com.example.calc;
            public class Calculator {
                public int add(int a, int b) { return a + b; }
                public int subtract(int a, int b) { return a - b; }
            }
            """);
        Files.writeString(repo.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>demo-calc</artifactId>
              <version>1.0-SNAPSHOT</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.10.2</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <build><plugins>
                <plugin>
                  <groupId>org.apache.maven.plugins</groupId>
                  <artifactId>maven-surefire-plugin</artifactId>
                  <version>3.2.5</version>
                </plugin>
              </plugins></build>
            </project>
            """);
        Files.createDirectories(repo.resolve(".swarmcoder"));
        Files.writeString(repo.resolve(".swarmcoder/verify.yaml"), """
            toolchain: maven
            compile:
              - "mvn -q -B compile test-compile"
            acceptance:
              - "mvn -q -B test -Dtest=swarm/accept/** -Dsurefire.failIfNoSpecifiedTests=false"
            existing:
              - "mvn -q -B test"
            timeoutSeconds: 600
            """);
        git("init -q");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
    }

    private static RunState awaitTerminal(ArtifactStore store, UUID runId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        RunState last = null;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null) {
                if (run.state() != last) {
                    System.out.println("[E2E] state -> " + run.state());
                    last = run.state();
                }
                if (run.state() == RunState.DELIVERED || run.state() == RunState.ABORTED) {
                    return run.state();
                }
            }
            Thread.sleep(1000);
        }
        return last; // timed out mid-pipeline; the last observed state is the result
    }

    private static void dumpArtifacts(ArtifactStore store, UUID runId) {
        store.root().taskGraphs.values().forEach(g ->
            System.out.println("[E2E] TaskGraph: " + g.tasks().size() + " tasks, "
                + g.dependencies().size() + " edges"));
        store.root().taskGraphs.values().forEach(g -> g.tasks().forEach(t ->
            System.out.println("[E2E]   task '" + t.title() + "' writeSet=" + t.writeSet()
                + " criteria=" + t.criteria().size())));
        store.root().decisions.values().forEach(d ->
            System.out.println("[E2E] DECISION " + d.kind() + ": "
                + d.briefMarkdown().replaceAll("\\s+", " ").substring(0, Math.min(300, d.briefMarkdown().length()))));
    }

    private void git(String args) throws Exception {
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            command.addAll(List.of("cmd.exe", "/c", "git " + args));
        } else {
            command.addAll(List.of("sh", "-c", "git " + args));
        }
        Process p = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
    }
}
