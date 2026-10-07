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
package com.swarmcoder.verify;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of two ways a freshly authored acceptance test "does not compile" this classifier tells
 * apart — see {@link RedChecker} and {@code com.swarmcoder.workflow.UndeliverableType}, both of
 * which ask it the same question.
 */
class TypeDeliverabilityTest {

    private static Task task(String title, Set<String> writeSet, List<ApiContract> contracts) {
        Task t = new Task(UUID.randomUUID(), 1, title, "do it", writeSet, Set.of(), List.of(),
            "bookshelf-demo-server/src/test/java/swarm", null, null,
            new com.swarmcoder.domain.SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
        t.setDeliveredContracts(contracts);
        return t;
    }

    @Test
    void aTypeADesignContractDeliversIsNotBroken() {
        Task server = task("Implement the server", Set.of("src/main/java/com/demo/server"),
            List.of(new ApiContract(UUID.randomUUID(), "Rating", "", "",
                "com.demo.shared.Rating", List.of("int value"))));

        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("com.demo.shared.Rating"), List.of(server));

        assertThat(undeliverable).as("a contract type is deliverable, not broken").isEmpty();
    }

    @Test
    void aTypeInsideAWrittenPackageIsNotBroken() {
        Task shared = task("Implement the shared model",
            Set.of("src/main/java/com/demo/shared"), List.of());

        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("com.demo.shared.Rating"), List.of(shared));

        assertThat(undeliverable).as("the write set covers this package").isEmpty();
    }

    @Test
    void aPackageOutsideEveryWriteSetAndNoContractIsBroken() {
        // The run-26 shape: the acceptance test (server module) imports a client-only package.
        // No task's write set is under it, and no contract names a type in it.
        Task server = task("Implement the server", Set.of("src/main/java/com/demo/server"),
            List.of());

        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("com.zeroz4j.ui"), List.of(server));

        assertThat(undeliverable).containsExactly("com.zeroz4j.ui");
    }

    @Test
    void aTypoedSymbolNotInAnyContractIsBroken() {
        Task server = task("Implement the server", Set.of("src/main/java/com/demo/server"),
            List.of(new ApiContract(UUID.randomUUID(), "Book", "", "",
                "com.demo.shared.Book", List.of())));

        // The test author wrote "Bookk" instead of "Book" - a typo, not a not-yet-written type.
        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("com.demo.shared.Bookk"), List.of(server));

        assertThat(undeliverable).containsExactly("com.demo.shared.Bookk");
    }

    @Test
    void aBarePackageCoveredByATasksOwnWriteSetIsNotBroken() {
        // The missing entry is a PACKAGE, not a type ("package X does not exist"), and a task's
        // write set covers exactly that package - the fix for the off-by-one-package bug.
        Task ui = task("Implement the client screen", Set.of("src/main/java/com/demo/client/ui"),
            List.of());

        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("com.demo.client.ui"), List.of(ui));

        assertThat(undeliverable).as("the whole missing name is itself a package this task writes")
            .isEmpty();
    }

    @Test
    void theTypeNamedByAFileWriteSetEntryIsNotBroken() {
        // ArchitectClient's own prompt allows a write-set entry to name a FILE, not just a
        // directory - "src/.../org/jsoup/select/QueryParser.java" is a normal task write set.
        Task task = task("Port QueryParser", Set.of(
            "src/main/java/org/jsoup/select/QueryParser.java"), List.of());

        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("org.jsoup.select.QueryParser"), List.of(task));

        assertThat(undeliverable).as("the file entry names exactly this type").isEmpty();
    }

    @Test
    void anotherTypeInTheSamePackageAsAFileEntryIsNotBroken() {
        Task task = task("Port QueryParser", Set.of(
            "src/main/java/org/jsoup/select/QueryParser.java"), List.of());

        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("org.jsoup.select.Selector"), List.of(task));

        assertThat(undeliverable)
            .as("a file entry covers its parent directory's package like a directory entry would")
            .isEmpty();
    }

    @Test
    void aTypeInADifferentPackageFromAFileEntryIsBroken() {
        Task task = task("Port QueryParser", Set.of(
            "src/main/java/org/jsoup/select/QueryParser.java"), List.of());

        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("org.jsoup.nodes.Element"), List.of(task));

        assertThat(undeliverable).containsExactly("org.jsoup.nodes.Element");
    }

    @Test
    void aDirectoryWriteSetEntryIsUnchanged() {
        Task task = task("Implement select", Set.of("src/main/java/org/jsoup/select"), List.of());

        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("org.jsoup.select.QueryParser"), List.of(task));

        assertThat(undeliverable).as("a directory entry still covers everything under it").isEmpty();
    }

    @Test
    void aFileEntryWithWindowsSeparatorsIsRead() {
        Task task = task("Port QueryParser", Set.of(
            "src\\main\\java\\org\\jsoup\\select\\QueryParser.java"), List.of());

        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("org.jsoup.select.QueryParser"), List.of(task));

        assertThat(undeliverable).as("backslash-separated paths read the same as forward-slash")
            .isEmpty();
    }

    @Test
    void aModulePrefixedFileEntryIsRead() {
        Task task = task("Port QueryParser", Set.of(
            "bookshelf-demo-server/src/main/java/org/jsoup/select/QueryParser.java"), List.of());

        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("org.jsoup.select.QueryParser"), List.of(task));

        assertThat(undeliverable).as("a module prefix ahead of the source root is still read")
            .isEmpty();
    }

    @Test
    void noTasksMeansEverythingIsBroken() {
        List<String> undeliverable = TypeDeliverability.undeliverable(
            List.of("com.demo.shared.Rating"), List.of());

        assertThat(undeliverable).containsExactly("com.demo.shared.Rating");
    }

    @Test
    void noMissingNamesIsNeverBroken() {
        assertThat(TypeDeliverability.undeliverable(List.of(), List.of())).isEmpty();
        assertThat(TypeDeliverability.undeliverable(null, null)).isEmpty();
    }

    // ---- typeNamed: the forward reading a write-set entry gives up, for AcceptanceTestVocabulary
    // (harness run 38, 2026-09-25) ------------------------------------------------------------

    @Test
    void typeNamedReadsAFileEntryUnderARecognisedSourceRoot() {
        assertThat(TypeDeliverability.typeNamed(
            "bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/"
                + "BookshelfServiceImpl.java"))
            .isEqualTo("com.swarmcoder.demo.bookshelf.server.BookshelfServiceImpl");
    }

    @Test
    void typeNamedReadsBackslashSeparatedPaths() {
        assertThat(TypeDeliverability.typeNamed(
            "src\\main\\java\\org\\jsoup\\select\\QueryParser.java"))
            .isEqualTo("org.jsoup.select.QueryParser");
    }

    @Test
    void typeNamedIsNullForADirectoryEntry() {
        // A directory promises a whole package, not one concrete type.
        assertThat(TypeDeliverability.typeNamed("src/main/java/org/jsoup/select")).isNull();
    }

    @Test
    void typeNamedIsNullWhenNoSourceRootIsRecognised() {
        // Unlike mayCreate (which fails open here so a wave still dispatches), typeNamed must NOT
        // guess: this name would be offered to a test author as safe to use.
        assertThat(TypeDeliverability.typeNamed("some/odd/layout/Thing.java")).isNull();
    }

    @Test
    void typeNamedIsNullForBlankOrMissingInput() {
        assertThat(TypeDeliverability.typeNamed(null)).isNull();
        assertThat(TypeDeliverability.typeNamed("")).isNull();
        assertThat(TypeDeliverability.typeNamed("   ")).isNull();
    }
}
