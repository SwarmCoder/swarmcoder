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

import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared mechanics of running a test stage: clear report dirs, run commands, collect JUnit XML,
 * parse structurally. Used by the verification pipeline and by the {@link RedChecker}
 * (TEST_AUTHORING red-check), which need identical semantics.
 */
final class TestStageRunner {

    static final int MAX_REPORT_FILE_BYTES = 2 * 1024 * 1024;

    /** Marker inside TestResults for "stage could not produce a trustworthy result". */
    static final String INFRA_TEST_ID_SUFFIX = "-infrastructure";

    private TestStageRunner() {}

    record StageOutcome(TestResults results, boolean reportsFound, boolean infrastructureError) {}

    static StageOutcome run(ExecTarget target, String stage, List<String> commands,
                            List<String> reportDirs, int timeoutSeconds, StringBuilder fullLog) {
        try {
            for (String dir : reportDirs) {
                target.deleteDir(dir);
            }
        } catch (IOException e) {
            log(fullLog, "[" + stage + "] failed to clear report dirs: " + e.getMessage());
        }

        boolean anyCommandFailed = false;
        for (String command : commands) {
            log(fullLog, "[" + stage + "] $ " + command);
            ExecResult result;
            try {
                result = target.exec(command, timeoutSeconds);
            } catch (IOException e) {
                log(fullLog, "[" + stage + "] EXEC TARGET ERROR: " + e.getMessage());
                return new StageOutcome(infraError(stage, "exec target unreachable: " + e.getMessage()),
                    false, true);
            }
            if (!result.output().isBlank()) {
                fullLog.append(result.output());
                if (!result.output().endsWith("\n")) {
                    fullLog.append('\n');
                }
            }
            log(fullLog, "[" + stage + "] exit=" + result.exitCode()
                + (result.timedOut() ? " (TIMED OUT)" : "") + " in " + result.duration().toSeconds() + "s");
            if (!result.succeeded()) {
                anyCommandFailed = true; // expected when tests fail; the XML decides
            }
        }

        List<String> xmlContents = collectReports(target, reportDirs, fullLog);
        if (xmlContents.isEmpty()) {
            if (anyCommandFailed) {
                return new StageOutcome(
                    infraError(stage, "test command failed and produced no JUnit XML reports"),
                    false, true);
            }
            // Commands exited 0 and the report dirs — cleared a moment ago — are empty. A JUnit
            // runner writes a report for everything it runs, so this is not "we could not read the
            // result": it is the result. The runner selected nothing and ran nothing. That is
            // EXECUTED with a count of zero, and it is the exact shape of the defect in §15.2 —
            // a selector that matches no test looks identical to a green suite from the outside.
            log(fullLog, "[" + stage + "] warning: commands succeeded but no JUnit XML reports found in "
                + reportDirs + " — the stage ran ZERO tests");
            return new StageOutcome(
                new TestResults(0, 0, 0, 0, List.of()).withStageOutcome(TestStageOutcome.EXECUTED),
                false, false);
        }

        TestResults results =
            JUnitXmlParser.parse(xmlContents).withStageOutcome(TestStageOutcome.EXECUTED);
        log(fullLog, "[" + stage + "] " + results.passed() + " passed, " + results.failed()
            + " failed, " + results.errored() + " errored, " + results.skipped() + " skipped");
        return new StageOutcome(results, true, false);
    }

    private static List<String> collectReports(ExecTarget target, List<String> reportDirs, StringBuilder fullLog) {
        List<String> contents = new ArrayList<>();
        for (String dir : reportDirs) {
            try {
                for (String file : target.listFiles(dir, ".xml")) {
                    String xml = target.readFile(file, MAX_REPORT_FILE_BYTES);
                    if (xml != null) {
                        contents.add(xml);
                    }
                }
            } catch (IOException e) {
                log(fullLog, "[reports] failed reading " + dir + ": " + e.getMessage());
            }
        }
        return contents;
    }

    private static TestResults infraError(String stage, String message) {
        return new TestResults(0, 0, 1, 0,
            List.of(new TestFailure(stage + INFRA_TEST_ID_SUFFIX, message, "")))
            .withStageOutcome(TestStageOutcome.INCONCLUSIVE);
    }

    private static void log(StringBuilder fullLog, String line) {
        fullLog.append(line).append('\n');
    }
}
