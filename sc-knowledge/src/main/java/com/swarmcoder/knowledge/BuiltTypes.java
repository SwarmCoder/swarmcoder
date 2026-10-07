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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The types a build PRODUCED in a tree, as opposed to the ones somebody wrote: source an annotation
 * processor generated ({@code target/generated-sources/**}, {@code build/generated/**}) and
 * compiled classes ({@code target/classes}, {@code build/classes/**}).
 *
 * <p>Harness run 72, 2026-10-02: a design contract {@code com.hambook.Qso_Rules} names a class the
 * library's annotation processor generates once a field is annotated. It is never a hand-written
 * file. Every candidate was built green and had the class, and every one was failed for "no type
 * of that name exists anywhere in this candidate's tree", because {@link ProjectTypes} reads
 * hand-written sources only and skips build output on purpose. A contract type counts as delivered
 * when the build made it, and the check must therefore look at what the build made.
 *
 * <p>Generated source is preferred for comparing members (the same reader as for hand-written
 * code). A class file is read with ASM, which is already on the classpath through OpenRewrite;
 * the JDK's own class-file API is not available at this project's Java level (21). Members of a
 * generic type are compared without their erased types, because a class file does not carry what
 * the contract wrote ({@code T get()} compiles to {@code Object get()}).
 */
final class BuiltTypes {

    private static final Logger log = LoggerFactory.getLogger(BuiltTypes.class);

    private static final Set<String> NEVER_ENTER = Set.of(".git", "node_modules", ".m2", ".idea");
    private static final Pattern TYPE_VARIABLE = Pattern.compile("T[A-Za-z0-9_$]+;");

    private final Map<String, Path> generatedSource = new HashMap<>();
    private final Map<String, Path> classFile = new HashMap<>();

    private BuiltTypes() {}

    /**
     * An index of the build output under {@code root} for just the named types; empty when there is
     * no such tree or none of the types was built.
     */
    static BuiltTypes of(Path root, Set<String> fullNames) {
        BuiltTypes built = new BuiltTypes();
        if (root == null || fullNames == null || fullNames.isEmpty() || !Files.isDirectory(root)) {
            return built;
        }
        Map<String, List<String>> bySimple = new HashMap<>();
        for (String full : fullNames) {
            bySimple.computeIfAbsent(full.substring(full.lastIndexOf('.') + 1), k -> new ArrayList<>())
                .add(full);
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return dir != root && NEVER_ENTER.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    boolean java = name.endsWith(".java");
                    if (!java && !name.endsWith(".class")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String stem = name.substring(0, name.lastIndexOf('.'));
                    // a nested type compiles to Outer$Inner.class
                    List<String> candidates = bySimple.get(java ? stem
                        : stem.substring(stem.lastIndexOf('$') + 1));
                    if (candidates == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    List<String> segments = new ArrayList<>();
                    for (Path segment : root.relativize(file)) {
                        segments.add(segment.toString());
                    }
                    if (!(java ? inGeneratedSources(segments) : inClassOutput(segments))) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = String.join("/", segments);
                    if (!java) {
                        relative = relative.replace('$', '/');
                    }
                    for (String full : candidates) {
                        String suffix = full.replace('.', '/') + (java ? ".java" : ".class");
                        if (relative.equals(suffix) || relative.endsWith("/" + suffix)) {
                            (java ? built.generatedSource : built.classFile).putIfAbsent(full, file);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the build output under {}: {}", root, e.toString());
        }
        return built;
    }

    private static boolean inGeneratedSources(List<String> segments) {
        for (int i = 0; i < segments.size() - 1; i++) {
            String s = segments.get(i);
            if (s.equals("generated-sources") || s.equals("generated-test-sources")) {
                return true;
            }
            if (s.equals("build") && segments.get(i + 1).equals("generated")) {
                return true;
            }
        }
        return false;
    }

    private static boolean inClassOutput(List<String> segments) {
        for (int i = 0; i < segments.size() - 1; i++) {
            String s = segments.get(i);
            String next = segments.get(i + 1);
            if (s.equals("target") && (next.equals("classes") || next.equals("test-classes"))) {
                return true;
            }
            if (s.equals("build") && next.equals("classes")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The members of the compiled class alone, for a type that IS hand-written: what the build
     * added to it (an annotation processor that completes a class) is in the class file and
     * nowhere in the source. Empty when the build left no class of that name, or it cannot be read.
     */
    List<JavaSourceFacts.Declared> classMembersOf(String fullName) {
        Path clazz = classFile.get(fullName);
        if (clazz == null) {
            return List.of();
        }
        try {
            return membersFromClass(Files.readAllBytes(clazz),
                fullName.substring(fullName.lastIndexOf('.') + 1));
        } catch (IOException | RuntimeException | LinkageError e) {
            log.debug("Could not read the members of {} from {}: {}", fullName, clazz, e.toString());
            return List.of();
        }
    }

    /**
     * Whether a type the build produced extends or implements anything but {@code Object}, so a
     * member it does not declare may still be one it has. True also when that cannot be read.
     */
    boolean mayInherit(String fullName) {
        String simple = fullName.substring(fullName.lastIndexOf('.') + 1);
        Path source = generatedSource.get(fullName);
        if (source != null) {
            try {
                return !JavaSourceFacts.of(Files.readString(source)).supertypesOf(simple).isEmpty();
            } catch (IOException | RuntimeException e) {
                return true;
            }
        }
        Path clazz = classFile.get(fullName);
        if (clazz == null) {
            return false;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(clazz)).accept(node,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            boolean plainSuper = node.superName == null || node.superName.equals("java/lang/Object");
            return !plainSuper || (node.interfaces != null && !node.interfaces.isEmpty());
        } catch (IOException | RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** The annotations on the built type itself: from its generated source and its class file. */
    List<String> typeAnnotationsOf(String fullName) {
        String simple = fullName.substring(fullName.lastIndexOf('.') + 1);
        List<String> found = new ArrayList<>();
        Path source = generatedSource.get(fullName);
        if (source != null) {
            try {
                found.addAll(JavaSourceFacts.of(Files.readString(source)).typeAnnotationsOf(simple));
            } catch (IOException | RuntimeException e) {
                log.debug("Could not read generated source {}: {}", source, e.toString());
            }
        }
        Path clazz = classFile.get(fullName);
        if (clazz != null) {
            try {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(clazz)).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                found.addAll(annotations(node.visibleAnnotations, node.invisibleAnnotations));
            } catch (IOException | RuntimeException | LinkageError e) {
                log.debug("Could not read the annotations of {}: {}", clazz, e.toString());
            }
        }
        return found;
    }

    /** True when the build produced a source or a class file for this fully-qualified name. */
    boolean has(String fullName) {
        return generatedSource.containsKey(fullName) || classFile.containsKey(fullName);
    }

    /**
     * The members of a built type: from its generated source when there is one that yields any,
     * else from its class file; empty when neither can be read (the caller then concludes only
     * that the type exists).
     */
    List<JavaSourceFacts.Declared> membersOf(String fullName) {
        String simple = fullName.substring(fullName.lastIndexOf('.') + 1);
        Path source = generatedSource.get(fullName);
        if (source != null) {
            try {
                List<JavaSourceFacts.Declared> members =
                    JavaSourceFacts.of(Files.readString(source)).membersOf(simple);
                if (!members.isEmpty()) {
                    return members;
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Could not read generated source {}: {}", source, e.toString());
            }
        }
        Path clazz = classFile.get(fullName);
        if (clazz == null) {
            return List.of();
        }
        try {
            return membersFromClass(Files.readAllBytes(clazz), simple);
        } catch (IOException | RuntimeException | LinkageError e) {
            log.info("Could not read the members of {} from {} ({}); the type exists, its members "
                + "were not compared", fullName, clazz, e.toString());
            return List.of();
        }
    }

    /** What the class file declares, in the shape the source reader produces. Package-private for tests. */
    static List<JavaSourceFacts.Declared> membersFromClass(byte[] bytes, String simple) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node,
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<JavaSourceFacts.Declared> members = new ArrayList<>();
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }
            String type = hasTypeVariable(field.signature) ? ""
                : typeName(Type.getType(field.desc));
            members.add(new JavaSourceFacts.Declared(field.name, false, type, List.of(),
                annotations(field.visibleAnnotations, field.invisibleAnnotations)));
        }
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                    || method.name.equals("<clinit>")) {
                continue;
            }
            boolean generic = hasTypeVariable(method.signature);
            boolean constructor = method.name.equals("<init>");
            List<String> params = new ArrayList<>();
            for (Type arg : Type.getArgumentTypes(method.desc)) {
                params.add(generic ? "?" : typeName(arg));
            }
            String returns = constructor || generic ? ""
                : typeName(Type.getReturnType(method.desc));
            members.add(new JavaSourceFacts.Declared(constructor ? simple : method.name, true,
                returns, params,
                annotations(method.visibleAnnotations, method.invisibleAnnotations)));
        }
        return List.copyOf(members);
    }

    private static boolean hasTypeVariable(String signature) {
        return signature != null && TYPE_VARIABLE.matcher(signature).find();
    }

    private static String typeName(Type type) {
        return type.getClassName().replace('$', '.');
    }

    @SafeVarargs
    private static List<String> annotations(List<AnnotationNode>... lists) {
        Set<String> names = new HashSet<>();
        List<String> ordered = new ArrayList<>();
        for (List<AnnotationNode> list : lists) {
            if (list == null) {
                continue;
            }
            for (AnnotationNode annotation : list) {
                String name = typeName(Type.getType(annotation.desc));
                if (names.add(name)) {
                    ordered.add(name);
                }
            }
        }
        return ordered;
    }
}
