# SwarmCoder — the agent process, the tools, and how each class of problem is handled

**Written 2026-09-05, from the code, after two days of intensive fixes (harness runs 8 to 30).**

This is the developer guide for someone coming back to SwarmCoder after a break, or arriving for the
first time. It describes what the system does today: every pipeline stage and the gates on it, what a
worker can and cannot do, where knowledge comes from, how the model runtime is sized, what the two
harnesses prove, and — in one table — what the system now does about each class of problem that has
actually happened.

It is in two files because it is long. **This one** covers the machine: orientation, the pipeline, and
the worker. **[`Agent-Knowledge-And-Operations.md`](Agent-Knowledge-And-Operations.md)** covers the
support systems and how to run it.

**Where this sits among the other documents.**

- `docs/swarm-coder-architecture.md` and `docs/swarmcoder-technical-spec.md` are the original design
  rationale from July 2026. They are historical. The architecture document says so at the top.
- `docs/DEVELOPER_CORRECTIONS.md` is the correction contract, and it is normative where it speaks —
  but its last entry is 2026-08-29 (§39). **It does not contain anything from 2026-09-02 onward**,
  which is where most of the machinery in this document comes from. Read it for history; read this
  for what runs.
- `docs/FLOW_INVENTORY.md` (2026-08-28) maps every flow and its test coverage.
- `docs/plans/brownfield/Design.md` and `Ledger.md` are the contract and the results for the
  brownfield work, which is on a branch and not in master.

Every claim below was checked against the code on branch `master` at commit `e5ff802`. Class names
are given so you can go and read the thing itself; the code carries long javadoc explaining *why*
each rule exists, and that javadoc is the real record.

---

## Table of contents

**This file — the machine:**

