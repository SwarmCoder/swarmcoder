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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Did this tree deliver the contract types it was told to deliver, with the members the acceptance
 * tests were written against?
 *
 * <p><b>Why this is checked mechanically and not by the judge.</b> An enabler task claims no
 * acceptance test — that is what makes it an enabler — so nothing about it is verified by running
 * anything. On run 13 that was the hole the whole failure fell through: the waves that were meant
 * to deliver the shared types delivered a differently-shaped set of them, verification had nothing
 * to say about it, the judge had no way to know what had been agreed, and the gap only became
 * visible three waves later as a test that could not compile. A contract is a written promise with
 * a name and a list of members; comparing it against the tree costs a file read and no model call.
 *
 * <p><b>It fails only on positive evidence.</b> A contract naming no type checks nothing. A type
 * that IS declared but whose members could not be read out of the file — an unparsable source, a
 * language this does not read — yields no complaint about members, because "we could not tell"
 * must never become "you did not deliver it". The one thing it will say, and say plainly, is that
 * a named type is nowhere in the tree at all.
 *
 * <p><b>A member is missing only when every place it could come from has been read</b> (audit of
 * 2026-10-02, after runs 71 and 72). Java gives a type members nobody writes: a class with no
 * constructor has the default one, a record has its canonical constructor and accessors, an enum
 * has {@code values()} and {@code valueOf}, every type has {@code Object}'s methods, and a type has
 * everything its supertypes declare. So a promised member the type's own source does not declare is
 * looked for, in this order, among those implicit members, in the supertypes this tree declares,
 * in the JDK supertypes (by reflection), and in the class the build compiled (an annotation
 * processor may have completed it). It is reported missing only if none has it AND every supertype
 * could be read; a type that extends something outside this tree and the JDK may inherit the
 * member, so it is logged and skipped. A nested type is found by its dotted or its binary name.
 */
public final class ContractDelivery {

    private static final Logger log = LoggerFactory.getLogger(ContractDelivery.class);

    private ContractDelivery() {}

    /**
     * What is wrong with one contract's delivery.
     *
     * @param contract      the contract as the task was given it
     * @param missingType   true when no type of that fully-qualified name exists in the tree
     * @param missingMembers the members the contract named and the type does not declare, or
     *                   declares without an annotation the contract named (the latter are also in
     *                   {@code unannotated}), as the contract wrote them
     * @param unannotated the promised members that exist but lack a promised annotation
     * @param existedBefore true when the type was already in the project before this task began, so
     *                   the task was asked to CHANGE it and not to create it (harness run 78)
     */
    public record Shortfall(ApiContract contract, boolean missingType, List<String> missingMembers,
                            List<Unannotated> unannotated, boolean existedBefore) {

        public Shortfall {
            missingMembers = missingMembers == null ? List.of() : List.copyOf(missingMembers);
            unannotated = unannotated == null ? List.of() : List.copyOf(unannotated);
        }

        public Shortfall(ApiContract contract, boolean missingType, List<String> missingMembers,
                         List<Unannotated> unannotated) {
            this(contract, missingType, missingMembers, unannotated, false);
        }

        public Shortfall(ApiContract contract, boolean missingType, List<String> missingMembers) {
            this(contract, missingType, missingMembers, List.of(), false);
        }

        /** The same shortfall, said about a type the project already had. */
        public Shortfall onAnExistingType() {
            return new Shortfall(contract, missingType, missingMembers, unannotated, true);
        }

        /** The sentence a person and a judge both read: what was promised and what arrived. */
        public String render() {
            if (missingType) {
                return "the contract `" + contract.typeName() + "` was not delivered: no type of "
                    + "that name exists anywhere in this candidate's tree. The task was told to "
                    + "create it as " + contract.describe() + ".";
            }
            java.util.Set<String> annotated = new java.util.HashSet<>();
            unannotated.forEach(u -> annotated.add(u.member()));
            List<String> absent = missingMembers.stream().filter(m -> !annotated.contains(m)).toList();
            List<String> sentences = new ArrayList<>();
            if (!absent.isEmpty()) {
                sentences.add("the contract `" + contract.typeName() + "` was delivered without "
                    + (absent.size() == 1 ? "the member " : "the members ")
                    + String.join(", ", absent));
            }
            for (Unannotated u : unannotated) {
                if (u.onType()) {
                    sentences.add("the contract `" + contract.typeName() + "`: the type does not "
                        + "carry " + String.join(", ", u.annotations().stream()
                            .map(a -> "@" + a).toList()));
                    continue;
                }
                sentences.add("the contract `" + contract.typeName() + "` member `" + u.member()
                    + "` is declared but does not carry the annotation "
                    + String.join(", ", u.annotations().stream().map(a -> "@" + a).toList()));
            }
            if (existedBefore) {
                return String.join("; ", sentences) + ". The type already existed in the project "
                    + "before this task began, so it was not asked to create it; the design says "
                    + "it must look like " + contract.describe() + ". Change the existing "
                    + "declaration so that it has what is named above, because the acceptance "
                    + "tests of a later task are written against exactly that.";
            }
            return String.join("; ", sentences) + ". The task was told to create it as "
                + contract.describe() + ", and the acceptance tests of a later task are written "
                + "against exactly that.";
        }
    }

