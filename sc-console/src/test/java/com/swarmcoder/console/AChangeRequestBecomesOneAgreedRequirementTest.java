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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One pasted bug report becomes one agreed requirement, one story and one run — and nothing else.
 *
 * <p>What is under test is the contract around the model, not the model. The analyst is scripted,
 * so every assertion here is about the machinery a person relies on: that reading writes nothing,
 * that the requirement is really AGREED and its checks really ACCEPTED (without which the coverage
 * invariant, criterion evidence and the zero-tests-executed rule are all switched off), that the
 * story delivers exactly those checks, that the run is bound to the story before it starts, that
 * the analyst is asked ONCE and never asks a question back, and that a check can only ever name a
 * test in the protected acceptance package.
 *
 * <p>No model is called: {@link FakeAnalyst} answers from a list.
 */
class AChangeRequestBecomesOneAgreedRequirementTest {

    /** The neighbourhood a real run would have read out of the repository, standing in for one. */
    private static final String NEIGHBOURHOOD = """
        THE NEIGHBOURHOOD OF THIS CHANGE — read out of this repository, not guessed.

        The types this change request is about:
          org.jsoup.select.Selector (class) — src/main/java/org/jsoup/select/Selector.java:88

        Where this behaviour is asserted TODAY:
          src/test/java/org/jsoup/select/SelectorTest.java  (uses 4 of the 5 type(s) above)
        """;

    private static final String ISSUE = """
        String html = "<div><span>abc</span><a>def</a></div>";
        Document doc = Jsoup.parseBodyFragment(html);
        System.out.println(doc.select("div:has(span + a)").size()); // expected = 1, fact = 0
        jsoup 1.17.2 = everything ok
        """;

    private static final String GOOD_REPLY = """
        {"title":"A :has() query with a sibling combinator finds nothing",
         "statement":"Selecting div:has(span + a) over <div><span>abc</span><a>def</a></div> \
returns 0 elements; it should return 1.",
         "priority":"HIGH",
         "assumptions":["That every sibling combinator inside :has() is affected, not only +, \
because the report names one and the code path is shared."],
         "checks":[
           {"text":"div:has(span + a) over that fragment finds one element",
            "test":"swarm.accept.HasSelectorTest#findsADivWhoseSpanIsFollowedByAnAnchor"},
           {"text":"div:has(span + p) over the same fragment still finds nothing",
            "test":"swarm.accept.HasSelectorTest#findsNothingWhenTheSiblingDoesNotMatch"}]}
        """;

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;
    private final List<Started> started = Collections.synchronizedList(new ArrayList<>());

