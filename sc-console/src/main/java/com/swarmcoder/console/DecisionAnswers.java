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

import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.InstructedRepair;
import com.swarmcoder.domain.InstructedRetest;
import com.swarmcoder.domain.JourneyVerdict;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.SupersededRuns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers a question a build stopped to ask, and hands the stopped build back to its engine.
 *
 * <p>Until this existed, answering only rewrote the decision row ({@code
 * ControlServiceImpl.resolveDecision}); a parked run was taken up again only when the process
 * started, or by the journey harness, which carried its own record-and-resume. This is that
 * hand-back, in the product, for every caller: the answer is written through the Console's own
 * control service in the form the product's code reads, and when the decision belongs to a run that
 * is parked, the run goes back to the workflow engine of its own project, which retries the stage
 * it parked in.
 *
 * <p>Deterministic: nothing here calls a model.
 */
public final class DecisionAnswers {

    private static final Logger log = LoggerFactory.getLogger(DecisionAnswers.class);

    /**
     * The thread that parked a run is still finishing its own bookkeeping for a moment after the
     * park mark is written. A run is never handed back sooner than this after it parked, so two
     * threads are never on one run.
     */
    static final long SETTLE_MILLIS = 3_000;

    /**
     * A run handed back for one stop is not handed back again for the same stop inside this long.
     * A second question of the same stop answered a moment later must not put a second thread on
     * the run; a run that stops again later carries a new stop time and is handed back as usual.
     */
    private static final long RESUME_GUARD_MILLIS = 60_000;

    /** A stop already handed back: when the run parked, and when it was handed back. */
    private record HandedBack(Instant parkedAt, long at) {}

    private static final Map<UUID, HandedBack> RESUMED_AT = new ConcurrentHashMap<>();

    private DecisionAnswers() {}

    /** One answer a decision accepts. */
    public record Option(String token, String description, boolean acceptsText) {}

    /**
     * How an answer ended.
     *
     * @param error   null when the answer was recorded, else why it was not
     * @param resumed true when a parked run was handed back to its engine
     * @param note    one sentence saying what happened, for whoever answered
     */
    public record Outcome(String error, boolean resumed, String note) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * The answers the product can act on for a decision of this kind.
     *
     * <p>The Console's own answer box takes free text for every kind, so every kind accepts text
     * here. A question about a rule is read by the swarm's rule-question parser, which acts on the
     * first word: keep, reword, allow or repair.
     */
    public static List<Option> optionsFor(DecisionKind kind) {
        List<Option> options = new ArrayList<>();
        if (kind == DecisionKind.GUIDELINE_REVIEW) {
            options.add(new Option("keep", "the rule stands as written; the task is built again "
                + "under it (text is ignored)", false));
            options.add(new Option("reword", "the rule changes for the whole project. text = the "
                + "new wording; empty text takes the suggestion printed in the question", true));
            options.add(new Option("allow", "this task may do what the candidates did; the rule "
                + "gains that exception (text is ignored)", false));
            options.add(new Option("repair", "the rule stands and the task is built again with the "
                + "files the question names added to what it may edit; only when the question "
                + "names such files (text is ignored)", false));
        } else if (kind == DecisionKind.BLOCKED_TASK) {
            options.add(new Option("retry", "record the text as the answer (optional) and run the "
                + "stage the build stopped in again. Change something first or expect the same "
                + "stop", true));
        } else if (kind == DecisionKind.BUDGET_EXTENSION) {
            options.add(new Option("extend", "raise the limit that stopped the build by the same "
                + "amount again, then run the stage it stopped in again. Refused, and nothing "
                + "recorded, when no limit is on record as having stopped it", true));
            options.add(new Option("stop", "leave the build stopped and record the answer. The "
                + "limit is unchanged", true));
        } else if (kind == DecisionKind.APPROVAL) {
            options.add(new Option("note", "record the text. A run waiting for approval is "
                + "approved or rejected as a run, not by answering", true));
        }
        return options;
    }

