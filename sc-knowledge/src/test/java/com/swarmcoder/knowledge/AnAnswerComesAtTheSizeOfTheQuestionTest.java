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

import com.swarmcoder.inference.LookupMeter;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 95. One live build's architect looked up 487,401 characters, 248,177 of them 43 whole
 * files and 304,692 from the reference folder: example sources read whole to see how a thing is
 * done, guides read whole after their outlines, build files read whole after {@code build_of},
 * one package listed as 30,723 characters, and {@code outline_of} answering "no such file" for
 * every file of the reference folder. An answer now comes at the size of the question, and its
 * last line says what was left out and how to get it.
 */
class AnAnswerComesAtTheSizeOfTheQuestionTest {

    @TempDir
    Path world;

    private KnowledgeCurator curator;
    private String exampleSource;
    private String guide;

    @BeforeEach
    void aProjectAndTheFrameworkItBuildsOn() throws Exception {
        Path app = world.resolve("app");
        Path framework = world.resolve("framework");
        write(app.resolve("pom.xml"), "<project>\n  <groupId>com.shop</groupId>\n"
            + "  <artifactId>shop</artifactId>\n  <version>1</version>\n"
            + "  <!-- " + "why this build is as it is. ".repeat(80) + "-->\n"
            + "  <dependencies>\n    <dependency>\n      <groupId>org.example</groupId>\n"
            + "      <artifactId>ledger</artifactId>\n      <version>2.1</version>\n"
            + "    </dependency>\n  </dependencies>\n</project>\n");
        write(app.resolve("src/main/java/com/shop/Basket.java"),
            "package com.shop;\n\npublic interface Basket {\n    int size();\n}\n");

        StringBuilder source = new StringBuilder("/*\n * A licence.\n */\npackage org.frame.example;\n\n"
            + "import java.util.List;\n\n/** Shows how an order is taken. */\n"
            + "public class OrderExample {\n\n    private final List<String> lines = new java.util.ArrayList<>();\n\n");
        for (int i = 0; i < 12; i++) {
            source.append("    /** Step ").append(i).append(". */\n    public int step").append(i)
                .append("(int amount) {\n        int total = amount;\n")
                .append("        for (String line : lines) {\n            total += line.length() + ")
                .append(i).append(";\n            if (total > 1_000_000) {\n")
                .append("                throw new IllegalStateException(\"too much: \" + total);\n")
                .append("            }\n        }\n        lines.add(\"step ").append(i)
                .append(" took \" + total);\n        return total;\n    }\n\n");
        }
        exampleSource = source.append("}\n").toString();
        write(framework.resolve("examples/src/main/java/org/frame/example/OrderExample.java"),
            exampleSource);
        for (int i = 0; i < 45; i++) {
            write(framework.resolve("ui/src/main/java/org/frame/ui/Widget" + i + ".java"),
                "package org.frame.ui;\n\npublic class Widget" + i + " {\n    public void draw() {\n"
                    + "    }\n\n    public int width() {\n        return " + i + ";\n    }\n}\n");
        }
        guide = "# Saving data\n\nHow the framework saves.\n\n## The basics\n\n"
            + "Save through the node. ".repeat(120) + "\n\n## Transactions\n\n"
            + "A transaction wraps one call. ".repeat(40) + "\n\n## Limits\n\n"
            + "Nothing larger than a page. ".repeat(120) + "\n";
        write(framework.resolve("docs/guides/persistence.md"), guide);
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            data.append("{\"entry\": \"").append("x".repeat(3_000)).append("\"}\n");
        }
        write(framework.resolve("catalog.data"), data.toString());

