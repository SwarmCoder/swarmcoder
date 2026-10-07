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

import java.util.List;

/**
 * How many checks are in each state — for one requirement, or summed over a subtree.
 *
 * <p><b>A retired requirement counts nothing at all.</b> Taking a requirement out of scope stops it
 * affecting anything (see {@link BrdRequirement#isRetired()}), and a coverage figure is the loudest
 * thing it could still affect.
 *
 * <p><b>What counts.</b> The four state fields cover the <em>gating</em> checks: the ACCEPTED ones,
 * which are what a requirement is actually judged against ({@link BrdRequirement#gatingCriteria()}).
 * {@link #proposed} is the advisory count — checks an agent suggested and nobody has accepted, which
 * gate nothing and so must never be added into the denominator of "12 of 20 verified". RETIRED checks
 * are in neither: they are history, and counting them would make a tidy-up look like a regression.
 *
 * <p><b>Why STALE is inside the gating total.</b> A stale check is accepted and gating; the only thing
 * wrong with it is that its evidence predates the current wording. Excluding it would shrink the
 * denominator when someone edits a requirement, so a reword would <em>improve</em> the ratio while
 * making the truth less certain.
 */
public final class CheckCounts {

    public static final CheckCounts EMPTY = new CheckCounts(0, 0, 0, 0, 0);

    private final int passing;
    private final int failing;
    private final int unverified;
    private final int stale;
    private final int proposed;

    public CheckCounts(int passing, int failing, int unverified, int stale, int proposed) {
        this.passing = passing;
        this.failing = failing;
        this.unverified = unverified;
        this.stale = stale;
        this.proposed = proposed;
    }

    // An immutable value, but NOT a Java record: every type in this package is presumed reachable
    // from the EclipseStore root, whose reflective serializer cannot persist records, and
    // StoreArchitectureTest enforces that bluntly rather than maintaining a list of exemptions.
    // BuildHealth and RunPause are final classes for the same reason. Nothing is lost but a keyword.

    public int passing() {
        return passing;
    }

    public int failing() {
        return failing;
    }

    public int unverified() {
        return unverified;
    }

    public int stale() {
        return stale;
    }

    /** Advisory checks — suggested, not accepted, gating nothing. */
    public int proposed() {
        return proposed;
    }

    /** Every ACCEPTED check, whatever state it is in — the denominator of the headline ratio. */
    public int gating() {
        return passing + failing + unverified + stale;
    }

    /** Gating plus advisory — every check that still means something. */
    public int total() {
        return gating() + proposed;
    }

    public boolean isEmpty() {
        return total() == 0;
    }

    /**
     * True when there is something to prove and all of it is proven against the current wording.
     *
     * <p>This is the same condition {@link BrdRequirement#evidenceStatus()} flips to IMPLEMENTED on,
     * and that method is implemented in terms of this one so the tree's tick and the requirement's own
     * badge cannot disagree.
     */
    public boolean allGatingPassing() {
        return gating() > 0 && passing == gating();
    }

    /** Anything accepted, gating, and not currently proven. */
    public int outstanding() {
        return failing + unverified + stale;
    }

    public CheckCounts plus(CheckCounts other) {
        if (other == null) {
            return this;
        }
        return new CheckCounts(passing + other.passing, failing + other.failing,
            unverified + other.unverified, stale + other.stale, proposed + other.proposed);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CheckCounts that = (CheckCounts) o;
        return passing == that.passing && failing == that.failing
            && unverified == that.unverified && stale == that.stale && proposed == that.proposed;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(passing, failing, unverified, stale, proposed);
    }

    @Override
    public String toString() {
        return "CheckCounts[passing=" + passing + ", failing=" + failing
            + ", unverified=" + unverified + ", stale=" + stale + ", proposed=" + proposed + "]";
    }

    /**
     * The counts for one requirement's own checks, each evaluated against <b>that requirement's</b>
     * {@code contentRevision}.
     *
     * <p>The revision argument is not incidental. Staleness is per requirement — a check is stale when
     * it passed against wording its own requirement has since changed — so summing a subtree with the
     * parent's revision would report a child's evidence as stale, or fresh, according to an edit made
     * somewhere else entirely.
     */
    public static CheckCounts of(BrdRequirement requirement) {
        if (requirement == null) {
            return EMPTY;
        }
        // A retired requirement counts nothing. Its checks are kept — the trail to the commit that
        // satisfied them is the point of the whole document — but they gate nothing and must not
        // appear in a coverage figure, or a requirement somebody took out of scope goes on making
        // "12 of 20 verified" mean something different from what the operator agreed to.
        if (requirement.isRetired()) {
            return EMPTY;
        }
        long revision = requirement.contentRevision();
        int passing = 0;
        int failing = 0;
        int unverified = 0;
        int stale = 0;
        int proposed = 0;
        List<AcceptanceCriterion> criteria = requirement.criteria();
        for (AcceptanceCriterion c : criteria == null ? List.<AcceptanceCriterion>of() : criteria) {
            if (c == null) {
                continue;
            }
            if (c.status() == CriterionStatus.RETIRED) {
                continue;
            }
            if (!c.isGate()) {
                proposed++;
                continue;
            }
            CriterionState state = c.effectiveState(revision);
            if (state == CriterionState.PASSING) {
                passing++;
            } else if (state == CriterionState.FAILING) {
                failing++;
            } else if (state == CriterionState.STALE) {
                stale++;
            } else {
                unverified++;
            }
        }
        return new CheckCounts(passing, failing, unverified, stale, proposed);
    }
}
