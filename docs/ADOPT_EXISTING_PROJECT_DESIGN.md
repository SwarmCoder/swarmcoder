# SwarmCoder — Adopting an Existing Project

**Status:** DESIGN. Nothing here is built. Written 2026-08-28 in answer to the question *"can I
point SwarmCoder at a project folder, have it find the existing documentation, extract the
requirements, and work out what has been implemented and what has not — what would that cost, and
is it even feasible with what we now have?"*

**Two decisions are already made and this design obeys them.**

1. **Evidence only.** A requirement counts as implemented only where a real test ran, passed, and
   is matched to the check it proves. A model may say "this looks implemented, probably by this
   code" — that appears as a **suggestion**, never as evidence, and never reaches a total.
   (`DEVELOPER_CORRECTIONS.md` §20, §22, §24.)
2. **Design before build.** This document is what the decision is made from.

**Everything numbered below was measured on this repository today**, with the method stated. Where
a figure is an estimate it says so.

---

## 0. The answer

**Yes, it is feasible, and most of it is assembly.** Four of the six steps already exist and run.
The two new ones are a folder walk and a pairing queue.

**But it will not do the thing the question implies.** It cannot hand you a percentage. On this
codebase — which is unusually well tested and unusually well named — the honest ceiling is that
**about a third of requirements can be proved, and the pairing between a requirement and the test
that proves it has to be confirmed by a person, one at a time.** That is measured, not guessed
(§1.3, §1.4).

So the deliverable is not a report. It is **a queue of proposed pairings and a list of what the
project cannot demonstrate about itself.** If that sounds useful, build it. If you wanted a
dashboard number, do not.

> **Read §11 before acting on this answer.** The pairing was re-measured on 2026-08-28: it can be
> automated, the confirmation queue is gone, and the session list below changes.

**Cost: five worker sessions** of the size landed this week (§8), of which one is a live shakedown
against a second real repository, because §19 is the evidence that defects in this kind of work
appear only when it is run for real.

---

## 1. What was measured

### 1.1 The documentation a folder walk would find

Method: every `.md`, `.txt`, `.adoc`, `.rst` in the checkout, excluding build output and `.git`.

| | SwarmCoder | zeroz4j |
|---|---|---|
| Candidate documents | 18 | 57 |
| Total size | 601,145 characters | 708,260 characters |
| Largest single document | 193,197 (the corrections log) | 122,419 (the changelog) |
| Share in one file | 32% | 17% |
| README files | **none** | 14 |
| Files in tool folders (`.claude`, `.github`) | 0 | 0 |
| What the exclusions save | **nothing** — 0 bytes | nothing — 0 bytes |

Two things fall straight out. **The corpus is small in file count and large in bytes**, dominated
by a handful of very long hand-written design documents. And **build-output exclusions are not
where the difficulty is** — a careless walker and a careful one find the same corpus. The
difficulty is length.

**How many analyst passes.** The analyst refuses to start when the documents it is given exceed
250,000 characters (`RequirementsIntake.DEFAULT_BUDGET_CHARS`), and refuses rather than reading
part of them — there is already a test for that
(`GuidedFlowIntakeTest#documentsOverTheLimitRefuseToStartInsteadOfBeingPartlyRead`). So:

- SwarmCoder: 601,145 ÷ 250,000 = **3 passes**, and one single document uses 77% of one pass on
  its own.
- zeroz4j: 708,260 ÷ 250,000 = **3 passes**.

Three passes is not a problem. The machinery to run them already exists and is tested:
`GuidedFlowIntakeTest#aSecondRunReadsOnlyTheNewDocumentButStillSeesTheBrdTheFirstOneWrote` — a
later pass reads only its own documents but sees everything earlier passes wrote. **A repeated
requirement across passes is caught by the similarity matcher built yesterday** (§27), which
un-ticks it and names the one it repeats.

**Estimate, not measured:** a real customer project with generated API documentation, a wiki export
or a `node_modules` tree could be ten to a hundred times this. The walk needs a size ceiling and a
per-file cap that the operator sees and can raise, in the same shape as the analyst's existing
refusal.

### 1.2 The test suite, and why harvesting it is free

Method: static count of `@Test` annotations across `src/test/java`. No test suite was run.

| | SwarmCoder | zeroz4j |
|---|---|---|
| Test source files | 121 | 89 |
| Test classes with at least one test | 111 | 87 |
| Test methods | **543** | **612** |
| Parameterised / repeated / factory tests | **0** | 0 |
| `@Disabled` | 0 | 0 |
| Environment-gated (needs Chromium, Docker, a live model, Maven, a demo repo) | 16 annotation sites, roughly 19–22 methods | — |

Zero parameterised tests means the static count is an accurate prediction of what a run reports.
That will not hold on every project; where it does not, the run's own output is the truth and the
static count is only a sanity check.

**The important finding.** One ordinary verification run already yields the complete inventory.
`TestResults` — the object the pipeline fills in from the runner's XML — carries the identifiers of
**every test that passed**, every one that failed, and every one that was skipped, in exactly the
form the runner emitted them (`ClassName#methodName`, including nested classes and parameterised
invocations). Its own comment says why: counts alone cannot answer "did *my* test run, and did it
pass?"

So the harvest step needs **no new code at all**. Point the existing pipeline's "run the existing
tests" stage at the adopted project and the inventory comes back with it.

**One limit, and it is real.** The identifier list is capped at 5,000 per stage
(`TestResults.MAX_IDS`), and when the cap is hit a flag says the list is incomplete. Consumers must
then answer "unknown", never "passed". SwarmCoder (543) and zeroz4j (612) are nowhere near it. A
project with more than 5,000 tests in one stage would harvest nothing usable, and the feature must
say so plainly at the start rather than produce an empty report at the end.

**What the harvest costs the operator:** one full build and test run of the adopted project. For
this repository that is 30–60 minutes. It is paid once per adoption and again whenever the operator
refreshes it.

### 1.3 The ceiling — how often a real requirement has a test that proves it

**This is the most important number in this document.**

Method: I took the 60 numbered requirements in `REQUIREMENTS_AND_BACKLOG_DESIGN.md` §9 — real
requirements, written before the work, for the feature that built the requirement graph itself —
and sampled every third one, giving 20. For each I hunted by hand through all 543 test methods and
the test sources for the test that proves it. The sampling rule was fixed before I looked at any
of them.

