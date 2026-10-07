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

import com.swarmcoder.domain.ApiContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A repository whose only build file is at its root still gets a worked example.</b>
 *
 * <p>It did not, and the failure was total and silent: for such a repository {@code buildsAbove}
 * returned an empty list for every file, so the pass that picks which project to copy had no groups
 * to pick from, and <b>no worked example was ever offered whatever the contracts said</b>. The one
 * channel the unseen-code experiment measured as the difference between 467 turns with zero files
 * written and green in 34 turns was simply off, and nothing in the log said "this project has no
 * modules".
 *
 * <p><b>Measured, 2026-09-05, on jsoup</b> — one Maven module, one {@code pom.xml}, at the root.
 * The architect stated four contracts and the structural index found evidence for all four: the log
 * read "structural evidence from the semantic index for 4 of 4 contract(s)" and immediately after
 * it "worked example: null (value 0.0, of <b>0</b> candidate projects)". Nothing was wrong with the
 * contracts, the index or the scoring; there was nowhere for a file to belong.
 *
 * <p>Greenfield never met this. The demo project is multi-module and so is the reference checkout
 * beside it, so every file always had at least one ancestor carrying a build file. A single-module
 * repository is the commonest shape of brownfield target there is.
 */
class ASingleModuleProjectStillHasAWorkedExampleTest {

    @TempDir
    Path world;

    /** The shape jsoup has: sources under one root, one pom.xml, and no modules at all. */
    private Path singleModuleProject() throws Exception {
        Path root = world.resolve("one-module-library");
        write(root.resolve("pom.xml"), """
            <project>
              <groupId>com.acme</groupId><artifactId>one-module-library</artifactId>
              <version>1.0.0</version>
            </project>
            """);
        write(root.resolve("src/main/java/com/acme/doc/Document.java"), """
            package com.acme.doc;

            import java.nio.charset.Charset;

            /** A parsed document. */
            public class Document {
                private Charset charset;
                private Node firstChild;

                public Charset charset() { return charset; }

                public void charset(Charset charset) {
                    this.charset = charset;
                    ensureMetaCharsetElement();
                }

                private void ensureMetaCharsetElement() {
                    firstChild.attr("charset", charset.name());
                }
            }
            """);
        write(root.resolve("src/main/java/com/acme/doc/Node.java"), """
            package com.acme.doc;

            /** One node of a document. */
            public class Node {
                public void attr(String key, String value) { }
            }
            """);
        return root;
    }

    @Test
    void theRootCountsAsTheProjectWhenNothingBelowItDeclaresABuild() throws Exception {
        Path root = singleModuleProject();
        Path aSource = root.resolve("src/main/java/com/acme/doc/Document.java");

        assertThat(WorkedExamples.buildsAbove(aSource, root))
            .as("with no module between the file and the root, the root itself is the project — "
                + "an empty list here is not 'no preference', it is 'no projects', and the pass "
                + "that chooses one then chooses nothing")
            .containsExactly(root);
    }

    /**
     * <b>The code the change is about is the nearest example of it</b>, and on a single-module
     * repository it is now found.
     *
     * <p>Two gates had to open, and both were shut. The first is the one above. The second is that
     * a file which IS the contracted type scored zero — right on a greenfield build, where a file
     * with that exact name is a leftover or a coincidence and showing it as "the nearest existing
     * implementation of what you must build" is circular. It is wrong on a change, where that file
     * is the code being changed, and design §2.3 says so outright: "a worker reading the current
     * {@code Evaluator.java} before changing it is right."
     *
     * <p>What tells the two apart is the design's own word for it: the architect is asked to begin
     * an existing type's description with {@code EXISTING:} and a new one's with {@code NEW:}. That
     * is the one fact the chooser cannot work out for itself — the file exists either way, and only
     * the design knows whether the task is about to write it or to change it.
     */
    @Test
    void andSoTheCodeTheChangeIsAboutIsOfferedAsTheNearestExample() throws Exception {
        Path root = singleModuleProject();
        List<WorkedExamples.Shape> shapes =
            WorkedExamples.shapes(List.of(new KnowledgeCurator.Root("project", root)));
        assertThat(shapes).as("the two source files were read").hasSize(2);

        WorkedExamples.Selection selection = WorkedExamples.select(shapes,
            List.of(contract("EXISTING: the document whose charset is being set")),
            TaskBrief.taskWords("Setting charset on an empty document throws"));

        assertThat(selection.isEmpty())
            .as("the worked-example channel must be ON for a single-module repository — it is the "
                + "channel measured as the difference between writing nothing across 467 turns and "
                + "being green in 34")
            .isFalse();
        assertThat(selection.project()).isEqualTo(root);
        assertThat(selection.matches().get(0).example().simpleName())
            .as("and the file offered is the one being changed")
            .isEqualTo("Document");
    }

    /**
     * A contract the design did NOT mark as existing behaves exactly as every contract behaved
     * before the marker existed: the file that happens to carry that name is not offered as an
     * example of building it.
     *
     * <p>So a greenfield build is untouched, and a design that ignores the instruction loses
     * nothing it had.
     */
    @Test
    void anUnmarkedContractStillWillNotBeShownItsOwnFile() throws Exception {
        Path root = singleModuleProject();
        List<WorkedExamples.Shape> shapes =
            WorkedExamples.shapes(List.of(new KnowledgeCurator.Root("project", root)));

        WorkedExamples.Selection selection = WorkedExamples.select(shapes,
            List.of(contract("the document whose charset is being set")),
            TaskBrief.taskWords("Setting charset on an empty document throws"));

        assertThat(selection.matches().stream().map(m -> m.example().simpleName()))
            .as("nothing here may claim to be an example of how to build Document")
            .doesNotContain("Document");
    }

    /** The shape a change request produces: a contract naming a type this repository declares. */
    private static ApiContract contract(String description) {
        return new ApiContract(UUID.randomUUID(), "Document", description, "",
            "com.acme.doc.Document",
            List.of("Charset charset()", "void charset(Charset charset)"));
    }

    /**
     * A multi-module repository is untouched — the root is added only when nothing below it
     * declared a build, so nowhere that was already choosing a project can start choosing a
     * different one.
     */
    @Test
    void aMultiModuleRepositoryChoosesItsModulesExactlyAsBefore() throws Exception {
        Path root = world.resolve("many-modules");
        write(root.resolve("pom.xml"), """
            <project><artifactId>many-modules</artifactId>
              <modules><module>core</module></modules></project>
            """);
        write(root.resolve("core/pom.xml"), "<project><artifactId>core</artifactId></project>");
        Path source = root.resolve("core/src/main/java/com/acme/Thing.java");
        write(source, "package com.acme;\npublic class Thing { }\n");

        assertThat(WorkedExamples.buildsAbove(source, root))
            .as("the module, and only the module — the root is not added beside it")
            .containsExactly(root.resolve("core"));
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
