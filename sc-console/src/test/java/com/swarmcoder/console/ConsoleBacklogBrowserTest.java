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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One embedded Console per JVM — this test lives in its own class for that reason, not as a
 * style preference. zeroz4j's {@code Signals.shared(...)} binds to the first server engine
 * started in a JVM, so a second {@code Zeroz4jServer} started later in the same JVM serves
 * pages but its server-side signal {@code set()} never reaches the browser client, and any
 * signal-driven assertion times out. Surefire's {@code reuseForks=false} (see sc-console/pom.xml)
 * gives every browser test class a fresh JVM; adding a second browser test to this class would
 * silently reintroduce the defect — and this test asserts exactly on the shared signal.
 *
 * <p>The backlog panel renders live from the shared signal, and an operator action taken in the
 * browser reaches the store and comes back through the signal without a refresh.
 * Runs in an ordinary build wherever Playwright's Chromium is installed — no flag. On a machine without it, it is skipped and the skip is announced (see {@code @RunsWhen}).
 */
class ConsoleBacklogBrowserTest {

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        // The Console's RMI services are @Secured; the client authenticates as the dev admin.
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void backlogPanelRendersAndPromotesAStory(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            var project = new com.swarmcoder.domain.Project(projectId, "demo",
                dir.toString(), List.of(), Instant.now(), false);
            List<com.swarmcoder.domain.Project> projects = List.of(project);
            store.saveProject(project);
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> projects, () -> projectId, (n, p, c) -> null, id -> { }));

            // A requirement with one criterion, and a DRAFT story delivering it — the shape the
            // panel is built to show: something waiting in Triage for an operator decision.
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
                "A guest can complete a purchase", "HIGH", null, null, null, null);
            BrdAuthoring.addCriterion(store, projectId, "R1", "an empty cart is rejected",
                "GuestCheckoutTest#emptyCart");
            // The agent's output is provisional: promote it as the operator would in the
            // Requirements tab, otherwise nothing here could ever reach IMPLEMENTED.
            var seeded = store.getBrd(projectId);
            for (var r : seeded.requirements()) {
                r.setStatus(com.swarmcoder.domain.RequirementStatus.ACTIVE);
                for (var c : r.criteria()) {
                    c.setStatus(com.swarmcoder.domain.CriterionStatus.ACCEPTED);
                }
            }
            store.saveBrd(seeded, "human", "promoted");
            BacklogAuthoring.proposeStory(store, projectId, "Guest can pay", "R1:C1", null);
            UUID storyId = store.listStories(projectId).get(0).id();

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

                // The backlog is the PLAN stage now, not a panel wedged into every screen — three
                // stages of work no longer compete for one workspace. Readiness already recommends
                // Plan here (agreed scope, a proposed story, nothing workable), but the test selects
                // it explicitly so it does not silently depend on that judgement.
                page.waitForSelector("[data-testid='stage-plan']",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
                page.click("[data-testid='stage-plan']");
                // QUOTED = exact text. An unquoted "text=Backlog" is a case-insensitive substring
                // match, and its first hit is a sentence inside the hidden Setup stage ("…the
                // backlog and knowledge already work without one"). Stages are kept alive hidden
                // rather than rebuilt, so loose text selectors now resolve to whichever stage was
                // built first and then wait for it to become visible, forever.
                page.waitForSelector("text=\"Pipeline\"",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                // The columns are named for the DECISION each one is asking for, not for the state
                // enum, and they are addressed by testid for the same reason the stage bar is: the
                // label is prose, the decision is the contract.
                page.waitForSelector("[data-testid='pipeline-col-suggested'] >> text=Guest can pay",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                // The headings are in the operator's language now — "Suggested", not "PROPOSED",
                // which was the enum wearing a friendly font. The testid above is what the test
                // actually contracts on; this asserts the words the operator reads.
                assertThat(page.content()).contains("Suggested").contains("S1");
                // …and the card states its own situation, so "what is this and what do I do with
                // it" is answerable without opening anything. This is the whole redesign in one
                // assertion: the operator's report was "I was just clicking randomly on buttons
                // having no idea what they were for or what they would do".
                assertThat(page.locator("[data-testid='pipeline-col-suggested']").textContent())
                    .contains("The planner suggested this. Is it real work?")
                    .contains("Accept as work");

                // Drill into the story and accept it: the click goes over RMI to the store, and
                // the board redraws from the republished signal — no refresh anywhere. Depth opens in
                // a dialog OVER the board (UX v3 §3.2), and it carries the same words as the card, so
                // the action is scoped by the dialog's testid. That label used to be "Promote to
                // READY", which is the state machine's vocabulary and not the operator's.
                page.click("[data-testid='pipeline-col-suggested'] >> text=Guest can pay");
                page.waitForSelector("[data-testid='story-dialog'] >> text=\"Accept as work\"",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                // The widest dialog in the console, and one of the five that set their own width by
                // reaching into the component. Its surface is checked and it is looked at on three
                // window sizes before anything is clicked in it.
                Dialogs.settled(page);
                Dialogs.shotAtEveryWidth(page, "console-story-dialog");
                // The change journal is the deepest text on this dialog and the last place that
                // still printed enum constants: STATE_CHANGED and TOMBSTONED sat beside each line
                // of a history the operator is meant to read.
                page.click("[data-testid='story-dialog'] >> text=History");
                page.waitForSelector("[data-testid='story-dialog'] >> text=proposed S1",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-testid='story-dialog']").textContent())
                    .describedAs("each entry says what happened in words, not as a constant")
                    .contains("created");
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-story-history.png")));
                OperatorWords.assertNoneOnScreen(page, "the pipeline board with a story open on "
                    + "its change history");
                page.click("[data-testid='story-dialog'] >> text=\"Accept as work\"");

                // The story crossing from one column to the next is the proof the round trip landed
                // — the promotion went to the store and came back on the signal.
                page.waitForSelector("[data-testid='pipeline-col-ready'] >> text=Guest can pay",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

                // --- movement is SHOWN, not narrated (§3.1) ----------------------------------
                // The card flashes in its new column, and that flash IS the feedback. Every earlier
                // version of this reported the move in a sentence — "S1 has left this list and is now
                // on the Plan board under Ready to build" — a whole family of messages that existed
                // only because the destination was off-screen. Nothing is off-screen now, so the
                // sentences are gone and the ring is what says it happened.
                // WAITED for, not sampled: the flash is deliberately brief (a couple of seconds — long
                // enough to catch the eye, short enough to go), so reading the class after a few other
                // assertions raced it and found the ring already gone. Waiting for it to APPEAR in the
                // new column is also the more faithful assertion: it is what the operator's eye does.
                page.waitForSelector("[data-testid='pipeline-col-ready'] >> .ring-2",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                // …and the notice slot stayed silent, because there is nothing left for it to explain.
                // It is not a silent success (rule 4): the success is on screen, ringed, one column
                // over. What this slot is for now is refusals.
                assertThat(page.locator("body").innerText())
                    .describedAs("nothing narrates a move the operator can see")
                    .doesNotContain("has left this list")
                    .doesNotContain("is on the Plan board");
                assertThat(store.getStory(storyId).state())
                    .isEqualTo(com.swarmcoder.domain.StoryState.READY);

                // The requirements reference starts COLLAPSED, behind an edge that carries the word
                // rather than a glyph — the whole point of the label is that clicking it is the
                // discoverable way back to the thing the plan answers to.
                assertThat(page.locator("[data-testid='plan-requirements-pane']").isVisible())
                    .describedAs("the reference must start collapsed, not competing for width")
                    .isFalse();
                page.click("[data-testid='plan-requirements-edge']");
                page.waitForSelector("[data-testid='plan-requirements-pane'] >> text=Guest checkout",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-backlog.png")));

                // --- the way to Build is on the card, and it says what it will do first -------
                // The story landed in a column headed "Ready to build", on a card whose primary
                // action contains the word Build — because the answer to "how do I get a story
                // built" used to be "promote it, then press Start", and neither word appeared
                // anywhere on this board or named the stage it happens in.
                assertThat(page.locator("[data-testid='pipeline-col-ready']").textContent())
                    .contains("Ready to build")
                    .contains("Agreed work. Nothing is building it yet.")
                    .contains("Build this story");
                // Nine runs and thirty-seven unanswered decisions came out of a few clicks on a
                // button labelled "Start". Building dispatches agents against a real repository and
                // spends real tokens, and dropping the story afterwards does not recall them — so
                // it is the one action on this board that asks first. The dialog is unchanged apart
                // from its title, which now matches the button that opens it.
                page.click("[data-testid='pipeline-col-ready'] >> text=\"Build this story\"");
                page.waitForSelector("text=Build S1 now?",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                String dialog = page.content();
                assertThat(dialog)
                    .describedAs("the dialog must say what it dispatches, what it spends, and "
                        + "that there is no undo")
                    .contains("Dispatches a swarm of coding agents")
                    .contains("Spends cloud tokens")
                    .contains("check")
                    .contains("There is no undo");
                // Taken once the dialog has finished appearing. Before this it was caught
                // mid-fade, and the board behind it read through the words on it (see Dialogs).
                Dialogs.settled(page);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-backlog-start-confirm.png")));
                Dialogs.shotAtEveryWidth(page, "console-backlog-start-confirm");
                // Backing out changes nothing — the story is still READY and still ungrouped.
                page.click("text=Not now");
                assertThat(store.getStory(storyId).state())
                    .describedAs("declining the dialog must not start anything")
                    .isEqualTo(com.swarmcoder.domain.StoryState.READY);

                // --- the destructive one is named, quiet, and asks ----------------------------
                // This control was called "Cancel" and sat beside "Promote" in a row of four
                // identical links. It does not undo anything and it does not put the story back in
                // the backlog: cancelStory writes CANCELLED, which is a tombstone with no way out.
                // The operator pressed it expecting the opposite, so it is named for the deed and
                // it says the irreversible part before doing it.
                page.click("[data-testid='pipeline-col-ready'] >> text=\"Drop this story\"");
                page.waitForSelector("text=Drop S1?",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.content())
                    .describedAs("the dialog must say plainly that the story is retired for good")
                    .contains("It is retired")
                    .contains("does not come back");
                // QUOTED and scoped to the dialog. An unquoted text selector is a
                // case-insensitive SUBSTRING over the whole page, and the getting-started
                // guide — which is mounted, closed, on every screen — opens with "proves it
                // works before you keep it". That sentence is earlier in the document and is
                // inside a closed dialog, so the click waited for ever for it to be visible.
                page.click("dialog.modal.modal-open >> text=\"Keep it\"");
                assertThat(store.getStory(storyId).state())
                    .describedAs("backing out of the drop must leave the story exactly where it was")
                    .isEqualTo(com.swarmcoder.domain.StoryState.READY);

                // --- the orientation names the path, and then gets out of the way -------------
                // Two sentences at the top of a board nobody has built anything on yet: suggested,
                // accepted, built — and that an iteration is optional grouping. It is help, so it
                // has to be able to leave; it retires itself as soon as the project shows the path
                // has been walked, asserted below once a story is building.
                assertThat(page.locator("[data-testid='pipeline-orientation']").isVisible())
                    .describedAs("a project that has never built anything needs the way in named")
                    .isTrue();

                // --- RULE 1: an acted-on story is still on screen ----------------------------
                // The operator's complaint, reported four separate times: "if I promote a story I see
                // it briefly and then it vanishes… there is no indication that it is in Build." Every
                // earlier answer to it was a consolation prize — a grey chip below the columns, then a
                // band above them, then a toast naming the screen it had gone to. All three conceded
                // the point: the story had left the board.
                //
                // It has nowhere to go now. BUILDING is a column, so the story moves one place right
                // and stays in front of the operator, and this is the assertion that pins it.
                var started = store.getStory(storyId);
                started.setState(com.swarmcoder.domain.StoryState.RUNNING);
                store.saveStory(started);
                BacklogPublisher.publish(store, projectId);

                page.waitForSelector("[data-testid='pipeline-col-building'] >> text=Guest can pay",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                // It left the column it was in — sideways, one place, not off the board.
                assertThat(page.locator("[data-testid='pipeline-col-ready'] "
                        + ">> text=Guest can pay").count())
                    .describedAs("it moved out of Ready to build")
                    .isZero();
                // What the card says about liveness is BuildState's answer and there is no longer a
                // second one to disagree with it: this story has no run on record, so nothing is
                // driving it, and the card says exactly that — where it stands, in words, with the
                // one button that gets it moving again.
                assertThat(page.locator("[data-testid='pipeline-col-building']").textContent())
                    .describedAs("health is a badge and a sentence on the card, never a column")
                    .contains("stopped")
                    .contains("Nothing is driving it")
                    .contains("Build it again");
                // …and the orientation line has retired itself: this project has now built
                // something, so the sentence explaining what building is has stopped being help
                // and would be clutter.
                assertThat(page.locator("[data-testid='pipeline-orientation']").isVisible())
                    .describedAs("guidance that cannot go away becomes chrome the operator skips")
                    .isFalse();
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-pipeline-building.png")));


                // --- a question a build stopped to ask, ON the story's card -------------------
                // The Approval Center is retired as a destination (UX v3 2.3 / 6). It held one card
                // per decision, each naming a RUN — machinery the operator never handles — so with
                // thirty-seven of them there was no way to tell which of their stories any was about,
                // and nothing anywhere sent them to the queue in the first place. A question belongs
                // to the story whose build asked it, and it is answered there.
                UUID runId = UUID.randomUUID();
                var askingRun = new com.swarmcoder.domain.Run(runId,
                    com.swarmcoder.domain.WorkflowKind.GREENFIELD,
                    com.swarmcoder.domain.RunState.EXECUTING, projectId, null, null, null, null,
                    Instant.now(), null);
                askingRun.setHeartbeatAt(Instant.now());
                store.append(() -> store.root().runs.put(runId, askingRun)).get();
                var asking = store.getStory(storyId);
                asking.setRunIds(new ArrayList<>(List.of(runId)));
                store.saveStory(asking);
                UUID decisionId = UUID.randomUUID();
                store.append(() -> store.root().decisions.put(decisionId,
                    new com.swarmcoder.domain.Decision(decisionId, runId,
                        com.swarmcoder.domain.DecisionKind.BLOCKED_TASK,
                        "Task BLOCKED after swarm + repair round: 'Add multiply'",
                        com.swarmcoder.domain.DecisionState.PENDING, null, Instant.now()))).get();
                BacklogPublisher.publish(store, projectId);

                // The card says it, in one line, and carries the button that answers it. The badge is
                // "needs you" — a question is the operator's move, whatever the build is otherwise
                // doing — and the column has not changed, because health is never a location.
                page.waitForSelector("[data-testid='pipeline-col-building'] >> text=Answer this",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-testid='pipeline-col-building']").textContent())
                    .describedAs("the question outranks whatever else the card would have said")
                    .contains("needs you")
                    .contains("asking you something");
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-pipeline-question.png")));

                // Answering it: the brief is shown VERBATIM, because it is evidence and paraphrasing
                // evidence is how somebody ends up deciding against a summary they did not write.
                page.click("[data-testid='pipeline-col-building'] >> text=Answer this");
                page.waitForSelector("[data-testid='story-questions']",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-testid='story-questions']").textContent())
                    .contains("a build stopped")
                    .contains("Task BLOCKED after swarm + repair round");
                page.fill("[data-testid='story-questions'] >> textarea",
                    "re-decomposed the task by hand");
                page.click("text=Record this answer");

                // It reached the store, and the card stops asking — on the signal, with no refresh.
                // What it says instead is what the build is doing: this run is live and EXECUTING, so
                // the card goes back to reporting the phase, in the operator's words. That it can say
                // so is the point — the question was the situation, and now it is not.
                page.waitForSelector("[data-testid='pipeline-col-building'] >> text=writing the code",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-testid='pipeline-col-building']").textContent())
                    .contains("building now");
                assertThat(store.root().decisions.get(decisionId).state())
                    .describedAs("answering records the operator's call")
                    .isEqualTo(com.swarmcoder.domain.DecisionState.RESOLVED);
                assertThat(store.root().decisions.get(decisionId).humanResponse())
                    .isEqualTo("re-decomposed the task by hand");
                assertThat(page.locator("[data-testid='pipeline-col-building']").textContent())
                    .describedAs("an answered question stops being asked")
                    .doesNotContain("Answer this");

                // --- the accept flow ---------------------------------------------------------
                // Stand in for a run delivering the story: the workflow would set this after
                // integration. Publishing is what the server does on every mutation, and it is
                // what proves the panel is genuinely signal-driven rather than redrawing on click.
                var delivered = store.getStory(storyId);
                delivered.setState(com.swarmcoder.domain.StoryState.REVIEW);
                delivered.setDeliveredCommit("a1b2c3d4e5f6");
                store.saveStory(delivered);
                BacklogPublisher.publish(store, projectId);

                // Scoped to the column, not to the page: the card the inspector opens is built from
                // the story as the board last drew it, so clicking before the redraw would open a
                // panel offering "Start session" and this would wait for an Accept that is never
                // going to appear.
                page.waitForSelector("[data-testid='pipeline-col-came-back'] >> text=Guest can pay",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                // The card says what came back and what is being asked, before any button is read.
                assertThat(page.locator("[data-testid='pipeline-col-came-back']").textContent())
                    .contains("delivered commit a1b2c3d").contains("Did it deliver what was asked?");
                page.click("[data-testid='pipeline-col-came-back'] >> text=Guest can pay");
                // Exact text: "Accept delivery" is the primary, and the panel's explanatory hint
                // contains the word "Accepting" — a substring selector would depend on DOM order to
                // tell a button from a sentence about it.
                page.waitForSelector("[data-testid='story-dialog'] >> text=\"Accept delivery\"",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.click("[data-testid='story-dialog'] >> text=\"Accept delivery\"");

                // Rule 1 again, at the end of the line: accepting a delivery moves the story one more
                // column right, into DELIVERED, where it stays. That is the whole journey done on one
                // screen without anything ever disappearing — and it is a stronger claim than any
                // wording of the notice, because it is the round trip landing.
                page.waitForSelector("[data-testid='pipeline-col-delivered'] >> text=Guest can pay",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator("[data-testid='pipeline-col-came-back'] "
                        + ">> text=Guest can pay").count())
                    .describedAs("it moved out of Came back")
                    .isZero();
                // The empty column teaches rather than sitting blank: this is the first-run tutorial
                // nobody reads, delivered where and when it is needed instead.
                assertThat(page.locator("[data-testid='pipeline-col-building']").textContent())
                    .describedAs("an empty column says what fills it")
                    .contains("Nothing is being built");

                // The header's counts come from the SERVER, not from the client counting the graph it
                // happens to hold — two sources for one fact drift the moment one signal is
                // republished without the other, and this pair is on screen together. Accepting the
                // delivery took the requirement to IMPLEMENTED, so "agreed" must move with it.
                page.waitForSelector("[data-testid='header-counts'] >> text=1 agreed",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                // …and the requirements pane agrees, because accepting republishes the BRD it mutated.
                // It did not: only the backlog was published, so the pane went on badging this
                // requirement DRAFT beside a header that had already counted it as agreed.
                assertThat(page.locator("[data-testid='plan-requirements-pane']").textContent())
                    .describedAs("one requirement, one status, however many surfaces show it")
                    .doesNotContain("DRAFT");

                // The operator's click travelled over RMI and moved the requirement graph: the
                // story is done and the requirement is IMPLEMENTED on the commit that proved it.
                assertThat(store.getStory(storyId).state())
                    .isEqualTo(com.swarmcoder.domain.StoryState.DONE);
                var requirement = store.getBrd(projectId).requirements().get(0);
                assertThat(requirement.status())
                    .isEqualTo(com.swarmcoder.domain.RequirementStatus.IMPLEMENTED);
                assertThat(requirement.criteria().get(0).lastVerifiedCommit())
                    .isEqualTo("a1b2c3d4e5f6");

                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-backlog-accepted.png")));
                assertThat(pageErrors).isEmpty();
            }
        }
    }
}
