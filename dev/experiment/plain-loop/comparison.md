# Plain harness vs. a SwarmCoder worker

Same model, same machine, same task. Everything below about SwarmCoder is read from its
source, with the file and the constant named, so the two columns can be compared line by
line.

| | Plain loop (this experiment) | SwarmCoder worker |
|---|---|---|
| **Tools** | 5: `list_files`, `read_file`, `write_file`, `run`, `done` | 6: `read`, `write_file`, `apply_diff`, `exec`, `lookup_api`, `report_done` (`WorkerToolbox.bindings()`) |
| **Tool calls on the wire** | Native OpenAI `tools` + `tool_calls` + `role: tool`. Verified working against this endpoint in both directions. | Definitions native, **history rewritten as plain text JSON** — `MissingToolsConversionStrategy.All(ToolCallDescriber.JSON)` (`KoogAgentRuntime.buildAgent()`), on by default (`ModelQuirks.textualToolHistory = true`) |
| **Why text history** | n/a | A Koog/Qwen template clash: Qwen's template iterates tool-call arguments as a mapping, OpenAI clients send a string, and the second turn 400s with "Can only get item pairs from a mapping" |
| **Cost of text history** | n/a | The model started *imitating* the text format as ordinary prose instead of calling tools. Measured incident: **27 of 27 workers finished with zero files written.** A recovery parser (`TextEmittedToolCalls.parse()`) now scrapes those back out |
| **Thinking** | **ON** in runs 1 and the aborted attempt (this server's default); **OFF** in run 2 via `chat_template_kwargs: {enable_thinking: false}` | **OFF** — `ModelQuirks.thinking = false`, enforced with a `/no_think` line appended to the prompt |
| **Reading is capped** | No | Yes. Read tools stop returning real answers from the 17th investigation call until the worker writes something (`READ_PAUSE_TEXT`, `WorkerToolbox`) |
| **Nudges** | None | At 8 investigation calls with no file changed, and again at 16 (`INVESTIGATION_TOOL_CALLS_BEFORE_NUDGE = 8`, `..._SECOND_NUDGE = 16`, `EarlyKillEnforcer`) |
| **Kills** | None | 24 investigation calls with no write → `NO_PROGRESS`; 6 fruitless calls in a row → `NO_PROGRESS`; 3 non-discriminating doc lookups → `DOCS_DEAD_END`; 2 malformed tool calls → `TOOLCALL_MALFORMED`; 2 lethal path violations → `WRITESET_VIOLATION` |
| **Turn cap** | 200 (never reached) | 120 (`TurnAllowance.BUILT_IN_MAX_TOOL_TURNS`) |
| **Wall-clock cap** | 3 hours (reached, run 1) | none as such; bounded by turns and tokens |
| **Where writes may land** | Anywhere inside the cloned repository (machine safety only) | Mechanical path policy: `.git/` and `.swarmcoder/` always refused, operator-protected paths and the acceptance-test directory refused, trust-kernel files locked; writes outside the task's write set are allowed but recorded for the judge (`PathPolicy`) |
| **Working context** | The model's full 262,144 tokens. No compaction at all | **51,200 tokens** — derived at startup from the server's KV-cache pool ÷ concurrency × 90% (`ServerCapabilities.derivedWorkingContextTokens()`), not a hardcoded number |
| **Compaction** | None | Fires above 75% of the window, trims back to 40% (`HIGH_WATER_PERCENT = 75`, `LOW_WATER_PERCENT = 40`, `HistoryTrim`), last 8 messages protected; 3 consecutive compactions without progress → `BUDGET_EXCEEDED` |
| **System prompt** | 5 short lines: where the repo is, two Maven commands, where the docs are, "read before guessing", "call done when green" | Five ordered segments — system role, workflow rules, project constraints, task instructions, knowledge brief — plus a per-worker persona (`PromptBundle`). Size is logged per dispatch as `prefixTokens=`; there is no hardcoded 6-7k constant in the source |
| **Whole first prompt** | **2,662 tokens**, including the story and both requirement documents in full | The prefix alone is the reported 6-7k, before the task |
| **Documentation** | Read the files directly with the same `read_file` tool | A separate `lookup_api` search tool over reference docs |
| **Temperature** | 0.2 fixed | Spread across the wave from `tempMin` to `tempMax` per worker (`SwarmDispatcher.dispatch()`) |
| **max output tokens** | 8,192 | 32,768 (`ModelQuirks.maxOutputTokens`) |

## The three things that actually differ

1. **Tool-call encoding.** SwarmCoder cannot use native tool calls because of a framework
   template clash, and the text workaround has already caused a total failure (27 of 27
   workers writing nothing). The plain loop uses native calls and they work at this endpoint,
   first try, in both directions. This is a real defect in SwarmCoder's stack — but see the
   result: fixing it does not make the model finish the task.

2. **Context.** 51,200 tokens with compaction versus 262,144 with none. The plain loop's
   prompt passed SwarmCoder's entire window at turn 17 and reached 146,268 tokens — nearly
   three times the window — without ever writing a file. Compaction is not what stops
   SwarmCoder's workers producing code.

3. **The scaffold.** Nudges at 8 and 16, a read pause at 17, a kill at 24. In the plain loop
   the model made **165 tool calls over 163 turns and 3 hours and wrote nothing.** Every one
   of those thresholds was designed for exactly this behaviour, and the plain run shows the
   behaviour is the model's, not the harness's.
