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
 * One point in a project BRD's evolution (author decision 2026-07-24): an append-only snapshot
 * captured on every mutation so the living document's exact history can be browsed and any past
 * state restored. {@code author} attributes the change (human | agent | extraction | restore) and
 * {@code summary} is a short human-readable note ("added R3 (Login)", "edited R1", …). The
 * {@code snapshot} is a deep copy of the whole {@link Brd} at that revision, so later edits never
 * mutate history.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class BrdRevision {
    private UUID id;
    private UUID projectId;
    private long revision;
    private Instant at;
    private String author;
    private String summary;
    private Brd snapshot;

    public BrdRevision() {}

    public BrdRevision(UUID id, UUID projectId, long revision, Instant at,
                       String author, String summary, Brd snapshot) {
        this.id = id;
        this.projectId = projectId;
        this.revision = revision;
        this.at = at;
        this.author = author;
        this.summary = summary;
        this.snapshot = snapshot;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public long revision() { return revision; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public Instant at() { return at; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public String author() { return author; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String summary() { return summary; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Brd snapshot() { return snapshot; }
    public Brd getSnapshot() { return snapshot; }
    public void setSnapshot(Brd snapshot) { this.snapshot = snapshot; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrdRevision that = (BrdRevision) o;
        return this.revision == that.revision && Objects.equals(this.id, that.id)
            && Objects.equals(this.projectId, that.projectId) && Objects.equals(this.at, that.at)
            && Objects.equals(this.author, that.author) && Objects.equals(this.summary, that.summary)
            && Objects.equals(this.snapshot, that.snapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, projectId, revision, at, author, summary, snapshot);
    }
}
