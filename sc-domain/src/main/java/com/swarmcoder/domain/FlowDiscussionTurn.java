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
 * One line of the side conversation about a single {@link FlowQuestion}.
 *
 * <p>A clarification question used to be take-it-or-leave-it: answer it, skip it, or go and read the
 * document yourself. The response an operator actually wants to give is often neither — "why are you
 * asking?", "what did the document say around this?" — and there was nowhere to say it. These turns
 * are that place.
 *
 * <p>Deliberately <em>per question</em>, not per flow: the whole value of the questions being data
 * rather than a chat transcript is that each one can be picked up, put down and answered on its own,
 * and a single shared conversation would collapse that back into one thread to scroll.
 *
 * <p>A discussion is <strong>explanatory, never authoritative</strong>. Nothing said here changes the
 * BRD or the question; the operator's conclusion only lands when they capture it into the question's
 * {@code answer} or {@code note}, which is a separate, deliberate act.
 *
 * <p>{@code role} is a plain string ({@link #YOU} / {@link #ANALYST}) rather than an enum on purpose:
 * persisted enums here are append-only, and a two-valued vocabulary is not worth that constraint.
 *
 * <p>A mutable POJO (not a record) because it is persisted in EclipseStore — see
 * {@link VerificationReport}.
 */
@DataModel
public class FlowDiscussionTurn {

    /** The operator's own words. Rendered as "You" — it is their side of the conversation. */
    public static final String YOU = "you";
    /** The analyst answering for itself. */
    public static final String ANALYST = "analyst";

    private UUID id;
    /** The question being discussed. Discussions are keyed by this, not by the flow. */
    private UUID questionId;
    private String role;
    private String text;
    private Instant at;

    public FlowDiscussionTurn() {}

    public FlowDiscussionTurn(UUID id, UUID questionId, String role, String text, Instant at) {
        this.id = id;
        this.questionId = questionId;
        this.role = role;
        this.text = text;
        this.at = at;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID questionId() { return questionId; }
    public UUID getQuestionId() { return questionId; }
    public void setQuestionId(UUID questionId) { this.questionId = questionId; }
    /** Null-safe: an unset role reads as the analyst's, which is what a bare reply would be. */
    public String role() { return role == null ? ANALYST : role; }
    public String getRole() { return role(); }
    public void setRole(String role) { this.role = role; }
    /** Null-safe: an unset text reads as empty, so a half-written turn cannot NPE the renderer. */
    public String text() { return text == null ? "" : text; }
    public String getText() { return text(); }
    public void setText(String text) { this.text = text; }
    public Instant at() { return at; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }

    /** True when this turn is the operator's rather than the analyst's. */
    public boolean fromOperator() {
        return YOU.equals(role());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlowDiscussionTurn that = (FlowDiscussionTurn) o;
        return Objects.equals(this.id, that.id)
            && Objects.equals(this.questionId, that.questionId)
            && Objects.equals(this.role(), that.role())
            && Objects.equals(this.text(), that.text())
            && Objects.equals(this.at, that.at);
    }

    @Override
    public int hashCode() {
        // role and text compare through their NULL-SAFE accessors, because the wire serializer reads
        // through those same accessors: comparing the raw fields would make a round-tripped turn
        // unequal to the one that was sent, and silently break signal dedup.
        return Objects.hash(id, questionId, role(), text(), at);
    }
}
