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

import com.swarmcoder.domain.HostExecution;
import com.swarmcoder.sandbox.DockerSandboxManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * With no container, a build of model-written code is refused. It runs on this PC only when that
 * was allowed by name, and stops being allowed when the name is withdrawn.
 */
class BuildBoxesTest {

    @TempDir
    Path tree;

    @Test
    void withNoContainerTheBuildIsRefusedAndSaysWhy() {
        assertThat(HostExecution.allowedBy()).isEmpty();
        assertThatThrownBy(() -> BuildBoxes.none().open(tree, "The red check"))
            .isInstanceOf(DockerSandboxManager.SandboxException.class)
            .hasMessageContaining("Refused to run The red check on this PC")
            .hasMessageContaining("only inside a container");
        assertThatThrownBy(() -> BuildBoxes.none().use(tree, "The red check"))
            .isInstanceOf(DockerSandboxManager.SandboxException.class);
    }

    @Test
    void thisPcIsUsedOnlyWhileItIsAllowedByName() throws Exception {
        String by = "the test BuildBoxesTest";
        HostExecution.allow(by);
        try (BuildBoxes.Box box = BuildBoxes.none().open(tree, "The red check")) {
            assertThat(HostExecution.allowedBy()).contains(by);
            assertThat(box.containerId()).isNull();
            assertThat(box.target()).isInstanceOf(LocalProcessExecTarget.class);
            assertThat(box.target().exec("echo ran-here", 30).output()).contains("ran-here");
        } finally {
            HostExecution.withdraw(by);
        }
        assertThat(HostExecution.allowedBy()).isEmpty();
        assertThatThrownBy(() -> BuildBoxes.none().open(tree, "The red check"))
            .isInstanceOf(DockerSandboxManager.SandboxException.class);
    }
}
