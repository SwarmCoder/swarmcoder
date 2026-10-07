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
import com.microsoft.playwright.options.BoundingBox;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.AuthoredTest;
import com.swarmcoder.domain.AuthoredTests;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RepairIndex;
import com.swarmcoder.domain.Requirement;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.LiveWorkerSessions;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test-authoring stage is visible on the run graph while it runs.
 *
 * <p>The owner's words: "When we are in the generate tests step, there is zero feedback on what is
 * happening and no way to know how many tests, or what these tests are doing? ... it would be cool
 * to see the actual tests appearing on the GUI as proof that something is happening." The stage
 * takes minutes, authors tasks one at a time, and drew nothing at all for the whole of it - the
 * same picture a hung stage draws.
 *
 * <p>What this proves, in a real browser against an embedded Console with no model anywhere:
 * <ol>
 *   <li>a task whose tests have landed wears a badge - "2 tests · 2 checks" - and a task the author
 *       has right now says "writing its tests"; a task that claims no checks says nothing; a task
 *       handed checks that got no tests says so;
 *   <li>a task gaining tests mid-stage reaches the browser <b>through the real publisher</b>: the
 *       store is changed and nothing is pushed by hand. The disappearing-chips bug was exactly a
 *       fact the server knew and a frame the browser never got, so this is the assertion that
 *       matters most;
 *   <li>clicking the badge opens the tests - names, the check each proves in the requirement's own
 *       words, and the source - on a surface that is on top of the run-graph dialog and inside the
 *       window at three widths;
 *   <li>the crowded case: a badge on a task that also carries eight worker chips after a repair
 *       round, at three widths, with the badge inside its box.
 * </ol>
 *
 * <p>One embedded Console per JVM, so one browser test with everything asserted inside it.
 */
class ConsoleTestAuthoringBrowserTest {

    @TempDir
    Path dir;

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    private static final String SURFACE = "[data-testid='run-graph-canvas']";
    private static final String PANEL = "[data-testid='task-tests']";
    private static final String INSPECTOR = "[data-testid='inspector-dialog']";

    private static final String BOOK_FILE = "src/test/java/swarm/accept/BookTest.java";
    private static final String SEARCH_FILE = "src/test/java/swarm/accept/SearchTest.java";

    private static final String BOOK_SOURCE = """
        package swarm.accept;

        import org.junit.jupiter.api.Test;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        class BookTest {

            @Test
            void storesAllBookFields() {
                Book book = new Book("Dune", "Frank Herbert", 1965);
                assertEquals("Dune", book.title());
            }

            @Test
            void rejectsUnknownReadingStatus() {
                assertThrows(IllegalArgumentException.class, () -> Book.status("skimmed"));
            }
        }
        """;

    private static String badge(UUID task) {
        return "[data-tests-badge='" + task + "']";
    }

    private static String node(UUID task) {
        return "[data-node='task-" + task + "']";
    }

    @Test
    @RunsWhen(Need.CHROMIUM)
    void theTestsAppearOnTheGraphAsTheyAreWritten_andOpenWhenClicked() throws Exception {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve("src/test/java/swarm/accept"));
        Files.writeString(repo.resolve(BOOK_FILE), BOOK_SOURCE);
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            TraceHub hub = new TraceHub(null);
            UUID projectId = UUID.randomUUID();
            List<Project> projects = List.of(new Project(projectId, "bookshelf",
                repo.toString(), List.of(), Instant.now(), false));
            ConsoleContext.set(new ConsoleContext(store, hub,
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> projects, () -> projectId, (n, p, c) -> null, id -> { }));

