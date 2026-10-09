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
package com.swarmcoder.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What can be said about a journey's expectations from the journey alone, with no model and no
 * browser (section 69).
 *
 * <p>Live run 93: a journey typed a word into a search box and expected a book's title to be
 * visible. The store starts empty and no step added a book, so the journey could not pass on a
 * correct implementation either. Nothing a worker may write could repair it, and it was first
 * seen after the last merge. Two things are decided here:
 *
 * <ul>
 *   <li>{@link #unentered}: an {@code expectVisible} text that no earlier step types and the
 *       project as it stands does not hold. Either the new screen shows it by itself (a heading,
 *       a message) or it is data nobody entered. Which, the journey cannot say; its author is
 *       asked while the journey is being written.</li>
 *   <li>{@link #weakened}: what a correction of a failed journey may not do - become something
 *       that passes because it asks for less.</li>
 * </ul>
 *
 * <p>Only the forms of a selector are read ({@code text=}, {@code :has-text("...")}); no word of
 * any language is looked for.
 */
public final class JourneyExpectations {

    private JourneyExpectations() {}

    private static final Pattern TEXT_FUNCTION =
        Pattern.compile(":(?:has-)?text(?:-is)?\\(\\s*([\"'])(.*?)\\1\\s*\\)");

    /**
     * An expected text nobody entered.
     *
     * @param step the step's number, from 1
     * @param text the text the step expects to see
     */
    public record Unentered(int step, String text) {}

    /**
     * The texts a selector asks the page to show: the value of {@code text=...} and the argument
     * of {@code :text("...")}, {@code :has-text("...")} and {@code :text-is("...")}, in every
     * part of a chained selector. A regular expression ({@code text=/.../}) and a role's name are
     * not among them: a name is a label of the screen, which the story is there to add.
     */
    static List<String> textsOf(String selector) {
        List<String> texts = new ArrayList<>();
        if (selector == null) {
            return texts;
        }
        for (String part : selector.split(">>")) {
            String piece = part.strip();
            if (piece.startsWith("text=")) {
                String text = piece.substring(5).strip();
                if (text.startsWith("/")) {
                    continue;
                }
                if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'')
                        && text.charAt(text.length() - 1) == text.charAt(0)) {
                    text = text.substring(1, text.length() - 1);
                }
                if (!text.isBlank()) {
                    texts.add(text.strip());
                }
            }
            Matcher function = TEXT_FUNCTION.matcher(piece);
            while (function.find()) {
                if (!function.group(2).isBlank()) {
                    texts.add(function.group(2).strip());
                }
            }
        }
        return texts;
    }

    /** True when a step does something a person does: fill, select, click or press. */
    static boolean changes(VerifySpec.StepSpec step) {
        return step.fill() != null || step.click() != null || step.press() != null
            || step.select() != null;
    }

    /**
     * How many things a person does in the journey. A {@code select} counts as two: it stands
     * for opening the control and choosing in it, which a journey written before that step
     * existed spells as two clicks or as a click and key presses - so a correction that turns
     * those into one {@code select} uses the screen no less (live run 100).
     */
    public static int changing(JourneyFile.Journey journey) {
        return journey.steps().stream()
            .mapToInt(step -> step.select() != null ? 2 : changes(step) ? 1 : 0).sum();
    }

    private static int filling(JourneyFile.Journey journey) {
        return (int) journey.steps().stream().filter(step -> step.fill() != null).count();
    }

    /**
     * The {@code expectVisible} texts of a journey that no earlier step types and the project
     * does not hold. A text is typed when it and an earlier {@code fill} value contain one
     * another, whatever the case. Empty when {@code heldByTheProject} is null: with nothing to
     * compare against, nothing is established and nothing is said.
     *
     * @param heldByTheProject whether a text is one the project's code holds as it stands
     */
    public static List<Unentered> unentered(JourneyFile.Journey journey,
                                            Predicate<String> heldByTheProject) {
        List<Unentered> found = new ArrayList<>();
        if (journey == null || heldByTheProject == null) {
            return found;
        }
        List<String> typed = new ArrayList<>();
        for (int i = 0; i < journey.steps().size(); i++) {
            VerifySpec.StepSpec step = journey.steps().get(i);
            // What a step types, and what it chooses: a chosen option's text was put there
            // by the journey as much as a typed one, and the screen may show it afterwards.
            if ((step.fill() != null || step.select() != null) && step.value() != null
                    && !step.value().isBlank()) {
                typed.add(step.value().strip().toLowerCase(Locale.ROOT));
            }
            if (step.expectVisible() == null) {
                continue;
            }
            for (String text : textsOf(step.expectVisible())) {
                String wanted = text.toLowerCase(Locale.ROOT);
                boolean entered = typed.stream()
                    .anyMatch(value -> wanted.contains(value) || value.contains(wanted));
                if (!entered && !heldByTheProject.test(text)) {
                    found.add(new Unentered(i + 1, text));
                }
            }
        }
        return found;
    }

    /** What the author is asked about the texts {@link #unentered} found. */
    public static String question(List<Unentered> unentered) {
        StringBuilder text = new StringBuilder("The application is started with NO DATA of its "
            + "own unless the project's contract says otherwise: a list is empty until a step "
            + "of this journey fills it. This journey expects to see\n");
        for (Unentered one : unentered) {
            text.append("- step ").append(one.step()).append(": \"").append(one.text())
                .append("\"\n");
        }
        return text.append("and no earlier step types that, and no code of the project as it "
            + "stands holds it. If it is DATA (a record's name, a title, a value somebody must "
            + "have entered), the journey cannot pass on a correct implementation either: add "
            + "the steps that enter it through the screen first, or expect what a step of the "
            + "journey types. If it is a text the NEW screen shows by itself (a heading, a "
            + "label, a message), the journey is right as it is.").toString();
    }

    /**
     * What makes {@code corrected} a weaker journey than {@code original}; empty when nothing
     * here does. Both are well-formed journeys, so both end on an expectation.
     *
     * <p>Checked: it still has a step that changes something; it has no fewer such steps and
     * no fewer {@code fill} steps than the journey it replaces (a correction renames what a
     * selector looks for or adds the steps that were missing; it does not take use away).
     * Not checked here, because only a browser can: that it fails on the start tree, that what
     * its last step expects is not already on the entry page there, and that it passes on the
     * merged tree. Not checkable at all: that what it now expects is what the criteria ask.
     */
    public static List<String> weakened(JourneyFile.Journey original,
                                        JourneyFile.Journey corrected) {
        List<String> why = new ArrayList<>();
        if (changing(corrected) == 0) {
            why.add("it has no step that changes anything (fill, click or press): it only looks");
        }
        if (changing(corrected) < changing(original)) {
            why.add("it has " + changing(corrected) + " step(s) that change something where the "
                + "journey it replaces has " + changing(original));
        }
        if (filling(corrected) < filling(original)) {
            why.add("it fills " + filling(corrected) + " field(s) where the journey it replaces "
                + "fills " + filling(original));
        }
        return why;
    }

    /** Most expectations at the head of one journey that are tried alone on the start tree. */
    public static final int MAX_LEADING = 5;

    /**
     * An expectation a journey makes BEFORE it does anything: on the entry page, as the
     * application shows it when it opens.
     *
     * @param step the step's number, from 1
     */
    public record Leading(int step, VerifySpec.StepSpec spec) {}

    /**
     * The {@code expectVisible} and {@code expectValue} steps a journey makes before its first
     * fill, select, click or press (section 75, live run 101). They are made on the entry page,
     * so each can be tried alone on the application as it was before the story: one that holds
     * there is true of the STARTING application. It shows nothing about the story, and it fails
     * on a correct implementation as soon as the story replaces what that page shows - run
     * 101's journey began by expecting the start page's old text and failed at step 1 on a
     * screen that was right. {@code expectHidden} is left out: what is not there is hidden on
     * every page. At most {@link #MAX_LEADING}; decided from the steps' kinds, no word is read.
     */
    public static List<Leading> leading(JourneyFile.Journey journey) {
        List<Leading> found = new ArrayList<>();
        if (journey == null) {
            return found;
        }
        for (int i = 0; i < journey.steps().size() && found.size() < MAX_LEADING; i++) {
            VerifySpec.StepSpec step = journey.steps().get(i);
            if (changes(step)) {
                break;
            }
            if (step.expectVisible() != null || step.expectValue() != null) {
                found.add(new Leading(i + 1, step));
            }
        }
        return found;
    }

    /** Each of {@link #leading} as a journey of that one step, in the same order. */
    public static List<JourneyFile.Journey> leadingAlone(JourneyFile.Journey journey) {
        List<JourneyFile.Journey> alone = new ArrayList<>();
        for (Leading one : leading(journey)) {
            alone.add(new JourneyFile.Journey(journey.path(), journey.name() + " (step "
                + one.step() + " alone, on the entry page)", List.of(one.spec())));
        }
        return alone;
    }

    /**
     * The leading expectations a browser found true on the start tree.
     *
     * @param alone what the browser made of {@link #leadingAlone}, in the same order; a
     *              missing or failed result establishes nothing and names no step
     */
    public static List<Leading> alreadyThere(JourneyFile.Journey journey,
                                             List<JourneyFile.Result> alone) {
        List<Leading> there = new ArrayList<>();
        List<Leading> all = leading(journey);
        for (int i = 0; i < all.size() && alone != null && i < alone.size(); i++) {
            if (alone.get(i) != null && alone.get(i).passed()) {
                there.add(all.get(i));
            }
        }
        return there;
    }

    /** What is said about expectations that hold on the application before the story. */
    public static String alreadyThereObjection(List<Leading> there) {
        return String.join("; ", there.stream().map(one -> "step " + one.step() + " (`"
                + one.spec().describe() + "`)").toList())
            + (there.size() == 1 ? " is" : " are") + " already true on the entry page of the "
            + "application as it is BEFORE the story (tried there in a real browser), and no "
            + "step before " + (there.size() == 1 ? "it" : "them") + " does anything: "
            + (there.size() == 1 ? "it shows" : "they show") + " nothing about the story, and "
            + (there.size() == 1 ? "fails" : "fail") + " on a correct implementation as soon "
            + "as the story changes what that page shows. Take " + (there.size() == 1 ? "it"
                : "them") + " out, or expect what the story adds.";
    }

    /**
     * Role names of the accessibility vocabulary that are not also the name of a page element.
     * A selector part that begins with one of them and a bracket was meant as a role: as
     * written, the browser driver reads it as a stylesheet selector for an element of that
     * name, and no page has one. Roles that are element names too (button, form, table) are
     * left out: there the stylesheet reading is a real selector.
     */
    private static final java.util.Set<String> ROLES_THAT_ARE_NO_ELEMENT = java.util.Set.of(
        "textbox", "combobox", "checkbox", "radio", "link", "heading", "listbox", "option",
        "tab", "tabpanel", "tablist", "row", "cell", "gridcell", "columnheader", "rowheader",
        "searchbox", "spinbutton", "switch", "slider", "menuitem", "list", "listitem", "grid",
        "tree", "treeitem", "alert", "status", "banner", "navigation", "region", "group",
        "radiogroup", "img", "menubar", "tooltip", "progressbar", "separator", "scrollbar");

    private static final Pattern BARE_ROLE = Pattern.compile("^([a-z]+)\\s*\\[");

    /**
     * The steps whose selector names a role without {@code role=} (section 75; the journey of
     * live run 101 filled {@code textbox[name="..."]}, which finds nothing on any page). One
     * line a step, saying how it is written. Read from the selector's form and the
     * accessibility vocabulary's own names; no word of a story is looked at.
     */
    public static List<String> rolesWithoutPrefix(JourneyFile.Journey journey) {
        List<String> found = new ArrayList<>();
        if (journey == null) {
            return found;
        }
        for (int i = 0; i < journey.steps().size(); i++) {
            VerifySpec.StepSpec step = journey.steps().get(i);
            String selector = step.click() != null ? step.click() : step.fill() != null
                ? step.fill() : step.select() != null ? step.select()
                : step.expectVisible() != null ? step.expectVisible()
                : step.expectHidden() != null ? step.expectHidden() : step.expectValue();
            if (selector == null) {
                continue;
            }
            for (String part : selector.split(">>")) {
                Matcher bare = BARE_ROLE.matcher(part.strip());
                if (bare.find() && ROLES_THAT_ARE_NO_ELEMENT.contains(bare.group(1))) {
                    found.add("step " + (i + 1) + ": `" + part.strip() + "` names a role "
                        + "without `role=`, so the browser looks for an element <"
                        + bare.group(1) + ">, which no page has. Write `role=" + part.strip()
                        + "`");
                    break;
                }
            }
        }
        return found;
    }

    private static final Pattern ROLE_NAME =
        Pattern.compile("\\[name\\s*=\\s*([\"'])(.*?)\\1[^\\]]*\\]");

    /**
     * The names and texts a journey's selectors look for on the screen (section 75): the
     * texts of {@link #textsOf} and the accessible names of {@code role=...[name="..."]}, of
     * every step that acts on or expects to see something. Left out: what {@code expectHidden}
     * names (it must not be there), a pattern, and an expected text an earlier step of the
     * journey types or chooses - that is data, not a label of the screen.
     */
    public static List<String> namesUsed(JourneyFile.Journey journey) {
        List<String> names = new ArrayList<>();
        if (journey == null) {
            return names;
        }
        List<String> typed = new ArrayList<>();
        for (VerifySpec.StepSpec step : journey.steps()) {
            String selector = step.click() != null ? step.click() : step.fill() != null
                ? step.fill() : step.select() != null ? step.select()
                : step.expectVisible() != null ? step.expectVisible() : step.expectValue();
            boolean expects = step.expectVisible() != null;
            if (selector != null) {
                List<String> found = new ArrayList<>(textsOf(selector));
                Matcher name = ROLE_NAME.matcher(selector);
                while (name.find()) {
                    if (!name.group(2).isBlank()) {
                        found.add(name.group(2).strip());
                    }
                }
                for (String text : found) {
                    String wanted = text.toLowerCase(Locale.ROOT);
                    boolean entered = expects && typed.stream()
                        .anyMatch(value -> wanted.contains(value) || value.contains(wanted));
                    if (!entered && !names.contains(text)) {
                        names.add(text);
                    }
                }
            }
            if ((step.fill() != null || step.select() != null) && step.value() != null
                    && !step.value().isBlank()) {
                typed.add(step.value().strip().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /**
     * The names of {@link #namesUsed} that no shipped code of a checkout holds. Empty when
     * {@code heldByTheCheckout} is null: nothing is established then, and nothing is said.
     */
    public static List<String> namesNotHeld(JourneyFile.Journey journey,
                                            Predicate<String> heldByTheCheckout) {
        if (heldByTheCheckout == null) {
            return List.of();
        }
        return namesUsed(journey).stream().filter(name -> !heldByTheCheckout.test(name)).toList();
    }

    /**
     * The journey's last expectation on its own, made on the entry page: when that passes on the
     * start tree, what the journey ends on was there before the story, and a journey that fails
     * there only at an earlier step proves nothing by its ending. Null for a last step of
     * {@code expectHidden} - something that is not there is hidden on every page.
     */
    public static JourneyFile.Journey lastExpectationAlone(JourneyFile.Journey journey) {
        if (journey == null || journey.steps().isEmpty()) {
            return null;
        }
        VerifySpec.StepSpec last = journey.steps().get(journey.steps().size() - 1);
        if (last.expectVisible() == null && last.expectValue() == null) {
            return null;
        }
        return new JourneyFile.Journey(journey.path(), journey.name()
            + " (its last expectation alone, on the entry page)", List.of(last));
    }
}
