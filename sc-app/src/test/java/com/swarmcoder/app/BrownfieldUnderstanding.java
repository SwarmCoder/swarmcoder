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
package com.swarmcoder.app;

import com.swarmcoder.console.ChangeRequestIntake;
import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.knowledge.ChangeNeighbourhood;
import com.swarmcoder.knowledge.SemanticIndex;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.verify.BuildLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Links 4, 5 and 6 of the brownfield chain, as three calls a harness can make.
 *
 * <p>Written as a helper rather than as a test of its own for the reason design §5 gives: there is
 * one brownfield harness, {@code BrownfieldLoopTest}, and a second one walking the same links
 * against the same target would be a second definition of what those links mean. This is what that
 * harness calls; {@code BrownfieldUnderstandingTest} beside it calls the same three methods over
 * the five real cases with a scripted analyst, so the measurement is made in a build that needs no
 * live model.
 *
 * <table>
 *   <tr><td>4</td><td>the change request becomes one agreed requirement carrying checks</td></tr>
 *   <tr><td>5</td><td>the neighbourhood of the change names real types, with files and lines</td></tr>
 *   <tr><td>6</td><td>the architect's design states contracts naming types that exist</td></tr>
 * </table>
 *
 * <p><b>Every link records one observation phrased to be true either way</b>, which is
 * {@link ChainLedger}'s whole contract: a measurement, never a verdict, because the failure this
 * harness exists to catch is a stage that exits 0 having done nothing.
 *
 * <p>Nothing here reads the case file. The caller passes the fields, so this stays usable whatever
 * shape the cases are stored in.
 */
final class BrownfieldUnderstanding {

    static final String L_REQUIREMENT =
        "the change request becomes one agreed requirement carrying checks";
    static final String L_NEIGHBOURHOOD =
        "the neighbourhood of the change names real types, with files and lines";
    static final String L_CONTRACTS =
        "the architect's design states contracts naming types that exist";

    /**
     * The three links, in the order they are WALKED, for a ledger's plan.
     *
     * <p>Design §5.1 numbers the requirement 4 and the neighbourhood 5, and the neighbourhood is
     * still read first — the analyst that turns the issue into a requirement is shown it, so
     * measuring it afterwards would be measuring what the requirement was already written from. A
     * ledger prints its plan in order and marks anything it never reached, so the plan has to be
     * the walk rather than the numbering.
     */
    static List<String> chainLinks() {
        return List.of(L_NEIGHBOURHOOD, L_REQUIREMENT, L_CONTRACTS);
    }

    /**
     * The two links that can be walked before a run has produced a design.
     *
     * <p>Link 6 reads the architect's contracts, which do not exist until the run reaches DESIGN.
     * A caller that stops before that uses this, so its ledger reports a WHOLE chain rather than an
     * incomplete one — an "incomplete" that is by design reads exactly like a break, and a ledger
     * whose ordinary output is a warning is a ledger nobody reads.
     */
    static List<String> chainLinksBeforeTheDesign() {
        return List.of(L_NEIGHBOURHOOD, L_REQUIREMENT);
    }

    private BrownfieldUnderstanding() {}

    // --- link 5: the neighbourhood ---------------------------------------------------------

    /**
     * Reads the neighbourhood of a change request out of the target, and records what it found.
     *
     * <p>Walked BEFORE link 4 even though it is numbered after it, because the analyst is shown
     * this text: measuring it after the requirement exists would measure something the requirement
     * was already written from. The ledger records it in chain order all the same — see
     * {@link #recordTheNeighbourhood}.
     *
     * @param testSourceRoots from {@link #testRootsOf}; empty falls back to the conventions
     */
    static ChangeNeighbourhood.Neighbourhood neighbourhoodOf(String title, String issueText,
                                                             SemanticIndex index,
                                                             List<String> testSourceRoots,
                                                             int budgetChars) {
        String wholeRequest = (title == null || title.isBlank() ? "" : title + "\n\n")
            + (issueText == null ? "" : issueText);
        return ChangeNeighbourhood.read(wholeRequest, index, testSourceRoots, budgetChars);
    }

    /**
     * The build's own test source roots, so "which of these files is a test" is the build's answer
     * and not a directory-name guess. An undetermined layout gives an empty list, which
     * {@link ChangeNeighbourhood} treats as "use the conventions" rather than "there are none".
     */
    static List<String> testRootsOf(BuildLayout.Layout layout) {
        List<String> roots = new ArrayList<>();
        for (String root : layout == null ? List.<String>of() : layout.sourceRoots()) {
            String normalised = root.replace('\\', '/');
            if (normalised.contains("/test/") || normalised.startsWith("test/")
                    || normalised.contains("/it/") || normalised.startsWith("it/")) {
                roots.add(normalised);
            }
        }
        return roots;
    }

    /**
     * Records link 5. Held: the brief names at least one type the project really declares, and
     * every type it names carries a file and a line.
     */
    static void recordTheNeighbourhood(ChainLedger chain,
                                       ChangeNeighbourhood.Neighbourhood neighbourhood) {
        List<String> withoutALine = new ArrayList<>();
        for (ChangeNeighbourhood.NamedType type : neighbourhood.types()) {
            if (type.where() == null || !type.where().contains(":")) {
                withoutALine.add(type.fqn());
            }
        }
        chain.require(L_NEIGHBOURHOOD,
            !neighbourhood.types().isEmpty() && withoutALine.isEmpty(),
            neighbourhood.describe()
                + (withoutALine.isEmpty() ? "; every type named carries the file and line it is "
                    + "declared on" : "; " + withoutALine.size() + " type(s) carry no declaration "
                    + "site: " + withoutALine)
                + (neighbourhood.types().isEmpty()
                    ? " — the report named nothing this project has, so the architect designs from "
                        + "the goal string alone, exactly as it did before this existed" : ""));
    }

