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
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.ConstraintBrief;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowProposalKind;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.FlowQuestionKind;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The planning behind the backlog-planning wizard: read the agreed requirements and the backlog as
 * it stands, ask what is genuinely undecided about sequencing and scope, and propose STORIES
 * (docs/GUIDED_FLOWS_DESIGN.md §2). The guided replacement for the removed {@code /backlog} chat
 * mode.
 *
 * <p>The sibling of {@link RequirementsIntake}, and deliberately built to the same shape — one
 * round of questions, proposals reviewed before anything is written, the whole thing on its own
 * thread reporting through {@link GuidedFlows}. What differs is the input: intake reads documents
 * the operator attached, planning reads what the project already holds. There is nothing to upload,
 * so there is no collecting step; the DRAFT state simply means "not started yet".
 *
 * <p><b>The invariant.</b> A story is a slice of DELIVERABLE work identified by the criteria it
 * claims — never a restatement of a requirement. Nothing here writes a {@link Story}: apply goes
 * through {@link BacklogAuthoring#proposeStory} and {@link BacklogAuthoring#proposeEnabler}, which
 * reject a delivery story that names no real criterion. A model that invents capability therefore
 * cannot smuggle it in as story prose; it can only be told to say so in the rationale, which is
 * what the prompt demands.
 */
final class BacklogPlanning {

    private static final Logger log = LoggerFactory.getLogger(BacklogPlanning.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Reading → questions → planning → review. Shown as "step N of 4" under the progress bar. */
    private static final int TOTAL_STEPS = 4;

    /** Upper bound on one round. More than this is a form nobody fills in. */
    private static final int MAX_QUESTIONS = 8;

    /** Running flows, so {@link #cancel} can stop one mid-model-call. */
    private static final Map<UUID, AtomicBoolean> RUNNING = new ConcurrentHashMap<>();

    private BacklogPlanning() {}

    // --- lifecycle ------------------------------------------------------------------------------

    /**
     * Starts planning from the beginning. Returns "" immediately — progress arrives on the signal.
     *
     * <p>Everything but the validation happens on the worker thread. A store write and a broadcast
     * are not free, and doing them inline meant the RMI reply waited on them; the browser is sitting
     * blocked on that reply, so a slow publish turns into a request timeout and a button that looks
     * dead. Nothing here may block the caller.
     */
    static String launch(ConsoleContext context, GuidedFlow flow) {
        if (context.plannerModel() == null) {
            return "error: no planner model configured — set roles.storyPlanner "
                + "(or roles.chat) in Settings";
        }
        spawn(context, flow.id(), true, store -> {
            GuidedFlows.clearQuestions(store, flow.id());
            store.saveFlowProposals(flow.id(), List.of());
            flow.setTotalSteps(TOTAL_STEPS);
            GuidedFlows.advance(store, flow, GuidedFlowState.RUNNING, 1,
                "Reading the requirements and the backlog");
        });
        return "";
    }

    /** Resumes after a round of answers, going straight to planning. Returns immediately. */
    static String resume(ConsoleContext context, GuidedFlow flow) {
        if (context.plannerModel() == null) {
            return "error: no planner model configured — set roles.storyPlanner "
                + "(or roles.chat) in Settings";
        }
        spawn(context, flow.id(), false, store -> {
            flow.setTotalSteps(TOTAL_STEPS);
            GuidedFlows.advance(store, flow, GuidedFlowState.RUNNING, 3,
                "Planning stories from the requirements and your answers");
        });
        return "";
    }

    /** Signals a running plan to stop at its next checkpoint. */
    static void cancel(UUID flowId) {
        AtomicBoolean flag = RUNNING.get(flowId);
        if (flag != null) {
            flag.set(true);
        }
    }

    /** The state change that opens a run, performed on the worker thread rather than the caller's. */
    private interface Opening {
        void apply(ArtifactStore store);
    }

    private static void spawn(ConsoleContext context, UUID flowId, boolean askQuestions,
                              Opening opening) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        RUNNING.put(flowId, cancelled);
        Thread worker = new Thread(() -> {
            try {
                opening.apply(context.store());
                run(context, flowId, askQuestions, cancelled);
            } catch (Exception e) {
                log.warn("Planning flow {} threw", flowId, e);
                GuidedFlow flow = context.store().getGuidedFlow(flowId);
                if (flow != null) {
                    GuidedFlows.fail(context.store(), flow, message(e));
                }
            } finally {
                RUNNING.remove(flowId, cancelled);
            }
        }, "planning-" + flowId.toString().substring(0, 8));
        worker.setDaemon(true);
        worker.start();
    }

    // --- the planning ---------------------------------------------------------------------------

    private static void run(ConsoleContext context, UUID flowId, boolean askQuestions,
                            AtomicBoolean cancelled) throws Exception {
        ArtifactStore store = context.store();
        GuidedFlow flow = store.getGuidedFlow(flowId);
        if (flow == null) {
            return;
        }
        UUID projectId = flow.projectId();
        if (activeCriteria(store, projectId) == 0) {
            GuidedFlows.fail(store, flow, "no agreed requirement has a check on it, so there is "
                + "nothing a story could deliver. Open the Requirements panel and agree the "
                + "requirements you want built first.");
            return;
        }
        String brief = brief(store, projectId);
        log.info("Planning {}: {} characters of requirement and backlog context", flowId,
            brief.length());

        if (askQuestions) {
            GuidedFlows.advance(store, flow, GuidedFlowState.RUNNING, 2,
                "Looking for anything undecided about sequencing and scope");
            String reply = ask(context, questionPrompt(), brief);
            if (cancelled.get()) {
                return;
            }
            List<FlowQuestion> questions = parseQuestions(flowId, reply);
            if (!questions.isEmpty()) {
                store.saveFlowQuestions(flowId, questions);
                GuidedFlows.advance(store, flow, GuidedFlowState.AWAITING_ANSWERS, 2,
                    questions.size() + " question(s) — answer what matters, skip the rest");
                return;
            }
            GuidedFlows.advance(store, flow, GuidedFlowState.RUNNING, 3,
                "Planning stories from the requirements");
        }

        String answers = renderAnswers(store.listFlowQuestions(flowId));
        String system = proposalPrompt(example(store.ensureBrd(projectId)));
        String user = brief + answers;
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        String firstReply = ask(context, messages);
        if (cancelled.get()) {
            return;
        }
        LlmReplyRetry.Asked<List<FlowProposal>> asked;
        try {
            asked = LlmReplyRetry.askJson(context.blobStore(), "the planner's", messages, firstReply,
                m -> ask(context, m),
                r -> parseProposals(store, projectId, flowId, LlmJson.readTree(JSON, r)));
        } catch (MalformedReplyException e) {
            GuidedFlows.fail(store, flow, e.getMessage());
            return;
        }
        if (cancelled.get()) {
            return;
        }
        List<FlowProposal> proposals = asked.value();
        List<String> unclaimed = unclaimedAfterProposals(store, projectId, proposals);
        if (proposals.isEmpty() && unclaimed.isEmpty()) {
            // The scope this run started with is gone — every check it could have delivered is
            // now claimed by a story written meanwhile. Not a failure: there is simply nothing
            // left to plan, exactly the state PlanningFlowServiceImpl.start refuses before a run
            // ever begins.
            GuidedFlows.advance(store, flow, GuidedFlowState.DRAFT, 0,
                "Every check is already claimed by a story, so there was nothing to plan");
            return;
        }
        if (!unclaimed.isEmpty()) {
            proposals = reaskForUnclaimedChecks(context, store, projectId, flowId, messages,
                asked.reply(), proposals, unclaimed, cancelled);
            if (cancelled.get()) {
                return;
            }
        }
        proposals = insideAgreedScope(context, store, flow, projectId, flowId, user, proposals, cancelled);
        if (cancelled.get()) {
            return;
        }
        int flagged = markImpact(store, projectId, proposals);
        List<String> shortfall = unclaimedAfterProposals(store, projectId, proposals);
        store.saveFlowProposals(flowId, proposals);
        String label = proposals.size() + " proposed stor" + (proposals.size() == 1 ? "y" : "ies")
            + " — review, then apply";
        if (flagged > 0) {
            label += "; " + flagged + " cannot be written as they stand and are left unticked";
        }
        if (!shortfall.isEmpty()) {
            // Not a failure — see reaskForUnclaimedChecks. A partial plan the operator can see and
            // extend beats no plan at all, so the run finishes and simply says what is still open.
            label += "; the planner left " + shortfall.size() + " agreed check(s) unclaimed: "
                + String.join(", ", shortfall);
        }
        GuidedFlows.advance(store, flow, GuidedFlowState.REVIEW, TOTAL_STEPS, label);
    }

    // --- the agreement gate ----------------------------------------------------------------------

    /**
     * <b>Reject a plan that reaches outside the agreed scope, and ask again SAYING WHAT WAS
     * WRONG.</b>
     *
     * <p>Built to the precedent {@link com.swarmcoder.workflow.TaskGraphValidator} set for the
     * other planner: a plan that breaks an invariant is not repaired and not quietly trimmed — it
     * is refused, regenerated once, and behind that sits a fallback that is safe by construction.
     * Trimming would be the worse option here for the same reason partial task coverage is a
     * violation there: a story with its unagreed half removed delivers less than the sentence the
     * operator read on the review screen, and nothing tells them.
     *
     * <p><b>The regeneration is INFORMED, and that is the point.</b> The task-graph planner's
     * second attempt is made with the byte-identical prompt that produced the rejected plan, so a
     * model that misread the rule once misreads it again and the retry buys nothing but latency.
     * Here the violations are appended to the request, named ref by ref, so the second attempt
     * knows which references cost it the first plan.
     *
     * <p>The fallback when the second attempt is no better: the plan with FEWER violations is the
     * one the operator reviews, ties going to the informed attempt. Every story in it that reaches
     * outside is then unticked by {@code markImpact} with a reason in words, and the backlog itself
     * refuses the reference even if the operator ticks it anyway. So there is always a way forward
     * and it is never "work nobody agreed to gets written".
     *
     * @return the proposals to review — the original ones when they were already inside scope
     */
    private static List<FlowProposal> insideAgreedScope(ConsoleContext context, ArtifactStore store,
                                                        GuidedFlow flow, UUID projectId, UUID flowId,
                                                        String request, List<FlowProposal> first,
                                                        AtomicBoolean cancelled) throws Exception {
        Brd brd = store.ensureBrd(projectId);
        List<String> violations = outsideAgreedScope(brd, first);
        if (violations.isEmpty()) {
            return first;
        }
        log.info("Planning {}: rejected — {} reference(s) outside the agreed scope: {}", flowId,
            violations.size(), violations);
        GuidedFlows.advance(store, flow, GuidedFlowState.RUNNING, 3,
            "The first plan reached outside what you agreed — asking again");
        String corrected = ask(context, proposalPrompt(example(brd)),
            request + rejection(violations));
        if (cancelled.get()) {
            return first;
        }
        List<FlowProposal> second = parseProposals(store, projectId, flowId, corrected);
        if (second.isEmpty()) {
            // An unreadable second reply is not evidence about the first one. Keep what we have and
            // let the review flag it, rather than failing a flow that has something to show.
            return first;
        }
        List<String> stillOutside = outsideAgreedScope(brd, second);
        if (stillOutside.size() <= violations.size()) {
            if (!stillOutside.isEmpty()) {
                log.info("Planning {}: the second plan is still outside the agreed scope: {}",
                    flowId, stillOutside);
            }
            return second;
        }
        log.info("Planning {}: the second plan was worse ({} vs {} outside scope) — keeping the "
            + "first", flowId, stillOutside.size(), violations.size());
        return first;
    }

    /**
     * Every reference in a plan that names something the operator has not agreed, one line each.
     *
     * <p>Named ref by ref rather than counted, because this text is both the log line and what the
     * model is told: "fifteen references were out of scope" is not something a planner can act on,
     * and {@code R3:C2 belongs to R3, which is a draft requirement} is.
     */
    private static List<String> outsideAgreedScope(Brd brd, List<FlowProposal> proposals) {
        List<String> out = new ArrayList<>();
        for (FlowProposal proposal : proposals) {
            if (proposal.kind() == FlowProposalKind.CONFLICT) {
                // A conflict is a question for the operator; nothing is ever written for one.
                continue;
            }
            for (String ref : split(proposal.handle())) {
                BrdRequirement owner = enabler(proposal)
                    ? requirementFor(brd, ref) : requirementOf(brd, ref);
                if (owner == null || owner.isAgreed()) {
                    continue;   // unknown refs are markImpact's problem, not this one
                }
                out.add(ref + " belongs to " + owner.handle() + " \""
                    + orEmpty(owner.title()) + "\", which is a "
                    + (owner.status() == null ? "draft" : owner.status().label())
                    + " requirement — named by the story \"" + orEmpty(proposal.title()) + "\"");
            }
        }
        return out;
    }

    /** What the planner is told when its plan is thrown away, appended to the identical request. */
    private static String rejection(List<String> violations) {
        return "\n\nYOUR PREVIOUS PLAN WAS REJECTED AND NOTHING FROM IT WAS KEPT.\n\n"
            + "It named checks on requirements the operator has NOT agreed. Those are not yours to "
            + "plan, and a story that names one is thrown away — so it delivers nothing and costs "
            + "the work. Every one of these was refused:\n\n  "
            + String.join("\n  ", violations)
            + "\n\nPlan again over THE REQUIREMENTS YOU MAY PLAN above and nothing else. Do not "
            + "name a check on any requirement listed as out of scope, and do not invent a check "
            + "number for one. If the agreed scope is smaller than you think the project needs, "
            + "that is the operator's decision and it is correct: plan the smaller thing well, and "
            + "say in a story's rationale what you would have added.";
    }

    /** A requirement's status in the operator's words ("draft", "retired"), never the constant. */
    private static String label(BrdRequirement r) {
        return r.status() == null ? "draft" : r.status().label();
    }

    /** The requirement owning a {@code <handle>:C<n>} reference, or null. */
    private static BrdRequirement requirementOf(Brd brd, String ref) {
        String[] parts = ref == null ? new String[0] : ref.split(":");
        return parts.length == 2 ? requirementFor(brd, parts[0]) : null;
    }

    /** One non-streamed model call; returns the whole reply. */
    private static String ask(ConsoleContext context, String system, String user) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        return ask(context, messages);
    }

    /** The model call itself, over an already-built conversation — what a retry appends turns to. */
    private static String ask(ConsoleContext context, List<Map<String, String>> messages) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Stream<String> stream = context.plannerModel().stream(messages, null)) {
            for (Iterator<String> it = stream.iterator(); it.hasNext(); ) {
                out.append(it.next());
            }
        }
        return out.toString();
    }

    // --- apply ----------------------------------------------------------------------------------

    /**
     * Writes the accepted proposals into the backlog as DRAFT stories.
     *
     * <p>Through {@link BacklogAuthoring} and nothing else. That is what keeps the one rule the
     * backlog has: a delivery story must name at least one criterion the BRD actually holds, so a
     * story can never become a second description of what the system must do. A proposal that fails
     * that check is reported by handle rather than dropped, because the operator has to be able to
     * tell which of the ones they ticked did not land.
     */
    static String apply(ConsoleContext context, GuidedFlow flow) {
        ArtifactStore store = context.store();
        List<FlowProposal> proposals = store.listFlowProposals(flow.id());
        int applied = 0;
        List<String> failures = new ArrayList<>();
        // Two passes, and it has to be two. A story can only depend on another story's ID, and half
        // the stories being depended on are being created by this very apply — they have no id until
        // the first pass has written them. The first pass creates, remembering which proposal became
        // which story; the second links.
        Map<UUID, String> writtenFor = new LinkedHashMap<>();
        for (FlowProposal proposal : proposals) {
            if (!proposal.accepted() || proposal.kind() == FlowProposalKind.CONFLICT) {
                continue;
            }
            String result = enabler(proposal)
                ? BacklogAuthoring.proposeEnabler(store, flow.projectId(), proposal.title(),
                    proposal.handle(), proposal.rationale())
                : BacklogAuthoring.proposeStory(store, flow.projectId(), proposal.title(),
                    proposal.handle(), narrativeOf(proposal.after()));
            if (result != null && result.startsWith("error:")) {
                failures.add(orEmpty(proposal.title()) + ": " + result.substring(6).trim());
            } else {
                applied++;
                writtenFor.put(proposal.id(), proposal.title());
            }
        }
        String unlinked = BacklogAuthoring.linkPlannedDependencies(store, flow.projectId(),
            proposals, writtenFor, BacklogPlanning::dependenciesOf);
        if (unlinked != null) {
            failures.add(unlinked);
        }
        if (applied == 0) {
            // Nothing was written, so the flow stays in REVIEW and can be applied again once the
            // reason is fixed. Advancing to APPLIED here would claim a success that never happened
            // and force the operator to re-run the whole plan to get back to this list.
            return failures.isEmpty()
                ? "error: nothing is accepted — tick at least one proposed story"
                : "error: nothing could be applied — " + String.join("; ", failures);
        }
        String label = applied + " stor" + (applied == 1 ? "y" : "ies")
            + " added, waiting for you to accept"
            + (failures.isEmpty() ? "" : ", " + failures.size() + " failed");
        GuidedFlows.advance(store, flow, GuidedFlowState.APPLIED, TOTAL_STEPS, label);
        return failures.isEmpty() ? "" : "error: " + String.join("; ", failures);
    }

    // --- the briefing ---------------------------------------------------------------------------

    /**
     * Everything the planner reads: the requirement graph, the backlog as it stands, and the
     * coverage report.
     *
     * <p>All three, not just the gaps. The gaps say what is left to do, but a story that ignores
     * what is already planned duplicates it, and a story proposed without the requirement's own
     * wording in front of the model is a story written from a handle and a title.
     *
     * <p>The requirement graph comes in TWO parts, and that split is the point. The agreed
     * requirements are rendered in full, checks and all, because those are what a story may be
     * built on. The rest are named and nothing more — see {@link #outOfScopeNote}.
     */
    static String brief(ArtifactStore store, UUID projectId) {
        Brd brd = store.ensureBrd(projectId);
        List<BrdRequirement> agreed = agreed(brd);
        // The worked example uses a handle that IS agreed, never an invented one. A briefing that
        // demonstrates the reference format on "R7:C1" in a project whose R7 is a draft has handed
        // the planner a working out-of-scope reference in the very sentence telling it not to.
        String example = example(brd);
        return "THE REQUIREMENTS YOU MAY PLAN — the operator has agreed these, and only these. "
            + "Acceptance criteria are addressed as <handle>:C<n>; " + example + ":C1 is the "
            + "first criterion of " + example + ":\n"
            + BrdAuthoring.render(brd, agreed)
            + outOfScopeNote(brd)
            + "\n\nTHE BACKLOG AS IT STANDS:\n" + BacklogAuthoring.render(store, projectId)
            + "\n\nCOVERAGE — this is what your plan must close:\n"
            + BacklogAuthoring.coverage(store, projectId)
            + rulesSection(store, projectId);
    }

    /**
     * The project's standing rules — how it must be built — and what they mean for a story.
     *
     * <p><b>The runs this exists because of.</b> Harness runs 37, 38 and 39, 2026-09-25, Bookshelf
     * demo. The project's technical document mandates persistence through EclipseStore on the
     * server (zerozstack-store-eclipsestore), and every other agent in the pipeline is shown that
     * rule. This one was not: its briefing held the requirements, the backlog and the coverage, and
     * nothing about how the project is built. Asked for a story over "if the user closes the
     * browser and comes back tomorrow, the books and their ratings are still there", it wrote, three
     * runs in a row, "Add a persistence layer that saves the current books and their ratings to
     * localStorage…" — the statistically usual answer for a browser app, and the one the rules
     * forbid. That sentence became the run's goal. The architect, who IS shown the rules, rightly
     * designed server-side EclipseStore; the design reviewer, shown the goal, objected that the
     * design "deviated from the story's requirement"; and every worker read a goal telling it to do
     * the wrong thing.
     *
     * <p>So the planner now reads the same rules, rendered by the same {@link ConstraintBrief} over
     * the same definition of "in force" ({@link ConstraintBrief#inForce}) every worker's prompt
     * uses, with the one instruction that matters here: a story says WHAT the user gets, never HOW
     * it is built. The how is the architect's, inside the rules. The question round reads the same
     * briefing, so it is not tempted to ask about a settled rule either.
     *
     * <p>Empty when the project has no rules in force: the briefing is then byte-for-byte what it
     * was, and the planner is never told "there are no rules", which reads as permission.
     */
    static String rulesSection(ArtifactStore store, UUID projectId) {
        String rules;
        try {
            rules = ConstraintBrief.render(
                ConstraintBrief.inForce(store.root().guidelines.values(), projectId));
        } catch (RuntimeException e) {
            // A store with nothing behind it (a test double) has no rules to show; planning goes on.
            log.debug("No project rules readable for the story planner: {}", e.toString());
            return "";
        }
        if (rules == null || rules.isBlank()) {
            return "";
        }
        return "\n\n" + rules.strip() + "\n\n" + STORIES_SAY_WHAT_NOT_HOW;
    }

    /**
     * What the story planner is told under the project's rules (harness run 39, 2026-09-25 — see
     * {@link #rulesSection}).
     */
    static final String STORIES_SAY_WHAT_NOT_HOW =
        "WHAT THESE RULES MEAN FOR YOUR STORIES. The rules above are settled: do not ask about "
        + "them, and never propose a story to deliver one. A story says WHAT the user gets, never "
        + "HOW it is built — how is decided later, by the architect, inside these rules. So a "
        + "story's title, narrative and rationale must not name a technology, library, framework, "
        + "storage mechanism or place where data is kept unless a rule above names it, and must "
        + "NEVER name one a rule forbids or contradicts. \"The books and their ratings are still "
        + "there after the browser is closed and reopened\" is a story; \"save the books to "
        + "localStorage\", in a project whose rules keep the data on the server, is a story "
        + "telling every developer after you to break a rule.";

    /** The requirements the operator has agreed — the only ones a story may be built on. */
    private static List<BrdRequirement> agreed(Brd brd) {
        List<BrdRequirement> out = new ArrayList<>();
        if (brd != null && brd.requirements() != null) {
            for (BrdRequirement r : brd.requirements()) {
                if (r.isAgreed()) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    /**
     * The requirements that exist and are NOT agreed: named, and stripped of their checks.
     *
     * <p><b>Why they are shown at all.</b> A planner that cannot see its neighbours plans work that
     * ignores an obvious dependency — it will happily propose "search the list" as if nothing else
     * were ever going to touch the same record. Seeing that six more requirements are coming is the
     * difference between a first slice built to be extended and one built to be thrown away.
     *
     * <p><b>Why they are shown WITHOUT their checks.</b> A name is not addressable and a check
     * number is. The old briefing rendered the whole document — every {@code R3:C2} in it — beside
     * a sentence asking the model not to use them, and an end-to-end run answered that with six
     * stories claiming sixteen checks, fifteen of them from requirements still in draft. The
     * strongest thing this section can do is leave nothing to copy.
     */
    private static String outOfScopeNote(Brd brd) {
        StringBuilder sb = new StringBuilder();
        if (brd != null && brd.requirements() != null) {
            for (BrdRequirement r : brd.requirements()) {
                if (r.isAgreed() || r.isRetired()) {
                    continue;   // a retired requirement is history; it is not "coming later"
                }
                sb.append("  ").append(r.handle()).append(" [")
                    .append(r.status() == null ? "draft" : r.status().label()).append("] ")
                    .append(r.title() == null ? "" : r.title()).append('\n');
            }
        }
        if (sb.length() == 0) {
            return "";
        }
        return "\n\nNOT AGREED — OUT OF SCOPE, AND NOT YOURS TO PLAN. These requirements exist but "
            + "the operator has not agreed them. They are listed by name only so you can see what "
            + "is coming and order your work sensibly. Their checks are deliberately NOT shown and "
            + "cannot be addressed: a story naming a check on any of them is REJECTED and thrown "
            + "away, so naming one loses the story and gains nothing. Plan no work for them. If "
            + "something below plainly has to come first, say so in a story's rationale — do not "
            + "plan it.\n" + sb;
    }

    /** How many criteria the agreed (ACTIVE) requirements have between them. */
    static int activeCriteria(ArtifactStore store, UUID projectId) {
        Brd brd = store.getBrd(projectId);
        if (brd == null || brd.requirements() == null) {
            return 0;
        }
        int count = 0;
        for (BrdRequirement r : brd.requirements()) {
            if (r.status() == RequirementStatus.ACTIVE) {
                count += r.criteria().size();
            }
        }
        return count;
    }

    /**
     * Every {@code <handle>:C<n>} on an agreed requirement that no living story claims yet — what
     * the planner is shown when it proposes nothing and something is still there to propose over.
     * Shares {@link BacklogAuthoring#claimedCriterionIds} with the coverage report so this can never
     * disagree with the number the wizard already shows for the same thing.
     */
    private static List<String> unclaimedRefs(ArtifactStore store, UUID projectId) {
        Brd brd = store.getBrd(projectId);
        List<String> out = new ArrayList<>();
        if (brd == null || brd.requirements() == null) {
            return out;
        }
        Set<UUID> claimed = BacklogAuthoring.claimedCriterionIds(store, projectId);
        for (BrdRequirement r : brd.requirements()) {
            if (r.status() != RequirementStatus.ACTIVE) {
                continue;
            }
            List<AcceptanceCriterion> criteria = r.criteria();
            for (int i = 0; i < criteria.size(); i++) {
                if (!claimed.contains(criteria.get(i).id())) {
                    out.add(r.handle() + ":C" + (i + 1));
                }
            }
        }
        return out;
    }

    // --- the unclaimed-checks re-ask -------------------------------------------------------------

    /**
     * <b>Fires once when the parsed proposals leave an agreed check with nobody to claim it.</b>
     *
     * <p>Before this, the re-ask only fired when the reply proposed literally zero stories — and a
     * reply with ONE story that claimed nothing passed that narrower test cleanly, then broke the
     * harness one step later: the agreed check it should have delivered stayed unclaimed and the
     * plan covered nothing. The condition that actually matters is not the story count, it is
     * whether every agreed check has somewhere to be built — the same thing
     * {@code PlanTaskLinkageCheck} checks for tasks at the end of the chain, applied here at
     * planning time instead, where a bad plan costs nothing to correct.
     *
     * <p>The second reply's stories are MERGED with the first ({@link #mergeProposals}) rather than
     * replacing it: a retry that only needed to add one more story must not be allowed to drop an
     * enabler the first reply got right.
     *
     * <p>If checks are still unclaimed after this one retry, the caller proceeds anyway — see the
     * shortfall recorded on the flow's outcome in {@link #run}. A partial plan the operator can see
     * and extend beats no plan at all.
     */
    private static List<FlowProposal> reaskForUnclaimedChecks(ConsoleContext context, ArtifactStore store,
                                                              UUID projectId, UUID flowId,
                                                              List<Map<String, String>> messages,
                                                              String firstReply, List<FlowProposal> first,
                                                              List<String> unclaimed,
                                                              AtomicBoolean cancelled) throws Exception {
        Brd brd = store.ensureBrd(projectId);
        int total = activeCriteria(store, projectId);
        log.info("Planning {}: {} of {} agreed check(s) still unclaimed by {} propos{} — asking "
            + "again", flowId, unclaimed.size(), total, first.size(), first.size() == 1 ? "al" : "als");
        List<Map<String, String>> reask = new ArrayList<>(messages);
        reask.add(Map.of("role", "assistant", "content", firstReply == null ? "" : firstReply));
        reask.add(Map.of("role", "user", "content", unclaimedChecksReaskMessage(brd, unclaimed,
            total - unclaimed.size(), total, enablersWithNoDependant(first))));
        String secondReply = ask(context, reask);
        if (cancelled.get()) {
            return first;
        }
        List<FlowProposal> second = parseProposals(store, projectId, flowId, secondReply);
        return mergeProposals(first, second);
    }

    /** What the planner is told when checks remain unclaimed, appended to the identical request. */
    private static String unclaimedChecksReaskMessage(Brd brd, List<String> unclaimed, int claimedCount,
                                                       int totalAgreed, List<String> deadEnablers) {
        List<String> described = new ArrayList<>();
        for (String ref : unclaimed) {
            described.add(describeUnclaimed(brd, ref));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Your stories claim ").append(claimedCount).append(" of the ").append(totalAgreed)
            .append(" agreed checks. These are still unclaimed: ").append(String.join(", ", described))
            .append(". Add stories that claim them, or add the claims to the stories you proposed. "
                + "Keep any enabler story that other stories build on.");
        if (!deadEnablers.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (String title : deadEnablers) {
                lines.add("'" + title + "' claims no check and nothing builds on it");
            }
            sb.append(' ').append(String.join("; ", lines)).append('.');
        }
        return sb.toString();
    }

    /** One unclaimed check, named with its requirement handle and its test — what makes it act on. */
    private static String describeUnclaimed(Brd brd, String ref) {
        BrdRequirement requirement = requirementOf(brd, ref);
        AcceptanceCriterion criterion = criterion(brd, ref);
        String test = criterion == null || criterion.testClassOrFile() == null
            || criterion.testClassOrFile().isBlank() ? "no test recorded" : criterion.testClassOrFile();
        return ref + " (" + (requirement == null ? "?" : requirement.handle()) + " — " + test + ")";
    }

    /**
     * Every agreed check no DELIVERY proposal in this round claims — the store's own unclaimed
     * checks ({@link #unclaimedRefs}), minus whatever the round's proposals themselves cover. A
     * CONFLICT claims nothing (nothing is ever written for one) and an enabler claims nothing by
     * design (it unblocks a requirement, it does not deliver one of its checks), so neither counts.
     */
    private static List<String> unclaimedAfterProposals(ArtifactStore store, UUID projectId,
                                                         List<FlowProposal> proposals) {
        Set<String> claimedThisRound = new LinkedHashSet<>();
        for (FlowProposal proposal : proposals) {
            if (proposal.kind() == FlowProposalKind.CONFLICT || enabler(proposal)) {
                continue;
            }
            claimedThisRound.addAll(split(proposal.handle()));
        }
        List<String> out = new ArrayList<>();
        for (String ref : unclaimedRefs(store, projectId)) {
            if (!claimedThisRound.contains(ref)) {
                out.add(ref);
            }
        }
        return out;
    }

    /**
     * Every enabler in this round that nothing depends on — the planning-time twin of
     * {@code PlanTaskLinkageCheck}'s rule for tasks: an enabler claims no check by design, so the
     * only thing that justifies it is another story built on it. One that nothing builds on did
     * nothing but occupy the story count while an agreed check went unclaimed — exactly what
     * happened to the one-story plan that motivated this whole re-ask.
     */
    private static List<String> enablersWithNoDependant(List<FlowProposal> proposals) {
        Set<String> dependedOn = new LinkedHashSet<>();
        for (FlowProposal proposal : proposals) {
            for (String dependency : dependenciesOf(proposal.after())) {
                dependedOn.add(dependency.strip().toLowerCase(Locale.ROOT));
            }
        }
        List<String> out = new ArrayList<>();
        for (FlowProposal proposal : proposals) {
            if (proposal.kind() == FlowProposalKind.CONFLICT || !enabler(proposal)) {
                continue;
            }
            String title = orEmpty(proposal.title()).strip();
            if (!title.isBlank() && !dependedOn.contains(title.toLowerCase(Locale.ROOT))) {
                out.add(title);
            }
        }
        return out;
    }

    /**
     * The stories from a re-ask, added to what the first reply already got right. NOT a
     * replacement: an enabler the first reply proposed correctly must survive a retry that only
     * needed one more story claiming one more check. A story already present — same title, same
     * claim — is dropped rather than duplicated, which is what keeps a scripted or repeating reply
     * from doubling the plan.
     */
    private static List<FlowProposal> mergeProposals(List<FlowProposal> first, List<FlowProposal> second) {
        if (second.isEmpty()) {
            return first;
        }
        List<FlowProposal> merged = new ArrayList<>(first);
        Set<String> already = new LinkedHashSet<>();
        for (FlowProposal proposal : first) {
            already.add(mergeKey(proposal));
        }
        for (FlowProposal proposal : second) {
            if (already.add(mergeKey(proposal))) {
                merged.add(proposal);
            }
        }
        return merged;
    }

    /** Identity for de-duplicating a re-asked reply against the one it is answering. */
    private static String mergeKey(FlowProposal proposal) {
        return (orEmpty(proposal.title()).strip() + "|" + orEmpty(proposal.handle()).strip())
            .toLowerCase(Locale.ROOT);
    }

    /** The answered and skipped questions, told to the model as constraints on what it may assume. */
    private static String renderAnswers(List<FlowQuestion> questions) {
        if (questions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nWHAT YOU ASKED THE OPERATOR:\n");
        for (FlowQuestion q : questions) {
            sb.append("- ").append(q.text()).append('\n');
            if (q.sourceQuote() != null && !q.sourceQuote().isBlank()) {
                sb.append("  CONTEXT: ").append(q.sourceQuote().strip()).append('\n');
            }
            if (q.skipped() || q.answer() == null || q.answer().isBlank()) {
                sb.append("  NOT ANSWERED — plan the most defensible way and say in the story's "
                    + "RATIONALE what you assumed and why.\n");
            } else {
                sb.append("  ANSWER: ").append(q.answer().strip()).append('\n');
            }
            if (q.note() != null && !q.note().isBlank()) {
                // The operator's own words qualify the choice, and outrank the option label where
                // the two pull in different directions.
                sb.append("  THEY ALSO SAID: ").append(q.note().strip()).append('\n');
            }
        }
        return sb.toString();
    }

    // --- prompts --------------------------------------------------------------------------------

    private static String questionPrompt() {
        return """
            You are planning a delivery backlog over requirements that have already been agreed. \
            Your ONLY job right now is to identify what is genuinely undecided about HOW THIS GETS \
            BUILT AND IN WHAT ORDER — the things where two competent planners would produce \
            different plans.

            Ask about: sequencing where one requirement plainly depends on another and the BRD does \
            not say which comes first; what is explicitly OUT of scope for now; which requirement \
            the operator considers riskiest or least understood; what the first slice has to prove; \
            and whether an obviously large requirement should be delivered in one story or several.

            NEVER ask whether something in the BRD should be built. It is ACTIVE — that means the \
            operator has already agreed it. "Should we implement R4?" when R4 is agreed scope is \
            asking them to re-approve their own decision, and it is the single most common way this \
            goes wrong.

            Also never ask: for requirement content ("what should the password rules be?") — you \
            are not writing requirements and have no way to record an answer to that; anything the \
            BRD already answers; anything a competent planner would decide themselves; or \
            estimates, dates and sizes, because this system has none of those and never will.

            Before you emit a question, test it: would two competent planners, both having read \
            this BRD and this backlog, actually produce DIFFERENT plans depending on the answer? If \
            not, drop it. If you can pick the more defensible order yourself, do that and say so in \
            the story's rationale instead — a stated assumption is visible and correctable, and \
            costs the operator nothing to read.

            **Asking nothing is a correct and common answer.** A small, clear BRD deserves \
            {"questions":[]} and no apology for it. Eight questions where there are two real \
            decisions is worse than two, because the operator stops reading them.

            EVERY question MUST carry the background needed to answer it. Name the requirement \
            handles it concerns; the operator has not memorised them either.

            - "sourceQuote": the requirement wording or the criteria that raised the question, \
              quoted from the BRD above, with their handles. Never write a bare cross-reference.
            - "background": the fuller explanation, a short paragraph, read on demand rather than \
              inline so it can afford the length — what the plan turns on, and what each answer \
              would mean for the order of work.

            Reply with JSON ONLY, no prose and no code fence:
            {"questions":[{"subject":"short label — what this concerns","text":"the question",\
            "sourceQuote":"the requirements this came from","background":"the fuller explanation",\
            "kind":"CHOICE","options":["...","..."]}]}

            Use "kind":"CHOICE" with 2-4 options when the answer is closed-ended, otherwise \
            "kind":"TEXT" and omit options. At most %d questions. If nothing is genuinely \
            undecided, reply {"questions":[]}.""".formatted(MAX_QUESTIONS);
    }

    /**
     * @param example an AGREED requirement's handle, used for every worked reference below. The
     *                examples used to be written as "R7:C1" whatever the project held, so in a BRD
     *                whose R7 was a draft the instruction not to plan unagreed work was itself
     *                demonstrated with a working unagreed reference.
     */
    private static String proposalPrompt(String example) {
        return """
            You are a delivery planner. From the requirements, the existing backlog and any answers \
            given, propose STORIES. You are not writing them into the backlog — an operator reviews \
            every proposal and applies the ones they accept, and what lands is a DRAFT they still \
            have to promote.

            WHAT A STORY IS: a slice of DELIVERABLE work, identified by the acceptance criteria it \
            makes true. It is NOT a restatement of a requirement. If your story's description could \
            be pasted into the BRD without anyone noticing, you have written a requirement again \
            rather than planned any work — delete it and think about what someone would actually \
            build.

            EVERY DELIVERY STORY NAMES REAL CRITERIA from the BRD above, in "delivers", as \
            <handle>:C<n> — for example "%1$s:C1,%1$s:C2". Not a handle on its own, not a criterion you \
            wish existed, not a paraphrase. A story naming a criterion that is not in the BRD is \
            REJECTED when it is applied, so inventing one loses the story rather than gaining the \
            capability.

            STAY INSIDE THE AGREED SCOPE. You may name checks only on the requirements listed under \
            THE REQUIREMENTS YOU MAY PLAN. Anything under NOT AGREED is out of scope: the operator \
            has not agreed it, its checks are not shown to you, and a story naming one is refused \
            twice over — the whole plan is thrown away and asked for again, and the backlog refuses \
            the reference even if it got that far. This is not advice. It is checked in code.

            DO NOT INVENT REQUIREMENT CONTENT. If the work you can see is needed but nothing in the \
            BRD asks for it, say so in the rationale — plainly, naming what is missing — and \
            propose nothing for it. You have no way to write a requirement and must not pretend \
            otherwise by hiding one inside a story's narrative. The operator adds it with the \
            Requirements panel's "Analyse documents" wizard, and it becomes plannable on the next \
            run.

            COVER THE UNCLAIMED CRITERIA. The coverage report above lists what no story delivers \
            yet; that is your work list. Do NOT re-propose something the backlog already holds — \
            read it before you plan. A criterion already claimed by a living story is done being \
            planned.

            EVERY AGREED CHECK MUST BE CLAIMED BY EXACTLY ONE STORY, using its handle. A story with \
            no check of its own — an enabler — is allowed ONLY when another story you propose \
            depends on it. If you leave a check unclaimed, or write an enabler nothing builds on, \
            you will be shown what is still missing and asked again.

            SAY WHAT EACH STORY BUILDS ON. This is the most important thing you do here, and the \
            one it is easiest to skip. Nine stories were once planned from a document like this one \
            and started at the same time: the first was building the domain model, and the other \
            eight each invented their own version of a model that did not exist yet. The whole \
            night was wasted. Nothing but this field prevents that.

            Put in "dependsOn" the stories this one cannot be built without — the ones that create \
            the thing it uses. Name them by their EXACT TITLE as you wrote it in this same reply, \
            or by the key of a story already in the backlog ("S3").

            "dependsOn" IS A JSON ARRAY OF STRINGS, one entry per story, and each entry is one \
            whole title on its own: ["Book records with add, edit and remove","Login screen"]. \
            Never join two titles into one string. Titles contain commas, so a joined string \
            cannot be taken apart again and the ordering is lost — which is the one thing this \
            field is for. An empty array [] means the story can be built against the code as it \
            stands today.

            Ask it this way round: if a developer started this story tomorrow morning with only \
            today's code in front of them, what would they find missing? Whatever creates that \
            thing is what this depends on. A story that stores something comes before a story that \
            searches it; a story that defines a record comes before every story that reads one. \
            Do NOT declare a dependency for things that merely sound related, and never make two \
            stories wait for each other — that is a circle and neither would ever start.

            PREFER FEW SUBSTANTIAL STORIES TO MANY TRIVIAL ONES. A story that delivers two or three \
            related criteria end to end is worth more than three stories delivering one clause \
            each, because it can be demonstrated and it can be verified. A bad story is a layer \
            ("build the database tables"), a phase ("design the API"), or half a criterion. NEVER \
            split one criterion across two stories — the criterion is the unit that passes or \
            fails, and half of it can do neither.

            Rules:
            - "kind":"ADD" is a new story. Use "storyKind":"DELIVERY" with "delivers", or \
              "storyKind":"ENABLER" with "unblocks" for technical work — a migration, a spike, \
              infrastructure — that satisfies no criterion of its own. An enabler names the \
              requirement HANDLES it unblocks (e.g. "%1$s") and delivers nothing. Do not invent a \
              fake requirement to justify one.
            - "narrative" is the as-a / I-want / so-that, or one plain sentence of what gets built. \
              Keep it about the WORK, not about what the system must do — that is the BRD's job. \
              Describe what the user gets, not the mechanism: where the briefing shows the \
              project's rules, never name a technology or storage mechanism they forbid.
            - "rationale" is why this is a story of its own: why these criteria belong together, \
              why now, what it de-risks, and any assumption you had to make.
            - "kind":"CONFLICT" where two criteria cannot be delivered together — they contradict \
              each other, or one's design forecloses the other. Put both statements in "before" and \
              "after", name the criteria in "delivers", and leave the rest empty. A conflict is a \
              question for the operator, not a change; nothing is written for it.
            - No dates, no estimates, no story points. This system has none and never will, so do \
              not offer any.

            Reply with JSON ONLY, no prose and no code fence:
            {"proposals":[{"kind":"ADD","storyKind":"DELIVERY","title":"short story title",\
            "delivers":"%1$s:C1,%1$s:C2","unblocks":"",\
            "dependsOn":["exact title of another story here"],\
            "narrative":"what gets built",\
            "rationale":"why this is a story of its own"}]}""".formatted(example);
    }

    /**
     * A handle the planner may legitimately reference, for every worked example it is shown.
     *
     * <p>One derivation for the briefing and the prompt, so the two can never demonstrate the
     * reference format on different handles — or, as before, on one that is out of scope.
     */
    private static String example(Brd brd) {
        List<BrdRequirement> agreed = agreed(brd);
        return agreed.isEmpty() ? "R7" : agreed.get(0).handle();
    }

    // --- parsing --------------------------------------------------------------------------------

    private static List<FlowQuestion> parseQuestions(UUID flowId, String reply) {
        List<FlowQuestion> out = new ArrayList<>();
        JsonNode root = json(reply);
        if (root == null) {
            return out;
        }
        for (JsonNode node : root.path("questions")) {
            String text = str(node, "text");
            if (text.isBlank() || out.size() >= MAX_QUESTIONS) {
                continue;
            }
            List<String> options = new ArrayList<>();
            for (JsonNode option : node.path("options")) {
                if (!option.asText("").isBlank()) {
                    options.add(option.asText());
                }
            }
            // A CHOICE with fewer than two options is a text question the model mislabelled;
            // rendering it as a radio group with one button would be absurd.
            FlowQuestionKind kind = "CHOICE".equalsIgnoreCase(str(node, "kind")) && options.size() >= 2
                ? FlowQuestionKind.CHOICE : FlowQuestionKind.TEXT;
            out.add(new FlowQuestion(UUID.randomUUID(), flowId, str(node, "subject"), text,
                blankToNull(str(node, "sourceQuote")), null,
                blankToNull(str(node, "background")), kind,
                kind == FlowQuestionKind.CHOICE ? options : new ArrayList<>(), null, null, false));
        }
        return out;
    }

    /**
     * Reads the planner's stories into proposals.
     *
     * <p>The criteria refs go on {@code handle} because they are the story's IDENTITY — the thing
     * apply resolves, and the thing that decides whether the story may exist at all. {@code after}
     * is the same story rendered for a human, with each criterion's actual wording looked up from
     * the BRD: a review list of "R7:C1,R7:C2" asks the operator to hold the requirement graph in
     * their head, which is the friction this wizard exists to remove.
     */
    private static List<FlowProposal> parseProposals(ArtifactStore store, UUID projectId,
                                                     UUID flowId, String reply) {
        JsonNode root = json(reply);
        return root == null ? new ArrayList<>() : parseProposals(store, projectId, flowId, root);
    }

    /** As above, over an already-parsed root — what a caller reaches for once it has one in hand. */
    private static List<FlowProposal> parseProposals(ArtifactStore store, UUID projectId,
                                                     UUID flowId, JsonNode root) {
        List<FlowProposal> out = new ArrayList<>();
        Brd brd = store.ensureBrd(projectId);
        Set<String> known = knownReferences(store, projectId, root);
        for (JsonNode node : root.path("proposals")) {
            boolean conflict = "CONFLICT".equalsIgnoreCase(str(node, "kind"));
            boolean isEnabler = !conflict && "ENABLER".equalsIgnoreCase(str(node, "storyKind"));
            String title = str(node, "title");
            String refs = isEnabler ? str(node, "unblocks") : str(node, "delivers");
            if (title.isBlank() && refs.isBlank()) {
                continue;
            }
            String after = conflict ? str(node, "after")
                : render(brd, node, isEnabler, refs, known);
            out.add(new FlowProposal(UUID.randomUUID(), flowId,
                conflict ? FlowProposalKind.CONFLICT : FlowProposalKind.ADD,
                refs, title, blankToNull(str(node, "before")), after, str(node, "rationale"), null,
                // Pre-accepted for a story so the common case is one click on Apply. A CONFLICT
                // never is: it is a contradiction to resolve, and accepting one would be choosing a
                // side on the operator's behalf.
                !conflict));
        }
        return out;
    }

    /**
     * A proposed story, rendered as a line-oriented block that is both readable and parseable.
     *
     * <p>{@link FlowProposal} carries {@code after} as text because that is what a review shows.
     * Keeping the block parseable means apply writes exactly the story the operator read — not a
     * summary of it.
     */
    private static String render(Brd brd, JsonNode node, boolean isEnabler, String refs,
                                 Set<String> known) {
        // No "Kind:" line. Story kinds are cut from every operator surface (UX v3 §6), and
        // this block was the last place one survived: it printed the Java constant, DELIVERY or
        // ENABLER, as the first word of the review screen. What the two kinds differ in is
        // already spelled out underneath in words — an enabler UNBLOCKS requirements, an
        // ordinary story DELIVERS checks — and that is what enabler(FlowProposal) now reads.
        Map<String, String> header = new LinkedHashMap<>();
        header.put("Narrative", str(node, "narrative").replace("\n", " ").strip());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : header.entrySet()) {
            if (!entry.getValue().isBlank()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        for (String ref : split(refs)) {
            if (isEnabler) {
                BrdRequirement requirement = requirementFor(brd, ref);
                sb.append("Unblocks ").append(ref).append(" — ")
                    .append(requirement == null ? "(no such requirement)"
                        : orEmpty(requirement.title())).append('\n');
            } else {
                sb.append("Delivers ").append(ref).append(" — ")
                    .append(orDefault(criterionText(brd, ref), "(no such check)")).append('\n');
            }
        }
        // One line per story this one has to come after, written the way the operator reads it and
        // the way apply parses it back. The dependency is deliberately named by TITLE rather than by
        // a code: a review list of "depends on: A, C" asks somebody to hold a lookup table in their
        // head, which is the friction this wizard exists to remove.
        for (String waitsFor : dependencyReferences(node, known)) {
            // One line each, so a title carrying a comma survives being written down and
            // read back whole.
            sb.append(WAITS_FOR).append(' ')
                .append(waitsFor.replace("\n", " ").strip()).append('\n');
        }
        return sb.toString();
    }

    /** The marker line that carries a dependency through review and into apply. */
    private static final String WAITS_FOR = "Comes after:";

    /**
     * The dependencies out of a rendered block — the stories this one has to come after.
     *
     * <p>Read back from the block for the same reason the narrative is: the block is what the
     * operator actually read and ticked, so apply must write exactly that and not a second copy of
     * the model's reply that could differ from it.
     */
    static List<String> dependenciesOf(String block) {
        List<String> out = new ArrayList<>();
        for (String line : (block == null ? "" : block).split("\n")) {
            String stripped = line.strip();
            if (stripped.regionMatches(true, 0, WAITS_FOR, 0, WAITS_FOR.length())) {
                String value = stripped.substring(WAITS_FOR.length()).strip();
                if (!value.isBlank()) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    /**
     * The stories one proposal says it has to come after.
     *
     * <p><b>A dependency is a TITLE, and titles contain commas.</b> This used to be one string cut
     * on every comma in it, which meant a real plan naming "Book records with add, edit, remove,
     * validation, and labelled forms" produced five fragments, none of which was the name of
     * anything — so every one of those edges was thrown away and the ordering came off exactly the
     * stories that needed it most. Nothing warned; the operator got a list of fragments.
     *
     * <p>So the field is now a JSON ARRAY, one whole title per entry, and the prompt asks for it
     * that way. An array cannot be shredded: there is no delimiter to get wrong.
     *
     * <p>A plain string is still accepted, because replies written before the prompt changed are
     * sitting in stores and because a model will sometimes send one anyway. It is NOT cut blindly.
     * The whole string is offered first to the names that actually exist — the titles proposed in
     * this same reply, and the keys and titles of stories already in the backlog — and only what
     * matches nothing is looked at again for a comma. So "S1,S3" still yields two keys, while a
     * single title with four commas in it stays one title.
     *
     * <p>This does not touch {@code delivers} or {@code unblocks}. Those carry check ids and
     * requirement handles — "R7:C1,R7:C2", "R3,R4" — which genuinely are comma-separated lists of
     * tokens that can never contain a comma, and they keep using {@link #split(String)}.
     */
    private static List<String> dependencyReferences(JsonNode node, Set<String> known) {
        JsonNode value = node.path("dependsOn");
        List<String> out = new ArrayList<>();
        if (value.isArray()) {
            for (JsonNode element : value) {
                String text = element.asText("").strip();
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
            return out;
        }
        return matchAgainstKnown(str(node, "dependsOn"), known);
    }

    /**
     * Reads a one-string dependency list by matching it against names that exist, longest first.
     *
     * <p>At each position it asks: does the text from here to SOME comma — or to the end — name a
     * story we know? The longest answer wins, so a title that contains commas is taken whole rather
     * than at its first one. When nothing starting here is a name we know, the text is NOT cut into
     * pieces that mean nothing: it is kept whole up to the next place a known name does start, and
     * reported unmatched with the known names beside it, so the failure says what went wrong.
     */
    private static List<String> matchAgainstKnown(String text, Collection<String> known) {
        List<String> out = new ArrayList<>();
        Set<String> lowered = new LinkedHashSet<>();
        for (String name : known == null ? List.<String>of() : known) {
            if (name != null && !name.isBlank()) {
                lowered.add(name.strip().toLowerCase(Locale.ROOT));
            }
        }
        String rest = text == null ? "" : text.strip();
        while (!rest.isBlank()) {
            int end = longestKnownPrefix(rest, lowered);
            if (end < 0) {
                int commaBeforeNext = commaBeforeNextKnown(rest, lowered);
                if (commaBeforeNext < 0) {
                    out.add(rest);
                    return out;
                }
                String orphan = rest.substring(0, commaBeforeNext).strip();
                if (!orphan.isBlank()) {
                    out.add(orphan);
                }
                rest = rest.substring(commaBeforeNext + 1).strip();
                continue;
            }
            String piece = rest.substring(0, end).strip();
            if (!piece.isBlank()) {
                out.add(piece);
            }
            rest = end >= rest.length() ? "" : rest.substring(end + 1).strip();
        }
        return out;
    }

    /** Where the longest known name starting at index 0 ends, or -1 if none does. */
    private static int longestKnownPrefix(String text, Set<String> lowered) {
        List<Integer> ends = new ArrayList<>();
        for (int comma = text.indexOf(','); comma >= 0; comma = text.indexOf(',', comma + 1)) {
            ends.add(comma);
        }
        ends.add(text.length());
        for (int i = ends.size() - 1; i >= 0; i--) {
            String candidate = text.substring(0, ends.get(i)).strip();
            if (!candidate.isBlank() && lowered.contains(candidate.toLowerCase(Locale.ROOT))) {
                return ends.get(i);
            }
        }
        return -1;
    }

    /**
     * The comma that ends the unrecognised run: the first one after which some known name begins.
     * -1 when no known name begins anywhere later, which means the whole remainder is one
     * unrecognised reference.
     */
    private static int commaBeforeNextKnown(String text, Set<String> lowered) {
        for (int comma = text.indexOf(','); comma >= 0; comma = text.indexOf(',', comma + 1)) {
            String after = text.substring(comma + 1).stripLeading();
            if (!after.isEmpty() && longestKnownPrefix(after, lowered) >= 0) {
                return comma;
            }
        }
        return -1;
    }

    /**
     * Every name a dependency is allowed to use: the titles proposed in this same reply, and the
     * keys and titles of the stories already in the backlog.
     *
     * <p>Both halves are needed. The prompt lets a story depend on one being proposed beside it —
     * which exists nowhere yet — or on one the backlog already holds, named by its key.
     */
    private static Set<String> knownReferences(ArtifactStore store, UUID projectId, JsonNode root) {
        Set<String> known = new LinkedHashSet<>();
        for (JsonNode node : root.path("proposals")) {
            String title = str(node, "title");
            if (!title.isBlank()) {
                known.add(title);
            }
        }
        for (Story story : store.listStories(projectId)) {
            if (story.key() != null && !story.key().isBlank()) {
                known.add(story.key());
            }
            if (story.title() != null && !story.title().isBlank()) {
                known.add(story.title());
            }
        }
        return known;
    }

    /** The narrative back out of a rendered block — what {@code proposeStory} stores on the story. */
    private static String narrativeOf(String block) {
        for (String line : (block == null ? "" : block).split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("Narrative:")) {
                return stripped.substring("Narrative:".length()).strip();
            }
        }
        return null;
    }

    /**
     * Whether a rendered block describes technical work that delivers no criterion.
     *
     * <p>Read from the block's own "Unblocks R2 — …" lines, which are what an enabler
     * carries instead of "Delivers R2:C1 — …". This used to read a "Kind: ENABLER"
     * header, which meant a Java constant was the first thing on the review screen. That header is
     * still accepted: proposals rendered before the wording changed are sitting in stores, and
     * reading one of those as an ordinary story would send it down a path demanding criteria it
     * has none of.
     */
    private static boolean enabler(FlowProposal proposal) {
        for (String line : orEmpty(proposal.after()).split("\n")) {
            String stripped = line.strip();
            if (stripped.regionMatches(true, 0, "Unblocks ", 0, "Unblocks ".length())
                    || stripped.equalsIgnoreCase("Kind: ENABLER")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records, on each proposal, what would happen if it were applied — and unticks the ones that
     * cannot be.
     *
     * <p>Caught here, while the operator is still reviewing, rather than at Apply. A ref the BRD
     * does not have, or a criterion a living story already claims, is a story that will be refused;
     * discovering that from an error string after ticking eight boxes leaves a half-written batch
     * and no list to go back to. Flagged and left unticked, the default becomes "no" and the
     * operator only acts on what they actually want.
     *
     * @return how many were unticked
     */
    private static int markImpact(ArtifactStore store, UUID projectId,
                                  List<FlowProposal> proposals) {
        Brd brd = store.ensureBrd(projectId);
        java.util.Set<UUID> claimed = BacklogAuthoring.claimedCriterionIds(store, projectId);
        Map<UUID, String> claimedBy = new LinkedHashMap<>();
        for (Story story : store.listStories(projectId)) {
            if (story.state() != com.swarmcoder.domain.StoryState.CANCELLED) {
                for (UUID criterionId : story.criterionIds()) {
                    claimedBy.put(criterionId, story.key());
                }
            }
        }
        int flagged = 0;
        for (FlowProposal proposal : proposals) {
            if (proposal.kind() == FlowProposalKind.CONFLICT) {
                continue;                       // nothing to apply, so nothing to check
            }
            List<String> refs = split(proposal.handle());
            List<String> unknown = new ArrayList<>();
            List<String> taken = new ArrayList<>();
            // Requirements this story names that the operator has not agreed. Listed by HANDLE
            // rather than by ref: "R1, R2 are not agreed" is the sentence the operator can act on,
            // and the action is a button in the Requirements panel with those handles on it.
            Set<String> unagreed = new LinkedHashSet<>();
            for (String ref : refs) {
                if (enabler(proposal)) {
                    BrdRequirement r = requirementFor(brd, ref);
                    if (r == null) {
                        unknown.add(ref);
                    } else if (!r.isAgreed()) {
                        unagreed.add(r.handle() + " (" + label(r) + ")");
                    }
                    continue;
                }
                BrdRequirement owner = requirementOf(brd, ref);
                if (owner != null && !owner.isAgreed()) {
                    unagreed.add(owner.handle() + " (" + label(owner) + ")");
                    continue;
                }
                UUID criterionId = criterionId(brd, ref);
                if (criterionId == null) {
                    unknown.add(ref);
                } else if (claimed.contains(criterionId)) {
                    taken.add(ref + " (" + claimedBy.get(criterionId) + ")");
                }
            }
            if (!enabler(proposal) && refs.isEmpty()) {
                proposal.setImpact("This story names no check, so it delivers nothing the BRD "
                    + "asks for and cannot be written. If the capability is missing, add the "
                    + "requirement first — a story must not carry requirement content of its own.");
                proposal.setAccepted(false);
                flagged++;
                continue;
            }
            // Checked BEFORE the unknown-ref case, because it is the more useful thing to say: the
            // reference is real, the requirement is real, and the only thing missing is the
            // operator's own decision — which is one button away and theirs to make.
            if (!unagreed.isEmpty()) {
                proposal.setImpact("This is work on " + String.join(", ", unagreed)
                    + ", and you have not agreed " + (unagreed.size() == 1 ? "it" : "them")
                    + " yet. Nothing is built from a requirement you have not agreed, so this "
                    + "cannot be written. If you do want it built, agree "
                    + (unagreed.size() == 1 ? "it" : "them") + " in the Requirements panel and "
                    + "run the planner again.");
                proposal.setAccepted(false);
                flagged++;
                continue;
            }
            if (!unknown.isEmpty()) {
                proposal.setImpact("The BRD has no " + String.join(", ", unknown)
                    + ". Either the planner invented it or the BRD has moved since — this cannot be "
                    + "applied as it stands.");
                proposal.setAccepted(false);
                flagged++;
                continue;
            }
            if (!taken.isEmpty() && taken.size() == refs.size()) {
                proposal.setImpact("Every check here is already delivered by "
                    + String.join(", ", taken) + ". Left unticked — tick it only if this really is "
                    + "separate work.");
                proposal.setAccepted(false);
                flagged++;
            } else if (!taken.isEmpty()) {
                // Partly overlapping: a real judgement, so it is a warning rather than a veto.
                proposal.setImpact("Already claimed elsewhere: " + String.join(", ", taken)
                    + ". Applying this would give two stories the same check.");
            }
        }
        return flagged;
    }

    // --- BRD lookups ----------------------------------------------------------------------------

    /** The criterion a "<handle>:C<n>" ref names, or null when the BRD does not have it. */
    private static UUID criterionId(Brd brd, String ref) {
        AcceptanceCriterion criterion = criterion(brd, ref);
        return criterion == null ? null : criterion.id();
    }

    private static String criterionText(Brd brd, String ref) {
        AcceptanceCriterion criterion = criterion(brd, ref);
        return criterion == null ? null : criterion.text();
    }

    private static AcceptanceCriterion criterion(Brd brd, String ref) {
        String[] parts = ref == null ? new String[0] : ref.split(":");
        if (parts.length != 2) {
            return null;
        }
        BrdRequirement requirement = requirementFor(brd, parts[0]);
        if (requirement == null) {
            return null;
        }
        String digits = parts[1].strip().toUpperCase();
        if (digits.startsWith("C")) {
            digits = digits.substring(1);
        }
        int index;
        try {
            index = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        List<AcceptanceCriterion> criteria = requirement.criteria();
        return index < 1 || index > criteria.size() ? null : criteria.get(index - 1);
    }

    private static BrdRequirement requirementFor(Brd brd, String handle) {
        if (handle == null || brd == null || brd.requirements() == null) {
            return null;
        }
        for (BrdRequirement r : brd.requirements()) {
            if (r.handle() != null && r.handle().equalsIgnoreCase(handle.trim())) {
                return r;
            }
        }
        return null;
    }

    // --- plumbing -------------------------------------------------------------------------------

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String part : csv.split("[,\\s]+")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /**
     * The model's reply, tolerantly parsed ({@link LlmJson}) — fenced, prose-prefixed, or
     * double-encoded, the same shapes {@code TestAuthorClient} and {@code ArchitectClient} accept.
     * {@code null} when nothing in the reply reads as JSON at all.
     *
     * <p>Non-throwing: this is what a caller reaches for where a bad reply degrades gracefully
     * rather than failing the flow — the clarifying-question round (no questions is a normal
     * outcome), and the scope-correction re-ask in {@link #insideAgreedScope}, which already keeps
     * the first plan when the corrected one comes back unreadable. The main proposal ask gets the
     * stronger treatment ({@link LlmReplyRetry}) because there a bad reply is worth a retry, not a
     * shrug.
     */
    private static JsonNode json(String reply) {
        if (reply == null) {
            return null;
        }
        try {
            return LlmJson.readTree(JSON, reply);
        } catch (IOException e) {
            log.warn("Planning: model reply was not valid JSON: {}", e.getMessage());
            return null;
        }
    }

    private static String str(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
