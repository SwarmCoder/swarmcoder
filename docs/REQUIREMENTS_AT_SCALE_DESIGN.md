# Requirements at Scale — Hierarchy, Tree View, Search

**Status:** APPROVED by the author 2026-07-29 (§3.1 tree-not-DAG and §8.2 thousands-not-tens-of-
thousands confirmed). **§9 steps 1–7 IMPLEMENTED**, steps 1–6 on 2026-07-29 and step 7 on 2026-07-30. Written the same
day after the question *"do we have a hierarchy of requirements, and shouldn't we have a tree/table
with search — we may have thousands"*.

**Step 1 as built.** `RequirementTree` (sc-domain — shared by server and TeaVM client) resolves the
hierarchy and is the single write gate. It absorbed one rule that was **already** asymmetric: only an
NFR may `GATES`, which `BrdAuthoring.addEdge` enforced and `BrdServiceImpl.saveEdge` did not, so a
hand-drawn edge could create a gate no story could satisfy (`StoryScope.gatesFor` had to defend
against it at read time). Both write paths now call `RequirementTree.rejectionFor`; the
restore-a-revision path deliberately does not, because history is reproduced as it was and its shape
shows up as a badge.

**Step 2 as built.** `CheckCounts` (own or summed, immutable) and `RequirementRollup` (own vs subtree
per requirement, plus descendant counts), both sc-domain. Two decisions worth recording:
`BrdRequirement.statusFromEvidence` was **refactored to delegate** to `CheckCounts.allGatingPassing()`,
so a tree row's ratio and a requirement's own IMPLEMENTED badge are one derivation rather than two that
agree today (§6 rule 2 applied to code that already existed). And `CheckCounts` is a final class, not a
record: `StoreArchitectureTest.PERSISTED_DOMAIN_TYPES_ARE_NOT_RECORDS` presumes every
`com.swarmcoder.domain` type is reachable from the EclipseStore root and enforces it bluntly rather
than keeping a list of exemptions — `BuildHealth` and `RunPause` are final classes for the same reason,
so this follows the precedent instead of weakening the guard.

**Step 3 as built.** `RequirementRowDto`, `CheckCountsDto`, `RequirementQuery`, `RequirementPageDto`
and `BrdVersion` (sc-console-api); `BrdService.rows(query, offset, max)`; `RequirementRows` (sc-console)
as a pure function of a `Brd` plus a story list, so the filtering rules are testable without booting a
Console. The "has unclaimed checks" item deferred from step 2 lands here, where the stories are in scope.

**`BrdSignals.VERSION` is published beside `CURRENT`, not instead of it.** The whole-graph push is what
G5 is about, but the canvas editor legitimately wants a whole graph and the revision preview renders a
historic one — so the cheap notification exists now and the tree binds to it in step 4. **G5 is therefore
not yet closed:** it closes when the tree replaces the canvas as the default surface and nothing that
pages listens to `CURRENT` any more.

**A trap found while mutation-checking, worth recording.** For a `@DataModel` type,
`BinarySerializer.writeValue` dispatches to the **generated** serializer and never calls the
hand-written `writeToBuffer`/`readFromBuffer`. Those run only when a parent invokes them explicitly for
a nested object — which `RequirementPageDto` does for each row. A round-trip test written through
`writeValue` therefore proves nothing about the hand-written methods: transposing two adjacent fields in
`RequirementRowDto.writeToBuffer` left all seven such assertions passing. The fix is a second helper that
calls the packed methods directly; with it, the same transposition fails with a `BufferUnderflowException`.
Any future hand-rolled serializer needs the packed test, not just the value test.

**Step 4 as built.** `RequirementTreeView` (sc-console-ui) binds `BrdSignals.VERSION` and re-queries a
window; it never receives the document. It is the Requirements workspace's default surface, with the
canvas behind a **List / Graph** switch — the graph is kept, not replaced, and both stay mounted with one
hidden because rebuilding `BrdView` would re-issue its RMI loads and drop effects only its own
`dispose()` releases. Indent by depth, fold per row and for the whole tree, coverage breakdown on click,
NFR/status/shape badges, and fixed-width right-aligned columns.

