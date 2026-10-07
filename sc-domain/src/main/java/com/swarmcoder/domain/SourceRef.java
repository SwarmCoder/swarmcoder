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

import java.util.Objects;
import java.util.UUID;

/**
 * Provenance for an extracted requirement: which uploaded {@link SourceDocument} it came from, and
 * where in that document. {@code locator} is a free-form position within the document (a page
 * number, heading, section path or character range, depending on the extractor) so a reader can be
 * taken back to the passage the requirement was drawn from.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class SourceRef {
    private UUID documentId;
    private String locator;     // page / heading / section / char range

    public SourceRef() {}

    public SourceRef(UUID documentId, String locator) {
        this.documentId = documentId;
        this.locator = locator;
    }

    public UUID documentId() { return documentId; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public String locator() { return locator; }
    public String getLocator() { return locator; }
    public void setLocator(String locator) { this.locator = locator; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SourceRef that = (SourceRef) o;
        return Objects.equals(this.documentId, that.documentId)
            && Objects.equals(this.locator, that.locator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, locator);
    }
}
