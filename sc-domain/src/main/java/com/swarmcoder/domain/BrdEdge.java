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
 * A directed, typed edge between two {@link BrdRequirement}s in a project's BRD graph — the
 * relationship carrying its {@link RequirementRelation}. Mirrors {@link TaskEdge} but for the
 * requirement graph and with a relation type. A mutable POJO (persisted in EclipseStore).
 */
@DataModel
public class BrdEdge {
    private UUID from;
    private UUID to;
    private RequirementRelation relation;

    public BrdEdge() {}

    public BrdEdge(UUID from, UUID to, RequirementRelation relation) {
        this.from = from;
        this.to = to;
        this.relation = relation;
    }

    public UUID from() { return from; }
    public UUID getFrom() { return from; }
    public void setFrom(UUID from) { this.from = from; }
    public UUID to() { return to; }
    public UUID getTo() { return to; }
    public void setTo(UUID to) { this.to = to; }
    public RequirementRelation relation() { return relation; }
    public RequirementRelation getRelation() { return relation; }
    public void setRelation(RequirementRelation relation) { this.relation = relation; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrdEdge that = (BrdEdge) o;
        return Objects.equals(this.from, that.from) && Objects.equals(this.to, that.to)
            && Objects.equals(this.relation, that.relation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, relation);
    }
}
