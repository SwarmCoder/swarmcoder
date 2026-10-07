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
package com.swarmcoder.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One bug report or enhancement request becomes one agreed requirement, one story and one run.
 *
 * <p><b>The front half a change request never had.</b> There were two ways into SwarmCoder and
 * neither fits an issue. {@link RequirementsIntake} wants documents nobody writes for a bug report,
 * and {@link AdHocStory} produces a story with <b>no criteria at all</b> — which works, and turns
 * off everything that makes a delivery mean something: the plan's coverage invariant has nothing to
 * cover, criterion evidence has nothing to answer, the rule that an acceptance stage executing zero
 * tests is a failure never fires, and delivery stamps no check. On a product whose whole claim is
 * "we can tell you whether it worked", an ungated path is the wrong path.
 *
 * <p>So: <b>one requirement, agreed, carrying one to three checks, each naming the test that will
 * prove it</b> (design §1.2, decision 1a).
 *
 * <h2>One model call, and never a question</h2>
 *
 * <p>{@link RequirementsIntake} asks its clarifications once, as a batch, and argues the case for
 * one round rather than an interrogation. For a change request even one round is wrong: the issue
 * text is fixed, and the person who wrote it is not in the room. Anything the analyst cannot settle
 * becomes a <b>stated assumption on the requirement</b> — visible on the screen, correctable in one
 * edit, and the same mechanism {@code RequirementsIntake} already uses for the same situation
 * (design §9, decision 4a). It also keeps the harness unattended, which a question round does not.
 *
 * <p>The only second call this ever makes is {@link LlmReplyRetry}'s: a reply that was not JSON is
 * handed back with the parser's complaint. That is the same call asked again, not a new question.
 *
 * <h2>Nothing is written until somebody says go</h2>
 *
 * <p>{@link #read} makes the model call and writes nothing at all — no requirement, no check, no
 * story, no run. {@link #start} writes all four, in the order that works. That split is the review
 * seam: the console shows what {@code read} understood, the operator presses one button, and
 * {@code start} runs. In a harness the same two calls run back to back.
 *
 * <h2>The order in {@link #start}, and why it is not negotiable</h2>
 *
 * <p>Requirement → checks → agree → story → <b>run bound to the story from the instant the engine
 * gets it</b>. {@link AdHocStory#start} documents at length what happens otherwise: the engine
 * advances the run on its own thread the moment it is handed over and rebuilds it from its own copy
 * at every transition, so a story attached a line later is written to an object nobody reads again.
 * It was never a race that sometimes lost.
 *
 * <h2>The fork point is {@code RunState.INTAKE}</h2>
 *
 * <p>The run this starts is an ordinary run of kind {@code BUGFIX} or {@code ENHANCEMENT}, created
 * at {@code RunState.INTAKE} by whatever wired {@code ConsoleContext.startRun} — and
 * {@code GreenfieldWorkflow}'s {@code INTAKE} case does one thing: move to {@code DESIGN}. So every
 * state from {@code DESIGN} onward is shared byte-for-byte with a greenfield run, and there is no
 * brownfield workflow class. Corrections §22 already paid for the alternative once, with four
 * workflow classes that renamed a state and reported delivery having built nothing.
 */
public final class ChangeRequestIntake {

    private static final Logger log = LoggerFactory.getLogger(ChangeRequestIntake.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Design §1.2: one to three. Below one nothing is provable; above three it is a project. */
    public static final int MIN_CHECKS = 1;
    public static final int MAX_CHECKS = 3;

    /** How much of a pasted issue reaches the prompt. A 100 KB paste is not a bug report. */
    static final int MAX_ISSUE_CHARS = 20_000;

    /** Where every acceptance test the swarm writes lives — protected from the workers themselves. */
    static final String ACCEPTANCE_PACKAGE = "swarm.accept";

    private ChangeRequestIntake() {}

    /**
     * What the operator typed. Design §1.2: a title, the issue verbatim, and which of the two
     * shapes of work it is. Nothing else — this is not a document upload and not an interview.
     *
     * @param kind {@code BUGFIX} or {@code ENHANCEMENT}; anything else is refused
     */
    public record Request(String title, String issueText, String kind) {

        public Request {
            title = title == null ? "" : title.strip();
            issueText = issueText == null ? "" : issueText.strip();
            kind = kind == null ? "" : kind.strip().toUpperCase(java.util.Locale.ROOT);
        }

        /** The title and the body together — what the neighbourhood and the analyst both read. */
        public String wholeRequest() {
            return title.isBlank() ? issueText : title + "\n\n" + issueText;
        }

        /** Why this cannot be read, in the operator's words, or null when it can. */
        public String rejection() {
            if (title.isBlank()) {
                return "Give the change a short name, so it has something to be called on the board.";
            }
            if (issueText.isBlank()) {
                return "Paste the bug report, or say what needs to change. That is what we work "
                    + "from — there is nothing else.";
            }
            if (!"BUGFIX".equals(kind) && !"ENHANCEMENT".equals(kind)) {
                return "Say whether this is something broken that should be fixed, or something "
                    + "that works and should do more.";
            }
            return null;
        }
    }

    /** One check: what must be observably true, and the test that will show it. */
    public record Check(String text, String test) {}

    /**
     * What the analyst understood, before anything is written down.
     *
     * @param statement  the observable difference, in the shape "X happens; Y should happen"
     * @param assumptions what it had to decide that the issue did not settle — never presented as
     *                   fact, always shown to the operator
     */
    public record Understanding(String title, String statement, String priority,
                                List<Check> checks, List<String> assumptions) {

        public Understanding {
            checks = checks == null ? List.of() : List.copyOf(checks);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        }

        /** Why this understanding cannot be acted on, in plain words, or null when it can. */
        public String rejection() {
            if (statement == null || statement.isBlank()) {
                return "We could not turn that into a sentence about what should be different. "
                    + "Try saying what happens now and what should happen instead.";
            }
            if (checks.size() < MIN_CHECKS) {
                return "We could not work out how anyone would tell this was fixed. Add an example "
                    + "of what goes in and what should come out.";
            }
            for (Check check : checks) {
                if (check.test() == null || check.test().isBlank()) {
                    return "One of the checks names no test, so nothing would ever run to prove it.";
                }
            }
            return null;
        }

        /** The requirement statement plus the assumptions, as the BRD stores it. */
        public String statementWithAssumptions() {
            StringBuilder sb = new StringBuilder(statement == null ? "" : statement.strip());
            for (String assumption : assumptions) {
                if (assumption != null && !assumption.isBlank()) {
                    sb.append("\n\nASSUMPTION: ").append(assumption.strip());
                }
            }
            return sb.toString();
        }

        /** One line for a log or a chain link — a measurement, true whatever it found. */
        public String describe() {
            return "'" + title + "' — " + checks.size() + " check(s) "
                + checks.stream().map(c -> c.text() + " [" + c.test() + "]").toList()
                + ", " + assumptions.size() + " stated assumption(s) " + assumptions;
        }
    }

    /** What {@link #start} created: everything downstream needs the run and the story. */
    public record Started(UUID runId, Story story, String requirementHandle,
                          Understanding understanding) {}

    // --- reading -----------------------------------------------------------------------------

    /**
     * The one model call. Writes nothing.
     *
     * @param neighbourhood the rendered {@code ChangeNeighbourhood} brief — the types this repo
     *                      really has, with files and lines. Blank when the index could not answer,
     *                      and the call still runs: a brownfield run never blocks on the index.
     * @param codebaseRules what the build declares and how it is laid out — design §1.4's rules,
     *                      read from the repository rather than written by anybody. May be blank.
     * @throws IllegalArgumentException when the request is not readable ({@link Request#rejection})
     */
    public static Understanding read(ConsoleContext context, Request request, String neighbourhood,
                                     String codebaseRules) throws Exception {
        String refusal = request.rejection();
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        if (context.analystModel() == null) {
            throw new IllegalStateException("no analyst model configured — set "
                + "roles.requirementsAnalyst (or roles.chat) in Settings");
        }
        List<Map<String, String>> conversation = List.of(
            Map.of("role", "system", "content", systemPrompt()),
            Map.of("role", "user", "content", brief(request, neighbourhood, codebaseRules)));
        String reply = call(context, conversation);
        LlmReplyRetry.Asked<Understanding> asked = LlmReplyRetry.askJson(context.blobStore(),
            "the analyst's", conversation, reply, messages -> call(context, messages),
            ChangeRequestIntake::parse);
        Understanding understanding = asked.value();
        log.info("Change request read: {}", understanding.describe());
        return understanding;
    }

    /** {@link #read} with no codebase rules to state. */
    public static Understanding read(ConsoleContext context, Request request, String neighbourhood)
            throws Exception {
        return read(context, request, neighbourhood, "");
    }

    private static String call(ConsoleContext context, List<Map<String, String>> messages)
            throws Exception {
        StringBuilder out = new StringBuilder();
        try (Stream<String> stream = context.analystModel().stream(messages, null)) {
            for (Iterator<String> it = stream.iterator(); it.hasNext(); ) {
                out.append(it.next());
            }
        }
        return out.toString();
    }

    // --- the prompt --------------------------------------------------------------------------

    /**
     * Who is answering and what they may not do.
     *
     * <p>The scope sentence is lifted from {@code RunBrief.forKind(BUGFIX, …)} on purpose. The
     * design roles are already told "change what the fix needs and no more", and the same sentence
     * belongs here, because scope creep at intake is not recoverable downstream: a requirement that
     * asked for three things is planned as three things and judged as three things, and no later
     * stage can tell that only one of them was ever in the issue.
     */
    static String systemPrompt() {
        return """
            You are a business analyst reading ONE bug report or enhancement request against a \
            codebase that already exists. You turn it into one requirement and one to three checks, \
            and nothing else.

            YOU MAY NOT ASK A QUESTION. The person who wrote this report is not here and will not \
            answer. Where the report leaves something open, take the most defensible reading, act \
            on it, and SAY SO in an assumption. An assumption is visible and someone can correct \
            it; a question is a form nobody fills in.

            CHANGE WHAT THE FIX NEEDS AND NO MORE. Do not add behaviour the report does not ask \
            for. Do not generalise "this selector is wrong" into "rewrite the selector engine". Do \
            not bundle a tidy-up. One report is one requirement, and scope you add here cannot be \
            taken out again later.

            STATE THE DIFFERENCE, NOT THE FIX. The requirement says what someone can OBSERVE being \
            different, in the report's own terms: "parsing <input> puts the table outside the \
            paragraph; it should stay inside it". Never name a file, a method or a line as the \
            answer — you do not know where the fix goes, and saying so would pin the work to a \
            guess. Where the report gives an input and a wrong output, use them verbatim: they are \
            the most valuable thing in it.

            EVERY CHECK IS SOMETHING THAT RUNS. A check names what goes in, what comes out today, \
            and what must come out instead. One to three of them, no more — a fourth is almost \
            always the same check said twice. Where the report gives one reproduction, one check is \
            the honest answer; add a second only for a case the report itself raises, such as "and \
            it must still find nothing when the link is absent".

            NAME THE TEST FOR EVERY CHECK. Acceptance tests live in the package "swarm.accept", so \
            every test name is of the form "swarm.accept.<Area>Test#<methodName>", for example \
            "swarm.accept.AttributeSelectorTest#findsAnAttributeValueWithASpaceInIt". Use \
            lowerCamelCase for the method and make it say what the check says. Checks of this one \
            requirement normally share one class and differ only in the method.

            USE THE TYPES THE REPOSITORY REALLY HAS. You are shown, below, what the code around \
            this change actually contains, read out of it and not remembered. Where you name a type \
            or a method, name one from that list. If it is not there, describe the behaviour \
            instead of guessing a name.

            Reply with JSON ONLY, no prose and no code fence:
            {"title":"short name for the change",\
            "statement":"what is observably wrong now, and what must be true instead",\
            "priority":"HIGH|MEDIUM|LOW",\
            "assumptions":["what you decided that the report did not settle, and why"],\
            "checks":[{"text":"what must be observably true",\
            "test":"swarm.accept.AreaTest#methodName"}]}

            Give "assumptions" as an empty list when the report settled everything.""";
    }

    /** Everything the analyst is shown: the request, the neighbourhood, the build's own rules. */
    static String brief(Request request, String neighbourhood, String codebaseRules) {
        StringBuilder sb = new StringBuilder();
        sb.append("This is a ")
          .append("BUGFIX".equals(request.kind())
              ? "BUG REPORT: something is broken and should be fixed."
              : "ENHANCEMENT REQUEST: this works, and should do something more.")
          .append("\n\n=== TITLE ===\n").append(request.title())
          .append("\n\n=== THE REPORT, VERBATIM ===\n")
          .append(cut(request.issueText(), MAX_ISSUE_CHARS));
        if (neighbourhood != null && !neighbourhood.isBlank()) {
            sb.append("\n\n=== WHAT THE CODE AROUND THIS CHANGE ACTUALLY CONTAINS ===\n")
              .append(neighbourhood);
        } else {
            sb.append("\n\n=== WHAT THE CODE AROUND THIS CHANGE ACTUALLY CONTAINS ===\n")
              .append("Nothing could be read from the code for this report. Describe the "
                  + "behaviour in the report's own words and name no types at all.");
        }
        if (codebaseRules != null && !codebaseRules.isBlank()) {
            sb.append("\n\n=== HOW THIS PROJECT IS BUILT (read from its build files) ===\n")
              .append(codebaseRules);
        }
        return sb.toString();
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text
            : text.substring(0, max) + "\n… (the rest of the report was too long to send)";
    }

    // --- parsing -----------------------------------------------------------------------------

    /** @throws IOException when the reply is not JSON, or is JSON of the wrong shape */
    static Understanding parse(String reply) throws IOException {
        JsonNode root = LlmJson.readTree(JSON, reply);
        if (root == null || !root.isObject()) {
            throw new IOException("the reply was not a JSON object");
        }
        String statement = text(root, "statement");
        if (statement.isBlank()) {
            throw new IOException("the reply carries no \"statement\"");
        }
        List<Check> checks = new ArrayList<>();
        for (JsonNode node : root.path("checks")) {
            String checkText = text(node, "text");
            if (checkText.isBlank() || checks.size() >= MAX_CHECKS) {
                continue;
            }
            checks.add(new Check(checkText, testName(text(node, "test"), root)));
        }
        if (checks.isEmpty()) {
            throw new IOException("the reply carries no readable \"checks\"");
        }
        List<String> assumptions = new ArrayList<>();
        for (JsonNode node : root.path("assumptions")) {
            String assumption = node.isTextual() ? node.asText("").strip() : "";
            if (!assumption.isBlank()) {
                assumptions.add(assumption);
            }
        }
        String title = text(root, "title");
        return new Understanding(title.isBlank() ? shorten(statement) : title, statement,
            priority(text(root, "priority")), checks, assumptions);
    }

    /**
     * The test the check names, forced into the one place the swarm may write acceptance tests.
     *
     * <p>A model that answers {@code org.jsoup.select.SelectorTest#hasWithSibling} has named the
     * MAINTAINER'S test tree, which no worker may write in — the whole reason acceptance tests live
     * in a protected package is that a worker who can edit its own test can certify itself green.
     * Rather than refuse the reply, the class is moved into {@code swarm.accept} and the method
     * kept, because the method name is the part that carries the check.
     */
    static String testName(String proposed, JsonNode root) {
        String raw = proposed == null ? "" : proposed.strip();
        String method = raw.contains("#") ? raw.substring(raw.indexOf('#') + 1).strip() : "";
        String type = raw.contains("#") ? raw.substring(0, raw.indexOf('#')).strip() : raw;
        String simple = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
        if (simple.isBlank()) {
            simple = "ChangeRequestTest";
        }
        if (!simple.endsWith("Test")) {
            simple = simple + "Test";
        }
        if (method.isBlank()) {
            method = "reproducesTheReportedBehaviour";
        }
        return ACCEPTANCE_PACKAGE + "." + simple + "#" + method;
    }

    private static String priority(String raw) {
        String asked = raw == null ? "" : raw.strip().toUpperCase(java.util.Locale.ROOT);
        return switch (asked) {
            case "HIGH", "CRITICAL" -> "HIGH";
            case "LOW" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isValueNode() ? "" : value.asText("").strip();
    }

    private static String shorten(String statement) {
        String one = statement.replaceAll("\\s+", " ").strip();
        return one.length() <= 70 ? one : one.substring(0, 67) + "…";
    }

    // --- writing -----------------------------------------------------------------------------

    /**
     * Writes the requirement, agrees it, mints the story and starts the run — in that order.
     *
     * <p>The requirement is AGREED here rather than left in draft, because §1.2 is the whole
     * argument for this class: a story delivering no agreed check turns off the coverage invariant,
     * criterion evidence, the zero-tests-executed failure rule and the delivery stamp. In the
     * console the operator agrees it by pressing one button on the screen that showed them these
     * checks; in a harness the promotion is programmatic, exactly as the end-to-end harness already
     * promotes one requirement out of everything the analyst proposed.
     *
     * @return what was created, or null when nothing was — in which case NOTHING was started and
     *         the caller must say so
     * @throws IllegalArgumentException when the understanding cannot be acted on
     */
    public static Started start(ConsoleContext context, UUID projectId, Request request,
                                Understanding understanding) {
        String refusal = understanding.rejection();
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        if (projectId == null) {
            throw new IllegalArgumentException("there is no project to attach this change to");
        }
        ArtifactStore store = context.store();

        // 1. the requirement, DRAFT, with its checks PROPOSED — the ordinary authoring path.
        String added = BrdAuthoring.addRequirement(store, projectId, understanding.title(),
            understanding.statementWithAssumptions(), understanding.priority(), null,
            "FUNCTIONAL", null, null);
        if (added.startsWith("error:")) {
            throw new IllegalStateException("the requirement could not be written: "
                + added.substring(6).strip());
        }
        String handle = added.split("\\s+")[0];
        List<String> refs = new ArrayList<>();
        for (int at = 0; at < understanding.checks().size(); at++) {
            Check check = understanding.checks().get(at);
            String ref = BrdAuthoring.addCriterion(store, projectId, handle, check.text(),
                check.test());
            if (ref.startsWith("error:")) {
                throw new IllegalStateException("check " + (at + 1) + " could not be written: "
                    + ref.substring(6).strip());
            }
            refs.add(handle + ":C" + (at + 1));
        }

        // 2. agree it — through the SAME derivation an operator's press goes through.
        Brd brd = store.ensureBrd(projectId);
        BrdRequirement requirement = requirementFor(brd, handle);
        if (requirement == null) {
            throw new IllegalStateException("the requirement " + handle + " vanished between "
                + "being written and being agreed");
        }
        String gate = com.swarmcoder.domain.AgreementGate.rejectionFor(requirement);
        if (gate != null) {
            throw new IllegalStateException(gate);
        }
        int accepted = BrdServiceImpl.promote(requirement);
        brd.setRequirements(new ArrayList<>(brd.requirements()));
        store.saveBrd(brd, "human", "agreed " + handle + " and accepted " + accepted
            + (accepted == 1 ? " check" : " checks") + " from a change request");
        BrdAuthoring.pushChanged(brd);

        // 3. the story. proposeStory refuses a delivery story naming no real criterion, which is
        //    the gate that makes step 2 load-bearing rather than decorative.
        String proposed = BacklogAuthoring.proposeStory(store, projectId, understanding.title(),
            String.join(",", refs), understanding.statementWithAssumptions());
        if (proposed.startsWith("error:")) {
            throw new IllegalStateException("the story could not be written: "
                + proposed.substring(6).strip());
        }
        Story story = storyFor(store, projectId, proposed.split("\\s+")[0]);
        if (story == null) {
            throw new IllegalStateException("the story " + proposed.split("\\s+")[0]
                + " was written and could not be read back");
        }
        story.setState(StoryState.RUNNING);
        story.setUpdatedAt(Instant.now());
        store.saveStory(story);

        // 4. the run, bound to the story BEFORE the engine sees it. See AdHocStory#start.
        String kind = WorkflowKind.valueOf(request.kind()).name();
        String goal = goalFor(request, understanding, handle, refs);
        UUID runId;
        try {
            runId = context.bindsStoriesAtStart()
                ? context.startRun(goal, kind, story.id())
                : context.startRun(goal, kind);
        } catch (RuntimeException e) {
            block(store, story, e.getMessage());
            BacklogPublisher.publish(store, projectId);
            throw e;
        }
        if (runId == null) {
            block(store, story, "the run could not be started");
            BacklogPublisher.publish(store, projectId);
            return null;
        }
        try {
            story.setOriginRunId(runId);
            story.setRunIds(new ArrayList<>(List.of(runId)));
            story.setUpdatedAt(Instant.now());
            store.saveStory(story);
            store.recordChange(projectId, "human", ChangeEntityType.STORY, story.id(),
                ChangeKind.STATE_CHANGED, "state", "DRAFT", "RUNNING",
                "started building " + story.key() + " from a change request", runId);
        } catch (Exception e) {
            log.warn("Change-request story {} could not record its run {}: {}", story.key(), runId,
                e.getMessage());
        }
        BacklogPublisher.publish(store, projectId);
        log.info("Change request '{}' became {} ({} check(s)) and story {} on run {}",
            request.title(), handle, refs.size(), story.key(), runId);
        return new Started(runId, story, handle, understanding);
    }

    /**
     * What the run is told it is for.
     *
     * <p>The issue text goes in verbatim. Everything downstream — the architect, the test author,
     * every worker — reads the goal, and a paraphrase of a bug report loses the one thing that
     * makes it reproducible: the input and the wrong output, exactly as they were written.
     */
    static String goalFor(Request request, Understanding understanding, String handle,
                          List<String> refs) {
        StringBuilder goal = new StringBuilder(request.title())
            .append("\n\n").append(understanding.statementWithAssumptions())
            .append("\n\nThe report, as it was written:\n")
            .append(cut(request.issueText(), MAX_ISSUE_CHARS))
            .append("\n\nChecks this must satisfy:\n");
        for (int at = 0; at < understanding.checks().size(); at++) {
            Check check = understanding.checks().get(at);
            goal.append("- ").append(refs.get(at)).append(" (").append(handle).append(") ")
                .append(check.text()).append("  [test: ").append(check.test()).append("]\n");
        }
        return goal.toString();
    }

    private static void block(ArtifactStore store, Story story, String reason) {
        try {
            story.setState(StoryState.BLOCKED);
            story.setRationale("the run could not be started: " + reason);
            story.setUpdatedAt(Instant.now());
            store.saveStory(story);
        } catch (Exception e) {
            log.warn("Could not mark change-request story {} blocked: {}", story.key(),
                e.getMessage());
        }
    }

    private static BrdRequirement requirementFor(Brd brd, String handle) {
        for (BrdRequirement candidate : brd.requirements()) {
            if (handle.equalsIgnoreCase(candidate.handle())) {
                return candidate;
            }
        }
        return null;
    }

    private static Story storyFor(ArtifactStore store, UUID projectId, String key) {
        for (Story candidate : store.listStories(projectId)) {
            if (key.equalsIgnoreCase(candidate.key())) {
                return candidate;
            }
        }
        return null;
    }

    /** The status a requirement written by this class ends in — asserted by the harness. */
    public static RequirementStatus agreedStatus() {
        return RequirementStatus.ACTIVE;
    }

    /** For a caller that wants both halves in one line, with nothing between them to review. */
    public static Started readAndStart(ConsoleContext context, UUID projectId, Request request,
                                       String neighbourhood, String codebaseRules)
            throws Exception {
        return start(context, projectId, request,
            read(context, request, neighbourhood, codebaseRules));
    }

    /** Only so a caller can render "R7:C1, R7:C2" without re-deriving it. */
    static Map<String, String> refsOf(Understanding understanding, String handle) {
        Map<String, String> refs = new LinkedHashMap<>();
        for (int at = 0; at < understanding.checks().size(); at++) {
            refs.put(handle + ":C" + (at + 1), understanding.checks().get(at).test());
        }
        return refs;
    }
}
