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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Nothing the maintainer wrote is ever shown to the swarm — proved against the real commits, for
 * every case.</b>
 *
 * <p>The whole brownfield measurement rests on one property: the swarm is given the issue and
 * nothing else. If a single line of the maintainer's actual fix reached a prompt, a brief, a file
 * on the tree or a tool result, a green oracle would prove nothing at all — and it would look like
 * the most spectacular success the product had ever had. That is a failure worth a test of its own,
 * precisely because nothing else about it would ever look wrong.
 *
 * <h2>What is looked for</h2>
 *
 * <p>The real fix commit is read out of the real clone and reduced to <b>what the maintainer wrote
 * that nobody has given the swarm</b> — see {@link #theMaintainersOwnWords}, which is where the
 * three filters and the cases that forced each of them are recorded. Both halves count: the fix
 * itself, and the test that judges it.
 *
 * <h2>Where it is looked for</h2>
 *
 * <ol>
 *   <li><b>the tree.</b> The tree the workers are given is cut at the fix's parent, so the
 *       maintainer's test method names may not appear anywhere in it — a worker able to read one
 *       would be reading the name of the check it is about to be judged by;</li>
 *   <li><b>the change request.</b> The title and the issue body are the swarm's whole input, and
 *       they are the ISSUE, never the maintainer's commit message, which was written knowing the
 *       answer;</li>
 *   <li><b>the neighbourhood</b> — everything the machine read out of the code around the change;</li>
 *   <li><b>every prompt actually sent</b>, captured off the wire rather than assumed;</li>
 *   <li><b>the run's goal</b>, the string every later role and every worker reads.</li>
 * </ol>
 *
 * <p>And one property of the oracle itself, because it is the only thing in the harness that
 * touches the fix commit at all: {@link HumanFixOracle#testOnlyHalfOf} extracts the commit's
 * <b>test files only</b> and refuses outright, rather than applying anything, if what git hands
 * back touches a source file. It is exercised here against all five real commits, every one of
 * which does change source files.
 *
 * <h2>No model is called, and none can be</h2>
 *
 * <p>{@link FakeVllm} is a real OpenAI-compatible endpoint on localhost answering from a script,
 * reached through the same {@link VllmClient} the app uses. The base URL is this test's own server,
 * so there is nowhere for a paid call to go.
 */
@RunsWhen(Need.MAVEN)
class TheFixIsNeverShownToTheSwarmTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Wave 2's index cache, reused: a cold parse of jsoup is nine seconds, a warm one a fifth. */
    private static final Path INDEX_CACHE =
        Path.of(System.getProperty("java.io.tmpdir"), "swarmcoder-brownfield-index");

    /**
     * How long an added line must be to be worth looking for.
     *
     * <p>A diff is full of {@code });}, {@code return;} and a closing brace, which occur thousands
     * of times in any 30,000-line project. Only lines with real content are evidence of a leak, and
     * this is what separates the two. Every case is asserted to contribute several, so the filter
     * can never quietly reduce this test to checking nothing.
     */
    private static final int DISTINCTIVE_ENOUGH = 20;

    /** Each case must contribute at least this many searchable lines, or the test says so. */
    private static final int ENOUGH_LINES_TO_BE_A_REAL_CHECK = 4;

    /** The clone, paid for by whichever case ran first — wave 1's, reused, never re-fetched. */
    private static BrownfieldTarget.Clone clone;

    @TempDir
    Path work;

    static List<BrownfieldCases.Case> cases() throws IOException {
        return BrownfieldCases.all();
    }

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void nothingTheSwarmCanSeeCarriesTheMaintainersFix(BrownfieldCases.Case one) throws Exception {
        Path repo = caseTreeFor(one);

        // What the maintainer wrote that nobody has given the swarm: the fix's own new lines, and
        // the new lines of the test that judges it. Both halves must stay out of everything below.
        List<String> theFix = theMaintainersOwnWords(repo, one, one.humanSourceFiles());
        List<String> theOracleItself = theMaintainersOwnWords(repo, one, one.humanTestFiles());
        List<String> everythingTheyWrote = new ArrayList<>(theFix);
        everythingTheyWrote.addAll(theOracleItself);

        assertThat(everythingTheyWrote)
            .as("%s: the commit %s contributes nothing this test could look for, so it would be "
                + "checking nothing at all. Either the case file names the wrong commit, or the "
                + "wrong files, or the threshold above has been raised too far.",
                one.name(), one.fixCommit())
            .hasSizeGreaterThanOrEqualTo(ENOUGH_LINES_TO_BE_A_REAL_CHECK);
        assertThat(theOracleItself)
            .as("%s: every one of these cases adds a test, so the maintainer's test must "
                + "contribute lines of its own", one.name())
            .isNotEmpty();
        System.out.println("[ISOLATION] " + one.name() + ": the maintainer wrote " + theFix.size()
            + " new line(s) of fix and " + theOracleItself.size() + " new line(s) of test; "
            + "looking for every one of them in everything the swarm sees"
            + (theFix.isEmpty() ? " — this fix adds no NEW source line at all: it rearranges "
                + "lines the tree already had, so only the test half is searchable here" : ""));

        // --- 1. the tree the workers are given -------------------------------------------------
        for (String test : one.humanTests()) {
            String method = test.contains("#") ? test.substring(test.indexOf('#') + 1) : test;
            assertThat(whereAnyOfTheseAppear(repo, List.of(method)))
                .as("%s: the maintainer's test method %s must not be anywhere on the tree the "
                    + "workers are given, which is cut at %s", one.name(), method,
                    one.parentCommit())
                .isEmpty();
        }

        // --- 2. the change request, which is the swarm's whole input ---------------------------
        ChangeRequestIntake.Request request =
            new ChangeRequestIntake.Request(one.title(), one.issueText(), one.kind());
        assertNoLeak(one, "the change request the swarm is given", request.wholeRequest(),
            everythingTheyWrote);
        assertThat(request.wholeRequest())
            .as("%s: the issue text is the ISSUE BODY, never the maintainer's commit message, "
                + "which was written knowing the fix", one.name())
            .doesNotContain(one.fixCommit());

        // --- 3. the neighbourhood, read out of the pre-change tree -----------------------------
        BuildLayout.Layout layout = BuildLayout.read(repo, "maven");
        SemanticIndex index = SemanticIndex.over(
            List.of(new KnowledgeCurator.Root("project", repo)),
            INDEX_CACHE.resolve(String.valueOf(one.issue())));
        ChangeNeighbourhood.Neighbourhood neighbourhood = BrownfieldUnderstanding.neighbourhoodOf(
            one.title(), one.issueText(), index, BrownfieldUnderstanding.testRootsOf(layout),
            ChangeNeighbourhood.MAX_CHARS);
        assertNoLeak(one, "the code the machine read around this change", neighbourhood.brief(),
            everythingTheyWrote);
        assertThat(neighbourhood.brief())
            .as("%s: nor the commit itself", one.name())
            .doesNotContain(one.fixCommit());

        // --- 4 and 5. every prompt actually sent, and the goal every role reads -----------------
        List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        try (FakeVllm endpoint = new FakeVllm(prompt -> {
                prompts.add(prompt);
                return FakeVllm.Reply.text(scriptedUnderstandingOf(one));
            });
             ArtifactStore store = new ArtifactStore(work.resolve("store-" + one.issue()))) {

            UUID projectId = UUID.randomUUID();
            VllmClient analyst = new VllmClient(endpoint.baseUrl(), "", "fake-vllm", true);
            List<String> goals = Collections.synchronizedList(new ArrayList<>());
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> null, r -> { }, r -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
                .withStoryRuns((goal, kind, storyId) -> {
                    goals.add(goal);
                    return UUID.randomUUID();
                })
                .withAnalyst((messages, override) ->
                    analyst.chatCompletionStream(messages, null, 0.4)));

            ChangeRequestIntake.readAndStart(ConsoleContext.get(), projectId, request,
                neighbourhood.brief(), "");

            assertThat(prompts)
                .as("the analyst must actually have been asked something, or this check is "
                    + "looking at an empty list")
                .isNotEmpty();
            for (int at = 0; at < prompts.size(); at++) {
                assertNoLeak(one, "prompt " + (at + 1) + " of " + prompts.size()
                    + " sent to the model", prompts.get(at), everythingTheyWrote);
            }
            assertThat(goals).as("the run must have been started, or there is no goal to check")
                .isNotEmpty();
            for (String goal : goals) {
                assertNoLeak(one, "the goal every role and every worker reads", goal,
                    everythingTheyWrote);
            }
            System.out.println("[ISOLATION] " + one.name() + ": clean — none of those "
                + everythingTheyWrote.size() + " line(s) is in the request, the neighbourhood, "
                + "any of the " + prompts.size() + " prompt(s) sent, or the run's goal");
        }

        // --- the oracle's own restriction, exercised against this real commit -------------------
        // The one place in the harness that touches the fix commit at all. Its patch legitimately
        // carries the maintainer's TEST — that is what it is for, and it is applied only after the
        // run has finished, in a throwaway worktree nothing else reads. What it must never carry
        // is the FIX.
        HumanFixOracle.Patch patch = HumanFixOracle.testOnlyHalfOf(repo, one);
        assertThat(patch.usable())
            .as("%s: the test half of %s must be extractable — %s", one.name(), one.fixCommit(),
                patch.note())
            .isTrue();
        assertThat(patch.files())
            .as("%s: the patch the oracle applies must touch the maintainer's TEST files and "
                + "nothing else — %s", one.name(), patch.note())
            .containsExactlyInAnyOrderElementsOf(one.humanTestFiles());
        assertNoLeak(one, "the test-only patch the oracle applies after the run has finished",
            patch.diff(), theFix);
        for (String source : one.humanSourceFiles()) {
            assertThat(patch.diff())
                .as("%s: the oracle's patch must not so much as name the source file %s",
                    one.name(), source)
                .doesNotContain(source);
        }
        System.out.println("[ISOLATION] " + one.name() + ": the oracle's patch touches "
            + patch.files() + " and carries none of the " + theFix.size() + " line(s) of fix");
    }

    // --- what "the answer" is ------------------------------------------------------------------

    /**
     * <b>What the maintainer wrote that nobody has given the swarm</b>: the lines this commit adds
     * to the named files, minus everything that was available anyway.
     *
     * <p>Three filters, and a real case forced every one of them:
     *
     * <ul>
     *   <li><b>Added lines only.</b> A line the commit DELETES is by definition already on the
     *       pre-change tree, so looking for it would fail every case for the one reason that is
     *       not a leak.</li>
     *   <li><b>Not already somewhere on the tree.</b> A fix that MOVES code re-adds text the tree
     *       already had. Measured on issue 2187, whose fix is a +9/−10 rearrangement of one
     *       method: all seven of its added source lines exist verbatim elsewhere in jsoup before
     *       the fix. Those lines are not the answer, and looking for them would make this test red
     *       on a case where nothing whatsoever had leaked.</li>
     *   <li><b>Not in the issue text.</b> The maintainer wrote their test from the same report the
     *       swarm is given, so it reuses the report's own reproduction — issue 2187's test opens
     *       with the exact HTML string in the issue body. The swarm was handed that deliberately.
     *       What it was not handed is everything else.</li>
     * </ul>
     *
     * <p>Both filters compare whitespace-insensitively, because a re-indented line is the same
     * line.
     */
    private static List<String> theMaintainersOwnWords(Path repo, BrownfieldCases.Case one,
                                                       List<String> files) throws Exception {
        List<String> argv = new ArrayList<>(
            List.of("git", "show", "--no-color", "-U0", one.fixCommit(), "--"));
        argv.addAll(files);
        Process process = new ProcessBuilder(argv).directory(repo.toFile())
            .redirectErrorStream(true).start();
        String diff = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git could not show " + one.fixCommit() + ":\n" + diff);
        }
        String alreadyOnTheTree = flatten(everyJavaFileUnder(repo));
        String alreadyInTheReport = flatten(one.title() + "\n" + one.issueText());
        Set<String> own = new LinkedHashSet<>();
        for (String line : diff.split("\\R")) {
            if (!line.startsWith("+") || line.startsWith("+++")) {
                continue;
            }
            String code = line.substring(1).strip();
            String flat = flatten(code);
            if (code.length() >= DISTINCTIVE_ENOUGH && !alreadyOnTheTree.contains(flat)
                    && !alreadyInTheReport.contains(flat)) {
                own.add(code);
            }
        }
        return new ArrayList<>(own);
    }

    /** Every Java file under the tree, end to end — read once, because it is searched per line. */
    private static String everyJavaFileUnder(Path repo) throws IOException {
        StringBuilder all = new StringBuilder();
        try (var walk = Files.walk(repo)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String relative = repo.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith(".git/") || !relative.endsWith(".java")) {
                    continue;
                }
                try {
                    all.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
                } catch (Exception e) {                                    // noqa
                    // Not readable as text; nothing there can leak.
                }
            }
        }
        return all.toString();
    }

    private static String flatten(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    /** Every file under the tree that contains any of these lines, so a failure names the file. */
    private static List<String> whereAnyOfTheseAppear(Path repo, List<String> needles)
            throws IOException {
        List<String> found = new ArrayList<>();
        try (var walk = Files.walk(repo)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String relative = repo.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith(".git/") || !relative.endsWith(".java")) {
                    continue;
                }
                String text;
                try {
                    text = Files.readString(path, StandardCharsets.UTF_8);
                } catch (Exception e) {                                    // noqa
                    continue; // not text; nothing to leak
                }
                for (String needle : needles) {
                    if (text.contains(needle)) {
                        found.add(relative + " carries \"" + shorten(needle) + "\"");
                        break;
                    }
                }
            }
        }
        return found;
    }

    /**
     * Fails naming the line and the place, because "isolation broken" with nothing quoted is a
     * finding nobody can act on.
     */
    private static void assertNoLeak(BrownfieldCases.Case one, String what, String text,
                                     List<String> theAnswer) {
        String haystack = text == null ? "" : text;
        String flatHaystack = flatten(haystack);
        List<String> leaked = new ArrayList<>();
        for (String line : theAnswer) {
            if (haystack.contains(line)) {
                leaked.add(shorten(line));
            } else if (flatHaystack.contains(flatten(line))) {
                // A renderer that re-indented the maintainer's line would still be handing the
                // swarm the maintainer's line.
                leaked.add(shorten(line) + " (whitespace differs)");
            }
        }
        assertThat(leaked)
            .as("%s: %s must not contain anything the maintainer wrote in the fix commit. A single "
                + "line of it here makes every later measurement meaningless, and the run would "
                + "look like a triumph.", one.name(), what)
            .isEmpty();
    }

    private static String shorten(String line) {
        String flat = line.replaceAll("\\s+", " ").strip();
        return flat.length() <= 90 ? flat : flat.substring(0, 90) + "…";
    }

    // --- the scripted analyst, written from the ISSUE and never from the fix --------------------

    private static String scriptedUnderstandingOf(BrownfieldCases.Case one) {
        ObjectNode root = JSON.createObjectNode();
        root.put("title", one.title());
        root.put("statement", "What the report shows happening still happens: "
            + one.title().toLowerCase(Locale.ROOT)
            + ". The behaviour the report says it should have must hold instead.");
        root.put("priority", "HIGH");
        ArrayNode assumptions = root.putArray("assumptions");
        assumptions.add("That the report's single reproduction is the whole of the change.");
        ArrayNode checks = root.putArray("checks");
        ObjectNode only = checks.addObject();
        only.put("text", "The input in the report produces what the report says it should.");
        only.put("test", "swarm.accept.Issue" + one.issue() + "Test#producesWhatTheReportExpects");
        return root.toString();
    }

    // --- the target checkout --------------------------------------------------------------------

    /** Wave 1's clone and the case's own tree, reused. Nothing here fetches or modifies anything. */
    private static synchronized Path caseTreeFor(BrownfieldCases.Case one) throws Exception {
        if (clone == null) {
            BrownfieldCases.File file = BrownfieldCases.file();
            clone = BrownfieldTarget.cloneOnce(file.target(), file.cloneUrl());
        }
        BrownfieldTarget.CaseTree tree = BrownfieldTarget.caseTreeAt(clone.path(), one);
        assertThat(tree.headSha())
            .as("everything below is checked at the commit BEFORE the fix, never after it")
            .isEqualTo(one.parentCommit());
        return tree.path();
    }
}
