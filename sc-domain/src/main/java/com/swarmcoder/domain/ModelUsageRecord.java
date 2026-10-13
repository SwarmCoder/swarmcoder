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

/**
 * What the model calls of one role cost in one run, as the model server itself counted them: one
 * row per role, task, model and class of server (stored in {@link RunUsageRecord}).
 *
 * <p>Every model call adds to exactly one row - a call of an agent session as much as a single
 * question - so a row's {@link #calls()} is the number of requests sent, and its token counts are
 * the sums of the {@code usage} the server returned with each. Input and output are kept apart;
 * they are never added into one figure here.
 *
 * <p>A call whose server reported no count is still a call: it is in {@link #calls()} and not in
 * {@link #inputCalls()} or {@link #outputCalls()}, so a reader can say "not recorded" instead of
 * showing a sum that looks complete.
 *
 * <p>A mutable POJO because EclipseStore cannot persist records. Only fields may be added.
 */
public class ModelUsageRecord {

    /** A server that is paid for by the token. */
    public static final String CLOUD = "CLOUD";
    /** The operator's own model server: its tokens cost nothing. */
    public static final String LOCAL = "LOCAL";

    private String role;
    /** The task the calls were made for, as text; null for a role that works for the whole run. */
    private String task;
    private String model;
    /** {@link #CLOUD} or {@link #LOCAL}, as text so further classes can be added. */
    private String endpointClass;
    private int calls;
    /** Calls that ended without an answer (timed out, refused, broken off). */
    private int failedCalls;
    /** Calls whose server reported how many tokens it was sent. */
    private int inputCalls;
    private long inputTokens;
    /** Calls whose server reported how many tokens it wrote. */
    private int outputCalls;
    private long outputTokens;
    /** Calls whose server reported how much of the input it took from its prompt cache. */
    private int cachedCalls;
    private long cachedInputTokens;
    /** Calls with a recorded end. */
    private int finishedCalls;
    private long millisInCalls;
    /** Calls counted from the size of the text because the server reported no usage. */
    private int estimatedCalls;
    /** The most input tokens one call of this row was sent. */
    private long largestInput;
    /**
     * Calls of which both are known: the size of the request as it went out, and the input
     * tokens the server counted for it. Their sums follow (section 94): a server that counts
     * more than the request holds shows as few characters per token.
     */
    private int sizedCalls;
    private long requestChars;
    private long sizedInputTokens;
    /** Calls that followed another of the same session, and what their requests shared with it. */
    private int repeatCalls;
    private long repeatRequestChars;
    private long samePrefixChars;
    /**
     * Calls of which it is known how much of the answer was thinking, and the sum. Thinking is
     * part of {@link #outputTokens}, never added to it. Added with section 102; a row stored
     * before it reads 0 for both, which a reader shows as "not recorded".
     */
    private int thinkingCalls;
    private long thinkingTokens;
    /** Of {@link #thinkingCalls}, those whose thinking was estimated from its length. */
    private int estimatedThinkingCalls;

    public ModelUsageRecord() {}

    public ModelUsageRecord(String role, String task, String model, String endpointClass) {
        this.role = role;
        this.task = task;
        this.model = model;
        this.endpointClass = endpointClass;
    }

    /**
     * Adds one call.
     *
     * @param input     tokens the server says it was sent, or negative when it said nothing
     * @param output    tokens the server says it wrote, or negative when it said nothing
     * @param cached    how many of {@code input} came from its prompt cache, or negative
     * @param millis    how long the call took, or negative when its end was not recorded
     * @param failed    the call ended without an answer
     * @param estimated {@code input} and {@code output} are estimates from the size of the text
     */
    public void addCall(long input, long output, long cached, long millis, boolean failed,
                        boolean estimated) {
        calls++;
        if (failed) {
            failedCalls++;
        }
        if (input >= 0) {
            inputCalls++;
            inputTokens += input;
            largestInput = Math.max(largestInput, input);
        }
        if (output >= 0) {
            outputCalls++;
            outputTokens += output;
        }
        if (cached >= 0 && input >= 0) {
            cachedCalls++;
            cachedInputTokens += cached;
        }
        if (millis >= 0) {
            finishedCalls++;
            millisInCalls += millis;
        }
        if (estimated) {
            estimatedCalls++;
        }
    }

