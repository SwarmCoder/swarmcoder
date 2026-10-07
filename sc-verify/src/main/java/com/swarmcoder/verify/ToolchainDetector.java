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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Works out how to build and test a repository SwarmCoder has never seen, and proposes the
 * verification contract ({@code .swarmcoder/verify.yaml}) for the operator to correct.
 *
 * <p><b>Why this exists.</b> Without a contract, {@link VerifySpecLoader} returns empty,
 * verification is skipped, and every candidate comes back UNVERIFIED — which hollows out the swarm
 * entirely, because choosing between N attempts <em>is</em> testing them. Detection is therefore the
 * on-ramp: it is the one thing that stands between "here is a repository" and a run whose winner
 * was actually chosen on evidence.
 *
 * <p><b>It proposes; it never decides.</b> Every {@link Detection} carries the evidence that led to
 * it and the warnings the operator has to rule on, and the rendered YAML is meant to be read and
 * edited before it is saved. A wrong build command costs a whole run, and the detector cannot read
 * a README. {@link ContractProbe} exists so the proposal is offered having actually been run once.
 *
 * <p><b>Where the file lands is a security boundary, not a convenience</b> (§13.1). The contract
 * names commands the orchestrator executes on the host, so it is written into the OPERATOR'S tree
 * — the project's own checkout — never into a worker's worktree. {@code VerifySpecLoader.loadTrusted}
 * already reads it from there. Nothing here writes anything; {@link #render} returns text and the
 * caller decides where it goes.
 *
 * <p><b>Why no project ever got a browser block, until 2026-08-31.</b> There was no code path that
 * could produce one. Every toolchain here built its {@link VerifySpec} with {@code null} in the
 * browser slot, and {@link #render} had no branch that would have written the block out even if one
 * had been there - so the single stage that starts the application and looks at it was reachable
 * only by an operator who hand-wrote a block whose shape is documented nowhere but in the source.
 * Nobody did, in any repository in this project. {@link WebAppDetection} closes that: a repository
 * that plainly serves a web page is now offered a serve command, a readiness probe and a
 * navigation, and where no runnable command can be worked out it is told so in words rather than
 * handed a guess.
 *
 * <p><b>Nothing here is a fallback for a failed detection.</b> When no toolchain is recognised the
 * detection says so and proposes nothing. The absent-contract behaviour then stands exactly as §5
 * documents it: verification is skipped loudly and the candidate is marked unverified. Papering
 * over that with a guessed command would be worse than having none, because a guessed command that
 * exits 0 for the wrong reason certifies a candidate nobody checked.
 */
public final class ToolchainDetector {

    private static final Logger log = LoggerFactory.getLogger(ToolchainDetector.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * How deep to look for build files when the root itself has none. Three levels reaches the
     * shape monorepos actually use ({@code packages/thing/package.json}) and stops well short of
     * walking a whole checkout.
     */
    private static final int MONOREPO_SCAN_DEPTH = 3;

    /** Directories never worth descending into when looking for a project's build file. */
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", ".mvn", ".venv", "venv",
        "node_modules", "target", "build", "dist", "out", "__pycache__", ".swarmcoder");

    /** Default per-command timeout proposed for a repository nobody has timed yet. */
    public static final int DEFAULT_PROPOSED_TIMEOUT_SECONDS = 900;

    /**
     * Where the test author writes acceptance tests, per toolchain. Workers are forbidden from
     * writing here (PathPolicy), which is what stops a worker making its own gate pass.
     *
     * <p>Maven and Gradle are deliberately NOT in this list. Their directory is read from the
     * build by {@link AcceptanceTestLocation}, because a hardcoded {@code src/test/java/swarm/accept}
     * at the repository root is compiled by nothing in a multi-module reactor — the defect this
     * whole mechanism exists to stop. The three below stay constants because their layouts are
     * configuration rather than convention, and there is nothing honest to read.
     */
    private static final String NODE_ACCEPT_DIR = "test/accept";
    private static final String CARGO_ACCEPT_DIR = "tests/accept";
    private static final String PYTHON_ACCEPT_DIR = "tests/accept";

    private ToolchainDetector() {}

    /**
     * What was found, what will be run, and what the operator still has to decide.
     *
     * @param toolchain        maven | gradle | node | cargo | python, or null when nothing was
     *                         recognised
     * @param evidence         the files that led to this conclusion, in plain English
     * @param warnings         things the operator must rule on — a wrapper that was not used, a
     *                         multi-module reactor, a test runner that will not emit JUnit XML
     * @param proposed         the contract, or null when nothing was recognised
     * @param acceptanceTestDir where TEST_AUTHORING should write, or null
     * @param subprojects      when the root itself has no build file: directories under it that do
     */
    public record Detection(
        String toolchain,
        List<String> evidence,
        List<String> warnings,
        VerifySpec proposed,
        String acceptanceTestDir,
        List<String> subprojects
    ) {
        public boolean recognised() {
            return proposed != null;
        }

        /** One line for a log or a status strip. */
        public String summary() {
            if (!recognised()) {
                return subprojects.isEmpty()
                    ? "no build file recognised at the repository root"
                    : "no build file at the repository root; build files found under "
                        + String.join(", ", subprojects);
            }
            return toolchain + ": " + String.join(" ; ",
                proposed.compile() == null ? List.of() : proposed.compile());
        }
    }

    /** Detects the toolchain of a repository root and proposes its verification contract. */
    public static Detection detect(Path root) {
        List<String> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        if (root == null || !Files.isDirectory(root)) {
            return new Detection(null, List.of("no such directory: " + root), List.of(), null, null,
                List.of());
        }

        if (exists(root, "pom.xml")) {
            return maven(root, evidence, warnings, windows);
        }
        if (exists(root, "build.gradle") || exists(root, "build.gradle.kts")) {
            return gradle(root, evidence, warnings, windows);
        }
        if (exists(root, "Cargo.toml")) {
            return cargo(root, evidence, warnings);
        }
        if (exists(root, "package.json")) {
            return node(root, evidence, warnings);
        }
        if (exists(root, "pyproject.toml") || exists(root, "setup.py") || exists(root, "setup.cfg")
                || exists(root, "requirements.txt")) {
            return python(root, evidence, warnings);
        }
        // Nothing at the root. Say what IS there rather than guessing a command.
        return new Detection(null, evidence, warnings, null, null, subprojectsWithBuildFiles(root));
    }

    // --- Maven ---------------------------------------------------------------------------------

    private static Detection maven(Path root, List<String> evidence, List<String> warnings,
                                   boolean windows) {
        evidence.add("pom.xml at the repository root");
        String mvn = launcher(root, windows, "mvnw.cmd", "mvnw", "mvn", evidence, warnings, "Maven");

        List<String> modules = mavenModules(root, "", new LinkedHashSet<>());
        List<String> reportDirs = new ArrayList<>();
        if (modules.isEmpty()) {
            reportDirs.add("target/surefire-reports");
            reportDirs.add("target/failsafe-reports");
        } else {
            evidence.add("a multi-module reactor with " + modules.size() + " module(s): "
                + String.join(", ", modules.size() > 8 ? modules.subList(0, 8) : modules)
                + (modules.size() > 8 ? ", …" : ""));
            // Report dirs are walked literally, not globbed (ExecTarget.listFiles takes a directory,
            // not a pattern). A multi-module build writes its results per module, so the root
            // target/surefire-reports finds NOTHING and the stage reports zero tests — which reads
            // exactly like a green one. Enumerating the modules is what makes the stage real here.
            reportDirs.add("target/surefire-reports");
            for (String module : modules) {
                reportDirs.add(module + "/target/surefire-reports");
                reportDirs.add(module + "/target/failsafe-reports");
            }
            warnings.add("This is a multi-module Maven build, so a command at the root builds and "
                + "tests every module. That is correct, and it is the dominant cost of a run: "
                + "every worker's attempt pays it, in parallel, and again on every repair round. "
                + "If a run is slow it is almost certainly this and not the model. Narrow the "
                + "commands with -pl <module> -am once you know which part of the tree the work "
                + "touches, and cut the report list to match.");
        }

        WebAppDetection.Proposal web =
            WebAppDetection.forJava(root, "maven", mvn, modules, windows);
        evidence.addAll(web.evidence());
        warnings.addAll(web.warnings());

        VerifySpec spec = new VerifySpec(
            "maven",
            List.of(mvn + " -B -q test-compile"),
            // Surefire wants a PATH pattern; the dotted package form matches nothing and the stage
            // then silently runs zero tests (§15.2). failIfNoSpecifiedTests=false keeps the stage
            // clean before the test author has written anything.
            List.of(mvn + " -B test -Dtest=swarm/accept/** -Dsurefire.failIfNoSpecifiedTests=false"),
            List.of(mvn + " -B test"),
            List.of(),
            List.of(),
            new VerifySpec.TestReportsSpec(reportDirs, reportDirs),
            DEFAULT_PROPOSED_TIMEOUT_SECONDS,
            web.browser());
        return new Detection("maven", evidence, warnings, spec,
            acceptanceDir(root, "maven", evidence, warnings), List.of());
    }

    /**
     * Module directories of a Maven reactor, recursively, relative to the root. Read with a regex
     * rather than a POM parser on purpose: this runs against a repository nobody has vetted, an
     * XML parser is an XXE surface, and all that is wanted is the list of directory names.
     */
    private static List<String> mavenModules(Path root, String prefix, Set<String> seen) {
        Path pom = root.resolve(prefix.isEmpty() ? "pom.xml" : prefix + "/pom.xml");
        String text = readCapped(pom, 512 * 1024);
        if (text == null) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher block = Pattern.compile("<modules>(.*?)</modules>", Pattern.DOTALL).matcher(text);
        while (block.find()) {
            Matcher module = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>")
                .matcher(block.group(1));
            while (module.find()) {
                String name = module.group(1).replace('\\', '/').replaceAll("/+$", "");
                if (name.isBlank() || name.contains("..")) {
                    continue;
                }
                String path = prefix.isEmpty() ? name : prefix + "/" + name;
                if (!seen.add(path)) {
                    continue;
                }
                found.add(path);
                found.addAll(mavenModules(root, path, seen));
            }
        }
        return found;
    }

    // --- Gradle --------------------------------------------------------------------------------

    private static Detection gradle(Path root, List<String> evidence, List<String> warnings,
                                    boolean windows) {
        evidence.add(exists(root, "build.gradle.kts")
            ? "build.gradle.kts at the repository root" : "build.gradle at the repository root");
        String gradle = launcher(root, windows, "gradlew.bat", "gradlew", "gradle",
            evidence, warnings, "Gradle");

        List<String> reportDirs = new ArrayList<>(List.of("build/test-results"));
        List<String> subprojects = gradleSubprojects(root);
        if (!subprojects.isEmpty()) {
            evidence.add("settings file includes " + subprojects.size() + " subproject(s)");
            for (String sub : subprojects) {
                reportDirs.add(sub + "/build/test-results");
            }
            warnings.add("This is a multi-project Gradle build. The commands below run at the root, "
                + "so every subproject is built. Narrow with :module:test if that is too slow.");
        }

        WebAppDetection.Proposal web =
            WebAppDetection.forJava(root, "gradle", gradle, subprojects, windows);
        evidence.addAll(web.evidence());
        warnings.addAll(web.warnings());

        VerifySpec spec = new VerifySpec(
            "gradle",
            List.of(gradle + " testClasses"),
            // The Gradle selector IS dotted; only Surefire wants slashes (§15.2).
            List.of(gradle + " test --tests 'swarm.accept.*'"),
            List.of(gradle + " test"),
            List.of(),
            List.of(),
            new VerifySpec.TestReportsSpec(reportDirs, reportDirs),
            DEFAULT_PROPOSED_TIMEOUT_SECONDS,
            web.browser());
        warnings.add("Gradle fails a `--tests` selector that matches nothing. Before any acceptance "
            + "test exists the acceptance stage will fail rather than skip; that is visible, not "
            + "silent, and it clears the moment the test author writes the first one.");
        return new Detection("gradle", evidence, warnings, spec,
            acceptanceDir(root, "gradle", evidence, warnings), List.of());
    }

    /**
     * Where acceptance tests can actually be compiled and run in THIS repository.
     *
     * <p>Read from the build rather than assumed — see {@link AcceptanceTestLocation} for the rule
     * and for the live run it was written after. The layout is parsed by {@link BuildLayout}, which
     * uses a hardened XML parser (secure processing on, DOCTYPE declarations refused, no entity
     * expansion) on the same untrusted repositories the reachability gate already reads.
     */
    private static String acceptanceDir(Path root, String toolchain, List<String> evidence,
                                        List<String> warnings) {
        AcceptanceTestLocation.Location location = AcceptanceTestLocation.resolve(root, toolchain);
        evidence.add(location.note());
        if (!location.seesEveryModule()) {
            warnings.add(location.note());
        }
        if (!location.module().isEmpty() && !hasATestFramework(root, location.module())) {
            warnings.add("Nothing in " + location.module() + " (or the root build file) mentions a "
                + "test framework, so a JUnit test written into "
                + location.writeDir() + " will not compile there. Add a test-scoped JUnit "
                + "dependency to that module before a run depends on the acceptance stage.");
        }
        return location.writeDir();
    }

    /** A cheap, advisory look for a test dependency in the host module and the root build file. */
    private static boolean hasATestFramework(Path root, String module) {
        for (String candidate : List.of(module + "/pom.xml", module + "/build.gradle",
                module + "/build.gradle.kts", "pom.xml", "build.gradle", "build.gradle.kts")) {
            String text = readCapped(root.resolve(candidate), 512 * 1024);
            if (text == null) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("junit") || lower.contains("testng") || lower.contains("kotlin-test")
                    || lower.contains("spock") || lower.contains("kotest")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> gradleSubprojects(Path root) {
        String settings = readCapped(root.resolve("settings.gradle"), 256 * 1024);
        if (settings == null) {
            settings = readCapped(root.resolve("settings.gradle.kts"), 256 * 1024);
        }
        if (settings == null) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("include\\s*\\(?\\s*[\"']([^\"']+)[\"']").matcher(settings);
        while (m.find()) {
            String path = m.group(1).replace(':', '/').replaceAll("^/+", "");
            if (!path.isBlank() && !path.contains("..") && !found.contains(path)) {
                found.add(path);
            }
        }
        return found;
    }

    // --- Node ----------------------------------------------------------------------------------

    private static Detection node(Path root, List<String> evidence, List<String> warnings) {
        evidence.add("package.json at the repository root");
        String pm = "npm";
        if (exists(root, "pnpm-lock.yaml")) {
            pm = "pnpm";
            evidence.add("pnpm-lock.yaml, so the package manager is pnpm");
        } else if (exists(root, "yarn.lock")) {
            pm = "yarn";
            evidence.add("yarn.lock, so the package manager is yarn");
        } else if (exists(root, "package-lock.json")) {
            evidence.add("package-lock.json, so the package manager is npm");
        } else {
            warnings.add("No lockfile, so npm was assumed. Change it if this project uses pnpm or "
                + "yarn — the wrong one will install a different dependency tree.");
        }

        Set<String> scripts = packageScripts(root);
        List<String> compile = new ArrayList<>();
        if (scripts.contains("build")) {
            compile.add(run(pm, "build"));
            evidence.add("a \"build\" script, used as the compile stage");
        } else if (exists(root, "tsconfig.json")) {
            compile.add("npx tsc --noEmit");
            evidence.add("tsconfig.json but no \"build\" script, so type-checking stands in for "
                + "compiling");
        } else {
            warnings.add("Nothing here compiles: there is no \"build\" script and no tsconfig.json. "
                + "The compile stage is empty, so a candidate that is syntactically broken will "
                + "only be caught by the tests. Add a command if the project has one.");
        }

        List<String> existing = new ArrayList<>();
        if (scripts.contains("test")) {
            existing.add(run(pm, "test"));
            evidence.add("a \"test\" script, used as the existing-tests stage");
        } else {
            warnings.add("There is no \"test\" script, so nothing checks that a candidate did not "
                + "break what already worked. Without it a candidate survives on compiling alone.");
        }

        List<String> lint = scripts.contains("lint") ? List.of(run(pm, "lint")) : List.of();
        if (!lint.isEmpty()) {
            evidence.add("a \"lint\" script (advisory — lint never kills a candidate)");
        }

        // The runner must emit JUnit XML or the stage cannot be parsed structurally, and an
        // unparsable stage is INCONCLUSIVE, which proves nothing about anything.
        String runner = nodeTestRunner(root);
        List<String> acceptance = new ArrayList<>();
        if ("vitest".equals(runner)) {
            acceptance.add("npx vitest run test/accept --reporter=junit "
                + "--outputFile=test-results/acceptance.xml");
            evidence.add("vitest in the dependencies, so acceptance tests run with its JUnit "
                + "reporter");
        } else if ("jest".equals(runner)) {
            acceptance.add("npx jest test/accept --reporters=default "
                + "--reporters=jest-junit --passWithNoTests");
            evidence.add("jest in the dependencies, so acceptance tests run with jest-junit");
            warnings.add("jest-junit is a separate package. If it is not installed the acceptance "
                + "stage will produce no XML and prove nothing — install it, or point the "
                + "acceptance command at whatever this project already uses.");
        } else {
            warnings.add("No vitest or jest was found, so no acceptance command is proposed. "
                + "SwarmCoder reads results from JUnit-format XML, so whatever you put here must "
                + "be told to write it (vitest --reporter=junit, jest with jest-junit, and so on). "
                + "Until then acceptance tests cannot gate anything.");
        }

        WebAppDetection.Proposal web = WebAppDetection.forNode(root, pm);
        evidence.addAll(web.evidence());
        warnings.addAll(web.warnings());

        VerifySpec spec = new VerifySpec("node", compile, acceptance, existing, lint, List.of(),
            new VerifySpec.TestReportsSpec(List.of("test-results"), List.of("test-results")),
            DEFAULT_PROPOSED_TIMEOUT_SECONDS, web.browser());
        return new Detection("node", evidence, warnings, spec, NODE_ACCEPT_DIR, List.of());
    }

    private static String run(String pm, String script) {
        return "yarn".equals(pm) ? "yarn " + script : pm + " run " + script;
    }

    private static Set<String> packageScripts(Path root) {
        String json = readCapped(root.resolve("package.json"), 512 * 1024);
        if (json == null) {
            return Set.of();
        }
        try {
            JsonNode scripts = JSON.readTree(json).path("scripts");
            Set<String> names = new LinkedHashSet<>();
            scripts.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (IOException e) {
            log.warn("Unreadable package.json in {}: {}", root, e.getMessage());
            return Set.of();
        }
    }

    private static String nodeTestRunner(Path root) {
        String json = readCapped(root.resolve("package.json"), 512 * 1024);
        if (json == null) {
            return null;
        }
        try {
            JsonNode pkg = JSON.readTree(json);
            for (String section : List.of("devDependencies", "dependencies")) {
                JsonNode deps = pkg.path(section);
                if (deps.has("vitest")) {
                    return "vitest";
                }
                if (deps.has("jest")) {
                    return "jest";
                }
            }
        } catch (IOException e) {
            log.warn("Unreadable package.json in {}: {}", root, e.getMessage());
        }
        return null;
    }

    // --- Cargo ---------------------------------------------------------------------------------

    private static Detection cargo(Path root, List<String> evidence, List<String> warnings) {
        evidence.add("Cargo.toml at the repository root");
        String manifest = readCapped(root.resolve("Cargo.toml"), 256 * 1024);
        if (manifest != null && manifest.contains("[workspace]")) {
            evidence.add("a cargo workspace, so --workspace covers every member crate");
        }
        boolean workspace = manifest != null && manifest.contains("[workspace]");
        String scope = workspace ? " --workspace" : "";

        VerifySpec spec = new VerifySpec(
            "cargo",
            List.of("cargo build" + scope + " --all-targets"),
            // cargo test writes no machine-readable report; nextest is the only one that emits
            // JUnit XML, and without XML the stage is INCONCLUSIVE and proves nothing.
            List.of("cargo nextest run" + scope + " --profile ci -E 'test(/accept/)'"),
            List.of("cargo nextest run" + scope + " --profile ci"),
            List.of("cargo clippy" + scope + " --all-targets"),
            List.of(),
            new VerifySpec.TestReportsSpec(List.of("target/nextest"), List.of("target/nextest")),
            DEFAULT_PROPOSED_TIMEOUT_SECONDS,
            null);
        warnings.add("Both test stages use cargo-nextest, because plain `cargo test` writes no "
            + "machine-readable report and a stage SwarmCoder cannot parse proves nothing. Install "
            + "it with `cargo install cargo-nextest`, and add a ci profile to "
            + ".config/nextest.toml with junit output enabled — otherwise no XML is written and "
            + "the stage stays inconclusive.");
        return new Detection("cargo", evidence, warnings, spec, CARGO_ACCEPT_DIR, List.of());
    }

    // --- Python --------------------------------------------------------------------------------

    private static Detection python(Path root, List<String> evidence, List<String> warnings) {
        List<String> markers = new ArrayList<>();
        for (String marker : List.of("pyproject.toml", "setup.py", "setup.cfg",
                "requirements.txt")) {
            if (exists(root, marker)) {
                markers.add(marker);
            }
        }
        evidence.add(String.join(", ", markers) + " at the repository root");

        String sourceDir = Files.isDirectory(root.resolve("src")) ? "src" : ".";
        if (".".equals(sourceDir)) {
            warnings.add("There is no src/ directory, so the compile stage byte-compiles the whole "
                + "repository. That will walk anything checked in — narrow it to the package "
                + "directory if it is slow or noisy.");
        }

        VerifySpec spec = new VerifySpec(
            "python",
            // Python has no compile step. Byte-compiling is the nearest honest equivalent: it
            // catches a syntax error, and it catches nothing else. The tests are the real gate.
            List.of("python -m compileall -q " + sourceDir),
            List.of("python -m pytest tests/accept --junitxml=test-reports/acceptance.xml"),
            List.of("python -m pytest --junitxml=test-reports/existing.xml"),
            List.of(),
            List.of(),
            new VerifySpec.TestReportsSpec(List.of("test-reports"), List.of("test-reports")),
            DEFAULT_PROPOSED_TIMEOUT_SECONDS,
            null);
        warnings.add("The compile stage only byte-compiles, which catches a syntax error and "
            + "nothing else — no import error, no type error. On this toolchain the existing-test "
            + "stage is doing nearly all the work of deciding whether a candidate is alive.");
        if (!exists(root, "pytest.ini") && !exists(root, "tox.ini")
                && (readCapped(root.resolve("pyproject.toml"), 256 * 1024) == null
                    || !readCapped(root.resolve("pyproject.toml"), 256 * 1024).contains("pytest"))) {
            warnings.add("Nothing here mentions pytest, so it was assumed. If this project uses "
                + "unittest or nose, replace both test commands — and whatever you use must be "
                + "told to write JUnit XML.");
        }
        return new Detection("python", evidence, warnings, spec, PYTHON_ACCEPT_DIR, List.of());
    }

    // --- shared --------------------------------------------------------------------------------

    /**
     * Picks the build launcher: the checked-in wrapper when the platform's wrapper script is
     * actually present, otherwise the bare command. A wrapper pins the build tool's version, so
     * using it when it exists is the difference between building the project as its authors do and
     * building it with whatever happens to be on this machine's PATH.
     */
    private static String launcher(Path root, boolean windows, String windowsWrapper,
                                   String unixWrapper, String bare, List<String> evidence,
                                   List<String> warnings, String toolName) {
        if (windows && exists(root, windowsWrapper)) {
            evidence.add(windowsWrapper + ", so the project's own " + toolName
                + " wrapper is used rather than whatever is on PATH");
            return windowsWrapper;
        }
        if (!windows && exists(root, unixWrapper)) {
            evidence.add(unixWrapper + ", so the project's own " + toolName + " wrapper is used");
            return "./" + unixWrapper;
        }
        if (exists(root, windowsWrapper) || exists(root, unixWrapper)) {
            warnings.add("This project ships a " + toolName + " wrapper, but not the one this "
                + "machine can run, so the commands below use `" + bare + "` from PATH. That may "
                + "be a different version than the project expects.");
        }
        return bare;
    }

    /**
     * Directories under the root that carry a build file, when the root itself does not.
     *
     * <p>Pruned as it descends rather than filtered afterwards: one {@code node_modules} tree holds
     * tens of thousands of {@code package.json} files, and walking into it to throw the results
     * away turns an instant answer into a minute of disk.
     */
    private static List<String> subprojectsWithBuildFiles(Path root) {
        Set<String> found = new LinkedHashSet<>();
        scanForBuildFiles(root, root, 0, found);
        return List.copyOf(found);
    }

    private static void scanForBuildFiles(Path root, Path dir, int depth, Set<String> into) {
        if (depth > MONOREPO_SCAN_DEPTH) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path child : entries.toList()) {
                if (Files.isRegularFile(child) && isBuildFile(child.getFileName().toString())) {
                    Path parent = child.getParent();
                    if (parent != null && !parent.equals(root)) {
                        into.add(root.relativize(parent).toString().replace('\\', '/'));
                    }
                } else if (Files.isDirectory(child)
                        && !SKIP_DIRS.contains(child.getFileName().toString())) {
                    scanForBuildFiles(root, child, depth + 1, into);
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan {} for build files: {}", dir, e.getMessage());
        }
    }

    private static boolean isBuildFile(String name) {
        return switch (name) {
            case "pom.xml", "build.gradle", "build.gradle.kts", "package.json", "Cargo.toml",
                 "pyproject.toml", "setup.py" -> true;
            default -> false;
        };
    }

    private static boolean exists(Path root, String name) {
        return Files.isRegularFile(root.resolve(name));
    }

    private static String readCapped(Path file, int maxBytes) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            int length = Math.min(bytes.length, maxBytes);
            return new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Unreadable {}: {}", file, e.getMessage());
            return null;
        }
    }

    // --- rendering -----------------------------------------------------------------------------

    /**
     * Renders a detection as the {@code verify.yaml} the operator reviews and edits.
     *
     * <p>Hand-rendered rather than serialised, because the comments are the point: this file is
     * read by a person deciding whether these are the right commands, and a Jackson dump would
     * strip every explanation out of it. It round-trips — {@link VerifySpecLoader#parse} reads
     * back what this writes, which {@code ToolchainDetectorTest} asserts.
     */
    public static String render(Detection detection) {
        if (!detection.recognised()) {
            throw new IllegalArgumentException("nothing was detected, so there is nothing to render");
        }
        VerifySpec spec = detection.proposed();
        StringBuilder out = new StringBuilder();
        out.append("# SwarmCoder verification contract (spec §10.1) — PROPOSED, review before use.\n")
           .append("#\n")
           .append("# These are the commands SwarmCoder runs to decide whether a candidate passed.\n")
           .append("# A wrong command here costs a whole run, so read them. This file must live in\n")
           .append("# the project's own checkout: workers get worktrees, and a worker that could\n")
           .append("# edit this file could certify itself green.\n#\n");
        out.append("# Detected from:\n");
        for (String line : detection.evidence()) {
            out.append("#   - ").append(line).append('\n');
        }
        if (!detection.warnings().isEmpty()) {
            out.append("#\n# Decide these before trusting a run:\n");
            for (String warning : detection.warnings()) {
                out.append(wrapComment(warning));
            }
        }
        out.append('\n');
        out.append("toolchain: ").append(spec.toolchain()).append('\n');
        stage(out, "compile", spec.compile(),
            "must exit 0; a failure short-circuits everything after it");
        stage(out, "acceptance", spec.acceptance(),
            "the tests written for this run, in " + detection.acceptanceTestDir());
        stage(out, "existing", spec.existing(),
            "the project's own suite — this is what stops a candidate breaking what worked");
        stage(out, "lint", spec.lint(), "recorded and shown, but never kills a candidate");
        VerifySpec.TestReportsSpec reports = spec.testReports();
        if (reports != null) {
            out.append("\n# Where JUnit-format XML lands. These are walked as directories, not\n")
               .append("# globbed, so every one that can hold results has to be listed.\n")
               .append("testReports:\n");
            reportList(out, "acceptance", reports.acceptance());
            reportList(out, "existing", reports.existing());
        }
        browserBlock(out, spec.browser());
        out.append("\ntimeoutSeconds: ").append(spec.timeoutSeconds()).append('\n');
        return out.toString();
    }

    /**
     * Renders the browser block — the only stage that starts the application and looks at it.
     *
     * <p>Written with its explanation attached, because this is the stage an operator is most
     * likely to delete when it is inconvenient, and what it proves is the one thing nothing else in
     * the contract proves at all: that the candidate's application runs.
     */
    private static void browserBlock(StringBuilder out, VerifySpec.BrowserSpec browser) {
        out.append('\n');
        if (browser == null) {
            out.append("# browser: nothing proposed. Either this project is not a web application,\n")
               .append("# or no single command that starts it could be worked out - the warnings\n")
               .append("# above say which. Without this block NOTHING in verification ever starts\n")
               .append("# the application, and a candidate that compiles and cannot run is\n")
               .append("# declared delivered.\n");
            return;
        }
        out.append("# The browser check. This is the ONLY stage that starts the application and\n")
           .append("# looks at it with a real browser, and it decides survival: a candidate whose\n")
           .append("# application does not come up has not delivered one, whatever its tests say.\n")
           .append("# When the check cannot be attempted at all - no headless browser installed on\n")
           .append("# this machine, the port already taken - that is recorded and fails nobody.\n")
           .append("#\n")
           .append("# Add your own selectors under assertVisible as the application grows. \"body\"\n")
           .append("# only proves the page rendered; a selector naming the thing the operator asked\n")
           .append("# for is what proves the feature is actually there.\n");
        out.append("browser:\n");
        out.append("  serve: ").append(quote(browser.serve())).append('\n');
        if (browser.readyProbe() != null) {
            out.append("  readyProbe: ").append(quote(browser.readyProbe())).append('\n');
        }
        if (browser.hasFixedPort()) {
            out.append("  # This application hardcodes its port, so the harness cannot pick one.\n")
               .append("  port: ").append(browser.port()).append('\n');
        }
        if (browser.readyTimeoutSeconds() > 0) {
            out.append("  readyTimeoutSeconds: ").append(browser.readyTimeoutSeconds()).append('\n');
        }
        out.append("  checks:\n");
        for (VerifySpec.PageCheckSpec check : browser.checks()) {
            out.append("    - url: ").append(quote(check.url())).append('\n');
            out.append("      assertNoConsoleErrors: ").append(check.assertNoConsoleErrors()).append('\n');
            if (check.assertVisible() != null && !check.assertVisible().isEmpty()) {
                out.append("      assertVisible:\n");
                for (String selector : check.assertVisible()) {
                    out.append("        - ").append(quote(selector)).append('\n');
                }
            }
            out.append("      screenshot: ").append(check.screenshot()).append('\n');
        }
    }

    private static void stage(StringBuilder out, String name, List<String> commands, String note) {
        out.append('\n');
        if (commands == null || commands.isEmpty()) {
            out.append("# ").append(name).append(": nothing proposed — ").append(note)
               .append(".\n# Leaving this empty means the stage is skipped entirely.\n")
               .append(name).append(": []\n");
            return;
        }
        out.append("# ").append(note).append('\n').append(name).append(":\n");
        for (String command : commands) {
            out.append("  - ").append(quote(command)).append('\n');
        }
    }

    private static void reportList(StringBuilder out, String name, List<String> dirs) {
        if (dirs == null || dirs.isEmpty()) {
            return;
        }
        out.append("  ").append(name).append(":\n");
        for (String dir : dirs) {
            out.append("    - ").append(quote(dir)).append('\n');
        }
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Wraps a warning into comment lines short enough to read in a terminal. */
    private static String wrapComment(String text) {
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder("#   - ");
        for (String word : text.split("\\s+")) {
            if (line.length() + word.length() + 1 > 92) {
                out.append(line).append('\n');
                line = new StringBuilder("#     ");
            }
            line.append(word).append(' ');
        }
        out.append(line.toString().stripTrailing()).append('\n');
        return out.toString();
    }
}
