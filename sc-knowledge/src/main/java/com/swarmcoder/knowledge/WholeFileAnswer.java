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

import java.util.List;

/**
 * What a request for a whole file is answered with when the file has a structure a query can
 * return a part of: its outline and the ways to ask for a part, not its text (section 95,
 * CLAUDE.md section 1: a whole file is the last resort).
 *
 * <p><b>Why.</b> One live build's architect made 43 whole-file reads, 248,177 of the 487,401
 * characters it looked up, each resent with every later call: 25 source files of the framework's
 * examples to see how a thing is done there, 5 of its guides after their outlines had been
 * shown, 6 build files after {@code build_of} had answered. {@code read_file} with a bare path
 * returned the file whatever it was. Now the first answer is the file's outline - a Java file's
 * types and members with their lines, a document's sections, what a build file declares, an XML,
 * properties or YAML file's parts, the first lines of anything else that is long - and its last
 * line says what was left out and how to get one part or all of it. The whole file is still
 * one call away: the range of all its lines, which that line spells out.
 *
 * <p>No model and no project-specific rule: the outline is the one {@link JavaOutline},
 * {@link DocumentOutline}, {@link TreeQueries#buildOf} and {@link ResourceQueries} already give.
 */
final class WholeFileAnswer {

    /** A file up to this long is its own answer: an outline and a second call would cost more. */
    static final int WHOLE_UP_TO_CHARS = 1_500;
    /** A file with no structure a query knows is given whole up to this length. */
    static final int UNSTRUCTURED_WHOLE_UP_TO_CHARS = 6_000;
    /** How many first lines of a long file with no known structure are shown. */
    static final int HEAD_LINES = 40;
    /** How much of one line an outline or a head shows. */
    static final int LINE_CHARS = 200;
    /** A matching section up to this long is quoted under a document's outline. */
    static final int MATCHING_SECTION_CHARS = 3_000;

    private WholeFileAnswer() {}

    /**
     * The outline to answer a whole-file request with, or null when the file itself is the
     * answer: it is short, it has no structure, or its outline would be more than half of it.
     *
     * @param address       the file as the asker addressed it, {@code <root>/<path>}
     * @param text          the file
     * @param shapeTool     what the asker's members query is called
     * @param lastQuestion  the words of the asker's latest documentation question in this
     *                      session, or null; a document's best-matching section is marked by it
     */
    static String outlineOf(KnowledgeCurator curator, String address, String text,
                            String shapeTool, String lastQuestion) {
        if (text == null || address == null || text.length() <= WHOLE_UP_TO_CHARS) {
            return null;
        }
        String name = address.substring(address.lastIndexOf('/') + 1).toLowerCase(
            java.util.Locale.ROOT);
        int lines = text.split("\n", -1).length;
        String whole = "the whole file: read_file " + address + ":1-" + lines;
        String outline = null;
        if (name.endsWith(".java")) {
            outline = java(address, text, shapeTool, lines, whole,
                simple -> TreeQueries.lookupNameOf(curator, address, simple));
        } else if (DocumentOutline.isDocument(address)) {
            outline = document(curator, address, text, lastQuestion, whole);
        } else if (name.equals("pom.xml") && curator != null) {
            String declared = new TreeQueries(curator).buildOf(address);
            if (!declared.startsWith("No build file")) {
                outline = declared + "[What the build file declares, not its text: " + text.length()
                    + " characters in " + lines + " lines are left out. Lines: read_file "
                    + address + ":FROM-TO; " + whole + ".]";
            }
        }
        if (outline == null && ResourceQueries.hasOutline(name, text)) {
            outline = address + " - " + text.length() + " characters, " + lines + " lines:\n"
                + ResourceQueries.outlineOf(text, name) + "[Outline, not the file: its text is "
                + "left out. Lines: read_file " + address + ":FROM-TO; " + whole + ".]";
        }
        if (outline == null && text.length() > UNSTRUCTURED_WHOLE_UP_TO_CHARS) {
            outline = head(address, text, lines, whole);
        }
        return outline == null || outline.length() * 2 > text.length() ? null : outline;
    }

    /** A Java file as its types and members, one line each with its lines; null if unreadable. */
    static String java(String address, String text, String shapeTool, int lines, String whole) {
        return java(address, text, shapeTool, lines, whole, null);
    }

