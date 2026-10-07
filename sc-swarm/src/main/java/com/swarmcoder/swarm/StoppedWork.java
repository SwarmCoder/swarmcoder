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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.KillReason;

/**
 * Whether the change of a worker that was stopped is worth verifying (owner decision after the
 * audit of 2026-10-02).
 *
 * <p>A worker stopped for making no progress, at its turn cap, out of room, or because its place
 * was needed has said nothing about its code: the stop is a statement about the session. Until
 * now such a candidate was never verified, so a change that was complete - the worker had
 * written everything and was still looking around when it was stopped - was thrown away and the
 * task paid for a repair round or failed. A stopped candidate with a change is now verified
 * exactly like one that ended by itself, and may survive and be selected.
 *
 * <p>Not verified: a candidate with no change; one stopped because its task already had a
 * candidate that passed ({@code SUPERSEDED} - nothing is gained by a second verdict); one stopped
 * for changing protected files ({@code WRITESET_VIOLATION} - what it did to the tree that judges
 * it is not to be trusted); and one whose code was already established not to compile.
 */
final class StoppedWork {

    /** The name of the run-meter marks: {@code stopped candidate verified|<why>|<task>|<worker>|<survived or failed>}. */
    static final String SPAN = "stopped candidate verified|";

    private StoppedWork() {}

    static boolean worthVerifying(KillReason reason, String diff) {
        if (reason == null || diff == null || diff.isBlank()) {
            return false;
        }
        return switch (reason) {
            case SUPERSEDED, WRITESET_VIOLATION, COMPILE_FAIL_TWICE -> false;
            default -> true;
        };
    }

    static boolean worthVerifying(CandidateSolution candidate) {
        return candidate != null && candidate.state() == CandidateState.KILLED
            && worthVerifying(candidate.killReason(), candidate.diffUnified());
    }
}
