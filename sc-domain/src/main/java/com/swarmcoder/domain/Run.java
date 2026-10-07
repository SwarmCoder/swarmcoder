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
package com.swarmcoder.domain;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
@JsonTypeName("Run")
public class Run {
    private UUID id;
    private WorkflowKind kind;
    private RunState state;
    private UUID designId;
    private UUID taskGraphId;
    private Budget cloudBudget;
    private Instant startedAt;
    private RunReport report;
    /**
     * The project this run belongs to (spec: multi-project). Set via {@link #setProjectId}
     * rather than the all-args constructor so existing call sites are unaffected; a null value
     * (e.g. runs persisted before multi-project) is treated as the single default project.
     */
    private UUID projectId;
    /**
     * The {@link Story} this run is executing (author decision 2026-07-25). A run is an
     * <em>attempt</em>; the story is the durable work item, so one story may have many runs. Null
     * for runs persisted before the backlog existed.
     */
    private UUID storyId;
    /**
     * When a live process last touched this run.
     *
     * <p>A run's STATE cannot distinguish work in progress from work abandoned: one parked at
     * EXECUTING with a thread on it and one whose JVM died hours ago look identical in the store.
     * That is how a crash mid-run left stories saying "building now" for ever, with nothing able to
     * move them. Stamped on every persist, so a stale value means nobody is driving this.
     */
    private Instant heartbeatAt;
    /**
     * When this run started waiting for a model endpoint to come back, or null when it is not
     * waiting (UX v3 §2.4).
     *
     * <p>Deliberately NOT a {@link RunState}. The run stays at the stage it is going to retry, so
     * crash-resume still restarts in the right place and the most-read persisted enum in the store
     * needed no new constant. "Paused" is therefore a fact about a run at some state, not a state —
     * which is also the truth: something IS driving it (the retry loop keeps the heartbeat fresh),
     * it just cannot make progress yet.
     *
     * <p>Stamped once per pause, at the first failure, because the escalation threshold is measured
     * from when the endpoint went away. Every derivation from it lives in {@code OutagePause}.
     */
    private Instant pausedSince;
    /** The operator-facing sentence for the pause; written by {@code OutagePause.sentence}. */
    private String pauseReason;
    /** The endpoint that stopped answering — what the card links to in Setup. */
    private String pauseEndpoint;
    /**
     * When a workflow stage stopped this run and raised a question for the operator, or null when
     * nothing is parked.
     *
     * <p>Deliberately separate from {@link #pausedSince}. A pause is the engine still driving the
     * run, waiting for a model endpoint that will come back on its own; a park is the opposite — a
     * stage ran to completion, decided it could not go on, and raised a {@code BLOCKED_TASK}
     * decision. Nothing is retrying it and no amount of waiting changes that, so the two need to be
     * told apart rather than folded into one field with a flag: the escalation math a pause carries
     * (see {@code RunPause}) does not apply to a park, which must read as stopped immediately.
     *
     * <p>Not a {@link RunState}, for the same reason the pause is not one: the run stays at the
     * stage it parked in, so a resumed run retries that exact stage rather than needing a state of
     * its own to represent "stopped here". Cleared the moment something takes the run up again —
     * see {@code GreenfieldWorkflow.advance} — so the card stops saying "stopped" the instant work
     * actually resumes, rather than however long it takes the next heartbeat to look fresh.
     */
    private Instant parkedAt;
    /**
     * The operator-facing reason for the park — the same brief the {@code BLOCKED_TASK} decision
     * carries, reused rather than re-worded so the card and the decision never disagree about why
     * the run stopped.
     */
    private String parkReason;
    /**
     * The branch this run builds ON — the project's delivery branch, normally {@code master} or
     * {@code main}. Null for a run started before base pinning existed, which is read as "HEAD".
     */
    private String baseRef;
    /**
     * The exact commit every worktree of this run is cut from, resolved once when the run starts.
     *
     * <p>Two things depend on it. A story that builds on an earlier story must SEE that story's code,
     * and it can only do so if it branches from the state the earlier story was delivered into —
     * every worktree used to be cut from a live {@code HEAD} instead, so a dependent story literally
     * could not see its predecessor's work. And a run pinned to one commit is a run that can be
     * reasoned about: with a live {@code HEAD}, another story being accepted halfway through moved
     * the ground under the workers still going, so the candidates of one task were built on a
     * different tree from the candidates of the next.
     */
    private String baseCommit;
    /**
     * The commit holding this run's acceptance tests: {@link #baseCommit} plus the files the test
     * author wrote, on the run's own ref {@code swarm/tests/<runId>}. Null until TEST_AUTHORING has
     * written and committed them, and for a run started before the tests lived on a run ref.
     *
     * <p>The tests are NOT committed on the delivery branch. A run is pinned to {@link #baseCommit}
     * at intake, three stages before a test exists, so a test commit on the delivery branch was a
     * commit no worktree of the run could see — and one that outlived the run: a dead run's tests
     * stayed on the branch and broke the test-compile of every later run. A run-scoped commit is
     * where each candidate's verification fetches exactly the files its task claims, and what the
     * integration branch descends from, so the tests reach the delivery branch only together with
     * the code that makes them pass.
     */
    private String acceptanceTestsCommit;
    /**
     * The commit the NEXT wave of tasks is cut from: {@link #baseCommit} with the winners of every
     * wave that has already finished merged onto it, on the run's own ref
     * {@code swarm/progress/<runId>}. Null until the first wave has produced a winner, and for a
     * run persisted before waves built on each other.
     *
     * <p><b>Why it exists.</b> Every worktree of a run used to be cut from {@link #baseCommit},
     * including the worktrees of tasks whose whole job depends on a task that has already finished
     * and won. A task in the second wave therefore could not see the classes the first wave
     * delivered: its workers went hunting for a type that existed only on somebody else's candidate
     * branch, wrote their own copy of it or gave up, and were killed for making no progress. A plan
     * with a dependency edge in it failed at the second task, every time, and the failure was
     * recorded against the workers.
     *
     * <p><b>It does not replace {@link #baseCommit}.</b> The pinned base is the operator's starting
     * point and the thing the run is reasoned about from — resume, crash recovery, "this run builds
     * on X". This field is where the run has got to since, and it only ever moves forward, one wave
     * at a time.
     */
    private String progressCommit;
    /**
     * Every objection from an opinion check that was carried past rather than parking this run
     * (owner decision, 2026-10-01 — see {@link OpinionPolicy}). Null on a run stored before this
     * existed, which reads as none. Never changed in place: each addition puts a NEW list here, so
     * the store always sees a reference it has not stored yet.
     */
    private List<CarriedWarning> carriedWarnings;
    /**
     * The project's own tests that already failed on the tree this run started from, as
     * {@code class#method} ids, established once before the first wave (owner decision after the
     * audit of 2026-10-02). They are not held against any candidate or against the final
     * integration. Null on a run stored before this existed and on a run where none failed or
     * nothing could be established - all of which mean "hold every failure against the work".
     */
    private List<String> baselineFailingTests;
    /**
     * Acceptance test files of checks that were already satisfied on the tree this run started
     * from, whose task was dropped because nothing was left for it to build (owner decision
     * after the audit of 2026-10-02). No task claims them any more, so they are kept here: the
     * final integration still places and runs them, and that is what stamps their criteria.
     * Null on a run stored before this existed and on a run with none.
     */
    private List<String> alreadySatisfiedTests;
    /**
     * True when every task of the plan had every check already satisfied by the tree the run
     * started from, so nothing is dispatched (owner decision, 2026-10-03): the unchanged tree is
     * verified once with those tests at the final integration, the criteria are stamped from
     * that run, and the story is delivered as it stands. False on a run stored before this
     * existed.
     */
    private boolean nothingToBuild;