| Verdict | Meaning | Count |
|---|---|---|
| **Fully proved** | every part of the requirement has a named test that would run | **7 of 20 (35%)** |
| **Partly proved** | a test exists for some of it; at least one clause has nothing | 9 of 20 (45%) |
| **Nothing** | no test could show it | 4 of 20 (20%) |

Under decision 1, **only the first row counts.** A requirement is delivered when *every* check
passes, so the nine partial ones are recorded as not proved. **The ceiling is 35%.**

The four with nothing are instructive, and three of them are correct answers rather than gaps:

- *"All new views dispose their effects; verified by a leak test"* — the leak test was never
  written. A real gap, and finding it is the feature working.
- *"Optional pull-request overlay when a remote is configured; absence never blocks"* — never
  tested. A real gap.
- *"Docs updated"* — nothing could ever prove this. Correctly unprovable, and the product's
  agreement gate (§27) would refuse it as scope today.
- *"`/backlog` chat mode with six story tools"* — **the code no longer works this way.** The
  design document itself says the mode was superseded on 2026-07-27 by a wizard. The requirement is
  stale. See §4.

**Three reasons this 35% is optimistic, and a real project will do worse.**

1. **The requirements name their own classes.** "`TaskGraphValidator` coverage check…" tells me
   where to look. A requirement extracted from prose — which is what adoption actually produces —
   gives no such handle.
2. **The tests were written for these requirements**, by the same people, in the same weeks. Most
   codebases have tests written for the code, not for the requirements.
3. **I read the source.** I did not judge from test names alone. The tool, without a model reading
   test bodies, has less to go on than I did.

### 1.4 The matcher cannot make the pairing — measured

The product has a text-similarity matcher built yesterday (`RequirementSimilarity`, §27): Lucene's
English analysis, stemming with a prefix rule, one-to-one word overlap. It flags a pair at 0.50 and
treats it as the same thing at 0.85.

I extracted that class into a throwaway probe outside the repository (scratch directory, no
production code written, nothing installed), compiled it against the same Lucene version the
project pins, and scored **28 pairs where I had already found the right test by hand** — so every
pair is a known true match.

| | lowest | highest | mean | above the 0.50 flag |
|---|---|---|---|---|
| Test identifier as the runner emits it | 0.000 | 0.182 | 0.051 | **0 of 28** |
| Same identifier, camel-case split into words | 0.105 | **0.429** | 0.197 | **0 of 28** |

For contrast I scored 8 deliberately wrong pairings: 0.000 to 0.188, mean 0.045.

**Three conclusions, all load-bearing.**

- **The matcher as it stands cannot be pointed at test names at all.** Lucene's tokeniser does not
  split `storiesAndIterationsSurviveReopenAndKeysStayUnique`; it is one word, and scores near zero.
  A camel-case splitter is mandatory new work, and it is trivial.
- **Even split, nothing reaches the threshold.** The best true pair in the sample scored 0.429
  against a flag threshold of 0.50. Not one of 28 would be flagged. **Lowering the threshold is not
  the fix** — the wrong pairs reach 0.188 and the true ones start at 0.105, so the ranges overlap
  and no threshold separates them. This is the same finding §27 recorded for requirement-to-
  requirement matching, and it is worse here.
- **But there is signal.** True pairs average four times the wrong ones. That is enough to
  **rank** candidates, and not remotely enough to **decide** one.

**Therefore: the matcher shortlists, a model explains, and the operator confirms — every time.**
There is no automatic path. That single fact shapes the whole design.

> **Superseded in part — see §11.** The ranking claim above is right and was never measured as a
> ranking. It was: the correct test is inside a ten-long shortlist 89% of the time, and a model
> reading the test SOURCE picks it out with no confident wrong answers. "There is no automatic
> path" is wrong. "No threshold may ever decide" stands.

---

## 2. The steps, end to end

| # | Step | Exists? | New work |
|---|---|---|---|
| 1 | Point at a folder, find candidate documents | **No** | The walk, the filter, the size ceiling |
| 2 | Turn documents into requirements with checks | **Yes**, proved live 13 times (§19) | A pass planner that splits a corpus across the 250,000-character budget |
| 3 | Run the project's build and tests once, in a container | **Yes** | Store the inventory as a durable object, not a per-candidate report |
| 4 | Propose which test proves which check | **No** | The shortlist, the model call, the queue |
| 5 | Operator confirms each pairing | Partly — the tick-and-apply pattern exists | The screen, and writing the confirmed test name onto the check |
| 6 | Read the evidence and report | **Yes**, unchanged | Nothing |

### Step 1 — the walk (new)

Walk the folder, keep `.md`, `.txt`, `.adoc`, `.rst`, `.pdf`, `.docx` — the same set the existing
extractor already reads (`DocumentExtractor`, which handles text passthrough, PDFs via PDFBox, Word
via POI, and images via the vision model). Skip build output, dependency trees and version-control
internals. Rank by size and by path (a `docs/` folder outranks a test fixture). Show the operator
the list with sizes **before anything is read**, and let them un-tick.

The destination is proven: each accepted file goes through `DocumentIngest`, which already caps
uploads at 25 MB, computes a checksum and refuses a duplicate.

**Roughly a third of a session.** It is a directory walk and a list screen.

### Step 2 — the analyst, run several times (mostly exists)

Nothing about the analyst changes. What is new is a planner that packs the corpus into passes under
the 250,000-character limit, keeps whole documents together, and runs the existing intake flow once
per pass. Later passes see what earlier ones wrote, and the similarity matcher un-ticks repeats.

**Roughly two thirds of a session**, almost all of it the packing rule and its tests.

### Step 3 — the harvest (exists)

Run the adopted project's own build and test commands through the existing verification pipeline
and keep what comes back. See §1.2: the inventory is already in the result object. What is new is
that today this result is attached to one candidate in one run and is thrown away; here it is the
adoption's primary asset and has to be stored, dated, and shown.

**Roughly half a session.**

### Step 4 — the pairing proposal (new, and the hardest part)

For each check on each drafted requirement:

1. **Shortlist** the ten closest test identifiers using the similarity matcher, over camel-case
   split names, with no threshold — this is a ranking, not a decision (§1.4).
