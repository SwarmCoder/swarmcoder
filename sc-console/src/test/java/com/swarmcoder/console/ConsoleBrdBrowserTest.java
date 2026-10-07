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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One embedded Console per JVM — this test lives in its own class for that reason, not as a
 * style preference. zeroz4j's {@code Signals.shared(...)} binds to the first server engine
 * started in a JVM, so a second {@code Zeroz4jServer} started later in the same JVM serves
 * pages but its server-side signal {@code set()} never reaches the browser client, and any
 * signal-driven assertion times out. Surefire's {@code reuseForks=false} (see sc-console/pom.xml)
 * gives every browser test class a fresh JVM; adding a second browser test to this class would
 * silently reintroduce the defect.
 *
 * <p>Focused proof that the BRD editor renders and edits end-to-end. Boots the Console, opens
 * Requirements, verifies the seeded requirement graph (two nodes + a typed edge) draws on the
 * SvgCanvas, and that adding a requirement persists over RMI and produces a new node.
 * Runs in an ordinary build wherever Playwright's Chromium is installed — no flag. On a machine without it, it is skipped and the skip is announced (see {@code @RunsWhen}).
 */
class ConsoleBrdBrowserTest {

    @TempDir
    Path storeDir;

    /** Attaches one ACCEPTED check in the given state to the requirement with that handle. */
    private static void accepted(Brd brd, String handle,
                                 com.swarmcoder.domain.CriterionState state) {
        accepted(brd, handle, state, handle + "Test#check",
            com.swarmcoder.domain.TestRefOrigin.OPERATOR);
    }

