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

import com.swarmcoder.runtime.ExpertAnswerLog;
import com.swarmcoder.runtime.ExpertHelp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Questions to the expert run side by side, the same question is researched once, and every
 * answer is on the run's record with what became of the work that followed it.
 *
 * <p>Harness run 66 (2026-10-02). An answer took 4 to 13 minutes. Nothing in the desk serialises
 * two askers - each worker has its own desk and its own session - but the run's answer cache only
 * ever held FINISHED answers, so two workers stopping at the same unfamiliar API within those
 * minutes each paid for the same investigation. And nothing recorded whether any answer was right.
 */
class TwoAskersOfOneQuestionShareOneExpertAnswerTest {

    @TempDir
    Path world;

    private Path app;
    private KnowledgeCurator curator;

    @BeforeEach
    void world() throws Exception {
        app = world.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        curator = new KnowledgeCurator(List.of(new KnowledgeCurator.Root("project", app, "local")),
            null, world.resolve("cache"));
    }

    @Test
    void theSameQuestionAskedByTwoWorkersAtOnceIsResearchedOnceAndBothGetTheAnswer()
            throws Exception {
        AtomicInteger sessions = new AtomicInteger();
        CountDownLatch researching = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        BiFunction<String, String, String> expert = (question, context) -> {
            sessions.incrementAndGet();
            researching.countDown();
            await(finish);
            return "call frobnicate(whatsit) on the wibble";
        };
        RunAnswerCache cache = new RunAnswerCache();
        ExpertAnswerLog log = ExpertAnswerLog.forRun(UUID.randomUUID());
        ExpertAnswerLog.Asker first = ExpertAnswerLog.Asker.worker(UUID.randomUUID(), "Task", 0);
        ExpertAnswerLog.Asker second = ExpertAnswerLog.Asker.worker(UUID.randomUUID(), "Task", 1);
        ExpertDesk desk0 = new ExpertDesk(curator, app, List.of(), expert)
            .sharedAcrossRun(cache, 0).recordedIn(log, first);
        ExpertDesk desk1 = new ExpertDesk(curator, app, List.of(), expert)
            .sharedAcrossRun(cache, 1).recordedIn(log, second);

        CompletableFuture<ExpertHelp.Answer> answer0 = CompletableFuture.supplyAsync(
            () -> desk0.askExpert("qqzzx wibble frobnicate the whatsit", null));
        assertThat(researching.await(20, TimeUnit.SECONDS)).isTrue();
        Thread[] asker1 = new Thread[1];
        CompletableFuture<ExpertHelp.Answer> answer1 = new CompletableFuture<>();
        asker1[0] = new Thread(() -> answer1.complete(
            desk1.askExpert("how do I frobnicate the whatsit with qqzzx wibble", null)));
        asker1[0].start();
        // Worker 1 is now waiting for worker 0's answer; only then is worker 0's research ended.
        for (int i = 0; i < 400 && asker1[0].getState() != Thread.State.TIMED_WAITING; i++) {
            Thread.sleep(25);
        }
        finish.countDown();

        ExpertHelp.Answer given0 = answer0.get(20, TimeUnit.SECONDS);
        ExpertHelp.Answer given1 = answer1.get(20, TimeUnit.SECONDS);

        assertThat(sessions.get())
            .as("one investigation, not two: the second asker waited for the first's answer")
            .isEqualTo(1);
        assertThat(given0.source()).isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(given1.text()).isEqualTo(given0.text());
        assertThat(given1.source())
            .as("the shared answer cost the second asker nothing")
            .isEqualTo(ExpertHelp.Source.DETERMINISTIC);
        assertThat(given1.reason()).contains("worker 0");

        assertThat(log.entries()).hasSize(2);
        assertThat(log.entries()).extracting(ExpertAnswerLog.Entry::from)
            .containsExactlyInAnyOrder(ExpertAnswerLog.From.EXPERT_RESEARCH,
                ExpertAnswerLog.From.REUSED);
    }