2. **Ask the model** which of those ten, if any, proves the check, and to say why in one sentence.
   It may answer "none of these". **It may only cite an identifier it was shown**, and an answer
   naming anything else is discarded — the same rule the planner already lives under, where a task
   claiming a check outside its slice is a violation rather than a warning.
3. **Record the answer as a suggestion.** Nothing is written to the check.

**About a session and a half.** This is where the effort is, and where a live shakedown will find
things.

### Step 5 — the operator confirms (mostly new screen, proven pattern)

One row per check: the check's wording, the suggested test, the model's one-sentence reason, and
whether that test passed, failed or never ran in the harvest. The operator confirms, rejects, or
picks a different test from the shortlist. Confirming writes the test's name onto the check, which
is exactly what the intake wizard already does when it proposes a test name and the operator edits
it — and the product already distinguishes the operator's authorship from the wizard's suggestion
(`GuidedFlowIntakeTest#anOperatorEditOfTheReferenceMakesItTheirsAndAnUntouchedOneStaysAProposal`).

**About a session**, mostly screen and copy.

### Step 6 — the evidence (exists, untouched)

Once a check names a test, `CriterionEvidence` answers whether it passed, failed, or never ran,
against the harvest. It already handles awkward runner spellings, nested classes and parameterised
invocations, and it already returns **unknown, never passed**, for a test that did not run — pinned
by `RealJUnitCriterionMatchingTest#aCriterionWhoseTestNeverRanCannotBeDeliveredEvenWhenTheSuiteIsGreen`.

**No new work.** This is the whole reason the feature is affordable.

---

## 3. What it will claim, and what it will not

### The problem, stated plainly

Run this on a real codebase and most requirements come back with **nothing that could show they
work**. On this repository the best case is 35% proved (§1.3). On a customer's codebase, less.

That is the accurate answer. The design's job is to make it read as useful rather than as a broken
feature — because a list of what a project cannot demonstrate about itself is, on the face of it,
an insult.

### The solution: do not invent a status, and do not show a percentage as the headline

**The vocabulary already exists.** A check that nothing has proved is `UNVERIFIED`; that is one of
the four states a check can be in, and it already renders. An adopted requirement is not "failed"
and not "unknown" — it is agreed scope whose checks are unverified. Nothing new is needed, and
nothing new should be added: a special "adopted, unproven" status would be a second way of saying
what the product already says.

**The agreement gate does the right thing here by accident, and it should be left alone.** Since
yesterday, a requirement cannot become agreed scope unless at least one live check names a test
that could prove it (§27). On adoption, most extracted requirements name no test. **So they land as
drafts, and that is correct.** The document you get out of an adoption run is a pile of drafts,
of which the ones you could pair with a real test become agreed scope. The operator is not told
"78% unknown"; they are told "these 40 requirements are agreed, because a test proves each; these
120 are still drafts, because nothing does."

### The headline sentence

Not a percentage. A count of things to do:

> **Read 18 documents and ran 543 of this project's tests. 41 requirements have a test that proves
> them — those are agreed. 119 do not. 63 of those have a likely test waiting for you to confirm.
> 56 have nothing in the suite that resembles them.**

(The 18 and the 543 are this repository's real figures. The four requirement counts are made up to
show the shape of the sentence — nothing has been run to produce them.)

Every number in that sentence is an action. The last one is the interesting one, and it should be
the loudest thing on the screen, because it is what the operator actually bought.

### A worked example, in the words the operator reads

> **R47 — "A restore of an earlier requirements document must not destroy the checks on a
> requirement."**
>
> *Still a draft. Nothing in this project's tests could show this works.*
>
> We read this requirement out of `FLOW_INVENTORY.md`. We ran all 543 of this project's tests and
> none of them touches restoring an earlier version. The closest we found was
> `RequirementTreeTest#refusesASecondParentButAcceptsTheSameOneAgain`, and it does not test
> restoring anything, so we have not suggested it.
>
> **A model looked at the code and thinks this is probably implemented in `BrdServiceImpl`, in the
> restore path.** That is an opinion. It is not evidence, it is not counted anywhere, and this
> requirement stays a draft until a test proves it.
>
> **[ Write a test for this ]  [ Point at an existing test ]  [ Not a requirement ]**

Three things are doing the work there. It says **what was searched** and how much of it, so
"nothing found" reads as a completed search rather than a shrug. It says **what the nearest miss
was**, so the operator can see the search was competent. And it ends in **three buttons**, so an
unproved requirement is a piece of work, not a verdict.

### The line that must appear at the top and never be softened

> **This says what your project can prove about itself, not what it does.** A requirement with no
> test here is very often working code that nobody wrote a test for. This tool cannot tell the
> difference, and will not guess.

---

## 4. Stale and contradictory documentation

A three-year-old README describes a system that has moved on. This is not hypothetical — **this
repository's own requirements document has all three failure modes in it today.**

1. **A statement that was true and is not.** §0 of `REQUIREMENTS_AND_BACKLOG_DESIGN.md` says *"None
   of it has been run against a live model or a real repository."* That was overtaken on 2026-08-28
   by thirteen live runs (§19). An adoption run reading that document today would extract a
   requirement about something that is no longer the state of the world.
2. **A requirement for behaviour that was deliberately removed.** B-22 asks for a `/backlog` chat
   mode with six story tools. §5.2 of the same document says it was superseded by a wizard on
   2026-07-27. The requirement is stale, the code is right, and the harvest finds no test because
   there is nothing to test.
3. **Two documented deviations where the code deliberately differs from the design** — the upload
   endpoint takes raw bytes rather than a multipart form, and one object travels on the wire as a
   projection rather than whole. Both are written down with reasons. An adoption run that read only
   the design would extract two requirements the code knowingly does not meet.

### What happens, mechanically

**Nothing new is needed.** The intake already models exactly this. A proposal is one of four kinds:
**add** a requirement that does not exist, **edit** one that does (carried as before-and-after so
the operator reads a diff), **deprecate** one the documents no longer support, or **conflict** —
two things that cannot both be true, with nothing to apply and a contradiction to resolve. Nothing
is ever deleted; a superseded requirement keeps its handle, its history and every link the backlog
and the commit trail hold against it.

**Adoption uses all four, and adds nothing.** What it adds is one input the intake has never had:
the harvest. So a fifth situation becomes visible, and it is the valuable one —

> **A requirement the documents state, and a passing test that contradicts it.**

