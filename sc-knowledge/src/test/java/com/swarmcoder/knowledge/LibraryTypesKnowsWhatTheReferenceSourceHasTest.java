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
package com.swarmcoder.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness runs 44/45, 2026-09-27: a design contract promised {@code com.zeroz4j.ui.ListView<Book>
 * bookList}, and fields of {@code com.zeroz4j.ui.TextField} and {@code com.zeroz4j.ui.Button}.
 * ZeroZ Stack has no ListView at all (its dynamic-list component is {@code
 * com.zeroz4j.ui.component.KeyedList}), and its TextField and Button live in {@code
 * com.zeroz4j.ui.component}. This is the reference-checkout half of catching that at
 * DESIGN_REVIEW: what the checkout has, which namespace it speaks for, and what it offers instead.
 *
 * <p>The reference root here is a miniature of {@code C:/work/zeroz4j} with the real files'
 * package layout and javadoc first sentences.
 */
class LibraryTypesKnowsWhatTheReferenceSourceHasTest {

    @TempDir
    Path reference;

    private LibraryTypes library;

    @BeforeEach
    void aMiniatureZeroz4jCheckout() throws IOException {
        Path ui = reference.resolve(
            "zerozstack-ui-components/src/main/java/com/zeroz4j/ui/component");
        write(ui.resolve("KeyedList.java"), """
            package com.zeroz4j.ui.component;

            /**
             * Binds a {@code Signal<List<T>>} to a container's children, patching by key instead of
             * rebuilding. The standard way every dynamic list in the Console renders.
             */
            public final class KeyedList<T> {
                public void dispose() {}
            }
            """);
        write(ui.resolve("DiffView.java"), """
            package com.zeroz4j.ui.component;

            /**
             * Unified-diff renderer: one collapsible section per file with +N -M badges.
             */
            public final class DiffView {}
            """);
        write(ui.resolve("TextField.java"), """
            package com.zeroz4j.ui.component;

            public class TextField {}
            """);
        write(ui.resolve("Button.java"), """
            package com.zeroz4j.ui.component;

            public class Button {}
            """);
        write(reference.resolve(
            "zerozstack-ui-components/src/test/java/com/zeroz4j/ui/ListViewContractTest.java"), """
            package com.zeroz4j.ui;

            class ListViewContractTest {}
            """);
        write(reference.resolve(
            "zerozstack-examples/todo/src/main/java/com/zeroz4j/example/client/ListView.java"), """
            package com.zeroz4j.example.client;

            /** An example's own list view — not part of the library's UI package. */
            public class ListView {}
            """);
        library = LibraryTypes.of(List.of(reference));
    }

    @Test
    void theCheckoutSpeaksForTheUiPackageButNotForALibraryItHasNoSourceFor() {
        assertThat(library.covers("com.zeroz4j.ui"))
            .as("com.zeroz4j.ui.component is in the checkout, so com.zeroz4j.ui is its namespace")
            .isTrue();
        assertThat(library.coveringRoot("com.zeroz4j.ui"))
            .isEqualTo(reference.getFileName().toString());
        assertThat(library.covers("org.teavm.jso.dom.html"))
            .as("TeaVM is on the build's classpath but its source is checked out nowhere: "
                + "not ours to judge")
            .isFalse();
        assertThat(library.covers("com.zeroz4j.widgets"))
            .as("sharing only com.zeroz4j is not enough: a two-segment prefix is an organisation, "
                + "not a library")
            .isFalse();
    }

    @Test
    void listViewDoesNotExistAndTheNearestRealTypeIsKeyedList() {
        assertThat(library.declares("com.zeroz4j.ui.ListView")).isFalse();
        assertThat(library.declares("com.zeroz4j.ui.component.KeyedList")).isTrue();

        List<LibraryTypes.Suggestion> nearest = library.nearest("com.zeroz4j.ui.ListView", 3);

        assertThat(nearest).extracting(LibraryTypes.Suggestion::fullName)
            .as("KeyedList first: it shares 'List' and its javadoc is about lists; the example's "
                + "own ListView is in com.zeroz4j.example, not the UI library, and the test class "
                + "is never on anybody's classpath")
            .containsExactly("com.zeroz4j.ui.component.KeyedList",
                "com.zeroz4j.ui.component.DiffView");
        assertThat(nearest.get(0).summary())
            .startsWith("Binds a Signal<List<T>> to a container's children");
    }

    @Test
    void aTypeInTheWrongPackageGetsItsRealHomeAndNothingElse() {
        assertThat(library.declares("com.zeroz4j.ui.TextField")).isFalse();

        assertThat(library.nearest("com.zeroz4j.ui.TextField", 3))
            .extracting(LibraryTypes.Suggestion::fullName)
            .containsExactly("com.zeroz4j.ui.component.TextField");
    }

    @Test
    void theJdkAnswersForItsOwnPackages() {
        assertThat(LibraryTypes.isJdkPackage("java.util")).isTrue();
        assertThat(LibraryTypes.isJdkPackage("com.zeroz4j.ui")).isFalse();
        assertThat(LibraryTypes.jdkDeclares("java.util.List")).isTrue();
        assertThat(LibraryTypes.jdkDeclares("java.util.Map.Entry")).isTrue();
        assertThat(LibraryTypes.jdkDeclares("java.util.ListView")).isFalse();
    }

    @Test
    void noReferenceRootCoversNothing() {
        assertThat(LibraryTypes.NONE.covers("com.zeroz4j.ui")).isFalse();
        assertThat(LibraryTypes.of(List.of(reference.resolve("missing"))).any()).isFalse();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
