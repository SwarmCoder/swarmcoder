# SwarmCoder — the map of every flow, and what is actually tested

**Written 2026-08-28, from the code rather than from the design documents.**

Yesterday the main path was proved end to end against a live model for the first time: a
document goes in, a requirement comes out marked delivered, with a real build and a real commit
behind it. That is one path. This document lists every other one.

It is a map, not a test plan. For each thing it says what it does, where it lives, whether
anything tests it, and — where nothing does — what a test would have to do. Section 12 lists the
defects found while writing it; nothing was fixed, because other work is in flight. Section 13 is
the shortlist to act on.

**The order is by damage, not by module.** The things at the top are the ones that would cost the
most if they were broken, and most of them are untested.

---

## How coverage is stated

Every entry carries exactly one of these words, and anything above "untested" names its evidence.

| Word | Means |
|---|---|
| **Proved live** | Run against the real model server. The test or the recorded run is named. |
| **Proved offline** | Run by a test with scripted model replies and real everything else — real store, real git, a real Maven build, real verification. The test is named. |
| **Asserted only** | A unit or service test touches it, but nothing drives it the way a person or an agent would. |
| **Looked at** | It appears in a screenshot somebody examined. Nothing asserts it. |
| **Untested** | Nothing. |
| **Dormant** | The code exists and nothing reaches it in a normal run. Says whether that is deliberate, and where it says so. |

**This was true when the document was written and is no longer.** Thirteen test classes and four
further test methods ran only when somebody typed a flag, and nobody ever did. Among them was the
entire browser-based test of the operator console — the only thing that has ever driven the user
interface — so nothing in a normal build touched the console's 16,000 lines of screen code at all.

**Changed 2026-08-28.** Nine of those thirteen classes now run in an ordinary build with no flag,
including all six console browser tests. Every one of them passed once switched on, after one
harness fix. Four stay off, each for a reason that is stated where it is declared and repeated
below, and every skip is now announced in the build output and written to
`<module>/target/tests-not-run.txt`, so a green build says what it did not check. **The rest of
this document still describes coverage as it was**, so where an entry below says a browser test is
opt-in, read it as running.

| Was gated by | Now |
|---|---|
| `-Dswarmcoder.browser.tests=true` | **runs** wherever Playwright's Chromium is installed — six console browser tests plus the browser-check verifier |
| `SWARMCODER_SANDBOX_TESTS=true` | **runs** wherever a Docker daemon and the worker image are present |
| `SWARMCODER_REAL_JUNIT=true` | **runs** wherever Maven is on the PATH |
| `-Dswarmcoder.live.baseUrl=<url>` | **still opt-in** — the model server is shared with other work and must not become a build dependency |
| `SWARMCODER_CONFIG_E2E=true` | **still opt-in** — it spends real money |
| `-Dswarmcoder.demo.repo=<path>` | **still opt-in** — the repository it wants is not in this checkout |

Full account: `docs/TESTING.md`.

---

## Read this first — the six that would hurt most

1. **~~Four of the six kinds of run do nothing at all and report success.~~** FIXED 2026-08-28,
   corrections §20: bugfix and refactor now run the one real delivery path with a kind-specific
   brief; docs and analysis are removed from the product and cannot be started; and a run with no
   plan behind it can no longer be recorded as delivered by any route. (§2)

2. **~~After a restart, an unfinished run is continued using the wrong project's repository.~~**
   FIXED 2026-08-28, corrections §20: resume routes each run through its own project's engine, the
   constructor that dropped a run's project no longer exists, and a project that cannot be opened
   has its runs left alone rather than resumed elsewhere. (§6, §12)

3. **Restoring an earlier version of the requirements document destroys every check on every
   requirement.** One unlabelled clock-icon button, no confirmation. The restore code rebuilds
   each requirement without its checks, its kind, its source document or its revision counter, and
   every piece of verification evidence goes with them. Nothing tests it. (§3.1)

4. **A brand-new installation cannot start.** With no `~/.swarmcoder/config.yaml` the application
   throws at boot and exits. Nothing writes a starter file. The design says a new install opens
   into a first-project dialog; it never gets that far. (§10)

5. **The whole operator console is untested in any normal build.** Six good opt-in browser tests
   cover the shell, the requirements screens, the pipeline board, both wizards, three dialogs and
   the empty state. They are off. (§4)

6. **Ten more requirement and document actions delete things permanently with no confirmation and
   no test** — delete a requirement, delete a check, delete a relationship, forget every
   hand-placed diagram position, delete an uploaded document, clear an analysis, discard a plan.
   (§3.2)

---

## 1. The delivery spine — the one path that is proved

| Step | What happens | Where | Coverage |
|---|---|---|---|
| Upload a document | PDF, Word, Markdown, text, AsciiDoc, reStructuredText, CSV or an image is read into text | `sc-console/DocumentExtractor`, `DocumentUploadHandler` | **Proved offline** — `DocumentExtractorTest` builds real PDFs and Word files and reads them back; `DocumentUploadTest` |
| The analyst turns it into requirements | asks questions, proposes requirements with checks and the name of the test that will prove each | `sc-console/RequirementsIntake`, `GuidedFlowServiceImpl` | **Proved live** — `FullProductDressRehearsalTest.theSameJourneyAgainstALiveModel`, run thirteen times on 2026-08-28 |
| The operator agrees them | nothing an agent writes counts until a person presses Apply and agrees each requirement | `BrdServiceImpl.promoteRequirement` | **Proved live**, same test |
| The planner slices stories | each story claims specific check ids, never a paraphrase | `sc-console/BacklogPlanning`, `PlanningFlowServiceImpl` | **Proved live**, same test |
| The architect designs, is reviewed, then plans | design, one review round, then a task graph with write sets and claimed checks | `sc-workflow/ArchitectClient`, `DesignReviewerClient`, `TaskGraphValidator` | **Proved live**; **proved offline** by `FrontHalfWorkflowTest`, `ArchitectScopedDesignTest`, `TaskGraphValidatorTest` |
| The test author writes the acceptance tests first | writes into a protected directory, reports which test proves which check, and is audited against what is on disk | `TestAuthorClient`, `AuthoredTestAudit` | **Proved live**; **proved offline** by `TestAuthorClientTest`, `AuthoredTestAuditTest` |
| The red-check | the acceptance tests must genuinely fail before any worker starts | `sc-verify/RedChecker` | **Proved offline** — `RedCheckerTest`, and the live journey |
| N workers attempt each task in their own git worktrees | six tools each, real branches | `sc-swarm/SwarmDispatcher`, `WorkerLoop`, `WorkerToolbox` | **Proved live** at N=2; **proved offline** at N=2 by `SwarmEngineFakeVllmTest`. The last ten-worker run was 2026-07-13 |
| Every candidate is verified by a real build | compile, acceptance tests, existing tests, lint — cheapest first, fail fast | `sc-verify/CommandPipelineVerifier` | **Proved live** with real Maven and real surefire XML |
| Survivors are clustered, judged, one selected | | `SyntacticClusterer`, `JudgeClient`, `SelectionLogic` | **Proved offline**; `SelectionLogic` has no test of its own |
| The winner is merged and the merge verified again | plus a secret scan and an audit of every path the change touched | `sc-workflow/FinalIntegrator` | **Proved offline** — `FinalIntegratorTest` over a real git repository, including all three ways it parks |
| The story comes back for judgment; the operator accepts | acceptance stamps each check with the commit and the wording it passed against | `BacklogServiceImpl.acceptStory` | **Proved live**; **proved offline** by `DeliveryHandbackTest`, `BacklogServiceTest` |
| The requirement is marked delivered | only when every agreed check passes | `CriterionEvidence` | **Proved live**; **proved offline** by `CriterionEvidenceTest` and `RealJUnitCriterionMatchingTest` against surefire output a real Maven run produced |