            UUID runId = UUID.randomUUID();
            UUID bookTask = UUID.randomUUID();
            UUID searchTask = UUID.randomUUID();
            UUID bareTask = UUID.randomUUID();
            UUID emptyTask = UUID.randomUUID();
            seed(store, projects.get(0), runId, bookTask, searchTask, bareTask, emptyTask);

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
                page.waitForSelector(node(bookTask),
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
                page.waitForTimeout(400);

                // --- 1. mid-stage: one task authored, one being authored, one claiming none ---
                assertThat(page.locator("[data-testid='phase-strip']").innerText())
                    .contains("writing the tests it must pass");
                assertThat(page.locator(badge(bookTask)).count())
                    .describedAs("the task whose tests have landed wears a badge")
                    .isEqualTo(1);
                assertThat(page.locator(badge(bookTask)).textContent())
                    .describedAs("how many tests, and how many of its checks they prove")
                    .contains("2 tests").contains("2 checks");
                assertThat(page.locator(node(searchTask) + " [data-tests='writing']").count())
                    .describedAs("the task the author has right now says so")
                    .isEqualTo(1);
                assertThat(page.locator(node(searchTask)).textContent())
                    .contains("writing its tests");
                assertThat(page.locator(node(bareTask) + " [data-tests]").count())
                    .describedAs("a task that claims no checks says nothing - not '0 tests'")
                    .isZero();
                assertThat(page.locator(node(bareTask)).textContent())
                    .doesNotContain("tests");
                assertThat(page.locator(node(emptyTask) + " [data-tests='none-written']").count())
                    .describedAs("a task handed a check that got no test says that, quietly")
                    .isEqualTo(1);
                assertThat(page.locator(node(emptyTask)).textContent())
                    .contains("no tests were written");
                for (int width : new int[] {1600, 1280, 960}) {
                    page.setViewportSize(width, 950);
                    page.waitForTimeout(300);
                    recentre(page, node(bookTask));
                    badgeStaysInsideItsBox(page, bookTask, "mid-stage at " + width);
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target", "console-tests-writing-" + width + ".png")));
                }
                page.setViewportSize(1600, 950);
                page.waitForTimeout(300);

                // --- 2. a task gains tests, and the browser hears about it by itself ---------
                // Nothing is pushed by hand. The store changes, the publisher started by the
                // graph's own watch() has to notice - which it only does if a task gaining tests
                // is on the fingerprint it compares - and the frame has to reach the page.
                store.append(() -> {
                    taskIn(store, searchTask).setAuthoredTests(searchWritten());
                    return null;
                }).get();
                page.waitForSelector(badge(searchTask),
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                assertThat(page.locator(badge(searchTask)).textContent())
                    .describedAs("the badge that appeared mid-stage, through the real publisher")
                    .contains("1 test").contains("1 check");
                assertThat(page.locator(node(searchTask) + " [data-tests='writing']").count())
                    .describedAs("and it is no longer being written")
                    .isZero();
                assertThat(page.locator(badge(bookTask)).count())
                    .describedAs("the first badge is still there")
                    .isEqualTo(1);
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("target", "console-tests-second-badge.png")));

                // --- 3. the hover card names the tests too ------------------------------------
                page.locator(node(bookTask)).hover();
                page.waitForSelector("[data-testid='graph-hover-card']",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
                assertThat(page.locator("[data-testid='graph-hover-card']").innerText())
                    .contains("2 written, proving 2 of its 2 checks");
                page.mouse().move(4, 4);
                page.waitForTimeout(200);

                // --- 4. click the badge: the tests, the checks they prove, and the source -----
                page.locator(badge(bookTask)).click();
                page.waitForSelector(PANEL, new Page.WaitForSelectorOptions().setTimeout(5_000));
                page.waitForTimeout(400);
                String panel = page.locator(PANEL).innerText();
                assertThat(panel)
                    .describedAs("the names, the check each proves with the requirement's own "
                        + "words, and the source")
                    .contains("2 tests were written for this task")
                    .contains("swarm.accept.BookTest#storesAllBookFields")
                    .contains("R1:C1")
                    .contains("A book keeps every field it was given")
                    .contains("swarm.accept.BookTest#rejectsUnknownReadingStatus")
                    .contains("R1:C2")
                    .contains("The reading status is limited to the three known ones")
                    .contains(BOOK_FILE)
                    .contains("class BookTest")
                    .contains("assertEquals(\"Dune\", book.title())");
                assertThat(panel)
                    .describedAs("it says what was written and never that the workers will run it")
                    .doesNotContain("workers will")
                    .doesNotContain("must pass");
                assertThat(page.locator("text=Task — Store a book with all its fields").count())
                    .describedAs("the badge's click did not also open the task behind it")
                    .isZero();
                for (int width : new int[] {1600, 1280, 960}) {
                    page.setViewportSize(width, 950);
                    page.waitForTimeout(400);
                    panelIsOnTopAndInsideTheWindow(page, width);
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target", "console-tests-panel-" + width + ".png")));
                }
                page.setViewportSize(1600, 950);
                page.waitForTimeout(300);
                // The drill-down's own close control, at the end of its breadcrumb row.
                page.locator(INSPECTOR + " .ml-auto.cursor-pointer").first().click();
                page.waitForTimeout(400);
                assertThat(page.locator(PANEL).first().isVisible())
                    .describedAs("closing the tests takes them off the screen and leaves the "
                        + "graph where it was")
                    .isFalse();
                assertThat(page.locator(INSPECTOR + "[open]").count()).isZero();
                assertThat(page.locator(node(bookTask)).count()).isEqualTo(1);

                // --- 5. the crowded case: a badge AND eight chips after a repair round -------
                store.append(() -> {
                    Run run = store.root().runs.get(runId);
                    store.root().runs.put(runId, run.withState(RunState.EXECUTING));
                    taskIn(store, bookTask).setState(TaskState.REPAIRING);
                    return null;
                }).get();
                failWholeWave(store, bookTask, 4);
                for (int seed = 0; seed < 2; seed++) {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        UUID id = LiveWorkerSessions.open(hub, runId, bookTask,
                            RepairIndex.of(seed, attempt), "qwen3.8-27b", 0.4 + 0.4 * attempt,
                            8_000);
                        LiveWorkerSessions.answered(hub, id, "fixing", 9_000);
                    }
                }
                page.waitForSelector("[data-repair-band]",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
                page.waitForTimeout(400);
                assertThat(page.locator("[data-node^='cand-'][data-task='" + bookTask + "']")
                    .count()).isEqualTo(8);
                assertThat(page.locator(badge(bookTask)).count())
                    .describedAs("the badge survives the repair round")
                    .isEqualTo(1);
                assertThat(page.locator(SURFACE).innerText())
                    .describedAs("the box says both things: fixing what failed, and its tests")
                    .contains("fixing what failed")
                    .contains("2 tests");
                for (int width : new int[] {1600, 1280, 960}) {
                    page.setViewportSize(width, 950);
                    page.waitForTimeout(400);
                    recentre(page, node(bookTask));
                    badgeStaysInsideItsBox(page, bookTask, "the crowded case at " + width);
                    page.mouse().move(4, 4);
                    page.waitForTimeout(150);
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(Path.of("target", "console-tests-crowded-" + width + ".png")));
                }
                page.setViewportSize(1600, 950);

