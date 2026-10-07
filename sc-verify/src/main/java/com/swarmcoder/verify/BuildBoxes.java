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
package com.swarmcoder.verify;

import com.swarmcoder.domain.HostExecution;
import com.swarmcoder.sandbox.DockerSandboxManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Where every command runs that executes code a model wrote or chose: in a container that sees one
 * tree and nothing else of this PC.
 *
 * <p>Owner decision, 2026-10-02: <b>no code written or chosen by a model may run with access to
 * the PC's drives.</b> A worker's own commands and a candidate's verification already ran in a
 * container. The commands the PRODUCT issues did not: the red check, the test author's
 * {@code compile_test}, the wave compile, final integration, story delivery, the baseline. Their
 * text comes from the operator's {@code verify.yaml}, but what they execute is a test or a build
 * file a model wrote. They all get their target here now, and this class is the only place that
 * decides between a container and this PC.
 *
 * <h2>The rule</h2>
 * <ul>
 *   <li>A sandbox is configured: the commands run in a container. If it cannot be started the
 *       caller gets a {@link DockerSandboxManager.SandboxException} saying why. There is no
 *       fallback to this PC.</li>
 *   <li>No sandbox is configured: refused, with the same exception - unless
 *       {@link HostExecution} was switched on by name (the operator's {@code sandbox.enabled:
 *       false}, or a scripted test's {@code @ModelCodeOnThisPc}). Then, and only then, a
 *       {@link LocalProcessExecTarget}.</li>
 * </ul>
 *
 * <h2>What the container sees</h2>
 * The tree read-write at {@code /workspace} (its git pointer read-only), each reference root
 * read-only at {@code /reference/<label>}, the Maven repository read-only, no network, not root:
 * the same closed list a worker's container has ({@link DockerSandboxManager#launch}).
 *
 * <h2>One container per tree</h2>
 * {@link #use} hands out a target that starts its container at the first command and keeps it for
 * every later command on the same tree, until {@link #release} - which the caller runs where it
 * removes the tree, in the same {@code finally}. {@link #open} is the one-shot form.
 */
public final class BuildBoxes {

    private static final Logger log = LoggerFactory.getLogger(BuildBoxes.class);

    private final Supplier<DockerSandboxManager> sandbox;
    private final Supplier<Map<String, Path>> referenceRoots;
    /** The containers {@link #use} started, by tree. */
    private final ConcurrentMap<Path, Box> shared = new ConcurrentHashMap<>();

    /**
     * @param sandbox        the manager in force when a box is opened, or a supplier of null when
     *                       there is none
     * @param referenceRoots label to folder, mounted read-only
     */
    public BuildBoxes(Supplier<DockerSandboxManager> sandbox,
                      Supplier<Map<String, Path>> referenceRoots) {
        this.sandbox = sandbox == null ? () -> null : sandbox;
        this.referenceRoots = referenceRoots == null ? Map::of : referenceRoots;
    }

    /** Boxes for one manager and no reference roots. */
    public static BuildBoxes of(DockerSandboxManager sandbox) {
        return new BuildBoxes(() -> sandbox, Map::of);
    }

    /** No container at all: every use is refused unless {@link HostExecution} is switched on. */
    public static BuildBoxes none() {
        return new BuildBoxes(() -> null, Map::of);
    }

    /** Whether a container is what a box opened now would be. */
    public boolean contained() {
        return sandbox.get() != null;
    }

    /** The read-only mounts for a set of reference roots, in a stable order. */
    public static List<DockerSandboxManager.ReadOnlyMount> referenceMounts(Map<String, Path> roots) {
        if (roots == null || roots.isEmpty()) {
            return List.of();
        }
        return roots.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(root -> new DockerSandboxManager.ReadOnlyMount(
                root.getValue().toAbsolutePath().toString(),
                DockerSandboxManager.referenceMountPath(root.getKey())))
            .toList();
    }

    /**
     * A box for one tree, for the caller to close.
     *
     * @param tree the folder the commands work on; the only thing mounted read-write
     * @param who  what is being run, for the log and for the refusal, e.g. {@code "the red check"}
     * @throws DockerSandboxManager.SandboxException when no container can be had and this PC was
     *                                               not allowed
     */
    public Box open(Path tree, String who) {
        return open(tree, who, false);
    }

    /**
     * @param browser true when the commands include a browser check of a served application. The
     *                container is then started from the UI image when that image is on this
     *                machine, and browser checks run inside it; when it is not, the browser check
     *                reports that it could not run and names the image to build
     */
    public Box open(Path tree, String who, boolean browser) {
        DockerSandboxManager docker = sandbox.get();
        Path root = tree.toAbsolutePath().normalize();
        if (docker == null) {
            Optional<String> allowedBy = HostExecution.allowedBy();
            if (allowedBy.isEmpty()) {
                throw new DockerSandboxManager.SandboxException(HostExecution.refusal(who));
            }
            log.warn("{}: MODEL CODE RUNS ON THIS PC, in {}, with your files and your network "
                + "in reach. Allowed by {}.", who, root, allowedBy.get());
            return new Box(null, null, new LocalProcessExecTarget(root));
        }
        String image = null;
        if (browser) {
            if (docker.hasImage(DockerSandboxManager.uiImage())) {
                image = DockerSandboxManager.uiImage();
            } else {
                log.warn("{}: the image with a browser in it ({}) is not on this machine, so the "
                    + "browser check will report that it could not run. Everything else runs.",
                    who, DockerSandboxManager.uiImage());
            }
        }
        DockerSandboxManager.SandboxHandle handle;
        try {
            handle = docker.launch(root.toString(), "", referenceMounts(referenceRoots.get()), image);
        } catch (DockerSandboxManager.SandboxException e) {
            throw new DockerSandboxManager.SandboxException(who + " was not run: the container it "
                + "runs in could not be started (" + e.getMessage() + "). Code a model wrote runs "
                + "only inside a container, never on this PC, so nothing was run instead. Check "
                + "that Docker is running, then resume.", e);
        }
        SandboxExecTarget target = new SandboxExecTarget(docker, handle.containerId());
        target.setLogContext(who);
        if (browser) {
            target.browserChecksInside();
        }
        log.info("{}: runs in container {} (tree {})", who, handle.containerId(), root);
        return new Box(docker, handle, new InTree(target, root));
    }

    /**
     * The target for a tree whose container is shared by everything run on that tree until
     * {@link #release}. The container is started here, at the first call for the tree.
     *
     * @throws DockerSandboxManager.SandboxException as {@link #open(Path, String)}
     */
    public ExecTarget use(Path tree, String who) {
        return use(tree, who, false);
    }

    /** As {@link #use(Path, String)}; {@code browser} as in {@link #open(Path, String, boolean)}. */
    public ExecTarget use(Path tree, String who, boolean browser) {
        Path root = tree.toAbsolutePath().normalize();
        return shared.computeIfAbsent(root, key -> open(key, who, browser)).target();
    }

    /** Removes the container {@link #use} started for this tree, if any. Never throws. */
    public void release(Path tree) {
        if (tree == null) {
            return;
        }
        Box box = shared.remove(tree.toAbsolutePath().normalize());
        if (box != null) {
            box.close();
        }
    }

    /** One tree's execution place. Closing it removes the container. */
    public static final class Box implements AutoCloseable {

        private final DockerSandboxManager docker;
        private final DockerSandboxManager.SandboxHandle handle;
        private final ExecTarget target;

        private Box(DockerSandboxManager docker, DockerSandboxManager.SandboxHandle handle,
                    ExecTarget target) {
            this.docker = docker;
            this.handle = handle;
            this.target = target;
        }

        public ExecTarget target() {
            return target;
        }

        /** The container's id, or null when this box is this PC. */
        public String containerId() {
            return handle == null ? null : handle.containerId();
        }

        @Override
        public void close() {
            if (docker != null && handle != null) {
                try {
                    docker.kill(handle);
                } catch (RuntimeException e) {
                    log.warn("Could not remove container {}: {}", handle.containerId(),
                        e.getMessage());
                }
            }
        }
    }

    /**
     * A container target that also says where its tree is on this PC. The tree is a folder this
     * process created and can read, so the callers that read files out of it directly (the
     * compile-error readers, the advisory language server) keep working as they did.
     */
    private record InTree(ExecTarget target, Path tree) implements ExecTarget {

        @Override
        public ExecResult exec(String command, int timeoutSeconds) throws IOException {
            return target.exec(command, timeoutSeconds);
        }

        @Override
        public String readFile(String relativePath, int maxBytes) throws IOException {
            return target.readFile(relativePath, maxBytes);
        }

        @Override
        public List<String> listFiles(String relativeDir, String suffix) throws IOException {
            return target.listFiles(relativeDir, suffix);
        }

        @Override
        public void deleteDir(String relativePath) throws IOException {
            target.deleteDir(relativePath);
        }

        @Override
        public ServiceHandle startService(String command) throws IOException {
            return target.startService(command);
        }

        @Override
        public Optional<String> hostCannotReachServices() {
            return target.hostCannotReachServices();
        }

        @Override
        public boolean browserChecksRunInside() {
            return target.browserChecksRunInside();
        }

        @Override
        public Optional<Path> localRoot() {
            return Optional.of(tree);
        }
    }
}
