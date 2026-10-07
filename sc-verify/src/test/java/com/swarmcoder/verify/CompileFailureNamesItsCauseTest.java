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

import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A compile failure says whose fault it is before it is reported.
 *
 * <p>On 2026-09-02, task "Create shared Book data model and ReadingStatus enum" ran four candidates.
 * Every red chip on the run graph read "the candidate does not compile". Each candidate had written
 * exactly its two files, correctly; what failed was {@code test-compile}, on acceptance tests
 * committed to master by earlier runs, importing {@code swarm.Book} — a class that exists nowhere
 * and that no candidate was allowed to provide. The compile output below is that run's, verbatim.
 *
 * <p>Every assertion here is on the verdict sentence that {@link Verdicts} produces, because that
 * is the one sentence the engine writes onto the report, the run graph's hover card reads as the
 * failure reason, and the diagnosis tool repeats. Survival is asserted unchanged in every case: a
 * tree that does not compile still proves nothing about the candidate, whoever broke it.
 */
class CompileFailureNamesItsCauseTest {

    private static final String COMPILE = "mvn -o -q -B -DfastCompile compile test-compile";
    private static final String ACCEPTANCE_DIR = "bookshelf-demo-server/src/test/java/swarm/accept";
    private static final String OWN_BOOK =
        "bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/shared/Book.java";
    private static final String OWN_STATUS =
        "bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/shared/ReadingStatus.java";

    /** What the compile stage printed for every one of the four candidates that night. */
    private static final String TONIGHTS_OUTPUT = """
        [ERROR] COMPILATION ERROR :\s
        [ERROR] /workspace/bookshelf-demo-server/src/test/java/swarm/accept/BookTest.java:[9,13] cannot find symbol
          symbol:   class Book
          location: package swarm
        [ERROR] /workspace/bookshelf-demo-server/src/test/java/swarm/accept/BookTest.java:[10,13] cannot find symbol
          symbol:   class BookService
          location: package swarm
        [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project bookshelf-demo-server: Compilation failure: Compilation failure:\s
        [ERROR] /workspace/bookshelf-demo-server/src/test/java/swarm/accept/BookTest.java:[9,13] cannot find symbol
        [ERROR]   symbol:   class Book
        [ERROR]   location: package swarm
        [ERROR] /workspace/bookshelf-demo-server/src/test/java/swarm/accept/BookTest.java:[10,13] cannot find symbol
        [ERROR]   symbol:   class BookService
        [ERROR]   location: package swarm
        [ERROR] -> [Help 1]
        [ERROR]\s
        [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
        [ERROR] After correcting the problems, you can resume the build with the command
        [ERROR]   mvn <args> -rf :bookshelf-demo-server
        """;

    private static VerifySpec demoSpec() {
        return new VerifySpec("maven",
            List.of(COMPILE),
            List.of("mvn -o -q -B test -Dtest=swarm/accept/** -Dsurefire.failIfNoSpecifiedTests=false"),
            List.of("mvn -o -q -B test"),
            null, null, null, 60, null);
    }

    private static Task demoTask() {
        Task task = new Task();
        task.setTitle("Create shared Book data model and ReadingStatus enum");
        task.setWriteSet(Set.of("bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/shared"));
        task.setAcceptanceTestDir(ACCEPTANCE_DIR);
        return task;
    }

    private static String verdictFor(String compileOutput, Set<String> changedFiles) {
        FakeExecTarget target = new FakeExecTarget().scriptOutput(COMPILE, 1, compileOutput);
        VerificationReport report = new CommandPipelineVerifier().verify(
            target, demoTask(), demoSpec(), List.of(), changedFiles);
        assertThat(report.compiles()).isFalse();
        Verdicts.Verdict verdict = Verdicts.assess(report, List.of());
        assertThat(verdict.survived())
            .as("survival is not what changes here: a tree that does not compile proves nothing")
            .isFalse();
        return verdict.reason();
    }

    @Test
    void anAcceptanceTestFromAnEarlierRunIsNamedAsTheCauseNotTheCandidate() {
        String reason = verdictFor(TONIGHTS_OUTPUT, Set.of(OWN_BOOK, OWN_STATUS));

        assertThat(reason)
            .as("the sentence on the red chip")
            .startsWith("the tree does not compile before this candidate's change: "
                + "bookshelf-demo-server/src/test/java/swarm/accept/BookTest.java:9 "
                + "refers to swarm.Book, which does not exist.")
            .contains("acceptance test committed before this candidate ran")
            .contains("not this candidate's work")
            .doesNotContain("the candidate does not compile");
        assertThat(reason.lines().count())
            .as("the hover card shows the first line of the verdict and nothing else, so it all "
                + "has to be on that line")
            .isEqualTo(1);
    }

