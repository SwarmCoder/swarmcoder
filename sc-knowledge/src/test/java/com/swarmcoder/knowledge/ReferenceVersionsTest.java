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

import com.swarmcoder.domain.LibraryDoc;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index follows the library's VERSION, and so does the worker's brief.
 *
 * <p><b>Three faults, each measured.</b>
 *
 * <ol>
 *   <li>The folder's fingerprint was a hash of path and file SIZE over the first 400 files in path
 *       order. An edit that kept a file's length was invisible to it, and most of a real folder
 *       could not influence it at all.</li>
 *   <li>A reference folder carried no version, so nothing could say which version of a framework
 *       the documentation described — not the brief, not the index directory, not the log.</li>
 *   <li>The brief's "Available libraries (exact versions — use these APIs, do not invent)" list
 *       printed {@code com.zeroz4j:zerozstack-bom ${zeroz4j.version}}: the literal placeholder. The
 *       one line whose purpose is to pin a version pinned nothing.</li>
 * </ol>
 */
class ReferenceVersionsTest {

    @TempDir
    Path repo;
    @TempDir
    Path framework;
    @TempDir
    Path cache;

    @BeforeEach
    void forgetCachedIdentities() {
        RootIdentity.forget();
    }

    private void writeFramework(String version) throws Exception {
        Files.writeString(framework.resolve("pom.xml"), """
            <project>
              <groupId>com.demo</groupId>
              <artifactId>demostack-parent</artifactId>
              <version>%s</version>
            </project>
            """.formatted(version));
        Files.createDirectories(framework.resolve("docs/guides"));
        Files.writeString(framework.resolve("docs/guides/persistence.md"), """
            # Saving data

            How to persist your object graph.

            ## The basics
            Inject the storage manager and read the root from it.
            """);
    }

    private void writeProject(String frameworkVersion) throws Exception {
        Files.writeString(repo.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>my-app</artifactId>
              <version>1.0.0</version>
              <properties>
                <demostack.version>%s</demostack.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>com.demo</groupId>
                  <artifactId>demostack-bom</artifactId>
                  <version>${demostack.version}</version>
                </dependency>
              </dependencies>
            </project>
            """.formatted(frameworkVersion));
    }

    private Librarian librarian() {
        return new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(framework), repo, null, cache);
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Save a product", "storage manager root",
            Set.of("src/main"), Set.of("src/main"), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    /** A version written as a property is resolved, not printed as a placeholder. */
    @Test
    void aVersionHeldInAPropertyIsResolved() throws Exception {
        writeProject("0.8.0");

        List<LibraryDoc> libraries = ManifestParser.parse(repo);

        assertThat(libraries).extracting(LibraryDoc::coordinate)
            .contains("com.demo:demostack-bom");
        assertThat(ManifestParser.versionOf(libraries, "demostack-bom")).isEqualTo("0.8.0");
        assertThat(libraries).noneMatch(library -> library.version().contains("${"));
    }

    /** A property declared in a parent pom is resolved through the parent chain. */
    @Test
    void aVersionHeldInAParentPomIsResolved() throws Exception {
        Files.writeString(repo.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <properties><demostack.version>0.7.2</demostack.version></properties>
              <modules><module>app</module></modules>
            </project>
            """);
        Path module = repo.resolve("app");
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>app</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.demo</groupId>
                  <artifactId>demostack-bom</artifactId>
                  <version>${demostack.version}</version>
                </dependency>
              </dependencies>
            </project>
            """);

        assertThat(ManifestParser.versionOf(ManifestParser.parse(module), "demostack-bom"))
            .isEqualTo("0.7.2");
    }

    /** The brief says which version of the folder these documents describe. */
    @Test
    void theBriefNamesTheVersionOfEveryReferenceFolder() throws Exception {
        writeFramework("0.8.0");
        writeProject("0.8.0");

        String brief = librarian().assembleBrief(repo, task()).renderedMarkdown();

        assertThat(brief).contains("(" + framework.getFileName() + " docs at 0.8.0)");
    }

    /** Matching versions say nothing extra. */
    @Test
    void whenTheVersionsAgreeTheBriefSaysNothingAboutIt() throws Exception {
        writeFramework("0.8.0");
        writeProject("0.8.0");

        assertThat(librarian().assembleBrief(repo, task()).renderedMarkdown())
            .doesNotContain("prefer what compiles");
    }

    /**
     * A mismatch is named. This is the failure that produces confidently wrong code: the
     * documentation is real, the example compiles in the folder it came from, and the method it
     * uses does not exist in the version the project builds against.
     */
    @Test
    void whenTheVersionsDifferTheBriefSaysSo() throws Exception {
        writeFramework("0.7.2");
        writeProject("0.8.0");

        String brief = librarian().assembleBrief(repo, task()).renderedMarkdown();

        assertThat(brief)
            .contains("Your project uses 0.8.0 of com.demo; these documents are for 0.7.2.")
            .contains("prefer what compiles");
    }

    /** An edit that keeps a file's length still changes the folder's fingerprint. */
    @Test
    void anEditThatKeepsAFilesLengthStillChangesTheFingerprint() throws Exception {
        writeFramework("0.8.0");
        String before = RootIdentity.of(framework, false).fingerprint();

        Thread.sleep(10);
        Path guide = framework.resolve("docs/guides/persistence.md");
        String text = Files.readString(guide);
        Files.writeString(guide, text.replace("Saving data", "Saving dataX").substring(0, text.length()));
        RootIdentity.forget();

        assertThat(RootIdentity.of(framework, false).fingerprint()).isNotEqualTo(before);
    }

    /** A git checkout is identified by its commit, and a dirty tree by more than its commit. */
    @Test
    void aGitCheckoutIsIdentifiedByItsCommit() {
        Path swarmcoder = Path.of("").toAbsolutePath();
        RootIdentity.Identity identity = RootIdentity.of(swarmcoder, false);

        if (identity.commit().isBlank()) {
            return; // not a checkout on this machine; the folder path is then the identity
        }
        assertThat(identity.fingerprint()).startsWith(identity.commit());
        assertThat(identity.describe()).contains("commit " + identity.commit());
        assertThat(identity.version()).isNotBlank();
    }

    /** A folder with no version anywhere says so instead of guessing. */
    @Test
    void aFolderWithNoVersionSaysUnknown() throws Exception {
        Files.createDirectories(framework.resolve("docs"));
        Files.writeString(framework.resolve("docs/x.md"), "# X\n\nSomething.\n");

        assertThat(RootIdentity.of(framework, false).version()).isEqualTo("unknown");
    }

    /** A new fingerprint is a new index folder, and the old one is not left behind. */
    @Test
    void aChangedFolderGetsANewIndexAndTheOldOneIsRemoved() throws Exception {
        writeFramework("0.8.0");
        librarian().curator().documentCount();
        Path indexRoot = cache.resolve("refs");
        List<Path> first = listing(indexRoot);
        assertThat(first).hasSize(1);

        Thread.sleep(10);
        Files.writeString(framework.resolve("docs/guides/routing.md"),
            "# Routing\n\nHow a URL reaches a view.\n");
        RootIdentity.forget();
        librarian().curator().documentCount();

        List<Path> second = listing(indexRoot);
        assertThat(second).hasSize(1);
        assertThat(second.get(0)).isNotEqualTo(first.get(0));
    }

    private static List<Path> listing(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            return files.filter(Files::isDirectory)
                .filter(path -> !path.getFileName().toString().equals("web"))
                .sorted().toList();
        }
    }
}
