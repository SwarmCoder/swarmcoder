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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.verify.JUnitXmlParser;
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hinge of the product's central claim — that a written requirement can be traced to the commit
 * that satisfied it — is the match between the test a criterion NAMES and the ids the test runner
 * EMITS. Until now that match had only ever been tried against ids we wrote ourselves, which proves
 * nothing: we wrote them to be matchable.
 *
 * <p>So this test uses output a real runner really produced. {@code real-junit/} holds surefire XML
 * from an actual {@code mvn test} over {@code real-junit/SpellingsTest.java.txt} (Maven 3.9.9,
 * surefire 3.2.5, JUnit 5.10.2), containing every awkward spelling on purpose: a passing test, a
 * failing one, one that ERRORS rather than fails, one whose {@code @DisplayName} differs from its
 * method name, a parameterised test with one passing and one failing invocation, a {@code @Nested}
 * class, and a {@code @Disabled} test that never ran. It goes through the real
 * {@link JUnitXmlParser} and the real {@link CriterionEvidence}; nothing here is hand-written except
 * the criteria, which is the half a person or a model really does write.
 *
 * <p><b>What a real runner actually emits</b>, established by that run rather than assumed:
 * <ul>
 *   <li>the id is {@code <classname>#<name>} — {@code swarm.accept.SpellingsTest#plainFails};</li>
 *   <li>a nested class is {@code Outer$Inner}, and its tests are reported under that name;</li>
 *   <li>a parameterised test reports once per invocation, as {@code parameterised(int)[2]};</li>
 *   <li>{@code @DisplayName} does NOT appear — surefire reports the method name;</li>
 *   <li>a {@code @Disabled} test is written out as {@code skipped}, with no failure;</li>
 *   <li>an ERROR is a separate element from a FAILURE, and both are things going wrong.</li>
 * </ul>
 */
class RealJUnitCriterionMatchingTest {

    /** The parsed real report, shared by every case below. */
    private static VerificationReport realReport() throws IOException {
        TestResults acceptance = JUnitXmlParser.parse(List.of(resource(
            "/real-junit/TEST-swarm.accept.SpellingsTest.xml")));
        VerificationReport report = new VerificationReport();
        report.setId(UUID.randomUUID());
        report.setParses(true);
        report.setCompiles(true);
        report.setAcceptance(acceptance);
        return report;
    }

    @Test
    void theRealRunnersOwnCountsAndIdsAreReadCorrectly() throws Exception {
        TestResults results = realReport().acceptance();

        // 9 test cases: 3 pass, 4 fail, 1 errors, 1 is skipped.
        assertThat(results.passed()).isEqualTo(3);
        assertThat(results.failed()).isEqualTo(4);
        assertThat(results.errored()).isEqualTo(1);
        assertThat(results.skipped()).isEqualTo(1);

        // The ids, exactly as the runner spelt them. These are the strings a criterion has to meet.
        assertThat(results.passedIds()).containsExactlyInAnyOrder(
            "swarm.accept.SpellingsTest#parameterised(int)[1]",
            "swarm.accept.SpellingsTest#plainPasses",
            "swarm.accept.SpellingsTest$Inner#nestedPasses");
        assertThat(results.skippedIds()).containsExactly(
            "swarm.accept.SpellingsTest#neverRuns");
        assertThat(results.failures()).extracting(f -> f.testId()).containsExactlyInAnyOrder(
            "swarm.accept.SpellingsTest#parameterised(int)[2]",
            "swarm.accept.SpellingsTest#plainErrors",
            "swarm.accept.SpellingsTest#plainFails",
            "swarm.accept.SpellingsTest#multipliesPositives",
            "swarm.accept.SpellingsTest$Inner#nestedFails");
        assertThat(results.idsTruncated()).isFalse();
    }

    /**
     * Every spelling a criterion is realistically written in, against those ids. The expected value
     * beside each is the answer this project wants; where the answer is a judgement rather than an
     * obvious right and wrong, it is called out in the comment.
     */
    @Test
    void everyAwkwardSpellingResolvesAgainstRealRunnerIds() throws Exception {
        VerificationReport report = realReport();
        Map<String, CriterionEvidence.Outcome> expected = new LinkedHashMap<>();

        // --- the ordinary shapes ----------------------------------------------------------------
        expected.put("SpellingsTest#plainPasses", CriterionEvidence.Outcome.PASSED);
        expected.put("SpellingsTest#plainFails", CriterionEvidence.Outcome.FAILED);
        expected.put("swarm.accept.SpellingsTest#plainFails", CriterionEvidence.Outcome.FAILED);
        expected.put("SpellingsTest::plainPasses", CriterionEvidence.Outcome.PASSED);
        expected.put("SpellingsTest#plainPasses()", CriterionEvidence.Outcome.PASSED);

        // A bare method name, with no class at all. Common from a model, and resolvable as long as
        // the name is unique in the suite.
        expected.put("plainPasses", CriterionEvidence.Outcome.PASSED);
        expected.put("plainFails", CriterionEvidence.Outcome.FAILED);

        // A class with no method names every test in it: one of them failed, so the criterion did.
        expected.put("swarm.accept.SpellingsTest", CriterionEvidence.Outcome.FAILED);
        // …and a source path is just another way of naming that class.
        expected.put("src/test/java/swarm/accept/SpellingsTest.java",
            CriterionEvidence.Outcome.FAILED);

        // --- an ERROR is a failure, not an absence ----------------------------------------------
        // The test threw rather than asserting. The runner files it under <error>, not <failure>;
        // the criterion must still come out FAILED, because the thing it checks did not work.
        expected.put("SpellingsTest#plainErrors", CriterionEvidence.Outcome.FAILED);

        // --- nested classes ----------------------------------------------------------------------
        expected.put("SpellingsTest$Inner#nestedPasses", CriterionEvidence.Outcome.PASSED);
        expected.put("SpellingsTest$Inner#nestedFails", CriterionEvidence.Outcome.FAILED);
        // The dot spelling of a nested class, which is what a person writes.
        expected.put("SpellingsTest.Inner#nestedPasses", CriterionEvidence.Outcome.PASSED);
        // Naming only the outer class while meaning a test inside the nested one. Accepted: the
        // enclosing class is a legitimate name for the tests it encloses.
        expected.put("SpellingsTest#nestedFails", CriterionEvidence.Outcome.FAILED);
        expected.put("Inner#nestedPasses", CriterionEvidence.Outcome.PASSED);

        // --- parameterised ------------------------------------------------------------------------
        // Reported once per invocation as parameterised(int)[1] and [2]. A criterion names the
        // method, not an invocation, and one invocation failing fails the criterion — a criterion
        // that holds for some inputs and not others is not satisfied.
        expected.put("SpellingsTest#parameterised", CriterionEvidence.Outcome.FAILED);

        // --- the display name --------------------------------------------------------------------
        // The method is multipliesPositives; its @DisplayName is "multiplies two positive numbers".
        // Surefire reports the METHOD name, so naming the method works…
        expected.put("SpellingsTest#multipliesPositives", CriterionEvidence.Outcome.FAILED);
        // …and naming the display name matches nothing, so it is UNKNOWN. This is the honest
        // answer and the safe one: the criterion is not evaluated rather than silently passed.
        expected.put("SpellingsTest#multiplies two positive numbers",
            CriterionEvidence.Outcome.UNKNOWN);
        expected.put("multiplies two positive numbers", CriterionEvidence.Outcome.UNKNOWN);

        // --- the two that must never be PASSED ----------------------------------------------------
        // A @Disabled test. It did not run, so it proves nothing — and disabling a test is the
        // cheapest way there is to turn a red criterion green.
        expected.put("SpellingsTest#neverRuns", CriterionEvidence.Outcome.UNKNOWN);
        // A test that was never written at all, while other acceptance tests ran green. This is the
        // failure that would certify unbuilt work as delivered.
        expected.put("SpellingsTest#doesNotExist", CriterionEvidence.Outcome.UNKNOWN);
        expected.put("swarm.accept.NoSuchTest#anything", CriterionEvidence.Outcome.UNKNOWN);
        expected.put("NoSuchTest", CriterionEvidence.Outcome.UNKNOWN);

        // --- the substring trap --------------------------------------------------------------------
        // "plain" is a prefix of plainPasses, plainFails and plainErrors. Matching on substrings
        // would resolve it — to whichever happened to come first — and a criterion answered by an
        // arbitrary neighbouring test is worse than one that is not answered at all.
        expected.put("SpellingsTest#plain", CriterionEvidence.Outcome.UNKNOWN);
        expected.put("Spellings#plainPasses", CriterionEvidence.Outcome.UNKNOWN);

        List<String> wrong = new ArrayList<>();
        expected.forEach((ref, want) -> {
            CriterionEvidence.Evidence got = CriterionEvidence.evidenceFor(ref, report);
            if (got.outcome() != want) {
                wrong.add(ref + ": expected " + want + " but got " + got.outcome()
                    + " (" + got.reason() + ")");
            }
        });
        assertThat(wrong).as("test-reference spellings that resolved wrongly").isEmpty();
    }

    /**
     * The single most dangerous case, asserted on its own so it can never be lost in a table: a
     * criterion whose test did not run must come out UNKNOWN, and the story must therefore NOT be
     * deliverable — even though the acceptance suite ran and everything that did run was green.
     */
    @Test
    void aCriterionWhoseTestNeverRanCannotBeDeliveredEvenWhenTheSuiteIsGreen() {
        // A green suite: three real ids, all passing, none of them the criterion's test.
        TestResults acceptance = new TestResults(3, 0, 0, 0, List.of(),
            List.of("swarm.accept.SpellingsTest#plainPasses",
                "swarm.accept.SpellingsTest#parameterised(int)[1]",
                "swarm.accept.SpellingsTest$Inner#nestedPasses"),
            List.of(), false);
        VerificationReport green = new VerificationReport();
        green.setId(UUID.randomUUID());
        green.setParses(true);
        green.setCompiles(true);
        green.setAcceptance(acceptance);

        StoryScope scope = scopeWithCriterion("swarm.accept.CheckoutTest#guestPays");

        assertThat(CriterionEvidence.outcomes(scope, green).values())
            .containsExactly(CriterionEvidence.Outcome.UNKNOWN);
        assertThat(CriterionEvidence.allDelivered(scope, green))
            .as("a story whose criterion's test never ran must not be deliverable")
            .isFalse();
        assertThat(CriterionEvidence.explain(scope, green).get(0))
            .contains("UNKNOWN")
            .contains("no test matching");

        // The same scope against a run where the test DID run and passed: now it delivers.
        acceptance.setPassedIds(List.of("swarm.accept.CheckoutTest#guestPays"));
        acceptance.setPassed(1);
        assertThat(CriterionEvidence.allDelivered(scope, green)).isTrue();
    }

    /**
     * A report from before test ids were recorded cannot say whether a given test ran, so it does
     * not get to say that it passed. Fails closed: old stored reports read as UNKNOWN rather than
     * silently certifying whatever they are asked about.
     */
    @Test
    void aReportWithNoRecordedIdsIsUnknownRatherThanPassed() {
        TestResults legacy = new TestResults(5, 0, 0, 0, List.of());
        VerificationReport report = new VerificationReport();
        report.setId(UUID.randomUUID());
        report.setParses(true);
        report.setCompiles(true);
        report.setAcceptance(legacy);

        CriterionEvidence.Evidence evidence =
            CriterionEvidence.evidenceFor("CheckoutTest#guestPays", report);
        assertThat(evidence.outcome()).isEqualTo(CriterionEvidence.Outcome.UNKNOWN);
        assertThat(evidence.reason()).contains("records no test ids");
    }

    /**
     * Guards the committed report against drift in the toolchain. A surefire or JUnit upgrade
     * that changes how ids are spelt — phrased method names, a different parameterised suffix
     * — breaks the matcher silently, and this is the only thing that would notice.
     *
     * <p>It shells out to a real Maven build, so it runs wherever Maven is on the PATH, which
     * in this repository is everywhere: the whole build is Maven. It used to need
     * {@code SWARMCODER_REAL_JUNIT=true} and was therefore never run, which left the guard
     * against toolchain drift itself undefended. Measured cost of the real build it starts:
     * about four seconds.
     */
    @Test
    @RunsWhen(Need.MAVEN)
    void aRealMavenRunTodayStillEmitsTheIdsThisTestIsBuiltOn() throws Exception {
        Path work = Files.createTempDirectory("swarmcoder-real-junit");
        try {
            Path tests = work.resolve("src/test/java/swarm/accept");
            Files.createDirectories(tests);
            String source = resource("/real-junit/SpellingsTest.java.txt");
            // Drop the leading comment block; it explains the file and is not Java.
            source = source.substring(source.indexOf("package swarm.accept;"));
            Files.writeString(tests.resolve("SpellingsTest.java"), source);
            Files.writeString(work.resolve("pom.xml"), PROBE_POM);

            run(work, "mvn -o -q -B test");

            Path reports = work.resolve("target/surefire-reports");
            List<String> xml = new ArrayList<>();
            try (var stream = Files.list(reports)) {
                for (Path p : stream.toList()) {
                    if (p.getFileName().toString().endsWith(".xml")) {
                        xml.add(Files.readString(p));
                    }
                }
            }
            TestResults fresh = JUnitXmlParser.parse(xml);
            TestResults committed = JUnitXmlParser.parse(List.of(
                resource("/real-junit/TEST-swarm.accept.SpellingsTest.xml")));

            assertThat(fresh.allReportedIds())
                .as("the toolchain now emits different test ids than the committed report — "
                    + "regenerate real-junit/TEST-swarm.accept.SpellingsTest.xml and re-check "
                    + "every expectation in this class")
                .containsExactlyInAnyOrderElementsOf(committed.allReportedIds());
        } finally {
            deleteRecursively(work);
        }
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static final String PROBE_POM = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId><artifactId>probe</artifactId><version>1.0</version>
          <properties><maven.compiler.release>21</maven.compiler.release>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
          <dependencies><dependency><groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId><version>5.10.2</version>
            <scope>test</scope></dependency></dependencies>
          <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version>
            <configuration><testFailureIgnore>true</testFailureIgnore></configuration>
          </plugin></plugins></build>
        </project>
        """;

    private static String resource(String path) throws IOException {
        try (InputStream in = RealJUnitCriterionMatchingTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** One criterion, ACCEPTED, on an ACTIVE requirement, sliced by a story — the ordinary case. */
    private static StoryScope scopeWithCriterion(String testRef) {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R1", "Checkout",
            "A guest can pay", Priority.HIGH, RequirementStatus.ACTIVE, null);
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "a guest completes a purchase", testRef);
        criterion.setStatus(CriterionStatus.ACCEPTED);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "BRD",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now());
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Guest can pay", null, StoryState.RUNNING, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        return StoryScope.resolve(brd, story);
    }

    private static void run(Path dir, String command) throws Exception {
        List<String> argv = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", command) : List.of("sh", "-c", command);
        Process process = new ProcessBuilder(argv).directory(dir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // Test failures are expected — the probe class fails on purpose — so only a missing
        // toolchain is fatal here.
        if (!Files.isDirectory(dir.resolve("target/surefire-reports"))) {
            throw new IllegalStateException(command + " produced no surefire reports:\n" + out);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A temp directory that will not delete is not a test failure.
                }
            });
        }
    }
}
