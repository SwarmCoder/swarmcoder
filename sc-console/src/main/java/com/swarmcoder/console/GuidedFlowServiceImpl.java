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

import com.swarmcoder.console.api.FlowView;
import com.swarmcoder.console.api.GuidedFlowService;
import com.swarmcoder.domain.FlowDiscussionTurn;
import com.swarmcoder.domain.FlowDocument;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.store.ArtifactStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The server side of the requirements-intake wizard (docs/GUIDED_FLOWS_DESIGN.md).
 *
 * <p>This class owns the flow's <em>state machine and inputs</em>; the analysis itself lives in
 * {@link RequirementsIntake}. Splitting them keeps the rule that a transition is legal in exactly
 * one place — the engine cannot decide it is allowed to jump from {@code DRAFT} to {@code REVIEW}
 * because it never sets the state directly.
 *
 * <p>{@code @ApplicationScoped} so zeroz4j's CDI scan registers it as the {@code @RmiService}.
 */
@ApplicationScoped
public class GuidedFlowServiceImpl implements GuidedFlowService {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(GuidedFlowServiceImpl.class);

    @Override
    public FlowView intake() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        if (projectId == null) {
            GuidedFlows.publishNone(com.swarmcoder.domain.GuidedFlowKind.REQUIREMENTS_INTAKE);
            return FlowView.none();
        }
        ArtifactStore store = context.store();
        GuidedFlow flow = GuidedFlows.ensureIntake(store, projectId);
        FlowView view = GuidedFlows.view(store, flow);
        // Publish as well as return, so a wizard opened while one is already running gets the live
        // frame from signal retention rather than only this one snapshot.
        com.swarmcoder.console.api.GuidedFlowSignals.CURRENT.set(view);
        return view;
    }

    @Override
    public List<SourceDocument> availableDocuments(String flowId) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context == null ? null : context.currentProjectId();
            if (projectId == null) {
                return List.of();
            }
            GuidedFlow flow = load(context, flowId);
            List<UUID> attached = new ArrayList<>();
            if (flow != null) {
                for (FlowDocument entry : flow.documents()) {
                    attached.add(entry.documentId());
                }
            }
            List<SourceDocument> out = new ArrayList<>();
            for (SourceDocument document : context.store().listSourceDocuments(projectId)) {
                if (!attached.contains(document.id())) {
                    out.add(document);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("availableDocuments({}) threw", flowId, e);
            return List.of();
        }
    }

    // --- inputs ---------------------------------------------------------------------------------

    @Override
    public String addDocument(String flowId, String documentId, String notes) {
        return editing(flowId, (store, flow) -> {
            UUID docId = parseUuid(documentId);
            if (docId == null) {
                return "error: no document given";
            }
            SourceDocument document = store.getSourceDocument(docId);
            if (document == null) {
                return "error: no such document";
            }
            if (!flow.projectId().equals(document.projectId())) {
                return "error: that document belongs to another project";
            }
            List<FlowDocument> documents = new ArrayList<>(flow.documents());
            for (FlowDocument entry : documents) {
                if (docId.equals(entry.documentId())) {
                    // Already listed — a re-drop of the same file. Distinct from success, because
                    // treating it as a change would reset a REVIEW and throw the proposals away.
                    return UNCHANGED;
                }
            }
            documents.add(new FlowDocument(docId, blankToNull(notes)));
            flow.setDocuments(documents);
            return "";
        });
    }

    @Override
    public String setNotes(String flowId, String documentId, String notes) {
        return editing(flowId, (store, flow) -> {
            UUID docId = parseUuid(documentId);
            List<FlowDocument> documents = new ArrayList<>();
            boolean found = false;
            for (FlowDocument entry : flow.documents()) {
                if (entry.documentId().equals(docId)) {
                    // Carry the analysis state through. Rebuilding with the two-arg constructor
                    // reset analysedAt and excluded, so editing a note on an analysed document
                    // silently put it back into the next run — the exact re-reading this exists to
                    // prevent, triggered by an edit that has nothing to do with it.
                    documents.add(new FlowDocument(docId, blankToNull(notes),
                        entry.analysedAt(), entry.excluded()));
                    found = true;
                } else {
                    documents.add(entry);
                }
            }
            if (!found) {
                return "error: that document is not in this flow";
            }
            flow.setDocuments(documents);
            return "";
        });
    }

    @Override
    public String setDocumentIncluded(String flowId, String documentId, boolean included) {
        return editing(flowId, (store, flow) -> {
            UUID docId = parseUuid(documentId);
            List<FlowDocument> documents = new ArrayList<>();
            boolean found = false;
            boolean changed = false;
            for (FlowDocument entry : flow.documents()) {
                if (entry.documentId().equals(docId)) {
                    found = true;
                    changed = entry.excluded() == included;
                    documents.add(new FlowDocument(docId, entry.notes(), entry.analysedAt(),
                        !included));
                } else {
                    documents.add(entry);
                }
            }
            if (!found) {
                return "error: that document is not in this flow";
            }
            if (!changed) {
                return UNCHANGED;
            }
            flow.setDocuments(documents);
            return "";
        });
    }

    @Override
    public String setDocumentTechnical(String flowId, String documentId, boolean technical) {
        return editing(flowId, (store, flow) -> {
            UUID docId = parseUuid(documentId);
            List<FlowDocument> documents = new ArrayList<>();
            boolean found = false;
            boolean changed = false;
            for (FlowDocument entry : flow.documents()) {
                if (entry.documentId().equals(docId)) {
                    found = true;
                    changed = entry.technical() != technical;
                    documents.add(new FlowDocument(docId, entry.notes(), entry.analysedAt(),
                        entry.excluded(), technical));
                } else {
                    documents.add(entry);
                }
            }
            if (!found) {
                return "error: that document is not in this flow";
            }
            if (!changed) {
                return UNCHANGED;
            }
            flow.setDocuments(documents);
            return "";
        });
    }

    @Override
    public String removeDocument(String flowId, String documentId) {
        return editing(flowId, (store, flow) -> {
            UUID docId = parseUuid(documentId);
            List<FlowDocument> documents = new ArrayList<>();
            boolean removed = false;
            for (FlowDocument entry : flow.documents()) {
                if (entry.documentId().equals(docId)) {
                    removed = true;
                } else {
                    documents.add(entry);
                }
            }
            if (!removed) {
                return "error: that document is not in this flow";
            }
            flow.setDocuments(documents);
            return "";
        });
    }

    @Override
    public String deleteDocument(String documentId) {
        log.info("deleteDocument({})", documentId);
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context == null ? null : context.currentProjectId();
            UUID docId = parseUuid(documentId);
            if (projectId == null || docId == null) {
                return "error: no such document";
            }
            SourceDocument document = context.store().getSourceDocument(docId);
            if (document == null || !projectId.equals(document.projectId())) {
                return "error: no such document in this project";
            }
            // Detach before deleting, so no flow is left holding a reference to nothing.
            for (GuidedFlow flow : context.store().listGuidedFlows(projectId)) {
                if (GuidedFlows.inProgress(flow)) {
                    return "error: the analysis is running — cancel it before deleting documents";
                }
                List<FlowDocument> kept = new ArrayList<>();
                boolean held = false;
                for (FlowDocument entry : flow.documents()) {
                    if (docId.equals(entry.documentId())) {
                        held = true;
                    } else {
                        kept.add(entry);
                    }
                }
                if (held) {
                    flow.setDocuments(kept);
                    clearWorkings(context.store(), flow);
                    GuidedFlows.advance(context.store(), flow, GuidedFlowState.DRAFT, 0,
                        kept.isEmpty() ? "Add the documents to analyse"
                            : "Ready to analyse " + kept.size() + " document(s)");
                }
            }
            context.store().deleteSourceDocument(docId);
            log.info("Deleted document {} ({})", document.filename(), docId);
            return "";
        } catch (Exception e) {
            log.warn("deleteDocument({}) threw", documentId, e);
            return "error: " + e;
        }
    }

    @Override
    public String addPastedDocument(String flowId, String title, String text) {
        log.info("addPastedDocument({}, {})", flowId, title);
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context == null ? null : context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            if (text == null || text.isBlank()) {
                return "error: nothing to add — paste the text first";
            }
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such analysis — close and re-open the wizard";
            }
            if (GuidedFlows.inProgress(flow)) {
                return "error: the analysis is running — cancel it before changing the documents";
            }
            String name = title == null || title.isBlank() ? "pasted note" : title.trim();
            SourceDocument document = new SourceDocument(UUID.randomUUID(), projectId, name,
                "text/plain", DocumentIngest.sha256(text.getBytes(StandardCharsets.UTF_8)),
                text, "pasted", text.length(), Instant.now());
            context.store().saveSourceDocument(document);
            return addDocument(flowId, document.id().toString(), null);
        } catch (Exception e) {
            log.warn("addPastedDocument({}) threw", flowId, e);
            return "error: " + e;
        }
    }

    @Override
    public String documentText(String documentId) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context == null ? null : context.currentProjectId();
            UUID docId = parseUuid(documentId);
            if (projectId == null || docId == null) {
                return "error: no such document";
            }
            SourceDocument document = context.store().getSourceDocument(docId);
            if (document == null || !projectId.equals(document.projectId())) {
                return "error: no such document in this project";
            }
            // The extractor's ceiling is ten million characters, and anything over 4 MB closes
            // the connection instead of returning it (WireBudget).
            return WireBudget.clamp(document.extractedText(), "This document");
        } catch (Exception e) {
            log.warn("documentText({}) threw", documentId, e);
            return "error: " + e;
        }
    }

    @Override
    public String setCharacterBudget(String flowId, int characters) {
        log.info("setCharacterBudget({}, {})", flowId, characters);
        return editing(flowId, (store, flow) -> {
            if (characters < 0) {
                return "error: a limit cannot be negative";
            }
            if (flow.characterBudget() == characters) {
                return UNCHANGED;
            }
            flow.setCharacterBudget(characters);
            return "";
        });
    }

    // --- running --------------------------------------------------------------------------------

    @Override
    public String start(String flowId) {
        log.info("start({})", flowId);
        ConsoleContext.refuseIfWatching("run the analyst");
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such analysis — close and re-open the wizard";
            }
            if (GuidedFlows.inProgress(flow)) {
                return "error: this analysis is already running";
            }
            if (flow.documents().isEmpty()) {
                return "error: add at least one document before starting";
            }
            if (RequirementsIntake.includedDocuments(flow) == 0) {
                return "error: every document has already been analysed — add a new one, or tick "
                    + "an analysed document to read it again";
            }
            // Refuse rather than read part of it. A requirements analysis that quietly skipped a
            // third of the input produces a confident, incomplete BRD and no sign anything is
            // missing — the operator gets the numbers and decides.
            String tooLarge = RequirementsIntake.tooLarge(context.store(), flow);
            if (tooLarge != null) {
                log.info("start({}) refused: {}", flowId, tooLarge);
                return tooLarge;
            }
            String result = RequirementsIntake.launch(context, flow);
            log.info("start({}) -> {}", flowId, result.isEmpty() ? "running" : result);
            return result;
        } catch (Exception e) {
            log.warn("start({}) threw", flowId, e);
            return "error: " + e;
        }
    }

    @Override
    public String cancel(String flowId) {
        ConsoleContext context = ConsoleContext.get();
        GuidedFlow flow = load(context, flowId);
        if (flow == null) {
            return "error: no such flow";
        }
        RequirementsIntake.cancel(flow.id());
        clearWorkings(context.store(), flow);
        GuidedFlows.advance(context.store(), flow, GuidedFlowState.DRAFT, 0, "Cancelled");
        return "";
    }

    // --- questions ------------------------------------------------------------------------------

    @Override
    public String answer(String flowId, String questionId, String answer) {
        return answering(flowId, questionId, question -> {
            question.setAnswer(blankToNull(answer));
            question.setSkipped(false);
        });
    }

    @Override
    public String note(String flowId, String questionId, String note) {
        return answering(flowId, questionId, question -> question.setNote(blankToNull(note)));
    }

    @Override
    public String skip(String flowId, String questionId) {
        return answering(flowId, questionId, question -> {
            question.setAnswer(null);
            question.setSkipped(true);
        });
    }

    @Override
    public List<FlowDiscussionTurn> discussion(String flowId, String questionId) {
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            FlowQuestion question = flow == null ? null : questionOf(context, flow, questionId);
            if (question == null) {
                return List.of();
            }
            return context.store().listFlowDiscussion(question.id());
        } catch (Exception e) {
            log.warn("discussion({}, {}) threw", flowId, questionId, e);
            return List.of();
        }
    }

    @Override
    public String discuss(String flowId, String questionId, String message) {
        // Logged at both ends. This hands work to a thread and returns "" — so without the exit
        // line there is no way to tell a call that was accepted from one that never arrived.
        log.info("discuss({}, {})", flowId, questionId);
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such analysis — close and re-open the wizard";
            }
            if (message == null || message.isBlank()) {
                return "error: nothing to send — type something first";
            }
            FlowQuestion question = questionOf(context, flow, questionId);
            if (question == null) {
                return "error: no such question — the analysis may have moved on";
            }
            String result = RequirementsIntake.discuss(context, flow, question, message);
            log.info("discuss({}, {}) -> {}", flowId, questionId,
                result.isEmpty() ? "asked" : result);
            return result;
        } catch (Exception e) {
            log.warn("discuss({}, {}) threw", flowId, questionId, e);
            return "error: " + e;
        }
    }

    /** One of a flow's questions by id, or null — deliberately not "the question with that id". */
    private static FlowQuestion questionOf(ConsoleContext context, GuidedFlow flow,
                                           String questionId) {
        UUID id = parseUuid(questionId);
        if (id == null) {
            return null;
        }
        for (FlowQuestion question : context.store().listFlowQuestions(flow.id())) {
            if (id.equals(question.id())) {
                return question;
            }
        }
        return null;
    }

    @Override
    public String submitAnswers(String flowId) {
        // Logged at both ends: this was silently doing nothing once, and "nothing in the logs" made
        // it impossible to tell a call that never arrived from one that arrived and failed.
        log.info("submitAnswers({})", flowId);
        ConsoleContext.refuseIfWatching("send the analyst answers");
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                log.warn("submitAnswers: no flow {} in the current project", flowId);
                return "error: no such analysis — close and re-open the wizard";
            }
            if (flow.state() != GuidedFlowState.AWAITING_ANSWERS) {
                log.warn("submitAnswers: flow {} is {}, not AWAITING_ANSWERS", flowId, flow.state());
                return "error: this analysis is not waiting for answers (it is "
                    + flow.state() + ")";
            }
            String result = RequirementsIntake.resume(context, flow);
            log.info("submitAnswers({}) -> {}", flowId, result.isEmpty() ? "resumed" : result);
            return result;
        } catch (Exception e) {
            log.warn("submitAnswers({}) threw", flowId, e);
            return "error: " + e;
        }
    }

    // --- review ---------------------------------------------------------------------------------

    @Override
    public String setAccepted(String flowId, String proposalId, boolean accepted) {
        ConsoleContext context = ConsoleContext.get();
        GuidedFlow flow = load(context, flowId);
        if (flow == null) {
            return "error: no such flow";
        }
        if (flow.state() != GuidedFlowState.REVIEW) {
            return "error: there is nothing to review yet";
        }
        UUID id = parseUuid(proposalId);
        List<FlowProposal> proposals = new ArrayList<>(context.store().listFlowProposals(flow.id()));
        boolean found = false;
        for (FlowProposal proposal : proposals) {
            if (proposal.id().equals(id)) {
                proposal.setAccepted(accepted);
                found = true;
            }
        }
        if (!found) {
            return "error: no such proposal";
        }
        context.store().saveFlowProposals(flow.id(), proposals);
        GuidedFlows.publish(context.store(), flow);
        return "";
    }

    @Override
    public String setAllAccepted(String flowId, boolean accepted) {
        ConsoleContext context = ConsoleContext.get();
        GuidedFlow flow = load(context, flowId);
        if (flow == null) {
            return "error: no such flow";
        }
        if (flow.state() != GuidedFlowState.REVIEW) {
            return "error: there is nothing to review yet";
        }
        List<FlowProposal> proposals = new ArrayList<>(context.store().listFlowProposals(flow.id()));
        for (FlowProposal proposal : proposals) {
            // A conflict is a question, not a change, and a retirement is a judgement about
            // requirements that may already have code and tests against them. "Tick all" must
            // sweep up neither — both stay where the operator left them.
            if (proposal.kind() != com.swarmcoder.domain.FlowProposalKind.CONFLICT
                    && proposal.kind() != com.swarmcoder.domain.FlowProposalKind.DEPRECATE) {
                proposal.setAccepted(accepted);
            }
        }
        context.store().saveFlowProposals(flow.id(), proposals);
        GuidedFlows.publish(context.store(), flow);
        return "";
    }

    @Override
    public String apply(String flowId) {
        log.info("apply({})", flowId);
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such analysis — close and re-open the wizard";
            }
            if (flow.state() != GuidedFlowState.REVIEW) {
                return "error: there is nothing to apply yet (the analysis is "
                    + flow.state() + ")";
            }
            String result = RequirementsIntake.apply(context, flow);
            log.info("apply({}) -> {}", flowId, result.isEmpty() ? "applied" : result);
            return result;
        } catch (Exception e) {
            log.warn("apply({}) threw", flowId, e);
            return "error: " + e;
        }
    }

    @Override
    public String reopen(String flowId) {
        ConsoleContext context = ConsoleContext.get();
        GuidedFlow flow = load(context, flowId);
        if (flow == null) {
            return "error: no such flow";
        }
        if (GuidedFlows.inProgress(flow)) {
            return "error: cancel the running analysis first";
        }
        clearWorkings(context.store(), flow);
        GuidedFlows.advance(context.store(), flow, GuidedFlowState.DRAFT, 0,
            "Add or change documents, then run the analysis again");
        return "";
    }

    @Override
    public String startOver(String flowId) {
        log.info("startOver({})", flowId);
        ConsoleContext.refuseIfWatching("start the analysis over");
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such analysis — close and re-open the wizard";
            }
            // Stop any run first, or its thread would publish over the cleared state on completion.
            RequirementsIntake.cancel(flow.id());
            clearWorkings(context.store(), flow);
            flow.setDocuments(new java.util.ArrayList<>());
            GuidedFlows.advance(context.store(), flow, GuidedFlowState.DRAFT, 0,
                "Add the documents to analyse");
            return "";
        } catch (Exception e) {
            log.warn("startOver({}) threw", flowId, e);
            return "error: " + e;
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Questions and proposals belong to one run. Leaving the previous run's behind would show the
     * operator a review list assembled from two different readings of the documents.
     */
    private static void clearWorkings(ArtifactStore store, GuidedFlow flow) {
        // Discussions go with the questions they explain — see GuidedFlows.clearQuestions.
        GuidedFlows.clearQuestions(store, flow.id());
        store.saveFlowProposals(flow.id(), List.of());
    }

    /**
     * An {@link Edit} outcome meaning "the request was valid but nothing actually changed". It is
     * reported to the caller as success, but must not reset the flow: an edit that changes nothing
     * cannot invalidate a review.
     */
    private static final String UNCHANGED = " unchanged";

    /** A change to the flow's inputs, legal only while it is not running. */
    private interface Edit {
        String apply(ArtifactStore store, GuidedFlow flow);
    }

    private String editing(String flowId, Edit edit) {
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such flow";
            }
            if (GuidedFlows.inProgress(flow)) {
                return "error: the analysis is running — cancel it before changing the documents";
            }
            String error = edit.apply(context.store(), flow);
            if (UNCHANGED.equals(error)) {
                return "";
            }
            if (!error.isEmpty()) {
                return error;
            }
            // Back to DRAFT: the documents no longer match whatever was proposed last time.
            if (flow.state() == GuidedFlowState.REVIEW || flow.state() == GuidedFlowState.APPLIED
                    || flow.state() == GuidedFlowState.FAILED) {
                clearWorkings(context.store(), flow);
                GuidedFlows.advance(context.store(), flow, GuidedFlowState.DRAFT, 0,
                    readyLabel(flow));
            } else {
                GuidedFlows.advance(context.store(), flow, GuidedFlowState.DRAFT, 0,
                    readyLabel(flow));
            }
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private interface Answering {
        void apply(FlowQuestion question);
    }

    private String answering(String flowId, String questionId, Answering change) {
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such flow";
            }
            if (flow.state() != GuidedFlowState.AWAITING_ANSWERS) {
                return "error: this analysis is not waiting for answers";
            }
            UUID id = parseUuid(questionId);
            List<FlowQuestion> questions = new ArrayList<>(context.store().listFlowQuestions(flow.id()));
            boolean found = false;
            for (FlowQuestion question : questions) {
                if (question.id().equals(id)) {
                    change.apply(question);
                    found = true;
                }
            }
            if (!found) {
                return "error: no such question";
            }
            context.store().saveFlowQuestions(flow.id(), questions);
            GuidedFlows.publish(context.store(), flow);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /** What the DRAFT step line says — counting what will be READ, not what is attached. */
    private static String readyLabel(GuidedFlow flow) {
        if (flow.documents().isEmpty()) {
            return "Add the documents to analyse";
        }
        int included = RequirementsIntake.includedDocuments(flow);
        if (included == 0) {
            return "Every document has been analysed — tick one to read it again, or add a new one";
        }
        return "Ready to analyse " + included + " document(s)";
    }

    /** Loads a flow, refusing one belonging to a project other than the current one. */
    private static GuidedFlow load(ConsoleContext context, String flowId) {
        UUID projectId = context == null ? null : context.currentProjectId();
        UUID id = parseUuid(flowId);
        if (projectId == null || id == null) {
            return null;
        }
        GuidedFlow flow = context.store().getGuidedFlow(id);
        return flow != null && projectId.equals(flow.projectId()) ? flow : null;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
