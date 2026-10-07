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

import com.swarmcoder.domain.KnowledgeBrief;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documentation a project was given must actually reach the agents working on it (§32).
 *
 * <p>What went wrong. A project was pointed at a framework whose full documentation — forty
 * documents, about 90 KB — sat in its {@code docs/} folder. Ten workers were dispatched on one
 * story. Every one of them spent its whole budget unpacking the framework's jar and running
 * {@code javap} over the class files, and not one wrote a line of code. Three separate faults,
 * each individually sufficient:
 *
 * <ol>
 *   <li>the documentation walk listed the root folder only, and only files NAMED "readme", so
 *       everything under {@code docs/} was invisible;</li>
 *   <li>every renderer dropped any block that did not fit its budget, and each block was read at
 *       a cap LARGER than the budget, so the sections came back empty or as the words
 *       "… (conventions truncated)";</li>
 *   <li>with nothing found, {@code lookup_api} answered "no documentation found — inspect the
 *       code directly with read/exec", which is an instruction to do exactly what they did.</li>
 * </ol>
 */
class ReferenceDocsReachWorkersTest {

    @TempDir
    Path repo;
    @TempDir
    Path framework;
    @TempDir
    Path primerCache;

    private void writeFramework() throws Exception {
        Files.writeString(framework.resolve("README.md"),
            "# DemoStack\nA pure-Java web framework.\n" + "filler ".repeat(1_500));
        Files.createDirectories(framework.resolve("docs/guides"));
        Files.writeString(framework.resolve("docs/UI_COMPONENTS.md"), """
            # DemoStack UI components

            ## FormLayout
            FormLayout arranges labelled fields in columns.
            `new FormLayout()`, then `addFormItem(component, "Label")`.
            Call `setResponsiveSteps(...)` to control the column count.

            ## Button
            Button takes a caption and a click listener: `new Button("Save", event -> save())`.
            """);
        Files.writeString(framework.resolve("docs/guides/persistence.md"), """
            # Persistence
            ## Saving a record
            Call `store.save(entity)` inside a transaction opened with `store.begin()`.
            """);
    }

