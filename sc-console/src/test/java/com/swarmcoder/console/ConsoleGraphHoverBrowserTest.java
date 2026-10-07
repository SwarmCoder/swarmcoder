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
import com.microsoft.playwright.options.BoundingBox;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RepairIndex;
import com.swarmcoder.domain.Requirement;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.LiveWorkerSessions;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the run graph says when the pointer rests on something, and what it never stops saying.
 *
 * <p>Three complaints, one surface:
 *
 * <ol>
 *   <li>"The mouse over on the chip produces a popup but it is very ugly" - it was the operating
 *       system's tooltip, drawn from an SVG {@code <title>}. It is a card the Console draws now,
 *       and no chip carries a {@code <title>} at all.
 *   <li>"Also add a mouseover for the actual task - so show the story, BRD etc etc" - a task box
 *       shows a cut title and a cut path and had no hover at all.
 *   <li>"We have dropped down to 2 chips! What happened to the others. Why do they disappear off
 *       screen. If they fail they should turn red and still mouseover to see the reason why." -
 *       two of four workers had SUCCEEDED and vanished, because a worker leaves the live map when
 *       its session closes and is not archived until verification has run.
 * </ol>
 *
 * <p>One embedded Console per JVM, so this class holds exactly one browser test and everything is
 * asserted inside it. Runs wherever Playwright's Chromium is installed; skipped, and the skip
 * announced, where it is not.
 *
 * <h2>Why the edge assertions are geometry rather than a screenshot</h2>
 *
 * <p>A card is 19&nbsp;rem wide and a chip is 52&nbsp;px. A chip near the right of the window has
 * no room for a card to its right, so the card has to go to the chip's other side; a chip near the
 * bottom has to have its card above it. Nothing about that reads off a screenshot, so the card's
 * own bounding box is measured against the window's - at three widths, which is the same bar the
 * rest of the Console's layout is held to.
 */
class ConsoleGraphHoverBrowserTest {

    @TempDir
    Path storeDir;

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    private static final String CARD = "[data-testid='graph-hover-card']";
    private static final String SURFACE = "[data-testid='run-graph-canvas']";
    /**
     * The whole graph view, which is what a card may not spill out of.
     *
     * <p>Not the window. The graph opens as a dialog over the board, and a dialog hides its
     * overflow - a card inside the window but past the bottom of that dialog is silently cut, and
     * every measurement against the window says it fitted.
     */
    private static final String BOUNDS = "[data-testid='run-graph']";

    /**
     * One chip, named the way the graph names it.
     *
     * <p>Both halves are needed: every task in a run has a w0, so the worker index alone picks out
     * one chip per task and Playwright refuses the ambiguity.
     */
    private static String chip(UUID task, int worker) {
        return "[data-node='cand-" + worker + "'][data-task='" + task + "']";
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void aChipAndATaskEachSayWhatTheyAre_andNoWorkerEverVanishes() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TraceHub hub = new TraceHub(null);
            // What DependencyGraph wires in the running application: a closed session is written
            // to the store. The whole of the disappearing-chip fix hangs off that record, so a
            // test of it has to have the same listener on the hub.
            hub.addListener(new TraceHub.Listener() {
                @Override
                public void sessionEnded(com.swarmcoder.domain.AgentSessionRecord complete) {
                    try {
                        // Waited for, unlike in production: the next line of the test asks the
                        // Console what it can see, and a write still in flight would make this
                        // test tell the truth about the store only some of the time.
                        store.append(() -> {
                            store.root().agentSessions().put(complete.id(),
                                org.eclipse.serializer.reference.Lazy.Reference(complete));
                            return null;
                        }).get();
                    } catch (Exception e) {
                        throw new IllegalStateException("could not record a closed session", e);
                    }
                }
            });
            UUID projectId = UUID.randomUUID();
            List<Project> projects = List.of(new Project(projectId, "bookshelf",
                storeDir.toString(), List.of(), Instant.now(), false));
            ConsoleContext.set(new ConsoleContext(store, hub,
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> projects, () -> projectId, (n, p, c) -> null, id -> { }));

            UUID runId = UUID.randomUUID();
            UUID shelfTask = UUID.randomUUID();
            UUID searchTask = UUID.randomUUID();
            UUID bareTask = UUID.randomUUID();
            seed(store, projectId, runId, shelfTask, searchTask, bareTask);

            // Four workers on the first task, doing what a worker that finishes does - all four
            // opened with the same 8,000-token head, because that is what dispatching four workers
            // on one task means and it is what makes the chip's third number worth showing.
            List<UUID> sessions = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                UUID id = LiveWorkerSessions.open(hub, runId, shelfTask, i, "qwen3.8-27b",
                    0.1 + i * 0.23, 8_000);
                LiveWorkerSessions.answered(hub, id, "on it", 12_000 + i * 1_500L);
                LiveWorkerSessions.step(hub, id, "exec", "mvn -q test-compile");
                sessions.add(id);
            }
            // One on the second task, so the graph has a second row of chips to hover.
            LiveWorkerSessions.open(hub, runId, searchTask, 0, "qwen3.8-27b", 0.4);

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                ConsoleContext.get().setPushSink(
                    jakarta.enterprise.inject.spi.CDI.current()
                        .select(com.zeroz4j.server.WasmRmiServerEngine.class).get()::broadcastPush);

