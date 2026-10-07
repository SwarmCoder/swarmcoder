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

import com.zeroz4j.api.DataModel;
import com.zeroz4j.api.BinaryPackable;
import com.zeroz4j.api.BinarySerializer;

import java.nio.ByteBuffer;

/**
 * One requirement node in the run's plan view: the design's requirement plus the ids of the
 * tasks that cover it (traceability). {@code handle} is the stable R1/R2… label the planner
 * used. Nested inside {@link RunGraphDto}; requirements are static for a run so they ride the
 * same push as task-state changes.
 */
@DataModel
public class GraphRequirementDto implements BinaryPackable {

    private String requirementId;
    private String handle;       // R1, R2, …
    private String text;
    private String priority;     // LOW | MEDIUM | HIGH | CRITICAL
    private String coveredByCsv; // CSV of task ids that satisfy this requirement ("" = uncovered)

    public GraphRequirementDto() { }

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getCoveredByCsv() { return coveredByCsv; }
    public void setCoveredByCsv(String coveredByCsv) { this.coveredByCsv = coveredByCsv; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, requirementId);
        BinarySerializer.writeString(buffer, handle);
        BinarySerializer.writeString(buffer, text);
        BinarySerializer.writeString(buffer, priority);
        BinarySerializer.writeString(buffer, coveredByCsv);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.requirementId = BinarySerializer.readString(buffer);
        this.handle = BinarySerializer.readString(buffer);
        this.text = BinarySerializer.readString(buffer);
        this.priority = BinarySerializer.readString(buffer);
        this.coveredByCsv = BinarySerializer.readString(buffer);
    }
}


