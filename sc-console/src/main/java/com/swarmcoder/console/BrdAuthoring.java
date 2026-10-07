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
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.NfrCategory;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.TestRefOrigin;
import com.swarmcoder.domain.RequirementKind;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementTree;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.SourceRef;
import com.swarmcoder.store.ArtifactStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handle-addressable BRD mutations for the BRD-author agent. The agent speaks in R-handles
 * (R1, R2, …) and criterion refs (R7:C2) rather than UUIDs; this helper resolves them and writes
 * through the store.
 *
 * <p><b>What the agent may not do.</b> New requirements land DRAFT and new criteria land PROPOSED,
 * and the agent cannot promote either — promotion is the operator's act of accepting the requirement
 * into scope, and an agent that could promote its own extractions would make the DRAFT gate
 * decorative. Attempts return an explanation the agent can relay.
 *
 * <p>Every mutation is attributed ({@code author = "agent"}) so it appears in the BRD revision
 * history, and writes a {@code ChangeEvent} into the project's audit journal. Before 2026-07-25
 * agent edits called the one-arg {@code saveBrd}, which bumped the revision but wrote NO snapshot —
 * so agent changes were invisible in the history/restore UI.
 */
final class BrdAuthoring {

    private BrdAuthoring() {}

    static Brd brd(ArtifactStore store, UUID projectId) {
        return store.ensureBrd(projectId);
    }

    /**
     * Publishes the changed BRD into the shared {@link com.swarmcoder.console.api.BrdSignals#CURRENT}
     * signal so open Requirements editors redraw live (server-authoritative shared signal — no push
     * topic, no refresh). Best-effort; the store stays the source of truth.
     */
    static void pushChanged(Brd brd) {
        try {
            if (brd != null) {
                // A fresh copy, never the mutated canonical instance — the signal dedups by equals.
                com.swarmcoder.console.api.BrdSignals.CURRENT.set(ArtifactStore.copyOf(brd));
                // …and the cheap notification beside it, for surfaces that page rather than receive
                // the document. Both are published during the migration: the canvas editor still
                // wants a whole graph, and a paging view must not have to wait for it to stop.
                // A new instance every time, carrying the revision, so the equals-based dedup passes
                // it on exactly when something actually changed.
                com.swarmcoder.console.api.BrdSignals.VERSION.set(
                    new com.swarmcoder.console.api.BrdVersion(
                        brd.projectId() == null ? null : brd.projectId().toString(),
                        brd.revision()));
            }
        } catch (Exception ignored) {
            // publishing must never break a mutation
        }
        // The next-step bar reads the BRD, so it goes stale here and nowhere else: promoting the
        // last draft has to stop the Console asking for it, on the same publish that redraws the
        // editor. Guarded inside refresh() — a hint must never break a mutation either.
        ReadinessPublisher.refresh();
    }

    /** Saves with agent attribution, journals the change, and republishes. */
    private static void commit(ArtifactStore store, Brd brd, UUID projectId, String summary,
                               ChangeEntityType entityType, UUID entityId, ChangeKind kind) {
        store.saveBrd(brd, "agent", summary);
        try {
            store.recordChange(projectId, "agent", entityType, entityId, kind, summary);
        } catch (Exception ignored) {
            // the journal is an audit aid; never fail a mutation because it could not be written
        }
        pushChanged(brd);
    }

    /** A compact text rendering of the current graph for the agent to read. */
    static String render(Brd brd) {
        return render(brd, brd == null ? null : brd.requirements());
    }

