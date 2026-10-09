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

import com.swarmcoder.sandbox.DockerSandboxManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * By hand, no model: does a project's contract start its application in the browser image, and
 * does a journey run there (DEVELOPER_CORRECTIONS section 64, asked after live run 89)?
 *
 * <p>Off unless the three properties are given. It builds the tree with the contract's
 * {@code compile} and {@code existing} commands in a container that has a browser, starts the
 * application with the contract's {@code browser.serve} line and makes every
 * {@code *.journey.yaml} of the journeys folder - the same {@link JourneyRunner} the red check and
 * final integration use. The tree is written to (build output): give it a scratch copy.
 *
 * <pre>
 * mvn -o -pl sc-verify test -Dtest=AJourneyIsMadeOnATreeByHandTest \
 *   -Dswarmcoder.journeyByHand.tree=&lt;scratch copy&gt; \
 *   -Dswarmcoder.journeyByHand.contract=&lt;verify.yaml&gt; \
 *   -Dswarmcoder.journeyByHand.journeys=&lt;folder of *.journey.yaml&gt;
 * </pre>
 */
@EnabledIfSystemProperty(named = "swarmcoder.journeyByHand.tree", matches = ".+")
class AJourneyIsMadeOnATreeByHandTest {

    @Test
    void theContractStartsTheApplicationAndEveryJourneyPasses() throws Exception {
        Path tree = Path.of(System.getProperty("swarmcoder.journeyByHand.tree")).toAbsolutePath();
        VerifySpec spec = VerifySpecLoader.parse(Files.readString(
            Path.of(System.getProperty("swarmcoder.journeyByHand.contract"))));
        List<JourneyFile.Journey> journeys = new ArrayList<>();
        try (Stream<Path> files = Files.list(
                Path.of(System.getProperty("swarmcoder.journeyByHand.journeys")))) {
            for (Path file : files.sorted().toList()) {
                if (JourneyFile.isJourney(file.toString())) {
                    JourneyFile.Read read = JourneyFile.read(file.getFileName().toString(),
                        Files.readString(file));
                    assertThat(read.problems()).as(file.toString()).isEmpty();
                    journeys.add(read.journey());
                }
            }
        }
        assertThat(journeys).as("journeys to make").isNotEmpty();
        assertThat(JourneyFile.canRun(spec)).as("the contract has browser.serve").isTrue();

        DockerSandboxManager manager = new DockerSandboxManager("swarmcoder-worker:latest", 2, 4,
            null, Path.of(System.getProperty("user.home"), ".m2").toString());
        BuildBoxes boxes = BuildBoxes.of(manager);
        StringBuilder log = new StringBuilder();
        try {
            ExecTarget target = boxes.use(tree, "A journey made by hand", true);
            List<String> build = new ArrayList<>();
            build.addAll(spec.compile() == null ? List.of() : spec.compile());
            build.addAll(spec.existing() == null ? List.of() : spec.existing());
            for (String command : build) {
                long began = System.currentTimeMillis();
                ExecResult built = target.exec(command, spec.effectiveTimeoutSeconds());
                String said = built.output() == null ? "" : built.output().strip();
                System.out.println("[BY HAND] $ " + command + " -> exit " + built.exitCode()
                    + " in " + (System.currentTimeMillis() - began) / 1000 + " s");
                assertThat(built.succeeded()).as("build command `" + command + "`:\n"
                    + (said.length() <= 3000 ? said : said.substring(said.length() - 3000)))
                    .isTrue();
            }
            long began = System.currentTimeMillis();
            JourneyRunner.Outcome outcome =
                JourneyRunner.run(boxes.cleanStarts(tree, "A journey made by hand"), spec,
                    journeys, BlobSink.NONE, log);
            System.out.println("[BY HAND] journeys made in "
                + (System.currentTimeMillis() - began) / 1000 + " s: couldNotRun="
                + outcome.couldNotRun() + " didNotStart=" + outcome.didNotStart());
            for (JourneyFile.Result result : outcome.results()) {
                System.out.println("[BY HAND] " + (result.passed() ? "PASSED " : "FAILED ")
                    + result.journey().path() + (result.passed() ? "" : " - " + result.failure()));
            }
            System.out.println("[BY HAND] browser log:\n" + log);
            assertThat(outcome.couldNotRun()).as("could not run").isNull();
            assertThat(outcome.didNotStart()).as("the application did not start").isNull();
            assertThat(outcome.failed()).as("failed journeys").isEmpty();
        } finally {
            boxes.release(tree);
        }
    }
}
