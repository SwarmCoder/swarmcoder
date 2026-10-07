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
package com.swarmcoder.app;

import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.console.DocumentIngest;
import com.swarmcoder.console.GuidedFlowServiceImpl;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.ProjectRules;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.FakeVllm;
import com.swarmcoder.workflow.ArchitectClient;
import com.swarmcoder.workflow.StoryScope;
import com.swarmcoder.workflow.TaskGraphValidator;
import com.swarmcoder.workflow.TestAuthorClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole capability, on the document it was built for: {@code dev/bookshelf-tech-requirements.md}
 * goes in one end, and comes out the other in the architect's prompt and the test author's prompt,
 * without stopping a single run on its way.
 *
 * <p><b>The failure this exists to end.</b> {@code dev/bookshelf-demo} is a ZeroZ Stack project:
 * pure Java, a browser client compiled by TeaVM, calls carried over a binary WebSocket, an
 * EclipseStore object graph. There is no Spring in it, no JPA, no SQL, no REST and no JavaScript,
 * and there never will be. On 2026-08-31, given no technical context whatever, the test author
 * wrote its acceptance tests for that project against a {@code @SpringBootApplication} class, a
 * Flyway migration creating a USERS table, and a Spring Data repository over a {@code @Entity}.
 * None of those exist, so the tests could never pass, so every candidate for that story would have
 * failed for ever. The model had not misunderstood the story — nothing had told it what the project
 * was made of, and a model told nothing falls back on the commonest stack.
 *
 * <p><b>Where the rules land, and why that changed the same day.</b> They land in the project's
 * GUIDELINES — the mechanism SwarmCoder already had for a rule this project must follow, with a
 * scope, a status, a screen that turns one on and off, and a command that can prove one was
 * obeyed. They spent a few hours as a third kind of BRD requirement instead,
 * which needed nine exemptions to keep them out of machinery that assumes everything in it gets
 * delivered. Two gaps had to be closed for guidelines to do the job, and both are asserted below:
 * a person can now STATE a rule rather than waiting for a build to fail and a model to learn one,
 * and rules reach the ARCHITECT and the TEST AUTHOR rather than workers alone.
 *
 * <p><b>What is real here.</b> The real document, byte for byte off disk. The real ingest, the real
 * intake wizard on its own thread, the real proposal parsing and the real apply. The real rules,
 * written into the real store as objects (nothing touches the checkout). The real requirement graph, the real
 * story slice, the real PLAN validator, the real architect client and the real test author client,
 * both talking real HTTP to an in-process endpoint. Only the analyst's and the architect's WORDS
 * are scripted — no paid endpoint is contacted, here or anywhere in this feature's tests.
 */
class TechnicalDocumentReachesTheBuildTest {

