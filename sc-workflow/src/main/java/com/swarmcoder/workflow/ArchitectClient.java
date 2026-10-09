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
package com.swarmcoder.workflow;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.swarmcoder.domain.*;
import com.swarmcoder.inference.EndpointOutage;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.verify.BlobSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Architect role (spec §14): writes the DesignDocument, revises it against reviewer
 * objections, and decomposes it into a TaskGraph WITH write sets and acceptance criteria —
 * the decomposition rule is "smallest unit that still has a mechanically verifiable
 * acceptance test and a clean write set, and no further" (correction S4).
 *
 * <p>Interactive interviewing (bounded ≤10 questions, spec §14) arrives with the Console's
 * intake conversation; today the architect works single-pass from the stated goal.
 */
public class ArchitectClient {

    private static final Logger log = LoggerFactory.getLogger(ArchitectClient.class);

    /** Fallback when no swarm config exists: single worker, no split — never swarm silently. */
    static final SwarmPolicy NO_SWARM = new SwarmPolicy(1, false, 0.2, 0.2, List.of("minimal-diff"));

    private final VllmClient client;
    private final CloudGate cloudGate;
    private final SwarmPolicy taskPolicy;
    private final ArchitectResearch research;
    private final BlobSink blobs;
    /** The architect model's own working room — what its reference material is sized by. */
    private final MaterialBudget room;
    /**
     * The model the task planner runs on; null is the architect's own, as it always was
     * (section 73: the planner only splits and orders, so the owner may give it a modest model
     * while the architect keeps a strong one). See {@link #setPlannerClient}.
     */
    private volatile VllmClient plannerClient;
    /**
     * What the architect kept for the workers in the last design session on this thread
     * ({@code keep_for_workers}), until the call that ran the session attaches it to its design.
     */
    private final ThreadLocal<List<DesignFinding>> pendingFindings = new ThreadLocal<>();
    /** Lenient on a trailing comma, which a model with no structured-output support writes. */
    private final ObjectMapper mapper = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();

    /**
     * How {@link #planAttempt} talks to {@link #plan(DesignDocument, String, StoryScope, String)}
     * without changing that method's signature or return type — both fixed by callers this class
     * does not control, chief among them a harness that overrides {@code plan} to stamp a tool-turn
     * budget onto every planned task, calling it "the only method the workflow calls" (spec: a
     * caller MUST be able to keep overriding exactly this method and still see the retry loop's
     * real behaviour). Thread-scoped, not a plain field: one instance of this class is shared across
     * concurrently-running workflows, but a single PLAN attempt runs synchronously on one thread.
     */
    private final ThreadLocal<String> pendingRetryFeedback = new ThreadLocal<>();
    /** The raw reply text of the last reply THIS THREAD got to parse, whether {@code plan} then
     * accepted it or not — set by {@link #callForJson} the moment {@link LlmJson#parse} succeeds. */
    private final ThreadLocal<String> lastParsedReply = new ThreadLocal<>();
    /** Why the last {@code plan} call on this thread returned null, when it did. */
    private final ThreadLocal<String> lastFailureReason = new ThreadLocal<>();

    /**
     * How much of the standing framework primer rides in the design prompt — at the baseline room.
     *
     * <p><b>Sized by the architect's OWN model since 2026-09-25</b> ({@link #room}; see
     * {@code MaterialBudget}), never the workers': the architect reads this, and in production the
     * architect is a cloud role on its own endpoint. On the generic 32,768-token shape it is
     * configured with today that is exactly 9,000; an architect whose role states a bigger working
     * context gets proportionally more, up to 5.12 times at 262,144 and above.
     */
    private static final int REFERENCE_CHARS = 9_000;
    /**
     * Research turns allowed before the design is emitted; each is one model round trip.
     *
     * <p>NOT scaled with the room. It bounds latency at DESIGN, not context — every round is a
     * model round trip — and the room each round costs already scales through
     * {@link #RESEARCH_RESULT_CHARS}. Scaling both would grow the research conversation by the
     * square of the factor: 26 times at the ceiling, past the room it was meant to fit.
     */
    private static final int RESEARCH_ROUNDS = 6;
    /** One research tool result, at the baseline room; scaled like {@link #REFERENCE_CHARS}. */
    private static final int RESEARCH_RESULT_CHARS = 6_000;

    public ArchitectClient(VllmClient client, CloudGate cloudGate) {
        this(client, cloudGate, null);
    }

    /**
     * @param taskPolicy the swarm policy stamped on every planned task — built from the
     *                   {@code swarm:} config block (nPerTask, splitAcrossFamilies,
     *                   tempMin/tempMax). Null falls back to {@link #NO_SWARM}.
     */
    public ArchitectClient(VllmClient client, CloudGate cloudGate, SwarmPolicy taskPolicy) {
        this(client, cloudGate, taskPolicy, ArchitectResearch.NONE);
    }

    /**
     * @param research read-only reference material — the project's context folders. Without it the
     *                 Architect designs against a framework it has never seen.
     */
    public ArchitectClient(VllmClient client, CloudGate cloudGate, SwarmPolicy taskPolicy,
                           ArchitectResearch research) {
        this(client, cloudGate, taskPolicy, research, BlobSink.NONE);
    }

    /**
     * @param blobs where a reply kept after a second parse failure is stored — see
     *              {@link LlmReplyBlobs#describeFailure}.
     */
    public ArchitectClient(VllmClient client, CloudGate cloudGate, SwarmPolicy taskPolicy,
                           ArchitectResearch research, BlobSink blobs) {
        this.client = client;
        this.cloudGate = cloudGate;
        this.taskPolicy = taskPolicy == null ? NO_SWARM : taskPolicy;
        this.research = research == null ? ArchitectResearch.NONE : research;
        this.blobs = blobs == null ? BlobSink.NONE : blobs;
        this.room = MaterialBudget.of(client);
    }

    /**
     * The room this architect sizes its reference material by: its own client's working context.
     * Public so the wiring that answers its research tools can answer at the same size.
     */
    public MaterialBudget room() {
        return room;
    }

    /**
     * Puts the task planner on its own model: every plan call - the lookup session and the one
     * reply it falls back to - goes to {@code planner}. Null (the default) leaves the planner on
     * the architect's model, so nothing changes for a configuration that names no planner.
     */
    public void setPlannerClient(VllmClient planner) {
        this.plannerClient = planner;
    }

    /** The client a call of this role goes to: the planner's own for a plan when one is set. */
    private VllmClient clientOf(String role) {
        VllmClient planner = plannerClient;
        return "planner".equals(role) && planner != null ? planner : client;
    }

    /**
     * What the last design session on this thread kept for the workers; taken once. Null when
     * it kept nothing, which is what a design stored before findings existed holds too.
     */
    private List<DesignFinding> takeFindings() {
        List<DesignFinding> kept = pendingFindings.get();
        pendingFindings.remove();
        return kept == null || kept.isEmpty() ? null : new ArrayList<>(kept);
    }

    /** {@code earlier} and then every finding of {@code later} it does not already hold. */
    static List<DesignFinding> mergedFindings(List<DesignFinding> earlier,
                                              List<DesignFinding> later) {
        List<DesignFinding> all = new ArrayList<>(earlier == null ? List.of() : earlier);
        for (DesignFinding finding : later == null ? List.<DesignFinding>of() : later) {
            boolean held = all.stream().anyMatch(f -> java.util.Objects.equals(f.about(),
                finding.about()) && java.util.Objects.equals(f.source(), finding.source())
                && java.util.Objects.equals(f.snippet(), finding.snippet()));
            if (!held) {
                all.add(finding);
            }
        }
        return all;
    }

    /** The policy stamped on planned tasks — also used for the workflow's fallback task. */
    public SwarmPolicy taskPolicy() {
        return taskPolicy;
    }

    // ---- wire schemas ----
    public static class LlmDesign {
        public List<LlmRequirement> requirements;
        public List<LlmDecision> decisions;
        /** Aliases read the reply the model actually gave (as {@link LlmRequirement}'s do); the
         * schema in the prompt is unchanged. */
        @JsonAlias({"types", "interfaces", "apiContracts", "api_contracts", "typeContracts",
            "typeVocabulary"})
        public List<LlmContract> contracts;
        public List<LlmRisk> risks;
    }
    /**
     * A requirement on the wire. The prompt asks for {@code text}, but a model given a run's worth
     * of design work sometimes names the field for what it is instead (harness run 49, 2026-09-30:
     * design review objected "REQ entries are all null", and earlier runs rendered the design's
     * requirements as "[MEDIUM] null" — the text was there, under another name, and unknown
     * fields are ignored). The aliases read those replies; the schema in the prompt is unchanged.
     */
    public static class LlmRequirement {
        @JsonAlias({"requirement", "description", "statement", "name", "title", "summary"})
        public String text;
        @JsonAlias({"importance"})
        public String priority;

        /**
         * A requirement sent as one plain string, the way the design summary the revision prompt
         * shows it is written: {@code "R1 [HIGH] The operator can ..."} (harness runs 60 and 61,
         * 2026-10-01). Without this the whole reply failed to bind, and {@link LlmJson} then
         * retried from the next brace and bound an inner contract object as the design, so every
         * section looked absent. The handle and priority are peeled off; the rest is the text.
         */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static LlmRequirement fromString(String line) {
            LlmRequirement r = new LlmRequirement();
            Matcher m = REQUIREMENT_LINE.matcher(line == null ? "" : line.strip());
            if (m.matches()) {
                r.priority = m.group(1);
                r.text = m.group(2).strip();
            } else {
                r.text = line;
            }
            return r;
        }
    }
    /** {@code [REQ ][R1 ][[HIGH]] text} — the handle and the bracketed priority both optional. */
    private static final Pattern REQUIREMENT_LINE = Pattern.compile(
        "(?is)^(?:REQ\\s+)?(?:R\\d+\\s*[:.)-]?\\s*)?\\[\\s*([A-Za-z]+)\\s*]\\s*(.*)$");
    private static final Pattern RISK_LINE = Pattern.compile(
        "(?is)^(?:RISK\\s+)?\\[\\s*([A-Za-z]+)\\s*]\\s*(.*)$");
    public static class LlmDecision {
        public String decision;
        public String rationale;

        /** A decision sent as one string: {@code "decision — rationale"}, the rationale optional. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static LlmDecision fromString(String line) {
            LlmDecision d = new LlmDecision();
            String text = line == null ? "" : line.strip();
            if (text.regionMatches(true, 0, "DECISION ", 0, 9)) {
                text = text.substring(9).strip();
            }
            int dash = text.indexOf(" — ");
            d.decision = dash < 0 ? text : text.substring(0, dash).strip();
            d.rationale = dash < 0 ? null : text.substring(dash + 3).strip();
            return d;
        }
    }
    /**
     * A contract on the wire. {@code type} and {@code members} are the run's TYPE VOCABULARY
     * (author decision, 2026-09-03): every type an acceptance test will touch is named here, with
     * its exact package and the members the test needs, so the test author, the worker that
     * delivers it and the verification that checks it are all reading the same words. Absent in a
     * reply from a model that ignored them, and then nothing keyed off them does anything.
     */
    public static class LlmContract {
        public String name;
        public String description;
        public String signature;
        /** The fully-qualified type name, e.g. {@code com.acme.shop.Rating}. */
        public String type;
        /** The members a test needs, as written in Java: {@code "int rating"}, {@code "String title()"}. */
        public List<String> members;
        /**
         * Every key the schema does not name, kept so {@link #resolveTypeName} can find the type
         * under another key ({@code typeName}, {@code fqn}, {@code className}, ...). Private and
         * without a getter, so neither the generated schema nor any serialisation sees it.
         */
        @JsonIgnore
        private final Map<String, Object> extras = new HashMap<>();
        @JsonAnySetter
        void other(String key, Object value) {
            extras.put(key, value);
        }
    }
    public static class LlmRisk {
        public String description;
        public String severity;
        public String mitigation;

        /** A risk sent as one string: {@code "[LOW] description"}, the severity optional. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static LlmRisk fromString(String line) {
            LlmRisk r = new LlmRisk();
            Matcher m = RISK_LINE.matcher(line == null ? "" : line.strip());
            if (m.matches()) {
                r.severity = m.group(1);
                r.description = m.group(2).strip();
            } else {
                r.description = line;
            }
            return r;
        }
    }

    /**
     * The design schema for a story-scoped run. Note what is ABSENT: requirements. Those come from
     * the BRD, which is the only source of truth for what must be built, so the architect is not
     * given a field in which to invent them. What it genuinely cannot express any other way — a
     * capability the story needs but the BRD does not describe — goes to {@code missingRequirements}
     * as a proposal for the operator to triage.
     */
    public static class LlmScopedDesign {
        public List<LlmDecision> decisions;
        @JsonAlias({"types", "interfaces", "apiContracts", "api_contracts", "typeContracts",
            "typeVocabulary"})
        public List<LlmContract> contracts;
        public List<LlmRisk> risks;
        public List<LlmMissing> missingRequirements;
    }
    public static class LlmMissing { public String title; public String statement; public String why; }

    /** A design plus anything the architect thinks the BRD is missing. */
    public record ScopedDesign(DesignDocument design, List<LlmMissing> missing) {}

    public static class LlmPlan {
        public List<LlmTask> tasks;
        public List<LlmEdge> edges;
    }
    public static class LlmTask {
        public String id;
        public String title;
        public String instructions;
        public List<String> writeSet;
        public List<String> readSet;
        public List<LlmCriterion> criteria;
        /** Requirement handles (R1, R2, …) from the design summary that this task satisfies. */
        public List<String> requirementRefs;
        /**
         * Criterion refs (R7:C1) this task satisfies, drawn from the story's slice. This is the
         * link that makes delivery verifiable: the criterion names the test, so a passing task
         * is evidence for a specific requirement rather than a claim.
         */
        public List<String> criterionRefs;
        /**
         * The design contracts this task creates, by contract NAME as the design summary gives
         * them. This is what turns "somebody will build the shared types" into a named promise a
         * candidate is held to — see {@link com.swarmcoder.domain.Task#deliveredContracts()}.
         */
        public List<String> deliversContracts;
    }
    public static class LlmCriterion { public String text; public String testClassOrFile; }
    public static class LlmEdge { public String from; public String to; }

    /**
     * The one line that makes the design a VOCABULARY rather than a description (author decision,
     * 2026-09-03). It is deliberately short and deliberately absolute: on run 13 the design named
     * six contracts in prose, the test author read them and invented a seventh type, no task
     * delivered it, and the run spent three waves reaching a test that could never compile. What
     * fixes that is not more prose — it is the architect being made to write down, for every type
     * a test will touch, the package it lives in and the members the test needs.
     *
     * <p><b>Strengthened 2026-09-27 (harness run 46).</b> Every contract in that run's design came
     * back with {@code "type":"class"} — the model answered the field with the KIND of the type
     * ("class") rather than its fully-qualified NAME, so all four contracts in the design shared
     * one typeName and every check keyed on it (plan delivery, contract matching) treated them as
     * the same type; {@code TaskGraphValidator} reported each contract as "claimed by 4 tasks".
     * The fully-qualified example was already inline; what was missing was telling the model what
     * NOT to answer with, so the rule now says so directly. {@link #toContracts} also recovers a
     * real name when a reply still makes this mistake, but the prompt line is the cheaper fix.
     */
    static final String CONTRACT_VOCABULARY_RULE =
        "EVERY TYPE AN ACCEPTANCE TEST WILL TOUCH MUST BE A CONTRACT HERE, with its exact package "
        + "and members: give \"type\" as the fully-qualified NAME of the type (e.g. "
        + "com.acme.shop.Rating) — NOT its kind; never answer \"type\" with just \"class\", "
        + "\"interface\", \"record\" or \"enum\" on its own — "
        + "and give \"members\" as the fields and methods a test needs, one per entry, written as "
        + "Java (e.g. \"int rating\", \"String title()\"; a member that must carry an annotation is "
        + "written \"@Annotation Type name;\", never as a sentence). Nothing else will be built, so a type "
        + "that is not here is a type nobody delivers. "
        + "The acceptance test classes named by the checks are written by the test author and are "
        + "NOT contracts; do not list them.";

