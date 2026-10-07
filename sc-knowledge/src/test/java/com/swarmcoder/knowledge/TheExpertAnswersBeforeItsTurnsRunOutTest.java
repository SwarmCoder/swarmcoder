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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 37, 2026-09-25: the expert that read the answer and never gave it.
 *
 * <p>A worker implementing a store on EclipseStore asked for real code that creates an
 * {@code EmbeddedStorageManager}. The expert read a store test with exactly that code at its
 * seventh lookup and the persistence guide at its fourteenth, then kept looking — fifteen of its
 * turns spent walking folders one level per call — and was stopped at its thirty-turn cap. The
 * worker was handed "could not be answered", and carried on unaided with the answer sitting in the
 * expert's tool results.
 *
 * <p>Four causes, each pinned here without a live model (the endpoint is
 * {@link ScriptedExpertEndpoint}; the runtime, the tools and the files are real):
 *
 * <ol>
 *   <li><b>No answer at the cap.</b> A session stopped with material in hand now gets one closing
 *       turn, with no lookups, to answer from what its lookups returned — and from nothing else.
 *       When they returned nothing, there is no closing turn and no answer, as before.</li>
 *   <li><b>No clock.</b> Every lookup's result now says how many turns are left as they run out,
 *       and the one before the last says the next turn is for {@code report_done}.</li>
 *   <li><b>One folder per call.</b> {@code list_files} is a tree, single-folder chains joined; a
 *       folder handed to {@code read_file} answers with its tree.</li>
 *   <li><b>The same documentation again.</b> {@code lookup_docs} never hands one session the same
 *       section twice, the way a worker's {@code lookup_api} never has.</li>
 * </ol>
 */
