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
 * One chat with the coder (CONSOLE_DESIGN_V2.md §5): scoped to a project, optionally bound
 * to the run it started. Persisted POJO — EclipseStore cannot handle records.
 */
@DataModel
public class ChatSession {
    private UUID id;
    private UUID projectId;
    private String title;
    private Instant createdAt;
    private UUID boundRunId; // nullable — set when the chat starts a run
    private boolean archived;
    private String modelOverride; // nullable — a model name; null = the project's chat model

    public ChatSession() {}

    public ChatSession(UUID id, UUID projectId, String title,
                       Instant createdAt, UUID boundRunId, boolean archived) {
        this.id = id;
        this.projectId = projectId;
        this.title = title;
        this.createdAt = createdAt;
        this.boundRunId = boundRunId;
        this.archived = archived;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String title() { return title; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public UUID boundRunId() { return boundRunId; }
    public UUID getBoundRunId() { return boundRunId; }
    public void setBoundRunId(UUID boundRunId) { this.boundRunId = boundRunId; }
    public boolean archived() { return archived; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public String modelOverride() { return modelOverride; }
    public String getModelOverride() { return modelOverride; }
    public void setModelOverride(String modelOverride) { this.modelOverride = modelOverride; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatSession that = (ChatSession) o;
        return Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
