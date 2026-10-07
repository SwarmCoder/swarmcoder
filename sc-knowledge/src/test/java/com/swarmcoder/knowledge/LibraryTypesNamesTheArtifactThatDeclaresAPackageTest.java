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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The red check tells the task that owns a build file which dependency its acceptance test needs:
 * the Maven coordinate of the reference module whose source declares the package (live run 64).
 */
class LibraryTypesNamesTheArtifactThatDeclaresAPackageTest {

    @TempDir
    Path reference;

    @Test
    void theNearestPomAboveTheDeclaringFileNamesIt() throws Exception {
        write("store-module/pom.xml", """
            <project>
              <parent><groupId>org.acme</groupId><artifactId>acme-parent</artifactId></parent>
              <artifactId>acme-store</artifactId>
              <dependencies><dependency><groupId>x</groupId><artifactId>other</artifactId></dependency></dependencies>
            </project>
            """);
        write("store-module/src/main/java/org/acme/db/net/DbNode.java",
            "package org.acme.db.net;\n\npublic class DbNode {}\n");
        write("other-module/pom.xml",
            "<project><groupId>org.other</groupId><artifactId>other-module</artifactId></project>\n");
        write("other-module/src/main/java/org/other/Thing.java",
            "package org.other;\n\npublic class Thing {}\n");
        LibraryTypes library = LibraryTypes.of(List.of(reference));

        assertThat(library.declaringArtifact("org.acme.db.net", List.of("DbNode")))
            .contains("org.acme:acme-store");
        assertThat(library.declaringArtifact("org.acme.db.net", List.of()))
            .as("any type of the package will do when the import names none")
            .contains("org.acme:acme-store");
        assertThat(library.declaringArtifact("org.other", List.of("Thing")))
            .contains("org.other:other-module");
    }

    @Test
    void aPackageTheReferenceDoesNotHoldHasNoArtifact() throws Exception {
        write("m/pom.xml", "<project><artifactId>m</artifactId></project>\n");
        write("m/src/main/java/a/b/C.java", "package a.b;\n\npublic class C {}\n");
        LibraryTypes library = LibraryTypes.of(List.of(reference));

        assertThat(library.declaringArtifact("x.y", List.of("Z"))).isEmpty();
        assertThat(LibraryTypes.NONE.declaringArtifact("a.b", List.of("C"))).isEmpty();
    }

    private void write(String relative, String body) throws Exception {
        Path file = reference.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
