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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A check pointing at a test nobody wrote is caught at TEST_AUTHORING, before a worker starts —
 * and is reported rather than repaired.
 *
 * <p>The pair that matters: a check whose named test really is in the authored source passes, and
 * one whose named test is not parks the run with a brief that names both sides. The rest of these
 * cases are the ways the audit must NOT accuse: it may only conclude from source it could actually
 * read.
 */
class AuthoredTestAuditTest {

    @TempDir
    Path repo;

    private static final String SOURCE = """
        package swarm.accept;

        import org.junit.jupiter.api.Test;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        class MultiplyAcceptTest {

            @Test
            void multipliesTwoPositiveNumbers() {
                assertEquals(12, new Calculator().multiply(3, 4));
            }

            @Test
            void multiplyingByZeroGivesZero() {
                assertEquals(0, new Calculator().multiply(7, 0));
            }
        }
        """;

    @Test
    void aCheckWhoseTestWasActuallyWrittenIsReportedAsProved() throws Exception {
        write("src/test/java/swarm/accept/MultiplyAcceptTest.java", SOURCE);

        AuthoredTestAudit.Result result = AuthoredTestAudit.audit(
            List.of(criterion("multiplies", "swarm.accept.MultiplyAcceptTest#multipliesTwoPositiveNumbers")),
            List.of("R1:C1"), repo, authored());

        assertThat(result.ok()).isTrue();
        assertThat(result.mapping()).containsExactly(
            "R1:C1 is proved by swarm.accept.MultiplyAcceptTest#multipliesTwoPositiveNumbers");
    }

    @Test
    void aCheckNamingATestNobodyWroteParksTheRunAndNamesBothSides() throws Exception {
        write("src/test/java/swarm/accept/MultiplyAcceptTest.java", SOURCE);

        AuthoredTestAudit.Result result = AuthoredTestAudit.audit(
            List.of(criterion("multiplying by zero returns zero",
                "swarm.accept.MultiplyAcceptTest#zeroGivesZero")),
            List.of("R1:C2"), repo, authored());

        assertThat(result.ok()).isFalse();
        assertThat(result.findings()).hasSize(1);
        String brief = AuthoredTestAudit.brief("Add multiply", result);
        assertThat(brief)
            .as("what was promised").contains("swarm.accept.MultiplyAcceptTest#zeroGivesZero")
            .as("what was actually written")
            .contains("swarm.accept.MultiplyAcceptTest#multiplyingByZeroGivesZero")
            .as("and the reason it is not quietly fixed")
            .contains("agree with the code by definition");
    }

    @Test
    void theReferenceIsNeverWrittenBackToTheCriterion() throws Exception {
        write("src/test/java/swarm/accept/MultiplyAcceptTest.java", SOURCE);
        AcceptanceCriterion criterion =
            criterion("zero", "swarm.accept.MultiplyAcceptTest#zeroGivesZero");

        AuthoredTestAudit.audit(List.of(criterion), List.of("R1:C2"), repo, authored());

        // Adopting whatever the author produced would make the requirement point at a test chosen
        // by the thing being checked, and the disagreement would vanish with the error.
        assertThat(criterion.testClassOrFile())
            .isEqualTo("swarm.accept.MultiplyAcceptTest#zeroGivesZero");
    }

    @Test
    void aCheckNamingNoTestAtAllIsAlsoAFinding() throws Exception {
        write("src/test/java/swarm/accept/MultiplyAcceptTest.java", SOURCE);

        AuthoredTestAudit.Result result = AuthoredTestAudit.audit(
            List.of(criterion("something", null)), List.of("R1:C3"), repo, authored());

        assertThat(result.ok()).isFalse();
        assertThat(result.findings().get(0).problem()).contains("names no test at all");
    }

    @Test
    void anUnreadableAuthoredFileProducesANoteAndNoAccusation() {
        // Nothing was written to disk, so the audit can read nothing. "We could not tell" must
        // never become "this is wrong" — the same rule the evidence layer keeps in the other
        // direction, and the reason a parking decision is only ever taken on what was read.
        AuthoredTestAudit.Result result = AuthoredTestAudit.audit(
            List.of(criterion("multiplies", "swarm.accept.MultiplyAcceptTest#anything")),
            List.of("R1:C1"), repo, authored());

        assertThat(result.ok()).isTrue();
        assertThat(result.notes()).anyMatch(n -> n.contains("no test ids could be read"));
    }

    @Test
    void theAuthorsOwnClaimIsReportedAndCheckedButNeverBelieved() throws Exception {
        write("src/test/java/swarm/accept/MultiplyAcceptTest.java", SOURCE);

        AuthoredTestAudit.Result result = AuthoredTestAudit.audit(
            List.of(criterion("multiplies", "swarm.accept.MultiplyAcceptTest#multipliesTwoPositiveNumbers")),
            List.of("R1:C1"), repo,
            TestAuthorClient.Authored.of(
                List.of("src/test/java/swarm/accept/MultiplyAcceptTest.java"),
                List.of(new TestAuthorClient.Claim("multiplies",
                    "swarm.accept.MultiplyAcceptTest#somethingItNeverWrote"))));

        assertThat(result.ok()).as("a false claim is a note, not a park — the files decide").isTrue();
        assertThat(result.notes())
            .anyMatch(n -> n.contains("no such test is in the files it wrote"));
    }

