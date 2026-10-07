# Brownfield intake — ledger

What actually landed, in order. `Design.md` beside this file is the contract; this is the record.

**Wave branch:** `brownfield`. Worker branches merge into it; `brownfield` merges to `master` only
when the whole feature is complete.

**How to read this.** Every entry says what an operator can now do that they could not do before,
what was measured, and what is still not true. An entry is written when the work is merged into
`brownfield`, not when a session claims it is finished.

---

## Branch ledger

| Branch | Wave | Merged into `brownfield`? | What it carried |
|---|---|---|---|
| `brownfield-w1-ground` | 1 | yes, as `a21aac6` | The five jsoup cases and the file that holds them; the clone and per-case worktree management under `~/.swarmcoder/targets/`; `TargetRepository` (detect, probe, correct, render, commit, run the suite); `BrownfieldLoopTest` walking links 1–3 for all five cases. |
| `brownfield-w2-understanding` | 2 | yes, as `c16f2b6` | `ChangeNeighbourhood` — what a change request is about, read out of the code it is about. `ChangeRequestIntake` — one issue becomes one agreed requirement with one to three checks, one story and one run. `BrownfieldUnderstanding`, the links 4–6 helper the harness will call. The five-case measurement with the model scripted, and a live-model reading of all five for a person to check. Wave 1 is merged into it, so the wave branch takes it as a fast-forward. |
| `brownfield-w3-end-to-end` | 3 | not yet | `HumanFixOracle` — the maintainer's own test, applied to the tree the swarm produced and run there, reporting the four lines design §4 asks for. The harness chain extended from three links to seventeen, walking one case end to end on a live model. The architect told, on a change to code that already exists, to state a contract for every type the change touches. The code a change is about reaching both the design brief and every worker's brief, sized against what the inference endpoint really serves. The nearest existing example labelled as "the file you are changing" when it is one. And a test proving, over all five real fix commits, that nothing the maintainer wrote reaches anything the swarm can see. |

**On the branch name.** The design says worker branches under `brownfield/…`. Git refuses that: the
wave branch `brownfield` already exists, and one ref cannot be both a branch and a directory of
branches. Worker branches for this feature are therefore `brownfield-<wave>-<topic>`, with a hyphen.
Nothing else about the branching plan changes.

---

## Entries

### Wave 1 — point it at jsoup and prove the ground is solid

**Built on `brownfield-w1-ground`, 2026-09-05. Not yet merged.**

**What an operator can now do that they could not before.** Point the product at a repository nobody
wrote it for — a third-party checkout at a chosen commit — and get a straight answer to the three
questions that have to be true before any run is worth starting: is this the code we meant, do we
actually know how to build it, and does its own test suite pass right now. One command answers all
three for five real jsoup issues and prints the wall clock for each.

**What was built.**

- **The five cases and the file that holds them** (`dev/brownfield/jsoup-cases.json`,
  `BrownfieldCases`). Every field re-verified against the GitHub API on 2026-09-05: the issue body,
  the `Fixes #NNNN` commit, its parent, the source and test files it touched, and the test method
  names it added. All five matched the design exactly; no reserve was needed. The issue text is
  stored rather than fetched, so the harness runs offline and a case cannot change because somebody
  edited a GitHub issue.
- **Clone and per-case worktrees** (`BrownfieldTarget`), under `~/.swarmcoder/targets/`: one clone of
  jsoup, one worktree per case at the fix commit's parent. Idempotent — an existing clone is reused
  and nothing is fetched, and a worktree sitting at the wrong commit is rebuilt rather than trusted.
  Nothing is written to `C:/work/worktrees` or inside the SwarmCoder checkout.
- **`TargetRepository`** — detect the build, run its compile command once for real, apply any stated
  correction, render the contract, write it into the repository and **commit** it, and (separately)
  run the project's own suite and time it. It adds no logic of its own and never decides: a compile
  probe that fails writes nothing, and a folder with no build file gets no guess.
- **`BrownfieldLoopTest`** — links 1 to 3, parameterised by case, `[CHAIN ok]`/`[CHAIN FAIL]` lines
  through the existing `ChainLedger`. `-Dswarmcoder.brownfield.case=2187` runs one; the default runs
  all five. It needs no model at all, so unlike the greenfield harness there is no endpoint to guard
  and no way for it to spend money.

**What was measured** (2026-09-05, JDK 25.0.2 Temurin, Maven 3.9.9, all five cases green on all
three links):

| Case | Tree cut at | Compile probe | Suite | Tests executed |
|---|---|---|---|---|
| #2197 | `d3104a03` | 14s | **19s** | 1,159 |
| #2187 | `7def06d8` | 10s | **19s** | 1,160 |
| #2266 | `8086b1e4` | 11s | **20s** | 1,241 |
| #2476 | `e640ca8a` | 15s | **50s** | 1,587 |
| #2105 | `1f1f72d1` | 10s | **21s** | 1,138 |

