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
 * Live system health for the status strip (design §8): Spark reachability, Docker sandbox
 * availability, in-flight worker/run load, and cloud-budget burn. Polled by the strip; cheap
 * to assemble (store + trace hub counters + one cached Spark ping).
 */
@DataModel
public class HealthDto implements BinaryPackable {

    private String sparkStatus;   // up | down | unknown
    private String dockerStatus;  // on | off | unknown
    private int activeWorkers;
    private int activeRuns;
    private int totalRuns;
    private int sessions;
    private int pendingApprovals;
    private long budgetUsed;
    private long budgetMax;        // 0 = uncapped
    /** Which endpoint the reachability check actually probed — the answer to "down compared to what?". */
    private String sparkUrl;
    /** Comma-separated model ids the endpoint reports, when it answered. */
    private String sparkModels;
    /** Why the probe failed, when it did. Null while it is up. */
    private String sparkDetail;

    public HealthDto() { }

    public String getSparkStatus() { return sparkStatus; }
    public void setSparkStatus(String v) { this.sparkStatus = v; }
    public String getDockerStatus() { return dockerStatus; }
    public void setDockerStatus(String v) { this.dockerStatus = v; }
    public int getActiveWorkers() { return activeWorkers; }
    public void setActiveWorkers(int v) { this.activeWorkers = v; }
    public int getActiveRuns() { return activeRuns; }
    public void setActiveRuns(int v) { this.activeRuns = v; }
    public int getTotalRuns() { return totalRuns; }
    public void setTotalRuns(int v) { this.totalRuns = v; }
    public int getSessions() { return sessions; }
    public void setSessions(int v) { this.sessions = v; }
    public int getPendingApprovals() { return pendingApprovals; }
    public void setPendingApprovals(int v) { this.pendingApprovals = v; }
    public long getBudgetUsed() { return budgetUsed; }
    public void setBudgetUsed(long v) { this.budgetUsed = v; }
    public long getBudgetMax() { return budgetMax; }
    public void setBudgetMax(long v) { this.budgetMax = v; }
    public String getSparkUrl() { return sparkUrl; }
    public void setSparkUrl(String v) { this.sparkUrl = v; }
    public String getSparkModels() { return sparkModels; }
    public void setSparkModels(String v) { this.sparkModels = v; }
    public String getSparkDetail() { return sparkDetail; }
    public void setSparkDetail(String v) { this.sparkDetail = v; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, sparkStatus);
        BinarySerializer.writeString(buffer, dockerStatus);
        BinarySerializer.writeValue(buffer, activeWorkers, mapper);
        BinarySerializer.writeValue(buffer, activeRuns, mapper);
        BinarySerializer.writeValue(buffer, totalRuns, mapper);
        BinarySerializer.writeValue(buffer, sessions, mapper);
        BinarySerializer.writeValue(buffer, pendingApprovals, mapper);
        BinarySerializer.writeValue(buffer, budgetUsed, mapper);
        BinarySerializer.writeValue(buffer, budgetMax, mapper);
        // Appended, and appended in BOTH methods in the same order: this pair is hand-rolled, so
        // nothing but care keeps the reader aligned with the writer.
        BinarySerializer.writeString(buffer, sparkUrl);
        BinarySerializer.writeString(buffer, sparkModels);
        BinarySerializer.writeString(buffer, sparkDetail);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.sparkStatus = BinarySerializer.readString(buffer);
        this.dockerStatus = BinarySerializer.readString(buffer);
        this.activeWorkers = SessionSummaryDto.readInt(buffer, mapper);
        this.activeRuns = SessionSummaryDto.readInt(buffer, mapper);
        this.totalRuns = SessionSummaryDto.readInt(buffer, mapper);
        this.sessions = SessionSummaryDto.readInt(buffer, mapper);
        this.pendingApprovals = SessionSummaryDto.readInt(buffer, mapper);
        this.budgetUsed = SessionSummaryDto.readLong(buffer, mapper);
        this.budgetMax = SessionSummaryDto.readLong(buffer, mapper);
        this.sparkUrl = BinarySerializer.readString(buffer);
        this.sparkModels = BinarySerializer.readString(buffer);
        this.sparkDetail = BinarySerializer.readString(buffer);
    }
}


