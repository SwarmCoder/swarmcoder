# SwarmCoder — Requirements & Backlog Design

**Status:** increments 1–5 implemented (2026-07-25); increment 6 in progress. See §0.
**Author decisions:** 2026-07-25 (14 decisions, recorded in `DEVELOPER_CORRECTIONS.md` §11).
**Supersedes:** the "Epic → Story → Task" sketch discussed earlier the same day (Epic was dropped — see §2.3).
**Superseded in part:** §5.1 and §5.2 — both chat modes are gone, replaced by guided flows ([`GUIDED_FLOWS_DESIGN.md`](GUIDED_FLOWS_DESIGN.md) §7, G2). `/brd` was removed on 2026-07-26 for the Requirements panel's "Analyse documents" wizard; `/backlog` on 2026-07-27 for the backlog's "Plan stories" wizard. What the agents *do* is still described below and still true — the story rules, the tool semantics, the DRAFT gate — only the entry point changed, and `BacklogAuthoring` is still the only way a story reaches the backlog.
**Implements:** task #10 (Architect reads the project BRD) and everything that follows from it.

---

## 0. Status

Increments 1 through 5 of §10 are implemented — the domain and store (v7), document intake and the
`/brd` and `/backlog` agent surfaces, the backlog panel, board and criteria editor, the story-scoped
Architect, and the delivery evidence, NFR gates and definition-of-done gate. Increment 6 (end-to-end
tests and bringing the documentation in line) is in progress.

**None of it has been run against a live model or a real repository.** Every increment closed on
tests: unit tests, EclipseStore round-trips, service tests, browser tests, and agent tests driven by
a scripted LLM. That establishes that the code does what this document says it does; it does not
establish that a real model produces usable requirements from a real PDF, that a real Architect
respects a story's scope under pressure, or that the criterion-to-commit chain survives a real
repository. Treat the sections below as a description of built behaviour, not as evidence of a
working system. Proving it is what increment 6 is for.

The rest of this document is unchanged and remains the design. Two things landed differently from
what it specifies, and the deviations are deliberate:

| § | Designed | Built | Why |
|---|---|---|---|
| §4.2 | `POST /api/ingest`, `multipart/form-data` | `POST /api/ingest` with the **raw file bytes as the body** and filename, content type and project id as query parameters | Multipart exists so an HTML form can post several named fields at once. The only client is our own TeaVM app calling `fetch`, so multipart would have added a parser and a dependency on both sides to encode two strings that fit in a URL. The endpoint, the transport choice (HTTP rather than the RMI socket) and the 25 MB cap are otherwise as designed. |
| §8.2 | the domain `Task` travels on the `Backlog` signal, like `Story` and `Iteration` | a hand-written `BacklogTask` projection (id, storyId, title, state, commitSha) | The zeroz4j serializer supports `List` and `Map` but has no `Set` support, and `Task` holds four `Set` fields (`writeSet`, `readSet`, `requirementIds`, `criterionIds`). It is not wire-representable today. Written up as a request in [`ZEROZ4J_SERIALIZER_SPEC.md`](ZEROZ4J_SERIALIZER_SPEC.md) §4; if `TAG_SET` lands, the projection should be deleted rather than kept. `Story` and `Iteration` do travel directly, so this is the single exception. |

---

## 1. The problem this solves

SwarmCoder can already turn a goal into a run, a design, a task graph, N candidate solutions and an
integration branch. Two things are missing, and they are the same thing viewed from two ends:

1. **Nothing durable connects a business requirement to the commit that satisfied it.**
   `Task.requirementIds` points at *design-local* requirements that the Architect invented from the
   goal string and that die with the run. The project BRD — the thing a human actually owns and
   edits — is not read by any workflow code (`DesignDocument.brdRequirementIds` exists and has zero
   callers).
2. **There is no unit of work between "the BRD" and "a worker-sized task".** You cannot say
   "build *this* part of the BRD next", watch it, and see what it produced.

### 1.1 Why not just "use a backlog", and why not just "use a BRD"

A **BRD in the classical sense** is a waterfall artifact: a large document signed off up front and
handed over. It describes the system but says nothing about sequencing, and it rots because nothing
forces it to stay true.

A **backlog** is the agile replacement, and Scrum calls it the single source of truth for work. But a
backlog is a *queue of deltas*, not a *description of the system*. Items are completed and leave the
board. After a year the board cannot answer "what must this system do?" — only "what is next". Teams
bolt a wiki or PRD alongside it, and that rots for the same reason the BRD did.

The mature resolution — Specification by Example / BDD's **living documentation**, and SAFe's
treatment of NFRs as *persistent backlog constraints* rather than completable items — is to keep
**one** durable requirement layer and make it non-rotting by binding its acceptance criteria to
**executable tests**. The specification cannot drift from the system, because the build fails when it
does.

SwarmCoder is unusually well placed to do this properly: it already authors and runs acceptance
tests as a first-class workflow phase, so "criteria are executable" is not aspiration, it is the
existing `sc-verify` harness.

### 1.2 The governing principle

> **The requirement graph is the only source of truth. A story is a scheduled slice of it, not a
> second description of it.**

Concretely, the rule that keeps the two layers from competing:

> **A story carries no requirement content of its own.** If a story asserts something the BRD does
> not, that is a defect: promote it into the BRD or delete it. Stories own *scheduling and execution
> state*; requirements own *meaning*.

| | Requirement (`BrdRequirement`) | Story |
|---|---|---|
| answers | "what must be true" | "what are we doing next" |
| lifecycle | permanent, versioned, restorable | created → done → archived |
| owner | the operator (human) | the delivery process |
| in three years | still there | long gone |
| carries | meaning + acceptance criteria | ordering, state, runs, commits |