The first clone of jsoup took **2 seconds** (12 MB) and is paid once. **jsoup's suite is cheap** —
under a minute per case — so the design's arithmetic holds: on this target the wall clock of a run is
model turns, not builds, and the slice rule of §3.3 is not needed to make jsoup affordable.

**The one thing wave 1 found that the design did not predict, and it matters.**
**jsoup's own suite is not green on these trees out of the box.** On the untouched tree of issue
2187, six tests fail before anything is changed — five in `org.jsoup.integration.ProxyTest`, one in
`ConnectTest`. They start a local Jetty server and an HTTP proxy. Two measurements separate the
possible causes: `ProxyTest` fails there **even when it is the only test running**, so it is not
contention from surefire's parallelism; and the same class **passes on a recent jsoup master**, so it
is not this machine being unable to bind. It is the age of the tree against the JDK it is built
with, and jsoup fixed it later.

Design §5.5 half-anticipated this — it asked wave 1 to check that the local server can bind — but it
expected the answer to be yes, and it assumed jsoup's integration tests are `*IT.java` files that
`mvn test` does not run. They are not: they are `*Test` classes in an `org.jsoup.integration` package,
so surefire runs them.

**The fix, and why it is the product's own flow rather than a workaround.** `ToolchainDetector`
proposes and never decides; the console screen exists so a person can correct a proposed command
before anything depends on it. The harness has no person, so the correction is stated as data on the
target in the case file, and `TargetRepository` applies it and **writes the reason into the committed
contract's own comments**. The corrected gate is
`mvn -B test "-Dtest=!org/jsoup/integration/**"`, which leaves **1,162 tests green on the untouched
tree** and really do gate a change. Left in, those six would have failed every candidate of every
case for a reason no candidate caused. Two details worth keeping: the exclusion must be a **path**
pattern, because a dotted package name silently matches nothing; and the suite's **test count is
parsed from the JUnit reports**, not inferred from the exit code, because a run that selected nothing
exits 0 and reads exactly like a run that passed.

**What is still not true.**

- **Nothing understands a change request yet.** Links 4 onward are unbuilt: no requirement is drafted
  from an issue, no neighbourhood is read, no design, no worker, no oracle. Wave 1 proves the ground,
  not the product.
- **The maintainer's test has never been run against anything the swarm produced** — that is wave 3,
  and it is the only measurement that will say whether this works.
- **`TargetRepository` has no screen.** It is driven from the test. Wave 5 is the console.
- **Only one target.** Everything here is Maven and plain Java. The design's whole point is
  frameworks the model has not seen, and that is wave 6.
- **The chain's closing line still reads "document to merged commit"**, which is the greenfield
  harness's sentence. `ChainLedger` is shared with wave 2 and was deliberately left untouched to keep
  the merge clean; the wording is cosmetic and should be generalised once both waves are in.

**Deviation from the design, stated.** §6 lists `TargetRepository` in `sc-console`. It was built in
`sc-app` instead, because `sc-console` depends on neither `sc-verify` (the detector) nor `sc-git`
(the commit), and `DEVELOPER_CORRECTIONS.md` §29.4 records that boundary as deliberate — the console
already reaches the detector through `BuildContractBridge` in `sc-app` for exactly this reason.
Putting the class in `sc-console` would have meant adding two module dependencies to invert a
boundary somebody chose on purpose. Wave 5's screen reaches it the way the existing screen does.

### Wave 2 — the machine says what a change request means

**Built on `brownfield-w2-understanding`, 2026-09-05. Not yet merged.**

**What an operator can now do that they could not before.** Paste a bug report about a repository
nobody wrote SwarmCoder for, and read back what the machine understood *before* it builds anything:
the problem restated as an observable difference, one to three checks that say how anyone would tell
it was fixed, what it had to assume, and — the part that did not exist at all — the types in that
repository the report is about, each with the file and the line they are declared on, and the test
classes that already assert this behaviour today. Pressing on turns that into one agreed requirement,
one story and one run. Nothing is written until it is pressed.

**What was built.**

- **`ChangeNeighbourhood`** (`sc-knowledge`) — issue text plus the structural index in, a bounded
  brief out. Five queries: the types the report names, the methods it calls, where those types live
  and what their public surface is, what touches them (with the honest warning when a type is used by
  half the project), and the test files that already exercise them. Every line carries a real
  `path:line`. Capped at 6,000 characters and sized against the working context the endpoint actually
  offers, so a smaller server gets a smaller brief rather than an overrun one. It **fails open
  everywhere**: no index, an unparseable target, a report naming nothing this project has — every one
  of those gives an empty brief carrying the reason, never an exception and never an invented type.
