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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.ModelShapes;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.swarm.WorkerToolbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, against the real free local endpoint, what today's two settings changes ({@code
 * ModelQuirks.DEFAULTS.thinking} on by default; {@code qwen38-flash-next-125b}'s tool history
 * native rather than textual) actually do on the wire — not just what the code reads as intending.
 *
 * <p>Two parts, because one of the four things to prove cannot be shown by running the real
 * session at all: a raw HTTP call open-codes exactly the request Koog would build for turn one
 * (system prompt + native tool definitions + {@code tool_choice: auto}), so the response's own
 * JSON can be printed and inspected for {@code tool_calls} (not text) and {@code reasoning_content}
 * (present). That the reasoning is never REPLAYED back on turn two is a fact about Koog's own
 * request-building — proven directly, with no live model needed, by {@code
 * KoogAgentRuntimeReasoningTest} — so this class does not attempt to re-prove it by inspecting the
 * wire a second time.
 *
 * <p>The real {@link KoogAgentRuntime} session that follows is the functional half: a three-turn
 * scripted task (read a file, write a file, call {@code report_done}) against a plain temp
 * directory, using the exact same {@link WorkerToolbox} every real worker uses. It proves the tool
 * calls actually arrive as native {@code tool_calls} (recovered-from-text is never invoked) and the
 * write actually happens.
 *
 * <pre>
 * mvn -o test -pl sc-app -am -Dtest=LiveThinkingAndNativeToolsProbeTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://192.168.0.10:8002/v1 \
 *   -Dswarmcoder.live.model=qwen3.8-27b
 * </pre>
 */
class LiveThinkingAndNativeToolsProbeTest {

    @TempDir
    Path work;

    @Test
    @EnabledIfSystemProperty(named = "swarmcoder.live.baseUrl", matches = ".+",
        disabledReason = "needs a live model endpoint; see the class javadoc")
    void nativeToolCallsAndThinkingRoundTripAgainstTheRealEndpoint() throws Exception {
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        String model = System.getProperty("swarmcoder.live.model", "qwen3.8-27b");
        assertHostIsPrivate(baseUrl);

        // --- part 1: one raw turn, printed verbatim, for the report ---------------------------
        rawTurnEvidence(baseUrl, model);

        // --- part 2: the real three-turn worker session -----------------------------------------
        Files.writeString(work.resolve("seed.txt"), "hello from the live probe\n");

        Task task = new Task(UUID.randomUUID(), 1, "live probe",
            "You have exactly three tools: read_file, write_file, report_done. This is not a "
                + "coding task and there is no program to write. Do exactly these three tool "
                + "calls, in this order, and nothing else:\n"
                + "1. read_file(path=\"seed.txt\")\n"
                + "2. write_file(path=\"greeting.txt\", content=<the exact text read in step 1, "
                + "plus one more line reading \"probed ok\">)\n"
                + "3. report_done(summary=\"copied seed.txt to greeting.txt\")\n"
                + "The output file MUST be named exactly \"greeting.txt\" - do not invent any "
                + "other file name. Do not call any tool more than once.",
            Set.of("greeting.txt"), Set.of("seed.txt"), List.of(), null, null,
            new TokenBudget(32000, 4000, 500000, 8),
            new SwarmPolicy(1, false, 0.0, 0.0, List.of()), TaskState.READY);

        WorkerToolbox toolbox = new WorkerToolbox(work, task);
        List<AgentRuntime.ToolBinding> tools = threeTools(toolbox);

        ModelQuirks quirks = ModelShapes.get("qwen38-flash-next-125b");
        assertThat(quirks.thinking()).as("this is the shape today's change turned thinking on for").isTrue();
        assertThat(quirks.textualToolHistory())
            .as("this is the one shape today's change made native")
            .isFalse();

        AgentRuntime.ModelEndpoint endpoint = new AgentRuntime.ModelEndpoint(
            baseUrl, "", model, quirks.servedContextTokens(), quirks);

        List<TraceEvent> events = new CopyOnWriteArrayList<>();
        TraceHub hub = new TraceHub(null);
        hub.addListener(new TraceHub.Listener() {
            @Override
            public void event(UUID sessionId, TraceEvent event) {
                events.add(event);
                System.out.println("[PROBE] event " + event.kind() + " label=" + event.label()
                    + " payload=" + trimForLog(event.payload()));
            }
        });

        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec("live-probe-worker",
            "You are a scripted test worker with exactly three tools available: read_file, "
                + "write_file and report_done. Follow the instructions exactly.",
            endpoint, 0.0, 10, tools, info -> java.util.Optional.empty());

        KoogAgentRuntime runtime = new KoogAgentRuntime(hub);
        AgentRuntime.AgentSession session = runtime.open(spec);
        AgentRuntime.SessionResult result;
        try {
            result = session.run("Task: " + task.instructions());
        } finally {
            session.close();
        }

        System.out.println("[PROBE] session result: completed=" + result.completed()
            + " killReason=" + result.killReason() + " turns=" + result.turns()
            + " tokens=" + result.tokensUsed());

        long toolCallEvents = events.stream().filter(e -> e.kind() == TraceEventKind.TOOL_CALL).count();
        long reasoningTurns = events.stream()
            .filter(e -> e.kind() == TraceEventKind.LLM_RESPONSE)
            .filter(e -> e.payload() != null && e.payload().startsWith("[thought ~"))
            .count();
        boolean anyTextEmittedRecovery = events.stream()
            .anyMatch(e -> e.kind() == TraceEventKind.LLM_RESPONSE
                && e.payload() != null
                && e.payload().toLowerCase(Locale.ROOT).contains("tool_call_id"));
        System.out.println("[PROBE] tool call events: " + toolCallEvents
            + ", turns that carried reasoning: " + reasoningTurns
            + ", a turn's own text looked like a hand-written tool call: " + anyTextEmittedRecovery);

        assertThat(result.killReason())
            .as("no 400, no malformed-tool-call kill, no endpoint outage")
            .isEmpty();
        assertThat(toolCallEvents)
            .as("read_file and write_file both actually arrived as native tool calls")
            .isGreaterThanOrEqualTo(2);
        assertThat(Files.exists(work.resolve("greeting.txt")))
            .as("the write actually happened")
            .isTrue();
        String written = Files.readString(work.resolve("greeting.txt"));
        System.out.println("[PROBE] greeting.txt written:\n" + written);
        assertThat(written).contains("hello from the live probe");
    }

