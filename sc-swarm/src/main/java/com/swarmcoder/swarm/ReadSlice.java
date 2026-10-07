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
package com.swarmcoder.swarm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading part of a file, and cutting long output so that both ends of it survive (2026-10-02).
 *
 * <p><b>Why.</b> Harness run 66 sent the workers' model 5,419,009 prompt tokens to have it write
 * 102,036. Every turn resends the conversation, and the conversation is mostly tool results: whole
 * files read to look at one method, and build logs kept from the top when the errors are at the
 * bottom. A worker could only ask for a whole file, and a long result was cut by keeping its
 * first eight thousand characters and nothing else.
 *
 * <p><b>What a worker can ask for now,</b> in the one {@code path} argument its {@code read} tool
 * always had - so the tool's shape is unchanged and a plain path still means the whole file:
 *
 * <ul>
 *   <li>{@code src/App.java:120-180} - those lines;
 *   <li>{@code src/App.java#save} - the one method or type of that name, with the comment and
 *       annotations above it.
 * </ul>
 *
 * <p>A file too long for one result comes back up to a whole line, and says which lines it was
 * and how to ask for the next ones. Command output too long for one result keeps its beginning
 * AND its end - the first error and the summary are at opposite ends of a build log - and says
 * how to see the middle.
 */
final class ReadSlice {

    private static final Pattern RANGE = Pattern.compile("^(.*?):(\\d+)(?:-(\\d+))?$");
    private static final Pattern SYMBOL = Pattern.compile("^(.*?)#([A-Za-z_$][\\w$]*)$");

    private ReadSlice() { }

    /**
     * What was asked for.
     *
     * @param path   the file, without the range or the name
     * @param from   first line wanted, 1-based; 0 when no range was given
     * @param to     last line wanted, inclusive; 0 for "to the end"
     * @param symbol the method or type wanted; null when none was named
     */
    record Request(String path, int from, int to, String symbol) {

        boolean wholeFile() {
            return from == 0 && symbol == null;
        }
    }

    /** Splits {@code src/App.java:10-40} and {@code src/App.java#save} into file and part. */
    static Request parse(String asked) {
        String given = asked == null ? "" : asked.strip();
        Matcher range = RANGE.matcher(given);
        if (range.matches() && !range.group(1).isBlank()) {
            int from = parseLine(range.group(2));
            int to = range.group(3) == null ? from : parseLine(range.group(3));
            if (from > 0) {
                return new Request(range.group(1), from, Math.max(from, to), null);
            }
        }
        Matcher symbol = SYMBOL.matcher(given);
        if (symbol.matches() && !symbol.group(1).isBlank()) {
            return new Request(symbol.group(1), 0, 0, symbol.group(2));
        }
        return new Request(given, 0, 0, null);
    }

    private static int parseLine(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The part of {@code text} that was asked for, at most about {@code maxChars} long.
     *
     * <p>A whole file that fits is returned exactly as it is, byte for byte.
     */
    static String render(String text, Request request, int maxChars) {
        String path = request.path();
        if (request.wholeFile() && text.length() <= maxChars) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        int total = lines.length > 0 && lines[lines.length - 1].isEmpty()
            ? lines.length - 1 : lines.length;
        int first;
        int last;
        String heading;
        if (request.symbol() != null) {
            int[] block = findBlock(lines, total, request.symbol());
            if (block == null) {
                return "error: no method or type named '" + request.symbol() + "' was found in "
                    + path + " (" + total + " lines). Read part of it with " + path
                    + ":FROM-TO, or the whole file with " + path + ".";
            }
            first = block[0];
            last = block[1];
            heading = "'" + request.symbol() + "' in " + path;
        } else if (request.from() > 0) {
            if (request.from() > total) {
                return "error: " + path + " has " + total + " lines; there is no line "
                    + request.from() + ".";
            }
            first = request.from();
            last = Math.min(total, request.to());
            heading = path;
        } else {
            first = 1;
            last = total;
            heading = path;
        }
        StringBuilder body = new StringBuilder();
        int shownTo = first - 1;
        for (int line = first; line <= last; line++) {
            String next = lines[line - 1];
            if (body.length() + next.length() + 1 > maxChars && line > first) {
                break;
            }
            body.append(next).append('\n');
            shownTo = line;
        }
        if (body.length() > maxChars) {
            body.setLength(maxChars); // one line longer than a whole result
            body.append('\n');
        }
        StringBuilder out = new StringBuilder();
        if (!request.wholeFile()) {
            out.append("[").append(heading).append(": lines ").append(first).append('-')
                .append(shownTo).append(" of ").append(total).append("]\n");
        }
        out.append(body);
        if (shownTo < last) {
            out.append("[shown: lines ").append(first).append('-').append(shownTo).append(" of ")
                .append(total).append(" - it is too long for one read. Read on with ")
                .append(path).append(':').append(shownTo + 1).append('-')
                .append(Math.min(total, shownTo + (shownTo - first + 1)))
                .append(", or read one method or type by name with ").append(path)
                .append("#name.]");
        }
        return out.toString();
    }

    /**
     * The lines of the method or type called {@code name}: {first, last}, 1-based and inclusive,
     * with the comment and annotations directly above it; null when nothing declares that name.
     *
     * <p>Read off the text, not parsed: a type by its keyword, a method by its name followed by
     * an opening parenthesis on a line that is not a call - nothing assigned, nothing called
     * before it - and whose statement opens a block before it ends. Good for the languages the
     * workers write (Java, and anything else with braces); a file it cannot make sense of gets
     * "not found", and the worker reads a line range instead.
     */
    static int[] findBlock(String[] lines, int total, String name) {
        Pattern type = Pattern.compile("\\b(class|interface|enum|record|@interface)\\s+"
            + Pattern.quote(name) + "\\b");
        Pattern method = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "\\s*\\(");
        int declared = -1;
        for (int i = 0; i < total && declared < 0; i++) {
            if (type.matcher(lines[i]).find() && !isComment(lines[i])) {
                declared = i;
            }
        }
        for (int i = 0; i < total && declared < 0; i++) {
            Matcher m = method.matcher(lines[i]);
            if (!m.find() || isComment(lines[i])) {
                continue;
            }
            String before = lines[i].substring(0, m.start()).strip();
            if (before.contains("=") || before.contains("(") || before.startsWith("return")
                    || before.endsWith("new") || before.startsWith("throw")) {
                continue; // a call, not a declaration
            }
            char ends = firstOf(lines, total, i, m.end());
            if (ends == '{' || ends == ':' || ends == ';' && !before.isEmpty()) {
                declared = i;
            }
        }
        if (declared < 0) {
            return null;
        }
        int first = declared;
        while (first > 0) {
            String above = lines[first - 1].strip();
            if (above.startsWith("@") || above.startsWith("*") || above.startsWith("/*")
                    || above.startsWith("//")) {
                first--;
            } else {
                break;
            }
        }
        return new int[] {first + 1, blockEnd(lines, total, declared) + 1};
    }

    private static boolean isComment(String line) {
        String stripped = line.strip();
        return stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*");
    }

    /** Which of '{', ';' or a line-ending ':' comes first after a declaration's name. */
    private static char firstOf(String[] lines, int total, int line, int column) {
        for (int i = line; i < total && i < line + 20; i++) {
            String text = i == line ? lines[i].substring(Math.min(column, lines[i].length()))
                : lines[i];
            for (int c = 0; c < text.length(); c++) {
                char ch = text.charAt(c);
                if (ch == '{' || ch == ';') {
                    return ch;
                }
            }
            if (text.stripTrailing().endsWith(":")) {
                return ':'; // a block opened by indentation
            }
        }
        return ' ';
    }

    /** The last line (0-based) of the block declared on {@code declared}. */
    private static int blockEnd(String[] lines, int total, int declared) {
        int depth = 0;
        boolean opened = false;
        for (int i = declared; i < total; i++) {
            String text = lines[i];
            for (int c = 0; c < text.length(); c++) {
                char ch = text.charAt(c);
                if (ch == '{') {
                    depth++;
                    opened = true;
                } else if (ch == '}') {
                    depth--;
                } else if (ch == ';' && !opened) {
                    return i; // a declaration with no body
                }
            }
            if (opened && depth <= 0) {
                return i;
            }
            if (!opened && i > declared + 20) {
                break;
            }
        }
        if (opened) {
            return total - 1;
        }
        // No braces at all: the block is what is indented under the declaration.
        int indent = indentOf(lines[declared]);
        int end = declared;
        for (int i = declared + 1; i < total; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            if (indentOf(lines[i]) <= indent) {
                break;
            }
            end = i;
        }
        return end;
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && Character.isWhitespace(line.charAt(n))) {
            n++;
        }
        return n;
    }

    /** How much of a long command output's beginning is kept; the rest of the room is its end. */
    private static final int HEAD_SHARE_PERCENT = 30;

    /**
     * {@code output} when it fits; otherwise its beginning and its end, each cut at a line, with
     * a note between them saying how much is missing and how to see it.
     */
    static String headAndTail(String output, int maxChars) {
        if (output == null || output.length() <= maxChars) {
            return output == null ? "" : output;
        }
        int headChars = maxChars * HEAD_SHARE_PERCENT / 100;
        int tailChars = maxChars - headChars;
        int headEnd = output.lastIndexOf('\n', headChars);
        if (headEnd < headChars / 2) {
            headEnd = headChars;
        }
        int tailStart = output.indexOf('\n', output.length() - tailChars);
        if (tailStart < 0 || tailStart > output.length() - tailChars / 2) {
            tailStart = output.length() - tailChars;
        } else {
            tailStart++;
        }
        int cut = tailStart - headEnd;
        return output.substring(0, headEnd)
            + "\n[... " + cut + " characters of output are not shown here: this is its beginning "
            + "and its end. To see the part you need, run the command again and keep only those "
            + "lines - pipe it through a filter (grep or findstr for a word such as ERROR, or "
            + "tail for the last lines) instead of printing all of it ...]\n"
            + output.substring(tailStart);
    }
}
