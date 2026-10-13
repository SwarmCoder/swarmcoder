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
    /**
     * How many of {@link #calls} were about the reference material - the source and documents
     * of what the project builds on - and the characters those returned. Added with section 95;
     * a record stored before it reads 0 for both.
     */
    private int referenceCalls;
    private long referenceChars;
    /**
     * How many of {@link #calls} asked a question a lookup had already answered earlier in the
     * same run, to this role or to another, and the characters those returned. Added with
     * section 102; a record stored before it reads 0 for both.
     */
    private int repeatCalls;
    private long repeatChars;

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

    /** Of the lookups already added, this many were about the reference material. */
    public void addReference(int moreCalls, long moreChars) {
        referenceCalls += moreCalls;
        referenceChars += Math.max(0, moreChars);
    }

    /** Of the lookups already added, this many repeated a question answered before. */
    public void addRepeat(int moreCalls, long moreChars) {
        repeatCalls += moreCalls;
        repeatChars += Math.max(0, moreChars);
    }

    public LookupUsageRecord copy() {
        LookupUsageRecord copy = new LookupUsageRecord(role, kind, label, structured, calls, chars);
        copy.addReference(referenceCalls, referenceChars);
        copy.addRepeat(repeatCalls, repeatChars);
        return copy;
    }

    public String role() { return role; }
    public String kind() { return kind; }
    public String label() { return label; }
    public boolean structured() { return structured; }
    public int calls() { return calls; }
    public long chars() { return chars; }
    public int referenceCalls() { return referenceCalls; }
    public long referenceChars() { return referenceChars; }
    public int repeatCalls() { return repeatCalls; }
    public long repeatChars() { return repeatChars; }
}
