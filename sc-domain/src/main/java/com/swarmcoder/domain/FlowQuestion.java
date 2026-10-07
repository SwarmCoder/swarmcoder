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
package com.swarmcoder.domain;

import com.zeroz4j.api.DataModel;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One clarification a {@link GuidedFlow} needs from the operator, emitted as <em>data</em> rather
 * than as prose so the wizard can render it as a form field.
 *
 * <p>That buys what a scrolling conversation cannot: questions answerable in any order,
 * individually skippable, and a visible count of what is still outstanding. Questions arrive in
 * rounds — an agent will produce a dozen clarifications, and a dozen sequential modal prompts is
 * death by a thousand dialogs; a round is a short form, answered where it matters and skipped
 * elsewhere.
 *
 * <p>{@code subject} is what the question concerns (e.g. "R3 Guest checkout") so the answer can be
 * attached to the requirement it belongs to. {@code sourceQuote} carries the passage the question
 * arose from, because a question about a document you have not memorised is unanswerable without
 * it — an operator should never have to go and find the section themselves. {@code kind} decides
 * the control: a
 * {@link FlowQuestionKind#CHOICE} is answered from {@code options}, a {@link FlowQuestionKind#TEXT}
 * in free prose.
 *
 * <p><strong>Skipping is first-class.</strong> A skipped question does not block the flow; it
 * becomes an assumption the agent must state explicitly in the requirement it drafts, so an
 * unanswered question turns into something visible and correctable rather than a silent guess.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class FlowQuestion {
    private UUID id;
    private UUID flowId;
    private String subject;          // what the question concerns (e.g. "R3 Guest checkout")
    private String text;
    /** The passage this arose from, quoted or closely paraphrased — the background for the ask. */
    private String sourceQuote;
    /** Which document {@link #sourceQuote} came from, or null when it spans several. */
    private String sourceDocument;
    /**
     * The fuller background, shown behind an info icon rather than inline.
     *
     * <p>Separate from {@link #sourceQuote} because a great deal of a requirements pile is not
     * quotable — a wireframe, a photographed whiteboard, a table read out of an image. There the
     * honest answer is an explanation of what the document conveys and why it raised this question,
     * which is longer than a form field should carry but is exactly what the operator needs before
     * deciding.
     */
    private String background;
    private FlowQuestionKind kind;
    private List<String> options;    // the closed-ended answers, for CHOICE
    private String answer;
    /** Free-text qualification alongside {@code answer} — "yes, but only after the gate closes". */
    private String note;
    private boolean skipped;         // skipped answers become stated assumptions, not silent guesses

    public FlowQuestion() {}

    public FlowQuestion(UUID id, UUID flowId, String subject, String text, String sourceQuote,
                        String sourceDocument, String background, FlowQuestionKind kind,
                        List<String> options, String answer, String note, boolean skipped) {
        this.id = id;
        this.flowId = flowId;
        this.subject = subject;
        this.text = text;
        this.sourceQuote = sourceQuote;
        this.sourceDocument = sourceDocument;
        this.background = background;
        this.kind = kind;
        this.options = options;
        this.answer = answer;
        this.note = note;
        this.skipped = skipped;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID flowId() { return flowId; }
    public UUID getFlowId() { return flowId; }
    public void setFlowId(UUID flowId) { this.flowId = flowId; }
    public String subject() { return subject; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String text() { return text; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String sourceQuote() { return sourceQuote; }
    public String getSourceQuote() { return sourceQuote; }
    public void setSourceQuote(String sourceQuote) { this.sourceQuote = sourceQuote; }
    public String sourceDocument() { return sourceDocument; }
    public String getSourceDocument() { return sourceDocument; }
    public void setSourceDocument(String sourceDocument) { this.sourceDocument = sourceDocument; }
    public String background() { return background; }
    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }
    public FlowQuestionKind kind() { return kind; }
    public FlowQuestionKind getKind() { return kind; }
    public void setKind(FlowQuestionKind kind) { this.kind = kind; }
    /** Null-safe: returns an empty list when unset (pre-existing stores load this field as null). */
    public List<String> options() {
        return options == null ? List.of() : options;
    }
    public List<String> getOptions() { return options(); }
    public void setOptions(List<String> options) { this.options = options; }
    public String answer() { return answer; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String note() { return note; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public boolean skipped() { return skipped; }
    public boolean isSkipped() { return skipped; }
    public void setSkipped(boolean skipped) { this.skipped = skipped; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlowQuestion that = (FlowQuestion) o;
        return this.skipped == that.skipped && Objects.equals(this.id, that.id)
            && Objects.equals(this.flowId, that.flowId)
            && Objects.equals(this.subject, that.subject) && Objects.equals(this.text, that.text)
            && Objects.equals(this.sourceQuote, that.sourceQuote)
            && Objects.equals(this.sourceDocument, that.sourceDocument)
            && Objects.equals(this.background, that.background)
            && Objects.equals(this.kind, that.kind)
            && Objects.equals(this.options(), that.options())
            && Objects.equals(this.answer, that.answer) && Objects.equals(this.note, that.note);
    }

    @Override
    public int hashCode() {
        // options compares through its NULL-SAFE accessor: an unset list IS empty, and the wire
        // serializer reads through the same accessor, so comparing the raw field would make a
        // round-tripped question unequal to the one that was sent — silently breaking signal dedup.
        return Objects.hash(id, flowId, subject, text, sourceQuote, sourceDocument, background,
            kind, options(), answer, note, skipped);
    }
}
