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
import com.microsoft.playwright.options.WaitForSelectorState;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autonomous running, in the open: on the screen the operator is already using, never covering it,
 * and holding only the levers it has its own hand on.
 *
 * <p>Two reports an hour apart, in his words. First:
 *
 * <blockquote>"I would prefer a mode where I can see what is going on and can still use the UI to
 * open or view things. So I can see actual story planning, BRD requirements building, monitor
 * workers etc. Hiding everything behind one dialog with little feedback doesn't give me a good
 * feeling."</blockquote>
 *
 * <p>Then, having watched it run:
 *
 * <blockquote>"It is inconvenient to have this dialog box open as it covers underlying UI. It cannot
 * be moved and can only be closed and then how is it ever accessed again? It probably needs to be
 * minimised. And then there must be some strong visual indicator that the process is running
 * autonomously - something bold. And maybe features need to be disabled in this autonomous mode?"
 * </blockquote>
 *
 * <p>And, narrowing the last of those himself: <i>"I meant disabling the features that would
 * interfere or are equivalent to what the auto mode is doing!"</i>
 *
 * <p>Nothing about the pilot changed in any of this. It never cared whether a browser was looking,
 * and closing the window never stopped it. What was wrong was the surface: the only place it could
 * be seen was a modal that covered the application it was reporting on.
 *
 * <h2>What is asserted, and why each one</h2>
 *
 * <ul>
 *   <li><b>Off means absent.</b> Nothing about autonomous running is on the header, and every gate
 *       control on the board works exactly as it always has.</li>
 *   <li><b>On is unmissable, and on every screen.</b> A strip across the top of the header, in a
 *       colour of its own - measured, because "something bold" is the requirement and a screenshot
 *       proves it to a person and to nothing else.</li>
 *   <li><b>The good sentence survived.</b> What it is doing, how long, what it has spent and when
 *       it stops by - the line he said was the good part - is on that strip, word for word.</li>
 *   <li><b>The application is usable while it runs.</b> No modal is open, stages switch, a run
 *       opens, and the graph draws its workers exactly as it does for a build a person started.</li>
 *   <li><b>The pilot's own levers are held, and say why.</b> Accepting a suggestion and starting a
 *       build are greyed out with a sentence under them; judging what came back is NOT, because the
 *       arming screen promises the operator gets that.</li>
 *   <li><b>Closing the window does not stop it</b> - checked against the server's own switch.</li>
 *   <li><b>Stopping is one click, and gives everything straight back</b> - the strip goes, and the
 *       held buttons work again in the same frame, with no reload.</li>
 *   <li><b>It fits.</b> Pictures at 1600, 1280 and 960, with the header measured for overflow at
 *       every one of them.</li>
 * </ul>
 *
 * <p><b>No model is called anywhere in this file.</b> The analyst stand-in throws if anything asks
 * it for a completion, which is itself an assertion: this drives the surface by publishing a
 * session's state, never by letting the pilot spend money.
 *
 * <p>One embedded Console per JVM, so this class holds exactly one browser test and everything is
 * asserted inside it. Runs wherever Playwright's Chromium is installed; skipped, and the skip
 * announced, where it is not.
 */
class ConsoleAutonomousInTheOpenBrowserTest {

    @TempDir
    Path storeDir;

    /** The card button that accepts a planner suggestion as real work - the pilot's while it runs. */
    private static final String ACCEPT =
        "[data-testid='pipeline-col-suggested'] button:has-text('Accept as work')";
    /** The card button that starts a build - also the pilot's. */
    private static final String BUILD =
        "[data-testid='pipeline-col-ready'] button:has-text('Build this story')";

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void itIsBoldAndEverywhere_holdsOnlyItsOwnLevers_andClosingTheWindowStopsNothing()
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TraceHub hub = new TraceHub(null);
            UUID projectId = UUID.randomUUID();
            List<Project> projects = List.of(new Project(projectId, "bookshelf",
                storeDir.toString(), List.of(), Instant.now(), false));
            ConsoleContext.set(new ConsoleContext(store, hub,
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> projects, () -> projectId, (n, p, c) -> null, id -> { })
                // Autonomous running refuses to start without an analyst. This one exists to be
                // present and to fail loudly: nothing in this test may reach a model.
                .withAnalyst((messages, override) -> {
                    throw new AssertionError("no model may be called from this test");
                }));

