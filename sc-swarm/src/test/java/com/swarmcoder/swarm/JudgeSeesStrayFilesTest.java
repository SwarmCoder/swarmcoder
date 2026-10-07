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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ClusterId;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.inference.VllmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defect from harness run 11 (2026-09-03): a worker wrote a helper script,
 * {@code insert_dep.py}, at the repository root to edit a pom, left it in the diff, and the
 * judge scored the candidate 1.00, calling it "minimal, clean" — never told the file was there,
 * and nothing capped the number even though the file was never asked for. This class proves the
 * fix has both halves: the judge is TOLD (the brief names the file, before the diff), and the
 * cap is ENFORCED in code, so a judge that ignores the instruction — or an operator's own local
 * model that scores generously regardless — cannot hand out full marks anyway.
 */
class JudgeSeesStrayFilesTest {

    /** Verbatim from harness run 11: the reply that scored the stray-file candidate 1.00. */
    private static final String FLAT_TOP_MARK =
        "{\"score\": 1.0, \"rationale\": \"The candidate correctly adds the required dependency. "
        + "The change is minimal, clean.\"}";

    private static Task task() {
        Task t = new Task();
        t.setId(UUID.randomUUID());
        t.setTitle("Add the eclipsestore dependency");
        t.setInstructions("Add the zerozstack-store-eclipsestore dependency to the pom.");
        return t;
    }

    private static VerificationReport compiling() {
        return new VerificationReport(UUID.randomUUID(), true, true, null, null, null, null,
            Duration.ZERO, "", null);
    }

    private static CandidateSolution candidateWithOutOfWriteSet(List<String> outOfWriteSet) {
        CandidateSolution sol = new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 0,
            "swarm/w0", null, "--- a/pom.xml\n+++ b/pom.xml\n+<dependency/>\n", compiling(),
            new ClusterId("hash", 1), null, CandidateState.SURVIVED, null);
        sol.setOutOfWriteSetPaths(outOfWriteSet);
        return sol;
    }

    /** The defect itself: a stray helper script must not let a candidate score full marks. */
    @Test
    void aStrayScriptOutsideTheWriteSetCapsTheScoreAndIsNamedInTheBrief() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(FLAT_TOP_MARK))) {
            JudgeClient judge = new JudgeClient(new VllmClient(fake.baseUrl(), "", "fake-judge", true));

            CandidateSolution judged = judge.judge(
                candidateWithOutOfWriteSet(List.of("insert_dep.py")), task());

            assertThat(fake.requests).hasSize(1);
            String brief = fake.requests.get(0);
            assertThat(brief)
                .as("the judge must be told about the file BEFORE the diff, not just somewhere")
                .contains("FILES OUTSIDE THE TASK'S WRITE SET")
                .contains("insert_dep.py")
                .contains("cap the score at " + JudgeClient.STRAY_FILE_CEILING);
            assertThat(brief.indexOf("insert_dep.py"))
                .as("the write-set warning must come before the diff, not after it")
                .isLessThan(brief.indexOf("Candidate diff:"));
            assertThat(judged.judge().score())
                .as("the judge said 1.00; the enforced ceiling must still hold")
                .isLessThanOrEqualTo(JudgeClient.STRAY_FILE_CEILING)
                .isEqualTo(1.0 * JudgeClient.STRAY_FILE_CEILING);
            assertThat(judged.judge().rationale())
                .contains("SCORE LIMITED")
                .contains("insert_dep.py")
                .contains("which the task did not ask for");
        }
    }

    /**
     * A neighbouring source file under a compiled root is NOT a stray file — the case
     * "warn, don't kill" was made for (a multi-module task that genuinely needs a sibling class).
     * No ceiling applies, and the judge's own score is kept.
     */
    @Test
    void aNeighbouringSourceFileIsNotStrayAndKeepsTheJudgesOwnScore() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(FLAT_TOP_MARK))) {
            JudgeClient judge = new JudgeClient(new VllmClient(fake.baseUrl(), "", "fake-judge", true));

            CandidateSolution judged = judge.judge(
                candidateWithOutOfWriteSet(List.of("sc-domain/src/main/java/com/swarmcoder/domain/Helper.java")),
                task());

            String brief = fake.requests.get(0);
            assertThat(brief)
                .contains("FILES OUTSIDE THE TASK'S WRITE SET")
                .contains("Helper.java");
            assertThat(judged.judge().score())
                .as("a genuinely-needed neighbouring source file must not be capped")
                .isEqualTo(1.0);
            assertThat(judged.judge().rationale()).doesNotContain("SCORE LIMITED");
        }
    }

    /** A candidate with nothing outside its write set is judged exactly as before. */
    @Test
    void aCandidateWithNoOutOfWriteSetFilesGetsNoWriteSetLine() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(FLAT_TOP_MARK))) {
            JudgeClient judge = new JudgeClient(new VllmClient(fake.baseUrl(), "", "fake-judge", true));

            CandidateSolution judged = judge.judge(candidateWithOutOfWriteSet(List.of()), task());

            assertThat(fake.requests.get(0)).doesNotContain("FILES OUTSIDE THE TASK'S WRITE SET");
            assertThat(judged.judge().score()).isEqualTo(1.0);
        }
    }
}
