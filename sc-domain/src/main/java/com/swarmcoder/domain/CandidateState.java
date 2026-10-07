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

public enum CandidateState {
    RUNNING, KILLED, FAILED, SURVIVED, SELECTED, ARCHIVED,
    /**
     * The worker stopped and nothing has judged it yet.
     *
     * <p>Appended last, and never persisted: it is worked out by the Console when a worker's
     * session has closed but its candidate has not been archived, which is the whole time
     * verification is running a build. Before it existed those workers were in no collection the
     * run graph reads, and their chips VANISHED for the minutes verification took - so the
     * operator read a chip that was gone as a worker that had failed. A status display may change
     * what it says about something; it may never stop saying anything about it.
     */
    FINISHED;

    /**
     * What happened to this attempt, in words rather than in the constant's name.
     *
     * <p>The run graph draws each attempt as a coloured dot, and the dot's hover text was the
     * constant itself — a person hovering a red square was told {@code KILLED} (UX v3 §5 rule 3).
     * Colour and words are separate things now, and these are the words. Short: they are read on a
     * tooltip and in a one-line property row.
     */
    public String label() {
        return switch (this) {
            case RUNNING -> "still writing";
            case KILLED -> "stopped early";
            case FAILED -> "did not work";
            case SURVIVED -> "passed the checks";
            case SELECTED -> "the one chosen";
            case ARCHIVED -> "kept aside";
            case FINISHED -> "waiting to be checked";
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
