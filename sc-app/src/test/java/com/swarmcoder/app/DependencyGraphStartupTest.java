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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import com.swarmcoder.console.api.RoleEntryDto;
import java.util.List;

/**
 * Constructs the composition root end-to-end (the first test that does) so a wiring regression —
 * like the {@link ProjectContext} extraction — fails the build rather than the app at launch. Runs
 * fully offline: clients are built lazily, git is disabled with no repoPath, and an isolated
 * {@code user.home} keeps the real config/store untouched.
 */
class DependencyGraphStartupTest {

    @Test
    void buildsGraphAndPersistsTheDefaultProject(@TempDir Path home) throws Exception {
        Path scDir = Files.createDirectories(home.resolve(".swarmcoder"));
        // Minimal config: no repoPath (git disabled), no roles (default client). Context folders set.
        Files.writeString(scDir.resolve("config.yaml"), """
            contextPaths:
              - "%s"
            """.formatted(home.resolve("ctx").toString().replace('\\', '/')));
        Files.createDirectories(home.resolve("ctx"));

        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        DependencyGraph graph = null;
        try {
            graph = new DependencyGraph();
            assertThat(graph.currentProject()).isNotNull();
            assertThat(graph.currentProject().project().name()).isEqualTo("default");
            assertThat(graph.currentProject().workflowEngine()).isNotNull();
            assertThat(graph.swarmEngine).isSameAs(graph.currentProject().swarmEngine());
            // The default project was persisted and is listable.
            assertThat(graph.listProjects()).extracting(p -> p.name()).contains("default");

            // Registry: add a second project (distinct folder) and switch to it.
            Path second = Files.createDirectories(home.resolve("proj2"));
            var ctx2 = graph.addProject("proj2", second.toString(), List.of());
            assertThat(graph.listProjects()).extracting(p -> p.name()).contains("default", "proj2");
            assertThat(graph.currentProject().project().name()).isEqualTo("default"); // add doesn't switch
            graph.switchProject(ctx2.project().id());
            assertThat(graph.currentProject().project().name()).isEqualTo("proj2");
            assertThat(graph.currentProject()).isSameAs(ctx2); // same context reused, not rebuilt

            // The registry a restart resumes through answers with each project's OWN engine — its
            // repository, its git service, its locked modules. Resume used to drive every unfinished
            // run in the store through the DEFAULT project's engine, so a second project's run was
            // continued against the first project's code with the first project's protections.
            var defaultProject = graph.listProjects().stream()
                .filter(p -> "default".equals(p.name())).findFirst().orElseThrow();
            assertThat(graph.workflowEngineFor(ctx2.project().id()))
                .isSameAs(ctx2.workflowEngine());
            assertThat(graph.workflowEngineFor(defaultProject.id()))
                .isNotSameAs(ctx2.workflowEngine());
            // A run persisted before multi-project existed belongs to the DEFAULT project — not to
            // whichever project happens to be open, which here is proj2.
            assertThat(graph.workflowEngineFor(null))
                .isSameAs(graph.workflowEngineFor(defaultProject.id()));
            assertThat(graph.workflowEngineFor(java.util.UUID.randomUUID()))
                .as("an unknown project gets no engine, rather than somebody else's")
                .isNull();
        } finally {
            if (graph != null) {
                graph.close();
            }
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void configFormsRoundTripAndInvalidateProjectContexts(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".swarmcoder"));
        Files.writeString(home.resolve(".swarmcoder/config.yaml"), "consolePort: null\n");
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        DependencyGraph graph = null;
        try {
            graph = new DependencyGraph();
            var forms = new ConfigFormsBridge(graph);

            // Global roles: edit the architect entry, save, read back from the reloaded config.
            var roles = forms.globalRoles();
            var architect = roles.stream().filter(r -> "architect".equals(r.getRoleId()))
                .findFirst().orElseThrow();
            architect.setBaseUrl("https://api.example.com");
            architect.setModelName("super-model");
            architect.setThinking(1);
            assertThat(forms.saveGlobalRoles(roles)).isEmpty();
            var reloaded = forms.globalRoles().stream()
                .filter(r -> "architect".equals(r.getRoleId())).findFirst().orElseThrow();
            assertThat(reloaded.getBaseUrl()).isEqualTo("https://api.example.com");
            assertThat(reloaded.getModelName()).isEqualTo("super-model");
            assertThat(reloaded.getThinking()).isEqualTo(1);
            assertThat(graph.config.roles().architect().thinking()).isTrue();
            // A row whose model settings were never touched must not grow a quirks block: an
            // all-null block in config.yaml reads as a set of decisions nobody made.
            assertThat(graph.config.roles().architect().quirks()).isNull();

            // The model's own settings round-trip through the same form. Every one of these was a
            // constant or a JVM-wide switch, so this is the path by which a NEW model gets set up
            // without editing YAML by hand.
            var withShape = forms.globalRoles();
            var worker = withShape.stream()
                .filter(r -> "architect".equals(r.getRoleId())).findFirst().orElseThrow();
            assertThat(worker.getAvailableShapes())
                .describedAs("the shape catalogue reaches the settings screen")
                .contains("qwen38-flash-next-125b");
            worker.setShape("qwen38-flash-next-125b");
            worker.setMaxOutputTokens(8192);
            worker.setThinkingKwarg("none");
            worker.setKvBytesPerToken(2048);
            worker.setMaxConcurrentSequences(4);
            worker.setVerified(1);
            assertThat(forms.saveGlobalRoles(withShape)).isEmpty();

            var savedArchitect = graph.config.roles().architect();
            assertThat(savedArchitect.shape()).isEqualTo("qwen38-flash-next-125b");
            assertThat(savedArchitect.quirks().maxOutputTokens()).isEqualTo(8192);
            assertThat(savedArchitect.resolvedQuirks().maxOutputTokens()).isEqualTo(8192);
            assertThat(savedArchitect.resolvedQuirks().sendsThinkingKwarg())
                .describedAs("'none' means this model has no such argument, so send nothing")
                .isFalse();
            assertThat(savedArchitect.resolvedQuirks().noThinkDirective())
                .describedAs("unstated settings still come from the shape")
                .isEqualTo("/no_think");
            assertThat(savedArchitect.resolvedQuirks().kvBytesPerToken()).isEqualTo(2048);
            assertThat(savedArchitect.resolvedQuirks().maxConcurrentSequences()).isEqualTo(4);
            assertThat(savedArchitect.resolvedQuirks().verified()).isTrue();

            var reread = forms.globalRoles().stream()
                .filter(r -> "architect".equals(r.getRoleId())).findFirst().orElseThrow();
            assertThat(reread.getShape()).isEqualTo("qwen38-flash-next-125b");
            assertThat(reread.getMaxOutputTokens()).isEqualTo(8192);
            assertThat(reread.getKvBytesPerToken()).isEqualTo(2048);
            assertThat(reread.getVerified()).isEqualTo(1);

            // Budgets round-trip.
            var budgets = forms.budgets();
            budgets.setMaxCloudTokensPerRun(1_234_567);
            assertThat(forms.saveBudgets(budgets)).isEmpty();
            assertThat(graph.config.budgets().maxCloudTokensPerRun()).isEqualTo(1_234_567);

            // Project config: save context folders + a judge override; project.yaml lands on
            // disk, the Project record updates, and the rebuilt context sees the new folders.
            Path projectDir = Files.createDirectories(home.resolve("projX"));
            Path ctxDir = Files.createDirectories(home.resolve("ctxX"));
            var projectCtx = graph.addProject("projX", projectDir.toString(), List.of());
            String projectId = projectCtx.project().id().toString();
            var override = new RoleEntryDto();
            override.setRoleId("judge");
            override.setBaseUrl("http://localhost:9999/v1");
            override.setModelName("local-judge");
            // 6 attempts per piece of work: the project layer of story → project → global.
            assertThat(forms.saveProjectConfig(projectId, ctxDir.toString(),
                List.of(override), 6)).isEmpty();

            assertThat(Files.readString(projectDir.resolve(".swarmcoder/project.yaml")))
                .contains("local-judge").contains("ctxX").contains("nPerTask: 6");
            assertThat(forms.projectWorkersPerTask(projectId)).isEqualTo(6);
            // 0 clears it, and clearing writes NO swarm block — an inherited number written down
            // would read as a decision somebody made, and the run log would credit this project.
            assertThat(forms.saveProjectConfig(projectId, ctxDir.toString(),
                List.of(override), 0)).isEmpty();
            assertThat(forms.projectWorkersPerTask(projectId)).isZero();
            assertThat(Files.readString(projectDir.resolve(".swarmcoder/project.yaml")))
                .doesNotContain("nPerTask");
            assertThat(graph.artifactStore.getProject(projectCtx.project().id()).contextPaths())
                .containsExactly(ctxDir.toString());
            assertThat(forms.projectRoles(projectId).stream()
                .filter(r -> "judge".equals(r.getRoleId())).findFirst().orElseThrow()
                .getModelName()).isEqualTo("local-judge");
            // The old context instance was dropped; switching builds a fresh one.
            assertThat(graph.switchProject(projectCtx.project().id())).isNotSameAs(projectCtx);
        } finally {
            if (graph != null) {
                graph.close();
            }
            System.setProperty("user.home", originalHome);
        }
    }

