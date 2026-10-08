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

import com.swarmcoder.domain.RuleDispute;
import com.swarmcoder.domain.Task;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.sandbox.ConfinedPath;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.verify.ExecResult;
import com.swarmcoder.verify.ExecTarget;
import com.swarmcoder.verify.LocalProcessExecTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.swarmcoder.runtime.ApiLookup;
import com.swarmcoder.runtime.PathPolicy;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The worker's tools (spec §11.2: exec, read, apply_diff, report_done), operating on the
 * candidate's own worktree.
 *
 * <p>Path enforcement is mechanical, not prompt-based (spec §18), and since 2026-09-02 it has two
 * settings rather than one — see {@link PathPolicy.Verdict}:
 *
 * <ul>
 *   <li>A write into a PROTECTED place — outside the repository, {@code .git/},
 *       {@code .swarmcoder/}, an operator-locked module, the acceptance tests — is refused and
 *       counted. Two of those still kill the worker.</li>
 *   <li>A write merely OUTSIDE THE TASK'S WRITE SET happens, and the path is recorded on the
 *       candidate for the judge, selection and the operator to weigh. It never kills anything.
 *       The write set is a guess made from a plan before any code existed; killing a worker over
 *       it at turn 10 threw away both the work and the evidence of what it had done.</li>
 * </ul>
 */
public final class WorkerToolbox {

    private static final Logger log = LoggerFactory.getLogger(WorkerToolbox.class);
    private static final int MAX_TOOL_OUTPUT_CHARS = 8000;
    /** This worker's room; scales only the {@code lookup_api} cut. See {@link #setMaterialBudget}. */
    private volatile MaterialBudget room = MaterialBudget.BASELINE;
    private static final int MAX_READ_BYTES = 64 * 1024;
    private static final int EXEC_TIMEOUT_SECONDS = 300;
    /** How many distinct looking-only results are remembered for the repeat check. */
    private static final int MAX_REMEMBERED_RESULTS = 300;
    /**
     * How many DIFFERENT questions {@code lookup_api} may answer with the same leading section
     * before the worker — and the run's transcript — are told the documentation search is not
     * working. Three, because two can be a genuinely overlapping pair of questions and three
     * cannot.
     */
    private static final int SAME_LOOKUP_ANSWERS_BEFORE_WARNING = 3;

    /**
     * What {@code read}, {@code exec} and {@code lookup_api} return once reading is paused (§32
     * extended), byte-identical every time — the run that led to this rule was two workers who
     * ignored the second nudge outright and kept reading until they were killed at the twenty-four
     * call backstop with nothing written; the nudge alone did not change what happened next. A
     * paused call is never fed through {@link #countFruitfulness}, so this constant text does not
     * itself trip the separate "six fruitless calls in a row" guard.
     */
    static final String READ_PAUSE_TEXT =
        "Reading is paused. You have read 16 things and written nothing. Write your best attempt "
        + "now (replace_member or add_member for existing Java, write_file for a new file) — a "
        + "wrong file the compiler can correct is worth more "
        + "than another page read. Reading resumes after your first write.\n"
        + "If you are stuck on an API, call ask_expert with the exact question instead of reading "
        + "further; if you do not know how to start a file, call request_skeleton with the type "
        + "name. Both still work while reading is paused, and so does a build or test command.";

    /**
     * A shell command that runs a build or its tests — let through the read-pause even though it
     * only looks, because a worker that has stopped writing still needs the compiler's own verdict
     * on what it already wrote, and refusing that would make recovering from the pause harder
     * rather than easier.
     */
    private static final Pattern BUILD_OR_TEST_COMMAND =
        Pattern.compile("(?i)\\b(mvnw?|gradlew?|javac)\\b");

