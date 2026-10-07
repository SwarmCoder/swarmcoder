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
package com.swarmcoder.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether an acceptance check is worded about what a PERSON SEES, rather than about what a service
 * returns.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>On 2026-09-03 a run of three waves stopped at the last one. The check was "A rating can be
 * assigned to a book and is displayed alongside the book's details", and the JUnit acceptance test
 * written for it proved it through the service — because an acceptance test in the one module that
 * sees every other module cannot open a browser. The two enabler waves before it made that service
 * work, so by the time the UI task's wave started its test was already green, and the wave-time
 * red-check parked the run asking a person to "revise the tests".
 *
 * <p>Both halves of that were wrong. The test measured everything a JUnit test CAN measure about
 * that check and measured it correctly; what it never measured is the half the check is worded
 * about — the screen. Nothing in the machine could say that, because nothing anywhere read the
 * WORDS of a check.
 *
 * <h2>What it is used for, and what it deliberately is not</h2>
 *
 * <p>Three places read this, and none of them fails anything on it:
 *
 * <ul>
 *   <li>the test author is told, at authoring time, that its JUnit test can only prove the service
 *       side of such a check — so it writes that part honestly instead of faking a screen;</li>
 *   <li>the wave gate records, when such a check is already proved by an earlier wave, that the
 *       screen half is still unproven;</li>
 *   <li>the judge is told, so it reads the diff for the screen work instead of trusting a green
 *       test that was green before the candidate existed.</li>
 * </ul>
 *
 * <p><b>The word list is short on purpose.</b> A wider list would catch more checks and would also
 * start calling service-side checks user-facing, and every use above is an instruction to a model:
 * a wrong "this is about a screen" costs a worse prompt, not a failed candidate, but it is still a
 * lie told confidently. These are the words that mean a screen and nothing else.
 */
public final class UserFacingWording {

    /**
     * The words that mean "a person looks at this". Matched whole, lower-cased, so "screenshot"
     * does not match "screen" and "performs" does not match "form".
     */
    private static final Set<String> WORDS = Set.of(
        "see", "sees", "seen", "seeing",
        "display", "displays", "displayed", "displaying",
        "show", "shows", "shown", "showing",
        "screen", "onscreen", "page", "button", "buttons", "form", "field",
        "click", "clicks", "clicked", "clicking",
        "visible", "appear", "appears", "appeared",
        "browser", "dialog", "menu", "ui");

    private UserFacingWording() {}

    /** Whether this wording is about what somebody sees. False for null and blank. */
    public static boolean isUserFacing(String wording) {
        return !wordsFound(wording).isEmpty();
    }

    /** True when ANY of these criteria is worded about a screen. */
    public static boolean anyUserFacing(List<AcceptanceCriterion> criteria) {
        if (criteria == null) {
            return false;
        }
        for (AcceptanceCriterion criterion : criteria) {
            if (criterion != null && isUserFacing(criterion.text())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The screen words this wording actually used, in the order they appear — so a message can say
     * WHY it decided a check is about a screen instead of asserting it.
     */
    public static List<String> wordsFound(String wording) {
        if (wording == null || wording.isBlank()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        for (String token : wording.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (WORDS.contains(token)) {
                found.add(token);
            }
        }
        return new ArrayList<>(found);
    }
}
