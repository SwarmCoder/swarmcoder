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

import java.util.List;

/** Operate side of the Console — full TUI parity (docs/OBSERVABILITY_DESIGN.md §5.5). */
@RmiService
@Secured
public interface ControlService {

    /**
     * Starts a run; returns its id. kind: GREENFIELD | ENHANCEMENT | BUGFIX | REFACTOR.
     *
     * <p>DOCS and ANALYSIS are refused. They used to be accepted and produced a run that walked
     * through its state names and reported DELIVERED without designing, planning, testing or
     * building anything.
     */
    String submitIntake(String goal, String kind);

    void approveRun(String runId);

    void rejectRun(String runId);

    /**
     * The integrated diff of a run for approval review — the winning candidates' unified diffs
     * concatenated in task order. "" when the run has no selected candidates yet.
     */
    String integratedDiff(String runId);

    List<com.swarmcoder.domain.Decision> decisions();

    void resolveDecision(String decisionId, String response);

    /**
     * Records the SAME response against every decision that is still unanswered, and returns how
     * many were written.
     *
     * <p>It exists because the queue is produced in bursts. A model server going down mints one
     * BLOCKED_TASK per abandoned task, so twenty-one identical decisions arrive from one incident —
     * and clearing them one at a time meant twenty-one rounds of typing and clicking, which nobody
     * does. The queue then never empties and the Build attention badge is permanently wrong about
     * what is owed.
     *
     * <p><b>It resolves; it does not restart.</b> Same contract as {@link #resolveDecision}: the row
     * is rewritten and nothing else is touched. Nothing in the engine waits on a decision — see
     * {@code PendingDecisions} for the trace of every producer — so this clears a queue, it does not
     * resume a run or retry a task.
     *
     * <p><b>APPROVAL decisions are deliberately left alone.</b> An approval is answered by approving
     * or rejecting the RUN, not by writing prose at it; sweeping one to RESOLVED here would take the
     * gate off the queue while the run stayed parked at APPROVAL for ever, with nothing left on any
     * screen to say so. The count returned is therefore the number actually resolved, which can be
     * lower than the number the caller saw pending.
     */
    int resolveAllPendingDecisions(String response);

    /** The active config as YAML (design §5.5 Settings — same file the TUI edits). */
    String settingsYaml();

    /** Persists edited config YAML; returns "" on success or an error message. */
    String saveSettingsYaml(String yaml);

    /**
     * The CURRENT project's rules (ACTIVE / PROPOSED / RETIRED) — the guidelines browser (design
     * §5.5). Rules are store objects that belong to a project and are deleted with it; a rule of
     * another project is never listed here.
     */
    List<GuidelineDto> guidelines();

    /**
     * Turns one rule on or off.
     *
     * <p>Every rule SwarmCoder learns is written PROPOSED, and a PROPOSED rule never enters a
     * prompt. Until this existed there was nothing anywhere in the product that could change that,
     * so a project's whole body of learned lessons sat inert with no way to act on it.
     *
     * <p>{@code status} is ACTIVE (the rule goes into every worker's prompt) or RETIRED (it stops
     * being sent and is kept as history). The store is what is written; there is no file.
     *
     * @return "" on success, else "error: …" — shown to the operator as it is
     */
    String setGuidelineStatus(String guidelineId, String status);

    /**
     * Gives one rule the command that proves it, or takes it away.
     *
     * <p>A rule with a check is no longer advice: verification runs the command in every
     * candidate's workspace, and a candidate that fails it does not survive. {@code command} is one
     * line of shell, exit 0 meaning obeyed; blank removes the check. Only a rule a person decided —
     * stated from a document, or written by hand — may carry one.
     *
     * @param timeoutSeconds 0 for the default
     * @return "" on success, else "error: …" — shown to the operator as it is
     */
    String setGuidelineCheck(String guidelineId, String command, int timeoutSeconds);

    // --- Multi-project (the Console project switcher) ------------------------------------------

    /** All projects; the one flagged {@code current} is where new intake is routed. */
    List<ProjectDto> projects();

    /**
     * Creates (or re-uses, by primary path) a project. {@code contextPathsCsv} is a
     * comma-separated list of read-only context folders. Returns the project id, or an
     * {@code "error: ..."} string on failure.
     */
    String createProject(String name, String primaryPath, String contextPathsCsv);

    /** Routes subsequent intake to the given project. */
    void switchProject(String projectId);

    // --- The on-ramp: how do we build this folder? ----------------------------------------------

    /**
     * Works out how to build and test a folder of code, and proposes the verification contract.
     *
     * <p>This is the one thing that stands between "here is a repository" and a run whose winner
     * was chosen on evidence: with no contract, verification is skipped and every candidate comes
     * back unverified, so the swarm picks between attempts nothing tested.
     *
     * <p>Everything it returns is a PROPOSAL for the operator to correct. When {@code probe} is
     * true the compile command is really executed once first — which can take minutes on a large
     * project, and is the difference between a contract that builds the project and one that was
     * guessed. Returns null when the folder does not exist or nothing was recognised in it.
     */
    BuildContractDto detectBuildContract(String primaryPath, boolean probe);