    private final Path worktree;
    private final Task task;
    /**
     * Where the model's {@code exec} tool runs its (untrusted) shell commands — the in-sandbox
     * action server when a Docker sandbox is enabled, else a local process. Isolating this one
     * tool is the whole point of worker-exec-in-sandbox: arbitrary model-driven builds/commands
     * run in the container, never on the workstation.
     */
    private final ExecTarget commandTarget;
    /**
     * Local process rooted at the worktree, used for the TRUSTED mechanical file operations
     * ({@code read}, {@code apply_diff}'s {@code git apply}). These act on the real worktree
     * directory — which is bind-mounted into the sandbox at {@code /workspace}, so a host-side
     * mutation here is immediately visible to the sandboxed {@code exec} commands.
     */
    private final LocalProcessExecTarget localTarget;
    private final ApiLookup apiLookup;
    /**
     * Refusals that MUST stop the worker — escapes, {@code .git/}, {@code .swarmcoder/}, an
     * operator-locked module, the acceptance tests. This is the count the early-kill guard reads;
     * a plain write outside the task's slice no longer contributes to it. See {@link PathPolicy}.
     */
    private final AtomicInteger blockingViolations = new AtomicInteger();
    /**
     * Paths this worker changed outside its declared write set, in the order it reached them.
     *
     * <p>Recorded rather than refused. A set, because a worker editing one neighbouring file six
     * times has done one thing out of bounds, not six, and the number the judge and the operator
     * read must mean "how much of the repository did this touch".
     */
    private final java.util.Set<String> outOfWriteSet =
        java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    private final AtomicInteger applyFailures = new AtomicInteger();
    /**
     * Files whose {@code apply_diff} has already failed once, so the steer toward
     * {@code write_file} fires only on the FIRST rejection for a given file — a worker that reads
     * the hint and still fails again is not told the identical sentence forever.
     */
    private final java.util.Set<String> diffRejectedOnceFor =
        java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /**
     * The reverse-engineering-spiral guard (§32). Tool calls that only LOOK — exec, read,
     * lookup_api — are counted; a successful write resets the count. At the threshold the
     * worker is told, once, what reference material it has and to start writing. Not a kill:
     * reading before writing is correct, and an undocumented dependency genuinely can need
     * reverse-engineering. The failure being bounded is never stopping.
     */
    private final AtomicInteger investigationCalls = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean hasWritten =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final EarlyKillEnforcer spiralPolicy = new EarlyKillEnforcer();
    /**
     * Consecutive looking-only tool calls that told this worker nothing it did not already have —
     * an empty or timed-out command, a file that is not there, or an answer byte-identical to one
     * it has already been given. Any informative result, and any write, resets it to zero.
     *
     * <p>This is the guessing signature, and it is what six near-identical {@code javap … | grep}
     * calls in a row look like from outside. It is deliberately about the RESULT rather than the
     * command: an allowlist of binaries cannot be written, but "that returned nothing, again" is
     * mechanical and cannot be talked around.
     */
    private final AtomicInteger fruitlessInARow = new AtomicInteger();
    /**
     * Digests of looking-only results this worker has already been shown, so a repeat can be told
     * from a first sighting. Bounded — after {@link #MAX_REMEMBERED_RESULTS} distinct results it
     * stops growing, which only ever makes the guard more forgiving.
     */
    private final java.util.Set<String> seenResultDigests =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * The leading digest of the answer {@code lookup_api} gave for each distinct query. When one
     * digest answers several different questions the documentation search is not discriminating,
     * and the worker is about to do the rational thing and go to the jars instead.
     */
    private final java.util.Map<String, String> lookupAnswerByQuery =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean lookupWarningGiven =
        new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * Every documentation section (by "address › heading", the same identity {@code ####} lines
     * carry) this worker has already been shown by {@code lookup_api} this task — every one,
     * not only the leading section of each answer, since a slice can carry more than one. Passed
     * back to the lookup source on the NEXT call so it can skip these instead of handing back the
     * same page a second or third time (§32 extended, 2026-09-04: the warning fired and nothing
     * changed for the worker, who read the identical section a third time anyway).
     */
    private final java.util.Set<String> givenLookupSections =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Every word (length three or more, lower-cased) this worker has used in an earlier
     * {@code lookup_api} question this task — how {@link #newLookupTerms} tells a refined
     * question's ADDED words from ones it already asked about.
     */
    private final java.util.Set<String> lookupQueryWordsSeen =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Every {@code lookup_api} question this worker has asked, in order, with the top
     * documentation section its answer led with — the evidence behind
     * {@link #documentationLookupIsNotDiscriminating()}. Bounded so a long run cannot grow this
     * without limit; the last {@link #MAX_LOOKUP_HISTORY} are what a kill line or a diagnosis
     * needs, not the whole run.
     */
    private final List<LookupQuestion> lookupHistory = new ArrayList<>();
    /** How many past questions {@link #lookupHistory()} keeps — enough to show the pattern. */
    private static final int MAX_LOOKUP_HISTORY = 12;
    /** A question longer than this is truncated before it is remembered or shown. */
    private static final int MAX_LOOKUP_QUESTION_CHARS = 100;
    /** The heading line an answer's leading section is rendered as — see {@code KnowledgeCurator}. */
    private static final Pattern SECTION_LINE = Pattern.compile("(?m)^####\\s+(.+)$");

    /**
     * One question this worker asked {@code lookup_api}, and the top documentation section the
     * answer led with (path › heading, or "" when nothing matched). A small immutable record
     * rather than text to parse later — the kill line and the candidate's diagnosis both read it
     * directly instead of re-deriving it from a log line.
     */
    public record LookupQuestion(String question, String section) {}
    /** Steering text emitted but not yet reported to the trace — drained by the TurnGuard. */
    private final java.util.concurrent.atomic.AtomicReference<String> pendingSteer =
        new java.util.concurrent.atomic.AtomicReference<>();
    /** What the worker may read instead of guessing; named in the steer. Never null. */
    private volatile String referenceHint = "";
    private volatile ExpertHelp expert = ExpertHelp.UNAVAILABLE;
    private volatile List<String> frameworkPackages = List.of();
    /** Reference folders by label, readable as {@code /reference/<label>/...}. Never null. */
    private volatile Map<String, Path> referenceRoots = Map.of();
    private final List<ExpertHelp.Answer> helpCalls = new ArrayList<>();
    /** Every rule this worker disputed with evidence, in order — see {@link #disputeRule}. */
    private final List<RuleDispute> ruleDisputes = new ArrayList<>();

    /**
     * How many rules one worker may dispute. A worker that disputes more than this is not meeting
     * a library that does not fit its rules, it is arguing with the brief.
     */
    static final int MAX_RULE_DISPUTES = 3;

    /** How much of a file named as evidence is attached to the dispute. */
    static final int EVIDENCE_FILE_CHARS = 1_500;

    /** The project's real root, for mapping model-supplied absolute paths back to relative. */
    private final Path projectRoot;
    /**
     * Operator-declared locked-down paths — modules a worker may never modify whatever its write
     * set says (config {@code protectedPaths}). Enforced here AND re-checked at integration,
     * because a shell can write files no tool ever saw.
     */
    private final List<String> protectedPaths;

    public WorkerToolbox(Path worktree, Task task) {
        this(worktree, task, ApiLookup.UNAVAILABLE, null);
    }

    public WorkerToolbox(Path worktree, Task task, ApiLookup apiLookup) {
        this(worktree, task, apiLookup, null);
    }

    public WorkerToolbox(Path worktree, Task task, ApiLookup apiLookup,
                         Path projectRoot) {
        this(worktree, task, apiLookup, projectRoot, null);
    }

    /**
     * @param commandTarget where the model's {@code exec} tool runs: the candidate's container.
     *                      Null means this PC, and {@code exec} then refuses every command unless
     *                      {@code HostExecution} was switched on by name (the operator's
     *                      {@code sandbox.enabled: false}, or a scripted test).
     */
    public WorkerToolbox(Path worktree, Task task, ApiLookup apiLookup,
                         Path projectRoot, ExecTarget commandTarget) {
        this(worktree, task, apiLookup, projectRoot, commandTarget, List.of());
    }

    /**
     * @param protectedPaths operator-declared paths no worker may modify, regardless of write set
     */
    public WorkerToolbox(Path worktree, Task task, ApiLookup apiLookup,
                         Path projectRoot, ExecTarget commandTarget, List<String> protectedPaths) {
        this.worktree = worktree;
        this.task = task;
        this.localTarget = new LocalProcessExecTarget(worktree);
        this.commandTarget = commandTarget == null ? this.localTarget : commandTarget;
        this.apiLookup = apiLookup == null ? ApiLookup.UNAVAILABLE : apiLookup;
        this.projectRoot = projectRoot;
        this.protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
    }

    /**
     * Maps a model-supplied path to a repository-relative one. Small models routinely emit
     * the ABSOLUTE project path they see in their context (e.g. {@code C:/work/app/pom.xml})
     * even though they operate in an isolated worktree — that path would then "escape" the
     * worktree and fail every write. Strip a leading worktree or project-root prefix; leave
     * already-relative paths untouched.
     */
    String toRelative(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        // The checkout's name inside the container, which is the name a worker's own commands
        // print (`pwd`, a compiler error). Mapped whether or not this worker has a container: the
        // path names nothing else.
        if (normalized.equals(CONTAINER_CHECKOUT) || normalized.equals(CONTAINER_CHECKOUT + "/")) {
            return "";
        }
        if (normalized.startsWith(CONTAINER_CHECKOUT + "/")) {
            return normalized.substring(CONTAINER_CHECKOUT.length() + 1);
        }
        // Drop a leading worktree or project-root absolute prefix.
        for (Path root : new Path[]{worktree, projectRoot}) {
            if (root == null) {
                continue;
            }
            String prefix = root.toAbsolutePath().normalize().toString().replace('\\', '/');
            if (!prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
            if (normalized.equalsIgnoreCase(prefix.substring(0, prefix.length() - 1))) {
                return "";
            }
            if (normalized.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return normalized.substring(prefix.length());
            }
        }
        // An absolute path that is neither the checkout nor the project: REFUSED (null), not
        // re-rooted. It used to be stripped of its drive or leading slash and resolved inside the
        // worktree, which kept it from escaping but answered a question nobody asked - `read
        // C:/Windows/win.ini` came back as "file not found: Windows/win.ini", and a write to
        // /etc/hosts quietly created etc/hosts in the checkout. The caller says what is allowed.
        if (normalized.startsWith("/") || normalized.startsWith("~")
                || normalized.matches("^[A-Za-z]:.*")) {
            return null;
        }
        return normalized;
    }

    /** The checkout as a worker's container names it; see {@link DockerSandboxManager}. */
    static final String CONTAINER_CHECKOUT = "/workspace";

    /** What a worker is told when it names a path outside everything it may touch. */
    private String outsideRefusal(String path) {
        StringBuilder sb = new StringBuilder("Refused: `").append(path == null ? "" : path.strip())
            .append("` is not inside your checkout. Use a path relative to the root of your "
                + "checkout, for example `pom.xml` or `src/main/java/App.java`");
        if (!referenceRoots.isEmpty()) {
            sb.append("; the reference material can be READ as ")
              .append(referenceRoots.keySet().stream().sorted()
                  .map(label -> DockerSandboxManager.referenceMountPath(label) + "/<path>")
                  .collect(java.util.stream.Collectors.joining(", ")));
        }
        return sb.append(". Nothing else on this machine is reachable.").toString();
    }

    /**
     * Where a path given to {@code read} leads: a file in the checkout, or one in a reference
     * folder addressed the way the container mounts it. Anything else is refused - an absolute
     * path, a drive letter, {@code ..} out of the root, a symbolic link out of it. {@code read}
     * runs in the orchestrator's own process, so this check is all that stands between a model's
     * path and the workstation's disk, whatever its shell is confined to.
     */
    ConfinedPath.Result resolveForRead(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').trim();
        String referencePrefix = DockerSandboxManager.REFERENCE_MOUNT + "/";
        if (normalized.startsWith(referencePrefix)) {
            for (Map.Entry<String, Path> root : referenceRoots.entrySet()) {
                String mount = DockerSandboxManager.referenceMountPath(root.getKey());
                if (normalized.equals(mount) || normalized.startsWith(mount + "/")) {
                    String rest = normalized.length() <= mount.length() + 1 ? ""
                        : normalized.substring(mount.length() + 1);
                    return ConfinedPath.resolve(root.getValue(), rest,
                        "the reference folder " + mount);
                }
            }
            return new ConfinedPath.Result(null, outsideRefusal(path));
        }
        String rel = toRelative(path);
        if (rel == null) {
            return new ConfinedPath.Result(null, outsideRefusal(path));
        }
        return ConfinedPath.resolve(worktree, rel, "your checkout");
    }

    /**
     * Refusals that count toward the kill. Renamed from {@code writeSetViolations()} on
     * 2026-09-02, when writing outside the write set stopped being one of them — the name now
     * says what the number is for, so nothing feeds a merely out-of-slice write into a kill again.
     */
    public int blockingViolations() {
        return blockingViolations.get();
    }

    /** Files this worker changed outside its write set — recorded, never fatal. Never null. */
    public List<String> outOfWriteSetPaths() {
        synchronized (outOfWriteSet) {
            return List.copyOf(outOfWriteSet);
        }
    }

    /** Tool calls spent looking rather than changing anything since the last successful write. */
    public int investigationToolCalls() {
        return investigationCalls.get();
    }

    /** True once this worker has actually changed a file. */
    public boolean hasWritten() {
        return hasWritten.get();
    }

    /**
     * True once this worker has read/investigated for at least the second nudge's threshold (16)
     * of tool calls since its last write, without writing — the point from which {@code read},
     * {@code exec} (other than a build/test command) and {@code lookup_api} stop doing their real
     * work and return {@link #READ_PAUSE_TEXT} instead. Public because the operator-facing
     * diagnosis for a kill that follows a pause needs to say so; see {@link EarlyKillEnforcer}.
     */
    public boolean readingPaused() {
        return investigationCalls.get() >= EarlyKillEnforcer.INVESTIGATION_TOOL_CALLS_BEFORE_SECOND_NUDGE;
    }

    /**
     * Looking-only tool calls, one after another, that returned nothing this worker did not
     * already have. Reset by any informative result and by any write.
     */
    public int fruitlessCallsInARow() {
        return fruitlessInARow.get();
    }

    /**
     * True when the documentation search has answered several different questions with the same
     * section — the signal that a worker reaching for {@code javap} is doing the only thing left
     * to it rather than ignoring its instructions.
     */
    public boolean documentationLookupIsNotDiscriminating() {
        return lookupWarningGiven.get();
    }

    /**
     * Every {@code lookup_api} question this worker has asked, in order, each with the top
     * section its answer led with. Capped at the last {@link #MAX_LOOKUP_HISTORY}; never null.
     */
    public List<LookupQuestion> lookupHistory() {
        synchronized (lookupHistory) {
            return List.copyOf(lookupHistory);
        }
    }

    /**
     * A one-line summary of the reference material this worker has, shown to it when it has
     * been investigating too long. Set by the caller from the knowledge brief; blank when the
     * project genuinely has none, in which case the steer says exactly that instead.
     */
    public void setReferenceHint(String referenceHint) {
        this.referenceHint = referenceHint == null ? "" : referenceHint.strip();
    }

    /**
     * Where a stuck worker's questions go. Defaults to "nothing configured", which answers by
     * saying so rather than by pretending.
     */
    public void setExpert(ExpertHelp expert) {
        this.expert = expert == null ? ExpertHelp.UNAVAILABLE : expert;
    }

    /**
     * The package prefixes of this project's reference material — {@code com.zeroz4j},
     * {@code com.ledgerworks}. A compile error naming a symbol under one of these is a FRAMEWORK
     * error, not a typo in the worker's own code, and is answered with a different instruction.
     */
    /**
     * The read-only reference folders, by the label the knowledge brief addresses them with. The
     * same folders a container mounts at {@code /reference/<label>}; {@code read} takes that
     * address so the file tool and the shell agree about where a document is.
     */
    public void setReferenceRoots(Map<String, Path> roots) {
        this.referenceRoots = roots == null ? Map.of() : Map.copyOf(roots);
    }

    public void setFrameworkPackages(List<String> prefixes) {
        this.frameworkPackages = prefixes == null ? List.of() : List.copyOf(prefixes);
    }

    /**
     * This worker's own working room, which sizes how much of a {@code lookup_api} answer it is
     * shown (2026-09-25; see {@code MaterialBudget}).
     *
     * <p>The Librarian now sizes a lookup answer for the workers' room — up to 30,720 characters in
     * the DeepSeek workers' 262,144 tokens — and this toolbox cut every tool result at
     * {@link #MAX_TOOL_OUTPUT_CHARS}, so without this the code half of a scaled answer would have
     * been cut off on its way to the worker that asked for it. Only {@code lookup_api} is scaled
     * here: it is the one tool result that is reference material with a budget of its own.
     * {@code read} and {@code exec} keep the fixed 8,000; whether a bigger room should see more of
     * a file or of a build log is a separate question with its own costs (the repeat check and the
     * read-pause both count what a look returned). Never called means the baseline, which is 8,000
     * for every tool, exactly as before.
     */
    public void setMaterialBudget(MaterialBudget room) {
        this.room = room == null ? MaterialBudget.BASELINE : room;
    }

    /**
     * Names this worker/task on every exec-start/exit log line from the LOCAL exec target — see
     * {@link LocalProcessExecTarget#setLogContext}. Set on {@link #localTarget} because that is
     * the one always present regardless of sandboxing; when {@code commandTarget} is a Docker
     * sandbox, its own log context is set separately where the sandbox is attached.
     */
    public void setExecLogContext(String context) {
        localTarget.setLogContext(context);
    }

    /**
     * Stops every background process this worker started via the exec tool's trailing-{@code &}
     * translation (see {@link LocalProcessExecTarget#startBackground}) and never stopped itself.
     * Called once when the worker's session ends, so a server it tried never outlives it.
     */
    public void stopBackgroundProcesses() {
        localTarget.stopBackgroundProcesses();
    }

    /** Every help call this worker made, in order — for the candidate's record and the judge. */
    public List<ExpertHelp.Answer> helpCalls() {
        synchronized (helpCalls) {
            return List.copyOf(helpCalls);
        }
    }

    /** Every rule this worker disputed, in order — for the candidate's record and the judge. */
    public List<RuleDispute> ruleDisputes() {
        synchronized (ruleDisputes) {
            return List.copyOf(ruleDisputes);
        }
    }

    /** Steering text emitted since the last call, for the session trace. Null when there is none. */
    public String drainSteer() {
        return pendingSteer.getAndSet(null);
    }

    /**
     * Counts one looking-only tool call and returns the steer to append to its result, or "".
     *
     * <p>Appended to the TOOL RESULT rather than injected as a separate message on purpose: the
     * conversation stays a valid tool-call/tool-result sequence, which is what a small model's
     * chat template is most reliable about, and the model reads it at exactly the moment it is
     * deciding what to do next.
     */
    private String countInvestigation(String result) {
        int calls = investigationCalls.incrementAndGet();
        int fruitless = countFruitfulness(result);
        if (!spiralPolicy.shouldNudgeOutOfInvestigation(calls, hasWritten.get())) {
            return "";
        }
        boolean lastChance = calls >= EarlyKillEnforcer.INVESTIGATION_TOOL_CALLS_BEFORE_SECOND_NUDGE;
        String steer = "\n\n[orchestrator] You have made " + calls + " tool calls in a row and have "
            + (hasWritten.get() ? "not changed any file in any of them. "
                               : "not changed a single file yet. ")
            + (referenceHint.isBlank()
                ? "There is no reference documentation for this project, so stop trying to work "
                  + "the API out in full. Write your best attempt now and let the build tell you "
                  + "what is wrong — a failing compile is much cheaper than reverse-engineering."
                : "Do not work APIs out from compiled classes. " + referenceHint
                  + " Call lookup_api with a class name or a topic and you will get the real "
                  + "documentation.")
            + " If an API is genuinely beyond you, call ask_expert with the exact question — that "
            + "is what it is for and asking is not a failure. If you do not know how to start a "
            + "file, call request_skeleton with the type name."
            + " Start writing now - replace_member or add_member to change existing Java, "
            + "write_file for a new file - then verify with exec."
            // The count is what gets you killed, so say the count. A worker that is told only
            // "start writing" has no way to know how much rope is left; one told the number can
            // decide to commit its best attempt while it still has turns to fix it.
            + (lastChance
                ? " This is the last warning: at "
                  + EarlyKillEnforcer.INVESTIGATION_TOOL_CALLS_BEFORE_KILL
                  + " tool calls without a change this attempt is stopped and thrown away."
                : "");
        appendSteer(steer.strip());
        log.warn("Worker in task '{}' has made {} tool calls without changing anything ({} of them "
            + "returned nothing); steering it to start (reference material: {})",
            task.title(), calls, fruitless, referenceHint.isBlank() ? "none" : referenceHint);
        return steer;
    }

    /**
     * Updates the fruitless streak from one looking-only result and returns its new length.
     *
     * <p>A result is fruitless when it carried nothing this worker did not already have: an empty
     * or timed-out command, a missing file, or text byte-identical to something it has already
     * been shown. Anything else clears the streak. Digesting the result rather than the command
     * is the point — the same question asked six ways looks different every time, and the six
     * identical empty answers do not.
     */
    private int countFruitfulness(String result) {
        if (isInformative(result)) {
            fruitlessInARow.set(0);
            return 0;
        }
        return fruitlessInARow.incrementAndGet();
    }

    private boolean isInformative(String result) {
        String body = bodyOf(result);
        if (body.isBlank()) {
            return false;
        }
        String digest = Integer.toHexString(body.hashCode()) + ":" + body.length();
        if (seenResultDigests.contains(digest)) {
            return false;
        }
        if (seenResultDigests.size() < MAX_REMEMBERED_RESULTS) {
            seenResultDigests.add(digest);
        }
        progressMarks.incrementAndGet();
        return true;
    }

    /** The content of a tool result, with exec's status line and read's error prefix removed. */
    private static String bodyOf(String result) {
        if (result == null) {
            return "";
        }
        String text = result.strip();
        if (text.startsWith("error:")) {
            return "";
        }
        if (text.startsWith("exit=")) {
            int newline = text.indexOf('\n');
            if (text.contains("(timed out)")) {
                return "";
            }
            text = newline < 0 ? "" : text.substring(newline + 1);
        }
        return text.strip();
    }

    /**
     * Appends steering text rather than replacing it, so two steers raised in the same turn both
     * reach the transcript. Replacing was safe when there was one steer; there are now two.
     */
    private void appendSteer(String steer) {
        pendingSteer.updateAndGet(existing ->
            existing == null || existing.isBlank() ? steer : existing + "\n" + steer);
    }

    /**
     * Counts one paused tool call — it still counts toward the twenty-four call kill backstop,
     * unchanged, exactly as an executed looking-only call always has — and returns the pause text
     * in place of whatever the tool would otherwise have done. Deliberately NOT routed through
     * {@link #countInvestigation}: that method also tracks fruitlessness from the RESULT, and
     * {@link #READ_PAUSE_TEXT} is the same text every time on purpose, which would trip the
     * separate six-fruitless-calls guard a call or two early for a reason that has nothing to do
     * with what it measures.
     */
    private String pauseResponse() {
        investigationCalls.incrementAndGet();
        return READ_PAUSE_TEXT;
    }

    private static boolean isBuildOrTestCommand(String command) {
        return command != null && BUILD_OR_TEST_COMMAND.matcher(command).find();
    }

    /**
     * Counts up whenever this worker changes a file or is shown a tool result it had not seen
     * before. A turn across which it does not move is a turn that got nowhere - see
     * {@link EarlyKillEnforcer#IDLE_TURNS_BEFORE_KILL}.
     */
    public int progressMarks() {
        return progressMarks.get();
    }

    private final AtomicInteger progressMarks = new AtomicInteger();

    private void recordWrite() {
        progressMarks.incrementAndGet();
        hasWritten.set(true);
        investigationCalls.set(0);
        fruitlessInARow.set(0);
    }

    private static String firstLines(String text, int lines) {
        if (text == null) {
            return "";
        }
        String[] split = text.strip().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines, split.length); i++) {
            sb.append(i > 0 ? " | " : "").append(split[i]);
        }
        return sb.toString();
    }

    // ---- the tools (registered by name via bindings()) ----

    /**
     * Runs a shell command, then AUDITS what it changed and reverts anything out of bounds.
     *
     * <p>The audit is effect-based on purpose. Inspecting the command string cannot work: an
     * allowlist of binaries buys almost nothing when {@code mvn} runs arbitrary plugins and
     * {@code npm test} runs arbitrary scripts, and the shell offers endless ways to spell the same
     * thing. What CAN be checked is the result — after the command, every changed path in the
     * worktree is put through exactly the same {@link PathPolicy} as {@code write_file}, whatever
     * produced it.
     *
     * <p>Out-of-policy changes are REVERTED rather than merely reported, so "a worker cannot modify
     * a locked file" is true of the worktree itself and not only of the integration branch. That
     * matters because the candidate's own verification runs against this worktree: without the
     * revert, a worker could edit a file it may not touch, be judged on the result, and only be
     * caught later at integration.
     *
     * <p>Not a substitute for the sandbox. This bounds what the command may LEAVE BEHIND in the
     * worktree; it does nothing about what it read, sent over the network, or wrote elsewhere on
     * the machine. With {@code sandbox.enabled=false} that remains unbounded.
     */
    public String exec(String command) {
        if (command != null && REVERSE_ENGINEERING.matcher(command).find()) {
            // Counted, so a worker cannot spend its whole allowance being refused, and steered,
            // so it is told what to do instead in the same breath.
            return REVERSE_ENGINEERING_REFUSED + countInvestigation(REVERSE_ENGINEERING_REFUSED);
        }
        if (command != null && waitsOnAConsole(command)) {
            // Same shape as the reverse-engineering refusal above: counted as a look, and steered
            // in the same breath, before the command ever reaches a process — see CONSOLE_WAIT.
            return CONSOLE_WAIT_REFUSED + countInvestigation(CONSOLE_WAIT_REFUSED);
        }
        if (command != null && isUnscopedKillCommand(command)) {
            // A worker's shell command killed the harness JVM itself, twice (runs 21 and 29) — see
            // KILL_COMMAND and KILL_REFUSED. Same shape again: refused before it ever reaches a
            // process, counted as a look, steered toward what it may do instead.
            return KILL_REFUSED + countInvestigation(KILL_REFUSED);
        }
        if (readingPaused() && !isBuildOrTestCommand(command)) {
            return pauseResponse();
        }
        if (commandTarget == localTarget
                && com.swarmcoder.domain.HostExecution.allowedBy().isEmpty()) {
            // No container was given to this toolbox, and nobody allowed this PC by name. A
            // model's command is exactly what may not run here, so it is not run at all.
            log.error("Refused a worker command in task '{}': no container, and this PC is not "
                + "allowed", task.title());
            return "error: " + com.swarmcoder.domain.HostExecution.refusal("a worker's command");
        }
        try {
            ExecResult result = commandTarget.exec(command, EXEC_TIMEOUT_SECONDS);
            String audit = auditAndRevertStrayWrites();
            // Both ends of a long output are kept (2026-10-02): a build log has its first error
            // near the top and its summary at the bottom, and the old cut kept only the top.
            String answer = ReadSlice.headAndTail("exit=" + result.exitCode()
                + (result.timedOut() ? " (timed out)" : "") + "\n"
                + (result.timedOut() ? timeoutMessage(result) + "\n" : "")
                + result.output() + audit, MAX_TOOL_OUTPUT_CHARS)
                + frameworkErrorHint(result.output());
            com.swarmcoder.inference.LookupMeter.Kind shellRead =
                com.swarmcoder.inference.LookupMeter.ofShellCommand(command);
            com.swarmcoder.inference.LookupMeter.record("worker", shellRead, answer.length());
            if (shellRead != null) {
                // A worker that reads the project through its shell is a defect in its tools
                // (CLAUDE.md section 1); run 82 counted 12 such commands and nothing said
                // what they were.
                logLookup("exec", command, answer);
                if (!shellReadNoted) {
                    // Said once, on the first one: which query answers what the shell was
                    // asked. Nothing is refused (run 85: 22 shell reads, 46,071 characters).
                    shellReadNoted = true;
                    answer += SHELL_READ_NOTE;
                }
            }
            return answer + countInvestigation(answer);
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * What a timed-out command's result says, right after the {@code exit=... (timed out)} line —
     * the {@code (timed out)} substring stays first so {@link #bodyOf} still recognizes and
     * discounts a timeout as uninformative regardless of what this sentence says.
     */
    private static String timeoutMessage(ExecResult result) {
        return "Your command ran for " + result.duration().toSeconds() + " seconds and was "
            + "stopped. " + CONSOLE_WAIT_GUIDANCE;
    }

    /**
     * Checks every path the worktree currently shows as changed, reverting any that policy refuses.
     *
     * <p>Returns "" when everything is in bounds, else a note appended to the tool result so the
     * model learns immediately rather than discovering at integration that its work was rejected.
     */
    private String auditAndRevertStrayWrites() {
        List<String> changed = changedPaths();
        if (changed.isEmpty()) {
            return "";
        }
        List<String> reverted = new ArrayList<>();
        List<String> noted = new ArrayList<>();
        for (String path : changed) {
            String canonical = PathPolicy.canonicalize(worktree, path);
            PathPolicy.Verdict verdict = PathPolicy.check(canonical, task.writeSet(),
                task.acceptanceTestDir(), protectedPaths);
            if (verdict.allowed()) {
                continue;
            }
            if (!verdict.lethal()) {
                // Left in place and recorded. The revert used to fire here on things like the
                // EclipseStore data file a candidate's own test run wrote when it started the
                // service it had just built - and that file was the second violation that killed
                // the worker at turn 102. An audit cannot tell it apart from a class the task
                // genuinely needed; the judge, reading the diff, can.
                if (record(canonical)) {
                    noted.add(canonical);
                }
                continue;
            }
            revert(path);
            reverted.add(path);
            blockingViolations.incrementAndGet();
            log.warn("Reverted out-of-policy change by a shell command in task '{}': {} ({})",
                task.title(), path, verdict.reason());
        }
        StringBuilder note = new StringBuilder();
        if (!reverted.isEmpty()) {
            note.append("\n\n[write policy] That command changed protected files, so those changes "
                + "were REVERTED: ").append(String.join(", ", reverted))
                .append("\nThose paths can never be modified by a worker. Do not try again.");
        }
        if (!noted.isEmpty()) {
            note.append(outOfSetNote(noted));
        }
        return note.toString();
    }

    /** Notes one path as changed outside the write set; true when it was not already known. */
    private boolean record(String canonical) {
        if (canonical == null || canonical.isBlank()) {
            return false;
        }
        boolean fresh = outOfWriteSet.add(canonical);
        if (fresh) {
            log.warn("Task '{}' changed a file outside its write set {} ({} such file(s) so far): {}",
                task.title(), task.writeSet(), outOfWriteSet.size(), canonical);
        }
        return fresh;
    }

    /**
     * What the model is told after a write outside its slice.
     *
     * <p>It is told, and it is not stopped. The point is that it can correct itself when the write
     * was a mistake and carry on when it was not - and that whichever it was is now written down
     * where the judge and the operator can see it, instead of being guessed at by a rule that fired
     * before any code existed.
     */
    private String outOfSetNote(List<String> paths) {
        return "\n\n[write policy] This is outside your write set " + task.writeSet()
            + ": " + String.join(", ", paths)
            + "\nThe change was KEPT - you are not being stopped. It is recorded against this "
            + "candidate and the reviewer will see it, so go outside your own paths only when the "
            + "task genuinely cannot be done inside them, and put nothing there you do not need.";
    }

    /** Repo-relative paths git reports as modified, added, deleted or untracked. */
    private List<String> changedPaths() {
        List<String> paths = new ArrayList<>();
        try {
            // Host-side and trusted: the model cannot influence this the way it can influence
            // whatever ran inside the sandbox.
            ExecResult status = localTarget.exec("git status --porcelain --untracked-files=all", 60);
            if (!status.succeeded()) {
                return paths;
            }
            for (String line : status.output().split("\\R")) {
                if (line.length() < 4) {
                    continue;
                }
                String path = line.substring(3).trim();
                // A rename reads "R  old -> new"; the destination is what was written.
                int arrow = path.indexOf(" -> ");
                if (arrow >= 0) {
                    path = path.substring(arrow + 4);
                }
                if (path.startsWith("\"") && path.endsWith("\"") && path.length() > 1) {
                    path = path.substring(1, path.length() - 1);   // porcelain quotes odd names
                }
                if (!path.isBlank()) {
                    paths.add(path);
                }
            }
        } catch (IOException e) {
            log.warn("Could not audit worktree changes in task '{}': {}", task.title(), e.getMessage());
        }
        return paths;
    }

    /** Restores one path to its committed state, deleting it when it was never tracked. */
    private void revert(String path) {
        String quoted = "\"" + path + "\"";
        try {
            ExecResult restored = localTarget.exec("git checkout -- " + quoted, 60);
            if (!restored.succeeded()) {
                // Not tracked at HEAD, so there is nothing to restore — the command created it.
                Path target = worktree.toAbsolutePath().normalize()
                    .resolve(path.replace('\\', '/')).normalize();
                if (target.startsWith(worktree.toAbsolutePath().normalize())) {
                    Files.deleteIfExists(target);
                }
            }
        } catch (Exception e) {
            log.warn("Could not revert {} in task '{}': {}", path, task.title(), e.getMessage());
        }
    }

    public String read(String path) {
        // The parameter name is the tool schema (-parameters) — do not rename it.
        if (readingPaused()) {
            return pauseResponse();
        }
        try {
            // A plain path is the whole file, as always. `path:FROM-TO` is those lines and
            // `path#name` is one method or type (2026-10-02, see ReadSlice): a worker that wants
            // one method no longer has to be sent - and then resent every turn - the whole file.
            // A file that really is called that is still read as itself.
            ReadSlice.Request asked = ReadSlice.parse(path);
            ConfinedPath.Result where = resolveForRead(asked.path());
            if (!asked.wholeFile()
                    && !(where.allowed() && Files.isRegularFile(where.path()))) {
                ConfinedPath.Result literal = null;
                try {
                    literal = resolveForRead(path);
                } catch (RuntimeException notAPath) {
                    // ':' is not a character a file name may have on every system
                }
                if (literal != null && literal.allowed() && Files.isRegularFile(literal.path())) {
                    where = literal;
                    asked = new ReadSlice.Request(path.strip(), 0, 0, null);
                }
            }
            if (!where.allowed()) {
                String refused = "error: " + where.refusal();
                return refused + countInvestigation(refused);
            }
            String answer;
            if (!Files.isRegularFile(where.path())) {
                answer = "error: file not found: " + asked.path();
            } else {
                byte[] bytes = Files.readAllBytes(where.path());
                // A part may be asked for from anywhere in the file; the whole file is still
                // read from its start only as far as it always was.
                int upTo = asked.wholeFile() ? Math.min(bytes.length, MAX_READ_BYTES)
                    : bytes.length;
                String text = new String(bytes, 0, upTo,
                    java.nio.charset.StandardCharsets.UTF_8);
                answer = ReadSlice.render(text, asked, MAX_TOOL_OUTPUT_CHARS);
                if (asked.wholeFile() && upTo == bytes.length && answer.length() >= text.length()) {
                    // Never refused: the file is the answer. The note says, from the file
                    // itself, which query returns the part of it next time (run 82).
                    answer += wholeFileNote(asked.path(), text);
                }
                com.swarmcoder.inference.LookupMeter.record("worker", asked.wholeFile()
                    ? com.swarmcoder.inference.LookupMeter.Kind.WHOLE_FILE
                    : com.swarmcoder.inference.LookupMeter.Kind.FILE_PART, answer.length());
                logLookup("read", path, answer);
            }
            return answer + countInvestigation(answer);
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * What a whole-file read is answered with besides the file: for a Java file the queries that
     * return its members or one of them and the edits that change one member without the file,
     * for a document the query that returns one section. Computed from the file; "" for a short
     * one. See {@code TreeQueries.wholeFileNote} and {@code DocumentQueries.wholeFileNote}.
     */
    private static String wholeFileNote(String path, String text) {
        String code = com.swarmcoder.knowledge.TreeQueries.wholeFileNote(path, text, "shape_of");
        if (!code.isEmpty()) {
            return code.substring(0, code.length() - 1) + " To change it you do not need the "
                + "file: replace_member and add_member change one member by name.]";
        }
        if (!com.swarmcoder.knowledge.DocumentOutline.isDocument(path)) {
            return "";
        }
        com.swarmcoder.knowledge.DocumentOutline outline =
            com.swarmcoder.knowledge.DocumentOutline.of(path, text);
        String note = "\n[You read this whole document: " + outline.chars() + " characters in "
            + outline.sections().size() + " sections, sent again with every later call. ONE "
            + "section is doc_section " + path + "#<number or heading>; doc_outline " + path
            + " lists the sections with their sizes.]";
        return outline.sections().size() >= 2 && note.length() * 10 <= text.length() ? note : "";
    }

    /** True once this worker has been told which queries answer what it asked its shell. */
    private volatile boolean shellReadNoted;

    static final String SHELL_READ_NOTE = "\n[You read the project through the shell. The syntax "
        + "tree answers the same in fewer characters, for the reference material too: types_in "
        + "<folder or package> for what a place holds (its tests included), usages_of <Type> or "
        + "<Type>#<method> for where a name is used, body_of <Type> for one type's code without "
        + "its comments, build_of <module> for a build file, resources_of <module> for its resource files, doc_section for a document, acceptance_test for the test your task must satisfy.]";

    /** One line per lookup, as a role's lookups are logged: what was asked and how much came back. */
    private void logLookup(String tool, String argument, String answer) {
        String asked = argument == null ? "" : argument.strip().replaceAll("\\s+", " ");
        log.info("worker {}('{}') -> {} chars", tool,
            asked.length() <= 200 ? asked : asked.substring(0, 200) + "...", answer.length());
    }

    /**
     * Writes one file's new content under the write policy every write of a worker is held to:
     * a protected path is refused and counted, a path outside the write set is written and
     * noted. Returns the refusal, or null when it was written; {@code outside} is filled with
     * the note for a path outside the write set.
     */
    private String writeUnderPolicy(String rel, String content, StringBuilder outside)
            throws IOException {
        String canonical = PathPolicy.canonicalize(worktree, rel);
        PathPolicy.Verdict verdict = PathPolicy.check(canonical, task.writeSet(),
            task.acceptanceTestDir(), protectedPaths);
        if (!verdict.allowed() && verdict.lethal()) {
            blockingViolations.incrementAndGet();
            log.warn("Blocked write ({} total) in task '{}': {}",
                blockingViolations.get(), task.title(), verdict.reason());
            return "error: " + verdict.reason();
        }
        Path root = worktree.toAbsolutePath().normalize();
        Path target = root.resolve(canonical).normalize();
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        recordWrite();
        if (!verdict.allowed() && record(canonical)) {
            outside.append(outOfSetNote(List.of(canonical)));
        }
        return null;
    }

    /**
     * {@code replace_member} and {@code add_member}: one member of one type changed by its name,
     * in the file as it is in this checkout now, without the worker reading or rewriting the
     * file (run 82: 36 whole files read by workers, and every rejected diff answered with a
     * whole-file rewrite). The edit is computed with no model ({@code CheckoutCode}); the write
     * is held to the same policy as write_file.
     */
    private String editMember(com.swarmcoder.knowledge.CheckoutCode.Edit edit) {
        if (!edit.changed()) {
            return edit.answer();
        }
        try {
            StringBuilder outside = new StringBuilder();
            String refused = writeUnderPolicy(edit.file(), edit.content(), outside);
            return refused != null ? refused : edit.answer() + outside;
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * One line per edit, by tool, with whether it changed anything (run 88, section 60: 175
     * worker calls and the log could not say how many edits were by member, by diff or by whole
     * file, nor whether a member edit was ever refused - only the 4 diffs showed, through git).
     */
    private String logEdit(String tool, String target, String answer) {
        String asked = target == null ? "" : target.strip().replaceAll("\\s+", " ");
        String said = answer == null ? "" : answer.strip();
        int line = said.indexOf('\n');
        said = line < 0 ? said : said.substring(0, line);
        log.info("worker edit {}('{}') -> {}: {}", tool,
            asked.length() <= 200 ? asked : asked.substring(0, 200) + "...",
            said.startsWith("error:") ? "REFUSED" : "done",
            said.length() <= 160 ? said : said.substring(0, 160) + "...");
        return answer;
    }

    public String replaceMember(String member, String newText) {
        // The parameter names are the tool schema (-parameters) - do not rename them.
        return logEdit("replace_member", member, editMember(
            com.swarmcoder.knowledge.CheckoutCode.replaceMember(worktree, member, newText)));
    }

    public String removeMember(String member) {
        return logEdit("remove_member", member, editMember(
            com.swarmcoder.knowledge.CheckoutCode.removeMember(worktree, member)));
    }

    public String addMember(String type, String text) {
        return logEdit("add_member", type, editMember(
            com.swarmcoder.knowledge.CheckoutCode.addMember(worktree, type, text)));
    }

    public String applyDiff(String unifiedDiff) {
        return logEdit("apply_diff", String.join(", ", touchedPaths(
            relativizeDiffHeaders(unifiedDiff))), applyTheDiff(unifiedDiff));
    }

    private String applyTheDiff(String unifiedDiff) {
        // Model-supplied diffs may carry absolute paths in their headers — relativize them
        // so the write-set check passes and git apply targets the worktree. The parameter
        // NAME is the tool schema (compiled with -parameters) — do not rename it.
        String diff = relativizeDiffHeaders(unifiedDiff);
        List<String> touched = touchedPaths(diff);
        if (touched.isEmpty()) {
            return "error: no target paths found in diff — provide a standard unified diff with +++ b/<path> headers";
        }
        List<String> outside = new ArrayList<>();
        for (String path : touched) {
            String canonical = PathPolicy.canonicalize(worktree, path);
            PathPolicy.Verdict verdict = PathPolicy.check(canonical, task.writeSet(),
                task.acceptanceTestDir(), protectedPaths);
            if (verdict.allowed()) {
                continue;
            }
            if (verdict.lethal()) {
                // One protected path refuses the WHOLE diff: a patch either applies or it does
                // not, and applying the harmless half of it leaves the worktree in a state
                // neither the model nor the reviewer asked for.
                blockingViolations.incrementAndGet();
                log.warn("Blocked write ({} total) in task '{}': {}",
                    blockingViolations.get(), task.title(), verdict.reason());
                return "error: " + verdict.reason();
            }
            outside.add(canonical);
        }
        try {
            Path patch = Files.createTempFile("sc-patch", ".diff");
            try {
                Files.writeString(patch, diff.endsWith("\n") ? diff : diff + "\n");
                // --recount forgives miscounted @@ hunk headers, -C1 tolerates context drift —
                // the two dominant rejection causes for model-authored diffs.
                ExecResult result = localTarget.exec(
                    "git apply --recount -C1 --whitespace=nowarn \"" + patch.toAbsolutePath() + "\"", 60);
                if (!result.succeeded() && applyFailures.incrementAndGet() <= 3) {
                    // First few rejections per worker at WARN — the signature of a model
                    // emitting malformed diffs (a no-change candidate's usual root cause).
                    log.warn("git apply rejection #{} in task '{}' (paths {}): {}",
                        applyFailures.get(), task.title(), touched,
                        firstLines(result.output(), 4));
                }
                if (result.succeeded()) {
                    recordWrite();
                    List<String> noted = new ArrayList<>();
                    for (String path : outside) {
                        if (record(path)) {
                            noted.add(path);
                        }
                    }
                    return "applied cleanly to: " + String.join(", ", touched)
                        + (noted.isEmpty() ? "" : outOfSetNote(noted));
                }
                return "error: git apply failed:\n" + truncate(result.output())
                    + writeFileFallbackHint(touched);
            } finally {
                Files.deleteIfExists(patch);
            }
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * The sentence added to a failed {@code apply_diff}, the FIRST time it fails for a given file
     * — steering a worker whose unified diffs keep bouncing toward {@code write_file}, which needs
     * no diff format at all. The 27B model produces malformed diffs for XML routinely ({@code
     * error: No valid patches in input}), and until this existed the worker was told nothing more
     * than the raw git error and kept retrying the same broken diff shape.
     *
     * <p>Scoped per file, not per call: a worker that reads the hint, switches to {@code
     * write_file} for one file and later fails a diff against a DIFFERENT file should be told
     * again — but should not be told the identical sentence on every retry against the same one.
     */
    private String writeFileFallbackHint(List<String> touched) {
        boolean firstTimeForAny = false;
        for (String path : touched) {
            if (diffRejectedOnceFor.add(path)) {
                firstTimeForAny = true;
            }
        }
        if (!firstTimeForAny) {
            return "";
        }
        boolean java = touched.stream().anyMatch(path -> path.endsWith(".java"));
        return "\n\nYour patch was not a valid unified diff. "
            + (java ? "To change a method, a constructor or a field of a Java file, use "
                + "replace_member (Type#member and its complete new declaration) or add_member "
                + "- neither needs the file read. Otherwise, for this file, " : "For this file, ")
            + "use write_file with "
            + "the whole file content instead"
            + (java ? " — body_of <Type> returns it as it is now, without comments."
                : " — read it first, then write it back with your change.");
    }

    /**
     * Full-file replacement — the {@code full-files} output mode of {@code SamplingConfig}.
     * Small models mangle unified-diff hunk headers routinely; writing the whole file is the
     * reliable path for small files. Same mechanical write-set enforcement as apply_diff.
     */
    public String writeFile(String path, String content) {
        // The parameter names are the tool schema (-parameters) — do not rename them.
        boolean existed = false;
        try {
            String rel = path == null || path.isBlank() ? null : toRelative(path);
            existed = rel != null && Files.isRegularFile(worktree.resolve(rel));
        } catch (RuntimeException notAPath) {                               // noqa
            // the write below says what is wrong with it
        }
        return logEdit(existed ? "write_file, an existing file whole" : "write_file, a new file",
            path, writeTheFile(path, content));
    }

    private String writeTheFile(String path, String content) {
        if (path == null || path.isBlank()) {
            return "error: provide a repository-relative path";
        }
        if (content == null || content.isBlank()) {
            return "error: provide the full new content of the file";
        }
        String rel = toRelative(path);
        if (rel == null) {
            return "error: " + outsideRefusal(path);
        }
        String canonical = PathPolicy.canonicalize(worktree, rel);
        PathPolicy.Verdict verdict = PathPolicy.check(canonical, task.writeSet(),
            task.acceptanceTestDir(), protectedPaths);
        if (!verdict.allowed() && verdict.lethal()) {
            blockingViolations.incrementAndGet();
            log.warn("Blocked write ({} total) in task '{}': {}",
                blockingViolations.get(), task.title(), verdict.reason());
            return "error: " + verdict.reason();
        }
        try {
            Path root = worktree.toAbsolutePath().normalize();
            Path target = root.resolve(canonical).normalize();
            Files.createDirectories(target.getParent());
            Files.writeString(target, content.endsWith("\n") ? content : content + "\n");
            recordWrite();
            String written = "wrote " + rel + " (" + content.length() + " chars)";
            return verdict.allowed() || !record(canonical) ? written
                : written + outOfSetNote(List.of(canonical));
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    public String lookupApi(String query) {
        if (query == null || query.isBlank()) {
            return "error: provide a library/symbol to look up, e.g. \"jackson ObjectMapper.readValue\".";
        }
        if (readingPaused()) {
            return pauseResponse();
        }
        try {
            String trimmed = query.trim();
            java.util.Set<String> newTerms = newLookupTerms(trimmed);
            String answer = truncate(apiLookup.lookup(trimmed,
                java.util.Set.copyOf(givenLookupSections), newTerms),
                room.chars(MAX_TOOL_OUTPUT_CHARS));
            recordLookup(trimmed, answer);
            com.swarmcoder.inference.LookupMeter.record("worker",
                com.swarmcoder.inference.LookupMeter.Kind.SEARCH, answer.length());
            logLookup("lookup_api", trimmed, answer); // run 85: 7 searches, 45,575 chars, unnamed
            rememberGivenSections(answer);
            lookupQueryWordsSeen.addAll(wordsOf(trimmed));
            return answer + noteDegenerateLookup(trimmed, answer) + countInvestigation(answer);
        } catch (Exception e) {
            return "lookup_api unavailable: " + e.getMessage();
        }
    }

    /**
     * {@code find_example}: one whole file of real code that already does the kind of thing the
     * worker is writing, chosen by which types it uses - the librarian's by-use selection, the
     * same tool the planning roles have. The parameter names are the tool's schema.
     *
     * <p>Counted as an investigation call like {@code lookup_api}, and paused with it.
     */
    public String findExample(String what, String kind) {
        if (what == null || what.isBlank()) {
            return "error: name the library and project types involved and say what the code does.";
        }
        if (readingPaused()) {
            return pauseResponse();
        }
        try {
            boolean wantTest = kind != null
                && kind.strip().toLowerCase(java.util.Locale.ROOT).startsWith("test");
            String answer = truncate(apiLookup.findExample(what.strip(), wantTest,
                "a worker on '" + task.title() + "'"), room.chars(MAX_TOOL_OUTPUT_CHARS));
            return answer + countInvestigation(answer);
        } catch (Exception e) {
            return "find_example unavailable: " + e.getMessage();
        }
    }

    /**
     * The words of {@code query} this worker has not used in any earlier {@code lookup_api}
     * question this task — computed BEFORE {@link #lookupQueryWordsSeen} is updated with this
     * query's own words, so the very first question's words all count as "new" (harmless: nothing
     * has been given yet to re-rank against) and a repeated question adds nothing.
     */
    private java.util.Set<String> newLookupTerms(String query) {
        java.util.Set<String> added = new java.util.HashSet<>(wordsOf(query));
        added.removeAll(lookupQueryWordsSeen);
        return added;
    }

    /** Lower-cased words of at least three characters — the same shape the index matches on. */
    private static java.util.Set<String> wordsOf(String text) {
        java.util.Set<String> words = new java.util.HashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^A-Za-z0-9]+")) {
            if (token.length() >= 3) {
                words.add(token);
            }
        }
        return words;
    }

    /**
     * Records every documentation section this answer carried — every {@code ####} line, not only
     * the leading one — so the NEXT {@code lookup_api} call excludes all of them, not just the one
     * a warning happens to name.
     */
    private void rememberGivenSections(String answer) {
        if (answer == null) {
            return;
        }
        Matcher matcher = SECTION_LINE.matcher(answer);
        while (matcher.find()) {
            givenLookupSections.add(matcher.group(1).strip());
        }
    }

    /**
     * Remembers this question and the top section its answer led with, for
     * {@link #lookupHistory()} — every {@code lookup_api} call, not only the ones that trip the
     * degenerate-search check, so a kill line or a diagnosis can show the whole pattern that led
     * up to it.
     */
    private void recordLookup(String query, String answer) {
        String question = query.length() <= MAX_LOOKUP_QUESTION_CHARS ? query
            : query.substring(0, MAX_LOOKUP_QUESTION_CHARS);
        LookupQuestion entry = new LookupQuestion(question, topSection(answer));
        synchronized (lookupHistory) {
            lookupHistory.add(entry);
            while (lookupHistory.size() > MAX_LOOKUP_HISTORY) {
                lookupHistory.remove(0);
            }
        }
    }

    /** The leading "path › heading" of a {@code lookup_api} answer, or "" when nothing matched. */
    private static String topSection(String answer) {
        if (answer == null) {
            return "";
        }
        Matcher matcher = SECTION_LINE.matcher(answer);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    /**
     * Says so, once, when the documentation search has answered several DIFFERENT questions with
     * the same leading section — and returns the note to append to the tool result.
     *
     * <p><b>Why this exists.</b> In the run that produced the stall guard, the worker asked
     * {@code lookup_api} four well-formed questions about how to handle a button click and got the
     * same unrelated section every time. It said so itself, twice, and then went to the jars — and
     * found the answer there in one call. That is the correct move for the worker and a defect in
     * the search, and until this note existed nothing in the run said which of the two had
     * happened: the operator saw a worker "ignoring the rules" and no sign that the documentation
     * route was dead.
     *
     * <p>The note goes into the steer channel as well as the tool result, so it lands in the run
     * transcript as an orchestrator line the operator reads on the session timeline.
     */
    private String noteDegenerateLookup(String query, String answer) {
        // The answer QUOTES the question back ("Documentation for \"<query>\": …"), and so does the
        // miss message, so two answers that are otherwise the same section still differ in their
        // first line. Take the query out before digesting or this check can never fire — which is
        // exactly how four identical answers went unnoticed in the run that prompted it.
        String head = answer == null ? "" : answer.replace(query, "").strip();
        if (head.length() > 400) {
            head = head.substring(0, 400);
        }
        if (head.isBlank()) {
            return "";
        }
        String digest = Integer.toHexString(head.hashCode());
        String previous = lookupAnswerByQuery.putIfAbsent(query, digest);
        if (previous != null) {
            return "";   // the same question asked twice proves nothing about the search
        }
        long sameAnswer = lookupAnswerByQuery.values().stream().filter(digest::equals).count();
        if (sameAnswer < SAME_LOOKUP_ANSWERS_BEFORE_WARNING
            || !lookupWarningGiven.compareAndSet(false, true)) {
            return "";
        }
        // Name the section itself, not just that there was one — a worker told WHICH section is
        // being handed back can recognise it if the same page comes up again, and the operator
        // reading the steer later does not have to guess what "the same section" was.
        String section = topSection(answer);
        String sectionPhrase = section.isBlank() ? "the same section"
            : "the same section (" + section + ")";
        String note = "\n\n[orchestrator] The documentation search has now answered " + sameAnswer
            + " different questions with " + sectionPhrase + ", so it is not finding what you are "
            + "asking for. lookup_api is meant to never repeat a section it has already given you, "
            + "so this is a defect in the documentation index and not something asking differently "
            + "will fix. Do not spend your remaining turns on it and do not start unpacking "
            + "jars either — both are dead ends here. Ask it once more naming ONE class or file "
            + "exactly, and if that fails, write your best attempt and let the build correct you.";
        appendSteer(note.strip());
        log.warn("lookup_api answered {} different queries in task '{}' with the same leading "
            + "section — this should be impossible now that lookup_api excludes sections it has "
            + "already given a worker, so the documentation index itself has a defect. Workers "
            + "will fall back to decompiling. Latest query: '{}'",
            sameAnswer, task.title(), query);
        return note;
    }

    /**
     * Commands that reverse-engineer the framework instead of asking about it.
     *
     * <p><b>Measured, five runs of a plain harness.</b> Given the framework's own working code in
     * its prompt, the model spent 66 shell commands on {@code jar xf}, {@code jar tf} and
     * {@code javap} — over the source jars, over the annotation processor's bytecode, over the CDI
     * producer — to confirm an API it had already been handed, and wrote nothing in 92 turns.
     * Every one of those answers agreed with what it already had. Telling it not to do this in
     * prose does not work; the workflow rules have said so for weeks and both runs did it anyway.
     * Refusing the command works.
     *
     * <p>Deliberately narrow. It refuses the things that mean "I am decompiling the framework" and
     * nothing else: a jar being listed, unpacked or disassembled, and a hunt across the disk for
     * one. A build, a test, a grep and a find inside the worktree are untouched.
     */
    private static final Pattern REVERSE_ENGINEERING = Pattern.compile(
        "(?i)(\\bjavap\\b"
        + "|\\bjar\\s+[a-z]*[tx][a-z]*f\\b"
        + "|\\bunzip\\b[^|;&]*\\.jar"
        + "|\\bfind\\b[^|;&]*-name[^|;&]*\\.jar"
        + "|\\bcfr\\b|\\bprocyon\\b|\\bjd-cli\\b)");

    /** What a refused command says instead. Byte-identical every time, like the read pause. */
    static final String REVERSE_ENGINEERING_REFUSED =
        "Refused: do not reverse-engineer the framework. Call ask_expert with the exact API "
        + "question instead — it answers from code in this project's reference material that "
        + "compiles today, which is more reliable than a disassembly and costs one turn instead "
        + "of twenty.";

    /**
     * Commands that wait on a console the exec tool never has one to offer, or that start a
     * server in the foreground where the exec tool's own 300-second timeout will be the only
     * thing that ever ends the call.
     *
     * <p><b>Measured, two harness runs on one night.</b> A worker started a demo server as the
     * foreground command of an {@code exec} call, followed it with {@code sleep 25} and more
     * commands that could never run because the shell never returned; the call ran the full 300
     * seconds, was killed, and the run never produced a result. A second worker, on a later run,
     * piped {@code git log} into {@code more} and separately ran {@code type con} — both of them
     * genuinely wait for a keypress that an automated exec tool will never send — and burned
     * another five minutes on the same timeout. Stdin is now closed to every command (see
     * {@code LocalProcessExecTarget}/{@code DockerSandboxManager}), which alone fixes the pager and
     * {@code type con} cases; this refusal catches them (and an unbackgrounded server start) before
     * the command even runs, so the worker is steered on the spot instead of losing five minutes to
     * find out.
     *
     * <p>Deliberately narrow, the same way {@link #REVERSE_ENGINEERING} is: a pager, a console
     * read, or an unbackgrounded {@code java ...App}/{@code ...Server}, {@code mvn exec:java},
     * a Maven {@code :run} goal, or {@code npm start}. An ordinary build, test, grep or find is
     * untouched, and a command already backgrounded (trailing {@code &}, {@code start /b},
     * {@code nohup}) is untouched too — that is exactly how a worker is meant to try a server.
     */
    private static final Pattern CONSOLE_WAIT = Pattern.compile(
        "(?i)(\\|\\s*more\\b"
        + "|\\btype\\s+con\\b"
        + "|\\bpause\\b"
        + "|\\bread\\s+-p\\b"
        + "|\\|\\s*less\\b|\\bless\\b)");

    private static final Pattern FOREGROUND_SERVER = Pattern.compile(
        "(?i)(\\bjava\\b[^&\\n]*-cp\\b[^&\\n]*\\b[\\w.$]*(App|Server)\\b"
        + "|\\bmvn\\w*\\b[^&\\n]*\\bexec:java\\b"
        + "|\\bmvn\\w*\\b[^&\\n]*:run\\b"
        + "|\\bnpm\\s+start\\b)");

    /** A trailing background operator, or an explicit backgrounding idiom, on either shell. */
    private static final Pattern BACKGROUNDED = Pattern.compile(
        "(?i)((?<!&)&\\s*$|\\bnohup\\b|\\bstart\\s+/b\\b)");

    /** What a refused wait-on-a-console command says instead. Reused verbatim in the timeout reply. */
    static final String CONSOLE_WAIT_GUIDANCE =
        "A command must finish on its own; do not start servers in the foreground or pipe to a "
        + "pager. To try a server, start it in the background with its output to a file and stop "
        + "it before you finish.";

    static final String CONSOLE_WAIT_REFUSED =
        "Refused: this command would wait on a console, or start a server in the foreground. "
        + CONSOLE_WAIT_GUIDANCE;

    /** True for a command this exec tool must refuse before running it — see {@link #CONSOLE_WAIT}. */
    private static boolean waitsOnAConsole(String command) {
        if (CONSOLE_WAIT.matcher(command).find()) {
            return true;
        }
        return FOREGROUND_SERVER.matcher(command).find() && !BACKGROUNDED.matcher(command.strip()).find();
    }

    /**
     * Process-killing idioms this exec tool refuses unless they are narrowly scoped to a process
     * THIS WORKER'S OWN {@code exec} started in the background (see
     * {@link LocalProcessExecTarget#startedBackgroundPid}).
     *
     * <p><b>Measured, twice.</b> A worker's shell command killed the surefire fork JVM running the
     * whole harness — the swarm engine and every other worker with it — and nothing recorded which
     * command it was; the same thing happened on an earlier run after a worker started a demo
     * server. A command may background a server it started (the {@code &}/{@code nohup} idiom
     * {@link #FOREGROUND_SERVER} already steers toward, now genuinely non-blocking on Windows too —
     * see {@code LocalProcessExecTarget#startBackground}) and it may stop THAT SAME process later
     * by its own pid. It may never reach for a name, an image, a port, or a process-listing pipeline
     * to kill something it did not start — those are exactly the idioms that can select the harness
     * itself, and a build or test run never needs any of them: it finishes or times out on its own.
     *
     * <p>Two shapes, checked by {@link #isUnscopedKillCommand}: {@link #ALWAYS_REFUSE_KILL} can
     * never be validated against anything this worker started (a name, an image, a port, a
     * pipeline) and is refused outright; the narrow {@code taskkill /PID n}, {@code kill n} /
     * {@code kill -9 n}, and {@code Stop-Process -Id n} forms are refused only when {@code n} is
     * NOT a pid {@link #localTarget} remembers starting.
     */
    private static final Pattern ALWAYS_REFUSE_KILL = Pattern.compile(
        "(?i)(\\btaskkill\\b[^\\n]*/im\\b"
        + "|\\bwmic\\b[^\\n]*\\bprocess\\b[^\\n]*(\\bdelete\\b|\\bcall\\s+terminate\\b|\\bterminate\\b)"
        + "|\\bGet-Process\\b[^\\n]*\\|[^\\n]*\\bStop-Process\\b"
        + "|\\bpkill\\b|\\bkillall\\b|\\bfuser\\b[^\\n]*-k\\b"
        + "|\\bnetstat\\b[^\\n]*\\bfindstr\\b[^\\n]*\\btaskkill\\b"
        + "|\\blsof\\b[^\\n]*-t\\b[^\\n]*\\|[^\\n]*\\bkill\\b)");

    /** {@code taskkill ... /PID n} — allowed only when {@code n} is a pid this worker started. */
    private static final Pattern TASKKILL_PID = Pattern.compile("(?i)\\btaskkill\\b[^\\n]*/pid\\s+(\\d+)");
    /** {@code Stop-Process ... -Id n} — same carve-out. */
    private static final Pattern STOP_PROCESS_ID = Pattern.compile("(?i)\\bStop-Process\\b[^\\n]*-Id\\s+(\\d+)");
    /** Bare {@code kill n} / {@code kill -9 n} / {@code kill -KILL n} — {@code \b} excludes pkill/killall. */
    private static final Pattern POSIX_KILL_PID =
        Pattern.compile("(?i)\\bkill\\s+(?:-(?:9|KILL)\\s+)?(\\d+)\\b");

    static final String KILL_REFUSED =
        "Refused: a command may not kill a process it did not start. If you started a server in "
        + "the background (a command ending in & or nohup), it is stopped automatically when you "
        + "finish, or you may name that exact pid — the one the background-start call reported — "
        + "in taskkill /F /T /PID or kill; nothing else. A build or test run never needs a kill; "
        + "let it finish or time out on its own.";

    /** True when {@code command} tries to kill something this worker did not itself start. */
    private boolean isUnscopedKillCommand(String command) {
        if (ALWAYS_REFUSE_KILL.matcher(command).find()) {
            return true;
        }
        Long pid = scopedKillPid(command);
        return pid != null && !localTarget.startedBackgroundPid(pid);
    }

    /** The pid a narrow, potentially-scoped kill command names, or null if it names none. */
    private static Long scopedKillPid(String command) {
        Matcher m = TASKKILL_PID.matcher(command);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        m = STOP_PROCESS_ID.matcher(command);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        m = POSIX_KILL_PID.matcher(command);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return null;
    }

    /**
     * The worker's 911: the API question it cannot answer itself.
     *
     * <p>Counted as an investigation call like any other look, because it is one — a worker that
     * asks sixteen questions and writes nothing has still written nothing. What it is NOT is
     * refused while reading is paused: the whole point is that a worker out of rope can still get
     * the one answer that lets it write.
     */
    public String askExpert(String question, String whatITried) {
        ExpertHelp.Answer answer = expert.askExpert(question, whatITried);
        synchronized (helpCalls) {
            helpCalls.add(answer);
        }
        return answer.text() + countInvestigation(answer.text());
    }

    /** A compiling starting point for a type this task must deliver, or the nearest example. */
    public String requestSkeleton(String typeOrTask) {
        ExpertHelp.Answer answer = expert.requestSkeleton(typeOrTask);
        synchronized (helpCalls) {
            helpCalls.add(answer);
        }
        return answer.text() + countInvestigation(answer.text());
    }

    /**
     * A compile error naming a symbol from the framework, answered with what to do about it.
     *
     * <p>"cannot find symbol: class ZeroZDbNode" is not a typo a worker can stare at until it
     * resolves — it is a framework API it does not know, and the next thing it does is go and
     * disassemble something. Saying so at the moment the error appears is the cheapest possible
     * intervention there is.
     */
    String frameworkErrorHint(String output) {
        if (output == null || frameworkPackages.isEmpty()
            || (!output.contains("cannot find symbol") && !output.contains("does not exist"))) {
            return "";
        }
        for (String prefix : frameworkPackages) {
            if (!prefix.isBlank() && output.contains(prefix)) {
                return "\n\n[orchestrator] That build error names something from `"
                    + prefix + "`, which is this project's framework and not your own code. Do not "
                    + "guess at it and do not go looking in jars — call ask_expert with the exact "
                    + "symbol and what you were trying to do.";
            }
        }
        return "";
    }


    /**
     * The worker saying, with evidence, that a stated rule cannot be met here or is wrong for this
     * case. Recorded on the candidate; the judge reads it next to the code and decides whether the
     * evidence holds.
     *
     * <p><b>Why a worker gets this</b> (harness runs 53 and 55, 2026-10-01, HamBook on ZeroZ Stack
     * 0.9.1). The rules were stated from the technical document before anybody met the library.
     * One could not be met at all (the stack has no browser-side message catalog for "all
     * user-visible text lives in resource bundles"), and one was too broad (the stack serializes
     * enums natively, so "every type that crosses the wire is a {@code @DataModel}" did not need an
     * enum annotated). The workers were the only ones who met that behaviour, and they had no way
     * to say so: every candidate "broke" the rule and both runs parked hours in.
     *
     * <p>Evidence is required — a dispute without it is refused here and never recorded, because
     * a claim with nothing behind it is exactly what the judge must not be asked to weigh. When
     * the evidence is a path to a file in this checkout, the start of that file is attached, so
     * the judge reads the evidence rather than its address.
     *
     * <p>The parameter NAMES are the tool schema (compiled with -parameters) — do not rename them.
     */
    public String disputeRule(String rule, String reason, String evidence) {
        String name = rule == null ? "" : rule.strip();
        String why = reason == null ? "" : reason.strip();
        String proof = evidence == null ? "" : evidence.strip();
        if (name.isEmpty() || why.isEmpty()) {
            return "error: name the rule exactly as your brief lists it, and say why it cannot be "
                + "met or does not fit this case.";
        }
        if (proof.isEmpty()) {
            return "error: a dispute needs evidence — a compiler or test output excerpt, an "
                + "excerpt of the library's own source or documentation, or the path of a file "
                + "that shows it. Without evidence, follow the rule.";
        }
        synchronized (ruleDisputes) {
            if (ruleDisputes.size() >= MAX_RULE_DISPUTES) {
                return "error: you have already disputed " + MAX_RULE_DISPUTES + " rules. Follow "
                    + "the rest as written.";
            }
            ruleDisputes.add(new RuleDispute(name, why, withFileExcerpt(proof)));
        }
        log.info("Worker disputed rule '{}' in task '{}': {}", name,
            task == null ? "" : task.title(), why);
        return "Recorded. Your dispute and its evidence go to the judge next to your code. Now "
            + "finish the task the way the evidence supports, and say in report_done what you did "
            + "instead of the rule and why.";
    }

    /**
     * The evidence, with the start of the file it names attached when it is a single path to a
     * file in this checkout. Anything else — output, an excerpt, a sentence — is kept as given.
     */
    private String withFileExcerpt(String evidence) {
        if (evidence.contains("\n") || evidence.contains(" ") || evidence.length() > 300) {
            return evidence;
        }
        String rel = toRelative(evidence);
        if (rel == null || rel.isEmpty() || rel.contains("..") || rel.startsWith(".git/")) {
            return evidence;
        }
        try {
            String content = localTarget.readFile(rel, EVIDENCE_FILE_CHARS);
            if (content == null || content.isBlank()) {
                return evidence;
            }
            String excerpt = content.length() <= EVIDENCE_FILE_CHARS ? content
                : content.substring(0, EVIDENCE_FILE_CHARS) + "\n…";
            return evidence + "\n" + excerpt;
        } catch (IOException | RuntimeException e) {
            return evidence;
        }
    }

    public String reportDone(String summary) {
        return summary == null ? "" : summary;
    }

    /**
     * One question to the project's syntax tree and object graph (owner's rule, 2026-10-04): the
     * answer comes from the tree with no model, is counted as a look like any other, and is on
     * the run's record of lookups as a tree query.
     */
    private String tree(String query, String argument) {
        if (argument == null) {
            return "error: say what to look up.";
        }
        if (readingPaused()) {
            return pauseResponse();
        }
        try {
            // A member of a file of this checkout is answered from the file as it is NOW: the
            // tree holds the project as it was when the task started, and a worker that had
            // changed a file read it whole to see its own change.
            // The same holds for a type's members and for what a folder contains (run 88,
            // section 60): the tree is built from the project at the run's base, a worker's
            // checkout also holds what earlier tasks of the run delivered, and shape_of
            // answered "no such type" for those 27 times before the files were read whole.
            String live = "body_of".equals(query)
                ? com.swarmcoder.knowledge.CheckoutCode.bodyOf(worktree, argument)
                : "shape_of".equals(query)
                    ? com.swarmcoder.knowledge.CheckoutCode.shapeIfNotAsTheTree(worktree,
                        argument, apiLookup::sourceInTree)
                    : null;
            String fromTree = live != null ? live : apiLookup.tree(query, argument.strip());
            if ("types_in".equals(query)) {
                fromTree += com.swarmcoder.knowledge.CheckoutCode.notAsTheTreeIn(worktree,
                    argument, apiLookup::sourceInTree);
            }
            String answer = truncate(fromTree, room.chars(MAX_TOOL_OUTPUT_CHARS));
            com.swarmcoder.inference.LookupMeter.record("worker",
                query.startsWith("doc_") ? com.swarmcoder.inference.LookupMeter.Kind.DOCUMENT
                : live == null && apiLookup.answeredByLanguageServer()
                    ? com.swarmcoder.inference.LookupMeter.Kind.LANGUAGE_SERVER
                    : com.swarmcoder.inference.LookupMeter.Kind.TREE, answer.length());
            logLookup(query, argument, answer);
            return answer + countInvestigation(answer);
        } catch (Exception e) {
            return query + " unavailable: " + e.getMessage();
        }
    }

    /** One question to the Java language server about the project, with no model. */
    private String server(String query, String argument) {
        if (argument == null) {
            return "error: say what to look up.";
        }
        if (readingPaused()) {
            return pauseResponse();
        }
        try {
            String found = apiLookup.tree(query, argument.strip());
            if ("find_symbol".equals(query)) {
                // The project's server indexes the project at the run's base; a type an earlier
                // task of this run added is in this checkout only (run 88: 13 such searches
                // answered "0 type(s) match", then ls and find).
                found += com.swarmcoder.knowledge.CheckoutCode.newTypesNamed(worktree, argument,
                    apiLookup::sourceInTree);
            }
            String answer = truncate(found, room.chars(MAX_TOOL_OUTPUT_CHARS));
            com.swarmcoder.inference.LookupMeter.record("worker",
                com.swarmcoder.inference.LookupMeter.Kind.LANGUAGE_SERVER, answer.length());
            logLookup(query, argument, answer);
            return answer + countInvestigation(answer);
        } catch (Exception e) {
            return query + " unavailable: " + e.getMessage();
        }
    }

    /**
     * A refactoring or a check by the language server in this worker's own checkout. A file it
     * would write is held to the same policy as write_file: a protected path refuses the whole
     * change, a file outside the write set is written and noted.
     */
    private String inCheckout(String action, String argument, String second) {
        if (argument == null || argument.isBlank()) {
            return "error: say what to " + action.replace('_', ' ') + ".";
        }
        try {
            String first = "rename".equals(action) ? argument.strip() : toRelative(argument);
            if (first == null) {
                return "error: " + outsideRefusal(argument);
            }
            ApiLookup.InCheckout done = apiLookup.inCheckout(worktree, action, first, second,
                relative -> {
                    PathPolicy.Verdict verdict = PathPolicy.check(
                        PathPolicy.canonicalize(worktree, relative), task.writeSet(),
                        task.acceptanceTestDir(), protectedPaths);
                    return !verdict.allowed() && verdict.lethal() ? verdict.reason() : null;
                });
            String answer = truncate(done.answer(), room.chars(MAX_TOOL_OUTPUT_CHARS));
            com.swarmcoder.inference.LookupMeter.record("worker",
                com.swarmcoder.inference.LookupMeter.Kind.LANGUAGE_SERVER, answer.length());
            logLookup(action, argument, answer);
            if (done.filesWritten().isEmpty()) {
                return answer;
            }
            recordWrite();
            List<String> outside = new ArrayList<>();
            for (String file : done.filesWritten()) {
                String canonical = PathPolicy.canonicalize(worktree, file);
                if (!PathPolicy.check(canonical, task.writeSet(), task.acceptanceTestDir(),
                        protectedPaths).allowed() && record(canonical)) {
                    outside.add(canonical);
                }
            }
            return outside.isEmpty() ? answer : answer + outOfSetNote(outside);
        } catch (Exception e) {
            return action + " unavailable: " + e.getMessage();
        }
    }

    /** Stops what the language server started for this worker's checkout, if anything. */
    public void leaveLanguageServer() {
        try {
            apiLookup.leaveCheckout(worktree);
        } catch (RuntimeException e) {
            log.debug("could not stop the language server of {}: {}", worktree, e.toString());
        }
    }

    // The parameter names below are the tools' schemas (-parameters) - do not rename them.

    public String implementationsOf(String type) {
        return server("implementations_of", type);
    }

    public String supertypesOf(String type) {
        return server("supertypes_of", type);
    }

    public String callersOf(String method) {
        return server("callers_of", method);
    }

    public String findSymbol(String name) {
        return server("find_symbol", name);
    }

    public String docOf(String symbol) {
        return server("doc_of", symbol);
    }

    public String renameSymbol(String symbol, String newName) {
        return inCheckout("rename", symbol, newName);
    }

    public String organizeImports(String file) {
        return inCheckout("organize_imports", file, null);
    }

    public String problemsIn(String file) {
        return inCheckout("problems", file, null);
    }

    /** The language server's tools, in the order they go after the tree's; none when not installed. */
    private List<ToolBinding> languageServerTools() throws NoSuchMethodException {
        if (!apiLookup.languageServer()) {
            return List.of();
        }
        return List.of(
            new ToolBinding("implementations_of",
                "What implements or extends a type, or overrides Type#method: file and line "
                + "of each.",
                this, WorkerToolbox.class.getMethod("implementationsOf", String.class)),
            new ToolBinding("supertypes_of",
                "Everything a type extends or implements, nearest first, through library jars "
                + "too.",
                this, WorkerToolbox.class.getMethod("supertypesOf", String.class)),
            new ToolBinding("callers_of",
                "Every call of Type#method in the project: the calling line and the method it "
                + "is in.",
                this, WorkerToolbox.class.getMethod("callersOf", String.class)),
            new ToolBinding("find_symbol",
                "Types by part of their name, in the project and in its library jars: a "
                + "fragment (Greeter) or a pattern (*Repository). Use this instead of find or "
                + "grep when you know part of a name.",
                this, WorkerToolbox.class.getMethod("findSymbol", String.class)),
            new ToolBinding("doc_of",
                "One symbol's exact signature and its documentation, for a project symbol or a "
                + "library's: Type, Type#method or Type#field.",
                this, WorkerToolbox.class.getMethod("docOf", String.class)),
            new ToolBinding("problems_in",
                "Whether one Java file of YOUR checkout compiles as it is now, in seconds and "
                + "without a build: its errors and warnings with their lines. Call it after "
                + "you change a file. It runs no annotation processor, so the build is still "
                + "the final word.",
                this, WorkerToolbox.class.getMethod("problemsIn", String.class)),
            new ToolBinding("rename_symbol",
                "Rename a type, a method or a field of YOUR checkout everywhere it is used, in "
                + "one step: symbol is Type, Type#method (or Type#method(String, int) for one "
                + "overload) or Type#field; newName is the new simple name. Answers with the "
                + "files and lines it changed. Use this instead of editing each use by hand.",
                this, WorkerToolbox.class.getMethod("renameSymbol", String.class, String.class)),
            new ToolBinding("organize_imports",
                "Remove the unused imports of one Java file of YOUR checkout and add and sort "
                + "the ones it needs.",
                this, WorkerToolbox.class.getMethod("organizeImports", String.class)));
    }

    public String shapeOf(String type) {
        return tree("shape_of", type);
    }

    public String oneBodyOf(String member) {
        return tree("body_of", member);
    }

    public String typesIn(String where) {
        return tree("types_in", where);
    }

    public String buildOf(String module) {
        return tree("build_of", module);
    }

    public String resourcesOf(String where) {
        return tree("resources_of", where);
    }

    public String usagesOf(String what) {
        return tree("usages_of", what);
    }

    /**
     * Where the acceptance test's source comes from: a repository-relative path to the file as the
     * run's tests commit holds it, or null. Read-only by construction - it returns text and
     * nothing in this toolbox writes it anywhere.
     */
    private volatile java.util.function.Function<String, String> acceptanceSource = path -> null;

    public void setAcceptanceSource(java.util.function.Function<String, String> source) {
        this.acceptanceSource = source == null ? path -> null : source;
    }

    /**
     * The acceptance test methods this task claims and the helpers of their class that they use,
     * as the run's tests commit holds them (owner's decision 2026-10-05; section 61). Asked with
     * nothing, every claimed method; asked with a class name, a {@code Class#method} or a file
     * path, only the matching ones. The file is never shown whole and never written.
     */
    public String acceptanceTest(String which) {
        String asked = which == null ? "" : which.strip();
        boolean[] claimsAnything = new boolean[1];
        String answer = claimedBy(asked, claimsAnything);
        if (answer == null && !asked.isEmpty() && claimsAnything[0]) {
            // Asked with a name nothing claimed carries - a module, a class of the code under
            // test (live run 95: 'the-server-module' and the implementation's class name each
            // got a two-line "no match", and the worker repairing a failed journey never saw
            // the journey). What it wants is what the task must satisfy: all of it, once.
            String all = claimedBy("", new boolean[1]);
            answer = "Nothing this task claims is named `" + asked + "` - this tool takes a "
                + "test class, Class#method or a journey's name, not a module or a class of "
                + "the code. Everything the task claims:\n\n" + (all == null ? "" : all);
        }
        if (answer == null) {
            answer = "This task claims no acceptance test; there is nothing to read.\n";
        }
        answer = truncate(answer, room.chars(MAX_TOOL_OUTPUT_CHARS));
        com.swarmcoder.inference.LookupMeter.record("worker",
            com.swarmcoder.inference.LookupMeter.Kind.ACCEPTANCE_TEST, answer.length());
        logLookup("acceptance_test", asked, answer);
        return answer;
    }

    /**
     * What the task claims under {@code asked} - every claimed test and journey when it is
     * empty; null when nothing matches.
     *
     * @param claimsAnything set to whether the task claims a test or a journey at all
     */
    private String claimedBy(String asked, boolean[] claimsAnything) {
        java.util.List<String[]> claimed = new ArrayList<>();
        if (task.authoredTests() != null) {
            for (com.swarmcoder.domain.AuthoredTest test : task.authoredTests().tests()) {
                if (test.path() != null && !test.path().isBlank()) {
                    claimed.add(new String[] {test.path().replace('\\', '/'), test.testRef()});
                }
            }
        }
        java.util.Set<String> wholeFiles = new java.util.LinkedHashSet<>();
        for (String path : task.authoredTestPaths()) {
            String norm = path.replace('\\', '/');
            if (claimed.stream().noneMatch(c -> c[0].equals(norm))) {
                wholeFiles.add(norm);
            }
        }
        StringBuilder out = new StringBuilder();
        java.util.Map<String, java.util.Set<String>> byFile =
            com.swarmcoder.knowledge.AcceptanceTestRead.methodsByFile(claimed);
        for (String path : wholeFiles) {
            byFile.put(path, new java.util.LinkedHashSet<>());
        }
        for (java.util.Map.Entry<String, java.util.Set<String>> file : byFile.entrySet()) {
            String path = file.getKey();
            String simple = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\.java$", "");
            java.util.Set<String> methods = file.getValue();
            if (!asked.isEmpty()) {
                int hash = asked.indexOf('#');
                String name = hash < 0 ? asked : asked.substring(0, hash).strip();
                String method = hash < 0 ? "" : asked.substring(hash + 1).strip();
                String askedSimple = name.substring(name.lastIndexOf('/') + 1)
                    .replaceAll("\\.java$", "");
                askedSimple = askedSimple.substring(askedSimple.lastIndexOf('.') + 1);
                if (!askedSimple.equals(simple)) {
                    continue;
                }
                if (!method.isEmpty()) {
                    methods = new java.util.LinkedHashSet<>(java.util.Set.of(method));
                }
            }
            String source = acceptanceSource.apply(path);
            if (source == null) {
                out.append("`").append(path).append("` is not in the run's tests commit.\n");
                continue;
            }
            out.append(com.swarmcoder.knowledge.AcceptanceTestRead.of(path, source, methods));
        }
        // The journeys the task claims (section 63): small files, shown whole. Asked with a
        // name, only the journey of that name.
        claimsAnything[0] = !byFile.isEmpty() || !task.journeyPaths().isEmpty();
        java.util.List<String> journeysNotShown = new ArrayList<>();
        for (String claimedJourney : task.journeyPaths()) {
            String path = claimedJourney.replace('\\', '/');
            String file = path.substring(path.lastIndexOf('/') + 1);
            String name = file.endsWith(com.swarmcoder.verify.JourneyFile.SUFFIX)
                ? file.substring(0, file.length()
                    - com.swarmcoder.verify.JourneyFile.SUFFIX.length()) : file;
            if (!asked.isEmpty() && !asked.equals(path) && !asked.equals(file)
                    && !asked.equals(name)) {
                journeysNotShown.add(name);
                continue;
            }
            String source = acceptanceSource.apply(path);
            if (source == null) {
                out.append("`").append(path).append("` is not in the run's tests commit.\n");
                continue;
            }
            out.append("--- journey ").append(path).append(" ---\n")
                .append("What a person does in a browser, from the application's entry page, "
                    + "using only what is on the screen. It is made in a real browser after "
                    + "the last merge and must pass; no step loads an address, so the screen "
                    + "must be reachable by the clicks before it. You cannot change it: make "
                    + "the application match its selectors.\n")
                .append(source.strip()).append("\n\n");
        }
        if (out.length() == 0) {
            return null;
        }
        // Asked for one test class by name: the journey the task also claims is one line
        // away, not silently left out.
        for (String name : journeysNotShown) {
            out.append("This task also claims the journey `").append(name)
                .append("` - what a person does on the screen, made in a real browser after "
                    + "the last merge: acceptance_test('").append(name).append("') shows it.\n");
        }
        return out.toString();
    }

    public String docOutline(String document) {
        return tree("doc_outline", document);
    }

    public String docSection(String section) {
        return tree("doc_section", section);
    }

    public String docSearch(String words) {
        return tree("doc_search", words);
    }

    /** Tool bindings for {@code AgentRuntime.SessionSpec}; report_done's name is the loop's stop signal. */
    public List<ToolBinding> bindings() {
        try {
            Method shapeOf = WorkerToolbox.class.getMethod("shapeOf", String.class);
            Method bodyOf = WorkerToolbox.class.getMethod("oneBodyOf", String.class);
            Method typesIn = WorkerToolbox.class.getMethod("typesIn", String.class);
            Method buildOf = WorkerToolbox.class.getMethod("buildOf", String.class);
            Method resourcesOf = WorkerToolbox.class.getMethod("resourcesOf", String.class);
            Method usagesOf = WorkerToolbox.class.getMethod("usagesOf", String.class);
            Method acceptanceTest = WorkerToolbox.class.getMethod("acceptanceTest", String.class);
            Method docOutline = WorkerToolbox.class.getMethod("docOutline", String.class);
            Method docSection = WorkerToolbox.class.getMethod("docSection", String.class);
            Method docSearch = WorkerToolbox.class.getMethod("docSearch", String.class);
            Method replaceMember = WorkerToolbox.class.getMethod("replaceMember", String.class,
                String.class);
            Method addMember = WorkerToolbox.class.getMethod("addMember", String.class,
                String.class);
            Method removeMember = WorkerToolbox.class.getMethod("removeMember", String.class);
            Method exec = WorkerToolbox.class.getMethod("exec", String.class);
            Method read = WorkerToolbox.class.getMethod("read", String.class);
            Method applyDiff = WorkerToolbox.class.getMethod("applyDiff", String.class);
            Method writeFile = WorkerToolbox.class.getMethod("writeFile", String.class, String.class);
            Method lookupApi = WorkerToolbox.class.getMethod("lookupApi", String.class);
            Method reportDone = WorkerToolbox.class.getMethod("reportDone", String.class);
            Method askExpert = WorkerToolbox.class.getMethod("askExpert", String.class,
                String.class);
            Method requestSkeleton = WorkerToolbox.class.getMethod("requestSkeleton", String.class);
            Method disputeRule = WorkerToolbox.class.getMethod("disputeRule", String.class,
                String.class, String.class);
            Method findExample = WorkerToolbox.class.getMethod("findExample", String.class,
                String.class);
            List<ToolBinding> server = languageServerTools();
            List<ToolBinding> tools = new ArrayList<>(List.of(
                // The project's syntax tree first: this is how a worker learns the project.
                new ToolBinding("acceptance_test",
                    "The acceptance test this task must satisfy, read-only: the test methods it "
                    + "claims and the helpers of their class that they use, as committed for "
                    + "this run. Read it BEFORE you write code - the rules your code must meet "
                    + "are in it. Pass nothing for every claimed method, or a class name or "
                    + "Class#method to narrow. When the task claims a journey - what a person does "
                    + "on the screen, made in a real browser after the last merge - it is shown "
                    + "too, whole. Earlier stories' tests are ordinary files of your "
                    + "checkout (body_of, read). You cannot change any of them.",
                    this, acceptanceTest),
                new ToolBinding("shape_of",
                    "START HERE to learn about a type of this project or its reference "
                    + "material: its members with the types the compiler resolved, what it "
                    + "extends, and its file and line. A few hundred characters where the file "
                    + "is thousands. A type of your checkout that is new or changed since "
                    + "the tree was built - yours, or an earlier task's of this run - is "
                    + "answered from the file as it is NOW."
                    + (server.isEmpty() ? "" : " It lists a type that exists only in a library "
                        + "jar too, with its real signatures."),
                    this, shapeOf),
                new ToolBinding("body_of",
                    "The text of ONE method, constructor, field or type, taken out by name with "
                    + "its file and lines: Type#method, Type#Type for a constructor, "
                    + "Type#field, Type#first,second for several members, or Type for the whole "
                    + "type as code without comments and imports. A file of your checkout is "
                    + "answered as it is NOW, your own changes included. Read code this way, "
                    + "instead of reading the file.",
                    this, bodyOf),
                new ToolBinding("types_in",
                    "What a package, a module or a folder contains: its types, each on one "
                    + "line with its kind, what it extends and its file and line; files of "
                    + "your checkout that are new or changed are listed after them. Use this "
                    + "instead of find, ls or grep to learn where things are.",
                    this, typesIn),
                new ToolBinding("usages_of",
                    "Where a type, or Type#method, is used: file and line of each use. Use this "
                    + "instead of grep.",
                    this, usagesOf),
                new ToolBinding("build_of",
                    "What a module's build file declares - coordinates, parent, modules, "
                    + "properties, dependencies (test-scope ones with resolved versions), "
                    + "plugins with their configuration - without the file. Give the module "
                    + "folder; an empty string lists every build file.",
                    this, buildOf),
                new ToolBinding("resources_of",
                    "A module's resource files (beans.xml, persistence descriptors, property "
                    + "files): the module lists them with sizes; <module>/<path under the "
                    + "resources folder> gives one file when it is small, its outline when it "
                    + "is large; add #<element, key or L10-40> for one part.",
                    this, resourcesOf),
                new ToolBinding("doc_outline",
                    "The written documents of the project and its reference material by their "
                    + "structure. An empty string: every document with its sections. A "
                    + "document's path: each heading with its number, lines and size.",
                    this, docOutline),
                new ToolBinding("doc_section",
                    "ONE section of a document: <document>#<section number or heading>, as "
                    + "doc_outline lists them. Read a document this way, not whole.",
                    this, docSection),
                new ToolBinding("doc_search",
                    "Which sections of which documents are about a subject (any words): "
                    + "document, line, section number and heading of each.",
                    this, docSearch),
                new ToolBinding("replace_member",
                    "CHANGE ONE method, constructor or field of a Java file of your checkout by "
                    + "its name - without reading the file and without a diff. member is "
                    + "Type#name (or <path>#name; Type#name:LINE for one overload); newText is "
                    + "the complete new declaration: annotations, signature and body. Import "
                    + "lines put before it are added to the file's imports. Its documentation "
                    + "comment stays. Only files inside your write set may be modified.",
                    this, replaceMember),
                new ToolBinding("add_member",
                    "ADD a method, a field or a nested type to a type of your checkout, before "
                    + "its closing brace - without reading the file. type is the Type (or "
                    + "<path>, or <path>#Type); text is the complete declaration, with any "
                    + "import lines it needs put before it.",
                    this, addMember),
                new ToolBinding("remove_member",
                    "DELETE ONE method, constructor, field or nested type of a Java file of "
                    + "your checkout by its name, with its documentation comment - without "
                    + "reading the file and without a diff. member is Type#name (or "
                    + "<path>#name; Type#name:LINE for one overload). The answer names the "
                    + "members of the file that still use it.",
                    this, removeMember),
                new ToolBinding("exec",
                    "Run a shell command in the repository working directory - to build and to "
                    + "run tests. Returns exit code and output.",
                    this, exec),
                new ToolBinding("read",
                    "LAST RESORT for learning the project: body_of returns one member, shape_of "
                    + "a type's members, doc_section one section of a document. "
                    + "Read a file by repository-relative path (or a reference document as "
                    + "/reference/<root>/<path>). Returns its content; a file too long for one "
                    + "read comes back in part and says how to read on. To read only what you "
                    + "need, add :FROM-TO to the path for a line range (src/App.java:120-180) or "
                    + "#name for one method or type (src/App.java#save). Any file may be read "
                    + "whole when the whole file is what you need. "
                    + "Paths outside your checkout and the reference material are refused.",
                    this, read),
                new ToolBinding("apply_diff",
                    "Apply a unified diff to the repository - for a change replace_member and "
                    + "add_member cannot make (a build file, a resource, several places at "
                    + "once). Only files inside your write set may be modified.",
                    this, applyDiff),
                new ToolBinding("write_file",
                    "Write a NEW file, or replace one file's ENTIRE content (repository-relative "
                    + "path + full new content). To change part of an existing Java file use "
                    + "replace_member or add_member instead: they need no read of the file. "
                    + "Only files inside your write set may be modified.",
                    this, writeFile),
                new ToolBinding("lookup_api",
                    "Look up how to use a class, API or topic (e.g. \"FormLayout\", \"routing\", "
                    + "\"jackson ObjectMapper.readValue\"). Searches this project's reference "
                    + "documentation AND any configured documentation server, so it answers about "
                    + "third-party libraries too. USE THIS INSTEAD of decompiling or unpacking a "
                    + "jar (javap, jar xf) — those cost many turns and tell you less. One word "
                    + "works best: a class name or a topic.",
                    this, lookupApi),
                new ToolBinding("ask_expert",
                    "ASK FOR HELP when an API or a framework behaviour is beyond you — this is "
                    + "what it is for and using it is not a failure. Give the exact question "
                    + "(\"how do I save a changed list so it survives a restart\") and what you "
                    + "already tried. You get back real code from this project that makes that "
                    + "call, its imports, and the build line if one is missing. Use it INSTEAD of "
                    + "unpacking or disassembling a jar, which is refused.",
                    this, askExpert),
                new ToolBinding("request_skeleton",
                    "ASK FOR A STARTING POINT when you do not know how to begin a file. Give the "
                    + "type name your task must deliver (or describe it). You get back a version "
                    + "that compiles, with this project's own annotations, imports and injected "
                    + "fields already on it and every method body a TODO for you to fill in.",
                    this, requestSkeleton),
                new ToolBinding("find_example",
                    "ONE WHOLE FILE OF REAL CODE that already does the kind of thing you are "
                    + "writing, chosen by which types it uses. In `what`, name the library and "
                    + "project types involved (class names) and a few words on what the code "
                    + "does; `kind` is \"implementation\" or \"test\". Use it when you know what "
                    + "to build and want to see how this project builds it.",
                    this, findExample),
                new ToolBinding("dispute_rule",
                    "DISPUTE A PROJECT RULE, WITH EVIDENCE — only when the rule cannot be met here "
                    + "or is wrong for this case, never to skip work. Give the rule exactly as your "
                    + "brief names it, why, and the evidence: a compiler or test output excerpt, "
                    + "an excerpt of the library's own source or documentation, or a file path. "
                    + "The judge reads it next to your code.",
                    this, disputeRule),
                new ToolBinding("report_done",
                    "Call exactly once, when the task is complete and verified, with a short summary of the change.",
                    this, reportDone)));
            // The language server's queries sit with the tree's, before the documents, the
            // member edits, exec and read
            // (CLAUDE.md section 1: map, tree and language-server queries, search, whole file).
            int afterTree = 0;
            while (afterTree < tools.size()
                    && !tools.get(afterTree).name().equals("doc_outline")) {
                afterTree++;
            }
            tools.addAll(afterTree, server);
            return List.copyOf(tools);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Rewrites {@code diff --git}, {@code ---}, {@code +++} header paths to repo-relative.
     * Byte-preserving: non-header lines and header paths that are already relative (or
     * {@code /dev/null}) come out identical — only an actual absolute path is changed.
     */
    private String relativizeDiffHeaders(String diff) {
        if (diff == null) {
            return "";
        }
        String[] lines = diff.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = rewriteHeaderLine(lines[i]);
        }
        return String.join("\n", lines);
    }

    private String rewriteHeaderLine(String line) {
        if (line.startsWith("--- ") || line.startsWith("+++ ")) {
            String prefix = line.substring(0, 4);
            String path = line.substring(4);
            if (path.trim().equals("/dev/null")) {
                return line;
            }
            String marker = "";
            String trimmed = path.trim();
            if (trimmed.startsWith("a/") || trimmed.startsWith("b/")) {
                marker = trimmed.substring(0, 2);
                trimmed = trimmed.substring(2);
            }
            String rel = toRelative(trimmed);
            // Null: an absolute path outside the checkout. Left as it is, so the path check that
            // follows refuses the diff by name rather than applying it somewhere it did not say.
            return rel == null || rel.equals(trimmed) ? line : prefix + marker + rel;
        }
        if (line.startsWith("diff --git ")) {
            String[] parts = line.split(" ");
            StringBuilder sb = new StringBuilder("diff --git");
            boolean changed = false;
            for (int i = 2; i < parts.length; i++) {
                String p = parts[i];
                String marker = p.startsWith("a/") || p.startsWith("b/") ? p.substring(0, 2) : "";
                String body = marker.isEmpty() ? p : p.substring(2);
                String rel = toRelative(body);
                if (rel == null) {
                    rel = body;
                }
                changed |= !rel.equals(body);
                sb.append(' ').append(marker).append(rel);
            }
            return changed ? sb.toString() : line;
        }
        return line;
    }

    /** Paths a unified diff writes to, from its +++ headers. */
    public static List<String> touchedPaths(String unifiedDiff) {
        List<String> paths = new ArrayList<>();
        for (String line : unifiedDiff.split("\n")) {
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).trim();
                if (path.startsWith("b/")) {
                    path = path.substring(2);
                }
                if (!path.equals("/dev/null") && !path.isBlank()) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    private static String truncate(String text) {
        return truncate(text, MAX_TOOL_OUTPUT_CHARS);
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n[truncated " + (text.length() - maxChars) + " chars]";
    }
}
