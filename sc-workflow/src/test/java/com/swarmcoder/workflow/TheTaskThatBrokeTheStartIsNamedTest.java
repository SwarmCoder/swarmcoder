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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.knowledge.ProjectTypes;
import com.swarmcoder.verify.ExecTarget;
import com.swarmcoder.verify.ServeOutput;
import com.swarmcoder.verify.StartFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the application of the merged tree does not start, the task that wrote what its output
 * names is named from the tree and the chosen changes, and the stop can be answered for it
 * (DEVELOPER_CORRECTIONS section 92). Live supervised build, 2026-10-13: the output named a
 * class one task of the build had written; the build offered {@code retry} and nothing else.
 */
class TheTaskThatBrokeTheStartIsNamedTest {

    /** Output in the shape a refused start has: a member of a type, then stack frames. */
    private static final String OUTPUT = """
        starting
        SomethingException: a managed type has a public field: public \
        com.example.books.server.BookServiceImpl.db
        \tat org.framework.Boot.start(Boot.java:42)
        \tat com.example.books.Main.main(Main.java:9)
        \tat com.example.books.server.BookServiceImpl$Loader.load(BookServiceImpl.java:30)
        """;

    private static Task task(String title) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of("src/main/java"), Set.of(),
            List.of(), "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.DONE);
    }

    private static final Map<String, String> DECLARED = Map.of(
        "com.example.books.server.BookServiceImpl",
        "server/src/main/java/com/example/books/server/BookServiceImpl.java",
        "com.example.books.server.BookServiceImpl.Loader",
        "server/src/main/java/com/example/books/server/BookServiceImpl.java",
        "com.example.books.Main", "app/src/main/java/com/example/books/Main.java");

    private static final Function<String, String> TREE =
        name -> DECLARED.get(name.replace('$', '.'));

    @Test
    void theTypesTheOutputNamesAreThoseTheTreeDeclaresAndNothingElse() {
        Map<String, String> types = StartFailureOwner.typesNamedIn(OUTPUT, TREE);

        assertThat(types.keySet()).as("a member's type, a frame's type and a nested type; not "
                + "the framework's, not a file name")
            .containsExactly("com.example.books.server.BookServiceImpl",
                "com.example.books.Main", "com.example.books.server.BookServiceImpl$Loader");
        assertThat(types.get("com.example.books.Main"))
            .isEqualTo("app/src/main/java/com/example/books/Main.java");
        assertThat(StartFailureOwner.typesNamedIn("", TREE)).isEmpty();
        assertThat(StartFailureOwner.typesNamedIn("no dotted name here", TREE)).isEmpty();
    }

    /** The lookup is the tree's own index of declared types, on a real folder of sources. */
    @Test
    void theLookupIsTheTreesOwnIndexOfDeclaredTypes(@TempDir Path tree) throws Exception {
        Path file = tree.resolve("server/src/main/java/com/example/books/server/"
            + "BookServiceImpl.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package com.example.books.server;
            public class BookServiceImpl {
                public Object db;
            }
            """);
        ProjectTypes index = ProjectTypes.of(tree);

        Map<String, String> types = StartFailureOwner.typesNamedIn(OUTPUT, name -> {
            Path declaring = index.fileOf(name);
            return declaring == null ? null
                : tree.toAbsolutePath().relativize(declaring.toAbsolutePath()).toString();
        });

        assertThat(types).containsEntry("com.example.books.server.BookServiceImpl",
            "server/src/main/java/com/example/books/server/BookServiceImpl.java");
        assertThat(types).doesNotContainKey("com.example.books.Main");
    }

    @Test
    void oneTaskWroteWhatTheOutputNamesAndItIsOfferedRepair() {
        Task service = task("Book service");
        Task screen = task("Book list screen");
        Task untouched = task("Nothing chosen");
        Map<Task, List<String>> wrote = Map.of(
            service, List.of("server/src/main/java/com/example/books/server/BookServiceImpl.java"),
            screen, List.of("web/src/main/java/com/example/books/web/BookList.java"));

        StartFailureOwner.Named named = StartFailureOwner.name(OUTPUT,
            List.of(service, screen, untouched), t -> wrote.getOrDefault(t, List.of()), TREE);

        assertThat(named.task()).isSameAs(service);
        assertThat(named.candidates()).isEmpty();
        assertThat(named.why()).contains("`com.example.books.server.BookServiceImpl`")
            .contains("task 'Book service' wrote");

        FinalIntegrator.StartFailed failed = new FinalIntegrator.StartFailed(
            "it exited with code 1 after 4 seconds", service.id(), List.of(), named.why(), OUTPUT);
        StartFailureOwner.Offer offer =
            StartFailureOwner.offerFor(List.of(service, screen, untouched), failed);

        assertThat(offer.repair()).isEqualTo(service.id());
        assertThat(offer.choices()).isEmpty();
        assertThat(offer.paragraph())
            .startsWith("The application did not start in the merged tree: it exited with "
                + "code 1 after 4 seconds.")
            .contains("task 'Book service' wrote").contains("Answer repair with text");
        assertThat(("FINAL_INTEGRATION failed on branch swarm/integration/" + UUID.randomUUID()
            + ":\n\n" + offer.paragraph()).length())
            .as("inside what a supervisor is shown of a long question").isLessThan(700);

        // Section 84's cap: a task that has had its round is not offered another.
        service.setInstructedRepairRounds(1);
        StartFailureOwner.Offer used =
            StartFailureOwner.offerFor(List.of(service, screen, untouched), failed);
        assertThat(used.repair()).isNull();
        assertThat(used.choices()).isEmpty();
        assertThat(used.paragraph()).contains("only sending the story back remains");
    }

    @Test
    void severalTasksWroteWhatTheOutputNamesAndTheAnswerMustChoose() {
        Task service = task("Book service");
        Task main = task("Start-up");
        Task screen = task("Book list screen");
        Map<Task, List<String>> wrote = Map.of(
            service, List.of("server/src/main/java/com/example/books/server/BookServiceImpl.java"),
            main, List.of("app\\src\\main\\java\\com\\example\\books\\Main.java"),
            screen, List.of("web/src/main/java/com/example/books/web/BookList.java"));

        StartFailureOwner.Named named = StartFailureOwner.name(OUTPUT,
            List.of(service, main, screen), t -> wrote.getOrDefault(t, List.of()), TREE);

        assertThat(named.task()).isNull();
        assertThat(named.candidates()).containsExactly(service, main);
        assertThat(named.why()).contains("2 tasks of this build wrote")
            .contains("'Book service'").contains("'Start-up'")
            .doesNotContain("Book list screen");

        StartFailureOwner.Offer offer = StartFailureOwner.offerFor(List.of(service, main, screen),
            new FinalIntegrator.StartFailed("it exited with code 1 after 4 seconds", null,
                List.of(service.id(), main.id()), named.why(), OUTPUT));

        assertThat(offer.repair()).isNull();
        assertThat(offer.choices()).containsExactly(service.id(), main.id());
        assertThat(offer.paragraph())
            .contains("with task set to one of 'Book service', 'Start-up'");
    }

    @Test
    void whenNoTaskWroteWhatTheOutputNamesEveryTaskWithAChosenChangeIsACandidate() {
        Task screen = task("Book list screen");
        Task styles = task("Styles");
        Task untouched = task("Nothing chosen");
        Map<Task, List<String>> wrote = Map.of(
            screen, List.of("web/src/main/java/com/example/books/web/BookList.java"),
            styles, List.of("web/src/main/resources/styles.css"));

        StartFailureOwner.Named named = StartFailureOwner.name(OUTPUT,
            List.of(screen, styles, untouched), t -> wrote.getOrDefault(t, List.of()), TREE);
        StartFailureOwner.Named nothing = StartFailureOwner.name("it just stopped\n",
            List.of(screen, styles, untouched), t -> wrote.getOrDefault(t, List.of()), TREE);

        assertThat(named.task()).isNull();
        assertThat(named.candidates()).containsExactly(screen, styles);
        assertThat(named.why()).contains("no task of this build wrote the file of any of them");
        assertThat(nothing.candidates()).containsExactly(screen, styles);
        assertThat(nothing.why()).contains("names no type this project declares");

        // With nobody left to give a round to, the question says what remains.
        screen.setInstructedRepairRounds(1);
        styles.setInstructedRepairRounds(1);
        StartFailureOwner.Offer offer = StartFailureOwner.offerFor(
            List.of(screen, styles, untouched), new FinalIntegrator.StartFailed(
                "it was still running and had not answered after 300 seconds", null,
                List.of(screen.id(), styles.id()), nothing.why(), ""));
        assertThat(offer.choices()).isEmpty();
        assertThat(offer.paragraph()).contains("only sending the story back remains");
    }

    @Test
    void theQuestionCarriesTheLastLinesThatFitAndWhereTheWholeOutputIsRead() {
        String printed = "first line\n" + "a line in the middle of the output\n".repeat(200)
            + "the last line\n";
        StartFailure failure = new StartFailure("http://127.0.0.1:8080/", true, 300,
            new ExecTarget.Exited(1, 4), 5, ServeOutput.keep(printed, ServeOutput.FIRST_BYTES,
                ServeOutput.LAST_BYTES), "abc123");

        String detail = StartFailureOwner.detail(failure);

        assertThat(detail).contains("blob_text ref=abc123")
            .contains("The last lines the application printed:")
            .endsWith("the last line").doesNotContain("first line");
        assertThat(detail.length())
            .isLessThan(StartFailureOwner.QUESTION_OUTPUT_CHARS + 300);
        assertThat(StartFailureOwner.detail(null)).isEmpty();
        assertThat(StartFailureOwner.detail(new StartFailure("http://127.0.0.1:8080/", true, 300,
            null, 300, ServeOutput.NONE, null))).isEqualTo(" The serve command printed nothing.");
    }

    @Test
    void theRepairWorkersAreToldTheInstructionAndThenWhatTheApplicationPrinted() {
        String told = GreenfieldWorkflow.withStartOutput("make the field private", OUTPUT);

        assertThat(told).startsWith("make the field private\n\n")
            .contains("What it printed when it was started there")
            .endsWith(OUTPUT.strip());
        assertThat(GreenfieldWorkflow.withStartOutput("make the field private", null))
            .isEqualTo("make the field private");
    }
}
