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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.Iteration;
import com.swarmcoder.domain.IterationState;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryGraph;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Backlog mutations for the backlog-planning flow: proposing stories over the requirement graph,
 * grouping them into iterations, and reporting what is not yet covered.
 *
 * <p>These are the ONLY way a story reaches the backlog from an agent. {@link BacklogPlanning}
 * applies its accepted proposals through {@link #proposeStory} and {@link #proposeEnabler} rather
 * than writing {@code Story} objects itself, so the rules below hold no matter which surface asked.
 *
 * <p>The invariant this class exists to protect: <b>a story carries no requirement content of its
 * own.</b> A story is defined by the criteria it delivers, referenced as {@code R7:C1}, never by
 * paraphrasing the requirement into story prose — that is how two competing descriptions of the same
 * thing appear and start drifting. A story whose criteria do not exist in the BRD is rejected, with
 * the agent told to propose the requirement instead.
 *
 * <p>The second invariant, and the one the product sells: <b>a story may only name checks on a
 * requirement the operator has AGREED.</b> Enforced in {@code resolve} and in
 * {@link #proposeEnabler}, so it holds however the story was asked for — the planning wizard, an
 * edit to a draft story, or any surface added later. See {@code refuseUnagreed} for what it cost
 * to leave that rule in a prompt instead.
 *
 * <p>Agent-proposed stories land {@link StoryState#DRAFT}; the operator promotes them to READY
 * before they can be run.
 */
final class BacklogAuthoring {

    private BacklogAuthoring() {}

    /** A compact rendering of the backlog for the agent: iterations, stories, and their slices. */
    static String render(ArtifactStore store, UUID projectId) {
        List<Iteration> iterations = store.listIterations(projectId);
        List<Story> stories = store.listStories(projectId);
        if (iterations.isEmpty() && stories.isEmpty()) {
            return "(the backlog is empty — no iterations and no stories yet)";
        }
        Brd brd = store.ensureBrd(projectId);
        StringBuilder sb = new StringBuilder();
        for (Iteration it : iterations) {
            sb.append("Iteration \"").append(it.name()).append("\" [").append(it.state()).append(']');
            if (it.goal() != null && !it.goal().isBlank()) {
                sb.append(" — ").append(it.goal());
            }
            sb.append('\n');
            appendStories(sb, stories.stream().filter(s -> it.id().equals(s.iterationId())).toList(), brd);
        }
        List<Story> unscheduled = stories.stream().filter(s -> s.iterationId() == null).toList();
        if (!unscheduled.isEmpty()) {
            sb.append("Unscheduled backlog:\n");
            appendStories(sb, unscheduled, brd);
        }
        return sb.toString();
    }

    private static void appendStories(StringBuilder sb, List<Story> stories, Brd brd) {
        if (stories.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (Story s : stories) {
            sb.append("  ").append(s.key()).append(" [").append(s.kind())
                .append('/').append(s.state()).append("] ").append(s.title() == null ? "" : s.title());
            List<String> refs = criterionRefs(brd, s.criterionIds());
            if (!refs.isEmpty()) {
                sb.append("  delivers ").append(String.join(",", refs));
            } else if (s.kind() == StoryKind.ENABLER) {
                List<String> handles = handlesFor(brd, s.requirementIds());
                sb.append("  unblocks ").append(handles.isEmpty() ? "(nothing linked)" : String.join(",", handles));
            }
            sb.append('\n');
        }
    }

    /**
     * Reports what the backlog does not yet cover — the question the operator actually needs
     * answered before deciding what to build next.
     */
    // The operator reads this VERBATIM. The planning wizard prints it in the box headed "What
    // the planner will be asked to close" (GuidedFlows.describeInputs -> FlowView.coverage),
    // which makes it one of the very few strings in this class with two audiences. It used to
    // open with "ACTIVE requirements with NO criteria" - a persisted enum constant and the Java
    // type's name, three lines under the figure captioned "checks no story delivers". It is
    // written in the operator's words now (UX v3 4), and the model reads plain English.
    static String coverage(ArtifactStore store, UUID projectId) {
        Brd brd = store.ensureBrd(projectId);
        Set<UUID> claimed = claimedCriterionIds(store, projectId);
        StringBuilder gaps = new StringBuilder();
        StringBuilder noCriteria = new StringBuilder();
        for (BrdRequirement r : brd.requirements()) {
            if (r.status() != RequirementStatus.ACTIVE) {
                continue;
            }
            List<AcceptanceCriterion> criteria = r.criteria();
            if (criteria.isEmpty()) {
                noCriteria.append("  ").append(r.handle()).append(' ')
                    .append(r.title() == null ? "" : r.title()).append('\n');
                continue;
            }
            List<String> unclaimed = new ArrayList<>();
            for (int i = 0; i < criteria.size(); i++) {
                if (!claimed.contains(criteria.get(i).id())) {
                    unclaimed.add("C" + (i + 1));
                }
            }
            if (!unclaimed.isEmpty()) {
                gaps.append("  ").append(r.handle()).append(' ')
                    .append(r.title() == null ? "" : r.title())
                    .append(" — unclaimed: ").append(String.join(",", unclaimed)).append('\n');
            }
        }
        StringBuilder sb = new StringBuilder();
        if (noCriteria.length() > 0) {
            sb.append("Agreed requirements with no checks (nothing can verify them):\n")
                .append(noCriteria);
        }
        if (gaps.length() > 0) {
            sb.append("Checks no story delivers yet:\n").append(gaps);
        }
        if (sb.length() == 0) {
            sb.append("Every check on every agreed requirement is claimed by a story.");
        }
        return sb.toString();
    }

    /**
     * The criteria some living story already claims. A CANCELLED story is a tombstone, so its
     * criteria are unclaimed again — which is the only way an operator can hand work back to
     * planning after abandoning it.
     */
    static Set<UUID> claimedCriterionIds(ArtifactStore store, UUID projectId) {
        Set<UUID> claimed = new LinkedHashSet<>();
        for (Story s : store.listStories(projectId)) {
            if (s.state() != StoryState.CANCELLED) {
                claimed.addAll(s.criterionIds());
            }
        }
        return claimed;
    }

    /**
     * How many criteria of ACTIVE requirements no story yet delivers — the same set
     * {@link #coverage} names, counted.
     *
     * <p>It shares {@link #claimedCriterionIds} with the report on purpose. The planning wizard
     * shows this number, refuses to start when it is zero, and prints the report next to it; two
     * implementations of "unclaimed" would eventually disagree, and a dialog that says "3 criteria
     * are unclaimed" above a list of five is worse than one that says nothing.
     */
    static int unclaimedCriteria(ArtifactStore store, UUID projectId) {
        Brd brd = store.getBrd(projectId);
        if (brd == null || brd.requirements() == null) {
            return 0;
        }
        Set<UUID> claimed = claimedCriterionIds(store, projectId);
        int unclaimed = 0;
        for (BrdRequirement r : brd.requirements()) {
            if (r.status() != RequirementStatus.ACTIVE) {
                continue;
            }
            for (AcceptanceCriterion c : r.criteria()) {
                if (!claimed.contains(c.id())) {
                    unclaimed++;
                }
            }
        }
        return unclaimed;
    }

    /** How many stories the backlog holds, tombstones excluded. */
    static int livingStories(ArtifactStore store, UUID projectId) {
        int count = 0;
        for (Story s : store.listStories(projectId)) {
            if (s.state() != StoryState.CANCELLED) {
                count++;
            }
        }
        return count;
    }

    /** Proposes a DELIVERY story over a set of criteria refs (R7:C1,R7:C2). */
    static String proposeStory(ArtifactStore store, UUID projectId, String title,
                               String criteriaRefs, String narrative) {
        if (blank(title)) {
            return "error: a story needs a title";
        }
        Brd brd = store.ensureBrd(projectId);
        Resolved resolved = resolve(brd, criteriaRefs);
        if (resolved.error != null) {
            return resolved.error;
        }
        if (resolved.criterionIds.isEmpty()) {
            return "error: a delivery story must deliver at least one criterion (e.g. R7:C1,R7:C2). "
                + "If the capability you have in mind is not in the BRD, propose the requirement "
                + "first — a story must not introduce requirement content of its own.";
        }
        Story story = newStory(store, projectId, title, StoryKind.DELIVERY, narrative);
        story.setRequirementIds(new ArrayList<>(resolved.requirementIds));
        story.setCriterionIds(new ArrayList<>(resolved.criterionIds));
        store.saveStory(story);
        store.recordChange(projectId, "agent", ChangeEntityType.STORY, story.id(),
            ChangeKind.CREATED, "proposed " + story.key() + " (" + title + ")");
        BacklogPublisher.publish(store, projectId);
        return story.key() + " proposed as DRAFT delivering " + criteriaRefs
            + " — the operator promotes it to READY before it can run";
    }

    /** Proposes an ENABLER story: technical work that unblocks requirements but delivers none. */
    static String proposeEnabler(ArtifactStore store, UUID projectId, String title,
                                 String unblocksHandles, String rationale) {
        if (blank(title)) {
            return "error: an enabler story needs a title";
        }
        Brd brd = store.ensureBrd(projectId);
        List<UUID> unblocks = new ArrayList<>();
        for (String handle : split(unblocksHandles)) {
            BrdRequirement r = requirementFor(brd, handle);
            if (r == null) {
                return "error: no requirement " + handle;
            }
            // The same gate as a delivery story's, at the other door. An enabler names requirement
            // HANDLES rather than checks, so it would otherwise be the way round the resolver: no
            // criterion is claimed, and work still gets scheduled for something nobody agreed.
            if (!r.isAgreed()) {
                return refuseUnagreed(r, handle);
            }
            unblocks.add(r.id());
        }
        Story story = newStory(store, projectId, title, StoryKind.ENABLER, null);
        story.setRequirementIds(unblocks);
        story.setRationale(blankToNull(rationale));
        store.saveStory(story);
        store.recordChange(projectId, "agent", ChangeEntityType.STORY, story.id(),
            ChangeKind.CREATED, "proposed enabler " + story.key() + " (" + title + ")");
        BacklogPublisher.publish(store, projectId);
        return story.key() + " proposed as a DRAFT enabler"
            + (unblocks.isEmpty() ? " (linked to no requirement — say what it unblocks)" : "");
    }

    /**
     * The second pass of applying a plan: records which of the newly written stories has to come
     * after which.
     *
     * <p>Separate from writing them because a dependency is an id, and half the stories being
     * depended on are being created by the same apply — they have no id until the first pass has
     * finished. This resolves the planner's references, which are either the exact TITLE of another
     * story in the same plan or the key of one already in the backlog, and refuses the whole set if
     * it would make a circle.
     *
     * <p>A reference that resolves to nothing is dropped and reported rather than guessed at. A
     * wrong edge is worse than a missing one: a missing edge is a story that runs too early and
     * fails visibly, while an invented edge is a story that waits for ever for something that was
     * never going to arrive.
     *
     * @param writtenFor   proposal id → the title it was written under, for the ones that landed
     * @param referencesOf reads a proposal's rendered block back into the list of things it must
     *                     come after
     * @return null when everything linked, otherwise a plain sentence about what did not
     */
    static String linkPlannedDependencies(ArtifactStore store, UUID projectId,
                                          List<FlowProposal> proposals,
                                          java.util.Map<UUID, String> writtenFor,
                                          java.util.function.Function<String, List<String>> referencesOf) {
        List<Story> all = store.listStories(projectId);
        // Newest first, so a title written twice resolves to the copy this apply just created rather
        // than to an older story that happens to share it.
        List<Story> newestFirst = new ArrayList<>(all);
        java.util.Collections.reverse(newestFirst);

        List<String> unresolved = new ArrayList<>();
        List<Story> changed = new ArrayList<>();
        for (FlowProposal proposal : proposals) {
            String title = writtenFor.get(proposal.id());
            if (title == null) {
                continue;   // not applied, so there is nothing to link
            }
            Story story = firstMatching(newestFirst, title);
            if (story == null) {
                continue;
            }
            List<UUID> dependencies = new ArrayList<>();
            for (String reference : referencesOf.apply(proposal.after())) {
                Story target = firstMatching(newestFirst, reference);
                if (target == null || target.id().equals(story.id())
                        || dependencies.contains(target.id())) {
                    if (target == null) {
                        unresolved.add("\"" + reference + "\" (named by " + story.key() + ")");
                    }
                    continue;
                }
                dependencies.add(target.id());
            }
            if (!dependencies.isEmpty()) {
                story.setDependsOnStoryIds(dependencies);
                changed.add(story);
            }
        }
        if (changed.isEmpty()) {
            return report(unresolved, all);
        }
        StoryGraph.Verdict verdict = StoryGraph.validate(store.listStories(projectId));
        if (!verdict.ok()) {
            // Nothing is half-linked. A partly-applied ordering is a schedule nobody designed.
            changed.forEach(story -> story.setDependsOnStoryIds(new ArrayList<>()));
            return "the planner's ordering could not be used — " + String.join("; ",
                verdict.violations()) + ". The stories were still added; put them in order by hand.";
        }
        for (Story story : changed) {
            store.saveStory(story);
            store.recordChange(projectId, "agent", ChangeEntityType.STORY, story.id(),
                ChangeKind.LINKED, story.key() + " comes after "
                    + describe(all, story.declaredDependsOn()));
        }
        BacklogPublisher.publish(store, projectId);
        return report(unresolved, all);
    }

    /**
     * What could not be matched, AND what it could have matched.
     *
     * <p>The half that used to be missing is the second half. The message named only the text that
     * failed, which for a dependency — a whole story title, typed back by a model — is exactly the
     * string the reader already cannot place. Printing the names that do exist next to it turns a
     * baffling line into a one-glance comparison: a near miss shows up as a near miss, and a name
     * for something that was never proposed shows up as absent from the list.
     *
     * <p>The list of existing names is capped. A backlog of two hundred stories would bury the
     * failure it is meant to explain.
     */
    private static String report(List<String> unresolved, List<Story> all) {
        if (unresolved.isEmpty()) {
            return null;
        }
        return "these stories were said to come after something that is not in the plan, so the "
            + "ordering was left off them: " + String.join(", ", unresolved)
            + ". A story has to be named by its exact title, or by its key. "
            + namesThatExist(all);
    }

    /** The stories a dependency could have named, keys and titles, capped so it stays readable. */
    private static String namesThatExist(List<Story> all) {
        if (all == null || all.isEmpty()) {
            return "There are no stories in this project to come after.";
        }
        int limit = 12;
        List<String> names = new ArrayList<>();
        for (Story story : all) {
            if (names.size() == limit) {
                break;
            }
            String title = story.title() == null ? "" : story.title().strip();
            names.add(story.key() + (title.isEmpty() ? "" : " \"" + title + "\""));
        }
        return "The stories that exist are: " + String.join(", ", names)
            + (all.size() > limit ? " and " + (all.size() - limit) + " more." : ".");
    }

    /** A story matched by its key first, then by its exact title, ignoring case and stray spaces. */
    private static Story firstMatching(List<Story> stories, String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String wanted = reference.strip();
        for (Story story : stories) {
            if (wanted.equalsIgnoreCase(story.key())) {
                return story;
            }
        }
        for (Story story : stories) {
            if (story.title() != null && wanted.equalsIgnoreCase(story.title().strip())) {
                return story;
            }
        }
        return null;
    }

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
        return labels.isEmpty() ? "another story" : String.join(", ", labels);
    }

    /** Updates a DRAFT story's title, narrative or slice. */
    static String updateStory(ArtifactStore store, UUID projectId, String key,
                              String title, String narrative, String criteriaRefs) {
        Story story = storyFor(store, projectId, key);
        if (story == null) {
            return "error: no story " + key;
        }
        if (story.state() != StoryState.DRAFT) {
            return "error: " + story.key() + " is " + story.state()
                + " — only DRAFT stories can be reshaped by you; ask the operator to change a promoted one";
        }
        if (!blank(title)) {
            story.setTitle(title.trim());
        }
        if (!blank(narrative)) {
            story.setNarrative(narrative.trim());
        }
        if (!blank(criteriaRefs)) {
            Resolved resolved = resolve(store.ensureBrd(projectId), criteriaRefs);
            if (resolved.error != null) {
                return resolved.error;
            }
            story.setRequirementIds(new ArrayList<>(resolved.requirementIds));
            story.setCriterionIds(new ArrayList<>(resolved.criterionIds));
        }
        store.saveStory(story);
        store.recordChange(projectId, "agent", ChangeEntityType.STORY, story.id(),
            ChangeKind.UPDATED, "edited " + story.key());
        BacklogPublisher.publish(store, projectId);
        return "updated " + story.key();
    }

    /** Creates an iteration — a named ordered batch, with no dates and no points. */
    static String addIteration(ArtifactStore store, UUID projectId, String name, String goal) {
        if (blank(name)) {
            return "error: an iteration needs a name";
        }
        List<Iteration> existing = store.listIterations(projectId);
        for (Iteration it : existing) {
            if (name.trim().equalsIgnoreCase(it.name())) {
                return "error: an iteration named \"" + it.name() + "\" already exists";
            }
        }
        int seq = existing.stream().mapToInt(Iteration::seq).max().orElse(0) + 1;
        Iteration iteration = new Iteration(UUID.randomUUID(), projectId, name.trim(),
            blankToNull(goal), seq, IterationState.PLANNING, Instant.now(), null);
        store.saveIteration(iteration);
        store.recordChange(projectId, "agent", ChangeEntityType.ITERATION, iteration.id(),
            ChangeKind.CREATED, "added iteration \"" + iteration.name() + "\"");
        BacklogPublisher.publish(store, projectId);
        return "iteration \"" + iteration.name() + "\" added (position " + seq + ")";
    }

    /** Places a story in an iteration at a position, or removes it from one with "backlog". */
    static String scheduleStory(ArtifactStore store, UUID projectId, String key,
                                String iterationName, String position) {
        Story story = storyFor(store, projectId, key);
        if (story == null) {
            return "error: no story " + key;
        }
        if (iterationName != null && iterationName.trim().equalsIgnoreCase("backlog")) {
            story.setIterationId(null);
            store.saveStory(story);
            store.recordChange(projectId, "agent", ChangeEntityType.STORY, story.id(),
                ChangeKind.UNLINKED, story.key() + " returned to the unscheduled backlog");
            BacklogPublisher.publish(store, projectId);
            return story.key() + " moved back to the unscheduled backlog";
        }
        Iteration iteration = null;
        for (Iteration it : store.listIterations(projectId)) {
            if (it.name() != null && it.name().equalsIgnoreCase(blankToNull(iterationName))) {
                iteration = it;
                break;
            }
        }
        if (iteration == null) {
            return "error: no iteration named \"" + iterationName + "\" (use add_iteration first)";
        }
        story.setIterationId(iteration.id());
        story.setOrder(parseOrder(position, store.listStories(projectId).size()));
        store.saveStory(story);
        store.recordChange(projectId, "agent", ChangeEntityType.STORY, story.id(),
            ChangeKind.LINKED, story.key() + " scheduled into \"" + iteration.name() + "\"");
        BacklogPublisher.publish(store, projectId);
        return story.key() + " scheduled into \"" + iteration.name() + "\" at position " + story.order();
    }

    // --- helpers -------------------------------------------------------------------------------

    private static Story newStory(ArtifactStore store, UUID projectId, String title,
                                  StoryKind kind, String narrative) {
        return new Story(UUID.randomUUID(), projectId, store.nextStoryKey(projectId), kind,
            title.trim(), blankToNull(narrative), StoryState.DRAFT, new ArrayList<>(),
            new ArrayList<>(), null, 0, StoryOrigin.BACKLOG, null, null, "agent",
            new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
    }

    /** Criterion refs resolved to ids, plus the requirements they belong to. */
    private record Resolved(List<UUID> criterionIds, Set<UUID> requirementIds, String error) {}

    /**
     * <b>Why an unagreed requirement is refused here, in the resolver, and not asked for politely
     * in a prompt.</b>
     *
     * <p>Agreeing a requirement is the gate the product describes as the one that matters most:
     * nothing is built from a requirement the operator has not agreed. Until 2026-08-31 that gate
     * was one sentence in the planner's briefing — "Only ACTIVE requirements are agreed scope" —
     * and this resolver turned {@code R3:C2} into a criterion id without ever looking at R3's
     * status. So the rule held only while the model chose to keep it, and an end-to-end run
     * measured what that was worth: six stories claiming sixteen checks, of which one was agreed
     * and fifteen belonged to requirements still in draft.
     *
     * <p>The refusal is per REFERENCE and it fails the WHOLE story, not just the offending ref.
     * Dropping the unagreed half and writing the rest would produce a story that silently delivers
     * less than the operator read on the review screen, which is the failure this class exists to
     * prevent in its other form.
     */
    private static String refuseUnagreed(BrdRequirement r, String ref) {
        return "error: " + r.handle() + " is a " + label(r) + " requirement, not agreed scope, so "
            + ref + " cannot go in a story. Nothing is built from a requirement the operator has "
            + "not agreed — agree " + r.handle() + " in the Requirements panel first, and it "
            + "becomes plannable on the next run.";
    }

    /** The status in the operator's words ("draft", "retired"), never the Java constant. */
    private static String label(BrdRequirement r) {
        return r.status() == null ? "draft" : r.status().label();
    }

    private static Resolved resolve(Brd brd, String refs) {
        List<UUID> criterionIds = new ArrayList<>();
        Set<UUID> requirementIds = new LinkedHashSet<>();
        for (String ref : split(refs)) {
            String[] parts = ref.split(":");
            if (parts.length != 2) {
                return new Resolved(List.of(), Set.of(),
                    "error: address criteria as <requirement>:C<n>, e.g. R7:C1 — got \"" + ref + "\"");
            }
            BrdRequirement r = requirementFor(brd, parts[0]);
            if (r == null) {
                return new Resolved(List.of(), Set.of(), "error: no requirement " + parts[0]);
            }
            if (!r.isAgreed()) {
                return new Resolved(List.of(), Set.of(), refuseUnagreed(r, ref));
            }
            List<AcceptanceCriterion> criteria = r.criteria();
            int index = criterionIndex(parts[1]);
            if (index < 1 || index > criteria.size()) {
                return new Resolved(List.of(), Set.of(),
                    "error: " + r.handle() + " has " + criteria.size() + " criteria, so " + ref
                        + " does not exist");
            }
            criterionIds.add(criteria.get(index - 1).id());
            requirementIds.add(r.id());
        }
        return new Resolved(criterionIds, requirementIds, null);
    }

    private static List<String> criterionRefs(Brd brd, List<UUID> criterionIds) {
        List<String> refs = new ArrayList<>();
        for (BrdRequirement r : brd.requirements()) {
            List<AcceptanceCriterion> criteria = r.criteria();
            for (int i = 0; i < criteria.size(); i++) {
                if (criterionIds.contains(criteria.get(i).id())) {
                    refs.add(r.handle() + ":C" + (i + 1));
                }
            }
        }
        return refs;
    }

    private static List<String> handlesFor(Brd brd, List<UUID> requirementIds) {
        List<String> handles = new ArrayList<>();
        for (BrdRequirement r : brd.requirements()) {
            if (requirementIds.contains(r.id())) {
                handles.add(r.handle());
            }
        }
        return handles;
    }

    private static BrdRequirement requirementFor(Brd brd, String handle) {
        if (handle == null) {
            return null;
        }
        for (BrdRequirement r : brd.requirements()) {
            if (r.handle() != null && r.handle().equalsIgnoreCase(handle.trim())) {
                return r;
            }
        }
        return null;
    }

    private static Story storyFor(ArtifactStore store, UUID projectId, String key) {
        if (key == null) {
            return null;
        }
        for (Story s : store.listStories(projectId)) {
            if (s.key() != null && s.key().equalsIgnoreCase(key.trim())) {
                return s;
            }
        }
        return null;
    }

    private static int criterionIndex(String token) {
        String digits = token.trim().toUpperCase();
        if (digits.startsWith("C")) {
            digits = digits.substring(1);
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseOrder(String position, int fallback) {
        if (position == null || position.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(position.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