- **`ChangeRequestIntake`** (`sc-console`) — the front half a change request never had. One model
  call. It is told, in the prompt, that it may not ask a question and must state assumptions instead;
  that it must change what the fix needs and no more; that the requirement states the difference and
  never the fix, naming no file; and that where it names a type it must name one out of the
  neighbourhood it was shown. Reading writes nothing at all. Starting writes the requirement, agrees
  it through the same code path an operator's button press goes through, mints the story with
  `BacklogAuthoring.proposeStory` — which refuses a delivery story naming no agreed check — and
  starts the run **already bound to the story**, at `RunState.INTAKE`, from which
  `GreenfieldWorkflow` goes straight to DESIGN. There is no brownfield workflow class and no new
  state.
- **`BrownfieldUnderstanding`** (`sc-app`, test) — links 4, 5 and 6 as three calls the harness makes,
  so there is one definition of what those links mean rather than a second harness beside the first.
  Link 6 (does the design state contracts naming types that exist) is implemented and unexercised:
  it needs a design, which needs a run, which is wave 3.

**What was measured** (2026-09-05, all five cases, each at its fix commit's parent):

| Case | Types named | file:line refs | Brief | Test file it points at first | Names the file the maintainer changed? | Names their test file? |
|---|---|---|---|---|---|---|
| #2197 quirks mode | 1 | 17 | 2,704 chars ≈ 676 tokens | `DataUtilTest` | no | no |
| #2187 `:has()` | 5 | 21 | 4,483 ≈ 1,120 | `DocumentTest` (`SelectorTest` third) | no | **yes** |
| #2266 charset on empty XML | 5 | 21 | 4,489 ≈ 1,122 | **`DocumentTest`** | **yes** | **yes** |
| #2476 cleaner duplicates attrs | 6 | 22 | 3,796 ≈ 949 | **`CleanerTest`** | **yes** | **yes** |
| #2105 button text spacing | 4 | 20 | 4,397 ≈ 1,099 | **`ElementTest`** | no | **yes** |

**Four of the five briefs name the exact test class the maintainer added their test to**, and three
of those name it first. Two of the five also name the source file the maintainer changed. Both facts
are reported and neither is gated on — there is more than one right place to fix most of these, and
a swarm that fixed the same bug one layer up has not failed.

**Every brief is well inside its budget.** The largest is 4,489 characters, about 1,120 tokens, which
is 2.2% of the 51,200-token working context the design measured against — under the 2.9% it budgeted.
A brownfield task's prompt is the same size as a greenfield one's; what changed is what is in it.

**And with a real model reading them** (the free local 27B, `qwen3.8-27b` on the shared box; every
one of the five, one call each, nothing paid). This is the half of wave 2's done-when that no
assertion settles, so it is quoted:

| Case | Read in | The check it drafted |
|---|---|---|
| #2187 `:has()` | 49s | *"For the HTML `<div><span>abc</span><a>def</a></div>`, `doc.select("div:has(span + a)").size()` must be 1 instead of 0."* |
| #2105 button text | 68s | *"…parsing it and extracting its plain text currently returns `"ReplyReplyToAll"`; it must return `"Reply ReplyToAll"`."* |
| #2476 cleaner | 119s | *"…`cleaner.clean(dirty).body().html()` currently returns `<a REL="external" rel="external">One</a>`; it must return `<a rel="external">One</a>`."* (and a second check for the `Safelist.basic()` case the report also raises) |
| #2266 charset | 307s | *"…calling `charset(StandardCharsets.UTF_8)` today throws `IndexOutOfBoundsException`; it must instead complete without throwing and `charset()` must return `StandardCharsets.UTF_8`."* |
| #2197 quirks mode | 539s, then 45s on a re-read | *"…must produce a document in which the `<table>` element is a descendant of the `<p>` element (not a sibling)… and no empty `<p>` element appears after the table."* |

**Each one is the report's own input and its own numbers, and none of them names a file** — which is
what the prompt asks for and what keeps the work off a guess about where the fix goes. The
assumptions are the part worth reading: on #2197 it said the report gives no expected output string
at all and stated what it inferred; on #2476 it said the report gives no input for the second case
and said what it assumed. That is decision 4a working — never ask, state the assumption — and it is
visible and correctable rather than silent.

**One thing the live run found about the harness rather than the product.** The first reading of
#2197 needed the standard one JSON retry (`LlmReplyRetry`, which every role gets) and the test was
asserting exactly one call. A retry is the same call asked again, not a question round, so the
assertion now allows two and says when the second fired — a model that needs the retry every time is
a prompt problem worth seeing rather than a failure worth stopping on. Re-read afterwards in 45
seconds on one call.

**Three things the design did not predict, all found by measuring.**

1. **A dotted expression hides its type.** The index's own `namesKnownIn` takes the last segment of a
   dotted token, so `Jsoup.parseBodyFragment(html)` yields `parseBodyFragment`, which starts
   lowercase and is dropped — and `org.jsoup.Jsoup`, which the report plainly names, is not in the
   answer. Every capitalised segment is resolved here instead. Without this, issue 2197's brief would
   have named nothing at all.
2. **A nested call hides the inner method.** `methodNamesKnownIn` splits on a character class that
   keeps brackets, so `System.out.println(found.label())` is one token whose last dotted segment is
   `label)`, matching nothing. Method-call shapes are found with a pattern here instead, and a method
   is kept only when at least one type declaring a call to it belongs to the project — otherwise
   `println` and `get` come back on every report.
3. **Counting is not nearness, and it ranks the wrong tests top.** Ranking "the tests that already
   cover this" by how many of the named types a test file uses puts a project's HTTP integration
   tests first on nearly every issue, because they import the entry point, the document type and the
   parser on their way to doing something else. Against issue 2266 that list named six connection and
   fuzzing tests and never named `DocumentTest`, which is the file the maintainer put their test in.
   Two structural facts fix it: a test class named after one of the types is that type's suite, and a
   test in the same package directory is that package's suite — nearest wins, and summing the two
   across several types is itself wrong, because it once ranked the selector-query test above the
   element test on issue 2105 by adding two right answers together.

**One shared class was touched, minimally.** `SemanticIndex` gained one additive method,
`declarationOf(type)`, returning the file and line a type is declared on. `publicShape` already ended
with that sentence under a page of members; a caller wanting only the location had to quote the whole
shape or parse the sentence back out of it. Nothing existing changed.

**What is still not true.**

- **Nothing has built anything yet.** The run this starts has never been driven past DESIGN on a
  brownfield target. Links 6 onward are wave 3.
- **The architect is not yet told to state a contract for every type the change touches**, so the
  worked-example channel — the lever the unseen-code experiment measured — is still off for a
  brownfield task. Design §2.3 and §6 put that in wave 3, and `BrownfieldUnderstanding`'s link 6
  measurement is already written and waiting for it.
- **The neighbourhood does not reach a worker yet.** It reaches the analyst; the `Librarian` channel
  and the `RunBrief` sentence are wave 3, so nothing in `Librarian` was touched.
- **Issue 2197's report has no Java in it at all** — an HTML input and an HTML output — so the only
  name its repository recognises anywhere in it is the entry point, and the brief names one type. That
  is the honest limit of reading types out of prose, and it is recorded rather than papered over.
- **There is no screen.** Everything here is driven from a test. Wave 5 is the console.

### Wave 3 — one issue, end to end, against the human's test

**Built on `brownfield-w3-end-to-end`, 2026-09-05. Not yet merged.**

**What an operator can now do that they could not before.** Take one real bug report about a
repository nobody wrote SwarmCoder for, hand it over, and get back an answer to the only question
that matters: **did the person who actually fixed this bug write a test that passes on what the
machine produced?** One command walks the whole thing — clone, build contract, green suite,
understanding, design, plan, acceptance test, workers, verification, judging, merge — and then
applies the maintainer's own test to the merged tree and runs it. Four lines come out: the machine's
own test, the human's test, the project's whole suite, and what each of them changed.

**What was built.**

- **`HumanFixOracle`** (`sc-app`, test) — the independent check. After the run has finished it cuts
  a throwaway worktree from the swarm's merged commit, restores the swarm's own acceptance tests
  from the run's tests ref (the same source verification used, so the two measurements are of the
  same file), runs them, then extracts **the test half of the maintainer's fix commit and nothing
  else**, applies it, runs that class, and finally runs the project's whole suite. It reports the
  four lines and a named outcome. A case passes only when all three are green. "The swarm's test
  passes and the maintainer's does not" is a fail for the count **and** its own named outcome in the
  report — decision 5, both halves, because that distinction is the entire reason the human's test
  is there.