    /**
     * The same rendering for a CHOSEN SUBSET of the graph's requirements.
     *
     * <p>Only edges whose BOTH ends are in the subset are drawn. An edge with one end outside it
     * would name a handle the reader cannot see, which reads as a dangling reference rather than as
     * "that requirement exists and is not shown".
     */
    static String render(Brd brd, List<BrdRequirement> subset) {
        if (brd == null || subset == null || subset.isEmpty()) {
            return "(the BRD is currently empty)";
        }
        Set<UUID> visible = new HashSet<>();
        for (BrdRequirement r : subset) {
            if (r != null && r.id() != null) {
                visible.add(r.id());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (BrdRequirement r : subset) {
            sb.append(r.handle()).append(" [")
                .append(switch (r.kind()) {
                    case NON_FUNCTIONAL ->
                        "NFR/" + (r.nfrCategory() == null ? "UNCATEGORISED" : r.nfrCategory());
                    // A leftover from the few hours rules were requirements. Labelled so the
                    // analyst re-reading its own BRD does not treat it as an unfinished feature.
                    case CONSTRAINT -> "LEFTOVER RULE/rules live in the guidelines";
                    case FUNCTIONAL -> "FUNCTIONAL";
                })
                .append('/').append(r.priority() == null ? "MEDIUM" : r.priority())
                .append('/').append(r.status() == null ? "DRAFT" : r.status()).append("] ")
                .append(r.title() == null ? "" : r.title());
            if (r.text() != null && !r.text().isBlank()) {
                sb.append(" — ").append(r.text());
            }
            sb.append('\n');
            List<AcceptanceCriterion> criteria = r.criteria();
            if (criteria.isEmpty()) {
                sb.append(r.isConstraint()
                    // Not a gap to be filled. A constraint with no checks is a constraint that is
                    // correct, and the analyst asking for checks on one is exactly what breaks it.
                    ? "    (a rule — it has no checks and never will; nothing delivers it)\n"
                    : "    (no criteria yet — this requirement is not yet verifiable)\n");
            } else {
                for (int i = 0; i < criteria.size(); i++) {
                    AcceptanceCriterion c = criteria.get(i);
                    CriterionState state = c.effectiveState(r.contentRevision());
                    sb.append("    ").append(r.handle()).append(":C").append(i + 1)
                        .append(" [").append(c.status()).append('/').append(state).append("] ")
                        .append(c.text() == null ? "" : c.text())
                        .append("  test=").append(c.testClassOrFile() == null ? "(none)" : c.testClassOrFile())
                        .append('\n');
                }
            }
        }
        if (brd.edges() != null && !brd.edges().isEmpty()) {
            Map<UUID, String> byId = handleById(brd);
            StringBuilder edges = new StringBuilder();
            for (BrdEdge e : brd.edges()) {
                if (!visible.contains(e.from()) || !visible.contains(e.to())) {
                    continue;
                }
                edges.append("  ").append(byId.getOrDefault(e.from(), "?"))
                    .append(' ').append(e.relation() == null ? "?" : e.relation().name().toLowerCase())
                    .append(' ').append(byId.getOrDefault(e.to(), "?")).append('\n');
            }
            if (edges.length() > 0) {
                sb.append("Edges:\n").append(edges);
            }
        }
        return sb.toString();
    }

    /** Adds a DRAFT requirement; returns its assigned handle (R1, R2, …). */
    static String addRequirement(ArtifactStore store, UUID projectId, String title, String text,
                                 String priority, String category, String kind, String nfrCategory,
                                 SourceRef sourceRef) {
        Brd brd = store.ensureBrd(projectId);
        RequirementKind parsedKind = parseKind(kind);
        if (parsedKind == RequirementKind.NON_FUNCTIONAL && parseNfrCategory(nfrCategory) == null) {
            return "error: a non-functional requirement needs a category — one of "
                + categories() + " (it decides how the requirement is verified)";
        }
        String handle = nextHandle(brd);
        BrdRequirement r = new BrdRequirement(UUID.randomUUID(), handle,
            blankToNull(title), blankToNull(text), parsePriority(priority),
            RequirementStatus.DRAFT, blankToNull(category));
        r.setKind(parsedKind);
        r.setNfrCategory(parseNfrCategory(nfrCategory));
        r.setSourceRef(sourceRef);
        r.setCriteria(new ArrayList<>());
        List<BrdRequirement> reqs = new ArrayList<>(safe(brd.requirements()));
        reqs.add(r);
        brd.setRequirements(reqs);
        commit(store, brd, projectId, "added " + handle + " (" + orEmpty(title) + ")",
            ChangeEntityType.REQUIREMENT, r.id(), ChangeKind.CREATED);
        return handle + " added as DRAFT — it still needs at least one acceptance criterion";
    }

    /** Updates fields of the requirement with the given handle; blank fields are left unchanged. */
    static String updateRequirement(ArtifactStore store, UUID projectId, String handle,
                                    String title, String text, String priority, String status) {
        Brd brd = store.ensureBrd(projectId);
        BrdRequirement r = requirementFor(brd, handle);
        if (r == null) {
            return "error: no requirement " + handle;
        }
        if (status != null && !status.isBlank()) {
            RequirementStatus requested = parseStatus(status);
            if (requested == null) {
                return "error: status must be one of draft | deprecated (active and implemented are "
                    + "set by the operator and by evidence, not by you)";
            }
            if (requested == RequirementStatus.ACTIVE || requested == RequirementStatus.IMPLEMENTED) {
                return "error: only the operator promotes a requirement to " + requested
                    + " — ask them to review and promote it in the Requirements tab";
            }
            r.setStatus(requested);
        }
        if (title != null && !title.isBlank()) {
            r.setTitle(title);
        }
        if (text != null && !text.isBlank() && !text.equals(r.text())) {
            // A material wording change invalidates evidence gathered against the old wording:
            // bumping contentRevision makes every prior pass render STALE rather than green.
            r.setText(text);
            r.setContentRevision(r.contentRevision() + 1);
        }
        if (priority != null && !priority.isBlank()) {
            r.setPriority(parsePriority(priority));
        }
        // storeDeep persists in-place edits, so the requirement is mutated rather than rebuilt —
        // rebuilding it risks silently dropping any field the constructor does not carry.
        brd.setRequirements(new ArrayList<>(safe(brd.requirements())));
        commit(store, brd, projectId, "edited " + r.handle(),
            ChangeEntityType.REQUIREMENT, r.id(), ChangeKind.UPDATED);
        return "updated " + r.handle();
    }

    /** Attaches a PROPOSED criterion to a requirement. Returns its ref (e.g. {@code R7:C2}). */
    static String addCriterion(ArtifactStore store, UUID projectId, String handle,
                               String text, String testRef) {
        Brd brd = store.ensureBrd(projectId);
        BrdRequirement r = requirementFor(brd, handle);
        if (r == null) {
            return "error: no requirement " + handle + " (use get_brd to list them)";
        }
        if (blankToNull(text) == null) {
            return "error: a criterion needs a statement of what must be true";
        }
        AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), text.trim(), blankToNull(testRef));
        // PROPOSED, not ACCEPTED: an agent-authored criterion is a suggestion until the operator
        // accepts it. Only ACCEPTED criteria gate delivery.
        c.setStatus(CriterionStatus.PROPOSED);
        // The test reference is a suggestion for exactly the same reason, and has to be marked as
        // one. This is the only path by which an agent can write a reference, so PROPOSED is true
        // by construction here; an operator's own edit goes through BrdServiceImpl and stamps
        // OPERATOR. Without the distinction the editor cannot tell a guess from a decision.
        c.setTestRefOrigin(TestRefOrigin.PROPOSED);
        c.setVerification(CriterionState.UNVERIFIED);
        List<AcceptanceCriterion> criteria = new ArrayList<>(r.criteria());
        criteria.add(c);
        r.setCriteria(criteria);
        brd.setRequirements(new ArrayList<>(safe(brd.requirements())));
        String ref = r.handle() + ":C" + criteria.size();
        commit(store, brd, projectId, "proposed check " + ref,
            ChangeEntityType.CRITERION, c.id(), ChangeKind.CREATED);
        return ref + " added as PROPOSED"
            + (c.testClassOrFile() == null
                ? " — it has no test yet, so it cannot verify anything until one is named"
                : " with a PROPOSED test reference (" + c.testClassOrFile()
                    + ") — check it before accepting the criterion");
    }

