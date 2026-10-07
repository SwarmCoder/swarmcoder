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

import com.swarmcoder.testsupport.LocalCheckouts;
import com.swarmcoder.domain.HostExecution;
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
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import com.swarmcoder.verify.BuildBoxes;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The product's own commands that execute model-written tests - the test author's
 * {@code compile_test} and the red check after hand-in - run in a container that sees the tree
 * they work on and nothing else of this PC (owner decision, 2026-10-02).
 *
 * <p>The fixture is {@link TheTestAuthorLooksThingsUpAndCompilesItsDraftTest}'s: a scripted model,
 * a real git repository, a stand-in acceptance stage. Two things differ. The workflow is given a
 * real Docker sandbox and <b>this class does not allow this PC</b>, so a command that tried to run
 * here would be refused and the run could not get past TEST_AUTHORING. And the acceptance stage
 * reports where it ran: its operating system, its working folder, whether it can see a file that
 * sits beside its tree on this PC, and what it sees of the reference folder.
 *
 * <p>The reference folder is one on a drive other than C: when this PC has it
 * ({@code C:/work/zeroz4j}, read-only; override with {@code -Dswarmcoder.test.referenceRoot}),
 * which is the mount the first containment job could not try.
 */
class TheRedCheckAndCompileTestRunInAContainerTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String DOCUMENT_TEST = ACCEPT_DIR + "/DocumentTest.java";
    private static final String DELIVERABLE = "src/main/java/org/jsoup/nodes/Guard.java";
    private static final String CRITERION = "charset on an empty XML document does not throw";
    /** Where the workflow cuts its throwaway trees; a file here is "beside the tree". */
    private static final Path WORKTREES = Path.of(System.getProperty("user.home"), ".swarmcoder", "wt");

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;
    @TempDir
    Path cache;
    @TempDir
    Path standInReference;

    @Test
    @RunsWhen(Need.DOCKER)
    void theyRunInTheContainerAndCannotSeeAFileBesideTheTree() throws Exception {
        assertThat(HostExecution.allowedBy())
            .as("this test must not be able to pass by running on this PC").isEmpty();

        String marker = "beside-" + UUID.randomUUID() + ".txt";
        Files.createDirectories(WORKTREES);
        Path beside = WORKTREES.resolve(marker);
        Files.writeString(beside, "a file on this PC, next to the trees\n");
        Path reference = referenceRoot();
        boolean otherDrive = reference.toString().length() > 1
            && reference.toString().charAt(1) == ':'
            && Character.toUpperCase(reference.toString().charAt(0)) != 'C';

        List<String> events = new CopyOnWriteArrayList<>();
        Recording docker = new Recording();
        initRepo(marker);
        UUID runId = UUID.randomUUID();
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                 TheRedCheckAndCompileTestRunInAContainerTest::oneReply,
                 (turn, conversation) -> switch (turn) {
                     case 1 -> ScriptedAgentLlm.Turn.call("compile_test",
                         Map.of("path", DOCUMENT_TEST, "content", testSource("// misuse")));
                     case 2 -> ScriptedAgentLlm.Turn.call("compile_test",
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
            engine.setBuildBoxes(new BuildBoxes(() -> docker, () -> Map.of("zeroz4j", reference)));
            engine.setEventLogger(events::add);

            Run created = new Run(runId, com.swarmcoder.domain.WorkflowKind.GREENFIELD,
                RunState.INTAKE, null, null, null, null, null, Instant.now(),
                new RunReport(runId, "setting the charset on an empty XML document must not throw"));
            assertThat(BasePin.pin(created, git)).isNotNull();
            engine.advance(created);
            awaitTerminal(store, runId);
            Run finished = store.root().runs.get(runId);
            String log = String.join("\n", events) + "\n--- container output ---\n"
                + String.join("\n", docker.acceptanceOutputs);
            System.out.println("[CONTAINED-BUILDS] containers started and removed: "
                + docker.started + "\n" + String.join("\n", docker.acceptanceOutputs));

            // compile_test, twice, and then the red check after hand-in: each ran the stage.
            assertThat(llm.sessionRequests).as(log).hasSizeGreaterThanOrEqualTo(3);
            assertThat(llm.sessionRequests.get(1)).as("the first draft was compiled and called "
                + "broken\n" + log).contains("BROKEN TEST");
            assertThat(llm.sessionRequests.get(2)).as("the corrected draft was compiled and "
                + "called a healthy red\n" + log).contains("HEALTHY");
            assertThat(events).as(log).anyMatch(e -> e.contains("Red-check passed for task"));
            assertThat(finished.state()).as(log).isNotEqualTo(RunState.TEST_AUTHORING);

            assertThat(docker.acceptanceOutputs)
                .as("two draft compiles and at least one red check ran the acceptance stage in a "
                    + "container\n" + log)
                .hasSizeGreaterThanOrEqualTo(3);
            assertThat(docker.acceptanceOutputs).as(log).allSatisfy(output -> assertThat(output)
                .as("inside the container: Linux, in /workspace")
                .contains("PROBE os=Linux cwd=/workspace ")
                .as("the file beside the tree on this PC is not there")
                .contains(" beside=false ")
                .as("the reference folder is mounted, has files in it, and cannot be written")
                .contains(" refDir=true ")
                .contains(" refWritable=false ")
                .doesNotContain(" refEntries=0 "));
            assertThat(docker.started).as("one container for the drafts' tree, one for the red "
                + "check's - not one per command").hasSizeBetween(2, 4);
            assertThat(docker.removed).as("every container started was removed")
                .containsAll(docker.started);
            assertThat(docker.readOnlyMounts).as("the reference root went in read-only, from "
                + reference).allSatisfy(mounts -> assertThat(mounts)
                .contains(reference.toAbsolutePath() + " -> /reference/zeroz4j"));
            if (otherDrive) {
                System.out.println("[CONTAINED-BUILDS] the reference root is on another drive "
                    + "than C: and mounted: " + reference);
            }
        } finally {
            Files.deleteIfExists(beside);
            docker.removeWhatIsLeft();
        }
    }

    /** {@code C:/work/zeroz4j} when this PC has it, else a folder made for the test. */
    private Path referenceRoot() throws Exception {
        String configured = System.getProperty("swarmcoder.test.referenceRoot",
            LocalCheckouts.find("zeroz4j").toString());
        Path wanted = Path.of(configured);
        if (Files.isDirectory(wanted)) {
            return wanted;
        }
        Files.writeString(standInReference.resolve("README.md"), "reference\n");
        return standInReference;
    }

    /** The real manager, remembering what it started, what it ran and what it removed. */
    private static final class Recording extends DockerSandboxManager {

        final List<String> started = new CopyOnWriteArrayList<>();
        final List<String> removed = new CopyOnWriteArrayList<>();
        final List<String> acceptanceOutputs = new CopyOnWriteArrayList<>();
        final List<List<String>> readOnlyMounts = new CopyOnWriteArrayList<>();

        Recording() {
            super(System.getProperty("swarmcoder.sandbox.image", "swarmcoder-worker:latest"), 2, 4,
                System.getProperty("swarmcoder.sandbox.dockerHost"),
                Path.of(System.getProperty("user.home"), ".m2").toString());
        }

        @Override
        public SandboxHandle launch(String worktreeHostPath, String writeSetEnv,
                                    List<ReadOnlyMount> readOnly, String imageOverride) {
            SandboxHandle handle = super.launch(worktreeHostPath, writeSetEnv, readOnly, imageOverride);
            started.add(handle.containerId());
            readOnlyMounts.add(readOnly.stream()
                .map(m -> m.hostPath() + " -> " + m.containerPath()).toList());
            return handle;
        }

        @Override
        public ExecOutcome execInContainer(String containerId, List<String> command,
                                           int timeoutSeconds) {
            ExecOutcome outcome = super.execInContainer(containerId, command, timeoutSeconds);
            if (String.join(" ", command).contains("tools/Accept.java")) {
                acceptanceOutputs.add(outcome.output());
            }
            return outcome;
        }

        @Override
        public void killContainer(String containerId) {
            super.killContainer(containerId);
            removed.add(containerId);
        }

        void removeWhatIsLeft() {
            for (String id : started) {
                if (!removed.contains(id)) {
                    killContainer(id);
                }
            }
        }
    }

    // --- the one-reply roles ---------------------------------------------------------------------

    private static String oneReply(String conversation) {
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
        if (conversation.contains("do not compile for a reason described below")
                || conversation.contains("You are a test author")) {
            return testFileJson("// needs-type: " + DELIVERABLE);
        }
        return "I decline to produce JSON.";
    }

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

    private void initRepo(String marker) throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        write(repo, "src/main/java/org/jsoup/nodes/Document.java", """
            package org.jsoup.nodes;

            public class Document {
                public Document(String namespace, String baseUri) {}
                public Document(String baseUri) {}
            }
            """);
        write(repo, "src/main/java/org/jsoup/parser/Parser.java", """
            package org.jsoup.parser;

            public class Parser {
                public static Parser xmlParser() { return new Parser(); }
            }
            """);
        write(repo, "tools/Accept.java", ACCEPT_STAGE.replace("@MARKER@", marker));
        // Plain `java`: the container's own, not this PC's.
        write(repo, ".swarmcoder/verify.yaml", """
            toolchain: gradle
            compile:
              - "echo compile-ok"
            acceptance:
              - "java tools/Accept.java"
            timeoutSeconds: 120
            """);
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
    }

    /**
     * {@link TheTestAuthorLooksThingsUpAndCompilesItsDraftTest}'s stand-in stage, which first
     * prints where it is running and what it can see.
     */
    private static final String ACCEPT_STAGE = """
        import java.nio.file.*;
        import java.util.*;

        public class Accept {
            public static void main(String[] args) throws Exception {
                char q = 34;
                Path ref = Path.of("/reference/zeroz4j");
                long entries = 0;
                if (Files.isDirectory(ref)) {
                    try (var s = Files.list(ref)) {
                        entries = s.count();
                    }
                }
                System.out.println("PROBE os=" + System.getProperty("os.name")
                    + " cwd=" + Path.of("").toAbsolutePath().toString().replace('\\\\', '/')
                    + " beside=" + Files.exists(Path.of("..", "@MARKER@"))
                    + " refDir=" + Files.isDirectory(ref)
                    + " refWritable=" + Files.isWritable(ref)
                    + " refEntries=" + entries + " .");
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
        long deadline = System.currentTimeMillis() + 240_000;
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
