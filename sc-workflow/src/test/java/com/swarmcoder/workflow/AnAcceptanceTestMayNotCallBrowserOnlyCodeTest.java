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
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.BuildLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decided design: <b>an acceptance test may only call code that runs where the test runs — on
 * a plain JVM.</b> A module whose code runs only in a browser is named to the architect and to the
 * test author, and a test that calls it anyway is sent back once, then parks.
 *
 * <h2>The run this is a copy of</h2>
 *
 * <p>Harness run 37, 2026-09-25, DeepSeek V4 Flash, {@code dev/bookshelf-demo}. Check R5 "after
 * restarting the browser, previously added books and their ratings are still present". The test
 * author wrote {@code swarm.accept.BookPersistenceTest} in {@code bookshelf-demo-server}, calling
 * {@code BookStore.getInstance()} from the TeaVM client module and resetting the singleton by
 * reflection "to simulate a browser restart". Every gate was satisfied — BookStore was a contract,
 * the test did not implement it, and "BookStore does not compile" was a healthy red — and both
 * workers died on {@code UnsatisfiedLinkError: org.teavm.jso.browser.Window.current()}. The test
 * below is run 37's, verbatim.
 *
 * <h2>What is asserted here</h2>
 *
 * <ol>
 *   <li>the author's very first prompt names the browser-only module, what its build file says,
 *       and how a browser-worded check is proved on the JVM;</li>
 *   <li>run 37's test is refused at authoring with a re-ask naming BookStore and the module, and
 *       the server-side correction is what ends up on disk;</li>
 *   <li>an author that will not correct itself leaves a result the workflow parks on, with a brief
 *       that says what to decide;</li>
 *   <li>an honest server-side test is not accused, and a project without a browser-only module
 *       gets exactly the prompt it always did;</li>
 *   <li>the architect is told the same thing at DESIGN and PLAN.</li>
 * </ol>
 */
