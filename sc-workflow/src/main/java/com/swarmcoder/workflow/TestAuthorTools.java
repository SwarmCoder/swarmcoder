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

import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.verify.JourneyFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The test author's own two tools in a lookup session ({@link LookupAgent}): {@code compile_test},
 * which tells it what the build's own check will say about a draft BEFORE it hands the draft in,
 * and {@code report_done}, which hands in the draft as last compiled.
 *
 * <p><b>Why a compile.</b> The red check that follows authoring reads a test that does not compile
 * two ways: a healthy red (the planned code is not written yet) or a broken test (it misuses code
 * that exists, or names something nobody delivers). Until 2026-10-02 the author found out which
 * only after it had answered, through one bounded re-ask; live runs of the two days before spent
 * those re-asks on invented library methods and wrong arguments. Here it sees the same verdict,
 * from the same check, while it can still fix the draft.
 *
 * <p><b>A task that changes a screen</b> (section 63) has two more: {@code check_journey}, which
 * reads a draft journey and says what is wrong with it, with no model, and keeps a valid one to be
 * handed in with the test; and {@code texts_of}, the tree's answer to "what does this screen show".
 *
 * <p><b>What it may write.</b> Only files under the task's protected acceptance-test directory.
 * Nothing is written by this class at all: a draft is text held here until it is handed in, and the
 * check it is given decides where a draft is compiled.
 */
public final class TestAuthorTools {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(TestAuthorTools.class);

    /**
     * A safety stop, not a budget: how many drafts one session may compile before further ones
     * are refused. {@code swarmcoder.roles.maxDrafts} replaces it.
     */
    static final int MAX_COMPILES = Integer.getInteger("swarmcoder.roles.maxDrafts", 12);

    /** What the check said about a draft. */
    public record Verdict(boolean healthy, String text) {}

    private final ExpertTools session;
    private final String protectedDir;
    private final String writeDir;
    private volatile Function<Map<String, String>, Verdict> check;

    /** Path to content, as last given to {@code compile_test}. */
    private final Map<String, String> draft = new LinkedHashMap<>();
    /** The draft as it stood the last time the check called it healthy; null until then. */
    private Map<String, String> lastHealthy;
    private boolean lastWasHealthy;
    private int compiles;
    private String wrote = "";
    private boolean handedIn;
    /** Path to content of every journey {@code check_journey} called valid; handed in as they are. */
    private final Map<String, String> journeys = new LinkedHashMap<>();
    /** Null unless this task is to write a journey; then what a draft is held to besides its form. */
    private java.util.function.BiFunction<String, String, String> journeyObjection;
    private int journeyChecks;
    /** Whether "no visible effect" may be answered in place of a journey; and that answer. */
    private boolean journeyWaivable;
    private String journeyWaiver;
    private String journeyWaiverPath;
    /** Whether the project as it stands holds a text; null when that cannot be asked. */
    private java.util.function.Predicate<String> heldByTheProject;
    /** The journey last sent back with the question about a text nobody enters, as it was given. */
    private String askedAboutUnentered;
    /** True while the session reviews a journey that failed: no test is compiled or handed in. */
    private boolean journeyOnly;

    /**
     * @param session      the session's toolbox; every call here runs through it, so it is logged
     *                     on one line and carries the turn countdown
     * @param protectedDir the task's acceptance-test directory; nothing outside it is accepted
     * @param writeDir     where a new test class goes ({@code <protectedDir>/accept})
     * @param check        static checks and the compile, on the whole draft; may rewrite the map's
     *                     contents when a check corrects a file mechanically
     */
    TestAuthorTools(ExpertTools session, String protectedDir, String writeDir,
                    Function<Map<String, String>, Verdict> check) {
        this.session = session;
        this.protectedDir = protectedDir.replace('\\', '/');
        this.writeDir = writeDir.replace('\\', '/');
        this.check = check;
    }

    /**
     * This task changes a screen: the session is given {@code check_journey} and {@code texts_of}.
     *
     * @param objection path and content of a draft that is a well-formed journey, to what else
     *                  is wrong with it (it would replace an earlier story's journey); null when
     *                  nothing is
     */
    TestAuthorTools expectingAJourney(java.util.function.BiFunction<String, String, String> objection) {
        this.journeyObjection = objection == null ? (path, content) -> null : objection;
        return this;
    }

    boolean journeyDue() {
        return journeyObjection != null;
    }

    /**
     * A journey is also held to this (section 69): a text it expects to see is typed by one of
     * its steps or held by the project as it stands, or its author is asked once whether the
     * new screen shows it by itself.
     *
     * @param held whether the project's shipped code holds a text; null to ask nothing
     */
    TestAuthorTools knowingTheProjectsTexts(java.util.function.Predicate<String> held) {
        this.heldByTheProject = held;
        return this;
    }

