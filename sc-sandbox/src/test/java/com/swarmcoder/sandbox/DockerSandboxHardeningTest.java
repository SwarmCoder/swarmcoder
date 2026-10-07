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

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the sandbox hardening flags (spec §8.2) without needing a Docker daemon: the
 * DockerClient connects lazily, so the HostConfig a launch would send can be built and inspected
 * offline. The live behaviour of these flags inside a real container is asserted separately by
 * {@code DockerSandboxLiveTest}.
 *
 * <p>These assertions are deliberately blunt — a regression here silently removes the only thing
 * standing between model-authored shell commands and the workstation.
 */
class DockerSandboxHardeningTest {

    private static final String WORKTREE = "/home/op/.swarmcoder/wt/candidate-1";
    private static final String HOST_M2 = "/home/op/.m2";

    private static DockerSandboxManager manager() {
        return new DockerSandboxManager("swarmcoder-worker:latest", 2, 4, null, HOST_M2);
    }

    @Test
    void defaultsToNoNetwork() {
        assertThat(manager().network()).isEqualTo(DockerSandboxManager.NetworkPolicy.NONE);
        assertThat(manager().buildHostConfig(WORKTREE, null).getNetworkMode()).isEqualTo("none");
    }

    @Test
    void requiredIsTheDefault() {
        assertThat(manager().isRequired()).isTrue();
    }

    @Test
    void hostConfigCarriesEveryHardeningFlag() {
        HostConfig cfg = manager().buildHostConfig(WORKTREE, null);

        assertThat(cfg.getReadonlyRootfs()).isTrue();
        assertThat(cfg.getCapDrop()).contains(Capability.ALL);
        assertThat(cfg.getSecurityOpts()).contains("no-new-privileges");
        assertThat(cfg.getPidsLimit()).isEqualTo(DockerSandboxManager.DEFAULT_PIDS_LIMIT);
        assertThat(cfg.getNanoCPUs()).isEqualTo(2L * 1_000_000_000L);
        assertThat(cfg.getMemory()).isEqualTo(4L * 1024 * 1024 * 1024);
        // Docker's init as pid 1. Without it nothing reaps the orphans a stopped background
        // service leaves behind, and those zombies count against the pids limit above.
        assertThat(cfg.getInit()).isTrue();
    }

    @Test
    void readOnlyRootfsIsMadeWorkableByTmpfsForTheToolchain() {
        var tmpfs = manager().buildHostConfig(WORKTREE, null).getTmpFs();

        // /tmp = java.io.tmpdir + the writable head of the split Maven repo; $HOME = git/JDK/Maven.
        assertThat(tmpfs).containsKeys("/tmp", "/home/sandbox");
        assertThat(tmpfs.get("/tmp")).contains("nosuid").contains("nodev");
        // "exec" must be EXPLICIT: Docker defaults every --tmpfs to noexec, and a noexec
        // java.io.tmpdir breaks Maven's jansi (and any other JNI library extracted at runtime).
        // DockerSandboxLiveTest checks the resulting mount, not just this string.
        assertThat(tmpfs.get("/tmp")).contains("exec").doesNotContain("noexec");
        assertThat(tmpfs.get("/home/sandbox")).contains("exec").doesNotContain("noexec");
        // Sized within the memory cap (tmpfs pages are charged to the container's memory cgroup).
        assertThat(sizeOf(tmpfs.get("/tmp"))).isLessThanOrEqualTo(4L * 1024 * 1024 * 1024 / 2);
    }

    @Test
    void workspaceIsTheOnlyWritableBindAndTheHostMavenRepoIsReadOnly() {
        Bind[] binds = manager().buildHostConfig(WORKTREE, null).getBinds();
        List<Bind> list = Arrays.asList(binds);

        Bind workspace = list.stream()
            .filter(b -> b.getVolume().getPath().equals("/workspace")).findFirst().orElseThrow();
        assertThat(workspace.getAccessMode().name()).isEqualTo("rw");

        // The host's ~/.m2 is mounted read-only and NOT at ~/.m2 — Maven writes markers even
        // offline, so the writable local repo lives on tmpfs with this mount as a resolver tail.
        Bind m2 = list.stream()
            .filter(b -> b.getVolume().getPath()
                .equals(DockerSandboxManager.HOST_M2_MOUNT + "/repository"))
            .findFirst().orElseThrow();
        assertThat(m2.getAccessMode().name()).isEqualTo("ro");
        // The repository folder and nothing beside it: ~/.m2 itself holds settings.xml, where
        // repository passwords live.
        assertThat(m2.getPath()).isEqualTo(HOST_M2 + "/repository");
        assertThat(list).noneMatch(b -> b.getPath().equals(HOST_M2));
        assertThat(DockerSandboxManager.MAVEN_ARGS)
            .contains("-Dmaven.repo.local=/tmp/m2")
            .contains("-Dmaven.repo.local.tail=" + MavenRepoCache.MOUNT + "/repository,"
                + DockerSandboxManager.HOST_M2_MOUNT + "/repository");
    }

