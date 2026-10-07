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

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The library-docs index behaves the same way {@code HistoryRag} always has on a store nothing has
 * ever been written to: "not found", quietly, not an exception with a Lucene stack trace attached.
 *
 * <p>Before this class existed, every worker's docs lookup threw {@code IndexNotFoundException}
 * through the generic {@code catch (Exception e)} in {@code DocsIndex.lookupApi} and logged it with
 * its full stack trace — including the entire Koog/reflection call chain — on every single call,
 * for as long as the docs feature stays switched off (no {@code CONTEXT7_API_KEY}). See
 * DEVELOPER_CORRECTIONS.md for the incident log this is written against.
 */
class DocsIndexTest {

    @TempDir
    Path indexDir;

    @Test
    void lookupOnAnUnwrittenIndexIsEmptyNotAnException() {
        DocsIndex docs = assertDoesNotThrowOnConstruct();

        assertThatCode(() -> docs.lookupApi("some-library", "SomeClass"))
            .doesNotThrowAnyException();
        assertThat(docs.lookupApi("some-library", "SomeClass")).isEmpty();
    }

    @Test
    void indexedContentIsFoundAfterwards() throws Exception {
        DocsIndex docs = new DocsIndex(indexDir);
        docs.indexDocs("zeroz4j", "class BinarySerializer { static void writeValue(...) }");

        Optional<String> found = docs.lookupApi("zeroz4j", "BinarySerializer writeValue");

        assertThat(found).isPresent();
        assertThat(found.get()).contains("BinarySerializer");
    }

    private DocsIndex assertDoesNotThrowOnConstruct() {
        try {
            return new DocsIndex(indexDir);
        } catch (Exception e) {
            throw new AssertionError("DocsIndex construction should not fail on an empty directory", e);
        }
    }
}
