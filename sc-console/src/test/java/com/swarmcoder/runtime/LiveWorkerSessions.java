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

import java.util.List;
import java.util.UUID;

/**
 * Opens sessions on a real {@link TraceHub} exactly the way a worker does, so a test can ask what
 * the Console shows WHILE workers are running.
 *
 * <p>It lives in the runtime's own package because {@code TraceHub.begin} is package-private: the
 * hub's live map may only be filled by opening a session, and this opens one. Nothing here fakes
 * the hub — the record the Console reads back is the record production writes.
 */
public final class LiveWorkerSessions {

    private LiveWorkerSessions() { }

    /**
     * Opens one worker session and leaves it running, as a dispatched worker does — with no system
     * prompt, so nothing has measured the fixed head of its conversation.
     *
     * <p>That is not a shortcut, it is one of the two cases: a worker whose head has not been
     * measured must draw exactly as it drew before that number existed.
     */
    public static UUID open(TraceHub hub, UUID runId, UUID taskId, int workerIndex,
                            String model, double temperature) {
        return open(hub, runId, taskId, workerIndex, model, temperature, 0);
    }

    /**
     * The same, with a system prompt the size a real worker's is.
     *
     * <p>The prompt is real text of the right length, not a number handed to the hub: the hub
     * measures the head of the conversation itself, at four characters to the token, and a fixture
     * that set the answer directly would prove nothing about the measurement the Console shows.
     *
     * @param prefillTokens how big the fixed head of its prompt should come out; 0 for none
     */
    public static UUID open(TraceHub hub, UUID runId, UUID taskId, int workerIndex,
                            String model, double temperature, int prefillTokens) {
        UUID sessionId = UUID.randomUUID();
        String systemPrompt = prefillTokens <= 0 ? "" : "x".repeat(prefillTokens * 4);
        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec(
            "worker-" + workerIndex, systemPrompt,
            new AgentRuntime.ModelEndpoint("http://127.0.0.1:1/v1", "", model, 32768),
            temperature, 120, List.of(), info -> java.util.Optional.empty(),
            new AgentRuntime.SessionMeta(sessionId, runId, taskId, UUID.randomUUID(), workerIndex));
        TraceHub.SessionTracer tracer = hub.begin(spec);
        tracer.opened(systemPrompt, "Begin now.");
        tracers.put(sessionId, tracer);
        return sessionId;
    }

    /** One tool call from a worker that is already open — a step the operator should be able to see. */
    public static void step(TraceHub hub, UUID sessionId, String tool, String args) {
        TraceHub.SessionTracer tracer = tracers.get(sessionId);
        if (tracer != null) {
            tracer.toolCall(tool, args);
        }
    }

    /**
     * One assistant turn, with the size the conversation has reached — a worker answering.
     *
     * <p>This is the step that carries the number that decides whether a worker lives: the token
     * total the model reported for the request, which IS the size of the conversation, because
     * every turn re-sends all of it and nothing trims it.
     */
    public static void answered(TraceHub hub, UUID sessionId, String text, long tokensUsed) {
        TraceHub.SessionTracer tracer = tracers.get(sessionId);
        if (tracer != null) {
            tracer.llmResponse(text, java.util.List.of(), tokensUsed);
        }
    }

    /** The results of a tool handed back — after which the worker is waiting on the model. */
    public static void resultReturned(TraceHub hub, UUID sessionId, String tool, String output) {
        TraceHub.SessionTracer tracer = tracers.get(sessionId);
        if (tracer != null) {
            tracer.toolResult(tool, output);
        }
    }

    /**
     * The worker stops - exactly what the runtime does at the end of a session.
     *
     * <p>The hub drops it from its live map and hands the complete record to its listeners, which
     * is what persists it. That gap - out of the live map, not yet archived as a candidate - is
     * where four chips became two on the operator's screen, so a test that cannot reach it cannot
     * pin the fix. Outcomes are the runtime's own: COMPLETED, KILLED, FAILED.
     */
    public static void closed(TraceHub hub, UUID sessionId, String outcome,
                              com.swarmcoder.domain.KillReason killReason, int turns,
                              long totalTokens) {
        TraceHub.SessionTracer tracer = tracers.remove(sessionId);
        if (tracer != null) {
            tracer.closed(outcome, killReason, turns, totalTokens);
        }
    }

    private static final java.util.Map<UUID, TraceHub.SessionTracer> tracers =
        new java.util.concurrent.ConcurrentHashMap<>();
}
