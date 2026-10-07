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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.Task;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.JourneyFile;
import com.swarmcoder.verify.JourneyRunner;
import com.swarmcoder.verify.ScreenChange;
import com.swarmcoder.verify.VerifySpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which tasks of a plan must be proved by a journey, and what is said when that cannot happen
 * (section 63). Everything here is decided from the build, the plan's write sets and the
 * project's verification contract - no model, and nothing read from what a story says.
 *
 * <h2>Why it exists</h2>
 *
 * <p>Seven stories were accepted whose screens no user could open (section 62). Their acceptance
 * tests called the server, so a screen without a button passed them. A journey - a real browser,
 * from the application's entry page, using only what is on the screen - is the test that fails
 * such a screen. The test author writes it. This class is the part that does not depend on the
 * author doing so: a plan that changes a screen and ends up with no journey does not go on.
 *
 * <h2>Which stories (section 64, after live run 89)</h2>
 *
 * <p>Run 89's story was about what a screen shows, and no journey was asked for: the screen is
 * drawn in the browser from a descriptor the server returns, so the plan wrote server code only
 * and the write set named no screen. Three rules now, in this order, none of them a model's:
 *
 * <ol>
 *   <li><b>The write set names a screen</b> ({@link ScreenChange}) - a journey, always.</li>
 *   <li><b>Browser-only code uses what the task writes</b>, read off the object graph: a type
 *       the written file declares, or a type one of its types is (the service interface the
 *       client calls, implemented in the written file) - a journey, always.</li>
 *   <li><b>The project's contract starts an application for a browser</b> - every story is
 *       asked for a journey. Only when neither rule above holds for any task may the author
 *       answer {@code noVisibleEffect: <why>} in place of one; that answer is recorded on the
 *       task and in the run's log. Nothing is skipped without a record.</li>
 * </ol>
 *
 * <p>A project with no {@code browser.serve} line stops for rules 1 and 2, as it did for rule 1.
 */
final class JourneysOfAPlan {

    private JourneysOfAPlan() {}

    /**
     * A task that changes a screen, with the paths of its write set that make it so.
     *
     * @param through empty when the paths are a screen themselves; otherwise how browser-only
     *                code uses what they hold, one line a connection, from the object graph
     */
    record Screen(Task task, List<String> paths, List<String> through) {

        Screen(Task task, List<String> paths) {
            this(task, paths, List.of());
        }
    }

    /**
     * @param screens   the tasks that change a screen, each with the paths that make it so
     * @param authoring the tasks whose test author is asked for a journey
     * @param stop      null to go on; otherwise why the run stops before any test is written
     * @param mayWaive  true when a journey is asked only because the project's application is
     *                  used in a browser: no task writes a screen and the graph shows no browser
     *                  code using what the plan writes, so an author may answer "no visible
     *                  effect" in place of a journey
     */
    record Decision(List<Screen> screens, List<Task> authoring, String stop, boolean mayWaive) {

        Decision(List<Screen> screens, List<Task> authoring, String stop) {
            this(screens, authoring, stop, false);
        }

        static final Decision NONE = new Decision(List.of(), List.of(), null);

        boolean storyHasAScreen() {
            return !screens.isEmpty();
        }

        /** True when a journey is asked of at least one task's author. */
        boolean journeyAsked() {
            return stop == null && !authoring.isEmpty();
        }

        /** How browser-only code uses what {@code task} writes; empty when the graph shows none. */
        List<String> through(Task task) {
            for (Screen screen : screens) {
                if (screen.task() == task) {
                    return screen.through();
                }
            }
            return List.of();
        }
    }

    /**
     * @param hasChecks whether a task answers for acceptance criteria - only such a task has its
     *                  tests written, so only such a task can be given a journey to write
     */
    static Decision decide(List<Task> tasks, Predicate<Task> hasChecks,
                           BrowserOnlyCode.Survey survey, VerifySpec contract) {
        return decide(tasks, hasChecks, survey, contract, null);
    }

