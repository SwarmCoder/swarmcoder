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
 * How many tool turns one worker gets, and which of the three places said so.
 *
 * <p>This is {@link SwarmSizing} for the other number a task carries, and it deliberately reuses
 * that class's {@link SwarmSizing.Layer} rather than inventing a second override mechanism: same
 * three layers, most specific first (STORY, then PROJECT's {@code .swarmcoder/project.yaml}, then
 * the settings file's {@code budgets:} block), same "unset means inherit" rule, same requirement
 * that the run log names the layer that won.
 *
 * <p><b>Why this exists at all.</b> The number used to be a constant in the worker loop that
 * nothing could reach: {@code TokenBudget.maxToolTurns} was read, but nothing in production ever
 * constructed a {@code TokenBudget}, so every worker everywhere got thirty turns and no file
 * anywhere could say otherwise. Measured on 2026-09-01: workers were dying at turn 31 having spent
 * 71,000 of the 2,500,000 tokens they were allowed — under three percent. The wrong dimension was
 * doing the killing.
 *
 * <p><b>Read the warning on {@link #BUILT_IN_MAX_TOOL_TURNS} before changing it.</b> A turn cap is
 * a poor proxy for cost and is here as a runaway backstop, not as the budget.
 */
@NotReachableFromStoreRoot("resolve() is called at dispatch and the number/label are unpacked "
    + "into SwarmPolicy's own int/String fields (see SwarmPolicy.withTurns); the TurnAllowance "
    + "instance itself is never assigned to a field")
public record TurnAllowance(int maxToolTurns, SwarmSizing.Layer layer, String label) {

    /**
     * A worker gets 120 tool turns unless something says otherwise.
     *
     * <p><b>Where 120 comes from — measured, not chosen.</b> A worker's turns divide into
     * orientation and build-fix cycles.
     *
     * <ul>
     *   <li>ORIENTATION costs about 8 turns. A healthy worker reads a file, reads a neighbour,
     *       runs the build and writes: three to five turns. The investigation nudge in
     *       {@code EarlyKillEnforcer} fires at eight, which is already the measured line between
     *       a worker orienting itself and a worker that has stopped making progress.
     *   <li>A BUILD-FIX CYCLE costs about 3 turns: read the failure, edit, build again. One of
     *       those three is a Maven call, which was measured at 60-90 seconds inside the sandbox
     *       and at 14.5 seconds on an idle host for the demo project.
     * </ul>
     *
     * <p>8 + (40 x 3) = 128, rounded down to 120: forty build-fix cycles, against the ten the old
     * thirty-turn cap allowed. At the measured sandbox cost that is roughly eighty minutes of one
     * worker's wall clock — well inside {@code budgets.wallClockCeilingHours}, and about a tenth
     * of the turns the token budget alone would pay for (2,500,000 tokens at the measured 2,300
     * tokens per turn is over a thousand turns).
     *
     * <p><b>This is a backstop, not a budget.</b> Turns are not what costs money; tokens are, and
     * {@code TokenBudget.maxTotalTokens} already bounds those and is already enforced every turn.
     * A worker doing useful cheap work should not be killed for doing a lot of it. Raise this
     * number freely; the thing that must stay finite is the token ceiling.
     */
    public static final int BUILT_IN_MAX_TOOL_TURNS = 120;

    /** Fewer than this and a worker cannot finish a single build-fix cycle, so nothing may go lower. */
    public static final int MINIMUM = 4;

    /**
     * The most specific number anybody stated, with the reason it won.
     *
     * @param story    the story's own number, or null when the story says nothing
     * @param storyKey the story's human label ("S3"), used only in the sentence
     * @param project  the project's number, or null when its project.yaml says nothing
     * @param global   the settings file's number, or null when the {@code budgets:} block says nothing
     */
    public static TurnAllowance resolve(Integer story, String storyKey, Integer project,
                                        Integer global) {
        if (isSet(story)) {
            String named = storyKey == null || storyKey.isBlank() ? "this story" : "story " + storyKey;
            return new TurnAllowance(clamp(story), SwarmSizing.Layer.STORY,
                named + " asks for " + clamp(story));
        }
        if (isSet(project)) {
            return new TurnAllowance(clamp(project), SwarmSizing.Layer.PROJECT,
                "this project's own settings ask for " + clamp(project));
        }
        if (isSet(global)) {
            return new TurnAllowance(clamp(global), SwarmSizing.Layer.GLOBAL,
                "the settings file asks for " + clamp(global));
        }
        return new TurnAllowance(BUILT_IN_MAX_TOOL_TURNS, SwarmSizing.Layer.BUILT_IN,
            "nothing asks for a number, so every worker gets " + BUILT_IN_MAX_TOOL_TURNS);
    }

    /** One sentence for the run log and the story card. */
    public String sentence() {
        return maxToolTurns + " tool turns for each worker, because " + label;
    }

    private static boolean isSet(Integer value) {
        return value != null && value > 0;
    }

    private static int clamp(Integer value) {
        return Math.max(MINIMUM, value);
    }
}
