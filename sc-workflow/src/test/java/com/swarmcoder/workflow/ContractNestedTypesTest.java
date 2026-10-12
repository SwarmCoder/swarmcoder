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
import com.swarmcoder.knowledge.ContractDelivery;
import com.swarmcoder.knowledge.ProjectTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live supervised build, 2026-10-12: a design wrote the members of a nested class, its
 * constructors among them, as members of the class it is declared in. A constructor of another
 * type can be no type's member, so the contract is put right when it is accepted and never
 * reaches a worker as a demand no code can meet.
 */
class ContractNestedTypesTest {

    private static final String OUTER = "com.demo.server.BookQueries";
    private static final List<String> AS_WRITTEN = List.of("public BookSearchRequest request;",
        "public Search()", "public Search(BookSearchRequest request)",
        "List<Book> execute(Object root)");

    @TempDir
    Path tree;

    @Test
    void aTypeThatOnlyHoldsNestedTypesGetsTheNewOneAsAContractOfItsOwn() throws Exception {
        aClassOfQueries("");
        DesignDocument design = design(contract(OUTER, AS_WRITTEN));

        ContractNestedTypes.Outcome outcome = ContractNestedTypes.check(design, start());

        assertThat(outcome.objections()).isEmpty();
        assertThat(outcome.contracts()).hasSize(2);
        assertThat(outcome.contracts().get(0).id()).isEqualTo(design.contracts().get(0).id());
        assertThat(outcome.contracts().get(0).typeName()).isEqualTo(OUTER);
        assertThat(outcome.contracts().get(0).members()).isEmpty();
        assertThat(outcome.contracts().get(1).typeName()).isEqualTo(OUTER + ".Search");
        assertThat(outcome.contracts().get(1).members()).containsExactlyElementsOf(AS_WRITTEN);
        assertThat(outcome.notes()).hasSize(1);
        assertThat(outcome.notes().get(0)).contains("`public Search()`").contains("`ListAll`")
            .contains(OUTER + ".Search{");

        DesignDocument corrected = outcome.applyTo(design);
        assertThat(corrected.id()).isEqualTo(design.id());
        // put right once: the corrected design is left as it is, with the same contract id
        ContractNestedTypes.Outcome again = ContractNestedTypes.check(corrected, start());
        assertThat(again.changed()).isFalse();
        assertThat(again.objections()).isEmpty();
        assertThat(again.applyTo(corrected)).isSameAs(corrected);
        assertThat(ContractNestedTypes.check(design, start()).contracts().get(1).id())
            .isEqualTo(outcome.contracts().get(1).id());
    }

    @Test
    void theCorrectedContractsAreMetByWhatACandidateWrites() throws Exception {
        aClassOfQueries("");
        List<ApiContract> corrected = ContractNestedTypes.check(
            design(contract(OUTER, AS_WRITTEN)), start()).contracts();
        aClassOfQueries("""
                public static final class Search implements DbQuery<List<Book>> {
                    public final BookSearchRequest request;
                    public Search() {
                        this.request = new BookSearchRequest();
                    }
                    public Search(BookSearchRequest request) {
                        this.request = request;
                    }
                    public List<Book> execute(Object root) {
                        return List.of();
                    }
                }
            """);

        assertThat(ContractDelivery.shortfalls(tree, corrected)).isEmpty();
    }

    @Test
    void membersTheOuterTypeAlreadyDeclaresStayWithIt() throws Exception {
        source("com/demo/server/BookQueries.java", """
            package com.demo.server;
            public class BookQueries {
                public static int count() {
                    return 0;
                }
                public static final class Search {
                    public String text;
                    public Search(String text) {
                        this.text = text;
                    }
                }
            }
            """);

        ContractNestedTypes.Outcome outcome = ContractNestedTypes.check(design(contract(OUTER,
            List.of("static int count()", "public Search(String text)", "public String text;",
                "BookQueries()"))), start());

        assertThat(outcome.objections()).isEmpty();
        assertThat(outcome.contracts().get(0).members())
            .containsExactly("static int count()", "BookQueries()");
        assertThat(outcome.contracts().get(1).typeName()).isEqualTo(OUTER + ".Search");
        assertThat(outcome.contracts().get(1).members())
            .containsExactly("public Search(String text)", "public String text;");
        assertThat(outcome.notes().get(0)).contains("already declares the nested type `Search`");
    }

