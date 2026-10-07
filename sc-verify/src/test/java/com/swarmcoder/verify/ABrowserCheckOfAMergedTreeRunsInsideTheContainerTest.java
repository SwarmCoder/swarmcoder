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
import com.swarmcoder.domain.HostExecution;
import com.swarmcoder.domain.PageCheck;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser check that final integration and story delivery run on a merged tree happens inside
 * the tree's container: the application is started there and a browser in the same container
 * looks at it. Nothing of it runs on this PC, and the container still has no network.
 *
 * <p>With the image that has no browser in it the check does not fall back to this PC. It says it
 * could not run, and names the image to build.
 */
class ABrowserCheckOfAMergedTreeRunsInsideTheContainerTest {

    private static final String UI_IMAGE = "swarmcoder-worker-ui:latest";

    /** A page served by node, which the UI image carries; {PORT} is filled in by the verifier. */
    private static final String SERVE = "node -e \"require('http').createServer((q,s)=>{"
        + "s.setHeader('content-type','text/html');"
        + "s.end('<html><body><h1 id=ok>served from '+process.cwd()+'</h1></body></html>')})"
        + ".listen({PORT},'127.0.0.1')\"";

    @TempDir
    Path tree;

    @Test
    @RunsWhen(value = Need.DOCKER, image = UI_IMAGE)
    void theApplicationIsStartedAndLookedAtInsideTheContainer() throws Exception {
        assertThat(HostExecution.allowedBy()).isEmpty();
        Files.writeString(tree.resolve("README.md"), "a merged tree\n");
        List<byte[]> screenshots = new ArrayList<>();
        VerifySpec.BrowserSpec spec = new VerifySpec.BrowserSpec(SERVE, null, 60,
            List.of(new VerifySpec.PageCheckSpec("/", true, List.of("#ok"), true)), 0);
        StringBuilder log = new StringBuilder();

        BrowserCheckResults results;
        String containerId;
        try (BuildBoxes.Box box = BuildBoxes.of(manager()).open(tree, "Final integration", true)) {
            containerId = box.containerId();
            assertThat(box.target().browserChecksRunInside()).isTrue();
            results = new BrowserVerifier(png -> {
                screenshots.add(png);
                return "blob-" + screenshots.size();
            }).run(box.target(), spec, log);
        }
        System.out.println("[BROWSER-INSIDE] container " + containerId + " (removed)\n" + log);

        assertThat(results.couldNotTry()).as(log.toString()).isFalse();
        assertThat(results.checks()).hasSize(1);
        PageCheck page = results.checks().get(0);
        assertThat(page.loaded()).as(log.toString()).isTrue();
        assertThat(page.assertions()).as(log.toString()).isNotEmpty()
            .allMatch(AssertionResult::passed);
        assertThat(page.screenshotRef()).isEqualTo("blob-1");
        assertThat(screenshots.get(0)).as("a PNG came back over the exec channel")
            .startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(log.toString()).contains("(inside the container)");
    }

    @Test
    @RunsWhen(Need.DOCKER)
    void withoutTheBrowserImageItSaysWhatIsMissingAndRunsNothingHere() throws Exception {
        assertThat(HostExecution.allowedBy()).isEmpty();
        VerifySpec.BrowserSpec spec = new VerifySpec.BrowserSpec(SERVE, null, 30,
            List.of(new VerifySpec.PageCheckSpec("/", true, List.of("#ok"), false)), 0);
        StringBuilder log = new StringBuilder();
        DockerSandboxManager docker = manager();

        BrowserCheckResults results;
        // The worker image itself: started by hand so that the UI image is not picked.
        DockerSandboxManager.SandboxHandle handle =
            docker.launch(tree.toAbsolutePath().toString(), "", List.of());
        try {
            SandboxExecTarget target =
                new SandboxExecTarget(docker, handle.containerId()).browserChecksInside();
            results = new BrowserVerifier(BlobSink.NONE).run(target, spec, log);
        } finally {
            docker.kill(handle);
        }
        System.out.println("[BROWSER-INSIDE] container " + handle.containerId() + " (removed)");

        assertThat(results.couldNotTry()).as(log.toString()).isTrue();
        assertThat(results.couldNotTryReason())
            .contains("has no browser in it")
            .contains("docker build -t " + DockerSandboxManager.uiImage()
                + " sc-sandbox/images/sc-java-ui")
            .contains("Nothing was run on this PC instead");
    }

    private static DockerSandboxManager manager() {
        return new DockerSandboxManager(
            System.getProperty("swarmcoder.sandbox.image", "swarmcoder-worker:latest"), 2, 4,
            System.getProperty("swarmcoder.sandbox.dockerHost"),
            Path.of(System.getProperty("user.home"), ".m2").toString());
    }
}
