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

import com.swarmcoder.domain.OutsideLookups;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What leaves the computer for a documentation lookup, and when (section 90).
 *
 * <p>A live build of a small project named after an everyday word sent that word to an outside
 * documentation service, took in the documentation of an unrelated library of the same name, and
 * lost entries of its documentation index to a second writer. Three rules came out of it and each
 * has a group here: nothing is sent unless the owner allowed it; when it is allowed, only a
 * library the build files declare is asked about and only an answer tied to it is kept; and the
 * documentation index has one writer at a time.
 *
 * <p>No test here reaches a network: the server is a stand-in that records what it was sent.
 */
class OutsideLookupsTest {

    @TempDir
    Path repo;
    @TempDir
    Path cache;

    /** A documentation server that records every call and answers from a script. */
    private static final class RecordingServer extends Context7Client {
        final List<String> calls = new CopyOnWriteArrayList<>();
        String listing = "";
        String docs = "# Usage\nHow the published library is used.";

        RecordingServer(OutsideLookups lookups) {
            super("https://docs.example.com/mcp", "a-key", true, lookups);
        }

        @Override
        protected Optional<String> transport(String tool, Map<String, Object> arguments) {
            calls.add(tool + " " + arguments);
            return Optional.of(tool.equals("resolve-library-id") ? listing : docs);
        }
    }

    private Librarian librarian(Context7Client server, DocsIndex index) {
        return new Librarian(server, index, List.of(), repo, null, cache);
    }

