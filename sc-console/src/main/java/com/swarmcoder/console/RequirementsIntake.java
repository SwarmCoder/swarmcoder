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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.FlowDiscussionTurn;
import com.swarmcoder.domain.FlowDocument;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowProposalKind;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.FlowQuestionKind;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.SourceRef;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The analysis behind the requirements-intake wizard: read the operator's documents, ask what is
 * genuinely ambiguous, and propose changes to the BRD (docs/GUIDED_FLOWS_DESIGN.md §4–5).
 *
 * <p>It runs on its own thread and reports through {@link GuidedFlows}, so the operator can close the
 * wizard, keep working, and re-open it to find the analysis where they left it. Nothing it produces
 * touches the BRD: it writes {@link FlowProposal}s, and only {@link #apply} — reached by the
 * operator pressing Apply — turns the accepted ones into requirements.
 *
 * <p><b>One round of questions, not an interrogation.</b> The model is asked for its clarifications
 * once, as a batch. A loop that can keep asking is a loop that will, and the operator has no way to
 * tell a productive round from a stalling one; anything still unclear after the round becomes a
 * stated assumption on the requirement, which is visible and correctable.
 */
final class RequirementsIntake {

    private static final Logger log = LoggerFactory.getLogger(RequirementsIntake.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Reading → questions → drafting → review. Shown as "step N of 4" under the progress bar. */
    private static final int TOTAL_STEPS = 4;

    /** Upper bound on one round. More than this is a form nobody fills in. */
    private static final int MAX_QUESTIONS = 12;

    /**
     * The default ceiling on document text sent in one call, in characters (~60k tokens).
     *
     * <p>A ceiling exists because a model has a context window and overrunning it fails the call.
     * It is a DEFAULT, not a law: the operator raises it per analysis when their model can take
     * more. What it must never do is silently shrink the input — this used to truncate at 60,000
     * characters and drop whole documents, so requirements went missing with nobody told.
     */
    static final int DEFAULT_BUDGET_CHARS = 250_000;

    /** Running flows, so {@link #cancel} can stop one mid-model-call. */
    private static final Map<UUID, AtomicBoolean> RUNNING = new ConcurrentHashMap<>();

    private RequirementsIntake() {}

    // --- lifecycle ------------------------------------------------------------------------------

    /**
     * Starts analysis from the beginning. Returns "" immediately — progress arrives on the signal.
     *
     * <p>Everything but the validation happens on the worker thread. A store write and a broadcast
     * are not free, and doing them inline meant the RMI reply waited on them; the browser is sitting
     * blocked on that reply, so a slow publish turns into a request timeout and a button that looks
     * dead. Nothing here may block the caller.
     */
    static String launch(ConsoleContext context, GuidedFlow flow) {
        if (context.analystModel() == null) {
            return "error: no analyst model configured — set roles.requirementsAnalyst "
                + "(or roles.chat) in Settings";
        }
        spawn(context, flow.id(), true, store -> {
            GuidedFlows.clearQuestions(store, flow.id());
            store.saveFlowProposals(flow.id(), List.of());
            flow.setTotalSteps(TOTAL_STEPS);
            GuidedFlows.advance(store, flow, GuidedFlowState.RUNNING, 1, "Reading the documents");
        });
        return "";
    }

    /** Resumes after a round of answers, going straight to drafting. Returns immediately. */
    static String resume(ConsoleContext context, GuidedFlow flow) {
        if (context.analystModel() == null) {
            return "error: no analyst model configured — set roles.requirementsAnalyst "
                + "(or roles.chat) in Settings";
        }
        spawn(context, flow.id(), false, store -> {
            flow.setTotalSteps(TOTAL_STEPS);
            GuidedFlows.advance(store, flow, GuidedFlowState.RUNNING, 3,
                "Drafting requirements from the documents and your answers");
        });
        return "";
    }

    // --- discussing one question ------------------------------------------------------------------

    /**
     * How many characters of a discussion prompt may be the operator's own turns.
     *
     * <p>A side conversation is short by design, but nothing stops one running long, and the
     * documents are already the expensive part of this prompt. Turns are dropped from the OLDEST
     * end when the ceiling is passed, because the recent exchange is what the next reply has to
     * follow; the operator is told, in the prompt, that earlier turns were left out.
     */
    private static final int DISCUSSION_TURN_BUDGET_CHARS = 12_000;

    /**
     * Records what the operator said about one question and asks the analyst to respond. Returns ""
     * immediately — the reply is appended on a worker thread and read back by
     * {@code GuidedFlowService.discussion}.
     *
     * <p>The model call is NOT made on the caller's thread, for the same reason
     * {@link #launch} does not make one: the browser is blocked on the RMI reply, and a model that
     * takes twenty seconds becomes a request timeout and a Send button that appears to do nothing.
     * This uses its own spawn rather than {@link #spawn} because that one enrols the flow in
     * {@link #RUNNING} and drives the whole analysis — a discussion is a side conversation and must
     * not be cancellable by, or cancel, the analysis it is about.
     */
    static String discuss(ConsoleContext context, GuidedFlow flow, FlowQuestion question,
                          String message) {
        if (context.analystModel() == null) {
            return "error: no analyst model configured — set roles.requirementsAnalyst "
                + "(or roles.chat) in Settings";
        }
        ArtifactStore store = context.store();
        store.appendFlowDiscussionTurn(question.id(), new FlowDiscussionTurn(UUID.randomUUID(),
            question.id(), FlowDiscussionTurn.YOU, message.strip(), Instant.now()));
        UUID flowId = flow.id();
        UUID questionId = question.id();
        Thread worker = new Thread(() -> {
            log.info("Discussion on question {} of flow {}: asking the analyst", questionId, flowId);
            String reply;
            try {
                GuidedFlow current = store.getGuidedFlow(flowId);
                FlowQuestion asked = questionOf(store, flowId, questionId);
                if (current == null || asked == null) {
                    // The round was thrown away underneath the conversation. Say so in the
                    // transcript: silence here reads as an analyst that never answered.
                    reply = "That question is no longer part of this analysis — it was cleared or "
                        + "re-run while we were talking.";
                } else {
                    reply = ask(context, discussionPrompt(),
                        discussionBrief(store, current, asked));
                }
            } catch (Exception e) {
                log.warn("Discussion on question {} of flow {} threw", questionId, flowId, e);
                reply = "I could not answer that: " + message(e);
            }
            try {
                store.appendFlowDiscussionTurn(questionId, new FlowDiscussionTurn(UUID.randomUUID(),
                    questionId, FlowDiscussionTurn.ANALYST, reply, Instant.now()));
                GuidedFlow current = store.getGuidedFlow(flowId);
                if (current != null) {
                    // The transcript itself is fetched, not published — see GuidedFlowService
                    // .discussion. Publishing keeps the wizard's own frame current for anyone who
                    // opened it while this was in flight.
                    GuidedFlows.publish(store, current);
                }
            } catch (Exception e) {
                log.warn("Discussion on question {} of flow {}: could not record the reply",
                    questionId, flowId, e);
            }
            log.info("Discussion on question {} of flow {}: replied with {} characters",
                questionId, flowId, reply.length());
        }, "discuss-" + questionId.toString().substring(0, 8));
        worker.setDaemon(true);
        worker.start();
        return "";
    }

    private static FlowQuestion questionOf(ArtifactStore store, UUID flowId, UUID questionId) {
        for (FlowQuestion candidate : store.listFlowQuestions(flowId)) {
            if (questionId.equals(candidate.id())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Everything the analyst needs to explain its own question: the question and its stated
     * background, the documents it was drawn from, the BRD as it stands, and the conversation so far.
     *
     * <p>The documents are the whole point. An analyst asked "what did the document actually say
     * around this?" and given only its own question can do nothing but paraphrase itself more
     * confidently, which is precisely the failure the discussion exists to prevent.
     */
    static String discussionBrief(ArtifactStore store, GuidedFlow flow, FlowQuestion question) {
        StringBuilder sb = new StringBuilder("THE QUESTION YOU ASKED:\n");
        if (question.subject() != null && !question.subject().isBlank()) {
            sb.append("SUBJECT: ").append(question.subject().strip()).append('\n');
        }
        sb.append("QUESTION: ").append(question.text() == null ? "" : question.text().strip())
            .append('\n');
        if (question.sourceQuote() != null && !question.sourceQuote().isBlank()) {
            sb.append("THE WORDING YOU QUOTED: ").append(question.sourceQuote().strip()).append('\n');
        }
        if (question.sourceDocument() != null && !question.sourceDocument().isBlank()) {
            sb.append("FROM DOCUMENT: ").append(question.sourceDocument().strip()).append('\n');
        }
        if (question.background() != null && !question.background().isBlank()) {
            sb.append("THE BACKGROUND YOU GAVE: ").append(question.background().strip()).append('\n');
        }
        if (!question.options().isEmpty()) {
            sb.append("THE OPTIONS YOU OFFERED:\n");
            for (String option : question.options()) {
                sb.append("  - ").append(option).append('\n');
            }
        }
        if (question.answer() != null && !question.answer().isBlank()) {
            sb.append("WHAT THEY HAVE ANSWERED SO FAR: ").append(question.answer().strip())
                .append('\n');
        }
        if (question.note() != null && !question.note().isBlank()) {
            sb.append("THEIR NOTE ALONGSIDE IT: ").append(question.note().strip()).append('\n');
        }

        // The same documents, read through the same budget, as the analysis itself. A discussion
        // that could see more than the run that produced the question would answer from material
        // the question was never drawn from.
        String brief = brief(store, flow);
        int budget = budgetOf(flow);
        sb.append("\nTHE DOCUMENTS THIS ANALYSIS IS READING:\n");
        if (brief.isBlank()) {
            sb.append("(none of the included documents has any extracted text — say plainly that "
                + "you cannot check the wording)\n");
        } else if (brief.length() > budget) {
            // Never silently: the same rule the analysis runs under. Saying what was left out is
            // what stops a confident answer drawn from two thirds of the input.
            sb.append("NOTE: these documents total ").append(brief.length())
                .append(" characters, over this analysis's limit of ").append(budget)
                .append(". You are seeing the first ").append(budget)
                .append(" characters ONLY — if the answer would lie past that point, say so rather "
                    + "than answering from what you can see.\n")
                .append(brief, 0, budget).append('\n');
        } else {
            sb.append(brief).append('\n');
        }

        // The same bounded slice the analysis itself gets (§21). This call site was the one the
        // investigation missed: discussing ONE clarification re-sent the entire requirement graph,
        // once per message the operator typed. Focused on the question rather than the documents,
        // because that is what this conversation is about.
        sb.append('\n').append(BrdSubset.render(store.ensureBrd(flow.projectId()),
            orEmpty(question.subject()) + " " + orEmpty(question.text()) + " "
                + orEmpty(question.sourceQuote())));
        sb.append('\n').append(renderTurns(store.listFlowDiscussion(question.id())));
        return sb.toString();
    }

    /**
     * The conversation so far, newest turns guaranteed to survive the budget.
     *
     * <p>Trimmed from the front rather than the back: a reply that ignores what was just said is
     * useless, whereas one that has forgotten the opening exchange is merely narrower.
     */
    private static String renderTurns(List<FlowDiscussionTurn> turns) {
        int from = 0;
        int total = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            total += turns.get(i).text().length();
            if (total > DISCUSSION_TURN_BUDGET_CHARS) {
                from = i + 1;
                break;
            }
        }
        StringBuilder sb = new StringBuilder("THE CONVERSATION SO FAR "
            + "(they speak as THEM, you as YOU; the last line is what you must respond to):\n");
        if (from > 0) {
            sb.append("(").append(from).append(" earlier turn(s) omitted for length)\n");
        }
        for (FlowDiscussionTurn turn : turns.subList(from, turns.size())) {
            sb.append(turn.fromOperator() ? "THEM: " : "YOU: ").append(turn.text().strip())
                .append('\n');
        }
        return sb.toString();
    }

    /** Signals a running analysis to stop at its next checkpoint. */
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
                log.warn("Intake flow {} threw", flowId, e);
                GuidedFlow flow = context.store().getGuidedFlow(flowId);
                if (flow != null) {
                    GuidedFlows.fail(context.store(), flow, message(e));
                }
            } finally {
                RUNNING.remove(flowId, cancelled);
            }
        }, "intake-" + flowId.toString().substring(0, 8));
        worker.setDaemon(true);
        worker.start();
    }

    // --- the analysis ---------------------------------------------------------------------------

    /**
     * Whether this run includes a document no analysis has read yet.
     *
     * <p>The question round exists to resolve ambiguity in NEW material. Documents already turned
     * into requirements have had theirs resolved, and asking again wastes the operator's attention
     * on ground they have covered.
     */
    private static boolean hasUnreadDocument(GuidedFlow flow) {
        for (FlowDocument entry : flow.documents()) {
            if (!entry.excluded() && !entry.analysed()) {
                return true;
            }
        }
        return false;
    }

    private static void run(ConsoleContext context, UUID flowId, boolean askQuestions,
                            AtomicBoolean cancelled) throws Exception {
        ArtifactStore store = context.store();
        GuidedFlow flow = store.getGuidedFlow(flowId);
        if (flow == null) {
            return;
        }
        String brief = brief(store, flow);
        if (brief.isBlank()) {
            GuidedFlows.fail(store, flow,
                "none of the attached documents has any extracted text to read");
            return;
        }
        log.info("Intake {}: {} characters of document text", flowId, brief.length());
        Brd brd = store.ensureBrd(flow.projectId());
        // A bounded, relevant slice of the graph rather than all of it (§21) — with the sentence
        // that says so, because a model shown part of a document and not told reads an absence as a
        // non-existence. On any graph small enough to send whole, this IS the whole graph.
        String existing = BrdSubset.render(brd, brief);
        boolean trimmed = BrdSubset.isTrimmed(brd);
        log.info("Intake {}: BRD has {} requirement(s); {} characters sent to the analyst{}",
            flowId, BrdSubset.size(brd), existing.length(),
            trimmed ? " (a subset, with search offered)" : "");

        // A re-run over documents that have all been read before has nothing to ask about. Their
        // ambiguities were resolved on the first pass, and the only material the question round
        // would have left is the BRD — its own earlier output — which produces questions asking the
        // operator to referee a disagreement the analyst invented with itself. Re-ticking documents
        // to pick up something missed (relationships, a category) must not restart the interview.
        if (askQuestions && !hasUnreadDocument(flow)) {
            log.info("Intake {}: every included document has been analysed before — skipping the "
                + "question round and going straight to drafting", flowId);
            askQuestions = false;
            GuidedFlows.advance(store, flow, GuidedFlowState.RUNNING, 3,
                "Re-reading documents already analysed — no new questions");
        }

        if (askQuestions) {
            GuidedFlows.advance(store, flow, GuidedFlowState.RUNNING, 2,
                "Looking for anything ambiguous");
            String reply = ask(context, questionPrompt() + searchToolPrompt(trimmed),
                brief + "\n\n" + existing, brd, trimmed);
            if (cancelled.get()) {
                return;
            }
            List<FlowQuestion> questions = parseQuestions(flowId, reply);
            log.info("Intake flow {} asked {} question(s) from a {}-character reply", flowId,
                questions.size(), reply == null ? 0 : reply.length());
            if (questions.isEmpty()) {
                log.debug("Intake flow {} question reply verbatim: {}", flowId, oneLine(reply));
            }
            if (!questions.isEmpty()) {
                store.saveFlowQuestions(flowId, questions);
                GuidedFlows.advance(store, flow, GuidedFlowState.AWAITING_ANSWERS, 2,
                    questions.size() + " question(s) — answer what matters, skip the rest");
                return;
            }
            GuidedFlows.advance(store, flow, GuidedFlowState.RUNNING, 3,
                "Drafting requirements from the documents");
        }

        List<FlowQuestion> asked = store.listFlowQuestions(flowId);
        String answers = renderAnswers(asked);
        String system = proposalPrompt() + searchToolPrompt(trimmed);
        String user = brief + "\n\n" + existing + answers + partsOfTheProject(context);
        String firstReply = ask(context, system, user, brd, trimmed);
        if (cancelled.get()) {
            return;
        }
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", system),
            Map.of("role", "user", "content", user));
        // parse -> retry -> salvage, in that order. jsonOrThrow (the reader below) is STRICT: it
        // must actually fail on a reply only salvage can save, or the retry LlmReplyRetry exists
        // to give the analyst never fires — which is exactly the bug this replaced (harness link
        // 3, 2026-09-03: a reply broken at column 14 salvaged one proposal from a 7,240-character
        // document pair and was counted a success because the old jsonOrThrow tried salvage
        // BEFORE ever signalling failure). Salvage is tried here, ONLY after both the first reply
        // and the one retry have failed strict parsing — see the catch below.
        List<FlowProposal> proposals;
        String rawReply = null;      // the reply proposals came from; carried into the reask below
        String salvageOutcome = null; // set only when the final result rests on salvage
        try {
            LlmReplyRetry.Asked<List<FlowProposal>> analysed = LlmReplyRetry.askJson(
                context.blobStore(), "the analyst's", messages, firstReply,
                m -> call(context, m), r -> parseProposals(flowId, jsonOrThrow(r)));
            proposals = analysed.value();
            rawReply = analysed.reply();
        } catch (MalformedReplyException e) {
            List<FlowProposal> salvaged = salvageProposals(flowId, e.reply());
            if (salvaged.isEmpty()) {
                // Salvage found nothing readable either — a genuinely unparseable reply, not one
                // broken partway through. Fail exactly as before this existed.
                GuidedFlows.fail(store, flow, e.getMessage());
                return;
            }
            String ref = LlmReplyBlobs.store(context.blobStore(), e.reply());
            int attempted = attemptedProposalCount(e.reply());
            log.warn("Intake {}: the analyst's reply was malformed twice; salvage recovered {} of "
                + "{} proposal(s) it appears to contain{}", flowId, salvaged.size(), attempted,
                ref == null ? "" : " (kept as blob " + ref + ")");
            proposals = salvaged;
            // Salvage is not silent success. A thin recovery from a document that plainly had a
            // lot to say is not something the operator should discover only by counting the review
            // list themselves — but a small document salvaging one or two proposals from it is not
            // worth alarming over, and every scripted-analyst test in this codebase is one.
            if (salvaged.size() < 3 && brief.length() > 1000) {
                salvageOutcome = "the analyst's reply was malformed twice; " + salvaged.size()
                    + " proposal" + (salvaged.size() == 1 ? "" : "s") + " could be recovered from it"
                    + (ref == null ? "" : " (kept as blob " + ref + ")");
            }
        }
        if (cancelled.get()) {
            return;
        }
        if (proposals.isEmpty()) {
            // The documents were not empty — that was checked and would have failed the flow
            // already — so an empty batch here is the analyst saying nothing when there was
            // something to say. Ask once more rather than telling the operator their document has
            // nothing in it, which the guard above already disproved. (Only reachable from the
            // parsed-successfully path above: the salvage path above never leaves proposals empty
            // and returns before this point when it does.)
            log.info("Intake {}: the analyst proposed no changes from {} characters of document "
                + "text — asking again", flowId, brief.length());
            List<Map<String, String>> reask = new ArrayList<>(messages);
            reask.add(Map.of("role", "assistant", "content", rawReply == null ? "" : rawReply));
            reask.add(Map.of("role", "user", "content", "You proposed no changes, but the documents "
                + "are not empty — " + brief.length() + " characters of text was provided. Propose "
                + "at least one requirement change that accounts for what they say."));
            String secondReply = call(context, reask);
            if (cancelled.get()) {
                return;
            }
            rawReply = secondReply;   // the conversation's latest assistant turn, for the reask below
            proposals = parseProposals(flowId, secondReply);
            if (proposals.isEmpty()) {
                log.warn("Intake flow {} got no proposals twice. Second reply verbatim: {}",
                    flowId, oneLine(secondReply));
                GuidedFlows.fail(store, flow, "the analyst proposed no changes twice, though the "
                    + "documents hold " + brief.length() + " characters of text");
                return;
            }
        }
        // A technical document states RULES, not features (§ proposalPrompt "THERE IS A THIRD
        // KIND"). Harness run 15 (2026-09-03): the analyst proposed eight requirements and zero
        // CONSTRAINTs from a document that had yielded 8-13 rules on the previous nine runs — a
        // valid, parseable reply that the checks above have no reason to distrust. Ask once more,
        // exactly as the empty-batch reask above does, rather than silently accepting a technical
        // document that stated nothing.
        String ruleReaskOutcome = null;
        String technicalDocument = firstTechnicalDocumentName(store, flow);
        if (technicalDocument != null && !containsConstraint(proposals)) {
            log.info("Intake {}: technical document '{}' was read and the analyst stated no rule "
                + "from it — asking again", flowId, technicalDocument);
            List<Map<String, String>> reask = new ArrayList<>(messages);
            reask.add(Map.of("role", "assistant", "content", rawReply == null ? "" : rawReply));
            reask.add(Map.of("role", "user", "content", "The technical document '" + technicalDocument
                + "' states rules about how the project must be built (sentences with must, never, "
                + "only, do not, forbidden), and you proposed none. Every such sentence is a "
                + "CONSTRAINT proposal with its exact excerpt. Add them to your proposals."));
            String secondReply = call(context, reask);
            if (cancelled.get()) {
                return;
            }
            List<FlowProposal> newConstraints = new ArrayList<>();
            for (FlowProposal candidate : parseProposals(flowId, secondReply)) {
                if (isConstraint(candidate)) {
                    newConstraints.add(candidate);
                }
            }
            if (newConstraints.isEmpty()) {
                log.warn("Intake flow {} stated no rule from '{}' twice. Second reply verbatim: {}",
                    flowId, technicalDocument, oneLine(secondReply));
                ruleReaskOutcome = "the analyst stated no rules from '" + technicalDocument
                    + "' twice";
            } else {
                // The eight requirements from the first reply are kept — the second reply's
                // CONSTRAINTs are ADDED to them, never used to replace the batch.
                proposals = new ArrayList<>(proposals);
                proposals.addAll(newConstraints);
            }
        }
        // AND THE RULES IT DID STATE ARE HELD AGAINST THE DOCUMENT, section by section (live
        // harness runs 56 and 58, 2026-10-01): thirteen rules from a nine-section document, none
        // from its "Tests" section, which said how a test must obtain a service. The test author
        // was then handed the rules without the one written for it. A section that states rules
        // and that no stated rule was drawn from is asked about once, by name. See
        // UncoveredRuleSections.
        if (containsConstraint(proposals)) {
            for (SourceDocument technical : technicalDocuments(store, flow)) {
                List<UncoveredRuleSections.Section> passedOver =
                    UncoveredRuleSections.in(technical.extractedText(), ruleTexts(proposals));
                if (passedOver.isEmpty()) {
                    continue;
                }
                List<String> headings = new ArrayList<>();
                for (UncoveredRuleSections.Section section : passedOver) {
                    headings.add("\"" + section.heading() + "\"");
                }
                log.info("Intake {}: no rule was stated from section(s) {} of '{}' — asking again",
                    flowId, headings, technical.filename());
                List<Map<String, String>> reask = new ArrayList<>(messages);
                reask.add(Map.of("role", "assistant", "content", rawReply == null ? "" : rawReply));
                reask.add(Map.of("role", "user", "content", "The technical document '"
                    + technical.filename() + "' states rules in section(s) "
                    + String.join(", ", headings) + ", and you drew no CONSTRAINT from them. A "
                    + "rule about how tests are written, how a test obtains the code it exercises, "
                    + "how the work is built and checked, or how a screen is put together binds "
                    + "whoever does that work exactly as the other rules bind whoever writes the "
                    + "code. Every sentence in those sections with must, never, only, do not or "
                    + "forbidden is a CONSTRAINT proposal with its exact excerpt. Reply with the "
                    + "same JSON object holding ONLY the new CONSTRAINT proposals for those "
                    + "sections."));
                String sectionReply = call(context, reask);
                if (cancelled.get()) {
                    return;
                }
                Set<String> titles = new HashSet<>();
                for (FlowProposal already : proposals) {
                    titles.add(already.title() == null ? "" : already.title().strip().toLowerCase());
                }
                List<FlowProposal> recovered = new ArrayList<>();
                for (FlowProposal candidate : parseProposals(flowId, sectionReply)) {
                    if (isConstraint(candidate) && titles.add(candidate.title() == null ? ""
                            : candidate.title().strip().toLowerCase())) {
                        recovered.add(candidate);
                    }
                }
                proposals = new ArrayList<>(proposals);
                proposals.addAll(recovered);
                List<UncoveredRuleSections.Section> still =
                    UncoveredRuleSections.in(technical.extractedText(), ruleTexts(proposals));
                log.info("Intake {}: the section reask added {} rule(s) from '{}'; {} section(s) "
                    + "still have none", flowId, recovered.size(), technical.filename(), still.size());
                if (!still.isEmpty()) {
                    List<String> stillHeadings = new ArrayList<>();
                    for (UncoveredRuleSections.Section section : still) {
                        stillHeadings.add("\"" + section.heading() + "\"");
                    }
                    String note = "no rule was stated from " + String.join(", ", stillHeadings)
                        + " in '" + technical.filename() + "', asked twice — read "
                        + (still.size() == 1 ? "that section" : "those sections")
                        + " and state its rules yourself";
                    ruleReaskOutcome = ruleReaskOutcome == null ? note : ruleReaskOutcome + "; " + note;
                }
            }
        }
        // Relationships are validated HERE — once the batch is final, after every reask merge above
        // has had its say, and before anything is written or even saved for review. A dangling
        // handle caught this early is dropped from the one proposal that named it; the batch's other
        // proposals, its own good relationships and the rest of the run are untouched. See
        // dropDanglingRelationships for why apply() must never be the first place this is checked.
        int dangling = dropDanglingRelationships(flowId, proposals, brd);
        int duplicates = markDuplicates(proposals, brd);
        int unanswered = markUnansweredEchoes(proposals, asked);
        markImpact(store, proposals, brd, flow.projectId());
        store.saveFlowProposals(flowId, proposals);
        String label = salvageOutcome != null ? salvageOutcome
            : proposals.size() + " proposed change(s) — review, then apply";
        if (dangling > 0) {
            label += "; " + dangling + " relationship" + (dangling == 1 ? "" : "s")
                + " referred to a handle that does not exist and " + (dangling == 1 ? "was" : "were")
                + " dropped";
        }
        if (duplicates > 0) {
            label += "; " + duplicates + " look like duplicates of requirements the BRD "
                + "already has and are left unticked";
        }
        if (unanswered > 0) {
            label += "; " + unanswered + " restate a question you have not answered "
                + "and are left unticked";
        }
        if (ruleReaskOutcome != null) {
            label += "; " + ruleReaskOutcome;
        }
        GuidedFlows.advance(store, flow, GuidedFlowState.REVIEW, TOTAL_STEPS, label);
    }

    /**
     * Drops every relationship in this batch whose target is neither an existing BRD requirement
     * nor one of this reply's own proposals (a ref like N4, or the handle an EDIT names), and logs
     * one line per drop.
     *
     * <p>This is the fix for harness run 20 (2026-09-03): the analyst's reply named a relationship,
     * "N5 gates N15", whose second handle it never defined anywhere in the same reply. The one bad
     * edge reached {@link #apply}, which correctly refused it — {@code addEdge} has always required
     * both endpoints to exist — but refusing IT meant refusing the whole batch: apply reports one
     * combined result, so a single dangling handle cost the operator every proposal in the run,
     * technical document and all. The fix is not to make apply more forgiving; a bad edge reaching
     * apply is still a bug and still refused there. The fix is that a relationship this obviously
     * broken — pointing at a handle nothing in the reply ever proposed or the BRD ever held — has no
     * business surviving past the moment the reply is read. Everything else in the batch is sound and
     * is kept; only the one broken relationship line is removed from its proposal's block, leaving
     * every field a person would review untouched apart from that one line.
     *
     * @return how many relationships were dropped, for the note on the flow's step label
     */
    private static int dropDanglingRelationships(UUID flowId, List<FlowProposal> proposals, Brd brd) {
        Set<String> known = new HashSet<>();
        int nextHandle = 0;
        for (BrdRequirement r : brd.requirements() == null ? List.<BrdRequirement>of()
                : brd.requirements()) {
            if (r.handle() != null && !r.handle().isBlank()) {
                String handle = r.handle().trim().toUpperCase();
                known.add(handle);
                if (handle.matches("R\\d+")) {
                    nextHandle = Math.max(nextHandle, Integer.parseInt(handle.substring(1)));
                }
            }
        }
        // Every ADD in this reply — ref-labelled or not — is given a real sequential handle the
        // moment it is applied (BrdAuthoring#nextHandle), in this same list order. A model that
        // skips the ref convention and names that handle directly ("R7" for its own new
        // requirement, ahead of it existing) is not naming a dangling target — apply's own edge
        // resolution has always tolerated exactly that, once the requirement exists. Both spellings
        // of a proposal's own eventual identity count as known.
        for (FlowProposal proposal : proposals) {
            if (proposal.handle() != null && !proposal.handle().isBlank()) {
                known.add(proposal.handle().trim().toUpperCase());
            }
            if (proposal.kind() == FlowProposalKind.ADD) {
                known.add("R" + (++nextHandle));
            }
        }
        int dropped = 0;
        for (FlowProposal proposal : proposals) {
            String block = proposal.after();
            if (block == null || block.isBlank()) {
                continue;
            }
            StringBuilder kept = new StringBuilder();
            boolean changed = false;
            for (String line : block.split("\n")) {
                String stripped = line.strip();
                int colon = stripped.indexOf(':');
                RequirementRelation relation = colon < 0 ? null
                    : Draft.relationFor(stripped.substring(0, colon).strip());
                String target = colon < 0 ? null : stripped.substring(colon + 1).strip();
                if (relation != null && target != null && !target.isBlank()
                        && !known.contains(target.trim().toUpperCase())) {
                    dropped++;
                    changed = true;
                    String from = proposal.handle() == null || proposal.handle().isBlank()
                        ? "(new, no ref given)" : proposal.handle();
                    log.warn("Intake {}: dropped relationship {} {} {}: {} is not a proposal or a "
                        + "known requirement", flowId, from, relation.name().toLowerCase(), target,
                        target);
                    continue;
                }
                kept.append(line).append('\n');
            }
            if (changed) {
                proposal.setAfter(kept.toString());
            }
        }
        return dropped;
    }

    /**
     * The first technical document this run actually read — one whose text made it into the
     * briefing, not merely attached and excluded — or null when none of them is marked technical.
     *
     * <p>Deliberately narrower than {@link #technicalDocumentName}, which falls back to the first
     * document of ANY kind for the sentence a stated rule cites at apply time. Here a fallback would
     * be wrong: the whole point is to ask again only when a document that says how the system must
     * be BUILT produced no rule, not whenever any document did.
     */
    private static String firstTechnicalDocumentName(ArtifactStore store, GuidedFlow flow) {
        for (FlowDocument entry : flow.documents()) {
            if (entry.excluded() || !entry.technical()) {
                continue;
            }
            SourceDocument document = store.getSourceDocument(entry.documentId());
            if (document != null && document.filename() != null) {
                return document.filename();
            }
        }
        return null;
    }

    /** Every technical document this run actually read, in the order they were attached. */
    private static List<SourceDocument> technicalDocuments(ArtifactStore store, GuidedFlow flow) {
        List<SourceDocument> documents = new ArrayList<>();
        for (FlowDocument entry : flow.documents()) {
            if (entry.excluded() || !entry.technical()) {
                continue;
            }
            SourceDocument document = store.getSourceDocument(entry.documentId());
            if (document != null && document.filename() != null
                    && document.extractedText() != null && !document.extractedText().isBlank()) {
                documents.add(document);
            }
        }
        return documents;
    }

    /** The excerpt and the wording of every CONSTRAINT in this batch — what each was drawn from. */
    private static List<String> ruleTexts(List<FlowProposal> proposals) {
        List<String> texts = new ArrayList<>();
        for (FlowProposal proposal : proposals) {
            if (!isConstraint(proposal)) {
                continue;
            }
            Draft draft = Draft.parse(proposal.after());
            texts.add(draft.excerpt() == null ? "" : draft.excerpt());
            texts.add(draft.text() == null ? "" : draft.text());
        }
        return texts;
    }

    /** Whether an ADD proposal is a CONSTRAINT rather than an ordinary requirement. */
    private static boolean isConstraint(FlowProposal proposal) {
        return proposal.kind() == FlowProposalKind.ADD && Draft.parse(proposal.after()).isRule();
    }

    /** Whether this batch already states at least one CONSTRAINT. */
    private static boolean containsConstraint(List<FlowProposal> proposals) {
        for (FlowProposal proposal : proposals) {
            if (isConstraint(proposal)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A model reply on one log line, capped. Long enough to see whether the model answered with
     * prose, an empty string, a truncated object or something unparseable; short enough to log.
     */
    private static String oneLine(String reply) {
        if (reply == null) {
            return "<null>";
        }
        String flat = reply.replaceAll("\\s+", " ").strip();
        return flat.length() <= 2000 ? flat : flat.substring(0, 2000) + " …(truncated)";
    }

    /** How many times one analyst call may stop to look something up before it must answer. */
    private static final int MAX_SEARCH_ROUNDS = 4;

    /** The line the analyst writes to use its one tool. */
    private static final String SEARCH_LINE = "SEARCH ";

    /**
     * One analyst call, with the requirement search available when the graph was trimmed.
     *
     * <p>The tool is offered ONLY when something was left out. On a graph small enough to send
     * whole there is nothing to look up, the analyst has already been given everything, and adding
     * a tool protocol to that prompt would be pure cost — a longer prompt, another chance to answer
     * with a tool line instead of an answer, and a behaviour change to the path every project this
     * product has actually been run on.
     */
    private static String ask(ConsoleContext context, String system, String user, Brd brd,
                              boolean searchOffered) throws Exception {
        if (!searchOffered) {
            return ask(context, system, user);
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        String reply = "";
        for (int round = 0; round <= MAX_SEARCH_ROUNDS; round++) {
            reply = call(context, messages);
            String query = searchQuery(reply);
            if (query == null || round == MAX_SEARCH_ROUNDS) {
                if (query != null) {
                    log.info("Intake: analyst asked to search again after {} rounds — telling it to "
                        + "answer with what it has", MAX_SEARCH_ROUNDS);
                }
                return reply;
            }
            // Read-only, always (R11). The tool takes the graph and returns text; there is no store
            // behind it and nothing it could write.
            String result = RequirementSearch.search(brd, query);
            log.info("Intake: analyst searched the BRD for \"{}\" — {} characters back", query,
                result.length());
            messages.add(Map.of("role", "assistant", "content", reply));
            messages.add(Map.of("role", "user", "content", result
                + "\nSearch again if you need to, or give your answer now in the format you were "
                + "asked for."));
        }
        return reply;
    }

    /**
     * The words the analyst wants looked up, when its reply is a search request and nothing else.
     *
     * <p>Only the FIRST non-blank line counts, and only when the whole reply is that request. A
     * reply that already carries the answer and mentions searching in passing is an answer.
     */
    static String searchQuery(String reply) {
        if (reply == null) {
            return null;
        }
        String stripped = reply.strip();
        if (!stripped.regionMatches(true, 0, SEARCH_LINE, 0, SEARCH_LINE.length())) {
            return null;
        }
        int end = stripped.indexOf('\n');
        String line = end < 0 ? stripped : stripped.substring(0, end);
        String query = line.substring(SEARCH_LINE.length()).strip();
        return query.isEmpty() ? null : query;
    }

    /** The tool, described to the analyst — or nothing at all when it has the whole graph. */
    private static String searchToolPrompt(boolean searchOffered) {
        if (!searchOffered) {
            return "";
        }
        return """


            YOU CAN LOOK THINGS UP. This project has more requirements than fit in one message, so \
            the BRD below is a SUBSET. To check whether something already exists, reply with \
            EXACTLY one line and nothing else:

            SEARCH <the words of the requirement you are checking>

            You get back the requirements whose wording is closest to yours, with their handles, and \
            you may then search again or answer. You may search up to 4 times. The search READS the \
            BRD; it cannot change anything, and neither can you — everything you produce is a \
            proposal an operator reviews.

            Search BEFORE you propose anything as new in an area the subset does not cover. Never \
            mention the search to the operator and never put a SEARCH line in the same reply as \
            your answer — a reply is either one search line or your finished answer.""";
    }

    /** One non-streamed model call; returns the whole reply. */
    private static String ask(ConsoleContext context, String system, String user) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        return call(context, messages);
    }

    /** The model call itself, over an already-built conversation. */
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

    // --- apply ----------------------------------------------------------------------------------

    /**
     * Writes the accepted proposals into the BRD. Each becomes its own revision entry, so the
     * living document's history records what intake added rather than one opaque bulk change.
     */
    static String apply(ConsoleContext context, GuidedFlow flow) {
        ArtifactStore store = context.store();
        List<FlowProposal> proposals = store.listFlowProposals(flow.id());
        // Provenance is the first document THIS RUN ACTUALLY READ. A coarse citation is honest —
        // claiming a precise locator the model never gave would be fabricated — but citing a
        // document that was excluded from the run is simply false, and it is what happened once
        // analysed documents started staying attached while being skipped.
        SourceRef source = null;
        for (FlowDocument entry : flow.documents()) {
            if (!entry.excluded()) {
                source = new SourceRef(entry.documentId(), "intake");
                break;
            }
        }
        // The paper a stated rule is recorded against. A rule keeps a trail back to the document
        // somebody wrote it in — that is the one thing the requirements route gave that the rules
        // mechanism did not, and it is kept.
        String ruleDocument = technicalDocumentName(store, flow);
        // A document stated AGAIN replaces what it said before. Done here, before a single rule is
        // written, and only when this batch really is about to state some: re-reading one technical
        // document used to leave a complete extra set of rules behind every time, because the
        // analyst words them differently on each pass and a different wording is a different
        // filename. Four readings of one document had left 44 rules in force where the document
        // says about eleven things — and every copy reached every worker, the architect, the test
        // author and the judge. Nothing is deleted; the superseded rules go RETIRED, keep their
        // files, and are one click away on the Guidelines screen.
        int superseded = statesAnyRule(proposals) ? supersedeRulesFrom(ruleDocument) : 0;
        int applied = 0;
        int[] rules = new int[1];   // how many of them were RULES, for the sentence at the end
        List<String> failures = new ArrayList<>();
        List<PendingEdge> pending = new ArrayList<>();
        // The model's temporary refs (N1, N2…) against the handles they actually became.
        Map<String, String> assigned = new LinkedHashMap<>();
        for (FlowProposal proposal : proposals) {
            if (!proposal.accepted() || proposal.kind() == FlowProposalKind.CONFLICT) {
                continue;
            }
            String error = applyOne(store, flow.projectId(), proposal, source, pending,
                assigned, ruleDocument, rules);
            if (error != null) {
                failures.add(proposal.handle() + ": " + error);
            } else {
                applied++;
            }
        }
        if (applied == 0) {
            // Nothing was written, so the flow stays in REVIEW and can be applied again once the
            // reason is fixed. Advancing to APPLIED here would claim a success that never happened
            // and force the operator to re-run the whole analysis to get back to this list.
            return failures.isEmpty()
                ? "error: nothing is accepted — tick at least one proposed change"
                : "error: nothing could be applied — " + String.join("; ", failures);
        }
        // SECOND PASS, and it has to be second. An edge names both of its endpoints by handle, and
        // a requirement added earlier in this same batch only acquired its handle a moment ago —
        // so writing edges as each proposal was applied would refuse every relationship pointing at
        // anything later in the list, purely because of the order the model happened to emit them
        // in. Deferring until every requirement exists makes within-batch references resolve.
        EdgeOutcome edgeOutcome = writeEdges(store, flow.projectId(), pending, assigned);
        failures.addAll(edgeOutcome.failures());
        // The documents this run read have now become requirements. Mark them, and drop them out
        // of the next run by default: re-reading material already turned into requirements wastes
        // context and is the surest way to produce duplicate proposals.
        Instant now = Instant.now();
        List<FlowDocument> documents = new ArrayList<>();
        for (FlowDocument entry : flow.documents()) {
            if (entry.excluded()) {
                documents.add(entry);
            } else {
                documents.add(new FlowDocument(entry.documentId(), entry.notes(), now, true,
                    entry.technical()));
            }
        }
        flow.setDocuments(documents);

        int rulesStated = rules[0];
        String label = applied + " change(s) applied"
            + (rulesStated == 0 ? ""
                : " — " + rulesStated + " of them " + (rulesStated == 1 ? "a rule" : "rules")
                    + " this project is now held to, in force straight away")
            + (superseded == 0 ? ""
                : ", replacing " + superseded + " rule(s) this document stated before")
            + (failures.isEmpty() ? "" : ", " + failures.size() + " failed")
            + (edgeOutcome.skipped().isEmpty() ? ""
                : "; " + String.join("; ", edgeOutcome.skipped()));
        GuidedFlows.advance(store, flow, GuidedFlowState.APPLIED, TOTAL_STEPS, label);
        return failures.isEmpty() ? "" : "error: " + String.join("; ", failures);
    }

    /**
     * A relationship a proposal asked for, held back until every requirement in the batch exists.
     * Both endpoints are handles rather than ids, because the source end of an ADD does not have an
     * id — or a handle — until the moment it is written.
     */
    private record PendingEdge(String fromHandle, String relation, String toHandle) {}

    /**
     * What writing a batch's relationships came to: edges that failed outright, and edges skipped
     * because an endpoint plainly does not exist (see {@link #writeEdges}).
     */
    private record EdgeOutcome(List<String> failures, List<String> skipped) {}

    /**
     * Writes the batch's relationships once all its requirements exist.
     *
     * <p>A genuine refusal is still REPORTED, not dropped — a relationship the analyst asserted and
     * the operator accepted, which then silently fails to appear, leaves the graph looking merely
     * sparse, and a missing dependency is invisible in exactly the way a wrong one is not, because
     * nothing downstream will ever ask why the edge is not there.
     *
     * <p>But harness run 47 (2026-09-27) is a different shape: the analyst proposed "R5 depends_on
     * N15", where N15 named nothing — not an existing requirement, not one of this batch's own
     * proposals actually written. That handle survives {@code dropDanglingRelationships} at intake
     * whenever SOME proposal in the reply did declare it (the ref was real when the reply was read);
     * it only turns out to be nothing once apply runs and that proposal was never accepted, or
     * failed for its own reason. Treating that the same as a genuine refusal cost the operator the
     * one thing apply is supposed to guarantee: everything else they ticked still lands. So a missing
     * endpoint is checked for HERE, after every accepted proposal has already been written, and is
     * SKIPPED with a plain-word notice rather than added to the failures that make the whole apply
     * report an error. A genuine shape violation (a self-loop, a wrong-direction GATES, …) still
     * reaches {@link BrdAuthoring#addEdge} and fails exactly as before.
     *
     * @return the edges that failed outright, and the edges skipped for a missing endpoint
     */
    private static EdgeOutcome writeEdges(ArtifactStore store, UUID projectId,
                                          List<PendingEdge> pending,
                                          Map<String, String> assigned) {
        List<String> failures = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Brd brd = store.ensureBrd(projectId);
        for (PendingEdge edge : pending) {
            // A target is either a real handle or one of this batch's temporary refs.
            // Resolving through the map is what makes "N4" mean the requirement created a
            // moment ago, which is most of what a first analysis has to say.
            String target = assigned.getOrDefault(
                edge.toHandle() == null ? "" : edge.toHandle().trim().toUpperCase(),
                edge.toHandle());
            boolean fromExists = BrdAuthoring.handleExists(brd, edge.fromHandle());
            boolean toExists = BrdAuthoring.handleExists(brd, target);
            if (!fromExists || !toExists) {
                String missing = !fromExists && !toExists
                    ? edge.fromHandle() + " and " + edge.toHandle() + " do not exist"
                    : (fromExists ? edge.toHandle() : edge.fromHandle()) + " does not exist";
                skipped.add("skipped: " + edge.fromHandle() + " " + edge.relation().toLowerCase()
                    + " " + edge.toHandle() + " — " + missing);
                continue;
            }
            String result = BrdAuthoring.addEdge(store, projectId, edge.fromHandle(),
                edge.relation(), target);
            if (result.startsWith("error:")) {
                failures.add(edge.fromHandle() + " " + edge.relation().toLowerCase() + " "
                    + edge.toHandle() + ": " + result.substring(6).trim());
            }
        }
        return new EdgeOutcome(failures, skipped);
    }

    /** Queues a draft's relationships against the handle the requirement was actually written as. */
    private static void queueEdges(List<PendingEdge> pending, String fromHandle, Draft draft) {
        for (Draft.Link link : draft.relationships()) {
            pending.add(new PendingEdge(fromHandle, link.relation(), link.target()));
        }
    }

    /** @return null on success, else the reason it could not be written */
    private static String applyOne(ArtifactStore store, UUID projectId, FlowProposal proposal,
                                   SourceRef source, List<PendingEdge> pending,
                                   Map<String, String> assigned, String ruleDocument,
                                   int[] rulesStated) {
        if (proposal.kind() == FlowProposalKind.DEPRECATE) {
            // Marked, never removed: the handle, the history and every backlog and commit link
            // against it survive, so work that already shipped stays traceable to what asked for it.
            String result = BrdAuthoring.updateRequirement(store, projectId, proposal.handle(),
                null, null, null, "deprecated");
            return result.startsWith("error:") ? result.substring(6).trim() : null;
        }
        Draft draft = Draft.parse(proposal.after());
        if (proposal.kind() == FlowProposalKind.ADD && draft.isRule()) {
            // A standing rule about how the project is built does NOT become a requirement. It
            // becomes a project guideline: the mechanism SwarmCoder already had for "a rule this
            // project must follow", with a scope, a status, a screen that turns it on and off, and
            // a command that can prove it was obeyed (author decision 2026-08-31). Filing it as a
            // requirement needed nine exemptions to keep it out of machinery that assumes
            // everything in it gets delivered, and every one of them said it did not belong there.
            String error = stateRule(proposal.title(), draft.text, ruleDocument, draft.excerpt,
                draft.purpose, draft.isHardRule(), draft.appliesTo);
            if (error == null) {
                rulesStated[0]++;
            }
            return error;
        }
        if (proposal.kind() == FlowProposalKind.ADD) {
            String result = BrdAuthoring.addRequirement(store, projectId, proposal.title(),
                draft.text, draft.priority, draft.category, draft.kind, draft.nfrCategory, source);
            if (result.startsWith("error:")) {
                return result.substring(6).trim();
            }
            String handle = result.split("\\s+")[0];
            if (proposal.handle() != null && !proposal.handle().isBlank()) {
                assigned.put(proposal.handle().trim().toUpperCase(), handle);
            }
            for (Draft.Check criterion : draft.criteria) {
                BrdAuthoring.addCriterion(store, projectId, handle, criterion.text(), criterion.test());
            }
            queueEdges(pending, handle, draft);
            return null;
        }
        // An EDIT carrying nothing but relationships states structure, not content — so it must not
        // touch a word of the requirement. Passing the title through would rename it to whatever
        // label the model happened to put on a proposal that was never about the wording, and
        // passing the text through would bump the content revision and mark every passing test
        // STALE for a change that did not happen.
        boolean structureOnly = draft.text.isBlank() && draft.criteria.isEmpty()
            && !draft.relationships().isEmpty();
        // EDIT — the handle must already exist; a stale one means the BRD moved under the review.
        String result = structureOnly
            ? ""
            : BrdAuthoring.updateRequirement(store, projectId, proposal.handle(),
                proposal.title(), draft.text, draft.priority, null);
        if (result.startsWith("error:")) {
            return result.substring(6).trim();
        }
        for (Draft.Check criterion : draft.criteria) {
            if (!hasCriterion(store, projectId, proposal.handle(), criterion.text())) {
                BrdAuthoring.addCriterion(store, projectId, proposal.handle(), criterion.text(),
                    criterion.test());
            }
        }
        queueEdges(pending, proposal.handle(), draft);
        return null;
    }

    /** The heading the project's module folders are given under, in the analyst's material. */
    static final String PARTS_HEADING = "PARTS OF THIS PROJECT A RULE MAY BE RECORDED FOR";

    /**
     * The project's module folders, from its tree, for the analyst to name as the part a rule
     * applies to (owner's decision 2026-10-07): one short line per module, and nothing at all
     * when the project has no tree yet or one module, where there is nothing to choose between.
     */
    static String partsOfTheProject(ConsoleContext context) {
        ConsoleContext.GuidelineControl rules =
            context == null ? null : context.guidelineControl();
        List<String> modules = rules == null ? List.of() : rules.ruleScopes();
        if (modules == null || modules.size() < 2) {
            return "";
        }
        return "\n\n=== " + PARTS_HEADING + " (its module folders, from the project's tree) "
            + "===\n" + String.join("\n", modules) + "\n";
    }

    /**
     * Records one stated rule through the seam that owns the project's rules.
     *
     * <p>Refused loudly when nothing wired that seam in. A rule the operator ticked, which then
     * vanished because the console could not reach the project's rules, is exactly the silent
     * nothing this whole change exists to end.
     *
     * @param excerpt the document's own wording the rule was drawn from, verbatim; null when the
     *                analyst gave none to quote
     * @return null on success, else the reason it could not be written
     */
    private static String stateRule(String title, String text, String document, String excerpt,
                                    String purpose, boolean hard, List<String> appliesTo) {
        ConsoleContext.GuidelineControl rules = ConsoleContext.get().guidelineControl();
        if (rules == null) {
            return "this project's rules are not connected to the console, so a rule cannot be "
                + "recorded";
        }
        String result = rules.stateRule(title, text, document, excerpt, purpose, hard, appliesTo);
        return result != null && result.startsWith("error:") ? result.substring(6).trim() : null;
    }

    /**
     * Whether this batch is about to state at least one rule.
     *
     * <p>Asked before anything is written, because superseding a document's earlier statement when
     * nothing is going to replace it would turn every one of that document's rules off and leave
     * the project with none. A batch of pure requirement edits must not touch the rules at all.
     */
    private static boolean statesAnyRule(List<FlowProposal> proposals) {
        for (FlowProposal proposal : proposals) {
            if (proposal.accepted() && proposal.kind() == FlowProposalKind.ADD
                    && Draft.parse(proposal.after()).isRule()) {
                return true;
            }
        }
        return false;
    }

    /** Retires what this document said last time, so its new statement replaces it. */
    private static int supersedeRulesFrom(String document) {
        ConsoleContext.GuidelineControl rules = ConsoleContext.get().guidelineControl();
        if (rules == null || document == null || document.isBlank()) {
            return 0;
        }
        return rules.supersedeRulesFrom(document);
    }

    /**
     * The document a stated rule cites: the first technical one this run read, or failing that the
     * first document of any kind. Null when the run read none, which is possible for an analysis
     * driven entirely from the operator's answers.
     */
    private static String technicalDocumentName(ArtifactStore store, GuidedFlow flow) {
        String fallback = null;
        for (FlowDocument entry : flow.documents()) {
            if (entry.excluded()) {
                continue;
            }
            SourceDocument document = store.getSourceDocument(entry.documentId());
            if (document == null || document.filename() == null) {
                continue;
            }
            if (entry.technical()) {
                return document.filename();
            }
            if (fallback == null) {
                fallback = document.filename();
            }
        }
        return fallback;
    }

    private static boolean hasCriterion(ArtifactStore store, UUID projectId, String handle, String text) {
        Brd brd = store.ensureBrd(projectId);
        for (BrdRequirement r : brd.requirements() == null ? List.<BrdRequirement>of() : brd.requirements()) {
            if (handle != null && handle.equalsIgnoreCase(r.handle())) {
                for (com.swarmcoder.domain.AcceptanceCriterion c : r.criteria()) {
                    if (text.equalsIgnoreCase(c.text())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // --- the briefing ---------------------------------------------------------------------------

    /**
     * Un-ticks any ADD that looks like something the BRD already holds, and says so in its rationale.
     *
     * <p>The model is told not to re-propose existing requirements, and it still does — particularly
     * on a second run over a document it has already read. Flagging rather than dropping is
     * deliberate: a genuine near-duplicate is sometimes a real distinction the operator wants, and
     * silently discarding it would hide a decision. Left unticked, the default becomes "no" and the
     * operator only has to act on the ones they actually want.
     *
     * @return how many were flagged
     */
    private static int markDuplicates(List<FlowProposal> proposals, Brd brd) {
        List<BrdRequirement> existing = brd.requirements() == null
            ? List.of() : brd.requirements();
        int flagged = 0;
        for (FlowProposal proposal : proposals) {
            if (proposal.kind() != FlowProposalKind.ADD) {
                continue;
            }
            String title = proposal.title();
            if (title == null || title.isBlank()) {
                continue;
            }
            String text = Draft.parse(proposal.after()).text;
            List<RequirementSimilarity.Hit> hits = RequirementSimilarity.nearest(title, text,
                existing, RequirementSimilarity.NEAR_MATCH, 1);
            if (hits.isEmpty()) {
                continue;
            }
            BrdRequirement match = hits.get(0).requirement();
            double score = hits.get(0).score();
            proposal.setAccepted(false);
            flagged++;
            if (score >= RequirementSimilarity.SAME_REQUIREMENT) {
                // Not similar — the same requirement said again. Re-cast as a CHANGE to the one
                // that exists, so the operator reads a diff against R7 instead of a second copy of
                // it, and markImpact (which runs next) tells them what agreeing that diff would
                // cost in stale evidence. Still unticked: re-typing a proposal is a machine's
                // reading of two sentences, and nothing is written until a person agrees with it.
                proposal.setKind(FlowProposalKind.EDIT);
                proposal.setBefore(orEmpty(match.title())
                    + (match.text() == null || match.text().isBlank() ? "" : " — " + match.text()));
                proposal.setHandle(match.handle());
                proposal.setRationale("This is " + match.handle() + " said again — the wording is "
                    + "almost the same one. Shown as a change to " + match.handle()
                    + " rather than as a second requirement. Unticked: apply it only if you want "
                    + match.handle() + " REWORDED to this.\n" + orEmpty(proposal.rationale()));
            } else {
                proposal.setRationale("Looks like a duplicate of " + match.handle()
                    + " (\"" + orEmpty(match.title()) + "\"), which the BRD already holds. "
                    + "Left unticked — tick it only if this is genuinely a different "
                    + "requirement.\n" + orEmpty(proposal.rationale()));
            }
        }
        return flagged;
    }

    /**
     * Unticks any ADD that restates a question the operator has NOT answered — author decision
     * §20.1: the analyst may not draft from a question it has not had an answer to.
     *
     * <p><b>Why this is in code and not only in the prompt.</b> The prompt now says it plainly, and
     * the prompt is not enough. The live evidence is one pass in which the analyst asked a good
     * question about a vague sentence — "behave sensibly at the edges of the range we support" —
     * and then drafted "Handle multiplication at the boundaries of the supported integer range"
     * anyway, out of that same sentence. It asked well and drafted badly in one breath, so the
     * model's own judgement cannot be the only thing standing between vagueness and scope.
     *
     * <p><b>What it can and cannot see.</b> It compares wording, by the same matcher that catches
     * near-duplicates: a proposal that reuses the words of an unanswered question and the sentence
     * that question quoted is flagged. A requirement that answers the question in genuinely
     * different words is not, and nothing here judges whether a statement is vague — that is
     * §20.2's gate, at the moment of agreement, which is mechanical and cannot be talked past.
     *
     * <p>Flagged, not dropped, for the reason the duplicate check is: the operator may look at it
     * and decide it is fine. What they may not do is get it without noticing.
     *
     * @return how many were flagged
     */
    private static int markUnansweredEchoes(List<FlowProposal> proposals,
                                            List<FlowQuestion> questions) {
        List<FlowQuestion> open = new ArrayList<>();
        for (FlowQuestion question : questions) {
            if (isUnanswered(question)) {
                open.add(question);
            }
        }
        if (open.isEmpty()) {
            return 0;
        }
        int flagged = 0;
        for (FlowProposal proposal : proposals) {
            if (proposal.kind() != FlowProposalKind.ADD || !proposal.accepted()) {
                continue;                       // a CONFLICT states the problem; an unticked
            }                                   // proposal is already stopped
            String text = Draft.parse(proposal.after()).text;
            for (FlowQuestion question : open) {
                // The sentence the question QUOTED is the sharpest signal there is: it is the
                // document's own wording, and a proposal that merely restates it says it back
                // almost word for word. Where the analyst quoted nothing, its own subject and
                // question stand in.
                String wording = question.sourceQuote() != null
                        && !question.sourceQuote().isBlank()
                    ? question.sourceQuote()
                    : orEmpty(question.subject()) + " " + orEmpty(question.text());
                if (RequirementSimilarity.containment(proposal.title(), text, wording)
                        < RequirementSimilarity.DRAWN_FROM) {
                    continue;
                }
                proposal.setAccepted(false);
                proposal.setRationale("This is drawn from something you have not answered yet: \""
                    + orEmpty(question.text()).strip() + "\" Written without that answer it would "
                    + "be scope nobody can pin down. Answer the question and run the analysis "
                    + "again, or tick this if you want it as it stands.\n"
                    + orEmpty(proposal.rationale()));
                flagged++;
                break;
            }
        }
        return flagged;
    }

    /**
     * Records, on each EDIT and DEPRECATE, what applying it would disturb downstream.
     *
     * <p>Author decision 2026-07-27: warn, do not block. Changing a requirement's wording bumps its
     * content revision, which makes every criterion that was PASSING against the old wording read
     * STALE — so the requirement leaves IMPLEMENTED and has to be verified again. That is correct
     * behaviour and the operator is entitled to trigger it; what they are not entitled to is to
     * trigger it unknowingly, from a tick box in a list of twenty.
     */
    private static void markImpact(ArtifactStore store, List<FlowProposal> proposals, Brd brd,
                                   UUID projectId) {
        List<com.swarmcoder.domain.Story> stories;
        try {
            stories = store.listStories(projectId);
        } catch (Exception e) {
            stories = List.of();
        }
        for (FlowProposal proposal : proposals) {
            if (proposal.kind() != FlowProposalKind.EDIT
                    && proposal.kind() != FlowProposalKind.DEPRECATE) {
                continue;
            }
            BrdRequirement target = requirementByHandle(brd, proposal.handle());
            if (target == null) {
                proposal.setImpact("No requirement " + proposal.handle() + " exists - this cannot "
                    + "be applied. The BRD may have changed since the analysis ran.");
                proposal.setAccepted(false);
                continue;
            }
            // The sentence itself is RequirementImpact's, in sc-domain, and so is the hand-edit
            // path's warning in the requirements editor. It was written out here and nowhere else,
            // which is why the editor - the surface an operator uses far more often - warned about
            // nothing at all (25.3). Two wordings of one fact is how they drift.
            com.swarmcoder.domain.RequirementImpact impact =
                com.swarmcoder.domain.RequirementImpact.of(target, stories);
            String sentence = proposal.kind() == FlowProposalKind.DEPRECATE
                ? impact.retireSentence() : impact.editSentence();
            if (!sentence.isEmpty()) {
                proposal.setImpact(sentence);
            }
        }
    }

    private static BrdRequirement requirementByHandle(Brd brd, String handle) {
        if (handle == null || handle.isBlank()) {
            return null;
        }
        for (BrdRequirement requirement : brd.requirements() == null
                ? List.<BrdRequirement>of() : brd.requirements()) {
            if (handle.trim().equalsIgnoreCase(requirement.handle())) {
                return requirement;
            }
        }
        return null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * The documents and the operator's notes about them, exactly as the model sees them.
     *
     * <p>Nothing is truncated. If the documents do not fit the budget the analysis is refused
     * before it starts (see {@link #tooLarge}), because a requirements analysis that quietly read
     * two thirds of the input is worse than one that did not run: it produces a plausible,
     * confident, incomplete BRD and no indication that anything is missing.
     */
    static String brief(ArtifactStore store, GuidedFlow flow) {
        StringBuilder sb = new StringBuilder();
        for (FlowDocument entry : flow.documents()) {
            if (entry.excluded()) {
                continue;               // already absorbed, or deliberately held back
            }
            SourceDocument document = store.getSourceDocument(entry.documentId());
            if (document == null || document.extractedText() == null
                    || document.extractedText().isBlank()) {
                continue;
            }
            sb.append(entry.technical() ? "=== TECHNICAL DOCUMENT: " : "=== DOCUMENT: ")
                .append(document.filename()).append(" ===\n");
            if (entry.technical()) {
                // Labelled per document, not once at the top, because a briefing routinely
                // holds both kinds and the analyst must know which one it is reading where.
                sb.append("THIS DOCUMENT SAYS HOW THE SYSTEM MUST BE BUILT, not what it must "
                    + "do. Nearly everything in it is a standing RULE, so propose it as a CONSTRAINT.\n");
            }
            if (entry.notes() != null && !entry.notes().isBlank()) {
                // The operator's instructions for this document outrank the document itself.
                sb.append("OPERATOR NOTE (authoritative, follow it): ")
                    .append(entry.notes().strip()).append('\n');
            }
            String by = document.extractedBy();
            if (by != null && by.startsWith("vision")) {
                sb.append("NOTE: this text is a model's READING of an image, not the document "
                    + "itself, and may be wrong. Treat anything drawn from it as lower confidence.\n");
            }
            sb.append(document.extractedText()).append("\n\n");
        }
        return sb.toString();
    }

    /** Total characters of document text the NEXT run would send — the included ones only. */
    static int totalCharacters(ArtifactStore store, GuidedFlow flow) {
        int total = 0;
        for (FlowDocument entry : flow.documents()) {
            if (entry.excluded()) {
                continue;
            }
            SourceDocument document = store.getSourceDocument(entry.documentId());
            if (document != null && document.extractedText() != null) {
                total += document.extractedText().length();
            }
        }
        return total;
    }

    /** How many documents the next run would read. */
    static int includedDocuments(GuidedFlow flow) {
        int count = 0;
        for (FlowDocument entry : flow.documents()) {
            if (!entry.excluded()) {
                count++;
            }
        }
        return count;
    }

    /** The flow's ceiling, falling back to the default when the operator has not set one. */
    static int budgetOf(GuidedFlow flow) {
        return flow.characterBudget() > 0 ? flow.characterBudget() : DEFAULT_BUDGET_CHARS;
    }

    /**
     * Why the analysis cannot start, or null if it can. The operator is told the two numbers and
     * both ways out — raise the ceiling, or take a document out.
     */
    static String tooLarge(ArtifactStore store, GuidedFlow flow) {
        int total = totalCharacters(store, flow);
        int budget = budgetOf(flow);
        if (total <= budget) {
            return null;
        }
        return "error: the documents total " + total + " characters, over this analysis's limit of "
            + budget + ". Nothing will be read partially — raise the limit if your model's context "
            + "can take it, or remove a document.";
    }

    /** True when the operator has neither answered this question nor deliberately skipped it. */
    static boolean isUnanswered(FlowQuestion question) {
        return question != null && !question.skipped()
            && (question.answer() == null || question.answer().isBlank());
    }

    /** The answered and skipped questions, told to the model as constraints on what it may assume. */
    private static String renderAnswers(List<FlowQuestion> questions) {
        if (questions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nCLARIFICATIONS YOU ASKED FOR:\n");
        for (FlowQuestion q : questions) {
            sb.append("- ").append(q.text()).append('\n');
            if (q.sourceQuote() != null && !q.sourceQuote().isBlank()) {
                sb.append("  CONTEXT: ").append(q.sourceQuote().strip()).append('\n');
            }
            if (isUnanswered(q)) {
                // Author decision §20.1: an unanswered question does not silently become scope.
                // This used to read "choose the most defensible reading and say so in an ASSUMPTION
                // line", and that is exactly how a sentence nobody could pin down became a
                // requirement no test could ever prove.
                sb.append("  STILL UNANSWERED — DO NOT WRITE A REQUIREMENT FROM THIS. Not as an "
                    + "assumption, not as a placeholder, not in weaker words. It stays an open "
                    + "question until they answer it. Propose nothing that rests on it.\n");
            } else if (q.skipped()) {
                // Skipping is an answer: the operator read it and decided it did not matter enough
                // to settle, which is permission to take the most defensible reading and say so.
                sb.append("  SKIPPED, SO NOT ANSWERED — they read it and chose not to settle it, "
                    + "which is permission to proceed. Take the most defensible reading and state "
                    + "it in the requirement's ASSUMPTION line.\n");
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
            You are a requirements analyst reading a client's documents before writing a Business \
            Requirements Document. Your ONLY job right now is to identify what is genuinely \
            ambiguous — the things where two competent implementers would build different systems.

            Ask about: contradictions between or within documents, quantities left unstated where \
            the number changes the design (volumes, latencies, retention, concurrency), scope \
            boundaries, and who the actors are when it is unclear.

            NEVER ask whether something in a document should be built. If the document states a \
            requirement, it is required — that is what a requirements document IS. "Should the \
            system send the report by email?" when §8 says it sends the report by email is not a \
            clarification, it is asking the operator to re-read their own document back to you, and \
            it is the single most common way this goes wrong.

            Also never ask: anything the documents already answer; anything a competent analyst \
            would infer; preferences that do not change what gets built; implementation choices \
            that are yours to make; or confirmation that you have understood something correctly.

            NEVER ask the operator to adjudicate between a document and the CURRENT BRD. The BRD \
            below is YOUR OWN OUTPUT from an earlier pass over these same documents. "req.md says \
            30 days but the BRD adds a configurable period — which?" is you asking the operator to \
            referee a disagreement you invented. Where the BRD has drifted from a document, that is \
            an EDIT or a CONFLICT proposal in the review step, where it can be seen as a diff and \
            accepted or rejected. It is not a question. Questions are about ambiguity in the SOURCE \
            DOCUMENTS, and nowhere else.

            A MANDATED TECHNOLOGY IS NOT A QUESTION. When a document says the system must use a \
            particular language, framework or service, that is settled — record it as a \
            constraint and move on. Do not ask whether to use it, do not ask the operator to \
            confirm it, and do not ask them to characterise it for you. If you do not know what a \
            named technology is, say so in the requirement's ASSUMPTION line; that is not the \
            operator's homework.

            BUT DO CHALLENGE WHAT IS NOT THERE AT ALL. Ambiguity is not the only thing worth \
            asking about; silence is the other, and it is the more expensive one. Above, a document \
            marked "TECHNICAL DOCUMENT" says how the system must be built. Look for one, and read \
            what it settles.

            Ask — ONCE, in a single question, with the specifics you are missing named in it — \
            when NOTHING in any document settles how this system is built. Concretely, when none \
            of them says: the language and the build tool; how data is stored, and whether that is \
            a database at all; how the parts of the system talk to each other; how the user \
            interface is produced; or anything the project must NOT use. Say plainly why you are \
            asking: with none of that written down, everyone who works on this project guesses, \
            and what they guess is whatever a project of this shape usually is. That is how a \
            project ends up with tests written against frameworks it does not contain and never \
            will.

            Do NOT ask this when the documents already settle it, and do NOT ask it once per \
            missing detail — one question, listing what is missing, and offer the operator the \
            option of attaching the technical document they already have rather than typing it \
            out. If the documents settle SOME of it, ask only about the rest.

            Before you emit a question, test it: would two competent implementers, both having \
            read these documents, actually build DIFFERENT systems depending on the answer? If not, \
            drop it. If you can pick the more defensible reading yourself, do that and state it as \
            an assumption instead — an assumption on the requirement is visible and correctable, \
            and costs the operator nothing to read.

            **Asking nothing is a correct and common answer.** A clear document deserves \
            {"questions":[]} and no apology for it. Ten questions on a document that raises three \
            genuine ambiguities is worse than three, because the operator stops reading them.

            EVERY question MUST carry the background needed to answer it. The operator has not \
            memorised the document and must not have to go and find the section.

            - "sourceQuote": one or two sentences of the actual wording that raised the question, \
              quoted verbatim. Where two passages disagree, quote both. Where the source is an \
              image, a diagram or a transcription and there is no wording to quote, describe in one \
              sentence what it shows. Never write a bare cross-reference like "see section 4" — \
              that is the failure this field exists to prevent.
            - "sourceDocument": the file it came from.
            - "background": the fuller explanation, a short paragraph, read on demand rather than \
              inline so it can afford the length. What the document conveys around this point, why \
              it is ambiguous, and what turns on the answer. This is where an image-sourced \
              question earns its keep: explain what the diagram or photograph shows when you cannot \
              quote it, and say plainly that the source was an image and your reading of it may be \
              imperfect.

            Reply with JSON ONLY, no prose and no code fence:
            {"questions":[{"subject":"short label — what this concerns","text":"the question",\
            "sourceQuote":"the wording this came from","sourceDocument":"the file it is in",\
            "background":"the fuller explanation, read on demand",\
            "kind":"CHOICE","options":["...","..."]}]}

            Use "kind":"CHOICE" with 2-4 options when the answer is closed-ended, otherwise \
            "kind":"TEXT" and omit options. At most %d questions. If nothing is genuinely \
            ambiguous, reply {"questions":[]}.""".formatted(MAX_QUESTIONS);
    }

    /**
     * The side conversation about one question the analyst already asked.
     *
     * <p>Its job is narrow and it is told so bluntly, because the obvious failure is scope creep: an
     * analyst invited to discuss a requirement will start renegotiating it, and a change agreed in a
     * chat window is a change that never went through the review step and that nobody can see as a
     * diff.
     */
    private static String discussionPrompt() {
        return """
            You are the requirements analyst who asked the question below, and the operator wants to \
            talk about it before answering. Everything you need is in the message that follows: the \
            question, the wording you quoted, the documents this analysis is reading, the current \
            BRD, and the conversation so far.

            YOU ARE EXPLAINING A QUESTION YOU ASKED, NOT RENEGOTIATING THE REQUIREMENTS. You may \
            quote the documents, say what the answer would change, and lay out the options as you \
            see them. You may NOT propose, draft, edit or promise a change to the BRD — that happens \
            in the review step, where every change is shown as a diff and the operator accepts or \
            rejects it. A change agreed here is a change nobody can see and nobody approved.

            ANSWER FROM THE DOCUMENTS. When the operator asks what a document actually says, go and \
            find the wording and quote it, naming the file. If the documents do not settle the \
            point, SAY SO PLAINLY — "the documents do not say" is a complete and useful answer. Do \
            not invent a plausible reading and present it as what the document meant: the operator \
            is talking to you precisely because the documents were unclear, and a confident \
            fabrication here is worse than the ambiguity it papers over. Where you are inferring \
            rather than reading, say which.

            WHY YOU ASKED is a fair question and usually the real one. Answer it concretely: what \
            two implementers would build differently, what breaks if the answer goes the other way, \
            what you would have to assume if it goes unanswered.

            IF THEIR MESSAGE EFFECTIVELY ANSWERS THE QUESTION, say so and restate their answer in \
            ONE line, as they would want it recorded, so it can be captured onto the question \
            without them typing it again. Do not restate an answer they have not given.

            BE BRIEF. This is a side conversation, not an essay — a few sentences, or a short list \
            where the options genuinely are a list. Reply in plain prose. No JSON, no headings, no \
            preamble about what you are about to do.""";
    }

    private static String proposalPrompt() {
        return """
            You are a requirements analyst. From the documents and any answers given, propose \
            CHANGES to the current BRD. You are not writing it directly — an operator reviews every \
            proposal and applies the ones they accept.

            READ THE CURRENT BRD BELOW FIRST. Each requirement carries its handle. Do NOT propose \
            an ADD for anything it already covers — that is the single most common way this goes \
            wrong, and it produces a BRD full of near-identical entries the operator then has to \
            weed by hand. If a document restates an existing requirement, emit nothing. If it \
            changes one, emit an EDIT against that handle. Only genuinely new ground gets an ADD. \
            If the BRD is empty, everything is new. Read the heading above it: on a large project \
            it is a SUBSET, and a requirement you cannot see there still exists.

            NEVER DRAFT FROM A QUESTION NOBODY HAS ANSWERED. Below, each clarification you asked \
            for is marked answered, skipped, or still unanswered. A STILL UNANSWERED one is not \
            yours to resolve: do not propose a requirement about it — not as an assumption, not as \
            a placeholder, not in vaguer words that hide the gap. Restating the ambiguous sentence \
            as a requirement, in the shape of "handle X sensibly at the boundaries", is the exact \
            failure this rule exists to stop, because nothing could ever test it. Leave that ground \
            alone and propose everything else. A SKIPPED question is different: they read it and \
            chose not to settle it, so take the most defensible reading and say so in an ASSUMPTION \
            line.

            READ EVERY DOCUMENT, not only the longest. Each is delimited by "=== DOCUMENT: ... ===". \
            A short pasted note carries as much weight as a formal specification — often more, \
            because someone took the trouble to write it out.

            NON-FUNCTIONAL REQUIREMENTS COUNT. Performance and latency targets, security, privacy \
            and anonymity constraints, storage and retention rules, availability, accessibility, \
            compliance — all of these are requirements and must be proposed as NON_FUNCTIONAL with \
            a category. Do not skip them because they read like implementation detail: they are \
            measurable properties of the system, someone decided them deliberately, and dropping \
            one loses that decision.

            THERE IS A THIRD KIND, AND IT IS THE ONE THAT GOES WRONG. Use \
            "requirementKind":"CONSTRAINT" for a standing RULE about how the system must be BUILT, \
            as opposed to something it must DO or a quality it must MEET. Mandated languages, \
            frameworks, platforms and libraries; forbidden ones; the module layout; how the parts \
            talk to each other; how data is stored; how it is packaged and built; how its tests are written and how a test \
            obtains the code it exercises; how a screen is put together; a rule about the \
            technology that catches people out. Everything in a document marked "TECHNICAL \
            DOCUMENT" is almost certainly one of these.

            The difference is simple and it is not stylistic:

            - A FUNCTIONAL or NON_FUNCTIONAL requirement is DELIVERED. Somebody builds it, a task \
              is assigned it, a test proves it, and one day it is done.
            - A CONSTRAINT is never delivered and never done. Nobody can be assigned "the system \
              is pure Java with no SQL in it". It applies to every piece of work on the project, \
              for as long as the project exists, and it is either being obeyed or it is being \
              broken.

            Filing a rule as an ordinary requirement is not a small mistake. Every requirement's \
            checks must be claimed by some task, so a rule filed as a requirement demands that some \
            task DELIVER it, no task can, and the build stops and waits for a person.

            GIVE A CONSTRAINT NO CRITERIA AND NO TEST. Leave "criteria" empty — it is the one kind \
            of requirement that has none, because there is nothing to finish. Put the whole rule in \
            "text", including the REASON where the document gives one: a rule with its reason is \
            obeyed and a rule without one is worked around. Keep the wording the document used; do \
            not compress "saving an object does not save the objects inside it, so every level you \
            changed needs its own save call or the edit is silently lost" into "use correct \
            persistence". The detail IS the requirement. Leave "nfrCategory" and "category" empty.

            GIVE EVERY CONSTRAINT AN "excerpt": the exact sentence(s) of the document you drew it \
            from, copied verbatim — every backtick, every code-formatted identifier, every Maven \
            coordinate, exactly as the document wrote it. "text" is YOUR statement of the rule and \
            may compress or reorder it; "excerpt" may not — it is proof, not paraphrase, and it is \
            how a missing dependency the document names gets caught even when your own wording of \
            the rule leaves the name out. Never invent an excerpt or quote from memory: copy only \
            what the document in front of you actually says.

            GIVE EVERY CONSTRAINT A "purpose" AND A "strength". "purpose" is ONE line saying why \
            the rule exists — the problem it prevents ("so the browser and the server can always \
            decode what crosses the wire"), not the rule again in other words. It is what the work \
            is judged by: a change that differs from the rule's letter but causes none of that \
            problem has not broken it. "strength" is "hard" ONLY where the document states the \
            rule as a MUST, forbids something outright, or fixes part of the stack (a mandated \
            language, framework, store or protocol); everything else, and anything you are unsure \
            of, is "preference". A hard rule stops the work when it is broken; a preference only \
            costs quality.

            GIVE A CONSTRAINT AN "appliesTo" ONLY WHEN THE DOCUMENT SAYS WHICH PART IT IS ABOUT. \
            Where a heading "PARTS OF THIS PROJECT A RULE MAY BE RECORDED FOR" is given below, \
            "appliesTo" is a list of folders copied exactly from it: the parts whose code the rule \
            governs. A rule is then sent only to work on those parts. Leave it out for a rule \
            about the whole project, for a rule about how parts fit together, and whenever the \
            document does not say or you are unsure: a rule with no "appliesTo" goes to everyone, \
            which is always safe. Never write a folder that is not in the list.

            One rule per proposal, not one per document. "Do not use Spring, JPA, Flyway, SQL, \
            REST, JSON, JavaScript or Vaadin" is one constraint about what is forbidden; the module \
            layout is another; how the browser talks to the server is another.

            Rules:
            - A requirement states WHAT must be true, not how to build it. No technology choices \
              OF YOUR OWN — but a technology the documents MANDATE is a CONSTRAINT you must record.
            - Every requirement except a CONSTRAINT needs at least one acceptance criterion: an \
              observable, testable statement. "The system is fast" is not one; "a search over 1M \
              rows returns in under 300ms at p95" is. A CONSTRAINT has none, ever.
            - NAME THE TEST FOR EVERY CRITERION. Give each criterion a "test": the acceptance test \
              that will prove it. The test does not exist yet — you are proposing its NAME, which \
              the test author will then be told to write exactly. Acceptance tests always live in \
              the package "swarm.accept", one class per feature area, so the name is always of the \
              form "swarm.accept.<Area>Test#<methodName>", for example \
              "swarm.accept.GuestCheckoutTest#checksOutWithoutAnAccount". Use lowerCamelCase for \
              the method and make it say what the criterion says. Criteria of the SAME requirement \
              normally share one class and differ only in the method. This is a proposal: the \
              operator sees it and can change it before anything is built.
            - Where you had to decide something the documents did not settle, add an ASSUMPTION \
              line saying what you assumed and why. Never present an assumption as a fact.
            - Non-functional requirements need a category: PERFORMANCE, SECURITY, RELIABILITY, \
              USABILITY, MAINTAINABILITY, COMPLIANCE, PORTABILITY, OBSERVABILITY.
            - If a document contradicts an existing requirement, emit a CONFLICT rather than \
              silently choosing a side. The operator decides.

            RELATIONSHIPS BETWEEN REQUIREMENTS. A BRD is a graph, not a list, and the edges are \
            what makes it one. Where the documents establish a relationship between a requirement \
            you are proposing and one that ALREADY EXISTS in the BRD above, say so in \
            "relationships".

            - Only assert a relationship the documents actually support. A guessed dependency graph \
              is worse than none at all, because it will be used to SEQUENCE the work: a dependency \
              you invented will delay real work behind imaginary work, and one you invented in the \
              wrong direction will schedule things in an order that cannot be built. When in doubt, \
              emit nothing — an absent edge is a gap someone can fill, a wrong edge is a plan.
            - "DEPENDS_ON" means this requirement CANNOT BE DELIVERED until the other one is. It \
              does not mean "these are related", "these are in the same area" or "these mention the \
              same thing". If the two could be built in either order, there is no dependency.
            - "REFINES" means this requirement decomposes or details the other one. \
              "DERIVED_FROM" means you inferred this one from the other. "CONFLICTS_WITH" means the \
              two cannot both be fully met. "GATES" means this non-functional requirement \
              constrains the other one and everything under it.
            - Give EVERY "ADD" proposal a "ref": a short label unique within this reply — N1, N2, \
              N3 and so on. It is a temporary name for a requirement that has no handle yet.
            - A "target" is either a handle that already exists in the BRD above (R7) or the "ref" \
              of another ADD in this same reply (N4). Both are resolved when the operator applies. \
              Most of a first analysis is new requirements relating to EACH OTHER, so refs are the \
              normal case, not the exception — a BRD with no edges is a list pretending to be a \
              graph. Never invent a handle like "R23" for something you are proposing; use its ref.

            - **A relationship is a change worth proposing on its own.** If the requirements in the \
              BRD are already correct but the graph between them is empty, do NOT conclude there is \
              nothing to do. Emit an EDIT for the requirement the relationship starts from, giving \
              its handle and ONLY the "relationships" array — no title, no text, no criteria. That \
              records the structure without touching a word of the wording, and it is the whole \
              point of running the analysis again over a BRD you already trust.

            Use kind "ADD" for a new requirement, "EDIT" to change an existing one (give its \
            existing handle, e.g. R7), "DEPRECATE" when the documents plainly SUPERSEDE or WITHDRAW \
            an existing requirement, "CONFLICT" when two statements cannot both hold.

            Be sparing with DEPRECATE. A document not mentioning something is NOT evidence that it \
            is gone — most documents cover one area, and requirements the operator wrote by hand \
            may have no document behind them at all. Propose it only where a document says the \
            thing is no longer required, replaces it with something incompatible, or describes a \
            process that has plainly moved on. Quote the wording that supports that in the \
            rationale. Give the handle; leave criteria empty.

            Reply with JSON ONLY, no prose and no code fence:
            {"proposals":[{"kind":"ADD","ref":"N1","title":"short title",\
            "rationale":"why, citing the document","priority":"HIGH",\
            "requirementKind":"FUNCTIONAL|NON_FUNCTIONAL|CONSTRAINT",\
            "nfrCategory":"","category":"","text":"the requirement statement, plus any ASSUMPTION \
            line","excerpt":"for a CONSTRAINT only: the document's own sentence(s), verbatim",\
            "purpose":"for a CONSTRAINT only: why the rule exists, one line",\
            "strength":"for a CONSTRAINT only: hard|preference",\
            "appliesTo":["for a CONSTRAINT only, and optional: a folder from the list of parts"],\
            "criteria":[{"text":"observable criterion",\
            "test":"swarm.accept.AreaTest#methodName"}],\
            "relationships":[{"relation":"DEPENDS_ON","target":"R3"}]}]}

            Omit "relationships" entirely when there are none — that is the normal case.

            For "EDIT" also give "before": the current wording you are replacing. For "CONFLICT", \
            put the two conflicting statements in "before" and "after" and leave criteria empty.""";
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
                blankToNull(str(node, "sourceQuote")), blankToNull(str(node, "sourceDocument")),
                blankToNull(str(node, "background")), kind,
                kind == FlowQuestionKind.CHOICE ? options : new ArrayList<>(), null, null, false));
        }
        return out;
    }

    private static List<FlowProposal> parseProposals(UUID flowId, String reply) {
        JsonNode root = json(reply);
        return root == null ? new ArrayList<>() : parseProposals(flowId, root);
    }

    /** As above, over an already-parsed root — what a caller reaches for once it has one in hand. */
    private static List<FlowProposal> parseProposals(UUID flowId, JsonNode root) {
        List<FlowProposal> out = new ArrayList<>();
        for (JsonNode node : root.path("proposals")) {
            FlowProposalKind kind = switch (str(node, "kind").toUpperCase()) {
                case "EDIT" -> FlowProposalKind.EDIT;
                case "DEPRECATE" -> FlowProposalKind.DEPRECATE;
                case "CONFLICT" -> FlowProposalKind.CONFLICT;
                default -> FlowProposalKind.ADD;
            };
            String title = str(node, "title");
            String after = kind == FlowProposalKind.CONFLICT || kind == FlowProposalKind.DEPRECATE
                ? str(node, "after") : Draft.render(node);
            if (title.isBlank() && after.isBlank()) {
                continue;
            }
            // Pre-accepted for ADD and EDIT so the common case is one click on Apply. A CONFLICT
            // never is, because accepting one is choosing a side; nor is a DEPRECATE, because
            // retiring a requirement on the strength of a document's silence is exactly the
            // judgement the operator has to make themselves.
            boolean preAccepted = kind == FlowProposalKind.ADD || kind == FlowProposalKind.EDIT;
            // An ADD has no handle yet, so its `handle` slot carries the model's temporary ref
            // (N1, N2…) instead. That is what lets a relationship point at something else in the
            // same batch; apply maps each ref onto the handle the requirement actually gets.
            String label = kind == FlowProposalKind.ADD && !str(node, "ref").isEmpty()
                ? str(node, "ref") : str(node, "handle");
            out.add(new FlowProposal(UUID.randomUUID(), flowId, kind, label, title,
                blankToNull(str(node, "before")), after, str(node, "rationale"), null,
                preAccepted));
        }
        return out;
    }

    /**
     * The model's reply, read tolerantly — never throwing, for a caller where a bad reply degrades
     * gracefully (the question round: no questions is a normal outcome) rather than failing the
     * flow. {@code null} when nothing in it could be read at all, not even a fragment.
     */
    private static JsonNode json(String reply) {
        try {
            return jsonOrThrow(reply);
        } catch (IOException e) {
            log.warn("Intake: model reply was not valid JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The model's reply, read STRICTLY — {@link LlmJson#readTree} plus the shape checked and
     * normalised, throwing the parser's own complaint (or a shape complaint) when nothing usable
     * was found. This is the reader {@link LlmReplyRetry} calls on both the first reply and the
     * one retry it gives the analyst.
     *
     * <p><b>Must never fall back to {@link #salvage} itself.</b> It did, once, and that was the
     * bug: a reader that treats salvage as a successful parse reports success on the FIRST reply
     * whenever salvage found anything at all, so the retry this exists to earn never fires. Salvage
     * is tried by {@link #run} instead, and only as the last resort after THIS has failed twice —
     * see the {@code MalformedReplyException} catch there.
     *
     * <p>Shape, not just syntax: a reply that parses but wraps its array under a different name
     * ({@code "items"}) or sends the bare array with no envelope is accepted via
     * {@link LlmJson#wrapAsField} — a model sends both live, and neither is a reason to spend the
     * analyst's one retry. A reply that drops the envelope AND the array, sending a single
     * proposal object as if that were the whole reply, is also accepted — but only via
     * {@link LlmJson#readWholeValue}, which fails on a fragment the way {@link LlmJson#readTree}'s
     * own retry-past-a-bad-brace search would not; see that method's javadoc for why the two must
     * not be conflated here of all places.
     */
    private static JsonNode jsonOrThrow(String reply) throws IOException {
        if (reply == null) {
            throw new IOException("no reply");
        }
        JsonNode root = LlmJson.readTree(JSON, reply);
        if (root.has("proposals") || root.has("questions")) {
            return root;
        }
        JsonNode normalized = LlmJson.wrapAsField(JSON, root, "proposals", "items");
        if (normalized != null) {
            return normalized;
        }
        try {
            JsonNode whole = LlmJson.readWholeValue(JSON, reply);
            JsonNode single = LlmJson.wrapSingleAsField(JSON, whole, "proposals");
            if (single != null) {
                return single;
            }
        } catch (IOException ignored) {
            // Not a single whole object either — falls through to the failure below, which is
            // exactly what a genuinely broken reply (fragment and all) must reach.
        }
        throw new IOException("reply parsed but had neither \"proposals\" nor \"questions\" ("
            + reply.length() + " chars)");
    }

    /**
     * The last resort after the analyst's reply AND its one retry both failed strict parsing: as
     * much of the FINAL (retry) reply as {@link #salvage} can read, over the same
     * first-brace-to-last-brace span the analyst has always been read from. Never throws — a reply
     * salvage cannot help at all (no bracketed JSON, or an object with no readable proposals)
     * simply comes back empty, which {@link #run} treats as the retry having genuinely failed.
     */
    private static List<FlowProposal> salvageProposals(UUID flowId, String reply) {
        String bracketed = bracketed(reply);
        JsonNode salvaged = bracketed == null ? null : salvage(bracketed);
        return salvaged == null ? new ArrayList<>() : parseProposals(flowId, salvaged);
    }

    /**
     * A rough count of how many proposal objects a reply appears to have been attempting, from how
     * many times the one field every proposal object carries shows up — {@code salvage} stops
     * reading at the first one it cannot parse, so the reply may hold more the model never finished
     * writing. Only ever used to tell the operator "recovered N of about M", never to drive parsing.
     */
    private static int attemptedProposalCount(String reply) {
        if (reply == null) {
            return 0;
        }
        int count = 0;
        for (int idx = reply.indexOf("\"kind\""); idx >= 0; idx = reply.indexOf("\"kind\"", idx + 6)) {
            count++;
        }
        return count;
    }

    /** The reply from its first '{' to its last '}' — what {@link #salvage} reads from. */
    private static String bracketed(String reply) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        return start < 0 || end <= start ? null : reply.substring(start, end + 1);
    }

    /**
     * As much of a malformed reply as can be read without guessing at the rest.
     *
     * <p>A 27B model answering the drafting prompt on 2026-08-28 produced two well-formed
     * proposals with one extra closing brace between them. The whole analysis was lost to it: the
     * flow failed with "the analyst proposed no changes — the documents may not contain
     * requirements", which is a false statement about the operator's own document and leaves them
     * nothing to act on. There is one analyst and no second attempt, so a reply that is 90% good
     * has to be worth 90%.
     *
     * <p>This reads the object field by field and each array element by element, and STOPS at the
     * first thing it cannot read. Everything before the break is kept exactly as the model wrote
     * it; everything after it is discarded. Nothing is repaired, completed or inferred — a
     * requirement invented by a bracket-balancing heuristic would be far worse than a missing one.
     *
     * <p>Called ONLY from {@link #salvageProposals}, and only as the last resort after both the
     * analyst's reply and its one retry have failed to parse (see {@link #run}). The line logged
     * below is DEBUG, not WARN, because it fires on every partial read including a broken retry
     * that still recovers everything — {@link #run} logs the one WARN that matters, with the count
     * this stopped at, how many the reply appears to have held, and where it was kept.
     */
    private static JsonNode salvage(String bracketed) {
        ObjectNode root = JSON.createObjectNode();
        try (JsonParser parser = JSON.getFactory().createParser(bracketed)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                if (parser.nextToken() == JsonToken.START_ARRAY) {
                    ArrayNode array = root.putArray(field);
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        array.add((JsonNode) parser.readValueAsTree());
                    }
                } else {
                    root.set(field, (JsonNode) parser.readValueAsTree());
                }
            }
        } catch (Exception e) {
            log.debug("Intake: read {} before the reply broke ({}); everything after it is "
                + "discarded", describe(root), e.getMessage());
            return root.isEmpty() ? null : root;
        }
        return root.isEmpty() ? null : root;
    }

    /** "proposals=1, questions=0" — what survived, for the log line above. */
    private static String describe(ObjectNode root) {
        StringBuilder sb = new StringBuilder();
        root.fieldNames().forEachRemaining(name -> sb.append(sb.isEmpty() ? "" : ", ")
            .append(name).append('=').append(root.path(name).size()));
        return sb.isEmpty() ? "nothing" : sb.toString();
    }

    private static String str(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * A proposed requirement, rendered as a line-oriented block.
     *
     * <p>{@link FlowProposal} carries {@code before}/{@code after} as text because that is what a
     * diff shows. Keeping the block both human-readable and parseable means the operator reviews
     * exactly the thing that gets written — not a summary of it.
     */
    private record Draft(String text, String priority, String category, String kind,
                         String nfrCategory, String excerpt, String purpose, String strength,
                         List<String> appliesTo, List<Check> criteria,
                         List<Link> relationships) {

        /**
         * True only when the analyst said "hard" — anything else, blank included, is a preference
         * (harness runs 53 and 55, 2026-10-01: a rule that was never meant to stop a run parked two).
         */
        boolean isHardRule() {
            return strength != null && strength.strip().equalsIgnoreCase("hard");
        }

        /** True when this draft states a RULE about how the project is built, not a requirement. */
        boolean isRule() {
            String normalized = kind == null ? "" : kind.trim().toUpperCase().replace('-', '_');
            return normalized.equals("CONSTRAINT") || normalized.equals("RULE");
        }

        /** What the operator reads instead of {@code FUNCTIONAL}. */
        private static final String BEHAVIOUR_WORDS = "something it must do";

        /** What the operator reads instead of {@code NON_FUNCTIONAL}. */
        private static final String QUALITY_WORDS = "a quality it must meet";

        /**
         * What the operator reads instead of {@code CONSTRAINT}.
         *
         * <p>Worded as a rule and not as a thing, because the whole point of it is that it is never
         * delivered. "A rule for building it" reads as something nobody finishes; anything shaped
         * like a noun would read as another item of work.
         *
         * <p>It also says where the rule GOES and when it takes effect, on the line the operator
         * reads before ticking it. A rule that lands somewhere they have not looked, in a state
         * they have to go and change, is the dead end this whole route was rebuilt to remove.
         */
        private static final String RULE_WORDS =
            "a rule for building it — kept in this project's rules and in force as soon as you apply";

        /**
         * The line key each acceptance check is written under.
         *
         * <p>"check" is the operator's word for it (UX v3 §2.1); {@code AcceptanceCriterion} is the
         * Java type behind it and stays that way. This block is the one place the two vocabularies
         * meet, because a person edits it by hand and the result is parsed back.
         */
        private static final String CHECK_KEY = "Check";

        /**
         * What this key was called before, still read.
         *
         * <p>Proposals written before the wording changed are sitting in the store with
         * {@code Criterion:} lines in them. A parser that knew only {@link #CHECK_KEY} would not
         * fail on those — it would quietly drop every check out of every one of them and apply a
         * requirement nothing can ever verify.
         */
        private static final String LEGACY_CHECK_KEY = "Criterion";

        /**
         * The line key for the document's own wording a proposal — a RULE, above all — was drawn
         * from, verbatim. See {@code RequirementsIntake#proposalPrompt} for what the analyst is
         * asked to put here, and {@code ProjectRules#stateRule(String,String,String,String)} for
         * why a rule keeps it: {@code text} is the analyst's paraphrase, and a paraphrase can drop
         * the one backticked word a check against the build's poms
         * ({@code RulesVersusManifest}) needs.
         */
        private static final String EXCERPT_KEY = "Excerpt";

        /** The line key for why a RULE exists — the problem it prevents, one line. */
        private static final String PURPOSE_KEY = "Purpose";

        /** The line key for how hard a RULE is: "hard" or "preference". */
        private static final String STRENGTH_KEY = "Strength";

        /**
         * The line key for the part of the project a RULE applies to: folders, separated by
         * commas. No such line is a rule of the whole project.
         */
        private static final String APPLIES_TO_KEY = "AppliesTo";

        /**
         * One proposed check: what must be observably true, and the name of the test that will
         * prove it.
         *
         * <p>The test reference used to be written as null at both call sites, so the one string on
         * which the entire requirement-to-commit trace depends was typed by a person into the
         * editor and validated against nothing (§15.5). The wizard now PROPOSES it, following the
         * project's fixed acceptance-test convention, and the operator sees it marked as a proposal
         * and may change it. A proposal is not a decision, and it is not a repair: if the test
         * author later writes something else, the run parks rather than quietly adopting whatever
         * was written.
         *
         * @param test the proposed reference; blank when the model did not offer one
         */
        record Check(String text, String test) {}

        /**
         * One proposed edge, as the operator reads it in the block: the relation and the handle at
         * the far end. It is deliberately NOT resolved here — a handle is only meaningful against
         * the BRD as it stands when Apply runs, which may be a different graph from the one the
         * analysis read.
         */
        record Link(String relation, String target) {}

        /**
         * The relation an edge line's key names, or null if the key is not a relation at all.
         *
         * <p>Keys are matched on their letters alone, so {@code DependsOn}, {@code depends_on} and
         * {@code Depends On} all land on the same relation. The block is round-tripped through a
         * model and reviewed by a human, and neither of them writes punctuation consistently.
         */
        static RequirementRelation relationFor(String key) {
            String letters = key == null ? "" : key.replaceAll("[^A-Za-z]", "").toLowerCase();
            for (RequirementRelation relation : RequirementRelation.values()) {
                if (relation.name().replace("_", "").toLowerCase().equals(letters)) {
                    return relation;
                }
            }
            return null;
        }

        /** The line key for a relation — {@code DEPENDS_ON} renders as {@code DependsOn}. */
        private static String keyFor(RequirementRelation relation) {
            StringBuilder sb = new StringBuilder();
            for (String word : relation.name().split("_")) {
                sb.append(word.charAt(0)).append(word.substring(1).toLowerCase());
            }
            return sb.toString();
        }

        /**
         * The requirement kind in the words the operator reads (UX v3 §5 rule 3).
         *
         * <p>{@code FUNCTIONAL} and {@code NON_FUNCTIONAL} are Java constants. They were printed
         * into this block verbatim, so the first thing a reader met on the review screen was two
         * shouted words from the persistence layer. The block is round-tripped, so the plain words
         * have to survive {@link #kindConstant(String)} back to the constant that is stored.
         */
        private static String kindWords(String kind) {
            String normalized = kind == null ? "" : kind.trim().toUpperCase().replace('-', '_');
            if (normalized.equals("CONSTRAINT") || normalized.equals("RULE")) {
                return RULE_WORDS;
            }
            return normalized.equals("NON_FUNCTIONAL") || normalized.equals("NFR")
                ? QUALITY_WORDS : normalized.isEmpty() ? "" : BEHAVIOUR_WORDS;
        }

        /**
         * The stored constant behind whatever the Kind line says.
         *
         * <p>Both vocabularies are accepted. The operator edits this block by hand, and proposals
         * written before the wording changed are still in the store carrying the old constants; a
         * parser that only knew the new words would silently turn every one of them into an
         * ordinary functional requirement.
         */
        private static String kindConstant(String value) {
            String v = value == null ? "" : value.trim().toLowerCase();
            if (v.startsWith("a rule for building it") || v.equals("constraint")
                    || v.equals("rule")) {
                return "CONSTRAINT";
            }
            if (v.equals(QUALITY_WORDS) || v.equals("non_functional") || v.equals("non-functional")
                    || v.equals("nfr")) {
                return "NON_FUNCTIONAL";
            }
            return v.isEmpty() ? "" : "FUNCTIONAL";
        }

        static String render(JsonNode node) {
            Map<String, String> header = new LinkedHashMap<>();
            header.put("Kind", kindWords(str(node, "requirementKind")));
            header.put("Priority", str(node, "priority").toLowerCase());
            header.put("Category", str(node, "nfrCategory").isBlank()
                ? str(node, "category") : str(node, "nfrCategory"));
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : header.entrySet()) {
                if (!entry.getValue().isBlank()) {
                    sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
                }
            }
            String body = str(node, "text");
            if (!body.isBlank()) {
                sb.append("Text: ").append(body.replace("\n", " ").strip()).append('\n');
            }
            // The document's own sentence(s), verbatim — kept alongside "Text" so a rule is never
            // ONLY the analyst's paraphrase of it. See EXCERPT_KEY.
            String excerpt = str(node, "excerpt");
            if (!excerpt.isBlank()) {
                sb.append(EXCERPT_KEY).append(": ").append(excerpt.replace("\n", " ").strip())
                    .append('\n');
            }
            // Why the rule exists and how hard it is — on the block the operator reviews, so the
            // classification they apply is one they could see and change.
            String purpose = str(node, "purpose");
            if (!purpose.isBlank()) {
                sb.append(PURPOSE_KEY).append(": ").append(purpose.replace("\n", " ").strip())
                    .append('\n');
            }
            String strength = str(node, "strength");
            if (!strength.isBlank()) {
                sb.append(STRENGTH_KEY).append(": ")
                    .append(strength.strip().equalsIgnoreCase("hard") ? "hard" : "preference")
                    .append('\n');
            }
            // Where the rule applies - on the block the operator reviews, so they can see and
            // change which workers will be sent it. A bare string is read as one folder.
            List<String> parts = new ArrayList<>();
            JsonNode appliesTo = node.path("appliesTo");
            if (appliesTo.isArray()) {
                appliesTo.forEach(part -> parts.add(part.asText("")));
            } else if (appliesTo.isTextual()) {
                parts.add(appliesTo.asText(""));
            }
            parts.removeIf(part -> part == null || part.isBlank());
            if (!parts.isEmpty()) {
                sb.append(APPLIES_TO_KEY).append(": ").append(String.join(", ",
                    parts.stream().map(part -> part.replace("\n", " ").strip()).toList()))
                    .append('\n');
            }
            // A criterion may arrive as a bare string (the shape before test references) or as an
            // object carrying the proposed test name. Both are accepted: the model is asked for the
            // object, and a model that answers with the old shape must still produce a usable
            // requirement rather than an empty one.
            for (JsonNode criterion : node.path("criteria")) {
                String value = (criterion.isObject() ? str(criterion, "text") : criterion.asText(""))
                    .replace("\n", " ").strip();
                if (value.isBlank()) {
                    continue;
                }
                sb.append(CHECK_KEY).append(": ").append(value).append('\n');
                String test = criterion.isObject() ? str(criterion, "test").replace("\n", " ").strip() : "";
                if (!test.isBlank()) {
                    // Its own line, directly under the criterion it belongs to. The block IS the
                    // review: a proposed test name the operator cannot see in the diff is a name
                    // they never agreed to, and this one decides what proves the requirement.
                    sb.append("Test: ").append(test).append('\n');
                }
            }
            // Relationships are rendered as further lines of the same block rather than held apart,
            // because the block IS the review: an edge the operator cannot see in the diff is an
            // edge they never agreed to, and edges are what the sequencing will be built on.
            for (JsonNode link : node.path("relationships")) {
                RequirementRelation relation = relationFor(str(link, "relation"));
                String target = str(link, "target").replace("\n", " ").strip();
                if (relation != null && !target.isBlank()) {
                    sb.append(keyFor(relation)).append(": ").append(target).append('\n');
                }
            }
            return sb.toString();
        }

        static Draft parse(String block) {
            String text = "";
            String priority = "";
            String category = "";
            String kind = "";
            String nfrCategory = "";
            String excerpt = "";
            String purpose = "";
            String strength = "";
            List<String> appliesTo = new ArrayList<>();
            List<Check> criteria = new ArrayList<>();
            List<Link> relationships = new ArrayList<>();
            for (String line : (block == null ? "" : block).split("\n")) {
                String stripped = line.strip();
                int colon = stripped.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = stripped.substring(0, colon).strip();
                String value = stripped.substring(colon + 1).strip();
                switch (key) {
                    case "Kind" -> kind = kindConstant(value);
                    case "Priority" -> priority = value;
                    case "Category" -> category = value;
                    case "Text" -> text = value;
                    case EXCERPT_KEY -> excerpt = value;
                    case PURPOSE_KEY -> purpose = value;
                    case STRENGTH_KEY -> strength = value;
                    case APPLIES_TO_KEY -> {
                        for (String part : value.split(",")) {
                            if (!part.isBlank()) {
                                appliesTo.add(part.strip());
                            }
                        }
                    }
                    case CHECK_KEY, LEGACY_CHECK_KEY -> criteria.add(new Check(value, ""));
                    // A "Test:" line belongs to the check above it. A stray one with no check
                    // yet is ignored rather than becoming an error: the operator edits
                    // this block by hand and must not lose the rest of the proposal to a typo.
                    case "Test" -> {
                        if (!criteria.isEmpty() && !value.isBlank()) {
                            int last = criteria.size() - 1;
                            criteria.set(last, new Check(criteria.get(last).text(), value));
                        }
                    }
                    default -> {
                        // Anything else may still be a relationship line. Unknown keys stay ignored
                        // rather than becoming an error: the operator edits this block by hand, and
                        // a stray line must not cost them the rest of the proposal.
                        RequirementRelation relation = relationFor(key);
                        if (relation != null && !value.isBlank()) {
                            relationships.add(new Link(relation.name(), value));
                        }
                    }
                }
            }
            // The category field serves both roles: for an NFR it is the fitness category the BRD
            // requires, for a functional requirement it is a free-text grouping.
            if ("NON_FUNCTIONAL".equalsIgnoreCase(kind)) {
                nfrCategory = category;
                category = "";
            }
            return new Draft(text, priority, category, kind, nfrCategory, excerpt, purpose,
                strength, appliesTo, criteria, relationships);
        }
    }
}
