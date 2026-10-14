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
package com.swarmcoder.runtime;

import java.util.UUID;

/**
 * Thrown out of the swarm when the build it is executing was ended from outside: its story was
 * sent back, started again or dropped (section 110). Nothing further is started for the build, its
 * candidates in session were cancelled, and the workflow driving it stops without writing it.
 *
 * <p>Not a park and not a failure: no question is raised and nothing is recorded on the story. The
 * store already says the build is over.
 */
public class RunEnded extends RuntimeException {

    private final UUID runId;

    public RunEnded(UUID runId, String where) {
        super("Run " + runId + " was ended from outside " + where
            + " - nothing further is started in it", null, false, false);
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}
