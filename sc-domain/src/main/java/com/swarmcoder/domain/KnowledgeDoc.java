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
 * One curated knowledge entry (author requirement 2026-07-14): a convention, canonical
 * example, or best practice that feeds worker briefs and the chat analyst. Store-first —
 * NOT a markdown file on disk; the body is markdown-formatted text but the object is the
 * storage. status: PROPOSED (mined from a run, awaiting review) | ACTIVE. source: human |
 * extraction. Persisted POJO — EclipseStore cannot handle records.
 */
@DataModel
public class KnowledgeDoc {
    private UUID id;
    private UUID projectId;
    private String slug;
    private String title;
    private String body;
    private String status;
    private String source;
    private Instant createdAt;
    private Instant updatedAt;

    public KnowledgeDoc() {}

    public KnowledgeDoc(UUID id, UUID projectId, String slug, String title,
                        String body, String status, String source,
                        Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.slug = slug;
        this.title = title;
        this.body = body;
        this.status = status;
        this.source = source;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String slug() { return slug; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String title() { return title; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String body() { return body; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String status() { return status; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String source() { return source; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /**
     * Value equality over every field, as {@code Story}, {@code Iteration}, {@code Brd} and
     * {@code Decision} have. This is load-bearing, not bookkeeping: the Knowledge list is a
     * {@code ValueSignal<List<KnowledgeDoc>>}, and {@code ValueSignal.set} dedups by
     * {@code equals}. While this compared ids only, accepting a PROPOSED doc promoted it in the
     * store and then republished a list that compared EQUAL to the retained one — a silent no-op,
     * so the list never redrew and the amber badge stayed on an ACTIVE doc.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KnowledgeDoc that = (KnowledgeDoc) o;
        return Objects.equals(this.id, that.id)
            && Objects.equals(this.projectId, that.projectId)
            && Objects.equals(this.slug, that.slug)
            && Objects.equals(this.title, that.title)
            && Objects.equals(this.body, that.body)
            && Objects.equals(this.status, that.status)
            && Objects.equals(this.source, that.source)
            && Objects.equals(this.createdAt, that.createdAt)
            && Objects.equals(this.updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, projectId, slug, title, body, status, source,
            createdAt, updatedAt);
    }
}
