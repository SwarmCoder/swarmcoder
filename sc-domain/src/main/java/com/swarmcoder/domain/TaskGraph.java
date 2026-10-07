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
@JsonTypeName("TaskGraph")
public class TaskGraph {
    private UUID id;
    private long revision;
    private UUID designId;
    private List<Task> tasks;
    private List<TaskEdge> dependencies;

    public TaskGraph() {}

    public TaskGraph(UUID id, long revision, UUID designId, List<Task> tasks, List<TaskEdge> dependencies) {
        this.id = id;
        this.revision = revision;
        this.designId = designId;
        this.tasks = tasks;
        this.dependencies = dependencies;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long revision() { return revision; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public UUID designId() { return designId; }
    public UUID getDesignId() { return designId; }
    public void setDesignId(UUID designId) { this.designId = designId; }
    public List<Task> tasks() { return tasks; }
    public List<Task> getTasks() { return tasks; }
    public void setTasks(List<Task> tasks) { this.tasks = tasks; }
    public List<TaskEdge> dependencies() { return dependencies; }
    public List<TaskEdge> getDependencies() { return dependencies; }
    public void setDependencies(List<TaskEdge> dependencies) { this.dependencies = dependencies; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskGraph that = (TaskGraph) o;
        return Objects.equals(this.id, that.id) && this.revision == that.revision && Objects.equals(this.designId, that.designId) && Objects.equals(this.tasks, that.tasks) && Objects.equals(this.dependencies, that.dependencies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, revision, designId, tasks, dependencies);
    }
}

