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

import com.swarmcoder.store.BlobStore;

import java.nio.charset.StandardCharsets;

/**
 * Where a malformed reply from a guided-flow role goes once it gives up on it, and the honest
 * sentence naming where to find it — the console-side twin of {@code sc-workflow}'s
 * {@code LlmReplyBlobs} (commit 642b6d7, 2026-09-03), adapted to {@link BlobStore} because
 * sc-console does not depend on sc-verify (where that one's {@code BlobSink} lives) and already
 * carries its own blob store on {@link ConsoleContext#blobStore()}.
 *
 * <p>Before this, a reply that failed to parse was thrown away the moment it was caught, and the
 * failure message could only guess at why. The raw text is what an operator needs to see to tell
 * "the model sent garbage" from "our own prompt asked for the wrong shape"; keeping it, referenced
 * by a short blob id rather than logged inline (a reply can be arbitrarily long), is what makes
 * that diagnosis possible after the fact.
 */
final class LlmReplyBlobs {

    private LlmReplyBlobs() {}

    /**
     * Stores {@code reply} and returns a sentence naming what failed and where to read it. Never
     * the raw text itself — that is what the blob is for.
     *
     * @param blobs      where to store it; {@code null} when no blob store is wired (spike/test
     *                   contexts), in which case the parser's own message is used instead
     * @param roleLabel  who produced the reply, e.g. "the planner's" or "the analyst's"
     * @param reply      the raw reply that would not parse, twice
     * @param parseError the second parser's own message, used only when nothing could be stored
     */
    static String describeFailure(BlobStore blobs, String roleLabel, String reply, String parseError) {
        String ref = store(blobs, reply);
        return ref == null
            ? roleLabel + " reply was not valid JSON (twice): " + parseError
            : roleLabel + " reply was not valid JSON (twice); its reply is kept as blob " + ref;
    }

    /**
     * Stores {@code reply} and returns its blob id, or {@code null} when it could not be stored
     * (no blob store wired, or the write itself failed). Package-visible so a caller with its own
     * outcome sentence — the analyst's salvage of a reply broken partway through, see
     * {@code RequirementsIntake} — can cite the same blob without composing the "not valid JSON"
     * wording {@link #describeFailure} exists for, which does not fit a reply that partly worked.
     */
    static String store(BlobStore blobs, String reply) {
        if (blobs == null || reply == null) {
            return null;
        }
        try {
            return blobs.storeBlob(reply.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
