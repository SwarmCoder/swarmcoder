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
import com.swarmcoder.runtime.CloudGate;

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
    @JsonProperty("maxToolTurnsPerWorker") int maxToolTurnsPerWorker,
    /** Cloud tokens (input and output together) one story may use across its runs; 0 = no limit. */
    @JsonProperty("maxCloudTokensPerStory") long maxCloudTokensPerStory,
    /** Cloud tokens (input and output together) one project may use; 0 = no limit. */
    @JsonProperty("maxCloudTokensPerProject") long maxCloudTokensPerProject,
    /** Limits on cloud tokens SENT to the models, per run, story and project. Nullable. */
    @JsonProperty("maxCloudInputTokens") CloudTokenLimits maxCloudInputTokens,
    /** Limits on cloud tokens the models WROTE, per run, story and project. Nullable. */
    @JsonProperty("maxCloudOutputTokens") CloudTokenLimits maxCloudOutputTokens
) {
    /**
     * One direction's limits at the three levels; 0 means no limit at that level.
     *
     * <p>In the file: {@code budgets: {maxCloudInputTokens: {perRun: 500000, perStory: 2000000}}}.
     */
    public record CloudTokenLimits(
        @JsonProperty("perRun") long perRun,
        @JsonProperty("perStory") long perStory,
        @JsonProperty("perProject") long perProject) {
    }

    /** The four-field shape, kept so existing call sites construct this unchanged. */
    public BudgetsConfig(long maxCloudTokensPerRun, long maxLocalTokensPerTask,
                         int wallClockCeilingHours, int maxToolTurnsPerWorker) {
        this(maxCloudTokensPerRun, maxLocalTokensPerTask, wallClockCeilingHours,
            maxToolTurnsPerWorker, 0, 0, null, null);
    }

    /** The three-field shape, kept so existing call sites construct this unchanged. */
    public BudgetsConfig(long maxCloudTokensPerRun, long maxLocalTokensPerTask,
                         int wallClockCeilingHours) {
        this(maxCloudTokensPerRun, maxLocalTokensPerTask, wallClockCeilingHours, 0);
    }

    /** The same budgets with the first four settings replaced and the rest kept. */
    public BudgetsConfig withBasics(long maxCloudTokensPerRun, long maxLocalTokensPerTask,
                                    int wallClockCeilingHours, int maxToolTurnsPerWorker) {
        return new BudgetsConfig(maxCloudTokensPerRun, maxLocalTokensPerTask,
            wallClockCeilingHours, maxToolTurnsPerWorker, maxCloudTokensPerStory,
            maxCloudTokensPerProject, maxCloudInputTokens, maxCloudOutputTokens);
    }

    /**
     * The cloud token limits as the gate takes them: per run, story and project, each for input,
     * output and both together. Every figure is optional (0 = none); an absent block is none.
     */
    public CloudGate.Limits cloudLimits() {
        CloudTokenLimits in = maxCloudInputTokens == null
            ? new CloudTokenLimits(0, 0, 0) : maxCloudInputTokens;
        CloudTokenLimits out = maxCloudOutputTokens == null
            ? new CloudTokenLimits(0, 0, 0) : maxCloudOutputTokens;
        return new CloudGate.Limits(
            new CloudGate.Cap(maxCloudTokensPerRun, in.perRun(), out.perRun()),
            new CloudGate.Cap(maxCloudTokensPerStory, in.perStory(), out.perStory()),
            new CloudGate.Cap(maxCloudTokensPerProject, in.perProject(), out.perProject()));
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
