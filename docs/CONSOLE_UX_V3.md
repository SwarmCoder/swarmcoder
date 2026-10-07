# SwarmCoder Console — UX v3: The Pipeline

**Status:** APPROVED by the author, 2026-07-28, with one amendment applied below — story detail
opens in a dialog over the board, not a side panel. **IMPLEMENTED 2026-07-28, all six §9 steps**
(engine · pipeline board · navigation collapse · decisions folded onto cards · language pass ·
deletions). Supersedes the stage-based shell of CONSOLE_DESIGN_V2 §navigation and the 2026-07-27
stage restructure; where any older document conflicts with this one, this one wins.

**Known deviations from this document, all deliberate:** iterations and story kinds are cut from the
operator UI per §8.3 but their *services* remain (`addIteration`, `scheduleStory`) — data retained,
nothing reachable. `Stage`'s constants are still named `PLAN` and `BUILD` and its ids are still
`"plan"` / `"build"`: server-side `StageGuidance` is filed under them and the browser tests select on
`stage-<id>`, so renaming the keys would orphan the guidance and break the selectors for a cosmetic
gain. The three reference faces behind ⋯ (Guidelines, Insights, Setup) are still hosted as workspace
surfaces rather than dialogs — off the main path as §3 requires, reached only from the overflow.
**Author decisions (2026-07-28):** one pipeline board · two human gates · strict-per-slice with a
living requirements pool · auto-retry infrastructure outages · detail-on-demand in dialogs.
**The bar this design is measured against, in the author's words:** *"New users must not have to
think. It is an opinionated framework, not a free-for-all where anything can be done at any time by
superusers."*

---

## 1. Why a redesign, not another fix

Fifteen symptom-level fixes in two days did not make the flow intuitive, because the defects are
structural. Every complaint the author made traces to one of six diseases:

| # | Defect | Evidence | Rule this design enforces |
|---|---|---|---|
| D1 | The UI renders the machine, not the journey — internal states and transition verbs (DRAFT, Promote, Resolve) as UI | "What does promote do? I was just clicking randomly" | Internal names never appear. Each thing is in one of a handful of human situations |
| D2 | Things teleport — a story's location depends on its state, so acting on it makes it vanish | "It vanished! Now what?" — reported four separate times | **Nothing vanishes.** A story lives on one board from suggestion to delivery and only ever moves sideways, in view |
| D3 | The same fact derived twice — two screens, two liveness rules | "building now · 9h 5m" and "stopped", same story, same moment | One owner and one derivation per fact (`BuildState` is the pattern), rendered everywhere from the same code |
| D4 | Feedback lands where the user isn't looking, or lies | errors behind dialogs; success in red; "Resolve" that resolves nothing | Feedback adjacent to the action, coloured by outcome; movement shown by highlight in the new position, not by toast |
| D5 | A line drawn over a loop — the stepper promised forward motion; retries and re-analysis flow backwards | "I restarted S1… nothing is happening" | The loop is drawn honestly: backflows are named events on the board, not disappearances |
| D6 | Concept overload — ~12 nouns before the first build | requirement, criterion, story, iteration, kind, run, session, worker, task, candidate, decision, approval | A concept budget of **four nouns**. Everything else is internal detail or deleted |

What earned its keep and is retained: the guided wizards (document intake, planning), the
plain-language situation cards with one primary action, the working / needs-you / stopped
vocabulary, spend confirmation before dispatching agents, per-question Discuss, and the
requirement-objects backbone.

## 2. The model

### 2.1 Four nouns

| Noun | What it is | What it absorbs |
|---|---|---|
| **Document** | Raw material you upload or paste | — |
| **Requirement** | A Java object stating what must be true, carrying its **checks** (acceptance criteria) | criterion (becomes "check", a part of a requirement, not a peer noun) |
| **Story** | A slice of work that delivers named checks | iteration (cut from UI), story kind (cut from UI — enablers render as ordinary stories) |
| **Build** | One attempt at a story | run, session, worker, task, candidate, decision, approval — all become internal detail *inside* a build, visible on demand, never navigation destinations |

The requirements backbone is unchanged and explicitly reaffirmed: requirements and checks are
persisted Java objects; stories claim check **IDs**, not prose; workers receive the exact checks
they must satisfy; acceptance stamps verification against the wording revision it passed against,
so an edit automatically marks stale evidence. Documents are converted into objects and then stop
being the source of truth.

### 2.2 The process: a living pool feeding frozen slices

This is the resolution of "waterfall BRD" vs "agile flexibility":

