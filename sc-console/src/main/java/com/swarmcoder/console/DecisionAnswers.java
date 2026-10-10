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
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.store.ArtifactStore;
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
            options.add(new Option("note", "record the text as the answer. A spending limit is "
                + "changed in the settings, not by answering, so nothing restarts unless the "
                + "question belongs to a stopped build", true));
        } else if (kind == DecisionKind.APPROVAL) {
            options.add(new Option("note", "record the text. A run waiting for approval is "
                + "approved or rejected as a run, not by answering", true));
        }
        return options;
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
        if (decision.state() == DecisionState.RESOLVED) {
            return failed("that question has already been answered");
        }
        String word = token == null ? "" : token.strip().toLowerCase(Locale.ROOT);
        List<Option> options = optionsFor(decision.kind());
        if (options.stream().noneMatch(option -> option.token().equals(word))) {
            return failed("'" + token + "' is not an answer this question accepts; the answers are "
                + options.stream().map(Option::token).toList());
        }
        try {
            new ControlServiceImpl().resolveDecision(id.toString(),
                responseFor(decision.kind(), word, text, actor));
        } catch (RuntimeException e) {
            return failed("the answer could not be recorded: " + e.getMessage());
        }

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