    /** Updates a criterion addressed as {@code R7:C2}. Blank fields are left unchanged. */
    static String updateCriterion(ArtifactStore store, UUID projectId, String ref,
                                  String text, String testRef, String status) {
        Brd brd = store.ensureBrd(projectId);
        String[] parts = ref == null ? new String[0] : ref.trim().split(":");
        if (parts.length != 2) {
            return "error: address a criterion as <requirement>:C<n>, e.g. R7:C2";
        }
        BrdRequirement r = requirementFor(brd, parts[0]);
        if (r == null) {
            return "error: no requirement " + parts[0];
        }
        int index = criterionIndex(parts[1]);
        List<AcceptanceCriterion> criteria = new ArrayList<>(r.criteria());
        if (index < 1 || index > criteria.size()) {
            return "error: " + r.handle() + " has " + criteria.size() + " criteria";
        }
        AcceptanceCriterion c = criteria.get(index - 1);
        if (status != null && !status.isBlank()) {
            CriterionStatus requested = parseCriterionStatus(status);
            if (requested == CriterionStatus.ACCEPTED) {
                return "error: only the operator accepts a criterion — until then it stays PROPOSED "
                    + "and does not gate delivery";
            }
            if (requested == null) {
                return "error: status must be proposed | retired";
            }
            c.setStatus(requested);
        }
        if (text != null && !text.isBlank()) {
            c.setText(text.trim());
        }
        if (testRef != null && !testRef.isBlank()) {
            c.setTestClassOrFile(testRef.trim());
            // Still an agent writing it, so still a proposal. The editor keeps saying nobody has
            // checked this reference until the operator confirms it there.
            c.setTestRefOrigin(TestRefOrigin.PROPOSED);
        }
        r.setCriteria(criteria);
        brd.setRequirements(new ArrayList<>(safe(brd.requirements())));
        commit(store, brd, projectId, "edited check " + ref,
            ChangeEntityType.CRITERION, c.id(), ChangeKind.UPDATED);
        return "updated " + ref;
    }

