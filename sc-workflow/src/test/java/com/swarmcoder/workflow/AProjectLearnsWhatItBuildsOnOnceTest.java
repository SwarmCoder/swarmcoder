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

import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.Story;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.KnownFacts;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project learns what it builds on once (section 97). What the architect of a delivered story
 * kept is offered to the architect of the next one: by title in its opening, and whole through
 * {@code known_about}, with the story and commit it was true at; a fact whose source has
 * changed since is marked and its lines withheld.
 *
 * <p>The model is scripted; the agent runtime, the lookups and the tools are the real ones.
 */
class AProjectLearnsWhatItBuildsOnOnceTest {

    @TempDir
    Path world;

    private static final String CATALOG = "project/src/main/java/com/acme/shop/Catalog.java";

    private Path app() throws Exception {
        Path app = world.resolve("app");
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(app.resolve("src/main/java/com/acme/shop/Catalog.java"), """
            package com.acme.shop;

            import java.util.List;

            public class Catalog {
                public List<String> titles() { return List.of(); }
                public void add(String title) { }
            }
            """);
        return app;
    }

    private static KnownFacts whatStoryOneKept() {
        return KnownFacts.of(List.of(
            new KnownFacts.Fact("com.acme.shop.Rating", "read_file " + CATALOG + ":5-8",
                "The catalog is a plain class with one public method per use.",
                "public class Catalog {\n    public void add(String title) { }", "S1",
                "0123456789abcdef"),
            new KnownFacts.Fact("com.acme.shop.Rating", "body_of Catalog#remove",
                "A title is taken out by name.",
                "public void remove(String title) { titles.remove(title); }", "S1",
                "0123456789abcdef"),
            new KnownFacts.Fact(null, "read_file " + CATALOG,
                "Everything lives in com.acme.shop.", null, "S1", "0123456789abcdef")));
    }

