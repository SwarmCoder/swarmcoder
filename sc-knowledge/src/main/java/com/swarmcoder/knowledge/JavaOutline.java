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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The structure of one Java source file, read without a compiler: its types, and for each type
 * its members with their signature, their body, their visibility and the first sentence of their
 * javadoc. This is what {@code javap -p} gave the worker that reverse-engineered a jar, produced
 * from the source instead — and with the one thing {@code javap} cannot give, the body of the one
 * method a question is actually about.
 *
 * <p>A brace-counting scanner, not a parser. It knows about comments, string and character
 * literals and text blocks, so a brace inside any of those does not count; it does not know
 * about Java grammar beyond that, which is enough: a member is a declaration statement directly
 * inside a type body, ending at its {@code ;} or at the {@code }} that closes its body.
 * Anonymous classes and lambdas live inside method bodies and are never entered.
 */
final class JavaOutline {

    enum Kind { TYPE, CONSTRUCTOR, METHOD, FIELD, INITIALIZER, ENUM_CONSTANTS }

    /**
     * One declaration.
     *
     * @param header   the declaration without its body — modifiers, annotations, type parameters,
     *                 name, parameters, throws clause — with whitespace collapsed
     * @param summary  the first sentence of its javadoc, or ""
     * @param text     the declaration as written, javadoc excluded, body included
     * @param exposed  part of the type's public surface: public or protected, or a member of an
     *                 interface or annotation type, or enum constants
     * @param children the members of a nested or top-level type; empty for everything else
     */
    record Member(Kind kind, String name, String header, String summary, String text,
                  boolean exposed, int startLine, int endLine, List<Member> children) {

        /** Every descendant member, depth first, this one excluded. */
        List<Member> descendants() {
            List<Member> all = new ArrayList<>();
            for (Member child : children) {
                all.add(child);
                all.addAll(child.descendants());
            }
            return all;
        }

        boolean hasBody() {
            return text.endsWith("}");
        }
    }

    private final String source;
    private final byte[] mask;
    private final int[] lineStarts;
    private final List<int[]> comments = new ArrayList<>();

    final String packageName;
    final List<String> imports = new ArrayList<>();
    final List<Member> types = new ArrayList<>();
    /** The source with a leading license or copyright comment removed. */
    final String withoutLicense;
    final int lineCount;

    private static final byte CODE = 0;
    private static final byte COMMENT = 1;
    private static final byte LITERAL = 2;

    static JavaOutline of(String source) {
        return new JavaOutline(source == null ? "" : source.replace("\r\n", "\n"));
    }

    private JavaOutline(String source) {
        this.source = source;
        this.mask = maskOf(source, comments);
        this.lineStarts = lineStartsOf(source);
        this.lineCount = lineStarts.length;
        this.withoutLicense = stripLicense();
        String pkg = "";
        for (Member statement : statements(0, source.length(), null, false, false)) {
            String head = statement.header();
            if (head.startsWith("package ")) {
                pkg = head.substring(8).strip();
            } else if (head.startsWith("import ")) {
                imports.add(head.substring(7).strip());
            } else if (statement.kind() == Kind.TYPE) {
                types.add(statement);
            }
        }
        this.packageName = pkg;
    }

    /** The outermost type, or null for a file with none. */
    Member primaryType() {
        return types.isEmpty() ? null : types.get(0);
    }

    String source() {
        return source;
    }

