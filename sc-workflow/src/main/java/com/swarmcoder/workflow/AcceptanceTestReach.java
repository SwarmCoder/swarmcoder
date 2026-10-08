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

import com.swarmcoder.verify.BrowserOnlyCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An acceptance test may only call code that can run where the test runs: on a plain JVM. It may
 * not reach into a module whose code runs only in a browser.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 37, 2026-09-25, DeepSeek V4 Flash, {@code dev/bookshelf-demo}. The agreed check
 * was "after restarting the browser, previously added books and their ratings are still present".
 * The test author wrote {@code swarm.accept.BookPersistenceTest} in {@code bookshelf-demo-server}
 * and had it call {@code com.swarmcoder.demo.bookshelf.client.BookStore.getInstance()} — a class of
 * the TeaVM client module, which the server module has on its classpath only to package the
 * compiled JavaScript bundle. It then reset the singleton by reflection "to simulate a browser
 * restart". No server was running in the test, and the client's classes call {@code org.teavm.jso}
 * methods that are {@code native} on the JVM. Both workers wrote a BookStore that compiled; both
 * failed with {@code UnsatisfiedLinkError: org.teavm.jso.browser.Window.current()}. No code could
 * ever have passed.
 *
 * <p>Nothing caught it before the workers, and nothing could have from the evidence the gates had.
 * Every existing check was satisfied: {@code BookStore} was a design contract (so
 * {@link AcceptanceTestVocabulary} was content), the test did not implement it (so
 * {@link SelfImplementedContract} was content), and the red-check saw "BookStore does not compile",
 * which is exactly what a healthy test written before its code looks like. The author had even
 * been told, in one breath, that the client module was on its classpath AND that it must never
 * import the client layer.
 *
 * <h2>The rule</h2>
 *
 * <p>This check reads the files the author just wrote — imports and fully-qualified names, with
 * comments and strings stripped — and refuses any name that belongs to a browser-only module
 * ({@link BrowserOnlyCode.Survey#moduleOwning}) or to a browser runtime itself (TeaVM, ZeroZ
 * Stack's browser client, GWT, Elemental2). It is mechanical and it runs before any worker exists,
 * which is the whole point: a class that does not exist yet cannot fail at runtime, so the runtime
 * error is first visible on a candidate — too late.
 *
 * <p>The author is asked once, with the modules named, the reason, and what to do instead: prove
 * the behaviour through the modules that run on the JVM, at the layer that actually holds it. A
 * second miss parks the run, the same one-bounded-attempt shape as the vocabulary and
 * self-implementation re-asks.
 *
 * <h2>What it will not do</h2>
 *
 * <p>Accuse without evidence: a project with no browser-only module (or whose layout could not be
 * read) is checked only for direct browser-runtime imports, a split package that a JVM module also
 * holds is never refused, and an unreadable file concludes nothing.
 */
public final class AcceptanceTestReach {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceTestReach.class);

    private AcceptanceTestReach() {}

    /**
     * One name in a test that the JVM cannot run.
     *
     * @param path     the repo-relative test file
     * @param name     the qualified name as the test wrote it
     * @param module   the browser-only module that owns it, or null for a browser runtime package
     * @param evidence the line as written, trimmed
     */
    public record Reach(String path, String name, String module, String evidence) {
        public String render() {
            return "`" + name + "` — " + (module == null ? "a browser runtime class"
                : "code of " + module + ", which runs only in a browser") + ", in " + path
                + ": `" + evidence + "`";
        }
    }

    /**
     * @param findings every place a test reaches browser-only code; empty means it is runnable
     * @param survey   what the build says about where its code can run, for the sentences
     */
    public record Check(List<Reach> findings, BrowserOnlyCode.Survey survey) {

        public static final Check CLEAN = new Check(List.of(), BrowserOnlyCode.Survey.NONE);

        public boolean ok() {
            return findings.isEmpty();
        }

        /** The names reached, in order, without repeats. */
        public List<String> names() {
            List<String> names = new ArrayList<>();
            for (Reach reach : findings) {
                if (!names.contains(reach.name())) {
                    names.add(reach.name());
                }
            }
            return names;
        }
    }

    private static final Pattern IMPORT = Pattern.compile(
        "(?m)^[ \\t]*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;");
    // com.acme.client.BookStore written inline — lower-case package segments, then a type name.
    private static final Pattern QUALIFIED = Pattern.compile(
        "\\b((?:[a-z_][\\w]*\\.)+[A-Z][\\w]*)");

    /**
     * Reads the tests just written and reports every name in them that only a browser can run.
     *
     * @param repoRoot  the tree the tests were written into
     * @param survey    the build's browser-only modules; {@link BrowserOnlyCode.Survey#NONE} still
     *                  refuses a direct browser-runtime import, and nothing else
     * @param testPaths the repo-relative files the author just wrote
     */
    public static Check check(Path repoRoot, BrowserOnlyCode.Survey survey, List<String> testPaths) {
        if (repoRoot == null || testPaths == null || testPaths.isEmpty()) {
            return Check.CLEAN;
        }
        BrowserOnlyCode.Survey known = survey == null ? BrowserOnlyCode.Survey.NONE : survey;
        List<Reach> findings = new ArrayList<>();
        for (String path : testPaths) {
            String raw = read(repoRoot, path);
            if (raw.isEmpty()) {
                continue;
            }
            String source = SelfImplementedContract.strip(raw);
            Set<String> seen = new LinkedHashSet<>();
            Matcher imports = IMPORT.matcher(source);
            while (imports.find()) {
                String name = imports.group(2);
                if (imports.group(1) != null && imports.group(3) == null && name.contains(".")) {
                    name = name.substring(0, name.lastIndexOf('.')); // import static a.B.member
                }
                consider(path, name, line(source, imports.start()), known, seen, findings);
            }
            Matcher qualified = QUALIFIED.matcher(source);
            while (qualified.find()) {
                consider(path, qualified.group(1), line(source, qualified.start()), known, seen,
                    findings);
            }
        }
        return findings.isEmpty() ? new Check(List.of(), known) : new Check(List.copyOf(findings), known);
    }

    private static void consider(String path, String name, String evidence,
                                 BrowserOnlyCode.Survey survey, Set<String> seen, List<Reach> into) {
        if (name == null || name.startsWith("swarm.") || !seen.add(name)) {
            return;
        }
        if (BrowserOnlyCode.isBrowserRuntime(name)) {
            into.add(new Reach(path, name, null, evidence));
            return;
        }
        BrowserOnlyCode.Module module = survey.moduleOwning(name);
        if (module != null) {
            into.add(new Reach(path, name, module.name(), evidence));
        }
    }

    // ------------------------------------------------------------------ what people are told

    /**
     * The paragraph the architect (at DESIGN and PLAN) is given about modules that run only in a
     * browser. Empty when there are none, so the prompt is exactly what it was for every other
     * project.
     *
     * @param acceptanceModule where the acceptance tests will live, repo-relative; null or blank
     *                         when unknown
     */
    public static String architectBrief(BrowserOnlyCode.Survey survey, String acceptanceModule) {
        if (survey == null || !survey.any()) {
            return "";
        }
        String where = acceptanceModule == null || acceptanceModule.isBlank() ? ""
            : " (they live in " + acceptanceModule + ")";
        return "\n\nCODE THAT RUNS ONLY IN A BROWSER — read this before you decide which types the "
            + "checks are proved through.\n" + modulesSentence(survey)
            + "\nThe acceptance tests are JUnit tests on a plain JVM" + where + ". They can never "
            + "execute a line of " + namesOf(survey) + ": its classes call browser methods that "
            + "exist only once they are compiled to JavaScript, and on the JVM they throw "
            + "UnsatisfiedLinkError. So every behaviour a check proves must be reachable from code "
            + "that runs on the JVM" + jvmModulesClause(survey) + ", through a contract type there "
            + "(the service or store that holds the behaviour), and the check is proved at that "
            + "layer. A check worded as something a person does in the browser — \"after "
            + "restarting the browser, the books are still there\" — is proved by the server side "
            + "still holding them when it is asked again from scratch, never by driving browser "
            + "code. Never make a type in " + namesOf(survey) + " the only way to reach a checked "
            + "behaviour, and never name one as the type an acceptance test touches. "
            // Harness run 49, 2026-09-30: the client task was rejected as an enabler nothing
            // builds on, and the plan without it as missing the contract it delivers.
            + "The browser-side code itself is still built: plan the task that writes it (and "
            + "have it deliver the contracts the design names for those types). A task whose "
            + "write set is only in " + namesOf(survey) + " claims no check and needs no task "
            + "depending on it — no acceptance test can prove it; it is verified by "
            + "compiling — so never drop it for that.";
    }

    /**
     * What the design reviewer is told about modules that run only in a browser: the same fact
     * the architect designed from ({@link #architectBrief}), so the review does not send a design
     * back for doing what the architect was told to do. Empty when there are none, so the prompt
     * is exactly what it was for every other project.
     *
     * <p>Live run 98 (2026-10-08): the architect, told that no acceptance test can execute the
     * browser module, proved a check about a list on a screen at the service behind it. The
     * reviewer, told nothing of the build, objected under "testability" that a service-layer
     * test does not prove the display - before the revision and again after it. No revision
     * could answer that: the only test that would satisfy it is one that cannot run.
     */
    public static String reviewerBrief(BrowserOnlyCode.Survey survey, String acceptanceModule) {
        if (survey == null || !survey.any()) {
            return "";
        }
        String where = acceptanceModule == null || acceptanceModule.isBlank() ? ""
            : " (they live in " + acceptanceModule + ")";
        return "A FACT ABOUT THIS BUILD, read from its build files. " + modulesSentence(survey)
            + " The acceptance tests are JUnit tests on a plain JVM" + where + " and can never "
            + "execute a line of " + namesOf(survey) + ". So the architect was told to prove "
            + "every check through code that runs on the JVM" + jvmModulesClause(survey)
            + " - the service or store that holds the behaviour - including a check worded as "
            + "something a person sees or does on a screen, and to have the code in "
            + namesOf(survey) + " built by a task of its own that no acceptance test proves. "
            + "Judge testability by that: a check proved at the layer that holds the behaviour "
            + "IS verified by an executable test. NEVER object that such a check is not proved "
            + "through the screen or the browser, and never ask for an acceptance test that "
            + "runs code in " + namesOf(survey) + ". It is still right to object when the goal "
            + "promises a screen and the design has no type in " + namesOf(survey)
            + " that shows it.";
    }

    /**
     * The sentence the test author is given before it writes a line. Empty when the build has no
     * browser-only module, so every other project's prompt is exactly what it was.
     */
    public static String authorBrief(BrowserOnlyCode.Survey survey) {
        if (survey == null || !survey.any()) {
            return "";
        }
        List<String> packages = new ArrayList<>();
        for (BrowserOnlyCode.Module module : survey.browserOnly()) {
            packages.addAll(module.packages());
        }
        return "CODE THAT RUNS ONLY IN A BROWSER: " + modulesSentence(survey) + " A module like "
            + "that may be on your test's classpath — a server module packages the compiled "
            + "bundle — but your test is a JUnit test on a plain JVM, and the moment it calls one "
            + "of those classes it throws UnsatisfiedLinkError, even when everything compiles. No "
            + "code could ever make such a test pass. So never import or name a class from "
            + (packages.isEmpty() ? namesOf(survey) : "the packages " + String.join(", ", packages))
            + ", nor from org.teavm.*, com.zeroz4j.client.* or com.zeroz4j.ui.*. Prove the "
            + "behaviour through code that runs on the JVM" + jvmModulesClause(survey)
            + ": the service, the stored state, the rule it enforces. A criterion worded as "
            + "something a person does in the browser is proved at the layer that holds the "
            + "behaviour: \"after restarting the browser, the books are still there\" means they "
            + "are still there when the server side is asked again from scratch — a new service "
            + "over the same stored data — because restarting a browser changes nothing on the "
            + "server. Never simulate a browser by resetting client-side state.";
    }

    /** What the test author is told when its test reaches browser-only code — sent once. */
    public static String reask(Check check) {
        StringBuilder message = new StringBuilder("Your test calls code that can only run in a "
            + "browser, so it can never pass on the JVM it runs on — whatever the workers "
            + "write:\n");
        for (Reach reach : check.findings()) {
            message.append("  - ").append(reach.render()).append('\n');
        }
        message.append('\n').append(authorBriefOrGeneric(check.survey()))
            .append("\n\nReply with the same JSON object, with the corrected file(s).");
        return message.toString();
    }

    /**
     * What the test author is told when a test RAN and failed because it reached browser-only
     * code — before any candidate existed (the red-check), or on every candidate (verification).
     *
     * @param reachedSentence {@link BrowserOnlyCode#reachedIn}'s sentence
     * @param failureText     the failure as the runner reported it, frames included
     */
    public static String reaskForFailure(String reachedSentence, String failureText,
                                         BrowserOnlyCode.Survey survey) {
        return "Your test ran and " + reachedSentence + ".\n\nWhat the runner reported:\n"
            + (failureText == null ? "" : failureText.strip()) + "\n\nThat is not the code being "
            + "wrong and not a red state: no code anybody writes can make a JVM run a browser. "
            + authorBriefOrGeneric(survey)
            + "\n\nReply with the same JSON object, with the corrected file(s).";
    }

    /** The brief a run parks with when the author was asked once and the test still reaches it. */
    public static String brief(String taskTitle, Check check) {
        StringBuilder sb = new StringBuilder("The acceptance test(s) written for task '")
            .append(taskTitle).append("' call code that can only run in a browser, and the test "
                + "author was asked once to prove the check through code that runs on the JVM "
                + "and did not:\n");
        for (Reach reach : check.findings()) {
            sb.append("\n  - ").append(reach.render());
        }
        sb.append("\n\n").append(parkTail(check.survey()));
        return sb.toString();
    }

    /** The brief a run parks with when a test RAN into browser-only code and could not be fixed. */
    public static String failureBrief(String taskTitle, String reachedSentence, String why,
                                      BrowserOnlyCode.Survey survey) {
        return "The acceptance test(s) for task '" + taskTitle + "' can never pass: "
            + reachedSentence + "." + (why == null || why.isBlank() ? "" : " " + why.strip() + ".")
            + "\n\n" + parkTail(survey);
    }

    private static String parkTail(BrowserOnlyCode.Survey survey) {
        return (survey != null && survey.any() ? modulesSentence(survey) + " " : "")
            + "An acceptance test is a JUnit test on a plain JVM, so a test that calls that code "
            + "fails with UnsatisfiedLinkError for every candidate, however right the candidate "
            + "is. Dispatching a swarm at it would spend every worker on a test nobody can pass."
            + "\n\nDecide which side is wrong: correct the test to prove the check through the "
            + "module that holds the behaviour on the JVM (usually the server's service and its "
            + "stored data), or change the design so that module exposes a contract a test can "
            + "reach. Then resume the run.";
    }

    private static String authorBriefOrGeneric(BrowserOnlyCode.Survey survey) {
        String brief = authorBrief(survey);
        return !brief.isEmpty() ? brief
            : "Prove the check through code that runs on the JVM — the service, the stored "
                + "state, the rule — and never through a browser runtime such as org.teavm.*, "
                + "com.zeroz4j.client.* or com.zeroz4j.ui.*.";
    }

    private static String modulesSentence(BrowserOnlyCode.Survey survey) {
        StringBuilder sb = new StringBuilder();
        for (BrowserOnlyCode.Module module : survey.browserOnly()) {
            sb.append(sb.isEmpty() ? "" : " ").append(module.name()).append(" ")
                .append(module.evidence()).append(" — ").append(module.runtime())
                .append(" — so its code runs only in a web browser.");
        }
        return sb.toString();
    }

    private static String namesOf(BrowserOnlyCode.Survey survey) {
        return String.join(", ", survey.browserOnly().stream().map(BrowserOnlyCode.Module::name)
            .toList());
    }

    private static String jvmModulesClause(BrowserOnlyCode.Survey survey) {
        if (survey.jvmModules().isEmpty()) {
            return "";
        }
        return " (" + String.join(", ", survey.jvmModules().stream()
            .map(d -> d.isEmpty() ? "the repository root" : d).toList()) + ")";
    }

    private static String line(String source, int at) {
        while (at < source.length() - 1 && Character.isWhitespace(source.charAt(at))) {
            at++; // a match that starts on leading whitespace belongs to the line it reaches
        }
        int from = source.lastIndexOf('\n', at) + 1;
        int to = source.indexOf('\n', at);
        String text = (to < 0 ? source.substring(from) : source.substring(from, to)).strip();
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
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
