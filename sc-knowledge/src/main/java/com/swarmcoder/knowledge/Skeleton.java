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
package com.swarmcoder.knowledge;

import com.swarmcoder.domain.ApiContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The task's files, generated from its contracts, compiling, with every body still to write.
 *
 * <p><b>What it is for.</b> The measured failure is that the first write never happens: given an
 * unfamiliar framework the model reads, then investigates, then reads again, and the blank page
 * stays blank for three hours. A skeleton turns the first write into an EDIT — the package is
 * decided, the imports are the ones the codebase's own example uses, the annotations are the ones
 * that make the framework see the type, and every method is there with a body that says
 * {@code TODO}. There is no longer a question of where to start.
 *
 * <p><b>Everything in it is derived.</b> The names and members come from the contracts; the
 * annotations, the imports behind them and the bean convention come from the worked example
 * {@link WorkedExamples} chose. No rule here names a framework, and the same code run on a
 * codebase whose services are annotated {@code @Service} writes {@code @Service}.
 *
 * <p><b>It also fixes the build.</b> A skeleton that uses the example's annotations in a module
 * whose {@code pom.xml} does not declare the artifact they come from does not compile, and a tree
 * that does not compile is worse than a blank one. So the missing dependencies go in as well.
 */
public final class Skeleton {

    private static final Logger log = LoggerFactory.getLogger(Skeleton.class);

    /** Types every Java file can name without an import. */
    private static final Set<String> BUILT_IN = Set.of(
        "void", "int", "long", "short", "byte", "double", "float", "boolean", "char",
        "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character",
        "String", "CharSequence", "Object", "Number", "Comparable", "Iterable", "Runnable",
        "Exception", "RuntimeException", "Throwable", "Class", "Enum", "Record");

    /** Types worth importing from {@code java.util} when a contract names one. */
    private static final Map<String, String> JAVA_UTIL = Map.of(
        "List", "java.util.List", "ArrayList", "java.util.ArrayList",
        "Set", "java.util.Set", "Map", "java.util.Map",
        "Optional", "java.util.Optional", "Collection", "java.util.Collection",
        "LinkedHashMap", "java.util.LinkedHashMap", "LinkedHashSet", "java.util.LinkedHashSet");

    /**
     * @param files        the source files written, in the order they were written
     * @param buildChanges one line per build file changed, saying what was added
     * @param unresolved   type names a contract used that nothing could be found for; the skeleton
     *                     names them unqualified and may not compile until they exist
     */
    public record Result(List<Path> files, List<String> buildChanges, List<String> unresolved,
                         String note) {}

    private Skeleton() {}

