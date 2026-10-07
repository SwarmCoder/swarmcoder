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

import com.swarmcoder.domain.KillReason;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.Method;

/**
 * The only boundary to the agent framework (spec §6.1; rule R4 in DEVELOPER_CORRECTIONS.md:
 * Koog is the framework, and its types MUST NOT leak past this interface — enforced by
 * ArchUnit). Sessions run a complete tool loop; per-turn control is exercised through the
 * {@link TurnGuard} rather than by driving turns manually, which matches both Koog's
 * functional-strategy shape and the mini-SWE-agent worker design.
 *
 * <p>Checkpoint/rollback/fork/compress (the Context Ledger surface, spec §6.1/§12) are
 * declared but not implemented until M4 — see sc-runtime/NOTES.md.
 */
public interface AgentRuntime {

    AgentSession open(SessionSpec spec);

    interface AgentSession extends AutoCloseable {

        /** Runs the session's tool loop to completion (final answer, report_done, or kill). */
        SessionResult run(String input);

        /**
         * True when the last {@link #run} ended cleanly (a hand-in or a plain answer) and kept its
         * conversation, so that calling {@link #run} again with a new message continues it - the
         * history, with every lookup in it, is still there. False for a session that was killed,
         * failed, never ran, or whose runtime cannot continue.
         */
        default boolean canContinue() {
            return false;
        }

        /** Estimated size of the conversation a {@link #canContinue} session holds; -1 unknown. */
        default long conversationTokens() {
            return -1;
        }

        /** Total tokens consumed so far, best-effort from the underlying framework. */
        long tokensUsed();

        /**
         * Stops the session now, from another thread: a model request in flight is abandoned
         * rather than waited for, and {@link #run} returns killed with {@code reason}.
         *
         * <p>Live run 74, 2026-10-03: a worker told to stop only noticed at its next turn, and
         * its task waited twelve minutes for a request whose answer nobody wanted. Does nothing
         * by default, and then the session stops at its next turn as it always did.
         */
        default void cancel(com.swarmcoder.domain.KillReason reason) {
        }

        /**
         * What the model server itself counted as sent to it over this session's calls, summed -
         * every turn resends the conversation, so this is far more than the conversation's size.
         * -1 when the runtime does not know.
         */
        default long promptTokensSent() {
            return -1;
        }

        /**
         * True when this session ended on a request the client gave up waiting for, and the
         * conversation that request carried was small against the working context. Then the
         * answer was too long for the time allowed, not the room too small, and the ending is no
         * evidence about room (live run 74). False when unknown.
         */
        default boolean timedOutOnASmallConversation() {
            return false;
        }

        /** What the model server counted as generated over this session's calls; -1 unknown. */
        default long completionTokensGenerated() {
            return -1;
        }

        /** Context Ledger surface — M4 (see sc-runtime/NOTES.md). */
        default String checkpoint(String label) {
            throw new UnsupportedOperationException("Context Ledger checkpoints arrive in M4");
        }

        default void rollback(String checkpointLabel) {
            throw new UnsupportedOperationException("Context Ledger rollback arrives in M4");
        }

        default AgentSession fork(String childSeedPrompt) {
            throw new UnsupportedOperationException("Debug-fork quarantine arrives in M4");
        }

        default void compress() {
            throw new UnsupportedOperationException("Compaction hook arrives in M4");
        }

        @Override
        void close();
    }

    /**
     * Everything needed to open a session. The system prompt is the deterministic shared
     * prefix (spec §6.3); per-worker diversity (persona, sampling) is appended by the caller
     * so prefix-cache alignment stays intact.
     *
     * @param meta observability identity (nullable — an anonymous session is still traced)
     */
    record SessionSpec(
        String role,
        String systemPrompt,
        ModelEndpoint endpoint,
        double temperature,
        int maxTurns,
        List<ToolBinding> tools,
        TurnGuard guard,
        SessionMeta meta,
        SessionOptions options
    ) {
        public SessionSpec {
            options = options == null ? SessionOptions.NONE : options;
        }

        /** Convenience constructor for sessions without observability identity. */
        public SessionSpec(String role, String systemPrompt, ModelEndpoint endpoint,
                           double temperature, int maxTurns, List<ToolBinding> tools, TurnGuard guard) {
            this(role, systemPrompt, endpoint, temperature, maxTurns, tools, guard, null, null);
        }

        /** A session run as every session always was: no {@link SessionOptions}. */
        public SessionSpec(String role, String systemPrompt, ModelEndpoint endpoint,
                           double temperature, int maxTurns, List<ToolBinding> tools, TurnGuard guard,
                           SessionMeta meta) {
            this(role, systemPrompt, endpoint, temperature, maxTurns, tools, guard, meta, null);
        }

        /** This session with {@code options}; everything else as it is. */
        public SessionSpec with(SessionOptions options) {
            return new SessionSpec(role, systemPrompt, endpoint, temperature, maxTurns, tools,
                guard, meta, options);
        }
    }

