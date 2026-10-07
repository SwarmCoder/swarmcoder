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
package com.swarmcoder.app;

import com.swarmcoder.app.config.ProjectConfig;
import com.swarmcoder.app.config.ProjectSwarmConfig;
import com.swarmcoder.app.config.SwarmEngineConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.SwarmSizing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two sizing layers that are resolved when a project's engine is built — the project's own
 * {@code project.yaml} over the settings file — and the defaults that apply when neither speaks.
 *
 * <p>The story layer is applied later, when a run plans its tasks, because that is the first moment
 * a story exists; it is covered in {@code SwarmSizingTest}.
 */
class SwarmSizingLayersTest {

    private static SwarmEngineConfig swarm(int nPerTask) {
        return new SwarmEngineConfig(nPerTask, false, 0, 0.2, 0.8, null);
    }

    @Test
    void noSwarmBlockStillMeansNoSwarmingAtAll() {
        assertThat(ProjectContext.taskPolicyFrom(null, 6))
            .describedAs("a settings file with no swarm block must never swarm by surprise")
            .isNull();
    }

    @Test
    void theProjectsOwnNumberBeatsTheSettingsFile() {
        SwarmPolicy policy = ProjectContext.taskPolicyFrom(swarm(4), 6);
        assertThat(policy.n()).isEqualTo(6);
        assertThat(policy.nSource()).contains("this project's own settings");
    }

    @Test
    void theSettingsFileIsUsedWhenTheProjectSaysNothing() {
        SwarmPolicy policy = ProjectContext.taskPolicyFrom(swarm(10), null);
        assertThat(policy.n()).isEqualTo(10);
        assertThat(policy.nSource()).contains("the settings file");
    }

    /** An empty swarm block is four attempts, not one, and not eight. */
    @Test
    void aSwarmBlockThatStatesNothingGetsFour() {
        SwarmPolicy policy = ProjectContext.taskPolicyFrom(swarm(0), null);
        assertThat(policy.n()).isEqualTo(4);
        assertThat(policy.nSource()).contains("nothing asks for a number");
    }

    /**
     * The defaults the owner asked for: up to four workers on a piece of work, eight workers at
     * once on the machine, and (since 2026-10-02) no ceiling of its own on pieces of work at a
     * time — the model server's places decide that, first worker of every ready piece first.
     */
    @Test
    void theShippedDefaultsLeaveTasksAtOnceToTheServersPlaces() {
        SwarmEngineConfig unstated = new SwarmEngineConfig(0, false, 0, 0, 0, null);
        assertThat(unstated.workersPerTaskOrDefault()).isEqualTo(4);
        assertThat(unstated.taskGroupsAtOnce()).as("no ceiling of its own").isZero();
        assertThat(unstated.workerCeiling()).isEqualTo(8);
    }

    /** A number the operator wrote is a ceiling, kept exactly — including one. */
    @Test
    void aStatedCeilingOnTasksAtOnceIsKept() {
        assertThat(new SwarmEngineConfig(4, false, 1, 0.1, 0.8, null, null, 4).taskGroupsAtOnce())
            .isEqualTo(1);
    }

    /**
     * What the file said is kept separate from what the default is, because this record is written
     * back to config.yaml every time the settings screen saves anything. An accessor that
     * substituted the default would bake it into the operator's file and make the run log credit
     * the settings file for a number nobody typed.
     */
    @Test
    void unsetStaysDistinguishableFromStated() {
        assertThat(new SwarmEngineConfig(0, false, 0, 0, 0, null).statedNPerTask()).isNull();
        assertThat(new SwarmEngineConfig(4, false, 0, 0, 0, null).statedNPerTask()).isEqualTo(4);
        assertThat(new SwarmEngineConfig(0, false, 0, 0, 0, null).nPerTask())
            .describedAs("the raw component is what gets serialised").isZero();
    }

    /** A negative asks for the old unlimited behaviour explicitly; a bare 0 no longer does. */
    @Test
    void unlimitedHasToBeAskedForNow() {
        assertThat(new SwarmEngineConfig(4, false, -1, 0.2, 0.8, null, null, -1)
            .taskGroupsAtOnce()).isZero();
        assertThat(new SwarmEngineConfig(4, false, -1, 0.2, 0.8, null, null, -1)
            .workerCeiling()).isZero();
    }

    @Test
    void aProjectFileWithNoSwarmBlockInheritsRatherThanStatingFour() {
        ProjectConfig silent = new ProjectConfig("p", List.of(), List.of(), null);
        assertThat(silent.statedWorkersPerTask()).isNull();

        ProjectConfig speaks = new ProjectConfig("p", List.of(), List.of(), null,
            new ProjectSwarmConfig(6));
        assertThat(speaks.statedWorkersPerTask()).isEqualTo(6);
    }

    @Test
    void theBuiltInIsFourAndTheMinimumIsOne() {
        assertThat(SwarmSizing.BUILT_IN_WORKERS_PER_TASK).isEqualTo(4);
        assertThat(SwarmSizing.MINIMUM).isEqualTo(1);
    }
}
