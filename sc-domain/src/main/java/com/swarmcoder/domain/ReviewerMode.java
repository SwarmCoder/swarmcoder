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

import java.util.Locale;

/**
 * How the design reviewer works (owner decision, 2026-10-13, section 96).
 *
 * <ul>
 *   <li>{@link #QUESTION} (the default): each reading - the design against the rules, the plan,
 *       the tests - is ONE call with no tools: the text to review, the project's rules, and what
 *       the architect established for the design. Measured in the harness it was about 5 calls
 *       and 20,000 tokens a story.
 *   <li>{@link #LOOKUPS}: the reviewer is an agent with the roles' lookup tools and one
 *       conversation a run (section 94). One paid story cost 121 calls and 9.1 million tokens.
 * </ul>
 *
 * <p>The setting is {@code roles.reviewerMode} in the settings file. The application and the
 * end-to-end harness both resolve it here, so a harness run measures what the application does.
 */
public enum ReviewerMode {
    QUESTION,
    LOOKUPS;

    /** The settings key, under {@code roles:}. */
    public static final String SETTING = "reviewerMode";

    /** The reviewer's calls a run needs at most in {@link #QUESTION} mode: four readings. */
    public static final int QUESTION_READINGS = 4;

    private static volatile ReviewerMode current = QUESTION;

    /** The mode a setting names; absent, blank or unrecognized is {@link #QUESTION}. */
    public static ReviewerMode fromSetting(String setting) {
        if (setting != null && setting.strip().toLowerCase(Locale.ROOT).equals("lookups")) {
            return LOOKUPS;
        }
        return QUESTION;
    }

    /** Whether the setting is something other than a value this understands (for a warning). */
    public static boolean isRecognized(String setting) {
        if (setting == null || setting.isBlank()) {
            return true;
        }
        String s = setting.strip().toLowerCase(Locale.ROOT);
        return s.equals("question") || s.equals("lookups");
    }

    /**
     * Resolves the setting and makes it the mode the run report judges the reviewer's call count
     * by. Called once where the application and the harness wire the reviewer.
     */
    public static ReviewerMode configure(String setting) {
        ReviewerMode mode = fromSetting(setting);
        current = mode;
        return mode;
    }

    /** The mode in force, as last configured; {@link #QUESTION} before any. */
    public static ReviewerMode current() {
        return current;
    }

    /**
     * The reviewer's calls a run needs at most: in {@link #QUESTION} mode one call per reading
     * (the rubric review of the design, the design against the rules, the plan, the tests) plus
     * one per revision (the design is revised at most twice, the plan at most twice); in
     * {@link #LOOKUPS} mode 16 (section 94).
     */
    public int expectedCalls() {
        return this == QUESTION ? QUESTION_READINGS + 2 + 2 : 16;
    }
}
