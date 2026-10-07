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
package com.swarmcoder.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every path a model hands a file tool goes through {@link ConfinedPath}. These are the four ways
 * out of a root that it has to refuse, each with a sentence the model can act on.
 */
class ConfinedPathTest {

    @TempDir
    Path tmp;

    private Path root() throws IOException {
        Path root = tmp.resolve("checkout");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {}\n");
        return root;
    }

    @Test
    void aRelativePathInsideTheRootIsAllowed() throws IOException {
        Path root = root();
        ConfinedPath.Result result = ConfinedPath.resolve(root, "src/App.java", "your checkout");
        assertThat(result.allowed()).isTrue();
        assertThat(result.path()).isEqualTo(root.toAbsolutePath().normalize().resolve("src/App.java"));
        // Backslashes and a harmless `..` that stays inside are still inside.
        assertThat(ConfinedPath.resolve(root, "src\\..\\src\\App.java", "your checkout").allowed())
            .isTrue();
        // A file that does not exist yet is not an escape.
        assertThat(ConfinedPath.resolve(root, "src/New.java", "your checkout").allowed()).isTrue();
    }

    @Test
    void absolutePathsAndDriveLettersAreRefusedNotReRooted() throws IOException {
        Path root = root();
        for (String path : new String[] {"/etc/passwd", "C:\\Windows\\win.ini", "c:/Users",
            "C:relative.txt", "\\\\server\\share\\x", "~/.ssh/id_rsa",
            root.toAbsolutePath() + "/src/App.java"}) {
            ConfinedPath.Result result = ConfinedPath.resolve(root, path, "your checkout");
            assertThat(result.allowed()).as(path).isFalse();
            assertThat(result.path()).as(path).isNull();
            assertThat(result.refusal()).as(path).contains("absolute path").contains("your checkout");
        }
    }

    @Test
    void climbingOutWithDotDotIsRefused() throws IOException {
        Path root = root();
        Files.writeString(tmp.resolve("secret.txt"), "outside\n");
        for (String path : new String[] {"../secret.txt", "src/../../secret.txt",
            "src\\..\\..\\secret.txt", ".."}) {
            ConfinedPath.Result result = ConfinedPath.resolve(root, path, "your checkout");
            assertThat(result.allowed()).as(path).isFalse();
            assertThat(result.refusal()).as(path).contains("leads outside your checkout");
        }
    }

    @Test
    void aLinkInsideTheRootThatLeadsOutOfItIsRefused() throws IOException {
        Path root = root();
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "outside\n");
        assumeTrue(LinkForTests.directoryLink(root.resolve("way-out"), outside),
            "this machine lets the test create neither a symbolic link nor a junction");

        ConfinedPath.Result result =
            ConfinedPath.resolve(root, "way-out/secret.txt", "your checkout");
        assertThat(result.allowed()).isFalse();
        assertThat(result.refusal()).contains("symbolic link").contains("outside your checkout");
        // Including for a file that does not exist yet: a write through the link lands outside.
        assertThat(ConfinedPath.resolve(root, "way-out/new.txt", "your checkout").allowed())
            .isFalse();
        assertThat(ConfinedPath.inside(root, root.resolve("way-out/secret.txt"))).isFalse();
        assertThat(ConfinedPath.inside(root, root.resolve("src/App.java"))).isTrue();
    }

    @Test
    void blankAndUnusablePathsAreRefused() throws IOException {
        Path root = root();
        assertThat(ConfinedPath.resolve(root, " ", "your checkout").allowed()).isFalse();
        assertThat(ConfinedPath.resolve(root, null, "your checkout").allowed()).isFalse();
        assertThat(ConfinedPath.resolve(root, "src/\0App.java", "your checkout").allowed()).isFalse();
        assertThat(ConfinedPath.resolve(null, "src/App.java", "your checkout").allowed()).isFalse();
    }
}
