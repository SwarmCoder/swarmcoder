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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.SwarmSizing;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.TurnAllowance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many tool turns a worker actually gets, and where the number came from.
 *
 * <p><b>The bug this exists to keep dead.</b> The worker loop read the cap from
 * {@link TokenBudget}, and no production code anywhere ever constructed a {@code TokenBudget}. So
 * {@code task.budget()} was always null, the cap was always the hardcoded thirty, and no settings
 * file, project file or story could change it. Measured on 2026-09-01: workers dying at turn 31
 * with BUDGET_EXCEEDED, having spent 71,000 of the 2,500,000 tokens they were allowed.
 */
class WorkerTurnAllowanceTest {

    private static Task taskWith(SwarmPolicy policy, TokenBudget budget) {
        Task task = new Task(UUID.randomUUID(), 1, "t", "do it", new HashSet<>(), new HashSet<>(),
            new ArrayList<>(), null, null, budget, policy, TaskState.READY);
        return task;
    }

    /** The exact regression: an ordinary planned task, nothing stated anywhere. */
    @Test
    void aTaskThatStatesNothingGetsTheBuiltInAllowanceAndNotThirty() {
        int turns = WorkerLoop.maxTurnsFor(taskWith(
            new SwarmPolicy(4, false, 0.2, 0.8, List.of("minimal-diff")), null));
        assertThat(turns)
            .describedAs("a task with no stated turn allowance must not fall back to 30")
            .isEqualTo(TurnAllowance.BUILT_IN_MAX_TOOL_TURNS)
            .isGreaterThan(30);
    }

    @Test
    void theSwarmPolicyCarriesTheResolvedAllowanceToTheWorker() {
        SwarmPolicy policy = new SwarmPolicy(4, false, 0.2, 0.8, List.of())
            .withTurns(TurnAllowance.resolve(null, null, 200, 90));
        assertThat(policy.maxToolTurns()).isEqualTo(200);
        assertThat(policy.turnSource()).contains("this project's own settings");
        assertThat(WorkerLoop.maxTurnsFor(taskWith(policy, null))).isEqualTo(200);
    }

    /** The per-task escape hatch still wins, which is what the older tests rely on. */
    @Test
    void aTaskBudgetBeatsTheSwarmPolicy() {
        SwarmPolicy policy = new SwarmPolicy(4, false, 0.2, 0.8, List.of())
            .withTurns(TurnAllowance.resolve(null, null, null, 90));
        Task task = taskWith(policy, new TokenBudget(32_000, 4_000, 500_000, 12));
        assertThat(WorkerLoop.maxTurnsFor(task)).isEqualTo(12);
    }

    @Test
    void aTaskWithNoPolicyAtAllStillGetsTheBuiltInAllowance() {
        assertThat(WorkerLoop.maxTurnsFor(taskWith(null, null)))
            .isEqualTo(TurnAllowance.BUILT_IN_MAX_TOOL_TURNS);
    }

    // --- the three layers -----------------------------------------------------------------------

    @Test
    void theStoryBeatsTheProjectWhichBeatsTheSettingsFile() {
        assertThat(TurnAllowance.resolve(300, "S3", 200, 90).maxToolTurns()).isEqualTo(300);
        assertThat(TurnAllowance.resolve(300, "S3", 200, 90).layer())
            .isEqualTo(SwarmSizing.Layer.STORY);
        assertThat(TurnAllowance.resolve(300, "S3", 200, 90).label()).contains("story S3");

        assertThat(TurnAllowance.resolve(null, null, 200, 90).maxToolTurns()).isEqualTo(200);
        assertThat(TurnAllowance.resolve(null, null, 200, 90).layer())
            .isEqualTo(SwarmSizing.Layer.PROJECT);

        assertThat(TurnAllowance.resolve(null, null, null, 90).maxToolTurns()).isEqualTo(90);
        assertThat(TurnAllowance.resolve(null, null, null, 90).layer())
            .isEqualTo(SwarmSizing.Layer.GLOBAL);

        TurnAllowance nobody = TurnAllowance.resolve(null, null, null, null);
        assertThat(nobody.maxToolTurns()).isEqualTo(TurnAllowance.BUILT_IN_MAX_TOOL_TURNS);
        assertThat(nobody.layer()).isEqualTo(SwarmSizing.Layer.BUILT_IN);
        assertThat(nobody.sentence()).contains("120 tool turns");
    }

    /** Nobody may set an allowance a single build-fix cycle cannot fit inside. */
    @Test
    void anAbsurdlySmallNumberIsClampedRatherThanObeyed() {
        assertThat(TurnAllowance.resolve(1, "S1", null, null).maxToolTurns())
            .isEqualTo(TurnAllowance.MINIMUM);
    }

    /**
     * A story that states a worker COUNT but no turn allowance must not silently drop the project's
     * or the settings file's turn allowance. The two are applied one after the other when a run
     * plans its tasks, and the first must not erase the second's input.
     */
    @Test
    void changingTheWorkerCountKeepsTheTurnAllowance() {
        SwarmPolicy policy = new SwarmPolicy(4, false, 0.2, 0.8, List.of())
            .withTurns(TurnAllowance.resolve(null, null, null, 90))
            .withWorkers(SwarmSizing.resolve(6, "S3", null, null));
        assertThat(policy.n()).isEqualTo(6);
        assertThat(policy.maxToolTurns())
            .describedAs("the story asked for more workers, not for fewer turns")
            .isEqualTo(90);
    }
}
