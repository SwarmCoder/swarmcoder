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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three facts about a Java source file that a run needs in order to hold everybody to the same
 * type vocabulary: what it DECLARES, what it REFERS TO, and what a declared type's members are.
 *
 * <p>The public face of {@link JavaOutline}, which is the brace-counting scanner this project
 * already had — it knows about comments, string and character literals and text blocks, so a name
 * inside any of those is never mistaken for a type reference. There is no compiler and no
 * classpath here, and that is on purpose: this runs before any code exists and inside a worktree
 * that may not build, where the only thing available is the text.
 *
 * <p><b>It reports, it does not accuse.</b> Every list can come back short — an unparsable file
 * yields nothing at all — and every caller treats a short list as "we could not tell", never as
 * "there is nothing there". A run must not be stopped by a scanner's guess.
 */
public final class JavaSourceFacts {

    /**
     * One member of a declared type.
     *
     * @param name       its identifier, e.g. {@code rating}
     * @param method     true for a method or constructor, false for a field, a record component or
     *                   an enum constant
     * @param type       the declared type as written — a field's type, a method's return type, or
     *                   the enclosing enum's own name for a constant — or ""
     * @param paramTypes a method's parameter types, simple names as written, in order; empty for
     *                   everything that is not a method
     * @param annotations the annotations on the member as written ({@code NotBlank} or
     *                   {@code javax.x.NotBlank}), without the {@code @} or arguments
     */
    public record Declared(String name, boolean method, String type, List<String> paramTypes,
                           List<String> annotations) {
        public Declared {
            paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }

        public Declared(String name, boolean method, String type, List<String> paramTypes) {
            this(name, method, type, paramTypes, List.of());
        }
    }

    private final JavaOutline outline;
    private final String code;

    private JavaSourceFacts(JavaOutline outline) {
        this.outline = outline;
        this.code = outline.withoutCommentsOrLiterals();
    }

    public static JavaSourceFacts of(String source) {
        return new JavaSourceFacts(JavaOutline.of(source));
    }

    /** The file's package, or "" for the default package or a file that declares none. */
    public String packageName() {
        return outline.packageName;
    }

    /** Every import as written, without the {@code import} keyword or the semicolon. */
    public List<String> imports() {
        return List.copyOf(outline.imports);
    }

    /** The simple names of every type declared here, nested types included. */
    public List<String> declaredTypes() {
        Set<String> names = new LinkedHashSet<>();
        for (JavaOutline.Member type : outline.types) {
            collectTypeNames(type, names);
        }
        return List.copyOf(names);
    }

    private static void collectTypeNames(JavaOutline.Member member, Set<String> into) {
        if (member.kind() == JavaOutline.Kind.TYPE) {
            into.add(member.name());
        }
        for (JavaOutline.Member child : member.children()) {
            collectTypeNames(child, into);
        }
    }

    /**
     * The members of the type named {@code simpleTypeName}, or an empty list when this file does
     * not declare it.
     *
     * <p>A record's components count as members twice over, as a field and as the accessor the
     * compiler generates for it, because both are how a test reaches them and neither appears in
     * the record's body.
     */
    public List<Declared> membersOf(String simpleTypeName) {
        JavaOutline.Member type = typeNamed(simpleTypeName);
        if (type == null) {
            return List.of();
        }
        List<Declared> members = new ArrayList<>();
        boolean isRecord = "record".equals(kindOf(type));
        List<String> canonical = new ArrayList<>();
        for (String component : isRecord ? recordComponents(type.header()) : List.<String>of()) {
            List<String> componentAnnotations = ContractMember.annotationsOf(component);
            String bareComponent = JavaOutline.stripAnnotations(JavaOutline.collapse(component));
            String name = lastToken(bareComponent);
            String declaredType = beforeLastToken(bareComponent);
            members.add(new Declared(name, false, declaredType, List.of(), componentAnnotations));
            members.add(new Declared(name, true, declaredType, List.of(), componentAnnotations));
            canonical.add(declaredType);
        }
        if (isRecord) {
            // the canonical constructor every record has, whether or not one is written out
            members.add(new Declared(type.name(), true, "", canonical));
        }
        for (JavaOutline.Member child : type.children()) {
            switch (child.kind()) {
                case FIELD -> members.add(new Declared(child.name(), false,
                    fieldOrReturnType(child.header(), child.name(), false), List.of(),
                    ContractMember.annotationsOf(child.header())));
                case METHOD, CONSTRUCTOR -> members.add(new Declared(child.name(), true,
                    fieldOrReturnType(child.header(), child.name(), true),
                    paramTypes(child.header()), ContractMember.annotationsOf(child.header())));
                case ENUM_CONSTANTS -> {
                    // Recorded by the scanner as ONE member holding every constant's text, comma
                    // separated, constructor args and any constant body included. A contract names
                    // each constant individually, so this pulls the name out of each one — a
                    // constant is, for matching purposes, a field of the enum's own type.
                    for (String constant : enumConstantNames(child.header())) {
                        members.add(new Declared(constant, false, type.name(), List.of()));
                    }
                }
                default -> { }
            }
        }
        return List.copyOf(members);
    }