- **The seventeen-link chain.** `BrownfieldLoopTest` now has two tests. The wave 1 one still walks
  the first three links for all five cases with no model at all. The new one walks all seventeen for
  one case against a live model, defaulting to the easiest of the five and refusing to run more than
  one at a time because the workers share one graphics card.
- **The architect is told, on a change to code that already exists, to state a contract for every
  type the change touches** — the ones that already exist as much as the ones it is creating,
  marked as which. This is the sentence design §2.3 turns on. Left out, the architect names only
  types it invents; on a change to a decade-old file that is none of them, the design comes back
  with no contracts, and the code that shows a worker how this project does things is switched off
  for every task. The greenfield prompt is unchanged to the character, which is asserted by
  measuring the two prompts against each other.
- **The code a change is about now reaches the work.** It was already reaching the analyst (wave 2);
  it now also reaches the architect's brief and every worker's brief, under its own heading, with
  the sentence that turns "these test classes already cover this area" into an instruction. Sized
  against what the inference endpoint really serves rather than a flat number — measured on the
  local box, 5,939 characters of a 51,200-token working context.
- **The nearest example says whether it is yours to change.** On a brownfield task the nearest
  existing example very often IS the file being changed, and that is right — but "copy this shape
  into your own files" and "this is the file you are about to edit" are opposite instructions, and
  giving the first about the second teaches a worker to write a parallel copy of the code it was
  meant to modify. The brief now says which, out of the task's own write set.
