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

/** The budgets block of the shared config (design §7). Zero means unset/uncapped. */
@DataModel
public class BudgetsDto implements BinaryPackable {

    private long maxCloudTokensPerRun;
    private long maxLocalTokensPerTask;
    private int wallClockCeilingHours;
    /** Tool turns each worker gets before it is stopped. Zero means the built-in default (120). */
    private int maxToolTurnsPerWorker;

    public BudgetsDto() { }

    public long getMaxCloudTokensPerRun() { return maxCloudTokensPerRun; }
    public void setMaxCloudTokensPerRun(long maxCloudTokensPerRun) { this.maxCloudTokensPerRun = maxCloudTokensPerRun; }
    public long getMaxLocalTokensPerTask() { return maxLocalTokensPerTask; }
    public void setMaxLocalTokensPerTask(long maxLocalTokensPerTask) { this.maxLocalTokensPerTask = maxLocalTokensPerTask; }
    public int getWallClockCeilingHours() { return wallClockCeilingHours; }
    public void setWallClockCeilingHours(int wallClockCeilingHours) { this.wallClockCeilingHours = wallClockCeilingHours; }
    public int getMaxToolTurnsPerWorker() { return maxToolTurnsPerWorker; }
    public void setMaxToolTurnsPerWorker(int maxToolTurnsPerWorker) { this.maxToolTurnsPerWorker = maxToolTurnsPerWorker; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeValue(buffer, maxCloudTokensPerRun, mapper);
        BinarySerializer.writeValue(buffer, maxLocalTokensPerTask, mapper);
        BinarySerializer.writeValue(buffer, wallClockCeilingHours, mapper);
        BinarySerializer.writeValue(buffer, maxToolTurnsPerWorker, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.maxCloudTokensPerRun = SessionSummaryDto.readLong(buffer, mapper);
        this.maxLocalTokensPerTask = SessionSummaryDto.readLong(buffer, mapper);
        this.wallClockCeilingHours = SessionSummaryDto.readInt(buffer, mapper);
        this.maxToolTurnsPerWorker = SessionSummaryDto.readInt(buffer, mapper);
    }
}