**What the live proof does and does not say.** Eight of thirteen runs passed; the five failures
produced four real defects, all since fixed. Green runs took about two and a half minutes. It was
proved with **two** workers on **one** small task in a **one-class demo repository**. It has never
been run against a real codebase, and the ten-worker configuration has not been exercised since
July.

---

## 2. The four run kinds that do nothing and report success

The chat offers five ways to start a run. One of them works.

`GreenfieldWorkflow` is the real one. `BugfixWorkflow`, `RefactorWorkflow`, `DocsWorkflow` and
`AnalysisWorkflow` (all in `sc-workflow`) are about fifty lines each and every one does the same
thing: it renames the run's state a few times — bugfix through `REPRODUCE`, refactor through
`CHARACTERIZE`, docs through `DESIGN` and `PLAN` — with no code behind those names. No architect
runs. No plan is produced. No test is written. Then it calls the swarm engine, which finds no task
graph (nothing ever made one), logs `No task graph found for run …`, returns, and the workflow
marks the run **DELIVERED**.

**Coverage: untested.** No test file mentions any of the four classes. Every one of the twenty-two
places the test suite names a run kind names greenfield.

Reachable from the chat composer: `/bugfix`, `/fix`, `/refactor`, `/docs`, `/analyze`,
`/analysis`. All are listed in the chat's own command menu, which tells the operator they start a
run of that kind. The user manual describes all four as working features with their own stages.

**A test would have to** start each of the four kinds from a goal string against a real store and
assert the run cannot reach delivered without a task graph, a design and at least one verified
candidate. Making them refuse is cheaper than building them; either way the test is the same test.

---

## 3. Destructive operator actions

Fifteen controls in the console destroy something. Two are guarded. One is a data-loss defect.

### 3.1 Restore a previous version of the requirements document — the worst thing in here

**What it does.** The clock icon in the Requirements toolbar lists every past revision; each row
has a small restore arrow that replaces the live requirements document with that revision.

**Where.** `sc-console-ui/BrdView` history pane; server in `sc-console/BrdServiceImpl.restore`.

**What is broken.** The restore loop rebuilds each requirement through a seven-argument
constructor taking only id, handle, title, statement, priority, status and category. A requirement
also owns its **checks**, its functional/non-functional kind, its quality category, the document
it came from, and the revision counter that decides whether existing evidence is stale. None of
those five are copied. Restoring any revision therefore empties every check on every requirement
in the project, and every check carries the record of the commit and the test run that proved it.
Hand-placed diagram positions are lost too.

**Coverage: untested.** No test anywhere mentions `restore` or `revisionAt`.

**Guarding: none.** One click, no confirmation, no preview.

**A test would have to** create a requirement with checks, save a second revision, restore the
first, and assert the checks and their verification history survive — and that the operator is
told what will change before it happens.

### 3.2 The others, all unconfirmed and untested

| Action | Where | What it destroys | Coverage |
|---|---|---|---|
| Delete a requirement | Requirements editor | the requirement, every relationship touching it, its diagram position | **Untested** |
| Delete a check | Requirements editor, per row | the check and its verification history | **Untested** |
| Delete a relationship | Requirements editor, edges panel | the edge. The server's answer is discarded, so a refusal is invisible | **Untested** |
| Re-layout the diagram | Requirements toolbar | every hand-placed node position in the project | **Asserted only** — `BrdNodePositionTest` proves re-layout forgets positions; nothing drives the button |
| Delete an uploaded document | intake wizard, document pool | the document, project-wide | **Untested** |
| Clear documents | intake wizard header | every document, question and unapplied proposal, from any state | **Untested** |
| Discard this plan | planning wizard header | the same, for story planning | **Untested** |
| Delete a knowledge entry | Knowledge pane | the entry | **Untested** |

### 3.3 The two that are guarded

**Delete a project.** Behind a "Danger zone", a preview of what will be destroyed, and a field in
which the operator must type the project's name; the server re-checks it. The folder on disk is
not touched. **Proved offline** — `ProjectDeleteStoreTest` asserts every store root is cleared for
that project and a second project is untouched; `ConsoleServicesTest` covers the typed-name gate.
The screen is **looked at** in `ConsoleBrowserSmokeTest`.

**Drop a story, send a story back, build a story.** All three go through a confirmation naming the
consequence, including "There is no undo." **Proved offline** — `BacklogServiceTest` for the
rules, `ConsoleBacklogBrowserTest` (opt-in) for the dialogs.

---

## 4. The console, screen by screen

The console is the only operator surface: a Java client compiled to JavaScript in `sc-console-ui`,
services in `sc-console`, and a contract of nine services and about ninety methods in
`sc-console-api`.

**Blanket coverage statement.** The client has **no test of its own**. Its only coverage is six
Playwright tests in `sc-console` which boot the real compiled client in headless Chrome against
real services over a real store. **Since 2026-08-28 all six run in an ordinary build** on any
machine with Playwright's Chromium; they used to need a flag nobody set. Where a screen below says
"looked at", that means one of those six takes a screenshot of it.

Only one browser test may live in a class: the framework's shared-signal machinery binds to the
first server started in a process, so a second one in the same process serves pages that never
receive updates. Hence six classes with one test each, each in its own forked process. That is a
real limit on how cheaply UI coverage can grow.

### 4.1 The shell

| Control | Does | Coverage |
|---|---|---|
| Project chip → Projects dialog | switch project, create, per-project settings, delete | **Looked at**; switching is **proved offline** in `ConsoleServicesTest` |
| Ctrl/⌘+K command palette | new chat, theme, eight views, every chat, every run | **Untested** — no test opens it |
| ⋯ overflow | Guidelines, Insights, Setup, Settings, and two developer views | **Looked at** — the smoke test asserts Approvals is absent and the developer entries appear only after the toggle |
| Workspace tabs: Requirements, Pipeline | the only two destinations | **Looked at** |
| Header counts and the single guidance line | server-computed next step | **Proved offline** for the server (`StageGuidanceTest`, 12 cases); the rendering is **looked at** |
| Health dot | green/red, opens Setup | **Looked at** (`ConsoleReadinessBrowserTest`). The service behind it, `ConsoleHealthServiceImpl`, has **no direct test** |
| Chat dock toggle, inspector toggle, draggable dividers | layout only, remembered in the browser | **Untested** |

