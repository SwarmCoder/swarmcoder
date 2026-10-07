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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One thing the machine decided on the operator's behalf while running with nobody watching, written
 * down so that it can be read afterwards and disagreed with.
 *
 * <p><b>Why this exists at all.</b> Autonomous running overrides the rule that the definition of
 * done is human. The override is the operator's to make and it is made deliberately - but it must
 * never be made quietly. The most expensive case is not a build that fails; it is a build that
 * succeeds against a requirement nobody wrote. A clarifying question is asked because the document
 * did not say. An agent answering it is not looking the answer up, it is <em>choosing</em> what the
 * product does - "books are stored in a JSON file" - and then everything downstream proves that
 * choice was implemented, honestly and completely, and reports it delivered. Without this record
 * there is nothing anywhere that distinguishes that from something the operator asked for.
 *
 * <p><b>{@link #grounded} is the field the whole record is for.</b> True means the answer was
 * genuinely in the documents and the question was avoidable. False means nothing said, and the
 * machine made it up: this is an assumption, it is what the system now believes, and it is the list
 * the operator reads first. The agent is asked to say which it is, and the honest default when it
 * will not say is false - an assumption filed as a fact is the one failure this record cannot
 * tolerate.
 *
 * <p>Distinct from {@link ChangeEvent}, which records what the plan is and was, keyed per entity and
 * read one entity at a time. This is a session's own diary: everything one unattended stretch
 * decided, in order, in one list, because "what did it decide while I was asleep?" is a question
 * about the night and not about any one requirement.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore - see
 * {@link VerificationReport}.
 */
@DataModel
public class AutonomousDecision {
    private UUID id;
    private UUID projectId;
    /** The unattended stretch this belongs to, so one night can be read apart from the next. */
    private UUID sessionId;
    private Instant at;
    private AutonomousDecisionKind kind;
    /** What the decision was about, in the operator's words - "R3 Guest checkout", "Story S4". */
    private String subject;
    /** What was asked, or what gate was passed. Empty for a decision nobody asked a question about. */
    private String question;
    /** What the machine settled on. For a refusal, what it declined to settle. */
    private String answer;
    /** Why, in one or two sentences - the agent's own reasoning, or the rule that was applied. */
    private String reasoning;
    /**
     * True when the answer really was in the operator's documents; false when the machine made it
     * up. See the class note - this is the field that separates a reading from an invention.
     */
    private boolean grounded;
    /** The guided flow this arose in, when it arose in one. */
    private UUID flowId;
    /** The clarification this answers, when it answers one. */
    private UUID questionId;

    public AutonomousDecision() {}

    public AutonomousDecision(UUID id, UUID projectId, UUID sessionId, Instant at,
                              AutonomousDecisionKind kind, String subject, String question,
                              String answer, String reasoning, boolean grounded, UUID flowId,
                              UUID questionId) {
        this.id = id;
        this.projectId = projectId;
        this.sessionId = sessionId;
        this.at = at;
        this.kind = kind;
        this.subject = subject;
        this.question = question;
        this.answer = answer;
        this.reasoning = reasoning;
        this.grounded = grounded;
        this.flowId = flowId;
        this.questionId = questionId;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID sessionId() { return sessionId; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
    public Instant at() { return at; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public AutonomousDecisionKind kind() { return kind; }
    public AutonomousDecisionKind getKind() { return kind; }
    public void setKind(AutonomousDecisionKind kind) { this.kind = kind; }
    public String subject() { return subject; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String question() { return question; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String answer() { return answer; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String reasoning() { return reasoning; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public boolean grounded() { return grounded; }
    public boolean isGrounded() { return grounded; }
    public void setGrounded(boolean grounded) { this.grounded = grounded; }
    public UUID flowId() { return flowId; }
    public UUID getFlowId() { return flowId; }
    public void setFlowId(UUID flowId) { this.flowId = flowId; }
    public UUID questionId() { return questionId; }
    public UUID getQuestionId() { return questionId; }
    public void setQuestionId(UUID questionId) { this.questionId = questionId; }

    /**
     * The one-line heading the operator reads in the list, saying plainly which of the two things
     * this was.
     *
     * <p>Derived here rather than in the browser so the log, the dialog and any report can never
     * word the same decision differently.
     */
    public String headline() {
        if (kind == AutonomousDecisionKind.ANSWERED_QUESTION) {
            return grounded
                ? "Answered from your documents"
                : "Made this up - your documents did not say";
        }
        if (kind == null) {
            return "Decided";
        }
        return switch (kind) {
            case AGREED_REQUIREMENTS -> "Agreed this as scope without you reading it";
            case ACCEPTED_PROPOSALS -> "Accepted what the reading proposed";
            case ACCEPTED_STORIES -> "Accepted the slices of work it planned";
            case PROMOTED_STORY -> "Marked this ready to build";
            case REFUSED -> "Left this for you";
            case STOPPED -> "Stopped";
            case RETRIED_PARKED_STORY -> "Built it again after it stopped";
            default -> "Decided";
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AutonomousDecision that = (AutonomousDecision) o;
        return grounded == that.grounded && Objects.equals(id, that.id)
            && Objects.equals(projectId, that.projectId)
            && Objects.equals(sessionId, that.sessionId) && Objects.equals(at, that.at)
            && kind == that.kind && Objects.equals(subject, that.subject)
            && Objects.equals(question, that.question) && Objects.equals(answer, that.answer)
            && Objects.equals(reasoning, that.reasoning) && Objects.equals(flowId, that.flowId)
            && Objects.equals(questionId, that.questionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, projectId, sessionId, at, kind, subject, question, answer,
            reasoning, grounded, flowId, questionId);
    }
}
