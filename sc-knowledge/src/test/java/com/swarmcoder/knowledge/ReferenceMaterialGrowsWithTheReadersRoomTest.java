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
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.ModelShapes;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ExpertHelp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reference material a model is handed grows with that model's own working room (2026-09-25).
 *
 * <p>The workers moved from Qwen at 51,200 tokens of room to DeepSeek V4 Flash at 262,144, and every
 * cap on what the librarian and the help desk hand out was still the Qwen figure. These tests pin
 * the two halves of the change: at the baseline room every answer is byte-for-byte what it was, and
 * in the DeepSeek room the same question gets proportionally more — whole documentation sections
 * instead of their first 3,500 characters, a lookup answer past 6,000 characters, a tool result and
 * an expert answer past their old cuts. And they pin that a reader is sized by ITS OWN room: a
 * lookup made for the architect or the expert is not the workers' size.
 */
class ReferenceMaterialGrowsWithTheReadersRoomTest {

    private static final MaterialBudget DEEPSEEK =
        MaterialBudget.of(ModelShapes.get("deepseek-v4-flash-ds4"));

    /** A marker 6,000 characters into one long guide section: past 3,500, inside 7,680. */
    private static final String DEEP_IN_THE_SECTION = "DEEPINTHESECTIONMARKER";

    /** A marker 15,000 characters into one long source file: past 6,000, inside 30,720. */
    private static final String DEEP_IN_THE_FILE = "DEEPINTHEFILEMARKER";

    @TempDir
    Path world;

    private Path app;
    private Path reference;

    @BeforeEach
    void aProjectAndAReferenceFolderWithLongMaterial() throws Exception {
        app = world.resolve("app");
        reference = world.resolve("widgetlib");
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(reference.resolve("pom.xml"),
            "<project><groupId>com.widgetlib</groupId><artifactId>widgetlib</artifactId></project>");

        StringBuilder guide = new StringBuilder("# Rendering widgets\n\n");
        String sentence = "To render a widget layout, the renderer walks the widget tree and lays "
            + "out each widget in order before it paints. ";
        while (guide.length() < 6_000) {
            guide.append(sentence);
        }
        guide.append(DEEP_IN_THE_SECTION).append(". ");
        while (guide.length() < 12_000) {
            guide.append(sentence);
        }
        guide.append("\n\n# Styling widgets\n\nA widget layout is styled with a theme.\n");
        write(reference.resolve("docs/rendering.md"), guide.toString());

        StringBuilder source = new StringBuilder("""
            package com.widgetlib;

            /** Renders a widget layout. */
            public class WidgetRenderer {
            """);
        int n = 0;
        while (source.length() < 15_000) {
            source.append("    public void renderWidgetLayout").append(n++)
                .append("(Object widget) { /* render the widget layout step ").append(n)
                .append(" */ }\n");
        }
        source.append("    public void ").append(DEEP_IN_THE_FILE).append("() { }\n");
        while (source.length() < 24_000) {
            source.append("    public void layoutWidget").append(n++).append("() { }\n");
        }
        source.append("}\n");
        write(reference.resolve("src/main/java/com/widgetlib/WidgetRenderer.java"),
            source.toString());
    }

    // -- the documentation channel -------------------------------------------------------------

    @Test
    void atTheBaselineASectionIsStillTrimmedAt3500() {
        String answer = curator().relevantDocs("render widget layout", 2, 4_000);

        assertThat(answer)
            .contains("Rendering widgets")
            .contains("(section trimmed)")
            .doesNotContain(DEEP_IN_THE_SECTION);
    }

    @Test
    void aCallerWithABiggerBudgetGetsTheWholeSection() {
        // Two sections in 20,000 characters is 10,000 each: the whole 12,000-character section is
        // still trimmed, but not before the marker 6,000 characters in.
        String answer = curator().relevantDocs("render widget layout", 2, 20_000);

        assertThat(answer).contains(DEEP_IN_THE_SECTION);
    }

    // -- lookup_api ----------------------------------------------------------------------------

    @Test
    void aWorkerLookupAtTheBaselineIsTheOld6000Characters() {
        String answer = librarian().lookupApi("render widget layout renderer");

        assertThat(answer.length()).isLessThanOrEqualTo(6_000 + "\n[truncated]".length());
        assertThat(answer).doesNotContain(DEEP_IN_THE_SECTION);
    }

    @Test
    void aWorkerLookupInTheDeepSeekRoomIsProportionallyBigger() {
        Librarian librarian = librarian().sizedFor(DEEPSEEK);

        String answer = librarian.lookupApi("render widget layout renderer");

        assertThat(answer.length())
            .isGreaterThan(6_000)
            .isLessThanOrEqualTo(30_720 + "\n[truncated]".length());
        assertThat(answer)
            .as("the documentation half is two whole sections at 7,680 each, not two first 3,500s")
            .contains(DEEP_IN_THE_SECTION);
    }

    @Test
    void aLookupMadeForAnotherReaderIsThatReadersSize() {
        Librarian baseline = librarian();
        Librarian scaled = librarian().sizedFor(DEEPSEEK);
        String query = "render widget layout renderer";

        assertThat(scaled.lookupApi(query, MaterialBudget.BASELINE))
            .as("an architect or expert on the generic cloud shape is answered at its own size, "
                + "not the workers'")
            .isEqualTo(baseline.lookupApi(query));
    }

