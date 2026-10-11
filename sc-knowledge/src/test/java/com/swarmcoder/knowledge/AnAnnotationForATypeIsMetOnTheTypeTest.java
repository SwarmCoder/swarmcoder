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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live supervised build, 2026-10-11: a contract wrote {@code @DataModel Book()}, an annotation that
 * can only be written on a type, in front of the no-argument constructor. Four candidates put it
 * on the class and were failed for a constructor that "does not carry the annotation".
 */
class AnAnnotationForATypeIsMetOnTheTypeTest {

    /** The contract as the live build's design wrote it, fields with their semicolons included. */
    private static final List<String> AS_WRITTEN = List.of("@com.acme.model.DataModel Book()",
        "Book(String title, String author, int year)", "public String title;",
        "public String author;", "public int year;");

    @TempDir
    Path tree;
    @TempDir
    Path reference;

    @BeforeEach
    void theLibrarySaysWhereItsAnnotationsGo() throws Exception {
        source(reference, "com/acme/model/DataModel.java", """
            package com.acme.model;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            /** Marks a class the framework stores. */
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface DataModel {
            }
            """);
        source(reference, "com/acme/model/NotBlank.java", """
            package com.acme.model;
            import java.lang.annotation.Target;
            import static java.lang.annotation.ElementType.FIELD;
            import static java.lang.annotation.ElementType.METHOD;
            @Target({FIELD, METHOD})
            public @interface NotBlank {
            }
            """);
    }

    @Test
    void theAnnotationTypesOwnTargetIsRead() {
        AnnotationTargets targets = AnnotationTargets.of(null, library());

        assertThat(targets.targetsOf("com.acme.model.DataModel")).contains(Set.of("TYPE"));
        assertThat(targets.targetsOf("NotBlank")).contains(Set.of("FIELD", "METHOD"));
        assertThat(targets.targetsOf("java.lang.FunctionalInterface")).contains(Set.of("TYPE"));
        assertThat(targets.targetsOf("com.elsewhere.Unread")).isEmpty();
        ContractMember constructor = ContractMember.parse("Book()").orElseThrow();
        assertThat(targets.placeOf("DataModel", constructor, "Book"))
            .isEqualTo(AnnotationTargets.Place.ON_THE_TYPE);
        assertThat(targets.placeOf("com.elsewhere.Unread", constructor, "Book"))
            .isEqualTo(AnnotationTargets.Place.UNKNOWN);
    }

    @Test
    void theClassCarryingItDeliversTheContract() throws Exception {
        book("@DataModel", "public String title;");

        assertThat(shortfalls(AS_WRITTEN)).isEmpty();
    }

    @Test
    void aClassWithoutItFailsOnTheTypeNotTheConstructor() throws Exception {
        book("", "public String title;");

        List<ContractDelivery.Shortfall> found = shortfalls(AS_WRITTEN);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).render())
            .contains("the type does not carry @com.acme.model.DataModel")
            .doesNotContain("is declared but does not carry");
    }

    @Test
    void anAnnotationTheMemberCanCarryIsStillDemandedOnTheMember() throws Exception {
        book("@DataModel @NotBlank", "public String title;");

        List<ContractDelivery.Shortfall> found = shortfalls(List.of(
            "@com.acme.model.DataModel Book()", "@com.acme.model.NotBlank public String title;"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).render())
            .contains("is declared but does not carry the annotation @com.acme.model.NotBlank");
    }

    @Test
    void anAnnotationNothingCanReadIsDemandedAsWritten() throws Exception {
        book("@Unread", "public String title;");

        List<ContractDelivery.Shortfall> found =
            shortfalls(List.of("@com.elsewhere.Unread Book()"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).render())
            .contains("is declared but does not carry the annotation @com.elsewhere.Unread");
    }

    private void book(String annotations, String titleField) throws Exception {
        source(tree, "src/main/java/com/demo/shared/Book.java", """
            package com.demo.shared;
            import com.acme.model.DataModel;
            import com.acme.model.NotBlank;
            %s
            public class Book {
                %s
                public String author;
                public int year;
                public Book() {}
                public Book(String title, String author, int year) {}
            }
            """.formatted(annotations, titleField));
    }

    private LibraryTypes library() {
        return LibraryTypes.of(List.of(reference));
    }

    private List<ContractDelivery.Shortfall> shortfalls(List<String> members) {
        ApiContract contract = new ApiContract(UUID.randomUUID(), "Book", "a book", "",
            "com.demo.shared.Book", members);
        return ContractDelivery.shortfalls(tree, List.of(contract), this::library);
    }

    private static void source(Path root, String relative, String text) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
