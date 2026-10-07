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
import com.swarmcoder.domain.SwarmSizing;
import java.util.List;

/**
 * The {@code swarm:} block (spec §17) — the GLOBAL layer of the three sizing layers
 * (story → project → global; see {@link SwarmSizing}).
 *
 * <p>{@code splitAcrossFamilies} alternates workers between the first two configured worker
 * models. With only one model configured it has nothing to split across and does nothing — which
 * is now said out loud at startup, in the run log and on the settings screen rather than being
 * quietly true while the config reads as though model diversity is on.
 *
 * <p>{@code personaIds} is the diversity lever that survives having one model: each worker of a
 * group is asked for something different (smallest change, defensive edges, literal to the tests,
 * willing to tidy). Unset rotates the built-in set — it used to hand every worker the same
 * persona, which is a lever that does nothing.
 *
 * <p><b>The three concurrency numbers, and what they multiply out to.</b> {@code nPerTask} workers
 * attack one task; {@code maxConcurrentTaskGroups} tasks of a run are attacked at once; and
 * {@code maxConcurrentWorkers} is the ceiling on the product — the real number of build+model
 * sessions on this workstation at any instant, across every task, run and project. The first two
 * are how the work is shaped; the third is what protects the machine, because it is the only one
 * that cannot be exceeded by a story or a project asking for more.
 */
public record SwarmEngineConfig(
    @JsonProperty("nPerTask") int nPerTask,
    @JsonProperty("splitAcrossFamilies") boolean splitAcrossFamilies,
    @JsonProperty("maxConcurrentTaskGroups") int maxConcurrentTaskGroups,
    @JsonProperty("tempMin") double tempMin,
    @JsonProperty("tempMax") double tempMax,
    @JsonProperty("dispatch") DispatchConfig dispatch,
    @JsonProperty("personaIds") List<String> personaIds,
    @JsonProperty("maxConcurrentWorkers") int maxConcurrentWorkers
) {
    /**
     * <b>Every accessor above returns exactly what the file said, zero included.</b> The defaults
     * live in the four methods at the bottom instead, because this record is written back out to
     * {@code config.yaml} whenever the settings screen saves anything — an accessor that quietly
     * substituted a default would bake that default into the operator's file, turning "nobody
     * stated a number" into "the settings file states four" and making the run log credit the
     * wrong layer.
     *
     * <p>Tasks of one run worked on at the same time, when the file does not say: no ceiling of
     * its own (2026-10-02). It used to be two, and two tasks at a time on a model server that
     * serves four requests left half of it working on second attempts while ready tasks waited.
     * Now every ready task of a wave is started and the places on the model server decide: the
     * first attempt of each task queues for a place, and further attempts get only places nothing
     * else is waiting for. A number the operator writes in the file is still a ceiling.
     */
    public static final int DEFAULT_MAX_CONCURRENT_TASK_GROUPS = 0;

    /**
     * The ceiling on workers running at once anywhere in this process, when the file does not say.
     *
     * <p>Eight, because eight is what the machine already carries: today's single task with eight
     * workers on it. §10 measured that at ten concurrent workers the wall time was dominated by ten
     * simultaneous local {@code mvn} runs on the workstation rather than by the model server — so
     * the ceiling belongs on total workers, not on tasks, and it belongs a little below the number
     * that was already uncomfortable.
     */
    public static final int DEFAULT_MAX_CONCURRENT_WORKERS = 8;

    /** The pre-2026-08 shape, kept so existing code constructs this unchanged. */
    public SwarmEngineConfig(int nPerTask, boolean splitAcrossFamilies, int maxConcurrentTaskGroups,
                             double tempMin, double tempMax, DispatchConfig dispatch) {
        this(nPerTask, splitAcrossFamilies, maxConcurrentTaskGroups, tempMin, tempMax, dispatch, null, 0);
    }

    /** The shape before a worker ceiling existed. */
    public SwarmEngineConfig(int nPerTask, boolean splitAcrossFamilies, int maxConcurrentTaskGroups,
                             double tempMin, double tempMax, DispatchConfig dispatch,
                             List<String> personaIds) {
        this(nPerTask, splitAcrossFamilies, maxConcurrentTaskGroups, tempMin, tempMax, dispatch,
            personaIds, 0);
    }

    /**
     * What the file literally says about workers per task, or null when it says nothing.
     *
     * <p>This is the value the three-layer resolution consumes: a project or a story may override
     * it, and "the file says nothing" has to stay distinguishable from "the file says four" so the
     * run log can name the layer that actually won.
     */
    public Integer statedNPerTask() {
        return nPerTask >= SwarmSizing.MINIMUM ? nPerTask : null;
    }

    /** Workers per task with the built-in default applied — four, since 2026-08-29. */
    public int workersPerTaskOrDefault() {
        return nPerTask >= SwarmSizing.MINIMUM ? nPerTask : SwarmSizing.BUILT_IN_WORKERS_PER_TASK;
    }

    /**
     * The operator's ceiling on tasks of one run worked on at once, or 0 for none. Unset (and any
     * negative number) means none: the model server's places are what limits it then. A number
     * written in the file is kept exactly, including one that leaves places on the server to be
     * filled with further attempts at the same task.
     */
    public int taskGroupsAtOnce() {
        if (maxConcurrentTaskGroups < 0) {
            return 0;   // the engine reads 0 as "unlimited"
        }
        return maxConcurrentTaskGroups == 0
            ? DEFAULT_MAX_CONCURRENT_TASK_GROUPS : maxConcurrentTaskGroups;
    }

    /**
     * The hard ceiling on workers running at once, process-wide. Unset means
     * {@value #DEFAULT_MAX_CONCURRENT_WORKERS}; a negative number removes the ceiling.
     */
    public int workerCeiling() {
        if (maxConcurrentWorkers < 0) {
            return 0;   // 0 means "no ceiling" to WorkerSlots
        }
        return maxConcurrentWorkers == 0
            ? DEFAULT_MAX_CONCURRENT_WORKERS : maxConcurrentWorkers;
    }
}
