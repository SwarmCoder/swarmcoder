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
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two guided wizards, on a screen, at three window sizes — with a scripted model rather than a
 * live one.
 *
 * <p>These are the two dialogs the Console's whole opinionated flow runs through: the requirements
 * intake ("here are the client's documents, turn them into requirements") and the backlog planning
 * ("here is the agreed scope, propose the stories that deliver it"). Every other browser test
 * stopped at their front door, because both talk to a model and no model server is available. That
 * left the biggest, most stateful surfaces in the product as the only ones nobody had a picture of.
 *
 * <p><b>No model server is needed and none is used.</b> The wizards do not call a model endpoint;
 * they call {@code ConsoleContext.chatModel()}, which is wiring the embedder supplies. That is
 * exactly how the two existing unit tests around these flows drive them
 * ({@link GuidedFlowIntakeTest}, {@link GuidedFlowPlanningTest}) — the only new thing here is that
 * the same scripted replies are being driven from a real browser instead of from a service call. The
 * script is {@link ScriptedAnalyst}: it routes on the system prompt, so one model serves both
 * wizards and both of their phases in the order the operator happens to use them.
 *
 * <p>What is photographed is every screen either wizard has: the documents/inputs step, the round of
 * questions, the per-question background window that opens over the wizard, the review, and the
 * finished state you land in when you reopen a wizard after applying. The running step is not: it
 * lasts as long as the scripted reply takes to return, which is microseconds, and pausing to catch
 * it would be a test asserting on its own timing.
 *
 * <p>One embedded Console per JVM — this class holds exactly one browser test for that reason, not
 * as a style preference. zeroz4j's {@code Signals.shared(...)} binds to the first server engine
 * started in a JVM, so a second one started later serves pages but its signal {@code set()} never
 * reaches the browser. Surefire's {@code reuseForks=false} (sc-console/pom.xml) gives every browser
 * test class a fresh JVM; adding a second browser test to this class would silently break it.
 *
 * <p>Runs in an ordinary build wherever Playwright's Chromium is installed — no flag. On a machine without it, it is skipped and the skip is announced (see {@code @RunsWhen}).
 */
class ConsoleWizardsBrowserTest {