    /** What the run starter was handed — the thing the story binding depends on. */
    private record Started(String goal, String kind, UUID storyId, UUID runId) {}

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir);
    }

    @AfterEach
    void closeStore() throws Exception {
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    // --- reading -------------------------------------------------------------------------------

    @Test
    void readingAsksOnceAndWritesNothing() throws Exception {
        FakeAnalyst analyst = install(GOOD_REPLY);

        ChangeRequestIntake.Understanding understood = ChangeRequestIntake.read(
            ConsoleContext.get(), bugReport(), NEIGHBOURHOOD);

        assertThat(analyst.calls()).as("one model call, and never a question round").isEqualTo(1);
        assertThat(understood.checks()).hasSize(2);
        assertThat(understood.assumptions()).hasSize(1);
        assertThat(store.ensureBrd(projectId).requirements())
            .as("reading must write nothing at all — the operator has not agreed anything yet")
            .isEmpty();
        assertThat(store.listStories(projectId)).isEmpty();
        assertThat(started).isEmpty();
    }

    @Test
    void theAnalystIsShownTheCodeAroundTheChangeAndTheReportVerbatim() throws Exception {
        FakeAnalyst analyst = install(GOOD_REPLY);
        ChangeRequestIntake.read(ConsoleContext.get(), bugReport(), NEIGHBOURHOOD);

        String prompt = analyst.prompt(0);
        assertThat(prompt).contains("src/main/java/org/jsoup/select/Selector.java:88");
        assertThat(prompt).contains("div:has(span + a)");
        assertThat(prompt).contains("swarm.accept");
        assertThat(prompt).as("scope creep at intake is not recoverable downstream")
            .contains("CHANGE WHAT THE FIX NEEDS AND NO MORE");
        assertThat(prompt).as("it must never ask, only assume — and be told so")
            .contains("YOU MAY NOT ASK A QUESTION");
    }

    @Test
    void withNothingReadableFromTheCodeItIsToldToNameNoTypesAtAll() throws Exception {
        FakeAnalyst analyst = install(GOOD_REPLY);
        ChangeRequestIntake.read(ConsoleContext.get(), bugReport(), "");
        assertThat(analyst.prompt(0)).contains("name no types at all");
    }

    // --- writing -------------------------------------------------------------------------------

    @Test
    void oneRequirementIsAgreedCarryingCheckedTestsAndTheStatedAssumption() throws Exception {
        install(GOOD_REPLY);
        ChangeRequestIntake.Started result = ChangeRequestIntake.readAndStart(
            ConsoleContext.get(), projectId, bugReport(), NEIGHBOURHOOD, "");

        Brd brd = store.ensureBrd(projectId);
        assertThat(brd.requirements()).hasSize(1);
        BrdRequirement requirement = brd.requirements().get(0);
        assertThat(requirement.handle()).isEqualTo(result.requirementHandle());
        assertThat(requirement.status())
            .as("a story delivering no AGREED check turns off everything that proves delivery")
            .isEqualTo(RequirementStatus.ACTIVE);
        assertThat(requirement.criteria()).hasSize(2);
        for (AcceptanceCriterion criterion : requirement.criteria()) {
            assertThat(criterion.status()).isEqualTo(CriterionStatus.ACCEPTED);
            assertThat(criterion.testClassOrFile()).startsWith("swarm.accept.").contains("#");
        }
        assertThat(requirement.text())
            .as("what it had to decide is on the requirement, visible and correctable")
            .contains("ASSUMPTION:").contains("every sibling combinator");
    }

    @Test
    void oneStoryDeliversExactlyThoseChecksAndIsBuilding() throws Exception {
        install(GOOD_REPLY);
        ChangeRequestIntake.Started result = ChangeRequestIntake.readAndStart(
            ConsoleContext.get(), projectId, bugReport(), NEIGHBOURHOOD, "");

        List<Story> stories = store.listStories(projectId);
        assertThat(stories).hasSize(1);
        Story story = stories.get(0);
        assertThat(story.id()).isEqualTo(result.story().id());
        assertThat(story.kind()).isEqualTo(StoryKind.DELIVERY);
        assertThat(story.state()).isEqualTo(StoryState.RUNNING);

        List<UUID> agreed = store.ensureBrd(projectId).requirements().get(0).criteria().stream()
            .map(AcceptanceCriterion::id).toList();
        assertThat(story.criterionIds())
            .as("the story claims the agreed checks, all of them and nothing else")
            .containsExactlyInAnyOrderElementsOf(agreed);
        assertThat(story.runIds()).containsExactly(result.runId());
    }

    @Test
    void theRunIsBoundToTheStoryBeforeItStartsAndCarriesTheReportVerbatim() throws Exception {
        install(GOOD_REPLY);
        ChangeRequestIntake.Started result = ChangeRequestIntake.readAndStart(
            ConsoleContext.get(), projectId, bugReport(), NEIGHBOURHOOD, "");

        assertThat(started).hasSize(1);
        Started run = started.get(0);
        assertThat(run.kind()).isEqualTo("BUGFIX");
        assertThat(run.storyId())
            .as("bound before the engine sees it — see AdHocStory#start")
            .isEqualTo(result.story().id());
        assertThat(run.runId()).isEqualTo(result.runId());
        assertThat(run.goal())
            .as("a paraphrase of a bug report loses the reproduction")
            .contains("div:has(span + a)")
            .contains("swarm.accept.HasSelectorTest#findsADivWhoseSpanIsFollowedByAnAnchor");
    }

    @Test
    void anEnhancementStartsAnEnhancementRun() throws Exception {
        install(GOOD_REPLY);
        ChangeRequestIntake.readAndStart(ConsoleContext.get(), projectId,
            new ChangeRequestIntake.Request("Buttons need a space between them",
                "text() returns ReplyReplyToAll, expected Reply ReplyToAll", "ENHANCEMENT"),
            NEIGHBOURHOOD, "");
        assertThat(started).hasSize(1);
        assertThat(started.get(0).kind()).isEqualTo("ENHANCEMENT");
    }

    // --- what it refuses -------------------------------------------------------------------------

    @Test
    void aCheckMayNeverNameATestInTheProjectsOwnTestTree() throws Exception {
        install("""
            {"title":"t","statement":"a; should be b","priority":"MEDIUM","assumptions":[],
             "checks":[{"text":"c","test":"org.jsoup.select.SelectorTest#hasWithSibling"}]}
            """);
        ChangeRequestIntake.Understanding understood = ChangeRequestIntake.read(
            ConsoleContext.get(), bugReport(), NEIGHBOURHOOD);
        assertThat(understood.checks().get(0).test())
            .as("a worker that can edit its own acceptance test can certify itself green")
            .isEqualTo("swarm.accept.SelectorTest#hasWithSibling");
    }

    @Test
    void noMoreThanThreeChecksSurvive() throws Exception {
        install("""
            {"title":"t","statement":"a; should be b","priority":"LOW","assumptions":[],
             "checks":[{"text":"one","test":"swarm.accept.T#a"},
                       {"text":"two","test":"swarm.accept.T#b"},
                       {"text":"three","test":"swarm.accept.T#c"},
                       {"text":"four","test":"swarm.accept.T#d"}]}
            """);
        ChangeRequestIntake.Understanding understood = ChangeRequestIntake.read(
            ConsoleContext.get(), bugReport(), NEIGHBOURHOOD);
        assertThat(understood.checks()).hasSize(ChangeRequestIntake.MAX_CHECKS);
    }

    @Test
    void anUnderstandingWithNoCheckIsRefusedInWordsAndNothingIsWritten() {
        install(GOOD_REPLY);
        ChangeRequestIntake.Understanding empty = new ChangeRequestIntake.Understanding(
            "t", "a; should be b", "MEDIUM", List.of(), List.of());
        assertThatThrownBy(() -> ChangeRequestIntake.start(ConsoleContext.get(), projectId,
                bugReport(), empty))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("how anyone would tell this was fixed");
        assertThat(store.ensureBrd(projectId).requirements()).isEmpty();
        assertThat(store.listStories(projectId)).isEmpty();
        assertThat(started).isEmpty();
    }

    @Test
    void aRequestWithNoIssueTextIsRefusedBeforeAnyModelIsCalled() {
        FakeAnalyst analyst = install(GOOD_REPLY);
        assertThatThrownBy(() -> ChangeRequestIntake.read(ConsoleContext.get(),
                new ChangeRequestIntake.Request("Something", "  ", "BUGFIX"), NEIGHBOURHOOD))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Paste the bug report");
        assertThat(analyst.calls()).isZero();
    }

    @Test
    void aRequestThatIsNeitherBrokenNorAnImprovementIsRefused() {
        install(GOOD_REPLY);
        assertThatThrownBy(() -> ChangeRequestIntake.read(ConsoleContext.get(),
                new ChangeRequestIntake.Request("Something", "it is wrong", "REFACTOR"),
                NEIGHBOURHOOD))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("something broken");
    }

    @Test
    void aReplyThatIsNotJsonIsAskedAgainOnceAndThenGivesUpWithoutWritingAnything() {
        FakeAnalyst analyst = install("I think the selector engine needs a rewrite.",
            "Still not JSON, sorry.");
        assertThatThrownBy(() -> ChangeRequestIntake.read(ConsoleContext.get(), bugReport(),
                NEIGHBOURHOOD))
            .isInstanceOf(MalformedReplyException.class);
        assertThat(analyst.calls())
            .as("the one retry LlmReplyRetry gives every role — not a second question")
            .isEqualTo(2);
        assertThat(store.ensureBrd(projectId).requirements()).isEmpty();
        assertThat(store.listStories(projectId)).isEmpty();
    }

    @Test
    void aReplyThatIsNotJsonTheFirstTimeButIsTheSecondIsAccepted() throws Exception {
        FakeAnalyst analyst = install("here you go:", GOOD_REPLY);
        ChangeRequestIntake.Understanding understood = ChangeRequestIntake.read(
            ConsoleContext.get(), bugReport(), NEIGHBOURHOOD);
        assertThat(analyst.calls()).isEqualTo(2);
        assertThat(understood.checks()).hasSize(2);
    }

    // --- wiring ----------------------------------------------------------------------------------

    private ChangeRequestIntake.Request bugReport() {
        return new ChangeRequestIntake.Request("jsoup :has() bug", ISSUE, "BUGFIX");
    }

    private FakeAnalyst install(String... replies) {
        FakeAnalyst analyst = new FakeAnalyst(replies);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withStoryRuns((goal, kind, storyId) -> {
                UUID runId = UUID.randomUUID();
                started.add(new Started(goal, kind, storyId, runId));
                return runId;
            })
            .withAnalyst(analyst));
        return analyst;
    }

    /** Answers from a list and records what it was asked. Never a network call. */
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

        int calls() {
            return prompts.size();
        }

        String prompt(int index) {
            assertThat(prompts.size()).as("model calls made").isGreaterThan(index);
            return prompts.get(index);
        }
    }
}
