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
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The requirements database driven the way a PERSON drives it: typed in, edited, connected,
 * checked, retired and rolled back through the screens, never through the service.
 *
 * <p>Everything else about requirements had been proved from the other end — a document goes in and
 * requirements come out. This is the other half of the same question, and it had never been asked:
 * the operator owns this document, and most of what they do to it after the first day is by hand.
 *
 * <p>One embedded Console per JVM, as in every browser test here: zeroz4j's
 * {@code Signals.shared(...)} binds to the FIRST server engine started in a JVM, so a second one in
 * the same JVM serves pages while its signal {@code set()} never reaches the client. Surefire's
 * {@code reuseForks=false} gives this class its own JVM; a second browser test in this class would
 * silently reintroduce that. Opt-in: {@code -Dswarmcoder.browser.tests=true}.
 *
 * <p>The fixture is {@link RequirementsSandboxRunner#seed}, shared with the hand-driving harness, so
 * what a person clicks through and what these assertions clicks through are the same document.
 */
class ConsoleRequirementsByHandBrowserTest {

    @TempDir
    Path storeDir;

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void anOperatorCanRunTheRequirementsDatabaseByHand() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            RequirementsSandboxRunner.seed(store, storeDir);

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                List<String> pageErrors = new ArrayList<>();
                Page page = browser.newPage();
                page.onPageError(pageErrors::add);
                // The client logs its own failures rather than throwing them, so without this a
                // screen that quietly could not load something looks identical to one that did.
                page.onConsoleMessage(m -> System.out.println("[browser " + m.type() + "] "
                    + m.text()));
                page.setViewportSize(1600, 950);
                page.navigate("http://localhost:" + server.port() + "/");

                page.waitForSelector("[data-testid='stage-requirements']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
                page.click("[data-testid='stage-requirements']");
                page.waitForSelector("[data-row='req-R2']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- the list an operator lands on can write ------------------------------------
                // It could not. There was no way to start a requirement and no way to open one, so
                // every edit began by finding the Graph tab and nothing on this screen said so
                // (§25.2). All three row actions the design asks for are here now: open it, add a
                // requirement inside it, and see what surrounds it.
                assertThat(page.locator("[data-testid='requirements-tree']").innerText())
                    .describedAs("the surface an operator lands on offers the way in")
                    .contains("New requirement");
                assertThat(page.locator("[data-open='R2']").count()).isEqualTo(1);
                assertThat(page.locator("[data-add-part='R2']").count()).isEqualTo(1);
                assertThat(page.locator("[data-surroundings='R2']").count()).isEqualTo(1);

                // Opening a row opens the SAME editor the diagram uses, over the list.
                page.click("[data-open='R2']");
                page.waitForSelector("[data-testid='requirement-form'] [data-testid='brd-req-text']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                Dialogs.settled(page);
                assertThat(page.inputValue(
                        "dialog.modal-open [data-testid='brd-req-text']"))
                    .describedAs("the row fetched the requirement itself, not just its line")
                    .isEqualTo("A shopper can check out without creating an account");
                Dialogs.shotAtEveryWidth(page, "requirements-list-editor");
                page.keyboard().press("Escape");
                page.waitForSelector("dialog.modal-open",
                    new Page.WaitForSelectorOptions().setState(
                        com.microsoft.playwright.options.WaitForSelectorState.DETACHED)
                        .setTimeout(10_000));

                page.click("[data-testid='requirements-view-graph']");
                // The diagram's header first: the canvas sizes itself by measuring its own width, which
                // is zero while the pane is still hidden, so a node can be in the DOM and nowhere on
                // screen — and a click on it then waits for ever.
                page.waitForSelector("text=Requirements (BRD)",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.waitForSelector("[data-node='req-R2']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

                // --- a requirement a document produced says WHICH document ----------------------
                // It never did. The lookup ran inside a rendering effect, and an RMI call from there
                // is on the signal-dispatch path where TeaVM cannot suspend, so it threw every time:
                // the line read "Extracted from an uploaded document" with a red "could not be
                // loaded" warning under it, permanently, for every extracted requirement.
                // Clicking a requirement in the diagram is the ONLY way to open one, and it did not
                // work here: the canvas scales its picture by measuring its own width, which is
                // zero while this pane is hidden — and it starts hidden, because the list is the
                // default surface. So the diagram was drawn for a window of no width, and two of
                // the five requirements sat outside the pane: present to the DOM, absent to a
                // person and to a click, and therefore uneditable by any route at all.
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "requirements-by-hand-graph.png")));
                assertThat(insideItsCanvas(page, "req-R2") && insideItsCanvas(page, "req-R3"))
                    .describedAs("every requirement is inside the visible diagram, because one that "
                        + "is not cannot be opened and nothing else opens one")
                    .isTrue();
                page.click("[data-node='req-R2']");
                page.waitForSelector("text=checkout-brief.md",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("body").innerText())
                    .describedAs("naming the document is the whole point of the provenance line")
                    .contains("Extracted from checkout-brief.md")
                    .doesNotContain("could not be loaded");

                // --- writing one from nothing, every field ---------------------------------------
                page.click("[title='New requirement']");
                page.waitForSelector("[data-form='graph'] [placeholder='short title']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.fill("[data-form='graph'] [placeholder='short title']", "Refunds");
                page.fill("[data-form='graph'] [data-testid='brd-req-text']",
                    "A shopper can request a refund within 30 days of purchase");
                page.click("[data-form='graph'] [data-testid='brd-save']");
                page.waitForSelector("[data-node='req-R6']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

                // …and a QUALITY one, which is a different shape: it carries a category, and only it
                // may constrain anything. Both are one field on one type, so this is the same form.
                page.click("[title='New requirement']");
                page.waitForSelector("[data-form='graph'] [placeholder='short title']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.fill("[data-form='graph'] [placeholder='short title']", "Refunds are processed in a day");
                page.fill("[data-form='graph'] [data-testid='brd-req-text']",
                    "A refund reaches the shopper's card within 24 hours of approval");
                page.selectOption("[data-form='graph'] [data-testid='brd-kind']", "non-functional");
                // Scoped to the diagram's copy of the form: the list's copy is in the page too, with
                // the same hint hidden inside it, and an unscoped text= selector finds that one.
                page.waitForSelector("[data-form='graph'] >> text=This is a quality requirement",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.click("[data-form='graph'] [data-testid='brd-save']");
                page.waitForSelector("[data-node='req-R7']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

                // Reopening is what proves it was STORED rather than merely drawn.
                page.click("[data-node='req-R6']");
                page.waitForSelector("[data-form='graph'] [data-testid='brd-req-text']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(page.inputValue("[data-form='graph'] [placeholder='short title']")).isEqualTo("Refunds");
                assertThat(page.inputValue("[data-form='graph'] [data-testid='brd-req-text']"))
                    .isEqualTo("A shopper can request a refund within 30 days of purchase");

                // --- its checks: add, edit, change standing, remove ------------------------------
                page.fill("[data-form='graph'] [placeholder='New check — one statement you can test']",
                    "a refund inside 30 days is accepted");
                page.fill("[data-form='graph'] [placeholder='Test class or file']", "RefundTest#within30Days");
                page.click("[data-form='graph'] [data-testid='brd-add-criterion']");
                page.waitForSelector("[data-form='graph'] [placeholder='Checkable statement']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.inputValue("[data-form='graph'] [placeholder='Checkable statement']"))
                    .isEqualTo("a refund inside 30 days is accepted");
                // A check a person typed is a decision, not a proposal: it lands agreed, and it
                // carries no "nobody has confirmed this test name" note, because somebody just did.
                assertThat(page.locator("[data-form='graph'] [data-check-ref='confirmed']").count())
                    .describedAs("the operator named the test, so nothing is unconfirmed about it")
                    .isEqualTo(1);
                // Three widths, because the editor is a panel beside a diagram and the two share a
                // fixed-width split: what fits at 1600 is what gets squeezed at 960.
                // (Dialogs.shotAtEveryWidth is for dialogs — it waits for one to be open — and this
                // is a panel, so the same three shots are taken here directly.)
                for (int width : new int[] {1600, 1280, 960}) {
                    page.setViewportSize(width, 950);
                    page.waitForSelector("[data-form='graph'] [placeholder='Checkable statement']",
                        new Page.WaitForSelectorOptions().setTimeout(5_000));
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target",
                            "requirements-by-hand-editor-" + width + ".png")));
                }
                page.setViewportSize(1600, 950);

                // Editing the wording of a check saves on leaving the box, with no separate Save.
                page.fill("[data-form='graph'] [placeholder='Checkable statement']",
                    "a refund asked for inside 30 days is accepted");
                page.locator("[data-form='graph'] [placeholder='Checkable statement']").blur();
                page.waitForTimeout(500);
                page.click("[data-node='req-R1']");
                page.click("[data-node='req-R6']");
                page.waitForSelector("[data-form='graph'] [placeholder='Checkable statement']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(page.inputValue("[data-form='graph'] [placeholder='Checkable statement']"))
                    .describedAs("the edited wording survived a round trip through the store")
                    .isEqualTo("a refund asked for inside 30 days is accepted");

                // --- the three shape rules, tried through the interface --------------------------
                // Each is refused, and the refusal is under the button that was pressed. It used to
                // go to the form's status line ~400px further up, out of view on a laptop, so a
                // forbidden link looked exactly like one that had worked.
                addEdge(page, "part of", "R1 · Checkout");
                page.waitForSelector("[data-testid='brd-edge-status']",
                    new Page.WaitForSelectorOptions().setState(
                        com.microsoft.playwright.options.WaitForSelectorState.HIDDEN)
                        .setTimeout(5_000));

                addEdge(page, "part of", "R2 · Guest checkout");
                page.waitForSelector("[data-testid='brd-edge-status']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                String secondParent = page.textContent("[data-testid='brd-edge-status']");
                assertThat(secondParent)
                    .describedAs("one place in the hierarchy, said in the operator's words, naming "
                        + "both requirements and what to do instead")
                    .contains("R6").contains("already part of R1")
                    .contains("belongs in one place").contains("waits for");
                assertThat(secondParent)
                    .describedAs("and never in the wire's or the enum's vocabulary — the way out it "
                        + "offers has to name the words the picker beside it actually shows")
                    .doesNotContain("error:").doesNotContain("depends_on")
                    .doesNotContain("REFINES").doesNotContain("refines");
                assertThat(nearAddEdge(page))
                    .describedAs("the reason sits with the button that was pressed, not at the top "
                        + "of a panel the operator has scrolled away from")
                    .isTrue();
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "requirements-by-hand-edge-refused.png")));

                // A loop, from the other end: R1 made part of R6, which is already part of R1.
                page.click("[data-node='req-R1']");
                page.waitForSelector("[data-form='graph'] [data-testid='brd-req-text']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                addEdge(page, "part of", "R6 · Refunds");
                page.waitForSelector("[data-testid='brd-edge-status']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.textContent("[data-testid='brd-edge-status']"))
                    .describedAs("a loop is refused and named as a loop")
                    .contains("would create a loop").contains("R6").contains("R1");

                // Only a quality requirement may constrain another. R1 is an ordinary one.
                addEdge(page, "constrains", "R2 · Guest checkout");
                page.waitForSelector("[data-testid='brd-edge-status']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.textContent("[data-testid='brd-edge-status']"))
                    .describedAs("named in the words the Kind picker uses, since that is the "
                        + "setting the sentence tells the operator to change")
                    .contains("only a quality requirement can constrain another")
                    .contains("non-functional")
                    .doesNotContain("non_functional");

                // --- agreeing a draft, then doing the next ordinary thing -----------------------
                // R3 is a draft an agent proposed. Agreeing it leaves the form open, so the field
                // saying what the requirement IS has to catch up — and it did not: the Status
                // picker still read "draft" afterwards, so pressing Save wrote that straight back
                // and undid the decision, leaving a draft requirement carrying accepted checks.
                page.click("[data-node='req-R3']");
                page.waitForSelector("[data-form='graph'] [data-testid='brd-promote']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.click("[data-form='graph'] [data-testid='brd-promote']");
                page.waitForSelector("[data-node='req-R3'][data-state='unplanned']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.inputValue("[data-form='graph'] [data-testid='brd-status']"))
                    .describedAs("the form now says what the requirement now is")
                    .isEqualTo("agreed");
                page.click("[data-form='graph'] [data-testid='brd-save']");
                page.waitForTimeout(1000);
                assertThat(page.getAttribute("[data-node='req-R3']", "data-state"))
                    .describedAs("saving straight after agreeing must not un-agree it")
                    .isNotEqualTo("draft");

                // --- editing work a story has already claimed ------------------------------------
                // S1 claims both of R2's checks and one of them passes. Rewording R2 is allowed —
                // the pool is always open — and the evidence must stop reading green, because it
                // was gathered against wording that no longer exists.
                page.click("[data-node='req-R2']");
                page.waitForSelector("[data-form='graph'] [data-testid='brd-req-text']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.fill("[data-form='graph'] [data-testid='brd-req-text']",
                    "A shopper can check out as a guest and is never asked to create an account");
                page.click("[data-form='graph'] [data-testid='brd-save']");
                // It asks first, and says what it will cost — in the sentence the SERVER computes,
                // the same one the document-analysis wizard shows on a proposed edit. Neither
                // existed on this path: the check correctly went stale and the story card did not
                // change at all, still reading "Agreed work. Nothing is building it yet." (§25.3).
                page.waitForSelector("[data-testid='brd-edit-impact']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                Dialogs.settled(page);
                String impact = page.textContent("[data-testid='brd-edit-impact']");
                assertThat(impact)
                    .describedAs("it names the requirement, how much is agreed against it, the "
                        + "story that claimed it, and what happens to the evidence")
                    .contains("R2").contains("2 agreed checks").contains("1 story")
                    .contains("1 test that passes today will stop counting");
                assertThat(impact)
                    .describedAs("and it does NOT claim a story will be sent back: S1 has claimed "
                        + "these checks but nobody has started building it, so there is nothing "
                        + "to send back. Overstating that would be the same fault as saying "
                        + "nothing at all. RequirementEditImpactTest drives the case where a "
                        + "story really is in flight.")
                    .doesNotContain("sent back");
                Dialogs.shotAtEveryWidth(page, "requirements-edit-impact");
                page.click("[data-testid='brd-edit-impact-confirm']");
                // The state attribute, not the words in the box: this is an SVG group, and a text=
                // selector inside one matches nothing.
                page.waitForSelector("[data-node='req-R2'][data-state='stale']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[data-testid='requirements-view-list']");
                page.waitForSelector("[data-row='req-R2']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[data-chip='needs re-checking']");
                page.waitForSelector("[data-chip='needs re-checking'][data-active='true']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.waitForSelector("[data-row='req-R2']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-row='req-R6']").count())
                    .describedAs("only the reworded requirement's evidence is in question")
                    .isEqualTo(0);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "requirements-by-hand-stale-filter.png")));
                page.click("[data-chip='needs re-checking']");
                page.waitForSelector("[data-chip='needs re-checking'][data-active='false']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

                // --- rolling back --------------------------------------------------------------
                // The one thing an operator restores a revision FOR is to get the old document
                // back. It used to come back hollow: restore rebuilt every requirement through a
                // seven-field constructor, so every check in the document, every quality category,
                // and every link to the document a requirement came from were destroyed — silently,
                // on a document that then still claimed to be the old one.
                page.click("[data-testid='requirements-view-graph']");
                // The diagram's header first: the canvas sizes itself by measuring its own width, which
                // is zero while the pane is still hidden, so a node can be in the DOM and nowhere on
                // screen — and a click on it then waits for ever.
                page.waitForSelector("text=Requirements (BRD)",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.waitForSelector("[data-node='req-R2']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                // "Past versions" is above the tabs now, with "Analyse documents": a revision is a
                // fact about the document, not about the lens you are viewing it through. Pressing
                // it switches to the diagram, which is where a past revision is drawn.
                page.click("[data-testid='requirements-history']");
                page.waitForSelector("text=Back to live",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                String history = page.locator("body").innerText();
                assertThat(history)
                    .describedAs("every hand edit is in the record, attributed, with what it did")
                    .contains("added R6 (Refunds)").contains("added a check to R6")
                    .contains("edited R2");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "requirements-by-hand-history.png")));

                // The revision just before R6 was written. Restoring it must bring back a whole
                // document, not a stripped one.
                // BY NUMBER, not by label. Every row here reads "rev n · …" and carries an
                // identical restore control, so "text=rev 14" and "[title='Restore this revision']"
                // both resolve to the FIRST row — the newest revision. The first version of this
                // test restored the newest revision, which is a no-op, and every assertion below
                // passed while proving nothing.
                page.click("[data-revision='14']");
                page.waitForSelector("text=rev 14 (preview)",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-node='req-R6']").count())
                    .describedAs("the preview is the document as it was, so R6 is not in it")
                    .isEqualTo(0);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "requirements-by-hand-revision-preview.png")));

                page.click("[data-restore='14']");
                // It says what it will take out of the live document before it does it, and it says
                // that nothing is destroyed — which is true and was never said (§25.6).
                page.waitForSelector("[data-testid='brd-restore-impact']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                Dialogs.settled(page);
                String restoring = page.textContent("[data-testid='brd-restore-impact']");
                assertThat(restoring)
                    .describedAs("it names the requirements written since, and says they come back")
                    .contains("revision 14").contains("R6").contains("R7")
                    .contains("Nothing is deleted");
                Dialogs.shotAtEveryWidth(page, "requirements-restore-impact");
                page.click("[data-testid='brd-restore-impact-confirm']");
                page.waitForSelector("[data-node='req-R2']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[data-node='req-R2']");
                page.waitForSelector("[data-form='graph'] [placeholder='Checkable statement']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-form='graph'] [placeholder='Checkable statement']").count())
                    .describedAs("R2's two checks came back with it — they are the only thing that "
                        + "could ever show the requirement was met")
                    .isEqualTo(2);
                assertThat(page.locator("body").innerText())
                    .describedAs("and so did the document it was extracted from")
                    .contains("Extracted from checkout-brief.md");
                page.click("[data-testid='requirements-view-list']");
                page.waitForSelector("[data-row='req-R4']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.textContent("[data-row='req-R4']"))
                    .describedAs("a quality constraint comes back as one — restored as an ordinary "
                        + "requirement it would still hold a constraint no rule would let anyone "
                        + "draw, and the gate it imposes would have quietly stopped meaning "
                        + "anything")
                    .contains("performance").contains("constrains R1");
                assertThat(page.locator("body").innerText())
                    .describedAs("and no relation is named by its internal constant anywhere on "
                        + "the list (§25.5)")
                    .doesNotContain("refines").doesNotContain("REFINES")
                    .doesNotContain("derived from").doesNotContain("DEPENDS_ON");
                assertThat(page.textContent("[data-row='req-R2']"))
                    .describedAs("and the story that claimed its checks still finds them")
                    .contains("S1");
                // What restoring costs: everything written since that revision leaves the live
                // document. It is recoverable — history is only ever appended to and restoring a
                // later revision brings it back — and the operator is now told BOTH halves before
                // it happens, which is what the dialog above asserted.
                assertThat(page.locator("[data-row='req-R6']").count()
                        + page.locator("[data-row='req-R7']").count())
                    .describedAs("restoring takes the document back whole, later work included")
                    .isEqualTo(0);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "requirements-by-hand-restored.png")));

                // --- taking something out of scope ----------------------------------------------
                // "Delete" is gone. Nothing in the requirements can be destroyed: this marks R5 out
                // of scope, tells the operator what that costs and that nothing is lost, takes it
                // out of the list and out of every count, and leaves it one click away.
                page.click("[data-open='R5']");
                page.waitForSelector("[data-form='list'] [data-testid='brd-retire']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                Dialogs.settled(page);
                assertThat(page.locator("[data-form='list']").innerText())
                    .describedAs("the control says what it does — it used to say Delete, and it "
                        + "used to mean it")
                    .contains("Take out of scope").doesNotContain("Delete");
                page.click("[data-form='list'] [data-testid='brd-retire']");
                page.waitForSelector("[data-testid='brd-retire-impact']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                Dialogs.settled(page);
                assertThat(page.textContent("[data-testid='brd-retire-impact']"))
                    .describedAs("it says plainly that nothing is destroyed, and how to undo it")
                    .contains("R5").contains("Nothing is deleted")
                    .contains("bring it back by setting it back to a draft");
                Dialogs.shotAtEveryWidth(page, "requirements-retire-impact");
                page.click("[data-testid='brd-retire-impact-confirm']");

                page.waitForSelector("[data-testid='requirements-retired']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-row='req-R5']").count())
                    .describedAs("it has left the list an operator works from")
                    .isEqualTo(0);
                assertThat(page.textContent("[data-testid='requirements-retired']"))
                    .describedAs("counted, not vanished — and openable, which is UX v3 rule 1")
                    .contains("1 out of scope").contains("show them");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "requirements-retired-count.png")));
                page.click("[data-testid='requirements-retired']");
                page.waitForSelector("[data-row='req-R5']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.textContent("[data-row='req-R5']"))
                    .describedAs("and there it is, marked for what it is")
                    .contains("retired");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "requirements-retired-shown.png")));

                assertThat(pageErrors).isEmpty();
            }
        }
    }

    /** Picks a relation and a target in the edges panel and presses the button. */
    private static void addEdge(Page page, String relation, String target) {
        page.selectOption("[data-testid='brd-edge-relation']", relation);
        page.selectOption("[data-testid='brd-edge-target']", target);
        page.click("[data-testid='brd-edge-add']");
    }

    /**
     * Whether a requirement's box lies inside the diagram it is drawn on.
     *
     * <p>Measured against the node's OWN owner {@code svg}: a bare {@code "svg"} selector matches
     * the first icon on the page, which is 32px wide, and would pass for the wrong reason.
     */
    private static boolean insideItsCanvas(Page page, String node) {
        Object outside = page.evalOnSelector("[data-node='" + node + "']",
            "el => { const svg = el.ownerSVGElement;"
            + " const s = svg.getBoundingClientRect();"
            + " const n = el.getBoundingClientRect();"
            + " return n.right > s.right + 1 || n.left < s.left - 1"
            + " || n.bottom > s.bottom + 1 || n.top < s.top - 1; }");
        return Boolean.FALSE.equals(outside);
    }

    /**
     * Whether the refusal is next to the button rather than somewhere else on the panel. A message
     * that is correct and off screen is the defect this is about, and no text assertion can see it.
     */
    private static boolean nearAddEdge(Page page) {
        Object gap = page.evaluate(
            "() => {"
            + "  const status = document.querySelector(\"[data-testid='brd-edge-status']\");"
            + "  const button = document.querySelector(\"[data-testid='brd-edge-add']\");"
            + "  if (!status || !button) return 9999;"
            + "  const s = status.getBoundingClientRect();"
            + "  const b = button.getBoundingClientRect();"
            // Both must be ON the screen, not merely near each other: this panel is the last thing
            // in a scrolling pane, and a message directly under a button that is itself on the
            // bottom edge of the window is exactly as unread as one 400px away.
            + "  if (s.top < 0 || s.bottom > window.innerHeight) return 9999;"
            + "  return Math.abs(s.bottom - b.top);"
            + "}");
        return Double.parseDouble(String.valueOf(gap)) < 60;
    }
}
