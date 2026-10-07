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
package com.swarmcoder.console.api;

import com.swarmcoder.domain.ChangeEvent;
import com.zeroz4j.api.RmiService;
import com.zeroz4j.api.Secured;

import java.util.List;

/**
 * The project backlog: iterations, stories, and the operator actions over them.
 *
 * <p>Reads carry the domain {@link com.swarmcoder.domain.Story} and
 * {@link com.swarmcoder.domain.Iteration} directly. Mutations return "" on success or an
 * "error: …" string, and the fresh backlog is published on {@link BacklogSignals#CURRENT} — the
 * panel binds to that signal, not to these return values.
 *
 * <p>Everything an agent may not do lives here instead, because these are the operator's decisions:
 * promoting a DRAFT story into the plan, accepting delivered work, and starting a run.
 */
@RmiService
@Secured
public interface BacklogService {

    /** The current project's backlog (also republished onto the signal). */
    Backlog backlog();

    /** Promotes a DRAFT story to READY — the operator accepting it into the plan. */
    String promoteStory(String storyId);

    /** Cancels a story. A tombstone, never a deletion: the record and its history remain. */
    String cancelStory(String storyId);

    /**
     * The definition-of-done gate: the operator accepting a story that reached REVIEW.
     *
     * <p>This is deliberately a human act. "Tests pass" and "the requirement is satisfied" are not
     * the same claim, and a BRD that completes itself starts lying with nobody noticing. Accepting
     * marks the story DONE, stamps its criteria PASSING with the commit that delivered them, and
     * flips each requirement to IMPLEMENTED only once ALL of its accepted criteria pass.
     */
    String acceptStory(String storyId);

    /**
     * Sends a story back to READY so it can be built again — the way out of BLOCKED and the way to
     * reject a delivery that came back wrong.
     *
     * <p>Without this both states were terminal. A run that failed its quality gates left the story
     * BLOCKED, and nothing could move it: {@code promoteStory} refuses anything but DRAFT and
     * {@code startSession} refuses anything but READY. A model server going down mid-run therefore
     * did not pause the work, it converted it permanently into blocked stories — and an outage on a
     * local endpoint is an ordinary event that has to be survivable by retrying.
     *
     * <p>A delivery that came back wrong was the same trap from the other side: the only exits from
     * REVIEW were accepting work that failed, or retiring the story.
     *
     * <p>{@code note} records WHY, so the next run's brief can say what was wrong with the last one
     * and the history shows a judgement rather than a bare state change. Returns "" or "error: …".
     */
    String retryStory(String storyId, String note);

    /** Places a story in an iteration at a position; a blank iterationId unschedules it. */
    String scheduleStory(String storyId, String iterationId, int order);

    /** Creates an iteration — a named ordered batch, no dates and no points. */
    String addIteration(String name, String goal);

    /**
     * Starts a run bound to a READY story. The run is an ATTEMPT; the story is the durable work
     * item, so a story that has already failed once can be started again and keeps its identity.
     * Returns the run id, or "error: …".
     */
    String startSession(String storyId);

    /**
     * Starts a story that is still waiting for another one — the operator overruling the schedule.
     *
     * <p>A story does not start while a story it builds on is unfinished, because that is exactly
     * how nine stories once each invented their own version of a domain model the first of them had
     * not finished writing. But the schedule is a guess made before any code existed, so the operator
     * has to be able to say "I know, do it anyway"; a rule that cannot be overruled is a rule that
     * eventually has to be worked around by editing data.
     *
     * <p>Separate from {@link #startSession} on purpose. The plain start refuses and says what it is
     * waiting for; overruling is a different button, on a dialog that names the story in the way and
     * what it was going to provide. Nothing overrules it automatically, and unattended mode never
     * calls this.
     */
    String startSessionAnyway(String storyId);

    /**
     * Records that one story builds on another, or clears the links.
     *
     * <p>{@code dependsOnKeys} is a comma-separated list of story keys ("S1,S4"); empty clears them.
     * Refused when it would make a circle, or name a story this project does not have — a schedule
     * that cannot be run is worse than none, because it looks like a plan.
     */
    String setStoryDependencies(String storyId, String dependsOnKeys);

    /**
     * How many attempts each piece of this story's work gets, or 0 to inherit.
     *
     * <p>The most specific of three layers: this story, then the project's own settings, then the
     * settings file. Zero clears the story's number and puts it back on the project's, which is
     * the normal state for almost every story — a story that is one obvious line of work does not
     * need four attempts, and a hard one may want eight, and this is the operator saying which.
     *
     * <p>Takes effect on the story's NEXT build. A build already running has its worker count
     * stamped on its tasks and is not re-sized underneath itself.
     */
    String setStoryWorkers(String storyId, int workersPerTask);

    /**
     * The audit trail of one requirement, criterion, story, iteration or task, oldest first — how
     * it got to its current state and who moved it.
     */
    List<ChangeEvent> history(String entityId);
}
