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
package com.swarmcoder.console;

import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdNodePosition;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the operator dragged a requirement node to is part of the document, not of the session.
 *
 * <p>These are the server-side guarantees the whole feature rests on: the position outlives the
 * process, it can only ever be recorded for a requirement that actually exists, and "Re-layout"
 * genuinely forgets it rather than merely hiding it. If any of the three failed, hand-placing a node
 * would be a gesture that quietly does nothing — the worst possible outcome, because the operator
 * only finds out after arranging a whole graph.
 */
class BrdNodePositionTest {

    @Test
    void aDroppedNodeSurvivesAReopenAndReLayoutForgetsIt(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID requirementId;

        try (ArtifactStore store = new ArtifactStore(dir)) {
            useProject(store, projectId);
            BrdServiceImpl brd = new BrdServiceImpl();
            assertThat(brd.saveRequirement(new BrdRequirement(null, null, "Login", "Users can log in",
                Priority.HIGH, RequirementStatus.DRAFT, null))).isEmpty();
            requirementId = brd.brd().requirements().get(0).id();
            assertThat(brd.brd().nodePositions()).isEmpty(); // nothing placed by hand yet

            assertThat(brd.saveNodePosition(requirementId.toString(), 412.5, 260)).isEmpty();
            List<BrdNodePosition> positions = brd.brd().nodePositions();
            assertThat(positions).hasSize(1);
            assertThat(positions.get(0).requirementId()).isEqualTo(requirementId);
            assertThat(positions.get(0).x()).isEqualTo(412.5);
            assertThat(positions.get(0).y()).isEqualTo(260);

            // Moving the same node again REPLACES its position. Two entries for one node is a graph
            // that draws differently depending on which one the reader happens to hit first.
            assertThat(brd.saveNodePosition(requirementId.toString(), 100, 50)).isEmpty();
            assertThat(brd.brd().nodePositions()).hasSize(1);
            assertThat(brd.brd().nodePositions().get(0).x()).isEqualTo(100);

            // Editing the requirement does NOT disturb where it was put — this is the whole promise
            // of hand-placing, and the edit path rebuilds the requirement list on every save.
            BrdRequirement live = brd.brd().requirements().get(0);
            assertThat(brd.saveRequirement(new BrdRequirement(live.id(), live.handle(), live.title(),
                "Users can log in with email and password", live.priority(), live.status(),
                live.category()))).isEmpty();
            assertThat(brd.brd().nodePositions()).hasSize(1);
            assertThat(brd.brd().nodePositions().get(0).x()).isEqualTo(100);
        }

        // The document's, not the session's: still there in a fresh process.
        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            Brd loaded = reopened.getBrd(projectId);
            assertThat(loaded.nodePositions()).hasSize(1);
            assertThat(loaded.nodePositions().get(0).requirementId()).isEqualTo(requirementId);
            assertThat(loaded.nodePositions().get(0).x()).isEqualTo(100);
            assertThat(loaded.nodePositions().get(0).y()).isEqualTo(50);

            // The copy that goes onto the shared signal carries the positions. Without this the
            // editor would receive every node unplaced and auto-lay-out the operator's arrangement
            // on the very next edit — a silent, total loss of the feature.
            assertThat(ArtifactStore.copyOf(loaded).nodePositions()).hasSize(1);
            assertThat(ArtifactStore.copyOf(loaded).nodePositions().get(0).x()).isEqualTo(100);

            useProject(reopened, projectId);
            BrdServiceImpl brd = new BrdServiceImpl();
            assertThat(brd.clearNodePositions()).isEmpty();
            assertThat(brd.brd().nodePositions()).isEmpty();
        }

        // …and forgetting them is durable too, or one restart would undo the Re-layout.
        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            assertThat(reopened.getBrd(projectId).nodePositions()).isEmpty();
        }
    }

    @Test
    void aPositionForAnUnknownRequirementIsRefusedRatherThanStored(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            useProject(store, projectId);
            BrdServiceImpl brd = new BrdServiceImpl();
            assertThat(brd.saveRequirement(new BrdRequirement(null, null, "Login", "…",
                Priority.HIGH, RequirementStatus.DRAFT, null))).isEmpty();
            UUID known = brd.brd().requirements().get(0).id();
            assertThat(brd.saveNodePosition(known.toString(), 10, 10)).isEmpty();

            // An orphan position is one nothing can ever move, correct or clear — and it would
            // outlive every requirement in the BRD. So this is an error, not a quiet no-op.
            assertThat(brd.saveNodePosition(UUID.randomUUID().toString(), 40, 40))
                .startsWith("error");
            assertThat(brd.saveNodePosition("not-a-uuid", 40, 40)).startsWith("error");
            assertThat(brd.saveNodePosition(null, 40, 40)).startsWith("error");
            assertThat(brd.brd().nodePositions()).hasSize(1);
            assertThat(brd.brd().nodePositions().get(0).requirementId()).isEqualTo(known);

            // A node cannot be parked outside the fitted content box, where nothing could reach it
            // to drag it back.
            assertThat(brd.saveNodePosition(known.toString(), -800, -40)).isEmpty();
            assertThat(brd.brd().nodePositions().get(0).x()).isEqualTo(0);
            assertThat(brd.brd().nodePositions().get(0).y()).isEqualTo(0);

            // Taking the requirement out of scope KEEPS its position, because it keeps the
            // requirement. Nothing in the requirements is destroyed any more (author decision
            // 2026-08-28): retiring stops it counting and takes it out of the normal view, and
            // where its box sits is exactly what somebody needs if they bring it back.
            assertThat(brd.retireRequirement(known.toString())).isEmpty();
            assertThat(brd.brd().nodePositions()).hasSize(1);
            assertThat(brd.brd().requirements()).hasSize(1);
            assertThat(brd.brd().requirements().get(0).isRetired()).isTrue();
        }
    }

    private static void useProject(ArtifactStore store, UUID projectId) {
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));
    }
}
