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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Scripted ExecTarget for pipeline tests: exact-match command → result, plus an in-memory
 * workspace file map for report files.
 */
final class FakeExecTarget implements ExecTarget {

    private final Map<String, ExecResult> scripted = new HashMap<>();
    private final Map<String, Map<String, String>> producedFiles = new HashMap<>();
    private final Map<String, String> files = new HashMap<>();
    final List<String> executedCommands = new ArrayList<>();
    final List<String> deletedDirs = new ArrayList<>();
    private Path localRoot;

    /** Sets the local workspace root reported via {@link #localRoot()} (for the LSP precheck). */
    FakeExecTarget localRoot(Path root) {
        this.localRoot = root;
        return this;
    }

    @Override
    public Optional<Path> localRoot() {
        return Optional.ofNullable(localRoot);
    }

    FakeExecTarget script(String command, int exitCode) {
        scripted.put(command, new ExecResult(exitCode, "output of: " + command, false, Duration.ofMillis(5)));
        return this;
    }

    /** Scripts a command with explicit output text (e.g. to simulate compiler errors). */
    FakeExecTarget scriptOutput(String command, int exitCode, String output) {
        scripted.put(command, new ExecResult(exitCode, output, false, Duration.ofMillis(5)));
        return this;
    }

    /** Scripts a command that also writes a file when it runs (e.g. a test run producing a JUnit XML report). */
    FakeExecTarget scriptProducing(String command, int exitCode, String producedPath, String producedContent) {
        script(command, exitCode);
        producedFiles.computeIfAbsent(command, k -> new HashMap<>()).put(producedPath, producedContent);
        return this;
    }

    FakeExecTarget file(String path, String content) {
        files.put(path, content);
        return this;
    }

    @Override
    public ExecResult exec(String command, int timeoutSeconds) {
        executedCommands.add(command);
        ExecResult result = scripted.get(command);
        if (result == null) {
            throw new AssertionError("Unscripted command executed: " + command);
        }
        Map<String, String> produced = producedFiles.get(command);
        if (produced != null) {
            files.putAll(produced);
        }
        return result;
    }

    @Override
    public String readFile(String relativePath, int maxBytes) {
        return files.get(relativePath);
    }

    @Override
    public List<String> listFiles(String relativeDir, String suffix) {
        String prefix = relativeDir.endsWith("/") ? relativeDir : relativeDir + "/";
        return files.keySet().stream()
            .filter(p -> p.startsWith(prefix) && p.endsWith(suffix))
            .sorted()
            .toList();
    }

    @Override
    public void deleteDir(String relativePath) {
        deletedDirs.add(relativePath);
        String prefix = relativePath.endsWith("/") ? relativePath : relativePath + "/";
        files.keySet().removeIf(p -> p.startsWith(prefix));
    }

    @Override
    public ServiceHandle startService(String command) {
        throw new UnsupportedOperationException("FakeExecTarget does not run services");
    }
}