class TheExpertAnswersBeforeItsTurnsRunOutTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CAP = 4;

    @TempDir
    Path world;

    private Path app;
    private Path reference;
    private KnowledgeCurator curator;

    @BeforeEach
    void world() throws Exception {
        app = world.resolve("app");
        reference = world.resolve("ledgerworks");
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(app.resolve("bookshelf-server/src/main/java/com/example/bookshelf/server/BookService.java"),
            "package com.example.bookshelf.server;\n\npublic class BookService { }\n");
        write(reference.resolve("pom.xml"),
            "<project><groupId>com.ledgerworks</groupId><artifactId>ledgerworks</artifactId>"
                + "</project>");
        write(reference.resolve("src/main/java/com/ledgerworks/LedgerSession.java"), """
            package com.ledgerworks;

            /** A session against the ledger. */
            public class LedgerSession {

                public static LedgerSession open(String directory) {
                    return new LedgerSession();
                }

                public void append(Entry entry) {
                }
            }
            """);
        write(reference.resolve("src/main/java/com/ledgerworks/Entry.java"), """
            package com.ledgerworks;

            public record Entry(String what) { }
            """);
        write(reference.resolve("docs/guides/persistence.md"), """
            # Persistence

            ## Opening a ledger session

            Open one per process with LedgerSession.open(directory), keep it for the life of the
            application, and append entries to it. The ledger session is the storage manager.
            """);
        curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local"),
                new KnowledgeCurator.Root("ledgerworks", reference, "1.0")),
            null, world.resolve("cache"));
    }

    // -----------------------------------------------------------------------------------------
    // 1. The closing turn
    // -----------------------------------------------------------------------------------------

    @Test
    void anExpertStoppedAtItsCapHavingReadTheAnswerIsMadeToAnswerFromIt() throws Exception {
        String answer = "LedgerSession session = LedgerSession.open(dir); "
            + "// from ledgerworks/src/main/java/com/ledgerworks/LedgerSession.java";
        // Run 37's shape: the answer is read early, and the model keeps looking anyway. The main
        // session makes CAP requests and one more that is refused at the cap; the next request is
        // the closing turn's, and there the model answers.
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn -> {
            if (turn == 1) {
                return ScriptedExpertEndpoint.Reply.toolCall("read_file",
                    args("path", "ledgerworks/src/main/java/com/ledgerworks/LedgerSession.java"));
            }
            if (turn <= CAP + 1) {
                return ScriptedExpertEndpoint.Reply.toolCall("list_files", args("dir", "ledgerworks"));
            }
            return ScriptedExpertEndpoint.Reply.toolCall("report_done", args("answer", answer));
        })) {
            CloudGate gate = new CloudGate(10_000_000, null);
            ExpertHelp.Answer given = deskOver(endpoint, gate).askExpert(
                "qqzzx wibble frobnicate the whatsit", "I tried a plain field");

            assertThat(given.source())
                .as("an imperfect answer from code the expert actually read beats NONE")
                .isEqualTo(ExpertHelp.Source.MODEL);
            assertThat(given.text())
                .as("the worker is handed the closing turn's answer")
                .contains(answer)
                .as("and told how it was reached, so it checks it against the build")
                .contains("ran out of turns")
                .contains("answer from what its lookups had returned");

            JsonNode closing = JSON.readTree(endpoint.requests.get(CAP + 1));
            String closingText = closing.toString();
            assertThat(closingText)
                .as("the closing turn is handed the file the lookup READ - its real source")
                .contains("public static LedgerSession open(String directory)")
                .as("and told it has no lookups and may name nothing that is not in it")
                .contains("There are no more")
                .contains("from that material ONLY");
            assertThat(toolNames(closing))
                .as("it cannot reach for anything it has not been shown: report_done is its only "
                    + "tool")
                .containsExactly("report_done");
            assertThat(gate.used())
                .as("the closing turn is charged like every other")
                .isGreaterThan(0);
        }
    }

    @Test
    void whenTheLookupsFoundNothingThereIsNoClosingTurnAndNoAnswer() throws Exception {
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn ->
                ScriptedExpertEndpoint.Reply.toolCall("public_shape", args("type", "NoSuchThing")))) {
            ExpertHelp.Answer given = deskOver(endpoint, new CloudGate(10_000_000, null))
                .askExpert("qqzzx wibble frobnicate the whatsit", null);

            assertThat(given.source())
                .as("nothing was found, so there is nothing to answer FROM - still never invented")
                .isEqualTo(ExpertHelp.Source.NONE);
            assertThat(given.text()).contains("ran out of turns");
            assertThat(endpoint.turnsServed())
                .as("no closing turn was opened: the main session's requests only")
                .isEqualTo(CAP + 1);
        }
    }

    @Test
    void whatWasFoundKeepsCodeOverFolderListingsAndDropsTheNothings() throws Exception {
        ExpertTools tools = new ExpertTools(curator, null, null, null);
        tools.listFiles("ledgerworks");
        tools.publicShape("NoSuchThing");
        tools.readFile("ledgerworks/src/main/java/com/ledgerworks/LedgerSession.java");
        tools.readFile("ledgerworks/src/main/java/com/ledgerworks/LedgerSession.java");
        tools.readFile("../outside.txt");

        String all = tools.whatWasFound(100_000);
        assertThat(all)
            .as("what carried material is kept, under a heading naming the lookup")
            .contains("### read_file(ledgerworks/src/main/java/com/ledgerworks/LedgerSession.java)")
            .contains("### list_files(ledgerworks)")
            .as("a 'nothing' and a refusal carry no material and are never kept")
            .doesNotContain("holds no type")
            .doesNotContain("Refused:");
        assertThat(all.split("### read_file", -1))
            .as("the same file read twice is kept once")
            .hasSize(2);
        assertThat(all.indexOf("### list_files")).isLessThan(all.indexOf("### read_file"))
            .as("printed in the order the lookups were made");

        // Room for the file read and not for the listing as well.
        int fileBlock = all.length() - all.indexOf("### read_file");
        String tight = tools.whatWasFound(fileBlock + 10);
        assertThat(tight)
            .as("when it does not all fit, the code an answer is written from wins over a listing")
            .contains("LedgerSession open(")
            .doesNotContain("### list_files");
    }

    // -----------------------------------------------------------------------------------------
    // 2. The clock
    // -----------------------------------------------------------------------------------------

    @Test
    void theCountdownSpeaksAtHalfwayInTheLastFiveTurnsAndOnTheOneBeforeTheLast() {
        assertThat(ExpertTools.countdownNote(10, 30)).isNull();
        assertThat(ExpertTools.countdownNote(15, 30)).contains("Half of your 30 turns are gone");
        assertThat(ExpertTools.countdownNote(24, 30)).isNull();
        assertThat(ExpertTools.countdownNote(25, 30)).contains("5 turns left");
        assertThat(ExpertTools.countdownNote(28, 30)).contains("2 turns left");
        assertThat(ExpertTools.countdownNote(29, 30))
            .contains("Your NEXT turn is your LAST")
            .contains("report_done");
        assertThat(ExpertTools.countdownNote(30, 30))
            .as("nothing after the last turn: the model never sees that result")
            .isNull();
        assertThat(ExpertTools.countdownNote(3, 0))
            .as("a toolbox with no allowance has no clock")
            .isNull();
    }

    @Test
    void theModelIsToldItsNextTurnIsTheLastAndAnAnswerThenIsAnOrdinaryAnswer() throws Exception {
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn -> turn < CAP
                ? ScriptedExpertEndpoint.Reply.toolCall("public_shape", args("type", "LedgerSession"))
                : ScriptedExpertEndpoint.Reply.toolCall("report_done", args("answer", "use open")))) {
            ExpertHelp.Answer given = deskOver(endpoint, new CloudGate(10_000_000, null))
                .askExpert("qqzzx wibble frobnicate the whatsit", null);

            assertThat(endpoint.requests.get(CAP - 1))
                .as("the request carrying turn " + (CAP - 1) + "'s lookup result says the next "
                    + "turn is the last")
                .contains("Your NEXT turn is your LAST");
            assertThat(given.source()).isEqualTo(ExpertHelp.Source.MODEL);
            assertThat(given.text())
                .as("answered inside its allowance: no closing turn, no label")
                .isEqualTo("use open");
            assertThat(endpoint.turnsServed()).isEqualTo(CAP);
        }
    }

    @Test
    void oneNoteATurnHoweverManyLookupsItMakes() {
        ExpertTools tools = new ExpertTools(curator, null, null, null, null, 30);
        tools.onTurn(29);
        String first = tools.listFiles("ledgerworks");
        String second = tools.listFiles("project");

        assertThat(first).contains("Your NEXT turn is your LAST");
        assertThat(second).doesNotContain("Your NEXT turn");
        assertThat(tools.takeSteering())
            .as("and the trace is handed the note once, to show as a NUDGE")
            .hasValueSatisfying(note -> assertThat(note).contains("LAST"));
        assertThat(tools.takeSteering()).isEmpty();
    }

    // -----------------------------------------------------------------------------------------
    // 3. Folders
    // -----------------------------------------------------------------------------------------

    @Test
    void oneListingReachesTheFilesOfAModuleWithTheSingleFolderChainJoined() {
        ExpertTools tools = new ExpertTools(curator, null, null, null);

        String tree = tools.listFiles("project");

        assertThat(tree)
            .as("run 37 walked project/bookshelf-demo-server -> src -> main -> java -> com -> ... "
                + "-> server one call per folder; the chain is one line now")
            .contains("src/main/java/com/example/bookshelf/server/")
            .contains("BookService.java")
            .as("with the file's whole address spelled out once, so joining the lines is shown")
            .contains("project/bookshelf-server/src/main/java/com/example/bookshelf/server/"
                + "BookService.java");
    }

    @Test
    void aTreeThatIsCutIsCutAtItsDeepestLevelAndSaysSo() {
        String tree = curator.listTree("ledgerworks", 4, 3);

        assertThat(tree)
            .as("the top level is all there: breadth first")
            .contains("docs/guides/")
            .contains("pom.xml")
            .contains("src/main/java/com/ledgerworks/")
            .as("and the cut is announced, never silent")
            .contains("only the first 3 entries are shown");
        assertThat(tree).doesNotContain("LedgerSession.java");
    }

    @Test
    void aFolderHandedToReadFileAnswersWithWhatIsInIt() {
        ExpertTools tools = new ExpertTools(curator, null, null, null);

        String read = tools.readFile("ledgerworks/src");

        assertThat(read)
            .contains("is a folder, not a file")
            .contains("LedgerSession.java")
            .contains("Entry.java");
    }

    @Test
    void theLogKeepsTheEndOfALongPathWhichIsThePartThatSaysWhichFile() {
        String path = "project/bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/"
            + "server/BookService.java";
        assertThat(ExpertTools.forTheLog(path))
            .as("run 37's log cut this at 80 characters, which read as a folder")
            .endsWith("BookService.java");
        String longer = "x".repeat(400) + "/TheFileItWas.java";
        assertThat(ExpertTools.forTheLog(longer))
            .hasSizeLessThanOrEqualTo(200)
            .endsWith("TheFileItWas.java");
    }

    // -----------------------------------------------------------------------------------------
    // 4. The same documentation again
    // -----------------------------------------------------------------------------------------

    @Test
    void lookupDocsNeverHandsOneSessionTheSameSectionTwice() {
        Librarian librarian = new Librarian(new Context7Client("https://mcp.context7.com/mcp", null,
            false), null, List.of(reference), app, null, world.resolve("librarian-cache"));
        ExpertTools tools = new ExpertTools(curator, librarian, null, null);

        String first = tools.lookupDocs("ledger session persistence");
        String again = tools.lookupDocs("opening ledger session persistence");

        assertThat(first)
            .as("the first question gets the guide's section")
            .contains("Opening a ledger session");
        assertThat(again)
            .as("a near-neighbour wording does not get it a second time")
            .doesNotContain("keep it for the life of the")
            .startsWith(ExpertTools.NOTHING_NEW_FOR_EXPERT)
            .as("and is told, in the expert's own terms, to answer from what it read")
            .contains("Answer from what you have read")
            .doesNotContain("ask_expert");
    }

    // -----------------------------------------------------------------------------------------

    private ExpertDesk deskOver(ScriptedExpertEndpoint endpoint, CloudGate gate) {
        VllmClient client = new VllmClient(endpoint.baseUrl(), "", "expert-fake",
            ModelQuirks.DEFAULTS.withLabel("expert-fake"));
        return new ExpertDesk(curator, app, List.<ApiContract>of(),
            new ExpertEscalation(client, gate, curator, null, app, List.of(),
                new KoogAgentRuntime(), CAP));
    }

    private static List<String> toolNames(JsonNode request) {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : request.path("tools")) {
            names.add(tool.path("function").path("name").asText());
        }
        return names;
    }

    private static String args(String name, String value) {
        try {
            return JSON.writeValueAsString(JSON.createObjectNode().put(name, value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
