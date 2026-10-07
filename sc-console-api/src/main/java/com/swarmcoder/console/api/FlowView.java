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

import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.SourceDocument;
import com.zeroz4j.api.DataModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything the wizard renders, in one value: the flow, its outstanding questions, its proposed
 * changes, and the documents it is working from.
 *
 * <p>One aggregate rather than four calls because the wizard's steps are a single coherent state —
 * showing progress from one fetch and questions from another invites a frame where they disagree.
 *
 * <p>An aggregate, not a mirror: {@link GuidedFlow}, {@link FlowQuestion}, {@link FlowProposal} and
 * {@link SourceDocument} are the domain objects travelling directly (the direct-model pattern).
 * Only the composition is assembled here.
 */
@DataModel
public class FlowView {

    private GuidedFlow flow;
    private List<FlowQuestion> questions;
    private List<FlowProposal> proposals;
    /** The uploaded documents this flow's {@code FlowDocument} entries point at, for display. */
    private List<SourceDocument> documents;
    /**
     * Total characters of document text this analysis would send, and the ceiling it must fit under.
     *
     * <p>Carried here because the wizard has to show the operator both numbers BEFORE they press
     * Analyse — the document text itself is deliberately not sent, so the client cannot add it up.
     */
    private int totalCharacters;
    private int characterBudget;
    /**
     * How many requirements the BRD already holds.
     *
     * <p>Decides whether this run is an analysis or a RE-analysis — which is the difference between
     * "these documents become your requirements" and "these documents are compared against the
     * requirements you have". The operator must be able to tell which button they are pressing.
     */
    private int existingRequirements;
    /**
     * Backlog planning's inputs, which — unlike intake's — the operator does not supply.
     *
     * <p>{@code coverage} is the server's own report of which accepted criteria no story yet claims,
     * {@code unclaimedCriteria} its count, and {@code backlogStories} how many stories already
     * exist. All three are computed here because the browser cannot see the requirement graph, and
     * the planning wizard's first screen has nothing else to show: it has no document list, so
     * without this it would be a dialog with one button and no account of what pressing it reads.
     */
    private String coverage;
    private int unclaimedCriteria;
    private int backlogStories;

    public FlowView() {}

    public FlowView(GuidedFlow flow, List<FlowQuestion> questions, List<FlowProposal> proposals,
                    List<SourceDocument> documents, int totalCharacters, int characterBudget,
                    int existingRequirements) {
        this.flow = flow;
        this.questions = questions;
        this.proposals = proposals;
        this.documents = documents;
        this.totalCharacters = totalCharacters;
        this.characterBudget = characterBudget;
        this.existingRequirements = existingRequirements;
    }

    /** No flow yet — the signal's initial value, and what a project with no intake looks like. */
    public static FlowView none() {
        return new FlowView(null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0, 0, 0);
    }

    public GuidedFlow flow() { return flow; }
    public GuidedFlow getFlow() { return flow; }
    public void setFlow(GuidedFlow flow) { this.flow = flow; }
    public List<FlowQuestion> questions() { return questions == null ? List.of() : questions; }
    public List<FlowQuestion> getQuestions() { return questions(); }
    public void setQuestions(List<FlowQuestion> questions) { this.questions = questions; }
    public List<FlowProposal> proposals() { return proposals == null ? List.of() : proposals; }
    public List<FlowProposal> getProposals() { return proposals(); }
    public void setProposals(List<FlowProposal> proposals) { this.proposals = proposals; }
    public List<SourceDocument> documents() { return documents == null ? List.of() : documents; }
    public List<SourceDocument> getDocuments() { return documents(); }
    public void setDocuments(List<SourceDocument> documents) { this.documents = documents; }

    public int totalCharacters() { return totalCharacters; }
    public int getTotalCharacters() { return totalCharacters; }
    public void setTotalCharacters(int totalCharacters) { this.totalCharacters = totalCharacters; }
    public int characterBudget() { return characterBudget; }
    public int getCharacterBudget() { return characterBudget; }
    public void setCharacterBudget(int characterBudget) { this.characterBudget = characterBudget; }

    public int existingRequirements() { return existingRequirements; }
    public int getExistingRequirements() { return existingRequirements; }
    public void setExistingRequirements(int existingRequirements) {
        this.existingRequirements = existingRequirements;
    }

    public String coverage() { return coverage == null ? "" : coverage; }
    public String getCoverage() { return coverage(); }
    public void setCoverage(String coverage) { this.coverage = coverage; }
    public int unclaimedCriteria() { return unclaimedCriteria; }
    public int getUnclaimedCriteria() { return unclaimedCriteria; }
    public void setUnclaimedCriteria(int unclaimedCriteria) {
        this.unclaimedCriteria = unclaimedCriteria;
    }
    public int backlogStories() { return backlogStories; }
    public int getBacklogStories() { return backlogStories; }
    public void setBacklogStories(int backlogStories) { this.backlogStories = backlogStories; }

    /** True when this run would merge into an existing BRD rather than populate an empty one. */
    public boolean hasRequirements() {
        return existingRequirements > 0;
    }

    /** True when the documents will not fit and the analysis must not be started. */
    public boolean overBudget() {
        return characterBudget > 0 && totalCharacters > characterBudget;
    }

    /** Unanswered, unskipped questions — what the wizard is waiting on. */
    public int openQuestions() {
        int open = 0;
        for (FlowQuestion question : questions()) {
            if (!question.skipped() && (question.answer() == null || question.answer().isBlank())) {
                open++;
            }
        }
        return open;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlowView that = (FlowView) o;
        return this.totalCharacters == that.totalCharacters
            && this.characterBudget == that.characterBudget
            && this.existingRequirements == that.existingRequirements
            && this.unclaimedCriteria == that.unclaimedCriteria
            && this.backlogStories == that.backlogStories
            && Objects.equals(this.coverage(), that.coverage())
            && Objects.equals(this.flow, that.flow)
            && Objects.equals(this.questions(), that.questions())
            && Objects.equals(this.proposals(), that.proposals())
            && Objects.equals(this.documents(), that.documents());
    }

    @Override
    public int hashCode() {
        // coverage compares through its NULL-SAFE accessor, for the same reason the lists do on the
        // domain models: the wire serializer reads through the accessor, so an unset field would
        // round-trip as "" and make the received view unequal to the one that was sent.
        return Objects.hash(flow, questions(), proposals(), documents(),
            totalCharacters, characterBudget, existingRequirements, coverage(), unclaimedCriteria,
            backlogStories);
    }
}
