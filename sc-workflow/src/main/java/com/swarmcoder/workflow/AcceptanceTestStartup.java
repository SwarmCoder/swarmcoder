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
package com.swarmcoder.workflow;

import com.swarmcoder.verify.BrokenAtStartup;

/**
 * The words for an acceptance test that compiles and then dies before any code under test runs —
 * the workflow's half of {@link BrokenAtStartup}, which holds the rule and the run it comes from
 * (live harness run 51, 2026-09-30: a test booted the Helidon MP / CDI container, the container
 * died at class level, and about 90 minutes of model time went on candidates no worker could save,
 * because a worker may not edit the test).
 *
 * <p>The shape of what happens next is the one used for a test that does not compile
 * ({@link MiscompiledAcceptanceTest}) and for one that reaches browser-only code
 * ({@link AcceptanceTestReach}): at TEST_AUTHORING the author is asked once with the failure
 * itself, then the run parks; at wave time the run parks before dispatch.
 */
final class AcceptanceTestStartup {

    private AcceptanceTestStartup() {}

    /** What the test author is told, once, before the run parks. */
    static String reask(BrokenAtStartup.Finding finding) {
        return "Your test compiles, but it never gets to test anything: " + finding.reason() + ". "
            + "A worker may not edit your test, so every candidate would die on the same failure "
            + "whatever it wrote. The failure as the runner reported it:\n\n"
            + finding.failureText()
            + "\n\nAn acceptance test calls the project's own classes directly: construct the "
            + "service or store, call its methods, assert on what comes back. It does NOT boot the "
            + "application server or a DI/CDI container (no @HelidonTest, SeContainer, Server.start "
            + "and the like), because that setup is the application's business and it fails before "
            + "any of the delivered code runs. Rewrite the test so it reaches the SAME checks "
            + "through the classes themselves — do not change what a check proves, only how the "
            + "test reaches it. Reply with the same JSON object, with the corrected file(s).";
    }

    /** The brief a run parks with when the failing test cannot be corrected. */
    static String park(String taskTitle, BrokenAtStartup.Finding finding, String note) {
        return "The acceptance test for task '" + taskTitle + "' fails before any code under test "
            + "runs: " + finding.reason() + ".\n\n" + finding.failureText() + "\n\n"
            + (note == null || note.isBlank() ? "" : note.strip() + "\n\n")
            + "This is not a healthy red state. A test that errors while starting a server or "
            + "container has measured nothing about the work, and no candidate can make it pass: "
            + "workers may not edit an acceptance test (live harness run 51 spent about 90 minutes "
            + "of model time finding that out).\n\n"
            + "Correct the test so it calls the project's classes directly instead of starting the "
            + "application, then resume the run.";
    }
}