    /**
     * The answers the product can act on for THIS question: those of its kind, and for a
     * stopped build's question {@code repair} when one more repair round is sound for it
     * (section 84, {@link InstructedRepair}). That is decided from what the engine recorded
     * when it raised the question - which tasks it may be answered {@code repair} for - and
     * from the count on each task, never from the question's words. And {@code retest}, which
     * sends the task's acceptance test back to its author, where {@link #retestTasks} names a
     * task (section 86). And, for a failed final integration that stopped on a journey,
     * {@code screen} and {@code journey}: the verdict of whoever answers on which side of the
     * failed journey is wrong, where {@link #screenTasks} and {@link #journeyTasks} name a
     * task (section 88, {@link JourneyVerdict}).
     */
    public static List<Option> optionsFor(ArtifactStore store, Decision decision) {
        List<Option> options = optionsFor(decision.kind());
        List<Task> tasks = repairTasks(store, decision);
        if (whyNoRepair(store, decision, tasks) == null) {
            options.add(new Option(InstructedRepair.TOKEN, "give the workers of "
                + titles(tasks) + " one more repair round, started from the candidates the "
                + "task already has. text is REQUIRED: it is what the repair workers are told "
                + "to change. " + roundsLeft(tasks) + " Use it when the cause is something the "
                + "workers must change; retry repeats the same stop", true));
        } else {
            // A stop the facts name several tasks for, or none (section 92): the same round,
            // for the one task the answer names.
            List<Task> choices = repairChoices(store, decision);
            if (!choices.isEmpty() && whyNoRepair(store, decision, choices) == null) {
                options.add(new Option(InstructedRepair.TOKEN, "give the workers of ONE task "
                    + "one more repair round, started from the candidate the task has. task "
                    + "is REQUIRED: one of " + String.join(", ", choices.stream()
                        .map(task -> "'" + task.title() + "'").toList())
                    + ". text is REQUIRED: it is what the repair workers are told to change. "
                    + roundsLeft(choices) + " retry repeats the same stop", true));
            }
        }
        List<Task> retesting = retestTasks(store, decision);
        if (whyNoRetest(store, decision, retesting) == null) {
            options.add(new Option(InstructedRetest.TOKEN, "send the acceptance test of "
                + titles(retesting) + " back to its author. text is REQUIRED: it is what the "
                + "author is told is wrong with the test and must change. The correction is "
                + "checked like a first-written test and committed on the run's tests branch; "
                + "the task is then verified again from the candidates it already has and "
                + "built again only if none passes. The story is not designed again. "
                + retestsLeft(retesting) + " Use it when the test, not the candidates, is "
                + "what is wrong: no worker may change an acceptance test, so repair cannot",
                true));
        }
        List<Task> screening = screenTasks(store, decision);
        if (whyNoScreen(store, decision, screening) == null) {
            options.add(new Option(JourneyVerdict.SCREEN, "you rule that the journey is right "
                + "and the screen is wrong. " + capitalised(titles(screening)) + " get"
                + (screening.size() == 1 ? "s" : "") + " one repair round, started from the "
                + "candidate the task has chosen, for the journey(s) " + disputed(screening)
                + ". text is REQUIRED: it is what the workers are told the screen must do. "
                + "Your ruling outranks what the journey's author answered, is recorded as "
                + "such, and the journey is not sent back to its author again. "
                + roundsLeft(screening), true));
        }
        List<Task> correcting = journeyTasks(store, decision);
        if (whyNoJourney(store, decision, correcting) == null) {
            options.add(new Option(JourneyVerdict.JOURNEY, "you rule that the journey is "
                + "wrong. The journey(s) " + correctable(correcting) + " of "
                + titles(correcting) + " go back to their author. text is REQUIRED: it is how "
                + "the journey must be corrected. The correction is tried in a browser on the "
                + "tree the run started from and on the merged tree, like any correction; it "
                + "may ask for less than the journey it replaces, and exactly what it drops "
                + "is then recorded in the decision log and shown with the delivery. No "
                + "worker is started. " + correctionsLeft(correcting), true));
        }
        return options;
    }

    /**
     * The tasks of the question's build that it may be answered {@code screen} for (section
     * 88): the build is at final integration and the workflow marked the question as one
     * about a failed journey whose screen is that task's. Read from what was recorded, never
     * from the question's words.
     */
    static List<Task> screenTasks(ArtifactStore store, Decision decision) {
        return markedAtFinalIntegration(store, decision, true);
    }

    /** The tasks whose failed journeys the question may be answered {@code journey} for. */
    static List<Task> journeyTasks(ArtifactStore store, Decision decision) {
        return markedAtFinalIntegration(store, decision, false);
    }

    private static List<Task> markedAtFinalIntegration(ArtifactStore store, Decision decision,
                                                       boolean screen) {
        if (decision == null || decision.kind() != DecisionKind.BLOCKED_TASK
                || decision.runId() == null) {
            return List.of();
        }
        Run run = store.root().runs.get(decision.runId());
        TaskGraph graph = run == null || run.taskGraphId() == null ? null
            : store.root().taskGraphs.get(run.taskGraphId());
        if (graph == null || graph.tasks() == null
                || run.state() != RunState.FINAL_INTEGRATION) {
            return List.of();
        }
        return graph.tasks().stream().filter(task -> decision.id().equals(screen
            ? task.screenVerdictOfferedBy() : task.journeyVerdictOfferedBy())).toList();
    }

