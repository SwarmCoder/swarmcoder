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

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.Collections;

/**
 * The observability spine (docs/OBSERVABILITY_DESIGN.md): every agent session reports every
 * step here. The hub fans events out to listeners in real time (observer UI, log sinks) and
 * accumulates the complete {@link AgentSessionRecord} that the persistence listener stores
 * in EclipseStore when the session ends.
 *
 * <p>Tracing must never break the swarm: listener failures are logged and swallowed, and
 * payloads above the inline cap are offloaded through the overflow function (blob store)
 * or truncated when none is configured.
 */
public final class TraceHub {

    /** A hub that records nothing — the default when observability is not wired. */
    public static final TraceHub NONE = new TraceHub(null);

    public interface Listener {
        /** A session opened; the snapshot carries the header and the SESSION_OPENED event. */
        default void sessionStarted(AgentSessionRecord snapshot) { }

        /** One live step of a running session. */
        default void event(UUID sessionId, TraceEvent event) { }

        /** The session ended; the record is complete (all events, outcome, totals). */
        default void sessionEnded(AgentSessionRecord complete) { }
    }

    private static final Logger log = LoggerFactory.getLogger(TraceHub.class);
    private static final int INLINE_PAYLOAD_CAP = 16_000;

    private final Function<String, String> overflow; // full content -> blob ref, or null
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, ActiveSession> active = new ConcurrentHashMap<>();

