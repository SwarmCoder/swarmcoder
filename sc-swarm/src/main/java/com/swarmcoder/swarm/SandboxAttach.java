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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.HostExecution;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.verify.ExecTarget;
import com.swarmcoder.verify.SandboxExecTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The single place that decides where a worker's commands and a candidate's verification run.
 *
 * <p>Owner decision, 2026-10-02: no code written or chosen by a model may run with access to the
 * PC's drives. So there are three outcomes and none of them is a quiet fallback:
 *
 * <ul>
 *   <li>a sandbox is configured and its container started: run inside it;</li>
 *   <li>a sandbox is configured and the container could not be started:
 *       {@link Attachment#refusal()} carries the reason and the caller fails the candidate.
 *       {@code sandbox.required: false} used to turn this into "run on the workstation" and no
 *       longer does anything;</li>
 *   <li>no sandbox is configured at all: refused the same way, unless
 *       {@link HostExecution} was switched on by name - the operator's
 *       {@code sandbox.enabled: false}, or a scripted test's {@code @ModelCodeOnThisPc}. Only
 *       then {@link Attachment#localAllowed()}.</li>
 * </ul>
 *
 * <p>The product's own commands that execute model-written code (red check, wave compile, final
 * integration, story delivery) follow the same rule through {@code BuildBoxes} in sc-verify.
 */
public final class SandboxAttach {

    private static final Logger log = LoggerFactory.getLogger(SandboxAttach.class);

    private SandboxAttach() {
    }

    /**
     * Outcome of trying to obtain a sandbox.
     *
     * @param handle  the live container, or null when running on the host
     * @param target  where commands should run, or null to use the caller's local target
     * @param refusal non-null when the caller must NOT proceed: the sandbox was required and
     *                could not be provided
     */
    public record Attachment(DockerSandboxManager.SandboxHandle handle, ExecTarget target,
                             String refusal) {

        /** The caller must fail the candidate/run with {@link #refusal()} as the reason. */
        public boolean refused() {
            return refusal != null;
        }

        /** Commands may run on this PC: no sandbox, and {@link HostExecution} was switched on. */
        public boolean localAllowed() {
            return refusal == null && target == null;
        }
    }

    private static final Attachment LOCAL = new Attachment(null, null, null);

    /**
     * @param sandbox          the configured manager, or null when there is none
     * @param worktreeHostPath absolute host path of the candidate's worktree
     * @param writeSetEnv      comma-separated write set for the container
     * @param who              short label for logs, e.g. {@code "Worker 2"} or {@code "verification"}
     */
    public static Attachment attach(DockerSandboxManager sandbox, String worktreeHostPath,
                                    String writeSetEnv, String who) {
        return attach(sandbox, worktreeHostPath, writeSetEnv, who, List.of());
    }

    /**
     * @param readOnly reference folders the container may read and never write; with the worktree
     *                 and the Maven repository they are everything it can see of the machine
     */
    public static Attachment attach(DockerSandboxManager sandbox, String worktreeHostPath,
                                    String writeSetEnv, String who,
                                    List<DockerSandboxManager.ReadOnlyMount> readOnly) {
        if (sandbox == null) {
            java.util.Optional<String> allowedBy = HostExecution.allowedBy();
            if (allowedBy.isEmpty()) {
                String reason = HostExecution.refusal(who);
                log.error("{}: {}", who, reason);
                return new Attachment(null, null, reason);
            }
            log.warn("{}: MODEL CODE RUNS ON THIS PC, with your files and your network in reach. "
                + "Allowed by {}.", who, allowedBy.get());
            return LOCAL;
        }
        try {
            DockerSandboxManager.SandboxHandle handle =
                sandbox.launch(worktreeHostPath, writeSetEnv, readOnly);
            SandboxExecTarget target = new SandboxExecTarget(sandbox, handle.containerId());
            target.setLogContext(who);
            return new Attachment(handle, target, null);
        } catch (Exception e) {
            String reason = "the container could not be started: " + e.getMessage()
                + ". Code a model wrote or chose runs only inside a container, never on this PC, "
                + "so nothing was run instead. Check that Docker is running.";
            log.error("{}: {}", who, reason);
            return new Attachment(null, null, reason);
        }
    }

    /** Tears down a sandbox obtained from {@link #attach}; safe on a host-fallback attachment. */
    public static void release(DockerSandboxManager sandbox, Attachment attachment) {
        if (sandbox != null && attachment != null && attachment.handle() != null) {
            sandbox.kill(attachment.handle());
        }
    }
}
