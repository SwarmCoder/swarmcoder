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
import java.util.List;
import java.util.UUID;
import java.util.Objects;

/**
 * The complete, persistent record of one agent session: who ran, on what, with which model
 * and sampling, and every step it took ({@link TraceEvent}s). Stored in EclipseStore for
 * post-analysis; streamed live (header first, then events) to the observer UI.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore, whose reflective
 * serializer does not handle records — see {@link VerificationReport}.
 *
 * <p>{@code outcome} is COMPLETED | KILLED | FAILED | RUNNING (live snapshots only).
 */
public class AgentSessionRecord {
    private UUID id;
    private UUID runId;
    private UUID taskId;
    private UUID candidateId;
    private int workerIndex;
    private String role;
    private String modelProfileId;
    private double temperature;
    private Instant openedAt;
    private Instant closedAt;
    private String outcome;
    private KillReason killReason;
    private int turns;
    private long totalTokens;
    private List<TraceEvent> events;

    public AgentSessionRecord() {}

    public AgentSessionRecord(UUID id, UUID runId, UUID taskId, UUID candidateId, int workerIndex, String role, String modelProfileId, double temperature, Instant openedAt, Instant closedAt, String outcome, KillReason killReason, int turns, long totalTokens, List<TraceEvent> events) {
        this.id = id;
        this.runId = runId;
        this.taskId = taskId;
        this.candidateId = candidateId;
        this.workerIndex = workerIndex;
        this.role = role;
        this.modelProfileId = modelProfileId;
        this.temperature = temperature;
        this.openedAt = openedAt;
        this.closedAt = closedAt;
        this.outcome = outcome;
        this.killReason = killReason;
        this.turns = turns;
        this.totalTokens = totalTokens;
        this.events = events;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID runId() { return runId; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public UUID taskId() { return taskId; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public UUID candidateId() { return candidateId; }
    public UUID getCandidateId() { return candidateId; }
    public void setCandidateId(UUID candidateId) { this.candidateId = candidateId; }
    public int workerIndex() { return workerIndex; }
    public int getWorkerIndex() { return workerIndex; }
    public void setWorkerIndex(int workerIndex) { this.workerIndex = workerIndex; }
    public String role() { return role; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String modelProfileId() { return modelProfileId; }
    public String getModelProfileId() { return modelProfileId; }
    public void setModelProfileId(String modelProfileId) { this.modelProfileId = modelProfileId; }
    public double temperature() { return temperature; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public Instant openedAt() { return openedAt; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }
    public Instant closedAt() { return closedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public String outcome() { return outcome; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public KillReason killReason() { return killReason; }
    public KillReason getKillReason() { return killReason; }
    public void setKillReason(KillReason killReason) { this.killReason = killReason; }
    public int turns() { return turns; }
    public int getTurns() { return turns; }
    public void setTurns(int turns) { this.turns = turns; }
    public long totalTokens() { return totalTokens; }
    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    public List<TraceEvent> events() { return events; }
    public List<TraceEvent> getEvents() { return events; }
    public void setEvents(List<TraceEvent> events) { this.events = events; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentSessionRecord that = (AgentSessionRecord) o;
        return this.workerIndex == that.workerIndex && Double.compare(this.temperature, that.temperature) == 0 && this.turns == that.turns && this.totalTokens == that.totalTokens && Objects.equals(this.id, that.id) && Objects.equals(this.runId, that.runId) && Objects.equals(this.taskId, that.taskId) && Objects.equals(this.candidateId, that.candidateId) && Objects.equals(this.role, that.role) && Objects.equals(this.modelProfileId, that.modelProfileId) && Objects.equals(this.openedAt, that.openedAt) && Objects.equals(this.closedAt, that.closedAt) && Objects.equals(this.outcome, that.outcome) && this.killReason == that.killReason && Objects.equals(this.events, that.events);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, runId, taskId, candidateId, workerIndex, role, modelProfileId, temperature, openedAt, closedAt, outcome, killReason, turns, totalTokens, events);
    }
}