    /**
     * @param nameFor gives, for the simple name of the file's first type, what the queries
     *                take for it - the file's address when other types share the name (section
     *                105); null is the simple name
     */
    static String java(String address, String text, String shapeTool, int lines, String whole,
                       java.util.function.UnaryOperator<String> nameFor) {
        JavaOutline outline;
        try {
            outline = JavaOutline.of(text);
        } catch (Exception unreadable) {                                   // noqa
            return null;
        }
        if (outline.types.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(address + " - " + text.length() + " characters, "
            + lines + " lines"
            + (outline.packageName == null || outline.packageName.isBlank() ? ""
                : ", package " + outline.packageName) + ":\n");
        for (JavaOutline.Member type : outline.types) {
            member(sb, type, 0);
        }
        String first = nameFor == null ? outline.types.get(0).name()
            : nameFor.apply(outline.types.get(0).name());
        return sb.append("[Outline, not the file: the bodies, comments and imports are left "
            + "out. ONE member: body_of ").append(first).append("#<member>; several: body_of ")
            .append(first).append("#<first>,<second>; its imports: body_of ").append(first)
            .append("#import; the type without comments and imports: body_of ").append(first).append("; members as the compiler resolves them: ")
            .append(shapeTool).append(' ').append(first).append("; lines: read_file ")
            .append(address).append(":FROM-TO; ").append(whole).append(".]").toString();
    }

    private static void member(StringBuilder sb, JavaOutline.Member member, int depth) {
        String header = JavaOutline.stripAnnotations(member.header());
        sb.append("  ".repeat(depth + 1)).append(cut(header.isBlank() ? member.name() : header))
            .append("  (lines ").append(member.startLine()).append('-').append(member.endLine())
            .append(")\n");
        for (JavaOutline.Member child : member.children()) {
            member(sb, child, depth + 1);
        }
    }

    /** A document as its sections, the one the asker's last question matches best marked. */
    private static String document(KnowledgeCurator curator, String address, String text,
                                   String lastQuestion, String whole) {
        DocumentOutline outline = DocumentOutline.of(address, text);
        if (outline.sections().size() < 2) {
            return null;
        }
        DocumentOutline.Section best = bestSection(curator, address, outline, lastQuestion);
        StringBuilder sb = new StringBuilder("`" + address + "` - " + outline.chars()
            + " chars, " + outline.lineCount() + " lines, " + outline.sections().size()
            + " section(s):\n");
        for (DocumentOutline.Section section : outline.sections()) {
            sb.append("  ".repeat(Math.max(0, section.level() - 1)))
                .append(DocumentOutline.line(section))
                .append(section == best ? "  <- best match for your last documentation "
                    + "question" : "").append('\n');
        }
        if (best != null && best.chars() <= MATCHING_SECTION_CHARS) {
            sb.append("\nSection ").append(best.number()).append(", the best match for `")
                .append(cut(lastQuestion.strip())).append("`:\n").append(outline.textOf(best));
            if (sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
        }
        return sb.append("[Outline, not the document: the other sections' text is left out. ONE "
            + "section: doc_section ").append(address).append("#<number or heading>; the "
            + "sections on a subject: doc_search <words>; ").append(whole).append(".]")
            .toString();
    }

    /**
     * The section of this document the reference index ranks best for the asker's latest
     * documentation question; null without a question or a match. The index's own ranking, the
     * one {@code doc_search} answers from.
     */
    private static DocumentOutline.Section bestSection(KnowledgeCurator curator, String address,
                                                       DocumentOutline outline, String question) {
        if (curator == null || question == null || question.isBlank()) {
            return null;
        }
        List<KnowledgeCurator.ScoredSection> scored;
        try {
            scored = curator.scoreSections(question.strip());
        } catch (RuntimeException noIndex) {                               // noqa
            return null;
        }
        for (KnowledgeCurator.ScoredSection hit : scored) {
            if (address.equals(hit.value().address())) {
                DocumentOutline.Section section = outline.titled(hit.value().heading());
                if (section != null) {
                    return section;
                }
            }
        }
        return null;
    }

    /** The first lines of a long file no query knows the structure of. */
    private static String head(String address, String text, int lines, String whole) {
        String[] all = text.split("\n", -1);
        int shown = Math.min(HEAD_LINES, all.length);
        StringBuilder sb = new StringBuilder(address + " - " + text.length() + " characters, "
            + lines + " lines. Its first " + shown + ":\n");
        for (int i = 0; i < shown; i++) {
            sb.append(cut(all[i].stripTrailing())).append('\n');
        }
        return sb.append("[The first ").append(shown).append(" lines, each cut at ")
            .append(LINE_CHARS).append(" characters; the rest is left out. Lines: read_file ")
            .append(address).append(":FROM-TO; ").append(whole).append(".]").toString();
    }

    private static String cut(String line) {
        return line.length() <= LINE_CHARS ? line
            : line.substring(0, LINE_CHARS) + "... (+" + (line.length() - LINE_CHARS) + " chars)";
    }
}
