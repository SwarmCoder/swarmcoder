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
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guide, walked the way an operator walks it — and it does the work as it goes.
 *
 * <p>This replaces a test that walked a leaflet. The old guide explained SwarmCoder over two pages
 * of prose and then ended each route at a button that closed it and left the operator on another
 * screen; the old test proved the prose was on the screen and that the button navigated. Both were
 * true, and both were the fault. The author's words after using it for real: <i>"The wizard should
 * walk the user through the whole process and not delegate to other screens"</i>, and <i>"the
 * wizard is not a book to read, but a step-through of the different approaches to using the tool.
 * It must explain while it is collecting data."</i>
 *
 * <h2>What is asserted, and why each one</h2>
 *
 * <ul>
 *   <li><b>There is a way in that has words on it.</b> The guide's entry points were all on the
 *       Setup panel, and Setup is reached only through the small coloured health mark in the corner
 *       of the header. Nobody clicks a health indicator looking for a guide, and nobody did. The
 *       walk here starts from the control that says "Getting started".</li>
 *   <li><b>It does the work.</b> The drop box documents are added through is INSIDE the guide's own
 *       dialog, and agreeing a requirement from the guide really changes the project. Those two are
 *       the whole of "does not delegate to other screens", and the second is checked against the
 *       persisted requirement rather than against anything on the screen.</li>
 *   <li><b>Each word is explained on the step that uses it.</b> Document where one is added,
 *       requirement and check where they are extracted, story where they are planned, build where
 *       one is started — and none of them on a page of its own before anything has happened. UX v3
 *       §2.1 is still the whole vocabulary; what changed is where it lands.</li>
 *   <li><b>Every route reaches every other one.</b> UX v3 §2.2 and the author's decision that this
 *       picks a starting point, not a kind of project. Walked, not asserted in the abstract.</li>
 *   <li><b>The route that is not built says so</b> — on the card, before it is opened, and again in
 *       its own first sentence.</li>
 *   <li><b>The words.</b> {@link OperatorWords} on every screen. The guide explains four
 *       internal-sounding nouns on purpose, which is exactly the shape of text that leaks a
 *       constant.</li>
 *   <li><b>It fits.</b> Pictures at 1600, 1280 and 960, and nothing overflows the dialog at any of
 *       them. The document step carries a drop box, a list and a paste box, and at 960 the button
 *       that carries the walk forward was pushed off the bottom of the window until the body was
 *       made to scroll — so that button's visibility is checked at every width, not just its
 *       existence.</li>
 * </ul>
 *
 * <p>One embedded Console per JVM — see {@link ConsoleBrowserSmokeTest} for why this is its own
 * class rather than another method on an existing one.
 */
