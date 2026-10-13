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
 * <p>A mutable POJO because EclipseStore cannot persist records.
 */
public class RunUsageRecord {
    private UUID runId;
    private UUID storyId;
    private UUID projectId;
    private List<ModelUsageRecord> models = new ArrayList<>();
    private List<LookupUsageRecord> lookups = new ArrayList<>();

    public RunUsageRecord() {}

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
}