---

## 2. The model

### 2.1 Shape

```
BrdRequirement R2  FUNCTIONAL  ACTIVE  "Checkout"          <-- renders as an "epic"
  ├REFINES── R7  FUNCTIONAL  ACTIVE  "Guests can check out"
  │            ├─ C1 "empty cart is rejected"  GuestCheckoutTest#emptyCart   PASSING a1b2c3d
  │            ├─ C2 "guest order confirmed"   GuestCheckoutTest#confirmed   PASSING a1b2c3d
  │            └─ C3 "guest order emailed"     GuestCheckoutTest#email       FAILING
  └REFINES── R8  FUNCTIONAL  DRAFT   "Saved cards"

BrdRequirement R12 NON_FUNCTIONAL/PERFORMANCE ACTIVE "p95 API latency < 200ms @ 100rps"
  ├─ F1 (fitness)  PerfSmokeTest#p95Under200ms   PASSING
  └GATES──> R2      (inherited by R2 and every REFINES-descendant)

Iteration "MVP checkout"  ACTIVE
  ├ Story S3  DELIVERY  REVIEW   delivers R7:C1,C2
  │    runs: #41 FAILED, #42 DELIVERED
  │    commit a1b2c3d   integration e4f5a6b   pr #148 (optional)
  │    └ Task T1 DONE, T2 DONE, T3 DONE       (architect-produced)
  └ Story S9  ENABLER   READY    unblocks R7   "extract cart module"
```

### 2.2 New and changed domain classes (`sc-domain`)

All are mutable POJOs with no-arg + all-args constructors, dual accessors (`x()` and `getX()`),
setters, `equals`/`hashCode` — matching the existing convention — and all are `@DataModel` so they
travel the wire directly with no DTO (the direct-model pattern established 2026-07-24).

#### Changed: `BrdRequirement`

| Field | Type | Note |
|---|---|---|
| *(existing)* | `id, handle, title, text, priority, status, category` | unchanged |
| `kind` | `RequirementKind` | **NEW** `FUNCTIONAL` \| `NON_FUNCTIONAL`. Null loads as `FUNCTIONAL`. |
| `nfrCategory` | `NfrCategory` | **NEW** null unless `kind == NON_FUNCTIONAL` |
| `criteria` | `List<AcceptanceCriterion>` | **NEW** the executable definition of this requirement. For an NFR these are *fitness* criteria; the mechanism is identical. |
| `sourceRef` | `SourceRef` | **NEW** provenance: which uploaded document/locator this was extracted from (null if hand-authored) |

#### Changed: `AcceptanceCriterion` — moves from `Task` to `BrdRequirement`

| Field | Type | Note |
|---|---|---|
| `id`, `text`, `testClassOrFile` | | existing |
| `status` | `CriterionStatus` | **NEW** `PROPOSED` \| `ACCEPTED` \| `RETIRED` |
| `verification` | `CriterionState` | **NEW** `UNVERIFIED` \| `PASSING` \| `FAILING` |
| `lastVerifiedRunId` | `UUID` | **NEW** |
| `lastVerifiedCommit` | `String` | **NEW** the sha that made it pass |
| `lastVerifiedAt` | `Instant` | **NEW** |

#### New: `Story`

| Field | Type | Note |
|---|---|---|
| `id` | `UUID` | |
| `projectId` | `UUID` | |
| `key` | `String` | stable label `S1, S2, …`, minted like BRD handles |
| `kind` | `StoryKind` | `DELIVERY` \| `ENABLER` |
| `title` | `String` | |
| `narrative` | `String` | optional "as a … I want … so that …"; **never** a requirement restatement |
| `state` | `StoryState` | see §2.5 |
| `requirementIds` | `List<UUID>` | for `DELIVERY`: what it delivers. For `ENABLER`: what it unblocks. |
| `criterionIds` | `List<UUID>` | **the slice** — the exact criteria this story delivers. Empty for `ENABLER`. |
| `iterationId` | `UUID` | null = unscheduled backlog |
| `order` | `int` | position within the iteration (or the backlog) |
| `origin` | `StoryOrigin` | `BACKLOG` \| `AD_HOC` \| `DISCOVERED` |
| `originRunId` | `UUID` | set when `DISCOVERED` or `AD_HOC` |
| `rationale` | `String` | why the agent proposed it (`DISCOVERED` only) |
| `author` | `String` | `human` \| `agent` \| `extraction` — same vocabulary as `BrdRevision` |
| `runIds` | `List<UUID>` | every run that has attempted this story, in order |
| `deliveredCommit` | `String` | sha of the selected candidate's work |
| `integrationCommit` | `String` | sha on the run's integration branch |
| `prNumber` / `prUrl` | `Integer` / `String` | optional overlay, only when a remote is configured |
| `createdAt`, `updatedAt` | `Instant` | |

#### New: `Iteration`

`id`, `projectId`, `name`, `goal`, `seq` (ordering), `state` (`PLANNING` \| `ACTIVE` \| `CLOSED`),
`createdAt`, `closedAt`. Stories point *at* an iteration; the iteration holds no story list (one
direction only, so there is nothing to keep in sync).

**No dates, no story points, no velocity.** An iteration is a named ordered batch with a
definition of done ("all selected criteria PASSING"). Cost is *measured* from telemetry already
collected — tokens, wall-clock, candidate pass rate — not estimated.

#### Changed: `Task`