    /**
     * A promised member that exists in the tree but without an annotation the contract named.
     *
     * @param member      the member as the contract wrote it
     * @param annotations the annotation names that are missing, as the contract wrote them
     */
    public record Unannotated(String member, List<String> annotations, boolean onType) {
        public Unannotated {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }

        public Unannotated(String member, List<String> annotations) {
            this(member, annotations, false);
        }
    }

    /**
     * Every contract in {@code contracts} that {@code tree} does not deliver as promised.
     *
     * @param tree      the workspace to look in — a candidate's worktree, a wave's progress tree
     * @param contracts the contracts the task was told to deliver; contracts naming no type are
     *                  skipped, and an empty list yields an empty result
     */
    public static List<Shortfall> shortfalls(Path tree, List<ApiContract> contracts) {
        return shortfalls(tree, contracts, false);
    }

    /**
     * What a plan may still have to add to this tree: every shortfall, plus every promised member
     * that could not be established either way (the type extends something outside this tree and
     * the JDK, which may or may not declare it). {@link #shortfalls} leaves those out because a
     * candidate is never failed on them; a reader asking "is this compile error something a task
     * is about to cure?" has to count them in, or a healthy red state reads as a broken test.
     */
    public static List<Shortfall> notYetThere(Path tree, List<ApiContract> contracts) {
        return shortfalls(tree, contracts, true);
    }

    private static List<Shortfall> shortfalls(Path tree, List<ApiContract> contracts,
                                              boolean unestablishedCounts) {
        if (tree == null || contracts == null || contracts.isEmpty()) {
            return List.of();
        }
        List<ApiContract> named = contracts.stream()
            .filter(c -> c != null && c.namesAType()).toList();
        if (named.isEmpty()) {
            return List.of();
        }
        ProjectTypes types = ProjectTypes.of(tree);
        // What the build produced is read only when the hand-written sources leave a question
        // open (a type nobody wrote, harness run 72; a member the source lacks), so a tree with
        // nothing missing never pays for the walk.
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (ApiContract contract : named) {
            names.add(normalized(contract.typeName()));
        }
        BuiltTypes[] read = new BuiltTypes[1];
        java.util.function.Supplier<BuiltTypes> built = () -> {
            if (read[0] == null) {
                read[0] = BuiltTypes.of(tree, names);
            }
            return read[0];
        };
        return shortfalls(types, built, named, unestablishedCounts);
    }

    /**
     * Whether the build output under {@code tree} holds this type: a generated source, or a
     * compiled class. For a check that would otherwise call a type "nowhere" on the strength of the
     * hand-written sources alone.
     */
    public static boolean builtByTheBuild(Path tree, String fullName) {
        if (tree == null || fullName == null || fullName.isBlank()) {
            return false;
        }
        String name = normalized(fullName);
        return BuiltTypes.of(tree, java.util.Set.of(name)).has(name);
    }

