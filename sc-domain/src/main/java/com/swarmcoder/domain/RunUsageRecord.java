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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything one run's model calls and lookups cost, per role (stored in
 * {@code StoreRoot.runUsage}, keyed by the run's id, schema v14).
 *
 * <p>Written as a whole fresh copy each time, never edited in place, so the store sees a new
 * instance and writes it. The story and the project are kept with it so that a story's or a
 * project's total is a sum over these records and needs no other lookup.
 *
 * <p>Since section 102 it also holds every call one by one ({@link CallLog}) and the questions
 * the run's lookups have answered ({@link AskedQuestions}); and one such record per project,
 * kept under the project's id with no run id, holds what was called for no run at all.
 *
 * <p>A mutable POJO because EclipseStore cannot persist records.
 */
public class RunUsageRecord {
    private UUID runId;
    private UUID storyId;
    private UUID projectId;
    private List<ModelUsageRecord> models = new ArrayList<>();
    private List<LookupUsageRecord> lookups = new ArrayList<>();
    /** Every call one by one; null in a record stored before calls were kept that way. */
    private CallLog calls;
    /** The questions lookups have answered in this run; null in a record from before. */
    private AskedQuestions asked;
    /**
     * True for the record of what was called for no run - a wizard, the chat, knowledge read at
     * start: then {@link #runId} is null and the record is kept under its project's id.
     */
    private boolean outsideRuns;

    public RunUsageRecord() {}

    /** As the five-argument constructor, with the calls one by one and the questions asked. */
    public RunUsageRecord(UUID runId, UUID storyId, UUID projectId, List<ModelUsageRecord> models,
                          List<LookupUsageRecord> lookups, CallLog calls, AskedQuestions asked,
                          boolean outsideRuns) {
        this(runId, storyId, projectId, models, lookups);
        this.calls = calls;
        this.asked = asked;
        this.outsideRuns = outsideRuns;
    }

    public RunUsageRecord(UUID runId, UUID storyId, UUID projectId, List<ModelUsageRecord> models,
                          List<LookupUsageRecord> lookups) {
        this.runId = runId;
        this.storyId = storyId;
        this.projectId = projectId;
        this.models = new ArrayList<>(models);
        this.lookups = new ArrayList<>(lookups);
    }

    public UUID runId() { return runId; }
    public UUID storyId() { return storyId; }
    public UUID projectId() { return projectId; }

    public List<ModelUsageRecord> models() {
        return models == null ? List.of() : models;
    }

    public List<LookupUsageRecord> lookups() {
        return lookups == null ? List.of() : lookups;
    }

    /** The calls one by one as they are stored, or null for a record from before. */
    public CallLog callLog() {
        return calls;
    }

    /** Every call one by one, oldest first; empty for a record from before. */
    public List<CallUsageRecord> calls() {
        return calls == null ? List.of() : calls.calls();
    }

    /** The questions answered so far, or null for a record from before. */
    public AskedQuestions asked() {
        return asked;
    }

    /** True for what was called for no run; see the field. */
    public boolean outsideRuns() {
        return outsideRuns;
    }
}