| Field | Type | Note |
|---|---|---|
| *(existing)* | | unchanged, except `criteria` (see below) |
| `storyId` | `UUID` | **NEW** every task belongs to exactly one story |
| `criterionIds` | `List<UUID>` | **NEW** references into `BrdRequirement.criteria` — replaces owning copies |
| `criteria` | `List<AcceptanceCriterion>` | **RETAINED** but only for `ENABLER` tasks with no requirement (technical gates). Empty for delivery tasks. |
| `selectedCandidateId` | `UUID` | **NEW** which candidate won |
| `commitSha` | `String` | **NEW** the durable git link |

#### Changed: `Run`, `DesignDocument`

- `Run.storyId : UUID` — the story this run is executing.
- `DesignDocument.requirements` is **no longer invented**. It is populated from the story's linked
  requirements. `brdRequirementIds` is finally written, and is added to `equals`/`hashCode`
  (currently omitted — `DesignDocument.java:80` — so two designs differing only in BRD links compare
  equal, which breaks any signal that dedups by `equals`).

#### New: `SourceDocument`, `SourceRef`

`SourceDocument`: `id, projectId, filename, mediaType, sha256, extractedText, extractedBy`
(`passthrough` \| `pdfbox` \| `poi` \| `vision`), `uploadedAt`, `byteSize`.
`SourceRef`: `documentId` + `locator` (page/heading/line hint) — so every extracted requirement can
be traced back to the sentence it came from.

#### New enums

```java
RequirementKind  { FUNCTIONAL, NON_FUNCTIONAL }
NfrCategory      { PERFORMANCE, SECURITY, RELIABILITY, USABILITY,
                   MAINTAINABILITY, OPERABILITY, COMPLIANCE, PORTABILITY }
CriterionStatus  { PROPOSED, ACCEPTED, RETIRED }
CriterionState   { UNVERIFIED, PASSING, FAILING }
StoryKind        { DELIVERY, ENABLER }
StoryState       { DRAFT, READY, RUNNING, REVIEW, DONE, BLOCKED, CANCELLED }
StoryOrigin      { BACKLOG, AD_HOC, DISCOVERED }
IterationState   { PLANNING, ACTIVE, CLOSED }
```

`RequirementRelation` gains one constant: **`GATES`** — source (an NFR) constrains target and every
`REFINES`-descendant of target.

### 2.3 Why there is no `Epic` class

An earlier sketch had `Epic → Story → Task`. It was wrong. `BrdRequirement` already forms a
hierarchy through `REFINES` edges, and `category` already groups. A separate `Epic` class would
create a *second* hierarchy expressing the same thing, which you would then have to keep consistent
by hand — precisely the duplicated truth this design exists to remove.

**An epic is a coarse requirement with `REFINES` children.** It is a rendering, not a class. The
backlog panel groups stories by the nearest common ancestor of the requirements they deliver.

### 2.4 Why criteria live on the requirement

This is the load-bearing decision.

- **Slicing without drift.** A story is defined by *selecting criteria*, not by paraphrasing the
  requirement into story prose. There is no second wording, so there is nothing to diverge.
- **The BRD becomes self-verifying.** A criterion is bound to a test id. When the test passes, the
  criterion records the commit. When every `ACCEPTED` criterion of a requirement is `PASSING`, the
  requirement flips to `IMPLEMENTED` **on evidence**, not on someone's say-so.
- **NFR gates fall out for free.** A functional requirement's acceptance criteria and an NFR's
  fitness criteria are the same object checked by the same harness. The NFR gate is not a special
  case; it is the ordinary mechanism pointed at a `GATES` edge.
- **Criteria outlive the work.** Story-local criteria are archived with the story, leaving the
  requirement with no executable definition six months later — the exact rot this design rejects.

---

### 2.5 Lifecycles

**Story**

```
        (agent proposes / operator drafts)
                    DRAFT
                      | operator promotes
                      v
   BLOCKED <------- READY ------> CANCELLED
                      | run starts
                      v
                   RUNNING
                      | all tasks green AND all gating NFR criteria pass
                      v
                    REVIEW          <-- definition-of-done gate, operator-owned
                      | operator accepts
                      v
                     DONE  --> linked criteria marked PASSING + commit recorded
                            --> requirement flips to IMPLEMENTED when all its
                                ACCEPTED criteria are PASSING
```

A failed run returns the story to `READY` (retryable) or `BLOCKED` (needs a decision). The story keeps
its identity across all attempts — that is why it is not the session.

**Requirement status** (`DRAFT → ACTIVE → IMPLEMENTED → DEPRECATED`) is unchanged, except that
`ACTIVE → IMPLEMENTED` is now driven by criterion evidence, and reverts to `ACTIVE` if a criterion
later regresses to `FAILING`.

### 2.6 The NFR gate

| NFR status | Has `ACCEPTED` fitness criterion? | Criterion fails |
|---|---|---|
| `ACTIVE` | yes | **blocks final integration** |
| `ACTIVE` | no | run warning + a nag to add one |
| `DRAFT` | yes | advisory warning only |
| `DEPRECATED` | — | not evaluated |

Scope: an NFR gates a story when it has a `GATES` edge to any requirement the story delivers, **or to
any `REFINES`-ancestor of one**. Inherited gates are shown explicitly on the story in the UI so the
inheritance is never a surprise.

---

## 3. Persistence (`sc-store`)

`StoreRoot` → `schemaVersion = 7`, three new maps, each with the established null-guard accessor
(pre-v7 stores load the field as `null`; EclipseStore does not run field initializers on load):

