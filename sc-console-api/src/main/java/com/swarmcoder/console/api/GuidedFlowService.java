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

import com.zeroz4j.api.RmiService;
import com.zeroz4j.api.Secured;

/**
 * The guided-flow surface (docs/GUIDED_FLOWS_DESIGN.md) — the wizard behind the Requirements panel's
 * "Analyse documents" button, and the replacement for the now-removed {@code /brd} chat mode.
 *
 * <p>A flow <em>runs on the server and is persisted</em>; the dialog is a view of it. Extraction over
 * a real document takes minutes, so a flow that lived in the browser would be one the operator learns
 * not to open — closing the window would throw the work away. Here the wizard can be closed,
 * re-opened, or watched from a second tab, and a crash leaves a resumable record.
 *
 * <p>Consequently every method returns "" or "error: …" and the state the wizard renders arrives on
 * {@link GuidedFlowSignals#CURRENT}. Callers bind to the signal; they do not read these return values
 * for anything but failure.
 *
 * <p>Ids cross the wire as strings, matching {@link BrdService} — TeaVM has no usable {@code UUID}
 * bit-level API, so the browser treats them as opaque handles.
 */
@RmiService
@Secured
public interface GuidedFlowService {

    /**
     * The current project's requirements-intake flow, creating an empty {@code DRAFT} one if none
     * exists. Also publishes it on {@link GuidedFlowSignals#CURRENT}, which is how the wizard gets
     * its first frame — opening the dialog is the only thing the client has to do.
     */
    FlowView intake();

    // --- inputs (DRAFT) -------------------------------------------------------------------------

    /**
     * Adds an already-uploaded document to the flow. Adding the same document twice is a no-op
     * rather than an error, because a re-drop of the same file is a common accident.
     */
    String addDocument(String flowId, String documentId, String notes);

    /**
     * Documents already uploaded to this project that are NOT in the flow.
     *
     * <p>Uploads outlive any one analysis, so without this a document ingested before the flow
     * existed — or removed from it earlier — is stored, invisible and unreachable. The wizard offers
     * these for re-adding rather than making the operator find and upload the file again.
     */
    java.util.List<com.swarmcoder.domain.SourceDocument> availableDocuments(String flowId);

    /**
     * Sets the operator's notes for one document — what it is authoritative for, what to ignore,
     * that it is the superseded spec. These are handed to the agent alongside the text, and are the
     * piece that turns a pile of documents into a briefing.
     */
    String setNotes(String flowId, String documentId, String notes);

    /**
     * Includes or excludes one document from the NEXT analysis.
     *
     * <p>A document drops out automatically once an apply has turned it into requirements, so
     * adding one file to a project with five costs one file's worth of reading. Tick an analysed
     * document back in when its meaning has changed and you want it read against the current BRD.
     */
    String setDocumentIncluded(String flowId, String documentId, boolean included);

    /**
     * Marks one document as saying how the system must be BUILT rather than what it must DO.
     *
     * <p>The operator sets this when they attach the file, because it is one bit they already
     * know and the analyst does not. It makes the analyst look for standing rules — the stack, the
     * layout, the transport, what is forbidden — and propose them as constraints, which are never
     * delivered and never assigned to a task. It also lets the analyst notice when a project has
     * no such document at all and ask about it, which is the case that costs the most: told
     * nothing, every agent on the project falls back on whatever a project of that shape usually
     * is.
     */
    String setDocumentTechnical(String flowId, String documentId, boolean technical);

    /** Removes a document from the flow. The uploaded {@code SourceDocument} itself is untouched. */
    String removeDocument(String flowId, String documentId);

    /**
     * Deletes an uploaded document from the project for good, and from any flow holding it.
     * Requirements already drawn from it are not touched — only their provenance goes stale.
     */
    String deleteDocument(String documentId);

    /**
     * Stores pasted text as a document and adds it to the flow.
     *
     * <p>A great deal of what a requirement starts from is an email, a chat excerpt or a page of
     * notes that exists as text and has no file. Making the operator save it to disk first, just so
     * it can be uploaded back, is a step that earns nothing.
     */
    String addPastedDocument(String flowId, String title, String text);

    /**
     * The extracted text of a document — what the analyst actually reads.
     *
     * <p>Deliberately the extraction rather than the original file: the original bytes are not kept,
     * and for a PDF or an image what matters when a requirement looks wrong is what was READ out of
     * it, not what it looked like. Returns "error: …" if there is no such document.
     */
    String documentText(String documentId);