        curator = new KnowledgeCurator(List.of(
            new KnowledgeCurator.Root("project", app, "local"),
            new KnowledgeCurator.Root("framework", framework, "1.0")),
            null, world.resolve("cache"));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private ExpertTools aRolesTools() {
        return new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User("architect", "hand in", 0, null, 0, 0));
    }

    private static String lastLine(String answer) {
        String[] lines = answer.strip().split("\n");
        return lines[lines.length - 1];
    }

    @Test
    void aSourceFileAskedForWholeIsAnsweredWithItsOutlineAndTheWaysToAskForAPart() {
        ExpertTools tools = aRolesTools();
        String address = "framework/examples/src/main/java/org/frame/example/OrderExample.java";

        String first = tools.readFile(address);

        assertThat(first.length()).as("an outline, not the file").isLessThan(exampleSource.length() / 2);
        assertThat(first).contains("public class OrderExample", "public int step7(int amount)",
            "package org.frame.example").doesNotContain("total += line.length()");
        int lines = exampleSource.split("\n", -1).length;
        assertThat(lastLine(first)).as("the last line says what was left out and how to get it")
            .startsWith("[Outline, not the file")
            .contains("body_of OrderExample#<member>", "read_file " + address + ":FROM-TO",
                "the whole file: read_file " + address + ":1-" + lines);

        String whole = tools.readFile(address + ":1-" + lines);
        assertThat(whole).as("the explicit second request returns all of it")
            .contains("total += line.length() + 11;", "A licence.");
        assertThat(tools.bodyOf("OrderExample#step3")).as("one member of a reference type")
            .contains("public int step3(int amount)").doesNotContain("step4");
    }

    @Test
    void aShortFileIsItsOwnAnswer() {
        assertThat(aRolesTools().readFile("project/src/main/java/com/shop/Basket.java"))
            .contains("public interface Basket {", "int size();");
    }

    @Test
    void aFileOfTheReferenceFolderIsOutlinedLikeOneOfTheProject() {
        String outline = aRolesTools().outlineOf(
            "framework/examples/src/main/java/org/frame/example/OrderExample.java");

        assertThat(outline).contains("public class OrderExample", "public int step0(int amount)",
            "(lines ").doesNotContain("no file");
    }

    @Test
    void aDocumentAskedForWholeIsAnsweredWithItsOutlineAndTheSectionTheLastQuestionMatches() {
        ExpertTools tools = aRolesTools();
        String address = "framework/docs/guides/persistence.md";

        tools.docSearch("transaction");
        String answer = tools.readFile(address);

        assertThat(answer.length()).isLessThan(guide.length() / 2 + 1);
        assertThat(answer).contains("The basics", "Transactions", "Limits")
            .doesNotContain("Nothing larger than a page.");
        assertThat(lastLine(answer)).startsWith("[Outline, not the document")
            .contains("doc_section " + address + "#<number or heading>",
                "the whole file: read_file " + address + ":1-");
        assertThat(answer).as("the section the last documentation question matches is quoted")
            .contains("best match for your last documentation question",
                "A transaction wraps one call.");
        assertThat(tools.docSection(address + "#Limits")).contains("Nothing larger than a page.");
    }

    @Test
    void aBuildFileAskedForWholeIsAnsweredWithWhatItDeclares() {
        String answer = aRolesTools().readFile("project/pom.xml");

        assertThat(answer).contains("org.example:ledger").doesNotContain("why this build is");
        assertThat(lastLine(answer)).startsWith("[What the build file declares, not its text")
            .contains("the whole file: read_file project/pom.xml:1-");
    }

    @Test
    void aLongFileWithNoKnownStructureIsAnsweredWithItsFirstLinesCut() {
        ExpertTools tools = aRolesTools();

        String head = tools.readFile("framework/catalog.data");
        assertThat(head.length()).isLessThan(3_000);
        assertThat(lastLine(head)).contains("the whole file: read_file framework/catalog.data:1-");

        String range = tools.readFile("framework/catalog.data:2-4");
        assertThat(range.length()).as("three lines are three lines, however long each is")
            .isLessThan(3 * ExpertTools.RANGE_LINE_CHARS + 500);
        assertThat(lastLine(range)).contains("3 line(s) longer than",
            "read_file framework/catalog.data:2-2");
        assertThat(tools.readFile("framework/catalog.data:2-2").length())
            .as("one line asked for alone is given in full").isGreaterThan(3_000);
    }

    @Test
    void aLargePackageIsListedByNameWithAContinuation() {
        ExpertTools tools = aRolesTools();

        String first = tools.typesIn("org.frame.ui");
        assertThat(first).contains("declares 45 type(s)", "Types 1-45 of 45, by name:",
            "  class Widget0, ", " members\n").doesNotContain(".java:");
        assertThat(lastLine(first)).startsWith("[Names only").contains("public_shape <Type>");

        String small = tools.typesIn("org.frame.example");
        assertThat(small).as("a small package is listed with members and files")
            .contains("OrderExample.java:", "int step11(int);");
    }

    @Test
    void aListingLongerThanAPageSaysHowToAskForTheNextOnes() throws Exception {
        Path framework = world.resolve("framework");
        for (int i = 45; i < 70; i++) {
            write(framework.resolve("ui/src/main/java/org/frame/ui/Widget" + i + ".java"),
                "package org.frame.ui;\n\npublic class Widget" + i + " {\n}\n");
        }
        curator = new KnowledgeCurator(List.of(
            new KnowledgeCurator.Root("project", world.resolve("app"), "local"),
            new KnowledgeCurator.Root("framework", framework, "1.0")),
            null, world.resolve("cache2"));
        ExpertTools tools = aRolesTools();

        String first = tools.typesIn("org.frame.ui");
        assertThat(first).contains("Types 1-60 of 70, by name:");
        assertThat(lastLine(first)).contains("10 not listed yet: types_in org.frame.ui:61");

        String next = tools.typesIn("org.frame.ui:61");
        assertThat(next).contains("Types 61-70 of 70, by name:");
        assertThat(lastLine(next)).startsWith("[Names only.");
    }

    @Test
    void theRunsRecordTellsReferenceLookupsFromTheProjectsAndAnOutlineFromAWholeFile() {
        RunMeter.enable();
        LookupMeter.reset();
        try {
            ExpertTools tools = aRolesTools();
            String address = "framework/examples/src/main/java/org/frame/example/OrderExample.java";
            tools.readFile(address);
            tools.readFile(address + ":1-" + exampleSource.split("\n", -1).length);
            tools.readFile("project/src/main/java/com/shop/Basket.java");
            tools.publicShape("OrderExample");
            tools.publicShape("Basket");

            assertThat(LookupMeter.counts())
                .extracting(c -> c.kind() + " " + c.calls() + " of which reference "
                    + c.referenceCalls())
                .containsExactly("TREE 3 of which reference 2",
                    "WHOLE_FILE 2 of which reference 1");
            assertThat(LookupMeter.counts().get(1).referenceChars())
                .isGreaterThan(exampleSource.length() - 1);
        } finally {
            RunMeter.disable();
            LookupMeter.reset();
        }
    }
}
