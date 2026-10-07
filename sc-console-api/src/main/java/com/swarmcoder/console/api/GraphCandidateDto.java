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
import com.zeroz4j.api.BinaryPackable;
import com.zeroz4j.api.BinarySerializer;

import java.nio.ByteBuffer;

/**
 * One candidate node in the run graph (design §6.2): a live worker session while the swarm
 * runs, the archived CandidateSolution afterwards — merged by taskId+workerIndex.
 */
@DataModel
public class GraphCandidateDto implements BinaryPackable {

    private String candidateId;  // archive id, or the session id while live
    private String sessionId;    // "" when no session is known
    private String taskId;
    private int workerIndex;
    private String model;
    private double temperature;
    private String state;        // RUNNING | SURVIVED | FAILED | KILLED | SELECTED | SUPERSEDED
    private String killReason;   // "" when none
    private int turns;
    private long tokens;
    private double judgeScore;   // -1 when unjudged
    private String clusterHash;  // "" when unclustered
    private boolean verified;    // verification report present
    private boolean compiles;
    /**
     * When this worker's session opened, or 0 when it is not a live worker.
     *
     * <p>The four fields below are what tells a working swarm from a stuck one, and until now the
     * browser had none of them: a RUNNING chip said only that a worker existed, which is the same
     * picture whether it took a step a second ago or died an hour ago. The MCP {@code swarm_status}
     * tool has always reported exactly this — how long each worker has been open and the step it is
     * on — so these carry that same answer to the screen rather than deriving it again.
     */
    private long openedAtMillis;
    /** The kind of the last step this worker took (TOOL_CALL, LLM_RESPONSE, …); "" when none. */
    private String lastStepKind = "";
    /** What the last step was ON — the tool's name, the model's id; "" when none. */
    private String lastStepLabel = "";
    /** When the last step happened; 0 when the worker has not reported a step yet. */
    private long lastStepAtMillis;
    /**
     * The context this worker's model profile allows ONE session to occupy; 0 when not known.
     *
     * <p>It travels beside {@link #tokens} because a token count on its own says nothing an
     * operator can act on. 41k tokens is comfortable for a worker allowed 65k and is trouble for
     * one allowed 32k, and only the pair answers "should I be worried" — which is the whole
     * question the chip exists to answer. See {@code WorkerHealth}, which decides it once for the
     * chip, the words above the graph and the hover.
     */
    private long contextBudgetTokens;
    /**
     * How much of {@link #tokens} is the fixed head of the prompt — the part that never changes;
     * 0 when there is no measurement, which is every worker that has already stopped.
     *
     * <p>It is the third number the operator asked for, and it is the one that says where the
     * wall-clock time goes. The head is this worker's system prompt: the project's rules, the
     * design excerpt, the task, the repository map. It is byte-identical across the workers on one
     * task by construction, so the model server prefills it once and reuses it for all of them, and
     * nothing ever rewrites it, so it survives every compaction. Everything after it is this
     * worker's own, and each compaction makes the server read that part again from scratch.
     *
     * <p>So "8k of 41k" says eight thousand tokens were paid for once for the whole group and
     * thirty-three thousand belong to this worker alone. Estimated at four characters to the token,
     * and labelled as estimated wherever it is shown — there is no tokenizer on this side of the
     * wire and asking the server would be one request per worker per frame. See
     * {@code TraceHub.prefillTokens}.
     */
    private long prefillTokens;
    /**
     * Why this attempt died, in one sentence a person can act on; "" while it is alive or when it
     * ended well.
     *
     * <p>Derived on the server so there is ONE derivation of it. It is the sentence
     * {@code KillReason} carries for the way the worker was stopped, or - when verification is what
     * turned it down - the verdict the engine already recorded on the verification report. Neither
     * is re-worded here: a second wording of the same death is how two screens come to disagree
     * about it.
     *
     * <p>Until this existed a red chip was a red square and nothing else. The operator's words:
     * "if they fail they should turn red and still mouseover to see the reason why."
     */
    private String failReason = "";
    /**
     * Files this attempt changed outside its task's write set, comma-separated; "" when none.
     *
     * <p>It is on the chip's hover card because it is now a THING THAT HAPPENS rather than a thing
     * that kills. Until 2026-09-02 a worker that wrote outside its slice was terminated, so the
     * only way this could reach the operator was as a red square labelled WRITESET_VIOLATION with
     * the work destroyed and no way to see whether it had touched one file or fifty. The whole
     * point of recording instead of killing is that the difference is visible, and a candidate the
     * operator may be about to accept has to say so on the surface where they are looking.
     */
    private String outOfWriteSetPaths = "";
    /**
     * When this attempt was stopped as {@code DOCS_DEAD_END}, every {@code lookup_api} question it
     * asked and the top documentation section each one returned, in order, one per line
     * ({@code "1. \"question\" → section"}); "" for every other candidate.
     *
     * <p>This is on the chip's hover card and in the MCP diagnosis for the same reason
     * {@link #outOfWriteSetPaths} is: {@code DOCS_DEAD_END} is a finding about the documentation
     * SEARCH, not about the worker, and until this existed the only place that finding lived was a
     * WARN line in a log a JUnit temp store deletes when the run ends.
     */
    private String docsDeadEndEvidence = "";
    /**
     * {@code "asked the expert 2x (7 lookups)"} for an attempt that used the help desk, "" for one
     * that did not.
     *
     * <p>On the card because asking is the behaviour this system wants and an operator should be
     * able to see it happening — and because the lookup count says whether the answers were LOOKED
     * UP. The expert is an agent session with read-only tools over this codebase now, so an answer
     * either came out of files it read or out of a model's memory, and those are different things
     * to be handed a candidate on the strength of.
     */
    private String helpSummary = "";
    /**
     * True when the archived attempt's unified diff is blank: it ran to the end and changed no
     * code. Meaningful only on an archived attempt; a running worker has no diff yet.
     *
     * <p>Carried because "FAILED" alone does not say this. Until 2026-09-02 the MCP diagnosis
     * read every FAILED candidate as "its diff was empty", and four workers that had each written
     * their two files and then failed verification were reported as having written nothing. The
     * fact is on the candidate; this is it, and the state is not a stand-in for it.
     */
    private boolean diffEmpty;
    /**
     * How many files the diff adds or changes; 0 when it is blank. Deletions are not counted, so a
     * diff that only removes files reads as present ({@link #isDiffEmpty} false) with no changed
     * files.
     */
    private int changedFiles;

