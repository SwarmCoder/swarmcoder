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

import java.time.Duration;

/**
 * Outcome of one command execution on an {@link ExecTarget}.
 *
 * @param exitCode  process exit code; {@code -1} when the command timed out or could not start
 * @param output    combined stdout+stderr, truncated to the target's capture cap
 * @param timedOut  true when the command was killed for exceeding its timeout
 * @param duration  wall time of the execution
 */
public record ExecResult(int exitCode, String output, boolean timedOut, Duration duration) {

    public boolean succeeded() {
        return exitCode == 0 && !timedOut;
    }
}