    private Task task(String title, String instructions) {
        return new Task(UUID.randomUUID(), 1, title, instructions, Set.of("src/main"),
            Set.of("src/main"), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    private Librarian librarian() {
        return new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(framework), repo, null, primerCache);
    }

    /** Fault 1: documentation folders are walked, at depth, not just the root's README. */
    @Test
    void documentationInsideDocsFoldersIsFoundAtAnyDepth() throws Exception {
        writeFramework();
        KnowledgeCurator curator = librarian().curator();

        assertThat(curator.documentCount()).isEqualTo(3);
        assertThat(curator.documentationMap(2_000))
            .contains("docs/UI_COMPONENTS.md")
            .contains("docs/guides/persistence.md")
            .contains("DemoStack UI components");   // the title, so the worker knows what it is
    }

    /** Only real documentation: markdown buried in source trees is not documentation. */
    @Test
    void markdownScatteredThroughTheSourceTreeIsNotTreatedAsDocumentation() {
        assertThat(KnowledgeCurator.isDocumentation("README.md")).isTrue();
        assertThat(KnowledgeCurator.isDocumentation("AGENTS.md")).isTrue();
        assertThat(KnowledgeCurator.isDocumentation("docs/UI_COMPONENTS.md")).isTrue();
        assertThat(KnowledgeCurator.isDocumentation("docs/guides/persistence.md")).isTrue();
        assertThat(KnowledgeCurator.isDocumentation("module/documentation/api.md")).isTrue();
        assertThat(KnowledgeCurator.isDocumentation("src/main/java/notes.md")).isFalse();
        assertThat(KnowledgeCurator.isDocumentation("docs/UI_COMPONENTS.java")).isFalse();
    }

    /**
     * Fault 2, the one that made the whole channel silent. Every renderer was called with a
     * budget SMALLER than its own per-block read cap, and dropped what did not fit — so the
     * first block always overflowed and nothing at all came out.
     */
    @Test
    void aSectionSmallerThanItsFirstBlockStillCarriesContent() throws Exception {
        writeFramework();
        KnowledgeCurator curator = librarian().curator();

        // 3,000 chars of budget against a 6,000-char read cap: the exact old failure.
        String conventions = curator.conventions(3_000);
        assertThat(conventions).contains("A pure-Java web framework");
        assertThat(conventions.length()).isGreaterThan(2_000).isLessThanOrEqualTo(3_000);

        // Same shape for sources, where the read cap is 4,500 and lookup_api's budget was 3,000.
        Files.createDirectories(repo.resolve("src/main/java"));
        Files.writeString(repo.resolve("src/main/java/Widget.java"),
            "public class Widget { public void render() {} }\n" + "// filler\n".repeat(600));
        curator.invalidateInventory();
        assertThat(curator.relevantSources("Widget render", 2, 3_000))
            .contains("public void render()");
    }

    /** Fault 3: the brief carries the catalogue AND the slice this task is actually about. */
    @Test
    void theBriefNamesEveryDocumentAndCarriesTheRelevantSections() throws Exception {
        writeFramework();

        KnowledgeBrief brief = librarian().assembleBrief(repo,
            task("Add a signup form", "Lay the fields out with FormLayout and a Save Button"));
        String text = brief.renderedMarkdown();

        // The catalogue: what exists, by name.
        assertThat(text).contains("docs/UI_COMPONENTS.md").contains("docs/guides/persistence.md");
        // The slice: the section that answers this task, in full.
        assertThat(text).contains("addFormItem(component, \"Label\")");
        // And it says, in the prefix, not to go decompiling instead.
        assertThat(text).contains("lookup_api");
        assertThat(text.length()).isLessThanOrEqualTo(16_100);
    }

    /**
     * The prefix-cache invariant. The brief sits in the shared prompt prefix, which must be
     * byte-identical for every worker of a group, so nothing in its assembly may depend on
     * file-system walk order, hash-set iteration, or the clock.
     */
    @Test
    void theBriefIsByteIdenticalWhenAssembledAgain() throws Exception {
        writeFramework();
        Librarian librarian = librarian();
        Task task = task("Add a signup form", "FormLayout and a Save Button");

        String first = librarian.assembleBrief(repo, task).renderedMarkdown();
        String second = librarian.assembleBrief(repo, task).renderedMarkdown();
        // A second Librarian over the same roots — a fresh walk, no warm cache.
        String third = librarian().assembleBrief(repo, task).renderedMarkdown();

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    /** lookup_api answers from the documentation FIRST, and the answer is real content. */
    @Test
    void lookupApiAnswersFromTheReferenceDocumentation() throws Exception {
        writeFramework();

        String answer = librarian().lookupApi("FormLayout");

        assertThat(answer)
            .contains("addFormItem")
            .contains("setResponsiveSteps")
            .contains("UI_COMPONENTS.md");
    }

    /**
     * The miss message no longer sends the worker to a decompiler. When documents exist it names
     * them; when none do it says so and bounds how long to spend reverse-engineering.
     */
    @Test
    void aMissNamesWhatExistsInsteadOfSendingTheWorkerToADecompiler() throws Exception {
        writeFramework();

        String withDocs = librarian().lookupApi("zzzznothingmatchesthis");
        assertThat(withDocs)
            .contains("docs/UI_COMPONENTS.md")
            .doesNotContain("Inspect the code directly");

        Librarian bare = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(), repo, null, primerCache);
        String withoutDocs = bare.lookupApi("zzzznothingmatchesthis");
        assertThat(withoutDocs)
            .contains("no reference documentation")
            .contains("failing compile is far cheaper");
    }

    /**
     * A long section is scored WHOLE and trimmed only when rendered. Scoring the trimmed text
     * meant a big component reference was cut before the entry being asked about, so it scored
     * zero for its own subject and a passing mention elsewhere outranked it.
     */
    @Test
    void aLongSectionIsScoredOnAllOfItsText() {
        String big = "## Component reference\n" + "unrelated prose. ".repeat(400)
            + "\nDataTable renders rows and columns.\n";
        List<KnowledgeCurator.DocSection> sections =
            KnowledgeCurator.sectionsOf("demo/BIG.md", big);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).body()).contains("DataTable renders rows");
        assertThat(sections.get(0).body().length()).isGreaterThan(3_500);
    }

    /** A markdown heading inside a fenced code block must not split a section. */
    @Test
    void headingsInsideCodeFencesDoNotSplitSections() {
        List<KnowledgeCurator.DocSection> sections = KnowledgeCurator.sectionsOf("demo/D.md", """
            # Title
            Intro.
            ```bash
            # this is a shell comment, not a heading
            echo hi
            ```
            Still the same section.
            """);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).isEqualTo("Title");
        assertThat(sections.get(0).body()).contains("Still the same section");
    }

    /**
     * Repetition is not relevance: the longest section must not outbid the one that answers.
     *
     * <p><b>Measured, on 2026-09-02.</b> A worker asked the documentation four well-formed
     * questions about how to handle a button click. Every one came back with the same two
     * enormous sections — a changelog release and a page about which ports the example apps
     * listen on — while a reference document in the same folder named the exact classes it was
     * asking about. It said twice, in its own words, that the search kept giving it the same
     * thing, then unpacked the framework's jars and read the class files with {@code javap},
     * where it got its answer in one call. 121 turns, 105,529 tokens, nothing produced.
     *
     * <p>The cause was one number. A query term could count fifteen times inside a single
     * section, so a section fifteen times longer scored fifteen times higher on words it merely
     * happened to contain. Three is the cap now, which is what the file scorer had always used.
     */
    @Test
    void aLongSectionDoesNotOutbidTheSectionThatAnswersTheQuestion() throws Exception {
        writeFramework();
        // The shape that beat everything: one huge section repeating the query's words while
        // answering nothing.
        Files.writeString(framework.resolve("CHANGELOG.md"),
            "# Changelog\n\n## Added\n"
            + "A button was added. The click handler was changed. The listener now fires. "
                .repeat(40));
        // And the short, precise entry that is the actual answer.
        Files.writeString(framework.resolve("docs/EVENTS.md"), """
            # Events

            ## ClickEvent and the click listener
            `button.addClickListener(event -> save())` hands you a `ClickEvent`.
            """);
        KnowledgeCurator curator = librarian().curator();

        String answer = curator.relevantDocs("button click listener ClickEvent", 2, 4_000);

        assertThat(answer)
            .as("the precise entry must be first, not the changelog")
            .contains("docs/EVENTS.md")
            .contains("addClickListener");
        assertThat(answer.indexOf("docs/EVENTS.md"))
            .as("ranked above the long section, not merely present")
            .isLessThan(answer.indexOf("CHANGELOG.md") < 0
                ? Integer.MAX_VALUE : answer.indexOf("CHANGELOG.md"));
    }

    /**
     * One answer carries prose AND real code, because a worker about to write needs both.
     *
     * <p>Documentation used to return early: any section that scored at all ended the lookup, and
     * against a multi-word question some section always scores. So the source channel was
     * unreachable in practice, and the reference folder's Java files — 681 of them in the folder
     * this was measured on — were never returned to anyone. In the run that produced the stall
     * guard, the worker asked for a client view that calls a remote service and got a page about
     * example ports; the source channel, had it been reached, held two example client views doing
     * precisely that.
     */
    @Test
    void lookupApiReturnsRealCodeAlongsideTheProse() throws Exception {
        writeFramework();
        Files.createDirectories(repo.resolve("src/main/java"));
        Files.writeString(repo.resolve("src/main/java/SignupForm.java"), """
            public class SignupForm {
                void build() {
                    FormLayout layout = new FormLayout();
                    layout.addFormItem(new TextField(), "Email");
                }
            }
            """);

        // Three terms, because the file scorer only opens a file's body for a query of more than
        // two words unless the PATH already matched — a cost guard, and a sharp edge worth knowing.
        String answer = librarian().lookupApi("FormLayout addFormItem TextField");

        assertThat(answer)
            .as("the prose")
            .contains("UI_COMPONENTS.md")
            .as("and a real file using it")
            .contains("SignupForm.java")
            .contains("layout.addFormItem(new TextField()");
    }

    /**
     * The brief's "real code, use these APIs" heading must actually be followed by code.
     *
     * <p><b>Measured on the owner's own project, 2026-09-02.</b> That heading, in every worker's
     * prefix, was followed by two markdown files inside a {@code ```java} fence — a page of
     * prompt templates and a plan for rewriting the documentation — costing 3,431 tokens, 6.7%
     * of the whole working context, with not one line of Java in it. The cause was one missing
     * argument: the channel was called without {@code codeOnly}, so documents competed with code
     * and won. That is the exact defect the {@code codeOnly} overload was written for, applied to
     * {@code lookup_api}, and never applied here.
     */
    @Test
    void theBriefsSourceChannelCarriesCodeAndNeverDocumentation() throws Exception {
        writeFramework();
        // A document that says the query's words far more often than the class that answers it.
        Files.writeString(framework.resolve("docs/AGENT_PROMPTS.md"),
            "# Agent prompts\n\n## Building a signup form\n"
            + "Ask the agent for a FormLayout with addFormItem and a TextField. ".repeat(60));
        Files.createDirectories(repo.resolve("src/main/java"));
        Files.writeString(repo.resolve("src/main/java/SignupForm.java"), """
            public class SignupForm {
                void build() {
                    FormLayout layout = new FormLayout();
                    layout.addFormItem(new TextField(), "Email");
                }
            }
            """);

        String text = librarian().assembleBrief(repo,
            task("Add a signup form", "FormLayout addFormItem TextField")).renderedMarkdown();
        String sources = text.substring(text.indexOf("real code, use these APIs"));

        assertThat(sources)
            .as("the source channel is for code")
            .contains("SignupForm.java")
            .doesNotContain("AGENT_PROMPTS.md");
    }

    /**
     * Release history and contribution guides stay out of the prefix, and stay in the search.
     *
     * <p>A changelog's "Added" section legitimately mentions every subject the framework has, so
     * against a real task description — a dozen terms about loans, shelves and due dates — it
     * matches more DISTINCT terms than any focused section and wins on merit. Capping per-term
     * hits at three narrowed that and did not close it. Measured on the owner's project: the
     * whole task-relevant documentation slice in every worker's prefix was one trimmed chunk of
     * {@code CHANGELOG.md}, 873 tokens, holding nothing the worker could act on.
     */
    @Test
    void theBriefSkipsReleaseHistoryButLookupApiStillFindsIt() throws Exception {
        writeFramework();
        Files.writeString(framework.resolve("CHANGELOG.md"), """
            # Changelog

            ## Added
            The zzzunique marker was added to FormLayout in this release.
            """);

        String brief = librarian().assembleBrief(repo,
            task("Add a signup form", "FormLayout zzzunique marker")).renderedMarkdown();
        assertThat(brief)
            .as("the prefix does not quote a release note")
            .doesNotContain("The zzzunique marker was added");
        // Nor does the catalogue spend a line on it any more (2026-09-03). The catalogue is now
        // ordered by usefulness and carries a one-line summary of each page, which costs more per
        // line and buys a worker the ability to choose; process material earns none of that. It is
        // not hidden — the search still reaches it when nothing else can answer.
        assertThat(brief)
            .as("the catalogue lists what can be followed, not what happened in a release")
            .doesNotContain("CHANGELOG.md");

        assertThat(librarian().lookupApi("zzzunique"))
            .as("nothing is hidden — the worker can still ask for it")
            .contains("CHANGELOG.md");
    }

    /**
     * The reference README's opening does not ride in every worker's prefix.
     *
     * <p>{@code conventions()} reads the HEAD of each root's README, and the head of a README is
     * its pitch. Measured against the owner's reference folder it was 623 tokens of "a technical
     * thesis", "Total Cost of Ownership" and "AI Context Collapse" — and every fact of substance
     * in it was already in the worker's PROJECT_CONSTRAINTS twenty lines above, stated as a rule
     * it must obey rather than a claim it may believe.
     *
     * <p>What a worker loses: nothing it cannot get. The README is still named in the document
     * catalogue, any section of it that answers the task can still be chosen by the slice, and
     * {@code lookup_api} still returns the whole thing. The channel itself is kept for the chat
     * analyst, whose budget is far larger and who is shown no rules segment.
     */
    @Test
    void theBriefDoesNotCarryTheReferenceReadmesOpeningPitch() throws Exception {
        writeFramework();

        String brief = librarian().assembleBrief(repo,
            task("Add a signup form", "FormLayout and a Save Button")).renderedMarkdown();

        // The 623-token pitch is gone; what remains is the catalogue's one line, which is the
        // README's first sentence and is what tells a worker whether to open it at all.
        assertThat(brief).doesNotContain("filler filler filler");
        assertThat(brief)
            .as("the README is still catalogued by name, one lookup_api call away")
            .contains("README.md")
            .as("with one line saying what it is")
            .contains("README.md — DemoStack: A pure-Java web framework.");
        assertThat(brief.length()).isLessThan(16_100);
        assertThat(librarian().curator().conventions(3_000))
            .as("the channel still exists for the chat analyst")
            .contains("A pure-Java web framework");
    }

    /**
     * Length-normalised scoring, on top of the per-term cap. The cap narrowed the gap but did not
     * close it: a section that talks about everything still names more DIFFERENT query terms than
     * a section that talks about one thing. Measured on the real folder after the cap went in,
     * "TextField" was still answered by the changelog's 31,000-character "Added" section and a
     * component task's brief still spent its whole documentation slice on a 14,000-character page
     * of port numbers. A section longer than can be shown whole now pays in proportion to how much
     * of it the worker would never see.
     */
    @Test
    void aSectionThatTalksAboutEverythingDoesNotOutbidTheOneAboutTheThingAsked() throws Exception {
        writeFramework();
        // 17,000 characters under a heading that names two of the question's words, mentioning
        // every other word a few times each, in passing.
        String everything = "Fixed a layout bug. The form now validates. A button was restyled. "
            + "The listener API changed. A click no longer bubbles. TextField got a label. ";
        Files.writeString(framework.resolve("CHANGELOG.md"),
            "# Changelog\n\n## Added: button and layout changes\n"
            + (everything + "filler text of no relevance ".repeat(6)).repeat(50));
        // 200 characters that answer it.
        Files.writeString(framework.resolve("docs/FIELDS.md"), """
            # Fields

            ## TextField
            `new TextField().withLabel("Email")` — a TextField with a label. Add a click listener
            to the form's button to read it.
            """);
        KnowledgeCurator curator = librarian().curator();

        List<KnowledgeCurator.ScoredSection> ranked =
            curator.scoreSections("TextField label click listener layout form button");
        assertThat(ranked).hasSizeGreaterThan(1);
        assertThat(List.of(ranked.get(0).value().address(), ranked.get(1).value().address()))
            .as("the two pages that answer take the first two places, in either order")
            .allSatisfy(address -> assertThat(address)
                .matches(".*docs/(UI_COMPONENTS|FIELDS)\\.md"));
        assertThat(curator.relevantDocs("TextField label click listener layout form button", 2, 4_000))
            .contains("docs/FIELDS.md")
            .doesNotContain("CHANGELOG.md");
    }

    /**
     * A section about the subject beats a section that mentions it in passing.
     *
     * <p>This used to be a tie-break: a one-word question scored every section whose heading named
     * the word the same, and the tie went to whichever said it most often. BM25 makes the counting
     * unnecessary — a section that also carries the question's other words wins on merit — but it
     * does not stem, so a question about a "Button" does not match a heading that says "Buttons".
     * That is why the question here has more than one word in it, which is what a worker's
     * questions actually look like.
     */
    @Test
    void aSectionAboutTheSubjectBeatsOneThatMentionsItInPassing() throws Exception {
        writeFramework();
        Files.writeString(framework.resolve("docs/A_INSTALL.md"), """
            # Installing

            ## The install button
            Browsers show an install button once the manifest is served. Wire the button to prompt().
            """);
        Files.writeString(framework.resolve("docs/Z_COMPONENTS.md"), """
            # Components

            ## Buttons
            `new Button("Save")`. A Button takes a caption. Button.addClickListener wires a click.
            Button variants: a primary button, a ghost button, a link button, an icon button.
            """);
        KnowledgeCurator curator = librarian().curator();

        assertThat(curator.relevantDocs("Button caption addClickListener variants", 2, 4_000))
            .as("a section whose subject is the button")
            .containsPattern("› Button")
            .as("and not the one that mentions a button while explaining installation")
            .doesNotContain("A_INSTALL.md");
    }
}
