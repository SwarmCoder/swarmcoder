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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which lines of which file a lookup's answer holds, read from the two headings the lookups
 * themselves write: {@code // <address>:<first>-<last>} above a member or a type
 * ({@code body_of}, {@code read_file <path>#member}) and {@code Lines <first>-<last> of <n> of
 * `<address>`:} above a range of lines ({@code read_file <path>:FROM-TO}).
 *
 * <p>Why (section 105). The architect keeps part of a lookup for the workers by naming the
 * lookup and lines of its result. In a live build 8 of 20 keeps were refused: the role named
 * the file and the file's own line numbers, which no lookup of that spelling had returned, or
 * a part longer than a finding carries. With this the tool can tell that the lines named lie
 * inside a lookup that was made, say which named members a long part is made of, and name the
 * one lookup that returns exactly that part. Nothing here reads a file or asks a model.
 */
public final class LookupParts {

    private LookupParts() {}

    private static final Pattern RANGE_HEAD =
        Pattern.compile("^Lines (\\d+)-(\\d+) of \\d+ of `([^`]+)`:\\s*$");
    private static final Pattern MEMBER_HEAD =
        Pattern.compile("^// (\\S+?):(\\d+)-(\\d+)( - .*)?\\s*$");

    /**
     * One piece of a file inside a lookup's answer.
     *
     * @param resultLine  the line of the answer, from 1, on which the file's line
     *                    {@code firstLine} stands
     * @param lineForLine false when the answer does not hold the file's lines one for one: a
     *                    type with its comments left out, or its members listed
     */
    public record Part(String address, int firstLine, int lastLine, int resultLine,
                       boolean lineForLine) {}

    /** Lines of a file, both ends included. */
    public record FileRange(String address, int first, int last) {

        public int lines() {
            return last - first + 1;
        }
    }

    /**
     * Lines of a file as a lookup of the session returned them.
     *
     * @param call the lookup as it was made: the tool, a space, its argument
     */
    public record Lines(String call, FileRange range, List<String> text) {}

    /** The pieces of files an answer holds, in the order it holds them. */
    public static List<Part> of(String result) {
        List<Part> parts = new ArrayList<>();
        if (result == null) {
            return parts;
        }
        String[] lines = result.strip().split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher range = RANGE_HEAD.matcher(lines[i]);
            if (range.matches()) {
                parts.add(new Part(range.group(3), number(range.group(1)),
                    number(range.group(2)), i + 2, true));
                continue;
            }
            Matcher member = MEMBER_HEAD.matcher(lines[i]);
            if (member.matches() && number(member.group(3)) >= number(member.group(2))) {
                parts.add(new Part(member.group(1), number(member.group(2)),
                    number(member.group(3)), i + 2, member.group(4) == null));
            }
        }
        return parts;
    }

    private static int number(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLong) {                          // noqa
            return 0;
        }
    }

    /**
     * Which lines of which file the lines {@code first} to {@code last} of an answer are;
     * empty when the answer does not say.
     *
     * @param whole true when the lines are all of the answer
     */
    public static Optional<FileRange> rangeOf(String result, int first, int last, boolean whole) {
        List<Part> parts = of(result);
        if (whole && parts.size() == 1) {
            Part only = parts.get(0);
            return Optional.of(new FileRange(only.address(), only.firstLine(), only.lastLine()));
        }
        for (Part part : parts) {
            if (!part.lineForLine()) {
                continue;
            }
            // The heading itself may be the first line named: the part starts under it.
            int from = first == part.resultLine() - 1 ? part.resultLine() : first;
            int end = part.resultLine() + part.lastLine() - part.firstLine();
            if (from >= part.resultLine() && last <= end && from <= last) {
                return Optional.of(new FileRange(part.address(),
                    part.firstLine() + from - part.resultLine(),
                    part.firstLine() + last - part.resultLine()));
            }
        }
        return Optional.empty();
    }

    /**
     * The lines {@code first} to {@code last} of the FILE {@code file}, when the answer holds
     * them one for one; empty when it does not.
     *
     * @param file the file as it was named; blank is whichever file the answer is of
     * @param call the lookup the answer is of, as it was made
     */
    public static Optional<Lines> linesOf(String result, String file, int first, int last,
                                          String call) {
        if (result == null || first > last) {
            return Optional.empty();
        }
        String[] lines = result.strip().split("\\R", -1);
        for (Part part : of(result)) {
            if (!part.lineForLine() || first < part.firstLine() || last > part.lastLine()
                    || !(file == null || file.isBlank() || sameFile(part.address(), file))) {
                continue;
            }
            int from = part.resultLine() + first - part.firstLine();
            int to = part.resultLine() + last - part.firstLine();
            if (to > lines.length) {
                continue; // the answer was cut before those lines
            }
            return Optional.of(new Lines(call, new FileRange(part.address(), first, last),
                List.of(java.util.Arrays.copyOfRange(lines, from - 1, to))));
        }
        return Optional.empty();
    }

    /** True when an address a lookup wrote and a file a role named are the same file. */
    public static boolean sameFile(String address, String named) {
        String a = clean(address);
        String b = clean(named);
        return !a.isEmpty() && !b.isEmpty()
            && (a.equals(b) || a.endsWith("/" + b) || b.endsWith("/" + a));
    }

    /** A file as it was named, without a part of it: {@code :10-40}, {@code :12}, {@code #x}. */
    public static String fileOf(String named) {
        String text = named == null ? "" : named.strip();
        int hash = text.indexOf('#');
        if (hash >= 0) {
            text = text.substring(0, hash);
        }
        return text.replaceAll(":\\d+(-\\d+)?$", "").strip();
    }

    private static String clean(String path) {
        String text = fileOf(path).replace('\\', '/');
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        return text;
    }

    /**
     * What lines of a file are made of, by name.
     *
     * @param inside the named declarations wholly inside them, outermost only, each as
     *               {@code body_of <name> - <n> lines}
     * @param around the lookup that returns the smallest member holding all of them, as
     *               {@code body_of <name>}; null when no member does
     */
    public record Described(List<String> inside, String around, int aroundLines,
                            int aroundChars) {}

    /**
     * One named declaration of a file with the name a lookup takes for it.
     *
     * @param ask   {@code Type#member}, or {@code Outer.Inner#member}; for a type, its name
     * @param chars how long its text is
     */
    record Named(String ask, int firstLine, int lastLine, int chars, boolean isType) {

        int lines() {
            return lastLine - firstLine + 1;
        }
    }

    /**
     * The named declarations of a Java file that lie wholly inside the lines {@code first} to
     * {@code last}, outermost only: what a part too long to carry is made of.
     *
     * @param topName what a lookup takes for a top-level type of the file, by its simple name
     */
    static List<Named> inside(String source, int first, int last,
                              java.util.function.UnaryOperator<String> topName) {
        List<Named> found = new ArrayList<>();
        JavaOutline outline;
        try {
            outline = JavaOutline.of(source);
        } catch (Exception unreadable) {                                   // noqa
            return found;
        }
        for (JavaOutline.Member type : outline.types) {
            collect(type, topName.apply(type.name()), null, first, last, found);
        }
        return found;
    }

    private static void collect(JavaOutline.Member member, String typeName, String ownerName,
                                int first, int last, List<Named> found) {
        // A type nested in one that is named by its file has no name of its own to ask by: it
        // is asked for as a member of that file's type, and not looked into.
        boolean isType = member.kind() == JavaOutline.Kind.TYPE;
        String ask = isType && typeName != null ? typeName : ownerName + "#" + member.name();
        if (member.startLine() >= first && member.endLine() <= last) {
            if (found.stream().noneMatch(one -> one.ask().equals(ask))) {
                found.add(new Named(ask, member.startLine(), member.endLine(),
                    member.text().length(), isType));
            }
            return;
        }
        if (!isType || typeName == null || member.endLine() < first
                || member.startLine() > last) {
            return;
        }
        for (JavaOutline.Member child : member.children()) {
            collect(child, TreeQueries.isFile(typeName) ? null : typeName + "." + child.name(),
                typeName, first, last, found);
        }
    }

    /**
     * The smallest member - not a type - of a Java file that holds all of the lines
     * {@code first} to {@code last}; empty when none does.
     */
    static Optional<Named> around(String source, int first, int last,
                                  java.util.function.UnaryOperator<String> topName) {
        JavaOutline outline;
        try {
            outline = JavaOutline.of(source);
        } catch (Exception unreadable) {                                   // noqa
            return Optional.empty();
        }
        Named best = null;
        for (JavaOutline.Member type : outline.types) {
            best = smaller(best, around(type, topName.apply(type.name()), first, last));
        }
        return Optional.ofNullable(best);
    }

    private static Named around(JavaOutline.Member type, String typeName, int first, int last) {
        Named best = null;
        for (JavaOutline.Member child : type.children()) {
            if (child.startLine() > first || child.endLine() < last) {
                continue;
            }
            best = smaller(best, child.kind() == JavaOutline.Kind.TYPE
                ? TreeQueries.isFile(typeName) ? null
                    : around(child, typeName + "." + child.name(), first, last)
                : new Named(typeName + "#" + child.name(), child.startLine(), child.endLine(),
                    child.text().length(), false));
        }
        return best;
    }

    private static Named smaller(Named one, Named other) {
        return one == null ? other : other == null || one.lines() <= other.lines() ? one : other;
    }
}