    /**
     * The session now reviews ONE journey that failed in the browser (section 69): only a draft
     * at {@code path} is taken, nothing kept from authoring is handed in again, and no test is
     * compiled. What the review came to is read from {@link #journeys()} and {@link #handedIn()}.
     */
    TestAuthorTools reviewingAJourney(String path) {
        String only = path.replace((char) 92, '/');
        this.journeyObjection = (given, content) -> only.equals(given) ? null
            : "This review is about `" + only + "` and no other file. Give the corrected "
                + "journey at exactly that path.";
        this.journeyOnly = true;
        this.journeyWaivable = false;
        this.journeyWaiver = null;
        this.journeyWaiverPath = null;
        this.journeys.clear();
        this.journeyChecks = 0;
        this.askedAboutUnentered = null;
        this.check = files -> new Verdict(false, "No test is compiled in this review: it is "
            + "about the journey only. Use check_journey, then report_done.");
        this.draft.clear();
        this.lastHealthy = null;
        this.lastWasHealthy = false;
        this.wrote = "";
        this.handedIn = false;
        return this;
    }

    /**
     * The graph shows no browser code using what this task writes, so its author may answer
     * {@code noVisibleEffect: <why>} in place of a journey (section 64).
     */
    TestAuthorTools journeyMayBeWaived(boolean waivable) {
        this.journeyWaivable = waivable;
        return this;
    }

    /** The answer given in place of a journey, or null. */
    String journeyWaiver() {
        return journeyWaiver;
    }

    /** The path the answer was given under; with {@link #journeyWaiver()} it is handed in. */
    String journeyWaiverPath() {
        return journeyWaiverPath;
    }

    /** The journeys to hand in: path to content, as {@code check_journey} last called them valid. */
    Map<String, String> journeys() {
        return new LinkedHashMap<>(journeys);
    }

    /** The session goes on after a hand-in without a journey; the test draft stays as it is. */
    void handInAgain() {
        handedIn = false;
    }

    /** Parameter names are the tool's schema (the build compiles with {@code -parameters}). */
    public String checkJourney(String path, String content) {
        return session.runOwnTool("check_journey", path, () -> {
            String target = accepted(path, JourneyFile.SUFFIX);
            if (target == null) {
                return "Refused: `" + path + "` is not a journey file under " + protectedDir
                    + ". Give it as " + writeDir + "/<name>" + JourneyFile.SUFFIX + ".";
            }
            if (journeyChecks >= MAX_COMPILES) {
                return "error: you have used all " + MAX_COMPILES + " journey checks, so this "
                    + "draft was not read and not kept. Call report_done now.";
            }
            journeyChecks++;
            String waiver = JourneyFile.waiverOf(content);
            if (waiver != null) {
                if (!journeyWaivable) {
                    log.info("check_journey {} for {}: 'no visible effect' not taken", journeyChecks,
                        target);
                    return "NOT TAKEN. What this task writes is used by code that runs in the "
                        + "browser, so a person can see what it changes and `"
                        + JourneyFile.NO_VISIBLE_EFFECT + "` is not an answer here. Write the "
                        + "journey and call check_journey again with the complete file.";
                }
                journeys.clear();
                journeyWaiver = waiver;
                journeyWaiverPath = target;
                log.info("check_journey {} for {}: recorded in place of a journey - {}",
                    journeyChecks, target, waiver);
                return "RECORDED in place of a journey, and handed in with your test by "
                    + "report_done: \"" + waiver + "\". It is kept with the story and shown to "
                    + "the project's owner. If a person CAN see or do something different, "
                    + "write the journey instead and call check_journey with it.";
            }
            JourneyFile.Read read = JourneyFile.read(target, content);
            String objection = read.ok() ? journeyObjection.apply(target, content) : read.objection();
            if (objection != null) {
                log.info("check_journey {} for {}: not valid - {}", journeyChecks, target,
                    objection.replaceAll("\\s*\\R\\s*", " | "));
                return "NOT A VALID JOURNEY - not kept.\n" + objection
                    + "\n\nCorrect it and call check_journey again with the complete file.";
            }
            // A text it expects that nobody enters (section 69, live run 93): asked once,
            // while the journey is being written. The same file given again is the author's
            // answer that the new screen shows the text by itself, and is kept.
            List<com.swarmcoder.verify.JourneyExpectations.Unentered> unentered =
                com.swarmcoder.verify.JourneyExpectations.unentered(read.journey(),
                    heldByTheProject);
            String given = content.replace("\r\n", "\n").strip();
            if (!unentered.isEmpty() && !given.equals(askedAboutUnentered)) {
                askedAboutUnentered = given;
                log.info("check_journey {} for {}: asked about {} expected text(s) no step "
                    + "enters - {}", journeyChecks, target, unentered.size(), unentered);
                return "NOT KEPT YET - one question first.\n"
                    + com.swarmcoder.verify.JourneyExpectations.question(unentered)
                    + "\n\nCorrect the journey and call check_journey with the complete file - "
                    + "or, when every text named above is one the new screen shows by itself, "
                    + "call check_journey again with this same file and it is kept.";
            }
            if (!unentered.isEmpty()) {
                log.info("check_journey {} for {}: kept on its author's word that the new "
                    + "screen itself shows {}", journeyChecks, target, unentered);
            }
            journeys.put(target, content);
            journeyWaiver = null;
            journeyWaiverPath = null;
            log.info("check_journey {} for {}: valid, {} step(s)", journeyChecks, target,
                read.journey().steps().size());
            return "VALID - kept, and handed in " + (journeyOnly ? "" : "with your test ")
                + "by report_done.\n"
                + read.journey().describe()
                + "This says the file is well formed. It does not say the journey is right: "
                + (journeyOnly
                    ? "it is now made in a real browser twice - on the application as it was "
                        + "before the story, where it must FAIL, and on the built one, where "
                        + "it must pass."
                    : "after hand-in it is made in a real browser on the application as it is "
                        + "now, where it must FAIL at a step this task's work will make "
                        + "possible. The application is started with no data of its own unless "
                        + "the project's contract says otherwise: what the journey expects to "
                        + "see, one of its steps types or the screen shows by itself.");
        });
    }