                List<String> pageErrors = new ArrayList<>();
                Page page = browser.newPage();
                page.onPageError(pageErrors::add);
                page.setViewportSize(1600, 950);
                page.navigate("http://localhost:" + server.port() + "/");
                page.waitForSelector("[data-testid='status-header']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
                page.click("[data-testid='stage-plan']");
                page.waitForSelector("[data-testid='open-run']",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                page.locator("[data-testid='open-run']").first().click();
                page.waitForSelector(chip(shelfTask, 0),
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- 1. the native tooltip is gone -------------------------------------------
                assertThat(page.locator("[data-node] title").count())
                    .describedAs("no node on the graph carries an SVG <title>: that is the "
                        + "operating system's tooltip, unstyled and belonging to no application")
                    .isZero();

                // --- 2. a worker chip's card -------------------------------------------------
                page.locator(chip(shelfTask, 1)).hover();
                page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(page.locator(CARD).getAttribute("data-hover")).isEqualTo("worker");
                String worker = page.locator(CARD).innerText();
                assertThat(worker)
                    .describedAs("the same facts the tooltip carried, on a surface the Console owns")
                    .contains("w1")
                    .contains("still writing")
                    .contains("working for")
                    .contains("turns taken")
                    .contains("14k tokens")
                    .contains("last spoke");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "graph-hover-worker.png")));