    @TempDir
    Path storeDir;

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        // The Console's RMI services are @Secured; the client authenticates as the dev admin.
        System.setProperty("zeroz.security.mode", "dev");
    }

    /**
     * A scripted analyst and planner in one, routed on the system prompt.
     *
     * <p>Routed rather than a fixed list of replies in call order, because a browser test drives the
     * two wizards in whatever order the operator would and each asks its model twice. A positional
     * script would silently hand the planner the analyst's answer the moment anything moved.
     */
    private static final class ScriptedAnalyst implements ConsoleContext.ChatModel {

        private final List<String> systems = java.util.Collections.synchronizedList(
            new ArrayList<>());

        @Override
        public Stream<String> stream(List<Map<String, String>> messages, String modelOverride) {
            String system = messages.isEmpty() ? "" : String.valueOf(messages.get(0).get("content"));
            systems.add(system);
            if (system.contains("requirements analyst reading a client's documents")) {
                return Stream.of(INTAKE_QUESTIONS);
            }
            if (system.contains("You are a requirements analyst. From the documents")) {
                return Stream.of(INTAKE_PROPOSALS);
            }
            if (system.contains("planning a delivery backlog")) {
                return Stream.of(PLANNING_QUESTIONS);
            }
            if (system.contains("You are a delivery planner")) {
                return Stream.of(PLANNING_PROPOSALS);
            }
            return Stream.of("Nothing to say.");
        }

        int calls() {
            return systems.size();
        }
    }

    /**
     * One question with options and one without, so both kinds of answer control are on screen.
     *
     * <p>Each carries a background and a source quote: those are the parts of the question row that
     * only render when the model supplies them, and they are the reason the per-question window
     * exists at all — so a script that omitted them would photograph a question row with half its
     * furniture missing and prove nothing about the rest.
     */
    private static final String INTAKE_QUESTIONS = """
        {"questions":[\
        {"subject":"Refund window","text":"How long after a purchase may a customer ask for their \
        money back?","kind":"CHOICE","options":["14 days","30 days","no limit"],\
        "background":"The brief promises refunds twice and gives a different period each time. \
        Section 2 says a fortnight; the pricing table at the back says a month. Which one holds \
        decides whether the ledger has to keep completed orders open for two weeks or four, and \
        that is a different design, not a different constant.",\
        "sourceQuote":"Customers may request a refund within 14 days of purchase.",\
        "sourceDocument":"pricing.md"},\
        {"subject":"Who approves","text":"Who signs off a refund above the automatic limit?",\
        "kind":"TEXT",\
        "background":"Nothing in the documents names a person or a role for this, and the \
        difference between \\"anyone in support\\" and \\"a named manager\\" is an approval step \
        that either exists or does not.",\
        "sourceQuote":"Large refunds require approval.","sourceDocument":"pricing.md"}\
        ]}""";

    /** An ADD and an EDIT, so the review shows both kinds of proposed change. */
    private static final String INTAKE_PROPOSALS = """
        {"proposals":[\
        {"kind":"ADD","handle":"","title":"Refund within the stated window",\
        "rationale":"the brief promises refunds and names a period, so it is a requirement",\
        "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Payments",\
        "text":"A customer can request a refund within 30 days of purchase, and the money is \
        returned to the card that paid.",\
        "criteria":["a refund requested on day 29 is accepted",\
        "a refund requested on day 31 is refused with a reason"]},\
        {"kind":"EDIT","handle":"R2","title":"Guest checkout",\
        "before":"A guest can buy without an account.",\
        "priority":"MEDIUM","requirementKind":"FUNCTIONAL",\
        "text":"A guest can buy without an account, and is offered the refund window at checkout.",\
        "criteria":[]}\
        ]}""";

    private static final String PLANNING_QUESTIONS = """
        {"questions":[\
        {"subject":"Sequencing","text":"Which comes first — taking money, or giving it back?",\
        "kind":"CHOICE","options":["checkout first","refunds first"],\
        "background":"Refunds cannot be demonstrated end to end until something can be bought, so \
        planning them first means a story that cannot be shown working until the one after it \
        lands. Saying so out loud is cheaper than discovering it in a review."}\
        ]}""";

    private static final String PLANNING_PROPOSALS = """
        {"proposals":[\
        {"kind":"ADD","storyKind":"DELIVERY","title":"A guest can buy and see that it worked",\
        "delivers":"R2:C1,R2:C2","narrative":"As a guest I want to pay without making an account",\
        "rationale":"these two checks are one slice anybody can watch work end to end"},\
        {"kind":"ADD","storyKind":"ENABLER","title":"Payment provider sandbox",\
        "unblocks":"R2","rationale":"nothing above can be verified until the sandbox exists"}\
        ]}""";

    @Test
    @RunsWhen(Need.CHROMIUM)
    void bothGuidedWizardsEndToEndWithAScriptedModel() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            List<Project> projects = new ArrayList<>();
            Path alphaRepo = storeDir.resolve("alpha-repo");
            java.nio.file.Files.createDirectories(alphaRepo);
            new ProcessBuilder("git", "init", "-q")
                .directory(alphaRepo.toFile()).redirectErrorStream(true).start().waitFor();
            UUID projectId = UUID.randomUUID();
            projects.add(new Project(projectId, "alpha", alphaRepo.toString(), List.of(),
                Instant.now(), false));

            ScriptedAnalyst analyst = new ScriptedAnalyst();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), runId -> { }, runId -> { })
                .withProjects(() -> projects, () -> projectId, (n, p, c) -> null, id -> { })
                .withChat(analyst));

            // Agreed scope for the planning wizard to plan against: one ACTIVE requirement carrying
            // two accepted criteria that no story claims yet. Without it the wizard's front screen
            // is three zeros and a disabled button, which is a real state but not the one worth a
            // picture.
            Brd brd = store.ensureBrd(projectId);
            BrdRequirement checkout = new BrdRequirement(UUID.randomUUID(), "R2", "Guest checkout",
                "A guest can buy without an account.",
                Priority.HIGH, RequirementStatus.ACTIVE, "Payments");
            checkout.setCriteria(new ArrayList<>(List.of(
                criterion("a purchase completes with no account"),
                criterion("a receipt is shown straight after paying"))));
            brd.requirements().add(checkout);
            store.saveBrd(brd);

            // One uploaded document for the intake wizard to read. Ingested the way an upload
            // ingests it, so the wizard's pool lists a real SourceDocument with a real byte count.
            DocumentIngest.Result ingested = DocumentIngest.ingest(store, null, projectId,
                "pricing.md", "text/markdown",
                ("# Pricing and refunds\n\n"
                    + "Customers may request a refund within 14 days of purchase.\n"
                    + "Large refunds require approval.\n"
                    + "Pricing is per seat, billed monthly.\n").getBytes(StandardCharsets.UTF_8));
            assertThat(ingested.failed())
                .describedAs("the fixture document was ingested").isFalse();

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                // Both wizards run their analysis on a background thread and report progress only
                // over the shared signal. Without this sink the server's publish is a no-op and the
                // wizard sits on "the analyst is reading your documents" for ever.
                ConsoleContext.get().setPushSink(
                    jakarta.enterprise.inject.spi.CDI.current()
                        .select(com.zeroz4j.server.WasmRmiServerEngine.class).get()::broadcastPush);

                List<String> pageErrors = new ArrayList<>();
                Page page = browser.newPage();
                page.onPageError(pageErrors::add);
                List<String> consoleLog = new ArrayList<>();
                page.onConsoleMessage(m -> consoleLog.add(m.type() + ": " + m.text()));
                page.setViewportSize(1600, 950);
                page.navigate("http://localhost:" + server.port() + "/");
                page.waitForSelector("[data-testid='status-header']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
                page.click("[data-testid='stage-requirements']");
                // Waiting for the tree is waiting for the readiness signal: until it lands, every
                // stage shows its "needs a project" gate and nothing below is reachable.
                page.waitForSelector("[data-testid='requirements-tree']",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));

                // =========================== the intake wizard ===========================
                // "Analyse documents" is ABOVE the two lenses now, so it is on screen whichever one
                // is showing. It used to be in the diagram's own header, and this test had to switch
                // to the diagram first — which was the defect: the list is what an operator lands
                // on, and the primary way of getting requirements in was not on it.
                page.click("[data-testid='requirements-analyse']");
                page.waitForSelector("[data-testid='intake-wizard'] >> text=pricing.md",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- step 1: the documents ---------------------------------------------------
                Dialogs.shotAtEveryWidth(page, "console-intake-documents",
                    () -> Dialogs.assertNothingOverflows(page,
                        "[data-testid='intake-wizard'] .modal-box", "the intake wizard"));
                assertThat(page.locator("[data-testid='intake-wizard']").innerText())
                    .describedAs("it says plainly that analysing writes nothing, which is the "
                        + "fear that stops anyone pressing the button")
                    .contains("Nothing is written, deleted or overwritten until you review it");
                // The document is in the project but not yet in this analysis: it is in the pool
                // below, with Add beside it, and the button says it will read nothing until then.
                assertThat(page.locator("[data-testid='intake-wizard'] "
                        + "button:has-text('Re-analyse 0 documents')").count())
                    .describedAs("nothing is read until a document is chosen").isEqualTo(1);
                page.click("[data-testid='intake-wizard'] button:text-is('Add')");
                page.waitForSelector("[data-testid='intake-wizard'] "
                        + "button:has-text('Re-analyse 1 document')",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[data-testid='intake-wizard'] "
                    + "button:has-text('Re-analyse 1 document')");

                // --- step 3: the questions ----------------------------------------------------
                page.waitForSelector("[data-testid='intake-wizard'] >> text=Refund window",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                Dialogs.shotAtEveryWidth(page, "console-intake-questions",
                    () -> Dialogs.assertNothingOverflows(page,
                        "[data-testid='intake-wizard'] .modal-box", "the intake wizard"));
                String questions = page.locator("[data-testid='intake-wizard']").innerText();
                assertThat(questions)
                    .describedAs("the passage the question is about comes before the question, "
                        + "so it can be answered without going and finding the document")
                    .contains("Customers may request a refund within 14 days")
                    .contains("pricing.md")
                    .contains("How long after a purchase");
                assertThat(questions)
                    .describedAs("both kinds of question are on screen — one with options to pick "
                        + "from and one to write into")
                    .contains("14 days").contains("no limit").contains("Who signs off");

                // The per-question background, which opens as a second dialog OVER the wizard. It
                // is the case the wizard's own javadoc worries about: a .modal-box carries a
                // transform, so a dialog nested inside one is laid out inside it instead of over
                // the page. Two sibling dialogs is the answer, and this is the picture of it.
                page.locator("[data-testid='intake-wizard'] [title='More context behind this "
                    + "question']").first().click();
                page.waitForSelector("[data-testid='intake-wizard-detail'] >> text=Section 2 says",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                Dialogs.shotAtEveryWidth(page, "console-intake-question-detail",
                    () -> Dialogs.assertNothingOverflows(page,
                        "[data-testid='intake-wizard-detail'] .modal-box",
                        "the question-background window"));
                page.click("[data-testid='intake-wizard-detail'] button:text-is('Close')");

                // Answer the one with options, leave the other for the skip path, and go on.
                page.locator("[data-testid='intake-wizard'] input[type='radio']").nth(1).check();
                page.locator("[data-testid='intake-wizard'] button:text-is('Skip the rest')").click();

                // --- step 4: the review -------------------------------------------------------
                page.waitForSelector("[data-testid='intake-wizard'] >> text=Refund within the "
                        + "stated window",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                Dialogs.shotAtEveryWidth(page, "console-intake-review",
                    () -> Dialogs.assertNothingOverflows(page,
                        "[data-testid='intake-wizard'] .modal-box", "the intake wizard"));
                assertThat(page.locator("[data-testid='intake-wizard']").innerText())
                    .describedAs("it says nothing has been written, and shows the new requirement "
                        + "and the amendment to the existing one as separate proposals")
                    .contains("Nothing has been written yet")
                    .contains("Refund within the stated window")
                    .contains("Guest checkout");
                // The review block used to open with "Kind: FUNCTIONAL" — the Java constant,
                // shouted, as the first thing on the screen. It now says what the kind MEANS.
                assertThat(page.locator("[data-testid='intake-wizard']").innerText())
                    .describedAs("the requirement's kind reads as what it is, in ordinary words")
                    .contains("Kind: something it must do");
                OperatorWords.assertNoneOnScreen(page, "the intake wizard's review");
                assertThat(store.getBrd(projectId).requirements())
                    .describedAs("and it means it — the BRD still holds only what was seeded")
                    .hasSize(1);

                page.click("[data-testid='intake-wizard'] button:text-is('Apply to the BRD')");
                // Apply closes the wizard on success, because the next thing the operator wants is
                // the requirements themselves. So the finished screen is what you land on when you
                // open the wizard again — which is a state an operator does reach, and one nobody
                // had looked at either.
                page.waitForSelector("[data-testid='intake-wizard']",
                    new Page.WaitForSelectorOptions()
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN)
                        .setTimeout(15_000));
                assertThat(store.getBrd(projectId).requirements())
                    .describedAs("the accepted proposals landed").hasSize(2);

                page.click("[data-testid='requirements-analyse']");
                page.waitForSelector("[data-testid='intake-wizard'] >> text=Add more documents",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                Dialogs.shotAtEveryWidth(page, "console-intake-applied",
                    () -> Dialogs.assertNothingOverflows(page,
                        "[data-testid='intake-wizard'] .modal-box", "the intake wizard"));
                assertThat(page.locator("[data-testid='intake-wizard']").innerText())
                    .describedAs("it says what landed and what did not, and what has to happen to "
                        + "the new requirements next")
                    .contains("Applied")
                    .contains("Each new requirement is a draft")
                    .contains("agree the ones you want built");
                OperatorWords.assertNoneOnScreen(page, "the intake wizard's finished screen");
                page.click("[data-testid='intake-wizard'] button:text-is('Done')");

                // =========================== the planning wizard ===========================
                page.click("[data-testid='stage-plan']");
                page.waitForSelector("[data-testid='pipeline-board']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                page.locator("[data-testid='pipeline-board'] >> text=Plan stories").first().click();
                page.waitForSelector("[data-testid='planning-wizard'] >> text=checks no story "
                        + "delivers",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- step 1: what will be read ------------------------------------------------
                Dialogs.shotAtEveryWidth(page, "console-planning-inputs",
                    () -> Dialogs.assertNothingOverflows(page,
                        "[data-testid='planning-wizard'] .modal-box", "the planning wizard"));
                assertThat(page.locator("[data-testid='planning-wizard']").innerText())
                    .describedAs("the coverage report names the requirements with nothing "
                        + "delivering them, rather than only counting them")
                    .contains("R2");
                // The coverage report is printed VERBATIM here, and it is written by
                // BacklogAuthoring, which also writes for the model. It used to open with "ACTIVE
                // requirements with NO criteria" three lines under the figure captioned "checks no
                // story delivers", so the operator was shown a persisted enum constant and two
                // words for one thing on one screen.
                OperatorWords.assertNoneOnScreen(page, "the planning wizard's first screen");

                page.click("[data-testid='planning-wizard'] button:text-is('Plan stories')");

                // --- step 3: the questions ----------------------------------------------------
                page.waitForSelector("[data-testid='planning-wizard'] >> text=Sequencing",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                Dialogs.shotAtEveryWidth(page, "console-planning-questions",
                    () -> Dialogs.assertNothingOverflows(page,
                        "[data-testid='planning-wizard'] .modal-box", "the planning wizard"));
                assertThat(page.locator("[data-testid='planning-wizard']").innerText())
                    .describedAs("the planner asks about order of work, not about whether agreed "
                        + "scope should be built")
                    .contains("Which comes first").contains("checkout first");
                page.locator("[data-testid='planning-wizard'] input[type='radio']").first().check();
                page.click("[data-testid='planning-wizard'] button:text-is('Continue')");

                // --- step 4: the review -------------------------------------------------------
                page.waitForSelector("[data-testid='planning-wizard'] >> text=A guest can buy and "
                        + "see that it worked",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                Dialogs.shotAtEveryWidth(page, "console-planning-review",
                    () -> Dialogs.assertNothingOverflows(page,
                        "[data-testid='planning-wizard'] .modal-box", "the planning wizard"));
                assertThat(page.locator("[data-testid='planning-wizard']").innerText())
                    .describedAs("nothing is in the backlog yet, and what would land is named "
                        + "along with the checks it delivers")
                    .contains("Nothing has been added yet")
                    .contains("Payment provider sandbox");
                OperatorWords.assertNoneOnScreen(page, "the planning wizard's review");

                page.click("[data-testid='planning-wizard'] button:text-is('Add to the backlog')");
                page.waitForSelector("[data-testid='pipeline-col-suggested'] >> text=A guest can "
                        + "buy and see that it worked",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // The finished screen, reached the same way as the intake wizard's.
                page.locator("[data-testid='pipeline-board'] >> text=Plan stories").first().click();
                page.waitForSelector("[data-testid='planning-wizard'] >> text=Plan again",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                Dialogs.shotAtEveryWidth(page, "console-planning-applied",
                    () -> Dialogs.assertNothingOverflows(page,
                        "[data-testid='planning-wizard'] .modal-box", "the planning wizard"));
                // This screen used to say the stories were "a DRAFT sitting in Triage —
                // promoting it to READY". Three internal names in one sentence, and none of the
                // three words appears anywhere the operator could go and look.
                assertThat(page.locator("[data-testid='planning-wizard']").innerText())
                    .describedAs("it says where the stories now are and what to do with them, "
                        + "naming a column the operator can actually see")
                    .contains("Suggested column of the Pipeline")
                    .contains("Ready to build");
                OperatorWords.assertNoneOnScreen(page, "the planning wizard's finished screen");
                page.click("[data-testid='planning-wizard'] button:text-is('Done')");

                assertThat(analyst.calls())
                    .describedAs("four model calls: a round of questions and a round of proposals "
                        + "for each wizard, and not one more")
                    .isEqualTo(4);
                assertThat(pageErrors).isEmpty();
            }
        } finally {
            ConsoleContext.set(null);
        }
    }

    private static AcceptanceCriterion criterion(String text) {
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), text, null);
        criterion.setStatus(CriterionStatus.ACCEPTED);
        return criterion;
    }
}