    /** Parameter names are the tool's schema (the build compiles with {@code -parameters}). */
    public String compileTest(String path, String content) {
        return session.runOwnTool("compile_test", path, () -> {
            String target = accepted(path);
            if (target == null) {
                return "Refused: `" + path + "` is not under " + protectedDir + ". You may write "
                    + "test source files only, as " + writeDir + "/<ClassName>.java.";
            }
            if (content == null || content.isBlank()) {
                return "error: no source was given. Call compile_test with the complete Java "
                    + "source of the test class.";
            }
            if (compiles >= MAX_COMPILES) {
                return "error: you have used all " + MAX_COMPILES + " compiles, so this draft was "
                    + "not compiled and not kept. Call report_done now; it hands in the draft you "
                    + "last compiled.";
            }
            compiles++;
            draft.put(target, content);
            Verdict verdict = check.apply(draft);
            lastWasHealthy = verdict.healthy();
            // What the check said, on the run's log (run 86: four compiles, and the log held
            // only how long each answer was, so what the one broken draft lacked is not known).
            String said = verdict.text() == null ? "" : verdict.text().strip();
            log.info("compile_test {} of this round for {}: {} - {}", compiles, target,
                verdict.healthy() ? "healthy" : "BROKEN",
                (said.length() <= 600 ? said : said.substring(0, 600) + " ...")
                    .replaceAll("\\s*\\R\\s*", " | "));
            if (verdict.healthy()) {
                lastHealthy = new LinkedHashMap<>(draft);
            }
            return verdict.text() + (compiles < MAX_COMPILES ? "" : "\n\n(That was your last "
                + "compile: call report_done now.)");
        });
    }

    /**
     * The same session goes on after what it handed in was sent back (section 54): the next
     * hand-in is judged on its own, with the checks of the new call and a fresh allowance of
     * compiles. The files of the rejected draft are forgotten here; the conversation holds them.
     */
    void nextRound(Function<Map<String, String>, Verdict> check) {
        this.check = check;
        journeyOnly = false;
        draft.clear();
        lastHealthy = null;
        lastWasHealthy = false;
        compiles = 0;
        wrote = "";
        handedIn = false;
    }

    /** Ends the session. The parameter name is the tool's schema - do not rename it. */
    public String reportDone(String wrote) {
        this.wrote = wrote == null ? "" : wrote;
        this.handedIn = true;
        if (journeyOnly) {
            return journeys.isEmpty() ? "recorded: the journey stands as you wrote it"
                : "the corrected journey is handed in";
        }
        return draft.isEmpty() ? "nothing was compiled, so nothing is handed in" : "handed in";
    }

