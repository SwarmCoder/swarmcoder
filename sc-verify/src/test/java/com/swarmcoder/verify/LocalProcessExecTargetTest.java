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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProcessExecTargetTest {

    @TempDir
    Path workspace;

    @Test
    void runsShellCommandAndCapturesOutput() throws IOException {
        LocalProcessExecTarget target = new LocalProcessExecTarget(workspace);

        ExecResult result = target.exec("echo hello-verifier", 30);

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.output()).contains("hello-verifier");
    }

    @Test
    void reportsNonZeroExitCode() throws IOException {
        LocalProcessExecTarget target = new LocalProcessExecTarget(workspace);

        ExecResult result = target.exec("exit 3", 30);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void listsReadsAndDeletesWorkspaceFiles() throws IOException {
        Files.createDirectories(workspace.resolve("build/test-results/test"));
        Files.writeString(workspace.resolve("build/test-results/test/r.xml"), "<testsuite/>");
        LocalProcessExecTarget target = new LocalProcessExecTarget(workspace);

        List<String> files = target.listFiles("build/test-results", ".xml");
        assertThat(files).containsExactly("build/test-results/test/r.xml");
        assertThat(target.readFile(files.get(0), 1024)).isEqualTo("<testsuite/>");

        target.deleteDir("build/test-results");
        assertThat(target.listFiles("build/test-results", ".xml")).isEmpty();
        assertThat(Files.exists(workspace.resolve("build/test-results"))).isFalse();
    }

    @Test
    void refusesPathsEscapingTheWorkspace() {
        LocalProcessExecTarget target = new LocalProcessExecTarget(workspace);

        assertThatThrownBy(() -> target.readFile("../outside.txt", 1024))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("escapes workspace");
    }

    /**
     * Standard input is never a console. A tool that reads its data from stdin — a pipeline stage,
     * a pager fed piped data, {@code read} in a script — normally blocks waiting for a human at a
     * keyboard; with stdin already redirected from an empty source, it sees EOF at once and
     * returns instead of hanging until the exec timeout kills it.
     *
     * <p>{@code sort} on Windows and {@code cat} on Unix are both ordinary stdin readers with no
     * filename argument, so both exercise exactly the mechanism this fix changes: the process's
     * standard-input HANDLE, which {@code closeStdin} now always points at {@code NUL}/
     * {@code /dev/null}.
     *
     * <p><b>{@code cmd /c "type con"} is deliberately NOT tested here.</b> Measured directly: with
     * stdin already redirected exactly as below, {@code type con} still blocked for the full
     * timeout. {@code type} does not read fd 0 for the special filename {@code CON} — it opens the
     * console device by name (Windows treats {@code CON}/{@code PRN}/{@code AUX} as reserved
     * device names resolved independently of any redirected standard handle), so no amount of
     * stdin redirection touches it, and neither does {@code pause}, which reads a keypress the same
     * way. Those two are stopped by {@code WorkerToolbox}'s pre-execution refusal instead (see
     * {@code CONSOLE_WAIT} there) — proven by
     * {@code AWorkerCommandNeverWaitsOnAConsoleTest} in sc-swarm, not here.
     */
    @Test
    void stdinIsNeverAConsoleSoAPlainStdinReaderReturnsImmediately() throws IOException {
        LocalProcessExecTarget target = new LocalProcessExecTarget(workspace);
        String command = isWindows() ? "sort" : "cat";

        Instant start = Instant.now();
        ExecResult result = target.exec(command, 30);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(result.timedOut())
            .as("reading an already-closed stdin must hit EOF at once, not the 30s timeout")
            .isFalse();
        assertThat(elapsed)
            .as("stuck reading a console would have taken the full 30s timeout to return")
            .isLessThan(Duration.ofSeconds(10));
    }

    /**
     * A timed-out command kills only its OWN process tree, never anything wider. Proven by
     * spawning a real descendant process that outlives the exec timeout — not the top-level shell
     * itself, so this actually exercises {@code Process.descendants()} rather than only
     * {@code Process.destroyForcibly()} on the one process ProcessBuilder started — then checking,
     * after the timeout fires, that the descendant is gone and that this test method (i.e. the
     * calling thread) simply continues, exactly as it does here.
     */
    @Test
    void aTimedOutCommandKillsOnlyItsOwnChildAndTheCallingThreadContinues() throws IOException {
        LocalProcessExecTarget target = new LocalProcessExecTarget(workspace);
        Path heartbeat = workspace.resolve("child-pid.txt");
        String command = childSpawningCommand(heartbeat);

        ExecResult result = target.exec(command, 5);

        assertThat(result.timedOut())
            .as("the 5s timeout must have fired")
            .isTrue();
        long childPid = readChildPid(heartbeat);
        boolean stillAlive = ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false);
        for (int i = 0; stillAlive && i < 20; i++) {
            sleepBriefly();
            stillAlive = ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false);
        }
        assertThat(stillAlive)
            .as("the descendant process (pid " + childPid + ") the timed-out command spawned must "
                + "be dead, not merely detached from the killed top-level process")
            .isFalse();
        // Reaching this assertion at all is the proof the calling thread was not itself blocked or
        // killed by the timeout it triggered.
        assertThat(target.exec("echo still-alive", 5).output()).contains("still-alive");
    }

    /**
     * A command whose CHILD process — not the top-level shell itself — outlives a short timeout.
     *
     * <p>On Windows the script is written to a {@code .ps1} file rather than passed inline on the
     * command line: an inline {@code -Command "..."} needs quoting through both Java's Windows
     * argv escaping and {@code cmd.exe}'s own second pass, which is exactly the kind of thing that
     * breaks in a way unrelated to what this test is proving. Writing the pid with
     * {@code [System.IO.File]::WriteAllText} rather than {@code Out-File} avoids Windows
     * PowerShell's default UTF-16 (BOM) text encoding, which would otherwise make the pid
     * unparseable.
     */
    private static String childSpawningCommand(Path heartbeatFile) throws IOException {
        String path = heartbeatFile.toAbsolutePath().toString();
        if (isWindows()) {
            Path script = heartbeatFile.resolveSibling("spawn-child.ps1");
            Files.writeString(script,
                "[System.IO.File]::WriteAllText('" + path.replace("'", "''") + "', [string]$PID)\n"
                + "Start-Sleep -Seconds 600\n");
            return "powershell -NoProfile -ExecutionPolicy Bypass -File \""
                + script.toAbsolutePath() + "\"";
        }
        // `&` forks a real child the shell then waits on, rather than exec-replacing itself into
        // the single simple command (which a tail-position `sh -c '...'` may otherwise optimize
        // into one process with no separate descendant to prove the kill walked the tree).
        return "sh -c 'echo $$ > " + path + "; sleep 600' & wait";
    }

    private static long readChildPid(Path heartbeatFile) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(heartbeatFile)) {
                String content = Files.readString(heartbeatFile).strip();
                if (!content.isEmpty()) {
                    return Long.parseLong(content);
                }
            }
            sleepBriefly();
        }
        throw new IllegalStateException("Child never recorded its pid in " + heartbeatFile);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ---- Windows backgrounding (2026-09-05: a worker's own advice must actually work there) ----

    /**
     * On {@code cmd.exe}, a trailing {@code &} is a command SEPARATOR, not a backgrounding
     * operator — {@code cmd /c "foo &"} just runs foo, then exits once foo does, so the call still
     * blocks for the whole exec timeout. This is the one place that translation happens: the
     * command actually starts detached, returns almost immediately with its own pid and a log
     * file, and the process really does keep running afterward.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aTrailingAmpersandCommandBecomesARealBackgroundLaunchOnWindows() throws IOException {
        LocalProcessExecTarget target = new LocalProcessExecTarget(workspace);

        Instant start = Instant.now();
        ExecResult result = target.exec("ping -n 30 127.0.0.1 &", 30);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed)
            .as("must return almost immediately, not wait out the ping")
            .isLessThan(Duration.ofSeconds(10));
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("pid=").contains("log=");
        long pid = extractPid(result.output());
        String logName = extractLog(result.output());

        assertThat(target.startedBackgroundPid(pid))
            .as("the pid this call reported must be one this target remembers starting")
            .isTrue();
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
            .as("the background process must genuinely still be running")
            .isTrue();
        boolean logAppeared = Files.exists(workspace.resolve(logName));
        for (int i = 0; !logAppeared && i < 20; i++) {
            sleepBriefly();
            logAppeared = Files.exists(workspace.resolve(logName));
        }
        assertThat(logAppeared)
            .as("the generated log file must exist in the workspace")
            .isTrue();

        target.stopBackgroundProcesses();

        boolean stillAlive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        for (int i = 0; stillAlive && i < 20; i++) {
            sleepBriefly();
            stillAlive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }
        assertThat(stillAlive)
            .as("stopBackgroundProcesses must actually stop what this worker started")
            .isFalse();
        assertThat(target.startedBackgroundPid(pid))
            .as("a stopped background pid is forgotten, not remembered forever")
            .isFalse();
        // Windows can hold the log file's handle open for a moment after the process that wrote
        // to it has reported itself dead; wait it out so @TempDir's own cleanup does not race it.
        waitForFileToBeReleased(workspace.resolve(logName));
    }

    /** A command that already names its own log file must not get a second, generated one. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aBackgroundCommandThatAlreadyNamesALogFileKeepsIt() throws IOException {
        LocalProcessExecTarget target = new LocalProcessExecTarget(workspace);

        ExecResult result = target.exec("ping -n 30 127.0.0.1 > my-own.log 2>&1 &", 30);

        assertThat(result.output()).contains("log=my-own.log");
        long pid = extractPid(result.output());
        target.stopBackgroundProcesses();

        boolean stillAlive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        for (int i = 0; stillAlive && i < 20; i++) {
            sleepBriefly();
            stillAlive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }
        assertThat(target.startedBackgroundPid(pid)).isFalse();
        waitForFileToBeReleased(workspace.resolve("my-own.log"));
    }

    /** Best-effort wait until Windows has actually released the file's write handle. */
    private static void waitForFileToBeReleased(Path file) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (var channel = java.nio.channels.FileChannel.open(file,
                    java.nio.file.StandardOpenOption.WRITE)) {
                return;
            } catch (IOException e) {
                sleepBriefly();
            }
        }
    }

    /**
     * The harness/app JVM's own pid (and its parent's) must never be a legitimate kill target —
     * this is the basis {@code killTree} and {@code stopBackgroundProcesses} both filter against.
     */
    @Test
    void hostPidsIncludeThisProcess() {
        assertThat(LocalProcessExecTarget.hostPidsForTest())
            .as("the current JVM must always be its own host pid")
            .contains(ProcessHandle.current().pid());
    }

    // ---- Git-Bash-spelled cd targets (harness run 41, 2026-09-26) ---------------------------

    /**
     * Reproduces the run 41 failure directly: a worker told (by a POSIX-emulating {@code pwd} on
     * its PATH — see {@link #exec}'s javadoc) that its checkout is {@code /c/Users/dev/...}
     * tries to {@code cd} there in that spelling. Before this fix, {@code cmd.exe} read the
     * leading {@code /} as the root of whatever drive it was already on and failed with "The
     * system cannot find the path specified." — exactly the message the run recorded.
     */
    @Test
    void cdToAGitBashSpelledPathIsRewrittenForCmdExe() {
        assertThat(LocalProcessExecTarget.translateGitBashCdTarget(
            "cd /c/Users/dev/.swarmcoder/wt/abc123 && mvn -o -pl bookshelf-demo-server test"))
            .isEqualTo("cd /d C:/Users/dev/.swarmcoder/wt/abc123 && mvn -o -pl "
                + "bookshelf-demo-server test");
    }

    @Test
    void cdAfterASemicolonSeparatorIsAlsoRewritten() {
        assertThat(LocalProcessExecTarget.translateGitBashCdTarget("echo hi; cd /d/repo; ls"))
            .isEqualTo("echo hi; cd /d D:/repo; ls");
    }

    @Test
    void aBareDriveRootCdGetsTheRootSlashNotJustTheDriveLetter() {
        // "cd /c" alone must become the ROOT of C:, never "cd /d C:" with nothing after — that
        // spelling tells cmd.exe to stay wherever drive C's remembered directory already was.
        assertThat(LocalProcessExecTarget.translateGitBashCdTarget("cd /c"))
            .isEqualTo("cd /d C:/");
    }

    @Test
    void aGitBashPathThatIsNotACdTargetIsLeftAlone() {
        // Only an actual `cd` is rewritten — a path elsewhere in the command (a file argument, an
        // echoed message) is not a directory change and must not be touched.
        String command = "cat /c/Users/dev/notes.txt";
        assertThat(LocalProcessExecTarget.translateGitBashCdTarget(command)).isEqualTo(command);
    }

    @Test
    void aCommandWithNoCdAtAllIsUnchanged() {
        String command = "mvn -o -pl bookshelf-demo-server test";
        assertThat(LocalProcessExecTarget.translateGitBashCdTarget(command)).isEqualTo(command);
    }

    @Test
    void anOrdinaryWindowsStyleCdIsUnchanged() {
        String command = "cd C:\\Users\\dev\\project && dir";
        assertThat(LocalProcessExecTarget.translateGitBashCdTarget(command)).isEqualTo(command);
    }

    /**
     * End to end: the exact command shape from run 41, actually run through {@code exec}, must now
     * succeed instead of failing with "The system cannot find the path specified." The workspace
     * itself stands in for the worker's checkout, so the translated {@code cd} lands somewhere
     * real and {@code cd} (no args) afterwards proves it actually moved.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aGitBashSpelledCdActuallyWorksOnWindowsNow() throws IOException {
        LocalProcessExecTarget target = new LocalProcessExecTarget(workspace);
        String drive = workspace.getRoot().toString().substring(0, 1).toLowerCase(java.util.Locale.ROOT);
        String posixPath = "/" + drive + workspace.toString().substring(2).replace('\\', '/');

        ExecResult result = target.exec("cd " + posixPath + " && cd", 30);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(workspace.toString());
    }

    private static long extractPid(String output) {
        Matcher m = Pattern.compile("pid=(\\d+)").matcher(output);
        if (!m.find()) {
            throw new IllegalStateException("No pid= in: " + output);
        }
        return Long.parseLong(m.group(1));
    }

    private static String extractLog(String output) {
        Matcher m = Pattern.compile("log=(\\S+?)\\.").matcher(output);
        if (!m.find()) {
            throw new IllegalStateException("No log= in: " + output);
        }
        return m.group(1) + ".log";
    }
}
