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
package com.swarmcoder.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.FileAlreadyExistsException;

public class BlobStore {
    private final Path blobDir;

    public BlobStore(Path blobDir) throws IOException {
        this.blobDir = blobDir;
        if (!Files.exists(blobDir)) {
            Files.createDirectories(blobDir);
        }
    }

    public String storeBlob(byte[] content) throws IOException {
        String hash = computeSha256(content);
        Path target = blobDir.resolve(hash);
        if (!Files.exists(target)) {
            try {
                Files.write(target, content, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException e) {
                // Concurrent writers of identical content (same hash) — already stored.
            }
        }
        return hash;
    }

    public byte[] getBlob(String hash) throws IOException {
        return Files.readAllBytes(blobDir.resolve(hash));
    }

    private String computeSha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
