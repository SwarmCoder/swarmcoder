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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.VerificationReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out, from what a failed build actually produced, whether the story failed because something
 * it needed did not exist yet — and which other planned story was going to provide it.
 *
 * <p><b>Why this is needed.</b> The planner declares dependencies before any code is written, from
 * requirements alone. It is guessing, and it will sometimes be wrong. A dependency graph decided up
 * front and never revised turns one wrong guess into a story that fails at three in the morning and
 * sits there until somebody wakes up. The system has to be able to notice its own mistake.
 *
 * <p><b>It reads facts, not opinions.</b> Nothing here asks a model anything. Every worker on a task
 * compiles the same tree, and when the compiler says the same name does not exist to more than one
 * of them independently, that is a mechanical fact about the code — the strongest signal in the run,
 * and it is already sitting in the verification reports. A single worker's failure is not enough: one
 * worker can misspell something all by itself.
 *
 * <p><b>What it does with the fact.</b> Only what it can defend. If exactly the missing name appears
 * in another planned, undelivered story's own wording — its title, what it is for, or the checks it
 * promises — then that story is what this one was waiting for, and an edge is inferred. If nothing
 * planned mentions it, no edge is invented: the story stops and says which name it could not find,
 * which is a clean, readable failure rather than a guess dressed up as a schedule.
 */
public final class MissingPieceDetector {

    private MissingPieceDetector() {}

    /**
     * @param missing     the names the compiler could not resolve, most-reported first
     * @param providers   the undelivered stories whose own wording names one of them
     * @param explanation one sentence for the operator, or null when nothing was found
     */
    public record Finding(List<String> missing, List<Story> providers, String explanation) {
        public boolean any() {
            return !missing.isEmpty();
        }
        public boolean hasProvider() {
            return !providers.isEmpty();
        }
    }

    public static final Finding NOTHING = new Finding(List.of(), List.of(), null);

    /**
     * javac's two ways of saying "that does not exist". Both are printed by every JDK in English
     * regardless of locale for the diagnostic key, and both name the thing directly:
     * {@code symbol:   class Book} and {@code package com.acme.books does not exist}.
     */
    private static final Pattern SYMBOL =
        Pattern.compile("symbol:\\s+(?:class|interface|enum|record|variable|method)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern PACKAGE =
        Pattern.compile("package\\s+([A-Za-z_$][\\w$.]*)\\s+does not exist");

    /** How many independent workers must report the same missing name before it counts as a fact. */
    private static final int AGREEING_WORKERS = 2;

    /**
     * Reads the run's candidates and returns what they could not find.
     *
     * @param candidates every candidate produced for the story's run, in any order
     * @param others     the project's other living stories — the possible providers
     */
    public static Finding examine(List<CandidateSolution> candidates, List<Story> others, Brd brd) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int compileFailures = 0;
        for (CandidateSolution candidate : candidates == null ? List.<CandidateSolution>of() : candidates) {
            VerificationReport report = candidate == null ? null : candidate.verification();
            if (report == null || report.compiles()) {
                continue;
            }
            compileFailures++;
            for (String name : namesIn(report.logTail())) {
                counts.merge(name, 1, Integer::sum);
            }
        }
        if (compileFailures == 0) {
            return NOTHING;     // it did not fail for want of something; this is not that case
        }
        // One worker agreeing with itself is not agreement. When there was only one candidate at all,
        // one report is all the evidence that can exist, so accept it — a solo run is still a fact.
        int needed = compileFailures == 1 ? 1 : AGREEING_WORKERS;
        List<String> missing = new ArrayList<>();
        counts.entrySet().stream()
            .filter(e -> e.getValue() >= needed)
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(e -> missing.add(e.getKey()));
        if (missing.isEmpty()) {
            return NOTHING;
        }

        List<Story> providers = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        for (String name : missing) {
            for (Story other : others == null ? List.<Story>of() : others) {
                if (other == null || other.state() == StoryState.DONE
                        || other.state() == StoryState.CANCELLED) {
                    continue;
                }
                if (mentions(other, name, brd) && !providers.contains(other)) {
                    providers.add(other);
                    if (!matched.contains(name)) {
                        matched.add(name);
                    }
                }
            }
        }
        return new Finding(List.copyOf(missing), List.copyOf(providers),
            explain(missing, providers, matched));
    }

