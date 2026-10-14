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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.knowledge.ProjectTypes;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.verify.BuildLayout;
import org.junit.jupiter.api.BeforeEach;
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
 * DEVELOPER_CORRECTIONS section 106: one build of an empty project on a reference library, with
 * the model scripted ({@link ScriptedAgentLlm}) and the agent runtime, the lookups, the draft
 * tools and the checks the real ones. One test for each of its four findings:
 *
 * <ul>
 *   <li><b>A.</b> A turn whose every lookup brought nothing new is followed by the role's draft
 *       check: that one request names the check as the only tool to call, the role is told so,
 *       every lookup is still offered in it, and the turn after it is asked as always.</li>
 *   <li><b>B.</b> The planner is given what the architect of the same build kept: by title in
 *       its opening, in full through {@code known_about}; and its tool guide points to the
 *       lookup of one method or one type before a whole file.</li>
 *   <li><b>C.</b> A design that will need a build file written, with no fact kept from a build
 *       lookup, is objected to by {@code check_design} until one is kept.</li>
 *   <li><b>D.</b> {@code known_about} is offered on a project that has delivered nothing, and
 *       answers with a fact from the moment it is kept, in the session that kept it.</li>
 * </ul>
 */
class APlanningRoleThatLearnsNothingNewChecksItsDraftTest {

    /** The project: nothing is built in it yet. */
    @TempDir
    Path app;
    /** What it builds on: a library with an example application. */
    @TempDir
    Path reference;
    @TempDir
    Path cache;

    private static final String EXAMPLE = "reference/src/main/java/com/lib/example/Shelf.java";
    private static final String NOTE = "A module is built as a jar under the library's parent.";

    @BeforeEach
    void world() throws Exception {
        write(app.resolve("README.md"), "# A shop\n");
        write(reference.resolve("pom.xml"), """
            <project>
              <parent><groupId>com.lib</groupId><artifactId>lib-parent</artifactId></parent>
              <artifactId>lib-example</artifactId>
              <packaging>jar</packaging>
            </project>
            """);
        write(reference.resolve("src/main/java/com/lib/example/Shelf.java"), """
            package com.lib.example;

            import java.util.List;

            public class Shelf {
                public List<String> titles() { return List.of(); }
            }
            """);
    }

