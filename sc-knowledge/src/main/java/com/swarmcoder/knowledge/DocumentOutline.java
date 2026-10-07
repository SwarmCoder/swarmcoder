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
 * The structure of one written document, read with no model: its headings, each with the lines
 * its section spans and how many characters that is (live run 82, 2026-10-04).
 *
 * <p>The syntax tree covers code. A project's documents - requirements, architecture, a
 * framework primer - had no structure a role could ask about, so a role that wanted one
 * paragraph of the architecture read all of it: the architect of run 82 read seven documents
 * whole, 82,000 characters, and sent them again on every later call. This is the same thing
 * {@link JavaOutline} is for a source file.
 *
 * <p>Markdown ({@code #} headings) and AsciiDoc ({@code =} headings); a heading inside a fenced
 * code block is not one. A section runs from its heading to the line before the next heading of
 * the same or a higher level, so it holds its subsections.
 */
public final class DocumentOutline {

    /**
     * One heading and what is under it.
     *
     * @param level     1 for the outermost heading; 0 for the text before the first heading
     * @param number    its place among the headings, counted: {@code 2.3} is the third heading
     *                  under the second
     * @param ownNumber the number the heading itself begins with ({@code 4.2 Data flow}), or ""
     * @param title     the heading's text
     * @param startLine the heading's line, 1-based
     * @param endLine   the last line of the section, its subsections included
     * @param chars     the section's characters, its subsections included
     */
    public record Section(int level, String number, String ownNumber, String title, int startLine,
                          int endLine, int chars) {}

    private static final Pattern MARKDOWN = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern ASCIIDOC = Pattern.compile("^(={1,6})\\s+(.*?)\\s*$");
    private static final Pattern OWN_NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)*)[.):]?\\s+.*");

    private final String[] lines;
    private final List<Section> sections = new ArrayList<>();
    private final int chars;

    private DocumentOutline(String text, boolean asciidoc) {
        String normal = text == null ? "" : text.replace("\r\n", "\n");
        this.chars = normal.length();
        this.lines = normal.split("\n", -1);
        record Head(int level, String title, int line) {}
        List<Head> heads = new ArrayList<>();
        boolean inFence = false;
        boolean textBefore = false;
        Pattern heading = asciidoc ? ASCIIDOC : MARKDOWN;
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].strip();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")
                    || (asciidoc && stripped.startsWith("----"))) {
                inFence = !inFence;
            }
            Matcher m = inFence ? null : heading.matcher(lines[i]);
            if (m != null && m.matches() && !m.group(2).isBlank()) {
                heads.add(new Head(m.group(1).length(), m.group(2).strip(), i + 1));
            } else if (heads.isEmpty() && !stripped.isEmpty()) {
                textBefore = true;
            }
        }
        if (textBefore) {
            int end = heads.isEmpty() ? lines.length : heads.get(0).line() - 1;
            sections.add(new Section(0, "0", "", "(text before the first heading)", 1, end,
                charsOf(1, end)));
        }
        // The outermost level the document uses is depth 1, whatever number of marks it writes.
        int outermost = heads.stream().mapToInt(Head::level).min().orElse(1);
        int[] counters = new int[8];
        for (int i = 0; i < heads.size(); i++) {
            Head head = heads.get(i);
            int depth = head.level() - outermost + 1;
            counters[depth]++;
            for (int deeper = depth + 1; deeper < counters.length; deeper++) {
                counters[deeper] = 0;
            }
            StringBuilder number = new StringBuilder();
            for (int d = 1; d <= depth; d++) {
                number.append(number.isEmpty() ? "" : ".").append(Math.max(1, counters[d]));
            }
            int end = lines.length;
            for (int j = i + 1; j < heads.size(); j++) {
                if (heads.get(j).level() <= head.level()) {
                    end = heads.get(j).line() - 1;
                    break;
                }
            }
            Matcher own = OWN_NUMBER.matcher(head.title());
            sections.add(new Section(depth, number.toString(), own.matches() ? own.group(1) : "",
                head.title(), head.line(), end, charsOf(head.line(), end)));
        }
    }

    /** The outline of a markdown document. */
    public static DocumentOutline of(String text) {
        return new DocumentOutline(text, false);
    }

    /** The outline of the document at {@code path}, read by what its name says it is. */
    public static DocumentOutline of(String path, String text) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return new DocumentOutline(text, lower.endsWith(".adoc") || lower.endsWith(".asciidoc"));
    }

    /** True for a file this class reads the structure of. */
    public static boolean isDocument(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".adoc")
            || lower.endsWith(".asciidoc");
    }

    private int charsOf(int from, int to) {
        int sum = 0;
        for (int i = from; i <= to && i <= lines.length; i++) {
            sum += lines[i - 1].length() + 1;
        }
        return sum;
    }

    public List<Section> sections() {
        return List.copyOf(sections);
    }

    public int lineCount() {
        return lines.length;
    }

    public int chars() {
        return chars;
    }

    /** The lines {@code from} to {@code to}, as written. */
    public String lines(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(1, from); i <= to && i <= lines.length; i++) {
            sb.append(lines[i - 1]).append('\n');
        }
        return sb.toString();
    }

    /** A section's text, its heading and its subsections included. */
    public String textOf(Section section) {
        return lines(section.startLine(), section.endLine());
    }

    /** The sections directly under {@code parent}. */
    public List<Section> childrenOf(Section parent) {
        List<Section> children = new ArrayList<>();
        for (Section candidate : sections) {
            if (candidate.level() == parent.level() + 1
                    && candidate.startLine() > parent.startLine()
                    && candidate.endLine() <= parent.endLine()) {
                children.add(candidate);
            }
        }
        return children;
    }

    /** The outermost sections: what a one-line summary of the document names. */
    public List<Section> outermost() {
        List<Section> top = new ArrayList<>();
        for (Section section : sections) {
            if (section.level() == 1) {
                top.add(section);
            }
        }
        // A document that is one title over everything is described by what is under the title.
        return top.size() == 1 && !childrenOf(top.get(0)).isEmpty() ? childrenOf(top.get(0)) : top;
    }

    /**
     * The sections {@code key} names, by the first of these that matches anything: the number
     * the heading itself begins with, the heading's text, its counted number, a heading that
     * contains the text. Letter case and the heading marks are ignored.
     */
    public List<Section> find(String key) {
        String wanted = normal(key);
        if (wanted.isEmpty()) {
            return List.of();
        }
        String asNumber = wanted.replaceAll("[.):]+$", "");
        List<List<Section>> byRule = List.of(new ArrayList<>(), new ArrayList<>(),
            new ArrayList<>(), new ArrayList<>());
        for (Section section : sections) {
            String title = normal(section.title());
            if (!section.ownNumber().isEmpty() && section.ownNumber().equals(asNumber)) {
                byRule.get(0).add(section);
            }
            if (title.equals(wanted)) {
                byRule.get(1).add(section);
            }
            if (section.number().equals(asNumber)) {
                byRule.get(2).add(section);
            }
            if (title.contains(wanted)) {
                byRule.get(3).add(section);
            }
        }
        for (List<Section> found : byRule) {
            if (!found.isEmpty()) {
                return found;
            }
        }
        // The outline's own line, or its start: "1.2 The basics", "1.2  The basics  (lines
        // 10-36, 900 chars)". Run 85: seven of eleven doc_section calls were written that way,
        // were told "no section", and the document was then read whole. The number decides;
        // the words after it only have to belong to that section when the number is not one.
        String spoken = wanted.replaceFirst("\\s*\\(lines \\d+-\\d+, \\d+ chars\\)$", "");
        int space = spoken.indexOf(' ');
        if (space > 0) {
            String head = spoken.substring(0, space).replaceAll("[.):]+$", "");
            String rest = spoken.substring(space + 1).strip();
            List<Section> numbered = new ArrayList<>();
            List<Section> titled = new ArrayList<>();
            for (Section section : sections) {
                String title = normal(section.title());
                boolean itsNumber = section.number().equals(head)
                    || (!section.ownNumber().isEmpty() && section.ownNumber().equals(head));
                if (itsNumber && (title.equals(rest) || title.contains(rest)
                        || (!title.isEmpty() && rest.contains(title)))) {
                    numbered.add(section);
                }
                if (title.equals(rest)) {
                    titled.add(section);
                }
            }
            if (!numbered.isEmpty()) {
                return numbered;
            }
            if (!titled.isEmpty()) {
                return titled;
            }
        }
        return List.of();
    }

    /** The first section whose heading is exactly {@code title}, or null. */
    public Section titled(String title) {
        String wanted = normal(title);
        for (Section section : sections) {
            if (normal(section.title()).equals(wanted)) {
                return section;
            }
        }
        return null;
    }

    private static String normal(String text) {
        return text == null ? "" : text.strip().replaceFirst("^[#=]+\\s*", "")
            .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** One line of an outline: number, heading, lines, size. */
    public static String line(Section section) {
        return section.number() + "  " + section.title() + "  (lines " + section.startLine() + "-"
            + section.endLine() + ", " + section.chars() + " chars)";
    }
}
