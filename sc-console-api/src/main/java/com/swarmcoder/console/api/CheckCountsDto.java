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
package com.swarmcoder.console.api;

import com.zeroz4j.api.BinaryPackable;
import com.zeroz4j.api.BinarySerializer;
import com.zeroz4j.api.DataModel;

import java.nio.ByteBuffer;

/**
 * Check counts on the wire — the projection of the domain {@code CheckCounts}.
 *
 * <p>A separate type rather than putting the domain value on the wire directly (the pattern most of
 * this API follows) because the serializer needs a no-arg constructor and mutable fields to read into,
 * and {@code CheckCounts} is deliberately immutable. Making it mutable to save a class would trade the
 * property that matters for the one that does not.
 *
 * <p>Appears twice on every row: what verifies the requirement itself, and what verifies everything
 * beneath it. They are never summed into one figure — see {@link RequirementRowDto}.
 */
@DataModel
public class CheckCountsDto implements BinaryPackable {

    private int passing;
    private int failing;
    private int unverified;
    private int stale;
    private int proposed;

    public CheckCountsDto() { }

    public int getPassing() { return passing; }
    public void setPassing(int passing) { this.passing = passing; }
    public int getFailing() { return failing; }
    public void setFailing(int failing) { this.failing = failing; }
    public int getUnverified() { return unverified; }
    public void setUnverified(int unverified) { this.unverified = unverified; }
    public int getStale() { return stale; }
    public void setStale(int stale) { this.stale = stale; }
    public int getProposed() { return proposed; }
    public void setProposed(int proposed) { this.proposed = proposed; }

    /** Accepted checks in any state — the denominator the operator reads as "of 20". */
    public int gating() {
        return passing + failing + unverified + stale;
    }

    /** True when there is something to prove and all of it is proven against the current wording. */
    public boolean allGatingPassing() {
        return gating() > 0 && passing == gating();
    }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer,
                              com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeValue(buffer, passing, mapper);
        BinarySerializer.writeValue(buffer, failing, mapper);
        BinarySerializer.writeValue(buffer, unverified, mapper);
        BinarySerializer.writeValue(buffer, stale, mapper);
        BinarySerializer.writeValue(buffer, proposed, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.passing = SessionSummaryDto.readInt(buffer, mapper);
        this.failing = SessionSummaryDto.readInt(buffer, mapper);
        this.unverified = SessionSummaryDto.readInt(buffer, mapper);
        this.stale = SessionSummaryDto.readInt(buffer, mapper);
        this.proposed = SessionSummaryDto.readInt(buffer, mapper);
    }
}
