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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.knowledge.ProjectClasspath;
import com.swarmcoder.lsp.LspDiagnostic;
import com.swarmcoder.lsp.LspResult;
import com.swarmcoder.lsp.LspService;
import com.swarmcoder.runtime.ApiLookup;
import com.swarmcoder.swarm.WorkerToolbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 113, finding 2: a project with no build file yet, built on a
 * reference library. {@code shape_of} answered "No type" 19 times and {@code find_symbol} found
 * nothing about 25 times for classes of the library's dependencies: the language server was
 * given the project's jars, and the project named none.
 *
 * <ul>
 *   <li>The server's jars are the reference material's when the project's build names none.</li>
 *   <li>An answer that finds nothing says where it looked, and is not kept.</li>
 *   <li>Once the worker's checkout holds the run's build file, its own server is asked.</li>
 * </ul>
 */
class ALibraryTypeIsLookedUpInTheJarsItComesFromTest {

    @TempDir
    Path world;

    @Test
    void aLibraryTypeIsLookedUpInTheJarsTheProjectIsMeantToUseAndANoSaysWhereItLooked()
            throws Exception {
        Path m2 = world.resolve("m2");
        Path jarDir = Files.createDirectories(m2.resolve("repository/org/example/store/2.4"));
        Files.writeString(jarDir.resolve("store-2.4.pom"), "<project/>");
        Path jar = Files.write(jarDir.resolve("store-2.4.jar"), new byte[] {1});
        Path app = Files.createDirectories(world.resolve("app"));
        Path lib = world.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("pom.xml"), """
            <project><groupId>org.example</groupId><artifactId>lib</artifactId><version>1</version>
              <dependencyManagement><dependencies>
                <dependency><groupId>org.example</groupId><artifactId>store</artifactId>
                  <version>2.4</version></dependency>
              </dependencies></dependencyManagement>
            </project>
            """);
        String before = System.getProperty("swarmcoder.sandbox.m2");
        System.setProperty("swarmcoder.sandbox.m2", m2.toString());
        try {
            Librarian librarian = new Librarian(null, null, List.of(lib), app, null,
                world.resolve("primers"));

            assertThat(ProjectClasspath.orTheReferencesOf(librarian.curator()).apply(app))
                .as("the project names no library, so the reference material's jars are read")
                .contains(jar);

            librarian.curator().languageServer(new KnowsNothing(), app);
            WorkerLookups lookups = new WorkerLookups(librarian, app, false, null);

            assertThat(lookups.tree("shape_of", "org.example.store.Root"))
                .contains("Looked in:")
                .contains("3 library jar(s)")
                .contains("the jars the reference material's build files name");
            assertThat(lookups.answeredNothing()).isTrue();
            assertThat(lookups.tree("find_symbol", "Root")).contains("Looked in:");
            assertThat(lookups.tree("offline_artifacts", "org.example:store"))
                .contains("org.example:store  2.4");
            assertThat(lookups.answeredNothing()).isFalse();

            // The run's build is merged into the worker's checkout: its own server is asked.
            Path checkout = Files.createDirectories(world.resolve("checkout"));
            Files.writeString(checkout.resolve("pom.xml"), "<project/>");
            List<String> askedOfTheCheckout = new ArrayList<>();
            ApiLookup project = new ApiLookup() {
                @Override
                public String lookup(String query) {
                    return "";
                }

                @Override
                public String tree(String query, String argument) {
                    return "No type `" + argument + "` in this project's material.";
                }

                @Override
                public boolean answeredNothing() {
                    return true;
                }

                @Override
                public boolean languageServer() {
                    return true;
                }

                @Override
                public InCheckout inCheckout(Path where, String action, String argument,
                                             String second,
                                             java.util.function.Function<String, String> no) {
                    askedOfTheCheckout.add(action + " " + argument);
                    return new InCheckout("Root: open(), close()", List.of());
                }
            };
            WorkerToolbox toolbox = new WorkerToolbox(checkout, new Task(UUID.randomUUID(), 1,
                "t", "i", Set.of("src"), Set.of("src"), List.of(), null, null, null,
                new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY), project);

            assertThat(toolbox.shapeOf("org.example.store.Root"))
                .contains("Root: open(), close()").contains("the run's build");
            assertThat(askedOfTheCheckout).containsExactly("shape_of org.example.store.Root");
        } finally {
            if (before == null) {
                System.clearProperty("swarmcoder.sandbox.m2");
            } else {
                System.setProperty("swarmcoder.sandbox.m2", before);
            }
        }
    }

    /** An installed server that reads three jars and knows no type of the name asked. */
    private static final class KnowsNothing implements LspService {
        @Override
        public List<LspDiagnostic> diagnostics(Path file) {
            return List.of();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public LspResult members(String type) {
            return LspResult.notFound("No type `" + type + "` in this project or its jars.");
        }

        @Override
        public LspResult workspaceSymbols(String query) {
            return LspResult.ok("0 type(s) match `" + query + "`.", List.of(), 0);
        }

        @Override
        public int libraryJars() {
            return 3;
        }

        @Override
        public void close() {
            // nothing was started
        }
    }
}
