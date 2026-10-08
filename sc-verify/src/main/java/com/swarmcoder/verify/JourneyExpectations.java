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

    /** True when a step does something a person does: fill, click or press. */
    static boolean changes(VerifySpec.StepSpec step) {
        return step.fill() != null || step.click() != null || step.press() != null;
    }

    /** How many steps of the journey change something. */
    public static int changing(JourneyFile.Journey journey) {
        return (int) journey.steps().stream().filter(JourneyExpectations::changes).count();
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
            if (step.fill() != null && step.value() != null && !step.value().isBlank()) {
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
        if (last.expectVisible() == null) {
            return null;
        }
        return new JourneyFile.Journey(journey.path(), journey.name()
            + " (its last expectation alone, on the entry page)", List.of(last));
    }
}
