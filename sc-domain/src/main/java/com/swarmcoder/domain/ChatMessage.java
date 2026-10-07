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
 * One message in a chat transcript (CONSOLE_DESIGN_V2.md §5.1). Role: USER | CODER |
 * SYSTEM. Kind: TEXT | RUN_EVENT | DECISION | ERROR — the client picks the rendering.
 * Persisted POJO — EclipseStore cannot handle records.
 */
@DataModel
public class ChatMessage {
    private UUID id;
    private UUID chatId;
    private int seq;
    private String role;
    private String kind;
    private String markdown;
    private UUID runId;      // nullable — RUN_EVENT / DECISION context
    private UUID decisionId; // nullable — DECISION cards
    private Instant at;
    private long tokens;

    public ChatMessage() {}

    public ChatMessage(UUID id, UUID chatId, int seq, String role,
                       String kind, String markdown, UUID runId,
                       UUID decisionId, Instant at, long tokens) {
        this.id = id;
        this.chatId = chatId;
        this.seq = seq;
        this.role = role;
        this.kind = kind;
        this.markdown = markdown;
        this.runId = runId;
        this.decisionId = decisionId;
        this.at = at;
        this.tokens = tokens;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID chatId() { return chatId; }
    public UUID getChatId() { return chatId; }
    public void setChatId(UUID chatId) { this.chatId = chatId; }
    public int seq() { return seq; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public String role() { return role; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String kind() { return kind; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String markdown() { return markdown; }
    public String getMarkdown() { return markdown; }
    public void setMarkdown(String markdown) { this.markdown = markdown; }
    public UUID runId() { return runId; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public UUID decisionId() { return decisionId; }
    public UUID getDecisionId() { return decisionId; }
    public void setDecisionId(UUID decisionId) { this.decisionId = decisionId; }
    public Instant at() { return at; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public long tokens() { return tokens; }
    public long getTokens() { return tokens; }
    public void setTokens(long tokens) { this.tokens = tokens; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatMessage that = (ChatMessage) o;
        return Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
