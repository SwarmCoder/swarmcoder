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
package com.swarmcoder.domain;

/**
 * Whether a worker that is still running is heading for trouble — decided ONCE, for everybody.
 *
 * <p>It sits beside {@link BuildHealth} and {@link Elapsed} for the same reason those do: the run
 * graph colours a chip from it and the words above the graph are written from it, and two answers
 * to "is this worker in trouble" is two chances to disagree in front of an operator who is trying
 * to decide whether to intervene.
 *
 * <h2>What actually kills a worker</h2>
 *
 * <p>Measured on a live run on 2026-09-01. Eight workers finished, at 8 to 21 turns and 16k to 42k
 * tokens. Four died, at 62, 72, 74 and 99 turns, and every one of them died the same way: a single
 * request to the model exceeded the request timeout. The model endpoint was healthy the whole time.
 *
 * <p>So turn count is not the thing that kills a worker; it only correlates because turns grow the
 * conversation. The conversation is never trimmed, every turn re-sends all of it, and eventually
 * one request cannot finish inside the time it is allowed. A worker at turn 60 on small tool
 * results can be perfectly well; a worker at turn 25 that has read four large files may not be.
 *
 * <h2>The two things worth going orange for</h2>
 *
 * <p><b>Its conversation is bigger than the budget it was given.</b> Every model profile states
 * {@code workingContextTokens} — the context SwarmCoder will let ONE session occupy. Nothing
 * enforces it: {@code TokenBudget.maxPromptTokens} is written by configuration and read by nothing,
 * which is exactly how a worker reaches 99 turns. A worker past its own stated budget is on
 * borrowed time, and that number is per-model configuration rather than a figure chosen here.
 *
 * <p><b>It has been waiting on the model for most of the time a request is allowed.</b>
 * {@link #REQUEST_TIMEOUT_MILLIS} is the agent framework's own request and socket timeout, used
 * unmodified. When a worker's last recorded step is one that means "the request is in flight" —
 * results handed back, a nudge sent, or the session just opened — the silence since it IS the age
 * of the request that will kill it. A worker two thirds of the way through that budget is about to
 * be killed by it.
 *
 * <p><b>Silence after a TOOL_CALL is deliberately not counted.</b> Between a tool call and its
 * result the worker is waiting on a build, not on the model, and a ten-minute Maven run is normal.
 * Counting it would paint every honest build orange, and a warning that fires on healthy work is
 * worth less than no warning.
 */
public final class WorkerHealth {

    /**
     * How long one request to the model may take before the transport gives up: 15 minutes.
     *
     * <p>Not a number chosen here. It is the agent framework's own default request and socket
     * timeout ({@code ConnectionTimeoutConfig}, 900000 ms), and the worker runtime constructs that
     * configuration with no overrides, so this is the wall every worker that died tonight hit.
     */
    public static final long REQUEST_TIMEOUT_MILLIS = 900_000L;

    /**
     * Silence past this means the request in flight has used most of the only time it gets.
     *
     * <p>Two thirds of the timeout. The fraction is the one judgement in this class — nothing
     * measures the shape of the last third — but what it is two thirds OF is real, and it moves
     * with the timeout rather than standing as a figure of its own.
     */
    public static final long SILENT_WARN_MILLIS = REQUEST_TIMEOUT_MILLIS * 2 / 3;

    /** What is true of this worker right now. */
    public enum Kind {
        /** Not enough is known yet to say anything — no token count, or no budget on record. */
        UNKNOWN,
        /** Inside its budget and not waiting unusually long. */
        FINE,
        /** Its conversation is larger than the context its model profile allows one session. */
        OVER_BUDGET,
        /** It has been waiting on the model for most of the time one request is allowed. */
        WAITING_TOO_LONG
    }

    private WorkerHealth() { }

    /**
     * The health of one running worker.
     *
     * @param tokens               tokens in its conversation right now; 0 when not known yet
     * @param contextBudgetTokens  the context its model profile allows one session; 0 when unknown
     * @param lastStepKind         the kind of its last recorded step, or null/blank when it has
     *                             taken none — {@code TOOL_RESULT}, {@code NUDGE} and
     *                             {@code SESSION_OPENED} mean a request to the model is in flight
     * @param lastStepAtMillis     when that step happened; 0 when it has taken none
     * @param nowMillis            the clock the durations are measured against, passed in so this
     *                             stays testable and free of side effects
     */
    public static Kind of(long tokens, long contextBudgetTokens,
                          String lastStepKind, long lastStepAtMillis, long nowMillis) {
        // Waiting is reported ahead of size, because it is the one that is about to happen: an
        // over-budget worker is in danger, a worker eleven minutes into a fifteen-minute request
        // is nearly dead, and the operator can only act on one sentence.
        if (waitingOnTheModel(lastStepKind) && lastStepAtMillis > 0
                && nowMillis - lastStepAtMillis >= SILENT_WARN_MILLIS) {
            return Kind.WAITING_TOO_LONG;
        }
        if (tokens <= 0 || contextBudgetTokens <= 0) {
            // A worker whose numbers have not arrived is not healthy and not sick. Saying so is
            // what keeps a chip with a missing count drawn as an ordinary chip.
            return Kind.UNKNOWN;
        }
        return tokens > contextBudgetTokens ? Kind.OVER_BUDGET : Kind.FINE;
    }

