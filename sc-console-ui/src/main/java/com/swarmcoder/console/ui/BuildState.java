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
package com.swarmcoder.console.ui;

import com.swarmcoder.console.api.RunSummaryDto;
import com.swarmcoder.domain.BuildHealth;
import com.swarmcoder.domain.RunPause;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;

import java.util.List;

/**
 * Whether a story is building, paused, needs you, or stopped — decided in ONE place.
 *
 * <p>Both Plan and Build show work in progress, and each used to decide for itself whether a run
 * was still alive: Plan from a heartbeat age, Build from the run's state. The same story therefore
 * read "building now, 9h 5m" on one screen and "stopped" on the other at the same moment, and the
 * operator had no way to know which to believe.
 *
 * <p>Deleting one of the screens was the wrong fix — seeing what is being built without leaving the
 * board is worth having. The right fix is this: one derivation, two renderings. Build renders it
 * with the buttons because Build is where you act; Plan renders it read-only because Plan is where
 * you decide what to build next. Neither can invent its own answer.
 */
final class BuildState {

    /** The staleness threshold, shared with the server — see {@link BuildHealth}. */
    static final long STALLED_AFTER_MINUTES = BuildHealth.STALLED_AFTER_MINUTES;

    /**
     * The four situations the operator ever needs.
     *
     * <p>PAUSED earns its place by being the one that requires nothing of them: the endpoint is
     * down, the build is waiting, and it will carry on by itself. Folding it into STOPPED (which
     * invites "Build it again") or into WORKING (which is a lie) is precisely what turned one
     * afternoon's outage into 37 dead approvals and three stranded stories.
     */
    enum Kind { WORKING, PAUSED, WAITING, STOPPED }

    private BuildState() {}

    static Kind of(Story story, RunSummaryDto run) {
        if (story.state() == StoryState.REVIEW) {
            return Kind.WAITING;
        }
        if (story.state() == StoryState.BLOCKED) {
            return Kind.STOPPED;
        }
        // The decision itself is BuildHealth's, in sc-domain, so the readiness publisher counting
        // "how many are building" cannot reach a different answer from the badge beside it. This maps
        // that answer onto the word the operator reads.
        return switch (BuildHealth.of(
                run == null ? null : run.getState(),
                run == null ? 0 : run.getHeartbeatAtMillis(),
                run == null ? 0 : run.getPausedSinceMillis(),
                run == null ? 0 : run.getParkedAtMillis(),
                System.currentTimeMillis())) {
            case WORKING -> Kind.WORKING;
            case PAUSED -> Kind.PAUSED;
            case NEEDS_YOU -> Kind.WAITING;
            case STOPPED -> Kind.STOPPED;
        };
    }

    /** The word shown on the badge. */
    static String label(Kind kind) {
        return switch (kind) {
            case WORKING -> "building now";
            case PAUSED -> "paused";
            case WAITING -> "needs you";
            case STOPPED -> "stopped";
        };
    }

    static String badgeClass(Kind kind) {
        return switch (kind) {
            case WORKING -> "badge-primary";
            // Not an error colour: nothing has gone wrong that the operator has to fix, and colouring
            // a self-healing wait red is the same defect as colouring a success red (rule 4).
            case PAUSED -> "badge-info";
            case WAITING -> "badge-warning";
            case STOPPED -> "badge-error";
        };
    }

    static String borderClass(Kind kind) {
        return switch (kind) {
            case WORKING -> "border-primary/30";
            case PAUSED -> "border-info/40";
            case WAITING -> "border-warning/40";
            case STOPPED -> "border-error/40";
        };
    }

    /** Whether this run is waiting for a model endpoint to come back. */
    static boolean isPaused(RunSummaryDto run) {
        return run != null && run.getPausedSinceMillis() > 0;
    }

    /** Whether a workflow stage stopped this run and raised a question for the operator. */
    static boolean isParked(RunSummaryDto run) {
        return run != null && run.getParkedAtMillis() > 0;
    }

    /**
     * Why a parked run stopped, in the operator's own words — the same brief its BLOCKED_TASK
     * question carries, composed server-side ({@code GreenfieldWorkflow.parkRun}) so the card and
     * the question can never disagree about the reason.
     */
    static String parkSentence(RunSummaryDto run) {
        String reason = run == null ? null : run.getParkReason();
        return reason == null || reason.isBlank()
            ? "Stopped, and needs you before it can continue."
            : reason;
    }

    /**
     * The sentence explaining a pause, composed server-side so there is one wording everywhere.
     *
     * <p>Once the pause has outlasted {@link RunPause#ESCALATE_AFTER_MINUTES} the promise that it
     * resumes by itself is no longer the whole story — it is still true, but the operator now has a
     * decision to make — so the sentence says how long it has been and hands the choice over.
     */
    static String pauseSentence(RunSummaryDto run) {
        if (!isPaused(run)) {
            return "";
        }
        String reason = run.getPauseReason() == null || run.getPauseReason().isBlank()
            ? "paused — your model server is not answering; building resumes by itself when it returns."
            : run.getPauseReason();
        long minutes = pausedMinutes(run);
        if (minutes < RunPause.ESCALATE_AFTER_MINUTES) {
            return reason;
        }
        return reason + " It has been " + minutes + " minutes — keep waiting, or stop this build?";
    }

    /** Minutes since this run started waiting for its endpoint; 0 when it is not waiting. */
    static long pausedMinutes(RunSummaryDto run) {
        if (!isPaused(run)) {
            return 0;
        }
        return RunPause.pausedMinutes(run.getPausedSinceMillis(), System.currentTimeMillis());
    }

    /** The run's phase as something an operator would recognise, rather than an enum name. */
    static String phrase(String runState) {
        return switch (runState == null ? "" : runState) {
            case "INTAKE" -> "reading the story";
            case "DESIGN", "DESIGN_REVIEW" -> "working out a design";
            case "PLAN" -> "breaking it into tasks";
            case "TEST_AUTHORING" -> "writing the tests it must pass";
            case "REPRODUCE", "CHARACTERIZE" -> "reproducing the current behaviour";
            case "EXECUTING" -> "writing the code";
            case "FINAL_INTEGRATION" -> "putting the pieces together";
            default -> "in progress";
        };
    }

    static boolean isTerminal(String runState) {
        return BuildHealth.isTerminal(runState);
    }

    /**
     * Minutes since anything persisted this run, or 0 when that cannot be known.
     *
     * <p>0 for a run predating the heartbeat, and it must NOT read as stalled: a run that was never
     * able to report has not gone quiet.
     */
    static long quietMinutes(RunSummaryDto run) {
        if (run == null || run.getHeartbeatAtMillis() <= 0) {
            return 0;
        }
        return BuildHealth.minutesSince(run.getHeartbeatAtMillis(), System.currentTimeMillis());
    }

    /** The story's most recent attempt, or null if it has none. */
    static RunSummaryDto newestRun(Story story, List<RunSummaryDto> runs) {
        RunSummaryDto newest = null;
        for (RunSummaryDto run : runs) {
            boolean mine = story.runIds().stream()
                .anyMatch(id -> id.toString().equals(run.getRunId()));
            if (mine && (newest == null
                    || run.getStartedAtMillis() > newest.getStartedAtMillis())) {
                newest = run;
            }
        }
        return newest;
    }

    /** Is this story anywhere in the build pipeline at all? */
    static boolean inBuild(Story story) {
        return story.state() == StoryState.RUNNING || story.state() == StoryState.BLOCKED
            || story.state() == StoryState.REVIEW;
    }
}
