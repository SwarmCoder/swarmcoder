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
import com.swarmcoder.domain.AuthoredTest;
import com.swarmcoder.domain.AuthoredTests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks, at TEST_AUTHORING time, that every check the run answers for now points at a test that
 * actually exists — and says which test answers which check.
 *
 * <p><b>Why here and not at the end.</b> A criterion names the test that proves it, and that name is
 * the address the whole requirement-to-commit trace is looked up by. Since §15.1 a wrong name yields
 * UNKNOWN rather than a silent pass — but only after a full run: design, plan, N workers in
 * worktrees, verification, judging, merge, and then a story that goes BLOCKED with every criterion
 * unproven. The same mistake is obvious here, before a single worker starts, and costs nothing.
 *
 * <p><b>Why it does not simply write the reference back.</b> Adopting whatever the author happened
 * to produce would make the requirement point at a test chosen by the thing being checked. Any test
 * at all would then "prove" the criterion, and the mismatch — which is real information about a
 * disagreement between what was agreed and what was built — would vanish along with the error. A
 * self-satisfying requirement is worse than a hand-typed string, because nothing can ever detect it
 * again. The run parks and a person decides which side was wrong.
 *
 * <p><b>It only accuses on positive evidence.</b> The test ids come from reading the files the
 * author wrote. A file that cannot be read, or that yields no ids at all (a language this does not
 * parse), produces a note and no finding: "we could not tell" must never become "this is wrong",
 * which is the same rule §15.1 established in the other direction.
 */
public final class AuthoredTestAudit {

    private static final Logger log = LoggerFactory.getLogger(AuthoredTestAudit.class);

    private AuthoredTestAudit() {}

    /** A check whose named test is not among the tests just written. */
    public record Finding(String criterionRef, String criterionText, String testRef, String problem) {

        /** One line, in the words a person needs to fix it. */
        public String render() {
            return criterionRef + " " + criterionText + "\n      " + problem;
        }
    }

    /** One test the author actually wrote, read back out of the file it wrote it in. */
    public record Written(String testRef, String path) {}

    /**
     * @param mapping  one line per check that IS answered: the check and the test found for it
     * @param findings checks that are not answered by anything the author wrote — these park the run
     * @param notes    everything worth saying that is not an accusation: the author's own claimed
     *                 mapping, files that could not be read, claims that disagree with the files
     * @param written  the tests found in the files, one per test method, in file order - the same
     *                 facts the {@code mapping} lines are made from, kept as data so the run graph
     *                 can show them rather than re-parse a sentence
     */
    public record Result(List<String> mapping, List<Finding> findings, List<String> notes,
                         List<Written> written) {

        public boolean ok() {
            return findings.isEmpty();
        }
    }

    /**
     * Audits one task's authored tests against the checks it answers for.
     *
     * @param criteria the checks handed to the test author, in order
     * @param refs     their human refs (R7:C1) parallel to {@code criteria}; may be shorter or empty
     * @param repoRoot the repository the files were written into
     * @param authored what the author wrote, and what it says it wrote
     */
    public static Result audit(List<AcceptanceCriterion> criteria, List<String> refs,
                               Path repoRoot, TestAuthorClient.Authored authored) {
        List<String> mapping = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (criteria == null || criteria.isEmpty() || authored == null || authored.isEmpty()) {
            // Nothing was written, so there is nothing to compare. That case is the test author's
            // own to report; inventing findings from it would park every repository that has no
            // protected acceptance directory configured yet.
            return new Result(List.of(), List.of(), List.of(), List.of());
        }

        Set<String> writtenIds = new LinkedHashSet<>();
        List<Written> written = new ArrayList<>();
        boolean anyUnreadable = false;
        for (String path : authored.paths()) {
            String source = sourceOf(repoRoot, path);
            List<String> ids = idsIn(source);
            if (ids.isEmpty()) {
                anyUnreadable = true;
                notes.add("no test ids could be read out of " + path
                    + ", so nothing is concluded from it");
                continue;
            }
            writtenIds.addAll(ids);
            // One entry per test METHOD for the graph, where the generous double attribution
            // below would count every test in a nested class twice.
            for (String id : declaredIn(source)) {
                written.add(new Written(id, path));
            }
        }
        notes.add("tests actually written: " + String.join(", ", writtenIds));

        // The author's own account, kept beside the facts. It is a report, not evidence: a claim
        // that names a test not in the files is itself worth seeing, and is a note, never a finding.
        for (TestAuthorClient.Claim claim : authored.claims()) {
            boolean real = CriterionEvidence.namesOneOf(claim.testRef(), writtenIds);
            notes.add("the test author says it wrote " + claim.testRef()
                + " for \"" + claim.criterion() + "\""
                + (real ? "" : " — but no such test is in the files it wrote"));
        }

        for (int i = 0; i < criteria.size(); i++) {
            AcceptanceCriterion criterion = criteria.get(i);
            String ref = i < refs.size() ? refs.get(i) : "(unreferenced check)";
            String testRef = criterion.testClassOrFile();
            if (testRef == null || testRef.isBlank()) {
                findings.add(new Finding(ref, criterion.text(), null,
                    "This check names no test at all, so nothing can ever prove it. Open the "
                        + "requirement in the Requirements editor and name the test that must "
                        + "pass for it."));
                continue;
            }
            if (CriterionEvidence.namesOneOf(testRef, writtenIds)) {
                mapping.add(ref + " is proved by " + testRef);
                continue;
            }
            if (anyUnreadable) {
                notes.add(ref + " names " + testRef + " and nothing written matches it, but at "
                    + "least one authored file could not be read — so this is not being treated "
                    + "as a mismatch");
                continue;
            }
            findings.add(new Finding(ref, criterion.text(), testRef,
                "This check says it is proved by " + testRef + ", and no test of that name was "
                    + "written. What was written: " + String.join(", ", writtenIds)));
        }
        return new Result(List.copyOf(mapping), List.copyOf(findings), List.copyOf(notes),
            List.copyOf(written));
    }