    public Run() {}

    /**
     * The only way to build a run, and it takes the project deliberately.
     *
     * <p>There used to be an eight-argument constructor without {@code projectId} or
     * {@code storyId}. Every workflow state transition rebuilt the run through it, so a run lost
     * its project and its story on the first transition it made — which is why, after a restart, an
     * unfinished run had no project to be resumed against and was continued using the DEFAULT
     * project's repository and the DEFAULT project's locked modules. The main workflow was taught
     * to copy the two fields back on (corrections §12); the other four workflows were not, and
     * nothing stopped the next one forgetting either.
     *
     * <p>So the ability to build a run without its project is gone rather than guarded, and
     * transitions do not rebuild runs at all — see {@link #withState}.
     *
     * @param projectId the project whose repository, git service and locked modules this run is
     *                  driven against; null only for a run that predates multi-project, which is
     *                  read as the default project
     * @param storyId   the backlog story this run is an attempt at, or null for an ad-hoc run
     */
    public Run(UUID id, WorkflowKind kind, RunState state, UUID projectId, UUID storyId,
               UUID designId, UUID taskGraphId, Budget cloudBudget, Instant startedAt,
               RunReport report) {
        this.id = id;
        this.kind = kind;
        this.state = state;
        this.projectId = projectId;
        this.storyId = storyId;
        this.designId = designId;
        this.taskGraphId = taskGraphId;
        this.cloudBudget = cloudBudget;
        this.startedAt = startedAt;
        this.report = report;
    }

