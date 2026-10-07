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
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.WorkerHealth;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.LiveWorkerSessions;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker's health on the screen, before it dies.
 *
 * <p>The report this pins, in the operator's own words: "if we make it 40 and then I give it a much
 * bigger task, it might fail. And then I sit here and I don't see anything happening on screen as
 * there is no feedback about what is actually going on."
 *
 * <p>The measurement behind the rule, from his run on 2026-09-01: eight workers finished at 8 to 21
 * turns and 16k to 42k tokens; four died at 62, 72, 74 and 99 turns, every one killed when a single
 * request outlasted the fifteen minutes a request is allowed. The endpoint was healthy throughout.
 * So the thing to show is the size of the conversation — nothing trims it and every turn re-sends
 * all of it — and the age of the request in flight.
 *
 * <p>One embedded Console per JVM, so this class holds exactly one browser test and everything is
 * asserted inside it. Runs wherever Playwright's Chromium is installed; skipped, and the skip
 * announced, where it is not.
 */
class ConsoleWorkerHealthBrowserTest {

    @TempDir
    Path storeDir;

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void aWorkerHeadingForTroubleIsOrange_andAMissingNumberCostsNothing() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TraceHub hub = new TraceHub(null);
            UUID projectId = UUID.randomUUID();
            List<Project> projects = List.of(new Project(projectId, "bookshelf",
                storeDir.toString(), List.of(), Instant.now(), false));
            ConsoleContext.set(new ConsoleContext(store, hub,
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> projects, () -> projectId, (n, p, c) -> null, id -> { }));

            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            var graph = new TaskGraph(UUID.randomUUID(), 1, null,
                List.of(new Task(taskId, 1, "Add shared Book model", "",
                    Set.of("shared/src/main"), Set.of(), List.of(), null, null, null,
                    new SwarmPolicy(6, false, 0.1, 0.8, List.of()), TaskState.DISPATCHED)),
                List.of());
            store.append(() -> {
                store.root().taskGraphs.put(graph.id(), graph);
                Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                    projectId, null, null, graph.id(), null, Instant.now(),
                    new RunReport(runId, "a bookshelf that stores books"));
                run.setHeartbeatAt(Instant.now());
                store.root().runs.put(runId, run);
                return null;
            }).get();

            // Four workers doing exactly what a worker that FINISHES does: a dozen turns, a
            // conversation comfortably inside the 32,768 tokens the model profile allows one
            // session, a tool run, results handed back. All four were dispatched on one task, so
            // all four opened with the SAME 8,000-token head — the project's rules, the design,
            // the task, the repository map — which the model server prefills once for the group.
            // Two thirds of these conversations is that head: the healthy split.
            for (int i = 0; i < 4; i++) {
                UUID id = LiveWorkerSessions.open(hub, runId, taskId, i, "qwen3.8-27b",
                    0.1 + i * 0.23, 8_000);
                LiveWorkerSessions.answered(hub, id, "on it", 12_000 + i * 1_500L);
                LiveWorkerSessions.step(hub, id, "exec", "mvn -q test-compile");
                LiveWorkerSessions.resultReturned(hub, id, "exec", "BUILD SUCCESS");
            }
            // One heading for trouble: its conversation has passed what its own model profile
            // allows one session, which is how a worker gets to 99 turns and dies of a request
            // that cannot finish in time. Same 8,000-token head as its three siblings — so the
            // other 34,000 are its own, and THAT is what the operator is waiting on. This is the
            // case the third number exists for.
            UUID bloated = LiveWorkerSessions.open(hub, runId, taskId, 4, "qwen3.8-27b", 0.7, 8_000);
            LiveWorkerSessions.answered(hub, bloated, "reading", 22_000);
            LiveWorkerSessions.answered(hub, bloated, "reading more", 42_000);
            LiveWorkerSessions.resultReturned(hub, bloated, "read_file", "...");
            // One with no token count at all — every worker's first seconds, and the shape a field
            // takes when it is added on one side of the wire and not the other.
            LiveWorkerSessions.open(hub, runId, taskId, 5, "qwen3.8-27b", 0.8);

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                ConsoleContext.get().setPushSink(
                    jakarta.enterprise.inject.spi.CDI.current()
                        .select(com.zeroz4j.server.WasmRmiServerEngine.class).get()::broadcastPush);

                List<String> pageErrors = new ArrayList<>();
                Page page = browser.newPage();
                page.onPageError(pageErrors::add);
                page.setViewportSize(1600, 950);
                page.navigate("http://localhost:" + server.port() + "/");
                page.waitForSelector("[data-testid='status-header']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
                page.click("[data-testid='stage-plan']");
                page.waitForSelector("[data-testid='open-run']",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
                page.locator("[data-testid='open-run']").first().click();
                page.waitForSelector("[data-node='cand-0']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- 1. six chips, and exactly ONE of them orange ----------------------------
                // A colour that fires on every worker is worth nothing, and so is one that never
                // fires. Four healthy, one over budget, one whose numbers have not arrived.
                assertThat(page.locator("[data-node^='cand-']").count())
                    .describedAs("one chip per worker, including the one with no numbers yet")
                    .isEqualTo(6);
                page.waitForSelector("[data-health='warning']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-node^='cand-'][data-health='warning']").count())
                    .describedAs("only the worker whose conversation outgrew its budget")
                    .isEqualTo(1);
                assertThat(page.locator("[data-node='cand-4'][data-health='warning']").count())
                    .describedAs("and it is that one")
                    .isEqualTo(1);
                assertThat(page.locator("[data-node='cand-5'][data-health='warning']").count())
                    .describedAs("a worker with no token count is not a worker in trouble — "
                        + "otherwise every worker is orange for its first few seconds")
                    .isZero();

                // --- 2. the numbers are ON the chip ------------------------------------------
                assertThat(page.locator("[data-node='cand-4']").textContent())
                    .describedAs("turns taken and how big its conversation has grown, on the chip")
                    .contains("2t").contains("42k");
                assertThat(page.locator("[data-node='cand-5']").textContent())
                    .describedAs("and a worker with no count yet keeps its temperature rather "
                        + "than reading 0k, which would be a false alarm about a healthy start")
                    .contains("t0.8");

                // --- 2b. and the third one: how much of that conversation never changes -------
                // His words: "add the prefill content length to the chip, so we see prefill /
                // actual context length". Eight thousand of w4's forty-two are the fixed head of
                // its prompt, which every worker on this task sends identically and the model
                // server therefore reads once for all of them; the other thirty-four are w4's own.
                // That gap IS the wall-clock cost, and until now nothing on the screen showed it.
                assertThat(page.locator("[data-node='cand-4']").textContent())
                    .describedAs("the split, on the chip, in the nine characters it has room for")
                    .contains("8/42k");
                assertThat(page.locator("[data-node='cand-0']").textContent())
                    .describedAs("a healthy worker: most of its conversation is the shared head")
                    .contains("8/12k");
                assertThat(page.locator("[data-node='cand-5']").textContent())
                    .describedAs("and a worker with nothing measured shows no split at all")
                    .doesNotContain("/");

                // --- 3. the detail is on the hover -------------------------------------------
                // A card the Console draws, not the operating system's tooltip. Same numbers.
                assertThat(page.locator("[data-node^='cand-'] title").count())
                    .describedAs("no chip carries a native SVG <title> any more - that is the "
                        + "operating system's tooltip and it belongs to no application")
                    .isZero();
                page.locator("[data-node='cand-4']").hover();
                page.waitForSelector("[data-testid='graph-hover-card']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                String hover = page.locator("[data-testid='graph-hover-card']").innerText();
                assertThat(hover)
                    .describedAs("every number he asked to see, in words, behind the mouse")
                    .contains("turns taken")
                    .contains("42k tokens")
                    .contains("33k it is allowed")
                    .contains("last spoke")
                    .contains("Heading for trouble");
                assertThat(hover)
                    .describedAs("and the split in words, with which part is which - the chip has "
                        + "room for '8/42k' and nothing else, so this is where it is explained")
                    .contains("fixed start")
                    .contains("8.0k tokens (estimated)")
                    .contains("its own")
                    .contains("34k tokens")
                    .contains("Every worker on this task sends the same one");
                assertThat(hover)
                    .describedAs("said to be an estimate, because it is: there is no tokenizer "
                        + "here and the total beside it is the model server's own count")
                    .contains("(estimated)");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-worker-health-card.png")));
                page.locator("[data-node='cand-5']").hover();
                assertThat(page.locator("[data-testid='graph-hover-card']").innerText())
                    .describedAs("and a worker with nothing counted yet says so rather than "
                        + "claiming a number")
                    .contains("not counted yet");
                page.mouse().move(4, 4);

                // --- 4. and said in words above the graph ------------------------------------
                String band = page.locator("[data-testid='live-workers']").innerText();
                assertThat(band)
                    .contains("6 workers are writing code right now")
                    .contains("1 of them is heading for trouble");
                assertThat(page.locator("[data-testid='live-worker-warning-4']").innerText())
                    .describedAs("with the reason under the worker it is about")
                    .contains("42k").contains("33k");
                assertThat(page.locator("body").innerText())
                    .describedAs("no internal state name reaches the operator")
                    .doesNotContain("OVER_BUDGET").doesNotContain("TOOL_RESULT");

                Dialogs.settled(page);
                for (int width : new int[] {1600, 1280, 960}) {
                    page.setViewportSize(width, 950);
                    Dialogs.settled(page);
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target", "console-worker-health-" + width + ".png")));
                }
                page.setViewportSize(1600, 950);
                Dialogs.settled(page);

                // --- 5. the other way a worker dies: stuck in one long request ---------------
                // The frame is the real one, with w0's last step moved back past two thirds of
                // the fifteen minutes a request gets. This is what a worker looks like in the
                // minutes before the transport gives up on it — and the ONLY thing that changes
                // on the screen while it happens, which is why the publisher watches for it.
                var stuck = new GraphServiceImpl().snapshot(runId.toString());
                stuck.setSeq(stuck.getSeq() + 1_000_000);
                for (var candidate : stuck.getCandidates()) {
                    if (candidate.getWorkerIndex() == 0) {
                        candidate.setLastStepKind("TOOL_RESULT");
                        candidate.setLastStepAtMillis(stuck.getAtMillis()
                            - WorkerHealth.SILENT_WARN_MILLIS - 60_000);
                        // And the widest the chip's numbers can ever get, on the same frame: three
                        // digits of turns and six of tokens is "118t 8/105k", eleven characters in
                        // a chip 52 pixels wide. It is the shape of the worker that ran 121 turns
                        // to 105,717 tokens before compaction was fixed, and if a third number
                        // does not fit THAT it does not belong on a chip.
                        candidate.setTurns(118);
                        candidate.setTokens(105_000);
                    }
                }
                pageErrors.clear();
                ConsoleContext.get().push("run-graph", stuck);
                page.waitForTimeout(1500);

                assertThat(page.locator("[data-node='cand-0'][data-health='warning']").count())
                    .describedAs("a worker eleven minutes into a fifteen-minute request is "
                        + "nearly dead, and now says so")
                    .isEqualTo(1);
                assertThat(page.locator("[data-testid='live-worker-warning-0']").innerText())
                    .contains("waiting on the model").contains("15m");
                assertThat(page.locator("[data-testid='live-workers']").innerText())
                    .contains("2 of them are heading for trouble");
                assertThat(page.locator("[data-node='cand-0']").textContent())
                    .describedAs("the widest the numbers ever get, still three of them")
                    .contains("118t").contains("8/105k");
                // And it FITS. A chip is 52 pixels wide; a third number that spills over the edge
                // of its own box is worse than no third number, and nothing in a text assertion
                // would ever catch it. Measured off the rendered glyphs, not reasoned about.
                var numbers = page.locator("[data-node='cand-0'] text").nth(1).boundingBox();
                assertThat(numbers.width)
                    .describedAs("the second line of the widest chip stays inside the chip")
                    .isLessThanOrEqualTo(52.0);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-worker-health-stuck.png")));

                // --- 6. a missing number must not break the row ------------------------------
                // Nulls arrive exactly the way a field added on one side of the wire and not the
                // other does. Before the per-node guards, the first method call on any of them
                // took out the whole redraw, permanently, because a dead effect never runs again.
                var poisoned = new GraphServiceImpl().snapshot(runId.toString());
                poisoned.setSeq(poisoned.getSeq() + 2_000_000);
                for (var candidate : poisoned.getCandidates()) {
                    if (candidate.getWorkerIndex() == 1) {
                        // The exact shape of a number added on one side of the wire and not the
                        // other: the conversation is counted, the head of it is not. The chip must
                        // fall back to the two numbers it carried before this one existed, and the
                        // card must lose two rows and a sentence and nothing else.
                        candidate.setPrefillTokens(0);
                    }
                    if (candidate.getWorkerIndex() == 2) {
                        candidate.setTokens(0);
                        candidate.setContextBudgetTokens(0);
                        candidate.setLastStepKind(null);
                    }
                    if (candidate.getWorkerIndex() == 3) {
                        candidate.setState(null);
                    }
                }
                pageErrors.clear();
                ConsoleContext.get().push("run-graph", poisoned);
                page.waitForTimeout(1500);

                assertThat(page.locator("[data-node^='cand-']").count())
                    .describedAs("every chip is still drawn — a missing number costs the number, "
                        + "never the row")
                    .isEqualTo(6);
                assertThat(page.locator("[data-node='cand-2'][data-health='warning']").count())
                    .describedAs("and a worker whose numbers vanished is not thereby in trouble")
                    .isZero();
                assertThat(page.locator("[data-node='cand-1']").textContent())
                    .describedAs("a counted conversation with no measured head keeps the two "
                        + "numbers it always had, and shows no split rather than a wrong one")
                    .contains("14k")
                    .doesNotContain("/");
                page.locator("[data-node='cand-1']").hover();
                page.waitForSelector("[data-testid='graph-hover-card']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(page.locator("[data-testid='graph-hover-card']").innerText())
                    .describedAs("and its card loses the two split rows and the sentence under "
                        + "them, and keeps everything else")
                    .contains("conversation")
                    .doesNotContain("fixed start")
                    .doesNotContain("its own")
                    .doesNotContain("sends the same one");
                page.mouse().move(4, 4);
                assertThat(pageErrors)
                    .describedAs("nothing was thrown at the page")
                    .isEmpty();
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-worker-health-missing-number.png")));
            }
        }
    }
}
