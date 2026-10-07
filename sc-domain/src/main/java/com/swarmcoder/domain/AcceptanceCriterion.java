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
 * One executable clause of a requirement's definition: a statement bound to a test
 * ({@link #testClassOrFile}) that decides it. For a functional {@link BrdRequirement} these are
 * acceptance criteria; for a non-functional one they are <em>fitness</em> criteria — the same
 * object checked by the same harness, which is why the NFR gate is the ordinary mechanism rather
 * than a special case.
 *
 * <p>Author decision 2026-07-25: criteria belong to the REQUIREMENT, not to the story or the task.
 * A story is defined by <em>selecting</em> criteria, so there is no second wording to drift, and
 * the criteria outlive the work that delivered them. Criteria that hang off a work item are
 * archived with it, leaving the requirement with no executable definition — exactly how
 * specifications rot. {@link Task} still carries its own list for ENABLER work that answers to no
 * requirement.
 *
 * <p>{@link #verification} and the {@code lastVerified*} fields are a CACHE OF THE HEAD for cheap
 * rendering. The truth is the append-only {@link CriterionVerification} journal, which can answer
 * "when did this start passing, and when did it regress?" — a single mutable status field cannot.
 *
 * <p>The new fields are added via setters rather than the constructor, so existing callers and
 * pre-v7 stores are unaffected (they load as null — always read through the null-safe accessors).
 */
@DataModel
public class AcceptanceCriterion {
    private UUID id;
    private String text;
    private String testClassOrFile;
    /**
     * Who wrote {@link #testClassOrFile}. Null reads as {@link TestRefOrigin#OPERATOR}: every
     * reference written before the requirements wizard could propose one was typed by a person.
     */
    private TestRefOrigin testRefOrigin;
    /** PROPOSED (agent-suggested, advisory) → ACCEPTED (a real gate) → RETIRED. */
    private CriterionStatus status;
    /** Cached head of the verification journal. */
    private CriterionState verification;
    private UUID lastVerifiedRunId;
    /** The commit that produced the cached verification result. */
    private String lastVerifiedCommit;
    private Instant lastVerifiedAt;
    /**
     * The requirement's {@link BrdRequirement#contentRevision} at the time of that verification.
     * If the requirement's text has moved on since, the criterion reads STALE rather than green.
     */
    private long verifiedAgainstContentRevision;

    public AcceptanceCriterion() {}

    public AcceptanceCriterion(UUID id, String text, String testClassOrFile) {
        this.id = id;
        this.text = text;
        this.testClassOrFile = testClassOrFile;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String text() { return text; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String testClassOrFile() { return testClassOrFile; }
    public String getTestClassOrFile() { return testClassOrFile; }
    public void setTestClassOrFile(String testClassOrFile) { this.testClassOrFile = testClassOrFile; }

    /**
     * Null-safe: an unrecorded origin is {@link TestRefOrigin#OPERATOR}. Everything written before
     * the wizard could propose a reference was typed by a person, and reading those as proposals
     * would tell the operator to re-check work they had already done.
     */
    public TestRefOrigin testRefOrigin() {
        return testRefOrigin == null ? TestRefOrigin.OPERATOR : testRefOrigin;
    }
    public TestRefOrigin getTestRefOrigin() { return testRefOrigin; }
    public void setTestRefOrigin(TestRefOrigin testRefOrigin) { this.testRefOrigin = testRefOrigin; }

    /** True when the test reference is a wizard's suggestion nobody has confirmed yet. */
    public boolean testRefIsProposal() {
        return testClassOrFile != null && !testClassOrFile.isBlank()
            && testRefOrigin() == TestRefOrigin.PROPOSED;
    }

    /**
     * Null-safe. Criteria written before v7 were authored by the Architect and used as real gates,
     * so a missing status reads as ACCEPTED rather than demoting them to advisory.
     */
    public CriterionStatus status() { return status == null ? CriterionStatus.ACCEPTED : status; }
    public CriterionStatus getStatus() { return status; }
    public void setStatus(CriterionStatus status) { this.status = status; }
    /** Null-safe: never verified reads as UNVERIFIED. */
    public CriterionState verification() { return verification == null ? CriterionState.UNVERIFIED : verification; }
    public CriterionState getVerification() { return verification; }
    public void setVerification(CriterionState verification) { this.verification = verification; }
    public UUID lastVerifiedRunId() { return lastVerifiedRunId; }
    public UUID getLastVerifiedRunId() { return lastVerifiedRunId; }
    public void setLastVerifiedRunId(UUID lastVerifiedRunId) { this.lastVerifiedRunId = lastVerifiedRunId; }
    public String lastVerifiedCommit() { return lastVerifiedCommit; }
    public String getLastVerifiedCommit() { return lastVerifiedCommit; }
    public void setLastVerifiedCommit(String lastVerifiedCommit) { this.lastVerifiedCommit = lastVerifiedCommit; }
    public Instant lastVerifiedAt() { return lastVerifiedAt; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(Instant lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }
    public long verifiedAgainstContentRevision() { return verifiedAgainstContentRevision; }
    public long getVerifiedAgainstContentRevision() { return verifiedAgainstContentRevision; }
    public void setVerifiedAgainstContentRevision(long verifiedAgainstContentRevision) {
        this.verifiedAgainstContentRevision = verifiedAgainstContentRevision;
    }

    /** True when this criterion is an enforced gate rather than an advisory proposal. */
    public boolean isGate() { return status() == CriterionStatus.ACCEPTED; }

    /**
     * The state to render for a criterion of a requirement now at {@code contentRevision}: a pass
     * recorded against older wording is STALE, not green.
     */
    public CriterionState effectiveState(long contentRevision) {
        CriterionState current = verification();
        if (current == CriterionState.PASSING && verifiedAgainstContentRevision < contentRevision) {
            return CriterionState.STALE;
        }
        return current;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AcceptanceCriterion that = (AcceptanceCriterion) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.text, that.text)
            && Objects.equals(this.testClassOrFile, that.testClassOrFile)
            && this.testRefOrigin() == that.testRefOrigin()
            && Objects.equals(this.status, that.status) && Objects.equals(this.verification, that.verification)
            && Objects.equals(this.lastVerifiedRunId, that.lastVerifiedRunId)
            && Objects.equals(this.lastVerifiedCommit, that.lastVerifiedCommit)
            && Objects.equals(this.lastVerifiedAt, that.lastVerifiedAt)
            && this.verifiedAgainstContentRevision == that.verifiedAgainstContentRevision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, text, testClassOrFile, testRefOrigin(), status, verification,
            lastVerifiedRunId, lastVerifiedCommit, lastVerifiedAt, verifiedAgainstContentRevision);
    }
}