    /**
     * The sentence that keeps the worked-example channel alive on a change to code that already
     * exists (design §2.3, §6).
     *
     * <p>Left to itself the architect states contracts for the types a plan will CREATE. On a
     * greenfield build that is every type there is. On a change to an existing repository it is
     * usually none of them: the change modifies types that were written years ago, so the design
     * comes back with an empty contract list, {@code Librarian.workedExample} returns "" for every
     * task (it short-circuits on {@code task.deliveredContracts()} being empty), and the one
     * channel that was measured to matter collapses back into a documentation guess.
     *
     * <p>The measurement that makes this worth a prompt line, from
     * {@code dev/experiment/unseen-code/results.md}: given an unfamiliar framework, prose
     * documentation and a search tool, the model read 43 documents, disassembled the jars for 106
     * shell calls, and over three runs and 467 turns wrote <b>zero files</b>. With a worked example
     * in the brief and the product's own read-pause rule: 34 turns, 13 minutes, green.
     *
     * <p>So on a brownfield change every type the work TOUCHES is a contract, existing or new, and
     * the description says which — because "this type already exists, here is where" and "this type
     * has to be created" are different jobs for the planner and different answers for the
     * red-check's {@code TypeDeliverability}.
     */
    static final String EXISTING_CODE_CONTRACT_RULE =
        "THIS CHANGE IS AGAINST A CODEBASE THAT ALREADY EXISTS. State a contract for EVERY type the "
        + "change touches — the ones that already exist as much as the ones you are creating — and "
        + "say which in the description: begin an existing type's description with \"EXISTING:\" "
        + "and a new one's with \"NEW:\". A type you are only modifying is as much a contract as a "
        + "type you are inventing, and a type left out here is a type no worker is ever shown the "
        + "code of. Use the fully-qualified names exactly as the repository declares them; the "
        + "brief above lists the ones this change is about with the file and line each is declared "
        + "on, so prefer those over any name you would otherwise guess at.";

    /** Single-pass design from the goal; null when the architect endpoint fails. */
    public DesignDocument design(String goal) {
        return design(goal, "");
    }

    /**
     * @param constraintBrief the project's standing technical rules, rendered by
     *                        {@link com.swarmcoder.domain.ConstraintBrief}. Blank when the project
     *                        states none, and the prompt is then byte-for-byte what it always was.
     *                        When it is present it is the difference between designing for THIS
     *                        stack and designing for whatever stack a project of this shape usually
     *                        has — which, told nothing, is what a model reaches for.
     */
    public DesignDocument design(String goal, String constraintBrief) {
        return design(goal, constraintBrief, false);
    }

    /**
     * @param againstExistingCode true when this run changes a repository that already exists, which
     *                            adds {@link #EXISTING_CODE_CONTRACT_RULE} and nothing else. False
     *                            leaves the prompt byte-for-byte what it was, so a greenfield build
     *                            is unchanged and its prefill cache does not go cold.
     */
    public DesignDocument design(String goal, String constraintBrief, boolean againstExistingCode) {
        return design(goal, constraintBrief, againstExistingCode, "");
    }

    /**
     * @param buildBrief what the build itself says the design must respect — today, which modules
     *                   run only in a browser ({@link AcceptanceTestReach#architectBrief}). Appended
     *                   to the USER message, never the system prompt, so a project without such a
     *                   module sends byte-for-byte the prompt it always did. Blank means nothing to
     *                   say.
     */
    public DesignDocument design(String goal, String constraintBrief, boolean againstExistingCode,
                                 String buildBrief) {
        try {
            LlmDesign parsed = callForJson(
                "You are a software architect. Produce a concise design for the goal as JSON: "
                + "{\"requirements\":[{\"text\",\"priority\":LOW|MEDIUM|HIGH|CRITICAL}],"
                + "\"decisions\":[{\"decision\",\"rationale\"}],"
                + "\"contracts\":[{\"name\",\"description\",\"signature\","
                + "\"type\":\"fully.qualified.Name\",\"members\":[]}],"
                + "\"risks\":[{\"description\",\"severity\":LOW|MEDIUM|HIGH|CRITICAL,\"mitigation\"}]}. "
                + "Contracts are the API boundaries that make parallel implementation safe. "
                + CONTRACT_VOCABULARY_RULE + existingCodeRule(againstExistingCode),
                constraintPreamble(constraintBrief) + "Goal: " + goal + nullSafe(buildBrief),
                LlmDesign.class, LlmDesign.class, "the architect's",
                designWork(json -> {
                    DesignDocument draft = toDesign(UUID.randomUUID(), goal,
                        LlmJson.parse(mapper, json, LlmDesign.class), 1);
                    dropAcceptanceTestClassContracts(draft, List.of());
                    return draft;
                }, draft -> null, List.of()));
            DesignDocument document = toDesign(UUID.randomUUID(), goal, parsed, 1);
            document.setFindings(takeFindings());
            keepPendingAs("design:" + document.id());
            dropAcceptanceTestClassContracts(document, List.of());
            warnOfContractsWithoutAType(document, "design");
            return document;
        } catch (EndpointOutage outage) {
            // "The architect is unreachable" is not "the architect had nothing to say". Degrading to
            // a minimal design here is how an outage used to produce a run that designed nothing,
            // planned nothing and then swarmed on the goal string.
            throw outage;
        } catch (Exception e) {
            logFailure("Architect design failed", e);
            return null;
        }
    }

    /**
     * Design for a story-scoped run. The requirements are NOT produced by the model: they are the
     * BRD requirements the story delivers, carried through with their own ids so every task, and
     * ultimately every commit, traces back to the requirement graph. The model contributes only
     * what it is actually for — decisions, contracts, risks — plus proposals for anything it thinks
     * is missing, which go to triage rather than into the design.
     *
     * <p>Falls back to the unscoped {@link #design(String)} when the scope is empty (an ad-hoc run
     * that answers to no requirement yet).
     */
    public ScopedDesign design(String goal, StoryScope scope) {
        return design(goal, scope, false);
    }

    /**
     * @param againstExistingCode true when this run changes a repository that already exists — a
     *                            BUGFIX or ENHANCEMENT run on a registered target. It adds
     *                            {@link #EXISTING_CODE_CONTRACT_RULE} to the prompt and changes
     *                            nothing else; false is the greenfield prompt, unchanged.
     */
    public ScopedDesign design(String goal, StoryScope scope, boolean againstExistingCode) {
        return design(goal, scope, againstExistingCode, "");
    }

    /**
     * @param buildBrief what the build says the design must respect — which modules run only in a
     *                   browser, so no checked behaviour is designed to be reachable only through
     *                   one (harness run 37, 2026-09-25: the design made the TeaVM client's
     *                   {@code BookStore} the type the persistence check was proved through, and no
     *                   JUnit test can run it). Appended to the briefing, the user message; blank
     *                   changes nothing.
     */
    public ScopedDesign design(String goal, StoryScope scope, boolean againstExistingCode,
                               String buildBrief) {
        if (scope == null || scope.isEmpty()) {
            // No criteria to design against, but the project's standing rules still apply: an
            // ad-hoc run writes real code into the real repository and must not invent a stack.
            return new ScopedDesign(
                design(goal, scope == null ? "" : scope.constraintBrief(), againstExistingCode,
                    buildBrief),
                List.of());
        }
        List<Requirement> requirements = new ArrayList<>();
        List<UUID> brdRequirementIds = new ArrayList<>();
        for (BrdRequirement r : scope.requirements()) {
            // Same id as the BRD requirement — the design REFERENCES the requirement graph, it does
            // not copy it into a parallel identity that would immediately start drifting.
            requirements.add(new Requirement(r.id(), r.text(), r.priority()));
            brdRequirementIds.add(r.id());
        }
        DesignDocument design;
        List<LlmMissing> missing = List.of();
        String briefing = scopeBriefing(goal, scope) + nullSafe(buildBrief);
        // Learn the frameworks first: the design that follows is schema-constrained and cannot
        // ask questions once it has started. Not when the architect works as an agent
        // (2026-10-02): its session looks things up with better tools, as it designs.
        String notes = lookupAgent == null ? researchNotes(goal, briefing) : "";
        if (!notes.isEmpty()) {
            briefing = briefing + "\n\nWHAT YOU ESTABLISHED ABOUT THE CODE AND ITS FRAMEWORKS:\n" + notes;
        }
        try {
            LlmScopedDesign parsed = callForJson(
                "You are a software architect. The requirements are FIXED and given to you below — "
                + "they come from the project's Business Requirements Document, which is the only "
                + "source of truth for what must be built. Do NOT restate, reinterpret, extend or "
                + "invent requirements. Design HOW to satisfy exactly the acceptance criteria "
                + "listed, honouring every quality constraint. Output JSON: "
                + "{\"decisions\":[{\"decision\",\"rationale\"}],"
                + "\"contracts\":[{\"name\",\"description\",\"signature\","
                + "\"type\":\"fully.qualified.Name\",\"members\":[]}],"
                + "\"risks\":[{\"description\",\"severity\":LOW|MEDIUM|HIGH|CRITICAL,\"mitigation\"}],"
                + "\"missingRequirements\":[{\"title\",\"statement\",\"why\"}]}. "
                + "Contracts are the API boundaries that make parallel implementation safe. "
                + CONTRACT_VOCABULARY_RULE + existingCodeRule(againstExistingCode) + " "
                + "Use missingRequirements ONLY for a capability the criteria genuinely depend on "
                + "but the document does not describe; it is a proposal for the operator to review, "
                + "never something you may assume. Leave it empty if nothing is missing.",
                briefing, LlmScopedDesign.class, LlmScopedDesign.class, "the architect's",
                designWork(json -> {
                    LlmScopedDesign draft = LlmJson.parse(mapper, json, LlmScopedDesign.class);
                    DesignDocument document = new DesignDocument(UUID.randomUUID(), 1, goal,
                        requirements, toDecisions(draft.decisions), toContracts(draft.contracts),
                        toRisks(draft.risks), null, Instant.now());
                    dropAcceptanceTestClassContracts(document, checkTestRefs(scope));
                    return document;
                }, draft -> null, List.of()));
            design = new DesignDocument(UUID.randomUUID(), 1, goal, requirements,
                toDecisions(parsed.decisions), toContracts(parsed.contracts), toRisks(parsed.risks),
                null, Instant.now());
            dropAcceptanceTestClassContracts(design, checkTestRefs(scope));
            design.setFindings(takeFindings());
            keepPendingAs("design:" + design.id());
            warnOfContractsWithoutAType(design, "story design");
            missing = parsed.missingRequirements == null ? List.of() : parsed.missingRequirements;
        } catch (EndpointOutage outage) {
            throw outage; // wait for the endpoint; do not ship a design with no technical content
        } catch (Exception e) {
            // A design still exists even if the model is unreachable: the requirements are known,
            // only the technical reasoning is missing. Losing the run here would be worse.
            logFailure("Architect scoped design failed; proceeding with requirements only", e);
            design = new DesignDocument(UUID.randomUUID(), 1, goal, requirements,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null, Instant.now());
        }
        design.setBrdRequirementIds(brdRequirementIds);
        return new ScopedDesign(design, missing);
    }

    /** {@link #EXISTING_CODE_CONTRACT_RULE} with its leading space, or nothing at all. */
    private static String existingCodeRule(boolean againstExistingCode) {
        return againstExistingCode ? " " + EXISTING_CODE_CONTRACT_RULE : "";
    }

    /**
     * Removes any contract that names the acceptance test class itself rather than a type a task
     * builds, one log line per contract dropped (harness run 19, 2026-09-03 — see
     * {@link AcceptanceTestContracts}). Called on every design as it comes back from the model, so
     * neither the PLAN prompt nor {@link TaskGraphValidator} ever sees one: a design that still
     * listed it would send the planner in a circle it cannot get out of, since no task may ever be
     * assigned to write the file the design just asked one to deliver.
     */
    private static void dropAcceptanceTestClassContracts(DesignDocument design, List<String> checkTestRefs) {
        if (design == null) {
            return;
        }
        List<ApiContract> kept = AcceptanceTestContracts.withoutAcceptanceTestClasses(
            design.contracts(), checkTestRefs,
            dropped -> log.info("dropped contract " + dropped.typeName() + " from the design: it is "
                + "the acceptance test the test author writes, not a type a task delivers"));
        design.setContracts(kept);
    }

    /** The test references the story's own checks name, for matching a contract against them. */
    private static List<String> checkTestRefs(StoryScope scope) {
        if (scope == null || scope.criteria() == null) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (AcceptanceCriterion criterion : scope.criteria()) {
            if (criterion != null) {
                refs.add(criterion.testClassOrFile());
            }
        }
        return refs;
    }

    /**
     * A read-only research phase run BEFORE the design is emitted, returning notes to fold into it.
     *
     * <p>It has to be a separate phase. The design call is schema-constrained (guided JSON), so the
     * model physically cannot emit a tool line inside it — asking it to do both would either break
     * the schema or silently drop the research. So: an unconstrained conversation that gathers
     * facts, then the constrained call that consumes them.
     *
     * <p>Bounded and best-effort. Every round is a model round trip, so an unfamiliar framework
     * costs real latency at DESIGN; on any failure this returns what it has and the design proceeds
     * with the standing primer alone, which is still better than nothing.
     */
    private String researchNotes(String goal, String briefing) {
        String reference = research.reference(room.chars(REFERENCE_CHARS));
        if (reference == null || reference.isBlank()) {
            return "";
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
            "You are a software architect about to design against a codebase and its reference "
            + "material. FIRST research what you do not know. You may issue up to " + RESEARCH_ROUNDS
            + " tool calls, one at a time.\n"
            + "TOOLS — reply with EXACTLY one line and nothing else:\n"
            + "  TOOL lookup_api <what you need to know>\n"
            + "  TOOL search_code <keywords>\n"
            + "  TOOL read_file <root>/<path>\n"
            + "  TOOL list_folder <root>/<path>\n"
            + "Use them to learn the ACTUAL API you must build against: the real type and method "
            + "names, how a caller is expected to wire things together, and the idioms the "
            + "reference code follows. Do not guess at an API you have not seen — that is the "
            + "entire reason you have these tools.\n"
            + "When you have enough, reply with NOTES: followed by a compact summary of the facts "
            + "that matter for the design — concrete signatures and idioms, not prose. Everything "
            + "here is READ-ONLY reference; you are not designing yet."));
        messages.add(Map.of("role", "user", "content",
            briefing + "\n\nREFERENCE MATERIAL (read-only):\n" + reference));