    /** The same check against an index somebody has already built. */
    public static List<Shortfall> shortfalls(ProjectTypes types, List<ApiContract> contracts) {
        return shortfalls(types, () -> null, contracts, false);
    }

    private static String normalized(String typeName) {
        return typeName.replace('$', '.').strip();
    }

    private static List<Shortfall> shortfalls(ProjectTypes types,
                                              java.util.function.Supplier<BuiltTypes> builtTypes,
                                              List<ApiContract> contracts,
                                              boolean unestablishedCounts) {
        List<Shortfall> shortfalls = new ArrayList<>();
        for (ApiContract contract : contracts) {
            if (contract == null || !contract.namesAType()) {
                continue;
            }
            String fullName = normalized(contract.typeName());
            List<JavaSourceFacts.Declared> declared;
            boolean handWritten = types.declares(fullName);
            if (handWritten) {
                declared = types.membersOf(fullName);
            } else {
                BuiltTypes built = builtTypes.get();
                if (built == null || !built.has(fullName)) {
                    shortfalls.add(new Shortfall(contract, true, List.of()));
                    continue;
                }
                log.info("contract {} is not hand-written but the build produced it; comparing "
                    + "against the build output", fullName);
                declared = built.membersOf(fullName);
                if (declared.isEmpty() && !contract.members().isEmpty()) {
                    log.info("contract {}: built, but its members could not be read; delivered by "
                        + "existing, members not compared", fullName);
                }
            }
            if (declared.isEmpty() && !contract.members().isEmpty()
                    && (!handWritten || !types.bodyWasRead(fullName))) {
                // Declared but nothing could be read out of it (unparsable source, a scanner
                // gap): fail open. A candidate must never die because a regex could not read its
                // file; "the type is absent" is the only thing this reports without evidence of
                // members. A type that WAS read and simply declares nothing goes on, because its
                // members may all be implicit or inherited.
                log.debug("no members read for {} (declared but unparsed, or empty); skipping "
                    + "its {} promised member(s) rather than failing on a scanner gap",
                    fullName, contract.members().size());
                continue;
            }
            List<String> missing = new ArrayList<>();
            List<Unannotated> unannotated = new ArrayList<>();
            for (String member : contract.members()) {
                if (member == null || member.isBlank()) {
                    continue;
                }
                String simpleName = fullName.substring(fullName.lastIndexOf('.') + 1);
                List<String> onType = ContractMember.typeAnnotations(member, simpleName);
                if (!onType.isEmpty()) {
                    // Harness run 77: "@DataModel LogbookSort;" is the TYPE's annotation, not a
                    // member; it is met when the type declaration (source or build) carries it.
                    List<String> carried = new ArrayList<>(handWritten
                        ? types.typeAnnotationsOf(fullName) : List.of());
                    List<String> lacking = lackingOn(onType, carried);
                    if (!lacking.isEmpty()) {
                        BuiltTypes builtHere = builtTypes.get();
                        if (builtHere != null) {
                            carried.addAll(builtHere.typeAnnotationsOf(fullName));
                            lacking = lackingOn(onType, carried);
                        }
                    }
                    if (!lacking.isEmpty()) {
                        missing.add(member.strip());
                        unannotated.add(new Unannotated(member.strip(), lacking, true));
                    }
                    continue;
                }
                Optional<ContractMember> parsed = ContractMember.parse(member);
                if (parsed.isEmpty()) {
                    // Not a declaration (harness run 71: a sentence): nothing to compare, so it is
                    // skipped, never failed. DESIGN_REVIEW sends such a member back to the architect.
                    log.info("contract {}: the member \"{}\" is not a declaration, so it cannot "
                        + "be checked; skipped", fullName, member.strip());
                    continue;
                }
                List<String> lacking = new ArrayList<>();
                if (satisfied(parsed.get(), declared, lacking)) {
                    if (!lacking.isEmpty()) {
                        missing.add(member.strip());
                        unannotated.add(new Unannotated(member.strip(), lacking));
                    }
                    continue;
                }
                boolean[] unestablished = new boolean[1];
                String elsewhere = handWritten
                    ? foundElsewhere(parsed.get(), fullName, types, builtTypes, unestablished)
                    : builtElsewhere(parsed.get(), fullName, builtTypes.get(), unestablished);
                if (elsewhere == null || (unestablishedCounts && unestablished[0])) {
                    missing.add(member.strip());
                } else {
                    log.info("contract {}: the member \"{}\" is not declared in the type's own "
                        + "source; {}", fullName, member.strip(), elsewhere);
                }
            }
            if (!missing.isEmpty()) {
                shortfalls.add(new Shortfall(contract, false, List.copyOf(missing),
                    List.copyOf(unannotated)));
            }
        }
        return List.copyOf(shortfalls);
    }

