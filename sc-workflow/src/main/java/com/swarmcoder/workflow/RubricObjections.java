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
package com.swarmcoder.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rubric review of a design answers three questions - completeness, testability, write-set
 * partitionability - and none of them is "does the design keep the project's rules".
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 75, 2026-10-03. The story was "the logbook offers no import, no uploads, no award
 * counts". The rubric reviewer is shown the project's standing rules so that it does not object
 * to a design for following one, and it read them as a list of things every design must
 * deliver: "the standing rule 'Server-side filtering and sorting' requires the service to offer
 * list with a filter and sorting. The design must include a method that accepts filter/sorting
 * parameters." The architect obeyed, and a story that lists nothing delivered a filter type and
 * a new service method; the revision took 32 minutes and one expert question of 654,000 tokens.
 * A second objection of the same kind asked for the acceptance test's package to follow a rule
 * about packages, which the build fixes and no design chooses.
 *
 * <h2>The rule</h2>
 *
 * <p>Whether a design keeps the rules is one check's question:
 * {@link DesignReviewerClient#reviewDesign}, which reads the design's OWN decisions and contracts
 * against the rules, with lookups, and objects only to what the design itself adds or changes.
 * A rubric objection whose ground is a standing rule is therefore not sent to the architect. It
 * is set aside and said in the log; if the design does break that rule, the rules check that
 * runs next says so, in the wording the revision loop is built for.
 *
 * <p>An objection rests on a rule when it says "standing rule", or quotes a title that the rules
 * briefing carries. Read from the two texts, no model.
 */
final class RubricObjections {

    private static final Pattern STANDING_RULE = Pattern.compile(
        "\\b(standing|house|project(?:'s)?)\\s+rules?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED =
        Pattern.compile("['\"‘“]([^'\"’”]{4,120})['\"’”]");

    private RubricObjections() {
    }

    /**
     * @param forTheArchitect what the rubric itself objects to
     * @param restingOnARule  what was set aside for the rules check
     */
    record Split(List<String> forTheArchitect, List<String> restingOnARule) {}

    /** With no rules briefing nothing can rest on a rule, and every objection stands. */
    static Split split(List<String> objections, String rulesBrief) {
        List<String> rubric = new ArrayList<>();
        List<String> rule = new ArrayList<>();
        if (objections != null) {
            for (String objection : objections) {
                if (objection == null || objection.isBlank()) {
                    continue;
                }
                (restsOnARule(objection, rulesBrief) ? rule : rubric).add(objection);
            }
        }
        return new Split(List.copyOf(rubric), List.copyOf(rule));
    }

    static boolean restsOnARule(String objection, String rulesBrief) {
        if (objection == null || rulesBrief == null || rulesBrief.isBlank()) {
            return false;
        }
        if (STANDING_RULE.matcher(objection).find()) {
            return true;
        }
        String brief = rulesBrief.toLowerCase(Locale.ROOT);
        Matcher quoted = QUOTED.matcher(objection);
        while (quoted.find()) {
            String title = quoted.group(1).strip().toLowerCase(Locale.ROOT);
            // A title, not a single identifier the rules happen to mention.
            if (title.contains(" ") && brief.contains(title)) {
                return true;
            }
        }
        return false;
    }
}