- **And that file can now be chosen at all.** Two gates were shut and both had to open before any
  of the above could reach a worker: a repository with one build file at its root had no candidate
  project to choose from, and a file that IS the contracted type was scored zero. Both are recorded
  below with the run that measured them; both fixes are narrow, and a greenfield build takes exactly
  the path it always took.
- **`TheFixIsNeverShownToTheSwarmTest`** — the isolation proof, over all five real fix commits, with
  the model scripted so it costs nothing and runs in an ordinary build. It reduces each commit to
  *what the maintainer wrote that nobody has given the swarm*, then looks for every one of those
  lines in the tree the workers get, the change request, the neighbourhood, every prompt actually
  sent, and the run's goal — and separately proves the oracle's own patch touches the maintainer's
  test files and carries no line of their fix.

**The one thing wave 3 found that the design did not predict, and it is a real product limit.**
**SwarmCoder cannot be pointed at a checkout that is itself a git worktree**, and the way it fails
names the wrong culprit.

Design §5.2 says each case gets "a git worktree of that clone", and wave 1 built exactly that. It is
fine for links 1 to 3, which only read. The first live run of the whole chain parked at the
acceptance-test stage with: *"the test author claims test file(s) the run's tests commit does not
hold … This is a defect in SwarmCoder's own TEST_AUTHORING stage."* The test author had done its job
perfectly. It had written the file, and the run had committed it.

What actually happened: **the library SwarmCoder reads git with cannot resolve a branch inside a
linked worktree.** Such a worktree keeps its own current position in a private directory and its
branches in the shared one it points at. The library follows the pointer far enough to answer "which
branch am I on" and then looks for that branch in the private directory, where it is not — measured
directly on a two-commit repository: asking for the branch name works, asking what it points at
returns nothing. Every git question SwarmCoder asks goes through that one call, so on a worktree
every answer comes back empty, each swallowed and logged as one warning nobody reads. The run
therefore recorded its acceptance tests as committed **at an unknown position**, the step that copies
those tests onto each candidate's tree found nothing to copy, and the check that requires them to
fail first stopped the run and blamed the stage that had worked.

**Two consequences, and only the first is wave 3's to act on.**

1. **The harness's case trees are now clones rather than worktrees.** A clone is what the product is
   actually built for, so the harness measures the product rather than an unrelated gap in it. It
   costs almost nothing: jsoup is 12 MB and two seconds, and a local clone shares its objects with
   the one it came from, so all five case trees are a few seconds and hardly any disk.
2. **Making the product itself work on a worktree is a separate piece of work.** It means giving the
   git library a branch database rooted in the shared directory while the working position stays
   private, which its builder has no way to express. That is its own design, not a line in a
   brownfield wave — and it is worth doing, because keeping several branches of one repository
   checked out at once is an ordinary way to work, and anyone who does it gets a run that parks
   blaming a stage that is working. It is recorded here and at length in the harness's own code so
   the next person meets the explanation rather than the symptom.

**The second thing wave 3 found, and it was the whole lever being off.** **On a repository whose
only build file is at its root — which jsoup is, and which is the commonest shape of target there
is — no worked example could ever be offered, whatever the design said.**

Choosing the nearest existing code happens in two passes: score every file against every contract,
then pick one *project* to copy so the worker reads one coherent idiom rather than four. A project,
for that second pass, is any folder above a file that declares a build. The code that lists those
folders walked upward and **stopped before the repository root**, which is right for a folder of
example projects — a gallery of twelve unrelated demos is not one project to copy — and wrong for a
repository that has exactly one build file, at the root. Every jsoup file belonged to no project, so
the second pass had nothing to choose from and chose nothing.

The live run printed the two lines side by side, and they are worth quoting because neither of them
looks like a fault: *"structural evidence from the semantic index for 4 of 4 contract(s)"* and then
*"worked example: null (value 0.0, of **0** candidate projects)"*. The architect's new instruction
had worked — four contracts, all naming real jsoup types, all with evidence. There was simply
nowhere for a file to belong.

