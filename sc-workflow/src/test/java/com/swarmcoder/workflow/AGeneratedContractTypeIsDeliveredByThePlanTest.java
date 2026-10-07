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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 72: the contract {@code com.hambook.Qso_Rules} is generated at build time by an
 * annotation processor once a field is annotated. The task that annotates the field delivers it;
 * its write set holds the annotated source file and never the generated one. The plan check goes
 * by the task's {@code deliversContracts}, so that plan is accepted.
 */
class AGeneratedContractTypeIsDeliveredByThePlanTest {

    @Test
    void aTaskWhoseWriteSetLacksTheGeneratedFileIsAcceptedAsItsDeliverer() {
        ApiContract rules = new ApiContract(UUID.randomUUID(), "Qso_Rules", "generated rules", null,
            "com.hambook.Qso_Rules", List.of("List<String> validate(Qso qso)"));
        Task annotate = new Task(UUID.randomUUID(), 1, "Annotate Qso.call with @NotBlank",
            "annotate the field", Set.of("hambook-shared/src/main/java/com/hambook/Qso.java"),
            Set.of(), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        annotate.setDeliveredContracts(List.of(rules));
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "validate a QSO", List.of(),
            new ArrayList<>(), List.of(rules), List.of(), null, Instant.now());

        TaskGraphValidator.Verdict verdict = new TaskGraphValidator().validate(
            new TaskGraph(UUID.randomUUID(), 1, null, List.of(annotate), List.of()),
            null, null, design, null);

        assertThat(verdict.violations()).noneMatch(v -> v.contains("no task delivers"));
    }
}
