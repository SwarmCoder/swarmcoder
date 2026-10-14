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
 * DEVELOPER_CORRECTIONS section 113, finding 4: a worker's {@code types_in} of a reference folder
 * by the address its own read takes, {@code /reference/<root>/<path>}, answered "No types".
 */
class AWorkersReferenceFolderIsAPlaceTypesInTakesTest {

    @TempDir
    Path world;

    @Test
    void typesInTakesAReferenceFolderAndFileAsAWorkerAddressesThem() throws Exception {
        Path app = world.resolve("app");
        Path lib = world.resolve("lib");
        Files.createDirectories(app);
        Path file = lib.resolve("examples/shop/src/main/java/com/example/shop/Catalogue.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package com.example.shop;

            public class Catalogue {
                public int size() {
                    return 0;
                }
            }
            """);
        KnowledgeCurator curator = new KnowledgeCurator(List.of(
            new KnowledgeCurator.Root("project", app, "local"),
            new KnowledgeCurator.Root("lib", lib, "local")), null, world.resolve("cache"));
        TreeQueries worker = TreeQueries.forAWorkerOf(curator, app);

        assertThat(worker.typesIn("/reference/lib/examples/shop/src/main/java/com/example"))
            .contains("Catalogue")
            .contains("/reference/lib/examples/shop/src/main/java/com/example/shop/Catalogue.java");
        assertThat(worker.typesIn("/reference/lib/examples/shop/"))
            .as("a folder with a closing slash").contains("Catalogue");
        assertThat(worker.typesIn(
                "/reference/lib/examples/shop/src/main/java/com/example/shop/Catalogue.java"))
            .as("a file is the place its types are in").contains("Catalogue");
        assertThat(worker.typesIn("com.example.shop")).as("a package, as before")
            .contains("Catalogue");
    }
}
