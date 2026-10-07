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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A story that has stopped still offers "Build it again" while questions are waiting on it.
 *
 * <p><b>The day this exists for.</b> A model server was restarted mid-build. Seven questions were
 * raised against one story, the story stopped, and the board offered the operator exactly one
 * button: "Answer these". "Build it again" was not on the card at all — it was never created —
 * because the card chose between the story's action and the questions instead of showing both. Each
 * question refuses a blank answer, so getting the story moving again meant writing seven pieces of
 * prose about a network outage first.
 *
 * <p><b>Why it may never gate.</b> Answering a question "records the answer and nothing else: it
 * does not restart a run or retry a task" — the control interface's own words, in
 * {@code SwarmMcpTools}. A question is a record of something that happened, not a precondition.
 * Nothing functional depends on it, so nothing functional may be withheld until it is answered.
 * UX v3 §5 rule 5 says the same thing from the other end: every gate carries its own exit, and a
 * state with no exit reachable from its own card is a defect.
 *
 * <p><b>Its own class because only one embedded Console works per JVM</b> — see the note on
 * {@code ConsoleBacklogBrowserTest}; Surefire's {@code reuseForks=false} gives each browser class a
 * fresh one. All the states this has to cover are therefore put on one board and checked in one
 * pass, rather than costing a forked JVM each.
 */
class ConsoleStoppedStoryQuestionsBrowserTest {

