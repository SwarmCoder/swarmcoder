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

import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ArtifactStore#backupTo} — a copy of a store that is still open and still being written,
 * that opens afterwards as an ordinary store.
 *
 * <p>Written 2026-09-25 for the live harness's save-at-build snapshot: the harness copies the store
 * at the moment a run is about to dispatch workers, and later runs start from that copy. A raw file
 * copy of an open EclipseStore is not a copy of anything — the channels append from their own
 * threads — so the copy has to be one EclipseStore makes itself, and it has to be queued behind
 * every write the caller already made. These tests pin both halves without a model or a
 * repository.
 */
class StoreBackupTest {

    @TempDir
    Path tmp;

    private static Run run(UUID id, RunState state) {
        return new Run(id, WorkflowKind.GREENFIELD, state, null, null, null, null, null,
            Instant.now(), new RunReport(id, "a goal"));
    }

    @Test
    void aBackupOfAnOpenStoreOpensAsAStoreHoldingEverythingWrittenBeforeIt() throws Exception {
        UUID runId = UUID.randomUUID();
        Path backup = tmp.resolve("backup");
        Project project;
        try (ArtifactStore store = new ArtifactStore(tmp.resolve("live"))) {
            project = store.ensureProject("demo", tmp.resolve("repo").toString(), List.of());
            Run run = run(runId, RunState.TEST_AUTHORING);
            store.append(() -> {
                store.root().runs.put(runId, run);
                return null;
            }).get();
            // The last thing written before the backup is NOT waited for: the backup must still
            // hold it, because it is queued behind it on the same writer.
            Run executing = run.withState(RunState.EXECUTING, null, null);
            store.append(() -> {
                store.root().runs.put(runId, executing);
                return null;
            });

            store.backupTo(backup);

            // The live store carries on after being backed up, and the copy does not follow it.
            Run later = executing.withState(RunState.FINAL_INTEGRATION, null, null);
            store.append(() -> {
                store.root().runs.put(runId, later);
                return null;
            }).get();
        }

        try (ArtifactStore restored = new ArtifactStore(backup)) {
            assertThat(restored.root().runs.get(runId)).isNotNull();
            assertThat(restored.root().runs.get(runId).state())
                .as("the copy holds the state at the moment of the backup, not after it")
                .isEqualTo(RunState.EXECUTING);
            assertThat(restored.getProject(project.id())).isNotNull();
            assertThat(restored.getProject(project.id()).name()).isEqualTo("demo");
        }
        try (ArtifactStore live = new ArtifactStore(tmp.resolve("live"))) {
            assertThat(live.root().runs.get(runId).state()).isEqualTo(RunState.FINAL_INTEGRATION);
        }
    }

    @Test
    void aCopyOfTheBackupOpensAgainAndAgainWithoutTheBackupChanging() throws Exception {
        UUID runId = UUID.randomUUID();
        Path backup = tmp.resolve("backup");
        try (ArtifactStore store = new ArtifactStore(tmp.resolve("live"))) {
            store.append(() -> {
                store.root().runs.put(runId, run(runId, RunState.EXECUTING));
                return null;
            }).get();
            store.backupTo(backup);
        }
        for (int i = 0; i < 2; i++) {
            Path copy = tmp.resolve("copy" + i);
            copyTree(backup, copy);
            try (ArtifactStore restored = new ArtifactStore(copy)) {
                assertThat(restored.root().runs.get(runId).state()).isEqualTo(RunState.EXECUTING);
                // Writing into the copy is ordinary use of a store...
                Run moved = restored.root().runs.get(runId)
                    .withState(RunState.FINAL_INTEGRATION, null, null);
                restored.append(() -> {
                    restored.root().runs.put(runId, moved);
                    return null;
                }).get();
            }
        }
        // ...and never reaches the backup it was copied from.
        try (ArtifactStore original = new ArtifactStore(copyOf(backup, "check"))) {
            assertThat(original.root().runs.get(runId).state()).isEqualTo(RunState.EXECUTING);
        }
    }

    @Test
    void aBackupRefusesATargetThatAlreadyHoldsSomething() throws Exception {
        Path occupied = Files.createDirectories(tmp.resolve("occupied"));
        Files.writeString(occupied.resolve("something.txt"), "not a store");
        try (ArtifactStore store = new ArtifactStore(tmp.resolve("live"))) {
            assertThatThrownBy(() -> store.backupTo(occupied))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already holds");
        }
    }

    private Path copyOf(Path from, String name) throws Exception {
        Path to = tmp.resolve(name);
        copyTree(from, to);
        return to;
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }
}
