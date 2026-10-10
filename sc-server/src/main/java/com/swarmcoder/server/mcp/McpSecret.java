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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The secret a caller must present before any MCP tool that changes something is run.
 *
 * <p>The MCP port is loopback only, but loopback is every program on the machine. Reading what a
 * build is doing is one thing; agreeing requirements, accepting deliveries and restarting builds is
 * another, and those are not offered to whatever happens to find the port. The secret is made the
 * first time it is needed, kept in a file beside the settings file, and sent by the client as
 * {@code Authorization: Bearer <secret>}.
 *
 * <p><b>It is never logged and never returned by any tool.</b> The only way to learn it is to read
 * the file, which is the same trust as reading the settings file with its paid key.
 */
public final class McpSecret {

    /** The file's name, under the SwarmCoder home folder. */
    public static final String FILE_NAME = "mcp-secret";

    private McpSecret() {}

    /**
     * Reads the secret from {@code file}, creating it with a fresh random value when it is absent
     * or empty.
     *
     * @return the secret; never blank
     */
    public static String loadOrCreate(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, secret + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // A file system without owner-only permissions (Windows): the file sits in the user's
            // own home folder beside the settings file and is protected the way that one is.
        }
        return secret;
    }

    /**
     * Whether an {@code Authorization} header carries this secret. Compared in constant time, so
     * how long a refusal takes says nothing about how much of a guess was right.
     */
    public static boolean matches(String secret, String authorizationHeader) {
        if (secret == null || secret.isBlank() || authorizationHeader == null) {
            return false;
        }
        String presented = authorizationHeader.strip();
        if (presented.regionMatches(true, 0, "Bearer ", 0, 7)) {
            presented = presented.substring(7).strip();
        }
        return MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8));
    }
}
