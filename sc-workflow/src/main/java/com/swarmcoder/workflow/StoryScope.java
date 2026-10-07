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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.Story;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a run is actually allowed to build: the requirements a story delivers, the exact criteria it
 * slices out of them, and the non-functional requirements it inherits as gates.
 *
 * <p>This is the boundary that stops the Architect inventing requirements. Everything it is given
 * comes from the BRD, and everything it produces must trace back to something here.
 *
 * <p>Resolved from the requirement graph, not stored: gate inheritance follows {@code GATES} edges
 * up the {@code REFINES} hierarchy, so an NFR attached to "Checkout" applies to every requirement
 * refining it without being restated on each one. Recomputing means a gate added today applies to
 * the next run of an old story, which is the behaviour a living document needs.
 */
public record StoryScope(
    Story story,
    /** Requirements the sliced criteria belong to, in graph order. */
    List<BrdRequirement> requirements,
    /** The exact criteria this story delivers. */
    List<AcceptanceCriterion> criteria,
    /** Human-readable refs (R7:C1) parallel to {@link #criteria}. */
    List<String> criterionRefs,
    /** Non-functional requirements gating this story, inherited through REFINES. */
    List<BrdRequirement> gatingNfrs,
    /**
     * The project's standing rules, already rendered — how it must be built, and every lesson it
     * has learned (author decision 2026-08-31).
     *
     * <p>Not resolved from the story and not inherited through any edge: EVERY rule applies to
     * EVERY story, which is what makes it a rule rather than a requirement. Nothing here is ever
     * delivered, nothing here contributes a criterion, and no plan is rejected for failing to
     * cover one. They exist so that the architect, the test author and the workers are told what
     * this project is made of instead of assuming the commonest stack.
     *
     * <p>Handed in as TEXT rather than resolved here, because rules are guidelines and guidelines
     * live in the project's checkout — not in the BRD this class reads. One rendering
     * ({@code ConstraintBrief}) reaches the architect, the test author and every worker, so they
     * cannot be told the same rule in three different words.
     */
    String projectRules
) {

    /** An empty scope — an ad-hoc run that answers to no requirement yet. */
    public static StoryScope empty(Story story) {
        return new StoryScope(story, List.of(), List.of(), List.of(), List.of(), "");
    }

    /** The rules briefing every agent on this run is shown; empty when the project has none. */
    public String constraintBrief() {
        return projectRules == null ? "" : projectRules;
    }

    public boolean isEmpty() {
        return criteria.isEmpty();
    }

    /** The ref for a criterion id, or null when it is outside this scope. */
    public String refFor(UUID criterionId) {
        for (int i = 0; i < criteria.size(); i++) {
            if (criteria.get(i).id().equals(criterionId)) {
                return criterionRefs.get(i);
            }
        }
        return null;
    }

    /** The criterion id for a ref like {@code R7:C1}, or null when it is outside this scope. */
    public UUID idForRef(String ref) {
        if (ref == null) {
            return null;
        }
        String wanted = ref.trim().toUpperCase();
        for (int i = 0; i < criterionRefs.size(); i++) {
            if (criterionRefs.get(i).equalsIgnoreCase(wanted)) {
                return criteria.get(i).id();
            }
        }
        return null;
    }

    /** The requirement owning a criterion, or null. */
    public BrdRequirement requirementOf(UUID criterionId) {
        for (BrdRequirement r : requirements) {
            for (AcceptanceCriterion c : r.criteria()) {
                if (c.id().equals(criterionId)) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * Resolves a story against the project's BRD.
     *
     * <p>Only ACCEPTED criteria of AGREED requirements are included. A PROPOSED criterion is a
     * suggestion nobody has agreed to yet, and a draft or retired requirement is not in scope at
     * all, so building against either — or gating delivery on it — would let an agent's proposal
     * become work without anyone deciding.
     */
    public static StoryScope resolve(Brd brd, Story story) {
        return resolve(brd, story, "");
    }

    /**
     * @param projectRules the project's standing rules, already rendered — see {@link #projectRules}.
     *                     Kept even when the story slices nothing: a run with no criteria still
     *                     writes code, and code written against the wrong stack is the whole defect.
     */
    public static StoryScope resolve(Brd brd, Story story, String projectRules) {
        String rules = projectRules == null ? "" : projectRules;
        if (brd == null || story == null) {
            return new StoryScope(story, List.of(), List.of(), List.of(), List.of(), rules);
        }
        Set<UUID> wanted = new LinkedHashSet<>(story.criterionIds());
        if (wanted.isEmpty()) {
            return new StoryScope(story, List.of(), List.of(), List.of(), List.of(), rules);
        }

        List<BrdRequirement> requirements = new ArrayList<>();
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        for (BrdRequirement r : brd.requirements()) {
            // A requirement the operator has not AGREED is out of scope, so a run may not build it
            // even where a story still names its checks. That covers both ends: a retired
            // requirement, taken back out of scope, and a draft one that was never in it. The claim
            // itself is kept (that is the record of what the story was for); what is gone is its
            // power to send anyone to write code.
            //
            // The draft half is defence in depth. The backlog now refuses to write a story on an
            // unagreed requirement at all (BacklogAuthoring), so nothing should reach here — but
            // stores written before that gate existed hold stories that did, and a run must not be
            // the place the rule first gets tested.
            if (!r.isAgreed()) {
                continue;
            }
            List<AcceptanceCriterion> owned = r.criteria();
            boolean contributes = false;
            for (int i = 0; i < owned.size(); i++) {
                AcceptanceCriterion c = owned.get(i);
                if (wanted.contains(c.id()) && c.status() != CriterionStatus.RETIRED
                        && c.status() == CriterionStatus.ACCEPTED) {
                    criteria.add(c);
                    refs.add(r.handle() + ":C" + (i + 1));
                    contributes = true;
                }
            }
            if (contributes) {
                requirements.add(r);
            }
        }

        return new StoryScope(story, List.copyOf(requirements), List.copyOf(criteria),
            List.copyOf(refs), gatesFor(brd, requirements), rules);
    }

    /**
     * The NFRs gating a set of requirements: any non-functional requirement with a {@code GATES}
     * edge to one of them, or to any of their {@code REFINES}-ancestors.
     *
     * <p><b>A retired quality requirement gates nothing</b> (UX v3 §2.6, and
     * {@code docs/DEVELOPER_CORRECTIONS.md} §25.4). There was no status test here at all, so a
     * quality bar the operator had explicitly taken out of scope went on constraining every
     * requirement under it, for every run, for ever — and the rows on screen went on reading
     * "constrains R1" with nothing saying otherwise. Asked through
     * {@link BrdRequirement#isRetired()} rather than by testing the status here, so this and the
     * coverage figures cannot come to different views of what "out of scope" means.
     */
    private static List<BrdRequirement> gatesFor(Brd brd, List<BrdRequirement> requirements) {
        if (requirements.isEmpty()) {
            return List.of();
        }
        Map<UUID, BrdRequirement> byId = new LinkedHashMap<>();
        for (BrdRequirement r : brd.requirements()) {
            byId.put(r.id(), r);
        }

        // Everything the story's requirements answer to, including themselves.
        Set<UUID> covered = new LinkedHashSet<>();
        for (BrdRequirement r : requirements) {
            covered.add(r.id());
            collectAncestors(brd, r.id(), covered);
        }

        List<BrdRequirement> gates = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (BrdEdge edge : safeEdges(brd)) {
            if (edge.relation() != RequirementRelation.GATES || !covered.contains(edge.to())) {
                continue;
            }
            BrdRequirement nfr = byId.get(edge.from());
            // A functional requirement cannot gate; the editor refuses to create such an edge, but
            // an older store or a hand-edit could contain one, and silently honouring it would
            // apply a gate nobody could satisfy.
            if (nfr != null && nfr.isNonFunctional() && !nfr.isRetired() && seen.add(nfr.id())) {
                gates.add(nfr);
            }
        }
        return List.copyOf(gates);
    }

    /** Walks REFINES edges upward (source refines target), guarding against cycles. */
    private static void collectAncestors(Brd brd, UUID id, Set<UUID> into) {
        for (BrdEdge edge : safeEdges(brd)) {
            if (edge.relation() == RequirementRelation.REFINES && id.equals(edge.from())
                    && edge.to() != null && into.add(edge.to())) {
                collectAncestors(brd, edge.to(), into);
            }
        }
    }

    private static List<BrdEdge> safeEdges(Brd brd) {
        return brd.edges() == null ? List.of() : brd.edges();
    }
}
