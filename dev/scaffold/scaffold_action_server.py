import os

ACTION_SERVER_DIR = "sc-sandbox-action-server/src/main/java/com/swarmcoder/sandbox/action"
os.makedirs(ACTION_SERVER_DIR, exist_ok=True)

models = {
    "ActionServer.java": """package com.swarmcoder.sandbox.action;

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

public class ActionServer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path WORKSPACE = Paths.get("/workspace");
    private static Set<String> writeSet = new HashSet<>();

    public static void main(String[] args) throws IOException {
        String writeSetEnv = System.getenv("SC_WRITE_SET");
        if (writeSetEnv != null && !writeSetEnv.isEmpty()) {
            writeSet.addAll(Arrays.asList(writeSetEnv.split(",")));
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
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
                String content = Files.readString(target);
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
                
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(WORKSPACE.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();
                
                ObjectNode resp = MAPPER.createObjectNode();
                resp.put("exitCode", exitCode);
                resp.put("output", output);
                sendResponse(exchange, 200, resp);
            } catch (Exception e) {
                sendResponse(exchange, 500, MAPPER.createObjectNode().put("error", e.getMessage()));
            }
        });
        
        server.setExecutor(null);
        server.start();
        System.out.println("Action Server started on port 8080");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, JsonNode payload) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}""",

    "WriteSetEnforcer.java": """package com.swarmcoder.sandbox.action;

import java.nio.file.Path;
import java.util.Set;

public class WriteSetEnforcer {
    public static boolean isAllowed(Path targetPath, Path workspace, Set<String> writeSet) {
        if (!targetPath.startsWith(workspace)) {
            return false;
        }
        if (writeSet.contains("*")) {
            return true;
        }
        String relative = workspace.relativize(targetPath).toString().replace('\\\\', '/');
        for (String pattern : writeSet) {
            if (pattern.endsWith("/") && relative.startsWith(pattern)) {
                return true;
            } else if (relative.equals(pattern)) {
                return true;
            }
        }
        return false;
    }
}"""
}

for filename, content in models.items():
    filepath = os.path.join(ACTION_SERVER_DIR, filename)
    with open(filepath, "w") as f:
        f.write(content)

print(f"Generated {len(models)} action server files.")
