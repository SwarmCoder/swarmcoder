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

import static org.assertj.core.api.Assertions.assertThat;

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.knowledge.LibraryTypes;
import com.swarmcoder.verify.AcceptanceCompileErrors.Reading;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whoever is asked to repair a test that misuses a library type is told that type's real members —
 * the builder it hands out included (live run 63, 2026-10-02).
 *
 * <p>At wave start the author was shown the overloads of the one method it had called wrongly,
 * switched to the builder, and invented a builder method. Mid-run it was shown nothing about the
 * library at all and answered, in prose, that it could not fix the test without the builder's API.
 */
class ARepairPromptListsTheLibraryTypesRealMembersTest {

    private static final String TEST_FILE = "app-server/src/test/java/swarm/accept/EditTest.java";

    @TempDir
    Path world;

    Path tree;
    LibraryTypes library;

    @BeforeEach
    void buildTheWorld() throws Exception {
        tree = world.resolve("tree");
        Path reference = world.resolve("harnessworks");
        write(reference.resolve("core/src/main/java/com/harness/Harness.java"), """
            package com.harness;

            public final class Harness implements AutoCloseable {
                public static Harness start(Class<?>... beans) { return null; }
                public static Builder builder() { return new Builder(); }
                public <T> T bean(Class<T> type) { return null; }
                public void close() { }
                public static final class Builder {
                    public Builder beans(Class<?>... types) { return this; }
                    public Builder set(String setting, String value) { return this; }
                    public Harness start() { return null; }
                    private void internal() { }
                }
            }
            """);
        // Another type of the same package nesting a Builder of its own: the wrong one to show.
        write(reference.resolve("core/src/main/java/com/harness/Wire.java"), """
            package com.harness;

            public final class Wire {
                public static final class Builder {
                    public Builder frameSize(int bytes) { return this; }
                }
            }
            """);
        library = LibraryTypes.of(List.of(reference));
        write(tree.resolve(TEST_FILE), """
            package swarm.accept;

            import com.harness.Harness;

            class EditTest {
                void edits() {
                    Harness h = Harness.start(String.class, java.nio.file.Path.of("x"));
                    Harness g = Harness.builder().storePath(java.nio.file.Path.of("x")).start();
                }
            }
            """);
    }

    @Test
    void aMemberMissingFromALibraryBuilderListsThatBuildersRealMembers() {
        Reading reading = MiscompiledAcceptanceTest.read(tree, """
            %s:8: error: cannot find symbol
              symbol:   method storePath(java.nio.file.Path)
              location: class com.harness.Harness.Builder
            """.formatted(TEST_FILE), List.of(TEST_FILE), List.of(task()));
        assertThat(reading.isBroken()).isTrue();

        String signatures = MiscompiledAcceptanceTest.signatures(tree, reading, List.of(TEST_FILE),
            library);

        assertThat(signatures)
            .contains("com.harness.Harness.Builder: public Builder beans(Class<?>... types)")
            .contains("com.harness.Harness.Builder: public Builder set(String setting, String value)")
            .contains("com.harness.Harness.Builder: public Harness start()")
            .as("the builder of Harness, not of another type in the package")
            .doesNotContain("frameSize")
            .doesNotContain("internal");
        assertThat(MiscompiledAcceptanceTest.reask(reading, signatures))
            .contains("public Builder beans(Class<?>... types)");
    }

    @Test
    void aMisusedLibraryMethodListsTheWholeTypeAndTheBuilderItHandsOut() {
        Reading reading = MiscompiledAcceptanceTest.read(tree, """
            %s:7: error: method start in class com.harness.Harness cannot be applied to given types;
              required: java.lang.Class<?>[]
              found:    java.lang.Class<java.lang.String>,java.nio.file.Path
              reason: varargs mismatch; java.nio.file.Path cannot be converted to java.lang.Class<?>
            """.formatted(TEST_FILE), List.of(TEST_FILE), List.of(task()));
        assertThat(reading.isBroken()).isTrue();

        String signatures = MiscompiledAcceptanceTest.signatures(tree, reading, List.of(TEST_FILE),
            library);

        assertThat(signatures)
            .contains("com.harness.Harness: public static Harness start(Class<?>... beans)")
            .contains("com.harness.Harness: public static Builder builder()")
            .as("the type the next call in a chain is made on")
            .contains("com.harness.Harness.Builder: public Builder beans(Class<?>... types)")
            .doesNotContain("frameSize");
    }

    @Test
    void aTypeWhoseMembersCannotBeReadIsSaidSo() {
        Reading reading = MiscompiledAcceptanceTest.read(tree, """
            %s:8: error: cannot find symbol
              symbol:   method storePath(java.nio.file.Path)
              location: class org.elsewhere.Opaque.Builder
            """.formatted(TEST_FILE), List.of(TEST_FILE), List.of(task()));
        assertThat(reading.isBroken()).isTrue();

        assertThat(MiscompiledAcceptanceTest.signatures(tree, reading, List.of(TEST_FILE), library))
            .contains("org.elsewhere.Opaque.Builder: its members could NOT be read");
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "EditServiceImpl", "do it",
            Set.of("app-server/src/main/java/com/acme/server/EditServiceImpl.java"), Set.of(),
            List.of(), "app-server/src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