```java
private Map<UUID, Story>     stories     = new HashMap<>();   // by story id
private Map<UUID, Iteration> iterations  = new HashMap<>();   // by iteration id
private Map<UUID, Task>      tasks       = new HashMap<>();   // by task id — canonical index
private Map<UUID, SourceDocument> sourceDocuments = new HashMap<>();
```

**The task index costs nothing.** EclipseStore persists an object graph by reference, so putting the
*same* `Task` instances into `tasks` that already live in `TaskGraph.tasks` stores one copy, not two.
`TaskGraph` is unchanged — it remains the run's execution DAG. This gives tasks stable, directly
addressable identity (needed by the backlog panel and by git linkage) with **no breaking change and
no migration of existing data**.

`ArtifactStore` additions: `saveStory`, `saveIteration`, `saveTask`, `listStories(projectId)`,
`storyTasks(storyId)`, `saveSourceDocument`, plus `copyOf(Story)` for the shared-signal contract.
All new roots join `storeAll(...)`.

> **Lazy-store trap** (learned the hard way on `BrdRevision`): mutating an object already known to
> the store and calling `storeAll(container)` does **not** deep-store it. Every mutation path must
> `store(...)` the mutated object or list explicitly.

**Bug to fix on the way:** `BrdAuthoring` currently calls the 1-arg `saveBrd(brd)` at
`BrdAuthoring.java:87,115,144`, so **agent-authored BRD edits bump the revision but write no
`BrdRevision` snapshot** — they are invisible in the history/restore UI, and the documented `author =
"agent"` value is never actually produced. All agent mutations must use
`saveBrd(brd, "agent", summary)`.

---

## 4. Document intake

No upload path exists today. The only ingestion is plaintext pasted into a `/brd` chat message
(`ChatOrchestrator.java:118`); pasted images are deliberately never sent to the model
(`ChatOrchestrator.java:187`).

### 4.1 Pipeline

```
upload (drop / picker / paste)
   -> size + type guard
   -> extractor
        .md .txt .adoc   passthrough
        .pdf             PDFBox      -> text
        .docx            POI         -> text
        .png .jpg .webp  roles.vision -> description   (config'd vision model)
   -> SourceDocument persisted (text + sha256 + provenance)
   -> BRD author agent: extract DRAFT requirements, each with a SourceRef
   -> interview the operator on gaps, conflicts, missing criteria, priorities
   -> operator promotes DRAFT -> ACTIVE in the Requirements tab
```

### 4.2 Transport

Upload goes over a plain HTTP endpoint on the console's existing server
(`POST /api/ingest`, multipart, authenticated with the same session), **not** over the RMI socket.
Rationale: binary blobs of tens of megabytes on the RMI channel would block the same connection the
live signals use, and the framing/chunking work buys nothing. The RMI service is told the resulting
`SourceDocument` id.

Caps: 25 MB per file, 10 MB extracted text, configurable.

### 4.3 Vision

New config block, following the existing `roles.*` convention:

```
roles.vision.model     = <vision-capable model id>
roles.vision.endpoint  = <OpenAI-compatible endpoint>
roles.vision.maxTokens = ...
```

If absent, image upload is rejected with an explicit message naming the missing config rather than
failing obscurely. Images are sent as an OpenAI-compatible image message part; the returned
description becomes the `SourceDocument.extractedText` and is clearly marked as
`extractedBy = vision` so the operator knows it is a model's reading, not the document itself.

---

## 5. Agent surfaces

### 5.1 `/brd` — requirement authoring (extends the existing mode)

> **Superseded 2026-07-26.** The `/brd` chat mode is removed; requirement authoring is now the
> Requirements panel's "Analyse documents" wizard (`IntakeWizard` → `GuidedFlowServiceImpl` →
> `RequirementsIntake`). The extract → interview → DRAFT behaviour described here is what that
> wizard does; the chat entry point and its `TOOL` protocol are gone.

Existing behaviour is kept (extract → interview → DRAFT). New tool surface, following the
established single-line `TOOL <name> <args>` protocol:

| Tool | Purpose |
|---|---|
| `get_brd` | existing; now renders kind, NFR category, criteria and their verification state |
| `add_requirement` | gains `kind` and `nfr_category` arguments |
| `update_requirement` | existing |
| `add_edge` | existing; now accepts `gates` |
| `add_criterion` | **new** — attach an acceptance/fitness criterion to a requirement |
| `update_criterion` | **new** |
| `get_source` | **new** — read the uploaded source document text (paged) |

Persona additions: extract **functional and non-functional** requirements; every requirement must end
up with at least one criterion, and the agent should interview until it has one it can express as a
test; NFRs must be *measurable* (push back on "must be fast"); record `GATES` edges for NFRs that
constrain a whole area.

Agent-created criteria land as `PROPOSED` (advisory), and become `ACCEPTED` gates only when the
operator promotes them — mirroring `DRAFT → ACTIVE` for requirements.

### 5.2 Story authoring (superseded 2026-07-27 — now the "Plan stories" wizard)

Originally a `/backlog` chat mode. It is now the backlog's guided planning flow
(`BacklogPlanning` / `PlanningWizard`), which asks its questions as a form and shows its stories for
review before any of them land. The rules below still hold — they are enforced by
`BacklogAuthoring`, which the wizard writes through — but the tools are no longer a chat tool table:
the planner returns its stories as data in one reply.

| Tool | Purpose |
|---|---|
| `get_backlog` | iterations, stories, states, unscheduled backlog |
| `propose_story` | create a `DRAFT` `DELIVERY` story over a criterion set |
| `propose_enabler` | create a `DRAFT` `ENABLER` story linked to what it unblocks |
| `update_story` | title/narrative/requirements/criteria/order |
| `add_iteration` | name + goal |
| `schedule_story` | assign a story to an iteration at a position |

