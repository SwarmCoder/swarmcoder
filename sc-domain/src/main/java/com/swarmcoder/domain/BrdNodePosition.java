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
 * Where the operator has put one {@link BrdRequirement} node on the BRD canvas — the top-left corner
 * of its box, in the canvas's own content coordinates (the same space the automatic layout works in,
 * before any pan or zoom).
 *
 * <p>This is deliberately a SEPARATE object rather than two fields on the requirement. A position is
 * not part of what a requirement says: it never belongs in a revision diff, it is not something an
 * extraction agent may author, and a requirement with no entry here is exactly the statement "nobody
 * has placed this one — the automatic layout owns it". Two nullable doubles on the node could not say
 * that third thing without pretending 0,0 meant something.
 *
 * <p>Held on {@link Brd} as a {@code List} (never a {@code Set} — the wire serializer supports
 * {@code List} and {@code Map} only), keyed by requirement id, at most one entry per requirement.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class BrdNodePosition {

    private UUID requirementId;
    /** Content-space x of the node's top-left corner. Never negative — see BrdServiceImpl. */
    private double x;
    /** Content-space y of the node's top-left corner. Never negative — see BrdServiceImpl. */
    private double y;

    public BrdNodePosition() {}

    public BrdNodePosition(UUID requirementId, double x, double y) {
        this.requirementId = requirementId;
        this.x = x;
        this.y = y;
    }

    public UUID requirementId() { return requirementId; }
    public UUID getRequirementId() { return requirementId; }
    public void setRequirementId(UUID requirementId) { this.requirementId = requirementId; }
    public double x() { return x; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double y() { return y; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    // Compared through the accessors, not the raw fields, for the reason spelled out on
    // BrdRequirement#hashCode: the wire serializer reads a field through whichever accessor it finds
    // first, so equality must be defined over exactly what goes on the wire — otherwise a
    // round-tripped position is unequal to the one that was sent and signal dedup silently breaks.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrdNodePosition that = (BrdNodePosition) o;
        return Objects.equals(this.requirementId(), that.requirementId())
            && Double.compare(this.x(), that.x()) == 0
            && Double.compare(this.y(), that.y()) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(requirementId(), x(), y());
    }

    @Override
    public String toString() {
        return "BrdNodePosition[" + requirementId + " @ " + x + "," + y + "]";
    }
}
