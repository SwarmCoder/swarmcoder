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

/**
 * A planner/analyst reply that was still not valid JSON after the one retry {@link LlmReplyRetry}
 * gives it. The message is already the honest, operator-facing sentence — who failed, twice, and
 * where the raw reply was kept — so a catcher that has nothing more to try hands it straight to
 * {@link GuidedFlows#fail}.
 *
 * <p>A catcher that DOES have something more to try — the analyst's own last-resort salvage of a
 * reply broken partway through, see {@code RequirementsIntake} — reads {@link #reply()} instead of
 * giving up: it is exactly the text the second attempt would not parse, so a field-by-field
 * best-effort read gets a real chance without asking the model a third time.
 */
final class MalformedReplyException extends Exception {

    private final String reply;

    MalformedReplyException(String message) {
        this(message, null);
    }

    /** @param reply the final (retry) reply that would not parse; null when a caller has none */
    MalformedReplyException(String message, String reply) {
        super(message);
        this.reply = reply;
    }

    /** The reply {@link #getMessage()} is about, for a caller with its own last resort. */
    String reply() {
        return reply;
    }
}
