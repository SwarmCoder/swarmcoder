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
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 82, 2026-10-04: the first run on the tree-first tools. The architect still took
 * 119,578 characters from whole files against 16,208 from the tree - and what it read whole was
 * the project's DOCUMENTS (requirements, architecture, a 30,531-character framework primer),
 * because a document had no access but {@code read_file}, and its prompt said so. The planner
 * and the test author read source files whole after asking for their shape, because the only
 * whole-type answer was the file's own text.
 */
class ADocumentIsReadBySectionTest {

    @TempDir
    Path world;

    private Path app;
    private KnowledgeCurator curator;
    private String architecture;
    private String ledger;

    @BeforeEach
    void aProjectWithDocumentsAndAReferenceRoot() throws Exception {
        app = world.resolve("app");
        StringBuilder doc = new StringBuilder("# Architecture\n\nHow the shop is put together.\n\n"
            + "## 1. Modules\n\nThe server and the shared module.\n\n"
            + "### Server\n\nThe server stores baskets in a ledger.\n\n"
            + "```\n# not a heading, a comment in a code block\n```\n\n"
            + "## 2. Persistence\n\n");
        for (int i = 0; i < 100; i++) {
            doc.append("Baskets survive a restart because the ledger is appended to. ");
        }
        doc.append("\n\n## 3. Checkout\n\nA checkout empties the basket.\n");
        architecture = doc.toString();
        write(app.resolve("docs/architecture.md"), architecture);
        write(app.resolve("docs/testing.md"), "# Testing\n\nBaskets are tested through the server.\n");
        StringBuilder code = new StringBuilder("""
            package com.shop.server;

            import java.util.ArrayList;
            import java.util.List;

            /**
             * The ledger of baskets. A long comment that says what a reader of the code does
             * not need in order to see what the code does.
             */
            public class Ledger {

                // the entries, oldest first
                private final List<String> entries = new ArrayList<>();

                /** Appends one entry. */
                public void append(String entry) {
                    entries.add(entry); // "not // a comment"
                }

                public int size() {
                    return entries.size();
                }

                public String describe() {
                    return "ledger // of " + size();
                }
            """);
        for (int i = 0; i < 40; i++) {
            code.append("\n    /** Entry ").append(i).append(" of the ledger, if it has one. */\n")
                .append("    public String entry").append(i).append("() {\n        return entries.get(")
                .append(i).append(");\n    }\n");
        }
        ledger = code.append("}\n").toString();
        write(app.resolve("shop-server/src/main/java/com/shop/server/Ledger.java"), ledger);
        Path framework = world.resolve("framework");
        write(framework.resolve("docs/primer.md"),
            "# Primer\n\n## Storage\n\nA ledger is opened with Ledgers.open.\n\n"
            + "## Wiring\n\nBeans are wired by name.\n");
        curator = new KnowledgeCurator(List.of(
            new KnowledgeCurator.Root("project", app, "local"),
            new KnowledgeCurator.Root("framework", framework, "1")), null, world.resolve("cache"));
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

    @Test
    void anOutlineGivesEveryHeadingItsNumberItsLinesAndItsSize() {
        DocumentOutline outline = DocumentOutline.of(architecture);

        assertThat(outline.sections()).extracting(DocumentOutline.Section::title)
            .as("a # line inside a code block is not a heading")
            .containsExactly("Architecture", "1. Modules", "Server", "2. Persistence",
                "3. Checkout");
        assertThat(outline.sections()).extracting(DocumentOutline.Section::number)
            .containsExactly("1", "1.1", "1.1.1", "1.2", "1.3");
        DocumentOutline.Section modules = outline.sections().get(1);
        assertThat(outline.textOf(modules)).as("a section holds its subsections and stops at "
            + "the next heading of its own level")
            .startsWith("## 1. Modules").contains("### Server").doesNotContain("Persistence");
        assertThat(modules.chars()).isEqualTo(outline.textOf(modules).length());
        assertThat(outline.find("2")).as("the number the heading itself begins with wins over "
            + "the counted one").extracting(DocumentOutline.Section::title)
            .containsExactly("2. Persistence");
        assertThat(outline.find("server")).extracting(DocumentOutline.Section::title)
            .containsExactly("Server");
        assertThat(outline.find("1.3")).extracting(DocumentOutline.Section::title)
            .containsExactly("3. Checkout");
    }

    @Test
    void theDocumentMapNamesEveryProjectDocumentWithItsSectionsAndCountsTheReferenceOnes() {
        String map = new DocumentQueries(curator).outlineOf("");

        assertThat(map).contains("project/docs/architecture.md", "project/docs/testing.md")
            .contains("1.1 1. Modules", "1.2 2. Persistence", "1.3 3. Checkout")
            .contains("Reference material `framework`: 1 document(s)")
            .as("it names; it does not quote").doesNotContain("survive a restart");
        assertThat(new DocumentQueries(curator).outlineOf("framework"))
            .contains("framework/docs/primer.md", "Storage", "Wiring");
    }

    /**
     * Run 85: the test author wrote the section as the outline prints it - number, then heading -
     * seven times out of eleven, was told "no section" each time, and read the documents whole.
     */
    @Test
    void aSectionAskedForAsTheOutlinePrintsItIsThatSection() {
        DocumentQueries documents = new DocumentQueries(curator);
        String byNumber = documents.sectionOf("project/docs/architecture.md#1.3");

        assertThat(documents.sectionOf("project/docs/architecture.md#1.3 3. Checkout"))
            .isEqualTo(byNumber);
        assertThat(documents.sectionOf("project/docs/architecture.md#1.3 Checkout"))
            .isEqualTo(byNumber);
        DocumentOutline outline = DocumentOutline.of(architecture);
        assertThat(outline.find(DocumentOutline.line(outline.sections().get(4))))
            .as("the whole outline line, sizes and all")
            .extracting(DocumentOutline.Section::title).containsExactly("3. Checkout");
        assertThat(outline.find("1.1.1 Server")).extracting(DocumentOutline.Section::title)
            .containsExactly("Server");
        assertThat(documents.sectionOf("project/docs/architecture.md#1.3 Billing"))
            .as("a number with words that are not that section's is still not a section")
            .contains("has no section");
    }

    @Test
    void oneSectionIsReturnedByNumberOrHeadingAndIsAFractionOfTheDocument() {
        DocumentQueries documents = new DocumentQueries(curator);

        String byNumber = documents.sectionOf("project/docs/architecture.md#3");
        String byHeading = documents.sectionOf("docs/architecture.md#Checkout");

        assertThat(byNumber).startsWith("[project/docs/architecture.md:")
            .contains("A checkout empties the basket.").doesNotContain("survive a restart");
        assertThat(byHeading).isEqualTo(byNumber);
        assertThat(byNumber.length()).isLessThan(architecture.length() / 10);
        assertThat(documents.sectionOf("project/docs/architecture.md#Billing"))
            .as("a section that is not there is answered with the ones that are")
            .contains("has no section `Billing`", "2. Persistence");
        assertThat(documents.outlineOf("project/docs/architecture.md"))
            .contains("1.2  2. Persistence  (lines ").contains(" chars)");
    }

    @Test
    void aSearchAnswersWithSectionsAsPlacesNotWithTheirText() {
        String found = new DocumentQueries(curator).search("ledger restart");

        assertThat(found).contains("project/docs/architecture.md:")
            .contains("2. Persistence").contains("doc_section")
            .doesNotContain("because the ledger is appended to");
    }

    @Test
    void aWorkerIsAnsweredWithThePathsItsOwnReadTakes() {
        DocumentQueries worker = DocumentQueries.forAWorkerOf(curator, app);

        assertThat(worker.outlineOf("")).contains("docs/architecture.md - ")
            .doesNotContain("project/docs").contains("/reference/framework");
        assertThat(worker.sectionOf("docs/architecture.md#3")).startsWith("[docs/architecture.md:");
        assertThat(worker.sectionOf("/reference/framework/docs/primer.md#Storage"))
            .startsWith("[/reference/framework/docs/primer.md:").contains("Ledgers.open");
        assertThat(worker.sectionOf("../outside.md#1")).contains("No document");
    }

    @Test
    void aRoleHasTheDocumentQueriesBeforeSearchAndTheyAreCountedAsTheirOwnKind() {
        ExpertTools tools = aRolesTools();
        List<String> names = tools.lookupBindings().stream().map(ToolBinding::name).toList();
        assertThat(names).contains("doc_outline", "doc_section", "doc_search");
        assertThat(names.indexOf("doc_section")).isLessThan(names.indexOf("search"));
        assertThat(names.indexOf("read_file")).isGreaterThan(names.indexOf("doc_search"));

        RunMeter.enable();
        LookupMeter.reset();
        try {
            assertThat(tools.docOutline("")).contains("DOCUMENT MAP");
            assertThat(tools.docSection("project/docs/architecture.md#3"))
                .contains("A checkout empties the basket.");
            assertThat(LookupMeter.counts())
                .extracting(c -> c.role() + " " + c.kind() + " " + c.calls())
                .containsExactly("architect DOCUMENT 2");
            assertThat(LookupMeter.Kind.DOCUMENT.structured()).isTrue();
            assertThat(LookupMeter.Kind.WHOLE_FILE.structured()).isFalse();
        } finally {
            RunMeter.disable();
            LookupMeter.reset();
        }
    }

    @Test
    void aWholeFileIsStillReadWholeAndTheAnswerNamesTheCheaperQuery() {
        ExpertTools tools = aRolesTools();

        String document = tools.readFile("project/docs/architecture.md");
        String source = tools.readFile(
            "project/shop-server/src/main/java/com/shop/server/Ledger.java");
        String small = tools.readFile("project/docs/testing.md");

        assertThat(document).as("nothing is withheld").startsWith(architecture.strip().substring(0, 40))
            .contains("A checkout empties the basket.")
            .contains("doc_section project/docs/architecture.md#<number or heading>")
            .contains("5 sections");
        assertThat(source).contains("public String entry39()")
            .contains("public_shape Ledger lists its 44 members")
            .contains("body_of Ledger#<member>");
        assertThat(small).as("a short file gets no note: it would be most of the answer")
            .doesNotContain("[You read");
    }

    @Test
    void aWholeTypeIsAnsweredAsCodeAndSeveralMembersInOneCall() {
        TreeQueries tree = new TreeQueries(curator);

        String several = tree.bodyOf("com.shop.server.Ledger#append,size");
        assertThat(several).contains("entries.add(entry); // \"not // a comment\"")
            .contains("return entries.size();").doesNotContain("describe");

        String whole = TreeQueries.bodyIn("Ledger.java", """
            package p;

            import java.util.List;

            /** Prose. */
            public class Small {

                // a comment
                private final String name = "a // b";

                /* another */
                int size() {
                    return 1;
                }
            }
            """, "Small", null);
        assertThat(whole).contains("comments and blank lines left out")
            .contains("private final String name = \"a // b\";").contains("return 1;")
            .doesNotContain("Prose").doesNotContain("a comment").doesNotContain("another")
            .doesNotContain("import ").doesNotContain("\n\n");
    }

    @Test
    void theProjectMapSaysHowLargeADocumentIsAndNamesTheOutlineQuery() {
        String map = ProjectMap.of(app, 5_000, curator);

        assertThat(map).contains("doc_outline <document>")
            .contains("architecture.md (" + architecture.length() + " chars)");
    }
}
