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
 * A scheduled slice of the requirement graph — not a second description of it. A story carries no
 * requirement content of its own: the wording lives on {@link BrdRequirement}s and their acceptance
 * criteria, and the story only says which of that is being delivered, when, and by whom. That is
 * why there is nothing here to keep in sync with the BRD.
 *
 * <p>{@code criterionIds} is the exact slice the story delivers — the criteria that must pass for
 * it to be done. It is empty for {@link StoryKind#ENABLER} stories, which satisfy no criterion
 * directly and instead link via {@code requirementIds} to the requirements they unblock.
 *
 * <p>{@code iterationId} points at the {@link Iteration} the story is batched into and
 * {@code order} is its rank within that batch. {@code runIds} accumulates every {@link Run} that
 * worked the story, and {@code deliveredCommit} / {@code integrationCommit} / {@code prNumber} /
 * {@code prUrl} record where the work landed.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class Story {
    private UUID id;
    private UUID projectId;
    private String key;              // stable human label (S1, S2, …)
    private StoryKind kind;
    private String title;
    private String narrative;
    private StoryState state;
    private List<UUID> requirementIds;
    private List<UUID> criterionIds;
    private UUID iterationId;
    private int order;               // rank within the iteration
    /**
     * The stories this one BUILDS ON — it may not start until every one of them is delivered.
     *
     * <p>This is the field whose absence caused the failure of 2026-08-28. Nine stories were planned
     * from one requirements document and started together; the first was building the domain model
     * and the other eight each invented their own version of a model that did not exist yet. Nothing
     * in the system could express "this one comes after that one": {@code order} is a rank inside an
     * iteration and no scheduler ever read it, and a story's run branched from the repository's HEAD,
     * so a later story could not SEE an earlier story's work even if it had waited for it.
     *
     * <p>Two things follow from an edge here, and they are one mechanism, not two. The story does not
     * start until its predecessors are accepted — see {@code StoryGraph} — and acceptance is what puts
     * a predecessor's code on the project's base branch, which is what every run branches from. So
     * "waited for it" and "can see it" are the same fact.
     *
     * <p>Never null-checked at the call site: read it through {@link #dependsOn()}.
     */
    private List<UUID> dependsOnStoryIds;
    /**
     * Dependencies the SYSTEM worked out, not the planner — kept apart from the declared ones on
     * purpose.
     *
     * <p>The planner declares dependencies before any code exists, from requirements alone, so it is
     * guessing and will sometimes be wrong. When a build then fails because something it needed was
     * not there — every worker failing on the same unresolved symbol is a mechanical fact, not an
     * opinion — the missing edge is added here and the story goes back to waiting instead of dying.
     *
     * <p>Two lists rather than one because an inferred edge is a weaker claim than a declared one and
     * the operator must be able to see which is which and drop the machine's guess. This is the same
     * rule the project already applies to guidelines the machine wrote and tests the machine
     * proposed: an agent never silently widens scope, and anything it works out for itself is
     * recorded as its own.
     */
    private List<UUID> discoveredDependsOnStoryIds;
    /**
     * How many times this story has been sent back to wait because a build discovered it needed
     * something that was not there yet. Capped, so a story that keeps failing for the same reason
     * eventually stops and says so rather than consuming the whole night.
     */
    private int dependencyRetries;
    /**
     * Why this story is waiting or stopped, in the operator's own words — written when the system
     * works something out that the operator did not ask for, so the morning does not begin with a
     * state and no reason. Null when there is nothing to explain.
     */
    private String waitingReason;
    /**
     * How many workers each of this story's tasks gets, or null to inherit.
     *
     * <p>The most specific of the three sizing layers — story, then project, then the global
     * settings file (see {@link SwarmSizing}). Null is the normal case and means "whatever the
     * project says", which in turn usually means "whatever the settings file says". A story that
     * is one obvious line of work does not need four attempts; a hard one may want eight, and this
     * is where the operator says so.
     *
     * <p>On the story rather than in settings because it is a fact about THIS piece of work, and
     * because a number hidden on a settings screen is a number nobody remembers is set. The
     * pipeline board shows it on the story's own panel and lets the operator change it there.
     */
    private Integer workersPerTask;
    /**
     * How many tool turns each worker on this story gets, or null to inherit.
     *
     * <p>The story end of the same three layers {@link #workersPerTask} uses — story, then the
     * project's {@code .swarmcoder/project.yaml}, then the settings file's {@code budgets:} block
     * (see {@link TurnAllowance}). Null is the normal case.
     *
     * <p>It is here for the story whose work is genuinely a long grind against a slow build: the
     * default is a backstop sized for ordinary work, and this is where the operator says that this
     * particular piece is not ordinary.
     */
    private Integer maxToolTurns;
    private StoryOrigin origin;
    private UUID originRunId;        // set when origin is DISCOVERED
    private String rationale;
    private String author;
    private List<UUID> runIds;
    private String deliveredCommit;
    private String integrationCommit;
    private Integer prNumber;
    private String prUrl;
    /**
     * Who said this story was finished: {@code "human"} when a person pressed Accept, or
     * {@code "unattended"} when the machine accepted it overnight because every check it claimed
     * genuinely passed. Null on a story nobody has accepted.
     *
     * <p>Recorded because the two are not the same claim and the record must not blur them. The
     * project already carries provenance for exactly this reason — machine-authored guidelines are
     * marked apart from human ones, and a test the machine proposed is marked apart from one the
     * operator wrote. A story accepted while the operator slept is the same kind of fact and gets the
     * same treatment: the board says so on the card, and a person can disagree with it in the
     * morning.
     */
    private String acceptedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Story() {}

    public Story(UUID id, UUID projectId, String key, StoryKind kind, String title,
                 String narrative, StoryState state, List<UUID> requirementIds,
                 List<UUID> criterionIds, UUID iterationId, int order, StoryOrigin origin,
                 UUID originRunId, String rationale, String author, List<UUID> runIds,
                 String deliveredCommit, String integrationCommit, Integer prNumber, String prUrl,
                 Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.key = key;
        this.kind = kind;
        this.title = title;
        this.narrative = narrative;
        this.state = state;
        this.requirementIds = requirementIds;
        this.criterionIds = criterionIds;
        this.iterationId = iterationId;
        this.order = order;
        this.origin = origin;
        this.originRunId = originRunId;
        this.rationale = rationale;
        this.author = author;
        this.runIds = runIds;
        this.deliveredCommit = deliveredCommit;
        this.integrationCommit = integrationCommit;
        this.prNumber = prNumber;
        this.prUrl = prUrl;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String key() { return key; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public StoryKind kind() { return kind; }
    public StoryKind getKind() { return kind; }
    public void setKind(StoryKind kind) { this.kind = kind; }
    public String title() { return title; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String narrative() { return narrative; }
    public String getNarrative() { return narrative; }
    public void setNarrative(String narrative) { this.narrative = narrative; }
    public StoryState state() { return state; }
    public StoryState getState() { return state; }
    public void setState(StoryState state) { this.state = state; }
    /** Null-safe: returns an empty list when unset (pre-existing stores load this field as null). */
    public List<UUID> requirementIds() {
        return requirementIds == null ? List.of() : requirementIds;
    }
    public List<UUID> getRequirementIds() { return requirementIds(); }
    public void setRequirementIds(List<UUID> requirementIds) { this.requirementIds = requirementIds; }
    /** Null-safe: returns an empty list when unset (pre-existing stores load this field as null). */
    public List<UUID> criterionIds() {
        return criterionIds == null ? List.of() : criterionIds;
    }
    public List<UUID> getCriterionIds() { return criterionIds(); }
    public void setCriterionIds(List<UUID> criterionIds) { this.criterionIds = criterionIds; }
    public UUID iterationId() { return iterationId; }
    public UUID getIterationId() { return iterationId; }
    public void setIterationId(UUID iterationId) { this.iterationId = iterationId; }
    public int order() { return order; }
    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }
    /**
     * The stories this one builds on. Null-safe: returns an empty list when unset, which is what
     * every story written before dependencies existed looks like.
     *
     * <p>Not in the all-args constructor on purpose. Every existing call site builds a story with a
     * positional argument list twenty-two entries long, and threading a twenty-third through them
     * would have meant editing code that has nothing to do with dependencies — the same shape of
     * change that lost a run its project once already.
     */
    public List<UUID> dependsOn() {
        if (discoveredDependsOnStoryIds == null || discoveredDependsOnStoryIds.isEmpty()) {
            return declaredDependsOn();
        }
        List<UUID> all = new java.util.ArrayList<>(declaredDependsOn());
        for (UUID id : discoveredDependsOnStoryIds) {
            if (!all.contains(id)) {
                all.add(id);
            }
        }
        return List.copyOf(all);
    }
    /** Only the edges the planner declared — what a person agreed to when they applied the plan. */
    public List<UUID> declaredDependsOn() {
        return dependsOnStoryIds == null ? List.of() : dependsOnStoryIds;
    }
    /** Only the edges a failed build revealed. Marked apart because an inference is not a decision. */
    public List<UUID> discoveredDependsOn() {
        return discoveredDependsOnStoryIds == null ? List.of() : discoveredDependsOnStoryIds;
    }
    public List<UUID> getDependsOnStoryIds() { return declaredDependsOn(); }
    public void setDependsOnStoryIds(List<UUID> dependsOnStoryIds) {
        this.dependsOnStoryIds = dependsOnStoryIds;
    }
    public List<UUID> getDiscoveredDependsOnStoryIds() { return discoveredDependsOn(); }
    public void setDiscoveredDependsOnStoryIds(List<UUID> discoveredDependsOnStoryIds) {
        this.discoveredDependsOnStoryIds = discoveredDependsOnStoryIds;
    }
    public int dependencyRetries() { return dependencyRetries; }
    public int getDependencyRetries() { return dependencyRetries; }
    public void setDependencyRetries(int dependencyRetries) {
        this.dependencyRetries = dependencyRetries;
    }
    public String waitingReason() { return waitingReason; }
    public String getWaitingReason() { return waitingReason; }
    public void setWaitingReason(String waitingReason) { this.waitingReason = waitingReason; }
    /** How many workers each of this story's tasks gets, or null to inherit the project's number. */
    public Integer workersPerTask() { return workersPerTask; }
    public Integer getWorkersPerTask() { return workersPerTask; }
    public void setWorkersPerTask(Integer workersPerTask) { this.workersPerTask = workersPerTask; }
    /** How many tool turns each worker on this story gets, or null to inherit the project's number. */
    public Integer maxToolTurns() { return maxToolTurns; }
    public Integer getMaxToolTurns() { return maxToolTurns; }
    public void setMaxToolTurns(Integer maxToolTurns) { this.maxToolTurns = maxToolTurns; }
    public StoryOrigin origin() { return origin; }
    public StoryOrigin getOrigin() { return origin; }
    public void setOrigin(StoryOrigin origin) { this.origin = origin; }
    public UUID originRunId() { return originRunId; }
    public UUID getOriginRunId() { return originRunId; }
    public void setOriginRunId(UUID originRunId) { this.originRunId = originRunId; }
    public String rationale() { return rationale; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public String author() { return author; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    /** Null-safe: returns an empty list when unset (pre-existing stores load this field as null). */
    public List<UUID> runIds() {
        return runIds == null ? List.of() : runIds;
    }
    public List<UUID> getRunIds() { return runIds(); }
    public void setRunIds(List<UUID> runIds) { this.runIds = runIds; }
    public String deliveredCommit() { return deliveredCommit; }
    public String getDeliveredCommit() { return deliveredCommit; }
    public void setDeliveredCommit(String deliveredCommit) { this.deliveredCommit = deliveredCommit; }
    public String integrationCommit() { return integrationCommit; }
    public String getIntegrationCommit() { return integrationCommit; }
    public void setIntegrationCommit(String integrationCommit) { this.integrationCommit = integrationCommit; }
    public Integer prNumber() { return prNumber; }
    public Integer getPrNumber() { return prNumber; }
    public void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }
    public String prUrl() { return prUrl; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    /** {@code "human"}, {@code "unattended"}, or null when nobody has accepted this story. */
    public String acceptedBy() { return acceptedBy; }
    public String getAcceptedBy() { return acceptedBy; }
    public void setAcceptedBy(String acceptedBy) { this.acceptedBy = acceptedBy; }
    /** True when this story was accepted by the machine rather than by a person. */
    public boolean acceptedUnattended() { return "unattended".equals(acceptedBy); }
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
        Story that = (Story) o;
        return this.order == that.order && Objects.equals(this.id, that.id)
            && Objects.equals(this.projectId, that.projectId) && Objects.equals(this.key, that.key)
            && Objects.equals(this.kind, that.kind) && Objects.equals(this.title, that.title)
            && Objects.equals(this.narrative, that.narrative) && Objects.equals(this.state, that.state)
            && Objects.equals(this.requirementIds(), that.requirementIds())
            && Objects.equals(this.criterionIds(), that.criterionIds())
            && Objects.equals(this.iterationId, that.iterationId)
            && Objects.equals(this.origin, that.origin)
            && Objects.equals(this.originRunId, that.originRunId)
            && Objects.equals(this.rationale, that.rationale) && Objects.equals(this.author, that.author)
            && Objects.equals(this.runIds(), that.runIds())
            && Objects.equals(this.deliveredCommit, that.deliveredCommit)
            && Objects.equals(this.integrationCommit, that.integrationCommit)
            && Objects.equals(this.prNumber, that.prNumber) && Objects.equals(this.prUrl, that.prUrl)
            && Objects.equals(this.declaredDependsOn(), that.declaredDependsOn())
            && Objects.equals(this.discoveredDependsOn(), that.discoveredDependsOn())
            && this.dependencyRetries == that.dependencyRetries
            && Objects.equals(this.waitingReason, that.waitingReason)
            && Objects.equals(this.workersPerTask, that.workersPerTask)
            && Objects.equals(this.maxToolTurns, that.maxToolTurns)
            && Objects.equals(this.acceptedBy, that.acceptedBy)
            && Objects.equals(this.createdAt, that.createdAt)
            && Objects.equals(this.updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        // The three id lists compare through their NULL-SAFE accessors: an unset list IS empty, and
        // the wire serializer reads through the same accessors, so comparing raw fields would make a
        // round-tripped story unequal to the one that was sent — silently breaking signal dedup.
        return Objects.hash(id, projectId, key, kind, title, narrative, state, requirementIds(),
            criterionIds(), iterationId, order, origin, originRunId, rationale, author, runIds(),
            deliveredCommit, integrationCommit, prNumber, prUrl, declaredDependsOn(),
            discoveredDependsOn(), dependencyRetries, waitingReason, workersPerTask, maxToolTurns,
            acceptedBy, createdAt, updatedAt);
    }
}
