# SwarmCoder Console v2 — "Mission Control"
## Requirements & Design Document

> **⚠ PARTIALLY SUPERSEDED (2026-07-28).** The navigation/shell design in this document — flat
> views, tabs, the runs rail, the Swarm Board as a destination, the Approval Center as a queue —
> is replaced by **`docs/CONSOLE_UX_V3.md`** (the pipeline model). The data foundation (TraceHub,
> AgentSessionRecord, EclipseStore, blob store, live push) and the embedding decision remain
> authoritative here. Where the two conflict on anything the operator sees, **V3 wins.** Kept as a
> historical record of the v2 design and its data-layer decisions.

**Version 2.0 — 2026-07-13**
**Status: approved direction (author), next development phase — see supersession banner above**
**Supersedes the UI half of `OBSERVABILITY_DESIGN.md` v1.1. The data foundation
(TraceHub, AgentSessionRecord, EclipseStore persistence, blob store, HistoryRag) and the
embedding decision (Console lives in the orchestrator JVM) are unchanged and carried
forward. The v1 Console (six flat views + tabs) is the scaffold this replaces.**

---

## 1. Vision

The v1 Console proves the plumbing: RMI over WebSocket, live push, TeaVM client, six flat
views. It looks like a debug page. v2 is the product: a **multi-project agentic IDE
surface** in the spirit of Google Antigravity's agent manager — a place where the operator
*lives* while swarms work, not a page they refresh to check on them.

Three ideas organize everything:

1. **Projects are the workspace.** A persistent left rail lists every project; selecting
   one opens its world (chats, runs, observability, settings). Nothing outside the rail is
   global except shared settings and system health.
2. **The chat is the steering wheel.** Work is initiated, interviewed, approved, and
   reviewed in chat sessions with the coder — multiple concurrent chats per project, each
   optionally bound to a run. The TUI's intake form dies here.
3. **Observability is a living graph, not a list.** Every run renders as a real node-and-
   edge graph — stages, tasks, worker swarms, candidates — animating live as the swarm
   executes, and every node drills down through layers until you are reading the exact
   prompt bytes, tool calls, and tool results. The same graph replays historical runs.
   **Everything updates through signals. There is no refresh button anywhere in v2.**

### Non-goals

- Not a code editor. Diffs are reviewed, not edited, in the Console (the repo checkout is
  the editing surface, as with guidelines).