    @Test
    void theNestedTypesOwnContractTakesItsConstructors() {
        ApiContract nested = contract(OUTER + ".Search", List.of("public String text;"));
        DesignDocument design = design(contract(OUTER,
            List.of("public Search(String text)", "public String text")), nested);

        ContractNestedTypes.Outcome outcome = ContractNestedTypes.check(design, null);

        assertThat(outcome.objections()).isEmpty();
        assertThat(outcome.contracts()).hasSize(2);
        assertThat(outcome.contracts().get(0).members()).isEmpty();
        assertThat(outcome.contracts().get(1).id()).isEqualTo(nested.id());
        assertThat(outcome.contracts().get(1).members())
            .containsExactly("public String text;", "public Search(String text)");
    }

    @Test
    void aTypeWithMembersOfItsOwnGoesBackToTheArchitect() throws Exception {
        source("com/demo/server/BookQueries.java", """
            package com.demo.server;
            public class BookQueries {
                public int limit;
                public static final class ListAll {
                }
            }
            """);
        DesignDocument design = design(contract(OUTER, AS_WRITTEN));

        ContractNestedTypes.Outcome outcome = ContractNestedTypes.check(design, start());

        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.applyTo(design)).isSameAs(design);
        assertThat(outcome.objections()).hasSize(1);
        assertThat(outcome.objections().get(0)).contains("`public Search()`")
            .contains("cannot be a member of `BookQueries`").contains(OUTER + ".Search")
            .contains("`public BookSearchRequest request;`");
    }

    @Test
    void aNameNothingPlacesGoesBackToTheArchitect() throws Exception {
        // a new type, and a type with no nested sibling: nothing says Search is declared inside
        assertThat(ContractNestedTypes.check(design(contract(OUTER, AS_WRITTEN)), start())
            .objections()).hasSize(1);
        source("com/demo/server/BookQueries.java", """
            package com.demo.server;
            public class BookQueries {
            }
            """);
        assertThat(ContractNestedTypes.check(design(contract(OUTER, AS_WRITTEN)), start())
            .objections()).hasSize(1);
    }

    @Test
    void aNameAnotherTypeOfTheProjectHasGoesBackToTheArchitect() throws Exception {
        aClassOfQueries("");
        source("com/demo/shared/Search.java", """
            package com.demo.shared;
            public class Search {
            }
            """);

        ContractNestedTypes.Outcome outcome =
            ContractNestedTypes.check(design(contract(OUTER, AS_WRITTEN)), start());

        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.objections()).hasSize(1);
        assertThat(outcome.objections().get(0)).contains("where `Search` is declared");
    }

    @Test
    void aContractWithNoConstructorOfAnotherTypeIsLeftAlone() throws Exception {
        aClassOfQueries("");
        DesignDocument design = design(contract(OUTER, List.of("BookQueries()",
            "List<Book> all()", "save(Book book)")), contract("com.demo.shared.Status",
            List.of("ACTIVE", "DONE()")));

        ContractNestedTypes.Outcome outcome = ContractNestedTypes.check(design, start());

        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.objections()).isEmpty();
        assertThat(outcome.contracts()).containsExactlyElementsOf(design.contracts());
    }

    private ProjectTypes start() {
        return ProjectTypes.of(tree);
    }

    private static ApiContract contract(String typeName, List<String> members) {
        return new ApiContract(UUID.randomUUID(),
            typeName.substring(typeName.lastIndexOf('.') + 1), "", "", typeName, members);
    }

    private static DesignDocument design(ApiContract... contracts) {
        return new DesignDocument(UUID.randomUUID(), 1, "goal", List.of(), List.of(),
            List.of(contracts), List.of(), null, Instant.now());
    }

    /** A class that declares nested query classes and nothing else, plus {@code added}. */
    private void aClassOfQueries(String added) throws Exception {
        source("com/demo/server/BookQueries.java", """
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

    private void source(String relative, String text) throws Exception {
        Path file = tree.resolve("src/main/java").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
