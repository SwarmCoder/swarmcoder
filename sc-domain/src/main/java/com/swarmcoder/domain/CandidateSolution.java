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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
@JsonTypeName("CandidateSolution")
public class CandidateSolution {
    private UUID id;
    private UUID taskId;
    private int workerIndex;
    private String branch;
    private SamplingConfig sampling;
    private String diffUnified;
    private VerificationReport verification;
    private ClusterId cluster;
    private JudgeScore judge;
    private CandidateState state;
    private KillReason killReason;
    /**
     * Files this attempt changed outside the task's declared write set. Empty for almost every
     * candidate; never null after {@link #setOutOfWriteSetPaths}.
     *
     * <p>Recorded rather than prevented since 2026-09-02. The write set is the architect's guess,
     * made from a plan before any code existed, and on a multi-module project a task genuinely
     * spans more than one slice of it. Killing the worker for that destroyed the work AND the
     * evidence; this field is the evidence, and it travels to the judge, to selection and to the
     * operator's run graph so the call is made where the diff can actually be seen.
     */
    private List<String> outOfWriteSetPaths = List.of();
    /**
     * When this attempt was stopped as {@link KillReason#DOCS_DEAD_END}, every {@code lookup_api}
     * question it asked and the top documentation section each one returned, in order, formatted
     * as {@code "1. \"question\" → section"}. Empty for every other candidate — this is not a
     * verdict on the worker, it is the evidence that the documentation SEARCH is what failed, kept
     * on the candidate so it survives after the run's live session is gone. Never null.
     */
    private List<String> docsDeadEndEvidence = List.of();
    /**
     * What this candidate asked the expert for, one line each: the question, where the answer came
     * from, what it cost.
     *
     * <p>Kept on the candidate because a worker's toolbox goes away when the run ends, and because
     * the judge is told this and told what to make of it: <b>asking was right.</b> A worker that
     * asked twice and delivered is a better outcome than one that guessed and delivered, and far
     * better than one that spent twenty turns disassembling a jar. Nothing here is a defect.
     */
    private List<String> helpCalls = List.of();
    /**
     * One line for the run graph's card: {@code "asked the expert 2x (7 lookups)"}, or empty.
     *
     * <p>The lookup count is the point of it. The expert is an agent session with read-only tools
     * over this codebase now, so an answer either was looked up in real files or was not, and that
     * is the single most useful thing an operator glancing at a card can know about it. Derived
     * once, where the answers themselves still exist, rather than parsed back out of
     * {@link #helpCalls} by whichever screen wants it.
     */
    private String helpSummary = "";
    /**
     * The stated rules this attempt's worker disputed with evidence ({@code dispute_rule}), in the
     * order it raised them. Empty for almost every candidate; never null.
     *
     * <p>Kept on the candidate for the same reason {@link #helpCalls} is: the toolbox goes away
     * when the worker ends, and the judge has to read the dispute next to the code it explains
     * (harness runs 53 and 55, 2026-10-01 — see {@link RuleDispute}).
     */
    private List<RuleDispute> ruleDisputes = List.of();

    public CandidateSolution() {}

    public CandidateSolution(UUID id, UUID taskId, int workerIndex, String branch, SamplingConfig sampling, String diffUnified, VerificationReport verification, ClusterId cluster, JudgeScore judge, CandidateState state, KillReason killReason) {
        this.id = id;
        this.taskId = taskId;
        this.workerIndex = workerIndex;
        this.branch = branch;
        this.sampling = sampling;
        this.diffUnified = diffUnified;
        this.verification = verification;
        this.cluster = cluster;
        this.judge = judge;
        this.state = state;
        this.killReason = killReason;
    }

