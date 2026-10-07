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

import com.zeroz4j.client.WasmRmiClient;
import com.zeroz4j.ui.component.Alert;
import com.zeroz4j.client.Zeroz4jClient;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLElement;
import com.swarmcoder.console.api.ChatStreamDto;
import com.swarmcoder.console.api.RunGraphDto;
import com.zeroz4j.api.RmiSecurityContext;

/**
 * SwarmCoder Console entry point (docs/OBSERVABILITY_DESIGN.md). Connects the binary RMI
 * WebSocket, subscribes the live push topics, and mounts the main view.
 */
public final class ConsoleApp {

    /**
     * How long the console may take to appear before the watchdog calls it a failure. Generous on
     * purpose: a cold WebSocket handshake plus the AUTH round-trip is normally well under a second,
     * so anything past this is not slowness, it is a connection that is never going to arrive.
     */
    private static final int MOUNT_DEADLINE_MS = 8000;

    /** Set once the view is in the DOM, so the watchdog can tell "slow" from "never happened". */
    private static boolean mounted;

    public static void main(String[] args) {
        // First statement in the client, before anything can fail: from here on an uncaught error or
        // a rejected promise reaches the server log instead of a devtools console nobody has open.
        ClientLog.install();

        // The "swarm-sessions" and "swarm-events" feeds are NOT registered. Their only subscriber was
        // the global swarm board, and that is deleted (UX v3 6): workers live inside the build they
        // belong to, reached from the story's own card, so a process-wide board reading "No agent
        // sessions yet" beside a line claiming three runs were in flight had no reader and no purpose.
        // Registering a listener that publishes into a signal nobody reads is a push per event for
        // nothing, so the registration goes with the view it fed.
        // Chat topics land in the ChatStore signals (design §4.2). Unlike the two feeds above these
        // call their stores directly, so nothing between them and zeroz4j's dispatcher reports a
        // failure — see pushFailed for why the dispatcher's own handling is not enough.
        WasmRmiClient.registerPushListener("chat-events",
            (com.swarmcoder.domain.ChatMessage message) -> {
                try {
                    ChatStore.onMessage(message);
                } catch (RuntimeException e) {
                    pushFailed("chat-events", "a message is missing from the open chat", e);
                }
            });
        WasmRmiClient.registerPushListener("chat-stream",
            (ChatStreamDto delta) -> {
                try {
                    ChatStore.onStream(delta);
                } catch (RuntimeException e) {
                    pushFailed("chat-stream", "the streaming reply has stalled mid-sentence", e);
                }
            });
        WasmRmiClient.registerPushListener("chat-list",
            (com.swarmcoder.domain.ChatSession chat) -> {
                try {
                    ChatStore.onChatListChanged(chat);
                } catch (RuntimeException e) {
                    pushFailed("chat-list", "the sidebar chat list is out of date", e);
                }
            });
        WasmRmiClient.registerPushListener("run-graph",
            (RunGraphDto graph) -> {
                try {
                    GraphStore.onGraph(graph);
                    MainView.refreshRuns(); // run state changes reflect in the sidebar list too
                } catch (RuntimeException e) {
                    pushFailed("run-graph", "the run graph and the Runs list are frozen at their "
                        + "last good state", e);
                }
            });

        // Local single-operator tool: authenticate as the built-in dev admin so the @Secured
        // Console services are reachable (the server runs zeroz.security.mode=dev). Without
        // credentials the session is anonymous and every secured call is denied — no data loads.
        //
        // The endpoint URL comes from the framework rather than from a hand-written one built out
        // of window.location. Both of the obvious hand-written forms are wrong somewhere:
        // location.host + "/wasm-rmi" breaks under a context path, and stripping the last segment
        // off the path breaks on any deep link.
        Zeroz4jClient.connect(Zeroz4jClient.defaultWebSocketUrl() + "?user=admin&password=admin",
            () -> RmiSecurityContext.onAuthenticated(() -> {
                try {
                    MainView mainView = new MainView();
                    HTMLElement appRoot = Window.current().getDocument().getElementById("app-root");
                    appRoot.appendChild(mainView.getElement());
                    mounted = true;
                } catch (RuntimeException e) {
                    // This runs on a zeroz4j green thread, where a thrown exception does not
                    // necessarily reach window.onerror — it can be absorbed by the scheduler and the
                    // page just stays blank. Report it explicitly.
                    ClientLog.error("ConsoleApp", "console failed to mount: " + e);
                    throw e;
                }
            }));

        // A refused sign-in used to be indistinguishable from a slow one. There was no failure
        // hook of any kind: connect() took a success Runnable and nothing else, and
        // onAuthenticated fired on success and stayed silent for ever otherwise, so a wrong
        // password, a server that was not up, and a socket the browser refused all produced the
        // same blank page with no report anywhere. 0.6.0 added onAuthenticationFailed — and made
        // the answer honest at the same time: before it, the server sent an AUTH frame on EVERY
        // connection and the client believed it, so a REFUSED sign-in looked authenticated and the
        // console mounted anyway, against services that would then deny every call it made.
        RmiSecurityContext.onAuthenticationFailed(() -> {
            ClientLog.error("ConsoleApp", "the server refused the console sign-in, so every "
                + "secured call would be denied and no data can load");
            showBootFailure("The server refused this console sign-in, so nothing can load. The "
                + "console process is running in a mode that does not accept its credentials — "
                + "restart it, then reload this page.");
        });

        // The watchdog stays, for what neither callback above can see: a socket that never opens
        // at all, and a server that answers neither way. Silence cannot be told from slowness
        // except by waiting.
        Window.setTimeout(ConsoleApp::checkMounted, MOUNT_DEADLINE_MS);
    }

    private static void checkMounted() {
        if (mounted) {
            return;
        }
        ClientLog.error("ConsoleApp", "the console did not mount within " + (MOUNT_DEADLINE_MS / 1000)
            + "s — the RMI WebSocket never opened or dev-auth never completed, so the page is blank "
            + "and no data will load");
        showBootFailure("SwarmCoder Console could not connect to the server. The page will stay "
            + "empty until the connection succeeds — check that the console process is running, "
            + "then reload.");
    }

    /**
     * Puts the failure where the person staring at the blank page will see it.
     *
     * <p>An {@link Alert} now, rather than the hand-written JavaScript this used to be. The old
     * note said it must not depend on anything the bootstrap was supposed to set up — still true,
     * and still satisfied: a component is Java that builds an element, with no connection, no
     * signal and no view behind it. The empty-root check keeps a slow-but-successful mount from
     * being defaced.
     */
    private static void showBootFailure(String message) {
        HTMLElement root = Window.current().getDocument().getElementById("app-root");
        if (root == null || root.getChildNodes().getLength() > 0) {
            return;
        }
        Alert alert = new Alert(message, "alert-error");
        alert.addClassName("m-8 text-sm");
        root.appendChild(alert.getElement());
    }

    /**
     * Reports a push handler that threw. zeroz4j does catch these, but it catches them into
     * {@code System.err} — a devtools line in the compiled client — and carries on, so from the
     * outside the topic simply goes quiet with no trace. Nothing is rethrown here either: a single
     * unusable frame must not cost the topic its remaining listeners.
     */
    private static void pushFailed(String topic, String consequence, RuntimeException e) {
        ClientLog.error("ConsoleApp", "live push '" + topic + "' failed to apply — " + consequence
            + " until the next good update: " + e);
    }

}
