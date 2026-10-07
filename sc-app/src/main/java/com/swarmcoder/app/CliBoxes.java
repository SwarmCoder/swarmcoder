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
package com.swarmcoder.app;

import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.SandboxConfig;
import com.swarmcoder.app.config.SwarmConfig;
import com.swarmcoder.domain.HostExecution;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.verify.BuildBoxes;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * The containers a command-line tool runs builds in, made the way the app makes them: from the
 * settings file's {@code sandbox:} block, with the same default (a container) and the same single
 * off-switch ({@code sandbox.enabled: false}). Unlike the app it never writes a starter settings
 * file: a command that only reads must not create one.
 */
final class CliBoxes {

    private CliBoxes() {}

    static BuildBoxes fromSettings() {
        SwarmConfig config = null;
        try {
            if (Files.exists(ConfigLoader.configPath())) {
                config = ConfigLoader.loadDefaultConfig();
            }
        } catch (Exception e) {
            // An unreadable settings file means the defaults, which are the safe ones.
        }
        SandboxConfig sbx = config != null && config.sandbox() != null
            ? config.sandbox() : new SandboxConfig(2, 4, 2);
        String override = System.getProperty("swarmcoder.sandbox.enabled");
        boolean enabled = override != null && !override.isBlank()
            ? Boolean.parseBoolean(override) : sbx.enabledOrDefault();
        if (!enabled) {
            HostExecution.allow("sandbox.enabled: false in " + ConfigLoader.configPath());
            return BuildBoxes.none();
        }
        DockerSandboxManager manager = new DockerSandboxManager(
            System.getProperty("swarmcoder.sandbox.image", "swarmcoder-worker:latest"),
            Math.max(1, sbx.cpus()), Math.max(1, sbx.memGb()),
            sbx.pidsLimitOrDefault(), sbx.networkPolicy(), sbx.allowedHosts(),
            sbx.requiredOrDefault(),
            System.getProperty("swarmcoder.sandbox.dockerHost"),
            System.getProperty("swarmcoder.sandbox.m2",
                Paths.get(System.getProperty("user.home"), ".m2").toString()));
        return new BuildBoxes(() -> manager, Map::of);
    }
}
