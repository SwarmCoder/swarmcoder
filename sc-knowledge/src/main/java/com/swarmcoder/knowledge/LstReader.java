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

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.tree.ParseError;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.tree.Xml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Builds the Lossless Semantic Tree for one root and reads the relations out of it.
 *
 * <p><b>Type attribution is the whole point and it is not free.</b> A parse with no classpath
 * resolves only what is declared in the batch: it will tell you that {@code BookServiceImpl}
 * implements {@code BookService} because both are source files, and it will NOT tell you that the
 * thing it is annotated with is {@code jakarta.enterprise.context.ApplicationScoped}, because that
 * lives in a jar. So every root is parsed with a classpath assembled from what the machine
 * actually has offline — each module's own {@code target/classes} and the jars of every artifact
 * the build's dependency management pins, resolved through {@link DeclarableArtifacts}, the same
 * resolver the pre-flight uses to decide whether a dependency can be declared at all.
 *
 * <p><b>Line numbers come out of the tree, not out of a text search.</b> OpenRewrite's LST carries
 * no line numbers; what it carries is every byte of whitespace, so printing it reproduces the file
 * exactly. Each node worth quoting is therefore tagged with a numbered search marker, the tree is
 * printed once, and the line each marker landed on is the line of the node. That is exact, where
 * searching the text for a name is a guess that goes wrong on the second method of the same name.
 *
 * <p><b>Nothing here throws at the caller.</b> A file that will not parse comes back from
 * OpenRewrite as a {@code ParseError} rather than an exception and is counted and skipped; a root
 * that will not parse at all leaves a reason on {@link SemanticFacts.RootFacts#degraded} and the
 * caller falls back to the text index. See {@link SemanticIndex}.
 */
final class LstReader {

    private static final Logger log = LoggerFactory.getLogger(LstReader.class);

    /** Directories that hold build output or history, never source worth indexing. */
    private static final Set<String> SKIP_DIRS =
        Set.of("target", "build", "out", "node_modules", ".git", ".idea", ".gradle", "bin");

    /** A root bigger than this is not indexed: a compile of it would dominate the run. */
    private static final int MAX_JAVA_FILES = 4_000;

    /** How far up a supertype chain {@code assignableTo} is followed. */
    private static final int MAX_SUPERTYPE_DEPTH = 8;

    /** How many members a type's published shape may list before it stops being a shape. */
    private static final int MAX_SHAPE_MEMBERS = 40;

    private LstReader() {}

    // ---------------------------------------------------------------------------------------

    /** Every Java file under a root, in a stable order so two builds agree. */
    static List<Path> javaFilesUnder(Path root) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> !skipped(root, p))
                .forEach(files::add);
        } catch (Exception e) {                                            // noqa
            log.debug("could not walk {}: {}", root, e.getMessage());
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    /** Every build file under a root, in a stable order. */
    static List<Path> pomsUnder(Path root) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .filter(p -> !skipped(root, p))
                .forEach(files::add);
        } catch (Exception e) {                                            // noqa
            log.debug("could not walk {} for build files: {}", root, e.getMessage());
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static boolean skipped(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path segment : relative) {
            if (SKIP_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The classpath a type-attributed parse of this root needs, out of what is on the machine.
     *
     * <p>Two sources, both offline. Each module's {@code target/classes} — a checkout that has
     * been built once resolves its own siblings instantly and exactly. And the jar of every
     * artifact the build's dependency management pins, which is what puts a framework's own
     * annotations and interfaces within reach of the parser. Nothing is downloaded; an artifact
     * the local repository does not hold is simply not on the classpath, and the types it would
     * have carried resolve to nothing, which is what {@code degraded} is for.
     */
    static List<Path> classpathFor(Path root) {
        List<Path> classpath = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 4)) {
            walk.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().equals("classes")
                    && p.getParent() != null
                    && p.getParent().getFileName().toString().equals("target"))
                .sorted(Comparator.comparing(Path::toString))
                .forEach(classpath::add);
        } catch (Exception e) {                                            // noqa
            log.debug("could not look for compiled classes under {}: {}", root, e.getMessage());
        }
        try {
            List<String> modules = new ArrayList<>();
            for (Path pom : pomsUnder(root)) {
                Path dir = pom.getParent();
                modules.add(dir == null || dir.equals(root) ? ""
                    : root.relativize(dir).toString().replace(File.separatorChar, '/'));
            }
            Path repository = DeclarableArtifacts.defaultLocalRepository();
            DeclarableArtifacts.Catalog catalog =
                DeclarableArtifacts.scan(root, modules, repository);
            for (DeclarableArtifacts.Artifact artifact : catalog.artifacts()) {
                Path jar = DeclarableArtifacts.jarOf(repository, artifact);
                if (jar != null && !classpath.contains(jar)) {
                    classpath.add(jar);
                }
            }
        } catch (Exception e) {                                            // noqa
            log.debug("could not resolve a classpath for {}: {}", root, e.getMessage());
        }
        return classpath;
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Parses one root and reads every relation out of it.
     *
     * @param budgetMillis a wall clock the build may not exceed; when it does, what has been read
     *                     so far is kept and the root is marked degraded. A half index that says
     *                     it is half is worth more than a run that stalls behind a compile.
     */
    static SemanticFacts.RootFacts read(KnowledgeCurator.Root root, long budgetMillis) {
        return read(root, root.identity().fingerprint(), budgetMillis);
    }

    /**
     * The same for a tree that is not one of the curator's roots - a candidate's checkout, the
     * merged tree of a run. Nothing is cached and no version is worked out: the tree is read as
     * it is on disk now.
     */
    static SemanticFacts.RootFacts readTree(Path tree, long budgetMillis) {
        return read(new KnowledgeCurator.Root("tree", tree, "as on disk"), "", budgetMillis);
    }

    private static SemanticFacts.RootFacts read(KnowledgeCurator.Root root, String fingerprint,
                                                long budgetMillis) {
        SemanticFacts.RootFacts facts = new SemanticFacts.RootFacts();
        facts.label = root.label();
        facts.path = String.valueOf(root.path());
        facts.fingerprint = fingerprint;
        long started = System.currentTimeMillis();

        readPoms(root, facts);

        List<Path> sources = javaFilesUnder(root.path());
        facts.javaFiles = sources.size();
        if (sources.isEmpty()) {
            facts.buildMillis = System.currentTimeMillis() - started;
            return facts;
        }
        if (sources.size() > MAX_JAVA_FILES) {
            facts.degraded = "the root holds " + sources.size() + " Java files, more than the "
                + MAX_JAVA_FILES + " a single parse is allowed to take; no Java relations were "
                + "read";
            facts.buildMillis = System.currentTimeMillis() - started;
            return facts;
        }

        List<Path> classpath = classpathFor(root.path());
        log.info("building the semantic index of '{}': {} Java file(s), {} classpath entr(ies)",
            root.label(), sources.size(), classpath.size());

        ExecutionContext ctx = new InMemoryExecutionContext(
            t -> log.debug("openrewrite: {}", t.toString()));
        JavaParser parser;
        try {
            parser = JavaParser.fromJavaVersion()
                .classpath(classpath)
                .logCompilationWarningsAndErrors(false)
                .build();
        } catch (Throwable e) {                                            // noqa
            facts.degraded = "no OpenRewrite Java parser could be built on this JVM: "
                + e.getClass().getSimpleName() + ": " + e.getMessage();
            facts.buildMillis = System.currentTimeMillis() - started;
            return facts;
        }

        int failed = 0;
        try (Stream<SourceFile> parsed = parser.parse(sources, root.path(), ctx)) {
            for (SourceFile file : (Iterable<SourceFile>) parsed::iterator) {
                if (System.currentTimeMillis() - started > budgetMillis) {
                    facts.degraded = "the parse ran past its " + budgetMillis
                        + " ms budget after " + facts.parsedFiles + " of " + sources.size()
                        + " files; the rest of this root is not indexed";
                    break;
                }
                if (file instanceof ParseError) {
                    failed++;
                    continue;
                }
                if (!(file instanceof J.CompilationUnit unit)) {
                    continue;
                }
                try {
                    readOneFile(unit, facts);
                    facts.parsedFiles++;
                } catch (Throwable e) {                                    // noqa
                    failed++;
                    log.debug("could not read {}: {}", file.getSourcePath(), e.toString());
                }
            }
        } catch (Throwable e) {                                            // noqa
            facts.degraded = (facts.degraded.isEmpty() ? "" : facts.degraded + "; ")
                + "the parse failed after " + facts.parsedFiles + " file(s): "
                + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (failed > 0) {
            facts.degraded = (facts.degraded.isEmpty() ? "" : facts.degraded + "; ")
                + failed + " file(s) would not parse and were skipped";
        }
        facts.buildMillis = System.currentTimeMillis() - started;
        log.info("semantic index of '{}': {} type(s), {} use(s), {} call(s), {} declared "
                + "dependenc(ies) from {}/{} file(s) in {} ms{}",
            root.label(), facts.types.size(), facts.uses.size(), facts.calls.size(),
            facts.poms.size(), facts.parsedFiles, facts.javaFiles, facts.buildMillis,
            facts.degraded.isEmpty() ? "" : " (degraded: " + facts.degraded + ")");
        return facts;
    }

    // ---------------------------------------------------------------------------------------

    /** What one node is, kept beside its marker id until the print says which line it is on. */
    private record Pending(String kind, String a, String b, String c, String d) {}

    /**
     * One file: tag, collect, print once, and turn the marker lines into facts.
     *
     * <p>The order matters. Nothing is written into {@code facts} until the print has said where
     * each tagged node is, because a relation without a line is a relation a worker cannot check.
     */
    private static void readOneFile(J.CompilationUnit unit, SemanticFacts.RootFacts facts) {
        String file = unit.getSourcePath().toString().replace(File.separatorChar, '/');
        AtomicInteger ids = new AtomicInteger();
        Map<Integer, Pending> pending = new LinkedHashMap<>();

        J tagged = (J) new JavaIsoVisitor<Integer>() {

            @Override
            public J.Import visitImport(J.Import anImport, Integer p) {
                J.Import result = super.visitImport(anImport, p);
                String name = anImport.getTypeName();
                if (name != null && !name.isBlank() && !name.endsWith("*")) {
                    return SearchResult.found(result,
                        mark(ids, pending, new Pending("import", name, "", "", "")));
                }
                return result;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration declaration,
                                                            Integer p) {
                J.ClassDeclaration result = super.visitClassDeclaration(declaration, p);
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(declaration.getType());
                if (type == null) {
                    return result;
                }
                return SearchResult.found(result, mark(ids, pending,
                    new Pending("type", type.getFullyQualifiedName(),
                        kindOf(declaration), String.valueOf(isAbstract(declaration)), "")));
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, Integer p) {
                J.Annotation result = super.visitAnnotation(annotation, p);
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
                if (type == null) {
                    return result;
                }
                return SearchResult.found(result, mark(ids, pending,
                    new Pending("annotation", type.getFullyQualifiedName(), "", "", "")));
            }

            /**
             * A type NAMED in code: a class literal, a supertype, a field's, parameter's or
             * variable's type, the target of a static call. Run 85 (2026-10-04): the only use
             * of a framework's bean producer in its whole checkout was {@code Producer.class}
             * in a test of the producer's own package - no import, no {@code new} - so
             * find_usages answered "nothing", and the test author read files whole instead.
             */
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                J.Identifier result = super.visitIdentifier(identifier, p);
                if (identifier.getFieldType() != null) {
                    return result; // a variable, not a type's name
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(identifier.getType());
                if (type == null || getCursor().firstEnclosing(J.Import.class) != null
                        || getCursor().firstEnclosing(J.Package.class) != null) {
                    return result;
                }
                String fqn = type.getFullyQualifiedName();
                String simple = fqn.substring(Math.max(fqn.lastIndexOf('.'),
                    fqn.lastIndexOf('$')) + 1);
                if (!simple.equals(identifier.getSimpleName())) {
                    return result; // an expression of that type (this, super), not its name
                }
                return SearchResult.found(result, mark(ids, pending,
                    new Pending("names", fqn, "", "", "")));
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, Integer p) {
                J.NewClass result = super.visitNewClass(newClass, p);
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(newClass.getType());
                if (type == null) {
                    return result;
                }
                return SearchResult.found(result, mark(ids, pending,
                    new Pending("new", type.getFullyQualifiedName(), "", "", "")));
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                            Integer p) {
                J.MethodInvocation result = super.visitMethodInvocation(invocation, p);
                JavaType.Method method = invocation.getMethodType();
                if (method == null || method.getDeclaringType() == null) {
                    return result;
                }
                J.ClassDeclaration owner = getCursor()
                    .firstEnclosing(J.ClassDeclaration.class);
                J.MethodDeclaration in = getCursor()
                    .firstEnclosing(J.MethodDeclaration.class);
                JavaType.FullyQualified ownerType = owner == null ? null
                    : TypeUtils.asFullyQualified(owner.getType());
                return SearchResult.found(result, mark(ids, pending,
                    new Pending("call",
                        method.getDeclaringType().getFullyQualifiedName(), method.getName(),
                        ownerType == null ? "" : ownerType.getFullyQualifiedName(),
                        in == null ? "" : in.getSimpleName())));
            }
        }.visit(unit, 0);

        if (tagged == null) {
            return;
        }
        Map<Integer, Integer> lines = linesOf(tagged.print(new Cursor(null, tagged)));

        // The shape of every type this file declares, and what it is assignable to. Read off the
        // declaration rather than off the marker pass, because a shape is not a position.
        Map<String, J.ClassDeclaration> declarations = new LinkedHashMap<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration declaration,
                                                            Integer p) {
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(declaration.getType());
                if (type != null) {
                    declarations.put(type.getFullyQualifiedName(), declaration);
                }
                return super.visitClassDeclaration(declaration, p);
            }
        }.visit(unit, 0);

        Set<String> annotationsOfFile = new LinkedHashSet<>();
        List<SemanticFacts.UseFact> uses = new ArrayList<>();
        List<SemanticFacts.CallFact> calls = new ArrayList<>();
        Set<String> seenUse = new LinkedHashSet<>();
        Set<String> seenCall = new LinkedHashSet<>();
        /** Types named in code, with the first line each is named on. */
        Map<String, Integer> named = new LinkedHashMap<>();

        for (Map.Entry<Integer, Pending> entry : pending.entrySet()) {
            int line = lines.getOrDefault(entry.getKey(), 0);
            Pending what = entry.getValue();
            switch (what.kind()) {
                case "type" -> {
                    J.ClassDeclaration declaration = declarations.get(what.a());
                    JavaType.FullyQualified type = declaration == null ? null
                        : TypeUtils.asFullyQualified(declaration.getType());
                    List<String> annotations = new ArrayList<>();
                    if (declaration != null) {
                        for (J.Annotation annotation : declaration.getLeadingAnnotations()) {
                            JavaType.FullyQualified at =
                                TypeUtils.asFullyQualified(annotation.getType());
                            annotations.add(at == null ? annotation.getSimpleName()
                                : at.getFullyQualifiedName());
                        }
                    }
                    annotationsOfFile.addAll(annotations);
                    facts.types.add(new SemanticFacts.TypeFact(what.a(), file, line, what.b(),
                        Boolean.parseBoolean(what.c()), assignableTo(type), annotations,
                        shapeOf(type)));
                }
                case "annotation" -> {
                    annotationsOfFile.add(what.a());
                    if (seenUse.add("annotation:" + what.a())) {
                        uses.add(new SemanticFacts.UseFact(what.a(), file, line, "annotation"));
                    }
                }
                case "import", "new" -> {
                    if (seenUse.add(what.kind() + ":" + what.a())) {
                        uses.add(new SemanticFacts.UseFact(what.a(), file, line, what.kind()));
                    }
                }
                case "call" -> {
                    String key = what.a() + "#" + what.b() + "@" + what.d();
                    if (seenCall.add(key)) {
                        calls.add(new SemanticFacts.CallFact(what.a(), what.b(), file, line,
                            what.c(), what.d()));
                    }
                }
                case "names" -> named.merge(what.a(), line,
                    (first, next) -> first > 0 && first <= next ? first : next);
                default -> { }
            }
        }
        // Only where nothing else of this file says so already: an import, a `new` or an
        // annotation is the same fact with a better word, and a type's own file is not its user.
        for (Map.Entry<String, Integer> name : named.entrySet()) {
            String fqn = name.getKey();
            if (declarations.containsKey(fqn) || seenUse.contains("import:" + fqn)
                    || seenUse.contains("new:" + fqn) || seenUse.contains("annotation:" + fqn)) {
                continue;
            }
            uses.add(new SemanticFacts.UseFact(fqn, file, name.getValue(), "names"));
        }
        facts.uses.addAll(uses);
        facts.calls.addAll(calls);

        // The resolved types this file actually uses, which is the structural fingerprint the
        // nearest-example selector overlaps against a contract's demands. Erased of generics —
        // a file that uses List<Book> uses java.util.List, and a contract that asks for a list
        // must match it.
        Set<String> inUse = new LinkedHashSet<>();
        if (unit instanceof JavaSourceFile source) {
            for (JavaType type : source.getTypesInUse().getTypesInUse()) {
                JavaType.FullyQualified qualified = TypeUtils.asFullyQualified(type);
                if (qualified != null) {
                    inUse.add(qualified.getFullyQualifiedName());
                }
            }
        }
        inUse.addAll(annotationsOfFile);
        for (SemanticFacts.CallFact call : calls) {
            inUse.add(call.declaringType());
        }
        facts.typesInFile.put(file, List.copyOf(inUse));
    }

    private static String mark(AtomicInteger ids, Map<Integer, Pending> pending, Pending what) {
        int id = ids.incrementAndGet();
        pending.put(id, what);
        return "SC" + id;
    }

    /**
     * Which line each marker landed on, from the one print of the tagged tree.
     *
     * <p>A search marker prints as {@code /*~~(SC7)~~>*}{@code /} immediately before the node it
     * is on, so the line the marker is on is the line the node starts on.
     */
    private static Map<Integer, Integer> linesOf(String printed) {
        Map<Integer, Integer> lines = new LinkedHashMap<>();
        int line = 1;
        int at = 0;
        while (at < printed.length()) {
            int newline = printed.indexOf('\n', at);
            int end = newline < 0 ? printed.length() : newline;
            int from = at;
            while (true) {
                int marker = printed.indexOf("~~(SC", from);
                if (marker < 0 || marker >= end) {
                    break;
                }
                int close = printed.indexOf(')', marker);
                if (close < 0 || close >= end) {
                    break;
                }
                try {
                    lines.put(Integer.parseInt(printed.substring(marker + 5, close)), line);
                } catch (NumberFormatException e) {                        // noqa
                    // not one of ours
                }
                from = close + 1;
            }
            if (newline < 0) {
                break;
            }
            at = newline + 1;
            line++;
        }
        return lines;
    }

    /** Everything this type can stand in for: every supertype and every interface, transitively. */
    private static List<String> assignableTo(JavaType.FullyQualified type) {
        Set<String> found = new LinkedHashSet<>();
        collectSupertypes(type, found, 0);
        found.remove("java.lang.Object");
        found.remove(type == null ? "" : type.getFullyQualifiedName());
        return List.copyOf(found);
    }

    private static void collectSupertypes(JavaType.FullyQualified type, Set<String> into,
                                          int depth) {
        if (type == null || depth > MAX_SUPERTYPE_DEPTH) {
            return;
        }
        JavaType.FullyQualified supertype = type.getSupertype();
        if (supertype != null && into.add(supertype.getFullyQualifiedName())) {
            collectSupertypes(supertype, into, depth + 1);
        }
        for (JavaType.FullyQualified implemented : type.getInterfaces()) {
            if (implemented != null && into.add(implemented.getFullyQualifiedName())) {
                collectSupertypes(implemented, into, depth + 1);
            }
        }
    }

    /**
     * The public members of a type, as RESOLVED signatures.
     *
     * <p>Which is what a signature dump from a text outline cannot be. {@code save(Book)} in the
     * source is {@code com.acme.Book save(com.acme.Book)} here, with the package the compiler
     * decided rather than the one an import line suggested.
     */
    private static List<String> shapeOf(JavaType.FullyQualified type) {
        if (type == null) {
            return List.of();
        }
        List<String> shape = new ArrayList<>();
        for (JavaType.Variable field : type.getMembers()) {
            if (field == null || !isPublic(field.getFlagsBitMap())) {
                continue;
            }
            shape.add(name(field.getType()) + " " + field.getName());
            if (shape.size() >= MAX_SHAPE_MEMBERS) {
                return List.copyOf(shape);
            }
        }
        for (JavaType.Method method : type.getMethods()) {
            if (method == null || !isPublic(method.getFlagsBitMap())) {
                continue;
            }
            StringBuilder sb = new StringBuilder(name(method.getReturnType())).append(' ')
                .append(method.getName()).append('(');
            for (int i = 0; i < method.getParameterTypes().size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(name(method.getParameterTypes().get(i)));
            }
            shape.add(sb.append(')').toString());
            if (shape.size() >= MAX_SHAPE_MEMBERS) {
                break;
            }
        }
        return List.copyOf(shape);
    }

    /** {@code java.lang.reflect.Modifier.PUBLIC}, which is what OpenRewrite's bitmap uses. */
    private static boolean isPublic(long flags) {
        return (flags & 1L) != 0;
    }

    /**
     * A type as a person would write it in a signature.
     *
     * <p>A generic parameter is its own letter — {@code T}, not the parser's internal
     * {@code Generic{T}} — because the shape is quoted verbatim into an answer a worker reads.
     */
    private static String name(JavaType type) {
        if (type == null) {
            return "?";
        }
        if (type instanceof JavaType.GenericTypeVariable generic) {
            return generic.getName();
        }
        if (type instanceof JavaType.Array array) {
            return name(array.getElemType()) + "[]";
        }
        if (type instanceof JavaType.Primitive primitive) {
            return primitive.getKeyword();
        }
        JavaType.FullyQualified qualified = TypeUtils.asFullyQualified(type);
        return qualified != null ? qualified.getFullyQualifiedName() : type.toString();
    }

    private static String kindOf(J.ClassDeclaration declaration) {
        return declaration.getKind() == null ? "class"
            : declaration.getKind().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isAbstract(J.ClassDeclaration declaration) {
        for (J.Modifier modifier : declaration.getModifiers()) {
            if (modifier.getType() == J.Modifier.Type.Abstract) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------

    /**
     * What each build file DECLARES, read structurally rather than by regular expression.
     *
     * <p>{@link XmlParser} and not {@code MavenParser} on purpose. The Maven parser resolves the
     * whole dependency graph, which means following parent poms it may have to fetch; this runs
     * inside an index build that must never depend on a network, and the question here — which
     * file names this artifact, on which line — is answered by the document's own structure.
     * {@link MavenRecipes}, which has to hand OpenRewrite a resolved pom for
     * {@code AddDependency}, uses the Maven parser and says so.
     */
    private static void readPoms(KnowledgeCurator.Root root, SemanticFacts.RootFacts facts) {
        List<Path> poms = pomsUnder(root.path());
        if (poms.isEmpty()) {
            return;
        }
        ExecutionContext ctx = new InMemoryExecutionContext(
            t -> log.debug("openrewrite xml: {}", t.toString()));
        try (Stream<SourceFile> parsed =
                 XmlParser.builder().build().parse(poms, root.path(), ctx)) {
            for (SourceFile file : (Iterable<SourceFile>) parsed::iterator) {
                if (!(file instanceof Xml.Document document)) {
                    continue;
                }
                readOnePom(document, facts);
            }
        } catch (Throwable e) {                                            // noqa
            log.debug("could not read the build files under {}: {}", root.path(), e.toString());
        }
    }

    private static void readOnePom(Xml.Document document, SemanticFacts.RootFacts facts) {
        String file = document.getSourcePath().toString().replace(File.separatorChar, '/');
        AtomicInteger ids = new AtomicInteger();
        Map<Integer, Pending> pending = new LinkedHashMap<>();

        Xml tagged = (Xml) new org.openrewrite.xml.XmlIsoVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer p) {
                Xml.Tag result = super.visitTag(tag, p);
                if (!"dependency".equals(tag.getName())) {
                    return result;
                }
                String group = childValue(tag, "groupId");
                String artifact = childValue(tag, "artifactId");
                if (artifact.isEmpty()) {
                    return result;
                }
                boolean managed = getCursor().getPathAsStream()
                    .anyMatch(o -> o instanceof Xml.Tag t
                        && "dependencyManagement".equals(t.getName()));
                return SearchResult.found(result, mark(ids, pending,
                    new Pending("dependency", group, artifact, childValue(tag, "version"),
                        String.valueOf(managed))));
            }
        }.visit(document, 0);

        if (tagged == null) {
            return;
        }
        Map<Integer, Integer> lines = linesOf(tagged.print(new Cursor(null, tagged)));
        for (Map.Entry<Integer, Pending> entry : pending.entrySet()) {
            Pending what = entry.getValue();
            facts.poms.add(new SemanticFacts.PomFact(file,
                lines.getOrDefault(entry.getKey(), 0), what.a(), what.b(), what.c(),
                Boolean.parseBoolean(what.d())));
        }
    }

    private static String childValue(Xml.Tag tag, String name) {
        for (Xml.Tag child : tag.getChildren()) {
            if (name.equals(child.getName())) {
                return child.getValue().orElse("").strip();
            }
        }
        return "";
    }
}
