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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Task;
import com.swarmcoder.knowledge.ProjectTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An acceptance test may not supply its own implementation of a contract. A test that writes the
 * behaviour it is supposed to be measuring proves only that the test author can write Java: it
 * passes with no delivered code at all, on an empty tree, forever.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 30, 2026-09-05, 13:04. Story "rate a book", check "A user can assign a rating to
 * a book", test {@code swarm.accept.BookManagementTest#assignsRatingToBook}. The test author
 * wrote, in the server module's acceptance tree:
 *
 * <pre>{@code
 * BookService service = new BookService() {
 *     private final Book book = new Book();
 *     // ... its own in-memory implementation of the contract interface
 * };
 * }</pre>
 *
 * <p>Every gate downstream said something true and useless. It failed on both wave-3 candidates —
 * because of the field-initialisation order in the test's own anonymous class, nothing to do with
 * either candidate — and that was read as an ordinary failure. The test author "repaired" it by
 * moving three lines. Both candidates then passed. No candidate code was executed at any point,
 * and the story was stamped delivered at 13:10.
 *
 * <p>{@link AcceptanceTestVocabulary} could not catch it: {@code BookService} and {@code Book} are
 * contract types, which is exactly what that check demands. The test was written in perfect
 * vocabulary and meant nothing.
 *
 * <h2>The line this class draws</h2>
 *
 * <p>Naming a contract is required. <b>Being</b> one is forbidden. A test may instantiate what the
 * plan delivers and call it; it may not stand in for it — no anonymous class, no lambda, no local
 * or nested class, no subclass, and no mock or stub of a contract type. The whole point of an
 * acceptance test is that the delivered code is on the stack when the assertion runs.
 *
 * <h2>What it will not do</h2>
 *
 * <p>Accuse on a guess, for the same reason {@link AcceptanceTestVocabulary} will not: a false
 * accusation here re-asks the test author and then stops a run. Comments and string literals are
 * stripped before anything is matched, so the word "implements" in a sentence is not evidence. A
 * plain {@code new Book()} is not evidence — only {@code new Book() &#123;}, an anonymous body,
 * is. And a mock is evidence only when it is a mock OF a contract type ({@code mock(X.class)},
 * {@code @Mock X x}): a mocking framework on the imports proves nothing by itself, because the test
 * may be mocking a clock (audit of 2026-10-02 — until then any test that imported one and so much
 * as named a contract type was sent back).
 *
 * <h2>Pointing at a real one, harness run 38, 2026-09-25</h2>
 *
 * <p>{@link #wiring} tells the author how the application obtains one of these — but until run 38
 * that sentence could only name an implementation already sitting in the checkout, or one the
 * design happened to fix as its own contract. In the wave that is about to WRITE the
 * implementation, neither is true yet, and the sentence fell back to the generic "obtain it
 * however this project hands one out" — exactly the situation that left run 38's test author with
 * nowhere to turn: told to stop implementing the contract, and refused for naming the concrete
 * class a task's write set already promised. {@link PlannedImplementations} closes that gap: the
 * wiring sentence now also searches the plan's OWN tasks for a write-set entry matching one of the
 * usual implementation shapes, and can name a class before a single line of it has been written.
 *
 * <h2>Reading a lambda that was never there, harness run 42, 2026-09-26</h2>
 *
 * <p>The lambda check made the mistake it exists to catch elsewhere: it read code that only USED a
 * delivered class as though it had implemented one. {@code Book existing = service.getBooks()
 * .stream().filter(b -> "The Hobbit".equals(b.title))...} — an ordinary stream predicate three
 * method calls after the "=" — was reported as the test writing {@code Book}'s own lambda. Both
 * authoring attempts were rejected on it, and the run parked before a single worker ran. Fixed two
 * ways at once: the lambda must now be the WHOLE right-hand side, not merely contain an arrow
 * before the next {@code ;}; and the check is never applied to a contract the checkout confirms is
 * a class, enum, record or annotation type, because a lambda cannot implement one of those however
 * its surrounding text looks. The same audit found {@link #SUBTYPE} misreading a generic type
 * parameter's bound and a wildcard bound the identical way (see {@code matchSubtype}).
 */
public final class SelfImplementedContract {

    private static final Logger log = LoggerFactory.getLogger(SelfImplementedContract.class);

    private SelfImplementedContract() {}

    /**
     * One place a test stands in for a contract instead of using it.
     *
     * @param path         the repo-relative test file
     * @param contractType the contract's simple name, as the test wrote it
     * @param how          plain English for what the test did, e.g. "an anonymous class"
     * @param evidence     the line as written, trimmed — so the author sees its own code back
     */
    public record SelfImplementation(String path, String contractType, String how,
                                     String evidence) {
        public String render() {
            return "`" + contractType + "` — " + how + " in " + path + ": `" + evidence + "`";
        }
    }

    /**
     * @param findings the places the test implements a contract itself; empty means it is honest
     * @param wiring   how the application itself obtains one of these, in the words the test author
     *                 is told to use. Never blank: there is always a fallback sentence.
     */
    public record Check(List<SelfImplementation> findings, String wiring) {

        public static final Check CLEAN = new Check(List.of(), "");

        public boolean ok() {
            return findings.isEmpty();
        }

        /** The contract types this test stood in for, in order, without repeats. */
        public List<String> contractTypes() {
            List<String> names = new ArrayList<>();
            for (SelfImplementation finding : findings) {
                if (!names.contains(finding.contractType())) {
                    names.add(finding.contractType());
                }
            }
            return names;
        }
    }

    // new BookService() { — an anonymous body. The trailing brace is what separates standing in
    // for the type from simply constructing what the plan delivers, which is the whole point.
    private static final Pattern ANONYMOUS = Pattern.compile(
        "\\bnew\\s+([A-Z][A-Za-z0-9_]*)\\s*(?:<[^;{}]*>)?\\s*\\([^;()]*\\)\\s*\\{");
    // class X implements BookService / class X extends AbstractBookStore
    private static final Pattern SUBTYPE = Pattern.compile(
        "\\b(?:implements|extends)\\s+([A-Z][A-Za-z0-9_]*)");
    // BookService s = () -> ... / BookService s = x -> ...  — a functional-interface contract
    // implemented in place, as the WHOLE right-hand side: nothing between "=" and the parameter
    // list, or between the parameter list and "->", except optional whitespace.
    //
    // Harness run 42, 2026-09-26: the previous pattern (`= [^;]*->`) matched a lambda anywhere
    // between the "=" and the next ";", so
    //   Book existing = service.getBooks().stream().filter(b -> "The Hobbit".equals(b.title))...
    // — an ordinary use of a delivered class, whose only lambda is a filter predicate three method
    // calls deep — misread as the test standing in for `Book`. `Book` is a @DataModel class with
    // public fields, not an interface, and the "->" belongs to a stream pipeline's predicate, not
    // to `existing`'s initializer. Requiring the lambda to BE the initializer (this pattern) and
    // restricting the match, in `scan`, to contract types the checkout confirms are interfaces
    // (a lambda can never implement a class, whatever the surrounding text looks like — see
    // {@link com.swarmcoder.knowledge.ProjectTypes#isInterface}) closes this the same way twice
    // over: syntactically and by type.
    private static final Pattern LAMBDA = Pattern.compile(
        "\\b([A-Z][A-Za-z0-9_]*)\\s*(?:<[^;=]*>)?\\s+[a-zA-Z_$][\\w$]*\\s*=\\s*"
            + "(?:\\([^()]*\\)|[a-zA-Z_$][\\w$]*)\\s*->");
    // mock(BookService.class), spy(BookService.class), Mockito.mock(BookService.class)
    private static final Pattern MOCK_OF = Pattern.compile(
        "\\b(?:mock|spy|createMock|createNiceMock|createStrictMock|niceMock|strictMock)"
            + "\\s*\\(\\s*([A-Z][A-Za-z0-9_]*)\\s*\\.class");
    /** {@code @Mock private LogbookService service;} and the like. */
    private static final Pattern MOCK_FIELD = Pattern.compile(
        "@(?:Mock|Spy|MockBean|SpyBean|MockitoBean|MockitoSpyBean)\\b(?:\\s*\\([^)]*\\))?\\s+"
            + "(?:(?:private|protected|public|final|static)\\s+)*([A-Z][A-Za-z0-9_]*)\\b");
    // A hand-written stand-in named after the contract: BookServiceStub, FakeBookService.
    private static final Pattern DECLARED_TYPE = Pattern.compile(
        "\\b(?:class|interface|record|enum)\\s+([A-Z][A-Za-z0-9_]*)");
    /**
     * Reads the tests just written and reports every place they implement a contract themselves.
     * Equivalent to {@link #check(Path, DesignDocument, List, List)} with no plan tasks — kept so a
     * caller with no plan to offer behaves exactly as it did before harness run 38.
     *
     * @param repoRoot  the tree the tests were written into — the run's own tests worktree
     * @param design    the design whose contracts define what may not be stood in for; null means
     *                  there is nothing to protect and nothing is checked
     * @param testPaths the repo-relative files the author just wrote
     */
    public static Check check(Path repoRoot, DesignDocument design, List<String> testPaths) {
        return check(repoRoot, design, testPaths, List.of());
    }

    /**
     * Reads the tests just written and reports every place they implement a contract themselves.
     *
     * @param repoRoot  the tree the tests were written into — the run's own tests worktree
     * @param design    the design whose contracts define what may not be stood in for; null means
     *                  there is nothing to protect and nothing is checked
     * @param testPaths the repo-relative files the author just wrote
     * @param planTasks every task in this run's plan, so the wiring sentence can point at a
     *                  concrete implementation class a task's write set promises even before it
     *                  exists in the checkout (harness run 38, 2026-09-25 — see {@link
     *                  AcceptanceTestVocabulary} for the run this reads the same write sets for)
     */
    public static Check check(Path repoRoot, DesignDocument design, List<String> testPaths,
                              List<Task> planTasks) {
        if (repoRoot == null || design == null || testPaths == null || testPaths.isEmpty()) {
            return Check.CLEAN;
        }
        Map<String, String> contractsBySimpleName = contractsBySimpleName(design);
        if (contractsBySimpleName.isEmpty()) {
            // The design fixed no type names, so there is nothing here anybody could stand in for.
            return Check.CLEAN;
        }
        // Which of the contracts the checkout CONFIRMS are classes (or enums, records, annotation
        // types) rather than interfaces — never a lambda's target, whatever a line of test code
        // looks like (harness run 42, 2026-09-26; see LAMBDA and `classContracts`).
        Set<String> classContracts = classContracts(repoRoot, contractsBySimpleName);
        List<SelfImplementation> findings = new ArrayList<>();
        for (String path : testPaths) {
            String raw = read(repoRoot, path);
            if (raw.isEmpty()) {
                continue; // unreadable: told us nothing, so nothing is concluded
            }
            scan(path, strip(raw), contractsBySimpleName.keySet(), classContracts, findings);
        }
        if (findings.isEmpty()) {
            return Check.CLEAN;
        }
        Set<String> offended = new LinkedHashSet<>();
        for (SelfImplementation finding : findings) {
            offended.add(finding.contractType());
        }
        return new Check(List.copyOf(findings),
            wiring(repoRoot, contractsBySimpleName, offended, PlannedImplementations.of(planTasks)));
    }

    /**
     * The contract simple names the checkout confirms are declared as a class, enum, record or
     * annotation type — never a plain interface. {@link #LAMBDA} is never allowed to fire on one
     * of these, however its text looks: a lambda literal cannot compile against anything but an
     * interface, so a "lambda" the pattern reads against a class is always a misread of some other
     * expression, never a genuine self-implementation (harness run 42, 2026-09-26 — the class
     * {@code Book} was flagged this way).
     *
     * <p>A contract the checkout does not declare at all — not delivered yet — is deliberately
     * left OUT of this set, not put in it: there is nothing here to confirm it is a class, and
     * {@link SelfImplementedContract} reports, it does not accuse on a guess, in either direction.
     * An undelivered interface must still be caught if a test stands in for it.
     */
    private static Set<String> classContracts(Path repoRoot,
                                               Map<String, String> contractsBySimpleName) {
        Set<String> classes = new LinkedHashSet<>();
        ProjectTypes tree;
        try {
            tree = ProjectTypes.of(repoRoot);
        } catch (RuntimeException unreadable) {
            log.warn("Could not read the checkout's types to tell contract classes from "
                + "interfaces: {}", unreadable.toString());
            return classes; // unreadable checkout confirms nothing, so nothing is excluded
        }
        for (Map.Entry<String, String> entry : contractsBySimpleName.entrySet()) {
            String fullName = entry.getValue();
            if (fullName != null && tree.declares(fullName) && !tree.isInterface(fullName)) {
                classes.add(entry.getKey());
            }
        }
        return classes;
    }

    private static void scan(String path, String source, Set<String> contracts,
                             Set<String> classContracts, List<SelfImplementation> into) {
        match(ANONYMOUS, source, contracts, path, "the test writes an anonymous class that IS the "
            + "contract", into);
        matchSubtype(source, contracts, path, into);
        Set<String> lambdaEligible = new LinkedHashSet<>(contracts);
        lambdaEligible.removeAll(classContracts);
        match(LAMBDA, source, lambdaEligible, path, "the test writes a lambda that IS the contract",
            into);
        match(MOCK_OF, source, contracts, path, "the test mocks or stubs the contract", into);
        match(MOCK_FIELD, source, contracts, path, "the test mocks or stubs the contract", into);
        // A stand-in named after the contract — BookServiceStub, FakeBookService — declared in the
        // test file itself. The SUBTYPE match above catches it whenever it says so in Java; this
        // catches the shape where the contract is a class and the stand-in only borrows the name.
        Matcher declared = DECLARED_TYPE.matcher(source);
        while (declared.find()) {
            String name = declared.group(1);
            String contract = standInFor(name, contracts);
            if (contract != null) {
                add(into, new SelfImplementation(path, contract,
                    "the test declares `" + name + "`, its own stand-in for the contract",
                    line(source, declared.start())));
            }
        }
        // A mocking framework on the imports is not, by itself, a mocked contract: the test may
        // mock a clock. Only a mock OF a contract type, found above, is a finding.
    }

    private static void match(Pattern pattern, String source, Set<String> contracts, String path,
                              String how, List<SelfImplementation> into) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (contracts.contains(name)) {
                add(into, new SelfImplementation(path, name, how, line(source, matcher.start())));
            }
        }
    }

    /**
     * {@link #SUBTYPE}, with the same "anywhere in the line" weakness {@link #LAMBDA} had, found
     * during the run-42 fix while auditing the sibling checks. {@code extends} is not only how a
     * class declares its superclass — {@code <T extends Book>} (a type parameter's bound) and
     * {@code List<? extends Book>} (a wildcard bound) use the identical keyword and are ordinary,
     * common Java that names a contract type without a test standing in for it at all. Both shapes
     * sit inside an open {@code <...>}; an actual {@code class X extends Y} or
     * {@code class X implements Y} never does. Skipping a match found inside an unclosed angle
     * bracket, counted back to the nearest statement or block boundary, keeps the real case —
     * {@code class Stub implements BookService} — and drops both bound shapes.
     */
    private static void matchSubtype(String source, Set<String> contracts, String path,
                                     List<SelfImplementation> into) {
        Matcher matcher = SUBTYPE.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (contracts.contains(name) && !insideGenericBrackets(source, matcher.start())) {
                add(into, new SelfImplementation(path, name,
                    "a class in the test implements or extends the contract",
                    line(source, matcher.start())));
            }
        }
    }

    /**
     * True when {@code pos} sits inside an angle-bracket pair left open earlier in the same
     * statement or block — a generic type parameter's bound or a wildcard bound, neither of which
     * is a class declaring a supertype. Counts brackets only back to the nearest {@code ;},
     * {@code &#123;} or {@code &#125;}, so an unrelated {@code <>} in an earlier statement is never
     * counted.
     */
    private static boolean insideGenericBrackets(String source, int pos) {
        int from = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == ';' || c == '{' || c == '}') {
                from = i + 1;
                break;
            }
        }
        int depth = 0;
        for (int i = from; i < pos; i++) {
            char c = source.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            }
        }
        return depth > 0;
    }

    /** True when {@code name} is a stand-in named after one of the contracts. */
    private static String standInFor(String name, Set<String> contracts) {
        for (String contract : contracts) {
            for (String shape : List.of(contract + "Stub", contract + "Fake", contract + "Mock",
                    contract + "Double", "Stub" + contract, "Fake" + contract, "Mock" + contract,
                    "InMemory" + contract)) {
                if (name.equals(shape)) {
                    return contract;
                }
            }
        }
        return null;
    }

    private static boolean hasFinding(List<SelfImplementation> findings, String path,
                                      String contract) {
        for (SelfImplementation finding : findings) {
            if (finding.path().equals(path) && finding.contractType().equals(contract)) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<SelfImplementation> findings, SelfImplementation finding) {
        for (SelfImplementation existing : findings) {
            if (existing.path().equals(finding.path())
                    && existing.contractType().equals(finding.contractType())
                    && existing.how().equals(finding.how())) {
                return;
            }
        }
        findings.add(finding);
    }

    /** The whole source line a match sits on, trimmed and shortened, so the author sees its code. */
    private static String line(String source, int at) {
        int from = source.lastIndexOf('\n', at) + 1;
        int to = source.indexOf('\n', at);
        String text = (to < 0 ? source.substring(from) : source.substring(from, to)).strip();
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }

    /**
     * The wiring sentence for a design's contracts as a whole, for a caller that has no findings
     * to point at — the post-repair tautology check, which knows the test proves nothing but not
     * which type it stood in for. Never blank.
     */
    public static String wiringFor(Path repoRoot, DesignDocument design) {
        return wiringFor(repoRoot, design, List.of());
    }

    /**
     * Same as {@link #wiringFor(Path, DesignDocument)}, and lets the sentence point at a concrete
     * implementation class a task's write set promises even before it exists in the checkout
     * (harness run 38, 2026-09-25).
     *
     * @param planTasks every task in this run's plan; empty or null behaves exactly as before
     */
    public static String wiringFor(Path repoRoot, DesignDocument design, List<Task> planTasks) {
        if (repoRoot == null || design == null) {
            return fallbackWiring(Set.of());
        }
        Map<String, String> contracts = contractsBySimpleName(design);
        if (contracts.isEmpty()) {
            return fallbackWiring(Set.of());
        }
        return wiring(repoRoot, contracts, new LinkedHashSet<>(contracts.keySet()),
            PlannedImplementations.of(planTasks));
    }

    /** Simple name to fully-qualified name, for every contract that fixes a type. */
    private static Map<String, String> contractsBySimpleName(DesignDocument design) {
        Map<String, String> byName = new LinkedHashMap<>();
        List<ApiContract> contracts = design.contracts() == null ? List.of() : design.contracts();
        for (ApiContract contract : contracts) {
            if (contract != null && contract.namesAType()) {
                byName.putIfAbsent(contract.simpleTypeName(), contract.typeName().strip());
            }
        }
        return byName;
    }

    /**
     * How the application itself gets hold of one of these — the sentence the test author is told
     * to follow instead of writing its own. Read off the checkout, never guessed: the contract's
     * own source file says whether this project wires services through CDI, and the checkout says
     * whether an implementation type exists to name.
     *
     * <p>There is always an answer, because the fallback is the true general one: obtain the
     * delivered implementation the way the application does. An unreadable checkout costs the
     * precision of the sentence and nothing else.
     *
     * <p>{@code planned} adds a third source, tried after a design-fixed shape and an existing tree
     * type: a task's write set naming one of the usual implementation shapes for a contract not yet
     * built (harness run 38, 2026-09-25). Naming a contract's real implementation is exactly what a
     * test SHOULD do, so the sentence must be able to name one even in the wave that is about to
     * write it — the checkout alone cannot, because the file does not exist there yet.
     */
    static String wiring(Path repoRoot, Map<String, String> contractsBySimpleName,
                         Set<String> offended, List<PlannedImplementations.Planned> planned) {
        ProjectTypes tree;
        try {
            tree = ProjectTypes.of(repoRoot);
        } catch (RuntimeException unreadable) {
            log.warn("Could not read the checkout's types for the wiring sentence: {}",
                unreadable.toString());
            return fallbackWiring(offended);
        }
        List<PlannedImplementations.Planned> plan = planned == null ? List.of() : planned;
        String implementation = null;
        boolean cdi = false;
        for (String contract : offended) {
            String fullName = contractsBySimpleName.get(contract);
            cdi = cdi || usesCdi(tree, fullName);
            for (String shape : List.of(contract + "Impl", "Default" + contract,
                    contract + "Implementation", contract + "Bean")) {
                // The design's own contracts first: when a design fixes the implementation type,
                // that IS the wiring, and it is true before anybody has written the file.
                String agreed = contractsBySimpleName.get(shape);
                if (agreed != null && implementation == null) {
                    implementation = agreed;
                }
                for (String candidate : tree.fullNames()) {
                    if (candidate.endsWith("." + shape) || candidate.equals(shape)) {
                        cdi = cdi || usesCdi(tree, candidate);
                        if (implementation == null) {
                            implementation = candidate;
                        }
                    }
                }
                if (implementation == null) {
                    // Not built yet, but a task's write set already promises it — the plan's own
                    // word that the file is coming, read the same way AcceptanceTestVocabulary
                    // reads it for the vocabulary a test may name.
                    for (PlannedImplementations.Planned candidate : plan) {
                        if (candidate.simpleName().equals(shape)) {
                            implementation = candidate.typeName();
                            break;
                        }
                    }
                }
            }
        }
        String first = offended.iterator().next();
        if (cdi) {
            return "this project wires its services through CDI, so ask the container for one — "
                + "`CDI.current().select(" + first + ".class).get()`, or `@Inject` it"
                + (implementation == null ? "" : " (the delivered implementation is `"
                    + implementation + "`)");
        }
        if (implementation != null) {
            return "construct the delivered implementation `" + implementation + "` and use it "
                + "through the contract";
        }
        return fallbackWiring(offended);
    }

    private static String fallbackWiring(Set<String> offended) {
        String first = offended.isEmpty() ? "the contract" : offended.iterator().next();
        return "obtain the implementation this plan delivers the way the application obtains it — "
            + "the delivered `" + first + "` type, or however this project hands one out — never a "
            + "class you write inside the test";
    }

    private static boolean usesCdi(ProjectTypes tree, String fullName) {
        if (fullName == null) {
            return false;
        }
        Path file = tree.fileOf(fullName);
        if (file == null) {
            return false;
        }
        try {
            String source = Files.readString(file);
            return source.contains("jakarta.enterprise") || source.contains("jakarta.inject")
                || source.contains("javax.enterprise") || source.contains("javax.inject")
                || source.contains("@ApplicationScoped") || source.contains("CDI.current(");
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * What the test author is told when its test stands in for a contract — the same one-bounded-
     * attempt shape as {@link AcceptanceTestVocabulary#reask}: the complaint first, then the fix.
     */
    public static String reask(Check check) {
        List<String> types = check.contractTypes();
        StringBuilder names = new StringBuilder();
        for (String type : types) {
            names.append(names.isEmpty() ? "" : ", ").append('`').append(type).append('`');
        }
        StringBuilder message = new StringBuilder("Your test implements ").append(names)
            .append(" itself, so it can pass with no delivered code at all. Obtain the real ")
            .append(types.size() == 1 ? "one" : "ones").append(" the way the application does — ")
            .append(check.wiring()).append(" — and assert on what it does.\n\nWhat you wrote:\n");
        for (SelfImplementation finding : check.findings()) {
            message.append("  - ").append(finding.render()).append('\n');
        }
        message.append("\nAn acceptance test exists to prove that the DELIVERED code behaves. A "
            + "test that supplies its own version of the contract — an anonymous class, a lambda, "
            + "a nested class, a subclass, a mock or a stub — passes on an empty tree and proves "
            + "nothing about anybody's work. The delivered code must be on the stack when your "
            + "assertion runs.\n\nReply with the same JSON object, with the corrected file(s).");
        return message.toString();
    }

    /** The brief a run parks with when the second attempt still stands in for a contract. */
    public static String brief(String taskTitle, Check check) {
        List<String> types = check.contractTypes();
        StringBuilder sb = new StringBuilder("The acceptance test(s) written for task '")
            .append(taskTitle).append("' implement ")
            .append(types.size() == 1 ? "the contract they are supposed to be measuring"
                : "the contracts they are supposed to be measuring")
            .append(", and the test author was asked once to use the delivered code instead and "
                + "did not:\n");
        for (SelfImplementation finding : check.findings()) {
            sb.append("\n  - ").append(finding.render());
        }
        sb.append("\n\nA test that supplies its own implementation of a contract passes with no "
            + "delivered code at all. It goes green on an empty tree, every candidate 'passes' it "
            + "without a line of its own code being executed, and the story is stamped delivered "
            + "on a test that exercises only itself. Nothing further down this run can tell that "
            + "apart from an honest pass.\n\n"
            + "The application obtains one like this: ").append(check.wiring()).append(".\n\n"
            + "Decide which side is wrong: correct the test to obtain the delivered "
            + "implementation, or — if there is genuinely no way for a test in this module to "
            + "reach one — change the design so there is. Then resume the run.");
        return sb.toString();
    }

    /**
     * The sentence a repaired test is sent back with when it turns out to be green on the tree
     * before any candidate ran. Same rule, discovered a different way: a test nothing had to
     * deliver anything for is a test that measures itself.
     */
    public static String tautologyReask(String wiring) {
        return "Your repaired test passes on the tree as it was BEFORE any of this work — with "
            + "nothing delivered at all. That means it proves nothing about anybody's code: it "
            + "measures only itself, and every candidate would 'pass' it without a line of its own "
            + "code being executed.\n\nA test written before the code must FAIL until the code "
            + "exists. Obtain the delivered implementation the way the application does — " + wiring
            + " — and assert on what it does, so that the test is red now and green only once the "
            + "work is done.\n\nReply with the same JSON object, with the corrected file(s).";
    }

    /** The brief a run parks with when the repaired test is still green on the untouched tree. */
    public static String tautologyBrief(String taskTitle, String testClass, String wiring,
                                        String note) {
        return "The acceptance test '" + testClass + "' for task '" + taskTitle + "' was repaired "
            + "by its author, and the repaired test PASSES on the tree as it was before any work "
            + "started — with nothing delivered."
            + (note == null || note.isBlank() ? "" : " " + note.strip() + ".")
            + "\n\nThat is a test that measures itself. It would go green for every candidate "
            + "without any candidate's code being executed, and the story would be stamped "
            + "delivered on it. The test author was asked once to correct this and did not.\n\n"
            + "The application obtains the delivered code like this: " + wiring + ".\n\n"
            + "Correct the test so that it exercises the delivered implementation and is red until "
            + "that implementation exists, then resume the run.";
    }

    /**
     * Comments and string literals removed, so the words {@code implements}, {@code extends} and
     * {@code mock} are only ever matched where they are code. Line structure is preserved — the
     * evidence line the author is shown has to be the line it wrote.
     */
    static String strip(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n
                        && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (c == '"') {
                out.append("\"\"");
                i++;
                while (i < n && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        i++;
                    }
                    if (source.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(n, i + 1);
            } else if (c == '\'') {
                out.append("' '");
                i++;
                while (i < n && source.charAt(i) != '\'') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        i++;
                    }
                    i++;
                }
                i = Math.min(n, i + 1);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String read(Path repoRoot, String relativePath) {
        try {
            return Files.readString(repoRoot.resolve(relativePath.replace('\\', '/')));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the authored test {}: {}", relativePath, e.toString());
            return "";
        }
    }
}
