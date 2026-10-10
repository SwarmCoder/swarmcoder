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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.ArchDecision;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Task;
import com.swarmcoder.inference.EndpointOutage;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DESIGN_REVIEW (spec §14): a de-correlated reviewer critiques the design against a fixed
 * rubric — completeness, testability, write-set partitionability. At most two loops, then
 * the run proceeds with recorded objections (the Momus pattern with a convergence bound).
 *
 * <p>Also does the PLAN-stage rules review, {@link #reviewPlan} (addendum, 2026-09-03): the same
 * reviewer role, asked a different question — not "is the design good" but "does this plan's own
 * work contradict what this project must be built of."
 */
public class DesignReviewerClient {

    private static final Logger log = LoggerFactory.getLogger(DesignReviewerClient.class);

    public static class Review {
        public boolean approved;
        public List<String> objections;
    }

    /**
     * What the rubric reviewer is told when the project has stated rules (harness run 39,
     * 2026-09-25 — see {@link #review(DesignDocument, String)}). A goal says what the user gets;
     * where its wording also names HOW (a storage mechanism, a library, a place data is kept) and
     * the design builds it the way the rules require instead, the design is right and the wording
     * is wrong. Deliberately never contains the phrase the design-versus-rules prompt is told
     * apart by in tests and scripted models ("checking a DESIGN against this project's standing
     * rules").
     */
    /**
     * The rules are shown to the rubric reviewer for one reason, and it is not to be checked
     * against them (live run 75, 2026-10-03 - see {@link RubricObjections}, which sets aside what
     * still gets through).
     */
    static final String RULES_ARE_NOT_THE_RUBRIC =
        "The rules are shown to you ONLY for that. Whether the design keeps them is checked "
        + "separately, after you: never object that the design lacks, omits or fails to deliver "
        + "something a rule describes, and never ask the design to add anything for a rule's "
        + "sake. Completeness is measured against the goal alone. ";

    /**
     * What makes a rule this design's or this plan's business (live run 75): its own change. The
     * project's rules describe the whole project, and most of them govern something a given story
     * never touches.
     */
    static final String ONLY_WHAT_THE_CHANGE_TOUCHES =
        "A rule is in question ONLY where what is being added or changed here falls under it. A "
        + "rule about something this work does not touch asks nothing of it: never object that "
        + "the work fails to add, extend or bring up to date something a rule describes when the "
        + "goal does not ask for that thing. Code that existed before this work and is not "
        + "changed by it is not this work's violation, whatever a rule says about it. ";

    static final String RULES_OUTRANK_THE_GOAL =
        "The project's standing rules come first below, before the goal. THEY OUTRANK THE GOAL'S "
        + "WORDING ABOUT HOW ANYTHING IS BUILT. The goal says what the user gets; where it also "
        + "names a technology, library, storage mechanism or place data is kept that a rule "
        + "forbids or replaces, the design is right to follow the rule instead. Judge completeness "
        + "by whether the design delivers what the user gets, never by whether it uses the "
        + "mechanism the goal happened to name. NEVER object to a design for following a rule, and "
        + "never ask it for anything a rule forbids. " + RULES_ARE_NOT_THE_RUBRIC;

    private final VllmClient client;
    private final CloudGate cloudGate;
    private final ObjectMapper mapper = new ObjectMapper();

    /** The session the readings against the rules run in; null is the one reply they always were. */
    private volatile LookupAgent lookupAgent;

    public DesignReviewerClient(VllmClient client, CloudGate cloudGate) {
        this.client = client;
        this.cloudGate = cloudGate;
    }

    /**
     * Makes the design, plan and tests readings against the project's rules run as a lookup
     * session with the same read-only tools the other roles have, handing in the same verdict.
     * A session that fails falls back to the one reply, said in the log. Null (the default)
     * leaves every reading as it was.
     */
    public void setLookupAgent(LookupAgent agent) {
        this.lookupAgent = agent;
    }

    /**
     * Never blocks the run: a failed review counts as approved-with-note (recorded upstream).
     *
     * <p>Also never SENDS the model a blank rendering and lets it tell us so. Run 17 (2026-09-04)
     * showed a reviewer confidently objecting "The design section is empty. No requirements,
     * architecture, or implementation details are provided" against a design the DESIGN stage had
     * just logged as carrying real requirements and contracts — {@link ArchitectClient#designSummary}
     * had a rendering gap (see its own javadoc) that could hand the reviewer nothing to critique.
     * That objection was a correct answer to a broken question: an empty design section is a bug in
     * THIS side of the call, not a verdict on the design, and {@link #designRenderedEmpty} catches it
     * before the model ever sees it.
     */
    public Review review(DesignDocument design) {
        return review(design, null);
    }

    /**
     * The rubric review, told the project's standing rules and that they outrank the story goal's
     * wording about HOW something is built.
     *
     * <p>Harness run 39, 2026-09-25 (and runs 37 and 38 before it). The story goal read "Add a
     * persistence layer that saves the current books and their ratings to localStorage…"; the
     * project's technical document mandates persistence through EclipseStore on the server. The
     * architect, who is told the rules, rightly designed server-side EclipseStore — and this
     * review, which was told the goal and the design and NOT the rules, objected under
     * "completeness" that "the design replaces the explicit localStorage persistence with
     * server-side EclipseStore, deviating from the story's requirement". That objection went back
     * to the architect as something to fix: a revision that obeyed it would break a rule the
     * design-versus-rules check right after it would then send back again, and a revision that
     * ignored it cost a model call for nothing. The story planner is now told the rules too, and
     * told a story says what, never how — but a story written before that, or by a person, can
     * still name a technology, and the reviewer has to know which one wins.
     *
     * <p>When {@code rulesBrief} is blank the prompt is byte-for-byte what it was: a project with
     * no stated rules has nothing that outranks the goal.
     *
     * @param rulesBrief the project's stated rules, rendered exactly as the architect was shown
     *                   them ({@code StoryScope.constraintBrief()}); null or blank for none
     */
    public Review review(DesignDocument design, String rulesBrief) {
        return review(design, rulesBrief, null);
    }

    /**
     * As above, and told a fact about the build the architect designed from - which modules run
     * only in a browser ({@link AcceptanceTestReach#reviewerBrief}) - so that "testability" is
     * judged against what a test of this project can execute. When {@code buildFact} is blank
     * the prompt is byte-for-byte the two-argument one.
     *
     * @param buildFact a paragraph of facts read from the build; null or blank for none
     */
    public Review review(DesignDocument design, String rulesBrief, String buildFact) {
        boolean hasRules = rulesBrief != null && !rulesBrief.isBlank();
        boolean hasFact = buildFact != null && !buildFact.isBlank();
        String designText;
        try {
            designText = ArchitectClient.designSummary(design);
        } catch (Exception e) {
            log.warn("Design summary could not be rendered ({}); skipping the design reviewer call "
                + "rather than reviewing nothing", e.toString());
            return unavailable("design review skipped: design summary failed to render ("
                + e + ")");
        }
        if (designRenderedEmpty(design, designText)) {
            log.warn("Design summary rendered no text for a design with {} requirement(s) and {} "
                + "contract(s); skipping the design reviewer call rather than reviewing nothing",
                design.requirements().size(), design.contracts().size());
            return unavailable("design review skipped: the design summary rendered no text even "
                + "though the design has requirements or contracts — this is a rendering bug, not "
                + "a verdict on the design");
        }
        try {
            String system = "You are a design reviewer. Critique the design against this rubric: "
                + "(1) completeness — do the requirements cover the goal; "
                + "(2) testability — can every requirement be verified by an executable test; "
                + "(3) write-set partitionability — can implementation be split into tasks with "
                + "disjoint file ownership. "
                + (hasRules ? RULES_OUTRANK_THE_GOAL : "")
                + "Respond ONLY with JSON: "
                + "{\"approved\": true|false, \"objections\": [\"...\"]}";
            String user = (hasRules ? rulesBrief.strip() + "\n\n" : "")
                + (hasFact ? buildFact.strip() + "\n\n" : "")
                + "Goal: " + design.goal() + "\n\nDesign:\n" + designText;
            return callReviewer("design review", system, user);
        } catch (EndpointOutage outage) {
            // This role fails soft on purpose — an unavailable reviewer must never block a run. But
            // "unavailable" has to mean the reviewer refused or answered unusably. When the endpoint
            // itself is down, the very next stage needs that same endpoint, so recording
            // "approved, review unavailable" would launder an outage into a design verdict.
            throw outage;
        } catch (Exception e) {
            log.warn("Design review failed ({}); proceeding without review", e.toString());
            return unavailable("review unavailable: " + e);
        }
    }

    /**
     * True when {@code designText} has nothing in it despite the design actually having something to
     * say — the signal that the rendering broke rather than that the design is genuinely thin.
     * Deliberately narrow (requirements or contracts only, not decisions or risks): those two are
     * exactly what the rubric asks the reviewer to judge, and both were guaranteed non-empty going
     * into DESIGN's own log line, so their presence here is a fact, not a guess.
     */
    static boolean designRenderedEmpty(DesignDocument design, String designText) {
        boolean hasContent = !design.requirements().isEmpty() || !design.contracts().isEmpty();
        return hasContent && (designText == null || designText.isBlank());
    }

    /**
     * PLAN, once the shape validator ({@link TaskGraphValidator}) has already accepted the graph:
     * does the plan the architect actually wrote ask for anything the project's stated rules
     * forbid? {@link #review} critiques the DESIGN against a fixed rubric and never sees a single
     * task; the shape validator checks the graph's structure — cycles, write-set ownership,
     * coverage — and never reads a word of what a task's instructions actually say. Neither one
     * would ever have caught harness run 10 (2026-09-03): a design that never mentioned browser
     * storage, decomposed into a structurally sound two-wave plan whose second wave instructed a
     * worker to "implement client-side localStorage persistence for books and ratings" against
     * rules that require EclipseStore and forbid REST/JSON. This is the one place in the pipeline
     * that reads a plan's own words against the rules before a wave is ever dispatched at it.
     *
     * <p>Never blocks the run on its OWN failure, for the reason {@link #review} does not either:
     * an unavailable or twice-malformed reviewer must not be the thing that stops PLAN. A failure
     * here comes back {@code approved=true} with a note in {@code objections} — the caller's job
     * is to tell that note apart from a real objection (it always starts "plan review
     * unavailable:") and log it without treating it as grounds to reject the plan. An outage is
     * different again: it propagates, exactly as {@link #review} propagates one, so the run pauses
     * and retries rather than spending an attempt on an endpoint that was never reached.
     *
     * @param rulesBrief the project's stated rules, rendered exactly as every worker reads them
     *                   ({@link com.swarmcoder.domain.ConstraintBrief} — the same string
     *                   {@code StoryScope.constraintBrief()} already hands the architect and every
     *                   worker, so this reviewer can never be told a different set of rules than
     *                   the plan itself was written against); blank calls no model and returns no
     *                   objections, because nothing was stated to check the plan against
     * @param tasks      the candidate plan's own tasks — title and instructions are all a rule
     *                   could ever conflict with; write sets and criteria are the shape
     *                   validator's business, not this reviewer's
     */
    public Review reviewPlan(String rulesBrief, List<Task> tasks) {
        if (rulesBrief == null || rulesBrief.isBlank() || tasks == null || tasks.isEmpty()) {
            return unavailable(null);
        }
        try {
            String system = "You are a design reviewer checking a PLAN against this project's "
                + "standing rules. Below the rules is a list of tasks; each will be built by a "
                + "separate worker who never sees the others or this review. Object ONLY when a "
                + "task's own instructions clearly call for doing something a rule forbids or "
                + "contradicts — never merely because a task does not mention a rule, and never "
                + "for a task that is restating or obeying a rule rather than breaking it. This "
                + "includes a task whose instructions carve out an exception or loophole that "
                + "relaxes a rule instead of obeying it outright — for example \"in-memory "
                + "storage is fine if the database isn't set up yet\" against a rule that "
                + "mandates a specific persistence store. A rule's own instructions are not "
                + "negotiable per task, so a loophole like that is a conflict with the rule, not "
                + "an exception to it, and must be objected to exactly as a flat contradiction "
                + "would be. " + ONLY_WHAT_THE_CHANGE_TOUCHES
                + "Respond ONLY with JSON: {\"approved\": true|false, \"objections\": "
                + "[\"task '<task title>' conflicts with rule '<rule title>': <one sentence>\"]}";
            String user = rulesBrief + "\n\nThe plan's tasks:\n" + tasksSummary(tasks);
            return callReviewer("plan-versus-rules review", system, user, true);
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (Exception e) {
            log.warn("Plan-versus-rules review failed ({}); proceeding without it", e.toString());
            return unavailable("plan review unavailable: " + e);
        }
    }

    /**
     * DESIGN_REVIEW, addendum (2026-09-04): does the design's OWN decisions and contracts ask for
     * anything the project's stated rules forbid? {@link #review} critiques completeness,
     * testability and write-set partitionability against a fixed rubric and never reads a rule; a
     * design can pass that rubric cleanly and still decide, in so many words, to keep state in the
     * browser against a rule that mandates a server-side object graph — which is exactly what sent
     * harness run 19 in a circle: DESIGN_REVIEW recorded the objection and moved on, so the plan
     * built from that design kept the same decision, and PLAN's own rules check
     * ({@link #reviewPlan}) rejected three planner attempts in a row for a design defect none of
     * them could fix from where they were standing.
     *
     * <p>Deliberately the same objection shape {@link #reviewPlan} uses —
     * {@code "... conflicts with rule '<rule title>': ..."} — so both feed the one detection
     * {@code RuleConflictFeedback} reads regardless of which one raised the objection. Never
     * blocks the run on its own failure, for the same reason {@link #reviewPlan} does not either:
     * see its javadoc for what {@code approved=true} with a note in {@code objections} means, and
     * how an outage differs from a refusal.
     *
     * @param rulesBrief the project's stated rules, rendered exactly as the architect that wrote
     *                   this design was shown them; blank calls no model and returns no objections
     * @param design     the design under review — only its own decisions and contracts are read
     *                   here; the requirements are the BRD's and are never in question
     */
    public Review reviewDesign(String rulesBrief, DesignDocument design) {
        if (rulesBrief == null || rulesBrief.isBlank() || design == null) {
            return unavailable(null);
        }
        String content = designContent(design);
        if (content.isBlank()) {
            return unavailable(null);
        }
        try {
            String system = "You are a design reviewer checking a DESIGN against this project's "
                + "standing rules. Below the rules is the design's own decisions and contracts. "
                + "Object ONLY when the design's own wording clearly calls for doing something a "
                + "rule forbids or contradicts — never merely because the design does not mention "
                + "a rule, and never for wording that is restating or obeying a rule rather than "
                + "breaking it. This includes a decision that carves out an exception or loophole "
                + "that relaxes a rule instead of obeying it outright — for example \"keep this in "
                + "browser storage until the server side is wired up\" against a rule that mandates "
                + "server-side persistence. A rule's own instructions are not negotiable, so a "
                + "loophole like that is a conflict with the rule, not an exception to it, and must "
                + "be objected to exactly as a flat contradiction would be. "
                + ONLY_WHAT_THE_CHANGE_TOUCHES + "Respond ONLY with JSON: "
                + "{\"approved\": true|false, \"objections\": [\"decision '<the decision or "
                + "contract, in a few words>' conflicts with rule '<rule title>': <one sentence>\"]}";
            String user = rulesBrief + "\n\nThe design's own decisions and contracts:\n" + content;
            return callReviewer("design-versus-rules review", system, user, true);
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (Exception e) {
            log.warn("Design-versus-rules review failed ({}); proceeding without it", e.toString());
            return unavailable("design review unavailable: " + e);
        }
    }

    /**
     * TEST_AUTHORING, addendum (live harness runs 56 and 58, 2026-10-01): does an acceptance
     * test's OWN code do something the project's stated rules forbid? The test author is handed
     * the rules and told to follow them, and nothing ever read what it wrote against them: a
     * project whose rules said a service is never constructed by hand in a test, because its
     * database is injected, got a test that constructed it by hand. The red-check passed (the
     * class did not exist yet), and both workers then spent 80 minutes failing to make a
     * hand-constructed service find a database nothing would ever inject.
     *
     * <p>The same objection shape {@link #reviewPlan} and {@link #reviewDesign} use —
     * {@code "... conflicts with rule '<rule title>': ..."} — so {@code RuleConflictFeedback}
     * reads all three alike. Fails soft exactly as they do.
     *
     * @param rulesBrief the project's stated rules, rendered exactly as the test author was shown
     *                   them; blank calls no model and returns no objections
     * @param testFiles  repo-relative path to source, as written; empty calls no model
     */
    public Review reviewTests(String rulesBrief, Map<String, String> testFiles) {
        if (rulesBrief == null || rulesBrief.isBlank() || testFiles == null || testFiles.isEmpty()) {
            return unavailable(null);
        }
        try {
            String system = "You are a reviewer checking ACCEPTANCE TESTS against this project's "
                + "standing rules. Below the rules are the test files, exactly as written. The "
                + "code these tests exercise is not written yet, so a class that does not exist "
                + "is never an objection. Object ONLY when a line of the test's own code clearly "
                + "does something a rule forbids — above all a rule about how tests are written, "
                + "how a test obtains or starts the code it exercises, or what a test may use. "
                + "Never object because a test does not mention a rule, never for a rule about "
                + "production code that this test's own lines do not break, and never for a test "
                + "that follows a rule. The directory, file name and package of an acceptance test "
                + "are fixed by the build system, not chosen by the test: never object to a "
                + "test's package statement or to where its file is. Quote the offending line "
                + "exactly as it is written. Respond "
                + "ONLY with JSON: {\"approved\": true|false, \"objections\": [\"line '<the "
                + "offending line, copied>' in <file path> conflicts with rule '<rule title>': "
                + "<one sentence>\"]}";
            StringBuilder user = new StringBuilder(rulesBrief).append("\n\nThe test file(s):\n");
            for (Map.Entry<String, String> file : testFiles.entrySet()) {
                user.append("\n--- ").append(file.getKey()).append(" ---\n")
                    .append(file.getValue()).append('\n');
            }
            return callReviewer("tests-versus-rules review", system, user.toString(), true);
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (Exception e) {
            log.warn("Tests-versus-rules review failed ({}); proceeding without it", e.toString());
            return unavailable("test review unavailable: " + e);
        }
    }

    /** The design's own decisions and contracts, rendered plainly — the only two nobody plans and
     *  a rule could still conflict with; the requirements are the BRD's and are never in question. */
    private static String designContent(DesignDocument design) {
        StringBuilder sb = new StringBuilder();
        for (ArchDecision d : design.decisions()) {
            String decision = d.decision() == null ? "" : d.decision();
            if (decision.isBlank()) {
                continue;
            }
            sb.append("- ").append(decision);
            if (d.rationale() != null && !d.rationale().isBlank()) {
                sb.append(" — ").append(d.rationale().strip());
            }
            sb.append('\n');
        }
        for (ApiContract c : design.contracts()) {
            sb.append("- CONTRACT ").append(c.name()).append(": ").append(c.signatureSketch());
            if (c.namesAType()) {
                sb.append("  [type ").append(c.typeName().strip());
                if (!c.members().isEmpty()) {
                    sb.append(" with ").append(String.join("; ", c.members()));
                }
                sb.append(']');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String tasksSummary(List<Task> tasks) {
        StringBuilder sb = new StringBuilder();
        for (Task task : tasks) {
            String title = task.title() == null ? "" : task.title();
            String instructions = task.instructions() == null ? "" : task.instructions();
            sb.append("- ").append(title).append('\n');
            if (!instructions.isBlank()) {
                sb.append("  ").append(instructions.replace("\n", "\n  ")).append('\n');
            }
        }
        return sb.toString();
    }

    /** approved-with-note: never a real objection, always distinguishable by its own wording. */
    private static Review unavailable(String note) {
        Review fallback = new Review();
        fallback.approved = true;
        fallback.objections = note == null ? List.of() : List.of(note);
        return fallback;
    }

    /**
     * One reviewer round trip: charge, call, parse — with one retry, the parser's own complaint
     * fed back, when the first reply does not parse (mirrors {@code ArchitectClient#callForJson}).
     * A second parse failure propagates as an {@link IOException}; every call site above catches it
     * and fails soft.
     */
    private Review callReviewer(String roleLabel, String system, String user) throws Exception {
        return callReviewer(roleLabel, system, user, false);
    }

    /**
     * @param withLookups true for the readings against the project's rules (design, plan, tests):
     *                    they run as a lookup session when one is configured, so a reading that
     *                    depends on a fact - does this type exist, how does this library do it,
     *                    what does the rule mean here - is looked up rather than guessed. The
     *                    one-shot answer is what a session that fails falls back to, said in the
     *                    log. The rubric review of the design is not one of them.
     */
    private Review callReviewer(String roleLabel, String system, String user,
                                boolean withLookups) throws Exception {
        LookupAgent agent = lookupAgent;
        if (withLookups && agent != null) {
            try {
                Review viaSession = reviewInSession(agent, roleLabel, system, user);
                if (viaSession != null) {
                    return viaSession;
                }
            } catch (EndpointOutage outage) {
                throw outage;
            } catch (RuntimeException e) {
                log.warn("The reviewer's lookup session for the {} failed ({}); falling back to "
                    + "the one-shot review without tools", roleLabel, e.toString());
            }
        }
        return oneShotReview(roleLabel, system, user);
    }

    /** The verdict of a lookup session, or null when the session gave none. */
    private Review reviewInSession(LookupAgent agent, String roleLabel, String system,
                                   String user) {
        ReviewerTools[] own = new ReviewerTools[1];
        LookupAgent.Outcome outcome = agent.run(client, new LookupAgent.Ask("reviewer",
            sessionSystem(system), user, LookupAgent.Limits.configured(), SESSION_FINISH_ADVICE,
            List.of(), session -> {
                own[0] = new ReviewerTools(session);
                return own[0].bindings();
            }));
        if (own[0] != null && own[0].handedIn()) {
            Review review = own[0].review();
            log.info("The reviewer handed in its {} after {} turn(s) and {} tool call(s): {}",
                roleLabel, outcome.turns(), outcome.toolsUsed().size(),
                review.approved ? "approved" : review.objections.size() + " objection(s)");
            return review;
        }
        if (outcome.submitted() && outcome.finalText().contains("\"approved\"")) {
            // It answered with the JSON object as text instead of using report_done: still a verdict.
            try {
                return parse(outcome.finalText());
            } catch (IOException notJson) {
                log.warn("The reviewer's {} ended on text that is not the verdict ({})", roleLabel,
                    notJson.getMessage());
            }
        }
        log.warn("The reviewer's lookup session for the {} gave no verdict ({} after {} turn(s), "
            + "{} tool call(s)); falling back to the one-shot review without tools", roleLabel,
            outcome.stopped().map(Enum::name).orElse("ended without report_done"), outcome.turns(),
            outcome.toolsUsed().size());
        return null;
    }

    /** The review's own instructions, and what a session adds to them. */
    static String sessionSystem(String system) {
        return system + " YOU WORK IN STEPS WITH READ-ONLY LOOKUP TOOLS over this project and its "
            + "reference material. Before you object on a reading that depends on a fact - does "
            + "a type exist, how does this library do the thing, what does a rule mean here - look "
            + "the fact up; never object on a guess, and never approve on one. Give your verdict "
            + "with report_done: `approved`, and `objections` one per line in exactly the wording "
            + "described above. The JSON object described above is the shape of that verdict; "
            + "give it through report_done, not as text.";
    }

    private static final String SESSION_FINISH_ADVICE = "Stop looking things up and hand in your "
        + "verdict with report_done: approved, and one objection per line (none when approved).";

    private Review oneShotReview(String roleLabel, String system, String user) throws Exception {
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", system),
            Map.of("role", "user", "content", user));
        String response = call(messages);
        try {
            return parse(response);
        } catch (IOException parseFailure) {
            log.warn("{} reply was not valid JSON ({}); asking it to reply with only the JSON "
                + "object", roleLabel, parseFailure.getMessage());
            String retryAsk = "That was not valid JSON: " + parseFailure.getMessage()
                + ". Reply with only the JSON object.";
            String retryResponse = call(List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user),
                Map.of("role", "assistant", "content", response),
                Map.of("role", "user", "content", retryAsk)));
            return parse(retryResponse);
        }
    }

    private String call(List<Map<String, String>> messages) throws Exception {
        long prompt = 0;
        for (Map<String, String> message : messages) {
            prompt += CloudGate.estimateTokens(message.get("content"));
        }
        cloudGate.charge(prompt);
        String response;
        try {
            response = client.as("reviewer").chatCompletionStream(messages, Review.class, 0.2)
                .collect(Collectors.joining());
        } catch (Exception e) {
            EndpointOutage outage = EndpointOutage.from(client.baseUrl(), e);
            if (outage == null) {
                throw e;
            }
            cloudGate.refund(prompt);
            throw outage;
        }
        cloudGate.chargeOutput(CloudGate.estimateTokens(response));
        return response;
    }

    private Review parse(String response) throws IOException {
        Review review = LlmJson.parse(mapper, response, Review.class);
        if (review.objections == null) {
            review.objections = List.of();
        }
        return review;
    }
}
