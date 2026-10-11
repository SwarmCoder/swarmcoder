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
import com.swarmcoder.knowledge.AnnotationTargets;
import com.swarmcoder.knowledge.ContractMember;
import com.swarmcoder.knowledge.LibraryTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live supervised build, 2026-10-11: a design's contract wrote {@code @DataModel Book()}. The
 * annotation can only be written on a type, so the contract is put right when it is accepted and
 * never reaches a worker as a demand no code can meet.
 */
class ContractAnnotationPlacesTest {

    @TempDir
    Path reference;

    @BeforeEach
    void theLibrarySaysWhereItsAnnotationsGo() throws Exception {
        source("com/acme/model/DataModel.java", """
            package com.acme.model;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE)
            public @interface DataModel {
            }
            """);
        source("com/acme/model/NotBlank.java", """
            package com.acme.model;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface NotBlank {
            }
            """);
        source("com/acme/model/OnAParameter.java", """
            package com.acme.model;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.LOCAL_VARIABLE)
            public @interface OnAParameter {
            }
            """);
    }

    @Test
    void anAnnotationForATypeWrittenOnAConstructorBecomesTheTypesAnnotation() {
        ContractAnnotationPlaces.Outcome outcome = check("@com.acme.model.DataModel Book()",
            "Book(String title)", "public String title;");

        assertThat(outcome.objections()).isEmpty();
        assertThat(outcome.notes()).hasSize(1);
        assertThat(outcome.notes().get(0)).contains("com.demo.shared.Book")
            .contains("@com.acme.model.DataModel Book()").contains("its own @Target");
        List<String> members = outcome.contracts().get(0).members();
        assertThat(members).containsExactly("@com.acme.model.DataModel Book", "Book()",
            "Book(String title)", "public String title;");
        // and that first entry is what every check reads as a statement about the type
        assertThat(ContractMember.typeAnnotations(members.get(0), "Book"))
            .containsExactly("com.acme.model.DataModel");
    }

    @Test
    void theCorrectedDesignKeepsEverythingElse() {
        DesignDocument design = design("@DataModel Book()", "public String title;");

        DesignDocument corrected = ContractAnnotationPlaces.check(design, targets()).applyTo(design);

        assertThat(corrected).isNotSameAs(design);
        assertThat(corrected.id()).isEqualTo(design.id());
        assertThat(corrected.contracts().get(0).id()).isEqualTo(design.contracts().get(0).id());
        assertThat(corrected.contracts().get(0).members())
            .containsExactly("@DataModel Book", "Book()", "public String title;");
    }

    @Test
    void anAnnotationTheMemberCanCarryStaysOnTheMember() {
        DesignDocument design = design("@DataModel Book", "@NotBlank public String title;",
            "@com.elsewhere.Unread Book()", "void rename(@NotBlank String title)");

        ContractAnnotationPlaces.Outcome outcome = ContractAnnotationPlaces.check(design, targets());

        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.objections()).isEmpty();
        assertThat(outcome.applyTo(design)).isSameAs(design);
    }

    @Test
    void anAnnotationThatCanBeOnNeitherGoesBackToTheArchitect() {
        ContractAnnotationPlaces.Outcome outcome = check("@OnAParameter Book()");

        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.objections()).hasSize(1);
        assertThat(outcome.objections().get(0)).contains("@OnAParameter").contains("Book()")
            .contains("LOCAL_VARIABLE").contains("no task can deliver it");
    }

    private ContractAnnotationPlaces.Outcome check(String... members) {
        return ContractAnnotationPlaces.check(design(members), targets());
    }

    private AnnotationTargets targets() {
        return AnnotationTargets.of(null, LibraryTypes.of(List.of(reference)));
    }

    private static DesignDocument design(String... members) {
        ApiContract contract = new ApiContract(UUID.randomUUID(), "Book", "a book", "",
            "com.demo.shared.Book", List.of(members));
        return new DesignDocument(UUID.randomUUID(), 1, "goal", List.of(), List.of(),
            List.of(contract), List.of(), null, Instant.now());
    }

    private void source(String relative, String text) throws Exception {
        Path file = reference.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
