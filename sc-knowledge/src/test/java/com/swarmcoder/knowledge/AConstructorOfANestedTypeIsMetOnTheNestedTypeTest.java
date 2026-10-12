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
 * Live supervised build, 2026-10-12: the contract of a class of queries listed
 * {@code public Search()} and {@code public Search(BookSearchRequest request)}, the constructors
 * of the nested class {@code Search}, with that class's field and method. Eight candidates wrote
 * the nested class and were failed for members the outer type "was delivered without".
 */
class AConstructorOfANestedTypeIsMetOnTheNestedTypeTest {

    /** The contract as the live build's design wrote it. */
    private static final List<String> AS_WRITTEN = List.of("public BookSearchRequest request;",
        "public Search()", "public Search(BookSearchRequest request)",
        "List<Book> execute(Object root)");

    @TempDir
    Path tree;

    @Test
    void aConstructorNamedForAnotherTypeIsRecognisedFromTheDeclarationAlone() {
        assertThat(ContractMember.constructorOfAnotherType("public Search()", "BookQueries"))
            .contains("Search");
        assertThat(ContractMember.constructorOfAnotherType(
            "public Search(BookSearchRequest request);", "BookQueries")).contains("Search");
        // the type's own constructor, a method, a loosely written method, a constant, a field
        assertThat(ContractMember.constructorOfAnotherType("BookQueries()", "BookQueries"))
            .isEmpty();
        assertThat(ContractMember.constructorOfAnotherType("Search search()", "BookQueries"))
            .isEmpty();
        assertThat(ContractMember.constructorOfAnotherType("save(Book book)", "BookQueries"))
            .isEmpty();
        assertThat(ContractMember.constructorOfAnotherType("ACTIVE()", "Status")).isEmpty();
        assertThat(ContractMember.constructorOfAnotherType("public Search search;", "BookQueries"))
            .isEmpty();
    }

    @Test
    void theNestedTypeThatDeclaresThemMeetsTheContract() throws Exception {
        queries("""
                public static final class Search implements DbQuery<List<Book>> {
                    public final BookSearchRequest request;
                    public Search() {
                        this.request = new BookSearchRequest();
                    }
                    public Search(BookSearchRequest request) {
                        this.request = request;
                    }
                    @Override
                    public List<Book> execute(Object root) {
                        return List.of();
                    }
                }
            """);

        assertThat(ContractDelivery.shortfalls(tree, List.of(contract(AS_WRITTEN)))).isEmpty();
        assertThat(ProjectTypes.of(tree).nestedTypesOf("com.demo.server.BookQueries"))
            .containsExactly("ListAll", "Search");
    }

    @Test
    void aNestedTypeWithNoConstructorWrittenOutHasTheDefaultOne() throws Exception {
        queries("""
                public static final class Search {
                    public BookSearchRequest request;
                    public List<Book> execute(Object root) {
                        return List.of();
                    }
                }
            """);

        assertThat(ContractDelivery.shortfalls(tree, List.of(contract(List.of(
            "public BookSearchRequest request;", "public Search()",
            "List<Book> execute(Object root)"))))).isEmpty();
    }

    @Test
    void aMemberNeitherTypeHasIsStillMissing() throws Exception {
        queries("""
                public static final class Search {
                    public final BookSearchRequest request;
                    public Search() {
                        this.request = null;
                    }
                    public Search(BookSearchRequest request) {
                        this.request = request;
                    }
                }
            """);

        List<ContractDelivery.Shortfall> shortfalls =
            ContractDelivery.shortfalls(tree, List.of(contract(AS_WRITTEN)));

        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingMembers())
            .containsExactly("List<Book> execute(Object root)");
    }

    @Test
    void aConstructorWithOtherArgumentsIsStillMissing() throws Exception {
        queries("""
                public static final class Search {
                    public BookSearchRequest request;
                    public Search(String text) {
                    }
                    public List<Book> execute(Object root) {
                        return List.of();
                    }
                }
            """);

        List<ContractDelivery.Shortfall> shortfalls =
            ContractDelivery.shortfalls(tree, List.of(contract(AS_WRITTEN)));

        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingMembers()).containsExactly("public Search()",
            "public Search(BookSearchRequest request)");
    }

    @Test
    void withNoNestedTypeOfThatNameEverythingIsMissingAsBefore() throws Exception {
        queries("");

        List<ContractDelivery.Shortfall> shortfalls =
            ContractDelivery.shortfalls(tree, List.of(contract(AS_WRITTEN)));

        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingMembers()).containsExactlyElementsOf(AS_WRITTEN);
    }

    @Test
    void aContractWithNoSuchConstructorIsNotMetByANestedType() throws Exception {
        queries("""
                public static final class Search {
                    public BookSearchRequest request;
                    public List<Book> execute(Object root) {
                        return List.of();
                    }
                }
            """);

        List<ContractDelivery.Shortfall> shortfalls = ContractDelivery.shortfalls(tree,
            List.of(contract(List.of("public BookSearchRequest request;",
                "List<Book> execute(Object root)"))));

        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingMembers()).hasSize(2);
    }

    private static ApiContract contract(List<String> members) {
        return new ApiContract(UUID.randomUUID(), "BookQueries", "the queries", "",
            "com.demo.server.BookQueries", members);
    }

    /** The class of queries as the project had it, plus what a candidate added. */
    private void queries(String added) throws Exception {
        Path file = tree.resolve("src/main/java/com/demo/server/BookQueries.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package com.demo.server;
            import java.util.List;
            public final class BookQueries {
                public static final class ListAll implements DbQuery<List<Book>> {
                    @Override
                    public List<Book> execute(Object root) {
                        return List.of();
                    }
                }
                private BookQueries() {
                }
            """ + added + "}\n");
    }
}