    /** A copy of this run at a new state, carrying every other field. */
    public Run withState(RunState state) {
        return withState(state, this.designId, this.taskGraphId);
    }

    /**
     * A copy of this run at a new state, with the design and task graph it now has and every other
     * field carried across — project, story, budget, report, and the outage pause.
     *
     * <p>Carrying the pause is a decision, not tidiness. A transition that happened to forget it
     * would clear an outage pause as a SIDE EFFECT of which fields this method lists, which is
     * exactly how the project and the story were lost. Clearing a pause happens in one place only:
     * {@code OutagePause.resumed}, once a stage has actually progressed.
     */
    public Run withState(RunState state, UUID designId, UUID taskGraphId) {
        Run next = new Run(this.id, this.kind, state, this.projectId, this.storyId,
            designId, taskGraphId, this.cloudBudget, this.startedAt, this.report);
        next.heartbeatAt = this.heartbeatAt;
        next.pausedSince = this.pausedSince;
        next.pauseReason = this.pauseReason;
        next.pauseEndpoint = this.pauseEndpoint;
        // Carried for the same reason the pause is: a transition that dropped it as a side effect
        // of copying fields would be a second way to lose a fact that must only ever be cleared
        // deliberately (GreenfieldWorkflow.advance, the moment the run is taken up again).
        next.parkedAt = this.parkedAt;
        next.parkReason = this.parkReason;
        // Carried for the same reason the pause is: the base commit is decided once, at the start,
        // and a transition that quietly dropped it would put the next stage back on a live HEAD —
        // which is the exact defect this field exists to remove.
        next.baseRef = this.baseRef;
        next.baseCommit = this.baseCommit;
        next.acceptanceTestsCommit = this.acceptanceTestsCommit;
        // Carried for exactly the reason the base commit is: a transition that dropped it would put
        // the next wave back on the bare pinned base, which is the defect this field removes.
        next.progressCommit = this.progressCommit;
        // Carried so the list printed at the end of the run is the whole run's, not one stage's.
        next.carriedWarnings = this.carriedWarnings;
        // Carried so the final integration judges the merged tree against the same baseline the
        // candidates were judged against.
        next.baselineFailingTests = this.baselineFailingTests;
        next.alreadySatisfiedTests = this.alreadySatisfiedTests;
        next.nothingToBuild = this.nothingToBuild;
        return next;
    }