        StringBuilder notes = new StringBuilder();
        try {
            for (int round = 0; round < RESEARCH_ROUNDS; round++) {
                String reply = call(messages, 0.2).strip();
                String toolLine = firstToolLine(reply);
                if (toolLine == null) {
                    int marker = reply.indexOf("NOTES:");
                    notes.append(marker >= 0 ? reply.substring(marker + "NOTES:".length()).strip() : reply);
                    break;
                }
                String result = executeResearchTool(toolLine);
                messages.add(Map.of("role", "assistant", "content", reply));
                messages.add(Map.of("role", "user", "content",
                    RESULT_MARKER + toolLine + "`:\n" + result
                    + "\n\nContinue: another TOOL line, or NOTES: with what the design needs."));
            }
        } catch (EndpointOutage outage) {
            throw outage; // the design call is next and would fail the same way — pause instead
        } catch (Exception e) {
            log.warn("Architect research failed ({}); designing from the primer alone", e.getMessage());
        }
        return notes.toString().strip();
    }

    /**
     * What a planner working with lookup tools is told about the framework reference and the
     * worked examples: that it has neither and needs neither. Harness run 79 (2026-10-04) took
     * the two blocks out of its opening (about 14,000 tokens on each of 102 calls) and told it
     * to fetch what its instructions relied on; run 98 (2026-10-08) showed what that cost - 78
     * lookups in 28 calls, 31 of 69 a repeat of the architect's. Since section 73 the planner
     * writes no how-to, so there is nothing for it to fetch.
     */
    static final String LOOK_IT_UP = "\n\nNo framework reference and no example code is "
        + "attached here, and you need neither. How the work is built is not yours to say: the "
        + "architect's findings (the FACT lines of the design) are given to the workers word for "
        + "word with the tasks they concern, and a worker looks up the rest. What you may need "
        + "to look up is where things are - the modules, the source directories, which existing "
        + "type lives in which file, what uses what (which tool is for what is listed at the "
        + "end).";

    /**
     * The prefix a research result is announced with — see the loop in {@link #researchNotes}.
     * Shared with {@link #firstToolLine} so the two cannot drift: a model reply that echoes this
     * exact text back (some models do, when they run on past where they should have stopped) is
     * recognisable as harness framing, never real tool-call content.
     */
    private static final String RESULT_MARKER = "RESULT for `";

    /**
     * Accepts a reply only when its first non-blank line is the whole tool call — and stops that
     * line at the harness's own {@link #RESULT_MARKER}, if a model ran straight into it with no
     * newline in between.
     *
     * <p>Observed live (2026-09-03): the model's reply for {@code TOOL read_file <path>} was
     * immediately followed, same line, by the very "RESULT for `TOOL ...`:" text the harness uses
     * to introduce a tool's result on the NEXT turn — the model kept generating instead of
     * stopping, and predicted its own upcoming prompt. Without this, the whole tail became part
     * of the path argument and the tool call failed on a string that was never a file address.
     */
    private static String firstToolLine(String reply) {
        for (String line : reply.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("TOOL ")) {
                return null;
            }
            int echo = trimmed.indexOf(RESULT_MARKER, "TOOL ".length());
            return echo < 0 ? trimmed : trimmed.substring(0, echo).stripTrailing();
        }
        return null;
    }

    private String executeResearchTool(String toolLine) {
        String[] parts = toolLine.split("\\s+", 3);
        String tool = parts.length > 1 ? parts[1] : "";
        String argument = parts.length > 2 ? parts[2].strip() : "";
        String result = switch (tool) {
            case "lookup_api" -> research.lookupApi(argument);
            case "search_code" -> research.searchCode(argument);
            case "read_file" -> research.readFile(argument);
            case "list_folder" -> research.listFolder(argument);
            default -> "error: unknown tool '" + tool
                + "' — use lookup_api | search_code | read_file | list_folder";
        };
        if (result == null) {
            return "(nothing found)";
        }
        int resultChars = room.chars(RESEARCH_RESULT_CHARS);
        return result.length() <= resultChars ? result
            : result.substring(0, resultChars) + "\n…(truncated)";
    }

