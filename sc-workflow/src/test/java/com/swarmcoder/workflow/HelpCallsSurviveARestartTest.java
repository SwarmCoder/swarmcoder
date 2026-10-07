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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code WhatARunWritesDownSurvivesARestartTest}, but for one field that test does not touch: a
 * candidate's {@link CandidateSolution#helpCalls() helpCalls} — what it asked the help desk's
 * expert tier and where each answer came from.
 *
 * <h2>Why this is checked on its own</h2>
 *
 * <p>{@code helpCalls} is recorded by {@code WorkerLoop} on every candidate outcome and is one of
 * the three fields {@link CandidateSolution#carryingAuditFrom} exists to keep alive: clustering,
 * verification, judging and selection each rebuild a {@code CandidateSolution} through its
 * constructor, and that constructor does not take {@code helpCalls} — so a stage that forgets to
 * call {@code carryingAuditFrom} silently drops it, exactly as a stage that forgot to store the
 * archived candidate's own object (rather than a copy) would silently drop it across a restart.
 * {@code SwarmEngineFakeVllmTest} proves the first kind of drop cannot happen to
 * {@code outOfWriteSetPaths}; this proves the second kind cannot happen to {@code helpCalls} — the
 * EclipseStore write survives a process restart the same way a parked {@code Run}'s reason does
 * (see {@code WorkflowPersistenceTest}).
 */
class HelpCallsSurviveARestartTest {

    @TempDir
    Path storeDir;

    @Test
    void aCandidatesRecordedAsksSurviveAStoreReopen() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        List<String> helpCalls = List.of(
            "1. answered from this codebase's own code (free)",
            "2. answered by the expert model (612 tokens)");

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            CandidateSolution candidate = new CandidateSolution(candidateId, taskId, 0,
                "swarm/" + taskId + "/0", null, "diff", null, null, null,
                CandidateState.SURVIVED, null);
            candidate.setHelpCalls(helpCalls);

            store.append(() -> {
                store.root().candidateArchives.put(candidateId, Lazy.Reference(candidate));
                return null;
            }).get();
        }

        // "Restart": a fresh store instance over the same directory.
        try (ArtifactStore reopened = new ArtifactStore(storeDir)) {
            CandidateSolution archived =
                (CandidateSolution) Lazy.get(reopened.root().candidateArchives.get(candidateId));

            assertThat(archived).as("the archived candidate").isNotNull();
            assertThat(archived.helpCalls())
                .as("what it asked the expert for, and where each answer came from — kept because "
                    + "the worker's toolbox that recorded it is gone by the time the judge, the "
                    + "operator's run graph or this test reads the candidate back")
                .containsExactly(
                    "1. answered from this codebase's own code (free)",
                    "2. answered by the expert model (612 tokens)");
        }
    }
}
