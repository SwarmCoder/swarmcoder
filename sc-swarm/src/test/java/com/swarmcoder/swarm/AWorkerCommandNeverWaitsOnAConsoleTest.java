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

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.ExecResult;
import com.swarmcoder.verify.ExecTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker command must never wait on a console, and a timed-out command must never take the run
 * with it (2026-09-05, fixing two harness incidents in one night).
 *
 * <p><b>Run 21.</b> A worker started the demo server as the foreground command of an {@code exec}
 * call ({@code java -cp ... com.swarmcoder.demo.bookshelf.server.ServerApp}), followed by
 * {@code sleep 25} and more commands that could never run because the shell never returned. The
 * call ran the full 300-second timeout, was killed, and the run itself never produced a result.
 *
 * <p><b>Run 22.</b> A repair worker piped {@code git log} through {@code more} and separately ran
 * {@code type con} — both genuinely wait for a keypress an automated exec tool will never send —
 * and burned five more minutes on the same timeout.
 *
 * <p>This class covers the two things {@code WorkerToolbox} itself is responsible for: refusing
 * the command BEFORE it ever reaches a process, and wording the reply when one times out anyway.
 * The stdin-closing half of the fix is proven separately in sc-verify's
 * {@code LocalProcessExecTargetTest} — see its
 * {@code stdinIsNeverAConsoleSoAPlainStdinReaderReturnsImmediately} and its javadoc explaining why
 * {@code type con} specifically survives stdin redirection and is caught here instead.
 */
@ModelCodeOnThisPc
class AWorkerCommandNeverWaitsOnAConsoleTest {

    @TempDir
    Path worktree;

    private static Task task() {
        return new Task(UUID.randomUUID(), 1L, "Fix a bug", "Do the thing.",
            Set.of("src/main/java/**"), Set.of(), List.of(), "src/test/java/swarm/accept",
            null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private WorkerToolbox toolbox() throws IOException {
        Files.createDirectories(worktree.resolve("src/main/java"));
        Files.writeString(worktree.resolve("src/main/java/App.java"), "class App { }\n");
        return new WorkerToolbox(worktree, task());
    }

    // ---- refused before it ever runs -------------------------------------------------------

    @Test
    void aCommandThatWouldWaitOnAConsoleIsRefusedBeforeItRuns() throws IOException {
        WorkerToolbox toolbox = toolbox();

        for (String command : List.of(
                "git log --all --oneline | more",
                "cmd /c \"type con\" 2>nul",
                "pause",
                "read -p \"press enter\" x",
                "history | less")) {
            assertThat(toolbox.exec(command))
                .as("refused: " + command)
                .startsWith(WorkerToolbox.CONSOLE_WAIT_REFUSED);
        }
    }

    @Test
    void anUnbackgroundedForegroundServerStartIsRefused() throws IOException {
        WorkerToolbox toolbox = toolbox();

        for (String command : List.of(
                "java -cp \"bookshelf-demo-server\\target\\classes;lib\\*\" "
                    + "com.swarmcoder.demo.bookshelf.server.ServerApp",
                "java -cp target/classes com.example.app.BookshelfApp",
                "mvn -o -q exec:java",
                "mvn spring-boot:run",
                "npm start")) {
            assertThat(toolbox.exec(command))
                .as("refused: " + command)
                .startsWith(WorkerToolbox.CONSOLE_WAIT_REFUSED);
        }
    }

    /**
     * The exact scenario from run 21: the command that started the server as written, verbatim.
     */
    @Test
    void theRun21CommandIsRefused() throws IOException {
        WorkerToolbox toolbox = toolbox();

        String result = toolbox.exec(
            "java -cp \"bookshelf-demo-server\\target\\classes;bookshelf-demo-server\\target\\dependency\\*\" "
                + "com.swarmcoder.demo.bookshelf.server.ServerApp");

        assertThat(result).startsWith(WorkerToolbox.CONSOLE_WAIT_REFUSED);
    }

    /** Starting a server in the background — the thing a worker is meant to do instead — is fine. */
    @Test
    void aBackgroundedServerStartIsNotRefused() throws IOException {
        WorkerToolbox toolbox = toolbox();

        for (String command : List.of(
                "java -cp target/classes com.example.app.BookshelfApp > server.log 2>&1 &",
                "nohup java -cp target/classes com.example.app.BookshelfApp > server.log &",
                "start /b java -cp target\\classes com.example.app.BookshelfApp")) {
            assertThat(toolbox.exec(command))
                .as("allowed: " + command)
                .doesNotStartWith(WorkerToolbox.CONSOLE_WAIT_REFUSED);
        }
    }

    @Test
    void ordinaryCommandsAreUntouched() throws IOException {
        WorkerToolbox toolbox = toolbox();

        for (String command : List.of("mvn -o -q -B test", "npm run build", "npm test",
                "grep -rn Book src", "git log --oneline -5", "ls target")) {
            assertThat(toolbox.exec(command))
                .as("allowed: " + command)
                .doesNotStartWith(WorkerToolbox.CONSOLE_WAIT_REFUSED);
        }
    }

    @Test
    void aRefusedConsoleWaitStillCountsAsALookSoItCannotBeUsedToBuyTurns() throws IOException {
        WorkerToolbox toolbox = toolbox();
        int before = toolbox.investigationToolCalls();

        toolbox.exec("pause");

        assertThat(toolbox.investigationToolCalls()).isEqualTo(before + 1);
    }

    // ---- a timed-out command's own reply --------------------------------------------------

    /**
     * Wording the worker actually sees. A fake {@link ExecTarget} stands in for the real process:
     * this class's job is the WORDING of {@code exec}'s reply, not re-proving that the process
     * layer honours a timeout (sc-verify's {@code LocalProcessExecTargetTest} does that against a
     * real killed process tree).
     */
    @Test
    void aTimedOutCommandsReplySaysHowLongItRanAndHowToTryAServerInstead() throws IOException {
        Files.createDirectories(worktree.resolve("src/main/java"));
        Files.writeString(worktree.resolve("src/main/java/App.java"), "class App { }\n");
        ExecTarget fakeTimeout = new ExecTarget() {
            @Override
            public ExecResult exec(String command, int timeoutSeconds) {
                return new ExecResult(-1, "partial output before the kill", true,
                    Duration.ofSeconds(300));
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
            public ServiceHandle startService(String command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Path> localRoot() {
                return Optional.of(worktree);
            }
        };
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task(), null, null, fakeTimeout);

        String result = toolbox.exec("java -jar something-that-hangs.jar");

        assertThat(result)
            .as("the exit line keeps saying (timed out) so the existing fruitless-call tracking "
                + "still recognizes a timeout as uninformative")
            .contains("exit=-1 (timed out)")
            .contains("Your command ran for 300 seconds and was stopped.")
            .contains("A command must finish on its own; do not start servers in the foreground "
                + "or pipe to a pager.")
            .contains("To try a server, start it in the background with its output to a file and "
                + "stop it before you finish.")
            .contains("partial output before the kill");
    }
}
