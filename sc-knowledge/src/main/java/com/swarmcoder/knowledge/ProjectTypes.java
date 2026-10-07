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
import java.util.stream.Stream;

/**
 * Every Java type a checkout actually holds: its fully-qualified name, its simple name, the package
 * it lives in, and — on demand — the members it declares.
 *
 * <p>This is the other half of the run's type vocabulary. The design's contracts say what is going
 * to exist; this says what already does. Together they are the complete set of names anything in
 * the run is allowed to write down, and a name in neither is a name nobody will ever deliver.
 *
 * <p>Read from source text with no compiler and no classpath, because the trees it is asked about
 * are exactly the trees that do not build yet: a worktree cut before the code was written, or a
 * candidate's workspace mid-verification. A file that cannot be read or parsed contributes nothing
 * and is not an error — every caller treats absence as "we could not tell".
 */
public final class ProjectTypes {

    private static final Logger log = LoggerFactory.getLogger(ProjectTypes.class);

    /** Directories that hold build output or somebody else's code, never this project's sources. */
    private static final Set<String> SKIP = Set.of(
        ".git", ".gradle", ".idea", "node_modules", ".m2");

    /**
     * Build-output folders. Skipped only OUTSIDE a source tree: under {@code src/} the same words
     * are package names ({@code com.acme.build}, {@code com.acme.target}), and a type in such a
     * package that this index did not read was reported as "not delivered" by every check built
     * on it.
     */
    private static final Set<String> BUILD_OUTPUT = Set.of("target", "build", "out", "bin");

    /** True names of nested types ({@code com.acme.Logbook.Entry}) and the file each is in. */
    private final Map<String, Path> nestedByTrueName = new LinkedHashMap<>();

    private final Map<String, Path> byFullName = new LinkedHashMap<>();
    private final Set<String> simpleNames = new LinkedHashSet<>();
    private final Set<String> packages = new LinkedHashSet<>();

    private ProjectTypes() {}