    private static List<String> lackingOn(List<String> wanted, List<String> carried) {
        return wanted.stream().filter(a -> carried.stream().noneMatch(
            c -> ContractMember.simpleName(c).equals(ContractMember.simpleName(a)))).toList();
    }

    /** Every shortfall in one sentence per contract, or "" when there is nothing to report. */
    public static String describe(List<Shortfall> shortfalls) {
        if (shortfalls == null || shortfalls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Shortfall shortfall : shortfalls) {
            sb.append(sb.isEmpty() ? "" : "\n  - ").append(shortfall.render());
        }
        return sb.toString();
    }

    // --- members a type has without declaring them ----------------------------------------------

    /**
     * Where else a promised member of a hand-written type comes from, in words for the log, or
     * null when it has been established that the type does not have it.
     */
    private static String foundElsewhere(ContractMember promised, String fullName,
                                         ProjectTypes types,
                                         java.util.function.Supplier<BuiltTypes> builtTypes,
                                         boolean[] unestablished) {
        String simple = fullName.substring(fullName.lastIndexOf('.') + 1);
        String kind = types.kindOf(fullName);
        boolean constructor = promised.method() && promised.type().isEmpty()
            && promised.name().equals(simple);
        if (constructor) {
            if ("class".equals(kind) && promised.paramTypes().isEmpty()
                    && !types.declaresConstructor(fullName)) {
                return "a class that declares no constructor has the default one";
            }
        } else {
            if ("enum".equals(kind) && enumImplicit(promised)) {
                return "every enum has it";
            }
            List<String> implicit = new ArrayList<>();
            if ("enum".equals(kind)) {
                implicit.add("java.lang.Enum");
            } else if ("record".equals(kind)) {
                implicit.add("java.lang.Record");
            } else if ("annotation".equals(kind)) {
                implicit.add("java.lang.annotation.Annotation");
            }
            implicit.add("java.lang.Object");
            for (String jdk : implicit) {
                if (Boolean.TRUE.equals(jdkHas(jdk, promised))) {
                    return "inherited from " + jdk;
                }
            }
            boolean[] unreadable = new boolean[1];
            String from = inherited(promised, fullName, types, new java.util.HashSet<>(), unreadable);
            if (from != null) {
                return "inherited from " + from;
            }
            if (unreadable[0]) {
                BuiltTypes compiled = builtTypes.get();
                if (compiled != null && satisfied(promised, compiled.classMembersOf(fullName),
                        new ArrayList<>())) {
                    return "the compiled class has it (added at build time)";
                }
                unestablished[0] = true;
                return "it extends a type outside this tree and the JDK, which may declare it; "
                    + "not established either way, so not failed";
            }
        }
        BuiltTypes built = builtTypes.get();
        if (built != null && satisfied(promised, built.classMembersOf(fullName), new ArrayList<>())) {
            return "the compiled class has it (added at build time)";
        }
        return null;
    }

    /** The same question for a type only the build produced. */
    private static String builtElsewhere(ContractMember promised, String fullName, BuiltTypes built,
                                         boolean[] unestablished) {
        if (built == null) {
            return null;
        }
        if (Boolean.TRUE.equals(jdkHas("java.lang.Object", promised))) {
            return "inherited from java.lang.Object";
        }
        if (!built.mayInherit(fullName)) {
            return null;
        }
        unestablished[0] = true;
        return "the built type has a supertype that may declare it; not established either way, "
            + "so not failed";
    }