    /**
     * What the architect is told: how the project must be built, then the story, its exact criteria,
     * and the gates it inherits.
     *
     * <p>The standing rules come FIRST, above everything. They decide what every answer below them
     * may be made of, and a rule read after the thing it constrains has been decided is a rule read
     * too late.
     */
    static String scopeBriefing(String goal, StoryScope scope) {
        StringBuilder sb = new StringBuilder(scope.constraintBrief());
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append("Story: ").append(goal).append("\n\n");
        sb.append("Requirements this story delivers (from the BRD — fixed):\n");
        for (BrdRequirement r : scope.requirements()) {
            sb.append("- ").append(r.handle()).append(' ').append(nullSafe(r.title()))
                .append(" [").append(r.priority()).append("] ").append(nullSafe(r.text())).append('\n');
        }
        sb.append("\nAcceptance criteria to satisfy (the definition of done):\n");
        List<AcceptanceCriterion> criteria = scope.criteria();
        for (int i = 0; i < criteria.size(); i++) {
            AcceptanceCriterion c = criteria.get(i);
            sb.append("- ").append(scope.criterionRefs().get(i)).append(' ').append(nullSafe(c.text()));
            if (c.testClassOrFile() != null) {
                sb.append("  [test: ").append(c.testClassOrFile()).append(']');
            }
            sb.append('\n');
        }
        if (!scope.gatingNfrs().isEmpty()) {
            sb.append("\nQuality constraints that ALSO apply (inherited — the design must honour "
                + "these even though the story does not deliver them):\n");
            for (BrdRequirement nfr : scope.gatingNfrs()) {
                sb.append("- ").append(nfr.handle()).append(" [")
                    .append(nfr.nfrCategory() == null ? "QUALITY" : nfr.nfrCategory()).append("] ")
                    .append(nullSafe(nfr.text())).append('\n');
                for (AcceptanceCriterion c : nfr.criteria()) {
                    sb.append("    fitness: ").append(nullSafe(c.text())).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * The constraint briefing as a prompt preamble, or nothing at all.
     *
     * <p>Prefixed rather than appended, for the same reason {@link #scopeBriefing} puts it first.
     * Blank in, blank out: a project that states no rules gets exactly the prompt it got before
     * constraints existed, so nothing is ever handed a heading with nothing under it.
     */
    private static String constraintPreamble(String constraintBrief) {
        return constraintBrief == null || constraintBrief.isBlank() ? "" : constraintBrief + "\n";
    }

    /**
     * The outcome of one revision attempt against reviewer objections (2026-09-04, harness run 21).
     * A revision that says LESS than the design it was meant to fix is never accepted: harness run
     * 21 revised a design carrying 1 requirement and 0 contracts into one with 0 requirements,
     * DESIGN_REVIEW accepted it outright because it still parsed, and the run reached PLAN with
     * nothing to plan — three PLAN attempts then failed against a design that said nothing, each
     * one reporting only "the architect answered unusably". A reviewer or an operator can always
     * ask the architect for MORE detail; a revision is never allowed to quietly take detail away.
     *
     * @param design        the revised design when it was accepted; the ORIGINAL, unchanged, when
     *                       the revision was discarded — a caller reads this field either way and
     *                       never needs to branch to find out what to keep going with
     * @param discarded     true when the revision was rejected and {@code design} is the original
     * @param discardReason why, when {@code discarded}; null otherwise
     */
    public record ReviseAttempt(DesignDocument design, boolean discarded, String discardReason) {
        static ReviseAttempt accepted(DesignDocument design) {
            return new ReviseAttempt(design, false, null);
        }
        static ReviseAttempt discarded(DesignDocument original, String reason) {
            return new ReviseAttempt(original, true, reason);
        }
    }

    /**
     * The revision system prompt. Kept as a named constant (rather than inlined twice) because the
     * re-ask in {@link #revise} sends it a second time verbatim — the rules a revision is held to
     * must not change between the first try and the second.
     *
     * <p><b>Strengthened 2026-09-26 (harness runs 41 and 42, 2026-09-26).</b> Two consecutive live
     * runs on DeepSeek V4 Flash — a model with no structured-output support on its endpoint
     * (ds4 refuses {@code response_format json_object}, so this JSON shape is enforced by the
     * PROMPT alone) and which reasons at length before answering — asked for a revision and got
     * back a reply that parsed cleanly but was missing the {@code requirements} field entirely:
     * {@code WARN ArchitectClient - revision discarded: it dropped 1 requirement(s) and all
     * contracts}. The instruction already said "return the FULL corrected design"; it did not say
     * WHY that matters, so a model reasoning about "what changed" naturally drifted toward
     * answering only the part the objections were about. The added sentence below says outright
     * that an unaffected section must still be copied over, not dropped for being unaffected.
     */
    static final String REVISE_SYSTEM_PROMPT =
        "You are a software architect revising a design against review objections. "
        + "Return the FULL corrected design as the same JSON schema you used before — this is the "
        + "WHOLE design again, not a diff and not just the parts the objections are about: "
        + "{\"requirements\":[...],\"decisions\":[...],"
        + "\"contracts\":[{\"name\",\"description\",\"signature\","
        + "\"type\":\"fully.qualified.Name\",\"members\":[]}],"
        + "\"risks\":[...]}. Every requirement and every contract the CURRENT design already lists "
        + "below MUST appear again in your reply — copied over exactly as given when an objection "
        + "does not concern it — UNLESS an objection specifically asks you to remove or replace it. "
        + "Do not leave one out just because none of the objections mention it. "
        + CONTRACT_VOCABULARY_RULE;

    /** The revision user message — the goal, the current design, and the objections against it. */
    private static String reviseUserMessage(DesignDocument original, List<String> objections) {
        return "Goal: " + original.goal()
            + "\n\nCurrent design:\n" + designSummary(original)
            + "\n\nObjections:\n- " + String.join("\n- ", objections);
    }

    /**
     * Revision against reviewer objections. See {@link ReviseAttempt}: a revised design that
     * dropped requirements the original had, dropped every contract the original had, or never
     * parsed at all is discarded and the original is kept, logged and reported to the caller
     * instead of silently taking its place.
     *
     * <p><b>One re-ask before giving up, added 2026-09-26 (harness runs 41 and 42) — see
     * {@link #REVISE_SYSTEM_PROMPT}'s javadoc for the failure this answers.</b> When a revision
     * comes back short, {@link #looksLikeForgottenSection} tells apart a model that never
     * mentioned a section at all (its field is {@code null} on the wire — {@link LlmJson} leaves a
     * POJO field {@code null} when the reply's JSON never had that key, and ignores keys it does
     * not recognise, so nothing before this point can otherwise tell "never mentioned" apart from
     * "explicitly said there are none") from one that stated an empty array on purpose
     * ({@code "contracts": []}) — a stated removal is treated exactly as it always was, a genuine
     * drop, refused with no re-ask. Only when EVERY dropped section was left out of the JSON
     * entirely is the architect re-asked once, told by name what came back missing, and given
     * another try before the original design is kept for good.
     */
    public ReviseAttempt revise(DesignDocument original, List<String> objections) {
        return revise(original, objections, "");
    }

    /**
     * @param existingTypes the project's own existing types as the design call was shown them
     *                      ({@link ExistingProjectTypes#architectBrief}), so a revision is written
     *                      against the same code the design was (live run 68, 2026-10-02). Blank
     *                      sends the revision message exactly as it was.
     */
    public ReviseAttempt revise(DesignDocument original, List<String> objections,
                                String existingTypes) {
        String context = nullSafe(existingTypes);
        // The architect that wrote this design goes on in its own conversation when it is still
        // there (section 54); a revision it cannot continue opens a session, as it always did.
        String designKey = "design:" + original.id();
        KeptConversations.Held prior = kept.take(designKey);
        String followUp = sentBack("design", reviseUserMessage(original, objections) + context);
        try {
            AgentWork work = designWork(json -> {
                    LlmDesign draft = LlmJson.parse(mapper, json, LlmDesign.class);
                    DesignDocument document = toDesign(original.id(), original.goal(), draft,
                        original.revision() + 1);
                    document.setBrdRequirementIds(original.brdRequirementIds());
                    carryOverUnchangedSections(draft, original, document);
                    return document;
                }, draft -> revisionLoses(original, draft), original.contracts());
            LlmDesign parsed = callForJson(REVISE_SYSTEM_PROMPT,
                reviseUserMessage(original, objections) + context,
                LlmDesign.class, LlmDesign.class, "the architect's",
                work == null ? null : work.continuing(prior, followUp));
            keepPendingAs(designKey);
            // Same id, bumped revision — a revision is the same design evolving.
            DesignDocument revised = toDesign(original.id(), original.goal(), parsed, original.revision() + 1);
            // A scoped design's BRD links are not part of the LlmDesign wire schema (see
            // ScopedDesign's own javadoc) and toDesign never sets them — carried over explicitly so
            // an accepted revision of a story-scoped design does not silently stop tracing to the
            // requirement graph it was scoped from.
            revised.setBrdRequirementIds(original.brdRequirementIds());
            // What the architect kept for the workers is not part of the wire schema either: a
            // revision keeps every finding the design had and adds what this round kept.
            List<DesignFinding> carried = mergedFindings(original.findings(), takeFindings());
            revised.setFindings(carried.isEmpty() ? null : carried);
            carryOverUnchangedSections(parsed, original, revised);
            String discardReason = discardReason(original, revised);
            if (discardReason == null) {
                warnOfContractsWithoutAType(revised, "revision");
                return ReviseAttempt.accepted(revised);
            }
            if (!looksLikeForgottenSection(parsed, original, revised)) {
                // At least one dropped section was stated on the wire, not merely absent — a
                // considered removal, not a reasoning slip. Refused exactly as before 2026-09-26.
                log.warn(discardReason);
                warnRawReply("revision discarded", lastParsedReply.get());
                return ReviseAttempt.discarded(original, discardReason);
            }
            warnRawReply("revision discarded, about to be re-asked", lastParsedReply.get());
            log.warn("{}; every dropped section was simply absent from the reply rather than "
                + "explicitly emptied, so re-asking once, naming exactly what is missing, before "
                + "giving up on this revision", discardReason);
            try {
                LlmDesign retried = callForJson(REVISE_SYSTEM_PROMPT,
                    reviseUserMessage(original, objections) + context
                        + missingSectionsReminder(original, revised),
                    LlmDesign.class, LlmDesign.class, "the architect's revision re-ask", null);
                DesignDocument revisedRetry =
                    toDesign(original.id(), original.goal(), retried, original.revision() + 1);
                revisedRetry.setBrdRequirementIds(original.brdRequirementIds());
                revisedRetry.setFindings(carried.isEmpty() ? null : carried);
                carryOverUnchangedSections(retried, original, revisedRetry);
                String retryDiscardReason = discardReason(original, revisedRetry);
                if (retryDiscardReason == null) {
                    log.info("revision recovered on the re-ask: the reply now carries every "
                        + "requirement and contract the original design had");
                    warnOfContractsWithoutAType(revisedRetry, "revision re-ask");
                    return ReviseAttempt.accepted(revisedRetry);
                }
                log.warn("{} — even after being told by name what was missing; keeping the "
                    + "original design", retryDiscardReason);
                warnRawReply("revision re-ask discarded", lastParsedReply.get());
                return ReviseAttempt.discarded(original, retryDiscardReason);
            } catch (EndpointOutage outage) {
                throw outage;
            } catch (Exception e) {
                logFailure("Architect revision re-ask failed; falling back to the first attempt's "
                    + "own discard reason", e);
                return ReviseAttempt.discarded(original, discardReason);
            }
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (Exception e) {
            logFailure("Architect revision failed; proceeding with original design", e);
            return ReviseAttempt.discarded(original, "revision discarded: it did not parse");
        }
    }

    /**
     * A revision that names contracts but says nothing at all about another section — its key
     * never in the reply — has left that section unchanged, so the original's is kept rather than
     * the whole revision refused (harness run 59, 2026-10-01: the "name the types" ask comes back
     * with the contracts and nothing else, which is what it asked for). A section stated empty is
     * NOT touched: that stays a drop, as {@link #looksLikeForgottenSection} explains. A reply with
     * no contracts at all is not touched either — it left out the very thing the revision is about,
     * and is still re-asked.
     */
    private static void carryOverUnchangedSections(LlmDesign parsed, DesignDocument original,
                                                   DesignDocument revised) {
        if (revised.contracts().isEmpty()) {
            return;
        }
        if (parsed.requirements == null && !original.requirements().isEmpty()) {
            revised.setRequirements(new ArrayList<>(original.requirements()));
            log.info("the revision named no requirements section; keeping the original's");
        }
        if (parsed.decisions == null && !original.decisions().isEmpty()) {
            revised.setDecisions(new ArrayList<>(original.decisions()));
        }
        if (parsed.risks == null && !original.risks().isEmpty()) {
            revised.setRisks(new ArrayList<>(original.risks()));
        }
    }

    /** The most of a raw architect reply a log line carries: head and tail, the middle elided. */
    static final int RAW_REPLY_LOG_CAP = 12000;

    /**
     * Logs {@code raw} at WARN, capped to {@link #RAW_REPLY_LOG_CAP} characters (head and tail) —
     * what the model actually sent, which nothing else records (harness run 59: a design whose
     * contracts carried no type, and two revisions discarded, with no way to see why).
     */
    static void warnRawReply(String why, String raw) {
        String shown = raw == null ? "(no reply was recorded)" : cappedHeadAndTail(raw);
        log.warn("Architect {} — raw reply ({} chars): {}", why, raw == null ? 0 : raw.length(), shown);
    }

    static String cappedHeadAndTail(String raw) {
        if (raw.length() <= RAW_REPLY_LOG_CAP) {
            return raw;
        }
        int half = RAW_REPLY_LOG_CAP / 2;
        return raw.substring(0, half) + "\n...[" + (raw.length() - RAW_REPLY_LOG_CAP)
            + " chars elided]...\n" + raw.substring(raw.length() - half);
    }

    /** Logs the raw reply when {@code design} has a contract that names no usable type. */
    private void warnOfContractsWithoutAType(DesignDocument design, String stage) {
        for (ApiContract c : design.contracts()) {
            if (c != null && !c.namesAType()) {
                warnRawReply("design from the " + stage + " has a contract with no usable type ("
                    + c.name() + ")", lastParsedReply.get());
                return;
            }
        }
    }

    /**
     * Whether every section {@code discardReason} would refuse {@code revised} for was left out of
     * the wire reply entirely, rather than explicitly stated as empty or as fewer entries than the
     * original had — see {@link #revise}'s own javadoc for why this is the line between "the model
     * forgot" and "the model decided". A requirement count that is merely SHORTER than the
     * original's, with the field still present, is not treated as forgotten: the model plainly did
     * try to answer it and came up short, which is a drop like any other, not an omission.
     */
    private static boolean looksLikeForgottenSection(LlmDesign parsed, DesignDocument original,
                                                      DesignDocument revised) {
        boolean droppedRequirements = revised.requirements().size() < original.requirements().size();
        if (droppedRequirements && parsed.requirements != null) {
            return false;
        }
        boolean droppedContracts = !original.contracts().isEmpty() && revised.contracts().isEmpty();
        if (droppedContracts && parsed.contracts != null) {
            return false;
        }
        return droppedRequirements || droppedContracts;
    }

    /**
     * The reminder appended to the re-ask's user message, naming exactly which sections came back
     * missing and restating what belongs in them — built entirely from the ORIGINAL design, so the
     * architect is told what to restore without this class needing to trust the bad reply for
     * anything beyond having already been read.
     */
    private static String missingSectionsReminder(DesignDocument original, DesignDocument revised) {
        StringBuilder sb = new StringBuilder(
            "\n\nYour reply above left out the following — send the WHOLE corrected design again, "
            + "in the same JSON schema, and this time include every one of these unless an "
            + "objection above specifically asks you to remove or replace it:\n");
        if (revised.requirements().size() < original.requirements().size()) {
            for (int i = 0; i < original.requirements().size(); i++) {
                Requirement r = original.requirements().get(i);
                sb.append("- requirement ").append(requirementHandle(i)).append(" [")
                    .append(r.priority()).append("] ").append(r.text()).append('\n');
            }
        }
        if (!original.contracts().isEmpty() && revised.contracts().isEmpty()) {
            for (ApiContract c : original.contracts()) {
                sb.append("- contract ").append(c.name()).append(": ").append(c.signatureSketch());
                if (c.namesAType()) {
                    sb.append("  [type ").append(c.typeName().strip());
                    if (!c.members().isEmpty()) {
                        sb.append(" with ").append(String.join("; ", c.members()));
                    }
                    sb.append(']');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Why a revision must be discarded and the original kept, or null when it may replace it —
     * see {@link ReviseAttempt}.
     */
    private static String discardReason(DesignDocument original, DesignDocument revised) {
        List<String> reasons = new ArrayList<>();
        int originalReqs = original.requirements().size();
        int revisedReqs = revised.requirements().size();
        if (revisedReqs < originalReqs) {
            reasons.add((originalReqs - revisedReqs) + " requirement(s)");
        }
        if (!original.contracts().isEmpty() && revised.contracts().isEmpty()) {
            reasons.add("all contracts");
        }
        if (reasons.isEmpty()) {
            return null;
        }
        return "revision discarded: it dropped " + String.join(" and ", reasons);
    }

    /**
     * Story-scoped decomposition. Tasks claim CRITERIA (R7:C1) rather than inventing their own, so
     * "this task is done" and "this requirement is satisfied" are the same statement checked by the
     * same test. Every criterion in the slice must be claimed by some task; the validator enforces
     * it, because an unclaimed criterion means the story can never legitimately reach REVIEW.
     */
    public TaskGraph plan(DesignDocument design, String goal, StoryScope scope) {
        return plan(design, goal, scope, "");
    }

    /**
     * @param repoLayoutBrief where the target repository's build actually compiles from, rendered
     *                        by {@link RepoLayoutBrief}. Blank when the layout could not be read,
     *                        and the prompt is then exactly what it was — a planner is never handed
     *                        a guess dressed up as a fact. When it is present it is the difference
     *                        between a write set derived from THIS repository and one derived from
     *                        what a Maven project usually looks like; the second is what produced a
     *                        whole run's files in a source root that did not exist.
     */
    public TaskGraph plan(DesignDocument design, String goal, StoryScope scope,
                          String repoLayoutBrief) {
        if (scope == null || scope.isEmpty()) {
            return plan(design, goal, repoLayoutBrief,
                scope == null ? "" : scope.constraintBrief());
        }
        try {
            String head = scopeBriefing(goal, scope)
                + nullSafe(repoLayoutBrief)
                + (design == null ? "" : "\n\nDesign:\n" + designSummary(design));
            // Set by planAttempt() on a retry, on THIS thread, right before calling this
            // very method — see the thread-locals' own javadoc for why this indirection
            // exists instead of a sixth parameter.
            String retry = nullSafe(pendingRetryFeedback.get());
            AgentWork work = planWork(design, scope);
            // A plan sent back goes to the planner that wrote it, in its own conversation, with
            // its lookups intact (section 54). A first attempt, or one with nothing to send
            // back, starts a session; any conversation left from an earlier attempt is let go.
            String planKey = design == null ? null : "plan:" + design.id();
            KeptConversations.Held prior = planKey == null ? null : kept.take(planKey);
            if (prior != null && (retry.isBlank() || work == null)) {
                prior.conversation().close();
                prior = null;
            }
            if (work != null && prior != null) {
                work = work.continuing(prior, sentBack("plan", retry.strip()));
            }
            // Neither the framework reference nor example code (section 73): the planner
            // splits and orders, and what a worker needs to know about how the work is built
            // reaches it from the architect, not through the planner's words. Run 98: 33 of the
            // planner's 44 whole-file reads were framework examples.
            LlmPlan parsed = callForJson(PLAN_SYSTEM_PROMPT,
                head + retry,
                work == null ? null : head + LOOK_IT_UP + retry,
                LlmPlan.class, LlmPlan.class, "the planner's", work);
            keepPendingAs(planKey);
            return toTaskGraph(design, withRecoveredTasks(parsed), scope);
        } catch (EndpointOutage outage) {
            // The retry loop is the answer to "the planner produced nothing usable", not to "the
            // planner was not there" — an outage pauses the whole run (spec §2.4), it does not
            // spend one of a plan's three tries.
            throw outage;
        } catch (Exception e) {
            lastFailureReason.set(logFailure("Architect scoped planning failed", e));
            return null;
        }
    }

    /**
     * The one place every catch in this class turns a failure into (a) a WARN with the exception's
     * class, message and the first stack frame, and (b) the non-null text recorded as the failure
     * reason a caller — chiefly {@link #planAttempt} — reports to the operator.
     *
     * <p>Built 2026-09-04, after a PARK message read "Last objections: the architect answered
     * unusably" with no exception text after it, three times in under two seconds, for a run whose
     * design genuinely had contracts. {@code Exception#getMessage()} is null for a great many
     * exceptions — a bare {@code new NullPointerException()}, most exceptions built through
     * {@code Objects.requireNonNull(Object)}, several HTTP client failures — and every catch here
     * used to record exactly that, so a null message meant {@link #planAttempt} had nothing to fall
     * back on but its own canned "the architect answered unusably" text: an attempt that failed
     * without ever calling the model looked identical, to the operator, to one where the model
     * genuinely refused. {@link Throwable#toString()} always carries the class name even when the
     * message is null, and the first frame says where — enough to tell "designSummary threw before
     * any call was made" apart from "the model was reached and said something unusable".
     */
    private static String logFailure(String what, Exception e) {
        StackTraceElement[] trace = e.getStackTrace();
        String firstFrame = trace.length > 0 ? trace[0].toString() : "(no stack trace)";
        String detail = e.toString();
        log.warn("{}: {} at {}", what, detail, firstFrame);
        return detail;
    }

    /**
     * <b>Since section 73 (owner's decision, 2026-10-08)</b> the planner writes no how-to and
     * does not have to guess write sets: instructions say what a task delivers, and the files
     * of its contracts are computed ({@code ComputedReservation}).
     *
     * <p>The scoped planner's system instructions — factored out of {@link #plan(DesignDocument,
     * String, StoryScope, String)} so {@link #planAttempt} sends the retrying workflow loop exactly
     * the same rules on every attempt. Only the USER message changes between attempts (it grows the
     * previous reply and its objections); the rules the planner is held to do not.
     */
    private static final String PLAN_SYSTEM_PROMPT =
        "You are an AI planner. Decompose the work into a directed acyclic graph of "
        + "implementation tasks that together satisfy EXACTLY the acceptance criteria "
        + "listed — no more and no less. YOU SPLIT AND ORDER; YOU DO NOT SAY HOW. A task's "
        + "\"instructions\" are one to three sentences that say WHAT the task delivers: which "
        + "contracts, which checks, and what it leaves for a later task. Never write how to "
        + "build it - no library types, no annotations, no steps: the design's FACT lines are "
        + "what the architect established about that, they are handed to the workers word for "
        + "word with the tasks they concern, and a worker looks up the rest itself. "
        + "RULES: decompose only to the smallest unit that "
        + "still has a mechanically verifiable acceptance test and a clean write set — no "
        + "further. Tasks that can run concurrently MUST have disjoint writeSet paths "
        + "(repo-relative dirs or files). A CHANGE THAT BREAKS EXISTING CODE IS ONE TASK "
        + "WITH THAT CODE: a task that adds an abstract method to an existing interface or "
        + "abstract class, adds a constructor parameter or record component, or removes or "
        + "changes a member other code uses, also changes every existing file that would "
        + "stop compiling (every implementing class, every caller), because each candidate "
        + "is verified by compiling the whole build; never split such a change from its "
        + "implementation. YOU DO NOT HAVE TO WORK OUT A TASK'S FILES: the file of every "
        + "contract a task delivers, and every existing file that stops compiling with it, "
        + "is added to the task's writeSet for you from the project's own types. Put in "
        + "writeSet only what that cannot know - a file with no contract (a resource, a "
        + "class no contract names) or the module directory a new type belongs in - and a "
        + "worker that needs a file nobody else holds may take it. NO TASK REMOVES "
        + "EXISTING PUBLIC CODE - a public type, method or field the project already has - "
        + "unless a criterion says in so many words that something is to be removed: a "
        + "criterion that something is NOT offered is met by not offering it, and a candidate "
        + "that deletes existing public members without such a criterion fails verification. "
        + "Every task MUST list "
        + "in criterionRefs the "
        + "criteria it satisfies, using the refs exactly as given (e.g. R7:C1). EVERY "
        + "criterion listed must be claimed by at least one task, and you MUST NOT invent "
        + "refs that are not in the list. EVERY CHECK IS ANSWERED BY EXACTLY ONE TASK: the "
        + "task that completes it. If delivering one check takes several tasks (e.g. a data "
        + "model, then a server implementation, then a client UI), only the LAST of them — "
        + "the one whose own work finishes the behaviour the check proves — claims the "
        + "check in criterionRefs; the earlier ones are enablers the last task depends on, "
        + "and answer for no check of their own. "
        // Harness run 40, 2026-09-26: a service implementation nothing depended on and that
        // claimed no check was planned three times running and the run parked. The validator's
        // rule was never stated to the planner; now it is, before it is enforced.
        + "EVERY TASK THAT CLAIMS NO CHECK MUST BE ONE A TASK THAT CLAIMS A CHECK DEPENDS ON, "
        + "directly or through other tasks: work no check needs is left out of the plan, not "
        + "planned as a loose end. "
        // Harness runs 44/45, 2026-09-27: the server task that claimed every check was made to
        // depend on a browser page task whose types its code never used, only to satisfy the
        // rule above. Harness run 49, 2026-09-30: the same rule and "every contract must be
        // delivered" contradicted each other over a browser-only client task, and the run parked;
        // such a task is now exempt, and the planner is told it is not to be dropped or wired in.
        + "A dependency counts only when the dependent task's code uses what that task "
        + "builds — never add an edge only to satisfy this rule. The one exception to that rule "
        + "is a task that writes ONLY code in a module that runs only in a browser: no check can "
        + "ever prove it, it needs no check and nothing has to depend on it, and it is still "
        + "planned — it is verified by compiling — so never drop it and never add an edge "
        + "to it for that reason. A check that describes what the user sees "
        + "or does is answered by the task that delivers that user-facing behaviour "
        + "(usually the client/UI task); server and model tasks it needs are enablers. "
        + "Never put the same ref in criterionRefs on "
        + "more than one task. Criteria whose [test: ...] names the SAME test "
        + "class (the part before '#') MUST be claimed by the same task: a test class is "
        + "one file, and a task is verified against exactly the test files it claims, so "
        + "a class split across two tasks fails the one that was never asked for it. "
        + "NEVER PLAN A TASK THAT WRITES THE ACCEPTANCE "
        + "TESTS. They are written for you by the test author before any task runs, and "
        + "any directory named src/test/java/swarm — in ANY module of this repository — is "
        + "protected from the workers so that no worker can make "
        + "its own gate pass. A task whose writeSet points there is impossible: nobody "
        + "will ever be allowed to do it. EVERY writeSet path MUST start with a directory this "
        + "repository's build actually compiles; when they could be read they are listed "
        + "for you below, and you must not invent one that is not there. Honour every "
        + "quality constraint given. THE PROJECT'S STANDING RULES ABOVE, WHERE THERE ARE "
        + "ANY, ARE NOT WORK: never plan a task to deliver one, never claim a check for "
        + "one, and never add a task because of one. They constrain HOW every task below is "
        + "written and nothing more — no task finishes them and they are never done. A "
        + "TASK'S INSTRUCTIONS MAY NOT RELAX A STATED RULE — writing something like \"in-memory "
        + "storage is acceptable if the database isn't set up yet\" when a rule mandates a "
        + "specific persistence store is still a rule the plan told a worker it could break; the "
        + "rules are not negotiable per task, however small or urgent that task's own step looks. "
        + "EVERY CONTRACT IN THE DESIGN IS DELIVERED BY EXACTLY ONE TASK. Name it in that "
        + "task's deliversContracts, using the contract's name exactly as the design gives it. "
        + "The acceptance tests are written against those types, so a contract no task delivers "
        + "is a test that can never compile — and the task that delivers one usually claims no "
        + "check itself, because the check is finished by a later task that builds on it. "
        // Harness run 39, 2026-09-25: an interface task whose methods take and return Book and
        // Rating was planned with no edge from the task writing Book and Rating, and its workers
        // could not compile a line. TypeDependencyOrder now adds such an edge itself; saying it
        // here is what keeps the planner from relying on that.
        + "A TASK WHOSE CODE USES A TYPE ANOTHER TASK CREATES MUST DEPEND ON THAT TASK — an "
        + "edge from the task that creates the type to the task that uses it — because the "
        + "using task's code cannot compile until that type exists. Output "
        + "JSON: {\"tasks\":[{\"id\",\"title\",\"instructions\",\"writeSet\":[],"
        + "\"readSet\":[],\"criteria\":[{\"text\",\"testClassOrFile\"}],"
        + "\"criterionRefs\":[\"R7:C1\"],\"deliversContracts\":[\"<contract name>\"]}],"
        + "\"edges\":[{\"from\":\"<id of the task that must FINISH FIRST>\","
        + "\"to\":\"<id of the task that WAITS for it>\"}]}";

    /**
     * One PLAN attempt, real by construction: it never invents a plan by itself, and whatever it
     * returns tells the caller exactly what happened, so a rejected or unparseable reply is never
     * mistaken for silence.
     *
     * @param graph          the parsed, decomposed plan — null when the architect did not produce one
     * @param rawReply       the architect's raw reply text, when it replied with something that at
     *                       least parsed as JSON (win or lose against the validator); null when
     *                       nothing parsed or nothing was said
     * @param replyRef       where {@code rawReply} was stored ({@link BlobSink}), or null when it was
     *                       not stored (no sink configured, or there was no reply to store)
     * @param failureReason  human-readable account of why {@code graph} is null; null when it is not
     */
    public record PlanAttempt(TaskGraph graph, String rawReply, String replyRef, String failureReason) {
        static PlanAttempt succeeded(TaskGraph graph, String rawReply, String replyRef) {
            return new PlanAttempt(graph, rawReply, replyRef, null);
        }
        static PlanAttempt failed(String failureReason, String rawReply, String replyRef) {
            return new PlanAttempt(null, rawReply, replyRef, failureReason);
        }
    }

    /**
     * A single, real attempt at the scoped plan — what {@link GreenfieldWorkflow}'s PLAN retry loop
     * calls on every one of its (now three) tries. Unlike {@link #plan(DesignDocument, String,
     * StoryScope, String)} on its own, a failure is never swallowed into a bare {@code null}: the
     * caller gets back exactly why, and — when the architect said anything parseable at all — the
     * raw reply and where it is kept, so a plan that is ultimately rejected can still be shown to
     * the operator rather than thrown away.
     *
     * <p>Deliberately calls the overridable {@link #plan(DesignDocument, String, StoryScope,
     * String)} to actually produce the graph, rather than duplicating its call — a subclass that
     * overrides {@code plan} (a test harness stamping a tool-turn budget onto every planned task,
     * calling it "the only method the workflow calls") must keep seeing every real attempt, not just
     * the first. The retry feedback and the raw-reply/failure capture cross that method call via the
     * thread-locals declared with the fields, since neither fits {@code plan}'s fixed signature or
     * return type.
     *
     * @param previousReply      the previous attempt's raw reply, or null on the first attempt
     * @param previousObjections the validator's objections to that reply; empty on the first attempt
     */
    public PlanAttempt planAttempt(DesignDocument design, String goal, StoryScope scope,
                                   String repoLayoutBrief, String previousReply,
                                   List<String> previousObjections) {
        return planAttempt(design, goal, scope, repoLayoutBrief, previousReply, previousObjections,
            List.of());
    }

    /**
     * The same, also told what the attempts BEFORE the previous one were rejected for.
     *
     * <p>Harness run 40, 2026-09-26: attempt 1 was rejected for an orphaned task, attempt 2 fixed
     * that and lost two contracts, attempt 3 was shown only attempt 2's reply and objections — so it
     * restored the contracts and brought the orphan back, word for word, and the run parked. The
     * retry prompt had no memory beyond one attempt, so the planner could not know its fix for one
     * objection was undoing its fix for another. See {@link #retryFeedback(String, List, List)} for
     * what it is told now.
     *
     * @param earlierObjections every objection from the attempts before the previous one, in order;
     *                          empty on the first two attempts
     */
    public PlanAttempt planAttempt(DesignDocument design, String goal, StoryScope scope,
                                   String repoLayoutBrief, String previousReply,
                                   List<String> previousObjections,
                                   List<String> earlierObjections) {
        if (scope == null || scope.isEmpty()) {
            // The unscoped planner has no retry protocol of its own (no story, nothing to feed
            // objections about) — it is a single try, exactly as it always was.
            lastFailureReason.remove();
            TaskGraph graph;
            try {
                graph = plan(design, goal, repoLayoutBrief, scope == null ? "" : scope.constraintBrief());
            } catch (EndpointOutage outage) {
                throw outage;
            } catch (Exception e) {
                // Same reasoning as the scoped branch below: plan() is overridable, and an override
                // that throws after calling super.plan() must not escape this method uncaught.
                lastFailureReason.remove();
                return PlanAttempt.failed(logFailure("Architect planning failed", e), null, null);
            }
            if (graph != null) {
                return PlanAttempt.succeeded(graph, null, null);
            }
            // plan()'s own catch (above), or a return-null path inside it such as toTaskGraph
            // finding no tasks, already recorded exactly what happened — every return-null path in
            // this class now sets this thread-local before returning (2026-09-04, harness run 21:
            // the canned "the architect answered unusably" fallback that used to stand in for a
            // missing reason made a genuine bug indistinguishable from the model refusing).
            String reason = lastFailureReason.get();
            lastFailureReason.remove();
            return PlanAttempt.failed(reason, null, null);
        }
        lastParsedReply.remove();
        lastFailureReason.remove();
        pendingRetryFeedback.set(retryFeedback(previousReply, previousObjections, earlierObjections));
        try {
            TaskGraph graph;
            try {
                graph = plan(design, goal, scope, repoLayoutBrief);
            } catch (EndpointOutage outage) {
                throw outage; // pauses the whole run — see plan()'s own javadoc; never a failed attempt
            } catch (Exception e) {
                // plan() is overridable (a test harness stamps a tool-turn budget onto every planned
                // task by wrapping it), and its own try/catch only covers what happens INSIDE that
                // method — an override that throws after calling super.plan() would otherwise escape
                // this method entirely uncaught, killing the whole PLAN stage on attempt one instead
                // of producing a real, reportable failed attempt like every other kind of refusal.
                return PlanAttempt.failed(
                    logFailure("Architect scoped planning failed", e), lastParsedReply.get(), null);
            }
            String rawReply = lastParsedReply.get();
            if (graph == null) {
                String reason = lastFailureReason.get();
                // e.getMessage() (now lastFailureReason) already names a blob when plan()'s own
                // double-parse-failure did the storing (LlmReplyBlobs.describeFailure); toTaskGraph
                // sets its own reason ("the reply had no tasks") the same way — every return-null
                // path in this class sets this thread-local, so there is no longer a canned
                // "the architect answered unusably" fallback standing in for a missing reason.
                return PlanAttempt.failed(reason, rawReply, null);
            }
            String ref = rawReply == null ? null
                : blobs.put(rawReply.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return PlanAttempt.succeeded(graph, rawReply, ref);
        } finally {
            // An EndpointOutage propagates straight through this block (it is not caught here, by
            // design — see plan()'s own javadoc), and this still runs, leaving nothing of this
            // attempt behind for the next call on this thread to trip over.
            pendingRetryFeedback.remove();
            lastParsedReply.remove();
            lastFailureReason.remove();
        }
    }

    /**
     * The retry feedback appended to the planner's user message: its own previous reply, exactly
     * why it was rejected, and what earlier attempts were rejected for. Blank on a first attempt
     * (nothing to feed back) or when nothing about any previous attempt could be shown.
     *
     * <p><b>What changed on 2026-09-26 (harness run 40), and why.</b> Before, this said "Your
     * previous plan was rejected for these reasons: A; B. Fix exactly these…" and appended the
     * previous reply. Two gaps showed in run 40:
     *
     * <ul>
     *   <li><b>No memory past one attempt.</b> Attempt 3 was shown attempt 2's objection (two
     *       contracts not delivered) and fixed it by going back towards attempt 1 — reintroducing
     *       attempt 1's orphaned task word for word. Now every objection from the attempts before
     *       the previous one is listed under "must not bring back".</li>
     *   <li><b>A repeat looked like news.</b> An objection the planner had already been given once,
     *       fixed, and then undone reads exactly like a fresh one. Now it is marked as a repeat, with
     *       the reason it keeps coming back: the change made for another objection undid it.</li>
     * </ul>
     *
     * <p>HOW to fix each objection is not added here: it is in the objection text itself (see
     * {@link TaskGraphValidator}), so the same words reach the planner, the run log and the park
     * brief. The objections are a bulleted list rather than one "; "-joined line, because the
     * remedies are now full sentences with their own semicolons.
     */
    static String retryFeedback(String previousReply, List<String> previousObjections,
                                List<String> earlierObjections) {
        List<String> previous = previousObjections == null ? List.of() : previousObjections;
        List<String> earlier = earlierObjections == null ? List.of() : earlierObjections;
        boolean showPrevious = previousReply != null && !previous.isEmpty();
        if (!showPrevious && earlier.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (showPrevious) {
            sb.append("\n\nYour previous plan was rejected for these reasons:");
            boolean anyRepeat = false;
            for (String objection : previous) {
                boolean repeat = earlier.contains(objection);
                anyRepeat |= repeat;
                sb.append("\n- ").append(repeat ? "[AGAIN] " : "").append(objection);
            }
            if (anyRepeat) {
                sb.append("\nThe ones marked [AGAIN] are objections an EARLIER plan of yours was "
                    + "already rejected for, word for word. You fixed them once; a change you made "
                    + "for another objection brought them back. Fix them again AND keep the fix "
                    + "for every other objection — a plan that trades one objection for another is "
                    + "rejected again.");
            }
            sb.append("\nFix exactly these, each in one of the ways its reason gives; do not "
                + "repeat them and do not otherwise start over.");
        }
        List<String> keepFixed = new ArrayList<>();
        for (String objection : earlier) {
            if (!previous.contains(objection) && !keepFixed.contains(objection)) {
                keepFixed.add(objection);
            }
        }
        if (!keepFixed.isEmpty()) {
            sb.append("\n\nAn earlier plan of yours was also rejected for the following. Your "
                + "previous plan had fixed them; your new plan must not bring any of them back:");
            for (String objection : keepFixed) {
                sb.append("\n- ").append(objection);
            }
        }
        if (showPrevious) {
            sb.append("\n\nYour previous plan, verbatim:\n").append(previousReply);
        }
        return sb.toString();
    }

    /**
     * Where acceptance tests live, and therefore what workers may not write.
     *
     * <p>Historically shared rather than a literal because a second graph source — a single-task
     * fallback used when the planner produced nothing usable — once left it null, silently
     * disabling the test author on exactly the path that existed to rescue a failed plan. That
     * fallback is gone (2026-09-03: PLAN parks and says why instead of fabricating a plan), but the
     * constant stays shared because {@code GreenfieldWorkflow} is still where every task's real
     * directory is stamped, via {@code withAcceptanceTestDir}.
     *
     * <p><b>It is a default, not the answer.</b> In a multi-module project this directory is
     * compiled by nothing — the repository root of an aggregator build has no sources at all — so
     * the real one is read from the build by
     * {@link com.swarmcoder.verify.AcceptanceTestLocation} and applied to every task by
     * {@code GreenfieldWorkflow}. This constant is what a task carries until that happens, and
     * what a single-module project ends up with anyway.
     */
    public static final String ACCEPTANCE_TEST_DIR =
        com.swarmcoder.verify.AcceptanceTestLocation.PROTECTED_SUBDIR;

    /**
     * Where the test author must actually PUT the files — the {@code swarm.accept} package.
     *
     * <p>Separate from {@link #ACCEPTANCE_TEST_DIR} because the two are different things and
     * conflating them cost a live run on 2026-08-28. The protected tree has to be the whole
     * {@code swarm} directory, so a worker cannot slip a test in beside the acceptance ones; the
     * WRITE directory is the accept package alone, because every criterion's agreed test reference
     * begins {@code swarm.accept.}. The test author was being told both "package swarm.accept" and
     * "all files must live under src/test/java/swarm", with the second one shown again as the
     * example path — so it wrote {@code src/test/java/swarm/MultiplyAcceptTest.java}, every test
     * came out named {@code swarm.MultiplyAcceptTest#…}, not one criterion's reference matched,
     * and the run parked. The instruction was ambiguous and the model resolved it the wrong way;
     * both are now named separately and neither is a guess.
     */
    public static final String ACCEPTANCE_TEST_WRITE_DIR = ACCEPTANCE_TEST_DIR + "/accept";

    /**
     * The {@code accept} package inside whichever protected tree a task actually carries.
     *
     * <p>The test author is told this and the path policy allows exactly this, from the same
     * string, so the two cannot drift. They already had: run {@code e01d1378} told the author to
     * write to the root {@code src/test/java/swarm/accept} while the task's protected tree was
     * being read from the build, and the author's attempt to write into a real module was refused
     * by the policy for leaving the protected directory.
     */
    public static String acceptanceWriteDir(String protectedDir) {
        return com.swarmcoder.verify.AcceptanceTestLocation.writeDirUnder(protectedDir);
    }

    /** Decomposition with write sets + criteria; null on failure (caller falls back). */
    public TaskGraph plan(DesignDocument design, String goal) {
        return plan(design, goal, "");
    }

    /** @param repoLayoutBrief see {@link #plan(DesignDocument, String, StoryScope, String)}. */
    public TaskGraph plan(DesignDocument design, String goal, String repoLayoutBrief) {
        return plan(design, goal, repoLayoutBrief, "");
    }

    /** @param constraintBrief see {@link #design(String, String)}. */
    public TaskGraph plan(DesignDocument design, String goal, String repoLayoutBrief,
                          String constraintBrief) {
        try {
            LlmPlan parsed = callForJson(
                "You are an AI planner. Decompose the goal into a directed acyclic graph of "
                + "implementation tasks. RULES: decompose only to the smallest unit that still has a "
                + "mechanically verifiable acceptance test and a clean write set — no further. Tasks "
                + "that can run concurrently MUST have disjoint writeSet paths (repo-relative dirs or "
                + "files). Every task needs at least one acceptance criterion naming the test class "
                + "that will verify it. Every task MUST list the requirement handles (R1, R2, … as "
                + "shown in the design summary) it helps satisfy in requirementRefs, and every "
                + "requirement must be covered by at least one task. NEVER PLAN A TASK THAT "
                + "WRITES THE ACCEPTANCE TESTS: any directory named src/test/java/swarm — in ANY "
                + "module of this repository — is protected from the workers, "
                + "so a task whose writeSet points there is impossible. EVERY writeSet path MUST start "
                + "with a directory this repository's build actually compiles; when they could be "
                + "read they are listed for you below, and you must not invent one that is not "
                + "there. Output JSON: "
                + "{\"tasks\":[{\"id\",\"title\",\"instructions\",\"writeSet\":[],\"readSet\":[],"
                + "\"criteria\":[{\"text\",\"testClassOrFile\"}],\"requirementRefs\":[\"R1\"]}],"
                + "\"edges\":[{\"from\":\"<id of the task that must FINISH FIRST>\","
                + "\"to\":\"<id of the task that WAITS for it>\"}]}",
                constraintPreamble(constraintBrief) + "Goal: " + goal + nullSafe(repoLayoutBrief)
                    + (design == null ? "" : "\n\nDesign:\n" + designSummary(design)),
                LlmPlan.class, LlmPlan.class, "the planner's", planWork(design, null));
            return toTaskGraph(design, withRecoveredTasks(parsed));
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (Exception e) {
            // Recorded on the same thread-local plan() uses for the scoped path, so the unscoped
            // half of planAttempt() (below) can report exactly what threw here too, instead of its
            // own canned "the architect answered unusably" text.
            lastFailureReason.set(logFailure("Architect planning failed", e));
            return null;
        }
    }

    /** Unconstrained multi-turn call — the research phase, where the model must be free to speak. */
    private String call(List<Map<String, String>> messages, double temperature) throws Exception {
        long prompt = 0;
        for (Map<String, String> message : messages) {
            prompt += CloudGate.estimateTokens(message.get("content"));
        }
        cloudGate.charge(prompt);
        String response;
        try {
            response = client.as("architect").chatCompletionStream(messages, null, temperature)
                .collect(Collectors.joining());
        } catch (Exception e) {
            throw refundIfOutage(prompt, e);
        }
        cloudGate.charge(CloudGate.estimateTokens(response));
        return response;
    }

    private String call(String system, String user, Class<?> schema) throws Exception {
        return call(List.of(
            Map.of("role", "system", "content", system),
            Map.of("role", "user", "content", user)), schema, 0.2);
    }

    // ------------------------------------------------------------------------------------------
    // The architect and the planner as agents (owner decision, 2026-10-02)
    // ------------------------------------------------------------------------------------------

    /**
     * A safety stop, not a budget: how many drafts one session may have checked before further
     * ones are refused. {@code swarmcoder.roles.maxDrafts} replaces it.
     */
    static final int MAX_DRAFT_CHECKS = Integer.getInteger("swarmcoder.roles.maxDrafts", 12);

    static final String DESIGN_HOW = "\n\nHOW YOU WORK IN THIS SESSION. You do not answer in one "
        + "reply: you work in steps, with tools, and you hand the design in through a tool.\n"
        + "1. CHECK EVERY FACT BEFORE YOU RELY ON IT. Before the design names a library type, a "
        + "member of one, a type this project already has, or a package, confirm it with a tool, "
        + "and ask whenever you are not sure how something is done here (which tool is for what "
        + "is listed at the end). What you were given below is where you start, not the limit of "
        + "what you may read or ask. GUESSING IS THE FAILURE THIS SESSION EXISTS TO PREVENT: "
        + "a library class that does not exist, a method a library type does not have, a second "
        + "copy in a new package of a class this project already has - each of these has stopped "
        + "a build. Before you contract a NEW type, look whether the project already has one of "
        + "that name or purpose, and build on it where it is.\n"
        + "2. KEEP WHAT THE WORKERS WILL NEED, AS YOU FIND IT. The code is written by workers on "
        + "a smaller model. They are given the contracts and NOTHING else of what you read here "
        + "except what you keep. When a lookup shows HOW this project or its framework does "
        + "something a task will have to do - what makes the framework find a service, how a "
        + "screen is put on the entry page, how a store's root is obtained, which existing type "
        + "to extend, a few lines of real code that do the same kind of thing - call "
        + "keep_for_workers right then, naming the lookup and the lines: the lines are copied "
        + "for you, you write one sentence. Say what each fact is about (the contract it "
        + "concerns, or nothing for the whole project); it travels with the tasks that build "
        + "that contract AND with the tasks built on it, so a fact about how code that USES a "
        + "contract is written - a caller, a screen, a client - is about the contract it uses. "
        + "Keep what a worker could not know without your lookup; do not keep what a contract "
        + "already says. Above all keep what the compiler will not tell a worker: something "
        + "the framework requires that compiles when it is left out and fails only when the "
        + "application runs, with the lines of real code that do it and, in your sentence, "
        + "what goes wrong without it. Keep the lines that DO the thing - the call, the "
        + "annotation, the registration - never a file's package line and imports; when the "
        + "part that matters is one member, look that member up on its own and keep it whole. "
        + "Every part of the design that a task will write needs at least one such fact: the "
        + "parts no test of the build can execute most of all, because nothing else catches a "
        + "mistake there before the application is used.\n"
        + "3. CHECK YOUR DRAFT. Call check_design with the complete JSON object described above, "
        + "as one string. It runs the build's own mechanical checks and returns their objections. "
        + "Fix every objection - looking up whatever it shows you guessed - and check again.\n"
        + "4. HAND IN. Call report_done with an empty string to hand in the draft you last "
        + "checked exactly as it is, or with the complete final JSON object if you changed it "
        + "since.";

    static final String PLAN_HOW = "\n\nHOW YOU WORK IN THIS SESSION. You do not answer in one "
        + "reply: you work in steps, with tools, and you hand the plan in through a tool.\n"
        + "1. YOU SPLIT THE DESIGN INTO TASKS AND ORDER THEM. NOTHING ELSE. You do not say how "
        + "anything is built and you do not learn the framework: that was the architect's work, "
        + "its findings are in the design, and they reach the workers word for word without "
        + "you. What you check with a tool is WHERE things are: before a task names an existing "
        + "class of this project, a module or a file path, confirm it - list_files shows the "
        + "modules and source directories that really exist, and the tree says which type is in "
        + "which file and what uses it (which tool is for what is listed at the end). A write "
        + "set in a directory the build does not compile sends every worker of that task the "
        + "wrong way.\n"
        + "2. CHECK YOUR DRAFT. Call check_plan with the complete JSON object described above, as "
        + "one string. It runs the build's own mechanical checks and returns their objections, "
        + "all of them at once. Fix every objection and check again. What it lists under NOTES "
        + "is not an objection: an order between two tasks that the types they use decide, and "
        + "the task of a type declared inside another, are put right for you in the plan that "
        + "runs - never write the plan again for a note. Give it a first draft as "
        + "soon as you can write one: a draft you have checked is kept if this session ends "
        + "early, a plan still in your head is not, and what you read many turns ago is cut "
        + "to its first lines to keep each call small.\n"
        + "3. HAND IN. Call report_done with an empty string to hand in the draft you last "
        + "checked exactly as it is, or with the complete final JSON object if you changed it "
        + "since.";

    /** The session the architect and the planner work in; null is one reply, as it always was. */
    private volatile LookupAgent lookupAgent;
    /** The mechanical checks for drafts, for the call on this thread; see {@link #checkingDraftsWith}. */
    private final ThreadLocal<DraftChecks> draftChecks = new ThreadLocal<>();

    /**
     * The mechanical checks a design or a plan is held to after it is handed in, run on a draft.
     * Supplied by the workflow, which owns the checkout, the build layout and the story.
     */
    public interface DraftChecks {
        /** The objections to a draft design; empty when there are none. */
        List<String> design(DesignDocument draft);

        /**
         * The objections to a draft plan; empty when there are none.
         *
         * @param design the design the plan decomposes, as the planner was given it
         */
        List<String> plan(TaskGraph draft, DesignDocument design);

        /**
         * The same check with what it put right in the draft and what it proposes (live run
         * 103, section 76): notes the planner is told and need not answer.
         */
        default PlanDraft planChecked(TaskGraph draft, DesignDocument design) {
            return new PlanDraft(plan(draft, design), List.of());
        }
    }

    /**
     * @param objections what would send the plan back
     * @param notes      edges added or turned round from the types the tasks use, nested types
     *                   given to their outer type's task, and proposals; never objections
     */
    public record PlanDraft(List<String> objections, List<String> notes) {
    }

    /** Closes without an exception, so it reads as a plain try-with-resources. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Turns the architect and the planner into tool-using agents: every design, revision and plan
     * call first runs as a lookup session over the project and its reference material, and falls
     * back to the one reply when that session hands nothing in. Null (the default) leaves every
     * call as it was.
     */
    public void setLookupAgent(LookupAgent agent) {
        this.lookupAgent = agent;
    }

    /** True when design, revision and plan calls run as lookup sessions. */
    public boolean worksAsAnAgent() {
        return lookupAgent != null;
    }

    /**
     * For the calls made on this thread until the scope is closed, {@code check_design} and
     * {@code check_plan} run {@code checks}. Thread-scoped for the reason
     * {@code pendingRetryFeedback} is: one instance serves every run of a project.
     */
    public Scope checkingDraftsWith(DraftChecks checks) {
        DraftChecks before = draftChecks.get();
        draftChecks.set(checks);
        return () -> {
            if (before == null) {
                draftChecks.remove();
            } else {
                draftChecks.set(before);
            }
        };
    }

    /** Reads a draft as the thing the checks judge; throws when it cannot be read as one. */
    private interface DraftReader<T> {
        T read(String json) throws Exception;
    }

    /**
     * What one call's session is: who it is on the cost record, how it is told to work, its
     * bounds, and what its check tool does with a draft.
     */
    private record AgentWork(String role, String noun, String how, LookupAgent.Limits limits,
                             List<ApiContract> contracts,
                             java.util.function.Function<String, DraftTools.Checked> check,
                             KeptConversations.Held prior, String followUp) {

        AgentWork(String role, String noun, String how, LookupAgent.Limits limits,
                  List<ApiContract> contracts,
                  java.util.function.Function<String, DraftTools.Checked> check) {
            this(role, noun, how, limits, contracts, check, null, null);
        }

        /**
         * This call goes on in {@code prior}, the conversation that handed in what is being sent
         * back, with {@code followUp} as its next message. Null {@code prior} changes nothing.
         */
        AgentWork continuing(KeptConversations.Held prior, String followUp) {
            return new AgentWork(role, noun, how, limits, contracts, check, prior, followUp);
        }
    }

    /**
     * The conversations of this role that handed in and may be sent back (section 54), by what
     * they handed in: "design:" and the design's id, "plan:" and the design's id.
     */
    private final KeptConversations kept = new KeptConversations();
    /** What the last session on this thread kept, until the call that ran it says what it was. */
    private final ThreadLocal<KeptConversations.Held> pendingKept = new ThreadLocal<>();

    /** Keeps what the last session on this thread ended with under {@code key}, if it kept any. */
    private void keepPendingAs(String key) {
        KeptConversations.Held held = pendingKept.get();
        pendingKept.remove();
        if (held != null && key != null) {
            kept.keep(key, held);
        } else if (held != null) {
            held.conversation().close();
        }
    }

    /** Lets go of a session this thread's last call kept and nobody claimed. */
    private void dropPending() {
        KeptConversations.Held held = pendingKept.get();
        pendingKept.remove();
        if (held != null) {
            held.conversation().close();
        }
    }

    /** What the conversation is told when what it handed in is sent back. */
    private static String sentBack(String noun, String what) {
        return "\n\nTHIS IS THE SAME CONVERSATION, NOT A NEW TASK. What you handed in was sent "
            + "back after you handed it in. Everything you looked up above is still true; do not "
            + "look it up again unless an objection shows it was wrong. " + what + "\n\nCorrect "
            + "the " + noun + ", give it to check_" + noun + ", and hand it in with report_done.";
    }

    /**
     * @param reader       the draft JSON as the design this very call would return for it
     * @param ownObjection this call's own objection to a draft, or null - a revision that loses
     *                     what the design had
     */
    private AgentWork designWork(DraftReader<DesignDocument> reader,
                                 java.util.function.Function<DesignDocument, String> ownObjection,
                                 List<ApiContract> contracts) {
        if (lookupAgent == null) {
            return null;
        }
        // Read HERE, on the caller's thread: the tools run on the agent runtime's own threads.
        DraftChecks checks = draftChecks.get();
        return new AgentWork("architect", "design", DESIGN_HOW, LookupAgent.Limits.configured(),
            contracts, json -> {
            DesignDocument draft;
            try {
                draft = reader.read(json);
            } catch (Exception e) {
                return DraftTools.Checked.unreadable(String.valueOf(e.getMessage()));
            }
            List<String> objections = new ArrayList<>();
            String own = ownObjection.apply(draft);
            if (own != null) {
                objections.add(own);
            }
            if (checks != null) {
                objections.addAll(checks.design(draft));
            }
            return new DraftTools.Checked(true, objections);
        });
    }

    /** What a revision may not lose, said as an objection the architect can act on. */
    private static String revisionLoses(DesignDocument original, DesignDocument revised) {
        String reason = discardReason(original, revised);
        return reason == null ? null : "This revision would be thrown away (" + reason + "). A "
            + "revision must keep every requirement and every section of the design it revises, "
            + "changing only what the objections ask for.";
    }

    private AgentWork planWork(DesignDocument design, StoryScope scope) {
        if (lookupAgent == null) {
            return null;
        }
        DraftChecks checks = draftChecks.get();
        return new AgentWork("planner", "plan", PLAN_HOW, LookupAgent.Limits.configured(),
            design == null ? List.of() : design.contracts(), json -> {
                TaskGraph draft;
                try {
                    LlmPlan parsed = LlmJson.parse(mapper, json, LlmPlan.class);
                    if (parsed == null || parsed.tasks == null || parsed.tasks.isEmpty()) {
                        parsed = recoverPlan(mapper, json);
                    }
                    draft = parsed == null ? null : toTaskGraph(design, parsed, scope);
                } catch (Exception e) {
                    return DraftTools.Checked.unreadable(String.valueOf(e.getMessage()));
                }
                if (draft == null) {
                    return DraftTools.Checked.unreadable("no task could be read out of it; the "
                        + "tasks go in a \"tasks\" array at the top of the object");
                }
                // What a draft was, on the log: live run 103's thirteen drafts were recorded
                // as a length each, so what the planner changed between them is not known.
                log.info("check_plan draft: {} task(s) {}, {} edge(s)", draft.tasks().size(),
                    draft.tasks().stream().map(t -> "'" + t.title() + "' ("
                        + t.deliveredContracts().size() + " contract(s))").toList(),
                    draft.dependencies() == null ? 0 : draft.dependencies().size());
                if (checks == null) {
                    return new DraftTools.Checked(true, List.of());
                }
                PlanDraft checked = checks.planChecked(draft, design);
                return new DraftTools.Checked(true, checked.objections(), checked.notes());
            });
    }

    /**
     * The first reply of a design, revision or plan call, as the JSON the rest of the call parses:
     * what a lookup session handed in when one is configured, otherwise - and whenever the session
     * ends with nothing handed in - the one schema-constrained reply.
     */
    private String firstReply(String system, String user, Class<?> schema, AgentWork work)
            throws Exception {
        return firstReply(system, user, null, schema, work);
    }

    /**
     * @param sessionUser what the lookup session is opened with when that differs from
     *                    {@code user} - the same task without the material a session can look
     *                    up for itself; null opens it with {@code user}. The reply without
     *                    tools is always given {@code user} whole.
     */
    private String firstReply(String system, String user, String sessionUser, Class<?> schema,
                              AgentWork work) throws Exception {
        LookupAgent agent = lookupAgent;
        String material = "";
        dropPending();
        // Nothing a call before this one kept and never attached may reach this call's design.
        pendingFindings.remove();
        if (agent != null && work != null) {
            KeptConversations.Held prior = work.prior();
            try {
                DraftTools[] own = new DraftTools[1];
                LookupAgent.Outcome outcome = null;
                if (prior != null && work.followUp() != null
                        && prior.tools() instanceof DraftTools again) {
                    // What it handed in was sent back: same conversation, its lookups intact.
                    again.nextRound(work.check());
                    outcome = agent.resume(clientOf(work.role()), prior.conversation(),
                        work.followUp()).orElse(null);
                    own[0] = outcome == null ? null : again;
                } else if (prior != null) {
                    prior.conversation().close();
                }
                if (outcome == null) {
                    outcome = agent.run(clientOf(work.role()), new LookupAgent.Ask(work.role(),
                        system + work.how(), sessionUser == null ? user : sessionUser,
                        work.limits(),
                        "Stop looking things up: give your draft to check_" + work.noun()
                            + " if you have not yet, then call report_done. A draft you have "
                            + "checked is handed in as it stands when the turns run out; a "
                            + work.noun() + " you have not given to a tool is lost.",
                        work.contracts(), session -> {
                            own[0] = new DraftTools(session, work.noun(), MAX_DRAFT_CHECKS,
                                work.check());
                            return own[0].bindings();
                        }));
                }
                String draft = own[0] == null ? null : own[0].submission();
                if (own[0] != null && "design".equals(work.noun())) {
                    // Kept whether or not a draft was handed in: what the architect established
                    // is true of the project, and the one reply that follows is its design too.
                    pendingFindings.set(own[0].findings());
                }
                if (outcome.conversation() != null) {
                    if (draft != null && own[0] != null) {
                        pendingKept.set(new KeptConversations.Held(outcome.conversation(), own[0]));
                    } else {
                        outcome.conversation().close();
                    }
                }
                if (draft != null) {
                    if (own[0].done()) {
                        log.info("The {} handed in its {} after {} turn(s), {} tool call(s) and "
                            + "{} draft check(s)", work.role(), work.noun(), outcome.turns(),
                            outcome.toolsUsed().size(), own[0].checks());
                    } else {
                        log.warn("The {}'s lookup session ended ({}) before it handed in; taking "
                            + "the {} it last had checked", work.role(),
                            outcome.stopped().map(Enum::name).orElse("no report_done"), work.noun());
                    }
                    return draft;
                }
                String text = outcome.finalText() == null ? "" : outcome.finalText().strip();
                if (outcome.submitted() && (text.startsWith("{") || text.startsWith("```"))) {
                    log.info("The {} answered with its {} as text after {} turn(s); taking it as "
                        + "its reply", work.role(), work.noun(), outcome.turns());
                    return text;
                }
                log.warn("The {}'s lookup session handed in no {} ({} after {} turn(s), {} tool "
                    + "call(s)); falling back to one reply without tools{}", work.role(),
                    work.noun(), outcome.stopped().map(Enum::name).orElse("ended with no draft"),
                    outcome.turns(), outcome.toolsUsed().size(),
                    outcome.material().isBlank() ? "" : ", which is shown what its lookups returned");
                material = outcome.material();
            } catch (RuntimeException e) {
                log.warn("The {}'s lookup session failed ({}); falling back to one reply without "
                    + "tools", work.role(), e.toString());
            }
        }
        return call(system, material.isBlank() ? user : user
            + "\n\nWHAT YOU LOOKED UP BEFORE THIS REPLY (results of read-only lookups over this "
            + "project and its reference material - rely on these, not on memory):\n" + material,
            schema);
    }

    /**
     * A schema-constrained call whose reply is parsed as JSON, with one retry — the parser's own
     * complaint fed back — when the first reply does not parse. A second failure is final: the raw
     * reply is stored and the honest description of what happened becomes the thrown exception's
     * message, so every call site's existing "X failed: {}" logging reports it without needing to
     * know a retry happened at all.
     *
     * <p>Built from the exact failures of 2026-09-03: one reply that was a JSON string containing
     * escaped JSON rather than the object itself (now handled without a retry, by
     * {@link LlmJson#parse}), and one revision reply rejected outright for a single field the
     * schema did not expect (now ignored, also by {@link LlmJson#parse}) — this retry is for
     * whatever still fails after both of those.
     */
    private <T> T callForJson(String system, String user, Class<?> schema, Class<T> type,
                              String roleLabel, AgentWork work) throws Exception {
        return callForJson(system, user, null, schema, type, roleLabel, work);
    }

    /** @param sessionUser see {@link #firstReply(String, String, String, Class, AgentWork)} */
    private <T> T callForJson(String system, String user, String sessionUser, Class<?> schema,
                              Class<T> type, String roleLabel, AgentWork work) throws Exception {
        // In a lookup session when one is configured (2026-10-02); otherwise, and whenever the
        // session ends with nothing handed in, the one schema-constrained reply it always was.
        String response = firstReply(system, user, sessionUser, schema, work);
        try {
            T value = LlmJson.parse(mapper, response, type);
            // Recorded on every successful parse, not just the planner's, so plan()'s own retry
            // (and only that call, since a design/review/revision reply never reaches planAttempt())
            // can be recovered without a sixth parameter on plan() itself — see the field's javadoc.
            lastParsedReply.set(response);
            return value;
        } catch (IOException parseFailure) {
            log.warn("{} reply was not valid JSON ({}); asking it to reply with only the JSON object",
                roleLabel, parseFailure.getMessage());
            String retryAsk = "That was not valid JSON: " + parseFailure.getMessage()
                + ". Reply with only the JSON object.";
            String retryResponse = call(List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user),
                    Map.of("role", "assistant", "content", response),
                    Map.of("role", "user", "content", retryAsk)),
                schema, 0.2);
            try {
                T value = LlmJson.parse(mapper, retryResponse, type);
                lastParsedReply.set(retryResponse);
                return value;
            } catch (IOException secondFailure) {
                throw new IOException(LlmReplyBlobs.describeFailure(
                    blobs, roleLabel, retryResponse, secondFailure.getMessage()), secondFailure);
            }
        }
    }

    /**
     * The role a schema-constrained reply is put on the run's cost record under: the plan is the
     * planner's work, as its lookup session already is, so the one-shot fallback for a plan is
     * "planner" too; everything else this class asks is the architect's.
     */
    static String meterRole(Class<?> schema) {
        return schema == LlmPlan.class ? "planner" : "architect";
    }

    /** Schema-constrained single-pass call — the design, revision and planning round trips. */
    private String call(List<Map<String, String>> messages, Class<?> schema, double temperature)
            throws Exception {
        long prompt = 0;
        for (Map<String, String> message : messages) {
            prompt += CloudGate.estimateTokens(message.get("content"));
        }
        cloudGate.charge(prompt);
        String response;
        String role = meterRole(schema);
        try {
            response = clientOf(role).as(role).chatCompletionStream(messages, schema, temperature)
                .collect(Collectors.joining());
        } catch (Exception e) {
            EndpointOutage outage = EndpointOutage.from(clientOf(role).baseUrl(), e);
            if (outage != null) {
                cloudGate.refund(prompt);
                throw outage;
            }
            throw e;
        }
        cloudGate.charge(CloudGate.estimateTokens(response));
        return response;
    }

    /**
     * Gives the prompt charge back when the call never reached the model, and returns the failure to
     * throw — as an {@link EndpointOutage} when it was one, so callers can tell it apart from an
     * answer they disliked.
     *
     * <p>The prompt is charged before sending (so a runaway cannot spend first and be stopped
     * after), which means an unreachable endpoint would otherwise bill every one of the retries the
     * workflow makes while waiting for it to come back.
     */
    private Exception refundIfOutage(long charged, Exception failure) {
        EndpointOutage outage = EndpointOutage.from(client.baseUrl(), failure);
        if (outage == null) {
            return failure;
        }
        cloudGate.refund(charged);
        return outage;
    }

    private static DesignDocument toDesign(UUID id, String goal, LlmDesign parsed, long revision) {
        List<Requirement> requirements = new ArrayList<>();
        if (parsed.requirements != null) {
            for (LlmRequirement r : parsed.requirements) {
                requirements.add(new Requirement(UUID.randomUUID(), r.text, enumOr(Priority.class, r.priority, Priority.MEDIUM)));
            }
        }
        return new DesignDocument(id, revision, goal, requirements, toDecisions(parsed.decisions),
            toContracts(parsed.contracts), toRisks(parsed.risks), null, Instant.now());
    }

    private static List<ArchDecision> toDecisions(List<LlmDecision> parsed) {
        List<ArchDecision> decisions = new ArrayList<>();
        if (parsed != null) {
            for (LlmDecision d : parsed) {
                decisions.add(new ArchDecision(UUID.randomUUID(), d.decision, d.rationale, List.of()));
            }
        }
        return decisions;
    }

    private static List<ApiContract> toContracts(List<LlmContract> parsed) {
        List<ApiContract> contracts = new ArrayList<>();
        if (parsed != null) {
            for (LlmContract c : parsed) {
                contracts.add(new ApiContract(UUID.randomUUID(), c.name, c.description, c.signature,
                    resolveTypeName(c), c.members));
            }
        }
        return contracts;
    }

    /**
     * A bare Java type-kind keyword, on its own, with no dot — optionally preceded by modifiers
     * such as "public" or "abstract" (e.g. "class", "abstract class", "@interface"). This is the
     * KIND of a type, never its fully-qualified NAME, and {@link #resolveTypeName} treats it as
     * the architect having answered "type" with the wrong thing.
     */
    private static final Pattern BARE_TYPE_KIND = Pattern.compile(
        "(?i)^(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)\\s+)*"
        + "(?:class|interface|enum|record|@interface|annotation type)$");

    /** The identifier right after a kind keyword in a signature, e.g. "record" in "record Book(". */
    private static final Pattern KIND_THEN_NAME = Pattern.compile(
        "(?i)\\b(?:class|interface|enum|record|@interface)\\s+([A-Za-z_][\\w.]*)");

    /** An explicit {@code package x.y.z;} statement, when a signature happens to state one. */
    private static final Pattern PACKAGE_STATEMENT = Pattern.compile(
        "\\bpackage\\s+([a-zA-Z_][\\w.]*)\\s*;");

    /**
     * The type name to store for a wire contract — {@code c.type} unchanged, UNLESS the model
     * answered it with the KIND of the type rather than its name (harness run 46, 2026-09-27: every
     * contract in that run's design came back {@code "type":"class"}, so all four contracts shared
     * one typeName and every check keyed on it — plan delivery, contract matching — treated them as
     * the same type; {@code TaskGraphValidator} reported each as "claimed by 4 tasks").
     *
     * <p>When that happens, recover a real name from wherever else the reply might carry one: the
     * contract's own {@code name} field, or the identifier following a kind keyword in its
     * {@code signature} (e.g. "public record com.x.Book(...)" or "class Book"). A name that is
     * already qualified (has a dot) is used as-is; a simple name is combined with a package only
     * when the signature states one honestly (an explicit {@code package ...;} statement) — nothing
     * is guessed. When nothing at all can be recovered this returns {@code null}, so
     * {@link ApiContract#namesAType()} is false rather than the contract naming a fake type called
     * "class".
     */
    private static String resolveTypeName(LlmContract c) {
        String type = c.type == null ? null : c.type.strip();
        boolean statedKind = type != null && !type.isEmpty() && BARE_TYPE_KIND.matcher(type).matches();
        if (type != null && !type.isEmpty() && !statedKind) {
            return c.type;
        }
        // No usable "type": look under the other keys a model names it by (harness run 59,
        // 2026-10-01: a design with two contracts, none of which carried a type).
        String other = typeUnderAnotherKey(c);
        if (other != null) {
            return other;
        }
        String candidate = recoveredName(c);
        if (candidate == null || candidate.isEmpty()) {
            return statedKind ? null : c.type;
        }
        if (!statedKind && !looksLikeTypeName(candidate)) {
            // Nothing said this contract is a type at all, so a name such as "add to cart" is not
            // taken for one — only a dotted name or a capitalised identifier is.
            return c.type;
        }
        if (candidate.contains(".")) {
            return candidate;
        }
        String pkg = packageFromSignature(c.signature);
        return pkg.isEmpty() ? candidate : pkg + "." + candidate;
    }

    /**
     * The keys, lower-cased with every non-letter-or-digit removed, under which a model has been
     * seen to put a contract's type name instead of {@code type}.
     */
    private static final Set<String> TYPE_KEYS = Set.of(
        "typename", "fqn", "qualifiedname", "fullyqualifiedname", "fullname", "qualifiedtype",
        "fullyqualifiedtype", "fullyqualifiedclassname", "classname", "class", "interface",
        "interfacename", "javatype", "javaclass", "canonicalname");

    private static final Pattern JAVA_IDENTIFIER_PATH = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");

    /** A type name found under one of {@link #TYPE_KEYS} — an identifier path, not a kind keyword. */
    private static String typeUnderAnotherKey(LlmContract c) {
        for (Map.Entry<String, Object> e : c.extras.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase().replaceAll("[^a-z0-9]", "");
            if (!TYPE_KEYS.contains(key) || !(e.getValue() instanceof String value)) {
                continue;
            }
            String v = value.strip();
            if (JAVA_IDENTIFIER_PATH.matcher(v).matches() && !BARE_TYPE_KIND.matcher(v).matches()) {
                return v;
            }
        }
        return null;
    }

    /**
     * An identifier path whose last segment starts with a capital letter. A dotted name ending in
     * a lower-case segment, such as {@code Calculator.multiply}, is a method of a type, not a type.
     */
    private static boolean looksLikeTypeName(String candidate) {
        if (!JAVA_IDENTIFIER_PATH.matcher(candidate).matches()) {
            return false;
        }
        String last = candidate.substring(candidate.lastIndexOf('.') + 1);
        return Character.isUpperCase(last.charAt(0));
    }

    /** The best name {@code c.name} or {@code c.signature} offers, preferring a qualified one. */
    private static String recoveredName(LlmContract c) {
        String name = c.name == null ? "" : c.name.strip();
        String fromSignature = c.signature == null ? null : kindThenName(c.signature);
        if (name.contains(".")) {
            return name;
        }
        if (fromSignature != null && fromSignature.contains(".")) {
            return fromSignature;
        }
        if (!name.isEmpty()) {
            return name;
        }
        return fromSignature;
    }

    private static String kindThenName(String signature) {
        Matcher m = KIND_THEN_NAME.matcher(signature);
        return m.find() ? m.group(1) : null;
    }

    private static String packageFromSignature(String signature) {
        if (signature == null) {
            return "";
        }
        Matcher m = PACKAGE_STATEMENT.matcher(signature);
        return m.find() ? m.group(1) : "";
    }

    private static List<Risk> toRisks(List<LlmRisk> parsed) {
        List<Risk> risks = new ArrayList<>();
        if (parsed != null) {
            for (LlmRisk r : parsed) {
                risks.add(new Risk(UUID.randomUUID(), r.description,
                    enumOr(Severity.class, r.severity, Severity.MEDIUM), r.mitigation));
            }
        }
        return risks;
    }

    /**
     * The keys, lower-cased with every non-letter-or-digit removed, under which a planner reply
     * may carry its task list or wrap the whole plan.
     */
    private static final Set<String> TASK_LIST_KEYS = Set.of(
        "tasks", "tasklist", "plan", "taskgraph", "graph", "dag", "steps", "nodes", "items",
        "worktasks", "implementationtasks", "result", "output", "response", "answer");

    private static final Set<String> EDGE_LIST_KEYS = Set.of("edges", "dependencies", "deps");

    /**
     * {@code parsed} when it has tasks; otherwise the plan read again, leniently, out of the raw
     * reply — and when that finds none either, the raw reply logged, so "the reply had no tasks"
     * is never again all anybody knows.
     *
     * <p>Live run 67, 2026-10-02: two planner calls in a row failed with "the reply had no
     * tasks", eleven minutes each, and nothing recorded what the replies were. A reply binds to
     * {@link LlmPlan} with no tasks whenever the tasks are there under another shape: a bare
     * array of tasks (the parser takes its first object, which has no {@code tasks} key), the
     * list under another key, or the plan inside a wrapper object. All three are read here.
     */
    private LlmPlan withRecoveredTasks(LlmPlan parsed) {
        if (parsed != null && parsed.tasks != null && !parsed.tasks.isEmpty()) {
            return parsed;
        }
        String raw = lastParsedReply.get();
        LlmPlan recovered = recoverPlan(mapper, raw);
        if (recovered != null) {
            log.info("Architect plan reply did not have its tasks under \"tasks\" at the top; "
                + "read {} task(s) from the shape it did use", recovered.tasks.size());
            return recovered;
        }
        warnRawReply("plan reply had no tasks", raw);
        return parsed == null ? new LlmPlan() : parsed;
    }

    /**
     * A plan found in {@code raw} in a shape {@link LlmPlan} does not bind: a bare array of
     * tasks, a task list under one of {@link #TASK_LIST_KEYS}, a plan one or two wrapper objects
     * down, or one task alone. Null when no task can be found or the tasks do not bind.
     */
    static LlmPlan recoverPlan(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode root = null;
        int brace = raw.indexOf('{');
        int bracket = raw.indexOf('[');
        int start = brace < 0 ? bracket : bracket < 0 ? brace : Math.min(brace, bracket);
        if (start >= 0) {
            // The first JSON value in the reply, whatever follows it: a code fence, prose.
            try (com.fasterxml.jackson.core.JsonParser parser =
                     mapper.getFactory().createParser(raw.substring(start))) {
                root = mapper.readTree(parser);
            } catch (IOException | RuntimeException notAValue) {
                root = null;
            }
        }
        if (root == null) {
            try {
                root = LlmJson.readTree(mapper, raw);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        com.fasterxml.jackson.databind.node.ObjectNode plan = planIn(mapper, root, 0);
        if (plan == null) {
            return null;
        }
        try {
            LlmPlan bound = mapper.readerFor(LlmPlan.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(plan);
            if (bound.tasks == null || bound.tasks.isEmpty()) {
                return null;
            }
            return bound;
        } catch (IOException | RuntimeException e) {
            if (plan.has("edges")) {
                // Edges in a shape of their own must not cost the tasks; the validator and
                // TypeDependencyOrder still hold the plan to its order.
                plan.remove("edges");
                try {
                    LlmPlan bound = mapper.readerFor(LlmPlan.class)
                        .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(plan);
                    return bound.tasks == null || bound.tasks.isEmpty() ? null : bound;
                } catch (IOException | RuntimeException again) {
                    return null;
                }
            }
            return null;
        }
    }

    /** {@code {"tasks":[…],"edges":[…]}} built from wherever {@code node} keeps them, or null. */
    private static com.fasterxml.jackson.databind.node.ObjectNode planIn(
            ObjectMapper mapper, com.fasterxml.jackson.databind.JsonNode node, int depth) {
        if (node == null || depth > 3) {
            return null;
        }
        if (isTaskList(node)) {
            com.fasterxml.jackson.databind.node.ObjectNode plan = mapper.createObjectNode();
            plan.set("tasks", node);
            return plan;
        }
        if (!node.isObject()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode edges = null;
        for (java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it =
                 node.fields(); it.hasNext();) {
            Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e = it.next();
            if (EDGE_LIST_KEYS.contains(normalisedKey(e.getKey())) && e.getValue().isArray()) {
                edges = e.getValue();
            }
        }
        for (java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it =
                 node.fields(); it.hasNext();) {
            Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e = it.next();
            if (!TASK_LIST_KEYS.contains(normalisedKey(e.getKey()))) {
                continue;
            }
            com.fasterxml.jackson.databind.node.ObjectNode plan =
                planIn(mapper, e.getValue(), depth + 1);
            if (plan != null) {
                if (!plan.has("edges") && edges != null) {
                    plan.set("edges", edges);
                }
                return plan;
            }
        }
        if (isTask(node)) {
            com.fasterxml.jackson.databind.node.ObjectNode plan = mapper.createObjectNode();
            plan.putArray("tasks").add(node);
            return plan;
        }
        return null;
    }

    private static String normalisedKey(String key) {
        return key == null ? "" : key.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** A non-empty array whose every element looks like a task. */
    private static boolean isTaskList(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return false;
        }
        for (com.fasterxml.jackson.databind.JsonNode element : node) {
            if (!isTask(element)) {
                return false;
            }
        }
        return true;
    }

    /** An object with a title, and the instructions or the write set a task is made of. */
    private static boolean isTask(com.fasterxml.jackson.databind.JsonNode node) {
        return node != null && node.isObject() && node.path("title").isTextual()
            && (node.path("instructions").isTextual() || node.path("writeSet").isArray());
    }

    private TaskGraph toTaskGraph(DesignDocument design, LlmPlan plan) {
        return toTaskGraph(design, plan, null);
    }

    private TaskGraph toTaskGraph(DesignDocument design, LlmPlan plan, StoryScope scope) {
        if (plan.tasks == null || plan.tasks.isEmpty()) {
            // The reply parsed as valid JSON but never named a task — the one return-null path in
            // this class that used to leave lastFailureReason unset, because it is reached from
            // INSIDE plan()'s own try block rather than through its catch (2026-09-04, harness run
            // 21: this is exactly the "unparseable-but-not-throwing reply" planAttempt's own javadoc
            // already anticipated).
            lastFailureReason.set("the reply had no tasks");
            return null;
        }
        // Map requirement handles (R1, R2, …) back to their UUIDs so tasks trace to requirements.
        Map<String, UUID> reqHandleToId = new HashMap<>();
        if (design != null && design.requirements() != null) {
            List<Requirement> reqs = design.requirements();
            for (int i = 0; i < reqs.size(); i++) {
                reqHandleToId.put(requirementHandle(i), reqs.get(i).id());
            }
        }
        // The test references in play, for telling a real contract apart from a contract that
        // accidentally names the acceptance test class itself (see AcceptanceTestContracts) — the
        // story's own checks when scoped, or every task's raw criteria when not.
        List<String> checkTestRefs = scope != null ? checkTestRefs(scope) : allPlanCriteriaRefs(plan);
        List<Task> tasks = new ArrayList<>();
        Map<String, UUID> idMap = new HashMap<>();
        for (LlmTask t : plan.tasks) {
            UUID taskId = UUID.randomUUID();
            idMap.put(t.id, taskId);
            Set<UUID> requirementIds = new HashSet<>();
            if (t.requirementRefs != null) {
                for (String ref : t.requirementRefs) {
                    UUID rid = ref == null ? null : reqHandleToId.get(ref.trim().toUpperCase());
                    if (rid != null) {
                        requirementIds.add(rid);
                    }
                }
            }
            // In a scoped run the criteria belong to the REQUIREMENT, so the task references them
            // rather than owning copies that would be archived with the run. Task-local criteria
            // remain only for unscoped/enabler work that answers to no requirement.
            Set<UUID> criterionIds = new HashSet<>();
            if (scope != null && t.criterionRefs != null) {
                for (String ref : t.criterionRefs) {
                    UUID id = scope.idForRef(ref);
                    if (id == null) {
                        // A ref outside the slice is the model widening its own scope. Dropping it
                        // keeps the run honest; the validator then reports the uncovered criterion.
                        log.warn("Planner claimed criterion {} outside the story's slice — ignored", ref);
                        continue;
                    }
                    criterionIds.add(id);
                    var owner = scope.requirementOf(id);
                    if (owner != null) {
                        requirementIds.add(owner.id());
                    }
                }
            }
            List<AcceptanceCriterion> criteria = new ArrayList<>();
            if (scope == null && t.criteria != null) {
                for (LlmCriterion c : t.criteria) {
                    criteria.add(new AcceptanceCriterion(UUID.randomUUID(), c.text, c.testClassOrFile));
                }
            }
            // The contracts THIS task creates, resolved from the design so the task carries the
            // real thing rather than a name the planner typed. They go into the instructions
            // verbatim as well, because the worker reads the instructions and nothing else.
            List<ApiContract> delivers = contractsNamed(design, t.deliversContracts, checkTestRefs);
            String instructions = withContractBrief(t.instructions, delivers);
            Task task = new Task(taskId, 1, t.title, instructions,
                t.writeSet == null ? new HashSet<>() : new HashSet<>(t.writeSet),
                t.readSet == null ? new HashSet<>() : new HashSet<>(t.readSet),
                criteria, ACCEPTANCE_TEST_DIR, null, null,
                taskPolicy,
                TaskState.PENDING, requirementIds);
            task.setDeliveredContracts(delivers);
            if (scope != null) {
                task.setCriterionIds(criterionIds);
                task.setStoryId(scope.story() == null ? null : scope.story().id());
            }
            tasks.add(task);
        }
        List<TaskEdge> edges = new ArrayList<>();
        if (plan.edges != null) {
            for (LlmEdge e : plan.edges) {
                UUID from = idMap.get(e.from);
                UUID to = idMap.get(e.to);
                if (from != null && to != null) {
                    edges.add(new TaskEdge(from, to));
                }
            }
        }
        return new TaskGraph(UUID.randomUUID(), 1, design == null ? null : design.id(), tasks, edges);
    }

    /**
     * The design contracts a planned task named, matched by contract name and — because a model
     * that was asked for a name sometimes gives the type — by type name and simple type name too.
     * A name that matches nothing is dropped and reported by the validator, never invented here.
     *
     * <p>A contract that names the acceptance test class itself is never resolved here either
     * (see {@link AcceptanceTestContracts}): the design is normalised to drop these at intake, so
     * this ordinarily filters nothing, but a task's {@code deliversContracts} must never hand
     * {@code ContractDelivery} a test file to verify a candidate against, however the design that
     * still names one reached this call.
     */
    private static List<ApiContract> contractsNamed(DesignDocument design, List<String> named,
                                                     List<String> checkTestRefs) {
        if (design == null || design.contracts() == null || named == null || named.isEmpty()) {
            return List.of();
        }
        List<ApiContract> found = new ArrayList<>();
        for (String name : named) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String wanted = name.strip();
            for (ApiContract contract : design.contracts()) {
                if (contract == null || found.contains(contract)
                        || AcceptanceTestContracts.isAcceptanceTestClass(contract, checkTestRefs)) {
                    continue;
                }
                if (wanted.equalsIgnoreCase(contract.name())
                        || wanted.equalsIgnoreCase(contract.typeName())
                        || wanted.equalsIgnoreCase(contract.simpleTypeName())) {
                    found.add(contract);
                    break;
                }
            }
        }
        return List.copyOf(found);
    }

    /** Every raw {@code testClassOrFile} an unscoped plan's tasks named, across the whole plan. */
    private static List<String> allPlanCriteriaRefs(LlmPlan plan) {
        List<String> refs = new ArrayList<>();
        if (plan != null && plan.tasks != null) {
            for (LlmTask t : plan.tasks) {
                if (t.criteria == null) {
                    continue;
                }
                for (LlmCriterion c : t.criteria) {
                    if (c != null) {
                        refs.add(c.testClassOrFile);
                    }
                }
            }
        }
        return refs;
    }

    /**
     * The task's own instructions with the contracts it must deliver spelled out at the end,
     * fully-qualified and member by member.
     *
     * <p>The worker never sees the design. It sees the instructions, and on run 13 those said
     * "implement the Book data model and rating service interface" — which is how one wave
     * delivered a rating field on Book while the acceptance test of a later wave was written
     * against a separate Rating type. The names are not a detail of the work; they ARE the work,
     * because a later task's tests are already written against them.
     */
    private static String withContractBrief(String instructions, List<ApiContract> delivers) {
        if (delivers.isEmpty()) {
            return instructions;
        }
        StringBuilder sb = new StringBuilder(instructions == null ? "" : instructions);
        sb.append("\n\nYOU MUST DELIVER THESE TYPES EXACTLY AS WRITTEN — same package, same type "
            + "name, same members. The acceptance tests of a later task are already written "
            + "against them, so a renamed or missing one cannot be corrected later:\n");
        for (ApiContract contract : delivers) {
            sb.append("  - ").append(contract.describe());
            if (contract.description() != null && !contract.description().isBlank()) {
                sb.append(" — ").append(contract.description().strip());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String designSummary(DesignDocument design) {
        StringBuilder sb = new StringBuilder();
        // requirements()/decisions()/contracts()/risks() are all null-safe accessors on
        // DesignDocument (2026-09-04) — this used to null-guard only requirements before iterating
        // the other three lists directly, which is how a design whose contracts/decisions/risks
        // happened to be unset either lost lines silently or threw synchronously, before any model
        // call, from inside plan()'s try block. See DesignDocument#contracts() for the full story.
        List<Requirement> requirements = design.requirements();
        for (int i = 0; i < requirements.size(); i++) {
            Requirement r = requirements.get(i);
            sb.append("REQ ").append(requirementHandle(i)).append(" [").append(r.priority())
                .append("] ").append(r.text()).append('\n');
        }
        for (ArchDecision d : design.decisions()) {
            sb.append("DECISION ").append(d.decision()).append(" — ").append(d.rationale()).append('\n');
        }
        for (ApiContract c : design.contracts()) {
            sb.append("CONTRACT ").append(c.name()).append(": ").append(c.signatureSketch());
            if (c.namesAType()) {
                // The type and its members on the same line as the name, because this string is
                // the only place the test author, the planner and the reviewer all read the
                // vocabulary from. A contract whose type lives only in the prose is a contract
                // three roles will each spell differently — which is what run 13 cost.
                sb.append("  [type ").append(c.typeName().strip());
                if (!c.members().isEmpty()) {
                    sb.append(" with ").append(String.join("; ", c.members()));
                }
                sb.append(']');
            }
            sb.append('\n');
        }
        for (Risk r : design.risks()) {
            sb.append("RISK [").append(r.severity()).append("] ").append(r.description()).append('\n');
        }
        // What the architect kept for the workers (section 73), one line each and without the
        // code: the planner and the reviewer read what was established and about what; the
        // lines themselves travel with the tasks.
        for (DesignFinding f : design.findings()) {
            sb.append("FACT [").append(f.wholeProject() ? "the whole project" : f.about().strip())
                .append("] ").append(f.note() == null ? "" : f.note().strip());
            if (f.source() != null && !f.source().isBlank()) {
                sb.append(" (from ").append(f.source().strip()).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Stable, LLM-friendly handle for the requirement at {@code index}: R1, R2, … */
    static String requirementHandle(int index) {
        return "R" + (index + 1);
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
