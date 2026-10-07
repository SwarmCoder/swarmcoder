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
@JsonTypeName("KnowledgeBrief")
public class KnowledgeBrief {
    private UUID id;
    private long revision;
    private UUID taskId;
    private List<LibraryDoc> libraries;
    private List<InternalApi> internalApis;
    private String renderedMarkdown;

    public KnowledgeBrief() {}

    public KnowledgeBrief(UUID id, long revision, UUID taskId, List<LibraryDoc> libraries, List<InternalApi> internalApis, String renderedMarkdown) {
        this.id = id;
        this.revision = revision;
        this.taskId = taskId;
        this.libraries = libraries;
        this.internalApis = internalApis;
        this.renderedMarkdown = renderedMarkdown;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long revision() { return revision; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public UUID taskId() { return taskId; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public List<LibraryDoc> libraries() { return libraries; }
    public List<LibraryDoc> getLibraries() { return libraries; }
    public void setLibraries(List<LibraryDoc> libraries) { this.libraries = libraries; }
    public List<InternalApi> internalApis() { return internalApis; }
    public List<InternalApi> getInternalApis() { return internalApis; }
    public void setInternalApis(List<InternalApi> internalApis) { this.internalApis = internalApis; }
    public String renderedMarkdown() { return renderedMarkdown; }
    public String getRenderedMarkdown() { return renderedMarkdown; }
    public void setRenderedMarkdown(String renderedMarkdown) { this.renderedMarkdown = renderedMarkdown; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KnowledgeBrief that = (KnowledgeBrief) o;
        return Objects.equals(this.id, that.id) && this.revision == that.revision && Objects.equals(this.taskId, that.taskId) && Objects.equals(this.libraries, that.libraries) && Objects.equals(this.internalApis, that.internalApis) && Objects.equals(this.renderedMarkdown, that.renderedMarkdown);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, revision, taskId, libraries, internalApis, renderedMarkdown);
    }
}

