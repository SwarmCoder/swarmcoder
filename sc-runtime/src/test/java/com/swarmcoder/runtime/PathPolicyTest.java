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
package com.swarmcoder.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The containment rules for worker writes. Most of these are regression tests for bypasses that
 * were live: the write-set check used to run on the raw string while the escape check ran on the
 * normalised path, so a path could satisfy both and still land outside the write set.
 */
class PathPolicyTest {

    @Test
    void traversalInsideAnAllowedPrefixNoLongerEscapesTheWriteSet(@TempDir Path root) {
        String canonical = PathPolicy.canonicalize(root, "src/../pom.xml");

        // Canonicalising FIRST is the whole fix: "src/../pom.xml" is pom.xml, and pom.xml is not
        // in the write set. Previously it matched the raw prefix "src/" and was written anyway.
        assertThat(canonical).isEqualTo("pom.xml");
        PathPolicy.Verdict verdict =
            PathPolicy.check(canonical, Set.of("src"), null, List.of());
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("outside your write set");
    }

    @Test
    void traversalNoLongerReachesTheProtectedAcceptanceTests(@TempDir Path root) {
        String canonical = PathPolicy.canonicalize(root, "docs/../src/test/AcceptTest.java");

        assertThat(canonical).isEqualTo("src/test/AcceptTest.java");
        assertThat(PathPolicy.check(canonical, Set.of("docs"), "src/test", List.of()).reason())
            .contains("acceptance tests are protected");
    }

    @Test
    void pathsOutsideTheWorktreeAreRefused(@TempDir Path root) {
        assertThat(PathPolicy.canonicalize(root, "../escape.txt")).isNull();
        assertThat(PathPolicy.canonicalize(root, "../../../../etc/passwd")).isNull();
        assertThat(PathPolicy.check(null, Set.of(), null, List.of()).reason())
            .contains("escapes the repository");
    }

    @Test
    void aSymlinkPointingOutOfTheWorktreeIsRefused(@TempDir Path root, @TempDir Path outside) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(outside.resolve("secret.txt"), "s");
        try {
            Files.createSymbolicLink(root.resolve("src").resolve("link"), outside);
        } catch (Exception e) {
            return; // creating symlinks needs privileges on Windows; nothing to assert here
        }

