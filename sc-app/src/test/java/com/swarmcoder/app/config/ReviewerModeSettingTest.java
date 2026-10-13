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
import com.swarmcoder.domain.ReviewerMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design reviewer asks one question a reading unless the settings say otherwise (section 96).
 * The application resolves {@code roles.reviewerMode} and the harness resolves its flag with the
 * same function, so both mean the same thing by the same word.
 */
class ReviewerModeSettingTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @AfterEach
    void backToTheDefault() {
        ReviewerMode.configure(null);
    }

    private static String settingOf(String yaml) throws Exception {
        SwarmConfig config = YAML.readValue(yaml, SwarmConfig.class);
        return config.roles() == null ? null : config.roles().reviewerMode();
    }

    @Test
    void aSettingsFileThatSaysNothingIsOneQuestionAReading() throws Exception {
        assertThat(ReviewerMode.fromSetting(settingOf("repoPath: /work/project\n")))
            .isEqualTo(ReviewerMode.QUESTION);
        assertThat(ReviewerMode.fromSetting(settingOf("roles:\n  utility: null\n")))
            .isEqualTo(ReviewerMode.QUESTION);
        assertThat(ReviewerMode.fromSetting("garbage")).isEqualTo(ReviewerMode.QUESTION);
        assertThat(ReviewerMode.isRecognized("garbage")).isFalse();
        assertThat(ReviewerMode.configure(null)).isEqualTo(ReviewerMode.QUESTION);
    }

    @Test
    void theSettingNamesTheModeAndTheApplicationAndTheHarnessResolveItTheSame() throws Exception {
        String setting = settingOf("roles:\n  reviewerMode: lookups\n");

        assertThat(setting).isEqualTo("lookups");
        ReviewerMode application = ReviewerMode.configure(setting);
        ReviewerMode harness = ReviewerMode.configure("LOOKUPS");
        assertThat(application).isEqualTo(ReviewerMode.LOOKUPS).isEqualTo(harness);
        assertThat(ReviewerMode.current()).isEqualTo(ReviewerMode.LOOKUPS);

        assertThat(ReviewerMode.configure(settingOf("roles:\n  reviewerMode: question\n")))
            .isEqualTo(ReviewerMode.QUESTION);
        assertThat(ReviewerMode.current()).isEqualTo(ReviewerMode.QUESTION);
    }

    @Test
    void theStarterFileAndTheOldShapeOfTheRolesStillWork() {
        assertThat(ConfigLoader.starterConfigText()).contains("reviewerMode: question");
        assertThat(new RolesConfig(null, null, null, null, null, null, null, null, null, null,
            null, null, null).reviewerMode()).isNull();
    }
}
