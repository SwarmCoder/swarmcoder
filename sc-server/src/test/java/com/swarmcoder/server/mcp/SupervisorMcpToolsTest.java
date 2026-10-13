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

import com.swarmcoder.console.api.AttentionItem;
import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.GraphService;
import com.swarmcoder.console.api.ObserverService;
import com.swarmcoder.console.api.SupervisorService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolRegistration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The supervisor's tools: that one item is short enough to be cheap, that a tool which changes
 * something is never run without the secret, and that the secret itself is never handed out.
 */
class SupervisorMcpToolsTest {

    private static final String INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":"2025-06-18","capabilities":{},
          "clientInfo":{"name":"test","version":"1"}}}""";
    private static final String INITIALIZED =
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

    private SwarmMcpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void anItemIsOneShortReplyThatStandsAlone() {
        AttentionItem huge = new AttentionItem("RUN_QUESTION", "bookshelf", "S3 Lend a book",
            "q".repeat(5_000), List.of("retry: run the stage again"), "e".repeat(5_000),
            "answer_question decision_id=11111111-1111-1111-1111-111111111111 answer=<token>");
        SupervisorService supervisor = Stubs.of(SupervisorService.class)
            .answer("nextAttention", args -> huge.fitted())
            .build();
        SupervisorMcpTools tools = new SupervisorMcpTools(supervisor,
            Stubs.of(ControlService.class).build());

        String reply = tools.attention(supervisor.nextAttention(0));

        assertThat(reply.length())
            .describedAs("a supervisor pays for every character of every wake")
            .isLessThanOrEqualTo(1_500);
        assertThat(reply)
            .describedAs("cut from the evidence and the question, never from how to answer")
            .contains("bookshelf").contains("S3 Lend a book")
            .contains("retry: run the stage again")
            .contains("answer_question decision_id=11111111-1111-1111-1111-111111111111");
    }

    @Test
    void nothingWaitingIsSaidInOneSentence() {
        SupervisorService supervisor = Stubs.of(SupervisorService.class)
            .returning("standing", "bookshelf: 3 stories, 1 delivered, 1 building.")
            .build();
        SupervisorMcpTools tools = new SupervisorMcpTools(supervisor,
            Stubs.of(ControlService.class).build());

        assertThat(tools.attention(supervisor.nextAttention(0)))
            .isEqualTo("{\"attention\":null,\"standing\":\"bookshelf: 3 stories, 1 delivered, "
                + "1 building.\"}");
    }

    @Test
    void everyToolThatChangesSomethingIsOneTheTransportGuards() {
        SwarmMcpTools tools = new SwarmMcpTools(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), Stubs.of(ControlService.class).build(), false,
            Stubs.of(SupervisorService.class).build());

        List<String> changing = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (SyncToolRegistration registration : tools.registrations()) {
            names.add(registration.tool().name());
            if (registration.tool().description().startsWith("CHANGES SOMETHING")) {
                changing.add(registration.tool().name());
            }
        }

        assertThat(names.get(0))
            .describedAs("the tool a supervisor loops on is the first one it is shown")
            .isEqualTo("wait_for_attention");
        assertThat(changing)
            .describedAs("a tool that changes something and is not guarded is an open door")
            .isNotEmpty()
            .allMatch(SwarmMcpTools.writeToolNames()::contains);
        assertThat(SwarmMcpTools.writeToolNames())
            .describedAs("and nothing is guarded that does not exist")
            .allMatch(names::contains);
    }

    @Test
    void readOnlyOffersTheSupervisorNothingThatChangesAnything() {
        SwarmMcpTools tools = new SwarmMcpTools(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), Stubs.of(ControlService.class).build(), true,
            Stubs.of(SupervisorService.class).build());

        assertThat(tools.registrations()).extracting(r -> r.tool().name())
            .contains("wait_for_attention", "next_attention", "decision_log")
            .doesNotContainAnyElementsOf(SwarmMcpTools.writeToolNames());
    }

    @Test
    void aToolThatChangesSomethingIsRefusedWithoutTheSecretAndRunWithIt(@TempDir Path home)
            throws Exception {
        String secret = McpSecret.loadOrCreate(home.resolve(McpSecret.FILE_NAME));
        AtomicReference<String> accepted = new AtomicReference<>();
        SupervisorService supervisor = Stubs.of(SupervisorService.class)
            .answer("acceptDelivery", args -> {
                accepted.set(String.valueOf(args[0]));
                return "";
            })
            .returning("standing", "nothing is waiting")
            .build();
        server = new SwarmMcpServer(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), Stubs.of(ControlService.class).build(), 0, "",
            false, supervisor, secret);
        String url = server.start();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        rpc(client, url, INITIALIZE, null);
        rpc(client, url, INITIALIZED, null);

        String accept = """
            {"jsonrpc":"2.0","id":7,"method":"tools/call",
             "params":{"name":"accept_delivery","arguments":{"story":"S3"}}}""";

        String refused = rpc(client, url, accept, null).body();
        assertThat(refused).contains("\"error\"").contains("Refused").contains("mcp-secret");
        assertThat(accepted.get()).describedAs("nothing was accepted").isNull();

        String wrong = rpc(client, url, accept, "Bearer not-the-secret").body();
        assertThat(wrong).contains("Refused");
        assertThat(accepted.get()).isNull();

        String read = rpc(client, url, """
            {"jsonrpc":"2.0","id":8,"method":"tools/call",
             "params":{"name":"next_attention","arguments":{}}}""", null).body();
        assertThat(read)
            .describedAs("reading needs no secret, exactly as before")
            .contains("nothing is waiting").doesNotContain("Refused");

        String done = rpc(client, url, accept, "Bearer " + secret).body();
        assertThat(done).contains("done").doesNotContain("Refused");
        assertThat(accepted.get()).isEqualTo("S3");

        assertThat(refused + wrong + read + done)
            .describedAs("no reply, refusal included, ever carries the secret")
            .doesNotContain(secret);
    }

    @Test
    void referenceFolderToolsListFreelyAndChangeOnlyWithTheSecret(@TempDir Path home)
            throws Exception {
        String secret = McpSecret.loadOrCreate(home.resolve(McpSecret.FILE_NAME));
        List<String> calls = new ArrayList<>();
        SupervisorService supervisor = Stubs.of(SupervisorService.class)
            .answer("contextFolders", args -> List.of(
                new com.swarmcoder.console.api.ContextFolder("zeroz4j", "/libs/zeroz4j", "folder",
                    true)))
            .answer("addContextFolder", args -> {
                calls.add("add " + args[0] + " " + args[1]);
                return "Added zeroz4j. New builds read it.";
            })
            .answer("removeContextFolder", args -> {
                calls.add("remove " + args[0]);
                return "Removed.";
            })
            .returning("standing", "nothing is waiting")
            .build();
        server = new SwarmMcpServer(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), Stubs.of(ControlService.class).build(), 0, "",
            false, supervisor, secret);
        String url = server.start();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        rpc(client, url, INITIALIZE, null);
        rpc(client, url, INITIALIZED, null);

        String add = """
            {"jsonrpc":"2.0","id":11,"method":"tools/call",
             "params":{"name":"add_context_folder","arguments":{"name":"zeroz4j","path":"/libs/zeroz4j"}}}""";
        String remove = """
            {"jsonrpc":"2.0","id":12,"method":"tools/call",
             "params":{"name":"remove_context_folder","arguments":{"name":"zeroz4j"}}}""";

        assertThat(rpc(client, url, add, null).body()).contains("Refused");
        assertThat(rpc(client, url, remove, null).body()).contains("Refused");
        assertThat(rpc(client, url, add, "Bearer wrong").body()).contains("Refused");
        assertThat(calls).describedAs("nothing changed without the secret").isEmpty();

        String list = rpc(client, url, """
            {"jsonrpc":"2.0","id":13,"method":"tools/call",
             "params":{"name":"list_context_folders","arguments":{}}}""", null).body();
        assertThat(list).contains("zeroz4j").contains("/libs/zeroz4j").doesNotContain("Refused");

        assertThat(rpc(client, url, add, "Bearer " + secret).body())
            .contains("done").contains("New builds read it");
        assertThat(rpc(client, url, remove, "Bearer " + secret).body()).contains("done");
        assertThat(calls).containsExactly("add zeroz4j /libs/zeroz4j", "remove zeroz4j");
    }

    @Test
    void requirementsAreReadFreelyAndDeclinedOnlyWithTheSecret(@TempDir Path home)
            throws Exception {
        String secret = McpSecret.loadOrCreate(home.resolve(McpSecret.FILE_NAME));
        List<String> calls = new ArrayList<>();
        com.swarmcoder.console.api.RequirementRow row = new com.swarmcoder.console.api.RequirementRow(
            "R11", "Which years are refused", "draft", "functional", "medium", 2, List.of("R3"),
            "architect, while designing S3", "2026-10-13T08:00:00Z");
        SupervisorService supervisor = Stubs.of(SupervisorService.class)
            .answer("listRequirements", args -> List.of(row))
            .answer("viewRequirement", args -> "R11".equals(args[0])
                ? new com.swarmcoder.console.api.RequirementDetail(row, "Books before 1900 are refused.",
                    "", "", "the screen needs it", List.of("R11:C1 [accepted/unverified] refused - test: YearTest"),
                    List.of(), List.of("2026-10-13T08:00:00Z CREATED by agent: architect proposed R11"))
                : null)
            .answer("declineRequirement", args -> {
                calls.add("decline " + args[0] + " " + args[1]);
                return "R11 is out of scope.";
            })
            .returning("standing", "nothing is waiting")
            .build();
        server = new SwarmMcpServer(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), Stubs.of(ControlService.class).build(), 0, "",
            false, supervisor, secret);
        String url = server.start();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        rpc(client, url, INITIALIZE, null);
        rpc(client, url, INITIALIZED, null);

        String list = rpc(client, url, """
            {"jsonrpc":"2.0","id":21,"method":"tools/call",
             "params":{"name":"list_requirements","arguments":{"state":"draft"}}}""", null).body();
        assertThat(list).contains("R11 | Which years are refused | draft", "waits for R3",
            "drafted by architect, while designing S3").doesNotContain("Refused");
        String view = rpc(client, url, """
            {"jsonrpc":"2.0","id":22,"method":"tools/call",
             "params":{"name":"view_requirement","arguments":{"requirement":"R11"}}}""", null).body();
        assertThat(view).contains("Books before 1900 are refused.", "the screen needs it",
            "YearTest", "CREATED by agent");
        String missing = rpc(client, url, """
            {"jsonrpc":"2.0","id":23,"method":"tools/call",
             "params":{"name":"view_requirement","arguments":{"requirement":"R99"}}}""", null).body();
        assertThat(missing).contains("no requirement");

        String decline = """
            {"jsonrpc":"2.0","id":24,"method":"tools/call",
             "params":{"name":"decline_requirement","arguments":{"requirement":"R11","note":"no"}}}""";
        assertThat(rpc(client, url, decline, null).body()).contains("Refused");
        assertThat(calls).isEmpty();
        assertThat(rpc(client, url, decline, "Bearer " + secret).body()).contains("done");
        assertThat(calls).containsExactly("decline R11 no");
    }

    @Test
    void withNoSecretEveryToolThatChangesSomethingIsRefused() throws Exception {
        server = new SwarmMcpServer(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), Stubs.of(ControlService.class).build(), 0, "",
            false);
        String url = server.start();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        rpc(client, url, INITIALIZE, null);
        rpc(client, url, INITIALIZED, null);

        String start = rpc(client, url, """
            {"jsonrpc":"2.0","id":9,"method":"tools/call",
             "params":{"name":"start_run","arguments":{"goal":"x","kind":"GREENFIELD"}}}""",
            "Bearer ").body();

        assertThat(start)
            .describedAs("a server given no secret has no open way to change anything")
            .contains("Refused");
    }

    @Test
    void theSecretIsMadeOnceAndReadBackTheSame(@TempDir Path home) throws Exception {
        Path file = home.resolve("nested").resolve(McpSecret.FILE_NAME);

        String first = McpSecret.loadOrCreate(file);
        String second = McpSecret.loadOrCreate(file);

        assertThat(first).hasSize(64).isEqualTo(second);
        assertThat(Files.readString(file).strip()).isEqualTo(first);
        assertThat(McpSecret.matches(first, "Bearer " + first)).isTrue();
        assertThat(McpSecret.matches(first, first)).isTrue();
        assertThat(McpSecret.matches(first, "Bearer " + first + "x")).isFalse();
        assertThat(McpSecret.matches(first, null)).isFalse();
        assertThat(McpSecret.matches(null, "Bearer ")).isFalse();
        assertThat(McpSecret.matches("", "")).isFalse();
    }

    private static HttpResponse<String> rpc(HttpClient client, String url, String body,
                                            String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
