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

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The verification contract SwarmCoder writes itself, for a project that has none (section
 * 107).
 *
 * <p><b>Why.</b> A project made over an empty folder had no {@code .swarmcoder/verify.yaml}
 * and nothing wrote one: {@link ToolchainDetector} proposes a contract only when a person
 * opens the Console's dialog, and it reads build files that an empty folder does not have. A
 * whole build then ran with every candidate unverified. Earlier projects worked because their
 * starting files shipped a contract written by hand.
 *
 * <p><b>What it is made from.</b> The names of build files and nothing else: a {@code pom.xml}
 * is Maven, a {@code build.gradle} is Gradle. They are taken from the project's own folder
 * when it has any, and otherwise from the plan, whose tasks name the build files they create.
 * The commands are constants of this class. No model writes any of it and nothing a worker
 * writes is read to make it.
 *
 * <p><b>Where it goes.</b> Into the project's own folder, the place every reader takes the
 * contract from ({@link VerifySpecLoader#loadTrusted}) and no worker's command can reach: a
 * worker has a worktree of its own. A contract that is already there - one that reads or one
 * that does not - is never replaced.
 *
 * <p><b>What the Maven commands hold, and why</b> (each was a lost run once):
 * <ul>
 *   <li>{@code -o}: candidates build in containers with no network; a build that tries to
 *       reach one fails in a way that reads like a mistake in the code.</li>
 *   <li>{@code -B -q}: no prompts, and output short enough to be shown to a worker.</li>
 *   <li>Acceptance tests are selected by the PATH of the protected package
 *       ({@code swarm/accept}, with slashes). The dotted package form selects nothing and the
 *       stage then runs no test, which reads as green.
 *       {@code -Dsurefire.failIfNoSpecifiedTests=false} keeps a module with no such test from
 *       failing the build.</li>
 *   <li>Every command runs from the repository root, so a reactor of several modules orders
 *       itself.</li>
 *   <li>Reports are written per module, and report directories are walked, not searched: the
 *       contract names {@link #EVERY_MODULE}{@code /target/surefire-reports}, which is the
 *       directory of every build file on the tree at the moment the stage runs. The modules
 *       of a project built from nothing are not there yet when the contract is written.</li>
 * </ul>
 */
public final class GeneratedContract {

    private static final Logger log = LoggerFactory.getLogger(GeneratedContract.class);

    /**
     * In a report directory: every directory of the tree that holds a build file, the
     * repository root among them. Expanded by {@link #reportDirsOn} when a test stage runs.
     */
    public static final String EVERY_MODULE = "{module}";

    private static final List<String> MAVEN_FILES = List.of("pom.xml");
    private static final List<String> GRADLE_FILES =
        List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /** Never a module, whatever lies in it. */
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", ".mvn", ".venv", "venv",
        "node_modules", "target", "build", "dist", "out", "__pycache__", ".swarmcoder");

    private static final int MODULE_SCAN_DEPTH = 4;

    private GeneratedContract() {}

    /**
     * @param toolchain  maven | gradle
     * @param buildFiles the build files it was told of, repo-relative
     * @param spec       the contract
     * @param basis      where the build files were found, in words
     */
    public record Proposal(String toolchain, List<String> buildFiles, VerifySpec spec,
                           String basis) {}

    /**
     * The contract for the build system these build files belong to; empty when they name
     * none this class has standard commands for. A build file at the repository root decides;
     * with none there, the build files of modules do. Maven before Gradle when both are
     * named, as {@link ToolchainDetector} reads a folder.
     */
    public static Optional<Proposal> propose(Collection<String> buildFilePaths, String basis) {
        List<String> files = buildFilesAmong(buildFilePaths);
        String toolchain = toolchainOf(files.stream().filter(f -> !f.contains("/")).toList());
        if (toolchain == null) {
            toolchain = toolchainOf(files);
        }
        if (toolchain == null) {
            return Optional.empty();
        }
        return Optional.of(new Proposal(toolchain, files, specFor(toolchain), basis));
    }

    private static String toolchainOf(List<String> files) {
        if (files.stream().anyMatch(f -> MAVEN_FILES.contains(nameOf(f)))) {
            return "maven";
        }
        if (files.stream().anyMatch(f -> GRADLE_FILES.contains(nameOf(f)))) {
            return "gradle";
        }
        return null;
    }

    private static VerifySpec specFor(String toolchain) {
        if ("maven".equals(toolchain)) {
            List<String> reports = List.of(EVERY_MODULE + "/target/surefire-reports",
                EVERY_MODULE + "/target/failsafe-reports");
            return new VerifySpec("maven",
                List.of("mvn -o -B -q test-compile"),
                List.of("mvn -o -B -q test -Dtest=swarm/accept/** "
                    + "-Dsurefire.failIfNoSpecifiedTests=false"),
                List.of("mvn -o -B -q test"),
                List.of(), List.of(), new VerifySpec.TestReportsSpec(reports, reports),
                ToolchainDetector.DEFAULT_PROPOSED_TIMEOUT_SECONDS, null);
        }
        List<String> reports = List.of(EVERY_MODULE + "/build/test-results");
        return new VerifySpec("gradle",
            List.of("gradle --offline -q testClasses"),
            // The Gradle selector is dotted; only Surefire wants a path.
            List.of("gradle --offline -q test --tests 'swarm.accept.*'"),
            List.of("gradle --offline -q test"),
            List.of(), List.of(), new VerifySpec.TestReportsSpec(reports, reports),
            ToolchainDetector.DEFAULT_PROPOSED_TIMEOUT_SECONDS, null);
    }

    /** The contract as the file SwarmCoder writes; {@link VerifySpecLoader#parse} reads it back. */
    public static String render(Proposal proposal) {
        VerifySpec spec = proposal.spec();
        StringBuilder out = new StringBuilder();
        out.append("# SwarmCoder verification contract - GENERATED by SwarmCoder, not by a model.\n")
            .append("#\n")
            .append("# This project had no contract, so SwarmCoder wrote the standard commands\n")
            .append("# of its build system. They decide whether a candidate passed. Correct\n")
            .append("# them here if they are wrong for this project: a contract that is here is\n")
            .append("# never replaced. Remove the 'generated' line when you have made it yours.\n")
            .append("#\n")
            .append("# This file is read from the project's own folder only. Workers get a\n")
            .append("# checkout of their own, and a copy there is never used.\n\n");
        out.append(VerifySpecLoader.GENERATED_KEY).append(": ").append(quote(proposal.basis()))
            .append('\n');
        out.append("toolchain: ").append(spec.toolchain()).append('\n');
        stage(out, "compile", spec.compile());
        stage(out, "acceptance", spec.acceptance());
        stage(out, "existing", spec.existing());
        out.append("\n# Where JUnit-format XML lands. ").append(EVERY_MODULE)
            .append(" is every directory that\n# holds a build file when the stage runs, the ")
            .append("repository root among them.\n").append("testReports:\n");
        reports(out, "acceptance", spec.testReports().acceptance());
        reports(out, "existing", spec.testReports().existing());
        out.append("\ntimeoutSeconds: ").append(spec.timeoutSeconds()).append('\n');
        return out.toString();
    }

    /**
     * The contract in use for a project, written first when the project has none and its
     * build system can be told.
     *
     * @param trustedRoot       the project's own folder; null gives "no contract"
     * @param plannedBuildFiles the build files the plan's tasks name, used when the folder
     *                          itself holds no build file; may be empty
     */
    public static ContractInUse provide(Path trustedRoot, Collection<String> plannedBuildFiles) {
        if (trustedRoot == null || !Files.isDirectory(trustedRoot)) {
            return VerifySpecLoader.inUse(trustedRoot, null);
        }
        Path target = trustedRoot.resolve(VerifySpecLoader.SPEC_PATH);
        if (Files.exists(target)) {
            return VerifySpecLoader.inUse(trustedRoot, null); // never replaced, readable or not
        }
        List<String> inFolder = buildFilesIn(trustedRoot);
        Optional<Proposal> proposal = inFolder.isEmpty() ? Optional.empty()
            : propose(inFolder, "from the build files in the project's folder "
                + shortList(inFolder));
        if (proposal.isEmpty() && plannedBuildFiles != null) {
            List<String> planned = buildFilesAmong(plannedBuildFiles);
            proposal = propose(planned, "from the build files the plan names "
                + shortList(planned));
        }
        if (proposal.isEmpty()) {
            return VerifySpecLoader.inUse(trustedRoot, null);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, render(proposal.get()), StandardOpenOption.CREATE_NEW);
            log.info("Wrote a generated {} verification contract to {} ({})",
                proposal.get().toolchain(), target, proposal.get().basis());
        } catch (FileAlreadyExistsException raced) {
            // another stage of the same run wrote it a moment ago; that one is used
        } catch (IOException e) {
            log.warn("Could not write a generated verification contract to {}: {}", target,
                e.getMessage());
        }
        return VerifySpecLoader.inUse(trustedRoot, null);
    }

    /** The build files among {@code paths}, repo-relative with forward slashes, in order. */
    public static List<String> buildFilesAmong(Collection<String> paths) {
        Set<String> found = new LinkedHashSet<>();
        if (paths != null) {
            for (String raw : paths) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String path = raw.trim().replace('\\', '/').replaceAll("^(\\./)+", "")
                    .replaceAll("^/+", "");
                String name = nameOf(path);
                if ((MAVEN_FILES.contains(name) || GRADLE_FILES.contains(name))
                        && !path.contains("..")) {
                    found.add(path);
                }
            }
        }
        return List.copyOf(found);
    }

    /** The build files a folder holds, to a few levels, skipping build output. */
    public static List<String> buildFilesIn(Path root) {
        Set<String> found = new TreeSet<>();
        scan(root, root, 0, found);
        return List.copyOf(found);
    }

    private static void scan(Path root, Path dir, int depth, Set<String> into) {
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (Files.isDirectory(child)) {
                    if (depth < MODULE_SCAN_DEPTH && !SKIP_DIRS.contains(name)) {
                        scan(root, child, depth + 1, into);
                    }
                } else if (MAVEN_FILES.contains(name) || GRADLE_FILES.contains(name)) {
                    into.add(root.relativize(child).toString().replace('\\', '/'));
                }
            }
        } catch (IOException | RuntimeException e) {
            // a folder that cannot be listed holds no build file anybody can use
        }
    }

    /**
     * The layout a set of planned build files stands for, for placing the acceptance tests of
     * a project that does not exist yet; null when they name no module at all.
     *
     * <p>A directory with a build file and another build file beneath it is an aggregator: it
     * orders its modules and compiles nothing, so no test can be placed in it. Every other
     * directory with a build file is a module that compiles.
     *
     * @param dependsOn per module directory, the module directories its work is planned to
     *                  come after; may be empty
     */
    public static BuildLayout.Layout plannedLayout(
            Collection<String> buildFilePaths,
            Map<String, ? extends Collection<String>> dependsOn) {
        Optional<Proposal> proposal = propose(buildFilePaths, "");
        if (proposal.isEmpty()) {
            return null;
        }
        Set<String> dirs = new TreeSet<>();
        for (String file : proposal.get().buildFiles()) {
            if (nameOf(file).startsWith("settings.")) {
                continue;
            }
            dirs.add(file.contains("/") ? file.substring(0, file.lastIndexOf('/')) : "");
        }
        List<String> compiling = new ArrayList<>();
        for (String dir : dirs) {
            boolean aggregates = dirs.stream().anyMatch(other -> !other.equals(dir)
                && (dir.isEmpty() || other.startsWith(dir + "/")));
            if (!aggregates) {
                compiling.add(dir);
            }
        }
        if (compiling.isEmpty()) {
            return null;
        }
        List<String> roots = new ArrayList<>();
        List<BuildLayout.ModuleNode> graph = new ArrayList<>();
        for (String module : compiling) {
            String prefix = module.isEmpty() ? "" : module + "/";
            roots.add(prefix + "src/main/java");
            roots.add(prefix + "src/test/java");
            Collection<String> after = dependsOn == null ? null : dependsOn.get(module);
            graph.add(new BuildLayout.ModuleNode(module, after == null ? List.of()
                : after.stream().filter(d -> compiling.contains(d) && !d.equals(module))
                    .distinct().toList()));
        }
        return new BuildLayout.Layout(proposal.get().toolchain(), roots, compiling, graph,
            "Planned: this project has no build file yet; the plan names "
                + shortList(proposal.get().buildFiles()) + ".");
    }

    /**
     * Report directories as a test stage walks them: an entry that starts with
     * {@link #EVERY_MODULE} becomes one directory per build file on the tree, read at this
     * moment. Other entries are kept as they are.
     */
    public static List<String> reportDirsOn(ExecTarget target, List<String> reportDirs) {
        if (reportDirs == null) {
            return List.of();
        }
        if (reportDirs.stream().noneMatch(d -> d != null && d.startsWith(EVERY_MODULE))) {
            return reportDirs;
        }
        Set<String> modules = new TreeSet<>();
        modules.add("");
        List<String> names = new ArrayList<>(MAVEN_FILES);
        names.addAll(GRADLE_FILES);
        for (String name : names) {
            try {
                for (String file : target.listFiles(".", name)) {
                    String path = file.replace('\\', '/').replaceAll("^(\\./)+", "");
                    if (!nameOf(path).equals(name) || !path.contains("/")) {
                        continue;
                    }
                    String dir = path.substring(0, path.lastIndexOf('/'));
                    if (Stream.of(dir.split("/")).noneMatch(SKIP_DIRS::contains)) {
                        modules.add(dir);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // the root is still walked; a tree that cannot be listed has no other module
            }
        }
        List<String> expanded = new ArrayList<>();
        for (String dir : reportDirs) {
            if (dir == null || !dir.startsWith(EVERY_MODULE)) {
                expanded.add(dir);
                continue;
            }
            String rest = dir.substring(EVERY_MODULE.length()).replaceAll("^/+", "");
            for (String module : modules) {
                expanded.add(module.isEmpty() ? rest : module + "/" + rest);
            }
        }
        return expanded;
    }

    private static String nameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String shortList(List<String> files) {
        return "(" + String.join(", ", files.size() > 6 ? files.subList(0, 6) : files)
            + (files.size() > 6 ? ", ..." : "") + ")";
    }

    private static void stage(StringBuilder out, String name, List<String> commands) {
        out.append(name).append(":\n");
        for (String command : commands) {
            out.append("  - ").append(quote(command)).append('\n');
        }
    }

    private static void reports(StringBuilder out, String name, List<String> dirs) {
        out.append("  ").append(name).append(":\n");
        for (String dir : dirs) {
            out.append("    - ").append(quote(dir)).append('\n');
        }
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""))
            + "\"";
    }
}
