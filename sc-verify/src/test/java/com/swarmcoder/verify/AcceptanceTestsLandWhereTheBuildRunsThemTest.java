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

import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defect: acceptance tests written where nothing compiles them.
 *
 * <p>Run {@code e01d1378} on 2026-08-31 wrote three acceptance tests into
 * {@code src/test/java/swarm/accept} at the root of {@code dev/bookshelf-demo}, whose root
 * {@code pom.xml} is a {@code <packaging>pom</packaging>} aggregator with no sources of its own.
 * The test author's one attempt to write into a real module was REJECTED by the path policy for
 * leaving the protected directory — it was right and the directory was wrong. The acceptance stage
 * then ran, exited 0, executed zero tests, and the red-check parked the run.
 *
 * <p>The last test here is the one that matters: it does not check that a file is present, it
 * checks that Maven RAN it. The failure mode being fixed is a command that exits 0 having run
 * nothing, so exit status proves nothing at all — only a test count does.
 */
class AcceptanceTestsLandWhereTheBuildRunsThemTest {

    @TempDir
    Path root;

    // --- where they land ------------------------------------------------------------------------

    @Test
    void aSingleModuleProjectIsExactlyWhatItAlwaysWas() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>a</artifactId></project>");

        AcceptanceTestLocation.Location location = AcceptanceTestLocation.resolve(root, "maven");