The document says uploads are multipart; a passing test says they are raw bytes with query
parameters. That is a conflict between a document and running code, and it is the single most
useful thing an adoption run can find.

### Is the operator asked? Yes — always, for conflicts. Never automatically resolved.

**The document does not win and the code does not win.** Either could be the mistake: a document
can be stale, and code can be a bug that nobody noticed. The product's existing conflict kind is
already defined as "nothing to apply, a contradiction to resolve", and that is exactly right here.

The operator gets the two statements side by side, the test that establishes the code's side, and
three choices: the document is stale (deprecate the requirement), the code is wrong (keep the
requirement as a draft, and it becomes work), or both are partly right (edit the requirement).

**One rule, and it is not negotiable: a passing test never edits a requirement on its own.** That
would let the code rewrite its own specification, which is the same class of mistake as §13 — the
restrained party deciding what it is measured against.

---

## 5. Where the model's opinion appears, and where it is forbidden

Decision 1, made exact.

### Allowed — exactly three places

| Where | What it says | What it is |
|---|---|---|
| The pairing shortlist | "Of these ten tests, number 4 proves this check, because…" | A **suggestion** on a row. The operator confirms it before anything is written. |
| The nothing-found row | "This looks implemented, probably in `BrdServiceImpl`" | A **hint**, in muted text, under a heading that says it is an opinion. |
| The draft requirements themselves | everything the analyst extracts | Already governed: nothing lands until the operator applies it, and nothing becomes agreed scope without a check naming a test. |

### Forbidden — with the mechanism that stops it, not just the rule

1. **A model opinion may never set a check's state.** The only thing that sets a check to passing is
   `CriterionEvidence`, reading a real runner result. The path from a model's opinion to that class
   does not exist and must not be created.
2. **A model opinion may never enter a count.** Every number on the report is computed from
   confirmed pairings only. A suggestion sits outside every total. The test to write is the one that
   says a report with fifty suggestions and zero confirmations reports zero proved.
3. **A model opinion may never be persisted onto a check.** It lives on the pairing proposal, which
   is discarded when the queue is cleared. If it were stored on the check, something downstream
   would eventually read it as a test name.
4. **A model opinion may never name a test that was not in the shortlist.** A cited identifier that
   was not one of the ten shown is discarded, not looked up. This is the rule that stops a
   hallucinated test name from being written onto a check by an operator clicking through quickly.
5. **A model may never propose a *new* test name during adoption.** The intake wizard does this for
   new work and should keep doing it. In adoption it would produce a name for a test that does not
   exist, and `CriterionEvidence` would correctly answer "never ran" — a check pointing at fiction,
   which is worse than a check pointing at nothing. Adoption's model may only choose from tests that
   really ran.

**Why point 5 matters more than it looks.** It is the difference between "nothing proves this" and
"something that does not exist fails to prove this". The first is a true statement about the
project. The second is a defect that looks like a finding.

---

## 6. Running somebody else's build

Adoption means executing a stranger's build and test commands. That is the riskiest thing this
feature does, and the product has already thought about it once.

### What the sandbox covers

Worker commands run in a container by default — `SandboxConfig.enabledOrDefault()` returns true
unless the operator turns it off, with a comment saying why the old default was wrong: *"the safe
setting should be what you get without reading the documentation."* The container has **no network
by default**, a process limit, and CPU and memory caps. If a container is configured and will not
start, the run is **refused** rather than quietly falling back to the workstation
(`SandboxAttach`), and `SandboxAttachTest#hostFallbackSurvivesOnlyWhenTheOperatorOptedOut` pins it.

### What it does not cover, and this is the part to read

1. **`exec` has no policy layer** (§13.3). It is an unfiltered shell — no allowlist, no path
   interception, no audit. The container bounds it; nothing else does. With the container off, a
   build script runs on the workstation with the operator's privileges.
2. **The allowed-hosts list is parsed and not enforced.** Network is all or nothing: none, or
   unrestricted.
3. **Verification commands run on the host at integration.** That is a deliberate existing design
   point, and it is exactly why the next paragraph exists.

### The principle, applied

> **Policy must be resolved from a tree the restrained party cannot write.** (§13.1)

This is already in code. `VerifySpecLoader.loadTrusted` takes the build recipe from the operator's
own tree and falls back to the workspace only when there is none. Its comment says why in one
sentence: reading it from the workspace under test *"let a worker rewrite its own examiner"* —
certify itself green, and run arbitrary commands on the operator's machine.

**Adoption walks straight into that hazard, because the workspace is now somebody else's
repository.** So:

- **The adopted project's own build file is a suggestion, not a recipe.** If the folder contains
  `.swarmcoder/verify.yaml`, show it to the operator as text. Do not execute it. The operator's
  copy, in the operator's tree, is what runs.
- **The harvest runs in the container, with no network, always.** Not "by default". A build that
  needs the network to fetch dependencies is a build the operator explicitly enables, having been
  told what that means.
- **Show the commands before the first run.** "This is what I am about to execute in your project.
  Read it." A build script is the most likely place for something unpleasant, and it is also
  exactly the sort of thing an operator can eyeball in ten seconds.
- **The adopted repository is never written to.** Adoption reads; it does not build branches or
  commit. Everything it produces lives in SwarmCoder's own store.

---

## 7. How this differs from the flow that exists

Today the requirement graph describes work **to be done**. Here it describes what already **is**.
Four things change, and one of them is a genuine design question rather than a detail.

### 1. Story planning has nothing to plan

The planner slices agreed requirements into stories to be built. An adopted requirement that is
already proved needs no story — the work is done. So **adoption's output feeds planning only
through the unproved ones.** The one-item queue that drives the console's Home screen becomes: the
checks with no test, ordered by how much of the graph they block.

**Nothing needs to change in the planner.** A proved requirement has all its checks passing, and the
planner already ignores those.

### 2. "Done" means something narrower, and the product should say so

For new work, delivered means: we built it, our test proved it, at this commit. For adopted work it
means: **a test that already existed passes, and a person agreed it proves this requirement.** The
commit is whatever the project was at when the harvest ran.

That is genuinely weaker. It says nothing about whether the test is a good test. **The design's
answer is not to hide the difference but to record it:** every check confirmed during adoption
records that it was adopted, with the date of the harvest, so a later reader can tell "proved by a
test we wrote to prove it" from "proved by a test that already existed and somebody agreed was
close enough".

