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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.microsoft.playwright.TimeoutError;
import com.swarmcoder.console.api.BudgetsDto;
import com.swarmcoder.console.api.RoleEntryDto;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ChatMessage;
import com.swarmcoder.domain.JudgeScore;
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.domain.WorkflowKind;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.eclipse.serializer.reference.Lazy;

/**
 * True end-to-end: the TeaVM client boots in a real browser, authenticates over the binary
 * RMI WebSocket, and renders the Console views against the live services.
 *
 * <p>Runs in an ordinary build wherever Playwright's Chromium is installed — no flag. On a machine without it, it is skipped and the skip is announced (see {@code @RunsWhen}).
 *
 * <p>One embedded Console per JVM — this class holds exactly one browser test for that reason,
 * not as a style preference. zeroz4j's {@code Signals.shared(...)} binds to the first server
 * engine started in a JVM, so a second {@code Zeroz4jServer} started later in the same JVM
 * serves pages but its server-side signal {@code set()} never reaches the browser client, and
 * any signal-driven assertion times out. The sibling browser tests therefore live in their own
 * classes ({@link ConsoleBrdBrowserTest}, {@link ConsoleBacklogBrowserTest}) and Surefire's
 * {@code reuseForks=false} (see sc-console/pom.xml) gives each of them a fresh JVM. Adding a
 * second browser test here would silently reintroduce the defect.
 */
class ConsoleBrowserSmokeTest {