    /**
     * The same, naming the test and saying who named it. A test name the intake wizard proposed and
     * one a person typed look identical in the store and must not look identical on screen, so the
     * fixture has to be able to seed both.
     */
    private static void accepted(Brd brd, String handle,
                                 com.swarmcoder.domain.CriterionState state,
                                 String testName,
                                 com.swarmcoder.domain.TestRefOrigin origin) {
        for (BrdRequirement r : brd.requirements()) {
            if (handle.equals(r.handle())) {
                var criterion = new com.swarmcoder.domain.AcceptanceCriterion(
                    UUID.randomUUID(), "check for " + handle, testName);
                criterion.setTestRefOrigin(origin);
                criterion.setStatus(com.swarmcoder.domain.CriterionStatus.ACCEPTED);
                criterion.setVerification(state);
                // criteria() is NULL-SAFE, which means it hands back an immutable empty list when the
                // field was never set — and the 7-arg constructor above never sets it. Adding straight
                // to the accessor's return throws; a fresh mutable list has to be installed first.
                r.setCriteria(new java.util.ArrayList<>(r.criteria()));
                r.criteria().add(criterion);
                return;
            }
        }
        throw new IllegalArgumentException("no such requirement: " + handle);
    }

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        // The Console's RMI services are @Secured; the client authenticates as the dev admin.
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void brdEditorRendersAndEdits() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = UUID.randomUUID();
            List<Project> projects = new ArrayList<>();
            projects.add(new Project(projectId, "alpha", "C:/work/alpha", List.of(),
                Instant.now(), false));
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> projects, () -> projectId, (n, p, c) -> null, id -> { }));
            store.append(() -> {
                UUID rq1 = UUID.randomUUID();
                UUID rq2 = UUID.randomUUID();
                UUID rq3 = UUID.randomUUID();
                UUID rq4 = UUID.randomUUID();
                var brd = new Brd(UUID.randomUUID(), projectId, 1, "Business Requirements",
                    new ArrayList<>(List.of(
                        new BrdRequirement(rq1, "R1", "Login",
                            "Users can log in with email and password",
                            Priority.HIGH, RequirementStatus.ACTIVE, "Auth"),
                        new BrdRequirement(rq2, "R2", "OAuth", "Users can log in with Google",
                            Priority.MEDIUM, RequirementStatus.DRAFT, "Auth"),
                        // R3 is deliberately MALFORMED: it claims to be part of both R1 and R2.
                        // Nothing forbade that before the single-parent rule, so a real store can
                        // hold it, and the editor has to load anyway and say what is wrong.
                        new BrdRequirement(rq3, "R3", "Token refresh", "Sessions refresh silently",
                            Priority.LOW, RequirementStatus.DRAFT, "Auth"),
                        // Connected to nothing. Without an unrelated requirement in the fixture,
                        // "the diagram is scoped" cannot be asserted at all - every seeded node was
                        // reachable from every other, so a scope that excluded nothing would pass.
                        new BrdRequirement(rq4, "R4", "Audit log", "Every login is recorded",
                            Priority.LOW, RequirementStatus.DRAFT, null))),
                    new ArrayList<>(List.of(
                        new BrdEdge(rq2, rq1, RequirementRelation.REFINES),
                        new BrdEdge(rq3, rq2, RequirementRelation.REFINES),
                        new BrdEdge(rq3, rq1, RequirementRelation.REFINES),
                        // Non-hierarchy relations, so the row badges and the "no internal names"
                        // assertion below have something to be about. Before these, that assertion
                        // was true because nothing rendered a relation at all.
                        //
                        // Both hang off R2, not R3, deliberately: R4 has to stay OUTSIDE R3's
                        // neighbourhood for the scoped-graph assertion to mean anything. Hanging a
                        // conflict off R3 pulled R4 into scope and failed that assertion — which was
                        // the scoping working, not breaking.
                        new BrdEdge(rq2, rq4, RequirementRelation.DEPENDS_ON),
                        new BrdEdge(rq2, rq4, RequirementRelation.CONFLICTS_WITH))),
                    Instant.now(), Instant.now());
                // Checks on the CHILDREN only, so the coarse requirement has none of its own and the
                // screen has to distinguish "nothing verifies R1" from "2 of 3 verified inside it"
                // (UX v3 rule 4). Without these the coverage column was never exercised at all.
                // R2's two checks differ in ONE way that matters to the screen: the first names a
                // test the intake wizard guessed at and nobody has confirmed, the second names one
                // a person typed. The editor has to say so on the first and stay silent on the
                // second, and until this fixture existed no test had ever put either on a screen.
                accepted(brd, "R2", com.swarmcoder.domain.CriterionState.PASSING,
                    "OAuthLoginTest#googleSignInSucceeds",
                    com.swarmcoder.domain.TestRefOrigin.PROPOSED);
                accepted(brd, "R2", com.swarmcoder.domain.CriterionState.FAILING,
                    // Deliberately free of the word the search assertion below looks for: the tree
                    // searches check text and test names too, so a test called ...token...
                    // would be a second match for "Token" and break an unrelated assertion.
                    "OAuthLoginTest#sessionIsStored",
                    com.swarmcoder.domain.TestRefOrigin.OPERATOR);
                accepted(brd, "R3", com.swarmcoder.domain.CriterionState.PASSING);
                store.root().brds().put(projectId, brd);
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

                // The shell mounts once RMI is live (the project rail is not required here). The
                // BRD editor is the Requirements STAGE now, not a sidebar entry — selecting it is
                // what puts the editor in the workspace.
                page.waitForSelector("[data-testid='stage-requirements']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
                page.click("[data-testid='stage-requirements']");
                // --- the LIST is what the operator lands on -----------------------------------
                // A canvas is the right tool for the relationships around one requirement and the
                // wrong one for finding a requirement among hundreds, so the tree is the default and
                // the graph is a choice (REQUIREMENTS_AT_SCALE_DESIGN §3.3).
                page.waitForSelector("[data-testid='requirements-tree']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.waitForSelector("[data-row='req-R1']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("body").innerText())
                    .contains("Login").contains("OAuth").contains("Token refresh");
                // Indented by what contains what: R2 is part of R1, so it sits one level in. The style
                // attribute is the indent, and reading it proves the hierarchy reached the client.
                assertThat(page.getAttribute("[data-row='req-R2'] > div:first-child", "style"))
                    .describedAs("a child row is indented from its parent")
                    .contains("18px");
                assertThat(page.getAttribute("[data-row='req-R1'] > div:first-child", "style"))
                    .describedAs("a top-level row is not")
                    .contains("0px");

                // --- relations read as English, and no internal name reaches the screen -----------
                assertThat(page.textContent("[data-relations='R2']"))
                    .describedAs("a dependency and a conflict read as what they mean to a person")
                    .contains("waits for R4").contains("conflicts with R4");
                assertThat(page.textContent("[data-relations='R4']"))
                    .describedAs("the far end of each edge is phrased from ITS side — the other end "
                        + "of 'waits for' is 'needed by', not another 'waits for'")
                    .contains("needed by R2").contains("conflicts with R2");
                assertThat(page.querySelectorAll("[data-relations='R3']"))
                    .describedAs("a requirement with only hierarchy edges carries no relation badges")
                    .isEmpty();
                // The hierarchy is not restated: the indent already says it.
                assertThat(page.locator("[data-testid='requirements-tree']").innerText())
                    .doesNotContain("part of");
                // Rule 6, and the assertion §9 step 7 asked for. It is only meaningful now that
                // relations are actually drawn — until this change nothing rendered one, so this
                // passed for the emptiest possible reason.
                assertThat(page.locator("body").innerText())
                    .describedAs("relation constants are internal and must never reach the operator")
                    .doesNotContain("REFINES").doesNotContain("DEPENDS_ON")
                    .doesNotContain("CONFLICTS_WITH").doesNotContain("DERIVED_FROM")
                    .doesNotContain("GATES");
                // The same rule, for the other two families of constant. Relations were pinned and
                // have not leaked since; lifecycle states and kinds were not, and had leaked into
                // five separate places by the time anybody looked.
                OperatorWords.assertNoneOnScreen(page, "the requirements workspace");
                // Rule 1: an epic is a rendering, never a noun (four-noun concept budget).
                assertThat(page.locator("body").innerText().toLowerCase())
                    .describedAs("'epic' is not one of the four nouns")
                    .doesNotContain("epic");

                // --- a malformed hierarchy loads, and says what is wrong ----------------------
                // R3 claims two parents. The document must still render in full (nothing vanishes,
                // UX v3 rule 1) and the offending row must be MARKED — silently picking one parent
                // would hide an inconsistency in the operator's own document.
                page.waitForSelector("[data-row='req-R3'] [data-shape-warning='true']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                String rowWarning = page.getAttribute(
                    "[data-row='req-R3'] [data-shape-warning='true']", "title");
                assertThat(rowWarning)
                    .describedAs("the warning says what to DO and names the requirements involved")
                    .contains("belongs in one place").contains("R1").contains("R2");
                assertThat(rowWarning)
                    .describedAs("and never leaks the internal relation name (UX v3 rule 3)")
                    .doesNotContain("REFINES");

                // Folding hides parts without losing them: the count is the record (rule 1).
                page.click("[data-row='req-R1'] >> nth=0 >> css=div:nth-child(2)");
                page.waitForSelector("[data-testid='tree-folded-count']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(page.textContent("[data-testid='tree-folded-count']"))
                    .describedAs("what was folded is counted, not silently gone")
                    .contains("folded away");
                page.click("text=Expand all");
                page.waitForSelector("[data-row='req-R2']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

                // --- a requirement's own checks and its parts' are never one figure (rule 4) ------
                // R1 carries no check of its own; R2 and R3 carry three between them. Reporting the
                // subtree total as R1's own would claim something verifies R1 when nothing does.
                String r1Coverage = page.textContent("[data-coverage='R1']");
                assertThat(r1Coverage)
                    .describedAs("the coarse requirement's figure is labelled as its parts'")
                    .contains("in parts").contains("2/3");
                assertThat(page.textContent("[data-coverage='R2']"))
                    .describedAs("a leaf shows its own, unlabelled and unqualified")
                    .isEqualTo("1/2");
                // …and a ratio is never printed when there is nothing to count. Scoped to the tree:
                // the status bar legitimately says "0/0 runs", and asserting over the whole page made
                // this fail on an unrelated element rather than on the thing it is about.
                assertThat(page.locator("[data-testid='requirements-tree']").innerText())
                    .describedAs("'0/0' is a figure that means nothing where progress is read")
                    .doesNotContain("0/0");

                // The coverage breakdown opens on the figure, and states what the numbers mean in
                // words rather than making the operator decode a ratio.
                page.click("[data-coverage='R1']");
                page.waitForSelector("[data-testid='coverage-breakdown']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-requirements-tree.png")));

                // --- searching keeps a match attached to its place, and says what it hid ----------
                // "Token" matches only R3, which is two levels down. Its ancestors must come with it
                // (UX v3 rule 1) marked as context, and the counts must not present them as results.
                page.fill("[data-testid='requirements-search']", "Token");
                page.waitForSelector("[data-testid='requirements-excluded']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.textContent("[data-testid='requirements-query'] >> xpath=..")
                    .replace('\n', ' '))
                    .describedAs("one match, its parent as context, and the rest counted — three "
                        + "numbers, because their sum would overstate the answer")
                    .contains("1 match").contains("shown for context").contains("not matching");
                assertThat(page.querySelectorAll("[data-row='req-R3']"))
                    .describedAs("the match itself is on screen")
                    .hasSize(1);
                assertThat(page.getAttribute("[data-row='req-R1']", "data-context-only"))
                    .describedAs("its ancestor is shown, and marked as context rather than a result")
                    .isEqualTo("true");
                assertThat(page.querySelectorAll("[data-row='req-R2']"))
                    .describedAs("a requirement that neither matched nor holds a match is not shown")
                    .isEmpty();
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-requirements-search.png")));

                // A filter matching nothing must not look like an empty project.
                page.fill("[data-testid='requirements-search']", "zzzz-no-such-thing");
                page.waitForSelector("[data-testid='requirements-no-matches']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.textContent("[data-testid='requirements-no-matches']"))
                    .describedAs("it names how many requirements are being withheld")
                    .contains("4 requirements");

                // …and the excluded count is the way back — clicking it restores everything.
                page.click("text=Clear the search and filters");
                page.waitForSelector("[data-row='req-R2']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.isVisible("[data-testid='requirements-excluded']"))
                    .describedAs("with nothing filtered there is nothing to report hiding")
                    .isFalse();

                // A filter chip shows whether it is ON — a control whose state you cannot see is a
                // control that will be left on by accident.
                page.click("[data-chip='misplaced']");
                page.waitForSelector("[data-chip='misplaced'][data-active='true']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.waitForSelector("[data-row='req-R3'] [data-shape-warning='true']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[data-chip='misplaced']");
                page.waitForSelector("[data-chip='misplaced'][data-active='false']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

                // --- the diagram is reached FROM a row, scoped to it ------------------------------
                // R3's surroundings are R3 and its parent R1. R2 is a sibling one hop away through
                // nothing, so it is out of scope — which is the point: the diagram draws a
                // neighbourhood, not the document.
                page.click("[data-surroundings='R3']");
                page.waitForSelector("[data-testid='graph-focus-banner']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.waitForSelector("[data-node='req-R3']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.textContent("[data-testid='graph-focus-banner']"))
                    .describedAs("a scoped diagram must not read as a project with three requirements")
                    .contains("surrounds R3").contains("4 requirements in all");
                assertThat(page.querySelectorAll("[data-node='req-R1']"))
                    .describedAs("the chain it is part of comes with it")
                    .hasSize(1);
                assertThat(page.querySelectorAll("[data-node='req-R2']"))
                    .describedAs("R2 is one hop away - R3 wrongly claims it as a second parent, and "
                        + "that is exactly the link the operator opened this diagram to see")
                    .hasSize(1);
                assertThat(page.querySelectorAll("[data-node='req-R4']"))
                    .describedAs("a requirement no relation connects to R3 is out of scope")
                    .isEmpty();
                // The requirement the operator ASKED about has to be inside the visible canvas, not
                // merely in the DOM. It was not: the canvas fits by measuring its own width, which is
                // zero while the pane is hidden, so focusing before showing left R3 off the right edge.
                // waitForSelector passed throughout — only looking at the screen found it.
                // Measured against the node's OWN owner svg. A bare "svg" selector matches the first
                // icon on the page, which is 32px wide and made this assertion nonsense.
                Object clipped = page.evalOnSelector("[data-node='req-R3']",
                    "el => { const svg = el.ownerSVGElement;"
                        + " const s = svg.getBoundingClientRect();"
                        + " const n = el.getBoundingClientRect();"
                        + " return n.right > s.right + 1 || n.left < s.left - 1"
                        + " || n.bottom > s.bottom + 1 || n.top < s.top - 1; }");
                assertThat(clipped)
                    .describedAs("the focused requirement is inside the visible canvas, not past its edge")
                    .isEqualTo(Boolean.FALSE);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-requirements-focus.png")));

                // …and the way out is on the banner.
                page.click("[data-testid='graph-focus-clear']");
                page.waitForSelector("[data-node='req-R2']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.isVisible("[data-testid='graph-focus-banner']"))
                    .describedAs("nothing claims a scope once the whole document is shown")
                    .isFalse();

                // The editor no longer offers "category / epic" — a second way to say what contains
                // what, beside the hierarchy, using a noun the concept budget does not have.
                assertThat(page.locator("body").innerText())
                    .doesNotContain("category / epic").doesNotContain("Category");

                // The graph is still there, one click away, and its nodes are the same document.
                page.click("[data-testid='requirements-view-graph']");
                page.waitForSelector("text=Requirements (BRD)",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.waitForSelector("[data-node='req-R1']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.waitForSelector("[data-node='req-R2']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                // The diagram names its links in the operator's words now. It used to draw
                // "refines", "derived" and "gates" on the edges, in the legend and in the picker -
                // three of the five relation constants, on the panel that exists to explain the
                // picture (§25.5). RequirementRelation holds the wording, so the row, the legend
                // and the line of history cannot say three different things.
                assertThat(page.content())
                    .contains("part of").contains("constrains").contains("drawn from")
                    .doesNotContain("refines").doesNotContain("derived from");
                // The same sentence in both places, because both derive it from RequirementTree.
                assertThat(page.textContent("[data-node='req-R3'] >> title"))
                    .contains("belongs in one place");

                // --- a test name nobody confirmed says so, next to the box it is about ----------
                // Clicking a node opens that requirement in the editor, which is the only way the
                // checks reach a screen at all. The warning's DATA has been asserted since it was
                // written; until here nothing had ever rendered it, so "it compiles and the flag is
                // right" and "the operator can see it" had never met.
                page.click("[data-node='req-R2']");
                page.waitForSelector("[data-form='graph'] [data-check-ref='suggested']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                String suggested = page.textContent(
                    "[data-form='graph'] [data-check-ref='suggested'] [data-testid='check-ref-suggested']");
                assertThat(suggested)
                    .describedAs("it says where the name came from, that it is not confirmed, and "
                        + "what to do about it")
                    .contains("Suggested by the analyst")
                    .contains("no test with this name exists yet")
                    .contains("Change it now if it is wrong");
                assertThat(suggested)
                    .describedAs("in the operator's vocabulary only — UX v3 rule 3. testAuthor is "
                        + "the internal name of a worker role and never reaches a screen")
                    .doesNotContain("testAuthor").doesNotContain("test writer")
                    .doesNotContain("PROPOSED").doesNotContain("OPERATOR")
                    .doesNotContain("TestRefOrigin");
                // The note is INSIDE the row it is about, under that row's own test box. A warning
                // parked at the top of the panel would be about "one of these", which is no help.
                assertThat(page.querySelectorAll(
                    "[data-form='graph'] [data-check-ref='suggested'] [data-testid='check-ref-suggested']"))
                    .describedAs("the warning sits in the row whose test name is unconfirmed")
                    .hasSize(1);
                assertThat(page.querySelectorAll(
                    "[data-form='graph'] [data-check-ref='confirmed'] [data-testid='check-ref-suggested']"))
                    .describedAs("and a name a person typed carries no warning at all")
                    .isEmpty();
                assertThat(page.querySelectorAll("[data-form='graph'] [data-check-ref='confirmed']"))
                    .describedAs("...which is only worth asserting because that row is on screen")
                    .hasSize(1);
                // Looked at on three window sizes, because the note is a sentence beside an icon in
                // a flex row and a sentence that wraps badly is the usual way a quiet warning turns
                // into a mess. The editor pane narrows with the window; the note has to wrap under
                // itself and stay attached to its own test box.
                for (int width : new int[] {1600, 1280, 960}) {
                    page.setViewportSize(width, 950);
                    page.waitForSelector("[data-form='graph'] [data-check-ref='suggested']",
                        new Page.WaitForSelectorOptions().setTimeout(5_000));
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target", "console-check-suggested-test-" + width + ".png")));
                }
                page.setViewportSize(1600, 950);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-check-suggested-test.png")));

                // Add a requirement — persists over RMI. The server mints from the highest existing
                // handle, so with R1-R4 seeded above the new one is R5.
                page.click("[title='New requirement']");
                page.waitForSelector("[data-form='graph'] [placeholder='short title']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.fill("[data-form='graph'] [placeholder='short title']", "Password reset");
                page.fill("[data-form='graph'] [data-testid='brd-req-text']", "Users can reset a forgotten password");
                page.click("[data-form='graph'] [data-testid='brd-save']");
                page.waitForSelector("[data-node='req-R5']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

                // A brand-new requirement must be abandonable. Dismissing used to be possible
                // only through "Delete", which for something never saved reads as destructive —
                // so the form looked like a trap.
                page.click("[title='New requirement']");
                page.waitForSelector("[data-form='graph'] [data-testid='brd-cancel']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.click("[data-form='graph'] [data-testid='brd-cancel']");
                page.waitForSelector("[data-form='graph'] [data-testid='brd-save']",
                    new Page.WaitForSelectorOptions().setState(
                        com.microsoft.playwright.options.WaitForSelectorState.HIDDEN)
                        .setTimeout(5_000));

                // With nothing selected the pane must OFFER the primary way in, not merely
                // describe it: the upload lived behind an unlabelled header icon, so on the one
                // screen that exists to explain the feature, the main path into it was invisible.
                // (The selector named a label that has never existed in BrdView — the test is
                // opt-in, so it had never run against the button it was written for.)
                // BY ITS OWN NAME, not by its words. The analysis dialog's title is the same
                // sentence, and there is more than one of that dialog in the page since
                // the getting-started guide composed one of its own
                // — an unscoped text selector picks whichever is earlier in the
                // document, which is a CLOSED dialog, and then waits for ever for it to be visible.
                page.waitForSelector("[data-testid='brd-empty-analyse']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

                // …and following it reaches the box that documents are actually added through.
                // Worth asserting because that box is now a framework component talking to a
                // framework HTTP address, not code in this repository: if the component fails to
                // build, or the page it is on cannot reach the address, the only symptom is a
                // wizard with no way to add anything — which no other test would notice.
                page.click("[data-testid='brd-empty-analyse']");
                page.waitForSelector(
                    "[data-testid='intake-wizard'] >> text=Drop your requirements documents here",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-testid='intake-wizard']").innerText())
                    .describedAs("the box says how to add a document and what kinds are read")
                    .contains("or click to choose them")
                    .contains("photo of a whiteboard");
                // Both of the screenshots below used to be taken while the dialog was still
                // fading in, which is why they showed the panel behind it reading straight through
                // the words on it. See Dialogs.
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-document-upload.png")));
                Dialogs.shotAtEveryWidth(page, "console-document-upload");

                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-brd.png")));
                assertThat(pageErrors).isEmpty();
            }
        }
    }
}