### 3. A requirement implemented but with no test — the important case

This is the majority case, and it must not be treated as a failure.

**It stays a draft.** The agreement gate refuses agreed scope without a check naming a test (§27),
and that is the right answer: the operator has no way to know it stays true. The screen offers to
turn it into work — *"write a test for this"* — which is a perfectly good story and probably the
most valuable thing an adoption run produces.

**The model's opinion appears here and nowhere else on the row**, as a muted hint about where the
code probably lives, because that is genuinely useful to someone about to write the test and is
harmless as long as it touches no total (§5).

### 4. The graph is much bigger from the start

New work grows a graph a requirement at a time. Adoption drops 100–500 in at once. Everything
needed for that landed yesterday (§27): the analyst is sent at most 120 requirements ranked for
relevance, with a heading that says the rest exist; the drafting prompt stopped growing without
bound (1,127,860 characters at 5,000 requirements, now 27,316); and repeats are caught in code
rather than by asking the model nicely.

**Adoption is the first thing that will actually exercise that at scale**, and that is an argument
for building it: the scale work is currently proved only by tests that construct 5,000 synthetic
requirements.

---

## 8. The cost

In the currency of this week's sessions — a session lands 5 to 21 files and 400 to 2,500 lines,
with one or two new test classes and regressions run over what changed.

| # | Session | What it covers |
|---|---|---|
| 1 | **The walk and the passes** | Walk a folder, filter and rank candidates, show them with sizes, pack them into passes under the analyst's character limit, run the existing intake once per pass. Ends with: point at this repository, get drafts out. |
| 2 | **The harvest** | Run an adopted project's build and tests in the container, store the full test inventory as a durable dated object, show it. Ends with: 543 tests, this many passed, this many never ran. |
| 3 | **The pairing queue** | Camel-case splitting, the shortlist, the model call constrained to the shortlist, the suggestion object, the four forbidden paths in §5 each with a test. The biggest session. |
| 4 | **The report and the conflicts** | The screen, the headline sentence, the nothing-found row, conflict handling, and the copy — which on the evidence of this document is half the work. |
| 5 | **The live shakedown** | Run the whole thing against zeroz4j — a real repository nobody wrote this feature for. Fix what it finds. |

**Five sessions.** Session 5 is not optional padding. §19 is the record of what happens without it:
thirteen live runs of the delivery journey produced four defects, every one of which was invisible
to the scripted tests, and three of them were hidden behind each other so each appeared only once
the previous was fixed.

**What is deliberately not in the five:** reading test bodies with a model to improve the pairing
(a real improvement, and a separate question); adopting anything that is not a Maven or Gradle JVM
project; and anything that writes to the adopted repository.

---

## 9. What would make this not worth building

The honest case against, in the order I find it convincing.

### 1. The operator has to confirm every pairing, and there may be hundreds

This is the strongest argument and it is measured (§1.4). No threshold on any text measure
separates a true pairing from a false one, so there is no automatic path — not now, and not with a
better threshold. On this repository the queue would be several hundred rows.

**Counter:** it is one row per check, each is a yes/no with the answer on screen, and it is paid
once. Compare it to the alternative, which is reading 543 test names by hand — which is what I did
today, and it took hours for twenty requirements.

**But if the owner would not sit through that queue, the feature has no user, and nothing else in
this document matters.**

### 2. What comes back is mostly "we cannot show this"

Two thirds of requirements, on the best-case codebase available. §3 argues that this is useful
information. It is also, unavoidably, a long list of things the project cannot prove — and if the
reaction to that list is to distrust the tool rather than the project, the feature has produced
nothing but doubt.

### 3. Nobody has ever run this product against a real codebase

The delivery spine was proved live thirteen times — on a **one-class demo repository**, with two
workers, on one small task. Adoption points the same machinery at a hundred-thousand-line project
on day one. That is a bigger jump than it looks, and session 5 exists because of it.

### 4. A cheaper thing might answer the same question

If the real question is "what does my project not test?", that is a smaller feature: harvest the
inventory, list every test, and let a person read it. No documents, no analyst, no pairing queue.
Sessions 2 and 4 alone, roughly two sessions of the five.

**If the answer to "why do you want this?" is "to find out what is untested", build that instead.**

### 5. The failure mode is worse than nothing

A requirement recorded as delivered on the strength of a test that does not actually prove it is
exactly what §20, §22 and §24 exist to prevent. Adoption creates a new way to produce one: an
operator clicking through a long queue accepting plausible suggestions. **The queue's design has to
assume a tired operator**, which means the confirm button must show the check's wording and the
test's actual assertion side by side, not just two names.

### When to recommend against

**If the measured pairing rate on the owner's own project comes back below about one in five, do
not build it.** At that point the queue is nearly all rejections and the report is nearly all
"nothing here shows this", and the same information is available more cheaply by reading the test
list. On this repository the rate is roughly one in three — above that line, but not by much.

---

## 10. Recommendation

**Build it, in this order, and stop after session 2 if the numbers disappoint.**

Sessions 1 and 2 — the walk and the harvest — are worth having whatever happens next. They are
mostly assembly over machinery that already works, and together they answer a question the owner
can ask today: *what does this project test, and what do its documents claim?*

**Then measure before committing to sessions 3 to 5.** With the walk and the harvest built, run
them on zeroz4j and count how many extracted requirements have a plausible test in the shortlist.
That measurement costs an hour and settles the question §9 leaves open. This document's own figure
— roughly one in three, on a codebase that flatters the feature (§1.3) — is the estimate that
measurement would replace.

**And keep the honest framing from the start.** The feature's deliverable is a list of what a
project cannot demonstrate about itself. Presented as a score it will read as a failing grade.
Presented as a to-do list with the searching already done, it is the most useful thing this product
could tell somebody about code they inherited.

---

## Appendix — how each number here was obtained

