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

import com.swarmcoder.runtime.ExpertHelp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bug this fixes: harness run 30 (2026-09-03), one task, two workers — seven questions about
 * one unfamiliar RMI-proxy API in three minutes, every one of them answered {@code DETERMINISTIC
 * (0 tokens) — free: covered 0.4-0.6 of the question}, the expert never once reached. Two things
 * were true at once: the free tier found a DIFFERENT snippet for every rephrasing, so the
 * existing "did I already give this worker this exact text" check never fired; and the coverage
 * bar was scored against the WHOLE question, including the framework's own name and words the
 * worker's own task brief had already used, so a keyword hit on shared vocabulary kept passing.
 *
 * <p>A tiny invented reference module stands in for the real framework, for the same reason
 * {@link AStuckWorkerCanAskInsteadOfGuessingTest} invents one: a mechanism proved only against one
 * real folder is one edit away from being a set of rules about that folder.
 */
class AWorkerThatAsksAgainGoesToTheExpertTest {

    @TempDir
    Path world;

    Path app;
    Path reference;
    KnowledgeCurator curator;

    @BeforeEach
    void buildTheWorld() throws Exception {
        app = world.resolve("app");
        reference = world.resolve("widgetlib");

        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(reference.resolve("pom.xml"),
            "<project><groupId>com.widgetlib</groupId><artifactId>widgetlib</artifactId></project>");
        write(reference.resolve("widgetlib-core/pom.xml"), """
            <project>
              <groupId>com.widgetlib</groupId><artifactId>widgetlib-core</artifactId>
            </project>
            """);
        write(reference.resolve(
            "widgetlib-core/src/main/java/com/widgetlib/core/GearBox.java"), """
            package com.widgetlib.core;

            public class GearBox {

                private int ratio;

                public int currentRatio() {
                    return ratio;
                }

                public void engage(int newRatio) {
                    this.ratio = newRatio;
                }

                public void disengage() {
                    this.ratio = 0;
                }
            }
            """);
        // A second, unrelated type: two different subjects, so two different rephrasings can
        // each surface a genuinely different piece of the reference material — exactly the shape
        // that let the seven real questions escape the old "is this the same text I already gave"
        // check, which compares ANSWERS, not questions.
        write(reference.resolve(
            "widgetlib-core/src/main/java/com/widgetlib/core/ClutchPlate.java"), """
            package com.widgetlib.core;

            public class ClutchPlate {

                private boolean locked;

                public void lock() {
                    this.locked = true;
                }

                public void unlock() {
                    this.locked = false;
                }
            }
            """);

        curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local"),
                new KnowledgeCurator.Root("widgetlib", reference, "1.0")),
            null, world.resolve("cache"));
    }

    // -- rule 1: a question asked again, in different words, goes to the expert --------------

    @Test
    void aRephrasingOfAnEarlierQuestionEscalatesEvenWhenItsOwnFreeMaterialDiffers() {
        AtomicInteger escalations = new AtomicInteger();
        ExpertDesk desk = new ExpertDesk(curator, app, List.of(),
            (question, context) -> {
                escalations.incrementAndGet();
                return "the expert's worked answer for: " + question;
            });

        // Four asks, modelled on the real run: two share almost none of the first ask's words (a
        // weak keyword hit and a total miss — both realistic outcomes for an unrelated question,
        // neither is "the same question again"); the fourth reuses most of the first ask's own
        // phrasing but names the OTHER type, so its own best free material is genuinely different
        // text from the first ask's — nothing here trips the old "gave the identical text before"
        // check, only the new "asked something this close before" one.
        String ask1 = "Please show me exactly how to safely reverse without losing any pending "
            + "state changes on GearBox";
        String ask2 = "What is the Maven groupId of the completely unrelated widgetlib-core "
            + "artifact";
        String ask3 = "Does this project use snake case or camel case for its file names "
            + "typically speaking";
        String ask4 = "I need exactly how to safely reverse without losing any pending state "
            + "changes on ClutchPlate";

        ExpertHelp.Answer a1 = desk.askExpert(ask1, null);
        ExpertHelp.Answer a2 = desk.askExpert(ask2, null);
        desk.askExpert(ask3, null);
        ExpertHelp.Answer a4 = desk.askExpert(ask4, null);

        assertThat(a2.source())
            .as("a genuinely different question, about a different subject, stays free")
            .isEqualTo(ExpertHelp.Source.DETERMINISTIC);
        assertThat(a4.source())
            .as("a rephrasing of ask 1 — even though its own free material is about a "
                + "different type and so is not textually a repeat of anything given before")
            .isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(a4.reason())
            .as("names the ask it repeats, so the record says WHY")
            .contains("asked again")
            .contains("ask #1");
        assertThat(a4.text())
            .as("the real answer, not the free tier's weak material")
            .isEqualTo("the expert's worked answer for: " + ask4);
        // The first ask may or may not have escalated on its own merits — what matters here is
        // that asking again did, and did so without a second identical-text signal muddying which
        // rule caught it.
        assertThat(escalations.get()).isGreaterThanOrEqualTo(1);
        assertThat(a1.source()).isIn(ExpertHelp.Source.MODEL, ExpertHelp.Source.DETERMINISTIC);
    }

    // -- rule 3: the expert's answer is remembered per RUN, not per worker -------------------

    @Test
    void aNearDuplicateFromAnotherWorkerOfTheSameRunIsServedTheFirstWorkersAnswer() {
        AtomicInteger escalations = new AtomicInteger();
        RunAnswerCache runCache = new RunAnswerCache();

        ExpertDesk workerSeven = new ExpertDesk(curator, app, List.of(),
            (question, context) -> {
                escalations.incrementAndGet();
                return "the expert's worked answer, paid for once";
            });
        workerSeven.sharedAcrossRun(runCache, 7);

        ExpertDesk workerNine = new ExpertDesk(curator, app, List.of(),
            (question, context) -> {
                escalations.incrementAndGet();
                return "worker 9's own expert — must never be called for a duplicate";
            });
        workerNine.sharedAcrossRun(runCache, 9);

        String ask1 = "Please show me exactly how to safely reverse without losing any pending "
            + "state changes on GearBox";
        String ask4 = "I need exactly how to safely reverse without losing any pending state "
            + "changes on ClutchPlate";

        ExpertHelp.Answer fromWorkerSeven = workerSeven.askExpert(ask1, null);
        ExpertHelp.Answer fromWorkerNine = workerNine.askExpert(ask4, null);

        assertThat(fromWorkerNine.source())
            .as("served from the run's cache, not escalated a second time")
            .isEqualTo(ExpertHelp.Source.DETERMINISTIC);
        assertThat(fromWorkerNine.tokens()).isZero();
        assertThat(fromWorkerNine.reason())
            .as("says whose answer this is, so the record is honest about where it came from")
            .isEqualTo("free: the expert answered this for worker 7");
        assertThat(fromWorkerNine.text())
            .as("worker 7's real answer, not worker 9's own (never-called) expert, under the "
                + "question it was researched for (2026-10-04)")
            .startsWith("The expert already researched this in this run, for the question: ")
            .endsWith(fromWorkerSeven.text());
        assertThat(escalations.get())
            .as("only worker 7 ever paid for an escalation")
            .isOne();
    }

    // -- rule 2: coverage is scored over DISTINGUISHING tokens, not every word ---------------

    @Test
    void aQuestionThatOnlyRepeatsFrameworkWordsFromTheBriefDoesNotPassAsCovered() {
        String question = "How do I use the GearBox class's engage method to change the ratio";

        ExpertDesk withoutABrief = new ExpertDesk(curator, app, List.of(),
            (q, context) -> "escalated answer");
        ExpertHelp.Answer plain = withoutABrief.askExpert(question, null);
        assertThat(plain.source())
            .as("with no brief to exclude, this is a solid keyword hit and stays free")
            .isEqualTo(ExpertHelp.Source.DETERMINISTIC);

        ExpertDesk withABrief = new ExpertDesk(curator, app, List.of(),
            (q, context) -> "escalated answer");
        // Everything the question actually names was already in what this worker was handed —
        // the type, the members, and the very idea of "changing the ratio". A keyword hit that
        // only echoes the brief back is not an answer to anything the worker asked.
        withABrief.excludingBriefWords("The worker was told: GearBox is a class with engage, "
            + "disengage and currentRatio methods that change the ratio field.");
        ExpertHelp.Answer briefed = withABrief.askExpert(question, null);

        assertThat(briefed.source())
            .as("the same words, entirely accounted for by the brief, are not distinguishing")
            .isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(briefed.reason()).contains("escalated: free answer covered");
    }

    // -----------------------------------------------------------------------------------------

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
