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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One member of one type in a worker's OWN checkout, by name: read it, replace it, add one
 * (live run 82, 2026-10-04; CLAUDE.md section 1).
 *
 * <p><b>Why.</b> A worker changed code with {@code apply_diff}, which needs the exact lines
 * around the change, or with {@code write_file}, which needs the whole file - so it read the
 * whole file first, whatever the tree could have told it. Run 82: 36 whole files, 107,143
 * characters, and five rejected diffs each followed by a whole-file rewrite. And the tree
 * answers about the project as it was when the task started, so a worker that had changed a
 * file had to read it whole to see its own change. Here a member is addressed as
 * {@code Type#member} or {@code path#member} and the file as it is on disk now is what is read
 * and written.
 *
 * <p>No model and no compiler: {@link JavaOutline} finds the member. This class decides nothing
 * about what may be written - it returns the new content and the caller, which holds the write
 * policy, writes it. It reads only regular files inside the checkout and follows no link out.
 */
public final class CheckoutCode {

    private static final Set<String> SKIPPED = Set.of(".git", "target", "build", "node_modules",
        ".idea", ".gradle", "out");

    private CheckoutCode() {}

    /**
     * What an edit comes to.
     *
     * @param answer  what the worker is told
     * @param file    the file to write, relative to the checkout; null when nothing is to be
     *                written
     * @param content the file's whole new content; null when nothing is to be written
     */
    public record Edit(String answer, String file, String content) {

        public boolean changed() {
            return file != null && content != null;
        }

        static Edit refused(String why) {
            return new Edit(why, null, null);
        }
    }

    // --- finding the file ------------------------------------------------------------------------

    private static boolean isPath(String asked) {
        return asked.contains("/") || asked.endsWith(".java");
    }

    /** The files of the checkout that {@code typeOrPath} can mean; empty when there is none. */
    static List<Path> filesOf(Path checkout, String typeOrPath) {
        List<Path> found = new ArrayList<>();
        if (checkout == null || typeOrPath == null || typeOrPath.isBlank()) {
            return found;
        }
        Path root = checkout.toAbsolutePath().normalize();
        String asked = typeOrPath.replace('\\', '/').strip();
        if (isPath(asked)) {
            try {
                Path file = root.resolve(asked).normalize();
                if (file.startsWith(root) && inside(root, file)) {
                    found.add(file);
                }
            } catch (RuntimeException notAPath) {                           // noqa
                // not a path this system can hold
            }
            return found;
        }
        String[] parts = asked.split("\\.");
        // Outer.Inner is declared in Outer.java: the last name first, then the ones before it.
        for (int i = parts.length - 1; i >= 0 && found.isEmpty(); i--) {
            String simple = parts[i];
            if (simple.isEmpty() || !Character.isUpperCase(simple.charAt(0))) {
                break;
            }
            String packagePath = String.join("/", java.util.Arrays.copyOfRange(parts, 0, i));
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> named = walk.filter(p -> p.getFileName() != null
                        && p.getFileName().toString().equals(simple + ".java"))
                    .filter(p -> {
                        for (Path segment : root.relativize(p)) {
                            if (SKIPPED.contains(segment.toString())) {
                                return false;
                            }
                        }
                        return inside(root, p);
                    }).sorted().toList();
                List<Path> inPackage = packagePath.isEmpty() ? named : named.stream()
                    .filter(p -> p.getParent().toString().replace('\\', '/')
                        .endsWith(packagePath)).toList();
                found.addAll(inPackage.isEmpty() ? named : inPackage);
            } catch (IOException | RuntimeException unreadable) {            // noqa
                return found;
            }
        }
        return found;
    }

    /** A regular file that is really inside the checkout: no link, and no link above it. */
    private static boolean inside(Path root, Path file) {
        try {
            return Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException | RuntimeException unreadable) {                // noqa
            return false;
        }
    }

    private static String relative(Path checkout, Path file) {
        return checkout.toAbsolutePath().normalize().relativize(file).toString()
            .replace('\\', '/');
    }

    private static String severalFiles(Path checkout, String asked, List<Path> files) {
        List<String> paths = new ArrayList<>();
        files.forEach(f -> paths.add(relative(checkout, f)));
        return "`" + asked + "` is declared in " + files.size() + " files of your checkout: "
            + String.join(", ", paths) + ". Say which, by its path: <path>#<member>.";
    }

    // --- reading ---------------------------------------------------------------------------------

    /**
     * {@code Type#member} (or {@code Type}, or {@code Type#first,second}) as the checkout holds
     * it NOW, rendered as {@link TreeQueries#bodyOf} renders it. Null when the checkout has no
     * one file for the type - a reference type, a library's - so that the tree is asked instead.
     */
    public static String bodyOf(Path checkout, String what) {
        String asked = what == null ? "" : what.strip();
        int hash = asked.indexOf('#');
        String typeName = (hash < 0 ? asked : asked.substring(0, hash)).strip();
        String member = hash < 0 ? null : asked.substring(hash + 1).strip();
        if (typeName.isEmpty() || isPath(typeName)) {
            return null;
        }
        List<Path> files = filesOf(checkout, typeName);
        if (files.size() != 1) {
            return null;
        }
        try {
            String address = relative(checkout, files.get(0));
            String answer = TreeQueries.bodyIn(address, Files.readString(files.get(0)), typeName,
                member);
            return answer.startsWith("`" + address + "` ") ? null : answer;
        } catch (IOException | RuntimeException unreadable) {                // noqa
            return null;
        }
    }

    // --- what the tree does not hold -------------------------------------------------------------

    /**
     * True when the checkout's file is not what the tree was built from: the tree has no such
     * file, or other text. Line endings do not count.
     */
    private static boolean notAsTheTree(String relativePath, String now,
                                        java.util.function.Function<String, String> treeSource) {
        String then = treeSource == null ? null : treeSource.apply(relativePath);
        return then == null || !then.replace("\r\n", "\n").equals(now.replace("\r\n", "\n"));
    }

    /**
     * The members of {@code type} as the checkout declares them NOW, when its file is new or
     * changed since the tree was built (live run 88, DEVELOPER_CORRECTIONS section 60). Null
     * when the checkout has no one file for the type or the file is what the tree was built
     * from - the tree then answers, with the types the compiler resolved.
     *
     * @param treeSource a file's source as the tree was built from it, by repository path; null
     *                   from it when the tree has no such file
     */
    public static String shapeIfNotAsTheTree(Path checkout, String type,
                                             java.util.function.Function<String, String> treeSource) {
        String asked = type == null ? "" : type.strip();
        if (asked.isEmpty() || isPath(asked) || asked.indexOf('#') >= 0) {
            return null;
        }
        List<Path> files = filesOf(checkout, asked);
        if (files.size() != 1) {
            return null;
        }
        try {
            String address = relative(checkout, files.get(0));
            String now = Files.readString(files.get(0));
            if (!notAsTheTree(address, now, treeSource)) {
                return null;
            }
            JavaOutline outline = JavaOutline.of(now);
            JavaOutline.Member declared = typeOf(outline, asked);
            if (declared == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder("// ").append(address).append(':')
                .append(declared.startLine()).append('-').append(declared.endLine())
                .append(" - as your checkout holds it now (new or changed since the project's "
                    + "tree was built); body_of ").append(declared.name())
                .append("#<member> returns one member\n");
            if (!outline.packageName.isEmpty()) {
                sb.append("package ").append(outline.packageName).append(";\n");
            }
            sb.append(declared.header()).append(" {\n");
            for (JavaOutline.Member child : declared.children()) {
                sb.append("  ").append(child.header()).append("  // :").append(child.startLine());
                if (child.endLine() != child.startLine()) {
                    sb.append('-').append(child.endLine());
                }
                sb.append('\n');
            }
            return sb.append("}\n").toString();
        } catch (IOException | RuntimeException unreadable) {                // noqa
            return null;
        }
    }

    /** Every .java file under {@code dir}, build output and tool folders left out. */
    private static List<Path> javaFilesUnder(Path root, Path dir, boolean deep) {
        try (Stream<Path> walk = Files.walk(dir, deep ? Integer.MAX_VALUE : 1)) {
            return walk.filter(p -> p.getFileName() != null
                    && p.getFileName().toString().endsWith(".java"))
                .filter(p -> {
                    for (Path segment : root.relativize(p)) {
                        if (SKIPPED.contains(segment.toString())) {
                            return false;
                        }
                    }
                    return inside(root, p);
                }).sorted().toList();
        } catch (IOException | RuntimeException unreadable) {                // noqa
            return List.of();
        }
    }

    /** One line per type a file declares: kind and name as written, file and line, and why. */
    private static void listTypes(StringBuilder sb, String address, String source, boolean isNew) {
        JavaOutline outline;
        try {
            outline = JavaOutline.of(source);
        } catch (RuntimeException unreadable) {                              // noqa
            return;
        }
        for (JavaOutline.Member type : outline.types) {
            sb.append("  ").append(JavaOutline.stripAnnotations(type.header())).append("  ")
                .append(address).append(':').append(type.startLine())
                .append(isNew ? "  (new)" : "  (changed)").append('\n');
        }
    }

    /**
     * What {@code types_in} of the tree cannot say about {@code where}: the types of the
     * checkout's files there that are new or changed since the tree was built. "" when there
     * are none. {@code where} is what types_in takes - a folder or module as a path (its whole
     * subtree), or a package (the folders of that package).
     */
    public static String notAsTheTreeIn(Path checkout, String where,
                                        java.util.function.Function<String, String> treeSource) {
        if (checkout == null || where == null || where.isBlank()) {
            return "";
        }
        Path root = checkout.toAbsolutePath().normalize();
        String asked = where.replace('\\', '/').strip();
        List<Path> files = new ArrayList<>();
        try {
            Path folder = root.resolve(asked).normalize();
            if (folder.startsWith(root) && Files.isDirectory(folder)) {
                files.addAll(javaFilesUnder(root, folder, true));
            } else if (!asked.contains("/")) {
                String suffix = asked.replace('.', '/');
                for (Path file : javaFilesUnder(root, root, true)) {
                    String parent = root.relativize(file.getParent()).toString().replace('\\', '/');
                    if (parent.equals(suffix) || parent.endsWith("/" + suffix)) {
                        files.add(file);
                    }
                }
            }
        } catch (RuntimeException notAPlace) {                               // noqa
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Path file : files) {
            try {
                String address = relative(checkout, file);
                String now = Files.readString(file);
                if (notAsTheTree(address, now, treeSource)) {
                    listTypes(sb, address, now,
                        treeSource == null || treeSource.apply(address) == null);
                }
            } catch (IOException | RuntimeException unreadable) {            // noqa
                // a file that cannot be read is not listed
            }
        }
        return sb.length() == 0 ? "" : "\nIn your checkout now and not in the list above as "
            + "they are (added or changed since the project's tree was built - by you or by "
            + "an earlier task of this run); shape_of and body_of answer for them as they are "
            + "now:\n" + sb;
    }

    /**
     * What a symbol search over the project cannot find: the types of the checkout's NEW files
     * whose name matches {@code name} - a fragment, or a pattern with {@code *}. "" when none.
     */
    public static String newTypesNamed(Path checkout, String name,
                                       java.util.function.Function<String, String> treeSource) {
        if (checkout == null || name == null || name.isBlank()) {
            return "";
        }
        Path root = checkout.toAbsolutePath().normalize();
        String wanted = name.strip();
        wanted = wanted.substring(wanted.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        StringBuilder pattern = new StringBuilder();
        for (String part : wanted.split("\\*", -1)) {
            pattern.append(pattern.length() == 0 ? "" : ".*")
                .append(java.util.regex.Pattern.quote(part));
        }
        java.util.regex.Pattern matches = java.util.regex.Pattern.compile(
            wanted.contains("*") ? pattern.toString() : ".*" + pattern + ".*");
        StringBuilder sb = new StringBuilder();
        for (Path file : javaFilesUnder(root, root, true)) {
            String simple = file.getFileName().toString();
            simple = simple.substring(0, simple.length() - ".java".length());
            if (!matches.matcher(simple.toLowerCase(java.util.Locale.ROOT)).matches()) {
                continue;
            }
            try {
                String address = relative(checkout, file);
                if (treeSource == null || treeSource.apply(address) == null) {
                    listTypes(sb, address, Files.readString(file), true);
                }
            } catch (IOException | RuntimeException unreadable) {            // noqa
                // not listed
            }
        }
        return sb.length() == 0 ? "" : "\nIn your checkout and not in the project the search "
            + "above covers (added by an earlier task of this run, or by you):\n" + sb;
    }

    // --- writing ---------------------------------------------------------------------------------

    private record Opened(Path file, String address, String source, boolean crlf,
                          JavaOutline outline) {}

    private static Object open(Path checkout, String typeOrPath) {
        List<Path> files = filesOf(checkout, typeOrPath);
        if (files.isEmpty()) {
            return Edit.refused("error: your checkout has no file for `" + typeOrPath + "`. Give "
                + "a type it declares, or the file's path. A new file is written with "
                + "write_file.");
        }
        if (files.size() > 1) {
            return Edit.refused("error: " + severalFiles(checkout, typeOrPath, files));
        }
        try {
            String raw = Files.readString(files.get(0));
            String source = raw.replace("\r\n", "\n");
            return new Opened(files.get(0), relative(checkout, files.get(0)), source,
                raw.contains("\r\n"), JavaOutline.of(source));
        } catch (IOException | RuntimeException unreadable) {
            return Edit.refused("error: " + typeOrPath + " could not be read as Java: "
                + unreadable.getMessage());
        }
    }

    /** The type {@code name} of the file, or its outermost type when {@code name} is null. */
    private static JavaOutline.Member typeOf(JavaOutline outline, String name) {
        if (name == null || name.isBlank()) {
            return outline.primaryType();
        }
        String simple = name.substring(name.lastIndexOf('.') + 1);
        for (JavaOutline.Member top : outline.types) {
            if (top.name().equals(simple)) {
                return top;
            }
        }
        for (JavaOutline.Member top : outline.types) {
            for (JavaOutline.Member nested : top.descendants()) {
                if (nested.kind() == JavaOutline.Kind.TYPE && nested.name().equals(simple)) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static List<JavaOutline.Member> typesOf(JavaOutline outline) {
        List<JavaOutline.Member> types = new ArrayList<>();
        for (JavaOutline.Member top : outline.types) {
            types.add(top);
            for (JavaOutline.Member nested : top.descendants()) {
                if (nested.kind() == JavaOutline.Kind.TYPE) {
                    types.add(nested);
                }
            }
        }
        return types;
    }

    /**
     * Replaces ONE member with {@code newText}, the complete new declaration.
     *
     * @param where {@code Type#member} or {@code path#member}; {@code :LINE} after it names one
     *              overload by the line it starts on
     */
    public static Edit replaceMember(Path checkout, String where, String newText) {
        String asked = where == null ? "" : where.strip();
        int hash = asked.indexOf('#');
        if (hash <= 0 || hash == asked.length() - 1) {
            return Edit.refused("error: say which member, as Type#member or <path>#member.");
        }
        if (newText == null || newText.isBlank()) {
            return Edit.refused("error: give the member's complete new declaration. To take a "
                + "member out, use remove_member.");
        }
        String owner = asked.substring(0, hash).strip();
        String name = asked.substring(hash + 1).strip().replaceAll("\\([^)]*\\)", "");
        int line = 0;
        int colon = name.lastIndexOf(':');
        if (colon > 0) {
            try {
                line = Integer.parseInt(name.substring(colon + 1).strip());
                name = name.substring(0, colon).strip();
            } catch (NumberFormatException notALine) {                      // noqa
                line = 0;
            }
        }
        Object opened = open(checkout, owner);
        if (opened instanceof Edit refused) {
            return refused;
        }
        Opened file = (Opened) opened;
        List<JavaOutline.Member> owners = isPath(owner) ? typesOf(file.outline())
            : typeOf(file.outline(), owner) == null ? List.of()
                : List.of(typeOf(file.outline(), owner));
        if (owners.isEmpty()) {
            return Edit.refused("error: `" + file.address() + "` does not declare `" + owner
                + "`.");
        }
        List<JavaOutline.Member> matches = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (JavaOutline.Member type : owners) {
            for (JavaOutline.Member child : type.children()) {
                names.add(child.name());
                if (child.name().equals(name)) {
                    matches.add(child);
                }
            }
        }
        if (matches.isEmpty()) {
            return Edit.refused("error: `" + file.address() + "` declares no `" + name
                + "`. It declares: " + String.join(", ", names) + ". A new member is added "
                + "with add_member.");
        }
        Split split = Split.of(newText);
        String unbalanced = unbalanced(split.body());
        if (unbalanced != null) {
            return Edit.refused("error: " + unbalanced + " Nothing was changed.");
        }
        JavaOutline.Member target = matches.get(0);
        if (matches.size() > 1) {
            target = null;
            String wanted = parametersOf(headerOf(split.body()));
            for (JavaOutline.Member match : matches) {
                boolean byLine = line > 0 && line >= match.startLine() && line <= match.endLine();
                boolean bySignature = line == 0 && wanted != null
                    && wanted.equals(parametersOf(match.header()));
                if (byLine || bySignature) {
                    target = match;
                    break;
                }
            }
            if (target == null) {
                StringBuilder sb = new StringBuilder("error: `" + name + "` has " + matches.size()
                    + " overloads in `" + file.address() + "` and the new text has the parameters "
                    + "of none of them. Say which by the line it starts on, as " + owner + "#"
                    + name + ":<line>:\n");
                for (JavaOutline.Member match : matches) {
                    sb.append("  ").append(match.header()).append("  // line ")
                        .append(match.startLine()).append('\n');
                }
                return Edit.refused(sb.toString());
            }
        }
        String source = file.source();
        int at = source.indexOf(target.text(), file.outline().offsetOfLine(target.startLine()));
        if (at < 0) {
            return Edit.refused("error: `" + name + "` could not be located in `"
                + file.address() + "`. Nothing was changed.");
        }
        String block = indented(split.body(), indentAt(source, at), false);
        String changed = source.substring(0, at) + block
            + source.substring(at + target.text().length());
        changed = withImports(changed, split.imports());
        int newLines = block.split("\n", -1).length;
        return new Edit("replaced " + name + " in " + file.address() + ": lines "
            + target.startLine() + "-" + target.endLine() + " are now " + newLines + " line(s)"
            + (split.imports().isEmpty() ? "" : "; " + split.imports().size()
                + " import line(s) checked") + ". Its documentation comment, if it has one, "
            + "is as it was.", file.address(), file.crlf() ? changed.replace("\n", "\r\n")
                : changed);
    }

    /**
     * Takes ONE member out of a type, with the documentation comment directly above it (live
     * run 89, DEVELOPER_CORRECTIONS section 64: with no way to delete a member, both workers
     * replaced two unused helpers with a stub, read the whole file and wrote a diff).
     *
     * <p>The answer names the members of the file that still use the name, so the file need not
     * be read to find out what the removal broke.
     *
     * @param where {@code Type#member} or {@code path#member}; {@code :LINE} after it names one
     *              overload by the line it starts on
     */
    public static Edit removeMember(Path checkout, String where) {
        String asked = where == null ? "" : where.strip();
        int hash = asked.indexOf('#');
        if (hash <= 0 || hash == asked.length() - 1) {
            return Edit.refused("error: say which member, as Type#member or <path>#member.");
        }
        String owner = asked.substring(0, hash).strip();
        String name = asked.substring(hash + 1).strip().replaceAll("\\([^)]*\\)", "");
        int line = 0;
        int colon = name.lastIndexOf(':');
        if (colon > 0) {
            try {
                line = Integer.parseInt(name.substring(colon + 1).strip());
                name = name.substring(0, colon).strip();
            } catch (NumberFormatException notALine) {                      // noqa
                line = 0;
            }
        }
        Object opened = open(checkout, owner);
        if (opened instanceof Edit refused) {
            return refused;
        }
        Opened file = (Opened) opened;
        List<JavaOutline.Member> owners = isPath(owner) ? typesOf(file.outline())
            : typeOf(file.outline(), owner) == null ? List.of()
                : List.of(typeOf(file.outline(), owner));
        if (owners.isEmpty()) {
            return Edit.refused("error: `" + file.address() + "` does not declare `" + owner
                + "`.");
        }
        List<JavaOutline.Member> matches = new ArrayList<>();
        List<JavaOutline.Member> others = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (JavaOutline.Member type : owners) {
            for (JavaOutline.Member child : type.children()) {
                names.add(child.name());
                (child.name().equals(name) ? matches : others).add(child);
            }
        }
        if (matches.isEmpty()) {
            return Edit.refused("error: `" + file.address() + "` declares no `" + name
                + "`. It declares: " + String.join(", ", names) + ".");
        }
        JavaOutline.Member target = matches.get(0);
        if (matches.size() > 1) {
            target = null;
            for (JavaOutline.Member match : matches) {
                if (line > 0 && line >= match.startLine() && line <= match.endLine()) {
                    target = match;
                    break;
                }
            }
            if (target == null) {
                StringBuilder sb = new StringBuilder("error: `" + name + "` has " + matches.size()
                    + " overloads in `" + file.address() + "`. Say which by the line it starts "
                    + "on, as " + owner + "#" + name + ":<line>:\n");
                for (JavaOutline.Member match : matches) {
                    sb.append("  ").append(match.header()).append("  // line ")
                        .append(match.startLine()).append('\n');
                }
                return Edit.refused(sb.toString());
            }
        }
        String source = file.source();
        int at = source.indexOf(target.text(), file.outline().offsetOfLine(target.startLine()));
        if (at < 0) {
            return Edit.refused("error: `" + name + "` could not be located in `"
                + file.address() + "`. Nothing was changed.");
        }
        int from = at;
        // The comment directly above it goes with it: it documents what is no longer there.
        String before = source.substring(0, at).stripTrailing();
        if (before.endsWith("*/")) {
            int comment = before.lastIndexOf("/*");
            if (comment >= 0) {
                from = comment;
            }
        }
        while (from > 0 && (source.charAt(from - 1) == ' ' || source.charAt(from - 1) == '\t')) {
            from--;
        }
        int to = at + target.text().length();
        while (to < source.length() && (source.charAt(to) == ' ' || source.charAt(to) == '\t')) {
            to++;
        }
        if (to < source.length() && source.charAt(to) == '\n') {
            to++;
        }
        // One blank line between the neighbours, not two.
        if (from > 1 && source.charAt(from - 1) == '\n' && source.charAt(from - 2) == '\n'
                && to < source.length() && source.charAt(to) == '\n') {
            to++;
        }
        if (from > 1 && source.charAt(from - 1) == '\n' && source.charAt(from - 2) == '\n'
                && source.substring(to).stripLeading().startsWith("}")) {
            from--;
        }
        String changed = source.substring(0, from) + source.substring(to);
        List<String> stillUsing = new ArrayList<>();
        java.util.regex.Pattern use = java.util.regex.Pattern.compile(
            "(?<![\\w$])" + java.util.regex.Pattern.quote(name) + "(?![\\w$])");
        for (JavaOutline.Member other : others) {
            if (use.matcher(JavaOutline.withoutComments(other.text())).find()) {
                stillUsing.add(other.name() + " (line " + other.startLine() + ")");
            }
        }
        int firstLine = source.substring(0, from).split("\n", -1).length;
        String uses = matches.size() > 1 ? ""
            : stillUsing.isEmpty() ? " No other member of the file names it."
                : " Still named, at the lines they had before the removal, in: "
                    + String.join(", ", stillUsing) + ".";
        return new Edit("removed " + name + " from " + file.address() + ": lines " + firstLine
            + "-" + target.endLine() + " are gone, with its documentation comment." + uses,
            file.address(), file.crlf() ? changed.replace("\n", "\r\n") : changed);
    }

    /**
     * Adds {@code text} - a method, a field, a nested type - to a type, before its closing brace.
     *
     * @param where {@code Type}, a path (the file's outermost type), or {@code path#Type}
     */
    public static Edit addMember(Path checkout, String where, String text) {
        String asked = where == null ? "" : where.strip();
        if (asked.isEmpty()) {
            return Edit.refused("error: say which type to add to, as Type or <path>#Type.");
        }
        if (text == null || text.isBlank()) {
            return Edit.refused("error: give the complete declaration to add.");
        }
        int hash = asked.indexOf('#');
        String owner = hash < 0 ? asked : asked.substring(0, hash).strip();
        String typeName = hash >= 0 ? asked.substring(hash + 1).strip()
            : isPath(owner) ? null : owner;
        Object opened = open(checkout, owner);
        if (opened instanceof Edit refused) {
            return refused;
        }
        Opened file = (Opened) opened;
        JavaOutline.Member type = typeOf(file.outline(), typeName);
        if (type == null) {
            return Edit.refused("error: `" + file.address() + "` does not declare `"
                + (typeName == null ? "a type" : typeName) + "`.");
        }
        Split split = Split.of(text);
        String unbalanced = unbalanced(split.body());
        if (unbalanced != null) {
            return Edit.refused("error: " + unbalanced + " Nothing was changed.");
        }
        String source = file.source();
        int start = source.indexOf(type.text(), file.outline().offsetOfLine(type.startLine()));
        int close = start < 0 ? -1 : start + type.text().length() - 1;
        if (close < 0 || source.charAt(close) != '}') {
            return Edit.refused("error: `" + type.name() + "` has no body to add to in `"
                + file.address() + "`. Nothing was changed.");
        }
        String typeIndent = indentAt(source, start);
        String memberIndent = typeIndent + "    ";
        if (!type.children().isEmpty()) {
            int first = file.outline().offsetOfLine(type.children().get(0).startLine());
            int code = first;
            while (code < source.length() && (source.charAt(code) == ' '
                    || source.charAt(code) == '\t')) {
                code++;
            }
            // Only when the first member starts its own line; "class A { int x; }" does not.
            if (type.children().get(0).startLine() > type.startLine()) {
                memberIndent = source.substring(first, code);
            }
        }
        String block = indented(split.body(), memberIndent, true);
        int lineStart = source.lastIndexOf('\n', close - 1) + 1;
        String changed;
        if (source.substring(lineStart, close).isBlank() && lineStart > start) {
            boolean blankBefore = lineStart < 2 || source.charAt(lineStart - 2) == '\n';
            changed = source.substring(0, lineStart)
                + (blankBefore || type.children().isEmpty() ? "" : "\n") + block + "\n"
                + source.substring(lineStart);
        } else {
            changed = source.substring(0, close) + "\n" + block + "\n" + typeIndent
                + source.substring(close);
        }
        changed = withImports(changed, split.imports());
        JavaOutline.Member header = null;
        try {
            header = JavaOutline.of("class X {\n" + split.body() + "\n}").primaryType()
                .children().get(0);
        } catch (RuntimeException unreadable) {                             // noqa
            // named by its text, then
        }
        return new Edit("added " + (header == null ? "the declaration" : header.name()) + " to "
            + type.name() + " in " + file.address() + ", before its closing brace ("
            + block.split("\n", -1).length + " line(s))"
            + (split.imports().isEmpty() ? "" : "; " + split.imports().size()
                + " import line(s) checked") + ".", file.address(),
            file.crlf() ? changed.replace("\n", "\r\n") : changed);
    }

    // --- text ------------------------------------------------------------------------------------

    /** What a worker handed in: import lines it put first, and the declaration. */
    private record Split(List<String> imports, String body) {

        static Split of(String text) {
            List<String> imports = new ArrayList<>();
            String[] lines = text.replace("\r\n", "\n").split("\n", -1);
            int i = 0;
            for (; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.startsWith("import ") && line.endsWith(";")) {
                    imports.add(line);
                } else if (!line.isEmpty()) {
                    break;
                }
            }
            StringBuilder body = new StringBuilder();
            for (; i < lines.length; i++) {
                body.append(lines[i]).append('\n');
            }
            return new Split(imports, body.toString().strip().isEmpty() ? ""
                : body.toString().stripTrailing());
        }
    }

    /** Null when the braces and parentheses of {@code code} close; else what does not. */
    private static String unbalanced(String code) {
        if (code.isBlank()) {
            return "The new text holds no declaration.";
        }
        String bare = JavaOutline.of(code).withoutCommentsOrLiterals();
        int braces = 0;
        int parens = 0;
        for (int i = 0; i < bare.length(); i++) {
            char c = bare.charAt(i);
            braces += c == '{' ? 1 : c == '}' ? -1 : 0;
            parens += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (braces < 0 || parens < 0) {
                break;
            }
        }
        if (braces != 0) {
            return "The new text's braces do not close: " + (braces > 0 ? braces + " `{` more "
                + "than `}`." : "a `}` more than `{`.") + " Give the complete declaration.";
        }
        return parens == 0 ? null : "The new text's parentheses do not close. Give the complete "
            + "declaration.";
    }

    private static String headerOf(String declaration) {
        try {
            return JavaOutline.of("class X {\n" + declaration + "\n}").primaryType().children()
                .get(0).header();
        } catch (RuntimeException unreadable) {                             // noqa
            return null;
        }
    }

    /** The parameter list of a header with its spaces removed; null when it has none. */
    private static String parametersOf(String header) {
        if (header == null) {
            return null;
        }
        String bare = JavaOutline.stripAnnotations(header);
        int open = bare.indexOf('(');
        int close = bare.lastIndexOf(')');
        return open < 0 || close < open ? null
            : bare.substring(open + 1, close).replaceAll("\\s+", "");
    }

    /** The spaces and tabs before {@code offset} on its line, when nothing else is before it. */
    private static String indentAt(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        String before = source.substring(Math.min(lineStart, offset), offset);
        return before.isBlank() ? before : "";
    }

    /**
     * {@code text} moved to {@code indent}, its own relative indentation kept.
     *
     * @param firstLineToo false when the text goes where an indented declaration already began
     */
    private static String indented(String text, String indent, boolean firstLineToo) {
        String[] lines = text.strip().split("\n", -1);
        int own = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                own = Math.min(own, lines[i].length() - lines[i].stripLeading().length());
            }
        }
        StringBuilder sb = new StringBuilder(firstLineToo ? indent : "").append(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            sb.append('\n');
            if (!lines[i].isBlank()) {
                sb.append(indent).append(lines[i].substring(Math.min(own, lines[i].length()))
                    .stripTrailing());
            }
        }
        return sb.toString();
    }

    /** {@code source} with each of {@code imports} it does not already have. */
    private static String withImports(String source, List<String> imports) {
        if (imports.isEmpty()) {
            return source;
        }
        List<String> lines = new ArrayList<>(List.of(source.split("\n", -1)));
        int lastImport = -1;
        int packageLine = -1;
        Set<String> present = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("import ") && line.endsWith(";")) {
                lastImport = i;
                present.add(line.replaceAll("\\s+", " "));
            } else if (packageLine < 0 && line.startsWith("package ") && line.endsWith(";")) {
                packageLine = i;
            } else if (line.startsWith("public ") || line.startsWith("class ")
                    || line.startsWith("final ") || line.startsWith("abstract ")
                    || line.startsWith("interface ") || line.startsWith("@")) {
                break;   // the first type: nothing below it is an import
            }
        }
        List<String> missing = new ArrayList<>();
        for (String wanted : imports) {
            if (present.add(wanted.replaceAll("\\s+", " "))) {
                missing.add(wanted);
            }
        }
        if (missing.isEmpty()) {
            return source;
        }
        if (lastImport >= 0) {
            lines.addAll(lastImport + 1, missing);
        } else {
            List<String> block = new ArrayList<>();
            if (packageLine >= 0) {
                block.add("");
            }
            block.addAll(missing);
            if (packageLine < 0) {
                block.add("");
            }
            lines.addAll(packageLine + 1, block);
        }
        return String.join("\n", lines);
    }
}
