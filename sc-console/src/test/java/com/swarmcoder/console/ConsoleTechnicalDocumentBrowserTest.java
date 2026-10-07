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
 * A document can be called technical BEFORE anything reads it.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The tick that says "this document is about the technology" was put on the requirements
 * screen's document list only. On the guide's route — the one a brand-new project takes — that
 * screen is not reached until <b>after</b> the analyst has been started: the guide's own document
 * step ends at a button that reads "Read all 2, and show me what they say", and pressing it calls
 * {@code start} and only then moves on. So on a new project every document had already been read,
 * unclassified, by the time the tick was on screen at all. It changed the next run and nothing
 * else, which on a new project is a run that never happens.
 *
 * <p>The fix puts the same question, in the same words, on the step where documents are added. This
 * test walks to that step and proves three things about the order they happen in: the tick is there
 * while the documents are still unread, ticking it really writes the flag, and the analysis has
 * still not been started when it does.
 *
 * <h2>No model is called</h2>
 *
 * <p>The button that starts the analyst is asserted to be present and is never pressed. Starting it
 * would call a real, paid model endpoint. Everything asserted here happens before that button
 * matters, which is the whole point of the change.
 *
 * <p>One embedded Console per JVM — see {@link ConsoleBrowserSmokeTest} for why this is its own
 * class rather than another method on an existing one.
 */
class ConsoleTechnicalDocumentBrowserTest {

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void aDocumentIsMarkedTechnicalBeforeTheAnalystIsStarted(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            UUID projectId = RequirementsSandboxRunner.seed(store, dir);
            // Two documents on an UNREAD flow: a business brief and the one that says what the
            // thing must be made of. This is exactly the shape the defect was found on.
            SourceDocument brief = attach(store, projectId, "payments-brief.md",
                "Shoppers must be able to pay with a card they saved earlier.");
            SourceDocument stack = attach(store, projectId, "how-it-is-built.md",
                "Pure Java on ZeroZ Stack. No Spring, no JPA, no Flyway.");
            UUID flowId = UUID.randomUUID();
            store.saveGuidedFlow(new GuidedFlow(flowId, projectId,
                GuidedFlowKind.REQUIREMENTS_INTAKE, GuidedFlowState.DRAFT, 0, 0,
                "Add the documents to analyse", null,
                List.of(new FlowDocument(brief.id(), null),
                    new FlowDocument(stack.id(), null)),
                Instant.now(), Instant.now()));

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

                // --- the tick is on the step where documents are added --------------------------
                page.waitForSelector("[data-testid='guide-doc-technical']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='guide-doc-technical']").count())
                    .describedAs("one tick per attached document, on the step that attached them")
                    .isEqualTo(2);
                assertThat(page.locator("[data-testid='onboarding-wizard']").innerText())
                    .describedAs("and it asks in words, not with a bare box")
                    .contains("This says how the system must be BUILT, not what it must do");

                // --- and nothing has read anything yet ------------------------------------------
                assertThat(page.locator("[data-testid='guide-next']").innerText())
                    .describedAs("the analyst has NOT been started: the button that would start "
                        + "it is still sitting there unpressed, which is what makes the tick "
                        + "above worth having")
                    .contains("Read all 2");
                assertThat(store.getGuidedFlow(flowId).state())
                    .describedAs("the flow is still unread in the store, not merely on screen")
                    .isEqualTo(GuidedFlowState.DRAFT);
                assertThat(page.locator("[data-testid='guide-no-technical-document']").count())
                    .describedAs("with nothing ticked, the step says so once, quietly")
                    .isEqualTo(1);

                Dialogs.shotAtEveryWidth(page, "console-guide-2-documents-technical", () -> {
                    Dialogs.assertNothingOverflows(page,
                        "[data-testid='onboarding-wizard'] .modal-box",
                        "the document step carrying a kind tick per document");
                    assertThat(page.locator("[data-testid='guide-doc-technical']").last()
                            .isVisible())
                        .describedAs("the tick is reachable at every window width, not pushed "
                            + "out of the list at the narrow one")
                        .isTrue();
                });
                OperatorWords.assertNoneOnScreen(page, "the guide, adding a document");

                // --- ticking it, before the reading, sets the flag ------------------------------
                page.locator("[data-testid='guide-doc-technical']").last().check();
                // The click goes to the server and comes back as a published flow, so the fact is
                // waited for where it has to be true — in the store — rather than after a sleep.
                GuidedFlow saved = awaitTechnical(store, flowId, stack.id(), page);
                page.waitForFunction(
                    "() => document.querySelectorAll("
                        + "'[data-testid=\"guide-no-technical-document\"]').length === 0",
                    null, new Page.WaitForFunctionOptions().setTimeout(15_000));

                assertThat(technicalIn(saved, stack.id()))
                    .describedAs("the document the operator ticked is marked as saying how the "
                        + "system must be built — written before anything read it")
                    .isTrue();
                assertThat(technicalIn(saved, brief.id()))
                    .describedAs("and the one they did not tick is untouched")
                    .isFalse();
                assertThat(saved.state())
                    .describedAs("the analysis STILL has not been started — the classification "
                        + "happened first, which is the whole of this fix")
                    .isEqualTo(GuidedFlowState.DRAFT);

                // --- and it survives a re-render, so it is read from the server ------------------
                page.click("[data-testid='onboarding-wizard'] >> text=\"Back\"");
                page.waitForSelector("text=Pick whichever of these is closest",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("text=I have written down what I want built");
                page.waitForSelector("[data-testid='guide-doc-technical']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-testid='guide-doc-technical']").last().isChecked())
                    .describedAs("coming back to the step shows the tick still on, because it is "
                        + "read from the flow rather than held in the browser")
                    .isTrue();
                assertThat(page.locator("[data-testid='guide-no-technical-document']").count())
                    .describedAs("and the line about nothing being ticked is gone")
                    .isZero();
            }
        } finally {
            ConsoleContext.set(null);
        }
    }

    /**
     * Waits for the ticked document to be marked technical in the store.
     *
     * <p>Fails with what the wizard has on screen, because the two ways this can go wrong — the
     * click never reaching the server, and the server refusing the call — look identical from
     * outside and the wizard prints the refusal.
     */
    private static GuidedFlow awaitTechnical(ArtifactStore store, UUID flowId, UUID documentId,
                                             Page page) throws InterruptedException {
        long until = System.currentTimeMillis() + 15_000;
        GuidedFlow flow = store.getGuidedFlow(flowId);
        while (System.currentTimeMillis() < until) {
            flow = store.getGuidedFlow(flowId);
            if (technicalIn(flow, documentId)) {
                return flow;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("ticking the box never reached the flow. What the guide says: "
            + page.locator("[data-testid='onboarding-wizard']").innerText());
    }

    private static SourceDocument attach(ArtifactStore store, UUID projectId, String name,
                                         String text) {
        SourceDocument document = new SourceDocument(UUID.randomUUID(), projectId, name,
            "text/markdown", Integer.toHexString(name.hashCode()), text, "passthrough",
            text.length(), Instant.now());
        store.saveSourceDocument(document);
        return document;
    }

    private static boolean technicalIn(GuidedFlow flow, UUID documentId) {
        for (FlowDocument entry : flow.documents()) {
            if (documentId.equals(entry.documentId())) {
                return entry.technical();
            }
        }
        throw new IllegalStateException("no document " + documentId + " in this flow");
    }
}
