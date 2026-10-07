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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Evidence that a worker's {@code exec} tool call is ABOUT TO RUN, written to durable storage
 * before the command is ever launched — see {@code PendingExecRecorder} in {@code sc-workflow}.
 *
 * <p>This exists because of two harness deaths (runs 21 and 29) where a worker's shell command
 * killed the JVM that was running the whole harness. Both times the session's own transcript —
 * every {@code TOOL_CALL}/{@code TOOL_RESULT} it had recorded — lived only in memory and was lost
 * with the process, so nothing on disk said which command it was. A worker session's full
 * transcript is only appended to the store when the session CLOSES (see
 * {@code DependencyGraph}'s {@code TraceHub.Listener}); a JVM that dies mid-session never reaches
 * that point. This record is the one piece of the in-flight exec that IS flushed synchronously,
 * before the process starts, specifically so a death like that leaves evidence.
 *
 * <p>Keyed by session id in {@code StoreRoot.pendingExecs} and overwritten on every new exec call
 * from that session; cleared once the tool result comes back, so the map only ever holds
 * commands genuinely in flight (or the last one before an unexplained death).
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore, whose reflective
 * serializer does not handle records — see {@link VerificationReport}.
 */
public class PendingExec {
    private UUID sessionId;
    private UUID runId;
    private UUID taskId;
    private String role;
    private String command;
    private Instant startedAt;

    public PendingExec() {}

    public PendingExec(UUID sessionId, UUID runId, UUID taskId, String role, String command,
                       Instant startedAt) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.taskId = taskId;
        this.role = role;
        this.command = command;
        this.startedAt = startedAt;
    }

    public UUID sessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
    public UUID runId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public UUID taskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public String role() { return role; }
    public void setRole(String role) { this.role = role; }
    public String command() { return command; }
    public void setCommand(String command) { this.command = command; }
    public Instant startedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PendingExec that = (PendingExec) o;
        return Objects.equals(sessionId, that.sessionId) && Objects.equals(runId, that.runId)
            && Objects.equals(taskId, that.taskId) && Objects.equals(role, that.role)
            && Objects.equals(command, that.command) && Objects.equals(startedAt, that.startedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, runId, taskId, role, command, startedAt);
    }
}
