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
import com.swarmcoder.console.api.GuidedFlowSignals;
import com.swarmcoder.console.api.PlanningFlowSignals;
import com.swarmcoder.domain.FlowDocument;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowKind;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared plumbing for guided flows: persist a state change and publish it, in that order, so the
 * wizard's view can never be ahead of what would survive a restart.
 *
 * <p>Every state change funnels through {@link #advance} or {@link #fail} rather than being written
 * ad hoc by the service and the engine separately. Two writers with two publish paths is how a flow
 * ends up showing "step 3 of 4" for work that already failed.
 */
final class GuidedFlows {

    private static final Logger log = LoggerFactory.getLogger(GuidedFlows.class);

    private GuidedFlows() {}

    /**
     * Records the flow's new state and broadcasts the whole view.
     *
     * @param stepLabel what is happening right now, in the operator's words — this is the sentence
     *                  under the progress bar, so "Reading pricing-spec.pdf" beats "phase 2"
     */
    static void advance(ArtifactStore store, GuidedFlow flow, GuidedFlowState state,
                        int step, String stepLabel) {
        flow.setState(state);
        flow.setStep(step);
        flow.setStepLabel(stepLabel);
        flow.setUpdatedAt(Instant.now());
        if (state != GuidedFlowState.FAILED) {
            flow.setError(null);
        }
        store.saveGuidedFlow(flow);
        publish(store, flow);
    }

    /**
     * Moves the flow to {@code FAILED} with a message the operator can act on.
     *
     * <p>The failure is persisted, not merely logged: an analysis that dies must leave a visible
     * record the wizard can show on re-open, otherwise the operator returns to a dialog that looks
     * like it is still thinking.
     */
    static void fail(ArtifactStore store, GuidedFlow flow, String message) {
        log.warn("Guided flow {} failed: {}", flow.id(), message);
        flow.setError(message);
        flow.setStepLabel("Failed");
        flow.setState(GuidedFlowState.FAILED);
        flow.setUpdatedAt(Instant.now());
        store.saveGuidedFlow(flow);
        publish(store, flow);
    }

    /**
     * Publishes the flow's full view on the signal that belongs to its KIND.
     *
     * <p>Routing here rather than at each call site is what lets the engines and the two service
     * implementations share one {@link #advance}. A planning step publishing onto intake's signal
     * would blank the Requirements wizard and show it a flow it is not rendering — and the bug would
     * only appear when both wizards happened to be open.
     */
    static void publish(ArtifactStore store, GuidedFlow flow) {
        signalFor(flow).set(view(store, flow));
    }

    /** Clears a signal — no project selected, or no flow yet. */
    static void publishNone(GuidedFlowKind kind) {
        (kind == GuidedFlowKind.BACKLOG_PLANNING
            ? PlanningFlowSignals.CURRENT : GuidedFlowSignals.CURRENT).set(FlowView.none());
    }

    private static com.zeroz4j.signals.ValueSignal<FlowView> signalFor(GuidedFlow flow) {
        return flow != null && flow.kind() == GuidedFlowKind.BACKLOG_PLANNING
            ? PlanningFlowSignals.CURRENT : GuidedFlowSignals.CURRENT;
    }

    /**
     * Assembles the wizard's view of a flow.
     *
     * <p>Deep-copies the flow because the signal dedups by {@code equals()}: publishing the same
     * mutated instance twice would broadcast nothing, and the wizard would sit on a stale frame.
     */
    static FlowView view(ArtifactStore store, GuidedFlow flow) {
        if (flow == null) {
            return FlowView.none();
        }
        List<SourceDocument> documents = new ArrayList<>();
        for (FlowDocument entry : flow.documents()) {
            SourceDocument document = store.getSourceDocument(entry.documentId());
            if (document != null) {
                documents.add(withoutText(document));
            }
        }
        List<FlowQuestion> questions = new ArrayList<>(store.listFlowQuestions(flow.id()));
        List<FlowProposal> proposals = new ArrayList<>(store.listFlowProposals(flow.id()));
        boolean planning = flow.kind() == GuidedFlowKind.BACKLOG_PLANNING;
        FlowView view = new FlowView(ArtifactStore.copyOf(flow), questions, proposals, documents,
            // A planning flow reads no documents, so a character budget is not a number about it —
            // showing intake's default there would put a limit on screen that governs nothing.
            planning ? 0 : RequirementsIntake.totalCharacters(store, flow),
            planning ? 0 : RequirementsIntake.budgetOf(flow),
            requirementCount(store, flow.projectId()));
        if (planning) {
            describeInputs(store, flow.projectId(), view);
        }
        return view;
    }

    /**
     * Fills in what the planning wizard shows instead of a document list: how much of the agreed
     * scope no story yet claims, how big the backlog already is, and the coverage report itself.
     *
     * <p>Read with {@code getBrd}, not {@code ensureBrd}: a publish must not create rows. A project
     * with no BRD has nothing to plan and says so, which is not the same as having an empty one.
     */
    private static void describeInputs(ArtifactStore store, UUID projectId, FlowView view) {
        try {
            if (store.getBrd(projectId) == null) {
                view.setCoverage("This project has no requirements yet — analyse your documents in "
                    + "the Requirements panel first. A story can only deliver a check that "
                    + "already exists.");
                return;
            }
            view.setCoverage(BacklogAuthoring.coverage(store, projectId));
            view.setUnclaimedCriteria(BacklogAuthoring.unclaimedCriteria(store, projectId));
            view.setBacklogStories(BacklogAuthoring.livingStories(store, projectId));
        } catch (Exception e) {
            // A publish must survive a store that answers badly; the wizard simply shows no summary.
            log.warn("Could not summarise the planning inputs for project {}: {}", projectId,
                e.toString());
        }
    }

    /** How many requirements the project's BRD holds — 0 for a project that has not started. */
    private static int requirementCount(ArtifactStore store, UUID projectId) {
        try {
            com.swarmcoder.domain.Brd brd = store.getBrd(projectId);
            return brd == null || brd.requirements() == null ? 0 : brd.requirements().size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * A document header without its extracted text.
     *
     * <p>The full text of every attached document was being serialised into every publish — and a
     * flow publishes on each answer, each note and each step. For a real spec that is megabytes
     * broadcast dozens of times to say "the step label changed". The wizard only ever shows the
     * name, the size and the extractor; the text is fetched on demand by the viewer.
     */
    static SourceDocument withoutText(SourceDocument document) {
        SourceDocument light = new SourceDocument(document.id(), document.projectId(),
            document.filename(), document.mediaType(), document.sha256(), null,
            document.extractedBy(), document.byteSize(), document.uploadedAt());
        return light;
    }

    /**
     * The project's requirements-intake flow, creating an empty {@code DRAFT} one on first use.
     * There is exactly one per project: intake is a standing activity that gets re-run as documents
     * arrive, not a series of independent jobs to be found in a list.
     */
    static GuidedFlow ensureIntake(ArtifactStore store, UUID projectId) {
        return ensure(store, projectId, GuidedFlowKind.REQUIREMENTS_INTAKE,
            "Add the documents to analyse");
    }

    /**
     * The project's backlog-planning flow, creating an empty {@code DRAFT} one on first use. One per
     * project, for the same reason intake has one: planning is a standing activity re-run as the
     * requirements grow, not a series of independent jobs to be found in a list.
     */
    static GuidedFlow ensurePlanning(ArtifactStore store, UUID projectId) {
        return ensure(store, projectId, GuidedFlowKind.BACKLOG_PLANNING,
            "Ready to plan stories over the agreed requirements");
    }

    private static GuidedFlow ensure(ArtifactStore store, UUID projectId, GuidedFlowKind kind,
                                     String stepLabel) {
        for (GuidedFlow existing : store.listGuidedFlows(projectId)) {
            if (existing.kind() == kind) {
                return existing;
            }
        }
        Instant now = Instant.now();
        GuidedFlow flow = new GuidedFlow(UUID.randomUUID(), projectId, kind, GuidedFlowState.DRAFT,
            0, 0, stepLabel, null, new ArrayList<>(), now, now);
        store.saveGuidedFlow(flow);
        return flow;
    }

    /**
     * Adds a freshly uploaded document to the project's intake flow and republishes it.
     *
     * <p>The upload endpoint calls this rather than the browser calling {@code addDocument} after
     * the fetch resolves: an RMI call made from that native-JS callback throws ("suspension point
     * reached from non-threading context"). Doing it server-side also makes the behaviour right
     * regardless of where the upload came from — a document dropped on the Requirements panel shows
     * up in the wizard's list, which is what an operator expects of a document they just added.
     *
     * <p>A running analysis is left alone: changing its inputs mid-read would produce requirements
     * drawn from a document set that never existed as a whole.
     */
    static void attachUploaded(ArtifactStore store, UUID projectId, UUID documentId) {
        try {
            GuidedFlow flow = ensureIntake(store, projectId);
            if (inProgress(flow)) {
                return;
            }
            for (FlowDocument entry : flow.documents()) {
                if (documentId.equals(entry.documentId())) {
                    return;
                }
            }
            List<FlowDocument> documents = new ArrayList<>(flow.documents());
            documents.add(new FlowDocument(documentId, null));
            flow.setDocuments(documents);
            if (flow.state() != GuidedFlowState.DRAFT) {
                // The previous run's proposals were drawn from a different document set.
                clearQuestions(store, flow.id());
                store.saveFlowProposals(flow.id(), List.of());
            }
            advance(store, flow, GuidedFlowState.DRAFT, 0,
                "Ready to analyse " + documents.size() + " document(s)");
        } catch (Exception e) {
            // An upload must still succeed if the flow could not be updated; the operator can add
            // the document from the wizard by hand.
            log.warn("Could not attach document {} to the intake flow: {}", documentId, e.toString());
        }
    }

    /**
     * Throws away a flow's questions AND the discussions held about them.
     *
     * <p>The two are stored apart — discussions are keyed by question id, so that opening one costs
     * a lookup rather than a scan — which means dropping the questions on their own leaves
     * transcripts about questions that no longer exist, unreachable and undeletable. Every place
     * that clears a round goes through here so that cannot be forgotten in one of them.
     */
    static void clearQuestions(ArtifactStore store, UUID flowId) {
        List<UUID> discussed = new ArrayList<>();
        for (FlowQuestion question : store.listFlowQuestions(flowId)) {
            discussed.add(question.id());
        }
        store.deleteFlowDiscussions(discussed);
        store.saveFlowQuestions(flowId, List.of());
    }

    /** True while the flow is doing work or waiting on the operator mid-run. */
    static boolean inProgress(GuidedFlow flow) {
        return flow.state() == GuidedFlowState.RUNNING
            || flow.state() == GuidedFlowState.AWAITING_ANSWERS;
    }
}
