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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.knowledge.DeclarableArtifacts;
import com.swarmcoder.knowledge.RulesVersusManifest;
import com.swarmcoder.verify.BuildLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The build file of a module is part of the job of writing that module's code.
 *
 * <p><b>Why this exists, in the operator's words.</b> "I cannot have a frontier model holding the
 * hand of SwarmCoder because it can't add a dependency to a pom file." Run {@code ede2068b}
 * (2026-09-03): the project's technical rules said persistence goes through
 * {@code zerozstack-store-eclipsestore}, the server module's pom declared no such dependency, and
 * every worker's write set stopped at {@code src/main/java}. Four workers found the contradiction
 * independently, none of them could act on it, and one concluded — correctly, for the world it had
 * been put in — "the poms are locked, so the server must use an in-memory root". All four were
 * killed for making no progress, having written nothing. A person then added one line to a pom.
 *
 * <p>Two changes come out of that, and this class is both of them:
 *
 * <ol>
 *   <li><b>{@link #expandWriteSets}</b> — a task that may write a module's sources may write that
 *       module's build file. Unconditionally, for every plan: needing a dependency is not a special
 *       occasion, and a boundary that has to be predicted at PLAN time will be wrong.</li>
 *   <li><b>{@link #declareMissing}</b> — when a stated rule names an artifact the build does not
 *       declare, and that artifact can be resolved offline, the declaration becomes WORK in the
 *       plan rather than a reason to stop and fetch a human.</li>
 * </ol>
 *
 * <p><b>What is still protected is unchanged.</b> {@code .git}, {@code .swarmcoder} (which holds
 * the verification commands the host runs unsandboxed), an operator-locked module and the task's
 * acceptance tests are refused by {@link com.swarmcoder.runtime.PathPolicy} exactly as before. A
 * build file was never on that list — it was simply never in anybody's write set, which is a
 * different thing and had the same effect.
 */
final class BuildFilesInTheJob {

    private BuildFilesInTheJob() {}

    /**
     * The build files of a module, in the order they would be preferred.
     *
     * <p>Maven has one answer. Gradle has two spellings of the same file and a project uses one of
     * them, so the one that EXISTS is chosen; when neither does (a module the plan intends to
     * create), the Kotlin spelling is the modern default and either way the path policy allows it.
     */
    static List<String> buildFilesOf(String moduleDir, BuildLayout.Layout layout, Path repoRoot) {
        String prefix = moduleDir == null || moduleDir.isEmpty() ? "" : moduleDir + "/";
        if (layout != null && "gradle".equals(layout.toolchain())) {
            for (String name : List.of("build.gradle.kts", "build.gradle")) {
                if (repoRoot != null && Files.isRegularFile(repoRoot.resolve(prefix + name))) {
                    return List.of(prefix + name);
                }
            }
            return List.of(prefix + "build.gradle.kts");
        }
        return List.of(prefix + "pom.xml");
    }

    /** True for a path that is a module's build file rather than something the build compiles. */
    static boolean isBuildFile(String path) {
        if (path == null) {
            return false;
        }
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = slash < 0 ? name : name.substring(slash + 1);
        return "pom.xml".equals(name) || "build.gradle".equals(name)
            || "build.gradle.kts".equals(name);
    }

    /**
     * Adds every write-set module's build file to that task's write set.
     *
     * <p>Mutates the graph in place, the way {@code withAcceptanceTestDir} does, and returns it.
     *
     * <p><b>A task with NO write set is left alone.</b> An empty write set is unrestricted — the
     * single-task fallback graph is the case — so the build files are already inside it.
     *
     * <p><b>Two concurrent tasks in the same module both get the same build file, on purpose.</b>
     * Handing it to only one of them would mean the other cannot declare what it needs, which is
     * the whole defect this class exists to remove. The write-set disjointness rule therefore
     * ignores build files ({@code TaskGraphValidator}); a dependency declaration is an additive
     * three-line block, two of them usually merge, and when they do not the integrator parks the
     * run naming both candidates — which is what it does for any conflicting merge and is a far
     * better outcome than a task that cannot state what it depends on.
     */
    static TaskGraph expandWriteSets(TaskGraph graph, BuildLayout.Layout layout, Path repoRoot) {
        if (graph == null || graph.tasks() == null || layout == null || !layout.determined()) {
            return graph;
        }
        for (Task task : graph.tasks()) {
            Set<String> writeSet = task.writeSet();
            if (writeSet == null || writeSet.isEmpty()) {
                continue;
            }
            Set<String> expanded = new LinkedHashSet<>(writeSet);
            for (String module : modulesWritten(writeSet, layout)) {
                expanded.addAll(buildFilesOf(module, layout, repoRoot));
            }
            task.setWriteSet(expanded);
        }
        return graph;
    }

    /**
     * The compiling modules a write set reaches into.
     *
     * <p>Longest match wins, so a path in a nested module is attributed to the nested module and
     * not to its parent directory. A path under no module at all yields nothing: the validator
     * already refuses or warns about those, and inventing a build file for a directory the build
     * does not know is not this class's business.
     */
    static Set<String> modulesWritten(Set<String> writeSet, BuildLayout.Layout layout) {
        Set<String> modules = new TreeSet<>();
        if (writeSet == null || layout == null) {
            return modules;
        }
        for (String raw : writeSet) {
            String path = BuildLayout.normalize(raw);
            if (path == null || path.isEmpty()) {
                continue;
            }
            String best = null;
            for (String module : layout.compilingModules()) {
                if (module == null) {
                    continue;
                }
                boolean covers = module.isEmpty()
                    || path.equals(module) || path.startsWith(module + "/");
                if (covers && (best == null || module.length() > best.length())) {
                    best = module;
                }
            }
            if (best != null) {
                modules.add(best);
            }
        }
        return modules;
    }

    // ------------------------------------------------------------- a rule that names a dependency

    /**
     * One dependency the plan is now responsible for declaring.
     *
     * @param module    the module whose build file gains it, repo-relative
     * @param buildFile that module's build file, repo-relative
     * @param reason    why THAT module — said out loud, because it is a judgement and the operator
     *                  is entitled to disagree with it
     */
    record Declaration(String artifactId, String coordinate, String version, String managedBy,
                       String module, String buildFile, String reason, String ruleExcerpt,
                       String source) {

        /**
         * The sentence appended to the task that must do it.
         *
         * <p>It ends by naming the mechanical way, and by saying the worker need not use it. A pom
         * is XML with two dependency lists that mean opposite things, and this codebase now has a
         * refactoring recipe that puts a block in the right one and leaves the rest of the file
         * byte-identical ({@link com.swarmcoder.knowledge.MavenRecipes}). Telling the worker that
         * costs one sentence and removes the reason it might invent something worse. Telling it
         * that it MUST use the recipe would be a mistake of a different kind: the worker has a
         * build to answer to, and any edit that compiles is a correct edit.
         */
        String instruction() {
            String sourceLabel = source == null
                ? "the project's rules require it" : "the technical document \"" + source
                    + "\" requires it";
            return "Also declare `" + coordinate + "` in " + buildFile
                + " — " + sourceLabel + " (\"" + ruleExcerpt + "\") and no module of "
                + "this build declares it yet. Its version is already managed by " + managedBy
                + ", so add groupId and artifactId with NO <version> element. That file is in your "
                + "write set. Change nothing else about the build.\n\n"
                + "If you would rather not hand-edit the XML, this project applies OpenRewrite's "
                + "`org.openrewrite.maven.AddDependency` recipe for exactly this, which puts the "
                + "block in the module's own <dependencies> list — never in "
                + "<dependencyManagement> — and leaves every other byte of the file alone. Either "
                + "way is fine; the build is what decides.";
        }
    }

    /**
     * What to do about the rules naming artifacts the build does not declare: either work added to
     * the plan, or a park with a reason no worker could have fixed.
     *
     * @param parkBrief null when the run may proceed
     */
    record Outcome(TaskGraph graph, List<Declaration> declarations, String parkBrief) {
        static Outcome nothingToDo(TaskGraph graph) {
            return new Outcome(graph, List.of(), null);
        }

        boolean parks() {
            return parkBrief != null;
        }
    }

    /**
     * Turns each finding into work, or parks on the one thing work cannot fix.
     *
     * <p><b>The one park.</b> An artifact the offline Maven repository does not hold is not a
     * planning mistake and not a coding mistake: the sandbox runs with {@code network=none}, so no
     * worker, no candidate and no amount of retrying can obtain it. That is an environment fact,
     * and the only useful thing to do with it is to say which artifact and which directory a person
     * must put it in. Everything else — the artifact exists offline, the build simply has not
     * declared it — is now a three-line edit inside the job.
     *
     * @param declaredByModule what each compiling module already declares, used only to break the
     *                         tie when the rule does not say which module it is about
     * @param declaredCoordinatesByModule each module's own declared dependency coordinates — read
     *                         before the fewest-dependencies tie-break to see whether the artifact's
     *                         own name already points at a module wired into that tier (see
     *                         {@link #moduleOfMatchingTier})
     */
    static Outcome declareMissing(TaskGraph graph, List<RulesVersusManifest.Finding> findings,
                                  DeclarableArtifacts.Catalog catalog, BuildLayout.Layout layout,
                                  Map<String, Integer> declaredByModule,
                                  Map<String, List<String>> declaredCoordinatesByModule,
                                  Path repoRoot) {
        if (graph == null || findings == null || findings.isEmpty()) {
            return Outcome.nothingToDo(graph);
        }
        if (catalog == null || !catalog.repositoryPresent()) {
            // There is no local Maven repository on this machine to check against. "Nothing is
            // declarable" and "nothing was checked" are different answers, and parking on the
            // second would stop a run over a fact about the machine rather than a contradiction in
            // the project — the same refusal to guess that BuildLayout makes when it cannot read a
            // reactor. Nothing is claimed and nothing is added.
            return Outcome.nothingToDo(graph);
        }
        List<Declaration> declarations = new ArrayList<>();
        StringBuilder park = new StringBuilder();
        for (RulesVersusManifest.Finding finding : findings) {
            Optional<DeclarableArtifacts.Artifact> offline = catalog == null
                ? Optional.empty() : catalog.byArtifactId(finding.artifact());
            if (offline.isEmpty()) {
                if (park.length() > 0) {
                    park.append("\n\n");
                }
                park.append(parkBrief(finding, catalog));
                continue;
            }
            DeclarableArtifacts.Artifact artifact = offline.get();
            String module = moduleFor(finding, layout, graph, declaredByModule,
                declaredCoordinatesByModule);
            if (module == null) {
                if (park.length() > 0) {
                    park.append("\n\n");
                }
                park.append("This project says — in ").append(finding.sourceLabel())
                    .append(" — it uses `").append(finding.artifact())
                    .append("`, and that artifact IS in the offline Maven repository, but this "
                        + "build has no module to declare it in — nothing readable compiles "
                        + "anything. Add it by hand, or fix the build so its modules can be read.");
                continue;
            }
            String buildFile = buildFilesOf(module, layout, repoRoot).get(0);
            declarations.add(new Declaration(artifact.artifactId(), artifact.coordinate(),
                artifact.version(), artifact.managedBy(), module, buildFile,
                reasonFor(finding, module, layout, graph, declaredCoordinatesByModule),
                finding.ruleExcerpt(), finding.source()));
        }
        if (park.length() > 0) {
            return new Outcome(graph, declarations, park.toString());
        }
        TaskGraph withWork = graph;
        for (Declaration declaration : declarations) {
            withWork = attach(withWork, declaration, layout);
        }
        return new Outcome(withWork, declarations, null);
    }

    /** The brief for the one case a run still parks on: the artifact is not on the disk. */
    private static String parkBrief(RulesVersusManifest.Finding finding,
                                    DeclarableArtifacts.Catalog catalog) {
        String repository = catalog == null || catalog.localRepository() == null
            ? "the local Maven repository" : catalog.localRepository().toString();
        return "This project says — in " + finding.sourceLabel() + " — it uses `"
            + finding.artifact() + "`, no module of this "
            + "build declares it (checked: " + String.join(", ", finding.inspectedPoms())
            + "), and it is not in the Maven repository this machine builds from either — looked "
            + "in " + repository + ".\n\nA worker could add the dependency; it could not obtain "
            + "the files. Candidate builds run in a container with NO network, resolving against "
            + "that directory alone, so nothing inside the run can fetch it. Install "
            + "`" + finding.artifact() + "` into that repository (for a local project, `mvn "
            + "install` it), or change the rule, then build it again.\n\nSaid there: \""
            + finding.ruleExcerpt() + "\"";
    }

    /**
     * Which module gets the dependency.
     *
     * <p><b>First the rule's own words.</b> A rule about the server ("the server keeps the live
     * Java objects in memory") is about the server module, and the module directory's own name
     * says so — {@code bookshelf-demo-server} carries the word {@code server}. This is a guess and
     * it is stated as one: the chosen module and the reason are logged and put on the task, so an
     * operator reading the run can see the decision rather than discovering it in a diff.
     *
     * <p><b>Then the artifact's own tier.</b> The rule may say nothing about which module, but the
     * ARTIFACT often still does: {@code zerozstack-store-eclipsestore} is a persistence artifact by
     * its own name, and a module that already depends on this build's server-side foundation is
     * where persistence goes. See {@link #moduleOfMatchingTier}. This is weaker evidence than the
     * rule naming a module outright, and stronger than counting dependencies, so it sits between
     * the two.
     *
     * <p><b>Then the plan.</b> Among the modules the plan actually writes to, the one with the
     * FEWEST declared dependencies — the least furnished module is the one most likely to be
     * missing something. Then any module the plan writes to, then any module at all, each
     * tie-broken alphabetically so the same plan always produces the same answer.
     */
    static String moduleFor(RulesVersusManifest.Finding finding, BuildLayout.Layout layout,
                            TaskGraph graph, Map<String, Integer> declaredByModule,
                            Map<String, List<String>> declaredCoordinatesByModule) {
        if (layout == null || layout.compilingModules().isEmpty()) {
            return null;
        }
        String named = moduleNamedIn(finding.ruleExcerpt(), layout);
        if (named != null) {
            return named;
        }
        String tiered = moduleOfMatchingTier(finding.artifact(), layout, declaredCoordinatesByModule);
        if (tiered != null) {
            return tiered;
        }
        Set<String> written = new TreeSet<>();
        if (graph != null && graph.tasks() != null) {
            for (Task task : graph.tasks()) {
                written.addAll(modulesWritten(task.writeSet(), layout));
            }
        }
        List<String> candidates = new ArrayList<>(
            written.isEmpty() ? new TreeSet<>(layout.compilingModules()) : written);
        candidates.sort((a, b) -> {
            int byCount = Integer.compare(declaredCount(declaredByModule, a),
                declaredCount(declaredByModule, b));
            return byCount != 0 ? byCount : a.compareTo(b);
        });
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static int declaredCount(Map<String, Integer> declaredByModule, String module) {
        Integer count = declaredByModule == null ? null : declaredByModule.get(module);
        return count == null ? Integer.MAX_VALUE : count;
    }

    /**
     * A compiling module whose directory name the rule text uses as a word.
     *
     * <p>Only distinctive segments count. {@code bookshelf-demo-server} contributes {@code server}
     * but not {@code demo}: a word that names the project rather than a tier appears in every rule
     * and would match everything. Short segments are dropped for the same reason.
     */
    private static String moduleNamedIn(String ruleText, BuildLayout.Layout layout) {
        if (ruleText == null || ruleText.isBlank()) {
            return null;
        }
        String text = ruleText.toLowerCase(java.util.Locale.ROOT);
        Set<String> tooCommon = Set.of("demo", "main", "core", "app", "impl", "java", "test",
            "project", "module", "root");
        String best = null;
        for (String module : new TreeSet<>(layout.compilingModules())) {
            if (module == null || module.isEmpty()) {
                continue;
            }
            String leaf = module.substring(module.lastIndexOf('/') + 1)
                .toLowerCase(java.util.Locale.ROOT);
            for (String segment : leaf.split("[-_.]")) {
                if (segment.length() < 4 || tooCommon.contains(segment)) {
                    continue;
                }
                if (mentionsWord(text, segment) && (best == null || module.length() < best.length())) {
                    best = module;
                }
            }
        }
        return best;
    }

    /** Whole-word containment, so "client" does not match "clientele". */
    private static boolean mentionsWord(String text, String word) {
        int from = 0;
        while (true) {
            int at = text.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            boolean leftClear = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            int end = at + word.length();
            boolean rightClear = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (leftClear && rightClear) {
                return true;
            }
            from = at + 1;
        }
    }

    /**
     * A compiling module already wired into the TIER the artifact's own name says it belongs to —
     * read from what each candidate module already depends on, never from the rule's own words
     * (that is {@link #moduleNamedIn}, tried first and always preferred when it finds something).
     *
     * <p>An artifact called {@code ...store...}, {@code ...server...} or {@code ...persistence...}
     * is a server-side concern; one called {@code ...client...} or {@code ...ui...} is a
     * client-side one. That alone is not enough to pick a module — plenty of artifact names are
     * accidents of a naming scheme — so this only fires when a candidate module's OWN declared
     * dependencies confirm it: something in this build's server (respectively client) foundation is
     * already there. No module confirms it, no answer: the caller falls back to counting
     * dependencies, the same as it always did.
     */
    private static String moduleOfMatchingTier(String artifactId, BuildLayout.Layout layout,
            Map<String, List<String>> declaredCoordinatesByModule) {
        if (artifactId == null || layout == null || declaredCoordinatesByModule == null
                || declaredCoordinatesByModule.isEmpty()) {
            return null;
        }
        String lower = artifactId.toLowerCase(java.util.Locale.ROOT);
        String hint;
        if (mentionsAnyWord(lower, "store", "server", "persistence")) {
            hint = "zerozstack-server";
        } else if (mentionsAnyWord(lower, "client", "ui")) {
            hint = "zerozstack-client";
        } else {
            return null;
        }
        for (String module : new TreeSet<>(layout.compilingModules())) {
            for (String coordinate : declaredCoordinatesByModule.getOrDefault(module, List.of())) {
                if (coordinate != null
                        && coordinate.toLowerCase(java.util.Locale.ROOT).contains(hint)) {
                    return module;
                }
            }
        }
        return null;
    }

    private static boolean mentionsAnyWord(String text, String... words) {
        for (String word : words) {
            if (mentionsWord(text, word)) {
                return true;
            }
        }
        return false;
    }

    private static String reasonFor(RulesVersusManifest.Finding finding, String module,
                                    BuildLayout.Layout layout, TaskGraph graph,
                                    Map<String, List<String>> declaredCoordinatesByModule) {
        if (module.equals(moduleNamedIn(finding.ruleExcerpt(), layout))) {
            return "the rule names it";
        }
        if (module.equals(moduleOfMatchingTier(finding.artifact(), layout,
                declaredCoordinatesByModule))) {
            return "the rule does not say which module, but `" + finding.artifact() + "` names its "
                + "own tier and " + module + " already depends on that tier";
        }
        Set<String> written = new TreeSet<>();
        if (graph != null && graph.tasks() != null) {
            for (Task task : graph.tasks()) {
                written.addAll(modulesWritten(task.writeSet(), layout));
            }
        }
        return written.contains(module)
            ? "the rule does not say which module, so the one this plan writes to with the fewest "
                + "dependencies of its own was chosen"
            : "the rule does not say which module and this plan writes to none of them, so the one "
                + "with the fewest dependencies of its own was chosen";
    }

    /**
     * Puts the declaration where it will actually be done.
     *
     * <p>The first task that already writes that module's sources gets the instruction — it is
     * going to open the module anyway, and its write set already contains the build file, because
     * {@link #expandWriteSets} ran first. Only when NO task writes that module is a task added, and
     * then it is a single small one that every root task waits on, so the dependency is declared
     * before anything tries to compile against it.
     */
    private static TaskGraph attach(TaskGraph graph, Declaration declaration,
                                    BuildLayout.Layout layout) {
        Task unrestricted = null;
        for (Task task : graph.tasks()) {
            Set<String> writeSet = task.writeSet();
            if (writeSet == null || writeSet.isEmpty()) {
                // An empty write set is UNRESTRICTED — the single-task fallback graph. It may
                // already write the build file, so it needs the instruction and nothing else; but
                // a task that names the module explicitly is a better home, so keep looking first.
                if (unrestricted == null) {
                    unrestricted = task;
                }
                continue;
            }
            if (!modulesWritten(writeSet, layout).contains(declaration.module())) {
                continue;
            }
            instruct(task, declaration);
            if (!writeSet.contains(declaration.buildFile())) {
                Set<String> expanded = new LinkedHashSet<>(writeSet);
                expanded.add(declaration.buildFile());
                task.setWriteSet(expanded);
            }
            return graph;
        }
        if (unrestricted != null) {
            instruct(unrestricted, declaration);
            return graph;
        }
        return prependEnabler(graph, declaration);
    }

    private static void instruct(Task task, Declaration declaration) {
        task.setInstructions((task.instructions() == null ? "" : task.instructions() + "\n\n")
            + declaration.instruction());
    }

    /**
     * A task of its own, ahead of everything, when no planned task touches that module.
     *
     * <p>It carries no acceptance criterion, and that is correct rather than an omission: criteria
     * belong to the story's slice and every one of them is already claimed by a real task. This
     * task exists so that the others can compile, and the build compiling is what proves it.
     */
    private static TaskGraph prependEnabler(TaskGraph graph, Declaration declaration) {
        List<Task> tasks = new ArrayList<>(graph.tasks());
        if (tasks.isEmpty()) {
            return graph;
        }
        Set<UUID> hasIncoming = new HashSet<>();
        List<TaskEdge> edges = new ArrayList<>(
            graph.dependencies() == null ? List.<TaskEdge>of() : graph.dependencies());
        for (TaskEdge edge : edges) {
            hasIncoming.add(edge.to());
        }
        Task enabler = new Task(UUID.randomUUID(), 1L,
            "Declare " + declaration.artifactId() + " in " + declaration.module(),
            declaration.instruction() + "\n\nThat one declaration is the whole task. Do not write "
                + "or change any source file.",
            new LinkedHashSet<>(List.of(declaration.buildFile())),
            new LinkedHashSet<>(), List.of(), tasks.get(0).acceptanceTestDir(), null,
            tasks.get(0).budget(), tasks.get(0).swarmPolicy(), com.swarmcoder.domain.TaskState.PENDING);
        for (Task task : tasks) {
            if (!hasIncoming.contains(task.id())) {
                edges.add(new TaskEdge(enabler.id(), task.id()));
            }
        }
        tasks.add(0, enabler);
        return new TaskGraph(graph.id(), graph.revision(), graph.designId(), tasks, edges);
    }
}