                assertThat(page.locator("[data-node] title").count())
                    .describedAs("no operating-system tooltip anywhere on the graph")
                    .isZero();
                assertThat(pageErrors)
                    .describedAs("nothing was thrown at the page")
                    .isEmpty();
            }
        }
    }

    /** The badge's words are inside the task's own rectangle, not spilling over its edge. */
    private static void badgeStaysInsideItsBox(Page page, UUID task, String why) {
        BoundingBox box = page.locator(node(task) + " > rect").first().boundingBox();
        BoundingBox pill = page.locator(badge(task) + " rect").boundingBox();
        BoundingBox words = page.locator(badge(task) + " text").boundingBox();
        assertThat(pill.x).describedAs(why + " - pill left").isGreaterThanOrEqualTo(box.x);
        assertThat(pill.x + pill.width).describedAs(why + " - pill right")
            .isLessThanOrEqualTo(box.x + box.width);
        assertThat(pill.y + pill.height).describedAs(why + " - pill bottom")
            .isLessThanOrEqualTo(box.y + box.height);
        assertThat(words.x).describedAs(why + " - words left").isGreaterThanOrEqualTo(pill.x);
        assertThat(words.x + words.width).describedAs(why + " - words right, measured off the "
                + "rendered glyphs, inside the pill")
            .isLessThanOrEqualTo(pill.x + pill.width + 1);
    }

    /**
     * The tests panel is drawn over the run-graph dialog, not behind it, and inside the window.
     *
     * <p>"On top" is measured the only way that is real: the element the browser would deliver a
     * click to, at the panel's centre, is the panel. A dialog painted behind another one - which
     * is what DEVELOPER_CORRECTIONS §37.2 recorded happening - fails this, however good its own
     * bounding box looks.
     */
    private static void panelIsOnTopAndInsideTheWindow(Page page, int width) {
        BoundingBox panel = page.locator(PANEL).boundingBox();
        BoundingBox dialog = page.locator(INSPECTOR + " .modal-box").boundingBox();
        int height = ((Number) page.evaluate("() => window.innerHeight")).intValue();
        assertThat(dialog.x).describedAs("dialog left at " + width).isGreaterThanOrEqualTo(0);
        assertThat(dialog.x + dialog.width).describedAs("dialog right at " + width)
            .isLessThanOrEqualTo(width);
        assertThat(dialog.y).describedAs("dialog top at " + width).isGreaterThanOrEqualTo(0);
        assertThat(dialog.y + dialog.height).describedAs("dialog bottom at " + width)
            .isLessThanOrEqualTo(height);
        assertThat(panel.x).isGreaterThanOrEqualTo(dialog.x - 1);
        assertThat(panel.x + panel.width).isLessThanOrEqualTo(dialog.x + dialog.width + 1);
        Object onTop = page.evaluate("([x, y]) => {"
            + "  const hit = document.elementFromPoint(x, y);"
            + "  return hit && hit.closest(\"[data-testid='task-tests']\") ? 'panel'"
            + "    : (hit ? hit.tagName + '.' + hit.className : 'nothing');"
            + "}", List.of(panel.x + panel.width / 2, panel.y + Math.min(40, panel.height / 2)));
        assertThat(String.valueOf(onTop))
            .describedAs("the thing under the pointer at the panel's own centre is the panel - "
                + "not the run-graph dialog it must sit over, at " + width)
            .isEqualTo("panel");
        assertThat(page.locator(PANEL + " [data-test-source]").first().boundingBox().width)
            .describedAs("the source box is laid out, not collapsed, at " + width)
            .isGreaterThan(100);
    }

    /** Moves the graph until a node sits where the test wants it - see the hover test for why keys. */
    private static BoundingBox panNodeTo(Page page, String node, double x, double y) {
        page.locator(SURFACE).focus();
        for (int press = 0; press < 120; press++) {
            BoundingBox at = page.locator(node).boundingBox();
            double dx = x - at.x;
            double dy = y - at.y;
            if (Math.abs(dx) <= 25 && Math.abs(dy) <= 25) {
                break;
            }
            boolean horizontal = Math.abs(dx) > Math.abs(dy);
            double error = horizontal ? dx : dy;
            String arrow = horizontal
                ? (error > 0 ? "ArrowLeft" : "ArrowRight")
                : (error > 0 ? "ArrowUp" : "ArrowDown");
            page.keyboard().press(Math.abs(error) > 200 ? "Shift+" + arrow : arrow);
        }
        return page.locator(node).boundingBox();
    }

    private static void recentre(Page page, String node) {
        BoundingBox surface = page.locator(SURFACE).boundingBox();
        panNodeTo(page, node, surface.x + 70, surface.y + 55);
    }

    /** Every worker on this task fails verification, the way the engine records it. */
    private static void failWholeWave(ArtifactStore store, UUID taskId, int workers)
            throws Exception {
        store.append(() -> {
            for (int worker = 0; worker < workers; worker++) {
                VerificationReport report = new VerificationReport();
                report.setId(UUID.randomUUID());
                report.setParses(true);
                report.setCompiles(false);
                report.setLogTail("...\n[verdict] NOT SURVIVED — "
                    + "the candidate does not compile\n");
                CandidateSolution candidate = new CandidateSolution(UUID.randomUUID(), taskId,
                    worker, "swarm/w" + worker,
                    new SamplingConfig("qwen3.8-27b", 0.1 + worker * 0.23, 0, "", ""),
                    "", report, null, null, CandidateState.FAILED, null);
                store.root().candidateArchives.put(candidate.id(),
                    org.eclipse.serializer.reference.Lazy.Reference(candidate));
            }
            return null;
        }).get();
    }

    // --- fixtures ----------------------------------------------------------------------------

    private static AuthoredTests bookWritten() {
        AuthoredTests record = AuthoredTests.started(2, Instant.now().minusSeconds(90));
        record.setWrittenAt(Instant.now().minusSeconds(30));
        record.setFiles(new ArrayList<>(List.of(BOOK_FILE)));
        record.setTests(new ArrayList<>(List.of(
            new AuthoredTest("swarm.accept.BookTest#storesAllBookFields", BOOK_FILE, "R1:C1",
                "A book keeps every field it was given"),
            new AuthoredTest("swarm.accept.BookTest#rejectsUnknownReadingStatus", BOOK_FILE,
                "R1:C2", "The reading status is limited to the three known ones"))));
        return record;
    }

    private static AuthoredTests searchWritten() {
        AuthoredTests record = AuthoredTests.started(1, Instant.now().minusSeconds(20));
        record.setWrittenAt(Instant.now());
        record.setFiles(new ArrayList<>(List.of(SEARCH_FILE)));
        record.setTests(new ArrayList<>(List.of(
            new AuthoredTest("swarm.accept.SearchTest#findsABookByPartOfItsTitle", SEARCH_FILE,
                "R2:C1", "A reader can find a book by typing part of its title"))));
        return record;
    }

    private static Task taskIn(ArtifactStore store, UUID taskId) {
        for (TaskGraph graph : store.root().taskGraphs.values()) {
            for (Task task : graph.tasks()) {
                if (taskId.equals(task.id())) {
                    return task;
                }
            }
        }
        throw new IllegalStateException("no task " + taskId);
    }

    /**
     * A run in the test-authoring stage: the book task's tests are on disk and recorded, the search
     * task is being authored right now, the bare task claims no checks, and the empty task was
     * handed a check and got nothing.
     */
    private static void seed(ArtifactStore store, Project project, UUID runId, UUID bookTask,
                             UUID searchTask, UUID bareTask, UUID emptyTask) throws Exception {
        UUID shelfRequirement = UUID.randomUUID();
        UUID searchRequirement = UUID.randomUUID();
        DesignDocument design = new DesignDocument();
        design.setId(UUID.randomUUID());
        design.setRequirements(List.of(
            requirement(shelfRequirement, "Books can be put on the shelf and stay there"),
            requirement(searchRequirement,
                "A reader can find a book by typing part of its title")));

        Story shelfStory = story(project.id(), "S1", "Putting books on the shelf");
        Story searchStory = story(project.id(), "S2", "Searching the shelf");

        Task book = new Task(bookTask, 1, "Store a book with all its fields", "",
            Set.of("shelf/src/main/java"), Set.of(),
            List.of(check("A book keeps every field it was given",
                    "swarm.accept.BookTest#storesAllBookFields"),
                check("The reading status is limited to the three known ones",
                    "swarm.accept.BookTest#rejectsUnknownReadingStatus")),
            "src/test/java/swarm", null, null, new SwarmPolicy(4, false, 0.1, 0.8, List.of()),
            TaskState.PENDING, new LinkedHashSet<>(List.of(shelfRequirement)));
        book.setStoryId(shelfStory.id());
        book.setAuthoredTests(bookWritten());

        Task search = new Task(searchTask, 1, "Find a book on the shelf by its title", "",
            Set.of("search/src/main/java"), Set.of(),
            List.of(check("A reader can find a book by typing part of its title",
                "swarm.accept.SearchTest#findsABookByPartOfItsTitle")),
            "src/test/java/swarm", null, null, new SwarmPolicy(4, false, 0.1, 0.8, List.of()),
            TaskState.PENDING, new LinkedHashSet<>(List.of(searchRequirement)));
        search.setStoryId(searchStory.id());
        search.setAuthoredTests(AuthoredTests.started(1, Instant.now()));

        // Claims no checks at all: an enabler. Authored, and there was nothing to write.
        Task bare = new Task(bareTask, 1, "Set up the shared module", "",
            Set.of("shared/pom.xml"), Set.of(), List.of(), "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.1, 0.8, List.of()), TaskState.PENDING);
        AuthoredTests none = AuthoredTests.started(0, Instant.now().minusSeconds(60));
        none.setWrittenAt(Instant.now().minusSeconds(60));
        bare.setAuthoredTests(none);

        // Handed a check, and the author produced nothing for it.
        Task empty = new Task(emptyTask, 1, "Wire the search into the menu", "",
            Set.of("menu/src/main/java"), Set.of(),
            List.of(check("The menu offers the search", "swarm.accept.MenuTest#offersSearch")),
            "src/test/java/swarm", null, null, new SwarmPolicy(4, false, 0.1, 0.8, List.of()),
            TaskState.PENDING, new LinkedHashSet<>(List.of(searchRequirement)));
        AuthoredTests nothing = AuthoredTests.started(1, Instant.now().minusSeconds(45));
        nothing.setWrittenAt(Instant.now().minusSeconds(40));
        empty.setAuthoredTests(nothing);

        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            List.of(book, empty, search, bare),
            List.of(new TaskEdge(bookTask, searchTask), new TaskEdge(bookTask, bareTask)));

        store.append(() -> {
            store.root().projects.put(project.id(), project);
            store.root().designs.put(design.id(), design);
            store.root().stories().put(shelfStory.id(), shelfStory);
            store.root().stories().put(searchStory.id(), searchStory);
            store.root().taskGraphs.put(graph.id(), graph);
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.TEST_AUTHORING,
                project.id(), null, design.id(), graph.id(), null, Instant.now(),
                new RunReport(runId, "a bookshelf that stores books"));
            run.setHeartbeatAt(Instant.now());
            store.root().runs.put(runId, run);
            return null;
        }).get();
    }

    private static Requirement requirement(UUID id, String text) {
        Requirement requirement = new Requirement();
        requirement.setId(id);
        requirement.setText(text);
        requirement.setPriority(Priority.HIGH);
        return requirement;
    }

    private static AcceptanceCriterion check(String text, String testRef) {
        return new AcceptanceCriterion(UUID.randomUUID(), text, testRef);
    }

    private static Story story(UUID projectId, String key, String title) {
        Story story = new Story();
        story.setId(UUID.randomUUID());
        story.setProjectId(projectId);
        story.setKey(key);
        story.setKind(StoryKind.DELIVERY);
        story.setTitle(title);
        story.setState(StoryState.RUNNING);
        return story;
    }
}
