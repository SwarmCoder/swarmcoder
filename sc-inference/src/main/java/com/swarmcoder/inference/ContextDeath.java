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
package com.swarmcoder.inference;

import com.swarmcoder.domain.KillReason;

/**
 * A worker that stopped because its conversation no longer fitted the room it had — the one kind
 * of death that says something about how many workers should share a model server.
 *
 * <p>Two things produce it, and both already exist; this class only names them so that
 * {@link AdaptiveConcurrency} can count them:
 *
 * <ul>
 *   <li>{@link KillReason#TIMEOUT}, which since 2026-09-01 is only ever produced for a request the
 *       server accepted and did not finish answering while it was demonstrably answering other
 *       callers ({@link EndpointOutage#isOutage(Throwable, EndpointOutage.Attempt)}): a
 *       conversation grown too big to prefill inside the timeout.
 *   <li>{@link KillReason#BUDGET_EXCEEDED} thrown by the history compaction — its permanent floor
 *       had crossed the high-water mark, or three compactions running could not get back under it.
 *       The worker's own token-budget guard throws the same constant and is not about room either,
 *       so it is told apart mechanically: the guard is the worker's own code, which knows when it
 *       fired ({@code killedByOwnGuard} below). Since 2026-09-03 the turn cap is no longer part of
 *       this ambiguity at all — it throws its own {@link KillReason#TURN_CAP}, which is never a
 *       {@link ContextDeath}: a worker that used up its turns said nothing about whether the room
 *       it had was enough. Before this split, harness run 11 reported two turn-cap kills as
 *       {@code BUDGET_EXCEEDED} with no compaction ever having run.
 * </ul>
 *
 * <p>An orange chip on the graph ({@code WorkerHealth.OVER_BUDGET}) is the same fact seen while the
 * worker is still alive; it is not counted here because a worker that is over budget and then
 * finishes proved that it fitted after all.
 *
 * @param reason which of the two it was
 * @param turns  how many turns it had taken when it stopped
 */
public record ContextDeath(KillReason reason, int turns) {

    /**
     * Classifies how a worker's session ended.
     *
     * @param reason          why it stopped, or null when it finished
     * @param turns           the turns it had taken
     * @param maxTurns        its turn allowance — kept for the {@code turns <= maxTurns} sanity
     *                        check below; no longer needed to separate the turn cap out, which now
     *                        arrives as its own {@link KillReason#TURN_CAP} and never reaches here
     * @param killedByOwnGuard whether the worker's own per-turn guard was what stopped it (the
     *                        token-budget rule in {@code EarlyKillEnforcer}); that guard throws the
     *                        same constant the compaction does and is not about room
     * @return the death, or null when this ending says nothing about room
     */
    public static ContextDeath of(KillReason reason, int turns, int maxTurns, boolean killedByOwnGuard) {
        return of(reason, turns, maxTurns, killedByOwnGuard, false);
    }

    /**
     * As {@link #of(KillReason, int, int, boolean)}, also told whether a timeout was on a small
     * conversation. That is not a room death (live run 74: requests of 9,000 to 17,000 tokens
     * against 262,144 timed out because the ANSWER was too long to write in the time allowed), so
     * it must not make {@link AdaptiveConcurrency} back off.
     *
     * @param timedOutOnSmallConversation true when the TIMEOUT's request carried a conversation of
     *                        at most half the working context
     */
    public static ContextDeath of(KillReason reason, int turns, int maxTurns, boolean killedByOwnGuard,
                                  boolean timedOutOnSmallConversation) {
        if (reason == KillReason.TIMEOUT && timedOutOnSmallConversation) {
            return null;
        }
        if (reason == KillReason.TIMEOUT) {
            return new ContextDeath(reason, turns);
        }
        if (reason == KillReason.BUDGET_EXCEEDED && !killedByOwnGuard && turns <= maxTurns) {
            return new ContextDeath(reason, turns);
        }
        return null;
    }

    /**
     * Whether an ending is evidence about room at all. A worker that never reached a model, was
     * stopped because another attempt had already won, or died of the plumbing around it says
     * nothing about how much room the ones that did run had — so it is neither a death nor a
     * clean finish, and must not advance a recovery.
     */
    public static boolean saysNothingAboutRoom(KillReason reason) {
        return reason == KillReason.ENDPOINT_OUTAGE
            || reason == KillReason.WORKER_ERROR
            || reason == KillReason.SANDBOX_UNAVAILABLE
            || reason == KillReason.SUPERSEDED
            || reason == KillReason.PLACE_NEEDED;
    }

    /** What happened, in the operator's words, for the line that names which kills triggered a change. */
    public String sentence() {
        return switch (reason) {
            // Not "the conversation had grown too big" (live run 74): a small conversation
            // whose ANSWER was too long for the time allowed ends the same way, and the
            // session's own log line says which of the two it was.
            case TIMEOUT -> "its request was not answered in the time allowed, after " + turns
                + " turns, while the server was up - an answer too long to finish in that time, "
                + "or a conversation too big to read in it";
            default -> "its history could not be compacted back under its limit after " + turns
                + " turns";
        };
    }
}
