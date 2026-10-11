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

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where an annotation a contract names may legally be written, decided from the annotation type's
 * own {@code @Target}: read from the source of the project, from a reference checkout, or from the
 * JDK. A fact, never a guess: an annotation whose declaration cannot be read is "not known", and
 * everything built on this then leaves the contract exactly as it was written.
 *
 * <p>Live supervised build, 2026-10-11: a design wrote {@code @DataModel Book()} in a contract.
 * {@code @DataModel} can only be written on a type. Four candidates put it on the class, which is
 * what the library requires, and all four were failed for a constructor "not carrying" it; the
 * repair round had nothing to change and the task was blocked. A contract must not be able to
 * demand what cannot be, so the place a contract gives an annotation is held to this.
 *
 * <p>An annotation that may be written wherever a type is used ({@code TYPE_USE}) is never judged:
 * Java lets it stand in front of a type, a field, a method with a result and a constructor alike.
 */
public final class AnnotationTargets {

    /** Where a contract's annotation belongs, given the member it was written in front of. */
    public enum Place {
        /** It may be written on that member; the contract stands as written. */
        ON_THE_MEMBER,
        /** It cannot be written on that member and can be written on a type. */
        ON_THE_TYPE,
        /** It can be written neither on that member nor on a type. */
        NOWHERE,
        /** The annotation's declaration could not be read; nothing is decided. */
        UNKNOWN
    }

    /** Knows the JDK's annotations only. */
    public static final AnnotationTargets JDK_ONLY = new AnnotationTargets(null, null);

    /** Every place Java allows an annotation that declares no {@code @Target}. */
    private static final Set<String> EVERY_DECLARATION = declarations();

    private final ProjectTypes project;
    private final LibraryTypes library;

    private AnnotationTargets(ProjectTypes project, LibraryTypes library) {
        this.project = project;
        this.library = library;
    }

    /**
     * @param project the project's own tree, or null
     * @param library the reference checkouts, or null
     */
    public static AnnotationTargets of(ProjectTypes project, LibraryTypes library) {
        return new AnnotationTargets(project, library);
    }

    /**
     * The element types the annotation {@code name} (as a contract wrote it, with or without its
     * package) may be written on; empty when not known. A simple name is answered only when every
     * annotation type of that name the sources hold gives the same answer.
     */
    public Optional<Set<String>> targetsOf(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String written = name.strip().replace('$', '.');
        if (written.indexOf('.') >= 0) {
            return targetsOfQualified(written);
        }
        Set<String> candidates = new LinkedHashSet<>();
        if (project != null) {
            candidates.addAll(project.fullNamesOf(written));
        }
        if (library != null) {
            candidates.addAll(library.fullNamesOf(written, 16));
        }
        candidates.add("java.lang." + written);
        Set<String> agreed = null;
        for (String candidate : candidates) {
            Optional<Set<String>> targets = targetsOfQualified(candidate);
            if (targets.isEmpty()) {
                continue;
            }
            if (agreed != null && !agreed.equals(targets.get())) {
                return Optional.empty();
            }
            agreed = targets.get();
        }
        return Optional.ofNullable(agreed);
    }

    private Optional<Set<String>> targetsOfQualified(String fullName) {
        if (project != null) {
            Optional<Set<String>> targets = project.annotationTargetsOf(fullName);
            if (targets.isPresent()) {
                return targets;
            }
        }
        if (library != null) {
            Optional<Set<String>> targets = library.annotationTargetsOf(fullName);
            if (targets.isPresent()) {
                return targets;
            }
        }
        return jdkTargetsOf(fullName);
    }

