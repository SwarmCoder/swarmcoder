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

/**
 * What sort of decision the machine took, or refused to take, while running with nobody watching.
 *
 * <p>The distinction that matters here is the first one. {@link #ANSWERED_QUESTION} is the machine
 * <em>inventing a requirement</em>: a clarification exists precisely because the operator's document
 * did not say, so there is no answer to retrieve and whatever comes back is a decision about what
 * the product is. Everything else on this list is the machine agreeing with something it can see —
 * a draft it just produced, a gate that already passed. They are not the same act and the record
 * must not show them as one.
 *
 * <p><b>APPEND ONLY.</b> EclipseStore persists an enum by its ORDINAL, so inserting or reordering a
 * constant silently re-points every value already on disk. New constants go on the end — always.
 */
public enum AutonomousDecisionKind {
    /** A clarifying question the operator would have answered, answered by the machine instead. */
    ANSWERED_QUESTION,
    /** Requirements drafted from the documents, taken as read and made agreed scope. */
    AGREED_REQUIREMENTS,
    /** Everything the analysis proposed, accepted into the requirements without a person reading it. */
    ACCEPTED_PROPOSALS,
    /** Everything the planner proposed, accepted into the backlog without a person reading it. */
    ACCEPTED_STORIES,
    /** A story marked ready to build, which a person would otherwise have done by hand. */
    PROMOTED_STORY,
    /**
     * Something the machine deliberately did NOT decide, and left for a person.
     *
     * <p>On the record for the same reason the decisions are: a night that quietly skipped four
     * things is indistinguishable in the morning from a night that had nothing to skip.
     */
    REFUSED,
    /** The session ended, and why — a budget, the clock, the operator, or nothing left to do. */
    STOPPED,
    /**
     * A story whose build stopped mid-flight to ask something, sent back to be built again without
     * a person reading the question — once. A second stop on the same story is left for a person,
     * exactly as an unattended stop has always been; see {@code UnattendedPilot}.
     */
    RETRIED_PARKED_STORY
}
