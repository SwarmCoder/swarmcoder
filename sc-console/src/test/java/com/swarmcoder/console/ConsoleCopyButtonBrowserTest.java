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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Copy button that copies — read back off the real clipboard, not off the screen.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The project screen shows an id and a folder, each with a copy button beside it, and neither
 * did anything at all. The cause was not the clipboard API and not a missing handler. Every click
 * listener the component library registers is wrapped in {@code Component.threaded(...)} —
 * {@code new Thread(() -> ...).start()} — so the work runs on a LATER scheduler tick. A browser
 * only lets a page write to the clipboard while it is still handling the click that asked for it,
 * so by the time the copy ran the permission was gone, {@code document.execCommand('copy')}
 * returned false, and the failure was discarded. Nothing on the clipboard, nothing on the screen,
 * no error anywhere.
 *
 * <p>That wrapper is what lets an ordinary listener make a suspending server call, so it is right
 * for almost everything and wrong for exactly this. The fix is {@code Clipboard.onCopyClick},
 * which registers the listener straight onto the element and does nothing that could suspend.
 *
 * <h2>Why the clipboard itself is read</h2>
 *
 * <p>The button already said nothing when it worked and nothing when it did not, so no assertion
 * about the screen could have caught this. The browser context is granted clipboard permission and
 * the test asks the page what is actually on the clipboard afterwards. That is the only assertion
 * that means the button works.
 *
 * <p>The fallback is checked too: the value carries {@code select-all}, so an operator whose
 * browser refuses the clipboard can still take the text.
 *
 * <p>No model is called anywhere in this test.
 *
 * <p>One embedded Console per JVM — see {@link ConsoleBrowserSmokeTest}.
 */
class ConsoleCopyButtonBrowserTest {

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void theCopyButtonsOnTheProjectScreenReallyCopy(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        String projectPath = dir.resolve("alpha").toString();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Project alpha = new Project(projectId, "alpha", projectPath, List.of(),
                Instant.now(), false);
            store.saveProject(alpha);
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(store::listProjects, () -> projectId, (n, p, c) -> null, id -> { }));

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                // READ permission only, and that is the point of the test.
                //
                // A browser lets a page WRITE to the clipboard either because permission was
                // granted or because the page is still handling the click that asked. Granting
                // write permission here would make both a working button and the broken one pass,
                // because the broken one's fault is that it runs after the click is over. Without
                // it, only a control that copies while the gesture is still live can succeed —
                // which is exactly the behaviour under test. Read permission is needed to look at
                // the clipboard afterwards and is not part of what is being proved.
                Browser.NewContextOptions options = new Browser.NewContextOptions()
                    .setPermissions(List.of("clipboard-read"));
                Page page = browser.newContext(options).newPage();
                page.onPageError(e -> System.out.println("[page error] " + e));
                page.setViewportSize(1600, 950);
                page.navigate("http://localhost:" + server.port() + "/");
                page.waitForSelector("[data-testid='status-header']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));

                // The project screen: the header chip, then this project's gear.
                page.click("[data-testid='project-menu']");
                String gear = "[data-testid='project-settings'][data-project-id='" + projectId + "']";
                page.waitForSelector(gear, new Page.WaitForSelectorOptions().setTimeout(15_000));
                page.click(gear);
                page.waitForSelector("[data-testid='copy-id']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                Dialogs.settled(page);

                // --- the id ----------------------------------------------------------------------
                assertThat(page.locator("[data-testid='copy-value-id']").innerText())
                    .describedAs("the value is on the screen to begin with")
                    .isEqualTo(projectId.toString());
                page.click("[data-testid='copy-id']");
                // Read back from the browser, because a button that does nothing and a button that
                // works looked identical on screen. The wait is because the modern clipboard call
                // is a promise; the value either arrives or this fails.
                awaitClipboard(page, projectId.toString(),
                    "pressing the id's copy button puts the id on the clipboard");
                // Waited for, not read straight away: the modern clipboard call is a promise, so
                // the word arrives on the tick after the write lands.
                page.waitForSelector("dialog.modal.modal-open >> text=\"Copied\"",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("dialog.modal.modal-open").innerText())
                    .describedAs("it says what happened, so a press is never a silent event")
                    .contains("Copied");

                // --- the folder ------------------------------------------------------------------
                page.click("[data-testid='copy-primary-path']");
                awaitClipboard(page, projectPath,
                    "the folder's copy button copies the folder, not the id it sits under");

                // --- the fallback, for a browser that refuses the clipboard ----------------------
                assertThat(page.evaluate(
                    "() => getComputedStyle("
                    + "document.querySelector(\"[data-testid='copy-value-id']\")).userSelect"))
                    .describedAs("one click on the value selects the whole of it, which is the way "
                        + "of getting the text that cannot be refused")
                    .isEqualTo("all");

                Dialogs.shotAtEveryWidth(page, "console-project-copy", () ->
                    Dialogs.assertNothingOverflows(page, "dialog.modal.modal-open .modal-box",
                        "the project screen carrying the copy controls"));
            }
        } finally {
            ConsoleContext.set(null);
        }
    }

    /**
     * Waits for the clipboard to hold exactly {@code expected}, then says so; fails with what it
     * actually held.
     */
    private static void awaitClipboard(Page page, String expected, String why) {
        try {
            page.waitForFunction(
                "want => navigator.clipboard.readText().then(got => got === want)",
                expected, new Page.WaitForFunctionOptions().setTimeout(10_000));
        } catch (RuntimeException e) {
            String actual = String.valueOf(
                page.evaluate("() => navigator.clipboard.readText()"));
            assertThat(actual).describedAs(why).isEqualTo(expected);
            throw e;
        }
    }
}
