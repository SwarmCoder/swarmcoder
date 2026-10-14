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

import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 114, finding 4: four candidates of the client task read the same 489-line example
 * screen 28 times, about 1.08 million tokens carried, while their brief said no other example
 * was pasted and their findings were excerpts of that very file. The file is now named at the
 * top of the brief, with its size, and not pasted. No model.
 */
class ATaskThatMirrorsOneFileIsToldItsNameAndSizeTest {

    private static final String SCREEN = "client/src/main/java/app/BookScreen.java";

    @TempDir
    Path project;

    @TempDir
    Path references;

    private static DesignFinding excerpt(String source) {
        return new DesignFinding(UUID.randomUUID(), "BookList", source, "The example does this.",
            "    add(new Grid());\n    add(new Form());");
    }

    private Task task(List<DesignFinding> findings) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle("client screen");
        task.setInstructions("Write the book list screen.");
        task.setWriteSet(Set.of("client/src/main/java/app/BookList.java"));
        task.setArchitectFindings(findings);
        return task;
    }

    @Test
    void theFileTheFindingsAreExcerptsOfIsNamedOnceWithItsSize() throws Exception {
        Path example = references.resolve("example");
        Path screen = example.resolve(SCREEN);
        Files.createDirectories(screen.getParent());
        StringBuilder code = new StringBuilder("package app;\n\npublic class BookScreen {\n");
        for (int i = 3; i < 488; i++) {
            code.append("    // line ").append(i).append(" of the example screen\n");
        }
        code.append("}\n");
        Files.writeString(screen, code.toString());
        Files.writeString(example.resolve("client/src/main/java/app/Other.java"),
            "package app;\nclass Other {}\n");
        Librarian librarian = new Librarian(null, null, List.of(example), project, null,
            references.resolve("cache"));

        Task mirrors = task(List.of(
            excerpt("read_file example/" + SCREEN + ":120-160"),
            excerpt("body_of example/" + SCREEN + "#build"),
            excerpt("body_of example/client/src/main/java/app/Other.java#Other")));
        Optional<Librarian.MirroredFile> named = librarian.mirroredFile(mirrors);
        assertThat(named).isPresent();
        assertThat(named.get().address()).isEqualTo("/reference/example/" + SCREEN);
        assertThat(named.get().lines()).isEqualTo(489);
        assertThat(named.get().excerpts()).isEqualTo(2);

        String brief = librarian.assembleBrief(project, mirrors, null, null).renderedMarkdown();
        assertThat(brief).startsWith("### The file this task mirrors\n")
            .contains("read /reference/example/" + SCREEN + " - 489 lines, ")
            .doesNotContain("line 300 of the example screen");

        Task scattered = task(List.of(
            excerpt("body_of example/" + SCREEN + "#build"),
            excerpt("body_of example/client/src/main/java/app/Other.java#Other")));
        assertThat(librarian.mirroredFile(scattered)).as("one excerpt of a file is not a file "
            + "to mirror").isEmpty();
        assertThat(librarian.assembleBrief(project, scattered, null, null).renderedMarkdown())
            .doesNotContain("### The file this task mirrors");
    }
}
