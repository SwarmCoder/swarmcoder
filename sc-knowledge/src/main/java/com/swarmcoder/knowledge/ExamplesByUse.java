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
package com.swarmcoder.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The worked example chosen by what a piece of work USES, for the work {@link WorkedExamples}
 * cannot serve.
 *
 * <p><b>Why this exists (live harness runs 56 to 60, 2026-10-01).</b> {@link WorkedExamples}
 * matches the types a task DELIVERS against existing files by shape. The hardest task of a plan is
 * usually the one that delivers no contract at all — the implementation class behind an interface
 * a test touches is not itself something a test touches, so the design never states it — and for
 * that task the selector had nothing to match and returned nothing, silently. Its two workers then
 * spent eighty minutes asking how the framework's injected database is used. The test author was
 * never shown an example at all, and four of eight failed runs were an acceptance test that
 * guessed how the framework's test harness works.
 *
 * <p><b>What "uses" means, and why none of it names a framework.</b> The words of the work — its
 * title, its instructions, the member signatures of the contracts it touches, the project's own
 * rules — name types. The ones the reference material imports, extends or is annotated with are
 * library types this work will have to use, and a file that uses the same ones is an example of
 * how. Each name is weighted by how rare it is across the reference material, so a type every file
 * imports decides nothing. A second, weaker vote comes from the file's own name: a task writing
 * {@code FooServiceImpl} is nearer to {@code BarServiceImpl} than to {@code BarScreen}.
 *
 * <p><b>One focused file.</b> The answer is one class or one test under a size cap, never a
 * project. Reference folders are searched first; the project's own tree is only searched when no
 * reference file qualifies, because on a new build the project's own code is the code still being
 * guessed at.
 *
 * <p><b>Except for a test the project already has</b> (live run 86, 2026-10-04, section 58). The
 * test author spent 88 calls and 7.2 million prompt tokens finding out how a test starts this
 * project's framework - which beans the test server is given, how the store gets a temporary
 * directory - for the sixth story in a row. A test of the project itself that uses what the
 * rules name for tests already does all of that against this project's own build, beans and
 * packages, which no reference example can. So when a test is asked for and the project holds
 * one that uses a type the rules name for tests, that test is the example, ahead of the
 * reference material. The project's tree as the curator reads it is the tree the run started
 * from, so a test found there was written by earlier work, not by the task now being tested.
 */
final class ExamplesByUse {

    private static final Pattern TYPE_NAME = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*[a-z][A-Za-z0-9_]*\\b");

    /** A name used by more of the reference material than this distinguishes nothing. */
    private static final double TOO_COMMON = 0.25;

    private ExamplesByUse() {}

    /**
     * @param text      what the work says about itself: title, instructions, contract members
     * @param rules     the project's standing rules, or "" — names in them count, at {@code ruleWeight}
     * @param ruleWeight how much a rule-named type counts beside a task-named one
     * @param nameHints simple names of what is being written or tested ({@code LogbookServiceImpl})
     * @param ownNames  simple names the work itself creates — never demanded, never the example
     * @param wantTest  true for a test source, false for production code
     */
    record Query(String text, String rules, double ruleWeight, Collection<String> nameHints,
                 Collection<String> ownNames, boolean wantTest) {}

