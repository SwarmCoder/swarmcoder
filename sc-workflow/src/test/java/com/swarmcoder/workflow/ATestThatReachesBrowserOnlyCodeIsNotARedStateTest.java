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
 * The runtime half of the harness-run-37 fix (2026-09-25), end to end through the workflow: an
 * acceptance test that RUNS on the tree before any worker and dies with
 * {@code UnsatisfiedLinkError} on a TeaVM native method is a broken test, not a red one. It goes
 * back to its author once; corrected, the run proceeds past TEST_AUTHORING; still broken, the run
 * parks saying it can never pass — and no worker is ever dispatched at it.
 *
 * <p>In run 37 itself this signal could not appear before the workers, because the class the test
 * called did not exist yet ("does not compile" — a healthy red). It does appear whenever the
 * browser-only class is already in the tree when the red-check runs: a change to an existing
 * client, or a later wave after the client task has delivered. The static check in
 * {@link AcceptanceTestReach} catches the import; this catches the test that reaches browser code
 * without importing it — through a helper, reflection, or a class the survey could not trace.
 *
 * <p>The acceptance stage is the same stand-in {@link ABrokenAcceptanceTestIsRepairedOnceBeforeTheRunParksTest}
 * uses, plus one marker: a test file saying {@code // runs-in: browser} is reported as an error
 * with run 37's exact exception and native frame, as surefire would write it.
 */
@ModelCodeOnThisPc
class ATestThatReachesBrowserOnlyCodeIsNotARedStateTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String RATING_TEST = ACCEPT_DIR + "/RatingTest.java";
    /** Inside the task's write set — a healthy, deliverable red once corrected. */
    private static final String DELIVERABLE = "src/main/java/com/demo/client/Widget.java";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    @Test
    void correctedOnceTheRunProceedsPastTestAuthoring() throws Exception {
        AtomicInteger repairs = new AtomicInteger();
        List<String> repairAsks = new CopyOnWriteArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        Run finished = run(events, conversation -> route(conversation, repairs, repairAsks,
            "// needs-type: " + DELIVERABLE));

        assertThat(events).as(String.join("\n", events))
            .anyMatch(e -> e.contains("reached code that can only run in a browser")
                && e.contains("a broken test, not a healthy red state"))
            .anyMatch(e -> e.contains("reached browser-only code; the test author corrected them "
                + "and they are red again"));
        assertThat(finished.state()).as(String.join("\n", events))
            .isNotEqualTo(RunState.TEST_AUTHORING);
        assertThat(repairs.get()).as("exactly one bounded repair").isEqualTo(1);
        assertThat(repairAsks.get(0))
            .contains("Your test ran and the test reached code that can only run in a browser")
            .contains("Window.current(Native Method)");
    }

    @Test
    void stillReachingTheBrowserAfterOneCorrectionTheRunParks() throws Exception {
        AtomicInteger repairs = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        Run finished = run(events, conversation -> route(conversation, repairs,
            new CopyOnWriteArrayList<>(), "// runs-in: browser"));

        assertThat(finished.state()).as(String.join("\n", events)).isEqualTo(RunState.TEST_AUTHORING);
        assertThat(finished.parkReason()).as(String.join("\n", events))
            .contains("can never pass")
            .contains("UnsatisfiedLinkError")
            .contains("the corrected test still does")
            .contains("Decide which side is wrong");
        assertThat(repairs.get()).isEqualTo(1);
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

    private static String route(String conversation, AtomicInteger repairs, List<String> repairAsks,
                                String correctedMarker) {
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
        if (conversation.contains("call code that can only run in a web browser")) {
            // The bounded, one-shot repair call (TestAuthorClient.repairUnrunnableTest).
            repairs.incrementAndGet();
            repairAsks.add(conversation);
            return testFile(correctedMarker);
        }
        if (conversation.contains("You are a test author")) {
            return testFile("// runs-in: browser"); // the original: compiles, then hits the browser
        }
        return "I decline to produce JSON.";
    }

    private static String testFile(String marker) {
        return """
            {"files":[{"path":"%s",
              "content":"package swarm.accept;\\n%s\\nclass RatingTest {\\n    void works() {}\\n}\\n"}],
             "wrote":[{"criterion":"the rating is shown","test":"swarm.accept.RatingTest#works"}]}
            """.formatted(RATING_TEST, marker);
    }

    // --- the repository -------------------------------------------------------------------------

    private void initRepo() throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        Files.createDirectories(repo.resolve("src/main/java/com/demo/client"));
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
     * {@code // needs-type:} behaves as javac does when the file is missing; {@code // runs-in:
     * browser} writes the surefire report of run 37's failure — an ERROR, not a failure, with the
     * UnsatisfiedLinkError header and the TeaVM native frame; anything else passes.
     */
    private static final String ACCEPT_STAGE = """
        import java.nio.file.*;
        import java.util.*;

        public class Accept {
            public static void main(String[] args) throws Exception {
                char q = 34;
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
                StringBuilder xml = new StringBuilder();
                int errors = 0;
                for (Path file : files) {
                    String name = file.getFileName().toString().replace(".java", "");
                    boolean browser = false;
                    for (String line : Files.readString(file).split("\\n")) {
                        String t = line.trim();
                        if (t.startsWith("// needs-type:")) {
                            String path = t.substring(14).trim();
                            if (!Files.exists(Path.of(path))) {
                                String type = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
                                String pkg = path.substring("src/main/java/".length(),
                                    path.lastIndexOf('/')).replace('/', '.');
                                System.out.println(file + ":2: error: cannot find symbol");
                                System.out.println("  symbol:   class " + type);
                                System.out.println("  location: package " + pkg);
                                System.out.println("1 error");
                                System.exit(1);
                            }
                        }
                        if (t.startsWith("// runs-in: browser")) {
                            browser = true;
                        }
                    }
                    xml.append("<testcase classname=" + q + "swarm.accept." + name + q
                        + " name=" + q + "works" + q + ">");
                    if (browser) {
                        errors++;
                        xml.append("<error message=" + q
                            + "'org.teavm.jso.browser.Window org.teavm.jso.browser.Window.current()'" + q
                            + " type=" + q + "java.lang.UnsatisfiedLinkError" + q + ">"
                            + "java.lang.UnsatisfiedLinkError: 'org.teavm.jso.browser.Window "
                            + "org.teavm.jso.browser.Window.current()'\\n"
                            + "\\tat org.teavm.jso.browser.Window.current(Native Method)\\n"
                            + "\\tat com.demo.client.Widget.&lt;init&gt;(Widget.java:5)\\n"
                            + "\\tat swarm.accept." + name + ".works(" + name + ".java:4)\\n"
                            + "</error>");
                    }
                    xml.append("</testcase>\\n");
                }
                Files.createDirectories(Path.of("build/test-results"));
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=" + q + "accept" + q + " tests=" + q + files.size() + q
                    + " failures=" + q + "0" + q + " errors=" + q + errors + q
                    + " skipped=" + q + "0" + q + ">\\n" + xml + "</testsuite>\\n");
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
