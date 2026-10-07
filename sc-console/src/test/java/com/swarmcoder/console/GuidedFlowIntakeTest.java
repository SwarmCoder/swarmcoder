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

import com.swarmcoder.console.api.FlowView;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.FlowDiscussionTurn;
import com.swarmcoder.domain.FlowDocument;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowProposalKind;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.FlowQuestionKind;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The requirements-intake wizard end to end, with a scripted analyst instead of a live model.
 *
 * <p>What is under test is the <em>contract around</em> the model, not the model: that a round of
 * questions parks the flow instead of guessing, that a skipped question still lets it finish and is
 * told to the analyst as such, that nothing reaches the BRD until the operator applies, and that
 * changing the inputs invalidates a review assembled from the old ones. All of those are rules the
 * operator relies on and none of them need an LLM to prove.
 *
 * <p>The analysis runs on its own daemon thread, so every state transition is waited for with a
 * bounded poll on the persisted flow — the store, not the signal, is the source of truth.
 */
class GuidedFlowIntakeTest {

    /** Long enough to absorb a slow EclipseStore flush, short enough to fail rather than hang. */
    private static final long WAIT_MILLIS = 10_000;

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;
    private GuidedFlowServiceImpl service;

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir);
        service = new GuidedFlowServiceImpl();
    }

    @AfterEach
    void closeStore() throws Exception {
        // The context is installed statically; leaving one behind would let a later test run
        // against this test's closed store.
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    // --- 1. the happy path ------------------------------------------------------------------------

    @Test
    void aRoundOfQuestionsThenProposalsThatOnlyLandWhenTheOperatorApplies() {
        FakeAnalyst analyst = install(
            """
            {"questions":[{"subject":"Volume","text":"How many users?","kind":"CHOICE",\
            "options":["<1k",">1M"]}]}""",
            proposalsReply());
        SourceDocument document = upload("pricing.md",
            "A guest must be able to pay. Pricing is per seat, billed monthly.");

        FlowView opened = service.intake();
        assertThat(opened.flow()).isNotNull();
        assertThat(opened.flow().state()).isEqualTo(GuidedFlowState.DRAFT);
        String flowId = opened.flow().id().toString();

        assertThat(service.addDocument(flowId, document.id().toString(),
            "authoritative for pricing")).isEmpty();
        assertThat(service.start(flowId)).isEmpty();

        // --- the question round ---------------------------------------------------------------
        GuidedFlow awaiting = await(flowId, GuidedFlowState.AWAITING_ANSWERS);
        assertThat(awaiting.stepLabel()).contains("1 question");
        List<FlowQuestion> questions = store.listFlowQuestions(awaiting.id());
        assertThat(questions).hasSize(1);
        FlowQuestion question = questions.get(0);
        assertThat(question.subject()).isEqualTo("Volume");
        assertThat(question.text()).isEqualTo("How many users?");
        assertThat(question.kind()).isEqualTo(FlowQuestionKind.CHOICE);
        assertThat(question.options()).containsExactly("<1k", ">1M");
        assertThat(question.answer()).isNull();

        assertThat(service.answer(flowId, question.id().toString(), "<1k")).isEmpty();
        assertThat(store.listFlowQuestions(awaiting.id()).get(0).answer()).isEqualTo("<1k");

        // The operator's note travels with the document, and the answer with the question.
        assertThat(service.submitAnswers(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        assertThat(analyst.prompt(0)).contains("authoritative for pricing");
        assertThat(analyst.prompt(1)).contains("ANSWER: <1k");

        // --- the review -------------------------------------------------------------------------
        List<FlowProposal> proposals = store.listFlowProposals(awaiting.id());
        assertThat(proposals).hasSize(2);
        FlowProposal add = proposals.get(0);
        assertThat(add.kind()).isEqualTo(FlowProposalKind.ADD);
        assertThat(add.title()).isEqualTo("Guest checkout");
        assertThat(add.rationale()).contains("guest must be able to pay");
        // Pre-accepted so the common case is one click; nothing about that touches the BRD.
        assertThat(add.accepted()).isTrue();

        // The EDIT names R7, which this BRD has never had. That is caught while the operator is
        // still reviewing — unticked and explained — rather than at Apply, when a half-written
        // batch and an error string are all they would get.
        FlowProposal stale = proposals.get(1);
        assertThat(stale.kind()).isEqualTo(FlowProposalKind.EDIT);
        assertThat(stale.accepted()).as("an unapplicable proposal must not be pre-accepted").isFalse();
        assertThat(stale.impact()).contains("R7");

        // The whole point of proposals: at REVIEW the BRD has not been written to at all.
        Brd beforeApply = store.getBrd(projectId);
        assertThat(beforeApply == null ? List.of() : beforeApply.requirements()).isEmpty();

        // --- apply --------------------------------------------------------------------------
        // Only the sound proposal is ticked, so applying is clean.
        String applied = service.apply(flowId);
        assertThat(applied).isEmpty();

        GuidedFlow finished = store.getGuidedFlow(awaiting.id());
        assertThat(finished.state()).isEqualTo(GuidedFlowState.APPLIED);
        assertThat(finished.stepLabel()).contains("1 change(s) applied");

        List<BrdRequirement> requirements = store.getBrd(projectId).requirements();
        assertThat(requirements).hasSize(1);
        BrdRequirement written = requirements.get(0);
        assertThat(written.handle()).isEqualTo("R1");
        assertThat(written.title()).isEqualTo("Guest checkout");
        assertThat(written.text()).contains("ASSUMPTION");
        // Criteria are what make a requirement verifiable; dropping them on apply would produce a
        // requirement nothing can ever prove.
        assertThat(written.criteria()).extracting(AcceptanceCriterion::text)
            .containsExactly("a purchase completes with no account", "an empty cart is rejected");
        // Still the agent's draft — apply is the operator accepting a proposal, not a promotion.
        assertThat(written.status()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(written.sourceRef().documentId()).isEqualTo(document.id());
    }

    // --- 2. skipping ------------------------------------------------------------------------------

    @Test
    void aSkippedQuestionDoesNotBlockAndIsToldToTheAnalystAsUnanswered() {
        FakeAnalyst analyst = install(
            """
            {"questions":[{"subject":"Retention","text":"How long are orders kept?",\
            "kind":"TEXT"}]}""",
            proposalsReply());
        SourceDocument document = upload("orders.md", "Orders are stored after checkout.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);

        GuidedFlow awaiting = await(flowId, GuidedFlowState.AWAITING_ANSWERS);
        FlowQuestion question = store.listFlowQuestions(awaiting.id()).get(0);
        assertThat(service.skip(flowId, question.id().toString())).isEmpty();
        assertThat(store.listFlowQuestions(awaiting.id()).get(0).skipped()).isTrue();

        assertThat(service.submitAnswers(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);

        // Skipping must not silently become a guess: the drafting call is told the question went
        // unanswered and that the assumption has to be stated on the requirement.
        assertThat(analyst.prompt(1))
            .contains("How long are orders kept?")
            .contains("NOT ANSWERED")
            .contains("ASSUMPTION");
        assertThat(analyst.prompt(1)).doesNotContain("ANSWER: ");
    }

    // --- 2b. a model that miscounts its braces -----------------------------------------------------

    /**
     * Verbatim shape of a real reply from the 27B model on 2026-08-28: two well-formed proposals,
     * and one extra closing brace between them that closes the whole reply three quarters of the
     * way through.
     *
     * <p>The whole analysis used to be lost to it. The flow failed with "the analyst proposed no
     * changes — the documents may not contain requirements", which told the operator something
     * false about their own document, and left them nothing to act on. Everything before the break
     * is readable and is now read; everything after it is discarded rather than guessed at.
     */
    @Test
    void anExtraBraceCostsWhatCameAfterItNotTheWholeAnalysis() {
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Multiply two whole numbers",\
            "rationale":"the document asks for it in the first line",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Arithmetic",\
            "text":"The system returns the product of two integers.",\
            "criteria":[{"text":"two positive integers give their product",\
            "test":"swarm.accept.ArithmeticTest#multipliesTwoPositives"}]}},\
            {"kind":"ADD","ref":"N2","title":"Edges of the range",\
            "rationale":"the document asks for sensible behaviour at the edges",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Arithmetic",\
            "text":"Multiplication at the range boundary does not produce undefined behaviour.",\
            "criteria":[{"text":"overflow is reported, not silently wrapped",\
            "test":"swarm.accept.ArithmeticTest#reportsOverflow"}]}\
            ]}""");
        SourceDocument document = upload("calculator.md", "The calculator multiplies.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);

        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals)
            .as("everything before the stray brace survives; the rest is dropped, not invented")
            .hasSize(1);
        assertThat(proposals.get(0).title()).isEqualTo("Multiply two whole numbers");
    }

    // --- 3. guard rails ---------------------------------------------------------------------------

    @Test
    void startNeedsDocumentsApplyNeedsAReviewAndChangingTheInputsInvalidatesOne() {
        install("{\"questions\":[]}", proposalsReply());
        SourceDocument first = upload("spec.md", "A guest must be able to pay.");
        SourceDocument second = upload("addendum.md", "Refunds are handled by support.");

        String flowId = service.intake().flow().id().toString();

        // Analysing nothing would produce requirements out of thin air.
        assertThat(service.start(flowId)).startsWith("error:").contains("document");
        assertThat(store.getGuidedFlow(UUID.fromString(flowId)).state())
            .isEqualTo(GuidedFlowState.DRAFT);
        assertThat(service.apply(flowId)).startsWith("error:").contains("apply");

        service.addDocument(flowId, first.id().toString(), null);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);
        assertThat(store.listFlowProposals(reviewing.id())).isNotEmpty();

        // A review assembled from one document set must not survive a change to that set: the
        // operator would otherwise apply proposals drawn from a reading that no longer holds.
        assertThat(service.addDocument(flowId, second.id().toString(), "background only")).isEmpty();
        GuidedFlow reset = store.getGuidedFlow(reviewing.id());
        assertThat(reset.state()).isEqualTo(GuidedFlowState.DRAFT);
        assertThat(reset.documents()).hasSize(2);
        assertThat(store.listFlowProposals(reviewing.id())).isEmpty();
        assertThat(store.listFlowQuestions(reviewing.id())).isEmpty();
        assertThat(service.apply(flowId)).startsWith("error:").contains("apply");
    }

    // --- 4. reading what the model actually sends -------------------------------------------------

    @Test
    void aOneOptionChoiceBecomesTextAndAFencedReplyWrappedInProseIsStillRead() {
        install("""
            Here is the one thing I could not settle from the documents.

            ```json
            {"questions":[{"subject":"Volume","text":"How many users?","kind":"CHOICE",\
            "options":["a lot"]}]}
            ```

            Everything else was clear enough to assume.""",
            proposalsReply());
        SourceDocument document = upload("spec.md", "A guest must be able to pay.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);

        GuidedFlow awaiting = await(flowId, GuidedFlowState.AWAITING_ANSWERS);
        List<FlowQuestion> questions = store.listFlowQuestions(awaiting.id());
        assertThat(questions).hasSize(1);
        FlowQuestion question = questions.get(0);
        assertThat(question.text()).isEqualTo("How many users?");
        // A radio group with a single button is not a question. The mislabel is corrected rather
        // than rendered.
        assertThat(question.kind()).isEqualTo(FlowQuestionKind.TEXT);
        assertThat(question.options()).isEmpty();
    }

    // --- 5. every document reaches the model, in full ----------------------------------------------

    @Test
    void everyDocumentIsSentWholeAndNothingIsSilentlyDropped() {
        FakeAnalyst analyst = install("{\"questions\":[]}", proposalsReply());
        // A long specification, well under the default ceiling. Its marker is at the very top and
        // the note's is in a document added after it — the pair that used to fail, because the
        // budget was spent in order and whatever came second was dropped.
        SourceDocument huge = upload("huge-spec.md", filler(BIG_MARKER, 90_000));
        // The kind of thing an operator pastes in as an afterthought, and the thing that vanished.
        SourceDocument note = upload("nfr-note.md", pastedNote());

        String flowId = service.intake().flow().id().toString();
        assertThat(service.addDocument(flowId, huge.id().toString(), null)).isEmpty();
        assertThat(service.addDocument(flowId, note.id().toString(), null)).isEmpty();
        assertThat(service.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);

        // The drafting call is the one that decides what gets proposed; if the note is not in it,
        // the operator's paragraph of NFRs was collected, displayed, and then thrown away.
        String drafting = analyst.prompt(1);
        assertThat(drafting).as("the small pasted note must reach the model")
            .contains(SMALL_MARKER);
        assertThat(drafting).as("the long specification must reach the model")
            .contains(BIG_MARKER);
        // The whole of it, not a prefix: nothing may be cut behind the operator's back.
        assertThat(drafting).as("no document may be truncated").doesNotContain("truncated");
        assertThat(drafting).contains(filler(BIG_MARKER, 90_000).substring(89_000));
    }

    // --- 6. too much to read refuses to start, and says so -----------------------------------------

    @Test
    void documentsOverTheLimitRefuseToStartInsteadOfBeingPartlyRead() {
        install("{\"questions\":[]}", proposalsReply());
        SourceDocument huge = upload("huge-spec.md", filler(BIG_MARKER, 90_000));
        SourceDocument note = upload("nfr-note.md", pastedNote());

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, huge.id().toString(), null);
        service.addDocument(flowId, note.id().toString(), null);
        // A ceiling the pair cannot fit under. Reading part of them would produce a confident,
        // incomplete BRD with no sign anything was missing — so the analysis must not run at all.
        assertThat(service.setCharacterBudget(flowId, 50_000)).isEmpty();

        String refusal = service.start(flowId);
        assertThat(refusal).startsWith("error:");
        // The operator gets both numbers and both ways out, not a bare refusal.
        assertThat(refusal).contains("50000").contains("raise the limit").contains("remove a document");
        assertThat(store.getGuidedFlow(UUID.fromString(flowId)).state())
            .as("a refused start leaves the flow editable, not running")
            .isEqualTo(GuidedFlowState.DRAFT);

        // Raising it is all it takes — the same documents, now readable.
        assertThat(service.setCharacterBudget(flowId, 200_000)).isEmpty();
        assertThat(service.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
    }

    // --- 7. a re-proposed requirement is flagged, not applied --------------------------------------

    @Test
    void anAddThatRepeatsAnExistingRequirementIsUntickedAndSaysWhich() {
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","handle":"","title":"guest checkout!",\
            "rationale":"the spec says a guest must be able to pay",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Checkout",\
            "text":"A guest can complete a purchase without an account.",\
            "criteria":["a purchase completes with no account"]},\
            {"kind":"ADD","handle":"","title":"Refund window",\
            "rationale":"the spec gives customers 30 days",\
            "priority":"MEDIUM","requirementKind":"FUNCTIONAL","category":"Refunds",\
            "text":"A customer can request a refund within 30 days of purchase.",\
            "criteria":["a request on day 30 is accepted"]}\
            ]}""");
        // The BRD already holds it — a second run over the same document is the normal way this
        // happens, and the operator must not end up with two of everything.
        String existing = BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
            "A guest can complete a purchase without an account", "HIGH", null, null, null, null);
        assertThat(existing).startsWith("R1");
        SourceDocument document = upload("spec.md",
            "A guest must be able to pay. Refunds are accepted for 30 days.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);

        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(2);

        // Same words, different case and punctuation — still the same requirement.
        FlowProposal duplicate = proposals.get(0);
        assertThat(duplicate.title()).isEqualTo("guest checkout!");
        assertThat(duplicate.accepted()).isFalse();
        // Naming the handle is what lets the operator go and look at the one that already exists
        // and decide whether this really is a different requirement.
        assertThat(duplicate.rationale()).contains("R1");

        // Flagging must not become a blanket refusal: the genuinely new one is still one click away.
        FlowProposal fresh = proposals.get(1);
        assertThat(fresh.title()).isEqualTo("Refund window");
        assertThat(fresh.accepted()).isTrue();

        assertThat(reviewing.stepLabel()).contains("duplicate");

        // Applying now writes the new one only — the flagged one stays out until it is ticked.
        assertThat(service.apply(flowId)).isEmpty();
        assertThat(store.getBrd(projectId).requirements())
            .extracting(BrdRequirement::title)
            .containsExactly("Guest checkout", "Refund window");
    }

    // --- 8. a second run reads only what is new ----------------------------------------------------

    @Test
    void aSecondRunReadsOnlyTheNewDocumentButStillSeesTheBrdTheFirstOneWrote() {
        // Two runs, each of which asks its (empty) round of questions first, so the drafting calls
        // land on prompt 1 and prompt 3.
        FakeAnalyst analyst = install("{\"questions\":[]}", proposalsReply(),
            "{\"questions\":[]}", refundProposalReply());
        SourceDocument alpha = upload("alpha.md",
            ALPHA_MARKER + "\nA guest must be able to pay.");

        String flowId = service.intake().flow().id().toString();
        assertThat(service.addDocument(flowId, alpha.id().toString(), null)).isEmpty();
        assertThat(service.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        assertThat(analyst.prompt(1)).as("the first run must read alpha").contains(ALPHA_MARKER);
        assertThat(service.apply(flowId)).isEmpty();

        // Applying is what marks a document absorbed: it now has a time and drops out of the next
        // run. Without both, the next analysis re-reads material that is already in the BRD.
        GuidedFlow applied = flowOf(flowId);
        assertThat(applied.state()).isEqualTo(GuidedFlowState.APPLIED);
        FlowDocument absorbed = entryFor(applied, alpha.id());
        assertThat(absorbed.analysedAt()).as("an applied document must carry when it was read")
            .isNotNull();
        assertThat(absorbed.analysed()).isTrue();
        assertThat(absorbed.excluded()).as("an applied document must drop out of the next run")
            .isTrue();
        assertThat(absorbed.included()).isFalse();
        assertThat(RequirementsIntake.includedDocuments(applied)).isZero();

        // --- the whole point: adding one document costs one document's worth of reading ---------
        SourceDocument beta = upload("beta.md",
            BETA_MARKER + "\nRefunds are accepted for 30 days after purchase.");
        assertThat(service.addDocument(flowId, beta.id().toString(), null)).isEmpty();

        GuidedFlow ready = flowOf(flowId);
        assertThat(ready.state()).isEqualTo(GuidedFlowState.DRAFT);
        assertThat(ready.documents()).as("alpha stays attached, it is only not read again").hasSize(2);
        assertThat(RequirementsIntake.includedDocuments(ready)).isEqualTo(1);
        // The step line counts what will be READ, not what is attached — two documents, one run.
        assertThat(ready.stepLabel()).contains("Ready to analyse 1 document(s)");
        assertThat(RequirementsIntake.totalCharacters(store, ready))
            .as("only the new document's characters are budgeted for")
            .isEqualTo(store.getSourceDocument(beta.id()).extractedText().length());
        assertThat(RequirementsIntake.brief(store, ready))
            .contains(BETA_MARKER)
            .doesNotContain(ALPHA_MARKER);

        assertThat(service.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);

        String drafting = analyst.prompt(3);
        assertThat(drafting).as("the new document must be read").contains(BETA_MARKER);
        assertThat(drafting).as("the absorbed document must not be re-read")
            .doesNotContain(ALPHA_MARKER);
        assertThat(drafting).as("nor even named, since it is not in the briefing at all")
            .doesNotContain("alpha.md");
        // Not re-reading the source is only safe because the model still sees what came OUT of it:
        // merging against the BRD is how the second run avoids proposing R1 all over again.
        // The heading now states how much of the graph was sent (§21). On a project this small
        // that is all of it, and it says so.
        assertThat(drafting).contains("CURRENT BRD (all 1 requirement(s)):");
        assertThat(drafting).as("the requirement the first apply wrote must still be visible")
            .contains("R1")
            .contains("Guest checkout");
    }

    // --- 9. ticking an analysed document back in ---------------------------------------------------

    @Test
    void anAnalysedDocumentCanBeReadAgainAndAnEmptyRunIsRefused() {
        // Three replies, not four: the second run re-reads a document that has already been
        // analysed, so there is no question round and the drafting call comes straight after.
        // Re-interviewing the operator about material they have already been through is exactly
        // what that asymmetry exists to prevent.
        FakeAnalyst analyst = install("{\"questions\":[]}", proposalsReply(),
            refundProposalReply());
        SourceDocument alpha = upload("alpha.md",
            ALPHA_MARKER + "\nA guest must be able to pay.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, alpha.id().toString(), null);
        assertThat(service.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        assertThat(service.apply(flowId)).isEmpty();
        // The operator re-opens the wizard to run intake again — the standing activity, back at
        // DRAFT, still holding the one document it has already absorbed.
        assertThat(service.reopen(flowId)).isEmpty();
        assertThat(flowOf(flowId).state()).isEqualTo(GuidedFlowState.DRAFT);
        assertThat(RequirementsIntake.includedDocuments(flowOf(flowId))).isZero();

        // Starting now would send the model an empty briefing and let it invent requirements from
        // the BRD alone. It is refused, and the refusal says what to do about it.
        String refusal = service.start(flowId);
        assertThat(refusal).startsWith("error:")
            .contains("already been analysed")
            .contains("tick");
        assertThat(flowOf(flowId).state())
            .as("a refused start leaves the flow editable, not running")
            .isEqualTo(GuidedFlowState.DRAFT);
        assertThat(analyst.calls()).as("a refused start must not reach the model").isEqualTo(2);

        // --- ticked back in: the document's meaning changed, so read it again -------------------
        assertThat(service.setDocumentIncluded(flowId, alpha.id().toString(), true)).isEmpty();
        GuidedFlow reincluded = flowOf(flowId);
        assertThat(RequirementsIntake.includedDocuments(reincluded)).isEqualTo(1);
        FlowDocument entry = entryFor(reincluded, alpha.id());
        assertThat(entry.included()).isTrue();
        assertThat(entry.analysedAt()).as("ticking it in does not forget that it was analysed")
            .isNotNull();
        assertThat(reincluded.stepLabel()).contains("Ready to analyse 1 document(s)");

        assertThat(service.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        assertThat(analyst.prompt(2)).as("a re-ticked document really is read again")
            .contains(ALPHA_MARKER);
        assertThat(analyst.calls())
            .as("a re-read asks nothing — the third call is the drafting one")
            .isEqualTo(3);
    }

    // --- 10. discussing a question before answering it ---------------------------------------------

    @Test
    void aQuestionCanBeDiscussedAndWhatIsSettledLandsOnTheRightFieldForItsKind() {
        FakeAnalyst analyst = install(
            """
            {"questions":[\
            {"subject":"Retention","text":"How long are orders kept?","kind":"TEXT",\
            "sourceQuote":"Orders are stored after checkout.","sourceDocument":"orders.md",\
            "background":"The document says orders are stored but never for how long."},\
            {"subject":"Volume","text":"How many users?","kind":"CHOICE","options":["<1k",">1M"]}\
            ]}""",
            "orders.md says only that orders are stored after checkout — it gives no period at all. "
                + "What turns on it is the purge job and the storage estimate.",
            proposalsReply());
        SourceDocument document = upload("orders.md",
            ORDERS_MARKER + "\nOrders are stored after checkout.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);
        GuidedFlow awaiting = await(flowId, GuidedFlowState.AWAITING_ANSWERS);

        List<FlowQuestion> questions = store.listFlowQuestions(awaiting.id());
        assertThat(questions).hasSize(2);
        FlowQuestion free = questions.get(0);
        FlowQuestion choice = questions.get(1);
        assertThat(free.kind()).isEqualTo(FlowQuestionKind.TEXT);
        assertThat(choice.kind()).isEqualTo(FlowQuestionKind.CHOICE);

        String freeId = free.id().toString();
        assertThat(service.discussion(flowId, freeId))
            .as("a question nobody has discussed has no transcript").isEmpty();

        // --- the operator asks the question back ------------------------------------------------
        assertThat(service.discuss(flowId, freeId, "  Why are you asking? ")).isEmpty();
        List<FlowDiscussionTurn> turns = awaitTurns(flowId, freeId, 2);

        // The operator's turn is recorded before the model is even called, so a slow or failing
        // analyst cannot lose what they said.
        assertThat(turns.get(0).role()).isEqualTo(FlowDiscussionTurn.YOU);
        assertThat(turns.get(0).fromOperator()).isTrue();
        assertThat(turns.get(0).text()).isEqualTo("Why are you asking?");
        assertThat(turns.get(0).at()).isNotNull();
        assertThat(turns.get(1).role()).isEqualTo(FlowDiscussionTurn.ANALYST);
        assertThat(turns.get(1).fromOperator()).isFalse();
        assertThat(turns.get(1).text()).contains("it gives no period at all");

        // Keyed by QUESTION, not by flow: the store is the source of truth, and reading it back
        // through the store must give exactly what the service handed the client.
        assertThat(store.listFlowDiscussion(free.id())).isEqualTo(turns);
        assertThat(store.listFlowDiscussion(choice.id()))
            .as("a discussion belongs to one question, not to the round").isEmpty();

        // --- what the analyst was actually given ------------------------------------------------
        // Grounded, or it can only paraphrase its own question more confidently — which is the
        // exact failure the operator opened the conversation to escape.
        String discussion = analyst.prompt(1);
        assertThat(discussion).as("the question it is explaining")
            .contains("How long are orders kept?");
        assertThat(discussion).as("the wording it quoted when it asked")
            .contains("Orders are stored after checkout.");
        assertThat(discussion).as("the document the quote came from").contains("orders.md");
        assertThat(discussion).as("the background it wrote").contains("never for how long");
        assertThat(discussion).as("the document text itself, so it can go and check")
            .contains(ORDERS_MARKER);
        assertThat(discussion).as("the BRD as it stands").contains("CURRENT BRD");
        assertThat(discussion).as("what was said, and who said it").contains("THEM: Why are you asking?");
        assertThat(discussion).as("it may explain, not renegotiate")
            .contains("NOT RENEGOTIATING THE REQUIREMENTS");
        assertThat(discussion).as("and must admit when the documents do not settle it")
            .contains("SAY SO PLAINLY");
        // Talking is not answering. Nothing about the flow or the question has moved.
        assertThat(flowOf(flowId).state()).isEqualTo(GuidedFlowState.AWAITING_ANSWERS);
        assertThat(store.listFlowQuestions(awaiting.id()).get(0).answer()).isNull();

        // --- capturing what it settled ----------------------------------------------------------
        // A TEXT question has nowhere else to put it: the resolution IS the answer.
        String settled = turns.get(1).text();
        assertThat(service.answer(flowId, freeId, settled)).isEmpty();
        FlowQuestion captured = store.listFlowQuestions(awaiting.id()).get(0);
        assertThat(captured.answer()).isEqualTo(settled);
        assertThat(captured.note()).isNull();
        assertThat(captured.skipped()).isFalse();

        // A CHOICE keeps its picked option as the answer — that is the machine-readable part — so
        // what the conversation settled goes in the note beside it.
        assertThat(service.answer(flowId, choice.id().toString(), "<1k")).isEmpty();
        assertThat(service.note(flowId, choice.id().toString(), "only while we stay on one region"))
            .isEmpty();
        FlowQuestion picked = store.listFlowQuestions(awaiting.id()).get(1);
        assertThat(picked.answer()).as("the option stays the answer").isEqualTo("<1k");
        assertThat(picked.note()).isEqualTo("only while we stay on one region");

        // --- and it survives into the drafting call ----------------------------------------------
        assertThat(service.submitAnswers(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        assertThat(analyst.prompt(2)).contains("ANSWER: " + settled)
            .contains("THEY ALSO SAID: only while we stay on one region");
    }

    @Test
    void anEmptyMessageAndAQuestionThatIsGoneAreRefusedRatherThanSentToTheModel() {
        FakeAnalyst analyst = install(
            """
            {"questions":[{"subject":"Retention","text":"How long are orders kept?",\
            "kind":"TEXT"}]}""",
            "anything");
        SourceDocument document = upload("orders.md", "Orders are stored after checkout.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);
        GuidedFlow awaiting = await(flowId, GuidedFlowState.AWAITING_ANSWERS);
        FlowQuestion question = store.listFlowQuestions(awaiting.id()).get(0);
        String questionId = question.id().toString();

        assertThat(service.discuss(flowId, questionId, "   ")).startsWith("error:")
            .contains("type something first");
        assertThat(service.discuss(flowId, UUID.randomUUID().toString(), "hello"))
            .startsWith("error:").contains("no such question");
        assertThat(service.discussion(flowId, UUID.randomUUID().toString()))
            .as("an unknown question has no transcript, and asking is not an error").isEmpty();
        assertThat(analyst.calls()).as("a refused discussion must not reach the model").isEqualTo(1);

        // --- a discussion dies with the question it explains --------------------------------------
        assertThat(service.discuss(flowId, questionId, "Why are you asking?")).isEmpty();
        awaitTurns(flowId, questionId, 2);
        // Clearing the round leaves the transcript unreachable by any id the operator still has.
        // It is deleted, not orphaned — discussions are keyed by question, not by flow.
        assertThat(service.startOver(flowId)).isEmpty();
        assertThat(store.listFlowDiscussion(question.id())).isEmpty();
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** Distinctive enough that finding it in a prompt cannot be a coincidence. */
    private static final String BIG_MARKER = "ZZTOP_SPEC_MARKER";
    private static final String ORDERS_MARKER = "ZZTOP_ORDERS_MARKER";

    /** Waits for a question's transcript to reach {@code expected} turns — the reply is async. */
    private List<FlowDiscussionTurn> awaitTurns(String flowId, String questionId, int expected) {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        List<FlowDiscussionTurn> turns = List.of();
        while (System.currentTimeMillis() < deadline) {
            turns = service.discussion(flowId, questionId);
            if (turns.size() >= expected) {
                return turns;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + expected + " turn(s)", e);
            }
        }
        throw new AssertionError("question " + questionId + " never reached " + expected
            + " discussion turn(s) within " + WAIT_MILLIS + "ms — it had " + turns.size());
    }

    private static final String SMALL_MARKER = "ZZTOP_NFR_MARKER";
    private static final String ALPHA_MARKER = "ZZTOP_ALPHA_MARKER";
    private static final String BETA_MARKER = "ZZTOP_BETA_MARKER";

    /** A single ADD that lands cleanly, for the second run over a second document. */
    private static String refundProposalReply() {
        return """
            {"proposals":[\
            {"kind":"ADD","handle":"","title":"Refund window",\
            "rationale":"the addendum gives customers 30 days",\
            "priority":"MEDIUM","requirementKind":"FUNCTIONAL","category":"Refunds",\
            "text":"A customer can request a refund within 30 days of purchase.",\
            "criteria":["a request on day 30 is accepted"]}\
            ]}""";
    }

    /** The persisted flow, re-read rather than remembered — the store is the source of truth. */
    private GuidedFlow flowOf(String flowId) {
        GuidedFlow flow = store.getGuidedFlow(UUID.fromString(flowId));
        assertThat(flow).as("flow " + flowId + " is not in the store").isNotNull();
        return flow;
    }

    /** The flow's entry for one attached document, failing by name rather than with an index. */
    private static FlowDocument entryFor(GuidedFlow flow, UUID documentId) {
        for (FlowDocument entry : flow.documents()) {
            if (documentId.equals(entry.documentId())) {
                return entry;
            }
        }
        throw new AssertionError("document " + documentId + " is not attached to flow " + flow.id());
    }

    /** A document of roughly {@code chars} characters, marked at the very top. */
    private static String filler(String marker, int chars) {
        StringBuilder sb = new StringBuilder(chars + 128);
        sb.append(marker).append('\n');
        while (sb.length() < chars) {
            sb.append("The system records every order line, its price and the tax applied.\n");
        }
        return sb.toString();
    }

    /** A few hundred characters of pasted non-functional requirements. */
    private static String pastedNote() {
        return SMALL_MARKER + """

            Personal data must be encrypted at rest and purged 90 days after the order closes.
            The checkout page must respond in under 300ms at p95 under 100 concurrent shoppers.
            The service must stay available during a single availability-zone outage.
            Every administrative action must be recorded in an audit log the operator can read.""";
    }

    // --- the proposed test reference ------------------------------------------------------------

    /**
     * A proposal stored before "check" replaced "criterion" still applies, checks and all.
     *
     * <p>The review block is text: it is stored as the operator reviewed it and parsed back when
     * they apply it. Every proposal sitting in a store from before the wording changed carries
     * {@code Criterion:} lines, so the parser reads both keys. Without that it would not have
     * failed — it would have written a requirement with no checks on it at all, which is a
     * requirement nothing can ever verify and no story can ever be sliced from, and no screen
     * anywhere would have said a word about it.
     */
    @Test
    void aProposalStoredWithTheOldCriterionKeywordStillApplies() {
        install("""
            {"questions":[]}""", """
            {"proposals":[{"kind":"ADD","ref":"N1","title":"Guest checkout",\
            "rationale":"the spec says a guest must be able to pay",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Checkout",\
            "text":"A guest can complete a purchase without an account.",\
            "criteria":[{"text":"a purchase completes with no account",\
            "test":"swarm.accept.GuestCheckoutTest#checksOutWithoutAnAccount"}]}]}""");
        SourceDocument document = upload("pricing.md", "A guest must be able to pay.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        // Put the block back into the exact shape the previous version of the wizard wrote, then
        // apply it: a proposal reviewed before the change and applied after it.
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        proposals.get(0).setAfter(proposals.get(0).after().replace("Check: ", "Criterion: "));
        assertThat(proposals.get(0).after()).contains("Criterion: a purchase completes");
        store.saveFlowProposals(reviewing.id(), proposals);

        assertThat(service.apply(flowId)).isEmpty();

        List<AcceptanceCriterion> criteria =
            store.getBrd(projectId).requirements().get(0).criteria();
        assertThat(criteria)
            .describedAs("the old keyword is still read, so the checks still land")
            .extracting(AcceptanceCriterion::text)
            .containsExactly("a purchase completes with no account");
        assertThat(criteria).extracting(AcceptanceCriterion::testClassOrFile)
            .containsExactly("swarm.accept.GuestCheckoutTest#checksOutWithoutAnAccount");
    }

    /**
     * The wizard proposes the NAME of the test that will prove each check, the operator can see it
     * in the proposal block before anything is written, and it lands marked as a proposal.
     *
     * <p>Before this, both of the wizard's call sites wrote {@code testClassOrFile = null}, and the
     * one string the whole requirement-to-commit trace is looked up by was typed by a person into
     * the editor and checked against nothing (§15.5). It is still the operator's to settle — the
     * point is that they now settle a suggestion rather than invent one from scratch, and that the
     * editor can tell which is which.
     */
    @Test
    void theWizardProposesTheTestThatWillProveEachCheckAndSaysItIsOnlyAProposal() {
        install("""
            {"questions":[]}""", """
            {"proposals":[{"kind":"ADD","ref":"N1","title":"Guest checkout",\
            "rationale":"the spec says a guest must be able to pay",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Checkout",\
            "text":"A guest can complete a purchase without an account.",\
            "criteria":[{"text":"a purchase completes with no account",\
            "test":"swarm.accept.GuestCheckoutTest#checksOutWithoutAnAccount"},\
            {"text":"an empty cart is rejected",\
            "test":"swarm.accept.GuestCheckoutTest#rejectsAnEmptyCart"}]}]}""");
        SourceDocument document = upload("pricing.md", "A guest must be able to pay.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        // The operator reviews the block, so the proposed test name has to be IN the block. A name
        // they cannot see in the diff is a name they never agreed to, and this one decides what
        // proves the requirement.
        FlowProposal proposal = store.listFlowProposals(reviewing.id()).get(0);
        assertThat(proposal.after())
            .contains("Check: a purchase completes with no account")
            .contains("Test: swarm.accept.GuestCheckoutTest#checksOutWithoutAnAccount")
            .contains("Test: swarm.accept.GuestCheckoutTest#rejectsAnEmptyCart");

        assertThat(service.apply(flowId)).isEmpty();

        List<AcceptanceCriterion> criteria =
            store.getBrd(projectId).requirements().get(0).criteria();
        assertThat(criteria).extracting(AcceptanceCriterion::testClassOrFile).containsExactly(
            "swarm.accept.GuestCheckoutTest#checksOutWithoutAnAccount",
            "swarm.accept.GuestCheckoutTest#rejectsAnEmptyCart");
        assertThat(criteria).allMatch(AcceptanceCriterion::testRefIsProposal);
        assertThat(criteria).extracting(AcceptanceCriterion::testRefOrigin)
            .as("a suggestion, not a decision — the editor has to be able to say so")
            .containsOnly(com.swarmcoder.domain.TestRefOrigin.PROPOSED);
    }

    /**
     * The operator changing the reference makes it theirs; saving the row without touching it does
     * not. Otherwise editing the wording would silently convert the wizard's untouched guess into
     * the operator's decision, erasing the very warning that asked them to look at it.
     */
    @Test
    void anOperatorEditOfTheReferenceMakesItTheirsAndAnUntouchedOneStaysAProposal() {
        install("""
            {"questions":[]}""", """
            {"proposals":[{"kind":"ADD","ref":"N1","title":"Guest checkout","rationale":"r",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","text":"A guest can pay.",\
            "criteria":[{"text":"a purchase completes with no account",\
            "test":"swarm.accept.GuestCheckoutTest#guessedName"},\
            {"text":"an empty cart is rejected",\
            "test":"swarm.accept.GuestCheckoutTest#rejectsAnEmptyCart"}]}]}""");
        SourceDocument document = upload("pricing.md", "A guest must be able to pay.");
        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);
        await(flowId, GuidedFlowState.REVIEW);
        assertThat(service.apply(flowId)).isEmpty();

        BrdServiceImpl brd = new BrdServiceImpl();
        BrdRequirement requirement = store.getBrd(projectId).requirements().get(0);

        // Untouched reference, wording edited: still the wizard's.
        AcceptanceCriterion untouched = requirement.criteria().get(1);
        AcceptanceCriterion reworded = new AcceptanceCriterion(untouched.id(),
            "an empty cart is refused", untouched.testClassOrFile());
        reworded.setStatus(untouched.status());
        assertThat(brd.saveCriterion(requirement.id().toString(), reworded)).isEmpty();

        // Reference corrected: now the operator's.
        AcceptanceCriterion guessed = requirement.criteria().get(0);
        AcceptanceCriterion corrected = new AcceptanceCriterion(guessed.id(), guessed.text(),
            "swarm.accept.GuestCheckoutTest#checksOutWithoutAnAccount");
        corrected.setStatus(guessed.status());
        assertThat(brd.saveCriterion(requirement.id().toString(), corrected)).isEmpty();

        List<AcceptanceCriterion> after = store.getBrd(projectId).requirements().get(0).criteria();
        assertThat(after.get(0).testRefOrigin())
            .isEqualTo(com.swarmcoder.domain.TestRefOrigin.OPERATOR);
        assertThat(after.get(0).testClassOrFile())
            .isEqualTo("swarm.accept.GuestCheckoutTest#checksOutWithoutAnAccount");
        assertThat(after.get(1).testRefOrigin())
            .as("nobody looked at this one, so it must keep saying so")
            .isEqualTo(com.swarmcoder.domain.TestRefOrigin.PROPOSED);
    }

    /** One ADD that must land, and one EDIT naming a handle the BRD does not have. */
    private static String proposalsReply() {
        return """
            {"proposals":[\
            {"kind":"ADD","handle":"","title":"Guest checkout",\
            "rationale":"the spec says a guest must be able to pay",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Checkout",\
            "text":"A guest can complete a purchase without an account. ASSUMPTION: volumes are \
            under 1k users.",\
            "criteria":["a purchase completes with no account","an empty cart is rejected"]},\
            {"kind":"EDIT","handle":"R7","title":"Saved cards","before":"cards may be saved",\
            "priority":"MEDIUM","requirementKind":"FUNCTIONAL",\
            "text":"A returning customer can save a card","criteria":[]}\
            ]}""";
    }

    private FakeAnalyst install(String... replies) {
        FakeAnalyst analyst = new FakeAnalyst(replies);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withChat(analyst));
        return analyst;
    }

    private SourceDocument upload(String filename, String text) {
        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId, filename,
            "text/markdown", text.getBytes(StandardCharsets.UTF_8));
        assertThat(result.failed()).isFalse();
        return result.document();
    }

    /**
     * Waits for the analysis thread to reach a state, bounded. Polls the persisted flow rather than
     * sleeping a fixed time: a fixed sleep either flakes on a slow machine or wastes the difference
     * on a fast one, and neither says what went wrong.
     */
    private GuidedFlow await(String flowId, GuidedFlowState expected) {
        UUID id = UUID.fromString(flowId);
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        GuidedFlowState seen = null;
        while (System.currentTimeMillis() < deadline) {
            GuidedFlow flow = store.getGuidedFlow(id);
            if (flow != null) {
                seen = flow.state();
                if (seen == expected) {
                    return flow;
                }
                if (seen == GuidedFlowState.FAILED && expected != GuidedFlowState.FAILED) {
                    throw new AssertionError("flow " + flowId + " FAILED while waiting for "
                        + expected + ": " + flow.error());
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + expected, e);
            }
        }
        throw new AssertionError("flow " + flowId + " never reached " + expected
            + " within " + WAIT_MILLIS + "ms — last state was " + seen);
    }

    /**
     * A scripted analyst: hands back the next canned reply and keeps every prompt it was given, so a
     * test can assert on what the flow actually told the model rather than only on what came back.
     */
    private static final class FakeAnalyst implements ConsoleContext.ChatModel {

        private final List<String> replies;
        private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger call = new AtomicInteger();

        FakeAnalyst(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public Stream<String> stream(List<Map<String, String>> messages, String modelOverride) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> message : messages) {
                sb.append(message.get("role")).append(": ")
                    .append(message.get("content")).append('\n');
            }
            prompts.add(sb.toString());
            int index = call.getAndIncrement();
            return Stream.of(index < replies.size() ? replies.get(index)
                : replies.get(replies.size() - 1));
        }

        /** How many times the model was called at all — so "it was not asked" can be asserted. */
        int calls() {
            return prompts.size();
        }

        /** Everything sent on the n-th call, roles included. */
        String prompt(int index) {
            assertThat(prompts.size()).as("model calls made").isGreaterThan(index);
            return prompts.get(index);
        }
    }
}
