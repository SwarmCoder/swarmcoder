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

import java.time.Instant;
import java.util.Objects;

/**
 * One step of an agent session, exactly as it happened — the unit of both the real-time
 * observer feed and post-analysis. Payloads are inlined up to a cap; larger content lives
 * in the blob store behind {@code payloadRef}.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore, whose reflective
 * serializer does not handle records — see {@link VerificationReport}.
 *
 * <p>Fields: {@code seq} monotonically increasing within the session; {@code at} wall-clock
 * timestamp; {@code kind} what happened; {@code label} short discriminator (tool name, kill
 * reason, model id — kind-dependent); {@code payload} inline content (possibly truncated, see
 * {@code payloadRef}); {@code payloadRef} blob-store hash of the FULL content when payload was
 * truncated (null otherwise); {@code tokensUsed} cumulative session token usage as of this event
 * (0 when unknown).
 */
public class TraceEvent {
    private long seq;
    private Instant at;
    private TraceEventKind kind;
    private String label;
    private String payload;
    private String payloadRef;
    private long tokensUsed;

    public TraceEvent() {}

    public TraceEvent(long seq, Instant at, TraceEventKind kind, String label, String payload, String payloadRef, long tokensUsed) {
        this.seq = seq;
        this.at = at;
        this.kind = kind;
        this.label = label;
        this.payload = payload;
        this.payloadRef = payloadRef;
        this.tokensUsed = tokensUsed;
    }

    public long seq() { return seq; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public Instant at() { return at; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public TraceEventKind kind() { return kind; }
    public TraceEventKind getKind() { return kind; }
    public void setKind(TraceEventKind kind) { this.kind = kind; }
    public String label() { return label; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String payload() { return payload; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String payloadRef() { return payloadRef; }
    public String getPayloadRef() { return payloadRef; }
    public void setPayloadRef(String payloadRef) { this.payloadRef = payloadRef; }
    public long tokensUsed() { return tokensUsed; }
    public long getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(long tokensUsed) { this.tokensUsed = tokensUsed; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TraceEvent that = (TraceEvent) o;
        return this.seq == that.seq && this.tokensUsed == that.tokensUsed && Objects.equals(this.at, that.at) && this.kind == that.kind && Objects.equals(this.label, that.label) && Objects.equals(this.payload, that.payload) && Objects.equals(this.payloadRef, that.payloadRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seq, at, kind, label, payload, payloadRef, tokensUsed);
    }
}
