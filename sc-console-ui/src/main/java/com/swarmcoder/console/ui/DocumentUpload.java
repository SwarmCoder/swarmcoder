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
package com.swarmcoder.console.ui;

import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.ui.component.FileUpload;

/**
 * The one place the Console asks for a requirements document.
 *
 * <p>This replaces {@code Uploader}, 157 lines of {@code @JSBody}: a hand-made file input, hand-made
 * drag listeners, a {@code window.__scUpload} function that posted the raw bytes to
 * {@code ./api/ingest}, and a hand-written JSON parser for the reply. That endpoint took anything
 * anybody sent it — it was <b>unauthenticated</b>, and it was the one that persisted the bytes.
 *
 * <p>{@link FileUpload} sends each file to the framework's own address instead. The page has to ask
 * its live connection for a one-time pass first, so an upload carries the identity of the
 * connection that asked for it and there is no other way in. It also brings what the hand-rolled
 * version never had: a progress bar and a cancel button per file, a size check made before any
 * bytes leave the machine, and the server's own sentence shown beside the file it is about.
 *
 * <p>The server side is {@code DocumentUploadHandler} in {@code sc-console}.
 */
final class DocumentUpload {

    /** File types the intake pipeline can read; images need a configured vision model. */
    static final String ACCEPT =
        ".md,.markdown,.txt,.adoc,.rst,.csv,.pdf,.docx,.png,.jpg,.jpeg,.webp,.gif";

    private DocumentUpload() {}

    /** A drop box wired for requirements documents, ready to be added to a screen. */
    static FileUpload box() {
        return new FileUpload()
            .setAccept(ACCEPT)
            .setTitle("Drop your requirements documents here")
            .setSubtitle("or click to choose them — PDF, Word, Markdown, plain text, "
                + "or a photo of a whiteboard");
    }

    /**
     * The same box in a window, for screens whose own layout has no room for it.
     *
     * <p>{@code onFinished} runs after each file, on a thread that may make calls to the server, so
     * a caller can refresh whatever list the new document belongs in.
     */
    static Dialog dialog(Runnable onFinished) {
        Dialog dialog = new Dialog();
        dialog.getElement().setAttribute("data-testid", "document-upload-dialog");
        dialog.add(box().addUploadListener((name, accepted, message) -> {
            if (accepted && onFinished != null) {
                onFinished.run();
            }
        }));
        // Close is the primary action, and it stays even though it is no longer the only way out.
        // It exists because this window once had no way out at all: on ZeroZ Stack 0.7.0 a Dialog
        // was a <dialog> opened by adding a class rather than by showModal(), so Escape never
        // reached it and it had no clickable backdrop either, and opening the attachment window
        // from the chat composer froze the whole Console until the page was reloaded, taking the
        // half-typed message with it. 0.8.0 hands the element to the browser, so Escape and a click
        // outside work now — a visible way out is still what an operator looks for first.
        Button close = new Button("Close", e -> dialog.close());
        close.addClassName("btn-sm btn-primary");
        dialog.addAction(close);
        return dialog;
    }
}
