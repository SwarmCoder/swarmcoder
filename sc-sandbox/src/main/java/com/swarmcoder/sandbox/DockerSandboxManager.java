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

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.github.dockerjava.api.model.AccessMode;

/**
 * Launches and tears down per-candidate worker sandboxes (spec §8): one Docker container per
 * candidate, the candidate's git worktree bind-mounted at {@code /workspace}, running the
 * in-container action server (built into {@code swarmcoder-worker:latest}).
 *
 * <p>All failure modes surface as {@link SandboxException}. No caller may degrade to host
 * execution - see "no degradation" below.
 *
 * <h2>Isolation (spec §8.2)</h2>
 * The container runs arbitrary model-authored commands, so every launch gets: no network,
 * a read-only rootfs (writable tmpfs where the toolchain genuinely needs to write, plus the
 * read-write {@code /workspace} bind), all Linux capabilities dropped, {@code no-new-privileges},
 * a pids limit, a non-root uid, and the CPU/memory caps from config. Each flag is justified
 * inline in {@link #buildHostConfig}.
 *
 * <h2>Transport, and why {@code network: none} forces the Engine exec API</h2>
 * The action server speaks HTTP on the container port named by {@link #ACTION_SERVER_PORT} (not
 * 8080 — see that field). Docker <b>refuses to publish ports for a
 * container with {@code --network none}</b> — verified against Docker Engine 29: the created
 * container's {@code NetworkSettings.Ports} comes back as {@code {"8080/tcp":[]}}, i.e. no host
 * binding at all, so the orchestrator can never reach it. (The same is true of an
 * {@code --internal} bridge network, and on Docker Desktop the container IP is not routable from
 * the Windows host either.) Publishing a port is therefore mutually exclusive with real network
 * isolation, and the isolation is the point. So commands are driven through
 * {@link #execInContainer} — the Docker Engine {@code /exec} API, which travels over the daemon
 * socket and is completely independent of the container's network namespace. Under
 * {@link NetworkPolicy#BRIDGE} the port is still published and {@link SandboxHandle#actionServerUrl()}
 * is non-null so the HTTP action-server protocol stays exercisable; under {@code NONE} it is null.
 *
 * <h2>No degradation</h2>
 * A sandbox that cannot launch used to log a warning and let the candidate run on the host —
 * i.e. the isolation quietly evaporated exactly when something was wrong. It is a hard failure
 * now, always: {@code sandbox.required: false} used to bring the fallback back and is ignored
 * (owner decision, 2026-10-02). The only way model code runs on the host is the operator's
 * {@code sandbox.enabled: false}, which gives the engine no manager at all.
 *
 * <p><b>Cross-platform.</b> Works on Windows and Linux hosts, and with either Docker Desktop or a
 * native/WSL2 Docker engine: the daemon endpoint is auto-detected per OS by docker-java's zerodep
 * transport (a Windows named pipe {@code npipe://}, or the {@code unix://} socket on Linux/WSL2)
 * and may be overridden via {@code dockerHost}. Worktree bind paths are passed in their native form
 * — Windows drive paths ({@code C:\Users\...}) and POSIX paths ({@code /home/...}) both mount
 * correctly, and docker-java only recognizes a Windows drive when it keeps its backslashes. The
 * sandbox container itself is always Linux, so in-container commands are POSIX regardless of host.
 */