Persona: propose the **thinnest vertical slices** that deliver whole criteria — never a slice of a
criterion, never a layer ("build the DB tables"). Interview on sequencing, dependencies, what is out
of scope, and what the first iteration should prove. Refuse to invent requirement content: if a
needed capability is not in the BRD, say so and propose a requirement instead.

Coverage is reported back to the operator: which `ACTIVE` requirements have no story, and which
criteria are unclaimed.

### 5.3 Discovery during a run

Per the author decision: **tasks freely, stories need approval.**

- The Architect may add tasks within the story it is decomposing without asking (it effectively
  already does).
- Any worker or the Architect proposing a *new story, requirement or criterion* creates it as
  `DRAFT` / `PROPOSED` with `origin = DISCOVERED`, `originRunId`, and a rationale. It appears in a
  **Triage** group in the backlog panel with a count badge. It is never scheduled until promoted.

---

## 6. Architect changes (`sc-workflow`)

`ArchitectClient` today invents its own requirement list from the goal string
(`ArchitectClient.java:95`). That is removed.

**`design(story, requirements, gatingNfrs)`**
- Input: the story, its linked `BrdRequirement`s with their criteria, and every inherited NFR gate.
- Output: `DesignDocument` with **decisions, contracts and risks only**.
  `requirements` is populated *from* the BRD, and `brdRequirementIds` is written.
- If the Architect believes a requirement is missing, it emits a `DRAFT` proposal to triage.
  It must not invent one silently.

**`plan(design, story)`**
- Every task must declare the `criterionIds` it satisfies, drawn from the story's slice.
- `TaskGraphValidator` gains a **coverage check**: every criterion in the story is claimed by at
  least one task, and no task claims a criterion outside the story. This is a *violation*, not a
  warning — an uncovered criterion means the story cannot possibly reach `REVIEW`.
- Existing checks (acyclic, write-set disjointness for concurrent tasks) are unchanged.
- Each task's verification gate = its criteria's tests + the story's inherited NFR fitness tests.

The `fallbackGraph` path (`GreenfieldWorkflow.java:341`) produces a single task claiming all of the
story's criteria.

---

## 7. Delivery and git linkage

Today the only git handle on any persisted object is `CandidateSolution.branch`. No sha is stored
anywhere; candidate branches are archived to `refs/swarm-archive/...` after the run, so the branch
string rots.

- When a candidate is **selected**, its commit sha is captured and written to
  `Task.commitSha` + `Task.selectedCandidateId`.
- When `FinalIntegrator` merges, the integration sha is written to `Story.integrationCommit`, and
  `Story.deliveredCommit` records the tip of the delivered work.
- **PR is an optional overlay.** If a remote and `gh` are configured, the story additionally records
  `prNumber`/`prUrl`. Nothing depends on it; the system is fully functional offline, which is the
  normal mode.
- Ambiguity note: N workers produce N branches per task. Only the **selected** candidate's commit is
  recorded — "the branch for a task" is otherwise meaningless.

On successful integration: story → `REVIEW`. On operator accept: story → `DONE`, its criteria →
`PASSING` with the commit stamped, and each fully-satisfied requirement → `IMPLEMENTED`.

---

## 8. Console UI (`sc-console-ui`)

### 8.1 Layout

A new collapsible **Backlog panel** between the nav sidebar and the workspace, with a `Resizer`
(the existing component), persisted width and collapsed state:

```
┌─rail─┬─nav─┬─BACKLOG────────┬─workspace──────┬─inspector─┐
│      │     │ ▾ It.3 MVP     │                │  Story S3 │
│      │     │  ● S3 Guest  ✓ │  chat / graph  │  delivers │
│      │     │    └ T1      ✓ │                │  R7:C1,C2 │
│      │     │    └ T2      ◐ │                │  gates R12│
│      │     │  ○ S4 Cards    │                │  a1b2c3d  │
│      │     │ ▾ Backlog (12) │                │           │
│      │     │ ▾ Triage (2) ! │                │           │
└──────┴─────┴────────────────┴────────────────┴───────────┘
```

- Tree: **Iteration → Story → Task**, with live state badges and an NFR-gate indicator on stories.
- Groups: active iteration(s), unscheduled backlog, **Triage** (discovered items awaiting promotion,
  with a count badge), and a collapsed Done section.
- Clicking any node drills into the **existing** right-hand `Inspector` (already a breadcrumbed
  stack — `Inspector.java:14`). No new drill-down mechanism.
- The story inspector shows: delivered criteria with pass/fail and commit, inherited NFR gates,
  run history, git links, and an **Accept** button when in `REVIEW`.
- A full-width **Backlog board** tab in the workspace for planning sessions (drag to reorder, assign
  to iteration, promote from triage).
- **Start session** on a `READY` story creates the run bound to it.

### 8.2 Reactive plumbing

Follows the direct-model pattern established 2026-07-24 — **no DTOs**:

- `BacklogSignals.CURRENT : ValueSignal<Backlog>` via `Signals.shared("backlog.current", …)`,
  server-authoritative, carrying the domain objects directly.
- The server publishes a **fresh copy** (`ArtifactStore.copyOf`) on every mutation — `set()` dedups
  by `equals()`, so republishing the mutated canonical instance is a no-op and the UI would not
  update.
- Views implement `Disposable`, keep a `disposables` list and dispose their `Effect`s; `Workspace`
  already disposes tab content on close. This is mandatory — the signal is long-lived and leaked
  effects accumulate.
