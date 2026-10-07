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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
@JsonTypeName("DesignDocument")
public class DesignDocument {
    private UUID id;
    private long revision;
    private String goal;
    private List<Requirement> requirements;
    private List<ArchDecision> decisions;
    private List<ApiContract> contracts;
    private List<Risk> risks;
    private ReviewVerdict review;
    private Instant createdAt;
    /**
     * Ids of the project BRD's {@link BrdRequirement}s this session's design addresses
     * (author decision 2026-07-24: "the DesignDocument can refer to the relevant BRD sections").
     * Added via setter, never the constructor, so existing callers and pre-v5 stores are
     * unaffected (loads as null on old records — always read through {@link #brdRequirementIds()}).
     */
    private List<UUID> brdRequirementIds;

    public DesignDocument() {}

    public DesignDocument(UUID id, long revision, String goal, List<Requirement> requirements, List<ArchDecision> decisions, List<ApiContract> contracts, List<Risk> risks, ReviewVerdict review, Instant createdAt) {
        this.id = id;
        this.revision = revision;
        this.goal = goal;
        this.requirements = requirements;
        this.decisions = decisions;
        this.contracts = contracts;
        this.risks = risks;
        this.review = review;
        this.createdAt = createdAt;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long revision() { return revision; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public String goal() { return goal; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    /**
     * Null-safe: returns an empty list when unset. An append-only domain type reached through more
     * than one construction path (the full constructor; the bare {@link #DesignDocument()} plus
     * setters, which a couple of Console test fixtures already use; and whatever a future caller
     * adds) must never hand a reader {@code null} instead of "nothing here yet" — the same pattern
     * already used for {@link #brdRequirementIds()}, extended here to {@link #decisions()},
     * {@link #contracts()} and {@link #risks()}.
     */
    public List<Requirement> requirements() { return requirements == null ? List.of() : requirements; }
    public List<Requirement> getRequirements() { return requirements(); }
    public void setRequirements(List<Requirement> requirements) { this.requirements = requirements; }
    /** Null-safe: returns an empty list when unset — see {@link #requirements()}. */
    public List<ArchDecision> decisions() { return decisions == null ? List.of() : decisions; }
    public List<ArchDecision> getDecisions() { return decisions(); }
    public void setDecisions(List<ArchDecision> decisions) { this.decisions = decisions; }
    /**
     * Null-safe: returns an empty list when unset — see {@link #requirements()}.
     *
     * <p>{@code ArchitectClient.designSummary} — the text DESIGN_REVIEW's reviewer, the scoped PLAN
     * prompt and the revision prompt all read — used to null-guard only {@link #requirements()}
     * before iterating this list directly, so a design whose contracts were unset either rendered a
     * summary missing its CONTRACT lines or, worse, threw while building it: synchronously inside
     * {@code plan(...)}, before any model call, with a failure reason then at the mercy of whatever
     * {@code Exception#getMessage()} happened to return (frequently null); or inside the reviewer,
     * where a near-empty summary read back as a correct but unhelpful "the design section is empty".
     * Making the accessor itself never-null closes the gap for every reader, present and future, not
     * just the one caller that happened to be noticed.
     */
    public List<ApiContract> contracts() { return contracts == null ? List.of() : contracts; }
    public List<ApiContract> getContracts() { return contracts(); }
    public void setContracts(List<ApiContract> contracts) { this.contracts = contracts; }
    /** Null-safe: returns an empty list when unset — see {@link #requirements()}. */
    public List<Risk> risks() { return risks == null ? List.of() : risks; }
    public List<Risk> getRisks() { return risks(); }
    public void setRisks(List<Risk> risks) { this.risks = risks; }
    public ReviewVerdict review() { return review; }
    public ReviewVerdict getReview() { return review; }
    public void setReview(ReviewVerdict review) { this.review = review; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    /** Null-safe: returns an empty list when unset (pre-v5 stores load this field as null). */
    public List<UUID> brdRequirementIds() {
        return brdRequirementIds == null ? List.of() : brdRequirementIds;
    }
    public List<UUID> getBrdRequirementIds() { return brdRequirementIds(); }
    public void setBrdRequirementIds(List<UUID> brdRequirementIds) { this.brdRequirementIds = brdRequirementIds; }

    // brdRequirementIds participates in equality: it was previously omitted, so two designs
    // differing only in their BRD links compared equal — which silently breaks anything that dedups
    // by equals(), including the reactive signals the Console renders from. Read through the
    // null-safe accessor so a null field and an empty list compare the same.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DesignDocument that = (DesignDocument) o;
        return Objects.equals(this.id, that.id) && this.revision == that.revision && Objects.equals(this.goal, that.goal) && Objects.equals(this.requirements, that.requirements) && Objects.equals(this.decisions, that.decisions) && Objects.equals(this.contracts, that.contracts) && Objects.equals(this.risks, that.risks) && Objects.equals(this.review, that.review) && Objects.equals(this.createdAt, that.createdAt) && Objects.equals(this.brdRequirementIds(), that.brdRequirementIds());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, revision, goal, requirements, decisions, contracts, risks, review, createdAt, brdRequirementIds());
    }
}

