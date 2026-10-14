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
package com.swarmcoder.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 113, finding 1: workers searched the offline Maven repository
 * with {@code find} 65 times to learn which libraries and versions exist, since their brief
 * listed none. {@code offline_artifacts} answers it from the repository's folders, and a brief of
 * a project with no build file says how to ask.
 */
class TheOfflineRepositoryIsALookupTest {

    @TempDir
    Path m2;

    @Test
    void whichArtifactsAndVersionsTheOfflineRepositoryHoldsIsALookup() throws Exception {
        install("org.example.store", "store-core", "2.4", true);
        install("org.example.store", "store-core", "2.10", true);
        install("org.example.store", "store-bom", "2.10", false);
        install("org.example.store.extra", "store-tools", "1.0", true);
        install("com.other", "store-core", "9", true);

        String group = OfflineRepository.answer(m2, "org.example.store");
        assertThat(group).startsWith(OfflineRepository.HEAD)
            .contains("org.example.store:store-core  2.4, 2.10")
            .as("a version folder with no jar is said to be a pom only")
            .contains("org.example.store:store-bom  2.10 (pom only)")
            .contains("Groups under org.example.store: org.example.store.extra");

        assertThat(OfflineRepository.answer(m2, "org.example.store:store-core:3.0"))
            .contains("org.example.store:store-core  2.4, 2.10")
            .contains("Version 3.0 is NOT held");

        assertThat(OfflineRepository.answer(m2, "store-core"))
            .as("an artifact id alone: every group that has it")
            .contains("com.other:store-core  9").contains("org.example.store:store-core  2.4");

        assertThat(OfflineRepository.answer(m2, "tools"))
            .contains("No artifact is called exactly `tools`")
            .contains("org.example.store.extra:store-tools");

        assertThat(OfflineRepository.answer(m2, "")).contains("com, org");

        assertThat(OfflineRepository.answer(m2.resolve("absent"), "x"))
            .contains("no offline Maven repository");

        assertThat(OfflineLibraryBrief.noBuildYet().render())
            .as("the brief of a project with no build file says how to ask, not nothing")
            .contains("offline_artifacts").contains("No build file of this project names");
    }

    private void install(String group, String artifact, String version, boolean jar)
            throws Exception {
        Path dir = Files.createDirectories(m2.resolve(group.replace('.', '/')).resolve(artifact)
            .resolve(version));
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), "<project/>");
        if (jar) {
            Files.write(dir.resolve(artifact + "-" + version + ".jar"), new byte[] {1});
        }
    }
}
