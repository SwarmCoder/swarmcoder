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
import com.swarmcoder.console.api.PlanningFlowService;
import com.swarmcoder.console.api.PlanningFlowSignals;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowProposalKind;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowKind;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.store.ArtifactStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The server side of the backlog-planning wizard (docs/GUIDED_FLOWS_DESIGN.md §2), and the
 * replacement for the removed {@code /backlog} chat mode.
 *
 * <p>This class owns the flow's <em>state machine</em>; the planning itself lives in
 * {@link BacklogPlanning}. Splitting them keeps the rule that a transition is legal in exactly one
 * place — the engine cannot decide it is allowed to jump from {@code DRAFT} to {@code REVIEW}
 * because it never sets the state directly.
 *
 * <p>The mirror of {@link GuidedFlowServiceImpl} minus its inputs: there is nothing to attach here,
 * so there is no {@code addDocument}/{@code setNotes} half and no editing path that has to
 * invalidate a review. The inputs are the project's own requirements and backlog, and they change
 * under the flow by other people's actions — which is why {@link #reopen} exists and why a second
 * run merges against the backlog the first one wrote.
 *
 * <p>{@code @ApplicationScoped} so zeroz4j's CDI scan registers it as the {@code @RmiService}.
 */
@ApplicationScoped
public class PlanningFlowServiceImpl implements PlanningFlowService {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(PlanningFlowServiceImpl.class);

