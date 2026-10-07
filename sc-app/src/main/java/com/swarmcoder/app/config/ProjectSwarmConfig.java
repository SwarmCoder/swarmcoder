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
package com.swarmcoder.app.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The {@code swarm:} block of a project's own {@code .swarmcoder/project.yaml} — the middle of the
 * three sizing layers (story → project → global; see
 * {@link com.swarmcoder.domain.SwarmSizing}).
 *
 * <p>Two settings, and both are properties of the CODE. How many attempts a piece of work gets, and
 * how many tool turns one attempt is allowed, both depend on the repository: a project whose build
 * is slow, or whose tests are flaky, or which one model happens to be good at, wants different
 * numbers from the machine's general default. How many attempts a piece of work gets is a property
 * of the CODE — a repository whose build is slow, or whose tests are flaky, or which one model
 * happens to be good at, wants a different number from the machine's general default. The other
 * two concurrency numbers protect the workstation, and a file that lives inside a repository the
 * swarm writes to may not raise a limit that exists to protect the machine it runs on. That is the
 * same rule {@code protectedPaths} follows here: a project may ask for less, never more.
 *
 * <p>Unset (null) means inherit the global {@code swarm.nPerTask}.
 */
public record ProjectSwarmConfig(
    @JsonProperty("nPerTask") Integer nPerTask,
    /**
     * Tool turns each worker on this project gets; absent means inherit the global
     * {@code budgets.maxToolTurnsPerWorker}. This is the setting a project with a slow build
     * reaches for: a build-fix cycle costs about three turns, so a project whose Maven call takes
     * a minute and a half buys its workers real time here.
     */
    @JsonProperty("maxToolTurns") Integer maxToolTurns
) {
    /** The one-field shape, kept so existing call sites construct this unchanged. */
    public ProjectSwarmConfig(Integer nPerTask) {
        this(nPerTask, null);
    }
}
