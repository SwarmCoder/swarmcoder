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

import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.NextAction;
import com.swarmcoder.console.api.ReadinessSignals;
import com.swarmcoder.console.api.StageGuidance;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.BuildHealth;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunPause;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Works out what the Console can currently do and publishes it onto
 * {@link ReadinessSignals#CURRENT}.
 *
 * <p>Judged here rather than in the browser because none of it is visible from there: whether a
 * folder is a git repository, whether the sandbox answered, what the config actually says.
 *
 * <p>The message fields matter as much as the booleans. Hiding a surface without saying why turns
 * "this cannot work yet" into "this is broken", so every negative answer carries the reason and the
 * concrete next step, and the shell renders those verbatim.
 *
 * <p>Setup readiness answers "can this work"; the next action answers "what should I do", which is
 * the question that survives setup.
 *
 * <h2>One global step, and one per stage</h2>
 *
 * <p>{@link #globalAction} keeps the original judgement: the stages are ordered by dependency — an
 * empty BRD cannot be promoted, unpromoted requirements are not scope to plan against, an empty
 * backlog has nothing to run — so the first unmet stage is the next step, and there is never more
 * than one. That answer is what decides which stage the shell LANDS on, and it is deliberately
 * unchanged.
 *
 * <p>It is not, however, an answer to "what do I do <em>here</em>", and treating it as one was the
 * defect. First match wins, so any draft requirement outranked everything downstream: a project with
 * nineteen requirements, four still drafts, six proposed stories and a run in flight was told,
 * permanently, to go back to Requirements — while the operator stood in Plan with nothing on the
 * screen telling them what to do there. Requirements are refined while earlier ones are already
 * being built; one global "you are here" cannot describe concurrent work, and pointing backwards is
 * worse than silence because it reads as "you cannot proceed".
 *
 * <p>So {@link #guidance} computes one {@link StageGuidance} per stage from that stage's own facts,
 * and each is independent of the others. Plan says what Plan is waiting on even when Requirements
 * still has drafts.
 */
final class ReadinessPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReadinessPublisher.class);

    private ReadinessPublisher() {}

    /** Recomputes readiness from the current project and environment. */
    static ConsoleReadiness build(ConsoleContext context) {
        if (context == null) {
            return ConsoleReadiness.empty();
        }
        UUID projectId = context.currentProjectId();
        Project project = null;
        for (Project candidate : context.listProjects()) {
            if (candidate.id() != null && candidate.id().equals(projectId)) {
                project = candidate;
                break;
            }
        }
        if (project == null) {
            // No project: nothing in the Console is scoped to anything.
            return ConsoleReadiness.empty();
        }

        String repoPath = project.primaryPath();
        boolean hasRepository = repoPath != null && !repoPath.isBlank() && isGitRepo(repoPath);

        if (!hasRepository) {
            String blocker = repoPath == null || repoPath.isBlank()
                ? "'" + project.name() + "' has no folder configured"
                : "'" + project.name() + "' points at " + repoPath + ", which is not a git repository";
            // Authoring still works — the BRD, backlog and knowledge live in the store. Only
            // execution is impossible, because a worker branches from HEAD and there is no HEAD.
            // So the workflow step is computed here too: a missing repository is a reason not to
            // offer a RUN, not a reason to stop telling the operator how to shape the requirements.
            return withNextAction(new ConsoleReadiness(true, false, false, blocker,
                "Set this project's folder to a git repository in project settings, "
                    + "then builds become available. Requirements and the pipeline work already.",
                project.name(), repoPath), context, project);
        }

        String sandboxBlocker = context.sandboxBlocker();
        if (sandboxBlocker != null && !sandboxBlocker.isBlank()) {
            // Offering to start a run that will fail at dispatch is a promise the system cannot
            // keep, so this counts as "cannot run" even though everything else is in place.
            return withNextAction(new ConsoleReadiness(true, true, false, sandboxBlocker,
                "Start Docker and build the worker image, or set sandbox.enabled=false to accept "
                    + "that worker commands run on this machine with your privileges.",
                project.name(), repoPath), context, project);
        }
        return withNextAction(
            new ConsoleReadiness(true, true, true, null, null, project.name(), repoPath),
            context, project);
    }

    /**
     * Attaches the workflow step to an otherwise-complete readiness value.
     *
     * <p>Every read in here is guarded and the whole thing is wrapped: readiness is published from
     * project creation, project switching and context installation, and a store that answers badly
     * must degrade to "nothing to suggest" rather than take those paths down with it. A missing next
     * step costs one line of guidance; a thrown one costs the operator their project switch.
     */
    private static ConsoleReadiness withNextAction(ConsoleReadiness readiness,
                                                   ConsoleContext context, Project project) {
        try {
            ArtifactStore store = context.store();
            UUID projectId = project.id();
            if (store == null || projectId == null) {
                return readiness;
            }
            Facts facts = Facts.read(store, projectId, readiness.canRun());
            globalAction(readiness, facts);
            guidance(readiness, facts);
            landOnTheWork(readiness);
        } catch (Exception e) {
            log.warn("Could not work out the next step for project {}: {}",
                project.name(), e.toString());
        }
        return readiness;
    }

    // --- what the project actually looks like ----------------------------------------------------

    /**
     * Everything the guidance is judged from, counted once.
     *
     * <p>One read of the BRD, the backlog, the iterations and the run history per publish. Every
     * stage's answer is derived from the same snapshot, so two stages can never describe two
     * different moments — which on a board that redraws from a signal is a real failure mode, not a
     * theoretical one.
     */
    private record Facts(
        int requirements, int drafts, int active, int implemented,
        int proposed, int unscheduled, int scheduled, int verdict, int running,
        int delivered, int iterations, int decisions, int atGate, boolean hasRuns, boolean canRun) {

        static Facts read(ArtifactStore store, UUID projectId, boolean canRun) {
            // Read rather than ensureBrd(): computing readiness must not create rows, and "no BRD
            // yet" and "an empty BRD" are the same thing to the operator.
            Brd brd = store.getBrd(projectId);
            List<BrdRequirement> requirements =
                brd == null || brd.requirements() == null ? List.of() : brd.requirements();
            int drafts = 0;
            int active = 0;
            int implemented = 0;
            for (BrdRequirement requirement : requirements) {
                RequirementStatus status = requirement.status();
                if (status == RequirementStatus.DRAFT) {
                    drafts++;
                } else if (status == RequirementStatus.ACTIVE) {
                    active++;
                } else if (status == RequirementStatus.IMPLEMENTED) {
                    // Counted separately from ACTIVE, which every existing branch keys off: an
                    // implemented requirement is agreed AND delivered, so it belongs in the header's
                    // "agreed" total without becoming something the pipeline is asked to plan again.
                    implemented++;
                }
            }

            List<Story> stories = store.listStories(projectId);
            int proposed = 0;
            int unscheduled = 0;
            int scheduled = 0;
            int verdict = 0;
            int running = 0;
            int gate = 0;
            int delivered = 0;
            for (Story story : stories) {
                StoryState state = story.state();
                if (state == null) {
                    continue;
                }
                switch (state) {
                    case DRAFT -> proposed++;
                    // READY splits: agreeing a story is real and deciding when it happens are
                    // separate decisions taken at separate moments, and the board has a column for
                    // each. Guidance that merged them could never name the second one.
                    case READY -> {
                        if (story.iterationId() == null) {
                            unscheduled++;
                        } else {
                            scheduled++;
                        }
                    }
                    case REVIEW, BLOCKED -> verdict++;
                    case RUNNING -> {
                        // Not everything that says RUNNING is progressing, and the split is judged by
                        // BuildHealth — the SAME derivation the pipeline board's badges use. Counting
                        // every RUNNING story as "in flight" is how the guidance line came to say
                        // "nothing is waiting on you" directly above a card reading "stopped".
                        if (needsAPerson(store, story)) {
                            gate++;
                        } else {
                            running++;
                        }
                    }
                    case DONE -> delivered++;
                    default -> { }
                }
            }
            return new Facts(requirements.size(), drafts, active, implemented,
                proposed, unscheduled, scheduled, verdict, running, delivered,
                store.listIterations(projectId).size(),
                // Counted here for the first time. Its absence was the whole of the defect: Build
                // could see runs and stories and had no idea anybody was owed an answer, so
                // thirty-seven of them produced a chip with no badge. Via PendingDecisions, which
                // is also what the status strip's "N to approve" reads, so the two cannot disagree.
                PendingDecisions.forProject(store, projectId, stories), gate,
                anyRunRecorded(store, projectId, stories), canRun);
        }

        /**
         * Whether a building story is actually owed something by a person.
         *
         * <p>This used to ask whether any of its runs was holding at APPROVAL — a test that could
         * never be true even before that state was abolished, because a run only reached the gate
         * after its story had already been moved out of RUNNING. So the count it fed was always zero,
         * which is the whole of the "chip with no badge" defect.
         *
         * <p>Now it asks {@link BuildHealth}, which is the same question the board's badge asks and
         * therefore cannot come back with a different answer. Two things leave a building story owing
         * the operator: a pause that has outlasted the point where "it resumes by itself" is the whole
         * story, and a build that nothing is driving at all.
         */
        private static boolean needsAPerson(ArtifactStore store, Story story) {
            long now = System.currentTimeMillis();
            BuildHealth.Kind worst = null;
            for (UUID runId : story.runIds()) {
                Run run = store.root().runs.get(runId);
                if (run == null) {
                    continue;
                }
                BuildHealth.Kind kind = BuildHealth.of(
                    run.state() == null ? null : run.state().name(),
                    millis(run.heartbeatAt()), millis(run.pausedSince()), millis(run.parkedAt()),
                    now);
                // The story is as healthy as its healthiest run: one live attempt is enough for it to
                // be progressing, whatever earlier ones did.
                if (worst == null || !BuildHealth.needsAPerson(kind)) {
                    worst = kind;
                }
                if (!BuildHealth.needsAPerson(kind)) {
                    return false;
                }
            }
            // No run on record at all is the stranded case: it says it is building and nothing is.
            return worst == null || BuildHealth.needsAPerson(worst);
        }

        private static long millis(Instant instant) {
            return instant == null ? 0 : instant.toEpochMilli();
        }

        /** Anything in the backlog that is not a tombstone — the scope Plan has to work with. */
        boolean anyWork() {
            return proposed + unscheduled + scheduled + verdict + running + delivered > 0;
        }

        /** Everything on the planning board: every card is a decision the operator owes. */
        int planAttention() {
            return proposed + unscheduled + scheduled + verdict;
        }
    }

    /**
     * Whether this project has ever run — by the run's own project id, or via a story's runs.
     *
     * <p>Named apart from {@code Facts.hasRuns()}: a record's component accessor shadows any static
     * method of the same name inside the record body, so the two spellings cannot both be
     * {@code hasRuns} however obvious that name is for each of them.
     */
    private static boolean anyRunRecorded(ArtifactStore store, UUID projectId,
                                          List<Story> stories) {
        for (Run run : store.root().runs.values()) {
            if (projectId.equals(run.projectId())) {
                return true;
            }
        }
        // Runs persisted before Run carried a projectId load with a null one; a run recorded on this
        // project's story is still this project's run, and counting it prevents the bar from telling
        // a long-running project to start its first run.
        for (Story story : stories) {
            if (!story.runIds().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // --- the global recommendation ---------------------------------------------------------------

    /**
     * The workflow stages, in dependency order — first match wins, and it is the only one shown.
     *
     * <ol>
     *   <li>the BRD holds no requirements → {@link NextAction#ANALYSE_DOCUMENTS}</li>
     *   <li>any requirement is still DRAFT → {@link NextAction#PROMOTE_DRAFTS}</li>
     *   <li>ACTIVE requirements exist but nothing in the backlog can be worked →
     *       {@link NextAction#PLAN_STORIES}</li>
     *   <li>a story is READY and the project has never run → {@link NextAction#START_RUN}</li>
     *   <li>otherwise nothing</li>
     * </ol>
     *
     * <p>Stage 4 is additionally gated on {@code canRun}: pointing at a run that would be refused at
     * dispatch is the same broken promise the setup fields exist to avoid, and the operator is
     * already being told about the repository or the sandbox by {@code SetupView}.
     *
     * <p><b>This is the LANDING recommendation, and only that.</b> It answers "where should the
     * shell open", which is a question with exactly one answer, and for that the dependency order is
     * right: with four drafts outstanding, Requirements is a defensible place to start. It is not an
     * answer to "what do I do here" — {@link #guidance} is — and every bar mounted in a stage now
     * reads that instead.
     */
    private static void globalAction(ConsoleReadiness readiness, Facts facts) {
        if (facts.requirements() == 0) {
            set(readiness, NextAction.ANALYSE_DOCUMENTS,
                "Analyse the documents you have — the BRD is empty, and nothing downstream can be "
                    + "planned or built until this project's requirements exist.");
            return;
        }
        // Any draft at all, not only an all-draft BRD: a promoted set with three stragglers still
        // has three requirements nothing may be designed against.
        if (facts.drafts() > 0) {
            set(readiness, NextAction.PROMOTE_DRAFTS, draftSentence(facts.drafts()));
            return;
        }
        // A DRAFT story is a proposal, a CANCELLED one is a tombstone — neither can be scheduled, so
        // a backlog of only those is still a backlog with nothing in it.
        boolean workable = facts.unscheduled() + facts.scheduled() + facts.verdict()
            + facts.running() + facts.delivered() > 0;
        if (facts.active() > 0 && !workable) {
            set(readiness, NextAction.PLAN_STORIES, facts.proposed() > 0
                ? (facts.proposed() == 1 ? "1 suggested story is" : facts.proposed()
                    + " suggested stories are")
                    + " waiting in \"Suggested\". Accepting one moves it to \"Ready to build\"."
                : "The agreed requirements have no stories yet. \"Plan stories\" proposes them from "
                    + "what you have agreed, so the scope becomes work that can be picked up.");
            return;
        }
        if (facts.canRun() && facts.unscheduled() + facts.scheduled() > 0 && !facts.hasRuns()) {
            set(readiness, NextAction.START_RUN,
                "A story is agreed and nothing has been built yet. \"Build this story\" on its card "
                    + "is what turns it into a commit you can judge.");
        }
    }

    /**
     * Moves the landing recommendation to the FURTHEST stage that has outstanding work, overriding
     * the dependency order {@link #globalAction} computed.
     *
     * <p>Dependency order answers "what must be true before the next thing can happen", and as a
     * landing rule it sends the operator backwards for ever: any draft requirement outranks
     * everything downstream, so a project with four stragglers, six proposed stories and a run in
     * flight opens on Requirements every single time. The operator has moved on; the shell had not.
     *
     * <p>Furthest-with-work is the honest answer to "where has this project got to". Work is
     * concurrent here — requirements get refined while earlier ones are already being built — so
     * the last stage that still needs a decision is where the operator was, and where they want to
     * be put back. The stages they have moved past keep their attention badges, so nothing outstanding
     * becomes invisible; it simply stops being the thing the shell insists on.
     *
     * <p>With no attention anywhere, {@code globalAction}'s answer stands: it is the right one for a
     * project that has not started, and for a finished one it lands on Build.
     */
    private static void landOnTheWork(ConsoleReadiness readiness) {
        // Furthest first. Build now genuinely can win this: a run in flight still carries no
        // attention (watching one is not a decision the operator owes), but an unanswered decision
        // does, so a project with approvals outstanding lands on Build — which is where they are
        // answered. That is the intended consequence of buildGuidance counting them, not a
        // side-effect: the landing rule was already "the furthest stage with work", and Build had
        // simply never been able to report any.
        for (String stage : new String[] {
                StageGuidance.BUILD, StageGuidance.PLAN, StageGuidance.REQUIREMENTS }) {
            StageGuidance guidance = readiness.guidance(stage);
            if (guidance != null && guidance.attention() > 0
                    && guidance.action() != NextAction.NONE) {
                set(readiness, guidance.action(), guidance.text());
                return;
            }
        }
    }

    // --- what each stage says for itself ---------------------------------------------------------

    /**
     * One answer per stage, each computed from that stage's own facts and from nothing else.
     *
     * <p>The independence is the point. Requirements may have four drafts while Plan has six
     * proposals and Build has a run in flight; all three are true at once, all three are worth
     * saying, and the operator standing in any one of them is entitled to the one that is about
     * where they are standing.
     *
     * <p>Setup is not here: it already has {@code runBlocker} and {@code nextStep}, which say the
     * same thing in more detail, and a second phrasing of a blocker is how two surfaces come to
     * disagree about it.
     */
    private static void guidance(ConsoleReadiness readiness, Facts facts) {
        // The header's counts, from the same read of the store the sentences below are judged from —
        // see ConsoleReadiness.requirementsAgreed for what happened when the client counted its own.
        readiness.setRequirementsAgreed(facts.active() + facts.implemented());
        readiness.setRequirementsDrafts(facts.drafts());
        readiness.putGuidance(requirementsGuidance(facts));
        readiness.putGuidance(planGuidance(facts));
        readiness.putGuidance(buildGuidance(facts));
    }

    /**
     * Requirements: capture, then agree. Its attention count is the drafts — the requirements
     * nothing may yet be designed against.
     */
    private static StageGuidance requirementsGuidance(Facts facts) {
        String stage = StageGuidance.REQUIREMENTS;
        if (facts.requirements() == 0) {
            return StageGuidance.of(stage, NextAction.ANALYSE_DOCUMENTS,
                "The BRD is empty. Analysing the documents you already have is what turns them into "
                    + "requirements — nothing downstream can be planned or built until they exist.",
                0);
        }
        if (facts.drafts() > 0) {
            return StageGuidance.of(stage, NextAction.PROMOTE_DRAFTS,
                draftSentence(facts.drafts()), facts.drafts());
        }
        // Agreed, and nothing outstanding. Deliberately silent rather than congratulatory: a bar
        // that says "all good" on every visit is one the operator stops reading, and this stage is
        // still where scope is changed whenever it moves.
        return StageGuidance.settled(stage);
    }

    /**
     * Plan: accept the suggestions that are real work, then build them. Two steps, because there are
     * only two.
     *
     * <p>It used to be three — promote, schedule, start — and the middle one was an invention. See
     * the comment on the "ready to build" branch below: {@code BacklogServiceImpl.startSession}
     * requires {@link StoryState#READY} and never reads {@code iterationId}, so telling the operator
     * that "a story with no iteration is never picked up" was guidance towards a gate that does not
     * exist, on the one bar they read before touching the board.
     *
     * <p>Ordered by what is most blocking rather than by the order a story travels: a run that has
     * come back is holding up everything behind it, so a verdict outranks a suggestion even though
     * suggestions come first in a story's life. Everything on the board counts towards attention,
     * because every column of it is a decision the operator owes.
     */
    private static StageGuidance planGuidance(Facts facts) {
        String stage = StageGuidance.PLAN;
        int attention = facts.planAttention();

        if (facts.verdict() > 0) {
            return StageGuidance.of(stage, NextAction.REVIEW_RESULTS,
                (facts.verdict() == 1
                    ? "1 story is in \"Came back\" and needs your judgement"
                    : facts.verdict() + " stories are in \"Came back\" and need your judgement")
                    + ". Accept what delivered, or send back what did not — nothing behind them moves "
                    + "until you do.", attention);
        }
        if (facts.proposed() > 0) {
            return StageGuidance.of(stage, NextAction.PROMOTE_STORIES,
                (facts.proposed() == 1
                    ? "1 suggested story is waiting — accept it if it is real work"
                    : facts.proposed() + " suggested stories are waiting — accept the real ones")
                    + ", and drop the rest. Accepting one is what makes it buildable.",
                attention);
        }
        // Agreed work, WHETHER OR NOT it is in an iteration — because that is the only thing
        // startSession asks about. This used to be three branches: create an iteration, then put
        // the story in one, and only then start it. Two of those steps do not exist. A story with a
        // null iterationId is dispatched exactly like one inside a batch, so the bar above the Plan
        // board spent its life naming a prerequisite the server has never had, and the board's own
        // copy agreed with it. An iteration is grouping; it is offered on the card and it is never
        // the next step. (NextAction.CREATE_ITERATION and SCHEDULE_STORIES stay in the enum — it
        // travels the wire by name and is append-only — but nothing produces them any more.)
        int ready = facts.unscheduled() + facts.scheduled();
        if (ready > 0) {
            // canRun is checked because offering a run that would be refused at dispatch is the
            // broken promise the setup fields exist to avoid.
            if (!facts.canRun()) {
                return StageGuidance.waitingFor(stage, StageGuidance.SETUP,
                    (ready == 1 ? "1 story is" : ready + " stories are")
                        + " agreed and ready to build, but nothing can run until setup is "
                        + "finished — see the health dot in the header.");
            }
            return StageGuidance.of(stage, NextAction.START_RUN,
                (ready == 1 ? "1 story is" : ready + " stories are")
                    + " agreed and ready to build. \"Build this story\" on a card moves it into "
                    + "\"Building\", where you can watch it; putting it in an iteration is optional "
                    + "grouping, not a step on the way.", attention);
        }
        if (facts.active() > 0 && !facts.anyWork()) {
            return StageGuidance.of(stage, NextAction.PLAN_STORIES,
                "The agreed requirements have no stories yet. \"Plan stories\" proposes them from "
                    + "what you have agreed, so the scope becomes work that can be picked up.", 0);
        }
        if (facts.active() == 0) {
            // The one honest backwards pointer in the whole model — and it is phrased as what THIS
            // stage is waiting for, not as an instruction to go away. Plan genuinely cannot plan
            // against scope nobody has agreed to.
            return StageGuidance.waitingFor(stage, StageGuidance.REQUIREMENTS,
                facts.drafts() > 0
                    ? "Nothing to plan against yet: planning works from agreed requirements, and "
                        + "all " + facts.drafts() + " of this project's requirements are still "
                        + "drafts."
                    : "Nothing to plan against yet: planning works from agreed requirements, and "
                        + "this project has none.");
        }
        if (facts.atGate() > 0) {
            // A building story that nothing is driving, or one whose pause has outlasted the point
            // where it resumes by itself. This branch exists because its absence was the visible
            // contradiction: the line said "nothing is waiting on you" over a card saying "stopped".
            return StageGuidance.of(stage, NextAction.REVIEW_RESULTS,
                (facts.atGate() == 1
                    ? "1 story says it is building but nothing is driving it"
                    : facts.atGate() + " stories say they are building but nothing is driving them")
                    + ". Its card in \"Building\" says why, and carries the one button that moves it.",
                attention);
        }
        if (facts.running() > 0) {
            // Genuinely in flight — including waiting out an endpoint outage, which resumes by itself.
            // Nothing is owed, so this is a statement about where you are standing, not an instruction.
            return StageGuidance.waitingFor(stage, StageGuidance.PLAN,
                (facts.running() == 1 ? "1 story is" : facts.running() + " stories are")
                    + " being built and will move to \"Came back\" by themselves. Nothing is waiting "
                    + "on you.");
        }
        return StageGuidance.settled(stage);
    }

    /**
     * Build: where the work runs, and where unanswered decisions pile up.
     *
     * <h3>Its attention is the pending decisions, and nothing else</h3>
     *
     * <p>This used to return attention {@code 0} unconditionally, on the reasoning that watching a
     * run is not a decision the operator owes. That reasoning is right about WATCHING and was
     * applied to the wrong set: an unanswered decision is a decision they owe by definition, and
     * {@link Facts} never even read them. The result on a real screen was thirty-seven of them, the
     * status strip saying "37 to approve" at the very bottom of the window, and Build's chip
     * carrying no badge and its bar saying nothing about any of it.
     *
     * <p>Deliberately NOT counted into this number:
     * <ul>
     *   <li><b>Runs in flight.</b> Watching one is not a decision. The original judgement stands.</li>
     *   <li><b>Stories in REVIEW or BLOCKED.</b> They are already {@link Facts#planAttention()},
     *       and judgement is given on the pipeline board. Adding them here would badge one decision
     *       twice and make the two chips sum to more work than exists.</li>
     * </ul>
     *
     * <h3>What the sentence may claim</h3>
     *
     * <p>Nothing in the engine blocks on a decision — see {@link PendingDecisions} for the trace —
     * so "runs are waiting on you" is not available, however much it would suit a status bar. What
     * is true is that every one of them was minted where work stopped, and that answering records
     * the operator's call without restarting anything. That is what it says.
     *
     * <p><b>Said even when runs are impossible.</b> The {@code canRun} gate below is about a run
     * that cannot START; decisions already raised are owed regardless, and going silent about them
     * because the repository moved is the same disappearing act the operator has complained about
     * twice. {@code BuildStage} still renders {@code runBlocker} in its own strip, and this does not
     * repeat it, so the two surfaces cannot disagree about the blocker.
     */
    private static StageGuidance buildGuidance(Facts facts) {
        String stage = StageGuidance.BUILD;
        if (facts.decisions() > 0) {
            return StageGuidance.of(stage, NextAction.ANSWER_DECISIONS,
                decisionSentence(facts.decisions(), facts.running()), facts.decisions());
        }
        if (!facts.canRun()) {
            return StageGuidance.settled(stage);
        }
        // There used to be a branch here for stories "holding for your approval" — the abolished
        // APPROVAL park (UX v3 §2.3). It is gone twice over: no run reaches that state any more, and
        // a building story that DOES need a person needs them on its card, in the pipeline's
        // "Building" column, which is where planGuidance now says so. Claiming it here would badge a
        // surface the operator cannot act on it from.
        if (facts.running() > 0) {
            // running() already excludes the ones nothing is driving — see Facts.read.
            return StageGuidance.of(stage, NextAction.NONE,
                (facts.running() == 1 ? "1 story is" : facts.running() + " stories are")
                    + " being built. Nothing here is waiting on you; each moves to \"Came back\" by "
                    + "itself when it is done.", 0);
        }
        if (facts.verdict() > 0) {
            return StageGuidance.waitingFor(stage, StageGuidance.PLAN,
                (facts.verdict() == 1 ? "1 story has" : facts.verdict() + " stories have")
                    + " come back and are waiting for your judgement on the pipeline. Nothing "
                    + "new will start "
                    + "until they are decided.");
        }
        // Counts BOTH ready columns, for the same reason planGuidance does: an iteration has never
        // been a condition of dispatch, so a story without one is just as ready as a story with one
        // and this would otherwise claim there is nothing to build while the pipeline is full.
        int readyToBuild = facts.unscheduled() + facts.scheduled();
        if (readyToBuild > 0) {
            return StageGuidance.waitingFor(stage, StageGuidance.PLAN,
                (readyToBuild == 1 ? "1 story is" : readyToBuild + " stories are")
                    + " agreed and ready to build. \"Build this story\" on its card is what "
                    + "starts a run.");
        }
        if (!facts.anyWork()) {
            return StageGuidance.waitingFor(stage, StageGuidance.PLAN,
                "Nothing to build yet. Runs are started from stories, and this project has none in "
                    + "the plan.");
        }
        return StageGuidance.settled(stage);
    }

    /**
     * Build's lead sentence when decisions are owed: the number first, then the consequence — and
     * the consequence is the one that is actually true.
     *
     * <p>Every wording that reads better than this one is false. "N runs are waiting on you" is
     * wrong because nothing waits: no thread parks on a decision and resolving one restarts
     * nothing. "N decisions need a look" is wrong the other way — it makes an abandoned task and a
     * dead run sound like an inbox. What is verifiably true is that each was raised at the moment
     * work stopped, and that the operator's answer is a record rather than a resume button; both
     * halves are in here because the second is what stops them expecting the run to pick up again.
     *
     * <p>Runs in flight are appended rather than leading, when there are any: they are the less
     * urgent fact and they carry no attention, but omitting them entirely would leave a project
     * with nine runs and thirty-seven decisions apparently doing nothing.
     */
    /**
     * One line. It used to be a paragraph — where the question came from, that nothing in the engine
     * blocks on it, that answering restarts nothing, and how many runs were also in flight — which was
     * affordable when guidance had a strip to itself inside a stage. There is one line in the header
     * now (UX v3 §3), sharing the row with the counts, and it has to name the step and stop. The detail
     * lives on the card the sentence points at, which is where it can be read next to the evidence.
     */
    private static String decisionSentence(int owed, int running) {
        return owed == 1
            ? "1 build stopped to ask you something — it is on that story's card, with the button "
                + "that answers it."
            : owed + " builds stopped to ask you something — each is on its own story's card, with "
                + "the button that answers it.";
    }

    private static String draftSentence(int drafts) {
        // "Agree", never "promote": promote is the state machine's verb and it was the single
        // most confusing word in the product ("what does promote do? I was just clicking
        // randomly") — UX v3 §1 D1 and §4.
        return drafts == 1
            ? "1 requirement is still a draft. Agreeing it makes it part of the scope and accepts "
                + "the checks on it."
            : drafts + " requirements are still drafts. Agreeing one makes it part of the scope "
                + "and accepts the checks on it.";
    }

    private static void set(ConsoleReadiness readiness, NextAction action, String text) {
        readiness.setNextAction(action);
        readiness.setNextActionText(text);
    }

    /**
     * Recomputes and publishes from the installed context.
     *
     * <p>Called from the BRD and backlog publish funnels, because the next step is derived from
     * exactly those two things plus the run history: without this, promoting the last draft would
     * leave the bar still asking for it until the operator switched projects. Swallows everything,
     * including "no context installed" — this runs inside mutations that must not fail over a hint.
     */
    static void refresh() {
        try {
            publish(ConsoleContext.get());
        } catch (Exception ignored) {
            // no context (spike/test wiring) or a store that answered badly: the bar simply keeps
            // its last value, which is never worse than failing the mutation that triggered this
        }
    }

    /** Publishes the current readiness. Best-effort: a missed publish costs a redraw, not data. */
    static void publish(ConsoleContext context) {
        try {
            ReadinessSignals.CURRENT.set(build(context));
        } catch (Exception e) {
            log.warn("Could not publish console readiness: {}", e.getMessage());
        }
    }

    /**
     * A directory git will accept. {@code .git} is a DIRECTORY in a normal clone and a FILE in a
     * worktree or submodule, so both count.
     */
    private static boolean isGitRepo(String path) {
        try {
            Path git = Paths.get(path).resolve(".git");
            return Files.isDirectory(git) || Files.isRegularFile(git);
        } catch (Exception e) {
            return false;
        }
    }
}