    /**
     * One member of a type's public surface, as declared: its name, and its declaration header
     * (modifiers, type parameters, name, parameters, throws clause — no body, whitespace
     * collapsed). A constructor's name is the type's own simple name.
     */
    public record Exposed(String name, String header) {}

    /**
     * The constructors, methods and fields of {@code simpleTypeName} that are part of its public
     * surface — public or protected, or members of an interface — in source order. Empty when this
     * file does not declare the type.
     *
     * <p>Written for the test author's correction after a test misused a type that already exists
     * (brownfield harness run 43, 2026-09-26: {@code new Document(..., Parser)} against a
     * constructor taking two Strings). Telling it "that does not compile" without the real
     * signature invites the same guess twice; the header, read off the checkout, is the answer.
     */
    public List<Exposed> exposedMembers(String simpleTypeName) {
        JavaOutline.Member type = typeNamed(simpleTypeName);
        if (type == null) {
            return List.of();
        }
        List<Exposed> exposed = new ArrayList<>();
        for (JavaOutline.Member child : type.children()) {
            if (!child.exposed()) {
                continue;
            }
            switch (child.kind()) {
                case FIELD, METHOD, CONSTRUCTOR -> exposed.add(new Exposed(child.name(), child.header()));
                default -> { }
            }
        }
        return List.copyOf(exposed);
    }

    private static final Pattern CLASS_KEYWORD = Pattern.compile("(?:^|\\s)class\\s");

    /**
     * Why no other class can use {@code simpleTypeName} as this file declares it, or null when it
     * can be used — or when that cannot be settled, which is never read as "unusable".
     *
     * <p>Harness run 65 (2026-10-02): a task delivered its text-constants class as a final class
     * with a private constructor and instance methods only. It compiled, the task claimed no
     * acceptance check, and it merged; every later task that was told to read its text from that
     * class could not, and blamed the rule that said to. A class is reported here only in exactly
     * that shape, which is unusable in any Java program whatever library it is written for:
     * a plain class (not abstract, not an interface, enum, record or annotation), every one of
     * whose constructors is private, with nothing static that is reachable from outside (no
     * non-private static method, field or nested type through which an instance could be handed
     * out), and with at least one non-private instance member — the sign that it was meant to be
     * used through an instance nobody can get.
     *
     * <p>It fails open on anything that could make such a class reachable after all: an
     * annotation on the class or on a constructor (a framework may construct it reflectively) or
     * an enum constant.
     */
    public String unusableFromOutside(String simpleTypeName) {
        JavaOutline.Member type = typeNamed(simpleTypeName);
        if (type == null || type.header().strip().startsWith("@")) {
            return null;
        }
        String header = JavaOutline.stripAnnotations(type.header());
        Set<String> typeModifiers = modifiersOf(header);
        if (!CLASS_KEYWORD.matcher(header).find() || typeModifiers.contains("abstract")
                || typeModifiers.contains("sealed")) {
            return null;
        }
        int constructors = 0;
        List<String> instanceMembers = new ArrayList<>();
        for (JavaOutline.Member child : type.children()) {
            Set<String> modifiers = modifiersOf(JavaOutline.stripAnnotations(child.header()));
            boolean reachable = !modifiers.contains("private");
            switch (child.kind()) {
                case CONSTRUCTOR -> {
                    if (reachable || child.header().strip().startsWith("@")) {
                        return null;
                    }
                    constructors++;
                }
                case TYPE, ENUM_CONSTANTS -> {
                    if (reachable) {
                        return null; // a nested builder or holder may hand an instance out
                    }
                }
                case METHOD, FIELD -> {
                    if (reachable && modifiers.contains("static")) {
                        return null; // a factory, a constant, a shared instance
                    }
                    if (reachable) {
                        instanceMembers.add(child.name());
                    }
                }
                default -> { }
            }
        }
        if (constructors == 0 || instanceMembers.isEmpty()) {
            return null; // the default constructor is usable; nothing to reach is not this defect
        }
        return "class " + type.name() + " cannot be used by any other class: every constructor is "
            + "private and it has no static method, field or nested type that hands out an "
            + "instance, yet its members (" + String.join(", ", instanceMembers.stream().limit(4)
                .toList()) + (instanceMembers.size() > 4 ? ", …" : "") + ") are instance members";
    }

