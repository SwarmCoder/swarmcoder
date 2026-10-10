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

import com.swarmcoder.console.api.Backlog;
import com.swarmcoder.console.api.BacklogService;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.CriterionVerification;
import com.swarmcoder.domain.VerificationResult;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeEvent;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.Iteration;
import com.swarmcoder.domain.IterationState;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryGraph;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Serves the current project's backlog over RMI and performs the operator-only actions over it:
 * promoting a DRAFT story into the plan, scheduling it, cancelling it, and starting a run bound to
 * it. The agent modes deliberately have none of these — see {@link BacklogAuthoring}.
 *
 * <p>{@code @ApplicationScoped} so zeroz4j's CDI scan discovers and registers it as an
 * {@code @RmiService}.
 */
@ApplicationScoped
public class BacklogServiceImpl implements BacklogService {

    private static final Logger log = LoggerFactory.getLogger(BacklogServiceImpl.class);

    @Override
    public Backlog backlog() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        Backlog backlog = BacklogPublisher.build(context.store(), projectId);
        // Publish too, so a panel opened before the first mutation gets the current state via
        // signal retention rather than waiting for something to change.
        try {
            com.swarmcoder.console.api.BacklogSignals.CURRENT.set(backlog);
        } catch (Exception ignored) {
            // best-effort; the returned value already answers this call
        }
        return backlog;
    }

    @Override
    public String promoteStory(String storyId) {
        return mutate(storyId, (store, projectId, story) -> {
            if (story.state() != StoryState.DRAFT) {
                return "error: " + story.key() + " is already " + story.state().label();
            }
            if (story.kind() == StoryKind.DELIVERY && story.criterionIds().isEmpty()) {
                return "error: " + story.key() + " names nothing it would deliver, so there is "
                    + "no way to tell when it is done. Give it at least one check first.";
            }
            story.setState(StoryState.READY);
            store.saveStory(story);
            store.recordChange(projectId, "human", ChangeEntityType.STORY, story.id(),
                ChangeKind.PROMOTED, "accepted " + story.key() + " as real work");
            return "";
        });
    }

    @Override
    public String cancelStory(String storyId) {
        return mutate(storyId, (store, projectId, story) -> {
            if (story.state() == StoryState.DONE) {
                return "error: " + story.key() + " has already been delivered, and delivered "
                    + "work is not dropped";
            }
            story.setState(StoryState.CANCELLED);
            store.saveStory(story);
            // A tombstone, not a deletion: the story and everything it produced stay on the record.
            store.recordChange(projectId, "human", ChangeEntityType.STORY, story.id(),
                ChangeKind.TOMBSTONED, "dropped " + story.key());
            return "";
        });
    }

    @Override
    public String retryStory(String storyId, String note) {
        return mutate(storyId, (store, projectId, story) -> {
            // RUNNING is accepted too, and it has to be. A story is moved out of RUNNING only when
            // the workflow reaches its delivery check, so a process that dies mid-run — a restart,
            // a crash, a model endpoint that never came back — strands the story there for ever.
            // There is no heartbeat on a run: one parked at EXECUTING with a thread on it and one
            // abandoned hours ago are indistinguishable in the store, so the server cannot tell the
            // operator which they have. It can only refuse to be the reason they are stuck.
            if (story.state() != StoryState.BLOCKED && story.state() != StoryState.REVIEW
                    && story.state() != StoryState.RUNNING) {
                return "error: " + story.key() + " is " + story.state().label()
                    + " — only a story that is building, stopped, or back for a verdict can be "
                    + "sent back to be built again";
            }
            StoryState was = story.state();
            story.setState(StoryState.READY);
            // The commit references are cleared deliberately. They describe the attempt being
            // rejected, and leaving them would let the NEXT acceptance stamp criteria as verified
            // against a commit that was never accepted — evidence laundered through a retry.
            story.setDeliveredCommit(null);
            story.setIntegrationCommit(null);
            store.saveStory(story);
            // A run left behind by this retry (nothing here resumes it — see the dialog's own
            // words: "this cannot call an agent back") must not go on claiming to be parked. Left
            // alone, its "stopped" mark and reason would describe a run nobody is looking at any
            // more, on a story that has already moved back to "Ready to build".
            clearParkMarks(store, story);
            String why = note == null || note.isBlank() ? null : note.trim();
            store.recordChange(projectId, "human", ChangeEntityType.STORY, story.id(),
                ChangeKind.STATE_CHANGED, "state", String.valueOf(was), "READY",
                was == StoryState.BLOCKED
                    ? "unblocked " + story.key() + " for another run"
                        + (why == null ? "" : ": " + why)
                    : "sent " + story.key() + " back to be built again"
                        + (why == null ? "" : ": " + why),
                lastRunOf(story));
            return "";
        });
    }

    /**
     * Clears the "parked" mark on any of this story's runs that still carries one, once the story
     * itself has moved back to READY.
     *
     * <p>Field only — deliberately does NOT touch the {@code BLOCKED_TASK} decision the park raised.
     * That decision is real history about the attempt being abandoned, and {@code
     * ConsoleStoppedStoryQuestionsBrowserTest} pins the existing behaviour: a story's outstanding
     * questions survive a retry, unanswered, and stay reachable on its card in the new column. Only
     * the run's own "I am parked, and here is why" fact is stale the moment nothing is ever coming
     * back to resume THIS run — a fresh attempt starts a new one instead — so only that gets cleared.
     */
    private static void clearParkMarks(ArtifactStore store, Story story) {
        for (UUID runId : story.runIds()) {
            Run run = store.root().runs.get(runId);
            if (run == null || run.parkedAt() == null) {
                continue;
            }
            run.setParkedAt(null);
            run.setParkReason(null);
            try {
                // updateRun, not a put back into the runs map: this is the SAME instance the map
                // already binds, so re-putting it changes no binding and EclipseStore's lazy storer
                // skips it entirely. The mark was cleared in memory and came back on the next
                // restart. updateRun stores the run object itself, which is the only write that
                // reaches these two fields.
                store.updateRun(run);
            } catch (Exception e) {
                // Cosmetic: a run nobody is coming back to keeping a stale "parked" mark is not a
                // reason to fail the retry itself, which is already durable by this point.
                log.warn("Could not clear the park mark on run {} while retrying {}: {}",
                    runId, story.key(), e.toString());
            }
        }
    }

    @Override
    public String acceptStory(String storyId) {
        return mutate(storyId, (store, projectId, story) -> accept(store, projectId, story, "human"));
    }

    /**
     * Accepting a story on behalf of someone other than the person at the Console: the same
     * acceptance, with who decided written on the story and in the journal.
     */
    String acceptStoryAs(String storyId, String acceptedBy) {
        return mutate(storyId,
            (store, projectId, story) -> accept(store, projectId, story, acceptedBy));
    }

    /**
     * The whole of accepting a story, for both the person who presses the button and the unattended
     * pilot that presses it for them overnight.
     *
     * <p>One implementation on purpose. The two differ in exactly one thing — who is allowed to
     * decide, which {@link UnattendedAcceptance} answers — and in nothing else: the same criteria are
     * stamped with the same commit, the same requirements flip to implemented on the same rule, and
     * the same code lands on the delivery branch. A second acceptance path would be a second set of
     * rules about what "done" means.
     *
     * @param acceptedBy {@code "human"} or {@code "unattended"} — recorded on the story and in the
     *                   journal, because a story the machine accepted while the operator slept is a
     *                   weaker claim than one a person looked at, and the record must show which
     */
    static String accept(ArtifactStore store, UUID projectId, Story story, String acceptedBy) {
        {
            if (story.state() != StoryState.REVIEW) {
                return "error: " + story.key() + " is " + story.state().label()
                    + " — only a story that has come back for your verdict can be accepted";
            }
            // The code goes onto the delivery branch FIRST, and the acceptance is abandoned if it
            // cannot. Until now nothing ever merged anything into the branch the operator works on:
            // accepting was a state change in a database, the finished code stayed on a branch called
            // swarm/integration/<some uuid>, and the next story — which is cut from the delivery
            // branch like everything else — could not see a line of it. "Done" has to mean the work
            // is where the next story will find it, or the word is worthless.
            ConsoleContext context = ConsoleContext.get();
            String refused = context == null ? null : context.deliverStory(story.id());
            if (refused != null) {
                return "error: " + story.key() + " was not accepted, because its code could not be "
                    + "put with the rest of the project. " + refused;
            }
            Instant now = Instant.now();
            Brd brd = store.ensureBrd(projectId);
            List<UUID> delivered = story.criterionIds();
            int stamped = 0;
            for (BrdRequirement requirement : brd.requirements()) {
                boolean touched = false;
                for (AcceptanceCriterion criterion : requirement.criteria()) {
                    if (!delivered.contains(criterion.id())) {
                        continue;
                    }
                    criterion.setVerification(CriterionState.PASSING);
                    criterion.setLastVerifiedCommit(story.deliveredCommit());
                    criterion.setLastVerifiedAt(now);
                    criterion.setLastVerifiedRunId(lastRunOf(story));
                    // Stamp WHICH wording this passed against, so a later edit renders it STALE
                    // rather than letting old evidence vouch for new text.
                    criterion.setVerifiedAgainstContentRevision(requirement.contentRevision());
                    store.recordVerification(new CriterionVerification(UUID.randomUUID(),
                        criterion.id(), requirement.id(), lastRunOf(story), story.id(), null,
                        story.deliveredCommit(), VerificationResult.PASSING,
                        criterion.testClassOrFile(), now));
                    touched = true;
                    stamped++;
                }
                if (touched) {
                    RequirementStatus before = requirement.status();
                    // Only IMPLEMENTED once EVERY accepted criterion passes — a requirement is not
                    // delivered because one slice of it was.
                    RequirementStatus after = requirement.statusFromEvidence();
                    requirement.setStatus(after);
                    if (before != after) {
                        store.recordChange(projectId, "human", ChangeEntityType.REQUIREMENT,
                            requirement.id(), ChangeKind.STATE_CHANGED, "status",
                            String.valueOf(before), String.valueOf(after),
                            requirement.handle() + " went from " + before.label() + " to "
                                + after.label() + " on accepting " + story.key(),
                            lastRunOf(story));
                    }
                }
            }
            if (stamped > 0) {
                brd.setRequirements(new ArrayList<>(brd.requirements()));
                store.saveBrd(brd, acceptedBy, "accepted " + story.key()
                    + " — " + stamped + (stamped == 1 ? " check" : " checks") + " verified");
                // Republished, because this mutation CHANGES the requirement graph: criteria become
                // PASSING and a requirement can reach IMPLEMENTED. Only the backlog was published
                // before, so the requirements editor and its reference pane went on showing the old
                // status until something unrelated happened to republish the BRD. That was invisible
                // until the header started reporting "N agreed" from the server — at which point one
                // screen said "1 agreed" while the pane beside it still badged the same requirement
                // DRAFT. A fresh copy, never the mutated canonical instance: the signal dedups by
                // equals, so publishing the same object it already holds is a no-op.
                try {
                    com.swarmcoder.console.api.BrdSignals.CURRENT.set(ArtifactStore.copyOf(brd));
                } catch (Exception e) {
                    // Publishing must never break the acceptance itself; the store is already correct.
                    log.warn("Could not republish the BRD after accepting {}: {}",
                        story.key(), e.toString());
                }
            }
            story.setState(StoryState.DONE);
            story.setAcceptedBy(acceptedBy);
            story.setWaitingReason(null);
            store.saveStory(story);
            store.recordChange(projectId, acceptedBy, ChangeEntityType.STORY, story.id(),
                ChangeKind.STATE_CHANGED, "state", "REVIEW", "DONE",
                "unattended".equals(acceptedBy)
                    ? "accepted " + story.key() + " without anyone looking at it, because every "
                        + "check it promised was proved by a test that really ran. Nobody has said "
                        + "yet that it is what they wanted."
                    : "accepted " + story.key(),
                lastRunOf(story));
            return "";
        }
    }

    /** The run that produced the delivered work, for provenance on the verification records. */
    private static UUID lastRunOf(Story story) {
        List<UUID> runs = story.runIds();
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }

    @Override
    public String scheduleStory(String storyId, String iterationId, int order) {
        return mutate(storyId, (store, projectId, story) -> {
            UUID iteration = parseUuid(iterationId);
            if (iterationId != null && !iterationId.isBlank() && iteration == null) {
                return "error: unknown iteration";
            }
            if (iteration != null && store.getIteration(iteration) == null) {
                return "error: unknown iteration";
            }
            story.setIterationId(iteration);
            story.setOrder(Math.max(0, order));
            store.saveStory(story);
            store.recordChange(projectId, "human", ChangeEntityType.STORY, story.id(),
                iteration == null ? ChangeKind.UNLINKED : ChangeKind.LINKED,
                iteration == null ? story.key() + " unscheduled"
                    : story.key() + " scheduled at position " + story.order());
            return "";
        });
    }

    @Override
    public String addIteration(String name, String goal) {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        if (projectId == null) {
            return "error: no current project";
        }
        if (name == null || name.isBlank()) {
            return "error: an iteration needs a name";
        }
        ArtifactStore store = context.store();
        List<Iteration> existing = store.listIterations(projectId);
        for (Iteration it : existing) {
            if (name.trim().equalsIgnoreCase(it.name())) {
                return "error: an iteration named \"" + it.name() + "\" already exists";
            }
        }
        int seq = existing.stream().mapToInt(Iteration::seq).max().orElse(0) + 1;
        Iteration iteration = new Iteration(UUID.randomUUID(), projectId, name.trim(),
            goal == null || goal.isBlank() ? null : goal.trim(), seq,
            IterationState.PLANNING, Instant.now(), null);
        store.saveIteration(iteration);
        store.recordChange(projectId, "human", ChangeEntityType.ITERATION, iteration.id(),
            ChangeKind.CREATED, "added iteration \"" + iteration.name() + "\"");
        BacklogPublisher.publish(store, projectId);
        return "";
    }

    @Override
    public String startSession(String storyId) {
        return start(storyId, false);
    }

    @Override
    public String startSessionAnyway(String storyId) {
        return start(storyId, true);
    }

    /**
     * @param overrule true when the operator has deliberately chosen to build a story that is still
     *                 waiting for another one. Never true for anything the machine decides by itself.
     */
    private String start(String storyId, boolean overrule) {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        if (projectId == null) {
            return "error: no current project";
        }
        ArtifactStore store = context.store();
        Story story = findStory(store, projectId, storyId);
        if (story == null) {
            return "error: unknown story";
        }
        if (story.state() != StoryState.READY) {
            return "error: " + story.key() + " is " + story.state().label()
                + " — only a story that is ready to build can be started";
        }
        // The gate that did not exist. Nine stories were once started together from one plan; the
        // first was writing the domain model and the other eight each invented their own version of
        // a model that was not there yet. A story that builds on another does not begin until that
        // one is accepted, because accepting is what puts its code where this story's workers will
        // find it.
        StoryGraph.Blocked blocked = StoryGraph.blockedBy(story, store.listStories(projectId));
        if (blocked.any() && !overrule) {
            return "error: " + blocked.reason();
        }
        String goal = goalFor(store, projectId, story);
        UUID runId;
        try {
            // Bound to the story BEFORE the engine sees it. The story is the durable work item and
            // the run is one ATTEMPT at it, so this link is what keeps the requirement→commit trace
            // intact across a retry — and it has to exist from the first instant, because the
            // engine starts advancing the run on another thread the moment it is handed over and
            // rebuilds it from its own copy at every transition. Binding afterwards was a race the
            // caller lost silently, leaving the run unscoped: it then invented its own
            // requirements, planned without criterion refs, and finished without moving the story
            // to REVIEW or stamping anything.
            runId = context.startRun(goal, "GREENFIELD", story.id());
        } catch (Exception e) {
            log.warn("Starting a run for story {} failed", story.key(), e);
            return "error: could not start the run: " + e.getMessage();
        }
        if (runId == null) {
            return "error: the run could not be started";
        }
        if (!context.bindsStoriesAtStart()) {
            // Older wiring: the best that can be done is to bind it now and say so when it fails.
            Run run = store.root().runs.get(runId);
            if (run == null) {
                log.warn("Run {} for story {} was started by a ConsoleContext that cannot bind a "
                    + "story at start, and is not in the store yet — this run will not be scoped "
                    + "to the story and will not stamp its criteria", runId, story.key());
            } else {
                run.setStoryId(story.id());
                try {
                    store.updateRun(run);
                } catch (Exception e) {
                    log.warn("Run {} could not be bound to story {}", runId, story.key(), e);
                }
            }
        }
        List<UUID> runIds = new ArrayList<>(story.runIds());
        runIds.add(runId);
        story.setRunIds(runIds);
        story.setState(StoryState.RUNNING);
        store.saveStory(story);
        store.recordChange(projectId, "human", ChangeEntityType.STORY, story.id(),
            ChangeKind.STATE_CHANGED, "state", "READY", "RUNNING",
            "started building " + story.key(), runId);
        BacklogPublisher.publish(store, projectId);
        return runId.toString();
    }

    @Override
    public String setStoryDependencies(String storyId, String dependsOnKeys) {
        return mutate(storyId, (store, projectId, story) -> {
            List<Story> all = store.listStories(projectId);
            List<UUID> resolved = new ArrayList<>();
            for (String key : (dependsOnKeys == null ? "" : dependsOnKeys).split("[,\\s]+")) {
                if (key.isBlank()) {
                    continue;
                }
                Story target = null;
                for (Story candidate : all) {
                    if (key.trim().equalsIgnoreCase(candidate.key())) {
                        target = candidate;
                        break;
                    }
                }
                if (target == null) {
                    return "error: this project has no story called " + key.trim();
                }
                if (target.id().equals(story.id())) {
                    return "error: " + story.key() + " cannot wait for itself";
                }
                if (!resolved.contains(target.id())) {
                    resolved.add(target.id());
                }
            }
            List<UUID> was = story.declaredDependsOn();
            story.setDependsOnStoryIds(resolved);
            // Validated against the WHOLE backlog with the change applied, not against this story
            // alone: a circle is a property of the graph, and the only moment it can be caught
            // cheaply is before it is written.
            StoryGraph.Verdict verdict = StoryGraph.validate(withUpdated(all, story));
            if (!verdict.ok()) {
                story.setDependsOnStoryIds(was);
                return "error: " + String.join("; ", verdict.violations());
            }
            store.saveStory(story);
            store.recordChange(projectId, "human", ChangeEntityType.STORY, story.id(),
                resolved.isEmpty() ? ChangeKind.UNLINKED : ChangeKind.LINKED,
                resolved.isEmpty()
                    ? story.key() + " no longer waits for anything"
                    : story.key() + " now waits for " + describe(all, resolved));
            return "";
        });
    }

    @Override
    public String setStoryWorkers(String storyId, int workersPerTask) {
        if (workersPerTask < 0 || workersPerTask > MAX_STORY_WORKERS) {
            return "error: a story can ask for between 1 and " + MAX_STORY_WORKERS
                + " attempts, or 0 to use the project's number";
        }
        return mutate(storyId, (store, projectId, story) -> {
            Integer was = story.workersPerTask();
            Integer now = workersPerTask < 1 ? null : workersPerTask;
            if (java.util.Objects.equals(was, now)) {
                return "";
            }
            story.setWorkersPerTask(now);
            store.saveStory(story);
            store.recordChange(projectId, "human", ChangeEntityType.STORY, story.id(),
                ChangeKind.UPDATED, "workersPerTask",
                was == null ? "" : String.valueOf(was), now == null ? "" : String.valueOf(now),
                now == null
                    ? story.key() + " goes back to however many attempts the project asks for"
                    : story.key() + " now gets " + now + " attempts at each piece of work",
                null);
            return "";
        });
    }

    /**
     * The most attempts a single story may ask for.
     *
     * <p>Not a machine limit — that is {@code swarm.maxConcurrentWorkers}, which no story can get
     * past however large a number is typed here. This is a typo guard: 40 in this box would still
     * be run, in batches, and would take all night doing it.
     */
    private static final int MAX_STORY_WORKERS = 16;

    /** The backlog with this story's in-memory version substituted for the stored one. */
    private static List<Story> withUpdated(List<Story> all, Story updated) {
        List<Story> merged = new ArrayList<>();
        for (Story story : all) {
            merged.add(story.id().equals(updated.id()) ? updated : story);
        }
        return merged;
    }

    /** "S1 \"Store a book record\" and S4 \"Search the shelf\"" — never a bare list of codes. */
    private static String describe(List<Story> all, List<UUID> ids) {
        List<String> labels = new ArrayList<>();
        for (UUID id : ids) {
            for (Story story : all) {
                if (story.id().equals(id)) {
                    labels.add(story.title() == null || story.title().isBlank()
                        ? story.key() : story.key() + " \"" + story.title() + "\"");
                }
            }
        }
        return String.join(", ", labels);
    }

    @Override
    public List<ChangeEvent> history(String entityId) {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        UUID id = parseUuid(entityId);
        if (projectId == null || id == null) {
            return List.of();
        }
        return context.store().changeHistory(projectId, id);
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * The goal handed to the workflow. Built from the story plus the exact criteria it delivers, so
     * the run is scoped to the slice rather than to a title someone typed. Increment 4 replaces the
     * prose with the Architect reading these requirements directly.
     */
    private static String goalFor(ArtifactStore store, UUID projectId, Story story) {
        StringBuilder goal = new StringBuilder(story.title() == null ? "" : story.title());
        if (story.narrative() != null && !story.narrative().isBlank()) {
            goal.append("\n\n").append(story.narrative());
        }
        Brd brd = store.ensureBrd(projectId);
        List<String> lines = new ArrayList<>();
        for (BrdRequirement r : brd.requirements()) {
            List<AcceptanceCriterion> criteria = r.criteria();
            for (int i = 0; i < criteria.size(); i++) {
                AcceptanceCriterion c = criteria.get(i);
                if (story.criterionIds().contains(c.id())) {
                    lines.add("- " + r.handle() + ":C" + (i + 1) + " (" + r.title() + ") "
                        + c.text() + (c.testClassOrFile() == null ? ""
                            : "  [test: " + c.testClassOrFile() + "]"));
                }
            }
        }
        if (!lines.isEmpty()) {
            goal.append("\n\nAcceptance criteria this story must satisfy:\n")
                .append(String.join("\n", lines));
        }
        return goal.toString();
    }

    private interface StoryMutation {
        String apply(ArtifactStore store, UUID projectId, Story story);
    }

    /** Resolves the story, applies the mutation, and republishes on success. */
    private String mutate(String storyId, StoryMutation mutation) {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        if (projectId == null) {
            return "error: no current project";
        }
        ArtifactStore store = context.store();
        Story story = findStory(store, projectId, storyId);
        if (story == null) {
            return "error: unknown story";
        }
        String result = mutation.apply(store, projectId, story);
        if (result.isEmpty()) {
            BacklogPublisher.publish(store, projectId);
        }
        return result;
    }

    private static Story findStory(ArtifactStore store, UUID projectId, String storyId) {
        UUID id = parseUuid(storyId);
        if (id == null) {
            return null;
        }
        Story story = store.getStory(id);
        return story != null && projectId.equals(story.projectId()) ? story : null;
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
}
