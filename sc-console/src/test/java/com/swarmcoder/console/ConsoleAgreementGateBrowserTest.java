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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the agreement gate LOOKS like — the one thing about it nobody had ever seen.
 *
 * <p>The rule itself (nothing becomes agreed scope without a check that names a test) is asserted
 * from every direction in {@code AgreementGateTest}, and the sentence it refuses with is written
 * once, in {@code AgreementGate}. What no test and no person had looked at is the screen: a button
 * greyed out with a paragraph of warning-coloured text underneath it, inside an editor that also
 * has to hold a title, a statement, four pickers and a list of checks. Whether that reads as a
 * condition somebody can go and satisfy, or as a wall of orange, is a fact about pixels.
 *
 * <p>Both refusals are photographed, because they are different lengths and the longer one is the
 * one that will overflow: "no check at all" and "checks, but none of them names a test".
 *
 * <p>One embedded Console per JVM, as in every browser test here: zeroz4j's
 * {@code Signals.shared(...)} binds to the FIRST server engine started in a JVM, so a second one in
 * the same JVM serves pages while its signal {@code set()} never reaches the client. Surefire's
 * {@code reuseForks=false} gives this class its own JVM.
 */
class ConsoleAgreementGateBrowserTest {

    @TempDir
    Path storeDir;

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void theAgreementGateExplainsItselfWhereTheOperatorIsStanding() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            seed(store, storeDir);

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                List<String> pageErrors = new ArrayList<>();
                Page page = browser.newPage();
                page.onPageError(pageErrors::add);
                page.onConsoleMessage(m -> System.out.println("[browser " + m.type() + "] "
                    + m.text()));
                page.setViewportSize(1600, 950);
                page.navigate("http://localhost:" + server.port() + "/");

                page.waitForSelector("[data-testid='stage-requirements']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
                page.click("[data-testid='stage-requirements']");
                page.waitForSelector("[data-row='req-R1']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- a draft with nothing that could ever prove it --------------------------------
                page.click("[data-open='R1']");
                page.waitForSelector("[data-form='list'] [data-testid='brd-agree-blocked']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                Dialogs.settled(page);
                assertThat(page.textContent("[data-form='list'] [data-testid='brd-agree-blocked']"))
                    .describedAs("the reason is the server's own sentence, not a second wording")
                    .contains("R1 has no check on it")
                    .contains("Add at least one check");
                assertThat(page.isEnabled("[data-form='list'] [data-testid='brd-promote']"))
                    .describedAs("greyed out rather than hidden — a button that disappears looks "
                        + "like a feature that is not there")
                    .isFalse();
                assertThat(page.isVisible("[data-form='list'] [data-testid='brd-promote']")).isTrue();
                // Three widths, and each one is checked as well as photographed: the reason sits
                // directly under the button it explains, and neither of them leaves the dialog.
                Dialogs.shotAtEveryWidth(page, "agreement-gate-no-check",
                    () -> assertReasonIsUnderTheButton(page));
                page.keyboard().press("Escape");
                page.waitForSelector("dialog.modal-open",
                    new Page.WaitForSelectorOptions().setState(
                        com.microsoft.playwright.options.WaitForSelectorState.DETACHED)
                        .setTimeout(10_000));

                // --- a draft with checks, none of which names a test ------------------------------
                page.click("[data-open='R2']");
                page.waitForSelector("[data-form='list'] [data-testid='brd-agree-blocked']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                Dialogs.settled(page);
                assertThat(page.textContent("[data-form='list'] [data-testid='brd-agree-blocked']"))
                    .describedAs("the longer of the two refusals, and the one that says what to fix")
                    .contains("R2 has 2 checks but none of them names a test")
                    .contains("Fill in the test name on at least one check");
                assertThat(page.isEnabled("[data-form='list'] [data-testid='brd-promote']")).isFalse();
                Dialogs.shotAtEveryWidth(page, "agreement-gate-no-test",
                    () -> assertReasonIsUnderTheButton(page));

                // --- and the fix is right there ---------------------------------------------------
                // The refusal points at the checks panel, which is the next thing down the same
                // dialog. Naming a test on one check is enough, and the button comes alive.
                page.fill("dialog.modal-open [placeholder='Test class or file']",
                    "GuestCheckoutTest#emptyBasket");
                page.locator("dialog.modal-open [placeholder='Test class or file']").first().blur();
                page.waitForSelector("[data-form='list'] [data-testid='brd-promote']:not([disabled])",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.textContent("[data-form='list'] [data-testid='brd-agree-blocked']"))
                    .describedAs("and the reason goes when the reason is gone")
                    .isEmpty();

                assertThat(pageErrors).isEmpty();
            }
        }
    }

    /**
     * The refusal must sit under the button it is about, and both must be inside the dialog.
     *
     * <p>A correct sentence that has been pushed off the bottom of a narrow window is the defect
     * this is looking for, and no text assertion can see it.
     */
    private static void assertReasonIsUnderTheButton(Page page) {
        Object measured = page.evaluate(
            "() => {"
            + "  const b = document.querySelector("
            + "     \"[data-form='list'] [data-testid='brd-promote']\");"
            + "  const r = document.querySelector("
            + "     \"[data-form='list'] [data-testid='brd-agree-blocked']\");"
            + "  if (!b || !r) return 'one of them is not on the page';"
            + "  const bb = b.getBoundingClientRect();"
            + "  const rr = r.getBoundingClientRect();"
            + "  if (rr.top < bb.top) return 'the reason is above the button';"
            + "  if (rr.top - bb.bottom > 80) return 'the reason is ' "
            + "     + Math.round(rr.top - bb.bottom) + 'px below the button';"
            + "  const box = r.closest('.modal-box');"
            + "  if (box) {"
            + "    const m = box.getBoundingClientRect();"
            + "    if (rr.right > m.right + 1 || rr.left < m.left - 1)"
            + "      return 'the reason sticks out of the dialog';"
            + "  }"
            + "  return '';"
            + "}");
        assertThat(String.valueOf(measured))
            .describedAs("the reason a requirement cannot be agreed belongs with the button that "
                + "is greyed out, at every window width")
            .isEmpty();
    }

    /** Two drafts, each blocked by the gate for a different one of its two reasons. */
    private static void seed(ArtifactStore store, Path dir) {
        UUID projectId = UUID.randomUUID();
        Project project = new Project(projectId, "storefront", dir.toString(), List.of(),
            Instant.now(), false);
        store.saveProject(project);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
            .withProjects(() -> List.of(project), () -> projectId, (n, p, c) -> null, id -> { }));

        BrdAuthoring.addRequirement(store, projectId, "Checkout",
            "A shopper can pay for what is in their basket", "HIGH", null, null, null, null);
        BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
            "A shopper can check out without creating an account", "HIGH", null, null, null, null);
        // Two checks with no test named: the second refusal, and the one whose sentence is longest.
        BrdAuthoring.addCriterion(store, projectId, "R2", "an empty basket is refused", null);
        BrdAuthoring.addCriterion(store, projectId, "R2", "a guest order is confirmed on screen",
            null);
    }
}
