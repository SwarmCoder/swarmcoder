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
import com.swarmcoder.app.config.AgentModelConfig;
import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.RolesConfig;
import com.swarmcoder.app.config.SwarmConfig;
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
import com.swarmcoder.workflow.ArchitectClient;
import com.swarmcoder.workflow.CloudRoles;
import com.swarmcoder.workflow.DesignReviewerClient;
import com.swarmcoder.workflow.TestAuthorClient;
import com.swarmcoder.workflow.WorkflowEngine;
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.NotRun;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.SwarmPolicy;
import org.eclipse.serializer.reference.Lazy;

/**
 * Full workflow driven by the REAL per-role config (the routing the app uses in production):
 * cloud roles (e.g. deepseek/GLM) do design/review/plan/test-authoring; the local qwen workers do
 * code generation. This is the one path LiveEndToEndTest does not
 * cover (it runs qwen for everything).
 *
 * <p><b>Makes real, billed cloud API calls</b>, so it is opt-in via the environment variable
 * {@code SWARMCODER_CONFIG_E2E=true} and self-skips unless {@code ~/.swarmcoder/config.yaml}
 * configures cloud roles + a local worker family.
 */
@RunsWhen(Need.PAID_CLOUD_MODELS)
class ConfigDrivenE2ETest {

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    @Test
    void greenfieldRunUsesConfiguredCloudRolesAndLocalWorkers() throws Exception {
        SwarmConfig config = ConfigLoader.loadDefaultConfig();
        RolesConfig roles = config.roles();
        String test = "ConfigDrivenE2ETest#greenfieldRunUsesConfiguredCloudRolesAndLocalWorkers";
        NotRun.needed(roles != null && hasEndpoint(roles.architect())
                && hasEndpoint(roles.testAuthor()), test,
            "it was switched on, but ~/.swarmcoder/config.yaml names no cloud endpoint for the "
                + "architect and the test author, so there is nothing to run against.");
        NotRun.needed(roles.workerFamilies() != null && !roles.workerFamilies().isEmpty()
                && hasEndpoint(roles.workerFamilies().get(0)), test,
            "it was switched on, but ~/.swarmcoder/config.yaml names no worker model.");

        AgentModelConfig worker = roles.workerFamilies().get(0);
        // The same task policy production stamps on planned tasks (swarm: config block).
        SwarmPolicy taskPolicy = ProjectContext.taskPolicyFrom(config.swarm());
        System.out.println("[CFG-E2E] architect=" + describe(roles.architect())
            + " reviewer=" + describe(roles.designReviewer()) + " testAuthor=" + describe(roles.testAuthor())
            + " judge=" + describe(roles.judge()) + " worker=" + describe(worker)
            + " swarm=" + (taskPolicy == null ? "(none — n=1)"
                : "n=" + taskPolicy.n() + " split=" + taskPolicy.splitAcrossFamilies()
                    + " temp=" + taskPolicy.tempMin() + ".." + taskPolicy.tempMax()));

        initDemoRepo();

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            CloudGate cloudGate = new CloudGate(50_000_000, null);
            GitService git = new GitService(repo);

            VllmClient workerClient = client(worker);            // local qwen (also the default client)
            VllmClient judgeClient = client(roles.judge());      // configured judge (falls back to worker)

            List<ModelProfile> profiles = new ArrayList<>();
            profiles.add(new ModelProfile(worker.modelName(),
                new AgentRuntime.ModelEndpoint(worker.baseUrl(), worker.apiKey(), worker.modelName(), 65536),
                ModelProfile.Kind.WORKER, 0, 0));

            SwarmEngineImpl swarmEngine = new SwarmEngineImpl(
                judgeClient != null ? judgeClient : workerClient, store,
                new InferenceScheduler(16, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
                new ModelProfileRegistry(profiles), git, content -> null, cloudGate, () -> null);
            // Real models: their commands, their tests and the builds of their code run in a
            // container. No Docker, no run.
            swarmEngine.setSandbox(HarnessSandbox.required());
            if (config.swarm() != null) {
                swarmEngine.setDispatchTuning(config.swarm().taskGroupsAtOnce(),
                    config.swarm().dispatch() == null ? 0 : config.swarm().dispatch().staggerMs());
            }

            // Cloud roles do the reasoning-heavy front half; the architect stamps the
            // config-derived swarm policy so EXECUTING runs the real N-worker swarm.
            CloudRoles cloudRoles = new CloudRoles(
                new ArchitectClient(client(roles.architect()), cloudGate, taskPolicy),
                new DesignReviewerClient(client(roles.designReviewer()), cloudGate),
                new TestAuthorClient(client(roles.testAuthor()), cloudGate));

            WorkflowEngine engine = new WorkflowEngine(new KoogAgentRuntime(), swarmEngine, workerClient,
                store, cloudGate, repo, cloudRoles, git);
            engine.setEventLogger(msg -> System.out.println("[CFG-E2E] " + msg));

            UUID runId = UUID.randomUUID();
            engine.advance(new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE, null, null, null, null, null,
                Instant.now(), new RunReport(runId,
                    "Add a public method `multiply(int a, int b)` returning a*b to the Calculator "
                    + "class in src/main/java/com/example/calc/Calculator.java. Do not change existing behavior.")));

            RunState terminal = awaitTerminal(store, runId, 900_000);
            System.out.println("[CFG-E2E] terminal state = " + terminal);

            assertThat(store.root().designs).as("cloud architect produced a design").isNotEmpty();
            assertThat(store.root().taskGraphs).as("cloud planner produced a plan").isNotEmpty();
            assertThat(terminal).as("the build finished and handed back for judgment")
                .isEqualTo(RunState.DELIVERED);
            // DELIVERED alone can be hollow: FinalIntegrator treats zero winners as
            // nothing-to-integrate. The run only counts if a real change won.
            List<CandidateSolution> selected = store.root().candidateArchives.values().stream()
                .map(lazy -> Lazy.get(lazy))
                .filter(CandidateSolution.class::isInstance)
                .map(CandidateSolution.class::cast)
                .filter(c -> c.state() == CandidateState.SELECTED)
                .toList();
            assertThat(selected).as("at least one candidate was SELECTED").isNotEmpty();
            assertThat(selected).as("the selected candidate carries a real diff")
                .anyMatch(c -> c.diffUnified() != null && !c.diffUnified().isBlank());
        }
    }

    private static boolean hasEndpoint(AgentModelConfig role) {
        return role != null && role.baseUrl() != null && !role.baseUrl().isBlank()
            && role.modelName() != null && !role.modelName().isBlank();
    }

    private static VllmClient client(AgentModelConfig role) {
        return hasEndpoint(role)
            ? new VllmClient(role.baseUrl(), role.apiKey(), role.modelName(), true, role.thinking())
            : null;
    }

    private static String describe(AgentModelConfig role) {
        return role == null || role.modelName() == null ? "(default)" : role.modelName();
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
                    System.out.println("[CFG-E2E] state -> " + run.state());
                    last = run.state();
                }
                if (run.state() == RunState.DELIVERED || run.state() == RunState.ABORTED) {
                    return run.state();
                }
            }
            Thread.sleep(1000);
        }
        return last;
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
