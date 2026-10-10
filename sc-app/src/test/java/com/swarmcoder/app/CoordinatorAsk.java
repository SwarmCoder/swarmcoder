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
package com.swarmcoder.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.console.DecisionAnswers;
import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.domain.CarriedWarning;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.store.ArtifactStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets the AI session that started a harness run stand in for the operator when the run parks.
 *
 * <p>Off unless {@code -Dswarmcoder.e2e.askDir=<dir>} is given; with it absent the harness behaves
 * exactly as it always did (a park is a broken chain link and the walk ends).
 *
 * <h2>The exchange</h2>
 *
 * <ol>
 *   <li>The run parks with a decision pending. The harness writes {@code <dir>/question-<n>.json}
 *       (temp file, then an atomic rename, so a reader never sees half of it).</li>
 *   <li>It waits up to {@code -Dswarmcoder.e2e.askMinutes} (default 30) for
 *       {@code <dir>/answer-<n>.json}: {@code {"answer":"<token>","text":"<optional>"}}.</li>
 *   <li>A valid answer is applied through the product's own path (see {@link #apply}) and the run
 *       is handed back to the workflow engine exactly as {@code RunResumer} does at start-up, and
 *       the walk goes on. No answer in time, an unreadable or invalid answer, or {@code stop}
 *       ends the walk as a park always did, and the reason says which.</li>
 * </ol>
 *
 * <p>At most {@code -Dswarmcoder.e2e.askMax} (default 5) questions are asked in one run. Time spent
 * waiting is added to the harness's overall deadline ({@link #waitedMillis}); it never counts
 * against it.
 *
 * <h2>What an answer can be</h2>
 *
 * <p>Nothing in the product waits on a decision: a parked run has no thread attached and answering
 * in the Console only rewrites the decision row. What continues a parked run is handing it back to
 * its engine ({@code WorkflowEngine.advance}), which retries the stage it parked in and withdraws
 * the stale questions. So every answer except {@code stop} means "record this, then resume".
 */
final class CoordinatorAsk {

    static final String DIR_PROPERTY = "swarmcoder.e2e.askDir";
    static final String MINUTES_PROPERTY = "swarmcoder.e2e.askMinutes";
    static final String MAX_PROPERTY = "swarmcoder.e2e.askMax";
    /** Optional: where the harness's own output is going, echoed into each question as evidence. */
    static final String LOG_PROPERTY = "swarmcoder.e2e.askLog";

    static final String STOP = "stop";

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** One answer the coordinator may give. */
    record Option(String token, String description, boolean acceptsText) {}

    /** What {@code answer-<n>.json} says. */
    record Answer(String answer, String text) {
        String token() {
            return answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
        }

        String freeText() {
            return text == null ? "" : text.strip();
        }
    }

    enum Ending { ANSWERED, STOPPED, TIMED_OUT, INVALID, CAP_REACHED }

    /** How one question ended. {@code note} is the sentence the harness log and the verdict carry. */
    record Reply(Ending ending, Answer answer, String note) {}

    /** A run that has stopped with a question pending: what to ask about. */
    record Park(Run run, Decision decision, List<Decision> alsoPending) {
        String brief() {
            return decision.briefMarkdown() == null ? "" : decision.briefMarkdown();
        }
    }

    /** What happened to a park the harness handled: keep walking, or end with this sentence. */
    record Handled(boolean carryOn, String note) {}

    private final Path dir;
    private final long waitMillis;
    private final int max;
    private final long pollMillis;
    private final long settleMillis;
    private final Consumer<String> say;
    private final List<String> transcript = new ArrayList<>();
    private int asked;
    private volatile long waitedMillis;

    // Bound by the harness once the run is wired (see bind).
    private ControlService control;
    private Consumer<UUID> resume;
    private Path fixtureRepo;
    private Path checkoutsRoot;
    private Path harnessLog;