| Figure | Method |
|---|---|
| 18 documents, 601,145 characters | File walk over the checkout, excluding build output and version-control internals. No build run. |
| 543 test methods, 111 classes | Static count of `@Test` annotations. **No test suite was run.** Zero parameterised tests, so the static count predicts the reported count. |
| 3 analyst passes | Corpus size ÷ `RequirementsIntake.DEFAULT_BUDGET_CHARS` (250,000), read from the source. |
| 30–60 minutes for a harvest | The project's own stated figure for a full run. Not measured here; no suite was run. |
| 7 of 20 fully proved | Every third requirement from `REQUIREMENTS_AND_BACKLOG_DESIGN.md` §9, sampling rule fixed before looking. One reader's judgement, hunting by hand through the test sources. |
| Matcher scores over 28 true pairs | `RequirementSimilarity` copied unmodified into a throwaway probe in a scratch directory outside the repository, compiled against the pinned Lucene version, fed the pairs found by hand above. No production code written, nothing installed, the probe is not in this branch. |
| 8 wrong pairs, 0.000–0.188 | Same probe. One of the eight (an image-extraction requirement against a vision-signalling test) is arguably a true pair, which if anything understates the overlap between the two ranges. |
| Sandbox defaults | Read from `SandboxConfig.enabledOrDefault()` and `SandboxAttach`. Note that `DEVELOPER_CORRECTIONS.md` §13.3, written in July, says the sandbox is off by default; that is now out of date. |
| Session size, 5–21 files | `git show --stat` over the last seven commits on master. |

---

## 11. The pairing, re-measured — a model reading test source can do it

**Status:** MEASUREMENT, 2026-08-28, on this repository. Everything below was run. Where a figure
is an estimate it says so. Nothing above this line was changed; §1.4's numbers stand and are
correct about what they measured. This section says they measured the wrong thing.

**Everything here used the local model** (`qwen3.8-27b`, thinking off, temperature 0). **No paid
cloud API was called.** No production code was written; the probes live in a scratch directory
outside the repository (`…/scratchpad/pairing/`), and the reactor is untouched.

### 11.0 The answer

**Yes. A model reading the test source can say which existing test proves a requirement.**

On 28 pairings confirmed by hand in §1.4, given a shortlist of ten tests with their full Java
source, the local model returned a correct pointer for **18 of 28**, made **zero** confident wrong
pointers, and correctly said "none of these" every time nothing suitable was present. When the
right test was deliberately removed, it said none in **22 of 23** cases where nothing valid
remained. It errs by staying silent, never by guessing.

And the cheap matcher §1.4 dismissed is a **good shortlister**: the correct test was ranked first
for 15 of 28 requirements, inside the top ten for 25, and inside the top twenty for all 28 — out
of 536 candidates. §1.4 measured its absolute score against a threshold, which is the wrong
question. As a ranking it works.

### 11.1 Why this does not violate the evidence-only decision

This is the crux, so it is stated before any number.

- **"This code implements this requirement"** is a **verdict with no evidence behind it**. Forbidden
  by §20, §22 and §24, and nothing here proposes it.
- **"Test `X#y` is the test that checks this requirement"** is a **pointer**. The test result still
  does all the proving. A model cannot turn a failing test into a passing one, cannot make a test
  that never ran report a pass, and `CriterionEvidence` still answers **unknown, never passed**, for
  a test that did not run.

The whole residual risk is one thing: **a wrong pointer at a test that happens to be green**, so a
requirement reads as done with an evidence trail that looks legitimate. That is the §15.1 defect
wearing a new coat. Quantifying it was the point of this exercise, and §11.5 is that number.

### 11.2 The ground truth, recovered not redone

The 28 hand-confirmed pairings of §1.4 were recovered **verbatim** from the previous session's
scratch file, not re-derived. All 28 test identifiers were checked against the current checkout and
**all 28 exist**. To them were added the **four requirements §1.3 found to have no test at all**
(the disposal leak test, the pull-request overlay, "docs updated", and the superseded `/backlog`
chat mode) — because a "says none correctly" figure measured only on questions that have an answer
is meaningless.

**32 requirements in total: 28 with a known correct test, 4 with none.** No pairing was added by
hand, so this measurement inherits §1.3's sampling rule unchanged.

**One correction to §1.4 in passing.** Two of the eight deliberately-wrong pairings named test
identifiers that do not exist in the checkout (`PathPolicyTest#symlinksOutOfTheWorktreeAreRefused`
and `ConsoleBrdBrowserTest#theRequirementsScreenShowsEveryRequirement`). The 0.000–0.188 contrast
range was therefore partly scored against invented names. It does not change §1.4's conclusion, but
it should not be quoted as if all eight were real tests.

