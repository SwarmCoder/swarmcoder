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

import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A test that errors at class level in framework start-up, before any project code runs, is
 * BROKEN, not a healthy red (live harness run 51, 2026-09-30: a Helidon MP / CDI container died
 * with an NPE in {@code SecurityCdiExtension} and the red check confirmed it as red).
 */
class BrokenAtStartupTest {

    private static final String HELIDON_TRACE =
        "java.lang.NullPointerException: Cannot invoke \"io.helidon.config.Config.get(String)\" "
        + "because \"config\" is null\n"
        + "\tat io.helidon.microprofile.security.SecurityCdiExtension.registerSecurity(SecurityCdiExtension.java:90)\n"
        + "\tat io.helidon.microprofile.server.JaxRsCdiExtension.findNamedRouting(JaxRsCdiExtension.java:120)\n";

    private static TestResults results(int passed, TestFailure... failures) {
        return new TestResults(passed, 0, failures.length, 0, List.of(failures),
            passed == 0 ? List.of() : List.of("swarm.accept.BookCrudTest#other"), List.of(), false);
    }

    @Test
    void aClassLevelErrorInFrameworkStartupIsBroken() {
        TestResults r = results(0, new TestFailure("swarm.accept.BookCrudTest#",
            "Cannot invoke \"io.helidon.config.Config.get(String)\" because \"config\" is null",
            HELIDON_TRACE));

        BrokenAtStartup.Finding finding = BrokenAtStartup.find(r, Set.of("com.demo.bookshelf"));

        assertThat(finding).isNotNull();
        assertThat(finding.testClass()).isEqualTo("swarm.accept.BookCrudTest");
        assertThat(finding.reason()).contains("class level").contains("NullPointerException");
        assertThat(finding.failureText()).contains("SecurityCdiExtension");
    }

    @Test
    void everyMethodErroringWithNoProjectFrameIsBroken() {
        TestResults r = results(0,
            new TestFailure("swarm.accept.BookCrudTest#editsBookFields", "boom", HELIDON_TRACE),
            new TestFailure("swarm.accept.BookCrudTest#removesBook", "boom", HELIDON_TRACE));

        assertThat(BrokenAtStartup.find(r, Set.of("com.demo.bookshelf"))).isNotNull();
    }

    @Test
    void everyMethodErroringWithAnNpeThrownInTheTestMethodIsStillRed() {
        // A stub returns null and the test calls .size() on it: frames only in the test class.
        String trace = "java.lang.NullPointerException: Cannot invoke \"java.util.List.size()\" "
            + "because the return value is null\n"
            + "\tat swarm.accept.BookCrudTest.editsBookFields(BookCrudTest.java:20)\n";
        TestResults r = results(0,
            new TestFailure("swarm.accept.BookCrudTest#editsBookFields", "npe", trace),
            new TestFailure("swarm.accept.BookCrudTest#removesBook", "npe",
                trace.replace("editsBookFields", "removesBook")));

        assertThat(BrokenAtStartup.find(r, Set.of("com.demo.bookshelf"))).isNull();
    }

    @Test
    void anErrorThatEntersProjectCodeIsAHealthyRed() {
        String trace = "java.lang.UnsupportedOperationException: not implemented\n"
            + "\tat com.demo.bookshelf.BookStore.remove(BookStore.java:12)\n"
            + "\tat swarm.accept.BookCrudTest.removesBook(BookCrudTest.java:20)\n";
        TestResults r = results(0,
            new TestFailure("swarm.accept.BookCrudTest#", "not implemented", trace));

        assertThat(BrokenAtStartup.find(r, Set.of("com.demo.bookshelf"))).isNull();
    }

    @Test
    void anAssertionFailureAndAPartlyPassingClassAreHealthyRed() {
        String assertion = "org.opentest4j.AssertionFailedError: expected: <5> but was: <0>\n"
            + "\tat swarm.accept.BookCrudTest.editsBookFields(BookCrudTest.java:20)\n";
        TestResults assertionOnly = results(0,
            new TestFailure("swarm.accept.BookCrudTest#editsBookFields", "expected", assertion));
        assertThat(BrokenAtStartup.find(assertionOnly, Set.of())).isNull();

        // One method errors with no project frame, but a sibling passed and it is not class level:
        // that is one bad method, not a container that never came up.
        TestResults partly = results(1,
            new TestFailure("swarm.accept.BookCrudTest#removesBook", "boom", HELIDON_TRACE));
        assertThat(BrokenAtStartup.find(partly, Set.of("com.demo.bookshelf"))).isNull();
    }

    @Test
    void projectPackagesAreReadFromMainSourceDirectoriesAndNotTestOnes(@TempDir Path tree)
            throws Exception {
        Path main = tree.resolve("server/src/main/java/com/demo/bookshelf");
        Files.createDirectories(main);
        Files.writeString(main.resolve("BookStore.java"), "package com.demo.bookshelf; class BookStore {}");
        Path test = tree.resolve("server/src/test/java/swarm/accept");
        Files.createDirectories(test);
        Files.writeString(test.resolve("BookCrudTest.java"), "package swarm.accept; class BookCrudTest {}");

        assertThat(BrokenAtStartup.projectPackages(tree, List.of()))
            .containsExactly("com.demo.bookshelf");
    }

    @Test
    void aCompileFailureRedStateIsNeverReadAsBrokenAtStartup() {
        FakeExecTarget target = new FakeExecTarget().scriptOutput("mvn test", 1,
            "BookCrudTest.java:5: error: cannot find symbol\n  symbol:   class Book\n"
            + "  location: package com.demo");
        VerifySpec spec = new VerifySpec("maven", null, List.of("mvn test"), null, null,
            null, null, 60, null);
        RedChecker.RedCheckResult result = new RedChecker().check(target, spec);

        assertThat(result.compileFailure()).isTrue();
        assertThat(RedChecker.brokenAtStartup(result, null, List.of())).isNull();
    }

    /**
     * Audit of 2026-10-02: a container that cannot start because the service the plan has yet to
     * write is missing has no project frame in its trace, and is still exactly what a candidate
     * will change. It names a project type; that is enough to leave it a healthy red.
     */
    @Test
    void aFrameworkFailureAboutATypeThePlanDeliversIsAHealthyRed() {
        String trace = "org.jboss.weld.exceptions.DeploymentException: WELD-001408: Unsatisfied "
            + "dependencies for type LogbookService with qualifiers @Default\n"
            + "  at injection point [BackedAnnotatedField] @Inject private "
            + "com.acme.server.LogbookEndpoint.service\n"
            + "\tat org.jboss.weld.bootstrap.Validator.validateInjectionPointForDeploymentProblems"
            + "(Validator.java:378)\n"
            + "\tat org.jboss.weld.bootstrap.WeldStartup.validateBeans(WeldStartup.java:526)\n";
        TestResults r = results(0,
            new TestFailure("swarm.accept.LogbookTest#", "WELD-001408: Unsatisfied dependencies", trace));

        assertThat(BrokenAtStartup.find(r, Set.of("com.acme.server"))).isNull();
        assertThat(BrokenAtStartup.find(r, Set.of("com.other.app"))).isNotNull();
    }
}
