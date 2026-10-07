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
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.SourceRef;
import com.swarmcoder.domain.TestRefOrigin;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * NOT A TEST. A hand-driving harness: it boots the Console on a fixed port with a requirements
 * fixture that has the shapes a person meets — a coarse requirement with parts, a quality
 * constraint, a requirement a document produced, a draft an agent proposed, checks in every state,
 * and a story that has already claimed some of them — and then waits so a human (or an agent with a
 * browser) can use the screens.
 *
 * <p>Deliberately named so surefire's default includes never pick it up. Run it explicitly:
 * {@code mvn -o -pl sc-console -am test -Dtest=RequirementsSandboxRunner
 * -Dswarmcoder.sandbox=true -Dswarmcoder.sandbox.port=8899}
 */
class RequirementsSandboxRunner {

    @org.junit.jupiter.api.BeforeAll
    static void enableDevAuth() {
        System.setProperty("zeroz.security.mode", "dev");
    }

    @Test
    @EnabledIfSystemProperty(named = "swarmcoder.sandbox", matches = "true",
        disabledReason = "hand-driving harness; enable with -Dswarmcoder.sandbox=true")
    void serveUntilStopped() throws Exception {
        int port = Integer.getInteger("swarmcoder.sandbox.port", 8899);
        long minutes = Long.getLong("swarmcoder.sandbox.minutes", 45L);
        Path dir = Files.createTempDirectory("sc-requirements-sandbox");
        try (ArtifactStore store = new ArtifactStore(dir)) {
            UUID projectId = seed(store, dir);
            try (com.zeroz4j.server.Zeroz4jServer server =
                     com.zeroz4j.server.Zeroz4jServer.start(port, "Requirements Sandbox")) {
                System.out.println("SANDBOX READY http://localhost:" + server.port()
                    + "/  project=" + projectId + "  store=" + dir);
                Thread.sleep(minutes * 60_000L);
            }
        }
    }

    /**
     * The fixture. Shared with the by-hand browser test so what a person drives and what the
     * assertions drive are the same document.
     */
    static UUID seed(ArtifactStore store, Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        Project project = new Project(projectId, "storefront", dir.toString(), List.of(),
            Instant.now(), false);
        store.saveProject(project);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
            .withProjects(() -> List.of(project), () -> projectId, (n, p, c) -> null, id -> { }));

        // A document, so one requirement can carry real provenance and the operator can meet the
        // "a document wrote this" case rather than only the hand-typed one.
        SourceDocument doc = new SourceDocument(UUID.randomUUID(), projectId, "checkout-brief.md",
            "text/markdown", "deadbeef", "Guests must be able to check out without an account.",
            "passthrough", 512L, Instant.now());
        store.saveSourceDocument(doc);

        // R1 Checkout — coarse, no checks of its own.
        BrdAuthoring.addRequirement(store, projectId, "Checkout",
            "A shopper can pay for what is in their basket", "HIGH", null, null, null, null);
        // R2 Guest checkout — produced by the document above.
        BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
            "A shopper can check out without creating an account", "HIGH", null, null, null,
            new SourceRef(doc.id(), "heading 2"));
        // R3 Saved cards — a draft an agent proposed during a run.
        BrdAuthoring.addRequirement(store, projectId, "Saved cards",
            "A returning shopper can pay with a card they saved earlier", "MEDIUM", null, null,
            null, null);
        // R4 a quality constraint, and R5 something unconnected so scoping means something.
        BrdAuthoring.addRequirement(store, projectId, "Checkout responds in under 200ms",
            "The 95th percentile of checkout API calls is under 200ms at 100 requests a second",
            "HIGH", null, "NON_FUNCTIONAL", "PERFORMANCE", null);
        BrdAuthoring.addRequirement(store, projectId, "Audit log",
            "Every payment attempt is recorded", "LOW", null, null, null, null);

        BrdAuthoring.addEdge(store, projectId, "R2", "refines", "R1");
        BrdAuthoring.addEdge(store, projectId, "R3", "refines", "R1");
        BrdAuthoring.addEdge(store, projectId, "R4", "gates", "R1");

        BrdAuthoring.addCriterion(store, projectId, "R2", "an empty basket is refused",
            "GuestCheckoutTest#emptyBasket");
        BrdAuthoring.addCriterion(store, projectId, "R2", "a guest order is confirmed on screen",
            "GuestCheckoutTest#confirmed");
        BrdAuthoring.addCriterion(store, projectId, "R3", "a saved card can be chosen at checkout",
            "SavedCardTest#choose");
        BrdAuthoring.addCriterion(store, projectId, "R4", "95th percentile stays under 200ms",
            "CheckoutPerfTest#p95");

        Brd brd = store.getBrd(projectId);
        for (BrdRequirement r : brd.requirements()) {
            boolean draft = "R3".equals(r.handle());
            r.setStatus(draft ? RequirementStatus.DRAFT : RequirementStatus.ACTIVE);
            for (AcceptanceCriterion c : r.criteria()) {
                c.setStatus(draft ? CriterionStatus.PROPOSED : CriterionStatus.ACCEPTED);
                c.setTestRefOrigin(TestRefOrigin.PROPOSED);
            }
        }
        // R2's first check already passes, against the wording as it stands. This is what a later
        // edit has to be honest about.
        BrdRequirement r2 = requirement(brd, "R2");
        AcceptanceCriterion passing = r2.criteria().get(0);
        passing.setVerification(CriterionState.PASSING);
        passing.setLastVerifiedCommit("a1b2c3d");
        passing.setLastVerifiedAt(Instant.now());
        passing.setVerifiedAgainstContentRevision(r2.contentRevision());
        passing.setTestRefOrigin(TestRefOrigin.OPERATOR);
        r2.criteria().get(1).setVerification(CriterionState.FAILING);
        store.saveBrd(brd, "human", "agreed the checkout requirements");

        // A story that has already claimed both of R2's checks, so editing R2 by hand is editing
        // work somebody is already committed to delivering.
        BacklogAuthoring.proposeStory(store, projectId, "Guests can pay", "R2:C1,R2:C2", null);
        return projectId;
    }

    private static BrdRequirement requirement(Brd brd, String handle) {
        for (BrdRequirement r : brd.requirements()) {
            if (handle.equals(r.handle())) {
                return r;
            }
        }
        throw new IllegalStateException("no " + handle);
    }
}