        assertThat(location.module()).isEmpty();
        assertThat(location.protectedDir()).isEqualTo("src/test/java/swarm");
        assertThat(location.writeDir()).isEqualTo("src/test/java/swarm/accept");
        assertThat(location.seesEveryModule()).isTrue();
        assertThat(ToolchainDetector.detect(root).acceptanceTestDir())
            .isEqualTo("src/test/java/swarm/accept");
    }

    @Test
    void theModuleThatCanSeeEveryOtherModuleHostsThem() throws IOException {
        bookshelfShapedReactor();

        AcceptanceTestLocation.Location location = AcceptanceTestLocation.resolve(root, "maven");

        // server depends on shared AND client; client depends on shared; shared on neither. Only
        // server's test classpath can name a type from every module, which is what a story-level
        // test spanning storage and UI needs.
        assertThat(location.module()).isEqualTo("server");
        assertThat(location.protectedDir()).isEqualTo("server/src/test/java/swarm");
        assertThat(location.writeDir()).isEqualTo("server/src/test/java/swarm/accept");
        assertThat(location.seesEveryModule()).isTrue();
        assertThat(ToolchainDetector.detect(root).acceptanceTestDir())
            .isEqualTo("server/src/test/java/swarm/accept");
    }

    @Test
    void aTransitiveDependencyCountsJustAsMuchAsADirectOne() throws IOException {
        aggregator("a", "b", "c");
        module("a", "<project><artifactId>a</artifactId></project>");
        module("b", dependingOn("b", "a"));
        module("c", dependingOn("c", "b"));   // c -> b -> a, so c sees both

        assertThat(AcceptanceTestLocation.resolve(root, "maven").module()).isEqualTo("c");
    }

    @Test
    void aFlatReactorNobodyDependsOnStillGetsARealModuleAndAWarning() throws IOException {
        aggregator("one", "two");
        module("one", "<project><artifactId>one</artifactId></project>");
        module("two", "<project><artifactId>two</artifactId></project>");

        AcceptanceTestLocation.Location location = AcceptanceTestLocation.resolve(root, "maven");

        // Never the repository root: it compiles nothing, which is the whole defect. The first
        // module the build compiles is a place a test can run; the root is not.
        assertThat(location.module()).isEqualTo("one");
        assertThat(location.seesEveryModule()).isFalse();
        assertThat(location.note())
            .contains("none of them depends on another")
            .contains("cannot be tested from anywhere in this repository");
        assertThat(ToolchainDetector.detect(root).warnings())
            .anySatisfy(w -> assertThat(w).contains("none of them depends on another"));
    }

    @Test
    void aGradleMultiProjectBuildIsReadTheSameWay() throws IOException {
        Files.writeString(root.resolve("settings.gradle"), """
            include 'shared'
            include 'app'
            """);
        Files.createDirectories(root.resolve("shared"));
        Files.writeString(root.resolve("shared/build.gradle"), "dependencies { }\n");
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/build.gradle"),
            "dependencies { implementation project(':shared') }\n");

        assertThat(AcceptanceTestLocation.resolve(root, "gradle").writeDir())
            .isEqualTo("app/src/test/java/swarm/accept");
    }

    @Test
    void aLayoutThatCannotBeReadFallsBackToTheConvention() {
        // No build file at all: nothing to read, so the answer is what it has always been rather
        // than a guess at a module that may not exist.
        AcceptanceTestLocation.Location location = AcceptanceTestLocation.resolve(root, "node");

        assertThat(location.writeDir()).isEqualTo("src/test/java/swarm/accept");
        assertThat(location.module()).isEmpty();
    }

    @Test
    void aParentPomThatMerelyPinsASiblingVersionIsNotADependency() throws IOException {
        // dependencyManagement names every module; counting it would make the aggregator's
        // opinion look like a real classpath edge and could pick the wrong host.
        Files.writeString(root.resolve("pom.xml"), """
            <project><artifactId>root</artifactId><packaging>pom</packaging>
              <modules><module>a</module><module>b</module></modules>
              <dependencyManagement><dependencies>
                <dependency><artifactId>a</artifactId></dependency>
                <dependency><artifactId>b</artifactId></dependency>
              </dependencies></dependencyManagement>
            </project>""");
        module("a", "<project><artifactId>a</artifactId></project>");
        module("b", dependingOn("b", "a"));

        assertThat(AcceptanceTestLocation.resolve(root, "maven").module()).isEqualTo("b");
    }

    // --- and are actually executed there --------------------------------------------------------

    /**
     * The proof. A real Maven reactor, the contract the detector proposes for it, an acceptance
     * test written at the directory it names — and the number of tests Maven reports having RUN.
     *
     * <p>The same run also shows the defect: the same test at the old hardcoded root location is
     * executed zero times, by the same command, with the build exiting 0 throughout.
     */
    @Test
    @RunsWhen(Need.MAVEN)
    void aTestWrittenAtTheResolvedDirectoryIsActuallyRunAndOneAtTheRootIsNot() throws IOException {
        bookshelfShapedReactor();
        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);
        VerifySpec spec = offline(VerifySpecLoader.parse(ToolchainDetector.render(detection)));

        // The old, hardcoded location: a directory this build compiles nothing from.
        writeAcceptanceTest(root.resolve("src/test/java/swarm/accept"), "OrphanTest");

        VerificationReport before = new CommandPipelineVerifier()
            .verify(new LocalProcessExecTarget(root), null, spec);
        assertThat(before.compiles()).as("compile stage; log:\n" + before.logTail()).isTrue();
        assertThat(before.acceptance().passed() + before.acceptance().failed()
                + before.acceptance().errored())
            .as("a test at the repository root of an aggregator build is run by nothing; the "
                + "stage exits 0 and proves precisely nothing. Log:\n" + before.logTail())
            .isZero();

        // The resolved location, from the same detection the contract came from.
        writeAcceptanceTest(root.resolve(detection.acceptanceTestDir()), "RealAcceptTest");

        VerificationReport after = new CommandPipelineVerifier()
            .verify(new LocalProcessExecTarget(root), null, spec);

        assertThat(after.acceptance().passed())
            .as("Maven must report having RUN the test, not merely exit 0. Log:\n" + after.logTail())
            .isEqualTo(1);
    }

    // --- fixtures --------------------------------------------------------------------------------

    /** The shape of {@code dev/bookshelf-demo}: an aggregator over shared, client and server. */
    private void bookshelfShapedReactor() throws IOException {
        aggregator("shared", "client", "server");
        module("shared", """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent><groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version></parent>
              <artifactId>shared</artifactId>
            </project>""");
        module("client", dependingOn("client", "shared"));
        module("server", dependingOn("server", "shared", "client"));
        for (String module : List.of("shared", "client", "server")) {
            Files.createDirectories(root.resolve(module + "/src/main/java/demo"));
            Files.writeString(root.resolve(module + "/src/main/java/demo/" + module + "Marker.java"),
                "package demo; public class " + module + "Marker {}\n");
        }
    }

    private void aggregator(String... modules) throws IOException {
        StringBuilder pom = new StringBuilder("""
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>demo</groupId>
              <artifactId>root</artifactId>
              <version>1.0</version>
              <packaging>pom</packaging>
              <properties>
                <maven.compiler.release>21</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.11.4</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <modules>
            """);
        for (String module : modules) {
            pom.append("    <module>").append(module).append("</module>\n");
        }
        pom.append("  </modules>\n</project>\n");
        Files.writeString(root.resolve("pom.xml"), pom.toString());
    }

    private void module(String dir, String pom) throws IOException {
        Files.createDirectories(root.resolve(dir));
        Files.writeString(root.resolve(dir).resolve("pom.xml"), pom);
    }

    private static String dependingOn(String artifactId, String... siblings) {
        StringBuilder pom = new StringBuilder("<project>\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <parent><groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version></parent>\n"
            + "  <artifactId>" + artifactId + "</artifactId>\n  <dependencies>\n");
        for (String sibling : siblings) {
            pom.append("    <dependency><groupId>demo</groupId><artifactId>").append(sibling)
               .append("</artifactId><version>1.0</version></dependency>\n");
        }
        pom.append("  </dependencies>\n</project>\n");
        return pom.toString();
    }

    private static void writeAcceptanceTest(Path dir, String className) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(className + ".java"), """
            package swarm.accept;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            class %s {
                @Test
                void theBehaviourIsThere() {
                    assertEquals(2, 1 + 1);
                }
            }
            """.formatted(className));
    }

    /**
     * The detector proposes {@code mvn -B …}; this build runs offline so a machine without network
     * still gets a verdict. Only {@code -o} is added — the selector, which is what is on trial
     * here, is left exactly as the detector wrote it.
     */
    private static VerifySpec offline(VerifySpec spec) {
        return new VerifySpec(spec.toolchain(), offline(spec.compile()), offline(spec.acceptance()),
            offline(spec.existing()), spec.lint(), spec.lintReports(), spec.testReports(),
            spec.timeoutSeconds(), spec.browser());
    }

    private static List<String> offline(List<String> commands) {
        return commands == null ? List.of()
            : commands.stream().map(c -> c.replaceFirst("^(\\S+)", "$1 -o")).toList();
    }
}
