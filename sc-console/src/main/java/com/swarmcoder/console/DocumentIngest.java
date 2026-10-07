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
package com.swarmcoder.console;

import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Turns an uploaded file into a persisted {@link SourceDocument} the BRD author agent can read
 * (docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md §4).
 *
 * <p>Text formats are extracted locally by {@link DocumentExtractor}; images are read by the
 * configured vision model. The result is always labelled with HOW it was obtained
 * ({@code extractedBy}), because {@code vision} output is a model's READING of an image and not the
 * document itself — an operator promoting requirements out of it needs to know that.
 *
 * <p>Uploading the same bytes twice returns the existing document rather than re-extracting: PDF and
 * especially vision extraction are expensive, and a re-drop of the same file is a common accident.
 */
public final class DocumentIngest {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngest.class);

    /** Upper bound on an accepted upload. Generous for a spec document, far below heap pressure. */
    public static final int MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

    private static final String VISION_PROMPT = """
        This image is part of a requirements document. Transcribe every piece of information it \
        carries, faithfully and completely: all text verbatim (including headings, tables, labels \
        and annotations), and for any diagram, wireframe or chart, describe the structure, the \
        elements and the relationships between them.

        Do NOT interpret, summarise, judge or invent. If part of the image is illegible, say so \
        explicitly at that point rather than guessing. Output plain text only.""";

    private DocumentIngest() {
    }

    /** Outcome of an ingest: exactly one of {@code document} or {@code error} is non-null. */
    public record Result(SourceDocument document, String error) {
        public static Result ok(SourceDocument document) {
            return new Result(document, null);
        }

        public static Result error(String message) {
            return new Result(null, message);
        }

        public boolean failed() {
            return error != null;
        }
    }

    /**
     * Extracts and persists an uploaded file.
     *
     * @param mediaType the browser-reported content type; only a hint — the filename extension wins,
     *                  because browsers routinely report {@code application/octet-stream}
     */
    public static Result ingest(ArtifactStore store, ConsoleContext.VisionModel vision,
                                UUID projectId, String filename, String mediaType, byte[] bytes) {
        if (projectId == null) {
            return Result.error("no current project — a document is ingested into a project's BRD");
        }
        if (bytes == null || bytes.length == 0) {
            return Result.error("empty upload");
        }
        if (bytes.length > MAX_UPLOAD_BYTES) {
            return Result.error("file is " + (bytes.length / (1024 * 1024)) + "MB — the limit is "
                + (MAX_UPLOAD_BYTES / (1024 * 1024)) + "MB");
        }
        String safeName = filename == null || filename.isBlank() ? "upload" : filename.trim();

        String sha = sha256(bytes);
        SourceDocument existing = store.findSourceDocumentBySha(projectId, sha);
        if (existing != null) {
            log.info("Ingest: {} is byte-identical to already-ingested {} — reusing extraction",
                safeName, existing.filename());
            return Result.ok(existing);
        }

        DocumentExtractor.Extracted extracted = DocumentExtractor.extract(safeName, mediaType, bytes);
        String text;
        String extractedBy;

        if (DocumentExtractor.needsVision(extracted)) {
            if (vision == null) {
                return Result.error("'" + safeName + "' is an image, and no vision model is "
                    + "configured. Set roles.vision (baseUrl + modelName) in Settings to a "
                    + "vision-capable model, or upload the document as text, markdown, PDF or DOCX.");
            }
            try {
                text = vision.describe(VISION_PROMPT, dataUri(mediaType, safeName, bytes));
            } catch (Exception e) {
                log.warn("Vision extraction failed for {}: {}", safeName, e.toString());
                return Result.error("the vision model could not read '" + safeName + "': " + e.getMessage());
            }
            if (text == null || text.isBlank()) {
                return Result.error("the vision model returned nothing for '" + safeName + "'");
            }
            extractedBy = "vision:" + vision.modelName();
        } else if (extracted.error() != null) {
            return Result.error(extracted.error());
        } else {
            text = extracted.text();
            extractedBy = extracted.extractedBy();
            if (text == null || text.isBlank()) {
                return Result.error("no text could be extracted from '" + safeName
                    + "' — it may be a scanned document, which needs to be uploaded as an image "
                    + "so the vision model can read it");
            }
        }

        SourceDocument document = new SourceDocument(UUID.randomUUID(), projectId, safeName,
            mediaType, sha, text, extractedBy, bytes.length, Instant.now());
        store.saveSourceDocument(document);
        log.info("Ingest: {} ({} bytes) extracted by {} into {} chars",
            safeName, bytes.length, extractedBy, text.length());
        return Result.ok(document);
    }

    /** An OpenAI-compatible inline image reference. */
    private static String dataUri(String mediaType, String filename, byte[] bytes) {
        String type = mediaType != null && mediaType.startsWith("image/")
            ? mediaType : guessImageType(filename);
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String guessImageType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    /** Content hash — the dedup key, and a stable provenance fingerprint for the extracted text. */
    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 is mandatory in every JRE; if it is genuinely absent, fall back to a
            // length+content hash rather than failing the upload.
            return "len" + bytes.length + "-" + new String(bytes, StandardCharsets.ISO_8859_1).hashCode();
        }
    }
}
