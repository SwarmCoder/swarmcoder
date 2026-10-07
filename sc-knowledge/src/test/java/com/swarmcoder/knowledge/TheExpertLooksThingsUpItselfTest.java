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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ExpertHelp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The expert is an agent session with tools now, not one chat call — this is what that buys and
 * what it must never do.
 *
 * <p>Every test drives the REAL path: the real {@code KoogAgentRuntime}, the real
 * {@link ExpertTools} executing against real files on disk, the real {@link CloudGate}, and a
 * scripted local HTTP endpoint standing in for the model ({@link ScriptedExpertEndpoint}). Nothing
 * here reaches a paid model and nothing here may ever be changed so that it does.
 *
 * <p>Four things are proved, and they are the four that decide whether this is an improvement or a
 * new way to be confidently wrong:
 *
 * <ol>
 *   <li>the expert's lookups actually run, against this project's real material, and its final
 *       {@code report_done} text is what the worker is handed;</li>
 *   <li>what it looked up is on the worker's help record, so the judge and the operator can tell a
 *       looked-up answer from a recalled one;</li>
 *   <li>a read outside the project and its reference folders is refused, by address, before
 *       anything touches the disk;</li>
 *   <li>a session that reaches no answer says so — it never fabricates one.</li>
 * </ol>
 */
class TheExpertLooksThingsUpItselfTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path world;

    private Path app;
    private Path reference;
    private KnowledgeCurator curator;

    /**
     * One project and one reference module with a real type in it, so the structural tools have
     * something true to answer with — and so the question below is one the desk's own free tiers
     * cannot answer, which is what makes it escalate at all.
     */
    @BeforeEach
    void world() throws Exception {
        app = world.resolve("app");
        reference = world.resolve("ledgerworks");
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(reference.resolve("pom.xml"),
            "<project><groupId>com.ledgerworks</groupId><artifactId>ledgerworks</artifactId>"
                + "</project>");
        write(reference.resolve("src/main/java/com/ledgerworks/LedgerSession.java"), """
            package com.ledgerworks;

            /** A session against the ledger. */
            public class LedgerSession {

                public void append(Entry entry) {
                }

                public void commit() {
                }
            }
            """);
        write(reference.resolve("src/main/java/com/ledgerworks/Entry.java"), """
            package com.ledgerworks;

            public record Entry(String what) { }
            """);
        curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local"),
                new KnowledgeCurator.Root("ledgerworks", reference, "1.0")),
            null, world.resolve("cache"));
    }

    // -----------------------------------------------------------------------------------------

    @Test
    void theExpertLooksTwoThingsUpAndTheWorkerGetsWhatItFoundNotWhatItRemembered() throws Exception {
        String answer = "Call `session.append(new Entry(payslip))` then `session.commit()`.\n"
            + "import com.ledgerworks.LedgerSession;\nimport com.ledgerworks.Entry;";
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn -> switch (turn) {
            case 1 -> ScriptedExpertEndpoint.Reply.toolCall("public_shape",
                args("type", "LedgerSession"));
            case 2 -> ScriptedExpertEndpoint.Reply.toolCall("read_file",
                args("path", "ledgerworks/src/main/java/com/ledgerworks/LedgerSession.java"));
            default -> ScriptedExpertEndpoint.Reply.toolCall("report_done", args("answer", answer));
        })) {
            CloudGate gate = new CloudGate(10_000_000, null);
            ExpertDesk desk = deskOver(endpoint, gate);

            ExpertHelp.Answer given = desk.askExpert(
                "qqzzx wibble frobnicate the whatsit", "I tried a plain field");

            assertThat(given.source())
                .as("nothing free could answer it, so the expert did")
                .isEqualTo(ExpertHelp.Source.MODEL);
            assertThat(given.text())
                .as("what the worker is handed is the session's report_done text, EXACTLY - not "
                    + "the JSON the agent framework serialized it as. A tool result arrives quoted "
                    + "with its newlines escaped, and this answer is code the worker is about to "
                    + "write: delivered as one line of escapes it is a worse answer than the same "
                    + "snippet delivered as code. Measured on the live probe, 2026-09-05. Under "
                    + "it, since 2026-10-04, the files the expert read for it.")
                .isEqualTo(answer + "\n\nRead from: "
                    + "ledgerworks/src/main/java/com/ledgerworks/LedgerSession.java");

            assertThat(given.toolsUsed())
                .as("the two lookups it actually made, in the order it made them")
                .containsExactly("public_shape", "read_file");
            assertThat(given.expertTurns())
                .as("three turns: two lookups and the answer")
                .isEqualTo(3);
        }
    }

    @Test
    void anAnswerTheFrameworkSerializedIsHandedOverAsCodeAndNotAsJson() throws Exception {
        // Exactly the shape a tool result arrives in: the answer, JSON-encoded by the framework.
        String code = "session.append(entry);" + System.lineSeparator() + "session.commit();";
        String serialized = JSON.writeValueAsString(code);

        assertThat(serialized)
            .as("this is what the agent framework hands back - quoted, newlines escaped")
            .isNotEqualTo(code);
        assertThat(ExpertEscalation.plainText(serialized))
            .as("and this is what the worker must be handed: the code itself, not a description "
                + "of it in escapes")
            .isEqualTo(code);
        assertThat(ExpertEscalation.plainText("plain text, unquoted"))
            .as("anything that is not a JSON string literal is left exactly as it stands")
            .isEqualTo("plain text, unquoted");
        assertThat(ExpertEscalation.plainText(null)).isEmpty();
    }

    @Test
    void theLookupsRunAgainstThisProjectsRealMaterialAndNotAgainstNothing() throws Exception {
        ExpertTools tools = new ExpertTools(curator, null, null, new CloudGate(0, null));

        assertThat(tools.publicShape("LedgerSession"))
            .as("the compiler's own answer about a real type in the reference material")
            .contains("LedgerSession")
            .contains("append");
        assertThat(tools.readFile("ledgerworks/src/main/java/com/ledgerworks/Entry.java"))
            .as("a real file, read by the address the other tools print")
            .contains("public record Entry");
        assertThat(tools.listFiles(""))
            .as("an empty folder argument lists what may be read at all")
            .contains("project")
            .contains("ledgerworks");
        assertThat(tools.used())
            .containsExactly("public_shape", "read_file", "list_files");
    }

    @Test
    void aReadOutsideTheProjectAndItsReferenceFoldersIsRefused() throws Exception {
        Path secret = world.resolve("elsewhere/secrets.txt");
        write(secret, "the operator's private business");
        ExpertTools tools = new ExpertTools(curator, null, null, null);

        for (String outside : List.of(secret.toAbsolutePath().toString(),
                "../elsewhere/secrets.txt",
                "ledgerworks/../../elsewhere/secrets.txt",
                "C:/Windows/win.ini",
                "/etc/passwd")) {
            String refused = tools.readFile(outside);
            assertThat(refused)
                .as("reading " + outside + " must be refused, and refused legibly")
                .startsWith("Refused:")
                .contains("not inside this project's material");
            assertThat(refused)
                .as("and the refusal must not leak the file it refused to read")
                .doesNotContain("private business");
        }
        assertThat(tools.resolveInsideRoots("ledgerworks/pom.xml"))
            .as("a legitimate address still resolves — the refusal is not a blanket no")
            .isEqualTo("ledgerworks/pom.xml");
    }

    @Test
    void aSessionThatRunsOutOfTurnsSaysSoAndNeverInventsAnAnswer() throws Exception {
        // A model that only ever looks things up and never answers: the shape of a session that
        // has lost the thread, and the one the turn allowance exists for. Since harness run 37
        // (2026-09-25) a session stopped at its cap gets one closing turn to answer from what it
        // read; this model will not answer even then, so there is still nothing — and still never
        // an invented answer. TheExpertAnswersBeforeItsTurnsRunOutTest has the model that does.
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn ->
                ScriptedExpertEndpoint.Reply.toolCall("public_shape", args("type", "LedgerSession")))) {
            CloudGate gate = new CloudGate(10_000_000, null);
            ExpertDesk desk = deskOver(endpoint, gate, 4);

            ExpertHelp.Answer given = desk.askExpert("qqzzx wibble frobnicate the whatsit", null);

            assertThat(given.source())
                .as("no answer is NONE — never a MODEL answer made up to fill the gap")
                .isEqualTo(ExpertHelp.Source.NONE);
            assertThat(given.text())
                .as("the worker is told exactly what happened and what to do about it")
                .contains("ran out of turns")
                .contains("Write your best attempt");
            assertThat(given.toolsUsed())
                .as("what it did manage to look up is still on the record")
                .isNotEmpty();
            assertThat(endpoint.turnsServed())
                .as("the allowance is enforced: the session stopped at its cap (4 turns and the "
                    + "one that was refused), and so did its closing turn (2 and the refused one) "
                    + "- neither ran on")
                .isLessThanOrEqualTo(5 + ExpertEscalation.CLOSING_TURNS + 1);
        }
    }

    @Test
    void aDeadEndpointIsReportedAsUnreachableAndTheBudgetIsGivenBack() throws Exception {
        int deadPort;
        try (var socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        CloudGate gate = new CloudGate(10_000_000, null);
        ExpertDesk desk = new ExpertDesk(curator, app, List.of(),
            new ExpertEscalation(client("http://127.0.0.1:" + deadPort), gate, curator, null, app,
                List.of(), new com.swarmcoder.runtime.KoogAgentRuntime(), 8));

        ExpertHelp.Answer given = desk.askExpert("qqzzx wibble frobnicate the whatsit", null);

        assertThat(given.source()).isEqualTo(ExpertHelp.Source.NONE);
        assertThat(given.text()).contains("could not be reached");
        assertThat(gate.used())
            .as("nothing was generated, so nothing is owed — an outage retry stays inside the "
                + "original budget")
            .isZero();
    }

    @Test
    void everyTurnIsChargedThroughTheCloudGate() throws Exception {
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn -> turn < 3
                ? ScriptedExpertEndpoint.Reply.toolCall("public_shape", args("type", "LedgerSession"))
                : ScriptedExpertEndpoint.Reply.toolCall("report_done", args("answer", "use append")))) {
            CloudGate gate = new CloudGate(10_000_000, null);
            ExpertDesk desk = deskOver(endpoint, gate);

            assertThat(gate.used()).isZero();
            ExpertHelp.Answer given = desk.askExpert("qqzzx wibble frobnicate the whatsit", null);

            assertThat(given.source()).isEqualTo(ExpertHelp.Source.MODEL);
            assertThat(gate.used())
                .as("charged before the session opened and again for every turn the server "
                    + "reported — never zero for a session that reached the model")
                .isGreaterThan(0);
            assertThat(given.tokens())
                .as("and what it cost is on the answer, for the record and the judge")
                .isGreaterThan(0);
        }
    }

    @Test
    void anExhaustedBudgetStopsTheExpertBeingAskedAtAllAndSaysSo() throws Exception {
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn ->
                ScriptedExpertEndpoint.Reply.toolCall("report_done", args("answer", "never sent")))) {
            CloudGate spent = new CloudGate(10, null);
            try {
                spent.charge(1_000);
            } catch (CloudGate.BudgetExhaustedException e) {                // expected
                // the gate is now over its cap, exactly as it would be mid-run
            }
            ExpertDesk desk = deskOver(endpoint, spent);

            ExpertHelp.Answer given = desk.askExpert("qqzzx wibble frobnicate the whatsit", null);

            assertThat(given.source()).isEqualTo(ExpertHelp.Source.NONE);
            assertThat(given.text()).contains("budget is spent");
            assertThat(endpoint.turnsServed())
                .as("the endpoint was never called — a spent budget stops the spend, it does not "
                    + "discover it afterwards")
                .isZero();
        }
    }

    // -----------------------------------------------------------------------------------------

    @Test
    void theDocumentationServerToolIsAbsentWithoutAKeyAndPresentWithOne() throws Exception {
        Librarian noKey = librarianWith(new Context7Client("https://mcp.context7.com/mcp", null, false));
        Librarian withKey = librarianWith(
            new Context7Client("https://mcp.context7.com/mcp", "a-key", true));

        assertThat(ExpertTools.toolNames(curator, noKey))
            .as("a tool that can only ever answer \"nothing is configured\" costs a turn to find "
                + "that out, so it is not offered")
            .doesNotContain("library_docs");
        assertThat(ExpertTools.toolNames(curator, withKey))
            .as("configured, it is in the list")
            .contains("library_docs");

        assertThat(ExpertTools.toolNames(curator, noKey))
            .as("everything else is always there, whatever is configured")
            .containsExactly("public_shape", "types_in", "body_of", "find_usages",
                "find_implementations", "files_using", "types_annotated_with", "call_chain",
                "build_of", "resources_of", "dependency_declaring", "doc_outline", "doc_section",
                "doc_search",
                "search", "lookup_docs", "list_files", "read_file", "skeleton_for", "report_done");
    }

    @Test
    void everyToolIsReadOnly() {
        // Not a spot check: the whole tool list, by name. A write tool arriving here later would
        // hand a paid model a way to change the operator's checkout from inside a help question,
        // which is a different product from the one this is.
        for (String name : ExpertTools.toolNames(curator, null)) {
            assertThat(name)
                .as(name + " must not be a tool that changes anything")
                .doesNotContain("write").doesNotContain("apply").doesNotContain("exec")
                .doesNotContain("delete").doesNotContain("run_");
        }
    }

    // -----------------------------------------------------------------------------------------

    private ExpertDesk deskOver(ScriptedExpertEndpoint endpoint, CloudGate gate) {
        return deskOver(endpoint, gate, ExpertEscalation.MAX_TURNS);
    }

    private ExpertDesk deskOver(ScriptedExpertEndpoint endpoint, CloudGate gate, int maxTurns) {
        return new ExpertDesk(curator, app, List.<ApiContract>of(),
            new ExpertEscalation(client(endpoint.baseUrl()), gate, curator, null, app, List.of(),
                new com.swarmcoder.runtime.KoogAgentRuntime(), maxTurns));
    }

    /**
     * A client on the scripted endpoint with a small served context, so nothing here depends on the
     * defaults of a model nobody in this test is running.
     */
    private static VllmClient client(String baseUrl) {
        return new VllmClient(baseUrl, "", "expert-fake",
            ModelQuirks.DEFAULTS.withLabel("expert-fake"));
    }

    private Librarian librarianWith(Context7Client context7) {
        return new Librarian(context7, null, List.of(reference), app, null,
            world.resolve("librarian-cache-" + System.nanoTime()));
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
