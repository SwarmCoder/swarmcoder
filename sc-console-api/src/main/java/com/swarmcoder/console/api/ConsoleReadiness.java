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

import com.zeroz4j.api.DataModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What the Console can actually do right now.
 *
 * <p>It exists because the shell used to offer everything unconditionally: with no project at all
 * it still showed Requirements, a Swarm Board, Approvals, Insights and a Backlog — surfaces that
 * could not possibly hold anything, because every one of them is scoped to a project. A tool that
 * advertises capability it does not have teaches the operator to distrust it.
 *
 * <p>The capabilities are graded, not binary, and the distinction matters for what to show:
 *
 * <ul>
 *   <li>Without a PROJECT nothing is scoped — no requirements, no backlog, no runs, no knowledge.</li>
 *   <li>With a project but no git REPOSITORY, authoring still works: the BRD, the backlog and the
 *       knowledge base live in the store. Only execution is impossible, because a worker branches
 *       from HEAD into a worktree and there is no HEAD.</li>
 *   <li>Without a reachable SANDBOX (and {@code sandbox.required}) runs would fail at dispatch, so
 *       offering to start one is a promise the system cannot keep.</li>
 * </ul>
 *
 * <p>Judged server-side because the client cannot see any of it: whether the folder is a git repo,
 * whether Docker answered, what the config says.
 *
 * <h2>Past setup: the next step</h2>
 *
 * <p>The three capabilities above stop being interesting the moment a project and a repository
 * exist — and that is precisely where the operator was previously abandoned. Finishing an intake
 * and landing on seventeen DRAFT requirements is a state in which everything is "ready" and nothing
 * says what to do. {@link #nextAction()} and {@link #nextActionText()} continue the same idea into
 * the workflow: one step, named, with the reason it matters, judged from the BRD, the backlog and
 * the run history — none of which the browser can see without asking.
 *
 * <p>{@link #nextActionText()} is prose for a human and may be reworded at any time;
 * {@link #nextAction()} is the stable identity a client may branch on. Both are absent
 * ({@link NextAction#NONE}) when there is genuinely nothing to say, and the bar then renders
 * nothing at all — persistent guidance that cannot fall silent is nagging, not help.
 *
 * <h2>One global answer was not enough</h2>
 *
 * <p>{@link #nextAction()} is computed in a fixed dependency order and the first match wins, so ANY
 * draft requirement outranks everything downstream. That is a waterfall assumption inside a process
 * that is genuinely concurrent — requirements get refined while earlier ones are already being
 * built — and it produced a project with nineteen requirements, four still drafts, six proposed
 * stories and a run in flight being told, permanently, to go back to Requirements.
 *
 * <p>So {@link #stages()} carries what each stage would say if you were standing IN it, judged from
 * that stage's own facts. The global field keeps its meaning and its job: it is the recommendation
 * that decides which stage the shell lands on. See {@link StageGuidance}.
 */
@DataModel
public class ConsoleReadiness {

    private boolean hasProject;
    private boolean hasRepository;
    private boolean canRun;
    /** Why runs are impossible, phrased for the operator, or null when they are possible. */
    private String runBlocker;
    /** The concrete next step when something is missing — shown in the setup panel. */
    private String nextStep;
    private String projectName;
    private String repositoryPath;
    /** Which workflow step is current, or {@link NextAction#NONE}. Null on pre-v2 values. */
    private NextAction nextAction;
    /** The sentence shown in the next-step bar: what to do and why it matters. Nullable. */
    private String nextActionText;
    /**
     * What each stage says for itself — one entry per stage that has an opinion, keyed by the same
     * ids {@link StageGuidance} names. Absent (empty) on pre-v3 values and whenever the computation
     * had to be abandoned, and every reader treats absence as "this stage has nothing to say".
     */
    private List<StageGuidance> stages;
    /**
     * How many requirements are agreed, and how many are still drafts — the header's counts line.
     *
     * <p>Server-side and not counted in the browser, even though the client holds the graph it would
     * count. The guidance sentence beside them is judged from the server's own read of the store, so a
     * client that counted for itself would be a SECOND source for the same fact, and the two drift the
     * moment one signal is republished without the other: the header said "0 agreed · 1 draft" while
     * the line under it said "the agreed requirements have no stories yet". One source, one answer.
     */
    private int requirementsAgreed;
    private int requirementsDrafts;

    public ConsoleReadiness() {}

    public ConsoleReadiness(boolean hasProject, boolean hasRepository, boolean canRun,
                            String runBlocker, String nextStep, String projectName,
                            String repositoryPath) {
        this(hasProject, hasRepository, canRun, runBlocker, nextStep, projectName, repositoryPath,
            NextAction.NONE, null);
    }

    public ConsoleReadiness(boolean hasProject, boolean hasRepository, boolean canRun,
                            String runBlocker, String nextStep, String projectName,
                            String repositoryPath, NextAction nextAction, String nextActionText) {
        this.hasProject = hasProject;
        this.hasRepository = hasRepository;
        this.canRun = canRun;
        this.runBlocker = runBlocker;
        this.nextStep = nextStep;
        this.projectName = projectName;
        this.repositoryPath = repositoryPath;
        this.nextAction = nextAction;
        this.nextActionText = nextActionText;
    }

    /** The state before anything is configured — and the signal's initial value. */
    public static ConsoleReadiness empty() {
        return new ConsoleReadiness(false, false, false,
            "there is no project yet", "Create a project to begin.", null, null);
    }

    /** True when requirements, backlog and knowledge are usable — they need only a project. */
    public boolean canAuthor() {
        return hasProject;
    }

    /**
     * True when there is exactly one thing worth saying right now. The next-step bar renders only
     * then — both halves are required, because a key with no sentence is a button with no reason and
     * a sentence with no key is advice nothing can act on.
     */
    public boolean hasNextAction() {
        return nextAction() != NextAction.NONE
            && nextActionText != null && !nextActionText.isBlank();
    }

    /**
     * What the named stage says for itself, or null when it has nothing to say.
     *
     * <p>Null rather than an empty value on purpose: a stage with no entry and a stage that has
     * deliberately fallen silent are the same thing to a caller, and inventing a placeholder would
     * let a client render an empty bar for a project whose readiness predates this field.
     *
     * @param stageId one of the {@link StageGuidance} id constants
     */
    public StageGuidance guidance(String stageId) {
        if (stageId != null) {
            for (StageGuidance guidance : stages()) {
                if (stageId.equals(guidance.stage())) {
                    return guidance;
                }
            }
        }
        return null;
    }

    /** How many things in one stage are waiting on the operator; 0 when the stage is silent. */
    public int attention(String stageId) {
        StageGuidance guidance = guidance(stageId);
        return guidance == null ? 0 : guidance.attention();
    }

    public boolean hasProject() { return hasProject; }
    public boolean isHasProject() { return hasProject; }
    public void setHasProject(boolean hasProject) { this.hasProject = hasProject; }
    public boolean hasRepository() { return hasRepository; }
    public boolean isHasRepository() { return hasRepository; }
    public void setHasRepository(boolean hasRepository) { this.hasRepository = hasRepository; }
    public boolean canRun() { return canRun; }
    public boolean isCanRun() { return canRun; }
    public void setCanRun(boolean canRun) { this.canRun = canRun; }
    public String runBlocker() { return runBlocker; }
    public String getRunBlocker() { return runBlocker; }
    public void setRunBlocker(String runBlocker) { this.runBlocker = runBlocker; }
    public String nextStep() { return nextStep; }
    public String getNextStep() { return nextStep; }
    public void setNextStep(String nextStep) { this.nextStep = nextStep; }
    public String projectName() { return projectName; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String repositoryPath() { return repositoryPath; }
    public String getRepositoryPath() { return repositoryPath; }
    public void setRepositoryPath(String repositoryPath) { this.repositoryPath = repositoryPath; }
    /** Null-safe: an unrecorded action IS "nothing to do", which is what a pre-v2 value means. */
    public NextAction nextAction() { return nextAction == null ? NextAction.NONE : nextAction; }
    public NextAction getNextAction() { return nextAction; }
    public void setNextAction(NextAction nextAction) { this.nextAction = nextAction; }
    public String nextActionText() { return nextActionText; }
    public String getNextActionText() { return nextActionText; }
    public void setNextActionText(String nextActionText) { this.nextActionText = nextActionText; }
    public int requirementsAgreed() { return requirementsAgreed; }
    public int getRequirementsAgreed() { return requirementsAgreed; }
    public void setRequirementsAgreed(int v) { this.requirementsAgreed = v; }
    public int requirementsDrafts() { return requirementsDrafts; }
    public int getRequirementsDrafts() { return requirementsDrafts; }
    public void setRequirementsDrafts(int v) { this.requirementsDrafts = v; }
    /** Null-safe: no per-stage guidance recorded IS "no stage has anything to say". */
    public List<StageGuidance> stages() { return stages == null ? List.of() : stages; }
    public List<StageGuidance> getStages() { return stages(); }
    public void setStages(List<StageGuidance> stages) { this.stages = stages; }

    /** Records one stage's guidance, replacing any earlier entry for the same stage. */
    public void putGuidance(StageGuidance guidance) {
        if (guidance == null || guidance.stage() == null) {
            return;
        }
        if (stages == null) {
            stages = new ArrayList<>();
        }
        stages.removeIf(existing -> guidance.stage().equals(existing.stage()));
        stages.add(guidance);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConsoleReadiness that = (ConsoleReadiness) o;
        return this.hasProject == that.hasProject && this.hasRepository == that.hasRepository
            && this.canRun == that.canRun && Objects.equals(this.runBlocker, that.runBlocker)
            && Objects.equals(this.nextStep, that.nextStep)
            && Objects.equals(this.projectName, that.projectName)
            && Objects.equals(this.repositoryPath, that.repositoryPath)
            && this.nextAction() == that.nextAction()
            && Objects.equals(this.nextActionText, that.nextActionText)
            && this.requirementsAgreed == that.requirementsAgreed
            && this.requirementsDrafts == that.requirementsDrafts
            && Objects.equals(this.stages(), that.stages());
    }

    // nextAction and stages compare through their NULL-SAFE accessors, not the raw fields: an unset
    // action IS NONE and an unset list IS empty, so the two spellings must be equal. The wire
    // serializer reads through the same accessors, so comparing the raw fields would make a
    // round-tripped readiness unequal to the one that was sent — which silently breaks signal dedup
    // and redraws every bound view on every publish.
    @Override
    public int hashCode() {
        return Objects.hash(hasProject, hasRepository, canRun, runBlocker, nextStep,
            projectName, repositoryPath, nextAction(), nextActionText, stages(),
            requirementsAgreed, requirementsDrafts);
    }
}
