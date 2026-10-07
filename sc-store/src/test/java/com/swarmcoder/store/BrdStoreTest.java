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
package com.swarmcoder.store;

import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.BrdRevision;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project's BRD — its requirement nodes and typed edges — survives a store reopen, and
 * {@code ensureBrd} is idempotent per project. Guards the v5 store schema.
 */
class BrdStoreTest {

    @Test
    void brdRevisionsRecordAttributedEvolutionAndSurviveReopen(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Brd brd = store.ensureBrd(projectId);
            // Revision A (human): one requirement.
            brd.setRequirements(new ArrayList<>(List.of(
                new BrdRequirement(UUID.randomUUID(), "R1", "Login", "email + password",
                    Priority.HIGH, RequirementStatus.DRAFT, null))));
            store.saveBrd(brd, "human", "added R1 (Login)");
            // Revision B (agent): a second requirement.
            List<BrdRequirement> reqs = new ArrayList<>(brd.requirements());
            reqs.add(new BrdRequirement(UUID.randomUUID(), "R2", "OAuth", "Google sign-in",
                Priority.MEDIUM, RequirementStatus.DRAFT, null));
            brd.setRequirements(reqs);
            store.saveBrd(brd, "agent", "added R2 (OAuth)");

            List<BrdRevision> history = store.listBrdRevisions(projectId);
            assertThat(history).hasSize(2);
            assertThat(history.get(0).author()).isEqualTo("human");
            assertThat(history.get(0).summary()).contains("R1");
            assertThat(history.get(1).author()).isEqualTo("agent");
            // Snapshots do NOT alias: the first revision still holds ONE requirement.
            assertThat(history.get(0).snapshot().requirements()).hasSize(1);
            assertThat(history.get(1).snapshot().requirements()).hasSize(2);
        }

        // History (and its snapshots) survive a store reopen.
        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            List<BrdRevision> history = reopened.listBrdRevisions(projectId);
            assertThat(history).hasSize(2);
            assertThat(history.get(0).snapshot().requirements()).hasSize(1);
            Brd snap = reopened.brdRevisionSnapshot(projectId, history.get(0).revision());
            assertThat(snap.requirements().get(0).handle()).isEqualTo("R1");
        }
    }

    @Test
    void brdGraphPersistsAcrossReopenAndEnsureIsIdempotent(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();

        try (ArtifactStore store = new ArtifactStore(dir)) {
            Brd brd = store.ensureBrd(projectId);
            assertThat(brd.requirements()).isEmpty();

            // ensureBrd is idempotent — same project returns the same BRD.
            assertThat(store.ensureBrd(projectId).id()).isEqualTo(brd.id());

            List<BrdRequirement> reqs = new ArrayList<>();
            reqs.add(new BrdRequirement(r1, "R1", "Login", "Users can log in with email + password",
                Priority.HIGH, RequirementStatus.ACTIVE, "Auth"));
            reqs.add(new BrdRequirement(r2, "R2", "OAuth", "Users can log in with Google",
                Priority.MEDIUM, RequirementStatus.DRAFT, "Auth"));
            brd.setRequirements(reqs);
            List<BrdEdge> edges = new ArrayList<>();
            edges.add(new BrdEdge(r2, r1, RequirementRelation.REFINES));
            brd.setEdges(edges);
            store.saveBrd(brd);
        }

        // Reopen: the requirement graph (nodes + typed edge) is intact.
        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            Brd loaded = reopened.getBrd(projectId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.projectId()).isEqualTo(projectId);
            assertThat(loaded.requirements()).hasSize(2);
            assertThat(loaded.requirements().get(0).handle()).isEqualTo("R1");
            assertThat(loaded.requirements().get(0).priority()).isEqualTo(Priority.HIGH);
            assertThat(loaded.requirements().get(1).status()).isEqualTo(RequirementStatus.DRAFT);
            assertThat(loaded.edges()).hasSize(1);
            assertThat(loaded.edges().get(0).relation()).isEqualTo(RequirementRelation.REFINES);
            assertThat(loaded.edges().get(0).from()).isEqualTo(r2);
            assertThat(loaded.edges().get(0).to()).isEqualTo(r1);
            // revision bumped: ensureBrd(save) then the explicit saveBrd = 2.
            assertThat(loaded.revision()).isGreaterThanOrEqualTo(2);
        }
    }
}