- Not multi-user. Single operator, localhost, the existing dev-principal auto-login.
  (The layout should not *preclude* auth later; it just isn't built.)
- No REST/JSON API. Everything stays zeroz4j RMI + push, hand-packed binary DTOs.

---

## 2. Information architecture & layout

```
┌──┬───────────────┬──────────────────────────────────────────┬─────────────┐
│  │               │  ⌘K  Search / Command Palette      🌙 ⚙  │             │
│P │  PROJECT      ├──────────────────────────────────────────┤  INSPECTOR  │
│R │  SIDEBAR      │                                          │  (contextual│
│O │               │   TABBED WORKSPACE                       │   drill-down│
│J │  ▸ Chats      │                                          │   panel,    │
│E │    #fix-auth  │   [Chat #fix-auth] [Run 9e71… graph] [+] │   collapsible)
│C │    #refactor  │                                          │             │
│T │  ▸ Runs       │        ...active tab content...          │             │
│  │    ● 9e71 ✓   │                                          │             │
│R │    ● 41ac ⏳  │                                          │             │
│A │  ▸ Board      │                                          │             │
│I │  ▸ Insights   │                                          │             │
│L │  ▸ Guidelines │                                          │             │
│  │  ▸ Approvals② │                                          │             │
├──┤               │                                          │             │
│⚙ │               ├──────────────────────────────────────────┴─────────────┤
│❤ │               │ STATUS BAR: Spark ● 16/16 seqs · Docker ● · ▓▓▓░ 1.2M/3M│
└──┴───────────────┴──────────────────────────────────────────┴─────────────┘
```

Five regions, all resizable via the new `SplitPane` component:

- **R1 Project Rail** (56px icons / 240px expanded): one entry per project (avatar =
  initials + deterministic color from project id), a per-project **gear icon on hover**
  (opens Project Settings dialog, req. #1), a **＋ New Project** button, and pinned at the
  bottom: **global settings gear** (req. #2) and a **system-health heart** (Spark/Docker/
  budget at a glance; click → Health popover).
- **R2 Project Sidebar** (collapsible): sections for Chats (with unread dots), Runs (live
  state badges), Board, Insights, Guidelines, Approvals (badge = pending decision count).
- **R3 Tabbed Workspace**: chat sessions, run graphs, insight dashboards open as tabs
  (closable, reorderable, with overflow menu). Tab icons carry live state (pulsing dot on
  a running run's tab, badge on a chat with a pending question).
- **R4 Inspector**: contextual right panel. Selecting a graph node, a chat tool-card, or a
  table row populates it. It is a stack — drilling deeper pushes a level, breadcrumbs pop
  back (run → task → candidate → session → turn → payload).
- **R5 Status Bar**: Spark connectivity + active sequences, Docker sandbox status, cloud
  budget burn bar for the selected project's active run, decision-queue count, WS latency.

Keyboard: `⌘K` command palette, `⌘1..9` rail projects, `⌘T` new chat, `⌘W` close tab,
`g` then `r/b/i/g/a` sidebar sections, `Esc` pops inspector level. All shortcuts on `Kbd`
hints in tooltips.

### 2.1 As built (2026-07-25)

`MainView` renders the shell as one flex column — `topBar`, `body`, `statusBar` — where
`body` is a flex row of **ProjectRail** · **sidebar** (`w-48`: Chats, Runs, Requirements,
Swarm Board, Approvals, Insights, Prompt Lab, Guidelines, Knowledge, Components) ·
`Resizer` · **Workspace** (tabs) · `Resizer` · **Inspector** (`w-96`, the breadcrumbed
drill-down stack, hidden until something is selected and toggled from the top bar).

Two deviations from the sketch above, both deliberate: the dividers are zeroz4j's
`Resizer` rather than `SplitPane`, and `⌘K` opens `CommandPalette` as an overlay instead of
a search field living in the top bar (the bar carries the logo and the inspector toggle).
The status bar is a connection chip plus `HealthStrip`.

### 2.2 Backlog panel — built (2026-07-25)

A **collapsible Backlog panel between the sidebar and the Workspace**, with its own
`Resizer` and persisted width + collapsed state. The design is owned by
[`REQUIREMENTS_AND_BACKLOG_DESIGN.md`](REQUIREMENTS_AND_BACKLOG_DESIGN.md) §8.1 — that
document is authoritative; this section only records the layout consequence:

```
┌─rail─┬─nav─┬─BACKLOG────────┬─workspace──────┬─inspector─┐
│      │     │ ▾ It.3 MVP     │                │  Story S3 │
│      │     │  ● S3 Guest  ✓ │  chat / graph  │  delivers │
│      │     │    └ T1      ✓ │                │  R7:C1,C2 │
│      │     │    └ T2      ◐ │                │  gates R12│
│      │     │  ○ S4 Cards    │                │           │
│      │     │ ▾ Backlog (12) │                │           │
│      │     │ ▾ Triage (2) ! │                │           │
└──────┴─────┴────────────────┴────────────────┴───────────┘
```

Region order becomes rail · sidebar · `Resizer` · **backlog** · `Resizer` · workspace ·
`Resizer` · inspector.

- Tree is **Iteration → Story → Task** with live state badges and an NFR-gate indicator on
  stories; groups are active iteration(s), unscheduled backlog, **Triage** (discovered
  items awaiting promotion, with a count badge) and a collapsed Done section.
- Clicking any node drills into the **existing** Inspector stack — no new drill-down
  mechanism is introduced.
- A full-width **Backlog board** tab opens in the Workspace for planning sessions, and
  **Start session** on a `READY` story creates a run bound to it.

`BacklogPanel` and `BacklogBoard` are as sketched, with three differences worth recording.
The panel is shown or hidden from a toggle in the top bar (persisted to `localStorage` under
`console.backlog`) rather than collapsing in place, so it sits alongside the inspector toggle
that already lives there. Its groups are **Triage** (every `DRAFT` story, first and badged
with a count), then one group per iteration, then **Unscheduled**, then **Done** — `DONE` and
`CANCELLED` stories are excluded from the iteration groups so a long-running iteration does
not bury the work in flight. And it renders its own rows of `Div`s instead of composing
`TreeView`/`StatusDot`; those two generics were never built (§3.2), and the panel is the only
consumer they would have had.

Both surfaces read the same `BacklogSignals.CURRENT` (§4.4) and offer the same operator
actions — promote, start session, accept, plus history from the panel. Everything here is
verified by `ConsoleBacklogBrowserTest` and `BacklogServiceTest`; no operator has driven it
against a live model or a real repository yet.

---

## 3. Component inventory

### 3.1 Already in zeroz4j (use as-is)

`Drawer, Menu, Navbar, Tab, Dialog (modal), Card, Stat, Badge, Avatar, Indicator,
ChatBubble, Timeline, Steps, Progress, RadialProgress, Skeleton, Toast, Tooltip, Toggle,
Select, TextField, TextArea, Checkbox, RadioButtonGroup, Range, Table, Collapse,
Accordion, Breadcrumbs, Dropdown, Loading, Kbd, Alert, Join, Stack, Swap, Divider,
ThemeController` + layouts (`FlexLayout, GridLayout, Scroller, FormLayout`) + `Binder`
(validated forms) + signals (`ValueSignal, Computed, Effect`).

### 3.2 NEW components to build (zeroz4j-ui-components or sc-console-ui/components)

Java over DaisyUI/Tailwind classes; where DaisyUI has no primitive, raw Tailwind + a
small CSS block in the component. Components marked ★ are generic enough to upstream
into zeroz4j itself.

| Component | What it is | Implementation notes |
|---|---|---|
| ★ `SplitPane` | Draggable horizontal/vertical divider | 3 divs + pointer events; persist ratio to localStorage |
| ★ `SvgCanvas` | Base for vector graphics | `Document.createElementNS("http://www.w3.org/2000/svg", …)` via TeaVM JSO; pan (pointer drag on root `<g transform>`), zoom (wheel → scale), fit-to-content |
| `GraphView` | The observability node graph | On `SvgCanvas`. Layered DAG layout (§6.3), nodes as `foreignObject`-embedded DaisyUI cards (so nodes are normal styled HTML), edges as cubic bézier `<path>` with animated `stroke-dashoffset` while the target is RUNNING. Selection, hover halo, collapse/expand clusters |
| ★ `VirtualScroller` | Windowed list for 10k+ rows | Fixed row height, translate-Y spacer technique; drives transcript + tables |
| ★ `MarkdownView` | Renders LLM markdown | Small hand-rolled parser (headings, bold/italic, lists, links, tables, fenced code → `CodeBlock`); NO external JS libs (TeaVM) |
| ★ `CodeBlock` | Syntax-highlighted code + copy button | Lightweight tokenizer (keywords/strings/comments/numbers) for java/xml/json/yaml/diff; Tailwind token colors; line numbers optional |
| `DiffView` | Unified + side-by-side code diff | Parses unified diff text; per-file collapsible sections with add/del gutters (DaisyUI `diff` is an image-compare widget — not this) |
| ★ `CommandPalette` | ⌘K fuzzy launcher | Dialog + TextField + VirtualScroller; providers register `(icon, title, hint, action)` suppliers |
| ★ `TreeView` | Collapsible tree (files in a diff, graph outline) | ul/li + Collapse behavior, arrow-key navigation |
| ★ `Sparkline` | Tiny inline trend chart | `SvgCanvas` polyline, 100×24, for token burn / survival trends |
| `TokenMeter` | Budget burn bar | Progress + threshold coloring (ok/warn/critical) + tooltip with exact numbers |
| `StreamingText` | Token-by-token appending text | Span bound to a `ValueSignal<String>`; auto-follows scroll unless user scrolled up ("↓ jump to live" pill appears) |
| ★ `ContextMenu` | Right-click menu | Positioning + dismiss handling over Menu |
| `StatusDot` | Colored/pulsing state dot | Maps CandidateState/RunState/SessionState → DaisyUI colors; `animate-ping` while active |
| `PropertyGrid` | Key/value inspector table | Two-col grid, copy-on-click values, monospace |
| `LaneTimeline` | Horizontal swimlane timeline (workers × time) | `SvgCanvas`; one lane per worker, colored segments per turn/tool call; the replay scrubber rides on it |
| `EmptyState` | Friendly zero-data placeholder | Illustration glyph + hint + primary action |
| `KpiTile` | Stat + sparkline + delta arrow | Composes Stat + Sparkline |

**Estimate:** ~14 new components, each 80–300 lines of Java, no external JS.

#### Backlog & requirements components — built (2026-07-25)

Added by [`REQUIREMENTS_AND_BACKLOG_DESIGN.md`](REQUIREMENTS_AND_BACKLOG_DESIGN.md)
§8.1/§8.3 (that document is authoritative for their behaviour). The behaviour all landed;
the *packaging* did not, and the "What shipped" column is the honest record of where each
one actually lives:

| Component | What it is | What shipped |
|---|---|---|
| `BacklogPanel` | The collapsible region of §2.2 | As specified: own `Resizer`, width + visibility persisted to localStorage, one `Effect` on `BacklogSignals.CURRENT`, implements `Disposable` |
| `BacklogTree` | Iteration → Story → Task tree inside it | Folded into `BacklogPanel` — the Iteration → Story → Task rows are hand-rolled `Div`s. `TreeView` and `StatusDot` were never built, and a one-consumer generic is not worth extracting yet. Clicking a row does push an `Inspector` level, as designed |
| `CriteriaEditor` | Per-requirement acceptance/fitness criteria list in `BrdView` | Folded into `BrdView` as a criteria panel on the requirement editor, with its own service calls; a non-functional requirement labels them fitness criteria |
| `UploadDropZone` | Drag/drop + file-picker intake, in the BRD view and in chat | Shipped as `Uploader`, a small `@JSBody` helper rather than a component — file picker, drop target and the shared `fetch` all need JS that has no TeaVM equivalent. `BrdView` (button + whole-editor drop target) and `ChatView` (drop on the composer, which ingests without sending a message) both use it. **The body is the raw file bytes with the name, content type and project id as query parameters — not `multipart/form-data`**; see §5.2 |
| `TriageGroup` | Discovered items awaiting promotion | Folded into `BacklogPanel` as the badged Triage group of `DRAFT` stories, with promote / cancel offered from the Inspector rather than inline on the row |

Net effect on the §3.2 estimate: two new UI classes (`BacklogPanel`, `BacklogBoard`) plus
`Uploader`, and no new generic primitives.

---

## 4. Reactive architecture — signals end to end (req. #5)

### 4.1 The client `SignalStore`

One instance per connected client, the single source of truth the entire UI renders from.
Views never call RMI for data they render; they read signals. RMI writes go through
action methods; RMI reads exist only inside the store's loaders.

```java
public final class SignalStore {
    // identity
    ValueSignal<List<ProjectDto>>          projects;
    ValueSignal<String>                    currentProjectId;

    // per-project (keyed maps of signals, created lazily)
    ValueSignal<List<ChatSummaryDto>>      chats(projectId);
    ValueSignal<List<RunSummaryDto>>       runs(projectId);
    ValueSignal<Integer>                   pendingDecisions(projectId);

    // per-run live graph model (§6.2)
    ValueSignal<RunGraphDto>               runGraph(runId);
    ValueSignal<List<SessionSummaryDto>>   sessions(runId);

    // per-chat
    ValueSignal<List<ChatMessageDto>>      messages(chatId);
    ValueSignal<String>                    streamingText(chatId);   // token stream tail

    // system
    ValueSignal<HealthDto>                 health;
    Computed<…>                            deriveds (unreadCounts, burnRates, …);
}
```

### 4.2 Push topics → signals

The v1 topics (`swarm-sessions`, `swarm-events`) are joined by:

| Topic | Payload | Feeds |
|---|---|---|
| `run-graph/{runId}` | `GraphDeltaDto` (node added / state changed / edge added / metric tick) | `runGraph(runId)` — the GraphView animates from these deltas alone |
| `chat/{chatId}` | `ChatEventDto` (message appended, tokens delta, tool-card update, question asked) | `messages(chatId)`, `streamingText(chatId)` |
| `project/{projectId}` | run started/state change, decision queued/resolved, chat created | sidebar lists, badges |
| `health` | Spark seqs, docker, budget ticks (1/s max) | status bar |

**Delta discipline:** every topic message carries `(seq, runId/chatId)`. The store applies
deltas in order; a gap triggers one snapshot RMI call (`GraphService.snapshot(runId)`)
then resumes deltas — this is the reconnect story too. Server-side, TraceHub events are
**coalesced into ≤10 push frames/second per topic** (100 ms buckets) so a chatty worker
can't melt the WebSocket; token streams for the *focused* chat are exempt (smooth typing).

### 4.3 Rendering rule

Every dynamic DOM region is owned by exactly one `Effect`. List regions diff by key
(id) and patch children instead of rebuilding (a tiny `KeyedList` helper — part of the
SignalStore package). Signal writes happening in push callbacks are marshalled onto the
UI thread and batched per animation frame.

### 4.4 Server-authoritative shared signals — the direct-model pattern

Since 2026-07-24 the newer surfaces bypass the DTO/push-topic route entirely: the **domain
object travels on the wire** (`@DataModel`) inside a *shared* signal that the server owns
and the client only reads. There is no snapshot RMI call and no refresh.

**Built:** `BrdSignals.CURRENT : ValueSignal<Brd>` (`Signals.shared("brd.current", …)`).
`BrdView` binds one `Effect` to it and redraws after every edit, whether the edit came from
the editor or from the requirements-intake wizard; the framework retains the latest value so a
late-opening editor is populated immediately.

**Built:** `BacklogSignals.CURRENT : ValueSignal<Backlog>` on exactly the same contract.
`BacklogPanel` and `BacklogBoard` both bind an `Effect` to it, and every mutation path —
the panel, the planning wizard's `BacklogPlanning`, and `markTaskState` when a task moves mid-run — republishes
through `BacklogPublisher`.

`Backlog` is an aggregate rather than a DTO: it carries the domain `Iteration` and `Story`
objects directly, and exists only because the panel needs iterations, stories and tasks
together and one signal carrying one value is what keeps the three consistent.

**Tasks are the one exception to the direct-model pattern.** They travel as a hand-written
`BacklogTask` projection (id, storyId, title, state, commitSha), because the zeroz4j
serializer handles `List` and `Map` but has **no `Set` support**, and the domain `Task` holds
four `Set` fields — `writeSet`, `readSet`, `requirementIds` and now `criterionIds`. It is
simply not wire-representable today. The gap is written up as a request in
[`ZEROZ4J_SERIALIZER_SPEC.md`](ZEROZ4J_SERIALIZER_SPEC.md) §4; if `TAG_SET` lands, the
projection should be deleted rather than kept. It is an exception with a reason, not a
preference — and the fact that it is the *only* one is the argument for the pattern.

Two rules are mandatory for anything on this pattern, both learned from the BRD signal:

- **Publish a fresh copy, never the mutated canonical instance.** `ValueSignal.set()` dedups
  by `equals()`, so re-setting the object the server just mutated in place is a silent no-op
  and the UI never updates. Every mutation path publishes `ArtifactStore.copyOf(...)` —
  `BrdAuthoring.pushChanged` is the reference implementation. (This is also why
  `DesignDocument.brdRequirementIds` being absent from `equals`/`hashCode` was a bug and not
  a style nit; it now participates in both.)
- **Dispose.** The signal is process-lifetime; a view bound to it leaks its `Effect` forever
  otherwise. Views implement `Disposable`, hold their effects in a `disposables` list and
  dispose them — `Workspace` already disposes tab content on close, and `BrdView` already
  follows this.

---

## 5. The Chat surface (req. #3, #6)

### 5.1 Model

New **POJO** domain types (record ban applies — guard tests already enforce it):

- `ChatSession { UUID id; UUID projectId; String title; Instant createdAt; UUID boundRunId /*nullable*/; ChatState state; }`
- `ChatMessage { UUID id; UUID chatId; int seq; ChatRole role /*USER, CODER, SYSTEM, TOOL*/; String markdown; String payloadRef /*blob, large content*/; UUID runId; UUID decisionId; MessageKind kind; Instant at; long tokens; }`

`StoreRoot.chats: Map<UUID, ChatSession>` + `chatMessages: Map<UUID, Lazy<List<ChatMessage>>>`
(lazy per chat — transcripts get long). `ArtifactStore` gains the obvious accessors; both
maps join `storeAll(...)` (the EclipseStore lazy-storing rule).

### 5.2 Server: `ChatOrchestrator` (sc-console, driven per project)

The coder side of the chat is a thin conversation layer over what already exists:

1. **Freeform turn** → the project's utility/architect endpoint (per-role config applies)
   with a chat system prompt + project context (Librarian brief header + active
   guidelines). Streamed to `chat/{id}` token by token.
2. **Intake**: when the user asks for work (or uses `/run <goal>`), the coder runs the
   **bounded interview** (spec §14 — ≤10 clarifying questions, finally implemented here),
   then calls the same `startRun` path the TUI used, binds the run to the chat, and the
   chat becomes that run's narrative feed.
3. **Run narration**: workflow transitions, design summary, plan (rendered as a task
   checklist card), red-check result, per-task survivor counts, integration result — all
   appended as SYSTEM cards via the existing event-logger seam.
4. **Decisions in-line**: a parked decision renders as an action card (Approve / Reject /
   free-text) wired to the existing `ControlService.resolveDecision`; APPROVAL parks
   render the integrated `DiffView` inline with per-file tree + Approve/Reject buttons.

Turns are queued and processed **serially per chat** (a message sent mid-reply waits its
turn rather than starting a second stream), and the freeform turn is itself a tool loop:
the analyst may answer with a single `TOOL <name> <args>` line — `read_file`, `list_folder`,
`search_code`, `lookup_docs` — executed against the confined chat tools, up to 8 rounds
before its final answer.

#### `/brd` — BRD author mode (**removed 2026-07-26**)

Built 2026-07-24 as a sticky chat mode with its own BRD tool table, and **removed** when the
Requirements panel's "Analyse documents" wizard replaced it — see
[`GUIDED_FLOWS_DESIGN.md`](GUIDED_FLOWS_DESIGN.md) §7 G2. Two paths to one outcome is the
confusion that redesign exists to remove, so the command is gone rather than merely
undocumented. `BrdAuthoring` survives it: the wizard's `RequirementsIntake` writes through the
same helper, and every mutation still publishes a fresh copy onto `BrdSignals.CURRENT` (§4.4),
so an open Requirements tab redraws live while the analysis works.

#### Backlog planning and document upload (**built 2026-07-25**, planning moved to a wizard 2026-07-27)

Per [`REQUIREMENTS_AND_BACKLOG_DESIGN.md`](REQUIREMENTS_AND_BACKLOG_DESIGN.md) §4 and §5:

- `BrdAuthoring` gained `addCriterion` / `updateCriterion`, requirement kind + NFR category,
  and the `gates` edge relation. (The `/brd` tool table over it was removed in 2026-07-26;
  the wizard's `RequirementsIntake` is the caller now.)
- **Backlog planning** was a sticky `/backlog` chat mode until 2026-07-27, when it became the
  backlog's **"Plan stories"** guided flow — `PlanningFlowService` / `BacklogPlanning` /
  `PlanningWizard`, with `PlanningFlowSignals.CURRENT` carrying its state (see
  [`GUIDED_FLOWS_DESIGN.md`](GUIDED_FLOWS_DESIGN.md) §9). The chat command is gone rather than
  merely undocumented, for the reason G2 gives. `BacklogAuthoring` survives it unchanged: the
  wizard applies its accepted proposals through `proposeStory` / `proposeEnabler`, so the rule
  that a delivery story must claim at least one real criterion — and may carry no requirement
  content of its own — still holds no matter which surface asked.
- **Upload** replaces paste-only intake. Files go over `POST /api/ingest` — **not** over the
  RMI socket, because a multi-megabyte blob framed onto the connection the live signals use
  would stall every open view for the length of the transfer. They are extracted
  (passthrough / PDFBox / POI / `roles.vision`), deduplicated by SHA-256 so re-dropping the
  same file reuses the extraction, persisted as a `SourceDocument`, and become `DRAFT`
  requirements carrying a `SourceRef` back to the sentence they came from. Cap: 25 MB.

  **The transport is not multipart.** The request body is the raw file bytes; the filename,
  content type and project id ride in the query string. Multipart exists to let an HTML form
  post several named fields at once, and the only client is our own TeaVM app calling
  `fetch` — so it would have bought a parser and a dependency on each side to encode two
  strings that fit in a URL. The endpoint is also **unauthenticated**, matching the rest of
  the Console's HTTP surface; it is the most sensitive of those, since it persists
  attacker-supplied bytes, and must be closed with the real auth work.

Verified by `IngestEndpointTest`, `DocumentExtractorTest` and `BrdIntakeAndBacklogTest`
(scripted LLM). Neither mode has been run against a live model.

### 5.3 Chat UX — the bells and whistles

- **Composer**: auto-growing TextArea; `Enter` sends, `Shift+Enter` newline; **slash
  commands** with autocomplete popover (`/run`, `/bugfix`, `/refactor`, `/analyze`,
  `/docs`, `/status`, `/budget`, `/model <role=model>`, `/guideline <text>`); **@-mention
  completion** over the repo tree (`@src/main/java/...` attaches the file as context,
  rendered as a chip); paste of a stack trace auto-offers "Start bugfix run?".
- **Message stream**: `ChatBubble` + `MarkdownView`; code fences → `CodeBlock` with copy;
  diffs → `DiffView`; tool activity → compact TOOL cards (icon, name, ms, expandable
  args/result via Inspector); streaming CODER message uses `StreamingText` with a blinking
  caret and a **Stop** button (cancels the LLM call server-side).
- **Chat header**: title (click to rename), bound-run chip (click → opens the run-graph
  tab), model override selector for this chat, token/cost meter (`TokenMeter`), "New chat
  from here" (fork with summary).
- **Multiple chats**: sidebar section lists them with unread dots; each opens its own tab;
  chats are independent EclipseStore-persisted transcripts — closing the browser loses
  nothing.
- **History**: infinite scroll-back through `VirtualScroller`; `⌘F` in-chat search;
  HistoryRag-backed global search from the command palette.

---

## 6. The Observability Graph (req. #4) — the centerpiece

### 6.1 What it must answer at a glance

Where is the run right now, which agents are doing what, what died and why, what did it
cost — and on click: *exactly what bytes went to and came from the model.*

### 6.2 Graph model

Server-side `GraphService.snapshot(runId)` + `run-graph/{runId}` deltas build one client
model, `RunGraphDto`:

```
STAGE ribbon (top):  INTAKE → DESIGN → REVIEW → PLAN → TEST_AUTH → EXECUTING → INTEGRATION → APPROVAL
                       │ each stage node: state, duration, cost; DESIGN/REVIEW/PLAN nodes link
                       ▼ to their role-session (deepseek call transcript)
TASK layer:          [Task A]──depends──▶[Task B]        (from TaskGraph; validator edges)
                        │ per-task: writeSet chips, criteria count, state
                        ▼
GROUP layer:         (dispatch group: prefixHash badge, N, temps range)
                        │ fan-out
                        ▼
CANDIDATE layer:     ●w0 ●w1 ●w2 … ●w9   + repair-wave nodes appended right, marked ↻
                        each: temp, family, state color, turns, tokens, verify verdict,
                        cluster badge (same-cluster candidates get the same hue), judge score,
                        crown on SELECTED
```

Node states map to colors: PENDING slate, RUNNING pulsing sky, SURVIVED green, FAILED
amber, KILLED red (kill-reason glyph), SELECTED gold crown, SUPERSEDED gray. Edges from
group→candidate animate (marching dashes) while the candidate runs. Verification shows as
a small progress ring inside candidate nodes.

### 6.3 Layout

Deterministic layered layout, no physics: stages on a fixed horizontal ribbon; tasks
topologically ordered below (longest-path layering, barycenter ordering to reduce edge
crossings — ~60 lines); candidates in a grid fan under their group. Collapse/expand at
every level (a collapsed group shows `8✓ 2✗` summary chips). `GraphView` handles
pan/zoom/fit; a **mini-map** (same model, 1:10 scale, viewport rectangle) sits bottom-right
when the graph exceeds the viewport.

Scale guard: n≤32 candidates × few tasks renders full; beyond ~200 visible nodes the
deepest layer auto-collapses to summary chips (LOD).

### 6.4 Drill-down — the Inspector stack (to the bytes)

Click any node → Inspector level 1; every level offers the next:

1. **Candidate** → sampling config (model/temp/seed/persona/prefixHash), state history,
   verification report (stage results, failing tests with messages, log tail, `[lsp]`
   block), judge score + rationale, diff summary → buttons: *Open diff*, *Open session*.
2. **Session (transcript)** → `VirtualScroller` of turns: LLM_RESPONSE bubbles, TOOL_CALL /
   TOOL_RESULT cards, NUDGE/KILL warnings — the v1 inspector view, relocated and virtualized.
3. **Turn** → full prompt/response payload (`MarkdownView`/`CodeBlock`), blob-backed
   payloads lazy-load on expand ("Load full 48 KB"), token count, latency.
4. **Tool call** → args JSON, result (exit code, output), duration; `apply_diff` results
   render as `DiffView`; write-set violations highlighted red.

Level 1 also exists for **stage nodes** (the deepseek design/review/plan transcripts —
same session viewer) and **task nodes** (criteria, write set, brief, red-check evidence).

### 6.5 Realtime AND replay (same graph)

- **Live**: deltas animate the graph as they arrive. A "LIVE" pill glows top-right.
- **Replay**: for finished runs the graph loads from persisted data and the `LaneTimeline`
  scrubber appears (workers × time, segments = turns/tools, verify/judge marks). Dragging
  the scrubber sets a `ValueSignal<Instant> replayCursor`; every node/edge state is a
  `Computed` over (events ≤ cursor) — the whole graph time-travels for free because
  rendering is already signal-driven. Play/pause at 1×/4×/16×.
- Both modes share 100% of the rendering code; "live" is just `cursor = latest`.

---

## 7. Settings (req. #1, #2)

- **Global settings dialog** (rail gear): tabbed `Dialog` — *Models & Roles* (per-role
  endpoint/model/key/thinking, worker families table with add/remove — this replaces raw
  YAML editing; Save round-trips through the existing settingsYaml validate-and-write),
  *Budgets*, *Sandbox*, *Console* (theme via `ThemeController`, density, notifications).
  Forms use `Binder` with inline validation; a "raw YAML" escape hatch keeps the v1
  editor in an *Advanced* tab.
- **Per-project settings dialog** (hover gear on rail item): name, primary path, context
  folders (list editor with existence check), **per-role model overrides** (same form as
  global, values layered — placeholder shows the inherited global value), guideline
  auto-promote toggle, archive project. Persists to `.swarmcoder/project.yaml` via a new
  `ControlService.saveProjectConfig` (server writes the file; ProjectContext rebuild on
  change — the registry already builds contexts lazily, add `invalidate(projectId)`).

---

## 8. Beyond the ask — included in v2 scope

- **Approval Center** (sidebar): all parked decisions across the project; APPROVAL items
  show the integrated diff (file tree + `DiffView`) with Approve/Reject+comment; other
  decisions (BUDGET_EXTENSION, BLOCKED_TASK) render their evidence briefs. Everything
  also appears inline in the bound chat — this is the roll-up.
- **Insights v2**: KpiTiles (runs, survival rate, tokens, cost) with sparklines;
  **temperature-vs-survival scatter** (the tuning question answered from archived
  candidates); family comparison bars; kill-reason breakdown; budget burn-down per run;
  prefix-cache effectiveness (dispatch groups per prefixHash). All `SvgCanvas` charts,
  all signal-fed.
- **Prompt Lab**: inspect any dispatch's exact `PromptBundle` segments (system / rules /
  guidelines / design / task / brief / repo-map) with per-segment token counts; **diff two
  dispatches' prompts** (`DiffView`) — "what changed in the prompt between the run that
  worked and the run that didn't"; guideline editor (list, edit body, promote PROPOSED →
  ACTIVE, retire) writing through GuidelineSync's file mirror.
- **Health popover + status bar**: Spark reachability & active seqs (poll `/v1/models` +
  scheduler gauges), Docker sandbox availability, store size, WS latency; toasts on
  disconnect/reconnect.
- **Notifications**: `Toast` on decision parked / run finished / worker blocked;
  optional browser Notification API when the tab is hidden.
- **Command palette**: projects, chats, runs, sessions (HistoryRag search), actions
  ("New chat", "Approve run 9e71…", "Open settings"), guideline search.

Deferred (explicitly out of v2): multi-user/auth, mobile layout (min width 1200px),
run comparisons side-by-side, editing code in the Console.

---

## 9. Server-side additions

| Piece | Module | Notes |
|---|---|---|
| `ChatSession`/`ChatMessage` POJOs | sc-domain | record-ban guards apply; reachability test picks them up automatically |
| `StoreRoot.chats` + `chatMessages` | sc-store | join `storeAll`; Lazy transcripts |
| `ChatService` (RMI): `createChat, listChats, history(chatId, beforeSeq, n), send(chatId, text), stop(chatId), rename, fork` | sc-console-api/-console | send() is fire-and-forget; everything flows back via push |
| `ChatOrchestrator` | sc-console | interview + narration + decision cards (§5.2); uses RoleClients of the chat's project |
| `GraphService` (RMI): `snapshot(runId)` → `RunGraphDto`; server assembles from store + TraceHub | sc-console-api/-console | one DTO tree, hand-packed |
| Push topics `run-graph/{id}`, `chat/{id}`, `project/{id}`, `health` | sc-console `LivePushBridge` v2 | seq-numbered deltas, 100 ms coalescing |
| `ControlService` v2: `saveProjectConfig`, `resolveDecision` (exists), `approveRun` w/ comment | sc-console-api | |
| `ProjectContexts.invalidate(projectId)` | sc-app | rebuild after settings change |
| Scheduler gauges (`activeSeqs()`) | sc-inference | for the health strip |

DTO count: ~14 new hand-packed `BinaryPackable` DTOs (GraphNodeDto, GraphEdgeDto,
GraphDeltaDto, RunGraphDto, ChatSummaryDto, ChatMessageDto, ChatEventDto, HealthDto,
PromptBundleDto, DecisionDetailDto, FileDiffDto, KpiDto, ScatterPointDto, ProjectConfigDto).
Remember: `_Stub`/`_Serializer` are APT-generated — write only the DTOs.

---

## 10. Visual design language

- **Dark-first** (DaisyUI `dim`-derived custom theme `swarm`; light theme secondary via
  `ThemeController`). Custom theme tokens: `primary` electric indigo #6366f1, `success`
  #22c55e, `warning` #f59e0b, `error` #f43f5e, neutral slate scale; candidate-cluster hues
  from a 12-step categorical wheel.
- **Density**: compact by default (this is an operator tool) — 13px base in tables/
  transcripts, 15px chat.
- **Motion**: 150 ms ease-out on layout changes; node state changes flash a halo;
  running elements pulse (`animate-pulse`/`animate-ping`); marching-ant edges; token
  streaming caret. Everything animatable is CSS-driven — no JS animation loops except
  the replay scrubber.
- **Typography**: system UI stack; `JetBrains Mono`-fallback mono for code, ids, paths.
- **Iconography**: inline SVG glyph set (~40 icons) as a `Icon` helper (no icon-font
  dependency; TeaVM-friendly).
- Every id (run, candidate, session) renders as an 8-char mono chip, click-to-copy, with
  the full UUID in the tooltip.

---

## 11. Phasing — six increments, each shippable

| Phase | Delivers | Acceptance (mechanical) |
|---|---|---|
| **C2-0 Foundations** | `SplitPane, SvgCanvas, VirtualScroller, MarkdownView, CodeBlock, DiffView, ContextMenu, StatusDot, EmptyState, Icon`; `SignalStore` + KeyedList; app shell (rail/sidebar/tabs/inspector/status bar) with v1 views ported into tabs | Component gallery page renders all new components; shell resizes & persists; browser smoke test green |
| **C2-1 Projects & Settings** | Rail with live project list, create-project flow, per-project settings dialog (project.yaml round-trip + context invalidation), global settings dialog (typed forms over config.yaml) | Settings edits survive restart; changing a project's model override is visible in next run's SamplingConfig; smoke test drives both dialogs |
| **C2-2 Chat MVP** | Chat domain/store/service/orchestrator; chat tabs; streamed freeform turns; `/run` intake → bound run; run narration cards; decision cards incl. inline diff approval | A full run initiated, narrated, and approved entirely in chat (headless-Chromium test scripted against FakeVllm) |
| **C2-3 Run Graph v1** | `GraphView` + `GraphService.snapshot` + `run-graph` deltas; stage/task/candidate layers; Inspector levels 1–4 to raw payloads | Live FakeVllm run animates node states with zero manual refresh; clicking to a tool-call payload shows exact bytes; browser test asserts node count/states |
| **C2-4 Replay & Timeline** | LaneTimeline + scrubber; replay mode on persisted runs; mini-map; LOD collapse | A finished run scrubs at 16× with correct historical states; 200-node graph stays >30 fps in the smoke environment |
| **C2-5 Insight & Prompt Lab** | Insights v2 dashboards, temp-vs-survival scatter, Prompt Lab with prompt diff, guideline editor, Approval Center, command palette, health strip, notifications | Scatter reflects candidateArchives exactly; prompt diff between two real dispatches renders; palette reaches every project/chat/run in ≤2 keystroke rounds |
| **C2-6 Chat SOTA polish** | Slash-command + @-mention autocomplete, attach files, stop/regenerate, interview mode, chat fork, per-chat model override, in-chat search | Interview flow test: ambiguous goal triggers ≤10 questions then plan; @-mention injects file into prompt (asserted server-side) |

Ordering rationale: components before consumers; chat before graph because the chat is
the daily-driver and exercises push infrastructure the graph then reuses; replay after
live because it reuses the live rendering.

### 11.1 Requirements & backlog increments (1–5 landed 2026-07-25)

Runs alongside the C2-* phases. Full requirement list in
[`REQUIREMENTS_AND_BACKLOG_DESIGN.md`](REQUIREMENTS_AND_BACKLOG_DESIGN.md) §9–§10, which is
authoritative; each increment ends buildable, demoable and committed.

| Inc | Contents | Demo | Status |
|---|---|---|---|
| **1** | Domain + store (B-1…B-10) | server test round-trips a story with criteria; client builds | landed |
| **2** | Intake + agent surfaces (B-11…B-24) | drop a PDF, get a structured BRD with criteria, then a proposed backlog | landed |
| **3** | Backlog panel + BRD editor (B-25…B-33) — the §2.2 / §3.2 / §4.4 work | see the backlog live while a run executes | landed |
| **4** | Architect rewiring (B-34…B-40) | a story-scoped run whose design contains zero invented requirements | landed |
| **5** | Delivery, gates, DoD (B-41…B-49) | accept a story; watch a requirement flip to `IMPLEMENTED` with its commit | landed |
| **6** | Tests + docs (B-50…B-52) | green E2E, docs current | in progress |

The "Demo" column describes what each increment was meant to make possible, not something
anyone has watched happen. Every increment closed on unit, store, service, browser and
scripted-LLM tests; none of this has been exercised against a live model or a real
repository, which is what increment 6 is for.

---

## 12. Risks & mitigations

- **TeaVM DOM performance** (10k-event transcripts, 200-node SVG): VirtualScroller
  everywhere; keyed patching; per-frame signal batching; LOD collapse. Budget: interact
  at 60 fps for n=10 runs, degrade gracefully to 30 fps at n=32.
- **Push volume**: server-side 100 ms coalescing per topic; focused-chat exemption;
  seq-gap snapshot recovery makes drops harmless.
- **EclipseStore growth** (chat transcripts + graphs): transcripts Lazy per chat; payload
  bodies stay in the blob store (refs only), same as sessions today.
- **Markdown/highlighting correctness**: hand-rolled parsers are deliberately small;
  fenced-code and tables only — no HTML passthrough (LLM output is untrusted; escape
  everything).
- **Scope**: C2-0/C2-2/C2-3 are the load-bearing phases; C2-5/C2-6 items are individually
  droppable without structural damage.

---

## 13. Decisions needed from the author before C2-0

1. **Component home**: build ★-marked generics inside zeroz4j (`zeroz4j-ui-components`)
   for reuse, or keep everything in sc-console-ui first and upstream later?
   *(Recommendation: build in sc-console-ui/components, upstream after they stabilize —
   avoids cross-repo churn while iterating.)*
2. **Chat LLM role**: which configured role speaks in freeform chat — `utility`,
   `architect`, or a new `roles.chat` key? *(Recommendation: new optional `roles.chat`,
   falling back to `utility`.)*
3. Confirm the dark theme direction (§10) or supply brand tokens.
