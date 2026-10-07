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

/** One agent session tile on the Swarm Board. */
@DataModel
public class SessionSummaryDto implements BinaryPackable {

    private String sessionId;
    private String runId;
    private String taskId;
    private int workerIndex;
    private String role;
    private String model;
    private String outcome;      // RUNNING | COMPLETED | KILLED | FAILED
    private String killReason;   // "" when none
    private int turns;
    private long tokens;
    private long openedAtMillis;
    private long closedAtMillis; // 0 while the session is still open

    public SessionSummaryDto() { }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public int getWorkerIndex() { return workerIndex; }
    public void setWorkerIndex(int workerIndex) { this.workerIndex = workerIndex; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getKillReason() { return killReason; }
    public void setKillReason(String killReason) { this.killReason = killReason; }
    public int getTurns() { return turns; }
    public void setTurns(int turns) { this.turns = turns; }
    public long getTokens() { return tokens; }
    public void setTokens(long tokens) { this.tokens = tokens; }
    public long getOpenedAtMillis() { return openedAtMillis; }
    public void setOpenedAtMillis(long openedAtMillis) { this.openedAtMillis = openedAtMillis; }
    public long getClosedAtMillis() { return closedAtMillis; }
    public void setClosedAtMillis(long closedAtMillis) { this.closedAtMillis = closedAtMillis; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, sessionId);
        BinarySerializer.writeString(buffer, runId);
        BinarySerializer.writeString(buffer, taskId);
        BinarySerializer.writeValue(buffer, workerIndex, mapper);
        BinarySerializer.writeString(buffer, role);
        BinarySerializer.writeString(buffer, model);
        BinarySerializer.writeString(buffer, outcome);
        BinarySerializer.writeString(buffer, killReason);
        BinarySerializer.writeValue(buffer, turns, mapper);
        BinarySerializer.writeValue(buffer, tokens, mapper);
        BinarySerializer.writeValue(buffer, openedAtMillis, mapper);
        BinarySerializer.writeValue(buffer, closedAtMillis, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.sessionId = BinarySerializer.readString(buffer);
        this.runId = BinarySerializer.readString(buffer);
        this.taskId = BinarySerializer.readString(buffer);
        this.workerIndex = readInt(buffer, mapper);
        this.role = BinarySerializer.readString(buffer);
        this.model = BinarySerializer.readString(buffer);
        this.outcome = BinarySerializer.readString(buffer);
        this.killReason = BinarySerializer.readString(buffer);
        this.turns = readInt(buffer, mapper);
        this.tokens = readLong(buffer, mapper);
        this.openedAtMillis = readLong(buffer, mapper);
        this.closedAtMillis = readLong(buffer, mapper);
    }

    static int readInt(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        Object value = BinarySerializer.readValue(buffer, mapper);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    static long readLong(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        Object value = BinarySerializer.readValue(buffer, mapper);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}


