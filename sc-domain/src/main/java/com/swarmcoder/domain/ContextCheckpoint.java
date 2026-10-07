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
import java.util.UUID;

public class ContextCheckpoint {
    private UUID id;
    private UUID ownerAgentSessionId;
    private int messageIndex;
    private String prefixHash;
    private Instant at;
    private String label;

    public ContextCheckpoint() {}

    public ContextCheckpoint(UUID id, UUID ownerAgentSessionId, int messageIndex, String prefixHash, Instant at, String label) {
        this.id = id;
        this.ownerAgentSessionId = ownerAgentSessionId;
        this.messageIndex = messageIndex;
        this.prefixHash = prefixHash;
        this.at = at;
        this.label = label;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID ownerAgentSessionId() { return ownerAgentSessionId; }
    public UUID getOwnerAgentSessionId() { return ownerAgentSessionId; }
    public void setOwnerAgentSessionId(UUID ownerAgentSessionId) { this.ownerAgentSessionId = ownerAgentSessionId; }
    public int messageIndex() { return messageIndex; }
    public int getMessageIndex() { return messageIndex; }
    public void setMessageIndex(int messageIndex) { this.messageIndex = messageIndex; }
    public String prefixHash() { return prefixHash; }
    public String getPrefixHash() { return prefixHash; }
    public void setPrefixHash(String prefixHash) { this.prefixHash = prefixHash; }
    public Instant at() { return at; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public String label() { return label; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContextCheckpoint that = (ContextCheckpoint) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.ownerAgentSessionId, that.ownerAgentSessionId) && this.messageIndex == that.messageIndex && Objects.equals(this.prefixHash, that.prefixHash) && Objects.equals(this.at, that.at) && Objects.equals(this.label, that.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerAgentSessionId, messageIndex, prefixHash, at, label);
    }
}

