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
 * The expert starts from what the index already holds, not from an empty folder listing.
 *
 * <p>Harness run 66 (2026-10-02): four researched answers took 5 to 19 turns and 10 to 35 lookups
 * each, 4 to 13 minutes, and 55 of the 86 lookups were {@code list_files} and {@code read_file} -
 * the expert walked folders to find an example. The product's own index could name the type, its
 * signature and the file that uses it before the expert's first turn. These tests hold that it
 * now does, and that the lookups the expert has left do not send it browsing:
 *
 * <ol>
 *   <li>one search answers with the signature, the real usage and the documentation together;</li>
 *   <li>no search is run for the expert's first prompt and no file is quoted to it (section 54);</li>
 *   <li>a guessed package is answered with the type the index knows, not with "nothing";</li>
 *   <li>{@code read_file} reads one member or a range of lines, and a cut read says how to go on;</li>
 *   <li>{@code search} is the first tool on offer.</li>
 * </ol>
 */
class TheExpertIsHandedWhatTheIndexHoldsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
        write(reference.resolve("pom.xml"),
            "<project><groupId>com.ledgerworks</groupId><artifactId>ledgerworks</artifactId>"
                + "</project>");
        write(reference.resolve("src/main/java/com/ledgerworks/LedgerSession.java"), """
            package com.ledgerworks;

            /** A session against the ledger. */
            public class LedgerSession {

                /** Adds one entry to the open page. */
                public void append(Entry entry) {
                    pending = entry;
                }

                /** Makes everything appended since the last commit permanent. */
                public void commit() {
                    pending = null;
                }

                private Entry pending;
            }
            """);
        write(reference.resolve("src/main/java/com/ledgerworks/Entry.java"), """
            package com.ledgerworks;

            public record Entry(String what) { }
            """);
        write(reference.resolve("src/main/java/com/ledgerworks/example/PayrollPoster.java"), """
            package com.ledgerworks.example;

            import com.ledgerworks.Entry;
            import com.ledgerworks.LedgerSession;

            /** Posts one payslip to the ledger. */
            public class PayrollPoster {

                private final LedgerSession session = new LedgerSession();

                public void post(String payslip) {
                    session.append(new Entry(payslip));
                    session.commit();
                }
            }
            """);
        write(reference.resolve("docs/guides/posting.md"), """
            # Posting

            ## Posting an entry

            Open a LedgerSession, append each Entry, then commit. Nothing is permanent until
            commit returns.
            """);
        StringBuilder big = new StringBuilder("package com.ledgerworks.big;\n\npublic class Big {\n");
        for (int i = 0; i < 600; i++) {
            big.append("    public int number").append(i).append("() { return ").append(i)
                .append("; }\n");
        }
        write(reference.resolve("src/main/java/com/ledgerworks/big/Big.java"), big + "}\n");
        curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local"),
                new KnowledgeCurator.Root("ledgerworks", reference, "1.0")),
            null, world.resolve("cache"));
    }

    @Test
    void oneSearchAnswersWithTheSignatureTheRealUsageAndTheDocumentation() {
        String found = ExpertSearch.search(curator,
            "How do I append an Entry with a LedgerSession and make it permanent?", 20_000, true);

        assertThat(found)
            .as("the type the question names, with the signature the compiler resolved")
            .contains("`LedgerSession`").contains("append(")
            .as("where it is declared, as an address read_file takes")
            .contains("ledgerworks/src/main/java/com/ledgerworks/LedgerSession.java")
            .as("the real code that uses the types the question names, quoted")
            .contains("PayrollPoster.java").contains("session.append(new Entry(payslip))")
            .as("and the documentation section about it")
            .contains("Posting an entry");
    }

    @Test
    void aSearchThatMatchesNothingSaysSoInOneLine() {
        assertThat(ExpertSearch.search(curator, "qqzzx wibble frobnicate", 20_000, true))
            .startsWith(ExpertSearch.NOTHING);
        assertThat(ExpertTools.foundSomething(ExpertSearch.NOTHING + "qqzzx"))
            .as("and a closing answer is never written from it")
            .isFalse();
    }

    @Test
    void theExpertsFirstPromptHoldsNoSearchResultAndItFetchesWhatItNeedsItself() throws Exception {
        String answer = "session.append(new Entry(payslip)); session.commit();";
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn ->
                ScriptedExpertEndpoint.Reply.toolCall("report_done", args("answer", answer)))) {
            ExpertDesk desk = new ExpertDesk(curator, app, List.<ApiContract>of(),
                new ExpertEscalation(client(endpoint.baseUrl()), new CloudGate(10_000_000, null),
                    curator, null, app, List.of(), new com.swarmcoder.runtime.KoogAgentRuntime(),
                    ExpertEscalation.MAX_TURNS));

            // Asked twice by the same worker: the second ask always goes to the expert.
            desk.askExpert("zzqx how is a LedgerSession frobnicated wibble", null);
            ExpertHelp.Answer given = desk.askExpert(
                "zzqx how is a LedgerSession frobnicated wibble plugh", null);

            assertThat(given.source()).isEqualTo(ExpertHelp.Source.MODEL);

            String firstRequest = lastSessionFirstRequest(endpoint);
            assertThat(firstRequest)
                .as("section 54: no search is run for the expert before its first turn")
                .doesNotContain("What the index holds on this question");
            assertThat(firstRequest)
                .as("it is told so, and told to fetch what it needs with the tree queries first")
                .contains("NOT searched for you")
                .contains("Do NOT walk folders");
        }
    }

    @Test
    void aGuessedPackageIsAnsweredWithTheTypeTheIndexKnowsNotWithNothing() {
        ExpertTools tools = new ExpertTools(curator, null, null, new CloudGate(0, null));

        assertThat(tools.publicShape("com.wrong.guess.LedgerSession"))
            .as("run 66: public_shape said 'holds no type' for four of six names with a guessed "
                + "package. The simple name is right and the index knows it.")
            .contains("holds no `com.wrong.guess.LedgerSession`")
            .contains("com.ledgerworks.LedgerSession")
            .contains("append(");
        assertThat(tools.findUsages("org.nowhere.Entry"))
            .contains("PayrollPoster.java");
        assertThat(tools.publicShape("com.wrong.guess.NoSuchThingAnywhere"))
            .as("a name the index really does not know is still said to be unknown")
            .startsWith("The index holds no type");
    }

    @Test
    void readFileReadsOneMemberOrARangeOfLinesInsteadOfTheWholeFile() {
        ExpertTools tools = new ExpertTools(curator, null, null, new CloudGate(0, null));
        String file = "ledgerworks/src/main/java/com/ledgerworks/LedgerSession.java";

        assertThat(tools.readFile(file + "#append"))
            .as("one member, whole, with its lines and the package its imports need")
            .contains("package com.ledgerworks;")
            .contains("public void append(Entry entry)")
            .contains("pending = entry;")
            .doesNotContain("public void commit()");
        assertThat(tools.readFile(file + ":1-3"))
            .as("a range of lines")
            .startsWith("Lines 1-3 of")
            .contains("package com.ledgerworks;")
            .doesNotContain("public void commit()");
        assertThat(tools.readFile(file + ":7"))
            .as("the address the structural tools print, <file>:<line>, is read, not refused")
            .startsWith("Lines 1-")
            .contains("public void append(Entry entry)");
        assertThat(tools.readFile(file + "#noSuchMember"))
            .as("a member that is not there is said to be missing, with what is there")
            .contains("declares no `noSuchMember`").contains("append").contains("commit");
        assertThat(ExpertTools.foundSomething(tools.readFile(file + "#noSuchMember"))).isFalse();
        assertThat(tools.readFile(file))
            .as("and a file that fits is still read whole, exactly as before")
            .contains("public void append(Entry entry)").contains("public void commit()");
    }

    @Test
    void aFileTooLongForOneReadSaysHowToReadTheRest() {
        ExpertTools tools = new ExpertTools(curator, null, null, new CloudGate(0, null));
        String file = "ledgerworks/src/main/java/com/ledgerworks/big/Big.java";

        String read = tools.readFile(file);

        assertThat(read.length())
            .as("capped as every lookup is")
            .isLessThanOrEqualTo(ExpertTools.MAX_TOOL_CHARS);
        assertThat(read)
            .as("cut on a line, with the next range and the member form named")
            .contains("[Lines 1-").contains(". Read more of it with " + file + ":")
            .contains(file + "#methodName");
        assertThat(tools.readFile(file + "#number599"))
            .as("so the last member of a long file is one call away, not three reads")
            .contains("return 599;");
    }

    @Test
    void theTreeQueriesAreOfferedBeforeSearch() {
        List<String> offered = ExpertTools.toolNames(curator, null);
        assertThat(offered.indexOf("public_shape")).isLessThan(offered.indexOf("search"));
        ExpertTools tools = new ExpertTools(curator, null, null, new CloudGate(0, null));
        assertThat(tools.search("LedgerSession commit"))
            .as("the tool is the same search, over the same index")
            .contains("`LedgerSession`").contains("commit(");
        assertThat(tools.used()).containsExactly("search");
    }

    // -----------------------------------------------------------------------------------------

    /** The first request of the LAST session the endpoint served: the one whose history is shortest. */
    private static String lastSessionFirstRequest(ScriptedExpertEndpoint endpoint) {
        synchronized (endpoint.requests) {
            return endpoint.requests.get(endpoint.requests.size() - 1);
        }
    }

    private static VllmClient client(String baseUrl) {
        return new VllmClient(baseUrl, "", "expert-fake",
            ModelQuirks.DEFAULTS.withLabel("expert-fake"));
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
