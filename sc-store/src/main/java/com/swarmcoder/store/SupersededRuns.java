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
package com.swarmcoder.store;

import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one definition of "this build of a story has been replaced", and the one place such a build
 * is ended.
 *
 * <p>A story is the lasting work item and a run is one attempt at it. When a story is sent back to
 * be built again, or started again, its earlier attempts are over: they must not be driven again
 * (at process start or by an answer), must not ask anything, and must not write the story's state.
 * Until this class existed nothing ended them. Sending a story back only cleared the "stopped"
 * mark on its run, the run stayed at the stage it had stopped in, and the next process start drove
 * it again beside the story's new run; when it failed again it wrote its own failure onto the
 * story and raised its question a second time.
 *
 * <p>No new stored state: an ended attempt is {@link RunState#ABORTED}, and "replaced" is read
 * from the story (see {@link #isSuperseded}), so a store written before this class reads the same
 * way and {@link #sweep} ends what such a store left behind.
 */
public final class SupersededRuns {

    private static final Logger log = LoggerFactory.getLogger(SupersededRuns.class);

    /** How the recorded reason on a question closed here begins. */
    public static final String CLOSED_PREFIX = "Closed without an answer: ";

    private SupersededRuns() {}

    /** True for a state no run leaves. */
    public static boolean isEnded(RunState state) {
        return state == RunState.DELIVERED || state == RunState.ABORTED
            || state == RunState.ABANDONED;
    }

    /**
     * Whether this run is an earlier build of a story that has since been sent back or started
     * again.
     *
     * <p>True when the run's story lists the run and either lists a later one, or is waiting to be
     * built (it was sent back and no new build has started yet). A run its story does not list yet
     * is a build in the moment of being started and is never superseded; a run with no story is
     * never superseded.
     */
    public static boolean isSuperseded(ArtifactStore store, Run run) {
        if (store == null || run == null || run.storyId() == null || run.id() == null) {
            return false;
        }
        return isSuperseded(store.getStory(run.storyId()), run.id());
    }

    /** {@link #isSuperseded(ArtifactStore, Run)} for a run named by its id. */
    public static boolean isSuperseded(ArtifactStore store, UUID runId) {
        return store != null && runId != null && isSuperseded(store, store.root().runs.get(runId));
    }

    private static boolean isSuperseded(Story story, UUID runId) {
        if (story == null || story.runIds() == null) {
            return false;
        }
        List<UUID> runs = story.runIds();
        int at = runs.lastIndexOf(runId);
        if (at < 0) {
            return false;
        }
        return at < runs.size() - 1 || story.state() == StoryState.READY;
    }

    /**
     * The story a superseded run belongs to, for a sentence that names it; null when the run is
     * not superseded.
     */
    public static Story supersededStoryOf(ArtifactStore store, UUID runId) {
        if (!isSuperseded(store, runId)) {
            return null;
        }
        return store.getStory(store.root().runs.get(runId).storyId());
    }

    /**
     * Ends every build of the story except {@code keep}: an unfinished one becomes
     * {@link RunState#ABORTED} with its "stopped" mark cleared, and every question any of them
     * still has open is closed with {@code reason} recorded on it.
     *
     * @param keep   the story's current build, or null when it has none (it was just sent back)
     * @param reason why, in a sentence a person reads on the closed question and in the journal
     * @return the runs that were ended by this call
     */
    public static List<UUID> supersede(ArtifactStore store, Story story, UUID keep, String reason) {
        if (store == null || story == null || story.runIds() == null) {
            return List.of();
        }
        Set<UUID> earlier = new HashSet<>(story.runIds());
        earlier.remove(keep);
        if (earlier.isEmpty()) {
            return List.of();
        }
        List<UUID> ended = new ArrayList<>();
        List<UUID> closed = new ArrayList<>();
        try {
            store.append(() -> {
                for (UUID runId : earlier) {
                    Run run = store.root().runs.get(runId);
                    if (run == null || isEnded(run.state())) {
                        continue;
                    }
                    // A new object, never the stored one changed in place: a thread still driving
                    // this run holds the old object, and RunPersister tells the two apart to
                    // refuse that thread's next write.
                    Run over = run.withState(RunState.ABORTED);
                    over.setParkedAt(null);
                    over.setParkReason(null);
                    store.root().runs.put(runId, over);
                    ended.add(runId);
                }
                for (Decision decision : new ArrayList<>(store.root().decisions.values())) {
                    if (decision != null && decision.state() == DecisionState.PENDING
                            && decision.runId() != null && earlier.contains(decision.runId())) {
                        store.root().decisions.put(decision.id(), new Decision(decision.id(),
                            decision.runId(), decision.kind(), decision.briefMarkdown(),
                            DecisionState.RESOLVED, CLOSED_PREFIX + reason, decision.createdAt()));
                        closed.add(decision.id());
                    }
                }
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted ending the earlier builds of "
                + story.key(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not end the earlier builds of " + story.key(), e);
        }
        // Whatever is still working on an ended build is told now, after the store says so
        // (section 110): a build used to be ended in the store only, and its swarm went on to
        // its next wave with places on the model server.
        for (UUID runId : ended) {
            tellWhoIsWorkingOn(runId);
        }
        if (!ended.isEmpty() || !closed.isEmpty()) {
            log.info("Story {}: ended {} earlier build(s) {} and closed {} open question(s) — {}",
                story.key(), ended.size(), ended, closed.size(), reason);
            try {
                store.recordChange(story.projectId(), "system", ChangeEntityType.STORY, story.id(),
                    ChangeKind.STATE_CHANGED, "runs", null, null,
                    "ended " + ended.size() + " earlier build(s) of " + story.key() + " and closed "
                        + closed.size() + " open question(s): " + reason, keep);
            } catch (Exception e) {
                // The journal is an audit aid; the runs are already ended.
                log.warn("Could not journal the ended builds of {}: {}", story.key(), e.toString());
            }
        }
        return List.copyOf(ended);
    }

    /** What is to be told when a run is ended here, by run (section 110). */
    private static final java.util.Map<UUID, List<Runnable>> WORKING_ON =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Has {@code whenEnded} called, on the thread that ends the run, when {@link #supersede} ends
     * {@code runId}. For whatever is working on the run in this process (its swarm), which must
     * stop at once rather than at the end of its stage. The action must not block.
     *
     * @return what takes the action off again; called when the work on the run is over
     */
    public static Runnable whenEnded(UUID runId, Runnable whenEnded) {
        if (runId == null || whenEnded == null) {
            return () -> { };
        }
        WORKING_ON.computeIfAbsent(runId, id -> new java.util.concurrent.CopyOnWriteArrayList<>())
            .add(whenEnded);
        return () -> WORKING_ON.computeIfPresent(runId, (id, actions) -> {
            actions.remove(whenEnded);
            return actions.isEmpty() ? null : actions;
        });
    }

    private static void tellWhoIsWorkingOn(UUID runId) {
        List<Runnable> actions = WORKING_ON.get(runId);
        if (actions == null) {
            return;
        }
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException e) {
                // What could not be told stops at its next look at the store.
                log.warn("Could not tell the work on run {} that it was ended: {}", runId,
                    e.toString());
            }
        }
    }

    /**
     * True when the store has the run ended undelivered (aborted or abandoned) - by
     * {@link #supersede}, or by a person. For work in progress on a run that asks whether to go
     * on: the run object a thread holds is older than the one the store has.
     */
    public static boolean endedInStore(ArtifactStore store, UUID runId) {
        if (store == null || runId == null) {
            return false;
        }
        Run stored = store.root().runs.get(runId);
        return stored != null && (stored.state() == RunState.ABORTED
            || stored.state() == RunState.ABANDONED);
    }

    /**
     * Ends, across the whole store, every build that {@link #isSuperseded} and every question such
     * a build still has open. Run once at process start, before anything is resumed, so a store
     * written before stories ended their earlier builds is put right.
     *
     * @return how many unfinished runs were ended
     */
    public static int sweep(ArtifactStore store) {
        if (store == null) {
            return 0;
        }
        int ended = 0;
        for (Story story : List.copyOf(store.root().stories().values())) {
            if (story == null || story.runIds() == null || story.runIds().isEmpty()) {
                continue;
            }
            List<UUID> runs = story.runIds();
            UUID last = runs.get(runs.size() - 1);
            UUID keep = story.state() == StoryState.READY ? null : last;
            try {
                ended += supersede(store, story, keep, keep == null
                    ? story.key() + " was sent back to be built again."
                    : story.key() + " has a newer build.").size();
            } catch (RuntimeException e) {
                log.warn("Could not end the earlier builds of {} at start: {}", story.key(),
                    e.toString());
            }
        }
        return ended;
    }
}