        // normalize() is lexical and follows this straight out of the worktree; only resolving the
        // real path catches it. Nothing in the codebase did that before.
        assertThat(PathPolicy.canonicalize(root, "src/link/secret.txt")).isNull();
    }

    @Test
    void theBuildAndVerificationConfigCanNeverBeWritten(@TempDir Path root) {
        // .swarmcoder/verify.yaml names the commands that judge a candidate, and they run on the
        // host at integration. A worker able to edit it certifies itself and gets host execution.
        assertThat(PathPolicy.check(PathPolicy.canonicalize(root, ".swarmcoder/verify.yaml"),
            Set.of(".swarmcoder"), null, List.of()).reason()).contains("protected");
        assertThat(PathPolicy.check(PathPolicy.canonicalize(root, ".git/hooks/pre-commit"),
            Set.of(), null, List.of()).reason()).contains("protected");
    }

    @Test
    void operatorDeclaredModulesAreLockedEvenInsideTheWriteSet(@TempDir Path root) {
        PathPolicy.Verdict verdict = PathPolicy.check(
            PathPolicy.canonicalize(root, "src/main/java/com/acme/payments/Ledger.java"),
            Set.of("src"), null, List.of("src/main/java/com/acme/payments"));

        // A locked module beats the write set: the point is that some code is off limits even to
        // a task that was legitimately given the surrounding tree.
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("locked module");
    }

    @Test
    void prefixMatchingRespectsPathSegments(@TempDir Path root) {
        // "src" must not authorise "srcgen/" — a raw startsWith would have.
        assertThat(PathPolicy.check(PathPolicy.canonicalize(root, "srcgen/Foo.java"),
            Set.of("src"), null, List.of()).allowed()).isFalse();
        assertThat(PathPolicy.check(PathPolicy.canonicalize(root, "src/Foo.java"),
            Set.of("src"), null, List.of()).allowed()).isTrue();
        // An exact file entry is honoured too.
        assertThat(PathPolicy.check(PathPolicy.canonicalize(root, "pom.xml"),
            Set.of("pom.xml"), null, List.of()).allowed()).isTrue();
    }

    @Test
    void anEmptyWriteSetStaysUnrestrictedButProtectionStillApplies(@TempDir Path root) {
        assertThat(PathPolicy.check(PathPolicy.canonicalize(root, "anything/at/all.java"),
            Set.of(), null, List.of()).allowed()).isTrue();
        // …except the things that are never writable.
        assertThat(PathPolicy.check(PathPolicy.canonicalize(root, ".swarmcoder/verify.yaml"),
            Set.of(), null, List.of()).allowed()).isFalse();
    }

    @Test
    void auditReportsEveryOutOfBoundsPathInADiff(@TempDir Path root) {
        List<String> refusals = PathPolicy.auditAll(
            List.of("src/Ok.java", "pom.xml", ".swarmcoder/verify.yaml"),
            Set.of("src"), null, List.of());

        // Integration needs ALL of them, not just the first: the operator is deciding whether to
        // trust the candidate at all.
        assertThat(refusals).hasSize(2);
        assertThat(refusals.get(0)).contains("pom.xml");
        assertThat(refusals.get(1)).contains("protected");
    }

    /**
     * The severity line (2026-09-02). Everything the system's own guarantees rest on stays lethal;
     * a plain step outside the task's slice does not.
     *
     * <p>The lethal list is not a matter of taste. A worker that can write {@code .swarmcoder/}
     * chooses the commands the host runs unsandboxed to decide whether it passed; one that can
     * write the acceptance-test directory edits the test that judges it; one that can write
     * {@code .git/} owns the hooks. Each of those disables the check that would have caught it, so
     * each stays a refusal and each still counts toward the kill. Being in the wrong module does
     * none of that.
     */
    @Test
    void onlyThingsThatDisableTheSystemsOwnChecksAreLethal(@TempDir Path root) {
        // Outside the write set: refused as a verdict, but survivable - the caller writes anyway
        // and records the path.
        PathPolicy.Verdict outside = PathPolicy.check(
            PathPolicy.canonicalize(root, "other-module/Book.java"), Set.of("src"), null, List.of());
        assertThat(outside.allowed()).isFalse();
        assertThat(outside.lethal()).isFalse();

        // The four that must never soften.
        for (String path : List.of(".swarmcoder/verify.yaml", ".git/hooks/pre-commit",
                "src/test/accept/JudgeMeTest.java", "locked/Thing.java")) {
            PathPolicy.Verdict verdict = PathPolicy.check(PathPolicy.canonicalize(root, path),
                Set.of(), "src/test/accept", List.of("locked"));
            assertThat(verdict.allowed()).as(path).isFalse();
            assertThat(verdict.lethal()).as(path).isTrue();
        }

        // A path that leaves the repository entirely.
        PathPolicy.Verdict escape = PathPolicy.check(
            PathPolicy.canonicalize(root, "../elsewhere.txt"), Set.of(), null, List.of());
        assertThat(escape.allowed()).isFalse();
        assertThat(escape.lethal()).isTrue();
    }

    /** The split audit integration reads: what parks the run, and what is merely recorded. */
    @Test
    void theSplitAuditSeparatesWhatParksTheRunFromWhatIsOnlyRecorded(@TempDir Path root) {
        PathPolicy.Audit audit = PathPolicy.audit(
            List.of("src/Ok.java", "shared/Book.java", "shared/Book.java",
                ".swarmcoder/verify.yaml"),
            Set.of("src"), null, List.of());

        assertThat(audit.blocking()).hasSize(1);
        assertThat(audit.blocking().get(0)).contains("protected");
        // The neighbouring file is a fact about the candidate, not a reason to stop - and it is
        // listed once however many times the diff touched it.
        assertThat(audit.outOfWriteSet()).containsExactly("shared/Book.java");
    }

    /**
     * A module's build file is ORDINARY (2026-09-03). No rule in this class ever mentioned a pom —
     * but no task's write set ever contained one either, which had the same effect and cost run
     * {@code ede2068b} four workers: the project's rules named a library the server pom did not
     * declare, none of them could add it, and one concluded "the poms are locked, so the server
     * must use an in-memory root". Build files are now put into the write set of every task that
     * writes that module's sources, and this is what that relies on.
     */
    @Test
    void aModuleBuildFileInTheWriteSetIsWritable() {
        assertThat(PathPolicy.check("server/pom.xml",
            Set.of("server/src/main/java", "server/pom.xml"), "src/test/java/swarm", List.of())
            .allowed()).isTrue();
        assertThat(PathPolicy.check("server/build.gradle.kts",
            Set.of("server/src/main/kotlin", "server/build.gradle.kts"), null, List.of())
            .allowed()).isTrue();
        assertThat(PathPolicy.check("pom.xml", Set.of("pom.xml"), null, List.of()).allowed())
            .isTrue();
    }

    /**
     * …and nothing else moved. The write set here is the whole repository on purpose, so what
     * refuses each of these is the protection itself and not a narrow write set.
     */
    @Test
    void everythingProtectedBeforeIsStillProtected() {
        Set<String> everything = Set.of(".");

        PathPolicy.Verdict verify =
            PathPolicy.check(".swarmcoder/verify.yaml", everything, null, List.of());
        assertThat(verify.lethal()).isTrue();
        assertThat(verify.reason()).contains("protected");

        assertThat(PathPolicy.check(".git/hooks/pre-commit", everything, null, List.of()).lethal())
            .isTrue();

        PathPolicy.Verdict locked = PathPolicy.check("vendor/pom.xml",
            Set.of("vendor/src/main/java", "vendor/pom.xml"), null, List.of("vendor"));
        assertThat(locked.lethal())
            .as("a locked module is fenced off whole; its build file is inside the fence")
            .isTrue();
        assertThat(locked.reason()).contains("locked module");

        PathPolicy.Verdict acceptance = PathPolicy.check(
            "src/test/java/swarm/accept/ShelfAcceptTest.java", everything, "src/test/java/swarm",
            List.of());
        assertThat(acceptance.lethal()).isTrue();
        assertThat(acceptance.reason()).contains("acceptance tests are protected");
    }
}