    public GraphCandidateDto() { }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public int getWorkerIndex() { return workerIndex; }
    public void setWorkerIndex(int workerIndex) { this.workerIndex = workerIndex; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getKillReason() { return killReason; }
    public void setKillReason(String killReason) { this.killReason = killReason; }
    public int getTurns() { return turns; }
    public void setTurns(int turns) { this.turns = turns; }
    public long getTokens() { return tokens; }
    public void setTokens(long tokens) { this.tokens = tokens; }
    public double getJudgeScore() { return judgeScore; }
    public void setJudgeScore(double judgeScore) { this.judgeScore = judgeScore; }
    public String getClusterHash() { return clusterHash; }
    public void setClusterHash(String clusterHash) { this.clusterHash = clusterHash; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public boolean isCompiles() { return compiles; }
    public void setCompiles(boolean compiles) { this.compiles = compiles; }
    public long getOpenedAtMillis() { return openedAtMillis; }
    public void setOpenedAtMillis(long openedAtMillis) { this.openedAtMillis = openedAtMillis; }
    public String getLastStepKind() { return lastStepKind; }
    public void setLastStepKind(String lastStepKind) { this.lastStepKind = lastStepKind; }
    public String getLastStepLabel() { return lastStepLabel; }
    public void setLastStepLabel(String lastStepLabel) { this.lastStepLabel = lastStepLabel; }
    public long getLastStepAtMillis() { return lastStepAtMillis; }
    public void setLastStepAtMillis(long lastStepAtMillis) { this.lastStepAtMillis = lastStepAtMillis; }
    public long getContextBudgetTokens() { return contextBudgetTokens; }
    public void setContextBudgetTokens(long contextBudgetTokens) { this.contextBudgetTokens = contextBudgetTokens; }
    public long getPrefillTokens() { return prefillTokens; }
    public void setPrefillTokens(long prefillTokens) { this.prefillTokens = prefillTokens; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getOutOfWriteSetPaths() { return outOfWriteSetPaths; }
    public void setOutOfWriteSetPaths(String outOfWriteSetPaths) {
        this.outOfWriteSetPaths = outOfWriteSetPaths == null ? "" : outOfWriteSetPaths;
    }
    public String getDocsDeadEndEvidence() { return docsDeadEndEvidence; }
    public void setDocsDeadEndEvidence(String docsDeadEndEvidence) {
        this.docsDeadEndEvidence = docsDeadEndEvidence == null ? "" : docsDeadEndEvidence;
    }
    public String getHelpSummary() { return helpSummary; }
    public void setHelpSummary(String helpSummary) {
        this.helpSummary = helpSummary == null ? "" : helpSummary;
    }
    public boolean isDiffEmpty() { return diffEmpty; }
    public void setDiffEmpty(boolean diffEmpty) { this.diffEmpty = diffEmpty; }
    public int getChangedFiles() { return changedFiles; }
    public void setChangedFiles(int changedFiles) { this.changedFiles = changedFiles; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, candidateId);
        BinarySerializer.writeString(buffer, sessionId);
        BinarySerializer.writeString(buffer, taskId);
        BinarySerializer.writeValue(buffer, workerIndex, mapper);
        BinarySerializer.writeString(buffer, model);
        BinarySerializer.writeValue(buffer, temperature, mapper);
        BinarySerializer.writeString(buffer, state);
        BinarySerializer.writeString(buffer, killReason);
        BinarySerializer.writeValue(buffer, turns, mapper);
        BinarySerializer.writeValue(buffer, tokens, mapper);
        BinarySerializer.writeValue(buffer, judgeScore, mapper);
        BinarySerializer.writeString(buffer, clusterHash);
        BinarySerializer.writeValue(buffer, verified ? 1 : 0, mapper);
        BinarySerializer.writeValue(buffer, compiles ? 1 : 0, mapper);
        BinarySerializer.writeValue(buffer, openedAtMillis, mapper);
        BinarySerializer.writeString(buffer, lastStepKind);
        BinarySerializer.writeString(buffer, lastStepLabel);
        BinarySerializer.writeValue(buffer, lastStepAtMillis, mapper);
        BinarySerializer.writeValue(buffer, contextBudgetTokens, mapper);
        BinarySerializer.writeValue(buffer, prefillTokens, mapper);
        BinarySerializer.writeString(buffer, failReason);
        BinarySerializer.writeString(buffer, outOfWriteSetPaths);
        BinarySerializer.writeValue(buffer, diffEmpty ? 1 : 0, mapper);
        BinarySerializer.writeValue(buffer, changedFiles, mapper);
        BinarySerializer.writeString(buffer, docsDeadEndEvidence);
        BinarySerializer.writeString(buffer, helpSummary);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.candidateId = BinarySerializer.readString(buffer);
        this.sessionId = BinarySerializer.readString(buffer);
        this.taskId = BinarySerializer.readString(buffer);
        this.workerIndex = SessionSummaryDto.readInt(buffer, mapper);
        this.model = BinarySerializer.readString(buffer);
        this.temperature = readDouble(buffer, mapper);
        this.state = BinarySerializer.readString(buffer);
        this.killReason = BinarySerializer.readString(buffer);
        this.turns = SessionSummaryDto.readInt(buffer, mapper);
        this.tokens = SessionSummaryDto.readLong(buffer, mapper);
        this.judgeScore = readDouble(buffer, mapper);
        this.clusterHash = BinarySerializer.readString(buffer);
        this.verified = SessionSummaryDto.readInt(buffer, mapper) != 0;
        this.compiles = SessionSummaryDto.readInt(buffer, mapper) != 0;
        this.openedAtMillis = SessionSummaryDto.readLong(buffer, mapper);
        this.lastStepKind = BinarySerializer.readString(buffer);
        this.lastStepLabel = BinarySerializer.readString(buffer);
        this.lastStepAtMillis = SessionSummaryDto.readLong(buffer, mapper);
        this.contextBudgetTokens = SessionSummaryDto.readLong(buffer, mapper);
        this.prefillTokens = SessionSummaryDto.readLong(buffer, mapper);
        this.failReason = BinarySerializer.readString(buffer);
        this.outOfWriteSetPaths = BinarySerializer.readString(buffer);
        this.diffEmpty = SessionSummaryDto.readInt(buffer, mapper) != 0;
        this.changedFiles = SessionSummaryDto.readInt(buffer, mapper);
        this.docsDeadEndEvidence = BinarySerializer.readString(buffer);
        this.helpSummary = BinarySerializer.readString(buffer);
    }

    static double readDouble(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        Object value = BinarySerializer.readValue(buffer, mapper);
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }
}


