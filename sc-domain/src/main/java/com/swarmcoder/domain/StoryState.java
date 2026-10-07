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
 * Lifecycle of a {@link Story}. {@link #DRAFT}: sketched, not yet fit to schedule.
 * {@link #READY}: refined and schedulable. {@link #RUNNING}: a run is working it.
 * {@link #REVIEW}: work is done and awaiting human or judge approval. {@link #DONE}: accepted and
 * integrated. {@link #BLOCKED}: cannot progress until something else resolves.
 * {@link #CANCELLED}: dropped without delivery, kept for history.
 */
public enum StoryState {
    DRAFT, READY, RUNNING, REVIEW, DONE, BLOCKED, CANCELLED;

    /**
     * The same state in the words the operator reads, from the normative table in
     * {@code docs/CONSOLE_UX_V3.md} §4.
     *
     * <p>It lives on the enum so there is exactly one wording of each state anywhere in the
     * product (UX v3 §5 rule 2). Everything that has to name a state to a person — the
     * board, the story dialog, a refusal coming back from the server — asks here. Before
     * this, several of them just printed the constant, so a person who had never seen the source
     * was shown READY, RUNNING and CANCELLED and had to work out what they meant.
     */
    public String label() {
        return switch (this) {
            case DRAFT -> "suggested";
            case READY -> "ready to build";
            case RUNNING -> "building";
            case REVIEW -> "back for your verdict";
            case DONE -> "delivered";
            case BLOCKED -> "stopped";
            case CANCELLED -> "dropped";
        };
    }
}
