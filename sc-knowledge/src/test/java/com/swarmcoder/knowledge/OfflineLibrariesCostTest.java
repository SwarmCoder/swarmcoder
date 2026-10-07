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

import com.swarmcoder.testsupport.LocalCheckouts;
import com.swarmcoder.domain.LibraryDoc;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * What the "libraries you may ADD" list costs, measured on the operator's real demo project
 * against the operator's real local Maven repository.
 *
 * <p><b>Why measured and not reasoned about.</b> This list is in the shared prompt prefix, which is
 * charged against every worker's working context and is the floor history trimming can never
 * compact away. The prefix diet (2026-09-02) cut the knowledge brief from 4,007 tokens to a stated
 * budget per channel precisely because nobody had ever measured it. A new channel that is not
 * measured is how that comes back.
 *
 * <p>The cap is asserted, not just printed: this list is derived from a BOM, and a BOM can grow
 * without anybody here noticing. It skips itself when the demo project or the local repository is
 * not on this machine, so a checkout with neither still builds green.
 *
 * <p>Nothing is called: this reads two directories of XML off the disk.
 */
class OfflineLibrariesCostTest {

    private static final Path PROJECT = LocalCheckouts.find("dev/bookshelf-demo", "swarmcoder/dev/bookshelf-demo");

    /** {@code HistoryTrim}'s own estimator — crude, and the one the running system uses. */
    private static final int CHARS_PER_TOKEN = 4;

    /** The budget this channel was given. Beyond it, filter harder rather than raising it. */
    private static final int MAX_TOKENS = 400;

    /** The rule that started all of this — the operator's own tech-requirements wording. */
    private static final String RULES = """
        - Storage is an object graph, not a database
          Persistence uses EclipseStore through `zerozstack-store-eclipsestore`. The server keeps
          the live Java objects in memory and writes that object graph to disk.
        """;

    @Test
    void theListATaskOnTheRealDemoWouldCarryStaysUnderItsBudget() throws Exception {
        Path localRepository = DeclarableArtifacts.defaultLocalRepository();
        assumeThat(Files.isDirectory(PROJECT)).isTrue();
        assumeThat(Files.isDirectory(localRepository)).isTrue();

        List<String> modules = List.of("bookshelf-demo-shared", "bookshelf-demo-client",
            "bookshelf-demo-server");
        List<LibraryDoc> declared = new ArrayList<>(ManifestParser.parse(PROJECT));
        for (String module : modules) {
            declared.addAll(ManifestParser.parse(PROJECT.resolve(module)));
        }

        DeclarableArtifacts.Catalog catalog =
            DeclarableArtifacts.scan(PROJECT, modules, localRepository);
        OfflineLibraryBrief brief = new OfflineLibraryBrief(catalog, declared, RULES);
        String rendered = brief.render();

        System.out.println();
        System.out.println("=== LIBRARIES A WORKER MAY ADD: dev/bookshelf-demo + "
            + localRepository + " ===");
        System.out.println("managed AND present offline: " + catalog.artifacts().size());
        System.out.println("offered to the worker:       " + brief.offered().size());
        System.out.println("rendered: " + rendered.length() + " chars, "
            + (rendered.length() / CHARS_PER_TOKEN) + " tokens");
        System.out.println(rendered);
        System.out.println("=== END ===");

        assertThat(rendered.length() / CHARS_PER_TOKEN)
            .as("this list sits in every worker's permanent prefix floor")
            .isLessThan(MAX_TOKENS);
    }
}
