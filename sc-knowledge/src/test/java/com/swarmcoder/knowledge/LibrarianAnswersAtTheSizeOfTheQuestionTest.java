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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code lookup_api} answers at the granularity the question asks for, measured against the real
 * reference folder — the 742 Java files and forty documents a worker was pointed at when it spent
 * 105,529 tokens in {@code javap} instead.
 *
 * <p>A worker has about 51,200 tokens of working context and every tool result stays in it until
 * compaction. Before this, the code half of an answer was the first 2,400 characters of whichever
 * file mentioned the question's words most: a license header, the imports and the top of a
 * constructor. Now it is the type's public surface, or the one member the question names, or the
 * whole file when the worker asks for it by path — and every shaped answer says what it left out
 * and how to ask for it.
 *
 * <p>These tests skip, not fail, on a machine without the folder.
 */
class LibrarianAnswersAtTheSizeOfTheQuestionTest {

    static final Path ZEROZ4J = LocalCheckouts.find("zeroz4j");
    static final String UI = "zeroz4j/zerozstack-ui-components/src/main/java/com/zeroz4j/ui/component/";

    @TempDir
    Path repo;
    @TempDir
    Path primerCache;

    Librarian librarian;

    @BeforeEach
    void requireTheReferenceFolder() {
        assumeTrue(Files.isDirectory(ZEROZ4J.resolve("zerozstack-ui-components")),
            "reference folder not present");
        librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(ZEROZ4J), repo, null, primerCache);
    }

    /** The surface tier: "what is on TextField?" gets its signatures, not its constructor's body. */
    @Test
    void aTypeNameGetsThePublicSurfaceAndSaysWhatItLeftOut() {
        String answer = librarian.lookupApi("TextField");

        assertThat(answer)
            .contains("## Source: " + UI + "TextField.java")
            .contains("public class TextField extends AbstractField<TextField, String>")
            .contains("public void setPlaceholder(String placeholder) { … }")
            .contains("/** Sets the grey example text shown while the field is empty. */")
            .contains("@Override protected void setPresentationValue(String value) { … }")
            // No body, no import, no license.
            .doesNotContain("getElement().setAttribute(\"type\", \"text\")")
            .doesNotContain("import org.teavm")
            .doesNotContain("Apache License")
            // And it says so, and how to get the rest.
            .contains("[Signatures only. Left out: 6 method bodies")
            .contains("Ask lookup_api \"TextField setPlaceholder\" for one member with its body")
            .contains("give the path above for the whole file");
        assertThat(answer.length()).isLessThan(4_000);
    }

    /** The region tier: naming a member gets that member with its body. */
    @Test
    void aMemberNameGetsThatMemberWithItsBodyAndTheRestAsSignatures() {
        String answer = librarian.lookupApi("Button addClickListener");

        assertThat(answer)
            .contains("## Source: " + UI + "Button.java")
            .contains("public DomListenerRegistration addClickListener(EventListener<ClickEvent<Button>> listener) {")
            .contains("return addDomEventListener(\"click\", domListener);")
            .contains("// the rest of the public surface, signatures only:")
            .contains("public Button withAriaLabel(String accessibleName) { … }")
            .doesNotContain("addClassName(\"btn\");")   // the no-arg constructor's body stays out
            .contains("[Shown with bodies: the 2 members of Button that mention addClickListener");
    }

    /** The region tier on a question in words: the field that holds the service and the calls. */
    @Test
    void aQuestionAboutHowAClassDoesSomethingGetsTheRegionThatDoesIt() {
        String answer = librarian.lookupApi("how does ChatView connect to the service");

        assertThat(answer)
            .contains("## Source: zeroz4j/zerozstack-examples/chat-events/chat-events-client/src/main/java/com/zeroz4j/example/client/ChatView.java")
            .contains("private final ChatService chatService = new ChatService_Stub();")
            .contains("chatService.sendMessage(text);")
            .contains("List<ChatMessage> history = chatService.getHistory();")
            .contains("… (")                                  // a long constructor, excerpted
            .doesNotContain("msgDiv.addClassName(\"chat\");")  // render() is not about the service
            .contains("[Other files named ChatView: zeroz4j/zerozstack-examples/chat-livesync/");
    }

    /** The whole tier, by size: a file smaller than the note about it goes whole, license aside. */
    @Test
    void aSmallFileGoesWholeWithoutItsLicenseHeader() {
        String answer = librarian.lookupApi("ClickEvent");

        assertThat(answer)
            .contains("## Source: " + UI + "ClickEvent.java")
            .contains("public class ClickEvent<C extends Component> extends ComponentEvent<C> {")
            .contains("super(source, fromClient);")
            .doesNotContain("Licensed under")
            .doesNotContain("Signatures only");
    }

    /** The whole tier, by path: the file, in line ranges, with the next range named. */
    @Test
    void aFileAskedForByPathComesWholeInLineRanges() {
        String first = librarian.lookupApi(UI + "FileUpload.java");
        assertThat(first)
            .startsWith("\n## Source: " + UI + "FileUpload.java")
            .doesNotContain("Documentation for")             // a path is not a question
            .doesNotContain("Licensed under")
            .contains("import org.teavm.jso.dom.html.HTMLElement;")
            .containsPattern("\\[Lines 18-\\d+ of 637\\. For the next part ask lookup_api \""
                + java.util.regex.Pattern.quote(UI) + "FileUpload\\.java lines \\d+-\\d+\"\\.\\]");

        String range = librarian.lookupApi(UI + "FileUpload.java lines 200-260");
        assertThat(range)
            .contains("public FileUpload setAccept(String accept) {")
            .contains("[Lines 200-260 of 637. For the next part ask lookup_api \"" + UI
                + "FileUpload.java lines 261-321\".]");

        // A bare file name is enough.
        assertThat(librarian.lookupApi("Button.java"))
            .contains("## Source: " + UI + "Button.java")
            .contains("import com.zeroz4j.signals.Effect;")
            .contains("addClassName(\"btn\");");
    }

    /** What the question names is never lost: a member declared in the superclass is pointed to. */
    @Test
    void aMemberDeclaredInTheSuperclassIsPointedTo() {
        String answer = librarian.lookupApi("TextField getValue setValue label");

        assertThat(answer)
            .contains("## Source: " + UI + "TextField.java")
            .contains("[Not declared in TextField: getValue, setValue. It extends AbstractField")
            .contains("try lookup_api \"AbstractField getValue\"");
    }

    /**
     * The cap is gone.
     *
     * <p>The curator used to keep 400 source files per root, in path order, and this folder has
     * 681: {@code zerozstack-ui-components}, {@code zerozstack-shared-api} and the store wiring
     * were all past the cap, so the ranking could not see a single UI component class. Only the
     * by-name index could reach one, and only when a question spelled the type exactly. Every file
     * is indexed now, by its outline, and both routes reach it.
     */
    @Test
    void aTypeBeyondTheOldFileCapIsReachableByRankingAsWellAsByName() {
        assertThat(librarian.curator().relevantSources("ClickEvent", 1, 2_400, true))
            .as("the ranking now sees it too")
            .contains("ClickEvent.java");
        assertThat(librarian.lookupApi("ClickEvent"))
            .contains("## Source: " + UI + "ClickEvent.java");
    }

    /**
     * A question naming several types gets each one, in the order asked, while room lasts — and
     * the documentation half yields to make room, since a list of class names is asking what they
     * look like, not for prose about one of them. Whatever does not fit is named, never dropped.
     */
    @Test
    void aQuestionNamingSeveralTypesGetsEachOneAndNamesTheRest() {
        String answer = librarian.lookupApi(
            "com.zeroz4j.ui VerticalLayout CardTitle FormLayout TextField Button import");

        assertThat(answer)
            .contains("## Source: zeroz4j/zerozstack-ui-components/src/main/java/com/zeroz4j/ui/layout/VerticalLayout.java")
            .contains("## Source: " + UI + "CardTitle.java")
            .contains("## Source: zeroz4j/zerozstack-ui-components/src/main/java/com/zeroz4j/ui/layout/FormLayout.java")
            .contains("public void setResponsiveSteps(ResponsiveStep... newSteps) { … }")
            .containsPattern("\\[Also named, not shown for room: (TextField, )?Button")
            .doesNotContain("Not declared in FormLayout")
            .doesNotContain("[truncated]");
        // One documentation section, not two.
        assertThat(answer.split("\n#### ").length - 1).isEqualTo(1);
        assertThat(answer.length()).isLessThanOrEqualTo(6_000);
    }

    /** The worst case in the folder that a worker plausibly asks about: 637 lines, 25 KB. */
    @Test
    void theLargestComponentClassAnswersInUnderAThousandTokens() throws Exception {
        String answer = librarian.lookupApi("FileUpload");
        long fileChars = Files.size(ZEROZ4J.resolve(UI.substring("zeroz4j/".length()) + "FileUpload.java"));

        assertThat(fileChars).isGreaterThan(24_000);
        assertThat(answer)
            .contains("public FileUpload setAccept(String accept) { … }")
            .contains("@FunctionalInterface public interface UploadListener {")
            .contains("void onUploadFinished(String fileName, boolean accepted, String message);")
            .contains("43 private members (637 lines in the file)");
        assertThat(answer.length()).as("about 850 tokens, documentation half included").isLessThan(3_600);
    }

    /**
     * The documentation half: length-normalised scoring. Measured before it: "TextField" was
     * answered by {@code CHANGELOG.md › Added} (31,658 characters) and "Button" by
     * {@code PWA.md › The install button}; a component task's brief spent its whole slice on
     * {@code AGENTS.md › Running the examples}, 14,000 characters of port numbers.
     */
    @Test
    void theSectionThatTalksAboutEverythingDoesNotAnswerAQuestionAboutOneThing() {
        KnowledgeCurator curator = librarian.curator();

        assertThat(curator.relevantDocs("TextField", 2, 3_600))
            .doesNotContain("CHANGELOG.md")
            .contains("zeroz4j/docs/");
        assertThat(curator.relevantDocs("TextField getValue setValue label", 2, 3_600))
            .doesNotContain("CHANGELOG.md");
        // "Button" on its own is answered by a section about buttons. Which one leads moved when
        // the ranking became BM25 over fields: it is the guide that says when to use a button
        // rather than the component reference's entry, because the reference's heading is
        // "Buttons & Navigation" and nothing here stems a plural. Stemming the prose was tried and
        // reverted — it costs more than it buys, because it also collapses "store" and "storage",
        // which are two different things in this framework. What the test is FOR still holds: a
        // release note and a page of port numbers do not answer a question about one component.
        String button = curator.relevantDocs("Button", 2, 3_600);
        assertThat(button)
            .containsPattern("(?i)#### zeroz4j/docs/\\S+ › [^\\r\\n]*(button|component)")
            .doesNotContain("CHANGELOG.md")
            .doesNotContain("Running the examples");
        // The task-relevant slice of a real task description. It used to be 14,000 characters of
        // port numbers ("Running the examples"); it is now the component reference's own entries
        // for laying fields out, with the code that does it.
        assertThat(curator.relevantDocs(
                "Add a signup form Lay the fields out with FormLayout and a Save Button src/main", 4, 3_500))
            .doesNotContain("Running the examples")
            .doesNotContain("CHANGELOG.md")
            .contains("FormLayout");
    }
}
