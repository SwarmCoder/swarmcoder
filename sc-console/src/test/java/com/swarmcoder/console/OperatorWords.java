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

import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No lifecycle state, no kind constant and no internal noun reaches the screen.
 *
 * <p>UX v3 §5 rule 3: "No internal names in the UI. §4 is the complete vocabulary." One assertion
 * already pinned that for the requirement <em>relations</em> — {@code REFINES}, {@code DEPENDS_ON}
 * — and it worked: nothing has leaked one since. This is the same idea applied to the other two
 * families of constant, which had never been pinned and had leaked badly.
 *
 * <p>What was on screen when this was written: the intake wizard's review block opened with
 * {@code Kind: FUNCTIONAL}; the planning wizard's finish message said a story was "a DRAFT sitting
 * in Triage — promoting it to READY"; the requirements reference chip on every row read
 * {@code DRAFT} or {@code ACTIVE} or {@code IMPLEMENTED}; the change journal in a story's dialog
 * put {@code STATE_CHANGED} and {@code TOMBSTONED} beside each line; and the task list inside a
 * build badged ten constants including {@code DISPATCHED}, {@code VERIFYING} and {@code JUDGING}.
 *
 * <h2>Why the text is collected this way</h2>
 *
 * <p>Not {@code innerText}: that is the <em>rendered</em> text, and the browser applies
 * {@code text-transform} to it. Several labels in the Console are styled uppercase — the pipeline's
 * column headings, the status chip on a requirement row — so "Ready to build" reaches
 * {@code innerText} as "READY TO BUILD" and "draft" as "DRAFT", and an assertion reading that would
 * fail on wording that is perfectly correct.
 *
 * <p>Not {@code textContent} on the body either, because that swallows any {@code <script>} or
 * {@code <style>} in the page. This walks the document's text nodes and skips those two, which
 * gives the words as they were WRITTEN.
 *
 * <h2>Words that are not text</h2>
 *
 * <p>Text nodes are not all of what a person reads. A status dot is a coloured circle with nothing
 * in it: everything it says, it says through {@code title} — the tooltip — and {@code aria-label},
 * which is what a screen reader is given instead of the picture. Every dot in this console hovered
 * as its own internal state, {@code DISPATCHED} and {@code SELECTED} and the rest, all the way
 * through the two sweeps that took those constants off the visible screens, because nothing could
 * see them: this walker only read text nodes. It reads {@code title}, {@code aria-label},
 * {@code placeholder} and {@code alt} as well now, so a constant hidden behind a hover fails the
 * same assertion as one printed on a badge.
 *
 * <h2>The second list: words, not constants</h2>
 *
 * <p>{@link #FORBIDDEN} catches shouting. It cannot catch a perfectly ordinary-looking sentence
 * written in the wrong vocabulary, and the Console was full of one: "criterion" and "criteria",
 * the Java type's name, sitting beside the operator's word for the same thing on the same screens.
 * "Search requirements and checks" and "delivers 1 check" sat next to "Criterion:" in the intake
 * wizard's review and "criteria no story delivers" in the planning wizard, so a reader had no way
 * to tell whether the two words meant the same thing. UX v3 §2.1 fixes a budget of four nouns and
 * settles this one: the operator's word is <b>check</b>.
 *
 * <p>{@link #INTERNAL_NOUNS} is therefore matched case-INSENSITIVELY: unlike a state constant,
 * there is no innocent lower-case reading of it. Two things it deliberately does not touch:
 * {@code AcceptanceCriterion} and the rest of the Java vocabulary, which is correct and is never
 * on a screen; and the id format {@code R2:C1} beside each check, which is a handle the operator
 * uses on purpose.
 *
 * <p>It reads the whole page, including prose the model wrote and the operator is reviewing
 * verbatim — a proposal's rationale, a decision's brief. That is the one thing this cannot police,
 * so the scripted-model fixtures are written in the same vocabulary as the interface around them.
 */
final class OperatorWords {

    /**
     * The constants that must never be read by a person.
     *
     * <p>Every lifecycle state and every kind in {@code sc-domain} that has ever been rendered, or
     * could be. Matched whole-word and case-sensitively: the offence is the shouted Java constant,
     * and "draft", "ready" and "building" are the words the operator is supposed to see.
     *
     * <p>Four constants are deliberately absent, because they are also ordinary English and would
     * fire on innocent prose: {@code DONE}, {@code SELECTED}, {@code CREATED} and {@code UPDATED}.
     * Each is nevertheless mapped to operator words on its enum, and the remaining twenty-two are
     * more than enough to catch a screen that has started printing an enum again.
     */
    private static final List<String> FORBIDDEN = List.of(
        // StoryState
        "DRAFT", "READY", "RUNNING", "REVIEW", "BLOCKED", "CANCELLED",
        // RequirementStatus
        "ACTIVE", "IMPLEMENTED", "DEPRECATED",
        // TaskState
        "PENDING", "DISPATCHED", "VERIFYING", "JUDGING", "INTEGRATED",
        // StoryKind and RequirementKind
        "DELIVERY", "ENABLER", "FUNCTIONAL", "NON_FUNCTIONAL",
        // ChangeKind
        "STATE_CHANGED", "PROMOTED", "TOMBSTONED", "RESTORED",
        // CriterionStatus and Priority — the two pickers that offered their enum verbatim
        "PROPOSED", "CRITICAL",
        // StoryOrigin
        "BACKLOG", "AD_HOC", "DISCOVERED",
        // RequirementRelation
        "DEPENDS_ON", "REFINES", "CONFLICTS_WITH", "DERIVED_FROM", "GATES",
        // the run-internal parking state UX v3 §2.3 abolished
        "APPROVAL");

    /**
     * Internal nouns for things the operator has a word of their own for.
     *
     * <p>Case-insensitive, because the offence here is the WORD, not the shouting.
     * {@code AcceptanceCriterion} is a check (UX v3 §2.1, §4's mapping table), and the five
     * requirement relations have operator phrases of their own on {@code RequirementRelation}.
     */
    private static final List<String> INTERNAL_NOUNS = List.of("criterion", "criteria",
        // The five relation names, lower-cased, which is how they actually leaked. The diagram's
        // legend read "depends refines conflicts derived gates", its edge list badged every link
        // with one of those words, its picker offered all five, and the history panel printed
        // "added edge R6 refines R1" (25.5). RequirementRelation now holds the operator's phrase -
        // "part of", "waits for", "constrains" - and this is what stops a sixth place inventing its
        // own. Whole-word, so "gates" fires and "gating" does not, and "part of" is untouched.
        "refines", "derived from", "conflicts_with", "depends_on", "derived_from");

    private OperatorWords() {
    }

    /**
     * Fails naming every constant found, and where.
     *
     * @param where the screen this was called on, so a failure says which one to go and look at
     */
    static void assertNoneOnScreen(Page page, String where) {
        Object text = page.evaluate(
            "() => {"
            + "  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);"
            + "  const out = [];"
            + "  while (walker.nextNode()) {"
            + "    const parent = walker.currentNode.parentElement;"
            + "    const tag = parent ? parent.tagName : '';"
            + "    if (tag === 'SCRIPT' || tag === 'STYLE') continue;"
            + "    out.push(walker.currentNode.nodeValue);"
            + "  }"
            // Words a person reads that are not text nodes: a tooltip and the text a screen
            // reader is given instead of a picture. See the class javadoc for why.
            + "  for (const el of document.querySelectorAll("
            + "      '[title],[aria-label],[placeholder],[alt]')) {"
            + "    for (const attr of ['title', 'aria-label', 'placeholder', 'alt']) {"
            + "      const value = el.getAttribute(attr);"
            + "      if (value) out.push(value);"
            + "    }"
            + "  }"
            + "  return out.join(' | ');"
            + "}");
        String written = String.valueOf(text);
        List<String> found = new ArrayList<>();
        for (String constant : FORBIDDEN) {
            Matcher matcher = Pattern.compile("\\b" + Pattern.quote(constant) + "\\b")
                .matcher(written);
            if (matcher.find()) {
                found.add(constant + " (in \"" + around(written, matcher.start()) + "\")");
            }
        }
        for (String noun : INTERNAL_NOUNS) {
            Matcher matcher = Pattern.compile("\\b" + Pattern.quote(noun) + "\\b",
                Pattern.CASE_INSENSITIVE).matcher(written);
            if (matcher.find()) {
                found.add(noun + ", which the operator calls a check (in \""
                    + around(written, matcher.start()) + "\")");
            }
        }
        assertThat(found)
            .describedAs("on " + where + ": the machine's own vocabulary is being shown to the "
                + "operator. UX v3 §5 rule 3 — §4 is the complete vocabulary, and anything it has "
                + "no entry for is written in plain English instead")
            .isEmpty();
    }

    /** A little of the surrounding text, so a failure says WHERE on the screen to look. */
    private static String around(String text, int at) {
        int from = Math.max(0, at - 40);
        int to = Math.min(text.length(), at + 40);
        return text.substring(from, to).replace("\\n", " ").replaceAll("\\s+", " ").trim();
    }
}
