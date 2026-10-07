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

public enum TaskState {
    PENDING, READY, DISPATCHED, VERIFYING, JUDGING, SELECTED, INTEGRATED, BLOCKED, CANCELLED, DONE,
    /**
     * Every attempt failed its checks, and a fresh set of workers is fixing them.
     *
     * <p>Appended last, so nothing already in a store is re-labelled. It exists because the engine
     * has always done this and never said so: the log read "0/4 candidates survived verification"
     * and then started four more workers, while the screen showed a task quietly "being checked"
     * throughout. The operator saw new workers appear on a task he had no reason to think had gone
     * wrong, and read it as a restart.
     */
    REPAIRING;

    /**
     * What this task is doing, in words rather than in the constant's name.
     *
     * <p>A task is machinery inside one build, but the run's task list is on screen, and it used
     * to render these constants raw — DISPATCHED, VERIFYING, JUDGING, INTEGRATED — which
     * is the machine describing itself (UX v3 §5 rule 3). Two words at most: this is read on
     * a small badge.
     */
    public String label() {
        return switch (this) {
            case PENDING -> "waiting";
            case READY -> "ready";
            case DISPATCHED -> "being written";
            case VERIFYING -> "being checked";
            case JUDGING -> "being judged";
            case SELECTED -> "winner chosen";
            case INTEGRATED -> "merged in";
            case REPAIRING -> "fixing what failed";
            case BLOCKED -> "stuck";
            case CANCELLED -> "dropped";
            case DONE -> "done";
        };
    }

    /** {@link #label()} for a state that arrives as a string, and "" for one that is not a state. */
    public static String labelOf(String state) {
        if (state == null || state.isEmpty()) {
            return "";
        }
        try {
            return valueOf(state).label();
        } catch (IllegalArgumentException e) {
            return state.toLowerCase();
        }
    }
}
