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
package com.swarmcoder.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.runtime.CloudGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DEVELOPER_CORRECTIONS section 105, finding B: {@code keep_for_workers} refused 8 of 20 keeps
 * of a live build - a part longer than a finding carries, and lines named by the file's own
 * numbers - and what was refused reached no worker.
 */
class AFindingTooLongToCarryIsHandedOnByNameTest {

    private static final String FILE = "project/src/main/java/com/acme/shop/StockScreen.java";

    @TempDir
    Path world;

    private ExpertTools session;
    private DraftTools draft;
    private int constructorStarts;
    private int refreshStarts;
    private int saveEnds;

    @BeforeEach
    void aScreenWithALongConstructor() throws Exception {
        Path app = world.resolve("app");
        StringBuilder source = new StringBuilder("""
            package com.acme.shop;

            import java.util.List;

            public class StockScreen {
                private final List<String> rows = new java.util.ArrayList<>();

                public StockScreen() {
            """);
        for (int i = 0; i < DraftTools.FINDING_LINES + 20; i++) {
            source.append("        rows.add(\"row ").append(i).append("\");\n");
        }
        source.append("""
                }

                public void refresh() {
                    rows.clear();
                    rows.add("fresh");
                }

                public void save() {
                    rows.add("saved");
                }
            }
            """);
        Files.createDirectories(app.resolve("src/main/java/com/acme/shop"));
        Files.writeString(app.resolve("src/main/java/com/acme/shop/StockScreen.java"), source);
        Files.writeString(app.resolve("pom.xml"),
            "<project><artifactId>app</artifactId></project>");
        List<String> lines = source.toString().lines().toList();
        constructorStarts = lines.indexOf("    public StockScreen() {") + 1;
        refreshStarts = lines.indexOf("    public void refresh() {") + 1;
        saveEnds = lines.indexOf("    public void save() {") + 3;
        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local")), null,
            world.resolve("cache"));
        session = new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User("architect", "hand in", 0, null, 0, 0));
        draft = new DraftTools(session, "design", 3,
            json -> new DraftTools.Checked(true, List.of()));
    }

    @Test
    void aMemberLongerThanAFindingCarriesIsHandedOnAsTheLookupThatReturnsIt() {
        String body = session.bodyOf("StockScreen#StockScreen");

        String reply = draft.keepForWorkers("Stock", "body_of StockScreen#StockScreen", "",
            "The screen fills its rows in its constructor.");

        assertThat(reply).as("kept, by name, and told that no lines travel")
            .startsWith("KEPT for the tasks that build or change Stock")
            .contains("the lookup by name").contains("without its lines")
            .contains("a finding carries at most " + DraftTools.FINDING_LINES)
            .contains("No smaller named part lies inside it");
        assertThat(draft.findings()).hasSize(1);
        DesignFinding handedOn = draft.findings().get(0);
        assertThat(handedOn.source()).isEqualTo("body_of StockScreen#StockScreen");
        assertThat(handedOn.hasSnippet()).as("the limit on carried text stays").isFalse();
        int lines = body.strip().split("\n").length;
        assertThat(handedOn.note()).startsWith("The screen fills its rows in its constructor.")
            .contains("[Not carried: " + lines + " lines, "
                + String.format(java.util.Locale.ROOT, "%,d", body.strip().length())
                + " characters. Ask for exactly the lookup named here; do not read the file.]");
        assertThat(handedOn.render()).as("what a worker is given: the sentence and the lookup")
            .contains("(from body_of StockScreen#StockScreen)").doesNotContain("rows.add");
    }

    @Test
    void aFileWithItsOwnLineNumbersIsTakenWhenALookupReturnedThoseLines() {
        session.bodyOf("StockScreen#refresh");

        String reply = draft.keepForWorkers("Stock", "read_file " + FILE,
            refreshStarts + "-" + (refreshStarts + 3), "Refreshing clears the rows first.");

        assertThat(reply).startsWith("KEPT for the tasks that build or change Stock")
            .contains("lines " + refreshStarts + "-" + (refreshStarts + 3) + " of " + FILE)
            .contains("body_of StockScreen#refresh");
        DesignFinding kept = draft.findings().get(0);
        assertThat(kept.source()).as("the lookup that was made, not the one that was named")
            .isEqualTo("body_of StockScreen#refresh");
        assertThat(kept.snippet()).contains("public void refresh() {").contains("rows.clear();")
            .contains("rows.add(\"fresh\");");

        assertThat(draft.keepForWorkers("Stock", "read_file " + FILE, "1-3", "The package."))
            .as("lines no lookup of the session returned are still refused")
            .startsWith("error: no lookup of this session matches");
    }

    @Test
    void aLongRangeIsAnsweredWithTheNamedPartsInsideItAndTheirLineCounts() {
        session.readFile(FILE + ":1-" + (saveEnds + 1));

        String reply = draft.keepForWorkers("Stock", "read_file " + FILE,
            constructorStarts + "-" + saveEnds, "How the screen is built and saved.");

        assertThat(reply).startsWith("KEPT for the tasks that build or change Stock")
            .contains("Inside it:")
            .contains("body_of StockScreen#StockScreen - " + (DraftTools.FINDING_LINES + 22)
                + " lines")
            .contains("body_of StockScreen#refresh - 4 lines")
            .contains("body_of StockScreen#save - 3 lines");
        DesignFinding handedOn = draft.findings().get(0);
        assertThat(handedOn.source())
            .isEqualTo("read_file " + FILE + ":" + constructorStarts + "-" + saveEnds);
        assertThat(handedOn.hasSnippet()).isFalse();

        // The part it means, looked up on its own, travels with its lines.
        session.bodyOf("StockScreen#save");
        assertThat(draft.keepForWorkers("Stock", "body_of StockScreen#save", "",
            "Saving adds a row.")).contains("KEPT").contains("lines 1-4 of 4");
        assertThat(draft.findings().get(1).snippet()).contains("rows.add(\"saved\");");
    }
}