    // --- link 4: the requirement ------------------------------------------------------------

    /**
     * Runs the intake and records link 4.
     *
     * <p>Held when: exactly one requirement was added, it is AGREED, it carries one to three
     * checks, every one of them is ACCEPTED and names a test, one story delivers exactly those
     * checks, and a run was started bound to that story.
     *
     * @return what was created, for the caller to drive the run with
     */
    static ChangeRequestIntake.Started walkTheChangeRequest(ChainLedger chain,
                                                            ConsoleContext context, UUID projectId,
                                                            ChangeRequestIntake.Request request,
                                                            String neighbourhoodBrief,
                                                            String codebaseRules) {
        ArtifactStore store = context.store();
        int requirementsBefore = store.ensureBrd(projectId).requirements().size();
        ChangeRequestIntake.Started started;
        try {
            started = ChangeRequestIntake.readAndStart(context, projectId, request,
                neighbourhoodBrief, codebaseRules);
        } catch (Exception e) {
            throw chain.fail(L_REQUIREMENT, "the change request could not be read into a "
                + "requirement: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        if (started == null) {
            throw chain.fail(L_REQUIREMENT,
                "the requirement and story were written but no run was started");
        }
        Brd brd = store.ensureBrd(projectId);
        BrdRequirement requirement = null;
        for (BrdRequirement candidate : brd.requirements()) {
            if (started.requirementHandle().equalsIgnoreCase(candidate.handle())) {
                requirement = candidate;
            }
        }
        if (requirement == null) {
            throw chain.fail(L_REQUIREMENT, "requirement " + started.requirementHandle()
                + " was reported written and is not in the BRD");
        }
        List<AcceptanceCriterion> checks = requirement.criteria();
        long accepted = checks.stream()
            .filter(c -> c.status() == com.swarmcoder.domain.CriterionStatus.ACCEPTED).count();
        long named = checks.stream()
            .filter(c -> c.testClassOrFile() != null && !c.testClassOrFile().isBlank()).count();
        Story story = started.story();
        List<UUID> checkIds = checks.stream().map(AcceptanceCriterion::id).toList();
        boolean storyClaimsThem = story != null
            && story.criterionIds().size() == checkIds.size()
            && story.criterionIds().containsAll(checkIds);

        chain.require(L_REQUIREMENT,
            brd.requirements().size() == requirementsBefore + 1
                && requirement.status() == RequirementStatus.ACTIVE
                && checks.size() >= ChangeRequestIntake.MIN_CHECKS
                && checks.size() <= ChangeRequestIntake.MAX_CHECKS
                && accepted == checks.size() && named == checks.size()
                && storyClaimsThem && started.runId() != null,
            "the issue became " + requirement.handle() + " '" + requirement.title() + "' ["
                + requirement.status() + "] with " + checks.size() + " check(s), " + accepted
                + " accepted and " + named + " naming a test: "
                + checks.stream().map(c -> c.text() + " -> " + c.testClassOrFile()).toList()
                + "; it states " + assumptionsIn(requirement) + " assumption(s) and asked nobody "
                + "anything; story " + (story == null ? "none" : story.key() + " ["
                    + story.state() + "] claiming " + story.criterionIds().size() + " check(s)")
                + "; run " + started.runId());
        return started;
    }

    /** How many assumptions the requirement states — the mechanism that replaces a question. */
    static int assumptionsIn(BrdRequirement requirement) {
        String text = requirement.text() == null ? "" : requirement.text();
        int found = 0;
        int at = text.indexOf("ASSUMPTION:");
        while (at >= 0) {
            found++;
            at = text.indexOf("ASSUMPTION:", at + 1);
        }
        return found;
    }

    // --- link 6: the contracts ---------------------------------------------------------------

    /**
     * Records link 6: did the architect state contracts, and do they name types this repository
     * actually has?
     *
     * <p>On a brownfield change most contracts SHOULD name existing types — design §2.3 turns on
     * that, because {@code Librarian.workedExample} returns "" when a task delivers no contract,
     * and the whole worked-example channel then collapses into a documentation guess. A contract
     * naming a type the index does not know is not automatically wrong: the change may genuinely
     * introduce one. So the observation reports both counts and holds when the design stated any
     * contract carrying a type name at all.
     */
    static void recordTheContracts(ChainLedger chain, DesignDocument design, SemanticIndex index) {
        if (design == null) {
            throw chain.fail(L_CONTRACTS, "the run stored no design at all");
        }
        List<String> existing = new ArrayList<>();
        List<String> newTypes = new ArrayList<>();
        List<String> unnamed = new ArrayList<>();
        for (ApiContract contract : design.contracts()) {
            String typeName = contract.typeName();
            if (typeName == null || typeName.isBlank()) {
                unnamed.add(contract.name());
            } else if (index != null && index.available() && !index.resolve(typeName).isEmpty()) {
                existing.add(typeName);
            } else {
                newTypes.add(typeName);
            }
        }
        int named = existing.size() + newTypes.size();
        chain.require(L_CONTRACTS, named > 0,
            design.contracts().size() + " contract(s) in the design, " + named
                + " naming a type: " + existing.size() + " that this repository already declares "
                + existing + " and " + newTypes.size() + " that it does not " + newTypes
                + (unnamed.isEmpty() ? "" : "; " + unnamed.size() + " contract(s) name no type at "
                    + "all " + unnamed + ", and a task delivering one of those gets no worked "
                    + "example — the example channel is off for it"));
    }
}