    /**
     * The source with every comment and every string, character and text-block literal blanked to
     * spaces, newlines kept. What is left is code and nothing else, so a name found in it is a name
     * the compiler sees — which is the difference between reading a type reference and reading a
     * word in a javadoc sentence.
     */
    String withoutCommentsOrLiterals() {
        StringBuilder sb = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            sb.append(mask[i] == CODE || c == '\n' ? c : ' ');
        }
        return sb.toString();
    }

    /**
     * {@code text} - a declaration, a type, a file - without its comments and without the blank
     * lines, and nothing else changed: what the compiler reads, for a reader who wants the code
     * of a whole type and not its prose (run 82, 2026-10-04). A comment mark inside a string is
     * not a comment.
     */
    static String withoutComments(String text) {
        String source = text == null ? "" : text.replace("\r\n", "\n");
        byte[] mask = maskOf(source, new ArrayList<>());
        StringBuilder code = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            if (mask[i] != COMMENT) {
                code.append(source.charAt(i));
            }
        }
        StringBuilder sb = new StringBuilder(code.length());
        for (String line : code.toString().split("\n", -1)) {
            String kept = line.stripTrailing();
            if (!kept.isEmpty()) {
                sb.append(kept).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /** The offset at which the 1-based {@code line} starts. */
    int offsetOfLine(int line) {
        return lineStarts[Math.max(0, Math.min(lineStarts.length - 1, line - 1))];
    }

    /** The 1-based line at which the code after any license header starts. */
    int firstCodeLine() {
        return lineOf(source.length() - withoutLicense.length());
    }

    int lineOf(int offset) {
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low + 1;
    }

    // --- scanning --------------------------------------------------------------------------------

    private List<Member> statements(int from, int to, String enclosing,
                                    boolean enclosingIsInterface, boolean enclosingIsEnum) {
        List<Member> members = new ArrayList<>();
        int position = from;
        if (enclosingIsEnum) {
            position = enumConstants(from, to, members);
        }
        while (true) {
            int start = skipBlank(position, to);
            if (start >= to) {
                break;
            }
            int parens = 0;
            int header = -1;   // where the header ends
            char terminator = 0;
            for (int i = start; i < to; i++) {
                if (mask[i] != CODE) {
                    continue;
                }
                char c = source.charAt(i);
                if (c == '(') {
                    parens++;
                } else if (c == ')') {
                    parens--;
                } else if (parens == 0 && (c == '{' || c == ';' || c == '=')) {
                    header = i;
                    terminator = c;
                    break;
                }
            }
            if (header < 0) {
                members.add(member(start, to, codeOnly(start, to), enclosing,
                    enclosingIsInterface, false, List.of()));
                break;
            }
            int end;
            List<Member> children = List.of();
            String headerText = codeOnly(start, header);
            if (terminator == '{') {
                int close = matchingBrace(header, to);
                end = Math.min(to, close + 1);
                if (isTypeHeader(headerText)) {
                    children = statements(header + 1, close, typeName(headerText),
                        isInterfaceHeader(headerText), isEnumHeader(headerText));
                }
            } else if (terminator == '=') {
                end = Math.min(to, statementEnd(header, to) + 1);
            } else {
                end = header + 1;
            }
            members.add(member(start, end, headerText, enclosing, enclosingIsInterface,
                terminator == '{', children));
            position = end;
        }
        return members;
    }

    /** Enum constants come first and end at the first {@code ;} outside any nesting. */
    private int enumConstants(int from, int to, List<Member> members) {
        int start = skipBlank(from, to);
        int end = statementEnd(start - 1, to);
        String text = source.substring(start, Math.min(end, to)).strip();
        if (!text.isEmpty()) {
            String collapsed = collapse(codeOnly(start, Math.min(end, to)));
            members.add(new Member(Kind.ENUM_CONSTANTS, "constants",
                collapsed.length() > 400 ? collapsed.substring(0, 400) + " …" : collapsed,
                summaryBefore(start), text, true, lineOf(start), lineOf(Math.min(end, to) - 1),
                List.of()));
        }
        return Math.min(to, end + 1);
    }

    private Member member(int start, int end, String headerText, String enclosing,
                          boolean enclosingIsInterface, boolean hasBody, List<Member> children) {
        String header = collapse(headerText);
        String bare = stripAnnotations(header);
        Kind kind;
        String name;
        if (isTypeHeader(bare)) {
            kind = Kind.TYPE;
            name = typeName(bare);
        } else if (bare.isEmpty() || bare.equals("static")) {
            kind = Kind.INITIALIZER;
            name = bare.isEmpty() ? "instance initializer" : "static initializer";
        } else if (bare.indexOf('(') >= 0) {
            name = identifierBefore(bare, bare.indexOf('('));
            kind = name.equals(enclosing) ? Kind.CONSTRUCTOR : Kind.METHOD;
        } else if (hasBody && enclosing != null && lastIdentifier(bare).equals(enclosing)) {
            kind = Kind.CONSTRUCTOR; // a record's compact constructor: "public Point {"
            name = enclosing;
        } else {
            kind = Kind.FIELD;
            name = lastIdentifier(bare);
        }
        boolean exposed = kind == Kind.ENUM_CONSTANTS
            || hasModifier(bare, "public") || hasModifier(bare, "protected")
            || (enclosingIsInterface && !hasModifier(bare, "private"));
        String text = source.substring(start, end).strip();
        return new Member(kind, name, header, summaryBefore(start), text, exposed,
            lineOf(start), lineOf(Math.max(start, end - 1)), children);
    }

    private static final Pattern TYPE_HEADER = Pattern.compile(
        "(?:^|[\\s,])(?:@interface|class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    private static boolean isTypeHeader(String header) {
        String bare = stripAnnotations(collapse(header));
        int paren = bare.indexOf('(');
        Matcher m = TYPE_HEADER.matcher(bare);
        return m.find() && (paren < 0 || m.start() < paren);
    }

    private static boolean isInterfaceHeader(String header) {
        String bare = stripAnnotations(collapse(header));
        return bare.matches("(?s).*(?:^|\\s)(?:@interface|interface)\\s+[A-Za-z_$].*");
    }

    private static boolean isEnumHeader(String header) {
        String bare = stripAnnotations(collapse(header));
        return bare.matches("(?s).*(?:^|\\s)enum\\s+[A-Za-z_$].*");
    }

    private static String typeName(String header) {
        Matcher m = TYPE_HEADER.matcher(stripAnnotations(collapse(header)));
        return m.find() ? m.group(1) : "?";
    }

    /** Drops leading annotations, with their arguments, from a collapsed header. */
    static String stripAnnotations(String header) {
        String rest = header.strip();
        while (rest.startsWith("@") && !rest.startsWith("@interface")) {
            int i = 1;
            while (i < rest.length()
                   && (Character.isJavaIdentifierPart(rest.charAt(i)) || rest.charAt(i) == '.')) {
                i++;
            }
            if (i < rest.length() && rest.charAt(i) == '(') {
                int depth = 0;
                for (; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')' && --depth == 0) {
                        i++;
                        break;
                    }
                }
            }
            rest = rest.substring(Math.min(i, rest.length())).strip();
        }
        return rest;
    }

    private static boolean hasModifier(String bare, String modifier) {
        return bare.matches("(?s)(?:^|.*\\s)" + modifier + "(?:\\s.*|$)");
    }

    private static String identifierBefore(String text, int index) {
        int end = index;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, end);
    }

    private static String lastIdentifier(String bare) {
        String trimmed = bare.replaceAll("\\[\\s*\\]", "").strip();
        int comma = trimmed.indexOf(',');
        if (comma > 0 && trimmed.indexOf('<') < 0) {
            trimmed = trimmed.substring(0, comma).strip();
        }
        return identifierBefore(trimmed, trimmed.length());
    }

    static String collapse(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    /** The text between two offsets with its comments blanked — a header without its remarks. */
    private String codeOnly(int from, int to) {
        StringBuilder sb = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            sb.append(mask[i] == COMMENT ? ' ' : source.charAt(i));
        }
        return sb.toString();
    }

    private int skipBlank(int from, int to) {
        int i = from;
        while (i < to && (mask[i] == COMMENT || Character.isWhitespace(source.charAt(i)))) {
            i++;
        }
        return i;
    }

    private int matchingBrace(int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            if (mask[i] != CODE) {
                continue;
            }
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return to - 1;
    }

    /** The {@code ;} that ends a statement starting after {@code from}, nesting respected. */
    private int statementEnd(int from, int to) {
        int braces = 0;
        int parens = 0;
        for (int i = from + 1; i < to; i++) {
            if (mask[i] != CODE) {
                continue;
            }
            char c = source.charAt(i);
            if (c == '{') {
                braces++;
            } else if (c == '}') {
                braces--;
            } else if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens--;
            } else if (c == ';' && braces == 0 && parens == 0) {
                return i;
            }
        }
        return to;
    }

    // --- javadoc -----------------------------------------------------------------------------

    /** The first sentence of the javadoc that immediately precedes {@code offset}, or "". */
    private String summaryBefore(int offset) {
        for (int c = comments.size() - 1; c >= 0; c--) {
            int[] span = comments.get(c);
            if (span[1] > offset) {
                continue;
            }
            if (!source.substring(span[1], offset).isBlank()) {
                return "";
            }
            String comment = source.substring(span[0], span[1]);
            return comment.startsWith("/**") ? firstSentence(comment) : "";
        }
        return "";
    }

    static String firstSentence(String javadoc) {
        String body = javadoc.strip();
        if (body.startsWith("/**")) {
            body = body.substring(3);
        }
        if (body.endsWith("*/")) {
            body = body.substring(0, body.length() - 2);
        }
        StringBuilder joined = new StringBuilder();
        for (String line : body.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("*")) {
                stripped = stripped.substring(1).strip();
            }
            if (stripped.startsWith("@")) {
                break; // the tag block; nothing after it is prose
            }
            joined.append(stripped).append(' ');
        }
        String text = joined.toString()
            .replaceAll("\\{@\\w+\\s+#?([^}]*)\\}", "$1")
            .replaceAll("<pre>.*?</pre>", " ")
            .replaceAll("<[^>]+>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replaceAll("\\s+", " ").strip();
        Matcher end = Pattern.compile("[.!?](?=\\s|$)").matcher(text);
        if (end.find()) {
            text = text.substring(0, end.end());
        }
        if (text.length() <= 120) {
            return text;
        }
        int space = text.lastIndexOf(' ', 117);
        return text.substring(0, space > 60 ? space : 117).strip() + " …";
    }

    private String stripLicense() {
        if (comments.isEmpty()) {
            return source;
        }
        int[] first = comments.get(0);
        if (!source.substring(0, first[0]).isBlank()) {
            return source;
        }
        String text = source.substring(first[0], first[1]).toLowerCase(Locale.ROOT);
        if (text.contains("copyright") || text.contains("licen")) {
            return source.substring(first[1]).stripLeading();
        }
        return source;
    }

    // --- masks ----------------------------------------------------------------------------------

    private static byte[] maskOf(String s, List<int[]> comments) {
        byte[] mask = new byte[s.length()];
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int end = s.indexOf('\n', i);
                end = end < 0 ? n : end;
                fill(mask, i, end, COMMENT);
                comments.add(new int[] {i, end});
                i = end;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                fill(mask, i, end, COMMENT);
                comments.add(new int[] {i, end});
                i = end;
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                fill(mask, i, end, LITERAL);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c && s.charAt(j) != '\n') {
                    if (s.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                int end = Math.min(n, j + 1);
                fill(mask, i, end, LITERAL);
                i = end;
            } else {
                i++;
            }
        }
        return mask;
    }

    private static void fill(byte[] mask, int from, int to, byte value) {
        for (int i = from; i < to && i < mask.length; i++) {
            mask[i] = value;
        }
    }

    private static int[] lineStartsOf(String s) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] array = new int[starts.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = starts.get(i);
        }
        return array;
    }
}
