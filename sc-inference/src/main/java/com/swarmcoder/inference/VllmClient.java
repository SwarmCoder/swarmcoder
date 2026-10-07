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
package com.swarmcoder.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class VllmClient {
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final ObjectMapper mapper;

    /**
     * Everything model-specific about this endpoint (see {@link ModelQuirks}): the generation cap,
     * whether reasoning is on and which chat-template argument switches it off, whether the server
     * honours a JSON response format, and which HTTP version it can be spoken to over.
     *
     * <p>These were a JVM-wide system property and three constants until 2026-08-27. That meant two
     * models could not run side by side with different answers to any of them, and a new model
     * silently inherited a 27B Qwen's conventions.
     */
    private final ModelQuirks quirks;

    /** Legacy shape: everything model-specific comes from {@link ModelQuirks#DEFAULTS}. */
    public VllmClient(String baseUrl, String apiKey, String modelName, boolean useResponseFormat) {
        this(baseUrl, apiKey, modelName, useResponseFormat, null);
    }

    /**
     * @param thinking per-instance thinking override (nullable): {@code true}/{@code false} force
     *                 it; {@code null} leaves the quirks' own setting alone.
     */
    public VllmClient(String baseUrl, String apiKey, String modelName, boolean useResponseFormat, Boolean thinking) {
        this(baseUrl, apiKey, modelName,
            thinking == null ? ModelQuirks.DEFAULTS.withJsonResponseFormat(useResponseFormat)
                : ModelQuirks.DEFAULTS.withJsonResponseFormat(useResponseFormat).withThinking(thinking));
    }

    /** The real constructor: one endpoint, one model, one set of that model's quirks. */
    public VllmClient(String baseUrl, String apiKey, String modelName, ModelQuirks quirks) {
        this.quirks = quirks == null ? ModelQuirks.DEFAULTS : quirks;
        // Connect timeout so an unreachable endpoint fails over quickly (e.g. the PLAN
        // stage's offline fallback) instead of hanging the workflow. The HTTP version is a
        // per-model setting because it is a property of the SERVER build, not of us: Java's
        // default h2c upgrade confuses some uvicorn/vLLM builds — requests arrive with an
        // empty body and fail 400 ("body Field required") — so HTTP/1.1 is the default.
        this.httpClient = HttpClient.newBuilder()
            .version(this.quirks.httpVersion())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        // "/v1/chat/completions" is appended below; accept base URLs configured with or
        // without the conventional "/v1" suffix.
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = normalized.endsWith("/v1") ? normalized.substring(0, normalized.length() - 3) : normalized;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.mapper = new ObjectMapper();
    }

    /** This endpoint's model-specific settings — named in the startup announcement. */
    public ModelQuirks quirks() {
        return quirks;
    }

    public String modelName() {
        return modelName;
    }

    /** The endpoint this client talks to — named in outage messages so an operator knows which box. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * The key this endpoint is called with, so the same endpoint can be opened by an agent session
     * rather than only by this client.
     *
     * <p>The help desk's expert is a tool-using agent session now, and a session is opened through
     * {@code AgentRuntime.ModelEndpoint} — base URL, key, model id, context, quirks. Every one of
     * those was already readable off this class except the key, so this exists rather than a second
     * copy of the same role's configuration being threaded through the wiring beside the client.
     * Never logged, and never put in a prompt.
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * Classifies a non-200 as outage or refusal.
     *
     * <p>5xx and 429 mean the box is there but cannot serve the request right now — a model still
     * loading, a wedged engine, a queue at capacity. Waiting is the correct response, so they are
     * outages. Everything else (400 for a malformed request, 401, 404 for a wrong model id) is the
     * endpoint answering on the merits, and no amount of waiting will change it.
     */
    private void failByStatus(int status, String body) {
        if (status == 429 || status >= 500) {
            throw new EndpointOutage(baseUrl, "HTTP " + status + " — " + body, null);
        }
        throw new RuntimeException("vLLM error: " + status + " Body: " + body);
    }

    /**
     * Describes an image with a vision-capable model and returns the whole answer (not a stream).
     *
     * <p>Used by the BRD document intake to read requirements out of a screenshot or a photographed
     * page. It cannot go through {@link #chatCompletionStream}: that takes flat
     * {@code Map<String,String>} messages, and the OpenAI vision format needs {@code content} to be
     * an ARRAY of typed parts, so the image simply cannot be expressed there.
     *
     * @param imageDataUri a {@code data:image/...;base64,...} URI — what the browser produces from a
     *                     dropped file, and what an OpenAI-compatible endpoint accepts inline
     * @return the model's reading of the image. It is a reading, not the document: callers must
     *         label it as such so an operator never mistakes it for extracted text.
     */
    public String describeImage(String prompt, String imageDataUri, double temperature) throws Exception {
        ObjectNode textPart = mapper.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        ObjectNode imageUrl = mapper.createObjectNode();
        imageUrl.put("url", imageDataUri);
        ObjectNode imagePart = mapper.createObjectNode();
        imagePart.put("type", "image_url");
        imagePart.set("image_url", imageUrl);

        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.set("content", mapper.createArrayNode().add(textPart).add(imagePart));

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", modelName);
        payload.put("stream", false);
        payload.put("temperature", temperature);
        payload.put("max_tokens", quirks.maxOutputTokens());
        payload.set("messages", mapper.createArrayNode().add(message));
        // The reasoning switch is sent only when THIS model has one. It used to be omitted here by
        // a comment saying vision models are not thinking models — a per-model rule expressed as an
        // absence in shared code. Now the vision endpoint's profile simply names no chat-template
        // argument, and a vision model that does have one gets it.
        applyThinkingKwarg(payload);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/chat/completions"))
            // Longer than the streaming path's: there is no first-byte-then-stream here, the whole
            // image has to be encoded, prefilled and answered before any bytes come back.
            .timeout(Duration.ofSeconds(300))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        RunMeter.Open metered;
        HttpResponse<String> response;
        // One of the server's places while the request is in flight; see chatCompletionStream.
        try (ServerPlaces.Place place = ServerPlaces.enter(baseUrl)) {
            metered = RunMeter.begin(RunMeter.currentTag(), modelName);
            try {
                response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                metered.failed();
                // The box was not there. Distinguished from a bad reading of the image so the
                // caller can wait rather than conclude anything about the document.
                throw new EndpointOutage(baseUrl, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
            }
        }
        if (response.statusCode() != 200) {
            failByStatus(response.statusCode(), response.body());
        }
        // This endpoint answered. Recorded so that a DIFFERENT caller's request timeout on the same
        // box can be told apart from the box being gone — see EndpointActivity.
        EndpointActivity.succeeded(baseUrl);
        metered.end();
        JsonNode answered = mapper.readTree(response.body());
        recordUsage(metered, answered);
        JsonNode content = answered.at("/choices/0/message/content");
        if (content.isMissingNode() || content.isNull()) {
            throw new RuntimeException("vision model returned no content: " + response.body());
        }
        return content.asText();
    }

    /** The server's own token counts for this call, when the response (or stream chunk) has them. */
    private static void recordUsage(RunMeter.Open metered, JsonNode response) {
        JsonNode usage = response.get("usage");
        if (usage == null || !usage.isObject()) {
            return;
        }
        metered.usage(usage.path("prompt_tokens").asInt(-1),
            usage.path("completion_tokens").asInt(-1));
        metered.cached(cachedPromptTokens(usage));
    }

    /**
     * How many prompt tokens a chat response's {@code usage} says came from the server's prompt
     * prefix cache: DeepSeek's {@code prompt_cache_hit_tokens}, else OpenAI's and vLLM's
     * {@code prompt_tokens_details.cached_tokens}; -1 when the server said neither.
     */
    public static int cachedPromptTokens(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return -1;
        }
        JsonNode hit = usage.get("prompt_cache_hit_tokens");
        if (hit != null && hit.isNumber()) {
            return hit.asInt();
        }
        JsonNode detail = usage.path("prompt_tokens_details").get("cached_tokens");
        return detail != null && detail.isNumber() ? detail.asInt() : -1;
    }

    /**
     * This client, with the role that is asking named for the run's cost record ({@link RunMeter}).
     * One client is shared by several roles (the harness runs every role on one endpoint), so the
     * role cannot be read off the client: it is said where the call is made.
     */
    public Tagged as(String role) {
        return new Tagged(new RunMeter.Tag(role, null, null));
    }

    /** As {@link #as(String)}, for a call made on behalf of one task and, if any, one worker. */
    public Tagged as(String role, String task, String worker) {
        return new Tagged(new RunMeter.Tag(role, task, worker));
    }

    /** The calls of {@link VllmClient}, made under a tag. Everything else about them is the same. */
    public final class Tagged {
        private final RunMeter.Tag tag;

        private Tagged(RunMeter.Tag tag) {
            this.tag = tag;
        }

        public Stream<String> chatCompletionStream(List<Map<String, String>> messages,
                                                   Class<?> targetSchemaClass, double temperature)
                throws Exception {
            return RunMeter.tagged(tag, () ->
                VllmClient.this.chatCompletionStream(messages, targetSchemaClass, temperature));
        }

        public String describeImage(String prompt, String imageDataUri, double temperature)
                throws Exception {
            return RunMeter.tagged(tag, () ->
                VllmClient.this.describeImage(prompt, imageDataUri, temperature));
        }
    }

    /**
     * Switches the model's reasoning off, if this model has a chat-template argument that does it.
     *
     * <p>vLLM forwards {@code chat_template_kwargs} verbatim to the model's chat template, so the
     * ARGUMENT NAME is a property of the model, not of us — {@code enable_thinking} is a Qwen
     * convention and means nothing elsewhere. A model whose profile names no argument gets no
     * {@code chat_template_kwargs} at all, which is what a template that does not know the key
     * needs (some reject it outright). Reasoning left ON also sends nothing: the model's own
     * default stands.
     */
    private void applyThinkingKwarg(ObjectNode payload) {
        if (!quirks.sendsThinkingKwarg()) {
            return;
        }
        ObjectNode templateKwargs = mapper.createObjectNode();
        templateKwargs.put(quirks.thinkingKwarg(), false);
        payload.set("chat_template_kwargs", templateKwargs);
    }

    public Stream<String> chatCompletionStream(List<Map<String, String>> messages, Class<?> targetSchemaClass, double temperature) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", modelName);
        payload.put("stream", true);
        payload.put("temperature", temperature);
        payload.put("max_tokens", quirks.maxOutputTokens()); // bound generation so a rambling response can't stall the run
        payload.set("messages", mapper.valueToTree(messages));
        applyThinkingKwarg(payload);
        // A streamed answer carries the server's token counts only when asked to: one last chunk
        // with a "usage" object. Asked ONLY while a run is being metered (see RunMeter), so the
        // request every other caller sends is unchanged; -Dswarmcoder.meter.streamUsage=false
        // withholds it for a server that refuses the option.
        if (RunMeter.enabled() && !"false".equals(System.getProperty("swarmcoder.meter.streamUsage"))) {
            ObjectNode streamOptions = mapper.createObjectNode();
            streamOptions.put("include_usage", true);
            payload.set("stream_options", streamOptions);
        }

        if (targetSchemaClass != null) {
            JsonNode schemaNode = SchemaGen.generateSchema(targetSchemaClass);
            if (quirks.jsonResponseFormat()) {
                ObjectNode responseFormat = mapper.createObjectNode();
                responseFormat.put("type", "json_object");
                payload.set("response_format", responseFormat);
            } else {
                ObjectNode extraBody = mapper.createObjectNode();
                extraBody.set("guided_json", schemaNode);
                payload.set("extra_body", extraBody);
            }
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/chat/completions"))
            // Bounds time-to-first-byte (headers), not the streamed body: generous enough
            // for cold prefill, but a dead or wedged endpoint cannot hang a workflow stage.
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = requestBuilder.build();

        // One of the server's places, for as long as this request is in flight - the same places
        // the workers' requests take, so a judge or a reviewer call on the workers' own server is
        // counted with them. Nothing, at once, for a server nobody registered (see ServerPlaces).
        // Taken BEFORE the meter starts, so time spent waiting for a place is not time in a call.
        ServerPlaces.Place place = ServerPlaces.enter(baseUrl);
        RunMeter.Open metered = RunMeter.begin(RunMeter.currentTag(), modelName);
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.io.IOException e) {
            metered.failed();
            place.close();
            // Connection refused, DNS failure, or no first byte inside the timeout: the endpoint was
            // not there. Every role call funnels through here, so this is where the whole workflow's
            // "the model was not there" signal is born.
            throw new EndpointOutage(baseUrl, e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        } catch (InterruptedException | RuntimeException e) {
            metered.failed();
            place.close();
            throw e;
        }
        if (response.statusCode() != 200) {
            metered.failed();
            place.close();
            String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            failByStatus(response.statusCode(), errorBody);
        }
        // Headers are back with a 200, so the engine finished prefill and is generating: this
        // endpoint is demonstrably serving. Recorded here rather than at the end of the stream
        // because that is the moment that proves it, and it is the moment another caller's stuck
        // request needs to be judged against (see EndpointActivity).
        EndpointActivity.succeeded(baseUrl);

        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()));
        // The place goes back when the answer has been read to its end, when reading it fails,
        // or when the caller closes the stream - whichever comes first.
        java.util.Iterator<String> raw = reader.lines().iterator();
        java.util.Iterator<String> lines = new java.util.Iterator<>() {
            @Override
            public boolean hasNext() {
                try {
                    boolean more = raw.hasNext();
                    if (!more) {
                        place.close();
                    }
                    return more;
                } catch (RuntimeException e) {
                    place.close();
                    throw e;
                }
            }

            @Override
            public String next() {
                return raw.next();
            }
        };
        return java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(
                    lines, java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL), false)
                .onClose(place::close)
                .filter(line -> {
                    if (line.equals("data: [DONE]")) {
                        metered.end(); // the whole answer is in: this is how long the call took
                    }
                    return line.startsWith("data: ") && !line.equals("data: [DONE]");
                })
                .map(line -> {
                    try {
                        JsonNode node = mapper.readTree(line.substring(6));
                        recordUsage(metered, node);
                        JsonNode delta = node.at("/choices/0/delta/content");
                        JsonNode thought = node.at("/choices/0/delta/reasoning_content");
                        boolean wrote = !(delta.isMissingNode() || delta.isNull()
                            || delta.asText().isEmpty());
                        boolean reasoned = !(thought.isMissingNode() || thought.isNull()
                            || thought.asText().isEmpty());
                        if (wrote || reasoned) {
                            // The server has read the prompt and is writing: from here to the end
                            // is generation, which is what a tokens-per-second figure is about.
                            metered.firstToken();
                        }
                        return delta.isMissingNode() || delta.isNull() ? "" : delta.asText();
                    } catch (Exception e) {
                        return "";
                    }
                });
    }
}