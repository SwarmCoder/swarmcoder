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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one retry a guided-flow role gets when its reply will not read, shared by
 * {@link BacklogPlanning}, {@link RequirementsIntake} and {@link AutonomousAnswers} — the same
 * allowance {@code TestAuthorClient} and {@code ArchitectClient} give their own roles (commit
 * 642b6d7, 2026-09-03): the conversation so far, the bad reply as an assistant turn, and one user
 * turn naming exactly what the parser complained about. A second failure is final.
 *
 * <p>The caller supplies how to make the first call ({@code firstReply}, already in hand — the
 * planner and the analyst reach it by different paths, one of them with a search-tool round trip
 * in front of it, so this only ever repeats the SIMPLE two-turn shape) and how to read a reply into
 * its own shape ({@code reader}). Reading is the signal for retrying: {@code reader} throwing
 * {@link IOException} is what earns a reply the one retry, and what the honest final message
 * reports when it happens twice.
 *
 * <p><b>{@code reader} must be strict — parse only, never a fallback recovery.</b> A reader that
 * itself falls back to something short of a full parse (the analyst's own salvage of a reply
 * broken partway through, see {@code RequirementsIntake}) would report success on the very FIRST
 * reply whenever salvage found anything at all, and the retry this class exists to give would
 * never fire. That is exactly the bug this class fixed on 2026-09-03 (harness link 3: a reply
 * broken at column 14 salvaged one proposal from a 7,240-character document pair and was counted a
 * success). A caller with its own last-resort recovery reads {@link MalformedReplyException#reply()}
 * from the exception this throws after the SECOND failure, and tries it there — never inside
 * {@code reader}.
 */
final class LlmReplyRetry {

    private LlmReplyRetry() {}

    /** One already-built conversation turn back to the model, e.g. a second, simpler call. */
    @FunctionalInterface
    interface ModelCaller {
        String ask(List<Map<String, String>> messages) throws Exception;
    }

    /** How a reply is turned into the caller's shape; throws to signal "could not be read". */
    @FunctionalInterface
    interface Reader<T> {
        T read(String reply) throws IOException;
    }

    /** What survived: the value read, and the reply it was read from — the latter for a caller
     * that wants to continue the conversation further (an empty-but-valid proposal, re-asked). */
    record Asked<T>(T value, String reply) {}

    /**
     * @param blobs       where a reply kept after a second parse failure is stored; {@code null}
     *                    is treated as "store nothing"
     * @param roleLabel   who is answering, e.g. "the planner's" or "the analyst's" — the honest
     *                    message names it
     * @param conversation the system/user turns the first reply already answered, so the retry can
     *                    be appended to them (never rebuilt) as the assistant/user pair that follows
     * @param firstReply  the model's first reply — already made, by whatever means the caller uses
     * @param retryCaller how to make the (plain, non-tool) retry call
     * @param reader      turns a reply into {@code T}; an {@link IOException} is "not readable"
     */
    static <T> Asked<T> askJson(BlobStore blobs, String roleLabel, List<Map<String, String>> conversation,
                                String firstReply, ModelCaller retryCaller, Reader<T> reader)
            throws Exception {
        try {
            return new Asked<>(reader.read(firstReply), firstReply);
        } catch (IOException parseFailure) {
            List<Map<String, String>> retry = new ArrayList<>(conversation);
            retry.add(Map.of("role", "assistant", "content", firstReply == null ? "" : firstReply));
            retry.add(Map.of("role", "user", "content", "That was not valid JSON: "
                + parseFailure.getMessage() + ". Reply with only the JSON object."));
            String retryReply = retryCaller.ask(retry);
            try {
                return new Asked<>(reader.read(retryReply), retryReply);
            } catch (IOException secondFailure) {
                throw new MalformedReplyException(LlmReplyBlobs.describeFailure(blobs, roleLabel,
                    retryReply, secondFailure.getMessage()), retryReply);
            }
        }
    }
}
