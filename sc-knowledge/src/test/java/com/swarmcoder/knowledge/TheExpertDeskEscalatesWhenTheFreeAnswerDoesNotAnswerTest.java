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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.runtime.ExpertHelp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bug this fixes: seven questions in a row about an unfamiliar framework, each one a keyword
 * hit somewhere and therefore "answered", none of them actually answering what was asked — three
 * of them asked for a method body or a whole file and got a trimmed excerpt instead. The worker
 * was killed after 25 reads without writing.
 *
 * <p>A tiny invented reference module stands in for the real framework, for the same reason
 * {@link AStuckWorkerCanAskInsteadOfGuessingTest} invents one: a mechanism proved only against one
 * real folder is one edit away from being a set of rules about that folder.
 */
class TheExpertDeskEscalatesWhenTheFreeAnswerDoesNotAnswerTest {

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

        curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local"),
                new KnowledgeCurator.Root("widgetlib", reference, "1.0")),
            null, world.resolve("cache"));
    }

    // -- a member the question names outright: the FULL member, not an excerpt --------------

    @Test
    void aShowMeTheBodyQuestionReturnsTheMembersFullSource() {
        ExpertHelp.Answer answer = freshDesk()
            .askExpert("Show me the body of GearBox.engage( so I can see exactly what it does",
                null);

        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.DETERMINISTIC);
        assertThat(answer.text())
            .as("the real body, not a signature")
            .contains("this.ratio = newRatio;")
            .as("free — the member is named outright, so nothing needed escalating")
            .startsWith("The question names this member outright");
    }

    // -- a path the question names outright: the FULL file, not an excerpt ------------------

    @Test
    void aFullFileContentsQuestionReturnsTheWholeFile() {
        ExpertHelp.Answer answer = freshDesk().askExpert(
            "I need the FULL file contents of "
                + "widgetlib-core/src/main/java/com/widgetlib/core/GearBox.java please", null);

        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.DETERMINISTIC);
        assertThat(answer.text())
            .as("the WHOLE file — every member, not just the one a keyword search would pick")
            .contains("public int currentRatio()")
            .contains("public void engage(int newRatio)")
            .contains("public void disengage()");
    }

    // -- a weak keyword hit is not an answer: escalate, with the free material as context ----

    @Test
    void aWeakKeywordMatchEscalatesAndHandsTheExpertTheFreeMaterial() {
        AtomicInteger called = new AtomicInteger();
        List<String> contextsSeen = new ArrayList<>();
        ExpertDesk desk = new ExpertDesk(curator, app, contracts(), (question, context) -> {
            called.incrementAndGet();
            contextsSeen.add(context);
            return "the expert's worked answer";
        });

        // Shares exactly one distinctive word with the reference material ("engage"); the rest —
        // reverse, without, unlocking, everything — match nothing in it.
        ExpertHelp.Answer answer = desk.askExpert(
            "how do I reverse engage without unlocking everything", null);

        assertThat(called.get())
            .as("a keyword hit covering a small share of the question is not an answer")
            .isOne();
        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(answer.reason()).contains("escalated: free answer covered");
        assertThat(contextsSeen).hasSize(1);
        assertThat(contextsSeen.get(0))
            .as("the best the free tier found, handed over as a starting point rather than "
                + "discarded")
            .contains("GearBox");
    }

    // -- a repeat is not new material: escalate ----------------------------------------------

    @Test
    void aRepeatedQuestionEscalatesInsteadOfRepeatingItself() {
        AtomicInteger called = new AtomicInteger();
        ExpertDesk desk = new ExpertDesk(curator, app, contracts(),
            (question, context) -> {
                called.incrementAndGet();
                return "```java\n// answered by the expert\n```";
            });

        ExpertHelp.Answer first = desk.askExpert("GearBox", null);
        assertThat(first.source())
            .as("well covered the first time, so it is free")
            .isEqualTo(ExpertHelp.Source.DETERMINISTIC);

        ExpertHelp.Answer second = desk.askExpert("GearBox", null);
        assertThat(second.source())
            .as("the same free material a second time teaches the worker nothing new")
            .isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(second.reason()).contains("already gave");
        assertThat(called.get()).isOne();
    }

    // -- a well-covered question stays free ---------------------------------------------------

    @Test
    void aWellCoveredQuestionStaysFree() {
        ExpertHelp.Answer answer = freshDesk().askExpert("GearBox", null);

        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.DETERMINISTIC);
        assertThat(answer.tokens()).isZero();
        assertThat(answer.reason()).startsWith("free: covered");
        assertThat(answer.text()).contains("GearBox");
    }

    // -----------------------------------------------------------------------------------------

    private ExpertDesk freshDesk() {
        return new ExpertDesk(curator, app, contracts());
    }

    private static List<ApiContract> contracts() {
        return List.of();
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