    // --- running --------------------------------------------------------------------------------

    /**
     * Sets how much document text this analysis may send the model, in characters. 0 restores the
     * default.
     *
     * <p>The operator owns this number because only they know what model the Console is pointed at.
     * Raising it past the model's real context makes the call fail — visibly, as a failed analysis
     * with the model's own error, which is the honest outcome.
     */
    String setCharacterBudget(String flowId, int characters);

    /**
     * Starts (or restarts) analysis. Returns immediately — the flow moves to {@code RUNNING} and
     * progress arrives on the signal. Rejected if the flow has no documents, or is already running.
     */
    String start(String flowId);

    /** Abandons a running flow and returns it to {@code DRAFT}, keeping its documents. */
    String cancel(String flowId);

    // --- questions (AWAITING_ANSWERS) -----------------------------------------------------------

    /**
     * Records an answer to one clarification question. Answering does not resume the flow — a round
     * is a form, and {@link #submitAnswers} is its submit button.
     */
    String answer(String flowId, String questionId, String answer);

    /**
     * Records free-text qualification alongside the answer — "yes, but only once the gate closes".
     * Kept separate from {@link #answer} so a closed-ended choice stays machine-readable while the
     * operator can still say the thing the options do not cover.
     */
    String note(String flowId, String questionId, String note);

    /**
     * Marks a question as skipped. Skipping is first-class and never blocks: the agent must state the
     * assumption it made instead, so an unanswered question becomes visible and correctable rather
     * than a silent guess.
     */
    String skip(String flowId, String questionId);

    /**
     * The side conversation held about one question, oldest turn first. Empty when there is none.
     *
     * <p>Fetched on demand rather than carried on {@link FlowView}. A view is published on every
     * change — an answer, a note, a step label — and putting every question's transcript on it would
     * broadcast every conversation to say that one radio button moved. The wizard reads this when
     * the discussion is opened, and again while it is waiting for a reply.
     */
    java.util.List<com.swarmcoder.domain.FlowDiscussionTurn> discussion(String flowId,
                                                                       String questionId);

    /**
     * Says something to the analyst about one question, and asks it to reply.
     *
     * <p>Answering a clarification used to be take it or leave it. The response an operator actually
     * has is often "why are you asking?" or "what did the document say around this?" — and without
     * somewhere to say it, the only ways forward were to guess, to skip, or to go and read the
     * document themselves.
     *
     * <p>Returns as soon as the operator's own turn is stored: the model call runs on a worker
     * thread, because the browser is blocked on this reply and a slow analyst would become a request
     * timeout and a button that looks dead. Poll {@link #discussion} for the answer.
     *
     * <p>The conversation is explanatory only. Nothing said here edits the BRD or the question —
     * the operator captures what they concluded into {@link #answer} or {@link #note} themselves.
     */
    String discuss(String flowId, String questionId, String message);

    /** Submits the current round and resumes analysis with the answers given. */
    String submitAnswers(String flowId);

    // --- review (REVIEW) ------------------------------------------------------------------------

    /** Accepts or rejects one proposed change. Nothing has touched the BRD at this point. */
    String setAccepted(String flowId, String proposalId, boolean accepted);

    /** Accepts or rejects every proposal at once — the review list's select-all. */
    String setAllAccepted(String flowId, boolean accepted);

    /**
     * Writes the accepted proposals into the project's BRD as one revision, and moves the flow to
     * {@code APPLIED}. Rejected proposals are simply not written; the flow record keeps them, so
     * what was declined stays auditable.
     */
    String apply(String flowId);

    /**
     * Returns a finished (or failed) flow to {@code DRAFT} so a document can be added or its notes
     * changed and analysis run again. The next run is a merge against the requirements that now
     * exist, proposing edits and conflicts rather than duplicates.
     */
    String reopen(String flowId);

    /**
     * Throws the whole analysis away — documents, questions and proposals — leaving an empty
     * {@code DRAFT}. Nothing already written to the BRD is affected.
     *
     * <p>Available from every state, including mid-run. A guided flow that can get into a state its
     * own UI offers no way out of is a trap, and the operator's instinct — start again — has to be
     * something the wizard can actually do.
     */
    String startOver(String flowId);
}
