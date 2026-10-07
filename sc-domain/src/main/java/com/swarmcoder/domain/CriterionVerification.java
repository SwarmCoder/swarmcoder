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

import com.zeroz4j.api.DataModel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One record of one acceptance criterion being checked at one commit — append-only, never updated.
 * {@code result} is the outcome, {@code testRef} names the check that produced it (a test id,
 * assertion name or probe), and {@code commitSha} pins the code it was checked against.
 * {@code runId}, {@code storyId} and {@code taskId} attribute the verification to the work that
 * triggered it.
 *
 * <p>The criterion's own {@code lastVerified*} fields are a cache of the head of this journal, kept
 * so the current state can be read without a scan. This journal is the truth: it answers "when did
 * this start passing, and when did it regress?" — a question a single mutable status field cannot,
 * because each write destroys the previous answer.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class CriterionVerification {
    private UUID id;
    private UUID criterionId;
    private UUID requirementId;
    private UUID runId;
    private UUID storyId;
    private UUID taskId;
    private String commitSha;
    private VerificationResult result;
    private String testRef;         // test id / assertion name / probe
    private Instant at;

    public CriterionVerification() {}

    public CriterionVerification(UUID id, UUID criterionId, UUID requirementId, UUID runId,
                                 UUID storyId, UUID taskId, String commitSha,
                                 VerificationResult result, String testRef, Instant at) {
        this.id = id;
        this.criterionId = criterionId;
        this.requirementId = requirementId;
        this.runId = runId;
        this.storyId = storyId;
        this.taskId = taskId;
        this.commitSha = commitSha;
        this.result = result;
        this.testRef = testRef;
        this.at = at;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID criterionId() { return criterionId; }
    public UUID getCriterionId() { return criterionId; }
    public void setCriterionId(UUID criterionId) { this.criterionId = criterionId; }
    public UUID requirementId() { return requirementId; }
    public UUID getRequirementId() { return requirementId; }
    public void setRequirementId(UUID requirementId) { this.requirementId = requirementId; }
    public UUID runId() { return runId; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public UUID storyId() { return storyId; }
    public UUID getStoryId() { return storyId; }
    public void setStoryId(UUID storyId) { this.storyId = storyId; }
    public UUID taskId() { return taskId; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public String commitSha() { return commitSha; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public VerificationResult result() { return result; }
    public VerificationResult getResult() { return result; }
    public void setResult(VerificationResult result) { this.result = result; }
    public String testRef() { return testRef; }
    public String getTestRef() { return testRef; }
    public void setTestRef(String testRef) { this.testRef = testRef; }
    public Instant at() { return at; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CriterionVerification that = (CriterionVerification) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.criterionId, that.criterionId)
            && Objects.equals(this.requirementId, that.requirementId)
            && Objects.equals(this.runId, that.runId) && Objects.equals(this.storyId, that.storyId)
            && Objects.equals(this.taskId, that.taskId)
            && Objects.equals(this.commitSha, that.commitSha)
            && Objects.equals(this.result, that.result) && Objects.equals(this.testRef, that.testRef)
            && Objects.equals(this.at, that.at);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, criterionId, requirementId, runId, storyId, taskId, commitSha,
            result, testRef, at);
    }
}
