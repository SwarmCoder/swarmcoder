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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.swarmcoder.domain.AssertionResult;
import com.swarmcoder.domain.PageCheck;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A journey written for a story: {@code <name>.journey.yaml} in the protected acceptance
 * directory, beside the story's acceptance tests (section 63).
 *
 * <p>Seven stories were accepted whose screens no user could open (section 62). Their acceptance
 * tests called the server. A journey is the acceptance test of the other half: what a person does
 * in a browser, from the page the application opens on, using only what is on the screen.
 *
 * <pre>
 * journey: A contact is added from the logbook
 * steps:
 *   - click: "text=Logbook"
 *   - click: "role=button[name=\"Add contact\"]"
 *   - fill: "role=textbox[name=\"Call\"]"
 *     value: "DL1ABC"
 *   - press: "Enter"
 *   - expectVisible: "text=DL1ABC"
 * </pre>
 *
 * <p>A journey has no address. It starts where the application starts
 * ({@link #entryUrl}) and there is no step that loads a page, so a screen the application's own
 * navigation does not lead to cannot be arrived at.
 *
 * <p>Everything here is read and decided with no model.
 */
public final class JourneyFile {

    /** What a journey file's name ends with. */
    public static final String SUFFIX = ".journey.yaml";

    /** {@code -Dswarmcoder.verify.journeys=off}: no journey is asked for, required or run. */
    public static final String SWITCH = "swarmcoder.verify.journeys";

    /** A guard against a file that is not a journey at all. */
    static final int MAX_STEPS = 60;

    private static final Set<String> TOP_KEYS = Set.of("journey", "steps");
    private static final List<String> ACTIONS =
        List.of("click", "fill", "press", "expectVisible", "expectHidden");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private JourneyFile() {}

    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
    }

    /** True for a path named like a journey file. */
    /** The one key of the answer an author gives in place of a journey (section 64). */
    public static final String NO_VISIBLE_EFFECT = "noVisibleEffect";

    /**
     * The sentence of an answer given IN PLACE of a journey: a YAML mapping with the single key
     * {@code noVisibleEffect} and a sentence saying why no person using the application in a
     * browser sees or can do anything different. Null for anything else - a journey, a draft
     * with other keys, an empty sentence. Structured, so that it is recorded and never inferred
     * from prose; whether it is TAKEN is decided by the workflow, from the object graph.
     */
    public static String waiverOf(String yaml) {
        try {
            JsonNode root = yaml == null || yaml.isBlank() ? null : YAML.readTree(yaml);
            if (root == null || !root.isObject() || root.size() != 1
                    || !root.path(NO_VISIBLE_EFFECT).isValueNode()) {
                return null;
            }
            String why = root.path(NO_VISIBLE_EFFECT).asText("").strip();
            return why.isEmpty() ? null : why;
        } catch (Exception notYaml) {                                        // noqa
            return null;
        }
    }

    public static boolean isJourney(String path) {
        return path != null && path.replace('\\', '/').strip().endsWith(SUFFIX);
    }

    /**
     * @param path  repo-relative, as the task claims it
     * @param name  what the journey shows, in the author's words
     * @param steps in order; never empty for a journey without problems
     */
    public record Journey(String path, String name, List<VerifySpec.StepSpec> steps) {

        /** The browser check that makes this journey, starting at {@code entryUrl}. */
        public VerifySpec.PageCheckSpec toCheck(String entryUrl) {
            return new VerifySpec.PageCheckSpec(entryUrl, false, List.of(), false, steps);
        }

        /** The journey in words, one step a line. */
        public String describe() {
            StringBuilder text = new StringBuilder("journey \"" + name + "\" (" + path + "), "
                + steps.size() + " step(s) from the application's entry page:\n");
            for (int i = 0; i < steps.size(); i++) {
                text.append("  ").append(i + 1).append(". ").append(steps.get(i).describe())
                    .append('\n');
            }
            return text.toString();
        }
    }

    /** A file as read: the journey, and what is wrong with it. A journey with problems never runs. */
    public record Read(Journey journey, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }

        /** The problems as the lines a draft is sent back with. */
        public String objection() {
            return String.join("\n", problems.stream().map(problem -> "- " + problem).toList());
        }
    }

    /** Reads and checks a journey file. Never throws. */
    public static Read read(String path, String yaml) {
        List<String> problems = new ArrayList<>();
        String file = path == null ? "" : path.replace('\\', '/').strip();
        String name = file.substring(file.lastIndexOf('/') + 1);
        if (!name.endsWith(SUFFIX) || !name.substring(0, name.length() - SUFFIX.length())
                .matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            problems.add("the file must be named <name>" + SUFFIX + ", the name made of letters, "
                + "digits, - and _ (it is `" + name + "`)");
        }
        JsonNode root = null;
        try {
            root = yaml == null || yaml.isBlank() ? null : YAML.readTree(yaml);
        } catch (Exception unreadable) {
            problems.add("it is not YAML: " + firstLine(unreadable.getMessage()));
        }
        if (root == null || !root.isObject()) {
            if (problems.stream().noneMatch(problem -> problem.startsWith("it is not YAML"))) {
                problems.add("it must be a YAML mapping with the keys `journey` (one sentence: "
                    + "what a person does and sees) and `steps` (a list)");
            }
            return new Read(new Journey(file, "", List.of()), List.copyOf(problems));
        }
        for (Iterator<String> keys = root.fieldNames(); keys.hasNext(); ) {
            String key = keys.next();
            if (!TOP_KEYS.contains(key)) {
                problems.add("`" + key + "` is not a key of a journey. A journey has `journey` "
                    + "and `steps` and nothing else: it has no address, because it starts on the "
                    + "application's entry page and reaches every other screen by clicking");
            }
        }
        String title = root.path("journey").isValueNode() ? root.path("journey").asText("").strip()
            : "";
        if (title.isEmpty()) {
            problems.add("`journey` is missing: one sentence saying what a person does and sees");
        }
        List<VerifySpec.StepSpec> steps = new ArrayList<>();
        JsonNode list = root.path("steps");
        if (!list.isArray() || list.isEmpty()) {
            problems.add("`steps` is missing or empty: a list, each entry one of click, fill "
                + "(with value), press, expectVisible, expectHidden");
        } else if (list.size() > MAX_STEPS) {
            problems.add("`steps` has " + list.size() + " entries; a journey has at most "
                + MAX_STEPS + ". Write several journeys instead");
        } else {
            for (int i = 0; i < list.size(); i++) {
                VerifySpec.StepSpec step = stepOf(list.get(i), i + 1, problems);
                if (step != null) {
                    steps.add(step);
                }
            }
            if (problems.isEmpty()) {
                VerifySpec.StepSpec last = steps.get(steps.size() - 1);
                if (last.expectVisible() == null && last.expectHidden() == null) {
                    problems.add("the last step is `" + last.describe() + "`. A journey ends by "
                        + "looking: its last step is expectVisible or expectHidden, naming what "
                        + "the person sees when it has worked");
                }
            }
        }
        return new Read(new Journey(file, title, List.copyOf(steps)), List.copyOf(problems));
    }

    private static VerifySpec.StepSpec stepOf(JsonNode node, int number, List<String> problems) {
        String where = "step " + number;
        if (node == null || !node.isObject()) {
            problems.add(where + " is not a mapping. Write it as `- click: \"text=Save\"`");
            return null;
        }
        List<String> actions = new ArrayList<>();
        boolean sound = true;
        for (Iterator<String> keys = node.fieldNames(); keys.hasNext(); ) {
            String key = keys.next();
            if (ACTIONS.contains(key)) {
                actions.add(key);
            } else if (!key.equals("value")) {
                problems.add(where + " has `" + key + "`, which no step has. A step is one of "
                    + String.join(", ", ACTIONS) + (isAnAddress(key) ? ". No step loads an "
                    + "address: the screen is reached by clicking what the application shows" : ""));
                sound = false;
            }
            JsonNode value = node.get(key);
            if (value == null || !value.isValueNode() || value.isNull()
                    || (!key.equals("value") && value.asText().isBlank())) {
                problems.add(where + ": `" + key + "` needs a text value in quotes");
                sound = false;
            }
        }
        if (actions.size() != 1) {
            problems.add(where + " must do exactly one thing (" + String.join(", ", ACTIONS)
                + "); it has " + (actions.isEmpty() ? "none" : String.join(" and ", actions)));
            return null;
        }
        boolean fills = actions.get(0).equals("fill");
        if (fills && !node.has("value")) {
            problems.add(where + " fills a field and gives no `value` to type into it");
            sound = false;
        }
        if (!fills && node.has("value")) {
            problems.add(where + " has a `value` but does not `fill`: only fill types a value");
            sound = false;
        }
        if (!sound) {
            return null;
        }
        return new VerifySpec.StepSpec(text(node, "click"), text(node, "fill"),
            text(node, "value"), text(node, "press"), text(node, "expectVisible"),
            text(node, "expectHidden"));
    }

    /** A key that asks for a page to be loaded, in any of the spellings a browser driver has. */
    private static boolean isAnAddress(String key) {
        return Set.of("url", "goto", "navigate", "open", "visit", "href").contains(key);
    }

    private static String text(JsonNode node, String key) {
        return node.has(key) ? node.get(key).asText() : null;
    }

    private static String firstLine(String text) {
        String line = text == null ? "" : text.strip();
        int end = line.indexOf('\n');
        return end < 0 ? line : line.substring(0, end).strip();
    }

    /**
     * The page every journey starts on: the address of the contract's first page check when it
     * has one (the project said where its application opens), otherwise {@code /}.
     */
    public static String entryUrl(VerifySpec.BrowserSpec contract) {
        if (contract != null && contract.checks() != null) {
            for (VerifySpec.PageCheckSpec check : contract.checks()) {
                if (check != null && !check.isJourney() && check.url() != null
                        && !check.url().isBlank()) {
                    return check.url();
                }
            }
        }
        return "/";
    }

    /** True when the contract says how to start the application, so a journey can be made. */
    public static boolean canRun(VerifySpec spec) {
        return spec != null && spec.browser() != null && spec.browser().serve() != null
            && !spec.browser().serve().isBlank();
    }

    /**
     * What a browser made of one journey.
     *
     * @param passed  every step was done and every expectation held
     * @param failure null when passed; otherwise the failing step, by number and in words, with
     *                what the browser said - the sentence a worker is given to repair from
     * @param seen    what the page showed when the step failed (section 69): the roles and
     *                accessible names of its elements, its fields' placeholders and its visible
     *                text, as the browser in the container read them - no model, at most
     *                {@link #MAX_SEEN} characters. Null when the journey passed or the browser
     *                gave no reading
     * @param step    the number (from 1) of the step that failed; 0 when the journey passed or
     *                the failure is not a step's (the page did not load)
     */
    public record Result(Journey journey, boolean passed, String failure, String seen, int step) {

        public Result(Journey journey, boolean passed, String failure, String seen) {
            this(journey, passed, failure, seen, 0);
        }

        public Result(Journey journey, boolean passed, String failure) {
            this(journey, passed, failure, null, 0);
        }
    }

    /** The checker's mark for its reading of the page at a failed step; never a failure. */
    static final String PAGE_SEEN = "page-seen";

    /** The checker cuts its reading at this; cut here again, whatever a container sent. */
    public static final int MAX_SEEN = 4000;

    private static String seenIn(List<AssertionResult> assertions) {
        for (AssertionResult assertion : assertions) {
            if (PAGE_SEEN.equals(assertion.selector()) && assertion.message() != null
                    && !assertion.message().isBlank()) {
                String seen = assertion.message().strip();
                return seen.length() <= MAX_SEEN ? seen : seen.substring(0, MAX_SEEN);
            }
        }
        return null;
    }

    /** Reads the browser's result for {@code journey}; {@code check} is that journey's page. */
    public static Result resultOf(Journey journey, PageCheck check) {
        if (check == null) {
            return new Result(journey, false, "the browser gave no result for it");
        }
        List<AssertionResult> assertions =
            check.assertions() == null ? List.of() : check.assertions();
        if (!check.loaded()) {
            String why = assertions.stream().filter(a -> !a.passed() && a.message() != null)
                .map(AssertionResult::message).findFirst().orElse("the page did not load");
            return new Result(journey, false, "the application's entry page " + check.url()
                + " did not load, so no step was made: " + firstLine(why));
        }
        int total = journey.steps().size();
        for (AssertionResult assertion : assertions) {
            if (assertion.passed()) {
                continue;
            }
            String selector = assertion.selector() == null ? "" : assertion.selector();
            int number = stepNumber(selector);
            if (number < 1 || number > total) {
                return new Result(journey, false, selector + " - " + firstLine(assertion.message()),
                    seenIn(assertions));
            }
            StringBuilder text = new StringBuilder("step " + number + " of " + total
                + " failed: `" + journey.steps().get(number - 1).describe() + "` - "
                + firstLine(assertion.message()).replaceAll("[.\\s]+$", "") + ".");
            if (number > 1) {
                text.append(" The ").append(number - 1).append(" step(s) before it were done: ");
                for (int i = 0; i < number - 1; i++) {
                    text.append(i == 0 ? "" : "; ").append(journey.steps().get(i).describe());
                }
                text.append('.');
            } else {
                text.append(" It is the first step, made on the entry page ").append(check.url())
                    .append('.');
            }
            return new Result(journey, false, text.toString(), seenIn(assertions), number);
        }
        return new Result(journey, true, null);
    }

    /** The number in {@code step 3: click text=Save}; 0 when the selector is not a step's. */
    private static int stepNumber(String selector) {
        if (!selector.startsWith("step ")) {
            return 0;
        }
        int end = 5;
        while (end < selector.length() && Character.isDigit(selector.charAt(end))) {
            end++;
        }
        try {
            return end == 5 ? 0 : Integer.parseInt(selector.substring(5, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