            // Two stories in the two states whose buttons are the pilot's job, so that "held" and
            // "given straight back" are assertions rather than a claim.
            store.saveStory(story(projectId, "S1", "Add a Book model", StoryState.DRAFT, 1));
            store.saveStory(story(projectId, "S2", "Show the shelf", StoryState.READY, 2));

            // A build already in flight, seeded exactly as ConsoleLiveWorkersBrowserTest seeds one.
            // It is here to prove the point he actually made: with autonomous running switched on,
            // the run graph and the board must show what they always show. That machinery is
            // reused, not duplicated for this mode.
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
                List.of(new Task(taskId, 1, "Add shared Book model", "",
                    Set.of("shared/src/main"), Set.of(), List.of(), null, null, null,
                    new SwarmPolicy(6, false, 0.1, 0.8, List.of()), TaskState.DISPATCHED)),
                List.of());
            store.append(() -> {
                store.root().taskGraphs.put(graph.id(), graph);
                Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                    projectId, null, null, graph.id(), null, Instant.now(),
                    new RunReport(runId, "a bookshelf that stores books"));
                run.setHeartbeatAt(Instant.now());
                store.root().runs.put(runId, run);
                return null;
            }).get();
            for (int i = 0; i < 3; i++) {
                UUID id = LiveWorkerSessions.open(hub, runId, taskId, i, "qwen3.8-27b",
                    0.1 + i * 0.2);
                LiveWorkerSessions.answered(hub, id, "on it", 12_000 + i * 1_000L);
                LiveWorkerSessions.step(hub, id, "exec", "mvn -q test-compile");
            }

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

                // --- 1. OFF: nothing intrudes, and every lever is his -------------------------
                assertThat(page.locator("[data-testid='autonomous-banner']").isVisible())
                    .describedAs("nothing claims anything is deciding for you while nothing is")
                    .isFalse();
                assertThat(page.locator("[data-testid='status-header']").innerText())
                    .describedAs("and not a word of it either - an element that usually says "
                        + "nothing is one nobody reads when it finally does")
                    .doesNotContain("BUILDING ON ITS OWN");

                page.click("[data-testid='stage-plan']");
                page.waitForSelector(ACCEPT,
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                assertThat(page.locator(ACCEPT).isEnabled())
                    .describedAs("accepting a suggestion is his while nothing is running")
                    .isTrue();
                assertThat(page.locator(BUILD).isEnabled())
                    .describedAs("and so is starting a build")
                    .isTrue();
                assertThat(page.locator("[data-testid='pilot-holds-this']").count())
                    .describedAs("and nothing on the board is explaining away a grey button")
                    .isZero();
                shots(page, "console-autonomous-off");

                // --- 2. ON: the strip appears, and it is loud --------------------------------
                // Switched on and driven from the server one gate at a time, which is exactly how
                // the pilot drives it: AutonomousBuild names the gate, AutonomousMode publishes it.
                assertThat(AutonomousMode.start())
                    .describedAs("the switch itself must not refuse")
                    .isEmpty();
                AutonomousMode.current().setActivity("Reading your documents");
                AutonomousMode.publish();

                page.waitForSelector("[data-testid='autonomous-banner']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='autonomous-banner']").innerText())
                    .describedAs("the four words, the whole sentence, the way back to the record "
                        + "and the way out - all of it on the header, with no window open")
                    .contains("BUILDING ON ITS OWN")
                    .contains("Running on its own")
                    .contains("Reading your documents")
                    .contains("stops by")
                    .contains("What it decided")
                    .contains("Stop");
                assertThat(page.locator("dialog.modal.modal-open").count())
                    .describedAs("and no window IS open - this is the whole complaint")
                    .isZero();
                assertThat(loud(page))
                    .describedAs("\"something bold\": the strip is painted in a colour of its own, "
                        + "not left as another quiet line of chrome")
                    .isEqualTo("solid");

                // The gate moves as the machine passes it, with nothing asking the server for it.
                AutonomousMode.current().setActivity("Agreeing what it found");
                AutonomousMode.publish();
                page.waitForSelector(
                    "[data-testid='autonomous-banner-status']:has-text('Agreeing what it found')",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- 3. its own levers are held, and say why ---------------------------------
                page.waitForSelector("[data-testid='pilot-holds-this']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator(ACCEPT).isEnabled())
                    .describedAs("accepting a suggestion is what the machine is doing right now, "
                        + "so a person doing it by hand is a duplicate or a collision")
                    .isFalse();
                assertThat(page.locator(BUILD).isEnabled())
                    .describedAs("and so is starting a build")
                    .isFalse();
                assertThat(page.locator("[data-testid='pilot-holds-this']").first().innerText())
                    .describedAs("a grey button with no sentence is the same anxiety in a "
                        + "different costume - so it says what took it and how to get it back")
                    .contains("building on its own")
                    .contains("Stop it");
                assertThat(page.locator("[data-testid='pipeline-col-suggested']").innerText())
                    .describedAs("the work itself is still readable - nothing is hidden, one "
                        + "control is held")
                    .contains("Add a Book model");
                assertThat(page.locator("[data-testid='guidance']").innerText())
                    .describedAs("and the header stops telling him to press the button it just "
                        + "took - the one line of advice defers to the machine doing that step")
                    .contains("SwarmCoder is doing this itself");
                shots(page, "console-autonomous-on-board");

                // --- 4. the application is still an application ------------------------------
                page.click("[data-testid='stage-requirements']");
                page.waitForTimeout(500);
                assertThat(page.locator("[data-testid='autonomous-banner']").isVisible())
                    .describedAs("the strip is a row of the header, so it is over the requirements "
                        + "workspace too - not only over the board it used to live behind")
                    .isTrue();
                shots(page, "console-autonomous-on-requirements");

                page.click("[data-testid='stage-plan']");
                page.waitForSelector("[data-testid='open-run']",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                page.locator("[data-testid='open-run']").first().click();
                page.waitForSelector("[data-node='cand-0']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-node^='cand-']").count())
                    .describedAs("the run graph draws one chip per worker while autonomous running "
                        + "is on, exactly as it does for a build a person started - this machinery "
                        + "is reused, not rebuilt for this mode")
                    .isEqualTo(3);
                assertThat(page.locator("[data-testid='live-workers']").innerText())
                    .describedAs("and the band above it still says in words what they are doing")
                    .contains("writing code right now");
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-autonomous-on-graph.png")));
                page.keyboard().press("Escape");
                page.waitForTimeout(500);

                // --- 5. the record is one click away, and closing it stops nothing -----------
                page.click("[data-testid='autonomous-banner-record']");
                page.waitForSelector("[data-testid='autonomous-dialog'].modal-open",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                // Scoped to the window that is OPEN. Three of these windows exist once the board
                // and the guide have been built - the header's, the board's and the guide's - and
                // all three render the same running face, so an unscoped id matches three nodes.
                assertThat(page.locator("[data-testid='autonomous-dialog'].modal-open "
                        + "[data-testid='autonomous-status']").innerText())
                    .describedAs("the window carries the same sentence the strip does - one "
                        + "derivation, drawn in two places")
                    .contains("Running on its own")
                    .contains("Agreeing what it found");
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-autonomous-record.png")));

                page.keyboard().press("Escape");
                page.waitForSelector("[data-testid='autonomous-dialog'].modal-open",
                    new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.DETACHED).setTimeout(10_000));
                page.waitForTimeout(1000);
                assertThat(AutonomousMode.isRunning())
                    .describedAs("THE regression: closing the window is not stopping it. Checked "
                        + "against the server's own switch, because that is the thing deciding "
                        + "requirements overnight")
                    .isTrue();
                assertThat(page.locator("[data-testid='autonomous-banner']").isVisible())
                    .describedAs("and he is left in the application with the strip still saying "
                        + "so - not handed back to another window")
                    .isTrue();

                // --- 6. stopping is one click, and gives everything back ---------------------
                page.click("[data-testid='autonomous-banner-stop']");
                page.waitForSelector("[data-testid='autonomous-banner']",
                    new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.HIDDEN).setTimeout(15_000));
                assertThat(AutonomousMode.isRunning())
                    .describedAs("one click on the header, from whatever screen he was on")
                    .isFalse();
                assertThat(page.locator("dialog.modal.modal-open").count())
                    .describedAs("and stopping it opened nothing to confirm in")
                    .isZero();

                page.waitForSelector("[data-testid='pilot-holds-this']",
                    new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.DETACHED).setTimeout(15_000));
                assertThat(page.locator(ACCEPT).isEnabled())
                    .describedAs("the application is entirely his again in the same frame - no "
                        + "reload, and no grey button left behind")
                    .isTrue();
                assertThat(page.locator(BUILD).isEnabled()).isTrue();
                assertThat(page.locator("[data-testid='guidance']").innerText())
                    .describedAs("and the advice line is his again too")
                    .doesNotContain("SwarmCoder is doing this itself");

                assertThat(pageErrors)
                    .describedAs("nothing was thrown at the page")
                    .isEmpty();
            }
        } finally {
            AutonomousMode.reset();
            ConsoleContext.set(null);
        }
    }

    /** A story in one state, built directly: this test is about buttons, not about the BRD. */
    private static Story story(UUID projectId, String key, String title, StoryState state,
                               int order) {
        return new Story(UUID.randomUUID(), projectId, key, StoryKind.DELIVERY, title,
            "so that the shelf is useful", state, List.of(), List.of(), null, order,
            StoryOrigin.BACKLOG, null, null, "test", List.of(), null, null, null, null,
            Instant.now(), Instant.now());
    }

    /**
     * Whether the strip is painted in a colour of its own rather than left as more quiet chrome.
     *
     * <p>"Something bold" is the operator's whole requirement for this element, and it is the one
     * thing a screenshot proves to a person and nothing proves to a test. So it is measured: the
     * strip's own background must be fully opaque - a tint has an alpha and reads as chrome - and it
     * must not be one of the surface colours the rest of the Console is painted in.
     */
    private static String loud(Page page) {
        Object verdict = page.evaluate(
            "() => {"
            + "  const strip = document.querySelector('[data-testid=\"autonomous-banner\"]');"
            + "  if (!strip) return 'no strip';"
            + "  const colour = getComputedStyle(strip).backgroundColor;"
            + "  if (!colour || colour === 'transparent') return 'transparent';"
            + "  if (colour.includes('/') || colour.startsWith('rgba')) return 'a tint: ' + colour;"
            + "  if (colour === getComputedStyle(document.body).backgroundColor) {"
            + "    return 'the same colour as the page';"
            + "  }"
            + "  const header = getComputedStyle("
            + "    document.querySelector('[data-testid=\"status-header\"]')).backgroundColor;"
            + "  if (colour === header) return 'the same colour as the header';"
            + "  return 'solid';"
            + "}");
        return String.valueOf(verdict);
    }

    /**
     * A picture at a wide desktop, a laptop and a narrow window, with the header measured at each.
     *
     * <p>Not {@link Dialogs#shotAtEveryWidth} - that one waits for an open dialog to settle, and the
     * entire point here is that there is no dialog. The header is where the risk is: it already
     * carries a project name, two clickable count groups, a guidance line and two workspace tabs,
     * and the strip is a full-width row added above all of it.
     */
    private static void shots(Page page, String name) {
        for (int width : new int[] {1600, 1280, 960}) {
            page.setViewportSize(width, 950);
            page.waitForTimeout(350);
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("target", name + "-" + width + ".png")));
            Object overflow = page.evaluate(
                "() => {"
                + "  const header = document.querySelector('[data-testid=\"status-header\"]');"
                + "  if (!header) return 'NO HEADER';"
                + "  const bad = [];"
                + "  if (header.scrollWidth > header.clientWidth + 1) {"
                + "    bad.push('the header scrolls sideways: ' + header.scrollWidth"
                + "      + 'px of content in ' + header.clientWidth + 'px');"
                + "  }"
                + "  if (document.body.scrollWidth > document.body.clientWidth + 1) {"
                + "    bad.push('the page scrolls sideways');"
                + "  }"
                + "  const strip = document.querySelector('[data-testid=\"autonomous-banner\"]');"
                + "  if (strip && strip.offsetParent !== null) {"
                + "    const c = strip.getBoundingClientRect();"
                + "    const h = header.getBoundingClientRect();"
                + "    if (c.right > h.right + 1 || c.left < h.left - 1 || c.width === 0) {"
                + "      bad.push('the strip is not inside the header');"
                + "    }"
                + "    if (strip.scrollHeight > strip.clientHeight + 1) {"
                + "      bad.push('the strip clips its own words');"
                + "    }"
                + "  }"
                + "  return bad.join(' | ');"
                + "}");
            assertThat(String.valueOf(overflow))
                .describedAs("the header must fit at " + width + "px - what was measured: "
                    + overflow)
                .isEmpty();
        }
        page.setViewportSize(1600, 950);
        page.waitForTimeout(350);
    }
}
