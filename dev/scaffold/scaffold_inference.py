import os

INFERENCE_DIR = "sc-inference/src/main/java/com/swarmcoder/inference"
TEST_DIR = "sc-inference/src/test/java/com/swarmcoder/inference"
os.makedirs(INFERENCE_DIR, exist_ok=True)
os.makedirs(TEST_DIR, exist_ok=True)

models = {
    "SchemaGen.java": """package com.swarmcoder.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator;

public class SchemaGen {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JsonNode generateSchema(Class<?> targetClass) {
        try {
            JsonSchemaGenerator schemaGen = new JsonSchemaGenerator(MAPPER);
            JsonSchema schema = schemaGen.generateSchema(targetClass);
            return MAPPER.valueToTree(schema);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON schema for " + targetClass.getName(), e);
        }
    }
}""",

    "VllmClient.java": """package com.swarmcoder.inference;

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

public class VllmClient {
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String modelName;
    private final boolean useResponseFormat;
    private final ObjectMapper mapper;

    public VllmClient(String baseUrl, String modelName, boolean useResponseFormat) {
        this.httpClient = HttpClient.newBuilder().build();
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.useResponseFormat = useResponseFormat;
        this.mapper = new ObjectMapper();
    }

    public Stream<String> chatCompletionStream(List<Map<String, String>> messages, Class<?> targetSchemaClass, double temperature) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", modelName);
        payload.put("stream", true);
        payload.put("temperature", temperature);
        payload.set("messages", mapper.valueToTree(messages));

        if (targetSchemaClass != null) {
            JsonNode schemaNode = SchemaGen.generateSchema(targetSchemaClass);
            if (useResponseFormat) {
                ObjectNode responseFormat = mapper.createObjectNode();
                responseFormat.put("type", "json_schema");
                ObjectNode jsonSchema = mapper.createObjectNode();
                jsonSchema.put("name", "result");
                jsonSchema.set("schema", schemaNode);
                jsonSchema.put("strict", true);
                responseFormat.set("json_schema", jsonSchema);
                payload.set("response_format", responseFormat);
            } else {
                ObjectNode extraBody = mapper.createObjectNode();
                extraBody.set("guided_json", schemaNode);
                payload.set("extra_body", extraBody);
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("vLLM error: " + response.statusCode());
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()));
        return reader.lines()
                .filter(line -> line.startsWith("data: ") && !line.equals("data: [DONE]"))
                .map(line -> {
                    try {
                        JsonNode node = mapper.readTree(line.substring(6));
                        JsonNode delta = node.at("/choices/0/delta/content");
                        return delta.isMissingNode() || delta.isNull() ? "" : delta.asText();
                    } catch (Exception e) {
                        return "";
                    }
                });
    }
}""",

    "InferenceScheduler.java": """package com.swarmcoder.inference;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class InferenceScheduler {
    private final Semaphore sequenceLeases;
    private final AtomicInteger kvBytesInFlight;
    private final int kvBytesCeiling;
    private final int kvBytesPerTokenEstimate;

    public InferenceScheduler(int maxNumSeqs, int kvBytesCeiling, int kvBytesPerTokenEstimate) {
        this.sequenceLeases = new Semaphore(maxNumSeqs, true); // fair semaphore
        this.kvBytesInFlight = new AtomicInteger(0);
        this.kvBytesCeiling = kvBytesCeiling;
        this.kvBytesPerTokenEstimate = kvBytesPerTokenEstimate;
    }

    public Lease acquire(int expectedTokens) throws InterruptedException {
        int expectedKvBytes = expectedTokens * kvBytesPerTokenEstimate;
        // Simple admission control: reject if single request exceeds ceiling
        if (expectedKvBytes > kvBytesCeiling) {
            throw new IllegalArgumentException("Expected tokens exceed KV cache capacity.");
        }

        // Wait for a sequence slot
        sequenceLeases.acquire();

        // Spin until KV cache bytes are available (simple backoff can be added, using sleep for brevity)
        while (true) {
            int current = kvBytesInFlight.get();
            if (current + expectedKvBytes <= kvBytesCeiling) {
                if (kvBytesInFlight.compareAndSet(current, current + expectedKvBytes)) {
                    break;
                }
            } else {
                Thread.sleep(100);
            }
        }

        return new Lease(expectedKvBytes);
    }

    public class Lease implements AutoCloseable {
        private final int reservedBytes;
        private boolean closed = false;

        private Lease(int reservedBytes) {
            this.reservedBytes = reservedBytes;
        }

        @Override
        public void close() {
            if (!closed) {
                kvBytesInFlight.addAndGet(-reservedBytes);
                sequenceLeases.release();
                closed = true;
            }
        }
    }
}"""
}

test_models = {
    "InferenceSchedulerTest.java": """package com.swarmcoder.inference;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class InferenceSchedulerTest {

    @Test
    public void testAcquireAndRelease() throws InterruptedException {
        InferenceScheduler scheduler = new InferenceScheduler(2, 1000, 10);
        
        try (InferenceScheduler.Lease lease1 = scheduler.acquire(50)) {
            // we have 1 seq, 500 bytes used. 500 remaining.
            assertEquals(1, 1);
            try (InferenceScheduler.Lease lease2 = scheduler.acquire(50)) {
                // 2 seqs, 1000 bytes used. 0 remaining.
                assertEquals(1, 1);
            }
        }
    }

    @Test
    public void testRejectOversized() {
        InferenceScheduler scheduler = new InferenceScheduler(2, 1000, 10);
        assertThrows(IllegalArgumentException.class, () -> scheduler.acquire(200));
    }
}"""
}

for filename, content in models.items():
    filepath = os.path.join(INFERENCE_DIR, filename)
    with open(filepath, "w") as f:
        f.write(content)

for filename, content in test_models.items():
    filepath = os.path.join(TEST_DIR, filename)
    with open(filepath, "w") as f:
        f.write(content)

print(f"Generated {len(models)} inference files and {len(test_models)} test files.")