    /** Why this question cannot be answered {@code screen}, or null when it can. */
    static String whyNoScreen(ArtifactStore store, Decision decision, List<Task> tasks) {
        String common = whyNoVerdict(store, decision, tasks);
        if (common != null) {
            return common;
        }
        for (Task task : tasks) {
            if (task.selectedCandidateId() == null) {
                return "task '" + task.title() + "' has no chosen candidate to start a repair "
                    + "round from";
            }
            if (!InstructedRepair.roundLeft(task)) {
                return "task '" + task.title() + "' has already had "
                    + task.instructedRepairRounds() + " repair round(s) on an instruction in "
                    + "this build, and " + InstructedRepair.limit() + " is the most a task is "
                    + "given (-D" + InstructedRepair.LIMIT_PROPERTY + " changes it). Send the "
                    + "story back, or change something and retry";
            }
        }
        return null;
    }

    /** Why this question cannot be answered {@code journey}, or null when it can. */
    static String whyNoJourney(ArtifactStore store, Decision decision, List<Task> tasks) {
        String common = whyNoVerdict(store, decision, tasks);
        if (common != null) {
            return common;
        }
        for (Task task : tasks) {
            if (JourneyVerdict.correctable(task).isEmpty()) {
                return "the journey(s) of task '" + task.title() + "' this question is about "
                    + "have already gone back to their author on an instruction in this "
                    + "build, and " + JourneyVerdict.limit() + " is the most a journey is "
                    + "given (-D" + JourneyVerdict.LIMIT_PROPERTY + " changes it). Correct "
                    + "the journey by hand on the run's tests branch and retry, or send the "
                    + "story back";
            }
        }
        return null;
    }

    private static String whyNoVerdict(ArtifactStore store, Decision decision,
                                       List<Task> tasks) {
        if (decision.kind() != DecisionKind.BLOCKED_TASK) {
            return "this kind of question is not about a journey";
        }
        if (decision.state() != DecisionState.PENDING) {
            return "that question has already been answered";
        }
        if (tasks.isEmpty()) {
            return "this question is not one about a failed journey that names a task of its "
                + "build: the build did not stop at final integration on a journey, or no "
                + "one task could be named for the journey that failed";
        }
        Run run = store.root().runs.get(decision.runId());
        if (run == null || run.parkedAt() == null) {
            return "the build this question came from is not stopped waiting on it";
        }
        return null;
    }

    private static String disputed(List<Task> tasks) {
        return tasks.stream().flatMap(task -> task.disputedJourneys().stream()).distinct()
            .toList().toString();
    }

    private static String correctable(List<Task> tasks) {
        return tasks.stream().flatMap(task -> JourneyVerdict.correctable(task).stream())
            .distinct().toList().toString();
    }

