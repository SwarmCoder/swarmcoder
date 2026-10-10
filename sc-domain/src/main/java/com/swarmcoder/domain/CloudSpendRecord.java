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

import java.util.UUID;

/**
 * What the cloud token gate has counted for one run, story or project, kept so the count survives a
 * restart (stored in {@code StoreRoot.cloudSpend}, keyed by {@link #scopeId()}).
 *
 * <p>A mutable POJO because EclipseStore cannot persist records. The gate replaces an entry with a
 * fresh copy on every change instead of editing one in place.
 */
public class CloudSpendRecord {
    private UUID scopeId;
    /** RUN, STORY or PROJECT, as text so the enum in the runtime module can grow. */
    private String level;
    private long input;
    private long output;
    /** How many times this scope's limit was raised by the same amount again. */
    private int extensions;

    public CloudSpendRecord() {}

    public CloudSpendRecord(UUID scopeId, String level, long input, long output, int extensions) {
        this.scopeId = scopeId;
        this.level = level;
        this.input = input;
        this.output = output;
        this.extensions = extensions;
    }

    public UUID scopeId() { return scopeId; }
    public String level() { return level; }
    public long input() { return input; }
    public long output() { return output; }
    public int extensions() { return extensions; }
}
