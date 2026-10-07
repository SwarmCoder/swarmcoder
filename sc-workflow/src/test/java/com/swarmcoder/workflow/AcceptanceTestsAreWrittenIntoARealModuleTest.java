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
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.PathPolicy;
import com.swarmcoder.verify.AcceptanceTestLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three places that must name the SAME directory, or the bug simply moves.
 *
 * <p>On run {@code e01d1378} they did not. The test author was told to write to the hardcoded
 * {@code src/test/java/swarm/accept} at the repository root, it worked out for itself that the
 * tests belonged in {@code bookshelf-demo-server}, tried to write there — and the path policy
 * refused it for leaving the protected directory. The tests it did write sat at the root of an
 * aggregator build, so {@code mvn test} never saw them, the acceptance stage executed zero tests
 * and the run parked.
 *
 * <p>This test holds all three to one string: what the author is TOLD, where a write is ALLOWED,
 * and what the path policy PROTECTS from workers.
 */
class AcceptanceTestsAreWrittenIntoARealModuleTest {

    @TempDir
    Path repo;

    @Test
    void theAuthorIsToldTheModuleDirectoryAndItsWriteThereIsAccepted() throws Exception {
        aggregatorOverSharedClientServer();
        String protectedDir = AcceptanceTestLocation.resolve(repo, "maven").protectedDir();
        assertThat(protectedDir).isEqualTo("server/src/test/java/swarm");

        StringBuilder prompt = new StringBuilder();
        String reply = """
            {"files":[
              {"path":"server/src/test/java/swarm/accept/BookshelfTest.java",
               "content":"package swarm.accept; class BookshelfTest {}"},
              {"path":"src/test/java/swarm/accept/OrphanTest.java",
               "content":"package swarm.accept; class OrphanTest {}"}
            ]}
            """;
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            prompt.append(conversation);
            return reply;
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));

            List<String> written = author.authorTests(repo, task(protectedDir), null).paths();

            assertThat(prompt.toString())
                .as("the author must be told the module directory, not a root convention")
                .contains("server/src/test/java/swarm/accept/<ClassName>.java");
            assertThat(written)
                .as("the write the old policy rejected is the one that has to be accepted")
                .containsExactly("server/src/test/java/swarm/accept/BookshelfTest.java");
            assertThat(Files.exists(repo.resolve("server/src/test/java/swarm/accept/BookshelfTest.java")))
                .isTrue();
            assertThat(Files.exists(repo.resolve("src/test/java/swarm/accept/OrphanTest.java")))
                .as("a file outside the task's protected tree is still refused")
                .isFalse();
        }
    }

    @Test
    void thePathPolicyProtectsExactlyThatDirectoryFromWorkers() throws IOException {
        aggregatorOverSharedClientServer();
        String protectedDir = AcceptanceTestLocation.resolve(repo, "maven").protectedDir();

        // A worker owning the whole server module still may not touch the acceptance tests —
        // protection wins over the write set, which is what stops a worker passing its own gate.
        PathPolicy.Verdict refused = PathPolicy.check(
            "server/src/test/java/swarm/accept/BookshelfTest.java", Set.of("server"),
            protectedDir, null);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason()).contains("acceptance tests are protected");

        // Its ordinary tests in the same module are its own business.
        assertThat(PathPolicy.check("server/src/test/java/demo/ShelfTest.java", Set.of("server"),
            protectedDir, null).allowed()).isTrue();
    }

    @Test
    void aSingleModuleProjectIsUnchanged() throws IOException {
        Files.writeString(repo.resolve("pom.xml"), "<project><artifactId>a</artifactId></project>");

        String protectedDir = AcceptanceTestLocation.resolve(repo, "maven").protectedDir();

        assertThat(protectedDir).isEqualTo(ArchitectClient.ACCEPTANCE_TEST_DIR);
        assertThat(ArchitectClient.acceptanceWriteDir(protectedDir))
            .isEqualTo(ArchitectClient.ACCEPTANCE_TEST_WRITE_DIR);
        assertThat(PathPolicy.check("src/test/java/swarm/accept/X.java", Set.of("src"),
            protectedDir, null).allowed()).isFalse();
    }

    private static Task task(String protectedDir) {
        return new Task(UUID.randomUUID(), 1, "list books", "show every book on the shelf",
            Set.of("server/src/main/java"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "the shelf lists every book",
                "swarm.accept.BookshelfTest#listsEveryBook")),
            protectedDir, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    /** The shape of {@code dev/bookshelf-demo}: server depends on shared and client. */
    private void aggregatorOverSharedClientServer() throws IOException {
        Files.writeString(repo.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <artifactId>root</artifactId><packaging>pom</packaging>
              <modules>
                <module>shared</module><module>client</module><module>server</module>
              </modules>
            </project>""");
        module("shared");
        module("client", "shared");
        module("server", "shared", "client");
    }

    private void module(String name, String... dependsOn) throws IOException {
        Files.createDirectories(repo.resolve(name));
        StringBuilder pom = new StringBuilder("<project><modelVersion>4.0.0</modelVersion>"
            + "<artifactId>" + name + "</artifactId><dependencies>");
        for (String dependency : dependsOn) {
            pom.append("<dependency><artifactId>").append(dependency).append("</artifactId></dependency>");
        }
        pom.append("</dependencies></project>");
        Files.writeString(repo.resolve(name).resolve("pom.xml"), pom.toString());
    }
}
