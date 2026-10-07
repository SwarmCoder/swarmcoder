# SwarmCoder Console — Observability & Control Design

**Version 1.1 — 2026-07-10 — Status: foundation + v1 UI implemented.
The UI half of this document is SUPERSEDED by `CONSOLE_DESIGN_V2.md` (2026-07-13) —
the "Mission Control" redesign. The data foundation described here (TraceHub,
AgentSessionRecord, EclipseStore, blob store, embedding decision) remains authoritative
and is carried forward unchanged by v2.**

The requirement (author): see in real time a graph of the agents and who is doing what;
select an agent and see the full session detail — prompts, responses, tool calls, results;
persist entire sessions in EclipseStore; post-analyse exactly what happened, with access to
all decisions and information, to drive agent improvement. **Amended 2026-07-10: the GUI
must also do everything the TUI does, so ALL development can take place in the GUI.**

---

## 1. Ruling: the GUI is the complete SwarmCoder Console

The zeroz4j web app is the **primary and complete interface** — observability, analysis,
AND operation: intake, approvals, decision resolution, settings, guidelines curation.
Nothing requires the terminal.

- The session inspector needs long scrollable transcripts, syntax-highlighted diffs,
  side-by-side comparison, and live-updating graphs — Lanterna can render none of these
  without heroics.
- zeroz4j's component set maps almost one-to-one onto the views below (Timeline, ChatBubble,
  CodeMockup, Diff, Stat, Steps, Table, Badge, RadialProgress, Drawer, Toast, TextField,
  Dialog), and its server-push over binary WebSockets is exactly the real-time transport
  needed — no REST polling layer to build.
- **The TUI is demoted to a legacy convenience** (headless/SSH fallback). It is frozen at
  its current feature set — intake, approval, settings — gains nothing new ever, and may be
  retired once the Console covers daily use. All new operator features land in the Console
  only, so the two surfaces never compete.

## 2. What is already built (the data foundation, 2026-07-10)

Everything below exists and is covered by `SwarmEngineFakeVllmTest`:

- **`TraceEvent` / `AgentSessionRecord`** (sc-domain): every step of every agent session —
  SESSION_OPENED (system prompt + input), LLM_RESPONSE, TOOL_CALL (name + args),
  TOOL_RESULT, NUDGE, KILLED, DONE, SESSION_CLOSED — with seq, timestamp, cumulative token
  usage, and join keys to run / task / candidate / worker index.
- **`TraceHub`** (sc-runtime): the observability spine. `KoogAgentRuntime` emits every step;
  the hub fans out to registered listeners in real time and accumulates the complete record.
  Listener failures never break the swarm. Payloads above 16K chars go to the blob store
  (`payloadRef`), inline text is truncated with a pointer.
- **EclipseStore persistence**: on session end the complete `AgentSessionRecord` is appended
  to `StoreRoot.agentSessions()` (Lazy-wrapped; schema v2 with a class-evolution guard).
  Post-analysis therefore has: session traces + archived candidates (diff, verification,
  judge verdict, sampling config) + runs/tasks/decisions — the full causal chain from prompt
  to selection.
- **Live surface**: `TraceHub.addListener(...)` + `activeSessions()` snapshots — the exact
  API the observer server subscribes to.

JVM note: EclipseStore needs `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED` plus
`--add-opens java.base/java.{util,lang,time}=ALL-UNNAMED` (set for surefire in the parent
POM; must be in any launcher script).

## 3. Architecture

**Hard constraint:** EclipseStore is single-process. The observer backend therefore runs
**inside the orchestrator JVM** — not as a separate WildFly deployment. The zeroz4j example
ships as a WAR for WildFly; for SwarmCoder we embed instead:

```
┌────────────────────────── SwarmCoder JVM ──────────────────────────┐
│  swarm engine ── TraceHub ──► ObserverBridge (listener)            │
│  workflows      ArtifactStore ◄─── reads ────┐                     │
│                                              │                     │
│  sc-observer-server: embedded servlet+websocket container          │
│   (Tomcat embed or Undertow + Weld SE for CDI)                     │
│   • zeroz4j-server-backend @ServerEndpoint /wasm-rmi               │
│   • @RmiService beans (ObserverService)                            │
│   • serves the compiled wasm client statics                        │
└──────────────────────────────┬─────────────────────────────────────┘
                               │ binary RMI over WebSocket (+ push)
                     Browser: sc-observer-ui (TeaVM WasmGC client)
```

New Maven modules:

| Module | Contents |
|---|---|
| `sc-observer-api` | `@BinaryModel` DTOs + `@RmiService` interfaces (shared client/server) |
| `sc-observer-server` | Embedded container bootstrap, service impls, TraceHub bridge |
| `sc-observer-ui` | TeaVM WasmGC client (views below); built under a Maven profile so the main reactor doesn't require the TeaVM toolchain |

**Spike required first (O0):** validate zeroz4j-server-backend under an embedded container +
Weld SE (its engine resolves services via `jakarta.enterprise.inject.spi.CDI`). If CDI-SE
embedding fights back, fallback: a tiny programmatic service registry patch in zeroz4j
(author owns the framework) — cleaner than dragging a full app server into the orchestrator.

## 4. RMI service surface (`sc-observer-api`)

```java
@RmiService
public interface ObserverService {
    List<RunSummaryDto> listRuns();                          // post-analysis entry
    RunGraphDto runGraph(UUID runId);                        // tasks → candidates → sessions, states
    List<SessionSummaryDto> activeSessions();                // live board initial state
    SessionDetailDto session(UUID sessionId);                // header + all events (paged)
    List<TraceEventDto> sessionEvents(UUID sessionId, long fromSeq, int max);
    String blobText(String ref);                             // full payload on demand
    CandidateDetailDto candidate(UUID candidateId);          // diff, verification, judge, sampling
    InsightsDto insights();                                  // aggregates for the eval loop
}

@RmiService
public interface ControlService {                            // TUI parity — full operation
    UUID submitIntake(String goal, String workflowKind);     // starts a run (WorkflowEngine.advance)
    void approveRun(UUID runId);                             // -> DELIVERED
    void rejectRun(UUID runId);                              // -> ABORTED
    List<DecisionDto> decisions();                           // decision queue
    void resolveDecision(UUID decisionId, String response);  // incl. BUDGET_EXTENSION grants
    SettingsDto settings();                                  // roles/endpoints/budgets/swarm
    void saveSettings(SettingsDto settings);                 // same yaml the TUI edits today
    List<GuidelineDto> guidelines();                         // browse (M4: promote/retire/edit)
}
```

Push topics (server → client):
- `swarm-live` — compact deltas: session started (header), trace event (seq, kind, label,
  first ~200 chars), session ended (outcome). Full payloads always fetched on demand.
- `run-live` — run/stage transitions, task state changes, leaderboard deltas.

## 5. Views

**5.1 Swarm Board (live — the "who is doing what" graph).**
Run header as `Steps` (INTAKE → … → APPROVAL). Below, one `Card` per task showing its
`swarm/<task>` state, containing a row of worker tiles: `Avatar` + persona + model family
`Badge`, live state (`Loading` spinner while RUNNING, colored `Badge` for
SURVIVED/KILLED(reason)/FAILED/SELECTED), live token `Stat` and turn counter, last tool
called. Cloud-budget `RadialProgress` (CloudGate.used vs cap) in the navbar. Clicking a
tile opens the Session Inspector in a `Drawer`. `Toast` on kills and selections.

**5.2 Session Inspector (live tail AND post-mortem — same view).**
Header: role, model profile, temperature, task link, outcome, turns, tokens.
Body: `Timeline` of TraceEvents —
- SESSION_OPENED → `Collapse` with the full system prompt + input,
- LLM_RESPONSE → `ChatBubble` (assistant),
- TOOL_CALL → tool name badge + args in `CodeMockup`; apply_diff args rendered with the
  `Diff` component,
- TOOL_RESULT → `CodeMockup` (exit codes highlighted), truncated payloads with a
  "load full" action hitting `blobText(ref)`,
- NUDGE/KILLED/DONE → `Alert` rows.
Live sessions auto-follow via `swarm-live` push; ended sessions render identically from the
store. This one view is both the real-time microscope and the post-analysis reader.

**5.3 Run Explorer (post-analysis).**
`Table` of runs → run graph → per-task candidate leaderboard (verification summary, judge
score + rationale, cluster size, diff size) → candidate page: full diff (`Diff`),
VerificationReport (acceptance/existing failures with traces), judge verdict, sampling
config, link to its session trace. Side-by-side candidate comparison (two inspectors).
Decision queue browser included — every parked decision with its brief.

**5.4 Insights (the improvement loop).**
Aggregates over archived candidates + sessions — the spec §8.9 eval dataset made visible:
survival and selection rate by model family / temperature band / persona; kill-reason
distribution; tokens and turns per outcome; verification failure clusters; judge score vs
verification agreement. Start as `Stat` tiles + tables; charts later.

