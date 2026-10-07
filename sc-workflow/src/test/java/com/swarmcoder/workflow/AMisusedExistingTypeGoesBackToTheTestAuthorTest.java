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
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.git.BasePin;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.verify.AcceptanceCompileErrors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Brownfield harness run 43, 2026-09-26, through the workflow: an acceptance test whose line 16
 * passes a {@code Parser} where the existing {@code Document} constructor takes a {@code String}
 * does not compile, and the red check used to confirm that as "references not-yet-implemented
 * symbols". Now it is a broken test: it goes back to its author once, with the compiler's own
 * line and the constructor's real signatures read off the checkout; corrected, the run proceeds;
 * still misused, the run parks before any worker is dispatched. And a test that fails to compile
 * only because a type the plan delivers is missing is still the healthy red it always was.
 *
 * <p>The acceptance stage is the same stand-in {@link ATestThatReachesBrowserOnlyCodeIsNotARedStateTest}
 * uses, plus one marker: a test file saying {@code // misuse} makes it print run 43's compile
 * error exactly as Maven did, pointing at line 16 of that file.
 */
@ModelCodeOnThisPc
class AMisusedExistingTypeGoesBackToTheTestAuthorTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String DOCUMENT_TEST = ACCEPT_DIR + "/DocumentTest.java";
    /** Inside the task's package, so a test missing it is a healthy, deliverable red. */
    private static final String DELIVERABLE = "src/main/java/org/jsoup/nodes/Guard.java";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;
    @TempDir
    Path tree;

    // ------------------------------------------------------------------------ the pure halves

    @Test
    void theReaskQuotesTheCompilerAndTheRealConstructors() throws Exception {
        writeJsoupStubs(tree);
        write(tree, DOCUMENT_TEST, testSource("// misuse"));
        String output = misuseOutput(tree.resolve(DOCUMENT_TEST).toAbsolutePath().toString());
        Task task = run43Task(List.of(new ApiContract(UUID.randomUUID(), "Document", "", "",
            "org.jsoup.nodes.Document", List.of("Document charset(Charset charset)"))));

        AcceptanceCompileErrors.Reading reading = MiscompiledAcceptanceTest.read(tree, output,
            List.of(DOCUMENT_TEST), List.of(task));
        String signatures = MiscompiledAcceptanceTest.signatures(tree, reading, List.of(DOCUMENT_TEST));
        String reask = MiscompiledAcceptanceTest.reask(reading, signatures);

        assertThat(reading.isBroken()).as("the contract's members all exist: nothing will change the "
            + "constructor").isTrue();
        assertThat(reask)
            .contains("DocumentTest.java:16: incompatible types: org.jsoup.parser.Parser cannot be "
                + "converted to java.lang.String")
            .contains("not because the code it needs has not been written yet");
        assertThat(signatures)
            .contains("org.jsoup.nodes.Document: public Document(String namespace, String baseUri)")
            .contains("org.jsoup.nodes.Document: public Document(String baseUri)")
            .contains("org.jsoup.parser.Parser: public static Parser xmlParser()")
            .doesNotContain("private Document()")
            .doesNotContain("charset(");
    }

    @Test
    void aContractThatChangesTheConstructorMakesTheSameLineAHealthyRed() throws Exception {
        writeJsoupStubs(tree);
        write(tree, DOCUMENT_TEST, testSource("// misuse"));
        String output = misuseOutput(tree.resolve(DOCUMENT_TEST).toAbsolutePath().toString());
        Task task = run43Task(List.of(new ApiContract(UUID.randomUUID(), "Document", "", "",
            "org.jsoup.nodes.Document", List.of("public Document(String baseUri, Parser parser)"))));

        AcceptanceCompileErrors.PlannedChanges planned =
            MiscompiledAcceptanceTest.plannedChanges(tree, List.of(task));
        AcceptanceCompileErrors.Reading reading = MiscompiledAcceptanceTest.read(tree, output,
            List.of(DOCUMENT_TEST), List.of(task));

        assertThat(planned.membersByType()).containsKey("org.jsoup.nodes.Document");
        assertThat(planned.membersByType().get("org.jsoup.nodes.Document")).contains("Document");
        assertThat(reading.isBroken()).as("the plan promises Document(String, Parser)").isFalse();
    }

    // ------------------------------------------------------------------------ through the workflow

    @Test
    void run43ShapeIsSentBackOnceAndTheCorrectionProceeds() throws Exception {
        AtomicInteger repairs = new AtomicInteger();
        List<String> repairAsks = new CopyOnWriteArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        Run finished = run(events, conversation -> route(conversation, repairs, repairAsks,
            "// misuse", "// needs-type: " + DELIVERABLE));

        assertThat(events).as(String.join("\n", events))
            .anyMatch(e -> e.contains("do not compile for a reason no task in this plan can fix")
                && e.contains("DocumentTest.java:16 incompatible types"))
            .anyMatch(e -> e.contains("did not compile for a reason no task could fix; the test "
                + "author corrected them and they are red again"));
        assertThat(finished.state()).as(String.join("\n", events))
            .isNotEqualTo(RunState.TEST_AUTHORING);
        assertThat(repairs.get()).as("exactly one bounded repair").isEqualTo(1);
        assertThat(repairAsks.get(0))
            .contains("DocumentTest.java:16: incompatible types: org.jsoup.parser.Parser cannot be "
                + "converted to java.lang.String")
            .contains("public Document(String baseUri)");
    }

    @Test
    void stillMisusedAfterOneCorrectionTheRunParksBeforeAnyWorker() throws Exception {
        AtomicInteger repairs = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        Run finished = run(events, conversation -> route(conversation, repairs,
            new CopyOnWriteArrayList<>(), "// misuse", "// misuse"));

        assertThat(finished.state()).as(String.join("\n", events)).isEqualTo(RunState.TEST_AUTHORING);
        assertThat(finished.parkReason()).as(String.join("\n", events))
            .contains("do not compile, and no task in this plan can make them compile")
            .contains("DocumentTest.java:16: incompatible types")
            .contains("the corrected test still does not compile")
            .contains("Decide which side is wrong");
        assertThat(repairs.get()).isEqualTo(1);
    }

    @Test
    void aTestMissingOnlyATypeThePlanDeliversIsStillAHealthyRed() throws Exception {
        AtomicInteger repairs = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        Run finished = run(events, conversation -> route(conversation, repairs,
            new CopyOnWriteArrayList<>(), "// needs-type: " + DELIVERABLE, "unused"));

        assertThat(events).as(String.join("\n", events))
            .anyMatch(e -> e.contains("Red-check passed for task"));
        assertThat(finished.state()).as(String.join("\n", events))
            .isNotEqualTo(RunState.TEST_AUTHORING);
        assertThat(repairs.get()).as("nothing to repair").isZero();
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
                new RunReport(runId, "setting the charset on an empty XML document must not throw"));
            assertThat(BasePin.pin(created, git)).isNotNull();
            engine.advance(created);
            awaitTerminal(store, runId);
            return store.root().runs.get(runId);
        }
    }

    // --- the scripted roles ------------------------------------------------------------------------

    private static String route(String conversation, AtomicInteger repairs, List<String> repairAsks,
                                String originalMarker, String correctedMarker) {
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
            // The bounded, one-shot repair call (TestAuthorClient.repairBrokenTest).
            repairs.incrementAndGet();
            repairAsks.add(conversation);
            return testFileJson(correctedMarker);
        }
        if (conversation.contains("You are a test author")) {
            return testFileJson(originalMarker);
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
             "wrote":[{"criterion":"charset on an empty XML document does not throw",
                       "test":"swarm.accept.DocumentTest#works"}]}
            """.formatted(DOCUMENT_TEST, escaped);
    }

    /** Run 43's error, as Maven printed it: once as it happened, once in the goal's summary. */
    private static String misuseOutput(String absolutePath) {
        String line = "[ERROR] " + absolutePath.replace('\\', '/') + ":[16,48] incompatible types: "
            + "org.jsoup.parser.Parser cannot be converted to java.lang.String";
        return "[ERROR] COMPILATION ERROR : \n" + line + "\n[INFO] 1 error\n"
            + "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:"
            + "testCompile (default-testCompile) on project jsoup: Compilation failure\n" + line + "\n";
    }

    private static Task run43Task(List<ApiContract> contracts) {
        Task task = new Task(UUID.randomUUID(), 1,
            "Guard Document.ensureMetaCharsetElement against empty XML documents", "do it",
            Set.of("src/main/java/org/jsoup/nodes/Document.java", "pom.xml"), Set.of(), List.of(),
            "src/test/java/swarm", null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()),
            TaskState.READY);
        task.setDeliveredContracts(contracts);
        return task;
    }

    // --- the repository -------------------------------------------------------------------------

    private static void writeJsoupStubs(Path root) throws Exception {
        write(root, "src/main/java/org/jsoup/nodes/Document.java", """
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
        write(root, "src/main/java/org/jsoup/parser/Parser.java", """
            package org.jsoup.parser;

            public class Parser {
                public static final String NamespaceXml = "http://www.w3.org/XML/1998/namespace";
                public static Parser xmlParser() { return new Parser(); }
            }
            """);
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void initRepo() throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        writeJsoupStubs(repo);
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
