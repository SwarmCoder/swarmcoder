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
package com.swarmcoder.server.mcp;

/**
 * How much text one MCP answer may carry, and what the caller is told when it is cut.
 *
 * <p>This is the lesson of DEVELOPER_CORRECTIONS.md §18 applied to a second wire. There, one
 * oversized console reply did not fail — it closed the socket, and the operator's screen went blank
 * with nothing saying why. The fix was to send names and sizes and fetch bodies on demand.
 *
 * <p>An MCP reply has exactly that shape and worse consequences: a run's trace can be tens of
 * thousands of events, each with a payload that was moved to the blob store <em>because</em> it was
 * too big to inline. Sent whole, one {@code session_events} call would fill the caller's entire
 * context window and it would have no way to know that is what happened.
 *
 * <p>So every tool here obeys three rules:
 * <ul>
 *   <li><b>Rows are paged</b> — a bounded number, with the total and the next offset returned.</li>
 *   <li><b>Payloads are snippets</b> — the full text is fetched by {@code blob_ref} on demand.</li>
 *   <li><b>Anything cut says so, in the reply</b> — never silently, and always with the call that
 *       fetches the rest.</li>
 * </ul>
 */
public final class ReplyBudget {

    /**
     * The most characters any one tool reply may be, whole. About fifteen thousand tokens — big
     * enough for a ten-worker run's diagnosis, small enough that a caller can afford several calls
     * in one conversation.
     */
    public static final int MAX_REPLY_CHARS = 60_000;

    /** The most characters one text window ({@code blob_text}, {@code event_payload}) returns. */
    public static final int MAX_WINDOW_CHARS = 20_000;

    /** How much of a trace payload rides along in a list of events. */
    public static final int SNIPPET_CHARS = 400;

    /** The most trace events one {@code session_events} page returns. */
    public static final int MAX_EVENTS_PER_PAGE = 200;

    /** The default page of trace events when the caller does not ask for a size. */
    public static final int DEFAULT_EVENTS_PER_PAGE = 50;

    /** The most rows any listing returns in one call. */
    public static final int MAX_ROWS = 200;

    private ReplyBudget() { }

    /** Cuts a snippet to {@link #SNIPPET_CHARS} with a visible ellipsis. */
    public static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.strip();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + " …";
    }

    /**
     * Cuts a whole tool reply that came out over budget, replacing the tail with a sentence saying
     * so. The last resort — every tool is meant to page itself so this never fires.
     */
    public static String clampReply(String text, String narrowerCall) {
        if (text == null || text.length() <= MAX_REPLY_CHARS) {
            return text == null ? "" : text;
        }
        return text.substring(0, MAX_REPLY_CHARS)
            + "\n\n[CUT. This answer was " + text.length() + " characters and only the first "
            + MAX_REPLY_CHARS + " are here, so the rest is missing and nothing below this line is "
            + "reliable. Ask again with " + narrowerCall + ".]";
    }

    /** A window of a long text, with the arithmetic the caller needs to ask for the next one. */
    public record Window(String text, int offset, int returned, int total, int nextOffset) {
        public boolean more() {
            return nextOffset < total;
        }
    }

    /**
     * Takes at most {@link #MAX_WINDOW_CHARS} from {@code offset}. An offset past the end returns
     * an empty window rather than an error: the caller is paging, and running off the end is how
     * paging finishes.
     */
    public static Window window(String text, int offset, int max) {
        String whole = text == null ? "" : text;
        int from = Math.max(0, Math.min(offset, whole.length()));
        int size = Math.max(1, Math.min(max <= 0 ? MAX_WINDOW_CHARS : max, MAX_WINDOW_CHARS));
        int to = Math.min(whole.length(), from + size);
        return new Window(whole.substring(from, to), from, to - from, whole.length(), to);
    }

    /** Clamps a caller's requested row count into what a listing will actually return. */
    public static int rows(int asked, int fallback) {
        int wanted = asked <= 0 ? fallback : asked;
        return Math.max(1, Math.min(wanted, MAX_ROWS));
    }
}