public class DockerSandboxManager {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxManager.class);
    /**
     * Port the in-container action server listens on, and deliberately not 8080.
     *
     * <p>8080 is the port a Java web application binds by default and the port the demo repository
     * in this checkout hardcodes. The action server starts first (it is the container's command),
     * so when a candidate's application wanted 8080 the action server already had it: the
     * application lost the bind, still logged that it had started, and anything pointed at 8080 —
     * a readiness probe, a browser — got the action server's 404 and read it as a candidate whose
     * application does not work. Measured in this container on 2026-08-31, with the real
     * bookshelf-demo. Moving this server to a port nothing else wants costs nothing and removes
     * the whole class of mistake.
     *
     * <p>The image is told the number through {@code SC_ACTION_PORT}. An image built before that
     * env var existed ignores it and listens on 8080, which the launch health check catches with a
     * message naming the rebuild.
     */
    private static final int ACTION_SERVER_PORT = 47871;
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(60);

    /** Default cap on processes in the container — enough for a JVM + Maven fork, not a fork bomb. */
    public static final int DEFAULT_PIDS_LIMIT = 512;

    /**
     * uid:gid the container runs as. Must match the {@code sandbox} account baked into the image
     * (see sc-sandbox-action-server/Dockerfile): the numbers are what the kernel actually checks
     * against the bind-mounted worktree, and 1000 is the operator's own uid on a typical Linux
     * host, so the container can write /workspace without the mount being world-writable.
     */
    private static final String SANDBOX_USER = "1000:1000";

    /**
     * Where the host's Maven repository appears, read-only: its {@code repository} folder is
     * mounted at {@code HOST_M2_MOUNT/repository}. The rest of {@code ~/.m2} is not mounted.
     */
    public static final String HOST_M2_MOUNT = "/opt/m2-ro";

    /**
     * <b>Maven local-repository decision.</b> Maven writes into its local repository even with
     * {@code -o}: any multi-module build installs its own modules there, along with
     * {@code _remote.repositories} and {@code maven-metadata-local.xml} markers. Measured in this
     * very image, {@code mvn -o -B install} with {@code maven.repo.local} pointed at the read-only
     * mount fails with <i>"Failed to install artifact ...: Read-only file system"</i>. So a
     * read-only local repo is not a workable configuration, and the old setup (host {@code ~/.m2}
     * bind-mounted read-only at {@code /root/.m2}) could not have worked once the container stopped
     * running as root. The two obvious fixes were both rejected:
     * <ul>
     *   <li><b>Mount ~/.m2 read-write</b> — the sandbox could then plant or overwrite artifacts in
     *       the operator's own repository, which the operator's next non-sandboxed build resolves
     *       and executes. That is a worse hole than the one we are closing.</li>
     *   <li><b>Copy/overlay the repo into the container</b> — a real overlayfs mount needs
     *       CAP_SYS_ADMIN, which conflicts with dropping all capabilities, and copying a
     *       multi-gigabyte repo per candidate is not viable.</li>
     * </ul>
     * Chosen instead: Maven's own <i>chained local repository</i> (Maven 3.9+/resolver 1.9+).
     * The host repo stays mounted <b>read-only</b> at {@value #HOST_M2_MOUNT} and is declared as a
     * read-only resolver "tail"; the writable head is a directory on the {@code /tmp} tmpfs. Reads
     * fall through to the host cache (so offline builds resolve), writes land in RAM and die with
     * the container. {@code ignoreAvailability} is set because artifacts in a host repo that was
     * populated by a different Maven version may lack the availability markers the tail check
     * wants — in an offline sandbox "use whatever the host already cached" is the intent.
     *
     * <p><b>A third repository was added in front of both, and it is the reason builds are no
     * longer slow.</b> {@value MavenRepoCache#MOUNT} is a Docker named volume holding a copy of
     * just the artifacts this project's builds resolve. It lives on the Linux VM's own filesystem,
     * whereas the host {@code ~/.m2} bind reaches across to the Windows disk at 8-44 ms per file —
     * and since the chained head never caches anything, every build was paying that on every
     * artifact. Measured on the demo project: 41-70 s a sandbox build before, 11-13 s after,
     * against 10-12 s for the same command on the host. The host repository stays in the chain
     * behind the cache and stays authoritative, so a cache miss costs milliseconds and can never
     * change which artifact is resolved. See {@link MavenRepoCache}.
     *
     * <p>Also set in the image's {@code MAVEN_ARGS} so ad-hoc model-authored {@code mvn}
     * invocations get it too; passed here as well so the setting survives an older image.
     */
    public static final String MAVEN_ARGS =
        "-Dmaven.repo.local=/tmp/m2"
        + " -Dmaven.repo.local.tail=" + MavenRepoCache.MOUNT + "/repository," + HOST_M2_MOUNT + "/repository"
        + " -Dmaven.repo.local.tail.ignoreAvailability=true";

    /**
     * Name of the Docker named volume holding the fast, read-only artifact cache that now sits in
     * FRONT of the host repository in the chain above. Overridable so two orchestrators on one
     * machine can be told apart; see {@link MavenRepoCache} for why it exists and what makes
     * sharing it between candidates safe (short version: they mount it read-only and nothing they
     * run can write it).
     */
    public static final String M2_CACHE_VOLUME =
        System.getProperty("swarmcoder.sandbox.m2cacheVolume", MavenRepoCache.DEFAULT_VOLUME);

    /** Where reference roots are mounted, read-only, one folder per root label. */
    public static final String REFERENCE_MOUNT = "/reference";

    /** Label on every container this class starts - how its own leftovers are told from anything else. */
    static final String OWNER_LABEL = "swarmcoder.sandbox";

    /**
     * The longest a sandbox may live, enforced from INSIDE the container. Removal is the caller's
     * job ({@link #kill}) and a shutdown hook covers an orderly exit, but an orchestrator that is
     * killed outright runs neither - and a container is a JVM holding gigabytes. So each one
     * stops itself after this long, and the next launch removes stopped ones carrying
     * {@link #OWNER_LABEL}. Generous on purpose: it must outlast the longest real worker session.
     */
    static final long MAX_LIFETIME_SECONDS =
        Long.getLong("swarmcoder.sandbox.maxLifetimeSeconds", 6 * 60 * 60L);

    /**
     * A folder the container may read and never write: reference source and documentation.
     *
     * @param hostPath      native host path of the folder
     * @param containerPath where it appears inside the container
     */
    public record ReadOnlyMount(String hostPath, String containerPath) {}

    /** Where a reference root with this label is mounted - the same address the file tools use. */
    public static String referenceMountPath(String label) {
        // One path segment, whatever the label: no separator, no `..`, no leading dot.
        String safe = label == null ? "" : label.replaceAll("[^A-Za-z0-9._-]", "_")
            .replaceAll("\\.\\.+", "_").replaceAll("^\\.+", "");
        return REFERENCE_MOUNT + "/" + (safe.isBlank() ? "root" : safe);
    }

    /** Containers this JVM started and has not removed yet; emptied by the shutdown hook. */
    private static final java.util.Set<String> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicBoolean HOUSEKEEPING =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** Container network policy. See the class javadoc for why {@code NONE} changes the transport. */
    public enum NetworkPolicy {
        /** No network namespace connectivity at all: no egress, no DNS, no published ports. */
        NONE,
        /** Docker's default bridge: the action server port is published — and egress is UNRESTRICTED. */
        BRIDGE;

        /** Parses a config value; unknown/blank falls back to the safe default {@code NONE}. */
        public static NetworkPolicy parse(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            return switch (value.trim().toLowerCase()) {
                case "bridge" -> BRIDGE;
                case "none" -> NONE;
                default -> {
                    log.warn("Unknown sandbox network '{}' — falling back to 'none'", value);
                    yield NONE;
                }
            };
        }
    }

    private final DockerClient dockerClient;
    private final String image;
    private final long nanoCpus;
    private final long memoryBytes;
    private final long pidsLimit;
    private final NetworkPolicy network;
    private final List<String> allowedHosts;
    private final boolean required;
    private final String mavenRepoHostPath; // nullable: bind host ~/.m2 read-only for offline builds
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Defaults: the standard worker image, 2 CPUs, 4 GiB, no network, required, no ~/.m2 mount. */
    public DockerSandboxManager() {
        this("swarmcoder-worker:latest", 2, 4, null, null);
    }

    /**
     * @param image             worker image tag (must contain the action server at
     *                          {@code /opt/action-server.jar})
     * @param cpus              CPU limit (whole cores)
     * @param memGb             memory limit in GiB
     * @param dockerHost        Docker daemon endpoint, or null for the environment default
     *                          (npipe on Windows, the unix socket on Linux)
     * @param mavenRepoHostPath host Maven repo (~/.m2) to bind read-only at {@value #HOST_M2_MOUNT}
     *                          so container builds resolve already-cached dependencies without
     *                          network; null to skip
     */
    public DockerSandboxManager(String image, int cpus, int memGb, String dockerHost, String mavenRepoHostPath) {
        this(image, cpus, memGb, DEFAULT_PIDS_LIMIT, NetworkPolicy.NONE, List.of(), true,
            dockerHost, mavenRepoHostPath);
    }

    /**
     * @param pidsLimit    max processes in the container (&le;0 → {@value #DEFAULT_PIDS_LIMIT})
     * @param network      {@link NetworkPolicy#NONE} (default, no egress) or
     *                     {@link NetworkPolicy#BRIDGE} (published port, UNRESTRICTED egress)
     * @param allowedHosts operator egress allowlist — <b>parsed but NOT enforced</b>; see
     *                     {@link #allowedHosts()}
     * @param required     ignored: a sandbox that fails to launch always fails the
     *                     candidate/run. Kept so existing callers compile
     */
    public DockerSandboxManager(String image, int cpus, int memGb, int pidsLimit,
                                NetworkPolicy network, List<String> allowedHosts, boolean required,
                                String dockerHost, String mavenRepoHostPath) {
        this.image = image;
        this.nanoCpus = Math.max(1, cpus) * 1_000_000_000L;
        this.memoryBytes = Math.max(1, memGb) * 1024L * 1024L * 1024L;
        this.pidsLimit = pidsLimit > 0 ? pidsLimit : DEFAULT_PIDS_LIMIT;
        this.network = network == null ? NetworkPolicy.NONE : network;
        this.allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        this.required = true;
        if (!required) {
            log.warn("sandbox.required: false no longer does anything. A container that cannot be "
                + "started stops the work; model code is never run on this PC instead.");
        }
        this.mavenRepoHostPath = mavenRepoHostPath;

        if (this.network == NetworkPolicy.BRIDGE) {
            log.warn("Sandbox network=bridge: containers have UNRESTRICTED outbound network access. "
                + "sandbox.allowedHosts is NOT enforced (no egress proxy is implemented yet) — "
                + "prefer network=none unless a build genuinely needs the network.");
        } else if (!this.allowedHosts.isEmpty()) {
            log.warn("sandbox.allowedHosts={} is ignored under network=none (there is no egress to "
                + "allow); it is not enforced under network=bridge either", this.allowedHosts);
        }

        DefaultDockerClientConfig.Builder cfg = DefaultDockerClientConfig.createDefaultConfigBuilder();
        if (dockerHost != null && !dockerHost.isBlank()) {
            cfg.withDockerHost(dockerHost);
        }
        DockerClientConfig config = cfg.build();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    /** True when the Docker daemon is reachable — callers use this to decide sandbox vs local. */
    public boolean isAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            log.info("Docker daemon not reachable ({}); sandbox unavailable", e.getMessage());
            return false;
        }
    }

    /**
     * Why no worker can be started in a container right now, in words for the operator, or empty
     * when one can. Asked BEFORE a run spends anything, so "Docker is not running" and "the image
     * was never built" stop a run at its first line instead of failing every candidate later.
     */
    public java.util.Optional<String> whyUnusable() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            return java.util.Optional.of("Docker is not answering (" + e.getMessage() + "). "
                + "Workers' commands run only inside a container, never on this machine, so "
                + "nothing was started. Start Docker Desktop and run this again.");
        }
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (Exception e) {
            return java.util.Optional.of("Docker is running but the image `" + image + "` that "
                + "workers' commands run in is not on this machine. Workers' commands run only "
                + "inside a container, never on this machine, so nothing was started. Build it "
                + "once, in the SwarmCoder folder: `mvn -o -q -pl sc-sandbox-action-server -am "
                + "package -DskipTests` then `docker build -t " + image
                + " sc-sandbox-action-server`.");
        }
        return java.util.Optional.empty();
    }

    /**
     * Always true. A container that cannot be started fails the candidate or stops the run; it
     * never falls back to running model code on this PC. {@code sandbox.required: false} used to
     * turn that fallback on and no longer does anything (owner decision, 2026-10-02).
     */
    public boolean isRequired() {
        return true;
    }

    /**
     * The image that is the worker image plus a browser ({@code sc-sandbox/images/sc-java-ui}).
     * Browser checks run inside a container started from it, because a container with no network
     * publishes no port a browser on this PC could open.
     */
    public static String uiImage() {
        return System.getProperty("swarmcoder.sandbox.uiImage", "swarmcoder-worker-ui:latest");
    }

    /** The image this manager starts containers from. */
    public String image() {
        return image;
    }

    /** Whether an image is on this machine. Never pulls or builds one. */
    public boolean hasImage(String name) {
        try {
            dockerClient.inspectImageCmd(name).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public NetworkPolicy network() {
        return network;
    }

    /**
     * The configured egress allowlist. <b>Not enforced.</b> Honest status: enforcing it needs the
     * allowlist proxy of spec §8.2 (a dedicated docker network whose only route out is a filtering
     * proxy container), which is a separate piece of work. Until that exists the only real choices
     * are {@code none} (no egress — the default) and {@code bridge} (all egress). This accessor
     * exists so the value is visible and testable rather than a config field that silently does
     * nothing; the constructor logs a WARN whenever a non-empty allowlist is configured.
     */
    public List<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * Builds the container's HostConfig. Package-visible so the hardening flags can be asserted
     * without a Docker daemon.
     *
     * @param portBindings published ports, or null under {@link NetworkPolicy#NONE} (Docker
     *                     publishes nothing for a network-less container anyway)
     */
    HostConfig buildHostConfig(String worktreeHostPath, Ports portBindings) {
        return buildHostConfig(worktreeHostPath, List.of(), portBindings);
    }

    /**
     * @param readOnly reference folders to mount read-only. Together with the worktree, the Maven
     *                 repository and the artifact cache these are the WHOLE of what the container
     *                 can see of the machine: no drive, no home folder, no Docker socket.
     */
    HostConfig buildHostConfig(String worktreeHostPath, List<ReadOnlyMount> readOnly,
                               Ports portBindings) {
        List<Bind> binds = new ArrayList<>();
        // Pass host paths in their NATIVE form: docker-java's Bind parser recognizes a Windows
        // drive path only in the backslash form (C:\Users\...); rewriting it to forward slashes
        // (C:/Users/...) makes the drive colon look like a host:container separator and breaks
        // container inspect. POSIX paths (/home/...) have no drive colon and pass through fine —
        // so the raw path is correct on both Windows and Linux.
        // /workspace is the ONE read-write bind: it is the candidate's own worktree and the only
        // thing the run is allowed to produce.
        binds.add(new Bind(worktreeHostPath, new Volume("/workspace")));
        // The checkout's git pointer, read-only on top of the read-write worktree. The product
        // runs git on the HOST in this folder after every command (the audit, the diff). In a
        // worktree `.git` is a one-line file naming the real git folder, so a command able to
        // rewrite it could aim that host-side git at any repository on the machine. A mount point
        // can be neither replaced nor removed from inside.
        String gitPointer = join(worktreeHostPath, ".git");
        if (java.nio.file.Files.exists(java.nio.file.Path.of(gitPointer))) {
            binds.add(new Bind(gitPointer, new Volume("/workspace/.git"), AccessMode.ro));
        }
        for (ReadOnlyMount mount : readOnly == null ? List.<ReadOnlyMount>of() : readOnly) {
            binds.add(new Bind(mount.hostPath(), new Volume(mount.containerPath()), AccessMode.ro));
        }
        if (mavenRepoHostPath != null && !mavenRepoHostPath.isBlank()) {
            // READ-ONLY, and deliberately NOT at ~/.m2 — see MAVEN_ARGS for the full rationale.
            // Only the `repository` folder: ~/.m2 itself also holds settings.xml and
            // settings-security.xml, which is where repository passwords live, and nothing a
            // model runs has any business reading those. Maven in the container never used them
            // (it reads $HOME/.m2, which is the container's own tmpfs).
            binds.add(new Bind(join(mavenRepoHostPath, "repository"),
                new Volume(HOST_M2_MOUNT + "/repository"), AccessMode.ro));
            // The fast artifact cache, ahead of the host repo in the resolver chain. READ-ONLY,
            // which is the whole reason one volume can be shared by concurrent candidates: the
            // mount is read-only in the kernel, every capability is dropped and no-new-privileges
            // is set, so no candidate can write it and therefore no candidate can reach another
            // through it. Docker creates the volume empty on first use; an empty or incomplete
            // cache simply falls through to the host repository, so this can never change WHICH
            // artifact a build resolves - only how fast it is found. See MavenRepoCache.
            binds.add(new Bind(M2_CACHE_VOLUME, new Volume(MavenRepoCache.MOUNT), AccessMode.ro));
        }

        HostConfig hostConfig = HostConfig.newHostConfig()
            .withBinds(binds.toArray(new Bind[0]))
            .withNanoCPUs(nanoCpus)
            .withMemory(memoryBytes)
            // Fork-bomb / runaway-build guard. A single `while :; do ... & done` from the model
            // would otherwise take the whole workstation down with it, memory cap or not.
            .withPidsLimit(pidsLimit)
            // No egress by default: the container holds the operator's source tree and runs
            // model-authored commands, so "can talk to the internet" is an exfiltration channel.
            // BRIDGE is opt-in and loudly warned about at construction.
            .withNetworkMode(network == NetworkPolicy.NONE ? "none" : "bridge")
            // Nothing outside /workspace and the tmpfs mounts may be modified: no tampering with
            // the toolchain (planting a wrapper on $PATH, editing the JDK's cacerts, dropping a
            // Maven extension) that a later or concurrent candidate would then execute.
            .withReadonlyRootfs(true)
            // Drop every Linux capability. The container only needs to run compilers and tests;
            // it needs no raw sockets, no mount, no chown, no ptrace of anything outside itself.
            .withCapDrop(Capability.ALL)
            // Bars setuid/file-capability escalation, so even if the image ever ships a setuid
            // binary the non-root sandbox uid cannot climb back to root inside the container.
            .withSecurityOpts(List.of("no-new-privileges"))
            // A read-only rootfs breaks the toolchain unless the places it legitimately writes are
            // given real, container-private, RAM-backed filesystems:
            //   /tmp          — java.io.tmpdir (hsperfdata, jar extraction), git temp files, and
            //                   the writable head of the split Maven repo (/tmp/m2).
            //   /home/sandbox — $HOME; git, the JDK and Maven all write there.
            // "exec" is spelled out on purpose. Docker adds noexec to every --tmpfs mount unless
            // told otherwise (verified: /proc/mounts shows noexec even when the option string
            // omits it), and a noexec java.io.tmpdir breaks every JNI-using tool that extracts a
            // native library and dlopen()s it — Maven's own jansi dies with
            // "failed to map segment from shared object". It costs nothing defensively: the model
            // can already write and run executables in the read-write /workspace bind, so noexec
            // on /tmp blocks no attack while breaking the toolchain. nosuid+nodev, which DO close
            // real escalation paths, stay on.
            // tmpfs is charged to the container's memory cgroup, so the sizes stay well inside the
            // configured memory cap.
            .withTmpFs(tmpFsMounts())
            // Docker's tini as pid 1. Without it the container's pid 1 is the action server's JVM,
            // which does not reap orphans: every background service started through
            // SandboxExecTarget leaves its whole process tree behind as zombies once it is killed.
            // Zombies hold pid-table entries, so they count against withPidsLimit above — a
            // candidate that starts and stops its application a few dozen times would eventually
            // hit the limit and fail for a reason nothing in the report could explain. Measured on
            // this machine: without --init, four zombies survived one stopped service; with it,
            // none.
            .withInit(true);

        if (portBindings != null) {
            hostConfig = hostConfig.withPortBindings(portBindings);
        }
        return hostConfig;
    }

    /** Joins with the separator the host path already uses (docker-java needs Windows paths native). */
    static String join(String hostDir, String child) {
        String sep = hostDir.contains("\\") ? "\\" : "/";
        return hostDir.endsWith(sep) ? hostDir + child : hostDir + sep + child;
    }

    private Map<String, String> tmpFsMounts() {
        // Half the memory cap for /tmp, clamped to [256 MiB, 2 GiB]: big enough for JDK/Maven
        // scratch and the split repo's write side, small enough that it cannot itself OOM the
        // container (tmpfs pages count against the memory cgroup).
        long tmpBytes = Math.min(Math.max(memoryBytes / 2, 256L * 1024 * 1024), 2L * 1024 * 1024 * 1024);
        Map<String, String> mounts = new LinkedHashMap<>();
        mounts.put("/tmp", "rw,exec,nosuid,nodev,mode=1777,size=" + tmpBytes);
        mounts.put("/home/sandbox", "rw,exec,nosuid,nodev,mode=0777,size=" + (256L * 1024 * 1024));
        return mounts;
    }

    /**
     * Launches a sandbox for a worktree and blocks until it is healthy.
     *
     * @param worktreeHostPath absolute host path of the candidate's git worktree
     * @param writeSetEnv      comma-separated write-set passed to the action server as {@code SC_WRITE_SET}
     * @return a handle carrying the container id and, under {@link NetworkPolicy#BRIDGE} only,
     *         the action server base URL
     */
    public SandboxHandle launch(String worktreeHostPath, String writeSetEnv) {
        return launch(worktreeHostPath, writeSetEnv, List.of());
    }

    /**
     * As {@link #launch(String, String)}, with reference folders mounted read-only.
     *
     * @param readOnly see {@link #buildHostConfig(String, List, Ports)}
     */
    public SandboxHandle launch(String worktreeHostPath, String writeSetEnv,
                                List<ReadOnlyMount> readOnly) {
        return launch(worktreeHostPath, writeSetEnv, readOnly, null);
    }

    /**
     * As {@link #launch(String, String, List)}, from another image with the same hardening.
     *
     * @param imageOverride the image to start, or null for this manager's own. Used for the UI
     *                      image ({@link #uiImage()}), which is the worker image plus a browser,
     *                      when the tree's browser checks have to run inside the container
     */
    public SandboxHandle launch(String worktreeHostPath, String writeSetEnv,
                                List<ReadOnlyMount> readOnly, String imageOverride) {
        String image = imageOverride == null || imageOverride.isBlank() ? this.image : imageOverride;
        housekeeping();
        String containerName = "swarmcoder-worker-" + UUID.randomUUID().toString().substring(0, 8);
        ExposedPort actionPort = ExposedPort.tcp(ACTION_SERVER_PORT);

        Ports portBindings = null;
        if (network == NetworkPolicy.BRIDGE) {
            portBindings = new Ports();
            // null host port → Docker assigns an ephemeral port, bound to loopback only.
            portBindings.bind(actionPort, new Ports.Binding("127.0.0.1", null));
        }

        HostConfig hostConfig = buildHostConfig(worktreeHostPath, readOnly, portBindings);

        String containerId = null;
        try {
            CreateContainerCmd create = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withHostConfig(hostConfig)
                // Non-root. Pinned numerically rather than by name so it is the uid the kernel
                // checks against the bind-mounted worktree, not whatever the image happens to
                // resolve "sandbox" to.
                .withUser(SANDBOX_USER)
                .withLabels(Map.of(OWNER_LABEL, "worker",
                    OWNER_LABEL + ".pid", String.valueOf(ProcessHandle.current().pid())))
                .withEnv("SC_WRITE_SET=" + (writeSetEnv == null ? "" : writeSetEnv),
                    // Keeps the action server off 8080 so the candidate's own application can have
                    // it — see ACTION_SERVER_PORT.
                    "SC_ACTION_PORT=" + ACTION_SERVER_PORT,
                    // Belt-and-braces: the image sets this too, but an older image would not.
                    // Offline under network=none: there is nothing to download from, and a model
                    // that writes `mvn test` without -o otherwise gets a wall of resolver errors
                    // about hosts that cannot be reached instead of its build.
                    "MAVEN_ARGS=" + MAVEN_ARGS + (network == NetworkPolicy.NONE ? " -o" : ""),
                    "HOME=/home/sandbox");
            if (portBindings != null) {
                create = create.withExposedPorts(actionPort);
            }
            containerId = create.exec().getId();
            LIVE.add(containerId);
            dockerClient.startContainerCmd(containerId).exec();
            startLifetimeWatchdog(containerId);

            String actionServerUrl = null;
            if (network == NetworkPolicy.BRIDGE) {
                actionServerUrl = "http://127.0.0.1:" + mappedHostPort(containerId, actionPort);
                awaitHealthyOverHttp(containerId, actionServerUrl);
            } else {
                awaitHealthyInContainer(containerId);
            }
            log.info("Sandbox {} up (network={}, worktree={}{})", containerName, network,
                worktreeHostPath, actionServerUrl == null ? "" : ", actionServer=" + actionServerUrl);
            return new SandboxHandle(containerId, actionServerUrl);
        } catch (SandboxException e) {
            safeKill(containerId);
            throw e;
        } catch (Exception e) {
            safeKill(containerId);
            throw new SandboxException("Failed to launch sandbox " + containerName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Mirrors the artifacts this project's builds resolve into the shared read-only cache volume,
     * so sandbox builds stop reading the operator's Maven repository across the host filesystem
     * bridge. Blocking (minutes on the first call for a project, seconds afterwards) and safe to
     * call repeatedly; callers run it off the critical path.
     *
     * <p>Read {@link MavenRepoCache} for the measurements that motivate it and for exactly what
     * keeps the operator's {@code ~/.m2} read-only and concurrent candidates isolated.
     *
     * @param repoHostPath native host path of the operator's pristine checkout (mounted READ-ONLY)
     * @param commands     the project's own build commands, from its verification contract
     */
    public MavenRepoCache.Report warmMavenRepoCache(String repoHostPath, List<String> commands) {
        return new MavenRepoCache(dockerClient, image, mavenRepoHostPath, M2_CACHE_VOLUME)
            .warm(repoHostPath, commands);
    }

    /**
     * Environment set on every in-container exec so a command can never end up waiting on a
     * console: {@code CI=true} is the convention the widest range of build tools already check,
     * and the other three defeat pager/dumb-terminal behaviour (e.g. {@code git log | less}).
     * Mirrors the local exec target's equivalent (sc-verify's {@code LocalProcessExecTarget}).
     */
    private static final List<String> NON_INTERACTIVE_ENV =
        List.of("CI=true", "TERM=dumb", "PAGER=cat", "GIT_PAGER=cat");

    /**
     * Runs a command inside a running sandbox over the Docker Engine exec API. This is the
     * transport that works under {@code network: none} — it goes over the daemon socket, never
     * through the container's (absent) network stack. stdout and stderr are interleaved, exactly
     * as the action server's {@code /exec} does.
     *
     * <p>Stdin is never attached ({@code withAttachStdin} is left at its default false, and no
     * {@code -i} equivalent is requested anywhere in this class) — the equivalent, over the Engine
     * API, of running {@code docker exec} without {@code -i}. A command that tries to read a
     * console (a pager, {@code read -p}, a server blocking on stdin) sees EOF immediately instead
     * of hanging until the timeout kills it.
     *
     * @throws SandboxException if the daemon or the container is unreachable (a non-zero exit or
     *                          a timeout is a <i>result</i>, not an exception)
     */
    public ExecOutcome execInContainer(String containerId, List<String> command, int timeoutSeconds) {
        try {
            ExecCreateCmdResponse created = dockerClient.execCreateCmd(containerId)
                .withCmd(command.toArray(new String[0]))
                .withUser(SANDBOX_USER)
                .withWorkingDir("/workspace")
                .withEnv(NON_INTERACTIVE_ENV)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

            StringBuilder output = new StringBuilder();
            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    synchronized (output) {
                        output.append(new String(frame.getPayload()));
                    }
                }
            };
            boolean finished;
            try (ResultCallback.Adapter<Frame> started =
                     dockerClient.execStartCmd(created.getId()).exec(callback)) {
                finished = started.awaitCompletion(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            }
            synchronized (output) {
                if (!finished) {
                    return new ExecOutcome(-1, output.toString(), true);
                }
                Long exit = dockerClient.inspectExecCmd(created.getId()).exec().getExitCodeLong();
                return new ExecOutcome(exit == null ? -1 : exit.intValue(), output.toString(), false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException("Interrupted running command in sandbox " + containerId, e);
        } catch (SandboxException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxException(
                "Sandbox " + containerId + " exec failed: " + e.getMessage(), e);
        }
    }

    private String mappedHostPort(String containerId, ExposedPort port) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        Ports.Binding[] bindings = inspect.getNetworkSettings().getPorts().getBindings().get(port);
        if (bindings == null || bindings.length == 0 || bindings[0].getHostPortSpec() == null) {
            throw new SandboxException("Action server port " + port + " was not published for " + containerId);
        }
        return bindings[0].getHostPortSpec();
    }

    /**
     * Readiness under {@code network: none}: the action server is unreachable from outside, so
     * probe it from <i>inside</i> the container. This proves the container is running, that the
     * non-root uid can execute, and that the JVM came up under the read-only rootfs — the three
     * things that would otherwise fail much later and much less legibly.
     */
    private void awaitHealthyInContainer(String containerId) {
        long deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            assertStillRunning(containerId);
            ExecOutcome probe = execInContainer(containerId,
                List.of("sh", "-c", "curl -fsS --max-time 2 http://127.0.0.1:" + ACTION_SERVER_PORT + "/health"), 10);
            if (probe.exitCode() == 0) {
                return;
            }
            last = probe.output();
            sleepBriefly();
        }
        throw new SandboxException("Sandbox action server did not answer on port "
            + ACTION_SERVER_PORT + " within " + HEALTH_TIMEOUT.toSeconds() + "s in container "
            + containerId + " (last probe: " + last.strip() + "). An image built before the action "
            + "server read SC_ACTION_PORT listens on 8080 instead and looks exactly like this — "
            + "rebuild it with: mvn -o -pl sc-sandbox-action-server -am package && docker build -t "
            + image + " sc-sandbox-action-server. Container log: " + logTail(containerId));
    }

    /** Readiness under {@code network: bridge}: the published port must actually answer. */
    private void awaitHealthyOverHttp(String containerId, String baseUrl) {
        long deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
            .timeout(Duration.ofSeconds(3)).GET().build();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            assertStillRunning(containerId);
            try {
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (Exception e) {
                last = e; // server not up yet
            }
            sleepBriefly();
        }
        throw new SandboxException("Sandbox action server did not become healthy within "
            + HEALTH_TIMEOUT.toSeconds() + "s at " + baseUrl
            + "; container log: " + logTail(containerId), last);
    }

    /**
     * Fails fast (with the container's own log) when the main process died on startup — the usual
     * symptom of an image that predates the hardening, e.g. one whose JVM cannot write $HOME.
     */
    private void assertStillRunning(String containerId) {
        InspectContainerResponse.ContainerState state;
        try {
            state = dockerClient.inspectContainerCmd(containerId).exec().getState();
        } catch (Exception e) {
            throw new SandboxException("Sandbox " + containerId + " vanished: " + e.getMessage(), e);
        }
        if (state != null && Boolean.FALSE.equals(state.getRunning())) {
            throw new SandboxException("Sandbox " + containerId + " exited (code "
                + state.getExitCodeLong() + ") before becoming healthy; container log: "
                + logTail(containerId));
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new SandboxException("Interrupted waiting for sandbox health", ie);
        }
    }

    /** Best-effort tail of the container's own output, for diagnosing launch failures. */
    private String logTail(String containerId) {
        StringBuilder sb = new StringBuilder();
        try (ResultCallback.Adapter<Frame> cb = dockerClient.logContainerCmd(containerId)
                .withStdOut(true).withStdErr(true).withTail(30)
                .exec(new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Frame frame) {
                        synchronized (sb) {
                            sb.append(new String(frame.getPayload()));
                        }
                    }
                })) {
            cb.awaitCompletion(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // diagnostics only
        }
        synchronized (sb) {
            String text = sb.toString().strip();
            return text.isEmpty() ? "(empty)" : text;
        }
    }

    public void kill(SandboxHandle handle) {
        if (handle != null) {
            killContainer(handle.containerId());
        }
    }

    public void killContainer(String containerId) {
        safeKill(containerId);
    }

    /**
     * Once per JVM: registers the shutdown hook that removes whatever this JVM still has running,
     * and removes STOPPED containers earlier orchestrators left behind. Only containers carrying
     * {@link #OWNER_LABEL} and only ones that have already exited - a running one may belong to
     * another orchestrator on this machine, and nothing without the label is ever touched.
     */
    private void housekeeping() {
        if (!HOUSEKEEPING.compareAndSet(false, true)) {
            return;
        }
        DockerClient client = dockerClient;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (String id : List.copyOf(LIVE)) {
                try {
                    client.removeContainerCmd(id).withForce(true).exec();
                } catch (Exception ignored) {
                    // already gone
                }
            }
        }, "swarmcoder-sandbox-cleanup"));
        try {
            for (com.github.dockerjava.api.model.Container stale : dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(List.of(OWNER_LABEL))
                    .withStatusFilter(List.of("exited", "dead"))
                    .exec()) {
                log.info("Removing stopped sandbox {} left by an earlier run", stale.getId());
                dockerClient.removeContainerCmd(stale.getId()).withForce(true).exec();
            }
        } catch (Exception e) {
            log.debug("Could not look for leftover sandboxes: {}", e.getMessage());
        }
    }

    /** Stops the container from inside after {@link #MAX_LIFETIME_SECONDS}; see that field. */
    private void startLifetimeWatchdog(String containerId) {
        try {
            ExecCreateCmdResponse created = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c", "sleep " + MAX_LIFETIME_SECONDS + "; kill -TERM 1")
                .withUser(SANDBOX_USER)
                .exec();
            dockerClient.execStartCmd(created.getId()).withDetach(true)
                .exec(new ResultCallback.Adapter<>()).close();
        } catch (Exception e) {
            log.warn("Sandbox {} has no lifetime limit ({}); it is still removed when its "
                + "candidate finishes", containerId, e.getMessage());
        }
    }

    private void safeKill(String containerId) {
        if (containerId == null) {
            return;
        }
        LIVE.remove(containerId);
        try {
            dockerClient.killContainerCmd(containerId).exec();
        } catch (Exception ignored) {
            // already dead
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception ignored) {
            // already gone
        }
    }

    /**
     * Handle to a running sandbox.
     *
     * @param actionServerUrl base URL of the in-container action server, or <b>null</b> under
     *                        {@link NetworkPolicy#NONE} where no port can be published — drive the
     *                        container with {@link #execInContainer} instead
     */
    public record SandboxHandle(String containerId, String actionServerUrl) {}

    /** Outcome of one {@link #execInContainer} call; stdout and stderr interleaved in {@code output}. */
    public record ExecOutcome(int exitCode, String output, boolean timedOut) {}

    /** Unchecked failure launching or reaching a sandbox. */
    public static class SandboxException extends RuntimeException {
        public SandboxException(String message) {
            super(message);
        }

        public SandboxException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
