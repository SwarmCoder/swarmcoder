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
 * A project's living Business Requirements Document (author decision 2026-07-24): a persisted
 * network graph of {@link BrdRequirement} nodes and typed {@link BrdEdge} relationships, exactly
 * one per {@link Project}, editable in the Console and kept in sync across runs. Unlike the
 * per-session {@link DesignDocument} (which the Architect regenerates each run and which links
 * back to the requirement ids it addresses), the BRD is the durable, human-owned source of the
 * project's requirements.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}. {@code revision} bumps on every edit so clients can detect staleness.
 */
@DataModel
public class Brd {
    private UUID id;
    private UUID projectId;
    private long revision;
    private String title;
    private List<BrdRequirement> requirements;
    private List<BrdEdge> edges;
    /**
     * Where the operator has placed nodes by hand on the canvas — at most one entry per requirement,
     * and only for requirements somebody actually moved. Absence is meaningful: a requirement with no
     * entry is one the automatic layout still owns. Added via a setter, not the constructor, so
     * existing callers and older stores are unaffected (they load as null — read through the
     * null-safe {@link #nodePositions()}).
     */
    private List<BrdNodePosition> nodePositions;
    private Instant createdAt;
    private Instant updatedAt;

    public Brd() {}

    public Brd(UUID id, UUID projectId, long revision, String title,
               List<BrdRequirement> requirements, List<BrdEdge> edges,
               Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.revision = revision;
        this.title = title;
        this.requirements = requirements;
        this.edges = edges;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public long revision() { return revision; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public String title() { return title; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<BrdRequirement> requirements() { return requirements; }
    public List<BrdRequirement> getRequirements() { return requirements; }
    public void setRequirements(List<BrdRequirement> requirements) { this.requirements = requirements; }
    public List<BrdEdge> edges() { return edges; }
    public List<BrdEdge> getEdges() { return edges; }
    public void setEdges(List<BrdEdge> edges) { this.edges = edges; }
    /** Null-safe read (an unset list IS "nothing placed by hand"); use {@link #setNodePositions}. */
    public List<BrdNodePosition> nodePositions() { return nodePositions == null ? List.of() : nodePositions; }
    public List<BrdNodePosition> getNodePositions() { return nodePositions; }
    public void setNodePositions(List<BrdNodePosition> nodePositions) { this.nodePositions = nodePositions; }
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
        Brd that = (Brd) o;
        return this.revision == that.revision && Objects.equals(this.id, that.id)
            && Objects.equals(this.projectId, that.projectId) && Objects.equals(this.title, that.title)
            && Objects.equals(this.requirements, that.requirements) && Objects.equals(this.edges, that.edges)
            && Objects.equals(this.nodePositions(), that.nodePositions())
            && Objects.equals(this.createdAt, that.createdAt) && Objects.equals(this.updatedAt, that.updatedAt);
    }

    // nodePositions compares through its NULL-SAFE accessor, not the raw field: an unset list IS an
    // empty one, the wire serializer reads through that same accessor, and comparing the raw field
    // would therefore make a round-tripped BRD unequal to the one that was sent — which silently
    // breaks signal dedup (see BrdRequirement#hashCode).
    @Override
    public int hashCode() {
        return Objects.hash(id, projectId, revision, title, requirements, edges, nodePositions(),
            createdAt, updatedAt);
    }
}
