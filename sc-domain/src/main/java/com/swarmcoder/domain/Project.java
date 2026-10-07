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
import java.util.List;
import java.util.UUID;
import java.util.Objects;

/**
 * A project the orchestrator can work on (multi-project support). Each project owns a primary
 * code folder (where changes are made) and zero or more read-only context folders the agents
 * may read for grounding but never write to (e.g. a shared library like zeroz4j for GUI work).
 * Runs, designs, decisions, and sessions are scoped to a project via {@link Run#projectId()}.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore, whose reflective
 * serializer cannot handle records — see {@link VerificationReport}. Richer per-project settings
 * (model overrides, budgets) live in the project's on-disk config, layered over this identity.
 */
public class Project {
    private UUID id;
    private String name;
    private String primaryPath;
    private List<String> contextPaths;
    private Instant createdAt;
    private boolean archived;
    /**
     * True once the rule files a checkout may still carry under {@code .swarmcoder/guidelines/}
     * have been read into the store (author decision 2026-09-02). Rules are store objects now; the
     * folder is read exactly once, on the first open after this field existed, and never again.
     * The files are left where they are — they are in the operator's git history, and that is
     * theirs to clean. Loads as {@code false} on a project record written before the field.
     */
    private boolean guidelineFilesImported;

    public Project() {}

    public Project(UUID id, String name, String primaryPath, List<String> contextPaths, Instant createdAt, boolean archived) {
        this.id = id;
        this.name = name;
        this.primaryPath = primaryPath;
        this.contextPaths = contextPaths;
        this.createdAt = createdAt;
        this.archived = archived;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String name() { return name; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String primaryPath() { return primaryPath; }
    public String getPrimaryPath() { return primaryPath; }
    public void setPrimaryPath(String primaryPath) { this.primaryPath = primaryPath; }
    public List<String> contextPaths() { return contextPaths; }
    public List<String> getContextPaths() { return contextPaths; }
    public void setContextPaths(List<String> contextPaths) { this.contextPaths = contextPaths; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public boolean archived() { return archived; }
    public boolean getArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public boolean guidelineFilesImported() { return guidelineFilesImported; }
    public boolean getGuidelineFilesImported() { return guidelineFilesImported; }
    public void setGuidelineFilesImported(boolean imported) { this.guidelineFilesImported = imported; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Project that = (Project) o;
        return this.archived == that.archived && Objects.equals(this.id, that.id) && Objects.equals(this.name, that.name) && Objects.equals(this.primaryPath, that.primaryPath) && Objects.equals(this.contextPaths, that.contextPaths) && Objects.equals(this.createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, primaryPath, contextPaths, createdAt, archived);
    }
}
