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

import com.swarmcoder.domain.AgreementGate;
import com.swarmcoder.domain.AutonomousDecisionKind;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The front half of the pipeline, driven with nobody present: a loaded document becomes agreed
 * requirements, then stories, then a running build.
 *
 * <p>{@link UnattendedPilot} already did the back half - accept what is proved finished, start what
 * is ready. This is the part before it, and it is the part that has a person's judgement in it. The
 * gates it now passes on the operator's behalf are, in order:
 *
 * <ol>
 *   <li>start the reading of the documents;</li>
 *   <li>answer the analyst's clarifications ({@link AutonomousAnswers});</li>
 *   <li>accept the requirements it drafted;</li>
 *   <li>agree those requirements as scope - the gate the product calls the one that matters most;</li>
 *   <li>ask the planner for stories, and answer its clarifications;</li>
 *   <li>accept the stories and mark them ready to build.</li>
 * </ol>
 *
 * <p><b>One step per tick, on the pilot's slow timer.</b> Not a loop. Two of these steps take
 * minutes of model time, everything is persisted between them, and a pass that did the whole front
 * half in one go would be unstoppable for as long as it ran. Stepping means the stop button, the
 * budget and the clock are all checked between every act.
 *
 * <p><b>What it refuses to do.</b>
 *
 * <ul>
 *   <li><b>It never answers a stopped build's question.</b> A {@code BLOCKED_TASK} decision means
 *       the swarm could not do the work. There is no answer to it - see {@link #noteBlockedTasks}.</li>
 *   <li><b>It never agrees a requirement the agreement gate refuses.</b> That gate says nothing
 *       becomes scope unless a check names a test that could prove it, and running unattended is
 *       exactly when that rule earns its keep. Held-back requirements are named on the record and
 *       left as drafts.</li>
 *   <li><b>It never applies a conflict or a retirement.</b> The wizard's own tick-all leaves both
 *       alone - a conflict is a question and a retirement is a judgement about work that may
 *       already have code against it - and this uses that same tick-all rather than a second one
 *       with different rules.</li>
 *   <li><b>It never retries a genuine failure.</b> A failed reading, a failed plan, or a
 *       {@code BLOCKED_TASK} the swarm truly could not get past stops the session or the story with
 *       the reason on the record, for the same cause the pilot has always given: a retry nobody read
 *       the failure for is how a night is spent producing the same error eight times.</li>
 *   <li><b>A PARKED story is the one exception, and it gets exactly one.</b> Since 2026-09-03,
 *       {@link #retryParkedStories} sends a story whose run stopped mid-flight back to be built
 *       again once, automatically — the park this exists for was a one-off bad model reply that a
 *       plain restart fixed, not a genuine failure. A second park on the same story falls back to
 *       the rule above and is left for a person.</li>
 * </ul>
 */
final class AutonomousBuild {

    private static final Logger log = LoggerFactory.getLogger(AutonomousBuild.class);

    /** The actor written on every change this makes, so the journal never calls it a person. */
    static final String ACTOR = "unattended";

    private AutonomousBuild() {}

    /**
     * Does at most one thing, and says what it did.
     *
     * <p>Called from the pilot's tick after the stop conditions have been checked. Never throws:
     * the pilot's night must survive one bad step, and every failure that matters is already
     * written to the record before this returns.
     */
    static void step(ConsoleContext context, AutonomousMode.Session session) {
        try {
            ArtifactStore store = context.store();
            UUID projectId = session.projectId();
            if (intakeStep(context, session, store, projectId)) {
                return;
            }
            if (agreementStep(context, session, store, projectId)) {
                return;
            }
            if (planningStep(context, session, store, projectId)) {
                return;
            }
            if (storyStep(context, session, store, projectId)) {
                return;
            }
            retryParkedStories(context, session, store, projectId);
            noteBlockedTasks(context, session, store, projectId);
            finishIfNothingLeft(context, session, store, projectId);
        } catch (Exception e) {
            log.warn("Autonomous step failed: {}", e.toString(), e);
        }
    }

    // --- 1..3: reading the documents --------------------------------------------------------------

    /** @return true when this tick's one action was spent here */
    private static boolean intakeStep(ConsoleContext context, AutonomousMode.Session session,
                                      ArtifactStore store, UUID projectId) {
        GuidedFlow flow = GuidedFlows.ensureIntake(store, projectId);
        GuidedFlowServiceImpl intake = new GuidedFlowServiceImpl();
        String flowId = flow.id().toString();
        switch (flow.state()) {
            case DRAFT -> {
                if (flow.documents().isEmpty()) {
                    // No document to read is not by itself a reason to stop: a project whose
                    // requirements were typed in by hand has none and is perfectly buildable.
                    // Whether there is anything here at all is decided at the end, once every
                    // other source of work has been looked at.
                    return false;
                }
                if (RequirementsIntake.includedDocuments(flow) == 0) {
                    return false;   // everything already read; move on to agreeing what it found
                }
                session.setActivity("Reading your documents");
                String refused = intake.start(flowId);
                if (refused != null && refused.startsWith("error:")) {
                    AutonomousMode.halt("It could not start reading your documents. "
                        + refused.substring("error:".length()).strip());
                }
                return true;
            }
            case RUNNING -> {
                session.setActivity(flow.stepLabel() == null ? "Reading" : flow.stepLabel());
                return true;    // the analyst is working; nothing to decide this tick
            }
            case AWAITING_ANSWERS -> {
                session.setActivity("Answering the questions about your documents");
                AutonomousAnswers.Outcome outcome =
                    AutonomousAnswers.answerAll(context, session, flow, false);
                log.info("Autonomous: answered {} question(s) about the documents, {} of them made "
                    + "up, {} skipped", outcome.answered(), outcome.invented(), outcome.skipped());
                String refused = intake.submitAnswers(flowId);
                if (refused != null && refused.startsWith("error:")) {
                    AutonomousMode.halt("It answered the questions about your documents but could "
                        + "not carry on. " + refused.substring("error:".length()).strip());
                }
                return true;
            }
            case REVIEW -> {
                session.setActivity("Accepting the requirements it drafted");
                acceptEverythingProposed(context, session, store, intake, flow, flowId);
                return true;
            }
            case FAILED -> {
                AutonomousMode.halt("Reading your documents failed, and nothing is retried on its "
                    + "own. " + (flow.error() == null ? "" : flow.error()));
                return true;
            }
            default -> {
                return false;   // APPLIED - the requirements exist; on to agreeing them
            }
        }
    }

    private static void acceptEverythingProposed(ConsoleContext context,
                                                 AutonomousMode.Session session,
                                                 ArtifactStore store, GuidedFlowServiceImpl intake,
                                                 GuidedFlow flow, String flowId) {
        int proposals = store.listFlowProposals(flow.id()).size();
        // The wizard's own tick-all, not a second one: it leaves conflicts and retirements alone,
        // and those exclusions are exactly the ones an absent operator most needs kept.
        intake.setAllAccepted(flowId, true);
        AutonomousMode.write(store, session, AutonomousDecisionKind.ACCEPTED_PROPOSALS,
            "What your documents say", null,
            "Accepted " + proposals + (proposals == 1 ? " proposed change" : " proposed changes")
                + " into the requirements",
            "Nobody read these before they were accepted. Contradictions and retirements were left "
                + "alone, as they are when a person ticks everything.",
            true, flow.id(), null);
        String refused = intake.apply(flowId);
        if (refused != null && refused.startsWith("error:")) {
            AutonomousMode.halt("It could not write the requirements it found. "
                + refused.substring("error:".length()).strip());
        }
    }

    // --- 4: agreeing them as scope ------------------------------------------------------------

    /**
     * Agrees every draft the agreement gate allows, and names the ones it does not.
     *
     * <p>This is the gate the product calls the one that matters most, and it is the one this whole
     * feature exists to pass. What it does NOT do is lower it: a requirement with no check that
     * names a test cannot be proved by anything, so it stays a draft here exactly as it would in
     * front of a person, and it is named on the record rather than skipped in silence.
     */
    private static boolean agreementStep(ConsoleContext context, AutonomousMode.Session session,
                                         ArtifactStore store, UUID projectId) {
        Brd brd = store.getBrd(projectId);
        if (brd == null || brd.requirements() == null) {
            return false;
        }
        List<BrdRequirement> drafts = new ArrayList<>();
        for (BrdRequirement requirement : brd.requirements()) {
            if (requirement.status() == RequirementStatus.DRAFT) {
                drafts.add(requirement);
            }
        }
        if (drafts.isEmpty()) {
            return false;
        }
        session.setActivity("Agreeing what it found");
        int agreed = 0;
        int checks = 0;
        List<String> held = new ArrayList<>();
        for (BrdRequirement requirement : drafts) {
            String refusal = AgreementGate.rejectionFor(requirement);
            if (refusal != null) {
                held.add(requirement.handle() + ": " + refusal);
                continue;
            }
            checks += BrdServiceImpl.promote(requirement);
            agreed++;
        }
        if (agreed > 0) {
            brd.setRequirements(new ArrayList<>(brd.requirements()));
            store.saveBrd(brd, ACTOR, "agreed " + agreed + " requirements and accepted " + checks
                + (checks == 1 ? " check" : " checks") + " while running on its own");
            BrdAuthoring.pushChanged(brd);
            AutonomousMode.write(store, session, AutonomousDecisionKind.AGREED_REQUIREMENTS,
                "The scope of the work", null,
                "Agreed " + agreed + (agreed == 1 ? " requirement" : " requirements")
                    + " and accepted " + checks + (checks == 1 ? " check" : " checks"),
                "This is the decision that says \"this is what must be true\". It was taken without "
                    + "you reading them. Everything built from here answers to these.",
                true, null, null);
        }
        if (!held.isEmpty()) {
            AutonomousMode.write(store, session, AutonomousDecisionKind.REFUSED,
                "Requirements it would not agree", null,
                held.size() + (held.size() == 1 ? " requirement was" : " requirements were")
                    + " left as a draft",
                "Nothing named a test that could prove them, so nothing could ever show they were "
                    + "met. They were not built. " + String.join(" ", held),
                true, null, null);
        }
        if (agreed == 0) {
            AutonomousMode.halt("None of the requirements it found could be agreed, because none "
                + "of them names a test that could prove it. There is nothing it can build.");
        }
        return true;
    }

    // --- 5: planning the work -------------------------------------------------------------------

    private static boolean planningStep(ConsoleContext context, AutonomousMode.Session session,
                                        ArtifactStore store, UUID projectId) {
        GuidedFlow flow = GuidedFlows.ensurePlanning(store, projectId);
        PlanningFlowServiceImpl planner = new PlanningFlowServiceImpl();
        String flowId = flow.id().toString();
        switch (flow.state()) {
            case DRAFT -> {
                if (BacklogAuthoring.unclaimedCriteria(store, projectId) == 0) {
                    return false;   // every check is already claimed by a story
                }
                session.setActivity("Working out the slices of work");
                String refused = planner.start(flowId);
                if (refused != null && refused.startsWith("error:")) {
                    AutonomousMode.halt("It could not start planning the work. "
                        + refused.substring("error:".length()).strip());
                }
                return true;
            }
            case RUNNING -> {
                session.setActivity(flow.stepLabel() == null ? "Planning" : flow.stepLabel());
                return true;
            }
            case AWAITING_ANSWERS -> {
                session.setActivity("Answering the questions about how to slice the work");
                AutonomousAnswers.Outcome outcome =
                    AutonomousAnswers.answerAll(context, session, flow, true);
                log.info("Autonomous: answered {} planning question(s), {} of them made up, {} "
                    + "skipped", outcome.answered(), outcome.invented(), outcome.skipped());
                String refused = planner.submitAnswers(flowId);
                if (refused != null && refused.startsWith("error:")) {
                    AutonomousMode.halt("It answered the planning questions but could not carry "
                        + "on. " + refused.substring("error:".length()).strip());
                }
                return true;
            }
            case REVIEW -> {
                session.setActivity("Accepting the slices of work it planned");
                int proposals = store.listFlowProposals(flow.id()).size();
                planner.setAllAccepted(flowId, true);
                AutonomousMode.write(store, session, AutonomousDecisionKind.ACCEPTED_STORIES,
                    "How the work is sliced", null,
                    "Accepted " + proposals + (proposals == 1 ? " story" : " stories")
                        + " into the backlog",
                    "Nobody read these before they were accepted. Each of them claims checks off "
                        + "the requirements above, and each will be built and proved separately.",
                    true, flow.id(), null);
                String refused = planner.apply(flowId);
                if (refused != null && refused.startsWith("error:")) {
                    AutonomousMode.halt("It could not write the stories it planned. "
                        + refused.substring("error:".length()).strip());
                }
                return true;
            }
            case FAILED -> {
                AutonomousMode.halt("Planning the work failed, and nothing is retried on its own. "
                    + (flow.error() == null ? "" : flow.error()));
                return true;
            }
            default -> {
                return false;   // APPLIED
            }
        }
    }

    // --- 6: marking the stories ready ---------------------------------------------------------

    /**
     * Turns the planner's suggestions into work the queue may start.
     *
     * <p>Written here rather than through {@code BacklogService.promoteStory} for one reason: that
     * method stamps the journal with {@code human}, and this is not one. The refusals it makes are
     * the same - a delivery story that names nothing it would deliver is not made ready, because
     * there would be no way to tell when it was done.
     */
    private static boolean storyStep(ConsoleContext context, AutonomousMode.Session session,
                                     ArtifactStore store, UUID projectId) {
        List<Story> drafts = new ArrayList<>();
        for (Story story : store.listStories(projectId)) {
            if (story.state() == StoryState.DRAFT) {
                drafts.add(story);
            }
        }
        if (drafts.isEmpty()) {
            return false;
        }
        session.setActivity("Marking the work ready to build");
        int ready = 0;
        List<String> refused = new ArrayList<>();
        for (Story story : drafts) {
            if (story.kind() == StoryKind.DELIVERY && story.criterionIds().isEmpty()) {
                refused.add(story.key() + " names nothing it would deliver");
                continue;
            }
            story.setState(StoryState.READY);
            store.saveStory(story);
            store.recordChange(projectId, ACTOR, ChangeEntityType.STORY, story.id(),
                ChangeKind.PROMOTED, "accepted " + story.key() + " as real work, unattended");
            AutonomousMode.write(store, session, AutonomousDecisionKind.PROMOTED_STORY,
                story.key() + " " + (story.title() == null ? "" : story.title()), null,
                "Marked ready to build",
                "A person would have read this and decided it was worth building. It was not read.",
                true, null, null);
            ready++;
        }
        if (!refused.isEmpty()) {
            AutonomousMode.write(store, session, AutonomousDecisionKind.REFUSED,
                "Work it would not start", null,
                refused.size() + (refused.size() == 1 ? " story was" : " stories were")
                    + " left alone",
                "There would have been no way to tell when they were done. "
                    + String.join("; ", refused),
                true, null, null);
        }
        if (ready > 0) {
            BacklogPublisher.publish(store, projectId);
        }
        return true;
    }

    // --- a park gets one try again, before it becomes the question there is no answer to ----------

    /**
     * Gives a parked story exactly one automatic retry before it falls back to being left stopped
     * for a person — the same rule {@link #noteBlockedTasks} has always applied, just not on the
     * very first stop any more.
     *
     * <p><b>Why a park earns a retry a plain {@code BLOCKED_TASK} refusal still does not.</b> That
     * refusal, right below, is for a stop where the swarm genuinely could not do the work — nothing
     * a retry changes. A PARK (2026-09-03) is different: it is also raised when a workflow STAGE
     * itself did not get through cleanly, and the park that prompted this feature was exactly that —
     * a one-off bad reply from a paid model, fixed by nothing more than trying again. So while
     * autonomous mode is on, a story whose run parks is sent back to be built again automatically,
     * once. If it parks a second time, this method leaves it alone from then on and
     * {@link #noteBlockedTasks} takes over — a retry nobody read the failure for is still how a
     * night is spent producing the same error eight times, and one free retry is where that line is
     * drawn.
     *
     * <p>Goes through the exact call the card's own "Build it again" button uses
     * ({@link BacklogServiceImpl#retryStory}), never a second path to the same effect: that call
     * sends the story back to READY, and {@link UnattendedPilot#startWhatIsReady} starts it fresh on
     * a later tick, exactly like any other ready story.
     *
     * <p>Recorded with {@code questionId} set to the park's own decision id, so
     * {@link #noteBlockedTasks} — called right after this, in the same tick — recognises the
     * question as already handled instead of writing a contradictory "left for you" entry for one
     * that was, in fact, just acted on.
     */
    private static void retryParkedStories(ConsoleContext context, AutonomousMode.Session session,
                                           ArtifactStore store, UUID projectId) {
        List<String> alreadyRetried = new ArrayList<>();
        for (com.swarmcoder.domain.AutonomousDecision recorded
                : store.listAutonomousDecisions(projectId)) {
            if (recorded.kind() == AutonomousDecisionKind.RETRIED_PARKED_STORY) {
                alreadyRetried.add(recorded.subject());
            }
        }
        BacklogServiceImpl service = new BacklogServiceImpl();
        for (Story story : store.listStories(projectId)) {
            if (story.state() != StoryState.RUNNING) {
                continue;
            }
            List<UUID> runIds = story.runIds();
            UUID runId = runIds.isEmpty() ? null : runIds.get(runIds.size() - 1);
            Run run = runId == null ? null : store.root().runs.get(runId);
            if (run == null || run.parkedAt() == null) {
                continue;    // not parked - nothing for this method to do
            }
            String subject = story.key() + " " + (story.title() == null ? "" : story.title());
            if (alreadyRetried.contains(subject)) {
                continue;    // already had its one retry; a second park is left for a person
            }
            Decision decision = pendingParkDecision(store, run.id());
            String reason = run.parkReason() == null || run.parkReason().isBlank()
                ? "it stopped and asked a question" : run.parkReason();
            String result = service.retryStory(story.id().toString(),
                "Retried automatically after it stopped: " + reason);
            if (result != null && result.startsWith("error:")) {
                log.warn("Autonomous: could not retry {} after it parked: {}", story.key(), result);
                continue;
            }
            AutonomousMode.write(store, session, AutonomousDecisionKind.RETRIED_PARKED_STORY,
                subject, reason, "Built it again after it stopped: " + reason,
                "It had not been retried before. A second park on the same story is left for "
                    + "you, exactly as any other stop has always been.",
                true, null, decision == null ? null : decision.id());
            log.info("Autonomous: retried {} after it parked ({})", story.key(), reason);
        }
    }

    /** The still-pending BLOCKED_TASK decision this run's park raised, when there is one. */
    private static Decision pendingParkDecision(ArtifactStore store, UUID runId) {
        for (Decision decision : store.root().decisions.values()) {
            if (decision.state() == DecisionState.PENDING
                    && decision.kind() == DecisionKind.BLOCKED_TASK
                    && runId.equals(decision.runId())) {
                return decision;
            }
        }
        return null;
    }

    // --- the question there is no answer to -----------------------------------------------------

    /**
     * Notices a build that stopped to ask something, and deliberately does not answer it.
     *
     * <p><b>Why nothing is answered here.</b> Every other question in this file exists because the
     * operator's document was silent, and an answer - even an invented one - unblocks it. A
     * {@code BLOCKED_TASK} is not that. It means the swarm tried the work and could not do it: the
     * code would not compile, the endpoint was unreachable, the change was beyond it. No sentence
     * written by any model changes that fact, and a made-up answer would only produce a second
     * identical failure with a fabricated justification attached to it. A PARK gets one automatic
     * retry from {@link #retryParkedStories}, above, before it ever reaches here; what reaches this
     * method is either a plain {@code BLOCKED_TASK} the swarm genuinely could not get past, or a
     * park that has already used its one retry and parked again.
     *
     * <p>So it is recorded and skipped. The story stays stopped with its reason on it, everything
     * that does not depend on it carries on, and the morning shows one line saying which builds
     * asked something and were left. That is the whole of the honest answer.
     */
    private static void noteBlockedTasks(ConsoleContext context, AutonomousMode.Session session,
                                         ArtifactStore store, UUID projectId) {
        List<UUID> already = new ArrayList<>();
        for (com.swarmcoder.domain.AutonomousDecision recorded
                : store.listAutonomousDecisions(projectId)) {
            if ((recorded.kind() == AutonomousDecisionKind.REFUSED
                    || recorded.kind() == AutonomousDecisionKind.RETRIED_PARKED_STORY)
                    && recorded.questionId() != null) {
                already.add(recorded.questionId());
            }
        }
        for (Decision decision : store.root().decisions.values()) {
            if (decision.state() != DecisionState.PENDING
                    || decision.kind() != DecisionKind.BLOCKED_TASK
                    || already.contains(decision.id())) {
                continue;
            }
            if (!belongsToProject(store, projectId, decision)) {
                continue;
            }
            AutonomousMode.write(store, session, AutonomousDecisionKind.REFUSED,
                "A build stopped and asked something", brief(decision),
                "Left for you - it was not answered",
                "This is not a question about what you wanted. It means the work could not be "
                    + "done, and no answer from a model changes that. Nothing was retried, because "
                    + "a retry nobody read the failure for produces the same error again. "
                    + "Everything that does not depend on it carried on.",
                true, null, decision.id());
        }
    }

    /** True when this project holds nothing the front half could ever act on. */
    private static boolean nothingHereAtAll(ArtifactStore store, UUID projectId) {
        if (!GuidedFlows.ensureIntake(store, projectId).documents().isEmpty()) {
            return false;
        }
        Brd brd = store.getBrd(projectId);
        if (brd != null && brd.requirements() != null && !brd.requirements().isEmpty()) {
            return false;
        }
        return store.listStories(projectId).isEmpty();
    }

    private static boolean belongsToProject(ArtifactStore store, UUID projectId, Decision decision) {
        if (decision.runId() == null) {
            return false;
        }
        com.swarmcoder.domain.Run run = store.root().runs.get(decision.runId());
        return run != null && projectId.equals(run.projectId());
    }

    private static String brief(Decision decision) {
        String text = decision.briefMarkdown();
        if (text == null || text.isBlank()) {
            return "(no details were recorded)";
        }
        return text.length() > 300 ? text.substring(0, 300).strip() + "..." : text.strip();
    }

    // --- knowing when it is over ------------------------------------------------------------

    /**
     * Stops the session when there is genuinely nothing left it could do.
     *
     * <p>Which is also the moment it stops costing anything. A queue that goes quiet and a session
     * that is still switched on look identical on the board, and the difference matters in the
     * morning: one of them is going to start something when a build finishes, and the other is not.
     */
    private static void finishIfNothingLeft(ConsoleContext context, AutonomousMode.Session session,
                                            ArtifactStore store, UUID projectId) {
        int building = 0;
        int waiting = 0;
        int delivered = 0;
        int stopped = 0;
        int forYou = 0;
        for (Story story : store.listStories(projectId)) {
            switch (story.state()) {
                case RUNNING -> building++;
                case READY -> waiting++;
                case DONE -> delivered++;
                case BLOCKED -> stopped++;
                case REVIEW -> forYou++;
                default -> { }
            }
        }
        if (building > 0 || waiting > 0) {
            session.setActivity(building + " building, " + waiting + " waiting to start");
            return;
        }
        if (delivered == 0 && stopped == 0 && forYou == 0) {
            if (nothingHereAtAll(store, projectId)) {
                // The button was pressed on an empty project. Said plainly and at once, rather
                // than left switched on all night watching a backlog that will never fill.
                AutonomousMode.halt("There is no document to read and nothing written down to "
                    + "build. Add what you have written down first, then start it again.");
            }
            return;     // otherwise the front half is still working
        }
        // Checks nothing claimed are named in the same sentence. Planning runs once, and a plan
        // that covered nine requirements out of ten leaves a project that looks finished and is
        // not - which is a thing to be told at the end, not to discover a week later.
        int unclaimed = 0;
        try {
            unclaimed = BacklogAuthoring.unclaimedCriteria(store, projectId);
        } catch (Exception e) {
            log.warn("Could not count what no story claims: {}", e.toString());
        }
        AutonomousMode.halt("Everything it could do is done. " + delivered + " delivered, "
            + stopped + " stopped without delivering, " + forYou + " came back for your verdict "
            + "and could not be accepted on its own."
            + (unclaimed == 0 ? ""
                : " " + unclaimed + (unclaimed == 1 ? " check on an agreed requirement is"
                    : " checks on agreed requirements are") + " still claimed by no story, so "
                    + (unclaimed == 1 ? "it was" : "they were") + " not built."));
    }
}
