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
import com.swarmcoder.domain.PageCheck;
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end browser verification against a static site served from a temp workspace.
 *
 * <p>Runs in an ordinary build wherever Playwright's Chromium is installed — no flag. On a machine without it, it is skipped and the skip is announced (see {@code @RunsWhen}).
 */
class BrowserVerifierTest {

    @TempDir
    Path workspace;

    @Test
    @RunsWhen(Need.CHROMIUM)
    void staticSiteChecksPassAndFailCorrectly() throws Exception {
        Files.createDirectories(workspace.resolve("dist"));
        Files.writeString(workspace.resolve("dist/index.html"), """
            <!doctype html>
            <html><head><title>demo</title></head>
            <body><nav id="main-nav">nav</nav><h1>Welcome</h1></body></html>
            """);

        VerifySpec.BrowserSpec spec = new VerifySpec.BrowserSpec("static:dist", null, 30, List.of(
            new VerifySpec.PageCheckSpec("/", true, List.of("#main-nav", "text=Welcome"), true),
            new VerifySpec.PageCheckSpec("/missing.html", false, List.of("#nothing"), false)));

        AtomicReference<byte[]> storedScreenshot = new AtomicReference<>();
        BlobSink sink = content -> {
            storedScreenshot.set(content);
            return "fake-ref";
        };

        BrowserCheckResults results = new BrowserVerifier(sink)
            .run(new LocalProcessExecTarget(workspace), spec, new StringBuilder());

        assertThat(results.checks()).hasSize(2);

        PageCheck index = results.checks().get(0);
        assertThat(index.loaded()).isTrue();
        assertThat(index.assertions()).allMatch(a -> a.passed());
        assertThat(index.screenshotRef()).isEqualTo("fake-ref");
        assertThat(storedScreenshot.get()).isNotEmpty();

        PageCheck missing = results.checks().get(1);
        // The 404 page still "loads" as a navigation, but its assertion must fail.
        assertThat(missing.assertions()).anyMatch(a -> !a.passed());
    }
}
