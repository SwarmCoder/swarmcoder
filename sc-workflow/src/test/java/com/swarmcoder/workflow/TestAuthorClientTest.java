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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestAuthorClientTest {

    @TempDir
    Path repo;

    @Test
    void writesTestsIntoProtectedDirAndRejectsEscapes() throws Exception {
        String reply = """
            {"files":[
              {"path":"src/test/java/swarm/accept/MultiplyAcceptTest.java",
               "content":"package swarm.accept; class MultiplyAcceptTest {}"},
              {"path":"src/main/java/Evil.java","content":"class Evil {}"},
              {"path":"src/test/java/swarm/../../../Escape.java","content":"class Escape {}"}
            ]}
            """;
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> reply)) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));

            Task task = new Task(UUID.randomUUID(), 1, "multiply", "add multiply",
                Set.of("src/main"), Set.of(),
                List.of(new AcceptanceCriterion(UUID.randomUUID(), "multiply works",
                    "swarm.accept.MultiplyAcceptTest")),
                "src/test/java/swarm", null, null,
                new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);

            List<String> written = author.authorTests(repo, task, null).paths();

            assertThat(written).containsExactly("src/test/java/swarm/accept/MultiplyAcceptTest.java");
            assertThat(Files.readString(repo.resolve("src/test/java/swarm/accept/MultiplyAcceptTest.java")))
                .contains("MultiplyAcceptTest");
            assertThat(Files.exists(repo.resolve("src/main/java/Evil.java")))
                .as("write outside the protected dir must be rejected").isFalse();
            assertThat(Files.exists(repo.resolve("Escape.java")))
                .as("path traversal must be rejected").isFalse();
        }
    }

    /**
     * The instruction used to say two things at once: "package swarm.accept" and "all files must
     * live under src/test/java/swarm", the second one repeated as the example path. A live 27B
     * model followed the path, wrote src/test/java/swarm/MultiplyAcceptTest.java, named every test
     * swarm.MultiplyAcceptTest#... instead of swarm.accept.MultiplyAcceptTest#..., and not one
     * criterion's agreed reference matched. The run parked and proved nothing.
     */
    @Test
    void theAuthorIsToldTheAcceptPackageDirectory() throws Exception {
        StringBuilder seen = new StringBuilder();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            seen.append(conversation);
            return "{\"files\":[]}";
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));
            Task task = new Task(UUID.randomUUID(), 1, "multiply", "add multiply",
                Set.of("src/main"), Set.of(),
                List.of(new AcceptanceCriterion(UUID.randomUUID(), "multiply works",
                    "swarm.accept.MultiplyAcceptTest#works")),
                ArchitectClient.ACCEPTANCE_TEST_DIR, null, null,
                new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);

            author.authorTests(repo, task, null);

            assertThat(seen.toString())
                .as("the author is told the accept-package directory, not the protected tree")
                .contains(ArchitectClient.ACCEPTANCE_TEST_WRITE_DIR + "/<ClassName>.java");
        }
    }

    @Test
    void skipsTasksWithoutCriteria() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "{}")) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));
            Task task = new Task(UUID.randomUUID(), 1, "t", "i", Set.of(), Set.of(),
                List.of(), "src/test/java/swarm", null, null,
                new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);

            assertThat(author.authorTests(repo, task, null).paths()).isEmpty();
        }
    }

    /**
     * The steering that was missing on 2026-09-03, and that a whole run stopped for the want of.
     *
     * <p>The criterion was "A rating can be assigned to a book and is displayed alongside the
     * book's details". The test written for it proved it through the service, which is all a JUnit
     * test in the acceptance module can ever do — and nothing had ever SAID so. Two enabler waves
     * later the service worked, the test was green before the wave that owns the criterion started,
     * and the run parked telling a person to revise a test that was doing its job.
     */
    @Test
    void aCriterionAboutWhatSomebodySeesTellsTheAuthorWhatItsTestCanAndCannotProve() throws Exception {
        StringBuilder seen = new StringBuilder();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            seen.append(conversation);
            return "{\"files\":[]}";
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));
            Task task = new Task(UUID.randomUUID(), 1, "rating",
                "show and set a book's rating", Set.of("src/main"), Set.of(), List.of(),
                ArchitectClient.ACCEPTANCE_TEST_DIR, null, null,
                new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
            List<AcceptanceCriterion> screenCheck = List.of(new AcceptanceCriterion(
                UUID.randomUUID(),
                "A rating can be assigned to a book and is displayed alongside the book's details",
                "swarm.accept.BookRatingTest#assignsRatingToBook"));

            author.authorTests(repo, task, null, screenCheck, "", true);

            assertThat(seen.toString())
                .as("it is told which half its test can settle")
                .contains("ONE OR MORE CRITERIA ABOVE ARE ABOUT WHAT A PERSON SEES ON SCREEN")
                .contains("it cannot open a browser")
                .as("told to write that half properly rather than skip the criterion")
                .contains("do not skip one because it mentions a screen")
                .as("told not to fake the screen, which is the other way this goes wrong")
                .contains("no asserting on HTML strings")
                .as("and told what does prove the screen, so it knows the job is covered")
                .contains("looks at it with a real browser in a later stage");
        }
    }

    /** A service-side criterion is authored by exactly the prompt it was authored by before. */
    @Test
    void aCriterionAboutTheServiceGetsNoScreenSteering() {
        List<AcceptanceCriterion> serviceCheck = List.of(new AcceptanceCriterion(
            UUID.randomUUID(), "A book's rating is stored and returned with the book",
            "swarm.accept.BookRatingTest#storesRating"));

        assertThat(TestAuthorClient.screenSteering(serviceCheck, true)).isEmpty();
    }

    /**
     * And a project that never starts its application gets none either. The steering promises that
     * a later stage looks at the screen with a real browser; where no browser block exists in the
     * verification contract, that promise would simply be false.
     */
    @Test
    void aProjectWithNoBrowserStageIsPromisedNothing() {
        List<AcceptanceCriterion> screenCheck = List.of(new AcceptanceCriterion(
            UUID.randomUUID(), "The rating is displayed alongside the book",
            "swarm.accept.BookRatingTest#assignsRatingToBook"));

        assertThat(TestAuthorClient.screenSteering(screenCheck, false)).isEmpty();
    }
}
