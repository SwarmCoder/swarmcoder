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
import com.zeroz4j.api.DataModel;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
/**
 * One unit of work a swarm of agents attempts: what to change, which files it may touch, and what
 * has to be true afterwards.
 *
 * <p>It travels on the wire itself. Until ZeroZ Stack 0.4.0 the serializer had no {@code Set} tag
 * and this class has three {@code Set} fields ({@link #writeSet}, {@link #readSet},
 * {@link #requirementIds}), so the console shipped a hand-written {@code BacklogTask} projection
 * instead. {@code Set}, {@code UUID}, {@code Instant} and enums are all supported now, the
 * projection is deleted, and the backlog carries the real object.
 */
@DataModel
@JsonTypeName("Task")
public class Task {
    private UUID id;
    private long revision;
    private String title;
    private String instructions;
    private Set<String> writeSet;
    private Set<String> readSet;
    private List<AcceptanceCriterion> criteria;
    private String acceptanceTestDir;
    private UUID knowledgeBriefId;
    private TokenBudget budget;
    private SwarmPolicy swarmPolicy;
    private TaskState state;
    /** Requirement ids (DesignDocument.requirements) this task helps satisfy — traceability. */
    private Set<UUID> requirementIds;
    /**
     * The {@link Story} this task belongs to (author decision 2026-07-25). Every task belongs to
     * exactly one story; the story is the durable work item that survives retries, so this is the
     * link that keeps the requirement→commit trace intact when a run is re-attempted.
     */
    private UUID storyId;
    /**
     * Ids of the {@link AcceptanceCriterion}s (owned by {@link BrdRequirement}) this task satisfies,
     * drawn from its story's slice. Replaces owning copies of criteria for delivery work;
     * {@link #criteria} remains for ENABLER tasks that answer to no requirement.
     */
    private Set<UUID> criterionIds;
    /** The candidate that won judging — only its commit is meaningful (N workers produce N branches). */
    private UUID selectedCandidateId;
    /** The durable git link: the sha of the selected candidate's work. */
    private String commitSha;
    /**
     * What the test-authoring stage did for this task, written the moment it happens; null until
     * the stage reaches the task. This is what puts a "3 tests, 2 checks" badge on the task box
     * while the stage is still running instead of leaving the graph silent for minutes - see
     * {@link AuthoredTests} for the three states.
     */
    private AuthoredTests authoredTests;
    /**
     * The acceptance-test files the test author wrote FOR THIS TASK, repo-relative, as they sit in
     * the run's acceptance-tests commit ({@code Run.acceptanceTestsCommit}).
     *
     * <p>This is the list a candidate of this task is verified against — exactly these files are
     * placed into its worktree before the acceptance stage runs, and no other acceptance test is.
     * Empty for a task that claims no check: nothing is placed and nothing runs, which is the
     * documented allowance for an enabler. Recorded on the task because the run-level commit holds
     * every task's tests together and only the task knows which of them are its own.
     */
    private List<String> authoredTestPaths;
    /**
     * Set when this task's acceptance tests were ALREADY GREEN on the tree its own wave was cut
     * from - because the waves in front of it delivered what those tests measure. Null in every
     * other case, which is the normal one.
     *
     * <p>Appended, never reordered: this class is persisted. See {@link ChecksAlreadyProved} for
     * why an already-green wave is written down rather than parked on.
     */
    private ChecksAlreadyProved checksAlreadyProved;
    /**
     * The design contracts this task was told to deliver, verbatim: their fully-qualified type
     * names and the members an acceptance test will touch (author decision, 2026-09-03).
     *
     * <p>Empty for a task that delivers no contract, which is most of them, and then nothing here
     * changes. When it is not empty it is a promise the candidate is held to mechanically: a
     * candidate whose tree does not declare one of these types, with these members, has not
     * delivered what a later wave's tests were written against, so it fails verification with the
     * contract named rather than reaching the judge and leaving the judge to guess.
     *
     * <p>Appended, never reordered: this class is persisted.
     */
    private List<ApiContract> deliveredContracts;
    /**
     * True once this task's acceptance test has been sent back to its author for a repair, whether
     * or not the repair fixed it (author decision, 2026-09-05: a broken acceptance test goes back
     * to the test author). Bounds the repair to ONE attempt per task, durably: without this a run
     * parked because the repaired test was STILL broken, then resumed, would dispatch a brand new
     * swarm whose candidates fail the same way and send the test back to its author again, forever.
     * Checked by {@code SwarmEngineImpl} before it ever raises a {@code TestRepairNeeded}.
     */
    private boolean testRepairAttempted;
    /**
     * Files an EARLIER task of the same run delivered that this task was additionally allowed to
     * edit, because its workers showed with evidence that the file as delivered is what stands in
     * their way (harness run 65, 2026-10-02: a text-constants class nobody could call, blamed on
     * the rule that said to use it). They are also in {@link #writeSet}; this list is the record
     * that the widening happened, and what bounds it to ONE per task.
     *
     * <p>Appended, never reordered: this class is persisted. Null in every store written before
     * the field existed, which reads as "never widened".
     */
    private List<String> siblingRepairPaths;
    /**
     * The journeys this task claims: {@code <name>.journey.yaml} files in the protected
     * acceptance directory, written by the test author with the task's tests and held by the
     * run's tests commit (section 63). Kept apart from {@link #authoredTestPaths} because those
     * are compiled and run by the build, and a journey is made by a browser at final integration.
     */
    private List<String> journeyPaths;
    /** True once a failed journey has sent this task back to the workers; it is sent once. */
    private boolean journeyRepairAttempted;
    /**
     * The test author's recorded answer given in place of a journey: why no person using the
     * application in a browser sees or can do anything different when this task is done
     * (section 64). Null when a journey was written or none was asked for. Taken only when the
     * object graph shows no browser code using what the task writes.
     */
    private String journeyWaiver;
    /**
     * True once a journey this task claims failed in the browser and went back to its author
     * (section 69). It goes back once, before any worker repairs anything.
     */
    private boolean journeySentBack;
    /**
     * What sending the journey back to its author came to, in a sentence or two: the author's
     * answer, and whether its correction was taken. Null until a journey was sent back. Shown
     * in the run report and given to the workers when a repair round follows.
     */
    private String journeyReviewNote;
    /**
     * Each review of a journey by its author, as {@code <path>|<failing step number>}, oldest
     * first (section 71). Decides whether a journey that fails again is sent back again: only
     * when it now fails at a later step, and at most twice per journey. Null until reviewed.
     */
    private List<String> journeyReviews;
    /**
     * True once a refusal at final integration that names this task's own files (code nothing
     * can reach) has sent the task back to the workers; it is sent once (section 69).
     */
    private boolean integrationRepairAttempted;
    /**
     * The architect's findings that travel with this task, verbatim: those about the contracts
     * it delivers and the types whose files it reserves, then those about the whole project, as
     * many as fit the per-task bound (section 73). Chosen with no model when the plan is
     * accepted. The workers and the test author of the task are opened with them. Null in every
     * store written before the field existed, which reads as none.
     */
    private List<DesignFinding> architectFindings;
    /**
     * The entries of {@link #writeSet} that were computed, with no model, from what the task
     * claims: the files of the contracts it delivers and every existing file the project's own
     * types say stops compiling with them (section 73). A record for the run report; the write
     * set is what is enforced. Null for a task planned before the field existed.
     */
    private List<String> computedReservation;
    /**
     * Files the selected candidate changed outside the reservation the plan gave this task,
     * allowed by rule because no other task of the plan held them and nothing protects them
     * (section 73). They are added to {@link #writeSet} when the candidate is selected; this
     * list is the record that the reservation grew. Null until it did.
     */
    private List<String> takenBeyondPlan;

