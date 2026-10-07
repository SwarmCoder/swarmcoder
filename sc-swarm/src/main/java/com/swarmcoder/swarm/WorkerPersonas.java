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

import java.util.List;

/**
 * The per-worker personas — one of the swarm's three diversity levers, alongside the temperature
 * spread and (when more than one model is configured) the model family.
 *
 * <p>The persona is a short instruction appended AFTER the shared prompt prefix, so the group's
 * prefill is still paid once. It is what makes ten workers on one model disagree usefully: one
 * makes the smallest change that passes, another handles the edge cases, another is willing to
 * tidy structure. The verification gate then decides between them.
 *
 * <p>This list exists because the default was {@code List.of("minimal-diff")} — a single persona
 * handed to every worker in the group, which is a diversity lever that quietly does nothing.
 * That mattered less while two model families were configured. With one model on the box it is
 * one of only two levers left.
 */
public final class WorkerPersonas {

    /**
     * Rotated across the workers of a group when config names none. Every entry has a matching
     * instruction in {@link #text(String)}; an id with no match is passed to the model verbatim,
     * so an operator can write a persona in config without a code change.
     */
    public static final List<String> DEFAULT_ROTATION =
        List.of("minimal-diff", "test-literalist", "defensive-edges", "refactor-friendly");

    private WorkerPersonas() {}

    /** The instruction text for a persona id; an unknown id is used as the instruction itself. */
    public static String text(String personaId) {
        if (personaId == null || personaId.isBlank()) {
            return null;
        }
        return switch (personaId) {
            case "minimal-diff" -> "make the smallest change that satisfies the task; avoid refactoring";
            case "test-literalist" -> "implement exactly what the acceptance tests require, literally";
            case "defensive-edges" -> "handle edge cases and invalid inputs defensively";
            case "refactor-friendly" -> "prefer clean structure; small refactors are acceptable when they simplify the change";
            default -> personaId;
        };
    }
}
