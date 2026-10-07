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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A guided agent task — server-owned and persisted. The wizard dialog is a <em>view</em> of this
 * record, never its home.
 *
 * <p>That is not incidental. Extraction over a real document takes minutes, and a flow that lives
 * in the dialog is one the operator learns not to open, because closing the window throws the work
 * away. Because the flow is server state instead: close the wizard, keep working, re-open to find
 * it still running or finished; a second tab shows the same flow; a crash mid-flow leaves a
 * resumable record rather than nothing. {@code step} / {@code totalSteps} / {@code stepLabel} are
 * therefore real progress, not a spinner animating next to a request that may already have failed.
 *
 * <p>The state machine is {@code DRAFT -> RUNNING -> AWAITING_ANSWERS -> REVIEW -> APPLIED}, plus
 * {@link GuidedFlowState#FAILED} — in which case {@code error} says why. From any resting state the
 * operator may return to {@link GuidedFlowState#DRAFT} to add a document and run again; re-analysis
 * merges against what already exists and proposes changes, so re-running never starts from nothing.
 *
 * <p>{@code documents} is the input set for {@link GuidedFlowKind#REQUIREMENTS_INTAKE} — each entry
 * pairs a stored document with the operator's notes about it. The questions and proposals the flow
 * produces are separate objects that point back here by {@code flowId}; see {@link FlowQuestion}
 * and {@link FlowProposal}.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class GuidedFlow {
    private UUID id;
    private UUID projectId;
    private GuidedFlowKind kind;
    private GuidedFlowState state;
    private int step;                // 0-based position within the flow's progress
    private int totalSteps;
    private String stepLabel;        // what is happening right now, for the progress line
    private String error;            // set when state is FAILED
    private List<FlowDocument> documents;
    /**
     * How much document text this analysis may send the model, in characters. 0 means the default.
     *
     * <p>On the flow rather than in config because it is a per-analysis decision: one project's
     * intake is three pages of notes and another's is a 300-page specification, and the operator is
     * the only one who knows which model they pointed this at. Exceeding it REFUSES to start rather
     * than quietly reading less — a requirement dropped to fit a number is a requirement nobody
     * knows is missing.
     */
    private int characterBudget;
    private Instant createdAt;
    private Instant updatedAt;

    public GuidedFlow() {}

    public GuidedFlow(UUID id, UUID projectId, GuidedFlowKind kind, GuidedFlowState state, int step,
                      int totalSteps, String stepLabel, String error, List<FlowDocument> documents,
                      Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.kind = kind;
        this.state = state;
        this.step = step;
        this.totalSteps = totalSteps;
        this.stepLabel = stepLabel;
        this.error = error;
        this.documents = documents;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public GuidedFlowKind kind() { return kind; }
    public GuidedFlowKind getKind() { return kind; }
    public void setKind(GuidedFlowKind kind) { this.kind = kind; }
    public GuidedFlowState state() { return state; }
    public GuidedFlowState getState() { return state; }
    public void setState(GuidedFlowState state) { this.state = state; }
    public int step() { return step; }
    public int getStep() { return step; }
    public void setStep(int step) { this.step = step; }
    public int totalSteps() { return totalSteps; }
    public int getTotalSteps() { return totalSteps; }
    public void setTotalSteps(int totalSteps) { this.totalSteps = totalSteps; }
    public String stepLabel() { return stepLabel; }
    public String getStepLabel() { return stepLabel; }
    public void setStepLabel(String stepLabel) { this.stepLabel = stepLabel; }
    public String error() { return error; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    /** Null-safe: returns an empty list when unset (pre-existing stores load this field as null). */
    public List<FlowDocument> documents() {
        return documents == null ? List.of() : documents;
    }
    public List<FlowDocument> getDocuments() { return documents(); }
    public void setDocuments(List<FlowDocument> documents) { this.documents = documents; }
    public int characterBudget() { return characterBudget; }
    public int getCharacterBudget() { return characterBudget; }
    public void setCharacterBudget(int characterBudget) { this.characterBudget = characterBudget; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GuidedFlow that = (GuidedFlow) o;
        return this.step == that.step && this.totalSteps == that.totalSteps
            && this.characterBudget == that.characterBudget
            && Objects.equals(this.id, that.id) && Objects.equals(this.projectId, that.projectId)
            && Objects.equals(this.kind, that.kind) && Objects.equals(this.state, that.state)
            && Objects.equals(this.stepLabel, that.stepLabel)
            && Objects.equals(this.error, that.error)
            && Objects.equals(this.documents(), that.documents())
            && Objects.equals(this.createdAt, that.createdAt)
            && Objects.equals(this.updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        // documents compares through its NULL-SAFE accessor: an unset list IS empty, and the wire
        // serializer reads through the same accessor, so comparing the raw field would make a
        // round-tripped flow unequal to the one that was sent — silently breaking signal dedup.
        return Objects.hash(id, projectId, kind, state, step, totalSteps, stepLabel, error,
            characterBudget, documents(), createdAt, updatedAt);
    }
}
