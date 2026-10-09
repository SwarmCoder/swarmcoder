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
 *
 * <h2>An application is started on a copy</h2>
 * A started application writes: a data folder in the tree, files in its home folder. The shared
 * container and its tree would hand that to whatever is started next. {@link #openOnCopy} is a
 * container of its own on a copy of the tree, for one start (journeys, section 77).
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
            return new Box(null, null, new LocalProcessExecTarget(root), null);
        }
        return launch(docker, root, who, browser, referenceMounts(referenceRoots.get()), null);
    }

    private Box launch(DockerSandboxManager docker, Path root, String who, boolean browser,
                       List<DockerSandboxManager.ReadOnlyMount> readOnly, Path removedOnClose) {
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
            handle = docker.launch(root.toString(), "", readOnly, image);
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
        return new Box(docker, handle, new InTree(target, root), removedOnClose);
    }

    /** Where the tree being copied is mounted, read-only, in a container of {@link #openOnCopy}. */
    private static final String AS_BUILT = "/sc-tree-as-built";
    /** How long the copy of a built tree may take before the box is given up. */
    private static final int COPY_TIMEOUT_SECONDS = 900;

    /**
     * A box nothing has run in, on a COPY of the tree as it stands now (section 77, live run
     * 104). An application started in it sees the same files at the same place
     * ({@code /workspace}) as it would in the tree, and whatever it writes - in the tree, in its
     * home folder, in the temporary folder - goes when the box is closed. The tree itself is
     * mounted read-only for the copying and cannot be written to from this box.
     *
     * <p>The copy is made inside the container, by {@code cp -a}: links, modes and file times
     * arrive as the build left them, so a start command that looks at file times does not build
     * again. It is a folder beside the tree, removed on {@link Box#close}.
     *
     * <p>With no container this is {@link #open(Path, String, boolean)}: refused, or this PC
     * when that was allowed by name - and nothing that serves an application runs there.
     *
     * @throws DockerSandboxManager.SandboxException as {@link #open(Path, String)}, and when the
     *                                               tree could not be copied
     */
    public Box openOnCopy(Path tree, String who, boolean browser) {
        DockerSandboxManager docker = sandbox.get();
        if (docker == null) {
            return open(tree, who, browser);
        }
        Path root = tree.toAbsolutePath().normalize();
        Path copy = root.resolveSibling(root.getFileName() + "-copy-"
            + java.util.UUID.randomUUID().toString().substring(0, 8));
        Box box = null;
        try {
            java.nio.file.Files.createDirectories(copy);
            List<DockerSandboxManager.ReadOnlyMount> readOnly =
                new java.util.ArrayList<>(referenceMounts(referenceRoots.get()));
            readOnly.add(new DockerSandboxManager.ReadOnlyMount(root.toString(), AS_BUILT));
            box = launch(docker, copy, who, browser, readOnly, copy);
            long began = System.currentTimeMillis();
            // Entry by entry: the folder /workspace itself is a mount this user does not own,
            // and cp cannot set its times.
            ExecResult copied = box.target().exec("cd " + AS_BUILT + " && find . -mindepth 1 "
                + "-maxdepth 1 -exec cp -a {} /workspace/ ';'", COPY_TIMEOUT_SECONDS);
            if (!copied.succeeded()) {
                String said = copied.output() == null ? "" : copied.output().strip();
                throw new DockerSandboxManager.SandboxException(who + " was not run: the tree "
                    + "could not be copied for it (exit " + copied.exitCode()
                    + (copied.timedOut() ? ", timed out" : "") + "): "
                    + (said.length() <= 600 ? said : said.substring(said.length() - 600)));
            }
            log.info("{}: tree {} copied in {} s; what is started here writes to the copy only",
                who, root, (System.currentTimeMillis() - began) / 1000);
            return box;
        } catch (IOException | RuntimeException e) {
            if (box != null) {
                box.close();
            } else {
                removeFolder(copy);
            }
            if (e instanceof DockerSandboxManager.SandboxException refused) {
                throw refused;
            }
            throw new DockerSandboxManager.SandboxException(who + " was not run: a copy of the "
                + "tree could not be made for it (" + e + ")", e);
        }
    }

    /**
     * One clean start per journey for {@link JourneyRunner}: each call is a new
     * {@link #openOnCopy} of the tree as it stands at that moment.
     */
    public JourneyRunner.Starts cleanStarts(Path tree, String who) {
        return () -> openOnCopy(tree, who, true);
    }

    /** Removes a folder this class made, with everything in it. Never throws. */
    private static void removeFolder(Path folder) {
        if (folder == null || !java.nio.file.Files.exists(folder,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            java.nio.file.Files.walkFileTree(folder, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    java.nio.file.Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException e)
                        throws IOException {
                    java.nio.file.Files.delete(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            log.warn("The copy of a tree at {} could not be removed ({}); it is safe to delete",
                folder, e.toString());
        }
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
    public static final class Box implements JourneyRunner.Start {

        private final DockerSandboxManager docker;
        private final DockerSandboxManager.SandboxHandle handle;
        private final ExecTarget target;
        /** The copy of a tree this box was opened on, or null when it is on the tree itself. */
        private final Path removedOnClose;

        private Box(DockerSandboxManager docker, DockerSandboxManager.SandboxHandle handle,
                    ExecTarget target, Path removedOnClose) {
            this.docker = docker;
            this.handle = handle;
            this.target = target;
            this.removedOnClose = removedOnClose;
        }

        @Override
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
            removeFolder(removedOnClose);
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
