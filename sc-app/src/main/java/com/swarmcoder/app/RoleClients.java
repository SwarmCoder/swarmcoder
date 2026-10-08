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
package com.swarmcoder.app;

import com.swarmcoder.inference.VllmClient;

/**
 * The per-role inference clients for a project (spec §6.2 de-correlation). Built from config —
 * each role may point at a different endpoint/model and set its own thinking mode. Per-project
 * model overrides will layer here later; today they come from the global roles config.
 */
public record RoleClients(
    VllmClient architect,
    VllmClient reviewer,
    VllmClient testAuthor,
    VllmClient judge,
    VllmClient utility,
    /** The Console chat coder — {@code roles.chat}, falling back to utility. */
    VllmClient chat,
    /** The BRD author behind the "Analyse documents" wizard — {@code roles.requirementsAnalyst}, falling back to chat. */
    VllmClient requirementsAnalyst,
    /** The planner behind the "Plan stories" wizard — {@code roles.storyPlanner}, falling back to chat. */
    VllmClient storyPlanner,
    /**
     * Whether {@code roles.utility} named its own endpoint, as opposed to {@link #utility} being
     * {@link DependencyGraph#clientFor} 's fallback to the process-wide default client. That
     * fallback makes {@link #utility} always non-null, so it cannot by itself answer "is a real
     * utility model configured" — which the help desk's expert tier needs at startup (ExpertDesk
     * javadoc: escalating to an unconfigured default endpoint on every unanswered question is not
     * what "wired" means).
     */
    boolean utilityConfigured,
    /** Same as {@link #utilityConfigured}, for {@code roles.architect} — the desk's fallback role. */
    boolean architectConfigured,
    /**
     * The task planner, which splits a design into tasks and orders them —
     * {@code roles.taskPlanner}, falling back to the architect's own client (section 73).
     */
    VllmClient taskPlanner
) {
    /** The clients as they were before the task planner had its own: it is the architect's. */
    public RoleClients(VllmClient architect, VllmClient reviewer, VllmClient testAuthor,
                       VllmClient judge, VllmClient utility, VllmClient chat,
                       VllmClient requirementsAnalyst, VllmClient storyPlanner,
                       boolean utilityConfigured, boolean architectConfigured) {
        this(architect, reviewer, testAuthor, judge, utility, chat, requirementsAnalyst,
            storyPlanner, utilityConfigured, architectConfigured, architect);
    }
}
