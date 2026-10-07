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

import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reviewer's readings against the rules (design, plan, tests) run as a lookup session when one
 * is configured, and hand in the same verdict {@link DesignReviewerClient.Review}. When the session
 * gives none, the one-shot review answers, as it always did. No model is called: the endpoint is
 * scripted.
 */
class TheReviewerLooksFactsUpBeforeItObjectsTest {

    private static final String RULES = "RULE 'No hand-built services' [HARD]: a test never "
        + "constructs a service by hand.";

    @TempDir
    Path repo;
    @TempDir
    Path cache;

    private DesignReviewerClient reviewer(ScriptedAgentLlm llm) throws Exception {
        Files.createDirectories(repo.resolve("src/main/java"));
        Files.writeString(repo.resolve("src/main/java/BookService.java"),
            "public class BookService { public BookService(Db db) {} }\n");
        VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
        CloudGate gate = new CloudGate(0, null);
        DesignReviewerClient reviewer = new DesignReviewerClient(client, gate);
        reviewer.setLookupAgent(new LookupAgent(new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", repo, "local")), null, cache),
            null, repo, gate, new KoogAgentRuntime(), null, null));
        return reviewer;
    }

    @Test
    void theTestsReadingLooksAFactUpAndHandsInTheSameVerdict() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"approved\": true, \"objections\": []}",
                (turn, conversation) -> turn == 1
                    ? ScriptedAgentLlm.Turn.call("read_file",
                        Map.of("path", "project/src/main/java/BookService.java"))
                    : ScriptedAgentLlm.Turn.call("report_done", Map.of("approved", "false",
                        "objections", "line 'new BookService(db)' in src/test/java/BookTest.java "
                            + "conflicts with rule 'No hand-built services': it constructs one.\n"))
             )) {
            DesignReviewerClient.Review review = reviewer(llm).reviewTests(RULES,
                Map.of("src/test/java/BookTest.java", "class BookTest { Object s = new BookService(db); }"));

            assertThat(llm.sessionRequests).as("a lookup turn, then the verdict").hasSize(2);
            assertThat(llm.sessionRequests.get(1))
                .as("the lookup returned the real file").contains("public BookService(Db db)");
            assertThat(llm.sessionRequests.get(0)).contains("READ-ONLY LOOKUP TOOLS");
            assertThat(llm.oneReplyRequests).as("no one-shot review was needed").isEmpty();
            assertThat(review.approved).isFalse();
            assertThat(review.objections).containsExactly("line 'new BookService(db)' in "
                + "src/test/java/BookTest.java conflicts with rule 'No hand-built services': "
                + "it constructs one.");
        }
    }

    @Test
    void aSessionThatGivesNoVerdictFallsBackToTheOneShotReview() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"approved\": false, \"objections\": [\"task 'T' conflicts with "
                    + "rule 'No hand-built services': one sentence\"]}",
                (turn, conversation) -> ScriptedAgentLlm.Turn.text("I cannot tell."))) {
            DesignReviewerClient.Review review = reviewer(llm).reviewPlan(RULES,
                List.of(new com.swarmcoder.domain.Task(java.util.UUID.randomUUID(), 1L, "T",
                    "Construct the service by hand.", java.util.Set.of(), java.util.Set.of(),
                    List.of(), null, null, null, null, com.swarmcoder.domain.TaskState.PENDING)));

            assertThat(llm.oneReplyRequests).as("the one-shot review answered").hasSize(1);
            assertThat(review.approved).isFalse();
            assertThat(review.objections).hasSize(1);
        }
    }
}
