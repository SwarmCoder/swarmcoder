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
package com.swarmcoder.app;

import com.swarmcoder.verify.BuildLayout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write-set shapes {@link WriteSetLinkageCheck} must tell apart. Written after link 8 of
 * {@code EndToEndLoopTest} (L_WRITESETS) rejected a plan on 2026-09-03 for writing a module's
 * build file alongside that module's own source root — exactly what {@code BuildFilesInTheJob}
 * (merged {@code 5e01c3d}) now hands every task, so a worker can declare a dependency itself.
 *
 * <p>Harness run 10, the same day, showed that fix was still too narrow: a pre-flight enabler
 * task whose write set is exactly one pom — no sibling source entry, because declaring a
 * dependency is the whole task — was rejected too. {@link #aPomAloneForACompiledModuleIsAccepted}
 * and {@link #aPomOfAModuleTheBuildDoesNotKnowIsRejected} cover the corrected rule.
 */
class WriteSetLinkageCheckTest {

    private final BuildLayout.Layout layout = new BuildLayout.Layout("maven",
        List.of("bookshelf-demo-shared/src/main/java", "bookshelf-demo-server/src/main/java"),
        List.of("bookshelf-demo-shared", "bookshelf-demo-server"), List.of(), null);

    @Test
    void aSourceRootPlusThatModulesPomIsAccepted() {
        Set<String> writeSet = Set.of(
            "bookshelf-demo-shared/src/main/java", "bookshelf-demo-shared/pom.xml");

        assertThat(WriteSetLinkageCheck.orphans(writeSet, layout)).isEmpty();
    }

    @Test
    void aPomAloneForACompiledModuleIsAccepted() {
        Set<String> writeSet = Set.of("bookshelf-demo-server/pom.xml");

        assertThat(WriteSetLinkageCheck.orphans(writeSet, layout)).isEmpty();
    }

    @Test
    void aRandomFileOutsideAnyCompiledRootIsRejected() {
        Set<String> writeSet = Set.of(
            "bookshelf-demo-shared/src/main/java", "bookshelf-demo-shared/README.md");

        assertThat(WriteSetLinkageCheck.orphans(writeSet, layout))
            .containsExactly("bookshelf-demo-shared/README.md");
    }

    @Test
    void aPomOfAModuleTheBuildDoesNotKnowIsRejected() {
        Set<String> writeSet = Set.of("bookshelf-demo-other/pom.xml");

        assertThat(WriteSetLinkageCheck.orphans(writeSet, layout))
            .containsExactly("bookshelf-demo-other/pom.xml");
    }
}