Keyboard shortcuts, the complete list: **Ctrl/⌘+K** opens the palette; **↑ ↓ Enter Esc** inside
it; **Enter** sends in the chat composer, **Shift+Enter** newlines, **Esc** closes a suggestion
list; **Ctrl+V** of an image posts it to the chat; **Esc** closes any dialog. Nothing else. **All
untested** except that a dialog closes with Escape, which the smoke test does once.

There is **no right-click menu anywhere** in the operator interface. The only context menu in the
codebase is a demo in the component gallery whose three items do nothing.

### 4.2 Requirements

- **The list** — a search box and seven filter chips: draft, agreed, delivered, quality, no story
  yet, needs re-checking, misplaced. Rows expand a coverage breakdown. **Proved offline** for the
  query (`RequirementRowsTest`, 502 lines, plus `RequirementRowsWireTest`); **looked at** for the
  screen (`ConsoleBrdBrowserTest`).
  **A gap worth naming: a row cannot be opened for editing.** The only way into the editor is the
  diagram view; the list's own empty state says so.

- **The diagram** — pan, zoom, and drag a requirement to a new position, which is saved. **Proved
  offline** for the saving (`BrdNodePositionTest`); the drag gesture is **untested**.

- **The editor** — the manual path for creating and changing a requirement by hand. Title,
  statement, priority, status, functional/non-functional, quality category; Save, Agree this
  requirement, Cancel, Delete. **Proved offline** for save and agree (`ConsoleServicesTest`,
  `BrdIntakeAndBacklogTest`, and the dress rehearsal, which retypes a test name in the editor on
  purpose); **looked at** for hand-creating one (`ConsoleBrdBrowserTest` fills the form and
  asserts the node appears).

- **Checks** are added, edited (statement, test name, proposed/agreed/retired) and deleted here. A
  check whose test name came from the analyst carries a warning until a person changes it.
  **Proved live** — the live journey asserts both origins and that saving a row unchanged does not
  silently claim the operator's authorship.

- **Relationships between requirements** are edited here: pick a relation (depends on, refines,
  conflicts with, derived from, gates), pick a target, Add edge; each edge has a delete cross.
  **Proved offline** for the rules (`RequirementTreeTest`, `StoryScopeTest`,
  `RequirementsIntakeRelationshipsTest`); the editor controls are **untested**, and the delete
  discards the server's answer.

- **History and restore** — §3.1. **Untested.**

- **The Knowledge pane** lists knowledge entries, lets one be written by hand, accepted or
  deleted, and has a Research button that sends an agent to the web. **Looked at** — the smoke
  test accepts a proposed entry and presses Research. The services are **proved offline**
  (`ConsoleServicesTest`, `ResearcherAgentTest`).

### 4.3 Pipeline

One board, five columns: suggested, ready to build, building, came back, delivered. A story
appears when the planner suggests it and moves sideways until delivered or dropped.

| Card action | Server call | Coverage |
|---|---|---|
| Accept as work | `promoteStory` | **Proved offline** — `BacklogServiceTest`; `ConsoleBacklogBrowserTest` walks the whole column sequence |
| Build this story (with a confirm naming the cost) | `startSession` | **Proved live** — the dress rehearsal starts every story this way |
| Accept delivery | `acceptStory` | **Proved live** |
| Send it back / Build it again / Stop waiting and take it back | `retryStory` | **Asserted only** — the state rules are tested; nothing exercises taking back a build that is genuinely still running. `ConsoleStoppedStoryQuestionsBrowserTest` **looks at** it being pressed on a stopped story with seven questions still unanswered |
| Drop this story | `cancelStory` | **Proved offline** |
| Answer this question / Answer these N questions | `resolveDecision` | **Proved offline** (`ConsoleServicesTest`); the card is **looked at**. Never a gate: the story's own action sits beside it as a quiet secondary, pinned by `ConsoleStoppedStoryQuestionsBrowserTest` |
| Open the run | run graph dialog | **Looked at** |
| Card head → story dialog: attempts, commits, tasks, question history, change history | | **Looked at** |

The board also shows **loose runs** — a run started from the chat with no story behind it, which
gets a card in "building" with only "open the run". **Untested.**

### 4.4 The chat

A companion, never a required path, but the only door to four things.

Controls: send, stop, regenerate the last reply, fork the conversation, pick a model for this
chat, search within the conversation, attach a document, paste an image, `@` to reference a file,
`/` to pick a command.

Slash commands: `/run` (and `/greenfield`), `/bugfix` (and `/fix`), `/refactor`, `/docs`,
`/analyze` (and `/analysis`). **`/run` is the "freeform chat mints its own story" path** — it
starts a greenfield run bound to no story. The other four are §2.

The chat agent has five tools of its own: read a file, list a folder, search the code, look up
documentation, fetch the source of a stored object.

**Coverage.** `ConsoleServicesTest` covers send, the run command, fork, regenerate, the model
picker, the `@` file reference and the analyst's tool loop — **proved offline**. The chat's own
orchestrator class, `ChatOrchestrator`, is named by no test. Rename and archive a chat exist on
the wire and no screen calls them.

### 4.5 Settings

Three tabs behind the ⋯ overflow.

**Roles** — one row per role with a base URL, a model name, an API key, a thinking default, and a
fold-out of eleven per-model settings: which known model shape to start from, longest answer in
tokens, the name of the setting that turns reasoning off, the text that turns reasoning off, how
past tool calls are sent, whether asking for JSON back is honoured, HTTP version, the context the
server was started with, the context one job may use, memory per token, requests served at once,
and whether these have been measured. Worker models can be added and removed.
**Proved offline** for the resolution (`ModelQuirksConfigTest`; `ModelQuirksRequestTest` asserts
what actually goes on the wire per model, with two models in one process; `DependencyGraphStartupTest`;
`RoleEntryWireTest`). The form is **looked at** once.
*Defect: a non-numeric entry in any numeric field silently becomes zero, which the resolver reads
as "use the shape's value". No message.*

**Budgets** — three fields: max cloud tokens per run, max local tokens per task, wall-clock
ceiling in hours. **Only the first does anything** (§9). **Untested.**

**Advanced and developer** — a checkbox revealing the Prompt Lab and the component gallery, and a
text box holding the whole raw configuration file with Reload and Save. The server validates it
parses before writing. **Proved offline** (`ConsoleServicesTest`); **looked at** once.

Two roles appear in this form and are read by nothing: `librarian` and `approver`. Editing them
does nothing.

### 4.6 The reference and developer screens

