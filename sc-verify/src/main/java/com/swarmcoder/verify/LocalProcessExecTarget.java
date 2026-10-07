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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Optional;

/**
 * Runs verification commands as local processes in a workspace directory (a candidate's git
 * worktree). Commands go through the platform shell — {@code cmd.exe /c} on Windows,
 * {@code sh -c} elsewhere — because {@code verify.yaml} commands are written as shell strings.
 *
 * <p>This target exists so verification works before the Docker sandbox path is functional;
 * the spec-compliant target is {@link ActionServerExecTarget}.
 */
public final class LocalProcessExecTarget implements ExecTarget {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessExecTarget.class);
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 256 * 1024;
    /** How much of a command is logged and recorded — enough to identify it, not the whole thing. */
    private static final int MAX_LOGGED_COMMAND_CHARS = 300;

    private final Path workspaceRoot;
    /** Worker/task label for the exec-start log line, set once by the caller; "" if unknown. */
    private volatile String logContext = "";
    /** Windows background launches this target started (see {@link #startBackground}), by pid. */
    private final Set<Long> backgroundPids = ConcurrentHashMap.newKeySet();
    private final AtomicInteger backgroundLogCounter = new AtomicInteger();

    public LocalProcessExecTarget(Path workspaceRoot) {
        if (!Files.isDirectory(workspaceRoot)) {
            throw new IllegalArgumentException("Workspace root is not a directory: " + workspaceRoot);
        }
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Names the worker/task this target's commands belong to, for the exec-start/exit log lines.
     * Set once by the caller (e.g. {@code WorkerToolbox}) right after construction; every log line
     * before this is called just omits the context, which is exactly as informative as before this
     * existed.
     */
    public void setLogContext(String context) {
        this.logContext = context == null ? "" : context;
    }

    @Override
    public Optional<Path> localRoot() {
        return Optional.of(workspaceRoot);
    }

    @Override
    public ExecResult exec(String command, int timeoutSeconds) throws IOException {
        // Harness run 41 (2026-09-26): a worker ran `cd /c/Users/dev/.swarmcoder/wt/<id> && mvn
        // ...` repeatedly and every call failed with "exit=1 The system cannot find the path
        // specified" — a cmd.exe message — until it was killed for NO_PROGRESS. Every exec on
        // Windows has always gone through cmd.exe, consistently (see the ProcessBuilder below);
        // the shell was never the part that varied. What varied is that some tool on this
        // worker's PATH (a Git-for-Windows coreutils build of `pwd`, most likely) is itself an
        // MSYS binary, and MSYS binaries translate the real Windows working directory back into
        // its POSIX-mount spelling regardless of which shell launched them — so a plain `pwd`
        // answered "/c/Users/dev/..." even though cmd.exe, not bash, ran it. The worker read
        // that as "I am in a POSIX shell" and spelled its next `cd` the POSIX way, which cmd.exe's
        // own `cd` does not accept: it treats a leading `/` as the root of the CURRENT drive, so
        // `cd /c/Users/...` looks for a folder literally named `c` under that root and fails.
        // See SwarmDispatcher.buildBundle for the primary fix (the worker is now told it never
        // needs to `cd` at all — every exec already starts in its checkout — and told plainly
        // that a POSIX-spelled path it sees is not an instruction to use POSIX syntax back). This
        // translation is the mechanical safety net for when a `cd` in that spelling reaches this
        // target anyway: the prompt is advice, never the only thing standing between a worker and
        // a wasted run (compare PathPolicy, which enforces the write set the prompt only narrates).
        String translated = isWindows() ? translateGitBashCdTarget(command) : command;
        // A worker following the toolbox's own advice ends its command in `&` (or `nohup ...`) to
        // try a server without blocking. On Linux that already backgrounds it — `sh -c "foo &"`
        // returns immediately. On Windows, `&` inside `cmd.exe /c` is a command SEPARATOR, not a
        // backgrounding operator: `cmd /c "foo &"` just runs foo and then exits, so the call still
        // blocks on foo until it finishes or the exec timeout kills it. This is the one place that
        // is fixed, so the advice is actually true on both platforms.
        if (isWindows() && wantsWindowsBackground(translated)) {
            return startBackground(translated);
        }
        Instant start = Instant.now();
        log.info("exec{}: cwd={} timeout={}s command={}", contextSuffix(), workspaceRoot,
            timeoutSeconds, truncateForLog(translated));
        ProcessBuilder pb = isWindows()
            ? new ProcessBuilder("cmd.exe", "/c", translated)
            : new ProcessBuilder("sh", "-c", translated);
        pb.directory(workspaceRoot.toFile());
        pb.redirectErrorStream(true);
        closeStdin(pb);
        nonInteractiveEnv(pb);

        Process process = pb.start();
        // Read output on its own thread: reading inline would block until EOF and defeat the
        // timeout on a hung process, while not reading at all risks pipe-buffer deadlock.
        // StringBuffer: appended by the reader thread, read here possibly after a join timeout.
        StringBuffer captured = new StringBuffer();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                readCapped(process.getInputStream(), captured);
            } catch (IOException e) {
                // Stream closed by process death/kill — whatever was captured stands.
            }
        });
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("Command timed out after {}s: {}", timeoutSeconds, translated);
                killTree(process);
            }
            reader.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            Duration duration = Duration.between(start, Instant.now());
            log.info("exec{} done: exit=-1 (interrupted) duration={}s", contextSuffix(), duration.toSeconds());
            return new ExecResult(-1, captured.toString(), true, duration);
        }
        Duration duration = Duration.between(start, Instant.now());
        if (!finished) {
            log.info("exec{} done: exit=-1 (timed out) duration={}s", contextSuffix(), duration.toSeconds());
            return new ExecResult(-1, captured.toString(), true, duration);
        }
        log.info("exec{} done: exit={} duration={}s", contextSuffix(), process.exitValue(), duration.toSeconds());
        return new ExecResult(process.exitValue(), captured.toString(), false, duration);
    }

    /** " [worker/task]" when a log context is set, else "" — appended to every exec log line. */
    private String contextSuffix() {
        return logContext.isEmpty() ? "" : " [" + logContext + "]";
    }

    private static String truncateForLog(String command) {
        if (command == null) {
            return "";
        }
        return command.length() > MAX_LOGGED_COMMAND_CHARS
            ? command.substring(0, MAX_LOGGED_COMMAND_CHARS) + "…" : command;
    }

    @Override
    public String readFile(String relativePath, int maxBytes) throws IOException {
        Path target = resolveInWorkspace(relativePath);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(target);
        int len = Math.min(bytes.length, maxBytes);
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    @Override
    public List<String> listFiles(String relativeDir, String suffix) throws IOException {
        Path dir = resolveInWorkspace(relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(suffix))
                .sorted()
                .forEach(p -> result.add(workspaceRoot.relativize(p).toString().replace('\\', '/')));
        }
        return result;
    }

    @Override
    public void deleteDir(String relativePath) throws IOException {
        Path dir = resolveInWorkspace(relativePath);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> toDelete = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : toDelete) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Override
    public ServiceHandle startService(String command) throws IOException {
        // Same translation as exec() (see its javadoc) — a service command comes from
        // verify.yaml, not a worker, but there is no reason for the two entry points into
        // cmd.exe to disagree about which path spelling they accept.
        String translated = isWindows() ? translateGitBashCdTarget(command) : command;
        ProcessBuilder pb = isWindows()
            ? new ProcessBuilder("cmd.exe", "/c", translated)
            : new ProcessBuilder("sh", "-c", translated);
        pb.directory(workspaceRoot.toFile());
        pb.redirectErrorStream(true);
        closeStdin(pb);
        nonInteractiveEnv(pb);
        Process process = pb.start();
        StringBuffer captured = new StringBuffer();
        Thread.ofVirtual().start(() -> {
            try {
                readCapped(process.getInputStream(), captured);
            } catch (IOException e) {
                // Service stopped — captured output stands.
            }
        });
        return new ServiceHandle() {
            @Override
            public boolean isAlive() {
                return process.isAlive();
            }

            @Override
            public String outputSoFar() {
                return captured.toString();
            }

            @Override
            public void close() {
                killTree(process);
            }
        };
    }

    /** Exposed for static-site serving and spec loading; only the local target has one. */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    private Path resolveInWorkspace(String relativePath) throws IOException {
        Path target = workspaceRoot.resolve(relativePath).normalize();
        if (!target.startsWith(workspaceRoot)) {
            throw new IOException("Path escapes workspace: " + relativePath);
        }
        // normalize() is lexical; a symbolic link inside the workspace that points out of it
        // passes the check above. The workspace is a tree model-written commands work in.
        com.swarmcoder.sandbox.ConfinedPath.requireInside(workspaceRoot, target, relativePath);
        return target;
    }

    /**
     * Reads process output while the process runs, capped so a log-spewing build cannot
     * exhaust heap. Reading concurrently with execution also prevents pipe-buffer deadlock.
     */
    private static void readCapped(InputStream in, StringBuffer sb) throws IOException {
        byte[] buf = new byte[8192];
        int read;
        boolean truncated = false;
        while ((read = in.read(buf)) != -1) {
            if (sb.length() < MAX_CAPTURED_OUTPUT_BYTES) {
                int keep = Math.min(read, MAX_CAPTURED_OUTPUT_BYTES - sb.length());
                sb.append(new String(buf, 0, keep, StandardCharsets.UTF_8));
                if (keep < read) {
                    truncated = true;
                }
            } else {
                truncated = true;
            }
        }
        if (truncated) {
            sb.append("\n[output truncated at ").append(MAX_CAPTURED_OUTPUT_BYTES).append(" bytes]");
        }
    }

    /**
     * Kills the process and its descendants (Gradle/Maven spawn daemons and forked JVMs).
     *
     * <p>{@link Process#descendants()} walks the OS process tree rooted at THIS process handle —
     * never the JVM's own tree — so a worker command that times out takes only what it started
     * (and whatever that forked) with it. The orchestrator JVM, its other worker threads, and any
     * unrelated process on the machine are untouched. Descendants are snapshotted and killed
     * before the process itself so nothing is orphaned out of the walk by killing the parent first.
     *
     * <p>{@link #hostPids()} is filtered out of both the descendant walk and the process itself as
     * belt-and-braces: nothing here should ever be able to select the harness's own JVM or its
     * launcher in the first place (this is a real, unrelated OS process tree), but a command that
     * somehow reparents itself under the host is not worth the risk of testing that assumption.
     * This does not, and cannot, stop an arbitrary {@code taskkill}/{@code kill} the worker runs
     * directly against a pid it read off the system — that is what the exec refusal in
     * {@code WorkerToolbox} is for.
     */
    private static void killTree(Process process) {
        Set<Long> hostPids = hostPids();
        process.descendants()
            .filter(ph -> !hostPids.contains(ph.pid()))
            .forEach(ProcessHandle::destroyForcibly);
        if (!hostPids.contains(process.pid())) {
            process.destroyForcibly();
        }
    }

    /**
     * The harness/app JVM's own pid and its parent's — never a legitimate target for anything this
     * class kills, see {@link #killTree} and {@link #stopBackgroundProcesses}. Also published to
     * every child process as {@code SWARMCODER_HOST_PIDS} (see {@link #nonInteractiveEnv}) so a
     * command that wants to check before acting can, though nothing in this codebase reads it back
     * — it is there for a future or operator-authored safeguard, not enforced here.
     */
    private static Set<Long> hostPids() {
        Set<Long> pids = new HashSet<>();
        ProcessHandle self = ProcessHandle.current();
        pids.add(self.pid());
        self.parent().ifPresent(p -> pids.add(p.pid()));
        return pids;
    }

    /** Test-only window onto {@link #hostPids()} — never touched by anything else. */
    static Set<Long> hostPidsForTest() {
        return hostPids();
    }

    /**
     * Redirects stdin from an empty source so a command can never block reading a console.
     *
     * <p>A pager ({@code more}, {@code less}), {@code type con}, a shell {@code read -p}, or a
     * server that blocks on {@code System.in} all normally wait for a human at a keyboard. With
     * stdin already at EOF, each of those returns immediately instead of hanging until the exec
     * timeout kills the whole command tree.
     */
    private static void closeStdin(ProcessBuilder pb) {
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")));
    }

    /**
     * Environment that tells well-behaved tools not to page, prompt or colorize for a human.
     * {@code CI=true} is the convention the widest range of build tools and CLIs already check;
     * the other three specifically defeat `git log | less`-style paging and a dumb-terminal
     * assumption that would otherwise make a tool wait for a keypress that will never come.
     */
    private static void nonInteractiveEnv(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.put("CI", "true");
        env.put("TERM", "dumb");
        env.put("PAGER", "cat");
        env.put("GIT_PAGER", "cat");
        env.put("SWARMCODER_HOST_PIDS", hostPids().stream()
            .map(String::valueOf).collect(Collectors.joining(",")));
    }

    /**
     * Public so a prompt built elsewhere (see {@code SwarmDispatcher.buildBundle}) can tell the
     * worker which shell its own {@code exec} tool actually runs — the one thing this class alone
     * knows for certain, since it is the class that chooses {@code cmd.exe} or {@code sh}. Kept as
     * a single source of truth rather than a second "os.name contains win" check growing up
     * elsewhere and drifting from this one.
     */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * A leading {@code cd} to a POSIX-spelled Windows drive path — {@code cd /c/Users/dev/...}
     * — rewritten to the form {@code cmd.exe}'s own {@code cd} accepts: {@code cd /d C:/Users/dev/...}.
     * Matches {@code cd} at the very start of the command or right after a {@code &&} / {@code ;}
     * separator, so a path that merely happens to appear elsewhere in the command (a file argument,
     * a URL, a log message being echoed) is never touched — only an actual attempt to change
     * directory is.
     *
     * <p>{@code /d} is included deliberately, not left as a plain {@code cd}: plain {@code cd}
     * changes the remembered directory of the NAMED drive without switching cmd.exe's current
     * drive, so {@code cd C:\foo} run while sitting on {@code G:} silently does nothing useful —
     * confirmed empirically against this machine's cmd.exe while diagnosing harness run 41. The
     * worktree and the harness JVM are not guaranteed to be on the same drive, so the flag that
     * always crosses drives is the only spelling that is never wrong.
     *
     * <p>See harness run 41 (2026-09-26) and the javadoc on {@link #exec} for why this exists at
     * all: a POSIX-emulating tool on the worker's PATH prints the workspace's real Windows path in
     * this spelling, and a worker that had just read that spelling used it back in its own next
     * {@code cd} — reasonably, since nothing had told it not to.
     */
    static String translateGitBashCdTarget(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        Matcher m = CD_GIT_BASH_PATH.matcher(command);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String drive = m.group(2).toUpperCase(Locale.ROOT);
            // A bare "cd /c" names the root of the drive — "/" in both spellings — never "C:"
            // alone, which cmd.exe's cd would read as "stay wherever C: last was".
            String rest = m.group(3) == null ? "/" : m.group(3);
            m.appendReplacement(out,
                Matcher.quoteReplacement(m.group(1) + "cd /d " + drive + ":" + rest));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Group 1: what came before {@code cd} (start of string, or a {@code &&}/{@code ;} separator,
     * captured so it is put back unchanged). Group 2: the drive letter. Group 3: everything after
     * the drive letter up to the next whitespace/separator, or null when the path is bare
     * ({@code cd /c}, the root of the drive).
     */
    private static final Pattern CD_GIT_BASH_PATH =
        Pattern.compile("(?i)(^|&&\\s*|;\\s*)cd\\s+/([A-Za-z])(/\\S*)?");

    // --- Windows backgrounding (spec: "a worker command may not kill what it did not start") ----

    /** A trailing single {@code &} (not {@code &&}), or a leading {@code nohup}. */
    private static final Pattern TRAILING_AMPERSAND = Pattern.compile("(?<!&)&\\s*$");
    private static final Pattern LEADING_NOHUP = Pattern.compile("(?i)^\\s*nohup\\s+");
    /** A command that already uses {@code start /b} itself backgrounds correctly as-is. */
    private static final Pattern ALREADY_START_B = Pattern.compile("(?i)\\bstart\\s+/b\\b");
    /** A redirect to a real file — excludes fd-duplication forms like {@code 2>&1} or {@code 1>&2}. */
    private static final Pattern FILE_REDIRECT = Pattern.compile(">{1,2}\\s*([^&\\s][^\\s]*)");

    private static boolean wantsWindowsBackground(String command) {
        if (command == null) {
            return false;
        }
        String stripped = command.strip();
        if (stripped.isEmpty() || ALREADY_START_B.matcher(stripped).find()) {
            return false;
        }
        return TRAILING_AMPERSAND.matcher(stripped).find() || LEADING_NOHUP.matcher(stripped).find();
    }

    /**
     * Runs a trailing-{@code &}/{@code nohup} command as a genuine Windows background launch
     * instead of letting {@code cmd.exe /c} run it to completion as if the {@code &} had never
     * been there — see {@link #wantsWindowsBackground}. Returns immediately with the pid and the
     * log file the worker can read later; the process itself is remembered so it can be stopped
     * when the worker ends (see {@link #stopBackgroundProcesses}) and so a later
     * {@code taskkill /PID} of this exact pid can be recognised as scoped to what this worker
     * actually started (see {@code WorkerToolbox}'s kill-command refusal).
     *
     * <p><b>No {@code start /b} needed.</b> That is how a human backgrounds a job inside an
     * interactive cmd.exe session; here Java already has direct control of the child process via
     * {@link Process}, and simply never calling {@link Process#waitFor()} on it IS backgrounding —
     * the OS process runs on regardless of what this JVM does next, and {@link Process#pid()} is
     * the real, stable pid to remember. Reaching for {@code start /b} on top would mean nesting a
     * second {@code cmd.exe /c "..."} inside the first, which reintroduces exactly the quoting
     * fragility the single-string invocation below (matching every other command in this class)
     * exists to avoid.
     */
    private ExecResult startBackground(String command) throws IOException {
        Instant start = Instant.now();
        String inner = stripBackgroundIdiom(command);
        String namedLog = namedLogFile(inner);
        String logRelName = namedLog != null ? namedLog
            : "swarm-bg-" + backgroundLogCounter.incrementAndGet() + ".log";
        String launch = namedLog != null ? inner : inner + " > \"" + logRelName + "\" 2>&1";

        log.info("exec{}: cwd={} command={} started in background, log={}", contextSuffix(),
            workspaceRoot, truncateForLog(inner), logRelName);

        ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", launch);
        pb.directory(workspaceRoot.toFile());
        // The shell command itself redirects its output to the log file above; nothing is left
        // for cmd.exe's own inherited handles to carry, so these are simply never read.
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        closeStdin(pb);
        nonInteractiveEnv(pb);
        Process bg = pb.start(); // deliberately never waitFor()'d — that IS the backgrounding
        long pid = bg.pid();
        backgroundPids.add(pid);

        String output = "Started in background: pid=" + pid + ", log=" + logRelName + ". "
            + "It keeps running after this call returns and is stopped automatically when you "
            + "finish; to stop it yourself first, use taskkill /F /T /PID " + pid + " — the /T "
            + "matters, since this pid is the launching shell and a plain /PID without it leaves "
            + "the actual command running under it.";
        return new ExecResult(0, output, false, Duration.between(start, Instant.now()));
    }

    private static String stripBackgroundIdiom(String command) {
        String s = command.strip();
        s = TRAILING_AMPERSAND.matcher(s).replaceFirst("").strip();
        s = LEADING_NOHUP.matcher(s).replaceFirst("").strip();
        return s;
    }

    /** The last file a command already redirects to, or null if it names none. */
    private static String namedLogFile(String command) {
        Matcher m = FILE_REDIRECT.matcher(command);
        String last = null;
        while (m.find()) {
            String token = m.group(1);
            if (token != null && !token.isBlank()) {
                last = token;
            }
        }
        return last;
    }

    /** True when {@code pid} is a background process THIS target itself started via {@code &}/nohup. */
    public boolean startedBackgroundPid(long pid) {
        return backgroundPids.contains(pid);
    }

    /**
     * Stops every background process this target started and that the worker never stopped
     * itself — called when the worker's session ends. Never touches {@link #hostPids()}, the same
     * guard {@link #killTree} applies, even though a pid this method tracks can, by construction,
     * never be one of them.
     */
    public void stopBackgroundProcesses() {
        if (backgroundPids.isEmpty()) {
            return;
        }
        Set<Long> hostPids = hostPids();
        for (Long pid : backgroundPids) {
            if (hostPids.contains(pid)) {
                continue;
            }
            ProcessHandle.of(pid).ifPresent(handle -> {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                handle.destroyForcibly();
            });
        }
        backgroundPids.clear();
    }
}
