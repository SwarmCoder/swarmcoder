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
 * One document attached to a {@link GuidedFlow}, together with the operator's instructions for
 * <em>that</em> document — "authoritative for pricing", "ignore section 4", "this is the legacy
 * spec". The notes are handed to the agent alongside the extracted text.
 *
 * <p>This is the piece that turns a pile of documents into a briefing. Without it the agent sees
 * only a stack of files with no way to know which one wins, which part is stale, and which is there
 * for background; with it, the operator's judgement about the sources travels with the sources.
 *
 * <p>{@code documentId} points at the stored {@link SourceDocument}; this object carries no copy of
 * the text.
 *
 * <p><b>Per-document analysis state.</b> {@code analysedAt} records when a document's content was
 * last applied to the BRD, and {@code excluded} keeps it out of the next run. Adding one document to
 * a project that already has five should cost one document's worth of reading, not six — re-sending
 * material already turned into requirements wastes the model's context, invites duplicate proposals,
 * and makes every analysis slower than the last. An analysed document can always be ticked back in
 * when its meaning has changed.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class FlowDocument {
    private UUID documentId;
    private String notes;            // the operator's instructions for this document
    /** When this document's content was last applied to the BRD, or null if it never has been. */
    private Instant analysedAt;
    /**
     * Kept out of the next run. Set when an apply absorbs the document, so the DEFAULT after
     * analysing is "do not read this again"; false — the value a freshly attached document loads
     * with — means it goes in.
     */
    private boolean excluded;
    /**
     * True when this document says how the project must be BUILT rather than what it must DO
     * (author decision 2026-08-31).
     *
     * <p>The operator sets it when they attach the file, and it is one bit they already know at
     * that moment. It changes what the analyst is asked to look for — standing rules about the
     * stack, the layout, the transport, what is forbidden — and it makes the absence of any such
     * document something the analyst can notice and ask about.
     *
     * <p><b>Why the operator and not the model.</b> A model classifying the document would be a
     * guess about technology made by the same kind of guess this whole feature exists to stop, and
     * a document that mixes business and technical content would come out as one or the other. The
     * analyst still decides, statement by statement, which requirements are rules and which are
     * features; this bit only tells it what kind of document it is holding.
     *
     * <p>A field added after the fact: it loads as false on every stored flow, which is what every
     * document attached before this existed was.
     */
    private boolean technical;

    public FlowDocument() {}

    public FlowDocument(UUID documentId, String notes) {
        this(documentId, notes, null, false);
    }

    public FlowDocument(UUID documentId, String notes, Instant analysedAt, boolean excluded) {
        this(documentId, notes, analysedAt, excluded, false);
    }

    public FlowDocument(UUID documentId, String notes, Instant analysedAt, boolean excluded,
                        boolean technical) {
        this.documentId = documentId;
        this.notes = notes;
        this.analysedAt = analysedAt;
        this.excluded = excluded;
        this.technical = technical;
    }

    public UUID documentId() { return documentId; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public String notes() { return notes; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant analysedAt() { return analysedAt; }
    public Instant getAnalysedAt() { return analysedAt; }
    public void setAnalysedAt(Instant analysedAt) { this.analysedAt = analysedAt; }
    public boolean excluded() { return excluded; }
    public boolean isExcluded() { return excluded; }
    public void setExcluded(boolean excluded) { this.excluded = excluded; }
    public boolean technical() { return technical; }
    public boolean isTechnical() { return technical; }
    public void setTechnical(boolean technical) { this.technical = technical; }

    /** True when this document will be read by the next analysis. */
    public boolean included() { return !excluded; }

    /** True when this document has already been turned into requirements at least once. */
    public boolean analysed() { return analysedAt != null; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlowDocument that = (FlowDocument) o;
        return this.excluded == that.excluded && this.technical == that.technical
            && Objects.equals(this.documentId, that.documentId)
            && Objects.equals(this.notes, that.notes)
            && Objects.equals(this.analysedAt, that.analysedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, notes, analysedAt, excluded, technical);
    }
}
