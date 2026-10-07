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

import com.swarmcoder.domain.ApiContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JavaSourceFacts#membersOf} reads an {@code ENUM_CONSTANTS} member as ONE scanner entry
 * holding every constant's text — never one entry per constant — and a promised method's text can
 * carry its own modifiers ({@code "static ReadingStatus fromLabel(String label)"}). Fixed here
 * after a live run rejected a worker's exact, correct delivery of exactly such a contract because
 * neither the enum's constants nor the static method's return type were being read out correctly.
 */
class ContractDeliveryTest {

    private static final String TYPE = "com.swarmcoder.demo.bookshelf.shared.ReadingStatus";
    private static final List<String> MEMBERS = List.of(
        "HAVENT_STARTED", "CURRENTLY_READING", "FINISHED",
        "String label()", "static ReadingStatus fromLabel(String label)");

    @TempDir
    Path tree;

    @Test
    void anEnumDeliveredExactlyAsWrittenHasNoShortfall() throws Exception {
        write("""
            package com.swarmcoder.demo.bookshelf.shared;

            public enum ReadingStatus {
                HAVENT_STARTED("haven't started"),
                CURRENTLY_READING("currently reading"),
                FINISHED("finished");

                private final String label;

                ReadingStatus(String label) {
                    this.label = label;
                }

                public String label() {
                    return label;
                }

                public static ReadingStatus fromLabel(String label) {
                    for (ReadingStatus status : values()) {
                        if (status.label.equals(label)) {
                            return status;
                        }
                    }
                    throw new IllegalArgumentException(label);
                }
            }
            """);

        assertThat(shortfalls()).isEmpty();
    }

    @Test
    void anEnumMissingOneConstantNamesOnlyThatConstant() throws Exception {
        write("""
            package com.swarmcoder.demo.bookshelf.shared;

            public enum ReadingStatus {
                HAVENT_STARTED("haven't started"),
                FINISHED("finished");

                private final String label;

                ReadingStatus(String label) {
                    this.label = label;
                }

                public String label() {
                    return label;
                }

                public static ReadingStatus fromLabel(String label) {
                    return HAVENT_STARTED;
                }
            }
            """);

        var shortfalls = shortfalls();
        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingMembers()).containsExactly("CURRENTLY_READING");
    }

    /**
     * The exact defect from the live run: {@code static ReadingStatus fromLabel(String label)}
     * must match a declaration that reorders and adds modifiers on both sides — {@code public}
     * added, {@code static} still present but no longer leading, the parameter renamed and made
     * {@code final}, and a {@code throws} clause added — none of which change what was promised.
     */
    @Test
    void aStaticFactoryMethodMatchesDespiteModifiersAndAThrowsClause() throws Exception {
        write("""
            package com.swarmcoder.demo.bookshelf.shared;

            public enum ReadingStatus {
                HAVENT_STARTED("haven't started"),
                CURRENTLY_READING("currently reading"),
                FINISHED("finished");

                private final String label;

                ReadingStatus(String label) {
                    this.label = label;
                }

                public String label() {
                    return label;
                }

                public static ReadingStatus fromLabel(final String l) throws IllegalStateException {
                    for (ReadingStatus status : values()) {
                        if (status.label.equals(l)) {
                            return status;
                        }
                    }
                    throw new IllegalStateException(l);
                }
            }
            """);

        assertThat(shortfalls()).isEmpty();
    }

    /** A same-named method with different arguments is a different method: still missing. */
    @Test
    void aMethodOfTheSameNameButDifferentParametersDoesNotSatisfyTheContract() throws Exception {
        write("""
            package com.swarmcoder.demo.bookshelf.shared;

            public enum ReadingStatus {
                HAVENT_STARTED("haven't started"),
                CURRENTLY_READING("currently reading"),
                FINISHED("finished");

                private final String label;

                ReadingStatus(String label) {
                    this.label = label;
                }

                public String label() {
                    return label;
                }

                public static ReadingStatus fromLabel(String label, boolean strict) {
                    return HAVENT_STARTED;
                }
            }
            """);

        var shortfalls = shortfalls();
        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingMembers())
            .containsExactly("static ReadingStatus fromLabel(String label)");
    }

    /** A scanner gap — here, a type with an unparsable body — must never fail a candidate. */
    @Test
    void aTypeWhoseBodyCannotBeReadIsNeverReportedAsMissingMembers() throws Exception {
        write("package com.swarmcoder.demo.bookshelf.shared;\n\npublic enum ReadingStatus\n");

        assertThat(shortfalls()).isEmpty();
    }

    /**
     * Harness run 44 (2026-09-27): the design promised {@code Book{public String id;; …}} — each
     * field written as a statement, with its semicolon — and every correct delivery was rejected,
     * because the name read out of {@code "public String id;"} was {@code "id;"}.
     */
    @Test
    void aFieldPromisedWithItsSemicolonOrADefaultValueIsTheSamePromise() throws Exception {
        Path file = tree.resolve("src/main/java/com/swarmcoder/demo/bookshelf/Book.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package com.swarmcoder.demo.bookshelf;

            public class Book {
                public String id;
                public String title;
                public String author;
                public int stars = 0;

                public Book() {}
            }
            """);
        ApiContract book = new ApiContract(UUID.randomUUID(), "Book", "a book on the shelf",
            "Book", "com.swarmcoder.demo.bookshelf.Book",
            List.of("public String id;", "public String title;", "public String author;",
                "public int stars = 0;", "public Book();"));

        assertThat(ContractDelivery.shortfalls(tree, List.of(book))).isEmpty();
    }

    @Test
    void aFieldPromisedWithItsSemicolonThatIsReallyMissingIsStillMissing() throws Exception {
        Path file = tree.resolve("src/main/java/com/swarmcoder/demo/bookshelf/Book.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package com.swarmcoder.demo.bookshelf;

            public class Book {
                public String id;
                public Book() {}
            }
            """);
        ApiContract book = new ApiContract(UUID.randomUUID(), "Book", "a book on the shelf",
            "Book", "com.swarmcoder.demo.bookshelf.Book",
            List.of("public String id;", "public String author;"));

        List<ContractDelivery.Shortfall> shortfalls = ContractDelivery.shortfalls(tree, List.of(book));
        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingMembers()).containsExactly("public String author;");
    }

    private void write(String source) throws Exception {
        Path file = tree.resolve("src/main/java/com/swarmcoder/demo/bookshelf/shared/"
            + "ReadingStatus.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private List<ContractDelivery.Shortfall> shortfalls() {
        ApiContract contract = new ApiContract(UUID.randomUUID(), "ReadingStatus",
            "how far a book has been read", "ReadingStatus", TYPE, MEMBERS);
        return ContractDelivery.shortfalls(tree, List.of(contract));
    }
}
