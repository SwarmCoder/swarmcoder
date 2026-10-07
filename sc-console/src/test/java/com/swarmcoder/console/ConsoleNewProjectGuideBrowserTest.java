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
import com.swarmcoder.domain.Project;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Making a project is the moment somebody is told what SwarmCoder is.
 *
 * <p><b>The fault this pins.</b> The guide was built, screenshotted and tested, and the first
 * person other than its author to use the Console never saw it: "I create a new project, no sign of
 * a wizard". Both of its ways in were on the Setup panel, and Setup is not a place anybody is sent
 * — {@code Stages.recommended} deliberately never lands on it, and it has no tab. So the guide sat
 * behind a small mark in the corner of the header, on a panel a new operator has no reason to open,
 * for the one person it was written for.
 *
 * <p>The walk here is the walk a person actually takes and nothing else: open the list of projects,
 * press the plus, type a folder, press Create. Nothing is clicked that a first-time operator would
 * have to be told about, which is the whole point — the previous test for this feature started by
 * clicking the health mark in the header, and a test that starts where nobody starts cannot find
 * this class of fault.
 *
 * <h2>What is asserted, and why each one</h2>
 *
 * <ul>
 *   <li><b>It arrives by itself.</b> No card is hunted for and no menu is opened. If the guide
 *       needs finding, this fails.</li>
 *   <li><b>It goes away and stays away.</b> Closed, then the page is loaded again from scratch: a
 *       guide that reappears every time the window is opened is worse than one nobody found.</li>
 *   <li><b>Only on a project that was MADE.</b> Switching to a project that already exists runs
 *       the same "the project changed" path, and somebody moving between their own projects has
 *       been here before. This is the one way the trigger could double-fire, so it is walked.</li>
 *   <li><b>The words.</b> {@link OperatorWords} on the screen it opens at.</li>
 *   <li><b>It fits.</b> Pictures at 1600, 1280 and 960, with nothing sticking out of the dialog at
 *       any of them.</li>
 * </ul>
 *
 * <p>One embedded Console per JVM — see {@link ConsoleBrowserSmokeTest} for why this is its own
 * class rather than another method on an existing one.
 */
