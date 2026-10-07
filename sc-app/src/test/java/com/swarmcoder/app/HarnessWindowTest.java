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

import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.BlobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A harness run can be watched: the Console and the MCP server come up over the harness's own
 * store and show its run, and a port somebody else holds costs a loud line and nothing more
 * (2026-09-25).
 *
 * <p>No model and no browser. The live harness cannot be run to prove this — it needs the model
 * server, which is busy — and nothing here depends on a model: what is being proved is that the
 * wiring {@link EndToEndLoopTest} uses ({@link DependencyGraph#traceHubOver},
 * {@link HarnessWindow#consoleOver}, {@link HarnessWindow#open}) serves a stored run through both
 * surfaces. So a run is put in a temp store the way the workflow persists one, both servers are
 * started on ports the operating system picks (the app's own 9090 and 8931 may be in use on this
 * machine), the Console's root page is fetched, and the MCP server is spoken to exactly as a
 * client speaks to it — initialize, then {@code tools/list} and {@code tools/call} — over plain
 * HTTP. One browser test per JVM is the rule in this repository; there is no browser here.
 */
class HarnessWindowTest {

    @TempDir
    Path tmp;

    private final List<String> said = new CopyOnWriteArrayList<>();

    @AfterEach
    void uninstall() {
        ConsoleContext.set(null);
    }

    @Test
    void aStoredRunIsShownInTheConsoleAndOverReadOnlyMcpThenThePortsAreGivenBack()
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(tmp.resolve("store"))) {
            Project project = store.ensureProject("bookshelf", tmp.resolve("repo").toString(),
                List.of());
            UUID runId = UUID.randomUUID();
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING, project.id(),
                null, null, null, null, Instant.now(), new RunReport(runId, "rate a book"));
            store.append(() -> {
                store.root().runs.put(runId, run);
                return null;
            }).get();

            TraceHub hub = DependencyGraph.traceHubOver(store,
                new BlobStore(tmp.resolve("blobs")), null);
            ConsoleContext.set(HarnessWindow.consoleOver(store, hub,
                new BlobStore(tmp.resolve("blobs")), project.id(), (goal, kind) -> {
                    throw new AssertionError("nothing in this test starts a run");
                }));

            int consolePort;
            int mcpPort;
            try (HarnessWindow window = new HarnessWindow(true, 0, 0, 0, said::add)) {
                window.open();

                assertThat(window.consoleUrl()).describedAs(String.join("\n", said))
                    .startsWith("http://localhost:");
                assertThat(window.mcpUrl()).startsWith("http://127.0.0.1:").endsWith("/mcp");
                assertThat(said)
                    .describedAs("each address on its own [E2E] line, easy to spot in the log")
                    .anyMatch(line -> line.startsWith("[E2E] >>> WATCH IN A BROWSER: "
                        + window.consoleUrl()))
                    .anyMatch(line -> line.startsWith("[E2E] >>> WATCH OVER MCP (read-only): "
                        + window.mcpUrl()));
                consolePort = URI.create(window.consoleUrl()).getPort();
                mcpPort = URI.create(window.mcpUrl()).getPort();

                HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
                HttpResponse<String> page = http.send(
                    HttpRequest.newBuilder(URI.create(window.consoleUrl())).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertThat(page.statusCode()).isEqualTo(200);
                assertThat(page.body()).contains("SwarmCoder Console");

                String mcp = window.mcpUrl();
                assertThat(rpc(http, mcp, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                      "protocolVersion":"2025-06-18","capabilities":{},
                      "clientInfo":{"name":"harness-window-test","version":"1"}}}""").body())
                    .contains("\"serverInfo\"");
                assertThat(rpc(http, mcp,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode())
                    .isEqualTo(202);

                String tools = rpc(http, mcp,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").body();
                assertThat(tools).contains("list_runs").contains("swarm_status");
                assertThat(tools)
                    .describedAs("read-only: an outside agent must not be able to start, decide "
                        + "or answer anything in a run the harness is measuring")
                    .doesNotContain("start_run")
                    .doesNotContain("decide_run")
                    .doesNotContain("answer_decision");

                String runs = rpc(http, mcp, """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call",
                     "params":{"name":"list_runs","arguments":{}}}""").body();
                assertThat(runs)
                    .describedAs("the harness's own run, from the harness's own store")
                    .contains(runId.toString())
                    .doesNotContain("\"isError\":true");

                String projects = rpc(http, mcp, """
                    {"jsonrpc":"2.0","id":4,"method":"tools/call",
                     "params":{"name":"list_projects","arguments":{}}}""").body();
                assertThat(projects)
                    .describedAs("the project is listed, not only selected: the Console's rail is "
                        + "built from the list alone")
                    .contains(project.id().toString())
                    .contains("bookshelf");
            }

            // Closed: both ports are free again for the next run.
            assertThat(canBind(consolePort)).describedAs("the Console's port is released").isTrue();
            assertThat(canBind(mcpPort)).describedAs("the MCP port is released").isTrue();
        }
    }

    @Test
    void aTakenPortIsSaidLoudlyAndTheRunCarriesOnWithoutThatView() throws Exception {
        try (ArtifactStore store = new ArtifactStore(tmp.resolve("store"));
             ServerSocket consoleSquatter = squat();
             ServerSocket mcpSquatter = squat()) {
            Project project = store.ensureProject("bookshelf", tmp.resolve("repo").toString(),
                List.of());
            ConsoleContext.set(HarnessWindow.consoleOver(store, new TraceHub(null), null,
                project.id(), (goal, kind) -> null));
            int consolePort = consoleSquatter.getLocalPort();
            int mcpPort = mcpSquatter.getLocalPort();

            HarnessWindow window = new HarnessWindow(true, consolePort, mcpPort, 0, said::add);
            window.open();   // must not throw: observability never fails the chain
            window.close();

            assertThat(window.consoleUrl()).isNull();
            assertThat(window.mcpUrl()).isNull();
            assertThat(said)
                .describedAs(String.join("\n", said))
                .anyMatch(line -> line.startsWith("[E2E] !!! NO BROWSER VIEW")
                    && line.contains(String.valueOf(consolePort))
                    && line.contains("-D" + HarnessWindow.CONSOLE_PORT_PROPERTY))
                .anyMatch(line -> line.startsWith("[E2E] !!! NO MCP VIEW")
                    && line.contains(String.valueOf(mcpPort))
                    && line.contains("-D" + HarnessWindow.MCP_PORT_PROPERTY));

            // A start that failed leaves nothing behind that stops the next one in this JVM.
            try (HarnessWindow again = new HarnessWindow(true, 0, 0, 0, said::add)) {
                again.open();
                assertThat(again.consoleUrl()).describedAs(String.join("\n", said)).isNotNull();
                assertThat(again.mcpUrl()).isNotNull();
            }
        }
    }

    @Test
    void switchedOffItSaysSoAndOpensNothing() {
        HarnessWindow window = new HarnessWindow(false, 0, 0, 0, said::add);
        window.open();
        window.close();

        assertThat(window.consoleUrl()).isNull();
        assertThat(window.mcpUrl()).isNull();
        assertThat(said).singleElement().asString()
            .contains("-D" + HarnessWindow.OBSERVE_PROPERTY + "=false");
    }

    @Test
    void itIsOnByDefaultOnTheAppsOwnPorts() {
        // Read from the properties exactly as the harness reads them; nothing is bound.
        String observe = System.clearProperty(HarnessWindow.OBSERVE_PROPERTY);
        try {
            HarnessWindow window = HarnessWindow.fromProperties(said::add);
            assertThat(window).extracting("wanted", "consolePort", "mcpPort", "holdMillis")
                .containsExactly(true, com.swarmcoder.app.config.ConfigLoader.DEFAULT_CONSOLE_PORT,
                    com.swarmcoder.app.config.McpApiConfig.DEFAULT_PORT, 0L);
        } finally {
            if (observe != null) {
                System.setProperty(HarnessWindow.OBSERVE_PROPERTY, observe);
            }
        }
    }

    /**
     * The harness's context refuses a browser's acts but not the harness's own. The refusal itself
     * is proved in sc-console ({@code ConsoleWatchOnlyTest}); this pins that the harness asks for it.
     */
    @Test
    void theHarnesssConsoleOnlyWatches() throws Exception {
        try (ArtifactStore store = new ArtifactStore(tmp.resolve("store"))) {
            Project project = store.ensureProject("bookshelf", tmp.resolve("repo").toString(),
                List.of());
            UUID started = UUID.randomUUID();
            ConsoleContext context = HarnessWindow.consoleOver(store, new TraceHub(null), null,
                project.id(), (goal, kind) -> started);

            assertThat(context.watchOnlyReason()).isEqualTo(HarnessWindow.WATCH_ONLY_REASON);
            assertThat(context.startRun("goal", "GREENFIELD"))
                .describedAs("the harness's own call, which carries no browser session, goes through")
                .isEqualTo(started);
            assertThat(context.listProjects()).extracting(Project::id).containsExactly(project.id());
            assertThat(context.currentProjectId()).isEqualTo(project.id());
            assertThatThrownBy(() -> {
                com.zeroz4j.server.RmiRequestContext.setContext(null, java.util.Set.of(), "ws-1");
                try {
                    context.startRun("goal", "GREENFIELD");
                } finally {
                    com.zeroz4j.server.RmiRequestContext.clear();
                }
            }).hasMessageContaining("You cannot start a build here")
                .hasMessageContaining("watching an end-to-end harness run");
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static HttpResponse<String> rpc(HttpClient client, String url, String body)
            throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** Holds a free port on every interface, the way a running SwarmCoder would hold its own. */
    private static ServerSocket squat() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress((InetAddress) null, 0));
        return socket;
    }

    private static boolean canBind(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress((InetAddress) null, port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
