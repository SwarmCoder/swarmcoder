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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The swarm has to be honest about how much diversity it actually has.
 *
 * <p>The design assumed two model families side by side on the box, so that ten workers on one task
 * would disagree in useful ways. Only one model fits now — a memory limit, since all of a
 * mixture-of-experts model's weights must be resident even though few are active per token — and
 * {@code splitAcrossFamilies: true} with one family then does nothing at all. It used to do nothing
 * SILENTLY, while the config still read as though model diversity was on.
 */
class SwarmDiversityTest {

    @Test
    void oneModelSaysSoAndNamesTheSettingThatIsDoingNothing() {
        String said = SwarmDispatcher.describeDiversity(true, List.of("qwen38-flash-next-125b"),
            0.2, 0.8, 4);

        assertThat(said).contains("one worker model");
        assertThat(said).contains("SAME model");
        assertThat(said).contains("nothing to split across");
    }

    @Test
    void oneModelWithTheSettingOffDoesNotComplainAboutIt() {
        String said = SwarmDispatcher.describeDiversity(false, List.of("qwen38-flash-next-125b"),
            0.2, 0.8, 4);

        assertThat(said).contains("one worker model");
        assertThat(said).doesNotContain("nothing to split across");
    }

    @Test
    void noModelAtAllIsSaidPlainly() {
        assertThat(SwarmDispatcher.describeDiversity(true, List.of(), 0.2, 0.8, 4))
            .contains("NO worker model is configured");
    }

    @Test
    void twoModelsWithTheSettingOffIsAlsoWorthSaying() {
        // Configuring a second model and forgetting the switch is the mirror-image silent loss.
        String said = SwarmDispatcher.describeDiversity(false, List.of("a", "b"), 0.2, 0.8, 4);

        assertThat(said).contains("2 worker models configured");
        assertThat(said).contains("every worker runs a");
    }

    @Test
    void theRemainingLeversAreNamedAndTheFakeOneIsCalledOut() {
        String said = SwarmDispatcher.describeDiversity(true, List.of("only"), 0.2, 0.8, 4);

        assertThat(said).contains("Temperature spread 0.20-0.80");
        assertThat(said).contains("4 persona(s)");
        // The seed is recorded on every candidate and looks like a lever. Neither request path has
        // a seed parameter, so it never reaches the model.
        assertThat(said).contains("seed is recorded but never sent");
    }

    @Test
    void thereIsMoreThanOnePersonaToRotate() {
        // The default used to be a single persona handed to every worker in the group — a
        // diversity lever that did nothing. With one model it is one of only two levers left.
        assertThat(WorkerPersonas.DEFAULT_ROTATION).hasSizeGreaterThan(1);
        for (String id : WorkerPersonas.DEFAULT_ROTATION) {
            assertThat(WorkerPersonas.text(id)).isNotBlank().isNotEqualTo(id);
        }
    }
}
