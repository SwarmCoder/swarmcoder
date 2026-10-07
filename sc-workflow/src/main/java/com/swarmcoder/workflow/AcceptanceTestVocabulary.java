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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Task;
import com.swarmcoder.knowledge.ContractDelivery;
import com.swarmcoder.knowledge.JavaSourceFacts;
import com.swarmcoder.knowledge.ProjectTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An acceptance test may name only the types the design has agreed and the types the checkout
 * already holds. Anything else is a type nobody will ever deliver, and a test that names one is
 * broken from the moment it is written.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>2026-09-03, run 13, story "Assign a rating to a book". The design fixed six contracts; the
 * plan was three waves — the shared data model, the server service, then the client screen that
 * owns the one check. Waves one and two built, were verified and were integrated. Then both
 * candidates of wave three failed with the same sentence: the tree does not compile, because the
 * acceptance test refers to {@code com.swarmcoder.demo.bookshelf.Rating}, which does not exist.
 *
 * <p>Nobody was lying. The test author had invented {@code Rating}; wave one had delivered a
 * {@code Book} carrying a rating field instead; the wave-three task writes only the client module
 * and so could not have created a shared type even if it had wanted to. And every gate said the
 * right thing: "does not compile" is a valid red state, because a test written before the code is
 * SUPPOSED not to compile. Three waves of work arrived at a test that could never pass.
 *
 * <h2>The line this class draws</h2>
 *
 * <p>"Does not compile because the delivered types are missing" is red and healthy. "Does not
 * compile because the test names a type nobody will deliver" is a broken test. The two are told
 * apart by comparing the names in the test against the design's contracts and the types already in
 * the tree, which is a file read and no model call.
 *
 * <h2>The run this also exists because of</h2>
 *
 * <p>2026-09-25, run 38, story "a bookshelf demo". The design fixed four contracts —
 * {@code Book}, {@code Rating}, {@code BookshelfService}, {@code BookshelfStore} — all interfaces
 * or plain data; the plan's task 5, "Implement BookshelfServiceImpl coordinating service and
 * store", had the write set {@code .../server/BookshelfServiceImpl.java} (and task 4 likewise
 * wrote {@code BookshelfStoreImpl.java}). The test author's first attempt named
 * {@code com.swarmcoder.demo.bookshelf.server.BookshelfServiceImpl} to get hold of a real service,
 * and was refused here as a type "nothing in this plan delivers" — although task 5's own write set
 * says, word for word, that it does. Told instead to obtain the delivered implementation the way
 * the application does, its second attempt implemented {@code BookshelfService} itself and was
 * refused by {@link SelfImplementedContract}, correctly. Between the two rules the test author had
 * no legal way left to reach a real implementation of anything task 5 or task 4 built: naming the
 * interface got it accused of standing in for the contract, and naming the implementation class got
 * it accused of inventing a type.
 *
 * <p>The fix: a type whose source file sits in some task's write set — a compiled source root, the
 * package path, and {@code SimpleName.java}, exactly as {@link
 * com.swarmcoder.verify.TypeDeliverability#typeNamed} reads it — is exactly as real, for vocabulary
 * purposes, as a design contract. The plan is going to create it either way; the only difference is
 * who wrote the promise down. See {@link PlannedImplementations} for where this is read, and
 * {@link Check#vocabulary()} for how it reaches the author. The run-13 shape this class was built
 * for is unchanged: a name that is in NEITHER the design's contracts NOR any task's write set is
 * still refused, because then nobody, anywhere in the plan, has promised to create it.
 *
 * <h2>What it will not do</h2>
 *
 * <p>Accuse on a guess. A name is reported only when it is unambiguously the project's own: an
 * import into a package this project writes code in, or a simple name that is nowhere in the
 * checkout, is not a contract, is not declared in the test itself, and is not a JDK type. A
 * reference to a library, a wildcard import, a static import and an unreadable file all pass in
 * silence, because a false accusation here re-asks the test author and then stops a run.
 */
public final class AcceptanceTestVocabulary {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceTestVocabulary.class);

    private AcceptanceTestVocabulary() {}

    /** One name a written test uses that nothing in this run will ever deliver. */
    public record Unknown(String path, String typeName, List<String> realTypes) {

        public Unknown {
            realTypes = realTypes == null ? List.of() : List.copyOf(realTypes);
        }

        public Unknown(String path, String typeName) {
            this(path, typeName, List.of());
        }

        public String render() {
            return "`" + typeName + "` (in " + path + ")" + (realTypes.isEmpty() ? ""
                : " — a type of that name does exist: " + String.join(", ", realTypes));
        }

        /**
         * The one real type this name can only have meant, or null: the types of the same simple
         * name that the checkout, the design's contracts or the plan's write sets hold, when there
         * is exactly one (live run 68, 2026-10-02).
         */
        public String onlyRealType() {
            return realTypes.size() == 1 && typeName.contains(".") ? realTypes.get(0) : null;
        }
    }

    /**
     * @param unknowns   the names nothing will deliver; empty means the test is answerable
     * @param vocabulary the contract types the author may use, rendered as it is shown to it
     */
    public record Check(List<Unknown> unknowns, List<String> vocabulary) {

        public static final Check CLEAN = new Check(List.of(), List.of());

        public boolean ok() {
            return unknowns.isEmpty();
        }
    }

    /**
     * Reads the tests just written and reports every type they name that neither the design nor
     * the checkout provides. Equivalent to {@link #check(Path, DesignDocument, List, List)} with no
     * plan tasks — kept so a caller with no plan to offer behaves exactly as it did before harness
     * run 38.
     *
     * @param repoRoot  the tree the tests were written into — the run's own tests worktree
     * @param design    the design whose contracts are the agreed vocabulary; null means there is
     *                  no agreed vocabulary and nothing is checked
     * @param testPaths the repo-relative files the author just wrote
     */
    public static Check check(Path repoRoot, DesignDocument design, List<String> testPaths) {
        return check(repoRoot, design, testPaths, List.of());
    }

    /**
     * Reads the tests just written and reports every type they name that neither the design, the
     * checkout, nor the plan's own tasks provide.
     *
     * @param repoRoot  the tree the tests were written into — the run's own tests worktree
     * @param design    the design whose contracts are the agreed vocabulary; null means there is
     *                  no agreed vocabulary and nothing is checked
     * @param testPaths the repo-relative files the author just wrote
     * @param planTasks every task in this run's plan — not just the one the test was written for.
     *                  A type whose source file sits in ANY of their write sets is exactly as real
     *                  as a design contract, because the plan is going to create it either way (see
     *                  the run-38 paragraph in this class's javadoc). Empty or null means there is
     *                  nothing beyond the design and the checkout, exactly as before that run.
     */
    public static Check check(Path repoRoot, DesignDocument design, List<String> testPaths,
                              List<Task> planTasks) {
        if (repoRoot == null || design == null || testPaths == null || testPaths.isEmpty()) {
            return Check.CLEAN;
        }
        List<ApiContract> contracts = design.contracts() == null ? List.of() : design.contracts();
        List<String> vocabulary = new ArrayList<>();
        Set<String> contractFullNames = new LinkedHashSet<>();
        Set<String> contractSimpleNames = new LinkedHashSet<>();
        Set<String> contractPackages = new LinkedHashSet<>();
        for (ApiContract contract : contracts) {
            if (contract == null || !contract.namesAType()) {
                continue;
            }
            contractFullNames.add(contract.typeName().strip());
            contractSimpleNames.add(contract.simpleTypeName());
            if (!contract.packageName().isEmpty()) {
                contractPackages.add(contract.packageName());
            }
            vocabulary.add(contract.describe());
        }
        if (contractFullNames.isEmpty()) {
            // The design fixed no type names at all, so there is no vocabulary to hold anyone to.
            // Checking against the checkout alone would refuse every test that names the class the
            // run is about to create, which is every acceptance test there has ever been.
            return Check.CLEAN;
        }

        // The plan's OWN promises, read off its tasks' write sets (harness run 38, 2026-09-25): a
        // concrete implementation class a task is about to write is exactly as real, for vocabulary
        // purposes, as a type the design fixed. Kept apart from contractFullNames/contractSimpleNames
        // above only so the vocabulary list can say WHO delivers each one.
        Map<String, String> plannedByFullName = new LinkedHashMap<>(); // fullName -> task title
        Map<String, List<String>> plannedBySimpleName = new LinkedHashMap<>(); // simple -> fullNames
        for (PlannedImplementations.Planned planned : PlannedImplementations.of(planTasks)) {
            plannedByFullName.putIfAbsent(planned.typeName(), planned.taskTitle());
            plannedBySimpleName.computeIfAbsent(planned.simpleName(), k -> new ArrayList<>())
                .add(planned.typeName());
            vocabulary.add(planned.describe());
        }

        ProjectTypes tree = ProjectTypes.of(repoRoot);
        List<Unknown> unknowns = new ArrayList<>();
        for (String path : testPaths) {
            String source = read(repoRoot, path);
            if (source.isEmpty()) {
                continue; // unreadable: told us nothing, so nothing is concluded
            }
            JavaSourceFacts facts = JavaSourceFacts.of(source);
            Set<String> declaredHere = new LinkedHashSet<>(facts.declaredTypes());
            Map<String, String> imported = new LinkedHashMap<>();
            // `import com.library.ui.*;` brings in types this check cannot list: with one of those
            // in the file, a simple name it does not know may be one of them, so it establishes
            // nothing about simple names and says nothing (the compiler will, at the red check).
            String openWildcard = null;
            for (String statement : facts.imports()) {
                String simple = JavaSourceFacts.importedSimpleName(statement);
                if (simple.isEmpty()) {
                    String text = statement.strip();
                    if (!text.startsWith("static ") && text.endsWith(".*")) {
                        String pkg = text.substring(0, text.length() - 2);
                        if (!contractPackages.contains(pkg) && !tree.isProjectPackage(pkg)) {
                            openWildcard = pkg;
                        }
                    }
                    continue; // a wildcard or a static import names no single type
                }
                String fullName = statement.strip();
                imported.put(simple, fullName);
                if (contractFullNames.contains(fullName) || tree.declares(fullName)
                        || plannedByFullName.containsKey(fullName)) {
                    continue;
                }
                int cut = fullName.length() - simple.length() - 1;
                String pkg = cut <= 0 ? "" : fullName.substring(0, cut);
                if (contractPackages.contains(pkg) || tree.isProjectPackage(pkg)) {
                    String generatedFrom = generatedFrom(simple, pkg, tree, contractFullNames,
                        plannedByFullName.keySet());
                    if (generatedFrom != null
                            || ContractDelivery.builtByTheBuild(repoRoot, fullName)) {
                        // The build writes it (harness run 72: Qso_Rules, generated from Qso by
                        // an annotation processor): nobody hand-writes it and no task is planned
                        // for it, and it exists once the type it is made from does.
                        log.info("{}: {} is not a contract and not in the sources, but {}; not "
                            + "treated as a type nobody delivers", path, fullName,
                            generatedFrom != null ? "its name says the build generates it from "
                                + generatedFrom : "the build output has it");
                        continue;
                    }
                    // A package THIS project writes into, and no such type in it: the author made
                    // the name up, or it belongs to a contract that was never agreed.
                    add(unknowns, path, fullName, realTypesNamed(simple, tree, contractFullNames,
                        plannedBySimpleName));
                }
            }
            if (openWildcard != null) {
                log.info("{}: imports {}.*, so the simple names it uses are not checked against "
                    + "the contracts (any of them may come from that package)", path, openWildcard);
                continue;
            }
            for (String simple : facts.referencedTypeNames()) {
                if (simple.length() == 1 || simple.equals(simple.toUpperCase(java.util.Locale.ROOT))) {
                    // CALL.length(), SENT.ordinal(), T value: a constant, a statically imported
                    // enum constant or a type variable reads like a type to a regex and is none
                    continue;
                }
                List<String> plannedForSimple = plannedBySimpleName.get(simple);
                if (declaredHere.contains(simple) || imported.containsKey(simple)
                        || contractSimpleNames.contains(simple) || tree.hasSimpleName(simple)
                        || JdkTypes.exists(simple)
                        // A bare simple name is trusted only when exactly one planned type answers
                        // to it — two tasks writing a same-named class in different packages is not
                        // this test's problem to guess at, so it stays subject to the checks above.
                        || (plannedForSimple != null && plannedForSimple.size() == 1)) {
                    continue;
                }
                add(unknowns, path, simple, List.of());
            }
        }
        return new Check(List.copyOf(unknowns), List.copyOf(vocabulary));
    }

    /**
     * The known type a generated type is made from, going by the one naming convention generated
     * code shares: the source type's name, then {@code _} or {@code $}, then the generator's own
     * suffix ({@code Qso_Rules}, {@code Qso_}, {@code Qso$Builder}). Null when the name does not
     * have that shape or its stem is not a type of this package that exists, is a contract or is
     * planned.
     */
    private static String generatedFrom(String simple, String pkg, ProjectTypes tree,
                                        Set<String> contractFullNames, Set<String> plannedFullNames) {
        int mark = -1;
        for (int i = 1; i < simple.length() && mark < 0; i++) {
            if (simple.charAt(i) == '_' || simple.charAt(i) == '$') {
                mark = i;
            }
        }
        if (mark < 0) {
            return null;
        }
        String stem = (pkg.isEmpty() ? "" : pkg + ".") + simple.substring(0, mark);
        return tree.declares(stem) || contractFullNames.contains(stem)
            || plannedFullNames.contains(stem) ? stem : null;
    }

    private static void add(List<Unknown> unknowns, String path, String typeName,
                            List<String> realTypes) {
        for (Unknown existing : unknowns) {
            // The same wrong name in a second file is kept when it can be corrected there too.
            if (existing.typeName().equals(typeName)
                    && (realTypes.size() != 1 || existing.path().equals(path))) {
                return;
            }
        }
        unknowns.add(new Unknown(path, typeName, realTypes));
    }

    /**
     * Every real type of this simple name: in the checkout, among the design's contracts, or in
     * a task's write set. What a name written in the wrong package can have meant.
     */
    private static List<String> realTypesNamed(String simple, ProjectTypes tree,
            Set<String> contractFullNames, Map<String, List<String>> plannedBySimpleName) {
        Set<String> real = new java.util.TreeSet<>();
        String suffix = "." + simple;
        for (String fullName : tree.fullNames()) {
            if (fullName.endsWith(suffix)) {
                real.add(fullName);
            }
        }
        for (String fullName : contractFullNames) {
            if (fullName.endsWith(suffix)) {
                real.add(fullName);
            }
        }
        real.addAll(plannedBySimpleName.getOrDefault(simple, List.of()));
        return List.copyOf(real);
    }

    /**
     * Rewrites, in the test files, every name {@link Unknown#onlyRealType} can settle: the wrong
     * qualified name becomes the real one wherever it is written (the import, and any use spelled
     * out in full). No model call. A name with no real type of its simple name, or with several,
     * is left for the author.
     *
     * @return one line per correction made, for the log; empty when nothing was correctable
     */
    public static List<String> correctMisplacedNames(Path repoRoot, Check check) {
        List<String> corrected = new ArrayList<>();
        if (repoRoot == null || check == null) {
            return corrected;
        }
        for (Unknown unknown : check.unknowns()) {
            String real = unknown.onlyRealType();
            if (real == null || real.equals(unknown.typeName())) {
                continue;
            }
            Path file = repoRoot.resolve(unknown.path().replace('\\', '/'));
            try {
                String source = Files.readString(file);
                String rewritten = source.replaceAll(
                    "(?<![\\w.])" + java.util.regex.Pattern.quote(unknown.typeName()) + "(?!\\w)",
                    java.util.regex.Matcher.quoteReplacement(real));
                if (rewritten.equals(source)) {
                    continue;
                }
                Files.writeString(file, rewritten);
                corrected.add(unknown.typeName() + " -> " + real + " (in " + unknown.path() + ")");
            } catch (IOException | RuntimeException e) {
                log.warn("Could not correct {} in {}: {}", unknown.typeName(), unknown.path(),
                    e.toString());
            }
        }
        return corrected;
    }

    /**
     * What the test author is told when it names something nobody will build. Says the offending
     * names first, then the whole vocabulary it may use, because the second is the fix and the
     * first is only the complaint.
     */
    public static String reask(Check check) {
        StringBuilder sb = new StringBuilder();
        StringBuilder real = new StringBuilder();
        for (Unknown unknown : check.unknowns()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(unknown.typeName());
            if (!unknown.realTypes().isEmpty()) {
                real.append("\n  - ").append(unknown.typeName()).append(" does not exist; ")
                    .append(String.join(" and ", unknown.realTypes()))
                    .append(unknown.realTypes().size() == 1 ? " does" : " do")
                    .append(". Use that name.");
            }
        }
        StringBuilder message = new StringBuilder("Your test names ")
            .append(check.unknowns().size() == 1 ? "a type" : "types")
            .append(" that no task in this plan will ever create: ").append(sb)
            .append(check.unknowns().size() == 1
                ? " is not a contract and does not exist in this project."
                : " are not contracts and do not exist in this project.")
            .append(real.isEmpty() ? ""
                : "\n\nA type of the same name does exist somewhere else:" + real)
            .append("\n\nThe contracts are the ONLY new types anything in this run will deliver, "
                + "and they are:\n");
        for (String line : check.vocabulary()) {
            message.append("  - ").append(line).append('\n');
        }
        message.append("\nRewrite the test using only those types and the types this project "
            + "already has. Do not invent a type, and do not rename one: a test that names "
            + "something nobody delivers can never compile, however good the test is. Reply with "
            + "the same JSON object, with the corrected files.");
        return message.toString();
    }

    /** The brief a run parks with when the second attempt still names something undeliverable. */
    public static String brief(String taskTitle, Check check) {
        StringBuilder sb = new StringBuilder("The acceptance tests written for task '")
            .append(taskTitle).append("' name ")
            .append(check.unknowns().size() == 1 ? "a type" : "types")
            .append(" that nothing in this plan delivers, and the test author was asked once to "
                + "correct that and did not:\n");
        for (Unknown unknown : check.unknowns()) {
            sb.append("\n  - ").append(unknown.render());
        }
        sb.append("\n\nThe design's contracts are the only new types this run creates:\n");
        for (String line : check.vocabulary()) {
            sb.append("  - ").append(line).append('\n');
        }
        sb.append("\nA test that names a type nobody will build cannot compile, and \"it does not "
            + "compile\" is exactly what a freshly written test is SUPPOSED to look like — so "
            + "nothing further down the run can tell the two apart. It would be dispatched, "
            + "swarmed on and failed, wave after wave, and every candidate would be blamed for a "
            + "tree it did not break.\n\n"
            + "Decide which side is wrong: add the missing type to the design as a contract, so a "
            + "task is made to deliver it, or correct the test to use the contracts above. Then "
            + "resume the run.");
        return sb.toString();
    }

    private static String read(Path repoRoot, String relativePath) {
        try {
            return Files.readString(repoRoot.resolve(relativePath.replace('\\', '/')));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the authored test {}: {}", relativePath, e.toString());
            return "";
        }
    }

    /**
     * Whether a simple name is a JDK type. Asked of the running JVM rather than of a list, because
     * a list of "the java.* classes a test might use" is wrong the day somebody uses a new one —
     * and being wrong here means accusing a correct test.
     */
    private static final class JdkTypes {
        private static final List<String> PACKAGES = List.of("java.lang.", "java.util.",
            "java.util.function.", "java.util.stream.", "java.util.concurrent.", "java.time.",
            "java.io.", "java.nio.file.", "java.math.", "java.net.", "java.text.");

        static boolean exists(String simpleName) {
            for (String pkg : PACKAGES) {
                try {
                    Class.forName(pkg + simpleName, false, JdkTypes.class.getClassLoader());
                    return true;
                } catch (ClassNotFoundException | LinkageError notThere) {
                    // the next package, then
                }
            }
            return false;
        }
    }
}
