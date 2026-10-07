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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Projects survive a store reopen (multi-project foundation), and {@code ensureProject} is
 * idempotent by primary path so migrating the historical single-project setup is safe.
 */
class ProjectStoreTest {

    @Test
    void projectsPersistAcrossReopenAndEnsureIsIdempotent(@TempDir Path dir) throws Exception {
        UUID id;
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Project created = store.ensureProject("demo", "C:/work/demo", List.of("C:/work/zeroz4j"));
            id = created.id();
            // Idempotent: same primary path (even non-normalized) returns the same project.
            Project again = store.ensureProject("demo-again", "C:/work/demo/", List.of());
            assertThat(again.id()).isEqualTo(id);
            assertThat(store.listProjects()).hasSize(1);
        }

        // Reopen: the project (and its context folders) is still there.
        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            Project loaded = reopened.getProject(id);
            assertThat(loaded).isNotNull();
            assertThat(loaded.name()).isEqualTo("demo");
            assertThat(loaded.contextPaths()).containsExactly("C:/work/zeroz4j");
            assertThat(reopened.listProjects()).hasSize(1);
        }
    }

    @Test
    void archivedProjectsAreHiddenFromTheList(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Project p = store.ensureProject("temp", "C:/work/temp", List.of());
            p.setArchived(true);
            store.saveProject(p);
            assertThat(store.listProjects()).isEmpty();
            assertThat(store.getProject(p.id())).isNotNull(); // still retrievable by id
        }
    }
}
