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

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One node in a project's BRD requirement graph (author decision 2026-07-24): a first-class
 * business requirement as a persisted object rather than a line in a markdown file. Edges between
 * requirements are held separately on the {@link Brd} as {@link BrdEdge}s, so a requirement is a
 * pure node here. {@code handle} is the stable human label (R1, R2, …) used across the UI and in
 * the per-session {@link DesignDocument} traceability links.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 *
 * <p>Author decision 2026-07-25 (see docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md): functional and
 * non-functional requirements are ONE type distinguished by {@link #kind}, and a requirement owns
 * its {@link AcceptanceCriterion}s — they are its executable definition, and for a non-functional
 * requirement they are its <em>fitness</em> criteria. That is what makes the BRD living
 * documentation: a criterion binds to a test, so the spec cannot drift from the system. Criteria
 * used to hang off {@link Task} and died with the run.
 *
 * <p>The new fields are added via setters rather than the constructor, so existing callers and
 * pre-v7 stores are unaffected (they load as null — always read through the null-safe accessors).
 */
@DataModel
public class BrdRequirement {
    private UUID id;
    private String handle;      // R1, R2, … — stable label
    private String title;       // short one-line name
    private String text;        // full requirement statement
    private Priority priority;
    private RequirementStatus status;
    private String category;    // optional grouping/section/epic
    /** Functional or non-functional; null (pre-v7) reads as FUNCTIONAL. */
    private RequirementKind kind;
    /** Only meaningful when {@link #kind} is NON_FUNCTIONAL. */
    private NfrCategory nfrCategory;
    /** The executable definition of this requirement — acceptance criteria, or fitness criteria for an NFR. */
    private List<AcceptanceCriterion> criteria;
    /** Provenance: the uploaded document this was extracted from, null if hand-authored. */
    private SourceRef sourceRef;
    /**
     * Bumped whenever {@link #text} is materially edited. A criterion verified against an older
     * content revision renders STALE ("verified, but against older wording") rather than silently
     * continuing to report green.
     */
    private long contentRevision;

    public BrdRequirement() {}

    public BrdRequirement(UUID id, String handle, String title, String text,
                          Priority priority, RequirementStatus status, String category) {
        this.id = id;
        this.handle = handle;
        this.title = title;
        this.text = text;
        this.priority = priority;
        this.status = status;
        this.category = category;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String handle() { return handle; }
    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }
    public String title() { return title; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String text() { return text; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Priority priority() { return priority; }
    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }
    public RequirementStatus status() { return status; }
    public RequirementStatus getStatus() { return status; }
    public void setStatus(RequirementStatus status) { this.status = status; }
    public String category() { return category; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    /** Null-safe: a requirement with no recorded kind (pre-v7) is functional. */
    public RequirementKind kind() { return kind == null ? RequirementKind.FUNCTIONAL : kind; }
    public RequirementKind getKind() { return kind; }
    public void setKind(RequirementKind kind) { this.kind = kind; }
    public NfrCategory nfrCategory() { return nfrCategory; }
    public NfrCategory getNfrCategory() { return nfrCategory; }
    public void setNfrCategory(NfrCategory nfrCategory) { this.nfrCategory = nfrCategory; }
    /** Null-safe read; use {@link #setCriteria} to write. */
    public List<AcceptanceCriterion> criteria() { return criteria == null ? List.of() : criteria; }
    public List<AcceptanceCriterion> getCriteria() { return criteria; }
    public void setCriteria(List<AcceptanceCriterion> criteria) { this.criteria = criteria; }
    public SourceRef sourceRef() { return sourceRef; }
    public SourceRef getSourceRef() { return sourceRef; }
    public void setSourceRef(SourceRef sourceRef) { this.sourceRef = sourceRef; }
    public long contentRevision() { return contentRevision; }
    public long getContentRevision() { return contentRevision; }
    public void setContentRevision(long contentRevision) { this.contentRevision = contentRevision; }

    /** True when this requirement is non-functional and therefore a candidate gate. */
    public boolean isNonFunctional() { return kind() == RequirementKind.NON_FUNCTIONAL; }

    /**
     * True when this is one of the CONSTRAINT rows a store written between 2026-08-31 13:43 and
     * 2026-08-31 18:00 may still hold — see {@link RequirementKind#CONSTRAINT}.
     *
     * <p>Nothing creates one any more: a rule about how the project is built is a guideline, not a
     * requirement. Kept so the rows already in a store can be LABELLED as the leftovers they are
     * instead of appearing as ordinary requirements nobody wrote checks for. It grants no
     * exemption from anything, deliberately: every exemption it used to grant was the requirements
     * model saying these did not belong in it.
     */
    public boolean isConstraint() { return kind() == RequirementKind.CONSTRAINT; }

    /**
     * True when this requirement has been taken out of scope.
     *
     * <p><b>Retiring is what "delete" does here</b> (author decision 2026-08-28,
     * {@code docs/DEVELOPER_CORRECTIONS.md} §25.1). Nothing in the requirement graph is destroyed:
     * the trail from a requirement to the commit and the test that satisfied it is what this product
     * is FOR, and removing a requirement severs that trail silently, leaving delivered work with
     * nothing recording why it was built. So a retired requirement keeps its checks, its links, its
     * history and its evidence — and stops affecting anything.
     *
     * <p>"Stops affecting anything" is enforced from ONE place, and this is it. Everything downstream
     * asks this question rather than testing the status itself: {@link #gatingCriteria()} returns
     * nothing, so {@link #statusFromEvidence()} and the delivery gate see no checks;
     * {@link CheckCounts#of(BrdRequirement)} returns zero, so every coverage figure and roll-up drops
     * it; and a retired quality requirement gates nothing (§25.4 — it used to gate every descendant
     * for ever, with the rows still reading "constrains R1").
     */
    public boolean isRetired() { return status == RequirementStatus.DEPRECATED; }

    /**
     * <b>Has the operator agreed this requirement into scope?</b> The one question the whole
     * product turns on: nothing is built from a requirement nobody agreed.
     *
     * <p>ACTIVE is agreed and not yet delivered; IMPLEMENTED is agreed and delivered, and stays
     * plannable because a delivered requirement that gains a check — or loses its evidence —
     * reverts to ACTIVE by itself ({@link #statusFromEvidence()}), so refusing it would only refuse
     * legitimate re-work. DRAFT has never been agreed and DEPRECATED has been taken back out, and
     * neither may put anyone to work.
     *
     * <p>Asked from ONE place, for the same reason {@link #isRetired()} is. This used to be a
     * sentence in the planner's prompt asking it to restrain itself, and on 2026-08-31 an
     * end-to-end run measured what that was worth: six stories claiming sixteen checks, of which
     * one was agreed and fifteen belonged to requirements still in draft.
     */
    public boolean isAgreed() {
        return status == RequirementStatus.ACTIVE || status == RequirementStatus.IMPLEMENTED;
    }

    /**
     * The ACCEPTED criteria — the ones that actually gate. PROPOSED ones are advisory.
     *
     * <p>A RETIRED requirement gates nothing at all: its checks are history, kept so the work that
     * already shipped against it stays traceable, and evaluating them would mean a requirement the
     * operator took out of scope still deciding whether something counts as delivered.
     *
     */
    public List<AcceptanceCriterion> gatingCriteria() {
        if (isRetired()) {
            return List.of();
        }
        return criteria().stream().filter(AcceptanceCriterion::isGate).toList();
    }

    /**
     * The status this requirement's EVIDENCE supports, which is how the BRD stays honest as it
     * lives: a requirement is IMPLEMENTED only while every accepted criterion is passing against the
     * current wording.
     *
     * <p>So adding a criterion to an IMPLEMENTED requirement reverts it to ACTIVE — it is no longer
     * fully satisfied and must stop claiming it is — and a regression or a text edit that staled the
     * evidence does the same. DRAFT and DEPRECATED are operator-owned and never moved by evidence;
     * promotion out of DRAFT remains a human act.
     *
     * <p>The gate condition itself is {@link CheckCounts#allGatingPassing()} rather than a loop
     * written here, so this badge and the coverage ratio a tree row shows are one derivation and not
     * two that happen to agree (UX v3 rule 2). An empty gating set is not "all passing": there is
     * nothing executable to prove the requirement, so it cannot claim to be implemented.
     */
    public RequirementStatus statusFromEvidence() {
        if (status != RequirementStatus.ACTIVE && status != RequirementStatus.IMPLEMENTED) {
            return status;
        }
        return CheckCounts.of(this).allGatingPassing()
            ? RequirementStatus.IMPLEMENTED : RequirementStatus.ACTIVE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrdRequirement that = (BrdRequirement) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.handle, that.handle)
            && Objects.equals(this.title, that.title) && Objects.equals(this.text, that.text)
            && Objects.equals(this.priority, that.priority) && Objects.equals(this.status, that.status)
            && Objects.equals(this.category, that.category) && this.kind() == that.kind()
            && Objects.equals(this.nfrCategory, that.nfrCategory)
            && Objects.equals(this.criteria(), that.criteria())
            && Objects.equals(this.sourceRef, that.sourceRef) && this.contentRevision == that.contentRevision;
    }

    // kind and criteria compare through their NULL-SAFE accessors, not the raw fields: an unset kind
    // IS functional and an unset criteria list IS empty, so the two spellings must be equal. The wire
    // serializer reads through the same accessors, so comparing raw fields would make a round-tripped
    // requirement unequal to the one that was sent — which silently breaks signal dedup.
    @Override
    public int hashCode() {
        return Objects.hash(id, handle, title, text, priority, status, category,
            kind(), nfrCategory, criteria(), sourceRef, contentRevision);
    }
}