    /** A two-module build: the parent lists its own module beside one real dependency. */
    private void aProjectNamedAfterAnEverydayWord() throws Exception {
        Files.writeString(repo.resolve("pom.xml"), """
            <project>
              <groupId>com.example.shelf</groupId>
              <artifactId>shelf</artifactId>
              <version>1.0</version>
              <modules><module>shelf-shared</module></modules>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example.shelf</groupId>
                    <artifactId>shelf-shared</artifactId>
                    <version>1.0</version>
                  </dependency>
                  <dependency>
                    <groupId>org.eclipse.store</groupId>
                    <artifactId>storage-embedded</artifactId>
                    <version>2.1.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
        Files.createDirectories(repo.resolve("shelf-shared"));
        Files.writeString(repo.resolve("shelf-shared/pom.xml"), """
            <project>
              <parent>
                <groupId>com.example.shelf</groupId>
                <artifactId>shelf</artifactId>
                <version>1.0</version>
              </parent>
              <artifactId>shelf-shared</artifactId>
            </project>
            """);
    }

    private static final String UNRELATED_AND_TIED = """
        Available Libraries:

        - Title: Shelf.js
        - Context7-compatible library ID: /shelf/shelf
        - Description: An unrelated library that shares a word with the project.
        - Source Reputation: High
        ----------
        - Title: EclipseStore
        - Context7-compatible library ID: /eclipse-store/store
        - Description: Java-native persistence.
        """;

    @Nested
    class OffUnlessTheOwnerAllowedIt {

        @Test
        void theDocumentationServerIsNeverAsked() throws Exception {
            aProjectNamedAfterAnEverydayWord();
            List<String> log = new ArrayList<>();
            RecordingServer server = new RecordingServer(OutsideLookups.off());
            Librarian librarian = librarian(server, null);

            assertThat(librarian.fetchLibraryDocs("storage-embedded", "how to store")).isEmpty();
            assertThat(server.resolveLibrary("storage-embedded", "anything")).isEmpty();
            assertThat(server.fetchDocs("/eclipse-store/store", "anything")).isEmpty();
            librarian.lookupApi("storage-embedded root object");

            assertThat(server.calls)
                .describedAs("off means the server is not asked by any route")
                .isEmpty();
            assertThat(server.connect()).isFalse();
            assertThat(server.posture()).isEqualTo(Context7Client.Posture.SWITCHED_OFF);
            assertThat(librarian.documentationServerConfigured())
                .describedAs("a key is set, and the roles are still not offered the tool")
                .isFalse();
            assertThat(librarian.outsideLookupRefusal("storage-embedded")).isPresent();
            assertThat(log).isEmpty();
        }

        @Test
        void theResearcherHasNoWebAndNoOtherServer() {
            List<String> reached = new ArrayList<>();
            WebAccess real = new WebAccess() {
                @Override
                public String search(String query) {
                    reached.add(query);
                    return "results";
                }

                @Override
                public String fetch(String url) {
                    reached.add(url);
                    return "page";
                }
            };
            WebAccess web = WebAccess.governedBy(OutsideLookups.off(), real);

            assertThat(web.search("the project's words")).isEqualTo(WebAccess.SWITCHED_OFF);
            assertThat(web.fetch("https://example.com/page")).isEqualTo(WebAccess.SWITCHED_OFF);
            assertThat(reached).isEmpty();

            McpServers servers = new McpServers(
                List.of(new McpServers.Server("docs", "https://docs.example.com/sse")),
                OutsideLookups.off());
            assertThat(servers.isEmpty())
                .describedAs("a listed server is not kept, so it cannot be connected to")
                .isTrue();
            assertThat(servers.listTools()).isEmpty();
        }

        @Test
        void allowedTheWebIsUsedAndEverySendingIsWrittenDown() {
            List<String> log = new ArrayList<>();
            OutsideLookups allowed = OutsideLookups.on(log::add);
            WebAccess web = WebAccess.governedBy(allowed, new WebAccess() {
                @Override
                public String search(String query) {
                    return "results";
                }

                @Override
                public String fetch(String url) {
                    return "page";
                }
            });

            assertThat(web.search("storage root")).isEqualTo("results");
            assertThat(web.fetch("https://example.com/guide?x=1")).isEqualTo("page");

            assertThat(log).hasSize(2);
            assertThat(log.get(0)).startsWith("Outside lookup sent to html.duckduckgo.com")
                .endsWith("storage root");
            assertThat(log.get(1)).startsWith("Outside lookup sent to example.com");
        }
    }

    @Nested
    class OnlyALibraryTheBuildFilesDeclare {

        @Test
        void theProjectsOwnModuleAndAFreeWordAreNotSent() throws Exception {
            aProjectNamedAfterAnEverydayWord();
            RecordingServer server = new RecordingServer(OutsideLookups.on(line -> { }));
            Librarian librarian = librarian(server, null);

            assertThat(librarian.fetchLibraryDocs("shelf-shared", "shelf-shared")).isEmpty();
            assertThat(librarian.fetchLibraryDocs("shelf", "shelf")).isEmpty();
            assertThat(librarian.fetchLibraryDocs("ShelfService", "how do I save")).isEmpty();
            librarian.lookupApi("ShelfService save a book");

            assertThat(server.calls)
                .describedAs("a module the build itself makes is not a library, and neither is "
                    + "a word out of a question")
                .isEmpty();
            assertThat(librarian.outsideLookupRefusal("ShelfService").orElse(""))
                .contains("nothing was sent")
                .contains("org.eclipse.store:storage-embedded")
                .doesNotContain("shelf-shared");
        }

        @Test
        void aDeclaredLibraryIsAskedAboutAndOnlyTheAnswerTiedToItIsKept() throws Exception {
            aProjectNamedAfterAnEverydayWord();
            List<String> log = new ArrayList<>();
            RecordingServer server = new RecordingServer(OutsideLookups.on(log::add));
            server.listing = UNRELATED_AND_TIED;
            Librarian librarian = librarian(server, null);

            Optional<String> docs = librarian.fetchLibraryDocs("storage-embedded", "root object");

            assertThat(docs).isPresent();
            assertThat(server.calls).hasSize(2);
            assertThat(server.calls.get(0))
                .isEqualTo("resolve-library-id {libraryName=storage-embedded, query=root object}");
            assertThat(server.calls.get(1))
                .describedAs("the first entry shares a word with the project and is passed over")
                .isEqualTo("query-docs {libraryId=/eclipse-store/store, query=root object}");
            assertThat(librarian.curator().holdsLibraryDocs("/eclipse-store/store", "latest"))
                .isTrue();
            assertThat(librarian.curator().holdsLibraryDocs("/shelf/shelf", "latest")).isFalse();
            assertThat(log)
                .describedAs("what was sent is written down: the words, not the answer")
                .hasSize(2)
                .allMatch(line -> line.startsWith("Outside lookup sent to docs.example.com"))
                .noneMatch(line -> line.contains("How the published library is used"));
            assertThat(log.get(0)).contains("storage-embedded").contains("root object");
        }

        @Test
        void anAnswerThatCannotBeTiedToTheLibraryIsDropped() throws Exception {
            aProjectNamedAfterAnEverydayWord();
            RecordingServer server = new RecordingServer(OutsideLookups.on(line -> { }));
            server.listing = """
                Available Libraries:

                - Title: Shelf.js
                - Context7-compatible library ID: /shelf/shelf
                """;
            Librarian librarian = librarian(server, null);

            assertThat(librarian.fetchLibraryDocs("org.eclipse.store:storage-embedded", "root"))
                .isEmpty();

            assertThat(server.calls)
                .describedAs("asked which library it is, and nothing fetched for the wrong one")
                .hasSize(1);
            assertThat(librarian.curator().holdsLibraryDocs("/shelf/shelf", "latest")).isFalse();
        }

        @Test
        void aCandidateIsTiedByWholeNamePartsNotByFragments() {
            DeclaredLibraries.Library store =
                new DeclaredLibraries.Library("org.eclipse.store", "storage-embedded", "2.1.0");
            DeclaredLibraries.Library jupiter =
                new DeclaredLibraries.Library("org.junit.jupiter", "junit-jupiter", "5.10.2");

            assertThat(DeclaredLibraries.tied(store,
                new DeclaredLibraries.Candidate("EclipseStore", "/eclipse-store/store"))).isTrue();
            assertThat(DeclaredLibraries.tied(jupiter,
                new DeclaredLibraries.Candidate("JUnit", "/junit-team/junit-framework"))).isTrue();
            assertThat(DeclaredLibraries.tied(store,
                new DeclaredLibraries.Candidate("Shelf.js", "/shelf/shelf"))).isFalse();
            assertThat(DeclaredLibraries.tied(store,
                new DeclaredLibraries.Candidate("Restore", "/acme/restore")))
                .describedAs("'restore' contains 'store' and is not it")
                .isFalse();
            assertThat(DeclaredLibraries.tied(store,
                new DeclaredLibraries.Candidate("Org", "/acme/org")))
                .describedAs("the first group segment names nobody")
                .isFalse();
        }
    }

    @Nested
    class OneWriterAtATime {

        @TempDir
        Path indexDir;

        @Test
        void everyLibraryIndexedAtTheSameMomentIsInTheIndex() throws Exception {
            DocsIndex index = new DocsIndex(indexDir);
            int writers = 12;
            CountDownLatch together = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String id = "/library/number" + i;
                Thread thread = new Thread(() -> {
                    try {
                        together.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    index.indexDocs(id, "persistence guide for " + id);
                });
                threads.add(thread);
                thread.start();
            }
            together.countDown();
            for (Thread thread : threads) {
                thread.join(30_000);
            }

            for (int i = 0; i < writers; i++) {
                String id = "/library/number" + i;
                assertThat(index.lookupApi(id, "persistence"))
                    .describedAs("the writer for %s did not lose the index to another", id)
                    .isPresent();
            }
        }
    }
}
