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

import com.swarmcoder.console.ChangeRequestIntake;
import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.ChangeNeighbourhood;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.SemanticIndex;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import com.swarmcoder.verify.BuildLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
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
 * <b>A real model reads a real bug report, and a person reads back what it understood.</b>
 *
 * <p>{@link BrownfieldUnderstandingTest} proves the machinery with the model scripted, which is
 * everything except the one thing wave 2 is actually for: that the checks the analyst drafts are
 * the issue restated, in words somebody would recognise. No assertion can settle that. So this
 * test does two things — it PRINTS the whole understanding, in the shape the console will show it,
 * for a person to read; and it asserts the properties that would make the printed text worthless
 * if they were missing.
 *
 * <h2>What it asserts, and what it leaves to the reader</h2>
 *
 * <ul>
 *   <li><b>Asserted:</b> one model call and no question; one to three checks; every check names a
 *       test in the protected acceptance package; the requirement is stated as a difference rather
 *       than as a fix, so it names no file and no line; and the drafted text shares the report's
 *       own vocabulary rather than inventing its own — measured as how many of the distinctive
 *       words of the report come back in the understanding.</li>
 *   <li><b>Left to the reader:</b> whether it is RIGHT. That is what the printed block is for.</li>
 * </ul>
 *
 * <h2>No paid call, ever</h2>
 *
 * <p>{@link #refuseAnythingButAFreeLocalEndpoint} is copied from {@link EndToEndLoopTest} and
 * refuses to start unless the endpoint is plain HTTP on a private address, so a mistyped flag
 * cannot quietly spend money. The operator's {@code ~/.swarmcoder/config.yaml}, which points seven
 * roles at a billed endpoint, is never loaded.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * mvn -o test -pl sc-app -am -Dtest=BrownfieldIntakeLiveTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://192.168.0.10:8002/v1 -Dswarmcoder.live.model=qwen3.8-27b
 * </pre>
 *
 * <p>{@code -Dswarmcoder.brownfield.case=2187} picks one case; without it every case in the file is
 * read, one after another, which is a handful of minutes on a local 27B.
 */
@RunsWhen({Need.LIVE_MODEL, Need.MAVEN})
class BrownfieldIntakeLiveTest {

    /** The index cache, shared with the scripted test so a case is parsed once on this machine. */
    private static final Path INDEX_CACHE =
        Path.of(System.getProperty("java.io.tmpdir"), "swarmcoder-brownfield-index");

    /**
     * How much of the report's own distinctive vocabulary must come back in the understanding.
     *
     * <p>A weak instrument on purpose, and it is the difference between a restatement and a
     * paraphrase-from-memory: an analyst that answered "the selector engine has a bug" about a
     * report naming {@code parseBodyFragment} and {@code div:has(span + a)} would score near zero.
     * It cannot tell a good restatement from a mediocre one — that is the printed block's job.
     */
    private static final double VOCABULARY_FLOOR = 0.10;

    /** Words every bug report is full of, which prove nothing about whether it was read. */
    private static final Set<String> NOT_DISTINCTIVE = Set.of(
        "the", "and", "that", "this", "with", "from", "have", "has", "was", "were", "not", "but",
        "for", "you", "your", "when", "then", "there", "here", "should", "would", "could", "does",
        "did", "get", "got", "output", "input", "expected", "actual", "issue", "bug", "jsoup",
        "java", "code", "text", "test", "tests", "using", "used", "same", "also", "still", "into",
        "what", "which", "will", "can", "one", "two", "html");

    @TempDir
    Path work;

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
    }

    @Test
    void whatTheMachineUnderstoodIsTheReportRestated() throws Exception {
        String baseUrl = refuseAnythingButAFreeLocalEndpoint();
        String model = System.getProperty("swarmcoder.live.model", "qwen3.8-27b");
        System.out.println("[LIVE INTAKE] endpoint=" + baseUrl + " model=" + model);

        BrownfieldCases.File file = BrownfieldCases.file();
        BrownfieldTarget.Clone clone = BrownfieldTarget.cloneOnce(file.target(), file.cloneUrl());
        List<String> failures = new ArrayList<>();

        for (BrownfieldCases.Case one : BrownfieldCases.selected()) {
            try {
                readOneCase(baseUrl, model, clone, one);
            } catch (AssertionError e) {
                // Every case is read before anything fails, so one run tells you about all five
                // rather than about whichever happened to be first.
                failures.add(one.name() + ": " + e.getMessage());
                System.out.println("[LIVE INTAKE] " + one.name() + " FAILED: " + e.getMessage());
            }
        }
        assertThat(failures).as("cases whose understanding did not hold up").isEmpty();
    }

    private void readOneCase(String baseUrl, String model, BrownfieldTarget.Clone clone,
                             BrownfieldCases.Case one) throws Exception {
        BrownfieldTarget.CaseTree tree = BrownfieldTarget.caseTreeAt(clone.path(), one);
        BuildLayout.Layout layout = BuildLayout.read(tree.path(), "maven");
        SemanticIndex index = SemanticIndex.over(
            List.of(new KnowledgeCurator.Root("project", tree.path())),
            INDEX_CACHE.resolve(String.valueOf(one.issue())));

        ChangeNeighbourhood.Neighbourhood neighbourhood = BrownfieldUnderstanding.neighbourhoodOf(
            one.title(), one.issueText(), index, BrownfieldUnderstanding.testRootsOf(layout),
            ChangeNeighbourhood.MAX_CHARS);

        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        try (ArtifactStore store = new ArtifactStore(work.resolve("store-" + one.issue()))) {
            UUID projectId = UUID.randomUUID();
            VllmClient analyst = new VllmClient(baseUrl, "", model, true);
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> null, r -> { }, r -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
                .withStoryRuns((goal, kind, storyId) -> UUID.randomUUID())
                .withAnalyst((messages, override) -> {
                    asked.add(messages.toString());
                    return analyst.chatCompletionStream(messages, null, 0.4);
                }));

            long started = System.currentTimeMillis();
            ChangeRequestIntake.Understanding understood = ChangeRequestIntake.read(
                ConsoleContext.get(),
                new ChangeRequestIntake.Request(one.title(), one.issueText(), one.kind()),
                neighbourhood.brief(), "");
            long took = System.currentTimeMillis() - started;

            print(one, neighbourhood, understood, took);

            // One call, or two when the first reply was not readable JSON and LlmReplyRetry handed
            // it back with the parser's complaint. That is the same call asked again, and it is
            // what every role in this product gets; what is being ruled out here is a QUESTION
            // ROUND — the analyst coming back for an answer nobody is there to give. Two is
            // reported rather than passed over in silence, because a model that needs the retry
            // every time is a prompt problem worth seeing.
            assertThat(asked.size())
                .as("one model call for one change request, or two when the first reply was not "
                    + "JSON — never a question round")
                .isBetween(1, 2);
            if (asked.size() > 1) {
                System.out.println("[LIVE INTAKE] " + one.name() + ": the first reply was not "
                    + "readable JSON and was asked again — " + asked.size() + " calls");
            }
            assertThat(understood.checks().size())
                .as("one to three checks: %s", understood.describe())
                .isBetween(ChangeRequestIntake.MIN_CHECKS, ChangeRequestIntake.MAX_CHECKS);
            for (ChangeRequestIntake.Check check : understood.checks()) {
                assertThat(check.test())
                    .as("a check may only ever name a test the workers cannot edit")
                    .startsWith("swarm.accept.").contains("#");
                assertThat(check.text().strip().length())
                    .as("a check nobody could run is not a check: %s", check)
                    .isGreaterThan(15);
            }
            assertThat(understood.statement())
                .as("the requirement states the difference, never the fix — no file paths")
                .doesNotContain(".java:").doesNotContain("src/main/");
            assertThat(understood.rejection())
                .as("everything the writing path needs must already be true here")
                .isNull();

            double shared = sharedVocabulary(one, understood);
            assertThat(shared)
                .as("the understanding must be the REPORT restated, not a paraphrase from "
                    + "memory — %.0f%% of the report's distinctive words came back", shared * 100)
                .isGreaterThanOrEqualTo(VOCABULARY_FLOOR);
        }
    }

    /**
     * The whole understanding, in the shape §7.2 of the design puts on the screen.
     *
     * <p>Printed for a person, because "a person recognises it as the issue restated" is wave 2's
     * own done-when and no assertion decides it.
     */
    private static void print(BrownfieldCases.Case one, ChangeNeighbourhood.Neighbourhood found,
                              ChangeRequestIntake.Understanding understood, long millis) {
        StringBuilder sb = new StringBuilder("\n")
            .append("==================================================================\n")
            .append("HERE IS WHAT WE UNDERSTOOD — ").append(one.name()).append(" — ")
            .append(one.title()).append("\n")
            .append("read in ").append(millis / 1000).append("s\n")
            .append("==================================================================\n\n")
            .append("THE REPORT SAID:\n").append(indent(one.issueText())).append("\n\n")
            .append("THE PROBLEM:\n").append(indent(understood.statement())).append("\n\n")
            .append("WE WILL KNOW IT IS FIXED WHEN:\n");
        for (ChangeRequestIntake.Check check : understood.checks()) {
            sb.append("  - ").append(check.text()).append("\n        proved by ")
              .append(check.test()).append('\n');
        }
        if (understood.assumptions().isEmpty()) {
            sb.append("\nWE ASSUMED: nothing — the report settled everything.\n");
        } else {
            sb.append("\nWE ASSUMED:\n");
            understood.assumptions().forEach(a -> sb.append("  - ").append(a).append('\n'));
        }
        sb.append("\nWE LOOKED AT YOUR CODE AND FOUND:\n").append(indent(found.brief()))
          .append("\n==================================================================\n")
          .append("FOR COMPARISON — what the maintainer actually changed (never shown to the "
              + "machine): ").append(one.humanSourceFiles()).append(", tests ")
          .append(one.humanTests()).append('\n')
          .append("==================================================================\n");
        System.out.println(sb);
    }

    /**
     * The share of the report's distinctive words that come back in the understanding.
     *
     * <p>Distinctive means: at least four letters, not one of the words every bug report is full
     * of, and appearing in the report. Identifiers and the strings of a reproduction survive this;
     * "the output was not what I expected" does not.
     */
    private static double sharedVocabulary(BrownfieldCases.Case one,
                                           ChangeRequestIntake.Understanding understood) {
        Set<String> distinctive = new LinkedHashSet<>();
        for (String word : (one.title() + " " + one.issueText())
                .toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (word.length() >= 4 && !NOT_DISTINCTIVE.contains(word)) {
                distinctive.add(word);
            }
        }
        if (distinctive.isEmpty()) {
            return 1.0;
        }
        StringBuilder answer = new StringBuilder(understood.title()).append(' ')
            .append(understood.statement());
        understood.checks().forEach(c -> answer.append(' ').append(c.text())
            .append(' ').append(c.test()));
        understood.assumptions().forEach(a -> answer.append(' ').append(a));
        String said = answer.toString().toLowerCase(Locale.ROOT);
        int found = 0;
        for (String word : distinctive) {
            if (said.contains(word)) {
                found++;
            }
        }
        System.out.println("[LIVE INTAKE] " + one.name() + ": " + found + " of "
            + distinctive.size() + " distinctive word(s) of the report came back");
        return (double) found / distinctive.size();
    }

    private static String indent(String text) {
        return "    " + text.strip().replace("\n", "\n    ");
    }

    /**
     * Copied verbatim from {@link EndToEndLoopTest}: only a free local endpoint, ever.
     *
     * <p>Copied rather than shared because it is a refusal, and a refusal that lives somewhere else
     * is one refactor away from being relaxed for a caller that is not this one.
     */
    private static String refuseAnythingButAFreeLocalEndpoint() {
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AssertionError("swarmcoder.live.baseUrl was not set — @RunsWhen should have "
                + "skipped this test rather than starting it");
        }
        URI uri = URI.create(baseUrl);
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean localOnly = "localhost".equals(host) || host.startsWith("127.")
            || host.startsWith("10.") || host.startsWith("192.168.")
            || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
        if (!"http".equals(uri.getScheme()) || !localOnly) {
            throw new AssertionError("this harness runs only against a free local model server. "
                + baseUrl + " is not plain HTTP on a private address, so it may be a billed "
                + "endpoint. Refusing to start rather than risk spending money.");
        }
        return baseUrl;
    }
}
