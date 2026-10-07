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

import com.swarmcoder.domain.KnowledgeBrief;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.swarmcoder.domain.KnowledgeDoc;
import java.time.Instant;

/**
 * Librarian v2: manifest coordinates + curated conventions + task-relevant FULL sources
 * (no tree-sitter signature dumps — author decision 2026-07-14).
 */
class LibrarianTest {

    @TempDir
    Path repo;
    @TempDir
    Path contextDir;
    @TempDir
    Path primerCache;

    private static Task task(String title, String instructions) {
        return new Task(UUID.randomUUID(), 1, title, instructions, Set.of("src/main"), Set.of("src/main"),
            List.of(), null, null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    private Librarian librarian(List<Path> contexts) {
        return new Librarian(new Context7Client("http://localhost:1/sse"), null,
            contexts, repo, null, primerCache);
    }

    @Test
    void briefCarriesLibrariesConventionsAndRelevantSources() throws Exception {
        Files.writeString(repo.resolve("pom.xml"), """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.10.2</version>
                </dependency>
              </dependencies>
            </project>
            """);
        // A source that is relevant to the task by name and content.
        Files.createDirectories(repo.resolve("src/main/java/com/example"));
        Files.writeString(repo.resolve("src/main/java/com/example/Calculator.java"), """
            package com.example;
            public class Calculator {
                public int add(int a, int b) { return a + b; }
                public int divide(int a, int b) { return a / b; }
            }
            """);
        // Curated conventions: store-first KnowledgeDoc objects, injected via the supplier.
        var rule = new KnowledgeDoc(UUID.randomUUID(), null,
            "di", "DI", "Always use constructor injection. Never call new inside components.",
            "ACTIVE", "human", Instant.now(), Instant.now());
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(), repo, null, primerCache, () -> List.of(rule));

        KnowledgeBrief brief = librarian.assembleBrief(repo,
            task("extend the calculator", "add multiply to Calculator"));

        assertThat(brief.libraries()).extracting(lib -> lib.coordinate())
            .contains("org.junit.jupiter:junit-jupiter");
        assertThat(brief.renderedMarkdown())
            .contains("junit-jupiter 5.10.2")
            .contains("constructor injection")               // curated conventions
            .contains("Calculator.java")                     // relevant source selected
            .contains("public int divide(int a, int b)");    // FULL source, not a signature list
        assertThat(brief.renderedMarkdown().length()).isLessThan(20_000);
    }

    @Test
    void contextFolderSourcesArePreemptedForRelevantTasks() throws Exception {
        Files.createDirectories(contextDir.resolve("com/zeroz4j/ui"));
        Files.writeString(contextDir.resolve("com/zeroz4j/ui/FileInput.java"), """
            package com.zeroz4j.ui;
            public class FileInput {
                public void setAccept(String accept) {}
                public String getValue() { return ""; }
            }
            """);
        Files.writeString(contextDir.resolve("README.md"),
            "# zeroz4j\nComponents are Java classes over DaisyUI.");

        Librarian librarian = librarian(List.of(contextDir));
        KnowledgeBrief brief = librarian.assembleBrief(repo,
            task("folder picker GUI", "use a FileInput component for folder selection"));

        // The worker sees the REAL FileInput source, not a signature line.
        assertThat(brief.renderedMarkdown())
            .contains("FileInput.java")
            .contains("public void setAccept(String accept)")
            .contains("read-only");
        // The analyst grounding carries the README conventions + browsable inventory.
        String analyst = librarian.contextApiReference(9_000);
        assertThat(analyst).contains("Components are Java classes over DaisyUI")
            .contains("FileInput.java");
    }

    @Test
    void curatorToolsListReadAndSearchWithinRootsOnly() throws Exception {
        Files.createDirectories(contextDir.resolve("com/zeroz4j/ui"));
        Files.writeString(contextDir.resolve("com/zeroz4j/ui/Button.java"), """
            package com.zeroz4j.ui;
            public class Button { public void addClickListener(Runnable r) {} }
            """);
        KnowledgeCurator curator = librarian(List.of(contextDir)).curator();
        String label = contextDir.getFileName().toString();

        assertThat(curator.listFolder("")).contains("project").contains(label);
        assertThat(curator.listFolder(label + "/com/zeroz4j/ui")).contains("Button.java");
        assertThat(curator.readFile(label + "/com/zeroz4j/ui/Button.java", 4000))
            .contains("addClickListener");
        assertThat(curator.searchCode("addClickListener", 10)).contains("Button.java:2");
        // Confinement: traversal and unknown roots refuse.
        assertThat(curator.readFile(label + "/../outside.txt", 100)).startsWith("error:");
        assertThat(curator.readFile("nosuchroot/x.java", 100)).startsWith("error:");
    }

    @Test
    void activeKnowledgeDocsFeedBriefsAndAnalystButProposedOnesDoNot() throws Exception {
        var active = new KnowledgeDoc(UUID.randomUUID(), null,
            "di-rule", "DI rule", "Constructor injection only — never new inside components.",
            "ACTIVE", "human", Instant.now(), Instant.now());
        var proposed = new KnowledgeDoc(UUID.randomUUID(), null,
            "draft", "Draft", "Unreviewed material.", "PROPOSED", "extraction",
            Instant.now(), Instant.now());
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(), repo, null, primerCache, () -> List.of(active, proposed));

        KnowledgeBrief brief = librarian.assembleBrief(repo, task("t", "i"));
        assertThat(brief.renderedMarkdown())
            .contains("Constructor injection only")
            .doesNotContain("Unreviewed material");
        assertThat(librarian.contextApiReference(9_000))
            .contains("Constructor injection only")
            .doesNotContain("Unreviewed material");
    }

    @Test
    void lookupApiFallsBackToCuratorSources() throws Exception {
        Files.createDirectories(contextDir.resolve("com/zeroz4j/ui"));
        Files.writeString(contextDir.resolve("com/zeroz4j/ui/Dialog.java"), """
            package com.zeroz4j.ui;
            public class Dialog { public void open() {} public void close() {} }
            """);
        Librarian librarian = librarian(List.of(contextDir));

        String result = librarian.lookupApi("Dialog open close");
        assertThat(result).contains("Dialog").contains("public void open()");
    }

    @Test
    void emptyRepoYieldsEmptyBriefWithoutFailing() {
        Librarian librarian = librarian(List.of());
        KnowledgeBrief brief = librarian.assembleBrief(repo,
            task("t", "i"));
        assertThat(brief.libraries()).isEmpty();
        assertThat(brief.internalApis()).isEmpty();
    }
}