    /** The modifier keywords of a declaration header, read up to its name's '(' or '='. */
    private static Set<String> modifiersOf(String bareHeader) {
        int end = bareHeader.length();
        for (char stop : new char[] {'(', '=', '{'}) {
            int at = bareHeader.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        Set<String> found = new LinkedHashSet<>();
        for (String token : bareHeader.substring(0, end).split("\\s+")) {
            if (MODIFIERS.contains(token) || token.equals("sealed")) {
                found.add(token);
            }
        }
        return found;
    }

    private static final Pattern KIND = Pattern.compile(
        "(?:^|\\s)(@interface|interface|enum|record|class)\\s+[A-Za-z_$]");

    /**
     * What kind of type this is: {@code class}, {@code interface}, {@code enum}, {@code record} or
     * {@code annotation}; "" when the file declares no type of this name.
     */
    public String kindOf(String simpleTypeName) {
        JavaOutline.Member type = typeNamed(simpleTypeName);
        return type == null ? "" : kindOf(type);
    }

    /** The annotations written on the type's own declaration, as written; empty when none. */
    public List<String> typeAnnotationsOf(String simpleTypeName) {
        JavaOutline.Member type = typeNamed(simpleTypeName);
        return type == null ? List.of() : ContractMember.annotationsOf(type.header());
    }

    private static String kindOf(JavaOutline.Member type) {
        Matcher m = KIND.matcher(JavaOutline.stripAnnotations(JavaOutline.collapse(type.header())));
        if (!m.find()) {
            return "";
        }
        return "@interface".equals(m.group(1)) ? "annotation" : m.group(1);
    }

    /**
     * Whether the type's body was read: it has one, closed. False for a type this scanner could
     * only see the header of, whose members are therefore unknown rather than absent.
     */
    public boolean bodyWasRead(String simpleTypeName) {
        JavaOutline.Member type = typeNamed(simpleTypeName);
        return type != null && type.hasBody();
    }

    /** Whether the type writes out a constructor of its own; false means it has the default one. */
    public boolean declaresConstructor(String simpleTypeName) {
        JavaOutline.Member type = typeNamed(simpleTypeName);
        return type != null && type.children().stream()
            .anyMatch(child -> child.kind() == JavaOutline.Kind.CONSTRUCTOR);
    }

    /**
     * The types this one extends or implements, as written in its header and without their type
     * arguments ({@code Base}, {@code java.util.List}); empty when it names none or is not here.
     */
    public List<String> supertypesOf(String simpleTypeName) {
        JavaOutline.Member type = typeNamed(simpleTypeName);
        if (type == null) {
            return List.of();
        }
        String header = JavaOutline.stripAnnotations(JavaOutline.collapse(type.header()));
        Matcher name = Pattern.compile("(?:@interface|interface|enum|record|class)\\s+"
            + Pattern.quote(type.name()) + "\\b").matcher(header);
        if (!name.find()) {
            return List.of();
        }
        // drop type arguments and a record's components, keeping only the clause words and names
        StringBuilder flat = new StringBuilder();
        int angle = 0;
        int paren = 0;
        for (int i = name.end(); i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '<') {
                angle++;
            } else if (c == '>') {
                angle--;
            } else if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren--;
            } else if (c == '{') {
                break;
            } else if (angle == 0 && paren == 0) {
                flat.append(c);
            }
        }
        List<String> supertypes = new ArrayList<>();
        boolean reading = false;
        for (String token : flat.toString().replace(",", " ").trim().split("\\s+")) {
            if (token.equals("extends") || token.equals("implements")) {
                reading = true;
            } else if (token.equals("permits")) {
                reading = false;
            } else if (reading && !token.isEmpty()) {
                supertypes.add(token);
            }
        }
        return List.copyOf(supertypes);
    }

    /**
     * Every type this file declares, with the types it is nested in: {@code Logbook} and
     * {@code Logbook.Entry}. {@link #declaredTypes()} gives the simple names alone.
     */
    public List<String> declaredTypePaths() {
        List<String> paths = new ArrayList<>();
        for (JavaOutline.Member type : outline.types) {
            collectTypePaths(type, "", paths);
        }
        return List.copyOf(paths);
    }

    private static void collectTypePaths(JavaOutline.Member member, String outer, List<String> into) {
        if (member.kind() != JavaOutline.Kind.TYPE) {
            return;
        }
        String path = outer.isEmpty() ? member.name() : outer + "." + member.name();
        into.add(path);
        for (JavaOutline.Member child : member.children()) {
            collectTypePaths(child, path, into);
        }
    }

    /** True when this file declares {@code simpleTypeName} at any nesting level. */
    public boolean declares(String simpleTypeName) {
        return typeNamed(simpleTypeName) != null;
    }

    // {@code interface Foo}, {@code public interface Foo} — never {@code @interface Foo}, an
    // annotation type, which a lambda can no more implement than it can a class.
    private static final Pattern INTERFACE_KEYWORD = Pattern.compile("(?<!@)\\binterface\\b");

    /**
     * True when this file declares {@code simpleTypeName} as a plain interface — never a class,
     * enum, record or annotation type. The one shape a lambda or a method reference can ever be
     * assigned to, which is what {@code SelfImplementedContract} (module {@code sc-workflow})
     * uses this for: harness run 42, 2026-09-26, flagged the @DataModel class {@code Book} for
     * "writing a lambda that IS the contract" over a line that only USED a delivered class —
     * {@code Book existing = service.getBooks().stream().filter(b -> ...)} — because its lambda
     * pattern matched an arrow anywhere between "=" and the next ";", three method calls into an
     * ordinary stream pipeline. A lambda literal cannot compile against a class however its
     * surrounding text looks, so confirming interface-ness here rules that whole class of false
     * positive out at the type, not only by tightening the pattern that reads the test file.
     *
     * @return false both when the file declares the type as something other than an interface,
     *         and when it does not declare the type at all — the caller tells those apart with
     *         {@link #declares}, because an undelivered type confirms nothing either way
     */
    public boolean isInterface(String simpleTypeName) {
        JavaOutline.Member type = typeNamed(simpleTypeName);
        return type != null && INTERFACE_KEYWORD.matcher(type.header()).find();
    }

    private JavaOutline.Member typeNamed(String simpleTypeName) {
        if (simpleTypeName == null || simpleTypeName.isBlank()) {
            return null;
        }
        for (JavaOutline.Member type : outline.types) {
            JavaOutline.Member found = typeNamed(type, simpleTypeName.strip());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static JavaOutline.Member typeNamed(JavaOutline.Member member, String name) {
        if (member.kind() == JavaOutline.Kind.TYPE && member.name().equals(name)) {
            return member;
        }
        for (JavaOutline.Member child : member.children()) {
            JavaOutline.Member found = typeNamed(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // --- what the file refers to ------------------------------------------------------------

    /** {@code new Book(}, {@code new Book<} */
    private static final Pattern INSTANTIATION =
        Pattern.compile("\\bnew\\s+([A-Z][A-Za-z0-9_]*)\\s*[(<\\[]");
    /** {@code Book b = }, {@code Rating r;}, {@code void f(Book b)} */
    private static final Pattern DECLARATION = Pattern.compile(
        "(?<![.\\w])([A-Z][A-Za-z0-9_]*)(?:<[^;{}()]*>)?(?:\\[\\s*\\])*\\s+[a-z_$][A-Za-z0-9_$]*\\s*[=;,)]");
    /** {@code extends Book}, {@code implements Rated}, {@code throws IOException} */
    private static final Pattern IN_CLAUSE =
        Pattern.compile("\\b(?:extends|implements|throws|instanceof)\\s+([A-Z][A-Za-z0-9_]*)");
    /** {@code catch (BadThing e)} */
    private static final Pattern IN_CATCH =
        Pattern.compile("\\bcatch\\s*\\(\\s*(?:final\\s+)?([A-Z][A-Za-z0-9_]*)");
    /** {@code Rating.of(...)}, {@code Book.TITLE} — a static reach through a type. */
    private static final Pattern STATIC_ACCESS =
        Pattern.compile("(?<![.\\w\"])([A-Z][A-Za-z0-9_]*)\\.[a-zA-Z_$]");

    /**
     * Every simple type name this file appears to REFER to, in the positions where a name can only
     * be a type. Deliberately not every capitalised word: a caller decides whether an unresolved
     * name is a fault, and a name read out of a comment or a string would make that decision on
     * nothing.
     */
    public List<String> referencedTypeNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Pattern pattern : List.of(INSTANTIATION, DECLARATION, IN_CLAUSE, IN_CATCH,
                STATIC_ACCESS)) {
            Matcher m = pattern.matcher(code);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return List.copyOf(names);
    }

    /**
     * The simple name an import ends in, for a single-type import; "" for a wildcard or a static
     * import, which name no type this can check.
     */
    public static String importedSimpleName(String importStatement) {
        if (importStatement == null) {
            return "";
        }
        String text = importStatement.strip();
        if (text.startsWith("static ") || text.endsWith("*")) {
            return "";
        }
        int dot = text.lastIndexOf('.');
        return dot < 0 ? text : text.substring(dot + 1);
    }

    // --- headers -------------------------------------------------------------------------------

    /**
     * The declared type in a member header: the text before the member's own name. Package-visible
     * so {@link ContractDelivery} can normalize a promised member's text through the exact same
     * rule it applies to a declared one — modifiers, annotations and a method's parameter list
     * stripped the same way on both sides of the comparison.
     */
    static String fieldOrReturnType(String header, String name, boolean method) {
        String bare = JavaOutline.stripAnnotations(JavaOutline.collapse(header));
        int paren = bare.indexOf('(');
        if (method && paren >= 0) {
            bare = bare.substring(0, paren);
        }
        int at = bare.lastIndexOf(name);
        if (at <= 0) {
            return "";
        }
        return withoutTypeParameters(withoutModifiers(bare.substring(0, at).strip()));
    }

    /**
     * {@code <T extends Comparable<T>> List<T>} without the method's own type parameters:
     * {@code List<T>}. They are part of the declaration, not of the type it returns.
     */
    private static String withoutTypeParameters(String text) {
        if (!text.startsWith("<")) {
            return text;
        }
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>' && --depth == 0) {
                return withoutModifiers(text.substring(i + 1).strip());
            }
        }
        return text;
    }

    /**
     * A method or constructor header's parameter types, simple names as written, in declaration
     * order — annotations, modifiers (e.g. {@code final}), parameter names, generic arguments and
     * a {@code throws} clause all ignored. Empty when the header has no parameter list, or none of
     * its parameters could be read.
     */
    static List<String> paramTypes(String header) {
        String bare = JavaOutline.stripAnnotations(JavaOutline.collapse(header));
        int open = bare.indexOf('(');
        if (open < 0) {
            return List.of();
        }
        int close = matchingParen(bare, open);
        String inside = close < 0 ? "" : bare.substring(open + 1, close).strip();
        if (inside.isEmpty()) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (String part : splitDepthAware(inside)) {
            String type = paramType(part);
            if (!type.isEmpty()) {
                types.add(type);
            }
        }
        return List.copyOf(types);
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

    /** Splits on commas that sit outside any {@code ()}, {@code <>} or {@code []} nesting. */
    private static List<String> splitDepthAware(String text) {
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

    /** One {@code (final String[] names)}-style parameter reduced to its bare type. */
    private static String paramType(String rawParam) {
        String text = JavaOutline.stripAnnotations(rawParam.strip());
        if (text.isEmpty()) {
            return "";
        }
        boolean varargs = text.contains("...");
        text = text.replace("...", " ").strip();
        int space = text.lastIndexOf(' ');
        String type = space < 0 ? text : withoutModifiers(text.substring(0, space).strip());
        return type.isEmpty() ? "" : type + (varargs ? "[]" : "");
    }

    /**
     * The identifier of each enum constant named in an {@code ENUM_CONSTANTS} member's header —
     * {@code "HAVENT_STARTED(\"haven't started\"), CURRENTLY_READING(\"currently reading\")"}
     * yields {@code ["HAVENT_STARTED", "CURRENTLY_READING"]}. Constructor arguments and a constant
     * body ({@code FOO { ... }}) are dropped, not walked into.
     */
    static List<String> enumConstantNames(String header) {
        List<String> names = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= header.length(); i++) {
            char c = i < header.length() ? header.charAt(i) : ',';
            if (i < header.length()) {
                if (c == '(' || c == '{' || c == '[') {
                    depth++;
                } else if (c == ')' || c == '}' || c == ']') {
                    depth--;
                }
            }
            if (depth == 0 && (i == header.length() || c == ',')) {
                String piece = header.substring(start, i).strip();
                start = i + 1;
                if (!piece.isEmpty()) {
                    int cut = piece.length();
                    int paren = piece.indexOf('(');
                    if (paren >= 0) {
                        cut = Math.min(cut, paren);
                    }
                    int brace = piece.indexOf('{');
                    if (brace >= 0) {
                        cut = Math.min(cut, brace);
                    }
                    String name = piece.substring(0, cut).strip();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    private static final Set<String> MODIFIERS = Set.of("public", "protected", "private", "static",
        "final", "abstract", "synchronized", "native", "transient", "volatile", "default",
        "strictfp", "sealed", "non-sealed");

    private static String withoutModifiers(String text) {
        String rest = text.strip();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String modifier : MODIFIERS) {
                if (rest.equals(modifier)) {
                    return "";
                }
                if (rest.startsWith(modifier + " ")) {
                    rest = rest.substring(modifier.length()).strip();
                    changed = true;
                }
            }
        }
        return rest;
    }

    /** The components of {@code record Book(String title, int rating)}; empty for anything else. */
    private static List<String> recordComponents(String header) {
        String bare = JavaOutline.stripAnnotations(JavaOutline.collapse(header));
        if (!bare.toLowerCase(Locale.ROOT).contains("record ")) {
            return List.of();
        }
        int open = bare.indexOf('(');
        int close = bare.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return List.of();
        }
        List<String> components = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = open + 1; i < close; i++) {
            char c = bare.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                components.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            components.add(current.toString().strip());
        }
        components.removeIf(String::isBlank);
        return components;
    }

    /** Package-visible so {@link ContractDelivery} reads a promised member's name the same way. */
    static String lastToken(String declaration) {
        String text = declaration.strip();
        int space = text.lastIndexOf(' ');
        return space < 0 ? text : text.substring(space + 1).strip();
    }

    private static String beforeLastToken(String declaration) {
        String text = declaration.strip();
        int space = text.lastIndexOf(' ');
        return space < 0 ? "" : withoutModifiers(text.substring(0, space).strip());
    }
}
