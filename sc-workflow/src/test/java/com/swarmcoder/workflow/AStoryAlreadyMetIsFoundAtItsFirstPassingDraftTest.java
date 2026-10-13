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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 99: a story the existing code already meets is found at the first draft of its test
 * that compiles and passes on the tree the build starts from - while the test author is still in
 * its first session, before any test is handed in, reviewed or corrected and before any worker.
 * The draft is held to the evidence a green red check is held to; a passing draft that measures
 * nothing is still a broken test.
 */
@ModelCodeOnThisPc
class AStoryAlreadyMetIsFoundAtItsFirstPassingDraftTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String BOOK = "src/main/java/com/x/Book.java";
    private static final String DRAFT = ACCEPT_DIR + "/BooksSurviveARestartTest.java";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    @Test
    void aPassingDraftThatMeasuresTheProjectsCodeStopsTheAuthorThere() throws Exception {
        TestAuthorTools.Verdict verdict = draft("package swarm.accept;\n"
            + "import com.x.Book;\n"
            + "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
            + "// needs: " + BOOK + "\n"
            + "class BooksSurviveARestartTest {\n"
            + "    void booksAreStillThere() { assertEquals(\"Emma\", new Book().title()); }\n"
            + "}\n");

        assertThat(verdict.healthy())
            .as("the author is not sent round again to make a correct test fail").isTrue();
        assertThat(verdict.text()).startsWith("ALREADY MET")
            .contains("no code will be written")
            .contains("Do not change the test to make it fail")
            .contains("report_done");
    }

    @Test
    void aPassingDraftThatMeasuresNothingIsStillABrokenTest() throws Exception {
        TestAuthorTools.Verdict verdict = draft("package swarm.accept;\n"
            + "// needs: " + BOOK + "\n"
            + "class BooksSurviveARestartTest {\n"
            + "    void booksAreStillThere() { String title = \"Emma\"; }\n"
            + "}\n");

        assertThat(verdict.healthy()).isFalse();
        assertThat(verdict.text()).startsWith("BROKEN TEST: it passes on the code as it is now");
    }

    /** The verdict compile_test gives the test author for {@code source} on the start tree. */
    private TestAuthorTools.Verdict draft(String source) throws Exception {
        write(BOOK, "package com.x;\npublic class Book { public String title() { return \"Emma\"; } }\n");
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
        write(DRAFT, source);
        // The task as it is while its author is still writing: it claims no test file yet.
        Task task = new Task(UUID.randomUUID(), 1, "Keep books across a restart",
            "Books are still there after a restart.", Set.of(BOOK), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "Books are there after a restart",
                "swarm.accept.BooksSurviveARestartTest#booksAreStillThere")),
            "src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(1, false, 0.2, 0.8, List.of()), TaskState.READY);
        VerifySpec spec = VerifySpecLoader.load(repoDir).orElseThrow();
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime unusedRuntime = agent -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            SwarmEngine unusedSwarm = run -> run;
            GreenfieldWorkflow workflow = new GreenfieldWorkflow(unusedRuntime, unusedSwarm,
                new VllmClient("http://localhost:1", "", "unused", true), store,
                new RunPersister(store), new CloudGate(0, null), repoDir,
                new CloudRoles(null, null, null), GitService.disabled());
            return workflow.draftVerdict(repoDir, spec, task, List.of(DRAFT), List.of(task),
                false, null);
        }
    }

    /** The stand-in acceptance stage: a test passes when every path in its {@code // needs:} is there. */
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
                StringBuilder xml = new StringBuilder();
                int failures = 0;
                for (Path file : files) {
                    String name = file.getFileName().toString().replace(".java", "");
                    boolean ok = true;
                    for (String line : Files.readString(file).split("\\n")) {
                        if (line.trim().startsWith("// needs:")) {
                            ok &= Files.exists(Path.of(line.trim().substring(9).trim()));
                        }
                    }
                    xml.append("<testcase classname=\\"swarm.accept.").append(name)
                       .append("\\" name=\\"booksAreStillThere\\">");
                    if (!ok) {
                        failures++;
                        xml.append("<failure message=\\"missing\\">the file it needs is missing</failure>");
                    }
                    xml.append("</testcase>\\n");
                    System.out.println("ran swarm.accept." + name + " -> " + (ok ? "pass" : "FAIL"));
                }
                Files.createDirectories(Path.of("build/test-results"));
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=\\"accept\\" tests=\\"" + files.size() + "\\" failures=\\"" + failures
                    + "\\" errors=\\"0\\" skipped=\\"0\\">\\n" + xml + "</testsuite>\\n");
                System.exit(failures > 0 ? 1 : 0);
            }
        }
        """;

    private void write(String relative, String content) throws Exception {
        Path file = repoDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