**Three defects a screenshot caught that the assertions did not**, all now fixed and pinned:
`Icon.of("alert-triangle")` is not in the icon set — every other call site uses `"warning"` — so the
shape warning rendered as an unrecognizable fallback glyph; a parent with no checks anywhere showed
`0/0 in parts`, a figure that means nothing sitting exactly where progress is read; and the status badges
landed at a different x on every row, because variable-width coverage text shifted them in a
content-sized flex row, which defeats the scanning the whole view exists for. A fourth thing the
screenshot exposed: the seeded browser fixture had **no checks at all**, so the coverage column had never
been exercised — the fixture now carries checks on the children only, which is also what makes the
own-versus-subtree assertion (§6 rule 4) meaningful.

**Step 5 as built.** Search settles for 220ms before asking — a query per keystroke puts eight round
trips into the word "checkout" and flickers through answers to prefixes nobody asked for — with a
generation counter so a timer belonging to an abandoned burst does nothing. Filters are **chips, not
dropdowns**: a filter that is on has to be visible at a glance, and a select box reading "any" looks
identical to one nobody touched. The version and query effects are **one** effect, so an edit arriving
while a filter is on re-asks the same question instead of silently resetting to the whole tree.

**A decision §3.7 did not settle: a filter never changes a coverage figure.** Search for one deep
requirement and its ancestor still reads *"2/3 in parts · across its 2 parts"* while only one of those
parts is on screen. That is deliberate. Roll-ups describe the **document**, not the window — a filter
that changed them would make progress appear to depend on what somebody typed in a search box, which is
a far worse lie than a number whose parts are currently hidden. The counts line is what tells the
operator things are hidden, and that is its job.

**Step 6 as built.** A row's diagram icon hands a specific requirement to `BrdView.focusOn`, which
narrows the canvas to that requirement's neighbourhood — itself, the chain it is part of, its own parts,
and anything one relation away either way. Ancestors rather than just the parent, because "where does
this sit" is the first question a diagram is opened to answer; one hop for everything else, because two
hops on a dense graph is most of the document again. A banner names the scope, states the document total,
and carries the way out. `category` is gone from the editor — a second containment concept beside the
hierarchy whose own placeholder read "category / epic", a noun the concept budget does not have.

**The defect that mattered here, and how it was found.** Focusing happened *before* the graph pane was
made visible, and `SvgCanvas.fit` measures its own `offsetWidth` and gives up when that is zero. So the
neighbourhood drew at whatever scale and pan the canvas last had, and R3 — the requirement the operator
had just asked to see — sat off the right-hand edge. `waitForSelector` passed the whole time, because the
node was in the DOM and "visible". A screenshot found it. The fix is one reordering (show, then focus)
and the assertion is now geometric: the focused node's rect must lie inside its own owner `svg`'s rect.
A first attempt at that assertion compared against `page.querySelector("svg")`, which matches the first
icon on the page and is 32px wide — an assertion that would have passed for the wrong reason.

**Removing the category field could have destroyed data, and nearly did.** `saveRequirement` set
`category` unconditionally from the incoming object, so a form that no longer sends one would have wiped
every stored value on the first edit of each requirement — exactly the class of defect the comment
already in that method describes about the 7-arg constructor. The server now preserves it when absent,
following what `kind` and `nfrCategory` already do, and a service test pins it: edit the text, the
category survives.

**G5 is mitigated, not eliminated.** The default surface no longer receives the document, which was the
point. But `BrdSignals.CURRENT` is still published on every edit because `BrdView` binds it and the
revision preview renders a whole historic `Brd`. Eliminating the push entirely means making the canvas
fetch on demand instead of binding a signal — worth doing, not in this plan, and it should be recorded
as the remaining half rather than counted as done.

**Step 7 as built, and what its review pass found.** Step 7 was meant to be assertions only. It turned
up a **missing feature**: relation badges, specified in §3.2 and drawn in §4's mockup and named in §9
step 4, were never implemented — zero references to `DEPENDS_ON`, `GATES`, `CONFLICTS_WITH` or
`DERIVED_FROM` in either the row DTO or the tree view. Worse, this document's own step-3 note claimed the
DTO carried "relation badge summaries", so anyone reading the design to check it had been implemented
would have been told yes. That note is corrected above; the field now exists.

