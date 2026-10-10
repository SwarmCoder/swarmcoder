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
package com.swarmcoder.console;

import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryGraph;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The thing that lets the operator plan a set of stories, press go, and go to bed.
 *
 * <p>It does two jobs, on a slow timer, and nothing else. It accepts a story the machine has already
 * proved finished — see {@link UnattendedAcceptance} for the narrow definition of that — and it
 * starts the next story whose predecessors are all delivered. Everything else is left exactly where
 * it is, for the morning.
 *
 * <p><b>Off unless the operator turns it on.</b> The rule this works alongside says the definition of
 * done is human, and the reason for that rule has not stopped being true. So this is a setting the
 * operator switches on for a session, never a default, and the record shows plainly which stories a
 * person accepted and which the machine did.
 *
 * <p><b>What happens when something fails at two in the morning.</b> It is skipped, and the queue
 * carries on with everything that does not depend on it. A stopped story is left stopped, with its
 * reason written on it; nothing is retried on its behalf, because a retry nobody read the failure for
 * is how a night is spent producing the same error eight times. Stories that were waiting for the
 * stopped one stay waiting and are shown in the morning as waiting for it. The queue only goes quiet
 * when there is genuinely nothing left that can be started — which is also the moment it stops
 * costing anything.
 *
 * <p><b>One story at a time by default.</b> Two stories building simultaneously are cut from the same
 * branch and neither can see the other, so their work is only combined when the second one is merged,
 * verified and possibly rejected. Overnight there is nothing to be gained by rushing, and a queue
 * that is easy to reason about in the morning is worth far more than one that finished sooner.
 *
 * <h2>The front half, added 2026-09-02</h2>
 *
 * <p>Those two jobs are the BACK half of the pipeline, and the note above used to end "everything
 * else is left exactly where it is, for the morning". The operator asked for the rest: load a
 * document, press one button, and have the requirements read, agreed, planned and started with
 * nobody present. That work is {@link AutonomousBuild}, switched on by {@link AutonomousMode}, and
 * this timer drives it one step per tick for the same reasons the back half is slow and single-file.
 *
 * <p><b>It is a second, stronger switch and not the same one.</b> Unattended running still means
 * only what it meant: finish and start work whose evidence is already complete. Autonomous running
 * additionally lets the machine decide things nobody has decided - above all it answers the
 * analyst's clarifications, and a clarification exists because the document did not say, so an
 * answer to one is an invented requirement. Every such decision is written down as it is taken and
 * the invented ones are marked as invented; see {@link com.swarmcoder.domain.AutonomousDecision}.
 * The rule that the definition of done is human is being overridden here, on the operator's own
 * instruction, and the record is the price of the override.
 *
 * <p><b>The one exception to "nothing is retried" (2026-09-03).</b> That rule, in the paragraph
 * above, is still exactly true for a story the swarm genuinely could not finish. It is not the
 * whole story for a run that PARKED mid-flight — stopped a stage short, to ask something, rather
 * than running to a real failure. While autonomous mode is on, {@link AutonomousBuild#step} gives a
 * parked story one automatic try again before falling back to leaving it stopped; see
 * {@code AutonomousBuild.retryParkedStories}.
 *
 * <h2>Supervised running, added 2026-10-10</h2>
 *
 * <p>A third way to run, chosen by {@code overnight.supervised} in the settings: an outside
 * supervising model, connected to SwarmCoder's MCP server, runs the build as a person would. The
 * pilot then only starts the next story whose predecessors are delivered. It accepts nothing and
 * answers nothing: every delivery and every question waits for the supervisor, and each of its
 * answers is written to the decision log with the actor "supervisor". Autonomous running cannot be
 * switched on while this is set, because the two disagree about who answers a question.
 */
public final class UnattendedPilot {

    private static final Logger log = LoggerFactory.getLogger(UnattendedPilot.class);

    /** How often the pilot looks. Slow on purpose: a build takes minutes to hours. */
    private static final long TICK_SECONDS = 30;

    private final ArtifactStore store;
    private final int maxConcurrent;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public UnattendedPilot(ArtifactStore store, int maxConcurrentStories) {
        this.store = store;
        this.maxConcurrent = Math.max(1, maxConcurrentStories);
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "unattended-pilot");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts looking. Harmless when unattended mode is off — every tick then does nothing. */
    public void start() {
        timer.scheduleWithFixedDelay(this::tickQuietly, TICK_SECONDS, TICK_SECONDS,
            TimeUnit.SECONDS);
        log.info("Unattended pilot is watching the backlog (checks every {}s, up to {} story(s) "
            + "building at once). It only acts while unattended mode is switched on.",
            TICK_SECONDS, maxConcurrent);
    }

    public void stop() {
        timer.shutdownNow();
    }

    private void tickQuietly() {
        if (!ticking.compareAndSet(false, true)) {
            return;     // a previous tick is still going; never two at once
        }
        try {
            tick();
        } catch (Exception e) {
            // A pilot that dies on one bad tick leaves the operator's night silently over.
            log.warn("Unattended pilot tick failed: {}", e.toString(), e);
        } finally {
            ticking.set(false);
        }
    }

    /**
     * One pass: carry the autonomous front half forward by one step when it is switched on, then
     * accept what is finished and start what is ready. Package-private so tests drive it.
     *
     * <p>The stop conditions are checked FIRST and before anything is spent. A session that has run
     * past its wall-clock ceiling or its token ceiling stops here, cleanly, with the reason on the
     * record - it does not stop by failing somewhere further in.
     */
    void tick() {
        ConsoleContext context = ConsoleContext.get();
        if (context == null) {
            return;
        }
        UUID projectId = context.currentProjectId();
        if (projectId == null) {
            return;
        }
        if (context.supervisedMode()) {
            // Supervised (2026-10-10): the queue still runs one story after another, but the two
            // things this pilot otherwise does for a person are left to the supervising model
            // connected over MCP. Nothing is accepted here, and the autonomous front half, which
            // answers the analyst's questions itself, is not stepped. A delivery waits as
            // "back for your verdict" until the supervisor accepts it or sends it back.
            startWhatIsReady(context, projectId);
            return;
        }
        AutonomousMode.Session session = AutonomousMode.runningFor(projectId);
        if (session != null) {
            String stop = AutonomousMode.stopReason(context, session);
            if (stop != null) {
                AutonomousMode.halt(stop);
                session = null;
            } else {
                AutonomousBuild.step(context, session);
                // The step may have stopped the session itself - a failed reading, nothing left to
                // do. Re-read rather than assume, so a stopped session does not get one more free
                // acceptance out of this same tick.
                session = AutonomousMode.runningFor(projectId);
            }
            // Say what just happened, wherever the operator happens to be standing. This is the
            // only moment the gate can have moved, so it is the only moment worth publishing at:
            // between ticks nothing changes but the clock, which the published sentence carries.
            // A tick that changed nothing republishes nothing - the signal dedups by value.
            AutonomousMode.publish();
        }
        // Autonomous running implies unattended running: a night that plans the work and then
        // waits for a person to press Start would be exactly the night the operator asked not to
        // have.
        if (session == null && !context.unattendedMode()) {
            return;
        }
        acceptWhatIsFinished(context, projectId);
        startWhatIsReady(context, projectId);
    }

    /**
     * Accepts every story the machine has already proved finished, and leaves the rest alone with the
     * reason written on them.
     */
    private void acceptWhatIsFinished(ConsoleContext context, UUID projectId) {
        for (Story story : store.listStories(projectId)) {
            if (story.state() != StoryState.REVIEW) {
                continue;
            }
            UnattendedAcceptance.Verdict verdict = UnattendedAcceptance.judge(store, story);
            if (!verdict.acceptable()) {
                // Written once, so the morning card explains itself. Rewriting it every thirty
                // seconds would republish the backlog for ever and change nothing.
                if (!java.util.Objects.equals(story.waitingReason(), waitingText(verdict.reason()))) {
                    story.setWaitingReason(waitingText(verdict.reason()));
                    store.saveStory(story);
                    BacklogPublisher.publish(store, projectId);
                }
                continue;
            }
            String failed = BacklogServiceImpl.accept(store, projectId, story, "unattended");
            if (failed != null && failed.startsWith("error:")) {
                String reason = failed.substring("error:".length()).strip();
                log.warn("Unattended: could not accept {}: {}", story.key(), reason);
                story.setWaitingReason(reason);
                store.saveStory(story);
            } else {
                log.info("Unattended: accepted {} — every check it promised was proved", story.key());
            }
            BacklogPublisher.publish(store, projectId);
        }
    }

    private static String waitingText(String reason) {
        return reason == null ? null
            : "Left for you rather than accepted overnight, because " + reason + ".";
    }

    /**
     * Starts the next story whose predecessors have all been delivered, up to the concurrency limit.
     *
     * <p>The order is the plan's own: earliest iteration first, then the operator's rank inside it.
     * The dependency graph decides what MAY start; this only decides which of the startable ones goes
     * first, and the operator's ordering is the best answer available.
     */
    private void startWhatIsReady(ConsoleContext context, UUID projectId) {
        List<Story> all = store.listStories(projectId);
        long building = all.stream().filter(s -> s.state() == StoryState.RUNNING).count();
        if (building >= maxConcurrent) {
            return;
        }
        List<Story> ready = new ArrayList<>();
        for (Story story : all) {
            if (story.state() == StoryState.READY && StoryGraph.isStartable(story, all)) {
                ready.add(story);
            }
        }
        ready.sort((a, b) -> {
            int byOrder = Integer.compare(a.order(), b.order());
            return byOrder != 0 ? byOrder : a.key().compareTo(b.key());
        });
        BacklogServiceImpl service = new BacklogServiceImpl();
        for (Story story : ready) {
            if (building >= maxConcurrent) {
                return;
            }
            String result = service.startSession(story.id().toString());
            if (result != null && result.startsWith("error:")) {
                // Not fatal to the night. Something about this one story is wrong; the others are
                // unaffected and the reason is on the record for the morning.
                log.warn("Unattended: could not start {}: {}", story.key(), result);
                continue;
            }
            log.info("Unattended: started {} — everything it builds on has been delivered",
                story.key());
            building++;
        }
    }
}
