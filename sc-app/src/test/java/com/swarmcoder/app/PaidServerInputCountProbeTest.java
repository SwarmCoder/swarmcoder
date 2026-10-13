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

import com.swarmcoder.app.config.AgentModelConfig;
import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.SwarmConfig;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.inference.LookupMeter;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A PAID measurement, off unless asked for: what the architect's own model server counts as
 * input on the calls of one small agent session, against what the session sent (section 94).
 *
 * <p>Never runs in a build. It runs only with {@code -Dswarmcoder.paidProbe=true} and
 * {@code -Dswarmcoder.paidProbe.home=<a home folder whose .swarmcoder/config.yaml names the
 * architect's server>}; the settings are read by {@link ConfigLoader} as the application reads
 * them and handed to the product's own client and agent runtime. Nothing of the settings is
 * printed but the server's host name and the model's name.
 *
 * <p><b>Hard limits, here in code:</b> it stops before a call that could take the total past
 * {@link #MOST_TOKENS} tokens as the server reports them (input and output of every call), and
 * after {@link #MOST_CALLS} calls, whichever comes first. The running total is printed after
 * every call. {@code -Dswarmcoder.paidProbe.spentTokens} and {@code .spentCalls} carry what an
 * earlier run of the same measurement used.
 *
 * <p>Each conversation is the runtime's real tool loop with two trivial tools that answer a
 * fixed 200 characters; the model is asked to think and call one tool a turn. One line a call:
 * the server's input, output, thinking and cached-input counts, and the size of the request.
 */
class PaidServerInputCountProbeTest {

    static final long MOST_TOKENS = 200_000;
    static final int MOST_CALLS = 40;
    /** The longest answer one call may ask for; part of what the next call could cost. */
    static final int MOST_OUTPUT_TOKENS = 4_096;

    private static final String FIXED = ("This fixed answer is two hundred characters long and "
        + "says nothing at all. ").repeat(4).substring(0, 200);

    public static final class Tools {
        public String look(String what) {
            return FIXED;
        }

        public String note(String text) {
            return FIXED;
        }
    }

    private final AtomicLong tokens = new AtomicLong(Long.getLong("swarmcoder.paidProbe.spentTokens", 0));
    private final AtomicInteger calls = new AtomicInteger(Integer.getInteger("swarmcoder.paidProbe.spentCalls", 0));
    private final AtomicLong lastInput = new AtomicLong();
    private final AtomicLong inputInAll = new AtomicLong();
    private final AtomicLong outputInAll = new AtomicLong();
    private final AtomicLong thinkingInAll = new AtomicLong();
    private volatile String variant = "";
    private final AtomicInteger callOfVariant = new AtomicInteger();

    /** True when another call may be made without passing a limit. */
    private boolean mayCall() {
        long next = lastInput.get() * 2 + MOST_OUTPUT_TOKENS + 2_000;
        return calls.get() < MOST_CALLS && tokens.get() + next <= MOST_TOKENS;
    }

    @Test
    void whatThePaidServerCountsAsInput() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("swarmcoder.paidProbe"),
            "a paid measurement: only with -Dswarmcoder.paidProbe=true");
        String home = System.getProperty("swarmcoder.paidProbe.home");
        Assumptions.assumeTrue(home != null && !home.isBlank(),
            "needs -Dswarmcoder.paidProbe.home");
        String ownHome = System.getProperty("user.home");
        System.setProperty("user.home", home);
        RunMeter.recordTo(new RunMeter.Recorder() {
            @Override
            public Object begins(RunMeter.Tag tag, String run) {
                return null;
            }

            @Override
            public void finished(Object begun, RunMeter.Finished finished) {
                RunMeter.Call call = finished.call();
                int input = Math.max(0, call.promptTokens());
                int output = Math.max(0, call.completionTokens());
                calls.incrementAndGet();
                lastInput.set(input);
                inputInAll.addAndGet(input);
                outputInAll.addAndGet(output);
                thinkingInAll.addAndGet(Math.max(0, finished.reasoningTokens()));
                long total = tokens.addAndGet(input + output);
                System.out.println("PROBE|" + variant + "|call " + callOfVariant.incrementAndGet()
                    + "|input " + call.promptTokens() + "|output " + call.completionTokens()
                    + "|thinking " + finished.reasoningTokens()
                    + "|cached " + call.cachedPromptTokens()
                    + "|requestChars " + call.requestChars()
                    + "|samePrefixChars " + call.samePrefixChars()
                    + "|failed " + call.failed()
                    + "|running total " + total + " tokens, " + calls.get() + " calls");
            }

            @Override
            public void lookup(String role, LookupMeter.Kind kind, int chars) {
            }
        });
        try {
            Assumptions.assumeTrue(Files.exists(ConfigLoader.configPath()),
                "no settings file in that home");
            SwarmConfig config = ConfigLoader.loadDefaultConfig();
            AgentModelConfig role = config.roles().architect();
            ModelQuirks shape = role.resolvedQuirks();
            ModelQuirks quirks = new ModelQuirks(shape.label(),
                Math.min(MOST_OUTPUT_TOKENS, shape.maxOutputTokens()), shape.thinking(),
                shape.thinkingKwarg(), shape.noThinkDirective(), shape.textualToolHistory(),
                shape.jsonResponseFormat(), shape.http2(), shape.servedContextTokens(),
                shape.workingContextTokens(), shape.kvBytesPerToken(),
                shape.maxConcurrentSequences(), shape.verified(), shape.loadedTokensPerSecond());
            VllmClient client = new VllmClient(role.baseUrl(), role.apiKey(), role.modelName(), quirks);
            System.out.println("PROBE|server " + java.net.URI.create(client.baseUrl()).getHost()
                + "|model " + client.modelName() + "|shape " + role.effectiveShape()
                + "|" + quirks.describe());

            for (String name : System.getProperty("swarmcoder.paidProbe.variants",
                    "baseline,newMessage").split(",")) {
                switch (name.trim()) {
                    case "baseline" -> converse(client, quirks, "baseline", false, 8);
                    case "newMessage" -> converse(client, quirks, "newMessage", true, 10);
                    default -> System.out.println("PROBE|unknown variant " + name);
                }
            }
            System.out.println("PROBE|in all|input " + inputInAll.get() + "|output "
                + outputInAll.get() + "|of it thinking " + thinkingInAll.get()
                + "|total " + tokens.get() + "|calls " + calls.get());
        } finally {
            RunMeter.recordTo(null);
            System.setProperty("user.home", ownHome);
            System.clearProperty("swarmcoder.countedNotSent");
            System.clearProperty("swarmcoder.countedNotSent.leastTokens");
        }
    }

    /**
     * One conversation of at most {@code most} calls.
     *
     * @param newMessages false: the session as it ran before section 94's remedy; true: it
     *                    begins a new user message when the server's count shows input that
     *                    was not sent, at the small marks this small conversation needs
     */
    private void converse(VllmClient client, ModelQuirks quirks, String name, boolean newMessages,
                          int most) throws Exception {
        if (!mayCall()) {
            System.out.println("PROBE|" + name + "|not run: a limit would be passed");
            return;
        }
        variant = name;
        callOfVariant.set(0);
        lastInput.set(0);
        System.setProperty("swarmcoder.countedNotSent", String.valueOf(newMessages));
        System.setProperty("swarmcoder.countedNotSent.leastTokens", "200");
        Tools target = new Tools();
        List<AgentRuntime.ToolBinding> tools = List.of(
            new AgentRuntime.ToolBinding("look", "Looks one thing up.", target,
                Tools.class.getMethod("look", String.class)),
            new AgentRuntime.ToolBinding("note", "Keeps one note.", target,
                Tools.class.getMethod("note", String.class)));
        AtomicInteger asked = new AtomicInteger();
        AgentRuntime.TurnGuard guard = info -> asked.incrementAndGet() >= most || !mayCall()
            ? Optional.of(KillReason.BUDGET_EXCEEDED) : Optional.empty();
        AgentRuntime.ModelEndpoint endpoint = new AgentRuntime.ModelEndpoint(client.baseUrl(),
            client.apiKey(), client.modelName(), quirks.servedContextTokens(), quirks);
        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec("architect",
            "You are being used to test a tool loop. On every turn you think the task of that "
                + "turn through step by step before you act, and then you call exactly one tool. "
                + "You never answer in plain text.",
            endpoint, 0.2, most + 2, tools, guard)
            .with(new AgentRuntime.SessionOptions(600, 300, 600, null));
        try (AgentRuntime.AgentSession session = new KoogAgentRuntime().open(spec)) {
            AgentRuntime.SessionResult result = session.run(
                "Do this twenty times, once a turn, and do not stop until you are told to. On "
                    + "turn k (starting at k = 1) work out (137 + k) x (291 + k) by long "
                    + "multiplication, digit by digit, and check the result a second way. Then "
                    + "call `look` with the product as `what` on an odd turn, or `note` with the "
                    + "product as `text` on an even turn. One call a turn.");
            System.out.println("PROBE|" + name + "|ended " + result.killReason()
                .map(Enum::name).orElse("by itself") + " after " + result.turns() + " turn(s)");
        }
    }
}