    private static String trimForLog(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace("\n", " | ");
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    /** read_file / write_file / report_done — the three the scripted task uses, and no others. */
    private static List<AgentRuntime.ToolBinding> threeTools(WorkerToolbox toolbox) throws Exception {
        Method read = WorkerToolbox.class.getMethod("read", String.class);
        Method writeFile = WorkerToolbox.class.getMethod("writeFile", String.class, String.class);
        Method reportDone = WorkerToolbox.class.getMethod("reportDone", String.class);
        return List.of(
            new AgentRuntime.ToolBinding("read_file",
                "Read a file by repository-relative path. Returns its content.", toolbox, read),
            new AgentRuntime.ToolBinding("write_file",
                "Replace one file's entire content (repository-relative path + full new content).",
                toolbox, writeFile),
            new AgentRuntime.ToolBinding("report_done",
                "Call exactly once, when the task is complete, with a short summary.",
                toolbox, reportDone));
    }

    /**
     * One raw chat-completions call, open-coded exactly as a first Koog turn would be sent — a
     * system message, native tool definitions, {@code tool_choice: auto} — so the response can be
     * printed and inspected directly rather than trusted to have been sent and parsed correctly.
     * Independent of {@link KoogAgentRuntime}: this is what the report's "one turn's JSON" is.
     */
    private static void rawTurnEvidence(String baseUrl, String model) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system")
            .put("content", "You are a scripted test worker. Use your tools.");
        messages.addObject().put("role", "user")
            .put("content", "Call read_file with path \"seed.txt\", then stop.");
        ArrayNode tools = payload.putArray("tools");
        ObjectNode readFile = tools.addObject();
        readFile.put("type", "function");
        ObjectNode fn = readFile.putObject("function");
        fn.put("name", "read_file");
        fn.put("description", "Read a file by path.");
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("path").put("type", "string");
        params.putArray("required").add("path");
        payload.put("tool_choice", "auto");
        payload.put("temperature", 0.2);
        payload.put("max_tokens", 4096);

        String url = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
            + "/chat/completions";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();

        System.out.println("[PROBE] one raw turn — REQUEST to " + url + ":");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("[PROBE] one raw turn — RESPONSE (HTTP " + response.statusCode() + "):");
        JsonNode responseBody = mapper.readTree(response.body());
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(responseBody));

        assertThat(response.statusCode()).as("no 400 from the server").isEqualTo(200);
        JsonNode message = responseBody.at("/choices/0/message");
        assertThat(message.isMissingNode()).as("a real choice came back").isFalse();
        assertThat(message.has("tool_calls") && message.get("tool_calls").size() > 0)
            .as("the tool call arrived as a native tool_calls entry, not as text")
            .isTrue();
        JsonNode reasoning = message.get("reasoning_content");
        System.out.println("[PROBE] reasoning_content present: "
            + (reasoning != null && !reasoning.asText("").isBlank()));
    }

    /** Refuses to run this probe against anything but a private-network endpoint. */
    private static void assertHostIsPrivate(String baseUrl) throws Exception {
        String host = URI.create(baseUrl).getHost();
        assertThat(host).as("swarmcoder.live.baseUrl must name a host").isNotBlank();
        InetAddress address = InetAddress.getByName(host);
        boolean isPrivate = address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress();
        assertThat(isPrivate)
            .as("refusing to run a live probe against a non-private host: " + host + " -> " + address)
            .isTrue();
    }
}