| Screen | Does | Coverage |
|---|---|---|
| Setup | a three-step read-only checklist: a project, a git repository, somewhere to run | **Looked at** (`ConsoleReadinessBrowserTest`) |
| Guidelines | read-only list of the standing instructions workers get; editing is by editing the markdown files | **Untested** as a screen; the sync is **proved offline** (`GuidelineSyncTest`) |
| Insights | six figures, a temperature-versus-survival chart, survival by model, kill reasons | **Looked at** — the smoke test asserts the tiles and both tables |
| Run graph | the swarm as a diagram, plus a scrubbable replay of every agent's session | **Looked at** — the smoke test opens it, clicks a candidate, opens the transcript, and screenshots at three widths |
| Transcript | every step of one agent session, with the full payload on click | **Looked at** (`ConsoleDialogsBrowserTest` — written because this dialog silently did nothing) |
| Prompt Lab (developer) | compare the prompts two agent sessions actually received | **Untested** |
| Components (developer) | a gallery of the UI kit | **Untested** |

---

## 5. The two guided wizards and their unhappy paths

Both run **on the server**. Closing the window does not stop the work; re-opening lands where it
got to. Both have the same shape: collect inputs, start, answer a round of questions, review
proposals, apply.

**Analyse documents** — `IntakeWizard`, behind it `GuidedFlowServiceImpl` and `RequirementsIntake`.
Twenty-three service methods: add, remove, include or exclude a document, write per-document
notes, paste text instead of a file, view a document, set a character limit, start, cancel, answer
a question, add detail to an answer, skip one, discuss one in a side conversation, submit, tick or
untick a proposal, tick all, apply, reopen, start over.

**Plan stories** — `PlanningWizard`, `PlanningFlowServiceImpl`, `BacklogPlanning`. Twelve methods,
the same shape without documents or discussion.

**Coverage.**
- The intake engine is **proved offline** and thoroughly: `GuidedFlowIntakeTest` is 999 lines and
  covers a question round parking rather than guessing, skipped questions being reported as
  unanswered, nothing reaching the requirements document until Apply, changed inputs invalidating
  a review, documents over the limit refusing to start, duplicate proposals unticked with a
  reason, a second analysis reading only new documents, per-question discussions, and the verbatim
  malformed reply the live model produced on 2026-08-28.
- The planning engine is **proved offline** — `GuidedFlowPlanningTest`, 466 lines.
- Both are **proved live** through the live journey.
- Both are **looked at** by `ConsoleWizardsBrowserTest`, which drives every step of both and
  screenshots each at three widths.

**What is not covered.**
- **Re-analysis that merges.** The design says adding a document proposes edits and conflicts
  against what already exists, with before and after shown. Edits and conflicts are exercised as
  scripted proposals; **a real second analysis over a changed document against a live model has
  never been run.**
- **A wizard left running when the application is killed.** Nothing at startup reconciles guided
  flows. A flow stuck saying "analysing" has no thread behind it and will say so for ever. Cancel
  recovers it, so it is not fatal, but the screen lies until somebody presses it. The equivalent
  problem for runs was fixed; for wizards it was not. **Untested.**
- **The delivery wizard does not exist.** The design names three guided flows; the third has no
  engine. Deliberate, stated in `GUIDED_FLOWS_DESIGN.md` §9.

---

## 6. Interruption — outage, restart, retry, stranded work

This is the best-covered area outside the main path, and it deserves its reputation. It also
contains the second-worst defect in this document.

| Flow | What happens | Coverage |
|---|---|---|
| The model server disappears mid-build | The engine tells "the model said no" from "the model was not there", **pauses at the stage that failed**, retries with growing backoff, resumes by itself, refunds the budget so retries are not billed, and escalates to the operator after about thirty minutes | **Proved offline** — `OutagePauseAndResumeTest` starts a run against a dead port, asserts the pause, the operator-readable sentence and the zero spend, then starts a real server on that port and watches the run finish with nobody pressing anything. Plus `EndpointOutageTest` on real loopback servers |
| The model server is already down when a build starts | One cheap question before any worker is launched; the build pauses instead of burning a worktree and a container per candidate | **Proved offline** — `SwarmEngineFakeVllmTest` asserts **zero** agent sessions opened, no candidates archived, no leftover branches |
| The whole application restarts | Every state change is persisted; unfinished runs are resumed from the stage they reached, including one waiting out an outage | **Proved offline** — `WorkflowPersistenceTest` closes the store and reopens it; `SwarmEngineFakeVllmTest` reopens the store and finds complete agent session records. **But see the defect below** |
| Stories left saying "building" by a killed process | Freed to "ready to build" — but only if their run is genuinely dead. A run that is executing, or merely paused for an outage, is left alone | **Proved offline** — `StrandedStoryRecoveryTest`, three cases, written from an operator's report of three stories stranded for fifteen hours |
| Abandoned worker worktrees | Swept at startup by asking git, never by recognising shapes. Nothing under six hours old, at most 200 removals, nothing git refuses, nothing holding uncommitted work, nothing belonging to a live run, and nothing it cannot identify | **Proved offline** — `WorktreeSweeperTest`, eight cases against real repositories and real linked worktrees |
| Re-running a failed build | "Build it again" on a stopped story | **Asserted only** — the state rules are tested; the button is looked at once |
| A build parks and asks a question | It surfaces on the story's card with the answer box on the card. Recording an answer does not restart anything, and the dialog says so | **Proved offline** for the server; **looked at** for the card |
| Taking back a build that is still running | Allowed deliberately, because nothing can tell a live run from an abandoned one. It does not stop the run | **Untested.** Nothing exercises the operator then pressing "build this story" while the first run is still writing to worktrees |

**The defect.** Resume is not scoped to a project. `WorkflowEngine.resumeAll()` selects **every**
unfinished run in the store with no project filter, and the engine it runs on is the **default
project's** — `Main` uses `graph.workflowEngine`, which is assigned from the default project's
context. That context carries the default project's repository path, its git service and its list
of locked modules. So a run belonging to a second project is continued against the first project's
code, with the first project's protections. The same applies to the stranded-story pass and the
event logger. **Untested** — every persistence test uses one project.

**Also untested here:** the outage tests use a fake endpoint on a local port. **No test has ever
taken the real model server away in the middle of a real run.** The promise that an outage is
invisible to the operator rests on a test whose model was never real.

---

## 7. What the agents can do

### 7.1 The worker's tools — six, all always on

Registered unconditionally in `sc-swarm/WorkerToolbox.bindings()`. No flags.

| Tool | Does | Guardrail |
|---|---|---|
| `exec` | run a shell command, 300-second limit | **No policy layer at all.** After every command the orchestrator runs `git status` and reverts or deletes anything written outside the write set or into a protected path |
| `read` | read a file, capped at 64 KB | absolute paths made relative |
| `apply_diff` | apply a unified diff | every target checked against the write set and protected paths before `git apply --recount` |
| `write_file` | replace a whole file | same check, plus an explicit escape check |
| `lookup_api` | ask about a library or API | answered from the local documentation index, then the project's real sources, then Context7 |
| `report_done` | finish, with a summary | its name is the loop's stop signal |

**Coverage: proved offline, and well.** `WorkerToolboxTest` is 303 lines against a real git
repository: each tool inside and outside the write set, path traversal, symbolic links, locked
modules, miscounted diff headers, and — the important half — that a shell command writing outside
the write set is reverted, an untracked file it created is deleted, and it cannot touch the
verification contract.

