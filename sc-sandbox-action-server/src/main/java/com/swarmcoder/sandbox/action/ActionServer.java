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
package com.swarmcoder.sandbox.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class ActionServer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path WORKSPACE = Paths.get("/workspace");
    private static Set<String> writeSet = new HashSet<>();

    /**
     * Port this server listens on: {@code SC_ACTION_PORT}, or 8080 when unset.
     *
     * <p>It used to be 8080 and nothing else, and that was a trap rather than a default. The
     * container also holds the candidate's own application, and 8080 is the port a Java web
     * application is most likely to bind — the demo repository in this checkout hardcodes exactly
     * that. When both wanted it, this server won (it starts first, as the container's command) and
     * the candidate's application lost the bind while still logging that it had started. Anything
     * then pointed at 8080 got THIS server's 404 and read it as a broken application. So the
     * orchestrator now moves this server out of the way and tells it where to sit.
     */
    private static int port() {
        String configured = System.getenv("SC_ACTION_PORT");
        if (configured == null || configured.isBlank()) {
            return 8080;
        }
        try {
            return Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            System.err.println("SC_ACTION_PORT=" + configured + " is not a number; using 8080");
            return 8080;
        }
    }

    public static void main(String[] args) throws IOException {
        String writeSetEnv = System.getenv("SC_WRITE_SET");
        if (writeSetEnv != null && !writeSetEnv.isEmpty()) {
            writeSet.addAll(Arrays.asList(writeSetEnv.split(",")));
        }

        int port = port();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        
        server.createContext("/health", exchange -> {
            sendResponse(exchange, 200, MAPPER.createObjectNode().put("status", "ok"));
        });
        
        server.createContext("/read", exchange -> {
            try {
                JsonNode req = MAPPER.readTree(exchange.getRequestBody());
                String filePath = req.get("path").asText();
                Path target = WORKSPACE.resolve(filePath).normalize();
                if (!target.startsWith(WORKSPACE)) {
                    sendResponse(exchange, 403, MAPPER.createObjectNode().put("error", "Access denied"));
                    return;
                }
                if (!Files.exists(target)) {
                    sendResponse(exchange, 404, MAPPER.createObjectNode().put("error", "File not found"));
                    return;
                }
                int maxBytes = req.has("maxBytes") ? req.get("maxBytes").asInt(Integer.MAX_VALUE) : Integer.MAX_VALUE;
                byte[] raw = Files.readAllBytes(target);
                String content = new String(raw, 0, Math.min(raw.length, maxBytes),
                    StandardCharsets.UTF_8);
                sendResponse(exchange, 200, MAPPER.createObjectNode().put("content", content));
            } catch (Exception e) {
                sendResponse(exchange, 500, MAPPER.createObjectNode().put("error", e.getMessage()));
            }
        });
        
        server.createContext("/write", exchange -> {
            try {
                JsonNode req = MAPPER.readTree(exchange.getRequestBody());
                String filePath = req.get("path").asText();
                String content = req.get("content").asText();
                Path target = WORKSPACE.resolve(filePath).normalize();
                
                if (!WriteSetEnforcer.isAllowed(target, WORKSPACE, writeSet)) {
                    sendResponse(exchange, 403, MAPPER.createObjectNode().put("error", "WriteSet violation"));
                    return;
                }
                
                Files.createDirectories(target.getParent());
                Files.writeString(target, content);
                sendResponse(exchange, 200, MAPPER.createObjectNode().put("status", "written"));
            } catch (Exception e) {
                sendResponse(exchange, 500, MAPPER.createObjectNode().put("error", e.getMessage()));
            }
        });
        
        server.createContext("/exec", exchange -> {
            try {
                JsonNode req = MAPPER.readTree(exchange.getRequestBody());
                String[] command = MAPPER.convertValue(req.get("command"), String[].class);
                int timeoutSec = req.has("timeoutSec") ? req.get("timeoutSec").asInt(1800) : 1800;

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(WORKSPACE.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();

                // Read on a separate thread so the timeout applies even to hung processes,
                // capped so a log-spewing build cannot exhaust the container's memory.
                StringBuffer output = new StringBuffer();
                Thread reader = Thread.ofVirtual().start(() -> readCapped(process, output));
                boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
                if (!finished) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
                reader.join(5000);

                ObjectNode resp = MAPPER.createObjectNode();
                resp.put("exitCode", finished ? process.exitValue() : -1);
                resp.put("timedOut", !finished);
                resp.put("output", output.toString());
                sendResponse(exchange, 200, resp);
            } catch (Exception e) {
                sendResponse(exchange, 500, MAPPER.createObjectNode().put("error", e.getMessage()));
            }
        });
        
        server.setExecutor(null);
        server.start();
        System.out.println("Action Server started on port " + port);
    }

    private static final int MAX_EXEC_OUTPUT_BYTES = 256 * 1024;

    private static void readCapped(Process process, StringBuffer sb) {
        byte[] buf = new byte[8192];
        try (InputStream in = process.getInputStream()) {
            int read;
            boolean truncated = false;
            while ((read = in.read(buf)) != -1) {
                if (sb.length() < MAX_EXEC_OUTPUT_BYTES) {
                    int keep = Math.min(read, MAX_EXEC_OUTPUT_BYTES - sb.length());
                    sb.append(new String(buf, 0, keep, StandardCharsets.UTF_8));
                    if (keep < read) truncated = true;
                } else {
                    truncated = true;
                }
            }
            if (truncated) {
                sb.append("\n[output truncated at ").append(MAX_EXEC_OUTPUT_BYTES).append(" bytes]");
            }
        } catch (IOException e) {
            // Process ended/killed — captured output stands.
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, JsonNode payload) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}