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
 * Live run 85 (2026-10-04): the test author asked where the framework's store producer is used -
 * the one question that leads to the test listing it as a bean - and was told "nothing in this
 * project's material". The only use was {@code Producer.class} in a test of the producer's own
 * package: no import, no {@code new}, no annotation, the three things a use was read from. It
 * then listed folders and read 46 files whole. A type named in code is a use.
 */
class ATypeNamedWithoutAnImportIsAUsageTest {

    @TempDir
    Path world;

    @Test
    void aClassLiteralASupertypeAndAFieldTypeInTheSamePackageAreFound() throws Exception {
        Path framework = world.resolve("framework");
        write(framework.resolve("pom.xml"), "<project><groupId>f</groupId>"
            + "<artifactId>store</artifactId><version>1</version></project>");
        write(framework.resolve("src/main/java/com/f/store/StoreProducer.java"), """
            package com.f.store;

            public class StoreProducer {
                public String node() { return "node"; }
            }
            """);
        write(framework.resolve("src/main/java/com/f/store/StorageProvider.java"), """
            package com.f.store;

            public class StorageProvider {
                StorageProvider self;
            }
            """);
        write(framework.resolve("src/main/java/com/f/store/Container.java"), """
            package com.f.store;

            public class Container {
                public static Container from(Class<?>... beans) { return new Container(); }
            }
            """);
        write(framework.resolve("src/test/java/com/f/store/NodeInjectionTest.java"), """
            package com.f.store;

            class NodeInjectionTest {

                static class StubProvider extends StorageProvider {
                }

                private StoreProducer held;

                void starts() {
                    Container.from(StoreProducer.class, StubProvider.class);
                }
            }
            """);
        write(framework.resolve("src/main/java/com/f/web/Importer.java"), """
            package com.f.web;

            import com.f.store.StoreProducer;

            public class Importer {
                StoreProducer producer = new StoreProducer();
            }
            """);

        SemanticIndex index = SemanticIndex.over(
            List.of(new KnowledgeCurator.Root("framework", framework, "1")),
            world.resolve("cache"));

        List<SemanticIndex.Ref> producer = index.usagesOf("StoreProducer");
        assertThat(producer).extracting(SemanticIndex.Ref::file)
            .as("the test that only names it, and the file that imports it")
            .anyMatch(f -> f.endsWith("NodeInjectionTest.java"))
            .anyMatch(f -> f.endsWith("Importer.java"))
            .as("a type's own file is not a user of it")
            .noneMatch(f -> f.endsWith("StoreProducer.java"));
        assertThat(producer).filteredOn(r -> r.file().endsWith("NodeInjectionTest.java"))
            .as("once, on the first line that names it").hasSize(1)
            .first().satisfies(ref -> {
                assertThat(ref.line()).isEqualTo(8);
                assertThat(ref.detail()).startsWith("names ");
            });
        assertThat(producer).filteredOn(r -> r.file().endsWith("Importer.java"))
            .as("an import and a new say it already; naming adds nothing to them")
            .extracting(SemanticIndex.Ref::detail).noneMatch(d -> d.startsWith("names "));
        assertThat(index.usagesOf("StorageProvider")).extracting(SemanticIndex.Ref::file)
            .as("a supertype is a use; a field of its own type in its own file is not")
            .anyMatch(f -> f.endsWith("NodeInjectionTest.java"))
            .noneMatch(f -> f.endsWith("StorageProvider.java"));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