class ConsoleNewProjectGuideBrowserTest {

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void makingAProjectExplainsWhatSwarmCoderIs(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            // One project that already exists, so "switch to one you already have" can be walked
            // afterwards. It is not the one the operator makes below.
            Project older = new Project(UUID.randomUUID(), "alpha", dir.resolve("alpha").toString(),
                List.of(), Instant.now(), false);
            store.saveProject(older);

            List<Project> registry = new ArrayList<>(List.of(older));
            AtomicReference<UUID> current = new AtomicReference<>(older.id());
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> List.copyOf(registry), current::get,
                    (name, primaryPath, contextPaths) -> {
                        Project made = new Project(UUID.randomUUID(),
                            name == null || name.isBlank() ? "beta" : name,
                            primaryPath, contextPaths == null ? List.of() : contextPaths,
                            Instant.now(), false);
                        store.saveProject(made);
                        registry.add(made);
                        return made;
                    },
                    current::set));

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                String home = "http://localhost:" + server.port() + "/";
                Page page = browser.newPage();
                page.onConsoleMessage(m -> System.out.println("[browser " + m.type() + "] "
                    + m.text()));
                page.setViewportSize(1600, 950);
                page.navigate(home);
                page.waitForSelector("[data-testid='status-header']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));

                // Nothing has been made yet, so nothing explains itself yet. Asserted before the
                // walk so the assertion after it means something.
                assertThat(page.locator("[data-testid='onboarding-wizard'].modal-open").count())
                    .describedAs("the guide must not be in the way of somebody who did not ask "
                        + "for it")
                    .isZero();

                // --- the walk: make a project ---------------------------------------------------
                page.click("[data-testid='project-menu']");
                page.waitForSelector("[title='New project']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[title='New project']");
                page.waitForSelector("label:has-text('The folder holding the code')",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                // By the label's own "for", as in ConsoleDialogsBrowserTest: the boxes are named by
                // captions, and the placeholder carries an example rather than the name.
                String pathField = "dialog.modal.modal-open input#"
                    + page.getAttribute("label:has-text('The folder holding the code')", "for");
                page.fill(pathField, dir.resolve("beta").toString());
                page.click("dialog.modal.modal-open >> text=Create");

                // --- and it explains itself, with nothing else pressed --------------------------
                page.waitForSelector("[data-testid='onboarding-wizard'].modal-open",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                page.waitForSelector(
                    "text=SwarmCoder writes code for you, and proves it works before you keep it.",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.content())
                    .describedAs("it opens at the beginning, and says so without inventing a total "
                        + "— the three routes are different lengths and none has been picked yet")
                    .contains("First: where to start");
                assertThat(page.locator("dialog.modal.modal-open").count())
                    .describedAs("the guide stands alone — the form that made the project and the "
                        + "list of projects behind it are both retired, or the page stays blocked "
                        + "behind a modal nobody can see")
                    .isEqualTo(1);
                OperatorWords.assertNoneOnScreen(page, "the guide, opened by making a project");
                Dialogs.shotAtEveryWidth(page, "console-new-project-guide", () ->
                    Dialogs.assertNothingOverflows(page,
                        "[data-testid='onboarding-wizard'] .modal-box",
                        "the guide as it opens on a new project"));

                // --- closing it means closed ----------------------------------------------------
                // By its aria-label. The close control is an icon with no words beside it —
                // Button(icon, label) puts the label in aria-label since ZeroZ Stack 0.8.0 — so a
                // text selector waits for ever on a button that is plainly on the screen.
                page.click("dialog.modal.modal-open >> [aria-label='Close']");
                page.waitForSelector("[data-testid='onboarding-wizard'].modal-open",
                    new Page.WaitForSelectorOptions()
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED)
                        .setTimeout(10_000));
                // And what is behind it is the workspace the operator was sent to, not the panel
                // the guide used to live on.
                page.waitForSelector("[data-testid='requirements-tree']",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                // The screen a new project lands on is empty, and it offers the same walk. Somebody
                // who closed the guide before reading it must not have to remember where it was.
                assertThat(page.locator("[data-testid='requirements-empty-guide']").isVisible())
                    .describedAs("the empty requirements list offers the guide, because that is "
                        + "the first screen a new project shows anybody")
                    .isTrue();
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-new-project-guide-closed.png")));

                // --- opening the window again does not start it over ----------------------------
                page.navigate(home);
                page.waitForSelector("[data-testid='status-header']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
                // Asked for by name rather than waited for: which workspace a reload lands on is
                // the server's recommendation and is not what this test is about, and a stage that
                // is merely not the one showing is still in the page, hidden.
                page.click("[data-testid='stage-requirements']");
                page.waitForSelector("[data-testid='requirements-tree']",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-new-project-guide-reloaded.png")));
                assertThat(page.locator("[data-testid='onboarding-wizard'].modal-open").count())
                    .describedAs("a guide that comes back every time the window is opened is "
                        + "nagging, not help")
                    .isZero();

                // --- and there is a way back in, in words, where a person looks for one ----------
                // The fault this whole entry point exists for: after the reload above, every route
                // back into the guide was the small coloured health mark in the corner of the
                // header. This is the same assertion the author made by hand — press the thing that
                // says "Getting started" and the guide is there.
                assertThat(page.locator("[data-testid='open-guide']").isVisible())
                    .describedAs("the way back into the guide is visible on every screen, and it "
                        + "has words on it")
                    .isTrue();
                page.click("[data-testid='open-guide']");
                page.waitForSelector("[data-testid='onboarding-wizard'].modal-open",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("dialog.modal.modal-open >> [aria-label='Close']");
                page.waitForSelector("[data-testid='onboarding-wizard'].modal-open",
                    new Page.WaitForSelectorOptions()
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED)
                        .setTimeout(10_000));

                // --- switching to a project you already have does not start it either -----------
                // The same "the project changed" path runs on a switch as on a create, so this is
                // the one way the trigger could fire at somebody who has been here before.
                page.click("[data-testid='project-menu']");
                page.waitForSelector("dialog.modal.modal-open >> text=\"alpha\"",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("dialog.modal.modal-open >> text=\"alpha\"");
                // The header chip is what says which project you are in, so this is the switch
                // having actually landed in the browser rather than only on the server.
                page.waitForSelector("[data-testid='project-menu'] >> text=\"alpha\"",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                // The switcher stays up after a switch — it is a list you may want to keep using —
                // so it has to be put away before anything behind it can be clicked.
                page.click("dialog.modal.modal-open >> text=\"Close\"");
                page.click("[data-testid='stage-requirements']");
                page.waitForSelector("[data-testid='requirements-tree']",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                assertThat(page.locator("[data-testid='onboarding-wizard'].modal-open").count())
                    .describedAs("moving between projects you already have is not a first day")
                    .isZero();
                assertThat(current.get())
                    .describedAs("and the switch really happened, so the check above is not "
                        + "passing because nothing did anything")
                    .isEqualTo(older.id());
            }
        } finally {
            ConsoleContext.set(null);
        }
    }
}
