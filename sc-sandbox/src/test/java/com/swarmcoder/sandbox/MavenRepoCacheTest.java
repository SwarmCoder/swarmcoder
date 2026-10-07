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
package com.swarmcoder.sandbox;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the artifact cache that decide what gets copied and what the copy script says.
 * No Docker daemon needed.
 *
 * <p>The list of paths comes out of a Maven debug log produced by a build of the operator's own
 * project — trusted, but scraped text all the same, and it is interpolated into a shell script
 * that runs in the one container where the cache is writable. Everything below exists so that
 * script can only ever name real artifact files inside the read-only host repository mount.
 */
class MavenRepoCacheTest {

    private static final String REPO = DockerSandboxManager.HOST_M2_MOUNT + "/repository";

    @Test
    void keepsRealArtifactPathsAndDropsEverythingElse() {
        List<String> kept = MavenRepoCache.validated(List.of(
            REPO + "/org/teavm/teavm-classlib/0.15.0/teavm-classlib-0.15.0.jar",
            REPO + "/com/zeroz4j/zerozstack-apt/0.8.0-SNAPSHOT/zerozstack-apt-0.8.0-SNAPSHOT.jar",
            // Not under the repository mount at all.
            "/etc/passwd",
            "/opt/m2-cache/repository/evil/evil.jar",
            // Escapes upward.
            REPO + "/../../etc/shadow",
            // Shell metacharacters: the operator's own repository really does contain a directory
            // named "${quarkus", and nothing with a $, a quote, a backtick or a space may reach
            // the copy script.
            REPO + "/${quarkus/x/1.0/x-1.0.jar",
            REPO + "/a b/c/1.0/c-1.0.jar",
            REPO + "/x/`whoami`/1.0/x-1.0.jar",
            REPO + "/x;rm -rf ~/1.0/x-1.0.jar",
            // A bare directory would drag in everything beneath it.
            REPO + "/org/teavm/",
            "   "));

        assertThat(kept).containsExactly(
            REPO + "/org/teavm/teavm-classlib/0.15.0/teavm-classlib-0.15.0.jar",
            REPO + "/com/zeroz4j/zerozstack-apt/0.8.0-SNAPSHOT/zerozstack-apt-0.8.0-SNAPSHOT.jar");
    }

    @Test
    void deduplicatesSoOneArtifactIsCopiedOnce() {
        String jar = REPO + "/org/teavm/teavm-classlib/0.15.0/teavm-classlib-0.15.0.jar";
        assertThat(MavenRepoCache.validated(List.of(jar, jar, jar))).containsExactly(jar);
    }

    @Test
    void onlyMavenCommandsAreReplayed() {
        assertThat(MavenRepoCache.mavenCommands(Arrays.asList(
            "mvn -o -q -B compile test-compile",
            "npm run build",
            "  mvn -o -B test  ",
            null)))
            .containsExactly("mvn -o -q -B compile test-compile", "mvn -o -B test");
    }

    /**
     * Discovery must not leave a mark on the operator's checkout: it is mounted read-only and
     * copied onto the container's own tmpfs before anything runs, because the commands being
     * replayed start with {@code clean}.
     */
    @Test
    void discoveryBuildsACopyAndNeverTheOperatorsOwnTree() {
        String script = MavenRepoCache.discoveryScript(List.of("mvn -o -q -B clean compile"));

        assertThat(script).contains("tar -C /opt/src").contains("-C /tmp/ws").contains("cd /tmp/ws");
        // -q and -X contradict each other, and -X is the whole point of the pass.
        assertThat(script).contains("mvn -o -B clean compile -X").doesNotContain("-q");
        assertThat(script).contains("grep -ohE '" + REPO);
    }

    @Test
    void theCopyScriptOnlyNamesValidatedPathsAndPreservesModificationTimes() {
        String jar = REPO + "/org/teavm/teavm-classlib/0.15.0/teavm-classlib-0.15.0.jar";
        String script = MavenRepoCache.populateScript(List.of(jar));

        assertThat(script).contains(jar);
        // -u re-copies a SNAPSHOT the operator rebuilt since the last warm; -p is what makes -u
        // able to tell, because it keeps the source's modification time on the copy.
        assertThat(script).contains("cp -p -u");
        assertThat(script).contains("mkdir -p " + MavenRepoCache.MOUNT + "/repository");
    }
}
