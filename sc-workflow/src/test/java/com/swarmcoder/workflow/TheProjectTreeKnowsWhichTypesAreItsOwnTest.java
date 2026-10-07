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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 57, 2026-10-01: a missing member of a type that lives in a jar can never be added by any
 * task. {@link MiscompiledAcceptanceTest#sourceInTree} is how the classifier tells such a type from
 * one of the project's own.
 */
class TheProjectTreeKnowsWhichTypesAreItsOwnTest {

    @TempDir
    Path tree;

    @Test
    void aTypeWithASourceFileInTheTreeIsTheProjectsAndAJarTypeIsNot() throws Exception {
        write("server/src/main/java/com/acme/store/Store.java", "package com.acme.store; class Store {}");
        write("server/src/main/java/com/acme/store/Store.java.orig", "x");

        Predicate<String> own = MiscompiledAcceptanceTest.sourceInTree(tree);

        assertThat(own.test("com.acme.store.Store")).isTrue();
        assertThat(own.test("com.vendor.testkit.TestServer")).as("only a jar holds it").isFalse();
        assertThat(own.test("TestServer")).isFalse();
    }

    @Test
    void aKotlinTypeIsFoundByItsPackageBecauseItsFileMayBeNamedAnything() throws Exception {
        write("app/src/main/kotlin/com/acme/model/Models.kt", "package com.acme.model\nclass Order");

        Predicate<String> own = MiscompiledAcceptanceTest.sourceInTree(tree);

        assertThat(own.test("com.acme.model.Order")).isTrue();
        assertThat(own.test("com.vendor.Thing")).isFalse();
    }

    @Test
    void noTreeMeansNothingIsKnown() {
        assertThat(MiscompiledAcceptanceTest.sourceInTree(null)).isNull();
        assertThat(MiscompiledAcceptanceTest.sourceInTree(tree.resolve("missing"))).isNull();
    }

    private void write(String relative, String content) throws Exception {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