Greenfield never met it: the demo project is multi-module and so is the reference checkout beside
it, so every file always had at least one ancestor carrying a build file. **The fix is that the root
counts as the project when nothing below it declares a build** — added only in that case, so a
multi-module repository and a folder of examples both behave exactly as they did and nowhere that
was already choosing a project can start choosing a different one.

**Behind that one there was a second gate, and it is the more interesting of the two.** With the
first open, the chooser still would not offer the file the change is about — because **a file that
IS the type the design contracted was scored zero**, deliberately. On a build from nothing that rule
is right: a file already carrying the name of a type you are about to create is a leftover or a
coincidence, and showing it as "the nearest existing implementation of what you must build" is
circular. On a **change** it is exactly backwards. Design §2.3 says so in as many words: *"a worker
reading the current `Evaluator.java` before changing it is right."*

What tells the two apart is the design's own word for it. The architect's new instruction already
asks it to begin an existing type's description with `EXISTING:` and a new one's with `NEW:`, and
that marker is the one fact the chooser cannot work out for itself — the file is on disk either way,
and only the design knows whether the task is about to write it or to change it. An unmarked
contract behaves precisely as every contract behaved before, so a greenfield build is untouched and
a design that ignores the instruction loses nothing it had.

These two matter more than their size. The channel they switch back on is the one the unseen-code
experiment measured as the difference between **467 turns with zero files written** and **green in
34** — and it was off for every single-module target the product has ever been pointed at, and off
for every change to a type that already exists.

**What the isolation proof found while it was being written, and it changed the check.** Two of the
three filters in it exist because the naive version was wrong on a real case.

1. **A fix that MOVES code re-adds lines the tree already had.** Issue 2187's fix is a nine-line
   addition and a ten-line deletion inside one method — a rearrangement. All seven of its
   distinctive added source lines already exist verbatim elsewhere in jsoup *before* the fix. A
   check that searched for "lines the commit added" would go red on a case where nothing whatever
   had leaked.
2. **The maintainer writes their test from the same report the swarm gets.** Issue 2187's test opens
   with the exact HTML string in the issue body. The swarm was handed that deliberately; it is the
   reproduction. What it was not handed is everything else.

   With both filters the five cases contribute 8 to 14 searchable lines each, and issue 2187
   contributes zero from its fix and ten from its test — which the report says out loud rather than
   quietly passing on an empty set.

**Why #2266 is the case the walk defaults to.** Setting `charset` on an empty XML document throws.
Every wave 1 and 2 measurement makes it the easiest: the report carries three lines of Java and a
full stack trace that names the method, so the work does not begin with a search; wave 2 measured
that the machine's reading of it names both the file the maintainer changed and their test file, and
ranks that test class first; the fix is two changed lines in one method; there is one maintainer
test method to satisfy rather than three; and its suite is 20 seconds rather than 50. Any of the
five can be named instead.

**What was measured on the live run** — 2026-09-05, the free local 27B (`qwen3.8-27b` on the shared
box), nothing paid:

RESULTS_PLACEHOLDER

**One shared thing was touched, and it was widened rather than changed.** Thirteen members of the
greenfield harness went from private to package-private, because the brownfield harness walks the
same eight middle links against a third-party repository and now shares them: the refusal to talk to
anything but a free local endpoint, the run-outcome record and its waiting loop, the prompt-recording
client, the budget-stamping architect, the pinned run starter, and the small readers over the store
and the repository. Copying them would have given this repository two definitions of "the harness
refuses a billed endpoint" and two of "how a run is started", and the second copy of each is the one
that drifts. No behaviour changed and nothing outside the package can see any of them.

**What is still not true.**

- **Only one case has been run end to end.** Wave 4 runs all five in one command and compares them.
  One case is a result, not a rate.
- **The whole suite still runs per candidate.** That is deliberate (decision 3): wave 3 pays the
  full cost so a failing case can never be blamed on a test selection that narrowed too far. The
  slice is wave 4.
- **The swarm's reproduction still lives in the protected `swarm/accept` tree**, not beside the
  tests it belongs with. Decision 2 keeps it there for now; moving it at merge is a later step.
- **There is no screen.** Everything here is still driven from a test. Wave 5 is the console.
- **One target, one language, one build tool.** Everything measured so far is plain Java and Maven,
  and the design's whole point is frameworks the model has not seen. That is wave 6.
- **The product still cannot be pointed at a checkout that is a git worktree.** The harness now
  avoids that shape rather than the product coping with it, and anyone who points a run at one gets
  a run that parks blaming a stage that is working. The evidence is above and in the harness's own
  code; the fix is its own piece of work.
