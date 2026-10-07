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
 * Aggregates over archived candidates and sessions — the spec §8.9 eval dataset made visible.
 * Table data is carried as tab/newline-delimited strings to stay within the manual binary
 * protocol without nested-list serialization; the UI splits them into tables.
 */
@DataModel
public class InsightsDto implements BinaryPackable {

    private int totalRuns;
    private int totalCandidates;
    private int selectedCandidates;
    private int totalSessions;
    private long totalTokens;

    /** Rows "family\tdispatched\tsurvived\tselected", newline-separated. */
    private String familyRows;
    /** Rows "killReason\tcount", newline-separated. */
    private String killReasonRows;
    /** One row per archived candidate: "temperature\tsurvived(0/1)\tfamily", newline-separated. */
    private String scatterRows;

    public InsightsDto() { }

    public int getTotalRuns() { return totalRuns; }
    public void setTotalRuns(int v) { this.totalRuns = v; }
    public int getTotalCandidates() { return totalCandidates; }
    public void setTotalCandidates(int v) { this.totalCandidates = v; }
    public int getSelectedCandidates() { return selectedCandidates; }
    public void setSelectedCandidates(int v) { this.selectedCandidates = v; }
    public int getTotalSessions() { return totalSessions; }
    public void setTotalSessions(int v) { this.totalSessions = v; }
    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long v) { this.totalTokens = v; }
    public String getFamilyRows() { return familyRows; }
    public void setFamilyRows(String v) { this.familyRows = v; }
    public String getKillReasonRows() { return killReasonRows; }
    public void setKillReasonRows(String v) { this.killReasonRows = v; }
    public String getScatterRows() { return scatterRows; }
    public void setScatterRows(String v) { this.scatterRows = v; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeValue(buffer, totalRuns, mapper);
        BinarySerializer.writeValue(buffer, totalCandidates, mapper);
        BinarySerializer.writeValue(buffer, selectedCandidates, mapper);
        BinarySerializer.writeValue(buffer, totalSessions, mapper);
        BinarySerializer.writeValue(buffer, totalTokens, mapper);
        BinarySerializer.writeString(buffer, familyRows);
        BinarySerializer.writeString(buffer, killReasonRows);
        BinarySerializer.writeString(buffer, scatterRows);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.totalRuns = SessionSummaryDto.readInt(buffer, mapper);
        this.totalCandidates = SessionSummaryDto.readInt(buffer, mapper);
        this.selectedCandidates = SessionSummaryDto.readInt(buffer, mapper);
        this.totalSessions = SessionSummaryDto.readInt(buffer, mapper);
        this.totalTokens = SessionSummaryDto.readLong(buffer, mapper);
        this.familyRows = BinarySerializer.readString(buffer);
        this.killReasonRows = BinarySerializer.readString(buffer);
        this.scatterRows = BinarySerializer.readString(buffer);
    }
}