    @Test
    void theFourFindingsOnOneScriptedBuild() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"tasks\":[]}",
                (turn, conversation) -> switch (turn) {
                    // The architect.
                    case 1, 2 -> ScriptedAgentLlm.Turn.call("read_file", Map.of("path", EXAMPLE));
                    case 3, 7 -> ScriptedAgentLlm.Turn.call("check_design",
                        Map.of("design", design("com.acme.shop.Rating")));
                    case 4 -> ScriptedAgentLlm.Turn.call("read_file",
                        Map.of("path", "reference/pom.xml"));
                    case 5 -> ScriptedAgentLlm.Turn.call("keep_for_workers", Map.of("about", "",
                        "lookup", "read_file reference/pom.xml", "lines", "", "note", NOTE));
                    case 6 -> ScriptedAgentLlm.Turn.call("known_about", Map.of("what", "pom.xml"));
                    case 8 -> ScriptedAgentLlm.Turn.call("report_done", Map.of("finalJson", ""));
                    // The planner.
                    case 9 -> ScriptedAgentLlm.Turn.call("known_about", Map.of("what", "pom.xml"));
                    default -> ScriptedAgentLlm.Turn.call("report_done",
                        Map.of("finalJson", "{\"tasks\":[]}"));
                })) {
            VllmClient client = new VllmClient(llm.baseUrl(), null, "scripted", true);
            CloudGate gate = new CloudGate(10_000_000, null);
            ArchitectClient architect = new ArchitectClient(client, gate);
            architect.setLookupAgent(new LookupAgent(curator(), null, app, gate,
                new KoogAgentRuntime(), null, null));

            DesignDocument design;
            try (LookupAgent.Scope inRun = LookupAgent.inRun(UUID.randomUUID());
                 ArchitectClient.Scope checking = architect.checkingDraftsWith(checks())) {
                design = architect.design("Let a customer rate a title");
                assertThat(design).isNotNull();
                assertThat(llm.sessionRequests).as("the architect's session").hasSize(8);
                architect.plan(design, "Let a customer rate a title");
            }

            // A: the turn after a turn of repeats is the draft check, and only that turn.
            assertThat(llm.sessionToolChoices.get(1)).isEmpty();
            assertThat(llm.sessionToolChoices.get(2)).as("turn 2 read again what turn 1 had "
                + "read and the conversation still held whole: turn 3 is the draft check")
                .isEqualTo("check_design");
            assertThat(llm.sessionTools.get(2)).as("no lookup is taken away for that turn")
                .isEqualTo(llm.sessionTools.get(1)).contains("read_file", "body_of");
            assertThat(llm.sessionRequests.get(2)).as("and the role is told so")
                .contains("So your next turn is check_design");
            assertThat(llm.sessionToolChoices.subList(3, llm.sessionToolChoices.size()))
                .as("after the check every turn is asked as always: turn 4 is a lookup")
                .allMatch(String::isEmpty);
            assertThat(llm.sessionRequests.get(4)).as("and that lookup was answered")
                .contains("<artifactId>lib-example</artifactId>");

            // C: the first draft needs a build file and nothing was kept from a build lookup.
            assertThat(llm.sessionRequests.get(3)).contains("OBJECTION(S)")
                .contains("none of the 0 fact(s) you kept comes from a build lookup");
            assertThat(llm.sessionRequests.get(7)).as("kept from the reference build file: "
                + "the same draft has nothing against it").contains("NO OBJECTIONS");
            assertThat(design.findings()).singleElement().satisfies(kept -> {
                assertThat(kept.source()).isEqualTo("read_file reference/pom.xml");
                assertThat(BuildFactsAreKept.fromABuildLookup(kept)).isTrue();
            });

            // D: offered on a project that has delivered nothing, and answered at once.
            assertThat(llm.sessionTools.get(0)).as("the architect of a project's first build "
                + "is offered the lookup").contains("known_about");
            assertThat(llm.sessionRequests.get(6)).as("the architect's own question, one turn "
                + "after it kept the fact").contains("1 fact(s) recorded about `pom.xml`")
                .contains(NOTE).contains("found by: the architect");

            // B: the planner of the same build.
            assertThat(llm.sessionRequests).as("the planner's session").hasSize(10);
            String opening = llm.sessionRequests.get(8);
            assertThat(opening).as("by title in its opening, with the lookup that returns it")
                .contains("WHAT THIS PROJECT ALREADY KNOWS - 1 fact(s)")
                .contains("- the whole project (1): from pom.xml")
                .contains("FACT [the whole project] " + NOTE);
            assertThat(opening).as("the fact's lines are a lookup, not part of the opening")
                .doesNotContain("<artifactId>lib-example</artifactId>");
            assertThat(opening).as("one method or one type before a whole file")
                .contains("ONE METHOD OR ONE TYPE BEFORE A FILE")
                .contains("known_about <the fact's subject or source>");
            assertThat(llm.sessionTools.get(8)).contains("known_about", "body_of");
            assertThat(llm.sessionRequests.get(9)).as("known_about gives the planner the fact "
                + "with the lines the architect's lookup returned")
                .contains(NOTE).contains("<artifactId>lib-example</artifactId>")
                .contains("found by: the architect");
        }
    }

    @Test
    void aBuildFactIsAskedForOnlyWhereABuildFileIsStillToBeWrittenAndOneCanBeLookedUp()
            throws Exception {
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "rate a title", List.of(),
            List.of(), List.of(contract("com.acme.shop.Rating")), List.of(), null, Instant.now());
        BuildLayout.Layout nothingBuilt = BuildLayout.read(app, null);
        ProjectTypes empty = ProjectTypes.of(app);
        DesignFinding code = new DesignFinding(UUID.randomUUID(), "com.acme.shop.Rating",
            "body_of Shelf#titles", "A shelf lists its titles.", "titles()");

        assertThat(BuildFactsAreKept.objections(design, List.of(code), nothingBuilt, empty, true))
            .as("a fact about code is not a fact about the build").singleElement()
            .satisfies(objection -> assertThat(objection).contains("com.acme.shop.Rating")
                .contains("build_of").contains("keep_for_workers"));
        for (String source : List.of("build_of reference", "dependency_declaring lib-core",
                "read_file reference/pom.xml:1-12", "read_file reference/app/build.gradle.kts")) {
            DesignFinding build = new DesignFinding(UUID.randomUUID(), null, source,
                "How a module is built.", null);
            assertThat(BuildFactsAreKept.objections(design, List.of(code, build), nothingBuilt,
                empty, true)).as(source).isEmpty();
        }
        assertThat(BuildFactsAreKept.objections(design, List.of(), nothingBuilt, empty, false))
            .as("no build file among the reference material: nothing can be kept").isEmpty();

        // The same design in a project whose build already compiles a module.
        write(app.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
            + "<groupId>com.acme</groupId><artifactId>shop</artifactId>"
            + "<version>1</version></project>");
        write(app.resolve("src/main/java/com/acme/shop/Catalog.java"),
            "package com.acme.shop;\n\npublic class Catalog { }\n");
        BuildLayout.Layout built = BuildLayout.read(app, null);
        assertThat(built.compilingModules()).isNotEmpty();
        assertThat(BuildFactsAreKept.objections(design, List.of(), built, ProjectTypes.of(app),
            true)).as("the project builds: no build file is to be written").isEmpty();

        // And a design that delivers nothing new adds no build file either.
        DesignDocument nothingNew = new DesignDocument(UUID.randomUUID(), 1, "x", List.of(),
            List.of(), List.of(), List.of(), null, Instant.now());
        assertThat(BuildFactsAreKept.objections(nothingNew, List.of(), nothingBuilt, empty, true))
            .isEmpty();
    }

    // -------------------------------------------------------------------------------------------

    private KnowledgeCurator curator() {
        return new KnowledgeCurator(List.of(new KnowledgeCurator.Root("project", app, "local"),
            new KnowledgeCurator.Root("reference", reference, "1.0")), null, cache);
    }

    /** No objection of its own; the check of the kept findings is the product's. */
    private ArchitectClient.DraftChecks checks() {
        return new ArchitectClient.DraftChecks() {
            @Override
            public List<String> design(DesignDocument draft) {
                return List.of();
            }

            @Override
            public List<String> designAgainstKept(DesignDocument draft, List<DesignFinding> kept) {
                return BuildFactsAreKept.objections(draft, kept, BuildLayout.read(app, null),
                    ProjectTypes.of(app), true);
            }

            @Override
            public List<String> plan(TaskGraph draft, DesignDocument design) {
                return List.of();
            }
        };
    }

    private static ApiContract contract(String type) {
        ApiContract contract = new ApiContract();
        contract.setName(type.substring(type.lastIndexOf('.') + 1));
        contract.setTypeName(type);
        return contract;
    }

    private static String design(String contractType) {
        String simple = contractType.substring(contractType.lastIndexOf('.') + 1);
        return """
            {"requirements":[{"text":"A customer can rate a title","priority":"HIGH"}],
             "decisions":[{"decision":"Keep ratings in a module of their own","rationale":"small"}],
             "contracts":[{"name":"%s","description":"NEW: what the story adds",
               "signature":"class %s","type":"%s","members":["int stars()"]}],
             "risks":[]}
            """.formatted(simple, simple, contractType);
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