- **The requirements pool is always open.** New documents, re-analysis, mid-build discoveries land
  as drafts at any time. Nothing about being "in the build phase" closes intake.
- **Each slice is strict.** Agreeing a requirement makes it plannable; once a story claims its
  checks and is accepted, that slice is protected: editing a claimed requirement is permitted but
  is a deliberate act that shows impact ("2 passing checks go stale; S2 will be sent back") and
  requires confirmation. In-flight work is never churned silently.
- **Parking is first-class.** A draft that isn't ready doesn't block anything and doesn't nag
  forever: "not in this scope" moves it aside, visibly, reversibly.

### 2.3 Two human gates, and an automated middle

The author decides exactly twice per story:

1. **Accept as work** — "this suggestion is real." (Also where "Not needed" lives.)
2. **Judge the delivery** — "did it do what was asked?" Accept, or send back with a note.

Everything between is the machine's job:

- **Build** is one click on an agreed story (spending money stays a deliberate act; the existing
  confirm dialog is kept, slimmed).
- **The run-internal APPROVAL parking state is abolished.** When quality gates are green the build
  integrates and comes back for judgment on its own. When gates are not green, that is a genuine
  question and surfaces as *needs you* on the story card — with the deciding buttons on the card.
  (This kills permanently the class of bug where runs parked at a gate no reachable button could
  open.)
- **Blocked-task decisions, budget extensions and every other mid-build question** surface the same
  way: as the story's situation, on its card, with its buttons. The standalone Approval Center is
  retired as a destination; its history is reachable from the build detail.

### 2.4 Failure policy

The engine must distinguish **"the model said no"** from **"the model was not there"**:

- **Checked before a build starts, not only when one fails** (added 2026-07-30). Promoting a story asks
  the worker endpoints one cheap question first; if they are configured and none answer, the build pauses
  before a single worker is launched. The reactive path below still exists for an endpoint that dies
  mid-build, but it is no longer how an already-down endpoint gets discovered — that used to cost a
  worktree and a container per candidate, N traced sessions, and N `ENDPOINT_OUTAGE` candidates written
  into what is also the evaluation dataset. An endpoint that is merely *unconfigured* is not an outage
  and never pauses anything: some setups reach their workers by a route the Console cannot see. The
  health dot and this check read the same `EndpointReachability`, so they cannot disagree.
- Infrastructure outage (endpoint unreachable, timeout storms): the build **pauses**, retries
  automatically, resumes when the endpoint returns. It never consumes the repair budget, never
  blocks the story, and the card says plainly: *"paused — your model server (Spark) is not
  answering; building resumes by itself when it returns."* The Setup indicator goes red and the
  card links to it. If the outage outlasts a generous threshold (~30 min), the card escalates to
  *needs you*: keep waiting or stop.
- Genuine failure (gates failed, zero survivors on the merits): *stopped*, with why, and **Build it
  again** — as already built.
- A story may never claim to be building unless something is verifiably driving it (heartbeat rule,
  already built — retained as an invariant).

## 3. The surfaces

Navigation collapses to **two workspaces** plus a status header. The stage stepper is deleted —
with one board, process position is visible *on* the board, so navigation no longer needs to
pretend to be a progress meter.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ IT Assessment ▾   Requirements: 12 agreed · 4 drafts   Pipeline: 2 building, │
│                                                        1 needs you    ⚙ ok   │
│ ▸ Next: S3 came back — judge its delivery                    [Open pipeline] │
├──────────────────────────────────────────────────────────────────────────────┤
│   [ Requirements ]   [ Pipeline ]                            ⌕  💬  ⋯        │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **Status header** — project switcher (the permanent multi-project rail is demoted to this menu),
  live counts, health dot, and **one** guidance line: the single next step, server-computed
  (retargets the existing StageGuidance machinery). One line, one button, gone when there is
  nothing to say.
- **Requirements** — the pool: documents, the intake wizard, the graph, the editor, Knowledge as
  its collapsible reference pane. Largely as already built; language changes only ("Agree" not
  "Promote"; "check" not "criterion"; slice-pinning warnings as in §2.2).
- **Pipeline** — the centerpiece, below.
- **⋯ overflow** — Guidelines, Insights, Settings, developer tools. Present, never on the main path.
- **Chat** — the companion dock, unchanged, available everywhere.

### 3.1 The Pipeline board

One screen. Five columns. **A story appears when the planner suggests it and never leaves this
board until it is delivered or dropped. It only moves sideways.**

