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

    private static final Set<String> TOP_KEYS = Set.of("journey", "steps", "proves");

    /**
     * What an author is told about {@code proves} (live run 103, section 76): a journey for a
     * story about editing and removing records added one twice with a corrected spelling,
     * never used the screen's edit control, passed, and the story was accepted. Its one-line
     * name said it edits. Which steps exercise which criterion is now a field of the file,
     * read with no model, shown to whoever accepts the story.
     *
     * @param criteria the criteria the journey is written for, in the order they are numbered
     */
    public static String provesBrief(List<String> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(" THE JOURNEY SAYS WHICH OF ITS STEPS PROVE "
            + "WHICH CRITERION. After `steps` the file has a third key, `proves`, with one "
            + "entry for every criterion below, by its number:\n"
            + "proves:\n"
            + "  - criterion: 1\n"
            + "    steps: \"<first>-<last>\"   # the steps in which a person does what this "
            + "criterion describes and sees the result\n"
            + "  - criterion: 2\n"
            + "    notOnScreen: \"<one sentence: why no person can see or do this on a "
            + "screen>\"\n"
            + "The steps of an entry use the control the screen offers for THAT criterion and "
            + "end by looking at its result: changing a record is proved by changing it on the "
            + "screen, not by entering it a second time. The entries are shown to the person "
            + "who accepts the story. The criteria, numbered:\n");
        for (int i = 0; i < criteria.size(); i++) {
            text.append(i + 1).append(". ").append(criteria.get(i) == null ? ""
                : criteria.get(i).strip().replaceAll("\\s+", " ")).append('\n');
        }
        return text.toString();
    }

    /**
     * One entry of {@code proves}: the steps said to exercise one criterion, or why it cannot
     * be seen on a screen.
     *
     * @param criterion   its number in the list the author was given, from 1
     * @param first       the first step, from 1; 0 with {@code notOnScreen}
     * @param last        the last step, inclusive
     * @param notOnScreen the author's sentence in place of steps; null when steps are named
     */
    public record Proof(int criterion, int first, int last, String notOnScreen) {
    }

    /**
     * What is wrong with a journey's {@code proves} for these criteria, or null: every
     * criterion needs exactly one entry. The form of each entry is checked by {@link #read}.
     */
    public static String coverageObjection(Journey journey, List<String> criteria) {
        if (journey == null || criteria == null || criteria.isEmpty()) {
            return null;
        }
        List<String> problems = new ArrayList<>();
        for (Proof proof : journey.proves()) {
            if (proof.criterion() > criteria.size()) {
                problems.add("`proves` names criterion " + proof.criterion() + "; there are "
                    + criteria.size());
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= criteria.size(); i++) {
            int number = i;
            if (journey.proves().stream().noneMatch(proof -> proof.criterion() == number)) {
                missing.add(number);
            }
        }
        if (!missing.isEmpty()) {
            problems.add((journey.proves().isEmpty() ? "the file has no `proves`"
                : "`proves` has no entry for criterion " + missing.stream()
                    .map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")))
                + ". Every criterion needs one entry: the steps that exercise it, or why it is "
                + "not on a screen");
        }
        if (problems.isEmpty()) {
            return null;
        }
        return String.join("\n", problems.stream().map(problem -> "- " + problem).toList())
            + "\n" + provesBrief(criteria).strip();
    }

    /**
     * The entries whose steps act only on controls the journey also acts on OUTSIDE those
     * steps: nothing on the screen is used for that criterion that was not already used for
     * something else. Compared as selectors, character for character; no word is read. It is
     * a question for the author, not a refusal - a second record entered through the same form
     * can be exactly what a criterion describes.
     */
    public static List<String> borrowedControls(Journey journey) {
        List<String> lines = new ArrayList<>();
        if (journey == null) {
            return lines;
        }
        for (Proof proof : journey.proves()) {
            if (proof.notOnScreen() != null) {
                continue;
            }
            Set<String> inside = new java.util.LinkedHashSet<>();
            Set<String> outside = new java.util.HashSet<>();
            for (int i = 1; i <= journey.steps().size(); i++) {
                String control = controlOf(journey.steps().get(i - 1));
                if (control != null) {
                    (i >= proof.first() && i <= proof.last() ? inside : outside).add(control);
                }
            }
            if (!inside.isEmpty() && outside.containsAll(inside)) {
                lines.add("criterion " + proof.criterion() + " (steps " + proof.first() + "-"
                    + proof.last() + ") uses only " + String.join(", ", inside)
                    + ", which other steps of the journey use too");
            }
        }
        return lines;
    }

    /** The question {@link #borrowedControls} puts to the author. */
    public static String borrowedQuestion(List<String> borrowed) {
        return "The steps you name for a criterion use no control of their own:\n"
            + String.join("\n", borrowed.stream().map(line -> "- " + line).toList())
            + "\nIf that criterion is about something else a person does on the screen - "
            + "changing or removing what is there, not entering it again - its steps must use "
            + "the control the screen offers for that, and the journey does not prove it yet.";
    }

    /** What a step acts on: the selector of a click, a fill or a select; null for the rest. */
    private static String controlOf(VerifySpec.StepSpec step) {
        String control = step.click() != null ? step.click()
            : step.fill() != null ? step.fill() : step.select();
        return control == null || control.isBlank() ? null : control.strip();
    }
    private static final List<String> ACTIONS =
        List.of("click", "fill", "select", "press", "expectVisible", "expectHidden",
            "expectValue");
    /** The steps that carry a {@code value}: what is typed, chosen, or expected to be held. */
    private static final Set<String> WITH_VALUE = Set.of("fill", "select", "expectValue");

    /**
     * Every step a journey has, one line each, saying what it is for. Given wherever an author
     * writes or corrects a journey, so that none has to look the vocabulary up (live run 100:
     * a review spent five of its ten turns searching the project for how a journey chooses
     * from a drop-down list, and there was no way to).
     */
    public static final String VOCABULARY =
        "  - click: \"<selector>\"          # click a button, a link, a tab, a row\n"
        + "  - fill: \"<selector>\"           # type into a text field\n"
        + "    value: \"<what is typed>\"\n"
        + "  - select: \"<selector>\"         # choose in a drop-down list or combobox\n"
        + "    value: \"<the option, as it reads>\"\n"
        + "  - press: \"Enter\"               # press a key\n"
        + "  - expectVisible: \"<selector>\"  # this is on the page and can be seen\n"
        + "  - expectHidden: \"<selector>\"   # this is not on the page, or cannot be seen\n"
        + "  - expectValue: \"<selector>\"    # this field or drop-down list holds the value\n"
        + "    value: \"<what it holds or shows as chosen>\"\n";

    /** What an author must know about choosing, in two sentences. */
    public static final String CHOOSING = "To choose an option use `select` on the control, "
        + "with the option's text as `value` - never click the option: the options of a closed "
        + "drop-down list are not on the page for a click or an expectVisible to find. What a "
        + "field or a drop-down list currently holds is checked with `expectValue`, not with "
        + "expectVisible.";
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
    public record Journey(String path, String name, List<VerifySpec.StepSpec> steps,
                          List<Proof> proves) {

        public Journey {
            proves = proves == null ? List.of() : List.copyOf(proves);
        }

        /** A journey that says nothing of which steps prove what. */
        public Journey(String path, String name, List<VerifySpec.StepSpec> steps) {
            this(path, name, steps, List.of());
        }

        /**
         * What the journey says proves each criterion, one line an entry, for the run's log,
         * its report and whoever accepts the story. Empty when the file has no {@code proves}.
         *
         * @param criteria the criteria by number; null or short leaves a number without words
         */
        public List<String> proofLines(List<String> criteria) {
            List<String> lines = new ArrayList<>();
            List<String> borrowed = borrowedControls(this);
            for (Proof proof : proves) {
                String words = criteria != null && proof.criterion() <= criteria.size()
                    && criteria.get(proof.criterion() - 1) != null
                    ? " \"" + criteria.get(proof.criterion() - 1).strip() + "\"" : "";
                StringBuilder line = new StringBuilder(path + ": criterion " + proof.criterion()
                    + words);
                if (proof.notOnScreen() != null) {
                    line.append(" - said not to be on a screen: ").append(proof.notOnScreen());
                } else {
                    line.append(" - by steps ").append(proof.first()).append('-')
                        .append(proof.last()).append(": ");
                    for (int i = proof.first(); i <= proof.last() && i <= steps.size(); i++) {
                        line.append(i == proof.first() ? "" : "; ")
                            .append(steps.get(i - 1).describe());
                    }
                    String flag = "criterion " + proof.criterion() + " (steps ";
                    if (borrowed.stream().anyMatch(b -> b.startsWith(flag))) {
                        line.append(" [NOTE: these steps use no control the rest of the "
                            + "journey does not also use]");
                    }
                }
                lines.add(line.toString());
            }
            return lines;
        }

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
            for (Proof proof : proves) {
                text.append("  criterion ").append(proof.criterion()).append(": ")
                    .append(proof.notOnScreen() != null
                        ? "said not to be on a screen - " + proof.notOnScreen()
                        : "said to be proved by steps " + proof.first() + "-" + proof.last())
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
                problems.add("`" + key + "` is not a key of a journey. A journey has `journey`, "
                    + "`steps` and `proves` and nothing else: it has no address, because it starts on the "
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
                + "(with value), select (with value), press, expectVisible, expectHidden, "
                + "expectValue (with value)");
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
                if (!last.looks()) {
                    problems.add("the last step is `" + last.describe() + "`. A journey ends by "
                        + "looking: its last step is expectVisible, expectHidden or expectValue, "
                        + "naming what the person sees when it has worked");
                }
            }
        }
        List<Proof> proves = problems.isEmpty() ? provesOf(root.get("proves"), steps, problems)
            : List.of();
        return new Read(new Journey(file, title, List.copyOf(steps), proves),
            List.copyOf(problems));
    }

    private static final java.util.regex.Pattern STEP_RANGE =
        java.util.regex.Pattern.compile("^(\\d+)(?:\\s*-\\s*(\\d+))?$");

    /** Reads {@code proves}; absent is no entry and no problem. */
    private static List<Proof> provesOf(JsonNode node, List<VerifySpec.StepSpec> steps,
                                        List<String> problems) {
        List<Proof> proves = new ArrayList<>();
        if (node == null || node.isNull()) {
            return proves;
        }
        String form = "Each entry of `proves` is `criterion: <number>` with either `steps: "
            + "\"<first>-<last>\"` or `notOnScreen: \"<why>\"`";
        if (!node.isArray()) {
            problems.add("`proves` must be a list. " + form);
            return proves;
        }
        Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            String where = "`proves` entry " + (i + 1);
            if (entry == null || !entry.isObject() || !entry.path("criterion").canConvertToInt()
                    || entry.path("criterion").asInt() < 1) {
                problems.add(where + " has no `criterion` number. " + form);
                continue;
            }
            int criterion = entry.path("criterion").asInt();
            if (!seen.add(criterion)) {
                problems.add(where + " is a second entry for criterion " + criterion
                    + "; one entry a criterion");
                continue;
            }
            boolean hasSteps = entry.hasNonNull("steps");
            String why = entry.path("notOnScreen").asText("").strip();
            if (hasSteps == !why.isEmpty() || entry.size() != 2) {
                problems.add(where + " must have exactly one of `steps` and `notOnScreen` "
                    + "beside `criterion`. " + form);
                continue;
            }
            if (!hasSteps) {
                proves.add(new Proof(criterion, 0, 0, why));
                continue;
            }
            java.util.regex.Matcher range = STEP_RANGE.matcher(entry.path("steps").asText("")
                .strip());
            int first = range.matches() ? Integer.parseInt(range.group(1)) : 0;
            int last = !range.matches() ? 0
                : range.group(2) == null ? first : Integer.parseInt(range.group(2));
            if (first < 1 || last < first || last > steps.size()) {
                problems.add(where + ": `steps` must be \"<first>-<last>\" within this "
                    + "journey's " + steps.size() + " step(s) (it is `"
                    + entry.path("steps").asText("") + "`)");
                continue;
            }
            List<VerifySpec.StepSpec> named = steps.subList(first - 1, last);
            if (named.stream().allMatch(VerifySpec.StepSpec::looks)) {
                problems.add(where + ": steps " + first + "-" + last + " only look. The steps "
                    + "that prove a criterion DO what it describes - click, fill, select or "
                    + "press - and then look at the result");
                continue;
            }
            if (named.stream().noneMatch(VerifySpec.StepSpec::looks)) {
                problems.add(where + ": steps " + first + "-" + last + " never look. The steps "
                    + "that prove a criterion end with expectVisible, expectHidden or "
                    + "expectValue of what the person then sees");
                continue;
            }
            proves.add(new Proof(criterion, first, last, null));
        }
        return List.copyOf(proves);
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
        String action = actions.get(0);
        boolean valued = WITH_VALUE.contains(action);
        if (valued && !node.has("value")) {
            problems.add(where + (action.equals("fill")
                ? " fills a field and gives no `value` to type into it"
                : action.equals("select")
                    ? " selects in a control and gives no `value`: the option to choose, as it "
                        + "reads on the screen"
                    : " expects a control to hold something and gives no `value`: what the "
                        + "field holds, or the option the list shows as chosen"));
            sound = false;
        }
        if (valued && !action.equals("fill") && node.has("value")
                && node.get("value").isValueNode() && node.get("value").asText("").isBlank()) {
            problems.add(where + ": the `value` of " + action + " is empty");
            sound = false;
        }
        if (!valued && node.has("value")) {
            problems.add(where + " has a `value` but is a " + action + ": only fill (what is "
                + "typed), select (the option chosen) and expectValue (what the control holds) "
                + "take a value");
            sound = false;
        }
        if (!sound) {
            return null;
        }
        return new VerifySpec.StepSpec(text(node, "click"), text(node, "fill"),
            text(node, "value"), text(node, "press"), text(node, "expectVisible"),
            text(node, "expectHidden"), text(node, "select"), text(node, "expectValue"));
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
