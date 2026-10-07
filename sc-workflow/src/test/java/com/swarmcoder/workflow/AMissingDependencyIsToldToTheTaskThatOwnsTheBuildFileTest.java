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
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
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
 * Live run 64, 2026-10-02, through the workflow: the acceptance test imports a package the module
 * does not depend on yet, and the task that writes the module owns its build file. The red check
 * must not park the run or send the test back to its author; it must say so in the log and tell the
 * task, in its own instructions, which package it needs and which file to add the dependency to.
 *
 * <p>The acceptance stage is a stand-in that prints, for a test file saying {@code // needs-package},
 * the two errors javac and Maven print for a package that is not on the classpath.
 */
@ModelCodeOnThisPc
class AMissingDependencyIsToldToTheTaskThatOwnsTheBuildFileTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String TEST_PATH = ACCEPT_DIR + "/DocumentTest.java";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    @Test
    void theRunGoesOnAndTheTaskIsTold() throws Exception {
        AtomicInteger repairs = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        List<String> instructions = new CopyOnWriteArrayList<>();
        Run finished = run(events, instructions, repairs);

        assertThat(finished.state()).as(String.join("\n", events)).isNotEqualTo(RunState.TEST_AUTHORING);
        assertThat(repairs.get()).as("a healthy red is not sent back to the author").isZero();
        assertThat(events).as(String.join("\n", events))
            .anyMatch(e -> e.contains("package com.example.lib [Thing] is not on the classpath of "
                + "the module its test is in") && e.contains("has pom.xml in its write set")
                && e.contains("a healthy red state"))
            .noneMatch(e -> e.contains("do not compile for a reason") || e.contains("sending it back"));
        assertThat(instructions).hasSize(1);
        assertThat(instructions.get(0)).contains("Edit src/main/java/org/jsoup/nodes/Document.java")
            .contains("The acceptance test needs package com.example.lib (type Thing), which is not "
                + "on this module's classpath yet; add the dependency that provides it to pom.xml.");
    }

    // --- driving one run -------------------------------------------------------------------------

    private Run run(List<String> events, List<String> instructions, AtomicInteger repairs)
            throws Exception {
        initRepo();
        UUID runId = UUID.randomUUID();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> route(conversation, repairs));
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
                new RunReport(runId, "setting the charset on an empty XML document must not throw"));
            assertThat(BasePin.pin(created, git)).isNotNull();
            engine.advance(created);
            awaitTerminal(store, runId);
            for (TaskGraph graph : store.root().taskGraphs.values()) {
                for (Task task : graph.tasks()) {
                    instructions.add(task.instructions());
                }
            }
            return store.root().runs.get(runId);
        }
    }

    private static String route(String conversation, AtomicInteger repairs) {
        if (conversation.contains("You are an AI planner")) {
            return """
                {"tasks":[
                  {"id":"t1","title":"Guard Document.ensureMetaCharsetElement against empty XML documents",
                   "instructions":"Edit src/main/java/org/jsoup/nodes/Document.java",
                   "writeSet":["src/main/java/org/jsoup/nodes/Document.java","pom.xml"],"readSet":[],
                   "criteria":[{"text":"charset on an empty XML document does not throw",
                     "testClassOrFile":"swarm.accept.DocumentTest#works"}],
                   "requirementRefs":[]}
                ],"edges":[]}
                """;
        }
        if (conversation.contains("do not compile for a reason described below")) {
            repairs.incrementAndGet();
            return testFileJson();
        }
        if (conversation.contains("You are a test author")) {
            return testFileJson();
        }
        return "I decline to produce JSON.";
    }

    /** Line 3 is the import the stand-in stage's errors point at. */
    private static String testSource() {
        return "package swarm.accept;\n// needs-package\nimport com.example.lib.Thing;\n\n"
            + "class DocumentTest { Thing thing; void works() {} }\n";
    }

    private static String testFileJson() {
        String escaped = testSource().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
            {"files":[{"path":"%s","content":"%s"}],
             "wrote":[{"criterion":"charset on an empty XML document does not throw",
                       "test":"swarm.accept.DocumentTest#works"}]}
            """.formatted(TEST_PATH, escaped);
    }

    // --- the repository -------------------------------------------------------------------------

    private void initRepo() throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        Path doc = repo.resolve("src/main/java/org/jsoup/nodes/Document.java");
        Files.createDirectories(doc.getParent());
        Files.writeString(doc, "package org.jsoup.nodes;\n\npublic class Document {}\n");
        Files.writeString(repo.resolve("pom.xml"), "<project/>\n");
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

    /** {@code // needs-package} prints Maven's two errors for an import of a package off the classpath. */
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
                        if (line.trim().equals("// needs-package")) {
                            String where = file.toAbsolutePath().toString().replace('\\\\', '/');
                            String[] errors = {
                                "[ERROR] " + where + ":[3,24] package com.example.lib does not exist",
                                "[ERROR] " + where + ":[5,19] cannot find symbol",
                                "[ERROR]   symbol:   class Thing",
                                "[ERROR]   location: class swarm.accept.DocumentTest"};
                            System.out.println("[ERROR] COMPILATION ERROR : ");
                            for (String e : errors) System.out.println(e);
                            System.out.println("[INFO] 2 errors");
                            System.out.println("[ERROR] Failed to execute goal org.apache.maven.plugins:"
                                + "maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on "
                                + "project jsoup: Compilation failure");
                            for (String e : errors) System.out.println(e);
                            System.exit(1);
                        }
                    }
                }
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
                return; // parked: the assertion on the state will say so
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
