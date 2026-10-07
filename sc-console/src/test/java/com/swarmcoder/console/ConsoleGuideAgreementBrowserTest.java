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
import com.microsoft.playwright.Locator;
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
 * The guide's agreement gate, on the shape the author actually met: seven requirements, two or
 * three checks inside each, and nothing planned yet.
 *
 * <h2>The two things this pins, both reported after a real run</h2>
 *
 * <p><b>1. A requirement can be read where it is agreed.</b> The step calls itself the gate that
 * matters most and used to show, per requirement, a title, a count of checks and an Agree button —
 * and then said in prose that to read one in full you should open the Requirements workspace. That
 * sentence was not a link and that screen is not in this window, so agreeing seven requirements
 * meant agreeing seven statements and eighteen checks nobody had been shown. Every row now opens
 * in place, and this walks that: the wording and the checks appear, the guide is still the open
 * window while they are read, and folding the row puts them away again.
 *
 * <p><b>2. The last two steps offer what is actually available.</b> Step five used to end in
 * "Next: build one" in every state — a button that builds nothing (it advances to a step which then
 * explains that a build starts from a story's card), offered when there was nothing planned to
 * build, and coloured as the main action beside a second one that was also coloured as the main
 * action. Both states without stories are walked here: nothing agreed, and seven agreed with
 * nothing planned. The third state — stories on the board, where moving on is legitimate — is
 * pinned in {@link ConsoleOnboardingBrowserTest}, whose fixture has one.
 *
 * <p><b>No model is called and none is needed.</b> The requirements are written straight into the
 * store, and the intake flow is seeded as already applied, so no step here reaches an analyst.
 *
 * <p>One embedded Console per JVM — this class holds exactly one browser test for that reason, not
 * as a style preference. zeroz4j's {@code Signals.shared(...)} binds to the first server engine
 * started in a JVM, so a second one started later serves pages but its signal {@code set()} never
 * reaches the browser. Surefire's {@code reuseForks=false} gives every browser test class a fresh
 * JVM; adding a second browser test to this class would silently break it.
 */
class ConsoleGuideAgreementBrowserTest {

    /** What the first row says once it is opened — none of it is on the collapsed row. */
    private static final String FIRST_STATEMENT =
        "A shopper can pay for a basket without ever making an account first.";
    private static final String FIRST_CHECK =
        "an empty basket is refused before the payment page";
    private static final String SECOND_CHECK =
        "a shopper who never signed in sees the order number on screen";

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void theGateShowsWhatIsBeingAgreedAndOffersOnlyWhatIsAvailable(@TempDir Path dir)
        throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            UUID projectId = seedSevenDrafts(store, dir);
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

                page.click("[data-testid='open-guide']");
                page.waitForSelector("[data-testid='onboarding-wizard'].modal-open",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("text=I have written down what I want built");
                page.waitForSelector("text=Add what you have written down.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=SwarmCoder is reading what you gave it.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=Agree what it found.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- step 4, closed: seven lines, and the buttons are still reachable -----------
                assertThat(page.locator("[data-testid='guide-read-requirement']").count())
                    .describedAs("every requirement waiting on the operator carries its own way "
                        + "to read it — the list is the gate, so the words have to be reachable "
                        + "from the gate")
                    .isEqualTo(7);
                assertThat(page.locator("[data-testid='guide-requirement-text']").count())
                    .describedAs("and none of them is open to start with: seven requirements "
                        + "unfolded at once would push the buttons off the bottom and turn the "
                        + "step into a document")
                    .isZero();
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("the old screen promised a way to read one and did not give it: "
                        + "the sentence naming another workspace was plain prose, not a link")
                    .doesNotContain("open the Requirements workspace");
                OperatorWords.assertNoneOnScreen(page, "the guide, agreeing, all folded");
                Dialogs.shotAtEveryWidth(page, "console-guide-agree-folded", () -> {
                    Dialogs.assertNothingOverflows(page,
                        "[data-testid='onboarding-wizard'] .modal-box",
                        "the agreement step with seven requirements");
                    assertThat(page.locator("[data-testid='guide-next']").isVisible())
                        .describedAs("the button that carries the walk forward is on the screen "
                            + "at every window width, with seven rows above it")
                        .isTrue();
                });

                // --- step 4, one row open: the words, and the checks, without leaving ------------
                // Addressed by the title on the row rather than by position: which row comes first
                // is the server's ordering, and a test that silently reads a different requirement
                // than the one it names proves nothing.
                Locator guestRow = page.locator("[data-testid='guide-draft-row']")
                    .filter(new Locator.FilterOptions().setHasText("Guest checkout"));
                guestRow.locator("[data-testid='guide-read-requirement']").click();
                page.waitForSelector("[data-testid='guide-requirement-text']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                String opened = page.locator("[data-testid='onboarding-wizard']").innerText();
                assertThat(opened)
                    .describedAs("what the requirement actually says is on the screen that agrees "
                        + "it — this is the whole defect: the author agreed seven of these having "
                        + "been shown nothing but their titles")
                    .contains(FIRST_STATEMENT);
                assertThat(opened)
                    .describedAs("and its checks with it. Agreeing a requirement agrees its "
                        + "checks, which the step says out loud, so the checks are part of what "
                        + "is being agreed and cannot be shown only as a number")
                    .contains(FIRST_CHECK)
                    .contains(SECOND_CHECK);
                assertThat(page.locator("[data-testid='onboarding-wizard'].modal-open").count())
                    .describedAs("read WHERE it is agreed: the guide is still the open window, so "
                        + "nobody has lost their place in the walk")
                    .isEqualTo(1);
                assertThat(page.locator("[data-testid='guide-requirement-text']").count())
                    .describedAs("opening one opens one — the other six stay as lines")
                    .isEqualTo(1);
                OperatorWords.assertNoneOnScreen(page, "the guide, agreeing, one open");
                Dialogs.shotAtEveryWidth(page, "console-guide-agree-open", () -> {
                    Dialogs.assertNothingOverflows(page,
                        "[data-testid='onboarding-wizard'] .modal-box",
                        "the agreement step with a requirement open");
                    assertThat(page.locator("[data-testid='guide-next']").isVisible())
                        .describedAs("and the way forward is still reachable with a requirement "
                            + "unfolded, at every window width")
                        .isTrue();
                });

                guestRow.locator("[data-testid='guide-read-requirement']").click();
                page.waitForFunction(
                    "() => document.querySelectorAll("
                    + "'[data-testid=\"guide-requirement-text\"]').length === 0",
                    null, new Page.WaitForFunctionOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("folding it away puts the words away again, so the list stays a "
                        + "list")
                    .doesNotContain(FIRST_STATEMENT);

                // --- step 5 with nothing agreed: the way on is the step before ------------------
                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=Turn the agreed requirements into slices of work.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='onboarding-wizard'] .btn-primary").count())
                    .describedAs("one main action on the step, whatever state it is in (UX v3 "
                        + "§3.1)")
                    .isEqualTo(1);
                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=Agree what it found.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("with nothing agreed there is nothing to plan and nothing to "
                        + "build, so the one thing offered is the step that agrees something. It "
                        + "used to offer \"Next: build one\" here, most prominently, and land the "
                        + "operator on a step about building with nothing to build")
                    .contains("Agree what it found.");

                // --- agree all seven, then step 5 with nothing planned --------------------------
                page.click("[data-testid='onboarding-wizard'] >> text=\"Agree all 7\"");
                page.waitForSelector("text=Nothing is waiting on you.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                page.click("[data-testid='guide-next']");
                page.waitForSelector("text=Turn the agreed requirements into slices of work.",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                assertThat(page.locator("[data-testid='onboarding-wizard'] .btn-primary").count())
                    .describedAs("with work to plan and none planned there were TWO buttons "
                        + "coloured as the main action, and the one that did nothing useful sat "
                        + "rightmost, where the eye lands")
                    .isEqualTo(1);
                assertThat(page.locator("[data-testid='guide-next']").innerText())
                    .describedAs("and the one that is left is planning, which is the only thing "
                        + "genuinely available with nothing proposed yet")
                    .contains("Plan the work");
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("nothing on the step offers to build while there is nothing to "
                        + "build")
                    .doesNotContain("build one");
                Dialogs.shotAtEveryWidth(page, "console-guide-stories-none-planned", () ->
                    Dialogs.assertNothingOverflows(page,
                        "[data-testid='onboarding-wizard'] .modal-box",
                        "the planning step with nothing planned"));

                // The one action offered is the one that works: it opens the planner and the walk
                // comes back on the same step when that window closes.
                page.click("[data-testid='guide-next']");
                page.waitForSelector("[data-testid='guide-planning-wizard'].modal-open",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                page.keyboard().press("Escape");
                page.waitForSelector("[data-testid='onboarding-wizard'].modal-open",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .contains("Turn the agreed requirements into slices of work.");
            }
        } finally {
            ConsoleContext.set(null);
        }
    }

    /**
     * Seven requirements nobody has agreed, two or three checks in each, and no stories.
     *
     * <p>The author's real shape. {@code BrdAuthoring.addRequirement} writes a draft and
     * {@code addCriterion} attaches a check that is only proposed, which is exactly what an intake
     * leaves behind, so nothing here has to fix up a status afterwards.
     */
    private static UUID seedSevenDrafts(ArtifactStore store, Path dir) {
        UUID projectId = UUID.randomUUID();
        Project project = new Project(projectId, "storefront", dir.toString(), List.of(),
            Instant.now(), false);
        store.saveProject(project);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
            .withProjects(() -> List.of(project), () -> projectId, (n, p, c) -> null, id -> { }));

        add(store, projectId, "R1", "Guest checkout", FIRST_STATEMENT,
            FIRST_CHECK, SECOND_CHECK, null);
        add(store, projectId, "R2", "Saved cards",
            "A shopper who has paid before can pay again with a card they kept.",
            "a kept card can be picked at the payment page",
            "a kept card can be thrown away",
            "paying with a kept card still asks for the three digits on the back");
        add(store, projectId, "R3", "Basket totals",
            "The basket shows the price, the postage and the tax as three separate numbers.",
            "postage is shown before the shopper is asked to pay",
            "the tax line matches the country the parcel is going to", null);
        add(store, projectId, "R4", "Order email",
            "Every order sends one email that says what was bought and where it is going.",
            "the email arrives within a minute of paying",
            "the email lists every line of the order", null);
        add(store, projectId, "R5", "Refunds",
            "A shop worker can send a shopper their money back, in full or in part.",
            "a full refund puts the whole amount back",
            "a part refund cannot exceed what was paid",
            "every refund is written down with who did it");
        add(store, projectId, "R6", "Stock holds",
            "Anything in a basket is held for fifteen minutes so two shoppers cannot buy the last "
                + "one.",
            "the last item cannot be sold twice",
            "a hold is let go when the basket is abandoned", null);
        add(store, projectId, "R7", "Payment record",
            "Every attempt to pay is written down, whether it worked or not.",
            "a failed payment is written down with the reason it failed",
            "the record cannot be changed afterwards", null);
        return projectId;
    }

    /**
     * One requirement and its checks. A null check is simply not added.
     *
     * <p>The handle is passed in rather than read back: {@code addRequirement} returns a sentence
     * for an agent to read ("R1 added as a draft — it still needs …"), not the handle, and
     * handles are assigned R1, R2, … in the order they are added.
     */
    private static void add(ArtifactStore store, UUID projectId, String handle, String title,
                            String text, String first, String second, String third) {
        BrdAuthoring.addRequirement(store, projectId, title, text, "MEDIUM",
            null, null, null, null);
        int number = 0;
        for (String check : new String[] {first, second, third}) {
            if (check != null) {
                number++;
                // Every check names a test it could be proved by. Not decoration: a requirement
                // whose checks name no test is REFUSED at the agreement gate (AgreementGate,
                // DEVELOPER_CORRECTIONS §20.2), so a fixture without these cannot be agreed at all
                // and the walk stops on the step this test is about.
                BrdAuthoring.addCriterion(store, projectId, handle, check,
                    handle + "Test#check" + number);
            }
        }
    }
}
