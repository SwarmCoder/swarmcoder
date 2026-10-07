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

import java.time.Duration;
import java.util.Objects;

public class Budget {
    private long maxCloudTokens;
    private long usedCloudTokens;
    private Duration wallClockCeiling;
    private long maxLocalTokensPerTask;

    public Budget() {}

    public Budget(long maxCloudTokens, long usedCloudTokens, Duration wallClockCeiling, long maxLocalTokensPerTask) {
        this.maxCloudTokens = maxCloudTokens;
        this.usedCloudTokens = usedCloudTokens;
        this.wallClockCeiling = wallClockCeiling;
        this.maxLocalTokensPerTask = maxLocalTokensPerTask;
    }

    public long maxCloudTokens() { return maxCloudTokens; }
    public long getMaxCloudTokens() { return maxCloudTokens; }
    public void setMaxCloudTokens(long maxCloudTokens) { this.maxCloudTokens = maxCloudTokens; }
    public long usedCloudTokens() { return usedCloudTokens; }
    public long getUsedCloudTokens() { return usedCloudTokens; }
    public void setUsedCloudTokens(long usedCloudTokens) { this.usedCloudTokens = usedCloudTokens; }
    public Duration wallClockCeiling() { return wallClockCeiling; }
    public Duration getWallClockCeiling() { return wallClockCeiling; }
    public void setWallClockCeiling(Duration wallClockCeiling) { this.wallClockCeiling = wallClockCeiling; }
    public long maxLocalTokensPerTask() { return maxLocalTokensPerTask; }
    public long getMaxLocalTokensPerTask() { return maxLocalTokensPerTask; }
    public void setMaxLocalTokensPerTask(long maxLocalTokensPerTask) { this.maxLocalTokensPerTask = maxLocalTokensPerTask; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Budget that = (Budget) o;
        return this.maxCloudTokens == that.maxCloudTokens && this.usedCloudTokens == that.usedCloudTokens && Objects.equals(this.wallClockCeiling, that.wallClockCeiling) && this.maxLocalTokensPerTask == that.maxLocalTokensPerTask;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxCloudTokens, usedCloudTokens, wallClockCeiling, maxLocalTokensPerTask);
    }
}

