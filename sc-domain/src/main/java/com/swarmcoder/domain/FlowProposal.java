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

import java.util.Objects;
import java.util.UUID;

/**
 * A proposed <em>change</em> to the BRD — not an applied one. Nothing a {@link GuidedFlow} produces
 * reaches the BRD until the operator accepts it and the flow is applied; the agent drafts, it never
 * promotes.
 *
 * <p>Proposing changes rather than content is what makes re-analysis safe. Adding or replacing a
 * document does not start from nothing: the agent is given what already exists and answers with a
 * diff, which avoids both failure modes of the alternatives — appending duplicates that must be
 * weeded out by hand, or replacing wholesale and discarding every edit made since the last
 * extraction.
 *
 * <p>{@code before} and {@code after} are both carried so an {@link FlowProposalKind#EDIT} can be
 * shown as a diff; an {@link FlowProposalKind#ADD} has no {@code before}. A
 * {@link FlowProposalKind#CONFLICT} marks two requirements that cannot both hold — there is nothing
 * to apply, only a contradiction to resolve. {@code handle} is the stable label of the requirement
 * the proposal touches (e.g. "R3") and {@code rationale} is why the agent believes it.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class FlowProposal {
    private UUID id;
    private UUID flowId;
    private FlowProposalKind kind;
    private String handle;           // stable label of the requirement touched (R1, R2, …)
    private String title;
    private String before;           // null for ADD
    private String after;
    private String rationale;
    /**
     * What applying this would disturb downstream — accepted criteria that go stale, stories and
     * tasks already scheduled against it. Null when nothing is affected.
     *
     * <p>Computed on the server when the proposal is made, because the browser cannot see the
     * backlog. The operator ticking a box needs to know the cost BEFORE they tick it; discovering
     * afterwards that a review invalidated a fortnight of verified work is not a review.
     */
    private String impact;
    private boolean accepted;        // the operator's decision; nothing lands until apply

    public FlowProposal() {}

    public FlowProposal(UUID id, UUID flowId, FlowProposalKind kind, String handle, String title,
                        String before, String after, String rationale, String impact,
                        boolean accepted) {
        this.id = id;
        this.flowId = flowId;
        this.kind = kind;
        this.handle = handle;
        this.title = title;
        this.before = before;
        this.after = after;
        this.rationale = rationale;
        this.impact = impact;
        this.accepted = accepted;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID flowId() { return flowId; }
    public UUID getFlowId() { return flowId; }
    public void setFlowId(UUID flowId) { this.flowId = flowId; }
    public FlowProposalKind kind() { return kind; }
    public FlowProposalKind getKind() { return kind; }
    public void setKind(FlowProposalKind kind) { this.kind = kind; }
    public String handle() { return handle; }
    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }
    public String title() { return title; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String before() { return before; }
    public String getBefore() { return before; }
    public void setBefore(String before) { this.before = before; }
    public String after() { return after; }
    public String getAfter() { return after; }
    public void setAfter(String after) { this.after = after; }
    public String rationale() { return rationale; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public String impact() { return impact; }
    public String getImpact() { return impact; }
    public void setImpact(String impact) { this.impact = impact; }
    public boolean accepted() { return accepted; }
    public boolean isAccepted() { return accepted; }
    public void setAccepted(boolean accepted) { this.accepted = accepted; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlowProposal that = (FlowProposal) o;
        return this.accepted == that.accepted && Objects.equals(this.id, that.id)
            && Objects.equals(this.flowId, that.flowId) && Objects.equals(this.kind, that.kind)
            && Objects.equals(this.handle, that.handle) && Objects.equals(this.title, that.title)
            && Objects.equals(this.before, that.before) && Objects.equals(this.after, that.after)
            && Objects.equals(this.rationale, that.rationale)
            && Objects.equals(this.impact, that.impact);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, flowId, kind, handle, title, before, after, rationale, impact,
            accepted);
    }
}
