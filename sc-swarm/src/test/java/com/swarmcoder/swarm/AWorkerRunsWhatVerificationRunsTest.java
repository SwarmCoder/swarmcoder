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

import com.swarmcoder.domain.Task;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.verify.ExecResult;
import com.swarmcoder.verify.ExecTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Section 114, finding 1: a failing build looked passed. A pipeline fails when any stage fails,
 * and a worker's {@code verify} runs the contract's own commands from the root of its checkout.
 * No model, no container: the target the commands go to is a recording one.
 */
class AWorkerRunsWhatVerificationRunsTest {

    @TempDir
    Path checkout;

    @TempDir
    Path project;

    /** Records each command and answers with what {@code answer} says. */
    static final class Recording implements ExecTarget {
        final List<String> commands = new ArrayList<>();
        final Function<String, ExecResult> answer;

        Recording(Function<String, ExecResult> answer) {
            this.answer = answer;
        }

        @Override
        public ExecResult exec(String command, int timeoutSeconds) {
            commands.add(command);
            return answer.apply(command);
        }

        @Override
        public String readFile(String relativePath, int maxBytes) {
            return null;
        }

        @Override
        public List<String> listFiles(String relativeDir, String suffix) {
            return List.of();
        }

        @Override
        public void deleteDir(String relativePath) {
        }

        @Override
        public ExecTarget.ServiceHandle startService(String command) {
            throw new UnsupportedOperationException();
        }
    }

    private static ExecResult result(int exit, String output) {
        return new ExecResult(exit, output, false, Duration.ofMillis(5));
    }

    private Task task(boolean withTests) {
        Task task = new Task();
        task.setTitle("server module");
        task.setInstructions("Build the server module.");
        task.setWriteSet(Set.of("server/pom.xml", "server/src/main/java/App.java"));
        task.setAcceptanceTestDir("server/src/test/java/swarm/accept");
        if (withTests) {
            task.setAuthoredTestPaths(List.of("server/src/test/java/swarm/accept/AppTest.java"));
        }
        return task;
    }

    private void contract() throws IOException {
        Files.createDirectories(project.resolve(".swarmcoder"));
        Files.writeString(project.resolve(".swarmcoder/verify.yaml"), """
            toolchain: maven
            compile:
              - mvn -o -B -q test-compile
            acceptance:
              - mvn -o -B -q test -Dtest=swarm/accept/** -Dsurefire.failIfNoSpecifiedTests=false
            existing:
              - mvn -o -B -q test
            """, StandardCharsets.UTF_8);
    }

    @Test
    void aPipelineIsRunSoThatAFailingStageFailsItAndTheResultSaysSo() throws Exception {
        Recording target = new Recording(command -> result(1, "[ERROR] cannot find symbol"));
        WorkerToolbox toolbox = new WorkerToolbox(checkout, task(false), null, null, target);

        String piped = toolbox.exec("mvn -o -B -q test-compile 2>&1 | tail -30");
        assertThat(target.commands.get(0))
            .startsWith("if (set -o pipefail) 2>/dev/null; then set -o pipefail;")
            .endsWith("\nmvn -o -B -q test-compile 2>&1 | tail -30");
        assertThat(piped).startsWith("exit=1" + PipelineFailure.NOTE + "\n");

        String plain = toolbox.exec("mvn -o -B -q test-compile || true");
        assertThat(target.commands.get(1)).isEqualTo("mvn -o -B -q test-compile || true");
        assertThat(plain).startsWith("exit=1\n");

        // And what the option does, in a real POSIX shell where there is one.
        String sh = System.getenv().containsKey("SHELL") ? "sh" : null;
        boolean runnable = false;
        if (sh != null) {
            try {
                runnable = new ProcessBuilder(sh, "-c", "exit 0").start().waitFor() == 0;
            } catch (IOException noShell) {
                runnable = false;
            }
        }
        assumeTrue(runnable, "no POSIX shell on this machine");
        Process before = new ProcessBuilder(sh, "-c", "false | cat").start();
        Process after = new ProcessBuilder(sh, "-c",
            PipelineFailure.wrap("false | cat", true)).start();
        assertThat(before.waitFor()).as("a pipeline's exit is its last stage's").isZero();
        String said = new String(after.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(after.waitFor() != 0 || said.contains(PipelineFailure.NO_PIPEFAIL))
            .as("with pipefail the failing stage fails it, or the shell says it has no such option")
            .isTrue();
    }

    @Test
    void verifyRunsTheContractsCompileAndTheTasksAcceptanceAndAnswersWithTheVerdict()
            throws Exception {
        contract();
        Recording failing = new Recording(command -> command.contains("test-compile")
            ? result(1, "[ERROR] /workspace/server/src/main/java/App.java:[3,1] error\n")
            : result(0, ""));
        WorkerToolbox toolbox = new WorkerToolbox(checkout, task(true), null, project, failing);

        List<String> names = toolbox.bindings().stream().map(ToolBinding::name).toList();
        assertThat(names).containsSubsequence("exec", "verify");

        String failed = toolbox.verify("");
        assertThat(failing.commands).contains("mvn -o -B -q test-compile");
        assertThat(failing.commands).noneMatch(command -> command.equals("mvn -o -B -q test"));
        assertThat(failed).startsWith(WorkerToolbox.VERIFY_FAILED)
            .contains("mvn -o -B -q test-compile").contains("Why: ");

        Recording passing = new Recording(command -> result(0, ""));
        WorkerToolbox green = new WorkerToolbox(checkout, task(true), null, project, passing);
        String passed = green.verify("whatever the model wrote");
        assertThat(passing.commands).contains("mvn -o -B -q test-compile",
            "mvn -o -B -q test -Dtest=swarm/accept/** -Dsurefire.failIfNoSpecifiedTests=false");
        assertThat(passing.commands).as("the existing tests are verification's, not the "
            + "worker's").doesNotContain("mvn -o -B -q test");
        assertThat(passed).startsWith(WorkerToolbox.VERIFY_PASSED);

        Recording noTests = new Recording(command -> result(0, ""));
        new WorkerToolbox(checkout, task(false), null, project, noTests).verify(null);
        assertThat(noTests.commands).containsExactly("mvn -o -B -q test-compile");

        assertThat(new WorkerToolbox(checkout, task(true), null, null, passing).bindings()
            .stream().map(ToolBinding::name)).as("no contract, no verify").doesNotContain(
                "verify");
    }
}