Also always on: two consecutive turns with no tool call kill the worker; two write-set violations
kill it; a token budget kills it. Thirty turns maximum, 2.5 million tokens.

### 7.2 The knowledge and documentation path — six things, three not what they look like

| Capability | Wired into a normal run? | On by default? | Coverage |
|---|---|---|---|
| **The knowledge brief** given to every worker — the project's libraries, the operator's curated notes, the conventions, and the real source of the files the task touches | Yes, assembled for every task at plan time | Yes, no flag | **Proved offline** — `LibrarianTest` |
| **The local documentation index** the `lookup_api` tool searches first | Yes | Yes — but **its only writer is the Context7 fetch**, so with no Context7 server running it stays empty for ever | **Untested directly.** No test names it |
| **Context7 documentation service** | Yes, three ways: pre-warmed at plan time, the `lookup_api` fall-through, and a researcher tool | **On by default** — `http://localhost:3000/sse` is built in when the setting is absent. It fails quietly: one INFO line, then silently inert | **Asserted only** — `LibrarianTest` and `ResearcherAgentTest` use scripted local endpoints |
| **The researcher with web access** | **No.** Constructed for every project; nothing in the pipeline calls it. Its only trigger is a button in the Knowledge pane | Manual only. It is the only role allowed near the open web; workers have no web tool at all | **Proved offline** — `ResearcherAgentTest`; the button is **looked at** once |
| **History search** — "have we seen this before?" | **Half.** Every finished agent session is indexed, always. But **no agent can search it** — there is no such tool in the worker's toolbox, the architect's research tools or the researcher's tools | Indexing on, no flag; the search is a service method with **no caller in the console client either** | Indexing **proved offline** (`HistoryRagTest`). The search is **dormant** — reachable from neither an agent nor a person |
| **Guideline extraction** — learning rules from finished runs | Yes, at the end of every run | Extraction on; **promotion off** (`guidelines.autoPromote` defaults false), so learned rules land as proposals and never reach a prompt until a person edits the file | **Proved offline** — `GuidelineExtractorTest`, `GuidelineSyncTest` |

**The history-search gap is real and is not documented as deliberate.** The technical spec says
any agent may ask whether this has been seen before. Everything needed to answer it exists and is
maintained on every run. Nothing asks.

### 7.3 The code-analysis service (`sc-lsp`)

A language server providing pre-compile diagnostics. **Wired at exactly one point** — the final
integration step — and **off by default**: it needs `-Dswarmcoder.jdtLsHome` pointed at an
installed Eclipse Java language server, and unset means a no-op facade.

Per-candidate use is **deliberately not wired** (one server per candidate at eight workers is
prohibitive). Architect use is **not built** — a "TODO" in `sc-lsp/NOTES.md`. Worker tools are
**deliberately deferred**, same file.

**Coverage: asserted only, for the off path.** `JdtLanguageServerTest` proves every method degrades
safely with no server installed; `CommandPipelineVerifierLspTest` proves the advisory block folds
into the log without gating survival. **The live launch path has never been run** — its own notes
say so.

### 7.4 The sandbox

**On by default** (`sandbox.enabled` absent means true) and, when Docker will not start,
**candidates fail rather than silently running model-authored commands on the workstation**
(`sandbox.required` also defaults true). Containers run non-root, with no network, a read-only
root filesystem, all capabilities dropped, a process limit, and the host Maven cache mounted
read-only.

**Coverage.** The container settings are **proved offline** without a daemon
(`DockerSandboxHardeningTest`, every flag asserted) and **proved live** against a real daemon
(`DockerSandboxLiveTest` — DNS fails, outbound HTTP fails, the read-only mount is unwritable, and
an offline Maven build still succeeds). The whole journey through Docker against the live model
ran once, on 2026-08-28, in 177 seconds. Both are opt-in.

**Two honest gaps, both stated in the code.** The egress allow-list is parsed and warned about but
**not enforced** — the real choice is no network or all network. And the in-container HTTP action
server is unreachable under the default no-network setting, so commands go through the Docker
engine instead; that action server has no test of its own.

### 7.5 Things that look wired and are not

| Thing | Status |
|---|---|
| Behavioural probes for clustering candidates | **Dormant, deliberate** — the probe runner is the constant empty list, with the comment "behavioral probes arrive in M4" |
| Tree-sitter signature extraction and the repository map | **Dormant** — tree-sitter is used only to normalise candidates before clustering; the signature and repo-map methods have no production caller. The librarian's own parameter for it is named `unusedSyntax` |
| Clustering in any language but Java | **Dormant** — the language is hardcoded |
| Session checkpoint, rollback, fork and compress | **Dormant, deliberate** — all throw, "arrives in M4", stated in `sc-runtime/NOTES.md` |
| The `roles.librarian` and `roles.approver` settings | **Dormant** — in the Settings form, read by nothing |
| The per-worker random seed | **Dormant, deliberate and documented** — recorded on every candidate; the model API has no field to send it in |
| `sc-evals` | An empty module. No code at all |
| `sc-server` | A stub, and **conformant** — the protocol servers it was for were descoped (corrections §9, item S7) |

**On worker diversity, which is the product's whole bet.** Three mechanisms were meant to make
parallel attempts differ. The seed is never sent. Splitting across model families has nothing to
split across while one model is configured. That leaves the temperature spread — **which defaults
to no spread at all**, both ends 0.2 — and four rotating personas, which are on. The startup log
says this out loud, and `SwarmDiversityTest` asserts that it does.

---

## 8. Rules and containment

The principle, from corrections §13.1: policy must be resolved from a tree the restrained party
cannot write.

| Layer | What it locks | Coverage |
|---|---|---|
| Always protected | `.swarmcoder/` and `.git/` — the verification contract and the git hooks are both things the host executes | **Proved offline** — `PathPolicyTest`, `WorkerToolboxTest` |
| Trust kernel | the six classes that implement the checks themselves; locked by default, released only with `-Dswarmcoder.unlockTrustKernel` and logged loudly | **Proved offline** — `PathPolicyTest`, `LockMarkerTest` |
| In-source markers | a `swarmcoder:locked` comment, read **at the last commit**, so deleting it in the same change does not unlock the file | **Proved offline** — `LockMarkerTest` over a real repository |
| Operator-declared protected paths | global settings and the project file, **unioned**, never replaced, because the project file lives inside the repository the swarm writes to | **Proved offline** — `LockMarkerTest` |
| The acceptance-test directory | per task | **Proved offline** |
| The write set | per task; empty means unrestricted | **Proved offline** |

Enforced twice, and both are needed: at the worker's tools, and again as an audit of every path
the winning change touched before it is merged. The second exists because a shell command can
write anything and `git add -A` sweeps it in. Path matching canonicalises first and resolves
symbolic links, and matches whole path segments so `src` never authorises `srcgen/`.

**What is not enforced, all stated in the code.**
- `exec` has no allow-list, no path interception and no audit of its own; the revert-after-the-fact
  check is what bounds it.
