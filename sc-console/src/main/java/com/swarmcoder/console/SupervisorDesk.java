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

import com.swarmcoder.console.api.AttentionItem;
import com.swarmcoder.console.api.Backlog;
import com.swarmcoder.console.api.FlowView;
import com.swarmcoder.console.api.SupervisorService;
import com.swarmcoder.domain.AutonomousDecision;
import com.swarmcoder.domain.AutonomousDecisionKind;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowKind;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryGraph;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The supervisor's side of the product: what needs it, the acts it may take, and the record of them.
 *
 * <p><b>Nothing is re-implemented here.</b> Every act is a call into the service a person's click
 * reaches ({@link GuidedFlowServiceImpl}, {@link PlanningFlowServiceImpl}, {@link BrdServiceImpl},
 * {@link BacklogServiceImpl}, {@link DecisionAnswers}), followed by one line in the project's
 * decision log with the actor "supervisor". What needs attention is read from the store by lookup;
 * no model is called anywhere in this class.
 *
 * <p><b>Waiting costs nothing.</b> {@link #waitForAttention} sleeps on a condition that the store
 * signals after every durable write, so it wakes when something changed and at no other time.
 */
public final class SupervisorDesk implements SupervisorService {

    private static final Logger log = LoggerFactory.getLogger(SupervisorDesk.class);

    /** Longest one wait may be: under the MCP transport's own reply limit. */
    static final int MAX_WAIT_SECONDS = 100;

    /** After a write, the least time before the store is looked at again; writes come in bursts. */
    private static final long LOOK_AGAIN_MILLIS = 1_000;

    /** How much of a stopped build's question an item carries. The whole text is one call away. */
    private static final int QUESTION_CHARS = 700;

    /** One stretch of supervision per process, so its entries can be read apart from a night's. */
    private static final UUID SESSION = UUID.randomUUID();

    private static final Bell BELL = new Bell();

    /** Rung by the store after every durable write; what {@link #waitForAttention} sleeps on. */
    static final class Bell implements Runnable {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition rang = lock.newCondition();
        private long rings;

        @Override
        public void run() {
            lock.lock();
            try {
                rings++;
                rang.signalAll();
            } finally {
                lock.unlock();
            }
        }

        long rings() {
            lock.lock();
            try {
                return rings;
            } finally {
                lock.unlock();
            }
        }

        /** Sleeps until the bell has rung since {@code seen}, or the time is up. */
        void await(long seen, long millis) throws InterruptedException {
            long nanos = TimeUnit.MILLISECONDS.toNanos(millis);
            lock.lock();
            try {
                while (rings == seen && nanos > 0) {
                    nanos = rang.awaitNanos(nanos);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    // --- what needs the supervisor ---------------------------------------------------------------

    @Override
    public AttentionItem nextAttention(int skip) {
        List<AttentionItem> items = attention();
        int index = Math.max(0, skip);
        return index < items.size() ? items.get(index).fitted() : null;
    }

    @Override
    public AttentionItem waitForAttention(int timeoutSeconds, int skip) {
        ConsoleContext context = ConsoleContext.get();
        if (context == null) {
            return null;
        }
        context.store().addWriteListener(BELL);
        long wait = Math.max(1, Math.min(timeoutSeconds <= 0 ? MAX_WAIT_SECONDS : timeoutSeconds,
            MAX_WAIT_SECONDS)) * 1_000L;
        long deadline = System.currentTimeMillis() + wait;
        try {
            while (true) {
                long seen = BELL.rings();
                long looked = System.currentTimeMillis();
                AttentionItem item = nextAttention(skip);
                if (item != null) {
                    return item;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return null;
                }
                BELL.await(seen, left);
                // A build writes the store many times a second. One look per burst is enough.
                long since = System.currentTimeMillis() - looked;
                long rest = Math.min(LOOK_AGAIN_MILLIS - since, deadline - System.currentTimeMillis());
                if (rest > 0) {
                    TimeUnit.MILLISECONDS.sleep(rest);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public String standing() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        if (projectId == null) {
            return "No project is selected.";
        }
        ArtifactStore store = context.store();
        List<Story> stories = store.listStories(projectId);
        long building = stories.stream().filter(s -> s.state() == StoryState.RUNNING).count();
        long done = stories.stream().filter(s -> s.state() == StoryState.DONE).count();
        StringBuilder sentence = new StringBuilder(projectName(store, projectId)).append(": ");
        GuidedFlow intake = flowOf(store, projectId, false);
        GuidedFlow planning = flowOf(store, projectId, true);
        if (intake != null && intake.state() == GuidedFlowState.RUNNING) {
            sentence.append("the analyst is reading the documents. ");
        }
        if (planning != null && planning.state() == GuidedFlowState.RUNNING) {
            sentence.append("the planner is planning stories. ");
        }
        sentence.append(stories.size()).append(" stories, ").append(done).append(" delivered, ")
            .append(building).append(" building.");
        if (building > 0) {
            sentence.append(" Nothing needs you until a build finishes or stops to ask.");
        }
        return sentence.toString();
    }

    /** Everything that needs the supervisor, most urgent first. */
    List<AttentionItem> attention() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        if (projectId == null) {
            return List.of();
        }
        ArtifactStore store = context.store();
        String project = projectName(store, projectId);
        List<Story> stories = new ArrayList<>(store.listStories(projectId));
        stories.sort(Comparator.comparingInt(Story::order).thenComparing(s -> nn(s.key())));
        List<AttentionItem> items = new ArrayList<>();

        // 1. A build that stopped mid-flight to ask: nothing else moves it.
        Set<UUID> runsHere = new HashSet<>();
        for (Story story : stories) {
            runsHere.addAll(story.runIds());
        }
        List<Decision> pending = new ArrayList<>();
        for (Decision decision : store.root().decisions.values()) {
            if (decision != null && decision.state() == DecisionState.PENDING
                    && decision.runId() != null) {
                Run run = store.root().runs.get(decision.runId());
                if (run != null && run.parkedAt() != null
                        && (projectId.equals(run.projectId()) || runsHere.contains(run.id()))) {
                    pending.add(decision);
                }
            }
        }
        pending.sort(Comparator.comparing((Decision d) -> d.createdAt() == null
            ? Instant.EPOCH : d.createdAt()));
        for (Decision decision : pending) {
            items.add(runQuestion(store, project, stories, decision, pending.size()));
        }

        // 2. Stories that stopped for good, then deliveries waiting for a verdict.
        for (Story story : stories) {
            if (story.state() == StoryState.BLOCKED) {
                items.add(new AttentionItem("STORY_STOPPED", project, name(story),
                    "This story's build stopped and nothing will try it again by itself. Send it "
                        + "back to be built again, saying what should be different?",
                    List.of("send_back: build it again; the note goes into the next build's brief"),
                    nn(story.waitingReason()).isEmpty()
                        ? lastRunLine(store, story) : story.waitingReason(),
                    "send_back story=" + story.key() + " note=<what to do differently>"));
            }
        }
        for (Story story : stories) {
            if (story.state() == StoryState.REVIEW) {
                items.add(delivery(store, project, story));
            }
        }

        // 3. The analyst, then agreeing what it drafted, then the planner.
        GuidedFlow intake = flowOf(store, projectId, false);
        boolean intakeBusy = flowItems(store, project, intake, "analyst", items);
        Brd brd = store.ensureBrd(projectId);
        List<BrdRequirement> drafts = new ArrayList<>();
        for (BrdRequirement requirement : brd.requirements()) {
            if (requirement.status() == RequirementStatus.DRAFT) {
                drafts.add(requirement);
            }
        }
        if (!drafts.isEmpty() && !intakeBusy) {
            items.add(new AttentionItem("AGREE_REQUIREMENTS", project, "",
                drafts.size() + " drafted requirement(s) are not agreed. Agreeing one makes it "
                    + "scope and accepts its checks; only agreed requirements are planned and "
                    + "built.",
                List.of("agree_requirements: agree all drafts, or one by its handle"),
                listed(drafts.stream().map(r -> r.handle() + " " + nn(r.title())).toList(), 500),
                "agree_requirements requirement=all"));
        }
        GuidedFlow planning = flowOf(store, projectId, true);
        boolean planningBusy = flowItems(store, project, planning, "planner", items);

        // 4. Suggested stories, then the things nobody has started yet.
        List<Story> suggested = stories.stream()
            .filter(s -> s.state() == StoryState.DRAFT).toList();
        if (!suggested.isEmpty() && !planningBusy) {
            items.add(new AttentionItem("PROMOTE_STORIES", project, "",
                suggested.size() + " suggested stor" + (suggested.size() == 1 ? "y is" : "ies are")
                    + " not marked ready to build. Only a ready story is ever started.",
                List.of("promote_story: mark one ready by its key, or all"),
                listed(suggested.stream().map(SupervisorDesk::name).toList(), 500),
                "promote_story story=all"));
        }
        if (intake != null && intake.state() == GuidedFlowState.DRAFT
                && RequirementsIntake.includedDocuments(intake) > 0) {
            items.add(new AttentionItem("START_ANALYST", project, "",
                RequirementsIntake.includedDocuments(intake) + " document(s) are loaded and have "
                    + "not been read into requirements. Start the analyst?",
                List.of("start_flow: the analyst reads them; it may come back with questions"),
                "", "start_flow flow=analyst"));
        }
        boolean planningIdle = planning == null || planning.state() == GuidedFlowState.DRAFT
            || planning.state() == GuidedFlowState.APPLIED;
        if (planningIdle && drafts.isEmpty() && !intakeBusy) {
            int unclaimed = BacklogAuthoring.unclaimedCriteria(store, projectId);
            if (unclaimed > 0) {
                items.add(new AttentionItem("START_PLANNER", project, "",
                    unclaimed + " agreed check(s) are not covered by any story. Start the planner?",
                    List.of("start_flow: the planner proposes stories; it may come back with "
                        + "questions"),
                    "", "start_flow flow=planner"));
            }
        }
        boolean queueStartsStories = context.supervisedMode() || context.unattendedMode();
        boolean building = stories.stream().anyMatch(s -> s.state() == StoryState.RUNNING);
        if (!queueStartsStories && !building) {
            for (Story story : stories) {
                if (story.state() == StoryState.READY && StoryGraph.isStartable(story, stories)) {
                    items.add(new AttentionItem("START_STORY", project, name(story),
                        "This story is ready, everything it builds on is delivered, and nothing "
                            + "is building. Start it?",
                        List.of("start_story: start its build"), "",
                        "start_story story=" + story.key()));
                    break;
                }
            }
        }

        // 5. Nothing left at all: said once, so the supervisor knows to stop waiting.
        if (items.isEmpty() && !stories.isEmpty() && !intakeBusy && !planningBusy
                && stories.stream().allMatch(s -> s.state() == StoryState.DONE
                    || s.state() == StoryState.CANCELLED)) {
            long done = stories.stream().filter(s -> s.state() == StoryState.DONE).count();
            items.add(new AttentionItem("FINISHED", project, "",
                "Every story of this project is delivered or dropped (" + done + " delivered). "
                    + "Nothing is building and nothing is waiting.",
                List.of(), "", ""));
        }
        return items;
    }

    private AttentionItem runQuestion(ArtifactStore store, String project, List<Story> stories,
                                      Decision decision, int alsoOpen) {
        Run run = store.root().runs.get(decision.runId());
        Story story = stories.stream().filter(s -> s.runIds().contains(decision.runId()))
            .findFirst().orElse(null);
        String brief = nn(decision.briefMarkdown()).strip();
        boolean cut = brief.length() > QUESTION_CHARS;
        String question = cut ? brief.substring(0, QUESTION_CHARS).stripTrailing() + "…" : brief;
        StringBuilder evidence = new StringBuilder("The build stopped at the ")
            .append(run.state()).append(" stage and stays stopped until this is answered.");
        if (alsoOpen > 1) {
            evidence.append(' ').append(alsoOpen - 1)
                .append(" other question(s) from stopped builds are open.");
        }
        if (cut) {
            evidence.append(" Whole question: decision_text decision_id=").append(decision.id());
        }
        List<String> options = DecisionAnswers.optionsFor(decision.kind()).stream()
            .map(option -> option.token() + ": " + option.description()).toList();
        return new AttentionItem("RUN_QUESTION", project, story == null ? "" : name(story),
            question, options, evidence.toString(),
            "answer_question decision_id=" + decision.id() + " answer=<token> text=<optional>");
    }

    private AttentionItem delivery(ArtifactStore store, String project, Story story) {
        UnattendedAcceptance.Verdict verdict = UnattendedAcceptance.judge(store, story);
        int checks = story.criterionIds().size();
        StringBuilder evidence = new StringBuilder();
        if (verdict.acceptable()) {
            evidence.append("Every check it promised (").append(checks)
                .append(") was proved by a test that ran in its build.");
        } else {
            evidence.append("The application cannot vouch for it: ").append(verdict.reason())
                .append('.');
        }
        List<UUID> runs = story.runIds();
        if (!runs.isEmpty()) {
            evidence.append(" To read the code: run_diff run_id=").append(runs.get(runs.size() - 1));
        }
        return new AttentionItem("DELIVERY", project, name(story),
            "This story's build finished and its work is back for a verdict. Accepting puts its "
                + "code on the delivery branch, which the next story is cut from. Is it what was "
                + "asked for?" + (nn(story.narrative()).isEmpty() ? ""
                    : " The story: " + clip(story.narrative(), 300)),
            List.of("accept_delivery: accept it",
                "send_back: build it again; the note says what was wrong"),
            evidence.toString(),
            "accept_delivery story=" + story.key() + "  |  send_back story=" + story.key()
                + " note=<what was wrong>");
    }

    /**
     * Adds what one flow needs, if anything.
     *
     * @return true while the flow is mid-flight, so what comes after it must wait
     */
    private boolean flowItems(ArtifactStore store, String project, GuidedFlow flow, String word,
                              List<AttentionItem> items) {
        if (flow == null || flow.state() == null) {
            return false;
        }
        String who = word.equals("analyst") ? "The analyst" : "The planner";
        String upper = word.toUpperCase(Locale.ROOT);
        switch (flow.state()) {
            case RUNNING -> {
                return true;
            }
            case AWAITING_ANSWERS -> {
                List<FlowQuestion> questions = store.listFlowQuestions(flow.id());
                List<FlowQuestion> open = questions.stream().filter(q -> !q.skipped()
                    && (q.answer() == null || q.answer().isBlank())).toList();
                if (open.isEmpty()) {
                    items.add(new AttentionItem(upper + "_SUBMIT", project, "",
                        "Every question of this round has an answer. Submit them so the "
                            + word + " carries on?",
                        List.of("submit_answers: carry on with the answers given"), "",
                        "submit_answers flow=" + word));
                    return true;
                }
                FlowQuestion question = open.get(0);
                List<String> options = new ArrayList<>();
                for (String option : question.options() == null
                        ? List.<String>of() : question.options()) {
                    options.add(option);
                }
                options.add("(or any text of your own; \"skip\" makes the " + word
                    + " state its own assumption)");
                StringBuilder evidence = new StringBuilder();
                evidence.append("Question 1 of ").append(open.size()).append(" still open.");
                if (!nn(question.sourceQuote()).isEmpty()) {
                    evidence.append(" The document says: \"")
                        .append(clip(question.sourceQuote(), 300)).append('"');
                }
                if (!nn(question.background()).isEmpty()) {
                    evidence.append(" Why it asks: ").append(clip(question.background(), 300));
                }
                items.add(new AttentionItem(upper + "_QUESTION", project, "",
                    who + " asks" + (nn(question.subject()).isEmpty() ? ""
                        : " about " + question.subject()) + ": " + nn(question.text()),
                    options, evidence.toString(),
                    "answer_flow_question flow=" + word + " question_id=" + question.id()
                        + " answer=<answer> note=<optional>; after the last one, submit_answers"));
                return true;
            }
            case REVIEW -> {
                List<FlowProposal> proposals = store.listFlowProposals(flow.id());
                String what = word.equals("analyst") ? "change(s) to the requirements"
                    : "stor" + (proposals.size() == 1 ? "y" : "ies");
                items.add(new AttentionItem(upper + "_PROPOSALS", project, "",
                    who + " proposes " + proposals.size() + " " + what + ". Nothing is written "
                        + "until they are applied. Apply them all, or all but some?",
                    List.of("apply_proposals: write them; reject=<ids> leaves those out",
                        "view_flow: every proposal in full, with its id"),
                    listed(proposals.stream().map(p -> p.kind() + " " + nn(p.handle()) + " "
                        + nn(p.title())).toList(), 600),
                    "apply_proposals flow=" + word));
                return true;
            }
            case FAILED -> {
                items.add(new AttentionItem(upper + "_FAILED", project, "",
                    who + " failed and will not try again by itself. Start it again?",
                    List.of("start_flow: run it again from the start"),
                    clip(nn(flow.error()), 500), "start_flow flow=" + word));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // --- documents, analyst and planner ----------------------------------------------------------

    @Override
    public String addDocument(String title, String text, boolean technical) {
        GuidedFlowServiceImpl intake = new GuidedFlowServiceImpl();
        FlowView view = intake.intake();
        if (view == null || view.flow() == null) {
            return "error: no project is selected";
        }
        String flowId = view.flow().id().toString();
        Set<UUID> before = new HashSet<>();
        view.documents().forEach(document -> before.add(document.id()));
        String result = intake.addPastedDocument(flowId, title, text);
        if (failed(result)) {
            return result;
        }
        if (technical) {
            for (SourceDocument document : intake.intake().documents()) {
                if (!before.contains(document.id())) {
                    String marked = intake.setDocumentTechnical(flowId, document.id().toString(),
                        true);
                    if (failed(marked)) {
                        return marked;
                    }
                }
            }
        }
        return "";
    }

    @Override
    public FlowView flow(String flow) {
        return isPlanner(flow) ? new PlanningFlowServiceImpl().planning()
            : new GuidedFlowServiceImpl().intake();
    }

    @Override
    public String startFlow(String flow) {
        FlowView view = flow(flow);
        if (view == null || view.flow() == null) {
            return "error: no project is selected";
        }
        String flowId = view.flow().id().toString();
        GuidedFlowState state = view.flow().state();
        boolean reopen = state == GuidedFlowState.APPLIED || state == GuidedFlowState.FAILED;
        if (isPlanner(flow)) {
            PlanningFlowServiceImpl planning = new PlanningFlowServiceImpl();
            String reopened = reopen ? planning.reopen(flowId) : "";
            return failed(reopened) ? reopened : planning.start(flowId);
        }
        GuidedFlowServiceImpl intake = new GuidedFlowServiceImpl();
        String reopened = reopen ? intake.reopen(flowId) : "";
        return failed(reopened) ? reopened : intake.start(flowId);
    }

    @Override
    public String answerQuestion(String flow, String questionId, String answer, String note) {
        FlowView view = flow(flow);
        if (view == null || view.flow() == null) {
            return "error: no project is selected";
        }
        FlowQuestion question = view.questions().stream()
            .filter(q -> q.id() != null && q.id().toString().equals(nn(questionId).strip()))
            .findFirst().orElse(null);
        if (question == null) {
            return "error: this flow has no question with that id";
        }
        String flowId = view.flow().id().toString();
        String said = nn(answer).strip();
        String noted = nn(note).strip();
        boolean skip = said.equalsIgnoreCase("skip") && noted.isEmpty();
        boolean planner = isPlanner(flow);
        String result;
        if (skip) {
            result = planner ? new PlanningFlowServiceImpl().skip(flowId, questionId.strip())
                : new GuidedFlowServiceImpl().skip(flowId, questionId.strip());
        } else {
            if (said.isEmpty()) {
                return "error: an answer with no words records nothing; say \"skip\" to skip it";
            }
            result = planner ? new PlanningFlowServiceImpl().answer(flowId, questionId.strip(), said)
                : new GuidedFlowServiceImpl().answer(flowId, questionId.strip(), said);
            if (!failed(result) && !noted.isEmpty()) {
                result = planner
                    ? new PlanningFlowServiceImpl().note(flowId, questionId.strip(), noted)
                    : new GuidedFlowServiceImpl().note(flowId, questionId.strip(), noted);
            }
        }
        if (!failed(result)) {
            record(AutonomousDecisionKind.ANSWERED_QUESTION,
                nn(question.subject()).isEmpty() ? (planner ? "Planning" : "Requirements")
                    : question.subject(),
                nn(question.text()),
                skip ? "Skipped: left to the " + (planner ? "planner" : "analyst")
                    + " to state its own assumption."
                    : said + (noted.isEmpty() ? "" : " (" + noted + ")"),
                false, view.flow().id(), question.id());
        }
        return result;
    }

    @Override
    public String submitAnswers(String flow) {
        FlowView view = flow(flow);
        if (view == null || view.flow() == null) {
            return "error: no project is selected";
        }
        String flowId = view.flow().id().toString();
        return isPlanner(flow) ? new PlanningFlowServiceImpl().submitAnswers(flowId)
            : new GuidedFlowServiceImpl().submitAnswers(flowId);
    }

    @Override
    public String applyProposals(String flow, String rejectIdsCsv) {
        FlowView view = flow(flow);
        if (view == null || view.flow() == null) {
            return "error: no project is selected";
        }
        boolean planner = isPlanner(flow);
        String flowId = view.flow().id().toString();
        Set<String> reject = new HashSet<>();
        for (String id : nn(rejectIdsCsv).split(",")) {
            if (!id.isBlank()) {
                reject.add(id.strip());
            }
        }
        Set<String> known = new HashSet<>();
        view.proposals().forEach(proposal -> known.add(String.valueOf(proposal.id())));
        for (String id : reject) {
            if (!known.contains(id)) {
                return "error: no proposal has the id " + id + "; nothing was applied";
            }
        }
        PlanningFlowServiceImpl planning = new PlanningFlowServiceImpl();
        GuidedFlowServiceImpl intake = new GuidedFlowServiceImpl();
        String result = planner ? planning.setAllAccepted(flowId, true)
            : intake.setAllAccepted(flowId, true);
        List<String> rejected = new ArrayList<>();
        for (FlowProposal proposal : view.proposals()) {
            if (failed(result)) {
                return result;
            }
            String id = String.valueOf(proposal.id());
            if (reject.contains(id)) {
                rejected.add((nn(proposal.handle()) + " " + nn(proposal.title())).strip());
                result = planner ? planning.setAccepted(flowId, id, false)
                    : intake.setAccepted(flowId, id, false);
            }
        }
        if (failed(result)) {
            return result;
        }
        result = planner ? planning.apply(flowId) : intake.apply(flowId);
        if (!failed(result)) {
            int total = view.proposals().size();
            record(planner ? AutonomousDecisionKind.ACCEPTED_STORIES
                    : AutonomousDecisionKind.ACCEPTED_PROPOSALS,
                planner ? "The planner's stories" : "The analyst's requirements",
                total + " proposal(s) were offered.",
                "Applied " + (total - rejected.size()) + " of " + total
                    + (rejected.isEmpty() ? "." : "; left out: " + String.join("; ", rejected)
                        + "."),
                true, view.flow().id(), null);
        }
        return result;
    }

    // --- gates -----------------------------------------------------------------------------------

    @Override
    public String agreeRequirements(String requirement) {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        if (projectId == null) {
            return "error: no project is selected";
        }
        String wanted = nn(requirement).strip();
        BrdServiceImpl brdService = new BrdServiceImpl();
        if (wanted.isEmpty() || wanted.equalsIgnoreCase("all")) {
            String result = brdService.promoteAllDraftsAs(ACTOR);
            if (!failed(result)) {
                record(AutonomousDecisionKind.AGREED_REQUIREMENTS, "Every drafted requirement",
                    "Agree the drafted requirements as scope?", clip(result.isEmpty()
                        ? "Agreed." : result, 1_000), true, null, null);
            }
            return result;
        }
        BrdRequirement found = null;
        for (BrdRequirement candidate : context.store().ensureBrd(projectId).requirements()) {
            if (wanted.equalsIgnoreCase(candidate.handle())
                    || wanted.equals(String.valueOf(candidate.id()))) {
                found = candidate;
            }
        }
        if (found == null) {
            return "error: this project has no requirement " + wanted;
        }
        String result = brdService.promoteRequirementAs(found.id().toString(), ACTOR);
        if (!failed(result)) {
            record(AutonomousDecisionKind.AGREED_REQUIREMENTS,
                found.handle() + " " + nn(found.title()), "Agree this requirement as scope?",
                "Agreed, and its checks accepted.", true, null, null);
        }
        return result;
    }

    @Override
    public Backlog backlog() {
        return new BacklogServiceImpl().backlog();
    }

    @Override
    public String promoteStory(String story) {
        BacklogServiceImpl backlog = new BacklogServiceImpl();
        if (nn(story).strip().equalsIgnoreCase("all")) {
            List<String> refused = new ArrayList<>();
            int promoted = 0;
            for (Story candidate : stories()) {
                if (candidate.state() != StoryState.DRAFT) {
                    continue;
                }
                String result = backlog.promoteStoryAs(candidate.id().toString(), ACTOR);
                if (failed(result)) {
                    refused.add(result.substring("error:".length()).strip());
                } else {
                    promoted++;
                    record(AutonomousDecisionKind.PROMOTED_STORY, name(candidate),
                        "Mark this suggested story ready to build?", "Marked ready.", true, null,
                        null);
                }
            }
            if (promoted == 0 && refused.isEmpty()) {
                return "error: there are no suggested stories to mark ready";
            }
            return promoted + " marked ready." + (refused.isEmpty() ? ""
                : " Not marked: " + String.join(" ", refused));
        }
        Story found = story(story);
        if (found == null) {
            return "error: this project has no story " + story;
        }
        String result = backlog.promoteStoryAs(found.id().toString(), ACTOR);
        if (!failed(result)) {
            record(AutonomousDecisionKind.PROMOTED_STORY, name(found),
                "Mark this suggested story ready to build?", "Marked ready.", true, null, null);
        }
        return result;
    }

    @Override
    public String startStory(String story) {
        Story found = story(story);
        if (found == null) {
            return "error: this project has no story " + story;
        }
        String result = new BacklogServiceImpl().startSession(found.id().toString());
        if (!failed(result)) {
            record(AutonomousDecisionKind.STARTED_STORY, name(found), "Start this story's build?",
                "Started.", true, null, null);
        }
        return result;
    }

    @Override
    public String acceptDelivery(String story) {
        Story found = story(story);
        if (found == null) {
            return "error: this project has no story " + story;
        }
        ConsoleContext context = ConsoleContext.get();
        UnattendedAcceptance.Verdict verdict = UnattendedAcceptance.judge(context.store(), found);
        String result = new BacklogServiceImpl().acceptStoryAs(found.id().toString(), ACTOR);
        if (!failed(result)) {
            record(AutonomousDecisionKind.ACCEPTED_DELIVERY, name(found),
                "Is this delivery what was asked for?",
                "Accepted. " + (verdict.acceptable()
                    ? "Every check it promised had been proved by its build."
                    : "The application could not vouch for it: " + verdict.reason() + "."),
                verdict.acceptable(), null, null);
        }
        return result;
    }

    @Override
    public String sendBack(String story, String note) {
        Story found = story(story);
        if (found == null) {
            return "error: this project has no story " + story;
        }
        if (nn(note).isBlank()) {
            return "error: say why it is being sent back; the note is what the next build is told";
        }
        String was = found.state() == null ? "" : found.state().label();
        String result = new BacklogServiceImpl().retryStoryAs(found.id().toString(), note.strip(),
            ACTOR);
        if (!failed(result)) {
            record(AutonomousDecisionKind.SENT_BACK_DELIVERY, name(found),
                "It was " + was + ". Build it again?", "Sent back: " + note.strip(), true, null,
                null);
        }
        return result;
    }

    // --- a build that stopped to ask -------------------------------------------------------------

    @Override
    public List<String> decisionOptions(String decisionId) {
        Decision decision = decision(decisionId);
        return decision == null ? List.of() : DecisionAnswers.optionsFor(decision.kind()).stream()
            .map(option -> option.token() + ": " + option.description()).toList();
    }

    @Override
    public String decisionText(String decisionId) {
        Decision decision = decision(decisionId);
        return decision == null ? "" : nn(decision.briefMarkdown());
    }

    @Override
    public String answerDecision(String decisionId, String answer, String text) {
        Decision decision = decision(decisionId);
        DecisionAnswers.Outcome outcome =
            DecisionAnswers.answerAndResume(decisionId, answer, text, ACTOR);
        if (!outcome.ok()) {
            return "error: " + outcome.error();
        }
        String subject = "A stopped build";
        if (decision != null && decision.runId() != null) {
            for (Story story : stories()) {
                if (story.runIds().contains(decision.runId())) {
                    subject = name(story);
                }
            }
        }
        record(AutonomousDecisionKind.ANSWERED_RUN_QUESTION, subject,
            decision == null ? "" : clip(nn(decision.briefMarkdown()), 2_000),
            nn(answer).strip() + (nn(text).isBlank() ? "" : ": " + text.strip()) + " — "
                + outcome.note(),
            true, null, decision == null ? null : decision.id());
        return outcome.note();
    }

    // --- the record ------------------------------------------------------------------------------

    @Override
    public List<AutonomousDecision> decisionLog() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        if (projectId == null) {
            return List.of();
        }
        return context.store().listAutonomousDecisions(projectId).stream()
            .filter(AutonomousDecision::bySupervisor).toList();
    }

    private void record(AutonomousDecisionKind kind, String subject, String question,
                        String answer, boolean grounded, UUID flowId, UUID questionId) {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        if (projectId == null) {
            return;
        }
        AutonomousDecision decision = new AutonomousDecision(UUID.randomUUID(), projectId, SESSION,
            Instant.now(), kind, subject, question, answer,
            "Decided by the supervising model over the MCP connection.", grounded, flowId,
            questionId);
        decision.setActor(ACTOR);
        try {
            context.store().recordAutonomousDecision(decision);
        } catch (RuntimeException e) {
            // The act is already done and durable; losing its line is reported, never hidden.
            log.warn("A supervisor decision could not be written to the log ({} - {}): {}",
                kind, subject, e.toString());
        }
    }

    // --- lookups ---------------------------------------------------------------------------------

    private static List<Story> stories() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        return projectId == null ? List.of() : context.store().listStories(projectId);
    }

    /** A story of the current project, by key ("S3") or id. */
    private static Story story(String keyOrId) {
        String wanted = nn(keyOrId).strip();
        for (Story story : stories()) {
            if (wanted.equalsIgnoreCase(story.key()) || wanted.equals(String.valueOf(story.id()))) {
                return story;
            }
        }
        return null;
    }

    private static Decision decision(String decisionId) {
        ConsoleContext context = ConsoleContext.get();
        if (context == null) {
            return null;
        }
        try {
            return context.store().root().decisions.get(UUID.fromString(nn(decisionId).strip()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static GuidedFlow flowOf(ArtifactStore store, UUID projectId, boolean planning) {
        for (GuidedFlow flow : store.listGuidedFlows(projectId)) {
            if (flow.kind() == (planning ? GuidedFlowKind.BACKLOG_PLANNING
                    : GuidedFlowKind.REQUIREMENTS_INTAKE)) {
                return flow;
            }
        }
        return null;
    }

    private static String lastRunLine(ArtifactStore store, Story story) {
        List<UUID> runs = story.runIds();
        Run run = runs.isEmpty() ? null : store.root().runs.get(runs.get(runs.size() - 1));
        if (run == null) {
            return "No build is recorded against it.";
        }
        return "Its last build ended at the " + run.state() + " stage"
            + (nn(run.parkReason()).isEmpty() ? "." : ": " + clip(run.parkReason(), 400))
            + " Why: run_diagnosis run_id=" + run.id();
    }

    private static String projectName(ArtifactStore store, UUID projectId) {
        Project project = store.getProject(projectId);
        return project == null || nn(project.name()).isEmpty() ? "this project" : project.name();
    }

    private static boolean isPlanner(String flow) {
        return nn(flow).strip().toLowerCase(Locale.ROOT).startsWith("plan");
    }

    private static boolean failed(String result) {
        return result != null && result.startsWith("error:");
    }

    private static String name(Story story) {
        return (nn(story.key()) + " " + nn(story.title())).strip();
    }

    /** A list as one line, cut at a whole entry, saying how many were left out. */
    private static String listed(List<String> entries, int max) {
        StringBuilder line = new StringBuilder();
        int shown = 0;
        for (String entry : entries) {
            String next = clip(entry, 90);
            if (line.length() + next.length() + 2 > max) {
                break;
            }
            line.append(shown == 0 ? "" : "; ").append(next);
            shown++;
        }
        if (shown < entries.size()) {
            line.append(" (and ").append(entries.size() - shown).append(" more)");
        }
        return line.toString();
    }

    private static String clip(String text, int max) {
        String flat = nn(text).replaceAll("\\s*\\R\\s*", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1).stripTrailing() + "…";
    }

    private static String nn(String value) {
        return value == null ? "" : value;
    }
}
