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
package com.swarmcoder.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The prompt-prefix discipline (spec §6.3). A bundle is an ordered, deterministic sequence
 * of shared segments — byte-identical for every worker in a group — followed by per-worker
 * segments (persona, sampling hints) appended strictly AFTER the shared prefix. On the
 * Spark, vLLM's prefix cache then computes the expensive shared prefill once and reuses it
 * N times; this class exists to make that alignment an invariant instead of an accident.
 *
 * <p>Segment order is fixed by the builder (systemRole, workflowRules, projectConstraints,
 * taskInstructions, knowledgeBrief); null/blank segments are skipped without disturbing the
 * order of the rest. {@link #prefixHash()} is the SHA-256 of the shared text — logged per
 * dispatch and comparable across runs: any change in the hash means the prefill cache goes
 * cold.
 *
 * <h2>Size is the cost, not prefill time</h2>
 *
 * <p>The prefix is shared, so the server prefills it once for the whole group and shrinking it
 * buys almost no time. What it buys is ROOM: every token here is a token unavailable to the work,
 * it is charged against the model's working context, and {@code HistoryTrim} starts throwing away
 * the worker's own history once the conversation passes three quarters of that. A fat prefix
 * pushes a worker into compaction sooner. {@link #estimatedTokens()} and
 * {@link #segmentTokens()} are that number, measured the same crude way {@code HistoryTrim}
 * measures everything else, and logged on every dispatch.
 */
public final class PromptBundle {

    /**
     * Fixed segment order — spec §6.3.
     *
     * <p>{@code PROJECT_CONSTRAINTS} sits high on purpose. It carries the project's standing
     * rules — the language, the frameworks, what is forbidden, and every lesson the project has
     * learned — and it changes less often than anything below it, so putting it early keeps the
     * shared prefill warm across every task in a project. It is also what a worker must know
     * BEFORE it reads its task, not after.
     *
     * <p>There was a separate {@code GUIDELINES} segment beside it for a few hours on 2026-08-31,
     * while the project's rules were being kept in two places at once. There is one place, so
     * there is one segment: two would have sent every rule to every worker twice.
     *
     * <p><b>{@code DESIGN_EXCERPT} and {@code REPO_MAP} are gone (2026-09-02).</b> They were in
     * the spec's list and in this enum, with builder methods, from the first day — and in three
     * months nothing ever called either one. Every measurement of "what does a worker's prefix
     * cost" had to establish that fact again before it could start, and a reader looking for the
     * repository map a worker is given would have gone looking for its size. There is no such
     * map: a worker is given its write set and its tools, and it lists what it needs. Two dead
     * levers that look live are worse than two missing features.
     */
    public enum SegmentKind { SYSTEM_ROLE, WORKFLOW_RULES, PROJECT_CONSTRAINTS,
        TASK_INSTRUCTIONS, KNOWLEDGE_BRIEF }

    public record Segment(SegmentKind kind, String content) {}

    private final List<Segment> shared;
    private final String sharedText;
    private final String prefixHash;

    private PromptBundle(List<Segment> shared) {
        this.shared = List.copyOf(shared);
        StringBuilder sb = new StringBuilder();
        for (Segment segment : shared) {
            // Deterministic framing: the header makes segments self-describing in traces
            // and guarantees distinct segment splits can never collide to one byte stream.
            sb.append("## ").append(segment.kind().name()).append('\n')
              .append(segment.content().stripTrailing()).append("\n\n");
        }
        this.sharedText = sb.toString();
        this.prefixHash = sha256(sharedText);
    }

    /** The byte-identical shared prefix for every worker in the group. */
    public String sharedText() {
        return sharedText;
    }

    /** SHA-256 of the shared prefix — the dispatch log's cache-alignment fingerprint. */
    public String prefixHash() {
        return prefixHash;
    }

    public List<Segment> segments() {
        return shared;
    }

    /**
     * Characters per token — {@code HistoryTrim}'s own constant, repeated here rather than shared
     * so the two modules stay independent. Crude on purpose: the exact number belongs to the
     * tokenizer, and this is used to report a size and decide when to act, never to bill anybody.
     */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * What this prefix costs the worker, in tokens, before it has read a word of its own history.
     *
     * <p>This is the number to show beside a worker, and the one to watch: it is charged against
     * the same working context the conversation grows into, and it is the floor
     * {@code HistoryTrim} can never compact away.
     */
    public int estimatedTokens() {
        return sharedText.length() / CHARS_PER_TOKEN;
    }

    /** The same measurement per segment, in prefix order — which segment is actually costing. */
    public List<SegmentSize> segmentTokens() {
        List<SegmentSize> sizes = new ArrayList<>();
        for (Segment segment : shared) {
            int chars = segment.content().stripTrailing().length();
            sizes.add(new SegmentSize(segment.kind(), chars, chars / CHARS_PER_TOKEN));
        }
        return List.copyOf(sizes);
    }

    /** One segment's measured size. */
    public record SegmentSize(SegmentKind kind, int chars, int tokens) {}

    /** The per-segment sizes as one log line: {@code KNOWLEDGE_BRIEF=2301 PROJECT_...=1248}. */
    public String sizeSummary() {
        StringBuilder sb = new StringBuilder();
        for (SegmentSize size : segmentTokens()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(size.kind().name()).append('=').append(size.tokens());
        }
        return sb.toString();
    }

    /**
     * The full system prompt for one worker: shared prefix first, diversity content after —
     * never interleaved (spec §4.4: diversity is appended post-prefix).
     */
    public String forWorker(String persona, String samplingHints) {
        StringBuilder sb = new StringBuilder(sharedText);
        if (persona != null && !persona.isBlank()) {
            sb.append("## PERSONA\n").append(persona.stripTrailing()).append("\n\n");
        }
        if (samplingHints != null && !samplingHints.isBlank()) {
            sb.append("## HINTS\n").append(samplingHints.stripTrailing()).append("\n\n");
        }
        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final String[] contents = new String[SegmentKind.values().length];

        public Builder systemRole(String content) { return set(SegmentKind.SYSTEM_ROLE, content); }

        public Builder workflowRules(String content) { return set(SegmentKind.WORKFLOW_RULES, content); }

        /**
         * The project's standing rules — how it must be built, and what it has learned. Rendered
         * by {@code ConstraintBrief} from the ACTIVE guidelines; blank when the project has none,
         * and the prefix is then byte-for-byte what it was before rules existed.
         */
        public Builder projectConstraints(String content) {
            return set(SegmentKind.PROJECT_CONSTRAINTS, content);
        }

        public Builder taskInstructions(String content) { return set(SegmentKind.TASK_INSTRUCTIONS, content); }

        public Builder knowledgeBrief(String content) { return set(SegmentKind.KNOWLEDGE_BRIEF, content); }

        private Builder set(SegmentKind kind, String content) {
            contents[kind.ordinal()] = content;
            return this;
        }

        public PromptBundle build() {
            List<Segment> segments = new ArrayList<>();
            for (SegmentKind kind : SegmentKind.values()) {
                String content = contents[kind.ordinal()];
                if (content != null && !content.isBlank()) {
                    segments.add(new Segment(kind, content));
                }
            }
            if (segments.isEmpty()) {
                throw new IllegalStateException("A PromptBundle needs at least one segment");
            }
            return new PromptBundle(segments);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
