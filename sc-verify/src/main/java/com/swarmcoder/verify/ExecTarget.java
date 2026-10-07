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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Where verification commands run. Two implementations exist:
 * {@link LocalProcessExecTarget} (workstation process, used for worktree verification until
 * sandboxes are live) and {@link ActionServerExecTarget} (in-sandbox action server, the
 * spec-compliant target per spec §10.1).
 *
 * <p>All paths are workspace-relative with forward slashes.
 */
public interface ExecTarget {

    /**
     * Runs a single shell command in the workspace root and waits for completion.
     * Never throws on command failure — a non-zero exit code or timeout is a result,
     * not an exception. Throws only when the target itself is unreachable.
     */
    ExecResult exec(String command, int timeoutSeconds) throws IOException;

    /**
     * Reads a workspace-relative file, or null if it does not exist.
     * Content beyond maxBytes is truncated.
     */
    String readFile(String relativePath, int maxBytes) throws IOException;

    /**
     * Lists workspace-relative paths of regular files under the given directory (recursive)
     * whose names end with the given suffix. Returns an empty list when the directory
     * does not exist.
     */
    List<String> listFiles(String relativeDir, String suffix) throws IOException;

    /**
     * Recursively deletes a workspace-relative directory if it exists. Used to clear stale
     * test-report directories between pipeline stages so results never bleed across stages.
     */
    void deleteDir(String relativePath) throws IOException;

    /**
     * Starts a long-running service (an app server for browser checks) without waiting for
     * completion. The returned handle stops the service and its children.
     *
     * @throws UnsupportedOperationException on targets that cannot run background services
     *         ({@link ActionServerExecTarget}: the action server has no background endpoint)
     */
    ServiceHandle startService(String command) throws IOException;

    /**
     * Why a service started by {@link #startService} cannot be reached from <b>this</b> process,
     * or empty when it can.
     *
     * <p>This exists because "the service started" and "I can open it in a browser here" are two
     * different facts, and a target where the first is true and the second is false used to be
     * indistinguishable from a candidate whose application does not start. A sandbox under
     * {@code sandbox.network: none} is exactly that target: the application really does come up
     * and really does serve on the container's own loopback, and no client on the workstation can
     * ever see it, because Docker publishes no ports for a container with no network. Anything
     * probing readiness over HTTP from the orchestrator must ask this first, or it will read
     * "unreachable" as "did not start" and fail a candidate for the harness's own limitation.
     *
     * <p>Empty by default: a target whose services run where this process runs (the local
     * worktree) is reachable, and that is the common case.
     */
    default Optional<String> hostCannotReachServices() {
        return Optional.empty();
    }

    /**
     * True when a browser check of a served application must run INSIDE this target: start the
     * application there, look at it with the browser there, and bring the result back. That is the
     * only way a browser reaches an application in a container with no network, and it is how
     * final integration and story delivery check a merged tree without running it on this PC.
     * See {@link BrowserVerifier}.
     */
    default boolean browserChecksRunInside() {
        return false;
    }

    /**
     * The local filesystem root of this target's workspace, when it has one. Present for
     * {@link LocalProcessExecTarget} (a real worktree directory), empty for targets whose
     * workspace is not directly readable from this process (e.g. an in-sandbox action server).
     * Used by the advisory LSP pre-compile signal, which is inherently local (spec §S6).
     */
    default Optional<Path> localRoot() {
        return Optional.empty();
    }

    /** Handle to a running background service started via {@link #startService}. */
    interface ServiceHandle extends AutoCloseable {
        boolean isAlive();

        /** Output captured so far (capped), for diagnosing services that die on startup. */
        String outputSoFar();

        @Override
        void close();
    }
}
