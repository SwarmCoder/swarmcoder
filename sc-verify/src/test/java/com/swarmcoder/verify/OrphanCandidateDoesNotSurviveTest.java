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

import com.swarmcoder.domain.BuildReachabilityStatus;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole pipeline against the trap: a verification contract whose compile command genuinely
 * passes on a candidate none of it compiled.
 *
 * <p>This is the shape of run {@code 9bd237ff-9bc4-4da7-970e-c51a46d5c05e} exactly — a green
 * compile, a green acceptance stage, a green existing-test stage, and six files nothing built. The
 * old pipeline reported success. The stage under test is the only thing between that report and a
 * winner.
 */
class OrphanCandidateDoesNotSurviveTest {

    private static final String PASSING_XML = """
        <testsuite name="s" tests="1" failures="0" errors="0" skipped="0">
          <testcase classname="A" name="t1"/>
        </testsuite>
        """;

    @TempDir
    Path repo;

    private static VerifySpec mavenSpec() {
        return new VerifySpec("maven",
            List.of("mvn -o -q -B compile test-compile"),
            List.of("mvn -o -q -B test -Dtest=swarm/accept/**"),
            List.of("mvn -o -q -B test"),
            null, null, null, 60, null);
    }

    @Test
    void aGreenPipelineDoesNotSaveACandidateTheBuildNeverCompiled() throws IOException {
        aggregatorOverThreeModules();
        FakeExecTarget target = greenMavenRun();

        VerificationReport report = new CommandPipelineVerifier().verify(
            target, null, mavenSpec(), List.of(),
            Set.of("src/main/java/com/zeroz4j/bookstore/domain/Book.java",
                "src/main/webapp/bookstore.css"));

        // Everything the old pipeline looked at is green.
        assertThat(report.compiles()).isTrue();
        // And the candidate is dead anyway.
        assertThat(report.buildReachability().status()).isEqualTo(BuildReachabilityStatus.ORPHANED);
        assertThat(Verdicts.survived(report)).isFalse();
        assertThat(Verdicts.assess(report, List.of()).reason())
            .contains("src/main/java/com/zeroz4j/bookstore/domain/Book.java")
            .contains("bookshelf-demo-shared/src/main/java");

        // Nothing downstream was bought: a test suite run against code that is not in the build
        // proves nothing about it.
        assertThat(target.executedCommands).containsExactly("mvn -o -q -B compile test-compile");
        assertThat(report.logTail()).contains("[reachability] FAILED");
    }

    @Test
    void theSameCandidateInsideAModuleSurvives() throws IOException {
        aggregatorOverThreeModules();
        FakeExecTarget target = greenMavenRun();

        VerificationReport report = new CommandPipelineVerifier().verify(
            target, null, mavenSpec(), List.of(),
            Set.of("bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/model/Book.java",
                "bookshelf-demo-client/src/main/resources/bookstore.css"));

        assertThat(report.buildReachability().status()).isEqualTo(BuildReachabilityStatus.REACHABLE);
        assertThat(Verdicts.survived(report)).isTrue();
        assertThat(target.executedCommands).hasSize(3);
    }

    @Test
    void aCallerThatDoesNotKnowWhatChangedChangesNothing() throws IOException {
        aggregatorOverThreeModules();
        FakeExecTarget target = greenMavenRun();

        VerificationReport report = new CommandPipelineVerifier()
            .verify(target, null, mavenSpec());     // the no-changed-files overload

        assertThat(report.buildReachability().status())
            .isEqualTo(BuildReachabilityStatus.UNDETERMINED);
        assertThat(Verdicts.survived(report)).isTrue();
    }

    /** A green run of every stage the contract declares. */
    private FakeExecTarget greenMavenRun() {
        return new FakeExecTarget()
            .localRoot(repo)
            .script("mvn -o -q -B compile test-compile", 0)
            .scriptProducing("mvn -o -q -B test -Dtest=swarm/accept/**", 0,
                "target/surefire-reports/acc.xml", PASSING_XML)
            .scriptProducing("mvn -o -q -B test", 0, "target/surefire-reports/reg.xml", PASSING_XML)
            .file("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.swarmcoder.demo</groupId>
                  <artifactId>bookshelf-demo</artifactId>
                  <version>1.0.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>bookshelf-demo-shared</module>
                    <module>bookshelf-demo-client</module>
                    <module>bookshelf-demo-server</module>
                  </modules>
                </project>
                """)
            .file("bookshelf-demo-shared/pom.xml", modulePom("bookshelf-demo-shared"))
            .file("bookshelf-demo-client/pom.xml", modulePom("bookshelf-demo-client"))
            .file("bookshelf-demo-server/pom.xml", modulePom("bookshelf-demo-server"));
    }

    private static String modulePom(String artifactId) {
        return """
            <project><modelVersion>4.0.0</modelVersion>
              <artifactId>%s</artifactId>
            </project>
            """.formatted(artifactId);
    }

    /** The temp dir exists only so the fake target can report a local root. */
    private void aggregatorOverThreeModules() throws IOException {
        Files.createDirectories(repo);
    }
}
