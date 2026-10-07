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
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
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
 * The run graph WHILE the workers are working, and the two faults that made it useless.
 *
 * <p>The report this pins: four worker containers running at 33-70% CPU, the agent tools reporting
 * four live workers with the step each was on — and a graph showing two empty task boxes. The
 * operator has no other way to tell a working build from a hung one, and had been wrong about it
 * three times in one day.
 *
 * <p>One embedded Console per JVM, so this class holds exactly one browser test and everything is
 * asserted inside it — see the note on {@code ConsoleBacklogBrowserTest}. Runs wherever
 * Playwright's Chromium is installed; skipped, and the skip announced, where it is not.
 */
class ConsoleLiveWorkersBrowserTest {

    @TempDir
    Path storeDir;

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void workersInFlightAreVisible_andOneBadFieldDoesNotBlankTheGraph() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TraceHub hub = new TraceHub(null);
            UUID projectId = UUID.randomUUID();
            List<Project> projects = List.of(new Project(projectId, "bookshelf",
                storeDir.toString(), List.of(), Instant.now(), false));
            ConsoleContext.set(new ConsoleContext(store, hub,
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> projects, () -> projectId, (n, p, c) -> null, id -> { }));

            UUID runId = UUID.randomUUID();
            UUID taskA = UUID.randomUUID();
            UUID taskB = UUID.randomUUID();
            var graph = new TaskGraph(UUID.randomUUID(), 1, null,
                List.of(
                    new Task(taskA, 1, "Add shared Book model", "", Set.of("shared/src/main"),
                        Set.of(), List.of(), null, null, null,
                        new SwarmPolicy(4, false, 0.1, 0.8, List.of()), TaskState.DISPATCHED),
                    new Task(taskB, 1, "Implement validated BookService", "",
                        Set.of("server/src/main"), Set.of(), List.of(), null, null, null,
                        new SwarmPolicy(4, false, 0.1, 0.8, List.of()), TaskState.PENDING)),
                List.of(new TaskEdge(taskA, taskB)));
            store.append(() -> {
                store.root().taskGraphs.put(graph.id(), graph);
                Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                    projectId, null, null, graph.id(), null, Instant.now(),
                    new RunReport(runId, "a bookshelf that stores books"));
                run.setHeartbeatAt(Instant.now());
                store.root().runs.put(runId, run);
                return null;
            }).get();

            // Four workers dispatched and still going: nothing archived, nothing persisted, because
            // none of them has finished. This is the state the graph was blank in.
            double[] temperatures = {0.1, 0.33333333, 0.56666666, 0.8};
            for (int i = 0; i < 4; i++) {
                UUID sessionId = LiveWorkerSessions.open(hub, runId, taskA, i,
                    "qwen3.8-27b", temperatures[i]);
                LiveWorkerSessions.step(hub, sessionId, "exec", "mvn -q test-compile");
            }

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
                page.waitForSelector("[data-testid='phase-strip']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

                // --- 1. the operator can SEE that work is happening --------------------------
                // A chip per attempt. Playwright's text engine does not pierce <svg><text>, so
                // nodes are addressed by their data-node attribute.
                page.waitForSelector("[data-node='cand-0']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertThat(page.locator("[data-node^='cand-']").count())
                    .describedAs("one chip per worker that is running right now")
                    .isEqualTo(4);

                // …and, above the graph, the same fact in words. Chips alone were never enough:
                // four chips look identical whether the workers are building or all four hung.
                page.waitForSelector("[data-testid='live-workers']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                String band = page.locator("[data-testid='live-workers']").innerText();
                assertThat(band)
                    .describedAs("how many are working, said outright")
                    .contains("4 workers are writing code right now");
                assertThat(band)
                    .describedAs("and per worker: how long it has been going, what it is doing, "
                        + "and how long since it last did anything — the three facts that "
                        + "separate a working swarm from a stuck one")
                    .contains("w0").contains("w3")
                    .contains("working")
                    .contains("running exec")
                    .contains("last spoke");
                assertThat(page.locator("body").innerText())
                    .describedAs("no internal state name reaches the operator")
                    .doesNotContain("TOOL_CALL").doesNotContain("EXECUTING");

                Dialogs.settled(page);
                for (int width : new int[] {1600, 1280, 960}) {
                    page.setViewportSize(width, 950);
                    Dialogs.settled(page);
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target", "console-graph-live-" + width + ".png")));
                }
                page.setViewportSize(1600, 950);

                // --- 2. a keydown carrying no key must not throw -----------------------------
                // TeaVM turns a JavaScript `undefined` into a java.lang.String object whose
                // backing string is undefined: not null, so every null guard passes, and then
                // every method on it throws out of jl_String_length. The Console logged fifteen of
                // these in one afternoon from the Ctrl+K shortcut bound to the document, which
                // every keystroke anywhere in the Console goes through.
                pageErrors.clear();
                page.evaluate("() => document.documentElement.dispatchEvent("
                    + "new Event('keydown', {bubbles: true}))");
                page.waitForTimeout(300);
                assertThat(pageErrors)
                    .describedAs("a keyboard event with no key is an ordinary thing for a "
                        + "component to dispatch, and must cost nothing")
                    .isEmpty();

                // --- 3. one bad field must not take out the whole graph ----------------------
                // The frame is the real one, with fields blanked to null the way a field added on
                // one side of the wire and not the other arrives. Before this, the first method
                // call on any of them threw out of the redraw and left whatever half of the graph
                // had been painted, permanently, because a dead effect never runs again.
                var poisoned = new GraphServiceImpl().snapshot(runId.toString());
                poisoned.setSeq(poisoned.getSeq() + 1_000_000);
                poisoned.getTasks().get(0).setTitle(null);
                poisoned.getTasks().get(0).setState(null);
                poisoned.getTasks().get(0).setWriteSetCsv(null);
                poisoned.getTasks().get(1).setDependsOnCsv(null);
                poisoned.getCandidates().get(0).setState(null);
                pageErrors.clear();
                ConsoleContext.get().push("run-graph", poisoned);
                page.waitForTimeout(1500);

                assertThat(page.locator("[data-node^='task-']").count())
                    .describedAs("both tasks are still drawn — the graph does not stop at the "
                        + "node it could not read")
                    .isEqualTo(2);
                assertThat(page.locator("[data-node^='cand-']").count())
                    .describedAs("and every attempt is still drawn, including the unreadable one")
                    .isEqualTo(4);
                assertThat(pageErrors)
                    .describedAs("nothing was thrown at the page")
                    .isEmpty();
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-graph-live-with-a-null.png")));
            }
        }
    }
}