    CoordinatorAsk(Path dir, long waitMillis, int max, long pollMillis, long settleMillis,
                   Consumer<String> say) {
        this.dir = dir;
        this.waitMillis = waitMillis;
        this.max = max;
        this.pollMillis = Math.max(1, pollMillis);
        this.settleMillis = settleMillis;
        this.say = say;
    }

    /** The feature as the command line asks for it, or null when {@code askDir} is absent. */
    static CoordinatorAsk fromProperties(Consumer<String> say) {
        String dir = System.getProperty(DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            return null;
        }
        long minutes = Long.getLong(MINUTES_PROPERTY, 30);
        int max = Integer.getInteger(MAX_PROPERTY, 5);
        CoordinatorAsk ask = new CoordinatorAsk(Path.of(dir), minutes * 60_000, max, 1000, 3000,
            say);
        String log = System.getProperty(LOG_PROPERTY);
        ask.harnessLog = log == null || log.isBlank() ? null : Path.of(log);
        say.accept("[E2E] a parked run will ask the coordinator through " + dir + " (up to " + max
            + " question(s), " + minutes + " minute(s) each; the wait does not use the run's budget)");
        return ask;
    }

    /**
     * Binds what answering needs: the product's own control service (the one the Console's answer
     * box calls), a way to hand a run back to its workflow engine, the fixture clone, and where
     * the product cuts its run checkouts.
     */
    void bind(ControlService control, Consumer<UUID> resume, Path fixtureRepo, Path checkoutsRoot) {
        this.control = control;
        this.resume = resume;
        this.fixtureRepo = fixtureRepo;
        this.checkoutsRoot = checkoutsRoot;
    }

    /** Milliseconds spent waiting for answers so far — added to the harness's deadline. */
    long waitedMillis() {
        return waitedMillis;
    }

    synchronized List<String> transcript() {
        return List.copyOf(transcript);
    }

    synchronized int asked() {
        return asked;
    }

    // --- what the product offers for each decision --------------------------------------------

    /**
     * The answers the product can act on for a decision of this kind, {@code stop} always last.
     *
     * <p>The list is the product's own ({@code DecisionAnswers.optionsFor}); the harness adds only
     * {@code stop}, which is its way of ending the walk and is nothing the product does.
     */
    static List<Option> optionsFor(DecisionKind kind) {
        List<Option> options = new ArrayList<>();
        // APPROVAL and BUDGET_EXTENSION have nothing an unattended run can do about them: no
        // producer raises APPROVAL any more, and BUDGET_EXTENSION has no run (the harness uses a
        // free local model). Only stop is offered, so a coordinator is never invited to guess.
        if (kind == DecisionKind.GUIDELINE_REVIEW || kind == DecisionKind.BLOCKED_TASK) {
            for (DecisionAnswers.Option option : DecisionAnswers.optionsFor(kind)) {
                options.add(new Option(option.token(), option.description(), option.acceptsText()));
            }
        }
        options.add(new Option(STOP, "give up: the chain link breaks exactly as it does without "
            + "askDir (text is ignored)", false));
        return options;
    }

    /**
     * What is written on the decision for an answer — in the form the product's own code reads,
     * and worded by the product ({@code DecisionAnswers.responseFor}).
     */
    static String responseFor(DecisionKind kind, Answer answer) {
        return DecisionAnswers.responseFor(kind, answer.token(), answer.freeText(), "coordinator");
    }

    // --- finding a park ---------------------------------------------------------------------------

