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
 * One role's model endpoint AND that model's own behaviour, in a settings form (design §7):
 * global roles, worker families (roleId {@code worker0..n}), and per-project overrides (blank
 * fields = inherit).
 *
 * <p>Everything from {@code shape} down was a hardcoded constant or a JVM-wide switch until
 * 2026-08. They are per-model settings — a chat-template argument name, an in-prompt directive,
 * whether the chat template survives native tool-call history, an HTTP version, two context sizes
 * and two scheduler figures — so two models can no longer be forced to agree on them.
 *
 * <p><b>Tri-state ints</b> ({@code thinking}, {@code textualToolHistory},
 * {@code jsonResponseFormat}, {@code verified}): -1 inherit from the shape, 0 off/no, 1 on/yes.
 * <b>Plain ints</b> ({@code maxOutputTokens} and below): 0 means inherit from the shape.
 * <b>Strings</b>: blank inherits; the literal {@code none} means this model has no such mechanism
 * and nothing should be sent.
 */
@DataModel
public class RoleEntryDto implements BinaryPackable {

    private String roleId;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private int thinking = -1;

    /** Named starting profile for this model, e.g. {@code qwen38-flash-next-125b}. */
    private String shape;
    private int maxOutputTokens;
    /** Chat-template argument that turns reasoning off (Qwen: {@code enable_thinking}). */
    private String thinkingKwarg;
    /** Text appended to the system prompt to turn reasoning off (Qwen: {@code /no_think}). */
    private String noThinkDirective;
    private int textualToolHistory = -1;
    private int jsonResponseFormat = -1;
    /** {@code 1.1} or {@code 2}; blank inherits. */
    private String httpVersion;
    private int servedContextTokens;
    private int workingContextTokens;
    private int kvBytesPerToken;
    private int maxConcurrentSequences;
    /** 1 once the numbers were measured against this model on this box; 0 = explicitly unverified. */
    private int verified = -1;
    /** Server → UI only: the shape ids this build offers, comma separated. Never read back. */
    private String availableShapes;

    public RoleEntryDto() { }

    public String getRoleId() { return roleId; }
    public void setRoleId(String roleId) { this.roleId = roleId; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public int getThinking() { return thinking; }
    public void setThinking(int thinking) { this.thinking = thinking; }

    public String getShape() { return shape; }
    public void setShape(String shape) { this.shape = shape; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public String getThinkingKwarg() { return thinkingKwarg; }
    public void setThinkingKwarg(String thinkingKwarg) { this.thinkingKwarg = thinkingKwarg; }
    public String getNoThinkDirective() { return noThinkDirective; }
    public void setNoThinkDirective(String noThinkDirective) { this.noThinkDirective = noThinkDirective; }
    public int getTextualToolHistory() { return textualToolHistory; }
    public void setTextualToolHistory(int textualToolHistory) { this.textualToolHistory = textualToolHistory; }
    public int getJsonResponseFormat() { return jsonResponseFormat; }
    public void setJsonResponseFormat(int jsonResponseFormat) { this.jsonResponseFormat = jsonResponseFormat; }
    public String getHttpVersion() { return httpVersion; }
    public void setHttpVersion(String httpVersion) { this.httpVersion = httpVersion; }
    public int getServedContextTokens() { return servedContextTokens; }
    public void setServedContextTokens(int servedContextTokens) { this.servedContextTokens = servedContextTokens; }
    public int getWorkingContextTokens() { return workingContextTokens; }
    public void setWorkingContextTokens(int workingContextTokens) { this.workingContextTokens = workingContextTokens; }
    public int getKvBytesPerToken() { return kvBytesPerToken; }
    public void setKvBytesPerToken(int kvBytesPerToken) { this.kvBytesPerToken = kvBytesPerToken; }
    public int getMaxConcurrentSequences() { return maxConcurrentSequences; }
    public void setMaxConcurrentSequences(int maxConcurrentSequences) { this.maxConcurrentSequences = maxConcurrentSequences; }
    public int getVerified() { return verified; }
    public void setVerified(int verified) { this.verified = verified; }
    public String getAvailableShapes() { return availableShapes; }
    public void setAvailableShapes(String availableShapes) { this.availableShapes = availableShapes; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, roleId);
        BinarySerializer.writeString(buffer, baseUrl);
        BinarySerializer.writeString(buffer, apiKey);
        BinarySerializer.writeString(buffer, modelName);
        BinarySerializer.writeValue(buffer, thinking, mapper);
        BinarySerializer.writeString(buffer, shape);
        BinarySerializer.writeValue(buffer, maxOutputTokens, mapper);
        BinarySerializer.writeString(buffer, thinkingKwarg);
        BinarySerializer.writeString(buffer, noThinkDirective);
        BinarySerializer.writeValue(buffer, textualToolHistory, mapper);
        BinarySerializer.writeValue(buffer, jsonResponseFormat, mapper);
        BinarySerializer.writeString(buffer, httpVersion);
        BinarySerializer.writeValue(buffer, servedContextTokens, mapper);
        BinarySerializer.writeValue(buffer, workingContextTokens, mapper);
        BinarySerializer.writeValue(buffer, kvBytesPerToken, mapper);
        BinarySerializer.writeValue(buffer, maxConcurrentSequences, mapper);
        BinarySerializer.writeValue(buffer, verified, mapper);
        BinarySerializer.writeString(buffer, availableShapes);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.roleId = BinarySerializer.readString(buffer);
        this.baseUrl = BinarySerializer.readString(buffer);
        this.apiKey = BinarySerializer.readString(buffer);
        this.modelName = BinarySerializer.readString(buffer);
        this.thinking = SessionSummaryDto.readInt(buffer, mapper);
        this.shape = BinarySerializer.readString(buffer);
        this.maxOutputTokens = SessionSummaryDto.readInt(buffer, mapper);
        this.thinkingKwarg = BinarySerializer.readString(buffer);
        this.noThinkDirective = BinarySerializer.readString(buffer);
        this.textualToolHistory = SessionSummaryDto.readInt(buffer, mapper);
        this.jsonResponseFormat = SessionSummaryDto.readInt(buffer, mapper);
        this.httpVersion = BinarySerializer.readString(buffer);
        this.servedContextTokens = SessionSummaryDto.readInt(buffer, mapper);
        this.workingContextTokens = SessionSummaryDto.readInt(buffer, mapper);
        this.kvBytesPerToken = SessionSummaryDto.readInt(buffer, mapper);
        this.maxConcurrentSequences = SessionSummaryDto.readInt(buffer, mapper);
        this.verified = SessionSummaryDto.readInt(buffer, mapper);
        this.availableShapes = BinarySerializer.readString(buffer);
    }
}
