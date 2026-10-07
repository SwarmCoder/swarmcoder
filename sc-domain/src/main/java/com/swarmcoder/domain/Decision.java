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

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.zeroz4j.api.DataModel;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Something the swarm stopped to ask the operator: a blocked task, a run parked mid-workflow, an
 * exhausted budget. Raised where work halted, and answered in the Approval Center.
 *
 * <p>{@code @DataModel} because it travels the wire directly — {@code ControlService.decisions()}
 * returns these, no DTO. Without the annotation the annotation processor generates no serializer and
 * the call fails at the frame with "Unsupported type for GrowableBuffer", which is what happened:
 * the service was migrated to return the domain object and the class was never annotated, so the
 * Approval Center could not load at all.
 */
@DataModel
@JsonTypeName("Decision")
public class Decision {
    private UUID id;
    private UUID runId;
    private DecisionKind kind;
    private String briefMarkdown;
    private DecisionState state;
    private String humanResponse;
    private Instant createdAt;

    public Decision() {}

    public Decision(UUID id, UUID runId, DecisionKind kind, String briefMarkdown, DecisionState state, String humanResponse, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.kind = kind;
        this.briefMarkdown = briefMarkdown;
        this.state = state;
        this.humanResponse = humanResponse;
        this.createdAt = createdAt;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID runId() { return runId; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public DecisionKind kind() { return kind; }
    public DecisionKind getKind() { return kind; }
    public void setKind(DecisionKind kind) { this.kind = kind; }
    public String briefMarkdown() { return briefMarkdown; }
    public String getBriefMarkdown() { return briefMarkdown; }
    public void setBriefMarkdown(String briefMarkdown) { this.briefMarkdown = briefMarkdown; }
    public DecisionState state() { return state; }
    public DecisionState getState() { return state; }
    public void setState(DecisionState state) { this.state = state; }
    public String humanResponse() { return humanResponse; }
    public String getHumanResponse() { return humanResponse; }
    public void setHumanResponse(String humanResponse) { this.humanResponse = humanResponse; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Decision that = (Decision) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.runId, that.runId) && Objects.equals(this.kind, that.kind) && Objects.equals(this.briefMarkdown, that.briefMarkdown) && Objects.equals(this.state, that.state) && Objects.equals(this.humanResponse, that.humanResponse) && Objects.equals(this.createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, runId, kind, briefMarkdown, state, humanResponse, createdAt);
    }
}

