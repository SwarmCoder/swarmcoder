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

import java.util.UUID;

/**
 * The limit that parked a run, kept so that answering "extend" still works after a restart (stored
 * in {@code StoreRoot.cloudBreaches}, keyed by the run's id). Removed once the limit is extended.
 *
 * <p>A mutable POJO because EclipseStore cannot persist records.
 */
public class CloudBreachRecord {
    private UUID runId;
    private UUID storyId;
    private UUID projectId;
    /** RUN, STORY or PROJECT. */
    private String level;
    /** TOTAL, INPUT or OUTPUT. */
    private String direction;
    private long limit;
    private long usedInput;
    private long usedOutput;

    public CloudBreachRecord() {}

    public CloudBreachRecord(UUID runId, UUID storyId, UUID projectId, String level,
                             String direction, long limit, long usedInput, long usedOutput) {
        this.runId = runId;
        this.storyId = storyId;
        this.projectId = projectId;
        this.level = level;
        this.direction = direction;
        this.limit = limit;
        this.usedInput = usedInput;
        this.usedOutput = usedOutput;
    }

    public UUID runId() { return runId; }
    public UUID storyId() { return storyId; }
    public UUID projectId() { return projectId; }
    public String level() { return level; }
    public String direction() { return direction; }
    public long limit() { return limit; }
    public long usedInput() { return usedInput; }
    public long usedOutput() { return usedOutput; }
}
