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
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.JudgeScore;
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
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three dialogs no browser test had ever walked to: <b>New project</b>, the <b>chat's document
 * window</b>, and the <b>transcript's payload window</b>.
 *
 * <p>Written after a report that said, honestly, that these were "expected to be fine" but that
 * nobody had a picture of any of them. One of the three was not fine: clicking an event in a
 * session transcript built a dialog, filled it, and called {@code open()} on it without ever
 * putting it in the page, so nothing happened — silently, with no error anywhere. See
 * {@code TranscriptPane.showOverThePage}. It had survived because looking at a screen is the only
 * way to find that class of fault and nothing had ever looked.
 *
 * <p>One embedded Console per JVM — this class holds exactly one browser test for that reason, not
 * as a style preference. zeroz4j's {@code Signals.shared(...)} binds to the first server engine
 * started in a JVM, so a second one started later serves pages but its signal {@code set()} never
 * reaches the browser. Surefire's {@code reuseForks=false} (sc-console/pom.xml) gives every browser
 * test class a fresh JVM; adding a second browser test to this class would silently break it.
 *
 * <p>Runs in an ordinary build wherever Playwright's Chromium is installed — no flag. On a machine without it, it is skipped and the skip is announced (see {@code @RunsWhen}).
 */
class ConsoleDialogsBrowserTest {

