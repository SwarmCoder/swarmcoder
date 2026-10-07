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
package com.swarmcoder.app;

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.verify.BuildBoxes;
import com.swarmcoder.verify.ContractProbe;
import com.swarmcoder.verify.VerifySpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registration compile probe builds a throwaway copy of the checkout, never the working
 * folder, and removes the copy afterwards. No container is started here: what is asserted is the
 * copy, the refusal without a container, and the clean-up.
 */
class ProbeOnACopyTest {

    @TempDir
    Path work;

    private static VerifySpec compiling(String command) {
        return new VerifySpec("maven", List.of(command), List.of(), List.of(), List.of(),
            List.of(), null, 60, null);
    }

    private Path checkout() throws Exception {
        Path repo = Files.createDirectories(work.resolve("checkout"));
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        BookshelfFixture.git(repo, "init -q");
        BookshelfFixture.git(repo, "-c user.email=t@t -c user.name=t add -A");
        BookshelfFixture.git(repo, "-c user.email=t@t -c user.name=t commit -q -m first");
        return repo;
    }

    private static long entries(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.count();
        }
    }

    @Test
    void withNoContainerAndNoPermissionNothingRunsAndNothingIsLeftBehind() throws Exception {
        Path repo = checkout();
        Path scratch = work.resolve("scratch");

        ContractProbe.Result result = ProbeOnACopy.probe(repo, compiling("echo built>built.marker"),
            30, BuildBoxes.none(), scratch);

        assertThat(result.ran()).isFalse();
        assertThat(result.compiles()).isFalse();
        assertThat(result.verdict()).contains("Refused");
        assertThat(entries(scratch)).as("the throwaway copy is removed").isZero();
        assertThat(BookshelfFixture.git(repo, "worktree list").strip().split("\n"))
            .as("no worktree is left registered").hasSize(1);
    }

    @Nested
    @ModelCodeOnThisPc
    class OnThisPcForTheTest {

        @Test
        void theBuildHappensInACopyAndTheWorkingFolderIsNotTouched() throws Exception {
            Path repo = checkout();
            Path scratch = work.resolve("scratch");

            ContractProbe.Result result = ProbeOnACopy.probe(repo,
                compiling("echo built>built.marker"), 30, BuildBoxes.none(), scratch);

            assertThat(result.compiles()).as(result.verdict() + result.logTail()).isTrue();
            assertThat(Files.exists(repo.resolve("built.marker")))
                .as("the build's output must not appear in the operator's folder").isFalse();
            assertThat(entries(scratch)).as("the throwaway copy is removed").isZero();
            assertThat(BookshelfFixture.git(repo, "worktree list").strip().split("\n"))
                .hasSize(1);
        }

        @Test
        void aFolderWithNoGitHistoryIsCopiedAndTheCopyIsRemoved() throws Exception {
            Path plain = Files.createDirectories(work.resolve("plain"));
            Files.writeString(plain.resolve("pom.xml"), "<project/>");
            Path scratch = work.resolve("scratch2");

            ContractProbe.Result result = ProbeOnACopy.probe(plain,
                compiling("echo built>built.marker && dir pom.xml >nul 2>&1 || test -f pom.xml"),
                30, BuildBoxes.none(), scratch);

            assertThat(result.compiles()).as(result.verdict() + result.logTail()).isTrue();
            assertThat(Files.exists(plain.resolve("built.marker"))).isFalse();
            assertThat(entries(scratch)).isZero();
        }
    }
}
