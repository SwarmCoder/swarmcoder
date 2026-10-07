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
import com.swarmcoder.domain.CandidateSolution;
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
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.swarm.SwarmEngineImpl;
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

/**
 * The decided design, on the real swarm stack: <b>when a later wave's tests cannot compile because
 * of a type that no task delivers and this task cannot create, the run stops there — before a
 * single worker is dispatched.</b>
 *
 * <h2>Why the existing gates could not catch it</h2>
 *
 * <p>A freshly written acceptance test is SUPPOSED not to compile: it names the code the task is
 * about to write. So "does not compile" is a valid red state at test-authoring time, and again at
 * wave time, and again at verification, where it is honestly attributed to the tree rather than to
 * the candidate. Every one of those answers is correct.
 *
 * <p>On run 13 they were all correct about a test that could never compile for anybody. Two waves
 * were built and integrated; the third wave's tests needed
 * {@code com.swarmcoder.demo.bookshelf.Rating}, which the first two waves had not delivered and
 * the third wave's task — which writes only the client module — could not create. Both of its
 * candidates died, the repair wave would have died the same way, and three waves of real work
 * produced nothing.
 *
 * <h2>What is asserted here</h2>
 *
 * <p>Two waves. The first delivers the shared {@code Book}. The second owns the check, writes only
 * the client package, and its acceptance test needs a shared {@code Rating} nobody promised. The
 * stand-in acceptance stage does what javac does: names the missing symbol and its package, and
 * exits without a report.
 *
 * <ol>
 *   <li>the first wave runs and wins, as it should;</li>
 *   <li>the run then parks, with a message naming the type, the task, and what that task is
 *       allowed to write;</li>
 *   <li>and no worker is ever dispatched at the second task.</li>
 * </ol>
 */
@ModelCodeOnThisPc
class AWaveWhoseTestsNeedATypeNobodyDeliversParksTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String RATING_TEST = ACCEPT_DIR + "/BookRatingTest.java";
    private static final String BOOK = "src/main/java/com/demo/shared/Book.java";
    private static final String CLIENT = "src/main/java/com/demo/client/BookList.java";
    /** The type the test needs. Nobody's write set can hold it and no contract promises it. */
    private static final String MISSING = "src/main/java/com/demo/shared/Rating.java";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;
    private String testsCommit;

    @Test
    void theSecondWaveIsNotDispatchedAndTheRunSaysWhichTypeNobodyWillBuild() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);

        Task shared = task("Implement the shared Book data model", "Create " + BOOK + ".",
            Set.of("src/main/java/com/demo/shared"), List.of(), List.of());
        Task client = task("Implement the client screen that shows a rating",
            "Create " + CLIENT + ".", Set.of("src/main/java/com/demo/client"),
            List.of(new AcceptanceCriterion(UUID.randomUUID(),
                "a rating can be assigned to a book",
                "swarm.accept.BookRatingTest#assignsRatingToBook")),
            List.of(RATING_TEST));

        List<CandidateSolution> archived;
        List<String> requests;
        try (FakeVllm fake = new FakeVllm(AWaveWhoseTestsNeedATypeNobodyDeliversParksTest::route);
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
            // Constructing the workflow installs the REAL wave gate on the engine. Nothing else
            // about the workflow is used here.
            new GreenfieldWorkflow(new KoogAgentRuntime(), engine, client4j, store,
                new RunPersister(store), new CloudGate(1_000_000, null), repoDir,
                new CloudRoles(null, null, null), new GitService(repoDir));

            RunMustPark park = (RunMustPark) org.assertj.core.api.Assertions.catchThrowable(
                () -> engine.executeRun(run));
            assertThat(park).as("the wave gate stopped the run").isNotNull();
            // The whole brief, not the exception's one-line message: the operator reads brief().
            assertThat(park.brief())
                .contains("Implement the client screen that shows a rating")
                .contains("`com.demo.shared.Rating`")
                .contains("which no task delivers")
                .contains("cannot create — the plan or the tests are wrong")
                .contains("may only write src/main/java/com/demo/client")
                .contains("add the missing type to the design as a contract");

            archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
            requests = List.copyOf(fake.requests);
        }

        assertThat(archived).as("the first wave ran and won, which is what makes this a LATE stop")
            .isNotEmpty();
        assertThat(archived).extracting(CandidateSolution::taskId)
            .as("and nothing was archived for the task whose tests can never compile")
            .doesNotContain(client.id());
        assertThat(requests)
            .as("no worker was ever asked to write the client file")
            .noneMatch(request -> request.contains(CLIENT));
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
        write(RATING_TEST, "package swarm.accept;\n// needs-type: " + MISSING
            + "\nclass BookRatingTest { void assignsRatingToBook() {} }\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");
    }

    /**
     * The stand-in acceptance stage. A test naming a {@code // needs-type:} file that is not there
     * does not "fail" — it does what javac does when the class does not exist: prints the missing
     * symbol and its package, and exits without writing any report at all.
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
                        if (line.trim().startsWith("// needs-type:")) {
                            String path = line.trim().substring(14).trim();
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

    /** The first wave's worker. The second wave's is never reached, which is the point. */
    private static FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        ObjectMapper mapper = new ObjectMapper();
        if (conversation.contains("wrote " + BOOK)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"Book delivered\"}");
        }
        return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
            .put("path", BOOK)
            .put("content", "package com.demo.shared;\npublic class Book { public int rating; }\n")
            .toString());
    }

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
