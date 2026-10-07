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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Browser error sink: {@code POST ./api/clientlog} forwards what the TeaVM client sees into the
 * server log, so a failure in the UI stops being invisible to whoever is watching the process.
 *
 * <p>Plain HTTP rather than the RMI socket, and that is the whole point of the design. RMI calls run
 * on zeroz4j green threads and throw "suspension point reached from non-threading context" when made
 * from a native-JS callback or a signal effect — which is exactly where browser errors surface, so an
 * RMI-based reporter would fail precisely when it was needed. {@code fetch} works from any context,
 * and it also means a log write can never block or corrupt the RMI connection the live views depend
 * on.
 *
 * <p><b>The body is untrusted.</b> This endpoint is unauthenticated (matching the rest of the
 * Console's HTTP surface, see {@link IngestResource}) and it writes attacker-influenced text into an
 * operator's log file, so two things are enforced here rather than assumed of the client:
 * <ul>
 *   <li>the body is capped at {@value #MAX_BODY_BYTES} bytes and truncated beyond it;</li>
 *   <li>CR, LF and every other control character are replaced with spaces, so a crafted message
 *       cannot forge additional log lines with a fake timestamp and level.</li>
 * </ul>
 *
 * <p><b>Rate limited</b> to {@value #MAX_PER_MINUTE} messages a minute. A render loop in the browser
 * is an ordinary bug, and without a ceiling it would fill the operator's disk from a page they may
 * not even be looking at. Excess messages are discarded and summarised in a single line.
 *
 * <p>Always answers {@code 204 No Content}, even when it drops the message or fails internally. An
 * error response would be something the client wants to report, and reporting it would come straight
 * back here — see {@code ClientLog} in sc-console-ui for the other half of that rule.
 */
@Path("/api/clientlog")
@ApplicationScoped
public class ClientLogResource {

    /**
     * Named to match {@code logback.xml}, which configures {@code com.swarmcoder.browser} separately
     * so a flood from one bad page can be turned down without silencing the server.
     */
    private static final Logger browser = LoggerFactory.getLogger("com.swarmcoder.browser");

    /** Generous enough for a stack trace, small enough that a loop cannot post megabytes. */
    static final int MAX_BODY_BYTES = 8 * 1024;

    /** A label, not a payload: view names and the like. */
    static final int MAX_CONTEXT_CHARS = 64;

    static final int MAX_PER_MINUTE = 200;

    private static final long WINDOW_NANOS = 60_000_000_000L;

    private static final Object GATE = new Object();
    private static long windowStart = System.nanoTime();
    private static int accepted;
    private static int dropped;

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response report(@QueryParam("level") String level,
                           @QueryParam("context") String context,
                           byte[] body) {
        try {
            String message = decode(body);
            if (!message.isEmpty() && admit()) {
                String label = sanitize(context, MAX_CONTEXT_CHARS);
                String prefix = label.isEmpty() ? "[browser] " : "[browser " + label + "] ";
                switch (level == null ? "" : level.trim().toLowerCase(Locale.ROOT)) {
                    case "error" -> browser.error("{}{}", prefix, message);
                    case "warn" -> browser.warn("{}{}", prefix, message);
                    // Anything else, including a missing level, is informational. Guessing "error"
                    // for a garbled parameter would let a typo cry wolf in the operator's log.
                    default -> browser.info("{}{}", prefix, message);
                }
            }
        } catch (RuntimeException e) {
            // Reporting a problem must never create one. Swallowed on purpose: the client cannot act
            // on it and telling it would invite a report about the failure to report.
            browser.warn("[browser] client log entry could not be recorded: {}", e.toString());
        }
        return Response.noContent().build();
    }

    /**
     * Byte cap first, then decode: cutting after decoding would mean holding an unbounded string in
     * memory to throw most of it away. A multi-byte character split by the cut decodes to the
     * replacement character, which is the correct outcome for a message that was already too long.
     */
    private static String decode(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        boolean truncated = body.length > MAX_BODY_BYTES;
        int length = truncated ? MAX_BODY_BYTES : body.length;
        String text = sanitize(new String(body, 0, length, StandardCharsets.UTF_8), Integer.MAX_VALUE);
        return truncated ? text + " ...(truncated)" : text;
    }

    /**
     * Flattens the value to a single log-safe line. Control characters become spaces rather than
     * being deleted, so a stack trace stays readable as one line instead of running its frames
     * together, and no arrangement of newlines can produce a second log record.
     */
    static String sanitize(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int length = Math.min(value.length(), maxChars);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            out.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return out.toString().trim();
    }

    /**
     * Fixed one-minute window. Returns whether this message may be logged; when a window that dropped
     * messages rolls over, emits exactly one line saying how many were lost, so the gap in the log is
     * visible rather than silent.
     */
    private static boolean admit() {
        int lost = 0;
        boolean allowed;
        synchronized (GATE) {
            long now = System.nanoTime();
            if (now - windowStart >= WINDOW_NANOS) {
                lost = dropped;
                windowStart = now;
                accepted = 0;
                dropped = 0;
            }
            allowed = accepted < MAX_PER_MINUTE;
            if (allowed) {
                accepted++;
            } else {
                dropped++;
            }
        }
        if (lost > 0) {
            browser.warn("[browser] rate limit: dropped {} client log message(s) in the previous minute", lost);
        }
        return allowed;
    }
}