    @Test
    void twoDifferentQuestionsAreResearchedAtTheSameTime() throws Exception {
        CountDownLatch bothIn = new CountDownLatch(2);
        AtomicInteger together = new AtomicInteger();
        BiFunction<String, String, String> expert = (question, context) -> {
            bothIn.countDown();
            try {
                if (bothIn.await(15, TimeUnit.SECONDS)) {
                    together.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "an answer to " + question;
        };
        RunAnswerCache cache = new RunAnswerCache();
        ExpertDesk desk0 = new ExpertDesk(curator, app, List.of(), expert).sharedAcrossRun(cache, 0);
        ExpertDesk desk1 = new ExpertDesk(curator, app, List.of(), expert).sharedAcrossRun(cache, 1);

        CompletableFuture<ExpertHelp.Answer> answer0 = CompletableFuture.supplyAsync(
            () -> desk0.askExpert("qqzzx wibble frobnicate the whatsit", null));
        CompletableFuture<ExpertHelp.Answer> answer1 = CompletableFuture.supplyAsync(
            () -> desk1.askExpert("plugh xyzzy grault the corge garply", null));

        assertThat(answer0.get(30, TimeUnit.SECONDS).source()).isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(answer1.get(30, TimeUnit.SECONDS).source()).isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(together.get())
            .as("each was being researched while the other was: neither queued behind the other")
            .isEqualTo(2);
    }

    @Test
    void anExpertThatReachesNoAnswerDoesNotLeaveTheSecondAskerWaitingOrWithoutItsOwnTry()
            throws Exception {
        AtomicInteger sessions = new AtomicInteger();
        CountDownLatch researching = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        BiFunction<String, String, String> expert = (question, context) -> {
            if (sessions.incrementAndGet() == 1) {
                researching.countDown();
                await(finish);
                return "";                       // the first investigation reached nothing
            }
            return "the second investigation's answer";
        };
        RunAnswerCache cache = new RunAnswerCache();
        ExpertDesk desk0 = new ExpertDesk(curator, app, List.of(), expert).sharedAcrossRun(cache, 0);
        ExpertDesk desk1 = new ExpertDesk(curator, app, List.of(), expert).sharedAcrossRun(cache, 1);

        CompletableFuture<ExpertHelp.Answer> answer0 = CompletableFuture.supplyAsync(
            () -> desk0.askExpert("qqzzx wibble frobnicate the whatsit", null));
        assertThat(researching.await(20, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ExpertHelp.Answer> answer1 = CompletableFuture.supplyAsync(
            () -> desk1.askExpert("qqzzx wibble frobnicate the whatsit", null));
        Thread.sleep(300);
        finish.countDown();

        assertThat(answer0.get(20, TimeUnit.SECONDS).answered()).isFalse();
        ExpertHelp.Answer given1 = answer1.get(20, TimeUnit.SECONDS);
        assertThat(given1.source())
            .as("a failed investigation is not shared: the second asker gets its own")
            .isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(given1.text()).contains("second investigation");
    }

    @Test
    void everyAnswerIsRecordedWithItsSourceAndWhatBecameOfTheWorkThatFollowedIt() {
        BiFunction<String, String, String> expert = (question, context) -> "use the wibble";
        ExpertAnswerLog log = ExpertAnswerLog.forRun(UUID.randomUUID());
        UUID task = UUID.randomUUID();
        ExpertAnswerLog.Asker worker = ExpertAnswerLog.Asker.worker(task, "Build it", 3);
        ExpertDesk desk = new ExpertDesk(curator, app, List.of(), expert)
            .sharedAcrossRun(new RunAnswerCache(), 3).recordedIn(log, worker);

        desk.askExpert("qqzzx wibble frobnicate the whatsit", "I tried a field");
        assertThat(log.entries()).hasSize(1);
        ExpertAnswerLog.Entry first = log.entries().get(0);
        assertThat(first.asker().words()).isEqualTo("worker 3 of 'Build it'");
        assertThat(first.question()).isEqualTo("qqzzx wibble frobnicate the whatsit");
        assertThat(first.from()).isEqualTo(ExpertAnswerLog.From.EXPERT_RESEARCH);
        assertThat(first.outcome())
            .as("nothing is claimed about an answer until the work that followed it was checked")
            .isEqualTo("unknown");

        // The same worker asks the same thing again: the plainest sign the answer did not help.
        desk.askExpert("how do I frobnicate the whatsit, qqzzx wibble?", null);
        assertThat(log.entries().get(0).askedAgain()).isTrue();
        assertThat(log.entries().get(0).outcome()).isEqualTo("did not help");
        assertThat(log.entries().get(1).from())
            .as("and it was served the run's remembered answer")
            .isEqualTo(ExpertAnswerLog.From.REUSED);

        // Its candidate then passes verification.
        log.workFollowed(ExpertAnswerLog.Asker.worker(task, "Build it", 3), true);
        assertThat(log.entries().get(0).outcome())
            .as("asked again stays 'did not help' whatever came after")
            .isEqualTo("did not help");
        assertThat(log.entries().get(1).outcome()).isEqualTo("helped");
        assertThat(log.entries().get(1).outcomeBecause()).contains("passed its checks");

        // A later answer belongs to later work and is not touched by the earlier verdict.
        desk.askExpert("plugh xyzzy grault the corge garply", null);
        assertThat(log.entries().get(2).outcome()).isEqualTo("unknown");
        log.workFollowed(ExpertAnswerLog.Asker.worker(task, "Build it", 3), false);
        assertThat(log.entries().get(2).outcome()).isEqualTo("did not help");
        assertThat(log.entries().get(1).outcome()).isEqualTo("helped");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
