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

import com.zeroz4j.api.RmiService;
import com.zeroz4j.api.Secured;

/**
 * The backlog-planning surface (docs/GUIDED_FLOWS_DESIGN.md §2) — the wizard behind the backlog's
 * "Plan stories" button, and the replacement for the now-removed {@code /backlog} chat mode.
 *
 * <p>Same shape as {@link GuidedFlowService}, minus everything document-related, because the input
 * here is not documents: it is the project's ACTIVE requirements, their accepted criteria, and the
 * backlog as it already stands. There is nothing for the operator to attach, so there is no DRAFT
 * step that collects anything — the inputs are simply what the project already holds, and the
 * wizard's first screen shows them rather than asking for them.
 *
 * <p>A flow <em>runs on the server and is persisted</em>; the dialog is a view of it. Planning over
 * a real BRD takes minutes, so a flow that lived in the browser would be one the operator learns not
 * to open — closing the window would throw the work away. Here the wizard can be closed, re-opened,
 * or watched from a second tab, and a crash leaves a resumable record.
 *
 * <p>Consequently every method returns "" or "error: …" and the state the wizard renders arrives on
 * {@link PlanningFlowSignals#CURRENT}. Callers bind to the signal; they do not read these return
 * values for anything but failure.
 *
 * <p>Ids cross the wire as strings, matching {@link GuidedFlowService} — TeaVM has no usable
 * {@code UUID} bit-level API, so the browser treats them as opaque handles.
 */
@RmiService
@Secured
public interface PlanningFlowService {

    /**
     * The current project's backlog-planning flow, creating an empty {@code DRAFT} one if none
     * exists. Also publishes it on {@link PlanningFlowSignals#CURRENT}, which is how the wizard gets
     * its first frame — opening the dialog is the only thing the client has to do.
     */
    FlowView planning();

    // --- running --------------------------------------------------------------------------------

    /**
     * Starts (or restarts) planning. Returns immediately — the flow moves to {@code RUNNING} and
     * progress arrives on the signal.
     *
     * <p>Refused when there is nothing to plan against: an empty BRD, requirements nobody has
     * promoted, or a backlog that already claims every criterion. Planning against no agreed scope
     * would produce stories over requirements the operator has not accepted, which is the whole
     * thing the DRAFT gate exists to prevent.
     */
    String start(String flowId);

    /** Abandons a running flow and returns it to {@code DRAFT}. */
    String cancel(String flowId);

    // --- questions (AWAITING_ANSWERS) -----------------------------------------------------------

    /**
     * Records an answer to one planning question — sequencing, scope boundaries, what the first
     * iteration must prove. Answering does not resume the flow: a round is a form, and
     * {@link #submitAnswers} is its submit button.
     */
    String answer(String flowId, String questionId, String answer);

    /**
     * Records free-text qualification alongside the answer — "yes, but not until billing lands".
     * Kept separate from {@link #answer} so a closed-ended choice stays machine-readable while the
     * operator can still say the thing the options do not cover.
     */
    String note(String flowId, String questionId, String note);

    /**
     * Marks a question as skipped. Skipping is first-class and never blocks: the planner must state
     * the assumption it made in the story's rationale instead, so an unanswered question becomes
     * visible and correctable rather than a silent guess.
     */
    String skip(String flowId, String questionId);

    /** Submits the current round and resumes planning with the answers given. */
    String submitAnswers(String flowId);

    // --- review (REVIEW) ------------------------------------------------------------------------

    /** Accepts or rejects one proposed story. Nothing has touched the backlog at this point. */
    String setAccepted(String flowId, String proposalId, boolean accepted);

    /** Accepts or rejects every proposal at once — the review list's select-all. */
    String setAllAccepted(String flowId, boolean accepted);

    /**
     * Writes the accepted proposals into the project's backlog as {@code DRAFT} stories and moves
     * the flow to {@code APPLIED}. Rejected proposals are simply not written; the flow record keeps
     * them, so what was declined stays auditable.
     *
     * <p>Every write goes through the backlog's own authoring helpers, which enforce that a delivery
     * story names at least one real criterion and carries no requirement content of its own.
     */
    String apply(String flowId);

    /**
     * Returns a finished (or failed) flow to {@code DRAFT} so planning can be run again — against
     * the backlog as it now stands, which is what stops a second run re-proposing what the first one
     * just wrote.
     */
    String reopen(String flowId);

    /**
     * Throws the whole planning run away — questions and proposals — leaving an empty {@code DRAFT}.
     * Nothing already in the backlog is affected.
     *
     * <p>Available from every state, including mid-run. A guided flow that can get into a state its
     * own UI offers no way out of is a trap, and the operator's instinct — start again — has to be
     * something the wizard can actually do.
     */
    String startOver(String flowId);
}