    @Override
    public FlowView planning() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        if (projectId == null) {
            GuidedFlows.publishNone(GuidedFlowKind.BACKLOG_PLANNING);
            return FlowView.none();
        }
        ArtifactStore store = context.store();
        GuidedFlow flow = GuidedFlows.ensurePlanning(store, projectId);
        FlowView view = GuidedFlows.view(store, flow);
        // Publish as well as return, so a wizard opened while one is already running gets the live
        // frame from signal retention rather than only this one snapshot.
        PlanningFlowSignals.CURRENT.set(view);
        return view;
    }

    // --- running --------------------------------------------------------------------------------

    @Override
    public String start(String flowId) {
        log.info("planning start({})", flowId);
        ConsoleContext.refuseIfWatching("plan stories");
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such planning run — close and re-open the wizard";
            }
            if (GuidedFlows.inProgress(flow)) {
                return "error: this plan is already running";
            }
            ArtifactStore store = context.store();
            UUID projectId = flow.projectId();
            // Refuse rather than plan against nothing. A planner given no agreed scope invents it,
            // and the stories it invents look exactly like stories drawn from real requirements.
            if (BacklogPlanning.activeCriteria(store, projectId) == 0) {
                String refusal = "error: no agreed requirement has a check on it yet, so there "
                    + "is nothing a story could deliver. Open the Requirements panel and agree the "
                    + "ones you want built — agreeing one accepts its checks too.";
                log.info("planning start({}) refused: no agreed criteria", flowId);
                return refusal;
            }
            if (BacklogAuthoring.unclaimedCriteria(store, projectId) == 0) {
                String refusal = "error: every check on every agreed requirement is already "
                    + "claimed by a story — there is nothing left to plan. Add requirements, or "
                    + "cancel a story to hand its checks back to planning.";
                log.info("planning start({}) refused: nothing unclaimed", flowId);
                return refusal;
            }
            String result = BacklogPlanning.launch(context, flow);
            log.info("planning start({}) -> {}", flowId, result.isEmpty() ? "running" : result);
            return result;
        } catch (Exception e) {
            log.warn("planning start({}) threw", flowId, e);
            return "error: " + e;
        }
    }

    @Override
    public String cancel(String flowId) {
        ConsoleContext context = ConsoleContext.get();
        GuidedFlow flow = load(context, flowId);
        if (flow == null) {
            return "error: no such planning run";
        }
        BacklogPlanning.cancel(flow.id());
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
    public String submitAnswers(String flowId) {
        // Logged at both ends: "nothing in the logs" makes it impossible to tell a call that never
        // arrived from one that arrived and failed.
        log.info("planning submitAnswers({})", flowId);
        ConsoleContext.refuseIfWatching("send the planner answers");
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                log.warn("planning submitAnswers: no flow {} in the current project", flowId);
                return "error: no such planning run — close and re-open the wizard";
            }
            if (flow.state() != GuidedFlowState.AWAITING_ANSWERS) {
                log.warn("planning submitAnswers: flow {} is {}, not AWAITING_ANSWERS", flowId,
                    flow.state());
                return "error: this plan is not waiting for answers (it is " + flow.state() + ")";
            }
            String result = BacklogPlanning.resume(context, flow);
            log.info("planning submitAnswers({}) -> {}", flowId,
                result.isEmpty() ? "resumed" : result);
            return result;
        } catch (Exception e) {
            log.warn("planning submitAnswers({}) threw", flowId, e);
            return "error: " + e;
        }
    }

    // --- review ---------------------------------------------------------------------------------

    @Override
    public String setAccepted(String flowId, String proposalId, boolean accepted) {
        ConsoleContext context = ConsoleContext.get();
        GuidedFlow flow = load(context, flowId);
        if (flow == null) {
            return "error: no such planning run";
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
            return "error: no such planning run";
        }
        if (flow.state() != GuidedFlowState.REVIEW) {
            return "error: there is nothing to review yet";
        }
        List<FlowProposal> proposals = new ArrayList<>(context.store().listFlowProposals(flow.id()));
        for (FlowProposal proposal : proposals) {
            // A conflict is a question, not a change — accepting one is choosing a side, and the
            // server refuses to write it. "Tick all" must not sweep it up.
            if (proposal.kind() != FlowProposalKind.CONFLICT) {
                proposal.setAccepted(accepted);
            }
        }
        context.store().saveFlowProposals(flow.id(), proposals);
        GuidedFlows.publish(context.store(), flow);
        return "";
    }

    @Override
    public String apply(String flowId) {
        log.info("planning apply({})", flowId);
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such planning run — close and re-open the wizard";
            }
            if (flow.state() != GuidedFlowState.REVIEW) {
                return "error: there is nothing to apply yet (the plan is " + flow.state() + ")";
            }
            String result = BacklogPlanning.apply(context, flow);
            log.info("planning apply({}) -> {}", flowId, result.isEmpty() ? "applied" : result);
            return result;
        } catch (Exception e) {
            log.warn("planning apply({}) threw", flowId, e);
            return "error: " + e;
        }
    }

    @Override
    public String reopen(String flowId) {
        ConsoleContext context = ConsoleContext.get();
        GuidedFlow flow = load(context, flowId);
        if (flow == null) {
            return "error: no such planning run";
        }
        if (GuidedFlows.inProgress(flow)) {
            return "error: cancel the running plan first";
        }
        clearWorkings(context.store(), flow);
        GuidedFlows.advance(context.store(), flow, GuidedFlowState.DRAFT, 0,
            "Ready to plan again over the backlog as it now stands");
        return "";
    }

    @Override
    public String startOver(String flowId) {
        log.info("planning startOver({})", flowId);
        ConsoleContext.refuseIfWatching("start the plan over");
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such planning run — close and re-open the wizard";
            }
            // Stop any run first, or its thread would publish over the cleared state on completion.
            BacklogPlanning.cancel(flow.id());
            clearWorkings(context.store(), flow);
            GuidedFlows.advance(context.store(), flow, GuidedFlowState.DRAFT, 0,
                "Ready to plan stories over the agreed requirements");
            return "";
        } catch (Exception e) {
            log.warn("planning startOver({}) threw", flowId, e);
            return "error: " + e;
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Questions and proposals belong to one run. Leaving the previous run's behind would show the
     * operator a review list assembled from two different readings of the requirement graph.
     */
    private static void clearWorkings(ArtifactStore store, GuidedFlow flow) {
        // Through the shared helper so any discussion held about a question dies with it — planning
        // offers no Discuss control today, and this is what stops that becoming a leak if it does.
        GuidedFlows.clearQuestions(store, flow.id());
        store.saveFlowProposals(flow.id(), List.of());
    }

    private interface Answering {
        void apply(FlowQuestion question);
    }

    private String answering(String flowId, String questionId, Answering change) {
        try {
            ConsoleContext context = ConsoleContext.get();
            GuidedFlow flow = load(context, flowId);
            if (flow == null) {
                return "error: no such planning run";
            }
            if (flow.state() != GuidedFlowState.AWAITING_ANSWERS) {
                return "error: this plan is not waiting for answers";
            }
            UUID id = parseUuid(questionId);
            List<FlowQuestion> questions =
                new ArrayList<>(context.store().listFlowQuestions(flow.id()));
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

    /** Loads a flow, refusing one belonging to a project other than the current one. */
    private static GuidedFlow load(ConsoleContext context, String flowId) {
        UUID projectId = context == null ? null : context.currentProjectId();
        UUID id = parseUuid(flowId);
        if (projectId == null || id == null) {
            return null;
        }
        GuidedFlow flow = context.store().getGuidedFlow(id);
        return flow != null && projectId.equals(flow.projectId())
            && flow.kind() == GuidedFlowKind.BACKLOG_PLANNING ? flow : null;
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
