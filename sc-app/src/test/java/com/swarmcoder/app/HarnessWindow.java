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
package com.swarmcoder.app;

import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.McpApiConfig;
import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.console.ControlServiceImpl;
import com.swarmcoder.console.GraphServiceImpl;
import com.swarmcoder.console.ObserverServiceImpl;
import com.swarmcoder.domain.Project;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.server.mcp.SwarmMcpServer;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.BlobStore;
import com.zeroz4j.server.Zeroz4jServer;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * <b>A harness run you can watch while it happens: the product's own Console and its own MCP
 * server, over the harness's temporary store.</b>
 *
 * <p>Written 2026-09-25. Until then {@link EndToEndLoopTest} ran the real product against a store in
 * a temp directory and started neither of the two things the product uses to show a run — so the
 * operator watching a forty-minute walk read its log, and the Claude session driving the harness,
 * whose MCP client is already pointed at {@code http://127.0.0.1:8931/mcp}, got "connection
 * refused" and read the log too. Everything needed to show the run already existed: the harness
 * builds a {@link ConsoleContext} and a {@link TraceHub} shared with the swarm, and the Console and
 * the MCP server both read nothing else. This class only binds them.
 *
 * <h2>What starts, and on which ports</h2>
 *
 * <ul>
 *   <li><b>The Console</b>, through {@link DependencyGraph#bindConsole} — the app's own start-up,
 *       not a copy of it — on {@code -Dswarmcoder.e2e.consolePort} (default
 *       {@value ConfigLoader#DEFAULT_CONSOLE_PORT}).</li>
 *   <li><b>The MCP server</b>, {@link SwarmMcpServer} on loopback only, READ-ONLY, on
 *       {@code -Dswarmcoder.e2e.mcpPort} (default {@value McpApiConfig#DEFAULT_PORT}).</li>
 * </ul>
 *
 * <p><b>The app's own defaults, on purpose.</b> The operator never runs the app during a harness
 * run — the two would compete for the one model server — so those ports are free exactly when this
 * wants them, the bookmark in the browser works, and the Claude session's MCP client needs no
 * reconfiguring. When one IS taken (the app was left running, or two harness runs overlap), a loud
 * {@code [E2E] !!!} line says so and the run carries on without that surface. Observability never
 * fails the chain: a harness that broke because a port was busy would be measuring the machine.
 *
 * <h2>On by default — why that weakens nothing</h2>
 *
 * <p>{@code -Dswarmcoder.e2e.observe=false} turns it off; it is on otherwise. The harness is only
 * ever run on purpose, by somebody who then wants to know what it is doing, and a flag nobody
 * remembers to type is the failure this repository's test gates were rewritten to remove
 * (docs/TESTING.md). Against the two things the harness promises:
 *
 * <ul>
 *   <li><b>No paid call.</b> The Console is served over the harness's own ConsoleContext, whose
 *       only model clients are the harness's free local endpoint. No settings file is read or
 *       written (the context has no settings seam), and the chat, whose model the operator's config
 *       would name, is not wired at all.</li>
 *   <li><b>Isolation.</b> Everything shown is the harness's temp store; nothing under
 *       {@code ~/.swarmcoder} is opened. The MCP server binds loopback only, as it always does.
 *       Nothing else in the ordinary build calls this — only the live harness, which is itself
 *       gated off by default.</li>
 * </ul>
 *
 * <h2>Watch only</h2>
 *
 * <p>The harness drives its run itself and measures it. A browser click that started a second
 * build, answered the run's question, or ran the analyst again would change what is measured, and
 * nothing in the verdict would say so. So:
 *
 * <ul>
 *   <li>The MCP server is built read-only: {@code start_run}, {@code decide_run} and
 *       {@code answer_decision} are not offered at all.</li>
 *   <li>The ConsoleContext is {@link ConsoleContext#watchOnly}: a browser asking to start a build,
 *       approve or reject one, answer a question, run a wizard, change a rule, switch on unattended
 *       building, or create or delete a project is refused with the reason on screen. The
 *       harness's own calls go through the same services and are not refused, because what tells
 *       them apart is that a browser's call arrives through the RMI channel (see
 *       {@link ConsoleContext#watchOnly}).</li>
 *   <li>Approve and reject are also no-ops in the harness's wiring, as they always were: the
 *       harness never approves anything.</li>
 * </ul>
 *
 * <h2>How long it stays up</h2>
 *
 * <p>From the moment the harness has wired itself until the walk ends — on a verdict or a chain
 * break alike — then both are closed so the ports are free for the next run. The store lives in a
 * test temp directory that is deleted at the end, so once the walk is over there is nothing left
 * to look at; {@code -Dswarmcoder.e2e.holdMinutes=N} keeps both up N more minutes after the chain
 * report is printed, with the store still open, for looking round a broken run in the browser.
 * Default 0.
 */
final class HarnessWindow implements AutoCloseable {

    static final String OBSERVE_PROPERTY = "swarmcoder.e2e.observe";
    static final String CONSOLE_PORT_PROPERTY = "swarmcoder.e2e.consolePort";
    static final String MCP_PORT_PROPERTY = "swarmcoder.e2e.mcpPort";
    static final String HOLD_PROPERTY = "swarmcoder.e2e.holdMinutes";

    /** What a refused click says, after "You cannot start a build here: ". */
    static final String WATCH_ONLY_REASON = "this console is watching an end-to-end harness run, "
        + "and the harness drives that run itself — acting on it here would change what it measures";

    private final boolean wanted;
    private final int consolePort;
    private final int mcpPort;
    private final long holdMillis;
    private final Consumer<String> say;

    private Zeroz4jServer console;
    private SwarmMcpServer mcp;
    private String consoleUrl;
    private String mcpUrl;
    private Runnable beforeHolding = () -> { };

    HarnessWindow(boolean wanted, int consolePort, int mcpPort, long holdMinutes,
                  Consumer<String> say) {
        this.wanted = wanted;
        this.consolePort = consolePort;
        this.mcpPort = mcpPort;
        this.holdMillis = Math.max(0, holdMinutes) * 60_000;
        this.say = say;
    }

    /** What the command line asked for. Opens nothing yet; see {@link #open()}. */
    static HarnessWindow fromProperties(Consumer<String> say) {
        return new HarnessWindow(
            !"false".equalsIgnoreCase(System.getProperty(OBSERVE_PROPERTY, "true").trim()),
            Integer.getInteger(CONSOLE_PORT_PROPERTY, ConfigLoader.DEFAULT_CONSOLE_PORT),
            Integer.getInteger(MCP_PORT_PROPERTY, McpApiConfig.DEFAULT_PORT),
            Long.getLong(HOLD_PROPERTY, 0L), say);
    }

    /**
     * The ConsoleContext a harness run is shown through: its store, its hub, its blobs, its one
     * project listed and selected, and watch-only. The harness adds its own seams (the story-run
     * starter, the rules, the analyst and planner) to what this returns.
     *
     * <p>The project is LISTED, not only selected. Before 2026-09-25 the harness's context listed
     * no projects at all — nothing had ever rendered it — and the Console's rail, which is built
     * from the list alone, would have shown an empty machine with a build running in it.
     */
    static ConsoleContext consoleOver(ArtifactStore store, TraceHub traceHub, BlobStore blobs,
                                      UUID projectId, BiFunction<String, String, UUID> intake) {
        return new ConsoleContext(store, traceHub, blobs, intake, runId -> { }, runId -> { })
            .withProjects(() -> {
                    Project project = store.getProject(projectId);
                    return project == null ? List.of() : List.of(project);
                }, () -> projectId, (name, path, contextPaths) -> null, id -> { })
            .watchOnly(WATCH_ONLY_REASON);
    }

    /**
     * Binds the Console and the MCP server over the {@link ConsoleContext} already installed, and
     * prints where they are. Never throws: a surface that cannot start is announced and skipped.
     */
    void open() {
        if (!wanted) {
            say.accept("[E2E] not serving the Console or the MCP server (-D" + OBSERVE_PROPERTY
                + "=false): this run can be followed in the log only");
            return;
        }
        try {
            console = DependencyGraph.bindConsole(consolePort);
            if (console.port() <= 0) {
                // Measured 2026-09-25: on a taken port Zeroz4jServer.start does NOT throw. Helidon
                // logs "Failed to start listener … shutting down" and start() returns a server
                // whose port() is -1, so without this check the line below would have told the
                // operator to open http://localhost:-1/.
                throw new IllegalStateException("the web server started but bound no port — "
                    + "Helidon logged why just above; most likely the port is taken");
            }
            consoleUrl = "http://localhost:" + console.port() + "/";
            say.accept("[E2E] >>> WATCH IN A BROWSER: " + consoleUrl
                + "   (this run's own store; watch-only)");
        } catch (Throwable e) {
            if (console != null) {
                try {
                    console.close();
                } catch (Exception ignored) {
                    // it never served anything; there is nothing to release cleanly
                }
            }
            console = null;
            say.accept("[E2E] !!! NO BROWSER VIEW: the Console could not open port " + consolePort
                + " (" + rootMessage(e) + "). Is SwarmCoder itself still running? The run "
                + "carries on without it; another port: -D" + CONSOLE_PORT_PROPERTY + "=<port>");
        }
        try {
            // Built directly rather than taken from the Console's CDI container, as the app does,
            // so the MCP view survives the Console's port being taken. It changes nothing about
            // what is answered: all three are stateless readers of the one installed
            // ConsoleContext, and the app's container beans are these same classes.
            SwarmMcpServer server = new SwarmMcpServer(new ObserverServiceImpl(),
                new GraphServiceImpl(), new ControlServiceImpl(), mcpPort, "", true);
            mcpUrl = server.start();
            mcp = server;
            say.accept("[E2E] >>> WATCH OVER MCP (read-only): " + mcpUrl);
        } catch (Throwable e) {
            mcp = null;
            say.accept("[E2E] !!! NO MCP VIEW: the MCP server could not open 127.0.0.1:" + mcpPort
                + " (" + rootMessage(e) + "). Is SwarmCoder itself still running? The run "
                + "carries on without it; another port: -D" + MCP_PORT_PROPERTY + "=<port>");
        }
    }

    /** Runs just before a hold begins — the harness prints its chain report there. */
    void beforeHolding(Runnable action) {
        this.beforeHolding = action == null ? () -> { } : action;
    }

    /** The browser address, or null when the Console is not up. */
    String consoleUrl() {
        return console == null ? null : consoleUrl;
    }

    /** The MCP address, or null when the MCP server is not up. */
    String mcpUrl() {
        return mcp == null ? null : mcpUrl;
    }

    /** Holds if asked, then closes both. Idempotent. */
    @Override
    public void close() {
        if ((console != null || mcp != null) && holdMillis > 0) {
            beforeHolding.run();
            say.accept("[E2E] holding the Console and the MCP server open for "
                + holdMillis / 60_000 + " minute(s) for a look round (-D" + HOLD_PROPERTY
                + "); stop the test to end it sooner");
            try {
                Thread.sleep(holdMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mcp != null) {
            try {
                mcp.stop();
            } catch (Exception e) {
                say.accept("[E2E] the MCP server did not stop cleanly: " + e);
            }
            mcp = null;
        }
        if (console != null) {
            try {
                console.close();
            } catch (Exception e) {
                say.accept("[E2E] the Console did not stop cleanly: " + e);
            }
            console = null;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? String.valueOf(e) : e.getClass().getSimpleName() + " / " + root;
    }
}