**Phrasing is decided on the server, not in the renderer**, because `GATES` reads differently from each
end: the NFR *constrains*, the requirement is *constrained by*. One edge, two sentences, and rendering
the same phrase on both rows would tell the operator that an ordinary requirement imposes a quality
constraint on a non-functional one — backwards, and invisible to anyone not looking for it. The direction
is known where the edge is read, so it is decided there. `DEPENDS_ON` gets the same treatment
(*waits for* / *needed by*), `DERIVED_FROM` too (*from* / *led to*); `CONFLICTS_WITH` is genuinely
symmetric and reads the same both ways. `REFINES` gets no badge at all — the indent already says it, and
two statements of one fact are how they drift.

**The rule-6 assertion step 7 asked for was worthless until now.** "No internal relation name in
`body.innerText`" passed for the emptiest possible reason: nothing rendered a relation. It only became a
test once the badges existed, which is a useful reminder that an assertion over absent output proves
nothing. The same applies to rule 1's "no epic", now also pinned.

**And the fixture edges had to move.** Hanging the new conflict off R3 pulled R4 into R3's neighbourhood
and failed the step-6 scoped-graph assertion — the scoping working, not breaking, since a conflict *is* a
relation one hop away. Both new edges hang off R2 so R4 stays outside that neighbourhood and both
assertions keep their meaning.

**Rule 8 has no mechanical guard** — nothing anywhere fails a build when a persisted enum gains a
constant in the middle. Pre-existing rather than introduced here (this work added no enum constant), and
worth a guard of its own some day.

**Deviation from §3.4, deliberate:** the roll-up does **not** answer "has unclaimed checks". Whether a
check is claimed is a property of the backlog, not of the requirement graph, so it belongs to step 3's
query where the store has both. Accepting a claimed-set argument here would mean every caller without
the backlog passing "nothing is claimed", which renders as *every* check unclaimed and looks like data
rather than a missing argument.

**Relationship to existing documents.** `REQUIREMENTS_AND_BACKLOG_DESIGN.md` remains the design of
the requirement model and is not superseded: everything in §1 below already exists because that
document specified it. `CONSOLE_UX_V3.md` remains the shell design and its §5 invariants bind
everything here — in particular rule 1 (nothing vanishes), rule 2 (one derivation per fact) and the
four-noun concept budget. Where this document adds a surface, it adds it *inside* the Requirements
workspace V3 §3 already defines. No new workspace, no new noun.

---

## 1. What already exists (so we do not rebuild it)

The hierarchy is not missing. `RequirementRelation.REFINES` is the parent/child edge — *"the source
decomposes/details the target"* — and the design already ruled on the alternative:

> An earlier sketch had `Epic → Story → Task`. It was wrong. `BrdRequirement` already forms a
> hierarchy through `REFINES` edges… A separate `Epic` class would create a *second* hierarchy
> expressing the same thing, which you would then have to keep consistent by hand.
> **An epic is a coarse requirement with `REFINES` children. It is a rendering, not a class.**
> — `REQUIREMENTS_AND_BACKLOG_DESIGN.md` §2.3

That ruling stands and this document does not revisit it. **No `Epic` class, and "epic" never becomes
an operator-facing noun.**

The hierarchy is also load-bearing rather than decorative. `StoryScope.gatesFor` resolves which
non-functional requirements constrain a story by walking `GATES` edges up the `REFINES` ancestry, so
an NFR attached to "Checkout" applies to everything beneath it without being restated. Its
`collectAncestors` already carries a visited set, so a cycle terminates and multiple parents union
correctly — the *semantics* are DAG-safe today.

Also already built and reused unchanged: `BrdRequirement` carrying `criteria` directly,
`AcceptanceCriterion.effectiveState(contentRevision)` returning `STALE` when a passing check's
evidence predates a reword, the append-only `ChangeEvent` journal, `BrdRevision` snapshots with
preview, and the intake and planning wizards.

## 2. What is actually missing