- Task state changes already flow through `markTaskState`; that path additionally republishes the
  backlog signal.

### 8.3 BRD editor changes

`BrdView` gains: functional/non-functional toggle with NFR category, a criteria editor per
requirement (text + test id + status) showing live verification state and last-passing commit,
`GATES` edge support, source-document provenance links, and an upload drop zone.

---

## 9. Requirement list for implementation

Each item is independently verifiable. `Inc` = increment (§10).

### Domain & persistence

| # | Requirement | Inc |
|---|---|---|
| B-1 | `BrdRequirement` gains `kind`, `nfrCategory`, `criteria`, `sourceRef`; null `kind` loads as `FUNCTIONAL` | 1 |
| B-2 | `AcceptanceCriterion` gains `status`, `verification`, `lastVerifiedRunId`, `lastVerifiedCommit`, `lastVerifiedAt` | 1 |
| B-3 | New enums `RequirementKind`, `NfrCategory`, `CriterionStatus`, `CriterionState`, `StoryKind`, `StoryState`, `StoryOrigin`, `IterationState`; `RequirementRelation` gains `GATES` | 1 |
| B-4 | New `Story`, `Iteration`, `SourceDocument`, `SourceRef` domain classes, all `@DataModel` | 1 |
| B-5 | `Task` gains `storyId`, `criterionIds`, `selectedCandidateId`, `commitSha`; `Run` gains `storyId` | 1 |
| B-6 | `DesignDocument.brdRequirementIds` is included in `equals`/`hashCode` (bug fix) | 1 |
| B-7 | `StoreRoot` v7: `stories`, `iterations`, `tasks`, `sourceDocuments` with null-guard accessors; existing stores open without migration | 1 |
| B-8 | `ArtifactStore` CRUD + `copyOf(Story)`; every mutation path explicitly `store()`s the mutated object (lazy-store trap) | 1 |
| B-9 | Story `key` minting (`S1, S2, …`) is unique per project and stable across restarts | 1 |
| B-10 | All new `@DataModel` types round-trip through the zeroz4j serializer, verified by a server test **and** a TeaVM client build | 1 |

### Intake

| # | Requirement | Inc |
|---|---|---|
| B-11 | `POST /api/ingest` multipart endpoint, session-authenticated, 25 MB cap, returns a `SourceDocument` id | 2 |
| B-12 | Extractors: `.md/.txt/.adoc` passthrough, `.pdf` via PDFBox, `.docx` via POI | 2 |
| B-13 | Image extraction via `roles.vision`; missing config produces an explicit named error, not an obscure failure | 2 |
| B-14 | `SourceDocument` persisted with sha256, `extractedBy`, byte size; duplicate upload of an identical file is detected by sha | 2 |
| B-15 | Console upload affordance: drop zone + file picker in the BRD view and in chat | 2 |
| B-16 | Extracted requirements carry a `SourceRef` back to their source document and locator | 2 |
| B-17 | **Fix:** all agent BRD mutations use `saveBrd(brd, "agent", summary)` so agent edits appear in revision history | 2 |

### Agents

| # | Requirement | Inc |
|---|---|---|
| B-18 | `/brd` tools `add_criterion`, `update_criterion`, `get_source`; `add_requirement` gains kind/NFR category; `add_edge` accepts `gates` | 2 |
| B-19 | BRD persona extracts functional **and** non-functional requirements and interviews until each has a testable criterion | 2 |
| B-20 | Persona pushes back on unmeasurable NFRs and proposes a measurable restatement | 2 |
| B-21 | Agent-created criteria land as `PROPOSED`; only the operator promotes to `ACCEPTED` | 2 |
| B-22 | `/backlog` mode with the six story tools, entered/left symmetrically with `/brd` | 2 |
| B-23 | Backlog persona proposes thin vertical slices over whole criteria; refuses to invent requirement content | 2 |
| B-24 | Coverage report: `ACTIVE` requirements with no story, and unclaimed criteria | 2 |

### UI

| # | Requirement | Inc |
|---|---|---|
| B-25 | Backlog panel: collapsible, resizable, persisted width/state, Iteration → Story → Task tree | 3 |
| B-26 | Live status via `BacklogSignals.CURRENT` shared signal carrying domain objects (no DTOs) | 3 |
| B-27 | Server publishes a fresh `copyOf` on every mutation; verified by a test that a same-value republish is a no-op and a real change fires | 3 |
| B-28 | All new views implement `Disposable` and dispose their effects; verified by a leak test | 3 |
| B-29 | Story/task/criterion drill-down uses the existing `Inspector` stack | 3 |
| B-30 | Triage group with count badge; promote / merge / discard actions | 3 |
| B-31 | Full-width Backlog board tab with reorder, schedule and promote | 3 |
| B-32 | "Start session" on a `READY` story creates a run bound to it | 3 |
| B-33 | BRD editor: kind/NFR fields, criteria editor with live verification state, `GATES` edges, provenance links | 3 |

### Architect & planning

| # | Requirement | Inc |
|---|---|---|
| B-34 | `design()` no longer invents requirements; `DesignDocument.requirements` is populated from the story's BRD requirements and `brdRequirementIds` is written | 4 |
| B-35 | Architect receives inherited NFR gates as design constraints | 4 |
| B-36 | Missing-requirement proposals go to triage as `DRAFT`; silent invention is impossible | 4 |
| B-37 | `plan()` emits `criterionIds` per task, drawn from the story's slice | 4 |
| B-38 | `TaskGraphValidator` coverage check: every story criterion claimed, none claimed from outside — a violation, not a warning | 4 |
| B-39 | `fallbackGraph` produces one task claiming all story criteria | 4 |
| B-40 | Architect may add tasks in-scope freely; new stories/requirements/criteria require promotion | 4 |

