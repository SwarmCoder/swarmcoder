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
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementStatus;
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
 * The requirement graph's drawing: lines go round boxes, no text sits on the lines, and the picture
 * fits and zooms with the panel.
 *
 * <p>Alone in its class for the reason {@link ConsoleBrdBrowserTest} gives (one embedded Console per
 * JVM). Set {@code -Dswarmcoder.graphCase=large} for 30 requirements instead of 8, and
 * {@code -Dswarmcoder.screens=<folder>} to also save screenshots there.
 */
class ConsoleBrdGraphLayoutBrowserTest {

    @TempDir
    Path storeDir;

    @BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    private static BrdRequirement req(List<BrdRequirement> into, String handle, String title) {
        BrdRequirement r = new BrdRequirement(UUID.randomUUID(), handle, title,
            title + " works as described", Priority.MEDIUM, RequirementStatus.ACTIVE, null);
        into.add(r);
        return r;
    }

    /** A small bookshelf app: a link across a box in the same row, crossing links, one loner. */
    private static Brd small(UUID projectId) {
        List<BrdRequirement> r = new ArrayList<>();
        BrdRequirement r1 = req(r, "R1", "Add a book");
        BrdRequirement r2 = req(r, "R2", "Search the shelf");
        BrdRequirement r3 = req(r, "R3", "Lend a book");
        BrdRequirement r4 = req(r, "R4", "Reading list");
        BrdRequirement r5 = req(r, "R5", "Import from file");
        BrdRequirement r6 = req(r, "R6", "Share a list");
        req(r, "R7", "Scan a bar code");
        BrdRequirement r8 = req(r, "R8", "Sync devices");
        List<BrdEdge> e = new ArrayList<>();
        e.add(new BrdEdge(r3.id(), r2.id(), RequirementRelation.DEPENDS_ON));
        e.add(new BrdEdge(r4.id(), r3.id(), RequirementRelation.DEPENDS_ON));
        // R4 and R1 share a row with R3 between them.
        e.add(new BrdEdge(r4.id(), r1.id(), RequirementRelation.DEPENDS_ON));
        // Crosses R3 -> R2.
        e.add(new BrdEdge(r5.id(), r1.id(), RequirementRelation.REFINES));
        e.add(new BrdEdge(r6.id(), r5.id(), RequirementRelation.DEPENDS_ON));
        e.add(new BrdEdge(r6.id(), r2.id(), RequirementRelation.DEPENDS_ON));
        e.add(new BrdEdge(r8.id(), r4.id(), RequirementRelation.CONFLICTS_WITH));
        return new Brd(UUID.randomUUID(), projectId, 1, "Business Requirements",
            new ArrayList<>(r), e, Instant.now(), Instant.now());
    }

    private static Brd large(UUID projectId) {
        List<BrdRequirement> r = new ArrayList<>();
        String[] topics = {"Add", "Edit", "Remove", "Search", "Tag", "Lend", "Return", "Import",
            "Export", "Share"};
        for (int i = 0; i < 30; i++) {
            req(r, "R" + (i + 1), topics[i % topics.length] + " books " + (i / topics.length + 1));
        }
        List<BrdEdge> e = new ArrayList<>();
        // Five layers of six: each waits on the one six before it, some skip a layer, which puts
        // a box between the two ends of the link.
        for (int i = 6; i < 30; i++) {
            e.add(new BrdEdge(r.get(i).id(), r.get(i - 6).id(), RequirementRelation.DEPENDS_ON));
            if (i >= 12 && i % 2 == 0) {
                e.add(new BrdEdge(r.get(i).id(), r.get(i - 12).id(), RequirementRelation.REFINES));
            }
            if (i % 7 == 0) {
                e.add(new BrdEdge(r.get(i).id(), r.get(i - 5).id(), RequirementRelation.CONFLICTS_WITH));
            }
        }
        e.add(new BrdEdge(r.get(1).id(), r.get(20).id(), RequirementRelation.GATES));
        e.add(new BrdEdge(r.get(3).id(), r.get(26).id(), RequirementRelation.GATES));
        return new Brd(UUID.randomUUID(), projectId, 1, "Business Requirements",
            new ArrayList<>(r), e, Instant.now(), Instant.now());
    }

