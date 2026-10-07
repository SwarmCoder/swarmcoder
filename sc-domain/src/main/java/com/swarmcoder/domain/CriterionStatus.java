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
 * Lifecycle of an acceptance criterion as a statement of intent — whether it is agreed, not whether
 * it currently holds (that is {@link CriterionState}). {@link #PROPOSED}: drafted but not yet
 * agreed. {@link #ACCEPTED}: agreed and binding — part of the definition of done.
 * {@link #RETIRED}: no longer binding but kept for history and traceability.
 */
public enum CriterionStatus {
    PROPOSED, ACCEPTED, RETIRED;

    /**
     * The same standing in the words the operator reads. The picker beside every check used to
     * offer the three constants themselves (UX v3 §5 rule 3).
     */
    public String label() {
        return switch (this) {
            case PROPOSED -> "proposed";
            case ACCEPTED -> "agreed";
            case RETIRED -> "retired";
        };
    }

    /** The constant behind a label, accepting the constant's own name too. */
    public static CriterionStatus of(String label) {
        String v = label == null ? "" : label.trim().toLowerCase();
        if (v.equals("agreed") || v.equals("accepted")) {
            return ACCEPTED;
        }
        return v.equals("retired") ? RETIRED : PROPOSED;
    }
}
