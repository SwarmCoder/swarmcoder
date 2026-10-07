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
package com.swarmcoder.domain;

/**
 * How many workers one task gets, and which of the three places said so.
 *
 * <p>Three layers, most specific first: the STORY, then the PROJECT, then the global settings
 * file. Every layer is optional and "unset" means inherit, so a project that states nothing
 * behaves exactly like the global default and a story that states nothing behaves exactly like its
 * project. There is a fourth, {@link Layer#BUILT_IN}, for when nothing anywhere states a number.
 *
 * <p><b>Why the layer travels with the number.</b> A value that silently comes from somewhere is
 * how {@code splitAcrossFamilies} came to read as switched on while doing nothing for months. So
 * this carries both, the run log prints the sentence, and the number is stamped on the task in the
 * run record next to the reason it has that number.
 *
 * <p><b>Why four rather than eight.</b> Six or seven of eight attempts were passing the build, so
 * the extra attempts were buying almost nothing: the swarm exists as a filter and at that hit rate
 * it is barely filtering. Four attempts against two tasks does twice the work for the same number
 * of workers running at once — which is the number that governs load, not the number of tasks.
 */
@NotReachableFromStoreRoot("resolve() is called at dispatch and the number/label are unpacked "
    + "into SwarmPolicy's own int/String fields (see SwarmPolicy.withWorkers); the SwarmSizing "
    + "instance itself is never assigned to a field")
public record SwarmSizing(int workersPerTask, Layer layer, String label) {

    /** Where a number came from, most specific first. */
    public enum Layer {
        /** The operator (or the planner) sized this one story. */
        STORY,
        /** The repository's own {@code .swarmcoder/project.yaml}. */
        PROJECT,
        /** The settings file's {@code swarm:} block. */
        GLOBAL,
        /** Nothing said anything, so the number below was used. */
        BUILT_IN
    }

    /**
     * Four attempts per task, unless something says otherwise.
     *
     * <p>This was eight until 2026-08-29. Six or seven of the eight were surviving the build, so
     * four of them were spent proving what the first four already proved.
     */
    public static final int BUILT_IN_WORKERS_PER_TASK = 4;

    /** Never fewer than one worker, whatever any layer says. */
    public static final int MINIMUM = 1;

    /**
     * The most specific number anybody stated, with the reason it won.
     *
     * @param story    the story's own number, or null when the story says nothing
     * @param storyKey the story's human label ("S3"), used only in the sentence
     * @param project  the project's number, or null when its project.yaml says nothing
     * @param global   the settings file's number, or null when the {@code swarm:} block says nothing
     */
    public static SwarmSizing resolve(Integer story, String storyKey, Integer project,
                                      Integer global) {
        if (isSet(story)) {
            String named = storyKey == null || storyKey.isBlank() ? "this story" : "story " + storyKey;
            return new SwarmSizing(clamp(story), Layer.STORY, named + " asks for " + clamp(story));
        }
        if (isSet(project)) {
            return new SwarmSizing(clamp(project), Layer.PROJECT,
                "this project's own settings ask for " + clamp(project));
        }
        if (isSet(global)) {
            return new SwarmSizing(clamp(global), Layer.GLOBAL,
                "the settings file asks for " + clamp(global));
        }
        return new SwarmSizing(BUILT_IN_WORKERS_PER_TASK, Layer.BUILT_IN,
            "nothing asks for a number, so every task gets " + BUILT_IN_WORKERS_PER_TASK);
    }

    /** One sentence for the run log and the story card. */
    public String sentence() {
        return workersPerTask + (workersPerTask == 1 ? " worker" : " workers")
            + " on each piece of work, because " + label;
    }

    private static boolean isSet(Integer value) {
        return value != null && value >= MINIMUM;
    }

    private static int clamp(Integer value) {
        return Math.max(MINIMUM, value);
    }
}