    /** True when the operator should be looking at this worker. */
    public static boolean isWarning(Kind kind) {
        return kind == Kind.OVER_BUDGET || kind == Kind.WAITING_TOO_LONG;
    }

    /**
     * True when the last step means the worker is waiting for the model to answer.
     *
     * <p>The worker loop hands tool results back and then asks the model again, so a session whose
     * last recorded step is a tool RESULT has a request in flight. Same after a nudge, and same
     * when it has only just opened. A session whose last step is a tool CALL is waiting for a
     * build, and a session whose last step is the model's own answer is running tools.
     */
    private static boolean waitingOnTheModel(String lastStepKind) {
        return "TOOL_RESULT".equals(lastStepKind)
            || "NUDGE".equals(lastStepKind)
            || "SESSION_OPENED".equals(lastStepKind);
    }

    /**
     * Why this worker is orange, in the operator's words — the sentence behind the colour.
     *
     * <p>Returns "" when there is nothing to warn about, so a caller can use it as the whole test.
     */
    public static String warning(Kind kind, long tokens, long contextBudgetTokens,
                                 long lastStepAtMillis, long nowMillis) {
        return switch (kind) {
            case OVER_BUDGET -> "its conversation is " + tokensWords(tokens)
                + ", past the " + tokensWords(contextBudgetTokens)
                + " it is allowed — every turn re-sends all of it";
            case WAITING_TOO_LONG -> "it has been waiting on the model for "
                + Elapsed.words(nowMillis - lastStepAtMillis)
                + " of the " + Elapsed.words(REQUEST_TIMEOUT_MILLIS) + " a request gets";
            default -> "";
        };
    }

    /** "42k" — token counts, short enough for a chip 52 pixels wide. */
    public static String tokensWords(long tokens) {
        if (tokens <= 0) {
            return "—";
        }
        if (tokens < 1000) {
            return String.valueOf(tokens);
        }
        // One decimal below ten thousand, so 4.2k and 4.9k do not both read as 4k on a chip where
        // the whole point of the number is watching it climb.
        if (tokens < 10_000) {
            long tenths = (tokens + 50) / 100;
            return (tenths / 10) + "." + (tenths % 10) + "k";
        }
        return ((tokens + 500) / 1000) + "k";
    }

    /**
     * "8/41k" — the conversation, and how much of it is the fixed head of the prompt.
     *
     * <p>The split is what says where the time goes. The head is the system prompt: the project's
     * rules, the task, the repository map. Every worker dispatched on one task sends the same one,
     * so a model server with a prefix cache reads it once for the whole group; and nothing ever
     * rewrites it, so it survives every compaction. Everything after it is this worker's own, and
     * that part is read again from scratch each time its history is compacted.
     *
     * <p><b>The chip has room for one more number and no more.</b> So the head loses its unit and
     * borrows the total's: "8/41k" is eight thousand of forty-one thousand, nine characters against
     * the seven "21t 41k" already spends of the eleven a 52-pixel chip holds. The words are on the
     * hover card, where there is room to say which part is which.
     *
     * <p>Falls back to {@link #tokensWords(long)} alone whenever the split would mislead: no
     * measurement, a head at least as big as the whole conversation (an estimate that has not been
     * overtaken by the first real count yet), or a conversation too small for the ratio to mean
     * anything. A chip with no measurement reads exactly as it did before this number existed.
     */
    public static String splitWords(long prefillTokens, long tokens) {
        if (tokens < 1000 || prefillTokens <= 0 || prefillTokens >= tokens) {
            return tokensWords(tokens);
        }
        long thousands = (prefillTokens + 500) / 1000;
        return (thousands <= 0 ? "<1" : String.valueOf(thousands)) + "/" + tokensWords(tokens);
    }

    /** True when the head of the prompt has been measured and is worth splitting out. */
    public static boolean hasPrefill(long prefillTokens, long tokens) {
        return tokens >= 1000 && prefillTokens > 0 && prefillTokens < tokens;
    }
}
