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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A browser check with steps is a journey: it starts at the entry page and from there only
 * clicks, types and looks, inside the container (the seven accepted stories whose screens no user
 * could open, 2026-10-05). A screen the entry page leads to is reached and used; a page that
 * exists and works but that nothing links to is not, and the journey fails at the step that
 * needed it.
 */
class AJourneyUsesOnlyTheApplicationsOwnNavigationTest {

    private static final String UI_IMAGE = "swarmcoder-worker-ui:latest";

    @TempDir
    Path tree;

    @Test
    void theContractDeclaresAJourneyAsStepsOfACheck() throws Exception {
        VerifySpec spec = VerifySpecLoader.parse("""
            toolchain: maven
            browser:
              serve: "static:site"
              checks:
                - url: "/"
                  assertNoConsoleErrors: true
                  steps:
                    - click: "text=Ledger"
                    - fill: "#payee"
                      value: "Hale"
                    - press: "Enter"
                    - expectVisible: "role=row[name='Hale']"
                    - expectHidden: "#empty"
                - url: "/"
                  assertVisible: ["body"]
            """);

        VerifySpec.PageCheckSpec journey = spec.browser().checks().get(0);
        assertThat(journey.isJourney()).isTrue();
        assertThat(journey.steps()).extracting(VerifySpec.StepSpec::describe).containsExactly(
            "click text=Ledger", "fill #payee", "press Enter",
            "expect visible role=row[name='Hale']", "expect hidden #empty");
        assertThat(journey.steps().get(1).value()).isEqualTo("Hale");
        assertThat(spec.browser().checks().get(1).isJourney())
            .as("a check without steps is a page check, as before").isFalse();
        assertThat(BrowserVerifier.checkerScript())
            .as("this build's checker travels with the request, so an image built before "
                + "journeys existed needs no rebuild")
            .contains("check.steps");
    }

    @Test
    void outsideAContainerWithABrowserAJourneyIsNotMadeAndNothingIsReportedAsLookedAt() {
        ExecTarget notAContainerWithABrowser = new ExecTarget() {
            @Override public ExecResult exec(String command, int timeoutSeconds) {
                throw new AssertionError("nothing may be run: " + command);
            }
            @Override public String readFile(String relativePath, int maxBytes) throws IOException {
                throw new IOException("not read in this test");
            }
            @Override public List<String> listFiles(String relativeDir, String suffix) {
                return List.of();
            }
            @Override public void deleteDir(String relativePath) { }
            @Override public ServiceHandle startService(String command) {
                throw new AssertionError("nothing may be started: " + command);
            }
            @Override public Optional<String> hostCannotReachServices() {
                return Optional.empty();
            }
        };
        VerifySpec.BrowserSpec spec = new VerifySpec.BrowserSpec("java -jar app.jar {PORT}", null,
            30, List.of(new VerifySpec.PageCheckSpec("/", false, List.of(), false,
                List.of(new VerifySpec.StepSpec("text=Ledger", null, null, null, null, null)))), 0);

        BrowserCheckResults results = new BrowserVerifier(BlobSink.NONE)
            .run(notAContainerWithABrowser, spec, new StringBuilder());

        assertThat(results.couldNotTry()).isTrue();
        assertThat(results.couldNotTryReason()).contains("journey")
            .contains("Nothing was run on this PC instead");
    }

    @Test
    @RunsWhen(value = Need.DOCKER, image = UI_IMAGE)
    void aScreenTheEntryPageLeadsToIsReachedAndOneNothingLinksToIsNot() throws Exception {
        assertThat(HostExecution.allowedBy()).isEmpty();
        Path site = Files.createDirectories(tree.resolve("site"));
        Files.writeString(site.resolve("index.html"), """
            <html><body><h1>Shop</h1><nav><a href="ledger.html">Ledger</a></nav></body></html>
            """);
        Files.writeString(site.resolve("ledger.html"), """
            <html><body><h1>Ledger</h1>
            <button id="add" onclick="document.getElementById('form').hidden=false">Add entry</button>
            <form id="form" hidden onsubmit="event.preventDefault();
                var li=document.createElement('li'); li.textContent=document.getElementById('payee').value;
                document.getElementById('rows').appendChild(li)">
              <input id="payee"><button id="save">Save</button></form>
            <ul id="rows"></ul></body></html>
            """);
        // Complete and working, and nothing in the application leads to it.
        Files.writeString(site.resolve("refunds.html"), """
            <html><body><h1>Refunds</h1><button id="refund">Refund</button></body></html>
            """);
        VerifySpec.PageCheckSpec reached = new VerifySpec.PageCheckSpec("/", true, List.of(), false,
            List.of(step("click", "text=Ledger"), step("click", "#add"),
                new VerifySpec.StepSpec(null, "#payee", "Hale", null, null, null),
                step("click", "#save"), step("expectVisible", "#rows >> text=Hale")));
        VerifySpec.PageCheckSpec orphan = new VerifySpec.PageCheckSpec("/", true, List.of(), false,
            List.of(step("click", "text=Refunds"), step("expectVisible", "#refund")));
        VerifySpec.BrowserSpec spec = new VerifySpec.BrowserSpec("static:site", null, 60,
            List.of(reached, orphan), 0);
        StringBuilder log = new StringBuilder();

        BrowserCheckResults results;
        try (BuildBoxes.Box box = BuildBoxes.of(manager()).open(tree, "Final integration", true)) {
            results = new BrowserVerifier(BlobSink.NONE).run(box.target(), spec, log);
        }

        assertThat(results.couldNotTry()).as(log.toString()).isFalse();
        assertThat(results.checks()).hasSize(2);
        PageCheck first = results.checks().get(0);
        assertThat(first.assertions()).as(log.toString())
            .extracting(AssertionResult::selector).contains("step 1: click text=Ledger",
                "step 5: expect visible #rows >> text=Hale");
        assertThat(first.assertions()).as(log.toString()).allMatch(AssertionResult::passed);
        PageCheck second = results.checks().get(1);
        assertThat(second.assertions()).as(log.toString())
            .filteredOn(a -> a.selector().startsWith("step"))
            .extracting(AssertionResult::passed, AssertionResult::message)
            .satisfiesExactly(
                failed -> {
                    assertThat(failed.toList().get(0)).isEqualTo(false);
                    assertThat((String) failed.toList().get(1)).contains("the browser was at /");
                },
                skipped -> assertThat(skipped.toList()).containsExactly(false, "not reached"));
    }

    private static VerifySpec.StepSpec step(String action, String selector) {
        return new VerifySpec.StepSpec("click".equals(action) ? selector : null, null, null, null,
            "expectVisible".equals(action) ? selector : null, null);
    }

    private static DockerSandboxManager manager() {
        return new DockerSandboxManager(
            System.getProperty("swarmcoder.sandbox.image", "swarmcoder-worker:latest"), 2, 4,
            System.getProperty("swarmcoder.sandbox.dockerHost"),
            Path.of(System.getProperty("user.home"), ".m2").toString());
    }
}
