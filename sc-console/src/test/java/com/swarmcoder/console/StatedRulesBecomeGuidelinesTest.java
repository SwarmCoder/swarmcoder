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
package com.swarmcoder.console;

import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a technical document's statements land, at the point the operator presses Apply.
 *
 * <p><b>The correction this asserts.</b> For a few hours on 2026-08-31 a rule about how the project
 * is built was applied as a third kind of BRD requirement. That needed nine exemptions to keep it
 * out of machinery that assumes everything in the requirements document eventually gets delivered:
 * the plan's coverage rule, the agreement gate, the coverage figures, story slicing, the backlog,
 * and four screens. SwarmCoder already had a mechanism for a rule the project must follow, so a
 * rule now goes there instead — and nothing at all is written into the requirements document.
 *
 * <p>Nothing here contacts a model endpoint: the analyst is a scripted reply.
 */
class StatedRulesBecomeGuidelinesTest {

    private static final long WAIT_MILLIS = 20_000;

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;
    private GuidedFlowServiceImpl flows;
    private RecordingRules rules;

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir);
        flows = new GuidedFlowServiceImpl();
    }

    @AfterEach
    void closeStore() throws Exception {
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    @Test
    void aRuleIsRecordedAsAProjectRuleAndNothingIsAddedToTheRequirements() throws Exception {
        install(true);
        applyOneRuleAndOneFeature();

        assertThat(rules.stated)
            .describedAs("the rule went to the project's rules, in the document's own words")
            .hasSize(1);
        RecordingRules.Stated stated = rules.stated.get(0);
        assertThat(stated.title()).isEqualTo("What this project must not use");
        assertThat(stated.body()).contains("Do not use").contains("Spring");
        assertThat(stated.document())
            .describedAs("and it names the paper it was read out of, which is the trail the "
                + "requirements route gave and this one has to keep")
            .isEqualTo("bookshelf-tech-requirements.md");

        Brd brd = store.getBrd(projectId);
        assertThat(brd.requirements())
            .describedAs("the requirements document holds the DELIVERABLE work and nothing else")
            .hasSize(1);
        assertThat(brd.requirements().get(0).title()).isEqualTo("Reading list");
    }

    /**
     * A rule the console cannot record must say so, not disappear.
     *
     * <p>A rule the operator wrote, ticked and applied, which then quietly went nowhere, is the
     * exact failure this whole change exists to end — and it would be invisible, because the
     * requirements list would look no different.
     */
    @Test
    void aRuleThatCannotBeRecordedIsReportedAndNotSilentlyDropped() throws Exception {
        install(false);
        String error = applyOneRuleAndOneFeature();

        assertThat(error).contains("rules are not connected");
        assertThat(store.getBrd(projectId).requirements())
            .describedAs("the deliverable requirement still landed — one failure is not all of them")
            .hasSize(1);
    }

    /**
     * A CONSTRAINT proposal's "excerpt" — the document's own sentence, verbatim, distinct from the
     * analyst's paraphrase in "text" — must reach {@link ConsoleContext.GuidelineControl#stateRule}
     * unchanged. This is the round trip {@code Draft.render} writes onto the proposal block and
     * {@code Draft.parse} reads back off it at apply time (see {@code RequirementsIntake}); nothing
     * here exercises the retry or salvage path, only that the field survives end to end once a
     * reply parses normally.
     */
    @Test
    void aConstraintsExcerptReachesTheStatedRuleUnchanged() throws Exception {
        rules = new RecordingRules();
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withChat(new ScriptedAnalyst("{\"questions\":[]}", """
                {"proposals":[\
                {"kind":"ADD","ref":"N1","title":"What this project must not use",\
                "rationale":"the document says so","priority":"CRITICAL",\
                "requirementKind":"CONSTRAINT",\
                "text":"Do not use Spring, JPA or SQL — none of them is present.",\
                "excerpt":"This app uses no Spring, no JPA and no SQL.",\
                "criteria":[]}\
                ]}"""))
            .withGuidelineControl(rules));

        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId,
            "tech-notes.md", "text/markdown",
            "This app uses no Spring, no JPA and no SQL.".getBytes(StandardCharsets.UTF_8));
        assertThat(result.failed()).describedAs(result.error()).isFalse();
        SourceDocument document = result.document();

        String flowId = flows.intake().flow().id().toString();
        assertThat(flows.addDocument(flowId, document.id().toString(), null)).isEmpty();
        assertThat(flows.setDocumentTechnical(flowId, document.id().toString(), true)).isEmpty();
        assertThat(flows.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        assertThat(flows.apply(flowId)).isEmpty();

        assertThat(rules.stated).hasSize(1);
        RecordingRules.Stated stated = rules.stated.get(0);
        assertThat(stated.body())
            .as("\"text\" is the analyst's own statement of the rule")
            .isEqualTo("Do not use Spring, JPA or SQL — none of them is present.");
        assertThat(stated.excerpt())
            .as("\"excerpt\" is the document's own wording, verbatim, kept alongside the "
                + "paraphrase rather than replaced by it")
            .isEqualTo("This app uses no Spring, no JPA and no SQL.");
    }

    /**
     * Why a rule exists and whether it is HARD reach the stated rule (harness runs 53 and 55,
     * 2026-10-01): the judge holds work to the purpose, and only a hard rule may stop a task. A
     * rule the analyst did not mark "hard" is a preference.
     */
    @Test
    void aConstraintsPurposeAndStrengthReachTheStatedRule() throws Exception {
        rules = new RecordingRules();
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withChat(new ScriptedAnalyst("{\"questions\":[]}", """
                {"proposals":[\
                {"kind":"ADD","ref":"N1","title":"Wire types",\
                "rationale":"the document says so","priority":"CRITICAL",\
                "requirementKind":"CONSTRAINT",\
                "text":"Every type that crosses the wire MUST be a @DataModel.",\
                "purpose":"so both ends can always decode what crosses the wire",\
                "strength":"hard","criteria":[]},\
                {"kind":"ADD","ref":"N2","title":"Text in bundles",\
                "rationale":"the document says so","priority":"LOW",\
                "requirementKind":"CONSTRAINT",\
                "text":"User-visible text lives in resource bundles.",\
                "purpose":"so the text can be translated later","criteria":[]}\
                ]}"""))
            .withGuidelineControl(rules));

        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId,
            "tech-notes.md", "text/markdown",
            "Wire types MUST be data models. Text lives in bundles.".getBytes(StandardCharsets.UTF_8));
        assertThat(result.failed()).describedAs(result.error()).isFalse();
        String flowId = flows.intake().flow().id().toString();
        assertThat(flows.addDocument(flowId, result.document().id().toString(), null)).isEmpty();
        assertThat(flows.setDocumentTechnical(flowId, result.document().id().toString(), true))
            .isEmpty();
        assertThat(flows.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        assertThat(flows.apply(flowId)).isEmpty();

        assertThat(rules.stated).hasSize(2);
        RecordingRules.Stated wire = rules.stated.stream()
            .filter(s -> s.title().equals("Wire types")).findFirst().orElseThrow();
        assertThat(wire.purpose()).isEqualTo("so both ends can always decode what crosses the wire");
        assertThat(wire.hard()).as("the analyst said hard").isTrue();
        RecordingRules.Stated text = rules.stated.stream()
            .filter(s -> s.title().equals("Text in bundles")).findFirst().orElseThrow();
        assertThat(text.purpose()).isEqualTo("so the text can be translated later");
        assertThat(text.hard()).as("no strength given is a preference").isFalse();
    }

    /**
     * The part of the project a rule applies to reaches the stated rule (owner's decision
     * 2026-10-07, section 65), and the analyst is given the project's module folders to choose
     * from. A rule the analyst gave no part is a rule of the whole project.
     */
    @Test
    void aConstraintsPartOfTheProjectReachesTheStatedRule() throws Exception {
        rules = new RecordingRules();
        rules.modules = List.of("client", "server");
        ScriptedAnalyst analyst = new ScriptedAnalyst("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Screens are descriptors",\
            "rationale":"the document says so","priority":"HIGH",\
            "requirementKind":"CONSTRAINT",\
            "text":"In the browser client a screen is drawn from a descriptor.",\
            "appliesTo":["client"],"criteria":[]},\
            {"kind":"ADD","ref":"N2","title":"No frameworks",\
            "rationale":"the document says so","priority":"HIGH",\
            "requirementKind":"CONSTRAINT",\
            "text":"Do not use Spring anywhere.","criteria":[]}\
            ]}""");
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withChat(analyst)
            .withGuidelineControl(rules));

        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId,
            "tech-notes.md", "text/markdown",
            "Client screens are descriptors. No Spring.".getBytes(StandardCharsets.UTF_8));
        assertThat(result.failed()).describedAs(result.error()).isFalse();
        String flowId = flows.intake().flow().id().toString();
        assertThat(flows.addDocument(flowId, result.document().id().toString(), null)).isEmpty();
        assertThat(flows.setDocumentTechnical(flowId, result.document().id().toString(), true))
            .isEmpty();
        assertThat(flows.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        assertThat(flows.apply(flowId)).isEmpty();

        assertThat(String.join("\n", analyst.seen))
            .as("the analyst is given the module folders, from the project's tree")
            .contains(RequirementsIntake.PARTS_HEADING).contains("client\nserver");
        assertThat(rules.stated).hasSize(2);
        RecordingRules.Stated screens = rules.stated.stream()
            .filter(s -> s.title().equals("Screens are descriptors")).findFirst().orElseThrow();
        assertThat(screens.appliesTo()).containsExactly("client");
        RecordingRules.Stated frameworks = rules.stated.stream()
            .filter(s -> s.title().equals("No frameworks")).findFirst().orElseThrow();
        assertThat(frameworks.appliesTo()).as("no part given: the whole project").isEmpty();
    }

    // --- fixtures ---------------------------------------------------------------------------

    /** Runs the real intake over one document and applies both proposals. @return the apply result */
    private String applyOneRuleAndOneFeature() {
        SourceDocument document = upload();
        String flowId = flows.intake().flow().id().toString();
        assertThat(flows.addDocument(flowId, document.id().toString(), null)).isEmpty();
        assertThat(flows.setDocumentTechnical(flowId, document.id().toString(), true)).isEmpty();
        assertThat(flows.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        return flows.apply(flowId);
    }

    private SourceDocument upload() {
        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId,
            "bookshelf-tech-requirements.md", "text/markdown",
            ("This app uses no Spring and no SQL. Readers can list the books they have added.")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(result.failed()).describedAs(result.error()).isFalse();
        return result.document();
    }

    private void install(boolean rulesConnected) {
        rules = new RecordingRules();
        ConsoleContext context = new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withChat(new ScriptedAnalyst("{\"questions\":[]}", proposals()));
        if (rulesConnected) {
            context = context.withGuidelineControl(rules);
        }
        ConsoleContext.set(context);
    }

    /** One rule and one ordinary requirement, so the routing decision is visible both ways. */
    private static String proposals() {
        return """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"What this project must not use",\
            "rationale":"the document says so","priority":"CRITICAL",\
            "requirementKind":"CONSTRAINT",\
            "text":"Do not use Spring, JPA, SQL, REST or JavaScript. None of them is present and \
            none will be added.","criteria":[]},\
            {"kind":"ADD","ref":"N2","title":"Reading list",\
            "rationale":"the document says so","priority":"HIGH",\
            "requirementKind":"FUNCTIONAL",\
            "text":"A reader can see the books they have added.",\
            "criteria":[{"text":"a book that was added appears in the list",\
            "test":"swarm.accept.ReadingListTest#listsAnAddedBook"}]}\
            ]}""";
    }

    private void await(String flowId, GuidedFlowState expected) {
        UUID id = UUID.fromString(flowId);
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        GuidedFlowState seen = null;
        while (System.currentTimeMillis() < deadline) {
            GuidedFlow flow = store.getGuidedFlow(id);
            if (flow != null) {
                seen = flow.state();
                if (seen == expected) {
                    return;
                }
                if (seen == GuidedFlowState.FAILED) {
                    throw new AssertionError("the analysis FAILED: " + flow.error());
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + expected, e);
            }
        }
        throw new AssertionError("the flow never reached " + expected + " — last state " + seen);
    }

    /** The project's rules seam, recorded rather than written to a checkout. */
    private static final class RecordingRules implements ConsoleContext.GuidelineControl {
        record Stated(String title, String body, String document, String excerpt,
                      String purpose, boolean hard, List<String> appliesTo) {}

        /** The module folders the project's tree is said to hold. */
        private List<String> modules = List.of();

        @Override
        public List<String> ruleScopes() {
            return modules;
        }

        private final List<Stated> stated = Collections.synchronizedList(new ArrayList<>());
        /** The documents whose earlier statement was superseded, in order, for assertions. */
        private final List<String> superseded = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String setStatus(UUID guidelineId, String status) {
            return "";
        }

        @Override
        public String setCheck(UUID guidelineId, String command, int timeoutSeconds) {
            return "";
        }

        @Override
        public String stateRule(String title, String body, String document) {
            return stateRule(title, body, document, null);
        }

        /** Overridden too, not just inherited from the interface's default: a caller relying on the
         * default would silently drop the excerpt, which is the exact regression the round-trip
         * test above exists to catch. */
        @Override
        public String stateRule(String title, String body, String document, String excerpt) {
            return stateRule(title, body, document, excerpt, null, false);
        }

        @Override
        public String stateRule(String title, String body, String document, String excerpt,
                                String purpose, boolean hard) {
            return stateRule(title, body, document, excerpt, purpose, hard, null);
        }

        @Override
        public String stateRule(String title, String body, String document, String excerpt,
                                String purpose, boolean hard, List<String> appliesTo) {
            stated.add(new Stated(title, body, document, excerpt, purpose, hard,
                appliesTo == null ? List.of() : List.copyOf(appliesTo)));
            return "";
        }

        @Override
        public int supersedeRulesFrom(String document) {
            superseded.add(document);
            return 0;
        }
    }

    /** Scripted replies in order; the last one repeats. */
    private static final class ScriptedAnalyst implements ConsoleContext.ChatModel {
        private final List<String> replies;
        private final AtomicInteger call = new AtomicInteger();
        /** Every message the analyst was sent, in order. */
        private final List<String> seen = Collections.synchronizedList(new ArrayList<>());

        ScriptedAnalyst(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public Stream<String> stream(List<Map<String, String>> messages, String modelOverride) {
            int index = call.getAndIncrement();
            messages.forEach(message -> seen.add(String.valueOf(message.get("content"))));
            return Stream.of(index < replies.size() ? replies.get(index)
                : replies.get(replies.size() - 1));
        }
    }
}
