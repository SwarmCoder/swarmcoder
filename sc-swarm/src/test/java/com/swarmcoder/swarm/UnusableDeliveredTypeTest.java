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
package com.swarmcoder.swarm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A task with no acceptance test of its own is checked for having delivered a class nobody else can
 * use (harness run 65, 2026-10-02): only the main-code files its own diff changed are read.
 */
class UnusableDeliveredTypeTest {

    @TempDir
    Path workspace;

    private static final String UNUSABLE = """
        package com.acme.i18n;
        public final class Texts {
            private Texts() { }
            public String saveLabel() { return "Save"; }
        }
        """;

    private static String diffOf(String path) {
        return "diff --git a/" + path + " b/" + path + "\n--- /dev/null\n+++ b/" + path
            + "\n@@ -0,0 +1 @@\n+x\n";
    }

    private void write(String path, String source) throws Exception {
        Path file = workspace.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void anUnusableClassTheCandidateWroteFailsItWithTheReason() throws Exception {
        String path = "app/src/main/java/com/acme/i18n/Texts.java";
        write(path, UNUSABLE);

        assertThat(UnusableDeliveredType.in(workspace, diffOf(path)))
            .contains(path + ": class Texts cannot be used by any other class")
            .contains("Make its members static");
    }

    @Test
    void whatTheCandidateDidNotWriteAndTestCodeAreNotRead() throws Exception {
        String main = "app/src/main/java/com/acme/i18n/Texts.java";
        String test = "app/src/test/java/com/acme/i18n/Texts.java";
        write(main, UNUSABLE);
        write(test, UNUSABLE);

        assertThat(UnusableDeliveredType.in(workspace, diffOf("README.md"))).isNull();
        assertThat(UnusableDeliveredType.in(workspace, diffOf(test))).isNull();
        assertThat(UnusableDeliveredType.in(workspace, null)).isNull();
        assertThat(UnusableDeliveredType.in(workspace, diffOf("app/src/main/java/Gone.java")))
            .as("a file that is not there settles nothing").isNull();
    }
}
