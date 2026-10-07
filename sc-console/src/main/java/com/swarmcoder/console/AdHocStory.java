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

import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The work item behind a freeform run — the on-ramp for a codebase nobody has written requirements
 * for yet.
 *
 * <p><b>What it is.</b> An ENABLER story with origin {@code AD_HOC}, no requirements and <b>no
 * criteria</b>. It delivers no requirement until somebody triages it and says which one it belongs
 * to. That is the whole point: on an existing project the requirement graph is not the on-ramp, and
 * demanding one before any work can start is what turns "get going in minutes" into "get going in
 * days". Requirements accrue from work here, they do not gate it.
 *
 * <p><b>Why it is a class and not four lines in the chat.</b> Two reasons. It was written inline in
 * {@code ChatOrchestrator}, unreachable from anything but a chat message, so the one path an
 * operator takes on their first day was the one path no test could drive. And it was written in the
 * WRONG ORDER — see {@link #start}.
 */
public final class AdHocStory {

    private static final Logger log = LoggerFactory.getLogger(AdHocStory.class);

    private AdHocStory() {}

    /** A started ad-hoc run and the backlog card standing for it. */
    public record Started(UUID runId, Story story) {}

    /**
     * Creates the work item and starts the run already bound to it.
     *
     * <p><b>The order is the fix.</b> This used to start the run first and attach the story
     * afterwards, which is precisely what {@code ConsoleContext.startRun(goal, kind, storyId)}
     * exists to prevent and says so at length: the engine advances the run on its own thread the
     * instant it is handed over, and rebuilds it from its own copy at every state transition, so a
     * storyId attached a moment later is written to an object nobody reads again. The run then
     * finished without ever moving its story out of "building", and the backlog card for every
     * freeform run stayed there for ever. It was not a race that sometimes lost; the engine is
     * always faster than the caller's next line.
     *
     * <p>The story is created first, in {@code RUNNING}, and if the run cannot be started it is
     * moved to {@code BLOCKED} rather than left claiming work that never began.
     *
     * @param projectId the project the work belongs to; null returns null (nothing to attach to)
     * @param kind      a startable {@link com.swarmcoder.domain.WorkflowKind} name
     * @return the run and its story, or null when no story could be minted — in which case
     *         <b>nothing was started</b> and the caller must say so
     */
    public static Started start(ConsoleContext context, UUID projectId, String kind, String goal) {
        if (projectId == null || goal == null || goal.isBlank()) {
            return null;
        }
        ArtifactStore store = context.store();
        Story story;
        try {
            story = new Story(
                UUID.randomUUID(), projectId, store.nextStoryKey(projectId),
                StoryKind.ENABLER, title(goal), null,
                StoryState.RUNNING, new ArrayList<>(), new ArrayList<>(), null, 0,
                StoryOrigin.AD_HOC, null, "started directly from chat", "human",
                new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
            store.saveStory(story);
            store.recordChange(projectId, "human", ChangeEntityType.STORY, story.id(),
                ChangeKind.CREATED, "ad-hoc run started as " + story.key());
        } catch (Exception e) {
            // The backlog card is bookkeeping; the run is the work. Losing the card must not lose
            // the run, so the caller falls back to an unbound run and is told.
            log.warn("Could not mint an ad-hoc story for a freeform run: {}", e.getMessage());
            return null;
        }

        UUID runId;
        try {
            runId = context.bindsStoriesAtStart()
                ? context.startRun(goal, kind, story.id())
                : context.startRun(goal, kind);
        } catch (RuntimeException e) {
            block(store, story, e.getMessage());
            BacklogPublisher.publish(store, projectId);
            throw e;
        }

        // originRunId and the run list are the story's record of WHICH run this was, and they are
        // the only fields that genuinely cannot be known before the run has an id.
        try {
            story.setOriginRunId(runId);
            story.setRunIds(new ArrayList<>(List.of(runId)));
            story.setUpdatedAt(Instant.now());
            store.saveStory(story);
        } catch (Exception e) {
            log.warn("Ad-hoc story {} could not record its run {}: {}", story.key(), runId,
                e.getMessage());
        }
        BacklogPublisher.publish(store, projectId);
        return new Started(runId, story);
    }

    private static void block(ArtifactStore store, Story story, String reason) {
        try {
            story.setState(StoryState.BLOCKED);
            story.setRationale("the run could not be started: " + reason);
            story.setUpdatedAt(Instant.now());
            store.saveStory(story);
        } catch (Exception e) {
            log.warn("Could not mark ad-hoc story {} blocked: {}", story.key(), e.getMessage());
        }
    }

    /** The goal, short enough to be a card title. */
    static String title(String goal) {
        String trimmed = goal.strip();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "…";
    }
}
