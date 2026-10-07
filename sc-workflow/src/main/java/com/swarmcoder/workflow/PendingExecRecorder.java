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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.PendingExec;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flushes evidence of a worker's {@code exec} tool call to durable storage BEFORE the command
 * ever runs — see {@link PendingExec} for the incident this fixes (two harness deaths, runs 21
 * and 29, where a worker's shell command killed the harness JVM and nothing on disk said which
 * command it had been).
 *
 * <h2>Why the trace hub, and why this is the right moment</h2>
 *
 * <p>{@link com.swarmcoder.runtime.KoogAgentRuntime}'s worker loop calls
 * {@code tracer.toolCall(tool, args)} for every tool call in a turn, ON THE WORKER'S OWN THREAD,
 * strictly BEFORE it calls {@code ctx.executeTools(...)} to actually run them. That ordering is
 * exactly what a "recorded before it runs" guarantee needs, and it already exists — the only
 * thing missing was a listener that turns the in-memory event into a durable write and BLOCKS
 * the calling thread until it lands, so the write really does finish before the process starts.
 * Every other {@link TraceHub.Listener} in this codebase fires and forgets (see
 * {@link RunHeartbeat}); this one is deliberately different, and only for the {@code exec} tool.
 *
 * <p>{@link ArtifactStore#recordPendingExec} does the blocking write; this class only figures out
 * which run/task a session belongs to (the same {@code sessionStarted}-populated map
 * {@link RunHeartbeat} uses) and clears the record once the command has returned — a session that
 * dies with an entry still in {@code pendingExecs} is exactly the forensic trail the incident
 * needed and did not have.
 */
public final class PendingExecRecorder implements TraceHub.Listener {

    private static final Logger log = LoggerFactory.getLogger(PendingExecRecorder.class);
    private static final int MAX_COMMAND_CHARS = 300;
    static final String EXEC_TOOL = "exec";

    private final ArtifactStore store;
    /** sessionId -> (runId, taskId, role), populated at open and read for every exec call. */
    private final Map<UUID, SessionIdentity> sessions = new ConcurrentHashMap<>();

    public PendingExecRecorder(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public void sessionStarted(AgentSessionRecord snapshot) {
        if (snapshot == null) {
            return;
        }
        sessions.put(snapshot.id(),
            new SessionIdentity(snapshot.runId(), snapshot.taskId(), snapshot.role()));
    }

    @Override
    public void event(UUID sessionId, TraceEvent event) {
        if (event == null || !EXEC_TOOL.equals(event.label())) {
            return;
        }
        if (event.kind() == TraceEventKind.TOOL_CALL) {
            recordStart(sessionId, event.payload());
        } else if (event.kind() == TraceEventKind.TOOL_RESULT) {
            // The command finished (or timed out and was killed) — the ordinary session record
            // will carry the full result once the session closes. Clearing here just keeps the
            // pending map from claiming a command is still running once it plainly is not.
            store.clearPendingExec(sessionId);
        }
    }

    @Override
    public void sessionEnded(AgentSessionRecord complete) {
        if (complete != null) {
            sessions.remove(complete.id());
            store.clearPendingExec(complete.id());
        }
    }

    /**
     * Blocks the calling (worker) thread until the record is on disk. This is the load-bearing
     * part: {@link com.swarmcoder.runtime.KoogAgentRuntime} calls {@code tracer.toolCall} for
     * every call in a turn before it runs ANY of them, so blocking here delays "start the process"
     * until after the write is durable, exactly as required.
     */
    private void recordStart(UUID sessionId, String rawArgs) {
        try {
            SessionIdentity identity = sessions.get(sessionId);
            String command = truncate(rawArgs);
            PendingExec exec = new PendingExec(sessionId,
                identity == null ? null : identity.runId(),
                identity == null ? null : identity.taskId(),
                identity == null ? null : identity.role(),
                command, Instant.now());
            store.recordPendingExec(exec);
        } catch (Exception e) {
            // Never break a worker's exec over a forensics write. A missed record costs exactly
            // what the run had before this class existed; an exception thrown back into the trace
            // hub costs the exec call itself.
            log.warn("Could not record pending exec for session {}: {}", sessionId, e.toString());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_COMMAND_CHARS ? s.substring(0, MAX_COMMAND_CHARS) + "…" : s;
    }

    private record SessionIdentity(UUID runId, UUID taskId, String role) {}
}