    @Test
    void theCandidatesOwnBrokenFileIsStillTheCandidatesFault() {
        String output = """
            [ERROR] COMPILATION ERROR :\s
            [ERROR] /workspace/bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/shared/Book.java:[12,20] cannot find symbol
            [ERROR]   symbol:   class ReadingStatus
            [ERROR]   location: class com.swarmcoder.demo.bookshelf.shared.Book
            [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project bookshelf-demo-shared: Compilation failure
            """;

        String reason = verdictFor(output, Set.of(OWN_BOOK, OWN_STATUS));

        assertThat(reason)
            .startsWith("the candidate does not compile: " + OWN_BOOK + ":12 cannot find symbol: "
                + "class ReadingStatus in class com.swarmcoder.demo.bookshelf.shared.Book")
            .doesNotContain("before this candidate's change");
    }

    @Test
    void mainCompilesAndATestTheCandidateWroteDoesNot() {
        String ownTest =
            "bookshelf-demo-shared/src/test/java/com/swarmcoder/demo/bookshelf/shared/BookRoundTripTest.java";
        String output = """
            [ERROR] /workspace/bookshelf-demo-shared/src/test/java/com/swarmcoder/demo/bookshelf/shared/BookRoundTripTest.java:[7,8] cannot find symbol
            [ERROR]   symbol:   class Bookk
            [ERROR]   location: package com.swarmcoder.demo.bookshelf.shared
            [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project bookshelf-demo-shared: Compilation failure
            """;

        String reason = verdictFor(output, Set.of(OWN_BOOK, OWN_STATUS, ownTest));

        assertThat(reason)
            .startsWith("the main code compiles, but the test tree does not: " + ownTest + ":7 "
                + "refers to com.swarmcoder.demo.bookshelf.shared.Bookk, which does not exist.")
            .contains("This candidate changed that test file")
            .contains("directory workers may not edit");
    }

    @Test
    void anUntouchedMainFileIsNotTheCandidatesWorkEither() {
        String output = """
            [ERROR] /workspace/bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/Main.java:[3,44] package com.swarmcoder.demo.bookshelf.client does not exist
            [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project bookshelf-demo-server: Compilation failure
            """;

        String reason = verdictFor(output, Set.of(OWN_BOOK, OWN_STATUS));

        assertThat(reason)
            .startsWith("the tree does not compile before this candidate's change: "
                + "bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/Main.java:3 "
                + "refers to package com.swarmcoder.demo.bookshelf.client, which does not exist.")
            .contains("This candidate did not touch that file");
    }

    @Test
    void gradleOutputOnALocalWorktreeIsAttributedTheSameWay() {
        Path worktree = Path.of("G:", "proj", "worktrees", "demo-w3");
        String output = worktree + "\\bookshelf-demo-server\\src\\test\\java\\swarm\\accept\\BookTest.java:9: "
            + "error: cannot find symbol\n"
            + "  symbol:   class Book\n"
            + "  location: package swarm\n"
            + "> Task :bookshelf-demo-server:compileTestJava FAILED\n";
        FakeExecTarget target = new FakeExecTarget().localRoot(worktree).scriptOutput(COMPILE, 1, output);

        VerificationReport report = new CommandPipelineVerifier().verify(
            target, demoTask(), demoSpec(), List.of(), Set.of(OWN_BOOK, OWN_STATUS));

        assertThat(Verdicts.assess(report, List.of()).reason())
            .startsWith("the tree does not compile before this candidate's change: "
                + "bookshelf-demo-server/src/test/java/swarm/accept/BookTest.java:9 "
                + "refers to swarm.Book, which does not exist.");
    }

    @Test
    void aFailureThatNamesNoFileSaysSoInsteadOfGuessing() {
        String output = """
            [ERROR] Failed to execute goal on project bookshelf-demo-server: Could not resolve dependencies for project a:b:jar:1: Cannot access central in offline mode
            """;

        String reason = verdictFor(output, Set.of(OWN_BOOK, OWN_STATUS));

        assertThat(reason)
            .startsWith("the candidate does not compile, and the compiler named no file: ")
            .contains("Could not resolve dependencies");
    }

    @Test
    void notKnowingWhatTheCandidateChangedIsReportedAsNotKnowing() {
        String reason = verdictFor(TONIGHTS_OUTPUT, Set.of());

        assertThat(reason)
            .startsWith("the candidate does not compile: "
                + "bookshelf-demo-server/src/test/java/swarm/accept/BookTest.java:9")
            .contains("could not be established")
            .doesNotContain("before this candidate's change");
    }

