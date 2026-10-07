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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zeroz4j.server.Zeroz4jServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/clientlog} is reachable on the real embedded server and lands browser reports on
 * the {@code com.swarmcoder.browser} logger.
 *
 * <p>Over HTTP rather than by calling the resource directly, for the same reason as
 * {@code IngestEndpointTest}: the failure mode is silent. The resource only works if Helidon's CDI
 * discovers the {@code @Path} bean, and if discovery breaks, every unit test still passes while the
 * browser's reports 404 into nothing — which is exactly the invisibility this endpoint exists to
 * remove.
 */
class ClientLogEndpointTest {

    /**
     * One server for the class: zeroz4j binds parts of its engine to the first server started in a
     * JVM (see the surefire {@code reuseForks} note in the module pom), so starting several is asking
     * for trouble that has nothing to do with what is under test here.
     */
    private static Zeroz4jServer server;

    private ch.qos.logback.classic.Logger browser;
    private ListAppender<ILoggingEvent> captured;

    @BeforeAll
    static void startServer() {
        server = Zeroz4jServer.start(0, "Test Console");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void captureBrowserLogger() {
        browser = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.swarmcoder.browser");
        browser.setLevel(Level.INFO);
        captured = new ListAppender<>();
        captured.start();
        browser.addAppender(captured);
    }

    @AfterEach
    void releaseBrowserLogger() {
        browser.detachAppender(captured);
    }

    @Test
    void reportsLandInTheBrowserLoggerAtTheRequestedLevel() throws Exception {
        HttpResponse<String> response =
            post("?level=error&context=BrdView", "boom: cannot read version");
        // 204 with no body, always: an error response is something the client would want to report,
        // and reporting it would come straight back here.
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        post("?level=warn", "slow render");
        post("?level=info&context=ChatView", "reconnected");

        List<ILoggingEvent> events = captured.list;
        assertThat(events).hasSize(3);

        assertThat(events.get(0).getLevel()).isEqualTo(Level.ERROR);
        // Prefixed so the line is obviously browser-origin even where the logger name is elided.
        assertThat(events.get(0).getFormattedMessage())
            .isEqualTo("[browser BrdView] boom: cannot read version");
        assertThat(events.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(1).getFormattedMessage()).isEqualTo("[browser] slow render");
        assertThat(events.get(2).getLevel()).isEqualTo(Level.INFO);
        assertThat(events.get(2).getFormattedMessage()).isEqualTo("[browser ChatView] reconnected");
    }

    @Test
    void anOversizedBodyIsTruncated() throws Exception {
        post("?level=error", "x".repeat(64 * 1024));

        assertThat(captured.list).hasSize(1);
        String logged = captured.list.get(0).getFormattedMessage();
        assertThat(logged).startsWith("[browser] ").endsWith(" ...(truncated)");
        assertThat(logged.chars().filter(c -> c == 'x').count())
            .isEqualTo(ClientLogResource.MAX_BODY_BYTES);
    }

    @Test
    void newlinesInTheBodyCannotForgeASecondLogLine() throws Exception {
        // The shape of a real log line, offered by an unauthenticated caller.
        post("?level=info",
            "harmless\r\n12:00:00.000 [main] ERROR com.swarmcoder.Fake - the build is broken");

        assertThat(captured.list).hasSize(1);
        ILoggingEvent event = captured.list.get(0);
        // One record, at the level the caller asked for — not the ERROR it tried to fabricate.
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        String logged = event.getFormattedMessage();
        assertThat(logged).doesNotContain("\n").doesNotContain("\r");
        assertThat(logged).isEqualTo(
            "[browser] harmless  12:00:00.000 [main] ERROR com.swarmcoder.Fake - the build is broken");
    }

    @Test
    void aHostileContextLabelIsSanitisedAndBounded() throws Exception {
        post("?level=info&context=" + "A".repeat(200) + "%0AERROR", "hi");

        assertThat(captured.list).hasSize(1);
        String logged = captured.list.get(0).getFormattedMessage();
        assertThat(logged).doesNotContain("\n");
        assertThat(logged).isEqualTo(
            "[browser " + "A".repeat(ClientLogResource.MAX_CONTEXT_CHARS) + "] hi");
    }

    private static HttpResponse<String> post(String query, String body) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/clientlog" + query))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