**5.5 Operate (TUI parity — development happens here).**
- **Intake**: goal `TextArea` + workflow kind `Select` + submit — starts the run and jumps
  straight to its Swarm Board. Recent goals list for re-runs.
- **Approvals**: runs parked at APPROVAL, each with the integrated diff (`Diff`),
  verification summary and judge rationale inline; Approve / Reject buttons (`Dialog`
  confirm). This supersedes the TUI review screen with strictly more context.
- **Decision queue**: every parked `Decision` (BLOCKED_TASK, BUDGET_EXTENSION,
  GUIDELINE_REVIEW) rendered with its brief; resolve with free-text or one-click grant.
- **Settings**: the same role/endpoint/model/budget/swarm config the TUI edits, as a form
  (`TextField`/`Select` + `Binder` validation), persisted through the existing
  `ConfigLoader`. Live `Badge` showing which endpoints respond.
- **Guidelines browser** (M4): list, promote PROPOSED → ACTIVE, retire, edit body.

## 6. Delivery phases

- **O0 (spike):** ~~embedded container + zeroz4j engine handshake~~ — **DONE, verdict GO
  (2026-07-10)**. `sc-console` embeds **TomEE 10.1.5 embedded** (not Tomcat+Weld: TomEE is
  the runtime zeroz4j apps already target, and one dependency covers servlet + WebSocket +
  CDI). `ConsoleServer.start(port)` boots the zeroz4j `/wasm-rmi` endpoint in-process;
  `ConsoleServerSpikeTest` proves a WebSocket RMI connect ("Client connected: anonymous").
  Findings: TomEE 9.x cannot start on JDK 24+ (installs a removed java.security.Policy) —
  10.x required; two null-principal NPEs on anonymous handshakes were fixed UPSTREAM in
  zeroz4j (`RmiEndpointConfigurator.modifyHandshake`, `WasmRmiServerEngine.onOpen`) and
  installed to the local repo — commit those in the zeroz4j project. Remaining O0 tail:
  serve the TeaVM wasm client statics (part of O1).
- **O1:** Swarm Board live (push wiring, run graph, session tiles) **+ Operate essentials
  (intake, approvals, decision queue)** — from this point all development can run through
  the Console. **Server half DONE (2026-07-10):** `sc-console-api` (binary DTOs +
  `ObserverService`/`ControlService` contracts), server impls over the live store and
  TraceHub, `LivePushBridge` (TraceHub → `swarm-sessions`/`swarm-events` push topics),
  awaited control mutations (a click is durable before the read-back), and app wiring —
  set `consolePort` in config and `Main` starts the Console inside the orchestrator;
  intake/approve/reject are now single-path methods on `DependencyGraph` shared by TUI and
  Console. **Client half DONE too (2026-07-10): O1 COMPLETE.** `sc-console-ui` (TeaVM 0.15
  JAVASCRIPT target, zeroz4j ui-components + signals) ships `ConsoleApp` with three views —
  Swarm Board (live session tiles + inline step inspector fed by both push topics), Operate
  (intake, run list, approve/reject), Decisions (browse + resolve) — compiled straight into
  `META-INF/resources` and served by `ConsoleStaticServlet`. `ConsoleAutoLogin` seeds
  zeroz4j's dev principal per session (localhost single-operator console — design §7), so
  there is no login page and the RMI handshake is authenticated as "operator/admin".
  Proven end-to-end by `ConsoleBrowserSmokeTest`: headless Chromium loads the page, the
  TeaVM client connects the binary RMI WebSocket, authenticates, and mounts all views with
  zero page errors. It runs in an ordinary build wherever Playwright's Chromium is
  installed — see `docs/TESTING.md`.
- **O2:** Session Inspector (live tail + historic), blob fetch, Settings form.
- **O3:** Run Explorer + Insights + guidelines browser. TUI enters legacy freeze.

## 7. Notes

- Trace everything policy: cloud-role calls (judge, planner) currently bypass AgentRuntime
  and are therefore not session-traced; when they move behind `AgentRuntime` (R4c) they get
  tracing for free. Until then the judge verdict is visible on the candidate instead.
- Volume: a 30-turn worker session ≈ 60–100 events, N=8 swarm ≈ <1K events per task — well
  within a single WebSocket and EclipseStore's comfort zone. Blob offload keeps the hot
  object graph small (spec §4.3 discipline).
- Security: bind the observer to localhost by default; auth is out of scope until the
  observer leaves the workstation.
