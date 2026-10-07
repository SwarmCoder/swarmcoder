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

import java.util.Objects;
import java.util.UUID;

/**
 * Where a rule came from.
 *
 * <p>{@code source} is one of three words, and the difference between them is who decided:
 * {@code "extraction"} — a model read a build's transcripts after a failure and proposed it;
 * {@code "stated"} — a person wrote it in a document, ticked that document as technical, and
 * applied it, so it was never a guess; {@code "human"} — somebody wrote or edited the rule file
 * by hand.
 *
 * <p>{@code document} is the filename the rule was stated in, and it is null for the other two.
 * It exists because a rule with no trail back to the paper it came from is a rule nobody can
 * check against its source — the one thing the requirements route did give, and the reason it
 * is kept here (author decision 2026-08-31).
 *
 * <p>{@code excerpt} is the document's own sentence(s) a STATED rule was drawn from — verbatim,
 * not the analyst's restatement of them. It exists because a rule's {@code markdownBody} is
 * itself the analyst's paraphrase, and a paraphrase can drop the one word (a backticked artifact
 * id, most often) the document actually used — {@link com.swarmcoder.domain.LearnedGuideline}'s
 * javadoc points at {@code RulesVersusManifest} for the comparison this exists to keep honest.
 * Null for a rule with no wording to quote (a machine proposal, or one written by hand).
 */
public class Provenance {
    private String source;
    private UUID sourceRunId;
    /** The document a STATED rule was read out of; null for a learned or hand-written one. */
    private String document;
    /** The document's own wording a STATED rule was drawn from, verbatim; null when there is none
     * to quote. */
    private String excerpt;

    public Provenance() {}

    public Provenance(String source, UUID sourceRunId) {
        this(source, sourceRunId, null);
    }

    public Provenance(String source, UUID sourceRunId, String document) {
        this(source, sourceRunId, document, null);
    }

    public Provenance(String source, UUID sourceRunId, String document, String excerpt) {
        this.source = source;
        this.sourceRunId = sourceRunId;
        this.document = document;
        this.excerpt = excerpt;
    }

    public String source() { return source; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public UUID sourceRunId() { return sourceRunId; }
    public UUID getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(UUID sourceRunId) { this.sourceRunId = sourceRunId; }
    public String document() { return document; }
    public String getDocument() { return document; }
    public void setDocument(String document) { this.document = document; }
    public String excerpt() { return excerpt; }
    public String getExcerpt() { return excerpt; }
    public void setExcerpt(String excerpt) { this.excerpt = excerpt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Provenance that = (Provenance) o;
        return Objects.equals(this.source, that.source)
            && Objects.equals(this.sourceRunId, that.sourceRunId)
            && Objects.equals(this.document, that.document)
            && Objects.equals(this.excerpt, that.excerpt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, sourceRunId, document, excerpt);
    }
}
