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

import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.workflow.RunPersister;
import com.swarmcoder.workflow.WorkflowEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The restart points before the build, proved without a model (2026-10-01): the stage seam fires
 * on the workflow's own persist of a state transition, once per run and state, each snapshot
 * restores into a store whose run is in the state the point names and which the engine's own
 * unfinished-run query returns, and the manifest carries the point and the document hashes.
 *
 * <p>The resume paths through the live harness are NOT exercised here — they need the model. What
 * is exercised is everything they stand on: the seam, the save, the restore, the manifest and the
 * product's own query for runs to pick up ({@code RunResumer} is state-agnostic and was proved at
 * EXECUTING by {@code SaveAtDispatchThenResumeTest}).
 */
class RestartPointsTest {

    @TempDir
    Path tmp;

    private Path newRepo() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("original").resolve("bookshelf"));
        BookshelfFixture.git(repo, "init -q -b master");
        Files.writeString(repo.resolve("pom.xml"), "<project/>\n");
        BookshelfFixture.git(repo, "add -A");
        BookshelfFixture.git(repo, "-c user.email=t@local -c user.name=t commit -q -m Baseline");
        return repo;
    }

    private static HarnessSnapshot.Manifest manifest(Run run, Path repo, RestartPoint point,
                                                     List<HarnessSnapshot.Link> links)
            throws Exception {
        return new HarnessSnapshot.Manifest(HarnessSnapshot.FORMAT, Instant.now().toString(),
            run.id(), run.projectId(), null, "abc1234", List.of(), "http://127.0.0.1:9/v1",
            "no-model", null, 2, "origin", null,
            BookshelfFixture.git(repo, "rev-parse HEAD").strip(), null, "reference", links,
            HarnessSnapshot.registeredWorktrees(repo), point.folder(),
            HarnessSnapshot.documentHash("business text"),
            HarnessSnapshot.documentHash("technical text"));
    }

    @Test
    void everyPointBeforeTheBuildIsSavedOnceAsTheRunEntersItsStateAndRestoresThere()
            throws Exception {
        Path repo = newRepo();
        Path saveAt = tmp.resolve("saves");
        List<HarnessSnapshot.Link> links = List.of(
            new HarnessSnapshot.Link("link one", "observed one"));
        Map<RunState, Run> seenAtTheSeam = new EnumMap<>(RunState.class);
        UUID runId = UUID.randomUUID();
        UUID projectId;

        ArtifactStore[] opened = new ArtifactStore[1];
        Map<RunState, Consumer<Run>> actions = new EnumMap<>(RunState.class);
        for (RestartPoint point : RestartPoint.values()) {
            if (point == RestartPoint.BUILD) {
                continue;
            }
            actions.put(point.runState(), entered -> {
                // What the store holds at this instant is what the snapshot will hold.
                seenAtTheSeam.put(entered.state(), opened[0].root().runs.get(entered.id()));
                try {
                    HarnessSnapshot.save(saveAt.resolve(point.folder()), opened[0], repo,
                        manifest(entered, repo, point, links));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        StageSeam seam = new StageSeam(actions);

        try (ArtifactStore store = new StageSeam.Store(tmp.resolve("original").resolve("store"),
                seam)) {
            opened[0] = store;
            Project project = store.ensureProject("bookshelf", repo.toString(), List.of());
            projectId = project.id();
            RunPersister persister = new RunPersister(store);
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE, projectId, null,
                null, null, null, Instant.now(), new RunReport(runId, "rate a book"));

            run = persister.save(run);
            assertThat(seam.reached(runId, RunState.DESIGN)).as("INTAKE is no restart point")
                .isFalse();
            run = persister.save(run.withState(RunState.DESIGN, null, null));
            run = persister.save(run.withState(RunState.DESIGN_REVIEW, null, null));
            // a revised design goes back to DESIGN: that is not the moment point 1 is about
            run = persister.save(run.withState(RunState.DESIGN, null, null));
            run = persister.save(run.withState(RunState.DESIGN_REVIEW, null, null));
            run = persister.save(run.withState(RunState.PLAN, null, null));
            run = persister.save(run.withState(RunState.TEST_AUTHORING, null, null));
            persister.save(run.withState(RunState.EXECUTING, null, null));

            assertThat(seenAtTheSeam.keySet()).containsExactlyInAnyOrder(RunState.DESIGN,
                RunState.PLAN, RunState.TEST_AUTHORING);
            assertThat(seenAtTheSeam.values()).allSatisfy(r -> assertThat(r).isNotNull());
        }

        for (RestartPoint point : List.of(RestartPoint.REQUIREMENTS, RestartPoint.DESIGN,
                RestartPoint.PLAN)) {
            Path snapshot = saveAt.resolve(point.folder());
            HarnessSnapshot.Manifest read = HarnessSnapshot.readManifest(snapshot);
            assertThat(read.restartPoint()).isEqualTo(point);
            assertThat(read.runId()).isEqualTo(runId);
            assertThat(read.businessDocSha256()).isEqualTo(HarnessSnapshot.documentHash("business text"));
            assertThat(read.links()).containsExactlyElementsOf(links);

            HarnessSnapshot.Restored restored = HarnessSnapshot.restore(snapshot,
                tmp.resolve("resumed-" + point.folder()), tmp.resolve("wt"));
            try (ArtifactStore store = new ArtifactStore(restored.store())) {
                HarnessSnapshot.repointProject(store, projectId, restored.repo());
                assertThat(store.root().runs.get(runId).state())
                    .as("the snapshot is the moment of entering " + point.runState()
                        + ", not a later state").isEqualTo(point.runState());
                WorkflowEngine engine = new WorkflowEngine(null, run -> {
                    throw new UnsupportedOperationException("no workers here");
                }, null, store, new CloudGate(0, null), restored.repo(), null, null, null,
                    List.of());
                assertThat(engine.unfinishedRunsOf(projectId)).singleElement().satisfies(r -> {
                    assertThat(r.id()).isEqualTo(runId);
                    assertThat(r.state()).isEqualTo(point.runState());
                });
            }
        }
    }

    @Test
    void aSnapshotFromBeforeRestartPointsIsTheBuildPointWithNoHashesToCompare() throws Exception {
        Path repo = newRepo();
        Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
            UUID.randomUUID(), null, null, null, null, Instant.now(), new RunReport(UUID.randomUUID(), "g"));
        HarnessSnapshot.Manifest old = new HarnessSnapshot.Manifest(HarnessSnapshot.FORMAT,
            "2026-09-25T10:00:00Z", run.id(), run.projectId(), null, "abc", List.of(), "u", "m",
            null, 2, "o", null, "base", null, "ref", List.of(), List.of());
        assertThat(old.restartPoint()).isEqualTo(RestartPoint.BUILD);
        assertThat(HarnessSnapshot.documentWarnings(old, "anything", "anything")).isEmpty();
        assertThat(repo).exists();
    }

    @Test
    void aChangedInputDocumentIsAWarningPerDocumentAndNeverARefusal() throws Exception {
        Path repo = newRepo();
        Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.DESIGN,
            UUID.randomUUID(), null, null, null, null, Instant.now(), new RunReport(UUID.randomUUID(), "g"));
        HarnessSnapshot.Manifest saved = manifest(run, repo, RestartPoint.REQUIREMENTS, List.of());

        assertThat(HarnessSnapshot.documentWarnings(saved, "business text", "technical text"))
            .isEmpty();
        assertThat(HarnessSnapshot.documentWarnings(saved, "business text EDITED", "technical text"))
            .singleElement().asString().contains("business document differs");
        assertThat(HarnessSnapshot.documentWarnings(saved, "business text EDITED", "other"))
            .hasSize(2);
    }

    @Test
    void theSavePlanNamesOneFolderPerPointAndTheOldPropertyOwnsTheBuildPoint() {
        String saveAt = "swarmcoder.e2e.saveAt";
        String saveAtBuild = "swarmcoder.e2e.saveAtBuild";
        try {
            System.setProperty(saveAt, tmp.resolve("all").toString());
            System.clearProperty(saveAtBuild);
            Map<RestartPoint, Path> plan = HarnessSnapshot.savePlan();
            assertThat(plan).containsOnlyKeys(RestartPoint.values());
            assertThat(plan.get(RestartPoint.REQUIREMENTS).getFileName().toString())
                .isEqualTo("1-requirements");
            assertThat(plan.get(RestartPoint.BUILD).getFileName().toString()).isEqualTo("4-build");

            System.setProperty(saveAtBuild, tmp.resolve("build-only").toString());
            assertThat(HarnessSnapshot.savePlan().get(RestartPoint.BUILD).getFileName().toString())
                .isEqualTo("build-only");

            System.clearProperty(saveAt);
            assertThat(HarnessSnapshot.savePlan()).containsOnlyKeys(RestartPoint.BUILD);
        } finally {
            System.clearProperty(saveAt);
            System.clearProperty(saveAtBuild);
        }
    }

    @Test
    void aSaveAtFolderIsRefusedWithTheNamesOfThePointsInIt() throws Exception {
        Path repo = newRepo();
        Path saveAt = tmp.resolve("saves");
        Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.PLAN,
            UUID.randomUUID(), null, null, null, null, Instant.now(), new RunReport(UUID.randomUUID(), "g"));
        try (ArtifactStore store = new ArtifactStore(tmp.resolve("store"))) {
            HarnessSnapshot.save(saveAt.resolve("2-design"), store, repo,
                manifest(run, repo, RestartPoint.DESIGN, List.of()));
        }
        assertThatThrownBy(() -> HarnessSnapshot.readManifest(saveAt))
            .hasMessageContaining("saveAt folder").hasMessageContaining("2-design");
        assertThat(HarnessSnapshot.readManifest(saveAt.resolve("2-design")).restartPoint())
            .isEqualTo(RestartPoint.DESIGN);
    }

    @Test
    void aResumedRunSavesOnlyTheLaterPointsAndNeverRewritesWhereItStarted() throws Exception {
        Path repo = newRepo();
        Path saveAt = tmp.resolve("saves");
        Map<RestartPoint, Path> full = new EnumMap<>(RestartPoint.class);
        for (RestartPoint point : RestartPoint.values()) {
            full.put(point, saveAt.resolve(point.folder()));
        }
        assertThat(HarnessSnapshot.savePlanAfter(full, RestartPoint.REQUIREMENTS))
            .containsOnlyKeys(RestartPoint.DESIGN, RestartPoint.PLAN, RestartPoint.BUILD);
        assertThat(HarnessSnapshot.savePlanAfter(full, RestartPoint.DESIGN))
            .containsOnlyKeys(RestartPoint.PLAN, RestartPoint.BUILD);
        assertThat(HarnessSnapshot.savePlanAfter(full, RestartPoint.BUILD)).isEmpty();

        // The saving run is resumed from 2-design, which already lives in the same saveAt folder.
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Run resumed = new Run(runId, WorkflowKind.GREENFIELD, RunState.PLAN, projectId, null,
            null, null, null, Instant.now(), new RunReport(runId, "rate a book"));
        List<HarnessSnapshot.Link> restoredLinks = List.of(
            new HarnessSnapshot.Link("link one", "observed one"));
        try (ArtifactStore earlier = new ArtifactStore(tmp.resolve("earlier"))) {
            HarnessSnapshot.save(saveAt.resolve("2-design"), earlier, repo,
                manifest(resumed, repo, RestartPoint.DESIGN, restoredLinks));
        }
        Path designMarker = saveAt.resolve("2-design").resolve("untouched.txt");
        Files.writeString(designMarker, "x");
        HarnessSnapshot.Manifest from = HarnessSnapshot.readManifest(saveAt.resolve("2-design"));
        String provenance = from.provenance(saveAt.resolve("2-design"));

        Map<RestartPoint, Path> later = HarnessSnapshot.savePlanAfter(full, from.restartPoint());
        ArtifactStore[] opened = new ArtifactStore[1];
        Map<RunState, Consumer<Run>> actions = new EnumMap<>(RunState.class);
        later.forEach((point, target) -> {
            if (point != RestartPoint.BUILD) {
                actions.put(point.runState(), entered -> {
                    try {
                        HarnessSnapshot.Manifest m = manifest(entered, repo, point, restoredLinks);
                        HarnessSnapshot.save(target, opened[0], repo, new HarnessSnapshot.Manifest(
                            m.format(), m.savedAt(), m.runId(), m.projectId(), m.storyId(),
                            m.swarmcoderCommit(), m.swarmcoderUncommitted(), m.baseUrl(), m.model(),
                            m.shape(), m.workers(), m.fixtureOrigin(), m.fixtureOriginHead(),
                            m.fixtureBaseCommit(), m.testsCommit(), m.referenceRoot(), m.links(),
                            m.worktreesNotCarried(), m.point(), from.businessDocSha256(),
                            from.technicalDocSha256(), provenance));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        });
        StageSeam seam = new StageSeam(actions);
        try (ArtifactStore store = new StageSeam.Store(tmp.resolve("resumed"), seam)) {
            opened[0] = store;
            store.ensureProject("bookshelf", repo.toString(), List.of());
            RunPersister persister = new RunPersister(store);
            // the resume itself re-persists the run in the state it was saved in
            Run run = persister.save(resumed);
            run = persister.save(run.withState(RunState.TEST_AUTHORING, null, null));
            persister.save(run.withState(RunState.EXECUTING, null, null));
        }

        assertThat(seam.reached(runId, RunState.PLAN)).as("the resume point is not re-saved")
            .isFalse();
        assertThat(designMarker).exists();
        assertThat(saveAt.resolve("1-requirements")).doesNotExist();
        HarnessSnapshot.Manifest saved = HarnessSnapshot.readManifest(saveAt.resolve("3-plan"));
        assertThat(saved.resumedFrom()).isEqualTo(provenance).contains("2-design");
        assertThat(saved.restartPoint()).isEqualTo(RestartPoint.PLAN);
        assertThat(saved.links()).containsExactlyElementsOf(restoredLinks);
        assertThat(saved.businessDocSha256()).isEqualTo(from.businessDocSha256());
        assertThat(from.resumedFrom()).as("a full run records no resume").isNull();
    }
}
