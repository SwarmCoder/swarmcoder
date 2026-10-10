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

import com.swarmcoder.app.config.BudgetsConfig;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The question a passed cloud token limit puts to the operator, and the settings it comes from. */
class BudgetDecisionTest {

    @Test
    void theQuestionNamesProjectStoryRunLimitAndWhatWasUsed() {
        UUID run = UUID.randomUUID();
        CloudGate.Breach breach = new CloudGate.Breach(CloudGate.Level.STORY,
            CloudGate.Direction.INPUT, UUID.randomUUID(), UUID.randomUUID(), run, 2_000_000,
            new CloudGate.Spend(2_100_000, 80_000));

        String brief = BudgetDecision.brief(breach, "Bookshelf", "S3 Lend a book");

        assertThat(brief)
            .contains("Project: Bookshelf")
            .contains("Story: S3 Lend a book")
            .contains("Run: " + run)
            .contains("the story's (all its runs together) limit on cloud input tokens")
            .contains("2,000,000 input tokens (story limit)")
            .contains("2,100,000 input tokens and 80,000 output tokens")
            .contains("\"extend\"")
            .contains("\"stop\"");
    }

    @Test
    void theSettingsGiveEachLimitForInputOutputOrBoth() {
        BudgetsConfig budgets = new BudgetsConfig(1_000, 0, 6, 0, 5_000, 20_000,
            new BudgetsConfig.CloudTokenLimits(700, 0, 0),
            new BudgetsConfig.CloudTokenLimits(0, 300, 0));

        CloudGate.Limits limits = budgets.cloudLimits();

        assertThat(limits.run()).isEqualTo(new CloudGate.Cap(1_000, 700, 0));
        assertThat(limits.story()).isEqualTo(new CloudGate.Cap(5_000, 0, 300));
        assertThat(limits.project()).isEqualTo(new CloudGate.Cap(20_000, 0, 0));
    }

    @Test
    void theOldSettingStillMeansTotalTokensPerRunAndNothingElse() {
        CloudGate.Limits limits = new BudgetsConfig(1_000_000, 500_000, 6, 200).cloudLimits();

        assertThat(limits.run()).isEqualTo(CloudGate.Cap.total(1_000_000));
        assertThat(limits.story().any()).isFalse();
        assertThat(limits.project().any()).isFalse();
    }
}
