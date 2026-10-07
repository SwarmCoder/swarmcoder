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

@DataModel
public class RunSummaryDto implements BinaryPackable {

    private String runId;
    private String kind;
    private String state;
    private String goal;
    private long startedAtMillis;
    /**
     * When a live process last persisted this run, or 0 if it never has.
     *
     * <p>The run's STATE says nothing about whether anything is driving it — one parked at
     * EXECUTING with a thread on it and one whose process died look identical. This is the only
     * field that tells them apart, and it is why a story could sit reading "building now" for
     * fifteen hours with no way for the screen to know better.
     */
    private long heartbeatAtMillis;
    /**
     * When this run started waiting for a model endpoint to come back, or 0 when it is not waiting.
     *
     * <p>Carried on the wire because "paused — resuming by itself" is a different thing to tell the
     * operator than either "building" or "stopped", and the client cannot work it out: a paused run
     * has a live heartbeat and an unchanged state, so it is indistinguishable from a working one
     * without this. {@code BuildState} is the only code that reads it.
     */
    private long pausedSinceMillis;
    /** The sentence to show for the pause, composed server-side by {@code OutagePause}. */
    private String pauseReason;
    /**
     * When a workflow stage stopped this run and raised a question for the operator, or 0 when it
     * is not parked.
     *
     * <p>Separate from {@link #pausedSinceMillis}: a pause is the engine still driving the run,
     * waiting for an endpoint that comes back on its own; a park is a stage that ran to completion
     * and decided it could not go on. Without this the client had no way to tell a genuinely stopped
     * run from a working one until a heartbeat aged out, minutes after the workflow had already
     * parked and raised a question.
     */
    private long parkedAtMillis;
    /** The sentence to show for the park — the same brief its BLOCKED_TASK question carries. */
    private String parkReason;
    /**
     * Tokens this run's agent sessions have consumed so far, or 0 when none have reported.
     *
     * <p>What a building story has COST is one of the three things the pipeline board's building
     * cards must show (UX v3 §3.1), alongside the phase and the elapsed time. It is summed
     * server-side from the run's session records: the client holds no sessions, and having it add up
     * a number the server already knows would be a second derivation of it.
     */
    private long tokensUsed;

    public RunSummaryDto() { }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public long getStartedAtMillis() { return startedAtMillis; }
    public void setStartedAtMillis(long startedAtMillis) { this.startedAtMillis = startedAtMillis; }
    public long getHeartbeatAtMillis() { return heartbeatAtMillis; }
    public void setHeartbeatAtMillis(long v) { this.heartbeatAtMillis = v; }
    public long getPausedSinceMillis() { return pausedSinceMillis; }
    public void setPausedSinceMillis(long v) { this.pausedSinceMillis = v; }
    public String getPauseReason() { return pauseReason; }
    public void setPauseReason(String pauseReason) { this.pauseReason = pauseReason; }
    public long getParkedAtMillis() { return parkedAtMillis; }
    public void setParkedAtMillis(long v) { this.parkedAtMillis = v; }
    public String getParkReason() { return parkReason; }
    public void setParkReason(String parkReason) { this.parkReason = parkReason; }
    public long getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(long v) { this.tokensUsed = v; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, runId);
        BinarySerializer.writeString(buffer, kind);
        BinarySerializer.writeString(buffer, state);
        BinarySerializer.writeString(buffer, goal);
        BinarySerializer.writeValue(buffer, startedAtMillis, mapper);
        // Appended in BOTH methods in the same order — this pair is hand-rolled.
        BinarySerializer.writeValue(buffer, heartbeatAtMillis, mapper);
        BinarySerializer.writeValue(buffer, pausedSinceMillis, mapper);
        BinarySerializer.writeString(buffer, pauseReason);
        BinarySerializer.writeValue(buffer, tokensUsed, mapper);
        // Appended at the end, same discipline as the pause pair above: both methods, same order.
        BinarySerializer.writeValue(buffer, parkedAtMillis, mapper);
        BinarySerializer.writeString(buffer, parkReason);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.runId = BinarySerializer.readString(buffer);
        this.kind = BinarySerializer.readString(buffer);
        this.state = BinarySerializer.readString(buffer);
        this.goal = BinarySerializer.readString(buffer);
        this.startedAtMillis = SessionSummaryDto.readLong(buffer, mapper);
        this.heartbeatAtMillis = SessionSummaryDto.readLong(buffer, mapper);
        this.pausedSinceMillis = SessionSummaryDto.readLong(buffer, mapper);
        this.pauseReason = BinarySerializer.readString(buffer);
        this.tokensUsed = SessionSummaryDto.readLong(buffer, mapper);
        this.parkedAtMillis = SessionSummaryDto.readLong(buffer, mapper);
        this.parkReason = BinarySerializer.readString(buffer);
    }
}


