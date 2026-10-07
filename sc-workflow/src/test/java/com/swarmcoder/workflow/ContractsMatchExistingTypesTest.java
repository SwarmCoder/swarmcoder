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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Harness run 78: a design stated {@code @DataModel} on a class that carries {@code @Vetoed}. */
class ContractsMatchExistingTypesTest {

    @TempDir
    Path repo;

    private DesignDocument design(String... members) throws Exception {
        Path file = repo.resolve("src/main/java/com/x/Root.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package com.x;\n\n@Vetoed\npublic class Root {\n    int version;\n}\n");
        ApiContract c = new ApiContract(UUID.randomUUID(), "Root", "d", "Root", "com.x.Root",
            List.of(members));
        return new DesignDocument(UUID.randomUUID(), 1, "g", List.of(), new ArrayList<>(),
            List.of(c), List.of(), null, Instant.now());
    }

    @Test
    void aFalseAnnotationOnAnExistingTypeIsAnObjectionQuotingTheRealDeclaration() throws Exception {
        List<String> objections = ContractsMatchExistingTypes.objections(
            design("@DataModel Root;", "int getVersion()"), repo);
        assertThat(objections).hasSize(1);
        assertThat(objections.get(0)).contains("src/main/java/com/x/Root.java:4")
            .contains("public class Root").contains("@Vetoed").contains("@DataModel")
            .contains("no mark");
    }

    @Test
    void membersAnExistingTypeGainsAreNotAnObjection() throws Exception {
        assertThat(ContractsMatchExistingTypes.objections(design("int getVersion()"), repo)).isEmpty();
    }
}
