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

import com.swarmcoder.domain.GuidelineCheck;
import com.swarmcoder.domain.GuidelineCheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the proof commands that house rules declare, in the candidate's workspace (author
 * decision, §21).
 *
 * <p>The single implementation, on purpose. It is used from the verification pipeline's own
 * guideline stage and from the one other place a candidate can be checked without a
 * {@code verify.yaml}, so there is exactly one answer to "did this candidate obey the rules"
 * and one place that answer can be changed.
 *
 * <p><b>Where it runs matters more than what it runs.</b> The commands go through the
 * {@link ExecTarget} it is given — which is the sandbox's action server whenever the sandbox is
 * on, because that is where candidate verification runs. Nothing here reaches for a local process
 * on its own. The commands themselves are resolved from the operator's checkout before they ever
 * get here (see {@link com.swarmcoder.domain.GuidelineCheck}); a worktree the swarm can write to
 * never contributes one.
 *
 * <p>Every declared check runs — the stage does not stop at the first breach — because an operator
 * reading a failed candidate wants the whole list of rules it broke, not the first one.
 */
public final class GuidelineCheckRunner {

    private static final Logger log = LoggerFactory.getLogger(GuidelineCheckRunner.class);
    private static final int OUTPUT_TAIL_LINES = 20;

    private GuidelineCheckRunner() {}

    /**
     * Runs every check and returns one result each, in the order given.
     *
     * @param fullLog appended with the same {@code [stage] $ command / exit=N} shape the rest of
     *                the pipeline logs, so the verification log reads as one document; may be null
     */
    public static List<GuidelineCheckResult> run(ExecTarget target, List<GuidelineCheck> checks,
                                                 StringBuilder fullLog) {
        List<GuidelineCheckResult> results = new ArrayList<>();
        if (checks == null || checks.isEmpty()) {
            return results;
        }
        for (GuidelineCheck check : checks) {
            if (check == null || check.command() == null || check.command().isBlank()) {
                continue;
            }
            append(fullLog, "[guidelines] " + check.scope() + "/" + check.slug() + " $ " + check.command());
            try {
                ExecResult result = target.exec(check.command(), check.effectiveTimeoutSeconds());
                if (fullLog != null && !result.output().isBlank()) {
                    fullLog.append(result.output());
                    if (!result.output().endsWith("\n")) {
                        fullLog.append('\n');
                    }
                }
                append(fullLog, "[guidelines] " + check.slug() + " exit=" + result.exitCode()
                    + (result.timedOut() ? " (TIMED OUT)" : "")
                    + (result.succeeded() && !result.timedOut() ? " — OBEYED" : " — BROKEN"));
                results.add(new GuidelineCheckResult(check.slug(), check.scope(), check.rule(),
                    check.command(), true, result.succeeded() && !result.timedOut(),
                    result.exitCode(), result.timedOut(), tail(result.output())));
            } catch (IOException e) {
                // The target is unreachable, not the rule broken. Recording it as NOT passed is
                // deliberate and matches how the rest of verification fails closed: a check that
                // could not be run has proved nothing, and a candidate must not be waved through
                // on the strength of a check that never happened.
                log.error("Guideline check '{}' could not run: {}", check.slug(), e.getMessage());
                append(fullLog, "[guidelines] " + check.slug() + " EXEC TARGET ERROR: " + e.getMessage());
                results.add(new GuidelineCheckResult(check.slug(), check.scope(), check.rule(),
                    check.command(), false, false, -1, false, e.getMessage()));
            }
        }
        return results;
    }

    /** True when any check in the list was not obeyed. */
    public static boolean anyBroken(List<GuidelineCheckResult> results) {
        if (results == null) {
            return false;
        }
        return results.stream().anyMatch(r -> r != null && !r.passed());
    }

    private static void append(StringBuilder fullLog, String line) {
        if (fullLog != null) {
            fullLog.append(line).append('\n');
        }
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String[] lines = output.strip().split("\n");
        if (lines.length <= OUTPUT_TAIL_LINES) {
            return output.strip();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - OUTPUT_TAIL_LINES; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().strip();
    }
}
