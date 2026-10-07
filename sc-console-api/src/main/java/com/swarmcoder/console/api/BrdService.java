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
package com.swarmcoder.console.api;

import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.BrdRevision;
import com.zeroz4j.api.RmiService;
import com.zeroz4j.api.Secured;

/**
 * The per-project Business Requirements Document editor (author decision 2026-07-24). The BRD is a
 * persisted network graph of requirement nodes and typed edges, exactly one per project; this
 * service reads and edits the CURRENT project's BRD, carrying the domain objects
 * ({@link Brd}/{@link BrdRequirement}/{@link BrdEdge}) directly over the wire — no DTOs. Mutations
 * return "" on success or an "error: …" string, and the fresh graph is published on the
 * {@link BrdSignals#CURRENT} signal (the editor binds to that, not to these return values).
 */
@RmiService
@Secured
public interface BrdService {

    /** The current project's BRD graph (created empty on first access). */
    Brd brd();

    /**
     * A window onto the requirements tree matching {@code query}, in tree order — parents before their
     * parts (see {@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §3.6/§5).
     *
     * <p>This is the surface a paging view uses instead of {@link #brd()}. It returns ROWS: enough to
     * draw a line, with the statement text, criteria and history fetched only when a row is opened. The
     * point is that the cost of reading the requirements stops scaling with how many there are.
     *
     * <p>Matches arrive with their ancestors, marked {@code contextOnly}, so a filter can never orphan a
     * result or hide the shape it sits in; {@link RequirementPageDto} carries the three counts that keep
     * that honest.
     *
     * @param query  what to look for; an empty query returns the whole tree
     * @param offset where to start in the matched-plus-context sequence
     * @param max    how many rows to return; clamped to a sane ceiling by the server
     */
    RequirementPageDto rows(RequirementQuery query, int offset, int max);

    /**
     * Creates (null/blank id) or updates a requirement node. The server assigns a handle
     * (R1, R2, …) to new nodes when none is given. Returns "" or "error: …".
     *
     * <p>Materially rewording a requirement that a story is already building SENDS THAT STORY BACK
     * (UX v3 §2.2 — "in-flight work is never churned silently"). Ask {@link #editImpact} first and
     * show the operator the sentence: the change is theirs to make, but not to make unknowingly.
     */
    String saveRequirement(BrdRequirement requirement);

    /**
     * Creates a requirement and, when {@code parentId} names one, makes the new requirement part of
     * it — in one save, so the tree never briefly holds a top-level requirement nobody asked for.
     *
     * <p>Returns "" or "error: …". This is what "Add a part" on a row does.
     */
    String addRequirement(BrdRequirement requirement, String parentId);

    /** One requirement in full — statement, checks, provenance — or null. What opening a row fetches. */
    BrdRequirement requirement(String requirementId);

    /**
     * Takes a requirement OUT OF SCOPE. It is never destroyed (author decision 2026-08-28,
     * {@code docs/DEVELOPER_CORRECTIONS.md} §25.1).
     *
     * <p>The requirement keeps its checks, its links, its history and every piece of evidence
     * gathered against it, and stops counting anywhere: no coverage figure, no gate, no build. It
     * leaves the list unless the operator asks to see retired ones, and setting its status back to a
     * draft brings it back. Returns "" or "error: …".
     */
    String retireRequirement(String requirementId);

    /**
     * What editing this requirement would disturb, in one sentence for the operator, or "" when
     * nothing downstream would notice. Shown before the edit is committed to, never after.
     */
    String editImpact(String requirementId);

    /** What retiring this requirement would do, in one sentence for the operator. Never blank. */
    String retireImpact(String requirementId);

    /**
     * Promotes one DRAFT requirement to ACTIVE and ACCEPTS every criterion it carries.
     *
     * <p>The requirement is the unit of decision, not the criterion. Promoting one is the operator
     * saying "this is what must be true" — and its criteria ARE what must be true, so asking them
     * to confirm each one separately is the same decision put twice. At a hundred criteria that
     * stops being a safeguard and becomes an obstacle people clear by accepting everything without
     * reading, which is worse than not asking. An individual criterion can still be set back to
     * PROPOSED afterwards.
     */
    String promoteRequirement(String requirementId);

    /**
     * Promotes every DRAFT requirement at once, criteria included. Returns a summary of what moved,
     * or "error: …". This is the step straight after applying an intake, where every requirement is
     * a fresh draft the operator has just read in the review list.
     */
    String promoteAllDrafts();

    // --- canvas layout (where the operator put things) ------------------------------------------

    /**
     * Records where the operator dragged one requirement node to, in the canvas's content
     * coordinates (top-left of the box). One call per completed gesture, never per pointer move.
     *
     * <p>A node with a recorded position is HAND-PLACED and the automatic layout never touches it
     * again — which is why an unknown requirement id is refused rather than stored: an orphan entry
     * would be a position nothing can ever move, correct or clear.
     *
     * <p>Returns "" or "error: …".
     */
    String saveNodePosition(String requirementId, double x, double y);

    /**
     * Forgets every hand-placed position, handing the whole graph back to the automatic layout.
     *
     * <p>This is the way out of a bad drag session, and the reason dragging is safe to offer at all:
     * without it one careless afternoon would be permanent. Returns "" or "error: …".
     */
    String clearNodePositions();

    /** Adds (or replaces the relation of) a directed edge between two requirement nodes. */
    String saveEdge(BrdEdge edge);

    /** Removes the edge matching from+to+relation. Returns "" or "error: …". */
    String deleteEdge(BrdEdge edge);

    // --- criteria (the requirement's executable definition) -------------------------------------

    /**
     * Creates (null/blank id) or updates one of a requirement's acceptance criteria — its fitness
     * criteria when the requirement is non-functional.
     *
     * <p>Unlike the authoring agent, the operator MAY set {@code ACCEPTED}: accepting a criterion is
     * exactly the act of deciding it now gates delivery. Doing so can move the requirement's status,
     * because a requirement is only IMPLEMENTED while every accepted criterion passes.
     */
    String saveCriterion(String requirementId, com.swarmcoder.domain.AcceptanceCriterion criterion);

    /**
     * Takes one check out of the gate. It is never destroyed, for the same reason a requirement is
     * not: a check is the thing a commit and a test were tied to, and removing it severs that.
     *
     * <p>Returns "" on a silent success, "error: …" on a refusal, or a plain sentence when the
     * operator needs to know something — a story was delivering this check and now has one fewer,
     * or has nothing left to deliver at all.
     */
    String retireCriterion(String requirementId, String criterionId);

    /** The uploaded documents this project's requirements can cite as provenance. */
    java.util.List<com.swarmcoder.domain.SourceDocument> sources();

    // --- versioning (living-document history) --------------------------------------------------

    /** The current project's BRD revision history, newest first. */
    java.util.List<BrdRevision> history();

    /** The BRD graph as it was at a given revision (for read-only preview), or an empty graph. */
    Brd revisionAt(long revision);

    /**
     * What restoring this revision would change, in words for the operator, before they do it.
     *
     * <p>Restoring is the one action taken BECAUSE the operator wants the old document back, and it
     * takes everything written since out of the live document. That is recoverable — history is only
     * appended to — but until now nothing said either half out loud.
     */
    String restoreSummary(long revision);

    /**
     * Restores the BRD to a past revision's snapshot. This never rewrites history — it appends a
     * new "restore" revision whose content equals the chosen snapshot. Returns "" or "error: …".
     */
    String restore(long revision);
}