    @TempDir
    Path storeDir;

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        // The Console's RMI services are @Secured; the client authenticates as the dev admin.
        System.setProperty("zeroz.security.mode", "dev");
    }

    /**
     * A worker's answer, long enough that the window holding it has a layout worth photographing.
     *
     * <p>Deliberately not a lorem-ipsum block: markdown headings, a list and a fenced code block are
     * the three things {@code MarkdownView} has to get right inside a modal, and a wall of one
     * paragraph proves none of them.
     */
    private static final String LONG_ANSWER = """
        ## What I changed

        `multiply` was missing from the calculator, so I added it beside `add` and gave it the
        same overflow guard the existing operation already had.

        - `Calculator.multiply(int, int)` — the operation itself
        - `CalculatorTest#multipliesTwoPositives` — the check it must pass
        - `CalculatorTest#refusesOverflow` — the edge the guard exists for

        ```java
        public int multiply(int a, int b) {
            long result = (long) a * (long) b;
            if (result > Integer.MAX_VALUE || result < Integer.MIN_VALUE) {
                throw new ArithmeticException("multiply overflowed");
            }
            return (int) result;
        }
        ```

        Both checks pass. Nothing else in the module changed.
        """;

    @Test
    @RunsWhen(Need.CHROMIUM)
    void theThreeDialogsNobodyHadEverPhotographed() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            List<Project> projects = new ArrayList<>();
            Path alphaRepo = storeDir.resolve("alpha-repo");
            java.nio.file.Files.createDirectories(alphaRepo);
            new ProcessBuilder("git", "init", "-q")
                .directory(alphaRepo.toFile()).redirectErrorStream(true).start().waitFor();
            UUID projectId = UUID.randomUUID();
            projects.add(new Project(projectId, "alpha", alphaRepo.toString(),
                List.of("C:/work/zeroz4j"), Instant.now(), false));

            List<String> created = new ArrayList<>();
            List<String> detected = new ArrayList<>();
            List<String> saved = new ArrayList<>();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), runId -> { }, runId -> { })
                .withProjects(() -> projects, () -> projectId,
                    (name, path, ctx) -> {
                        created.add(name + " @ " + path);
                        Project np = new Project(UUID.randomUUID(), name, path, ctx,
                            Instant.now(), false);
                        projects.add(np);
                        return np;
                    },
                    id -> { })
                .withChat((messages, modelOverride) -> Stream.of("Nothing to say."))
                // The on-ramp bridge. Answers as sc-app's real one does; the detection itself is
                // proven by ToolchainDetectorTest, and what a browser can prove is the screen.
                .withBuildContracts(new ConsoleContext.BuildContracts() {
                    @Override
                    public com.swarmcoder.console.api.BuildContractDto detect(String path,
                                                                              boolean probe) {
                        detected.add(path + " probe=" + probe);
                        var dto = new com.swarmcoder.console.api.BuildContractDto();
                        dto.setToolchain("maven");
                        dto.setEvidence("pom.xml at the repository root");
                        dto.setWarnings("This is a multi-module Maven build, so a command at the "
                            + "root builds every module.");
                        dto.setCommands("compile: mvn -B -q test-compile\nexisting: mvn -B test\n");
                        dto.setAcceptanceTestDir("src/test/java/swarm/accept");
                        dto.setYaml("toolchain: maven\ncompile:\n  - \"mvn -B -q test-compile\"\n");
                        dto.setProbed(true);
                        dto.setCompiles(true);
                        dto.setProbeSeconds(41);
                        dto.setProbeVerdict("The project compiles with these commands.");
                        return dto;
                    }

                    @Override
                    public String save(String path, String yaml, boolean overwrite) {
                        saved.add(path);
                        return "";
                    }
                }));

            // A run with one task, one candidate and one persisted session, so the run graph can be
            // opened, a candidate inspected, its transcript listed and one of its events clicked.
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            var taskGraph = new TaskGraph(UUID.randomUUID(), 1, null,
                List.of(new Task(taskId, 1, "add multiply", "",
                    Set.of("src/main/java"), Set.of(), List.of(), null, null, null,
                    new SwarmPolicy(1, false, 0.2, 0.4, List.of()),
                    TaskState.DONE)),
                List.of());
            UUID sessionId = UUID.randomUUID();
            UUID candidateId = UUID.randomUUID();
            Instant opened = Instant.now().minusSeconds(300);
            store.append(() -> {
                store.root().taskGraphs.put(taskGraph.id(), taskGraph);
                var seededRun = new Run(runId, WorkflowKind.GREENFIELD,
                    RunState.EXECUTING, projectId, null, null, taskGraph.id(), null, Instant.now(),
                    new RunReport(runId, "seeded graph run"));
                store.root().runs.put(runId, seededRun);
                store.root().candidateArchives.put(UUID.randomUUID(),
                    Lazy.Reference(new CandidateSolution(candidateId, taskId, 0,
                        "swarm/x/0",
                        new SamplingConfig("qwen36-27b", 0.2, 0, "p", "s"),
                        "diff", null, null,
                        new JudgeScore(1.0, "best", "judge"),
                        CandidateState.SELECTED, null)));
                // The session names the candidate it was run FOR. Without that link the graph hands
                // the candidate an empty session id and the inspector offers no transcript at all —
                // which is what the existing smoke-test fixture does, and why its candidate panel
                // reads "No persisted session for this candidate."
                store.root().agentSessions().put(sessionId,
                    Lazy.Reference(new AgentSessionRecord(sessionId, runId, taskId,
                        candidateId, 0, "worker-0", "qwen36-27b", 0.2,
                        opened, opened.plusSeconds(45), "COMPLETED", null, 4, 900,
                        List.of(
                            new TraceEvent(0, opened.plusSeconds(5),
                                TraceEventKind.TOOL_CALL, "exec", "mvn -q test", null, 50),
                            // The one the test clicks. LLM_RESPONSE renders as markdown, which is
                            // the path the window exists for.
                            new TraceEvent(1, opened.plusSeconds(30),
                                TraceEventKind.LLM_RESPONSE, "", LONG_ANSWER, null, 200)))));
                return null;
            }).get();

            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(0, "Test Console");
                 Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium()
                     .launch(new BrowserType.LaunchOptions().setHeadless(true))) {

                // Without this the server's push is a no-op and the run graph never arrives.
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
                // Wait for the shell to know it has a project before photographing anything over
                // it. Until the readiness signal lands, every stage past Setup shows its "needs a
                // project" gate, and a dialog shot over that gate is a picture of a Console still
                // booting rather than of the one an operator works in.
                page.click("[data-testid='stage-requirements']");
                page.waitForSelector("[data-testid='requirements-tree']",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));

                // --- 1. New project ---------------------------------------------------------
                // Two modals deep: the project switcher is itself a dialog, and "New project" is
                // opened from inside it. ProjectMenu.mount retires the switcher on the way, which
                // is the thing worth having a picture of — an outer modal left blocking the page
                // behind a closed inner one reads as a frozen Console.
                page.click("[data-testid='project-menu']");
                // By the "New project" control, not by the word "Projects": stages are kept alive
                // hidden rather than rebuilt, so a loose text selector resolves to prose on a stage
                // that is not on screen -- here, three times over.
                page.waitForSelector("[title='New project']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[title='New project']");
                // By the LABEL. Every box in this form used to be named by its placeholder,
                // which meant the form stopped saying what it wanted the moment anything was typed
                // into it. The placeholder now carries an example instead.
                // has-text, not text-is: a caption is <span>words</span><span hidden> *</span>,
                // the second being the required marker the framework builds whether it is shown or
                // not, so the label's exact text is never just the caption.
                page.waitForSelector("label:has-text('The folder holding the code')",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("dialog.modal.modal-open").count())
                    .describedAs("the switcher stands down when it opens a dialog of its own — "
                        + "two stacked modals leave the page blocked behind the closed one")
                    .isEqualTo(1);
                // A label is only a label if it is bound to its box: clicking the words must put
                // the cursor in the field, and a screen reader must be able to announce the field
                // by name. Checked at all three widths, with the picture taken at each.
                Dialogs.shotAtEveryWidth(page, "console-new-project", () ->
                    assertThat(page.evaluate(
                        "() => [...document.querySelectorAll('dialog.modal.modal-open label')]"
                        + ".every(l => l.htmlFor && document.getElementById(l.htmlFor))"))
                        .describedAs("every label names the field it stands over")
                        .isEqualTo(Boolean.TRUE));

                // The fault this replaces: type into the folder box and the box stopped saying what
                // it was. Fill all three in and the form must still name every one of them.
                String pathField = "dialog.modal.modal-open input#"
                    + page.getAttribute("label:has-text('The folder holding the code')", "for");
                page.fill(pathField, "somewhere");
                assertThat(page.locator("dialog.modal.modal-open").textContent())
                    .describedAs("a filled-in form still says what each box is for")
                    .contains("Project name").contains("The folder holding the code")
                    .contains("Other folders to read");
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-new-project-typed.png")));
                // Emptied again, because the next thing this test does is prove Create refuses
                // when the folder is blank.
                page.fill(pathField, "");
                // It refuses to create a project with no folder, and says so where the operator is
                // looking rather than closing on them.
                page.click("dialog.modal.modal-open >> text=Create");
                page.waitForSelector("text=The folder holding the code is required",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-new-project-refused.png")));
                assertThat(created).describedAs("nothing was registered").isEmpty();

                // --- 1b. the on-ramp, on the same screen -------------------------------------
                // Without a verification contract every candidate comes back unverified, so the
                // swarm picks between attempts nothing tested. This is where an existing project
                // answers that, and until now nothing had looked at it.
                page.fill(pathField, alphaRepo.toString());
                page.click("dialog.modal.modal-open >> text=Check how to build it");
                page.waitForSelector("text=This looks like a maven project",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(detected)
                    .describedAs("the folder in the box is the folder it asked about, and it "
                        + "really ran the build rather than only reading files")
                    .containsExactly(alphaRepo + " probe=true");
                String panel = page.textContent("dialog.modal.modal-open");
                assertThat(panel)
                    .describedAs("the operator must see the commands, the evidence, the cost and "
                        + "the open questions before a run depends on any of it")
                    .contains("mvn -B -q test-compile")
                    .contains("pom.xml at the repository root")
                    .contains("41 seconds")
                    .contains("multi-module");
                assertThat(page.locator("dialog.modal.modal-open textarea").count())
                    .describedAs("and be able to correct it — a wrong command costs a whole run")
                    .isGreaterThan(0);
                Dialogs.shotAtEveryWidth(page, "console-new-project-build", () ->
                    assertThat(page.locator("dialog.modal.modal-open").count()).isEqualTo(1));

                page.click("dialog.modal.modal-open >> text=Save these commands");
                page.waitForSelector("text=Saved into that folder",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(saved).containsExactly(alphaRepo.toString());

                page.click("dialog.modal.modal-open >> text=Cancel");

                // --- 2. the chat's document window -------------------------------------------
                // Chat is a companion pane in every stage rather than a destination, and it starts
                // closed, so a chat has to exist before its composer — and the paperclip on it —
                // is anywhere on screen.
                page.click("[title='New chat']");
                page.waitForSelector("[data-testid='chat-composer']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

                // --- the message box says what it is, and the words fit in it -----------------
                // It used to hold a 93-character sentence documenting three features. The box is
                // one line tall, so the sentence wrapped and was cut off at the bottom edge at
                // every window width — what could actually be read stopped mid-sentence, at
                // "Ctrl+V to". Measured, not eyeballed: the placeholder is laid out in the box's
                // own font and its width compared with the room the box has for it.
                for (int width : new int[] {1600, 1280, 960}) {
                    page.setViewportSize(width, 950);
                    Object overflow = page.evaluate(
                        "() => {"
                        + "  const box = document.querySelector('[data-testid=\"chat-composer\"]');"
                        + "  const style = getComputedStyle(box);"
                        + "  const ruler = document.createElement('span');"
                        + "  ruler.style.font = style.font;"
                        + "  ruler.style.letterSpacing = style.letterSpacing;"
                        + "  ruler.style.whiteSpace = 'pre';"
                        + "  ruler.style.position = 'absolute';"
                        + "  ruler.style.visibility = 'hidden';"
                        + "  ruler.textContent = box.getAttribute('placeholder');"
                        + "  document.body.appendChild(ruler);"
                        + "  const words = ruler.getBoundingClientRect().width;"
                        + "  ruler.remove();"
                        + "  const room = box.clientWidth"
                        + "    - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);"
                        + "  return words <= room ? '' : Math.round(words) + 'px of placeholder in '"
                        + "    + Math.round(room) + 'px of box';"
                        + "}");
                    assertThat(String.valueOf(overflow))
                        .describedAs("at " + width + "px the message box's own prompt must fit on "
                            + "the one line it has, or it is clipped and reads as a half sentence")
                        .isEmpty();
                }
                page.setViewportSize(1600, 950);
                // The three things the box can do have moved out of the placeholder and under the
                // composer, where they stay put while you type — which is when they are of any
                // use — and where they are free to wrap.
                assertThat(page.locator("[data-testid='chat-composer-hints']").textContent())
                    .describedAs("the features are explained where they can be read")
                    .contains("Type / to pick a command")
                    .contains("point at a file")
                    .contains("paste a screenshot");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-chat-composer.png")));
                page.click("[title='Add a requirements document to this project']");
                page.waitForSelector("text=Drop your requirements documents here",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                Dialogs.shotAtEveryWidth(page, "console-chat-upload");
                assertThat(page.locator("dialog.modal.modal-open").innerText())
                    .describedAs("it says what it takes, in the words of someone who has a "
                        + "document and not a file format in mind")
                    .contains("PDF").contains("photo of a whiteboard");
                // The window shipped with NO way out. A modal blocks the page, and the framework's
                // Dialog is a <dialog> opened by class rather than by showModal(), so Escape does
                // nothing to it and it has no clickable backdrop — opening this froze the Console
                // until the page was reloaded. This is the pin for that.
                page.click("[data-testid='document-upload-dialog'] >> text=Close");
                assertThat(page.locator("dialog.modal.modal-open").count())
                    .describedAs("nothing is blocking the page any more")
                    .isZero();

                // --- 3. the transcript's payload window --------------------------------------
                page.click("[data-testid='stage-plan']");
                page.waitForSelector("[data-testid='open-run']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                page.locator("[data-testid='open-run']").first().click();
                page.waitForSelector("[data-node='cand-0']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[data-node='cand-0']");
                // The drill-down from inside a dialog is now a dialog of its own, and this is the
                // pin for the fault that made it one. It used to be the shell's right-hand panel,
                // and a dialog is a native <dialog> since ZeroZ Stack 0.8.0: the browser draws one
                // in the TOP LAYER, above every stacking number there is, and makes the rest of
                // the page inert. The panel appeared beside the graph looking perfectly usable
                // while every click on it landed on the backdrop, so a candidate's details, its
                // transcript and the payload behind that were all unreachable — in the only way
                // any of them is ever opened. Playwright's own actionability check is what pins
                // it: the click below fails if anything covers the button.
                page.waitForSelector("[data-testid='inspector-dialog'] >> text=judge score",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.evaluate(
                    "() => {"
                    + "  const open = [...document.querySelectorAll('dialog.modal.modal-open')];"
                    + "  const last = open[open.length - 1];"
                    + "  return last && last.dataset.testid === 'inspector-dialog';"
                    + "}"))
                    .describedAs("the drill-down is the dialog nearest the front — a native "
                        + "dialog stacks by the order it was opened, so the newest is on top")
                    .isEqualTo(Boolean.TRUE);
                assertThat(page.locator("[data-testid='inspector']").isVisible())
                    .describedAs("and the right-hand rail stands down while the drill-down is a "
                        + "dialog, rather than showing the same thing twice")
                    .isFalse();
                Dialogs.shotAtEveryWidth(page, "console-graph-drilldown");
                page.click("text=Open session transcript");
                // The transcript is a deeper level of the same drill-down — the window is what
                // opens when one of its event cards is clicked.
                page.waitForSelector("text=LLM_RESPONSE",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("text=LLM_RESPONSE");
                page.waitForSelector("[data-testid='transcript-payload']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-testid='transcript-payload']").innerText())
                    .describedAs("the whole answer, not the one-line summary on the card")
                    .contains("What I changed").contains("multiply overflowed");
                Dialogs.shotAtEveryWidth(page, "console-transcript-payload",
                    () -> Dialogs.assertNothingOverflows(page,
                        "[data-testid='transcript-payload']", "the transcript payload window"));
                // Closing takes it out of the page rather than hiding it, so a long reading session
                // does not leave a stack of dead modals behind. Scoped to THIS window: the run
                // graph's dialog is still open underneath and its Close button says the same word.
                page.click("dialog.modal.modal-open:has([data-testid='transcript-payload']) "
                    + ">> text=Close");
                assertThat(page.locator("[data-testid='transcript-payload']").count())
                    .describedAs("the closed window is gone from the document, not merely hidden")
                    .isZero();
                assertThat(page.locator("[data-testid='open-run']").count())
                    .describedAs("and the graph it was opened from is still there, still open")
                    .isPositive();

                assertThat(pageErrors).isEmpty();
            }
        } finally {
            ConsoleContext.set(null);
        }
    }
}
