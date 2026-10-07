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

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live harness run 54 (2026-10-01): the test author could not tell how to build the server-side
 * Store and wrote a helper that searched for its constructor by reflection. Every candidate failed
 * inside the test's own code, the repair did the same, and the run parked with three of four tasks
 * built. A test may call the project's types through their contract members or say the contracts do
 * not say how; it may not guess.
 */
class AnAcceptanceTestMayNotGuessTheApiByReflectionTest {

    private static final String TEST_PATH = "src/test/java/swarm/accept/EditContactTest.java";

    /** Run 54's helper, in the shape it was written. */
    private static final String REFLECTING = """
        package swarm.accept;
        import org.junit.jupiter.api.Test;
        class EditContactTest {
            private Object createStore() throws Exception {
                for (var c : com.hambook.server.Store.class.getDeclaredConstructors()) { return c.newInstance(); }
                throw new IllegalStateException("No suitable Store constructor found");
            }
            @Test void edits() throws Exception { createStore(); }
        }
        """;
    private static final String DIRECT = """
        package swarm.accept;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class EditContactTest {
            @Test void edits() throws Exception {
                var store = new com.hambook.server.Store(new java.util.HashMap<String, String>());
                assertEquals("trim", String.class.getMethod("trim").getName());
            }
        }
        """;

    @TempDir
    Path repo;

    @Test
    void aTestThatFindsAProjectConstructorByReflectionIsFlagged() throws Exception {
        write(REFLECTING);

        AcceptanceTestReflection.Check check = AcceptanceTestReflection.check(repo, List.of(TEST_PATH));

        assertThat(check.ok()).isFalse();
        assertThat(check.findings()).hasSize(1);
        assertThat(check.findings().get(0).evidence()).contains("Store.class.getDeclaredConstructors()");
        assertThat(AcceptanceTestReflection.reask(check))
            .contains("by reflection")
            .contains("call exactly the members the contracts give");
    }

    @Test
    void aNormalTestAndReflectionOnJdkTypesAreNotFlagged() throws Exception {
        write(DIRECT);
        assertThat(AcceptanceTestReflection.check(repo, List.of(TEST_PATH)).ok()).isTrue();

        // A bean accessor that happens to share a name, and a JDK class by name, are not guesses.
        assertThat(AcceptanceTestReflection.inSource("T.java", """
            class T { void t(Object request) throws Exception {
                request.getMethod();
                Class.forName("java.sql.Driver");
            } }
            """)).isEmpty();
    }

    @Test
    void forNameOnAProjectClassAndSetAccessibleAreFlagged() {
        assertThat(AcceptanceTestReflection.inSource("T.java", """
            class T { void t() throws Exception {
                Class<?> c = Class.forName("com.hambook.server.Store");
                c.getDeclaredConstructors()[0].setAccessible(true);
            } }
            """)).hasSize(2); // the forName line, and the line with the lookup and setAccessible
    }

    @Test
    void anAuthorWhoReflectsIsAskedOnceAndACleanCorrectionIsAccepted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> asks = new CopyOnWriteArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            asks.add(conversation);
            return reply(calls.incrementAndGet() == 1 ? REFLECTING : DIRECT);
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null));

            TestAuthorClient.Authored authored = author.authorTests(repo, task(), null);

            assertThat(calls.get()).as("one re-ask").isEqualTo(2);
            assertThat(authored.callsMembersDirectly()).isTrue();
            assertThat(asks.get(0)).as("said before the first line is written")
                .contains(AcceptanceTestReflection.AUTHOR_RULE);
            assertThat(asks.get(1)).contains("Your test finds the project's own constructors");
        }
    }

    @Test
    void anAuthorWhoStillReflectsAfterTheReaskIsReportedForParking() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            calls.incrementAndGet();
            return reply(REFLECTING);
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null));

            TestAuthorClient.Authored authored = author.authorTests(repo, task(), null);

            assertThat(calls.get()).isEqualTo(2);
            assertThat(authored.callsMembersDirectly()).isFalse();
            assertThat(AcceptanceTestReflection.brief("Implement LogbookServiceImpl",
                authored.reflection())).contains("Implement LogbookServiceImpl")
                .contains("Decide which side is wrong");
        }
    }

    /**
     * The mid-run repair is shown what the project's types really declare now: by the time a test
     * fails inside its own code, an earlier task has written the Store it could not build.
     */
    @Test
    void theRepairPromptCarriesTheConstructorsOfTheProjectTypesTheTestTouches() throws Exception {
        Path tree = repo.resolve("progress");
        Path dir = tree.resolve("src/main/java/com/hambook/server");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Store.java"), """
            package com.hambook.server;
            public class Store {
                public Store(HamBookRoot root) {}
                public void write(String key) {}
            }
            """);
        Files.writeString(dir.resolve("HamBookRoot.java"), """
            package com.hambook.server;
            public class HamBookRoot { public HamBookRoot() {} }
            """);

        String signatures = TouchedProjectTypes.signatures(tree, REFLECTING);
        assertThat(signatures).contains("Store: public Store(HamBookRoot root)")
            .contains("HamBookRoot: public HamBookRoot()");

        List<String> conversations = new CopyOnWriteArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            conversations.add(conversation);
            return reply(DIRECT);
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null));

            TestAuthorClient.Authored repaired = author.repairFailingTest(repo, task(), null,
                TEST_PATH, REFLECTING, "EditContactTest#edits - No suitable Store constructor found",
                signatures);

            assertThat(repaired.failureReason()).isNull();
            assertThat(conversations).hasSize(1);
            assertThat(conversations.get(0))
                .contains("Store: public Store(HamBookRoot root)")
                .contains("call exactly these members")
                .contains(AcceptanceTestReflection.AUTHOR_RULE);
        }
    }

    // ------------------------------------------------------------------------------------------

    private void write(String source) throws Exception {
        Path file = repo.resolve(TEST_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static String reply(String source) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of(
                "files", List.of(Map.of("path", TEST_PATH, "content", source)),
                "wrote", List.of()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Implement LogbookServiceImpl", "edit a contact",
            Set.of("src/main"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "a contact can be edited",
                "swarm.accept.EditContactTest")),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }
}
