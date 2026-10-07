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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One member a design contract promises, read as the Java declaration it must be: a field, a
 * method, a constructor, an enum constant or a record component, with optional annotations,
 * modifiers, a trailing semicolon and (for a field) an initializer.
 *
 * <p>Harness run 71, 2026-10-02: the architect wrote a member as prose,
 * {@code "call field is annotated @NotBlank (com.zeroz4j.api.validation.NotBlank) - this is the
 * member this story adds"}. Every candidate did the work correctly and every one was rejected for
 * "delivered without the member call field is annotated ...", because the delivery check looked
 * for a member matching the sentence. A text that does not read as a declaration cannot be
 * compared with anything, so {@link #parse} says so by returning empty, and the callers skip it
 * (the delivery check) or send it back to the architect (the design review).
 *
 * <p>Deliberately strict about what counts as a declaration, since the cost of a wrong "yes" is a
 * candidate killed over a sentence: at most a type and a name before a method's parentheses, at
 * most a type and a name for a field, a type that starts upper-case, is a primitive, or is
 * package-qualified, nothing after the closing parenthesis but a {@code throws} clause.
 *
 * @param annotations the annotation names as written ({@code NotBlank}, {@code javax.x.NotBlank}),
 *                    without the {@code @} or arguments
 * @param method      true for a method or constructor
 * @param name        the identifier
 * @param type        a field's type or a method's return type as written, or "" when none was
 *                    written (an enum constant, a constructor, a bare name)
 * @param paramTypes  a method's parameter types as written, in order; varargs become {@code T[]}
 */
public record ContractMember(List<String> annotations, boolean method, String name, String type,
                             List<String> paramTypes) {

    public ContractMember {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
    }

    /** The example form an architect is told to write a member in. */
    public static final String EXAMPLE_FORM = "@Annotation Type name;";

    private static final Set<String> MODIFIERS = Set.of("public", "protected", "private", "static",
        "final", "abstract", "synchronized", "native", "transient", "volatile", "default",
        "strictfp", "sealed", "non-sealed");
    private static final Set<String> PRIMITIVES = Set.of("void", "boolean", "byte", "short", "char",
        "int", "long", "float", "double", "var");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern TYPE = Pattern.compile(
        "[A-Za-z_$][A-Za-z0-9_$.]*(?:<[^;{}()@]*>)?(?:\\[\\s*\\])*");
    private static final Pattern THROWS = Pattern.compile(
        "throws\\s+[A-Za-z_$][A-Za-z0-9_$.]*(?:\\s*,\\s*[A-Za-z_$][A-Za-z0-9_$.]*)*");

    /** The member as a declaration, or empty when the text is not one. */
    public static Optional<ContractMember> parse(String promised) {
        return parse(promised, null);
    }

    /**
     * As {@link #parse(String)}, but an entry that is only annotation(s) plus the type's own
     * simple name is a statement about the type (see {@link #typeAnnotations}), not a member, so
     * it yields empty. A null {@code typeSimpleName} skips that test.
     */
    public static Optional<ContractMember> parse(String promised, String typeSimpleName) {
        if (!typeAnnotations(promised, typeSimpleName).isEmpty()) {
            return Optional.empty();
        }
        if (promised == null || promised.isBlank()) {
            return Optional.empty();
        }
        String text = declarationPart(promised);
        List<String> annotations = new ArrayList<>();
        text = withoutAnnotations(text, annotations);
        if (text == null) {
            return Optional.empty();
        }
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        if (text.isEmpty() || text.contains("{") || text.contains("}") || text.contains(";")) {
            return Optional.empty();
        }
        int open = text.indexOf('(');
        int assign = text.indexOf('=');
        if (open >= 0 && (assign < 0 || open < assign)) {
            Optional<ContractMember> method = parseMethod(text, open, annotations);
            if (method.isEmpty() && ENUM_CONSTANT_WITH_ARGUMENTS.matcher(text).matches()) {
                // ACTIVE("active"): an enum constant with its constructor arguments
                return Optional.of(new ContractMember(annotations, false,
                    text.substring(0, open).strip(), "", List.of()));
            }
            return method;
        }
        if (assign > 0) {
            // a field with its initial value, whatever that value is: List<String> names = new ArrayList<>()
            text = text.substring(0, assign).strip();
        }
        List<String> tokens = tokens(text);
        stripModifiers(tokens);
        if (tokens.size() == 1 && IDENTIFIER.matcher(tokens.get(0)).matches()) {
            return Optional.of(new ContractMember(annotations, false, tokens.get(0), "",
                List.of()));
        }
        if (tokens.size() == 2 && typeLike(tokens.get(0))
                && IDENTIFIER.matcher(tokens.get(1)).matches()) {
            return Optional.of(new ContractMember(annotations, false, tokens.get(1),
                tokens.get(0), List.of()));
        }
        return Optional.empty();
    }

    private static final Pattern ENUM_CONSTANT_WITH_ARGUMENTS =
        Pattern.compile("[A-Z][A-Z0-9_]*\\s*\\(.*\\)");

    /**
     * The declaration in a promised member, without what a model wraps around one: a list bullet,
     * backticks, a trailing comment, or a remark after a dash ({@code Qso addQso(Qso qso) - this
     * story adds the end-before-start guard}, harness run 71). The remark is for the reader; the
     * declaration in front of it is still a promise that can be checked, so it is not thrown away
     * with it. A sentence with no declaration in front stays a sentence.
     */
    public static String declarationPart(String promised) {
        if (promised == null) {
            return "";
        }
        String text = promised.strip().replaceAll("\\s+", " ");
        if (text.startsWith("- ") || text.startsWith("* ") || text.startsWith("\u2022 ")) {
            text = text.substring(2).strip();
        }
        int depth = 0;
        char quote = 0;
        int cut = -1;
        for (int i = 0; i < text.length() && cut < 0; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth <= 0 && (text.startsWith("//", i) || text.startsWith("/*", i))) {
                cut = i;
            } else if (depth <= 0 && c == ' ' && (text.startsWith(" - ", i)
                    || text.startsWith(" -- ", i) || text.startsWith(" \u2013 ", i)
                    || text.startsWith(" \u2014 ", i))) {
                cut = i;
            }
        }
        if (cut >= 0) {
            text = text.substring(0, cut).strip();
        }
        while (text.length() > 1 && text.startsWith("`") && text.endsWith("`")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return text;
    }

    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record",
        "@interface");

    /**
     * The annotations a contract entry puts on the TYPE itself, or empty when the entry is not
     * that. Harness run 77: the architect wrote the type's own annotation as the first "member",
     * {@code "@com.zeroz4j.api.DataModel LogbookSort;"}: annotation(s), then the type's own simple
     * name, optionally with a {@code class}/{@code interface}/{@code enum}/{@code record} keyword
     * and modifiers, optionally ending in a semicolon. Read as a member it matched the
     * constructor and every candidate was failed for a constructor "not carrying" the annotation.
     * It is a statement about the type, and every check that reads members must treat it so.
     */
    public static List<String> typeAnnotations(String promised, String typeSimpleName) {
        if (promised == null || typeSimpleName == null || promised.isBlank()) {
            return List.of();
        }
        List<String> annotations = new ArrayList<>();
        String text = withoutAnnotations(declarationPart(promised), annotations);
        if (text == null || annotations.isEmpty()) {
            return List.of();
        }
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        List<String> tokens = tokens(text);
        while (!tokens.isEmpty() && MODIFIERS.contains(tokens.get(0))) {
            tokens.remove(0);
        }
        if (tokens.size() == 2 && TYPE_KEYWORDS.contains(tokens.get(0))) {
            tokens.remove(0);
        }
        return tokens.size() == 1 && tokens.get(0).equals(typeSimpleName)
            ? List.copyOf(annotations) : List.of();
    }

    /** True when {@code promised} reads as a declaration. */
    public static boolean isDeclaration(String promised) {
        return parse(promised).isPresent();
    }

    /**
     * The names of the annotations on a declared member's header, as written, without the
     * {@code @} or arguments. Only annotations outside any parentheses count — a method's
     * parameters' annotations are the parameters', not the method's.
     */
    public static List<String> annotationsOf(String header) {
        List<String> names = new ArrayList<>();
        if (header == null) {
            return names;
        }
        String text = header.strip().replaceAll("\\s+", " ");
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '<') {
                depth++;
            } else if (c == ')' || c == '>') {
                depth--;
            } else if (c == '=' && depth == 0) {
                break;
            } else if (c == '@' && depth == 0 && !text.startsWith("@interface", i)) {
                int end = i + 1;
                while (end < text.length() && (Character.isJavaIdentifierPart(text.charAt(end))
                        || text.charAt(end) == '.')) {
                    end++;
                }
                if (end > i + 1) {
                    names.add(text.substring(i + 1, end));
                }
                i = end - 1;
            }
        }
        return names;
    }

    /** The last dot-separated segment of an annotation or type name, lower-cased for comparing. */
    public static String simpleName(String name) {
        String text = name.strip();
        int dot = text.lastIndexOf('.');
        return dot < 0 ? text : text.substring(dot + 1);
    }

    // --- parsing helpers -----------------------------------------------------------------------

    private static Optional<ContractMember> parseMethod(String text, int open,
                                                        List<String> annotations) {
        int close = matchingParen(text, open);
        if (close < 0) {
            return Optional.empty();
        }
        String tail = text.substring(close + 1).strip();
        if (tail.startsWith("default ")) {
            tail = ""; // an annotation type's element with its default value: int max() default 10
        }
        if (!tail.isEmpty() && !THROWS.matcher(tail).matches()) {
            return Optional.empty();
        }
        List<String> head = tokens(text.substring(0, open).strip());
        stripModifiers(head);
        if (!head.isEmpty() && head.get(0).startsWith("<")) {
            head.remove(0); // generic method's own type parameters
        }
        String type = "";
        String name;
        if (head.size() == 1) {
            name = head.get(0);
        } else if (head.size() == 2 && typeLike(head.get(0))) {
            type = head.get(0);
            name = head.get(1);
        } else {
            return Optional.empty();
        }
        if (!IDENTIFIER.matcher(name).matches()) {
            return Optional.empty();
        }
        List<String> params = new ArrayList<>();
        String inside = text.substring(open + 1, close).strip();
        if (!inside.isEmpty()) {
            for (String part : splitTopLevel(inside)) {
                String param = parameterType(part);
                if (param == null) {
                    return Optional.empty();
                }
                params.add(param);
            }
        }
        return Optional.of(new ContractMember(annotations, true, name, type, params));
    }

    /** One parameter reduced to its type, or null when it is not a parameter. */
    private static String parameterType(String raw) {
        List<String> annotations = new ArrayList<>();
        String text = withoutAnnotations(raw.strip(), annotations);
        if (text == null || text.isEmpty()) {
            return null;
        }
        boolean varargs = text.contains("...");
        text = text.replace("...", " ").strip();
        List<String> tokens = tokens(text);
        while (!tokens.isEmpty() && tokens.get(0).equals("final")) {
            tokens.remove(0);
        }
        String type;
        if (tokens.size() == 1) {
            type = tokens.get(0);
        } else if (tokens.size() == 2 && IDENTIFIER.matcher(tokens.get(1)).matches()) {
            type = tokens.get(0);
        } else {
            return null;
        }
        if (!typeLike(type)) {
            return null;
        }
        return type + (varargs ? "[]" : "");
    }

    /** Upper-case start, a primitive, or package-qualified: never an ordinary lower-case word. */
    private static boolean typeLike(String token) {
        if (!TYPE.matcher(token).matches()) {
            return false;
        }
        String raw = token;
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        raw = raw.replace("[", "").replace("]", "").strip();
        return PRIMITIVES.contains(raw) || raw.contains(".")
            || (!raw.isEmpty() && Character.isUpperCase(raw.charAt(0)));
    }

    private static void stripModifiers(List<String> tokens) {
        while (!tokens.isEmpty() && MODIFIERS.contains(tokens.get(0)) && tokens.size() > 1) {
            tokens.remove(0);
        }
    }

    /** Whitespace-separated tokens, keeping a generic's {@code <A, B>} together. */
    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (Character.isWhitespace(c) && depth <= 0) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** Leading annotations (and any between modifiers) removed into {@code into}; null if broken. */
    private static String withoutAnnotations(String text, List<String> into) {
        StringBuilder rest = new StringBuilder();
        int i = 0;
        int angle = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '<') {
                angle++;
            } else if (c == '>') {
                angle--;
            }
            if (c == '@' && angle == 0) {
                int end = i + 1;
                while (end < text.length() && (Character.isJavaIdentifierPart(text.charAt(end))
                        || text.charAt(end) == '.')) {
                    end++;
                }
                if (end == i + 1) {
                    return null;
                }
                into.add(text.substring(i + 1, end));
                int after = end;
                while (after < text.length() && text.charAt(after) == ' ') {
                    after++;
                }
                if (after < text.length() && text.charAt(after) == '(') {
                    int close = matchingParen(text, after);
                    if (close < 0) {
                        return null;
                    }
                    end = close + 1;
                }
                rest.append(' ');
                i = end;
                continue;
            }
            rest.append(c);
            i++;
        }
        return rest.toString().strip().replaceAll("\\s+", " ");
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '<' || c == '[') {
                depth++;
            } else if (c == ')' || c == '>' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }
}
