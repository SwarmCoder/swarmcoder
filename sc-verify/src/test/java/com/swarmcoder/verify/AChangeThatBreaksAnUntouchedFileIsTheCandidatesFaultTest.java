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

import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.CompileFailureCause;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 74: a candidate added two methods to an interface; the class implementing it, which
 * the candidate did not touch, stopped compiling; the verdict said the tree had been broken
 * before the candidate. The tree had compiled. With the start tree measured, the verdict blames
 * the change, names what was changed and names the untouched file.
 */
class AChangeThatBreaksAnUntouchedFileIsTheCandidatesFaultTest {

    private static final String API = "app-shared/src/main/java/org/example/shop/OrderService.java";
    private static final String IMPL =
        "app-server/src/main/java/org/example/shop/server/OrderServiceImpl.java";

    /** The shape of run 74's compile output, with other names. */
    private static final String IMPLEMENTOR_BROKEN = """
        [ERROR] COMPILATION ERROR :\s
        [ERROR] /workspace/app-server/src/main/java/org/example/shop/server/OrderServiceImpl.java:[18,8] org.example.shop.server.OrderServiceImpl is not abstract and does not override abstract method cancel(java.lang.String) in org.example.shop.OrderService
        [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project app-server: Compilation failure
        [ERROR] /workspace/app-server/src/main/java/org/example/shop/server/OrderServiceImpl.java:[18,8] org.example.shop.server.OrderServiceImpl is not abstract and does not override abstract method cancel(java.lang.String) in org.example.shop.OrderService
        [ERROR] -> [Help 1]
        """;

    private static final String ACCEPTANCE_TEST_RED = """
        [ERROR] COMPILATION ERROR :\s
        [ERROR] /workspace/app-server/src/test/java/swarm/accept/CancelTest.java:[9,13] cannot find symbol
          symbol:   method cancel(java.lang.String)
          location: interface org.example.shop.OrderService
        [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project app-server: Compilation failure
        """;

    private static CompileFailure attribute(VerificationBaseline.StartCompile start) {
        return CompileFailureAttribution.attribute(IMPLEMENTOR_BROKEN, Set.of(API),
            "app-server/src/test/java/swarm/accept", null, List.of(), start);
    }

    @Test
    void theStartTreeCompiledSoTheChangeIsTheCause() {
        CompileFailure failure = attribute(CompileFailureAttribution.startTree(null, null));

        assertThat(failure.cause()).isEqualTo(CompileFailureCause.CAUSED_BY_CHANGE);
        assertThat(failure.file()).isEqualTo(IMPL);
        assertThat(failure.describe())
            .contains("this candidate's change broke a file it did not change")
            .contains(IMPL)
            .contains("does not override abstract method cancel")
            .contains("it changed OrderService (" + API + ")")
            .contains("must change with it")
            .doesNotContain("not its work")
            .doesNotContain("before this candidate's change");
    }

    @Test
    void onlyTheAcceptanceTestWasRedOnTheStartTreeAndThatIsStillTheChangesFault() {
        VerificationBaseline.StartCompile start =
            CompileFailureAttribution.startTree(ACCEPTANCE_TEST_RED, null);
        assertThat(start.established()).isTrue();
        assertThat(start.compiles()).isFalse();
        assertThat(start.mainCompiles()).isTrue();

        assertThat(attribute(start).cause()).isEqualTo(CompileFailureCause.CAUSED_BY_CHANGE);
    }

    @Test
    void aFileThatWasAlreadyBrokenOnTheStartTreeIsNotBlamedOnTheCandidate() {
        VerificationBaseline.StartCompile start =
            CompileFailureAttribution.startTree(IMPLEMENTOR_BROKEN, null);
        assertThat(start.mainCompiles()).isFalse();

        CompileFailure failure = attribute(start);
        assertThat(failure.cause()).isEqualTo(CompileFailureCause.PRE_EXISTING);
        assertThat(failure.describe()).contains("the tree does not compile before");
    }

    @Test
    void anAcceptanceTestThatWasRedBeforeStaysTheTreesFault() {
        VerificationBaseline.StartCompile start =
            CompileFailureAttribution.startTree(ACCEPTANCE_TEST_RED, null);
        CompileFailure failure = CompileFailureAttribution.attribute(ACCEPTANCE_TEST_RED,
            Set.of(API), "app-server/src/test/java/swarm/accept", null, List.of(), start);

        assertThat(failure.cause()).isEqualTo(CompileFailureCause.PRE_EXISTING);
    }

    @Test
    void anUnmeasuredStartTreeKeepsTheOldReading() {
        assertThat(attribute(null).cause()).isEqualTo(CompileFailureCause.PRE_EXISTING);
        assertThat(attribute(VerificationBaseline.StartCompile.UNKNOWN).cause())
            .isEqualTo(CompileFailureCause.PRE_EXISTING);
    }

    @Test
    void theStartTreesCompileIsMeasuredOnce() {
        VerifySpec spec = new VerifySpec("maven", List.of("mvn compile test-compile"), List.of(),
            List.of("mvn test"), List.of(), null, null, 60, null);
        FakeExecTarget red = new FakeExecTarget()
            .scriptOutput("mvn compile test-compile", 1, ACCEPTANCE_TEST_RED);
        VerificationBaseline.StartCompile measured =
            BaseTreeChecks.compile(red, spec, new StringBuilder());

        assertThat(measured.established()).isTrue();
        assertThat(measured.hadErrorIn("app-server/src/test/java/swarm/accept/CancelTest.java"))
            .isTrue();
        assertThat(measured.wasCleanOnTheStartTree(IMPL, false)).isTrue();
        assertThat(BaseTreeChecks.existingTests(red, spec, new StringBuilder(), measured)
            .established()).as("a tree that does not compile has no test baseline").isFalse();
    }
}