    /**
     * Whether anything was ever planned for this run — the cheapest true statement about whether
     * work was attempted at all.
     *
     * <p>A task graph exists only after an architect designed and a planner sliced, and nothing can
     * be built, verified or merged without one. A run that reaches DELIVERED without a task graph
     * therefore delivered nothing; {@code RunPersister} refuses to record that.
     */
    public boolean wasEverPlanned() {
        return taskGraphId != null;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public WorkflowKind kind() { return kind; }
    public WorkflowKind getKind() { return kind; }
    public void setKind(WorkflowKind kind) { this.kind = kind; }
    public RunState state() { return state; }
    public RunState getState() { return state; }
    public void setState(RunState state) { this.state = state; }
    public UUID designId() { return designId; }
    public UUID getDesignId() { return designId; }
    public void setDesignId(UUID designId) { this.designId = designId; }
    public UUID taskGraphId() { return taskGraphId; }
    public UUID getTaskGraphId() { return taskGraphId; }
    public void setTaskGraphId(UUID taskGraphId) { this.taskGraphId = taskGraphId; }
    public Budget cloudBudget() { return cloudBudget; }
    public Budget getCloudBudget() { return cloudBudget; }
    public void setCloudBudget(Budget cloudBudget) { this.cloudBudget = cloudBudget; }
    public Instant startedAt() { return startedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public RunReport report() { return report; }
    public RunReport getReport() { return report; }
    public void setReport(RunReport report) { this.report = report; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public Instant heartbeatAt() { return heartbeatAt; }
    public Instant getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(Instant heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public UUID storyId() { return storyId; }
    public UUID getStoryId() { return storyId; }
    public void setStoryId(UUID storyId) { this.storyId = storyId; }
    public Instant pausedSince() { return pausedSince; }
    public Instant getPausedSince() { return pausedSince; }
    public void setPausedSince(Instant pausedSince) { this.pausedSince = pausedSince; }
    public String pauseReason() { return pauseReason; }
    public String getPauseReason() { return pauseReason; }
    public void setPauseReason(String pauseReason) { this.pauseReason = pauseReason; }
    public String pauseEndpoint() { return pauseEndpoint; }
    public String getPauseEndpoint() { return pauseEndpoint; }
    public void setPauseEndpoint(String pauseEndpoint) { this.pauseEndpoint = pauseEndpoint; }
    public Instant parkedAt() { return parkedAt; }
    public Instant getParkedAt() { return parkedAt; }
    public void setParkedAt(Instant parkedAt) { this.parkedAt = parkedAt; }
    /** The opinion-check objections this run carried past, oldest first; never null. */
    public synchronized List<CarriedWarning> carriedWarnings() {
        return carriedWarnings == null ? List.of() : List.copyOf(carriedWarnings);
    }
    public List<CarriedWarning> getCarriedWarnings() { return carriedWarnings(); }
    public synchronized void setCarriedWarnings(List<CarriedWarning> warnings) {
        this.carriedWarnings = warnings == null || warnings.isEmpty() ? null : new ArrayList<>(warnings);
    }
    /**
     * Records one carried objection. The same check, stage and objection is recorded once.
     *
     * @return false when it was already there
     */
    public synchronized boolean carryWarning(CarriedWarning warning) {
        if (warning == null || (carriedWarnings != null && carriedWarnings.contains(warning))) {
            return false;
        }
        List<CarriedWarning> next = new ArrayList<>(carriedWarnings == null ? List.of() : carriedWarnings);
        next.add(warning);
        this.carriedWarnings = next;
        return true;
    }
    public synchronized List<String> baselineFailingTests() {
        return baselineFailingTests == null ? List.of() : List.copyOf(baselineFailingTests);
    }
    public List<String> getBaselineFailingTests() { return baselineFailingTests(); }
    public synchronized void setBaselineFailingTests(List<String> tests) {
        this.baselineFailingTests = tests == null || tests.isEmpty() ? null : new ArrayList<>(tests);
    }
    public synchronized List<String> alreadySatisfiedTests() {
        return alreadySatisfiedTests == null ? List.of() : List.copyOf(alreadySatisfiedTests);
    }
    public List<String> getAlreadySatisfiedTests() { return alreadySatisfiedTests(); }
    public synchronized void setAlreadySatisfiedTests(List<String> tests) {
        this.alreadySatisfiedTests = tests == null || tests.isEmpty() ? null : new ArrayList<>(tests);
    }
    public boolean nothingToBuild() { return nothingToBuild; }
    public boolean getNothingToBuild() { return nothingToBuild; }
    public void setNothingToBuild(boolean nothingToBuild) { this.nothingToBuild = nothingToBuild; }
    public String parkReason() { return parkReason; }
    public String getParkReason() { return parkReason; }
    public void setParkReason(String parkReason) { this.parkReason = parkReason; }
    public String baseRef() { return baseRef; }
    public String getBaseRef() { return baseRef; }
    public void setBaseRef(String baseRef) { this.baseRef = baseRef; }
    public String baseCommit() { return baseCommit; }
    public String getBaseCommit() { return baseCommit; }
    public void setBaseCommit(String baseCommit) { this.baseCommit = baseCommit; }
    public String acceptanceTestsCommit() { return acceptanceTestsCommit; }
    public String getAcceptanceTestsCommit() { return acceptanceTestsCommit; }
    public void setAcceptanceTestsCommit(String acceptanceTestsCommit) {
        this.acceptanceTestsCommit = acceptanceTestsCommit;
    }
    public String progressCommit() { return progressCommit; }
    public String getProgressCommit() { return progressCommit; }
    public void setProgressCommit(String progressCommit) { this.progressCommit = progressCommit; }

    /**
     * The ref every worktree of this run is cut from: the pinned base commit, or {@code "HEAD"} for a
     * run that predates pinning. Never null, so no caller has to remember the fallback.
     */
    public String startPoint() {
        return baseCommit == null || baseCommit.isBlank() ? "HEAD" : baseCommit;
    }

    /**
     * The ref the tasks that have NOT run yet are cut from: the winners of every finished wave when
     * there are any, else the pinned base. Never null.
     *
     * <p>This is the one a worker's worktree, a later wave's red-check and a repair worker all
     * start from, so a task that depends on an earlier task sees that task's delivered code. The
     * first wave has nothing in front of it and gets the pinned base, which is exactly what every
     * wave used to get.
     */
    public String progressPoint() {
        return progressCommit == null || progressCommit.isBlank() ? startPoint() : progressCommit;
    }

    /**
     * The ref a worktree that VERIFIES this run's work is cut from: the acceptance-tests commit when
     * there is one, else the same start point the workers had. The two differ by exactly the test
     * files, so a tree cut here is the workers' tree plus the tests — which is what the red-check
     * and the integration branch need, and what a candidate's worktree is brought to by overlaying
     * the files its task claims.
     */
    public String verificationPoint() {
        return acceptanceTestsCommit == null || acceptanceTestsCommit.isBlank()
            ? startPoint() : acceptanceTestsCommit;
    }

    // projectId and storyId participate in equality: they were previously omitted, so two runs
    // differing only in project (or story) compared equal — which silently breaks anything that
    // dedups by equals(), including the reactive signals the Console renders from.
    //
    // The pause fields participate for the same reason, and it matters more here than anywhere: a
    // run that has just gone from building to paused differs in NOTHING else — same state, same ids.
    // Leaving them out would make the paused copy equal to the unpaused one, the signal would dedup
    // it away, and the card would go on saying "building" through the entire outage. heartbeatAt is
    // still excluded: it changes on every persist, so including it would defeat dedup completely.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Run that = (Run) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.kind, that.kind) && Objects.equals(this.state, that.state) && Objects.equals(this.designId, that.designId) && Objects.equals(this.taskGraphId, that.taskGraphId) && Objects.equals(this.cloudBudget, that.cloudBudget) && Objects.equals(this.startedAt, that.startedAt) && Objects.equals(this.report, that.report) && Objects.equals(this.projectId, that.projectId) && Objects.equals(this.storyId, that.storyId) && Objects.equals(this.pausedSince, that.pausedSince) && Objects.equals(this.pauseReason, that.pauseReason) && Objects.equals(this.pauseEndpoint, that.pauseEndpoint) && Objects.equals(this.baseRef, that.baseRef) && Objects.equals(this.baseCommit, that.baseCommit) && Objects.equals(this.acceptanceTestsCommit, that.acceptanceTestsCommit) && Objects.equals(this.progressCommit, that.progressCommit) && Objects.equals(this.parkedAt, that.parkedAt) && Objects.equals(this.parkReason, that.parkReason) && Objects.equals(this.carriedWarnings(), that.carriedWarnings());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, state, designId, taskGraphId, cloudBudget, startedAt, report, projectId, storyId, pausedSince, pauseReason, pauseEndpoint, baseRef, baseCommit, acceptanceTestsCommit, progressCommit, parkedAt, parkReason, carriedWarnings());
    }
}

