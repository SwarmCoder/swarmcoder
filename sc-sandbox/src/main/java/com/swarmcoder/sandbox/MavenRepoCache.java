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
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Why this exists: the Maven repository was being read over a Windows filesystem bridge, and
 * that was four fifths of every sandbox build.</b>
 *
 * <p>Measured on the operator's workstation (Docker Desktop / WSL2, 16 CPUs), building the
 * three-module demo project with {@code mvn -o -q -B -DfastCompile clean compile test-compile}:
 *
 * <pre>
 *   on the host                                      10-12 s
 *   in a sandbox, host ~/.m2 as the resolver tail    41-70 s
 *   in a sandbox, with this cache in front           11-13 s
 * </pre>
 *
 * <p>The cause is not CPU, not container start-up (1-2 s), not the workspace bind mount (moving
 * the workspace onto the container's own tmpfs changed nothing), and not a cold Maven local
 * repository — {@code /tmp/m2}, the writable head of the chained local repo, is still
 * <i>completely empty</i> after a build, because Maven's chained local repository resolves
 * through to the tail and never copies. Every artifact is therefore read from
 * {@code /opt/m2-ro} on <i>every single build</i>, and that mount is 9p/drvfs — the bridge
 * between the Linux VM and the Windows disk. Measured on that mount:
 *
 * <pre>
 *   stat one small file      5.7 ms
 *   open+read one small file  44 ms cold, 8.5 ms warm
 *   large sequential read     87 MB/s
 * </pre>
 *
 * <p>A build of that project touches ~1650 files (~83 MB) in the repository — resolver POM reads
 * and javac opening classpath jars. At tens of milliseconds each that is the whole gap. On a
 * normal Linux filesystem the same reads cost microseconds.
 *
 * <p><b>The fix.</b> Keep a copy of just the artifacts the project's own build commands resolve in
 * a Docker <i>named volume</i>, which lives on the VM's own filesystem and never crosses the
 * bridge, and put it in front of the host repository in Maven's resolver chain:
 *
 * <pre>
 *   -Dmaven.repo.local=/tmp/m2                       writable head, container-private tmpfs
 *   -Dmaven.repo.local.tail=/opt/m2-cache/repository fast, read-only, shared
 *                           ,/opt/m2-ro/repository   the host repo, read-only, still authoritative
 * </pre>
 *
 * <h2>What keeps this safe</h2>
 * <ul>
 *   <li><b>The operator's {@code ~/.m2} is never writable.</b> It is bind-mounted read-only in the
 *       candidate sandboxes, and read-only in both containers this class runs. Nothing here can
 *       write into the shared repository.</li>
 *   <li><b>Candidates cannot write the cache, so they cannot reach each other through it.</b> The
 *       volume is mounted {@code :ro} into every candidate sandbox — a kernel-level read-only
 *       mount, in a container that has dropped every capability and carries
 *       {@code no-new-privileges}, so it cannot be remounted. The one container that mounts it
 *       writable is the populate container below, which runs a fixed {@code cp} script this class
 *       generates and never executes a candidate's code, has no network, no workspace mount and a
 *       read-only rootfs.</li>
 *   <li><b>No candidate code runs anywhere near the writable mount.</b> Discovery runs the
 *       <i>operator's own</i> verification commands against a copy of the <i>operator's own</i>
 *       pristine checkout, in a separate container with the same hardening as a candidate sandbox
 *       and no access to the volume at all. Only the resulting list of file paths crosses over,
 *       and every path is validated ({@link #validated}) to be inside
 *       {@code /opt/m2-ro/repository} and to contain nothing but Maven-coordinate characters
 *       before it is allowed into the copy script.</li>
 *   <li><b>Correctness beats speed.</b> The cache is a byte copy of the host repository, made with
 *       {@code cp -p -u} so a rebuilt {@code SNAPSHOT} in {@code ~/.m2} is re-copied on the next
 *       warm rather than shadowed by a stale one. Anything the discovery pass missed is not in the
 *       cache, so the resolver falls through to the host repository and gets the right artifact —
 *       a miss costs milliseconds, never correctness.</li>
 *   <li><b>The operator's checkout is not touched.</b> Discovery mounts it <b>read-only</b> and
 *       copies it into the container's own tmpfs before building, so no {@code target/} directory
 *       appears in it and {@code clean} cannot delete one that was already there.</li>
 * </ul>
 */
public final class MavenRepoCache {

    private static final Logger log = LoggerFactory.getLogger(MavenRepoCache.class);

    /** Where the shared cache volume is mounted — READ-ONLY in every candidate sandbox. */
    public static final String MOUNT = "/opt/m2-cache";

    /** Default Docker named volume backing the cache. */
    public static final String DEFAULT_VOLUME = "swarmcoder-m2-cache";

    /** Where the operator's pristine checkout is mounted, read-only, during discovery. */
    private static final String SRC_MOUNT = "/opt/src";

    /**
     * Every path the copy script is allowed to touch. Maven coordinates are the only thing that
     * can appear under a repository, so the character class is deliberately narrow: no spaces, no
     * quotes, no {@code $}, no backticks, no wildcards — nothing a shell would re-interpret.
     * (The operator's repository really does contain a directory literally named
     * <code>${quarkus</code>; it is rejected here, which is the intended outcome.)
     */
    private static final Pattern ARTIFACT_PATH = Pattern.compile(
        DockerSandboxManager.HOST_M2_MOUNT + "/repository/[A-Za-z0-9._+~/-]+");

    /** Ceiling on how many artifact paths one warm may copy — a runaway list is a bug, not a cache. */
    private static final int MAX_PATHS = 20_000;

    private static final int DISCOVERY_TIMEOUT_SECONDS = 900;
    private static final int POPULATE_TIMEOUT_SECONDS = 900;

    private final DockerClient docker;
    private final String image;
    private final String mavenRepoHostPath;
    private final String volume;

    MavenRepoCache(DockerClient docker, String image, String mavenRepoHostPath, String volume) {
        this.docker = docker;
        this.image = image;
        this.mavenRepoHostPath = mavenRepoHostPath;
        this.volume = volume;
    }

    /** What one warm did, so the caller can log it and tests can assert on it. */
    public record Report(boolean ran, int artifactsFound, String detail) {
        public static Report skipped(String why) {
            return new Report(false, 0, why);
        }
    }

    /**
     * Discovers what the given build commands resolve out of the host Maven repository and copies
     * those artifacts into the shared read-only cache volume. Blocking and idempotent; safe to
     * call again (unchanged files are skipped, changed ones re-copied).
     *
     * @param repoHostPath native host path of the operator's pristine checkout — mounted READ-ONLY
     * @param commands     the project's own Maven build commands (from its verification contract)
     */
    public Report warm(String repoHostPath, List<String> commands) {
        if (mavenRepoHostPath == null || mavenRepoHostPath.isBlank()) {
            return Report.skipped("no host Maven repository is mounted, so there is nothing to cache");
        }
        if (repoHostPath == null || repoHostPath.isBlank()) {
            return Report.skipped("no project checkout to discover from");
        }
        List<String> maven = mavenCommands(commands);
        if (maven.isEmpty()) {
            return Report.skipped("no Maven commands in the verification contract");
        }
        long started = System.nanoTime();
        List<String> paths;
        try {
            paths = validated(runToCompletion("swarmcoder-m2-discover-",
                discoveryHostConfig(repoHostPath), discoveryScript(maven), DISCOVERY_TIMEOUT_SECONDS));
        } catch (Exception e) {
            return Report.skipped("discovery build failed: " + e.getMessage());
        }
        if (paths.isEmpty()) {
            return Report.skipped("the discovery build named no artifacts under "
                + DockerSandboxManager.HOST_M2_MOUNT);
        }
        try {
            ensureVolume();
            runToCompletion("swarmcoder-m2-populate-", populateHostConfig(),
                populateScript(paths), POPULATE_TIMEOUT_SECONDS);
        } catch (Exception e) {
            return new Report(false, paths.size(), "populating the cache failed: " + e.getMessage());
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        String detail = paths.size() + " artifacts mirrored into volume '" + volume + "' in "
            + seconds + "s; sandbox builds now resolve them without crossing the host filesystem "
            + "bridge";
        log.info("Maven repo cache warm: {}", detail);
        return new Report(true, paths.size(), detail);
    }

    // ---------------------------------------------------------------- pure, unit-testable pieces

    /** Only Maven commands can be replayed with {@code -X}; anything else is left alone. */
    static List<String> mavenCommands(List<String> commands) {
        List<String> maven = new ArrayList<>();
        if (commands == null) {
            return maven;
        }
        for (String command : commands) {
            if (command != null && command.strip().startsWith("mvn ")) {
                maven.add(command.strip());
            }
        }
        return maven;
    }

    /**
     * The discovery script. It copies the read-only checkout onto the container's own tmpfs
     * first — the operator's tree must come out of this byte-identical, and {@code clean} would
     * otherwise delete their {@code target/} directories — then runs each build command with
     * {@code -X} and prints every repository path Maven named.
     *
     * <p>{@code -q} is stripped because it and {@code -X} contradict each other, and a failing
     * command is not fatal: a partial list still makes the next build faster, and anything missing
     * still resolves through the host repository.
     */
    static String discoveryScript(List<String> mavenCommands) {
        StringBuilder sb = new StringBuilder();
        sb.append("set -e\n")
            .append("mkdir -p /tmp/ws\n")
            // --exclude keeps a big checkout inside the container's tmpfs: .git and previous build
            // output are never inputs to dependency resolution.
            .append("tar -C ").append(SRC_MOUNT)
            .append(" --exclude=.git --exclude=target -cf - . | tar -C /tmp/ws -xf -\n")
            .append("cd /tmp/ws\n")
            .append(": > /tmp/x.log\n");
        for (String command : mavenCommands) {
            sb.append(stripQuiet(command)).append(" -X >> /tmp/x.log 2>&1 || true\n");
        }
        sb.append("grep -ohE '").append(DockerSandboxManager.HOST_M2_MOUNT)
            .append("/repository/[A-Za-z0-9._+~/-]+' /tmp/x.log | sort -u\n");
        return sb.toString();
    }

    private static String stripQuiet(String command) {
        return command.replaceAll("(?<=\\s)-q(?=\\s|$)", " ").replaceAll("\\s+", " ").strip();
    }

    /**
     * Keeps only paths that are unmistakably artifacts inside the read-only host repository mount.
     * Everything the copy script later interpolates has passed through here, which is what makes
     * embedding the list in a shell script safe.
     */
    static List<String> validated(List<String> candidatePaths) {
        Set<String> keep = new LinkedHashSet<>();
        for (String raw : candidatePaths) {
            String path = raw == null ? "" : raw.strip();
            if (path.isEmpty() || path.contains("..") || !ARTIFACT_PATH.matcher(path).matches()) {
                continue;
            }
            // Only real artifact files are worth mirroring; a bare directory would drag in
            // everything below it.
            if (!path.contains("/") || path.endsWith("/")) {
                continue;
            }
            keep.add(path);
            if (keep.size() >= MAX_PATHS) {
                break;
            }
        }
        return List.copyOf(keep);
    }

    /**
     * The copy script. For each artifact it mirrors that artifact's whole version directory, which
     * is what picks up the sibling {@code .pom} the resolver reads next to every jar.
     *
     * <p>{@code cp -p -u} is the correctness clause: mtimes are preserved, so a {@code SNAPSHOT}
     * the operator rebuilds after the last warm is newer than the cached copy and gets replaced
     * rather than silently shadowing the real one.
     */
    static String populateScript(List<String> validatedPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("set -e\n")
            .append("mkdir -p ").append(MOUNT).append("/repository\n")
            .append("copied=0\n")
            .append("while IFS= read -r p; do\n")
            .append("  [ -n \"$p\" ] || continue\n")
            .append("  d=${p%/*}\n")
            .append("  rel=${d#").append(DockerSandboxManager.HOST_M2_MOUNT).append("/repository/}\n")
            .append("  mkdir -p \"").append(MOUNT).append("/repository/$rel\"\n")
            .append("  cp -p -u \"$d\"/* \"").append(MOUNT)
            .append("/repository/$rel/\" 2>/dev/null || true\n")
            .append("  copied=$((copied+1))\n")
            .append("done <<'SC_ARTIFACT_PATHS'\n");
        for (String path : validatedPaths) {
            sb.append(path).append('\n');
        }
        sb.append("SC_ARTIFACT_PATHS\n")
            .append("date -u +%Y-%m-%dT%H:%M:%SZ > ").append(MOUNT).append("/.swarmcoder-warmed\n")
            .append("echo \"mirrored $copied artifact directories\"\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------------- Docker plumbing

    private void ensureVolume() {
        try {
            docker.inspectVolumeCmd(volume).exec();
        } catch (Exception notThere) {
            docker.createVolumeCmd().withName(volume).exec();
        }
    }

    /**
     * Discovery container: candidate-grade hardening, the operator's checkout READ-ONLY, the host
     * Maven repository READ-ONLY, and no cache volume at all — it produces a list of paths and
     * nothing else.
     */
    private HostConfig discoveryHostConfig(String repoHostPath) {
        return hardened()
            .withBinds(
                new Bind(repoHostPath, new Volume(SRC_MOUNT), AccessMode.ro),
                hostRepositoryReadOnly());
    }

    /**
     * Populate container: the ONLY place the cache volume is writable. It runs a generated
     * {@code cp} script over validated paths, mounts no workspace, and never sees a candidate's
     * code. It runs as root solely because a fresh Docker named volume is owned by root — with
     * every capability dropped, {@code no-new-privileges}, no network and a read-only rootfs, that
     * uid buys nothing beyond writing the volume.
     */
    private HostConfig populateHostConfig() {
        return hardened()
            .withBinds(
                hostRepositoryReadOnly(),
                new Bind(volume, new Volume(MOUNT), AccessMode.rw));
    }

    /**
     * The repository folder of the operator's {@code ~/.m2} and nothing beside it - the settings
     * files that live next to it are where repository passwords are kept. Same mount a candidate's
     * container gets; see {@link DockerSandboxManager#buildHostConfig}.
     */
    private Bind hostRepositoryReadOnly() {
        return new Bind(DockerSandboxManager.join(mavenRepoHostPath, "repository"),
            new Volume(DockerSandboxManager.HOST_M2_MOUNT + "/repository"), AccessMode.ro);
    }

    private HostConfig hardened() {
        Map<String, String> tmpfs = new LinkedHashMap<>();
        tmpfs.put("/tmp", "rw,exec,nosuid,nodev,mode=1777,size=2147483648");
        tmpfs.put("/home/sandbox", "rw,exec,nosuid,nodev,mode=0777,size=268435456");
        return HostConfig.newHostConfig()
            .withNetworkMode("none")
            .withReadonlyRootfs(true)
            .withCapDrop(Capability.ALL)
            .withSecurityOpts(List.of("no-new-privileges"))
            .withPidsLimit((long) DockerSandboxManager.DEFAULT_PIDS_LIMIT)
            .withTmpFs(tmpfs)
            .withInit(true);
    }

    /** Creates, runs and removes a one-shot container; returns its output as lines. */
    private List<String> runToCompletion(String namePrefix, HostConfig hostConfig, String script,
                                         int timeoutSeconds) {
        String name = namePrefix + UUID.randomUUID().toString().substring(0, 8);
        String id = null;
        try {
            id = docker.createContainerCmd(image)
                .withName(name)
                .withHostConfig(hostConfig)
                .withUser(hostConfig.getBinds() != null && writesTheCache(hostConfig) ? "0:0" : "1000:1000")
                .withEnv("HOME=/home/sandbox", "MAVEN_ARGS=" + DockerSandboxManager.MAVEN_ARGS)
                .withWorkingDir("/tmp")
                .withEntrypoint("sh", "-c", script)
                .withCmd()
                .exec().getId();
            docker.startContainerCmd(id).exec();
            Integer exit = docker.waitContainerCmd(id)
                .exec(new WaitContainerResultCallback())
                .awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);
            List<String> output = logLines(id);
            if (exit == null || exit != 0) {
                throw new DockerSandboxManager.SandboxException(name + " exited " + exit
                    + "; last output: " + tail(output));
            }
            return output;
        } finally {
            if (id != null) {
                try {
                    docker.removeContainerCmd(id).withForce(true).exec();
                } catch (Exception ignored) {
                    // already gone
                }
            }
        }
    }

    private boolean writesTheCache(HostConfig hostConfig) {
        for (Bind bind : hostConfig.getBinds()) {
            if (MOUNT.equals(bind.getVolume().getPath())
                && "rw".equals(bind.getAccessMode().name())) {
                return true;
            }
        }
        return false;
    }

    private List<String> logLines(String containerId) {
        StringBuilder sb = new StringBuilder();
        try (ResultCallback.Adapter<Frame> cb = docker.logContainerCmd(containerId)
                .withStdOut(true).withStdErr(true)
                .exec(new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Frame frame) {
                        synchronized (sb) {
                            sb.append(new String(frame.getPayload()));
                        }
                    }
                })) {
            cb.awaitCompletion(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // whatever arrived is what we work with
        }
        List<String> lines = new ArrayList<>();
        synchronized (sb) {
            Matcher m = Pattern.compile("[^\\r\\n]+").matcher(sb);
            while (m.find()) {
                lines.add(m.group().strip());
            }
        }
        return lines;
    }

    private static String tail(List<String> lines) {
        int from = Math.max(0, lines.size() - 10);
        return String.join(" | ", lines.subList(from, lines.size()));
    }
}
