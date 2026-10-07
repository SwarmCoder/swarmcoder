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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a change request is ASKING ABOUT, read out of the code it is asking about.
 *
 * <p><b>The gap this fills.</b> A bug report is prose. Everything downstream of it — the checks the
 * analyst drafts, the contracts the architect states, the file a worker opens first — is about
 * types, and until this class existed nothing between the prose and the design ever asked the
 * codebase a question. The architect designed a change to a 30,000-line repository from the goal
 * string alone, and the checks were drafted against type names the model remembered rather than
 * type names the project has.
 *
 * <p><b>Every line here carries a file and a line number,</b> because {@link SemanticIndex.Ref}
 * already renders one and because a brief that says "the selector code" and a brief that says
 * {@code src/main/java/org/jsoup/select/Selector.java:37} are not the same brief. Nothing in here
 * is a model's opinion: it is five queries against the structural index, rendered.
 *
 * <p><b>The most valuable line is the one about tests.</b> "These test classes already assert this
 * behaviour" tells the architect, the test author and the worker where the project states today
 * what the issue says is wrong — and it is the fact a text search cannot give, because a test that
 * exercises a type through three layers never mentions its name.
 *
 * <h2>It fails open, like everything built on the index</h2>
 *
 * <p>An unavailable index, a language OpenRewrite cannot parse, an issue naming nothing the project
 * has: every one of those produces an EMPTY neighbourhood carrying the reason, never an exception
 * and never an invented type. A brownfield run must never block on this — the later targets are on
 * frameworks whose build the parser may not manage at all.
 *
 * <h2>Two departures from what {@link SemanticIndex#namesKnownIn} alone gives</h2>
 *
 * <p>Measured against the five jsoup cases, and both are why the plain call is not enough:
 *
 * <ol>
 *   <li><b>A dotted expression hides its type.</b> {@code namesKnownIn} takes the LAST segment of a
 *       dotted token, so {@code Jsoup.parseBodyFragment(html)} yields {@code parseBodyFragment},
 *       which starts lowercase and is dropped — and the type the issue plainly names,
 *       {@code org.jsoup.Jsoup}, is not in the answer. Every segment is resolved here, not only the
 *       last one.</li>
 *   <li><b>A method names its owner.</b> An issue whose whole reproduction is
 *       {@code doc.select("div:has(span + a)")} names no project type at all beyond
 *       {@code Document}. The types that DECLARE the methods it calls are the neighbourhood, and
 *       the call graph knows them exactly.</li>
 * </ol>
 *
 * <p>JDK types are dropped. {@code String}, {@code IndexOutOfBoundsException} and
 * {@code ArrayList} are named by half the bug reports ever written, the change is never in them,
 * and listing them makes a brief that looks full and says nothing.
 */
public final class ChangeNeighbourhood {

    private static final Logger log = LoggerFactory.getLogger(ChangeNeighbourhood.class);

    /**
     * The ceiling on the rendered brief, in characters (~1,500 tokens).
     *
     * <p>Design §2.2's measured budget: about 2.9% of the 51,200-token working context a worker
     * actually gets, taken out of the space a brownfield target's near-empty documentation channels
     * do not use. {@link #forWorkingContext} sizes it against a smaller window rather than
     * overrunning one.
     */
    public static final int MAX_CHARS = 6_000;

    /** The share of a discovered working context this brief may take. */
    private static final double SHARE_OF_CONTEXT = 0.029;

    /** Characters per token, the same four-to-one the rest of the product budgets with. */
    private static final int CHARS_PER_TOKEN = 4;

    /** How many types the brief names. Beyond this it stops being a neighbourhood. */
    private static final int MAX_TYPES = 6;

    /** How many types get their public shape spelled out. Design §2.2: "two or three". */
    private static final int MAX_SHAPES = 2;

    /** How much of one type's public surface is quoted. A whole Element is a page of text. */
    private static final int MAX_SHAPE_CHARS = 900;

    /** How many "what touches this" references are shown, of the index's own 40. */
    private static final int MAX_TOUCHING = 10;

    /** How many types may be brought in only because they declare a method the issue calls. */
    private static final int MAX_IMPLIED_TYPES = 3;

    /** How many existing test classes are named. */
    private static final int MAX_TEST_FILES = 6;

    /** A test named after a type the issue is about is the test for that type, not a coincidence. */
    private static final int NAMED_AFTER_THE_TYPE = 100;

    /** A test in the same package as a type the issue is about is that package's test suite. */
    private static final int SAME_PACKAGE_AS_THE_TYPE = 40;

    /** A type used by more of the project than this is context, not a lead. Warned about, not dropped. */
    private static final double COMMON_ENOUGH_TO_WARN = 0.25;

    /** Conventional test directories, for a caller with no build layout to hand. */
    private static final List<String> CONVENTIONAL_TEST_ROOTS =
        List.of("src/test/", "src/it/", "test/", "tests/");

    private ChangeNeighbourhood() {}

    /** One type the issue names or implies, and everything the index knows about where it lives. */
    public record NamedType(String fqn, String kind, String where, double howCommon,
                            boolean implied) {

        /** {@code org.jsoup.select.Selector (class) — src/main/java/…/Selector.java:37} */
        public String describe() {
            return fqn + (kind == null || kind.isBlank() ? "" : " (" + kind + ")")
                + (where == null || where.isBlank() ? "" : " — " + where);
        }
    }

    /** One test file that already exercises this area, and how much of it it touches. */
    public record CoveringTest(String file, int typesUsed) {}

    /**
     * The answer, and the rendered brief that goes in a prompt.
     *
     * @param types    the types the issue names or implies, in the order they were found
     * @param methods  the methods the issue names that the index has seen called
     * @param touching usages and implementations, capped and sorted
     * @param tests    the test files that already use these types, most first
     * @param brief    the whole thing rendered, capped — the string a prompt carries
     * @param note     plain English about what was read, or why nothing could be
     */
    public record Neighbourhood(List<NamedType> types, List<String> methods,
                                List<SemanticIndex.Ref> touching, List<CoveringTest> tests,
                                String brief, String note) {

        /** True when the index answered nothing worth putting in a prompt. */
        public boolean isEmpty() {
            return types.isEmpty() && methods.isEmpty() && tests.isEmpty();
        }

        /** How many file:line references the brief carries — the measurement, not a verdict. */
        public int references() {
            int refs = touching.size() + tests.size();
            for (NamedType type : types) {
                if (type.where() != null && type.where().contains(":")) {
                    refs++;
                }
            }
            return refs;
        }

        public int chars() {
            return brief.length();
        }

        /** One line for a log or a chain link. Reads correctly whether or not it found anything. */
        public String describe() {
            return types.size() + " type(s) " + types.stream().map(NamedType::fqn).toList()
                + ", " + methods.size() + " method(s) named, " + touching.size()
                + " usage(s) shown, " + tests.size() + " existing test file(s) "
                + tests.stream().map(CoveringTest::file).toList()
                + "; " + references() + " file:line reference(s) in " + brief.length()
                + " characters" + (note.isBlank() ? "" : " — " + note);
        }
    }

    /** The empty answer, carrying why. Never an exception: every caller must be able to go on. */
    public static Neighbourhood none(String why) {
        return new Neighbourhood(List.of(), List.of(), List.of(), List.of(), "",
            why == null ? "" : why);
    }

    /**
     * The size this brief may be against the working context a worker actually gets.
     *
     * <p>{@code ServerCapabilities.derivedWorkingContextTokens()} is a measurement of the endpoint,
     * not a constant, and on a smaller server 6,000 characters is a bigger share of the window than
     * design §2.2 budgeted for. Never larger than {@link #MAX_CHARS}, never below a floor at which
     * the brief would carry no references at all.
     */
    public static int forWorkingContext(int workingContextTokens) {
        if (workingContextTokens <= 0) {
            return MAX_CHARS;
        }
        int sized = (int) (workingContextTokens * SHARE_OF_CONTEXT * CHARS_PER_TOKEN);
        return Math.max(1_000, Math.min(MAX_CHARS, sized));
    }

    /** {@link #read(String, SemanticIndex, Collection, int)} at the full budget, conventions only. */
    public static Neighbourhood read(String issueText, SemanticIndex index) {
        return read(issueText, index, List.of(), MAX_CHARS);
    }

    /**
     * Reads the neighbourhood of one change request.
     *
     * @param issueText       the issue, verbatim — the only prose input there is
     * @param index           the structural index over the target; an unavailable one gives
     *                        {@link #none}
     * @param testSourceRoots repo-relative test source roots ({@code BuildLayout.Layout} knows
     *                        them); empty falls back to the conventional directories
     * @param maxChars        the ceiling on the rendered brief; see {@link #forWorkingContext}
     */
    public static Neighbourhood read(String issueText, SemanticIndex index,
                                     Collection<String> testSourceRoots, int maxChars) {
        if (issueText == null || issueText.isBlank()) {
            return none("the change request has no text to read");
        }
        if (index == null || !index.available()) {
            return none("the structural index could not answer: "
                + (index == null ? "none was built" : index.unavailableReason()));
        }

        // 1+2. what the issue names: the types, and the methods.
        List<String> methods = namedMethods(issueText, index);
        Map<String, NamedType> types = new LinkedHashMap<>();
        for (String fqn : namedTypes(issueText, index)) {
            if (types.size() >= MAX_TYPES) {
                break;
            }
            types.put(fqn, describe(fqn, index, false));
        }
        // 3. what the issue IMPLIES: the types that declare the methods it calls.
        int implied = 0;
        for (String fqn : ownersOf(methods, index, types.keySet())) {
            if (types.size() >= MAX_TYPES || implied >= MAX_IMPLIED_TYPES) {
                break;
            }
            if (types.putIfAbsent(fqn, describe(fqn, index, true)) == null) {
                implied++;
            }
        }
        List<NamedType> named = List.copyOf(types.values());

        // 4. what touches them.
        List<SemanticIndex.Ref> touching = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (NamedType type : named) {
            for (SemanticIndex.Ref ref : index.usagesOf(type.fqn())) {
                if (seen.add(ref.where() + "|" + ref.detail())) {
                    touching.add(ref);
                }
            }
            for (SemanticIndex.Ref ref : index.implementationsOf(type.fqn())) {
                if (seen.add(ref.where() + "|" + ref.detail())) {
                    touching.add(ref);
                }
            }
        }
        touching.sort(Comparator.comparing(SemanticIndex.Ref::file)
            .thenComparingInt(SemanticIndex.Ref::line));
        List<SemanticIndex.Ref> trimmed = touching.size() > MAX_TOUCHING
            ? List.copyOf(touching.subList(0, MAX_TOUCHING)) : List.copyOf(touching);

        // 5. the tests that already cover them — the line worth having.
        List<CoveringTest> tests = coveringTests(named, index, testSourceRoots);

        String note = index.degradedReason().isBlank() ? ""
            : "the index is partial: " + index.degradedReason();
        String brief = render(named, methods, tests, trimmed, touching.size(), index, maxChars);
        Neighbourhood result = new Neighbourhood(named, List.copyOf(methods), trimmed, tests,
            brief, note);
        log.info("neighbourhood of the change: {}", result.describe());
        return result;
    }

    // --- the queries -------------------------------------------------------------------------

    /**
     * Every type this text names that the project actually has, JDK types dropped.
     *
     * <p>{@link SemanticIndex#namesKnownIn} first, because it is the index's own answer and the one
     * that resolves a bare simple name. Then every capitalised SEGMENT of every dotted token, which
     * is what recovers the type out of {@code Jsoup.parseBodyFragment(html)}.
     */
    private static List<String> namedTypes(String issueText, SemanticIndex index) {
        // One walk, in READING ORDER. Order is not cosmetic here: the type the report wrote out
        // first is the one its author was talking about, and it breaks the tie when two test files
        // are equally near (see proximity). Resolving each capitalised segment is a superset of
        // SemanticIndex.namesKnownIn — that call resolves the whole dotted token or its last
        // segment, and both are segments — so nothing is lost by not calling it, and the answer
        // stops arriving in two batches with the second appended after the first.
        Set<String> found = new LinkedHashSet<>();
        String[] tokens = issueText.split("[^A-Za-z0-9_.]+");
        for (int at = 0; at < tokens.length; at++) {
            String[] segments = tokens[at].split("[.]");
            for (String segment : segments) {
                if (!looksLikeATypeName(segment)) {
                    continue;
                }
                found.addAll(index.resolve(segment));
            }
            // A type written as two English words — "Quirks Mode" for QuirksMode, "Tree Builder"
            // for TreeBuilder. Kept ONLY when the joined name resolves, so the cost of the guess
            // is nothing and the evidence for it is the project having that type.
            if (at + 1 < tokens.length && looksLikeATypeName(tokens[at])
                    && looksLikeATypeName(tokens[at + 1])) {
                found.addAll(index.resolve(tokens[at] + tokens[at + 1]));
            }
        }
        List<String> kept = new ArrayList<>();
        for (String fqn : found) {
            if (!isPlatform(fqn)) {
                kept.add(fqn);
            }
        }
        return kept;
    }

    /**
     * Every method this text calls that this project really declares.
     *
     * <p>{@link SemanticIndex#methodNamesKnownIn} is the base and is deliberately narrow — a
     * lowercase word only counts when it is written with parentheses or in camelCase, or every
     * question about "how do I get the root object" would match every call to anything named
     * {@code get}. Two things are added, and each was measured against a real bug report:
     *
     * <ol>
     *   <li><b>A nested call hides the inner method.</b> The index splits on a character class that
     *       keeps brackets, so {@code System.out.println(found.label())} is ONE token, and after
     *       its brackets are stripped the last dotted segment is {@code label)} — which matches
     *       nothing. Method-call shapes are found with a pattern here instead, so the inner call is
     *       seen.</li>
     *   <li><b>A method whose only callers are the JDK is not this project's method.</b> Finding
     *       every {@code identifier(} in a report also finds {@code println} and {@code get}. A
     *       method survives only when at least one type that declares a call to it belongs to the
     *       code being changed — which is the same reason platform types are dropped from the type
     *       list.</li>
     * </ol>
     */
    private static List<String> namedMethods(String issueText, SemanticIndex index) {
        Set<String> candidates = new LinkedHashSet<>(index.methodNamesKnownIn(issueText));
        Matcher calls = CALL_SHAPED.matcher(issueText);
        while (calls.find()) {
            String name = calls.group(1);
            if (name.length() >= 3 && Character.isLowerCase(name.charAt(0))) {
                candidates.add(name);
            }
        }
        List<String> kept = new ArrayList<>();
        for (String method : candidates) {
            for (SemanticIndex.Ref ref : index.usagesOf(method)) {
                String detail = ref.detail();
                if (detail != null && detail.contains("#")
                        && !isPlatform(detail.substring(0, detail.indexOf('#')).strip())) {
                    kept.add(method);
                    break;
                }
            }
        }
        return List.copyOf(kept);
    }

    /** {@code name(} — the shape a method call is written in, whatever is nested around it. */
    private static final Pattern CALL_SHAPED =
        Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");

    /** Capitalised, three letters or more, not an ALL-CAPS word. What a type name looks like. */
    private static boolean looksLikeATypeName(String word) {
        return word != null && word.length() >= 3 && Character.isUpperCase(word.charAt(0))
            && !word.equals(word.toUpperCase(Locale.ROOT));
    }

    /**
     * The types that DECLARE the methods the issue calls, most-called first.
     *
     * <p>A method name on its own is not evidence — {@code parse} is declared by nine types in
     * jsoup — so the count of resolved call sites does the ranking, and a type already named
     * outright is not repeated.
     */
    private static List<String> ownersOf(List<String> methods, SemanticIndex index,
                                         Set<String> already) {
        Map<String, Integer> weight = new LinkedHashMap<>();
        for (String method : methods) {
            for (SemanticIndex.Ref ref : index.usagesOf(method)) {
                String detail = ref.detail();
                if (detail == null || !detail.contains("#")) {
                    continue;
                }
                String owner = detail.substring(0, detail.indexOf('#')).strip();
                if (owner.isEmpty() || isPlatform(owner) || already.contains(owner)) {
                    continue;
                }
                weight.merge(owner, 1, Integer::sum);
            }
        }
        List<String> ranked = new ArrayList<>(weight.keySet());
        ranked.sort(Comparator.comparingInt((String fqn) -> -weight.get(fqn))
            .thenComparing(Comparator.naturalOrder()));
        return ranked;
    }

    /**
     * The test files that already exercise these types, nearest first.
     *
     * <p>Deliberately NOT {@code filesUsingAll(everything)}: a file using every type named is the
     * empty set on most issues, and the honest question is which test file is NEAREST, not which
     * one is perfect.
     *
     * <p><b>And deliberately not ranked by count alone,</b> which was tried and measured. Counting
     * how many of the named types a test file uses ranks a project's HTTP integration tests top of
     * every list, because they import the entry-point class, the document class and the parser on
     * their way to doing something else entirely: against jsoup issue 2266 ("setting the charset on
     * an empty XML document throws") a count-ranked list named six connection and fuzzing tests and
     * never named {@code DocumentTest}, which is the file the maintainer put their test in. Two
     * facts fix it and both are structural rather than lexical guesses:
     *
     * <ul>
     *   <li>a test class NAMED after one of the types — {@code DocumentTest} for {@code Document} —
     *       is that type's test suite by the convention every JVM project uses;</li>
     *   <li>a test in the same PACKAGE DIRECTORY as one of the types is that package's suite.</li>
     * </ul>
     *
     * <p>The count is still the third term, so a test that is neither still gets in when nothing
     * nearer exists.
     */
    private static List<CoveringTest> coveringTests(List<NamedType> types, SemanticIndex index,
                                                    Collection<String> testSourceRoots) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (NamedType type : types) {
            for (String file : index.filesUsingAll(List.of(type.fqn()))) {
                if (isTestFile(file, testSourceRoots)) {
                    hits.merge(file, 1, Integer::sum);
                }
            }
        }
        Map<String, Integer> score = new LinkedHashMap<>();
        hits.forEach((file, count) -> score.put(file, count + proximity(file, types)));
        List<CoveringTest> ranked = new ArrayList<>();
        hits.forEach((file, count) -> ranked.add(new CoveringTest(file, count)));
        ranked.sort(Comparator.comparingInt((CoveringTest t) -> -score.get(t.file()))
            .thenComparing(CoveringTest::file));
        return ranked.size() > MAX_TEST_FILES
            ? List.copyOf(ranked.subList(0, MAX_TEST_FILES)) : List.copyOf(ranked);
    }

    /**
     * How near a test file sits to any of these types: named after one, or beside one.
     *
     * <p>The type's POSITION costs a point, so that when two tests are equally near — one named
     * after the type the report wrote out, one named after a type inferred from a method call — the
     * one the report actually named wins. Without it the tie broke alphabetically, which is no
     * answer at all.
     */
    private static int proximity(String file, List<NamedType> types) {
        String path = file.replace('\\', '/');
        String simple = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
        String directory = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        // The NEAREST type wins, not the most of them. Summing across types rewards a test that
        // happens to sit in a package holding two of the named types over the test actually named
        // after one, which is how the selector-query test outranked the element test on jsoup
        // issue 2105 — a wrong answer produced by adding two right ones together.
        int nearest = 0;
        for (int at = 0; at < types.size(); at++) {
            int score = 0;
            NamedType type = types.get(at);
            String fqn = type.fqn();
            String typeName = fqn.substring(fqn.lastIndexOf('.') + 1);
            int nested = typeName.indexOf('$');
            if (nested > 0) {
                typeName = typeName.substring(0, nested);
            }
            if (simple.equals(typeName + "Test") || simple.equals("Test" + typeName)
                    || simple.equals(typeName + "Tests")) {
                score += NAMED_AFTER_THE_TYPE - at;
            }
            String typePackage = fqn.contains(".")
                ? fqn.substring(0, fqn.lastIndexOf('.')).replace('.', '/') : "";
            if (!typePackage.isEmpty() && directory.endsWith("/" + typePackage)) {
                score += SAME_PACKAGE_AS_THE_TYPE - at;
            }
            nearest = Math.max(nearest, score);
        }
        return nearest;
    }

    /**
     * Whether a repo-relative path is a test source.
     *
     * <p>The build's own answer when the caller has one — {@code BuildLayout} reads the real source
     * roots — and the conventional directories when it does not, because a neighbourhood that
     * silently names no tests on a project whose layout could not be read is worse than one that
     * goes by directory and is occasionally generous.
     */
    public static boolean isTestFile(String file, Collection<String> testSourceRoots) {
        if (file == null || file.isBlank()) {
            return false;
        }
        String path = file.replace('\\', '/');
        if (testSourceRoots != null && !testSourceRoots.isEmpty()) {
            for (String root : testSourceRoots) {
                if (root == null || root.isBlank()) {
                    continue;
                }
                String prefix = root.replace('\\', '/');
                if (!prefix.endsWith("/")) {
                    prefix = prefix + "/";
                }
                if (path.startsWith(prefix) || path.contains("/" + prefix)) {
                    return true;
                }
            }
            return false;
        }
        for (String convention : CONVENTIONAL_TEST_ROOTS) {
            if (path.startsWith(convention) || path.contains("/" + convention)) {
                return true;
            }
        }
        return false;
    }

    private static NamedType describe(String fqn, SemanticIndex index, boolean implied) {
        SemanticIndex.Ref declaration = index.declarationOf(fqn);
        String kind = declaration == null || declaration.detail() == null ? ""
            : declaration.detail().replace(fqn, "").strip();
        return new NamedType(fqn, kind, declaration == null ? "" : declaration.where(),
            index.howCommon(fqn), implied);
    }

    /** A JDK or platform type is named by half the bug reports ever written and is never the change. */
    private static boolean isPlatform(String fqn) {
        return fqn.startsWith("java.") || fqn.startsWith("javax.") || fqn.startsWith("jdk.")
            || fqn.startsWith("sun.") || fqn.startsWith("com.sun.");
    }

    // --- rendering ---------------------------------------------------------------------------

    /**
     * The brief, in the order it is worth reading, cut from the END when the budget runs out.
     *
     * <p>Types, then the tests that cover them, then the shape, then the usage dump — because the
     * usage dump is the longest and the least surprising, and losing its tail costs least.
     */
    private static String render(List<NamedType> types, List<String> methods,
                                 List<CoveringTest> tests, List<SemanticIndex.Ref> touching,
                                 int touchingTotal, SemanticIndex index, int maxChars) {
        if (types.isEmpty() && methods.isEmpty() && tests.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("THE NEIGHBOURHOOD OF THIS CHANGE — read out of this repository, not guessed. ")
          .append("Every line names a real file and line.\n");

        if (!types.isEmpty()) {
            sb.append("\nThe types this change request is about:\n");
            for (NamedType type : types) {
                sb.append("  ").append(type.describe());
                if (type.implied()) {
                    sb.append("  [not named outright — it declares a method the report calls]");
                }
                if (type.howCommon() >= COMMON_ENOUGH_TO_WARN) {
                    sb.append("\n      used by ")
                      .append(Math.round(type.howCommon() * 100))
                      .append("% of the files here — a change to it is not a small change");
                }
                sb.append('\n');
            }
        }
        if (!methods.isEmpty()) {
            sb.append("\nThe methods it names, that this code really calls: ")
              .append(String.join("(), ", methods)).append("()\n");
        }
        if (!tests.isEmpty()) {
            sb.append("\nWhere this behaviour is asserted TODAY — read one of these before you ")
              .append("write your own:\n");
            for (CoveringTest test : tests) {
                sb.append("  ").append(test.file()).append("  (uses ").append(test.typesUsed())
                  .append(" of the ").append(types.size()).append(" type(s) above)\n");
            }
        }
        String head = sb.toString();

        StringBuilder rest = new StringBuilder();
        for (int at = 0; at < Math.min(MAX_SHAPES, types.size()); at++) {
            String shape = index.publicShape(types.get(at).fqn());
            if (shape == null || shape.isBlank()) {
                continue;
            }
            rest.append("\nWhat ").append(types.get(at).fqn()).append(" looks like today:\n  ")
                .append(cut(shape, MAX_SHAPE_CHARS).replace("\n", "\n  ")).append('\n');
        }
        if (!touching.isEmpty()) {
            rest.append("\nWhat touches these types (").append(touching.size()).append(" of ")
                .append(touchingTotal).append(" the index found):\n");
            for (SemanticIndex.Ref ref : touching) {
                rest.append("  ").append(ref.where()).append("  ")
                    .append(ref.detail() == null ? "" : ref.detail()).append('\n');
            }
        }

        if (head.length() + rest.length() <= maxChars) {
            return head + rest;
        }
        if (head.length() >= maxChars) {
            // The head alone overran: keep whole lines, and say a line was dropped.
            return cutToLine(head, maxChars);
        }
        return head + cutToLine(rest.toString(), maxChars - head.length());
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n  …";
    }

    /** Cuts on a line boundary, so nothing is quoted as half a file reference. */
    private static String cutToLine(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        String clipped = text.substring(0, Math.max(0, max - 40));
        int lastLine = clipped.lastIndexOf('\n');
        return (lastLine > 0 ? clipped.substring(0, lastLine) : clipped)
            + "\n  … (more, left out for room)\n";
    }
}
