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
package com.swarmcoder.lsp;

import java.util.List;

/**
 * What a language-server query answered: a status, one sentence, and a short capped list of
 * places. Facade-clean - nothing of LSP4J in it.
 *
 * @param status whether there is an answer, and if not, which kind of "no"
 * @param note   one or a few sentences: the heading of the answer, or why there is none
 * @param hits   the places, already capped
 * @param total  how many there were before the cap
 */
public record LspResult(Status status, String note, List<LspHit> hits, int total) {

    public enum Status {
        /** Answered; {@code hits} may be empty when the true answer is "none". */
        OK,
        /** No language server is installed, or it did not start, or it stopped. */
        NOT_AVAILABLE,
        /** The server runs, and knows no symbol or file of that name. */
        NOT_FOUND,
        /** The name fits several symbols; {@code hits} lists them. */
        AMBIGUOUS,
        /** The server runs and the request failed or was refused. */
        FAILED
    }

    public LspResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        note = note == null ? "" : note;
    }

    public static LspResult ok(String note, List<LspHit> hits, int total) {
        return new LspResult(Status.OK, note, hits, total);
    }

    public static LspResult unavailable(String why) {
        return new LspResult(Status.NOT_AVAILABLE, "The Java language server is not available"
            + (why == null || why.isBlank() ? "." : ": " + why + "."), List.of(), 0);
    }

    public static LspResult notFound(String note) {
        return new LspResult(Status.NOT_FOUND, note, List.of(), 0);
    }

    public static LspResult failed(String note) {
        return new LspResult(Status.FAILED, note, List.of(), 0);
    }

    /** True when the server answered the question, even with "none". */
    public boolean answered() {
        return status == Status.OK;
    }

    /** The note, then one line per place, then how many were left out. */
    public String render() {
        StringBuilder sb = new StringBuilder(note);
        for (LspHit hit : hits) {
            sb.append('\n').append(hit.render());
        }
        if (total > hits.size()) {
            sb.append("\n(").append(total - hits.size()).append(" more not shown)");
        }
        return sb.append('\n').toString();
    }
}
