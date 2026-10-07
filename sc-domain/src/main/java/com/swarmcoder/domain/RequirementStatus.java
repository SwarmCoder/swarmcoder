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

/**
 * Lifecycle of a BRD requirement. {@link #DRAFT}: captured but not yet agreed. {@link #ACTIVE}:
 * accepted and in scope — the set the Architect designs against. {@link #IMPLEMENTED}: delivered
 * by a run. {@link #DEPRECATED}: no longer in scope but kept for history/traceability.
 */
public enum RequirementStatus {
    DRAFT, ACTIVE, IMPLEMENTED, DEPRECATED;

    /**
     * The same status in the words the operator reads (UX v3 §4: a requirement is a draft
     * until it is agreed). One wording, defined once, so a badge and a sentence about the same
     * requirement can never disagree.
     */
    public String label() {
        return switch (this) {
            case DRAFT -> "draft";
            case ACTIVE -> "agreed";
            case IMPLEMENTED -> "delivered";
            case DEPRECATED -> "retired";
        };
    }
}
