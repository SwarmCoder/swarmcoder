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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Fewer workers on a model server when its workers keep running out of room, and each of the
 * ones that remain given the room the others gave up.
 *
 * <h2>Why the two numbers are one dial</h2>
 *
 * <p>{@link ServerCapabilities} works a worker's room out from what the server reports: the
 * key/value cache the running requests share, divided by how many run at once, less headroom. On
 * the 2026-08 box that is 462103 tokens shared 8 ways, 51200 each. The division is the whole
 * relationship — four workers would get 103424 each, two would get 207872. So when workers keep
 * dying because their conversations outgrow 51200, the lever is not a bigger number typed into a
 * file; it is the divisor. This class turns it.
 *
 * <h2>What counts as evidence</h2>
 *
 * <p>Only a {@link ContextDeath}: a request that timed out while the server answered others, or a
 * history that compaction could not get back under its limit. Anything else a worker dies of says
 * nothing about room, and a worker that never reached a model says nothing about anything — those
 * endings are ignored entirely rather than counted as clean (see
 * {@link ContextDeath#saysNothingAboutRoom}).
 *
 * <h2>The numbers, and where they come from</h2>
 *
 * <p><b>Back off when at least half of the last {@code c} workers dispatched at the current count
 * died of room, where {@code c} is that count.</b> This is the measured incident of 2026-09-01: twelve
 * workers, eight finished in 8 to 21 turns, four died of room at 62 to 99 turns. Because room
 * deaths are always the last to land — they are the workers that ran longest — the trailing eight
 * completions on that endpoint held all four deaths, which is exactly the threshold. Below it the
 * evidence is one or two unlucky workers, and the normal all-cause death rate is already one or
 * two of eight (the {@code SwarmSizing} note: six or seven of eight were surviving the build).
 *
 * <p><b>Each step halves the count and so doubles the room.</b> Compaction reclaims from the
 * high-water mark down to the low-water mark — 75% to 40% of the working context, a third of the
 * budget ({@code HistoryTrim}). Every worker counted here is one that compaction had already
 * tried and failed to save, so a step that adds less room than a compaction reclaims cannot change
 * the outcome. Halving is the smallest whole-number step that adds more, and it keeps the
 * derivation an exact division of the pool.
 *
 * <p><b>Recover one step after two clean waves at the current size.</b> One wave's worth of clean
 * completions is not evidence, for the reason above: room deaths land last, so a wave's survivors
 * can all be in and its deaths still running. Two waves' worth guarantees the window spans at least
 * one whole wave's end. A recovery is a probe of the larger size: if a back-off follows before the
 * larger size has itself run clean for the same requirement, the probe failed and the next one
 * waits twice as long (four waves, then eight); a probe that holds for its requirement resets the
 * wait to two. That is what keeps it from oscillating.
 *
 * <p><b>One is the floor.</b> One worker has the whole pool. If it still runs out of room the task
 * does not fit this server, and nothing here can make it fit: the death is logged as exactly that,
 * the worker stays killed with its usual reason, and the task takes the path it always took (the
 * repair round, then blocked).
 *
 * <h2>Rules it keeps</h2>
 *
 * <ul>
 *   <li><b>Changes take effect between waves.</b> A running worker's room cannot grow. The
 *       dispatcher asks for a {@link Plan} once per wave, and a wave already dispatched finishes at
 *       its size. A change decided mid-wave is logged then and applied at the next dispatch.
 *   <li><b>The operator's ceiling is a ceiling.</b> {@code swarm.maxConcurrentWorkers} caps where
 *       this starts and where it may recover to. It goes below a hand-set figure, never above it,
 *       and says so in the log when it does.
 *   <li><b>An operator-pinned working context is never raised.</b> Config wins over discovery
 *       everywhere else; it wins here too. Fewer workers still run at once — that alone helps a
 *       request that timed out — but their room stays what the file says.
 *   <li><b>Per endpoint.</b> State is keyed by server root, so a Spark and a cloud API are separate
 *       pools and one's deaths never throttle the other. Two profiles on one server share a state.
 *   <li><b>Nothing changes for a profile nobody registered.</b> An unregistered profile gets
 *       {@link Plan#UNMANAGED}: no cap, the endpoint's own quirks, no permits — every test and
 *       embedded use behaves exactly as before this class existed.
 * </ul>
 */
public final class AdaptiveConcurrency {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveConcurrency.class);

    /** Clean waves at the current size before one recovery step; doubles when a probe fails. */
    public static final int CLEAN_WAVES_TO_RECOVER = 2;

    /** The longest a failed probe can push the wait — eight waves is most of a night at eight a wave. */
    public static final int MAX_CLEAN_WAVES_TO_RECOVER = 8;

    private static volatile AdaptiveConcurrency shared = new AdaptiveConcurrency();

    /** The process-wide controller: the one the app registers its endpoints with and the console reads. */
    public static AdaptiveConcurrency shared() {
        return shared;
    }

    /** For the app's startup, and for tests that need the console to read a controller of theirs. */
    public static void install(AdaptiveConcurrency controller) {
        shared = controller == null ? new AdaptiveConcurrency() : controller;
    }

    /**
     * What one wave of workers on a profile should look like.
     *
     * @param profileId                 the model profile asked about
     * @param concurrency               workers that may run at once on this profile's server; 0 = unmanaged
     * @param workingContextTokens      the room each gets; 0 = the profile's own quirks, unchanged
     * @param startConcurrency          the count the endpoint started the process at
     * @param startWorkingContextTokens the room in force at startup
     * @param because                   why the numbers are what they are, in the operator's words
     */
    public record Plan(String profileId, int concurrency, int workingContextTokens,
                       int startConcurrency, int startWorkingContextTokens, String because) {

        /** Nobody registered this profile: leave everything exactly as it is. */
        public static final Plan UNMANAGED = new Plan("", 0, 0, 0, 0, "");

        public boolean managed() {
            return concurrency > 0;
        }

        /** True when this wave is running smaller than the process started. */
        public boolean throttled() {
            return managed() && concurrency < startConcurrency;
        }
    }

    private record Completion(String worker, ContextDeath death) { }

    /** One model server: its pool, its current size and the evidence behind it. */
    private static final class Endpoint {
        final String root;
        final int poolTokens;
        final int start;
        final String ceilingNote;
        int level;
        /** The last {@code start} endings that said something about room, oldest first. */
        final Deque<Completion> recent = new ArrayDeque<>();
        int cleanSinceChange;
        int cleanWavesNeeded = CLEAN_WAVES_TO_RECOVER;
        boolean lastChangeWasRecovery;
        String because = "";
        volatile Semaphore permits;

        Endpoint(String root, int poolTokens, int start, String ceilingNote) {
            this.root = root;
            this.poolTokens = poolTokens;
            this.start = start;
            this.level = start;
            this.ceilingNote = ceilingNote;
            this.permits = new Semaphore(start, true);
        }
    }

    /** One model profile on an endpoint, with the figures that are its own rather than the server's. */
    private record Profile(String id, Endpoint endpoint, ServerCapabilities caps,
                           int startWorking, int served, boolean pinned) { }

    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- registration

    /**
     * Puts one worker profile under control.
     *
     * @param profileId             the model profile id workers are dispatched under
     * @param baseUrl               its endpoint; profiles sharing a server root share a state
     * @param inForce               the quirks in force after config and discovery — the baseline
     * @param caps                  what the server reported; its pool is what room is recomputed from
     * @param workingContextPinned  whether the operator wrote {@code workingContextTokens} themselves
     * @param operatorWorkerCeiling {@code swarm.maxConcurrentWorkers} as in force (0 = none)
     * @param ceilingStated         whether the operator wrote that ceiling rather than inheriting it
     */
    public void register(String profileId, String baseUrl, ModelQuirks inForce,
                         ServerCapabilities caps, boolean workingContextPinned,
                         int operatorWorkerCeiling, boolean ceilingStated) {
        if (profileId == null || profileId.isBlank() || baseUrl == null || baseUrl.isBlank()
                || inForce == null) {
            return;
        }
        ServerCapabilities reported = caps == null ? ServerCapabilities.NOTHING : caps;
        String root = ServerCapabilities.root(baseUrl);
        Endpoint endpoint = endpoints.computeIfAbsent(root, key -> {
            int start = inForce.maxConcurrentSequences();
            String note = "";
            if (operatorWorkerCeiling > 0 && operatorWorkerCeiling < start) {
                start = operatorWorkerCeiling;
                note = ceilingStated
                    ? " The settings file caps workers at " + operatorWorkerCeiling
                        + ", so that is where this starts and the most it will ever go back to."
                    : " The built-in ceiling of " + operatorWorkerCeiling
                        + " workers is where this starts and the most it will ever go back to.";
            } else if (ceilingStated && operatorWorkerCeiling > 0) {
                note = " The settings file caps workers at " + operatorWorkerCeiling
                    + "; this stays under it.";
            }
            return new Endpoint(key, reported.kvCachePoolTokens(), Math.max(1, start), note);
        });
        profiles.put(profileId, new Profile(profileId, endpoint, reported,
            inForce.workingContextTokens(), inForce.servedContextTokens(), workingContextPinned));
        log.info("Worker model '{}' on {} will back off when its workers keep running out of room: "
            + "it starts at {} at once with {} tokens each{}{}", profileId, root, endpoint.start,
            inForce.workingContextTokens(),
            endpoint.poolTokens > 0
                ? ", and the server's " + endpoint.poolTokens + " shared tokens are what each "
                    + "worker's room is worked out from as the count changes."
                : ", but the server never said how much memory it shares, so fewer will run at "
                    + "once without each getting more room.",
            workingContextPinned
                ? " The settings file pins the room at " + inForce.workingContextTokens()
                    + " tokens, so the count will change and the room will not." : "");
    }

    // ---------------------------------------------------------------- the plan

    /** The wave this profile should be dispatched at right now. Never blocks. */
    public Plan plan(String profileId) {
        Profile profile = profileId == null ? null : profiles.get(profileId);
        if (profile == null) {
            return Plan.UNMANAGED;
        }
        Endpoint endpoint = profile.endpoint();
        synchronized (endpoint) {
            return new Plan(profileId, endpoint.level, workingFor(profile, endpoint.level),
                endpoint.start, profile.startWorking(), endpoint.because);
        }
    }

    /**
     * The room one worker of this profile gets at a given count.
     *
     * <p>At the starting count it is exactly the figure in force at startup — this class changes
     * nothing until it has evidence. Below it, the same derivation {@link ServerCapabilities} used
     * at startup, with the new divisor: never less than the starting figure, never more than the
     * longest request the server accepts, and never anything else when the operator pinned it or
     * the server never reported a pool.
     */
    private static int workingFor(Profile profile, int level) {
        if (level >= profile.endpoint().start || profile.pinned()
                || profile.endpoint().poolTokens <= 0) {
            return profile.startWorking();
        }
        int derived = profile.caps().derivedWorkingContextTokens(level);
        if (derived <= 0) {
            return profile.startWorking();
        }
        int capped = profile.served() > 0 ? Math.min(derived, profile.served()) : derived;
        return Math.max(profile.startWorking(), capped);
    }

    // ---------------------------------------------------------------- permits

    /**
     * Runs {@code work} as one of the workers this profile's server is allowed at once, waiting for
     * a place when it is full. Unmanaged profiles run immediately.
     *
     * <p>Like {@code WorkerSlots}: a change replaces the holder rather than resizing it, so workers
     * already running keep the places they took under the old count. A wave dispatched at eight
     * keeps its eight places while it finishes; the next wave is admitted against the new count.
     * Acquired only AFTER the machine-wide slot, always in that order, so two workers can never
     * each hold the place the other is waiting for.
     */
    public <T> T holding(String profileId, String what, Supplier<T> work) {
        Profile profile = profileId == null ? null : profiles.get(profileId);
        if (profile == null) {
            return work.get();
        }
        Semaphore permits = profile.endpoint().permits;
        if (!permits.tryAcquire()) {
            log.info("{} is waiting for a place on {} — it is running {} at a time right now.",
                what, profile.endpoint().root, profile.endpoint().level);
            permits.acquireUninterruptibly();
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    /** One of a server's worker places, held until closed. Closing it twice is harmless. */
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    private static final Held NOT_COUNTED = () -> { };

    private static Held heldOn(Semaphore permits) {
        java.util.concurrent.atomic.AtomicBoolean given =
            new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (given.compareAndSet(false, true)) {
                permits.release();
            }
        };
    }

    /**
     * {@link #holding} for a caller that cannot wrap its work in a lambda: waits for one of the
     * places this profile's server is allowed, in the order asked. Unmanaged profiles get a place
     * that is nothing, at once.
     */
    public Held hold(String profileId, String what) {
        Profile profile = profileId == null ? null : profiles.get(profileId);
        if (profile == null) {
            return NOT_COUNTED;
        }
        Semaphore permits = profile.endpoint().permits;
        if (!permits.tryAcquire()) {
            log.info("{} is waiting for a place on {} — it is running {} at a time right now.",
                what, profile.endpoint().root, profile.endpoint().level);
            permits.acquireUninterruptibly();
        }
        return heldOn(permits);
    }

    /**
     * A place only when one is free this instant and nobody is waiting for one; otherwise null,
     * at once. For a spare candidate, which may only ever use a place that would otherwise be
     * idle (2026-10-02). Unmanaged profiles get a place that is nothing.
     */
    public Held tryHold(String profileId) {
        Profile profile = profileId == null ? null : profiles.get(profileId);
        if (profile == null) {
            return NOT_COUNTED;
        }
        Semaphore permits = profile.endpoint().permits;
        try {
            // The timed form with no time: unlike the bare tryAcquire(), it keeps to the queue.
            return permits.tryAcquire(0, java.util.concurrent.TimeUnit.SECONDS)
                ? heldOn(permits) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ---------------------------------------------------------------- evidence

    /**
     * Records how one worker ended and moves the count when the evidence says to.
     *
     * @param profileId the profile it ran under
     * @param worker    its name for the log ("worker 3 of 'Add login'")
     * @param reason    why it stopped, or null when it finished
     * @param death     the {@link ContextDeath} when that is what it was, else null
     * @param ranAt     the count its wave was dispatched at ({@link Plan#concurrency()}); a worker
     *                  from a wave dispatched at a different count is evidence about THAT count,
     *                  which has already been acted on, and is ignored — otherwise the late deaths
     *                  of one eight-worker wave would cascade eight to four to two to one before a
     *                  single worker had run at four
     */
    public void completed(String profileId, String worker, KillReason reason, ContextDeath death,
                          int ranAt) {
        Profile profile = profileId == null ? null : profiles.get(profileId);
        if (profile == null || ContextDeath.saysNothingAboutRoom(reason)) {
            return;
        }
        Endpoint endpoint = profile.endpoint();
        synchronized (endpoint) {
            if (ranAt != endpoint.level) {
                log.info("{} ran on {} at {} at a time, which has since changed to {} — its ending "
                    + "is about the old count and does not move the new one.", worker,
                    endpoint.root, ranAt, endpoint.level);
                return;
            }
            endpoint.recent.addLast(new Completion(worker, death));
            while (endpoint.recent.size() > endpoint.start) {
                endpoint.recent.removeFirst();
            }
            if (death != null) {
                endpoint.cleanSinceChange = 0;
                onDeath(profile, endpoint, worker, death);
            } else {
                endpoint.cleanSinceChange++;
                onClean(profile, endpoint);
            }
        }
    }

    private void onDeath(Profile profile, Endpoint endpoint, String worker, ContextDeath death) {
        if (endpoint.level <= 1) {
            log.error("A task does not fit {}: {} ran out of room with the whole server to itself — "
                + "one worker at a time, {} tokens of room{}. {}. SwarmCoder cannot make more room "
                + "on this server: split the task into smaller pieces, or start the server with "
                + "more memory. The worker stays killed and the task takes the usual path.",
                endpoint.root, worker, workingFor(profile, 1),
                endpoint.poolTokens > 0 ? " out of the " + endpoint.poolTokens + " it shares" : "",
                death.sentence());
            return;
        }
        List<Completion> window = lastN(endpoint.recent, endpoint.level);
        List<Completion> deaths = window.stream().filter(c -> c.death() != null).toList();
        int needed = (endpoint.level + 1) / 2;
        if (deaths.size() < needed) {
            log.info("{} on {} ran out of room ({}); that is {} of the last {} — half of {} is "
                + "what it takes to run fewer at once.", worker, endpoint.root, death.sentence(),
                deaths.size(), window.size(), endpoint.level);
            return;
        }
        int from = endpoint.level;
        int to = Math.max(1, from / 2);
        int roomFrom = workingFor(profile, from);
        int roomTo = workingFor(profile, to);
        if (endpoint.lastChangeWasRecovery) {
            endpoint.cleanWavesNeeded = Math.min(MAX_CLEAN_WAVES_TO_RECOVER,
                endpoint.cleanWavesNeeded * 2);
        }
        endpoint.lastChangeWasRecovery = false;
        StringBuilder who = new StringBuilder();
        for (Completion c : deaths) {
            who.append(who.isEmpty() ? "" : "; ").append(c.worker()).append(" — ")
               .append(c.death().sentence());
        }
        endpoint.because = deaths.size() + " of the last " + window.size()
            + " workers on it ran out of room";
        apply(endpoint, to);
        log.warn("Backing off on {}: {} -> {} workers at a time, {} -> {} tokens of room each{}"
            + "{}. Triggered by {} of the last {} workers on it running out of room: {}. Takes "
            + "effect at the next wave; the wave already running finishes at its size.{}",
            endpoint.root, from, to, roomFrom, roomTo,
            roomTo == roomFrom && endpoint.poolTokens <= 0
                ? " (the server never said how much memory it shares, so the room cannot be "
                    + "recomputed — only fewer will run at once)" : "",
            roomTo == roomFrom && profile.pinned()
                ? " (the settings file pins the room, so only the count changes)" : "",
            deaths.size(), window.size(), who, endpoint.ceilingNote);
    }

    private void onClean(Profile profile, Endpoint endpoint) {
        int needed = endpoint.cleanWavesNeeded * endpoint.level;
        if (endpoint.cleanSinceChange < needed) {
            return;
        }
        if (endpoint.lastChangeWasRecovery) {
            // The last step up has now held for its whole clean requirement: it was not a failed
            // probe, so a later back-off is a fresh one and the wait goes back to normal.
            endpoint.cleanWavesNeeded = CLEAN_WAVES_TO_RECOVER;
            endpoint.lastChangeWasRecovery = false;
        }
        if (endpoint.level >= endpoint.start) {
            return;
        }
        int from = endpoint.level;
        int to = Math.min(endpoint.start, from * 2);
        int roomFrom = workingFor(profile, from);
        int roomTo = workingFor(profile, to);
        endpoint.lastChangeWasRecovery = true;
        endpoint.because = to >= endpoint.start ? ""
            : "it is recovering after " + endpoint.cleanSinceChange
                + " workers in a row finished without running out of room";
        int clean = endpoint.cleanSinceChange;
        apply(endpoint, to);
        log.info("Recovering on {}: {} -> {} workers at a time, {} -> {} tokens of room each, after "
            + "{} workers in a row finished without running out of room ({} clean waves at {}). "
            + "Takes effect at the next wave.{}{}", endpoint.root, from, to, roomFrom, roomTo,
            clean, endpoint.cleanWavesNeeded, from,
            to < endpoint.start
                ? " The next step up needs " + endpoint.cleanWavesNeeded + " clean waves at " + to + "."
                : " That is back where it started.",
            endpoint.ceilingNote);
    }

    private static void apply(Endpoint endpoint, int level) {
        endpoint.level = level;
        endpoint.recent.clear();
        endpoint.cleanSinceChange = 0;
        endpoint.permits = new Semaphore(level, true);
    }

    private static List<Completion> lastN(Deque<Completion> all, int n) {
        List<Completion> out = new ArrayList<>(all);
        return out.size() <= n ? out : out.subList(out.size() - n, out.size());
    }

    // ---------------------------------------------------------------- for the operator

    /**
     * One sentence per endpoint that is currently running smaller than it started, for the
     * surfaces the operator already reads. Empty when nothing is backed off, so a caller can use
     * it as the whole test.
     */
    public List<String> throttled() {
        List<String> out = new ArrayList<>();
        for (Profile profile : profiles.values()) {
            Endpoint endpoint = profile.endpoint();
            synchronized (endpoint) {
                if (endpoint.level >= endpoint.start) {
                    continue;
                }
                String sentence = "The model server at " + endpoint.root + " is running "
                    + endpoint.level + (endpoint.level == 1 ? " worker" : " workers")
                    + " at a time instead of " + endpoint.start + ", each with "
                    + workingFor(profile, endpoint.level) + " tokens of room instead of "
                    + profile.startWorking() + ", because " + endpoint.because + ".";
                if (!out.contains(sentence)) {
                    out.add(sentence);
                }
            }
        }
        return out;
    }
}
