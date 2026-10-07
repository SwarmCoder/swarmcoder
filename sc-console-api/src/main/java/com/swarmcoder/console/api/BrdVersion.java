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

import com.zeroz4j.api.BinaryPackable;
import com.zeroz4j.api.BinarySerializer;
import com.zeroz4j.api.DataModel;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * "The requirements changed, and they are now at revision N" — the whole notification, in two fields.
 *
 * <p><b>What this replaces.</b> {@link BrdSignals#CURRENT} carries the entire {@code Brd}, so every
 * edit deep-copied the requirement graph, serialised it, and pushed it to every connected client. At a
 * few thousand requirements that is megabytes each time somebody renames one requirement. What a client
 * actually needs is to know that its rows are stale; it can then re-query the page it is showing, which
 * is bounded by the window and not by the size of the document.
 *
 * <p><b>Why a type and not a bare Long.</b> Room to add without changing the signal, and the project id
 * so a client that has switched projects can ignore a notification for the one it left.
 *
 * <p><b>The dedup trap.</b> zeroz4j signals compare by {@code equals} and swallow a value equal to the
 * one already held, so a notification must be a FRESH instance and must differ when it means something
 * new. {@code revision} increments on every save, which satisfies both — but the {@code equals} below
 * has to include it, and this class has no setters so nothing can mutate a published value in place.
 * Six incidents in this codebase trace to a signal that was set with a mutated object (UX v3 rule 7).
 */
@DataModel
public class BrdVersion implements BinaryPackable {

    private String projectId;
    private long revision;

    public BrdVersion() { }

    public BrdVersion(String projectId, long revision) {
        this.projectId = projectId;
        this.revision = revision;
    }

    public String getProjectId() { return projectId; }
    public long getRevision() { return revision; }

    // Setters exist for the serializer's read path only; nothing else may call them, which is why the
    // constructor is the way this is built everywhere else.
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public void setRevision(long revision) { this.revision = revision; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BrdVersion that = (BrdVersion) o;
        return revision == that.revision && Objects.equals(projectId, that.projectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, revision);
    }

    @Override
    public String toString() {
        return "BrdVersion[project=" + projectId + ", revision=" + revision + "]";
    }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer,
                              com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, projectId);
        BinarySerializer.writeValue(buffer, revision, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.projectId = BinarySerializer.readString(buffer);
        this.revision = SessionSummaryDto.readLong(buffer, mapper);
    }
}