### Delivery, gates and DoD

| # | Requirement | Inc |
|---|---|---|
| B-41 | Selected candidate's commit sha written to `Task.commitSha` + `selectedCandidateId` | 5 |
| B-42 | Integration sha written to `Story.integrationCommit`; `deliveredCommit` set | 5 |
| B-43 | Optional PR overlay recorded when a remote + `gh` are configured; absence never blocks | 5 |
| B-44 | NFR gate enforcement per the §2.6 table (ACTIVE+criterion blocks, DRAFT warns, ACTIVE without criterion warns and nags) | 5 |
| B-45 | Story → `REVIEW` on all tasks green + gates passing; never auto-`DONE` | 5 |
| B-46 | Operator **Accept** sets story `DONE`, stamps criteria `PASSING` with the commit | 5 |
| B-47 | Requirement auto-flips to `IMPLEMENTED` when all `ACCEPTED` criteria are `PASSING`; reverts to `ACTIVE` on regression | 5 |
| B-48 | Freeform chat runs mint an `AD_HOC` story so every run is visible in the backlog | 5 |
| B-49 | Story retains identity across retries; failed run returns it to `READY` or `BLOCKED` | 5 |

### History & change propagation

| # | Requirement | Inc |
|---|---|---|
| B-53 | `ChangeEvent` type + append-only per-project journal; events are never mutated or removed | 1 |
| B-54 | Every mutation of a requirement, criterion, story, iteration or task writes a `ChangeEvent` with actor attribution (`human` \| `agent` \| `system` \| `extraction`) and the originating run where applicable | 1 |
| B-55 | `CriterionVerification` append-only history; the criterion's `lastVerified*` fields are a cache of the head, the journal is the truth | 1 |
| B-56 | Deletion is always a tombstone — no store map ever loses an entry; guarded by a test | 1 |
| B-57 | `BrdRequirement.contentRevision`; criteria verified against an older content revision render as `STALE` rather than silently green | 1 |
| B-58 | Adding an `ACCEPTED` criterion to an `IMPLEMENTED` requirement reverts it to `ACTIVE`; retiring criteria recomputes status | 1 |
| B-59 | History views in the Inspector: requirement timeline, story timeline, criterion pass/fail history | 3 |
| B-60 | **All** BRD writers snapshot — including the agent path (the `saveBrd` fix), verified by a test | 2 |

### Polish

| # | Requirement | Inc |
|---|---|---|
| B-50 | End-to-end test: upload → requirements → story → run → commit → `IMPLEMENTED` | 6 |
| B-51 | Browser E2E: backlog panel renders, live status updates, accept flow works | 6 |
| B-52 | Docs updated: technical spec §4/§5/§14, console design §2/§3/§5, user manual, corrections log | 6 |

---

## 10. Increments

Each ends buildable, demoable and committed.

| Inc | Contents | Demo |
|---|---|---|
| **1** | Domain + store (B-1…B-10) | server test round-trips a story with criteria; client builds |
| **2** | Intake + agent surfaces (B-11…B-24) | drop a PDF, get a structured BRD with criteria, then a proposed backlog |
| **3** | Backlog panel + BRD editor (B-25…B-33) | see the backlog live while a run executes |
| **4** | Architect rewiring (B-34…B-40) | a story-scoped run whose design contains zero invented requirements |
| **5** | Delivery, gates, DoD (B-41…B-49) | accept a story; watch a requirement flip to `IMPLEMENTED` with its commit |
| **6** | Tests + docs (B-50…B-52) | green E2E, docs current |

---

## 11. Deliberately rejected

| Rejected | Why |
|---|---|
| Session = the high-level task | Work-item identity would die with the run; retries and splits would break the BRD→commit trace, which is the entire point |
| `Epic` as a class | Duplicates the `REFINES` hierarchy — a second truth to hand-maintain |
| Story-local acceptance criteria | Criteria archive with the story, leaving the requirement unverifiable; this is exactly how specs rot |
| Calendar sprints, story points, velocity | Estimation ceremony calibrated to human throughput; meaningless when a task completes in minutes, and the system already has real telemetry |
| PR-first git linkage | Requires a GitHub remote + auth on every run; the swarm never pushes today, and offline is the normal mode |
| Separate NFR register | Breaks traceability — "which NFRs did this commit have to satisfy?" becomes unanswerable |
| NFRs as ordinary stories | They either bloat into unverifiable prose ("make it fast") or silently vanish once "done" |
| Fully automatic story completion | "Tests pass" ≠ "requirement satisfied"; the BRD would start lying with no human noticing |
| Backlog-only runs (no freeform) | Every throwaway experiment would need backlog ceremony first |

---

## 12. Risks

| Risk | Mitigation |
|---|---|
| Criteria that cannot be expressed as tests | The agent interviews until they can; if it genuinely cannot, the criterion is `PROPOSED` and advisory rather than a blocking gate |
| Test id drift (renamed/moved test) | Criterion verification degrades to `UNVERIFIED` with a visible warning rather than silently reporting `PASSING` |
| Triage queue becomes a landfill | Count badge is always visible; the backlog persona reports triage size in its coverage report |
| Vision extraction hallucinating requirements | `extractedBy = vision` is surfaced in the UI; extracted requirements are `DRAFT` with a `SourceRef` and must be promoted by a human |
| Shared-signal payload growth (whole backlog on every change) | Same pattern as the BRD signal, which is already whole-document. Revisit with a delta topic if it becomes a problem — do not pre-optimise |
| Increment 4 changes the Architect's contract | Increment 1–3 leave the existing workflow untouched, so the rewiring lands against a working, observable backlog |