    /**
     * The two wizard roles resolve independently of the chat dock, and an unset one still lands on
     * chat.
     *
     * <p>Both agents constructed the requirement graph and the story slices on {@code roles.chat}
     * for a purely historical reason — they were the {@code /brd} and {@code /backlog} chat modes,
     * and each wizard inherited the chat model when its command was removed. Giving them their own
     * slots is only worth anything if setting one moves that agent and NOTHING else, so that is what
     * this asserts: three endpoints, three roles, no leakage between them.
     */
    @Test
    void wizardRolesResolveIndependentlyOfChatAndFallBackToIt(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".swarmcoder"));
        // requirementsAnalyst is set; storyPlanner is NOT, so it must land on chat — not on the
        // analyst (they are peers, and a planner silently borrowing a frontier analyst would bill
        // the operator for a model they only configured for one of the two).
        Files.writeString(home.resolve(".swarmcoder/config.yaml"), """
            consolePort: null
            roles:
              utility:
                baseUrl: http://localhost:8001/v1
                modelName: small-local
              chat:
                baseUrl: http://localhost:8002/v1
                modelName: chat-local
              requirementsAnalyst:
                baseUrl: https://frontier.example.com/v1
                modelName: big-analyst
            """);
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        DependencyGraph graph = null;
        try {
            graph = new DependencyGraph();
            ProjectContext project = graph.currentProject();

            // Asserted on the model name as well as the host: the endpoint alone would pass even if
            // a role picked up a neighbour's model at the same base URL. VllmClient normalises the
            // "/v1" suffix away, which is why the hosts below carry none.
            assertThat(project.analystClient().modelName())
                .describedAs("the BRD author uses its own model")
                .isEqualTo("big-analyst");
            assertThat(project.analystClient().baseUrl()).isEqualTo("https://frontier.example.com");
            assertThat(project.chatClient().modelName())
                .describedAs("pointing the analyst at a frontier model does NOT move the chat dock")
                .isEqualTo("chat-local");
            assertThat(project.plannerClient().modelName())
                .describedAs("an unset storyPlanner falls back to chat, not to the analyst")
                .isEqualTo("chat-local");

            // The settings form must round-trip both new ids. The mapping back into RolesConfig is
            // POSITIONAL over twelve arguments, and these two were inserted in the middle of it —
            // get the order wrong and roles silently swap endpoints with no compile error.
            var forms = new ConfigFormsBridge(graph);
            var entries = forms.globalRoles();
            assertThat(entries).extracting(RoleEntryDto::getRoleId)
                .contains("requirementsAnalyst", "storyPlanner");
            assertThat(entries.stream().filter(r -> "requirementsAnalyst".equals(r.getRoleId()))
                .findFirst().orElseThrow().getModelName()).isEqualTo("big-analyst");
            var planner = entries.stream().filter(r -> "storyPlanner".equals(r.getRoleId()))
                .findFirst().orElseThrow();
            planner.setBaseUrl("https://frontier.example.com/v1");
            planner.setModelName("big-planner");
            assertThat(forms.saveGlobalRoles(entries)).isEmpty();

            assertThat(graph.config.roles().storyPlanner().modelName()).isEqualTo("big-planner");
            assertThat(graph.config.roles().requirementsAnalyst().modelName())
                .describedAs("saving one role does not clobber its neighbour in the record")
                .isEqualTo("big-analyst");
            assertThat(graph.config.roles().chat().modelName()).isEqualTo("chat-local");
            assertThat(graph.config.roles().utility().modelName()).isEqualTo("small-local");
        } finally {
            if (graph != null) {
                graph.close();
            }
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void startsWithEveryModelServerSwitchedOff(@TempDir Path home) throws Exception {
        // SwarmCoder asks each model server what its limits are at startup. The operator restarts
        // those servers independently all day, so a box that does not answer must cost a moment and
        // a log line — never a failed boot and never a hang. Port 1 refuses instantly; port 9 on a
        // documentation-only address is the case that would hang if the timeout were missing.
        Path scDir = Files.createDirectories(home.resolve(".swarmcoder"));
        Files.writeString(scDir.resolve("config.yaml"), """
            roles:
              workerFamilies:
                - baseUrl: "http://127.0.0.1:1/v1"
                  apiKey: ""
                  modelName: "not-running"
                  shape: "qwen38-flash-next-125b"
            spark:
              instances:
                - id: "not-running"
                  baseUrl: "http://127.0.0.1:1/v1"
                  servedModelName: "not-running"
            """);

        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        DependencyGraph graph = null;
        long startedAt = System.currentTimeMillis();
        try {
            graph = new DependencyGraph();
            assertThat(graph.currentProject()).isNotNull();
        } finally {
            if (graph != null) {
                graph.close();
            }
            System.setProperty("user.home", originalHome);
        }
        assertThat(System.currentTimeMillis() - startedAt)
            .describedAs("a dead model server must not add minutes to a restart")
            .isLessThan(60_000L);
    }

    /**
     * The help desk's expert tier (ExpertDesk javadoc) with no {@code roles.utility} or
     * {@code roles.architect} in config: today's behaviour — the desk answers a question nothing
     * free can settle by saying so plainly — has to be paired with a startup-time WARN rather than
     * one on every such question. {@code clientFor} makes {@code roles.utility()} non-null even
     * here (it falls back to the process-wide default endpoint), so the regression this guards is
     * "wired an escalation to that default and spammed a WARN, or a real request, per question".
     */
    @Test
    void noExpertRoleConfiguredWarnsOnceAtStartup(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".swarmcoder"));
        Files.writeString(home.resolve(".swarmcoder/config.yaml"), "consolePort: null\n");
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());

        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProjectContext.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
            new ch.qos.logback.core.read.ListAppender<>();
        captured.start();
        logger.addAppender(captured);

        DependencyGraph graph = null;
        try {
            graph = new DependencyGraph();
            assertThat(graph.currentProject()).isNotNull();

            List<String> warnings = captured.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("expert tier"))
                .toList();
            assertThat(warnings)
                .as("logged once at startup, not once per question the desk cannot escalate")
                .hasSize(1);
            assertThat(warnings.get(0)).contains("no utility or architect role is configured");
        } finally {
            logger.detachAppender(captured);
            if (graph != null) {
                graph.close();
            }
            System.setProperty("user.home", originalHome);
        }
    }
}