    /** {@code values()} and {@code valueOf(String)}, which the compiler adds to every enum. */
    private static boolean enumImplicit(ContractMember promised) {
        if (!promised.method()) {
            return false;
        }
        return (promised.name().equals("values") && promised.paramTypes().isEmpty())
            || (promised.name().equals("valueOf") && promised.paramTypes().size() == 1);
    }

    /**
     * The supertype that declares the member, searching the supertypes this tree declares and the
     * JDK's; null when none found. {@code unreadable[0]} is set when a supertype could be neither
     * found in this tree nor loaded from the JDK, so the search proves nothing.
     */
    private static String inherited(ContractMember promised, String fullName, ProjectTypes types,
                                    java.util.Set<String> seen, boolean[] unreadable) {
        if (!seen.add(fullName) || seen.size() > 64) {
            return null;
        }
        for (ProjectTypes.Supertype supertype : types.supertypesOf(fullName)) {
            if (supertype.fullName() == null) {
                unreadable[0] = true;
                continue;
            }
            if (supertype.ours()) {
                if (satisfied(promised, types.membersOf(supertype.fullName()), new ArrayList<>())) {
                    return supertype.fullName();
                }
                String above = inherited(promised, supertype.fullName(), types, seen, unreadable);
                if (above != null) {
                    return above;
                }
                continue;
            }
            Boolean has = jdkHas(supertype.fullName(), promised);
            if (has == null) {
                unreadable[0] = true;
            } else if (has) {
                return supertype.fullName();
            }
        }
        return null;
    }

    /**
     * Whether a JDK type has an accessible member of the promised name (and, for a method, the
     * promised number of parameters), its own or inherited; null when the JDK has no such type.
     */
    private static Boolean jdkHas(String jdkFullName, ContractMember promised) {
        Class<?> type = null;
        String binary = jdkFullName;
        for (int tries = 0; type == null && tries < 4; tries++) {
            try {
                type = Class.forName(binary, false, ClassLoader.getPlatformClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                int dot = binary.lastIndexOf('.');
                if (dot < 0) {
                    break;
                }
                binary = binary.substring(0, dot) + '$' + binary.substring(dot + 1); // a nested type
            }
        }
        if (type == null) {
            return null;
        }
        try {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                if (hasOwn(c, promised)) {
                    return true;
                }
            }
            for (java.lang.reflect.Method method : type.getMethods()) {
                if (promised.method() ? method.getName().equals(promised.name())
                        && method.getParameterCount() == promised.paramTypes().size()
                        : getterOf(promised.name(), method.getName())) {
                    return true;
                }
            }
            return false;
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static boolean hasOwn(Class<?> c, ContractMember promised) {
        if (promised.method()) {
            for (java.lang.reflect.Method method : c.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPrivate(method.getModifiers())
                        && method.getName().equals(promised.name())
                        && method.getParameterCount() == promised.paramTypes().size()) {
                    return true;
                }
            }
            return false;
        }
        for (java.lang.reflect.Field field : c.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isPrivate(field.getModifiers())
                    && field.getName().equals(promised.name())) {
                return true;
            }
        }
        return false;
    }

    private static boolean getterOf(String field, String method) {
        if (field.isEmpty()) {
            return false;
        }
        String capitalised = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        return method.equals(field) || method.equals("get" + capitalised)
            || method.equals("is" + capitalised);
    }

    // --- matching one promised member against what was declared ---------------------------------

