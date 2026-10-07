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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing opens itself. The Console asks which project to work on, and asks again after a deletion.
 *
 * <h2>The report this pins</h2>
 *
 * <p>Deleting a project used to open whichever project was next in the list. The screen filled
 * straight back up — requirements, backlog, runs — which is indistinguishable from the deletion
 * having failed, and the obvious response to that is to delete again. That second deletion lands on
 * a project nobody meant to touch. The operator's words: <i>"it doesn't look like the previous
 * project was deleted and they may go ahead and accidentally delete the project that opens."</i>
 *
 * <h2>What is walked here, in one pass, and why each step</h2>
 *
 * <ol>
 *   <li><b>Several projects, on load.</b> The picker is up before anything else, the project the
 *       operator was in last time is marked and holds the keyboard, and no workspace has opened.</li>
 *   <li><b>One keypress opens the remembered one.</b> Enter, and nothing else — the pre-selection
 *       has to be worth having, because the operator restarts this many times a day.</li>
 *   <li><b>Delete, and the picker comes back.</b> The deleted project is gone from the list, the
 *       words say it was deleted, and NOTHING is pre-selected: what is left standing after a
 *       deletion is not a remembered choice, and putting the keyboard on it would leave the
 *       operator one press from opening something arbitrary.</li>
 *   <li><b>Exactly one project.</b> Still a picker, still a press. A single-row list is a moment's
 *       friction; opening it automatically is how the wrong project gets deleted.</li>
 *   <li><b>No projects.</b> No list, the reason in plain words, and the button that makes one.</li>
 * </ol>
 *
 * <p>Pictures at 1600, 1280 and 960 for every one of those shapes, with nothing overflowing.
 *
 * <p>No model is called anywhere in this test: nothing here needs one.
 *
 * <p>One embedded Console per JVM — see {@link ConsoleBrowserSmokeTest} for why this is its own
 * class with a single test rather than another method somewhere.
 */
class ConsoleProjectPickerBrowserTest {

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    private static final String PICKER = "[data-testid='project-picker']";
    private static final String OPEN_PICKER = PICKER + ".modal-open";
    private static final String CHOICE = PICKER + " >> [data-testid='project-choice']";

    @Test
    @RunsWhen(Need.CHROMIUM)
    void theConsoleAsksWhichProjectAndNeverAnswersForItself(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Project alpha = save(store, dir, "alpha");
            Project beta = save(store, dir, "beta");
            Project gamma = save(store, dir, "gamma");
            // Where the operator was last time. It is the suggestion, not the answer.
            store.setLastProject(beta.id());

            AtomicReference<UUID> current = new AtomicReference<>(beta.id());
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(store::listProjects, current::get,
                    (name, primaryPath, contextPaths) -> {
                        Project made = new Project(UUID.randomUUID(), name, primaryPath,
                            contextPaths == null ? List.of() : contextPaths, Instant.now(), false);
                        store.saveProject(made);
                        return made;
                    },
                    current::set)
                // What the real application does while it wires itself: the machinery has a current
                // project because everything resolves through one, and that is NOT the operator
                // having chosen it.
                .awaitingProjectChoice(beta.id()));

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