    /** Samples every drawn line and returns how many sample points fall inside a box other than its ends. */
    private static final String LINES_THROUGH_BOXES = """
        () => {
          const boxes = [...document.querySelectorAll('g[data-node] rect')].map(r => ({
            x: +r.getAttribute('x'), y: +r.getAttribute('y'),
            w: +r.getAttribute('width'), h: +r.getAttribute('height')}));
          let hits = 0, lines = 0;
          for (const p of document.querySelectorAll('path[data-edge]')) {
            lines++;
            const len = p.getTotalLength();
            for (let t = 14; t < len - 14; t += 3) {
              const q = p.getPointAtLength(t);
              if (boxes.some(b => q.x > b.x + 2 && q.x < b.x + b.w - 2
                               && q.y > b.y + 2 && q.y < b.y + b.h - 2)) { hits++; break; }
            }
          }
          return {lines, hits};
        }
        """;

    private static double scaleOf(Page page) {
        return ((Number) page.evaluate("() => {"
            + " const g = document.querySelector('g[data-node]').parentNode;"
            + " return g.getScreenCTM().a; }")).doubleValue();
    }

    private static void dragDivider(Page page, double by) {
        var divider = page.locator("div.cursor-col-resize").first().boundingBox();
        double x = divider.x + divider.width / 2;
        double y = divider.y + 300;
        page.mouse().move(x, y);
        page.mouse().down();
        page.mouse().move(x + by, y, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(8));
        page.mouse().up();
        page.waitForTimeout(500);
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void linesGoRoundBoxesLabelsDoNotOverprintAndThePictureFitsAndZooms() throws Exception {
        boolean large = "large".equals(System.getProperty("swarmcoder.graphCase"));
        String screens = System.getProperty("swarmcoder.screens");
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = UUID.randomUUID();
            List<Project> projects = new ArrayList<>();
            projects.add(new Project(projectId, "bookshelf", "work/bookshelf", List.of(),
                Instant.now(), false));
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> projects, () -> projectId, (n, p, c) -> null, id -> { }));
            store.append(() -> {
                store.root().brds().put(projectId, large ? large(projectId) : small(projectId));
                return null;
            }).get();

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
                page.waitForSelector("[data-testid='stage-requirements']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
                page.click("[data-testid='stage-requirements']");
                page.waitForSelector("[data-testid='requirements-tree']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[data-testid='requirements-view-graph']");
                page.waitForSelector("[data-node='req-R1']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.waitForTimeout(600);
                if (screens != null) {
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of(screens, (large ? "large" : "small") + "-wide.png")));
                }

                // 1. No line passes through a box that is not one of its ends.
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> through =
                    (java.util.Map<String, Object>) page.evaluate(LINES_THROUGH_BOXES);
                assertThat(((Number) through.get("lines")).intValue()).isGreaterThan(5);
                assertThat(((Number) through.get("hits")).intValue())
                    .describedAs("lines drawn through a box").isZero();

                // 2. No text sits on a line: the relation is the hover text, not printed.
                assertThat(page.querySelectorAll("path[data-edge]").size()).isGreaterThan(5);
                assertThat(page.locator("svg text").allTextContents()
                    .stream().filter(t -> t.contains("waits for") || t.contains("constrains")).count())
                    .describedAs("per-line labels").isZero();

                // 3. The picture is fitted to the panel (small graphs grow), zooms on the wheel and
                // buttons, and refits when the panel narrows.
                double fitted = scaleOf(page);
                page.mouse().move(500, 400);
                page.mouse().wheel(0, -300);
                page.waitForTimeout(150);
                assertThat(scaleOf(page)).describedAs("wheel zooms in").isGreaterThan(fitted);
                page.click("[data-testid='graph-zoom-fit']");
                assertThat(scaleOf(page)).isCloseTo(fitted, org.assertj.core.data.Offset.offset(0.01));
                page.click("[data-testid='graph-zoom-in']");
                assertThat(scaleOf(page)).describedAs("button zooms in").isGreaterThan(fitted);
                page.click("[data-testid='graph-zoom-out']");
                page.click("[data-testid='graph-zoom-out']");
                assertThat(scaleOf(page)).describedAs("button zooms out").isLessThan(fitted);
                page.click("[data-testid='graph-zoom-fit']");
                // Drag the divider between the graph and the editor: the picture follows the panel,
                // growing when the panel widens and shrinking when it narrows.
                dragDivider(page, 300);
                assertThat(scaleOf(page)).describedAs("refits when the panel widens")
                    .isGreaterThan(fitted);
                if (screens != null) {
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of(screens, (large ? "large" : "small") + "-widened.png")));
                }
                dragDivider(page, -600);
                assertThat(scaleOf(page)).describedAs("refits when the panel narrows")
                    .isLessThan(fitted);
                if (screens != null) {
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of(screens, (large ? "large" : "small") + "-narrow.png")));
                }
                assertThat(pageErrors).isEmpty();
            }
        }
    }
}