    public Task() {}

    public Task(UUID id, long revision, String title, String instructions, Set<String> writeSet, Set<String> readSet, List<AcceptanceCriterion> criteria, String acceptanceTestDir, UUID knowledgeBriefId, TokenBudget budget, SwarmPolicy swarmPolicy, TaskState state) {
        this(id, revision, title, instructions, writeSet, readSet, criteria, acceptanceTestDir, knowledgeBriefId, budget, swarmPolicy, state, new HashSet<>());
    }

    public Task(UUID id, long revision, String title, String instructions, Set<String> writeSet, Set<String> readSet, List<AcceptanceCriterion> criteria, String acceptanceTestDir, UUID knowledgeBriefId, TokenBudget budget, SwarmPolicy swarmPolicy, TaskState state, Set<UUID> requirementIds) {
        this.id = id;
        this.revision = revision;
        this.title = title;
        this.instructions = instructions;
        this.writeSet = writeSet;
        this.readSet = readSet;
        this.criteria = criteria;
        this.acceptanceTestDir = acceptanceTestDir;
        this.knowledgeBriefId = knowledgeBriefId;
        this.budget = budget;
        this.swarmPolicy = swarmPolicy;
        this.state = state;
        this.requirementIds = requirementIds;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long revision() { return revision; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public String title() { return title; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String instructions() { return instructions; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public Set<String> writeSet() { return writeSet; }
    public Set<String> getWriteSet() { return writeSet; }
    public void setWriteSet(Set<String> writeSet) { this.writeSet = writeSet; }
    public Set<String> readSet() { return readSet; }
    public Set<String> getReadSet() { return readSet; }
    public void setReadSet(Set<String> readSet) { this.readSet = readSet; }
    public List<AcceptanceCriterion> criteria() { return criteria; }
    public List<AcceptanceCriterion> getCriteria() { return criteria; }
    public void setCriteria(List<AcceptanceCriterion> criteria) { this.criteria = criteria; }
    public String acceptanceTestDir() { return acceptanceTestDir; }
    public String getAcceptanceTestDir() { return acceptanceTestDir; }
    public void setAcceptanceTestDir(String acceptanceTestDir) { this.acceptanceTestDir = acceptanceTestDir; }
    public UUID knowledgeBriefId() { return knowledgeBriefId; }
    public UUID getKnowledgeBriefId() { return knowledgeBriefId; }
    public void setKnowledgeBriefId(UUID knowledgeBriefId) { this.knowledgeBriefId = knowledgeBriefId; }
    public TokenBudget budget() { return budget; }
    public TokenBudget getBudget() { return budget; }
    public void setBudget(TokenBudget budget) { this.budget = budget; }
    public SwarmPolicy swarmPolicy() { return swarmPolicy; }
    public SwarmPolicy getSwarmPolicy() { return swarmPolicy; }
    public void setSwarmPolicy(SwarmPolicy swarmPolicy) { this.swarmPolicy = swarmPolicy; }
    public TaskState state() { return state; }
    public TaskState getState() { return state; }
    public void setState(TaskState state) { this.state = state; }
    public Set<UUID> requirementIds() { return requirementIds; }
    public Set<UUID> getRequirementIds() { return requirementIds; }
    public void setRequirementIds(Set<UUID> requirementIds) { this.requirementIds = requirementIds; }
    public UUID storyId() { return storyId; }
    public UUID getStoryId() { return storyId; }
    public void setStoryId(UUID storyId) { this.storyId = storyId; }
    /** Null-safe read; use {@link #setCriterionIds} to write. */
    public Set<UUID> criterionIds() { return criterionIds == null ? Set.of() : criterionIds; }
    public Set<UUID> getCriterionIds() { return criterionIds; }
    public void setCriterionIds(Set<UUID> criterionIds) { this.criterionIds = criterionIds; }
    public UUID selectedCandidateId() { return selectedCandidateId; }
    public UUID getSelectedCandidateId() { return selectedCandidateId; }
    public void setSelectedCandidateId(UUID selectedCandidateId) { this.selectedCandidateId = selectedCandidateId; }
    /** Never null: a task nothing was written for claims no test file. */
    public List<String> authoredTestPaths() {
        return authoredTestPaths == null ? List.of() : authoredTestPaths;
    }
    public List<String> getAuthoredTestPaths() { return authoredTestPaths; }
    public void setAuthoredTestPaths(List<String> authoredTestPaths) {
        this.authoredTestPaths = authoredTestPaths;
    }
    public String commitSha() { return commitSha; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public AuthoredTests authoredTests() { return authoredTests; }
    public AuthoredTests getAuthoredTests() { return authoredTests; }
    public void setAuthoredTests(AuthoredTests authoredTests) { this.authoredTests = authoredTests; }
    /** Null unless this task's tests were already green before its wave - the normal case. */
    public ChecksAlreadyProved checksAlreadyProved() { return checksAlreadyProved; }
    public ChecksAlreadyProved getChecksAlreadyProved() { return checksAlreadyProved; }
    public void setChecksAlreadyProved(ChecksAlreadyProved checksAlreadyProved) {
        this.checksAlreadyProved = checksAlreadyProved;
    }
    /** Never null: a task that delivers no design contract returns an empty list. */
    public List<ApiContract> deliveredContracts() {
        return deliveredContracts == null ? List.of() : deliveredContracts;
    }
    public List<ApiContract> getDeliveredContracts() { return deliveredContracts; }
    public void setDeliveredContracts(List<ApiContract> deliveredContracts) {
        this.deliveredContracts = deliveredContracts;
    }
    public boolean testRepairAttempted() { return testRepairAttempted; }
    public boolean getTestRepairAttempted() { return testRepairAttempted; }
    public void setTestRepairAttempted(boolean testRepairAttempted) {
        this.testRepairAttempted = testRepairAttempted;
    }

    /** Never null: empty until this task was given an earlier task's file to repair. */
    public List<String> siblingRepairPaths() {
        return siblingRepairPaths == null ? List.of() : siblingRepairPaths;
    }
    public List<String> getSiblingRepairPaths() { return siblingRepairPaths; }
    public void setSiblingRepairPaths(List<String> siblingRepairPaths) {
        this.siblingRepairPaths = siblingRepairPaths;
    }

    public List<String> journeyPaths() {
        return journeyPaths == null ? List.of() : journeyPaths;
    }
    public List<String> getJourneyPaths() { return journeyPaths; }
    public void setJourneyPaths(List<String> journeyPaths) { this.journeyPaths = journeyPaths; }

    public String journeyWaiver() { return journeyWaiver; }
    public String getJourneyWaiver() { return journeyWaiver; }
    public void setJourneyWaiver(String journeyWaiver) { this.journeyWaiver = journeyWaiver; }

    public boolean journeySentBack() { return journeySentBack; }
    public boolean getJourneySentBack() { return journeySentBack; }
    public void setJourneySentBack(boolean journeySentBack) {
        this.journeySentBack = journeySentBack;
    }

    public List<String> journeyReviews() {
        return journeyReviews == null ? List.of() : journeyReviews;
    }
    public List<String> getJourneyReviews() { return journeyReviews; }
    public void setJourneyReviews(List<String> journeyReviews) {
        this.journeyReviews = journeyReviews;
    }

    public String journeyReviewNote() { return journeyReviewNote; }
    public String getJourneyReviewNote() { return journeyReviewNote; }
    public void setJourneyReviewNote(String journeyReviewNote) {
        this.journeyReviewNote = journeyReviewNote;
    }

    public boolean integrationRepairAttempted() { return integrationRepairAttempted; }
    public boolean getIntegrationRepairAttempted() { return integrationRepairAttempted; }
    public void setIntegrationRepairAttempted(boolean integrationRepairAttempted) {
        this.integrationRepairAttempted = integrationRepairAttempted;
    }

    /** Never null: empty when the architect kept nothing that concerns this task. */
    public List<DesignFinding> architectFindings() {
        return architectFindings == null ? List.of() : architectFindings;
    }
    public List<DesignFinding> getArchitectFindings() { return architectFindings; }
    public void setArchitectFindings(List<DesignFinding> architectFindings) {
        this.architectFindings = architectFindings;
    }

    /** Never null: the write-set entries that were computed rather than planned. */
    public List<String> computedReservation() {
        return computedReservation == null ? List.of() : computedReservation;
    }
    public List<String> getComputedReservation() { return computedReservation; }
    public void setComputedReservation(List<String> computedReservation) {
        this.computedReservation = computedReservation;
    }

    /** Never null: empty until the selected candidate took a file beyond the plan. */
    public List<String> takenBeyondPlan() {
        return takenBeyondPlan == null ? List.of() : takenBeyondPlan;
    }
    public List<String> getTakenBeyondPlan() { return takenBeyondPlan; }
    public void setTakenBeyondPlan(List<String> takenBeyondPlan) {
        this.takenBeyondPlan = takenBeyondPlan;
    }

    public boolean journeyRepairAttempted() { return journeyRepairAttempted; }
    public boolean getJourneyRepairAttempted() { return journeyRepairAttempted; }
    public void setJourneyRepairAttempted(boolean journeyRepairAttempted) {
        this.journeyRepairAttempted = journeyRepairAttempted;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Task that = (Task) o;
        return Objects.equals(this.id, that.id) && this.revision == that.revision && Objects.equals(this.title, that.title) && Objects.equals(this.instructions, that.instructions) && Objects.equals(this.writeSet, that.writeSet) && Objects.equals(this.readSet, that.readSet) && Objects.equals(this.criteria, that.criteria) && Objects.equals(this.acceptanceTestDir, that.acceptanceTestDir) && Objects.equals(this.knowledgeBriefId, that.knowledgeBriefId) && Objects.equals(this.budget, that.budget) && Objects.equals(this.swarmPolicy, that.swarmPolicy) && Objects.equals(this.state, that.state) && Objects.equals(this.requirementIds, that.requirementIds) && Objects.equals(this.storyId, that.storyId) && Objects.equals(this.criterionIds(), that.criterionIds()) && Objects.equals(this.selectedCandidateId, that.selectedCandidateId) && Objects.equals(this.commitSha, that.commitSha) && Objects.equals(this.authoredTests, that.authoredTests) && Objects.equals(this.authoredTestPaths(), that.authoredTestPaths()) && Objects.equals(this.checksAlreadyProved, that.checksAlreadyProved) && Objects.equals(this.deliveredContracts(), that.deliveredContracts()) && this.testRepairAttempted == that.testRepairAttempted && Objects.equals(this.siblingRepairPaths(), that.siblingRepairPaths())
            && Objects.equals(this.journeyPaths(), that.journeyPaths())
            && this.journeyRepairAttempted == that.journeyRepairAttempted
            && Objects.equals(this.journeyWaiver, that.journeyWaiver)
            && this.journeySentBack == that.journeySentBack
            && Objects.equals(this.journeyReviewNote, that.journeyReviewNote)
            && Objects.equals(this.journeyReviews(), that.journeyReviews())
            && this.integrationRepairAttempted == that.integrationRepairAttempted
            && Objects.equals(this.architectFindings(), that.architectFindings())
            && Objects.equals(this.computedReservation(), that.computedReservation())
            && Objects.equals(this.takenBeyondPlan(), that.takenBeyondPlan());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, revision, title, instructions, writeSet, readSet, criteria, acceptanceTestDir, knowledgeBriefId, budget, swarmPolicy, state, requirementIds, storyId, criterionIds(), selectedCandidateId, commitSha, authoredTests, authoredTestPaths(), checksAlreadyProved, deliveredContracts(), testRepairAttempted, siblingRepairPaths(), journeyPaths(), journeyRepairAttempted, journeyWaiver);
    }
}