    /**
     * Writes one file per contract into {@code tree}, and adds the dependencies the example's
     * modules declare and the target's do not.
     *
     * <p>A contract whose type already exists in the tree is left completely alone: this creates a
     * starting point, it never overwrites work.
     */
    public static Result write(Path tree, List<ApiContract> contracts,
                               WorkedExamples.Selection selection) {
        List<Path> written = new ArrayList<>();
        List<String> buildChanges = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        if (tree == null || contracts == null || contracts.isEmpty()) {
            return new Result(List.of(), List.of(), List.of(), "");
        }
        List<Path> targetModules = TaskBrief.mavenModulesOf(tree);
        Map<String, WorkedExamples.Match> byType = new LinkedHashMap<>();
        for (WorkedExamples.Match match : selection == null ? List.<WorkedExamples.Match>of()
                : selection.matches()) {
            byType.put(match.contract().typeName(), match);
        }
        ProjectTypes existing = ProjectTypes.of(tree);

        for (ApiContract contract : contracts) {
            if (contract == null || !contract.namesAType()
                || existing.declares(contract.typeName().strip())) {
                continue;
            }
            WorkedExamples.Match match = byType.get(contract.typeName());
            boolean test = isTest(contract);
            Path module = homeFor(contract, match, targetModules, tree);
            Path file = module.resolve("src").resolve(test ? "test" : "main").resolve("java")
                .resolve(contract.packageName().replace('.', '/'))
                .resolve(contract.simpleTypeName() + ".java");
            String source = source(contract, match, contracts, test, unresolved,
                selection == null ? List.of() : selection.matches());
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, source, StandardCharsets.UTF_8);
                written.add(file);
            } catch (Exception e) {                                        // noqa
                log.warn("could not write the skeleton for {}: {}", contract.typeName(),
                    e.getMessage());
            }
        }
        buildChanges.addAll(addMissingDependencies(tree, selection, targetModules));
        return new Result(List.copyOf(written), List.copyOf(buildChanges), List.copyOf(unresolved),
            note(tree, written, buildChanges));
    }

    /**
     * The module a new type belongs in.
     *
     * <p>Three answers, in order, and the third one matters as much as the first two. A type whose
     * package already exists goes where its package is. A type matched to an example goes where the
     * example's counterpart module is. A type with neither — a contract for which nothing in the
     * reference material resembled anything, or a test in a package no module has yet — goes into
     * the module that depends on the most of its siblings, which is the only module in a reactor
     * from which every other module's types can be named. Without that third answer both of them
     * landed beside the aggregator pom, which compiles nothing: the files exist, no build ever
     * sees them, and the acceptance stage reports zero tests while exiting zero.
     */
    static Path homeFor(ApiContract contract, WorkedExamples.Match match, List<Path> modules,
                        Path tree) {
        Path exact = holderOf(contract.packageName(), modules);
        if (exact != null) {
            return exact;
        }
        Path counterpart = match == null ? null
            : TaskBrief.counterpartModule(match.example().module(), modules);
        if (counterpart != null) {
            return counterpart;
        }
        // Only now the shortened package, and only as a hint. Every module of a three-module
        // application holds `com.x.demo.something`, so a walk that stops at the first module
        // holding the shortened package answers whichever module the file system listed first.
        String pkg = contract.packageName();
        while (!pkg.isEmpty()) {
            int dot = pkg.lastIndexOf('.');
            pkg = dot < 0 ? "" : pkg.substring(0, dot);
            Path holder = holderOf(pkg, modules);
            if (holder != null) {
                return holder;
            }
        }
        return leafModule(modules, tree);
    }

    /** The single module whose sources already hold this package, or null when none or several. */
    private static Path holderOf(String packageName, List<Path> modules) {
        if (packageName.isEmpty()) {
            return null;
        }
        String tail = packageName.replace('.', '/');
        Path found = null;
        for (Path module : modules) {
            if (Files.isDirectory(module.resolve("src/main/java").resolve(tail))
                || Files.isDirectory(module.resolve("src/test/java").resolve(tail))) {
                if (found != null) {
                    return null;
                }
                found = module;
            }
        }
        return found;
    }

    /** The module that declares the most of its own siblings as dependencies. */
    static Path leafModule(List<Path> modules, Path tree) {
        Path best = null;
        int most = -1;
        for (Path module : modules) {
            int siblings = 0;
            for (String dependency : TaskBrief.dependenciesOf(module.resolve("pom.xml"))) {
                String artifact = dependency.substring(dependency.indexOf(':') + 1);
                for (Path other : modules) {
                    if (!other.equals(module)
                        && other.getFileName().toString().equals(artifact)) {
                        siblings++;
                    }
                }
            }
            if (siblings > most) {
                most = siblings;
                best = module;
            }
        }
        return best == null ? tree : best;
    }

    /** The last segment of a package: what part the type plays, in its own codebase's words. */
    private static String roleOf(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "";
        }
        int dot = packageName.lastIndexOf('.');
        return dot < 0 ? packageName : packageName.substring(dot + 1);
    }

    private static boolean isTest(ApiContract contract) {
        String simple = contract.simpleTypeName();
        return simple.endsWith("Test") || simple.endsWith("Tests") || simple.startsWith("Test");
    }

    // -------------------------------------------------------------------------------------
    // One file
    // -------------------------------------------------------------------------------------

    static String source(ApiContract contract, WorkedExamples.Match match,
                         List<ApiContract> contracts, boolean test, List<String> unresolved) {
        return source(contract, match, contracts, test, unresolved, List.of());
    }

    static String source(ApiContract contract, WorkedExamples.Match match,
                         List<ApiContract> contracts, boolean test, List<String> unresolved,
                         List<WorkedExamples.Match> matches) {
        WorkedExamples.Shape example = match == null ? null : match.example();
        WorkedExamples.Wanted wanted = WorkedExamples.Wanted.of(contract);
        Set<String> imports = new LinkedHashSet<>();
        StringBuilder body = new StringBuilder();

        boolean beans = example != null && declaresGetters(example);
        // An interface only when the example is one AND it plays the same part in its own
        // codebase as this contract does in ours. Measured: a contract for
        // `...bookshelf.server.BookServiceImpl` — all methods, no fields — matched
        // `...example.api.ProductService`, and the skeleton came back as
        // `public interface BookServiceImpl`, which has no bodies to fill in and is not what the
        // task must deliver. The example's FORM is only worth copying when the example is the
        // same kind of thing; the role its package names is how that is known, and it is the same
        // signal WorkedExamples ranks with.
        boolean sameRole = example != null
            && example.role().equalsIgnoreCase(roleOf(contract.packageName()));
        String form = wanted.allConstants() ? "enum"
            : example != null && example.isInterface() && wanted.allMethods() && sameRole
                ? "interface" : "class";

        // The annotations that make the framework see this type — the example's own, with the
        // imports they come from. This is the piece a model cannot invent and cannot find by
        // reading prose: which marker goes on which kind of type in this codebase.
        List<String> annotations = new ArrayList<>();
        if (example != null && !test) {
            for (String annotation : example.annotations()) {
                String owner = resolve(annotation, example, contracts, contract);
                if (owner != null) {
                    annotations.add("@" + annotation);
                    if (!owner.isEmpty()) {
                        imports.add(owner);
                    }
                }
            }
        }
        if (test) {
            imports.add("org.junit.jupiter.api.Test");
        }

        // The example implements an interface; the contract matched to THAT interface is the one
        // this type implements. Derived, so a codebase whose services extend an abstract base
        // class gets `extends` for the same reason.
        List<String> implement = new ArrayList<>();
        if (example != null && !test) {
            for (String supertype : example.supertypes()) {
                ApiContract counterpart = contractMatchedTo(supertype, matches, contracts);
                if (counterpart != null && !counterpart.typeName().equals(contract.typeName())) {
                    implement.add(counterpart.simpleTypeName());
                    String owner = counterpart.packageName().equals(contract.packageName()) ? ""
                        : counterpart.typeName().strip();
                    if (!owner.isEmpty()) {
                        imports.add(owner);
                    }
                }
            }
        }

        // The example's own framework wiring, verbatim: the injected node, the publisher, the
        // context. A contract never mentions these and no amount of prose makes them guessable.
        List<String> wiring = new ArrayList<>();
        if (example != null && !test && !"interface".equals(form)) {
            for (String field : example.frameworkFields()) {
                wiring.add(field);
                for (String simple : simpleNamesIn(field.replace('@', ' ')
                        .replaceAll("[\\[\\]<>,;=]", " "))) {
                    String owner = resolve(simple, example, contracts, contract);
                    if (owner != null && !owner.isEmpty()) {
                        imports.add(owner);
                    }
                }
            }
        }

        for (JavaSourceFacts.Declared member : wanted.members()) {
            if (wanted.allConstants()) {
                continue;
            }
            need(member.type(), imports, example, contracts, contract, unresolved);
            for (String parameter : member.paramTypes()) {
                need(parameter, imports, example, contracts, contract, unresolved);
            }
        }

        if (wanted.allConstants()) {
            List<String> names = new ArrayList<>();
            for (JavaSourceFacts.Declared member : wanted.members()) {
                names.add(member.name());
            }
            body.append("    ").append(String.join(", ", names)).append(";\n");
        } else {
            for (JavaSourceFacts.Declared member : wanted.members()) {
                if (member.method()) {
                    body.append(method(member, form.equals("interface"), test));
                } else {
                    body.append("    private ").append(member.type()).append(' ')
                        .append(member.name()).append(";\n");
                }
            }
            if (beans) {
                for (JavaSourceFacts.Declared member : wanted.members()) {
                    if (!member.method()) {
                        body.append(accessors(member));
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!contract.packageName().isEmpty()) {
            sb.append("package ").append(contract.packageName()).append(";\n\n");
        }
        List<String> sorted = new ArrayList<>(imports);
        sorted.sort(String::compareTo);
        for (String imported : sorted) {
            sb.append("import ").append(imported).append(";\n");
        }
        if (!sorted.isEmpty()) {
            sb.append('\n');
        }
        sb.append("/**\n * TODO: written from this task's contract, ")
          .append(example == null ? "with no example to follow.\n"
              : "shaped after `" + example.relative() + "`.\n")
          .append(" * Every body below is a TODO. Fill them in; do not change the names.\n */\n");
        for (String annotation : annotations) {
            sb.append(annotation).append('\n');
        }
        sb.append("public ").append(form).append(' ').append(contract.simpleTypeName());
        if (!implement.isEmpty()) {
            sb.append("interface".equals(form) ? " extends " : " implements ")
              .append(String.join(", ", implement));
        }
        sb.append(" {\n\n");
        for (String field : wiring) {
            sb.append("    ").append(field).append('\n');
        }
        if (!wiring.isEmpty()) {
            sb.append('\n');
        }
        sb.append(body).append("}\n");
        return sb.toString();
    }

    /** The contract whose example file is the type this example names as its supertype. */
    static ApiContract contractMatchedTo(String exampleSupertype,
                                         List<WorkedExamples.Match> matches,
                                         List<ApiContract> contracts) {
        for (WorkedExamples.Match match : matches == null ? List.<WorkedExamples.Match>of()
                : matches) {
            if (match.example().simpleName().equals(exampleSupertype)) {
                return match.contract();
            }
        }
        return null;
    }

    private static String method(JavaSourceFacts.Declared member, boolean isInterface,
                                 boolean test) {
        StringBuilder sb = new StringBuilder();
        StringBuilder params = new StringBuilder();
        int n = 1;
        for (String parameter : member.paramTypes()) {
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append(parameter).append(" arg").append(n++);
        }
        String returns = member.type().isBlank() ? "void" : member.type();
        if (test) {
            sb.append("    @Test\n");
        }
        sb.append("    ").append(isInterface ? "" : "public ").append(returns).append(' ')
          .append(member.name()).append('(').append(params).append(')');
        if (isInterface) {
            return sb.append(";\n\n").toString();
        }
        return sb.append(" {\n        throw new UnsupportedOperationException(\"TODO: ")
            .append(member.name()).append("\");\n    }\n\n").toString();
    }

    private static String accessors(JavaSourceFacts.Declared member) {
        String capital = Character.toUpperCase(member.name().charAt(0)) + member.name().substring(1);
        String prefix = "boolean".equals(member.type()) ? "is" : "get";
        return "    public " + member.type() + ' ' + prefix + capital + "() {\n"
            + "        return " + member.name() + ";\n    }\n\n"
            + "    public void set" + capital + '(' + member.type() + ' ' + member.name()
            + ") {\n        this." + member.name() + " = " + member.name() + ";\n    }\n\n";
    }

    private static boolean declaresGetters(WorkedExamples.Shape example) {
        for (JavaSourceFacts.Declared member : example.members()) {
            if (member.method() && member.name().startsWith("get")
                && member.paramTypes().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------
    // Resolving a name to an import
    // -------------------------------------------------------------------------------------

    private static void need(String type, Set<String> imports, WorkedExamples.Shape example,
                             List<ApiContract> contracts, ApiContract self,
                             List<String> unresolved) {
        for (String simple : simpleNamesIn(type)) {
            String owner = resolve(simple, example, contracts, self);
            if (owner == null) {
                if (!unresolved.contains(simple)) {
                    unresolved.add(simple);
                }
            } else if (!owner.isEmpty()) {
                imports.add(owner);
            }
        }
    }

    /**
     * @return the fully-qualified name to import, "" when none is needed (built in, or the same
     *         package), or null when nothing could be found — which is a fact worth reporting, not
     *         a reason to guess.
     */
    static String resolve(String simple, WorkedExamples.Shape example, List<ApiContract> contracts,
                          ApiContract self) {
        if (simple.isEmpty() || BUILT_IN.contains(simple)) {
            return "";
        }
        for (ApiContract contract : contracts == null ? List.<ApiContract>of() : contracts) {
            if (contract != null && contract.namesAType()
                && contract.simpleTypeName().equals(simple)) {
                return contract.packageName().equals(self.packageName()) ? ""
                    : contract.typeName().strip();
            }
        }
        if (example != null) {
            for (String imported : example.imports()) {
                if (JavaSourceFacts.importedSimpleName(imported).equals(simple)) {
                    return imported.strip();
                }
            }
        }
        String util = JAVA_UTIL.get(simple);
        return util != null ? util : null;
    }

    /** Every simple type name inside a declared type: {@code List<Book>} yields List and Book. */
    static List<String> simpleNamesIn(String type) {
        List<String> names = new ArrayList<>();
        if (type == null) {
            return names;
        }
        for (String token : type.split("[^A-Za-z0-9_.$]+")) {
            if (token.isEmpty()) {
                continue;
            }
            String simple = token.contains(".")
                ? token.substring(token.lastIndexOf('.') + 1) : token;
            if (!simple.isEmpty() && !names.contains(simple)) {
                names.add(simple);
            }
        }
        return names;
    }

    // -------------------------------------------------------------------------------------
    // The build
    // -------------------------------------------------------------------------------------

    /**
     * Adds every dependency the example's module declares that the counterpart does not, with no
     * {@code <version>} — the parent manages it, which is how the example's own module declares it.
     */
    static List<String> addMissingDependencies(Path tree, WorkedExamples.Selection selection,
                                               List<Path> targetModules) {
        List<String> changes = new ArrayList<>();
        if (selection == null || selection.isEmpty()) {
            return changes;
        }
        for (Path module : selection.modules()) {
            Path counterpart = TaskBrief.counterpartModule(module, targetModules);
            if (counterpart == null) {
                continue;
            }
            Path pom = counterpart.resolve("pom.xml");
            Set<String> have = TaskBrief.dependenciesOf(pom);
            List<String> missing = new ArrayList<>();
            for (String dependency : TaskBrief.dependenciesOf(module.resolve("pom.xml"))) {
                String artifact = dependency.substring(dependency.indexOf(':') + 1);
                Path sibling = module.getParent() == null ? null
                    : module.getParent().resolve(artifact);
                boolean isSibling = sibling != null && Files.isDirectory(sibling);
                // Only what the target's own dependency management already knows how to version.
                boolean managed = TaskBrief.managed(tree, dependency);
                if (!have.contains(dependency) && !isSibling && managed) {
                    missing.add(dependency);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            try {
                String text = Files.readString(pom, StandardCharsets.UTF_8);
                int at = text.indexOf("</dependencies>");
                if (at < 0) {
                    continue;
                }
                StringBuilder add = new StringBuilder();
                for (String dependency : missing) {
                    add.append("    <dependency>\n      <groupId>")
                       .append(dependency, 0, dependency.indexOf(':'))
                       .append("</groupId>\n      <artifactId>")
                       .append(dependency.substring(dependency.indexOf(':') + 1))
                       .append("</artifactId>\n    </dependency>\n");
                }
                Files.writeString(pom, text.substring(0, at) + add + text.substring(at),
                    StandardCharsets.UTF_8);
                changes.add(tree.relativize(pom).toString().replace('\\', '/') + ": added "
                    + String.join(", ", missing));
            } catch (Exception e) {                                        // noqa
                log.warn("could not add dependencies to {}: {}", pom, e.getMessage());
            }
        }
        return changes;
    }

    private static String note(Path tree, List<Path> written, List<String> buildChanges) {
        if (written.isEmpty() && buildChanges.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
            "\n### These files already exist in your checkout, waiting to be filled in\n\n"
            + "They were generated from this task's contract and shaped after the working "
            + "example this brief also carries. "
            + "**They compile, right now, against the exact versions this project builds "
            + "against** — the build was run on this tree after they were written and it "
            + "passed. The annotations, the imports and the types in them are therefore the "
            + "right ones for this project, and there is nothing left to verify before you "
            + "start. Every method body throws `UnsupportedOperationException`; your job is "
            + "to replace those bodies. **Do not rename anything in them** — a later task's tests "
            + "are written against exactly these names.\n\n");
        for (Path file : written) {
            sb.append("- `").append(tree.relativize(file).toString().replace('\\', '/'))
              .append("`\n");
        }
        for (String change : buildChanges) {
            sb.append("- `").append(change).append("`\n");
        }
        return sb.toString();
    }
}
