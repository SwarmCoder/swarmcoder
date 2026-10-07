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
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With no project, the Console must not advertise what it cannot do — while still showing the
 * process it is asking you to start.
 *
 * <p>The shell used to show Requirements, a Swarm Board, Approvals, Insights and a Backlog before
 * any project existed, all scoped to a project that was not there, and the author's call was to HIDE
 * them. "Guided, not locked" keeps the two workspaces clickable — a map that hides its own later
 * steps cannot teach the process — while the surfaces inside them stay unoffered. So this asserts:
 *
 * <ul>
 *   <li><b>Setup is not where you land any more</b> (UX v3 §3.3). It stopped being a step: it is the
 *       header's health dot, which goes red and opens the fix-it surface when asked. Landing a
 *       first-time operator in a configuration panel put setup in front of everybody who had come to
 *       do something else;</li>
 *   <li>both workspaces are present and clickable, and clicking one explains itself rather than
 *       showing a convincing empty screen;</li>
 *   <li>the individual surfaces — Insights, Guidelines, Knowledge, developer tools — are
 *       still not visible anywhere, because every one of them is scoped to a project;</li>
 *   <li>the absence is accounted for, with the concrete next step, because hiding without explaining
 *       reads as broken.</li>
 * </ul>
 *
 * <p>One embedded Console per JVM — see {@link ConsoleBrowserSmokeTest} for why this is its own
 * class.
 */
class ConsoleReadinessBrowserTest {

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void withNoProjectTheShellHidesEverythingAndExplainsWhy(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            // No projects at all — the state a first-time operator actually sees.
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, () -> null, (n, p, c) -> null, id -> { }));

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                Page page = browser.newPage();
                page.setViewportSize(1600, 950);
                page.navigate("http://localhost:" + server.port() + "/");

                // The shell is up.
                page.waitForSelector("[data-testid='status-header']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));

                // TWO workspaces, and only two. Setup and the reference faces are reachable without
                // being on the path, which is the whole of the navigation collapse.
                for (String workspace : List.of("requirements", "plan")) {
                    assertThat(page.locator("[data-testid='stage-" + workspace + "']").isVisible())
                        .describedAs("%s is a workspace and must have a tab", workspace)
                        .isTrue();
                }
                for (String offPath : List.of("setup", "build")) {
                    assertThat(page.locator("[data-testid='stage-" + offPath + "']").count())
                        .describedAs("%s must not be a tab: it is reachable, not a step", offPath)
                        .isZero();
                }

                // Setup is the health dot. Red, and saying so in a word — the sentence is in its
                // tooltip and in the surface it opens, because a blocker is a paragraph and a
                // paragraph in a 44px row either truncates or pushes the counts off the screen.
                assertThat(page.locator("[data-testid='health-dot']").textContent())
                    .describedAs("with nothing configured the dot must not read as ready")
                    .contains("setup");
                // …and it opens the fix-it surface, which is the only way in now that it is not a step.
                page.click("[data-testid='health-dot']");
                page.waitForSelector("text=Set up SwarmCoder",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

                // The SURFACES inside those stages are a different matter: they are scoped to a
                // project and would hold nothing, so none of them is offered. Asserted on
                // VISIBILITY, not on page.content(): hiding is display-level, so the markup is
                // still present and a content check would pass while the operator is still looking
                // at a full menu.
                //
                // The selectors are QUOTED, which makes them exact-text rather than the default
                // case-insensitive substring. Unquoted, "Knowledge" matched the setup panel's own
                // prose ("…the backlog and knowledge already work without one") — a sentence that is
                // visible and is supposed to be, so the assertion was testing the explanation
                // instead of the menu.
                // "Swarm Board" is gone from this list because it is gone from the product: the
                // workers are inside the run they belong to now, not a destination of their own.
                // "In build" has left this list because it has left the product: the stories being
                // built are on the pipeline board with every other story, not on a face of their own.
                for (String hidden : List.of("Plan stories", "Insights",
                        "Prompt Lab", "Knowledge", "Guidelines", "Components")) {
                    assertThat(page.locator("text=\"" + hidden + "\"").first().isVisible())
                        .describedAs("%s must not be offered with no project", hidden)
                        .isFalse();
                }

                // Clicking a not-yet-ready stage is allowed, and it explains itself rather than
                // showing a convincing empty workspace.
                // Clicking a workspace that cannot mean anything yet is allowed, and it explains
                // itself rather than showing a convincing empty board.
                page.click("[data-testid='stage-plan']");
                page.waitForSelector("text=Pipeline needs a project",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[data-testid='health-dot']");

                // …and the absence is accounted for, with the next concrete step.
                String content = page.content();
                assertThat(content).contains("A project");
                assertThat(content).contains("A git repository");
                assertThat(content).contains("Create a project to begin");

                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-setup.png")));
            }
        } finally {
            ConsoleContext.set(null);
        }
    }
}