- The read set is parsed, copied, and consulted by nothing.
- **The plan validator's "can this task write anything at all?" check passes no protected-path
  list**, so it only catches the always-protected paths and the acceptance-test directory. A
  planner can still produce a task whose entire write set is an operator-locked module. That is
  the exact shape of one of the four defects found in the live runs.
- **Guidelines are not scoped to a project when they are rendered into a prompt.** The stored
  guideline has no project field and the renderer reads the whole store. With two projects, every
  active guideline from either is injected into both projects' worker prompts.
- **Guidelines reach workers only.** Nothing gives them to the architect, the design reviewer, the
  test author, the analyst or the planner.

**One weakness worth a test.** The verification contract is loaded from the operator's tree and
**falls back to the candidate's workspace when the operator's tree has none**. In that narrow case
a worker that got a contract file past the protections would be writing its own exam. The path
audit before merge is the backstop. Nothing tests the fallback.

**Secret scanning** runs on every winning change before merge, on added lines only, with known
token shapes plus an entropy test on credential-looking assignments, and findings are redacted. A
hit parks the run. **Proved offline** — `SecretScannerTest`, `FinalIntegratorTest`. It is not run
per candidate and not on any chat path.

**How a rule reaches a model.** Guidelines live as markdown files under
`<repo>/.swarmcoder/guidelines/<scope>/<slug>.md`. Before every task the store is reconciled
against the files, active ones are rendered into the shared part of the prompt in order of how
specific they are, capped at about 12,000 characters, and the shared prefix is hashed so a change
is visible in the log. A human edit always wins and never decays. **Proved offline** —
`GuidelineSyncTest`.

---

## 9. Configuration — every setting, and which ones do nothing

One file, `~/.swarmcoder/config.yaml`, plus per-project overrides in
`<repo>/.swarmcoder/project.yaml`. Both readers ignore unknown keys silently, so **a mistyped key
is silently a default**.

| Setting | Default | What it does |
|---|---|---|
| `repoPath` | none | the target repository. Without it git is disabled and runs cannot execute |
| `consolePort` | none | **without it no console starts and there is no interface at all** |
| `contextPaths` | empty | read-only reference folders. **Empty switches off the architect's entire research phase** |
| `protectedPaths` | empty | unioned with the always-protected paths and the trust kernel |
| `sandbox.enabled` | **true** | workers' shell commands and verification run in Docker |
| `sandbox.required` | **true** | Docker missing means the candidate fails, not that it runs unprotected |
| `sandbox.network` | `none` | no egress. `bridge` means unrestricted |
| `sandbox.cpus` / `memGb` / `pidsLimit` | 2 / 4 / 512 | container limits |
| `sandbox.allowedHosts` | empty | **parsed, warned about, not enforced** |
| `sandbox.poolExtra` | — | **read by nothing** |
| `swarm.nPerTask` | **4** when the `swarm:` block is present but the field is not. The whole block absent still means **1** — no swarm at all, deliberately | workers per task, and the GLOBAL layer of story → project → global |
| `project.yaml` `swarm.nPerTask` | absent = inherit the global | the PROJECT layer; edited in the per-project settings dialog |
| `Story.workersPerTask` | absent = inherit the project | the STORY layer; edited on the story's panel on the pipeline board |
| `swarm.tempMin` / `tempMax` | 0.2 / 0.2 | **no diversity unless set** |
| `swarm.personaIds` | four rotating personas | the one diversity lever that is on |
| `swarm.splitAcrossFamilies` | false | inert with fewer than two worker models, and says so at startup |
| `swarm.maxConcurrentTaskGroups` | **2**. A NEGATIVE value means unlimited, which is what a bare 0 used to mean | tasks of one run dispatched at once |
| `swarm.maxConcurrentWorkers` | **8** | the hard process-wide ceiling on workers running at once. Over it, workers wait for a free place; none are dropped |
| `swarm.dispatch.staggerMs` | 0 | no warm-up stagger for the shared prompt cache |
| `swarm.dispatch.timeoutSeconds`, `swarm.dispatch.retries` | — | **read by nothing.** Both appear in the user manual's example |
| `budgets.maxCloudTokensPerRun` | **0, meaning no cap** | the only enforced budget, and only for the architect, the judge, the researcher and the two extractors — **not** workers, the test author, the design reviewer, the wizards or the chat. The counter is in memory and **resets to zero on every restart** |
| `budgets.maxLocalTokensPerTask` | — | **read by nothing.** Editable in Settings |
| `budgets.wallClockCeilingHours` | — | **read by nothing.** Editable in Settings |
| `guidelines.autoPromote` | false | learned rules stay proposals |
| `guidelines.maxPrefixTokens` | 3000 | about 12,000 characters of rules per prompt |
| `guidelines.decayRuns` | — | **read by nothing;** decay is not implemented |
| `mcpServers` | **one entry: Context7 on localhost:3000, enabled** | on by default, silently inert if nothing is listening |
| `cloud.endpoints.*` | — | **the entire block is read by nothing.** `apiKeyEnv` is never resolved to an environment variable |
| `spark.instances[]` | — | admission pools, **superseded** by the per-model settings; its endpoint and model fields are read by nothing |
| `roles.*` | see §4.5 | endpoint, model and key per role. The architect's entry also supplies the process-wide default client |
| `roles.vision` | none | without it, uploading an image is refused with a message saying so — deliberately, no text fallback |
| `roles.librarian`, `roles.approver` | — | **in Settings, read by nothing** |
| `roles.*.protocol` | — | **read by nothing**; the form hardcodes "OpenAI" when writing |
| `overnight.enabled` | false | **warns at startup that the feature does not exist** |
| `telemetry.otlpEndpoint` | none | **warns at startup that the export does not exist** |
| `project.yaml: contextPaths, protectedPaths, roles, name` | — | per-project; protected paths are added to the global ones, never replacing |

System properties, not in the file: `swarmcoder.sandbox.enabled`, `.image`, `.dockerHost`, `.m2`;
`swarmcoder.jdtLsHome`; `swarmcoder.unlockTrustKernel`; `swarmcoder.outage.firstWaitMillis`;
`zeroz.security.mode` (set to `dev` by the application itself if unset — the browser client signs
in as admin/admin). **No environment variable configures the running application.**

**All settings apply only on restart.** Saving them writes the file and says so; the clients,
scheduler pools, sandbox manager and project contexts were built at startup. The one exception is
per-project settings, which invalidate that project's context.

**Coverage.** The model-settings resolution is **proved offline**; writing the file from the
Settings screen is **proved offline**. **Nothing tests what happens when a setting is absent,
malformed or contradictory**, beyond the model shapes.

---

## 10. Startup and the command line

**There is no command line.** `Main.main` ignores its arguments. No subcommands, no flags, no
headless mode, no argument parser anywhere in the repository. `run.bat` starts the jar with the
four JVM options the store needs and passes no properties. `build.bat` builds and installs — and
deletes a `dist` folder that nothing creates. `deploy/build-images.sh` builds a container image
under a **different tag** from the one the application looks for. `dev/spark_bench.py` measures
the model server's throughput.

