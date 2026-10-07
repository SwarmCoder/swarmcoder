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
import com.swarmcoder.knowledge.JavaSourceFacts;
import com.swarmcoder.knowledge.ProjectTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The types the project's own checkout already declares, as the design roles are shown them: fully
 * qualified name, kind, module and public members — so a story is built ON the code that is there.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 68, 2026-10-02: the second story of a project, on a repository that already held the
 * first story's service interface, data types, store root and screens. The run was a new-build run,
 * so the only channel that shows the architect existing code (the change neighbourhood, for bugfix
 * and enhancement runs) was off, and the standing reference names files, never types. The design
 * came back with a second service interface and second data types of the same simple names in a
 * new package; design review passed it, because a new package in the project's own namespace is
 * something a task can write; the test author then guessed a package for the existing store root,
 * and the run parked.
 *
 * <h2>What this gives</h2>
 *
 * <ul>
 *   <li>{@link #inventory}: the bounded list for a prompt, most relevant first;</li>
 *   <li>{@link #duplicateObjections}: the DESIGN_REVIEW fact check — a contract whose simple name
 *       is an existing type's, in another package, is a duplicate and goes back;</li>
 *   <li>{@link #named}: the existing types of one simple name, for a correction that says where the
 *       real one is.</li>
 * </ul>
 *
 * <p>Only top-level types of the project's main sources count: a type is top-level when its file
 * carries its name, and test sources are somebody's tests, not what a story builds on. Read from
 * source text, no compiler; an unreadable tree is an empty inventory and every prompt and check is
 * then exactly what it was. Project-agnostic: every name comes from the checkout.
 */
final class ExistingProjectTypes {

    /** The inventory's size at the baseline room; callers scale it by the reader's own room. */
    static final int INVENTORY_CHARS = 6_000;

    /** How many of one type's members the inventory and an objection show. */
    static final int MAX_MEMBERS_SHOWN = 12;

    /** How many types past the size limit are still named, without members. */
    static final int MAX_NAMES_ONLY = 60;

    /** The most test source read to learn which types earlier stories' tests use. */
    private static final int MAX_TEST_FILES = 200;

    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern CAMEL_PART = Pattern.compile("[A-Z][a-z0-9]+|[A-Z]+(?![a-z])");

    /**
     * One existing top-level type.
     *
     * @param module  the build module it is in, "" for a single-module project
     * @param members its public surface as declared, modifiers dropped
     */
    record Existing(String fullName, String simpleName, String kind, String module,
                    List<String> members) {

        String packageName() {
            int cut = fullName.length() - simpleName.length() - 1;
            return cut <= 0 ? "" : fullName.substring(0, cut);
        }

        /** {@code com.x.Qso (record, module app-shared): String call(); ...} */
        String line(int maxMembers) {
            StringBuilder sb = new StringBuilder(fullName).append(" (").append(kind);
            if (!module.isEmpty()) {
                sb.append(", module ").append(module);
            }
            sb.append(')');
            if (members.isEmpty()) {
                return sb.toString();
            }
            sb.append(": ").append(members.stream().limit(maxMembers)
                .collect(Collectors.joining("; ")));
            if (members.size() > maxMembers) {
                sb.append("; and ").append(members.size() - maxMembers).append(" more");
            }
            return sb.toString();
        }
    }

    static final ExistingProjectTypes NONE = new ExistingProjectTypes(List.of(), Set.of());

    private final List<Existing> types;
    private final Set<String> namedInTests;

    private ExistingProjectTypes(List<Existing> types, Set<String> namedInTests) {
        this.types = types;
        this.namedInTests = namedInTests;
    }

    /** The existing types under {@code root}; {@link #NONE} when there is no readable tree. */
    static ExistingProjectTypes of(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return NONE;
        }
        try {
            ProjectTypes index = ProjectTypes.of(root);
            List<Existing> found = new ArrayList<>();
            Set<String> inTests = new HashSet<>();
            Set<Path> testFiles = new HashSet<>();
            for (String fullName : index.fullNames().stream().sorted().toList()) {
                Path file = index.fileOf(fullName);
                if (file == null) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (isTestSource(relative)) {
                    if (testFiles.size() < MAX_TEST_FILES && testFiles.add(file)) {
                        collectWords(read(file), inTests);
                    }
                    continue;
                }
                String simple = fullName.substring(fullName.lastIndexOf('.') + 1);
                if (!file.getFileName().toString().equals(simple + ".java")) {
                    continue; // nested, or a second type in somebody else's file
                }
                String source = read(file);
                List<String> members = new ArrayList<>();
                for (String component : recordComponents(source, simple)) {
                    members.add(component);
                }
                for (JavaSourceFacts.Exposed member : index.exposedMembers(fullName)) {
                    members.add(withoutModifiers(member.header()));
                }
                if (members.isEmpty()) {
                    // An enum's constants, a class of package-private fields: the names at least.
                    index.membersOf(fullName).stream().map(JavaSourceFacts.Declared::name)
                        .distinct().forEach(members::add);
                }
                found.add(new Existing(fullName, simple, kindOf(source, simple),
                    moduleOf(relative), List.copyOf(members)));
            }
            return found.isEmpty() ? NONE : new ExistingProjectTypes(List.copyOf(found), inTests);
        } catch (RuntimeException e) {
            return NONE; // a tree that could not be read establishes nothing
        }
    }

    boolean isEmpty() {
        return types.isEmpty();
    }

    List<Existing> all() {
        return types;
    }

    /** The existing top-level types with this simple name, whatever their package. */
    List<Existing> named(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return List.of();
        }
        String simple = simpleName.strip();
        return types.stream().filter(t -> t.simpleName().equals(simple)).toList();
    }

    /** The existing type with this fully-qualified name, or null. */
    Existing declared(String fullName) {
        if (fullName == null) {
            return null;
        }
        String name = fullName.replace('$', '.').strip();
        return types.stream().filter(t -> t.fullName().equals(name)).findFirst().orElse(null);
    }

    /**
     * The inventory lines for a prompt, most relevant first, within {@code maxChars}: a type the
     * {@code about} text names outright, then one an earlier story's tests use, then one whose
     * name shares a word with the text, then the rest. Types that no longer fit are still named,
     * without members, because a name is what stops a duplicate. "" when there are no types.
     *
     * @param about the story, its requirements and checks (or a task and its checks), as text
     */
    String inventory(String about, int maxChars) {
        if (types.isEmpty() || maxChars <= 0) {
            return "";
        }
        Set<String> words = new HashSet<>();
        Set<String> lowered = new HashSet<>();
        collectWords(about, words);
        for (String word : words) {
            lowered.add(word.toLowerCase(Locale.ROOT));
            Matcher part = CAMEL_PART.matcher(word);
            while (part.find()) {
                lowered.add(part.group().toLowerCase(Locale.ROOT));
            }
        }
        Map<Existing, Integer> score = new LinkedHashMap<>();
        for (Existing type : types) {
            int points = 0;
            if (words.contains(type.simpleName())) {
                points += 8;
            }
            if (namedInTests.contains(type.simpleName())) {
                points += 4;
            }
            int shared = 0;
            Matcher part = CAMEL_PART.matcher(type.simpleName());
            while (part.find()) {
                String piece = part.group().toLowerCase(Locale.ROOT);
                if (piece.length() >= 4 && (lowered.contains(piece) || lowered.contains(piece + "s")
                        || (piece.endsWith("s") && lowered.contains(
                            piece.substring(0, piece.length() - 1))))) {
                    shared++;
                }
            }
            points += Math.min(shared, 2) * 2;
            score.put(type, points);
        }
        List<Existing> ranked = new ArrayList<>(types);
        ranked.sort(Comparator.comparing((Existing t) -> -score.get(t))
            .thenComparing(Existing::fullName));
        StringBuilder sb = new StringBuilder();
        List<String> namesOnly = new ArrayList<>();
        for (Existing type : ranked) {
            String line = "  - " + type.line(MAX_MEMBERS_SHOWN) + "\n";
            if (namesOnly.isEmpty() && sb.length() + line.length() <= maxChars) {
                sb.append(line);
            } else {
                namesOnly.add(type.fullName());
            }
        }
        if (!namesOnly.isEmpty()) {
            sb.append("  Also exist (members not shown): ")
                .append(namesOnly.stream().limit(MAX_NAMES_ONLY).collect(Collectors.joining(", ")));
            if (namesOnly.size() > MAX_NAMES_ONLY) {
                sb.append(", and ").append(namesOnly.size() - MAX_NAMES_ONLY).append(" more");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * The paragraph the architect reads, for a design and for every revision of it; "" when the
     * project has no types yet, so a build into an empty repository sends what it always did.
     */
    String architectBrief(String about, int maxChars) {
        String inventory = inventory(about, maxChars);
        if (inventory.isEmpty()) {
            return "";
        }
        return "\n\nTYPES THIS PROJECT ALREADY HAS (read from the repository; this story is built "
            + "ON this code, it does not start again):\n" + inventory + ARCHITECT_RULES
                .replace("@LISTED@", "exactly as listed above")
                .replace("@NOT_LISTED@", "that is not in this list");
    }

    /**
     * The same paragraph without the inventory, for a role that looks types up itself (section
     * 54, CLAUDE.md section 1): the rules about existing code stay, the list of types does not,
     * because the role has the project map and the tree queries. "" when the project has no
     * types yet.
     */
    String architectRules() {
        if (types.isEmpty()) {
            return "";
        }
        return "\n\nTHIS PROJECT ALREADY HAS CODE (this story is built ON it, it does not start "
            + "again; the project map and the tree queries show which types exist):\n"
            + ARCHITECT_RULES
                .replace("@LISTED@", "exactly as the project's types are named")
                .replace("@NOT_LISTED@", "that the project does not have");
    }

    private static final String ARCHITECT_RULES = "Reuse or extend an existing type under its "
        + "EXISTING name and package. Never create a second type with the same simple name in "
        + "another package, and never move one. A contract for an existing type gives its "
        + "fully-qualified name @LISTED@ and lists only the members this story ADDS to it "
        + "(members it already has that a test will call may be repeated, exactly as they are). "
        + "A contract for a type @NOT_LISTED@ is a NEW type; put it in the package where the "
        + "project keeps types of its kind. NEVER DESIGN THE REMOVAL of a public type, method or "
        + "field that already exists unless a criterion says in so many words that something "
        + "is to be removed: a criterion that something is NOT offered is met by not "
        + "offering it, and a change that deletes existing public members without such a "
        + "criterion fails verification.";

    /** The paragraph the test author reads; "" when the project has no types yet. */
    String testAuthorBrief(String about, int maxChars) {
        String inventory = inventory(about, maxChars);
        if (inventory.isEmpty()) {
            return "";
        }
        return "\nTypes this project ALREADY has (they exist now; import them from exactly these "
            + "packages and call exactly these members — do not guess a package for one):\n"
            + inventory;
    }

    /**
     * DESIGN_REVIEW, as a fact: every contract that would create a second type of an existing
     * type's simple name in a different package. A contract naming an existing type in its real
     * package is not one — that means "add these members", and the plan check owns it.
     */
    List<String> duplicateObjections(DesignDocument design) {
        List<String> objections = new ArrayList<>();
        if (types.isEmpty() || design == null || design.contracts() == null) {
            return objections;
        }
        for (ApiContract contract : design.contracts()) {
            if (contract == null || !contract.namesAType()
                    || AcceptanceTestContracts.isAcceptanceTestClass(contract, null)) {
                continue;
            }
            String name = contract.typeName().replace('$', '.').strip();
            if (!name.contains(".") || declared(name) != null) {
                continue;
            }
            String simple = name.substring(name.lastIndexOf('.') + 1);
            List<Existing> same = named(simple);
            if (same.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder("the contract ").append(name)
                .append(" duplicates a type this project already has: ");
            for (int i = 0; i < same.size(); i++) {
                Existing existing = same.get(i);
                sb.append(i == 0 ? "" : "; and ").append(existing.fullName())
                    .append(" already exists")
                    .append(existing.module().isEmpty() ? "" : " in " + existing.module())
                    .append(" (").append(existing.kind());
                if (!existing.members().isEmpty()) {
                    sb.append(" with ").append(existing.members().stream()
                        .limit(MAX_MEMBERS_SHOWN).collect(Collectors.joining("; ")));
                    if (existing.members().size() > MAX_MEMBERS_SHOWN) {
                        sb.append("; and ").append(existing.members().size() - MAX_MEMBERS_SHOWN)
                            .append(" more");
                    }
                }
                sb.append(')');
            }
            String real = same.get(0).fullName();
            sb.append(". Extend it there, do not create ").append(name).append(": name the "
                + "contract ").append(same.size() == 1 ? real : "by the existing type it extends")
                .append(" and list only the members this story adds to it, and write every other "
                    + "contract's members against ").append(same.size() == 1 ? real : "that name")
                .append(". If this story truly needs a different type, give it a different "
                    + "name.");
            objections.add(sb.toString());
        }
        return objections;
    }

    // --- reading ---------------------------------------------------------------------------

    private static boolean isTestSource(String relative) {
        return ("/" + relative).contains("/src/test/") || relative.startsWith("test/");
    }

    /** The path before the first {@code src} segment: the build module, "" at the root. */
    private static String moduleOf(String relative) {
        int src = ("/" + relative).indexOf("/src/");
        return src <= 0 ? "" : relative.substring(0, src - 1);
    }

    private static String kindOf(String source, String simple) {
        Matcher m = Pattern.compile("(@interface|\\binterface|\\benum|\\brecord|\\bclass)\\s+"
            + Pattern.quote(simple) + "\\b").matcher(source);
        if (!m.find()) {
            return "type";
        }
        String kind = m.group(1);
        return "@interface".equals(kind) ? "annotation" : kind;
    }

    /** {@code record Qso(String call, int band)} gives {@code String call()}, {@code int band()}. */
    private static List<String> recordComponents(String source, String simple) {
        Matcher m = Pattern.compile("\\brecord\\s+" + Pattern.quote(simple)
            + "\\s*(?:<[^>{(]*>)?\\s*\\(").matcher(source);
        if (!m.find()) {
            return List.of();
        }
        int depth = 1;
        int angle = 0;
        int start = m.end();
        List<String> components = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = start; i < source.length() && depth > 0; i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    break;
                }
            } else if (c == '<') {
                angle++;
            } else if (c == '>') {
                angle--;
            }
            if (c == ',' && depth == 1 && angle == 0) {
                components.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        components.add(current.toString());
        List<String> out = new ArrayList<>();
        for (String component : components) {
            String text = component.replaceAll("@\\w+(\\([^)]*\\))?", " ")
                .replaceAll("\\s+", " ").strip();
            if (!text.isEmpty()) {
                out.add(text + "()");
            }
        }
        return out;
    }

    private static String withoutModifiers(String header) {
        return header.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "")
            .replaceAll("\\b(public|protected|static|final|abstract|default|synchronized)\\s+", "")
            .replaceAll("\\s+", " ").strip();
    }

    private static void collectWords(String text, Set<String> into) {
        if (text == null) {
            return;
        }
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            into.add(m.group());
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