    List<ToolBinding> bindings() {
        try {
            List<ToolBinding> all = journeyOnly ? new ArrayList<>(List.of(
                new ToolBinding(LookupAgent.SUBMIT_TOOL,
                    "End the review. Give two or three sentences: what was wrong with the "
                        + "journey when you corrected it (the journey check_journey last called "
                        + "VALID is handed in), or what the screen got wrong when the journey "
                        + "is right (give check_journey nothing then).",
                    this, TestAuthorTools.class.getMethod("reportDone", String.class))))
                : new ArrayList<>(List.of(
                new ToolBinding("compile_test",
                    "Compile a draft of your test exactly as the build's own check will, with the "
                        + "types the plan has not written yet stubbed in. Give the file path ("
                        + writeDir + "/<ClassName>.java) and the COMPLETE Java source. It answers "
                        + "HEALTHY (the test fails only because the planned code is not written "
                        + "yet) or BROKEN TEST with the compiler's own lines. Compile again "
                        + "after every correction. A test file the project already has is never "
                        + "shortened: a draft at its path must keep every existing test method "
                        + "unchanged (add yours), or use a new class name.",
                    this, TestAuthorTools.class.getMethod("compileTest", String.class, String.class)),
                new ToolBinding(LookupAgent.SUBMIT_TOOL,
                    "Hand in the test file(s) exactly as you last gave them to compile_test, and "
                        + "end the session. Earlier tests of the project stay as they are. "
                        + "Give one line per criterion: <the criterion> => "
                        + "<package>.<Class>#<method>.",
                    this, TestAuthorTools.class.getMethod("reportDone", String.class))));
            if (journeyDue()) {
                all.add(0, new ToolBinding("check_journey",
                    "Check a draft journey - what a person does in a browser to use what this "
                        + "task delivers - with no model. Give the file path (" + writeDir
                        + "/<name>" + JourneyFile.SUFFIX + ") and the COMPLETE YAML. It answers "
                        + "VALID and keeps the journey, or says what is wrong with it. A valid "
                        + "journey is handed in with your test by report_done. An earlier "
                        + "story's journey is never changed: give yours a name of its own."
                        + (journeyWaivable ? " When no person using the application in a "
                            + "browser sees or can do anything different, give the single line `"
                            + JourneyFile.NO_VISIBLE_EFFECT + ": <why>` as the content instead; "
                            + "it is recorded in place of a journey." : ""),
                    this, TestAuthorTools.class.getMethod("checkJourney", String.class,
                        String.class)));
                all.add(0, new ToolBinding("texts_of",
                    "The texts a type holds - every string literal in it, by member, with its "
                        + "annotations' arguments - and no code: what a screen shows, what a "
                        + "route is called, what a class of text constants holds. Give a type "
                        + "name. Use it to learn the labels and links the application ALREADY "
                        + "shows on the way to your screen, instead of reading the screen's code.",
                    session, ExpertTools.class.getMethod("textsOf", String.class)));
            }
            return List.copyOf(all);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True once the author has given at least one draft to {@code compile_test}. */
    boolean hasDraft() {
        return !draft.isEmpty();
    }

    boolean handedIn() {
        return handedIn;
    }

    int compiles() {
        return compiles;
    }

    /** What the author gave {@code report_done}, as it gave it; "" until it has. */
    String wrote() {
        return wrote;
    }

    /**
     * What is handed in: the last draft when the check called it healthy; otherwise the last
     * draft it did call healthy, when there was one; otherwise the last draft as it stands, for
     * the checks after hand-in to judge.
     */
    Map<String, String> submission() {
        if (lastWasHealthy || lastHealthy == null) {
            return new LinkedHashMap<>(draft);
        }
        return new LinkedHashMap<>(lastHealthy);
    }

    /** The author's "criterion => test" lines, as {@code [criterion, test]} pairs. */
    List<String[]> claims() {
        List<String[]> claims = new ArrayList<>();
        for (String line : wrote.split("\\R")) {
            int arrow = line.lastIndexOf("=>");
            if (arrow <= 0) {
                continue;
            }
            String test = line.substring(arrow + 2).strip();
            if (test.contains("#")) {
                claims.add(new String[] {line.substring(0, arrow).strip(), test});
            }
        }
        return claims;
    }

    /** The path as it will be written, or null when it is not under the protected directory. */
    private String accepted(String path) {
        return accepted(path, ".java");
    }

    private String accepted(String path, String suffix) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String cleaned = path.replace('\\', '/').strip();
        // The lookups address a file of the project as project/<path> when reference material
        // is loaded beside it, and the author gives its draft the same address (run 86: such a
        // draft was refused as "not under" the directory it was under).
        if (cleaned.startsWith("project/") && !protectedDir.startsWith("project/")) {
            cleaned = cleaned.substring("project/".length());
        }
        if (!cleaned.contains("/")) {
            cleaned = writeDir + "/" + cleaned; // a bare file name: the one place it can go
        }
        Path normalized;
        try {
            normalized = Path.of(cleaned).normalize();
        } catch (RuntimeException e) {
            return null;
        }
        if (normalized.isAbsolute() || !normalized.startsWith(Path.of(protectedDir).normalize())
                || !cleaned.endsWith(suffix)) {
            return null;
        }
        return normalized.toString().replace('\\', '/');
    }
}
