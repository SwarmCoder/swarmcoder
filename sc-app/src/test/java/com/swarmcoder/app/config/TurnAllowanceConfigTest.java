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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.swarmcoder.domain.SwarmSizing;
import com.swarmcoder.domain.TurnAllowance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two turn-allowance layers that live in files — the settings file's {@code budgets:} block and
 * a project's own {@code .swarmcoder/project.yaml} — actually parse and actually win in the right
 * order.
 *
 * <p>This is the half of the fix that a unit test on the resolution alone would miss: the previous
 * turn cap was unreachable not because the resolution was wrong but because nothing read a file
 * into it.
 */
class TurnAllowanceConfigTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void theSettingsFileCanStateATurnAllowance() throws Exception {
        SwarmConfig config = YAML.readValue("""
            budgets:
              maxCloudTokensPerRun: 1000000
              maxToolTurnsPerWorker: 200
            """, SwarmConfig.class);
        assertThat(config.budgets().maxToolTurnsPerWorker()).isEqualTo(200);
        assertThat(config.budgets().statedMaxToolTurns()).isEqualTo(200);
    }

    /** Silence has to stay distinguishable from stating the default, or the run log lies. */
    @Test
    void aBudgetsBlockThatSaysNothingStatesNothing() throws Exception {
        SwarmConfig config = YAML.readValue("""
            budgets:
              maxCloudTokensPerRun: 1000000
            """, SwarmConfig.class);
        assertThat(config.budgets().statedMaxToolTurns()).isNull();
        assertThat(TurnAllowance.resolve(null, null, null, config.budgets().statedMaxToolTurns())
            .layer()).isEqualTo(SwarmSizing.Layer.BUILT_IN);
    }

    @Test
    void aProjectCanStateItsOwnTurnAllowanceAndItBeatsTheSettingsFile() throws Exception {
        ProjectConfig project = YAML.readValue("""
            swarm:
              nPerTask: 6
              maxToolTurns: 400
            """, ProjectConfig.class);
        assertThat(project.statedMaxToolTurns()).isEqualTo(400);
        assertThat(project.statedWorkersPerTask()).isEqualTo(6);

        TurnAllowance resolved =
            TurnAllowance.resolve(null, null, project.statedMaxToolTurns(), 200);
        assertThat(resolved.maxToolTurns()).isEqualTo(400);
        assertThat(resolved.layer()).isEqualTo(SwarmSizing.Layer.PROJECT);
    }

    @Test
    void aProjectThatOnlyStatesAWorkerCountInheritsTheTurnAllowance() throws Exception {
        ProjectConfig project = YAML.readValue("""
            swarm:
              nPerTask: 6
            """, ProjectConfig.class);
        assertThat(project.statedMaxToolTurns()).isNull();
        assertThat(TurnAllowance.resolve(null, null, project.statedMaxToolTurns(), 200)
            .maxToolTurns()).isEqualTo(200);
    }

    /**
     * Saving the budgets form must not wipe the turn allowance. The settings screen sends the whole
     * block back, so a field it did not carry would be silently reset to "unset" on every save.
     */
    @Test
    void theBudgetsBlockSurvivesBeingWrittenBackOut() throws Exception {
        BudgetsConfig budgets = new BudgetsConfig(1_000_000, 500_000, 6, 200);
        String yaml = YAML.writeValueAsString(budgets);
        assertThat(YAML.readValue(yaml, BudgetsConfig.class).maxToolTurnsPerWorker())
            .isEqualTo(200);
    }
}
