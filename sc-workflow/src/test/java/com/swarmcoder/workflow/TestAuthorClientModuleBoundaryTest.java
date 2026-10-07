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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test author is told what its OWN test can and cannot import, before it ever writes one
 * (author decision, 2026-09-05, after harness run 26 — see {@link BrokenAcceptanceTest}), and can
 * be sent a broken test back for a bounded, one-shot correction.
 */
class TestAuthorClientModuleBoundaryTest {

    @TempDir
    Path repo;

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "rating", "show a rating",
            Set.of("bookshelf-demo-server/src/main"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "the rating is shown",
                "swarm.accept.RatingTest#works")),
            "bookshelf-demo-server/src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    @Test
    void thePromptNamesTheModuleAndItsClasspath() throws Exception {
        StringBuilder seen = new StringBuilder();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            seen.append(conversation);
            return "{\"files\":[]}";
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));

            author.authorTests(repo, task(), null, task().criteria(), "", false,
                "bookshelf-demo-server", List.of("bookshelf-demo-shared", "junit-jupiter"));

            assertThat(seen.toString())
                .as("names the module the test lives in")
                .contains("THIS TEST FILE LIVES IN THE MODULE 'bookshelf-demo-server'")
                .as("names what that module's own build file declares")
                .contains("bookshelf-demo-shared, junit-jupiter")
                .as("warns against a client/UI-layer import specifically")
                .contains("never a package that belongs to this project's client or UI layer");
        }
    }

    /**
     * Harness run 42, 2026-09-26: told only the declared-dependency list, the author twice tried
     * to WRITE {@code bookshelf-demo-server/pom.xml} — nothing had ever said this role cannot touch
     * a build file at all, mechanically true since {@link TestAuthorClient#write} was written. Both
     * attempts were silently rejected, wasting the run's one correction round trip on a file that
     * could never land.
     */
    @Test
    void thePromptSaysItCanNeverWriteABuildFile() throws Exception {
        StringBuilder seen = new StringBuilder();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            seen.append(conversation);
            return "{\"files\":[]}";
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));

            author.authorTests(repo, task(), null, task().criteria(), "", false,
                "bookshelf-demo-server", List.of("bookshelf-demo-shared", "junit-jupiter"));

            assertThat(seen.toString())
                .as("says plainly this role can never add, change or write a build file")
                .contains("never a pom.xml, a build.gradle(.kts), or any other file")
                .contains("This role cannot add, change or declare a dependency at all");
        }
    }

    /**
     * The other half of the run-42 fix: when the declared dependencies name no assertion library,
     * the author is told so directly and pointed at the one framework that IS always there — JUnit
     * 5's own {@code Assertions} — instead of reaching for AssertJ's {@code assertThat} and then
     * discovering, two attempts in, that nothing lets it add the dependency. Modelled on
     * {@code bookshelf-demo-server}'s real pom, which declares {@code junit-jupiter} and nothing
     * else a test could assert with.
     */
    @Test
    void withNoAssertionLibraryTheAuthorIsToldToUseJUnitsOwn() throws Exception {
        StringBuilder seen = new StringBuilder();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            seen.append(conversation);
            return "{\"files\":[]}";
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));

            author.authorTests(repo, task(), null, task().criteria(), "", false,
                "bookshelf-demo-server", List.of("bookshelf-demo-shared", "junit-jupiter"));

            assertThat(seen.toString())
                .as("no assertj/hamcrest/truth in the declared list, so the author is redirected")
                .contains("this module has no assertion library")
                .contains("org.junit.jupiter.api.Assertions");
        }
    }

    /** Companion to the test above: when an assertion library IS declared, nothing new is said. */
    @Test
    void withAnAssertionLibraryDeclaredNothingIsSaidAboutMissingOne() throws Exception {
        StringBuilder seen = new StringBuilder();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            seen.append(conversation);
            return "{\"files\":[]}";
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));

            author.authorTests(repo, task(), null, task().criteria(), "", false,
                "bookshelf-demo-server",
                List.of("bookshelf-demo-shared", "junit-jupiter", "assertj-core"));

            assertThat(seen.toString())
                .as("assertj-core is declared, so the fallback sentence would be misleading")
                .doesNotContain("this module has no assertion library");
        }
    }

    @Test
    void noModuleMeansNoNewSentence() throws Exception {
        StringBuilder seen = new StringBuilder();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            seen.append(conversation);
            return "{\"files\":[]}";
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));

            author.authorTests(repo, task(), null, task().criteria(), "", false);

            assertThat(seen.toString())
                .as("the caller with no build layout is told nothing new")
                .doesNotContain("THIS TEST FILE LIVES IN THE MODULE");
        }
    }

    @Test
    void repairBrokenTestWritesTheCorrectionUnderTheProtectedDir() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> """
            {"files":[{"path":"bookshelf-demo-server/src/test/java/swarm/accept/RatingTest.java",
             "content":"package swarm.accept; class RatingTest {}"}],"wrote":[]}
            """)) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));
            Task task = task();
            String path = "bookshelf-demo-server/src/test/java/swarm/accept/RatingTest.java";

            TestAuthorClient.Authored authored = author.repairBrokenTest(repo, task, null,
                Map.of(path, "package swarm.accept; import com.zeroz4j.ui.Widget; class RatingTest {}"),
                BrokenAcceptanceTest.reask(List.of("com.zeroz4j.ui"), "bookshelf-demo-server",
                    List.of("bookshelf-demo-shared")));

            assertThat(authored.failureReason()).isNull();
            assertThat(authored.paths()).containsExactly(path);
            assertThat(java.nio.file.Files.readString(repo.resolve(path)))
                .contains("class RatingTest");
        }
    }

    @Test
    void repairBrokenTestReportsWhenTheReplyNamesNoFile() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "{\"files\":[],\"wrote\":[]}")) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));
            Task task = task();

            TestAuthorClient.Authored authored = author.repairBrokenTest(repo, task, null,
                Map.of("bookshelf-demo-server/src/test/java/swarm/accept/RatingTest.java", "old"),
                "fix it");

            assertThat(authored.failureReason()).contains("named no file");
        }
    }
}