    /**
     * Copies the facts about how the WORKER ran onto a candidate rebuilt by a later stage.
     *
     * <p>Clustering, verification, judging and selection each rebuild the candidate through the
     * constructor above. Anything not in that argument list is silently dropped by every one of
     * them, which is why this exists as one call rather than an eleventh and twelfth argument
     * nobody remembers to pass. {@code SwarmEngineWriteSetRecordingTest} fails if a stage forgets.
     */
    public CandidateSolution carryingAuditFrom(CandidateSolution source) {
        if (source != null) {
            this.outOfWriteSetPaths = source.outOfWriteSetPaths();
            this.docsDeadEndEvidence = source.docsDeadEndEvidence();
            this.helpCalls = source.helpCalls();
            this.helpSummary = source.helpSummary();
            this.ruleDisputes = source.ruleDisputes();
        }
        return this;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID taskId() { return taskId; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public int workerIndex() { return workerIndex; }
    public int getWorkerIndex() { return workerIndex; }
    public void setWorkerIndex(int workerIndex) { this.workerIndex = workerIndex; }
    public String branch() { return branch; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public SamplingConfig sampling() { return sampling; }
    public SamplingConfig getSampling() { return sampling; }
    public void setSampling(SamplingConfig sampling) { this.sampling = sampling; }
    public String diffUnified() { return diffUnified; }
    public String getDiffUnified() { return diffUnified; }
    public void setDiffUnified(String diffUnified) { this.diffUnified = diffUnified; }
    public VerificationReport verification() { return verification; }
    public VerificationReport getVerification() { return verification; }
    public void setVerification(VerificationReport verification) { this.verification = verification; }
    public ClusterId cluster() { return cluster; }
    public ClusterId getCluster() { return cluster; }
    public void setCluster(ClusterId cluster) { this.cluster = cluster; }
    public JudgeScore judge() { return judge; }
    public JudgeScore getJudge() { return judge; }
    public void setJudge(JudgeScore judge) { this.judge = judge; }
    public CandidateState state() { return state; }
    public CandidateState getState() { return state; }
    public void setState(CandidateState state) { this.state = state; }
    public List<String> outOfWriteSetPaths() {
        return outOfWriteSetPaths == null ? List.of() : outOfWriteSetPaths;
    }
    public List<String> getOutOfWriteSetPaths() { return outOfWriteSetPaths(); }
    public void setOutOfWriteSetPaths(List<String> outOfWriteSetPaths) {
        this.outOfWriteSetPaths = outOfWriteSetPaths == null ? List.of() : List.copyOf(outOfWriteSetPaths);
    }
    public List<String> helpCalls() {
        return helpCalls == null ? List.of() : helpCalls;
    }
    public List<String> getHelpCalls() { return helpCalls(); }
    public void setHelpCalls(List<String> helpCalls) {
        this.helpCalls = helpCalls == null ? List.of() : List.copyOf(helpCalls);
    }
    public String helpSummary() { return helpSummary == null ? "" : helpSummary; }
    public String getHelpSummary() { return helpSummary(); }
    public void setHelpSummary(String helpSummary) {
        this.helpSummary = helpSummary == null ? "" : helpSummary;
    }

    public List<RuleDispute> ruleDisputes() {
        return ruleDisputes == null ? List.of() : ruleDisputes;
    }
    public List<RuleDispute> getRuleDisputes() { return ruleDisputes(); }
    public void setRuleDisputes(List<RuleDispute> ruleDisputes) {
        this.ruleDisputes = ruleDisputes == null ? List.of() : List.copyOf(ruleDisputes);
    }

    public List<String> docsDeadEndEvidence() {
        return docsDeadEndEvidence == null ? List.of() : docsDeadEndEvidence;
    }
    public List<String> getDocsDeadEndEvidence() { return docsDeadEndEvidence(); }
    public void setDocsDeadEndEvidence(List<String> docsDeadEndEvidence) {
        this.docsDeadEndEvidence = docsDeadEndEvidence == null ? List.of() : List.copyOf(docsDeadEndEvidence);
    }
    public KillReason killReason() { return killReason; }
    public KillReason getKillReason() { return killReason; }
    public void setKillReason(KillReason killReason) { this.killReason = killReason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CandidateSolution that = (CandidateSolution) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.taskId, that.taskId) && this.workerIndex == that.workerIndex && Objects.equals(this.branch, that.branch) && Objects.equals(this.sampling, that.sampling) && Objects.equals(this.diffUnified, that.diffUnified) && Objects.equals(this.verification, that.verification) && Objects.equals(this.cluster, that.cluster) && Objects.equals(this.judge, that.judge) && Objects.equals(this.state, that.state) && Objects.equals(this.killReason, that.killReason) && Objects.equals(this.outOfWriteSetPaths(), that.outOfWriteSetPaths()) && Objects.equals(this.docsDeadEndEvidence(), that.docsDeadEndEvidence());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, taskId, workerIndex, branch, sampling, diffUnified, verification, cluster, judge, state, killReason, outOfWriteSetPaths(), docsDeadEndEvidence());
    }
}

