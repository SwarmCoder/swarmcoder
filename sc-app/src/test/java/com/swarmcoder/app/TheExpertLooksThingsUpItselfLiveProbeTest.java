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
package com.swarmcoder.app;

import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.ExpertEscalation;
import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.knowledge.ToolUsingExpert;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.TraceHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one live measurement of the tool-using expert: a real model, on the free local endpoint, put
 * to a question about a real framework it has never been trained on, with nothing in the prompt but
 * the question — and asked to go and find the answer.
 *
 * <p><b>Why it has to be live.</b> Every other test proves the machinery: the tools run, the
 * lookups are recorded, a read outside the roots is refused, a session with no answer says so.
 * None of them can prove the thing that decides whether this was worth building — that a model,
 * handed these particular tools with these particular descriptions, actually USES them to find a
 * real file rather than answering out of its own memory of some other framework. A scripted
 * endpoint cannot answer that, because the script is us deciding.
 *
 * <p><b>Free endpoint only, one conversation.</b> The endpoint must be a private-network host
 * ({@link #assertHostIsPrivate}) and the model is the free local one the workers themselves run on.
 * The expert in production is a paid role; nothing in this module may ever call one.
 *
 * <pre>
 * mvn -o test -pl sc-app -am -Dtest=TheExpertLooksThingsUpItselfLiveProbeTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://192.168.0.10:8002/v1 \
 *   -Dswarmcoder.live.model=qwen3.8-27b
 * </pre>
 */
class TheExpertLooksThingsUpItselfLiveProbeTest {

    @TempDir
    Path work;

    @Test
    @EnabledIfSystemProperty(named = "swarmcoder.live.baseUrl", matches = ".+",
        disabledReason = "needs a live model endpoint; see the class javadoc")
    void theExpertFindsTheRealAnswerInTheReferenceCheckoutInsteadOfRecallingOne() throws Exception {
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        String model = System.getProperty("swarmcoder.live.model", "qwen3.8-27b");
        assertHostIsPrivate(baseUrl);

        // The same reference root the harness gives its workers: the real ZeroZ checkout.
        HarnessReferenceRoot.Setup reference =
            HarnessReferenceRoot.build(work.resolve("project"), work.resolve("knowledge"));
        Librarian librarian = reference.librarian();
        System.out.println("[PROBE] " + reference.describe());
        System.out.println("[PROBE] the expert's tools: "
            + String.join(", ", ExpertTools.toolNames(librarian.curator(), librarian)));

        VllmClient client = new VllmClient(baseUrl, "", model,
            ModelQuirks.DEFAULTS.withLabel(model).withThinking(true));
        CloudGate gate = new CloudGate(0, null);   // free endpoint: nothing to cap
        TraceHub hub = new TraceHub(null);
        ExpertEscalation expert = new ExpertEscalation(client, gate, librarian.curator(), librarian,
            work.resolve("project"), List.of(), new KoogAgentRuntime(hub),
            ExpertEscalation.MAX_TURNS);

        // The EXPERT is asked, not the desk. Put through the desk this particular question is
        // answered by the free tiers for nothing — which is the desk working exactly as designed
        // and is proved elsewhere — and the expert would never be reached. What is being measured
        // here is the one thing only a live model can settle: given these tools and these
        // descriptions and NOTHING pasted into the prompt, does it go and find the answer.
        ToolUsingExpert.Report report = expert.answer(
            "how do I get the root object from the database node in this stack", "");

        System.out.println("[PROBE] answered: " + report.answered());
        System.out.println("[PROBE] turns: " + report.turns() + "  lookups: " + report.toolsUsed());
        System.out.println("[PROBE] ------------------------- answer -------------------------");
        System.out.println(report.text());
        System.out.println("[PROBE] ----------------------------------------------------------");

        assertThat(report.answered())
            .as("the expert reached an answer at all")
            .isTrue();
        assertThat(report.toolsUsed())
            .as("it looked things up rather than answering out of its own memory - that IS the "
                + "change being measured")
            .isNotEmpty();
        assertThat(report.text())
            .as("it names the real types this stack uses for exactly that, which are not guessable "
                + "from any other framework")
            .containsAnyOf("ZeroZDbNode", "DataRoot");
        assertThat(report.text())
            .as("and it cites where it read them, in the reference checkout")
            .contains("zerozstack-examples");
    }

    /** Refuses to run this probe against anything but a private-network endpoint. */
    private static void assertHostIsPrivate(String baseUrl) throws Exception {
        String host = URI.create(baseUrl).getHost();
        assertThat(host).as("swarmcoder.live.baseUrl must name a host").isNotBlank();
        InetAddress address = InetAddress.getByName(host);
        boolean isPrivate = address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress();
        assertThat(isPrivate)
            .as("refusing to run a live probe against a non-private host: " + host + " -> " + address)
            .isTrue();
    }
}