    @Test
    void theNextStorysArchitectReadsWhatWasKeptInsteadOfLookingItUpAgain() throws Exception {
        Path app = app();
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(), app, null, world.resolve("cache"));
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> designJson(),
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("known_about", Map.of("what", "Rating"));
                    case 2 -> ScriptedAgentLlm.Turn.call("known_about", Map.of("what", "Catalog"));
                    case 3 -> ScriptedAgentLlm.Turn.call("known_about", Map.of("what", "Basket"));
                    case 4 -> ScriptedAgentLlm.Turn.call("keep_for_workers", Map.of(
                        "about", "Rating", "lookup", "known_about Rating", "lines", "none",
                        "note", "The catalog is a plain class with one public method per use."));
                    case 5 -> ScriptedAgentLlm.Turn.call("check_design", Map.of("design", designJson()));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("finalJson", ""));
                })) {
            VllmClient client = new VllmClient(llm.baseUrl(), null, "scripted", true);
            CloudGate gate = new CloudGate(10_000_000, null);
            ArchitectClient architect = new ArchitectClient(client, gate);
            architect.setLookupAgent(new LookupAgent(librarian.curator(), librarian, app, gate,
                new KoogAgentRuntime(), null, null)
                .withKnown(AProjectLearnsWhatItBuildsOnOnceTest::whatStoryOneKept));

            DesignDocument design = architect.design("Let a customer rate a title");

            String opening = llm.sessionRequests.get(0);
            assertThat(opening)
                .as("told there is something known, and how to read it")
                .contains("WHAT IS ALREADY KNOWN, BEFORE ANYTHING ELSE")
                .contains("WHAT THIS PROJECT ALREADY KNOWS - 3 fact(s)")
                .contains("- Rating (2): from Catalog, Catalog#remove")
                .contains("- the whole project (1): from Catalog");
            assertThat(opening)
                .as("titles only: no fact's sentence and no line of code is in the opening")
                .doesNotContain("one public method per use")
                .doesNotContain("A title is taken out by name")
                .doesNotContain("public void add(String title) { }");

            String aboutRating = llm.sessionRequests.get(1);
            assertThat(aboutRating)
                .as("the fact, where it came from, when it was true and that it still is")
                .contains("2 fact(s) recorded about `Rating`")
                .contains("[Rating] The catalog is a plain class with one public method per use.")
                .contains("from: read_file " + CATALOG + ":5-8; true for story S1 at commit "
                    + "0123456789")
                .contains("source: unchanged since")
                .contains("public void add(String title) { }");
            assertThat(aboutRating)
                .as("a fact whose source no longer holds its lines is not served as true")
                .contains("[Rating] A title is taken out by name.")
                .contains("source: CHANGED SINCE")
                .doesNotContain("titles.remove(title)");

            assertThat(llm.sessionRequests.get(2))
                .as("asked by the file a fact was copied from: every fact from it")
                .contains("3 fact(s) recorded about `Catalog`")
                .contains("[the whole project] Everything lives in com.acme.shop.")
                .contains("kept as a sentence, with no lines to hold against it");
            assertThat(llm.sessionRequests.get(3))
                .as("nothing recorded is said plainly, with what is")
                .contains("Nothing is recorded about `Basket`. Look it up.")
                .contains("- Rating (2)");
            assertThat(llm.sessionRequests.get(4))
                .as("a known fact can be handed to this story's workers like any lookup")
                .contains("KEPT for the tasks that build or change Rating");

            assertThat(design).isNotNull();
            assertThat(design.findings()).hasSize(1);
            assertThat(design.findings().get(0).source()).isEqualTo("known_about Rating");
        }
    }

    @Test
    void aProjectThatHoldsNothingOpensItsSessionsAsBefore() throws Exception {
        Path app = app();
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(), app, null, world.resolve("cache"));
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> designJson(),
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("check_design", Map.of("design", designJson()));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("finalJson", ""));
                })) {
            VllmClient client = new VllmClient(llm.baseUrl(), null, "scripted", true);
            CloudGate gate = new CloudGate(10_000_000, null);
            ArchitectClient architect = new ArchitectClient(client, gate);
            architect.setLookupAgent(new LookupAgent(librarian.curator(), librarian, app, gate,
                new KoogAgentRuntime(), null, null).withKnown(KnownFacts::none));

            assertThat(architect.design("Let a customer rate a title")).isNotNull();
            assertThat(llm.sessionRequests.get(0))
                .doesNotContain("known_about").doesNotContain("ALREADY KNOW");
        }
    }

    @Test
    void whatIsKnownIsWhatDeliveredStoriesKeptWithTheStoryAndCommit() {
        UUID project = UUID.randomUUID();
        DesignDocument first = designWith(new DesignFinding(UUID.randomUUID(), "com.acme.Order",
            "body_of Ledger#post", "A post is one call.", "void post() { }"));
        DesignDocument second = designWith(new DesignFinding(UUID.randomUUID(), "com.acme.Order",
            "body_of Ledger#void", "Never proved by a build.", null));
        DesignDocument other = designWith(new DesignFinding(UUID.randomUUID(), "com.else.Thing",
            "body_of Thing", "Another project's.", null));
        Story delivered = story(project, "S1", "abc123");
        Story stopped = story(project, "S2", null);
        Story elsewhere = story(UUID.randomUUID(), "S1", "fff999");
        Map<UUID, DesignDocument> designs = Map.of(first.id(), first, second.id(), second,
            other.id(), other);
        Map<UUID, Story> stories = Map.of(delivered.id(), delivered, stopped.id(), stopped,
            elsewhere.id(), elsewhere);
        List<Run> runs = List.of(run(project, delivered, first), run(project, stopped, second),
            run(elsewhere.projectId(), elsewhere, other));

        KnownFacts known = KnownFacts.fromDelivered(runs, designs::get, stories::get, project);

        assertThat(known.size())
            .as("only this project's delivered story: a stopped run proved nothing").isEqualTo(1);
        assertThat(known.about("Order", fact -> KnownFacts.Source.UNCHANGED))
            .contains("[Order] A post is one call.")
            .contains("true for story S1 at commit abc123")
            .contains("void post() { }")
            .doesNotContain("Never proved");
        assertThat(known.about("Ledger", fact -> KnownFacts.Source.GONE))
            .as("asked by the type the fact was copied from; a source that is gone is said so")
            .contains("source: NO LONGER THERE").doesNotContain("void post() { }");
    }

    private static DesignDocument designWith(DesignFinding finding) {
        DesignDocument design = new DesignDocument();
        design.setId(UUID.randomUUID());
        design.setFindings(List.of(finding));
        return design;
    }

    private static Story story(UUID project, String key, String deliveredCommit) {
        Story story = new Story();
        story.setId(UUID.randomUUID());
        story.setProjectId(project);
        story.setKey(key);
        story.setDeliveredCommit(deliveredCommit);
        return story;
    }

    private static Run run(UUID project, Story story, DesignDocument design) {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setProjectId(project);
        run.setStoryId(story.id());
        run.setDesignId(design.id());
        run.setStartedAt(Instant.now());
        return run;
    }

    private static String designJson() {
        return """
            {"requirements":[{"text":"A customer can rate a title","priority":"HIGH"}],
             "decisions":[{"decision":"Keep ratings beside the catalog","rationale":"one module"}],
             "contracts":[{"name":"Rating","description":"NEW: what the story adds",
               "signature":"class Rating","type":"com.acme.shop.Rating","members":["int stars()"]}],
             "risks":[]}
            """;
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