class AnAcceptanceTestMayNotCallBrowserOnlyCodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTECTED = "bookshelf-demo-server/src/test/java/swarm";
    private static final String TEST_FILE = PROTECTED + "/accept/BookPersistenceTest.java";

    @TempDir
    Path repo;

    @BeforeEach
    void theBookshelfBuild() throws Exception {
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>com.swarmcoder.demo</groupId><artifactId>bookshelf-demo</artifactId>
              <version>1</version><packaging>pom</packaging>
              <modules>
                <module>bookshelf-demo-shared</module>
                <module>bookshelf-demo-client</module>
                <module>bookshelf-demo-server</module>
              </modules>
            </project>
            """);
        write("bookshelf-demo-shared/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><artifactId>bookshelf-demo-shared</artifactId>
            </project>
            """);
        write("bookshelf-demo-client/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><artifactId>bookshelf-demo-client</artifactId>
              <dependencies>
                <dependency><groupId>org.teavm</groupId><artifactId>teavm-classlib</artifactId>
                  <scope>provided</scope></dependency>
                <dependency><groupId>com.swarmcoder.demo</groupId><artifactId>bookshelf-demo-shared</artifactId></dependency>
              </dependencies>
            </project>
            """);
        write("bookshelf-demo-server/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><artifactId>bookshelf-demo-server</artifactId>
              <dependencies>
                <dependency><groupId>com.swarmcoder.demo</groupId><artifactId>bookshelf-demo-shared</artifactId></dependency>
                <dependency><groupId>com.swarmcoder.demo</groupId><artifactId>bookshelf-demo-client</artifactId>
                  <scope>provided</scope></dependency>
              </dependencies>
            </project>
            """);
        write("bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/model/Message.java",
            "package com.swarmcoder.demo.bookshelf.model; public class Message {}");
        write("bookshelf-demo-client/src/main/java/com/swarmcoder/demo/bookshelf/client/ClientApp.java",
            "package com.swarmcoder.demo.bookshelf.client; public class ClientApp {}");
        write("bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/ServerApp.java",
            "package com.swarmcoder.demo.bookshelf.server; public class ServerApp {}");
    }

    @Test
    void run37sTestIsRefusedAtAuthoringAndTheServerSideCorrectionIsWhatSurvives() throws Exception {
        List<String> conversations = new ArrayList<>();
        AtomicInteger replies = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            conversations.add(conversation);
            return replies.getAndIncrement() == 0 ? reply(RUN_37_TEST) : reply(SERVER_SIDE_TEST);
        })) {
            TestAuthorClient.Authored authored = authorWithSurvey(llm);

            // --- 1. told before it wrote a line --------------------------------------------------
            assertThat(conversations.get(0))
                .contains("CODE THAT RUNS ONLY IN A BROWSER")
                .contains("bookshelf-demo-client declares org.teavm:teavm-classlib")
                .contains("UnsatisfiedLinkError")
                .contains("com.swarmcoder.demo.bookshelf.client")
                .contains("after restarting the browser, the books are still there")
                .as("the client comes off the list of what the test can import")
                .doesNotContain("declares: bookshelf-demo-shared, bookshelf-demo-client");

            // --- 2. refused once, with the reason, and the correction survives ------------------
            assertThat(conversations).as("one first attempt and one re-ask").hasSize(2);
            assertThat(conversations.get(1))
                .contains("Your test calls code that can only run in a browser")
                .contains("`com.swarmcoder.demo.bookshelf.client.BookStore` — code of "
                    + "bookshelf-demo-client, which runs only in a browser")
                .contains("import com.swarmcoder.demo.bookshelf.client.BookStore;");
            assertThat(authored.paths()).containsExactly(TEST_FILE);
            assertThat(Files.readString(repo.resolve(TEST_FILE)))
                .contains("new BookServiceImpl(")
                .doesNotContain("bookshelf.client");
            assertThat(authored.runsOnTheJvm()).isTrue();
            assertThat(authored.vocabularyIsAnswerable()).isTrue();
            assertThat(authored.provesDeliveredCode()).isTrue();
        }
    }

    @Test
    void anAuthorThatKeepsCallingTheClientLeavesAResultTheRunParksOn() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> reply(RUN_37_TEST))) {
            TestAuthorClient.Authored authored = authorWithSurvey(llm);

            assertThat(authored.runsOnTheJvm()).isFalse();
            assertThat(authored.reach().names())
                .contains("com.swarmcoder.demo.bookshelf.client.BookStore");
            assertThat(AcceptanceTestReach.brief("Create the client BookStore singleton",
                    authored.reach()))
                .contains("call code that can only run in a browser")
                .contains("was asked once")
                .contains("bookshelf-demo-client declares org.teavm:teavm-classlib")
                .contains("Decide which side is wrong");
        }
    }

    @Test
    void anHonestServerSideTestIsNotAccused() throws Exception {
        List<String> conversations = new ArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            conversations.add(conversation);
            return reply(SERVER_SIDE_TEST);
        })) {
            TestAuthorClient.Authored authored = authorWithSurvey(llm);

            assertThat(conversations).hasSize(1);
            assertThat(authored.runsOnTheJvm()).isTrue();
        }
    }

    @Test
    void aProjectWithNoBrowserOnlyModuleIsToldNothingNew() throws Exception {
        List<String> conversations = new ArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            conversations.add(conversation);
            return reply(SERVER_SIDE_TEST);
        })) {
            author(llm).authorTests(repo, task(), design(), task().criteria(), "", false,
                "bookshelf-demo-server", List.of("bookshelf-demo-shared"));

            assertThat(conversations.get(0)).doesNotContain("CODE THAT RUNS ONLY IN A BROWSER");
        }
    }

    @Test
    void aDirectBrowserRuntimeImportIsRefusedEvenWithoutALayout() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;
            import org.teavm.jso.browser.Window;
            // com.swarmcoder.demo.bookshelf.client.BookStore in a comment is not evidence
            class BookPersistenceTest { void t() { Window.current(); } }
            """);

        AcceptanceTestReach.Check check = AcceptanceTestReach.check(repo,
            BrowserOnlyCode.Survey.NONE, List.of(TEST_FILE));

        assertThat(check.names()).containsExactly("org.teavm.jso.browser.Window");
    }

    @Test
    void theArchitectIsToldAtDesignAndAtPlan() throws Exception {
        BuildLayout.Layout layout = BuildLayout.read(repo, "maven");
        BrowserOnlyCode.Survey survey = BrowserOnlyCode.survey(repo, layout);

        String plan = RepoLayoutBrief.render(layout, survey, "bookshelf-demo-server");
        assertThat(plan)
            .contains("WHERE THIS REPOSITORY'S CODE LIVES")
            .contains("CODE THAT RUNS ONLY IN A BROWSER")
            .contains("bookshelf-demo-client declares org.teavm:teavm-classlib")
            .contains("they live in bookshelf-demo-server")
            .contains("(bookshelf-demo-shared, bookshelf-demo-server)")
            .contains("Never make a type in bookshelf-demo-client the only way to reach a checked "
                + "behaviour");
        assertThat(RepoLayoutBrief.render(layout, BrowserOnlyCode.Survey.NONE, "x"))
            .isEqualTo(RepoLayoutBrief.render(layout));

        List<String> conversations = new ArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            conversations.add(conversation);
            return "{\"requirements\":[],\"decisions\":[],\"contracts\":[],\"risks\":[]}";
        })) {
            new ArchitectClient(new VllmClient(llm.baseUrl(), null, "test-model", true),
                new CloudGate(1_000_000, null))
                .design("Persist books across browser restarts", "", false,
                    AcceptanceTestReach.architectBrief(survey, "bookshelf-demo-server"));
        }
        assertThat(conversations.get(0))
            .contains("Persist books across browser restarts")
            .contains("CODE THAT RUNS ONLY IN A BROWSER");
    }

    // ------------------------------------------------------------------ helpers

    private TestAuthorClient.Authored authorWithSurvey(ScriptedLlm llm) {
        BrowserOnlyCode.Survey survey = BrowserOnlyCode.survey(repo, BuildLayout.read(repo, "maven"));
        assertThat(survey.browserOnlyDirs()).containsExactly("bookshelf-demo-client");
        // What the workflow hands over: the server's declared siblings, the TeaVM client removed.
        return author(llm).authorTests(repo, task(), design(), task().criteria(), "", false,
            "bookshelf-demo-server", List.of("bookshelf-demo-shared"), survey);
    }

    private static TestAuthorClient author(ScriptedLlm llm) {
        return new TestAuthorClient(new VllmClient(llm.baseUrl(), "", "scripted", true),
            new CloudGate(1_000_000, null));
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Create the client BookStore singleton",
            "Create the client BookStore singleton that restores books from the server.",
            Set.of("bookshelf-demo-client/src/main/java/com/swarmcoder/demo/bookshelf/client"),
            Set.of(), List.of(new AcceptanceCriterion(UUID.randomUUID(),
                "After restarting the browser, previously added books and their ratings are still "
                    + "present.",
                "swarm.accept.BookPersistenceTest#dataSurvivesBrowserRestart")),
            PROTECTED, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    /** Run 37's contracts: shared Book and BookService, the client's BookStore, the server impl. */
    private static DesignDocument design() {
        return new DesignDocument(UUID.randomUUID(), 1, "persist books", List.of(), List.of(),
            List.of(
                new ApiContract(UUID.randomUUID(), "Book", "a book", "Book",
                    "com.swarmcoder.demo.bookshelf.shared.Book",
                    List.of("String id", "String title", "int rating")),
                new ApiContract(UUID.randomUUID(), "BookService", "the RMI service", "BookService",
                    "com.swarmcoder.demo.bookshelf.shared.BookService",
                    List.of("Book addBook(String)", "void rateBook(String, int)",
                        "List<Book> getBooks()", "void clearAll()")),
                new ApiContract(UUID.randomUUID(), "BookStore", "client singleton", "BookStore",
                    "com.swarmcoder.demo.bookshelf.client.BookStore",
                    List.of("static BookStore getInstance()")),
                new ApiContract(UUID.randomUUID(), "BookServiceImpl", "EclipseStore-backed service",
                    "BookServiceImpl", "com.swarmcoder.demo.bookshelf.server.BookServiceImpl",
                    List.of("BookServiceImpl(java.nio.file.Path storageDir)"))),
            List.of(), null, Instant.now());
    }

    private static String reply(String source) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                "files", List.of(Map.of("path", TEST_FILE, "content", source)),
                "wrote", List.of(Map.of("criterion", "After restarting the browser",
                    "test", "swarm.accept.BookPersistenceTest#dataSurvivesBrowserRestart"))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(String path, String content) throws Exception {
        Path target = repo.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    /** Harness run 37's test, verbatim from swarm/tests/67431f61-6d0e-4fb1-9ebc-5a9b2d00e4af. */
    private static final String RUN_37_TEST = """
        package swarm.accept;

        import com.swarmcoder.demo.bookshelf.client.BookStore;
        import com.swarmcoder.demo.bookshelf.shared.Book;
        import org.junit.jupiter.api.Test;

        import java.lang.reflect.Field;
        import java.lang.reflect.Modifier;
        import java.util.List;

        import static org.junit.jupiter.api.Assertions.*;

        /**
         * Acceptance test for the BookStore client singleton.
         *
         * Criterion: after restarting the browser, previously added books and their
         * ratings are still present. A browser restart discards the client-side
         * singleton, so a fresh getInstance() must reconnect to the server and reload
         * the books from it.
         */
        public class BookPersistenceTest {

            @Test
            void dataSurvivesBrowserRestart() throws Exception {
                // First browser session.
                BookStore first = BookStore.getInstance();
                assertSame(first, BookStore.getInstance(), "getInstance() must return the same singleton");

                first.clearAll(); // deterministic clean state

                Book added = first.addBook("The Hobbit");
                assertNotNull(added.id, "addBook must return a book with an id");
                first.rateBook(added.id, 5);

                // Simulate a browser restart: the client-side singleton is discarded,
                // so a fresh getInstance() must reconnect to the server and reload.
                resetSingleton();

                BookStore second = BookStore.getInstance();
                assertNotSame(first, second, "after restart a new singleton instance is required");

                List<Book> books = second.getBooks();
                assertEquals(1, books.size(), "server must still hold exactly one book");
                Book restored = books.get(0);
                assertEquals(added.id, restored.id, "book id must survive restart");
                assertEquals("The Hobbit", restored.title, "book title must survive restart");
                assertEquals(5, restored.rating, "book rating must survive restart");

                // Cleanup so this test does not pollute other acceptance tests.
                second.clearAll();
            }

            /**
             * Resets every static field of type {@code BookStore} in {@code BookStore}
             * itself and in its nested classes (e.g. a lazy-holder singleton would put
             * the instance field in a private static nested class). This makes the next
             * {@code getInstance()} call create a brand-new instance, mimicking a
             * browser restart.
             */
            private void resetSingleton() throws Exception {
                resetSingletonIn(BookStore.class);
            }

            private void resetSingletonIn(Class<?> clazz) throws Exception {
                for (Field field : clazz.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && field.getType().equals(BookStore.class)) {
                        field.setAccessible(true);
                        field.set(null, null);
                    }
                }
                for (Class<?> nested : clazz.getDeclaredClasses()) {
                    resetSingletonIn(nested);
                }
            }
        }
        """;

    /** The check proved where the behaviour lives: the server's stored books survive a fresh start. */
    private static final String SERVER_SIDE_TEST = """
        package swarm.accept;

        import com.swarmcoder.demo.bookshelf.server.BookServiceImpl;
        import com.swarmcoder.demo.bookshelf.shared.Book;
        import com.swarmcoder.demo.bookshelf.shared.BookService;
        import org.junit.jupiter.api.Test;
        import org.junit.jupiter.api.io.TempDir;

        import java.nio.file.Path;
        import java.util.List;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        public class BookPersistenceTest {

            @TempDir
            Path storage;

            @Test
            void dataSurvivesBrowserRestart() {
                BookService before = new BookServiceImpl(storage);
                Book added = before.addBook("The Hobbit");
                before.rateBook(added.id, 5);

                BookService after = new BookServiceImpl(storage);
                List<Book> books = after.getBooks();
                assertEquals(1, books.size());
                assertEquals(5, books.get(0).rating);
            }
        }
        """;
}
