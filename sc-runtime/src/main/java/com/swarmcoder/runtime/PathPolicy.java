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
package com.swarmcoder.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The single place that decides whether a worker may write to a path.
 *
 * <p>It exists because the answer used to be computed in three different ways in two different
 * places, and the differences between them were exploitable. The rules are:
 *
 * <ol>
 *   <li><b>Canonicalise first, then match.</b> The write-set check used to run on the raw string
 *       while the escape check ran on the normalised path, so {@code src/../pom.xml} passed both
 *       with a write set of {@code ["src"]} — outside the write set, and the violation counter
 *       never moved.</li>
 *   <li><b>Resolve symlinks.</b> {@link Path#normalize()} is lexical: it happily walks a symlink
 *       that points out of the worktree. Nothing in this codebase called {@code toRealPath}.</li>
 *   <li><b>Protected paths win over the write set.</b> A path may be inside the write set and
 *       still be refused.</li>
 * </ol>
 *
 * <p>The same decision is applied again at integration, against the paths a candidate's diff
 * actually touched — because a worker's shell can write files no tool ever checked, and
 * {@code git add -A} then sweeps them into the candidate diff.
 */
public final class PathPolicy {

    /**
     * Never writable by a worker, whatever the write set says.
     *
     * <p>{@code .swarmcoder/} holds {@code verify.yaml} — the commands the orchestrator runs to
     * decide whether the work passed, executed on the HOST at integration and never sandboxed. A
     * worker able to edit it can both certify itself green and run arbitrary code on the operator's
     * machine. {@code .git/} is the repository's own state, including hooks, which are executable.
     */
    public static final List<String> ALWAYS_PROTECTED = List.of(".swarmcoder/", ".git/");

    /**
     * The marker a source file uses to declare itself unmodifiable.
     *
     * <p>Collected from the BASE COMMIT, never the working tree — see
     * {@code GitService.grepFilesAtRef}. A guard the guarded thing can delete is not a guard.
     */
    public static final String LOCK_MARKER = "swarmcoder:locked";

    /**
     * The code this system's own guarantees rest on.
     *
     * <p>These are locked by default whenever the swarm edits a repository that contains them,
     * because they are categorically different from "a module I care about": a worker that modifies
     * the path policy, the verification loader, the secret scanner or the integration audit does not
     * merely break a feature — it disables the checks that would have caught it doing so. The
     * failure is silent and self-concealing, which is exactly the shape of failure a default should
     * prevent rather than leave to someone remembering to configure it.
     *
     * <p>Locking a path that does not exist costs nothing, so this applies unconditionally rather
     * than trying to detect "is this SwarmCoder's own repo" — for any other project these paths
     * simply match nothing. Override with {@code unlockTrustKernel: true}, which is logged loudly.
     */
    public static final List<String> TRUST_KERNEL = List.of(
        "sc-runtime/src/main/java/com/swarmcoder/runtime/PathPolicy.java",
        "sc-verify/src/main/java/com/swarmcoder/verify/VerifySpecLoader.java",
        "sc-verify/src/main/java/com/swarmcoder/verify/SecretScanner.java",
        "sc-workflow/src/main/java/com/swarmcoder/workflow/FinalIntegrator.java",
        "sc-workflow/src/main/java/com/swarmcoder/workflow/CriterionEvidence.java",
        "sc-swarm/src/main/java/com/swarmcoder/swarm/WorkerToolbox.java");

    private PathPolicy() {}

    /**
     * The outcome of checking one path: allowed, or refused with a reason for the model.
     *
     * <p><b>Two kinds of refusal, and only one of them stops a worker (2026-09-02).</b> Until now
     * every refusal was the same thing and the worker was killed on its second one. On the
     * operator's live run that killed three of thirteen candidates, and not one of them was
     * misbehaving: two were re-creating a model class the parallel task had not delivered yet, two
     * were scratch files written to check their own work, and one was the EclipseStore data file
     * that the candidate's OWN test run produced when it started the service it had just written.
     * The last of those died at turn 102 — an hour of work thrown away for running its own code.
     * Killing also destroyed the evidence: nobody could see afterwards whether the worker had
     * scribbled everywhere or reached one file it legitimately needed.
     *
     * <p>So the two are now separated:
     *
     * <ul>
     *   <li><b>Lethal.</b> The path escapes the repository, or lands in {@link #ALWAYS_PROTECTED}
     *       ({@code .git/}, {@code .swarmcoder/}), in an operator-locked module, or in the task's
     *       acceptance-test directory. Refused, never written, counted toward the kill — unchanged.
     *       A worker that can edit the test that judges it, or the verification commands the host
     *       runs unsandboxed, defeats every check in the system at once, and no amount of "it was
     *       probably trying to help" makes that safe.</li>
     *   <li><b>Outside the write set.</b> The write set is decided by the architect from a plan
     *       before any code exists, and it is routinely wrong about which files a task genuinely
     *       spans. The write is ALLOWED, the path is RECORDED on the candidate, and the judge,
     *       selection and the operator's run graph are all shown it. One neighbouring file touched
     *       to do the job is usable with a note; half the repository rewritten is obviously bad —
     *       and that is a judgement to make where the diff can be seen, not a rule to enforce at
     *       turn 10 with the evidence thrown away.</li>
     * </ul>
     */
    public record Verdict(String reason, boolean lethal) {
        public boolean allowed() {
            return reason == null;
        }

        public static final Verdict ALLOWED = new Verdict(null, false);

        /** A refusal that must stop the write, and on repetition the worker. */
        public static Verdict refused(String reason) {
            return new Verdict(reason, true);
        }

        /** A write outside the task's declared slice: allowed, recorded, never fatal. */
        public static Verdict outsideWriteSet(String reason) {
            return new Verdict(reason, false);
        }
    }

    /**
     * Canonical repo-relative form of {@code rawPath}, or null when it resolves outside {@code root}.
     *
     * <p>Resolves symlinks for the deepest existing ancestor, so a link inside the worktree
     * pointing elsewhere is caught rather than followed.
     */
    public static String canonicalize(Path root, String rawPath) {
        if (root == null || rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path base = realOrNormalized(root.toAbsolutePath());
        Path target;
        try {
            Path candidate = Path.of(rawPath.replace('\\', '/'));
            target = candidate.isAbsolute() ? candidate.normalize()
                : base.resolve(candidate).normalize();
        } catch (Exception e) {
            return null; // not a usable path on this platform
        }
        Path resolved = realOrNormalized(target);
        if (!resolved.startsWith(base)) {
            return null;
        }
        return base.relativize(resolved).toString().replace('\\', '/');
    }

    /**
     * The real path of the deepest existing ancestor, with the remainder appended.
     *
     * <p>{@code toRealPath} throws when the file does not exist yet, which is the normal case for
     * a new file, so walking up to the nearest existing parent is what makes symlink resolution
     * work for writes as well as edits.
     */
    private static Path realOrNormalized(Path path) {
        Path normalized = path.normalize();
        Path existing = normalized;
        List<String> trailing = new ArrayList<>();
        // NOFOLLOW: a dangling link must count as existing, or a write through it would be
        // taken for a new file and create the link's target, outside the worktree.
        while (existing != null
                && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name != null) {
                trailing.add(0, name.toString());
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            return normalized;
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (Exception e) {
            if (Files.isSymbolicLink(existing)) {
                return Path.of("unresolvable-link"); // relative: inside no root
            }
            real = existing.normalize();
        }
        for (String segment : trailing) {
            real = real.resolve(segment);
        }
        return real.normalize();
    }

    /**
     * Decides a single CANONICAL repo-relative path.
     *
     * @param relPath           canonical form from {@link #canonicalize}; null means it escaped
     * @param writeSet          the task's declared write set; empty means unrestricted
     * @param acceptanceTestDir the task's protected acceptance-test directory, may be null
     * @param protectedPaths    operator-declared locked-down paths, may be null
     */
    public static Verdict check(String relPath, Collection<String> writeSet,
                                String acceptanceTestDir, Collection<String> protectedPaths) {
        if (relPath == null) {
            return Verdict.refused("path escapes the repository");
        }
        String path = relPath.replace('\\', '/');

        for (String prefix : ALWAYS_PROTECTED) {
            if (covers(prefix, path)) {
                return Verdict.refused(path + " is protected and can never be modified — "
                    + prefix + " controls how this project is built and verified");
            }
        }
        if (protectedPaths != null) {
            for (String entry : protectedPaths) {
                if (entry != null && !entry.isBlank() && covers(entry.replace('\\', '/'), path)) {
                    return Verdict.refused(path + " is in a locked module (" + entry.trim()
                        + ") and may not be modified");
                }
            }
        }
        if (acceptanceTestDir != null && !acceptanceTestDir.isBlank()
                && covers(acceptanceTestDir.replace('\\', '/'), path)) {
            return Verdict.refused("acceptance tests are protected — workers may not modify " + path);
        }
        if (writeSet == null || writeSet.isEmpty()) {
            // No write set declared: unrestricted, as before. The PLAN invariants are what make
            // this rare; tightening it here would reject every task an older planner produced.
            return Verdict.ALLOWED;
        }
        for (String entry : writeSet) {
            if (entry != null && covers(entry.replace('\\', '/'), path)) {
                return Verdict.ALLOWED;
            }
        }
        // NOT lethal — see Verdict. The write happens and the path is recorded.
        return Verdict.outsideWriteSet(path + " is outside your write set " + writeSet);
    }

    /** Prefix containment on whole path segments, so "src" never matches "srcgen/x". */
    private static boolean covers(String prefix, String path) {
        String clean = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        return clean.equals(path) || path.startsWith(clean + "/");
    }

    /**
     * Checks a whole set of touched paths, returning every refusal.
     *
     * <p>Used at integration against the paths a candidate's diff actually changed. Tool-level
     * checks cannot see what a worker's shell did, and {@code git add -A} sweeps all of it into
     * the diff, so this is the last point at which an out-of-bounds change can be caught before it
     * reaches the integration branch.
     */
    public static List<String> auditAll(Collection<String> relPaths, Collection<String> writeSet,
                                        String acceptanceTestDir, Collection<String> protectedPaths) {
        List<String> refusals = new ArrayList<>();
        if (relPaths == null) {
            return refusals;
        }
        for (String path : relPaths) {
            Verdict verdict = check(path == null ? null : path.replace('\\', '/'),
                writeSet, acceptanceTestDir, protectedPaths);
            if (!verdict.allowed()) {
                refusals.add(verdict.reason());
            }
        }
        return refusals;
    }

    /**
     * The same audit, split by whether each finding must stop the run.
     *
     * @param blocking     reasons that park integration: escapes, protected directories, locked
     *                     modules, the acceptance tests
     * @param outOfWriteSet the PATHS (not sentences) a diff changed outside the task's slice —
     *                     recorded and reported, never a reason to park on their own
     */
    public record Audit(List<String> blocking, List<String> outOfWriteSet) {
        public boolean clean() {
            return blocking.isEmpty() && outOfWriteSet.isEmpty();
        }
    }

    /** {@link #auditAll} with the two kinds of finding kept apart. */
    public static Audit audit(Collection<String> relPaths, Collection<String> writeSet,
                              String acceptanceTestDir, Collection<String> protectedPaths) {
        List<String> blocking = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        if (relPaths == null) {
            return new Audit(blocking, outside);
        }
        for (String path : relPaths) {
            String clean = path == null ? null : path.replace('\\', '/');
            Verdict verdict = check(clean, writeSet, acceptanceTestDir, protectedPaths);
            if (verdict.allowed()) {
                continue;
            }
            if (verdict.lethal()) {
                blocking.add(verdict.reason());
            } else if (clean != null && !outside.contains(clean)) {
                outside.add(clean);
            }
        }
        return new Audit(blocking, outside);
    }
}
