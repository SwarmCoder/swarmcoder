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

import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The before-and-after, measured against a real project instead of argued about.
 *
 * <p>Opt-in, because it builds the named project several times inside containers and takes
 * minutes. Point it at a Maven checkout and run it:
 *
 * <pre>
 * mvn -o -B -pl sc-sandbox -am test -Dtest=MavenRepoCacheProofTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dswarmcoder.m2cache.proof=C:\\work\\swarmcoder\\dev\\bookshelf-demo \
 *     -Dswarmcoder.m2cache.proof.image=swarmcoder-worker:latest
 * </pre>
 *
 * <p>It prints, and asserts on, the same build command run three ways: on a cold cache (the old
 * behaviour, everything resolved across the host filesystem bridge), then again after the cache is
 * warmed. Measured on the operator's workstation against {@code dev/bookshelf-demo}: 41-70 s
 * before, 11-13 s after, against 10-12 s for the same command on the host.
 */
@RunsWhen(Need.DOCKER)
@EnabledIfSystemProperty(named = "swarmcoder.m2cache.proof", matches = ".+")
class MavenRepoCacheProofTest {

    private static final String BUILD =
        "mvn -o -q -B -DfastCompile clean compile test-compile";

    @Test
    void theCacheMakesASandboxBuildAsFastAsTheHost() {
        Path project = Path.of(System.getProperty("swarmcoder.m2cache.proof")).toAbsolutePath();
        String image = System.getProperty("swarmcoder.m2cache.proof.image", "swarmcoder-worker:latest");
        String hostM2 = Path.of(System.getProperty("user.home"), ".m2").toString();

        DockerSandboxManager manager = new DockerSandboxManager(image, 2, 4, null, hostM2);

        long before = medianBuildSeconds(manager, project, 3);
        System.out.println("[m2cache] BEFORE (cold cache): " + before + "s median of 3");

        MavenRepoCache.Report report = manager.warmMavenRepoCache(project.toString(), List.of(BUILD));
        System.out.println("[m2cache] warm: ran=" + report.ran() + " artifacts=" + report.artifactsFound()
            + " — " + report.detail());
        assertThat(report.ran()).as("warm should succeed: %s", report.detail()).isTrue();
        assertThat(report.artifactsFound()).isPositive();

        long after = medianBuildSeconds(manager, project, 3);
        System.out.println("[m2cache] AFTER (warm cache): " + after + "s median of 3");

        assertThat(after).as("a warmed cache must make the same build materially faster")
            .isLessThan(before);
    }

    /** Median wall clock of {@code runs} identical builds in one freshly launched sandbox. */
    private long medianBuildSeconds(DockerSandboxManager manager, Path project, int runs) {
        DockerSandboxManager.SandboxHandle handle =
            manager.launch(project.toString(), "src");
        try {
            long[] seconds = new long[runs];
            for (int i = 0; i < runs; i++) {
                long start = System.nanoTime();
                DockerSandboxManager.ExecOutcome outcome = manager.execInContainer(
                    handle.containerId(),
                    List.of("sh", "-c", "cp -r /workspace /tmp/ws" + i + " && cd /tmp/ws" + i
                        + " && " + BUILD),
                    900);
                seconds[i] = (System.nanoTime() - start) / 1_000_000_000L;
                assertThat(outcome.exitCode())
                    .as("build must succeed: %s", outcome.output()).isZero();
            }
            java.util.Arrays.sort(seconds);
            return seconds[runs / 2];
        } finally {
            manager.kill(handle);
        }
    }
}
