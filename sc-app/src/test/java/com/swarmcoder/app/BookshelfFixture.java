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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A pristine, throwaway copy of {@code dev/bookshelf-demo} — the real three-module ZeroZ Stack
 * project the end-to-end loop is walked against.
 *
 * <p><b>Never the original.</b> A run writes commits, branches and worktrees into whatever
 * repository it is pointed at. The operator's own {@code dev/bookshelf-demo} already carries the
 * wreckage of a day of manual rounds — two hundred {@code swarm/*} branches and several commits of
 * acceptance tests written by earlier runs. Running against it would mean the second run of this
 * harness could never reproduce the first, which is the whole point of having it.
 *
 * <h2>Pristine means reset, not merely copied</h2>
 *
 * <p>A plain clone is not a clean slate here, because the leftovers are COMMITTED. Two of them
 * would silently invalidate the two assertions this harness exists to make:
 *
 * <ul>
 *   <li>{@code bookshelf-demo-server/src/test/java/swarm/accept/BookshelfTest.java} — acceptance
 *       tests an earlier run wrote. Left in place, "the acceptance stage executed a non-zero number
 *       of tests" could be satisfied by tests THIS run never authored.</li>
 *   <li>{@code src/test/java/swarm/accept/BookshelfTest.java} at the repository root — the orphan
 *       path defect itself: the root pom is a {@code <packaging>pom</packaging>} aggregator with no
 *       sources, so that file is compiled by nothing and run by nothing.</li>
 * </ul>
 *
 * <p>Both are removed and the removal is committed, so the harness starts from a project with no
 * acceptance tests at all and every {@code swarm/accept} file it later finds was written by the run
 * under test.
 */
final class BookshelfFixture {

    /** Where the original lives, relative to the swarmcoder checkout root. */
    private static final String ORIGIN = "dev/bookshelf-demo";

    // 2026-09-30, first run against a second project (HamBook): the harness was wired to
    // Bookshelf in four places — the repository it clones, the two documents, and (for a target
    // that has no verification contract of its own) nothing at all to supply one. Each is now a
    // system property whose default is the old behaviour, so an unconfigured run is unchanged.
    // "fixture" is the older name of the first one and still works.

    /** Path of the git repository to clone (its HEAD) as the target. */
    static final String PROP_FIXTURE_REPO = "swarmcoder.e2e.fixtureRepo";
    /** Path of the business requirements document. */
    static final String PROP_BUSINESS_DOC = "swarmcoder.e2e.businessDoc";
    /** Path of the technical requirements document. */
    static final String PROP_TECHNICAL_DOC = "swarmcoder.e2e.technicalDoc";
    /** Path of a verify.yaml to place at .swarmcoder/verify.yaml in the clone and commit. */
    static final String PROP_VERIFY_SPEC = "swarmcoder.e2e.verifySpec";
    /**
     * Path of a directory whose files are copied over the clone, at the same relative paths, and
     * committed in the base commit. 2026-09-30, HamBook: its committed M0 skeleton has no test
     * dependency in the server module, so no acceptance test could compile there — a project
     * setup gap, not a finding about SwarmCoder, fixed in the baseline rather than left to a worker.
     */
    static final String PROP_OVERLAY = "swarmcoder.e2e.overlay";
    /**
     * {@code true} keeps the acceptance tests the cloned project's modules already hold (run 86,
     * DEVELOPER_CORRECTIONS section 58). A real project keeps the passing acceptance tests of its
     * earlier stories, and they are the example the product shows the next story's test author
     * ({@code ExamplesByUse}); stripped, every story's author finds out from nothing how a test
     * starts this project's framework. ON unless set to {@code false} (DEVELOPER_CORRECTIONS
     * section 59); the harness's own "this run's tests ran" check then counts executed tests
     * beyond {@link Fixture#keptTests()}, the test methods the clone already held. The orphan
     * tree at the repository root is removed either way.
     */
    static final String PROP_KEEP_ACCEPTANCE_TESTS = "swarmcoder.e2e.keepAcceptanceTests";

    static final String DEFAULT_BUSINESS_DOC = "dev/bookshelf-requirements.md";
    static final String DEFAULT_TECHNICAL_DOC = "dev/bookshelf-tech-requirements.md";

    private BookshelfFixture() {}

    /** What was made, and what had to be cleaned off it — for the ledger's observation line. */
    record Fixture(Path repo, String origin, String baseCommit, List<String> removed,
                   List<String> modules, String verifySpecPlaced, int keptTests) {

        String describe() {
            return "a clone of " + origin + " at " + repo + ", reset to " + baseCommit.substring(0,
                Math.min(8, baseCommit.length())) + " with " + modules.size() + " module(s) "
                + modules + (removed.isEmpty() ? " and no leftover acceptance tests"
                    : "; removed " + removed.size() + " leftover file(s) from earlier runs: "
                        + removed)
                + (verifySpecPlaced == null ? ""
                    : "; committed the verification contract " + verifySpecPlaced
                        + " as .swarmcoder/verify.yaml in the base commit");
        }
    }

    /**
     * Clones the demo project into {@code target} and strips it back to a clean baseline.
     *
     * @throws IllegalStateException when {@code dev/bookshelf-demo} is not in this checkout, which
     *                               is a finding about the machine and not about the product
     */
    static Fixture create(Path target) throws Exception {
        Path origin = locate();
        if (origin == null) {
            throw new IllegalStateException(declaredRepo() != null
                ? declaredRepo() + " (from -D" + PROP_FIXTURE_REPO + ") is not a git repository"
                : "dev/bookshelf-demo was not found above "
                    + Paths.get("").toAbsolutePath() + " or above the main checkout — it is its "
                    + "own git repository and is "
                    + "gitignored by this one, so a checkout without it cannot run this harness. "
                    + "Point -D" + PROP_FIXTURE_REPO + "=<path> at a copy.");
        }
        Path verifySpec = verifySpecFile();
        Files.createDirectories(target.getParent());
        // --single-branch: the original carries hundreds of swarm/<run-id>/<n> branches from
        // earlier runs. Cloning them all would be slow and would leave this copy looking like a
        // repository that has already been worked, which is exactly what it must not look like.
        // Bookshelf's branch stays pinned to master as before; a configured repository is cloned
        // at whatever its HEAD is, which is what "its committed HEAD" means.
        git(target.getParent(), "clone -q --single-branch"
            + (declaredRepo() == null ? " --branch master" : "") + " \""
            + origin.toString().replace('\\', '/') + "\" \"" + target.getFileName() + "\"");

        List<String> removed = stripAcceptanceTests(target);
        String placed = null;
        if (verifySpec != null) {
            Path dest = target.resolve(".swarmcoder").resolve("verify.yaml");
            Files.createDirectories(dest.getParent());
            Files.copy(verifySpec, dest, StandardCopyOption.REPLACE_EXISTING);
            placed = verifySpec.toString();
        }
        List<String> overlaid = applyOverlay(target);
        if (!overlaid.isEmpty()) {
            placed = placed == null ? "overlay " + overlaid : placed + ", overlay " + overlaid;
        }
        if (!removed.isEmpty() || placed != null) {
            git(target, "add -A");
            if (placed != null) {
                // -f: a target whose .gitignore covers .swarmcoder must still carry the contract.
                git(target, "add -f .swarmcoder/verify.yaml");
            }
            git(target, "-c user.email=e2e@local -c user.name=e2e commit -q --allow-empty -m \""
                + (removed.isEmpty() ? "Baseline: verification contract"
                    : placed == null ? "Baseline: no acceptance tests"
                        : "Baseline: no acceptance tests, verification contract") + "\"");
        }

        List<String> modules = new ArrayList<>();
        for (Path child : Files.newDirectoryStream(target, Files::isDirectory)) {
            if (Files.isRegularFile(child.resolve("pom.xml"))) {
                modules.add(target.relativize(child).toString().replace('\\', '/'));
            }
        }
        modules.sort(Comparator.naturalOrder());
        return new Fixture(target, origin.toString(), git(target, "rev-parse HEAD").strip(),
            removed, modules, placed, countKeptTests(target));
    }

    /** True unless {@code -Dswarmcoder.e2e.keepAcceptanceTests=false}. */
    static boolean keepAcceptanceTests() {
        String v = System.getProperty(PROP_KEEP_ACCEPTANCE_TESTS);
        return v == null || !v.strip().equalsIgnoreCase("false");
    }

    /** Test methods the clone holds in its acceptance trees ({@code @Test} and variants). */
    private static int countKeptTests(Path repo) throws IOException {
        int[] n = {0};
        java.util.regex.Pattern marker = java.util.regex.Pattern.compile(
            "^\\s*@(Test|ParameterizedTest|RepeatedTest)\\b", java.util.regex.Pattern.MULTILINE);
        Files.walkFileTree(repo, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return dir.getFileName() != null && dir.getFileName().toString().equals(".git")
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
                String rel = repo.relativize(file).toString().replace('\\', '/');
                if (rel.endsWith(".java") && rel.contains("src/test/java/swarm/")) {
                    var m = marker.matcher(Files.readString(file));
                    while (m.find()) {
                        n[0]++;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return n[0];
    }

    /**
     * Deletes every {@code src/test/java/swarm} tree in the copy, and the orphan {@code src/} the
     * root aggregator carries.
     *
     * @return the repo-relative paths removed, for the record
     */
    private static List<String> stripAcceptanceTests(Path repo) throws IOException {
        List<Path> trees = new ArrayList<>();
        Files.walkFileTree(repo, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (dir.endsWith(Paths.get("src", "test", "java", "swarm"))) {
                    boolean atTheRoot = dir.equals(repo.resolve(Paths.get("src", "test", "java",
                        "swarm")));
                    if (atTheRoot || !keepAcceptanceTests()) {
                        trees.add(dir);
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        List<String> removed = new ArrayList<>();
        for (Path tree : trees) {
            try (var walk = Files.walk(tree)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    removed.add(repo.relativize(file).toString().replace('\\', '/'));
                }
            }
            deleteRecursively(tree);
        }
        // The aggregator's own src/ is compiled by nothing; if stripping emptied it, take it away
        // so nothing can be tempted to write there again.
        Path rootSrc = repo.resolve("src");
        if (Files.isDirectory(rootSrc) && isEmptyTree(rootSrc)) {
            deleteRecursively(rootSrc);
        }
        return removed;
    }

    private static boolean isEmptyTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.noneMatch(Files::isRegularFile);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * The business document that drives the journey: the file named by
     * {@code -Dswarmcoder.e2e.businessDoc}, else {@code dev/bookshelf-requirements.md}.
     */
    static String businessDocument() throws IOException {
        return configuredDocument(PROP_BUSINESS_DOC, DEFAULT_BUSINESS_DOC);
    }

    /** As {@link #businessDocument()}, for {@code -Dswarmcoder.e2e.technicalDoc}. */
    static String technicalDocument() throws IOException {
        return configuredDocument(PROP_TECHNICAL_DOC, DEFAULT_TECHNICAL_DOC);
    }

    /** The name the business document is ingested under: its file name. */
    static String businessDocumentName() {
        return documentName(PROP_BUSINESS_DOC, DEFAULT_BUSINESS_DOC);
    }

    /** The name the technical document is ingested under: its file name. */
    static String technicalDocumentName() {
        return documentName(PROP_TECHNICAL_DOC, DEFAULT_TECHNICAL_DOC);
    }

    /**
     * The name the project is registered under: the configured repository's directory name, or
     * {@code bookshelf} when none is configured (what it always was).
     */
    static String projectName() {
        String declared = declaredRepo();
        if (declared == null) {
            return "bookshelf";
        }
        Path name = Path.of(declared).toAbsolutePath().normalize().getFileName();
        return name == null ? "bookshelf" : name.toString();
    }

    private static String configuredDocument(String property, String defaultRelative)
            throws IOException {
        String declared = System.getProperty(property);
        if (declared == null || declared.isBlank()) {
            return document(defaultRelative);
        }
        Path file = Path.of(declared);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("-D" + property + "=" + declared + " is not a file");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String documentName(String property, String defaultRelative) {
        String declared = System.getProperty(property);
        String path = declared == null || declared.isBlank() ? defaultRelative : declared;
        Path name = Path.of(path).getFileName();
        return name == null ? path : name.toString();
    }

    /** The verify.yaml to place in the clone, or null when none is configured. */
    /** Copies the {@link #PROP_OVERLAY} directory's files over {@code target}; their paths. */
    private static List<String> applyOverlay(Path target) throws Exception {
        String declared = System.getProperty(PROP_OVERLAY);
        if (declared == null || declared.isBlank()) {
            return List.of();
        }
        Path overlay = Paths.get(declared);
        if (!Files.isDirectory(overlay)) {
            throw new IllegalStateException("-D" + PROP_OVERLAY + "=" + declared
                + " is not a directory");
        }
        List<String> copied = new ArrayList<>();
        try (var files = Files.walk(overlay)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = overlay.relativize(file).toString().replace('\\', '/');
                Path dest = target.resolve(relative);
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                copied.add(relative);
            }
        }
        return copied;
    }

    private static Path verifySpecFile() {
        String declared = System.getProperty(PROP_VERIFY_SPEC);
        if (declared == null || declared.isBlank()) {
            return null;
        }
        Path file = Path.of(declared);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("-D" + PROP_VERIFY_SPEC + "=" + declared
                + " is not a file");
        }
        return file;
    }

    private static String declaredRepo() {
        String declared = System.getProperty(PROP_FIXTURE_REPO);
        if (declared == null || declared.isBlank()) {
            declared = System.getProperty("swarmcoder.e2e.fixture");
        }
        return declared == null || declared.isBlank() ? null : declared;
    }

    /** A document read off disk, relative to this checkout or the main one. */
    static String document(String relative) throws IOException {
        Path file = above(relative);
        if (file == null) {
            throw new IllegalStateException(relative + " was not found above "
                + Paths.get("").toAbsolutePath());
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Path locate() {
        String declared = declaredRepo();
        if (declared != null) {
            Path path = Path.of(declared);
            return Files.isDirectory(path.resolve(".git")) ? path : null;
        }
        Path here = walkUpFor(Paths.get("").toAbsolutePath(), ORIGIN);
        return here != null ? here : walkUpFor(mainCheckout(), ORIGIN);
    }

    /**
     * The main checkout this worktree belongs to, or null when there is none.
     *
     * <p>Needed because the fixture is GITIGNORED. {@code dev/bookshelf-demo} is its own git
     * repository, so it is absent from every {@code git worktree add} — and this harness is
     * exactly the kind of thing an agent session runs from a worktree. Asking git for the common
     * directory finds the one checkout that does have it, so the harness works from anywhere in
     * the repository without a flag.
     */
    private static Path mainCheckout() {
        try {
            String common = git(Paths.get("").toAbsolutePath(),
                "rev-parse --path-format=absolute --git-common-dir").strip();
            if (common.isEmpty()) {
                return null;
            }
            return Path.of(common).getParent();
        } catch (Exception e) {
            return null;
        }
    }

    private static Path walkUpFor(Path start, String relative) {
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate.resolve(".git"))
                || Files.isRegularFile(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        return null;
    }

    private static Path above(String relative) {
        for (Path start : new Path[] {Paths.get("").toAbsolutePath(), mainCheckout()}) {
            for (Path dir = start; dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** One git command; its output, or an exception naming what failed. */
    static String git(Path dir, String args) throws Exception {
        List<String> argv = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", "git " + args)
            : List.of("sh", "-c", "git " + args);
        Process process = new ProcessBuilder(argv).directory(dir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed in " + dir + ":\n" + out);
        }
        return out;
    }
}
