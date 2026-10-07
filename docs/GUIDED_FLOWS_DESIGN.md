# SwarmCoder — Guided Flows

> **Note (2026-07-28):** the wizards described here (document intake, backlog planning) SURVIVE
> into **`docs/CONSOLE_UX_V3.md`** and remain authoritative for their own behaviour. The
> surrounding shell this document assumes — separate Plan/Build stages, the stage stepper — is
> superseded by V3's pipeline model. Where the two conflict on navigation, V3 wins.

**Status:** requirements intake (2026-07-26) and backlog planning (2026-07-27) implemented — see §9
for what is and is not built.
**Author decisions:** 2026-07-26 (4 decisions, §7).
**Supersedes:** the `/brd` and `/backlog` chat modes, which are removed.

---

## 1. The problem

`/brd` was a terminal affordance wearing a GUI costume. To use it you had to know the command
existed, know it put the chat into a *mode*, know `/done` left that mode, and then read the agent's
questions as prose in a scrolling river. Nothing on screen taught you any of that — the proof is
that the upload button had to be explained in conversation before it could be found.

That is the pattern a TUI is forced into because it has one input line. A GUI is not forced into it,
and adopting it anyway throws away the only advantage the GUI has: it can show you what is possible,
where you are, and what happens next.

> **The rule this document exists to enforce: if a capability can only be reached by typing a
> command, it does not exist for most operators.** Every substantial agent task gets a surface that
> shows its inputs, its progress, its questions and its results.

## 2. The shape

One pattern, reused for every guided task:

```
   inputs  ──▶  [ Start ]  ──▶  progress  ──▶  questions  ──▶  review  ──▶  apply
     ▲                              │              │             │
     └──────────────── add or change inputs, run it again ───────┘
```

Applied to:

| Flow | Inputs | Produces |
|---|---|---|
| **Requirements intake** | uploaded documents + per-document notes | requirement/criterion proposals |
| **Backlog planning** | the ACTIVE requirements | story proposals |
| **Delivery** | a READY story | a run, then an acceptance decision |

Requirements intake was built first and backlog planning second, on the same machinery — three
wizards that behave differently would recreate the problem in a new form.

## 3. Server-owned, not dialog-owned

A flow is **persisted and runs on the server**. The dialog is a view of it.

This is not incidental. Extraction over a real PDF takes minutes; a flow that lives in the dialog is
one the operator learns not to open, because closing the window throws the work away. Instead:
close the wizard, keep working, re-open to find it still running or finished. Progress is real
server state, never a spinner animating next to a request that may already have failed.

It also means the browser can reconnect, a second tab shows the same flow, and a crash mid-flow
leaves a resumable record rather than nothing.

## 4. Questions are DATA

The agent does not ask questions in prose. It emits them as structured objects — the question, what
it concerns, and (when the answer is closed-ended) the choices. The wizard renders them as a form.

That buys what prose cannot: questions answerable in any order, individually skippable, a visible
count of what is still open, and answers that can be attached to the requirement they concern.

Questions are asked in **rounds**, not one at a time. An agent will produce a dozen clarifications;
a dozen sequential modal prompts is death by a thousand dialogs. A round is a short form — answer
what matters, skip the rest, continue.

**Skipping is first-class.** A skipped question does not block the flow; it becomes an assumption
the agent must state explicitly in the requirement it drafts, so an unanswered question turns into
something visible and correctable rather than a silent guess.

## 5. Re-analysis merges, and shows the diff

Adding or replacing a document does not start from nothing. The agent is given the requirements that
already exist and proposes **changes**:

```
+ new requirement
~ edited requirement          before / after, both shown
! conflict                    two requirements that cannot both hold
```

Nothing lands until the operator applies it. This fits what the BRD already is — a living document
with an append-only revision history that can be restored — and it avoids the two failure modes of
the alternatives: appending duplicates that must be weeded by hand, or replacing wholesale and
discarding the edits made since the last extraction.

## 6. Model

Persisted in EclipseStore (mutable POJOs, never records) and carried on the wire as `@DataModel`.

**`GuidedFlow`** — `id, projectId, kind, state, step, totalSteps, stepLabel, error, createdAt,
updatedAt`, plus `documents : List<FlowDocument>`.

**`FlowDocument`** — `documentId, notes`. The notes are the operator's instructions *for that
document* ("authoritative for pricing", "ignore section 4", "this is the legacy spec") and are given
to the agent alongside the text. This is the piece that makes a pile of documents into a briefing.

**`FlowQuestion`** — `id, flowId, subject, text, kind (CHOICE | TEXT), options, answer, skipped`.

**`FlowProposal`** — `id, flowId, kind (ADD | EDIT | CONFLICT), handle, title, before, after,
rationale, accepted`.

**States** — `DRAFT` (collecting documents) → `RUNNING` → `AWAITING_ANSWERS` → `REVIEW` → `APPLIED`,
plus `FAILED`. The operator can return to `DRAFT` from any resting state to add a document and run
again.

## 7. Author decisions (2026-07-26)

G1. **Questions are structured form fields**, not an embedded chat. The agent emits them as data or
    they cannot be rendered as a form; the agent contract is stricter as a result, deliberately.

G2. **`/brd` and `/backlog` are REMOVED**, not merely undocumented. Keeping them would leave two
    paths to one outcome, which is the confusion this design exists to remove — and an
    undocumented path still rots, because nothing tests what nobody is told about.

    *Resolved (2026-07-27):* both are gone. `/brd` went with the intake wizard on 2026-07-26;
    `/backlog` was kept a day longer only because deleting a capability before its replacement
    exists is worse than the inconsistency, and it was deleted the moment the backlog-planning flow
    landed — the chat mode, its tool table, its persona, and the `/backlog` and `/done` entries in
    the command list. `BacklogAuthoring` was kept: the new engine writes through it.