class ConsoleOnboardingBrowserTest {

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void theGuideWalksTheWorkAndDoesIt(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            // The requirements fixture, plus an intake that has already been applied: the steps
            // after the reading cannot be reached for real without a model endpoint.
            UUID projectId = RequirementsSandboxRunner.seed(store, dir);
            ConsoleLookRunner.seedAppliedIntake(store, projectId);

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                Page page = browser.newPage();
                page.onPageError(e -> System.out.println("[page error] " + e));
                page.setViewportSize(1600, 950);
                page.navigate("http://localhost:" + server.port() + "/");
                page.waitForSelector("[data-testid='status-header']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));

                // --- the way in, in words -------------------------------------------------------
                assertThat(page.locator("[data-testid='open-guide']").innerText())
                    .describedAs("the way into the guide is a control with words on it, not the "
                        + "coloured health mark it used to hide behind")
                    .contains("Getting started");
                page.click("[data-testid='open-guide']");
                page.waitForSelector("[data-testid='onboarding-wizard'].modal-open",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

                // --- step 1: where to start -----------------------------------------------------
                page.waitForSelector(
                    "text=SwarmCoder writes code for you, and proves it works before you keep it.");
                assertThat(page.content())
                    .describedAs("no total until a route is picked — the three are different "
                        + "lengths, so any number here would be wrong for two of them")
                    .contains("First: where to start");
                assertThat(page.content())
                    .describedAs("the vocabulary is not a page of its own any more")
                    .doesNotContain("SwarmCoder has four things in it");
                assertThat(page.locator("text=Not available yet").first().isVisible())
                    .describedAs("the route that is not built says so on the card, not only once "
                        + "it has been opened")
                    .isTrue();
                OperatorWords.assertNoneOnScreen(page, "the guide, where to start");
                Dialogs.shotAtEveryWidth(page, "console-guide-1-where", () ->
                    Dialogs.assertNothingOverflows(page,
                        "[data-testid='onboarding-wizard'] .modal-box",
                        "the three starting-point cards"));

                // --- step 2: adding a document happens HERE -------------------------------------
                page.click("text=I have written down what I want built");
                page.waitForSelector("text=Add what you have written down.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.content()).contains("Step 2 of 6");
                assertThat(page.locator("[data-testid='onboarding-wizard'] "
                        + ">> text=Drop your requirements documents here").isVisible())
                    .describedAs("the box documents are added through is INSIDE the guide — this "
                        + "is the whole of \"the wizard does it itself\"")
                    .isTrue();
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("and the word Document is explained here, where one is added, "
                        + "rather than in a glossary two screens earlier")
                    .contains("Anything you hand SwarmCoder to read")
                    .contains("Paste the words straight in");
                OperatorWords.assertNoneOnScreen(page, "the guide, adding a document");
                Dialogs.shotAtEveryWidth(page, "console-guide-2-documents", () -> {
                    Dialogs.assertNothingOverflows(page,
                        "[data-testid='onboarding-wizard'] .modal-box",
                        "the document step");
                    // This was off the bottom of the window at 960 before the body was made to
                    // scroll, which makes the walk a dead end at the width it matters most at.
                    assertThat(page.locator("[data-testid='guide-next']").isVisible())
                        .describedAs("the button that carries the walk forward is on the screen at "
                            + "every window width")
                        .isTrue();
                });

                // --- step 3: the reading, and the two words it needs ----------------------------
                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=SwarmCoder is reading what you gave it.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.content()).contains("Step 3 of 6");
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("requirement and check are explained on the screen where the "
                        + "operator first meets the things they name")
                    .contains("One thing that must be true about the finished software")
                    .contains("A small, exact statement inside a requirement that a test can prove");
                OperatorWords.assertNoneOnScreen(page, "the guide, the reading");
                Dialogs.shotAtEveryWidth(page, "console-guide-3-reading");

                // --- step 4: agreeing, done here, and it really changes the project -------------
                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=Agree what it found.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.content()).contains("Step 4 of 6");
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("the requirement waiting on the operator is listed here by name, "
                        + "with the button that agrees it")
                    .contains("Saved cards");
                assertThat(statusOf(store, projectId, "R3"))
                    .describedAs("and it is a draft before anything is pressed, so the assertion "
                        + "below is not passing because nothing happened")
                    .isEqualTo(RequirementStatus.DRAFT);
                OperatorWords.assertNoneOnScreen(page, "the guide, agreeing");
                Dialogs.shotAtEveryWidth(page, "console-guide-4-agree", () ->
                    Dialogs.assertNothingOverflows(page,
                        "[data-testid='onboarding-wizard'] .modal-box",
                        "the agreement step"));

                page.click("[data-testid='onboarding-wizard'] >> text=\"Agree\"");
                page.waitForSelector("text=Nothing is waiting on you.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(statusOf(store, projectId, "R3"))
                    .describedAs("pressing Agree in the GUIDE agreed the requirement in the "
                        + "project — the work is done in the wizard, not described by it")
                    .isEqualTo(RequirementStatus.ACTIVE);

                // --- step 5: the stories --------------------------------------------------------
                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=Turn the agreed requirements into slices of work.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.content()).contains("Step 5 of 6");
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("story is explained on the step that makes them")
                    .contains("One slice of work.");
                OperatorWords.assertNoneOnScreen(page, "the guide, the stories");
                // This project HAS a story, so moving on is legitimate here and the step offers
                // it. Two things about how it offers it, both reported by the author from a real
                // run of the step in its other state:
                //
                // One main action, not two. "Plan more work" is a quiet outline button beside it
                // (UX v3 3.1, the card grammar the pipeline board follows) - with nothing planned
                // this step used to colour BOTH as the main action and put the one that led
                // nowhere rightmost, where the eye lands.
                assertThat(page.locator("[data-testid='onboarding-wizard'] .btn-primary").count())
                    .describedAs("one main action on the step, whatever state it is in")
                    .isEqualTo(1);
                // And it does not name an act it does not perform. It used to read "Next: build
                // one" and it builds nothing: it moves the walk to the last step, which then
                // explains that a build is started from a story's card on the board.
                assertThat(page.locator("[data-testid='guide-next']").innerText())
                    .describedAs("the button that carries the walk forward says what the next "
                        + "step is about, not what pressing it does not do")
                    .doesNotContain("build one");
                Dialogs.shotAtEveryWidth(page, "console-guide-5-stories");

                // --- the hand-over, and the walk coming back ------------------------------------
                // This is what "does not delegate to other screens" means for the two steps whose
                // work already has a surface of its own. Planning is hundreds of lines of proposal
                // review; a second copy of it would be a second answer to the same question. So the
                // guide opens the real one and comes back to the same step when it closes.
                //
                // A swap, not a stack. Opening one modal over another was tried and the screenshot
                // showed the new window painted BEHIND the guide, dimmed by its own backdrop — the
                // browser draws both in its top layer and the result was unusable.
                page.click("[data-testid='onboarding-wizard'] >> text=\"Plan more work\"");
                page.waitForSelector("[data-testid='guide-planning-wizard'].modal-open",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='onboarding-wizard'].modal-open").count())
                    .describedAs("one window at a time — the guide steps aside rather than being "
                        + "covered by the thing it opened")
                    .isZero();
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-guide-5-planner.png")));

                page.keyboard().press("Escape");
                page.waitForSelector("[data-testid='onboarding-wizard'].modal-open",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("and closing it brings the walk back on the step it was on — not "
                        + "to nowhere, which is what the old guide did at the end of every route")
                    .contains("Turn the agreed requirements into slices of work.");

                // --- step 6: the build, and a hand-off FORWARD ----------------------------------
                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=Build one, and judge what comes back.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.content()).contains("Step 6 of 6");
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("build is explained on the step that starts one, and the step "
                        + "says where the button is before it sends anybody to look for it")
                    .contains("One attempt at one story.")
                    .contains("Ready to build");
                OperatorWords.assertNoneOnScreen(page, "the guide, the build");
                Dialogs.shotAtEveryWidth(page, "console-guide-6-build");

                // --- every route reaches every other one ----------------------------------------
                // Back out of the document route and into the other two. This is the author's
                // decision that the first screen picks a STARTING POINT, and a starting point can
                // be changed; it is walked rather than asserted in the abstract.
                for (int i = 0; i < 5; i++) {
                    page.click("[data-testid='onboarding-wizard'] >> text=\"Back\"");
                }
                page.waitForSelector("text=Pick whichever of these is closest",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

                page.click(
                    "text=I have code already, and I want SwarmCoder to work out what it does");
                page.waitForSelector("text=Starting from code that already exists");
                assertThat(page.content())
                    .describedAs("the route that is not built says so in its own first sentence")
                    .contains("This one is not available yet.");
                assertThat(page.content())
                    .describedAs("and says what to use instead, or it is a dead end")
                    .contains("Take the small-change route instead");
                OperatorWords.assertNoneOnScreen(page, "the guide, the route that is not built");
                Dialogs.shotAtEveryWidth(page, "console-guide-route-existing");

                page.click("text=Take the small-change route instead");
                page.waitForSelector("text=Starting from one small change");
                assertThat(page.content())
                    .describedAs("the short route says what a usable goal looks like, in an "
                        + "example, because a vague one is the way it fails")
                    .contains("Say something concrete");
                OperatorWords.assertNoneOnScreen(page, "the guide, one small change");
                Dialogs.shotAtEveryWidth(page, "console-guide-route-change", () ->
                    Dialogs.assertNothingOverflows(page,
                        "[data-testid='onboarding-wizard'] .modal-box",
                        "the small-change route"));

                // --- the end of a route is a door, not a dead end -------------------------------
                // QUOTED, so it is the button and not the prose above it that is clicked. An
                // unquoted text selector is a case-insensitive SUBSTRING, which once matched a
                // sentence, clicked it, and reported a guide that had refused to close.
                page.click("[data-testid='onboarding-wizard'] >> text=\"Open the chat and say it\"");
                page.waitForSelector("[data-testid='chat-dock']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='onboarding-wizard'].modal-open").count())
                    .describedAs("pressing the button at the end of a route opens the thing being "
                        + "done and closes the guide behind you")
                    .isZero();
            }
        } finally {
            ConsoleContext.set(null);
        }
    }

    private static RequirementStatus statusOf(ArtifactStore store, UUID projectId, String handle) {
        Brd brd = store.getBrd(projectId);
        for (BrdRequirement requirement : brd.requirements()) {
            if (handle.equals(requirement.handle())) {
                return requirement.status();
            }
        }
        throw new IllegalStateException("no requirement " + handle + " in this project");
    }
}
