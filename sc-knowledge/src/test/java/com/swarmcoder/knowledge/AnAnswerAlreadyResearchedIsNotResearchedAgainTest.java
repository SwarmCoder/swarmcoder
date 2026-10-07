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

import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.ExpertHelp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an answer from the expert costs, and that one already researched is not researched again.
 *
 * <p>Harness run 77 (2026-10-03): 26 questions from the architect and the planner, 12 of them
 * researched, 6.6 million prompt tokens - 17 to 32 turns and 31 to 71 lookups each. The same
 * facts were researched several times because they were asked in new words, the answers reached
 * the asker cut off part-way, and a reused answer was handed back bare to a question it only
 * partly matched.
 */
class AnAnswerAlreadyResearchedIsNotResearchedAgainTest {

    @TempDir
    Path world;

    private Path app;
    private KnowledgeCurator curator;

    @BeforeEach
    void world() throws Exception {
        app = world.resolve("app");
        Path pkg = app.resolve("src/main/java/demo");
        Files.createDirectories(pkg);
        Files.writeString(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        Files.writeString(pkg.resolve("Wibble.java"), """
            package demo;

            public class Wibble {
                public String frobnicate(String whatsit) {
                    return "frobnicated " + whatsit;
                }
            }
            """);
        Files.writeString(pkg.resolve("WibbleCaller.java"), """
            package demo;

            public class WibbleCaller {
                public String callIt() {
                    String marker = "only-in-the-caller-body";
                    return new Wibble().frobnicate(marker);
                }
            }
            """);
        curator = new KnowledgeCurator(List.of(new KnowledgeCurator.Root("project", app, "local")),
            null, world.resolve("cache"));
    }

    @Test
    void aQuestionInNewWordsIsAnsweredFromTheStoredAnswerWhenTheLocalCheckSaysItAnswersIt() {
        AtomicInteger researched = new AtomicInteger();
        BiFunction<String, String, String> expert = (question, context) -> {
            researched.incrementAndGet();
            return "The zorblat tests live in module gamma, folder src/test/java/zz.\n\n"
                + "Read from: project/gamma/pom.xml";
        };
        List<List<StoredAnswerJudge.Candidate>> shown = new ArrayList<>();
        StoredAnswerJudge judge = (question, candidates) -> {
            shown.add(candidates);
            // The first stored answer, when the run holds one; never the project's own material.
            return !candidates.isEmpty() && !candidates.get(0).question().isBlank() ? 0 : -1;
        };
        RunAnswerCache cache = new RunAnswerCache();
        ExpertDesk desk = new ExpertDesk(curator, app, List.of(), expert)
            .sharedAcrossRun(cache, RunAnswerCache.ASKED_BY_A_ROLE).judgedBy(judge);

        ExpertHelp.Answer first = desk.askExpert("qqzzx where do the zorblat tests live", null);
        // Not one word in common with the first question: no count of shared words finds it.
        ExpertHelp.Answer second = desk.askExpert("vvkkj which folder holds the checks", null);

        assertThat(first.source()).isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(researched.get()).as("researched once, not twice").isEqualTo(1);
        assertThat(second.source()).isEqualTo(ExpertHelp.Source.DETERMINISTIC);
        assertThat(second.text())
            .as("the asker is told which question the answer was researched for")
            .contains("qqzzx where do the zorblat tests live")
            .as("and gets the answer with the files it was read from")
            .contains("module gamma")
            .contains("Read from: project/gamma/pom.xml");
        assertThat(second.reason()).contains("a local check");
        assertThat(shown.get(shown.size() - 1).get(0).question())
            .isEqualTo("qqzzx where do the zorblat tests live");
    }

    @Test
    void whenTheLocalCheckFindsNoneTheExpertIsAskedAndStartsFromWhatItAlreadyResearched() {
        List<String> contexts = new ArrayList<>();
        BiFunction<String, String, String> expert = (question, context) -> {
            contexts.add(context);
            return "answer " + contexts.size() + ": the zorblat tests live in module gamma";
        };
        // With a judge, shared words alone no longer hand a stored answer back.
        StoredAnswerJudge judge = (question, candidates) -> -1;
        RunAnswerCache cache = new RunAnswerCache();
        ExpertDesk desk = new ExpertDesk(curator, app, List.of(), expert)
            .sharedAcrossRun(cache, RunAnswerCache.ASKED_BY_A_ROLE).judgedBy(judge);

        desk.askExpert("qqzzx where do the zorblat tests live", null);
        ExpertHelp.Answer second = new ExpertDesk(curator, app, List.of(), expert)
            .sharedAcrossRun(cache, RunAnswerCache.ASKED_BY_A_ROLE).judgedBy(judge)
            .askExpert("qqzzx where do the zorblat tests live and which runner starts them", null);

        assertThat(second.source()).isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(0)).doesNotContain("Answers you already researched");
        assertThat(contexts.get(1))
            .contains("Answers you already researched in this run")
            .contains("Asked: qqzzx where do the zorblat tests live")
            .contains("answer 1: the zorblat tests live in module gamma");
    }

    @Test
    void theExpertIsToldHowLongItsAnswerMayBe() {
        List<String> contexts = new ArrayList<>();
        new ExpertDesk(curator, app, List.of(), (question, context) -> {
            contexts.add(context);
            return "an answer";
        }).askExpert("qqzzx where do the zorblat tests live", null);

        assertThat(contexts.get(0)).contains("Your answer may be at most 3400 characters");
    }