    @Test
    void theAttributionIsInTheLogTooForWhoeverReadsTheFullLog() {
        FakeExecTarget target = new FakeExecTarget().scriptOutput(COMPILE, 1, TONIGHTS_OUTPUT);

        VerificationReport report = new CommandPipelineVerifier().verify(
            target, demoTask(), demoSpec(), List.of(), Set.of(OWN_BOOK, OWN_STATUS));

        assertThat(report.logTail())
            .contains("[compile] the tree does not compile before this candidate's change: "
                + "bookshelf-demo-server/src/test/java/swarm/accept/BookTest.java:9");
        assertThat(report.compileFailure().failingFiles())
            .containsExactly("bookshelf-demo-server/src/test/java/swarm/accept/BookTest.java");
        assertThat(report.compileFailure().mainCompiled()).isTrue();
    }

    // ------------------------------------ a dependency the offline repository does not hold

    /** The server module's pom — a file a worker may now write. */
    private static final String SERVER_POM = "bookshelf-demo-server/pom.xml";

    /**
     * Maven 3.9's own wording, offline, for an artifact the local repository does not hold.
     *
     * <p>Note that the failing PROJECT's coordinate is printed in the same sentence as the artifact
     * that could not be found. Naming the project instead of the artifact would be exactly
     * backwards, and this output is here to catch that.
     */
    private static final String OFFLINE_RESOLUTION_FAILURE = """
        [INFO] Scanning for projects...
        [INFO] ------< com.swarmcoder.demo:bookshelf-demo-server >------
        [ERROR] Failed to execute goal on project bookshelf-demo-server: Could not resolve \
        dependencies for project com.swarmcoder.demo:bookshelf-demo-server:jar:1.0.0-SNAPSHOT: \
        The following artifacts could not be resolved: \
        com.zeroz4j:zerozstack-store-eclipsestore:jar:0.9.9 (absent): Cannot access central \
        (https://repo.maven.apache.org/maven2) in offline mode and the artifact \
        com.zeroz4j:zerozstack-store-eclipsestore:jar:0.9.9 has not been downloaded from it \
        before. -> [Help 1]
        [ERROR]\s
        [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
        """;

    /**
     * The failure a worker can cause since 2026-09-03 and could not before: it declared a
     * dependency the offline repository does not hold. The old attribution could say nothing at all
     * about this — resolution fails before javac runs, so there is no file-and-line error to find
     * and every candidate got "the compiler named no file".
     */
    @Test
    void aDependencyTheOfflineRepositoryDoesNotHoldIsNamedAndAttributedToTheCandidate() {
        String reason = verdictFor(OFFLINE_RESOLUTION_FAILURE, Set.of(SERVER_POM, OWN_BOOK));

        assertThat(reason)
            .contains("the candidate declared")
            .contains("com.zeroz4j:zerozstack-store-eclipsestore:0.9.9")
            .contains(SERVER_POM)
            .contains("no network");
        assertThat(reason)
            .as("the project whose build failed is not the artifact that was missing")
            .doesNotContain("bookshelf-demo-server:jar");
    }

    @Test
    void theJudgeIsToldWhichLibraryAndWhereItWasDeclared() {
        FakeExecTarget target =
            new FakeExecTarget().scriptOutput(COMPILE, 1, OFFLINE_RESOLUTION_FAILURE);

        VerificationReport report = new CommandPipelineVerifier().verify(
            target, demoTask(), demoSpec(), List.of(), Set.of(SERVER_POM));

        assertThat(report.compileFailure().cause())
            .isEqualTo(com.swarmcoder.domain.CompileFailureCause.UNRESOLVABLE_DEPENDENCY);
        assertThat(report.compileFailure().file()).isEqualTo(SERVER_POM);
        assertThat(report.compileFailure().explanation())
            .contains("declare only a library the knowledge brief lists as available");
    }

    /**
     * The same output from a candidate that changed no build file: the coordinate is still named,
     * and nothing is pinned on the candidate. The declaration was in the tree before it arrived and
     * the repository is short of an artifact — not something a worker can fix, and it must not read
     * as though it were.
     */
    @Test
    void anUnresolvableDependencyTheCandidateDidNotDeclareIsNotItsFault() {
        String reason = verdictFor(OFFLINE_RESOLUTION_FAILURE, Set.of(OWN_BOOK, OWN_STATUS));

        assertThat(reason)
            .contains("com.zeroz4j:zerozstack-store-eclipsestore:0.9.9")
            .contains("changed no build file")
            .doesNotContain("the candidate declared");
    }
}