    /**
     * The record the run graph shows for a task whose tests have just been written.
     *
     * <p>Built from the same facts the audit concluded from — the tests read back out of the files,
     * and which check names each of them — so what the badge says and what the audit logged cannot
     * disagree. A test that no check names is still listed, with its "proves" blank: the author
     * wrote it, so it is counted, and the panel says nothing asked for it.
     *
     * @param criteria  the checks the author was handed, in order
     * @param refs      their handles (R7:C1), parallel to {@code criteria}; may be shorter
     * @param result    what the audit concluded
     * @param authored  what the author wrote
     * @param startedAt when the author was handed this task; carried over from the started record
     * @param at        now
     */
    public static AuthoredTests written(List<AcceptanceCriterion> criteria, List<String> refs,
                                        Result result, TestAuthorClient.Authored authored,
                                        Instant startedAt, Instant at) {
        AuthoredTests record = new AuthoredTests();
        record.setStartedAt(startedAt == null ? at : startedAt);
        record.setWrittenAt(at);
        record.setChecksOffered(criteria == null ? 0 : criteria.size());
        record.setFiles(new ArrayList<>(authored == null ? List.of() : authored.paths()));
        List<AuthoredTest> tests = new ArrayList<>();
        for (Written written : result.written()) {
            String provesRef = "";
            String provesText = "";
            for (int i = 0; criteria != null && i < criteria.size(); i++) {
                AcceptanceCriterion criterion = criteria.get(i);
                if (CriterionEvidence.namesOneOf(criterion.testClassOrFile(),
                        List.of(written.testRef()))) {
                    provesRef = i < refs.size() ? refs.get(i) : "(a check this task owns)";
                    provesText = criterion.text() == null ? "" : criterion.text();
                    break;
                }
            }
            tests.add(new AuthoredTest(written.testRef(), written.path(), provesRef, provesText));
        }
        record.setTests(tests);
        List<String> problems = new ArrayList<>();
        for (Finding finding : result.findings()) {
            problems.add(finding.render().replace('\n', ' ').replaceAll("\\s+", " ").trim());
        }
        record.setProblems(problems);
        // What the author's own call said went wrong, if it did - carried here so a task's own
        // red-check park message can quote it instead of the generic "nothing recorded" text.
        // See TestAuthorClient.Authored.failureReason.
        record.setFailureReason(authored == null ? null : authored.failureReason());
        return record;
    }

