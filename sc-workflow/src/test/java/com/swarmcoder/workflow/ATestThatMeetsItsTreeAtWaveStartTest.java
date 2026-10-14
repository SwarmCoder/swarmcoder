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

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
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
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.FakeVllm;
import com.swarmcoder.swarm.SwarmEngineImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live local build, 2026-10-14 (section 111). The server task's test was written before any
 * build file existed; at the start of its wave the server module's build file, written by an
 * earlier wave, declared no library at all, and the run parked offering only "retry".
 *
 * <p>Two waves over a two-module tree. The first writes the shared module; the second, the
 * server task, owns the check. The stand-in acceptance stage prints what javac prints: the
 * missing package and the static-import error on the import line while the server's build file
 * does not declare the library; with {@code // misuse} and the library declared, a call that
 * does not match the library's signature.
 */
@ModelCodeOnThisPc
class ATestThatMeetsItsTreeAtWaveStartTest {

    private static final String SERVER_POM = "app-server/pom.xml";
    private static final String SERVER_CODE = "app-server/src/main/java/com/demo/server/Books.java";
    private static final String SHARED_CODE = "app-shared/src/main/java/com/demo/shared/Book.java";
    private static final String TEST = "app-server/src/test/java/swarm/accept/ServerTest.java";
    private static final String SERVER_TITLE = "Implement the server's book list";
    private static final String ARTIFACT = "com.libdep:libdep-check";
    private static final String DECLARES = "<dependencies><dependency><groupId>com.libdep</groupId>"
        + "<artifactId>libdep-check</artifactId><version>1.0</version><scope>test</scope>"
        + "</dependency></dependencies>";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;
    @TempDir
    Path m2;

    private String previousM2;
    private String base;
    private String testsCommit;
    private final List<String> repairAsks = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void aLocalRepositoryHoldingTheLibrary() throws Exception {
        Path jar = Files.createDirectories(m2.resolve("repository/com/libdep/libdep-check/1.0"))
            .resolve("libdep-check-1.0.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("com/libdep/check/Verify.class"));
            zip.write(new byte[] {0});
            zip.closeEntry();
        }
        previousM2 = System.getProperty("swarmcoder.sandbox.m2");
        System.setProperty("swarmcoder.sandbox.m2", m2.toString());
    }

    @AfterEach
    void restore() {
        if (previousM2 == null) {
            System.clearProperty("swarmcoder.sandbox.m2");
        } else {
            System.setProperty("swarmcoder.sandbox.m2", previousM2);
        }
    }

    @Test
    void aMissingTestDependencyIsNamedAndTheTaskMayDeclareItSoTheWorkGoesOn() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId, false, "// as written");
        Task server = serverTask();

        try (FakeVllm fake = new FakeVllm(conversation -> route(conversation, "// as written"));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seed(store, runId, server);
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> engine(fake, store).executeRun(run));

            assertThat(thrown).as(String.join("\n", events)).isNull();
            assertThat(repairAsks).as("the test's own lines are right: not sent back").isEmpty();
            assertThat(server.writeSet()).as("the task may now declare the library")
                .contains(SERVER_POM);
            assertThat(server.instructions()).contains(ARTIFACT).contains(SERVER_POM);
            assertThat(events).anyMatch(e -> e.contains("is added to what '" + SERVER_TITLE
                + "' may write") && e.contains(ARTIFACT));
            assertThat(events).noneMatch(e -> e.contains("everything this line names already"));
            assertThat(fake.requests).as("the wave was dispatched")
                .anyMatch(request -> request.contains(SERVER_TITLE));
        }
    }

    @Test
    void aTestWhoseOwnLinesAreWrongGoesBackToItsAuthorWithTheCompilerOutputAndAParkOffersRetest()
            throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId, true, "// misuse");
        Task server = serverTask();

        try (FakeVllm fake = new FakeVllm(conversation -> route(conversation, "// misuse"));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seed(store, runId, server);
            SwarmEngineImpl engine = engine(fake, store);
            GreenfieldWorkflow workflow = workflowOn(engine, fake, store);
            workflow.advance(run);

            assertThat(repairAsks).as("sent back once\n" + String.join("\n", events)).hasSize(1);
            assertThat(repairAsks.get(0)).as("with the compiler's output on the wave's tree")
                .contains("ServerTest.java:6: method that in class Verify cannot be applied")
                .contains("required: java.lang.String").contains("found: int");
            assertThat(server.writeSet()).as("nothing was granted for a fault in the test")
                .doesNotContain(SERVER_POM);

            Run stopped = store.root().runs.get(runId);
            assertThat(stopped.parkedAt()).as("the correction still misuses it").isNotNull();
            List<Decision> questions = store.root().decisions.values().stream()
                .filter(d -> d.kind() == DecisionKind.BLOCKED_TASK
                    && d.state() == DecisionState.PENDING).toList();
            assertThat(questions).hasSize(1);
            Task stored = store.root().taskGraphs.get(stopped.taskGraphId()).tasks().stream()
                .filter(t -> t.id().equals(server.id())).findFirst().orElseThrow();
            assertThat(stored.retestOfferedBy())
                .as("the stop is about the task's own test: its question takes retest, not "
                    + "only retry").isEqualTo(questions.get(0).id());
        }
    }

    // --- driving one run -------------------------------------------------------------------------

    private Task serverTask() {
        Task task = new Task(UUID.randomUUID(), 1, SERVER_TITLE, "Create " + SERVER_CODE + ".",
            Set.of("app-server/src/main/java/com/demo/server"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "the server lists books",
                "swarm.accept.ServerTest#listsBooks")),
            "app-server/src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(1, false, 0.2, 0.8, List.of()), TaskState.READY);
        task.setAuthoredTestPaths(List.of(TEST));
        return task;
    }

    private Run seed(ArtifactStore store, UUID runId, Task server) throws Exception {
        Task shared = new Task(UUID.randomUUID(), 1, "Implement the shared Book data model",
            "Create " + SHARED_CODE + ".", Set.of("app-shared/src/main/java/com/demo/shared"),
            Set.of(), List.of(), "app-shared/src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(1, false, 0.2, 0.8, List.of()), TaskState.READY);
        UUID graphId = UUID.randomUUID();
        TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(shared, server),
            List.of(new TaskEdge(shared.id(), server.id())));
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
            null, null, null, graphId, null, Instant.now(), null);
        run.setBaseCommit(base);
        run.setAcceptanceTestsCommit(testsCommit);
        store.append(() -> {
            store.root().taskGraphs.put(graphId, graph);
            store.root().runs.put(runId, run);
            return null;
        }).get();
        store.indexTasks(graph.tasks());
        return run;
    }

    private SwarmEngineImpl engine(FakeVllm fake, ArtifactStore store) {
        VllmClient client = new VllmClient(fake.baseUrl(), "", "fake-judge", true);
        SwarmEngineImpl engine = new SwarmEngineImpl(client, store,
            new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
            new ModelProfileRegistry(List.of(new ModelProfile("fake",
                new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                ModelProfile.Kind.WORKER, 0, 0))),
            new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
        workflowOn(engine, fake, store); // installs the real wave gate on the engine
        return engine;
    }

    private GreenfieldWorkflow workflowOn(SwarmEngineImpl engine, FakeVllm fake,
                                          ArtifactStore store) {
        VllmClient client = new VllmClient(fake.baseUrl(), "", "fake-judge", true);
        CloudGate gate = new CloudGate(0, null);
        GreenfieldWorkflow workflow = new GreenfieldWorkflow(new KoogAgentRuntime(), engine,
            client, store, new RunPersister(store), gate, repoDir,
            CloudRoles.allOn(client, gate), new GitService(repoDir));
        workflow.setEventLogger(events::add);
        return workflow;
    }

    // --- the scripted model --------------------------------------------------------------------

    private FakeVllm.Reply route(String conversation, String correctedMarker) {
        if (conversation.contains("do not compile for a reason described below")) {
            repairAsks.add(conversation);
            return FakeVllm.Reply.text(testFileJson(correctedMarker));
        }
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        ObjectMapper mapper = new ObjectMapper();
        boolean isServer = conversation.contains(SERVER_TITLE);
        if (isServer && conversation.contains(ARTIFACT) && !conversation.contains("wrote " + SERVER_POM)) {
            // What the task's instructions now say: declare the library in its build file.
            return write(mapper, SERVER_POM, "<project><artifactId>app-server</artifactId>"
                + DECLARES + "</project>\n");
        }
        String path = isServer ? SERVER_CODE : SHARED_CODE;
        if (conversation.contains("wrote " + path)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"done\"}");
        }
        return write(mapper, path, isServer
            ? "package com.demo.server;\npublic class Books { }\n"
            : "package com.demo.shared;\npublic class Book { }\n");
    }

    private static FakeVllm.Reply write(ObjectMapper mapper, String path, String content) {
        return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
            .put("path", path).put("content", content).toString());
    }

    private static String testSource(String marker) {
        return "package swarm.accept;\n\nimport static com.libdep.check.Verify.that;\n" + marker
            + "\nclass ServerTest {\n    void listsBooks() { that(1); }\n}\n";
    }

    private static String testFileJson(String marker) {
        String escaped = testSource(marker).replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n");
        return """
            {"files":[{"path":"%s","content":"%s"}],
             "wrote":[{"criterion":"the server lists books",
                       "test":"swarm.accept.ServerTest#listsBooks"}]}
            """.formatted(TEST, escaped);
    }

    // --- the repository ------------------------------------------------------------------------

    private void initRepo(UUID runId, boolean serverDeclaresTheLibrary, String marker)
            throws Exception {
        git("init -q");
        write("README.md", "hello\n");
        write("pom.xml", "<project><modules><module>app-shared</module>"
            + "<module>app-server</module></modules></project>\n");
        write("app-shared/pom.xml", "<project><artifactId>app-shared</artifactId>" + DECLARES
            + "</project>\n");
        // Written by an earlier wave in the live build: the server's build file, declaring nothing.
        write(SERVER_POM, "<project><artifactId>app-server</artifactId>"
            + (serverDeclaresTheLibrary ? DECLARES : "") + "</project>\n");
        write("app-shared/src/main/java/com/demo/shared/.keep", "");
        write("app-server/src/main/java/com/demo/server/.keep", "");
        write("tools/Accept.java", ACCEPT_STAGE);
        String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
        write(".swarmcoder/verify.yaml", """
            toolchain: gradle
            compile:
              - "echo compile-ok"
            acceptance:
              - "\\"%s\\" tools/Accept.java"
            timeoutSeconds: 60
            """.formatted(java));
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        base = git("rev-parse HEAD").strip();

        git("checkout -q -b swarm/tests/" + runId);
        write(TEST, testSource(marker));
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");
    }

    /**
     * The stand-in acceptance stage, printing what javac prints. While the server's build file
     * does not declare the library: the missing package and the static-import error on line 3,
     * and the unresolved method on line 6. With the library declared and {@code // misuse}: the
     * call on line 6 does not match the library's signature. Otherwise the test passes.
     */
    private static final String ACCEPT_STAGE = """
        import java.nio.file.*;
        import java.util.*;

        public class Accept {
            public static void main(String[] args) throws Exception {
                Path dir = Path.of("app-server/src/test/java/swarm/accept");
                List<Path> files = new ArrayList<>();
                if (Files.isDirectory(dir)) {
                    try (var s = Files.list(dir)) {
                        s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
                    }
                }
                if (files.isEmpty()) {
                    System.out.println("no acceptance test present; nothing run");
                    return;
                }
                boolean declared = Files.readString(Path.of("app-server/pom.xml"))
                    .contains("libdep-check");
                for (Path file : files) {
                    String where = file.toString().replace('\\\\', '/');
                    if (!declared) {
                        System.out.println("[ERROR] COMPILATION ERROR : ");
                        System.out.println(where + ":3: error: package com.libdep.check does not exist");
                        System.out.println(where + ":3: error: static import only from classes and interfaces");
                        System.out.println(where + ":6: error: cannot find symbol");
                        System.out.println("  symbol:   method that(int)");
                        System.out.println("  location: class ServerTest");
                        System.out.println("3 errors");
                        System.exit(1);
                    }
                    if (Files.readString(file).contains("// misuse")) {
                        System.out.println("[ERROR] COMPILATION ERROR : ");
                        System.out.println(where + ":6: error: method that in class Verify cannot be applied to given types;");
                        System.out.println("  required: java.lang.String");
                        System.out.println("  found:    int");
                        System.out.println("  reason: argument mismatch; int cannot be converted to java.lang.String");
                        System.out.println("1 error");
                        System.exit(1);
                    }
                }
                Files.createDirectories(Path.of("build/test-results"));
                StringBuilder xml = new StringBuilder();
                for (Path file : files) {
                    String name = file.getFileName().toString().replace(".java", "");
                    xml.append("<testcase classname=\\"swarm.accept.").append(name)
                       .append("\\" name=\\"listsBooks\\"></testcase>\\n");
                }
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=\\"accept\\" tests=\\"" + files.size()
                    + "\\" failures=\\"0\\" errors=\\"0\\" skipped=\\"0\\">\\n" + xml + "</testsuite>\\n");
            }
        }
        """;

    private void write(String relative, String content) throws Exception {
        Path file = repoDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String git(String args) throws Exception {
        List<String> command = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", "git " + args) : List.of("sh", "-c", "git " + args);
        Process p = new ProcessBuilder(command).directory(repoDir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
        return out;
    }
}