    private static final long WAIT_MILLIS = 20_000;

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;
    private GuidedFlowServiceImpl flows;
    private ProjectRules rules;

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir.resolve("store"));
        flows = new GuidedFlowServiceImpl();
        rules = new ProjectRules(store, projectId);
    }

    @AfterEach
    void closeStore() throws Exception {
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    @Test
    void theBookshelfTechnicalDocumentBecomesRulesThatReachEveryoneAndParkNothing() throws Exception {
        FakeAnalyst analyst = install("{\"questions\":[]}", ruleProposals());

        // --- 1. the real document, off disk -----------------------------------------------------
        Path document = repoFile("dev/bookshelf-tech-requirements.md");
        String text = Files.readString(document);
        assertThat(text).contains("ZeroZ Stack").contains("EclipseStore");

        SourceDocument uploaded = upload(document.getFileName().toString(), text);
        String flowId = flows.intake().flow().id().toString();
        assertThat(flows.addDocument(flowId, uploaded.id().toString(), null)).isEmpty();

        // The one bit the operator supplies, and the only thing that separates this document from
        // a business one. Everything downstream follows from it.
        assertThat(flows.setDocumentTechnical(flowId, uploaded.id().toString(), true)).isEmpty();

        assertThat(flows.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);

        // The analyst was told which kind of document it was reading, and told what to do about it.
        assertThat(analyst.prompt(0)).contains("TECHNICAL DOCUMENT: bookshelf-tech-requirements.md");
        assertThat(analyst.prompt(1)).contains("requirementKind\":\"CONSTRAINT");

        assertThat(flows.apply(flowId)).isEmpty();

        // --- 2. they landed as RULES, not as work ----------------------------------------------
        List<LearnedGuideline> stated = statedRules();
        assertThat(stated)
            .describedAs("three rules, as objects of this project in the store")
            .hasSize(3);
        assertThat(dir.resolve("checkout").resolve(".swarmcoder"))
            .describedAs("and nothing was written into the checkout — a rule is not a file")
            .doesNotExist();
        assertThat(stated)
            .describedAs("IN USE the moment they are applied. A rule the operator wrote, in a "
                + "document they attached and ticked, arriving switched off behind a screen they "
                + "have not opened is the dead end this whole change was ordered to remove")
            .allMatch(g -> g.status() == GuidelineStatus.ACTIVE)
            .allMatch(g -> g.scope() == GuidelineScope.PROJECT);
        assertThat(stated)
            .describedAs("and each one says a PERSON stated it, and names the paper it came from — "
                + "the trail the requirements route gave and this one had to keep")
            .allMatch(g -> "stated".equals(g.provenance().source()))
            .allMatch(g -> "bookshelf-tech-requirements.md".equals(g.provenance().document()));

        Brd brd = store.getBrd(projectId);
        assertThat(brd.requirements())
            .describedAs("and NOTHING was written into the requirements document — a rule is not "
                + "work, so it is not in the graph that decides what gets built")
            .isEmpty();

        // --- 3. a real story over the same BRD --------------------------------------------------
        String brief = rules.renderActive(12_000);
        assertThat(brief).contains("Spring").contains("EclipseStore");
        StoryScope scope = StoryScope.resolve(brd, storyOver(addFeature(brd)), brief);
        assertThat(scope.criteria())
            .describedAs("only the feature's own check is deliverable")
            .hasSize(1);

        // --- 4. the plan is accepted, so the run does not park ----------------------------------
        Task task = new Task(UUID.randomUUID(), 1, "List a reader's books", "do it",
            Set.of("bookshelf-demo-server/src/main/java"), Set.of(), List.of(),
            ArchitectClient.ACCEPTANCE_TEST_DIR, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        task.setCriterionIds(Set.of(scope.criteria().get(0).id()));
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(task)), new ArrayList<>());

        TaskGraphValidator.Verdict verdict = new TaskGraphValidator().validate(graph, scope, null);
        assertThat(verdict.ok())
            .describedAs("three rules and not one task answering for them is a CORRECT plan — %s",
                verdict.violations())
            .isTrue();

        // --- 5. and the rules actually arrive, over real HTTP, in both prompts -------------------
        try (FakeVllm endpoint = new FakeVllm(conversation -> FakeVllm.Reply.text(
                conversation.contains("test author") ? testAuthorReply() : plannerReply()))) {
            VllmClient client = new VllmClient(endpoint.baseUrl(), "", "scripted", true);
            CloudGate gate = new CloudGate(10_000_000L, () -> { });

            new ArchitectClient(client, gate).plan(design(), "List a reader's books", scope, "");
            new TestAuthorClient(client, gate).authorTests(dir, task, design(),
                scope.criteria(), scope.constraintBrief());

            assertThat(endpoint.requests).hasSize(2);
            String plannerSaw = endpoint.requests.get(0);
            String testAuthorSaw = endpoint.requests.get(1);

            for (String prompt : List.of(plannerSaw, testAuthorSaw)) {
                assertThat(prompt)
                    .describedAs("the forbidden list is the difference between a runnable test and "
                        + "a Spring Boot test in a project that has no Spring")
                    .contains("Spring")
                    .contains("EclipseStore")
                    .contains("Nothing delivers them");
            }
            assertThat(testAuthorSaw)
                .describedAs("and the author is told a test naming a class this project does not "
                    + "have can never pass, however well it is written")
                .contains("can never pass");
        }
    }

    /**
     * A rule the operator turns off stops reaching anybody, from the same one place.
     *
     * <p>This is the half the requirements route never had: a rule that is in force can be taken
     * out of force from a screen, without editing a document — and the architect and the test
     * author see the change on the next run, because they read the same rendering the workers do.
     */
    @Test
    void aRuleTurnedOffStopsReachingTheArchitectAndTheTestAuthor() throws Exception {
        install("{\"questions\":[]}", ruleProposals());
        SourceDocument uploaded = upload("bookshelf-tech-requirements.md",
            Files.readString(repoFile("dev/bookshelf-tech-requirements.md")));
        String flowId = flows.intake().flow().id().toString();
        assertThat(flows.addDocument(flowId, uploaded.id().toString(), null)).isEmpty();
        assertThat(flows.setDocumentTechnical(flowId, uploaded.id().toString(), true)).isEmpty();
        assertThat(flows.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        assertThat(flows.apply(flowId)).isEmpty();
        assertThat(rules.renderActive(12_000)).contains("Spring");

        for (LearnedGuideline rule : statedRules()) {
            assertThat(rules.setStatus(rule.id(), GuidelineStatus.RETIRED)).isEmpty();
        }

        assertThat(rules.renderActive(12_000))
            .describedAs("no heading with nothing under it — the prompt is what it was before any "
                + "rule existed, so no project's prefill cache goes cold for rules it is not using")
            .isNull();
    }

    /**
     * A project that states no rules must be untouched by any of this — same prompts, same bytes.
     *
     * <p>Every project SwarmCoder has ever run is one of these, so "nothing changes for them" is
     * not a nicety: a heading with nothing under it is noise in every prompt of every run, and a
     * changed prompt is a cold prefill cache on every task.
     */
    @Test
    void aProjectWithNoTechnicalDocumentIsCompletelyUnaffected() throws Exception {
        Brd brd = new Brd(UUID.randomUUID(), projectId, 1, "BRD", new ArrayList<>(),
            new ArrayList<>(), Instant.now(), Instant.now());
        StoryScope scope = StoryScope.resolve(brd, storyOver(addFeature(brd)));

        assertThat(rules.renderActive(12_000)).isNull();
        assertThat(scope.constraintBrief()).isEmpty();

        try (FakeVllm endpoint = new FakeVllm(c -> FakeVllm.Reply.text(plannerReply()))) {
            VllmClient client = new VllmClient(endpoint.baseUrl(), "", "scripted", true);
            new ArchitectClient(client, new CloudGate(10_000_000L, () -> { }))
                .plan(design(), "List a reader's books", scope, "");

            assertThat(endpoint.requests).hasSize(1);
            assertThat(endpoint.requests.get(0))
                .describedAs("no heading with nothing under it, and no sentence about rules that "
                    + "do not exist — this is every project SwarmCoder has ever run")
                .doesNotContain("HOW THIS PROJECT MUST BE BUILT")
                .doesNotContain("standing rules")
                .contains("Story: List a reader's books");
        }
    }

    // --- fixtures ---------------------------------------------------------------------------

    /** This project's rules, in a stable order so assertions do not depend on map iteration. */
    private List<LearnedGuideline> statedRules() {
        return store.root().guidelines.values().stream()
            .filter(g -> projectId.equals(g.projectId()))
            .sorted(Comparator.comparing(LearnedGuideline::slug))
            .toList();
    }

    /** The scripted analyst's proposals: the rules a competent reading of that document yields. */
    private static String ruleProposals() {
        return """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"The stack is fixed",\
            "rationale":"the document names ZeroZ Stack 0.8.0-SNAPSHOT on Java 21 with Maven",\
            "priority":"CRITICAL","requirementKind":"CONSTRAINT",\
            "text":"This app is built on ZeroZ Stack 0.8.0-SNAPSHOT, on Java 21 with Maven. \
            One language from the browser down to the disk: anything that reintroduces a second \
            language or a translation layer defeats the point of it. It looks like an ordinary \
            Java web app and is not one.","criteria":[]},\
            {"kind":"ADD","ref":"N2","title":"What this project must not use",\
            "rationale":"the document lists these explicitly as forbidden",\
            "priority":"CRITICAL","requirementKind":"CONSTRAINT",\
            "text":"Do not use, add, import or write tests against Spring or Spring Boot, JPA or \
            any ORM, Flyway or SQL migrations, a relational database, REST endpoints or JSON, \
            JavaScript or TypeScript, or Vaadin. None of them is present and none will be added. \
            If a piece of work seems to need one, the design is wrong — say so.","criteria":[]},\
            {"kind":"ADD","ref":"N3","title":"Storage is an object graph, not a database",\
            "rationale":"the document calls the nested-save rule the single most likely way to \
            get this project wrong",\
            "priority":"CRITICAL","requirementKind":"CONSTRAINT",\
            "text":"Persistence is EclipseStore. The server keeps live Java objects in memory and \
            writes the graph to disk; there is no SQL, no schema and no mapping layer. Saving an \
            object does not save the objects inside it: every level of nesting you changed needs \
            its own save call, and getting it wrong loses the edit silently.","criteria":[]}\
            ]}""";
    }

    private static String plannerReply() {
        return """
            {"tasks":[{"id":"t1","title":"List a reader's books",\
            "instructions":"Walk the stored graph and render the list.",\
            "writeSet":["bookshelf-demo-server/src/main/java"],"readSet":[],\
            "criteria":[],"criterionRefs":["R4:C1"]}],"edges":[]}""";
    }

    private static String testAuthorReply() {
        return """
            {"files":[{"path":"src/test/java/swarm/accept/ReadingListTest.java",\
            "content":"package swarm.accept; class ReadingListTest { }"}],\
            "wrote":[{"criterion":"a book that was added appears in the list",\
            "test":"swarm.accept.ReadingListTest#listsAnAddedBook"}]}""";
    }

    /** Adds one ordinary deliverable requirement to the BRD and returns its check's id. */
    private UUID addFeature(Brd brd) {
        AcceptanceCriterion check = new AcceptanceCriterion(UUID.randomUUID(),
            "a book that was added appears in the list",
            "swarm.accept.ReadingListTest#listsAnAddedBook");
        check.setStatus(CriterionStatus.ACCEPTED);
        BrdRequirement feature = new BrdRequirement(UUID.randomUUID(), "R4", "Reading list",
            "A reader can see the books they have added.", Priority.HIGH,
            RequirementStatus.ACTIVE, null);
        feature.setCriteria(new ArrayList<>(List.of(check)));
        List<BrdRequirement> all = new ArrayList<>(brd.requirements());
        all.add(feature);
        brd.setRequirements(all);
        return check.id();
    }

    private Story storyOver(UUID criterionId) {
        return new Story(UUID.randomUUID(), projectId, "S1", StoryKind.DELIVERY,
            "List a reader's books", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterionId)), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
    }

    private static DesignDocument design() {
        return new DesignDocument(UUID.randomUUID(), 1, "List a reader's books", List.of(),
            List.of(), List.of(), List.of(), null, Instant.now());
    }

    /** Walks up from the working directory to the repository root and resolves a file in it. */
    private static Path repoFile(String relative) {
        Path here = Paths.get("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        throw new AssertionError(relative + " was not found above " + here
            + " — this test reads the real document, not a copy of it");
    }

    private SourceDocument upload(String filename, String text) {
        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId, filename,
            "text/markdown", text.getBytes(StandardCharsets.UTF_8));
        assertThat(result.failed()).describedAs(result.error()).isFalse();
        return result.document();
    }

    /** The console, wired to the REAL rules of this project in the store. */
    private FakeAnalyst install(String... replies) {
        FakeAnalyst analyst = new FakeAnalyst(replies);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withGuidelineControl(new ConsoleContext.GuidelineControl() {
                @Override
                public String setStatus(UUID guidelineId, String status) {
                    return rules.setStatus(guidelineId, GuidelineStatus.valueOf(status));
                }
                @Override
                public String setCheck(UUID guidelineId, String command, int timeoutSeconds) {
                    return rules.setCheck(guidelineId, command, timeoutSeconds);
                }
                @Override
                public String stateRule(String title, String body, String document) {
                    return rules.stateRule(title, body, document);
                }
                @Override
                public int supersedeRulesFrom(String document) {
                    return rules.supersedeRulesFrom(document);
                }
            })
            .withChat(analyst));
        return analyst;
    }

    private GuidedFlow await(String flowId, GuidedFlowState expected) {
        UUID id = UUID.fromString(flowId);
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        GuidedFlowState seen = null;
        while (System.currentTimeMillis() < deadline) {
            GuidedFlow flow = store.getGuidedFlow(id);
            if (flow != null) {
                seen = flow.state();
                if (seen == expected) {
                    return flow;
                }
                if (seen == GuidedFlowState.FAILED && expected != GuidedFlowState.FAILED) {
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
        throw new AssertionError("the flow never reached " + expected + " within " + WAIT_MILLIS
            + "ms — last state was " + seen);
    }

    /** Scripted replies, and every prompt kept so the test can assert what was actually sent. */
    private static final class FakeAnalyst implements ConsoleContext.ChatModel {

        private final List<String> replies;
        private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger call = new AtomicInteger();

        FakeAnalyst(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public Stream<String> stream(List<Map<String, String>> messages, String modelOverride) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> message : messages) {
                sb.append(message.get("role")).append(": ").append(message.get("content"))
                    .append('\n');
            }
            prompts.add(sb.toString());
            int index = call.getAndIncrement();
            return Stream.of(index < replies.size() ? replies.get(index)
                : replies.get(replies.size() - 1));
        }

        String prompt(int index) {
            assertThat(prompts.size()).as("model calls made").isGreaterThan(index);
            return prompts.get(index);
        }
    }
}
