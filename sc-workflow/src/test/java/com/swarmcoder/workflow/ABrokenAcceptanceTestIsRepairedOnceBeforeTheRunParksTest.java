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
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.Task;
import com.swarmcoder.git.BasePin;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decided design, end to end (author decision, 2026-09-05, after harness run 26): a freshly
 * authored acceptance test that does not compile for a reason no task can ever fix — a package
 * nobody delivers and no task's write set covers — is sent back to its author ONCE, before the
 * run parks. Corrected, the run proceeds; still broken, it parks with a sentence naming the
 * acceptance module and what its own build file declares — never silently treated as a healthy
 * TDD red state, which is what let every candidate of harness run 26's third wave die against a
 * test that could never compile there.
 *
 * <p>The stand-in acceptance stage below is the same device {@code
 * AWaveWhoseTestsNeedATypeNobodyDeliversParksTest} uses: it does what javac does when a test's
 * {@code // needs-type:} file is missing — names the missing symbol and its package — without a
 * real compiler, so the scenario runs in milliseconds and stays about the workflow's own wiring.
 */
@ModelCodeOnThisPc
class ABrokenAcceptanceTestIsRepairedOnceBeforeTheRunParksTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String RATING_TEST = ACCEPT_DIR + "/RatingTest.java";
    /** Outside the task's write set (src/main/java/com/demo/client) and no contract names it. */
    private static final String UNDELIVERABLE = "src/main/java/com/demo/shared/Rating.java";
    /** Inside the task's write set — a healthy, deliverable red once the author is told so. */
    private static final String DELIVERABLE = "src/main/java/com/demo/client/Widget.java";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    @Test
    void correctedOnceTheRunProceedsPastTestAuthoring() throws Exception {
        AtomicInteger authorCalls = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        Run finished = run(events, conversation -> route(conversation, authorCalls, DELIVERABLE));

        assertThat(events).as(String.join("\n", events))
            .anyMatch(e -> e.contains("is not delivered by any task and not in any task's write "
                + "set — this is a broken test, not a healthy red state"))
            .anyMatch(e -> e.contains("were corrected by the test author and are red again"));
        assertThat(finished.state()).as(String.join("\n", events))
            .isNotEqualTo(RunState.TEST_AUTHORING);
        assertThat(authorCalls.get()).as("one original attempt plus one repair attempt").isEqualTo(2);
    }

    @Test
    void stillBrokenAfterOneCorrectionTheRunParksNamingTheModule() throws Exception {
        AtomicInteger authorCalls = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        Run finished = run(events, conversation -> route(conversation, authorCalls, UNDELIVERABLE));

        assertThat(finished.state()).as(String.join("\n", events)).isEqualTo(RunState.TEST_AUTHORING);
        assertThat(finished.parkReason()).as(String.join("\n", events))
            .contains("`" + toType(UNDELIVERABLE) + "`")
            .contains("was asked once to correct this and did not")
            .contains("The acceptance module is")
            .contains("Decide which side is wrong");
        assertThat(authorCalls.get()).isEqualTo(2);
    }

    // --- driving one run -------------------------------------------------------------------------

    private Run run(List<String> events, java.util.function.Function<String, String> router)
            throws Exception {
        initRepo();
        UUID runId = UUID.randomUUID();
        try (ScriptedLlm llm = new ScriptedLlm(router::apply);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime unused = spec -> {
                throw new UnsupportedOperationException("no workers in this test");
            };
            VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
            CloudGate gate = new CloudGate(0, null);
            GitService git = new GitService(repo);
            WorkflowEngine engine = new WorkflowEngine(unused, r -> r, client, store, gate,
                repo, CloudRoles.allOn(client, gate), git);
            engine.setEventLogger(events::add);

            Run created = new Run(runId, com.swarmcoder.domain.WorkflowKind.GREENFIELD,
                RunState.INTAKE, null, null, null, null, null, Instant.now(),
                new RunReport(runId, "show the book's rating"));
            assertThat(BasePin.pin(created, git)).isNotNull();
            engine.advance(created);
            awaitTerminal(store, runId);
            return store.root().runs.get(runId);
        }
    }

    // --- the scripted roles ------------------------------------------------------------------------

    /**
     * @param secondAttemptNeeds the file the REPAIRED test names via {@code // needs-type:} — inside
     *                           the task's write set (deliverable) or not (still broken), so both
     *                           tests share one router and differ only in this.
     */
    private static String route(String conversation, AtomicInteger authorCalls,
                                String secondAttemptNeeds) {
        if (conversation.contains("You are an AI planner")) {
            return """
                {"tasks":[
                  {"id":"t1","title":"Show the book's rating",
                   "instructions":"Create src/main/java/com/demo/client/Widget.java",
                   "writeSet":["src/main/java/com/demo/client"],"readSet":[],
                   "criteria":[{"text":"the rating is shown",
                     "testClassOrFile":"swarm.accept.RatingTest#works"}],
                   "requirementRefs":[]}
                ],"edges":[]}
                """;
        }
        if (conversation.contains("do not compile for a reason described below")) {
            // The bounded, one-shot repair call (TestAuthorClient.repairBrokenTest).
            authorCalls.incrementAndGet();
            return testFile(secondAttemptNeeds);
        }
        if (conversation.contains("You are a test author")) {
            // The original TEST_AUTHORING call: always broken, on purpose.
            authorCalls.incrementAndGet();
            return testFile(UNDELIVERABLE);
        }
        return "I decline to produce JSON.";
    }

    private static String testFile(String needs) {
        return """
            {"files":[{"path":"%s",
              "content":"package swarm.accept;\\n// needs-type: %s\\nclass RatingTest {\\n    void works() {}\\n}\\n"}],
             "wrote":[{"criterion":"the rating is shown","test":"swarm.accept.RatingTest#works"}]}
            """.formatted(RATING_TEST, needs);
    }

    private static String toType(String path) {
        String withoutRoot = path.substring("src/main/java/".length());
        String withoutExt = withoutRoot.substring(0, withoutRoot.length() - ".java".length());
        int slash = withoutExt.lastIndexOf('/');
        return withoutExt.substring(0, slash).replace('/', '.') + "." + withoutExt.substring(slash + 1);
    }

    // --- the repository -------------------------------------------------------------------------

    private void initRepo() throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        Files.createDirectories(repo.resolve("src/main/java/com/demo/client"));
        Files.createDirectories(repo.resolve("src/main/java/com/demo/shared"));
        Files.writeString(repo.resolve("src/main/java/com/demo/client/.keep"), "");
        Files.createDirectories(repo.resolve("tools"));
        Files.writeString(repo.resolve("tools/Accept.java"), ACCEPT_STAGE);
        Files.createDirectories(repo.resolve(".swarmcoder"));
        String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
        Files.writeString(repo.resolve(".swarmcoder/verify.yaml"), """
            toolchain: gradle
            compile:
              - "echo compile-ok"
            acceptance:
              - "\\"%s\\" tools/Accept.java"
            timeoutSeconds: 60
            """.formatted(java));
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
    }

    /**
     * Does what javac does when a test's {@code // needs-type:} file is missing: names the missing
     * symbol and its package, and exits without a report — no real compiler needed. The SAME device
     * {@code AWaveWhoseTestsNeedATypeNobodyDeliversParksTest} uses.
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
                       .append("\\" name=\\"works\\"></testcase>\\n");
                }
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=\\"accept\\" tests=\\"" + files.size()
                    + "\\" failures=\\"0\\" errors=\\"0\\" skipped=\\"0\\">\\n" + xml + "</testsuite>\\n");
            }
        }
        """;

    private static void awaitTerminal(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && (run.state() == RunState.DELIVERED || run.state() == RunState.ABORTED)) {
                return;
            }
            if (store.root().decisions.values().stream().anyMatch(d -> runId.equals(d.runId()))) {
                return; // parked — the assertion on the state will say so
            }
            Thread.sleep(50);
        }
    }

    private String git(String args) throws Exception {
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
        return out;
    }
}
