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

import java.util.ArrayList;
import java.util.List;

/**
 * How much text one answer may carry back to the browser.
 *
 * <p>ZeroZ Stack 0.7.0 refuses any single message over <b>4 MB</b>
 * ({@code zeroz.ws.maxBinaryMessageBytes}), and the refusal is not an exception the caller can
 * catch: <b>the connection is closed</b>. From the operator's seat that is the whole console going
 * blank and reconnecting, with no message saying why. Before 0.7.0 the ceiling was whatever the
 * container allowed, which on Helidon was about 2 GB, so nothing here had ever been bounded.
 *
 * <p>Two of the console's answers could genuinely exceed it. A document's extracted text is capped
 * by the extractor at ten million characters, which is two and a half times the limit on its own.
 * A blob-backed trace payload — a full prompt, a whole tool output — has no cap at all.
 *
 * <p>The rule here is: cut, and say so in the text. A truncated document that ends with a line
 * telling the reader it was truncated is a worse answer than the whole thing but a far better one
 * than a dead connection, and the operator can act on it. The budget is set well under the
 * framework's own so that the surrounding object — ids, names, other fields, and UTF-8 characters
 * that cost more than one byte each — still fits.
 */
final class WireBudget {

    /**
     * The framework's ceiling on one message, restated so the arithmetic below can be read without
     * looking it up. Not read from the property: if a deployment raises the property, the smaller
     * number here is still safe.
     */
    static final int FRAMEWORK_MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

    /**
     * The most characters one string in one answer may carry — one million, so that even at the
     * three bytes per character that non-Latin text costs, the message stays under half the
     * framework's limit and leaves room for everything around it.
     */
    static final int MAX_TEXT_CHARS = 1_000_000;

    private WireBudget() {}

    /**
     * Cuts a string to {@link #MAX_TEXT_CHARS} and appends a line saying what happened.
     *
     * @param text        the value about to be returned; null is returned as an empty string
     * @param description what the text is, in words the operator will understand, e.g.
     *                    "This document"
     * @return the text, whole or cut with an explanation on the end
     */
    static String clamp(String text, String description) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_TEXT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TEXT_CHARS)
            + "\n\n[" + description + " is " + text.length() + " characters long and only the "
            + "first " + MAX_TEXT_CHARS + " are shown here. The whole text is on the server and is "
            + "what the analyst reads; this window cannot carry more than that in one piece.]";
    }

    /**
     * Copies source documents WITHOUT their extracted text.
     *
     * <p>Every screen that lists documents shows the name, the size and which extractor read it —
     * never the text, which is fetched on demand for the one document being looked at. Sending the
     * text with the list meant a project with a handful of large specs could not list its documents
     * at all.
     *
     * @param documents the stored documents
     * @return fresh instances carrying everything but the text
     */
    static List<SourceDocument> withoutText(List<SourceDocument> documents) {
        List<SourceDocument> stripped = new ArrayList<>();
        if (documents == null) {
            return stripped;
        }
        for (SourceDocument document : documents) {
            stripped.add(GuidedFlows.withoutText(document));
        }
        return stripped;
    }
}
