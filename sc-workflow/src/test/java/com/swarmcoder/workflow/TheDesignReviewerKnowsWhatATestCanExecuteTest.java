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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.ArchDecision;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Requirement;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.BuildLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rubric design review is told which modules run only in a browser - the fact the architect
 * designed from - so "testability" is judged against what a test of the project can execute.
 *
 * <p>Live run 98 (2026-10-08): the architect, told that no acceptance test can execute the
 * browser module, proved a check about a list on a screen through the service behind it. The
 * reviewer, told the goal and the design but nothing of the build, objected that a service-layer
 * test does not prove the display, once before the revision and again after it. No revision can
 * answer that objection: the test it asks for cannot run.
 */
class TheDesignReviewerKnowsWhatATestCanExecuteTest {

    @TempDir
    Path repo;

    @Test
    void theReviewerReadsTheBuildFactBeforeTheGoal() throws Exception {
        String fact = AcceptanceTestReach.reviewerBrief(surveyOfABuildWithABrowserModule(),
            "app-server");
        assertThat(fact)
            .contains("app-client declares org.teavm:teavm-classlib")
            .contains("they live in app-server")
            .contains("NEVER object that such a check is not proved through the screen")
            .contains("It is still right to object when the goal promises a screen");

        String conversation = reviewAndCapture(fact);

        assertThat(conversation).contains("Critique the design against this rubric");
        assertThat(conversation.indexOf("A FACT ABOUT THIS BUILD"))
            .isGreaterThan(-1)
            .isLessThan(conversation.indexOf("Goal: Show every stored item"));
    }

    @Test
    void withoutABrowserModuleThePromptIsExactlyWhatItWas() throws Exception {
        assertThat(AcceptanceTestReach.reviewerBrief(BrowserOnlyCode.Survey.NONE, "app-server"))
            .isEmpty();
        assertThat(AcceptanceTestReach.reviewerBrief(null, null)).isEmpty();

        String plain = reviewAndCapture(null);
        assertThat(plain).doesNotContain("A FACT ABOUT THIS BUILD");
        assertThat(reviewAndCapture("  ")).isEqualTo(plain);
    }

    private static String reviewAndCapture(String buildFact) throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                seen.add(conversation);
                return "{\"approved\": true, \"objections\": []}";
            })) {
            DesignReviewerClient reviewer = new DesignReviewerClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));
            DesignReviewerClient.Review review = buildFact == null
                ? reviewer.review(design(), null) : reviewer.review(design(), null, buildFact);
            assertThat(review.approved).isTrue();
        }
        assertThat(seen).hasSize(1);
        return seen.get(0);
    }

    private BrowserOnlyCode.Survey surveyOfABuildWithABrowserModule() throws Exception {
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>app</artifactId>
              <version>1</version><packaging>pom</packaging>
              <modules><module>app-client</module><module>app-server</module></modules>
            </project>
            """);
        write("app-client/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><artifactId>app-client</artifactId>
              <dependencies>
                <dependency><groupId>org.teavm</groupId><artifactId>teavm-classlib</artifactId>
                  <scope>provided</scope></dependency>
              </dependencies>
            </project>
            """);
        write("app-server/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><artifactId>app-server</artifactId>
            </project>
            """);
        write("app-client/src/main/java/com/example/client/Screen.java",
            "package com.example.client;\npublic class Screen {}\n");
        write("app-server/src/main/java/com/example/server/Items.java",
            "package com.example.server;\npublic class Items {}\n");
        return BrowserOnlyCode.survey(repo, BuildLayout.read(repo, "maven"));
    }

    private void write(String path, String content) throws Exception {
        Path target = repo.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static DesignDocument design() {
        return new DesignDocument(UUID.randomUUID(), 1, "Show every stored item on a list screen",
            List.of(new Requirement(UUID.randomUUID(),
                "The list shows every stored item", Priority.HIGH)),
            List.of(new ArchDecision(UUID.randomUUID(), "The service returns the stored items and "
                + "the screen renders them", "the check is proved at the service", List.of())),
            List.of(new ApiContract(UUID.randomUUID(), "ItemService", "lists items", "",
                "com.example.server.ItemService", List.of("List<String> list()"))),
            List.of(), null, Instant.now());
    }
}
