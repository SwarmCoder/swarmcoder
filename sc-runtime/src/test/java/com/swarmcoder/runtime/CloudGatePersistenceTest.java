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
package com.swarmcoder.runtime;

import com.swarmcoder.runtime.CloudGate.Breach;
import com.swarmcoder.runtime.CloudGate.BudgetExhaustedException;
import com.swarmcoder.runtime.CloudGate.Cap;
import com.swarmcoder.runtime.CloudGate.Limits;
import com.swarmcoder.runtime.CloudGate.Where;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The gate's counts, extensions and parking breach survive closing and reopening the store. */
class CloudGatePersistenceTest {

    private final UUID project = UUID.randomUUID();
    private final UUID story = UUID.randomUUID();

    @Test
    void aProjectLimitHoldsAcrossARestartAndAnExtensionStillWorks(@TempDir Path dir)
            throws Exception {
        UUID firstRun = UUID.randomUUID();
        UUID secondRun = UUID.randomUUID();
        Limits limits = new Limits(Cap.NONE, Cap.NONE, Cap.total(1000));

        try (ArtifactStore store = new ArtifactStore(dir)) {
            CloudGate gate = new CloudGate(limits, b -> { });
            gate.persistTo(store);
            try (var in = CloudGate.enter(new Where(project, story, firstRun))) {
                gate.charge(700);
            }
        }

        List<Breach> raised = new ArrayList<>();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            CloudGate gate = new CloudGate(limits, raised::add);
            gate.persistTo(store);
            assertThat(gate.spendOfProject(project).total()).isEqualTo(700);
            assertThat(gate.spendOfRun(firstRun).total()).isEqualTo(700);
            try (var in = CloudGate.enter(new Where(project, story, secondRun))) {
                assertThatThrownBy(() -> gate.charge(400))
                    .isInstanceOf(BudgetExhaustedException.class);
            }
            assertThat(raised).hasSize(1);
        }

        raised.clear();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            CloudGate gate = new CloudGate(limits, raised::add);
            gate.persistTo(store);
            assertThat(gate.spendOfProject(project).total()).isEqualTo(1100);
            Where where = new Where(project, story, secondRun);
            assertThat(gate.parkingBreach(where)).isPresent();
            assertThat(raised)
                .describedAs("the question already exists; a restart does not raise it again")
                .isEmpty();

            var extension = gate.extendForRun(secondRun);
            assertThat(extension).isPresent();
            assertThat(extension.get().limitNow().total()).isEqualTo(2000);
            assertThat(gate.standing(where)).isEmpty();
        }

        try (ArtifactStore store = new ArtifactStore(dir)) {
            CloudGate gate = new CloudGate(limits, b -> { });
            gate.persistTo(store);
            assertThat(gate.standing(new Where(project, story, secondRun)))
                .describedAs("the extension was saved")
                .isEmpty();
            assertThat(gate.extendForRun(secondRun))
                .describedAs("an extension is used up once").isEmpty();
        }
    }
}
