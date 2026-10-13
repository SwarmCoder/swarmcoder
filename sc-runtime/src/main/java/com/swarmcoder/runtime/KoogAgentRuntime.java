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
package com.swarmcoder.runtime;

import ai.koog.agents.core.agent.AIAgent;
import ai.koog.agents.core.agent.config.AIAgentConfig;
import ai.koog.agents.core.agent.config.MissingToolsConversionStrategy;
import ai.koog.agents.core.agent.config.ToolCallDescriber;
import ai.koog.agents.core.agent.context.AIAgentFunctionalContext;
import ai.koog.agents.core.environment.ReceivedToolResult;
import ai.koog.agents.core.tools.ToolRegistry;
import ai.koog.agents.core.tools.ToolRegistryBuilder;
import ai.koog.http.client.ktor.KtorKoogHttpClient;
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig;
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings;
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient;
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor;
import ai.koog.prompt.llm.LLMCapability;
import ai.koog.prompt.llm.LLModel;
import ai.koog.prompt.llm.OpenAILLMProvider;
import ai.koog.prompt.Prompt;
import ai.koog.prompt.params.LLMParams;
import ai.koog.prompt.message.Message;
import ai.koog.prompt.message.MessagePart;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.inference.EndpointActivity;
import com.swarmcoder.inference.EndpointOutage;
import com.swarmcoder.inference.ModelQuirks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/**
 * The Koog 1.0 implementation of {@link AgentRuntime} (rule R4: Koog is IN, confined to this
 * module). Each session is a Koog functional-strategy agent driving the blocking Java API:
 * {@code requestLLM → executeTools → sendToolResults} until the model stops calling tools,
 * a tool signals completion, the turn cap is hit, or the {@link TurnGuard} kills the run.
 */
