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
package com.swarmcoder.swarm;

import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.verify.BuildBoxes;

import java.nio.file.Path;

/**
 * The container every test that talks to a real model runs model code in - the same one the app
 * uses by default ({@code DependencyGraph} builds its own from the same properties).
 *
 * <p>The harnesses used to build their engine by hand and never gave it a sandbox, so a model's
 * commands ran through cmd.exe on the workstation as the operator. One worker searched the whole
 * of C: for a source file ({@code dir /s /b C:\*ZeroZDbNode*.java}). There is no switch for going
 * back: when Docker or the image is missing the harness stops before it starts a worker and says
 * which one, and what to run. An engine given no sandbox refuses to run anything at all.
 *
 * <p>In sc-swarm's test jar so that sc-workflow's and sc-app's live tests share this one helper.
 * A scripted test with no model does not use it; it opts in to this PC with
 * {@code @ModelCodeOnThisPc}.
 */
public final class HarnessSandbox {

    private HarnessSandbox() {
    }

    /**
     * @throws IllegalStateException when no container can be started, saying why and what to do
     */
    public static DockerSandboxManager required() {
        DockerSandboxManager docker = new DockerSandboxManager(
            System.getProperty("swarmcoder.sandbox.image", "swarmcoder-worker:latest"),
            Integer.getInteger("swarmcoder.sandbox.cpus", 2),
            Integer.getInteger("swarmcoder.sandbox.memGb", 4),
            System.getProperty("swarmcoder.sandbox.dockerHost"),
            System.getProperty("swarmcoder.sandbox.m2",
                Path.of(System.getProperty("user.home"), ".m2").toString()));
        docker.whyUnusable().ifPresent(why -> {
            throw new IllegalStateException(why);
        });
        return docker;
    }

    /**
     * Containers for the harness's own builds of model-written code: the baseline suite, the
     * toolchain check, the checks against the maintainer's fix.
     *
     * @throws IllegalStateException as {@link #required()}
     */
    public static BuildBoxes boxes() {
        return BuildBoxes.of(required());
    }
}
