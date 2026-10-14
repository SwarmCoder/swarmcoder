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

import java.util.regex.Pattern;

/**
 * A worker's shell pipeline fails when any stage of it fails (section 114).
 *
 * <p>12 of 34 worker builds of one audited build piped {@code mvn} into {@code tail} or
 * {@code grep}. A shell gives a pipeline the exit code of its LAST stage, so each of them was
 * answered {@code exit=0} while the build's own output said {@code [ERROR]}, and all four first
 * candidates of a task reported done on a tree that did not compile. With {@code pipefail} the
 * pipeline's exit is that of the last stage that failed, which is the build's.
 *
 * <p>Decided from the command's syntax only (a {@code |} that is not {@code ||}), never from
 * what it runs or prints. A {@code |} inside quotes is taken as a pipe too; the option then
 * changes nothing, since a command without a pipeline is unaffected by it. The option is set only
 * when the shell has it: {@code (set -o pipefail)} is tried in a subshell first, because a POSIX
 * {@code sh} that lacks it exits on an unknown option, and such a shell says so in the output.
 */
final class PipelineFailure {

    /** A pipe that is not half of {@code ||}. */
    private static final Pattern PIPE = Pattern.compile("(?<!\\|)\\|(?!\\|)");

    /** What a shell without the option prints, so the result says the exit is the last stage's. */
    static final String NO_PIPEFAIL = "[this shell has no pipefail: a pipeline's exit code is its "
        + "last stage's]";

    /** On the exit line of a pipeline's result. */
    static final String NOTE = " (pipefail: a failing stage fails the command)";

    /** On the exit line of a pipeline run by a shell that has no such option (cmd.exe). */
    static final String NOTE_NONE = " (a pipeline's exit code is its last stage's on this shell)";

    private static final String PREFIX = "if (set -o pipefail) 2>/dev/null; then set -o pipefail; "
        + "else echo '" + NO_PIPEFAIL.replace("'", "'\\''") + "'; fi\n";

    private PipelineFailure() {
    }

    /** True when the command holds a pipeline. */
    static boolean isPipeline(String command) {
        return command != null && PIPE.matcher(command).find();
    }

    /** The command to run: as given, with {@code pipefail} set first when it holds a pipeline. */
    static String wrap(String command, boolean posixShell) {
        return posixShell && isPipeline(command) ? PREFIX + command : command;
    }

    /** What the exit line says about the command, "" when it holds no pipeline. */
    static String note(String command, boolean posixShell) {
        return !isPipeline(command) ? "" : posixShell ? NOTE : NOTE_NONE;
    }
}
