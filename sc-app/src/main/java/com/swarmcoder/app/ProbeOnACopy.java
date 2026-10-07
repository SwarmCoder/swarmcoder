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
package com.swarmcoder.app;

import com.swarmcoder.verify.BuildBoxes;
import com.swarmcoder.verify.ContractProbe;
import com.swarmcoder.verify.VerifySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs the compile probe of a proposed contract the way a candidate's build will run: in a
 * container, on a throwaway copy of the checkout.
 *
 * <p>Owner decision, 2026-10-02: code a model wrote or chose never runs on this PC. The probe
 * builds the project, and a build runs the project's own build files, so it gets the same
 * treatment as every other build here. The operator's working folder is never mounted or written:
 * the build leaves its output (target folders, caches, generated sources) in the copy, and the copy
 * is removed afterwards.
 *
 * <p>A git checkout is copied as a detached worktree at its current commit, which is exactly what a
 * candidate's worktree is cut from, so the probe proves what a run will see. Changes the operator
 * has not committed are not in it. A folder with no git history is copied file by file.
 */
final class ProbeOnACopy {

    private static final Logger log = LoggerFactory.getLogger(ProbeOnACopy.class);

    /** Where throwaway trees live: the same root the run's own throwaway trees use. */
    private static final Path ROOT =
        Path.of(System.getProperty("user.home"), ".swarmcoder", "wt");

    private ProbeOnACopy() {}

    /**
     * @param checkout       the operator's folder; read, never written
     * @param boxes          where the build runs; with no container and no host permission the
     *                       result says nothing was run
     */
    static ContractProbe.Result probe(Path checkout, VerifySpec spec, int timeoutSeconds,
                                      BuildBoxes boxes) {
        return probe(checkout, spec, timeoutSeconds, boxes, ROOT);
    }

    static ContractProbe.Result probe(Path checkout, VerifySpec spec, int timeoutSeconds,
                                      BuildBoxes boxes, Path scratchRoot) {
        Path copy = scratchRoot.resolve("probe-" + UUID.randomUUID().toString().substring(0, 8));
        boolean worktree = false;
        try {
            worktree = copyOf(checkout, copy);
            return ContractProbe.probeCompile(copy, spec, timeoutSeconds, boxes);
        } catch (IOException e) {
            return new ContractProbe.Result(false, false, List.of(), -1, false, Duration.ZERO, "",
                "Nothing was run: a throwaway copy of " + checkout + " could not be made ("
                    + e.getMessage() + "). The probe builds a copy, never the working folder.");
        } finally {
            remove(checkout, copy, worktree);
        }
    }

    /** Makes the copy; true when it is a git worktree of the checkout. */
    private static boolean copyOf(Path checkout, Path copy) throws IOException {
        Files.createDirectories(copy.getParent());
        if (Files.exists(checkout.resolve(".git"))
                && git(checkout, "rev-parse", "--verify", "HEAD")) {
            if (git(checkout, "worktree", "add", "--detach", copy.toString(), "HEAD")) {
                return true;
            }
        }
        Files.walkFileTree(checkout, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(copy.resolve(checkout.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, copy.resolve(checkout.relativize(file).toString()),
                    StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        return false;
    }

    private static void remove(Path checkout, Path copy, boolean worktree) {
        if (worktree) {
            git(checkout, "worktree", "remove", "--force", copy.toString());
            git(checkout, "worktree", "prune");
        }
        if (Files.exists(copy)) {
            deleteTree(copy);
        }
    }

    private static void deleteTree(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    file.toFile().setWritable(true);
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc)
                        throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not remove the probe's throwaway copy {}: {}", dir, e.getMessage());
        }
    }

    private static boolean git(Path in, String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(in.toFile())
                .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
