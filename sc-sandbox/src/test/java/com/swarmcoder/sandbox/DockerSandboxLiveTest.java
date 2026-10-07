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
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.NotRun;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end sandbox check against a real Docker daemon and the {@code swarmcoder-worker:latest}
 * image (build it first: {@code mvn -o -pl sc-sandbox-action-server -am package} then
 * {@code docker build -t swarmcoder-worker:latest sc-sandbox-action-server}).
 *
 * <p>It runs in an ordinary build on any machine with both, and no flag switches it on. It used to
 * need {@code SWARMCODER_SANDBOX_TESTS=true}, which nobody set, so nothing ever checked that the
 * containment the product promises actually holds. {@code @RunsWhen} looks for the daemon and the
 * image itself and, when either is absent, says so in the build output instead of vanishing.
 *
 * <p>{@code DockerSandboxHardeningTest} asserts the flags are <i>requested</i>; this asserts they
 * actually <i>hold</i> in a live container — non-root, no network, read-only rootfs, no
 * capabilities — and that a real offline Maven build still succeeds despite all of it, which is
 * the constraint that makes the hardening non-trivial.
 */
@RunsWhen(Need.DOCKER)
class DockerSandboxLiveTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private static final Path HOST_M2 = Path.of(System.getProperty("user.home"), ".m2");

    @Test
    void hardenedContainerIsNonRootNetworklessAndReadOnly(@TempDir Path workspace) {
        DockerSandboxManager manager = new DockerSandboxManager(
            "swarmcoder-worker:latest", 2, 4, null, HOST_M2.toString());

        DockerSandboxManager.SandboxHandle handle =
            manager.launch(workspace.toAbsolutePath().toString(), "src");
        try {
            // network=none publishes nothing — the Engine exec API is the transport.
            assertThat(handle.actionServerUrl()).isNull();

            assertThat(sh(manager, handle, "id -u").output().strip())
                .as("container must not run as root").isNotEqualTo("0");

            // No network namespace connectivity at all: DNS and a direct-IP connect both fail.
            assertThat(sh(manager, handle, "getent hosts example.com").exitCode())
                .as("DNS must not resolve without a network").isNotZero();
            assertThat(sh(manager, handle, "curl -s --max-time 5 http://1.1.1.1/").exitCode())
                .as("outbound connect must fail without a network").isNotZero();

            // Read-only rootfs: everything outside /workspace and the tmpfs mounts is immutable,
            // so the toolchain cannot be tampered with for a later candidate.
            assertThat(sh(manager, handle, "touch /etc/sc-probe").exitCode()).isNotZero();
            assertThat(sh(manager, handle, "touch /opt/sc-probe").exitCode()).isNotZero();
            assertThat(sh(manager, handle, "touch /opt/m2-ro/sc-probe").exitCode())
                .as("the host Maven repo mount must be read-only").isNotZero();

            // ...but the places the toolchain legitimately writes ARE writable.
            assertThat(sh(manager, handle, "touch /workspace/sc-probe").exitCode()).isZero();
            assertThat(sh(manager, handle, "touch /tmp/sc-probe").exitCode()).isZero();
            assertThat(sh(manager, handle, "touch \"$HOME/sc-probe\"").exitCode()).isZero();

            // Docker silently defaults every --tmpfs to noexec, which breaks any JNI library
            // extracted into java.io.tmpdir (Maven's own jansi, for one) — so assert the mount
            // that actually resulted, not the option string we asked for.
            assertThat(sh(manager, handle,
                "printf '#!/bin/sh\\nexit 0\\n' > /tmp/p.sh && chmod +x /tmp/p.sh && /tmp/p.sh").exitCode())
                .as("java.io.tmpdir must permit exec").isZero();

            // All capabilities dropped, and no path back up via setuid.
            assertThat(sh(manager, handle, "grep ^CapEff /proc/self/status").output())
                .contains("0000000000000000");
            assertThat(sh(manager, handle, "grep ^NoNewPrivs /proc/self/status").output())
                .contains("1");
        } finally {
            manager.kill(handle);
        }
    }

    /**
     * The hardening is worthless if it breaks the toolchain. Maven writes into its local
     * repository even offline, so this proves the split-repo decision (read-only host ~/.m2 as a
     * resolver tail + writable head on tmpfs) actually lets a non-root user build under a
     * read-only rootfs.
     */
    @Test
    void offlineMavenBuildSucceedsUnderTheHardenedContainer(@TempDir Path workspace) throws Exception {
        NotRun.needed(Files.isDirectory(HOST_M2.resolve("repository")),
            "DockerSandboxLiveTest#offlineMavenBuildSucceedsUnderTheHardenedContainer",
            "there is no host Maven repository at " + HOST_M2.resolve("repository")
                + " to mount as the read-only resolver tail, so an offline build inside the "
                + "container could not resolve anything and would fail for that reason alone.");

        Files.writeString(workspace.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.swarmcoder.sandboxprobe</groupId>
              <artifactId>probe</artifactId>
              <version>1.0-SNAPSHOT</version>
              <properties>
                <maven.compiler.release>21</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>
            </project>
            """);
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("src/main/java/Probe.java"),
            "public class Probe { public static void main(String[] a) { System.out.println(\"ok\"); } }");

        DockerSandboxManager manager = new DockerSandboxManager(
            "swarmcoder-worker:latest", 2, 4, null, HOST_M2.toString());

        DockerSandboxManager.SandboxHandle handle =
            manager.launch(workspace.toAbsolutePath().toString(), "src");
        try {
            // `install`, not `compile`: installing is the phase that actually WRITES to the local
            // repository, which is the whole reason the repo had to be split.
            DockerSandboxManager.ExecOutcome build =
                manager.execInContainer(handle.containerId(),
                    List.of("sh", "-c", "mvn -o -B install"), 300);
            assertThat(build.exitCode())
                .as("offline mvn install inside the hardened container:%n%s", build.output())
                .isZero();
            assertThat(sh(manager, handle, "ls /workspace/target/classes/Probe.class").exitCode())
                .as("the build must really have compiled, not merely exited 0").isZero();
            // Those writes landed on the tmpfs head, never on the read-only host repo.
            assertThat(sh(manager, handle,
                "test -f /tmp/m2/com/swarmcoder/sandboxprobe/probe/1.0-SNAPSHOT/probe-1.0-SNAPSHOT.jar")
                .exitCode()).isZero();
        } finally {
            manager.kill(handle);
        }
    }

    /**
     * {@code network: bridge} is the opt-in mode: the action server port is published and the
     * §8.3 HTTP protocol (including write-set enforcement) is reachable — at the cost of
     * unrestricted egress, which is why it is not the default.
     */
    @Test
    void bridgeModePublishesTheActionServerProtocol(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("hello.txt"), "workspace-file-contents");
        Files.createDirectories(workspace.resolve("src"));

        DockerSandboxManager manager = new DockerSandboxManager("swarmcoder-worker:latest",
            2, 4, DockerSandboxManager.DEFAULT_PIDS_LIMIT,
            DockerSandboxManager.NetworkPolicy.BRIDGE, List.of(), true, null, HOST_M2.toString());

        DockerSandboxManager.SandboxHandle handle =
            manager.launch(workspace.toAbsolutePath().toString(), "src");
        try {
            assertThat(handle.actionServerUrl()).isNotNull();

            // launch() already blocked until /health was 200; exercise /exec and /read.
            String exec = post(handle.actionServerUrl() + "/exec",
                "{\"command\":[\"sh\",\"-c\",\"echo hi-from-container; git --version\"],\"timeoutSec\":60}");
            assertThat(exec).contains("\"exitCode\":0").contains("hi-from-container").contains("git version");

            String read = post(handle.actionServerUrl() + "/read",
                "{\"path\":\"hello.txt\",\"maxBytes\":1000}");
            assertThat(read).contains("workspace-file-contents");

            // Write-set enforcement: writing outside SC_WRITE_SET (=src) is rejected.
            String blocked = post(handle.actionServerUrl() + "/write",
                "{\"path\":\"outside.txt\",\"content\":\"nope\"}");
            assertThat(blocked).contains("error");

            // Bridge relaxes ONLY the network: it is still non-root with a read-only rootfs.
            assertThat(sh(manager, handle, "id -u").output().strip()).isNotEqualTo("0");
            assertThat(sh(manager, handle, "touch /etc/sc-probe").exitCode()).isNotZero();
        } finally {
            manager.kill(handle);
        }
    }

    private static DockerSandboxManager.ExecOutcome sh(DockerSandboxManager manager,
                                                       DockerSandboxManager.SandboxHandle handle,
                                                       String command) {
        return manager.execInContainer(handle.containerId(), List.of("sh", "-c", command), 30);
    }

    private String post(String url, String json) throws Exception {
        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
            HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }
}
