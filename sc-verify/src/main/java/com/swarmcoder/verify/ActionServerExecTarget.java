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
package com.swarmcoder.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.net.http.HttpTimeoutException;

/**
 * ExecTarget backed by the in-sandbox action server (spec §8.3) — the spec-compliant place
 * to run verification once sandboxes are live. Talks plain HTTP to {@code /exec} and
 * {@code /read}; directory listing and deletion are delegated to POSIX commands via
 * {@code /exec} since sandbox images are Linux.
 *
 * <p>Known gap: the current action server does not accept a timeout on {@code /exec};
 * the timeout is enforced client-side on the HTTP request as a stopgap. Fix in
 * sc-sandbox-action-server when hardening the sandbox path.
 */
public final class ActionServerExecTarget implements ExecTarget {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;

    public ActionServerExecTarget(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public ExecResult exec(String command, int timeoutSeconds) throws IOException {
        Instant start = Instant.now();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("command", MAPPER.valueToTree(new String[] {"sh", "-c", command}));
        payload.put("timeoutSec", timeoutSeconds);
        try {
            JsonNode resp = post("/exec", payload, timeoutSeconds + 30);
            int exitCode = resp.path("exitCode").asInt(-1);
            String output = resp.path("output").asText("");
            boolean timedOut = resp.path("timedOut").asBoolean(false);
            return new ExecResult(exitCode, output, timedOut, Duration.between(start, Instant.now()));
        } catch (HttpTimeoutException e) {
            return new ExecResult(-1, "[action server exec timed out client-side]", true,
                Duration.between(start, Instant.now()));
        }
    }

    @Override
    public String readFile(String relativePath, int maxBytes) throws IOException {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("path", relativePath);
        payload.put("maxBytes", maxBytes);
        JsonNode resp = post("/read", payload, 30);
        if (resp.has("error")) {
            return null;
        }
        String content = resp.path("content").asText("");
        return content.length() > maxBytes ? content.substring(0, maxBytes) : content;
    }

    @Override
    public List<String> listFiles(String relativeDir, String suffix) throws IOException {
        ExecResult result = exec(
            "find " + shellQuote(relativeDir) + " -type f -name " + shellQuote("*" + suffix) + " 2>/dev/null | sort",
            30);
        List<String> files = new ArrayList<>();
        if (result.exitCode() == 0 || !result.output().isBlank()) {
            for (String line : result.output().split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    files.add(trimmed.startsWith("./") ? trimmed.substring(2) : trimmed);
                }
            }
        }
        return files;
    }

    @Override
    public void deleteDir(String relativePath) throws IOException {
        exec("rm -rf " + shellQuote(relativePath), 30);
    }

    /**
     * Refuses, and the reason is exactly one thing: the action server's HTTP protocol has no
     * background-start endpoint. {@code /exec} runs a command and answers when it has finished,
     * so there is nothing to hold a long-running server open or to kill it afterwards. Adding
     * {@code /startService} + {@code /stopService} to sc-sandbox-action-server would remove this.
     *
     * <p>The network is <b>not</b> the reason, and an older version of this message implied it
     * was. Under {@code sandbox.network: none} this target is not used at all — nothing outside
     * the container can reach the action server's port, so the orchestrator drives the container
     * over the Docker Engine exec API through {@link SandboxExecTarget}, which does support
     * background services. Under {@code sandbox.network: bridge}, where this target IS used, the
     * port is published and a service started here would be perfectly reachable; the only thing
     * missing is the endpoint to start one.
     */
    @Override
    public ServiceHandle startService(String command) {
        throw new UnsupportedOperationException(
            "the in-sandbox action server's HTTP protocol has no background-start endpoint: "
            + "/exec runs a command to completion, so it cannot hold a server open. Browser "
            + "'serve' checks therefore need either the local target or SandboxExecTarget (the "
            + "Docker Engine exec transport used under network=none), both of which start "
            + "background services. Static-site checks work on every target, this one included");
    }

    private JsonNode post(String path, ObjectNode payload, int timeoutSeconds) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return MAPPER.readTree(response.body());
        } catch (HttpTimeoutException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted talking to action server", e);
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