public final class KoogAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(KoogAgentRuntime.class);

    /** Tool name that ends the loop; the worker toolbox registers report_done under this name. */
    public static final String DONE_TOOL = "report_done";

    /**
     * How many turns in a row may need a compaction before the worker is stopped instead.
     *
     * <p>A working compaction buys eight or nine turns before the next one — it cuts from three
     * quarters of the budget to two fifths, and a turn adds about two thousand tokens. Needing one
     * on three turns running therefore does not mean the conversation is busy; it means the
     * conversation has reached the smallest it can be made and is still over the line. Repeating the
     * attempt is not free: every compaction rewrites the head of the history, so the model server
     * must re-read the whole conversation instead of reusing what it had already prefilled. The
     * owner's box did that 532 times in one day for an average of 1298 tokens each.
     *
     * <p>Three rather than two because a single unlucky pair — a giant tool output landing right
     * after a compaction — is a real thing that happens and recovers on its own.
     */
    static final int MAX_CONSECUTIVE_COMPACTIONS = 3;

    /**
     * What a user message begun by the session itself says (section 94; see
     * {@link CountedNotSent}). It asks for nothing new: the results it follows are the answers
     * to the model's own calls.
     */
    static final String GO_ON = "Those are the results of your calls. Go on with your work.";

    /** What a turn that called no tool is answered with. */
    static final String USE_YOUR_TOOLS =
        "Continue using your tools. When the task is complete, call " + DONE_TOOL + ".";

    /**
     * How many turns of one session may call no tool and be answered with
     * {@link #USE_YOUR_TOOLS} (section 102). Each such answer is a whole request more, and the
     * session's allowance of two in a row never ran out for a session that called a tool in
     * between. The next turn without a call after these ends the session, as two in a row
     * always did: with what the model said as its answer.
     */
    static final int MAX_TURNS_WITHOUT_A_CALL =
        Math.max(1, Integer.getInteger("swarmcoder.session.turnsWithoutACall", 3));

    /**
     * The conversation without a turn that called no tool and the reminder it was answered
     * with, once the model has gone on to call one (section 102). Expects the last three
     * messages to be that turn, the reminder exactly as this runtime sent it, and the answer
     * to it; anything else is returned as it is, the same list. What comes before those three
     * is not touched, so the server's reading of it stays good, and every later request is
     * shorter by the two.
     */
    static List<Message> withoutTheTurnThatCalledNothing(List<Message> messages) {
        int size = messages.size();
        if (size < 3 || !(messages.get(size - 1) instanceof Message.Assistant)
                || !(messages.get(size - 2) instanceof Message.User reminder)
                || !(messages.get(size - 3) instanceof Message.Assistant idle)) {
            return messages;
        }
        if (reminder.getParts().size() != 1
                || !(reminder.getParts().get(0) instanceof MessagePart.Text said)
                || !USE_YOUR_TOOLS.equals(said.getText())) {
            return messages;
        }
        for (MessagePart part : idle.getParts()) {
            if (part instanceof MessagePart.Tool.Call) {
                return messages;
            }
        }
        List<Message> kept = new ArrayList<>(messages.subList(0, size - 3));
        kept.add(messages.get(size - 1));
        return List.copyOf(kept);
    }

    private final TraceHub traceHub;

    public KoogAgentRuntime() {
        this(TraceHub.NONE);
    }

    public KoogAgentRuntime(TraceHub traceHub) {
        this.traceHub = traceHub == null ? TraceHub.NONE : traceHub;
    }

    @Override
    public AgentSession open(SessionSpec spec) {
        return new KoogSession(spec, traceHub.begin(spec));
    }

    /** Thrown inside the strategy to abort the Koog run; carries the kill reason out. */
    private static final class KillSignal extends RuntimeException {
        final KillReason reason;

        KillSignal(KillReason reason) {
            super("killed: " + reason, null, false, false);
            this.reason = reason;
        }
    }

    /**
     * The pure half of the reasoning-strip described on {@code KoogSession.dropReasoningFromSessionHistory}:
     * given a session's message history, returns it with any {@link MessagePart.Reasoning} part
     * removed from the LAST message, when that message is an assistant turn that carries one.
     * Returns the SAME list instance (by reference — callers rely on this to skip a needless
     * rewrite) when there is nothing to strip.
     *
     * <p>Kept pure and package-visible on purpose: this is the one fact that has to be true —
     * "a turn's reasoning never survives into the next request" — and it can be proven directly,
     * on a handful of {@link Message} objects, with no live model and no running Koog agent. See
     * {@code KoogAgentRuntimeReasoningTest}.
     */
    static List<Message> withoutReasoningOnLastTurn(List<Message> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        int lastIndex = messages.size() - 1;
        if (!(messages.get(lastIndex) instanceof Message.Assistant assistant)) {
            return messages;
        }
        boolean carriesReasoning = assistant.getParts().stream()
            .anyMatch(part -> part instanceof MessagePart.Reasoning);
        if (!carriesReasoning) {
            return messages;
        }
        List<MessagePart.ResponsePart> kept = assistant.getParts().stream()
            .filter(part -> !(part instanceof MessagePart.Reasoning))
            .toList();
        List<Message> rewritten = new ArrayList<>(messages);
        rewritten.set(lastIndex,
            new Message.Assistant(kept, assistant.getMetaInfo(), assistant.getFinishReason()));
        return List.copyOf(rewritten);
    }

    /**
     * A conversation that ended on the model's hand-in call carries that call without an answer
     * to it (the hand-in tool ends the loop before its result is sent back). A new user message
     * may not follow an unanswered call, so the last assistant turn is left off when it made
     * calls; one that only spoke stays. Everything before it is unchanged.
     */
    static List<Message> withoutUnansweredCalls(List<Message> messages) {
        if (messages.isEmpty()
                || !(messages.get(messages.size() - 1) instanceof Message.Assistant last)) {
            return messages;
        }
        for (MessagePart part : last.getParts()) {
            if (part instanceof MessagePart.Tool.Call) {
                return new ArrayList<>(messages.subList(0, messages.size() - 1));
            }
        }
        return messages;
    }

    private static final class KoogSession implements AgentSession {

        private final SessionSpec spec;
        private final TraceHub.SessionTracer tracer;
        private final AtomicLong tokensUsed = new AtomicLong();
        private final AtomicInteger turns = new AtomicInteger();

        /**
         * The conversation as it stood when the last {@link #run} ended on a hand-in or a plain
         * answer; null before one did, and after a run that was killed or failed. When set, the
         * next {@link #run} continues from it instead of starting from the system prompt alone.
         */
        private volatile List<Message> carried;

        @Override
        public boolean canContinue() {
            return carried != null;
        }

        @Override
        public long conversationTokens() {
            List<Message> held = carried;
            return held == null ? -1 : HistoryTrim.estimateTokens(held);
        }

        /** Keeps the conversation for a later {@link #run}; see {@link #carried}. */
        private void keepConversation(AIAgentFunctionalContext ctx) {
            List<Message> messages = ctx.llm().writeSession(session -> session.getPrompt().getMessages());
            carried = withoutUnansweredCalls(messages);
        }

        /**
         * How many turns in a row have needed a compaction. Reset by any turn that does not.
         *
         * <p>This is the meter on the failure that started the second round of this work. A
         * compaction that cannot reach its target does not fail loudly: it succeeds at reclaiming
         * almost nothing, and next turn the conversation is over the line again, so it runs again,
         * on the same content, under the same constraints, to the same effect. The owner's log reads
         * "compacted at turn 81, 82, 83, 84" — that is not a turn-based trigger, it is the 81st
         * consecutive futile attempt at a threshold the conversation can no longer reach. In one day
         * it happened 532 times, and each one rewrites the head of the history and so throws away
         * this session's prefix cache: 532 cold prefills bought for an average of 1298 tokens each.
         */
        private final AtomicInteger consecutiveCompactions = new AtomicInteger();

        /**
         * When the request that is currently outstanding was sent, and how big it was.
         *
         * <p>Both are needed only when a request fails, and both are gone by then: the exception
         * carries neither. Held here so the classifier can ask "was this endpoint answering anyone
         * else while my request sat there?" — see {@link EndpointOutage#isOutage(Throwable,
         * EndpointOutage.Attempt)}.
         */
        private final java.util.concurrent.atomic.AtomicReference<EndpointOutage.Attempt> inFlight =
            new java.util.concurrent.atomic.AtomicReference<>();

        /**
         * Estimated size of the conversation as last measured, for the record above. Measured just
         * before the turn's own tool results are appended, so it runs about one tool output behind
         * the truth — close enough for "was this request oversized?", and there is no cheaper
         * moment: the server's own count only arrives with an answer, and the request that matters
         * is the one that never gets one.
         */
        private final AtomicInteger promptTokens = new AtomicInteger();

        /**
         * Sees every request and response of this session at the HTTP client: what the server
         * took from its prompt cache, and how much of each request repeated the one before.
         */
        private final UsageTap tap = new UsageTap(new KtorKoogHttpClient.Factory());

        /** The server's own per-call counts, summed; see {@link AgentSession#promptTokensSent}. */
        private final AtomicLong promptSent = new AtomicLong();
        private final AtomicLong completionGenerated = new AtomicLong();
        private final java.util.concurrent.atomic.AtomicBoolean usageSeen =
            new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public long promptTokensSent() {
            return usageSeen.get() ? promptSent.get() : -1;
        }

        private volatile boolean timedOutSmall;

        @Override
        public boolean timedOutOnASmallConversation() {
            return timedOutSmall;
        }

        @Override
        public long completionTokensGenerated() {
            return usageSeen.get() ? completionGenerated.get() : -1;
        }

        /** Why this session was told to stop from outside; null while it was not. */
        private volatile KillReason cancelRequested;
        /** The thread waiting on a model request, and only while it is; guarded by the lock. */
        private Thread askingThread;
        private final Object cancelLock = new Object();

        @Override
        public void cancel(KillReason reason) {
            synchronized (cancelLock) {
                if (cancelRequested != null) {
                    return;
                }
                cancelRequested = reason == null ? KillReason.SUPERSEDED : reason;
                // Only a thread that is waiting for the model is interrupted. One that is
                // running a tool is left to finish it - a half-written file helps nobody - and
                // stops before its next request.
                if (askingThread != null) {
                    askingThread.interrupt();
                }
            }
        }

        /** The stop asked for from outside, as this session's result. */
        private SessionResult cancelled() {
            Thread.interrupted(); // the interrupt was ours; what follows must not inherit it
            KillReason reason = cancelRequested;
            tracer.killed(reason);
            tracer.closed("KILLED", reason, turns.get(), tokensUsed.get());
            return new SessionResult("", Optional.of(reason), turns.get(), tokensUsed.get());
        }

        KoogSession(SessionSpec spec, TraceHub.SessionTracer tracer) {
            this.spec = spec;
            this.tracer = tracer;
        }

        @Override
        public SessionResult run(String input) {
            if (carried == null) {
                tracer.opened(spec.systemPrompt(), input);
            } else {
                tracer.nudge("The conversation continues with: " + input);
            }
            AIAgent<String, String> agent = buildAgent();
            owner = CloudGate.current();
            // Kept again only by a run that ends cleanly: one that was killed or failed is gone.
            carried = null;
            try {
                String output = agent.run(input);
                tracer.closed("COMPLETED", null, turns.get(), tokensUsed.get());
                return new SessionResult(output, Optional.empty(), turns.get(), tokensUsed.get());
            } catch (KillSignal kill) {
                tracer.killed(kill.reason);
                tracer.closed("KILLED", kill.reason, turns.get(), tokensUsed.get());
                return new SessionResult("", Optional.of(kill.reason), turns.get(), tokensUsed.get());
            } catch (Exception e) {
                if (cancelRequested != null) {
                    // Told to stop from outside: whatever the abandoned request threw is that
                    // stop, not an outage and not an error.
                    return cancelled();
                }
                // Unwrap: Koog wraps strategy exceptions; a KillSignal may arrive as a cause.
                for (Throwable t = e; t != null; t = t.getCause()) {
                    if (t instanceof KillSignal kill) {
                        tracer.killed(kill.reason);
                        tracer.closed("KILLED", kill.reason, turns.get(), tokensUsed.get());
                        return new SessionResult("", Optional.of(kill.reason), turns.get(), tokensUsed.get());
                    }
                }
                // Every remaining failure used to be reported as TIMEOUT, and this is where an
                // outage lost its identity: the model server being unreachable arrived at the engine
                // as N workers that had timed out, indistinguishable from N that had genuinely tried
                // and run out of budget — so the engine spent the story's one repair round on
                // candidates that had never reached a model. Naming it here is what lets every layer
                // above tell "the model was not there" from "the model said no" (UX v3 §2.4).
                //
                // Everything EndpointOutage does not recognise is now WORKER_ERROR, not TIMEOUT.
                // EndpointOutage already catches every real elapsed-time transport timeout (socket,
                // HTTP, ktor's own timeout plugin) by type, so whatever lands here is something else
                // entirely — a bug, a malformed response the tool loop could not parse further up, an
                // unexpected framework exception — and calling it TIMEOUT was never accurate for any
                // of those either.
                //
                // TIMEOUT is now produced, for the first time: a request the server accepted and did
                // not finish answering, while that same server was demonstrably answering other
                // callers. That used to be recorded as an outage, which made the whole run wait
                // indefinitely for a box that was working fine (six of them on 2026-09-01).
                EndpointOutage.Attempt attempt = inFlight.get();
                KillReason reason;
                if (!EndpointOutage.isOutage(e)) {
                    reason = KillReason.WORKER_ERROR;
                } else if (EndpointOutage.isOutage(e, attempt)) {
                    reason = KillReason.ENDPOINT_OUTAGE;
                } else {
                    reason = KillReason.TIMEOUT;
                    timedOutSmall = attempt != null && attempt.smallConversation();
                    int waited = com.swarmcoder.inference.RequestTimeouts.workerRequestSeconds();
                    log.error("Session '{}' timed out on its own request: {}", spec.role(),
                        EndpointOutage.slowRequestSentence(attempt, waited,
                            spec.endpoint().quirks().turnOutputTokens(waited)));
                }
                log.error("Session '{}' failed ({}): {}", spec.role(), reason, e.getMessage(), e);
                tracer.closed("FAILED", reason, turns.get(), tokensUsed.get());
                return new SessionResult("", Optional.of(reason), turns.get(), tokensUsed.get());
            }
        }

        private AIAgent<String, String> buildAgent() {
            // Kotlin default arguments are not visible from Java here (no @JvmOverloads on
            // OpenAIClientSettings) — spell out the standard API paths. The base URL is
            // normalized: users conventionally configure ".../v1", but the paths below
            // already carry the v1 prefix.
            OpenAIClientSettings settings = new OpenAIClientSettings(
                stripV1(spec.endpoint().baseUrl()),
                requestTimeouts(),
                "v1/chat/completions",
                "v1/responses",
                "v1/embeddings",
                "v1/moderations",
                "v1/models");
            OpenAILLMClient client = new OpenAILLMClient(
                spec.endpoint().apiKey() == null ? "" : spec.endpoint().apiKey(),
                settings,
                tap);

            ModelQuirks quirks = spec.endpoint().quirks();

            LLModel model = new LLModel(
                OpenAILLMProvider.INSTANCE,
                spec.endpoint().modelId(),
                List.of(LLMCapability.Tools.INSTANCE,
                    LLMCapability.Temperature.INSTANCE,
                    LLMCapability.Completion.INSTANCE,
                    // Routes requests through the chat-completions path (vLLM's surface);
                    // without it the OpenAI client cannot determine params for non-OpenAI ids.
                    LLMCapability.OpenAIEndpoint.Completions.INSTANCE),
                spec.endpoint().contextLength(),
                null);

            ToolRegistryBuilder registry = ToolRegistry.builder();
            for (ToolBinding tool : spec.tools()) {
                registry.tool(tool.method(), tool.target(), tool.name(), tool.description());
            }

            // MultiLLMPromptExecutor routes by provider *identity* (plain map lookup, LLMProvider
            // has no equals). Somewhere in Koog the model's provider loses identity (config
            // serialization), so the direct lookup can miss. The fallback — resolved eagerly at
            // construction, where identity is still intact — pins every request to our client.
            MultiLLMPromptExecutor executor = new MultiLLMPromptExecutor(
                Map.of(OpenAILLMProvider.INSTANCE, client),
                new MultiLLMPromptExecutor.FallbackPromptExecutorSettings(OpenAILLMProvider.INSTANCE, model));

            // How tool-call HISTORY is sent is a property of the model's CHAT TEMPLATE, not of us.
            // Some templates (Qwen's) iterate tool-call arguments as a mapping while OpenAI-spec
            // clients send them as a string, and reject the second turn with 400 "Can only get
            // item pairs from a mapping"; those need the history textified to plain-text JSON.
            // Tool *definitions* go natively either way, so the model keeps calling tools normally.
            // See sc-runtime/NOTES.md: this must never quietly become native-for-everyone, but it
            // is now a per-model setting instead of being forced on for every model that will ever
            // run here.
            var configBuilder = AIAgentConfig.builder()
                .model(model)
                .maxAgentIterations(Math.max(spec.maxTurns() * 2, 10)); // Koog's safety net above ours
            if (quirks.textualToolHistory()) {
                configBuilder = configBuilder.missingToolsConversionStrategy(
                    new MissingToolsConversionStrategy.All(ToolCallDescriber.JSON.INSTANCE));
            }
            AIAgentConfig config = configBuilder.build();

            // Reasoning switch, worker path. The agent framework's OpenAI client cannot send
            // chat_template_kwargs, so the only lever here is an in-prompt directive — and WHICH
            // directive is a property of the model ("/no_think" is a Qwen convention and means
            // nothing to anything else). A model whose profile names no directive gets an
            // untouched prompt. Appended AFTER the shared prefix so prefix-cache alignment across
            // the group is preserved.
            String systemPrompt = quirks.appendsNoThinkDirective()
                ? spec.systemPrompt() + "\n\n" + quirks.noThinkDirective()
                : spec.systemPrompt();

            // Bound the generation. Workers used to send NO max_tokens at all — only the role
            // client capped output — so a rambling worker turn could stream toward the served
            // context ceiling with nothing to stop it. All eight LLMParams arguments are spelled
            // out because Kotlin default arguments are not visible from Java.
            // And bound it to what the server can write before the client stops waiting (live
            // run 74): the call is not streamed, so an answer still being written at the timeout
            // is lost whole, and the session with it. See ModelQuirks.turnOutputTokens.
            LLMParams params = new LLMParams(spec.temperature(), quirks.turnOutputTokens(
                    com.swarmcoder.inference.RequestTimeouts.workerRequestSeconds()),
                null, null, null, null, null, null);
            List<Message> earlier = carried;
            Prompt prompt = earlier == null
                ? Prompt.builder(spec.role()).system(systemPrompt).build().withParams(params)
                : new Prompt(earlier, spec.role(), params);

            return AIAgent.builder()
                .<String, String>functionalStrategy(spec.role(), this::loop)
                .promptExecutor(executor)
                .agentConfig(config)
                .prompt(prompt)
                .toolRegistry(registry.build())
                .build();
        }

        /**
         * How long a request is waited for. The framework's own default request and socket
         * timeouts were both 900 seconds and written down nowhere here; now both follow
         * {@link com.swarmcoder.inference.RequestTimeouts}. The call is not streamed, so the
         * socket is silent for as long as the answer takes and must wait as long as the request.
         */
        private static ConnectionTimeoutConfig requestTimeouts() {
            long millis = com.swarmcoder.inference.RequestTimeouts.workerRequestSeconds() * 1000L;
            return new ConnectionTimeoutConfig(millis,
                new ConnectionTimeoutConfig().getConnectTimeoutMillis(), millis);
        }

        /**
         * Whose work this session is: the run, story and project of the thread that called
         * {@link #run}. Taken there and entered again in {@link #loop}, because the framework
         * runs the loop on a thread of its own pool, and such a thread carries whatever scope
         * the thread that first created it happened to be in - another run's, or none
         * (section 93: a session's charges went to the wrong run).
         */
        private volatile CloudGate.Where owner;

        /** The loop, run as the work of the run that opened the session. */
        private String loop(AIAgentFunctionalContext ctx, String input) {
            try (CloudGate.Entered inRun = CloudGate.enter(owner)) {
                return turns(ctx, input);
            }
        }

        /** The worker loop (spec §11.2 shape), with the TurnGuard consulted every turn. */
        private String turns(AIAgentFunctionalContext ctx, String input) {
            int consecutiveTextTurns = 0;
            int turnsWithoutACall = 0;
            boolean remindedLastTurn = false;
            Message.Assistant response = ask(ctx, () -> ctx.requestLLM(input));

            while (true) {
                turns.incrementAndGet();
                tokensUsed.set(ctx.latestTokenUsage());
                List<MessagePart.Tool.Call> calls = toolCalls(response);
                if (calls.isEmpty()) {
                    calls = recoverTextEmittedCalls(response);
                }
                tracer.llmResponse(textOf(response),
                    calls.stream().map(MessagePart.Tool.Call::getTool).toList(), tokensUsed.get(),
                    reasoningTokensEstimate(response));
                guard(consecutiveTextTurns);
                if (turns.get() > spec.maxTurns()) {
                    stopOnTurnCap();
                }

                if (calls.isEmpty()) {
                    consecutiveTextTurns++;
                    turnsWithoutACall++;
                    if (consecutiveTextTurns >= 2) {
                        // Two turns without tool use and without report_done: the model has
                        // stopped making progress through the sanctioned interface.
                        endedWithoutACall("the second in a row");
                        keepConversation(ctx);
                        return textOf(response);
                    }
                    if (turnsWithoutACall > MAX_TURNS_WITHOUT_A_CALL) {
                        // Each reminder is a whole request more (section 102).
                        endedWithoutACall("turn number " + turnsWithoutACall + " of this session "
                            + "that did so; a session is reminded " + MAX_TURNS_WITHOUT_A_CALL
                            + " time(s)");
                        keepConversation(ctx);
                        return textOf(response);
                    }
                    // Nothing is rewritten before this request: it is the last one with one
                    // message more, so the server reads only that.
                    tracer.nudge(USE_YOUR_TOOLS);
                    remindedLastTurn = true;
                    response = ask(ctx, () -> ctx.requestLLM(USE_YOUR_TOOLS));
                    continue;
                }
                consecutiveTextTurns = 0;
                if (remindedLastTurn) {
                    // The model is working again: the turn that did nothing and the reminder
                    // are not carried on every later request.
                    remindedLastTurn = false;
                    forgetTheTurnThatCalledNothing(ctx);
                }

                // An argument its tool lets a caller leave out is the empty string (run 103).
                calls = OmittedArguments.filled(calls, spec.tools());
                for (MessagePart.Tool.Call call : calls) {
                    tracer.toolCall(call.getTool(), call.getArgs());
                }
                // One after another, always. A turn that makes several calls is announced to
                // the session's toolbox first, so it can start its slow independent ones (two
                // questions to the expert, minutes each) side by side - see SessionOptions.
                announce(calls);
                List<ReceivedToolResult> results = ctx.executeTools(calls, false);
                for (ReceivedToolResult result : results) {
                    tracer.toolResult(result.getTool(), result.getOutput());
                }
                Optional<String> done = doneSummary(calls, results);
                if (done.isPresent()) {
                    tracer.done(done.get());
                    keepConversation(ctx);
                    return done.get();
                }
                // Before the next request, not after it: this is the only moment at which the
                // conversation can still be made small enough to be answered.
                compactIfNeeded(ctx);
                boolean tidied = tidyIfAsked(ctx);
                final List<ReceivedToolResult> toSend = withoutRepeatedContent(ctx, calls, results);
                shortenSuperseded(ctx, calls, toSend);
                if (countedNotSent.newMessageDue(tidied, spec.options().tidyAboveTokens())) {
                    response = askInANewMessage(ctx, toSend);
                } else {
                    response = ask(ctx, () -> ctx.sendToolResults(toSend));
                }
            }
        }

        /** Says, in the log and in the session's trace, why a turn without a call ended it. */
        private void endedWithoutACall(String which) {
            String sentence = "Session ended at turn " + turns.get() + ": the model answered "
                + "without calling a tool or handing in (" + which + "). What it said is taken "
                + "as its answer.";
            log.info("Session '{}': {}", spec.role(), sentence);
            tracer.nudge(sentence);
        }

        /** See {@link KoogAgentRuntime#withoutTheTurnThatCalledNothing}. */
        private void forgetTheTurnThatCalledNothing(AIAgentFunctionalContext ctx) {
            try {
                ctx.llm().<Void>writeSession(session -> {
                    Prompt current = session.getPrompt();
                    List<Message> messages = current.getMessages();
                    List<Message> kept = withoutTheTurnThatCalledNothing(messages);
                    if (kept != messages) {
                        session.setPrompt(new Prompt(kept, current.getId(), current.getParams()));
                    }
                    return null;
                });
            } catch (RuntimeException e) {                                 // noqa
                log.debug("Session '{}': the turn without a call stays in the conversation: {}",
                    spec.role(), e.toString());
            }
        }

        /**
         * Cuts what this turn's calls have replaced - an earlier run of the same build or
         * test, an earlier draft of the same file or design - to a short note, before the
         * results are sent (section 102; see {@link Superseded}). Never stops a session.
         *
         * @param toSend this turn's results as they will be sent, one for each of {@code calls}
         */
        private void shortenSuperseded(AIAgentFunctionalContext ctx,
                                       List<MessagePart.Tool.Call> calls,
                                       List<ReceivedToolResult> toSend) {
            try {
                if (calls.size() != toSend.size()) {
                    return;
                }
                List<Superseded.Now> now = new ArrayList<>();
                for (int i = 0; i < calls.size(); i++) {
                    MessagePart.Tool.Call call = calls.get(i);
                    now.add(new Superseded.Now(call.getId(), call.getTool(), call.getArgs(),
                        toSend.get(i).getOutput()));
                }
                Superseded.Result result = ctx.llm().writeSession(session -> {
                    Prompt current = session.getPrompt();
                    Superseded.Result cut = Superseded.shorten(current.getMessages(),
                        spec.tools(), now);
                    if (cut.changed()) {
                        session.setPrompt(new Prompt(cut.messages(), current.getId(),
                            current.getParams()));
                    }
                    return cut;
                });
                if (result.changed()) {
                    promptTokens.set(result.tokensAfter());
                    log.info("Session '{}': at turn {} {} result(s) of an earlier run and {} "
                        + "earlier draft(s) that a later call replaced were cut to a note, "
                        + "{} -> {} tokens (estimated)", spec.role(), turns.get(),
                        result.results(), result.drafts(), result.tokensBefore(),
                        result.tokensAfter());
                }
            } catch (RuntimeException e) {                                 // noqa
                log.debug("Session '{}': replaced results and drafts left whole: {}",
                    spec.role(), e.toString());
            }
        }

        /**
         * What the server counts as input on this session's calls and the session did not send:
         * see {@link CountedNotSent}.
         */
        private final CountedNotSent countedNotSent = new CountedNotSent();

        /**
         * Sends a turn's tool results and begins a new user message after them, in one request
         * (section 94). A server that counts the model's earlier thinking as input on every
         * later call of the same user message stops counting it here; measured on the paid
         * server, see {@link CountedNotSent}. The results go into the conversation exactly as
         * {@code sendToolResults} puts them there, as one message, and the request then adds
         * the user message.
         */
        private Message.Assistant askInANewMessage(AIAgentFunctionalContext ctx,
                                                   List<ReceivedToolResult> results) {
            long counted = countedNotSent.excessTokens();
            ctx.llm().<Void>writeSession(session -> {
                Prompt current = session.getPrompt();
                List<MessagePart.RequestPart> parts = new ArrayList<>();
                for (ReceivedToolResult result : results) {
                    parts.add(result.toMessagePart());
                }
                List<Message> messages = new ArrayList<>(current.getMessages());
                messages.add(new Message.User(parts,
                    ai.koog.prompt.message.RequestMetaInfo.Companion.getEmpty(), null));
                session.setPrompt(new Prompt(messages, current.getId(), current.getParams()));
                return null;
            });
            countedNotSent.beginsNewMessage();
            String sentence = "At turn " + turns.get() + " the model server counted about "
                + counted + " input tokens this session had not sent (what the model thought "
                + "before its earlier calls). The next request begins a new user message, at "
                + "which the server stops counting them.";
            log.info("Session '{}': {}", spec.role(), sentence);
            tracer.nudge(sentence);
            return ask(ctx, () -> ctx.requestLLM(GO_ON));
        }

        /**
         * Tells {@link CountedNotSent} what the call just answered was sent and counted for. The
         * conversation the session now holds ends on that call's answer; what was sent is
         * everything before it.
         */
        private void noteCountedNotSent(AIAgentFunctionalContext ctx, Message.Assistant answer) {
            try {
                Integer reported = answer == null || answer.getMetaInfo() == null ? null
                    : answer.getMetaInfo().getInputTokensCount();
                if (reported == null) {
                    return;
                }
                List<Message> held = ctx.llm().writeSession(
                    session -> session.getPrompt().getMessages());
                int upTo = held.isEmpty() || !(held.get(held.size() - 1) instanceof Message.Assistant)
                    ? held.size() : held.size() - 1;
                boolean wasNoUse = countedNotSent.noUse();
                countedNotSent.called(HistoryTrim.estimateTokens(held.subList(0, upTo)), reported);
                if (!wasNoUse && countedNotSent.noUse()) {
                    log.info("Session '{}': a new user message did not stop the model server "
                        + "counting input this session had not sent (still about {} tokens); "
                        + "no further one is begun for that in this session", spec.role(),
                        countedNotSent.excessTokens());
                }
            } catch (RuntimeException e) {                                 // noqa
                log.debug("Session '{}': counted-not-sent not judged: {}", spec.role(),
                    e.toString());
            }
        }

        /**
         * A call that repeats an earlier one whose answer is still whole in the conversation, and
         * whose answer has not changed, is sent as one line saying so (section 59). Run after the
         * compaction and the tidy of this turn, so "still whole" is judged on what the model will
         * really be sent. The tool was run as always: an answer that differs from the one held is
         * sent in full, so nothing the model does not already hold is ever withheld.
         */
        private List<ReceivedToolResult> withoutRepeatedContent(
                AIAgentFunctionalContext ctx, List<MessagePart.Tool.Call> calls,
                List<ReceivedToolResult> results) {
            try {
                if (calls.size() != results.size()) {
                    return results;
                }
                List<Message> held = ctx.llm().writeSession(
                    session -> session.getPrompt().getMessages());
                List<ReceivedToolResult> out = new java.util.ArrayList<>(results);
                boolean changed = false;
                for (int i = 0; i < results.size(); i++) {
                    ReceivedToolResult result = results.get(i);
                    String stub = HistoryTrim.unchangedRepeat(held, calls.get(i).getTool(),
                        calls.get(i).getArgs(), result.getOutput());
                    if (stub != null) {
                        out.set(i, result.copy(result.getId(), result.getTool(),
                            result.getToolArgs(), result.getToolDescription(), stub,
                            result.getResultKind(), result.getResult(),
                            result.getResultObject(), null));
                        changed = true;
                    }
                }
                return changed ? out : results;
            } catch (RuntimeException e) {                                 // noqa
                log.debug("Session '{}': repeated results left whole: {}", spec.role(),
                    e.toString());
                return results;
            }
        }

        /**
         * Who this session is, for the run's cost record: "worker-3" is role "worker", worker 3;
         * "expert-closing" is the expert. The task and worker come from the session's own identity
         * when it has one.
         */
        private com.swarmcoder.inference.RunMeter.Tag meterTag() {
            String role = spec.role() == null ? "" : spec.role();
            int dash = role.indexOf('-');
            if (dash > 0) {
                role = role.substring(0, dash);
            }
            SessionMeta meta = spec.meta();
            // What the call is for is the session it is a turn of, by the session's own full
            // name ("worker-3", "expert-closing", "expert for architect"); and the candidate
            // it works on, so a call's tokens can be set against what came of the candidate.
            return new com.swarmcoder.inference.RunMeter.Tag(role,
                meta == null || meta.taskId() == null ? null : meta.taskId().toString(),
                meta == null ? null : String.valueOf(meta.workerIndex()),
                "a turn of the session " + (spec.role() == null ? "" : spec.role()),
                meta == null || meta.candidateId() == null ? null
                    : meta.candidateId().toString());
        }

        /**
         * One request to the model, with the two facts an autopsy needs.
         *
         * <p>Before: what was sent and when, so a failure can be classified as this worker's
         * oversized request rather than the endpoint being gone. After: that this endpoint answered,
         * which is the evidence some OTHER worker's stuck request will be judged against. Neither
         * fact survives into the exception, and neither can be recovered afterwards.
         */
        private Message.Assistant ask(AIAgentFunctionalContext ctx, java.util.function.Supplier<Message.Assistant> call) {
            // One of the server's places for as long as this one request is in flight, shared
            // with every other role that talks to the same server (see ServerPlaces). Taken
            // before the clock below starts: waiting for a place is not a slow request, and it
            // is not time spent in a model call.
            com.swarmcoder.inference.ServerPlaces.Place place =
                com.swarmcoder.inference.ServerPlaces.enter(spec.endpoint().baseUrl());
            com.swarmcoder.inference.RunMeter.Open metered;
            Message.Assistant answer;
            try {
                inFlight.set(new EndpointOutage.Attempt(spec.endpoint().baseUrl(),
                    java.time.Instant.now(), promptTokens.get(),
                    spec.endpoint().quirks().workingContextTokens()));
                // The run's cost record: who asked, how long the answer took, and the token counts
                // the server itself reported for THIS call (Koog reads them from the response's
                // "usage").
                metered = com.swarmcoder.inference.RunMeter.begin(meterTag(),
                    spec.endpoint().modelId(), spec.endpoint().baseUrl(),
                    spec.meta() == null || spec.meta().runId() == null ? null
                        : spec.meta().runId().toString());
                synchronized (cancelLock) {
                    if (cancelRequested != null) {
                        metered.end();
                        throw new KillSignal(cancelRequested);
                    }
                    askingThread = Thread.currentThread();
                }
                try {
                    answer = call.get();
                    metered.end();
                    // Told here, before anything below can throw: an answer that arrived was
                    // paid for whether or not the session goes on to use it.
                    tellUsage(metered, answer);
                } catch (RuntimeException | Error noAnswer) {
                    // Timed out, broken off or abandoned: it held a place and gave no answer.
                    metered.failed();
                    throw noAnswer;
                } finally {
                    synchronized (cancelLock) {
                        askingThread = null;
                    }
                }
                if (cancelRequested != null) {
                    Thread.interrupted();
                    throw new KillSignal(cancelRequested);
                }
            } finally {
                place.close();
            }
            EndpointActivity.succeeded(spec.endpoint().baseUrl());
            inFlight.set(null);
            if (hasReasoning(answer)) {
                dropReasoningFromSessionHistory(ctx);
            }
            noteCountedNotSent(ctx, answer);
            return answer;
        }

        /**
         * Hands the run's cost record what the server said this one call used: its prompt and
         * completion tokens (the framework reads them from the response's {@code usage}), what
         * it took from its prompt cache and how big the request was. One call of a session is
         * one record; nothing is added up here except the session's own running sums.
         */
        private void tellUsage(com.swarmcoder.inference.RunMeter.Open metered,
                               Message.Assistant answer) {
            try {
                UsageTap.Seen seen = tap.last();
                metered.cached(seen.cachedPromptTokens());
                metered.reasoning(seen.reasoningTokens());
                // What the thinking is estimated from when the server gives no count of it.
                metered.thought(answer == null ? 0 : reasoningTokensEstimate(answer) * 4);
                metered.request(seen.requestChars(), seen.samePrefixChars());
                if (answer != null && answer.getMetaInfo() != null) {
                    Integer sent = answer.getMetaInfo().getInputTokensCount();
                    Integer generated = answer.getMetaInfo().getOutputTokensCount();
                    metered.usage(sent == null ? -1 : sent, generated == null ? -1 : generated);
                    if (sent != null || generated != null) {
                        usageSeen.set(true);
                        promptSent.addAndGet(sent == null ? 0 : Math.max(0, sent));
                        completionGenerated.addAndGet(
                            generated == null ? 0 : Math.max(0, generated));
                    }
                }
            } finally {
                metered.done();
            }
        }

        /**
         * Thinking is on by default now (see {@code ModelQuirks#thinking}), so most turns from here
         * on carry a {@link MessagePart.Reasoning} part on the assistant's answer — and Koog keeps
         * that answer, reasoning included, as the newest message in its OWN running {@code Prompt}
         * (that is how {@link #compactIfNeeded} can later see every earlier turn). Left alone, the
         * NEXT request replays that reasoning back to the server as {@code reasoning_content} on the
         * assistant message — confirmed by reading Koog 1.2.0's own
         * {@code AbstractOpenAILLMClient.convertPromptToMessages}, which does exactly that.
         *
         * <p>That is pure waste. A real reasoning-capable server never reads back its own PRIOR
         * turn's reasoning trace — the model reasoned, answered, and the reasoning is spent; only
         * the answer and any tool calls matter to the next turn — so every byte of it sent back is
         * extra prompt the server prefills for no effect on the answer. This rewrites it out of
         * Koog's own session history the instant a turn arrives with any, before compaction, before
         * the next request — regardless of whether this model's tool-call history is textual or
         * native, because the leak is in the ASSISTANT message, not in how tool calls are encoded.
         *
         * <p>The local {@code response} variable in {@link #loop} is untouched by this: it is a
         * separate reference to the same turn's content, read once for its text and tool calls
         * before this method ever runs, and Koog's data classes are immutable, so rewriting the
         * SESSION's copy cannot reach back and change it.
         */
        private void dropReasoningFromSessionHistory(AIAgentFunctionalContext ctx) {
            ctx.llm().<Void>writeSession(session -> {
                Prompt current = session.getPrompt();
                List<Message> messages = current.getMessages();
                List<Message> stripped = withoutReasoningOnLastTurn(messages);
                if (stripped != messages) {
                    session.setPrompt(new Prompt(stripped, current.getId(), current.getParams()));
                }
                return null;
            });
        }

        private static boolean hasReasoning(Message.Assistant response) {
            for (MessagePart part : response.getParts()) {
                if (part instanceof MessagePart.Reasoning) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Crude estimate of how much this turn's reasoning cost, in tokens — the same
         * characters-over-four heuristic {@link HistoryTrim} already uses, for the same reason:
         * there is no tokenizer on this side, and this number is for a trace label ("thought N
         * tokens"), not for anything that must be exact. Real reasoning-token accounting already
         * happens for free: the server's own {@code completion_tokens} (what
         * {@code ctx.latestTokenUsage()} reports) includes reasoning tokens as a subset — they are
         * not billed separately — so the worker's token budget already reflects the cost of
         * thinking without this method's help.
         */
        private static int reasoningTokensEstimate(Message.Assistant response) {
            int chars = 0;
            for (MessagePart part : response.getParts()) {
                if (part instanceof MessagePart.Reasoning reasoning) {
                    for (String piece : reasoning.getContent()) {
                        chars += piece == null ? 0 : piece.length();
                    }
                }
            }
            return chars / 4;
        }

        /**
         * Keeps the conversation inside this model's working context.
         *
         * <p>The budget is {@code ModelQuirks.workingContextTokens()} — "the context SwarmCoder will
         * let one session occupy". That setting has existed, been documented and been editable per
         * model on the roles screen the whole time, and nothing ever applied it to a running
         * conversation. This is what applies it. See {@link HistoryTrim} for the scheme, for why it
         * fires rarely and cuts deep rather than trimming a little every turn, and for the measured
         * arithmetic that proves it reclaims more than a turn adds.
         *
         * <p><b>And the case where it cannot.</b> A conversation whose untouchable part — system
         * prompt, opening instruction, the four most recent turns — is already over the high-water
         * mark cannot be made answerable by any amount of compaction. The broken version had no way
         * to notice that, so it compacted uselessly on every turn while the conversation doubled
         * past its budget, and the worker ran to 121 turns and produced nothing. Here it is a kill:
         * {@code BUDGET_EXCEEDED}, whose sentence to the operator is already exactly right — "its
         * conversation outgrew what it is allowed, so it was stopped". Since 2026-09-03 this is the
         * ONLY thing in this loop that throws {@code BUDGET_EXCEEDED} — a worker over its turn cap
         * gets {@link KillReason#TURN_CAP} instead (see {@link #stopOnTurnCap()}), because the two
         * say different things to the operator: this one means the room was too small, that one
         * means the allowance was too small. Conflating them is what let harness run 11 report two
         * workers dead at exactly turn 25 as "its conversation outgrew what it is allowed" when
         * neither one had ever needed a compaction.
         */
        private void compactIfNeeded(AIAgentFunctionalContext ctx) {
            HistoryTrim.Result result = ctx.llm().writeSession(session -> {
                Prompt current = session.getPrompt();
                HistoryTrim.Result trimmed = HistoryTrim.trim(current.getMessages(), budget());
                if (trimmed.changed()) {
                    session.setPrompt(new Prompt(trimmed.messages(), current.getId(), current.getParams()));
                }
                return trimmed;
            });
            promptTokens.set(result.tokensAfter());
            if (!result.changed()) {
                consecutiveCompactions.set(0);
                return;
            }
            int inARow = consecutiveCompactions.incrementAndGet();
            String sentence = "History compacted at turn " + turns.get() + ": " + result.sentence()
                + ". Emptied results were replaced in place by a line naming the tool and its "
                + "arguments; removed turns are named in a block in the worker's opening "
                + "instruction, so it can run any of them again.";
            log.info("Session '{}': {}", spec.role(), sentence);
            // Traced as a NUDGE because that is what it is from the run's point of view: something
            // the orchestrator did to the conversation, which the operator must be able to see at
            // the turn it happened rather than infer from a shrinking token count.
            tracer.nudge(sentence);

            if (result.tokensAfter() > budget().highWaterTokens()) {
                stop("even with every removable turn gone its conversation is "
                    + result.tokensAfter() + " tokens against a limit of "
                    + budget().highWaterTokens() + ". What is left is its task, its opening"
                    + " instruction and its last four turns, and none of that may be dropped, so no"
                    + " further compaction can help it.");
            }
            if (inARow >= MAX_CONSECUTIVE_COMPACTIONS) {
                stop("its history had to be compacted on " + inARow + " turns running and is still "
                    + result.tokensAfter() + " tokens against a limit of "
                    + budget().highWaterTokens() + ". A compaction that cannot buy even one turn"
                    + " will not buy one next turn either: it would keep rewriting the same history"
                    + " to no effect, and each rewrite costs the model server a full re-read of the"
                    + " conversation. There is nothing left to reclaim, so it was stopped.");
            }
        }

        /** Tells the session's toolbox, when it asked to be told, what a multi-call turn calls. */
        private void announce(List<MessagePart.Tool.Call> calls) {
            java.util.function.BiConsumer<String, String> upcoming = spec.options().upcoming();
            if (upcoming == null || calls.size() < 2) {
                return;
            }
            for (MessagePart.Tool.Call call : calls) {
                try {
                    upcoming.accept(call.getTool(), call.getArgs());
                } catch (RuntimeException e) {                             // noqa
                    // An early start is a courtesy: the call still runs in its turn.
                    log.debug("Session '{}': early notice of {} failed: {}", spec.role(),
                        call.getTool(), e.toString());
                }
            }
        }

        /**
         * Replaces old tool results by a digest of themselves when the session asked for that
         * ({@link SessionOptions#tidyAboveTokens}). Never stops the session and never counts as
         * a compaction: it is housekeeping well below the room, not a conversation that no
         * longer fits.
         *
         * @return true when something was shortened on this turn
         */
        private boolean tidyIfAsked(AIAgentFunctionalContext ctx) {
            SessionOptions options = spec.options();
            if (options.tidyAboveTokens() <= 0) {
                return false;
            }
            // The same mark whether or not the server caches the prompt head: input tokens are
            // counted raw (section 67).
            int above = options.tidyAboveTokens();
            int to = options.tidyToTokens();
            HistoryTrim.Result result = ctx.llm().writeSession(session -> {
                Prompt current = session.getPrompt();
                HistoryTrim.Result tidied = HistoryTrim.tidy(current.getMessages(),
                    above, to, options.digestChars(), options.askedAgainTokens());
                if (tidied.changed()) {
                    session.setPrompt(new Prompt(tidied.messages(), current.getId(), current.getParams()));
                }
                return tidied;
            });
            if (result.changed()) {
                promptTokens.set(result.tokensAfter());
                log.info("Session '{}': at turn {} {} old lookup result(s) were shortened (whole "
                    + "to first lines, first lines to one line), {} -> {} tokens (estimated)",
                    spec.role(), turns.get(),
                    result.dropped(), result.tokensBefore(), result.tokensAfter());
            }
            return result.changed();
        }

        /** Ends the worker, with the numbers in the operator's own words on the way out. */
        private void stop(String because) {
            String sentence = "Worker stopped at turn " + turns.get() + ": " + because;
            log.warn("Session '{}': {}", spec.role(), sentence);
            tracer.nudge(sentence);
            throw new KillSignal(KillReason.BUDGET_EXCEEDED);
        }

        /**
         * Ends the worker at its turn cap — {@link KillReason#TURN_CAP}, never
         * {@code BUDGET_EXCEEDED}. This is a worker that kept making requests it fit inside its
         * room; it simply used up the number of turns it was allowed before it finished. That is
         * a different fact from a conversation that no longer fits, and {@link #stop} is what
         * reports that one. See the class-level split in {@link KillReason}.
         */
        private void stopOnTurnCap() {
            int cap = spec.maxTurns();
            String sentence = "Worker stopped at turn " + turns.get() + ": it used all " + cap
                + " of its turns without finishing - the cap is " + cap + "; raise "
                + "budgets.maxToolTurnsPerWorker or split the task.";
            log.warn("Session '{}': {}", spec.role(), sentence);
            tracer.nudge(sentence);
            throw new KillSignal(KillReason.TURN_CAP);
        }

        private HistoryTrim.Budget budget() {
            return HistoryTrim.Budget.forWorkingContext(spec.endpoint().quirks().workingContextTokens());
        }

        /**
         * A tool call the model wrote as plain text, executed as the call it plainly is.
         *
         * <p>Only when this session's history is being textified. That conversion is what teaches
         * the model the text dialect in the first place ({@link TextEmittedToolCalls} has the
         * measurement), and it is also what makes recovery safe: the tool RESULT we send back
         * carries a {@code tool_call_id} that no native assistant tool-call message announced, and
         * every message in a textified history is flattened to text before it reaches the server,
         * so nothing ever sees the mismatch. With native history that same result would arrive as
         * an orphan {@code role: tool} message and the server would reject the turn — and a model
         * on native history is never shown the text dialect, so it has nothing to imitate.
         */
        private List<MessagePart.Tool.Call> recoverTextEmittedCalls(Message.Assistant response) {
            if (!spec.endpoint().quirks().textualToolHistory()) {
                return List.of();
            }
            List<String> toolNames = spec.tools().stream().map(ToolBinding::name).toList();
            List<MessagePart.Tool.Call> recovered =
                TextEmittedToolCalls.parse(textOf(response), toolNames);
            if (!recovered.isEmpty()) {
                log.info("Session '{}' turn {}: the model wrote {} tool call(s) as text instead of "
                    + "calling them ({}); executing them as written", spec.role(), turns.get(),
                    recovered.size(),
                    recovered.stream().map(MessagePart.Tool.Call::getTool).toList());
            }
            return recovered;
        }

        private void guard(int consecutiveTextTurns) {
            // Surface any steering the orchestrator already handed the model, so the console's
            // transcript shows a NUDGE at the turn it happened rather than the operator having
            // to spot it inside a tool result.
            spec.guard().steeringGiven().ifPresent(tracer::nudge);
            spec.guard()
                .check(new TurnGuard.TurnInfo(turns.get(), consecutiveTextTurns, tokensUsed.get()))
                .ifPresent(reason -> {
                    throw new KillSignal(reason);
                });
        }

        private static String stripV1(String baseUrl) {
            String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return url.endsWith("/v1") ? url.substring(0, url.length() - 3) : url;
        }

        private static List<MessagePart.Tool.Call> toolCalls(Message.Assistant response) {
            List<MessagePart.Tool.Call> calls = new ArrayList<>();
            for (MessagePart part : response.getParts()) {
                if (part instanceof MessagePart.Tool.Call call) {
                    calls.add(call);
                }
            }
            return calls;
        }

        private static String textOf(Message.Assistant response) {
            StringBuilder sb = new StringBuilder();
            for (MessagePart part : response.getParts()) {
                if (part instanceof MessagePart.Text text) {
                    sb.append(text.getText());
                }
            }
            return sb.toString();
        }

        private static Optional<String> doneSummary(List<MessagePart.Tool.Call> calls,
                                                    List<ReceivedToolResult> results) {
            for (int i = 0; i < calls.size(); i++) {
                if (DONE_TOOL.equals(calls.get(i).getTool())) {
                    return Optional.of(results.get(i).getOutput());
                }
            }
            return Optional.empty();
        }

        @Override
        public long tokensUsed() {
            return tokensUsed.get();
        }

        @Override
        public void close() {
            // Sessions are one-shot; the Koog agent and HTTP client are released with them.
        }
    }
}
