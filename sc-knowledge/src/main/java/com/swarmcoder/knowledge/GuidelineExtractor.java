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
package com.swarmcoder.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guideline extraction (spec §12.4): the utility model mines completed session transcripts for
 * durable instructions — user-stated constraints, repo conventions discovered, root causes of
 * failures — and records them as PROPOSED rules of the project. A PROPOSED rule enters no prompt
 * until a person switches it on (or {@code guidelines.autoPromote} writes it ACTIVE).
 *
 * <p>This closes the learning loop: runs produce rules that improve future runs, exactly the
 * "core instructions saved as guides so they aren't forgotten" the author asked for. The rules
 * are store objects like every other rule ({@link ProjectRules}); this class used to write files.
 *
 * <h2>One guideline per lesson — the model decides, not a word count (author decision, §21)</h2>
 *
 * <p>Dedup used to be word overlap alone: normalized-token Jaccard over the whole body, with
 * anything at or above 0.6 counted a duplicate. Guideline bodies are one or two sentences of
 * prose, so two rules stating the same lesson in different words share only a fraction of their
 * vocabulary union. Measured on the 47 rules a real project had accumulated, the HIGHEST Jaccard
 * of any of the 1081 pairs was 0.481 — the threshold caught nothing at all, and the project grew a
 * fresh copy of the same lesson on every run: seven ways of saying "look the API up instead of
 * picking apart compiled classes", six of "write code early and let the build tell you what is
 * wrong", four of "git does not work here".
 *
 * <p>The gate is now the model that is already being prompted. Every rule the project already has
 * — name and text — is put in the prompt, with an instruction to return nothing for a lesson
 * already covered and to name the rule that covers it ({@code duplicateOf}). Semantic judgement is
 * exactly what a language model is for, and it costs one extra block in a request that was being
 * sent anyway.
 *
 * <p>Word overlap stays as a BACKSTOP for a model that ignores the instruction, but as an overlap
 * COEFFICIENT over content words — shared words divided by the smaller of the two sets, stop words
 * dropped — because that measure does not punish a candidate for being longer than the rule it
 * repeats. On those same 47 real rules, 0.5 flags 21 pairs and every one of them is a genuine
 * repeat; nothing else in the corpus reaches it. That is where {@link #REPEAT_OVERLAP} is set, and
 * why. It is deliberately the weaker gate: it cannot see that "do not infer APIs by unpacking
 * jars" and "do not inspect compiled classes with javap" are one lesson (they share four content
 * words), and it is not asked to.
 */
public class GuidelineExtractor {

    public static class LlmGuidelines {
        public List<LlmGuideline> guidelines;
    }
    public static class LlmGuideline {
        public String slug;   // filename-safe
        public String scope;  // GLOBAL | PROJECT | TASK_FAMILY
        public String body;
        /** Set by the model when an existing rule already covers this lesson; then nothing is written. */
        public String duplicateOf;
    }

    /** One rule the project already has, as the prompt and the backstop both see it. */
    private record Existing(String slug, String body) {}

    private static final Logger log = LoggerFactory.getLogger(GuidelineExtractor.class);
    private static final int MAX_TRANSCRIPT_CHARS = 20_000;
    private static final int MAX_NEW_PER_RUN = 5;

    /**
     * Backstop threshold: shared content words divided by the smaller of the two content-word
     * sets. Measured on a real 47-rule folder, 0.5 flags 21 pairs and all 21 are genuine repeats,
     * while nothing that is a distinct lesson reaches it (the nearest false positive sits at
     * 0.462). See the class javadoc for why this replaced Jaccard ≥ 0.6, which flagged zero.
     */
    private static final double REPEAT_OVERLAP = 0.5;

    /** Below this many content words a body is too short for the overlap ratio to mean anything. */
    private static final int MIN_CONTENT_TOKENS = 5;

    /** How much of each existing rule is shown to the model. Bodies are a sentence or two. */
    private static final int GIST_CHARS = 240;

    /** A cap on the prompt block, so a project with hundreds of rules cannot blow the context. */
    private static final int MAX_EXISTING_SHOWN = 120;

