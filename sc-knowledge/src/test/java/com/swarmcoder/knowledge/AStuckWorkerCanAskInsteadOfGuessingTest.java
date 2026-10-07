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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker's 911, on a framework that does not exist.
 *
 * <p>Same invented ledger codebase as {@link TheNearestExampleOnAnUnrelatedFrameworkTest}, for the
 * same reason: a mechanism designed while looking at one real reference folder is one edit away
 * from being a set of rules about that folder. Nothing asserted here names a real framework.
 *
 * <p>The two things that matter most are the last two: the escalation to a paid model is not wired
 * unless a caller wires it, and when it is wired it is capped. A worker that can ask an expensive
 * question is a worker that can ask it forty times.
 */
class AStuckWorkerCanAskInsteadOfGuessingTest {

    @TempDir
    Path world;

    Path app;
    Path reference;
    KnowledgeCurator curator;
    List<ApiContract> contracts;

    @BeforeEach
    void buildTheWorld() throws Exception {
        app = world.resolve("orders-app");
        reference = world.resolve("ledgerworks");

        write(app.resolve("pom.xml"), """
            <project>
              <groupId>com.acme</groupId><artifactId>orders-app</artifactId>
              <dependencyManagement><dependencies>
                <dependency>
                  <groupId>com.ledgerworks</groupId><artifactId>ledgerworks-bom</artifactId>
                  <version>3.2.0</version><type>pom</type><scope>import</scope>
                </dependency>
              </dependencies></dependencyManagement>
            </project>
            """);
        write(app.resolve("orders-app-service/pom.xml"),
            "<project><artifactId>orders-app-service</artifactId><dependencies></dependencies></project>");
        write(app.resolve("orders-app-service/src/main/java/com/acme/orders/service/Startup.java"),
            "package com.acme.orders.service;\n\npublic class Startup { }\n");

        write(reference.resolve("pom.xml"),
            "<project><groupId>com.ledgerworks</groupId><artifactId>ledgerworks</artifactId></project>");
        write(reference.resolve("ledgerworks-runtime/pom.xml"), """
            <project>
              <groupId>com.ledgerworks</groupId>
              <artifactId>ledgerworks-runtime</artifactId>
            </project>
            """);
        write(reference.resolve(
            "ledgerworks-runtime/src/main/java/com/ledgerworks/runtime/LedgerSession.java"), """
            package com.ledgerworks.runtime;

            public class LedgerSession {
                public <T> T append(Object entry) { return null; }
            }
            """);
        write(reference.resolve("payroll/pom.xml"), """
            <project>
              <groupId>com.ledgerworks</groupId><artifactId>payroll</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.ledgerworks</groupId><artifactId>ledgerworks-runtime</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);
        // Two uses of the same type: a short one and a long one. The short one is the answer.
        write(reference.resolve(
            "payroll/src/main/java/com/ledgerworks/payroll/service/PayslipServiceImpl.java"), """
            package com.ledgerworks.payroll.service;

            import com.ledgerworks.runtime.LedgerSession;

            import java.util.List;

            public class PayslipServiceImpl {

                private LedgerSession session;

                public String save(String payslip) {
                    return session.append(new PayslipEntries.Recorded(payslip));
                }

                public void reconcileEverything(List<String> all) {
                    for (String one : all) {
                        String trimmed = one.trim();
                        if (trimmed.isEmpty()) {
                            continue;
                        }
                        String normalised = trimmed.toLowerCase();
                        session.append(new PayslipEntries.Recorded(normalised));
                    }
                }
            }
            """);
        write(reference.resolve(
            "payroll/src/main/java/com/ledgerworks/payroll/service/PayslipEntries.java"), """
            package com.ledgerworks.payroll.service;

            public final class PayslipEntries {
                public static final class Recorded {
                    public String payslip;
                    public Recorded(String payslip) { this.payslip = payslip; }
                }
                private PayslipEntries() { }
            }
            """);

        curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local"),
                new KnowledgeCurator.Root("ledgerworks", reference, "3.2.0")),
            null, world.resolve("cache"));

        contracts = List.of(new ApiContract(UUID.randomUUID(), "OrderServiceImpl",
            "The server side of the order list.", "",
            "com.acme.orders.service.OrderServiceImpl",
            List.of("List<String> list()", "String save(String order)")));
    }

    @Test
    void anApiQuestionIsAnsweredWithCodeThatCompilesToday() {
        ExpertHelp.Answer answer = new ExpertDesk(curator, app, contracts)
            .askExpert("How do I write something with LedgerSession so it is recorded?", null);

        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.DETERMINISTIC);
        assertThat(answer.tokens()).as("the deterministic path costs nothing").isZero();
        assertThat(answer.text())
            .as("the body, not the signature: what you reach it through and what you pass")
            .contains("session.append(new PayslipEntries.Recorded(payslip));")
            .as("the import that method's file needs for it")
            .contains("import com.ledgerworks.runtime.LedgerSession;")
            .as("where it came from, so the worker can go and read the rest")
            .contains("PayslipServiceImpl.java");
    }

    @Test
    void itAnswersWithTheShortestRealUseAndNotTheLongestOne() {
        String text = new ExpertDesk(curator, app, contracts)
            .askExpert("LedgerSession", null).text();

        assertThat(text)
            .as("a long method that happens to touch the type teaches the call plus fifty lines "
                + "of something else")
            .doesNotContain("reconcileEverything");
    }

    @Test
    void itNamesTheBuildLineTheAskingProjectIsMissing() {
        String text = new ExpertDesk(curator, app, contracts)
            .askExpert("LedgerSession", null).text();

        assertThat(text)
            .contains("com.ledgerworks:ledgerworks-runtime")
            .contains("no `<version>`");
    }

    @Test
    void aSkeletonRequestIsAnsweredFromTheTasksOwnContract() {
        ExpertHelp.Answer answer = new ExpertDesk(curator, app, contracts)
            .requestSkeleton("OrderServiceImpl");

        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.DETERMINISTIC);
        assertThat(answer.text())
            .contains("public class OrderServiceImpl")
            .contains("throw new UnsupportedOperationException(\"TODO: save\");")
            .as("and where to put it, because a file in the wrong module is compiled by nothing")
            .contains("orders-app-service/src/main/java/com/acme/orders/service/"
                + "OrderServiceImpl.java")
            .as("with the reason the names may not be changed")
            .contains("do not rename anything");
    }

    @Test
    void aSkeletonCanBeAskedForInWordsRatherThanByTypeName() {
        assertThat(new ExpertDesk(curator, app, contracts)
            .requestSkeleton("the server side of the order list").text())
            .contains("public class OrderServiceImpl");
    }

    @Test
    void withNoExpertWiredNothingIsCalledAndTheWorkerIsToldToWrite() {
        ExpertHelp.Answer answer = new ExpertDesk(curator, app, contracts)
            .askExpert("How does Kubernetes schedule a pod?", "nothing");

        assertThat(answer.source())
            .as("the escalation is a seam, and by default it is not wired to anything")
            .isEqualTo(ExpertHelp.Source.NONE);
        assertThat(answer.text())
            .contains("Write your best attempt and let the build correct you");
    }

    @Test
    void theExpertIsOnlyReachedWhenNothingInTheCodebaseCanAnswer() {
        AtomicInteger called = new AtomicInteger();
        ExpertDesk desk = new ExpertDesk(curator, app, contracts,
            (question, context) -> {
                called.incrementAndGet();
                return "the expert's answer";
            });

        desk.askExpert("LedgerSession", null);
        assertThat(called.get())
            .as("a question the reference material answers never costs a model call")
            .isZero();

        desk.askExpert("How does Kubernetes schedule a pod?", null);
        assertThat(called.get()).isEqualTo(1);
    }

    @Test
    void theExpertHasNoPerWorkerCap() {
        AtomicInteger called = new AtomicInteger();
        ExpertDesk desk = new ExpertDesk(curator, app, contracts,
            (question, context) -> {
                called.incrementAndGet();
                return "the expert's answer";
            });

        int askCount = 6;
        List<ExpertHelp.Source> sources = new ArrayList<>();
        for (int i = 0; i < askCount; i++) {
            sources.add(desk.askExpert("How does Kubernetes schedule pod number " + i + "?", null)
                .source());
        }

        assertThat(called.get())
            .as("a worker that can ask an expensive question can ask it as many times as it needs")
            .isEqualTo(askCount);
        assertThat(sources)
            .as("nothing is refused for having asked too many times")
            .allMatch(source -> source == ExpertHelp.Source.MODEL);
        assertThat(desk.asked()).hasSize(askCount);
    }

    @Test
    void theExpertIsHandedThisCodebasesOwnMaterialToAnswerFrom() {
        List<String> contexts = new ArrayList<>();
        new ExpertDesk(curator, app, contracts, (question, context) -> {
            contexts.add(context);
            return "answer";
        }).askExpert("How does Kubernetes schedule a pod?", "I tried a Deployment");

        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0))
            .as("grounded in the same code the deterministic path searched, not in its own memory "
                + "of some other framework")
            .contains("ledgerworks")
            .as("and told what the worker already tried, so it corrects rather than repeats")
            .contains("I tried a Deployment");
    }

    // -- three of the twelve shapes, on a framework that does not exist ----------------------
    //
    // The corpus in TheDeskAnswersEveryShapeOfQuestionTest runs against the real reference
    // material, which is exactly what makes it worth having and also what makes it unable to
    // prove the desk is not shaped around that material. These three are the same shapes stated
    // in an invented framework's vocabulary.

    @Test
    void anArtifactShapedQuestionOnAnInventedFramework() {
        assertThat(new ExpertDesk(curator, app, contracts, refuse())
                .askExpert("what is ledgerworks-runtime and how do I declare it", null).text())
            .contains("ledgerworks-runtime")
            .contains("<dependency>");
    }

    @Test
    void aConceptShapedQuestionOnAnInventedFramework() {
        assertThat(new ExpertDesk(curator, app, contracts, refuse())
                .askExpert("how do I record a payslip so it is appended to the ledger", null)
                .text())
            .containsAnyOf("append", "Recorded", "PayslipEntries");
    }

    @Test
    void aQuestionWithNoAnswerAnywhereStillReachesTheExpertOnAnInventedFramework() {
        ExpertHelp.Answer answer = new ExpertDesk(curator, app, contracts,
            (question, context) -> "the expert's worked answer")
            .askExpert("qqzzx wibble frobnicate", null);

        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.MODEL);
        assertThat(answer.text()).contains("the expert's worked answer");
    }

    /** An expert that would fail the test if it were ever reached. */
    private static java.util.function.BiFunction<String, String, String> refuse() {
        return (question, context) -> {
            throw new AssertionError("the free tiers should have answered: " + question);
        };
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
