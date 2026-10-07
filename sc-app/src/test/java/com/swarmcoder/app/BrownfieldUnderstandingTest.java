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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swarmcoder.console.ChangeRequestIntake;
import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.ChangeNeighbourhood;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.SemanticIndex;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.FakeVllm;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import com.swarmcoder.verify.BuildLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The five real jsoup change requests, read the way the product reads them.</b>
 *
 * <p>Wave 2 of the brownfield design is done when, for all five cases, the machine names the right
 * types with real file:line references and drafts checks a person recognises as the issue restated.
 * This is the first half of that, measured rather than argued: a real 30,000-line repository at the
 * exact commit before each fix landed, the real structural index over it, the real
 * {@link ChangeNeighbourhood}, and the real {@link ChangeRequestIntake} — with the model scripted,
 * so a green build proves the machinery and costs nothing. The second half, that a person
 * recognises the drafted checks, is {@link BrownfieldIntakeLiveTest}, which needs a model and is
 * skipped without one.
 *
 * <h2>No model is called, and none can be</h2>
 *
 * <p>{@link FakeVllm} is a real OpenAI-compatible endpoint on localhost answering from a script, and
 * the intake reaches it through the same {@link VllmClient} the app uses. The base URL is this
 * test's own server, so there is nowhere for a paid call to go.
 *
 * <h2>What it walks</h2>
 *
 * <p>Links 5 and 4 of the chain, through {@link BrownfieldUnderstanding} — the same helper
 * {@link BrownfieldLoopTest} will call once it has a live model, so there is one definition of what
 * those links mean and this is not a second harness. It reuses wave 1's clone and per-case
 * worktrees ({@link BrownfieldTarget}) and wave 1's case file ({@link BrownfieldCases}), so it
 * fetches nothing and builds nothing.
 */