    /**
     * Words carrying no lesson. Dropped before the overlap ratio, so "do not use git commands in
     * this workspace" and "git commands fail here" are compared on git/commands/workspace, not on
     * do/not/use/this/in.
     */
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "that", "with", "not", "this", "than", "into", "their", "them",
        "are", "was", "you", "your", "but", "its", "from", "all", "any", "can", "use", "uses",
        "using", "instead", "because", "before", "after", "when", "where", "which", "what",
        "how", "has", "have", "had", "does", "did", "will", "would", "should", "must", "may",
        "might", "one", "two", "only", "own", "same", "other", "others", "each", "every",
        "some", "such", "more", "most", "then", "there", "these", "those", "they");

    private final VllmClient utility;
    private final CloudGate cloudGate;
    private final ProjectRules rules;
    private final boolean autoPromote;
    private final ObjectMapper mapper = new ObjectMapper();

    public GuidelineExtractor(VllmClient utility, CloudGate cloudGate, ProjectRules rules) {
        this(utility, cloudGate, rules, false);
    }

    /**
     * @param autoPromote {@code guidelines.autoPromote} (§7 Q4): when true, extracted rules are
     *                    written ACTIVE and enter prompts immediately; the default false writes
     *                    PROPOSED for human review.
     */
    public GuidelineExtractor(VllmClient utility, CloudGate cloudGate, ProjectRules rules,
                              boolean autoPromote) {
        this.utility = utility;
        this.cloudGate = cloudGate;
        this.rules = rules;
        this.autoPromote = autoPromote;
    }

    /**
     * Extracts candidate rules from a run's sessions, recording genuinely-new ones as PROPOSED.
     * Returns the names written.
     */
    public List<String> extractFromSessions(List<AgentSessionRecord> sessions) {
        if (sessions.isEmpty()) {
            return List.of();
        }
        try {
            String transcript = digest(sessions);
            if (transcript.isBlank()) {
                return List.of();
            }
            List<Existing> existing = existingGuidelines();
            String system = "You extract DURABLE engineering guidelines from an agent session log. "
                + "Only capture lessons that will help FUTURE tasks in this repository: conventions "
                + "discovered, constraints the user stated, root causes of failures and how they were "
                + "fixed. Ignore one-off details. Each guideline is a single imperative sentence. "
                + "Respond ONLY with JSON: {\"guidelines\":[{\"slug\":\"kebab-case\",\"scope\":"
                + "\"PROJECT|GLOBAL|TASK_FAMILY\",\"body\":\"...\",\"duplicateOf\":null}]}. Return an "
                + "empty list if there is nothing durable to learn."
                + existingBlock(existing);
            cloudGate.charge(CloudGate.estimateTokens(system) + CloudGate.estimateTokens(transcript));
            String response = utility.as("knowledge").chatCompletionStream(List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", transcript)),
                LlmGuidelines.class, 0.2).collect(Collectors.joining());
            cloudGate.charge(CloudGate.estimateTokens(response));

            LlmGuidelines parsed = LlmJson.parse(mapper, response, LlmGuidelines.class);
            if (parsed.guidelines == null || parsed.guidelines.isEmpty()) {
                return List.of();
            }

            List<Existing> pool = new ArrayList<>(existing);
            List<String> written = new ArrayList<>();
            for (LlmGuideline candidate : parsed.guidelines) {
                if (written.size() >= MAX_NEW_PER_RUN) {
                    break;
                }
                if (candidate.body == null || candidate.body.isBlank() || candidate.slug == null) {
                    continue;
                }
                // The model was shown every rule the project already has and asked to name the one
                // that covers this lesson. When it names one, that is the answer: it read both
                // texts and judged the meaning, which no word count can do.
                if (candidate.duplicateOf != null && !candidate.duplicateOf.isBlank()) {
                    log.info("Guideline candidate '{}' dropped: the model says '{}' already covers "
                        + "this lesson", candidate.slug, candidate.duplicateOf.strip());
                    continue;
                }
                String covered = repeatOf(candidate.body, pool);
                if (covered != null) {
                    log.info("Guideline candidate '{}' dropped: it repeats the existing rule '{}' "
                        + "almost word for word", candidate.slug, covered);
                    continue;
                }
                String slug = candidate.slug.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-")
                    .replaceAll("-+", "-").replaceAll("^-|-$", "");
                if (slug.isBlank()) {
                    continue;
                }
                if (rules.propose(slug, candidate.body, autoPromote) != null) {
                    written.add(slug);
                    pool.add(new Existing(slug, candidate.body));
                }
            }
            if (!written.isEmpty()) {
                log.info("Guideline extraction recorded {} {} rule(s): {}",
                    written.size(), autoPromote ? "ACTIVE (autoPromote)" : "PROPOSED", written);
            }
            return written;
        } catch (Exception e) {
            log.warn("Guideline extraction failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** A compact multi-session digest, prioritizing failures (where the lessons are). */
    private static String digest(List<AgentSessionRecord> sessions) {
        StringBuilder sb = new StringBuilder();
        sessions.stream()
            .sorted((a, b) -> Boolean.compare(isFailure(b), isFailure(a))) // failures first
            .forEach(session -> {
                if (sb.length() > MAX_TRANSCRIPT_CHARS) {
                    return;
                }
                sb.append("=== ").append(session.role()).append(" (").append(session.outcome());
                if (session.killReason() != null) {
                    sb.append('/').append(session.killReason());
                }
                sb.append(") ===\n");
                if (session.events() != null) {
                    for (TraceEvent event : session.events()) {
                        if (sb.length() > MAX_TRANSCRIPT_CHARS) {
                            break;
                        }
                        if (event.payload() != null && !event.payload().isBlank()) {
                            sb.append(event.kind()).append(' ')
                                .append(event.label() == null ? "" : event.label()).append(": ")
                                .append(truncate(event.payload(), 500)).append('\n');
                        }
                    }
                }
            });
        return sb.toString();
    }

    private static boolean isFailure(AgentSessionRecord s) {
        return !"COMPLETED".equals(s.outcome());
    }

    /** Every rule this project still has — a retired one is not a rule the project has. */
    private List<Existing> existingGuidelines() {
        List<Existing> existing = new ArrayList<>();
        for (LearnedGuideline rule : rules.all()) {
            if (rule.status() != GuidelineStatus.RETIRED && rule.markdownBody() != null
                    && rule.slug() != null) {
                existing.add(new Existing(rule.slug(), rule.markdownBody()));
            }
        }
        return existing;
    }

    /**
     * The block of already-known rules appended to the system prompt. This is the real dedup: the
     * model reads what the project already says and decides whether the lesson is new, which is a
     * judgement about meaning that no measure of shared words can make.
     */
    private static String existingBlock(List<Existing> existing) {
        if (existing.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nThis repository ALREADY has the guidelines "
            + "below. Do NOT propose a rule that states a lesson one of them already states, "
            + "however differently it is worded. If the lesson you found is already covered, leave "
            + "it out of \"guidelines\" entirely — or, if you want to record that you saw it, "
            + "include it with \"duplicateOf\" set to the slug that covers it, and it will be "
            + "discarded. Only propose a rule whose lesson appears NOWHERE in this list."
            + "\n\nExisting guidelines:\n");
        int shown = 0;
        for (Existing rule : existing) {
            if (shown++ >= MAX_EXISTING_SHOWN) {
                sb.append("- (").append(existing.size() - MAX_EXISTING_SHOWN)
                    .append(" more not listed)\n");
                break;
            }
            sb.append("- ").append(rule.slug()).append(": ")
                .append(truncate(rule.body().replace('\n', ' ').strip(), GIST_CHARS)).append('\n');
        }
        return sb.toString();
    }

    /**
     * The mechanical BACKSTOP, for a model that ignores the instruction above: the name of an
     * existing rule this candidate repeats almost word for word, or null. Overlap coefficient over
     * content words — see {@link #REPEAT_OVERLAP}. It catches near-copies only, by design; the
     * reworded duplicate is the model's job.
     */
    private static String repeatOf(String candidate, List<Existing> existing) {
        Set<String> a = contentTokens(candidate);
        if (a.size() < MIN_CONTENT_TOKENS) {
            return null;
        }
        for (Existing other : existing) {
            Set<String> b = contentTokens(other.body());
            if (b.size() < MIN_CONTENT_TOKENS) {
                continue;
            }
            Set<String> intersection = new HashSet<>(a);
            intersection.retainAll(b);
            if ((double) intersection.size() / Math.min(a.size(), b.size()) >= REPEAT_OVERLAP) {
                return other.slug();
            }
        }
        return null;
    }

    /** Lowercased words of three letters or more, stop words removed. */
    static Set<String> contentTokens(String text) {
        Set<String> set = new HashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 2 && !STOP_WORDS.contains(token)) {
                set.add(token);
            }
        }
        return set;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
