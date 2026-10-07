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
package com.swarmcoder.knowledge;

import com.swarmcoder.runtime.ExpertHelp.Answer;
import com.swarmcoder.runtime.ExpertHelp.Source;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the expert has already answered for ONE RUN, shared by every worker's own {@link
 * ExpertDesk} for that run — never across runs, and never written to a store.
 *
 * <p><b>Why this exists.</b> A swarm dispatches several workers at once, at different temperatures
 * and personas, against the very same task; when the task meets an unfamiliar API, more than one
 * of them stops in the same place within seconds of each other. Measured 2026-09-03: two workers
 * asked the same question about obtaining an RMI proxy less than a minute apart, each paying for
 * its own escalation to answer the one thing. This is what lets the second worker's question be
 * answered from the first worker's paid-for answer instead of escalating twice for one topic.
 *
 * <p><b>Keyed on the QUESTION, not the answer.</b> Same overlap measure and the same threshold
 * {@link ExpertDesk} uses to decide a worker is asking itself the same thing again in different
 * words ({@link #OVERLAP_THRESHOLD}) — one opinion, shared, about what "the same question" means,
 * whether it is asked twice by one worker or once each by two.
 *
 * <p>Only ever grows within the lifetime of one run: a {@code SwarmDispatcher} keeps one instance
 * per {@code runId} and hands the same instance to every {@link ExpertDesk} it builds for that
 * run's workers ({@code ExpertDesk#sharedAcrossRun}). Thread-safe — workers of a wave run
 * concurrently.
 */
public final class RunAnswerCache {

    /**
     * The same bar {@link ExpertDesk}'s own "asked again" rule uses for one worker's repeated
     * question — kept identical so a rephrasing that would escalate when asked twice by the same
     * worker is exactly the rephrasing served free when asked once each by two different workers.
     */
    static final double OVERLAP_THRESHOLD = 0.5;

    private record Recorded(List<String> tokens, int workerIndex, Answer answer, String question) {}

    /**
     * One answer the expert researched in this run, with the question that earned it - what a
     * check of "has this been answered already?" reads ({@link StoredAnswerJudge}).
     */
    public record Stored(String question, String answer, int workerIndex) {}

    /** Every researched answer of the run that still has its question, newest first. */
    synchronized List<Stored> stored() {
        List<Stored> out = new ArrayList<>();
        for (int i = answers.size() - 1; i >= 0; i--) {
            Recorded recorded = answers.get(i);
            if (recorded.question() != null && !recorded.question().isBlank()) {
                out.add(new Stored(recorded.question(), recorded.answer().text(),
                    recorded.workerIndex()));
            }
        }
        return out;
    }

    /**
     * A stored answer as an asker is handed it: under the question it was researched for, so the
     * asker can see what it is being given and why, and ask for only what is missing
     * (2026-10-04). Harness run 77 handed reused answers back bare, to questions they only
     * partly matched, and the architect wrote "please read the files rather than reuse a
     * previous answer" and asked again.
     */
    static String labelled(String question, String answer) {
        if (question == null || question.isBlank()) {
            return answer;
        }
        String asked = question.strip().replaceAll("\\s+", " ");
        return "The expert already researched this in this run, for the question: \""
            + (asked.length() <= 400 ? asked : asked.substring(0, 400) + "...") + "\"\n"
            + "Its answer follows, with the files it was read from. If a part of YOUR question "
            + "is not covered by it, ask for that part alone and say it is the part still "
            + "missing.\n\n" + answer;
    }

    private final List<Recorded> answers = new ArrayList<>();

    /** A question the expert is researching right now, and who asked it first. */
    private record InFlight(List<String> tokens, int workerIndex,
                            java.util.concurrent.CompletableFuture<Answer> answer,
                            String question) {}

    private final List<InFlight> inFlight = new ArrayList<>();

    /**
     * What asking the run for a question came to: it is already answered ({@link #finished}), or
     * somebody is having it researched right now ({@link #theirs} - wait for it), or nobody is
     * and the caller now owns it ({@link #mine} - research it, then {@link #finish}).
     *
     * <p><b>Why (2026-10-02).</b> The cache only ever held FINISHED answers. A swarm dispatches
     * several workers against one task at once, and when the task meets an unfamiliar API they
     * stop in the same place within seconds of each other: the second question arrives while the
     * first is minutes from its answer, finds nothing, and pays for a second investigation of
     * the same thing. Measured answers took 4 to 13 minutes (harness run 66), which is the whole
     * window in which that happens.
     */
    static final class Claim {
        final Answer finished;
        final java.util.concurrent.CompletableFuture<Answer> theirs;
        final int theirWorker;
        private final InFlight mine;

        private Claim(Answer finished, java.util.concurrent.CompletableFuture<Answer> theirs,
                      int theirWorker, InFlight mine) {
            this.finished = finished;
            this.theirs = theirs;
            this.theirWorker = theirWorker;
            this.mine = mine;
        }

        boolean mine() {
            return mine != null;
        }
    }

    /** See {@link Claim}. One decision under one lock, so two askers cannot both become owner. */
    synchronized Claim claim(List<String> tokens, int workerIndex) {
        return claim(tokens, workerIndex, null, true);
    }

    /**
     * @param question     the question as asked, kept with its answer for {@link #stored}
     * @param matchByWords false when the caller has its own check of the stored answers
     *                     ({@link StoredAnswerJudge}) and has already made it: then a finished
     *                     answer is not matched here by shared words
     */
    synchronized Claim claim(List<String> tokens, int workerIndex, String question,
                             boolean matchByWords) {
        Optional<Answer> done = matchByWords ? answerFor(tokens) : Optional.empty();
        if (done.isPresent()) {
            return new Claim(done.get(), null, -1, null);
        }
        if (tokens != null && !tokens.isEmpty()) {
            for (InFlight flying : inFlight) {
                if (WorkedExamples.tokenOverlap(tokens, flying.tokens()) >= OVERLAP_THRESHOLD) {
                    return new Claim(null, flying.answer(), flying.workerIndex(), null);
                }
            }
        }
        InFlight mine = new InFlight(tokens == null ? List.of() : List.copyOf(tokens), workerIndex,
            new java.util.concurrent.CompletableFuture<>(), question);
        inFlight.add(mine);
        return new Claim(null, null, -1, mine);
    }

    /**
     * The owner's research is over: its answer is remembered (when it is one - see
     * {@link #record}) and handed to everybody who was waiting for it. Always called, answer or
     * not, so nobody waits for a question that will never be answered.
     */
    void finish(Claim claim, Answer answer) {
        if (claim == null || claim.mine == null) {
            return;
        }
        synchronized (this) {
            inFlight.remove(claim.mine);
            record(claim.mine.tokens(), claim.mine.workerIndex(), answer, claim.mine.question());
        }
        claim.mine.answer().complete(answer);
    }

    /** "worker 3", or "an earlier role of this run" - who an answer was first given to. */
    static String askerWords(int workerIndex) {
        return workerIndex == ASKED_BY_A_ROLE ? "a role of this run" : "worker " + workerIndex;
    }

    /**
     * The index a question is recorded under when it was asked by a planning role - the architect,
     * the planner, the test author - rather than by a worker (2026-10-02).
     */
    public static final int ASKED_BY_A_ROLE = -2;

    /** One cache per run, for everybody who asks the expert in that run; capped, oldest out. */
    private static final java.util.Map<java.util.UUID, RunAnswerCache> BY_RUN =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                    java.util.Map.Entry<java.util.UUID, RunAnswerCache> eldest) {
                return size() > 200;
            }
        });

    /**
     * The one cache of run {@code runId}: the same object for the run's workers and for its
     * planning roles, so a question the expert answered for the architect is served free to a
     * worker who asks the same thing an hour later, and the other way round.
     */
    public static RunAnswerCache forRun(java.util.UUID runId) {
        return BY_RUN.computeIfAbsent(runId, id -> new RunAnswerCache());
    }

    /**
     * The expert's answer to a near-duplicate of {@code tokens} that some worker of this run
     * already asked and paid to have escalated — free, and marked as a reuse rather than a fresh
     * free-tier hit. Empty when nothing recorded yet overlaps this question by {@link
     * #OVERLAP_THRESHOLD} or more.
     */
    synchronized Optional<Answer> answerFor(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Optional.empty();
        }
        for (Recorded recorded : answers) {
            if (WorkedExamples.tokenOverlap(tokens, recorded.tokens()) >= OVERLAP_THRESHOLD) {
                return Optional.of(new Answer(
                    labelled(recorded.question(), recorded.answer().text()), Source.DETERMINISTIC, 0, 0,
                    List.of(), "free: the expert answered this for "
                        + (recorded.workerIndex() == ASKED_BY_A_ROLE
                            ? "an earlier role of this run" : "worker " + recorded.workerIndex())));
            }
        }
        return Optional.empty();
    }

    /**
     * Remembers a real expert answer, keyed to the question that earned it, so a later
     * near-duplicate from any worker of this run finds it. Only an answer the expert actually
     * produced is worth remembering here — a deterministic free-tier answer is already free for
     * everyone, through the reference material itself, and "nobody could answer" is not something
     * to hand a later worker as if it were settled.
     */
    synchronized void record(List<String> tokens, int workerIndex, Answer answer) {
        record(tokens, workerIndex, answer, null);
    }

    /** @param question the question as asked; null keeps the answer out of {@link #stored} */
    synchronized void record(List<String> tokens, int workerIndex, Answer answer,
                             String question) {
        if (tokens == null || tokens.isEmpty() || answer == null
                || answer.source() != Source.MODEL || !answer.answered()) {
            return;
        }
        answers.add(new Recorded(List.copyOf(tokens), workerIndex, answer, question));
    }
}