    @BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void aStoppedStoryStillOffersItsRetryWhileQuestionsWait(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Project project = new Project(projectId, "demo", dir.toString(), List.of(),
                Instant.now(), false);
            store.saveProject(project);
            List<Project> projects = List.of(project);
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> projects, () -> projectId, (n, p, c) -> null, id -> { }));

            BrdAuthoring.addRequirement(store, projectId, "Book shelf",
                "A reader can find and borrow a book", "HIGH", null, null, null, null);
            BrdAuthoring.addCriterion(store, projectId, "R1", "a book can be stored",
                "BookStoreTest#stores");
            BrdAuthoring.addCriterion(store, projectId, "R1", "the shelf can be searched",
                "ShelfSearchTest#finds");
            BrdAuthoring.addCriterion(store, projectId, "R1", "a book can be lent out",
                "LendingTest#lends");
            BrdAuthoring.addCriterion(store, projectId, "R1", "a lent book can be returned",
                "LendingTest#returns");
            BrdAuthoring.addCriterion(store, projectId, "R1", "an overdue book is chased",
                "LendingTest#chases");
            var brd = store.getBrd(projectId);
            for (var r : brd.requirements()) {
                r.setStatus(com.swarmcoder.domain.RequirementStatus.ACTIVE);
                for (var c : r.criteria()) {
                    c.setStatus(com.swarmcoder.domain.CriterionStatus.ACCEPTED);
                }
            }
            store.saveBrd(brd, "human", "promoted");

            BacklogAuthoring.proposeStory(store, projectId, "Store a book record", "R1:C1", null);
            BacklogAuthoring.proposeStory(store, projectId, "Search the shelf", "R1:C2", null);
            BacklogAuthoring.proposeStory(store, projectId, "Lend a book out", "R1:C3", null);
            BacklogAuthoring.proposeStory(store, projectId, "Take a book back", "R1:C4", null);
            BacklogAuthoring.proposeStory(store, projectId, "Chase a late book", "R1:C5", null);

            // 1. The story from the report: a build that came back without delivering, with seven
            //    questions raised against it by the same outage that stopped it.
            Story stopped = byTitle(store, projectId, "Store a book record");
            UUID stoppedRun = attach(store, stopped, RunState.ABORTED, projectId, false);
            stopped.setState(StoryState.BLOCKED);
            store.saveStory(stopped);
            for (int i = 1; i <= 7; i++) {
                ask(store, stoppedRun, "A piece of work stopped with nothing written: the model "
                    + "server stopped answering part way through attempt " + i + ".");
            }

            // 2. A delivery waiting to be judged, with a question on it as well. Its own action is
            //    "Accept delivery" and it must be reachable for the same reason.
            Story judge = byTitle(store, projectId, "Search the shelf");
            UUID judgeRun = attach(store, judge, RunState.DELIVERED, projectId, false);
            judge.setState(StoryState.REVIEW);
            judge.setDeliveredCommit("a1b2c3d4e5f6");
            store.saveStory(judge);
            ask(store, judgeRun, "The shelf search was written twice and the second one was kept.");

            // 3. A build that is genuinely working, with a question on it. This one has NO action of
            //    its own, deliberately — offering to build it again would invite the operator to pay
            //    for a second swarm over work that is already running. The null must stay a null.
            Story working = byTitle(store, projectId, "Lend a book out");
            UUID workingRun = attach(store, working, RunState.EXECUTING, projectId, true);
            working.setState(StoryState.RUNNING);
            store.saveStory(working);
            ask(store, workingRun, "One of the workers wants to add a library nothing else uses.");

            // 4. A build that says it is running and is not: its attempt ended without handing the
            //    story back. This one HAS an action - nothing is driving it, so building it again is
            //    the only way it ever moves - and a question must not hide that one either.
            Story stalled = byTitle(store, projectId, "Take a book back");
            UUID stalledRun = attach(store, stalled, RunState.ABORTED, projectId, false);
            stalled.setState(StoryState.RUNNING);
            store.saveStory(stalled);
            ask(store, stalledRun, "The last worker stopped before it wrote anything down.");

            // 5. A build waiting out a model server that is restarting. This one has NO action, and
            //    the reason is the money: it is going to resume by itself, and a button offering to
            //    build it again invites the operator to pay for a second swarm over the same work.
            //    A question on it may not conjure that button into existence.
            Story paused = byTitle(store, projectId, "Chase a late book");
            UUID pausedRun = attach(store, paused, RunState.EXECUTING, projectId, true);
            paused.setState(StoryState.RUNNING);
            store.saveStory(paused);
            store.append(() -> {
                store.root().runs.get(pausedRun).setPausedSince(Instant.now());
                return null;
            }).get();
            ask(store, pausedRun, "A worker asked whether to keep waiting for the model server.");

            BacklogPublisher.publish(store, projectId);

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                List<String> pageErrors = new ArrayList<>();
                Page page = browser.newPage();
                page.onPageError(pageErrors::add);
                page.setViewportSize(1600, 950);
                page.navigate("http://localhost:" + server.port() + "/");

                page.waitForSelector("[data-testid='stage-plan']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
                page.click("[data-testid='stage-plan']");
                page.waitForSelector("[data-testid='pipeline-col-came-back'] "
                        + ">> text=Store a book record",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- the defect, pinned -------------------------------------------------------
                assertThat(cardFor(page, "Store a book record"))
                    .describedAs("a stopped story offers its own way back, questions or no questions")
                    .contains("Build it again")
                    .describedAs("and the questions are offered too, not instead")
                    .contains("Answer these 7 questions")
                    .contains("asking you 7 things")
                    // Said on the card, so the operator does not have to try it to find out. The
                    // whole cost of the original defect was believing the questions came first.
                    .describedAs("the card says the two are independent")
                    .contains("ready whether you answer or not");
                // The delivery waiting to be judged keeps its own action for the same reason.
                assertThat(cardFor(page, "Search the shelf"))
                    .describedAs("a delivery with a question on it can still be accepted")
                    .contains("Accept delivery")
                    .contains("Answer this question");

                // --- a story that only LOOKS like it is building -------------------------------
                // Its attempt ended without handing the story back, so nothing is driving it and
                // building it again is the only thing that ever moves it. Same fix, different column.
                assertThat(cardFor(page, "Take a book back"))
                    .describedAs("a build that stopped short offers its own way back too")
                    .contains("Build it again")
                    .contains("Answer this question");

                // --- the nulls that must stay nulls -------------------------------------------
                assertThat(cardFor(page, "Lend a book out"))
                    .describedAs("a story genuinely being built is never offered a second swarm")
                    .contains("Answer this question")
                    .doesNotContain("Build it again")
                    .doesNotContain("Stop waiting and take it back");
                assertThat(cardFor(page, "Chase a late book"))
                    .describedAs("nor is one waiting out a model server that is coming back - that "
                        + "button would be an invitation to pay for the same work twice")
                    .contains("Answer this question")
                    .doesNotContain("Build it again")
                    .doesNotContain("Stop waiting and take it back");

                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-stopped-story-with-questions.png")));
                OperatorWords.assertNoneOnScreen(page,
                    "the pipeline board with questions waiting on three stories");

                // --- and it really works, with every question still unanswered ------------------
                page.click("[data-testid='pipeline-col-came-back'] >> text=\"Build it again\"");
                page.waitForSelector("text=\"Take it back\"",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("body").innerText())
                    .contains("Store a book record")
                    .contains("Ready to build");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-retry-behind-questions-dialog.png")));
                page.click("text=\"Take it back\"");

                // It moved. Nothing was answered to get it there, and the questions came with it —
                // still asked, still answerable, on the card in its new column.
                page.waitForSelector("[data-testid='pipeline-col-ready'] "
                        + ">> text=Store a book record",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(store.getStory(stopped.id()).state())
                    .describedAs("the retry ran without a single question being answered")
                    .isEqualTo(StoryState.READY);
                for (Decision decision : store.root().decisions.values()) {
                    assertThat(decision.state())
                        .describedAs("and answered none of them on the way")
                        .isEqualTo(DecisionState.PENDING);
                }
                String ready = page.locator("[data-testid='pipeline-col-ready']").textContent();
                assertThat(ready)
                    .describedAs("a story agreed for a second try keeps both offers")
                    .contains("Answer these 7 questions")
                    .contains("Build this story");

                // The questions were never made unreachable by any of this.
                page.click("[data-testid='pipeline-col-ready'] >> text=Answer these 7 questions");
                page.waitForSelector("[data-testid='story-questions']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-testid='story-questions']").textContent())
                    .contains("7 questions")
                    .contains("the model server stopped answering");
                // Let the modal finish arriving before the picture is taken: a shot of a dialog
                // half way through fading in is evidence of nothing.
                page.waitForTimeout(500);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-questions-still-reachable.png")));

                assertThat(pageErrors).isEmpty();
            }
        } finally {
            ConsoleContext.set(null);
        }
    }

    /**
     * One card's words, picked out by the story's title.
     *
     * <p>Per CARD rather than per column: three of these stories sit in "Building" at once and the
     * whole point of them is that they must NOT all say the same thing, so an assertion reading the
     * column would pass on any one of them carrying the words.
     */
    private static String cardFor(Page page, String title) {
        return page.locator("[data-testid='pipeline-card']")
            .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(title))
            .textContent();
    }

    /** Gives a story a run of its own, so a question can be joined back to it. */
    private static UUID attach(ArtifactStore store, Story story, RunState state, UUID projectId,
                               boolean alive) throws Exception {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, state, projectId, null, null, null, null,
            Instant.now(), null);
        if (alive) {
            run.setHeartbeatAt(Instant.now());
        }
        store.append(() -> store.root().runs.put(runId, run)).get();
        story.setRunIds(new ArrayList<>(List.of(runId)));
        store.saveStory(story);
        return runId;
    }

    /** One unanswered question, raised in a run and therefore belonging to that run's story. */
    private static void ask(ArtifactStore store, UUID runId, String brief) throws Exception {
        UUID id = UUID.randomUUID();
        store.append(() -> store.root().decisions.put(id,
            new Decision(id, runId, DecisionKind.BLOCKED_TASK, brief, DecisionState.PENDING, null,
                Instant.now()))).get();
    }

    private static Story byTitle(ArtifactStore store, UUID projectId, String title) {
        for (Story story : store.listStories(projectId)) {
            if (title.equals(story.title())) {
                return story;
            }
        }
        throw new AssertionError("no story titled " + title);
    }
}