    @Test
    void withoutALocalCheckAStoredAnswerIsStillHandedBackUnderItsQuestion() {
        RunAnswerCache cache = new RunAnswerCache();
        List<String> tokens = ExpertDesk.questionTokens("qqzzx where do the zorblat tests live");
        cache.record(tokens, 3, new ExpertHelp.Answer("in module gamma", ExpertHelp.Source.MODEL, 10),
            "qqzzx where do the zorblat tests live");

        ExpertHelp.Answer reused = cache.answerFor(tokens).orElseThrow();

        assertThat(reused.text())
            .startsWith("The expert already researched this in this run, for the question: "
                + "\"qqzzx where do the zorblat tests live\"")
            .endsWith("in module gamma");
        assertThat(reused.reason()).isEqualTo("free: the expert answered this for worker 3");
    }

    @Test
    void theLocalChecksReplyIsReadForItsNumberAndNothingElse() {
        assertThat(LocalAnswerJudge.verdict("ANSWERED BY 2", 3)).isEqualTo(1);
        assertThat(LocalAnswerJudge.verdict("ANSWERED BY 0", 3)).isEqualTo(-1);
        assertThat(LocalAnswerJudge.verdict("ANSWERED BY 7", 3)).as("not a candidate").isEqualTo(-1);
        assertThat(LocalAnswerJudge.verdict("I think it is the second one", 3)).isEqualTo(-1);
        assertThat(LocalAnswerJudge.verdict("First I thought ANSWERED BY 1.\nANSWERED BY 0", 3))
            .as("the last verdict is the conclusion").isEqualTo(-1);
        assertThat(LocalAnswerJudge.verdict(null, 3)).isEqualTo(-1);

        String prompt = LocalAnswerJudge.prompt("where are the tests?", List.of(
            new StoredAnswerJudge.Candidate("where do tests live", "in gamma"),
            new StoredAnswerJudge.Candidate("", "class Wibble {}")));
        assertThat(prompt).contains("### Stored answer 1\nFirst written for the question: where do "
            + "tests live\nin gamma").contains("### Stored answer 2\nclass Wibble {}");
    }

    @Test
    void aSecondSearchOfOneSessionNamesAFileItAlreadyQuotedInsteadOfQuotingItAgain() {
        Set<String> quoted = ConcurrentHashMap.newKeySet();
        String first = ExpertSearch.search(curator, "Wibble frobnicate whatsit", 6_000, true, quoted);
        String second = ExpertSearch.search(curator, "frobnicate whatsit Wibble", 6_000, true, quoted);

        assertThat(first).contains("only-in-the-caller-body");
        assertThat(quoted).anyMatch(file -> file.endsWith("WibbleCaller.java"));
        assertThat(second)
            .as("the second search names the file and does not carry its text again")
            .contains("WibbleCaller.java")
            .doesNotContain("only-in-the-caller-body");
        assertThat(ExpertSearch.search(curator, "Wibble frobnicate whatsit", 6_000, true))
            .as("a search outside a session is what it always was").isEqualTo(first);
    }

    @Test
    void theExpertMakingTheSameLookupAThirdTimeIsToldSoInsteadOfBeingSentItAgain() {
        ExpertTools tools = new ExpertTools(curator, null, null, null, null, 12);

        String once = tools.search("Wibble frobnicate");
        tools.search("Wibble frobnicate");
        String third = tools.search("Wibble frobnicate");

        assertThat(once).doesNotContain("exactly this call");
        assertThat(third).contains("You have made exactly this call 2 times");
        assertThat(tools.lookups()).as("the refused call returned no material").hasSize(2);
    }

    @Test
    void aRolesOldLookupResultsAreTidiedAtAQuarterOfASmallRoomAndAsConfiguredInALargeOne() {
        AgentRuntime.SessionOptions small = LookupAgent.sessionOptions(null, 34_000);
        AgentRuntime.SessionOptions large = LookupAgent.sessionOptions(null, 262_144);
        AgentRuntime.SessionOptions unknown = LookupAgent.sessionOptions(null, 0);

        assertThat(small.tidyAboveTokens()).isEqualTo(8_500);
        assertThat(small.tidyToTokens()).as("section 51: half the threshold stays whole in a small room").isEqualTo(4_250);
        assertThat(large.tidyAboveTokens()).isEqualTo(LookupAgent.TIDY_ABOVE_TOKENS);
        assertThat(large.tidyToTokens()).isEqualTo(LookupAgent.TIDY_TO_TOKENS);
        assertThat(unknown).isEqualTo(LookupAgent.sessionOptions(null));
    }

    @Test
    void theExpertHasTwelveTurnsAndIsToldThatNotFindingSomethingIsAnAnswer() {
        assertThat(ExpertEscalation.MAX_TURNS).isEqualTo(12);
        assertThat(ExpertEscalation.SYSTEM)
            .contains("THAT SOMETHING IS NOT THERE IS AN ANSWER")
            .contains("path:LINE");
        assertThat(LookupAgent.toolGuide(List.of("search", "ask_expert")))
            .contains("never guess")
            .contains("when your own search finds nothing")
            .doesNotContain("no charge");
    }
}
