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

import com.swarmcoder.domain.BrowserCheckResults;
import com.swarmcoder.domain.BrowserStageOutcome;
import com.swarmcoder.domain.PageCheck;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point, end to end, against a real application: the detector looks at a repository it has
 * never seen, proposes a way to start it, and that proposal starts it for real and a browser loads
 * its page.
 *
 * <p>Everything else about this feature can be proved with fixtures, and fixtures cannot answer the
 * only question that matters — whether the command the detector writes down actually launches a
 * real, dependency-heavy, CDI-container application. This one does. It is opt-in for the same reason
 * {@code DemoRepoVerificationTest} is: the repository it wants is a separate checkout, and it starts
 * a server and drives a browser, which is a thing a person runs deliberately.
 *
 * <p>Run it with:
 * {@code mvn -o -B test -pl sc-verify -am -Dtest=DetectedBrowserBlockStartsTheRealAppTest
 * -Dswarmcoder.demo.repo=<absolute path to a copy of dev/bookshelf-demo>}
 *
 * <p>Use a COPY. The serve command runs the project's own build, and the repository this points at
 * is the operator's, not a scratch one.
 */
class DetectedBrowserBlockStartsTheRealAppTest {

    @Test
    @RunsWhen({Need.CHROMIUM, Need.DEMO_REPO})
    void theProposedServeCommandStartsTheApplicationAndTheBrowserSeesIt() {
        Path repo = Path.of(System.getProperty("swarmcoder.demo.repo"));

        ToolchainDetector.Detection detection = ToolchainDetector.detect(repo);
        assertThat(detection.recognised()).isTrue();
        VerifySpec.BrowserSpec browser = detection.proposed().browser();
        assertThat(browser)
            .as("this repository serves a web page, so a browser block must have been proposed")
            .isNotNull();

        AtomicReference<byte[]> screenshot = new AtomicReference<>();
        BlobSink sink = content -> {
            screenshot.set(content);
            return "screenshot";
        };

        StringBuilder log = new StringBuilder();
        BrowserCheckResults results = new BrowserVerifier(sink)
            .run(new LocalProcessExecTarget(repo), browser, log);

        assertThat(results.stageOutcome())
            .as("the harness must have got as far as trying; log:\n" + log)
            .isEqualTo(BrowserStageOutcome.EXECUTED);

        PageCheck front = results.checks().get(0);
        assertThat(front.loaded()).as("the application must serve its front page; log:\n" + log).isTrue();
        assertThat(front.assertions()).allMatch(a -> a.passed(),
            "every assertion on the front page; log:\n" + log);
        assertThat(front.consoleErrors()).isEmpty();
        assertThat(screenshot.get()).as("a screenshot of the running application").isNotEmpty();
    }
}