    /** Adds a typed edge between two requirements addressed by handle. */
    static String addEdge(ArtifactStore store, UUID projectId,
                          String fromHandle, String relation, String toHandle) {
        Brd brd = store.ensureBrd(projectId);
        UUID from = idForHandle(brd, fromHandle);
        UUID to = idForHandle(brd, toHandle);
        if (from == null || to == null) {
            return "error: unknown handle(s) — both endpoints must exist (use get_brd to list them)";
        }
        if (from.equals(to)) {
            return "error: an edge cannot connect a requirement to itself";
        }
        RequirementRelation rel = parseRelation(relation);
        if (rel == null) {
            return "error: relation must be depends_on | refines | conflicts_with | derived_from | gates";
        }
        // Every shape rule lives in RequirementTree so this path and the operator's saveEdge cannot
        // drift apart — they already had: the GATES-source check used to be here and nowhere else.
        String rejection = RequirementTree.rejectionFor(brd, from, to, rel);
        if (rejection != null) {
            return rejection;
        }
        List<BrdEdge> edges = new ArrayList<>();
        for (BrdEdge e : safe(brd.edges())) {
            if (!(from.equals(e.from()) && to.equals(e.to()) && rel.equals(e.relation()))) {
                edges.add(e);
            }
        }
        edges.add(new BrdEdge(from, to, rel));
        brd.setEdges(edges);
        // Two different readers, two different sentences. The JOURNAL is read by a person in the
        // history panel, so it gets the operator's phrase (25.5 - it used to read "added edge R6
        // refines R1"). The RETURN VALUE is read by the authoring agent, which speaks in relation
        // names and has to be able to echo back what it just did.
        String summary = fromHandle + " " + rel.name().toLowerCase() + " " + toHandle;
        commit(store, brd, projectId,
            "linked " + fromHandle + ": now " + rel.fromPhrase() + " " + toHandle,
            ChangeEntityType.REQUIREMENT, from, ChangeKind.LINKED);
        return summary + " added";
    }

    // --- helpers -------------------------------------------------------------------------------

    private static Map<UUID, String> handleById(Brd brd) {
        Map<UUID, String> m = new HashMap<>();
        for (BrdRequirement r : safe(brd.requirements())) {
            m.put(r.id(), r.handle());
        }
        return m;
    }

    /** The LIVE requirement instance (edits are made in place — see {@code ArtifactStore.storeDeep}). */
    private static BrdRequirement requirementFor(Brd brd, String handle) {
        if (handle == null) {
            return null;
        }
        for (BrdRequirement r : safe(brd.requirements())) {
            if (r.handle() != null && r.handle().equalsIgnoreCase(handle.trim())) {
                return r;
            }
        }
        return null;
    }

    private static UUID idForHandle(Brd brd, String handle) {
        BrdRequirement r = requirementFor(brd, handle);
        return r == null ? null : r.id();
    }

    /**
     * Whether a handle names a requirement the BRD currently holds.
     *
     * <p>Package-visible for {@link RequirementsIntake#apply}, which needs to tell "this edge's
     * endpoint plain does not exist" (harness run 47, 2026-09-27 — an analyst proposal named a
     * handle nothing in its own batch created) from a genuine shape violation, so it can skip the
     * former instead of failing the whole apply. {@link #addEdge}'s own refusal for a direct
     * operator or agent call is unchanged; only intake's leniency uses this.
     */
    static boolean handleExists(Brd brd, String handle) {
        return idForHandle(brd, handle) != null;
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

    private static String nextHandle(Brd brd) {
        int max = 0;
        for (BrdRequirement r : safe(brd.requirements())) {
            String h = r.handle();
            if (h != null && h.matches("[Rr]\\d+")) {
                max = Math.max(max, Integer.parseInt(h.substring(1)));
            }
        }
        return "R" + (max + 1);
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String categories() {
        StringBuilder sb = new StringBuilder();
        for (NfrCategory c : NfrCategory.values()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(c.name().toLowerCase());
        }
        return sb.toString();
    }

    private static Priority parsePriority(String s) {
        if (s == null || s.isBlank()) {
            return Priority.MEDIUM;
        }
        try {
            return Priority.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Priority.MEDIUM;
        }
    }

    private static RequirementKind parseKind(String s) {
        if (s == null || s.isBlank()) {
            return RequirementKind.FUNCTIONAL;
        }
        String normalized = s.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        if (normalized.equals("NFR") || normalized.equals("NON_FUNCTIONAL")) {
            return RequirementKind.NON_FUNCTIONAL;
        }
        return RequirementKind.FUNCTIONAL;
    }

    private static NfrCategory parseNfrCategory(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return NfrCategory.valueOf(s.trim().toUpperCase().replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Null on anything unrecognised — the previous silent fallback to DRAFT meant a typo quietly
     * reset a requirement's status instead of telling the agent it got the argument wrong.
     */
    private static RequirementStatus parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return RequirementStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static CriterionStatus parseCriterionStatus(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return CriterionStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static RequirementRelation parseRelation(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return RequirementRelation.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
