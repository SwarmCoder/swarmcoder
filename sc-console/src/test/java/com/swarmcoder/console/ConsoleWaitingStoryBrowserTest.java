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
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
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
 * A story that has to wait for another one, seen the way the operator sees it.
 *
 * <p>Its own class because only one embedded Console works per JVM — see the note on
 * {@code ConsoleBacklogBrowserTest}; Surefire's {@code reuseForks=false} gives each browser class a
 * fresh one.
 *
 * <p>What it is for: nine stories were once started together, and the board said the same thing on
 * all nine cards — "Agreed work. Nothing is building it yet." — with the same button on each. The
 * card now has to SAY what is in the way, name it in words rather than a code, and offer a
 * differently-named button for overruling it. That is a visible claim about a screen, so it is
 * looked at rather than reasoned about.
 */
class ConsoleWaitingStoryBrowserTest {

    @BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void aWaitingStorySaysWhatItIsWaitingFor(@TempDir Path dir) throws Exception {
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
            Story first = byTitle(store, projectId, "Store a book record");
            Story second = byTitle(store, projectId, "Search the shelf");
            first.setState(StoryState.READY);
            second.setState(StoryState.READY);
            second.setDependsOnStoryIds(List.of(first.id()));
            store.saveStory(first);
            store.saveStory(second);

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
                page.waitForSelector("[data-testid='pipeline-col-ready'] >> text=Search the shelf",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                String ready = page.locator("[data-testid='pipeline-col-ready']").textContent();
                // Named, with its title, and with what is happening to it. A bare "S1" would tell
                // somebody running twenty projects nothing at all.
                assertThat(ready)
                    .describedAs("the waiting card says what is in the way, in words")
                    .contains("Waiting for")
                    .contains("Store a book record")
                    .contains("agreed, not built yet");
                // The one that is free to go still reads as free to go.
                assertThat(ready).contains("Agreed work. Nothing is building it yet.");
                // Overruling is offered, and it is NOT the same button as an ordinary build.
                assertThat(ready).contains("Build it anyway").contains("Build this story");

                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-waiting-story.png")));
                OperatorWords.assertNoneOnScreen(page,
                    "the pipeline board with one story waiting for another");

                // The overrule dialog says what is being overruled before it does it.
                page.click("[data-testid='pipeline-col-ready'] >> text=\"Build it anyway\"");
                page.waitForSelector("text=\"Build it anyway\"",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("body").innerText())
                    .contains("Store a book record")
                    .contains("only see the code as it is today");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-build-anyway-dialog.png")));

                assertThat(pageErrors).isEmpty();
            }
        } finally {
            ConsoleContext.set(null);
        }
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