There are three `main` methods: the application; the browser client (compiled, not run as a JVM
program); and the in-container action server.

What startup does, in order:

1. **Load the configuration file.** Missing means **the application throws and exits**. There is
   no built-in default and nothing writes a starter file. **Untested.**
2. Open the store and **adopt the persisted root** rather than a fresh one. Schema version 10.
3. **Sweep abandoned worker worktrees**, on a background thread, by asking git — and sweep nothing
   at all if the run graph cannot be read, rather than guess. **Proved offline.**
4. Build the inference clients, the model profiles and the admission pools; **warn** about: a
   worker model whose numbers are unverified (every start, per model); a working context larger
   than the context the server was started with; one answer allowed to be bigger than the whole
   working context; no worker model configured; a duplicate worker model; splitting across
   families with nothing to split across.
5. **Announce how contained workers are** — a loud block if the sandbox is off, and a
   two-remedies error if it is on and Docker is unreachable.
6. Build the default project: resolve its locked modules and log them; **create the git repository
   if there is none** (init, a default ignore file, an empty first commit) or disable git with a
   warning; reconcile the guideline files; apply the project's context folders.
7. Reopen the project the operator was last on, falling back with a warning if it is gone.
8. Warn about the environment: running on Windows rather than Linux; a repository on a Windows or
   `/mnt/` filesystem; the two settings that do nothing.
9. Start the console. Without a port set, nothing starts and there is no interface.
10. **Resume every unfinished run** — including one waiting out an outage. **See the defect in §6.**
11. **Then** free stories that say "building" with nothing driving them.

Steps 3, 10 and 11 are **proved offline**. Steps 1, 4, 5 and 8 are **untested** —
`DependencyGraphStartupTest` builds the whole object graph offline but asserts nothing about the
checks or the warnings.

**Guided flows are not reconciled at startup.** See §5.

**What survives a restart.** Everything in the store: runs at the exact state they reached,
designs, task graphs, tasks, briefs, candidate archives, complete agent session traces, the
requirements document and its revision history, stories, iterations, source documents, the change
journal, check verification history, wizard state, chats, knowledge entries, the guideline index,
pending decisions, and which project was open. Plus blobs and both search indexes on disk.

**What does not.** In-flight worker sessions and live model streams — a resumed run re-enters its
stage, it does not resume mid-turn. The cloud budget counter. Research mission status. Docker
containers. Live push subscriptions. And every configuration edit, until the next start.

---

## 11. The contract a target repository supplies

A repository tells SwarmCoder how it is built and tested in `.swarmcoder/verify.yaml`: a
`toolchain`, command lists for `compile`, `acceptance`, `existing` and `lint`, where the test
reports land, a timeout (default 1800 seconds), and an optional browser block naming a serve
command, a readiness probe and per-page checks.

Default report locations are built in for Maven, Gradle, Node, Cargo and Python. **Only Maven has
ever been exercised.** The other four are string constants nothing has run.

**If the file is absent**, verification is skipped loudly and the candidate is marked unverified —
the temporary allowance from July, still in place, and contradicting the architecture's own rule
that unverifiable tasks must not swarm at all.

**If the file is malformed, exactly the same thing happens.** The parse error is caught by the same
handler as a missing file, and the candidate passes through unverified. A YAML syntax error in the
one file that decides whether code is correct is indistinguishable from having no file. That is
the same defect class as "an empty check is not a pass", which was fixed in three other places on
2026-08-28.

**Coverage.** The pipeline is **proved offline** (`CommandPipelineVerifierTest`: green path,
compile short-circuit, acceptance short-circuit, an infrastructure failure, stale reports cleared,
lint advisory) and **proved live** with real Maven and real surefire XML. The rule that an
acceptance stage running **zero** tests is a failure when checks are claimed is **proved offline**
by `EmptyCheckIsNotAPassTest`, ten cases. Browser page checks are **asserted only** —
`BrowserVerifierTest`, which since 2026-08-28 runs in an ordinary build.

**Untested:** a malformed contract; any toolchain but Maven; the browser block against a real
served application.

---

## 12. Found while writing this — record, do not fix

Nothing here was changed. Each is separate work.

**Data loss and wrong-target writes**

1. **`BrdServiceImpl.restore` drops five fields per requirement** — checks, functional kind,
   quality category, source document, content revision — because it rebuilds each requirement
   through the seven-argument constructor. Restoring any revision empties the project's checks and
   all their verification evidence. One click, no confirmation, no test. Node positions go too.
2. **Resume is not scoped to a project.** `WorkflowEngine.resumeAll()` selects every unfinished run
   in the store with no project filter, and `Main` runs it on the default project's engine
   (`DependencyGraph:324` assigns `workflowEngine` from the default context). A run from a second
   project is continued against the first project's repository, git service and locked-module list.
   The stranded-story pass and the event logger have the same scoping.
3. **Guidelines are not scoped to a project when rendered.** `GuidelineSync.renderActive` reads
   every guideline in the store and the stored guideline has no project field. In a multi-project
   install every active guideline from every project is injected into every project's worker
   prompts.

**Flows that claim success without doing anything**

4. **`BugfixWorkflow`, `RefactorWorkflow`, `DocsWorkflow` and `AnalysisWorkflow` do no work and
   mark the run delivered.** Reachable from the chat's own advertised commands. No test.
5. **A malformed `verify.yaml` is silently identical to an absent one**, and both let a candidate
   through unverified.

**Cannot start**

6. **A missing `~/.swarmcoder/config.yaml` kills the application at boot** with
   `Config file not found at …`. Nothing seeds one. `CONSOLE_UX_V3.md` §3.3 says a brand-new
   install opens into a first-project dialog; it cannot.

**Gaps in containment**

7. **The plan validator's "can this task write anything?" check passes no protected-path list**, so
   a planner can still produce a task whose whole write set is an operator-locked module. A task
   whose write set was entirely protected is one of the four defects the live runs found; this
   closes only part of it.

**Promised in the design, not built**

8. **Editing a requirement a building story depends on does not warn, show impact, or send the
   story back.** `CONSOLE_UX_V3.md` §2.2 and §7 promise "2 passing checks go stale; S2 will be sent
   back to Ready" with a confirmation. What happens is the revision counter is bumped silently and
   the evidence renders stale. `BrdServiceImpl.saveRequirement` has no impact calculation and no
   confirmation path.
9. **There is no "agree all drafts" button.** `CONSOLE_UX_V3.md` §7's day-one walkthrough says
   "agree requirements (one button for all drafts)". `BrdService.promoteAllDrafts` is implemented
   on the server and no screen calls it.
10. **A requirement cannot be opened for editing from the requirements list** — only from the
    diagram. The list's empty state admits it.

**Documents that contradict the code**

11. **`DEVELOPER_CORRECTIONS.md` §13.3 says "with the sandbox off — the default"**, and §6 item 17
    says "default OFF … falling back to local on any sandbox error (a Docker hiccup never blocks a
    run)". Both are now false: the sandbox defaults to **on**, and one that will not launch
    **fails the candidate**. The code is right and the document is stale — but §13.3 is the
    normative record of the containment model, so the stale line matters.