    @TempDir
    Path storeDir;

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        // The Console's RMI services are @Secured; the client authenticates as the dev admin.
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void consoleBootsInARealBrowser() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            List<Project> projects = new ArrayList<>();
            // A REAL git repository, not a made-up path: the shell now hides run-dependent views
            // when the project has no repository, because a Swarm Board that can never hold
            // anything is exactly the misleading thing that gating removed. This test drives those
            // views, so its project has to be one where runs are actually possible.
            Path alphaRepo = storeDir.resolve("alpha-repo");
            java.nio.file.Files.createDirectories(alphaRepo);
            new ProcessBuilder("git", "init", "-q")
                .directory(alphaRepo.toFile()).redirectErrorStream(true).start().waitFor();
            projects.add(new Project(UUID.randomUUID(), "alpha",
                alphaRepo.toString(), List.of("C:/work/zeroz4j"), Instant.now(), false));
            AtomicReference<UUID> current =
                new AtomicReference<>(projects.get(0).id());
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), runId -> { }, runId -> { })
                .withProjects(() -> projects, current::get,
                    (name, path, ctx) -> {
                        Project np = new Project(
                            UUID.randomUUID(), name, path, ctx, Instant.now(), false);
                        projects.add(np);
                        return np;
                    },
                    current::set)
                // Scripted coder so the chat surface can be driven end-to-end.
                .withChat((messages, modelOverride) -> Stream.of(
                    "Here is a plan:\n\n- add `multiply`\n- **verify** it"))
                // A Researcher double: start flips status, which the view polls to completion.
                .withResearcher(new ConsoleContext.Researcher() {
                    private volatile String status = "idle";
                    @Override
                    public String start(String topic) {
                        status = "researching (surveying the project's libraries)…";
                        // Settle quickly so the smoke test's poll observes completion.
                        new Thread(() -> {
                            try {
                                Thread.sleep(1_500);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            status = "filed 1 proposal(s): grid-lazy-loading";
                        }).start();
                        return "";
                    }
                    @Override
                    public String status() {
                        return status;
                    }
                })
                // Minimal typed-settings backend so the dialogs can be driven.
                .withConfigForms(new ConsoleContext.ConfigForms() {
                    @Override
                    public List<RoleEntryDto> globalRoles() {
                        List<RoleEntryDto> entries = new ArrayList<>();
                        for (String role : List.of("architect", "designReviewer", "testAuthor",
                                "judge", "utility", "chat", "worker0")) {
                            var dto = new RoleEntryDto();
                            dto.setRoleId(role);
                            // worker0 is configured and the named roles are not: that is the real
                            // shape of the box (one model serving the workers), and it is what
                            // makes the form's diversity notice say something worth asserting.
                            boolean worker = role.startsWith("worker");
                            dto.setBaseUrl(worker ? "http://box:8000" : "");
                            dto.setApiKey("");
                            dto.setModelName(worker ? "qwen38-flash-next" : "");
                            // The shape catalogue travels on the row, so the settings screen never
                            // holds a list of model names of its own.
                            dto.setAvailableShapes("generic-openai,qwen36-27b,qwen38-flash-next-125b");
                            entries.add(dto);
                        }
                        return entries;
                    }
                    @Override
                    public String saveGlobalRoles(List<RoleEntryDto> roles) {
                        return "";
                    }
                    @Override
                    public BudgetsDto budgets() {
                        return new BudgetsDto();
                    }
                    @Override
                    public String saveBudgets(BudgetsDto budgets) {
                        return "";
                    }
                    @Override
                    public List<RoleEntryDto> projectRoles(String projectId) {
                        return List.of();
                    }
                    @Override
                    public int projectWorkersPerTask(String projectId) {
                        return 0;   // this project inherits the global number
                    }
                    @Override
                    public String saveProjectConfig(String projectId, String contextPathsCsv,
                                                    List<RoleEntryDto> roles, int workersPerTask) {
                        return "";
                    }
                }));

            // A seeded run with a task graph + candidates so the run graph can be driven.
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            var taskGraph = new TaskGraph(UUID.randomUUID(), 1, null,
                List.of(new Task(taskId, 1, "add multiply", "",
                    Set.of("src/main/java"), Set.of(), List.of(), null, null, null,
                    new SwarmPolicy(2, false, 0.2, 0.4, List.of()),
                    TaskState.DONE)),
                List.of());
            store.append(() -> {
                store.root().taskGraphs.put(taskGraph.id(), taskGraph);
                var seededRun = new Run(runId,
                    WorkflowKind.GREENFIELD,
                    RunState.EXECUTING, null, null, null, taskGraph.id(), null,
                    Instant.now(),
                    new RunReport(runId, "seeded graph run"));
                seededRun.setProjectId(projects.get(0).id()); // sidebar lists are project-scoped
                store.root().runs.put(runId, seededRun);
                // Knowledge docs (store-first objects): one ACTIVE, one mined proposal.
                var activeDoc = new KnowledgeDoc(UUID.randomUUID(),
                    projects.get(0).id(), "conventions", "Conventions",
                    "Always use signals.", "ACTIVE", "human",
                    Instant.now(), Instant.now());
                var proposedDoc = new KnowledgeDoc(UUID.randomUUID(),
                    projects.get(0).id(), "di-pattern", "DI pattern",
                    "Constructor injection only.", "PROPOSED", "extraction",
                    Instant.now(), Instant.now());
                store.root().knowledgeDocs().put(activeDoc.id(), activeDoc);
                store.root().knowledgeDocs().put(proposedDoc.id(), proposedDoc);
                for (int i = 0; i < 3; i++) {
                    store.root().candidateArchives.put(UUID.randomUUID(),
                        Lazy.Reference(
                            new CandidateSolution(UUID.randomUUID(), taskId, i,
                                "swarm/x/" + i,
                                new SamplingConfig("qwen36-27b", 0.2 + i * 0.2, 0, "p", "s"),
                                "diff", null, null,
                                i == 0 ? new JudgeScore(1.0, "best", "judge") : null,
                                i == 0 ? CandidateState.SELECTED
                                       : CandidateState.FAILED, null)));
                    // A persisted session per candidate — the replay timeline's lanes.
                    var opened = Instant.now().minusSeconds(300 - i * 60L);
                    UUID sessionId = UUID.randomUUID();
                    store.root().agentSessions().put(sessionId,
                        Lazy.Reference(
                            new AgentSessionRecord(sessionId, runId, taskId,
                                null, i, "worker-" + i, "qwen36-27b", 0.2 + i * 0.2,
                                opened, opened.plusSeconds(45), "COMPLETED", null, 4, 900,
                                List.of(new TraceEvent(0, opened.plusSeconds(5),
                                        TraceEventKind.TOOL_CALL, "exec", "mvn", null, 50),
                                    new TraceEvent(1, opened.plusSeconds(30),
                                        TraceEventKind.LLM_RESPONSE, "", "done", null, 200)))));
                }
                return null;
            }).get();

            try (com.zeroz4j.server.Zeroz4jServer server = com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                // Live push (chat-events, chat-stream, run-graph) travels over the RMI engine's
                // broadcast, and the sink is wired by whoever embeds the Console — in production
                // DependencyGraph.startConsole(). This test starts a bare Zeroz4jServer, so it must
                // do the same wiring or ConsoleContext.push(...) is a no-op and nothing the server
                // emits ever reaches the browser.
                ConsoleContext.get().setPushSink(
                    jakarta.enterprise.inject.spi.CDI.current()
                        .select(com.zeroz4j.server.WasmRmiServerEngine.class).get()::broadcastPush);

                List<String> pageErrors = new ArrayList<>();
                List<String> consoleLog = new ArrayList<>();
                Page page = browser.newPage();
                page.onPageError(pageErrors::add);
                page.onConsoleMessage(msg -> consoleLog.add(msg.type() + ": " + msg.text()));

                BiConsumer<String, Integer> await = (selector, timeout) -> {
                    try {
                        page.waitForSelector(selector,
                            new Page.WaitForSelectorOptions().setTimeout(timeout));
                    } catch (TimeoutError e) {
                        page.screenshot(new Page.ScreenshotOptions()
                            .setPath(Path.of("target", "console-FAILED.png")));
                        throw new AssertionError("never appeared: " + selector
                            + "\npageErrors=" + pageErrors
                            + "\nconsole(last 25)=" + consoleLog.subList(
                                Math.max(0, consoleLog.size() - 25), consoleLog.size()), e);
                    }
                };

                page.setViewportSize(1600, 950);
                page.navigate("http://localhost:" + server.port() + "/");
                // The main view mounts only after WS connect + auth + RMI stubs are live. The status
                // header is the shell's whole navigation now, so its presence is what "mounted" means.
                await.accept("[data-testid='status-header']", 30_000);
                // Which project you are in is a header chip, not a 16rem rail on every screen (§8.4) —
                // but the NAME still has to be permanently visible, because everything is scoped to it.
                await.accept("[data-testid='project-menu'] >> text=alpha", 10_000);

                // TWO workspaces, and no numbered stepper. The four-step bar is deleted (§6): it drew a
                // progress front across a process that is not sequential, so it could either lie about
                // doneness or refuse to tick anything off, and it did both in turn.
                for (String workspace : List.of("requirements", "plan")) {
                    assertThat(page.locator("[data-testid='stage-" + workspace + "']").isVisible())
                        .describedAs("%s must have a workspace tab", workspace).isTrue();
                }
                for (String offPath : List.of("setup", "build")) {
                    assertThat(page.locator("[data-testid='stage-" + offPath + "']").count())
                        .describedAs("%s is reachable but is not a step, so it gets no tab", offPath)
                        .isZero();
                }
                // The counts, which are the reason the header row exists: what is in this project,
                // answered before the operator has clicked anything.
                assertThat(page.locator("[data-testid='header-counts']").textContent())
                    .contains("Requirements:").contains("Pipeline:");
                // And ONE guidance line, never one per stage.
                assertThat(page.locator("[data-testid='guidance']").count())
                    .describedAs("one next step, not one per workspace").isEqualTo(1);

                // Chat end-to-end: create a chat, send a message, the scripted coder's
                // markdown reply must render as a bubble — RMI, push, signals, MarkdownView.
                // Chat is a companion in EVERY stage, so "New chat" lives in the shell's top bar
                // and opens the dock over whichever stage is showing.
                page.click("[title='New chat']");
                // By testid, not by tag: "the textarea" was unique only while chat was a tab that
                // replaced everything else. It is a companion pane now, sharing the screen with a
                // stage that has textareas of its own.
                await.accept("[data-testid='chat-composer']", 10_000);
                page.fill("[data-testid='chat-composer']", "how should we add multiply?");
                page.press("[data-testid='chat-composer']", "Enter");
                await.accept("text=Here is a plan:", 15_000);
                assertThat(page.content()).contains("how should we add multiply?");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-chat.png")));

                // --- a build's question, seen from the chat (UX v3 2.3, 9 step 4) -------------
                // The chat is the companion dock, never a place work has to be done. This card
                // used to carry three buttons: Approve and Reject, drawn only when the message
                // text happened to contain the word APPROVAL — a Java constant printed into a
                // sentence a person reads, gating controls on a state this design deleted, so they
                // could never appear at all — and Resolve, which wrote the fixed words "approved
                // from chat" into the record having asked the operator nothing. 1's table names
                // that button as one of the six original diseases.
                //
                // The card now says what happened and points at the story, where the question has
                // its brief and a box to answer it in. Two ways to answer one question, free to
                // disagree, is defect D3.
                UUID chatId = store.listChats(projects.get(0).id()).get(0).id();
                ChatMessage question = store.appendChatMessage(new ChatMessage(
                    UUID.randomUUID(), chatId, 0, "SYSTEM", "DECISION",
                    "Task 3 could not be finished: every candidate failed the same test.",
                    UUID.randomUUID(), UUID.randomUUID(), Instant.now(), 0));
                ConsoleContext.get().push("chat-events", question);
                await.accept("text=A build stopped to ask you something", 10_000);
                // Not Dialogs.shotAtEveryWidth: that waits on an open modal and this is the
                // companion dock, which is not one. The dock keeps its own width, but the shell
                // around it does not, so the card is looked at in all three.
                for (int width : new int[] {1600, 1280, 960}) {
                    page.setViewportSize(width, 950);
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target", "console-chat-decision-" + width + ".png")));
                }
                page.setViewportSize(1600, 950);
                assertThat(page.locator("[data-testid='chat-decision']").innerText())
                    .describedAs("the question is answered on the story's card, and the chat says "
                        + "so instead of offering a second way to answer it")
                    .contains("A build stopped to ask you something")
                    .contains("Show me the story")
                    .doesNotContain("Approve")
                    .doesNotContain("Reject")
                    .doesNotContain("Resolve")
                    .doesNotContain("APPROVAL");

                // What is read ABOUT the work — approvals owed, the guidelines workers obey, insights
                // — lives behind the ⋯ overflow: present, never on the main path (§3). The work itself
                // moved to the pipeline board, because a story crossing from planning to building used
                // to vanish from one screen and reappear on another, and because each screen decided
                // liveness for itself and the two disagreed.
                page.click("[data-testid='overflow-menu']");
                await.accept("[data-testid='overflow-guidelines']", 10_000);
                assertThat(page.content()).contains("Guidelines").contains("Insights");
                // And NOT Approvals: the Approval Center is retired as a destination (UX v3 2.3/6).
                // A question a build stopped to ask is on the card of the story it belongs to, with
                // the button that answers it — keeping the queue as well would be two places to
                // answer one question, and the queue was the worse of the two because it listed run
                // ids rather than stories.
                assertThat(page.locator("[data-testid='overflow-approvals']").count())
                    .describedAs("the Approval Center must not be a destination any more")
                    .isZero();
                page.click("[data-testid='overflow-guidelines']");
                await.accept("text=Guidelines", 10_000);

                // Insights, opened rather than merely listed. Every part of this screen is now a
                // framework component — the tiles, the scatter and the two tables — where it used
                // to be hand-drawn SVG, so "it compiles" says nothing about whether it draws. The
                // seeded store has one session and no candidates, which is also the state the
                // screen most often opens in.
                page.click("[data-testid='overflow-menu']");
                await.accept("[data-testid='overflow-insights']", 10_000);
                page.click("[data-testid='overflow-insights']");
                await.accept("text=Temperature vs survival", 15_000);
                String insights = page.locator("body").innerText();
                assertThat(insights)
                    .describedAs("the six tiles carry their labels and their figures — the seeded "
                        + "store holds one run, three candidates and one selected")
                    .contains("RUNS").contains("CANDIDATES").contains("SURVIVAL")
                    .contains("SELECTED").contains("SESSIONS").contains("TOKENS")
                    .contains("33%");
                assertThat(insights)
                    .describedAs("the chart drew its own axes and named the model family it "
                        + "plotted, neither of which this screen computes any more")
                    .contains("0 %").contains("100 %")
                    .contains("temperature").contains("survived")
                    .contains("qwen36-27b");
                assertThat(insights)
                    .describedAs("both tables are drawn, with their headings, their column "
                        + "headers and their rows")
                    .contains("Survival by model family").contains("dispatched")
                    .contains("Kill reasons").contains("no data");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-insights.png")));

                // Run graph end-to-end, from the pipeline, where builds live.
                page.click("[data-testid='stage-plan']");
                await.accept("[data-testid='pipeline-board']", 10_000);
                // A run started outside the backlog still has a card, in BUILDING — it has no story
                // to be an attempt at, and a board built only from stories would have dropped it
                // silently, which is the same disappearance rule 1 forbids.
                await.accept("[data-testid='pipeline-col-building'] >> text=seeded graph run", 10_000);
                // "Open the run", not the card itself: the graph is for understanding rather than
                // acting, so it is the quiet control — and it opens OVER the board rather than
                // navigating, so the operator never loses the column they were reading.
                await.accept("[data-testid='open-run']", 10_000);
                page.locator("[data-testid='open-run']").first().click();
                // "writing the code", not EXECUTING. This label was the last place in the product where
                // a RunState reached the operator verbatim (UX v3 4), and it is the same phrase the
                // card that opened this graph uses — both read BuildState.phrase, so the graph and the
                // card cannot describe one phase two ways.
                await.accept("text=writing the code", 10_000);
                // On the VISIBLE text, not the markup: a data- attribute or a library component's
                // internal state token is a contract, and §4 bans internal names from what the
                // operator READS. innerText is that distinction, expressed as a selector.
                assertThat(page.locator("body").innerText())
                    .describedAs("no internal state name reaches the operator")
                    .doesNotContain("EXECUTING").doesNotContain("TEST_AUTHORING");
                // SVG nodes: Playwright's text engine does not pierce <svg><text>, so nodes
                // carry data-node attributes; the raw markup still proves the labels render.
                await.accept("[data-node='cand-0']", 10_000);
                assertThat(page.content()).contains("add multiply").contains("w0 ♛");
                page.click("[data-node='cand-0']");
                await.accept("text=judge score", 10_000);
                // Escape closes it, and that is worth a line of its own. A drill-down from inside
                // the graph is a dialog over it since ZeroZ Stack 0.8.0 — nothing outside a native
                // dialog can be clicked, so a side panel beside one is unreachable by construction
                // — and a dialog over the graph has to be dismissed before the graph answers
                // again. Escape is how, and before 0.8.0 Escape did nothing to any dialog here.
                page.keyboard().press("Escape");
                page.waitForSelector("[data-testid='inspector-dialog']",
                    new Page.WaitForSelectorOptions()
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN)
                        .setTimeout(5_000));
                // The phase strip: six phases, in order, each shown once. DESIGN and DESIGN_REVIEW
                // share one phrase and collapse to one step, so six run states make six steps and
                // a seventh would mean the dedupe had broken.
                assertThat(page.locator("[data-testid='phase-strip'] > li").count())
                    .describedAs("one step per phase the operator can distinguish")
                    .isEqualTo(6);
                assertThat(page.locator("[data-testid='phase-strip']").innerText()
                        .replace("\n", " "))
                    .describedAs("the phases read left to right in the order a build goes "
                        + "through them")
                    .containsSubsequence("reading the story", "working out a design",
                        "breaking it into tasks", "writing the tests it must pass",
                        "writing the code", "putting the pieces together");
                // The run graph is a dialog, and one of the eleven that used to reach past the
                // component's own API to set their width. Settled first, then three window sizes.
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-graph.png")));
                Dialogs.shotAtEveryWidth(page, "console-graph",
                    // The pin for the overflow this strip shipped with. Checked at 1600 as well as
                    // at 960: it was broken at every width, so a narrow-window-only check would
                    // have gone on passing.
                    () -> Dialogs.assertNothingOverflows(
                        page, "[data-testid='phase-strip']", "the build's phase strip"));

                // Replay (C2-4): the timeline opens with a lane per persisted session.
                //
                // Both faults this block used to describe are gone, and both are now PINNED here
                // rather than excused.
                //
                // THE FRAMEWORK'S. In zerozstack-ui-components 0.7.0 the class-file constant
                // behind the "▶ 1×" button was SIXTY-FOUR bytes where a correct one is
                // seven: the text had been round-tripped between UTF-8 and Windows-1252 three
                // times, in the compiled class and not only in the sources, so it was what shipped
                // and what the browser drew. Eight strings were affected across LaneTimeline (the
                // three speed buttons, the pause glyph, the ellipsis its truncation appends),
                // DiffView, PropertyGrid and StreamingText. 0.8.0 repairs all eight; the speed
                // buttons are therefore matched on their real labels now, which is the assertion
                // that fails if a corrupt one ever ships again.
                //
                // OURS. LaneTimeline cut a lane label at twelve characters, so this had been
                // reduced to passing the worker's role alone — "worker-0 qwen3.8-27b" arrived
                // as "worker-0 qw" plus an ellipsis. 0.8.0 measures the column from the longest
                // name, so the model is back on the row and asserted below.
                page.click("text=Replay");
                await.accept("text=REPLAY", 10_000);
                await.accept("button:has-text('16')", 5_000);
                assertThat(page.content()).contains("worker-0").contains("worker-2");
                assertThat(page.locator("button:has-text('16')").textContent())
                    .describedAs("the speed buttons read as the glyphs they were written as; "
                        + "eight strings in this component shipped mis-encoded in 0.7.0")
                    .isEqualTo("▶ 16×");
                assertThat(page.locator("[data-testid='replay-timeline'] svg").textContent())
                    .describedAs("a worker row reads as the whole worker's name AND the model it "
                        + "wrote with, with nothing truncated and nothing mis-decoded")
                    .contains("worker-0").contains("worker-2")
                    .doesNotContain("Ã").doesNotContain("€")
                    .doesNotContain("…");
                page.click("button:has-text('16')");
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-replay.png")));

                // Close it before going anywhere else. The graph opens as a modal ON PURPOSE — it
                // keeps the operator's place in the list behind it — and a modal blocks the page
                // while it is up, which is exactly what a modal is for. Asserting the close works
                // is worth a line: an unclosable one would trap the whole Console.
                // Scoped to the OPEN modal. Every dialog in the Console has a Close, and they stay in
                // the document while closed -- the chat composer's upload window is mounted with the
                // composer and merely hidden -- so a bare "text=Close" resolves to whichever one is
                // first in the DOM and waits for ever on an element nobody can see.
                page.click("dialog.modal.modal-open button:text-is('Close')");
                await.accept("[data-testid='open-run']", 10_000);

                // Settings dialogs (C2-1). Both entry points moved with the navigation collapse and
                // the test follows them rather than the other way round: global settings are a row in
                // the ⋯ overflow, and the per-project ones are in the project menu behind the header
                // chip — which is where a 16rem permanent rail's contents went (§8.4).
                page.click("[data-testid='overflow-menu']");
                page.click("[data-testid='overflow-settings']");
                await.accept("text=add worker family", 10_000);
                assertThat(page.content()).contains("architect").contains("designReviewer");
                // With one worker model configured, the form must SAY that every worker will run
                // the same model — the "split across families" switch has nothing to split across
                // and used to do nothing silently.
                assertThat(page.content()).contains("SAME model");
                // Each row folds open into that model's own settings. Everything in there was a
                // hardcoded constant or a JVM-wide switch until 2026-08.
                page.click("text=model settings >> nth=0");
                await.accept("text=Memory per token, bytes", 10_000);
                assertThat(page.content()).contains("qwen38-flash-next-125b");
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-settings.png")));
                Dialogs.shotAtEveryWidth(page, "console-settings");
                page.click("dialog.modal.modal-open button:text-is('Close')");
                page.click("[data-testid='project-menu']");
                await.accept("[title='Project settings']", 10_000);
                // Opening this retires the menu behind it — two stacked modals would leave the outer
                // one blocking every click after the inner one closed, which reads as a frozen page.
                page.click("[title='Project settings']");
                await.accept("text=Model overrides", 10_000);
                Dialogs.settled(page);
                Dialogs.shotAtEveryWidth(page, "console-project-settings");
                page.click("text=Cancel");

                // Knowledge editor (store-first objects): list renders with the proposal
                // badged, opening an entry loads it, accepting promotes it to ACTIVE.
                //
                // Knowledge is no longer a destination in a menu — it is reference material the
                // analyst reads WHILE writing requirements, so it is a pane on the Requirements
                // stage, collapsed to a labelled edge. Selecting the stage and clicking the edge is
                // what the operator does, and it is what the test does.
                page.click("[data-testid='stage-requirements']");
                // The workspace opens on the LIST, not the canvas (REQUIREMENTS_AT_SCALE_DESIGN §3.3),
                // so this waits for the tree. The graph's own header is behind the "Graph" tab now and
                // waiting for it here would time out on an element that is merely hidden — which is a
                // real distinction: the graph is one click away, not gone.
                await.accept("[data-testid='requirements-tree']", 10_000);
                // Quoted = exact text. Unquoted, "Knowledge" first matches a sentence inside the
                // hidden Setup stage — stages are kept alive hidden rather than rebuilt, so a loose
                // text selector can resolve to a stage that is not on screen.
                page.click("text=\"Knowledge\"");
                await.accept("text=Conventions", 10_000);
                await.accept("text=proposed", 5_000);
                page.click("text=Conventions");
                // A textarea's value is a property, not DOM text — poll it directly.
                long editorDeadline = System.currentTimeMillis() + 10_000;
                while (!page.inputValue("[data-testid='knowledge-editor']").contains("Always use signals.")) {
                    if (System.currentTimeMillis() > editorDeadline) {
                        throw new AssertionError("knowledge editor never loaded the doc; value="
                            + page.inputValue("[data-testid='knowledge-editor']"));
                    }
                    Thread.sleep(200);
                }
                page.hover("text=DI pattern");
                page.click("[title='Accept — the entry starts feeding briefs']");
                // The amber badge disappears once the doc is ACTIVE ("text=proposed" would
                // also match the editor's PROPOSED hint text — poll the badge class).
                long acceptDeadline = System.currentTimeMillis() + 10_000;
                while (page.locator(".badge-warning").count() > 0) {
                    if (System.currentTimeMillis() > acceptDeadline) {
                        throw new AssertionError("proposal never promoted to ACTIVE"
                            + "\nbadges=" + page.locator(".badge-warning").allTextContents()
                            + "\nstoreStatuses=" + store.root().knowledgeDocs().values().stream()
                                .map(d -> d.slug() + "=" + d.status()).toList()
                            + "\npageErrors=" + pageErrors
                            + "\nconsole(last 15)=" + consoleLog.subList(
                                Math.max(0, consoleLog.size() - 15), consoleLog.size()));
                    }
                    Thread.sleep(200);
                }
                // Researcher: the Research button starts a mission and the status line
                // reports its outcome.
                page.click("[title='Research — the agent scours the web + docs and files proposals']");
                await.accept("text=grid-lazy-loading", 15_000);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-knowledge.png")));

                // (The BRD editor has its own focused end-to-end test: ConsoleBrdBrowserTest.)

                // Plan: ONE board, whose columns are the decisions the operator owes each story,
                // with the read-only requirements reference collapsed to a labelled edge beside it.
                // By testid rather than by heading text — the heading is now the exact word
                // "Backlog", which as a loose text selector also matches prose in hidden stages.
                page.click("[data-testid='stage-plan']");
                // The board itself, not a column: this project has no stories, and an empty backlog
                // deliberately shows the way in rather than four empty columns.
                await.accept("[data-testid='pipeline-board']", 10_000);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-plan.png")));

                // Visual artifact for review: the shell with the pipeline on screen, which is where an
                // operator actually spends their time.
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-shell.png")));

                // The developer toggle (author decision 2026-07-27): Prompt Lab and Components are
                // off the operator's navigation entirely until Settings says otherwise, and the
                // preference is client-side like the theme.
                // Not on the tab bar — nothing but the two workspaces ever is — and not in the ⋯
                // overflow either until the toggle says so.
                assertThat(page.locator("[data-testid='stage-gallery']").count())
                    .describedAs("a developer tool is never a workspace tab")
                    .isZero();
                page.click("[data-testid='overflow-menu']");
                assertThat(page.locator("[data-testid='overflow-components']").count())
                    .describedAs("developer tools must be off the navigation by default")
                    .isZero();
                page.click("[data-testid='overflow-settings']");
                page.click("text=Advanced & developer");
                await.accept("[data-testid='devtools-toggle']", 10_000);
                page.click("[data-testid='devtools-toggle']");
                page.click("dialog.modal.modal-open button:text-is('Close')");
                // …and now they are, in the overflow, where a tool belongs: reachable, off the path.
                page.click("[data-testid='overflow-menu']");
                await.accept("[data-testid='overflow-components']", 10_000);
                page.click("[data-testid='overflow-components']");
                page.waitForSelector("text=StatusDot",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-gallery.png"))
                    .setFullPage(false));

                assertThat(pageErrors).isEmpty();
            }
        }
    }
}
