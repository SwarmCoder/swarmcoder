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

import java.util.List;

/**
 * Per-repo verification contract, loaded from {@code .swarmcoder/verify.yaml} in the target
 * repository (spec §10.1). Command lists are shell strings executed in pipeline order;
 * a null/empty list means the stage is skipped.
 *
 * <p>Non-JVM toolchains must emit JUnit-format XML for structural parsing:
 * vitest/jest {@code --reporter=junit}, pytest {@code --junitxml=...}, cargo-nextest junit
 * output. Set {@code testReports} explicitly for these until their defaults harden.
 *
 * @param toolchain       gradle | maven | node | cargo | python — selects default test-report
 *                        locations when {@code testReports} is not given
 * @param compile         compile-stage commands; all must exit 0
 * @param acceptance      acceptance-test commands (protected test dir only)
 * @param existing        existing-test (regression) commands
 * @param lint            lint commands; failures are recorded but currently advisory
 * @param lintReports     optional dirs with checkstyle-format XML; when set, lint results are
 *                        parsed structurally instead of derived from exit codes
 * @param testReports     optional override for where JUnit XML reports land
 * @param timeoutSeconds  per-command timeout; 0 or negative selects the default (1800s)
 * @param browser         browser-check block (spec §10.3); runs as the final stage
 */
public record VerifySpec(
    String toolchain,
    List<String> compile,
    List<String> acceptance,
    List<String> existing,
    List<String> lint,
    List<String> lintReports,
    TestReportsSpec testReports,
    int timeoutSeconds,
    BrowserSpec browser
) {
    public static final int DEFAULT_TIMEOUT_SECONDS = 1800;

    public record TestReportsSpec(List<String> acceptance, List<String> existing) {}

    /**
     * Browser-check block (spec §10.3). {@code serve} is either a shell command containing a
     * {@code {PORT}} placeholder, or {@code static:<dir>} to serve a workspace directory
     * (e.g. {@code static:dist}) through a throwaway HTTP server. {@code readyProbe} is an
     * URL (with {@code {PORT}}) polled until it returns 2xx; ignored in static mode.
     *
     * <p><b>{@code port} is for applications that cannot be told which port to use.</b> Left at 0
     * the harness picks a free one and substitutes it for {@code {PORT}}, which is right for
     * anything configurable and is what every framework runner supports. But plenty of real
     * applications hardcode the port in {@code main} — the demo repository in this checkout calls
     * {@code Zeroz4jServer.start(8080, …)} — and for those the placeholder is a lie: the app binds
     * its own port whatever was substituted, the browser is pointed at a different one, and every
     * page "fails to load" for a reason that has nothing to do with the candidate. Naming the real
     * port is what makes the check honest.
     *
     * <p>The price of naming one is that two verifications on the same machine collide and the
     * second application cannot bind. That is recorded as
     * {@link com.swarmcoder.domain.BrowserStageOutcome#COULD_NOT_TRY} rather than as a failure (see
     * {@link BrowserVerifier}), so a collision never kills a candidate.
     */
    public record BrowserSpec(String serve, String readyProbe, int readyTimeoutSeconds,
                              List<PageCheckSpec> checks, int port) {
        public static final int DEFAULT_READY_TIMEOUT_SECONDS = 60;

        /** The shape from before a fixed port was expressible; keeps existing callers compiling. */
        public BrowserSpec(String serve, String readyProbe, int readyTimeoutSeconds,
                           List<PageCheckSpec> checks) {
            this(serve, readyProbe, readyTimeoutSeconds, checks, 0);
        }

        public int effectiveReadyTimeoutSeconds() {
            return readyTimeoutSeconds > 0 ? readyTimeoutSeconds : DEFAULT_READY_TIMEOUT_SECONDS;
        }

        /** True when the application binds a port of its own that the harness may not choose. */
        public boolean hasFixedPort() {
            return port > 0;
        }
    }

    /**
     * One look at the application. Without {@code steps} it is one page: {@code url} is loaded
     * and the selectors must be visible. With {@code steps} it is a <b>journey</b>: the browser
     * starts at {@code url} - the page a user starts on - and then does only what a user can do
     * there: click, type, press a key, and look. There is no step that loads another address, so
     * a screen the application's own navigation does not lead to cannot be arrived at, however
     * well it works when a test calls its code (the seven accepted stories whose screens no user
     * could open, 2026-10-05). The steps run in order and the first that fails ends the journey.
     */
    public record PageCheckSpec(String url, boolean assertNoConsoleErrors, List<String> assertVisible,
                                boolean screenshot, List<StepSpec> steps) {

        /** A contract that names no steps reads back the same as one built without them. */
        public PageCheckSpec {
            steps = steps == null ? List.of() : steps;
        }

        /** A page check, as before journeys existed. */
        public PageCheckSpec(String url, boolean assertNoConsoleErrors, List<String> assertVisible,
                             boolean screenshot) {
            this(url, assertNoConsoleErrors, assertVisible, screenshot, List.of());
        }

        public boolean isJourney() {
            return steps != null && !steps.isEmpty();
        }
    }

    /**
     * One step of a journey; exactly one of the actions is set. Selectors are the browser
     * driver's: CSS, {@code text=Logbook}, {@code role=button[name="Add contact"]}.
     *
     * @param click         the element to click
     * @param fill          the field to type into, with {@code value}
     * @param value         what is typed into {@code fill}
     * @param press         a key pressed on the page, e.g. {@code Enter}
     * @param expectVisible an element that must be (or become) visible
     * @param expectHidden  an element that must be absent or hidden
     */
    public record StepSpec(String click, String fill, String value, String press,
                           String expectVisible, String expectHidden) {

        /** The step in words, for the log and for the result: {@code click text=Logbook}. */
        public String describe() {
            if (click != null) {
                return "click " + click;
            }
            if (fill != null) {
                return "fill " + fill;
            }
            if (press != null) {
                return "press " + press;
            }
            if (expectVisible != null) {
                return "expect visible " + expectVisible;
            }
            if (expectHidden != null) {
                return "expect hidden " + expectHidden;
            }
            return "nothing";
        }
    }

    public int effectiveTimeoutSeconds() {
        return timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    }

    /** Report directories for the acceptance stage: explicit config, else toolchain default. */
    public List<String> acceptanceReportDirs() {
        if (testReports != null && testReports.acceptance() != null && !testReports.acceptance().isEmpty()) {
            return testReports.acceptance();
        }
        return defaultReportDirs();
    }

    /** Report directories for the existing-tests stage: explicit config, else toolchain default. */
    public List<String> existingReportDirs() {
        if (testReports != null && testReports.existing() != null && !testReports.existing().isEmpty()) {
            return testReports.existing();
        }
        return defaultReportDirs();
    }

    private List<String> defaultReportDirs() {
        String tc = toolchain == null ? "" : toolchain.toLowerCase();
        return switch (tc) {
            case "gradle" -> List.of("build/test-results");
            case "maven" -> List.of("target/surefire-reports", "target/failsafe-reports");
            case "node" -> List.of("test-results");          // vitest/jest --reporter=junit convention
            case "cargo" -> List.of("target/nextest");        // cargo-nextest junit output
            case "python" -> List.of("test-reports");         // pytest --junitxml=test-reports/junit.xml
            default -> List.of();
        };
    }
}
