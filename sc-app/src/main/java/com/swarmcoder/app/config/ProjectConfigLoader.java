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
package com.swarmcoder.app.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;

/** Loads {@code <projectPath>/.swarmcoder/project.yaml} into a {@link ProjectConfig}. */
public final class ProjectConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigLoader.class);

    private ProjectConfigLoader() {}

    /** Writes the project's config (creating {@code .swarmcoder/} if needed). */
    public static void save(String primaryPath, ProjectConfig config) throws IOException {
        Path dir = Paths.get(primaryPath, ".swarmcoder");
        Files.createDirectories(dir);
        String yaml = new ObjectMapper(new YAMLFactory()).writeValueAsString(config);
        Files.writeString(dir.resolve("project.yaml"), yaml);
    }

    /** Returns the project's config, or null when the path is unset or has no {@code project.yaml}. */
    public static ProjectConfig load(String primaryPath) {
        if (primaryPath == null || primaryPath.isBlank()) {
            return null;
        }
        Path file = Paths.get(primaryPath, ".swarmcoder", "project.yaml");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(Files.readString(file), ProjectConfig.class);
        } catch (Exception e) {
            log.warn("Ignoring unreadable project.yaml at {}: {}", file, e.getMessage());
            return null;
        }
    }
}
