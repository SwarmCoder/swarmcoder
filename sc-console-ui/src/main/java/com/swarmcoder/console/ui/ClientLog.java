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

import org.teavm.jso.JSBody;

/**
 * Sends browser-side failures to the server log, so a broken view stops being invisible to whoever is
 * watching the process. Without this, an exception in the compiled TeaVM client lands in a devtools
 * console nobody has open and the UI simply appears to do nothing.
 *
 * <p>The reports go to the plain HTTP endpoint {@code POST ./api/clientlog}, not over the RMI socket,
 * and that is the load-bearing part of the design. RMI calls run on zeroz4j green threads and throw
 * "suspension point reached from non-threading context" when made from a native-JS callback or a
 * signal effect — which is exactly where these errors arise, so an RMI reporter would fail in
 * precisely the situations it exists for. {@code fetch} works from any context, and it keeps a log
 * write from ever blocking or corrupting the RMI connection the live views depend on.
 *
 * <p><b>The loop hazard is the thing to get right.</b> A failure to send a report must never itself
 * be reported: that is an infinite loop that saturates the network and the disk. So the {@code fetch}
 * has an empty {@code .catch}, the server always answers 204, and the number of reports per page load
 * is capped.
 */
final class ClientLog {

    private ClientLog() {}

    /**
     * Installs the shared reporter and the global handlers, once. Call this as early as possible —
     * before any view is constructed — so a failure during construction is caught too.
     *
     * <p>{@code window.__scLog} mirrors {@code Uploader}'s {@code window.__scUpload} idiom: one
     * function defined once, so URL construction and the loop guards exist in exactly one place.
     */
    @JSBody(script =
        "if (!window.__scLog) {"
        // Cap per page load. A render loop that throws on every frame must not be able to hammer
        // the endpoint; the server rate-limits too, but stopping at the source costs nothing.
        + "  var sent = 0;"
        + "  var LIMIT = 100;"
        + "  window.__scLog = function (level, message, context) {"
        + "    var text = '' + message;"
        + "    try {"
        // Always the devtools console as well, so debugging still works when the server is
        // unreachable — which is one of the failures worth reporting in the first place.
        + "      var sink = level === 'error' ? console.error"
        + "        : (level === 'warn' ? console.warn : console.log);"
        + "      sink.call(console, '[sc' + (context ? ' ' + context : '') + '] ' + text);"
        + "    } catch (ignored) {}"
        + "    if (sent >= LIMIT) { return; }"
        + "    sent++;"
        + "    if (sent === LIMIT) {"
        + "      text = text + ' (further client log messages suppressed for this page load)';"
        + "    }"
        + "    try {"
        + "      var url = './api/clientlog?level=' + encodeURIComponent(level);"
        + "      if (context) { url += '&context=' + encodeURIComponent(context); }"
        // keepalive: a report raised while the page is unloading still has to go out, and an
        // ordinary fetch is cancelled with the document.
        + "      fetch(url, { method: 'POST', body: text, keepalive: true })"
        + "        .catch(function () {"
        // DELIBERATELY EMPTY, do not add logging here. Anything written in this handler would
        // itself be a client log message, which would fail the same way and re-enter here: one
        // unreachable server would become an unbounded loop of retries.
        + "        });"
        + "    } catch (ignored) {}"
        + "  };"
        + "  var previousOnError = window.onerror;"
        + "  window.onerror = function (message, source, line, column, error) {"
        + "    try {"
        + "      var detail = (error && error.stack) ? error.stack : ('' + message);"
        + "      var at = source ? (' @ ' + source + ':' + line + ':' + column) : '';"
        + "      window.__scLog('error', detail + at, 'onerror');"
        // Swallowed on purpose: an exception thrown out of onerror re-enters onerror.
        + "    } catch (ignored) {}"
        + "    if (previousOnError) { return previousOnError(message, source, line, column, error); }"
        + "    return false;"
        + "  };"
        // A rejected promise with no handler is the quiet half of this problem: nothing is thrown,
        // nothing reaches onerror, and the UI just stops mid-operation.
        + "  window.addEventListener('unhandledrejection', function (event) {"
        + "    try {"
        + "      var reason = event ? event.reason : null;"
        + "      var detail = (reason && reason.stack) ? reason.stack : ('' + reason);"
        + "      window.__scLog('error', 'unhandled rejection: ' + detail, 'rejection');"
        + "    } catch (ignored) {}"
        + "  });"
        + "}")
    static native void install();

    @JSBody(params = {"level", "message", "context"}, script =
        "if (window.__scLog) { window.__scLog(level, message, context); }")
    private static native void send(String level, String message, String context);

    /** Reports a failure the operator needs to see in the server log. */
    static void error(String message) {
        send("error", message, null);
    }

    /**
     * Reports a failure, tagged with a short label — a view name — so the log says where in the UI
     * it happened without the message having to spell it out.
     */
    static void error(String context, String message) {
        send("error", message, context);
    }

    static void warn(String message) {
        send("warn", message, null);
    }

    static void warn(String context, String message) {
        send("warn", message, context);
    }

    static void info(String message) {
        send("info", message, null);
    }

    static void info(String context, String message) {
        send("info", message, context);
    }
}