    /**
     * Generous on purpose. A contract member is a promise about what a test can reach, so a field
     * reached through a getter counts, a record component counts, an enum constant counts (as a
     * field of the enum's own type), and a declared type is compared only on its last segment
     * ({@code java.util.List<Book>} and {@code List<Book>} are the same promise). A method also
     * compares its parameter types — by simple name, in order — so two same-named methods that
     * take different arguments are not mistaken for each other; modifiers, annotations, generics,
     * a {@code throws} clause and parameter names are ignored on both sides of the comparison,
     * because {@link JavaSourceFacts} normalizes the promised text through the exact same header
     * parsing it applies to the declared one. This decides whether to kill a candidate, and it
     * must never kill one on a spelling of the same thing.
     */
    private static boolean satisfied(ContractMember promised, List<JavaSourceFacts.Declared> declared,
                                     List<String> lackingAnnotations) {
        boolean method = promised.method();
        String name = promised.name();
        String type = promised.type();
        List<String> params = promised.paramTypes();
        List<JavaSourceFacts.Declared> matches = new ArrayList<>();
        for (JavaSourceFacts.Declared member : declared) {
            if (!namesTheSameThing(name, member, method)) {
                continue;
            }
            if (method && member.method() && !sameParams(params, member.paramTypes())) {
                continue; // same name, different arguments: a different method, not this one
            }
            if (type.isEmpty() || member.type() == null || member.type().isBlank()
                    || sameType(type, member.type())) {
                // no type was promised, or none could be read — the name is enough
                matches.add(member);
            }
        }
        if (matches.isEmpty()) {
            return false;
        }
        // An annotation counts when any member that delivers the promise carries it: the field,
        // or the accessor a test reaches it through. Compared by simple name, so a promise written
        // fully qualified is met by an import and the other way round.
        for (String annotation : promised.annotations()) {
            String wanted = ContractMember.simpleName(annotation);
            boolean carried = matches.stream().anyMatch(m -> m.annotations().stream()
                .anyMatch(a -> ContractMember.simpleName(a).equals(wanted)));
            if (!carried) {
                lackingAnnotations.add(annotation);
            }
        }
        return true;
    }

    /**
     * The promised member as a bare declaration: no trailing semicolon, and for a field no
     * initializer.
     *
     * <p>Harness run 44, 2026-09-27: the architect wrote the Book contract's fields as statements —
     * {@code "public String id;"} — so the last token read as the member's name was {@code "id;"},
     * which no declared field is ever called. Both candidates and all four repair workers delivered
     * exactly {@code public String id; public String title; public String author;} and every one
     * was rejected for "delivered without the members", blocking the run. A member written with
     * its semicolon, or as a field with a default value ({@code "public int stars = 0;"}), is the
     * same promise as the bare declaration, and {@link #satisfied} must never reject a candidate on
     * a spelling of the same thing.
     */
    static String asDeclared(String promised) {
        String text = promised.strip();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        if (!text.contains("(")) {
            int assign = text.indexOf('=');
            if (assign > 0) {
                text = text.substring(0, assign).strip();
            }
        }
        return text;
    }

    private static boolean sameParams(List<String> promised, List<String> declared) {
        if (promised.size() != declared.size()) {
            return false;
        }
        for (int i = 0; i < promised.size(); i++) {
            if (declared.get(i).equals("?")) {
                continue; // a built generic member: its erased types say nothing about the promise
            }
            if (!sameType(promised.get(i), declared.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean namesTheSameThing(String promised, JavaSourceFacts.Declared member,
                                             boolean promisedIsMethod) {
        if (member.name().equals(promised)) {
            return true;
        }
        if (promisedIsMethod || !member.method()) {
            return false;
        }
        // A field promised as `int rating`, delivered as `getRating()` / `isRating()`, is delivered.
        String capitalised = Character.toUpperCase(promised.charAt(0)) + promised.substring(1);
        return member.name().equals("get" + capitalised) || member.name().equals("is" + capitalised);
    }

    private static boolean sameType(String promised, String declared) {
        return lastSegment(promised).equals(lastSegment(declared));
    }

    private static String lastSegment(String type) {
        String text = type.strip().replaceAll("\\s+", "");
        int generic = text.indexOf('<');
        String raw = generic < 0 ? text : text.substring(0, generic);
        int dot = raw.lastIndexOf('.');
        String simple = dot < 0 ? raw : raw.substring(dot + 1);
        return simple.toLowerCase(Locale.ROOT);
    }
}