12. **`docs/USER_MANUAL.md` describes a product that no longer exists** — the terminal interface
    (deleted), a project rail, a sidebar of chats and runs, a Swarm Board, an Approvals tab, a
    Backlog panel with iterations, dropping a file anywhere on the Requirements canvas, a
    `dist/bin/swarmcoder` launcher the build does not produce, and two configuration keys that are
    read by nothing. Its workflow section describes bugfix, refactor, docs and analysis runs as
    working features.
13. **`deploy/build-images.sh` builds `swarmcoder/sc-java:latest`**, while the application looks
    for `swarmcoder-worker:latest` and the startup error tells the operator to build that from a
    different directory.
14. **`dev/demo-repo/.swarmcoder/verify.yaml` still carries the acceptance-test selector that
    matches nothing** (`-Dtest=swarm.accept.*` instead of `-Dtest=swarm/accept/**`). Recorded in
    corrections §15.2 as needing a hand fix; still needed, and that repository is outside this
    checkout's history.

**Dead controls and silent failures**

15. **The heart-shaped "System health" button** at the bottom of the Projects dialog has an empty
    handler. It does nothing.
16. **The three items in the component gallery's context-menu demo** do nothing. That demo is also
    the only context menu in the whole client.
17. **Deleting a relationship discards the server's answer**, so a refused delete is invisible.
18. **A non-numeric value in any of the eleven per-model numeric settings silently becomes zero**,
    which the resolver reads as "use the shape's value". No message.
19. **The requirements diagram's empty state promises "drop a file anywhere on this panel".** There
    is no drop handler there. The capability was removed deliberately in the upload rework
    (corrections §12, D1) and the text was not updated.
20. **The Setup checklist says "Use + in the Projects rail on the left".** There is no rail.

**Implemented on the server, unreachable from any screen**

21. `ControlService.submitIntake`, `approveRun`, `rejectRun`, `integratedDiff`,
    `resolveAllPendingDecisions`; `BrdService.promoteAllDrafts`; `ObserverService.searchHistory`,
    `activeSessions`, `recentSessions`, `blobText`; `ChatService.rename`, `archive`. Some are
    residue of the abolished approval gate and are conformant. `promoteAllDrafts`, `searchHistory`
    and chat rename/archive are not accounted for anywhere.
22. `BacklogService.scheduleStory` and `addIteration` are unreachable **deliberately** — iterations
    are recorded in `CONSOLE_UX_V3.md` as cut from the interface with the data retained.

**Capabilities maintained that nobody can use**

23. **History search.** Every finished agent session is indexed on every run. No agent has a tool to
    search it and no screen calls the service that would.
24. **The local documentation index has exactly one writer** — the Context7 fetch. With no Context7
    server running it is permanently empty and `lookup_api` falls through to reading the project's
    own sources.

**Configuration that does nothing**

25. The entire `cloud:` block; `budgets.maxLocalTokensPerTask`; `budgets.wallClockCeilingHours`;
    `guidelines.decayRuns`; `swarm.dispatch.timeoutSeconds`; `swarm.dispatch.retries`;
    `sandbox.poolExtra`; `roles.librarian`; `roles.approver`; `roles.*.protocol`; the endpoint and
    model fields of `spark.instances[]`. Three of these are editable in the Settings screen.
26. **The cloud budget is charged by five spenders and not by the rest.** The architect, the judge,
    the researcher and the two extractors charge it. Workers, the test author, the design reviewer,
    the two wizards and the chat do not. A run's own budget object is always null, and the counter
    resets to zero on every restart.

**Structural**

27. **`sc-domain` has 93 classes and 2 test classes.** Almost all domain coverage is incidental,
    through store round-trips and console services.
28. **One browser test per process is a hard limit** of the framework's shared-signal design.
    Growing UI coverage costs one new class and one new forked process per test.
29. **Five modules have no tests at all**: the console client, the wire contract, the in-container
    action server, the stub server, and the empty evaluation module.

---

## 13. The ten things most worth testing next

In order. Each says why it and not the next one.

1. **The four run kinds that do nothing.** A person can start one from a menu the product itself
   offers, and be told the work is delivered. Nothing else on this list produces a confident false
   statement about work being done. The test is small: start each kind, assert it cannot reach
   delivered without a plan and a verified candidate.

2. **Restarting with two projects.** Today an unfinished run from the second project is resumed
   against the first project's repository, with the first project's locks. That is a wrong-target
   write and a containment failure at the same time, and it happens without anybody pressing
   anything. Test: two projects, a run left mid-flight in each, restart, assert each run continues
   against its own repository.

3. **Restoring a previous version of the requirements document.** One unguarded click destroys the
   project's checks and every piece of verification evidence attached to them. It is above the
   other delete buttons because those destroy one thing and this destroys everything.

4. **Starting from nothing.** No configuration file, no project, no repository. Today the first
   step fails and the application exits. This is the only item that decides whether a second person
   can ever use the product. Test: an empty home directory, boot, assert the console comes up and
   offers to create a project.

5. ~~**Turn the six browser tests on and keep them on.**~~ **Done 2026-08-28.** They cover the
   shell, the requirements screens, the pipeline board, both wizards, three dialogs and the empty
   state, and they now run in every ordinary build on any machine with Chromium — about three and
   a half minutes added to `sc-console`. All six pass. See `docs/TESTING.md`.

6. **The unguarded deletes in the requirements editor and the wizards** — requirement, check,
   relationship, re-layout, uploaded document, "clear documents", "discard this plan". Seven
   controls, one test each, asserting what is destroyed and what survives. Below the items above
   because each loses one thing rather than everything, but these are the actions an operator meets
   first while learning the product.

7. **A real second analysis over a changed document.** Re-analysis merging and showing conflicts is
   what lets the requirements pool stay open while work is in flight, and it is the only major
   agent behaviour proved with scripted replies and never with a live model. Run the intake twice
   over an edited document against the real model and assert edits and conflicts, not just
   additions.

8. **A real outage in a real run.** The pause-and-resume machinery is well tested against a dead
   local port with a fake model. Take the actual model server away in the middle of an actual run,
   put it back, and assert the run finishes and the operator was never asked for anything. This is
   the promise the whole "outage day" design was written to keep.

9. **A ten-worker run on a repository with more than one class.** Everything proved live so far
   used two workers on one method in a one-class demo. The product's central bet — that N attempts
   plus real tests beat one attempt — has one piece of direct evidence: two runs where one worker
   of two failed verification and the other passed. Scale is the next unknown, and it is what the
   unmeasured model numbers are blocking.

10. **A target repository that is not Maven, and a contract file that is wrong.** Gradle, Node,
    Cargo and Python report locations are built in and have never been run; a malformed contract
    has never been parsed, and today it silently lets a candidate through unverified. Last because
    the only target repository today is Maven — but it is the first thing that breaks when
    SwarmCoder is pointed at somebody else's code.
