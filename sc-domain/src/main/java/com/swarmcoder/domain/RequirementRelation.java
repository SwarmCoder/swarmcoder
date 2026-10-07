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
 * The typed relationship an edge in the BRD requirement graph carries (author decision
 * 2026-07-24, full set). {@link #DEPENDS_ON}: the source needs the target satisfied first.
 * {@link #REFINES}: the source decomposes/details the target (parent/child). {@link #CONFLICTS_WITH}:
 * the two requirements are in tension and cannot both be fully met. {@link #DERIVED_FROM}: the
 * source was inferred from the target (e.g. a technical requirement derived from a business one).
 *
 * <p>{@link #GATES} (added 2026-07-25): the source is a non-functional requirement that constrains
 * the target <em>and every {@link #REFINES}-descendant of it</em>. This is how an NFR applies to a
 * whole area of the product without being restated on each requirement under it, and how the
 * {@link RequirementKind#NON_FUNCTIONAL} gate is inherited by the stories that deliver them.
 *
 * <h2>The words the operator reads</h2>
 *
 * <p>None of the five constant names may reach a screen (UX v3 §5 rule 3, and rule 6 of
 * {@code REQUIREMENTS_AT_SCALE_DESIGN.md}). The wording lives here, on the enum, for the same reason
 * {@link RequirementStatus#label()} does: a legend, a picker, a row badge and a line of history all
 * name the same relation, and three of them used to name it {@code refines}.
 *
 * <p><b>A relation reads differently from each end</b>, which is why there are two phrases and not
 * one. The quality requirement <em>constrains</em>; the ordinary requirement is <em>constrained
 * by</em>. Printing one phrase on both rows would tell the operator that an ordinary requirement
 * imposes a quality bar on a non-functional one, which is backwards and is exactly the sort of thing
 * nobody notices on a screen. {@link #CONFLICTS_WITH} is symmetric and so reads the same both ways,
 * which is correct rather than an oversight.
 */
public enum RequirementRelation {
    DEPENDS_ON("waits for", "waits for", "needed by"),
    REFINES("part of", "part of", "contains"),
    CONFLICTS_WITH("conflicts with", "conflicts with", "conflicts with"),
    DERIVED_FROM("drawn from", "drawn from", "led to"),
    GATES("constrains", "constrains", "constrained by");

    private final String label;
    private final String fromPhrase;
    private final String toPhrase;

    RequirementRelation(String label, String fromPhrase, String toPhrase) {
        this.label = label;
        this.fromPhrase = fromPhrase;
        this.toPhrase = toPhrase;
    }

    /**
     * The relation named on its own, for a legend or a picker where there is no direction to read.
     * It is the phrase the SOURCE end would use, because a picker sits beside "edges from this
     * requirement" and that is the end the operator is choosing for.
     */
    public String label() {
        return label;
    }

    /** What the source requirement does, e.g. {@code "constrains"} — always followed by a handle. */
    public String fromPhrase() {
        return fromPhrase;
    }

    /** What the target requirement has done to it, e.g. {@code "constrained by"}. */
    public String toPhrase() {
        return toPhrase;
    }

    /** The relation a {@link #label()} names, accepting the constant's own name too, or null. */
    public static RequirementRelation of(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String wanted = text.trim();
        for (RequirementRelation relation : values()) {
            if (relation.label.equalsIgnoreCase(wanted) || relation.name().equalsIgnoreCase(wanted)) {
                return relation;
            }
        }
        return null;
    }
}
