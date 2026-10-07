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

/** One live/persisted session step. Payload is a snippet; full content is fetched on demand (O2). */
@DataModel
public class TraceEventDto implements BinaryPackable {

    private String sessionId;
    private long seq;
    private long atMillis;
    private String kind;
    private String label;
    private String payloadSnippet;
    private String payloadRef; // blob hash of the FULL payload when truncated; "" otherwise
    private long tokens;

    public TraceEventDto() { }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public long getAtMillis() { return atMillis; }
    public void setAtMillis(long atMillis) { this.atMillis = atMillis; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getPayloadSnippet() { return payloadSnippet; }
    public void setPayloadSnippet(String payloadSnippet) { this.payloadSnippet = payloadSnippet; }
    public String getPayloadRef() { return payloadRef; }
    public void setPayloadRef(String payloadRef) { this.payloadRef = payloadRef; }
    public long getTokens() { return tokens; }
    public void setTokens(long tokens) { this.tokens = tokens; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, sessionId);
        BinarySerializer.writeValue(buffer, seq, mapper);
        BinarySerializer.writeValue(buffer, atMillis, mapper);
        BinarySerializer.writeString(buffer, kind);
        BinarySerializer.writeString(buffer, label);
        BinarySerializer.writeString(buffer, payloadSnippet);
        BinarySerializer.writeString(buffer, payloadRef);
        BinarySerializer.writeValue(buffer, tokens, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.sessionId = BinarySerializer.readString(buffer);
        this.seq = SessionSummaryDto.readLong(buffer, mapper);
        this.atMillis = SessionSummaryDto.readLong(buffer, mapper);
        this.kind = BinarySerializer.readString(buffer);
        this.label = BinarySerializer.readString(buffer);
        this.payloadSnippet = BinarySerializer.readString(buffer);
        this.payloadRef = BinarySerializer.readString(buffer);
        this.tokens = SessionSummaryDto.readLong(buffer, mapper);
    }
}


