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
 * The same failures as {@link LibrarianReturnsThePageThatAnswersTest}, on a tree this test builds,
 * so they are pinned on every machine and not only on the one that has the reference folder.
 *
 * <p>The tree is a miniature of the shape that broke: a guide that answers but whose headings do
 * not repeat the question's words, a topical page whose FILE NAME does repeat them, a file of
 * prompts for an agent that mentions every class in the answer and contains none of it, a release
 * note, a design note, an archetype skeleton with no store in it, two examples that each declare
 * the same root class, the framework's own producer, and a test.
 */
class LibrarianRankingOnASyntheticTreeTest {

    @TempDir
    Path repo;
    @TempDir
    Path framework;
    @TempDir
    Path cache;

    Librarian librarian;
    /** The root's label is its folder name, which a temporary folder decides. */
    String label;

    @BeforeEach
    void buildTheTree() throws Exception {
        write("README.md", """
            # DemoStack
            A pure-Java web framework with an object store.
            """);
        write("CHANGELOG.md", """
            # Changelog

            ## Added
            The zzzreleasemarker was added. The storage manager now stores the root.
            """ + "The store stores the stored store. ".repeat(80));
        write("docs/AGENT_PROMPTS.md", """
            # Agent prompts

            ## Task 3 — inventory-crud
            Build an inventory example. It needs a DataRoot, a DataRootProvider, a
            ProductServiceImpl and a StorageManager. Save the products with the storage manager.
            """ + "Use the store, the storage, the root and the DataRoot. ".repeat(40));
        write("docs/design/locks.md", """
            # Lock design

            ## 4.5 Store locking
            The storage manager holds a write lock while it stores the root.
            """);
        write("docs/store-modes.md", """
            # Store modes: embedded or hosted

            ## Choosing a mode
            An embedded store runs in the process. A hosted store runs on a server.
            """);
        write("docs/guides/persistence.md", """
            # Saving data

            How to persist your object graph, and the one rule that prevents data loss.

            ## The basics
            Inject the storage manager and read the root from it.

            ```java
            @Inject private StorageManager storage;
            private DataRoot root() { return (DataRoot) storage.root(); }
            ```

            ## The rule: one call, one save
            Saving is explicit. `storage.storeAll(root.getProducts(), root)` writes both in one
            commit, and the framework never writes for you.
            """);
        write("archetype/src/main/resources/archetype-resources/"
            + "__rootArtifactId__-server/src/main/java/server/ServerApp.java", """
            package server;
            /** The generated project's server class. It has no store in it at all. */
            public class ServerApp {
                public static void main(String[] args) { new ServerApp().run(); }
                void run() { }
            }
            """);
        write("examples/inventory/inventory-server/src/main/java/app/ProductServiceImpl.java", """
            package app;
            /** Saves a product into the root, explicitly. */
            public class ProductServiceImpl implements ProductService {
                @Inject private StorageManager storage;
                private DataRoot root() { return (DataRoot) storage.root(); }
                public void add(Product product) {
                    root().getProducts().add(product);
                    storage.storeAll(root().getProducts(), root());
                }
            }
            """);
        write("examples/inventory/inventory-server/src/main/java/app/store/DataRoot.java", """
            package app.store;
            /** The inventory example's root object. */
            public class DataRoot {
                private final List<Product> products = new ArrayList<>();
                public List<Product> getProducts() { return products; }
            }
            """);
        write("examples/chat/chat-server/src/main/java/app/store/DataRoot.java", """
            package app.store;
            /** The chat example's root object. */
            public class DataRoot {
                private final List<Message> messages = new ArrayList<>();
                public List<Message> getMessages() { return messages; }
            }
            """);
        write("core/src/main/java/fw/StorageProducer.java", """
            package fw;
            /** Produces the StorageManager the application injects. */
            public class StorageProducer {
                public StorageManager storage(DataRootProvider roots) { return open(roots); }
            }
            """);
        write("core/src/test/java/fw/StorageProducerTest.java", """
            package fw;
            /** Tests the storage producer: storage, store, root, DataRoot, ProductServiceImpl. */
            public class StorageProducerTest {
                @Test void producesAStorageManagerThatStoresTheRoot() { }
            }
            """);
        write("examples/inventory/pom.xml", """
            <project>
              <artifactId>inventory</artifactId>
              <dependencies>
                <dependency><groupId>com.demo</groupId><artifactId>demostack-core</artifactId></dependency>
              </dependencies>
            </project>
            """);
        write("examples/chat/pom.xml", """
            <project>
              <artifactId>chat</artifactId>
              <dependencies>
                <dependency><groupId>com.demo</groupId><artifactId>demostack-core</artifactId></dependency>
                <dependency><groupId>com.demo</groupId><artifactId>demostack-livesync</artifactId></dependency>
              </dependencies>
            </project>
            """);
        Files.writeString(repo.resolve("pom.xml"), """
            <project>
              <artifactId>my-app</artifactId>
              <dependencies>
                <dependency><groupId>com.demo</groupId><artifactId>demostack-core</artifactId></dependency>
              </dependencies>
            </project>
            """);
        librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(framework), repo, null, cache);
        label = framework.getFileName().toString();
    }

    private void write(String relative, String content) throws Exception {
        Path file = framework.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** What each file IS — the classification the whole ranking rests on. */
    @Test
    void everyFileIsClassifiedByWhatItIsFor() {
        assertThat(FileKinds.of("f/docs/guides/persistence.md")).isEqualTo(FileKinds.Kind.GUIDE);
        assertThat(FileKinds.of("f/docs/store-modes.md")).isEqualTo(FileKinds.Kind.GUIDE);
        assertThat(FileKinds.of("f/README.md")).isEqualTo(FileKinds.Kind.GUIDE);
        assertThat(FileKinds.of("f/docs/AGENT_PROMPTS.md")).isEqualTo(FileKinds.Kind.META);
        assertThat(FileKinds.of("f/docs/design/locks.md")).isEqualTo(FileKinds.Kind.META);
        assertThat(FileKinds.of("f/CHANGELOG.md")).isEqualTo(FileKinds.Kind.META);
        assertThat(FileKinds.of("f/AGENTS.md")).isEqualTo(FileKinds.Kind.META);
        assertThat(FileKinds.of("f/core/src/main/java/fw/StorageProducer.java"))
            .isEqualTo(FileKinds.Kind.FRAMEWORK);
        assertThat(FileKinds.of("f/examples/inventory/inventory-server/src/main/java/app/X.java"))
            .isEqualTo(FileKinds.Kind.EXAMPLE);
        assertThat(FileKinds.of("f/core/src/test/java/fw/StorageProducerTest.java"))
            .isEqualTo(FileKinds.Kind.TEST);
        assertThat(FileKinds.of("f/archetype/src/main/resources/archetype-resources/"
            + "__rootArtifactId__-server/src/main/java/server/ServerApp.java"))
            .isEqualTo(FileKinds.Kind.FIXTURE);
        assertThat(FileKinds.Kind.META.isAnswerable()).isFalse();
        assertThat(FileKinds.Kind.FIXTURE.isAnswerable()).isFalse();
    }

    /** A project may say what its own folders are, and the store is where that lives. */
    @Test
    void aProjectCanSayThatItsOwnFolderIsReferenceMaterial() {
        List<String> fromTheStore = List.of("guide: /handbook/", "meta: /sandbox/");

        assertThat(FileKinds.of("f/handbook/api.md", fromTheStore)).isEqualTo(FileKinds.Kind.GUIDE);
        assertThat(FileKinds.of("f/sandbox/src/main/java/X.java", fromTheStore))
            .isEqualTo(FileKinds.Kind.META);
        assertThat(FileKinds.of("f/sandbox/src/main/java/X.java"))
            .as("without the project's own lines, the defaults decide")
            .isEqualTo(FileKinds.Kind.FRAMEWORK);
        assertThat(FileKinds.of("f/x.md", List.of("nonsense: /x")))
            .as("a kind name the operator mistyped is ignored, not fatal")
            .isEqualTo(FileKinds.Kind.GUIDE);
    }

    /** The guide answers, and the prompt file, the release note and the design note do not. */
    @Test
    void theGuideAnswersAndTheMetaFilesNeverDo() {
        String answer = librarian.lookupApi("storage manager root DataRoot store save products");

        assertThat(answer)
            .contains("docs/guides/persistence.md")
            .doesNotContain("AGENT_PROMPTS.md")
            .doesNotContain("CHANGELOG.md")
            .doesNotContain("docs/design/locks.md");
        assertThat(librarian.curator().scoreSections(
                "storage manager root DataRoot store save products", true).get(0).value().address())
            .isEqualTo(label + "/docs/guides/persistence.md");
    }

    /** Process material is reachable when it is the only thing that can answer, and only then. */
    @Test
    void aReleaseNoteIsFoundWhenNothingElseCanAnswerAndNeverBeforeThat() {
        assertThat(librarian.lookupApi("zzzreleasemarker"))
            .as("the only page that mentions this is the release note")
            .contains("CHANGELOG.md");
        assertThat(librarian.lookupApi("storage manager root save"))
            .as("but never while a real page matches")
            .doesNotContain("CHANGELOG.md");
    }

    /** A template that matches the word is worse than no match: it looks like an answer. */
    @Test
    void anArchetypeSkeletonIsNeverTheAnswer() {
        String answer = librarian.lookupApi("inventory-server ServerApp main");

        assertThat(answer)
            .doesNotContain("archetype-resources")
            .doesNotContain("__rootArtifactId__")
            .contains("## Source: ");
    }

    /** The example the question names, not the first one whose path shares a generic word. */
    @Test
    void theExampleTheQuestionNamesIsTheOneReturned() {
        List<String> shown = librarian.lookupApi("inventory-server DataRoot root object products")
            .lines().filter(line -> line.startsWith("## Source: ")).toList();

        assertThat(shown)
            .anySatisfy(line -> assertThat(line)
                .contains("examples/inventory/inventory-server/src/main/java/app/store/DataRoot.java"))
            .as("one file per type name — the other example's root teaches nothing extra")
            .noneSatisfy(line -> assertThat(line).contains("examples/chat/"));
    }

    /** A test exercises an API; it does not show how to use one, so it ranks below real code. */
    @Test
    void aTestRanksBelowTheCodeItTests() {
        List<ReferenceIndex.SrcHit> hits = librarian.curator()
            .sourceHits("StorageProducer storage manager root DataRoot ProductServiceImpl", 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).address()).doesNotContain("/src/test/");
        int producer = indexOf(hits, "StorageProducer.java");
        int test = indexOf(hits, "StorageProducerTest.java");
        assertThat(producer).isGreaterThanOrEqualTo(0);
        assertThat(test < 0 || producer < test).isTrue();
    }

    /** The catalogue leads with the guides and never spends a line on process material. */
    @Test
    void theCatalogueLeadsWithTheGuides() {
        String map = librarian.curator().documentationMap(3_000);

        assertThat(map).startsWith("- " + label + "/docs/guides/persistence.md — Saving data");
        assertThat(map)
            .contains("How to persist")
            .doesNotContain("AGENT_PROMPTS.md")
            .doesNotContain("CHANGELOG.md")
            .doesNotContain("docs/design/");
        assertThat(map.indexOf("docs/guides/")).isLessThan(map.indexOf("README.md"));
    }

    /** Nothing is capped: every file in the tree is indexed, whatever its path sorts to. */
    @Test
    void everyFileIsIndexedAndNoneIsPastACap() {
        librarian.curator().documentCount();
        ReferenceIndex.Stats stats = librarian.curator().indexStats();

        assertThat(stats.files()).isEqualTo(12);
        assertThat(librarian.curator().documentCount()).isEqualTo(6);
    }

    /**
     * The prefix-cache invariant. The brief sits in the shared prompt prefix, which must be
     * byte-identical for every worker of a group, so nothing in its assembly may depend on walk
     * order, hash iteration, Lucene segment layout or the clock.
     */
    @Test
    void theBriefIsByteIdenticalWhenAssembledAgain() {
        Task task = new Task(UUID.randomUUID(), 1, "Add a product form",
            "Save a product with the storage manager and the root", Set.of("src/main"),
            Set.of("src/main"), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);

        String first = librarian.assembleBrief(repo, task).renderedMarkdown();
        String second = librarian.assembleBrief(repo, task).renderedMarkdown();
        Librarian fresh = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(framework), repo, null, cache);
        String third = fresh.assembleBrief(repo, task).renderedMarkdown();

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    /** A file that changes is re-read; the index is not rebuilt from scratch to notice. */
    @Test
    void anEditedFileIsPickedUpWithoutRebuildingTheIndex() throws Exception {
        assertThat(librarian.lookupApi("zzzmarker")).doesNotContain("zzzmarker found");
        write("docs/guides/persistence.md", """
            # Saving data

            ## The basics
            zzzmarker found. Inject the storage manager and read the root from it.
            """);
        librarian.curator().invalidateInventory();

        assertThat(librarian.lookupApi("zzzmarker")).contains("zzzmarker found");
    }

    /**
     * What the framework primer in every worker's prefix is distilled FROM.
     *
     * <p>It used to be the README plus the first eight example files in path order. On the folder
     * this was measured against that is the chat example, which uses a different persistence API
     * from the one the guides teach — so the primer and the guide disagreed, and a worker asking
     * twelve questions in a row mixed the two vocabularies because the two authorities it had been
     * given did not agree on which stack it had. The guides are the primer's material now, and the
     * example code that follows comes only from an example whose own dependencies the project
     * actually has.
     */
    @Test
    void thePrimerIsDistilledFromTheGuidesAndAnExampleTheProjectCouldBuild() {
        KnowledgeCurator.Root root = librarian.curator().roots().stream()
            .filter(candidate -> !"project".equals(candidate.label())).findFirst().orElseThrow();

        String input = librarian.curator().primerInput(root);

        assertThat(input)
            .as("the guide that answers")
            .contains("docs/guides/persistence.md")
            .contains("storage.storeAll(root.getProducts(), root)")
            .as("and the page that says which store mode an application is in")
            .contains("docs/store-modes.md")
            .as("and the example whose dependencies the project has")
            .contains("examples/inventory/inventory-server/src/main/java/app/ProductServiceImpl.java")
            .as("and not the one that needs a module the project does not depend on")
            .doesNotContain("examples/chat/")
            .as("and never a file of prompts for an agent")
            .doesNotContain("AGENT_PROMPTS.md");
    }

    private static int indexOf(List<ReferenceIndex.SrcHit> hits, String name) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).address().endsWith(name)) {
                return i;
            }
        }
        return -1;
    }
}