                // --- 1. several projects, on load ----------------------------------------------
                page.waitForSelector(OPEN_PICKER,
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                assertThat(page.locator(CHOICE).count())
                    .describedAs("every project is offered, and only the projects")
                    .isEqualTo(3);
                assertThat(page.locator(PICKER).innerText())
                    .describedAs("it asks; it does not report")
                    .contains("Which project do you want to work on?")
                    .contains("Nothing is open yet.");
                assertThat(page.locator(PICKER).innerText())
                    .describedAs("the project the operator was in last time is marked as such")
                    .contains("Where you were last");
                assertThat(page.locator("[data-testid='onboarding-wizard'].modal-open").count())
                    .describedAs("the getting-started guide does not compete to be first on "
                        + "screen — it opens only when it is asked for, or straight after a "
                        + "project is created")
                    .isZero();
                assertThat(focusedProjectId(page))
                    .describedAs("the keyboard is on the remembered project, so the ordinary day "
                        + "is one keypress")
                    .isEqualTo(beta.id().toString());
                Dialogs.shotAtEveryWidth(page, "console-project-picker-many", () ->
                    Dialogs.assertNothingOverflows(page, PICKER + " .modal-box",
                        "the project picker with several projects"));

                // --- 2. one keypress, and only because it was pressed --------------------------
                page.keyboard().press("Enter");
                page.waitForSelector(OPEN_PICKER, new Page.WaitForSelectorOptions()
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED)
                    .setTimeout(15_000));
                assertThat(current.get())
                    .describedAs("Enter on the remembered project opened THAT project")
                    .isEqualTo(beta.id());
                page.waitForSelector("[data-testid='project-menu']:has-text('beta')",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- 3. delete it, and the picker comes back with it gone ----------------------
                deleteProject(page, beta, "beta");
                page.waitForSelector(OPEN_PICKER,
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                assertThat(page.locator(PICKER).innerText())
                    .describedAs("the outcome of the deletion is said in words, which is the whole "
                        + "of the report: a deletion that does not look like one gets repeated")
                    .contains("That project is deleted.")
                    .contains("was not touched");
                assertThat(page.locator(CHOICE).count()).isEqualTo(2);
                assertThat(page.locator(PICKER).innerText())
                    .describedAs("and the deleted project is not offered")
                    .doesNotContain("beta");
                assertThat(page.locator(PICKER).innerText())
                    .describedAs("nothing is suggested after a deletion — what is left standing is "
                        + "not a remembered choice")
                    .doesNotContain("Where you were last");
                assertThat(focusedProjectId(page))
                    .describedAs("and nothing holds the keyboard, so Enter opens nothing")
                    .isEmpty();
                assertThat(store.getProject(beta.id()))
                    .describedAs("the deletion really happened — this is not a picker over a "
                        + "project that survived")
                    .isNull();
                // …and the shell BEHIND the dialog agrees with it. The header used to go on naming
                // the deleted project, over a board still drawing its work, which is a console
                // that looks exactly as it did before the deletion — the operator's whole point.
                page.waitForSelector("[data-testid='project-menu']:has-text('No project open')",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='project-menu']").innerText())
                    .describedAs("nothing behind the picker claims the deleted project is open")
                    .doesNotContain("beta");
                Dialogs.shotAtEveryWidth(page, "console-project-picker-after-delete", () ->
                    Dialogs.assertNothingOverflows(page, PICKER + " .modal-box",
                        "the project picker after a deletion"));

                // --- 4. exactly one project ----------------------------------------------------
                open(page, gamma);
                deleteProject(page, alpha, "alpha");
                page.waitForSelector(OPEN_PICKER,
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                assertThat(page.locator(CHOICE).count())
                    .describedAs("one project left, and it is still offered rather than opened")
                    .isEqualTo(1);
                assertThat(page.locator(PICKER).innerText()).contains("gamma");
                Dialogs.shotAtEveryWidth(page, "console-project-picker-one", () ->
                    Dialogs.assertNothingOverflows(page, PICKER + " .modal-box",
                        "the project picker with one project"));

                // --- 5. no projects at all -----------------------------------------------------
                open(page, gamma);
                deleteProject(page, gamma, "gamma");
                page.waitForSelector(OPEN_PICKER,
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                assertThat(page.locator(CHOICE).count()).isZero();
                assertThat(page.locator(PICKER).innerText())
                    .describedAs("no list, the reason in plain words, and the one thing there is "
                        + "left to do")
                    .contains("You have no projects yet")
                    .contains("That was your last project.")
                    .contains("Create a new project");
                assertThat(store.listProjects()).isEmpty();
                Dialogs.shotAtEveryWidth(page, "console-project-picker-none", () ->
                    Dialogs.assertNothingOverflows(page, PICKER + " .modal-box",
                        "the project picker with no projects"));
            }
        } finally {
            ConsoleContext.set(null);
        }
    }

    /** Opens a project from the picker, the way the operator does: press its Open button. */
    private static void open(Page page, Project project) {
        page.click(PICKER + " >> [data-testid='open-project'][data-project-id='"
            + project.id() + "']");
        page.waitForSelector(OPEN_PICKER, new Page.WaitForSelectorOptions()
            .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED)
            .setTimeout(15_000));
    }

    /**
     * Deletes a project the long way round — the project rail, that project's gear, the danger
     * zone, the typed name — because the point of the test is what the operator sees afterwards,
     * and a deletion made through the service would not produce it.
     */
    private static void deleteProject(Page page, Project project, String typedName) {
        page.click("[data-testid='project-menu']");
        String gear = "[data-testid='project-settings'][data-project-id='" + project.id() + "']";
        page.waitForSelector(gear, new Page.WaitForSelectorOptions().setTimeout(15_000));
        page.click(gear);
        page.waitForSelector("[data-testid='reveal-delete-project']",
            new Page.WaitForSelectorOptions().setTimeout(15_000));
        page.click("[data-testid='reveal-delete-project']");
        page.waitForSelector("[data-testid='delete-confirm-name']",
            new Page.WaitForSelectorOptions().setTimeout(15_000));
        page.fill("[data-testid='delete-confirm-name']", typedName);
        page.click("[data-testid='confirm-delete-project']");
    }

    /**
     * The project id of whatever holds the keyboard, or "" when it is nothing in the picker.
     *
     * <p>Read from the page rather than inferred from the styling: "pre-selected but still needs a
     * press" is a claim about focus, and only focus can prove it.
     */
    private static String focusedProjectId(Page page) {
        Object id = page.evaluate(
            "() => {"
            + "  const active = document.activeElement;"
            + "  if (!active || !active.getAttribute) return '';"
            + "  return active.getAttribute('data-project-id') || '';"
            + "}");
        return String.valueOf(id);
    }

    private static Project save(ArtifactStore store, Path dir, String name) {
        Project project = new Project(UUID.randomUUID(), name, dir.resolve(name).toString(),
            List.of(), Instant.now(), false);
        store.saveProject(project);
        return project;
    }
}
