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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The warnings an unattended run carried stay on the run through every stage, once each. */
class RunCarriesWarningsTest {

    @Test
    void aWarningIsRecordedOnceAndSurvivesATransition() {
        Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.PLAN, null, null,
            null, null, null, Instant.now(), null);
        assertThat(run.carriedWarnings()).isEmpty();
        Run before = run.withState(RunState.PLAN);

        assertThat(run.carryWarning(new CarriedWarning("plan against the rules", "PLAN",
            "task 'A' conflicts with rule 'R':\n  it uses localStorage", Instant.now()))).isTrue();
        assertThat(run.carryWarning(new CarriedWarning("plan against the rules", "PLAN",
            "task 'A' conflicts with rule 'R':\n  it uses localStorage", Instant.now().plusSeconds(5))))
            .as("the same objection from the same check is one warning").isFalse();

        Run next = run.withState(RunState.TEST_AUTHORING);
        assertThat(next.carriedWarnings()).singleElement().satisfies(w -> {
            assertThat(w.stage()).isEqualTo("PLAN");
            assertThat(w.oneLine())
                .isEqualTo("[PLAN] plan against the rules: task 'A' conflicts with rule 'R': "
                    + "it uses localStorage");
        });
        assertThat(run).as("a run that gained a warning is a changed run, so it is saved")
            .isNotEqualTo(before);
    }
}
