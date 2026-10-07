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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.knowledge.LibraryTypes;
import com.swarmcoder.knowledge.ProjectTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness runs 44/45, 2026-09-27, in their exact shape: the design's contract for "Create
 * BookListPage UI component in client module" promised
 *
 * <pre>
 * com.swarmcoder.demo.bookshelf.client.BookListPage{public com.zeroz4j.ui.TextField titleInput;;
 *   … public com.zeroz4j.ui.Button addButton;; … public com.zeroz4j.ui.ListView&lt;Book&gt; bookList;;
 *   public void bind(com.swarmcoder.demo.bookshelf.BookListService service);;
 *   public void render(java.util.List&lt;Book&gt; books);; … }
 * </pre>
 *
 * over a reference root (ZeroZ Stack's checkout) that has {@code KeyedList}, {@code TextField} and
 * {@code Button} in {@code com.zeroz4j.ui.component} and no {@code ListView} anywhere. No worker
 * could deliver the {@code bookList} member; this is what now sends it back at DESIGN_REVIEW.
 */
class ContractsNameRealTypesTest {

    @TempDir
    Path reference;

    @TempDir
    Path checkout;

    private LibraryTypes library;
    private ProjectTypes project;

    @BeforeEach
    void theBookshelfCheckoutAndAMiniatureZeroz4j() throws IOException {
        Path ui = reference.resolve(
            "zerozstack-ui-components/src/main/java/com/zeroz4j/ui/component");
        write(ui.resolve("KeyedList.java"), """
            package com.zeroz4j.ui.component;

            /**
             * Binds a {@code Signal<List<T>>} to a container's children, patching by key instead of
             * rebuilding. The standard way every dynamic list in the Console renders.
             */
            public final class KeyedList<T> {}
            """);
        write(ui.resolve("TextField.java"), "package com.zeroz4j.ui.component;\npublic class TextField {}\n");
        write(ui.resolve("Button.java"), "package com.zeroz4j.ui.component;\npublic class Button {}\n");
        write(reference.resolve("zerozstack-api/src/main/java/com/zeroz4j/api/DataModel.java"),
            "package com.zeroz4j.api;\npublic @interface DataModel {}\n");
        // The Bookshelf demo as the run found it: the shared module's one working example.
        write(checkout.resolve("bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/"
            + "Message.java"), "package com.swarmcoder.demo.bookshelf;\npublic class Message {}\n");
        library = LibraryTypes.of(List.of(reference));
        project = ProjectTypes.of(checkout);
    }

    @Test
    void run44sPageContractIsSentBackForListViewAndForTheTwoWidgetsInTheWrongPackage() {
        List<ContractsNameRealTypes.Unknown> unknown =
            ContractsNameRealTypes.unknown(run44Design(), project, library);

        assertThat(unknown).extracting(ContractsNameRealTypes.Unknown::typeName)
            .as("TextField, Button and ListView do not exist in com.zeroz4j.ui; java.util.List is "
                + "the JDK's, BookListService is planned by the design, and Book is a simple name "
                + "this check never guesses at")
            .containsExactlyInAnyOrder("com.zeroz4j.ui.TextField", "com.zeroz4j.ui.Button",
                "com.zeroz4j.ui.ListView");

        String listView = unknown.stream().filter(u -> u.typeName().endsWith("ListView"))
            .findFirst().orElseThrow().objection();
        assertThat(listView)
            .contains("the contract com.swarmcoder.demo.bookshelf.client.BookListPage names "
                + "com.zeroz4j.ui.ListView")
            .contains("public com.zeroz4j.ui.ListView<Book> bookList;")
            .contains("has no ListView in com.zeroz4j.ui")
            .contains("No worker can deliver a member whose type does not exist")
            .as("the nearest real type, with what its javadoc says it is for")
            .contains("com.zeroz4j.ui.component.KeyedList (Binds a Signal<List<T>> to a "
                + "container's children")
            .as("and what to do")
            .contains("Change the member to a type that exists, or drop the member");

        String textField = unknown.stream().filter(u -> u.typeName().endsWith("TextField"))
            .findFirst().orElseThrow().objection();
        assertThat(textField)
            .contains("Its TextField is com.zeroz4j.ui.component.TextField — write the member "
                + "with that name.");
    }

    @Test
    void aCorrectedContractPasses() {
        DesignDocument fixed = design(contract("com.swarmcoder.demo.bookshelf.client.BookListPage",
            "public com.zeroz4j.ui.component.TextField titleInput;",
            "public com.zeroz4j.ui.component.Button addButton;",
            "public com.zeroz4j.ui.component.KeyedList<Book> bookList;",
            "@com.zeroz4j.api.DataModel public java.util.Map.Entry<String, Integer> last;"));

        assertThat(ContractsNameRealTypes.objections(fixed, project, library)).isEmpty();
    }

    @Test
    void whatThereIsNoSourceForAndWhatTheProjectMayStillCreateIsNeverJudged() {
        DesignDocument design = design(contract("com.swarmcoder.demo.bookshelf.client.BookListPage",
            // TeaVM: on the classpath, but no source checked out anywhere.
            "public org.teavm.jso.dom.html.HTMLElement root;",
            // The project's own namespace: a write set may create it; nothing here can know.
            "public com.swarmcoder.demo.bookshelf.server.BooksRoot root;",
            // A simple name: which type it means depends on imports the contract does not have.
            "public ListView<Book> plain;"));

        assertThat(ContractsNameRealTypes.objections(design, project, library)).isEmpty();
    }

    @Test
    void aJdkTypeThatDoesNotExistIsAnObjectionToo() {
        DesignDocument design = design(contract("com.swarmcoder.demo.bookshelf.Book",
            "public java.util.ListView<String> tags;"));

        assertThat(ContractsNameRealTypes.objections(design, project, library))
            .singleElement().asString()
            .contains("the JDK has no ListView in java.util");
    }

    @Test
    void withNoReferenceRootOnlyTheJdkIsJudged() {
        assertThat(ContractsNameRealTypes.objections(run44Design(), project, LibraryTypes.NONE))
            .as("no reference source: com.zeroz4j.* is not this check's to judge")
            .isEmpty();
    }

    static DesignDocument run44Design() {
        return design(
            contract("com.swarmcoder.demo.bookshelf.Book",
                "public String id;", "public String title;", "public String author;",
                "public Book();"),
            contract("com.swarmcoder.demo.bookshelf.BookListService",
                "java.util.List<Book> getBooks();", "void addBook(Book book);",
                "void updateBook(Book book);", "void removeBook(String id);"),
            contract("com.swarmcoder.demo.bookshelf.client.BookListPage",
                "public com.zeroz4j.ui.TextField titleInput;",
                "public com.zeroz4j.ui.TextField authorInput;",
                "public com.zeroz4j.ui.Button addButton;",
                "public com.zeroz4j.ui.Button editButton;",
                "public com.zeroz4j.ui.Button removeButton;",
                "public com.zeroz4j.ui.ListView<Book> bookList;",
                "public void bind(com.swarmcoder.demo.bookshelf.BookListService service);",
                "public void render(java.util.List<Book> books);",
                "public void onAddClick();", "public void onEditClick();",
                "public void onRemoveClick();"));
    }

    static ApiContract contract(String type, String... members) {
        return new ApiContract(UUID.randomUUID(), type.substring(type.lastIndexOf('.') + 1),
            "", "", type, List.of(members));
    }

    static DesignDocument design(ApiContract... contracts) {
        return new DesignDocument(UUID.randomUUID(), 1, "Add, edit, and remove books", List.of(),
            List.of(), List.of(contracts), List.of(), null, Instant.now());
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
