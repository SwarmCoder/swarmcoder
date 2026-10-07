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

import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.runtime.ApiLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 82, 2026-10-04: the workers made 11 tree queries and got 781 characters back - 71
 * each, the sentence {@link ApiLookup#tree} answers when nothing is wired. The harness had given
 * its workers {@code librarian::lookupApi}, a method reference that leaves the tree, the
 * documents and the language server at their defaults; only the product wired them. Both now
 * build a {@link WorkerLookups}.
 */
class WorkerLookupsTest {

    @TempDir
    Path world;

    @Test
    void aWorkersTreeAndDocumentQueriesAreAnsweredAndNotLeftAtTheirDefault() throws Exception {
        Path app = world.resolve("app");
        write(app.resolve("pom.xml"), "<project><groupId>com.shop</groupId>"
            + "<artifactId>shop</artifactId><version>1</version></project>");
        write(app.resolve("src/main/java/com/shop/Basket.java"), """
            package com.shop;

            public class Basket {
                public int size() {
                    return 0;
                }
            }
            """);
        write(app.resolve("docs/architecture.md"),
            "# Architecture\n\n## Baskets\n\nA basket holds items.\n\n## Checkout\n\nPays.\n");
        Librarian librarian = new Librarian(null, null, List.of(), app, null,
            world.resolve("primers"));
        String unwired = ApiLookup.UNAVAILABLE.tree("shape_of", "Basket");

        ApiLookup lookups = new WorkerLookups(librarian, app, false, null);

        assertThat(lookups.tree("shape_of", "com.shop.Basket"))
            .isNotEqualTo(unwired).contains("size()");
        assertThat(lookups.tree("body_of", "com.shop.Basket#size")).contains("return 0;")
            .as("addressed as a worker's read takes it").contains("src/main/java/com/shop/Basket.java");
        assertThat(lookups.tree("types_in", "com.shop")).contains("Basket");
        assertThat(lookups.tree("doc_outline", "")).contains("docs/architecture.md", "Baskets");
        assertThat(lookups.tree("doc_section", "docs/architecture.md#Baskets"))
            .contains("A basket holds items.").doesNotContain("Pays.");
        assertThat(lookups.languageServer()).isFalse();
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
