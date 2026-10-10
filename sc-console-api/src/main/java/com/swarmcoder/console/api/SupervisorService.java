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

import com.swarmcoder.domain.AutonomousDecision;

import java.util.List;

/**
 * What an outside supervising model does to run a whole project build the way a person does in the
 * Console: it is told the one thing that needs it, it answers, and the work goes on.
 *
 * <p>The owner decided (2026-10-10) that this supervisor may pass the gates a person passes:
 * agreeing requirements and accepting a delivery. Every such act goes through the same service a
 * person's click goes through, and every one is written to the project's decision log with the
 * actor "supervisor", so it can be read afterwards and disagreed with.
 *
 * <p>Not a browser service: nothing here is called from the Console's pages. It is reached through
 * SwarmCoder's own MCP server. Every method is a lookup or a call into an existing service; none
 * calls a model.
 *
 * <p>Mutations return "" or a plain sentence on success and "error: ..." on refusal. {@code flow}
 * is {@code "analyst"} (reading documents into requirements) or {@code "planner"} (turning agreed
 * requirements into stories). A story is named by its key ("S3") or its id.
 */
public interface SupervisorService {

    /** The actor written on everything done through this service. */
    String ACTOR = AutonomousDecision.SUPERVISOR;

    // --- what needs the supervisor ---------------------------------------------------------------

    /**
     * The one item that most needs the supervisor now, or null when nothing does.
     *
     * @param skip how many items to pass over, most urgent first; 0 for the most urgent. It is how
     *             a supervisor that has decided to leave one thing alone sees what is behind it
     */
    AttentionItem nextAttention(int skip);

    /**
     * Blocks until something needs the supervisor, or the time is up, then returns what
     * {@link #nextAttention} would. Woken by the store being written, never by polling it.
     *
     * @param skip as for {@link #nextAttention}
     * @return the item, or null when the time ran out with nothing needing attention
     */
    AttentionItem waitForAttention(int timeoutSeconds, int skip);

    /** One sentence saying what is going on when nothing needs the supervisor. */
    String standing();

    // --- documents, analyst and planner ----------------------------------------------------------

    /** Stores pasted text as a document of the current project and adds it to the analyst's flow. */
    String addDocument(String title, String text, boolean technical);

    /** The analyst's or planner's flow as it stands: state, open questions, proposals. */
    FlowView flow(String flow);

    /** Starts the analyst or the planner; a finished or failed flow is reopened first. */
    String startFlow(String flow);

    /**
     * Answers one question of a flow. {@code answer} of {@code "skip"} with no note skips it. The
     * round is not submitted; see {@link #submitAnswers}.
     */
    String answerQuestion(String flow, String questionId, String answer, String note);

    /** Submits the current round of answers; the flow goes on with them. */
    String submitAnswers(String flow);

    /**
     * Accepts every proposal except those named in {@code rejectIdsCsv} and writes the accepted
     * ones: requirements for the analyst (as drafts), stories for the planner (as suggestions).
     */
    String applyProposals(String flow, String rejectIdsCsv);

    // --- gates -----------------------------------------------------------------------------------

    /** Agrees one draft requirement, by handle ("R3") or id, or every draft when blank or "all". */
    String agreeRequirements(String requirement);

    /** The current project's backlog. */
    Backlog backlog();

    /** Marks a suggested story ready to build; "all" marks every suggested story. */
    String promoteStory(String story);

    /** Starts a ready story's build. Returns the run id, or "error: ...". */
    String startStory(String story);

    /** Accepts a delivery that came back for a verdict; its code joins the delivery branch. */
    String acceptDelivery(String story);

    /** Sends a delivery, or a stopped story, back to be built again. The note says why. */
    String sendBack(String story, String note);

    // --- a build that stopped to ask -------------------------------------------------------------

    /** The answers a stopped build's question accepts, each as "token: what it does". */
    List<String> decisionOptions(String decisionId);

    /** The whole text of one question a build stopped to ask, or "" when there is none. */
    String decisionText(String decisionId);

    /**
     * Records the answer and hands the stopped build back to its engine. Returns a sentence saying
     * whether the build was restarted, or "error: ...".
     */
    String answerDecision(String decisionId, String answer, String text);

    // --- the record ------------------------------------------------------------------------------

    /** Everything the supervisor has decided for the current project, oldest first. */
    List<AutonomousDecision> decisionLog();
}
