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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The expert's, the architect's, the planner's, the test author's and the chat's {@code read_file}
 * and {@code list_files} all end in {@link KnowledgeCurator}'s one address resolver. The project
 * root is a tree model-written commands work in, so a link planted there must not turn a lookup
 * into a way to read the rest of the machine.
 */
class ALookupDoesNotFollowALinkOutOfItsRootsTest {

    @Test
    void aLinkInsideTheProjectThatLeadsOutOfItIsNotRead(@TempDir Path world) throws Exception {
        Path app = world.resolve("app");
        Path elsewhere = world.resolve("elsewhere");
        Files.createDirectories(app.resolve("src"));
        Files.createDirectories(elsewhere);
        Files.writeString(app.resolve("src/App.java"), "class App {}\n");
        Files.writeString(elsewhere.resolve("secrets.txt"), "THE-OPERATORS-SECRET\n");
        assumeTrue(LinkForTests.directoryLink(app.resolve("way-out"), elsewhere),
            "this machine lets the test create neither a symbolic link nor a junction");

        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local")), null,
            world.resolve("cache"));

        assertThat(curator.readFile("project/src/App.java", 10_000)).contains("class App");
        assertThat(curator.readFile("project/way-out/secrets.txt", 10_000))
            .startsWith("error:").doesNotContain("THE-OPERATORS-SECRET");
        assertThat(curator.isFolder("project/way-out")).isFalse();

        ExpertTools tools = new ExpertTools(curator, null, null, null);
        assertThat(tools.readFile("project/way-out/secrets.txt"))
            .doesNotContain("THE-OPERATORS-SECRET");
        assertThat(tools.readFile("way-out/secrets.txt")).doesNotContain("THE-OPERATORS-SECRET");
        assertThat(tools.listFiles("project/way-out")).doesNotContain("secrets.txt");
    }
}
