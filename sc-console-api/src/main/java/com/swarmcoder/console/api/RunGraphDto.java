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
import java.util.ArrayList;
import java.util.List;

/**
 * The complete run graph (design §6.2): run state + task DAG + candidate fan. Pushed whole
 * on the {@code run-graph} topic whenever anything changes (a full snapshot at ≤32
 * candidates is a few KB — the delta protocol of design §4.2 is an optimization for later);
 * {@code seq} increases per publish so stale frames are droppable.
 */
@DataModel
public class RunGraphDto implements BinaryPackable {

    private String runId;
    private String runState;
    private String goal;
    private long seq;
    /**
     * The server's clock when this frame was assembled.
     *
     * <p>Durations on the screen are measured against this rather than against the browser's own
     * clock: the two are minutes apart on some machines, and "open for 3m" computed from the wrong
     * one is the exact number an operator uses to decide a worker has hung.
     */
    private long atMillis;
    /** How many workers of this run were running at {@link #atMillis}. */
    private int liveWorkers;
    private List<GraphRequirementDto> requirements = new ArrayList<>();
    private List<GraphTaskDto> tasks = new ArrayList<>();
    private List<GraphCandidateDto> candidates = new ArrayList<>();

    public RunGraphDto() { }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getRunState() { return runState; }
    public void setRunState(String runState) { this.runState = runState; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public long getAtMillis() { return atMillis; }
    public void setAtMillis(long atMillis) { this.atMillis = atMillis; }
    public int getLiveWorkers() { return liveWorkers; }
    public void setLiveWorkers(int liveWorkers) { this.liveWorkers = liveWorkers; }
    public List<GraphRequirementDto> getRequirements() { return requirements; }
    public void setRequirements(List<GraphRequirementDto> requirements) { this.requirements = requirements; }
    public List<GraphTaskDto> getTasks() { return tasks; }
    public void setTasks(List<GraphTaskDto> tasks) { this.tasks = tasks; }
    public List<GraphCandidateDto> getCandidates() { return candidates; }
    public void setCandidates(List<GraphCandidateDto> candidates) { this.candidates = candidates; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, runId);
        BinarySerializer.writeString(buffer, runState);
        BinarySerializer.writeString(buffer, goal);
        BinarySerializer.writeValue(buffer, seq, mapper);
        BinarySerializer.writeValue(buffer, atMillis, mapper);
        BinarySerializer.writeValue(buffer, liveWorkers, mapper);
        BinarySerializer.writeValue(buffer, requirements.size(), mapper);
        for (GraphRequirementDto requirement : requirements) {
            requirement.writeToBuffer(buffer, mapper);
        }
        BinarySerializer.writeValue(buffer, tasks.size(), mapper);
        for (GraphTaskDto task : tasks) {
            task.writeToBuffer(buffer, mapper);
        }
        BinarySerializer.writeValue(buffer, candidates.size(), mapper);
        for (GraphCandidateDto candidate : candidates) {
            candidate.writeToBuffer(buffer, mapper);
        }
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.runId = BinarySerializer.readString(buffer);
        this.runState = BinarySerializer.readString(buffer);
        this.goal = BinarySerializer.readString(buffer);
        this.seq = SessionSummaryDto.readLong(buffer, mapper);
        this.atMillis = SessionSummaryDto.readLong(buffer, mapper);
        this.liveWorkers = SessionSummaryDto.readInt(buffer, mapper);
        int requirementCount = SessionSummaryDto.readInt(buffer, mapper);
        this.requirements = new ArrayList<>(requirementCount);
        for (int i = 0; i < requirementCount; i++) {
            GraphRequirementDto requirement = new GraphRequirementDto();
            requirement.readFromBuffer(buffer, mapper);
            requirements.add(requirement);
        }
        int taskCount = SessionSummaryDto.readInt(buffer, mapper);
        this.tasks = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            GraphTaskDto task = new GraphTaskDto();
            task.readFromBuffer(buffer, mapper);
            tasks.add(task);
        }
        int candidateCount = SessionSummaryDto.readInt(buffer, mapper);
        this.candidates = new ArrayList<>(candidateCount);
        for (int i = 0; i < candidateCount; i++) {
            GraphCandidateDto candidate = new GraphCandidateDto();
            candidate.readFromBuffer(buffer, mapper);
            candidates.add(candidate);
        }
    }
}