    /**
     * How a session is run, where that differs from a worker's (2026-10-02). {@link #NONE} is
     * every session as it always ran.
     *
     * @param tidyAboveTokens once the tool results older than the last few turns add up to more
     *                        than this (estimated tokens), the oldest are replaced by a digest of
     *                        themselves - see {@code HistoryTrim.tidy}. 0 never does. Not a limit:
     *                        nothing is stopped by it.
     * @param tidyToTokens    how much of those older results is left whole after a tidy
     * @param digestChars     how much of each replaced result's beginning its digest keeps
     * @param upcoming        nullable. Told every call of a turn that makes SEVERAL calls - the
     *                        tool's name and its arguments as JSON - before the first of them is
     *                        run, so a toolbox can start its slow, independent ones (a question
     *                        to the expert takes minutes) at once instead of one after the other.
     *                        The calls themselves are still run in order, one at a time, exactly
     *                        as before; a toolbox that started one early answers it from that
     *                        when its turn comes. Null tells nobody.
     * @param tidyAboveWhenCachedTokens the mark used instead of {@code tidyAboveTokens} once the
     *                        session's server has reported prompt tokens taken from its prefix
     *                        cache, when it is the larger of the two: a tidy rewrites history, and
     *                        everything after the rewrite is billed in full again. Half of it is
     *                        then left whole. 0 keeps the one mark.
     */
    record SessionOptions(int tidyAboveTokens, int tidyToTokens, int digestChars,
                          java.util.function.BiConsumer<String, String> upcoming,
                          int tidyAboveWhenCachedTokens) {

        public SessionOptions(int tidyAboveTokens, int tidyToTokens, int digestChars,
                              java.util.function.BiConsumer<String, String> upcoming) {
            this(tidyAboveTokens, tidyToTokens, digestChars, upcoming, 0);
        }

        public static final SessionOptions NONE = new SessionOptions(0, 0, 0, null);

        /** Only the early notice of a turn's calls; no tidying. */
        public static SessionOptions tellingOfUpcomingCalls(
                java.util.function.BiConsumer<String, String> upcoming) {
            return new SessionOptions(0, 0, 0, upcoming);
        }
    }

    /** Links a session to the swarm entities it works for — the observer's join keys. */
    record SessionMeta(UUID sessionId, UUID runId, UUID taskId,
                       UUID candidateId, int workerIndex) {}

    /**
     * An OpenAI-compatible chat endpoint (vLLM on the Spark, or a cloud role endpoint), together
     * with everything model-specific about it.
     *
     * <p>{@code quirks} is what makes a second model a config change rather than a code change:
     * the generation cap, whether the model reasons before answering and which in-prompt directive
     * or chat-template argument switches that off, whether the chat template can take native
     * tool-call history, the HTTP version, and the context ceilings. Every one of those was a
     * constant or a JVM-wide switch before 2026-08-27.
     *
     * @param contextLength the SERVED ceiling the model is started with; the working budget one
     *                      session may occupy is {@code quirks.workingContextTokens()}
     */
    record ModelEndpoint(String baseUrl, String apiKey, String modelId, long contextLength,
                         com.swarmcoder.inference.ModelQuirks quirks) {

        public ModelEndpoint {
            quirks = quirks == null ? com.swarmcoder.inference.ModelQuirks.DEFAULTS : quirks;
        }

        /** Endpoint with default quirks — kept so pre-2026-08 call sites compile unchanged. */
        public ModelEndpoint(String baseUrl, String apiKey, String modelId, long contextLength) {
            this(baseUrl, apiKey, modelId, contextLength, com.swarmcoder.inference.ModelQuirks.DEFAULTS);
        }
    }

    /**
     * One callable tool: a plain public Java {@link Method} on {@code target}.
     * Parameter names become the tool schema (the build compiles with {@code -parameters}).
     */
    record ToolBinding(String name, String description, Object target, Method method) {}

    /**
     * Consulted after every assistant turn — the early-kill hook (spec §11.3). Returning a
     * KillReason terminates the session immediately with that reason.
     */
    @FunctionalInterface
    interface TurnGuard {
        Optional<KillReason> check(TurnInfo info);

        /**
         * Steering the orchestrator has already given the model this turn, for the session
         * trace. The text itself reaches the model through the tool result that provoked it
         * (a valid tool-call/tool-result sequence is what small models' chat templates handle
         * most reliably); this is purely so the run shows a NUDGE where it happened instead of
         * the operator having to find it buried in a tool result. Empty by default.
         */
        default Optional<String> steeringGiven() {
            return Optional.empty();
        }

        /**
         * @param turnIndex           completed assistant turns so far
         * @param consecutiveTextTurns assistant turns in a row that carried no tool call and
         *                             no completion signal — the malformed-output signal
         * @param tokensUsed          best-effort total token usage
         */
        record TurnInfo(int turnIndex, int consecutiveTextTurns, long tokensUsed) {}
    }

    /**
     * Outcome of a session run.
     *
     * @param finalOutput text of the final assistant message or the report_done summary
     * @param killReason  present when the session was killed by the guard or by turn/budget caps
     * @param turns       assistant turns consumed
     * @param tokensUsed  best-effort total token usage
     */
    record SessionResult(String finalOutput, Optional<KillReason> killReason, int turns, long tokensUsed) {

        public boolean completed() {
            return killReason.isEmpty();
        }
    }
}
