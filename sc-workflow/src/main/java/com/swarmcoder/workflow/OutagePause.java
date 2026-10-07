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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunPause;
import com.swarmcoder.inference.EndpointOutage;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What happens while the model server is not answering (UX v3 §2.4).
 *
 * <p>The whole policy is here — thresholds, backoff, the sentence the operator reads, and the two
 * predicates the UI derives its badge from — because a paused build is shown in more than one place
 * and rule 2 permits exactly one derivation per fact. Nothing outside this class may decide whether
 * a run is paused or how long it has been.
 *
 * <p>The shape of the policy:
 *
 * <ul>
 *   <li><b>It waits, indefinitely.</b> There is no attempt limit. A model server that is rebooting,
 *       being re-quantised, or sitting behind a dropped VPN comes back on its own schedule, and a
 *       build that gave up after N tries would need a person to notice and restart it — which is the
 *       whole cost this feature exists to remove.
 *   <li><b>It costs nothing.</b> Retried role calls are refunded at the {@code CloudGate}, and a
 *       wave lost to an outage never enters the repair round. An outage is not evidence.
 *   <li><b>It escalates by age, not by state.</b> After {@link #ESCALATE_AFTER} the card must say
 *       "needs you" instead of "paused", but the engine keeps waiting regardless: escalation is a
 *       change in what the operator is told, not in what the machine does. So it is derived from the
 *       pause's age ({@link #escalated}) rather than stored, and cannot drift out of agreement with
 *       the pause itself.
 * </ul>
 *
 * <p>The run's state is NOT changed while paused. It stays at the stage it will retry, so
 * crash-resume still restarts from the right place, and no persisted enum needed a new constant
 * (rule 7 — those are append-only and a run's state is the most-read enum in the store).
 */
final class OutagePause {

    private static final Logger log = LoggerFactory.getLogger(OutagePause.class);

    /**
     * First wait after a failure — short, because most outages are a restart of a few seconds.
     *
     * <p>Overridable with {@code -Dswarmcoder.outage.firstWaitMillis=N}. A genuine tunable: how
     * eagerly to poll a model server that has gone away depends on the server, and it is also what
     * lets the pause/resume tests run in a second instead of a minute. Read per call rather than
     * captured in a static, so setting it takes effect whenever it is set.
     */
    static final Duration FIRST_WAIT = Duration.ofSeconds(15);
    /**
     * Ceiling on the backoff. Two minutes is frequent enough that a returning endpoint is picked up
     * promptly, and rare enough that a long outage is not a poll loop against a dead socket.
     */
    static final Duration MAX_WAIT = Duration.ofMinutes(2);

    private static Duration firstWait() {
        long override = Long.getLong("swarmcoder.outage.firstWaitMillis", 0L);
        return override > 0 ? Duration.ofMillis(override) : FIRST_WAIT;
    }

    private static Duration maxWait() {
        Duration first = firstWait();
        return first.compareTo(MAX_WAIT) > 0 ? first : MAX_WAIT;
    }
    /**
     * When a pause stops being "it will be back" and becomes something a person should know about.
     * The number itself lives in {@link RunPause} — the engine, the readiness publisher and the
     * browser client all have to agree about it.
     */
    static final Duration ESCALATE_AFTER = Duration.ofMinutes(RunPause.ESCALATE_AFTER_MINUTES);

    private OutagePause() {}

    /**
     * Marks the run paused and returns it, ready to persist.
     *
     * <p>{@code pausedSince} is set only on the FIRST failure of a pause: escalation is measured
     * from when the endpoint went away, not from the latest of a hundred retries, or a long outage
     * would refresh its own clock and never escalate.
     */
    static Run pause(Run run, EndpointOutage outage) {
        if (run.pausedSince() == null) {
            run.setPausedSince(Instant.now());
        }
        run.setPauseEndpoint(outage.endpoint());
        run.setPauseReason(sentence(outage));
        return run;
    }

    /** Clears the pause after a stage finally succeeds. */
    static Run resumed(Run run) {
        run.setPausedSince(null);
        run.setPauseReason(null);
        run.setPauseEndpoint(null);
        return run;
    }

    /**
     * What the operator reads on the card. Plain, names the thing that is wrong, and says who is
     * going to fix it — the point of the whole mechanism is that the answer is "nobody, it resumes
     * by itself".
     */
    static String sentence(EndpointOutage outage) {
        String where = outage.endpoint() == null || outage.endpoint().isBlank()
            ? "your model server" : "your model server (" + outage.endpoint() + ")";
        return "paused — " + where + " is not answering; building resumes by itself when it returns.";
    }

    /**
     * How long to wait before attempt {@code attempt} (1-based), doubling from {@link #FIRST_WAIT}
     * up to {@link #MAX_WAIT}.
     */
    static Duration backoff(int attempt) {
        Duration ceiling = maxWait();
        Duration wait = firstWait();
        for (int i = 1; i < attempt && wait.compareTo(ceiling) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(ceiling) > 0 ? ceiling : wait;
    }

    /**
     * Sleeps out one retry interval.
     *
     * @return false when the thread was interrupted — the caller must stop rather than spin, which
     *         is how a shutdown gets out of an indefinite wait
     */
    static boolean waitBeforeRetry(Run run, int attempt) {
        Duration wait = backoff(attempt);
        log.info("Run {} paused at {} (attempt {}, {}s): {}", run.id(), run.state(), attempt,
            wait.toSeconds(), run.pauseReason());
        try {
            Thread.sleep(wait.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Run {} stopped waiting for its endpoint (interrupted)", run.id());
            return false;
        }
    }
}
