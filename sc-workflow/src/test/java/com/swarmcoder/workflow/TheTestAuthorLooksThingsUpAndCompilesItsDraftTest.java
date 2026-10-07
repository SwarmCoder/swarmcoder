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
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test author as an agent (owner decision, 2026-10-02), through the workflow: it looks a fact
 * up in the checkout before it writes, gives its draft to {@code compile_test}, is told by the red
 * check itself that the draft is a broken test - the very fault of brownfield harness run 43, a
 * {@code Parser} passed where the existing constructor takes a {@code String} - corrects it, is
 * told the correction is a healthy red, and hands it in. The red check after hand-in then passes
 * with no repair call at all, where the one-reply author needed its one bounded re-ask.
 *
 * <p>The model is scripted ({@link ScriptedAgentLlm}); everything else is real: the agent runtime,
 * the lookups on the files of a real git repository, the throwaway tree the draft is compiled in,
 * and the red check's own classifiers. The acceptance stage is the stand-in
 * {@link AMisusedExistingTypeGoesBackToTheTestAuthorTest} uses.
 */
@ModelCodeOnThisPc
class TheTestAuthorLooksThingsUpAndCompilesItsDraftTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String DOCUMENT_TEST = ACCEPT_DIR + "/DocumentTest.java";
    private static final String DELIVERABLE = "src/main/java/org/jsoup/nodes/Guard.java";
    private static final String CRITERION = "charset on an empty XML document does not throw";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;
    @TempDir
    Path cache;

    @Test
    void itReadsTheCheckoutIsToldItsDraftIsBrokenFixesItAndHandsInAHealthyRed() throws Exception {
        AtomicInteger repairs = new AtomicInteger();
        AtomicInteger oneReplyAuthoring = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        initRepo();
        UUID runId = UUID.randomUUID();
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                 conversation -> oneReply(conversation, repairs, oneReplyAuthoring),
                 (turn, conversation) -> switch (turn) {
                     case 1 -> ScriptedAgentLlm.Turn.call("read_file",
                         Map.of("path", "project/src/main/java/org/jsoup/nodes/Document.java"));
                     case 2 -> ScriptedAgentLlm.Turn.call("compile_test",
                         Map.of("path", DOCUMENT_TEST, "content", testSource("// misuse")));
                     case 3 -> ScriptedAgentLlm.Turn.call("compile_test",
                         Map.of("path", DOCUMENT_TEST, "content",
                             testSource("// needs-type: " + DELIVERABLE)));
                     default -> ScriptedAgentLlm.Turn.call("report_done",
                         Map.of("wrote", CRITERION + " => swarm.accept.DocumentTest#works"));
                 });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime noWorkers = spec -> {
                throw new UnsupportedOperationException("no workers in this test");
            };
            VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
            CloudGate gate = new CloudGate(0, null);
            GitService git = new GitService(repo);
            CloudRoles roles = CloudRoles.allOn(client, gate);
            KnowledgeCurator curator = new KnowledgeCurator(
                List.of(new KnowledgeCurator.Root("project", repo, "local")), null, cache);
            roles.testAuthor().setLookupAgent(new LookupAgent(curator, null, repo, gate,
                new KoogAgentRuntime(), null, null));
            WorkflowEngine engine = new WorkflowEngine(noWorkers, r -> r, client, store, gate,
                repo, roles, git);
            engine.setEventLogger(events::add);

            Run created = new Run(runId, com.swarmcoder.domain.WorkflowKind.GREENFIELD,
                RunState.INTAKE, null, null, null, null, null, Instant.now(),
                new RunReport(runId, "setting the charset on an empty XML document must not throw"));
            assertThat(BasePin.pin(created, git)).isNotNull();
            engine.advance(created);
            awaitTerminal(store, runId);
            Run finished = store.root().runs.get(runId);
            String log = String.join("\n", events);

            assertThat(llm.sessionRequests).as("four turns: a lookup, two drafts, the hand-in\n" + log)
                .hasSize(4);
            assertThat(llm.sessionRequests.get(0))
                .as("the session is told to verify before it writes, and how it hands in")
                .contains("CHECK EVERY FACT BEFORE YOU USE IT")
                .contains("compile_test")
                .doesNotContain("Respond ONLY with JSON");
            assertThat(llm.sessionRequests.get(1))
                .as("the lookup returned the real file from the checkout")
                .contains("public Document(String baseUri) {}");
            assertThat(llm.sessionRequests.get(2))
                .as("the first draft is called broken, with the compiler's own line")
                .contains("BROKEN TEST")
                .contains("DocumentTest.java:16: incompatible types: org.jsoup.parser.Parser cannot "
                    + "be converted to java.lang.String");
            assertThat(llm.sessionRequests.get(3))
                .as("the corrected draft is called a healthy red")
                .contains("HEALTHY");

            assertThat(events).as(log)
                .anyMatch(e -> e.contains("Red-check passed for task"));
            assertThat(finished.state()).as(log).isNotEqualTo(RunState.TEST_AUTHORING);
            assertThat(repairs.get()).as("the red check after hand-in had nothing to send back")
                .isZero();
            assertThat(oneReplyAuthoring.get()).as("the one-reply path was not needed").isZero();
            assertThat(git("show " + finished.acceptanceTestsCommit() + ":" + DOCUMENT_TEST))
                .as("what was handed in is the corrected draft")
                .contains("// needs-type: " + DELIVERABLE)
                .doesNotContain("// misuse");
            assertThat(git("worktree list")).as("the draft's throwaway tree is gone")
                .doesNotContain("draftcheck-");
        }
    }

    @Test
    void aSessionThatGivesNoTestFallsBackToTheOneReplyAuthor() throws Exception {
        AtomicInteger repairs = new AtomicInteger();
        AtomicInteger oneReplyAuthoring = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        initRepo();
        UUID runId = UUID.randomUUID();
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                 conversation -> oneReply(conversation, repairs, oneReplyAuthoring),
                 // It reads a file and then only talks: no draft is ever compiled or handed in.
                 (turn, conversation) -> turn == 1
                     ? ScriptedAgentLlm.Turn.call("read_file",
                         Map.of("path", "project/src/main/java/org/jsoup/parser/Parser.java"))
                     : ScriptedAgentLlm.Turn.text("I think the test is fine."));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime noWorkers = spec -> {
                throw new UnsupportedOperationException("no workers in this test");
            };
            VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
            CloudGate gate = new CloudGate(0, null);
            GitService git = new GitService(repo);
            CloudRoles roles = CloudRoles.allOn(client, gate);
            KnowledgeCurator curator = new KnowledgeCurator(
                List.of(new KnowledgeCurator.Root("project", repo, "local")), null, cache);
            roles.testAuthor().setLookupAgent(new LookupAgent(curator, null, repo, gate,
                new KoogAgentRuntime(), null, null));
            WorkflowEngine engine = new WorkflowEngine(noWorkers, r -> r, client, store, gate,
                repo, roles, git);
            engine.setEventLogger(events::add);

            Run created = new Run(runId, com.swarmcoder.domain.WorkflowKind.GREENFIELD,
                RunState.INTAKE, null, null, null, null, null, Instant.now(),
                new RunReport(runId, "setting the charset on an empty XML document must not throw"));
            assertThat(BasePin.pin(created, git)).isNotNull();
            engine.advance(created);
            awaitTerminal(store, runId);
            Run finished = store.root().runs.get(runId);
            String log = String.join("\n", events);

            assertThat(oneReplyAuthoring.get()).as("the one-reply author wrote the test\n" + log)
                .isEqualTo(1);
            assertThat(llm.oneReplyRequests)
                .as("and was shown what the session's lookup had returned")
                .anyMatch(r -> r.contains("You are a test author")
                    && r.contains("WHAT YOU LOOKED UP BEFORE THIS REPLY")
                    && r.contains("public static Parser xmlParser()"));
            assertThat(finished.state()).as(log).isNotEqualTo(RunState.TEST_AUTHORING);
        }
    }

    // --- the one-reply roles ---------------------------------------------------------------------

    private static String oneReply(String conversation, AtomicInteger repairs,
                                   AtomicInteger oneReplyAuthoring) {
        if (conversation.contains("You are an AI planner")) {
            return """
                {"tasks":[
                  {"id":"t1","title":"Guard Document.ensureMetaCharsetElement against empty XML documents",
                   "instructions":"Edit src/main/java/org/jsoup/nodes/Document.java",
                   "writeSet":["src/main/java/org/jsoup/nodes/Document.java","pom.xml"],"readSet":[],
                   "criteria":[{"text":"%s",
                     "testClassOrFile":"swarm.accept.DocumentTest#works"}],
                   "requirementRefs":[]}
                ],"edges":[]}
                """.formatted(CRITERION);
        }
        if (conversation.contains("do not compile for a reason described below")) {
            repairs.incrementAndGet();
            return testFileJson("// needs-type: " + DELIVERABLE);
        }
        if (conversation.contains("You are a test author")) {
            oneReplyAuthoring.incrementAndGet();
            return testFileJson("// needs-type: " + DELIVERABLE);
        }
        return "I decline to produce JSON.";
    }

    /** Line 16 is run 43's line; the marker on line 2 tells the stand-in stage what to print. */
    private static String testSource(String marker) {
        StringBuilder sb = new StringBuilder("package swarm.accept;\n").append(marker).append('\n')
            .append("import org.jsoup.nodes.Document;\n").append("import org.jsoup.parser.Parser;\n");
        for (int line = 5; line < 16; line++) {
            sb.append('\n');
        }
        sb.append("class DocumentTest { void works() { Document doc = new Document(\"\", "
            + "Parser.xmlParser()); } }\n");
        return sb.toString();
    }

    private static String testFileJson(String marker) {
        String escaped = testSource(marker).replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n");
        return """
            {"files":[{"path":"%s","content":"%s"}],
             "wrote":[{"criterion":"%s","test":"swarm.accept.DocumentTest#works"}]}
            """.formatted(DOCUMENT_TEST, escaped, CRITERION);
    }

    // --- the repository --------------------------------------------------------------------------

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void initRepo() throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        write(repo, "src/main/java/org/jsoup/nodes/Document.java", """
            package org.jsoup.nodes;

            import java.nio.charset.Charset;

            public class Document {
                public Document(String namespace, String baseUri) {}
                public Document(String baseUri) {}
                private Document() {}
                public Document charset(Charset charset) { return this; }
                public Charset charset() { return null; }
            }
            """);
        write(repo, "src/main/java/org/jsoup/parser/Parser.java", """
            package org.jsoup.parser;

            public class Parser {
                public static final String NamespaceXml = "http://www.w3.org/XML/1998/namespace";
                public static Parser xmlParser() { return new Parser(); }
            }
            """);
        write(repo, "tools/Accept.java", ACCEPT_STAGE);
        String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
        write(repo, ".swarmcoder/verify.yaml", """
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
     * {@code // needs-type:} behaves as javac does when the file is missing; {@code // misuse}
     * prints run 43's compile error, as Maven did, at line 16 of the file; anything else passes.
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
                for (Path file : files) {
                    String name = file.getFileName().toString().replace(".java", "");
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
                        if (t.equals("// misuse")) {
                            String where = file.toAbsolutePath().toString().replace('\\\\', '/');
                            String err = "[ERROR] " + where + ":[16,48] incompatible types: "
                                + "org.jsoup.parser.Parser cannot be converted to java.lang.String";
                            System.out.println("[ERROR] COMPILATION ERROR : ");
                            System.out.println(err);
                            System.out.println("[INFO] 1 error");
                            System.out.println("[ERROR] Failed to execute goal org.apache.maven.plugins:"
                                + "maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on "
                                + "project jsoup: Compilation failure");
                            System.out.println(err);
                            System.exit(1);
                        }
                    }
                    xml.append("<testcase classname=" + q + "swarm.accept." + name + q
                        + " name=" + q + "works" + q + "></testcase>\\n");
                }
                Files.createDirectories(Path.of("build/test-results"));
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=" + q + "accept" + q + " tests=" + q + files.size() + q
                    + " failures=" + q + "0" + q + " errors=" + q + "0" + q
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
                return; // parked - the assertion on the state will say so
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
