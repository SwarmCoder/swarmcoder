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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's report says, in words nobody can read past, when a paid role's calls were too large,
 * when it made more calls than its work needs, and when the server counted more input than the
 * requests held (section 94). The figures of the first test are the build of 2026-10-13, whose
 * report showed them in a table and warned of nothing.
 */
class UsageReportWarningsTest {

    private static ModelUsageRecord row(String role, boolean paid, int calls, long inputPerCall,
                                        long charsPerCall) {
        ModelUsageRecord row = new ModelUsageRecord(role, null, "a-model",
            paid ? ModelUsageRecord.CLOUD : ModelUsageRecord.LOCAL);
        for (int i = 0; i < calls; i++) {
            row.addCall(inputPerCall, 100, -1, 1_000, false, false);
            row.addRequest(inputPerCall, charsPerCall, i == 0 ? -1 : charsPerCall * 9 / 10);
        }
        return row;
    }

    @Test
    void theBuildThatCostFifteenMillionTokensIsWarnedAboutRoleByRole() {
        List<ModelUsageRecord> models = List.of(
            row("reviewer", true, 121, 75_400, 150_000),
            row("architect", true, 55, 83_100, 170_000),
            row("test author", true, 27, 37_000, 140_000),
            row("planner", true, 6, 27_300, 100_000),
            row("worker", false, 249, 18_300, 70_000));

        String warnings = UsageReport.warnings(models);

        assertThat(warnings.lines().filter(line -> line.startsWith("WARNING: "))).hasSize(7);
        assertThat(warnings)
            .contains("WARNING: the reviewer was sent 75400 input tokens a call on a paid server "
                + "(121 calls, the largest 75400); above 30000")
            .contains("WARNING: the reviewer made 121 calls on a paid server; its work in one "
                + "run needs at most 16.")
            .contains("WARNING: the architect was sent 83100 input tokens a call")
            .contains("WARNING: the architect made 55 calls on a paid server; its work in one "
                + "run needs at most 40.")
            .contains("WARNING: the test author was sent 37000 input tokens a call")
            .contains("WARNING: the paid server counted 9123400 input tokens for the reviewer's "
                + "requests of 18150000 characters in all (2.0 characters a token)")
            .contains("WARNING: the paid server counted 4570500 input tokens for the "
                + "architect's requests of 9350000 characters in all (2.0 characters a token)");
        assertThat(warnings).as("6 calls of 27,300 are the planner's work")
            .doesNotContain("planner");
        assertThat(warnings).as("local tokens are not money").doesNotContain("worker");

        String report = UsageReport.render("One run:", models, List.of());
        assertThat(report.indexOf("WARNING: the reviewer"))
            .as("the warnings stand above the table").isBetween(0, report.indexOf("| role |"));
        assertThat(report)
            .contains("Requests per role, as they went out:")
            .contains("- reviewer (paid cloud): 18150000 characters sent for 9123400 input "
                + "tokens counted (2.0 a token); 90% of a request repeated the one before it "
                + "from the start");
    }

    @Test
    void aRunWhoseRolesDidTheirWorkInSmallCallsIsNotWarnedAbout() {
        List<ModelUsageRecord> models = List.of(
            row("reviewer", true, 6, 9_000, 36_000),
            row("architect", true, 30, 24_000, 96_000),
            row("worker", true, 300, 12_000, 48_000));

        assertThat(UsageReport.warnings(models)).isEmpty();
        assertThat(UsageReport.render("One run:", models, List.of())).doesNotContain("WARNING");
    }

    @Test
    void aRowFromBeforeRequestsWereMeasuredSaysNothingAboutThem() {
        ModelUsageRecord old = new ModelUsageRecord("architect", null, "a-model",
            ModelUsageRecord.CLOUD);
        old.addCall(20_000, 100, -1, 1_000, false, false);

        assertThat(UsageReport.requestLines(List.of(old))).isEmpty();
        assertThat(UsageReport.warnings(List.of(old))).isEmpty();
    }
}
