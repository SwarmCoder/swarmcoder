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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Loads {@code .swarmcoder/verify.yaml} from a workspace (spec §10.1). */
public final class VerifySpecLoader {

    public static final String SPEC_PATH = ".swarmcoder/verify.yaml";

    /**
     * Loads the spec from a TRUSTED root, falling back to the workspace when there is none.
     *
     * <p>The distinction is a security boundary, not a convenience. {@code verify.yaml} names the
     * commands the orchestrator runs to decide whether a candidate passed — and it runs them on the
     * HOST at integration, unsandboxed. Reading it from the workspace under test let a worker
     * rewrite its own examiner: certify itself green, and execute arbitrary commands on the
     * operator's machine. The recipe now comes from the operator's tree; only the code it runs
     * against is the candidate's.
     */
    public static java.util.Optional<VerifySpec> loadTrusted(Path trustedRoot, Path workspaceRoot) {
        if (trustedRoot != null) {
            java.util.Optional<VerifySpec> trusted = load(trustedRoot);
            if (trusted.isPresent()) {
                return trusted;
            }
        }
        return load(workspaceRoot);
    }

    private static final Logger log = LoggerFactory.getLogger(VerifySpecLoader.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private VerifySpecLoader() {}

    /** Loads the spec from a local workspace; empty when the file is absent or unreadable. */
    public static Optional<VerifySpec> load(Path workspaceRoot) {
        Path specFile = workspaceRoot.resolve(SPEC_PATH);
        if (!Files.isRegularFile(specFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(Files.readString(specFile)));
        } catch (IOException e) {
            log.warn("Unreadable {} in {}: {}", SPEC_PATH, workspaceRoot, e.getMessage());
            return Optional.empty();
        }
    }

    /** Parses spec YAML content directly (used when the workspace is only reachable via an ExecTarget). */
    public static VerifySpec parse(String yaml) throws IOException {
        return YAML.readValue(yaml, VerifySpec.class);
    }
}