    /**
     * @param usedByBrowserCode a path of a write set to how browser-only code uses what the
     *                          file holds, one line a connection (the object graph of the tree
     *                          the run starts from: {@code ReachableCode.Graph
     *                          .usersThroughItsTypes}); empty when it does not. Null when there
     *                          is no graph - then only the write sets and the contract decide
     */
    static Decision decide(List<Task> tasks, Predicate<Task> hasChecks,
                           BrowserOnlyCode.Survey survey, VerifySpec contract,
                           java.util.function.Function<String, List<String>> usedByBrowserCode) {
        if (!JourneyFile.enabled() || tasks == null || tasks.isEmpty()) {
            return Decision.NONE;
        }
        boolean served = JourneyFile.canRun(contract);
        List<Screen> screens = new ArrayList<>();
        for (Task task : tasks) {
            List<String> paths = ScreenChange.screenPaths(task.writeSet(), survey, served);
            if (!paths.isEmpty()) {
                screens.add(new Screen(task, paths));
                continue;
            }
            if (usedByBrowserCode == null) {
                continue;
            }
            List<String> used = new ArrayList<>();
            List<String> through = new ArrayList<>();
            for (String path : ScreenChange.shippedPaths(task.writeSet())) {
                List<String> how = usedByBrowserCode.apply(path);
                if (how != null && !how.isEmpty()) {
                    used.add(path);
                    how.stream().filter(line -> !through.contains(line)).forEach(through::add);
                }
            }
            if (!used.isEmpty()) {
                screens.add(new Screen(task, used, through));
            }
        }
        if (!served) {
            return screens.isEmpty() ? Decision.NONE
                : new Decision(List.copyOf(screens), List.of(), cannotStart(screens, survey));
        }
        // The journey is written with a task's tests. A screen task that answers for no check
        // has none written, so then the tasks that do answer for the story's checks are asked.
        // With no screen task at all the application is still one a person uses in a browser:
        // every task that answers for a check is asked, and may answer "no visible effect".
        List<Task> authoring = screens.stream().map(Screen::task).filter(hasChecks).toList();
        if (authoring.isEmpty()) {
            authoring = tasks.stream().filter(hasChecks).toList();
        }
        if (screens.isEmpty() && authoring.isEmpty()) {
            return Decision.NONE;
        }
        return new Decision(List.copyOf(screens), List.copyOf(authoring), null,
            screens.isEmpty());
    }

    /** The stop for a story with a screen in a project whose contract cannot start the app. */
    private static String cannotStart(List<Screen> screens,
                                      BrowserOnlyCode.Survey survey) {
        return "This story changes what a person sees in a browser, and this project's "
            + "verification contract cannot start the application, so nothing could ever show "
            + "that the screen can be reached and used.\n\n" + whichScreens(screens, survey)
            + "\n\nA story with a screen is accepted on a journey: after the last merge the "
            + "application is started in the container and a real browser uses the screen from "
            + "the application's entry page. The contract (.swarmcoder/verify.yaml) has no "
            + "`browser.serve` line, so nothing says how to start the application. Add one - "
            + "for example\n\n"
            + "browser:\n"
            + "  serve: \"<the command that starts the application; {PORT} where its port goes>\"\n"
            + "  readyProbe: \"http://localhost:{PORT}/\"\n\n"
            + "(`swarmcoder onramp <repository>` proposes one for this project) - and resume the "
            + "run. No test was written and no worker was started. -D" + JourneyFile.SWITCH
            + "=off runs the story without a journey, as before.";
    }

    private static String whichScreens(List<Screen> screens,
                                       BrowserOnlyCode.Survey survey) {
        StringBuilder text = new StringBuilder();
        for (Screen screen : screens) {
            List<String> modules = ScreenChange.browserOnlyModulesOf(screen.paths(), survey);
            text.append(text.length() == 0 ? "" : "\n").append("- task '")
                .append(screen.task().title()).append("' may write ")
                .append(shortList(screen.paths()));
            if (!screen.through().isEmpty()) {
                text.append(", which code that runs only in a browser uses (")
                    .append(shortList(screen.through())).append(")");
            }
            if (!modules.isEmpty()) {
                text.append(" (").append(String.join(", ", modules.stream()
                    .map(module -> module.isEmpty() ? "the root module" : module).toList()))
                    .append(modules.size() == 1 ? " runs" : " run").append(" only in a browser)");
            }
        }
        return text.toString();
    }

    private static String shortList(List<String> paths) {
        return paths.size() <= 4 ? String.join(", ", paths)
            : String.join(", ", paths.subList(0, 4)) + " and " + (paths.size() - 4) + " more";
    }

    /**
     * The backstop, asked when the tests are written: a plan that changes a screen and whose
     * tasks claim no journey does not go on. Null when a journey is claimed or none is needed.
     */
    static String missing(Decision decision, List<Task> tasks, BrowserOnlyCode.Survey survey) {
        if (decision == null || decision.stop() != null
                || (!decision.storyHasAScreen() && !decision.journeyAsked())) {
            return null;
        }
        boolean claimed = tasks != null
            && tasks.stream().anyMatch(task -> !task.journeyPaths().isEmpty());
        if (claimed) {
            return null;
        }
        if (decision.mayWaive()) {
            // Asked only because the application is used in a browser: every author asked
            // either wrote a journey or put on record why nothing on a screen differs.
            List<Task> silent = decision.authoring().stream()
                .filter(task -> task.journeyWaiver() == null || task.journeyWaiver().isBlank())
                .toList();
            if (silent.isEmpty()) {
                return null;
            }
            return "This project's application is used in a browser (its verification contract "
                + "starts it), so every story is proved by a journey - a real browser, from the "
                + "application's entry page - unless its test author puts on record that no "
                + "person using it sees or can do anything different. For "
                + silent.stream().map(task -> "'" + task.title() + "'").toList()
                + " the author handed in neither a well-formed journey (" + JourneyFile.SUFFIX
                + ", beside the acceptance tests) nor that answer (`"
                + JourneyFile.NO_VISIBLE_EFFECT + ": <why>`). Live run 89 delivered a story "
                + "about what a screen shows with no journey, because its plan wrote server "
                + "code only. Resume the run to have the tests and the journey written again. "
                + "-D" + JourneyFile.SWITCH + "=off runs the story without a journey.";
        }
        return "This story changes what a person sees in a browser, and no journey was written "
            + "for it, so nothing would show that the screen can be reached and used.\n\n"
            + whichScreens(decision.screens(), survey)
            + "\n\nThe test author was asked for a journey (" + JourneyFile.SUFFIX + ", beside "
            + "the acceptance tests) for " + decision.authoring().stream()
                .map(task -> "'" + task.title() + "'").toList()
            + " and handed in none that is well formed. The acceptance tests alone call the "
            + "code behind the screen and pass whether or not a person can get to it - that is "
            + "how seven stories were accepted whose screens nobody could open. Resume the run "
            + "to have the tests and the journey written again. -D" + JourneyFile.SWITCH
            + "=off runs the story without a journey.";
    }