```
┌ SUGGESTED ──┬ READY TO BUILD ─┬ BUILDING ────────┬ CAME BACK ─────┬ DELIVERED ┐
│ The planner │ Agreed work.    │ Agents at work.  │ Judge it.      │ Done.     │
│ wrote these.│ Build when      │ Nothing to do    │ Did it deliver │           │
│ Real work?  │ ready.          │ unless it asks.  │ what was asked?│           │
│             │                 │                  │                │           │
│ S5 report   │ S2 scoring      │ S1 ⚙ building    │ S3 ⏸ needs you │ S4 ✓      │
│ [Accept]    │ [Build this]    │   writing code   │  commit a1b2c3 │           │
│  Not needed │  Drop           │   4 min · 12k tok│  [Accept]      │           │
│ S6 caps     │                 │ S7 ⏸ paused      │  [Send back]   │           │
│ [Accept]    │                 │   Spark is down, │                │           │
│             │                 │   auto-resuming  │                │           │
└─────────────┴─────────────────┴──────────────────┴────────────────┴───────────┘
  Plan stories ▸        Settled: 3 delivered · 1 dropped (below, counted)
```

Rules:

- **Columns map 1:1 to lifecycle**; the health of a building story (working / paused / stopped /
  needs-you) is a **badge and sentence on its card**, never a different location. Each column
  carries its one-line question as a caption, so the board is readable from its headings alone.
- **Every card**: key, title, its situation in plain words, and **one primary action** (quiet
  secondaries). Exactly the card grammar already proven on the Plan board.
- **Movement is shown, not narrated**: when an action moves a card, it highlights briefly in its
  new column. On one board this replaces the entire family of "S1 has left this list…" messages.
- **Building cards** show the phase in operator words ("writing the tests it must pass"), elapsed
  time, and cost. Clicking any card opens its **story dialog** over the board (§3.2) — the board
  keeps its full width and stays visibly behind the dialog, so studying detail costs no screen
  real estate and no navigation. The swarm spectacle lives *inside* the story it belongs to.
