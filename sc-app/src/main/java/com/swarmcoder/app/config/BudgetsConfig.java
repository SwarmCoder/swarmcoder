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
package com.swarmcoder.app.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.swarmcoder.domain.TurnAllowance;

/**
 * The {@code budgets:} block of {@code config.yaml} — the GLOBAL layer of the three layers a
 * worker's turn allowance is resolved over (story, then project, then here; see
 * {@link TurnAllowance}).
 *
 * <p>{@code maxToolTurnsPerWorker} is new on 2026-09-01 and closes a hole: the worker loop read a
 * turn cap from {@code TokenBudget}, nothing in production ever built a {@code TokenBudget}, so
 * the cap was permanently the hardcoded thirty and no file could change it. Workers were dying at
 * turn 31 having spent under three percent of the tokens they were allowed.
 */
public record BudgetsConfig(
    @JsonProperty("maxCloudTokensPerRun") long maxCloudTokensPerRun,
    @JsonProperty("maxLocalTokensPerTask") long maxLocalTokensPerTask,
    @JsonProperty("wallClockCeilingHours") int wallClockCeilingHours,
    /** Tool turns each worker gets. Absent or zero means the built-in default. */
    @JsonProperty("maxToolTurnsPerWorker") int maxToolTurnsPerWorker
) {
    /** The three-field shape, kept so existing call sites construct this unchanged. */
    public BudgetsConfig(long maxCloudTokensPerRun, long maxLocalTokensPerTask,
                         int wallClockCeilingHours) {
        this(maxCloudTokensPerRun, maxLocalTokensPerTask, wallClockCeilingHours, 0);
    }

    /**
     * What the file literally says about tool turns per worker, or null when it says nothing.
     *
     * <p>Null and "120" have to stay different answers, for the same reason they do for the worker
     * count: the run log names the layer that won, and it cannot do that if inheriting looks
     * identical to stating the inherited value.
     */
    public Integer statedMaxToolTurns() {
        return maxToolTurnsPerWorker > 0 ? maxToolTurnsPerWorker : null;
    }
}