    /**
     * @param used              the named library types this file uses, most distinctive first
     * @param ruleNamedForTests the ones among them the project's rules name for tests; empty for
     *                          production code, and when the rules name none
     */
    record Found(WorkedExamples.Shape example, List<String> used, int sharedNameTokens,
                 double score, List<String> ruleNamedForTests) {

        String why() {
            StringBuilder sb = new StringBuilder();
            if (!used.isEmpty()) {
                sb.append("it uses ").append(backticked(used));
            }
            if (!ruleNamedForTests.isEmpty()) {
                sb.append(sb.isEmpty() ? "it uses " + backticked(ruleNamedForTests) : "")
                    .append(" (the project's rules name ").append(backticked(ruleNamedForTests))
                    .append(" for tests)");
            }
            if (sharedNameTokens >= 2 || used.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " and ").append("it is the same kind of class by name");
            }
            return sb.toString();
        }
    }

    /**
     * @param best           null when nothing qualified
     * @param searchedTypes  the library types that were looked for
     * @param searchedNames  the name words that were looked for
     * @param considered     how many files of the wanted kind were small enough to be shown
     */
    record Result(Found best, List<String> searchedTypes, List<String> searchedNames,
                  int considered) {

        /** What was looked for, for the log line that says nothing was found. */
        String searched() {
            return "library types " + (searchedTypes.isEmpty() ? "(none named)" : searchedTypes)
                + ", class-name words " + (searchedNames.isEmpty() ? "(none)" : searchedNames)
                + ", across " + considered + " candidate file(s)";
        }
    }

    static Result find(List<WorkedExamples.Shape> shapes, Query query, int maxChars) {
        List<WorkedExamples.Shape> reference = new ArrayList<>();
        List<WorkedExamples.Shape> project = new ArrayList<>();
        for (WorkedExamples.Shape shape : shapes == null ? List.<WorkedExamples.Shape>of() : shapes) {
            ("project".equals(shape.rootLabel()) ? project : reference).add(shape);
        }
        Set<String> own = new LinkedHashSet<>(query.ownNames() == null ? List.of() : query.ownNames());
        List<String> hintTokens = new ArrayList<>();
        for (String hint : query.nameHints() == null ? List.<String>of() : query.nameHints()) {
            for (String token : WorkedExamples.tokens(hint)) {
                if (!hintTokens.contains(token)) {
                    hintTokens.add(token);
                }
            }
        }
        // Reference material first. Only when it holds nothing that qualifies is the project's own
        // tree worth showing — and then never a file the work itself is about to write.
        Result fromReference = findIn(reference, query, own, hintTokens, maxChars);
        Result fromProject = null;
        if (query.wantTest() && !project.isEmpty()) {
            // A test this project already has, built with what its rules name for tests.
            fromProject = findIn(project, query, own, hintTokens, maxChars);
            if (fromProject.best() != null && !fromProject.best().ruleNamedForTests().isEmpty()) {
                return fromProject;
            }
        }
        if (fromReference.best() != null || project.isEmpty()) {
            return fromReference;
        }
        if (fromProject == null) {
            fromProject = findIn(project, query, own, hintTokens, maxChars);
        }
        if (fromProject.best() != null) {
            return fromProject;
        }
        Set<String> types = new LinkedHashSet<>(fromReference.searchedTypes());
        types.addAll(fromProject.searchedTypes());
        return new Result(null, List.copyOf(types), hintTokens,
            fromReference.considered() + fromProject.considered());
    }

    private static Result findIn(List<WorkedExamples.Shape> shapes, Query query, Set<String> own,
                                 List<String> hintTokens, int maxChars) {
        if (shapes.isEmpty()) {
            return new Result(null, List.of(), hintTokens, 0);
        }
        Map<WorkedExamples.Shape, Set<String>> uses = new LinkedHashMap<>();
        Map<String, Integer> frequency = new HashMap<>();
        Set<String> platform = new LinkedHashSet<>();
        for (WorkedExamples.Shape shape : shapes) {
            Set<String> used = usedBy(shape, platform);
            uses.put(shape, used);
            for (String name : used) {
                frequency.merge(name, 1, Integer::sum);
            }
        }
        Map<String, Double> weights = new LinkedHashMap<>();
        addNamed(weights, query.text(), 1.0, frequency, platform, own, shapes.size());
        addNamed(weights, query.rules(), query.ruleWeight(), frequency, platform, own, shapes.size());
        double possible = 0;
        for (double weight : weights.values()) {
            possible += weight;
        }
        // WHAT THE RULES SAY A TEST IS BUILT WITH (live run 63, 2026-10-02). The rules said a
        // service is never built by hand and named the framework's test server. The example shown
        // was a test of the right kind of class by name that built its service by hand and
        // injected by reflection; the test that used the test server was passed over, first
        // because it was larger than the size cap. A test that uses a type the rules name for
        // tests is the example, ahead of any that merely looks alike, and gets twice the room.
        Map<String, Double> forTests = new LinkedHashMap<>();
        if (query.wantTest()) {
            addNamed(forTests, rulesAboutTests(query.rules()), 1.0, frequency, platform, own,
                shapes.size());
        }
        double bestForTests = 0;

        Found best = null;
        int considered = 0;
        for (WorkedExamples.Shape shape : shapes) {
            if (isTest(shape) != query.wantTest() || own.contains(shape.simpleName())
                || shape.simpleName().equals("package-info")) {
                continue;
            }
            List<String> usedForTests = new ArrayList<>();
            double forTestsUse = 0;
            for (Map.Entry<String, Double> named : forTests.entrySet()) {
                if (uses.get(shape).contains(named.getKey())
                    && !named.getKey().equals(shape.simpleName())) {
                    usedForTests.add(named.getKey());
                    forTestsUse += named.getValue();
                }
            }
            if (shape.bytes() > (forTestsUse > 0 ? 2L * maxChars : maxChars)) {
                continue;
            }
            considered++;
            List<String> used = new ArrayList<>();
            double use = 0;
            for (Map.Entry<String, Double> named : weights.entrySet()) {
                // A file that DECLARES the type is not an example of using it.
                if (uses.get(shape).contains(named.getKey())
                    && !named.getKey().equals(shape.simpleName())) {
                    used.add(named.getKey());
                    use += named.getValue();
                }
            }
            List<String> nameTokens = WorkedExamples.tokens(shape.simpleName());
            int shared = 0;
            for (String token : hintTokens) {
                if (nameTokens.contains(token)) {
                    shared++;
                }
            }
            double nameAgreement = WorkedExamples.tokenOverlap(hintTokens, nameTokens);
            boolean sameKindByName = shared >= 2 && nameAgreement >= 0.5;
            if (use <= 0 && !sameKindByName && forTestsUse <= 0) {
                continue;
            }
            double score = (0.65 * (possible <= 0 ? 0 : use / possible) + 0.35 * nameAgreement)
                // Smaller teaches the same idiom for less; gentle, so it only breaks near-ties.
                / (1.0 + shape.bytes() / 20_000.0);
            used.sort((a, b) -> Double.compare(weights.get(b), weights.get(a)));
            for (String name : usedForTests) {
                if (!used.contains(name)) {
                    used.add(0, name);
                }
            }
            usedForTests.sort((a, b) -> Double.compare(forTests.get(b), forTests.get(a)));
            // More of what the rules name for tests wins outright; the usual score only decides
            // between files that use the same amount of it.
            boolean moreForTests = forTestsUse > bestForTests + 1e-9;
            boolean sameForTests = Math.abs(forTestsUse - bestForTests) <= 1e-9;
            if (best == null || moreForTests || (sameForTests && (score > best.score() + 1e-9
                || (Math.abs(score - best.score()) <= 1e-9
                    && shape.relative().compareTo(best.example().relative()) < 0)))) {
                best = new Found(shape, List.copyOf(used), shared, score,
                    List.copyOf(usedForTests));
                bestForTests = forTestsUse;
            }
        }
        return new Result(best, List.copyOf(weights.keySet()), hintTokens, considered);
    }

    private static final Pattern ABOUT_TESTS =
        Pattern.compile("\\btest(?:s|ed|ing)?\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The part of the rules that is about tests: every sentence that says "test", and from the
     * others the type names that say it themselves ({@code TestServer}, {@code MockClock} does
     * not). Derived from the rules' own words; nothing here knows any library.
     */
    static String rulesAboutTests(String rules) {
        if (rules == null || rules.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String sentence : rules.split("\\R|(?<=[.;:])\\s+")) {
            if (ABOUT_TESTS.matcher(sentence).find()) {
                sb.append(sentence).append('\n');
                continue;
            }
            Matcher matcher = TYPE_NAME.matcher(sentence);
            while (matcher.find()) {
                if (WorkedExamples.tokens(matcher.group()).contains("test")) {
                    sb.append(matcher.group()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Every capitalised name in the text that the material uses, weighted by how rare it is. */
    private static void addNamed(Map<String, Double> into, String text, double weight,
                                 Map<String, Integer> frequency, Set<String> platform,
                                 Set<String> own, int files) {
        if (text == null || text.isBlank() || weight <= 0) {
            return;
        }
        Matcher matcher = TYPE_NAME.matcher(text);
        while (matcher.find()) {
            String name = matcher.group();
            Integer count = frequency.get(name);
            if (count == null || own.contains(name) || platform.contains(name)
                || into.containsKey(name)) {
                continue;
            }
            // In a handful of files everything is "common"; the cut only means something at size.
            if (files >= 20 && count / (double) files > TOO_COMMON) {
                continue;
            }
            into.put(name, weight * Math.log(1.0 + files / (double) count));
        }
    }

    /**
     * The simple names of the types a file uses that it did not declare: what it imports, what it
     * extends or implements, what it is annotated with, what its framework-typed fields are.
     *
     * @param platform collects the names imported from the language's own library, which are never
     *                 evidence of how a framework is used
     */
    static Set<String> usedBy(WorkedExamples.Shape shape, Set<String> platform) {
        Set<String> used = new LinkedHashSet<>();
        for (String raw : shape.imports()) {
            String name = raw.replace(";", "").strip();
            boolean isStatic = name.startsWith("static ");
            if (isStatic) {
                name = name.substring(7).strip();
            }
            String[] parts = name.split("\\.");
            // A static import names a member; the type is the last capitalised segment before it.
            String simple = "";
            for (int i = parts.length - 1; i >= 0; i--) {
                if (!parts[i].isEmpty() && Character.isUpperCase(parts[i].charAt(0))) {
                    simple = parts[i];
                    break;
                }
            }
            if (simple.isEmpty()) {
                continue;
            }
            if (name.startsWith("java.") || name.startsWith("javax.")) {
                platform.add(simple);
                continue;
            }
            used.add(simple);
        }
        used.addAll(shape.annotations());
        used.addAll(shape.supertypes());
        for (String field : shape.frameworkFields()) {
            Matcher matcher = TYPE_NAME.matcher(field);
            while (matcher.find()) {
                used.add(matcher.group());
            }
        }
        used.removeAll(platform);
        return used;
    }

    static boolean isTest(WorkedExamples.Shape shape) {
        String path = shape.file().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return path.contains("/src/test/") || shape.simpleName().endsWith("Test")
            || shape.simpleName().endsWith("Tests") || shape.simpleName().endsWith("IT");
    }

    private static String backticked(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names.subList(0, Math.min(4, names.size()))) {
            out.add("`" + name + "`");
        }
        return String.join(", ", out);
    }
}
