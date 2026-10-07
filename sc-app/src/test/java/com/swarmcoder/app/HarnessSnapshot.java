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
package com.swarmcoder.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.swarmcoder.domain.Project;
import com.swarmcoder.store.ArtifactStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The live harness's run, saved at the moment it was about to dispatch its workers, and put back
 * so a later run can start there.
 *
 * <h2>Why (2026-09-25)</h2>
 *
 * <p>On DeepSeek V4 Flash (about 28 tokens a second, long reasoning) everything before the workers
 * — the fixture, ingest, rules, requirements, agreement, stories, the design, its review, the plan
 * and the acceptance tests — takes 20 to 40 minutes, and it comes out nearly the same every run.
 * The work being iterated on is the workers. {@code -Dswarmcoder.e2e.saveAtBuild=<dir>} writes one
 * of these during an ordinary full walk; {@code -Dswarmcoder.e2e.resumeFrom=<dir>} starts a walk
 * from it and skips straight to the build.
 *
 * <h2>What is in one</h2>
 *
 * <pre>
 * &lt;dir&gt;/manifest.json   which run, which project, which SwarmCoder commit made it, when, which
 *                       model, and every link walked before the seam with what was measured there
 * &lt;dir&gt;/store/          the ArtifactStore, as EclipseStore's own backup of the open store
 * &lt;dir&gt;/repo/           the fixture repository — working tree and .git, branches, the run's
 *                       tests commit — minus its linked-worktree registrations
 * </pre>
 *
 * <p><b>The store is backed up, never file-copied</b> — see {@link ArtifactStore#backupTo}. It is
 * taken on the workflow thread at the dispatch seam ({@link DispatchSeam}), queued behind every
 * write the run had made, and executed by EclipseStore's storage channels as one task.
 *
 * <p><b>The repository is file-copied</b>, and that is safe for the same reason the seam was
 * chosen: at that moment nothing is writing to it. The only thing driving the run is the thread the
 * copy runs on, the wizards finished long ago, and the test-authoring stage removed its own
 * worktrees before handing the run to EXECUTING.
 *
 * <p><b>The reference documentation index is NOT saved.</b> {@link HarnessReferenceRoot} builds it
 * from the reference checkout and the project root with no model call, so it is rebuilt on resume
 * exactly as a full walk builds it, against the restored repository — a copied index would carry
 * the saved run's absolute paths.
 *
 * <h2>Linked worktrees</h2>
 *
 * <p>Git records a linked worktree twice, both times by ABSOLUTE path: the repository's
 * {@code .git/worktrees/<name>/gitdir} names the checkout, and the checkout's {@code .git} file
 * names the repository. The product puts every checkout under {@code ~/.swarmcoder/wt}, keyed by
 * run id. A restored repository that still carried the saved run's registrations would believe it
 * owned the ORIGINAL run's checkouts — and the product's first move before reusing a name is
 * {@code git worktree remove --force}, which would delete a directory belonging to another copy of
 * the run. So registrations are never carried: the snapshot is written without
 * {@code .git/worktrees} (what was registered is listed in the manifest), and a restore removes any
 * that are present anyway. Nothing the run needs lives in one — every stage cuts its own checkout
 * when it needs one, and at the seam every earlier stage has removed its own.
 *
 * <p>The other half is {@link #clearLeftovers}: checkouts under the worktree root named with this
 * run's id, left there by an earlier resume of the same snapshot (or by the run that saved it)
 * whose repository has since been deleted with its test's temp directory. The product reuses those
 * names (the progress branch's {@code progress-<runId>}, a test repair's {@code tests-<runId>}),
 * and {@code git worktree add} refuses a folder that is already there. Without clearing them the
 * second resume of a snapshot would fail where the first succeeded.
 *
 * <h2>Repeatable</h2>
 *
 * <p>A restore copies out of the snapshot and never writes into it; the store is opened from the
 * copy. A save writes to a sibling {@code .partial-*} directory and renames it into place only
 * when everything including the manifest is written, so a save that died halfway can never be
 * mistaken for a whole one, and an existing snapshot is never overwritten.
 */
final class HarnessSnapshot {

    static final String SAVE_PROPERTY = "swarmcoder.e2e.saveAtBuild";
    static final String RESUME_PROPERTY = "swarmcoder.e2e.resumeFrom";
    /**
     * A directory that gets one sub-folder per {@link RestartPoint} as the run passes it
     * ({@code 1-requirements}, {@code 2-design}, {@code 3-plan}, {@code 4-build}). The build point
     * also has its own older property, {@link #SAVE_PROPERTY}, which names that one folder directly.
     */
    static final String SAVE_AT_PROPERTY = "swarmcoder.e2e.saveAt";

    /** Bumped when the layout changes; a restore refuses a format it does not know. */
    static final int FORMAT = 1;

    static final String MANIFEST = "manifest.json";
    static final String STORE = "store";
    static final String REPO = "repo";

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private HarnessSnapshot() {
    }

    /** One link walked before the seam, with what was measured at it, verbatim. */
    record Link(String name, String observation) {
    }

    /**
     * Everything a resumed walk needs to know that is not in the store or the repository, and
     * everything a reader needs to judge whether the snapshot still describes today's code.
     *
     * @param swarmcoderCommit      HEAD of the SwarmCoder checkout that ran the walk
     * @param swarmcoderUncommitted files that checkout had changed and not committed — the code that
     *                              made the snapshot is the commit PLUS these
     * @param fixtureOriginHead     HEAD of {@code dev/bookshelf-demo} when the fixture was cloned
     * @param fixtureBaseCommit     the fixture's baseline commit, the "before" of the merged diff
     * @param links                 the links walked and held before the seam, in chain order
     * @param worktreesNotCarried   linked worktrees registered in the repository at the seam, which
     *                              the snapshot deliberately does not carry
     * @param point                 the {@link RestartPoint#folder()} this snapshot was taken at;
     *                              null in a snapshot from before there were several (the build point)
     * @param businessDocSha256     SHA-256 of the business document the saving run was given, or null
     * @param technicalDocSha256    SHA-256 of the technical document the saving run was given, or null
     * @param resumedFrom           when the saving run was itself resumed from a snapshot, which one
     *                              (its {@link #provenance}); null for a run walked from the start.
     *                              Links 1 to the resume point were restored, not walked, by that run
     */
    record Manifest(int format, String savedAt, UUID runId, UUID projectId, UUID storyId,
                    String swarmcoderCommit, List<String> swarmcoderUncommitted,
                    String baseUrl, String model, String shape, int workers,
                    String fixtureOrigin, String fixtureOriginHead, String fixtureBaseCommit,
                    String testsCommit, String referenceRoot,
                    List<Link> links, List<String> worktreesNotCarried,
                    String point, String businessDocSha256, String technicalDocSha256,
                    String resumedFrom) {

        /** A manifest from a run walked from the start: nothing was resumed. */
        Manifest(int format, String savedAt, UUID runId, UUID projectId, UUID storyId,
                 String swarmcoderCommit, List<String> swarmcoderUncommitted,
                 String baseUrl, String model, String shape, int workers,
                 String fixtureOrigin, String fixtureOriginHead, String fixtureBaseCommit,
                 String testsCommit, String referenceRoot,
                 List<Link> links, List<String> worktreesNotCarried,
                 String point, String businessDocSha256, String technicalDocSha256) {
            this(format, savedAt, runId, projectId, storyId, swarmcoderCommit,
                swarmcoderUncommitted, baseUrl, model, shape, workers, fixtureOrigin,
                fixtureOriginHead, fixtureBaseCommit, testsCommit, referenceRoot, links,
                worktreesNotCarried, point, businessDocSha256, technicalDocSha256, null);
        }

        /** A manifest from before restart points: the build point, no document hashes. */
        Manifest(int format, String savedAt, UUID runId, UUID projectId, UUID storyId,
                 String swarmcoderCommit, List<String> swarmcoderUncommitted,
                 String baseUrl, String model, String shape, int workers,
                 String fixtureOrigin, String fixtureOriginHead, String fixtureBaseCommit,
                 String testsCommit, String referenceRoot,
                 List<Link> links, List<String> worktreesNotCarried) {
            this(format, savedAt, runId, projectId, storyId, swarmcoderCommit,
                swarmcoderUncommitted, baseUrl, model, shape, workers, fixtureOrigin,
                fixtureOriginHead, fixtureBaseCommit, testsCommit, referenceRoot, links,
                worktreesNotCarried, null, null, null, null);
        }

        /** The restart point this snapshot was taken at. */
        RestartPoint restartPoint() {
            return RestartPoint.named(point);
        }

        /** One line naming where a restored observation came from. */
        String provenance(Path dir) {
            return "snapshot " + dir + " (" + restartPoint().folder() + ": "
                + restartPoint().description() + "), saved " + savedAt + " by SwarmCoder "
                + EndToEndLoopTest.shortSha(swarmcoderCommit)
                + (swarmcoderUncommitted == null || swarmcoderUncommitted.isEmpty() ? ""
                    : " plus " + swarmcoderUncommitted.size() + " uncommitted file(s)")
                + " on " + model;
        }
    }

    /** Where a restore put things. */
    record Restored(Path store, Path repo, Manifest manifest, List<String> cleared) {
    }

    /** The directory named by {@code -Dswarmcoder.e2e.saveAtBuild}, or null. */
    static Path saveTarget() {
        return pathProperty(SAVE_PROPERTY);
    }

    /** The directory named by {@code -Dswarmcoder.e2e.resumeFrom}, or null. */
    static Path resumeSource() {
        return pathProperty(RESUME_PROPERTY);
    }

    /**
     * Where each point is to be saved: {@code <saveAt>/<folder>} for every point when
     * {@code -Dswarmcoder.e2e.saveAt} is given, and the build point at
     * {@code -Dswarmcoder.e2e.saveAtBuild} when that is (it wins for the build point).
     */
    static java.util.Map<RestartPoint, Path> savePlan() {
        java.util.Map<RestartPoint, Path> plan = new java.util.EnumMap<>(RestartPoint.class);
        Path root = pathProperty(SAVE_AT_PROPERTY);
        if (root != null) {
            for (RestartPoint point : RestartPoint.values()) {
                plan.put(point, root.resolve(point.folder()));
            }
        }
        Path build = saveTarget();
        if (build != null) {
            plan.put(RestartPoint.BUILD, build);
        }
        return plan;
    }

    /**
     * The saves a run resumed from {@code resumedAt} still makes: every point AFTER it. The point it
     * resumed from, and every earlier one, already exist (that is where it started) and are never
     * rewritten by it.
     */
    static java.util.Map<RestartPoint, Path> savePlanAfter(java.util.Map<RestartPoint, Path> plan,
                                                           RestartPoint resumedAt) {
        java.util.Map<RestartPoint, Path> later = new java.util.EnumMap<>(RestartPoint.class);
        plan.forEach((point, target) -> {
            if (point.ordinal() > resumedAt.ordinal()) {
                later.put(point, target);
            }
        });
        return later;
    }

    /** One requirement the snapshot's story was planned from. */
    record AgreedRequirement(String handle, String title, String text) {

        String label() {
            return handle + " '" + title + "'";
        }

        boolean matches(String needle) {
            return ((title == null ? "" : title) + " " + (text == null ? "" : text))
                .toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * A resumed walk builds the snapshot's story: its requirement was agreed, its story planned and
     * its design, plan and tests made from it, and none of that can be swapped for another
     * requirement's without walking the stages again. So when
     * {@code -Dswarmcoder.e2e.requirement=<text>} names a requirement and the snapshot's agreed one
     * is a different one, the walk must stop at the start and say so, never build the snapshot's
     * story under a request for another (it used to).
     *
     * <p>Matching is the full walk's own: the text is a substring of the requirement's title and
     * text, any case.
     *
     * @param wanted the requested text; null or blank asks for nothing in particular
     * @param agreed the requirements the snapshot's story was planned from
     * @return null when the walk may go on; otherwise the message to stop it with
     */
    static String requirementMismatch(String wanted, List<AgreedRequirement> agreed,
                                      RestartPoint point, Path from) {
        if (wanted == null || wanted.isBlank()) {
            return null;
        }
        if (agreed.stream().anyMatch(requirement -> requirement.matches(wanted.strip()))) {
            return null;
        }
        return "-Dswarmcoder.e2e.requirement=" + wanted.strip() + " asks for a requirement that is "
            + "not the one the snapshot " + from + " (" + point.folder() + ") was made for. Its "
            + "story was planned from "
            + (agreed.isEmpty() ? "no requirement that can be read"
                : agreed.stream().map(AgreedRequirement::label).toList().toString())
            + ", and its design, plan and tests follow from that; a snapshot cannot be moved to "
            + "another requirement. Nothing was built. Either drop the property to build the "
            + "snapshot's own story, resume a snapshot saved for the requested one, or walk from "
            + "the start with the property set.";
    }

    /**
     * Which drafted requirement {@code -Dswarmcoder.e2e.requirement=<text>} pins a full walk to.
     *
     * @param index   the drafted requirement to agree; -1 when nothing was requested (the walk
     *                chooses) or when the request cannot be met
     * @param failure the message to stop the walk with; null when it may go on
     * @param note    something to say on the way; null for nothing
     */
    record Pin(int index, String failure, String note) {}

    /**
     * A requested requirement is the one that is built, or the walk stops (live run 75,
     * 2026-10-03: pinned to "sorting", the analyst titled that requirement "Show all contacts in a
     * sortable table", the text matched nothing, and the walk silently agreed another requirement
     * and spent 212 minutes on it).
     *
     * <p>The text is a substring, any case, of a drafted requirement's title, text or checks.
     * Matching none stops the walk with the drafted titles in the message; matching several takes
     * the first in document order and says so.
     */
    static Pin pin(String wanted, List<AgreedRequirement> drafted) {
        if (wanted == null || wanted.isBlank()) {
            return new Pin(-1, null, null);
        }
        String needle = wanted.strip();
        List<Integer> matching = new ArrayList<>();
        for (int i = 0; i < drafted.size(); i++) {
            if (drafted.get(i).matches(needle)) {
                matching.add(i);
            }
        }
        if (matching.isEmpty()) {
            return new Pin(-1, "-Dswarmcoder.e2e.requirement=" + needle + " matches none of the "
                + drafted.size() + " drafted requirement(s), in title, text or checks (any case): "
                + drafted.stream().map(AgreedRequirement::label).toList() + ". Nothing was agreed "
                + "and nothing was built. The analyst words the requirements anew on every walk: "
                + "pin a word one of these titles carries, or drop the property to build the "
                + "smallest requirement.", null);
        }
        int first = matching.get(0);
        if (matching.size() == 1) {
            return new Pin(first, null, null);
        }
        return new Pin(first, null, "-Dswarmcoder.e2e.requirement=" + needle + " matches "
            + matching.size() + " drafted requirements " + matching.stream()
                .map(i -> drafted.get(i).label()).toList() + "; the first, "
            + drafted.get(first).label() + ", is the one built.");
    }

    /** SHA-256 of a document's text, as lower-case hex; null for no document. */
    static String documentHash(String text) {
        if (text == null) {
            return null;
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Warnings, one per input document whose hash differs from the one the snapshot was made with.
     * Never a refusal: a changed document means the snapshot's rules, requirements, story and plan
     * describe the OLD document, which a person may well want on purpose.
     */
    static List<String> documentWarnings(Manifest manifest, String businessText,
                                         String technicalText) {
        List<String> warnings = new ArrayList<>();
        compareDocument(warnings, "business", manifest.businessDocSha256(), businessText);
        compareDocument(warnings, "technical", manifest.technicalDocSha256(), technicalText);
        return warnings;
    }

    private static void compareDocument(List<String> warnings, String which, String saved,
                                        String text) {
        if (saved == null) {
            return; // an older snapshot recorded none; there is nothing to compare with
        }
        String now = documentHash(text);
        if (!saved.equals(now)) {
            warnings.add("[E2E] !!! the " + which + " document differs from the one this snapshot "
                + "was made from (sha256 " + saved.substring(0, 12) + " then, "
                + (now == null ? "none" : now.substring(0, 12)) + " now): the rules, requirements, "
                + "story and plan in it describe the old document.");
        }
    }

    private static Path pathProperty(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : Path.of(value.strip()).toAbsolutePath();
    }

    // --- saving -----------------------------------------------------------------------------

    /**
     * Writes a snapshot of {@code store} and {@code repo} into {@code dir}. Must be called while
     * nothing else writes to either — the dispatch seam. The manifest should already list the
     * repository's linked worktrees ({@link #registeredWorktrees}), which are not carried.
     *
     * @throws IllegalStateException when {@code dir} already holds anything — a snapshot is never
     *                               overwritten, so a resume from it stays repeatable
     */
    static void save(Path dir, ArtifactStore store, Path repo, Manifest manifest) throws Exception {
        if (Files.exists(dir) && !isEmptyDirectory(dir)) {
            throw new IllegalStateException(dir + " already holds something; a snapshot is never "
                + "written over another. Name a new directory or delete that one.");
        }
        Files.createDirectories(dir.toAbsolutePath().getParent());
        Path partial = dir.resolveSibling(dir.getFileName() + ".partial-" + System.currentTimeMillis());
        try {
            store.backupTo(partial.resolve(STORE));
            copyTree(repo, partial.resolve(REPO), true);
            Files.writeString(partial.resolve(MANIFEST), JSON.writeValueAsString(manifest),
                StandardCharsets.UTF_8);
            if (Files.exists(dir)) {
                Files.delete(dir); // empty, checked above
            }
            Files.move(partial, dir);
        } catch (Exception e) {
            deleteTree(partial);
            throw e;
        }
    }

    /** The linked worktrees a repository has registered, as {@code git worktree list} names them. */
    static List<String> registeredWorktrees(Path repo) {
        List<String> linked = new ArrayList<>();
        try {
            String main = null;
            for (String line : BookshelfFixture.git(repo, "worktree list --porcelain").split("\\R")) {
                if (line.startsWith("worktree ")) {
                    String path = line.substring("worktree ".length()).strip();
                    if (main == null) {
                        main = path; // the first entry is always the main working tree
                    } else {
                        linked.add(path);
                    }
                }
            }
        } catch (Exception e) {
            linked.add("(could not be listed: " + e.getMessage().strip() + ")");
        }
        return linked;
    }

    // --- restoring --------------------------------------------------------------------------

    /** Reads a snapshot's manifest, refusing a directory that is not a whole snapshot. */
    static Manifest readManifest(Path dir) throws IOException {
        Path manifest = dir.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            List<String> points = new ArrayList<>();
            for (RestartPoint point : RestartPoint.values()) {
                if (Files.isRegularFile(dir.resolve(point.folder()).resolve(MANIFEST))) {
                    points.add(point.folder());
                }
            }
            if (!points.isEmpty()) {
                throw new IllegalStateException(dir + " is a saveAt folder, not one snapshot. "
                    + "Name the point to start from: " + points.stream()
                        .map(p -> "-D" + RESUME_PROPERTY + "=" + dir.resolve(p)).toList());
            }
        }
        if (!Files.isRegularFile(manifest) || !Files.isDirectory(dir.resolve(STORE))
                || !Files.isDirectory(dir.resolve(REPO))) {
            throw new IllegalStateException(dir + " is not a whole snapshot: it needs " + MANIFEST
                + ", " + STORE + "/ and " + REPO + "/. A save that did not finish leaves a "
                + "*.partial-* directory instead, never one of these.");
        }
        Manifest read = JSON.readValue(Files.readString(manifest, StandardCharsets.UTF_8),
            Manifest.class);
        if (read.format() != FORMAT) {
            throw new IllegalStateException(dir + " is snapshot format " + read.format()
                + "; this harness reads format " + FORMAT + ". Save a new one.");
        }
        return read;
    }

    /**
     * Copies the snapshot into {@code work} — the store to {@code work/store}, the repository to
     * {@code work/bookshelf}, the names a full walk uses — makes the repository whole at its new
     * location, and clears this run's leftovers under {@code worktreeRoot}. The snapshot itself is
     * only read.
     *
     * @param worktreeRoot where the product cuts its checkouts ({@code ~/.swarmcoder/wt})
     */
    static Restored restore(Path dir, Path work, Path worktreeRoot) throws Exception {
        Manifest manifest = readManifest(dir);
        Path store = work.resolve("store");
        Path repo = work.resolve("bookshelf");
        if (Files.exists(store) || Files.exists(repo)) {
            throw new IllegalStateException("will not restore over " + store + " or " + repo);
        }
        copyTree(dir.resolve(STORE), store, false);
        copyTree(dir.resolve(REPO), repo, false);
        // Carried anyway only by a snapshot made some other way; see the class javadoc.
        deleteTree(repo.resolve(".git").resolve("worktrees"));
        BookshelfFixture.git(repo, "worktree prune");
        List<String> cleared = clearLeftovers(worktreeRoot, manifest.runId());
        return new Restored(store, repo, manifest, cleared);
    }

    /**
     * Points the restored store's project at the restored repository — what an operator does when
     * a project's folder moves. Without it {@code ArtifactStore.ensureProject}, which matches by
     * path, would create a second, empty project, and the resumed run would belong to one nobody
     * opened.
     */
    static Project repointProject(ArtifactStore store, UUID projectId, Path repo) {
        Project project = store.getProject(projectId);
        if (project == null) {
            throw new IllegalStateException("the snapshot's store has no project " + projectId);
        }
        project.setPrimaryPath(repo.toString());
        return store.saveProject(project);
    }

    /**
     * Removes checkouts under {@code worktreeRoot} named with {@code runId} whose repository no
     * longer exists — debris of an earlier copy of this same run.
     *
     * <p>Only names carrying this run's id are ever looked at; a live run of anything else, such
     * as another harness running beside this one, is invisible here. A checkout whose repository
     * DOES still exist may be in use by another copy of this run that is still going, so it is
     * never touched: the restore stops and says which one.
     *
     * @return one line per directory removed
     * @throws IllegalStateException when a checkout of this run still belongs to a live repository
     */
    static List<String> clearLeftovers(Path worktreeRoot, UUID runId) throws IOException {
        List<String> cleared = new ArrayList<>();
        if (worktreeRoot == null || runId == null || !Files.isDirectory(worktreeRoot)) {
            return cleared;
        }
        List<Path> ours;
        try (Stream<Path> children = Files.list(worktreeRoot)) {
            ours = children.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().contains(runId.toString()))
                .sorted().toList();
        }
        for (Path checkout : ours) {
            Path owner = gitdirOf(checkout);
            if (owner != null && Files.exists(owner)) {
                throw new IllegalStateException(checkout + " is a checkout of this same run ("
                    + runId + ") that still belongs to a repository at " + owner + ". Another copy "
                    + "of this run may still be going; this resume will not touch it. Stop that "
                    + "run, or remove the checkout with git from that repository.");
            }
            deleteTree(checkout);
            cleared.add(checkout.getFileName() + (owner == null
                ? " — no git pointer at all, a folder a failed attempt left"
                : " — its repository " + owner + " no longer exists"));
        }
        return cleared;
    }

    /** The {@code gitdir:} a linked worktree's {@code .git} file points at, or null. */
    private static Path gitdirOf(Path checkout) {
        Path pointer = checkout.resolve(".git");
        if (!Files.isRegularFile(pointer)) {
            return null;
        }
        try {
            String text = Files.readString(pointer, StandardCharsets.UTF_8).strip();
            return text.startsWith("gitdir:") ? Path.of(text.substring("gitdir:".length()).strip())
                : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // --- files -------------------------------------------------------------------------------

    private static boolean isEmptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.findAny().isEmpty();
        }
    }

    /**
     * Copies a tree. {@code skipWorktreeRegistrations} leaves out {@code .git/worktrees}, for the
     * reason the class javadoc gives.
     */
    static void copyTree(Path from, Path to, boolean skipWorktreeRegistrations) throws IOException {
        Path registrations = from.resolve(".git").resolve("worktrees");
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (skipWorktreeRegistrations && dir.equals(registrations)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path target = to.resolve(from.relativize(file).toString());
                Files.copy(file, target);
                // Git's object files are read-only; a copy that keeps that flag cannot be deleted by
                // the test's temp-dir cleanup on Windows, and nothing here needs it.
                makeWritable(target);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                makeWritable(path);
                Files.deleteIfExists(path);
            }
        }
    }

    private static void makeWritable(Path path) {
        try {
            DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class);
            if (dos != null) {
                dos.setReadOnly(false);
            } else {
                path.toFile().setWritable(true);
            }
        } catch (IOException | RuntimeException ignored) {
            // best effort: a flag that will not change costs a failed delete later, reported there
        }
    }
}
