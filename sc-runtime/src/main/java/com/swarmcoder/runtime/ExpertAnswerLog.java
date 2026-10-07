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
package com.swarmcoder.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every answer the expert desk gave in ONE RUN, with what it cost and what became of the work that
 * followed it (2026-10-02) - in memory only, never written to a store.
 *
 * <p><b>Why.</b> Harness run 66: seven questions, four of them researched by the expert's model
 * for 4 to 13 minutes each, and nothing anywhere said whether a single one of those answers was
 * right. An answer that sends a worker into a build that then fails costs more than no answer, and
 * the only way to see that is to keep the answer beside what happened next.
 *
 * <p><b>The outcome is evidence, not proof.</b> "Helped" means the asker's work that followed the
 * answer passed its checks - which it may have done despite the answer; "did not help" means that
 * work failed, or the asker came back with the same question - which may have had nothing to do
 * with the answer. It says where to look, and every reader of it must be told so
 * ({@link #OUTCOME_CAVEAT}).
 *
 * <p>One log per run, shared by the run's workers and its planning roles, exactly as the run's
 * answer cache is. Thread-safe: workers of a wave ask side by side.
 */
public final class ExpertAnswerLog {

    /** What every table of these outcomes must say beside it. */
    public static final String OUTCOME_CAVEAT = "The outcome is evidence, not proof: \"helped\" "
        + "means the asker's work that followed the answer passed its checks, \"did not help\" "
        + "means that work failed or the asker asked the same thing again; neither shows the "
        + "answer was the cause.";

    /** Where an answer came from. In memory only - never persisted, so free to change. */
    public enum From {
        /** The expert's model investigated the question in a session of its own. */
        EXPERT_RESEARCH("expert research"),
        /** An answer the expert had already researched in this run, or was researching. */
        REUSED("reused"),
        /** The project's own code, reference material or index, with no model call. */
        PROJECT_CODE("project code"),
        /** Nobody could answer: no expert, no budget, or the session reached nothing. */
        NO_ANSWER("no answer");

        private final String words;

        From(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    /** What became of the asker's work after the answer. */
    public enum Followed { PASSED, FAILED, UNKNOWN }

    /**
     * Who asked.
     *
     * @param role   "worker", or the planning role's name ("architect", "planner", "test author")
     * @param task   the task's id, or null for a role that works on no one task
     * @param title  the task's title for a reader, or null
     * @param worker the worker's index within its task; -1 for a role
     */
    public record Asker(String role, String task, String title, int worker) {

        public Asker {
            role = role == null || role.isBlank() ? "unknown" : role;
        }

        public static Asker role(String role) {
            return new Asker(role, null, null, -1);
        }

        public static Asker worker(UUID task, String title, int worker) {
            return new Asker("worker", task == null ? null : task.toString(), title, worker);
        }

        /** "worker 1 of 'Build the screen'", or "architect". */
        public String words() {
            if (worker < 0) {
                return role;
            }
            return role + " " + worker + (title == null || title.isBlank() ? "" : " of '" + title + "'");
        }
    }

    /**
     * One answer.
     *
     * @param turns            model turns the expert took; 0 when no model was called
     * @param lookups          lookups the expert made
     * @param promptTokens     what the model server counted as sent to it over the whole session,
     *                         every turn's resend included; -1 when it was not reported
     * @param completionTokens what it counted as generated; -1 when it was not reported
     * @param seconds          from the question to the answer, as the asker waited
     * @param askedAgain       the same asker later asked the same thing again
     */
    public record Entry(Asker asker, String question, From from, int turns, int lookups,
                        long promptTokens, long completionTokens, double seconds,
                        Followed followed, boolean askedAgain, long askedAtMillis) {

        /** "helped", "did not help" or "unknown" - see {@link #OUTCOME_CAVEAT}. */
        public String outcome() {
            if (askedAgain || from == From.NO_ANSWER || followed == Followed.FAILED) {
                return "did not help";
            }
            return followed == Followed.PASSED ? "helped" : "unknown";
        }

        /** Why {@link #outcome} is what it is, in a few words. */
        public String outcomeBecause() {
            if (from == From.NO_ANSWER) {
                return "no answer was given";
            }
            if (askedAgain) {
                return "the asker asked the same thing again";
            }
            return switch (followed) {
                case PASSED -> "the asker's work passed its checks";
                case FAILED -> "the asker's work failed its checks";
                case UNKNOWN -> "what became of the asker's work was not recorded";
            };
        }
    }

    /** A recorded answer, for the one who recorded it to say later that it was asked again. */
    public static final class Handle {
        private final int index;

        private Handle(int index) {
            this.index = index;
        }
    }

    private static final Map<UUID, ExpertAnswerLog> BY_RUN =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, ExpertAnswerLog> eldest) {
                return size() > 200;
            }
        });

    /** The one log of run {@code runId}; the same object for everybody who asks in that run. */
    public static ExpertAnswerLog forRun(UUID runId) {
        return BY_RUN.computeIfAbsent(runId, id -> new ExpertAnswerLog());
    }

    /** Every answer of every run this process still remembers, oldest first - for a report. */
    public static List<Entry> allRuns() {
        List<ExpertAnswerLog> logs;
        synchronized (BY_RUN) {
            logs = List.copyOf(BY_RUN.values());
        }
        List<Entry> all = new ArrayList<>();
        for (ExpertAnswerLog log : logs) {
            all.addAll(log.entries());
        }
        all.sort(java.util.Comparator.comparingLong(Entry::askedAtMillis));
        return List.copyOf(all);
    }

    /** Forgets every run - for a harness that measures one run per process, and for tests. */
    public static void reset() {
        BY_RUN.clear();
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Records one answer; what became of the asker's work is {@link Followed#UNKNOWN} until told. */
    public synchronized Handle record(Asker asker, String question, From from, int turns,
                                      int lookups, long promptTokens, long completionTokens,
                                      double seconds) {
        entries.add(new Entry(asker == null ? Asker.role("unknown") : asker,
            question == null ? "" : question, from == null ? From.NO_ANSWER : from, turns, lookups,
            promptTokens, completionTokens, seconds, Followed.UNKNOWN, false,
            System.currentTimeMillis()));
        return new Handle(entries.size() - 1);
    }

    /** The asker of {@code earlier} has asked the same thing again: that answer did not settle it. */
    public synchronized void askedAgain(Handle earlier) {
        if (earlier == null || earlier.index >= entries.size()) {
            return;
        }
        Entry e = entries.get(earlier.index);
        entries.set(earlier.index, new Entry(e.asker(), e.question(), e.from(), e.turns(),
            e.lookups(), e.promptTokens(), e.completionTokens(), e.seconds(), e.followed(), true,
            e.askedAtMillis()));
    }

    /**
     * The work of {@code asker} has now been checked: every answer it was given so far and that
     * has no result yet gets this one. An answer given AFTER this call belongs to the asker's next
     * piece of work and waits for the next call.
     */
    public synchronized void workFollowed(Asker asker, boolean passed) {
        if (asker == null) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.followed() == Followed.UNKNOWN && sameAsker(e.asker(), asker)) {
                entries.set(i, new Entry(e.asker(), e.question(), e.from(), e.turns(), e.lookups(),
                    e.promptTokens(), e.completionTokens(), e.seconds(),
                    passed ? Followed.PASSED : Followed.FAILED, e.askedAgain(), e.askedAtMillis()));
            }
        }
    }

    private static boolean sameAsker(Asker a, Asker b) {
        return a.role().equals(b.role()) && a.worker() == b.worker()
            && java.util.Objects.equals(a.task(), b.task());
    }

    /** Every answer of this run, in the order they were given. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }
}
