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
package com.swarmcoder.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.runtime.ExpertHelp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * {@link ExpertDesk} on a command line, so the Python experiment harness can offer the REAL tools
 * rather than a Python re-implementation of them.
 *
 * <p>One process per call. That is slower than a service and it is the right trade: what is being
 * measured is what the worker is told, and a re-implementation would measure a second piece of
 * code that nothing in production runs.
 *
 * <pre>
 * java -cp &lt;cp&gt; com.swarmcoder.knowledge.HelpDeskCli ask &lt;task.json&gt; "question" "what I tried"
 * java -cp &lt;cp&gt; com.swarmcoder.knowledge.HelpDeskCli skeleton &lt;task.json&gt; "BookService"
 * </pre>
 *
 * <p>Prints {@code SOURCE\n} then the answer, so the caller can record where it came from.
 */
public final class HelpDeskCli {

    /**
     * The only endpoint anything here may talk to. Asserted, not assumed: the escalation in
     * production is a paid model, and nothing in this experiment may ever reach one.
     */
    private static final String BASE_URL = "http://192.168.0.10:8002/v1";
    private static final String MODEL = "qwen3.8-27b";
    private static final String ALLOWED_HOST = "192.168.0.10";

    private HelpDeskCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: HelpDeskCli ask|skeleton <task.json> <question> [tried]");
            System.exit(2);
        }
        String what = args[0];
        JsonNode task = new ObjectMapper().readTree(
            Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));

        Path target = Path.of(task.path("targetRoot").asText());
        List<KnowledgeCurator.Root> roots = new ArrayList<>();
        roots.add(new KnowledgeCurator.Root("project", target, "local"));
        for (JsonNode reference : task.path("referenceRoots")) {
            roots.add(new KnowledgeCurator.Root(reference.path("label").asText(),
                Path.of(reference.path("path").asText()), "local"));
        }
        List<ApiContract> contracts = new ArrayList<>();
        for (JsonNode node : task.path("contracts")) {
            List<String> members = new ArrayList<>();
            for (JsonNode member : node.path("members")) {
                members.add(member.asText());
            }
            contracts.add(new ApiContract(UUID.randomUUID(), node.path("typeName").asText(),
                node.path("purpose").asText(), "", node.path("typeName").asText(), members));
        }

        KnowledgeCurator curator = new KnowledgeCurator(roots, null,
            Path.of(System.getProperty("java.io.tmpdir"), "helpdesk-cache"));
        ExpertDesk desk = new ExpertDesk(curator, target, contracts, localExpert());

        ExpertHelp.Answer answer = "skeleton".equals(what)
            ? desk.requestSkeleton(args[2])
            : desk.askExpert(args[2], args.length > 3 ? args[3] : null);

        System.out.println(answer.source());
        System.out.println(answer.text());
    }

    /**
     * The stand-in for production's utility/architect role, which is a paid endpoint.
     *
     * <p>Here it is the same free local model the worker itself runs on, given the reference
     * material as its context. That makes the experiment's escalation weaker than production's,
     * not stronger, so any result it produces is a lower bound. It is stated in the run record.
     */
    private static BiFunction<String, String, String> localExpert() {
        if (!URI.create(BASE_URL).getHost().equals(ALLOWED_HOST)) {
            throw new IllegalStateException("refusing to run: the endpoint is not the free local one");
        }
        return (question, context) -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode body = mapper.createObjectNode();
                body.put("model", MODEL);
                body.put("temperature", 0.2);
                body.put("max_tokens", 1200);
                var messages = body.putArray("messages");
                ObjectNode system = messages.addObject();
                system.put("role", "system");
                system.put("content", "You are an expert on this codebase. Answer with WORKING "
                    + "JAVA CODE for exactly the call the question asks about — the imports, the "
                    + "call itself, and the dependency line if one is needed — then at most two "
                    + "lines of explanation. Use only APIs that appear in the material below. If "
                    + "the material does not contain the answer, say so in one line rather than "
                    + "inventing an API.");
                ObjectNode user = messages.addObject();
                user.put("role", "user");
                user.put("content", question + "\n\n" + context);

                HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30)).build()
                    .send(HttpRequest.newBuilder(URI.create(BASE_URL + "/chat/completions"))
                        .timeout(Duration.ofMinutes(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return "";
                }
                return mapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content").asText("");
            } catch (Exception e) {                                        // noqa
                return "";
            }
        };
    }

    /** Unused today; kept so a caller can pass options without another argument-order change. */
    static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        return options;
    }
}