    @Test
    void testIdsAreReadOutOfNestedClassesToo() {
        List<String> ids = AuthoredTestAudit.idsIn("""
            package swarm.accept;
            class OuterTest {
                @org.junit.jupiter.api.Nested
                class Inner {
                    @org.junit.jupiter.api.Test
                    void nestedWorks() {}
                }
            }
            """);

        assertThat(ids).contains("swarm.accept.Inner#nestedWorks", "swarm.accept.OuterTest#nestedWorks");
    }

    /**
     * What the run graph shows for the task is built from the audit's own facts, so the badge and
     * the log cannot disagree.
     *
     * <p>Two tests in the file, one check naming one of them: the record says two tests were
     * written, that the named one proves R1:C1 in the requirement's own words, and that the other
     * proves nothing anybody asked for - and the "is proved by" line the audit logged names the
     * same test the record does.
     */
    @Test
    void theRecordForTheRunGraphSaysWhatTheAuditConcluded() throws Exception {
        write("src/test/java/swarm/accept/MultiplyAcceptTest.java", SOURCE);
        List<AcceptanceCriterion> criteria = List.of(criterion(
            "multiplying two positive numbers returns their product",
            "swarm.accept.MultiplyAcceptTest#multipliesTwoPositiveNumbers"));
        java.time.Instant started = java.time.Instant.now().minusSeconds(40);

        AuthoredTestAudit.Result result = AuthoredTestAudit.audit(
            criteria, List.of("R1:C1"), repo, authored());
        com.swarmcoder.domain.AuthoredTests record = AuthoredTestAudit.written(
            criteria, List.of("R1:C1"), result, authored(), started, java.time.Instant.now());

        assertThat(record.written()).isTrue();
        assertThat(record.inProgress()).isFalse();
        assertThat(record.startedAt()).as("when the author was handed the task survives")
            .isEqualTo(started);
        assertThat(record.checksOffered()).isEqualTo(1);
        assertThat(record.files())
            .containsExactly("src/test/java/swarm/accept/MultiplyAcceptTest.java");
        assertThat(record.tests()).as("one entry per test METHOD, in file order").hasSize(2);
        assertThat(record.tests().get(0).testRef())
            .isEqualTo("swarm.accept.MultiplyAcceptTest#multipliesTwoPositiveNumbers");
        assertThat(record.tests().get(0).provesRef()).isEqualTo("R1:C1");
        assertThat(record.tests().get(0).provesText())
            .isEqualTo("multiplying two positive numbers returns their product");
        assertThat(record.tests().get(1).testRef())
            .isEqualTo("swarm.accept.MultiplyAcceptTest#multiplyingByZeroGivesZero");
        assertThat(record.tests().get(1).provesACheck())
            .as("written, counted, and honest that nothing asked for it").isFalse();
        assertThat(record.checksProved()).isEqualTo(1);
        assertThat(record.problems()).isEmpty();
        assertThat(result.mapping())
            .as("and the line the run log carries names the same test the record does")
            .containsExactly("R1:C1 is proved by " + record.tests().get(0).testRef());
    }

    /** A check that names a test nobody wrote is on the record too, as the problem it is. */
    @Test
    void aMismatchIsOnTheRecordAsAProblem() throws Exception {
        write("src/test/java/swarm/accept/MultiplyAcceptTest.java", SOURCE);
        List<AcceptanceCriterion> criteria = List.of(
            criterion("zero", "swarm.accept.MultiplyAcceptTest#zeroGivesZero"));

        AuthoredTestAudit.Result result = AuthoredTestAudit.audit(
            criteria, List.of("R1:C2"), repo, authored());
        com.swarmcoder.domain.AuthoredTests record = AuthoredTestAudit.written(
            criteria, List.of("R1:C2"), result, authored(), null, java.time.Instant.now());

        assertThat(record.tests()).hasSize(2);
        assertThat(record.checksProved()).isZero();
        assertThat(record.problems()).hasSize(1);
        assertThat(record.problems().get(0))
            .contains("R1:C2")
            .contains("swarm.accept.MultiplyAcceptTest#zeroGivesZero")
            .doesNotContain("\n");
    }

    /** Nested classes: the audit decides generously, the record lists each method once. */
    @Test
    void theRecordListsEachTestMethodOnce() {
        String source = """
            package swarm.accept;
            class OuterTest {
                @org.junit.jupiter.api.Nested
                class Inner {
                    @org.junit.jupiter.api.Test
                    void nestedWorks() {}
                }
            }
            """;

        assertThat(AuthoredTestAudit.idsIn(source))
            .containsExactly("swarm.accept.Inner#nestedWorks", "swarm.accept.OuterTest#nestedWorks");
        assertThat(AuthoredTestAudit.declaredIn(source))
            .containsExactly("swarm.accept.Inner#nestedWorks");
    }

    private static AcceptanceCriterion criterion(String text, String testRef) {
        return new AcceptanceCriterion(UUID.randomUUID(), text, testRef);
    }

    private static TestAuthorClient.Authored authored() {
        return TestAuthorClient.Authored.of(
            List.of("src/test/java/swarm/accept/MultiplyAcceptTest.java"), List.of());
    }

    private void write(String relative, String content) throws Exception {
        Path target = repo.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
