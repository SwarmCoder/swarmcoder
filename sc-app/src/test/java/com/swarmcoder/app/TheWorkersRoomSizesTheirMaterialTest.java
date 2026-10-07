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

import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.ModelShapes;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the workers' room reaches the Librarian and the help desk in production (2026-09-25): from
 * the resolved worker profiles, the smallest of them, because one knowledge brief is shared by every
 * worker of a task whichever family it runs on.
 */
class TheWorkersRoomSizesTheirMaterialTest {

    @Test
    void theDeepSeekWorkersGetTheirOwnRoom() {
        MaterialBudget room = ProjectContext.workerRoomOf(registry(
            ModelShapes.get("deepseek-v4-flash-ds4")));

        assertThat(room.workingContextTokens()).isEqualTo(262_144);
        assertThat(room.chars(18_000)).isEqualTo(92_160);
    }

    @Test
    void twoFamiliesAreSizedForTheSmallerRoom() {
        ModelQuirks deepSeek = ModelShapes.get("deepseek-v4-flash-ds4");
        ModelQuirks qwenSized = deepSeek.withWorkingContextTokens(51_200);

        MaterialBudget room = ProjectContext.workerRoomOf(registry(deepSeek, qwenSized));

        assertThat(room.workingContextTokens()).isEqualTo(51_200);
        assertThat(room.atBaseline()).isTrue();
    }

    @Test
    void noWorkerModelsIsTheBaseline() {
        assertThat(ProjectContext.workerRoomOf(new ModelProfileRegistry(List.of())))
            .isEqualTo(MaterialBudget.BASELINE);
        assertThat(ProjectContext.workerRoomOf(null)).isEqualTo(MaterialBudget.BASELINE);
    }

    private static ModelProfileRegistry registry(ModelQuirks... families) {
        List<ModelProfile> profiles = new java.util.ArrayList<>();
        int n = 0;
        for (ModelQuirks quirks : families) {
            String id = "family-" + n++;
            profiles.add(new ModelProfile(id,
                new AgentRuntime.ModelEndpoint("http://127.0.0.1:1", "", id,
                    quirks.servedContextTokens(), quirks),
                ModelProfile.Kind.WORKER, quirks, 0));
        }
        return new ModelProfileRegistry(profiles);
    }
}