@RunsWhen(Need.MAVEN)
class BrownfieldUnderstandingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The structural index's cache, kept between runs on purpose. A cold parse of jsoup is about
     * nine seconds per case; a warm one is a fifth of a second, and this is meant to be run often.
     */
    private static final Path INDEX_CACHE =
        Path.of(System.getProperty("java.io.tmpdir"), "swarmcoder-brownfield-index");

    /**
     * The types each case's neighbourhood must name, measured on 2026-09-05 against each fix
     * commit's parent. A change that stops one of these being found is a regression in what the
     * machine understands, and this is where it shows up.
     *
     * <p>Issue 2197 has one, and that is the honest answer rather than a lowered bar: its report is
     * an HTML input and an HTML output with no Java in it at all, so the only name the repository
     * recognises anywhere in it is the entry point. The summary line records, per case, whether the
     * file the maintainer actually changed was named — never gated on, because there is more than
     * one right place to fix most of these.
     */
    private static final Map<Integer, List<String>> EXPECTED_TYPES = Map.of(
        2197, List.of("org.jsoup.Jsoup"),
        2187, List.of("org.jsoup.nodes.Document", "org.jsoup.nodes.Element",
            "org.jsoup.select.Selector"),
        2266, List.of("org.jsoup.nodes.Document", "org.jsoup.parser.Parser"),
        2476, List.of("org.jsoup.safety.Cleaner", "org.jsoup.safety.Safelist",
            "org.jsoup.parser.ParseSettings"),
        2105, List.of("org.jsoup.Jsoup", "org.jsoup.nodes.Element"));

    /** One block per case, printed together at the end so five runs read as one measurement. */
    private static final List<String> SUMMARY = Collections.synchronizedList(new ArrayList<>());

    /** The clone, paid for by whichever case ran first — wave 1's, reused, never re-fetched. */
    private static BrownfieldTarget.Clone clone;

    @TempDir
    Path work;

    static List<BrownfieldCases.Case> cases() throws IOException {
        return BrownfieldCases.selected();
    }

    @AfterEach
    void clearContext() {
        // Installed statically; left behind it points a later test at a closed store.
        ConsoleContext.set(null);
    }

    @AfterAll
    static void printTheMeasurement() {
        if (SUMMARY.isEmpty()) {
            return;
        }
        System.out.println("\n=========== WHAT THE MACHINE UNDERSTOOD, PER CASE ===========");
        SUMMARY.stream().sorted().forEach(System.out::println);
        System.out.println("============================================================\n");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theNeighbourhoodNamesRealTypesAndTheIssueBecomesOneAgreedRequirement(
            BrownfieldCases.Case one) throws Exception {

        Path repo = caseTreeFor(one);
        BuildLayout.Layout layout = BuildLayout.read(repo, "maven");
        List<String> testRoots = BrownfieldUnderstanding.testRootsOf(layout);

        SemanticIndex index = SemanticIndex.over(
            List.of(new KnowledgeCurator.Root("project", repo)),
            INDEX_CACHE.resolve(String.valueOf(one.issue())));
        assertThat(index.available())
            .as("the structural index over %s at %s: %s", one.name(), one.parentCommit(),
                index.unavailableReason())
            .isTrue();

        ChangeNeighbourhood.Neighbourhood neighbourhood = BrownfieldUnderstanding.neighbourhoodOf(
            one.title(), one.issueText(), index, testRoots, ChangeNeighbourhood.MAX_CHARS);

        // --- link 5: it names real types, with files and lines --------------------------------
        assertThat(neighbourhood.types().stream().map(ChangeNeighbourhood.NamedType::fqn))
            .as("the types %s is about — %s", one.name(), neighbourhood.describe())
            .containsAll(EXPECTED_TYPES.getOrDefault(one.issue(), List.of()));
        assertThat(everyReferenceIsARealFile(neighbourhood.brief(), repo))
            .as("a neighbourhood with no file:line reference in it is not a neighbourhood")
            .isGreaterThan(0);
        assertThat(neighbourhood.chars()).isLessThanOrEqualTo(ChangeNeighbourhood.MAX_CHARS);
        assertThat(neighbourhood.tests())
            .as("where this behaviour is asserted today is the most valuable line in the brief")
            .isNotEmpty();
        assertThat(neighbourhood.brief())
            .as("the maintainer's fix is never shown to anything")
            .doesNotContain(one.fixCommit());

        ChainLedger chain = new ChainLedger(BrownfieldUnderstanding.chainLinksBeforeTheDesign());
        BrownfieldUnderstanding.recordTheNeighbourhood(chain, neighbourhood);

        // --- link 4: the issue becomes one agreed requirement ---------------------------------
        String reply = scriptedUnderstandingOf(one);
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        try (FakeVllm endpoint = new FakeVllm(prompt -> {
                asked.add(prompt);
                return FakeVllm.Reply.text(reply);
            });
             ArtifactStore store = new ArtifactStore(work.resolve("store-" + one.issue()))) {

            UUID projectId = UUID.randomUUID();
            VllmClient analyst = new VllmClient(endpoint.baseUrl(), "", "fake-vllm", true);
            List<UUID> runs = Collections.synchronizedList(new ArrayList<>());
            List<String> kinds = Collections.synchronizedList(new ArrayList<>());
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> null, r -> { }, r -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
                .withStoryRuns((goal, kind, storyId) -> {
                    // What sc-app's real starter does with these, minus the engine: a run at
                    // RunState.INTAKE carrying its story from the first instant.
                    assertThat(storyId).as("bound to its story before any engine sees it")
                        .isNotNull();
                    kinds.add(kind);
                    UUID runId = UUID.randomUUID();
                    runs.add(runId);
                    return runId;
                })
                .withAnalyst((messages, override) ->
                    analyst.chatCompletionStream(messages, null, 0.4)));

            ChangeRequestIntake.Started started = BrownfieldUnderstanding.walkTheChangeRequest(
                chain, ConsoleContext.get(), projectId,
                new ChangeRequestIntake.Request(one.title(), one.issueText(), one.kind()),
                neighbourhood.brief(), "");

            assertThat(runs).containsExactly(started.runId());
            assertThat(kinds).containsExactly(one.kind());
            assertThat(asked)
                .as("one model call for one change request — never a question round")
                .hasSize(1);
            assertThat(asked.get(0))
                .as("the analyst is shown what the code around this change really contains")
                .contains("THE NEIGHBOURHOOD OF THIS CHANGE");

            SUMMARY.add(String.format(
                "%s%n"
                + "    types (%d): %s%n"
                + "    file:line references: %d      brief: %d chars (~%d tokens)%n"
                + "    tests it points at: %s%n"
                + "    names the file the maintainer changed? %-5s   their test file? %s%n"
                + "    became %s [AGREED] with %d check(s), story %s, one run started as %s",
                one.name() + " — " + one.title(),
                neighbourhood.types().size(),
                neighbourhood.types().stream().map(ChangeNeighbourhood.NamedType::fqn).toList(),
                neighbourhood.references(), neighbourhood.chars(), neighbourhood.chars() / 4,
                neighbourhood.tests().stream().map(ChangeNeighbourhood.CoveringTest::file)
                    .map(BrownfieldUnderstandingTest::fileName).toList(),
                namesAnyOf(neighbourhood.brief(), one.humanSourceFiles()),
                namesAnyOf(neighbourhood.brief(), one.humanTestFiles()),
                started.requirementHandle(), started.understanding().checks().size(),
                started.story().key(), one.kind()));
        } finally {
            System.out.println(chain.report());
        }
        assertThat(chain.whole()).as(chain.verdict()).isTrue();
    }

    // --- the scripted analyst ------------------------------------------------------------------

    /**
     * What a competent analyst would answer for this case, as JSON.
     *
     * <p>Written from the ISSUE, never from the fix: the swarm is never shown the maintainer's
     * commit and neither is this. Built with Jackson rather than quoted by hand, because a reply
     * carrying an HTML reproduction is one backslash away from testing the escaping instead.
     */
    private static String scriptedUnderstandingOf(BrownfieldCases.Case one) {
        ObjectNode root = JSON.createObjectNode();
        root.put("title", one.title());
        root.put("statement", "What the report shows happening still happens: " + one.title()
            + ". The behaviour the report says it should have must hold instead.");
        root.put("priority", "HIGH");
        ArrayNode assumptions = root.putArray("assumptions");
        assumptions.add("That the report's single reproduction is the whole of the change, "
            + "because it names no other case.");
        ArrayNode checks = root.putArray("checks");
        ObjectNode first = checks.addObject();
        first.put("text", "The input in the report produces what the report says it should, "
            + "not what it says it does.");
        first.put("test", "swarm.accept.Issue" + one.issue() + "Test#producesWhatTheReportExpects");
        ObjectNode second = checks.addObject();
        second.put("text", "An input the report does not mention behaves exactly as it does "
            + "today.");
        second.put("test", "swarm.accept.Issue" + one.issue() + "Test#leavesEverythingElseAlone");
        return root.toString();
    }

    // --- the target checkout ---------------------------------------------------------------

    /** Wave 1's clone and the case's own tree, reused. Nothing here fetches or modifies anything. */
    private static synchronized Path caseTreeFor(BrownfieldCases.Case one) throws Exception {
        if (clone == null) {
            BrownfieldCases.File file = BrownfieldCases.file();
            clone = BrownfieldTarget.cloneOnce(file.target(), file.cloneUrl());
            System.out.println("[UNDERSTANDING] " + clone.describe());
        }
        BrownfieldTarget.CaseTree tree = BrownfieldTarget.caseTreeAt(clone.path(), one);
        System.out.println("[UNDERSTANDING] " + one.name() + ": " + tree.describe());
        assertThat(tree.headSha())
            .as("the neighbourhood must be read at the commit BEFORE the fix, never after it")
            .isEqualTo(one.parentCommit());
        return tree.path();
    }

    // --- measuring ----------------------------------------------------------------------------

    /** How many {@code path:line} references the brief carries — every one a file that exists. */
    private static int everyReferenceIsARealFile(String brief, Path repo) {
        Matcher references = Pattern.compile("(src/[A-Za-z0-9_/.$-]+\\.java):(\\d+)")
            .matcher(brief);
        int checked = 0;
        while (references.find()) {
            assertThat(Files.isRegularFile(repo.resolve(references.group(1))))
                .as("the brief quotes %s, which must be a real file in %s",
                    references.group(0), repo)
                .isTrue();
            assertThat(Integer.parseInt(references.group(2))).isGreaterThan(0);
            checked++;
        }
        return checked;
    }

    /** Reported, never gated: whether the brief happened to name a file the maintainer touched. */
    private static boolean namesAnyOf(String brief, List<String> files) {
        return files.stream().anyMatch(brief::contains);
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