**And a correction to the test count.** §1.2 says 543 test methods in 121 files. Parsing every test
source with string literals and comments masked out first gives **536 test methods in 120 files**.
The difference is exactly 7: three `@Test` methods in the `demo-repo` fixture under
`sc-app/src/test/resources` (a fixture project, not this project's suite), and four `@Test`
annotations inside **Java source embedded in string literals** in `ScriptedModel` and
`AuthoredTestAuditTest`. A naive `grep -c @Test` counts all seven. **536 is the number of tests a
run of this project reports**, and every figure below uses it.

### 11.3 Measurement 1 — is the right test in the cheap matcher's shortlist? Yes.

Method: the product's own `RequirementSimilarity`, copied unmodified into the scratch probe and
compiled against the pinned Lucene 9.10.0, scoring each requirement's wording against **all 536
test identifiers**, camel-case split (`storiesAndIterationsSurviveReopen` → `stories And
Iterations Survive Reopen`). Full ranking, no threshold. Ties are reported both ways: *optimistic*
puts the correct test first among equal scores, *pessimistic* puts it last.

| The correct test is ranked | optimistic | pessimistic |
|---|---|---|
| **1st** of 536 | **15 of 28 (54%)** | 12 of 28 (43%) |
| in the **top 5** | **22 of 28 (79%)** | 21 of 28 (75%) |
| in the **top 10** | **25 of 28 (89%)** | 25 of 28 (89%) |
| in the **top 20** | **28 of 28 (100%)** | 26 of 28 (93%) |
| in the **top 50** | 28 of 28 (100%) | 28 of 28 (100%) |

Median rank **1**. Mean rank **3.5**. Worst rank **20**. **Not one correct test scored zero** — every
true pair shares at least one stem with its requirement.

**So the shortlist approach does not fail, and no different candidate set is needed.** A shortlist
of ten catches 89% of the answers and a shortlist of twenty catches all of them. The question §1.4
asked — *does the score clear 0.50?* — has the answer "never", and it did not matter: what a
shortlist needs is order, not calibration.

**Cost of the ranking step: 0.79 seconds** for 17,152 requirement-to-test scores, including JVM
startup. It is free.

**What §1.4 got right and should be kept.** The camel-case splitter is mandatory and was not
built — raw identifiers are one Lucene token and score near zero. And no threshold separates true
from false pairs, so the matcher must never *decide*. Both stand. Only "there is no automatic path"
falls.

### 11.4 Measurement 2 — can the model pick it out of the shortlist? Yes, and it never bluffs.

Method: for each of the 32 requirements, the model was given the requirement's wording and a
numbered shortlist of the top-ranked tests **with their full Java source** (capped at 3,000
characters each; 90% of tests are under 2,400). It was told that answering "none" is correct and
valuable, that a wrong pick is much worse than none, and that it may name only an identifier it was
shown. It replied with one JSON object naming a test or `none`, plus one sentence of reasoning.
Temperature 0, thinking mode off (`reasoning_tokens` came back 0 on every call, confirming it).

Every pick was then **read by hand against the test source**, because the recovered ground truth is
one reader's judgement and a disagreement is not automatically the model's fault.

| Shortlist of **10** (32 requirements) | count |
|---|---|
| Picked exactly the hand-found test | **15** |
| Picked a *different* test that is as good or better (adjudicated by reading the source) | **3** |
| **Picked a test that does not prove the requirement — a confident wrong pointer** | **0** |
| Said "none" when nothing suitable was in the shortlist (4 requirements with no test at all, 2 where the shortlist genuinely missed it) | **6** |
| Said "none" when a *partially* covering test was there | 8 |

**Right pointer: 18 of 28 (64%). Wrong pointer: 0 of 32 (0%). Correct "none": 6 of 6 (100%).**

A shortlist of twenty changes almost nothing: 16 exact, 3 alternatives, **0 wrong**, 5 correct
"none", 8 silent. Recall rises by one requirement; cost doubles. **Ten is the right shortlist size.**

### The three "wrong" picks are not wrong

All three, read against the source, are at least as good as the hand-chosen answer, and two are
better. This matters because it means the model's apparent error rate is an artefact of imperfect
ground truth, not of the model.

- *"Deletion is always a tombstone — no store map ever loses an entry"* — the model chose
  `BacklogStoreTest#cancelledStoriesAreTombstonesNotDeletions`, which asserts exactly that. The hand
  answer was a journal test that only touches it sideways. **The model is right and the human was
  wrong.**
- *"Criteria verified against an older content revision render as STALE"* — the model chose a test
  asserting precisely that, in a different class from the hand answer. **Two near-identical tests
  exist**; both are correct.
- *"Adding an ACCEPTED criterion to an IMPLEMENTED requirement reverts it to ACTIVE"* — the model
  chose the test that asserts the revert; the hand answer asserts the other clause (retired checks).
  Both are half of the requirement.

### The real failure mode is silence, not bluffing

Eight of 28 times the model refused where a hand-found test existed. Its stated reasons are
consistently the same argument, and it is the product's own rule:

> *"No single test proves the entire requirement; test 1 proves the 'every criterion claimed' half,
> and test 2 proves the 'none claimed from outside' half, but neither covers both."*

Every one of the eight was checked. In each, the hand-found test covers **part** of the requirement
— which is precisely §1.3's "partly proved" bucket, and under the evidence-only decision a partly
proved requirement is **not proved**. **The model is applying the product's rule correctly and the
ground truth is not.**

**A prediction, not a measurement.** The design already specifies pairing **one row per check**, not
per requirement (§4 step 4, §5 step 5). This measurement paired at requirement level only because
the recovered ground truth is at requirement level. The model's own refusal reasons name the two
tests that between them cover the requirement. **Pairing per check should convert most of those
eight silences into correct pointers.** That is the single largest improvement available and it
costs nothing extra — it is what the design already says to build. It has not been measured.

### 11.5 Measurement 4 — does it hold up when the answer is not there? Yes.

Method: the same 28 requirements, with the correct test **deleted from the candidate pool** and the
shortlist backfilled from rank 11. If the model always picks something, it is unusable.

| Shortlist of 10, correct test removed (28 requirements) | count |
|---|---|
| Said **none** | **22** |
| Picked a *different but genuinely valid* test still in the shortlist | 4 |
| Picked a *partially* relevant test | 1 |
| **Picked a test that proves nothing of the requirement — a confabulation** | **1** |

Of the **23 trials where nothing valid remained**, it said none in **22 — 96%.** The single
confabulation was *"story key minting is unique per project and stable across restarts"* answered
with a test about **project** records persisting across a store reopen — a plausible-sounding
near-miss, and exactly the shape of error to expect.

**The strongest single result in this section:** three of the four valid alternatives it picked
after the correct test was removed are the *same* tests it picked when the correct test was still
there. Its answer does not move when the shortlist does. It is not filling a slot.

**Across the two shortlist-of-ten runs: one confident wrong pointer in 60 model calls (1.7%).**

### 11.6 Measurement 3 — what it costs

Measured per requirement on the shared local endpoint, shortlist of ten:

| | measured |
|---|---|
| Prompt tokens | **3,135** mean (1,520–4,985) |
| Output tokens | **57** mean |
| Wall clock | **3.2 s** mean (1.5–4.8 s) |
| Money | **zero** — local model |

**Extrapolated to a 200-requirement project** (arithmetic on the means, labelled an estimate):

| | shortlist of 10 | shortlist of 20 |
|---|---|---|
| Prompt tokens | **627,000** | 1,240,000 |
| Output tokens | 11,400 | 13,300 |
| Wall clock on the local model | **11 minutes** | 18 minutes |

Everything else is unchanged: the ranking is under a second, and the harvest is one build-and-test
run of the adopted project (§1.2: 30–60 minutes here, paid once).

**If a stronger model were wanted, the decision to spend is the owner's.** The workload is
**0.63 M input and 0.011 M output tokens per 200 requirements**; multiply by whatever the current
list price is per million. **Nothing here was run against a paid API and no price is quoted from
memory.** On this evidence the local model does not need replacing: it produced zero confident
wrong answers in 60 calls.

### 11.7 The new question — would confirmation-by-ordinary-use actually fire?

The revised design records a model-proposed link as **provisional**, and lets it be confirmed for
free by ordinary work: the first time somebody changes that area, the test goes **red** and then
**green**, and the link is confirmed by exactly the evidence the product already demands of a new
acceptance test. **If that never happens, the mechanism is decorative.**

**What is not measurable, said plainly first.** Git records commits, not test results. Whether a
test actually went red is not in the history, so **the true confirmation rate cannot be measured
from this repository.** What follows is an **upper bound**: how often a provisional link would be
*exposed* to a change that could turn it red.

Method: 113 non-merge commits, 2026-07-08 to 2026-08-28 (7.1 weeks). For each of the 536 tests, the
commit where its file first appeared was found, and only **later** commits counted — otherwise a
7-week-old repository reports 100% because every file was created inside the window.

| After the test already existed | of 536 tests |
|---|---|
| Production code **in its own package** was changed again | **475 (88.6%)** |
| A later commit changed production code **and** that package's tests together | 437 (81.5%) |
| The test's **own file** was edited | 253 (47.2%) |
| Never exposed to any later change in its package | **61 (11.4%)** |

Median **10** exposures per test in seven weeks; mean 11.3.

**Read that carefully.** 88.6% is the ceiling, not the rate. A change in the same package is an
opportunity for a test to go red; most such changes do not break most tests. The middle row —
production code and its tests moving in the same commit, 81.5% — is closer to the real signature,
because a test edited alongside a code change is usually a test that broke. Even so it is an
over-count.

**Three conclusions.**

1. **The mechanism is not decorative on a project like this one.** Nearly nine in ten tests sit in
   an area that moves within two months, and the median test sees ten such changes. A provisional
   link would be tested by real work, repeatedly, without anybody being asked to do anything.
2. **This repository flatters it, and a mature codebase will do far worse** — 113 commits in seven
   weeks is heavy, active development on a young codebase. **This is an estimate:** an adopted
   legacy project with a quiet, stable core could leave most links provisional indefinitely, and the
   design must read as normal, not as a failure, when they stay that way.
3. **The observation is already free.** `TestResults` — the object the verification pipeline fills
   in from the runner's XML — already carries the identifier of **every test that failed** as well as
   every one that passed (§1.2). A red-then-green transition for a named test is therefore visible
   across two ordinary runs with **no new plumbing at all**. What is new is storing the previous
   run's failures and comparing.

**The 11.4% never exposed matters too.** Sixty-one tests sat in packages nothing touched again.
Those links would stay provisional forever, and the honest screen says so: *"believed covered;
nothing has changed in this area since, so nothing has tested the belief."*

### 11.8 What changes in the design

**§1.4's conclusion is superseded on one point and confirmed on the other.** The matcher cannot
*decide* — that stands, and no threshold should ever be introduced. The matcher **can rank**, well
enough that the right answer is in a ten-long shortlist 89% of the time. **"There is no automatic
path" is wrong.** There is: rank in code, choose with a model reading source, record the choice as
provisional.

**§1.3's 35% ceiling is untouched.** Nothing measured here changes how many requirements have a
test that fully proves them. It changes only how the pairing is found.

**The recommendation changes, and the shape of the work with it.**

| Session in §8 | What happens now |
|---|---|
| 1 — the walk and the passes | **Unchanged.** Still worth having on its own. |
| 2 — the harvest | **Unchanged**, plus one small addition: keep the previous run's failing identifiers so a red-then-green transition is visible. |
| 3 — the pairing queue | **Becomes the pairing *pass*, and it is smaller.** Camel-case splitter, top-ten shortlist, one constrained model call per check, the result stored as a provisional machine-assigned link. There is no queue to build. |
| 4 — the report and the conflicts | **Unchanged in size, changed in content.** Three states to render, not two: proved, believed-covered-not-confirmed, and nothing found. The "believed" state needs the copy that says what would confirm it. |
| 5 — the live shakedown | **Unchanged and still not optional.** |
| *(§8's operator-confirms screen)* | **Gone.** No confirmation gate. Confirming by hand stays available where the operator does know; it is not a step anybody must complete. |

**§9's strongest objection — "the operator has to confirm every pairing, and there may be hundreds"
— is now answered twice over.** There is no confirmation queue, and the measurement shows the
machine's picks would have been right or defensible 18 times in 28 with no wrong pointers, so a
human clicking through would mostly have been rubber-stamping correct answers and adding false
provenance to them.

**§9's line for when to recommend against — "below about one in five, do not build it" — is
comfortably cleared.** Measured pairing rate on this repository: **18 of 28, roughly two in three.**

**What does not change.** The five forbidden paths in §5 all still hold, and rule 4 (a model may
only cite an identifier it was shown) earns its place: on the very first run of this measurement the
model answered with the shortlist *number* eight times out of twenty-eight rather than the
identifier. A parser that resolved a stray string to a lookup would have been the bug. Telling it
"give the full identifier, never its list number" fixed all eight.

### Appendix to §11 — how each number here was obtained

| Figure | Method |
|---|---|
| 536 test methods, 120 files | Every `@Test` method parsed from `*/src/test/java`, with string literals and comments masked first so Java embedded in fixtures is not counted, and the `demo-repo` fixture excluded. **No test suite was run.** |
| 28 ground-truth pairs | Recovered verbatim from the §1.4 session's scratch file; all 28 identifiers verified to exist in the checkout. Not re-derived. |
| 4 requirements with no test | §1.3's own four. Not re-verified. |
| Shortlist ranks | `RequirementSimilarity` copied unmodified into a scratch probe, compiled against the pinned Lucene 9.10.0, scoring each requirement against all 536 identifiers camel-case split. Ties reported both ways. |
| Model figures | 92 calls to the local `qwen3.8-27b` endpoint, temperature 0, thinking disabled (`reasoning_tokens` 0 on every call), shortlist source capped at 3,000 characters per test. Every non-exact pick read by hand against the test source. **No paid API was called.** |
| Tokens and wall clock | The endpoint's own `usage` block and the client's own clock, per call. |
| 200-requirement extrapolation | Arithmetic on the measured means. **An estimate.** |
| Churn figures | 113 non-merge commits, `git show --name-status` per commit, package-level attribution, counting only commits after each test file first appeared. **An upper bound on confirmation, not the confirmation rate** — git does not record test results. |
| Probes | `…/scratchpad/pairing/` — outside the repository, not in this branch. Nothing was installed into the shared Maven repository and no production code was written. |
