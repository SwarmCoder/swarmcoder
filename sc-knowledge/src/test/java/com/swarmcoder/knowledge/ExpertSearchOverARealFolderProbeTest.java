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
 * A probe, not a guard: what {@link ExpertSearch} answers for real questions over a real reference
 * folder, printed, so a person can read it. It runs only when it is told which folder and which
 * questions, and is skipped otherwise - nothing here names a library or a project.
 *
 * <pre>
 *   -Dexpert.search.folder=&lt;a reference folder, read only&gt;
 *   -Dexpert.search.questions="first question|second question"
 *   -Dexpert.search.out=&lt;file to write what was found to&gt;      (optional)
 * </pre>
 *
 * <p>The folder is only read. The indexes are built in a temporary folder of this test.
 */
class ExpertSearchOverARealFolderProbeTest {

    @TempDir
    Path scratch;

    @Test
    void whatOneSearchAnswersForEachQuestion() throws Exception {
        String folder = System.getProperty("expert.search.folder", "");
        String questions = System.getProperty("expert.search.questions", "");
        assumeTrue(!folder.isBlank() && !questions.isBlank(), "no folder and questions were given");
        Path reference = Path.of(folder);
        assumeTrue(Files.isDirectory(reference), "the folder does not exist");

        Path project = Files.createDirectories(scratch.resolve("project"));
        long started = System.currentTimeMillis();
        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", project, "local"),
                new KnowledgeCurator.Root(reference.getFileName().toString(), reference, "probe")),
            null, scratch.resolve("cache"));

        StringBuilder out = new StringBuilder();
        for (String question : questions.split("\\|")) {
            long before = System.currentTimeMillis();
            String found = ExpertSearch.search(curator, question.strip(), 30_000, true);
            out.append("\n==================== ").append(question.strip()).append("\n(")
                .append(found.length()).append(" characters, ")
                .append(System.currentTimeMillis() - before).append(" ms)\n").append(found);
        }
        out.append("\n\n(everything, indexes included: ")
            .append(System.currentTimeMillis() - started).append(" ms)\n");
        String target = System.getProperty("expert.search.out", "");
        if (target.isBlank()) {
            System.out.println(out);
        } else {
            Files.writeString(Path.of(target), out.toString());
        }
        assertThat(out).isNotEmpty();
    }
}
