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

import com.swarmcoder.sandbox.DockerSandboxManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ExecTarget that drives a hardened sandbox container over the Docker Engine {@code /exec} API.
 *
 * <p>This is the transport for the default {@code sandbox.network: none}: Docker publishes no
 * ports for a container without a network, so the in-container action server's HTTP endpoint is
 * unreachable from the orchestrator (see {@link DockerSandboxManager} for the verified details).
 * Engine exec travels over the daemon socket instead and is entirely independent of the
 * container's network namespace, so isolation and drivability stop being mutually exclusive.
 *
 * <p>Commands run as the container's non-root sandbox uid with {@code /workspace} as the working
 * directory, so the reachable filesystem is exactly the candidate's worktree plus the container's
 * private tmpfs — the same surface {@link ActionServerExecTarget} exposes.
 *
 * <h2>Background services, and the part the network has nothing to do with</h2>
 *
 * <p>{@link #startService} used to refuse, and the refusal blamed two things at once: no
 * background channel over Engine exec (true then, implemented here) and the container's absent
 * published ports (true, and beside the point). The second half is what made the old conclusion —
 * "browser checks can only run at final integration" — wrong. {@code --network none} does not
 * remove the container's loopback interface. A container with no network still has {@code lo},
 * still binds {@code 127.0.0.1:8080}, and still serves itself; that was measured at the same time
 * as the missing ports were, in the same container. What {@code none} removes is any route in from
 * the host. So a server started here is invisible to the workstation and perfectly visible to a
 * browser running <b>in the same container</b> — which is why the UI sandbox image
 * ({@code sc-sandbox/images/sc-java-ui}) carries one.
 *
 * <p>{@link #hostCannotReachServices()} states the remaining limitation precisely, so a caller
 * that probes readiness over HTTP from the orchestrator refuses to draw a conclusion instead of
 * reading its own blindness as a candidate whose application will not start.
 */
public final class SandboxExecTarget implements ExecTarget {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecTarget.class);

    /** Where a background service's pid file, script and log live — on the container's own tmpfs. */
    private static final String SERVICE_DIR = "/tmp/sc-services";

    /** Cap on the captured service log pulled back per call. Matches the local target's cap. */
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 256 * 1024;

    /**
     * Hard upper bound on how long a background service may live, enforced by a watchdog
     * <b>inside the container</b>. See {@link #startService} for why that layer is the one that
     * survives an orchestrator crash.
     */
    private static final int SERVICE_WATCHDOG_SECONDS = 30 * 60;

    /** How much of a command is logged — enough to identify it, not the whole thing. */
    private static final int MAX_LOGGED_COMMAND_CHARS = 300;

    private final DockerSandboxManager sandbox;
    private final String containerId;
    /** Worker/task label for the exec-start log line, set once by the caller; "" if unknown. */
    private volatile String logContext = "";
    /** {@code "sh"} for the product's own commands, {@code "bash"} for a model's; see {@link #preferBash}. */
    private volatile String shell = "sh";

    /**
     * Runs one command under a shell and stops it, with everything it started, when its time is
     * up. {@code $1} is the shell wanted, {@code $2} the limit in seconds, {@code $3} the command.
     *
     * <p>Two things the bare {@code sh -c <command>} this replaced did not do. It left a command
     * that overran RUNNING in the container after the caller had given up on it - the wait on the
     * exec stream timed out, the process did not - so a worker's stuck build kept its memory and
     * its files open under every command that followed. {@code timeout} puts the command in its
     * own process group and kills the group. And it could not offer bash: both are looked for at
     * run time and fall back to plain {@code sh}, so an image carrying neither still works.
     */
    private static final String RUNNER =
        "S=sh; [ \"$1\" = bash ] && command -v bash >/dev/null 2>&1 && S=bash; "
        + "if command -v timeout >/dev/null 2>&1; "
        + "then exec timeout -k 5 \"$2\" \"$S\" -c \"$3\"; "
        + "else exec \"$S\" -c \"$3\"; fi";

    /** What {@code timeout} exits with when it had to stop the command. */
    private static final int TIMEOUT_EXIT = 124;
    /** ...and when the command ignored TERM and was killed. */
    private static final int TIMEOUT_KILLED_EXIT = 137;

    /** See {@link #browserChecksInside()}. */
    private volatile boolean browserInside;

    public SandboxExecTarget(DockerSandboxManager sandbox, String containerId) {
        this.sandbox = sandbox;
        this.containerId = containerId;
    }

    /**
     * Names the worker/task this target's commands belong to, for the exec-start/exit log lines —
     * mirrors {@link LocalProcessExecTarget#setLogContext}. Set once by the caller right after
     * construction (see {@code SandboxAttach}).
     */
    public void setLogContext(String context) {
        this.logContext = context == null ? "" : context;
    }

    /**
     * Runs commands under bash when the image has it. For a worker's exec tool: models write
     * bash, and the image's plain {@code sh} is dash, which rejects {@code [[ ]]}, arrays and
     * {@code set -o pipefail}. The product's own commands stay on {@code sh}.
     */
    public SandboxExecTarget preferBash() {
        this.shell = "bash";
        return this;
    }

    /**
     * Makes browser checks of a served application run inside this container, with the browser
     * the UI image carries. Off by default: a candidate's own verification keeps declining them
     * (see {@link #hostCannotReachServices()}), which is a decision about cost. Final integration
     * and story delivery switch it on, because the alternative for them was this PC.
     */
    public SandboxExecTarget browserChecksInside() {
        this.browserInside = true;
        return this;
    }

    @Override
    public boolean browserChecksRunInside() {
        return browserInside;
    }

    @Override
    public ExecResult exec(String command, int timeoutSeconds) throws IOException {
        Instant start = Instant.now();
        String context = logContext.isEmpty() ? "" : " [" + logContext + "]";
        log.info("exec{}: container={} timeout={}s command={}", context, containerId,
            timeoutSeconds, truncateForLog(command));
        try {
            int limit = Math.max(1, timeoutSeconds);
            // The caller's own wait is a little longer than the limit enforced inside, so the
            // normal way a command times out is "it was stopped", not "we stopped listening".
            DockerSandboxManager.ExecOutcome outcome = sandbox.execInContainer(containerId,
                List.of("sh", "-c", RUNNER, "sc-exec", shell, String.valueOf(limit), command),
                limit + 15);
            Duration duration = Duration.between(start, Instant.now());
            boolean timedOut = outcome.timedOut()
                || ((outcome.exitCode() == TIMEOUT_EXIT || outcome.exitCode() == TIMEOUT_KILLED_EXIT)
                    && duration.toSeconds() >= limit);
            log.info("exec{} done: container={} exit={}{} duration={}s", context, containerId,
                outcome.exitCode(), timedOut ? " (timed out)" : "", duration.toSeconds());
            return new ExecResult(timedOut ? -1 : outcome.exitCode(), outcome.output(), timedOut,
                duration);
        } catch (DockerSandboxManager.SandboxException e) {
            // Contract: a non-zero exit is a result, an unreachable target is an exception.
            throw new IOException("Sandbox " + containerId + " unreachable", e);
        }
    }

    private static String truncateForLog(String command) {
        if (command == null) {
            return "";
        }
        return command.length() > MAX_LOGGED_COMMAND_CHARS
            ? command.substring(0, MAX_LOGGED_COMMAND_CHARS) + "…" : command;
    }

    @Override
    public String readFile(String relativePath, int maxBytes) throws IOException {
        // `cat` rather than a dedicated read channel: the Engine exec API only carries commands,
        // and the sandbox image is always Linux (see DockerSandboxManager's cross-platform note).
        ExecResult result = exec("cat -- " + shellQuote(relativePath), 30);
        if (result.exitCode() != 0) {
            return null;
        }
        String content = result.output();
        return content.length() > maxBytes ? content.substring(0, maxBytes) : content;
    }

    @Override
    public List<String> listFiles(String relativeDir, String suffix) throws IOException {
        ExecResult result = exec(
            "find " + shellQuote(relativeDir) + " -type f -name " + shellQuote("*" + suffix) + " 2>/dev/null | sort",
            30);
        List<String> files = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                files.add(trimmed.startsWith("./") ? trimmed.substring(2) : trimmed);
            }
        }
        return files;
    }

    @Override
    public void deleteDir(String relativePath) throws IOException {
        exec("rm -rf -- " + shellQuote(relativePath), 30);
    }

    /**
     * The one thing a sandbox genuinely cannot do: hand a running service to a client outside the
     * container. That is not a missing feature, it is the isolation working. Measured on this
     * machine: a container started with {@code --network none -p 18080:8080} reports an empty
     * {@code NetworkSettings.Ports}, answers nothing on the host on any port, and serves its own
     * {@code 127.0.0.1:8080} happily when asked from inside.
     */
    @Override
    public Optional<String> hostCannotReachServices() {
        return Optional.of("the candidate's application runs inside a container with "
            + "sandbox.network=none, which publishes no ports at all, so nothing on the "
            + "workstation can open it — this process's own HTTP client and the workstation's "
            + "browser included. The application is unaffected and serves normally on the "
            + "container's own loopback, where a browser running INSIDE the container reaches it. "
            + "A failure to connect from here is therefore not evidence about the candidate");
    }

    /**
     * Starts a background service inside the container and returns a handle that really does stop
     * it.
     *
     * <p><b>How it works.</b> One Engine exec launches a wrapper under {@code setsid}, so the
     * service becomes a session leader and everything it forks — Maven, a forked JVM, a
     * framework's own child processes — lands in one process group. The wrapper records that
     * group's id in a pid file on the container's tmpfs and redirects the service's combined
     * output to a log file beside it. The launching exec then returns; the service is reparented
     * to the container's pid 1 and keeps running, which is ordinary {@code docker exec} behaviour
     * and is why nothing has to stay attached to it. The command travels base64-encoded and is
     * decoded into a script file inside the container, so a serve command containing quotes,
     * {@code &&} or newlines cannot break the wrapper.
     *
     * <p><b>How a leaked service is prevented.</b> Three layers, because the first is the only one
     * under the caller's control and the caller can die:
     * <ol>
     *   <li>{@link ServiceHandle#close()} signals the whole process <i>group</i> — TERM, then KILL
     *       after a grace period — so a service that forked children dies with them. It is
     *       idempotent and never throws, so it is safe on the failure and timeout paths as well as
     *       the happy one.</li>
     *   <li>A watchdog inside the container KILLs that same group after
     *       {@value #SERVICE_WATCHDOG_SECONDS} seconds regardless. If the orchestrator is killed
     *       mid-check, the service still stops.</li>
     *   <li>The container is killed when the candidate finishes, and killing a container kills
     *       every process in it. A service can never outlive its candidate — and, the part that
     *       matters for the operator's machine, it can never hold a workstation port, because a
     *       {@code network: none} container has none to hold.</li>
     * </ol>
     */
    @Override
    public ServiceHandle startService(String command) throws IOException {
        String id = "svc-" + UUID.randomUUID().toString().substring(0, 8);
        String pidFile = SERVICE_DIR + "/" + id + ".pid";
        String logFile = SERVICE_DIR + "/" + id + ".log";
        String cmdFile = SERVICE_DIR + "/" + id + ".sh";
        String encoded = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8));

        // The wrapper holds no candidate-authored text at all — only generated ids — so there is
        // nothing in it that could be quoted wrong. The command itself arrives as base64.
        String inner = "SCPID=$$; echo \"$SCPID\" > " + pidFile + "; "
            + "( sleep " + SERVICE_WATCHDOG_SECONDS + "; kill -KILL \"-$SCPID\" 2>/dev/null ) & "
            + "exec sh " + cmdFile;
        String launcher = "mkdir -p " + SERVICE_DIR + " && "
            + "printf %s " + shellQuote(encoded) + " | base64 -d > " + cmdFile + " && "
            + "setsid sh -c " + shellQuote(inner) + " > " + logFile + " 2>&1 < /dev/null & "
            // Give the wrapper a moment to record its group id, so isAlive() cannot answer "no"
            // purely because it looked a millisecond too early.
            + "i=0; while [ $i -lt 50 ]; do [ -s " + pidFile + " ] && break; sleep 0.1; "
            + "i=$((i+1)); done; cat " + pidFile + " 2>/dev/null";

        ExecResult started = exec(launcher, 60);
        String pid = firstNumber(started.output());
        if (pid == null) {
            throw new IOException("Could not start a background service in sandbox " + containerId
                + ": the wrapper recorded no process group (output: " + started.output().strip()
                + ")");
        }
        log.debug("Sandbox {} started service {} as process group {}", containerId, id, pid);
        return new InContainerService(id, pid, pidFile, logFile, cmdFile);
    }

    /** A background service running in the container, addressed by its process group id. */
    private final class InContainerService implements ServiceHandle {

        private final String id;
        private final String pid;
        private final String pidFile;
        private final String logFile;
        private final String cmdFile;
        private volatile boolean closed;

        private InContainerService(String id, String pid, String pidFile, String logFile,
                                   String cmdFile) {
            this.id = id;
            this.pid = pid;
            this.pidFile = pidFile;
            this.logFile = logFile;
            this.cmdFile = cmdFile;
        }

        @Override
        public boolean isAlive() {
            if (closed) {
                return false;
            }
            try {
                ExecResult result = exec(aliveFunction(pid)
                    + "if sc_alive; then echo SC_ALIVE; else echo SC_DEAD; fi", 20);
                return result.output().contains("SC_ALIVE");
            } catch (IOException e) {
                // The container is gone, so the service certainly is not running.
                return false;
            }
        }

        @Override
        public String outputSoFar() {
            try {
                return exec("tail -c " + MAX_CAPTURED_OUTPUT_BYTES + " " + logFile + " 2>/dev/null",
                    30).output();
            } catch (IOException e) {
                return "[service log unavailable: " + e.getMessage() + "]";
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            String group = shellQuote("-" + pid);
            String stop = aliveFunction(pid)
                + "kill -TERM " + group + " 2>/dev/null; "
                + "i=0; while [ $i -lt 20 ]; do sc_alive || break; sleep 0.5; i=$((i+1)); done; "
                + "kill -KILL " + group + " 2>/dev/null; sleep 0.3; "
                // The log stays: it is the only account of a service that died on startup.
                + "rm -f " + pidFile + " " + cmdFile + "; "
                + "if sc_alive; then echo SC_STILL_RUNNING; else echo SC_STOPPED; fi";
            try {
                ExecResult result = exec(stop, 60);
                if (!result.output().contains("SC_STOPPED")) {
                    log.warn("Sandbox {} service {} did not stop ({}); it dies with the container",
                        containerId, id, result.output().strip());
                }
            } catch (IOException e) {
                // The container is unreachable or already gone — itself the strongest possible
                // stop. Never throw out of close(): callers use it from a finally block.
                log.debug("Sandbox {} service {} could not be signalled ({}); the container is "
                    + "gone or going, which stops it anyway", containerId, id, e.getMessage());
            }
        }
    }

    /**
     * A shell function {@code sc_alive} that answers "is any process of this group still doing
     * anything", defined so it can be pasted in front of a command that uses it.
     *
     * <p>It walks {@code /proc} rather than asking {@code kill -0}, and the difference is not
     * cosmetic. {@code kill -0} succeeds for a <b>zombie</b>: a killed process whose parent has not
     * reaped it still owns a pid-table entry and still accepts signal 0. The container's pid 1 is
     * the action server, which reaps nothing, so without this a stopped service would report itself
     * alive forever and {@code close()} would log a failure to stop something that was already
     * dead. (Sandboxes now also run with Docker's init, which reaps orphans — but a container
     * launched by something else may not, and the check should not depend on that.)
     *
     * <p>Field 3 of {@code /proc/<pid>/stat} after the command name is the process group id and
     * field 1 is the state; the {@code sed} strips everything up to the last {@code ')'} because a
     * process name can itself contain spaces and brackets.
     */
    private static String aliveFunction(String pid) {
        return "sc_alive() { for d in /proc/[0-9]*; do "
            + "s=$(sed -n '1s/.*) //p' \"$d/stat\" 2>/dev/null); [ -n \"$s\" ] || continue; "
            + "set -- $s; "
            + "if [ \"$1\" != Z ] && [ \"$3\" = " + pid + " ]; then return 0; fi; "
            + "done; return 1; }; ";
    }

    /** The first bare integer in the output — the wrapper's group id, past any shell chatter. */
    private static String firstNumber(String output) {
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.chars().allMatch(Character::isDigit)) {
                return trimmed;
            }
        }
        return null;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