    /**
     * Adds what is known of one call's request, for a call {@link #addCall} has counted.
     *
     * @param input           tokens the server says it was sent, or negative
     * @param chars           the size of the request body as it went out, or not positive when
     *                        it was not seen
     * @param samePrefixChars how many leading characters of that body were the same as the
     *                        session's request before it, or negative for a first request
     */
    public void addRequest(long input, long chars, long samePrefixChars) {
        if (chars <= 0) {
            return;
        }
        if (input >= 0) {
            sizedCalls++;
            requestChars += chars;
            sizedInputTokens += input;
        }
        if (samePrefixChars >= 0) {
            repeatCalls++;
            repeatRequestChars += chars;
            this.samePrefixChars += Math.min(chars, samePrefixChars);
        }
    }

    /**
     * Of a call already added, this many of its output tokens were thinking; negative: unknown.
     *
     * @param estimated the figure is the length of the thinking text / 4, because the server
     *                  gave no count of it
     */
    public void addThinking(long thinking, boolean estimated) {
        if (thinking >= 0) {
            thinkingCalls++;
            thinkingTokens += thinking;
            if (estimated) {
                estimatedThinkingCalls++;
            }
        }
    }

    /** Adds every count of {@code other} to this row. */
    public void add(ModelUsageRecord other) {
        calls += other.calls;
        failedCalls += other.failedCalls;
        inputCalls += other.inputCalls;
        inputTokens += other.inputTokens;
        outputCalls += other.outputCalls;
        outputTokens += other.outputTokens;
        cachedCalls += other.cachedCalls;
        cachedInputTokens += other.cachedInputTokens;
        finishedCalls += other.finishedCalls;
        millisInCalls += other.millisInCalls;
        estimatedCalls += other.estimatedCalls;
        largestInput = Math.max(largestInput, other.largestInput);
        sizedCalls += other.sizedCalls;
        requestChars += other.requestChars;
        sizedInputTokens += other.sizedInputTokens;
        repeatCalls += other.repeatCalls;
        repeatRequestChars += other.repeatRequestChars;
        samePrefixChars += other.samePrefixChars;
        thinkingCalls += other.thinkingCalls;
        thinkingTokens += other.thinkingTokens;
        estimatedThinkingCalls += other.estimatedThinkingCalls;
    }

    /** A new row with the same key and the same counts. */
    public ModelUsageRecord copy() {
        ModelUsageRecord copy = new ModelUsageRecord(role, task, model, endpointClass);
        copy.add(this);
        return copy;
    }

    /** True unless the calls went to the operator's own server. */
    public boolean paid() {
        return !LOCAL.equals(endpointClass);
    }

    public String role() { return role; }
    public String task() { return task; }
    public String model() { return model; }
    public String endpointClass() { return endpointClass; }
    public int calls() { return calls; }
    public int failedCalls() { return failedCalls; }
    public int inputCalls() { return inputCalls; }
    public long inputTokens() { return inputTokens; }
    public int outputCalls() { return outputCalls; }
    public long outputTokens() { return outputTokens; }
    public int cachedCalls() { return cachedCalls; }
    public long cachedInputTokens() { return cachedInputTokens; }
    public int finishedCalls() { return finishedCalls; }
    public long millisInCalls() { return millisInCalls; }
    public int estimatedCalls() { return estimatedCalls; }
    public long largestInput() { return largestInput; }
    public int sizedCalls() { return sizedCalls; }
    public long requestChars() { return requestChars; }
    public long sizedInputTokens() { return sizedInputTokens; }
    public int repeatCalls() { return repeatCalls; }
    public long repeatRequestChars() { return repeatRequestChars; }
    public long samePrefixChars() { return samePrefixChars; }
    public int thinkingCalls() { return thinkingCalls; }
    public long thinkingTokens() { return thinkingTokens; }
    public int estimatedThinkingCalls() { return estimatedThinkingCalls; }
}