    /** The whole audit as the brief a parked run shows the operator. */
    public static String brief(String taskTitle, Result result) {
        StringBuilder sb = new StringBuilder("The tests just written do not match what task '")
            .append(taskTitle).append("' promises to prove.\n");
        for (Finding finding : result.findings()) {
            sb.append("\n  - ").append(finding.render()).append('\n');
        }
        sb.append("\nEvery requirement-check names the test that proves it, and that name is how "
            + "the finished commit is traced back to the requirement. A check pointing at a test "
            + "nobody wrote can never be proved: the run would finish, the tests would be green, "
            + "and the requirement would still be unverified.\n\n"
            + "This is not fixed automatically on purpose. Pointing the check at whatever was "
            + "written would make the requirement agree with the code by definition, and the "
            + "disagreement — which is real information — would disappear with it.\n\n"
            + "Decide which side is wrong: correct the check's test name in the Requirements "
            + "editor, or fix the test so it is called what the check says. Then resume the run.");
        if (!result.notes().isEmpty()) {
            sb.append("\n\nWhat the test-authoring step reported:");
            result.notes().forEach(note -> sb.append("\n  - ").append(note));
        }
        return sb.toString();
    }

    // --- reading the tests that were actually written -------------------------------------------

    private static final Pattern TYPE = Pattern.compile(
        "\\b(?:class|interface|record|enum)\\s+([A-Z][A-Za-z0-9_]*)");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;",
        Pattern.MULTILINE);
    /** The annotation may be written qualified ({@code @org.junit.jupiter.api.Test}) or not. */
    private static final Pattern TEST_METHOD = Pattern.compile(
        "@(?:[A-Za-z0-9_.]+\\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b"
            + "[\\s\\S]{0,600}?\\b([a-z][A-Za-z0-9_]*)\\s*\\(");

    /**
     * Every id a written test file plausibly declares, as {@code package.Type#method}.
     *
     * <p>Deliberately generous. Each test method is attributed both to the type declared nearest
     * above it — which is right for ordinary Java layout, nested classes included — and to the
     * file's first type, in case a method follows a nested class but belongs to the outer one.
     * Over-generating can only make the audit accuse LESS, which is the safe direction: this
     * decides whether to stop a run, and it must never stop one on a parsing guess.
     *
     * <p>Empty means "this file told us nothing" — unreadable, or not a language we parse — and the
     * caller treats that as inconclusive rather than as an absence.
     */
    static List<String> idsIn(Path repoRoot, String relativePath) {
        return idsIn(sourceOf(repoRoot, relativePath));
    }

    /** The file's text, or "" when it cannot be read — which the callers treat as "told us nothing". */
    private static String sourceOf(Path repoRoot, String relativePath) {
        try {
            return Files.readString(repoRoot.resolve(relativePath.replace('\\', '/')));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read authored test file {}: {}", relativePath, e.toString());
            return "";
        }
    }

    /** The ids declared by one Java source, as {@code package.Type#method}. */
    static List<String> idsIn(String source) {
        return idsIn(source, true);
    }

    /**
     * The same ids, one per test method: each attributed only to the type declared nearest above
     * it. This is the list a person is shown; {@link #idsIn(String)} is the list the audit
     * decides by, and the two differ only for a method inside a nested class.
     */
    static List<String> declaredIn(String source) {
        return idsIn(source, false);
    }

    private static List<String> idsIn(String source, boolean generous) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        // Comments and string literals out first: "the interface LogbookService" in a javadoc is
        // not a type this file declares, and read as one it became the class every test was
        // reported under, so a check naming the real class found "no test of that name".
        source = SelfImplementedContract.strip(source);
        Matcher pkg = PACKAGE.matcher(source);
        String prefix = pkg.find() ? pkg.group(1) + "." : "";

        List<int[]> typeAt = new ArrayList<>();   // {offset, index into typeNames}
        List<String> typeNames = new ArrayList<>();
        Matcher types = TYPE.matcher(source);
        while (types.find()) {
            typeAt.add(new int[] {types.start(), typeNames.size()});
            typeNames.add(types.group(1));
        }
        if (typeNames.isEmpty()) {
            return List.of();
        }

        Set<String> ids = new LinkedHashSet<>();
        Matcher tests = TEST_METHOD.matcher(source);
        while (tests.find()) {
            String method = tests.group(1);
            String nearest = typeNames.get(0);
            for (int[] at : typeAt) {
                if (at[0] < tests.start()) {
                    nearest = typeNames.get(at[1]);
                }
            }
            ids.add(prefix + nearest + "#" + method);
            if (generous) {
                ids.add(prefix + typeNames.get(0) + "#" + method);
            }
        }
        return List.copyOf(ids);
    }
}
