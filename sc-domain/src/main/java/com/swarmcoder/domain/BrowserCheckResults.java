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
import java.util.Objects;

/**
 * What the browser stage reported: one {@link PageCheck} per configured navigation, plus
 * {@link #stageOutcome()} — whether those page results mean anything at all.
 *
 * <p>The outcome is the load-bearing part. "Every page failed to load" is the strongest possible
 * evidence that a candidate's application is broken, and it is also exactly what a machine with no
 * headless browser installed produces. See {@link BrowserStageOutcome}.
 */
public class BrowserCheckResults {
    private List<PageCheck> checks;

    /**
     * What the stage did. Null on any report written before this field existed, which reads as
     * {@link BrowserStageOutcome#EXECUTED} — those reports were judged that way when they were
     * written, and re-reading one must not silently change the verdict it recorded.
     */
    private BrowserStageOutcome stageOutcome;

    /** Plain English for why the stage could not be tried; null when it ran. */
    private String couldNotTryReason;

    public BrowserCheckResults() {}

    public BrowserCheckResults(List<PageCheck> checks) {
        this.checks = checks;
    }

    public List<PageCheck> checks() { return checks; }
    public List<PageCheck> getChecks() { return checks; }
    public void setChecks(List<PageCheck> checks) { this.checks = checks; }

    /** Null-safe: an absent outcome reads as {@link BrowserStageOutcome#EXECUTED}. */
    public BrowserStageOutcome stageOutcome() {
        return stageOutcome == null ? BrowserStageOutcome.EXECUTED : stageOutcome;
    }
    public BrowserStageOutcome getStageOutcome() { return stageOutcome; }
    public void setStageOutcome(BrowserStageOutcome stageOutcome) { this.stageOutcome = stageOutcome; }

    public String couldNotTryReason() { return couldNotTryReason; }
    public String getCouldNotTryReason() { return couldNotTryReason; }
    public void setCouldNotTryReason(String couldNotTryReason) { this.couldNotTryReason = couldNotTryReason; }

    /** True when nothing was learned about the candidate, so it must not be failed on this stage. */
    public boolean couldNotTry() {
        return stageOutcome() == BrowserStageOutcome.COULD_NOT_TRY;
    }

    /** Results tagged "the harness never got to try", with the reason an operator can act on. */
    public static BrowserCheckResults couldNotTry(List<PageCheck> checks, String reason) {
        BrowserCheckResults results = new BrowserCheckResults(checks);
        results.setStageOutcome(BrowserStageOutcome.COULD_NOT_TRY);
        results.setCouldNotTryReason(reason);
        return results;
    }

    /** Results tagged "the browser really ran, so these page results are the truth". */
    public static BrowserCheckResults executed(List<PageCheck> checks) {
        BrowserCheckResults results = new BrowserCheckResults(checks);
        results.setStageOutcome(BrowserStageOutcome.EXECUTED);
        return results;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrowserCheckResults that = (BrowserCheckResults) o;
        return Objects.equals(this.checks, that.checks)
            && this.stageOutcome == that.stageOutcome
            && Objects.equals(this.couldNotTryReason, that.couldNotTryReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(checks, stageOutcome, couldNotTryReason);
    }
}