    // -- the brief -----------------------------------------------------------------------------

    @Test
    void theBriefsCuratedKnowledgeGrowsWithTheRoom() {
        List<KnowledgeDoc> docs = List.of(doc("First rule"), doc("Second rule"), doc("Third rule"));
        Task task = task();

        String atBaseline = new Librarian(null, null, List.of(), app, null,
            world.resolve("cache-a"), () -> docs).assembleBrief(app, task).renderedMarkdown();
        String inTheRoom = new Librarian(null, null, List.of(), app, null,
            world.resolve("cache-b"), () -> docs).sizedFor(DEEPSEEK)
            .assembleBrief(app, task).renderedMarkdown();

        assertThat(atBaseline)
            .as("5,000 characters holds one 3,000-character rule")
            .contains("First rule")
            .doesNotContain("Third rule")
            .contains("curated knowledge truncated");
        assertThat(inTheRoom)
            .as("25,600 characters holds all three")
            .contains("First rule").contains("Second rule").contains("Third rule")
            .doesNotContain("curated knowledge truncated");
    }

    // -- the help desk -------------------------------------------------------------------------

    @Test
    void anExpertToolResultIsSizedByTheExpertsRoom() {
        String address = "widgetlib/src/main/java/com/widgetlib/WidgetRenderer.java";

        String atBaseline = new ExpertTools(curator(), null, null, null).readFile(address);
        String inTheRoom = new ExpertTools(curator(), null, null, null, DEEPSEEK).readFile(address);

        assertThat(atBaseline.length()).isLessThanOrEqualTo(6_000 + 30);
        assertThat(atBaseline).doesNotContain(DEEP_IN_THE_FILE);
        assertThat(inTheRoom).contains(DEEP_IN_THE_FILE);
    }

    @Test
    void theExpertKnowsItsOwnRoom() {
        VllmClient onTheSpark = new VllmClient("http://127.0.0.1:1", "", "deepseek-v4-flash",
            ModelShapes.get("deepseek-v4-flash-ds4"));
        VllmClient onTheCloud = new VllmClient("http://127.0.0.1:1", "", "generic", true);

        assertThat(new ExpertEscalation(onTheSpark, new CloudGate(0, null), curator(), null, app,
            List.of()).room().workingContextTokens()).isEqualTo(262_144);
        assertThat(new ExpertEscalation(onTheCloud, new CloudGate(0, null), curator(), null, app,
            List.of()).room().atBaseline()).isTrue();
    }

    @Test
    void anEscalatedAnswerIsSizedByTheWorkersRoom() {
        String longAnswer = "x".repeat(30_000);
        String question = "qqzzx wibble frobnicate the whatsit";

        ExpertHelp.Answer atBaseline = new ExpertDesk(curator(), app, List.<ApiContract>of(),
            (q, context) -> longAnswer).askExpert(question, null);
        ExpertHelp.Answer inTheRoom = new ExpertDesk(curator(), app, List.<ApiContract>of(),
            (q, context) -> longAnswer).sizedFor(DEEPSEEK).askExpert(question, null);

        assertThat(atBaseline.source()).isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(atBaseline.text().length()).isLessThanOrEqualTo(4_000 + 3);
        assertThat(inTheRoom.text().length())
            .isGreaterThan(20_000)
            .isLessThanOrEqualTo(20_480 + 3);
    }

    // -- the primer ----------------------------------------------------------------------------

    @Test
    void aPrimerDistilledFromMoreIsCachedUnderItsOwnKey() {
        KnowledgeCurator onTheCloud = new KnowledgeCurator(roots(),
            new VllmClient("http://127.0.0.1:1", "", "generic", true), world.resolve("p1"));
        KnowledgeCurator onTheSpark = new KnowledgeCurator(roots(),
            new VllmClient("http://127.0.0.1:1", "", "deepseek-v4-flash",
                ModelShapes.get("deepseek-v4-flash-ds4")), world.resolve("p2"));

        assertThat(onTheCloud.primerRecipe())
            .as("the baseline keeps the key every primer on disk was cached under")
            .isEqualTo("v2");
        assertThat(onTheSpark.primerRecipe()).isEqualTo("v2-in133120");
    }

    // -- helpers -------------------------------------------------------------------------------

    private List<KnowledgeCurator.Root> roots() {
        return List.of(new KnowledgeCurator.Root("project", app, "local"),
            new KnowledgeCurator.Root("widgetlib", reference, "1.0"));
    }

    private KnowledgeCurator curator() {
        return new KnowledgeCurator(roots(), null, world.resolve("cache"));
    }

    private Librarian librarian() {
        return new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(reference), app, null, world.resolve("librarian-cache"));
    }

    private static KnowledgeDoc doc(String title) {
        return new KnowledgeDoc(UUID.randomUUID(), null, title.toLowerCase().replace(' ', '-'),
            title, "Follow this rule. ".repeat(170), "ACTIVE", "human", Instant.now(),
            Instant.now());
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "render a widget", "render the widget layout",
            Set.of("src/main"), Set.of("src/main"), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