    /** One journey a task claims, read from the run's tests commit. */
    record Claimed(Task task, JourneyFile.Journey journey) {}

    /**
     * The journeys the plan's tasks claim, read with {@code fileAt} (path to content, null when
     * the tests commit does not hold it). A file that is missing or no longer well formed is in
     * {@code unreadable}, by path - it was valid when it was written, so that is a defect.
     */
    static List<Claimed> claimed(List<Task> tasks, java.util.function.Function<String, String> fileAt,
                                 List<String> unreadable) {
        List<Claimed> all = new ArrayList<>();
        if (tasks == null) {
            return all;
        }
        for (Task task : tasks) {
            for (String path : task.journeyPaths()) {
                String content = fileAt.apply(path);
                JourneyFile.Read read = content == null ? null : JourneyFile.read(path, content);
                if (read == null || !read.ok()) {
                    unreadable.add(path);
                } else {
                    all.add(new Claimed(task, read.journey()));
                }
            }
        }
        return all;
    }

    /**
     * What the red check of the journeys comes to: a journey must FAIL on the application as the
     * run found it, as an acceptance test must. Null when every journey failed there.
     *
     * @param buildFailed null when the start tree built; otherwise the end of what the build said
     */
    static String notRed(List<Claimed> claimed, JourneyRunner.Outcome outcome, String buildFailed) {
        if (outcome.couldNotRun() != null) {
            return "This story's " + claimed.size() + " journey(s) could not be made on the code "
                + "the run starts from: " + outcome.couldNotRun() + ".\n\nA journey that cannot "
                + "be made now cannot be made after the last merge either, and the story would "
                + "then stop there with every worker already paid for. So it stops here. Nothing "
                + "was run on this PC instead. -D" + JourneyFile.SWITCH + "=off runs the story "
                + "without a journey.";
        }
        if (outcome.didNotStart() != null) {
            return "The application did not start on the code the run starts from, so this "
                + "story's journey(s) could not be made: " + outcome.didNotStart() + ".\n\nIt "
                + "was started the way the verification contract says (`browser.serve`), after "
                + "the contract's own build commands"
                + (buildFailed == null ? ", which passed. Either the serve line is wrong for "
                    + "this project or the application is broken before this story begins."
                    : ", which FAILED on that tree:\n" + buildFailed)
                + "\n\nCorrect the contract or the start tree and resume the run.";
        }
        List<JourneyFile.Result> passed = outcome.passed();
        if (passed.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("A journey must FAIL on the application as it is "
            + "before the story is built, as an acceptance test must: one that already passes "
            + "shows nothing about what this story adds, and every candidate would pass it "
            + "without building a screen. Already passing:\n");
        for (JourneyFile.Result result : passed) {
            text.append("\n").append(result.journey().describe());
        }
        return text.append("\nResume the run to have the tests and the journey written again; a "
            + "journey goes through what the story adds and ends on what a person then sees.")
            .toString();
    }

    /** The paragraph a repair worker is given when the journey its task claims failed. */
    static String repairEvidence(List<JourneyFile.Result> failed) {
        StringBuilder text = new StringBuilder("--- a journey failed in a real browser ---\n"
            + "Every task of this run was merged, the application was started, and a browser "
            + "made the journey this task claims: from the application's entry page, using only "
            + "what is on the screen. It failed.\n");
        for (JourneyFile.Result result : failed) {
            text.append("\njourney \"").append(result.journey().name()).append("\" (")
                .append(result.journey().path()).append("): ").append(result.failure())
                .append('\n');
        }
        return text.append("\nRead the journey with acceptance_test; you cannot change it. The "
            + "JUnit acceptance tests passed, so the code behind the screen works. What is "
            + "missing is on the screen or on the way to it: the element the failing step names "
            + "is not there, is not visible, is not reachable by the steps before it, or is "
            + "named differently from the journey's selector. Make the application match the "
            + "journey, step by step.\n").toString();
    }
}