    /**
     * The run's park: it is marked parked AND has a decision nobody has answered. Null when either
     * is missing — a task's own blocked-task decision is pending while the rest of the run is still
     * working, and that is not a park. The decision asked about is the one whose text is the park
     * reason, else the newest.
     */
    static Park findPark(ArtifactStore store, UUID runId) {
        Run run = store.root().runs.get(runId);
        if (run == null || run.parkedAt() == null) {
            return null;
        }
        List<Decision> pending = new ArrayList<>();
        for (Decision decision : store.root().decisions.values()) {
            if (decision != null && runId.equals(decision.runId())
                    && decision.state() == DecisionState.PENDING) {
                pending.add(decision);
            }
        }
        if (pending.isEmpty()) {
            return null;
        }
        pending.sort(Comparator.comparing((Decision d) -> d.createdAt() == null
            ? Instant.EPOCH : d.createdAt()).reversed());
        Decision primary = pending.stream()
            .filter(d -> d.briefMarkdown() != null && d.briefMarkdown().equals(run.parkReason()))
            .findFirst().orElse(pending.get(0));
        List<Decision> others = new ArrayList<>(pending);
        others.remove(primary);
        return new Park(run, primary, others);
    }

    // --- handling a park -----------------------------------------------------------------------

    /**
     * Asks about a park, and on a valid answer applies it and resumes the run.
     *
     * @return carry on (the run is going again) or end, with the sentence to attach to the verdict
     */
    Handled handle(ArtifactStore store, UUID runId, Park first) {
        // The workflow thread that parked the run is still finishing its own bookkeeping for a
        // moment (it persists the run after marking it); asking and answering takes far longer than
        // that, but resuming into a second thread while the first is still unwinding must not happen.
        sleepQuietly(settleMillis);
        Park park = findPark(store, runId);
        if (park == null) {
            return new Handled(true, null); // it moved on by itself
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId.toString());
        body.put("workflowState", String.valueOf(park.run().state()));
        body.put("parkedAt", String.valueOf(park.run().parkedAt()));
        body.put("decisionId", park.decision().id().toString());
        body.put("decisionKind", String.valueOf(park.decision().kind()));
        body.put("question", park.brief());
        List<Map<String, String>> also = new ArrayList<>();
        for (Decision other : park.alsoPending()) {
            also.add(Map.of("decisionId", other.id().toString(), "kind",
                String.valueOf(other.kind()), "question",
                other.briefMarkdown() == null ? "" : other.briefMarkdown()));
        }
        body.put("alsoPending", also);
        List<String> warnings = new ArrayList<>();
        List<CarriedWarning> carried = park.run().carriedWarnings();
        if (carried != null) {
            carried.forEach(w -> warnings.add(w.oneLine()));
        }
        body.put("carriedWarnings", warnings);
        body.put("evidence", evidence(runId, park.brief()));

        Reply reply = ask(body, optionsFor(park.decision().kind()));
        if (reply.ending() != Ending.ANSWERED) {
            return new Handled(false, reply.note());
        }
        String problem = apply(park, reply.answer());
        if (problem != null) {
            record("the answer could not be applied: " + problem);
            return new Handled(false, "the coordinator's answer '" + reply.answer().token()
                + "' could not be applied: " + problem);
        }
        // Wait for the resumed workflow to clear the park mark (it does so as its first act), so the
        // next look at the run does not see the old park again.
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until) {
            Run now = store.root().runs.get(runId);
            if (now == null || now.parkedAt() == null) {
                return new Handled(true, null);
            }
            sleepQuietly(pollMillis);
        }
        record("the run was handed back to the engine but never cleared its park mark");
        return new Handled(false, "the coordinator answered '" + reply.answer().token()
            + "' but the run did not resume within a minute");
    }

    /**
     * Applies an answer through the product, and through nothing else: {@code
     * DecisionAnswers.answerAndResume} writes the decision the way the Console's answer box writes
     * it and hands the run back to its engine. The harness used to do both steps itself; the only
     * thing it still supplies is which engine a run of this walk belongs to.
     *
     * @return null on success, else why not
     */
    String apply(Park park, Answer answer) {
        if (control == null || resume == null) {
            return "the harness has not bound the product's control service yet";
        }
        try {
            ConsoleContext.get().withRunResume(id -> {
                resume.accept(id);
                return "";
            });
            DecisionAnswers.Outcome outcome = DecisionAnswers.answerAndResume(
                park.decision().id().toString(), answer.token(), answer.freeText(), "coordinator");
            if (!outcome.ok()) {
                return outcome.error();
            }
            return outcome.resumed() ? null : outcome.note();
        } catch (RuntimeException e) {
            return e.toString();
        }
    }

    private Map<String, Object> evidence(UUID runId, String text) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("harnessLog", harnessLog == null ? null : harnessLog.toString());
        evidence.put("fixtureClone", fixtureRepo == null ? null : fixtureRepo.toString());
        List<Path> checkouts = new ArrayList<>();
        if (checkoutsRoot != null && Files.isDirectory(checkoutsRoot)) {
            try (var children = Files.list(checkoutsRoot)) {
                children.filter(p -> p.getFileName().toString().contains(runId.toString()))
                    .forEach(checkouts::add);
            } catch (IOException ignored) {
                // evidence is a convenience; its absence is not a finding
            }
        }
        evidence.put("runCheckouts", checkouts.stream().map(Path::toString).toList());
        evidence.put("testsBranch", "swarm/tests/" + runId);
        // Said in full, because naming the branch alone was not enough (live run 63): the
        // coordinator has to know that a commit on its tip is what a 'retry' picks up.
        evidence.put("howToCorrectAnAcceptanceTest", "Commit the corrected test file on the tip of "
            + "branch swarm/tests/" + runId + " in fixtureClone (same path as the broken file), "
            + "then answer 'retry'. On resume the run takes its acceptance tests from that "
            + "branch's tip, for the red check and for every candidate. Editing a file in a run "
            + "checkout or on any other branch has no effect.");
        evidence.put("filesNamedInTheQuestion", filesNamedIn(text, checkouts));
        return evidence;
    }

    private static final Pattern FILE = Pattern.compile(
        "[\\w./\\\\-]+\\.(?:java|xml|yaml|yml|md|json|properties|kt)\\b");

    private List<String> filesNamedIn(String text, List<Path> checkouts) {
        Set<String> found = new LinkedHashSet<>();
        List<Path> roots = new ArrayList<>(checkouts);
        if (fixtureRepo != null) {
            roots.add(fixtureRepo);
        }
        Matcher matcher = FILE.matcher(text == null ? "" : text);
        while (matcher.find() && found.size() < 12) {
            for (Path root : roots) {
                try {
                    Path candidate = root.resolve(matcher.group().replace('\\', '/')).normalize();
                    if (candidate.startsWith(root.normalize()) && Files.isRegularFile(candidate)) {
                        found.add(candidate.toString());
                    }
                } catch (RuntimeException notAPath) {
                    // a token that is not a path is simply not evidence
                }
            }
        }
        return List.copyOf(found);
    }

    // --- the file exchange -----------------------------------------------------------------------

    /**
     * Writes the next question, waits for its answer, and says how it ended. The question holds
     * what the caller gave plus {@code n}, the options, where to put the answer and how long the
     * harness will wait. The wait is added to {@link #waitedMillis} however it ends.
     */
    Reply ask(Map<String, Object> body, List<Option> options) {
        int n;
        synchronized (this) {
            if (asked >= max) {
                String note = "no more questions: the cap of " + max + " ("
                    + MAX_PROPERTY + ") is used up";
                record(note);
                return new Reply(Ending.CAP_REACHED, null, note);
            }
            n = ++asked;
        }
        Path questionFile = dir.resolve("question-" + n + ".json");
        Path answerFile = dir.resolve("answer-" + n + ".json");
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("n", n);
        question.putAll(body);
        question.put("options", options);
        question.put("answerFile", answerFile.toString());
        question.put("answerFormat", "{\"answer\":\"<option token>\",\"text\":\"<optional>\"}");
        question.put("waitsMinutes", waitMillis / 60_000.0);
        question.put("askedAt", Instant.now().toString());
        try {
            Files.createDirectories(dir);
            Path temp = dir.resolve("question-" + n + ".json.tmp");
            Files.writeString(temp, JSON.writeValueAsString(question), StandardCharsets.UTF_8);
            Files.move(temp, questionFile, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            String note = "the question could not be written to " + questionFile + ": " + e;
            record(note);
            return new Reply(Ending.INVALID, null, note);
        }
        say.accept("[E2E] >>> QUESTION " + n + " for the coordinator: " + questionFile + " ("
            + body.get("decisionKind") + " in " + body.get("workflowState") + ") — "
            + CoordinatorAskText.oneLine(String.valueOf(body.get("question")), 400));
        record("question " + n + " (" + body.get("decisionKind") + " in " + body.get("workflowState")
            + "): " + CoordinatorAskText.oneLine(String.valueOf(body.get("question")), 600));

        long started = System.currentTimeMillis();
        try {
            Reply reply = awaitAnswer(n, answerFile, options, started);
            say.accept("[E2E] <<< ANSWER " + n + ": " + reply.note());
            record("answer " + n + ": " + reply.note());
            return reply;
        } finally {
            waitedMillis += System.currentTimeMillis() - started;
        }
    }

    private Reply awaitAnswer(int n, Path answerFile, List<Option> options, long started) {
        long unreadableSince = 0;
        while (true) {
            if (Files.exists(answerFile)) {
                Answer answer;
                try {
                    answer = JSON.readValue(Files.readString(answerFile, StandardCharsets.UTF_8),
                        Answer.class);
                } catch (Exception partialOrBroken) {
                    // The coordinator may write the file in place; give a half-written one a few
                    // seconds to finish before calling it broken.
                    long now = System.currentTimeMillis();
                    if (unreadableSince == 0) {
                        unreadableSince = now;
                    }
                    if (now - unreadableSince > Math.max(5 * pollMillis, 1000)) {
                        return new Reply(Ending.INVALID, null, "answer-" + n + ".json is not "
                            + "readable JSON of the form {\"answer\":\"...\",\"text\":\"...\"}: "
                            + partialOrBroken.getMessage());
                    }
                    sleepQuietly(pollMillis);
                    continue;
                }
                String token = answer.token();
                if (STOP.equals(token)) {
                    return new Reply(Ending.STOPPED, answer, "the coordinator answered stop"
                        + (answer.freeText().isEmpty() ? "" : ": " + answer.freeText()));
                }
                if (options.stream().noneMatch(o -> o.token().equals(token))) {
                    return new Reply(Ending.INVALID, answer, "'" + answer.answer() + "' is not an "
                        + "answer this question accepts; the options were "
                        + options.stream().map(Option::token).toList());
                }
                return new Reply(Ending.ANSWERED, answer, "the coordinator answered '" + token + "'"
                    + (answer.freeText().isEmpty() ? "" : " with text: "
                        + CoordinatorAskText.oneLine(answer.freeText(), 300)));
            }
            if (System.currentTimeMillis() - started >= waitMillis) {
                return new Reply(Ending.TIMED_OUT, null, "no answer to question " + n + " in "
                    + waitMillis / 60_000.0 + " minute(s) (" + MINUTES_PROPERTY + ")");
            }
            sleepQuietly(pollMillis);
        }
    }

    private synchronized void record(String line) {
        transcript.add(line);
    }

    /** The questions and answers of this run, for the harness log and the end of the chain report. */
    String report() {
        List<String> lines = transcript();
        StringBuilder sb = new StringBuilder("\n[ASK THE COORDINATOR] ")
            .append(lines.isEmpty() ? "no question was asked" : lines.size() + " entr"
                + (lines.size() == 1 ? "y" : "ies") + ", waited " + waitedMillis / 1000 + "s in total")
            .append('\n');
        lines.forEach(line -> sb.append("  - ").append(line).append('\n'));
        return sb.toString();
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the coordinator", e);
        }
    }
}

/** One line, bounded — the same shape the harness uses everywhere it quotes a brief. */
final class CoordinatorAskText {
    private CoordinatorAskText() {}

    static String oneLine(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s*\\R\\s*", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