    /**
     * Where the annotation {@code name} belongs when a contract writes it in front of
     * {@code member} of the type whose simple name is {@code typeSimpleName}.
     *
     * <p>As generous as the delivery check: a promised field is also met by its accessor, a record
     * component or a constructor parameter, so an annotation that may be written on any of those
     * may be written "on the field".
     */
    public Place placeOf(String name, ContractMember member, String typeSimpleName) {
        Optional<Set<String>> known = targetsOf(name);
        if (known.isEmpty() || member == null) {
            return Place.UNKNOWN;
        }
        Set<String> targets = known.get();
        if (targets.contains(ElementType.TYPE_USE.name())) {
            return Place.UNKNOWN;
        }
        boolean constructor = member.method() && member.type().isEmpty()
            && member.name().equals(typeSimpleName);
        boolean legal;
        if (constructor) {
            legal = targets.contains(ElementType.CONSTRUCTOR.name());
        } else if (member.method()) {
            legal = targets.contains(ElementType.METHOD.name());
        } else {
            legal = targets.contains(ElementType.FIELD.name())
                || targets.contains(ElementType.METHOD.name())
                || targets.contains(ElementType.RECORD_COMPONENT.name())
                || targets.contains(ElementType.PARAMETER.name());
        }
        if (legal) {
            return Place.ON_THE_MEMBER;
        }
        return targets.contains(ElementType.TYPE.name()) ? Place.ON_THE_TYPE : Place.NOWHERE;
    }

    // --- reading a declaration ------------------------------------------------------------------

    /**
     * The targets an annotation type's header declares: its {@code @Target}'s element types, every
     * declaration place when it has no {@code @Target}, or empty when the {@code @Target} holds
     * anything but element types written out.
     */
    static Optional<Set<String>> declaredIn(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String text = header.strip().replaceAll("\\s+", " ");
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '@' && depth == 0) {
                int end = i + 1;
                while (end < text.length() && (Character.isJavaIdentifierPart(text.charAt(end))
                        || text.charAt(end) == '.')) {
                    end++;
                }
                String name = text.substring(i + 1, end);
                if (!name.equals("Target") && !name.equals("java.lang.annotation.Target")) {
                    i = end - 1;
                    continue;
                }
                int open = end;
                while (open < text.length() && text.charAt(open) == ' ') {
                    open++;
                }
                if (open >= text.length() || text.charAt(open) != '(') {
                    return Optional.empty();
                }
                int close = text.indexOf(')', open);
                return close < 0 ? Optional.empty()
                    : elementTypes(text.substring(open + 1, close));
            }
        }
        return Optional.of(EVERY_DECLARATION);
    }

    private static Optional<Set<String>> elementTypes(String arguments) {
        String inside = arguments.strip();
        if (inside.startsWith("value")) {
            String rest = inside.substring("value".length()).strip();
            if (!rest.startsWith("=")) {
                return Optional.empty();
            }
            inside = rest.substring(1).strip();
        }
        if (inside.startsWith("{") && inside.endsWith("}")) {
            inside = inside.substring(1, inside.length() - 1).strip();
        }
        if (inside.isEmpty()) {
            return Optional.of(Set.of());
        }
        Set<String> found = new LinkedHashSet<>();
        for (String part : inside.split(",")) {
            String token = part.strip();
            if (token.isEmpty()) {
                continue; // a trailing comma
            }
            int dot = token.lastIndexOf('.');
            String owner = dot < 0 ? "" : token.substring(0, dot);
            if (!owner.isEmpty() && !owner.equals("ElementType")
                    && !owner.equals("java.lang.annotation.ElementType")) {
                return Optional.empty();
            }
            try {
                found.add(ElementType.valueOf(token.substring(dot + 1)).name());
            } catch (IllegalArgumentException notAnElementType) {
                return Optional.empty();
            }
        }
        return Optional.of(Set.copyOf(found));
    }

    private static Optional<Set<String>> jdkTargetsOf(String fullName) {
        String binary = fullName;
        for (int tries = 0; tries < 4; tries++) {
            try {
                Class<?> type = Class.forName(binary, false, ClassLoader.getPlatformClassLoader());
                if (!type.isAnnotation()) {
                    return Optional.empty();
                }
                Target target = type.getAnnotation(Target.class);
                if (target == null) {
                    return Optional.of(EVERY_DECLARATION);
                }
                List<String> names = new ArrayList<>();
                for (ElementType element : target.value()) {
                    names.add(element.name());
                }
                return Optional.of(Set.copyOf(names));
            } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
                int dot = binary.lastIndexOf('.');
                if (dot < 0) {
                    break;
                }
                binary = binary.substring(0, dot) + '$' + binary.substring(dot + 1); // nested
            }
        }
        return Optional.empty();
    }

    private static Set<String> declarations() {
        Set<String> all = new LinkedHashSet<>();
        for (ElementType element : ElementType.values()) {
            if (element != ElementType.TYPE_USE) {
                all.add(element.name());
            }
        }
        return Set.copyOf(all);
    }
}