- **Empty states teach.** Each empty column says what fills it and offers the button, including the
  cross-reference when the prerequisite is elsewhere ("Stories are planned from agreed
  requirements → Open Requirements"). This replaces the first-run tutorial nobody reads.
- **Plan stories** (the existing wizard) fills SUGGESTED from unclaimed checks; it is the board's
  header action.

### 3.2 The story dialog (detail on demand)

Author decision 2026-07-28: detail opens in a **dialog over the pipeline**, not a docked panel.
Depth must cost nothing when you are not using it — a permanent panel taxes every board visit to
serve the occasional deep look, and the dialog pattern is already proven here (the run graph, the
wizards). Implementation notes the developer agent must honour: the wide-modal idiom already in
the codebase; Close as the primary action (a modal blocks the page — that is what a modal is for,
and an unclosable one traps the whole Console, which the smoke test now pins); and any view with
signal-bound effects mounted inside it is disposed on every close path, or each peek leaks an
effect on a process-wide signal (the RunView lesson).

```
┌ S3 · Adaptive surfacing ────────────────────────────────┐
│ suggested → agreed → building → CAME BACK               │
│ A build finished and delivered commit a1b2c3.           │
│   [ Accept delivery ]   [ Send back ]                   │
│ Delivers 5 checks: 4 verified · 1 unverified            │
│ Attempts: #2 today (14:02, 9 workers, 34k tok) · #1 ✗   │
│ Why #1 stopped: Spark outage — auto-recovered           │
└─────────────────────────────────────────────────────────┘
```

The dialog is the *only* place internal depth exists: attempts, workers, diffs, past decisions,
change journal. Depth is reachable in one click, mandatory for none, and gone the moment it is
closed — the board underneath never gave up an inch for it.

### 3.3 Setup

Setup stops being a stage. It is the health dot in the header: green with everything configured,
red with a sentence and a fix-it panel when not. A brand-new install opens straight into a
first-project dialog; after that the pipeline's empty states carry the guidance.

## 4. Language

No internal identifier ever reaches the operator. The mapping is normative:

| Internal | Operator sees |
|---|---|
| DRAFT (story) | suggested |
| READY | ready to build |
| RUNNING | building (with phase phrase) / paused / stopped — per `BuildState` |
| REVIEW | came back — judge it |
| BLOCKED / stranded | stopped, with why |
| CANCELLED | dropped |
| DRAFT/ACTIVE (requirement) | draft / agreed |
| AcceptanceCriterion | check |
| Run / session / worker | build / (inside a build) workers |
| RunState phases | "reading the story", "working out a design", "writing the tests it must pass", "writing the code", "putting the pieces together" |
| APPROVAL | *(state abolished — see §2.3)* |
| Decision / Resolve | the story's question, answered on its card |

Transition verbs are banned as button labels. Buttons say what the operator is doing: **Accept as
work · Build this story · Accept delivery · Send back · Build it again · Not needed · Drop**.

## 5. Hard rules (invariants for the developer agent)

1. **Nothing vanishes.** No action may remove a thing from view without its new position being
   visible on the same screen. Deletion/dropping keeps a counted, openable record.
2. **One derivation per fact.** Any state shown in two places is computed in one shared module
   (`BuildState` pattern). A second derivation is a defect even if it currently agrees.
3. **No internal names in the UI.** §4 is the complete vocabulary; additions require updating §4.
4. **Feedback is adjacent and honest.** Outcome-coloured, next to the control, naming what happened.
   A silent success is a defect; a success styled as an error is a defect.
5. **Nothing claims to be running unless something is driving it** (heartbeat-checked), and every
   gate carries its button. A state with no exit reachable from its own card is a defect.
6. **Money is deliberate.** Anything that dispatches agents confirms first and states cost basis.
   Auto-retry after outages is the one exception, and it caps at the original budget.
7. **The machinery already known to bite** (documented in code, learned here): RMI only from
   zeroz4j DOM handlers or `new Thread` (six incidents); signal dedup requires fresh copies and
   accessor-based equals; persisted/wire enums are append-only (one startup outage); hand-rolled
   serializers append in both methods; stale `~/.m2` jars require full-reactor builds.

## 6. What is deleted

- The stage stepper and `Stage`/`StageBar` navigation; `NextStepBar` instances (machinery
  retargeted to the single header guidance line).
- The separate Plan and Build workspaces (merged into Pipeline); the standalone Approval Center
  tab; the already-deleted runs rail and swarm board stay deleted.
- `RunState.APPROVAL` parking (engine change) and the Decision queue as an operator surface.
- Iterations and story kinds from all operator UI (data model retained).
- All remaining "where did it go" toasts — superseded by on-board movement.

## 7. Walkthroughs (the proof of the design)

**Day one, new user.** Create project (one dialog) → header says *"Next: add the documents your
requirements come from"* → Requirements opens with the intake wizard's empty state → analyse →
agree requirements (one button for all drafts) → header: *"Next: plan stories"* → Pipeline,
SUGGESTED fills → Accept the real ones → Build → watch the card, badge by badge → it slides to
CAME BACK → Accept delivery. At no point did they choose among surfaces: every step was the next
button on the thing they were looking at.

**The outage day (re-run of the worst session).** Spark dies mid-build. Cards flip to *paused —
auto-resuming*, header dot goes red, nothing blocks, no decision queue fills. Spark returns;
building resumes; the only trace is a line in each story's history. What previously produced 37
dead approvals, three stranded stories and a day of confusion produces nothing to do at all.

**Changing a requirement mid-build.** Author edits an agreed requirement whose checks S2 (building)
claims. The editor warns: *"2 passing checks go stale; S2 will be sent back to Ready."* Confirm →
S2's card visibly moves left with a note. The loop, drawn honestly.

## 8. Assumptions taken (veto any at review)

1. **Audience**: a colleague with zero context must succeed on day one (drives the empty-state
   teaching and language bar).
2. ~~Assumption~~ **Decided 2026-07-28:** swarm/build detail is on-demand, in the story dialog —
   not a primary spectacle surface and not a docked panel.
3. **Iterations and story kinds are cut from the UI** (data retained for the future).
4. **Multi-project demotes** to a header switcher; no permanent rail.
5. **The end state is the DELIVERED column** plus each story's dossier (commit links, verified
   checks). No release/changelog surface in this version.
6. **Chat stays a companion**, never a required path.

## 9. Build order for the developer agent (each step shippable)

1. **Engine first**: outage-vs-refusal distinction with auto-resume; abolish APPROVAL parking
   (auto-integrate on green gates, surface non-green as a story question). The UX promises depend
   on these two.
2. **Pipeline board**: merge BacklogBoard + BuildStage into one board reusing `BuildState`, the
   card grammar, and existing dialogs; movement highlighting.
3. **Navigation collapse**: two workspaces + status header + single guidance line; delete the
   stepper; retarget readiness/guidance publishing.
4. **Fold decisions into story cards/panel**; retire the Approval Center tab.
5. **Language pass** (§4 complete), empty-state teaching, story dialog depth.
6. **Delete** dead surfaces; update the four browser tests to pin the new invariants (especially
   rule 1: an acted-on story is still on screen).
