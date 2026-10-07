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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code static:<dir>} browser check is looked at by a browser inside the container, like a
 * served application. No Docker here: a recording target stands in for a container, and what is
 * asserted is where the work goes and what is said when the browser is not there.
 */
class StaticPagesAreRenderedInsideTheContainerTest {

    private static final VerifySpec.BrowserSpec STATIC = new VerifySpec.BrowserSpec("static:dist",
        null, 30, List.of(new VerifySpec.PageCheckSpec("/", true, List.of("#ok"), false)), 0);

    /** A container target; {@code inside} is whether it was started for browser checks. */
    private static final class FakeContainer implements ExecTarget {
        final List<String> commands = new ArrayList<>();
        final List<String> services = new ArrayList<>();
        final boolean inside;

        FakeContainer(boolean inside) {
            this.inside = inside;
        }

        @Override public ExecResult exec(String command, int timeoutSeconds) {
            commands.add(command);
            return new ExecResult(0, "", false, Duration.ZERO); // no SC_BROWSER_PRESENT
        }
        @Override public String readFile(String relativePath, int maxBytes) throws IOException {
            throw new IOException("a container's files are not read from this PC in this test");
        }
        @Override public List<String> listFiles(String relativeDir, String suffix) {
            return List.of();
        }
        @Override public void deleteDir(String relativePath) { }
        @Override public ServiceHandle startService(String command) {
            services.add(command);
            throw new UnsupportedOperationException("no service in this test");
        }
        @Override public Optional<String> hostCannotReachServices() {
            return Optional.of("inside a container");
        }
        @Override public boolean browserChecksRunInside() {
            return inside;
        }
    }

    @Test
    void staticPagesGoToTheBrowserInTheContainerAndAMissingImageSaysHowToBuildIt() {
        FakeContainer container = new FakeContainer(true);

        BrowserCheckResults results = new BrowserVerifier(BlobSink.NONE)
            .run(container, STATIC, new StringBuilder());

        assertThat(container.commands).as("the container was asked whether it has a browser")
            .anySatisfy(command -> assertThat(command).contains("SC_BROWSER_PRESENT"));
        assertThat(results.couldNotTry()).isTrue();
        assertThat(results.couldNotTryReason())
            .contains("docker build -t")
            .contains("sc-sandbox/images/sc-java-ui")
            .contains("Nothing was run on this PC instead");
    }

    @Test
    void aContainerNotStartedForBrowserChecksDeclinesAndDoesNotRenderOnThisPc() {
        FakeContainer container = new FakeContainer(false);

        BrowserCheckResults results = new BrowserVerifier(BlobSink.NONE)
            .run(container, STATIC, new StringBuilder());

        assertThat(results.couldNotTry()).isTrue();
        assertThat(results.couldNotTryReason()).contains("Nothing was run on this PC instead");
        assertThat(container.commands).isEmpty();
    }

    @Test
    void theServerCommandServesTheFolderOnTheContainersLoopbackAndAnswersTheReadinessPath() {
        String command = BrowserVerifier.staticServerCommand("dist/out", 18080);

        String encoded = command.substring(command.indexOf("printf %s '") + 11,
            command.indexOf("' | base64"));
        String js = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

        assertThat(js).contains("\"dist/out\"").contains(".listen(18080,'127.0.0.1')")
            .contains(BrowserVerifier.STATIC_READY_PATH)
            .doesNotContain("DIR").doesNotContain("PORT");
        assertThat(command).endsWith("exec node \"$F\"");
    }
}