    /**
     * What the container can see of the machine is a closed list: the checkout, the reference
     * folders it was given (read-only), the Maven repository (read-only) and the artifact cache
     * (read-only). Anything else appearing here is a host path a model's command can reach.
     */
    @Test
    void theOnlyHostFoldersMountedAreTheCheckoutTheReferenceFoldersAndTheMavenRepository() {
        List<DockerSandboxManager.ReadOnlyMount> reference = List.of(
            new DockerSandboxManager.ReadOnlyMount("/srv/reference/zeroz4j",
                DockerSandboxManager.referenceMountPath("zeroz4j")));
        List<Bind> binds = Arrays.asList(
            manager().buildHostConfig(WORKTREE, reference, null).getBinds());

        assertThat(binds).extracting(b -> b.getVolume().getPath()).containsExactlyInAnyOrder(
            "/workspace", "/reference/zeroz4j",
            DockerSandboxManager.HOST_M2_MOUNT + "/repository", MavenRepoCache.MOUNT);
        assertThat(binds).filteredOn(b -> !b.getVolume().getPath().equals("/workspace"))
            .allMatch(b -> b.getAccessMode().name().equals("ro"));
        Bind ref = binds.stream().filter(b -> b.getVolume().getPath().equals("/reference/zeroz4j"))
            .findFirst().orElseThrow();
        assertThat(ref.getPath()).isEqualTo("/srv/reference/zeroz4j");
    }

    @Test
    void aReferenceLabelCannotMountOutsideTheReferenceFolder() {
        assertThat(DockerSandboxManager.referenceMountPath("zeroz4j")).isEqualTo("/reference/zeroz4j");
        assertThat(DockerSandboxManager.referenceMountPath("../workspace"))
            .startsWith("/reference/").doesNotContain("..").doesNotContain("/workspace");
        assertThat(DockerSandboxManager.referenceMountPath("a/b c")).isEqualTo("/reference/a_b_c");
    }

    /**
     * The shared artifact cache is what stops every sandbox build reading the operator's Maven
     * repository across the host filesystem bridge (41-70 s a build; 11-13 s with the cache). It
     * is shared by concurrent candidates, so the two properties that make sharing safe are locked
     * in here: it is mounted READ-ONLY, and it sits in FRONT of the host repository rather than
     * replacing it, so a miss falls through to the authoritative copy instead of failing or
     * resolving something else.
     */
    @Test
    void theSharedArtifactCacheIsReadOnlyAndOnlyEverSitsInFrontOfTheHostRepository() {
        Bind cache = Arrays.stream(manager().buildHostConfig(WORKTREE, null).getBinds())
            .filter(b -> b.getVolume().getPath().equals(MavenRepoCache.MOUNT))
            .findFirst().orElseThrow();

        assertThat(cache.getAccessMode().name()).isEqualTo("ro");
        assertThat(cache.getPath()).isEqualTo(DockerSandboxManager.M2_CACHE_VOLUME);

        String tail = DockerSandboxManager.MAVEN_ARGS.split(Pattern.quote("-Dmaven.repo.local.tail="))[1]
            .split(" ")[0];
        assertThat(tail.split(",")).containsExactly(
            MavenRepoCache.MOUNT + "/repository",
            DockerSandboxManager.HOST_M2_MOUNT + "/repository");
    }

    /** With no host repository to read from there is nothing to cache, and nothing is mounted. */
    @Test
    void noHostMavenRepositoryMeansNoMountsAtAll() {
        DockerSandboxManager noM2 = new DockerSandboxManager("swarmcoder-worker:latest", 2, 4, null, null);
        Bind[] binds = noM2.buildHostConfig(WORKTREE, null).getBinds();

        assertThat(Arrays.stream(binds).map(b -> b.getVolume().getPath()))
            .containsExactly("/workspace");
    }

    @Test
    void bridgeIsOptInAndKeepsEveryOtherFlag() {
        DockerSandboxManager bridged = new DockerSandboxManager("swarmcoder-worker:latest",
            2, 4, 256, DockerSandboxManager.NetworkPolicy.BRIDGE, List.of("pypi.org"), true,
            null, HOST_M2);

        HostConfig cfg = bridged.buildHostConfig(WORKTREE, null);
        assertThat(cfg.getNetworkMode()).isEqualTo("bridge");
        assertThat(cfg.getReadonlyRootfs()).isTrue();
        assertThat(cfg.getCapDrop()).contains(Capability.ALL);
        assertThat(cfg.getPidsLimit()).isEqualTo(256L);
        // allowedHosts is surfaced, never silently swallowed — but it is NOT enforced yet.
        assertThat(bridged.allowedHosts()).containsExactly("pypi.org");
    }

    @Test
    void unknownNetworkValueFallsBackToTheSafeDefault() {
        assertThat(DockerSandboxManager.NetworkPolicy.parse(null))
            .isEqualTo(DockerSandboxManager.NetworkPolicy.NONE);
        assertThat(DockerSandboxManager.NetworkPolicy.parse("  "))
            .isEqualTo(DockerSandboxManager.NetworkPolicy.NONE);
        assertThat(DockerSandboxManager.NetworkPolicy.parse("host"))
            .isEqualTo(DockerSandboxManager.NetworkPolicy.NONE);
        assertThat(DockerSandboxManager.NetworkPolicy.parse("BRIDGE"))
            .isEqualTo(DockerSandboxManager.NetworkPolicy.BRIDGE);
    }

    private static long sizeOf(String tmpfsOptions) {
        for (String part : tmpfsOptions.split(",")) {
            if (part.startsWith("size=")) {
                return Long.parseLong(part.substring("size=".length()));
            }
        }
        throw new AssertionError("no size= in " + tmpfsOptions);
    }
}
