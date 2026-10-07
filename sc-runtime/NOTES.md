# sc-runtime — open notes

- **2026-09-04: Koog bumped to 1.2.0, thinking now on by default, native tool history for one
  shape.** Superseding the three notes directly below that this contradicts:
  - Koog 1.2.0 fixed the double-encoding defect (see "Why native tool history does not work here,
    precisely" below) — verified by reading `AbstractOpenAILLMClient.convertPromptToMessages` at
    that tag: `OpenAIFunction(it.tool, it.args)`, `args` sent verbatim, with a comment in Koog's own
    source noting the earlier double-encoding bug and that fixing it. A live probe against
    `qwen3.8-27b` with native history round-tripped a 3-turn tool-calling conversation clean — no
    400, `tool_calls` arriving natively both directions. `ModelShapes."qwen38-flash-next-125b"` is
    the one shape with `textualToolHistory=false` as a result; every other shape still defaults to
    textual until it, too, is measured. `TextEmittedToolCallsTest`/`recoverTextEmittedCalls` are
    unchanged and still fire for textual sessions.
  - `ModelQuirks.DEFAULTS.thinking` and the `qwen36-27b` / `qwen38-flash-next-125b` / `generic-openai`
    shapes flipped to thinking-ON. The `dev/experiment/plain-loop` control experiment (branch
    `experiment/plain-loop-ceiling`) ran the same plain-Java task on `qwen3.8-27b` thinking-on vs
    thinking-off: thinking-on finished green in 10 turns, thinking-off could not fix one
    NullPointerException in 110 shell commands. The "20s vs 4.5s per call" cost below is real but
    was never weighed against this.
  - Koog 1.2.0 added `MessagePart.Reasoning` (a `ResponsePart`) and `AbstractOpenAILLMClient`
    REPLAYS it back to the server as `reasoning_content` on every request that includes that turn —
    confirmed by reading the same source file. `KoogAgentRuntime.withoutReasoningOnLastTurn` +
    `KoogSession.dropReasoningFromSessionHistory` strip it from the session's own running `Prompt`
    the instant a turn arrives with any (native or textual history alike — the leak is in the
    assistant message, not in how tool calls are encoded). Proven directly, with no live model, by
    `KoogAgentRuntimeReasoningTest`. `reasoning_content`'s token cost is already inside the server's
    own `completion_tokens` (see `CompletionTokensDetails.reasoningTokens`), so
    `ctx.latestTokenUsage()` needed no change; a crude chars/4 estimate is traced per turn
    (`TraceHub.SessionTracer.llmResponse`'s 4-arg overload) so a turn's own thinking cost is visible
    as `"[thought ~N tokens]"` in its `LLM_RESPONSE` event, without a schema change to the persisted
    `TraceEvent`.
  - Still unverified, same as before: whether Koog's OpenAI client flattens `LLMParams.additionalProperties`
    into the request body (would let the worker path send `chat_template_kwargs` directly instead of
    the in-prompt `/no_think` directive). Thinking defaulting on makes this far less urgent — the
    directive is now the OPT-OUT path, exercised only by a role that sets `thinking: false`.

- **Qwen occasionally emits tool calls with MISSING arguments** (observed 2026-07-13, ~6 per
  ~150-call swarm run): Koog's `ToolFromCallable` then throws
  `IllegalArgumentException: No argument provided for a required parameter` and logs an
  ERROR (`FunctionalAIAgent - Tool 'exec' failed to execute`). This is **non-fatal** — Koog
  returns the failure to the model as the tool result and the model retries — so it costs a
  wasted turn plus log noise, nothing more. A real fix means pre-validating call args
  against the binding's parameter names before `ctx.executeTools(...)` and synthesizing an
  error result for malformed calls; only worth building if the malformation rate climbs
  (watch for the ERROR signature above becoming frequent in run logs).

- **Thinking mode is off by default, and HOW it is turned off is now per model.** Verified live
  against Qwen 3.6: 20s -> 4.5s per call, reasoning_len 0. Two mechanisms because the two client
  paths differ: `VllmClient` (role calls) sends `chat_template_kwargs:{<name>:false}` in the
  request body; `KoogAgentRuntime` (workers) appends an in-prompt directive because Koog's OpenAI
  client does not expose `chat_template_kwargs`. Both the argument NAME and the directive TEXT
  used to be the literals `enable_thinking` and `/no_think` — Qwen conventions that mean nothing
  to another model — and are now `ModelQuirks.thinkingKwarg` / `ModelQuirks.noThinkDirective`,
  either of which can be absent for a model that has no such mechanism.
  **Koog 1.0 update, UNVERIFIED:** `LLMParams` now has an `additionalProperties` map and
  `OpenAIChatCompletionRequest` carries a field of that name. If it is flattened into the request
  body rather than nested under `additionalProperties`, the worker path could send
  `chat_template_kwargs` too and drop the directive. Nobody has checked which it does, and it
  cannot be checked without a server. Measure before switching.

- **Tool history is textified, not native — but per model now, not for everyone.**
  `KoogAgentRuntime` configures `MissingToolsConversionStrategy.All(ToolCallDescriber.JSON)` when
  `ModelQuirks.textualToolHistory` is set, which is the default and what every shipped shape says.
  Why: vLLM chat templates (Qwen's in particular) iterate tool-call `arguments` as a mapping while
  OpenAI-spec clients send a JSON *string* — the second turn dies with 400 "Can only get item
  pairs from a mapping". With `All`, tool calls/results in the conversation history go as
  plain-text JSON; tool *definitions* still go natively so the model keeps emitting real tool
  calls. Side benefit: the textified history is stable for prefix caching.
  **Do not turn this off for a model on a hunch.** The point of making it a setting was that a
  model which does not need it should not be forced into it, not that the default is doubtful. If
  a chat template is ever fixed to accept string arguments, measure before switching back.

- **Workers now send a generation cap.** They used to send none: only `VllmClient` (role calls)
  bounded output, so a rambling worker turn could stream toward the served context ceiling with
  nothing to stop it. The cap goes on through Koog's `Prompt.withParams(LLMParams)` —
  all eight `LLMParams` arguments spelled out, because Kotlin default arguments are not visible
  from Java, the same trap as `OpenAIClientSettings`. `LLMParams` has **no seed field**, which is
  why the per-worker seed in `SamplingConfig` is recorded and never sent.

- **Context Ledger: post-run learning is DONE; in-session rollback still deferred.**
  Implemented (sc-knowledge, 2026-07-11): `HistoryRag` (search_history over completed session
  transcripts), `GuidelineExtractor` (utility model → PROPOSED guideline files), and
  `ContextLedger` wiring both on run completion. The **debug-fork quarantine (§12.2)** is
  substantially realized by the swarm's REPAIR ROUND — a fresh worker context seeded from a
  compact failure brief instead of the failed session's polluted transcript is exactly the
  spec's "fork before debugging, discard the thrash, keep only a FixSummary" at the swarm
  level. What remains genuinely deferred is INTRA-session `checkpoint / rollback / compress`
  on `AgentRuntime.AgentSession` (still throw): these need Koog snapshot integration —
  `agents-features-snapshot` for checkpoints, `AIAgentFunctionalContext.compressHistory`
  (already a blocking Java method) for compaction. Only worthwhile for very long single
  sessions that approach the token budget; the swarm's short worker sessions rarely do.
- **Sessions are one-shot.** `KoogAgentRuntime` builds one Koog `FunctionalAIAgent` per
  `run(...)`. Multi-run sessions with retained history come with the Context Ledger.
- **Kotlin default arguments.** `OpenAIClientSettings` has no `@JvmOverloads`; the full
  seven-argument constructor is spelled out in `KoogAgentRuntime`. If a Koog upgrade changes
  that constructor, the compiler will catch it here and nowhere else.
- **Malformed-tool-call detection** is approximated as "assistant turns without any tool
  call": vLLM's tool parser plus Koog's parsing hide raw malformed calls from us. If the
  archived-candidate data later shows kills misattributed to TOOLCALL_MALFORMED, revisit by
  inspecting `Message.Assistant.rawResponse`.
- **Token usage** comes from `AIAgentFunctionalContext.latestTokenUsage()` once per turn;
  treat it as best-effort until verified against vLLM's usage fields in a live run.

- **Textual tool history is a two-way dialect, and only half of it existed.** With
  `quirks.textualToolHistory` (the shipped default), Koog's
  `MissingToolsConversionStrategy.All(ToolCallDescriber.JSON)` rewrites every earlier tool call
  and tool result in the prompt into a plain-text line
  `{"tool_call_id":…,"tool_name":…,"tool_args":{…}}`. Tool *definitions* still go natively, so
  the model does call tools — but from turn 3 or so it starts writing that same line as ordinary
  TEXT, id and all, because the conversation is now full of examples of exactly that. The loop
  saw no tool call, counted two text turns and ended the session. Measured 2026-08-29: 27 of 27
  workers in one run finished with zero files written, every one signing off with a text-shaped
  tool call. `TextEmittedToolCalls` + `KoogSession.recoverTextEmittedCalls` are the missing half:
  a text turn carrying one of those lines for a REGISTERED tool is executed as the call it is.
  Recovery is deliberately confined to textified sessions — the tool result it produces names a
  `tool_call_id` that no native assistant message announced, which is invisible once the whole
  history is flattened to text and a 400 from the server if it is not.

- **Why native tool history does not work here, precisely.** Koog 1.0.0's
  `AbstractOpenAILLMClient.convertPromptToMessages` writes an assistant tool call as
  `OpenAIFunction(tool, Json.encodeToString(it.args))`, and `MessagePart.Tool.Call.args` is
  ALREADY a JSON string — so the arguments go on the wire double-encoded, as
  `"\"{\\\"command\\\":…}\""`. SGLang rejects that turn with 400 *"Assistant tool call
  function.arguments must be a JSON object"*, and a Qwen chat template rejects it with *"Can only
  get item pairs from a mapping"* — the same defect under two error messages. It is not
  overridable from here: `convertPromptToMessages` is `protected` but not `open`. Until Koog
  fixes it, textual history stays on, and therefore so does text-call recovery.
