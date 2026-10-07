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
import com.swarmcoder.sandbox.DockerSandboxManager;

import java.util.List;

/**
 * The {@code sandbox:} config block (spec §8).
 *
 * <pre>
 * sandbox:
 *   cpus: 2
 *   memGb: 4
 *   poolExtra: 2
 *   pidsLimit: 512
 *   network: none        # none (default, no egress) | bridge (UNRESTRICTED egress)
 *   allowedHosts: []     # PARSED BUT NOT ENFORCED — see allowedHosts()
 *   required: true       # retired: ignored. A container that will not start always stops the work
 * </pre>
 *
 * Every field is nullable/zero-able on purpose: existing config files predate the new keys, so
 * the accessors below apply the safe default rather than the YAML default of 0/null.
 */
public record SandboxConfig(
    @JsonProperty("cpus") int cpus,
    @JsonProperty("memGb") int memGb,
    @JsonProperty("poolExtra") int poolExtra,
    @JsonProperty("pidsLimit") int pidsLimit,
    @JsonProperty("network") String network,
    @JsonProperty("allowedHosts") List<String> allowedHosts,
    @JsonProperty("required") Boolean required,
    @JsonProperty("enabled") Boolean enabled
) {

    /** Legacy 3-arg shape, kept so existing construction sites keep compiling. */
    public SandboxConfig(int cpus, int memGb, int poolExtra) {
        this(cpus, memGb, poolExtra, 0, null, null, null, null);
    }

    /**
     * Whether worker commands run in a container. <b>Defaults to TRUE.</b>
     *
     * <p>It used to default to false, which meant the shipped behaviour was model-authored shell
     * commands executing on the operator's workstation with the operator's privileges. That is the
     * wrong default for a tool whose entire job is running code it did not write: the safe setting
     * should be what you get without reading the documentation.
     *
     * <p>Turning it off is a deliberate act and the ONLY way model code ever runs on this PC: the
     * app then says so, in plain words, in the log and on the Console. With it on, a missing
     * Docker stops the run with a message naming the remedy. There is no fallback in between.
     */
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    /** Max processes per container; 0/absent → {@link DockerSandboxManager#DEFAULT_PIDS_LIMIT}. */
    public int pidsLimitOrDefault() {
        return pidsLimit > 0 ? pidsLimit : DockerSandboxManager.DEFAULT_PIDS_LIMIT;
    }

    /** Container network policy; absent or unrecognised → {@code none}, the safe default. */
    public DockerSandboxManager.NetworkPolicy networkPolicy() {
        return DockerSandboxManager.NetworkPolicy.parse(network);
    }

    /**
     * Operator egress allowlist. <b>Honest status: parsed, surfaced and warned about, but NOT
     * enforced.</b> Enforcing it needs the allowlist proxy of spec §8.2 (a dedicated docker
     * network whose only route out is a filtering proxy container), which is separate work. Today
     * the real choice is {@code network: none} (no egress at all — the default) versus
     * {@code network: bridge} (everything). {@link DockerSandboxManager} logs a WARN whenever a
     * non-empty allowlist is configured so it can never look like it is doing something.
     */
    public List<String> allowedHosts() {
        return allowedHosts == null ? List.of() : allowedHosts;
    }

    /**
     * Always true. {@code required: false} used to mean "when Docker misbehaves, run the models'
     * commands on the workstation instead". It no longer does anything (owner decision,
     * 2026-10-02): a container that cannot be started stops the run with a message. The key is
     * still read so that an existing settings file loads; {@link #requiredSetToFalse()} lets the
     * app say that it is ignored.
     */
    public boolean requiredOrDefault() {
        return true;
    }

    /** True when the settings file still carries the retired {@code required: false}. */
    public boolean requiredSetToFalse() {
        return required != null && !required;
    }
}