    /** The unresolved names in one compiler log. */
    static Set<String> namesIn(String log) {
        Set<String> names = new LinkedHashSet<>();
        if (log == null || log.isBlank()) {
            return names;
        }
        Matcher symbol = SYMBOL.matcher(log);
        while (symbol.find()) {
            names.add(symbol.group(1));
        }
        Matcher pkg = PACKAGE.matcher(log);
        while (pkg.find()) {
            names.add(pkg.group(1));
        }
        return names;
    }

    /**
     * Whether a story's own words name this missing thing — its title, what it is for, and the
     * wording of every check it promises.
     *
     * <p>Whole words only, and case-insensitively. Asking whether the text CONTAINS "Book" would
     * match "Bookmark", "Bookkeeping" and "Facebook", and an edge inferred from that would stop a
     * story for a reason that is not true.
     */
    private static boolean mentions(Story story, String name, Brd brd) {
        String needle = simpleName(name);
        if (needle.length() < 3) {
            return false;   // too short to mean anything; "Id" matches everything
        }
        StringBuilder text = new StringBuilder();
        append(text, story.title());
        append(text, story.narrative());
        append(text, story.rationale());
        if (brd != null && brd.requirements() != null) {
            Set<UUID> wanted = new LinkedHashSet<>(story.criterionIds());
            Set<UUID> wantedRequirements = new LinkedHashSet<>(story.requirementIds());
            for (BrdRequirement requirement : brd.requirements()) {
                boolean whole = wantedRequirements.contains(requirement.id());
                if (whole) {
                    append(text, requirement.title());
                    append(text, requirement.text());
                }
                for (AcceptanceCriterion criterion : requirement.criteria()) {
                    if (whole || wanted.contains(criterion.id())) {
                        append(text, criterion.text());
                    }
                }
            }
        }
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(needle) + "(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE)
            .matcher(text).find();
    }

    /** {@code com.acme.books} is looked for as {@code books}; a package's last part is its name. */
    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static void append(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            text.append(' ').append(value);
        }
    }

    private static String explain(List<String> missing, List<Story> providers, List<String> matched) {
        String names = missing.size() == 1
            ? "\"" + missing.get(0) + "\""
            : "\"" + String.join("\", \"", missing.subList(0, Math.min(3, missing.size()))) + "\"";
        if (providers.isEmpty()) {
            return "Every attempt at this story failed to compile because " + names
                + " does not exist in the code yet, and nothing else that is planned looks like it "
                + "would create it. Either something is missing from the plan, or this story has to "
                + "build it itself.";
        }
        StringBuilder sb = new StringBuilder("Every attempt at this story failed to compile because ")
            .append(names).append(" does not exist in the code yet. ");
        sb.append(providers.size() == 1 ? "This is what " : "These are what ");
        for (int i = 0; i < providers.size(); i++) {
            if (i > 0) {
                sb.append(i == providers.size() - 1 ? " and " : ", ");
            }
            Story provider = providers.get(i);
            sb.append(provider.key());
            if (provider.title() != null && !provider.title().isBlank()) {
                sb.append(" \"").append(provider.title()).append('"');
            }
        }
        sb.append(providers.size() == 1 ? " is for" : " are for")
            .append(", so this story has been put back to wait until ")
            .append(providers.size() == 1 ? "it is" : "they are")
            .append(" delivered. Nobody planned that order — it was worked out from the failure.");
        return sb.toString();
    }

    /** Lower-cased for logging; keeps the class free of stray {@link Locale} imports elsewhere. */
    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** Every candidate the run's tasks produced, for {@link #examine}. */
    public static List<CandidateSolution> candidatesOf(TaskGraph graph,
                                                       java.util.function.Function<UUID,
                                                           List<CandidateSolution>> byTask) {
        List<CandidateSolution> all = new ArrayList<>();
        if (graph == null || graph.tasks() == null) {
            return all;
        }
        for (Task task : graph.tasks()) {
            List<CandidateSolution> some = byTask.apply(task.id());
            if (some != null) {
                all.addAll(some);
            }
        }
        return all;
    }
}
