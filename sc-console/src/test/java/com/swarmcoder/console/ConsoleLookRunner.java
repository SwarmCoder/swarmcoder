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
import com.swarmcoder.domain.FlowDocument;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowKind;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * NOT A TEST. The camera.
 *
 * <p>It boots the Console on the requirements fixture, walks to each screen that has been changed,
 * and photographs it at 1600, 1280 and 960 — the three widths {@link Dialogs#shotAtEveryWidth}
 * settled on, because a panel that is right at 1600 can push its buttons off the bottom at 960 and
 * nothing but looking will say so. Nothing here asserts anything: the assertions live in the browser
 * tests, and this exists so that a person (or an agent) can see what those tests are passing over.
 *
 * <p>It also photographs the dark theme <b>as it was</b>. {@link #OLD_DARK} restores daisyUI's own
 * three base colours and the page background the Console used to carry, so the before and the after
 * are the same screen, the same data and the same window, differing only in the thing that changed.
 * A before-and-after made from two different runs proves nothing.
 *
 * <p>Deliberately named so surefire's default includes never pick it up. Run it explicitly:
 * {@code mvn -o -pl sc-console -am test -Dtest=ConsoleLookRunner -Dswarmcoder.shots=true}, and the
 * pictures land in {@code sc-console/target/}.
 */
class ConsoleLookRunner {

    /**
     * The dark theme exactly as it was before 2026-08-29: daisyUI 5's stock ramp, two points of
     * lightness per step, with the page painted in the same token as the cards on it.
     */
    private static final String OLD_DARK =
        "[data-theme=\"dark\"]{"
        + "--color-base-100:oklch(25.33% 0.016 252.42);"
        + "--color-base-200:oklch(23.26% 0.014 253.1);"
        + "--color-base-300:oklch(21.15% 0.012 254.09);}"
        + "body{background-color:var(--color-base-100)!important;}"
        + "[data-testid=\"status-header\"]{background-color:var(--color-base-200)!important;}";

    private static final int[] WIDTHS = {1600, 1280, 960};

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @EnabledIfSystemProperty(named = "swarmcoder.shots", matches = "true",
        disabledReason = "screenshot harness; enable with -Dswarmcoder.shots=true")
    void photographEveryScreenThatChanged() throws Exception {
        Path dir = Files.createTempDirectory("sc-console-look");
        try (ArtifactStore store = new ArtifactStore(dir)) {
            UUID projectId = RequirementsSandboxRunner.seed(store, dir);
            seedAppliedIntake(store, projectId);

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Console Look");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                String home = "http://localhost:" + server.port() + "/";
                Page page = browser.newPage();
                page.onPageError(e -> System.out.println("[page error] " + e));
                page.setViewportSize(1600, 950);
                page.navigate(home);
                page.waitForSelector("[data-testid='status-header']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));

                // --- the two screens the author named, before and after --------------------------
                page.click("[data-testid='stage-plan']");
                page.waitForSelector("text=Every story from suggestion to delivery",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                shots(page, "look-pipeline");
                withOldTheme(page, () -> shots(page, "look-pipeline-BEFORE"));

                page.click("[data-testid='stage-requirements']");
                page.waitForSelector("[data-testid='requirements-tree']",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                shots(page, "look-requirements");
                withOldTheme(page, () -> shots(page, "look-requirements-BEFORE"));

                // --- the guide, step by step -----------------------------------------------------
                page.click("[data-testid='open-guide']");
                page.waitForSelector("[data-testid='onboarding-wizard'].modal-open",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                shots(page, "look-guide-1-where");

                page.click("text=I have written down what I want built");
                page.waitForSelector("text=Add what you have written down.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                shots(page, "look-guide-2-documents");

                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=SwarmCoder is reading what you gave it.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                shots(page, "look-guide-3-reading");

                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=Agree what it found.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                shots(page, "look-guide-4-agree");

                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=Turn the agreed requirements into slices of work.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                shots(page, "look-guide-5-stories");

                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=Build one, and judge what comes back.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                shots(page, "look-guide-6-build");

                System.out.println("PICTURES IN " + Path.of("target").toAbsolutePath());
            }
        } finally {
            ConsoleContext.set(null);
        }
    }

    /**
     * An intake that has already been applied, so the steps after it can be reached.
     *
     * <p>Shared with {@link ConsoleOnboardingBrowserTest}, so what a person looks at and what the
     * assertions walk are the same project. Reaching these steps for real needs a model endpoint,
     * and neither a camera nor a test can depend on one.
     */
    static void seedAppliedIntake(ArtifactStore store, UUID projectId) {
        SourceDocument brief = new SourceDocument(UUID.randomUUID(), projectId,
            "payments-brief.md", "text/markdown", "cafebabe",
            "Shoppers must be able to pay with a card they saved earlier.", "passthrough",
            2048L, Instant.now());
        store.saveSourceDocument(brief);
        store.saveGuidedFlow(new GuidedFlow(UUID.randomUUID(), projectId, GuidedFlowKind.REQUIREMENTS_INTAKE,
            GuidedFlowState.APPLIED, 4, 4, "written into the requirements", null,
            // Marked already analysed, which is what an APPLIED flow really holds.
            List.of(new FlowDocument(brief.id(), "authoritative for payments",
                Instant.now(), false)),
            Instant.now(), Instant.now()));
    }

    /** Three pictures, three widths, and the viewport put back. */
    private static void shots(Page page, String name) {
        for (int width : WIDTHS) {
            page.setViewportSize(width, 950);
            if (page.locator("dialog.modal.modal-open").count() > 0) {
                Dialogs.settled(page);
            }
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("target", name + "-" + width + ".png")));
        }
        page.setViewportSize(1600, 950);
    }

    /** Runs something with the old dark theme in force, and takes it back off afterwards. */
    private static void withOldTheme(Page page, Runnable shoot) {
        page.evaluate("(css) => {"
            + "  const s = document.createElement('style');"
            + "  s.id = 'old-dark';"
            + "  s.textContent = css;"
            + "  document.head.appendChild(s);"
            + "}", OLD_DARK);
        shoot.run();
        page.evaluate("() => { const s = document.getElementById('old-dark'); if (s) s.remove(); }");
    }
}
