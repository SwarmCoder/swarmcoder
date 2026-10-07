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
 * An uploaded document a project's requirements were extracted from — the raw material behind the
 * BRD. {@code extractedText} is the plain-text rendering the extractor produced, and
 * {@code sha256} identifies the original bytes so a re-upload of the same file is recognised.
 *
 * <p>{@code extractedBy} names the extractor and is one of {@code passthrough} (the upload was
 * already text), {@code pdfbox} (PDF text layer), {@code poi} (Office formats) or {@code vision}.
 * {@code vision} is different in kind from the others: the text is a <em>model's reading of an
 * image</em>, not the document itself, and may therefore be wrong or invented. It must be surfaced
 * as such in the UI wherever the extracted text or anything derived from it is shown.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class SourceDocument {
    private UUID id;
    private UUID projectId;
    private String filename;
    private String mediaType;
    private String sha256;          // digest of the original bytes
    private String extractedText;
    private String extractedBy;     // passthrough | pdfbox | poi | vision
    private long byteSize;
    private Instant uploadedAt;

    public SourceDocument() {}

    public SourceDocument(UUID id, UUID projectId, String filename, String mediaType,
                          String sha256, String extractedText, String extractedBy,
                          long byteSize, Instant uploadedAt) {
        this.id = id;
        this.projectId = projectId;
        this.filename = filename;
        this.mediaType = mediaType;
        this.sha256 = sha256;
        this.extractedText = extractedText;
        this.extractedBy = extractedBy;
        this.byteSize = byteSize;
        this.uploadedAt = uploadedAt;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String filename() { return filename; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String mediaType() { return mediaType; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String sha256() { return sha256; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String extractedText() { return extractedText; }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public String extractedBy() { return extractedBy; }
    public String getExtractedBy() { return extractedBy; }
    public void setExtractedBy(String extractedBy) { this.extractedBy = extractedBy; }
    public long byteSize() { return byteSize; }
    public long getByteSize() { return byteSize; }
    public void setByteSize(long byteSize) { this.byteSize = byteSize; }
    public Instant uploadedAt() { return uploadedAt; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SourceDocument that = (SourceDocument) o;
        return this.byteSize == that.byteSize && Objects.equals(this.id, that.id)
            && Objects.equals(this.projectId, that.projectId)
            && Objects.equals(this.filename, that.filename)
            && Objects.equals(this.mediaType, that.mediaType)
            && Objects.equals(this.sha256, that.sha256)
            && Objects.equals(this.extractedText, that.extractedText)
            && Objects.equals(this.extractedBy, that.extractedBy)
            && Objects.equals(this.uploadedAt, that.uploadedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, projectId, filename, mediaType, sha256, extractedText,
            extractedBy, byteSize, uploadedAt);
    }
}