- [0. Orientation](#0-orientation) — what it is, the modules, the git topology, the run states
- [1. The pipeline, stage by stage](#1-the-pipeline-stage-by-stage) — every stage, every gate, every park
- [2. The worker](#2-the-worker) — the tools, the brief, the read-pause, exec safety

**`Agent-Knowledge-And-Operations.md` — the support systems and how to run it:**

- 3. Knowledge and the help desk — the indexes, worked examples, `ask_expert`
- 4. Runtime and models — Koog, model shapes, context sizing, roles, the paid-call gate
- 5. The harnesses — the greenfield chain, the brownfield chain, the two experiments
- 6. Problem-handling playbook — symptom → mechanism → class → the run that taught it
- 7. Operating notes — the console, `~/.swarmcoder`, building, testing, conventions

If you read only one thing, read **section 6**: it is the whole system as a table of the failures that
produced it.

---

# 0. Orientation

## 0.1 What the system is

A document goes in. Requirements come out of it, each with checks. Checks become stories, a story
becomes a design and a plan of tasks, acceptance tests are written for the checks, and then N
workers attempt each task in parallel against a local model. Their work is verified by building and
running tests, judged, one winner per task is selected, the winners are merged, and the story is
delivered as a commit.

The bet is that a non-frontier local model sampled many times with strong automated selection beats
one pass of a stronger model. Everything in the pipeline is therefore about **selection and
evidence**, not about generation.

## 0.2 The modules

| module | what lives there |
|---|---|
| `sc-domain` | 118 persisted types: `Run`, `Story`, `Task`, `ApiContract`, all enums and their operator wording |
| `sc-store` | the ZeroZ DB store root |
| `sc-runtime` | `KoogAgentRuntime`, `HistoryTrim`, `CloudGate`, the agent-runtime contract |
| `sc-inference` | model endpoints, `ModelQuirks`/`ModelShapes`, `ServerCapabilities`, `AdaptiveConcurrency` |
| `sc-knowledge` | `Librarian`, `ReferenceIndex`, `SemanticIndex`, `WorkedExamples`, `ExpertDesk` |
| `sc-swarm` | `SwarmEngineImpl`, `SwarmDispatcher`, `WorkerLoop`, `WorkerToolbox`, `JudgeClient`, `SelectionLogic`, `WaveIntegrator` |
| `sc-workflow` | `GreenfieldWorkflow` (the state machine), the role clients, the plan and test gates |
| `sc-syntax`, `sc-lsp` | parsing and an advisory pre-compile check at integration |
| `sc-git` | `GitService`, `AcceptanceOverlay`, `BasePin`, `WorktreeSweeper` |
| `sc-sandbox`, `sc-sandbox-action-server` | container isolation for worker execution |
| `sc-verify` | compile, tests, verdicts, provenance, failure attribution |
| `sc-server` | the HTTP/MCP server |
| `sc-app` | wiring (`DependencyGraph`), config, and the end-to-end harnesses |
| `sc-console-api`, `sc-console-ui`, `sc-console` | the operator interface |

## 0.3 The git topology — read this before anything else

Almost every confusing bug in this system's history has been a git-topology bug. There are four
refs and they mean different things.

- **`Run.baseCommit` / `baseRef`** — the commit the run is pinned to, decided **once** at intake by
  `BasePin.pin(run, git)` (`sc-git`). Every worktree of the run is cut from here or from something
  descended from it. `BasePin` exists because the Console pinned and the harness did not: the
  harness cut from a live `HEAD` that happened to already carry the acceptance tests, so it walked
  the chain green while every Console-started run executed zero acceptance tests for a week.
- **`Run.acceptanceTestsCommit`, on `swarm/tests/<runId>`** — the acceptance tests, committed on the
  run's own ref, never on the delivery branch. Two failures forced this: a test committed on the
  delivery branch was invisible to every worktree cut from `baseCommit`, and a dead run's tests
  stayed on the branch forever (on 2026-09-02 the demo repository carried four such files from four
  dead runs, and `test-compile` failed on them before any candidate's own code was looked at).
- **`Run.progressCommit`, on `swarm/progress/<runId>`** — `baseCommit` with every finished wave's
  winner merged onto it. This is what the **next** wave is cut from. Before it existed, a second-wave
  task could not see the classes the first wave delivered, so its workers hunted for a type that
  only existed on someone else's candidate branch and were killed for making no progress.
- **candidate branches** — one per worker, archived rather than deleted, so a candidate can be
  re-verified later by recreating its worktree from its own branch.

Acceptance tests reach a candidate at verification time and **only the files its own task claims**,
placed by `AcceptanceOverlay` (`sc-git`). The overlay clears the whole protected tree
(`src/test/java/swarm`, anywhere in the worktree) first and then writes exactly the claimed files
out of the object database. A task claiming no check ends with an empty tree and an acceptance stage
that runs nothing — the documented allowance for an enabler.

Worktrees live under `~/.swarmcoder/wt`.

## 0.4 The run states

`sc-domain/src/main/java/com/swarmcoder/domain/RunState.java`:

```
INTAKE, DESIGN, DESIGN_REVIEW, PLAN, TEST_AUTHORING, REPRODUCE, CHARACTERIZE,
EXECUTING, FINAL_INTEGRATION, APPROVAL, DELIVERED, ABORTED, ABANDONED
```

`REPRODUCE` and `CHARACTERIZE` belong to other workflow kinds (bug and legacy work); the greenfield
path does not enter them. `ABANDONED` (added 2026-09-03) is terminal like `ABORTED` but means "the
project this run belonged to was deleted", which is a different fact from "the operator rejected
this" and must not be logged as the same thing.

Stories have their own lifecycle, `StoryState`: `DRAFT`, `READY`, `RUNNING`, `REVIEW`, `DONE`,
`BLOCKED`, `CANCELLED`. Every enum that an operator ever sees carries a `label()` giving the words
to print, so the board, the dialogs and any refusal all say the same thing about the same state.

## 0.5 Three things that are not states

- **A pause** (`Run.pausedSince`, `pauseReason`, `pauseEndpoint`) is the engine still driving the
  run, waiting for a model endpoint that will come back. `OutagePause` owns every derivation from
  it. The run stays at the stage it will retry.
- **A park** (`Run.parkedAt`, `parkReason`) is the opposite: a stage ran to completion, decided it
  could not continue, and raised a `BLOCKED_TASK` decision. Nothing is retrying it. The run stays at
  the stage that parked, so resuming retries exactly that stage.
- **A heartbeat** (`Run.heartbeatAt`) is stamped on every persist. A run's state cannot tell work in
  progress from work whose JVM died hours ago; a stale heartbeat can.

`GreenfieldWorkflow.advance` clears `parkedAt` the moment anything takes the run up again, before
the stage is even retried, and withdraws the stale decision — otherwise a card would read "stopped"
throughout a retry that is visibly building, and a second park would leave two pending questions
about the same run.

---

# 1. The pipeline, stage by stage

The pipeline has two halves. Everything up to and including "a story is ready to build" happens in
the **Console**, before a `Run` exists. From `INTAKE` onward it is `GreenfieldWorkflow`
(`sc-workflow`), a synchronous state machine on one virtual thread.

`WorkflowEngine.advanceSync` routes `GREENFIELD`, `ENHANCEMENT`, `BUGFIX` and `REFACTOR` to one
`GreenfieldWorkflow`. `DOCS` and `ANALYSIS` runs are retired to `ABORTED` — they never reach
`DELIVERED`.

`GreenfieldWorkflow.advance(run)` loops `step(run)` until `DELIVERED` or `ABORTED`. Every transition
is persisted before the next stage runs, so a crash resumes at the last state. A stage returning
`null` means it parked. An `EndpointOutage` thrown anywhere is caught here: the run is saved with an
`OutagePause`, the state deliberately does **not** move, and the same stage is retried when the
endpoint answers, with no attempt limit.

## 1.1 Document ingest

`sc-console/.../DocumentIngest.java`. `MAX_UPLOAD_BYTES = 25 * 1024 * 1024`. The same SHA-256
returns the existing `SourceDocument` instead of re-extracting. Text is pulled by
`DocumentExtractor` (`MAX_EXTRACTED_CHARS = 10_000_000`), which never throws and never touches the
network — every failure comes back as `Extracted.error()`. `extractedBy` records how:
`passthrough`, `pdfbox`, `poi` or `vision`.

Images go to the `vision` role under a fixed transcription prompt (*"Transcribe every piece of
information… Do NOT interpret, summarise, judge or invent"*). The `vision` role has **no fallback**;
if it is unset, image upload is refused rather than letting a text model describe a picture it never
saw. Vision-extracted text stays labelled as a model's reading of an image everywhere it is used.

Each document attached to a flow is a `FlowDocument(documentId, notes, analysedAt, excluded,
technical)`. **`technical` is set by the operator at attach time and is never inferred by a model.**
`excluded` is set by a successful apply, so the default next time is "do not read this again".

## 1.2 Project rules, from the technical document

The rule store is `com.swarmcoder.knowledge.ProjectRules` (`sc-knowledge`, **not** `sc-console`).

**`ProjectRules` makes no model call at all.** It is store mechanics. The model call that turns a
technical document into rules is the analyst's drafting turn inside `RequirementsIntake`: rules
arrive as `CONSTRAINT` proposals and become rules at Apply.

What the analyst is asked for (`RequirementsIntake.proposalPrompt()`): it is told a technical
document says how the system must be **built**, and that everything in it is almost certainly one
of — mandated or forbidden languages, frameworks, platforms, libraries; module layout; how parts
talk; how data is stored; packaging. Each proposal is `"requirementKind":"CONSTRAINT"` with no
criteria, no test, the whole rule in `text` including its reason, plus a **mandatory `excerpt`** —
the document's own sentence verbatim. That excerpt is proof rather than paraphrase, and it exists so
that a backticked Maven coordinate survives the analyst's rewording for `RulesVersusManifest` to
find at PLAN.

Storage: rules are `LearnedGuideline` objects in the store, keyed by project. Nothing reads or
writes a file — this replaced markdown under `<repo>/.swarmcoder/guidelines/` on 2026-09-02.

- **Identity is wording.** `contentKey(body)` lowercases, collapses whitespace, strips trailing full
  stops. Restating identical wording revives the existing rule at confidence 1.0 rather than
  duplicating it.
- `stateRule(title, body, document, excerpt)` writes provenance `"stated"` (with a document) or
  `"human"` (without), scope `PROJECT`, confidence 1.0, status ACTIVE immediately.
- `supersedeRulesFrom(document)` **retires, never deletes**, every ACTIVE stated rule from that
  document. The measurement in the code: one document applied four times over two days left 44 rules
  in force where the document says about eleven things.
- `propose(...)` is the machine path — provenance `"extraction"`, `MACHINE_INITIAL_CONFIDENCE = 0.5`,
  arriving PROPOSED unless `guidelines.autoPromote`.
- A rule can carry a one-line **check command** that proves it was obeyed; `setCheck` refuses one on
  a machine-proposed rule, and `activeChecks()` ignores and warns about any such command.
- Ordering is fixed (scope rank desc, confidence desc, slug, id) purely for prompt-cache stability.

Rendering into a prompt is `ConstraintBrief.render`, `DEFAULT_MAX_CHARS = 12_000` (≈3,000 tokens),
truncated rather than silently cut. **An empty rule set renders byte-identically to the pre-rules
prompt** — no heading, no "there are no rules". `ProjectContext` wires
`workflowEngine.setProjectRules(() -> projectRules.renderActive(maxGuidelineChars))` as a
**supplier**, read on every use, so a rule switched off mid-run stops being sent.

## 1.3 Requirements, from the business document

`sc-console/src/main/java/com/swarmcoder/console/RequirementsIntake.java` (2,205 lines).
Model role: `roles.requirementsAnalyst → roles.chat → roles.utility`. With none configured the flow
refuses before spawning anything.

Constants: `TOTAL_STEPS = 4` (reading → questions → drafting → review), `MAX_QUESTIONS = 12`,
`DEFAULT_BUDGET_CHARS = 250_000`, `DISCUSSION_TURN_BUDGET_CHARS = 12_000`, `MAX_SEARCH_ROUNDS = 4`.

The vocabulary: a project has one `Brd` holding `BrdRequirement`s (handles `R1`, `R2`…), each
carrying `AcceptanceCriterion`s — **the "checks"**. There is no `Check` type. The `Requirement` class
in `sc-domain` is a legacy stub used only by the design-document path.

### The sequence

1. **Brief.** Every non-excluded document is rendered whole with a `=== TECHNICAL DOCUMENT:` or
   `=== DOCUMENT:` header, the operator's note marked authoritative, and a lower-confidence warning
   for vision-extracted text. **Nothing is truncated**: if the total exceeds the budget the whole
   analysis is refused up front, naming both numbers.
2. **Questions**, one round only, never a loop — and skipped entirely when no included document is
   still unanalysed, so re-ticking documents cannot restart the interview. An **unanswered** question
   is rendered to the analyst as *"STILL UNANSWERED — DO NOT WRITE A REQUIREMENT FROM THIS. Not as an
   assumption, not as a placeholder, not in weaker words."* A **skipped** question is explicit
   permission to take the most defensible reading and say so in an ASSUMPTION line.
3. **Drafting**, then `parse → retry → salvage`, in that order:
   - `jsonOrThrow` is strict: parse, accept if `proposals` or `questions` is present, else try two
     wrapping shapes, else throw.
   - `LlmReplyRetry.askJson` gives **exactly one** retry — same conversation, the bad reply as an
     assistant turn, and one user turn: *"That was not valid JSON: <message>. Reply with only the
     JSON object."*
   - **Salvage** runs only in that catch, over the retry reply, reading field by field and element by
     element with a raw Jackson parser. It **stops at the first thing it cannot read, keeps
     everything before it verbatim, and discards everything after. Nothing is repaired, completed or
     inferred.** An empty salvage fails the flow.
   - The ordering is load-bearing and was a fixed bug (2026-09-03): the old code salvaged *before*
     signalling failure, so the retry never fired, and a reply broken at column 14 salvaged one
     proposal out of a 7,240-character document pair and was counted a success.
   - Salvage is never silent: a thin result says in the step label that the reply was malformed
     twice and how many proposals were recovered out of roughly how many, with the blob ref.
4. **Empty-batch re-ask.** Zero proposals from a non-empty document earns one more turn; still empty
   fails the flow naming the character count.
5. **Technical-rules re-ask.** If a technical document is attached and no `CONSTRAINT` proposal came
   back, one further turn says the document states rules (*"sentences with must, never, only, do
   not, forbidden"*) and every such sentence is a CONSTRAINT proposal with its exact excerpt. Only
   `CONSTRAINT` proposals from that reply are kept, and they are **added** to the first batch, never
   replacing it. If none come back the run continues with a note. Cause: harness run 15,
   2026-09-03 — eight requirements and zero rules from a document that had yielded 8–13 rules on nine
   previous runs.
6. **Dangling relationships are dropped, not the batch.** `dropDanglingRelationships` runs after
   every re-ask merge and before anything is saved. Known targets are every existing BRD handle,
   every proposal's own ref, **and the sequential `R<n>` handle each ADD will actually receive**. A
   relation line naming anything else is removed from that one proposal; everything else survives,
   and the count goes in the step label. Cause: harness run 20, 2026-09-03 — "N5 gates N15" where
   N15 was never defined. The bad edge reached apply, `addEdge` correctly refused it, and because
   apply reports one combined result **the single dangling handle cost the operator every proposal in
   the run.**
7. **Duplicate and echo marking.** `RequirementSimilarity`: at `NEAR_MATCH = 0.50` an ADD is
   unticked and flagged as a probable duplicate; at `SAME_REQUIREMENT = 0.85` it is re-cast as an
   EDIT against the matched handle. Separately, at `DRAWN_FROM = 0.60` containment against an
   **unanswered** question's own quote, an ADD that merely restates the question is unticked with the
   reason spelled out. Thresholds were measured over fifteen hand-labelled pairs.

Every check must name its test in the fixed form `swarm.accept.<Area>Test#<methodName>`. These
arrive as `TestRefOrigin.PROPOSED`.

**Apply.** Provenance is a `SourceRef` on the first non-excluded document. `statesAnyRule` is checked
*before* any write, so a batch of pure requirement edits never turns a document's rules off. A rule
proposal goes to `ProjectRules.stateRule` — **a rule never becomes a requirement**. Requirements are
written first and edges second, resolving refs through the assigned-handle map; unresolvable edges
are reported, never silently dropped. A structure-only EDIT (relationships but no wording) does not
bump the content revision, so passing tests are not staled. If nothing applied, the flow stays in
REVIEW so it can be applied again.

**Nothing here deletes.** Retiring is what "delete" means for a requirement; superseded rules go
RETIRED and stay one click away.

## 1.4 Agreement

`com.swarmcoder.domain.AgreementGate` (`sc-domain`, deliberately, so the server and the browser
derive the refusal once). `rejectionFor(BrdRequirement)`, counting only criteria whose status is not
RETIRED:

- no live check → refused: *"<handle> has no check on it, so nothing could ever show it was met"*
- live checks but none names a test → refused: *"Fill in the test name on at least one check."*
- otherwise → may be agreed.

**The rule is weaker than "every check names a test": at least one live check must exist, and at
least one of those must name a non-blank test.** A requirement with five checks of which one names a
test passes the gate.

It gates **agreement, not creation** — drafts without checks are legitimate — and deliberately does
not apply to restoring an old revision, nor to a requirement that reaches ACTIVE by evidence.

Five call sites all derive from that one method: the Agree button, a form save that moves
DRAFT→ACTIVE (checked *before* the setters run), `promoteAllDrafts` (which names every held-back
requirement instead of silently agreeing nine of ten), autonomous mode (which records a `REFUSED`
decision and halts if nothing can be agreed), and the browser form, which shows the same sentence
beside a **disabled, not hidden**, Agree button.

Agreeing is one decision, not one per check: every PROPOSED criterion becomes ACCEPTED, the status
is set ACTIVE, and then immediately re-derived from evidence, so a requirement whose checks already
pass lands IMPLEMENTED.

## 1.5 Backlog and story planning

`sc-console/src/main/java/com/swarmcoder/console/BacklogPlanning.java` (1,433 lines).
Model role: `roles.storyPlanner → roles.chat → roles.utility`. `TOTAL_STEPS = 4`,
`MAX_QUESTIONS = 8`.

It refuses to start when no agreed requirement has a check, or when every check is already claimed
by a story.

**The briefing is split in two on purpose.** `THE REQUIREMENTS YOU MAY PLAN` holds the agreed ones in
full with their checks; `NOT AGREED — OUT OF SCOPE` holds every other non-retired requirement by
**name and status only, checks deliberately withheld**. The old briefing rendered the whole document
beside a sentence asking the model not to use it, and one end-to-end run answered with six stories
claiming sixteen checks, **fifteen of them from requirements still in draft** (2026-08-31). Every
worked example in the prompt uses the first *agreed* handle, so the format is never demonstrated on
an out-of-scope one.

**The planner is told the project's rules** (since 2026-09-25, harness runs 37–39). The briefing —
read by both the question round and the proposal call — ends with the rules in force, rendered by
`ConstraintBrief` over `ConstraintBrief.inForce`, the one definition of "in force" that
`ProjectRules` also uses, so the story planner and every worker see the same set. Under them,
`STORIES_SAY_WHAT_NOT_HOW`: the rules are settled (never ask about them, never plan a story to
deliver one), and a story says **what** the user gets, never **how** — its title, narrative and
rationale name no technology, library, storage mechanism or place data is kept unless a rule names
it, and never one a rule forbids. Before this the planner was the one agent never shown the rules,
and wrote "saves the current books and their ratings to localStorage" into the story goal of a
project whose rules mandate EclipseStore on the server — three runs in a row. With no rules in force
the briefing is byte-for-byte what it was. (`sc-console` cannot depend on `sc-knowledge`, which is
why the shared definition lives in `sc-domain`.)

**There are two independent single re-asks, and neither loops.**

- **Out of scope.** If any proposal references a requirement that is not agreed, the plan is rejected
  wholesale and asked once more with the violations named ref by ref. Fallback keeps whichever plan
  has fewer violations, ties going to the informed second attempt.
- **Unclaimed checks.** If any agreed check is claimed by no living story and no delivery proposal,
  one further turn names each unclaimed ref with its requirement handle and its test name, and
  separately names every enabler nothing builds on. The second reply is **merged** into the first
  (de-duplicated on title and handle), never substituted, so a retry that only needed one more story
  cannot drop an enabler the first reply got right.

**If checks are still unclaimed after that one re-ask, the run does not fail.** It goes to REVIEW and
the step label says how many checks were left unclaimed and which. The stated reasoning: a partial
plan the operator can see and extend beats no plan at all.

A malformed reply gets the same single retry, and then fails — **planning has no salvage path**;
salvage exists only in requirements intake.

Apply writes DRAFT stories in two passes (create, then link dependencies), because a dependency is a
story id and half the targets are being created by the same apply. `dependsOn` is a JSON array of
whole titles; a plain string is matched longest-first against known names rather than split on
commas, because the comma split turned one title into five fragments that named nothing and silently
discarded exactly the edges that mattered. If the story graph has a cycle, **every** dependency the
apply was about to write is cleared rather than leaving a partial ordering.

## 1.6 Ad-hoc stories

`sc-console/src/main/java/com/swarmcoder/console/AdHocStory.java` — the on-ramp for a codebase with
no requirements. It mints an `ENABLER` story, origin `AD_HOC`, state `RUNNING`, with no requirement
or criterion ids, actor `"human"`, rationale *"started directly from chat"*, title = the goal cut to
80 chars.

The load-bearing detail is **ordering**: the story is created and saved *first*, then the run is
started bound to it. It used to start the run and attach the story afterwards, and because the engine
advances the run on its own thread immediately and rebuilds it from its own copy at each transition,
the later write went to an object nobody read again — every freeform run's card stayed at "building"
forever. As the code puts it, it was not a race that sometimes lost: the engine is always faster than
the caller's next line.

## 1.7 INTAKE

A pure pass-through: it logs and transitions straight to `DESIGN`. All requirements work happened in
the Console before the run existed.

Two things are worth knowing about the boundary:

- **`StoryScope`** (`sc-workflow`) is what stops the architect inventing requirements. A criterion is
  in scope only when it is in the story's `criterionIds`, its status is ACCEPTED, **and** its owning
  requirement is agreed. Gating NFRs are recomputed each run by following `GATES` up the `REFINES`
  hierarchy, so a gate added today applies to the next run of an old story. Project rules are passed
  in as text and are **never scoped or inherited** — every rule applies to every story.
- **The architect may propose requirements but never add them.** `proposeMissingRequirements` turns
  the model's `missingRequirements` into DRAFT `BrdRequirement`s for triage, journalled as "architect
  proposed R9 while designing S3", and **nothing is built from them**. An agent that could add
  requirements to the document would make the whole promotion gate decorative.

## 1.8 DESIGN

**Reads:** the `StoryScope`, the run's own brief, and — since 2026-09-25 (harness run 37) — which
modules of the operator's checkout run only in a browser (`BrowserOnlyCode.survey`, see
`Agent-Knowledge-And-Operations.md` §3.6). When there are any, `AcceptanceTestReach.architectBrief`
is appended to the design's **user** message (never the system prompt, so every other project's
prompt is byte-for-byte unchanged): the acceptance tests are JUnit tests on a plain JVM and can never
run a line of that module, so every checked behaviour must be reachable through a contract type in a
JVM module, and a check worded as a browser action is proved at the layer that holds the behaviour.
In run 37 the design made the TeaVM client's `BookStore` the type the persistence check went through.
**Calls:** `roles.architect()` → `ArchitectClient`. A scoped design first runs an *unconstrained*
multi-turn read-only research phase (`RESEARCH_ROUNDS = 6`, `REFERENCE_CHARS = 9_000`,
`RESEARCH_RESULT_CHARS = 6_000`), then one schema-constrained call at temperature 0.2. The two
character figures are sized by the architect's own model's room since 2026-09-25 — exactly these at
or below 51,200 tokens, which includes the generic cloud shape the architect runs on — see
`Agent-Knowledge-And-Operations.md` §4.6.
**Produces:** a `DesignDocument` with requirements, decisions, contracts and risks.

### Contracts are the run's type vocabulary

`com.swarmcoder.domain.ApiContract`: `id`, `name`, `description`, `signatureSketch`, `typeName`,
`members`. `typeName` is fully qualified (`com.swarmcoder.demo.bookshelf.Rating`); `members` are
written as Java, one per entry (`"int rating"`, `"String title()"`). Both are optional and
blank-tolerant, and every check keyed off them does nothing when they are absent. `describe()`
renders `com.x.Book{int rating; }`.

`ArchitectClient.CONTRACT_VOCABULARY_RULE` is appended verbatim to the design, scoped-design and
revision prompts. It ends by saying the acceptance test classes the checks name are written by the
test author and are **not** contracts.

**An acceptance test class is never a contract**, enforced at four places:
`ArchitectClient.dropAcceptanceTestClassContracts` normalises every design as it returns, so neither
the PLAN prompt nor the validator ever sees one; `ArchitectClient.contractsNamed` can never resolve a
task's `deliversContracts` to one; `TaskGraphValidator.checkEveryContractIsDelivered` holds the rule
independently; and the PLAN prompt forbids any task writing into `src/test/java/swarm`.
`AcceptanceTestContracts.isAcceptanceTestClass` fires on either of two signals — the contract's
package is the test-author package (derived from `AcceptanceTestLocation.WRITE_SUBDIR`, never a
literal), or its name matches a class in the story's own test refs.

### The zero-contract gate

For a **story-scoped** run only (an ad-hoc run with an empty scope skips it), if no contract in the
design **names a type**, one mechanical re-ask goes out listing every check with its ref, its text
and its test, ending *"Name the types."* If the revision still names no type, the design is persisted
and **the run parks, staying in DESIGN**. Exactly one re-ask.

Note the trigger is "no contract names a type", not "zero contracts": a design full of contracts that
all lack `typeName` fails identically. Cause: harness run 21, 2026-09-04.

### Malformed and empty replies

`ArchitectClient.callForJson` does **one** retry with the parser's own complaint fed back; a second
failure stores the raw reply as a blob and throws. In the unscoped path a failure returns null and
the workflow builds a minimal design from the goal, logging *"Architect produced no usable design —
using minimal design."* In the scoped path the failure is caught inside `ArchitectClient` and a
design is still produced carrying the BRD requirements with empty decisions, contracts and risks.
`EndpointOutage` is rethrown everywhere and never degrades into a design.

## 1.9 DESIGN_REVIEW

Skipped entirely when the design is null or has no requirements — including the rules check.

**Two separate loops with different caps.**

1. **The rubric review does exactly one revision, and never parks.** `DesignReviewerClient.review`
   critiques completeness, testability and write-set partitionability. On objections the architect
   revises **once**, the design is re-reviewed, and the stage proceeds regardless with the verdict
   recorded as `APPROVED` or `NEEDS_WORK`. (`DesignReviewerClient`'s own class javadoc still says
   "At most two loops" — that comment is stale.) **Since 2026-09-25 (harness run 39) it is given the
   project's rules**, ahead of the goal, with `RULES_OUTRANK_THE_GOAL`: the rules outrank the goal's
   wording about how anything is built, completeness is judged by what the user gets rather than
   by the mechanism the goal named, and it must never object to a design for following a rule. In
   run 39 it objected that a server-side EclipseStore design "deviated from" a story that said
   localStorage. No rules → the prompt is exactly what it was.
2. **The rules-conflict loop is capped at `MAX_DESIGN_RULE_REVISIONS = 2`, and it does park.**

A rule conflict is detected cheapest-first: `ForbiddenTechGuard.check` for free, over the design's
decisions and contracts dressed as a throwaway `Task` that is never built or dispatched; only if that
finds nothing is one model call spent on `DesignReviewerClient.reviewDesign`, which sees the design's
own decisions and contracts and never the requirements.

Objections are enriched before going back to the architect (`RuleConflictFeedback.enrich`): the rule
in full (trimmed to 700 chars) plus the nearest existing example of doing it right, from
`Librarian.lookupApi` (trimmed to 600 chars).

After two revisions with objections still standing, the design is persisted `NEEDS_WORK` and the run
parks with a brief ending: *"A design that breaks a stated rule cannot be planned around: no amount
of re-planning fixes a design that still says to do the thing the rule forbids. Fix the design
yourself, or change the rule, then resume the run."*

Cause: harness run 19, 2026-09-04 — a design that named browser-side `localStorage` against a rule
requiring a server-side object graph reached PLAN unchanged, and three planner attempts in a row were
rejected for a defect none of them could fix from where they were standing, because the design that
fed all three still said to build it.

### A contract may only name library types that exist

Cause: harness runs 44/45, 2026-09-27 (Bookshelf demo on ZeroZ Stack, story "Add, edit, and remove
books"). The page contract promised `public com.zeroz4j.ui.ListView<Book> bookList`, beside
`com.zeroz4j.ui.TextField` and `com.zeroz4j.ui.Button` fields. ZeroZ Stack has no `ListView` at all
(its dynamic-list component is `com.zeroz4j.ui.component.KeyedList`), and its `TextField` and
`Button` live in `com.zeroz4j.ui.component`. The contract-delivery check compares a member's declared
type, so no worker could deliver that member; the page task burned its turns, one worker scanning jar
files for 290 seconds.

`ContractsNameRealTypes` runs inside the same loop as the rule conflicts above, **first and for free**,
with the same cap (`MAX_DESIGN_RULE_REVISIONS = 2`) and the same park. Its objections go to
`ArchitectClient.revise` next to the rule objections (`RuleConflictFeedback.enrich` passes them through
unchanged). It reads every fully-qualified name in each contract's **members** — field types, return and
parameter types, generic arguments, annotations — and takes each in order:

1. the type of some contract in the design → planned, fine;
2. declared in the operator's checkout (`ProjectTypes`) → fine;
3. a JDK package (any package of a `java.*`/`jdk.*` boot-layer module) → judged by the JDK;
4. in the namespace the checkout already writes code into (shares the first three segments with a
   checkout package) → **left alone**: a write set may still create it;
5. in a namespace a reference checkout covers (`LibraryTypes`, over every knowledge root except the
   project's, built once per process by `Librarian.libraryTypes()`) → judged against that checkout;
6. anything else — a library with no source checked out, e.g. `org.teavm.jso` — **left alone**. There
   is no dependency-jar index; "no source" never becomes "does not exist".

A simple name (`Book`, `List<Book>`) is never judged: which type it means depends on imports the
contract does not have. The signature sketch and description are not read.

The objection names the contract, the type, the member it was in, and the reference checkout that lacks
it, and ends with what to do. When the checkout has a type of the same simple name elsewhere in that
namespace, it says so and nothing else (*"Its TextField is com.zeroz4j.ui.component.TextField — write
the member with that name."*). Otherwise it offers up to three real types sharing a word of the name,
ranked by shared words and then by whether their javadoc uses one of those words, each with its
javadoc's first sentence — for `ListView`, `KeyedList` first. The park brief adds that if the type does
exist in the version the project builds against, the reference checkout is a different version (run 44:
project 0.8.0-SNAPSHOT, reference 0.9.1).

**Why here and not at PLAN.** The planner names contracts by name and `ArchitectClient.contractsNamed`
hands each task the design's contract word for word, so no planner attempt could correct a member type
— the same trap run 19 fell into with rule conflicts. And PLAN adds nothing this check needs: the only
extra facts it has are write sets, which can only create types in the project's own namespace, which
step 4 already leaves alone.

### A revision that says less is discarded

`ArchitectClient.ReviseAttempt` discards the revision and keeps the original when the revision has
fewer requirements than the original, or when the original had contracts and the revision has none,
or when it did not parse. `attempt.design()` returns the original in that case so no caller has to
branch — but **a discarded attempt still counts** against the cap. Cause: harness run 21.

### A revision that merely forgot a section is re-asked once before being discarded

Cause: harness runs 41 and 42, 2026-09-26 — two consecutive live runs on DeepSeek V4 Flash, which has
no structured-output support on its endpoint (`ds4` refuses `response_format json_object`, so the JSON
shape asked of `ArchitectClient.revise` is enforced by the prompt alone) and reasons at length before
answering. Both runs logged `revision discarded: it dropped 1 requirement(s) and all contracts` — the
architect's revision reply parsed cleanly but never mentioned `requirements` or `contracts` at all, so
design review could never improve a design on this model: every revision it produced was thrown away
and the original, unchanged design went back for another round of the same objections.

`revise`'s system prompt already asked for "the FULL corrected design", but a model reasoning about
"what the objections are about" can still decide that repeating an untouched section is unnecessary —
the instruction said what to send, not that leaving a part out was never acceptable. It now also says
so directly: every requirement and contract the current design lists must appear again, copied over
unchanged, unless an objection specifically asks to remove or replace it.

When a revision still comes back short of that, `ArchitectClient` looks at the *wire* reply — the
object `LlmJson` parsed, before it became a `DesignDocument` — to tell two things apart that look
identical once they are both empty `List`s:

- **The model never mentioned the section.** Its field is `null`, because the key was never in the
  reply's JSON at all (`LlmJson` leaves an unset field `null`; it does not invent one). This is
  read as a reasoning slip, not a decision, and earns ONE re-ask: the same system prompt, and a user
  message naming exactly which requirements and which contracts (by text and by name, taken from the
  ORIGINAL design so the bad reply is never trusted for their content) must be sent again. If the
  re-ask's reply now carries everything the original had, it replaces the design as a genuine
  revision; if it still comes back short — of the same or a different section — the original is kept,
  exactly as before this fix.
- **The model stated the section is now empty**, e.g. `"contracts": []`. That is a considered claim,
  not an omission, and is refused immediately with no re-ask spent on it — unchanged from harness run
  21's original fix. A revision that mixes the two (say, forgets `requirements` but explicitly empties
  `contracts`) is treated as a deliberate drop overall: one section the model clearly meant to remove is
  reason enough not to assume everything else empty was merely forgotten.

The re-ask is spent at most once per `revise` call; it does not turn the rubric loop's one revision or
the rules-conflict loop's `MAX_DESIGN_RULE_REVISIONS` attempts into more attempts than either loop
already allows — from the workflow's side, `revise` still returns exactly one `ReviseAttempt`, whether
that took one model call or two.

There is also a pre-flight guard: if the design has requirements or contracts but rendered to nothing,
the reviewer is not called at all, and the result says this is a rendering bug rather than a verdict.

`DesignReviewerClient.unavailable(note)` fails **soft** — it returns approved with the note in the
objections list, and callers detect that by wording and log it rather than treating it as an
objection.

## 1.10 PLAN

**Reads:** the stored design, the story scope, and — read fresh from the operator's checkout — the
repository's real module and source-root layout (`BuildLayout.read`), rendered for the planner by
`RepoLayoutBrief`. The planner used to be told nothing about the layout and guessed a conventional
one, which is how a run once planned every task into a `src/main/java` at the root of a repository
whose root pom only aggregates modules. `AcceptanceTestLocation.resolve(layout)` decides the
acceptance directory before any model call, so three things then agree because they are one string:
what the test author is told, what the path policy protects, and what the acceptance command selects.
When the build has browser-only modules, `RepoLayoutBrief.render(layout, survey, acceptanceModule)`
adds the same paragraph the design got (harness run 37, 2026-09-25). The planner's own rule — "a check
about what the user sees or does is answered by the client/UI task" — is **unchanged**: the check
stays on the client task, its test proves the JVM half, and it usually goes green once the server
wave has landed, which `ChecksAlreadyProved` records as success (§1.11).

**Calls:** `ArchitectClient.planAttempt(...)`, with a system prompt that is identical on every
attempt; only the user message grows.

**`MAX_PLAN_ATTEMPTS = 3`**, raised from 2 when every retry was made a real one. A retry's user
message ends with (`ArchitectClient.retryFeedback`):

1. *"Your previous plan was rejected for these reasons:"* — one objection per line. An objection an
   **earlier** attempt was also rejected for, word for word, is marked `[AGAIN]`, with a sentence
   saying it was fixed once and a change made for another objection brought it back.
2. *"An earlier plan of yours was also rejected for the following. Your previous plan had fixed them;
   your new plan must not bring any of them back:"* — every objection from the attempts before the
   previous one that the previous plan did not repeat.
3. *"Your previous plan, verbatim:"* and the reply.

The `[AGAIN]` mark and item 2 are from harness run 40 (2026-09-26). Before, the planner saw only the
previous attempt: attempt 3 fixed attempt 2's objection (two contracts not delivered) by going back
towards attempt 1, and brought attempt 1's objection back word for word.

**Every objection says how to satisfy it.** The validator's messages end with the remedy in plain
words, so the same text serves the planner, the run log and the park brief. See "Everything
`TaskGraphValidator` checks" below.

**There is no fallback plan.** When all three attempts fail the run parks with a brief naming the last
objections and the blob ref of the last reply, and *"Build it again to try once more, or change the
story."* The old single-task fallback was deleted on 2026-09-03: a single fabricated "Implement Goal"
task had reached TEST_AUTHORING with an empty write set and no acceptance criteria, and parked three
stages later for a reason that had nothing to do with the real problem.

**The one deterministic repair on the last attempt: an unused enabler is dropped** (`UnusedEnablers`,
harness run 40). When the third attempt's **only** fault is tasks that claim no check and that no
checked task builds on, those tasks are removed, with no model call, and the smaller plan is validated
again. It is accepted only if it passes everything. The drop is refused, and the run parks as before
with the refusal in the log, when the plan has any other violation, when a check is unclaimed, when a
dropped task delivers a contract that names a type, or when a task that stays uses a type a dropped
task writes. The log line reads *"PLAN attempt 3: dropped task '…': it claims no check and no task that
claims one builds on it … Every check of the story is claimed without it (R6:C1 by '…') … If the story
needs that work — for the app to work end to end, say — the story is missing a check that proves it:
add one and build again"*.

- **Why drop rather than park.** The rule exists to stop work no check proves. Dropping does exactly
  that, and the plan left still delivers every check. Parking spends the whole run to get, at best,
  the same plan without the task on a later build.
- **What it costs.** The architect may have meant the task for the app to work end to end (run 40's
  service implementation is what the browser client would call). A behaviour no check covers is a gap
  in the story; building unproven code hides that gap rather than closing it, so the log line says so.
- **Why only on the last attempt.** The objection offers moving a check onto the task, which may be
  the better plan, and only the planner can judge that. It gets two tries first.

### Tasks and write sets

A `Task` carries `writeSet` and `readSet` as `Set<String>` of repo-relative entries — **directories
or files**. (There is no `TaskSpec` type.) The planner prompt has always allowed files; what changed
on 2026-09-05 is that `TypeDeliverability` stopped misreading a file entry's own basename as package
structure, so a file entry now matches the same way a directory entry does.

Every task also carries `acceptanceTestDir`, `deliveredContracts`, `criterionIds`, `storyId`,
`requirementIds`, a budget and a swarm policy.

### Enablers and the one-check-one-task rule

An **enabler** is a task that claims no check but that another task depends on. The prompt states the
rule: every check is answered by exactly one task — the one whose own work finishes the behaviour the
check proves; the earlier ones are enablers it depends on. A check about what a user sees is answered
by the user-facing task.

"One check, one task" is **normalised, not rejected**: among tasks claiming the same check,
`TaskGraphValidator.normalizeOneCheckOneTask` keeps it on the leaf of their dependency chain and
strips it from the rest with a warning, breaking ties toward a UI/client module and otherwise toward
the last leaf in plan order. It is a **violation** only when the claimers do not sit in one connected
dependency component.

**Enabler must be built on:** `checkEnablersAreUsed` requires every task with no criterion ids to be
transitively reachable from something that has one, else *"it is an enabler nothing builds on"*. It is
skipped entirely when nothing in the graph claims anything, to avoid repeating the coverage message
N times. The PLAN system prompt has stated the rule since run 40; before, it was only enforced.

Since run 40 the objection lists the ways out that exist in this plan. For run 40's shape it reads:

> task 'Implement BookshelfServiceImpl delegating to BookshelfStore' claims no check, and nothing that
> depends on it claims one either — it is an enabler nothing builds on, so no check would ever prove
> its work. Fix it in ONE of these ways: (1) drop the task — every check of this story is already
> claimed without it (R6:C1 by 'Implement BookshelfStore with EclipseStore persistence'), so nothing
> a check proves is lost; (2) if this task's own code is what a check proves, move that check's ref
> out of the criterionRefs of the task that claims it now and into this task's, and make this task
> depend on every task whose types it uses.

A third way, *"add an edge from '…' to that task"* naming the checked tasks that could wait for it, is
offered only for checked tasks this task does not already build on. In run 40 the only checked task
was the store the service delegates to, so an edge back would be a cycle and the option is left out.
When a check is still unclaimed, (1) says *"drop the task, if no check needs its code"* instead.

**An edge no code needs does not count — for browser-only work** (harness runs 44/45, 2026-09-27). The
planner made the story's only checked task, "Implement BookListService on the server with EclipseStore
persistence", depend on "Create BookListPage UI component in client module", which claimed no check.
The server code used `Book` and `BookListService` and nothing of the page's (the run log's own "told
'…' where the 2 type(s) it uses from other tasks live" line says so). The edge satisfied the rule above
and nothing else — and when the page task could not be finished, the one task that proved the story
could never start.

The evidence rule: an enabler whose **whole output is known and browser-only** — every write-set entry
is a build file or a `.java` file `TypeDeliverability.typeNamed` can read, each in a module
`BrowserOnlyCode` marks browser-only, and every typed contract it delivers is one of those files — is
built on only when some task's **code uses one of its types**, read exactly as `TypeDependencyOrder.uses`
reads it (the members and sketch of the contracts that task delivers, and its instructions), and that
task claims a check or is itself built on. The plan's edges are not evidence for such a task: it is on
no classpath unless its types are named, and no acceptance test can run it.

Every other enabler keeps the old rule — any path to a checked task counts. A JVM enabler can be needed
without its types being named by the task that waits for it: that task's acceptance test may construct
the class it writes (run 38 made that legal), or reach it at run time through the stack's wiring, so an
unnamed type there is not evidence of nothing. Treating it as such would send good plans back.

The objection for such a task names the edge and says to remove it: *"The plan makes '…' wait for it,
but that does not count: that task's code uses none of the types this task writes (BookListPage), and
they are in bookshelf-demo-client, which runs only in a browser, so no acceptance test can ever run them
either. That edge only holds that task back behind work it does not need: remove it, then fix this task
in ONE of these ways: …"*. It never offers "add an edge" to a task already waiting for it. On the last
attempt `UnusedEnablers` drops such a task exactly as it drops any other orphan — and refuses, as
before, when the task delivers a typed contract (run 44's page did), so that plan parks at PLAN rather
than dispatching the page ahead of the server task. The PLAN prompt now also says a dependency counts
only when the dependent's code uses what the task builds.

The validator knows which modules are browser-only only when constructed with the survey
(`new TaskGraphValidator(survey)`); PLAN builds one per run from the same survey the planner is shown
and uses it for every attempt, the last-attempt drop and the re-check of the stored plan. A validator
built without one (`PlanTaskLinkageCheck`, older tests) behaves exactly as before.

### Everything `TaskGraphValidator` checks

In order:

1. no tasks at all → violation, return immediately
2. every edge references a known task
3. no dependency cycle (Kahn's algorithm)
3a. **a task runs after the tasks whose types it uses** (`TypeDependencyOrder.apply`, harness run
   39, 2026-09-25) — on an acyclic graph only, and before disjointness because it can add edges.
   See below.
4. **write-set disjointness** — two tasks not ordered by the transitive closure may not overlap;
   build files are excluded (`pom.xml`, `build.gradle`, `build.gradle.kts`)
5. **write set is writable** — if `PathPolicy` refuses *every* entry, *"task '…' may write nothing"*
6. **write set is in the build** (only when the layout is determined) — a `src`-shaped path owned by a
   repository root that compiles nothing is a violation; one owned by a directory that is not
   currently a compiling module is a warning, since the plan may intend to create it
7. **every contract delivered exactly once** — each design contract naming a type, excluding
   acceptance-test classes, appears in exactly one task's `deliveredContracts`. Silent when the design
   names no types.

Every violation ends with its remedy (harness run 40): the cycle message names the tasks in or
behind the cycle; overlapping write sets are told to give the path to one task or order the two; a
task that may write nothing is told the tests are written for it; a write set outside the build is
told to use one of the listed directories; an unclaimed check is told to go on the task that finishes
it; a contract claimed twice is told to stay on the one task that writes the file. The two
`TypeDependencyOrder` violations and the contract-not-delivered one already said what to do.

Then, **only for a story-scoped run**: 8. normalise one-check-one-task; 9. criterion coverage — every
criterion in the slice claimed, nothing claimed from outside it, with the wholly-unassigned case
getting one message naming every ref rather than N identical ones; 10. criteria naming the same test
class must be claimed by the same task; 11. enablers are used. With an empty scope none of these run
and a task with no criteria is only a warning.

Watch out for three stale javadocs in this class that still justify a rule by pointing at "the
single-task fallback" — that fallback was deleted.

### A task runs after the tasks whose types it uses

Harness run 39, 2026-09-25: "Create shared BooksService interface" had the contract
`BooksService{List<Book> getBooks(); … void saveRating(Rating rating); }` and depended on nothing;
"Create shared data model classes (Book and Rating)" wrote `Book` and `Rating`. Both went out in
wave 1, and no worker of the interface task could compile a line. Every other invariant held.

`TypeDependencyOrder.apply` reads **who writes a type** from each task's `deliversContracts` and
from its write set's FILE entries (`TypeDeliverability.typeNamed` — source root + package path +
`SimpleName.java`, the reading run 38's fix added), and **who uses one** from the members and
signature sketch of the contracts a task delivers (contract evidence) or, failing that, from its
instructions (prose evidence). A qualified name is matched exactly; a simple name must resolve to
one planned type, preferring the using task's own package.

- **Added, with a log line** (`PLAN warning: added dependency: task '…' now depends on '…': the
  contract it delivers uses Book, Rating, which '…' writes…`), when the plan has no path between
  the two. This is mechanical, so it costs no planner attempt. Contract evidence is applied first.
- **Sent back to the planner** (a violation) when contract evidence shows the plan ordered the
  pair backwards, or when two tasks' contracts name each other's types — neither order compiles.
- **Left alone, with a `dependency not added:` line**, when only instructions point that way and
  the direction is unsure (both tasks' prose names the other's types, or the planner's own edges run
  the other way), when a simple name is ambiguous, and — silently, because there is nothing to
  read — when the contracts have no members and the instructions name nothing.
- **The "planner's own edges run the other way" line is the ordinary case, not a fault.** Harness
  run 40 logged it on every attempt: the shared-model task's instructions mentioned `BookshelfStore`,
  which the store task, correctly planned after it, writes. Checked 2026-09-26: no edge was added,
  nothing was sent back, and no worker was told to import it. The line now reads *"task '…' mentions
  BookshelfStore (written by '…', which the plan runs after it) in its instructions — read as
  describing what a later task builds on its work, which is normal; the planner's order is kept"*.

The PLAN system prompt now also states the rule. On the **accepted** plan,
`TypeDependencyOrder.annotate` appends to each such task's instructions a block headed "TYPES YOUR
CODE USES THAT ANOTHER TASK OF THIS PLAN WRITES": each type's fully-qualified name (with the
contract's members), the task that writes it, and whether that task finishes first. Run 39's worker
had looked for `Book` in `…bookshelf.model`, the package the knowledge brief's worked example
happened to use.

### The plan declares its own missing dependency

This is the mechanism that lets a run add a Maven dependency the operator forgot.

1. `GreenfieldWorkflow.readBuild(layout)` gathers `BuildFacts`: what each module declares
   (`ManifestParser`), what the **offline** Maven repository actually holds
   (`DeclarableArtifacts.Catalog`), and the reactor's own coordinates.
2. `declareMissingDependencies(...)` calls `RulesVersusManifest.check(rules, documents, declared,
   inspectedPoms, ownBuild)`. `documents` is every `SourceDocument` any flow of the project ever
   ticked *technical*, **in full text**, because the stated rule is the analyst's restatement and a
   paraphrase drops the backticked artifact name.
3. `RulesVersusManifest` splits rules on bullets and documents on sentences, then runs a deliberately
   narrow extractor: a Maven `group:artifact` coordinate (the group must contain a dot), a backticked
   `foo-bar` token, or a bare artifact id straight after `through|module|dependency|uses|via`. It
   never yields the build's own module names, groupIds or repo-root entries. Each artifact named but
   not declared becomes one `Finding` carrying a 120-character excerpt.
4. `BuildFilesInTheJob.declareMissing(...)` turns each finding into work. The module is chosen in
   strict order: the rule's own words naming a module directory; then the artifact's tier
   (`store`/`server`/`persistence` versus `client`/`ui`, read from what modules already depend on);
   then, among modules the plan writes to, the one with the fewest declared dependencies; then any
   module. The reason it chose is written on the task in plain words.
5. **Placement:** the first task that already writes that module's sources gets the instruction
   appended and the build file added to its write set. Only when no task writes the module is a real
   enabler task created — *"Declare `<artifactId>` in `<module>`"*, write set = the build file alone,
   no criteria, with an edge to every root task.
6. The instruction names the coordinate, the file, whether it came from the rules or from a named
   technical document, the rule excerpt, and says to add groupId and artifactId with **no `<version>`**
   because the version is already managed. OpenRewrite's `org.openrewrite.maven.AddDependency` recipe
   is offered as an option, not a requirement.
7. **The one park:** the artifact is not in the offline Maven repository. Candidate builds run with
   `network=none`, so no work inside the run could fix it; the brief names the artifact, the poms
   checked, and the exact local repository path. "Nothing is declarable" and "nothing was checked" are
   kept distinct.

Separately and unconditionally, `BuildFilesInTheJob.expandWriteSets` runs before validation for every
plan: every module a task's write set reaches contributes its build file. Two concurrent tasks in one
module deliberately share it.

### Plan versus rules

After the validator has accepted the shape, `reviewPlanAgainstRules` runs — nothing at all when the
rules brief is blank.

- **`ForbiddenTechGuard`** is free. It reads task titles and instructions only, against a fixed short
  vocabulary — `Spring, JPA, Hibernate, Flyway, Liquibase, REST, JSON, JavaScript, TypeScript,
  Vaadin` — in an introducing position (`use|add|implement|via|with` + optional article + the term),
  where the rule chunk mentioning that term reads as a prohibition, and where a 24-character window
  before the match contains no negation. It deliberately does **not** catch `localStorage`; that word
  is not on the vocabulary, and that case is caught only by the model call.
- **`DesignReviewerClient.reviewPlan`** is called only when the guard finds nothing. It rejects a task
  whose instructions clearly call for doing something a rule forbids, explicitly including the
  loophole *"in-memory storage is fine if the database isn't set up yet"*, and is explicitly told not
  to object merely because a task fails to mention a rule or because a task obeys one. Its objection
  shape is fixed — `task '<title>' conflicts with rule '<rule title>': <one sentence>` — the same
  shape the guard emits and `RuleConflictFeedback` parses.

**After** the accepted plan is enriched and stored, the **stored** graph is re-validated. A violation
that appears only there parks the run saying *"The plan was accepted and then damaged before it was
stored… This is a defect in SwarmCoder's own PLAN stage."*

## 1.11 TEST_AUTHORING

Two pieces: `authorAcceptanceTests(run)` writes the tests, then `redCheckFailure(run, scope)` proves
they are red.

### Where the tests go

`AcceptanceTestLocation` (`sc-verify`): `PROTECTED_SUBDIR = "src/test/java/swarm"`,
`WRITE_SUBDIR = "src/test/java/swarm/accept"`. The module is **the one whose test classpath reaches
the greatest number of the build's other compiling modules**, computed breadth-first over the
declared dependency graph, ties going to the earliest in reactor order so the answer is stable. One
compiling module → that one. An undetermined layout falls back to the repository root, carrying the
reason. When the best module reaches zero others (a flat reactor), `seesEveryModule()` is false and a
note says a criterion needing two modules cannot be tested anywhere in that repository as it stands.

The files are written into a **worktree of the run's own**, cut from the run's pinned base, and
committed on `swarm/tests/<runId>`; `run.acceptanceTestsCommit` records the sha. Not the operator's
checkout — see §0.3.

### What the test author may write

`AcceptanceTestVocabulary` allows: the design's contract types, **every type already in the
checkout**, and JDK types (asked of the running JVM by `Class.forName` over eleven packages, not a
hard-coded list). It refuses an import whose package is a contract package or a package this project
writes into where no such type exists, and a simple name referenced but not declared, imported,
contracted, in the tree, or in the JDK. It is silent by design on wildcard imports, static imports,
unreadable files and any library type — and, critically, **a design that fixes no type names at all
returns CLEAN**, because checking against the checkout alone would refuse every acceptance test ever
written.

The test author is also given a **classpath line**, added 2026-09-05 after harness run 26:

> THIS TEST FILE LIVES IN THE MODULE '<module>' (the repository root when that is blank), and a
> JUnit test there can import only what that module's own build file declares: <artifact ids>. Never
> import a package from a different, undeclared module — even one that exists elsewhere in this
> checkout — and never a package that belongs to this project's client or UI layer.

The artifact ids come from parsing that module's manifest, cached per module. In run 26 the author
had imported a client-only package into the server module's acceptance test.

Since 2026-09-25 (harness run 37) the artifact ids of the build's **browser-only** modules are taken
off that list — `bookshelf-demo-server` declares `bookshelf-demo-client` only to package its
JavaScript bundle, and listing it as importable, beside "never the client layer", is half of how run
37's author came to call it — and a second paragraph, `AcceptanceTestReach.authorBrief`, names each
browser-only module, what its build file declares, its packages, and the rule: a JUnit test that calls
that code throws `UnsatisfiedLinkError` even when everything compiles, so prove the behaviour through
the JVM modules; "after restarting the browser, the books are still there" means they are still there
when the server side is asked again from scratch. Empty when the build has no such module.

### A test may not call code that only a browser can run

`AcceptanceTestReach` (harness run 37, 2026-09-25). Run 37's test,
`swarm.accept.BookPersistenceTest` in `bookshelf-demo-server`, called
`com.swarmcoder.demo.bookshelf.client.BookStore.getInstance()` — a TeaVM class — and reset the
singleton by reflection "to simulate a browser restart". Every gate was satisfied (BookStore was a
contract; the test did not implement it; "BookStore does not compile" was a healthy red), and both
workers died on `UnsatisfiedLinkError: org.teavm.jso.browser.Window.current()`.

The check reads the written files with comments and strings stripped, takes every import (a static
import by its class) and every inline fully-qualified name, and refuses one that is a browser runtime
package (`org.teavm.`, `com.zeroz4j.client.`, `com.zeroz4j.ui.`, `com.google.gwt.`,
`org.gwtproject.`, `elemental2.`) or that `BrowserOnlyCode.Survey.moduleOwning` traces to a
browser-only module. It runs after the vocabulary and self-implementation checks are clean, re-asks
**once** with the same delete-then-rewrite discipline, and holds the correction to all three checks
again, because a test that stops calling the client by inventing a server type nobody delivers has
swapped one broken test for another. A second miss parks with `AcceptanceTestReach.brief`.

This is deliberately strict: every class of a browser-only module is refused, including a pure-logic
class that would happen to run on the JVM, because what a class that does not exist yet will touch
cannot be known when the test is written.

### A test may not implement the contract itself

`SelfImplementedContract` (author decision 2026-09-05, harness run 30). Naming a contract is
required; **being** one is forbidden. Detection is regex over the source with comments and string
literals stripped first, against the design's contract simple names:

- `new Contract(...) {` — the trailing brace is what separates standing in for the type from
  constructing it; a plain `new Book()` is not evidence
- `implements Contract` / `extends Contract`
- `Contract x = ... ->`
- `mock(Contract.class)` / `spy(Contract.class)`
- a type declared in the test file named `<C>Stub`, `<C>Fake`, `<C>Mock`, `<C>Double`, `Stub<C>`,
  `Fake<C>`, `Mock<C>`, `InMemory<C>`
- a mocking-framework import plus any whole-word mention of a contract

It runs only when the vocabulary check is already clean. The re-ask happens **once**, carrying a
`wiring` sentence read off the checkout — CDI when the contract's own source says so, else the
delivered implementation type found by shape (`<C>Impl`, `Default<C>`, `<C>Implementation`,
`<C>Bean`), else a general fallback. It is never blank. A second miss parks.

Both re-asks use the same discipline: the first attempt's files are **deleted from the tree** before
the correction is written, so a file the correction does not rewrite cannot be committed. If the
correction is unusable the first attempt's files are written back and the run parks.

### The audit before the red check

`AuthoredTestAudit` reads the test ids back **out of the files** and checks that every criterion's
named test is one of them. A check naming no test, or naming a test nobody wrote, parks. It **never
writes the reference back** — a self-satisfying requirement is worse than a hand-typed string. It
accuses only on positive evidence: a file that yields no ids downgrades every mismatch to a note.
And a **one-file-two-tasks** guard parks the run when the author writes the same path for two tasks,
because the second write silently replaced the first.

### The red check

`RedChecker` (`sc-verify`) returns `RedCheckResult(red, results, note, missingTypes, brokenTypes,
selfInflicted)`.

**Red** is one of: a compile failure of the acceptance stage — recognised by the output containing
`cannot find symbol`, `compilation error`, `compilation failure`, `compilefailed`, `error: package `,
`cannot resolve symbol` or `unresolved reference` — or reports produced with at least one failure or
error.

**Not red:** an infrastructure error without compile markers; no test reports produced at all; and
acceptance tests that already pass.

`selfInflicted` is stamped when the first failure was thrown only in the test's own frames with
nothing delivered on the stack. It is read from the verification summary, never re-derived.

Then `TypeDeliverability.undeliverable(missingTypes, tasks)` decides which kind of red this is. A
symbol is deliverable if any still-to-run task promises it as a contract or could create it from its
write set. It **fails open** everywhere: no tasks, no missing symbols, an unreadable source root, or
a task owning a whole source root all mean "could not settle", never "broken".

**So: missing contract types = a healthy red. Anything else = a broken test.**

A build file in a write set (`pom.xml`, `build.gradle[.kts]`, `settings.gradle[.kts]`, `build.xml`)
declares no type and delivers nothing. Before brownfield harness run 43 (2026-09-26) it had no source
root in it, so it read as "an unreadable layout, may write anything", and run 43's task — writing
`Document.java` and `pom.xml` — counted as able to deliver every name in the plan.

**A test that misuses code that already exists** (brownfield harness run 43, 2026-09-26). Target jsoup;
one task, "Guard Document.ensureMetaCharsetElement against empty XML documents". Line 16 of the
acceptance test passed a `Parser` where jsoup's existing `Document` constructor takes a `String`. The
red check confirmed that as "they reference not-yet-implemented symbols — red state confirmed",
because *any* compile failure used to read that way. Both workers wrote the right guard, both
candidates failed on that line, four repair workers changed nothing (the guard was already there, the
test is not theirs to edit), and the task was BLOCKED.

`RedCheckResult` now keeps the compile output (`compileOutput`, null when the red is not a compile
failure), and after the undeliverable-type question `redCheckOne` asks
`AcceptanceCompileErrors.classify` (`sc-verify`) about every javac error **located in the task's
acceptance test files** (errors elsewhere are the tree's, not the test's). Each is an absence or a
misuse:

| the compiler said | healthy red when | otherwise |
|---|---|---|
| `cannot find symbol` — class in `package p`, or `package p does not exist` | `TypeDeliverability` — unchanged | broken |
| `cannot find symbol` — a method, field or nested type on another type (`location: variable doc of type org.jsoup.nodes.Document`) | a still-to-run task's contract names that owner type, or its write set can hold the owner's own **file** (`TypeDeliverability.mayWrite`: a file entry naming exactly that type, or a directory covering its package; build files and entries with no source root count for nothing) | broken — always broken for a `java.`/`javax.` owner |
| `cannot find symbol` located on the test's own class (no import, no declaration) | a contract or a write-set file supplies a type of that simple name, or a contract member of that name | broken — a missing import or a helper nobody wrote |
| `cannot find symbol` whose `symbol:`/`location:` lines cannot be read | always | — (fails open) |
| anything else: incompatible types, cannot be applied to given types, no suitable constructor, unreported exception, already defined, syntax errors | a still-to-run task's contract promises a member the pre-change tree does not carry (`ContractDelivery` shortfall, computed by `MiscompiledAcceptanceTest.plannedChanges`) and that member's name appears in the error or on the offending source line | broken |

The write set is deliberately **not** enough for a misuse: run 43's task wrote exactly the file whose
constructor the test misused. Adding a member is what editing a file routinely does; changing an
existing signature breaks every existing caller and is never incidental — a plan that means it says so
in a contract. The one decided ambiguous case — a missing member on an existing type that no task
writes and no contract names — is **broken**: nothing in the plan can put it there. The costs were
weighed: a false "broken" costs one author re-ask; a false "red" costs a swarm, a repair round and a
blocked task.

A broken reading goes back to the author **once** (`reauthorMiscompiledAcceptanceTest`, through
`TestAuthorClient.repairBrokenTest`) with `MiscompiledAcceptanceTest.reask`: the compiler's own
`file:line: message` lines, why no task can fix each one, and the real signatures of the existing
members those lines use, read off the checkout (`ProjectTypes.exposedMembers` — public/protected
members only; a constructor only when the line says `new Type`). The correction is red-checked on the
same throwaway tree and re-classified; still broken, green on the untouched tree, or naming an
undeliverable type → the run parks with `MiscompiledAcceptanceTest.park`. At wave time it parks before
dispatch. Every other correction path (undeliverable type, browser-only reach, the test repair after
verification) re-runs the same classification on its correction, because each can trade its fault for
this one.

**A red that no code can turn green** (harness run 37, 2026-09-25). Before the deliverability question,
`GreenfieldWorkflow.redCheckOne` asks whether any failure of the red state shows the test reached
browser-only code — `BrowserOnlyCode.reachedIn`: an `UnsatisfiedLinkError` on the header or a
`Caused by:` line, a browser-runtime `(Native Method)` as the throwing frame, or a
`NoClassDefFoundError`/`ClassNotFoundException`/`ExceptionInInitializerError` naming a browser-runtime
package. An ERROR like that is not an assertion that did not hold and not something a candidate can
fix. At TEST_AUTHORING it goes back to its author once (`reauthorUnrunnableAcceptanceTest`, using
`TestAuthorClient.repairUnrunnableTest`, whose framing says the test *compiled* and then hit the
browser); the correction must pass the reach check, must not still reach browser code, must not name
anything undeliverable, and must not be green on the untouched tree. At wave time it parks before the
wave is dispatched, the same rule as an undeliverable type.

In run 37 itself this signal **could not** appear before the workers: the class the test called did
not exist until the client task wrote it, so every red-check before dispatch read "does not compile".
It appears whenever the browser-only class is already in the tree — a change to an existing client, or
a later wave after a client task delivered — and it catches a test that reaches browser code without
importing it. The static reach check above is what catches run 37's exact case.

At TEST_AUTHORING a broken test goes back to its author **exactly once per task**
(`reauthorBrokenAcceptanceTest`): the tests branch is opened, the task's current files are read, one
repair call is made with no internal retries, the files are overwritten in the red-check worktree,
and `RedChecker` runs again. Corrected → committed onto the tests ref and the run's tests commit is
updated. Still broken → the run parks.

At **wave** time there is no repair: the run parks immediately, because a wave is already under way.

Other pre-conditions that park here: a task with checks but no authored test file (the message
distinguishes "the file was written for another task" from "nothing was written", quoting the
recorded failure reason rather than blaming the endpoint); no task claiming anything while the run
answers for checks; and an acceptance stage that ran and executed **nothing**, which points at the
acceptance selector in `.swarmcoder/verify.yaml`.

**Already-green tests mean different things at different times.** At TEST_AUTHORING nothing in the
run has touched the tree, so a green acceptance test is evidence about the *test* — it measures
nothing new — and the run parks. At a later wave the *run* is what made it green, which is success:
`Task.checksAlreadyProved` is written and carried to the judge instead. See §1.14.

### Malformed and empty replies

Three allowances inside `authorTests`, each exactly one retry: an unparseable reply; a reply that
parses but has no files while the task answers for at least one check; then the vocabulary,
self-implementation and browser-only reach re-asks. `EndpointOutage` is rethrown at every layer with the prompt charge
refunded — returning no files on an outage would let the red check wave the swarm through under the
empty-suite allowance. `write()` mechanically refuses any file resolving outside the protected
directory, logging loudly and telling the model nothing.

---

## 1.12 EXECUTING — waves and dispatch

`SwarmEngineImpl.executeRun` walks the task graph in topological waves. Every task in a wave is
dispatched, verified, judged and selected; the wave's winners are merged onto the progress branch;
the next wave is cut from that.

### How many candidates, at what temperature

`SwarmDispatcher.dispatch` takes the count from `task.swarmPolicy().n()`. The built-in default is
**4** (`SwarmSizing.BUILT_IN_WORKERS_PER_TASK`), resolved over story → project → settings, minimum 1.
An absent `swarm:` block means no swarming at all.

Two different things limit it:

- `SwarmDispatcher.waveSize` can **cut the wave down** (never queue it) when `AdaptiveConcurrency`
  reports a throttled plan with a lower concurrency.
- `WorkerSlots` imposes a process-wide ceiling, `swarm.maxConcurrentWorkers`, default **8**. Extra
  workers **wait**; the wave is not shrunk. Tasks in flight per run is `maxConcurrentTaskGroups`,
  default 2.

Temperature is a linear spread across the group:

```java
double temp = policy.tempMin() + (policy.tempMax() - policy.tempMin()) * ((double) i / Math.max(1, n - 1));
```

The config defaults are `tempMin` 0.2 and `tempMax` falling back to `tempMin`, so **out of the box
there is no temperature diversity** — it must be configured. Personas rotate over
`WorkerPersonas.DEFAULT_ROTATION`: `minimal-diff`, `test-literalist`, `defensive-edges`,
`refactor-friendly`. The per-worker seed is recorded on `SamplingConfig` and **never transmitted**;
it exists for the record only.

### Prefix hashing

This is not deduplication. `PromptBundle` concatenates the shared segments into one byte-identical
`sharedText`, built once per group. `prefixHash()` is its SHA-256 and is **only logged**, as a
cache-alignment fingerprint. The point is that the inference server's prefix cache pays the shared
prefill once for the whole group; anything per-worker (the persona) is appended strictly after.
Write sets and protected paths are sorted before rendering precisely so set-iteration order cannot
perturb the hash. `swarm.dispatch.staggerMs` (default 0) delays worker *i* by `i * staggerMs` so
worker 0 warms the cache first.

### `WaveIntegrator`

Branch `swarm/progress/<runId>`, worktree `~/.swarmcoder/wt/progress-<runId>`, started from the run's
pinned base on wave 0 and extended thereafter.

Before any merge it audits every winner's diff, counts how many winners touch each out-of-write-set
path, and unions every write set in the wave. Then each winner is merged. After each merge,
out-of-write-set paths that are **uncontested (touched by at most one winner) and covered by no
task's write set** are deleted and the removal committed. Finally it runs only the contract's
**compile** commands (not the acceptance suite), after clearing the acceptance-test trees; a non-zero
exit parks with the command and a 40-line tail. A compiler that could not be *run* returns null —
an absent instrument, not a verdict.

A merge conflict returns a `MergeConflictBrief` with a paragraph explaining that this happened
between waves, that every winner is kept, and that resuming does not re-swarm; the engine turns that
into a `RunMustPark`.

**A wave with no winners does not stop the run** — the next wave starts from the same base. **But a
task whose dependency has no winner is not dispatched** (since 2026-09-25, harness run 39, where
"Implement server-side BooksService with EclipseStore" was dispatched right after "Create shared
BooksService interface" went BLOCKED). `WaitingOnDependencies` holds back every task one of whose
prerequisites, directly or transitively, finished with no winner; those tasks stay exactly as they
were. Tasks that do not depend on the blocked one still run. After the last wave, if anything
waited, the engine throws `RunMustPark(brief, true)`: the workflow marks the run parked at EXECUTING
with a reason naming the blocked task and the tasks that waited, and raises **no second question** —
the blocked task's own `BLOCKED_TASK` decision is the one to answer. A resume re-dispatches the
blocked task and then the ones that waited.

A repair round cannot see the work of a sibling task in the same wave: repair workers start from
their seed candidate's checkout, cut from the same wave base, and the wave's winners are merged only
after every task of the wave has finished.

### The re-walk

`SwarmEngineImpl.executeTask` begins by asking `archivedWinner(task)`: is there already a `SELECTED`
candidate for this task in the archives? If so it returns it and does no work. That single check is
what makes re-entering `executeRun` idempotent after an outage pause, after a park-and-resume, and
after a test repair.

### Kill reasons

`EarlyKillEnforcer` produces `TOOLCALL_MALFORMED` (2 malformed calls), `WRITESET_VIOLATION` (2
blocking violations), `BUDGET_EXCEEDED` (tokens over budget) and `NO_PROGRESS` (24 investigation calls
since a write, or 6 fruitless calls in a row). `KoogAgentRuntime` produces `TURN_CAP` and the other
`BUDGET_EXCEEDED`. `WorkerLoop`'s turn guard produces `SUPERSEDED` and `DOCS_DEAD_END`. The dispatcher
maps a thrown outage to `ENDPOINT_OUTAGE` and everything else to `WORKER_ERROR` — **deliberately
never `TIMEOUT`**, because nothing in that path waits on a clock. `SwarmEngineImpl` produces
`UNBUILT_FILES`, `NO_ACCEPTANCE_EVIDENCE` and `SANDBOX_UNAVAILABLE`.

**`PARSE_FAIL` and `COMPILE_FAIL_TWICE` are declared, have operator sentences, and are never produced
by any production path** — the method that would raise them has no caller outside tests.

`KillReason.sentence()` is the single wording of each death, used by the run graph, the inspector and
anything else that has to name one.

## 1.13 Verification

Before assessment, `SwarmEngineImpl.placeClaimedTests` calls `AcceptanceOverlay.reduceTo(...)`: the
candidate's tree is cleared of every inherited acceptance test and reduced to exactly this task's
claimed files, read fresh from the run's tests ref each time. Failure to place is
`NO_ACCEPTANCE_EVIDENCE`. The `VerifySpec` is loaded from **the operator's tree**, never the
candidate's.

`Verdicts.assess(report, claimedChecks, provenance)` then checks in order, first failure winning:

1. no report at all
2. does not parse
3. does not compile (the reason is the attributed compile failure when one exists — when every
   candidate fails on the same misuse in the acceptance test, the task goes to the test author, not a
   repair round: see §1.14)
4. unbuilt files — the candidate wrote source the build never compiles
5. a house rule that declared a check command and failed it
6. acceptance test failures
7. existing test failures
8. browser failures — only when the browser stage actually executed; "could not try" concludes nothing
9. **the empty-check verdict**: a task claiming at least one requirement-check whose acceptance stage
   executed but ran **zero** tests, or whose contract declares no acceptance stage at all, fails.
   A task claiming zero checks — an enabler — keeps the allowance.

Skipped and inconclusive stages kill nobody.

### An assertion failure is the test doing its job

`AcceptanceFailureAttribution` (package-private in `sc-verify`), the exact rule:

```java
boolean insideTest = !isAssertion && !frameClasses.isEmpty()
    && frameClasses.stream().allMatch(testClass::equals);
```

`isAssertion` is true when the thrown type starts with `org.opentest4j.` or its simple name is
`AssertionError`, `AssertionFailedError`, `ComparisonFailure` or `MultipleFailuresError`.
`frameClasses` are the classes in the already project-filtered stack trace; `testClass` is the part of
the test id before the `#`.

So an assertion failure is **never** "inside the test itself", whatever its frames. Only a
non-assertion throwable whose every surviving project frame is the failing test's own class counts.
That flag is stamped onto the first failure by `Verdicts.summarizeTestFailures` and read back through
`TestFailure.insideTestItself()`; nothing re-derives it.

### A green test on a tree carrying nothing proves nothing

This is the newest gate (2026-09-05, harness run 30) and it is worth understanding precisely, because
it is **not** a re-run against a bare tree.

`Verdicts.AcceptanceProvenance` is a record `(measured, greenWithoutTheDiff, nothingDeliveredYet,
note)` whose `provesNoCandidate()` is all three. It is fed from the run's own **wave-gate pre-change
execution**, recorded once per task, not measured per candidate.

`GreenfieldWorkflow.recordAlreadyProved` writes the discriminating line:

```java
boolean nothingDelivered = waveBase != null && waveBase.equals(run.verificationPoint());
```

— the tests were green on a tree identical to the run's pinned base plus its tests, carrying nothing
this run delivered. That boolean lives on `ChecksAlreadyProved.nothingDeliveredYet` (append-only;
false on older records, which is the honest reading).

`SwarmEngineImpl.acceptanceProvenance(task)` then returns: no record → unknown; a record with
`nothingDeliveredYet == false` → **also unknown**, because "green because the waves in front
delivered it" is success and must change no verdict; only `nothingDeliveredYet` → "proves nothing".
`Verdicts.assess` fails the candidate on that, and only when the stage verdict already survived and
the task claims at least one check — an enabler is untouched.

The judge is told separately, via `JudgeClient.testsDoNotSeparateLine(task)`, whenever
`checksAlreadyProved` exists at all.

### Stray files

`StrayFileCheck.isStray(path)` is **geometry only**, never "was this file useful": a path is stray
when it is not a build file (`pom.xml`, `build.gradle`, `build.gradle.kts`) and not under a
conventional JVM source or resource root, matched with any module prefix. Three callers share the one
rule: the judge's score cap, `WaveIntegrator.dropStrayFiles`, and `FinalIntegrator.dropStrayFiles`.

### `survivalReason` is never blank

`SwarmEngineImpl.survivalReason(verdict, report, ...)` returns null if the candidate survived, the
verdict's own reason when it is non-blank, and otherwise a literal fallback:

> verification failed without a recorded reason — this is a bug in SwarmCoder; compiles=…,
> acceptance=…p/…f/…e, claimedChecks=…

It is the only path into `recordVerdict`, and it is pinned by `AVerdictIsNeverBlankTest`.

## 1.14 The test repair path

**Detection.** `SwarmEngineImpl.testRepairNeededFor(task, candidates)` returns null unless *every*
verified candidate's **first** acceptance failure exists and is `insideTestItself()` — and also null
when `task.testRepairAttempted()` is already true. It is raised **before** the ordinary repair round,
after that flag is persisted, as `com.swarmcoder.runtime.TestRepairNeeded` carrying the task id, test
class, method, message and frames.

**The second shape: the test reached browser-only code** (harness run 37, 2026-09-25). Both
candidates of "Create the client BookStore singleton" died with `UnsatisfiedLinkError` on a TeaVM
native method. The trace ran through each candidate's own `BookStore`, so `insideTestItself()` was
false, and the run went to an ordinary repair round — two more workers, one killed `NO_PROGRESS` —
on a test no code could pass. Now, when the first rule does not fire, `browserOnlyFault` asks
`BrowserOnlyCode.reachedIn` of every candidate that **has a verdict** (a candidate killed before
verification is skipped; at least one verdict is required) and raises `TestRepairNeeded` with a
`browserOnlyReason`. `resumeAfterTestRepair` treats a re-verification that still reaches browser code
as still faulty, exactly as it does a test still crashing in itself.

**The third shape: the test can never compile** (brownfield harness run 43, 2026-09-26). Both
candidates failed with "the tree does not compile before this candidate's change:
src/test/java/swarm/accept/DocumentTest.java:16 incompatible types: org.jsoup.parser.Parser cannot be
converted to java.lang.String", and the ordinary repair round sent four workers who each finished
without a change. Now `miscompiledFault` raises `TestRepairNeeded.doesNotCompile(...)` when every
candidate that has a verdict failed to compile with a `CompileFailure` that is `PRE_EXISTING`, in the
protected acceptance test, on the **same file, line and message**, and that message is a misuse
(`AcceptanceCompileErrors.isMisuseMessage` — not "cannot find symbol" / "does not exist"). A missing
symbol stays with the candidates: a symbol the task was meant to deliver and did not is their fault.
The fault carries the compiler line in javac's own format, so the workflow reads it back with the same
classifier as the red check. `resumeAfterTestRepair` treats a re-verification where every candidate
still fails on a misuse in the acceptance test as still faulty.

Its one blind spot, named: a task whose contract changes an existing signature, where every candidate
identically failed to change it, reads the same. The author's correction is then re-classified on the
pre-change tree with the plan's promised changes in hand.

**Repair.** `GreenfieldWorkflow.repairFaultyAcceptanceTest` makes **one** call to the test author —
`repairFailingTest` for a test that crashed in itself, `repairUnrunnableTest` with
`AcceptanceTestReach.reaskForFailure` for one that reached browser code, `repairBrokenTest` with
`MiscompiledAcceptanceTest.reask` (compiler lines plus the real signatures, read off the tests
worktree, i.e. the code before any candidate) for one that never compiled. The correction then passes
four gates in order: the vocabulary check, the self-implementation check, the browser-only reach
check, and a **post-repair red check** — a throwaway worktree on `swarm/testrepair-redcheck/<runId>` cut from the
run's verification point, reduced to this task's claimed files, with the corrected file written over
it. A correction that does not compile for a reason no task can fix fails that red check, whichever
fault it was for.

**The tautology gate.** If that red check comes back *not red*, the repaired test is green on a tree
carrying nothing: `repairTautologicalTest` makes **one** further author call with a tautology re-ask
and checks again. A second miss parks the run.

On success the correction is committed on the run's tests ref and `acceptanceTestsCommit` is updated.

**Re-verification, without a new swarm.** `SwarmEngineImpl.resumeAfterTestRepair` collects every
archived candidate of the task that has a branch and calls `reverifyOne` on each: a worktree at
`~/.swarmcoder/wt/retest-<runId>-<candidateId>` is recreated from that candidate's own branch, the
candidate is reset to `SURVIVED` with a null report, and it is verified **once** against the corrected
test. Survivors go through the ordinary cluster → judge → rule-break → select path.

So: one repair attempt per task, one tautology re-ask inside it, each candidate re-verified exactly
once. The judge is told afterwards via `JudgeClient.testWasRepairedLine(task)`.

## 1.15 The judge

`JudgeClient`, temperature 0.0, reply `{score, rationale, violations}` parsed defensively.

The score is clamped to 0–1 and then **multiplied** by a ceiling — never truncated, so a bad candidate
cannot be lifted by the ceiling and ceilings compose by `min`:

| ceiling | when |
|---|---|
| `UNVERIFIED_CEILING = 0.4` | no report at all, or a compile failure attributed as pre-existing |
| `DOES_NOT_COMPILE_CEILING = 0.2` | any other compile failure |
| `DELIVERED_NOTHING_CEILING = 0.4` | no added or removed line carries anything but whitespace or a comment |
| `STRAY_FILE_CEILING = 0.5` | the candidate left a stray file, with a "SCORE LIMITED" note |

A judge that is unreachable, or a budget that is exhausted, produces a flat `JudgeScore(0.5,
"unjudged: …")`. 0.5 is deliberately not used as any ceiling, so the two are distinguishable.

The scale anchors given to the judge: 1.0 built and tested, complete and clean; 0.7 good with a real
reservation; 0.4 plausible but nothing checked it; 0.2 incomplete or does not build; 0.0 does not do
the task at all.

**The enabler line.** When the task claims no check, the brief says: *"ACCEPTANCE TESTS: this task
claims none, so none were due and none ran — that is correct for an enabler, do not treat it as
unproven."* A standing enabler rule is in the system prompt every time. Harness run 13 judged every
enabler candidate that night as unproven for exactly this reason.

**The documentation slice.** `documentationSection(knowledgeBrief)` extracts the text between the
worker brief's documentation heading and the next heading, capped at `MAX_DOC_SLICE_CHARS = 5_200`
(≈1,500 tokens), fronted by an authority sentence. It re-reads **the same rendered brief the workers
got** — never a second, independent selection. The diff itself is capped at `MAX_DIFF_CHARS = 24_000`,
guidelines at `MAX_GUIDELINE_CHARS = 6_000`.

**`brokenRules`** are the judge's own free-form strings, blanks stripped, stored on the score. The
rule-aware half of the system prompt is added only when the project has rules, and asks for
`violations` plus "a clear breach should not score above 0.4". The same strings are folded into the
rationale text; nothing parses them back out.

## 1.16 Selection

`SelectionLogic.BEST_FIRST`, in order:

1. **survived verification** (a null report has not survived)
2. **did not break a stated rule** (`judge().brokenRules()` non-empty sorts last)
3. **judge score**, descending — an unjudged candidate scores −1.0, so it sorts below every judged one
4. **cluster size**, descending — 0 when never clustered
5. **files outside the write set**, ascending
6. **changed lines** (added + removed), ascending
7. **candidate id**, as a total order

Note tiers 4 and 5: the commonly quoted "verified, rule-keeper, score, size" skips two tiers. Worker
index decides nothing.

Before sorting, `isSelectable` excludes a candidate the judge scored **exactly 0.0**; an *unjudged*
candidate stays selectable. The test is exact rather than a band, so a ceiling multiplier can never
manufacture a 0.0. When nothing is selectable, the selection returns a reason quoting each
candidate's rationale, and the engine turns that into a BLOCKED task plus a pending decision.

## 1.17 The repair rounds

There are **two**, and **neither is triggered by a low score threshold**.

### Verification repair — zero survivors

Entered from `executeTask` only when no candidate survived, and only after two earlier checks:
`failIfLostToOutage` (if *any* candidate died of `ENDPOINT_OUTAGE`, the outage is waited out rather
than repaired) and the `TestRepairNeeded` check above.

`REPAIR_SEEDS = 2`, `REPAIR_WORKERS_PER_SEED = 2` → at most **four** repair workers. Seeds are the
failed candidates that have a report and a branch, sorted by acceptance passes then by compiling. The
task goes to `REPAIRING`. Worker indices come from `RepairIndex.of(seedIndex, attempt)` with
`BASE = 100`, `PER_ATTEMPT = 10`, so w120 and w121 are the two goes at w2.

A repair worker's checkout already contains the rejected attempt's changes, and its brief carries
`failureEvidence(seed)` — the compile flag, per-stage failure summaries with frames, and the last 30
log lines. Repair sampling is temperature `0.4 + 0.4 * i`, persona always `defensive-edges`, always
the first model family.

Repairs are verified **as they complete**. The first survivor calls `signal.supersede()`, and every
in-flight sibling's turn guard returns `KillReason.SUPERSEDED` on its next turn. Still zero survivors
→ the task is `BLOCKED`, a decision is queued, and the run continues.

### Rule-break repair — every judged survivor broke a rule

Entered from `finishTask` when every judged survivor has a non-empty `brokenRules`. Same seed and
worker counts; seeds sorted by judge score. The evidence is the judge's `brokenRules` verbatim plus
its rationale, plus *"Do not work around it with an in-memory or temporary substitute"*. Repaired
survivors are re-clustered, re-judged and **combined** with the originals. If the combined set still
all break a rule, this throws `RunMustPark` — a run park, not an ordinary blocked task.

## 1.18 FINAL_INTEGRATION

`FinalIntegrator.integrate(run)`. Branch `swarm/integration/<runId>`, worktree
`~/.swarmcoder/wt/integration-<runId>`, cut from **`run.verificationPoint()`** — the tests commit, not
the progress branch. That is the only route by which the run's acceptance tests reach the delivery
branch, and it is why the tests arrive together with the code that passes them.

Winners are the `SELECTED` archives, merged in topological wave order. Per winner, in order:

1. `PathPolicy.audit` against the protected paths. A blocking hit **fails the run**: *"This reached
   the diff without passing a tool check, which means a shell command wrote it."*
2. out-of-write-set paths are recorded and logged, and are never a reason to park
3. `SecretScanner.scan` on the diff — any finding fails
4. restore the tree, then merge; a conflict produces a `MergeConflictBrief`
5. **stray non-source files are dropped** — paths uncontested run-wide and covered by no task's write
   set are deleted and the removal committed
6. the acceptance tests are reduced to the **cumulative** set due so far
7. **full verification after every merge**, with one shared language service for the whole worktree,
   assessed by `Verdicts.assess`
8. the task is marked done

A task with no winner is skipped with a warning, not a failure. The last report travels out on the
result and is what the delivery decision reads.

## 1.19 Delivery, review and acceptance

`GreenfieldWorkflow.recordDelivery` judges the story's criteria against the **integration**
verification, falling back to a candidate-level report only when integration produced none.

A gating NFR that is ACTIVE with a FAILED fitness criterion **blocks**: the story goes `BLOCKED`, a
decision is queued, and the run goes `ABORTED`. Otherwise the integration sha is stamped on the story
and every criterion PASSED decides `REVIEW` versus `BLOCKED`.

**A story is never set DONE by the machine.** The code's own comment: *"'the tests pass' and 'this is
what I asked for' are different claims, and only the operator can make the second one."* The run
itself goes `DELIVERED` or `ABORTED` and does not park at a gate of its own; the old `APPROVAL` state
is retired on sight.

The operator sees a card badged "came back", saying *"A build finished and delivered commit <sha8>.
Did it deliver what was asked?"*, with a primary button **Accept delivery**. Pressing it **first**
puts the code on the delivery branch (`StoryDelivery.deliver`: a throwaway worktree off the current
tip, a real merge, and full re-verification of the merged tree through the trusted contract, then a
fast-forward); any refusal aborts the acceptance. Only then are criteria stamped PASSING with the
commit and content revision, requirements re-evaluated from evidence, and the story set DONE with
`acceptedBy` recorded as `human` or `unattended`.

## 1.20 Parking, restarting, resuming

**Park** is set only by `GreenfieldWorkflow.parkRun(run, reason)`, which stamps `parkedAt` and
`parkReason` and queues a `BLOCKED_TASK` decision carrying the same brief. It is cleared at the top of
`advance` before the stage is retried, together with `withdrawStaleDecisions` (which rewrites this
run's pending `BLOCKED_TASK` rows to RESOLVED with *"Withdrawn — the run moved on."*).

Park sites: PLAN after three attempts; the TEST_AUTHORING file mismatch; the red check; a
`RunMustPark` at EXECUTING; an unrepairable acceptance test; a failed FINAL_INTEGRATION; and the
design gates in §1.8 and §1.9.

`BuildHealth.of(state, heartbeat, pausedSince, parkedAt, now)` checks `parkedAt` **before** the pause
and the heartbeat, so a park reads STOPPED immediately rather than looking WORKING for up to
`STALLED_AFTER_MINUTES = 20`. A pause older than the escalation threshold reads NEEDS_YOU, otherwise
PAUSED. Only DELIVERED and ABORTED are terminal.

**"Build it again"** appears on the pipeline board for a BLOCKED story, and for a RUNNING story whose
health is STOPPED. (A waiting story gets *"Stop waiting and take it back"* instead.) It calls
`retryStory`, which accepts BLOCKED, REVIEW or RUNNING, sets the story READY, **clears
`deliveredCommit` and `integrationCommit`** so a later acceptance cannot stamp criteria against an
unaccepted commit, and nulls the park marks on the story's runs — deliberately leaving the
`BLOCKED_TASK` decision in place as history. **Nothing is resumed and no agent is called back; a
fresh build starts a new run.**

**`RunResumer.resumeAll()`** runs at process start, and it is today the only thing that hands a parked
run back to a workflow. It first abandons orphans: any non-terminal run whose project no longer
resolves is set `ABANDONED` and its pending decisions removed. Then, per project owning unfinished
runs, it fetches **that project's own** engine and advances each run — never another project's engine,
and never for a project whose engine is null. The run's own state field is the resume point, so a
parked run retries exactly the stage it parked in.

**Autonomous retry** is `AutonomousBuild.retryParkedStories`, not `AutonomousAnswers`. While
autonomous mode is on, a RUNNING story whose latest run is parked is sent back **once** through the
same `retryStory` the button uses, recorded as `RETRIED_PARKED_STORY`; the list of prior such
decisions is the guard, so a second park is left for a person. (`AutonomousAnswers` does something
else: it answers the analyst's and planner's clarification questions, flagging grounded versus
invented, at most 600 characters.)

**Deleting a project** re-checks the typed name server-side, tears the project's context down
**before** the records go (so no new dispatch can start, and a worktree sweep is launched for that
repository), and then **removes the project's runs outright** along with its designs, task graphs,
tasks, candidate archives, decisions, sessions, stories, BRD and rules. Nothing on disk is touched.
`RunState.ABANDONED` is therefore the *startup sweep's* answer for orphan rows left by deletions that
predate this path — it is not what a deletion writes today.

---

# 2. The worker

A worker is one model session in one git worktree with one task. `WorkerLoop`
(`sc-swarm/src/main/java/com/swarmcoder/swarm/WorkerLoop.java`) owns the session; `WorkerToolbox`
(same package) owns the tools; `KoogAgentRuntime` drives the turns.

## 2.1 The eight tools

Registered in `WorkerToolbox.bindings()`. Parameter names are the schema (the module compiles with
`-parameters`).

| name | signature | returns | refuses |
|---|---|---|---|
| `exec` | `exec(command)` | `exit=<n>` (plus ` (timed out)`), newline, output, plus any write-policy note and framework hint; truncated at `MAX_TOOL_OUTPUT_CHARS = 8000` | three command classes, before any process starts (§2.7) |
| `read` | `read(path)` | file content, capped at `MAX_READ_BYTES = 64 * 1024` then 8000 chars | `error: file not found: <rel>` |
| `apply_diff` | `applyDiff(unifiedDiff)` | `applied cleanly to: <paths>` or `error: git apply failed:` | the **whole** diff if any target path is protected; a diff with no `+++ b/<path>` headers |
| `write_file` | `writeFile(path, content)` | `wrote <rel> (<n> chars)` | blank path, blank content, protected paths |
| `lookup_api` | `lookupApi(query)` | documentation plus shaped source, capped at `LOOKUP_CHARS = 6000` in `Librarian` and 8000 here — both at the 51,200-token baseline room, and both scaled by the worker's own room above it (30,720 and 40,960 at 262,144; `Agent-Knowledge-And-Operations.md` §4.6) | a blank query |
| `ask_expert` | `askExpert(question, whatITried)` | the desk's answer text | nothing — never blocked, not even by the read pause |
| `request_skeleton` | `requestSkeleton(typeOrTask)` | the desk's answer text | nothing — never blocked |
| `report_done` | `reportDone(summary)` | the summary verbatim | nothing; the name is the loop's stop signal (`KoogAgentRuntime.DONE_TOOL`) |

`ask_expert` and `request_skeleton` staying open during the read pause is deliberate and documented
on `askExpert`: a worker out of rope can still get the one answer that lets it write. `report_done`
is the only tool that does not count as an investigation call.

Absolute paths the model emits are silently made relative (`toRelative`, and `relativizeDiffHeaders`
for diffs). The opening user message is fixed: *"Begin now. Implement the task from your
instructions; call report_done when the change is complete and verified."*

## 2.2 Where a worker may write — `PathPolicy`

`sc-runtime/src/main/java/com/swarmcoder/runtime/PathPolicy.java` (note: **not** in `sc-sandbox`).
`check(relPath, writeSet, acceptanceTestDir, protectedPaths)` returns a
`Verdict(reason, lethal)` in this order:

1. path resolves outside the worktree root → refused, *"path escapes the repository"*. `canonicalize`
   normalises and then resolves symlinks for the deepest existing ancestor, so a symlink out is
   caught rather than followed.
2. `ALWAYS_PROTECTED = List.of(".swarmcoder/", ".git/")` → refused.
3. an operator-locked module → refused, naming the module.
4. the task's `acceptanceTestDir` → refused, *"acceptance tests are protected"*.
5. empty write set → allowed (unrestricted).
6. inside the write set → allowed.
7. otherwise → **outside the write set, and `lethal = false`**.

Matching is whole-segment, so `src/main/javax` is not inside `src/main/java`.

Beyond the operator's locks there is a **trust kernel**: six files locked by default for every
project (`ProjectContext.lockedPathsFor`), released only by `-Dswarmcoder.unlockTrustKernel` with a
loud warning — `PathPolicy`, `VerifySpecLoader`, `SecretScanner`, `FinalIntegrator`,
`CriterionEvidence`, `WorkerToolbox`. In-source marker `swarmcoder:locked` adds more, collected with
`GitService.grepFilesAtRef(marker, "HEAD")` — read from the base commit, never from the working
tree.

## 2.3 What a worker is told at the start

`SwarmDispatcher.buildBundle(...)` assembles a `PromptBundle` whose segment order is fixed by the
enum, not by call order:

```
SYSTEM_ROLE → WORKFLOW_RULES → PROJECT_CONSTRAINTS → TASK_INSTRUCTIONS → KNOWLEDGE_BRIEF
```

Each segment is framed as `## <KIND>`; blanks are skipped. `prefixHash()` is the SHA-256 of that
shared text, and the per-worker persona is appended strictly **after** it, so the server's prefix
cache still aligns across the workers of one task.

- **SYSTEM_ROLE** (125 chars): *"You are a software engineering worker agent. Implement exactly the
  task described below in the repository you have tools for."*
- **WORKFLOW_RULES** (~1,718 chars, identical for every worker): work in small steps; if
  `apply_diff` is rejected, read the file and use `write_file`; always use repository-relative
  paths; you may add a dependency to a module's build file but **only** an artifact your knowledge
  brief lists as available, because the build runs offline; *"You will meet APIs that are not in
  your training data. That is expected and it is not your failure"*; call `lookup_api` first; do not
  unpack or decompile jars, those commands are refused; and the read pause is warned about in
  advance.
- **PROJECT_CONSTRAINTS**: the project's ACTIVE guidelines rendered by `ConstraintBrief`, placed
  above the task on purpose. Capped by `guidelines.maxPrefixTokens`, default 3,000 tokens
  (≈12,000 chars). This is the largest measured segment.
- **TASK_INSTRUCTIONS**: title and instructions — which carry the types the task must deliver
  and, since 2026-09-25, the types its code uses that another task writes, by fully-qualified name
  with the writing task (§1.10); then the write set (*"You may ONLY modify these
  paths"*); then the protected acceptance-test directory; then the locked modules — a courtesy list,
  since the enforcement is mechanical anyway. Both lists are sorted so iteration order cannot
  perturb the prefix hash. On a repair wave a `REPAIR CONTEXT` block is appended saying the checkout
  already contains the rejected attempt's changes, with the failure evidence.
- **KNOWLEDGE_BRIEF**: from `Librarian.assembleBrief` — see §3.

Two segments that used to exist were deleted on 2026-09-02: `DESIGN_EXCERPT` and `REPO_MAP`. There
is no repository map any more; a worker gets its write set and its tools and lists what it needs.

### The measured prefix

There is **no gate** comparing prefix size to the context window. What exists is an estimate
(`PromptBundle.estimatedTokens()` = chars / 4, the same crude estimator `HistoryTrim` uses), logged
on every dispatch beside the prefix hash, and a non-asserting printer test
(`sc-app/src/test/java/com/swarmcoder/swarm/PrefixSizeMeasurementTest.java`, `WORKING_CONTEXT =
51_200`). Its recorded table for 2026-09-04:

| segment | chars | tokens | % of window |
|---|---|---|---|
| SYSTEM_ROLE | 125 | 31 | 0.1% |
| WORKFLOW_RULES | 1,718 | 429 | 0.8% |
| PROJECT_CONSTRAINTS | 12,005 | 3,001 | 5.9% |
| TASK_INSTRUCTIONS | 675 | 168 | 0.3% |
| KNOWLEDGE_BRIEF | 15,399 | 3,849 | 7.5% |
| **shared prefix** | **30,028** | **7,507** | **14.7%** |

It was 16,781 chars before the worked example was added. The prefix is the floor compaction can
never reclaim, which is why it is measured rather than guessed at.

## 2.4 The read pause

`EarlyKillEnforcer` (`sc-swarm`):

```
INVESTIGATION_TOOL_CALLS_BEFORE_NUDGE        = 8
INVESTIGATION_TOOL_CALLS_BEFORE_SECOND_NUDGE = 16
INVESTIGATION_TOOL_CALLS_BEFORE_KILL         = 24
FRUITLESS_CALLS_BEFORE_KILL                  = 6
```

`shouldNudgeOutOfInvestigation` fires on **equality only** (`calls == 8 || calls == 16`), so there
are exactly two steers per run of investigation, and any successful write resets the counter to
zero.

Both steers use the same text, which names the count, says whether the worker has ever written,
points at `lookup_api` (naming up to five real documents from the brief) or — when the project has
no reference documentation — says to stop working the API out and write a best attempt for the
compiler to correct, and then names `ask_expert` and `request_skeleton` explicitly: *"asking is not
a failure"*. The sixteenth call adds one more sentence: *"This is the last warning: at 24 tool calls
without a change this attempt is stopped and thrown away."*

**The pause itself starts on the 17th call.** `readingPaused()` is `investigationCalls >= 16` and is
checked *before* the call is counted, so calls 1–16 do real work. From the 17th, `read`, `exec` and
`lookup_api` return a fixed text instead of a result — except a build or test command, which still
runs (`BUILD_OR_TEST_COMMAND = (?i)\b(mvnw?|gradlew?|javac)\b`). The text says reading is paused,
that a wrong file the compiler can correct is worth more than another page read, that reading
resumes after the first write, and that `ask_expert` and `request_skeleton` still work.

## 2.5 The two stall rules

**No progress** (`EarlyKillEnforcer.checkProgress`, consulted every turn): 24 investigation calls
since the last write, **or** 6 fruitless calls in a row → `KillReason.NO_PROGRESS`. "Fruitless" is
defined by the *result*, not by the command: a blank body, a body starting `error:`, a body that
timed out, or a body whose digest has already been shown. Up to `MAX_REMEMBERED_RESULTS = 300`
digests are kept, which only ever makes the guard more forgiving.

**Documentation dead end.** When `SAME_LOOKUP_ANSWERS_BEFORE_WARNING = 3` *different* questions get
the same leading `####` section back, a one-shot note tells the worker that this is a defect in the
documentation index and not something asking differently will fix, that unpacking jars is equally a
dead end, and to ask once more naming one class exactly and then write. If the worker is
subsequently stopped for no progress, the kill is **relabelled** `KillReason.DOCS_DEAD_END`, and the
last 12 questions (each truncated to 100 chars) are written onto the candidate as
`docsDeadEndEvidence`.

That distinction is the whole finding behind the constant. Quoting `KillReason`'s own javadoc: the
worker in that run was not ignoring its instructions — it asked four well-formed questions and the
search answered all four with the same unrelated section, and only then went to the jars, where it
got its answer in one call. A run that ends this way is **evidence about the documentation search,
not about the model.**

Other kills in `checkState`: two malformed tool calls → `TOOLCALL_MALFORMED`; two blocking write
violations → `WRITESET_VIOLATION`; tokens over budget → `BUDGET_EXCEEDED`; another candidate winning
→ `SUPERSEDED`.

## 2.6 `TURN_CAP` versus `BUDGET_EXCEEDED`

Two constants, two classes, two operator sentences. They were one until 2026-09-03, and conflating
them is what let harness run 11 report two workers dead at exactly turn 25 as "its conversation
outgrew what it is allowed" when neither had ever needed a compaction.

- **`TURN_CAP`** — `KoogAgentRuntime.KoogSession.loop`: `turns > spec.maxTurns()` throws a kill
  signal saying *"it used all N of its turns without finishing … raise
  budgets.maxToolTurnsPerWorker or split the task"*. `maxTurns` resolves task budget → swarm policy
  → `TurnAllowance.BUILT_IN_MAX_TOOL_TURNS = 120` (`MINIMUM = 4`), over STORY → PROJECT → GLOBAL →
  BUILT_IN, and the winning layer is named in the run log. Koog's own ceiling is set to
  `max(maxTurns * 2, 10)` as a net above ours. Until 2026-09-01 this was a hardcoded 30 that nothing
  could reach.
- **`BUDGET_EXCEEDED`** — either `usedTokens > maxTokens` (task budget, else
  `WorkerLoop.DEFAULT_MAX_TOTAL_TOKENS = 2_500_000`), or `KoogAgentRuntime.compactIfNeeded` finding
  the conversation still above the high-water mark after compaction, or three consecutive turns each
  needing one.

## 2.7 Exec safety

Every refusal below fires before the command reaches a process, and each one counts as an
investigation call so a worker cannot spend its whole allowance being refused. `EXEC_TIMEOUT_SECONDS
= 300`.

**Reverse engineering.** `javap`, `jar t/xf`, `unzip …​.jar`, `find … -name … .jar`, `cfr`,
`procyon`, `jd-cli`. The refusal points at `ask_expert` instead: it answers from code in this
project's reference material that compiles today, *"which is more reliable than a disassembly and
costs one turn instead of twenty."* Deliberately narrow — a build, a test, a grep, and a plain
`find` inside the worktree are untouched. The measured cause: a worker spent 66 shell commands on
`jar xf`, `jar tf` and `javap` and wrote nothing in 92 turns.

**Console waits and foreground servers.** `| more`, `| less`, `less`, `type con`, `pause`,
`read -p` are always refused. A foreground server start (`java -cp … *App/*Server`,
`mvn exec:java`, `mvn …:run`, `npm start`) is refused **unless** the command is backgrounded — a
trailing single `&`, `nohup`, or `start /b`. The same guidance is reused verbatim in the timeout
reply, placed after the `exit=… (timed out)` line so a timeout still counts as uninformative.

**Unscoped process kills.** Two tiers. Anything naming an image, a name, a port or a pipeline is
refused unconditionally: `taskkill /IM`, `wmic process … delete|terminate`,
`Get-Process | Stop-Process`, `pkill`, `killall`, `fuser -k`, `netstat|findstr|taskkill`,
`lsof -t | kill`. Three narrow pid forms (`taskkill /PID n`, `Stop-Process -Id n`, `kill n`) are
refused only when the pid is not one this worker's own exec target remembers starting. The refusal
text explains that a background server is stopped automatically at the end, and that a build or test
run never needs a kill. Measured cause, twice (harness runs 21 and 29): a worker's shell command
killed the surefire fork JVM running the whole harness — the swarm engine and every other worker
with it — and nothing recorded which command it was.

**Framework hint.** After a failed build, if the output contains "cannot find symbol" or "does not
exist" and names one of the configured framework package prefixes, the result gets an appended
orchestrator note: that symbol is this project's framework, not your code — do not guess and do not
go looking in jars, call `ask_expert` with the exact symbol.

## 2.8 Backgrounding on Windows

`LocalProcessExecTarget` (`sc-verify`) detects `isWindows() && wantsWindowsBackground(command)`: a
single trailing `&` (not `&&`) or a leading `nohup`, and not already containing `start /b`. This
exists because on Windows `&` inside `cmd.exe /c` is a command *separator*, so the advice the
toolbox gives was untrue there and the call blocked for the full 300 seconds.

`startBackground` strips the idiom, uses any redirect the command already names or else
`swarm-bg-<n>.log`, appends `> "<log>" 2>&1`, launches `cmd.exe /c <launch>` in the worktree with
stdout/stderr discarded and stdin closed — and **never calls `waitFor()`, which is the
backgrounding.** (`start /b` is deliberately not used: it would nest a second `cmd.exe /c` and bring
the quoting fragility back.) The pid goes into `backgroundPids` and is reported to the model, with
the warning that `taskkill /F /T /PID` needs the `/T`, since this pid is the launching shell.

Cleanup is in a `finally` around the session in `WorkerLoop.runLiveSessionWith` — it runs whether
the worker finished, was killed, or threw — and force-destroys each remembered pid's descendants and
then the pid.

Inside the Docker sandbox the mechanism is different: `SandboxExecTarget.startService` uses a pid
file, script and log under `/tmp/sc-services` with an in-container watchdog,
`SERVICE_WATCHDOG_SECONDS = 30 * 60`.

## 2.9 Every exec is written down before it runs

`KoogAgentRuntime.KoogSession.loop` calls `tracer.toolCall(...)` for every call in the turn, on the
worker's own thread, strictly before executing them. `PendingExecRecorder` (`sc-workflow`) listens
for the `exec` tool and writes a `PendingExec(sessionId, runId, taskId, role, command, now)` with
the command truncated at 300 chars.

`ArtifactStore.recordPendingExec` **blocks the worker thread** (`writerThread.submit(...).get()`
plus `commit()`) — that is what makes "durable before the process starts" true, and it is the only
listener in the codebase that does so. Clearing on the result is fire-and-forget on purpose: a stale
entry reads as "this was the last exec running", which for a dead session is exactly right. A
forensics-write exception is swallowed and logged; a worker's exec is never broken over it.

The cause was two harness deaths (runs 21 and 29) where a worker's command killed the harness JVM
and the in-memory transcript died with it — a session's transcript only reaches the store when the
session closes.

Exit codes are logged by the exec target, not the recorder: `exec<ctx>: cwd=… timeout=…s command=…`
on start and `exec<ctx> done: exit=… duration=…s` on completion, where `<ctx>` is
`[Worker N (task title)]`.

## 2.10 Stdin and the environment

Host side, on `exec`, `startService` and `startBackground`:

```java
pb.redirectInput(Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")));
```

With stdin already at EOF, a pager, `type con`, `read -p`, or a server blocking on `System.in`
returns immediately. Five environment variables are set: `CI=true`, `TERM=dumb`, `PAGER=cat`,
`GIT_PAGER=cat`, and `SWARMCODER_HOST_PIDS`.

In the sandbox, `DockerSandboxManager.execInContainer` sets the first four only, runs as the sandbox
user in `/workspace`, and never attaches stdin — the Engine-API equivalent of `docker exec` without
`-i`.

Output is capped at `256 KB` by the exec target and the action server, then again at 8,000 chars by
the toolbox.

## 2.11 The host pid guard, honestly

`SWARMCODER_HOST_PIDS` **protects nothing today.** It is published to every child process and
nothing in this repository reads it back; its own javadoc says it is there for a future or
operator-authored safeguard. The two occurrences in the tree are the javadoc and the `env.put`.

What actually protects the host is in-process: `hostPids()` returns the current JVM's pid and its
parent's, and both `killTree(process)` (the timeout path) and `stopBackgroundProcesses()` filter
those out before destroying anything — plus, crucially, the exec refusal in §2.7, which is the only
thing that can stop a worker killing a pid it read off the system.

## 2.12 Write-set enforcement — kept, not shot

Since 2026-09-02 there are two settings, and they are not alternatives for the same path:

- **Protected paths** (escape, `.git/`, `.swarmcoder/`, a locked module, the acceptance tests): the
  write never happens; for `apply_diff` the *whole* diff is refused; `blockingViolations`
  increments; two of them kill the worker with `WRITESET_VIOLATION`.
- **Merely outside the write set**: the write **happens and is kept**. The path is recorded once per
  path, and the model is told: *"The change was KEPT — you are not being stopped. It is recorded
  against this candidate and the reviewer will see it, so go outside your own paths only when the
  task genuinely cannot be done inside them."*

Shell commands are audited by **effect**, not by reading the command string: after every `exec`,
`auditAndRevertStrayWrites()` runs `git status --porcelain --untracked-files=all` host-side on the
trusted local target (never in the sandbox), puts each changed path through the same `PathPolicy`,
reverts the lethal ones (`git checkout --`, or delete if never tracked) with a note saying so, and
leaves the rest. The revert used to fire on the non-lethal case too and killed a worker at turn 102
over an EclipseStore data file its own test run had written.

`WorkerLoop` records the out-of-write-set paths onto the candidate on **every** outcome, killed ones
included. The same policy runs again at integration against the winning diff's paths
(`PathPolicy.audit` → `FinalIntegrator`), because a shell can write files no tool ever saw and
`git add -A` sweeps them in.

Inside the container the action server's `/write` endpoint has its own much cruder gate
(`WriteSetEnforcer.isAllowed`: `*` allows all, trailing `/` is a prefix, else exact equality) and
returns 403. The worker's file tools do not go through it — `read`, `apply_diff` and `write_file`
are host-side on the bind-mounted worktree; only `exec` goes into the container.

## 2.13 Tool calls the model writes as prose

`KoogAgentRuntime.KoogSession.recoverTextEmittedCalls` runs only when a turn produced no tool calls,
and only when `quirks.textualToolHistory()` is true — with native tool history it returns nothing,
because a recovered result would arrive as an orphan `role: tool` message the server rejects, and a
model on native history has never been shown the text dialect to imitate.

The log line:

```
Session '<role>' turn <n>: the model wrote <k> tool call(s) as text instead of calling them
(<names>); executing them as written
```

`TextEmittedToolCalls` scans the assistant text for JSON objects, accepts `tool_name`/`tool_args`
(the framework's own textual-history shape, which is what the model is being shown) or
`name`/`arguments` (OpenAI wire shape, including the "arguments is a JSON string" spelling),
requires the name to be a **registered** tool, and caps a turn at `MAX_RECOVERED = 8`.

The measured cause: a Qwen 27B worker made real tool calls on turns 1–2 and from turn 3 emitted the
JSON as text with an invented call id. Across one full run that was 27 of 27 workers finishing with
zero files written.

If nothing is found or recovered, the first such turn gets a nudge (*"Continue using your tools.
When the task is complete, call report_done."*) and two in a row end the session.

---

**Continued in [`Agent-Knowledge-And-Operations.md`](Agent-Knowledge-And-Operations.md): knowledge and the help desk, the model runtime, the harnesses, the problem-handling playbook, and the operating notes.**