| # | Gap | Evidence | Consequence |
|---|---|---|---|
| G1 | Nothing constrains `REFINES` to a tree | `BrdAuthoring.addEdge` checks unknown handles, self-loops, relation validity and NFR-only `GATES` — there is no cycle check and no single-parent rule | The shape is a general digraph that happens to be usually tree-shaped. A renderer has no defined answer for "R7 refines both R2 and R5" |
| G2 | No roll-up over a subtree | nothing computes it | The one thing a hierarchy buys over a flat list — "Checkout is 12 of 20 checks verified", maintained by nobody — is absent |
| G3 | No tree, no table, no search, no filter | `BrdView` (2,143 lines) is the only Requirements surface | Finding one requirement among hundreds means visually scanning a canvas |
| G4 | The graph does not survive scale | `layout()` uses `rowsPerColumn = ceil(sqrt(n))` and appends a DOM node per requirement | At n=2000 that is a 45×45 grid of boxes across tens of thousands of pixels, redrawn whole on every signal fire |
| G5 | **The whole BRD is on the wire, every edit** | `BrdAuthoring.commit` → `BrdSignals.CURRENT.set(ArtifactStore.copyOf(brd))`; `BrdService.brd()` returns the entire `Brd` | A deep copy plus a full serialisation plus a push to every connected client, each time one title changes. This is the binding constraint, and it sits *underneath* G3/G4 |
| G6 | `category` is a second grouping | `BrdRequirement.category` — commented *"optional grouping/section/epic"* | Two ways to express containment, which is a V3 rule-2 violation even while they happen to agree |

**G5 is why this is one piece of work and not two.** A tree with server-side search and paging is the
same change as getting off the whole-graph signal. Build the view on `BrdSignals.CURRENT` as it
stands and it gets built twice.

## 3. Decisions

### 3.1 `REFINES` is a tree, enforced

**At most one `REFINES` parent per requirement, and no cycles.** Enforced in `BrdAuthoring.addEdge`
with a named error, the same way `GATES` already refuses a functional source.

Why enforce rather than render a DAG: multi-parent decomposition is ambiguous to a *reader* — "which
of its two parents' scope is this part of?" has no answer — and `DERIVED_FROM` already expresses
"this came from that" without claiming containment. The cost of enforcing now is one validation; the
cost of enforcing later is migrating graphs that rely on it.

Existing stores may already contain a violating shape (nothing prevented it). Loading must therefore
never fail: a requirement with two `REFINES` parents renders under the **first by handle order** and
carries a visible warning badge offering to re-parent. Silently picking one is not acceptable — it
would hide a real inconsistency in the operator's own document.

### 3.2 Only `REFINES` nests. The other four annotate.

`DEPENDS_ON`, `CONFLICTS_WITH`, `DERIVED_FROM` and `GATES` become badges and links on a row, not
nesting. Rendering five relation types as one tree produces a tree nobody can read.

### 3.3 The tree/table is the primary Requirements surface; the graph becomes a focused view

The Requirements workspace opens on the tree. The canvas graph is reached from a row —
**"show what surrounds R7"** — scoped to that requirement's neighbourhood (its ancestors, children,
and everything one edge away), not the whole document.

This is not a demotion of the graph's value; it is putting it where it is strong. A node-link diagram
is excellent at relationships *around* a node and hopeless at a thousand of them. Scoping it also
retires G4 without touching the layout code: a neighbourhood is a dozen nodes.

`BrdView` is retained for that scoped use. Its hand-placed `BrdNodePosition` persistence, relation
colours and revision preview all still apply.

### 3.4 Roll-up is computed once, server-side

Per requirement: the subtree's check counts by effective state (`PASSING` / `FAILING` /
`UNVERIFIED` / `STALE`), the count of descendants, and whether any descendant has unclaimed checks.
Computed in **one** module and rendered by every surface that shows it — V3 rule 2, the `BuildState`
pattern. A parent row shows its own checks *and* its subtree's; those are different numbers and must
be labelled as such.

### 3.5 `category` is retired as a grouping

It stays on the object for provenance (extraction fills it from document headings, which is useful)
but stops being rendered as a grouping anywhere. `REFINES` is the only containment. If a category
genuinely names a coarse area, it should become a requirement with children — and the tree makes that
an obvious, one-action fix rather than an invisible modelling choice.

### 3.6 Search and filter are server-side, over the store

Not client-side over a downloaded graph — that is G5 again. `BrdService` gains a query that returns a
**page of rows**, not requirements: enough to render a line, with the full object fetched only when a
row is opened.

Search covers handle, title, text, and check text. Filters: status, kind, NFR category, has unclaimed
checks, has stale evidence, claimed by a given story. Filters compose; the result count is always
shown, because a filter that silently matches nothing looks identical to an empty project.

### 3.7 Filtering must not make anything vanish (V3 rule 1)

A filtered tree that hides a matching child's non-matching parent would break the hierarchy on
screen. So: **a match is always shown with its full ancestor chain**, and ancestors present only as
context render dimmed and are marked as such. The count states both numbers — *"7 matches, 4 parents
shown for context"* — so the operator is never misled about what matched.