                // --- 3. a task box's card ----------------------------------------------------
                page.locator("[data-node='task-" + searchTask + "']").hover();
                page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(page.locator(CARD).getAttribute("data-hover")).isEqualTo("task");
                String task = page.locator(CARD).innerText();
                assertThat(task)
                    .describedAs("what the box has room for about a third of: the whole title, "
                        + "the story, the requirement it answers with the requirement's own "
                        + "words, the checks, the files, what it waits for, how it is going")
                    .contains("Find a book on the shelf by its title")
                    .contains("S2")
                    .contains("Searching the shelf")
                    .contains("R2")
                    .contains("A reader can find a book by typing part of its title")
                    .contains("2 checks must pass")
                    .contains("search/src/main/java")
                    .contains("Store a book on the shelf")
                    .contains("1 worker");
                assertThat(task)
                    .describedAs("no internal constant reaches the card")
                    .doesNotContain("DISPATCHED").doesNotContain("RUNNING");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "graph-hover-task.png")));

                // --- 4. a task with nothing on it draws a card without empty rows ------------
                page.locator("[data-node='task-" + bareTask + "']").hover();
                page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
                String bare = page.locator(CARD).innerText();
                assertThat(bare)
                    .describedAs("a missing value costs its own line and nothing else")
                    .contains("Tidy up the build")
                    .doesNotContain("story")
                    .doesNotContain("answers")
                    .doesNotContain("checks")
                    .doesNotContain("files")
                    .doesNotContain("waits for")
                    .doesNotContain("attempts");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "graph-hover-task-empty.png")));

                // --- 5. it never spills out of the window, at any edge, at any width ---------
                // The three widths the rest of the Console's layout is held to. The edges that
                // matter are the DIALOG's, not the window's - it is 56 rem wide and 70 vh tall,
                // so a chip at its right or its foot has nowhere to put a card without flipping,
                // at every one of these.
                boolean rightFlipped = false;
                boolean bottomFlipped = false;
                for (int[] shape : new int[][] {{1600, 950}, {1280, 950}, {960, 950}}) {
                    int width = shape[0];
                    page.setViewportSize(width, shape[1]);
                    page.waitForTimeout(300);
                    recentre(page, chip(shelfTask, 0));
                    cardStaysInside(page, "every node, straight off the layout",
                        chip(shelfTask, 0));
                    cardStaysInside(page, "every node, straight off the layout",
                        chip(shelfTask, 3));
                    rightFlipped |= flipsAtRightEdge(page, width, chip(shelfTask, 0));
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target",
                            "graph-hover-edge-right-" + width + "x" + shape[1] + ".png")));
                    recentre(page, chip(shelfTask, 0));
                    bottomFlipped |= flipsAtBottomEdge(page, width, chip(shelfTask, 0));
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target",
                            "graph-hover-edge-bottom-" + width + "x" + shape[1] + ".png")));
                    recentre(page, chip(shelfTask, 0));
                }
                assertThat(rightFlipped)
                    .describedAs("at least one of those shapes really did put a chip too close "
                        + "to the right edge for a card beside it - otherwise the flip above was "
                        + "never tested at all")
                    .isTrue();
                assertThat(bottomFlipped)
                    .describedAs("and one of them really did put a chip too close to the bottom")
                    .isTrue();
                page.setViewportSize(1600, 950);
                page.waitForTimeout(300);

                // --- 6. a worker that has finished does not disappear ------------------------
                // The gap the operator fell into: out of the hub's live map, not yet archived.
                // Before the fix these two workers were in NO collection the graph reads, so
                // their chips vanished - and he read the two that had gone as failures.
                LiveWorkerSessions.closed(hub, sessions.get(0), "COMPLETED", null, 15, 41_139);
                LiveWorkerSessions.closed(hub, sessions.get(3), "COMPLETED", null, 17, 40_541);
                LiveWorkerSessions.closed(hub, sessions.get(2), "KILLED",
                    KillReason.BUDGET_EXCEEDED, 74, 63_200);
                pushFresh(runId);
                page.waitForTimeout(1500);

                assertThat(page.locator("[data-node^='cand-']").count())
                    .describedAs("four chips on the first task and one on the second, still - a "
                        + "status display may change what it says, never stop saying it")
                    .isEqualTo(5);

                page.locator(chip(shelfTask, 0)).hover();
                page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
                String finished = page.locator(CARD).innerText();
                assertThat(finished)
                    .describedAs("neither running nor judged, and it says which")
                    .contains("waiting to be checked")
                    .contains("turns taken")
                    .contains("41k tokens");
                assertThat(page.locator(chip(shelfTask, 0)).textContent())
                    .describedAs("and the chip keeps the numbers that were on it a moment ago, "
                        + "rather than swapping them for the temperature and reading as a "
                        + "different worker")
                    .contains("15t").contains("41k");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "graph-hover-finished.png")));

                // --- 7. a worker that was stopped is red and says why ------------------------
                page.locator(chip(shelfTask, 2)).hover();
                page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
                String stopped = page.locator(CARD).innerText();
                assertThat(stopped)
                    .describedAs("the reason, in the sentence the product already has for it")
                    .contains("stopped early")
                    .contains("conversation outgrew what it is allowed");
                assertThat(stopped)
                    .describedAs("and not the constant")
                    .doesNotContain("BUDGET_EXCEEDED");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "graph-hover-stopped.png")));

                // --- 8. moving across nodes leaves nothing behind ----------------------------
                page.locator(chip(shelfTask, 1)).hover();
                page.locator(chip(shelfTask, 2)).hover();
                page.locator("[data-node='task-" + shelfTask + "']").hover();
                assertThat(page.locator(CARD).count())
                    .describedAs("one card, reused - not a trail of them behind the pointer")
                    .isEqualTo(1);
                page.mouse().move(4, 4);
                page.waitForTimeout(200);
                assertThat(page.locator(CARD).isVisible())
                    .describedAs("and it goes when the pointer does")
                    .isFalse();

                // --- 9. the card never swallows a click on the node it describes -------------
                page.locator(chip(shelfTask, 1)).click();
                page.waitForSelector("text=Candidate w1",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.keyboard().press("Escape");

                // --- 10. a repair round: both waves on the task at once ---------------------
                // What the operator saw on 2026-09-02: four workers failed to compile, the engine
                // dispatched a repair round, the four originals vanished and four numbered w100,
                // w101, w110, w111 appeared. "Now they seem to have restarted? More workers on
                // the same task? This is confusing?" Both waves stay on screen now, in two bands,
                // and the numbers say what they are.
                failWholeWave(store, shelfTask, sessions.size());
                for (int seed = 0; seed < 2; seed++) {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        UUID id = LiveWorkerSessions.open(hub, runId, shelfTask,
                            RepairIndex.of(seed, attempt), "qwen3.8-27b", 0.4 + 0.4 * attempt,
                            8_000);
                        LiveWorkerSessions.answered(hub, id, "fixing", 9_000);
                    }
                }
                markRepairing(store, shelfTask);
                pushFresh(runId);
                page.waitForTimeout(1500);

                assertThat(page.locator("[data-node^='cand-'][data-task='" + shelfTask + "']")
                    .count())
                    .describedAs("four that failed AND four repairing them - his words: keep the "
                        + "original failed chips onscreen and add the new ones in addition")
                    .isEqualTo(8);
                assertThat(page.locator("[data-repair-band]").count())
                    .describedAs("with the repairs behind one word saying what they are, so they "
                        + "are not simply four more chips in the same run of chips")
                    .isEqualTo(1);

                page.locator("[data-node='task-" + shelfTask + "']").hover();
                page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(page.locator(CARD).innerText())
                    .describedAs("and the box says which phase it is in, which it never did")
                    .contains("fixing what failed")
                    .contains("8 workers");

                page.locator(chip(shelfTask, 1)).hover();
                page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(page.locator(CARD).innerText())
                    .describedAs("a failed original is still there and still carries the real "
                        + "verdict - the case a chip must never disappear for")
                    .contains("did not work")
                    .contains("candidate does not compile");

                page.locator(chip(shelfTask, RepairIndex.of(1, 1))).hover();
                page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(page.locator(CARD).innerText())
                    .describedAs("and a repair worker says what it is repairing, rather than "
                        + "being called w111")
                    .contains("what w1 got wrong, try 2");
                assertThat(page.locator(chip(shelfTask, RepairIndex.of(1, 1))).textContent())
                    .describedAs("on the chip too")
                    .contains("w1").doesNotContain("w111");
                assertThat(page.locator(chip(shelfTask, RepairIndex.of(1, 1))).textContent())
                    .describedAs("and the third number survives the crowded case - eight chips on "
                        + "one task, in two bands, each carrying turns and a split conversation")
                    .contains("8/9.0k");

                assertThat(page.locator(SURFACE).innerText())
                    .describedAs("and it says so on the box as well, where a glance lands, "
                        + "rather than only behind a hover")
                    .contains("fixing what failed");

                for (int width : new int[] {1600, 1280, 960}) {
                    page.setViewportSize(width, 950);
                    page.waitForTimeout(400);
                    recentre(page, "[data-node='task-" + shelfTask + "']");
                    cardStaysInside(page, "a crowded task at " + width, chip(shelfTask, 1));
                    page.mouse().move(4, 4);
                    page.waitForTimeout(150);
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target", "graph-repair-round-" + width + ".png")));
                }
                page.setViewportSize(1600, 950);

                assertThat(pageErrors)
                    .describedAs("nothing was thrown at the page")
                    .isEmpty();
            }
        }
    }

    /** The card, wherever it lands for this node, is whole: inside the graph and inside the window. */
    private static void cardStaysInside(Page page, String why, String node) {
        page.locator(node).hover();
        page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
        BoundingBox card = page.locator(CARD).boundingBox();
        BoundingBox bounds = page.locator(BOUNDS).boundingBox();
        int width = ((Number) page.evaluate("() => window.innerWidth")).intValue();
        int height = ((Number) page.evaluate("() => window.innerHeight")).intValue();
        assertThat(card.x).describedAs(why + " - left edge of the window").isGreaterThanOrEqualTo(0);
        assertThat(card.y).describedAs(why + " - top edge of the window").isGreaterThanOrEqualTo(0);
        assertThat(card.x + card.width)
            .describedAs(why + " - right edge of the window at " + width)
            .isLessThanOrEqualTo(width);
        assertThat(card.y + card.height)
            .describedAs(why + " - bottom edge of the window at " + height)
            .isLessThanOrEqualTo(height);
        assertThat(card.x).describedAs(why + " - left edge of the graph")
            .isGreaterThanOrEqualTo(bounds.x);
        assertThat(card.y).describedAs(why + " - top edge of the graph")
            .isGreaterThanOrEqualTo(bounds.y);
        assertThat(card.x + card.width).describedAs(why + " - right edge of the graph")
            .isLessThanOrEqualTo(bounds.x + bounds.width);
        assertThat(card.y + card.height)
            .describedAs(why + " - bottom edge of the graph, which is where a dialog cuts it")
            .isLessThanOrEqualTo(bounds.y + bounds.height);
    }

    /**
     * A chip moved to the far right of the graph: the card must stay in the window, and when the
     * chip is close enough to the edge that a card beside it would not fit, it must have gone to
     * the chip's OTHER side.
     *
     * @return true when this width actually demanded the flip, so the caller can insist that at
     *         least one of them did
     */
    private static boolean flipsAtRightEdge(Page page, int width, String node) {
        BoundingBox surface = page.locator(SURFACE).boundingBox();
        BoundingBox chip = panNodeTo(page, node, surface.x + surface.width - 70,
            surface.y + surface.height / 2);
        page.locator(node).hover();
        page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
        BoundingBox card = page.locator(CARD).boundingBox();
        BoundingBox bounds = page.locator(BOUNDS).boundingBox();
        assertThat(card.x + card.width)
            .describedAs("a card on a chip at the right of the graph stays inside it at " + width)
            .isLessThanOrEqualTo(bounds.x + bounds.width);
        boolean demanded = chip.x + card.width > bounds.x + bounds.width - 8;
        if (demanded) {
            assertThat(card.x)
                .describedAs("and it got there by flipping to the chip's other side, not by "
                    + "being squashed against the edge")
                .isLessThan(chip.x);
        }
        return demanded;
    }

    /** The same at the bottom: the card goes above the chip rather than below it. */
    private static boolean flipsAtBottomEdge(Page page, int width, String node) {
        int height = ((Number) page.evaluate("() => window.innerHeight")).intValue();
        BoundingBox surface = page.locator(SURFACE).boundingBox();
        BoundingBox chip = panNodeTo(page, node, surface.x + surface.width / 2,
            surface.y + surface.height - 50);
        page.locator(node).hover();
        page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5_000));
        BoundingBox card = page.locator(CARD).boundingBox();
        BoundingBox bounds = page.locator(BOUNDS).boundingBox();
        assertThat(card.y + card.height)
            .describedAs("a card on a chip at the bottom of the graph stays inside it at " + width
                + "x" + height)
            .isLessThanOrEqualTo(bounds.y + bounds.height);
        boolean demanded = chip.y + chip.height + card.height > bounds.y + bounds.height - 8;
        if (demanded) {
            assertThat(card.y)
                .describedAs("and it is ABOVE the chip, which is the flip")
                .isLessThan(chip.y);
        }
        return demanded;
    }

    /**
     * Moves the graph until a node sits where the test wants it, and answers with where it ended
     * up.
     *
     * <p>By the keyboard, which is a path the graph really has: the surface takes the arrow keys
     * and Shift moves it 160&nbsp;px at a time. A mouse drag was tried first and is the wrong
     * instrument here - the pointer cannot leave the window, so a drag long enough to carry a node
     * across the screen is silently clipped and the node ends up somewhere else.
     *
     * <p>The keys move the VIEW, not the picture: ArrowRight looks further right, which slides the
     * content left. So moving a node rightwards means pressing ArrowLeft. Getting that backwards
     * sent a chip to x=-18,784 and cost an afternoon, which is why it is written down here.
     */
    private static BoundingBox panNodeTo(Page page, String node, double x, double y) {
        page.locator(SURFACE).focus();
        for (int press = 0; press < 120; press++) {
            BoundingBox at = page.locator(node).boundingBox();
            double dx = x - at.x;
            double dy = y - at.y;
            if (Math.abs(dx) <= 25 && Math.abs(dy) <= 25) {
                break;
            }
            // Shift crosses ground at 160 px a press; the plain key moves 40 and is what actually
            // lands on a target. Using only the big step overshoots and then overshoots back, for
            // ever.
            boolean horizontal = Math.abs(dx) > Math.abs(dy);
            double error = horizontal ? dx : dy;
            String arrow = horizontal
                ? (error > 0 ? "ArrowLeft" : "ArrowRight")
                : (error > 0 ? "ArrowUp" : "ArrowDown");
            page.keyboard().press(Math.abs(error) > 200 ? "Shift+" + arrow : arrow);
        }
        return page.locator(node).boundingBox();
    }

    /**
     * Puts the graph back somewhere known, so one edge test cannot set up the next one.
     *
     * <p>By panning rather than by the surface's own "back to the start" key: after an edge test
     * the keyboard is not reliably still on the surface, and a reset that quietly does nothing
     * leaves the next hover under the replay panel at the foot of the dialog.
     */
    private static void recentre(Page page, String node) {
        BoundingBox surface = page.locator(SURFACE).boundingBox();
        panNodeTo(page, node, surface.x + 70, surface.y + 55);
    }

    /**
     * Every worker on this task fails verification, the way the engine records it.
     *
     * <p>The candidates are archived with their verdict as soon as verification decides, which is
     * what makes the failures visible while the repair round runs. The verdict wording is what
     * {@code Verdicts} produced and {@code SwarmEngineImpl} wrote onto the report.
     */
    private static void failWholeWave(ArtifactStore store, UUID taskId, int workers)
            throws Exception {
        store.append(() -> {
            for (int worker = 0; worker < workers; worker++) {
                VerificationReport report = new VerificationReport();
                report.setId(UUID.randomUUID());
                report.setParses(true);
                report.setCompiles(false);
                report.setLogTail("...\n[verdict] NOT SURVIVED \u2014 "
                    + "the candidate does not compile\n");
                CandidateSolution candidate = new CandidateSolution(UUID.randomUUID(), taskId,
                    worker, "swarm/w" + worker,
                    new SamplingConfig("qwen3.8-27b", 0.1 + worker * 0.23, 0, "", ""),
                    "", report, null, null, CandidateState.FAILED, null);
                store.root().candidateArchives.put(candidate.id(),
                    org.eclipse.serializer.reference.Lazy.Reference(candidate));
            }
            return null;
        }).get();
    }

    /** The task moves into the phase the engine has always had and never said. */
    private static void markRepairing(ArtifactStore store, UUID taskId) throws Exception {
        store.append(() -> {
            for (TaskGraph graph : store.root().taskGraphs.values()) {
                for (Task task : graph.tasks()) {
                    if (taskId.equals(task.id())) {
                        task.setState(TaskState.REPAIRING);
                    }
                }
            }
            return null;
        }).get();
    }

    /** A fresh frame on the push topic, the way the run-graph publisher sends one. */
    private static void pushFresh(UUID runId) {
        var frame = new GraphServiceImpl().snapshot(runId.toString());
        frame.setSeq(frame.getSeq() + 1_000_000);
        ConsoleContext.get().push("run-graph", frame);
    }

    /**
     * A run with two tasks that answer to requirements and belong to stories, and one that answers
     * to nothing - the empty case a card has to survive.
     */
    private static void seed(ArtifactStore store, UUID projectId, UUID runId,
                             UUID shelfTask, UUID searchTask, UUID bareTask) throws Exception {
        UUID shelfRequirement = UUID.randomUUID();
        UUID searchRequirement = UUID.randomUUID();
        DesignDocument design = new DesignDocument();
        design.setId(UUID.randomUUID());
        design.setRequirements(List.of(
            requirement(shelfRequirement, "Books can be put on the shelf and stay there"),
            requirement(searchRequirement,
                "A reader can find a book by typing part of its title")));

        Story shelfStory = story(projectId, "S1", "Putting books on the shelf");
        Story searchStory = story(projectId, "S2", "Searching the shelf");

        Task shelf = new Task(shelfTask, 1, "Store a book on the shelf", "",
            Set.of("shelf/src/main/java"), Set.of(), List.of(check("a book added is still there")),
            null, null, null, new SwarmPolicy(6, false, 0.1, 0.8, List.of()),
            TaskState.DISPATCHED, new LinkedHashSet<>(List.of(shelfRequirement)));
        shelf.setStoryId(shelfStory.id());

        Task search = new Task(searchTask, 1, "Find a book on the shelf by its title", "",
            Set.of("search/src/main/java", "search/src/test/java"), Set.of(),
            List.of(check("a partial title finds the book"), check("no match finds nothing")),
            null, null, null, new SwarmPolicy(6, false, 0.1, 0.8, List.of()),
            TaskState.DISPATCHED, new LinkedHashSet<>(List.of(searchRequirement)));
        search.setStoryId(searchStory.id());

        // Nothing on it at all: no story, no requirement, no checks, no write set, no attempts.
        Task bare = new Task(bareTask, 1, "Tidy up the build", "",
            Set.of(), Set.of(), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.1, 0.8, List.of()), TaskState.PENDING);

        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            List.of(shelf, search, bare), List.of(new TaskEdge(shelfTask, searchTask)));

        store.append(() -> {
            store.root().designs.put(design.id(), design);
            store.root().stories().put(shelfStory.id(), shelfStory);
            store.root().stories().put(searchStory.id(), searchStory);
            store.root().taskGraphs.put(graph.id(), graph);
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                projectId, null, design.id(), graph.id(), null, Instant.now(),
                new RunReport(runId, "a bookshelf that stores books"));
            run.setHeartbeatAt(Instant.now());
            store.root().runs.put(runId, run);
            return null;
        }).get();
    }

    private static Requirement requirement(UUID id, String text) {
        Requirement requirement = new Requirement();
        requirement.setId(id);
        requirement.setText(text);
        requirement.setPriority(Priority.HIGH);
        return requirement;
    }

    private static AcceptanceCriterion check(String text) {
        return new AcceptanceCriterion(UUID.randomUUID(), text, "");
    }

    private static Story story(UUID projectId, String key, String title) {
        Story story = new Story();
        story.setId(UUID.randomUUID());
        story.setProjectId(projectId);
        story.setKey(key);
        story.setKind(StoryKind.DELIVERY);
        story.setTitle(title);
        story.setState(StoryState.RUNNING);
        return story;
    }
}
