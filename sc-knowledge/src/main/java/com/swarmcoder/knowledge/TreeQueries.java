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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The questions about a project that are answered from its syntax tree and its object graph,
 * with no model: what a module or package contains, what a build file declares, the body of one
 * method, a type's shape, who uses something (owner's rule, CLAUDE.md section 1, 2026-10-04).
 *
 * <p>Every role that can look things up is offered these - the planning roles and the expert
 * through {@link ExpertTools}, a worker through its toolbox - so that a whole file is read only
 * for a body no query returns, and nothing is learned by {@code find}, {@code grep} and
 * {@code cat}. One class, so that every role is given the same answer.
 *
 * <p>Addresses are written the way the asker's own read tool takes them: see
 * {@link #forAWorkerOf}.
 */
public final class TreeQueries {

    /** A member whose overloads add up to more than this are listed, not all quoted. */
    static final int OVERLOADS_QUOTED_CHARS = 6_000;
    /** How many types one answer lists; the rest are counted. */
    static final int MAX_TYPES_LISTED = 150;
    /** How long a type's one-line shape may be. */
    static final int ONE_LINE_SHAPE = 170;
    private static final int MAX_REFS = 20;

    private final KnowledgeCurator curator;
    /** The label of the root a worker's plain paths are relative to; null for a role. */
    private final String ownRoot;

    public TreeQueries(KnowledgeCurator curator) {
        this(curator, null);
    }

    private TreeQueries(KnowledgeCurator curator, String ownRoot) {
        this.curator = curator;
        this.ownRoot = ownRoot;
    }

    /**
     * The same queries for a worker in a checkout of {@code project}: a file of the project is
     * addressed by its path in the repository, a reference file as {@code /reference/<root>/...},
     * which is what a worker's {@code read} takes.
     */
    public static TreeQueries forAWorkerOf(KnowledgeCurator curator, Path project) {
        String label = "";
        if (curator != null && project != null) {
            Path wanted = project.toAbsolutePath().normalize();
            for (KnowledgeCurator.Root root : curator.roots()) {
                if (root.path() != null && root.path().toAbsolutePath().normalize().equals(wanted)) {
                    label = root.label();
                }
            }
        }
        return new TreeQueries(curator, label);
    }

    private String address(String rootLabel, String file) {
        String path = file.replace('\\', '/');
        if (ownRoot == null) {
            return (rootLabel == null || rootLabel.isBlank() ? "" : rootLabel + "/") + path;
        }
        return ownRoot.equals(rootLabel) ? path : "/reference/" + rootLabel + "/" + path;
    }

    private SemanticIndex index() {
        try {
            SemanticIndex index = curator == null ? null : curator.semanticIndex();
            return index != null && index.available() ? index : null;
        } catch (Throwable unusable) {                                     // noqa
            return null;
        }
    }

    private static final String NO_TREE = "The project's syntax tree is not available here, so "
        + "this cannot be answered from it.";

    // -------------------------------------------------------------------------------------------

    /** True when the tree holds a type of this name and can list its members. */
    public boolean holds(String type) {
        SemanticIndex index = index();
        return index != null && !index.publicShape(type == null ? "" : type.strip()).isEmpty();
    }

    /** A type's members as the compiler resolved them, and where it is declared. */
    public String shapeOf(String type) {
        SemanticIndex index = index();
        if (index == null) {
            return NO_TREE;
        }
        String shape = index.publicShape(type == null ? "" : type.strip());
        return shape.isEmpty() ? "No type `" + type + "` in this project's material."
            : "```java\n" + shape + "\n```\n";
    }

    /** Where a type or {@code Type#method} is used, with file and line. */
    public String usagesOf(String what) {
        SemanticIndex index = index();
        if (index == null) {
            return NO_TREE;
        }
        List<SemanticIndex.Ref> found = index.usagesOf(what == null ? "" : what.strip());
        if (found.isEmpty()) {
            return "Nothing in this project's material uses `" + what + "`.";
        }
        StringBuilder sb = new StringBuilder("Where `" + what + "` is used (" + found.size()
            + "):\n");
        for (SemanticIndex.Ref ref : found.subList(0, Math.min(MAX_REFS, found.size()))) {
            sb.append("  ").append(address(ref.rootLabel(), ref.file())).append(':')
                .append(ref.line()).append(ref.detail() == null || ref.detail().isBlank()
                    ? "" : "  " + ref.detail()).append('\n');
        }
        if (found.size() > MAX_REFS) {
            sb.append("  ... and ").append(found.size() - MAX_REFS).append(" more\n");
        }
        return sb.toString();
    }

    /**
     * What a package, a module or a folder contains: its types by package, each on one line with
     * its kind, what it extends or implements, the head of its members, and its file and line.
     */
    public String typesIn(String where) {
        SemanticIndex index = index();
        if (index == null) {
            return NO_TREE;
        }
        List<SemanticIndex.Declared> found = index.typesIn(where);
        if (found.isEmpty()) {
            return "No types in `" + where + "` in this project's material. Give a package "
                + "(com.example.server), or a module or folder as a path.";
        }
        Map<String, List<SemanticIndex.Declared>> byPackage = new java.util.TreeMap<>();
        for (SemanticIndex.Declared type : found) {
            int cut = type.fqn().lastIndexOf('.');
            byPackage.computeIfAbsent(cut < 0 ? "(no package)" : type.fqn().substring(0, cut),
                p -> new ArrayList<>()).add(type);
        }
        StringBuilder sb = new StringBuilder("`" + where + "` declares " + found.size()
            + " type(s) in " + byPackage.size() + " package(s):\n");
        int listed = 0;
        for (Map.Entry<String, List<SemanticIndex.Declared>> entry : byPackage.entrySet()) {
            sb.append(entry.getKey()).append(" (").append(entry.getValue().size()).append(")\n");
            for (SemanticIndex.Declared type : entry.getValue()) {
                if (listed++ >= MAX_TYPES_LISTED) {
                    continue;
                }
                sb.append("  ").append(oneLine(type)).append("  ")
                    .append(address(type.rootLabel(), type.file())).append(':')
                    .append(type.line()).append('\n');
            }
        }
        if (listed > MAX_TYPES_LISTED) {
            sb.append("... ").append(listed - MAX_TYPES_LISTED)
                .append(" more are not listed: ask for one of the packages above\n");
        }
        return sb.toString();
    }

    private static String oneLine(SemanticIndex.Declared type) {
        String simple = type.fqn().substring(type.fqn().lastIndexOf('.') + 1);
        StringBuilder sb = new StringBuilder(type.kind()).append(' ').append(simple);
        if (!type.isA().isEmpty()) {
            List<String> parents = new ArrayList<>();
            type.isA().forEach(p -> parents.add(p.substring(p.lastIndexOf('.') + 1)));
            sb.append(" is-a ").append(String.join(", ", parents));
        }
        if (!type.shape().isEmpty()) {
            sb.append(" { ");
            int shown = 0;
            for (String member : type.shape()) {
                String flat = member.strip().replaceAll("\\s+", " ");
                if (sb.length() + flat.length() > ONE_LINE_SHAPE) {
                    break;
                }
                sb.append(flat).append(flat.endsWith(";") ? " " : "; ");
                shown++;
            }
            if (shown < type.shape().size()) {
                sb.append("... ").append(type.shape().size() - shown).append(" more ");
            }
            sb.append('}');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------------------------

    /**
     * The text of ONE declaration, taken out of the tree by name: {@code Type#member} for a
     * method, a constructor ({@code Type#Type}) or a field with its initialiser, {@code Type} for
     * the type itself. Overloads are all returned while they are short, and listed with their
     * lines when they are not.
     *
     * <p>A nested type is named as the language names it, {@code Outer.Inner} or
     * {@code Outer$Inner}, and a member may follow a dot as well as a {@code #} (run 86,
     * section 58: {@code TestServer.Builder}, {@code TestServer$Builder} and
     * {@code TestServer.start} were each answered "no such type ... a library jar has no source
     * here", five times, and the 19,281-character file was then read whole three times).
     */
    public String bodyOf(String what) {
        SemanticIndex index = index();
        if (index == null) {
            return NO_TREE;
        }
        String asked = what == null ? "" : what.strip();
        int hash = asked.indexOf('#');
        String typeName = hash < 0 ? asked : asked.substring(0, hash);
        String member = hash < 0 ? null : asked.substring(hash + 1).strip();
        if (typeName.isBlank()) {
            return "Give Type#member, or Type for the whole type - for example Ledger#append.";
        }
        // Outer$Inner is walked like Outer.Inner: the index places it in the outer type's file
        // under a name the source never spells.
        SemanticIndex.Ref declared = typeName.indexOf('$') >= 0 ? null
            : index.declarationOf(typeName.strip());
        // The names after the outermost type the tree knows: nested types, then maybe a member.
        List<String> inner = new ArrayList<>();
        String outer = typeName.strip().replace('$', '.');
        while (declared == null && outer.contains(".")) {
            inner.add(0, outer.substring(outer.lastIndexOf('.') + 1));
            outer = outer.substring(0, outer.lastIndexOf('.'));
            declared = index.declarationOf(outer);
        }
        if (declared == null) {
            return "No type `" + typeName + "` is declared in this project's material (a class "
                + "from a library jar has no source here).";
        }
        String address = address(declared.rootLabel(), declared.file());
        String source = curator.readFile(
            (declared.rootLabel() == null || declared.rootLabel().isBlank()
                ? "" : declared.rootLabel() + "/") + declared.file().replace('\\', '/'), 2_000_000);
        if (source.startsWith("error:")) {
            return source;
        }
        return inner.isEmpty() ? bodyIn(address, source, typeName.strip(), member)
            : nestedIn(address, source, outer, inner, member);
    }

    /**
     * The texts a type holds: every string literal in it, by the member it is written in, with
     * the annotations' arguments - and no code (section 63). What a screen shows, what a route
     * is called and what a constants class holds are literals; this answers them in a few
     * hundred characters where the type's code is thousands.
     */
    public String textsOf(String what) {
        SemanticIndex index = index();
        if (index == null) {
            return NO_TREE;
        }
        String typeName = what == null ? "" : what.strip();
        int hash = typeName.indexOf('#');
        if (hash >= 0) {
            typeName = typeName.substring(0, hash).strip();
        }
        if (typeName.isBlank()) {
            return "Give a type - for example LogbookScreen.";
        }
        SemanticIndex.Ref declared = index.declarationOf(typeName);
        if (declared == null) {
            return "No type `" + typeName + "` is declared in this project's material (a class "
                + "from a library jar has no source here).";
        }
        String address = address(declared.rootLabel(), declared.file());
        String source = curator.readFile(
            (declared.rootLabel() == null || declared.rootLabel().isBlank()
                ? "" : declared.rootLabel() + "/") + declared.file().replace('\\', '/'), 2_000_000);
        if (source.startsWith("error:")) {
            return source;
        }
        return textsIn(address, source, typeName);
    }

    /** {@link #textsOf} once the file is known. */
    static String textsIn(String address, String source, String typeName) {
        JavaOutline outline;
        try {
            outline = JavaOutline.of(source);
        } catch (Exception unreadable) {                                   // noqa
            return "`" + address + "` could not be read as Java.";
        }
        String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
        JavaOutline.Member type = null;
        for (JavaOutline.Member top : outline.types) {
            if (top.name().equals(simple)) {
                type = top;
            }
            for (JavaOutline.Member nested : top.descendants()) {
                if (type == null && nested.kind() == JavaOutline.Kind.TYPE
                        && nested.name().equals(simple)) {
                    type = nested;
                }
            }
        }
        if (type == null) {
            return "`" + address + "` does not declare `" + simple + "`.";
        }
        List<String> lines = new ArrayList<>();
        textLines(type, "", lines);
        if (lines.isEmpty()) {
            return "`" + simple + "` (" + address + ":" + type.startLine() + "-" + type.endLine()
                + ") holds no string literal. Texts it shows are named constants or come from "
                + "another type: body_of gives the member that shows them.\n";
        }
        StringBuilder sb = new StringBuilder("Texts in `" + simple + "` (" + address + ":"
            + type.startLine() + "-" + type.endLine() + "), every string literal by the member "
            + "it is written in:\n");
        lines.forEach(line -> sb.append("  ").append(line).append('\n'));
        return sb.append("A text named as a constant (Texts.SAVE) is in the type that declares "
            + "the constant: ask texts_of for that type.\n").toString();
    }

    private static void textLines(JavaOutline.Member type, String prefix, List<String> lines) {
        List<String> own = literalsIn(type.header());
        if (!own.isEmpty()) {
            lines.add((prefix.isEmpty() ? type.name() : prefix.substring(0, prefix.length() - 1))
                + " (the type's own annotations): " + String.join(", ", own));
        }
        for (JavaOutline.Member child : type.children()) {
            if (child.kind() == JavaOutline.Kind.TYPE) {
                textLines(child, prefix + child.name() + ".", lines);
                continue;
            }
            List<String> found = literalsIn(JavaOutline.withoutComments(child.text()));
            if (!found.isEmpty()) {
                lines.add(prefix + child.name() + (child.kind() == JavaOutline.Kind.METHOD
                    || child.kind() == JavaOutline.Kind.CONSTRUCTOR ? "()" : "") + " :"
                    + child.startLine() + ": " + String.join(", ", found));
            }
        }
    }

    /** The string literals of a piece of code without comments, quoted, in order, each once. */
    static List<String> literalsIn(String code) {
        Set<String> found = new LinkedHashSet<>();
        String text = code == null ? "" : code;
        final char quote = '"';
        final char tick = '\'';
        final char escape = '\\';
        final String block = "" + quote + quote + quote;
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == tick) {
                // a character literal: skipped, so that a quote inside it opens no string
                at++;
                while (at < text.length() && text.charAt(at) != tick) {
                    at += text.charAt(at) == escape ? 2 : 1;
                }
                at++;
            } else if (text.startsWith(block, at)) {
                int end = text.indexOf(block, at + 3);
                if (end < 0) {
                    break;
                }
                String inside = text.substring(at + 3, end).strip().replaceAll("\\s+", " ");
                if (!inside.isEmpty()) {
                    found.add(quote + inside + quote);
                }
                at = end + 3;
            } else if (c == quote) {
                int end = at + 1;
                while (end < text.length() && text.charAt(end) != quote) {
                    end += text.charAt(end) == escape ? 2 : 1;
                }
                if (end >= text.length()) {
                    break;
                }
                if (end > at + 1) {
                    found.add(text.substring(at, end + 1));
                }
                at = end + 1;
            } else {
                at++;
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * {@code inner} followed down from {@code outer}: each name a nested type, the last one a
     * member of the type reached when it is no nested type and no member was asked for.
     */
    static String nestedIn(String address, String source, String outer, List<String> inner,
                           String member) {
        JavaOutline outline;
        try {
            outline = JavaOutline.of(source);
        } catch (Exception unreadable) {                                   // noqa
            return "`" + address + "` could not be read as Java.";
        }
        String simple = outer.substring(outer.lastIndexOf('.') + 1);
        JavaOutline.Member type = null;
        for (JavaOutline.Member top : outline.types) {
            if (top.name().equals(simple)) {
                type = top;
            }
        }
        if (type == null) {
            return "`" + address + "` does not declare `" + simple + "`.";
        }
        String asked = member;
        for (int i = 0; i < inner.size(); i++) {
            String name = inner.get(i);
            JavaOutline.Member nested = null;
            boolean declaresIt = false;
            for (JavaOutline.Member child : type.children()) {
                declaresIt |= child.name().equals(name);
                if (nested == null && child.kind() == JavaOutline.Kind.TYPE
                        && child.name().equals(name)) {
                    nested = child;
                }
            }
            if (nested != null) {
                type = nested;
            } else if (declaresIt && i == inner.size() - 1 && (asked == null || asked.isBlank())) {
                asked = name;
            } else {
                Set<String> names = new LinkedHashSet<>();
                type.children().forEach(child -> names.add(child.name()));
                return "`" + type.name() + "` (" + address + ") declares no `" + name
                    + "`. It declares: " + String.join(", ", names) + ". Ask for one as "
                    + type.name() + "#name.\n";
            }
        }
        return declarationIn(address, type, asked);
    }

    /**
     * {@link #bodyOf} once the file is known: {@code member} out of {@code typeName} as
     * {@code source} declares it. Also what answers a worker about a file of its own checkout,
     * which the tree holds as it was when the task started (see {@link CheckoutCode}).
     *
     * @param member null or blank for the type itself; one name; or several separated by commas
     */
    static String bodyIn(String address, String source, String typeName, String member) {
        JavaOutline outline;
        try {
            outline = JavaOutline.of(source);
        } catch (Exception unreadable) {                                   // noqa
            return "`" + address + "` could not be read as Java.";
        }
        String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
        JavaOutline.Member type = null;
        for (JavaOutline.Member top : outline.types) {
            if (top.name().equals(simple)) {
                type = top;
            }
            for (JavaOutline.Member nested : top.descendants()) {
                if (type == null && nested.kind() == JavaOutline.Kind.TYPE
                        && nested.name().equals(simple)) {
                    type = nested;
                }
            }
        }
        if (type == null) {
            return "`" + address + "` does not declare `" + simple + "`.";
        }
        return declarationIn(address, type, member);
    }

    /** {@code member} of {@code type}, or the type itself, as {@link #bodyIn} answers it. */
    private static String declarationIn(String address, JavaOutline.Member type, String member) {
        String simple = type.name();
        if (member == null || member.isBlank()) {
            // The whole type is asked for to see what it does, so it is answered as the
            // compiler reads it: no comments, no blank lines, and no imports (run 82: roles
            // read the file instead, comments and imports included).
            String code = JavaOutline.withoutComments(type.text());
            boolean shortened = code.length() < type.text().length();
            if (code.length() <= OVERLOADS_QUOTED_CHARS) {
                return "// " + address + ":" + type.startLine() + "-" + type.endLine()
                    + (shortened ? " - comments and blank lines left out" : "") + "\n" + code
                    + "\n";
            }
            StringBuilder sb = new StringBuilder("// " + address + ":" + type.startLine() + "-"
                + type.endLine() + " - " + code.length() + " characters of code, so its members "
                + "are listed; ask for one as " + simple + "#name, or several as " + simple
                + "#first,second\n" + type.header() + "\n");
            for (JavaOutline.Member child : type.children()) {
                sb.append("  ").append(child.header()).append("  // :").append(child.startLine())
                    .append('-').append(child.endLine()).append('\n');
            }
            return sb.toString();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String name : member.replaceAll("\\([^)]*\\)", "").split(",")) {
            String one = name.strip();
            if (!one.isEmpty()) {
                wanted.add(one);
            }
        }
        Set<String> names = new LinkedHashSet<>();
        type.children().forEach(child -> names.add(child.name()));
        StringBuilder sb = new StringBuilder();
        for (String name : wanted) {
            List<JavaOutline.Member> matches = new ArrayList<>();
            for (JavaOutline.Member child : type.children()) {
                if (child.name().equals(name)) {
                    matches.add(child);
                }
            }
            if (matches.isEmpty()) {
                sb.append("`").append(simple).append("` declares no `").append(name)
                    .append("`. It declares: ").append(String.join(", ", names)).append(".\n");
                continue;
            }
            int total = matches.stream().mapToInt(m -> m.text().length()).sum();
            if (matches.size() > 1 && total > OVERLOADS_QUOTED_CHARS) {
                sb.append("`").append(simple).append('#').append(name).append("` has ")
                    .append(matches.size()).append(" overloads, too long to quote together. "
                        + "Each with its lines; read one with its file and lines:\n");
                for (JavaOutline.Member match : matches) {
                    sb.append("  ").append(match.header()).append("  // ").append(address)
                        .append(':').append(match.startLine()).append('-')
                        .append(match.endLine()).append('\n');
                }
                continue;
            }
            for (JavaOutline.Member match : matches) {
                sb.append("// ").append(address).append(':').append(match.startLine())
                    .append('-').append(match.endLine()).append('\n').append(match.text())
                    .append("\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * What a whole-file read of a Java file is answered with besides the file: the queries that
     * return its members or one of them. "" for a file that declares no type with at least two
     * members, or that is so short that the note would be a tenth of it or more.
     *
     * @param shapeTool what the asker's members query is called: public_shape or shape_of
     */
    public static String wholeFileNote(String address, String text, String shapeTool) {
        if (text == null || address == null || !address.endsWith(".java")) {
            return "";
        }
        JavaOutline.Member type;
        try {
            type = JavaOutline.of(text).primaryType();
        } catch (Exception unreadable) {                                   // noqa
            return "";
        }
        if (type == null || type.children().size() < 2) {
            return "";
        }
        String note = "\n[You read this whole file: " + text.length() + " characters, sent again "
            + "with every later call. " + shapeTool + " " + type.name() + " lists its "
            + type.children().size() + " members; body_of " + type.name() + "#<member> returns "
            + "ONE of them, body_of " + type.name() + "#<first>,<second> several, body_of "
            + type.name() + " the type without comments and imports.]";
        return note.length() * 10 <= text.length() ? note : "";
    }

    // -------------------------------------------------------------------------------------------

    /**
     * The resource files of a module (src/main/resources, src/test/resources) with their sizes;
     * one resource's content when it is small; its outline when it is large; and with
     * {@code <file>#<element, key or L10-40>} one part of it (section 59). Addresses are the ones
     * {@link #buildOf} takes: {@code <module>} or {@code <module>/<path under the resources>}.
     */
    public String resourcesOf(String where) {
        if (curator == null) {
            return NO_TREE;
        }
        String asked = where == null ? "" : where.strip().replace('\\', '/');
        String part = null;
        int hash = asked.indexOf('#');
        if (hash >= 0) {
            part = asked.substring(hash + 1);
            asked = asked.substring(0, hash).strip();
        }
        while (asked.startsWith("/")) {
            asked = asked.substring(1);
        }
        while (asked.endsWith("/")) {
            asked = asked.substring(0, asked.length() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeCurator.Root root : curator.roots()) {
            if (root.path() == null) {
                continue;
            }
            String inRoot = asked.equals(root.label()) ? ""
                : asked.startsWith(root.label() + "/") ? asked.substring(root.label().length() + 1)
                : asked;
            if (inRoot.isEmpty() && !asked.equals(root.label())) {
                continue; // nothing was named: a module is needed
            }
            try {
                Path target = root.path().resolve(inRoot).normalize();
                if (!target.startsWith(root.path().normalize())) {
                    continue;
                }
                String address = address(root.label(), inRoot);
                if (Files.isRegularFile(target)) {
                    sb.append(ResourceQueries.file(address, target, part));
                } else if (Files.isDirectory(target)) {
                    sb.append(ResourceQueries.listing(address, target));
                }
            } catch (RuntimeException notAPath) {                          // noqa
                // not a path of this root
            }
        }
        return sb.length() == 0
            ? "No module or resource `" + where + "` in this project's material. Give a module "
                + "(its folder) to list its resource files."
            : sb.toString();
    }

    /**
     * What the build files of a module or folder declare - coordinates, parent, modules,
     * properties, dependencies (the test-scope ones again on a line of their own, with versions
     * and scopes resolved from the module's properties and dependency management) and plugins
     * with their configuration, executions and dependencies - without the build file's text. A
     * blank {@code where} is every build file the tree knows, each with its counts only.
     */
    public String buildOf(String where) {
        if (curator == null) {
            return NO_TREE;
        }
        String asked = where == null ? "" : where.strip().replace('\\', '/');
        while (asked.startsWith("/")) {
            asked = asked.substring(1);
        }
        if (asked.endsWith("pom.xml")) {
            asked = asked.substring(0, asked.length() - "pom.xml".length());
        }
        while (asked.endsWith("/")) {
            asked = asked.substring(0, asked.length() - 1);
        }
        Map<String, Path> files = new LinkedHashMap<>();
        for (KnowledgeCurator.Root root : curator.roots()) {
            if (root.path() == null) {
                continue;
            }
            String inRoot = asked.equals(root.label()) ? ""
                : asked.startsWith(root.label() + "/") ? asked.substring(root.label().length() + 1)
                : asked;
            try {
                Path dir = root.path().resolve(inRoot).normalize();
                if (dir.startsWith(root.path().normalize()) && Files.isRegularFile(dir.resolve("pom.xml"))) {
                    files.put(address(root.label(), (inRoot.isEmpty() ? "" : inRoot + "/")
                        + "pom.xml"), dir.resolve("pom.xml"));
                }
            } catch (RuntimeException notAPath) {                          // noqa
                // not a folder of this root
            }
        }
        SemanticIndex index = index();
        if (index != null) {
            for (SemanticIndex.Ref ref : index.buildOf(asked)) {
                for (KnowledgeCurator.Root root : curator.roots()) {
                    if (root.label().equals(ref.rootLabel()) && root.path() != null) {
                        files.putIfAbsent(address(ref.rootLabel(), ref.file()),
                            root.path().resolve(ref.file()));
                    }
                }
            }
        }
        if (files.isEmpty()) {
            return "No build file in `" + where + "` in this project's material (only Maven "
                + "pom.xml files are read as a tree).";
        }
        boolean countsOnly = asked.isEmpty() && files.size() > 3;
        StringBuilder sb = new StringBuilder();
        // Asked for everything, a reference checkout answers with how many build files it has,
        // not with each of them: run 85's architect was sent 12,711 characters of the
        // framework's own modules and examples to learn the project's three.
        Map<String, Integer> ofReference = new LinkedHashMap<>();
        for (Map.Entry<String, Path> file : files.entrySet()) {
            String reference = countsOnly ? referenceRootOf(file.getValue()) : null;
            if (reference != null) {
                ofReference.merge(reference, 1, Integer::sum);
                continue;
            }
            sb.append(file.getKey()).append('\n').append(pom(file.getValue(), countsOnly));
        }
        ofReference.forEach((label, count) -> sb.append(label).append(": ").append(count)
            .append(" build file(s) of reference material, not listed. build_of ").append(label)
            .append("/<module> for one; types_in ").append(label).append(" for its modules.\n"));
        if (countsOnly) {
            sb.append("Ask for one module to see what it declares.\n");
        }
        return sb.toString();
    }

    /** The label of the reference root a file is under; null for the project's own. */
    private String referenceRootOf(Path file) {
        Path normal = file.normalize();
        for (KnowledgeCurator.Root root : curator.roots()) {
            if (root.path() != null && normal.startsWith(root.path().normalize())) {
                boolean own = ownRoot != null && !ownRoot.isBlank()
                    ? ownRoot.equals(root.label()) : "project".equals(root.label());
                return own ? null : root.label();
            }
        }
        return null;
    }

    private static String pom(Path file, boolean countsOnly) {
        Element project;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(file.toFile());
            project = document.getDocumentElement();
        } catch (Exception unreadable) {                                   // noqa
            return "  (could not be read as XML)\n";
        }
        StringBuilder sb = new StringBuilder();
        Element parent = child(project, "parent");
        sb.append("  artifact: ").append(coordinate(project))
            .append(text(project, "packaging").isEmpty() ? ""
                : " (packaging " + text(project, "packaging") + ")")
            .append(parent == null ? "" : ", parent " + coordinate(parent)).append('\n');
        List<String> modules = texts(child(project, "modules"), "module");
        List<String> properties = new ArrayList<>();
        for (Element property : children(child(project, "properties"))) {
            properties.add(property.getTagName() + "=" + property.getTextContent().strip());
        }
        List<String> dependencies = coordinates(child(project, "dependencies"), "dependency");
        Element management = child(project, "dependencyManagement");
        List<String> managed = coordinates(child(management, "dependencies"), "dependency");
        Element build = child(project, "build");
        List<String> plugins = coordinates(child(build, "plugins"), "plugin");
        plugins.addAll(coordinates(child(child(build, "pluginManagement"), "plugins"), "plugin"));
        if (countsOnly) {
            return sb.append("  ").append(modules.size()).append(" module(s), ")
                .append(properties.size()).append(" propert(ies), ").append(dependencies.size())
                .append(" dependenc(ies), ").append(managed.size()).append(" managed, ")
                .append(plugins.size()).append(" plugin(s)\n").toString();
        }
        line(sb, "modules", modules);
        line(sb, "properties", properties);
        line(sb, "dependencies", dependencies);
        line(sb, "managed dependencies", managed);
        line(sb, "plugins", plugins);
        testScope(sb, project, properties, file);
        pluginDetails(sb, child(build, "plugins"), "plugin");
        pluginDetails(sb, child(child(build, "pluginManagement"), "plugins"), "managed plugin");
        return sb.toString();
    }

    /**
     * The module's test-scope dependencies with the version and scope they really have: a version
     * or scope the dependency leaves out comes from {@code dependencyManagement}, and a
     * {@code ${property}} is replaced by the module's property (section 59).
     */
    private static void testScope(StringBuilder sb, Element project, List<String> properties,
                                  Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Element> managed = new LinkedHashMap<>();
        // The parents' (nearest last, so nearest wins) properties and dependency management
        // first, then the module's own.
        List<Element> chain = new ArrayList<>();
        Element at = project;
        Path where = file;
        for (int up = 0; up < 8 && at != null && child(at, "parent") != null; up++) {
            String relative = text(child(at, "parent"), "relativePath");
            Path parentFile = where.getParent() == null ? null : where.getParent()
                .resolve(relative.isEmpty() ? "../pom.xml" : relative).normalize();
            if (parentFile != null && Files.isDirectory(parentFile)) {
                parentFile = parentFile.resolve("pom.xml");
            }
            if (parentFile == null || !Files.isRegularFile(parentFile)) {
                break;
            }
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                at = factory.newDocumentBuilder().parse(parentFile.toFile()).getDocumentElement();
            } catch (Exception unreadable) {                               // noqa
                break;
            }
            chain.add(0, at);
            where = parentFile;
        }
        chain.add(project);
        for (Element pom : chain) {
            for (Element property : children(child(pom, "properties"))) {
                values.put(property.getTagName(), property.getTextContent().strip());
            }
            for (Element element : children(child(child(pom, "dependencyManagement"),
                    "dependencies"))) {
                managed.put(text(element, "groupId") + ":" + text(element, "artifactId"), element);
            }
        }
        List<String> tests = new ArrayList<>();
        for (Element dependency : children(child(project, "dependencies"))) {
            Element under = managed.get(text(dependency, "groupId") + ":"
                + text(dependency, "artifactId"));
            String scope = text(dependency, "scope");
            if (scope.isEmpty() && under != null) {
                scope = text(under, "scope");
            }
            if (!scope.equals("test")) {
                continue;
            }
            String version = text(dependency, "version");
            if (version.isEmpty() && under != null) {
                version = text(under, "version");
            }
            for (Map.Entry<String, String> property : values.entrySet()) {
                version = version.replace("${" + property.getKey() + "}", property.getValue());
            }
            tests.add(text(dependency, "groupId") + ":" + text(dependency, "artifactId")
                + (version.isEmpty() ? "" : ":" + version));
        }
        line(sb, "test-scope dependencies", tests);
    }

    /** Each plugin's configuration (flattened to path=value), executions and own dependencies. */
    private static void pluginDetails(StringBuilder sb, Element plugins, String label) {
        for (Element plugin : children(plugins)) {
            if (!plugin.getTagName().equals("plugin")) {
                continue;
            }
            List<String> configuration = new ArrayList<>();
            flatten(child(plugin, "configuration"), "", configuration);
            List<String> executions = new ArrayList<>();
            for (Element execution : children(child(plugin, "executions"))) {
                List<String> inside = new ArrayList<>();
                flatten(child(execution, "configuration"), "", inside);
                executions.add((text(execution, "id").isEmpty() ? "default" : text(execution, "id"))
                    + " phase=" + (text(execution, "phase").isEmpty() ? "(default)"
                        : text(execution, "phase"))
                    + " goals=" + texts(child(execution, "goals"), "goal")
                    + (inside.isEmpty() ? "" : " configuration: " + String.join(", ", inside)));
            }
            List<String> own = coordinates(child(plugin, "dependencies"), "dependency");
            if (configuration.isEmpty() && executions.isEmpty() && own.isEmpty()
                    && text(plugin, "extensions").isEmpty()) {
                continue;
            }
            sb.append("  ").append(label).append(' ').append(coordinate(plugin)).append(":\n");
            if (!configuration.isEmpty()) {
                sb.append("    configuration: ").append(String.join(", ", configuration)).append('\n');
            }
            for (String execution : executions) {
                sb.append("    execution ").append(execution).append('\n');
            }
            if (!own.isEmpty()) {
                sb.append("    dependencies: ").append(String.join("; ", own)).append('\n');
            }
        }
    }

    private static void flatten(Element element, String prefix, List<String> out) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Element child : children(element)) {
            String name = child.getTagName();
            int n = seen.merge(name, 1, Integer::sum);
            String path = prefix + name + (n > 1 ? "[" + n + "]" : "");
            if (children(child).isEmpty()) {
                out.add(path + "=" + child.getTextContent().strip());
            } else {
                flatten(child, path + ".", out);
            }
        }
    }

    private static void line(StringBuilder sb, String name, List<String> values) {
        if (!values.isEmpty()) {
            sb.append("  ").append(name).append(" (").append(values.size()).append("): ")
                .append(String.join("; ", values)).append('\n');
        }
    }

    private static String coordinate(Element element) {
        String scope = text(element, "scope");
        String version = text(element, "version");
        return (text(element, "groupId").isEmpty() ? "" : text(element, "groupId") + ":")
            + text(element, "artifactId") + (version.isEmpty() ? "" : ":" + version)
            + (scope.isEmpty() ? "" : " [" + scope + "]");
    }

    private static List<String> coordinates(Element parent, String tag) {
        List<String> all = new ArrayList<>();
        for (Element element : children(parent)) {
            if (element.getTagName().equals(tag)) {
                all.add(coordinate(element));
            }
        }
        return all;
    }

    private static List<String> texts(Element parent, String tag) {
        List<String> all = new ArrayList<>();
        for (Element element : children(parent)) {
            if (element.getTagName().equals(tag)) {
                all.add(element.getTextContent().strip());
            }
        }
        return all;
    }

    private static List<Element> children(Element parent) {
        List<Element> all = new ArrayList<>();
        if (parent == null) {
            return all;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                all.add((Element) nodes.item(i));
            }
        }
        return all;
    }

    private static Element child(Element parent, String tag) {
        for (Element element : children(parent)) {
            if (element.getTagName().equals(tag)) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element parent, String tag) {
        Element element = child(parent, tag);
        return element == null ? "" : element.getTextContent().strip();
    }
}