Requirements filtered out entirely are counted in a footer line, openable, exactly as the pipeline
board counts settled stories.

## 4. The surface

```
┌ Requirements ─────────────────────────────────────────────────────────────────┐
│ ⌕ guest checkout            status: ACTIVE ▾  kind: any ▾  ⚑ unclaimed  ⚑ stale│
│ 7 matches · 4 parents shown for context · 1,983 not matching (open)           │
├───────────────────────────────────────────────────────────────────────────────┤
│  handle  requirement                       kind    status   checks    story   │
│  R2      Checkout                          FUNC    ACTIVE   12/20 ▾   —       │
│   └ R7   Guests can check out              FUNC    ACTIVE    2/3      S3      │
│      └ R9  Empty cart is rejected          FUNC    ACTIVE    1/1 ✓    S3      │
│      └ R11 Order confirmation is emailed   FUNC    ACTIVE    0/1 ✗    S3      │
│   └ R8   Saved cards                       FUNC    DRAFT     0/2      —       │
│  R12     p95 API latency < 200ms @ 100rps  NFR/PERF ACTIVE   1/1 ✓  gates R2  │
│                                                                               │
│  R2 row actions:  Open  ·  Show what surrounds it  ·  Add child requirement    │
└───────────────────────────────────────────────────────────────────────────────┘
```

- **Rows are the unit.** One line per requirement, indented by `REFINES` depth, collapsible.
- **`12/20 ▾` on a parent is the subtree roll-up**, expandable to the breakdown by state. The
  parent's own checks are shown on its own line when it has any; a requirement with children and no
  checks of its own says so rather than showing `0/0`.
- **Every row carries its relations as badges** — `gates R2`, `depends on R4`, `conflicts with R6` —
  each a link that scrolls to or opens that requirement.
- **Depth is not navigation.** Opening a row opens the existing editor over the tree, the same dialog
  idiom V3 §3.2 settled for stories.
- **Empty states teach**, per V3: an empty tree says where requirements come from and offers the
  intake wizard.

## 5. The wire (G5) — what has to change first

Today: `BrdService.brd()` returns the whole graph, and every mutation republishes a deep copy of it
on `BrdSignals.CURRENT`.

The change, in order of dependency:

1. **A row projection.** `RequirementRowDto` — id, handle, title, kind, NFR category, status, depth,
   parent id, own check counts, subtree check counts, claiming story key, relation phrases.
   Enough to render a line and nothing more. Text bodies, full criteria, source refs and the change
   journal stay behind the row.
   *Serializer note:* the zeroz4j serializer supports `List` and `Map` but **not `Set`** — the reason
   `BacklogTask` is a projection today (`REQUIREMENTS_AND_BACKLOG_DESIGN.md` §0, D2). Keep the DTO to
   `List`/`Map` or it is not wire-representable.
2. **A paged query.** `BrdService.rows(RequirementQuery query, int offset, int max)` returning rows
   plus the three counts §3.7 requires (matches, context parents, excluded). The query object carries
   the search string and filters so adding a filter later does not change the method signature.
3. **A narrow change signal.** `BrdSignals.CURRENT` stops being the editor's data source. What
   clients need is *"something changed, revision is now N"* — `Brd.revision` already exists for
   exactly this — plus, for the open row, that row. The tree re-queries; it does not receive the
   document.
   *Trap:* signal dedup compares by `equals`, so the notification must be a fresh value and must
   differ when it means something new. A bare revision counter satisfies both; reusing the same
   object mutated in place does not (V3 rule 7, six prior incidents).
4. **Only then the view.**

`BrdSignals.CURRENT` is not deleted in this step — the scoped graph view still wants a graph, and the
revision preview still renders a whole historic `Brd`. It stops being what the *tree* binds to, and
what it carries becomes a deliberate fetch rather than an automatic push.

## 6. Hard rules (invariants for the developer agent)

1. **No `Epic` class and no "epic" in the UI.** §2.3 of the requirements design already ruled;
   V3's four-noun budget still binds.
2. **One derivation per fact.** Roll-up counts are computed in one module and rendered from it
   everywhere. A second computation is a defect even while it agrees.
3. **Nothing vanishes.** A filter shows matches with their ancestor chain and counts what it excluded.
   A collapsed subtree shows its count.
