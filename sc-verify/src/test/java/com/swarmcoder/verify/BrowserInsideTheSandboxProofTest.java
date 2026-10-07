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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proof that a candidate's own sandbox can start its application and look at it with a real
 * browser — with no network, and with the browser inside the same container.
 *
 * <p>This test exists to retire a conclusion. An earlier investigation measured that a container
 * started with {@code --network none} publishes no ports and cannot be reached from the host, and
 * concluded that browser checks can therefore only run at final integration. The measurement was
 * right and the conclusion was too strong: {@code --network none} removes the route in from the
 * host, not the container's own loopback. The same investigation had already measured that the
 * container serves itself perfectly on {@code 127.0.0.1}. A browser in that container therefore has
 * no network problem to solve.
 *
 * <p>What is asserted here, in order, is the whole chain:
 * <ol>
 *   <li>the container really has no network — only {@code lo}, and no egress;</li>
 *   <li>the real {@code dev/bookshelf-demo} application is built and started inside it, through
 *       {@link SandboxExecTarget#startService}, using the same shape of command
 *       {@link WebAppDetection} proposes;</li>
 *   <li>it answers on the container's own loopback;</li>
 *   <li>headless Chromium, running in that same container, loads the page, sees the application's
 *       own markup, and takes a screenshot;</li>
 *   <li>the screenshot comes back through the Docker exec channel as real PNG bytes;</li>
 *   <li>closing the handle actually stops the service, leaving nothing running.</li>
 * </ol>
 *
 * <p>Run it with:
 * {@code mvn -o -B test -pl sc-verify -am -Dtest=BrowserInsideTheSandboxProofTest
 * -Dswarmcoder.demo.repo=<absolute path to dev/bookshelf-demo>}
 *
 * <p>It needs the UI sandbox image, which is not the default one:
 * {@code docker build -t swarmcoder-worker-ui:latest sc-sandbox/images/sc-java-ui}.
 *
 * <p>The repository is <b>copied</b> before it is mounted. The serve command runs the project's own
 * build, and the repository this points at is the operator's, not a scratch one.
 */
class BrowserInsideTheSandboxProofTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UI_IMAGE = "swarmcoder-worker-ui:latest";
    private static final String BEGIN = "<<<SC-BROWSER-JSON";
    private static final String END = "SC-BROWSER-JSON>>>";

    /**
     * The application hardcodes this port, and so does its own Dockerfile. It is also the port the
     * action server used to sit on, which is why {@link DockerSandboxManager} moved that elsewhere:
     * with both wanting 8080 the application silently lost the bind and the browser would have been
     * shown the action server's 404 instead.
     */
    private static final int APP_PORT = 8080;

    @Test
    @RunsWhen(value = {Need.DOCKER, Need.DEMO_REPO}, image = UI_IMAGE)
    void aBrowserInsideANetworklessSandboxLoadsTheCandidatesRunningApplication(@TempDir Path tmp)
            throws Exception {
        Path source = Path.of(System.getProperty("swarmcoder.demo.repo"));
        Path workspace = tmp.resolve("workspace");
        // Sources only, for two reasons. A candidate's worktree is a fresh git worktree with no
        // build output in it, so this is what a real one looks like. And a copied-in target/ would
        // break the build outright: files on a Windows bind mount arrive owned by root, and
        // maven-resources preserves timestamps, so overwriting one fails with "Operation not
        // permitted" — the build would die on the harness's own copying rather than on anything
        // about the candidate.
        copySourcesOnly(source, workspace);

        DockerSandboxManager sandbox = new DockerSandboxManager(
            UI_IMAGE, 2, 4, null, Path.of(System.getProperty("user.home"), ".m2").toString());
        assertThat(sandbox.network())
            .as("the whole question is whether this works with the isolation ON")
            .isEqualTo(DockerSandboxManager.NetworkPolicy.NONE);

        DockerSandboxManager.SandboxHandle handle =
            sandbox.launch(workspace.toAbsolutePath().toString(), "");
        StringBuilder evidence = new StringBuilder();
        try {
            ExecTarget target = new SandboxExecTarget(sandbox, handle.containerId());

            // 1. There really is no network. Not "we did not use it" — there is no interface to
            //    use. `lo` is all a --network none container has, and it is enough.
            assertThat(handle.actionServerUrl())
                .as("no port is published, so there is no action-server URL to hand out")
                .isNull();
            String interfaces = target.exec(
                "sed -n '3,$p' /proc/net/dev | awk -F: '{print $1}' | tr -d ' ' | sort | tr '\\n' ' '",
                30).output().trim();
            assertThat(interfaces).as("network interfaces inside the container").isEqualTo("lo");
            ExecResult egress = target.exec(
                "curl -s --max-time 5 -o /dev/null https://example.com; echo rc=$?", 30);
            assertThat(egress.output()).as("outbound network must fail").doesNotContain("rc=0");
            evidence.append("interfaces=").append(interfaces)
                .append(" egress=").append(egress.output().trim()).append('\n');

            // 2. Build and start the application, exactly as the detector proposes it: the module's
            //    own package build, then a plain java -cp on what that build produced.
            String serve = "mvn -o -q -B -pl bookshelf-demo-server -am package -Dmaven.test.skip=true"
                + " && java -cp \"bookshelf-demo-server/target/classes"
                + ":bookshelf-demo-server/target/libs/*\""
                + " com.swarmcoder.demo.bookshelf.server.ServerApp";

            Instant startedAt = Instant.now();
            try (ExecTarget.ServiceHandle service = target.startService(serve)) {

                // 3. Readiness is probed from INSIDE, because outside there is nothing to probe.
                boolean ready = awaitReadyInContainer(target, service, Duration.ofMinutes(15));
                Duration boot = Duration.between(startedAt, Instant.now());
                assertThat(ready)
                    .as("the application must answer on the container's own loopback; service log:\n"
                        + tail(service.outputSoFar(), 40))
                    .isTrue();
                evidence.append("build+boot=").append(boot.toSeconds()).append("s\n");

                // 4. The browser runs in the same container and reaches the app over loopback.
                JsonNode page = runInContainerBrowser(target);
                evidence.append("title=").append(page.path("title").asText())
                    .append("\nbodyText=").append(page.path("bodyText").asText()).append('\n')
                    .append("consoleErrors=").append(page.path("consoleErrors").toString())
                    .append('\n');

                assertThat(page.path("loaded").asBoolean())
                    .as("the browser inside the container must load the page; result:\n" + page)
                    .isTrue();
                assertThat(page.path("assertions")).allSatisfy(assertion ->
                    assertThat(assertion.path("passed").asBoolean())
                        .as("assertion " + assertion.path("selector").asText() + ": "
                            + assertion.path("message").asText())
                        .isTrue());
                assertThat(page.path("bodyText").asText())
                    .as("the page must be the application, not its own 'bundle did not load' notice")
                    .doesNotContain("The application bundle did not load");

                // 5. The screenshot comes back through the exec channel as real PNG bytes.
                byte[] png = Base64.getDecoder().decode(page.path("screenshotBase64").asText());
                assertThat(png.length).as("a screenshot of the running application").isGreaterThan(2000);
                assertThat(new byte[] {png[0], png[1], png[2], png[3]})
                    .as("PNG magic bytes").containsExactly((byte) 0x89, 'P', 'N', 'G');
                Path saved = Path.of("target", "browser-in-sandbox.png");
                Files.createDirectories(saved.getParent());
                Files.write(saved, png);
                evidence.append("screenshot=").append(png.length).append(" bytes -> ")
                    .append(saved.toAbsolutePath()).append('\n');

                // 6. Closing the handle really stops it. This is the leak question, and the answer
                //    has to be "nothing of it is left", not "we sent a signal".
                service.close();
                assertThat(service.isAlive()).as("the handle reports the service stopped").isFalse();
                ExecResult leftovers = target.exec(
                    "ps -eo stat,args | grep -v grep | grep ServerApp | grep -v '^Z' | wc -l", 30);
                assertThat(leftovers.output().trim())
                    .as("no application process may survive close(); ps said:\n"
                        + target.exec("ps -eo pid,pgid,stat,args", 30).output())
                    .isEqualTo("0");
            }
        } finally {
            System.out.println("[browser-in-sandbox proof]\n" + evidence);
            sandbox.kill(handle);
        }
    }

    /** Polls the application from inside the container until it answers or the deadline passes. */
    private static boolean awaitReadyInContainer(ExecTarget target, ExecTarget.ServiceHandle service,
                                                 Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!service.isAlive()) {
                return false;
            }
            ExecResult probe = target.exec("curl -fsS -o /dev/null --max-time 2 "
                + "http://127.0.0.1:" + APP_PORT + "/ && echo SC_READY", 30);
            if (probe.output().contains("SC_READY")) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    /**
     * Drives the in-container checker. The spec goes in base64-encoded — the Engine exec API
     * carries a command, not a stdin stream — and the whole result, screenshot included, comes back
     * on stdout between two markers so Chromium's own chatter on stderr cannot corrupt it.
     */
    private static JsonNode runInContainerBrowser(ExecTarget target) throws Exception {
        String spec = JSON.writeValueAsString(JSON.createObjectNode()
            .put("baseUrl", "http://127.0.0.1:" + APP_PORT)
            .set("checks", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("url", "/")
                .put("screenshot", true)
                .set("assertVisible", JSON.createArrayNode().add("#app-root")))));
        String encoded = Base64.getEncoder().encodeToString(spec.getBytes(StandardCharsets.UTF_8));

        ExecResult run = target.exec(
            "printf %s '" + encoded + "' | base64 -d | node \"${SC_BROWSER_CHECK:-/opt/sc-browser/check.js}\"",
            180);
        String output = run.output();
        int from = output.indexOf(BEGIN);
        int to = output.indexOf(END);
        assertThat(from).as("the in-container checker must emit its JSON; output:\n" + output)
            .isGreaterThanOrEqualTo(0);
        JsonNode result = JSON.readTree(output.substring(from + BEGIN.length(), to).trim());
        assertThat(result.path("ok").asBoolean())
            .as("the in-container browser run itself; result:\n" + result)
            .isTrue();
        return result.path("pages").get(0);
    }

    /** Copies a repository's sources: no {@code .git}, no build output. */
    private static void copySourcesOnly(Path source, Path target) throws Exception {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(path);
                if (relative.startsWith(".git") || hasNamedElement(relative, "target")) {
                    continue;
                }
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private static boolean hasNamedElement(Path relative, String name) {
        for (Path element : relative) {
            if (element.toString().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\\R", -1);
        if (all.length <= lines) {
            return text;
        }
        return String.join("\n", java.util.Arrays.copyOfRange(all, all.length - lines, all.length));
    }
}
