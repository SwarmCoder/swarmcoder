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
 * What one role looked up in one run, of one kind: how often, and how many characters came back
 * into its conversation (stored in {@link RunUsageRecord}).
 *
 * <p>The kind is kept as text with its label and whether it is a structured query (the syntax
 * tree, the language server, a document section), so the list of kinds can grow without this
 * class knowing it.
 *
 * <p>A mutable POJO because EclipseStore cannot persist records.
 */
public class LookupUsageRecord {
    private String role;
    private String kind;
    private String label;
    private boolean structured;
    private int calls;
    private long chars;

    public LookupUsageRecord() {}

    public LookupUsageRecord(String role, String kind, String label, boolean structured,
                             int calls, long chars) {
        this.role = role;
        this.kind = kind;
        this.label = label;
        this.structured = structured;
        this.calls = calls;
        this.chars = chars;
    }

    public void add(int moreCalls, long moreChars) {
        calls += moreCalls;
        chars += Math.max(0, moreChars);
    }

    public LookupUsageRecord copy() {
        return new LookupUsageRecord(role, kind, label, structured, calls, chars);
    }

    public String role() { return role; }
    public String kind() { return kind; }
    public String label() { return label; }
    public boolean structured() { return structured; }
    public int calls() { return calls; }
    public long chars() { return chars; }
}