4. **A parent's own checks and its subtree's checks are different numbers** and are never conflated
   into one figure.
5. **`REFINES` is single-parent and acyclic going forward, and tolerant of violating history** — a
   pre-existing bad shape renders with a warning and a fix, never a load failure and never a silent
   pick.
6. **No operator-facing internal names.** `REFINES`/`GATES`/`DEPENDS_ON` are internal: rows say
   "part of", "constrained by", "waits for". V3 §4 is the vocabulary and additions update it.
7. **The known-biting machinery** (V3 §5.7): RMI only from zeroz4j DOM handlers or `new Thread`;
   signal dedup needs fresh copies and accessor-based `equals`; persisted and wire enums are
   append-only; hand-rolled serializers append in **both** methods; stale `~/.m2` jars mean
   full-reactor builds.
8. **`RequirementStatus` and `CriterionState` are persisted enums.** New states append or nothing
   starts (one prior startup outage).

## 7. Walkthroughs

**Finding one thing in two thousand.** Operator types "email" — 3 matches, each shown under its
ancestor chain, dimmed parents marked as context, footer counting the rest. They open R11, see the
failing check and the story that claimed it, close, and click through to S3 on the pipeline. No
canvas, no dragging, no scanning.

**Understanding a coarse requirement.** R2 "Checkout" shows `12/20` and five children. Expanding the
roll-up gives 12 passing, 3 failing, 4 unverified, 1 stale. The stale one is a check whose wording
changed after it last passed — visible without opening anything, because `effectiveState` already
knows and the roll-up now surfaces it.

**Seeing the shape around something.** From R7's row, "show what surrounds it" opens the existing
canvas scoped to R7: its parent R2, its three children, the NFR gating it, and one `DEPENDS_ON`
neighbour. Nine nodes. The graph is legible again because it stopped trying to draw the document.

**A requirement with two parents, from an older store.** R7 renders under R2 with a warning badge:
*"also marked part of R5 — a requirement belongs in one place."* One click re-parents. Nothing failed
to load, and the inconsistency was neither hidden nor silently resolved.

## 8. Assumptions taken (veto any at review)

1. **A tree, not a DAG** (§3.1). The strongest assumption here; everything about the view follows
   from it.
2. **Thousands is the target, not tens of thousands.** Paged rows over EclipseStore in-process is
   sufficient at 10⁴; nothing here introduces an index. If the real number is 10⁵ this needs a
   different answer and should be said now.
3. **Search is substring, not semantic.** Handle, title, text, check text, case-insensitive. A
   semantic search over requirements is a plausible later addition and is not this.
4. **The graph keeps `BrdNodePosition`** — hand placement survives, scoped views included.
5. **`category` keeps its data** and loses its rendering (§3.5).
6. **No bulk edit in this version.** Multi-select re-parenting and bulk status changes are an obvious
   next step and are deliberately out, because each needs its own impact preview per §2.2 of the
   requirements design (editing a claimed requirement stales evidence and sends stories back).

## 9. Build order (each step shippable)

1. **Enforce the tree** — single-parent and acyclic `REFINES` in `BrdAuthoring.addEdge`, with the
   tolerant read path and the warning badge. Pure server; no UI change beyond the badge.
2. **Roll-up module** — one place computing subtree check counts and descendant counts, unit-tested
   against a hand-built graph including a violating shape and a `STALE` check.
3. **Row projection + paged query** — `RequirementRowDto`, `RequirementQuery`,
   `BrdService.rows(...)`, and the narrow revision signal. Server and wire only, with a round-trip
   test (the wire contract test already exists for this class of change).
4. **The tree view** — rendering, indent, collapse, roll-up expansion, relation badges, row actions.
   Becomes the Requirements workspace's default surface.
5. **Search and filter** — the query bar, the three counts, ancestor-chain context, the excluded
   footer.
6. **Scope the graph** — `BrdView` takes a focus requirement and renders its neighbourhood; reached
   from a row. Retire `category` as a grouping.
7. **Pin the invariants** — browser tests for: a filter leaves matches *and* their parents on screen
   with honest counts (rule 3), parent-own vs subtree counts are distinct (rule 4), a two-parent
   requirement loads with a warning rather than failing (rule 5), and no internal relation name
   appears in `body.innerText` (rule 6).

Steps 1–3 are worth doing even if the view is deferred: they remove G1, G2 and G5, which are defects
independent of how anything is drawn.