    /** @param overflow stores oversized payloads and returns a reference; null = truncate only */
    public TraceHub(Function<String, String> overflow) {
        this.overflow = overflow;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Live snapshots of all currently running sessions — the observer's initial state. */
    public List<AgentSessionRecord> activeSessions() {
        return active.values().stream().map(ActiveSession::snapshot).toList();
    }

    /**
     * The context one session of this worker is allowed to occupy, or 0 when it is not running here.
     *
     * <p>A live-only fact, so it is asked of the hub rather than added to the persisted session
     * record: it is the model profile's {@code workingContextTokens}, and it only means anything
     * while there is a conversation still growing against it. It is what turns a raw token count on
     * the screen into "past what this worker is allowed" — see {@code WorkerHealth}.
     */
    public int contextBudgetTokens(UUID sessionId) {
        ActiveSession session = active.get(sessionId);
        return session == null ? 0 : session.contextBudgetTokens;
    }

    /**
     * How much of this session's prompt is the fixed head that never changes — 0 when the session
     * is not running here, or when it was opened with no system prompt at all.
     *
     * <p>The head is the system prompt: {@link PromptBundle}'s shared segments — the project's
     * standing rules, the design excerpt, the task, the knowledge brief, the repository map — with
     * this worker's persona appended after them. Two things are true of it and of nothing else in
     * the conversation. It is byte-identical across every worker dispatched on one task apart from
     * that persona line, because {@link PromptBundle} exists to keep it so and
     * {@link PromptBundle#prefixHash()} is the fingerprint that proves it, so a model server with a
     * prefix cache prefills it ONCE for the whole group. And {@link HistoryTrim} never touches it,
     * so it survives every compaction while everything after it is rewritten and has to be read
     * again from scratch.
     *
     * <p>That is why this, and not "how much is warm in the cache right now", is the number a chip
     * carries. The warm prefix is nearly the whole conversation between compactions and collapses
     * to about this much for one turn after each of them, so it would say something different every
     * time an operator looked at it, and the thing it was saying would be about the last few
     * seconds rather than about the worker.
     *
     * <p><b>It is an estimate, and every surface that shows it says so.</b> There is no tokenizer
     * on this side, and the alternative — asking the server for a count — is a request per worker
     * per frame. This is characters over four, the same crude ratio {@link HistoryTrim} already
     * decides compaction with.
     *
     * <p>Live only, for the reason {@link #contextBudgetTokens(UUID)} is: it is a fact about a
     * conversation still being sent. A worker that has stopped keeps the numbers it ended with
     * rather than gaining a new one.
     */
    public int prefillTokens(UUID sessionId) {
        ActiveSession session = active.get(sessionId);
        return session == null ? 0 : session.prefillTokens;
    }

    SessionTracer begin(AgentRuntime.SessionSpec spec) {
        if (this == NONE) {
            return SessionTracer.NOOP;
        }
        ActiveSession session = new ActiveSession(spec);
        active.put(session.id, session);
        return session;
    }

    /** Per-session emitter handed to the runtime; package-private on purpose. */
    interface SessionTracer {
        SessionTracer NOOP = new SessionTracer() { };

        default void opened(String systemPrompt, String input) { }

        default void llmResponse(String text, List<String> toolCallSummaries, long tokensUsed) {
            llmResponse(text, toolCallSummaries, tokensUsed, 0);
        }

        /**
         * @param reasoningTokens estimated size of this turn's reasoning content (0 = the model did
         *                        not reason this turn, or reasoning is not tracked by this caller).
         *                        Never added to {@code tokensUsed}: the server's own completion-token
         *                        count already includes it, this is purely for display.
         */
        default void llmResponse(String text, List<String> toolCallSummaries, long tokensUsed,
                                 int reasoningTokens) { }

        default void toolCall(String tool, String args) { }

        default void toolResult(String tool, String output) { }

        default void nudge(String message) { }

        default void killed(KillReason reason) { }

        default void done(String summary) { }

        default void closed(String outcome, KillReason killReason, int turns, long totalTokens) { }
    }

    private final class ActiveSession implements SessionTracer {

        final UUID id;
        private final AgentRuntime.SessionMeta meta;
        private final String role;
        private final String modelProfileId;
        private final double temperature;
        private final Instant openedAt = Instant.now();
        private final List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong seq = new AtomicLong();
        private volatile long tokens;
        /** The context this session is allowed to occupy — see {@link #contextBudgetTokens(UUID)}. */
        final int contextBudgetTokens;
        /** The fixed head of its prompt, estimated — see {@link #prefillTokens(UUID)}. */
        final int prefillTokens;

        private ActiveSession(AgentRuntime.SessionSpec spec) {
            this.meta = spec.meta() != null ? spec.meta()
                : new AgentRuntime.SessionMeta(UUID.randomUUID(), null, null, null, -1);
            this.id = meta.sessionId() != null ? meta.sessionId() : UUID.randomUUID();
            this.role = spec.role();
            this.modelProfileId = spec.endpoint() != null ? spec.endpoint().modelId() : "";
            this.temperature = spec.temperature();
            this.contextBudgetTokens = spec.endpoint() == null || spec.endpoint().quirks() == null
                ? 0 : spec.endpoint().quirks().workingContextTokens();
            // Measured once, at open, because it is the one part of the prompt that cannot change
            // afterwards. A session opened without a system prompt reports 0, which every surface
            // reads as "no measurement" and draws exactly as it drew before this existed.
            String head = spec.systemPrompt();
            this.prefillTokens = head == null || head.isBlank()
                ? 0 : (int) CloudGate.estimateTokens(head);
        }

        @Override
        public void opened(String systemPrompt, String input) {
            emit(TraceEventKind.SESSION_OPENED, role, "SYSTEM:\n" + systemPrompt + "\n\nINPUT:\n" + input);
            AgentSessionRecord snapshot = snapshot();
            for (Listener listener : listeners) {
                try {
                    listener.sessionStarted(snapshot);
                } catch (Exception e) {
                    log.warn("Trace listener failed on sessionStarted: {}", e.getMessage());
                }
            }
        }

        @Override
        public void llmResponse(String text, List<String> toolCallSummaries, long tokensUsed,
                                int reasoningTokens) {
            this.tokens = tokensUsed;
            String payload = toolCallSummaries.isEmpty()
                ? text
                : (text == null || text.isBlank() ? "" : text + "\n")
                    + "tool calls: " + String.join(", ", toolCallSummaries);
            if (reasoningTokens > 0) {
                // A label, not a measurement: no tokenizer runs on this side, so this is the same
                // characters-over-four estimate HistoryTrim uses for the same reason. It is what
                // lets an operator see "this turn thought" at all now that thinking defaults on,
                // without needing a schema change to TraceEvent (persisted, reflectively, in
                // EclipseStore) for what is, so far, a display-only figure.
                payload = "[thought ~" + reasoningTokens + " tokens]\n"
                    + (payload == null ? "" : payload);
            }
            emit(TraceEventKind.LLM_RESPONSE, modelProfileId, payload);
        }

        @Override
        public void toolCall(String tool, String args) {
            emit(TraceEventKind.TOOL_CALL, tool, args);
        }

        @Override
        public void toolResult(String tool, String output) {
            emit(TraceEventKind.TOOL_RESULT, tool, output);
        }

        @Override
        public void nudge(String message) {
            emit(TraceEventKind.NUDGE, role, message);
        }

        @Override
        public void killed(KillReason reason) {
            emit(TraceEventKind.KILLED, reason.name(), null);
        }

        @Override
        public void done(String summary) {
            emit(TraceEventKind.DONE, role, summary);
        }

        @Override
        public void closed(String outcome, KillReason killReason, int turns, long totalTokens) {
            this.tokens = totalTokens;
            emit(TraceEventKind.SESSION_CLOSED, outcome, null);
            active.remove(id);
            AgentSessionRecord complete = new AgentSessionRecord(id, meta.runId(), meta.taskId(),
                meta.candidateId(), meta.workerIndex(), role, modelProfileId, temperature,
                openedAt, Instant.now(), outcome, killReason, turns, totalTokens,
                List.copyOf(events));
            for (Listener listener : listeners) {
                try {
                    listener.sessionEnded(complete);
                } catch (Exception e) {
                    log.warn("Trace listener failed on sessionEnded: {}", e.getMessage());
                }
            }
        }

        private void emit(TraceEventKind kind, String label, String rawPayload) {
            String payload = rawPayload;
            String payloadRef = null;
            if (payload != null && payload.length() > INLINE_PAYLOAD_CAP) {
                if (overflow != null) {
                    try {
                        payloadRef = overflow.apply(payload);
                    } catch (Exception e) {
                        log.warn("Trace payload overflow store failed: {}", e.getMessage());
                    }
                }
                payload = payload.substring(0, INLINE_PAYLOAD_CAP) + "\n[truncated; full content in blob "
                    + (payloadRef == null ? "unavailable" : payloadRef) + "]";
            }
            TraceEvent event = new TraceEvent(seq.getAndIncrement(), Instant.now(), kind, label, payload, payloadRef, tokens);
            events.add(event);
            for (Listener listener : listeners) {
                try {
                    listener.event(id, event);
                } catch (Exception e) {
                    log.warn("Trace listener failed on event: {}", e.getMessage());
                }
            }
        }

        private AgentSessionRecord snapshot() {
            List<TraceEvent> copy;
            synchronized (events) {
                copy = List.copyOf(events);
            }
            return new AgentSessionRecord(id, meta.runId(), meta.taskId(), meta.candidateId(),
                meta.workerIndex(), role, modelProfileId, temperature, openedAt, null,
                "RUNNING", null, turnsSoFar(copy), tokens, copy);
        }

        /**
         * How many turns this session has taken so far.
         *
         * <p>This was a hardcoded zero, and it stayed zero for the whole life of every worker: the
         * runtime counts turns in a field of its own and only hands the number over when the
         * session CLOSES, so everything reading a live worker — the run graph, the agent tools —
         * was told a running worker had taken no turns. The count is not passed in from a second
         * place to fix that: one assistant answer is one turn, the runtime increments its counter
         * immediately before recording that answer, so counting the answers here gives the same
         * number from the record that is already being kept.
         */
        private static int turnsSoFar(List<TraceEvent> recorded) {
            int turns = 0;
            for (TraceEvent event : recorded) {
                if (event.kind() == TraceEventKind.LLM_RESPONSE) {
                    turns++;
                }
            }
            return turns;
        }
    }
}
