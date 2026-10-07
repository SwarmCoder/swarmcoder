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
import com.zeroz4j.server.FileUploadHandler;
import com.zeroz4j.server.UploadResult;
import com.zeroz4j.server.UploadedFile;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.UUID;

/**
 * Receives requirements documents dropped into the Console and turns each one into a persisted
 * {@link SourceDocument} the BRD author agent can read
 * (docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md §4.2).
 *
 * <p>This replaces a hand-rolled {@code POST /api/ingest} JAX-RS resource that took the raw file
 * bytes as the request body with the name in a query parameter. That endpoint was
 * <b>unauthenticated</b> — recorded as a known deviation in {@code docs/DEVELOPER_CORRECTIONS.md}
 * (D1) and named there as the most sensitive of the three, because it accepted and persisted
 * attacker-supplied bytes. The framework's own upload address closes it with no auth work here: the
 * browser must first ask its existing live connection for a one-time pass, and the address accepts
 * nothing without one, so an upload carries exactly the identity of the connection that asked for
 * it. There is no other way in — no API key, no signed URL.
 *
 * <p>The framework also brings what the hand-rolled endpoint never had: a per-file progress bar and
 * cancel button, a size check made against the declared length <em>before</em> any body is read,
 * and a temporary file that is deleted whatever happens — including a handler that throws.
 *
 * <p><b>The temporary file is deleted the moment {@link #onFileUploaded} returns</b>, so the bytes
 * are read here and now rather than kept as a path for later.
 */
@ApplicationScoped
public class DocumentUploadHandler implements FileUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadHandler.class);

    @Override
    public UploadResult onFileUploaded(UploadedFile file) throws Exception {
        // getFileName() and getContentType() are text the browser sent. Neither is used to build a
        // path: the framework named the temporary file, and DocumentIngest generates the stored id.
        return ingest(file.getFileName(), file.getContentType(),
            Files.readAllBytes(file.getTempFile()));
    }

    /**
     * Everything the handler does once it holds the bytes.
     *
     * <p>Separate from {@link #onFileUploaded} so a test can drive it: {@code UploadedFile} is
     * built by the framework's upload machinery and cannot be constructed from outside it, which is
     * correct and also means the interesting half would otherwise be untestable.
     *
     * @param fileName    the name the browser reported; text, never a path
     * @param contentType the type the browser reported; a hint, the extension wins
     * @param bytes       the complete file
     * @return the sentence shown beside the file in the browser, and whether it was kept
     */
    UploadResult ingest(String fileName, String contentType, byte[] bytes) {
        ConsoleContext context = ConsoleContext.get();
        if (context == null) {
            return UploadResult.rejected("The console is still starting up. Try again in a moment.");
        }
        UUID projectId = context.currentProjectId();
        if (projectId == null) {
            return UploadResult.rejected(
                "There is no project open, and a document is read into a project. "
                + "Open or create a project first, then add the document again.");
        }

        DocumentIngest.Result result;
        try {
            result = DocumentIngest.ingest(context.store(), context.vision(),
                projectId, fileName, contentType, bytes);
        } catch (RuntimeException e) {
            // A file we cannot read must not take the console down with it, and the person who
            // dropped it is owed a sentence rather than silence.
            log.warn("Reading '{}' failed", fileName, e);
            return UploadResult.rejected("That file could not be read. Try a different format — "
                + "plain text, Markdown, PDF and Word all work.");
        }

        if (result.failed()) {
            return UploadResult.rejected(result.error());
        }

        SourceDocument document = result.document();
        // The document belongs in the intake wizard's list without the operator having to add it
        // there a second time.
        GuidedFlows.attachUploaded(context.store(), projectId, document.id());

        int chars = document.extractedText() == null ? 0 : document.extractedText().length();
        String read = document.extractedBy() == null ? "" : ", read by " + document.extractedBy();
        return UploadResult.accepted("Added — " + chars + " characters" + read + ".");
    }
}