---

## 13. History, audit and change propagation

Added 2026-07-25 on the author's requirement that **all history is kept**, and to answer "is the BRD a
living document that the whole process picks up?" — yes, and this section is how.

### 13.1 Principle

> **Nothing is destroyed.** Every requirement, criterion, story, iteration and task keeps a complete,
> attributed record of how it got to its current state. Deletion is always a *tombstone*, never a
> removal.

### 13.2 Two mechanisms, deliberately not one

| Layer | Mechanism | Why |
|---|---|---|
| **BRD** (`Brd` + requirements + criteria + edges) | **Full snapshot per mutation** — the existing `BrdRevision`, already built | You want to *browse and restore* the document at any point in its evolution. That needs whole-document snapshots, and it already works. |
| **Backlog** (stories, iterations, tasks) and **criterion verification** | **Append-only event journal** | Work items do not need restore; they need an audit trail. Task state alone transitions ~6 times per task per run, so whole-aggregate snapshots would multiply the store for no benefit. |

This is not a compromise, it is the right shape for each: the BRD is a *document* (restore is
meaningful), the backlog is a *process* (the trail is meaningful).

**Not to be confused with `TraceHub`.** `TraceHub`/`AgentSessionRecord` records what *agents did*
(prompts, tool calls, responses). The journal here records what *the plan is and was* — requirements,
criteria, work items. Different questions, deliberately separate stores.

#### `ChangeEvent`

`id`, `projectId`, `at`, `actor` (`human` \| `agent` \| `system` \| `extraction`), `entityType`
(`REQUIREMENT` \| `CRITERION` \| `STORY` \| `ITERATION` \| `TASK`), `entityId`, `kind` (`CREATED` \|
`UPDATED` \| `STATE_CHANGED` \| `LINKED` \| `UNLINKED` \| `PROMOTED` \| `TOMBSTONED` \| `RESTORED`),
`field`, `before`, `after`, `summary`, `runId` (nullable — set when a run caused the change).

Append-only: events are never mutated and never removed. Stored per project as a list; every append
must `store()` the list explicitly (the lazy-store trap).

#### `CriterionVerification`

`id`, `criterionId`, `requirementId`, `runId`, `storyId`, `taskId`, `commitSha`, `result`
(`PASSING` \| `FAILING`), `testRef`, `at`.

One record per verification, append-only. The criterion's `verification` / `lastVerifiedRunId` /
`lastVerifiedCommit` / `lastVerifiedAt` fields are a **cache of the head** for cheap rendering; this
journal is the truth. It answers "when did this requirement start passing, and when did it regress?" —
which a single mutable status field cannot.

#### Tombstones

Stories go `CANCELLED`, criteria go `RETIRED`, requirements go `DEPRECATED`, iterations and projects
go archived. **No entry is ever removed from a `StoreRoot` map**, and a test guards it.

### 13.3 The living BRD loop

The BRD is never frozen. Requirements and criteria may be added, edited, retired or re-prioritised at
any point in the project's life, from any source, and the process picks the change up:

```
        discovery (you, a document, or a worker mid-run)
                          |
                          v
        DRAFT requirement / PROPOSED criterion
        (origin=DISCOVERED, originRunId, rationale)
                          |
                    [ Triage ]  <-- operator promotes
                          |
                          v
        ACTIVE requirement with ACCEPTED criteria
                          |
              coverage report: criteria unclaimed
                          |
                          v
        backlog agent proposes a story over them
                          |
                    [ operator promotes + schedules ]
                          |
                          v
             run -> tasks -> commit -> criteria PASSING
                          |
                          v
              requirement -> IMPLEMENTED (on evidence)
```

### 13.4 Change propagation rules

These are what make the loop honest rather than decorative:

| Change | Consequence |
|---|---|
| `ACCEPTED` criterion added to an `IMPLEMENTED` requirement | Requirement reverts to **`ACTIVE`** — it is no longer fully satisfied and must stop claiming it is. The new criterion is unclaimed and appears in the coverage gap. |
| Criterion regresses to `FAILING` | Requirement reverts `IMPLEMENTED` → `ACTIVE`, with the regressing run recorded |
| Criterion `RETIRED` | Requirement status recomputed; may become `IMPLEMENTED` if the remaining criteria all pass |
| Requirement text materially edited | `contentRevision` bumps. Criteria verified against an older `contentRevision` render **`STALE`** — "verified, but against older wording". Never silently keeps reporting green. |
| Requirement `DEPRECATED` | Not evaluated for gates. Any in-flight story delivering it is flagged for the operator; scheduled-but-unstarted stories are proposed for cancellation |
| New NFR with a `GATES` edge | Applies to **future** runs immediately. Existing `IMPLEMENTED` requirements under it are **not** retroactively marked green — the fitness criterion is `UNVERIFIED` and shows as a gap until something runs it |
| Requirement re-prioritised | Surfaces as a backlog ordering hint; never silently reorders a scheduled iteration |
| Story cancelled with tasks already delivered | Commits stay recorded on the tasks; the criteria keep their verification history. Work done is never un-recorded |

Every one of these writes a `ChangeEvent`, so the evolution of the project's *intent* — not just its
code — is replayable.

### 13.5 Retention

Unbounded by default. The journal stores text deltas and is small relative to candidate diffs and
session traces, which already dominate the store. If it ever becomes a problem, compact by summarising
events older than N runs — **do not pre-optimise this.**