    /** An index of every {@code .java} file under {@code root}; empty when there is no such tree. */
    public static ProjectTypes of(Path root) {
        ProjectTypes types = new ProjectTypes();
        if (root == null || !Files.isDirectory(root)) {
            return types;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> isSource(root.relativize(p)))
                .forEach(files::add);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the type index under {}: {}", root, e.toString());
            return types;
        }
        for (Path file : files) {
            String source = read(file);
            if (source.isEmpty()) {
                continue;
            }
            JavaSourceFacts facts = JavaSourceFacts.of(source);
            String pkg = facts.packageName();
            if (!pkg.isEmpty()) {
                types.packages.add(pkg);
            }
            for (String simple : facts.declaredTypes()) {
                types.simpleNames.add(simple);
                types.byFullName.put(pkg.isEmpty() ? simple : pkg + "." + simple, file);
            }
            for (String path : facts.declaredTypePaths()) {
                if (path.indexOf('.') > 0) {
                    types.nestedByTrueName.put(pkg.isEmpty() ? path : pkg + "." + path, file);
                }
            }
        }
        return types;
    }

    /** Whether a path below the root is source this index reads, not build output or tooling. */
    private static boolean isSource(Path relative) {
        boolean insideSourceTree = false;
        int last = relative.getNameCount() - 1;
        for (int i = 0; i < last; i++) {
            String segment = relative.getName(i).toString();
            if (SKIP.contains(segment)) {
                return false;
            }
            if (!insideSourceTree && BUILD_OUTPUT.contains(segment)) {
                return false;
            }
            if (segment.equals("src")) {
                insideSourceTree = true;
            }
        }
        return true;
    }

    private static String normalized(String fullName) {
        return fullName == null ? null : fullName.replace('$', '.').strip();
    }

    /** True when a type with this fully-qualified name is declared somewhere in the tree. */
    public boolean declares(String fullName) {
        return fileOf(fullName) != null;
    }

    /**
     * True when {@code fullName} is declared in the tree as a plain interface — never a class,
     * enum, record or annotation type. False both when the tree declares it as something else and
     * when the tree does not declare it at all; a caller that needs to tell those two apart checks
     * {@link #declares} first (harness run 42, 2026-09-26 — see
     * {@code com.swarmcoder.workflow.SelfImplementedContract}, which uses this to keep its lambda
     * check off contract types a lambda could never actually implement).
     */
    public boolean isInterface(String fullName) {
        Path file = fileOf(fullName);
        if (file == null) {
            return false;
        }
        String source = read(file);
        if (source.isEmpty()) {
            return false;
        }
        return JavaSourceFacts.of(source).isInterface(simpleOf(fullName));
    }

    /** True when some type in the tree has this simple name, whatever its package. */
    public boolean hasSimpleName(String simpleName) {
        return simpleName != null && simpleNames.contains(simpleName.strip());
    }

    /**
     * True when {@code packageName} is one this project writes code into. A reference into a
     * package the project owns can be checked; a reference into anything else is a library and is
     * none of this class's business.
     */
    public boolean isProjectPackage(String packageName) {
        return packageName != null && packages.contains(packageName.strip());
    }

    /**
     * Every package the tree declares a type in. Read by {@link LibraryTypes} to tell which
     * namespaces a reference checkout is the source of (harness runs 44/45, 2026-09-27).
     */
    public Set<String> packages() {
        return Set.copyOf(packages);
    }

    /** Every fully-qualified name in the tree, in the order the files were read. */
    public Set<String> fullNames() {
        return Set.copyOf(byFullName.keySet());
    }

    /** The file declaring {@code fullName}, or null when the tree does not declare it. */
    public Path fileOf(String fullName) {
        if (fullName == null) {
            return null;
        }
        String name = normalized(fullName);
        Path file = byFullName.get(name);
        return file != null ? file : nestedByTrueName.get(name);
    }

    /**
     * What kind of type this is ({@code class}, {@code interface}, {@code enum}, {@code record},
     * {@code annotation}), or "" when this tree does not declare it.
     */
    public String kindOf(String fullName) {
        JavaSourceFacts facts = factsOf(fullName);
        return facts == null ? "" : facts.kindOf(simpleOf(fullName));
    }

    /** Whether the type's body was read, so that a member it does not list is one it lacks. */
    public boolean bodyWasRead(String fullName) {
        JavaSourceFacts facts = factsOf(fullName);
        return facts != null && facts.bodyWasRead(simpleOf(fullName));
    }

    /** Whether the type writes out a constructor of its own. */
    public boolean declaresConstructor(String fullName) {
        JavaSourceFacts facts = factsOf(fullName);
        return facts != null && facts.declaresConstructor(simpleOf(fullName));
    }

    /**
     * One type a declared type extends or implements.
     *
     * @param written  the name as the header has it
     * @param fullName its fully-qualified name where that could be worked out from the file's
     *                 imports, its package or the JDK; null where it could not
     * @param ours     whether this tree declares it
     */
    public record Supertype(String written, String fullName, boolean ours) {}

    /**
     * The types a declared type extends or implements, each resolved as far as this tree, the
     * file's imports and {@code java.lang} allow. A supertype that could not be placed keeps a
     * null full name: its members are unknown here, which is not the same as it having none.
     */
    public List<Supertype> supertypesOf(String fullName) {
        JavaSourceFacts facts = factsOf(fullName);
        if (facts == null) {
            return List.of();
        }
        String name = normalized(fullName);
        String pkg = facts.packageName();
        List<Supertype> found = new ArrayList<>();
        for (String written : facts.supertypesOf(simpleOf(name))) {
            found.add(resolve(written, pkg, name, facts));
        }
        return List.copyOf(found);
    }

    private Supertype resolve(String written, String pkg, String from, JavaSourceFacts facts) {
        String first = written.contains(".") ? written.substring(0, written.indexOf('.')) : written;
        String rest = written.substring(first.length());
        if (!first.isEmpty() && Character.isLowerCase(first.charAt(0))) {
            return new Supertype(written, written, declares(written)); // already qualified
        }
        for (String statement : facts.imports()) {
            String imported = statement.strip();
            if (!imported.startsWith("static ") && imported.endsWith("." + first)) {
                String full = imported + rest;
                return new Supertype(written, full, declares(full));
            }
        }
        // a type nested in the same outer type, then one in the same package
        String outer = from;
        while (outer.length() > pkg.length() && outer.contains(".")) {
            String candidate = outer + "." + written;
            if (nestedByTrueName.containsKey(candidate)) {
                return new Supertype(written, candidate, true);
            }
            outer = outer.substring(0, outer.lastIndexOf('.'));
        }
        String samePackage = pkg.isEmpty() ? written : pkg + "." + written;
        if (byFullName.containsKey(samePackage) || nestedByTrueName.containsKey(samePackage)) {
            return new Supertype(written, samePackage, true);
        }
        for (String statement : facts.imports()) {
            String imported = statement.strip();
            if (imported.startsWith("static ") || !imported.endsWith(".*")) {
                continue;
            }
            String full = imported.substring(0, imported.length() - 1) + written;
            if (declares(full) || LibraryTypes.jdkDeclares(full)) {
                return new Supertype(written, full, declares(full));
            }
        }
        String javaLang = "java.lang." + written;
        if (LibraryTypes.jdkDeclares(javaLang)) {
            return new Supertype(written, javaLang, false);
        }
        return new Supertype(written, null, false);
    }

    private JavaSourceFacts factsOf(String fullName) {
        Path file = fileOf(fullName);
        if (file == null) {
            return null;
        }
        String source = read(file);
        return source.isEmpty() ? null : JavaSourceFacts.of(source);
    }

    private static String simpleOf(String fullName) {
        String name = normalized(fullName);
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** The annotations on the declaration of the type {@code fullName}, as written. */
    public List<String> typeAnnotationsOf(String fullName) {
        Path file = fileOf(fullName);
        if (file == null) {
            return List.of();
        }
        String source = read(file);
        return source.isEmpty() ? List.of()
            : JavaSourceFacts.of(source).typeAnnotationsOf(simpleOf(fullName));
    }

    /**
     * The members of the type named {@code fullName}, or an empty list when the tree does not
     * declare it or the file can no longer be read.
     */
    public List<JavaSourceFacts.Declared> membersOf(String fullName) {
        Path file = fileOf(fullName);
        if (file == null) {
            return List.of();
        }
        String source = read(file);
        if (source.isEmpty()) {
            return List.of();
        }
        return JavaSourceFacts.of(source).membersOf(simpleOf(fullName));
    }

    /**
     * The public surface of the type named {@code fullName} — see
     * {@link JavaSourceFacts#exposedMembers}. A nested type may be named the way a compiler prints
     * it ({@code org.jsoup.nodes.Document.OutputSettings}) or with {@code $}; both are found.
     * Empty when the tree does not declare it.
     */
    public List<JavaSourceFacts.Exposed> exposedMembers(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return List.of();
        }
        String name = fullName.replace('$', '.').strip();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        Path file = fileOf(name);
        if (file == null) {
            // A nested type is read from the file of the top-level type that holds it, when the
            // name says which that is: two types in one package may each nest a `Builder`, and the
            // by-simple-name index below keeps only one of them (live run 63, 2026-10-02).
            StringBuilder top = new StringBuilder();
            for (String part : name.split("\\.")) {
                top.append(top.isEmpty() ? "" : ".").append(part);
                if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
                    break;
                }
            }
            Path outer = top.length() < name.length() ? fileOf(top.toString()) : null;
            if (outer != null) {
                String outerSource = read(outer);
                List<JavaSourceFacts.Exposed> nested = outerSource.isEmpty() ? List.of()
                    : JavaSourceFacts.of(outerSource).exposedMembers(simple);
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
            // Nested types are indexed as <package>.<Simple>: the package is the segments before
            // the first one that starts with a capital letter.
            StringBuilder pkg = new StringBuilder();
            for (String part : name.split("\\.")) {
                if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
                    break;
                }
                pkg.append(pkg.isEmpty() ? "" : ".").append(part);
            }
            file = fileOf(pkg.isEmpty() ? simple : pkg + "." + simple);
        }
        if (file == null) {
            return List.of();
        }
        String source = read(file);
        return source.isEmpty() ? List.of() : JavaSourceFacts.of(source).exposedMembers(simple);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read {}: {}", file, e.toString());
            return "";
        }
    }
}
