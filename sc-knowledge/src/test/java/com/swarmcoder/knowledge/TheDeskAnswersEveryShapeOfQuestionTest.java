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

import com.swarmcoder.testsupport.LocalCheckouts;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.runtime.ExpertHelp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Twelve shapes of question a stuck worker actually asks, against the real reference material.
 *
 * <p><b>Why a corpus and not a case.</b> The first version of this desk was fixed twice, and both
 * times the fix was shaped to the one question that had exposed it — a type-name search, then a
 * documentation search bolted on beside it. That is a chain of special cases pretending to be a
 * design, and the question that breaks it is always the next one. A worker with no brief then
 * asked "how do I persist a root object with EclipseStore through zerozstack-store-eclipsestore in
 * a ZeroZ Stack server module" — an artifact, a concept and a type in one sentence — and got
 * nothing at all.
 *
 * <p>So the desk stopped classifying questions. Every free source offers what it has and they are
 * ranked by one number: how much of the question's own vocabulary the answer contains. These
 * twelve exist to hold that honest. They are not twelve rules; they are twelve samples of a space,
 * and a thirteenth written after the implementation was finished
 * ({@link #aQuestionShapeNobodyDesignedFor()}) stands in for the ones nobody thought of.
 *
 * <p>Skipped, loudly, when {@code C:/work/zeroz4j} is not on this machine — the point is the real
 * corpus, and a synthetic one proves something different (see
 * {@link TheNearestExampleOnAnUnrelatedFrameworkTest} and the three shapes at the end of this file).
 *
 * <p>No model is called: the expert is a fake that records what it was asked.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheDeskAnswersEveryShapeOfQuestionTest {

    private static final Path REFERENCE = LocalCheckouts.find("zeroz4j");
    private static final Path PROJECT = LocalCheckouts.find("dev/bookshelf-demo", "swarmcoder/dev/bookshelf-demo");

    /** question -> which tier answered -> the first line of what came back. */
    private static final List<String> LEDGER = new ArrayList<>();

    private KnowledgeCurator curator;
    private ExpertDesk desk;
    private AtomicInteger escalations;

    @BeforeAll
    void openTheDesk() {
        assumeThat(Files.isDirectory(REFERENCE))
            .as("the real reference material is not on this machine, so this corpus is skipped")
            .isTrue();
        assumeThat(Files.isDirectory(PROJECT)).isTrue();

        curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", PROJECT, "local"),
                new KnowledgeCurator.Root("zeroz4j", REFERENCE, "local")),
            null, Paths.get(System.getProperty("java.io.tmpdir"), "desk-corpus-cache"));
        escalations = new AtomicInteger();
        desk = new ExpertDesk(curator, PROJECT, contracts(),
            (question, context) -> {
                escalations.incrementAndGet();
                // A fake, always. The production escalation is a paid endpoint.
                return "```java\n// answered by the expert\n```";
            });
    }

    @AfterAll
    void printTheLedger() {
        if (LEDGER.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println("=== which tier answered which shape of question ===");
        System.out.println(String.format("%-58s %-14s %-14s %s",
            "question", "source", "free tier", "first line of the answer"));
        LEDGER.forEach(System.out::println);
        System.out.println("=== end ===");
    }

    // -- the twelve ---------------------------------------------------------------------------

    @Test
    void aMavenArtifactByName() {
        assertThat(answer("what is zerozstack-store-eclipsestore and how do I declare it"))
            .contains("zerozstack-store-eclipsestore")
            .contains("<dependency>")
            .as("and not a version, which the parent manages")
            .doesNotContain("<version>0");
    }

    @Test
    void aConceptWithNoTypeNameInIt() {
        assertThat(answer("how do I persist an object graph in this stack"))
            .containsAnyOf("EclipseStore", "ZeroZDbNode", "store", "persist");
    }

    @Test
    void aSecondConceptAboutADifferentPartOfTheStack() {
        assertThat(answer("how does the client call the server"))
            .containsAnyOf("RmiService", "Zeroz4jClient", "WebSocket", "RMI");
    }

    @Test
    void aTypeNameOnItsOwn() {
        assertThat(answer("Zeroz4jClient")).contains("Zeroz4jClient");
    }

    @Test
    void aTypeNameFromADependencyWhoseSourceIsNotInTheReferenceRoot() {
        assertThat(answer("ZeroZDbNode")).contains("ZeroZDbNode");
    }

    @Test
    void aMethodOnAType() {
        assertThat(answer("how do I get the root object from the database node"))
            .containsAnyOf("root()", "createDefaultRoot", "DataRoot", "localDb");
    }

    @Test
    void aCompileErrorPastedVerbatim() {
        assertThat(answer("cannot find symbol: class DataRootProvider"))
            .contains("DataRootProvider");
    }

    @Test
    void aBuildLayoutQuestion() {
        assertThat(answer("which module should the service implementation live in, "
            + "shared or server"))
            .containsAnyOf("server", "shared");
    }

    @Test
    void aWiringQuestion() {
        assertThat(answer("how is an RmiService implementation discovered by the server"))
            .containsAnyOf("RmiService", "ApplicationScoped", "beans.xml", "CDI");
    }

    @Test
    void aUserInterfaceQuestion() {
        assertThat(answer("how do I make a select box with three fixed options"))
            .containsAnyOf("Select", "select", "option");
    }

    @Test
    void aHalfRememberedName() {
        // "ZerozDbNode" and "embeded" are both wrong, which is how people type.
        assertThat(answer("ZerozDbNode embeded root")).isNotBlank();
    }

    @Test
    void somethingTheStackDeliberatelyDoesNotHave() {
        // The right answer is not silence: it is what this stack does instead.
        assertThat(answer("how do I add a REST controller"))
            .containsAnyOf("RMI", "RmiService", "REST", "WebSocket", "JSON");
    }

    @Test
    void aQuestionAimedAtTheWrongHalfOfTheStack() {
        assertThat(answer("how do I inject EclipseStore in the client")).isNotBlank();
    }

    @Test
    void aSkeletonByContractName() {
        ExpertHelp.Answer answer = desk.requestSkeleton("BookServiceImpl");
        record("skeleton: BookServiceImpl", answer, "contract skeleton");

        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.DETERMINISTIC);
        assertThat(answer.text())
            .contains("public class BookServiceImpl")
            .contains("UnsupportedOperationException");
    }

    // -- the held-out one, written after the implementation was finished -----------------------

    /**
     * A shape nobody designed for, written only once the desk was done.
     *
     * <p>It is not an artifact, a type, a method, a concept, a compile error or a build question.
     * It is a comparison — two mechanisms, asked about together, with a "when" rather than a "how".
     * Nothing in the implementation has a branch for it. It is answered because nothing in the
     * implementation has a branch for anything: every source offers what it has, and the one whose
     * text covers most of "livesync events signals difference" wins.
     */
    @Test
    void aQuestionShapeNobodyDesignedFor() {
        assertThat(answer("when should I use LiveSync instead of server events, "
            + "and what is the difference"))
            .containsAnyOf("LiveSync", "livesync", "event", "signal");
    }

    // -- the escalation contract ---------------------------------------------------------------

    @Test
    void nothingEverComesBackAsCannotAnswerWhileAnExpertExists() {
        List<String> everything = new ArrayList<>(List.of(
            "what is zerozstack-store-eclipsestore and how do I declare it",
            "how do I persist an object graph in this stack",
            "how does the client call the server", "Zeroz4jClient", "ZeroZDbNode",
            "how do I get the root object from the database node",
            "cannot find symbol: class DataRootProvider",
            "which module should the service implementation live in, shared or server",
            "how is an RmiService implementation discovered by the server",
            "how do I make a select box with three fixed options",
            "ZerozDbNode embeded root", "how do I add a REST controller",
            "how do I inject EclipseStore in the client",
            "when should I use LiveSync instead of server events, and what is the difference",
            "qqzzx wibble frobnicate the greeble"));

        for (String question : everything) {
            assertThat(desk.askExpert(question, null).source())
                .as("a worker told 'I cannot answer' has been given what it already had: '"
                    + question + "'")
                .isNotEqualTo(ExpertHelp.Source.NONE);
        }
    }

    @Test
    void aQuestionAboutNothingAtAllStillReachesTheExpert() {
        ExpertDesk fresh = freshDesk();
        AtomicInteger asked = new AtomicInteger();
        ExpertDesk withCounter = new ExpertDesk(curatorFor(), PROJECT, contracts(),
            (question, context) -> {
                asked.incrementAndGet();
                return "the expert's worked answer";
            });

        ExpertHelp.Answer answer = withCounter.askExpert("qqzzx wibble frobnicate", null);

        assertThat(asked.get()).as("nothing free could touch it, so the expert must run").isOne();
        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(fresh).isNotNull();
    }

    @Test
    void withNoExpertAtAllTheAnswerSaysItIsASetupProblem() {
        ExpertHelp.Answer answer = new ExpertDesk(curatorFor(), PROJECT, contracts())
            .askExpert("qqzzx wibble frobnicate", null);

        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.NONE);
        assertThat(answer.text())
            .as("'nobody is on the other end', not 'your question has no answer' — a worker told "
                + "the second stops asking")
            .contains("setup problem");
    }

    // -----------------------------------------------------------------------------------------

    /**
     * A FRESH desk per question, over the same (expensively built, shared) curator. Not
     * {@code desk} itself: these twelve are independent samples of a question SPACE, and sharing
     * one desk's history across them would make an unrelated earlier question's answer count as
     * a "repeat" of this one — which is a real desk behaviour (an escalated repeat is correct
     * when the SAME worker asks the SAME thing twice) but not what this method is testing.
     */
    private String answer(String question) {
        ExpertDesk isolated = new ExpertDesk(curator, PROJECT, contracts(),
            (question2, context) -> {
                escalations.incrementAndGet();
                return "```java\n// answered by the expert\n```";
            });
        ExpertHelp.Answer answer = isolated.askExpert(question, null);
        record(question, answer, isolated.lastTier());
        assertThat(answer.source())
            .as("no question may come back unanswered: " + question)
            .isNotEqualTo(ExpertHelp.Source.NONE);
        return answer.text();
    }

    /**
     * @param tier WHICH free source won, which is the thing that was invisible before the
     *             structural index existed and the reason two earlier rounds of fixes were shaped
     *             to the wrong one. Reporting only; nothing asserts on it.
     */
    private static void record(String question, ExpertHelp.Answer answer, String tier) {
        String first = answer.text().lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        LEDGER.add(String.format("%-58s %-14s %-14s %s",
            question.length() > 56 ? question.substring(0, 55) + "…" : question,
            answer.source(), tier == null || tier.isBlank() ? "-" : tier,
            first.length() > 76 ? first.substring(0, 75) + "…" : first));
    }

    private ExpertDesk freshDesk() {
        return new ExpertDesk(curatorFor(), PROJECT, contracts());
    }

    private KnowledgeCurator curatorFor() {
        return new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", PROJECT, "local"),
                new KnowledgeCurator.Root("zeroz4j", REFERENCE, "local")),
            null, Paths.get(System.getProperty("java.io.tmpdir"), "desk-corpus-cache"));
    }

    private static List<ApiContract> contracts() {
        return List.of(
            new ApiContract(UUID.randomUUID(), "Book", "One book on the shelf.", "",
                "com.swarmcoder.demo.bookshelf.model.Book",
                List.of("long id", "String title", "String author")),
            new ApiContract(UUID.randomUUID(), "BookServiceImpl",
                "The server side of the book list.", "",
                "com.swarmcoder.demo.bookshelf.server.BookServiceImpl",
                List.of("List<Book> list()", "Book save(Book book)", "void delete(long id)")));
    }
}
