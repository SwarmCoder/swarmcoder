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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a proposed verification contract once, before the operator is asked to accept it.
 *
 * <p><b>Why.</b> A proposed contract that does not actually build the project is worse than no
 * contract at all. With no contract, verification is skipped loudly and every candidate is marked
 * unverified — the operator knows nothing was checked. With a contract whose compile command is
 * wrong, every candidate fails for a reason that has nothing to do with the candidate, and a whole
 * run is spent proving that the detector guessed badly. Running it once costs a build; getting it
 * wrong costs a run.
 *
 * <p><b>What it does NOT do.</b> It does not run the existing-test suite. On a large repository
 * that is thirty minutes, and it answers a different question: whether the suite is green today is
 * the operator's business, not the detector's. The probe answers exactly one question — does this
 * command build this project — plus, optionally, whether the existing-test command can at least be
 * started and produce a parseable report.
 *
 * <p><b>The project not building on a clean checkout is the common case</b>, not an error in the
 * detection. A missing dependency, a required environment variable, a code generator that has not
 * been run — all of them look identical to a wrong command from here. So the probe reports what it
 * ran, what came back, and how long it took, and says plainly that a red probe means either the
 * command is wrong or the project does not build as checked out. It never decides which. The
 * operator does, on the same screen, with the log tail in front of them.
 */
public final class ContractProbe {

    private static final Logger log = LoggerFactory.getLogger(ContractProbe.class);

    /** How much command output to keep. Enough to see the first real error, not a whole build. */
    private static final int LOG_TAIL_LINES = 60;

    private ContractProbe() {}

    /**
     * What happened when the proposed contract was actually run.
     *
     * @param ran       true when a compile command existed and was executed at all
     * @param compiles  true when every compile command exited 0
     * @param commands  the commands that were run, in order
     * @param exitCode  the exit code of the first command that failed, or 0
     * @param timedOut  true when a command was killed for exceeding its timeout
     * @param duration  wall time of the whole probe
     * @param logTail   the last lines of the failing command's output, or of the last command
     * @param verdict   plain English: what this result means and what to do about it
     */
    public record Result(
        boolean ran,
        boolean compiles,
        List<String> commands,
        int exitCode,
        boolean timedOut,
        Duration duration,
        String logTail,
        String verdict
    ) {
        public String describe() {
            return verdict + " (" + duration.toSeconds() + "s)";
        }
    }

    /**
     * Runs the contract's compile stage in the given directory, once.
     *
     * @param root           the project checkout — the operator's own tree, not a worktree
     * @param spec           the proposed contract
     * @param timeoutSeconds cap for each command; 0 or less uses the spec's own timeout
     */
    public static Result probeCompile(Path root, VerifySpec spec, int timeoutSeconds) {
        return probeCompile(root, spec, timeoutSeconds, (ExecTarget) null);
    }

    /**
     * As {@link #probeCompile(Path, VerifySpec, int)}, in a container that sees {@code root} and
     * nothing else of this PC. For a tree that holds, or may hold, code a model wrote.
     *
     * @param boxes where to run; null runs on this PC, which is right only for the operator's own
     *              project at the moment it is registered
     */
    public static Result probeCompile(Path root, VerifySpec spec, int timeoutSeconds,
                                      BuildBoxes boxes) {
        if (boxes == null) {
            return probeCompile(root, spec, timeoutSeconds);
        }
        Instant start = Instant.now();
        try (BuildBoxes.Box box = boxes.open(root, "The compile probe")) {
            return probeCompile(root, spec, timeoutSeconds, box.target());
        } catch (com.swarmcoder.sandbox.DockerSandboxManager.SandboxException e) {
            return new Result(false, false, List.of(), -1, false,
                Duration.between(start, Instant.now()), "", e.getMessage());
        }
    }

    private static Result probeCompile(Path root, VerifySpec spec, int timeoutSeconds,
                                       ExecTarget given) {
        Instant start = Instant.now();
        List<String> commands = spec == null || spec.compile() == null
            ? List.of() : List.copyOf(spec.compile());
        if (commands.isEmpty()) {
            return new Result(false, false, List.of(), 0, false, Duration.ZERO, "",
                "No compile command was proposed, so nothing could be tried. A contract with no "
                + "compile stage cannot tell a candidate that does not build from one that does — "
                + "every candidate then rests on the tests alone.");
        }

        int timeout = timeoutSeconds > 0 ? timeoutSeconds : spec.effectiveTimeoutSeconds();
        ExecTarget target;
        try {
            target = given != null ? given : new LocalProcessExecTarget(root);
        } catch (RuntimeException e) {
            return new Result(false, false, commands, -1, false,
                Duration.between(start, Instant.now()), "",
                "Could not run anything in " + root + ": " + e.getMessage());
        }

        List<String> executed = new ArrayList<>();
        for (String command : commands) {
            executed.add(command);
            ExecResult result;
            try {
                log.info("Probing proposed compile command in {}: {}", root, command);
                result = target.exec(command, timeout);
            } catch (IOException e) {
                return new Result(true, false, executed, -1, false,
                    Duration.between(start, Instant.now()), e.getMessage(),
                    "The command could not be started at all: " + e.getMessage()
                    + ". That usually means the build tool is not on this machine's PATH.");
            }
            if (result.timedOut()) {
                return new Result(true, false, executed, -1, true,
                    Duration.between(start, Instant.now()), tail(result.output()),
                    "`" + command + "` was still running after " + timeout + " seconds and was "
                    + "killed. Either the build is genuinely that slow — raise timeoutSeconds — or "
                    + "the command is waiting for input that will never come.");
            }
            if (!result.succeeded()) {
                return new Result(true, false, executed, result.exitCode(),
                    false, Duration.between(start, Instant.now()), tail(result.output()),
                    "`" + command + "` exited " + result.exitCode() + ". Either the command is "
                    + "wrong for this project, or the project does not build as checked out — a "
                    + "missing dependency, an unset environment variable, a generator that has not "
                    + "been run. The output below says which. Fix it before starting a run: every "
                    + "candidate will fail this same way, for a reason that has nothing to do with "
                    + "the candidate.");
            }
        }
        Duration took = Duration.between(start, Instant.now());
        return new Result(true, true, executed, 0, false, took, "",
            "The project compiles with these commands, in " + took.toSeconds() + " seconds. Every "
            + "candidate pays that cost, so it is roughly the floor on how long a run can take.");
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) {
            return "(the command produced no output)";
        }
        String[] lines = output.split("\r?\n");
        int from = Math.max(0, lines.length - LOG_TAIL_LINES);
        StringBuilder out = new StringBuilder();
        if (from > 0) {
            out.append("… ").append(from).append(" earlier line(s) omitted …\n");
        }
        for (int i = from; i < lines.length; i++) {
            out.append(lines[i]).append('\n');
        }
        return out.toString();
    }
}
