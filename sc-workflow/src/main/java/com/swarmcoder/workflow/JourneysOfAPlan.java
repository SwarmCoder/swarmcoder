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

    /** A journey whose claim went from the task it was written with to the task that owns it. */
    record Moved(String path, Task from, Task to) {}

    /**
     * Settles which task OWNS each journey (section 70, after live run 95).
     *
     * <p>A journey is written with a task's tests, so it was claimed by the task whose author
     * wrote it - and that is any task that answers for a check. In run 95 it was a task whose
     * write set held server code only; the screen the journey uses was written by another task.
     * When the journey failed, the repair went to workers who may not touch the screen.
     *
     * <p>Which task is asked for a journey ({@link #decide}) is unchanged: a task that writes no
     * screen may be the reason a journey is needed. It does not own it. The owner of a journey
     * is, from the write sets and the build alone:
     *
     * <ol>
     *   <li>the task it was written with, when that task's write set names a screen
     *       ({@link ScreenChange#screenPaths}) - the journey was written for that screen;</li>
     *   <li>otherwise the LAST task in the plan's order whose write set names a screen: the
     *       steps of a journey meet the browser code the plan writes, and the last such task
     *       is cut from every earlier one, so its checkout holds the whole of it;</li>
     *   <li>otherwise - no task of the plan writes a screen - the task it was written with:
     *       the screen is already there and what it shows comes from that task's change.</li>
     * </ol>
     *
     * <p>Nothing is read from a title, an instruction or the journey's words. Run again it
     * changes nothing, so it is also what puts right a plan made before this rule - a run
     * resumed from an earlier stage - before workers build and before final integration.
     *
     * <p>Not seen: which of several screen-writing tasks wrote the element a step names. With
     * two screens by two tasks and a journey written with a third, the last of the two owns it.
     *
     * @param planOrder the plan's tasks in the order they are built and merged
     * @return the claims that moved; the caller stores both tasks of each
     */
    static List<Moved> settleOwners(List<Task> planOrder, BrowserOnlyCode.Survey survey,
                                    boolean served) {
        List<Moved> moved = new ArrayList<>();
        if (planOrder == null || planOrder.isEmpty()) {
            return moved;
        }
        List<Task> writesAScreen = planOrder.stream()
            .filter(task -> !ScreenChange.screenPaths(task.writeSet(), survey, served).isEmpty())
            .toList();
        java.util.Map<String, List<Task>> claims = new java.util.LinkedHashMap<>();
        for (Task task : planOrder) {
            for (String path : task.journeyPaths()) {
                List<Task> claiming = claims.computeIfAbsent(path.replace((char) 92, '/'),
                    key -> new ArrayList<>());
                if (!claiming.contains(task)) {
                    claiming.add(task);
                }
            }
        }
        for (java.util.Map.Entry<String, List<Task>> claim : claims.entrySet()) {
            List<Task> claiming = claim.getValue();
            List<Task> own = claiming.stream().filter(writesAScreen::contains).toList();
            Task owner = !own.isEmpty() ? own.get(own.size() - 1)
                : !writesAScreen.isEmpty() ? writesAScreen.get(writesAScreen.size() - 1)
                : claiming.get(0);
            if (claiming.size() == 1 && claiming.get(0) == owner) {
                continue;
            }
            String path = claim.getKey();
            Task from = null;
            for (Task other : claiming) {
                if (other != owner) {
                    from = from == null ? other : from;
                    other.setJourneyPaths(other.journeyPaths().stream()
                        .filter(held -> !held.replace((char) 92, '/').equals(path)).toList());
                }
            }
            if (owner.journeyPaths().stream()
                    .noneMatch(held -> held.replace((char) 92, '/').equals(path))) {
                List<String> held = new ArrayList<>(owner.journeyPaths());
                held.add(path);
                owner.setJourneyPaths(List.copyOf(held));
            }
            if (from != null) {
                moved.add(new Moved(path, from, owner));
            }
        }
        return moved;
    }

    /**
     * What the run stops on when the author of a failed journey calls the journey wrong and no
     * correction of it could be taken (section 70). No worker is started on such a journey.
     *
     * @param answers what the author answered about each such journey, as recorded on the task
     */
    static String disowned(Task task, List<String> paths, List<String> answers) {
        return "The journey went back to its author before any worker, and its author says the "
            + "journey itself is wrong - and no corrected journey could be taken. Task '"
            + task.title() + "', " + paths + ":\n- " + String.join("\n- ", answers)
            + "\n\nNo worker was started. A worker cannot change a journey, and making the "
            + "screen match a journey its own author calls wrong would build the wrong thing. "
            + "Either correct the journey by hand and resume, or resume as it is: the author "
            + "is then asked again.";
    }

    /** Most reviews by its author one journey gets in a run (section 71). */
    static final int MAX_REVIEWS = 2;

    /** The step a journey was last reviewed at, 0 when never (see {@link Task#journeyReviews}). */
    static int lastReviewedStep(Task task, String path) {
        int last = 0;
        for (String entry : task.journeyReviews()) {
            int bar = entry.lastIndexOf('|');
            if (bar > 0 && entry.substring(0, bar).equals(path)) {
                try {
                    last = Integer.parseInt(entry.substring(bar + 1));
                } catch (NumberFormatException e) {
                    // an entry that is not ours; ignored
                }
            }
        }
        return last;
    }

    /** How many times the journey at {@code path} has been reviewed by its author. */
    static int reviewsOf(Task task, String path) {
        return (int) task.journeyReviews().stream()
            .filter(entry -> entry.startsWith(path + "|")).count();
    }

    /**
     * Whether a failed journey goes to its author now (section 71), decided from step numbers
     * only. A journey never reviewed goes once per task (section 69); one reviewed before goes
     * again only when it now fails at a LATER step than at its last review - the earlier step
     * passes now, which its author has not seen - and never a third time.
     */
    static boolean goesToItsAuthor(Task task, JourneyFile.Result failed) {
        int reviews = reviewsOf(task, failed.journey().path());
        if (reviews == 0) {
            return !task.journeySentBack();
        }
        return reviews < MAX_REVIEWS
            && failed.step() > lastReviewedStep(task, failed.journey().path());
    }

    /** Notes a review of {@code path} that failed at {@code step}. */
    static void recordReview(Task task, String path, int step) {
        List<String> all = new ArrayList<>(task.journeyReviews());
        all.add(path + "|" + step);
        task.setJourneyReviews(all);
    }

    /**
     * What the run stops on when a journey, sent back a second time after a repair round, was
     * not corrected: both of its author's answers, and no worker.
     */
    static String reviewedTwice(Task task, List<String> paths) {
        return "The journey went back to its author twice: once when it first failed, and again "
            + "when it failed at a later step. The second review gave no corrected journey "
            + "that could be taken. Task '" + task.title() + "', " + paths + ":\n"
            + task.journeyReviewNote() + "\n\nNo worker was started: the repair round of a "
            + "task is one, and a journey is not sent back a third time. Correct the journey "
            + "or the screen by hand and resume.";
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

    static String lastStepOf(JourneyFile.Journey journey) {
        return journey.steps().isEmpty() ? "nothing"
            : journey.steps().get(journey.steps().size() - 1).describe();
    }

    /**
     * Why a corrected journey is not taken; null when it is. The guard against an author that
     * makes its journey pass by asking for less (section 69):
     *
     * <ol>
     *   <li>it is a well-formed journey;</li>
     *   <li>it is not weaker by its form ({@code JourneyExpectations.weakened}): it still
     *       changes something, with no fewer changing steps and no fewer fills;</li>
     *   <li>it PASSES on the merged tree, in the container that holds it - or, since section
     *       75, fails there at a later step than the journey it replaces; that it was taken
     *       so is only on {@link #judgeCorrection}'s result, this method answers "not
     *       refused" for both;</li>
     *   <li>it FAILS on the tree the run started from, built and started as the red check
     *       does; and what its last step expects is not already on the entry page there.</li>
     * </ol>
     *
     * <p>The cheap checks come first: the start tree is built only for a correction that
     * passed the three before it.
     *
     * <p>Not caught: a correction with as many steps that ends on something the story adds
     * but the criteria do not ask for (the new screen's heading in place of the search result).
     * It fails before and passes after. The note on the task says what the journey ended on
     * before and after, so a person can see it.
     *
     * @param onMergedTree makes journeys on the merged, built tree
     * @param onStartTree  makes journeys on the tree the run started from
     */
    static String correctionRefused(JourneyFile.Journey original, String path, String corrected,
                                    java.util.function.Function<List<JourneyFile.Journey>,
                                        JourneyRunner.Outcome> onMergedTree,
                                    java.util.function.Function<List<JourneyFile.Journey>,
                                        JourneyRunner.Outcome> onStartTree) {
        return judgeCorrection(new JourneyFile.Result(original, false, null), path, corrected,
            onMergedTree, onStartTree).refused();
    }

    /**
     * What judging a corrected journey came to (section 75).
     *
     * @param refused null when the correction is taken; otherwise why it is not
     * @param further null unless the correction is taken although it still fails on the merged
     *                tree: then what the browser made of it there - the step it fails at now,
     *                later than the journey it replaces, and the page as read at that step
     * @param journey the correction as read; null when it is not a well-formed journey
     */
    record Correction(String refused, JourneyFile.Result further, JourneyFile.Journey journey) {

        boolean taken() {
            return refused == null;
        }

        boolean getsFurther() {
            return refused == null && further != null;
        }
    }

    /**
     * Judges a corrected journey against the journey it replaces, as it failed.
     *
     * <p>Section 70 took a correction only when it PASSED on the merged tree. Live run 101: the
     * journey failed at step 1, expecting a text only the starting application shows; its
     * author's correction passed step 1 and failed at step 2, a click on a button. It was
     * refused, the journey its author had just called wrong stayed, and the run stopped. A
     * correction that fails at a LATER step than the journey it replaces is the better
     * journey: it is taken now, on the same other guards, and the run goes on with what stops
     * it at the new step ({@link #afterFurther}). Decided from the two step numbers; a failure
     * that is not a step's (number 0) on either side is never "later".
     *
     * <p>The other guards are unchanged and are asked of both kinds: well formed; not weaker
     * by its form; fails on the tree the run started from; its last expectation not already
     * true there. One is added for both: an expectation it makes before doing anything is not
     * already true on the start tree's entry page ({@code JourneyExpectations.leading}), tried
     * in the same browser run.
     *
     * @param failed the journey being replaced, with the step it failed at
     */
    static Correction judgeCorrection(JourneyFile.Result failed, String path, String corrected,
                                      java.util.function.Function<List<JourneyFile.Journey>,
                                          JourneyRunner.Outcome> onMergedTree,
                                      java.util.function.Function<List<JourneyFile.Journey>,
                                          JourneyRunner.Outcome> onStartTree) {
        JourneyFile.Read read = JourneyFile.read(path, corrected);
        if (!read.ok()) {
            return new Correction("it is not a well-formed journey: "
                + read.objection().replace('\n', ' '), null, null);
        }
        JourneyFile.Journey journey = read.journey();
        List<String> weaker =
            com.swarmcoder.verify.JourneyExpectations.weakened(failed.journey(), journey);
        if (!weaker.isEmpty()) {
            return new Correction("it asks for less than the journey it replaces: "
                + String.join("; ", weaker), null, journey);
        }
        JourneyRunner.Outcome merged = onMergedTree.apply(List.of(journey));
        if (merged == null || !merged.made() || merged.results().isEmpty()) {
            return new Correction("it could not be made on the merged tree: " + whyNot(merged),
                null, journey);
        }
        JourneyFile.Result onMerged = merged.results().get(0);
        JourneyFile.Result further = null;
        if (!onMerged.passed()) {
            if (failed.step() < 1 || onMerged.step() <= failed.step()) {
                return new Correction("it fails on the merged tree too"
                    + (failed.step() < 1 || onMerged.step() < 1 ? ""
                        : ", and no later than the journey it replaces (at step "
                            + onMerged.step() + "; that one failed at step " + failed.step()
                            + ")")
                    + " - " + onMerged.failure(), null, journey);
            }
            further = onMerged;
        }
        List<JourneyFile.Journey> onStart = new ArrayList<>(List.of(journey));
        JourneyFile.Journey ending =
            com.swarmcoder.verify.JourneyExpectations.lastExpectationAlone(journey);
        if (ending != null) {
            onStart.add(ending);
        }
        int leadingFrom = onStart.size();
        onStart.addAll(com.swarmcoder.verify.JourneyExpectations.leadingAlone(journey));
        JourneyRunner.Outcome before = onStartTree.apply(onStart);
        if (before == null || !before.made() || before.results().isEmpty()) {
            return new Correction("it could not be made on the tree the run started from: "
                + whyNot(before), null, journey);
        }
        if (before.results().get(0).passed()) {
            return new Correction("it passes on the application as it was before the story, so "
                + "it shows nothing about what the story adds", null, journey);
        }
        if (ending != null && before.results().size() > 1 && before.results().get(1).passed()) {
            return new Correction("what its last step expects (`" + lastStepOf(journey) + "`) is "
                + "already on the entry page of the application as it was before the story, "
                + "so its ending shows nothing about what the story adds", null, journey);
        }
        List<com.swarmcoder.verify.JourneyExpectations.Leading> there =
            before.results().size() <= leadingFrom ? List.of()
                : com.swarmcoder.verify.JourneyExpectations.alreadyThere(journey,
                    before.results().subList(leadingFrom, before.results().size()));
        if (!there.isEmpty()) {
            return new Correction(
                com.swarmcoder.verify.JourneyExpectations.alreadyThereObjection(there), null,
                journey);
        }
        return new Correction(null, further, journey);
    }

    /** What follows a correction that was taken although it still fails, at a later step. */
    enum NextMove {
        /** The owning task's one worker repair round, with the corrected journey and its step. */
        WORKERS,
        /** The integration is made again; the journey fails there and its author is asked. */
        AUTHOR_AGAIN,
        /** Neither is left: the run stops, with the corrected journey committed. */
        PARK
    }

    /**
     * The next move after a correction that gets further was taken (section 75), from the
     * task's own marks and the two step numbers - no model. Asked AFTER the review that
     * produced the correction is recorded on the task.
     *
     * <ol>
     *   <li>the task's one worker repair round is unused: the workers get the corrected
     *       journey and the step it fails at now. The screen must expose what a step names
     *       (section 69), so a step the screen does not answer is first theirs;</li>
     *   <li>that round is used and the journey may still go to its author
     *       ({@link #goesToItsAuthor}: fewer than {@link #MAX_REVIEWS} reviews, and it fails
     *       later than at its last review): the author is asked once more, now shown the page
     *       at the new step, which it has not seen;</li>
     *   <li>otherwise nothing is left that could move the step, and the run stops.</li>
     * </ol>
     *
     * <p>Every move uses up a mark that is never given back (the repair round; a review), so
     * no sequence of moves repeats.
     */
    static NextMove afterFurther(Task task, JourneyFile.Result further) {
        if (!task.journeyRepairAttempted()) {
            return NextMove.WORKERS;
        }
        String path = further.journey().path();
        return reviewsOf(task, path) < MAX_REVIEWS
            && further.step() > lastReviewedStep(task, path)
            ? NextMove.AUTHOR_AGAIN : NextMove.PARK;
    }

    /**
     * The one move for a task with several such corrections: the workers when their round is
     * unused (it covers every journey of the task); otherwise the author again when any of
     * them may still go back; otherwise the stop.
     */
    static NextMove afterFurther(Task task, List<JourneyFile.Result> further) {
        NextMove move = NextMove.PARK;
        for (JourneyFile.Result result : further) {
            NextMove one = afterFurther(task, result);
            if (one == NextMove.WORKERS) {
                return one;
            }
            move = one == NextMove.AUTHOR_AGAIN ? one : move;
        }
        return move;
    }

    /** The note on the task for a correction that was taken although it still fails. */
    static String furtherNote(JourneyFile.Result replaced, JourneyFile.Result further,
                              String reason) {
        return "The journey's author answered that the journey was wrong and corrected it: "
            + reason + " The correction still fails on the merged tree, but at step "
            + further.step() + " of " + further.journey().steps().size() + " where the journey "
            + "it replaces failed at step " + replaced.step() + " of "
            + replaced.journey().steps().size() + "; it fails on the tree the run started from "
            + "too, so it is taken and replaces the journey. It now fails on: "
            + further.failure();
    }

    /**
     * What the run stops on when a correction that gets further was taken and neither the
     * workers' repair round nor another review by its author is left (section 75).
     */
    static String furtherAndNothingLeft(Task task, List<JourneyFile.Result> further) {
        StringBuilder text = new StringBuilder("The journey went back to its author, whose "
            + "correction gets further than the journey it replaces and still fails. The "
            + "correction is committed with the run's tests. Task '" + task.title() + "':\n");
        for (JourneyFile.Result result : further) {
            text.append("- ").append(result.journey().path()).append(": ")
                .append(result.failure()).append('\n');
        }
        return text.append(task.journeyReviewNote() == null ? "" : task.journeyReviewNote())
            .append("\n\nNo worker was started: this task's one repair round is used, and a "
                + "journey is reviewed by its author at most " + MAX_REVIEWS + " times in a "
                + "run. Correct the screen or the journey by hand and resume.").toString();
    }

    /**
     * The paragraph a repair worker is given after a correction that gets further: the journey
     * as its author corrected it, and where it fails now.
     */
    static String repairEvidenceAfterCorrection(List<JourneyFile.Result> stillFailing) {
        return repairEvidence(stillFailing) + "The journey above is the journey as its author "
            + "CORRECTED it after it first failed; acceptance_test shows that file now. The "
            + "steps before the failing one pass on what this task built.\n";
    }

    /**
     * What the red check says about a journey that begins by expecting what the application
     * already shows (section 75); null when it has no such step. A note, not a stop: its author
     * is no longer in a session, and the run does not stop on a step that may be harmless.
     */
    static String startsOnWhatWasThere(JourneyFile.Journey journey,
                                       List<JourneyFile.Result> leadingAlone) {
        List<com.swarmcoder.verify.JourneyExpectations.Leading> there =
            com.swarmcoder.verify.JourneyExpectations.alreadyThere(journey, leadingAlone);
        return there.isEmpty() ? null : "NOTE - \"" + journey.name() + "\": "
            + com.swarmcoder.verify.JourneyExpectations.alreadyThereObjection(there)
            + " If the story replaces what that page shows, this journey fails at that step "
            + "after the last merge and goes back to its author then.";
    }

    private static String whyNot(JourneyRunner.Outcome outcome) {
        return outcome == null ? "no result"
            : outcome.couldNotRun() != null ? outcome.couldNotRun()
            : outcome.didNotStart() != null ? outcome.didNotStart() : "no result";
    }

    /**
     * What the author of a failed journey is shown (section 69): the journey in words, the
     * failing step with what the browser said, and what the page showed at that step - read by
     * the browser in the container, bounded there and here.
     */
    static String sendBackEvidence(JourneyFile.Result failed) {
        return failed.journey().describe() + "\nMade in a real browser on the merged tree, from "
            + "the application's entry page: " + failed.failure()
            + "\n\nWHAT THE PAGE SHOWED AT THAT STEP (read by the browser; every element with "
            + "a role and an accessible name, what each drop-down list offers and shows as "
            + "chosen, the fields' placeholders, the visible text - which also lists a "
            + "drop-down list's options, though nobody sees them until it is opened):\n"
            + (failed.seen() == null || failed.seen().isBlank()
                ? "(the browser gave no reading of the page)" : failed.seen());
    }

    /**
     * The same paragraph, with what the journey's author answered when it was asked first
     * (section 69). The workers are told the author's reason exactly as it gave it.
     */
    static String repairEvidence(String evidence, String authorNote) {
        if (authorNote == null || authorNote.isBlank()) {
            return evidence;
        }
        return (evidence == null ? "" : evidence)
            + "\nTHE JOURNEY'S AUTHOR WAS ASKED FIRST whether the journey or the screen is "
            + "wrong. " + authorNote.strip() + "\n";
    }

    /** The paragraph a repair worker is given when the journey its task claims failed. */
    static String repairEvidence(List<JourneyFile.Result> failed) {
        StringBuilder text = new StringBuilder("--- a journey failed in a real browser ---\n"
            + "Every task of this run was merged, the application was started, and a browser "
            + "made the journey this task claims: from the application's entry page, using only "
            + "what is on the screen. It failed.\n");
        for (JourneyFile.Result result : failed) {
            // The journey itself, step by step (section 70): run 95's repair workers were told
            // to read it with acceptance_test, asked that tool for other names, and never saw it.
            text.append('\n').append(result.journey().describe()).append("It failed: ")
                .append(result.failure()).append('\n');
        }
        return text.append("\nThat is the whole journey (acceptance_test shows its file); you "
            + "cannot change it. The "
            + "JUnit acceptance tests passed, so the code behind the screen works. What is "
            + "missing is on the screen or on the way to it: the element the failing step names "
            + "is not there, is not visible, is not reachable by the steps before it, or is "
            + "named differently from the journey's selector. Make the application match the "
            + "journey, step by step: every role, accessible name and text a selector of the "
            + "journey uses must be on the screen exactly as the journey writes it.\n").toString();
    }
}
