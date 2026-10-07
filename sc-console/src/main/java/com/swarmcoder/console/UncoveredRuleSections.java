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
package com.swarmcoder.console;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which headed sections of a technical document state rules that no stated rule was drawn from.
 *
 * <p><b>Why</b> (live harness runs 56 and 58, 2026-10-01). A technical document had nine
 * sections. The analyst stated thirteen rules, every one from the first six: the stack, what is
 * forbidden, the modules, the wire, the data model, storage. It stated none from the last three —
 * the screen, how the work is built and checked, and "Tests", which said in so many words that a
 * service is never constructed by hand in a test and named the test server to get it from. A
 * valid, parseable reply with thirteen rules in it gave nothing a reason to look again, so the
 * test author was handed "this project's standing rules" without the one rule written for it, and
 * wrote exactly the test the document forbade.
 *
 * <p>So the reply is checked against the document, by text alone: every rule carries the excerpt
 * it was drawn from, and a section that uses the words rules are written in (must, never, only,
 * do not, forbidden …) while sharing no run of words with any rule's excerpt or wording was passed
 * over. The analyst is asked once more, for those sections by name.
 *
 * <p>Deliberately conservative: a document with fewer than two headings has no sections to
 * compare, and says nothing here.
 */
final class UncoveredRuleSections {

    private UncoveredRuleSections() {}

    /** A headed section: the heading as written (without its marks) and everything under it. */
    record Section(String heading, String body) {}

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+\\S.*$");

    /** The words a rule is written in. A section with none of them is narrative, not rules. */
    private static final Pattern RULE_WORDS = Pattern.compile(
        "\\b(must|never|only|always|do not|don't|does not|may not|cannot|forbidden|not allowed)\\b",
        Pattern.CASE_INSENSITIVE);

    /** How many consecutive words a rule must share with a section to count as drawn from it. */
    private static final int SHINGLE = 5;

    /**
     * @param documentText the technical document, as ingested
     * @param ruleTexts    every stated rule's excerpt and wording
     * @return the sections that state rules and that no rule was drawn from, in document order
     */
    static List<Section> in(String documentText, List<String> ruleTexts) {
        List<Section> sections = sections(documentText);
        if (sections.size() < 2) {
            return List.of();
        }
        Set<String> shingles = new HashSet<>();
        for (String text : ruleTexts) {
            shingles.addAll(shingles(text));
        }
        List<Section> uncovered = new ArrayList<>();
        for (Section section : sections) {
            if (!RULE_WORDS.matcher(section.body()).find()) {
                continue;
            }
            String body = " " + String.join(" ", words(section.body())) + " ";
            boolean covered = false;
            for (String shingle : shingles) {
                if (body.contains(" " + shingle + " ")) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                uncovered.add(section);
            }
        }
        return uncovered;
    }

    /** Whether this rule (its excerpt or wording) was drawn from any of these sections. */
    static boolean drawnFrom(List<Section> sections, String ruleText) {
        Set<String> shingles = shingles(ruleText);
        for (Section section : sections) {
            String body = " " + String.join(" ", words(section.body())) + " ";
            for (String shingle : shingles) {
                if (body.contains(" " + shingle + " ")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The document's headed sections. Text above the first heading belongs to none. */
    static List<Section> sections(String documentText) {
        List<Section> sections = new ArrayList<>();
        if (documentText == null || documentText.isBlank()) {
            return sections;
        }
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (String line : documentText.split("\\R")) {
            if (HEADING.matcher(line).matches()) {
                if (heading != null) {
                    sections.add(new Section(heading, body.toString()));
                }
                heading = line.strip().replaceFirst("^#+\\s+", "").replaceFirst("\\s+#+$", "");
                body.setLength(0);
            } else if (heading != null) {
                body.append(line).append('\n');
            }
        }
        if (heading != null) {
            sections.add(new Section(heading, body.toString()));
        }
        return sections;
    }

    /** Every run of {@link #SHINGLE} consecutive words; a shorter text of 3+ words is one run. */
    private static Set<String> shingles(String text) {
        Set<String> shingles = new HashSet<>();
        List<String> words = words(text);
        if (words.size() < SHINGLE) {
            if (words.size() >= 3) {
                shingles.add(String.join(" ", words));
            }
            return shingles;
        }
        for (int i = 0; i + SHINGLE <= words.size(); i++) {
            shingles.add(String.join(" ", words.subList(i, i + SHINGLE)));
        }
        return shingles;
    }

    /** Lower-cased words, with markdown marks and punctuation gone, so quoting style cannot matter. */
    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }
}