- **`RunKindsTest` in `sc-workflow` was already failing before this branch** — two of its five tests
  time out with the run stuck at PLAN. Verified against the wave branch itself, so it is not
  something this work caused, and it is not something this work fixed either.

### Wave 4 — all five, and the slice

*Not started.*

### Wave 5 — the console

*Not started.*

### Wave 6 — a second target, on a framework the model does not know

*Not started.*

---

## Case results

One row per harness run, so a regression in the swarm's ability shows up as a row, not as an
argument. `human test` is the maintainer's own test from the fix commit, applied to the swarm's tree
and run there; a case passes only when all three of the swarm's test, the human's test and the whole
suite are green.

| Date | Case | Swarm test | Human test | Whole suite | Files touched (swarm / human) | Wall clock |
|---|---|---|---|---|---|---|
| | | | | | | |

---

## Decisions taken after the design was written

Anything that contradicts `Design.md`, with the date and the reason. A design document that quietly
stops being true is worse than no design document.

| Date | What changed | Why |
|---|---|---|
| 2026-09-05 | Design §9: all six open decisions taken as recommended | Owner: "you decisions 1 to 6" |
| 2026-09-05 | The regression gate on jsoup excludes `org.jsoup.integration.**` | Measured in wave 1: six of those tests are red on the untouched pre-change trees, `ProxyTest` fails there even when run alone, and the same class passes on a recent jsoup master — so they are red because of the age of the tree against JDK 25, not because of anything a candidate could do. Left in, they would fail every candidate of every case. The exclusion is stated on the target in the case file and its reason is written into the committed contract. Design §5.5 assumed `mvn test` was a green gate and that jsoup's integration tests were `*IT.java`; both are wrong — they are `*Test` classes in an `integration` package. |
| 2026-09-05 | `TargetRepository` is in `sc-app`, not `sc-console` as Design §6 lists it | `sc-console` depends on neither `sc-verify` nor `sc-git`, and `DEVELOPER_CORRECTIONS.md` §29.4 records that boundary as deliberate — the console already reaches the detector through `BuildContractBridge` in `sc-app`. Moving one class there would have meant adding two module dependencies to invert a boundary somebody chose on purpose. |
| 2026-09-05 | Worker branches are `brownfield-<wave>-<topic>`, not `brownfield/<wave>-<topic>` | Git cannot hold a branch named `brownfield` and a directory of branches under `brownfield/` at the same time, and the wave branch already exists. |
| 2026-09-05 | `ChangeRequestIntake` is two plain calls — read, then start — rather than a guided-flow wizard with its own thread and proposal rows, as Design §1.5 describes it | The property §1.5 is really asking for is "nothing reaches the requirements or the backlog until somebody says go", and two calls give exactly that: reading makes the model call and writes nothing at all, starting writes the requirement, the checks, the story and the run. The wizard's thread, progress steps and proposal rows exist to keep a browser responsive while an analyst reads a stack of documents for minutes; a change request is one call on one pasted issue. Building the wizard shell now would be speculative machinery for a screen that does not exist until wave 5, and wave 5 can wrap these two calls in whatever the screen needs. |
| 2026-09-05 | `SemanticIndex` gained one additive method, `declarationOf(type)` | The neighbourhood needs the file and line a type is declared on for every type it names. `publicShape` already ends with that sentence, under a page of members, so the only alternatives were to quote a whole type's surface for every name in the brief or to parse that sentence back out of it. Nothing existing changed. |
| 2026-09-05 | The neighbourhood does its own type and method extraction rather than relying on `SemanticIndex.namesKnownIn`/`methodNamesKnownIn` alone | Measured against the five real reports: the index's extraction drops the type out of `Jsoup.parseBodyFragment(html)` (it takes the last dotted segment, which is lowercase) and drops the method out of `System.out.println(found.label())` (its token split keeps brackets, so the last segment is `label)`). Both are fine for the question those methods were written for — a worker's typed question — and both lose the thing a pasted reproduction is made of. The index's own methods are unchanged; the extra passes live in `ChangeNeighbourhood`. |
| 2026-09-05 | Whether a run is a change to code that already exists is decided by the run's KIND, not by anything about the project | Design §6 says the architect gains one instruction "for a change against existing code" without saying how the code knows. The run's kind is the honest answer: BUGFIX and ENHANCEMENT are the two kinds a change request becomes (§1.2), and a bugfix against the demo project is as much a change to code that already exists as a bugfix against jsoup. It needs no new field on the project, no new state, and no string-sniffing of a prompt. GREENFIELD and REFACTOR runs are untouched. |
| 2026-09-05 | The neighbourhood is read inside `Librarian` from the run's goal, rather than carried on the `Run` or the `Task` | Design §6 asks for it in three prompts. Both things it needs were already in one place: the change request's own words (the run's goal, which for a change request is the report verbatim) and the structural index the curator already builds and caches over the project root. Carrying it instead would have meant a new persisted field, a migration for stores written by older builds, and a second place for the two copies to disagree. It is asked for once per design or plan attempt; a warm index answers in a fraction of a second. |
| 2026-09-05 | Its size is a setter on the workflow (`setNeighbourhoodChars`), defaulting to the design's flat ceiling | `ChangeNeighbourhood.forWorkingContext` needs the working context the endpoint really serves, and nothing between the workflow and the Librarian knows it — that number is discovered at startup, in a different module. A setter lets the caller that HAS measured its endpoint state the size (the harness does, and reported 5,939 characters of a 51,200-token window) while everybody who has not gets the ceiling design §2.2 measured as affordable, rather than being asked to guess. |
| 2026-09-05 | Thirteen members of the greenfield harness went from private to package-private | The brownfield harness now walks the same eight middle links against a third-party repository: the free-endpoint refusal, the run-outcome record and its waiting loop, the prompt-recording client, the budget-stamping architect, the pinned run starter, and the small readers over the store and the repository. Copying them would give this repository two definitions of "the harness refuses a billed endpoint" and two of "how a run is started", and the second copy of each is the one that drifts. No behaviour changed; nothing outside the package sees them. |
| 2026-09-05 | The isolation proof searches for "what the maintainer wrote that nobody has given the swarm", not for "the lines the fix added" | The naive version was measurably wrong on two of the five cases. Issue 2187's fix is a rearrangement of one method: all seven of its distinctive added source lines already exist verbatim elsewhere in jsoup before the fix, so searching for them would go red on a case where nothing had leaked. And the maintainer writes their test from the same report the swarm is given — 2187's test opens with the exact HTML string in the issue body — which the swarm was handed on purpose. Both are filtered out, and each case still contributes 8 to 14 searchable lines. |
| 2026-09-05 | The harness's case trees are clones of the clone, not linked git worktrees as Design §5.2 says | The library SwarmCoder reads git with cannot resolve a branch inside a linked worktree — measured directly: asking which branch you are on works, asking what that branch points at returns nothing. Every git question the product asks goes through one call that fails there, each swallowed and logged as a warning. The first live run of the whole chain parked at the acceptance-test stage saying the test author had written no file; it had, and the run had committed it, but the position of that commit came back empty so the step that copies the tests onto each candidate's tree found nothing. A clone is the shape the product is built for, and it costs two seconds and 12 MB, shared with the clone it came from. Making the product work on a worktree is a separate piece of work and is worth doing; it is recorded rather than bodged here. |
| 2026-09-05 | A repository's root counts as "the project" for choosing the nearest worked example, when nothing below it declares a build | Measured on jsoup, whose only pom.xml is at the root: the code that lists candidate projects walked upward from each file and stopped before the root, so every file belonged to no project, the pass that picks one to copy had nothing to pick from, and no worked example could ever be offered whatever the contracts said. The run's own log: "structural evidence from the semantic index for 4 of 4 contract(s)" immediately followed by "worked example: null (value 0.0, of 0 candidate projects)". Excluding the root is right for a folder of unrelated example projects and wrong for a single-module repository, which is the commonest shape of brownfield target. The root is added only when nothing below it declared a build, so multi-module repositories and example folders are untouched. The channel this restores is the one measured as the difference between 467 turns with zero files written and green in 34. |
| 2026-09-05 | A file that IS the type a contract names is offered as the nearest example when the design marked that type as already existing | The chooser scored such a file zero on purpose, and on a build from nothing that is right — a file already carrying the name of a type you are about to create is a leftover or a coincidence, and showing it as "the nearest existing implementation of what you must build" is circular. On a change it is backwards, and Design §2.3 says so outright: "a worker reading the current Evaluator.java before changing it is right." What tells the two apart is the design's own word: the architect is asked to begin an existing type's description with EXISTING: and a new one's with NEW:, which is the one fact the chooser cannot work out for itself, since the file is on disk either way. An unmarked contract behaves exactly as before, so greenfield is untouched. |

**Session stop, 2026-09-05 ~14:00.** The wave 3 worker's one live `BrownfieldLoopTest` run on case #2266
was still in its repair wave when the owner stopped the session ("this will be the last run, stop after
this"). Its four oracle lines were therefore never recorded here; the case-results table has no row for it.
To resume: run `BrownfieldLoopTest` for case 2266 once (see Design §5) and fill the row, whatever it says.
The git-worktree product defect wave 3 found (the product cannot be pointed at a git worktree: every git
answer is empty) is open and belongs to wave 4.
