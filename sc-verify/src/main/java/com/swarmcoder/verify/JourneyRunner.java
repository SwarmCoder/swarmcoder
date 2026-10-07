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
package com.swarmcoder.verify;

import com.swarmcoder.domain.AssertionResult;
import com.swarmcoder.domain.BrowserCheckResults;
import com.swarmcoder.domain.PageCheck;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes journeys in a tree that has been built: starts the application the way the project's
 * contract says ({@code browser.serve}) and has the browser inside the container carry out each
 * journey from the entry page (section 63). No model, and nothing on this PC: outside a container
 * with a browser the answer is "could not run", never a run here.
 *
 * <p>Unlike the browser stage of an ordinary verification, a journey that could not be made is
 * not passed over in silence. The caller is told {@link Outcome#couldNotRun}, and decides.
 */
public final class JourneyRunner {

    private JourneyRunner() {}

    /**
     * @param couldNotRun     null when the journeys were made; otherwise why none was (no
     *                        {@code serve} line, no browser in the container, no container)
     * @param didNotStart     null when the application came up; otherwise what was waited for
     * @param results         one per journey, in order; empty unless the journeys were made
     */
    public record Outcome(String couldNotRun, String didNotStart,
                          List<JourneyFile.Result> results) {

        public boolean made() {
            return couldNotRun == null && didNotStart == null;
        }

        public List<JourneyFile.Result> failed() {
            return results.stream().filter(result -> !result.passed()).toList();
        }

        public List<JourneyFile.Result> passed() {
            return results.stream().filter(JourneyFile.Result::passed).toList();
        }
    }

    /**
     * @param target   the container holding the built tree
     * @param contract the project's verification contract
     * @param log      the run's log of the stage; appended to
     */
    public static Outcome run(ExecTarget target, VerifySpec contract,
                              List<JourneyFile.Journey> journeys, BlobSink blobs,
                              StringBuilder log) {
        if (!JourneyFile.canRun(contract)) {
            return new Outcome("the project's verification contract has no `browser.serve` line, "
                + "so nothing says how to start the application", null, List.of());
        }
        if (journeys == null || journeys.isEmpty()) {
            return new Outcome(null, null, List.of());
        }
        VerifySpec.BrowserSpec browser = contract.browser();
        String entry = JourneyFile.entryUrl(browser);
        List<VerifySpec.PageCheckSpec> checks = new ArrayList<>();
        for (JourneyFile.Journey journey : journeys) {
            checks.add(journey.toCheck(entry));
        }
        BrowserCheckResults results = new BrowserVerifier(blobs).run(target,
            new VerifySpec.BrowserSpec(browser.serve(), browser.readyProbe(),
                browser.readyTimeoutSeconds(), checks, browser.port()),
            log == null ? new StringBuilder() : log);
        if (results.couldNotTry()) {
            return new Outcome(results.couldNotTryReason(), null, List.of());
        }
        List<PageCheck> pages = results.checks() == null ? List.of() : results.checks();
        String didNotStart = didNotStart(pages);
        if (didNotStart != null) {
            return new Outcome(null, didNotStart, List.of());
        }
        List<JourneyFile.Result> made = new ArrayList<>();
        for (int i = 0; i < journeys.size(); i++) {
            made.add(JourneyFile.resultOf(journeys.get(i), i < pages.size() ? pages.get(i) : null));
        }
        return new Outcome(null, null, List.copyOf(made));
    }

    /** {@link BrowserVerifier}'s own mark for an application that never answered its probe. */
    private static String didNotStart(List<PageCheck> pages) {
        for (PageCheck page : pages) {
            if (page.assertions() == null) {
                continue;
            }
            for (AssertionResult assertion : page.assertions()) {
                if ("did-not-start".equals(assertion.selector())) {
                    return assertion.message();
                }
            }
        }
        return null;
    }
}