    /**
     * Writes {@code .swarmcoder/verify.yaml} into the project's own checkout. Returns "" on
     * success or an {@code "error: ..."} message. Refuses to replace an existing contract unless
     * {@code overwrite} is set: that file is the operator's decision, possibly hand-corrected, and
     * silently replacing it is damage nobody notices until a run later.
     */
    String saveBuildContract(String primaryPath, String yaml, boolean overwrite);

    /**
     * A plain-language, counted statement of what deleting the project would destroy — shown in the
     * confirm step so the operator sees the real cost before typing the name. Built server-side
     * because the counts come from roots the browser cannot see. Returns {@code "error: ..."} for
     * an unknown project.
     */
    String projectDeletionPreview(String projectId);

    /**
     * Permanently removes SwarmCoder's records of a project — requirements, backlog, chats, runs,
     * uploaded documents and all history. <b>Nothing on disk is touched:</b> the project's working
     * tree is not read, moved or deleted, only the store rows keyed to the project.
     *
     * <p>{@code confirmationName} must equal the project's name. The server re-checks it and
     * refuses otherwise: a client-side-only guard is not a guard, it is a suggestion. Returns
     * {@code ""} on success or {@code "error: ..."}.
     */
    String deleteProject(String projectId, String confirmationName);

    // --- Typed settings forms (design §7) --------------------------------------------------------

    /** Global per-role endpoints: named roles + worker families as {@code worker0..n}. */
    List<RoleEntryDto> globalRoles();

    /** Replaces the global roles config; returns "" on success or an error message. */
    String saveGlobalRoles(List<RoleEntryDto> roles);

    BudgetsDto globalBudgets();

    String saveGlobalBudgets(BudgetsDto budgets);

    /** A project's role OVERRIDES from its .swarmcoder/project.yaml (blank = inherit). */
    List<RoleEntryDto> projectRoles(String projectId);

    /**
     * How many workers each of this project's pieces of work gets, or 0 when the project says
     * nothing and inherits the number in the settings file.
     */
    int projectWorkersPerTask(String projectId);

    /**
     * Persists a project's settings: context folders (CSV), role overrides and the project's own
     * worker count, written to .swarmcoder/project.yaml; the project's engine context rebuilds on
     * next use. {@code workersPerTask} of 0 means "inherit", and is what clears an override.
     * Returns "" on success or an error message.
     */
    String saveProjectConfig(String projectId, String contextPathsCsv, List<RoleEntryDto> roles,
                             int workersPerTask);

    // --- Knowledge curation (store-first KnowledgeDoc objects, never markdown files) -------------

    /** The current project's curated knowledge; PROPOSED entries await operator review. */
    List<com.swarmcoder.domain.KnowledgeDoc> knowledgeDocs();

    /** Creates (blank docId) or updates a doc; returns the doc id, or "error: ...". */
    String saveKnowledgeDoc(com.swarmcoder.domain.KnowledgeDoc doc);

    /** Promotes a PROPOSED doc to ACTIVE — it starts feeding briefs; returns "" or an error. */
    String acceptKnowledgeDoc(String docId);

    /** Deletes a doc; returns "" or an error. */
    String deleteKnowledgeDoc(String docId);

    // --- Running with nobody watching -----------------------------------------------------------

    /**
     * Switches the current project into autonomous running: the machine reads the loaded documents,
     * answers the questions about them itself, agrees the requirements, plans the stories, marks
     * them ready and starts building - with nobody present.
     *
     * <p><b>This overrides the rule that the definition of done is human</b>, deliberately and on
     * the operator's own instruction. The cost is real and is not hidden: a clarifying question
     * exists because the document did not say, so an answer to one is an invented requirement that
     * everything downstream will then honestly prove was built. Every decision taken this way is
     * written down as it is taken, and the invented ones are marked as invented - read them with
     * {@link #autonomousDecisions()}.
     *
     * <p>It stops on {@link #stopAutonomousBuild}, on {@code budgets.wallClockCeilingHours}, on
     * {@code budgets.maxCloudTokensPerRun}, or when there is nothing left it could do. It is held in
     * memory only: restarting SwarmCoder ends it and it does not come back on its own.
     *
     * @return "" or "error: ..."
     */
    String startAutonomousBuild();

    /**
     * Stops autonomous running now. Anything already building carries on to its end - killing a
     * running build would throw away work that is minutes from being provable - but nothing new is
     * started and nothing further is decided.
     *
     * @return "" (stopping something that is not running is not an error)
     */
    String stopAutonomousBuild();

    /** True while the machine is entitled to decide things on the operator's behalf. */
    boolean autonomousRunning();

    /** One sentence saying what it is doing, how long it has been going, and when it will stop. */
    String autonomousStatus();

    /**
     * Everything the machine has decided for this project while running unattended, oldest first.
     *
     * <p>This is the one place the operator reads to find out what was decided while they were
     * asleep. The entries flagged as not grounded are the ones that matter: those are requirements
     * the machine invented because the documents were silent, and they are now what the product is
     * held to.
     */
    List<com.swarmcoder.domain.AutonomousDecision> autonomousDecisions();

    // --- Researcher agent (web + Context7 → PROPOSED knowledge docs) -----------------------------

    /**
     * Starts a research mission for the current project (empty topic = survey its libraries).
     * Fire-and-forget: findings land as PROPOSED knowledge docs. Returns "" or an error
     * (e.g. a mission is already running).
     */
    String startResearch(String topic);

    /** The live status line of the current project's research mission, or "idle". */
    String researchStatus();
}


