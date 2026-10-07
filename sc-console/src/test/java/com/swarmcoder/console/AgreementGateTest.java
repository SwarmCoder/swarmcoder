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
import com.swarmcoder.domain.AgreementGate;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vagueness cannot become agreed scope (author decision, DEVELOPER_CORRECTIONS.md §20.2).
 *
 * <p>The rule is one sentence: nothing becomes agreed scope without a check that names a test which
 * could prove it. What these tests pin is that it holds at EVERY door — the Agree button, the
 * agree-everything action, and the status dropdown in the editor — because a rule enforced at two
 * of three doors is not a rule, and the graph already had a red badge that told the operator about
 * the problem only after they had caused it.
 *
 * <p>They also pin the other half: creating and editing a draft is untouched. Somebody writing a
 * requirement by hand types the title first and the checks a minute later, and a gate on CREATION
 * would make that impossible while catching nothing a gate on AGREEMENT does not.
 */
class AgreementGateTest {

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;
    private BrdServiceImpl brd;

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir);
        brd = new BrdServiceImpl();
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));
    }

    @AfterEach
    void closeStore() throws Exception {
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    // --- the sentence itself -------------------------------------------------------------------

    @Test
    void aRequirementWithNoCheckAtAllIsRefusedAndToldWhy() {
        BrdRequirement bare = draft("R1", "Handle multiplication at the boundaries of the "
            + "supported integer range");

        String refusal = AgreementGate.rejectionFor(bare);

        assertThat(refusal)
            .contains("R1")
            .contains("no check")
            .contains("stays a draft");
        assertThat(AgreementGate.mayBeAgreed(bare)).isFalse();
    }

    @Test
    void aCheckThatNamesNoTestIsNotACheckThatCouldProveAnything() {
        BrdRequirement vague = draft("R1", "Multiplication behaves sensibly at the edges");
        vague.setCriteria(new ArrayList<>(List.of(
            criterion("it behaves sensibly", null, CriterionStatus.PROPOSED))));

        assertThat(AgreementGate.rejectionFor(vague))
            .contains("none of them names a test")
            .contains("nothing would ever run");
    }

    @Test
    void oneCheckThatNamesATestIsEnough() {
        BrdRequirement provable = draft("R1", "Multiply two whole numbers");
        provable.setCriteria(new ArrayList<>(List.of(
            criterion("two by three is six", "swarm.accept.MultiplyTest#twoByThree",
                CriterionStatus.PROPOSED))));

        assertThat(AgreementGate.rejectionFor(provable)).isNull();
    }

    /**
     * A retired check was deliberately taken out of the gate. Agreeing a requirement must not
     * resurrect it, so it cannot be what makes the requirement agreeable either.
     */
    @Test
    void aRetiredCheckDoesNotCountBecauseAgreeingWillNotBringItBack() {
        BrdRequirement retiredOnly = draft("R1", "Multiply two whole numbers");
        retiredOnly.setCriteria(new ArrayList<>(List.of(
            criterion("an old idea", "swarm.accept.OldTest#gone", CriterionStatus.RETIRED))));

        assertThat(AgreementGate.rejectionFor(retiredOnly)).contains("no check");
    }

    // --- every door ----------------------------------------------------------------------------

    @Test
    void theAgreeButtonRefusesAnUnprovableRequirementAndAgreesAProvableOne() {
        BrdRequirement vague = store(draft("R1", "Behaves sensibly at the edges"));
        BrdRequirement provable = store(withCheck(draft("R2", "Multiply two whole numbers")));

        assertThat(brd.promoteRequirement(vague.id().toString()))
            .startsWith("error:")
            .contains("no check");
        assertThat(reload("R1").status())
            .as("refused means unchanged, not half-changed")
            .isEqualTo(RequirementStatus.DRAFT);

        assertThat(brd.promoteRequirement(provable.id().toString())).isEmpty();
        assertThat(reload("R2").status()).isEqualTo(RequirementStatus.ACTIVE);
        assertThat(reload("R2").criteria().get(0).status())
            .as("agreeing a requirement accepts its checks, as it always did")
            .isEqualTo(CriterionStatus.ACCEPTED);
    }

    /**
     * Agreeing everything after an intake agrees what it can and NAMES what it could not. Refusing
     * the whole batch because one of ten is unprovable would be punitive; agreeing nine and saying
     * nothing would leave the operator believing they had agreed ten.
     */
    @Test
    void agreeingEverythingAgreesWhatItCanAndSaysWhatItLeftBehind() {
        store(withCheck(draft("R1", "Multiply two whole numbers")));
        store(draft("R2", "Behaves sensibly at the edges"));
        store(withCheck(draft("R3", "Divide two whole numbers")));

        String summary = brd.promoteAllDrafts();

        assertThat(summary)
            .doesNotStartWith("error:")
            .contains("Agreed 2 of 3")
            .contains("R2")
            .contains("no check");
        assertThat(reload("R1").status()).isEqualTo(RequirementStatus.ACTIVE);
        assertThat(reload("R2").status()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(reload("R3").status()).isEqualTo(RequirementStatus.ACTIVE);
    }

    @Test
    void agreeingEverythingWhenNothingCanBeAgreedIsAnErrorWithTheReasons() {
        store(draft("R1", "Behaves sensibly at the edges"));

        assertThat(brd.promoteAllDrafts())
            .startsWith("error: none of these can be agreed yet")
            .contains("R1");
        assertThat(reload("R1").status()).isEqualTo(RequirementStatus.DRAFT);
    }

    /**
     * The status dropdown in the editor is the third door, and it used to be the open one — the
     * Agree button and the dropdown wrote the same field and only one of them would have been
     * guarded.
     */
    @Test
    void theStatusDropdownIsTheSameActAndAnswersToTheSameRule() {
        BrdRequirement vague = store(draft("R1", "Behaves sensibly at the edges"));

        BrdRequirement edited = copyOf(vague);
        edited.setStatus(RequirementStatus.ACTIVE);
        assertThat(brd.saveRequirement(edited)).startsWith("error:").contains("no check");

        BrdRequirement stored = reload("R1");
        assertThat(stored.status()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(stored.title())
            .as("a refused save writes nothing at all, not the edit without the status")
            .isEqualTo("Behaves sensibly at the edges");
    }

    @Test
    void aBrandNewRequirementCannotBeCreatedStraightIntoAgreedScope() {
        BrdRequirement fresh = new BrdRequirement(UUID.randomUUID(), null,
            "Behaves sensibly at the edges", "It behaves sensibly.", null,
            RequirementStatus.ACTIVE, null);

        assertThat(brd.saveRequirement(fresh)).startsWith("error:").contains("no check");
        assertThat(store.getBrd(projectId).requirements()).isEmpty();
    }

    // --- and what it deliberately does not touch ------------------------------------------------

    @Test
    void writingAndEditingADraftWithNoChecksIsUntouched() {
        BrdRequirement fresh = new BrdRequirement(UUID.randomUUID(), null,
            "Something I will describe properly in a minute", null, null,
            RequirementStatus.DRAFT, null);

        assertThat(brd.saveRequirement(fresh)).isEmpty();
        BrdRequirement stored = store.getBrd(projectId).requirements().get(0);
        assertThat(stored.status()).isEqualTo(RequirementStatus.DRAFT);

        stored.setTitle("Still working on it");
        assertThat(brd.saveRequirement(stored)).isEmpty();
        assertThat(store.getBrd(projectId).requirements().get(0).title())
            .isEqualTo("Still working on it");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private BrdRequirement draft(String handle, String title) {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), handle, title,
            title + ".", null, RequirementStatus.DRAFT, null);
        requirement.setCriteria(new ArrayList<>());
        return requirement;
    }

    private static BrdRequirement withCheck(BrdRequirement requirement) {
        requirement.setCriteria(new ArrayList<>(List.of(criterion(
            "it does what it says", "swarm.accept.AreaTest#doesWhatItSays",
            CriterionStatus.PROPOSED))));
        return requirement;
    }

    private static AcceptanceCriterion criterion(String text, String test, CriterionStatus status) {
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), text, test);
        criterion.setStatus(status);
        return criterion;
    }

    private BrdRequirement store(BrdRequirement requirement) {
        Brd graph = store.ensureBrd(projectId);
        List<BrdRequirement> requirements = new ArrayList<>(
            graph.requirements() == null ? List.of() : graph.requirements());
        requirements.add(requirement);
        graph.setRequirements(requirements);
        store.saveBrd(graph, "human", "seeded " + requirement.handle());
        return requirement;
    }

    private BrdRequirement reload(String handle) {
        for (BrdRequirement requirement : store.getBrd(projectId).requirements()) {
            if (handle.equals(requirement.handle())) {
                return requirement;
            }
        }
        throw new AssertionError("no " + handle);
    }

    /** A detached copy, as the browser sends one back from the form. */
    private static BrdRequirement copyOf(BrdRequirement requirement) {
        BrdRequirement copy = new BrdRequirement(requirement.id(), requirement.handle(),
            requirement.title(), requirement.text(), requirement.priority(),
            requirement.status(), requirement.category());
        copy.setCriteria(new ArrayList<>(requirement.criteria()));
        return copy;
    }
}
