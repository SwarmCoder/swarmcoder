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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExpertEscalation}: production's escalation for {@link ExpertDesk} — the utility/architect
 * role's endpoint, spent through the cloud gate exactly like {@code ArchitectClient},
 * {@code GuidelineExtractor} and {@code KnowledgeExtractor} already are.
 *
 * <p>Wired into a real {@link ExpertDesk} rather than called directly, because what matters is what
 * the DESK hands it — the question, what the worker already tried, and the desk's own free
 * material — and that the answer makes it back to whoever called {@code askExpert}. The fake in
 * every test is the HTTP endpoint, never a real model: nothing here is billed.
 *
 * <p>The escalation is an agent session with tools now rather than one chat call, so the endpoint
 * answers the way an agent endpoint does — {@link ScriptedExpertEndpoint}, a scripted turn at a
 * time, ending in {@code report_done}. What this class is responsible for did not change, and it is
 * what is still proved here: the whole question reaches the endpoint, the run's budget is charged
 * for it, there is no cap on how often a worker may ask, and an endpoint that is not there is
 * reported as not there with the charge given back. What the expert DOES with its turns is
 * {@code TheExpertLooksThingsUpItselfTest}.
 */
class ExpertEscalationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path world;

    /** One project + one reference module, just enough for the desk's free tiers to come up empty. */
    private ExpertDesk deskOver(String baseUrl, CloudGate cloudGate) throws Exception {
        Path app = world.resolve("app");
        Path reference = world.resolve("ledgerworks");
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(reference.resolve("pom.xml"),
            "<project><groupId>com.ledgerworks</groupId><artifactId>ledgerworks</artifactId></project>");
        write(reference.resolve("src/main/java/com/ledgerworks/LedgerSession.java"), """
            package com.ledgerworks;

            public class LedgerSession { }
            """);
        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local"),
                new KnowledgeCurator.Root("ledgerworks", reference, "1.0")),
            null, world.resolve("cache"));
        VllmClient client = new VllmClient(baseUrl, "", "expert-model", true);
        return new ExpertDesk(curator, app, List.<ApiContract>of(),
            new ExpertEscalation(client, cloudGate, curator, null, app, List.of(),
                new KoogAgentRuntime(), 8));
    }

    @Test
    void theQuestionWhatWasTriedAndTheDesksOwnMaterialAllReachTheExpert() throws Exception {
        String reply = "session.append(new Entry(payslip)); // import com.ledgerworks.LedgerSession;";
        try (ScriptedExpertEndpoint endpoint = answering(reply)) {
            CloudGate cloudGate = new CloudGate(1_000_000, null);
            ExpertDesk desk = deskOver(endpoint.baseUrl(), cloudGate);

            // Nothing free can answer this: no type in the corpus, no matching prose.
            ExpertHelp.Answer answer =
                desk.askExpert("qqzzx wibble frobnicate the whatsit", "I tried a plain field");

            assertThat(answer.source()).isEqualTo(ExpertHelp.Source.MODEL);
            assertThat(answer.text()).contains("session.append(new Entry(payslip))");

            String sent = endpoint.everythingSent();
            assertThat(sent)
                .as("the exact question the worker asked")
                .contains("qqzzx wibble frobnicate the whatsit")
                .as("what the worker already tried, so the expert corrects rather than repeats")
                .contains("I tried a plain field")
                .as("the desk's own grounding: which reference material this project has")
                .contains("ledgerworks");
        }
    }

    @Test
    void theToolsTheExpertMayUseAreDeclaredToTheEndpoint() throws Exception {
        try (ScriptedExpertEndpoint endpoint = answering("an answer")) {
            deskOver(endpoint.baseUrl(), new CloudGate(1_000_000, null))
                .askExpert("qqzzx wibble frobnicate the whatsit", null);

            assertThat(endpoint.everythingSent())
                .as("the expert is opened WITH its lookups, natively declared — an expert that "
                    + "cannot look anything up is the thing this replaced")
                .contains("public_shape")
                .contains("read_file")
                .contains("dependency_declaring")
                .contains("report_done");
        }
    }

    @Test
    void theCloudGateIsChargedForBothThePromptAndTheReply() throws Exception {
        try (ScriptedExpertEndpoint endpoint = answering("a worked answer of some length")) {
            CloudGate cloudGate = new CloudGate(1_000_000, null);
            ExpertDesk desk = deskOver(endpoint.baseUrl(), cloudGate);

            assertThat(cloudGate.used()).isZero();
            desk.askExpert("qqzzx wibble frobnicate the whatsit", null);

            assertThat(cloudGate.used())
                .as("charged before the session opened (the prompt) and again for the turn the "
                    + "server reported — never zero for a session that reached the model")
                .isGreaterThan(0);
        }
    }

    @Test
    void aFourthAndFifthEscalationBothGoThroughAndAreCountedAndCharged() throws Exception {
        try (ScriptedExpertEndpoint endpoint = answering("an answer")) {
            CloudGate cloudGate = new CloudGate(1_000_000, null);
            ExpertDesk desk = deskOver(endpoint.baseUrl(), cloudGate);

            for (int i = 0; i < 5; i++) {
                ExpertHelp.Answer answer =
                    desk.askExpert("qqzzx wibble frobnicate number " + i, null);
                assertThat(answer.source())
                    .as("there is no per-worker cap; every escalation, including the 4th and 5th, "
                        + "reaches the expert")
                    .isEqualTo(ExpertHelp.Source.MODEL);
            }

            assertThat(endpoint.turnsServed())
                .as("all five sessions reached the endpoint")
                .isEqualTo(5);
            assertThat(cloudGate.used())
                .as("the 4th and 5th calls were charged just like the first three")
                .isGreaterThan(0);
        }
    }

    @Test
    void anUnreachableEndpointRefundsTheChargeAndTheDeskSaysSoPlainly() throws Exception {
        // A closed local port: nothing is listening, so the call fails before any bytes come back —
        // exactly the shape EndpointOutage recognises (connection refused).
        int deadPort;
        try (var socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        CloudGate cloudGate = new CloudGate(1_000_000, null);
        ExpertDesk desk = deskOver("http://127.0.0.1:" + deadPort, cloudGate);

        ExpertHelp.Answer answer = desk.askExpert("qqzzx wibble frobnicate the whatsit", null);

        assertThat(answer.source()).isEqualTo(ExpertHelp.Source.NONE);
        assertThat(answer.text()).contains("could not be reached");
        assertThat(cloudGate.used())
            .as("the prompt charge is given back when the endpoint was never reached")
            .isZero();
    }

    /** An endpoint whose first turn is the answer: report_done, straight away. */
    private static ScriptedExpertEndpoint answering(String answer) throws Exception {
        String args = JSON.writeValueAsString(JSON.createObjectNode().put("answer", answer));
        return new ScriptedExpertEndpoint(
            turn -> ScriptedExpertEndpoint.Reply.toolCall("report_done", args));
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
