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
 * A named, ordered batch of {@link Story}s with a definition of done — {@code goal} states what
 * finishing the batch is supposed to achieve, and {@code seq} orders iterations against each other.
 *
 * <p>Deliberately not a sprint: there are no dates, no story points and no velocity. Cost is
 * measured from telemetry after the fact — never estimated up front — so there is nothing here to
 * plan against a calendar.
 *
 * <p>Stories point at an iteration (via {@code Story.iterationId}); the iteration holds no story
 * list of its own, so there is nothing to keep in sync. Membership is queried, not stored twice.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class Iteration {
    private UUID id;
    private UUID projectId;
    private String name;
    private String goal;            // the definition of done for the batch
    private int seq;                // ordering across iterations
    private IterationState state;
    private Instant createdAt;
    private Instant closedAt;

    public Iteration() {}

    public Iteration(UUID id, UUID projectId, String name, String goal, int seq,
                     IterationState state, Instant createdAt, Instant closedAt) {
        this.id = id;
        this.projectId = projectId;
        this.name = name;
        this.goal = goal;
        this.seq = seq;
        this.state = state;
        this.createdAt = createdAt;
        this.closedAt = closedAt;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String name() { return name; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String goal() { return goal; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public int seq() { return seq; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public IterationState state() { return state; }
    public IterationState getState() { return state; }
    public void setState(IterationState state) { this.state = state; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant closedAt() { return closedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Iteration that = (Iteration) o;
        return this.seq == that.seq && Objects.equals(this.id, that.id)
            && Objects.equals(this.projectId, that.projectId) && Objects.equals(this.name, that.name)
            && Objects.equals(this.goal, that.goal) && Objects.equals(this.state, that.state)
            && Objects.equals(this.createdAt, that.createdAt)
            && Objects.equals(this.closedAt, that.closedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, projectId, name, goal, seq, state, createdAt, closedAt);
    }
}
