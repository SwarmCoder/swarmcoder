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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker's shell command must never be able to kill a process it did not itself start
 * (2026-09-05, fixing two harness deaths: runs 21 and 29, where a worker's shell command killed
 * the surefire fork JVM running the whole harness — the swarm engine and every other worker with
 * it — and nothing recorded which command it had been).
 *
 * <p>Two shapes: an idiom that can never be validated against anything this worker started (a
 * process name, an image, a port, a listing pipeline) is refused outright; a narrow
 * {@code taskkill /PID n} / {@code kill n} / {@code Stop-Process -Id n} is refused only when
 * {@code n} is not a pid this worker's own {@code exec} started in the background (see
 * {@code LocalProcessExecTarget#startedBackgroundPid}).
 */
@ModelCodeOnThisPc
class AWorkerCommandCannotKillWhatItDidNotStartTest {

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

    @Test
    void unscopedKillIdiomsAreAlwaysRefused() throws IOException {
        WorkerToolbox toolbox = toolbox();

        for (String command : List.of(
                "taskkill /F /IM java.exe",
                "wmic process where \"name='java.exe'\" delete",
                "wmic process where \"name='java.exe'\" call terminate",
                "Get-Process java | Stop-Process -Force",
                "pkill -f ServerApp",
                "killall java",
                "fuser -k 8080/tcp",
                "for /f \"tokens=5\" %a in ('netstat -aon ^| findstr :8080') do taskkill /F /PID %a",
                "lsof -t -i:8080 | xargs kill -9")) {
            assertThat(toolbox.exec(command))
                .as("refused: " + command)
                .startsWith(WorkerToolbox.KILL_REFUSED);
        }
    }

    @Test
    void aBarePidNamedInTaskkillOrKillIsRefusedWhenThisWorkerNeverStartedIt() throws IOException {
        WorkerToolbox toolbox = toolbox();

        for (String command : List.of(
                "taskkill /F /PID 999999",
                "kill -9 999999",
                "Stop-Process -Id 999999 -Force")) {
            assertThat(toolbox.exec(command))
                .as("refused: " + command)
                .startsWith(WorkerToolbox.KILL_REFUSED);
        }
    }

    @Test
    void ordinaryCommandsAreUntouched() throws IOException {
        WorkerToolbox toolbox = toolbox();

        for (String command : List.of("mvn -o -q -B test", "npm test", "git log --oneline -5",
                "grep -rn kill src")) {
            assertThat(toolbox.exec(command))
                .as("allowed: " + command)
                .doesNotStartWith(WorkerToolbox.KILL_REFUSED);
        }
    }

    @Test
    void aRefusedKillCommandStillCountsAsALookSoItCannotBeUsedToBuyTurns() throws IOException {
        WorkerToolbox toolbox = toolbox();
        int before = toolbox.investigationToolCalls();

        toolbox.exec("pkill -f ServerApp");

        assertThat(toolbox.investigationToolCalls()).isEqualTo(before + 1);
    }

    /**
     * The narrow carve-out end to end: start a real background process through the exec tool (the
     * honest way to try a server, see {@code AWorkerCommandNeverWaitsOnAConsoleTest}), then kill
     * that EXACT pid by name — the one case {@code taskkill /PID} must be ALLOWED.
     *
     * <p>{@code /T} is included because the reported pid is the LAUNCHING SHELL (see
     * {@code LocalProcessExecTarget#startBackground}, which explains why Windows has no cheaper
     * way to get a real, stable pid); a plain {@code /PID} without {@code /T} kills only that shell
     * and leaves the actual command running under it, which is exactly the guidance the
     * background-start reply itself gives.
     *
     * <p>Windows-only: the pid tracking this carve-out reads is populated by
     * {@code LocalProcessExecTarget}'s Windows-specific background translation, because on Windows
     * a real OS process must be started for {@code taskkill /PID} to have anything genuine to name
     * — a trailing {@code &} already backgrounds correctly under a POSIX shell with no translation
     * needed, so there is nothing to prove there.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void taskkillOfAPidThisWorkerItselfStartedInTheBackgroundIsAllowed() throws IOException {
        WorkerToolbox toolbox = toolbox();

        String started = toolbox.exec("ping -n 30 127.0.0.1 &");
        long pid = extractPid(started);

        String result = toolbox.exec("taskkill /F /T /PID " + pid);

        assertThat(result)
            .as("a taskkill naming exactly the pid this worker's own exec started must be allowed: "
                + started)
            .doesNotStartWith(WorkerToolbox.KILL_REFUSED);

        // Windows can hold the background process's log-file handle open for a moment after
        // taskkill has already reported success; wait it out so @TempDir's own cleanup, which runs
        // right after this method returns, does not race it.
        boolean stillAlive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        for (int i = 0; stillAlive && i < 20; i++) {
            sleepBriefly();
            stillAlive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }
        String logName = extractLog(started);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (var channel = java.nio.channels.FileChannel.open(worktree.resolve(logName),
                    java.nio.file.StandardOpenOption.WRITE)) {
                break;
            } catch (IOException e) {
                sleepBriefly();
            }
        }
    }

    private static String extractLog(String execOutput) {
        Matcher m = Pattern.compile("log=(\\S+?)\\.").matcher(execOutput);
        if (!m.find()) {
            throw new IllegalStateException("No log= in: " + execOutput);
        }
        return m.group(1) + ".log";
    }

    private static long extractPid(String execOutput) {
        Matcher m = Pattern.compile("pid=(\\d+)").matcher(execOutput);
        if (!m.find()) {
            throw new IllegalStateException("No pid= in background-start output: " + execOutput);
        }
        return Long.parseLong(m.group(1));
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
