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
package com.swarmcoder.domain;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The one switch that lets code a model wrote or chose run on this PC instead of in a container.
 *
 * <p>Owner decision (2026-10-02): <b>no code written or chosen by a model may run with access to
 * the PC's drives.</b> A model's shell command, a test it wrote, a build file it edited - all of
 * it runs in a container that sees only its own tree. An engine that has no container therefore
 * REFUSES to run such code. It does not fall back to this machine.
 *
 * <p>Two callers may lift that, each by name, and nothing else can - there is no system property
 * and no environment variable behind this class:
 * <ul>
 *   <li>the app, when the operator wrote {@code sandbox.enabled: false} in the settings file. It
 *       says so in the log and on the Console in plain words;</li>
 *   <li>a scripted unit test, through {@code @ModelCodeOnThisPc} in sc-testsupport. What such a
 *       test runs is its own fixture, not a model's output.</li>
 * </ul>
 *
 * <p>In sc-domain only because it is the one module both the product and sc-testsupport can see
 * without a dependency cycle. It is process state and is never persisted.
 */
public final class HostExecution {

    /** Who allowed it, and how many times (a test class and its nested classes may both ask). */
    private static final ConcurrentMap<String, Integer> ALLOWED_BY = new ConcurrentHashMap<>();

    private HostExecution() {
    }

    /** @param by who is allowing it, in words that can be shown to the operator */
    public static void allow(String by) {
        ALLOWED_BY.merge(name(by), 1, Integer::sum);
    }

    /** Takes back one {@link #allow} made under the same name. */
    public static void withdraw(String by) {
        ALLOWED_BY.computeIfPresent(name(by), (key, count) -> count <= 1 ? null : count - 1);
    }

    /** Who allowed model code to run on this PC, or empty when nobody did. */
    public static Optional<String> allowedBy() {
        return ALLOWED_BY.keySet().stream().sorted().findFirst();
    }

    /**
     * The sentence a caller fails with when it has no container and nobody allowed this PC.
     *
     * @param what what was about to run, e.g. {@code "the red check"}
     */
    public static String refusal(String what) {
        return "Refused to run " + what + " on this PC. It executes code a model wrote or chose, "
            + "and that runs only inside a container that cannot see this machine's drives. "
            + "There is no container here: this engine was given no Docker sandbox. Give it one "
            + "(the app does this unless sandbox.enabled is false), or, for a scripted test that "
            + "runs only its own fixture, put @ModelCodeOnThisPc on the test class.";
    }

    private static String name(String by) {
        return by == null || by.isBlank() ? "unnamed" : by.strip();
    }
}
