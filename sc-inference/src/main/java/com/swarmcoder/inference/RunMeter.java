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
package com.swarmcoder.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What a run cost, kept in memory while it happens: every model call with who made it, how long it
 * took and the token counts the model server itself reported, plus named stretches of wall-clock
 * time (a wave, an integration, a repair round).
 *
 * <p><b>Why it exists.</b> Until 2026-10-02 nothing recorded what a run spent. A worker session
 * kept one running total of tokens and every role call (architect, test author, judge, ...) kept
 * nothing, so "how many tokens did this delivered line cost, and how many went on candidates that
 * were thrown away" could not be answered from any run. The harness's end-of-run report reads this.
 *
 * <p><b>Off unless switched on</b> ({@link #enable()}, or {@code -Dswarmcoder.meter=true}). Off, a
 * call costs one volatile read and nothing is kept — a long-lived server must not grow a list for
 * ever, and the request sent to the model server is byte-for-byte what it was before this existed.
 *
 * <p>Token counts are the server's own ({@code usage} of the chat response) and never an estimate:
 * a call whose server reported none keeps -1, which a reader must show as "not recorded".
 *
 * <p>Nothing here is persisted, so nothing here can break an existing store.
 */
public final class RunMeter {

    /**
     * Who is asking, set where the call is made.
     *
     * @param role   "architect", "test author", "judge", "worker", ... — free text, never an enum
     * @param task   the task the call is for (its id as text), or null
     * @param worker the worker index as text, or null
     */
    public record Tag(String role, String task, String worker) {
        public static final Tag UNTAGGED = new Tag("untagged", null, null);

        public Tag {
            role = role == null || role.isBlank() ? "untagged" : role;
        }
    }

    /**
     * One finished or still running model call.
     *
     * @param startMillis      when the request was sent
     * @param endMillis        when the answer was complete; -1 while it is running, or when the
     *                         stream ended without saying so
     * @param promptTokens     the server's count, or -1 when it reported none
     * @param completionTokens the server's count, or -1 when it reported none
     * @param cachedPromptTokens how many of {@code promptTokens} the server took from its prompt
     *                         prefix cache and bills at the cached price (DeepSeek's
     *                         {@code prompt_cache_hit_tokens}, OpenAI's and vLLM's
     *                         {@code prompt_tokens_details.cached_tokens}); -1 when it said nothing
     * @param requestChars     the size of the request body as it went out, or -1 when not seen
     * @param samePrefixChars  how many leading characters of that body were the same as the
     *                         session's previous request - what a prefix cache CAN reuse, measured
     *                         here and not by the server; -1 for a session's first call or unseen
     */
    public record Call(String role, String task, String worker, String model, long startMillis,
                       long endMillis, int promptTokens, int completionTokens, boolean failed,
                       long firstTokenMillis, int cachedPromptTokens, int requestChars,
                       int samePrefixChars) {

        /** A call that ended with an answer, with no first-token time recorded. */
        public Call(String role, String task, String worker, String model, long startMillis,
                    long endMillis, int promptTokens, int completionTokens) {
            this(role, task, worker, model, startMillis, endMillis, promptTokens,
                completionTokens, false, -1);
        }

        /** A call whose server said nothing of its prompt cache and whose request was not seen. */
        public Call(String role, String task, String worker, String model, long startMillis,
                    long endMillis, int promptTokens, int completionTokens, boolean failed,
                    long firstTokenMillis) {
            this(role, task, worker, model, startMillis, endMillis, promptTokens,
                completionTokens, failed, firstTokenMillis, -1, -1, -1);
        }

        /** The server said how much of this call's prompt it took from its prefix cache. */
        public boolean cacheReported() {
            return cachedPromptTokens >= 0 && promptTokens >= 0;
        }

        /** When the first token of the answer arrived, where the client could see that. */
        public boolean firstTokenKnown() {
            return firstTokenMillis >= startMillis && firstTokenMillis > 0;
        }

        public boolean usageRecorded() {
            return promptTokens >= 0 && completionTokens >= 0;
        }

        public boolean finished() {
            return endMillis >= startMillis && endMillis > 0;
        }

        public long millis() {
            return finished() ? endMillis - startMillis : -1;
        }
    }

    /** A named stretch of wall-clock time, e.g. "wave 2 of 5". */
    public record Span(String name, long startMillis, long endMillis) {
        public long millis() {
            return Math.max(0, endMillis - startMillis);
        }
    }

    /** One call in flight: told when it ends and what the server said it used. */
    public static final class Open {
        static final Open IGNORED = new Open(null, null);

        private final Tag tag;
        private final String model;
        private final long start = System.currentTimeMillis();
        private volatile long end = -1;
        private volatile int prompt = -1;
        private volatile int completion = -1;

        private Open(Tag tag, String model) {
            this.tag = tag;
            this.model = model;
        }

        /** The answer is complete (or the call failed). The first call wins. */
        public void end() {
            if (end < 0) {
                end = System.currentTimeMillis();
            }
        }

        /**
         * The call ended without an answer: it timed out, the connection broke, the server
         * refused it, or it was abandoned. It held a place for as long as it lasted and no
         * tokens of it were counted, so it is kept out of every tokens-per-second figure.
         */
        public void failed() {
            failed = true;
            end();
        }

        /** The first token of the answer has arrived; only the first call counts. */
        public void firstToken() {
            if (firstToken < 0) {
                firstToken = System.currentTimeMillis();
            }
        }

        private volatile boolean failed;
        private volatile long firstToken = -1;

        /** The server's own counts. Negative values are ignored: "not reported" stays -1. */
        public void usage(int promptTokens, int completionTokens) {
            if (promptTokens >= 0) {
                prompt = promptTokens;
            }
            if (completionTokens >= 0) {
                completion = completionTokens;
            }
        }

        private volatile int cached = -1;
        private volatile int requestChars = -1;
        private volatile int samePrefixChars = -1;

        /** How many prompt tokens the server took from its prefix cache; negative is ignored. */
        public void cached(int cachedPromptTokens) {
            if (cachedPromptTokens >= 0) {
                cached = cachedPromptTokens;
            }
        }

        /** The request body's size and how much of its head the previous request also had. */
        public void request(int chars, int samePrefix) {
            if (chars >= 0) {
                requestChars = chars;
                samePrefixChars = samePrefix;
            }
        }

        private Call snapshot() {
            return new Call(tag.role(), tag.task(), tag.worker(), model, start, end, prompt,
                completion, failed, firstToken, cached, requestChars, samePrefixChars);
        }
    }

    private static volatile boolean on = Boolean.getBoolean("swarmcoder.meter");
    private static final List<Open> CALLS = new ArrayList<>();
    private static final List<Span> SPANS = new ArrayList<>();
    private static final ThreadLocal<Tag> TAG = new ThreadLocal<>();

    private RunMeter() {}

    /** Starts keeping calls and spans, until {@link #disable()}. */
    public static void enable() {
        on = true;
    }

    /** Stops keeping anything new; what was kept stays until {@link #reset()}. */
    public static void disable() {
        on = false;
    }

    public static boolean enabled() {
        return on;
    }

    /** Forgets everything recorded so far (a test, or a second run in one process). */
    public static void reset() {
        synchronized (CALLS) {
            CALLS.clear();
        }
        synchronized (SPANS) {
            SPANS.clear();
        }
    }

    /** The tag the code on this thread set for the call it is about to make. */
    public static Tag currentTag() {
        Tag tag = TAG.get();
        return tag == null ? Tag.UNTAGGED : tag;
    }

    /** Runs {@code call} with {@code tag} as this thread's tag, then puts the old one back. */
    public static <T, E extends Exception> T tagged(Tag tag, ThrowingSupplier<T, E> call) throws E {
        Tag before = TAG.get();
        TAG.set(tag);
        try {
            return call.get();
        } finally {
            if (before == null) {
                TAG.remove();
            } else {
                TAG.set(before);
            }
        }
    }

    /** A supplier that may throw a checked exception. */
    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }

    /** A model call is being sent now. Never null; when metering is off the result does nothing. */
    public static Open begin(Tag tag, String model) {
        if (!on) {
            return Open.IGNORED;
        }
        Open open = new Open(tag == null ? Tag.UNTAGGED : tag, model);
        synchronized (CALLS) {
            CALLS.add(open);
        }
        return open;
    }

    /** Records a stretch of time that has just ended. */
    public static void span(String name, long startMillis) {
        if (!on) {
            return;
        }
        synchronized (SPANS) {
            SPANS.add(new Span(name, startMillis, System.currentTimeMillis()));
        }
    }

    /** Times {@code work} as a span named {@code name}, whether it returns or throws. */
    public static <T> T timed(String name, Supplier<T> work) {
        long start = System.currentTimeMillis();
        try {
            return work.get();
        } finally {
            span(name, start);
        }
    }

    /** Every call so far, in the order they were sent. */
    public static List<Call> calls() {
        synchronized (CALLS) {
            return CALLS.stream().map(Open::snapshot).toList();
        }
    }

    /** Every span so far, in the order they ended. */
    public static List<Span> spans() {
        synchronized (SPANS) {
            return List.copyOf(SPANS);
        }
    }
}
