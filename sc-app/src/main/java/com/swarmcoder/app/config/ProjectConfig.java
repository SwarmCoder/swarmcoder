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

import java.util.List;

/**
 * Per-project config, read from {@code <projectPath>/.swarmcoder/project.yaml} and layered over
 * the global config. Lets a project override its display name, add context folders, pick
 * different models/thinking per role — e.g. a GUI project pointing the architect at a stronger
 * model — and say how many attempts a piece of its work gets. Everything is optional; absent
 * fields fall back to the global config.
 *
 * <p>{@code swarm} is the middle of the three sizing layers (story → project → global) and follows
 * the {@code roles} precedent exactly: same file, same "absent means inherit" rule, same
 * per-project settings dialog. It is not a second override mechanism.
 */
public record ProjectConfig(
    @JsonProperty("name") String name,
    @JsonProperty("contextPaths") List<String> contextPaths,
    @JsonProperty("protectedPaths") List<String> protectedPaths,
    @JsonProperty("roles") RolesConfig roles,
    /** How many workers each of this project's tasks gets; absent means inherit the global. */
    @JsonProperty("swarm") ProjectSwarmConfig swarm
) {
    /** The four-field shape, kept so existing call sites construct this unchanged. */
    public ProjectConfig(String name, List<String> contextPaths, List<String> protectedPaths,
                         RolesConfig roles) {
        this(name, contextPaths, protectedPaths, roles, null);
    }

    /**
     * What this project says about workers per task, or null when it says nothing.
     *
     * <p>Null and "four" have to stay different answers: the run log names the layer that won, and
     * it cannot do that if inheriting looks identical to stating the inherited value.
     */
    public Integer statedWorkersPerTask() {
        return swarm == null ? null : swarm.nPerTask();
    }

    /**
     * What this project says about tool turns per worker, or null when it says nothing.
     *
     * <p>Same rule as the worker count above: null and a stated number must stay distinguishable
     * so the run log can name the layer that won.
     */
    public Integer statedMaxToolTurns() {
        return swarm == null ? null : swarm.maxToolTurns();
    }

    /**
     * Locked-down modules declared by the project itself, ADDED to the global
     * {@code SwarmConfig.protectedPaths} rather than replacing them (union, resolved in
     * {@code ProjectContext}).
     *
     * <p>Replacement semantics would make this file a way to UNLOCK a module: project.yaml lives
     * in the repository, so anything that can edit the repo — including a worker whose write set
     * happens to cover it — could drop a global lock by listing one path of its own. Additive is
     * the only direction that cannot be used to widen access; a project may lock more of itself,
     * never less than the operator locked globally.
     */
    public List<String> protectedPaths() {
        return protectedPaths == null ? List.of() : protectedPaths;
    }
}
