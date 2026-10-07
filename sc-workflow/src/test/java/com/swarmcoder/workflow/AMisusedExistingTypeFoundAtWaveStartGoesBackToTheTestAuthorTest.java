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
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.FakeVllm;
import com.swarmcoder.swarm.SwarmEngineImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live harness run 62, 2026-10-01: an acceptance test called an existing method with the wrong
 * arguments. At TEST_AUTHORING javac reported only the missing types, so the red check passed
 * (rightly); two waves later, with those types delivered, the start of wave 3 found the real
 * errors and parked the run with ninety minutes of delivered work behind it.
 *
 * <p>Now that is a broken test, found late: before the wave is dispatched it goes back to the
 * test author ONCE, carrying every compiler error with its required/found/reason lines (not "and 5
 * more"); the correction is committed onto the run's tests ref and the wave goes ahead. Only a
 * correction that still cannot compile parks the run, with the full error text.
 *
 * <p>Two waves. The first delivers {@code Book}; the second owns the check. The stand-in
 * acceptance stage prints what javac does when the missing type is absent, and - once it is
 * present, as it is at the start of wave 2 - {@code // misuse} makes it print eight "cannot be
 * applied" errors, each with its follow-up lines.
 */
@ModelCodeOnThisPc
class AMisusedExistingTypeFoundAtWaveStartGoesBackToTheTestAuthorTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String RATING_TEST = ACCEPT_DIR + "/BookRatingTest.java";
    private static final String BOOK = "src/main/java/com/demo/shared/Book.java";
    private static final String CLIENT = "src/main/java/com/demo/client/BookList.java";
    private static final String CLIENT_TITLE = "Implement the client screen that shows a rating";
    private static final int ERRORS = 8;

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;
    private String testsCommit;
    private final List<String> repairAsks = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();

    @Test
    void theBrokenTestIsSentBackOnceWithEveryErrorAndTheWaveGoesAhead() throws Exception {
        Outcome outcome = drive("// fixed");

        assertThat(repairAsks).as("exactly one bounded repair\n" + String.join("\n", events))
            .hasSize(1);
        String ask = repairAsks.get(0);
        for (int i = 1; i <= ERRORS; i++) {
            assertThat(ask).as("error " + i + " is in the repair prompt, not summarised away")
                .contains("BookRatingTest.java:" + (4 + i) + ": method start" + i + " in class Server");
        }
        assertThat(ask).contains("required: int,String").contains("found: no arguments")
            .contains("reason: actual and formal argument lists differ in length")
            .doesNotContain("more)");
        assertThat(outcome.park).as("the correction held, so no park on a broken test")
            .satisfiesAnyOf(p -> assertThat(p).isNull(),
                p -> assertThat(p).doesNotContain("do not compile"));
        assertThat(outcome.testsCommitAfter).as("the correction was committed onto the tests ref")
            .isNotEqualTo(testsCommit);
        assertThat(events).anyMatch(e -> e.contains("Wave start: acceptance test(s)")
            && e.contains("the test author corrected them"));
        assertThat(outcome.requests).as("the wave was dispatched: a worker was asked for the file")
            .anyMatch(request -> request.contains(CLIENT));
    }

    @Test
    void aCorrectionThatStillDoesNotCompileParksWithTheFullErrorTextAndNoWorker() throws Exception {
        Outcome outcome = drive("// misuse");

        assertThat(repairAsks).as("one repair, not a loop").hasSize(1);
        assertThat(outcome.park).as(String.join("\n", events)).isNotNull()
            .contains("do not compile, and no task in this plan can make them compile")
            .contains("the corrected test still does not compile")
            .contains("required: int,String");
        for (int i = 1; i <= ERRORS; i++) {
            assertThat(outcome.park).contains("BookRatingTest.java:" + (4 + i) + ": method start" + i);
        }
        assertThat(outcome.requests).as("no worker was dispatched at the broken test")
            .noneMatch(request -> request.contains(CLIENT));
    }

    // --- driving one run -------------------------------------------------------------------------

    private record Outcome(String park, String testsCommitAfter, List<String> requests) {}

    private Outcome drive(String correctedMarker) throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);

        Task shared = task("Implement the shared Book data model", "Create " + BOOK + ".",
            Set.of("src/main/java/com/demo/shared"), List.of(), List.of());
        Task client = task(CLIENT_TITLE, "Create " + CLIENT + ".",
            Set.of("src/main/java/com/demo/client"),
            List.of(new AcceptanceCriterion(UUID.randomUUID(),
                "a rating can be assigned to a book",
                "swarm.accept.BookRatingTest#assignsRatingToBook")),
            List.of(RATING_TEST));

        try (FakeVllm fake = new FakeVllm(conversation -> route(conversation, correctedMarker));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(shared, client),
                List.of(new TaskEdge(shared.id(), client.id())));
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

            VllmClient client4j = new VllmClient(fake.baseUrl(), "", "fake-judge", true);
            SwarmEngineImpl engine = new SwarmEngineImpl(client4j, store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
            CloudGate gate = new CloudGate(0, null);
            // Constructing the workflow installs the REAL wave gate on the engine.
            GreenfieldWorkflow workflow = new GreenfieldWorkflow(new KoogAgentRuntime(), engine,
                client4j, store, new RunPersister(store), gate, repoDir,
                CloudRoles.allOn(client4j, gate), new GitService(repoDir));
            workflow.setEventLogger(events::add);

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> engine.executeRun(run));
            String park = thrown instanceof RunMustPark p ? p.brief()
                : thrown == null ? null : "unexpected: " + thrown;
            List<String> workerRequests = fake.requests.stream()
                .filter(request -> !request.contains("do not compile for a reason described below"))
                .toList();
            return new Outcome(park, run.acceptanceTestsCommit(), workerRequests);
        }
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
        boolean isClient = conversation.contains(CLIENT_TITLE);
        String path = isClient ? CLIENT : BOOK;
        if (conversation.contains("wrote " + path)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"done\"}");
        }
        return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
            .put("path", path)
            .put("content", isClient
                ? "package com.demo.client;\npublic class BookList { }\n"
                : "package com.demo.shared;\npublic class Book { public int rating; }\n")
            .toString());
    }

    private static String testSource(String marker) {
        return "package swarm.accept;\n// needs-type: " + BOOK + "\n" + marker + "\n"
            + "class BookRatingTest { void assignsRatingToBook() {} }\n";
    }

    private static String testFileJson(String marker) {
        String escaped = testSource(marker).replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n");
        return """
            {"files":[{"path":"%s","content":"%s"}],
             "wrote":[{"criterion":"a rating can be assigned to a book",
                       "test":"swarm.accept.BookRatingTest#assignsRatingToBook"}]}
            """.formatted(RATING_TEST, escaped);
    }

    // --- the repository ------------------------------------------------------------------------

    private void initRepo(UUID runId) throws Exception {
        git("init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        Files.createDirectories(repoDir.resolve("src/main/java/com/demo/shared"));
        Files.createDirectories(repoDir.resolve("src/main/java/com/demo/client"));
        Files.writeString(repoDir.resolve("src/main/java/com/demo/shared/.keep"), "");
        Files.createDirectories(repoDir.resolve("tools"));
        Files.writeString(repoDir.resolve("tools/Accept.java"), ACCEPT_STAGE);
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
        Files.writeString(repoDir.resolve(".swarmcoder/verify.yaml"), """
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
        write(RATING_TEST, testSource("// misuse"));
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");
    }

    /**
     * The stand-in acceptance stage. {@code // needs-type:} behaves as javac does when the file is
     * missing (names the symbol, writes no report). {@code // misuse} - with the type present -
     * prints eight "cannot be applied" errors, each with required/found/reason lines, the way javac
     * does. Anything else passes.
     */
    private static final String ACCEPT_STAGE = """
        import java.nio.file.*;
        import java.util.*;

        public class Accept {
            public static void main(String[] args) throws Exception {
                Path dir = Path.of("src/test/java/swarm/accept");
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
                for (Path file : files) {
                    for (String line : Files.readString(file).split("\\n")) {
                        String t = line.trim();
                        if (t.startsWith("// needs-type:")) {
                            String path = t.substring(14).trim();
                            if (!Files.exists(Path.of(path))) {
                                String type = path.substring(path.lastIndexOf('/') + 1)
                                    .replace(".java", "");
                                String pkg = path.substring("src/main/java/".length(),
                                    path.lastIndexOf('/')).replace('/', '.');
                                System.out.println(file + ":2: error: cannot find symbol");
                                System.out.println("  symbol:   class " + type);
                                System.out.println("  location: package " + pkg);
                                System.out.println("1 error");
                                System.exit(1);
                            }
                        }
                        if (t.equals("// misuse")) {
                            String where = file.toAbsolutePath().toString().replace('\\\\', '/');
                            System.out.println("[ERROR] COMPILATION ERROR : ");
                            for (int i = 1; i <= 8; i++) {
                                System.out.println("[ERROR] " + where + ":[" + (4 + i) + ",9] method start"
                                    + i + " in class Server cannot be applied to given types;");
                                System.out.println("[ERROR]   required: int,String");
                                System.out.println("[ERROR]   found:    no arguments");
                                System.out.println("[ERROR]   reason: actual and formal argument lists differ in length");
                            }
                            System.out.println("[INFO] 8 errors");
                            System.exit(1);
                        }
                    }
                }
                Files.createDirectories(Path.of("build/test-results"));
                StringBuilder xml = new StringBuilder();
                for (Path file : files) {
                    String name = file.getFileName().toString().replace(".java", "");
                    xml.append("<testcase classname=\\"swarm.accept.").append(name)
                       .append("\\" name=\\"assignsRatingToBook\\"></testcase>\\n");
                }
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=\\"accept\\" tests=\\"" + files.size()
                    + "\\" failures=\\"0\\" errors=\\"0\\" skipped=\\"0\\">\\n" + xml + "</testsuite>\\n");
            }
        }
        """;

    private static Task task(String title, String instructions, Set<String> writeSet,
                             List<AcceptanceCriterion> criteria, List<String> authoredTestPaths) {
        Task task = new Task(UUID.randomUUID(), 1, title, instructions, writeSet, Set.of(),
            criteria, "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(1, false, 0.2, 0.8, List.of()), TaskState.READY);
        task.setAuthoredTestPaths(authoredTestPaths);
        return task;
    }

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
