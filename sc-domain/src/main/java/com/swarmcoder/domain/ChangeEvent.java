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

import com.zeroz4j.api.DataModel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry in the append-only audit journal for the planning layer. Events are never mutated and
 * never removed; a correction is a new event, so the plan's history stays readable exactly as it
 * happened.
 *
 * <p>{@code actor} attributes the change and is one of {@code human}, {@code agent},
 * {@code system} or {@code extraction}. {@code entityType} plus {@code entityId} identify what
 * changed, {@code kind} says what sort of change it was, and for a field-level edit
 * {@code field} / {@code before} / {@code after} carry the values. {@code runId} is set when a
 * {@link Run} caused the change and null otherwise.
 *
 * <p>Distinct from {@link TraceEvent} / {@link AgentSessionRecord}, which record what
 * <em>agents did</em> — prompts, tool calls, token spend. This records what <em>the plan is and
 * was</em>: how the requirements, criteria, stories and iterations came to their current shape.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class ChangeEvent {
    private UUID id;
    private UUID projectId;
    private Instant at;
    private String actor;               // human | agent | system | extraction
    private ChangeEntityType entityType;
    private UUID entityId;
    private ChangeKind kind;
    private String field;               // set for field-level edits
    private String before;
    private String after;
    private String summary;             // short human-readable note
    private UUID runId;                 // set when a run caused the change

    public ChangeEvent() {}

    public ChangeEvent(UUID id, UUID projectId, Instant at, String actor,
                       ChangeEntityType entityType, UUID entityId, ChangeKind kind,
                       String field, String before, String after, String summary, UUID runId) {
        this.id = id;
        this.projectId = projectId;
        this.at = at;
        this.actor = actor;
        this.entityType = entityType;
        this.entityId = entityId;
        this.kind = kind;
        this.field = field;
        this.before = before;
        this.after = after;
        this.summary = summary;
        this.runId = runId;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public Instant at() { return at; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public String actor() { return actor; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public ChangeEntityType entityType() { return entityType; }
    public ChangeEntityType getEntityType() { return entityType; }
    public void setEntityType(ChangeEntityType entityType) { this.entityType = entityType; }
    public UUID entityId() { return entityId; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public ChangeKind kind() { return kind; }
    public ChangeKind getKind() { return kind; }
    public void setKind(ChangeKind kind) { this.kind = kind; }
    public String field() { return field; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public String before() { return before; }
    public String getBefore() { return before; }
    public void setBefore(String before) { this.before = before; }
    public String after() { return after; }
    public String getAfter() { return after; }
    public void setAfter(String after) { this.after = after; }
    public String summary() { return summary; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public UUID runId() { return runId; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChangeEvent that = (ChangeEvent) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.projectId, that.projectId)
            && Objects.equals(this.at, that.at) && Objects.equals(this.actor, that.actor)
            && Objects.equals(this.entityType, that.entityType)
            && Objects.equals(this.entityId, that.entityId) && Objects.equals(this.kind, that.kind)
            && Objects.equals(this.field, that.field) && Objects.equals(this.before, that.before)
            && Objects.equals(this.after, that.after) && Objects.equals(this.summary, that.summary)
            && Objects.equals(this.runId, that.runId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, projectId, at, actor, entityType, entityId, kind, field,
            before, after, summary, runId);
    }
}