G3. **Re-analysis merges and shows a diff.** Nothing is applied without review.

G4. **The pattern is general.** Built once as a guided-flow surface, then reused for backlog
    planning and delivery.

## 8. What this does NOT change

The chat remains, and remains valuable — it is where you think out loud, ask about the codebase, and
propose a run. What it stops being is the only door to requirements authoring.

The agent behind the flow is the same BRD author with the same rules: it drafts, it never promotes,
and the operator accepts. Structuring its output changes how it is asked and how its answers are
shown — not who decides.

## 9. Implementation status (2026-07-27)

**Built — requirements intake.**

| Piece | Where |
|---|---|
| Domain + persistence (schema v8) | `sc-domain/.../GuidedFlow`, `FlowDocument`, `FlowQuestion`, `FlowProposal`; `sc-store/.../StoreRoot`, `ArtifactStore` |
| Wire contract | `sc-console-api/.../GuidedFlowService`, `FlowView`, `GuidedFlowSignals` |
| State machine | `sc-console/.../GuidedFlowServiceImpl`, `GuidedFlows` |
| The analysis | `sc-console/.../RequirementsIntake` |
| The wizard | `sc-console-ui/.../IntakeWizard`, opened from the Requirements panel |
| Tests | `sc-console/src/test/.../GuidedFlowIntakeTest` (scripted analyst, no live model) |

**Built — backlog planning (2026-07-27).** The second use of the pattern, and the thing that made
`/backlog`'s removal legitimate.

| Piece | Where |
|---|---|
| Domain + persistence | reused unchanged — `GuidedFlowKind.BACKLOG_PLANNING` on the same `GuidedFlow`, `FlowQuestion`, `FlowProposal` |
| Wire contract | `sc-console-api/.../PlanningFlowService`, `PlanningFlowSignals`, the same `FlowView` |
| State machine | `sc-console/.../PlanningFlowServiceImpl`, sharing `GuidedFlows` |
| The planning | `sc-console/.../BacklogPlanning`, applying through `BacklogAuthoring` |
| The wizard | `sc-console-ui/.../PlanningWizard`, opened from `BacklogPanel`, `BacklogBoard`, and the `plan-stories` next-step button |
| Tests | `sc-console/src/test/.../GuidedFlowPlanningTest` (scripted planner, no live model) |

What did **not** transfer from intake, and why:

- **The inputs are not the operator's.** Intake's DRAFT state collects documents; planning has
  nothing to collect, because its input is the project's own ACTIVE requirements and the backlog.
  DRAFT therefore means only "not started", and the first screen is a *summary of what will be read*
  — requirements, unclaimed criteria, story count, and the coverage report verbatim. A wizard with
  one button and no account of what pressing it reads is one nobody trusts.
- **Coverage is the completion signal.** `BacklogAuthoring.coverage` already answers "which accepted
  criteria does no story claim?", so it is both the ideal prompt input and the reason to refuse a
  run: zero unclaimed criteria means there is nothing to plan, exactly as zero unread documents
  means there is nothing to analyse.
- **A second signal, not a second publisher.** `PlanningFlowSignals.CURRENT` is separate from
  `GuidedFlowSignals.CURRENT` because both wizards can be open at once; `GuidedFlows.publish` routes
  by `flow.kind()` so the engines still share one `advance`.
- **`FlowProposal` carries a story without a new enum constant.** `handle` holds the criteria refs
  (`R7:C1,R7:C2`) — the story's identity, and what apply resolves; `after` holds the same story
  rendered for a human, with each criterion's wording looked up from the BRD and a `Kind:` line that
  says DELIVERY or ENABLER. `ADD` is a new story and `CONFLICT` is two criteria that cannot both be
  delivered. Persisted enums here are APPEND-ONLY and a middle insert has stopped the whole app
  starting once already, so the existing constants were made to fit rather than extended.
- **Refusals are computed at review, not at apply.** A ref the BRD does not have, a delivery story
  naming no criterion, or criteria a living story already claims — each is flagged with an
  explanation and left unticked while the operator is still looking at the list, because discovering
  it from an error string after ticking eight boxes leaves a half-written batch and no list to
  return to.

Decisions taken during implementation, beyond §7:

- **One round of questions, not a loop.** A loop that can keep asking will, and the operator cannot
  tell a productive round from a stalling one. Anything still open after the round becomes a stated
  assumption on the requirement.
- **Uploading attaches.** The ingest endpoint adds the document to the project's intake flow
  server-side. A document you just uploaded appears in the wizard's list without being added twice —
  and the browser cannot make an RMI call from the upload's `fetch` callback anyway.
- **A CONFLICT is never applied.** It is a question, not a change; "tick all" skips it and its
  checkbox is disabled, because accepting one is choosing a side.
- **Applying nothing leaves the flow in REVIEW.** If every accepted proposal fails to write, the
  flow does not advance to APPLIED — claiming success for a run that wrote nothing would force a
  full re-analysis to get the review list back.

**Not built yet:** the delivery flow (§2). `GuidedFlowKind.DELIVERY` has no engine behind it.

**Not yet proven:** neither flow has been run against a live model. The tests script the replies;
what a real model returns for the prompts in `RequirementsIntake` and `BacklogPlanning` is untested.
