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

import com.swarmcoder.console.api.CheckCountsDto;
import com.swarmcoder.console.api.RequirementPageDto;
import com.swarmcoder.console.api.RequirementQuery;
import com.swarmcoder.console.api.RequirementRowDto;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CheckCounts;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementRollup;
import com.swarmcoder.domain.RequirementTree;
import com.swarmcoder.domain.Story;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a {@link Brd} plus the project's stories into a page of tree rows
 * (see {@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §3.6/§3.7).
 *
 * <p>Separate from {@code BrdServiceImpl} because it is pure: a Brd and a story list in, a page out, no
 * store and no {@code ConsoleContext}. That is what makes the filtering rules testable without booting
 * a Console, and the filtering rules are where this gets subtle.
 *
 * <p><b>The rule that shapes everything here.</b> A filter may not orphan a match. If a check deep in
 * the tree matches, the operator has to see where it lives, so every ancestor of every match is included
 * and marked {@code contextOnly} — present for shape, not counted as a result (UX v3 rule 1). The three
 * counts on the page exist so the operator is never misled about which is which.
 */
final class RequirementRows {

    /** A ceiling on one window, so a client asking for everything cannot ask for a whole document. */
    static final int MAX_ROWS = 500;

    private RequirementRows() {}

    static RequirementPageDto page(Brd brd, List<Story> stories, RequirementQuery query,
                                   int offset, int max) {
        RequirementPageDto out = new RequirementPageDto();
        if (brd == null || brd.requirements() == null || brd.requirements().isEmpty()) {
            return out;
        }
        RequirementQuery q = query == null ? new RequirementQuery() : query;
        List<BrdRequirement> reqs = brd.requirements();
        RequirementTree tree = RequirementTree.of(brd);
        RequirementRollup rollup = RequirementRollup.of(brd, tree);
        Map<UUID, BrdRequirement> byId = new HashMap<>();
        for (BrdRequirement r : reqs) {
            if (r != null && r.id() != null) {
                byId.put(r.id(), r);
            }
        }

        // Which story claims which check, and which checks are claimed at all. Built once: asking per
        // requirement would re-walk every story's criterion list for every row.
        Map<UUID, List<String>> storyKeysByCriterion = new HashMap<>();
        Set<UUID> claimed = new HashSet<>();
        for (Story story : stories == null ? List.<Story>of() : stories) {
            if (story == null || story.criterionIds() == null) {
                continue;
            }
            for (UUID criterionId : story.criterionIds()) {
                if (criterionId == null) {
                    continue;
                }
                claimed.add(criterionId);
                storyKeysByCriterion.computeIfAbsent(criterionId, k -> new ArrayList<>())
                    .add(story.key() == null ? "?" : story.key());
            }
        }

        Set<UUID> matched = new LinkedHashSet<>();
        for (BrdRequirement r : reqs) {
            if (r != null && r.id() != null
                    && matches(r, q, tree, claimed, storyKeysByCriterion)) {
                matched.add(r.id());
            }
        }

        // Ancestors of matches, minus the matches themselves: rows that exist for shape only.
        Set<UUID> context = new LinkedHashSet<>();
        for (UUID id : matched) {
            UUID at = tree.parentOf(id);
            while (at != null && !matched.contains(at) && context.add(at)) {
                at = tree.parentOf(at);
            }
        }

        Set<UUID> visible = new LinkedHashSet<>(matched);
        visible.addAll(context);

        // Retired requirements are counted apart from everything else the query left out. "Your
        // filter did not match this" and "this is not part of the project any more" are different
        // sentences, and rolling them into one number would make retiring indistinguishable from
        // deleting on the only surface most operators look at.
        int retiredHidden = 0;
        for (BrdRequirement r : reqs) {
            if (r != null && r.id() != null && r.isRetired() && !visible.contains(r.id())) {
                retiredHidden++;
            }
        }

        // Depth-first from the roots, so a parent is always emitted before its parts and the client can
        // indent by depth without sorting anything. A pre-order walk is also why paging works at all:
        // the sequence is stable, so a window into it means the same thing on every call.
        List<UUID> ordered = new ArrayList<>();
        for (UUID root : tree.roots()) {
            walk(root, tree, visible, ordered);
        }

        int clamped = max <= 0 ? MAX_ROWS : Math.min(max, MAX_ROWS);
        int from = Math.max(0, offset);
        List<RequirementRowDto> rows = new ArrayList<>();
        for (int i = from; i < ordered.size() && rows.size() < clamped; i++) {
            UUID id = ordered.get(i);
            rows.add(row(brd, byId.get(id), tree, rollup, context.contains(id),
                storyKeysByCriterion, claimed, reqs));
        }

        out.setRows(rows);
        out.setMatches(matched.size());
        out.setContextParents(context.size());
        out.setExcluded(byId.size() - visible.size() - retiredHidden);
        out.setRetiredHidden(retiredHidden);
        out.setTotal(byId.size());
        out.setOffset(from);
        out.setHasMore(from + rows.size() < ordered.size() ? 1 : 0);
        out.setRevision(brd.revision());
        return out;
    }

    /** Pre-order, emitting only visible nodes but descending through hidden ones to reach them. */
    private static void walk(UUID id, RequirementTree tree, Set<UUID> visible, List<UUID> into) {
        if (visible.contains(id)) {
            into.add(id);
        }
        for (UUID child : tree.childrenOf(id)) {
            walk(child, tree, visible, into);
        }
    }

    private static RequirementRowDto row(Brd brd, BrdRequirement r, RequirementTree tree,
                                         RequirementRollup rollup, boolean contextOnly,
                                         Map<UUID, List<String>> storyKeysByCriterion,
                                         Set<UUID> claimed, List<BrdRequirement> all) {
        RequirementRowDto row = new RequirementRowDto();
        if (r == null) {
            return row;
        }
        row.setRequirementId(r.id() == null ? "" : r.id().toString());
        row.setHandle(nz(r.handle()));
        row.setTitle(nz(r.title()));
        // The kind itself, all three of them. This said non-functional-or-functional, so a
        // CONSTRAINT reached every client claiming to be an ordinary functional requirement —
        // and the client is where "no checks of its own yet" is decided, which is a gap for a
        // requirement and permanent and correct for a rule.
        row.setKind(r.kind().name());
        row.setNfrCategory(r.nfrCategory() == null ? "" : r.nfrCategory().name());
        // The status the EVIDENCE supports, not the stored field: a requirement whose check regressed
        // must stop claiming IMPLEMENTED, and this is the one place the row learns that.
        row.setStatus(r.statusFromEvidence() == null ? "" : r.statusFromEvidence().name());
        row.setDepth(tree.depthOf(r.id()));
        UUID parent = tree.parentOf(r.id());
        row.setParentId(parent == null ? "" : parent.toString());
        row.setOwn(dto(rollup.own(r.id())));
        row.setSubtree(dto(rollup.subtree(r.id())));
        row.setDescendants(rollup.descendants(r.id()));
        row.setStoryKeysCsv(storyKeysFor(r, storyKeysByCriterion));
        row.setUnclaimedChecks(unclaimedCount(r, claimed));
        row.setShapeWarning(shapeWarning(tree, r, all));
        row.setContextOnly(contextOnly ? 1 : 0);
        row.setRelationsCsv(relationPhrases(brd, r, all));
        return row;
    }

    /**
     * This requirement's non-hierarchy relations, phrased for a reader.
     *
     * <p><b>REFINES is deliberately absent.</b> The row is already indented under its parent; a badge
     * saying "part of R2" beside it would state the same fact twice, and two statements of one fact are
     * how they drift.
     *
     * <p><b>GATES is read from both ends and must not be inverted.</b> One edge means the NFR
     * <em>constrains</em> its target, and the target is <em>constrained by</em> the NFR. Rendering the
     * same phrase on both rows would tell the operator an ordinary requirement imposes a quality
     * constraint on a non-functional one, which is backwards and is exactly the sort of thing nobody
     * notices on screen. The direction is known here, so it is decided here.
     *
     * <p>{@code CONFLICTS_WITH} is symmetric and so reads the same on both rows — which is correct, not
     * an oversight.
     *
     * <p><b>The words themselves live on {@link RequirementRelation}</b>, not here. They were written
     * out in this method and again in the diagram's legend, and the diagram's copy said {@code
     * refines}, {@code derived} and {@code gates} — the Java constants (§25.5). One enum now answers
     * for the row, the legend, the picker and the line of history.
     *
     * <p><b>A relation to or from a retired requirement says "no longer".</b> A quality requirement
     * the operator took out of scope stops constraining anything (§25.4), and a badge still reading
     * "constrains R1" is the screen contradicting what the server now does.
     */
    private static String relationPhrases(Brd brd, BrdRequirement r, List<BrdRequirement> all) {
        List<String> phrases = new ArrayList<>();
        for (BrdEdge edge : brd.edges() == null ? List.<BrdEdge>of() : brd.edges()) {
            if (edge == null || edge.relation() == null
                    || edge.relation() == RequirementRelation.REFINES) {
                continue;
            }
            boolean outgoing = r.id() != null && r.id().equals(edge.from());
            boolean incoming = r.id() != null && r.id().equals(edge.to());
            if (!outgoing && !incoming) {
                continue;
            }
            UUID otherId = outgoing ? edge.to() : edge.from();
            String other = handleOf(all, otherId);
            String phrase = outgoing ? edge.relation().fromPhrase() : edge.relation().toPhrase();
            boolean dead = r.isRetired() || isRetired(all, otherId);
            phrases.add((dead ? "no longer " : "") + phrase + " " + other);
        }
        return String.join(";", phrases);
    }

    private static boolean isRetired(List<BrdRequirement> all, UUID id) {
        if (id == null) {
            return false;
        }
        for (BrdRequirement r : all) {
            if (r != null && id.equals(r.id())) {
                return r.isRetired();
            }
        }
        return false;
    }

    private static CheckCountsDto dto(CheckCounts counts) {
        CheckCountsDto out = new CheckCountsDto();
        out.setPassing(counts.passing());
        out.setFailing(counts.failing());
        out.setUnverified(counts.unverified());
        out.setStale(counts.stale());
        out.setProposed(counts.proposed());
        return out;
    }

    /** Distinct story keys claiming any of this requirement's checks, in first-seen order. */
    private static String storyKeysFor(BrdRequirement r,
                                       Map<UUID, List<String>> storyKeysByCriterion) {
        Set<String> keys = new LinkedHashSet<>();
        for (AcceptanceCriterion c : safe(r)) {
            List<String> forCriterion = storyKeysByCriterion.get(c.id());
            if (forCriterion != null) {
                keys.addAll(forCriterion);
            }
        }
        return String.join(",", keys);
    }

    /**
     * Accepted checks of this requirement that no story claims.
     *
     * <p>Only ACCEPTED ones count: a proposed check is a suggestion nobody has agreed to, so nothing
     * is wrong with no story having claimed it, and counting it would make "unclaimed" fire on every
     * requirement an agent has ever touched.
     */
    private static int unclaimedCount(BrdRequirement r, Set<UUID> claimed) {
        if (r.isRetired()) {
            return 0;           // out of scope: nothing about it is waiting for a story
        }
        int count = 0;
        for (AcceptanceCriterion c : safe(r)) {
            if (c.isGate() && !claimed.contains(c.id())) {
                count++;
            }
        }
        return count;
    }

    // --- matching --------------------------------------------------------------------------------

    private static boolean matches(BrdRequirement r, RequirementQuery q, RequirementTree tree,
                                   Set<UUID> claimed,
                                   Map<UUID, List<String>> storyKeysByCriterion) {
        // Tested BEFORE the unfiltered shortcut, because "no filters" still means "the requirements
        // that are in scope". A retired one is out of scope: it leaves the normal view, and the
        // count in the footer is what stops that being a disappearance (UX v3 rule 1).
        if (r.isRetired() && q.getIncludeRetired() != 1 && !names(q.getStatusCsv(), "DEPRECATED")) {
            return false;
        }
        if (q.isUnfiltered()) {
            return true;
        }
        if (!csvContains(q.getStatusCsv(),
                r.statusFromEvidence() == null ? null : r.statusFromEvidence().name())) {
            return false;
        }
        // The same three names the row carries. Filtering on a fourth answer than the one shown
        // meant asking for the functional requirements and getting the rules with them.
        if (!csvContains(q.getKindCsv(), r.kind().name())) {
            return false;
        }
        if (!csvContains(q.getNfrCategoryCsv(),
                r.nfrCategory() == null ? null : r.nfrCategory().name())) {
            return false;
        }
        if (q.getOnlyUnclaimed() == 1 && unclaimedCount(r, claimed) == 0) {
            return false;
        }
        if (q.getOnlyStale() == 1 && !hasStale(r)) {
            return false;
        }
        if (q.getOnlyShapeWarnings() == 1
                && tree.extraParentsOf(r.id()).isEmpty() && !tree.inCycle().contains(r.id())) {
            return false;
        }
        String story = q.getClaimedByStory();
        if (story != null && !story.isBlank()
                && !csvContains(storyKeysFor(r, storyKeysByCriterion), story.strip())) {
            return false;
        }
        return searchMatches(r, q.getSearch());
    }

    /**
     * Handle, title, statement and check text, case-insensitively.
     *
     * <p>Check text is included deliberately: an operator searching "email" is as likely to be thinking
     * of the check that proves it as of the requirement's own wording, and a search that missed the
     * check would look broken rather than precise.
     */
    private static boolean searchMatches(BrdRequirement r, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String needle = search.strip().toLowerCase();
        if (contains(r.handle(), needle) || contains(r.title(), needle) || contains(r.text(), needle)) {
            return true;
        }
        for (AcceptanceCriterion c : safe(r)) {
            if (contains(c.text(), needle) || contains(c.testClassOrFile(), needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStale(BrdRequirement r) {
        if (r.isRetired()) {
            return false;       // out of scope: its evidence is history, not something to redo
        }
        for (AcceptanceCriterion c : safe(r)) {
            if (c.isGate() && c.effectiveState(r.contentRevision()) == CriterionState.STALE) {
                return true;
            }
        }
        return false;
    }

    /** True only when {@code csv} explicitly names {@code value} — blank names nothing. */
    private static boolean names(String csv, String value) {
        if (csv == null || csv.isBlank()) {
            return false;
        }
        for (String part : csv.split(",")) {
            if (part.strip().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code csv} is blank (meaning "any") or names {@code value}. */
    private static boolean csvContains(String csv, String value) {
        if (csv == null || csv.isBlank()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        for (String part : csv.split(",")) {
            if (part.strip().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String haystack, String lowercaseNeedle) {
        return haystack != null && haystack.toLowerCase().contains(lowercaseNeedle);
    }

    /** This requirement's live checks — retired ones are history and match nothing. */
    private static List<AcceptanceCriterion> safe(BrdRequirement r) {
        List<AcceptanceCriterion> criteria = r.criteria();
        if (criteria == null) {
            return List.of();
        }
        List<AcceptanceCriterion> live = new ArrayList<>();
        for (AcceptanceCriterion c : criteria) {
            if (c != null && c.status() != CriterionStatus.RETIRED) {
                live.add(c);
            }
        }
        return live;
    }

    // --- shape warnings ---------------------------------------------------------------------------

    /**
     * The same sentence the graph view shows, computed here so the row and the canvas cannot word it
     * differently. Says what to DO, and never names the internal relation (UX v3 rule 3).
     */
    private static String shapeWarning(RequirementTree tree, BrdRequirement r,
                                       List<BrdRequirement> all) {
        List<UUID> extra = tree.extraParentsOf(r.id());
        if (!extra.isEmpty()) {
            StringBuilder sb = new StringBuilder("A requirement belongs in one place. ");
            sb.append(nz(r.handle())).append(" is shown under ")
                .append(handleOf(all, tree.parentOf(r.id())))
                .append(", and is also marked as part of ");
            for (int i = 0; i < extra.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(handleOf(all, extra.get(i)));
            }
            sb.append(". Remove the links you do not want.");
            return sb.toString();
        }
        if (tree.inCycle().contains(r.id())) {
            return "These requirements are marked as parts of each other, which cannot be true. "
                + nz(r.handle()) + " is shown at the top so the rest stay reachable - remove one of "
                + "the links to break the loop.";
        }
        return "";
    }

    private static String handleOf(List<BrdRequirement> all, UUID id) {
        if (id == null) {
            return "(nothing)";
        }
        for (BrdRequirement r : all) {
            if (r != null && id.equals(r.id())) {
                return nz(r.handle());
            }
        }
        return id.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
