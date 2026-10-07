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
package com.swarmcoder.workflow;

import com.swarmcoder.verify.BlobSink;

import java.nio.charset.StandardCharsets;

/**
 * Where a malformed LLM reply goes once a role gives up on it, and the honest sentence naming
 * where to find it.
 *
 * <p>Before this, a reply that failed to parse was thrown away the moment it was caught, and
 * the failure message could only guess at why ("check the testAuthor endpoint", "designing from
 * the primer alone") — including on 2026-09-03, when the real cause was a paid endpoint sending
 * back a JSON string that itself contained escaped JSON. The raw text is what an operator needs
 * to see to tell "the model sent garbage" from "the endpoint is down" from "our own prompt asked
 * for the wrong shape"; keeping it, referenced by a short blob id rather than logged inline
 * (a reply can be arbitrarily long), is what makes that diagnosis possible after the fact.
 */
final class LlmReplyBlobs {

    private LlmReplyBlobs() {}

    /**
     * Stores {@code reply} and returns a sentence naming what failed and where to read it. Never
     * the raw text itself — that is what the blob is for.
     *
     * @param blobs      where to store it; {@code null} is treated as {@link BlobSink#NONE}
     * @param roleLabel  who produced the reply, e.g. "the test author's" or "the architect's"
     * @param reply      the raw reply that would not parse, twice
     * @param parseError the second parser's own message, used only when nothing could be stored
     */
    static String describeFailure(BlobSink blobs, String roleLabel, String reply, String parseError) {
        String ref = (blobs == null ? BlobSink.NONE : blobs).put(reply.getBytes(StandardCharsets.UTF_8));
        return ref == null
            ? roleLabel + " reply was not valid JSON (twice): " + parseError
            : roleLabel + " reply was not valid JSON (twice); its reply is kept as blob " + ref;
    }

    /**
     * Stores {@code reply} — a reply that parsed cleanly but named no files, after a re-ask got
     * the same answer again — and returns the sentence a park message quotes. Same shape as
     * {@link #describeFailure}, for the sibling failure: valid JSON that simply had nothing in
     * it, twice.
     *
     * @param blobs     where to store it; {@code null} is treated as {@link BlobSink#NONE}
     * @param roleLabel who produced the reply, e.g. "the test author"
     * @param reply     the raw reply that named no files, the second time
     */
    static String describeNoFilesTwice(BlobSink blobs, String roleLabel, String reply) {
        String ref = (blobs == null ? BlobSink.NONE : blobs).put(reply.getBytes(StandardCharsets.UTF_8));
        return ref == null
            ? roleLabel + " returned no test file twice"
            : roleLabel + " returned no test file twice; its last reply is kept as blob " + ref;
    }
}
