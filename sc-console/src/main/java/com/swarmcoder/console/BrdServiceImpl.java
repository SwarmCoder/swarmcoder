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

import com.swarmcoder.console.api.BrdService;
import com.swarmcoder.domain.AgreementGate;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdNodePosition;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.BrdRevision;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementImpact;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.RequirementTree;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Serves and edits the CURRENT project's BRD graph over RMI, carrying the domain
 * {@link Brd}/{@link BrdRequirement}/{@link BrdEdge}/{@link BrdRevision} objects directly on the
 * wire (@DataModel) — no DTOs. The store is the source of truth; every mutation replaces the
 * requirements/edges LISTS with fresh instances (never mutates elements in place) so EclipseStore's
 * lazy storer captures the change, and publishes a fresh copy onto {@code BrdSignals.CURRENT} so
 * open editors redraw live.
 *
 * <p>{@code @ApplicationScoped} so zeroz4j's CDI scan discovers and registers it as an
 * {@code @RmiService}.
 */
@ApplicationScoped
public class BrdServiceImpl implements BrdService {

    @Override
    public Brd brd() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context.currentProjectId();
        if (projectId == null) {
            return new Brd(null, null, 0, "Business Requirements", new ArrayList<>(), new ArrayList<>(), null, null);
        }
        Brd brd = context.store().ensureBrd(projectId);
        // Publish so a freshly opened editor gets the current graph via signal retention.
        BrdAuthoring.pushChanged(brd);
        return brd;
    }

    @Override
    public com.swarmcoder.console.api.RequirementPageDto rows(
            com.swarmcoder.console.api.RequirementQuery query, int offset, int max) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return new com.swarmcoder.console.api.RequirementPageDto();
            }
            // Read-only: no ensureBrd side effect and, unlike brd(), no publish. A query must not
            // broadcast to every other client just because somebody typed in a search box.
            Brd brd = context.store().ensureBrd(projectId);
            return RequirementRows.page(brd, context.store().listStories(projectId),
                query, offset, max);
        } catch (Exception e) {
            // A failed query returns an empty page rather than throwing across the wire: the caller is
            // a search box, and the alternative is a dead panel with nothing said.
            return new com.swarmcoder.console.api.RequirementPageDto();
        }
    }

    @Override
    public String saveRequirement(BrdRequirement incoming) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            Brd brd = context.store().ensureBrd(projectId);

            UUID id = incoming.id() == null ? UUID.randomUUID() : incoming.id();
            String handle = incoming.handle() == null || incoming.handle().isBlank()
                ? nextHandle(brd) : incoming.handle().trim();
            BrdRequirement existing = requirementById(brd, id.toString());

            // Edit the EXISTING node rather than constructing a replacement. The constructor covers
            // seven of the twelve fields, so rebuilding from the form silently destroyed the other
            // five — every acceptance criterion, the NFR category, the source document it came
            // from, and the content revision that decides whether prior test evidence is stale.
            // Renaming a requirement should not delete what proves it.
            BrdRequirement req = existing != null ? existing
                : new BrdRequirement(id, handle, null, null, Priority.MEDIUM,
                    RequirementStatus.DRAFT, null);
            boolean replaced = existing != null;

            RequirementStatus wasStatus = req.status();
            String wasText = req.text();
            // The gate (§20.2), asked BEFORE anything is written. The requirement in hand is the
            // stored one, mutated in place, so a refusal returned after the setters had run would
            // leave the in-memory graph carrying an edit that was never saved.
            if (wasStatus == RequirementStatus.DRAFT
                    && incoming.status() == RequirementStatus.ACTIVE) {
                // …and it answers to the same gate as the Agree button, for the same reason: the
                // act is what matters, not the control it was performed with.
                String refusal = AgreementGate.rejectionFor(req);
                if (refusal != null) {
                    return "error: " + refusal;
                }
            }
            req.setHandle(handle);
            req.setTitle(incoming.title());
            req.setText(incoming.text());
            req.setPriority(incoming.priority() == null ? Priority.MEDIUM : incoming.priority());
            req.setStatus(incoming.status() == null ? RequirementStatus.DRAFT : incoming.status());
            // Preserved when the form does not send one, exactly as kind and nfrCategory are below.
            // The editor no longer offers a Category field — it was a SECOND way to express containment
            // beside the REFINES hierarchy, and its own placeholder said "category / epic", which is a
            // noun UX v3's concept budget does not have (REQUIREMENTS_AT_SCALE_DESIGN §3.5). The data
            // stays: extraction fills it from document headings and that is real provenance. Setting it
            // unconditionally would mean removing the field silently wiped every stored value on the
            // first save — the same defect the comment above this block already describes.
            if (incoming.category() != null && !incoming.category().isBlank()) {
                req.setCategory(incoming.category());
            }
            if (incoming.kind() != null) {
                req.setKind(incoming.kind());
            }
            if (incoming.nfrCategory() != null) {
                req.setNfrCategory(incoming.nfrCategory());
            }
            if (req.criteria() == null) {
                req.setCriteria(new ArrayList<>());
            }
            if (replaced && incoming.text() != null && !incoming.text().equals(wasText)) {
                // A material wording change invalidates evidence gathered against the old wording:
                // bumping this makes every prior pass render STALE rather than green.
                req.setContentRevision(req.contentRevision() + 1);
            }
            // Promoting through the status dropdown must carry the criteria with it, exactly as the
            // Promote button does — otherwise the same act means two different things depending on
            // which control the operator happened to use.
            if (wasStatus == RequirementStatus.DRAFT
                    && req.status() == RequirementStatus.ACTIVE) {
                promote(req);
            }

            List<BrdRequirement> reqs = new ArrayList<>(safe(brd.requirements()));
            if (!replaced) {
                reqs.add(req);
            }
            brd.setRequirements(reqs);
            String summary = (replaced ? "edited " : "added ") + handle
                + (req.title() == null ? "" : " (" + req.title() + ")");
            context.store().saveBrd(brd, "human", summary);
            // In-flight work is never churned SILENTLY (UX v3 2.2): rewording a requirement a story
            // is building stops that story, because what it was told to build is no longer what the
            // document says. Rewording used to do nothing here - the evidence correctly went stale
            // and the story card did not change at all, still reading "Agreed work. Nothing is
            // building it yet." (25.3). The operator sees the same sentence BEFORE they confirm,
            // from RequirementImpact, which is also what decides this.
            if (replaced && incoming.text() != null && !incoming.text().equals(wasText)) {
                sendClaimingStoriesBack(context.store(), projectId, req);
            }
            BrdAuthoring.pushChanged(brd);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Takes a requirement out of scope. It is NOT removed - author decision 2026-08-28, 25.1.
     *
     * <p>What this used to do: drop the requirement from the list, drop every edge touching it, and
     * drop its hand-placed position. That severed the trail from the requirement to the commit and
     * the test that satisfied it, silently, leaving delivered work with nothing on record saying why
     * it was built - and that trail is what this product is for.
     *
     * <p>So everything stays and the status becomes retired. {@link BrdRequirement#isRetired()}
     * carries the consequences from one place: no coverage figure counts it, it gates nothing, no
     * run may build it, and it leaves the list unless the operator asks for retired ones. Setting
     * its status back to a draft in the editor brings it back, which is why there is no separate
     * "bring it back" control to explain.
     */
    @Override
    public String retireRequirement(String requirementId) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            Brd brd = context.store().ensureBrd(projectId);
            BrdRequirement requirement = requirementById(brd, requirementId);
            if (requirement == null) {
                return "error: unknown requirement";
            }
            if (requirement.isRetired()) {
                return "error: " + requirement.handle() + " is already out of scope";
            }
            requirement.setStatus(RequirementStatus.DEPRECATED);
            brd.setRequirements(new ArrayList<>(safe(brd.requirements())));
            context.store().saveBrd(brd, "human", "retired " + requirement.handle());
            try {
                context.store().recordChange(projectId, "human", ChangeEntityType.REQUIREMENT,
                    requirement.id(), ChangeKind.TOMBSTONED,
                    "took " + requirement.handle() + " out of scope");
            } catch (Exception ignored) {
                // the journal is an audit aid; never fail a mutation because it could not be written
            }
            sendClaimingStoriesBack(context.store(), projectId, requirement);
            BrdAuthoring.pushChanged(brd);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    @Override
    public String addRequirement(BrdRequirement incoming, String parentId) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            if (incoming == null) {
                return "error: nothing to add";
            }
            // A NEW requirement, always: an id arriving here would silently turn "add a part" into
            // an edit of whatever it named.
            incoming.setId(UUID.randomUUID());
            String saved = saveRequirement(incoming);
            if (!saved.isEmpty()) {
                return saved;
            }
            if (parentId == null || parentId.isBlank()) {
                return "";
            }
            UUID parent = parseUuid(parentId);
            if (parent == null) {
                return "";
            }
            // The link is a second save, so the operator keeps the requirement even if the link is
            // refused - a rejected shape must never cost them the text they just typed.
            String linked = saveEdge(new BrdEdge(incoming.id(), parent,
                com.swarmcoder.domain.RequirementRelation.REFINES));
            return linked.startsWith("error") ? linked : "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * One requirement in full, as a COPY.
     *
     * <p>This is what opening a row fetches. The row projection deliberately carries no statement
     * text and no checks, so an editor over the tree needs exactly this and nothing like the whole
     * document - which is the cost the tree exists to avoid.
     *
     * <p>A copy because the instance held here is the live, stored one: handing it to a browser
     * would let a client-side edit reach the store without anybody saving anything.
     */
    @Override
    public BrdRequirement requirement(String requirementId) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return null;
            }
            BrdRequirement found =
                requirementById(context.store().ensureBrd(projectId), requirementId);
            return found == null ? null : ArtifactStore.copyOf(found);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String editImpact(String requirementId) {
        return impact(requirementId, true);
    }

    @Override
    public String retireImpact(String requirementId) {
        return impact(requirementId, false);
    }

    /** One derivation, asked two ways - see {@link RequirementImpact}. */
    private static String impact(String requirementId, boolean editing) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "";
            }
            BrdRequirement requirement =
                requirementById(context.store().ensureBrd(projectId), requirementId);
            if (requirement == null) {
                return "";
            }
            RequirementImpact impact =
                RequirementImpact.of(requirement, context.store().listStories(projectId));
            return editing ? impact.editSentence() : impact.retireSentence();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Moves every in-flight story claiming this requirement's checks back to ready.
     *
     * <p>Which stories those are is {@link RequirementImpact}'s answer, so the sentence the operator
     * confirmed and the cards that actually move cannot disagree. Best-effort: a story that will not
     * save must not cost the requirement edit, which is the thing the operator asked for.
     */
    private static void sendClaimingStoriesBack(ArtifactStore store, UUID projectId,
                                                BrdRequirement requirement) {
        try {
            List<Story> stories = store.listStories(projectId);
            List<String> keys = RequirementImpact.of(requirement, stories).sentBackKeys();
            if (keys.isEmpty()) {
                return;
            }
            for (Story story : stories) {
                if (story == null || story.key() == null || !keys.contains(story.key())) {
                    continue;
                }
                StoryState was = story.state();
                story.setState(StoryState.READY);
                // Cleared for the same reason a retry clears them: they describe an attempt against
                // wording that no longer exists, and leaving them would let the next acceptance
                // stamp checks as verified against a commit nobody accepted.
                story.setDeliveredCommit(null);
                story.setIntegrationCommit(null);
                store.saveStory(story);
                store.recordChange(projectId, "human", ChangeEntityType.STORY, story.id(),
                    ChangeKind.STATE_CHANGED, "state", String.valueOf(was), "READY",
                    "sent " + story.key() + " back: " + requirement.handle()
                        + " changed while it was being built", null);
            }
            BacklogPublisher.publish(store, projectId);
        } catch (Exception ignored) {
            // the edit is the operator's act and must not fail because a card did not move
        }
    }

    @Override
    public String promoteRequirement(String requirementId) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            Brd brd = context.store().ensureBrd(projectId);
            BrdRequirement requirement = requirementById(brd, requirementId);
            if (requirement == null) {
                return "error: unknown requirement";
            }
            if (requirement.status() == RequirementStatus.ACTIVE
                    || requirement.status() == RequirementStatus.IMPLEMENTED) {
                return "error: " + requirement.handle() + " is already "
                    + requirement.status().label();
            }
            // The gate (DEVELOPER_CORRECTIONS.md §20.2). Nothing becomes agreed scope without a
            // check that names a test which could prove it. Refused here rather than warned about
            // afterwards: the red "unprovable" badge the graph already draws arrives after the
            // decision it should have prevented.
            String refusal = AgreementGate.rejectionFor(requirement);
            if (refusal != null) {
                return "error: " + refusal;
            }
            int accepted = promote(requirement);
            brd.setRequirements(new ArrayList<>(safe(brd.requirements())));
            context.store().saveBrd(brd, "human", "agreed " + requirement.handle()
                + " and accepted " + accepted + (accepted == 1 ? " check" : " checks"));
            BrdAuthoring.pushChanged(brd);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    @Override
    public String promoteAllDrafts() {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            Brd brd = context.store().ensureBrd(projectId);
            int requirements = 0;
            int criteria = 0;
            int drafts = 0;
            // Held back by the gate (§20.2), named rather than skipped. Silently agreeing nine of
            // ten and saying nothing about the tenth would leave the operator believing they had
            // agreed everything they just read.
            List<String> held = new ArrayList<>();
            for (BrdRequirement requirement : safe(brd.requirements())) {
                if (requirement.status() != RequirementStatus.DRAFT) {
                    continue;
                }
                drafts++;
                String refusal = AgreementGate.rejectionFor(requirement);
                if (refusal != null) {
                    held.add(refusal);
                    continue;
                }
                criteria += promote(requirement);
                requirements++;
            }
            if (drafts == 0) {
                return "error: there are no drafts left to agree";
            }
            if (requirements == 0) {
                // Every draft is unprovable. Nothing to save, and the operator needs the reasons,
                // not a bare refusal.
                return "error: none of these can be agreed yet. " + String.join(" ", held);
            }
            brd.setRequirements(new ArrayList<>(safe(brd.requirements())));
            context.store().saveBrd(brd, "human", "agreed " + requirements
                + " requirements and accepted " + criteria
                + (criteria == 1 ? " check" : " checks"));
            BrdAuthoring.pushChanged(brd);
            if (!held.isEmpty()) {
                return "Agreed " + requirements + " of " + drafts + ". " + held.size()
                    + (held.size() == 1 ? " was" : " were") + " left as a draft: "
                    + String.join(" ", held);
            }
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Moves one requirement to ACTIVE and accepts its criteria, returning how many it accepted.
     *
     * <p>Criteria that were already ACCEPTED are left alone, and so is anything RETIRED — promoting
     * a requirement must not resurrect a criterion the operator deliberately took out of the gate.
     */
    /**
     * Agrees one requirement and accepts every check it still proposes.
     *
     * <p>Package-private rather than private so unattended running agrees requirements through the
     * SAME derivation an operator does. A second copy of this would be two definitions of what
     * "agreed" means, and the first time they drifted the difference would only show up in what
     * the pipeline was allowed to build.
     */
    static int promote(BrdRequirement requirement) {
        int accepted = 0;
        for (com.swarmcoder.domain.AcceptanceCriterion criterion : requirement.criteria()) {
            if (criterion.status() == com.swarmcoder.domain.CriterionStatus.PROPOSED) {
                criterion.setStatus(com.swarmcoder.domain.CriterionStatus.ACCEPTED);
                accepted++;
            }
        }
        requirement.setStatus(RequirementStatus.ACTIVE);
        // Re-derive: a promoted requirement whose criteria all already pass is IMPLEMENTED, not
        // ACTIVE, and claiming otherwise would send the delivery gate looking for work that is done.
        requirement.setStatus(requirement.statusFromEvidence());
        return accepted;
    }

    // --- canvas layout ---------------------------------------------------------------------------

    /**
     * Records one node's hand-placed position.
     *
     * <p>An unknown requirement id is REFUSED rather than stored. A position keyed to nothing is an
     * orphan: it can never be seen, moved or corrected, and it would quietly outlive the requirement
     * it was meant for — so "save a position for a requirement that is not in this BRD" is not a
     * no-op, it is an error the caller has to hear about.
     *
     * <p>Negative coordinates are clamped, not rejected. The canvas is fitted from the content
     * bounding box starting at the origin, so a node at a negative coordinate is not "further left",
     * it is outside the fitted box and therefore invisible — a place no operator can have meant to
     * put it, and one they could not drag it back from.
     */
    @Override
    public String saveNodePosition(String requirementId, double x, double y) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            if (!isFinite(x) || !isFinite(y)) {
                return "error: a node position needs two real coordinates";
            }
            Brd brd = context.store().ensureBrd(projectId);
            BrdRequirement requirement = requirementById(brd, requirementId);
            if (requirement == null) {
                return "error: unknown requirement";
            }
            List<BrdNodePosition> positions = withoutPosition(brd, requirement.id());
            positions.add(new BrdNodePosition(requirement.id(), Math.max(0, x), Math.max(0, y)));
            brd.setNodePositions(positions);
            // The store's own BRD path (storeDeep — a lazy store() stops at instances it already
            // knows, which is exactly what a re-assigned list on a long-lived Brd looks like), but
            // the UNATTRIBUTED overload: where a box sits is not a change to what the document says.
            // Giving every drag a revision would bury the content history — the thing history is
            // for — under layout noise, and offer "restore" on revisions that restore nothing.
            context.store().saveBrd(brd);
            BrdAuthoring.pushChanged(brd);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    @Override
    public String clearNodePositions() {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            Brd brd = context.store().ensureBrd(projectId);
            brd.setNodePositions(new ArrayList<>());
            context.store().saveBrd(brd);
            BrdAuthoring.pushChanged(brd);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /** This BRD's positions, as a fresh list, minus the one for {@code requirementId}. */
    private static List<BrdNodePosition> withoutPosition(Brd brd, UUID requirementId) {
        List<BrdNodePosition> positions = new ArrayList<>();
        for (BrdNodePosition p : brd.nodePositions()) {
            if (p.requirementId() != null && !p.requirementId().equals(requirementId)) {
                positions.add(p);
            }
        }
        return positions;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    @Override
    public String saveEdge(BrdEdge incoming) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            UUID from = incoming.from();
            UUID to = incoming.to();
            if (from == null || to == null) {
                return "error: edge needs both endpoints";
            }
            if (from.equals(to)) {
                return "error: an edge cannot connect a requirement to itself";
            }
            if (incoming.relation() == null) {
                return "error: edge needs a relation";
            }
            Brd brd = context.store().ensureBrd(projectId);
            if (!hasNode(brd, from) || !hasNode(brd, to)) {
                return "error: both endpoints must be existing requirements";
            }
            // The shared shape gate — same rules as the agent's BrdAuthoring.addEdge. This path used
            // to accept a GATES edge from a functional requirement, which StoryScope then had to
            // defend against at read time; now neither path can create one.
            String rejection = RequirementTree.rejectionFor(brd, from, to, incoming.relation());
            if (rejection != null) {
                return rejection;
            }
            List<BrdEdge> edges = new ArrayList<>();
            for (BrdEdge e : safe(brd.edges())) {
                if (!(from.equals(e.from()) && to.equals(e.to()) && incoming.relation().equals(e.relation()))) {
                    edges.add(e);
                }
            }
            edges.add(new BrdEdge(from, to, incoming.relation()));
            brd.setEdges(edges);
            // The operator's phrase, not the constant. History used to read "added edge R6 refines
            // R1" - REFINES is a Java name and appears nowhere on any screen (25.5). The wording is
            // RequirementRelation's, the same source the rows and the legend read.
            context.store().saveBrd(brd, "human", "linked " + handleOf(brd, from) + ": now "
                + incoming.relation().fromPhrase() + " " + handleOf(brd, to));
            BrdAuthoring.pushChanged(brd);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    @Override
    public String saveCriterion(String requirementId, com.swarmcoder.domain.AcceptanceCriterion incoming) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            if (incoming == null || incoming.text() == null || incoming.text().isBlank()) {
                return "error: a check needs a statement of what must be true";
            }
            Brd brd = context.store().ensureBrd(projectId);
            BrdRequirement requirement = requirementById(brd, requirementId);
            if (requirement == null) {
                return "error: unknown requirement";
            }
            List<com.swarmcoder.domain.AcceptanceCriterion> criteria =
                new ArrayList<>(requirement.criteria());
            com.swarmcoder.domain.AcceptanceCriterion target = null;
            for (com.swarmcoder.domain.AcceptanceCriterion c : criteria) {
                if (incoming.id() != null && incoming.id().equals(c.id())) {
                    target = c;
                    break;
                }
            }
            String summary;
            if (target == null) {
                target = new com.swarmcoder.domain.AcceptanceCriterion(
                    UUID.randomUUID(), incoming.text().trim(), blankToNull(incoming.testClassOrFile()));
                target.setStatus(incoming.getStatus() == null
                    ? com.swarmcoder.domain.CriterionStatus.ACCEPTED : incoming.status());
                // Typed into the editor by a person: this is a decision, not a proposal.
                target.setTestRefOrigin(com.swarmcoder.domain.TestRefOrigin.OPERATOR);
                criteria.add(target);
                summary = "added a check to " + requirement.handle();
            } else {
                String wasTestRef = target.testClassOrFile();
                target.setText(incoming.text().trim());
                target.setTestClassOrFile(blankToNull(incoming.testClassOrFile()));
                // The operator OWNS the reference the moment they change it. Only a change flips
                // the origin: saving the row after editing the wording or the agreement must not
                // silently convert a wizard's untouched proposal into the operator's decision,
                // which would erase the very warning that asked them to look at it.
                if (!java.util.Objects.equals(wasTestRef, target.testClassOrFile())) {
                    target.setTestRefOrigin(com.swarmcoder.domain.TestRefOrigin.OPERATOR);
                }
                if (incoming.getStatus() != null) {
                    target.setStatus(incoming.status());
                }
                summary = "edited a check of " + requirement.handle();
            }
            requirement.setCriteria(criteria);
            // Accepting or retiring a criterion changes what has to hold for this requirement, so
            // its status is re-derived from the evidence rather than left claiming the old answer.
            requirement.setStatus(requirement.statusFromEvidence());
            brd.setRequirements(new ArrayList<>(safe(brd.requirements())));
            context.store().saveBrd(brd, "human", summary);
            context.store().recordChange(projectId, "human",
                com.swarmcoder.domain.ChangeEntityType.CRITERION, target.id(),
                com.swarmcoder.domain.ChangeKind.UPDATED, summary);
            BrdAuthoring.pushChanged(brd);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Takes one check out of the gate, keeping it on the record. It is NOT removed (25.1).
     *
     * <p>Removing it was worse than removing a requirement, because a check is the exact thing a
     * commit and a test were tied to: a story naming it was left pointing at nothing, with no
     * warning and no repair, and the verification history for it had nowhere to hang.
     *
     * <p><b>What a story claiming it does now.</b> Nothing is rewritten. The claim stays, because it
     * is the record of what the story was undertaken to deliver, and quietly editing it would be the
     * same silent severing in a different place. What changes is that the check stops gating -
     * {@code StoryScope} has always skipped retired checks, so no run will build it - and the
     * operator is TOLD, by name, on the spot: which story was delivering it, and how much of that
     * story is left. A story with nothing live left to deliver is named as such, because a story
     * that can never finish is not something to discover weeks later.
     */
    @Override
    public String retireCriterion(String requirementId, String criterionId) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            Brd brd = context.store().ensureBrd(projectId);
            BrdRequirement requirement = requirementById(brd, requirementId);
            if (requirement == null) {
                return "error: unknown requirement";
            }
            UUID id = parseUuid(criterionId);
            com.swarmcoder.domain.AcceptanceCriterion target = null;
            for (com.swarmcoder.domain.AcceptanceCriterion c : requirement.criteria()) {
                if (id != null && id.equals(c.id())) {
                    target = c;
                    break;
                }
            }
            if (target == null) {
                return "error: no such check";
            }
            if (target.status() == CriterionStatus.RETIRED) {
                return "error: that check is already retired";
            }
            target.setStatus(CriterionStatus.RETIRED);
            requirement.setCriteria(new ArrayList<>(requirement.criteria()));
            requirement.setStatus(requirement.statusFromEvidence());
            brd.setRequirements(new ArrayList<>(safe(brd.requirements())));
            context.store().saveBrd(brd, "human", "retired a check of " + requirement.handle());
            try {
                context.store().recordChange(projectId, "human", ChangeEntityType.CRITERION,
                    target.id(), ChangeKind.TOMBSTONED,
                    "retired a check of " + requirement.handle());
            } catch (Exception ignored) {
                // the journal is an audit aid; never fail a mutation because it could not be written
            }
            BrdAuthoring.pushChanged(brd);
            return claimWarning(context.store(), projectId, target.id());
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * What to say to the operator about the stories that were delivering a check just retired, or ""
     * when nobody was.
     *
     * <p>Not an error - nothing failed, and the retirement stands. It is the honest, adjacent
     * feedback UX v3 rule 4 asks for: the consequence, named, beside the control that caused it.
     */
    private static String claimWarning(ArtifactStore store, UUID projectId, UUID criterionId) {
        try {
            Brd brd = store.ensureBrd(projectId);
            java.util.Set<UUID> live = new java.util.HashSet<>();
            for (BrdRequirement r : safe(brd.requirements())) {
                if (r == null || r.isRetired()) {
                    continue;
                }
                for (com.swarmcoder.domain.AcceptanceCriterion c : r.criteria()) {
                    if (c != null && c.status() != CriterionStatus.RETIRED) {
                        live.add(c.id());
                    }
                }
            }
            List<String> emptied = new ArrayList<>();
            List<String> reduced = new ArrayList<>();
            for (Story story : store.listStories(projectId)) {
                if (story == null || story.criterionIds() == null
                        || story.state() == StoryState.CANCELLED
                        || story.state() == StoryState.DONE
                        || !story.criterionIds().contains(criterionId)) {
                    continue;
                }
                int left = 0;
                for (UUID claimed : story.criterionIds()) {
                    if (live.contains(claimed)) {
                        left++;
                    }
                }
                String key = story.key() == null ? "a story" : story.key();
                if (left == 0) {
                    emptied.add(key);
                } else {
                    reduced.add(key + " now delivers " + left
                        + (left == 1 ? " check" : " checks"));
                }
            }
            if (emptied.isEmpty() && reduced.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("That check is retired and kept on the record. ");
            if (!reduced.isEmpty()) {
                sb.append(String.join(", ", reduced)).append('.');
            }
            if (!emptied.isEmpty()) {
                sb.append(emptied.size() == 1
                    ? " " + emptied.get(0) + " has nothing left to deliver"
                    : " " + String.join(" and ", emptied) + " have nothing left to deliver");
                sb.append(" - give it another check, or drop it.");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The project's uploaded documents, WITHOUT their extracted text.
     *
     * <p>The caller lists names and sizes and fetches the text of the one document being looked at
     * separately. Sending the text with the list used to be free; since 0.7.0 a single message over
     * 4 MB closes the connection instead of returning, and the extractor's own ceiling is ten
     * million characters PER DOCUMENT — so one large spec was enough to make this call kill the
     * console rather than answer it.
     */
    @Override
    public List<com.swarmcoder.domain.SourceDocument> sources() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        return projectId == null ? List.of()
            : WireBudget.withoutText(context.store().listSourceDocuments(projectId));
    }

    private static BrdRequirement requirementById(Brd brd, String requirementId) {
        UUID id = parseUuid(requirementId);
        if (id == null) {
            return null;
        }
        for (BrdRequirement r : safe(brd.requirements())) {
            if (id.equals(r.id())) {
                return r;
            }
        }
        return null;
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

    @Override
    public String deleteEdge(BrdEdge incoming) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            UUID from = incoming.from();
            UUID to = incoming.to();
            Brd brd = context.store().ensureBrd(projectId);
            List<BrdEdge> edges = new ArrayList<>();
            boolean removed = false;
            for (BrdEdge e : safe(brd.edges())) {
                if (from != null && from.equals(e.from()) && to != null && to.equals(e.to())
                        && (incoming.relation() == null || incoming.relation().equals(e.relation()))) {
                    removed = true;
                } else {
                    edges.add(e);
                }
            }
            if (!removed) {
                return "error: no such edge";
            }
            brd.setEdges(edges);
            context.store().saveBrd(brd, "human",
                "unlinked " + handleOf(brd, from) + " from " + handleOf(brd, to));
            BrdAuthoring.pushChanged(brd);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    // --- versioning ----------------------------------------------------------------------------

    @Override
    public List<BrdRevision> history() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context.currentProjectId();
        if (projectId == null) {
            return List.of();
        }
        List<BrdRevision> revs = context.store().listBrdRevisions(projectId);
        List<BrdRevision> out = new ArrayList<>();
        for (int i = revs.size() - 1; i >= 0; i--) { // newest first for the history panel
            out.add(revs.get(i));
        }
        return out;
    }

    @Override
    public Brd revisionAt(long revision) {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context.currentProjectId();
        if (projectId == null) {
            return new Brd(null, null, 0, "", new ArrayList<>(), new ArrayList<>(), null, null);
        }
        Brd snap = context.store().brdRevisionSnapshot(projectId, revision);
        return snap == null
            ? new Brd(null, null, 0, "", new ArrayList<>(), new ArrayList<>(), null, null) : snap;
    }

    /**
     * What restoring this revision would change, said before it happens.
     *
     * <p>Restoring has no undo prompt anywhere else in this product because nothing else takes a
     * whole document backwards. Everything written since the chosen revision leaves the live
     * document; it is recoverable, because history is only ever appended to and restoring a later
     * revision brings it back, but until now the operator was told neither half. Before a fix landed
     * on 2026-08-28 this action silently destroyed most of the document it restored, so the class of
     * danger here is not hypothetical.
     */
    @Override
    public String restoreSummary(long revision) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "";
            }
            Brd snap = context.store().brdRevisionSnapshot(projectId, revision);
            if (snap == null) {
                return "There is no revision " + revision + " to go back to.";
            }
            Brd now = context.store().ensureBrd(projectId);
            java.util.Set<UUID> then = new java.util.HashSet<>();
            for (BrdRequirement r : safe(snap.requirements())) {
                if (r != null) {
                    then.add(r.id());
                }
            }
            List<String> going = new ArrayList<>();
            int changed = 0;
            for (BrdRequirement r : safe(now.requirements())) {
                if (r == null) {
                    continue;
                }
                if (!then.contains(r.id())) {
                    going.add(r.handle() == null ? "one requirement" : r.handle());
                } else if (!r.equals(requirementIn(snap, r.id()))) {
                    changed++;
                }
            }
            StringBuilder sb = new StringBuilder("This puts the requirements back to how they were "
                + "at revision " + revision + ".");
            if (going.isEmpty() && changed == 0) {
                sb.append(" Nothing has changed since then, so nothing will move.");
            } else {
                if (!going.isEmpty()) {
                    sb.append(' ').append(going.size())
                        .append(going.size() == 1 ? " requirement written since then leaves the "
                            + "list: " : " requirements written since then leave the list: ")
                        .append(String.join(", ", going)).append('.');
                }
                if (changed > 0) {
                    sb.append(' ').append(changed)
                        .append(changed == 1 ? " requirement goes back to its older wording, "
                            + "checks included." : " requirements go back to their older wording, "
                            + "checks included.");
                }
            }
            sb.append(" Nothing is deleted: this is saved as a new revision, and restoring a later "
                + "one brings everything back.");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static BrdRequirement requirementIn(Brd brd, UUID id) {
        for (BrdRequirement r : safe(brd.requirements())) {
            if (r != null && r.id() != null && r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    @Override
    public String restore(long revision) {
        try {
            ConsoleContext context = ConsoleContext.get();
            UUID projectId = context.currentProjectId();
            if (projectId == null) {
                return "error: no current project";
            }
            Brd snap = context.store().brdRevisionSnapshot(projectId, revision);
            if (snap == null) {
                return "error: no such revision " + revision;
            }
            Brd brd = context.store().ensureBrd(projectId);
            // A DEEP COPY of the snapshot, not a rebuild through the 7-arg constructor. That
            // constructor covers seven of the twelve fields, and restoring through it silently
            // destroyed the other five on EVERY requirement in the document: every check, the
            // kind and quality category (so a quality constraint came back as an ordinary
            // requirement and the gate it imposed stopped existing), the source document it was
            // extracted from, and the content revision that decides whether prior test evidence
            // reads stale. The snapshot itself has always held all of it — the loss was here, and
            // only here. Restoring is the one action an operator takes BECAUSE they want the old
            // document back, so quietly returning a hollowed-out one is the worst possible place
            // for this defect. It is the same fault the comment in saveRequirement describes, in
            // the one path nothing had ever driven end to end.
            Brd restored = com.swarmcoder.store.ArtifactStore.copyOf(snap);
            brd.setRequirements(new ArrayList<>(safe(restored.requirements())));
            brd.setEdges(new ArrayList<>(safe(restored.edges())));
            context.store().saveBrd(brd, "restore", "restored revision " + revision);
            BrdAuthoring.pushChanged(brd);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private static String handleOf(Brd brd, UUID id) {
        for (BrdRequirement r : safe(brd.requirements())) {
            if (r.id().equals(id)) {
                return r.handle();
            }
        }
        return id == null ? "?" : id.toString().substring(0, 8);
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

    private static boolean hasNode(Brd brd, UUID id) {
        for (BrdRequirement r : safe(brd.requirements())) {
            if (r.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