    private static String correctionsLeft(List<Task> tasks) {
        int used = 0;
        for (Task task : tasks) {
            for (String path : JourneyVerdict.correctable(task)) {
                used = Math.max(used, JourneyVerdict.given(task, path));
            }
        }
        return "Time " + (used + 1) + " of the " + JourneyVerdict.limit()
            + " a journey goes back on an instruction per build.";
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text
            : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    /**
     * The tasks of the question's build whose acceptance test may be sent back to its author
     * in answer to it (section 86, {@link InstructedRetest}): the build is at its build stage,
     * the engine marked the question as being about that one task and not about a fault of
     * the plan - the mark that offers {@code repair}, or the workflow's own mark for a stop on
     * the task's test - and the task has an acceptance test file of its own. Read from what
     * was recorded, never from the question's words.
     */
    static List<Task> retestTasks(ArtifactStore store, Decision decision) {
        if (decision == null || decision.kind() != DecisionKind.BLOCKED_TASK
                || decision.runId() == null) {
            return List.of();
        }
        Run run = store.root().runs.get(decision.runId());
        TaskGraph graph = run == null || run.taskGraphId() == null ? null
            : store.root().taskGraphs.get(run.taskGraphId());
        if (graph == null || graph.tasks() == null || run.state() != RunState.EXECUTING) {
            return List.of();
        }
        return graph.tasks().stream()
            .filter(task -> decision.id().equals(task.repairOfferedBy())
                || decision.id().equals(task.retestOfferedBy()))
            .filter(InstructedRetest::hasATest).toList();
    }

    /** Why this question cannot be answered {@code retest}, or null when it can. */
    static String whyNoRetest(ArtifactStore store, Decision decision, List<Task> tasks) {
        if (decision.kind() != DecisionKind.BLOCKED_TASK) {
            return "this kind of question has no acceptance test to send back";
        }
        if (decision.state() != DecisionState.PENDING) {
            return "that question has already been answered";
        }
        if (tasks.isEmpty()) {
            return "there is no acceptance test to send back here: the build did not stop at "
                + "its build stage on one task that has an acceptance test of its own (it "
                + "stopped for a fault of the plan, at final integration, or on something that "
                + "is not one task's)";
        }
        Run run = store.root().runs.get(decision.runId());
        if (run == null || run.parkedAt() == null) {
            return "the build this question came from is not stopped waiting on it";
        }
        for (Task task : tasks) {
            if (!InstructedRetest.left(task)) {
                return "the acceptance test of task '" + task.title() + "' has already gone "
                    + "back to its author " + task.instructedRetests() + " time(s) on an "
                    + "instruction in this build, and " + InstructedRetest.limit() + " is the "
                    + "most a task is given (-D" + InstructedRetest.LIMIT_PROPERTY + " changes "
                    + "it). Correct the test by hand on the run's tests branch and retry, or "
                    + "send the story back";
            }
        }
        return null;
    }

    private static String retestsLeft(List<Task> tasks) {
        int used = tasks.stream().mapToInt(Task::instructedRetests).max().orElse(0);
        return "Time " + (used + 1) + " of the " + InstructedRetest.limit()
            + " a task's test is sent back per build.";
    }

    /** The tasks of the question's build that it may be answered {@code repair} for. */
    static List<Task> repairTasks(ArtifactStore store, Decision decision) {
        if (decision == null || decision.kind() != DecisionKind.BLOCKED_TASK
                || decision.runId() == null) {
            return List.of();
        }
        Run run = store.root().runs.get(decision.runId());
        TaskGraph graph = run == null || run.taskGraphId() == null ? null
            : store.root().taskGraphs.get(run.taskGraphId());
        if (graph == null || graph.tasks() == null) {
            return List.of();
        }
        return graph.tasks().stream()
            .filter(task -> decision.id().equals(task.repairOfferedBy())).toList();
    }

    /**
     * The tasks of the question's build that it may be answered {@code repair} for when the
     * answer names one of them (section 92), and that still have a round left.
     */
    static List<Task> repairChoices(ArtifactStore store, Decision decision) {
        if (decision == null || decision.kind() != DecisionKind.BLOCKED_TASK
                || decision.runId() == null) {
            return List.of();
        }
        Run run = store.root().runs.get(decision.runId());
        TaskGraph graph = run == null || run.taskGraphId() == null ? null
            : store.root().taskGraphs.get(run.taskGraphId());
        if (graph == null || graph.tasks() == null) {
            return List.of();
        }
        return graph.tasks().stream()
            .filter(task -> decision.id().equals(task.repairChoiceOfferedBy()))
            .filter(InstructedRepair::roundLeft).toList();
    }

    /** The one of {@code choices} that {@code named} names, by its title or its id; or null. */
    static Task chosenAmong(List<Task> choices, String named) {
        String wanted = named == null ? "" : named.strip();
        if (wanted.length() > 1 && wanted.startsWith("'") && wanted.endsWith("'")) {
            wanted = wanted.substring(1, wanted.length() - 1).strip();
        }
        if (wanted.isEmpty()) {
            return null;
        }
        final String name = wanted;
        List<Task> matching = choices.stream()
            .filter(task -> name.equalsIgnoreCase(task.title() == null ? "" : task.title().strip())
                || name.equalsIgnoreCase(String.valueOf(task.id()))).toList();
        return matching.size() == 1 ? matching.get(0) : null;
    }

    /** Why this question cannot be answered {@code repair}, or null when it can. */
    static String whyNoRepair(ArtifactStore store, Decision decision, List<Task> tasks) {
        if (decision.kind() != DecisionKind.BLOCKED_TASK) {
            return "this kind of question has no repair round to give";
        }
        if (decision.state() != DecisionState.PENDING) {
            return "that question has already been answered";
        }
        if (tasks.isEmpty()) {
            return "one more repair round could not succeed here: the build did not stop on "
                + "one task that has a candidate to repair (it stopped for a fault of the plan, "
                + "or on something that is not one task's)";
        }
        Run run = store.root().runs.get(decision.runId());
        if (run == null || run.parkedAt() == null || run.state() == RunState.DELIVERED
                || run.state() == RunState.ABORTED || run.state() == RunState.ABANDONED) {
            return "the build this question came from is not stopped waiting on it";
        }
        for (Task task : tasks) {
            if (!InstructedRepair.roundLeft(task)) {
                return "task '" + task.title() + "' has already had "
                    + task.instructedRepairRounds() + " repair round(s) on an instruction in "
                    + "this build, and " + InstructedRepair.limit() + " is the most a task is "
                    + "given (-D" + InstructedRepair.LIMIT_PROPERTY + " changes it). Send the "
                    + "story back, or change something and retry";
            }
        }
        return null;
    }

    private static String titles(List<Task> tasks) {
        return (tasks.size() == 1 ? "task " : "tasks ") + String.join(", ",
            tasks.stream().map(task -> "'" + task.title() + "'").toList());
    }

    private static String roundsLeft(List<Task> tasks) {
        int used = tasks.stream().mapToInt(Task::instructedRepairRounds).max().orElse(0);
        return "Round " + (used + 1) + " of the " + InstructedRepair.limit()
            + " a task is given per build.";
    }

    /** What is written on the decision for an answer, in the form the product's own code reads. */
    public static String responseFor(DecisionKind kind, String token, String text, String actor) {
        String word = token == null ? "" : token.strip().toLowerCase(Locale.ROOT);
        String said = text == null ? "" : text.strip();
        if (kind == DecisionKind.GUIDELINE_REVIEW) {
            return switch (word) {
                case "reword" -> said.isEmpty() ? "reword" : "reword: " + said;
                case "allow" -> "allow";
                case "repair" -> "repair";
                default -> "keep";
            };
        }
        return "Answered by the " + (actor == null || actor.isBlank() ? "operator" : actor) + " ("
            + word + ")" + (said.isEmpty() ? "" : ": " + said);
    }

    /**
     * Records an answer and, when the decision belongs to a parked run, hands that run back to its
     * engine.
     *
     * @param decisionId the decision's id
     * @param token      one of {@link #optionsFor}'s tokens for the decision's kind
     * @param text       free text, where the option takes it
     * @param actor      who is answering, written into the recorded answer
     */
    public static Outcome answerAndResume(String decisionId, String token, String text,
                                          String actor) {
        return answerAndResume(decisionId, token, text, null, actor); // no task named
    }

    /**
     * @param taskNamed for {@code repair} on a question that offers it for one of several tasks
     *             (section 92): the title or id of the task chosen. Ignored elsewhere
     */
    public static Outcome answerAndResume(String decisionId, String token, String text,
                                          String taskNamed, String actor) {
        ConsoleContext context = ConsoleContext.get();
        if (context == null) {
            return failed("the console is not wired up yet");
        }
        UUID id;
        try {
            id = UUID.fromString(decisionId == null ? "" : decisionId.strip());
        } catch (IllegalArgumentException e) {
            return failed("'" + decisionId + "' is not a decision id");
        }
        ArtifactStore store = context.store();
        Decision decision = store.root().decisions.get(id);
        if (decision == null) {
            return failed("there is no question with that id");
        }
        String earlier = fromAnEarlierBuild(store, decision);
        if (earlier != null) {
            return failed(earlier);
        }
        if (decision.state() == DecisionState.RESOLVED) {
            return failed("that question has already been answered");
        }
        String word = token == null ? "" : token.strip().toLowerCase(Locale.ROOT);
        List<Option> options = optionsFor(store, decision);
        List<Task> repairing = List.of();
        List<Task> chosenFrom = List.of();
        if (decision.kind() == DecisionKind.BLOCKED_TASK && word.equals(InstructedRepair.TOKEN)) {
            repairing = repairTasks(store, decision);
            if (repairing.isEmpty()) {
                // Offered for one of several tasks: the answer says which (section 92).
                chosenFrom = repairChoices(store, decision);
                if (!chosenFrom.isEmpty()) {
                    Task chosen = chosenAmong(chosenFrom, taskNamed);
                    if (chosen == null) {
                        return failed("Not recorded: this question offers repair for one of "
                            + "several tasks, so repair needs task: one of "
                            + String.join(", ", chosenFrom.stream()
                                .map(one -> "'" + one.title() + "'").toList())
                            + (taskNamed == null || taskNamed.isBlank() ? "" : " ('" + taskNamed.strip()
                                + "' is not one of them)") + ".");
                    }
                    repairing = List.of(chosen);
                }
            }
            String whyNot = whyNoRepair(store, decision, repairing);
            if (whyNot != null) {
                return failed("Not recorded: " + whyNot + ". The answers this question accepts "
                    + "are " + options.stream().map(Option::token).toList());
            }
            if (text == null || text.isBlank()) {
                return failed("Not recorded: repair needs text. It is what the repair workers "
                    + "are told to change, and a round with nothing new to go on repeats the "
                    + "one that failed.");
            }
        }
        List<Task> retesting = List.of();
        if (decision.kind() == DecisionKind.BLOCKED_TASK && word.equals(InstructedRetest.TOKEN)) {
            retesting = retestTasks(store, decision);
            String whyNot = whyNoRetest(store, decision, retesting);
            if (whyNot != null) {
                return failed("Not recorded: " + whyNot + ". The answers this question accepts "
                    + "are " + options.stream().map(Option::token).toList());
            }
            if (text == null || text.isBlank()) {
                return failed("Not recorded: retest needs text. It is what the test's author "
                    + "is told is wrong with the test and must change; sent back with nothing "
                    + "new to go on, the author has no reason to write it differently.");
            }
        }
        List<Task> screening = List.of();
        if (decision.kind() == DecisionKind.BLOCKED_TASK && word.equals(JourneyVerdict.SCREEN)) {
            screening = screenTasks(store, decision);
            String whyNot = whyNoScreen(store, decision, screening);
            if (whyNot != null) {
                return failed("Not recorded: " + whyNot + ". The answers this question accepts "
                    + "are " + options.stream().map(Option::token).toList());
            }
            if (text == null || text.isBlank()) {
                return failed("Not recorded: screen needs text. It is what the workers are "
                    + "told the screen must do; a ruling with no reason gives them nothing to "
                    + "build from.");
            }
        }
        List<Task> correcting = List.of();
        if (decision.kind() == DecisionKind.BLOCKED_TASK && word.equals(JourneyVerdict.JOURNEY)) {
            correcting = journeyTasks(store, decision);
            String whyNot = whyNoJourney(store, decision, correcting);
            if (whyNot != null) {
                return failed("Not recorded: " + whyNot + ". The answers this question accepts "
                    + "are " + options.stream().map(Option::token).toList());
            }
            if (text == null || text.isBlank()) {
                return failed("Not recorded: journey needs text. It is how the journey's "
                    + "author is told to correct it; sent back with nothing new to go on, the "
                    + "author answers as it did before.");
            }
        }
        if (options.stream().noneMatch(option -> option.token().equals(word))) {
            return failed("'" + token + "' is not an answer this question accepts; the answers are "
                + options.stream().map(Option::token).toList());
        }
        String raised = "";
        if (decision.kind() == DecisionKind.BUDGET_EXTENSION && word.equals("extend")) {
            if (decision.runId() == null) {
                return failed("Not extended: this question names no build, so there is no limit "
                    + "to raise.");
            }
            String result = context.extendBudget(decision.runId());
            if (result.startsWith("error:")) {
                return failed("Not extended: " + result.substring("error:".length()).strip());
            }
            raised = result + " ";
        }
        if (!repairing.isEmpty()) {
            // On the task before the answer is recorded and the build handed back: the engine
            // gives the round when it takes the run up again, and clears the instruction.
            for (Task one : chosenFrom) {
                // The chosen task carries the question's mark now, as a task named by the
                // build's own facts does; the round and its evidence follow from that mark.
                one.setRepairChoiceOfferedBy(null);
                if (!repairing.contains(one)) {
                    one.setStartOutput(null);
                    store.saveTask(one);
                }
            }
            for (Task one : repairing) {
                if (!chosenFrom.isEmpty()) {
                    one.setRepairOfferedBy(decision.id());
                }
                one.setRepairInstruction(text.strip());
                store.saveTask(one);
            }
            raised = titles(repairing).substring(0, 1).toUpperCase(Locale.ROOT)
                + titles(repairing).substring(1) + " get" + (repairing.size() == 1 ? "s" : "")
                + " one more repair round with the text as the workers' instruction. "
                + roundsLeft(repairing) + " ";
        }
        if (!retesting.isEmpty()) {
            // On the task before the answer is recorded and the build handed back: the
            // workflow sends the test back when it takes the run up again, and clears this.
            for (Task task : retesting) {
                task.setRetestInstruction(text.strip());
                store.saveTask(task);
            }
            raised = "The acceptance test of " + titles(retesting) + " goes back to its "
                + "author with the text as the instruction; the task is then verified again "
                + "from the candidates it has. " + retestsLeft(retesting) + " ";
        }
        if (!screening.isEmpty()) {
            // The verdict of whoever answers outranks the journey's author (section 88). On
            // the task before the answer is recorded: the instruction the engine gives the
            // repair round, the journeys now ruled right, and a sentence that says so.
            String who = actor == null || actor.isBlank() ? "operator" : actor;
            for (Task task : screening) {
                List<String> journeys = task.disputedJourneys();
                task.setRepairInstruction(JourneyVerdict.screenInstruction(journeys, who, text));
                List<String> upheld = new ArrayList<>(task.journeysUpheld());
                journeys.stream().filter(path -> !upheld.contains(path)).forEach(upheld::add);
                task.setJourneysUpheld(upheld);
                String noted = task.journeyReviewNote();
                task.setJourneyReviewNote((noted == null || noted.isBlank() ? ""
                    : noted + " ") + JourneyVerdict.upheldNote(journeys, who, text));
                store.saveTask(task);
            }
            raised = "Recorded as a ruling that the journey is right and the screen is wrong; "
                + "it outranks the answer of the journey's author. " + capitalised(
                    titles(screening)) + " get" + (screening.size() == 1 ? "s" : "")
                + " one repair round with the text as the workers' instruction. "
                + roundsLeft(screening) + " ";
        }
        if (!correcting.isEmpty()) {
            // On the task before the answer is recorded and the build handed back: the stage
            // merges and verifies the build again, the journey fails again, and it then goes
            // to its author with this text; the workflow clears it.
            String who = actor == null || actor.isBlank() ? "operator" : actor;
            String journeys = correctable(correcting);
            for (Task task : correcting) {
                List<String> sentBack = JourneyVerdict.correctable(task);
                task.setJourneyInstruction(text.strip());
                task.setJourneyInstructionBy(who);
                task.setJourneysUpheld(task.journeysUpheld().stream()
                    .filter(path -> !sentBack.contains(path)).toList());
                store.saveTask(task);
            }
            raised = "Recorded as a ruling that the journey is wrong. The journey(s) "
                + journeys + " go back to their author with the text as the instruction, once "
                + "the stage has merged and verified the build again; a correction that asks "
                + "for less is taken when the browser shows it fails before the story and "
                + "gets further after it, and what it drops is recorded. "
                + correctionsLeft(correcting) + " ";
        }
        try {
            new ControlServiceImpl().recordAnswer(id.toString(),
                responseFor(decision.kind(), word, text, actor));
        } catch (RuntimeException e) {
            return failed("the answer could not be recorded: " + e.getMessage());
        }
        if (decision.kind() == DecisionKind.BUDGET_EXTENSION && !word.equals("extend")) {
            return new Outcome(null, false, "Recorded. The build stays stopped; its limit is "
                + "unchanged.");
        }
        return withPrefix(resumeStopped(context, store, decision, actor), raised);
    }

    /**
     * The Console's own answer: the person's free text. A spending-limit question whose text starts
     * with extend or stop is answered as that option; any other text is recorded as written (the
     * rule-question parser reads its own first word) and the stopped build, if there is one, is
     * handed back, as it is for the same answer given over the supervisor connection. A stopped
     * build's question whose text starts with repair, where that answer is offered, is answered
     * as that option with the rest of the text as the workers' instruction; one whose text
     * starts with retest, where that is offered, as that option with the rest as the
     * instruction to the author of the task's acceptance test (section 86). One whose text
     * starts with screen or journey, where a failed journey's question offers that verdict, is
     * answered as it with the rest as the instruction (section 88).
     */
    public static Outcome answerFromConsole(String decisionId, String text) {
        ConsoleContext context = ConsoleContext.get();
        if (context == null) {
            return failed("the console is not wired up yet");
        }
        UUID id = UUID.fromString(decisionId);
        Decision decision = context.store().root().decisions.get(id);
        if (decision == null) {
            return new Outcome(null, false, "There is no such question.");
        }
        String earlier = fromAnEarlierBuild(context.store(), decision);
        if (earlier != null) {
            return failed(earlier);
        }
        String said = text == null ? "" : text.strip();
        String first = said.split("[\\s:.,;]+", 2)[0].toLowerCase(Locale.ROOT);
        if (decision.kind() == DecisionKind.BUDGET_EXTENSION
                && (first.equals("extend") || first.equals("stop"))) {
            return answerAndResume(decisionId, first, said.substring(first.length()).strip(),
                "operator");
        }
        if (decision.kind() == DecisionKind.BLOCKED_TASK && first.equals(InstructedRepair.TOKEN)
                && whyNoRepair(context.store(), decision,
                    repairTasks(context.store(), decision)) == null) {
            // The same answer the supervisor connection gives, with the rest as the instruction.
            return answerAndResume(decisionId, first, said.substring(first.length())
                .replaceFirst("^[\\s:.,;]+", ""), "operator");
        }
        if (decision.kind() == DecisionKind.BLOCKED_TASK && first.equals(InstructedRepair.TOKEN)
                && !repairChoices(context.store(), decision).isEmpty()) {
            // Offered for one of several tasks (section 92): the task's title comes first,
            // then the instruction. The longest title the text begins with is the one meant.
            String rest = said.substring(first.length()).replaceFirst("^[\\s:.,;]+", "");
            String bare = rest.startsWith("'") ? rest.substring(1) : rest;
            Task chosen = null;
            for (Task choice : repairChoices(context.store(), decision)) {
                String title = choice.title() == null ? "" : choice.title().strip();
                if (!title.isEmpty() && bare.regionMatches(true, 0, title, 0, title.length())
                        && (chosen == null || title.length() > chosen.title().strip().length())) {
                    chosen = choice;
                }
            }
            String instruction = chosen == null ? rest
                : bare.substring(chosen.title().strip().length())
                    .replaceFirst("^'", "").replaceFirst("^[\\s:.,;]+", "");
            return answerAndResume(decisionId, first, instruction,
                chosen == null ? null : chosen.title(), "operator");
        }
        if (decision.kind() == DecisionKind.BLOCKED_TASK && first.equals(InstructedRetest.TOKEN)
                && whyNoRetest(context.store(), decision,
                    retestTasks(context.store(), decision)) == null) {
            // The same answer again, with the rest as the instruction to the test's author.
            return answerAndResume(decisionId, first, said.substring(first.length())
                .replaceFirst("^[\\s:.,;]+", ""), "operator");
        }
        if (decision.kind() == DecisionKind.BLOCKED_TASK && first.equals(JourneyVerdict.SCREEN)
                && whyNoScreen(context.store(), decision,
                    screenTasks(context.store(), decision)) == null) {
            // The ruling on a failed journey (section 88), with the rest as the instruction.
            return answerAndResume(decisionId, first, said.substring(first.length())
                .replaceFirst("^[\\s:.,;]+", ""), "operator");
        }
        if (decision.kind() == DecisionKind.BLOCKED_TASK && first.equals(JourneyVerdict.JOURNEY)
                && whyNoJourney(context.store(), decision,
                    journeyTasks(context.store(), decision)) == null) {
            return answerAndResume(decisionId, first, said.substring(first.length())
                .replaceFirst("^[\\s:.,;]+", ""), "operator");
        }
        new ControlServiceImpl().recordAnswer(decisionId, text);
        if (decision.kind() == DecisionKind.BUDGET_EXTENSION) {
            return new Outcome(null, false, "Recorded. The build stays stopped; answer extend to "
                + "raise its limit.");
        }
        return resumeStopped(context, context.store(), decision, "operator");
    }

    /**
     * Why a question cannot be answered when the build that asked it has been replaced, or null
     * when it can. Checked before anything is recorded: an answer to an earlier build would
     * otherwise be written down and, worse, hand that build back to its engine.
     */
    private static String fromAnEarlierBuild(ArtifactStore store, Decision decision) {
        Story story = decision.runId() == null ? null
            : SupersededRuns.supersededStoryOf(store, decision.runId());
        if (story == null) {
            return null;
        }
        return "Not recorded: this question came from an earlier build of " + story.key()
            + ". The story was sent back or started again and has a newer build"
            + (story.state() == com.swarmcoder.domain.StoryState.READY
                ? " waiting to start" : "")
            + ", so there is nothing left for an answer to restart. Answer the newer build's "
            + "own question instead, if it asks one.";
    }

    private static Outcome withPrefix(Outcome outcome, String prefix) {
        return prefix.isEmpty() ? outcome
            : new Outcome(outcome.error(), outcome.resumed(), prefix + outcome.note());
    }

    /** Hands the decision's run back to its engine when it is parked and has not been already. */
    private static Outcome resumeStopped(ConsoleContext context, ArtifactStore store,
                                         Decision decision, String actor) {
        UUID id = decision.id();
        String word = "answered";
        Run run = decision.runId() == null ? null : store.root().runs.get(decision.runId());
        if (run == null) {
            return new Outcome(null, false, "Recorded. This question belongs to no build that "
                + "still exists, so nothing was restarted.");
        }
        if (run.parkedAt() == null) {
            return new Outcome(null, false, "Recorded. The build this question came from is not "
                + "stopped waiting on it (it carried on, or it has ended), so nothing was "
                + "restarted.");
        }
        if (run.state() == RunState.DELIVERED || run.state() == RunState.ABORTED
                || run.state() == RunState.ABANDONED) {
            return new Outcome(null, false, "Recorded. The build this question came from has "
                + "ended, so nothing was restarted.");
        }
        long now = System.currentTimeMillis();
        HandedBack before = RESUMED_AT.get(run.id());
        if (before != null && run.parkedAt().equals(before.parkedAt())
                && now - before.at() < RESUME_GUARD_MILLIS) {
            return new Outcome(null, false, "Recorded. The build was already handed back to its "
                + "engine a moment ago for an earlier answer, so it was not handed back twice.");
        }
        Instant parkedAt = run.parkedAt();
        settle(parkedAt);
        RESUMED_AT.put(run.id(), new HandedBack(parkedAt, System.currentTimeMillis()));
        String refused = context.resumeRun(run.id());
        if (refused != null && refused.startsWith("error:")) {
            RESUMED_AT.remove(run.id());
            return new Outcome(null, false, "Recorded, but the build was NOT restarted: "
                + refused.substring("error:".length()).strip());
        }
        log.info("Run {} handed back to its engine after decision {} was answered '{}' by {}",
            run.id(), id, word, actor);
        return new Outcome(null, true, "Recorded, and the stopped build was handed back to its "
            + "engine: it runs the stage it stopped in again.");
    }

    private static Outcome failed(String why) {
        return new Outcome(why, false, why);
    }

    /** Waits until the park is at least {@link #SETTLE_MILLIS} old. Never longer than that. */
    private static void settle(Instant parkedAt) {
        long age = Duration.between(parkedAt, Instant.now()).toMillis();
        long wait = SETTLE_MILLIS - age;
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(wait, SETTLE_MILLIS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** For tests: forget which runs were handed back. */
    static void reset() {
        RESUMED_AT.clear();
    }
}
