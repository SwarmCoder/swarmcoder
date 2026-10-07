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
package com.swarmcoder.domain;

/** What happened at one step of an agent session (observability spec, docs/OBSERVABILITY_DESIGN.md). */
public enum TraceEventKind {
    SESSION_OPENED,   // system prompt + task input
    LLM_RESPONSE,     // assistant turn: text and/or tool calls
    TOOL_CALL,        // one tool invocation (label = tool name, payload = arguments)
    TOOL_RESULT,      // the tool's output returned to the model
    NUDGE,            // orchestrator steering message (e.g. "use your tools")
    KILLED,           // early-kill fired (label = KillReason)
    DONE,             // report_done summary
    SESSION_CLOSED,   // terminal event with outcome
    ERROR             // framework/transport failure
}
