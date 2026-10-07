# SwarmCoder — Brownfield intake

**Status:** DESIGN. Nothing here is built. Wave branch `brownfield`; the ledger beside this file
records what actually lands.

**The change.** Today SwarmCoder takes two requirement documents and builds a demo project from
nothing (`dev/bookshelf-requirements.md`, `dev/bookshelf-tech-requirements.md`, into
`dev/bookshelf-demo`). This design takes an **existing repository and one change request** — a bug
report or an enhancement request, as issue text — and delivers the change, with **the project's own
test suite as the gate**.

**Why now, in the owner's words:** *"the product is aimed at understanding and writing corporate
code bases, not javascript snake games. so the workers have to be able to work on unseen code and
apis."*

**First target: jsoup** (`github.com/jhy/jsoup`). Plain Java, one Maven module, about 30k lines,
fast tests, and hundreds of closed issues each with a linked fix commit that adds a test. It is
chosen because the **oracle is free**: the maintainer's own test is the independent check on the
swarm's work. Later targets are mid-size projects on frameworks the model has never seen, so
nothing in this design may assume plain Java or Maven.

---

## 0. The answer, before the detail

**Most of this already exists.** The delivery half of the pipeline — architect, design review,
planner, acceptance-test author, red-check, waves of workers, per-candidate verification, judge,
selection, merge audit, integration — is target-agnostic already, and `WorkflowKind.BUGFIX` and
`WorkflowKind.ENHANCEMENT` already run through the one delivery path (`GreenfieldWorkflow`) with a
different string of instructions (`RunBrief.forKind`). `docs/DEVELOPER_CORRECTIONS.md` §22 settled
that deliberately: *"Building `REPRODUCE` and `CHARACTERIZE` as real stages would have duplicated
the front half of the pipeline for no behaviour the pipeline does not already have."* Those two
enum constants are still in `RunState` and nothing walks through them.

**The on-ramp for an unknown repository also already exists and is tested.**
`ExistingProjectOnrampTest` points the product at a repository it has never seen, has
`ToolchainDetector` propose a build-and-test contract, has `ContractProbe` actually run the proposed
compile command once, writes `.swarmcoder/verify.yaml` into the operator's tree, mints an ad-hoc
work item with `AdHocStory.start`, and drives a run to `DELIVERED` with verified winners.

**Three things are genuinely missing.**

1. **A change request has no front half.** The only two routes in are (a) documents through
   `RequirementsIntake` → `BacklogPlanning`, which needs a requirements document nobody writes for a
   bug report, and (b) `AdHocStory`, which produces a story with **no criteria at all** — so nothing
   is agreed, nothing is evidenced, and `CriterionEvidence` has nothing to answer against.
2. **Nobody reads the neighbourhood of the change.** `SemanticIndex` can answer "what implements
   this", "what calls this", "which files use all of these" — and no stage asks it about the types a
   change request names. The architect designs from the goal string alone.
3. **The regression gate is all-or-nothing.** The `existing` stage runs whatever commands
   `verify.yaml` declares, per candidate, after the acceptance stage passes
   (`CommandPipelineVerifier`, lines ~137-139). On jsoup that is the whole suite for every candidate
   of every task.

Everything else in this document is wiring, prompts, and a harness.

---

## 1. Intake

### 1.1 What a project is today, and what has to change

`Project` (`sc-domain`) is a name, a **primary path** (the repository changes are made in), and
**context paths** (read-only folders the agents may read). That is already exactly what a brownfield
project needs — the primary path is the target checkout. Nothing in `Project` needs a new field for
"existing codebase"; what differs is only what the operator is shown and what they are asked for.

**Registering a target repository** is three steps, two of which are built:

| Step | Class | Exists? |
|---|---|---|
| Point at a path (or clone a URL to a path first) and record it as `primaryPath` | `Project`, `ProjectContext` | yes |
| Work out how to build and test it | `ToolchainDetector.detect` → `Detection` with evidence and warnings | yes |
| Prove the proposed command really builds it, once, before anyone trusts it | `ContractProbe.probeCompile` | yes |
| Write `.swarmcoder/verify.yaml` into the **operator's** tree and commit it | `ToolchainDetector.render`, `VerifySpecLoader.SPEC_PATH` | yes (done by hand in `ExistingProjectOnrampTest`; **no product screen does it**) |
| Read the module and source-root layout | `BuildLayout.read` | yes |
| Read what the build declares | `ManifestParser.parse` (Maven `pom.xml`, npm `package.json`) | yes, **Maven and npm only** |

**New work here is one screen and one service call.** `ToolchainDetector` proposes; it never
decides, and its own class doc says so. The screen shows the detected toolchain, the evidence lines,
the warnings, and the rendered YAML, lets the operator edit it, runs `ContractProbe` on the compile
command, and commits the file. Section 7 has the copy.

**Cloning is not the product's job.** For a URL the operator gets a clone into a directory of their
choosing; the harness clones to `~/.swarmcoder/targets/<name>` (section 5). `C:/work/worktrees/` is
for our own worktrees and nothing else goes there.

### 1.2 What a change request is

A **change request** is: a title, the issue text verbatim, an optional acceptance sentence the
operator adds, and the project. Nothing else. It is not a document upload and it is not a
requirements interview.

It becomes, in one step:

- **one requirement** in the project's BRD, `RequirementStatus.AGREED`, whose statement is the
  observable difference the issue describes;
- **one to three checks** on that requirement (`AcceptanceCriterion`), each naming the test that
  will prove it;
- **one story** (`Story`) delivering exactly those checks;
- **one run**, kind `BUGFIX` or `ENHANCEMENT`, bound to that story from the instant the engine gets
  it — the ordering `AdHocStory.start` documents at length and exists to enforce.

**Why a real requirement and not an ad-hoc story.** `AdHocStory` produces an `ENABLER` with
`StoryOrigin.AD_HOC` and **no criteria**, so `StoryScope.resolve` returns an empty scope. An empty
scope works — `ExistingProjectOnrampTest` proves it — but it turns off everything that makes a
delivery mean something: `TaskGraphValidator`'s coverage invariant has nothing to cover,
`CriterionEvidence` has nothing to answer, `Verdicts`' rule that an acceptance stage executing zero
tests is a failure does not fire (it fires only "when a task answers to requirement-checks"), and
`recordDelivery` has no criterion to stamp. On a harness whose entire purpose is comparing the
swarm's work against a human's, an ungated path is the wrong path. **One requirement, one story,
agreed at creation.**

**Who agrees it.** The operator, in the console, on the screen that creates the request — they see
the checks the analyst drafted and press one button. In the harness the promotion is programmatic,
exactly as `EndToEndLoopTest` already promotes one requirement out of everything the analyst
proposed.

### 1.3 What replaces the front half, stage by stage

| Today (greenfield) | Reads | Produces | Brownfield |
|---|---|---|---|
| **Document ingest** (`DocumentIngest`, `DocumentExtractor`) | uploaded `.md`/`.pdf`/`.docx` | `SourceDocument` text | **Skipped.** The issue text is typed or pasted; there is no file. |
| **Rules** — a technical document becomes project guidelines (`ProjectRules`, `GuidelineFolderImport`, rendered into `StoryScope.projectRules`) | technical requirements document | ACTIVE guidelines shown to architect, test author, every worker | **Derived from the codebase, not written.** See §1.4. |
| **`RequirementsIntake`** (2,205 lines) — analyst reads documents, asks up to 12 clarifying questions, drafts requirements each with checks and a proposed test name | `SourceDocument`s under a 250,000-char budget (`DEFAULT_BUDGET_CHARS`) | `FlowProposal`s the operator applies | **Replaced by `ChangeRequestIntake`** — same shape, one input, no question round. See §1.5. |
| **`BacklogPlanning`** (1,433 lines) — planner reads agreed requirements and the backlog, asks up to 8 questions, proposes stories | the BRD | proposed `Story` objects through `BacklogAuthoring.proposeStory` | **Skipped.** One request is one story. There is nothing to sequence. |
| **DESIGN** — `ArchitectClient.design(brief, scope)` | `RunBrief.forKind` + `StoryScope` (requirements, criteria, gating NFRs, project rules) | `DesignDocument` with decisions, `ApiContract`s, risks | **Kept, with the neighbourhood added to the brief.** See §2. |
| **DESIGN_REVIEW** — `DesignReviewerClient.review` and `.reviewDesign(rules, design)` | the design | objections; up to `MAX_DESIGN_RULE_REVISIONS` (2) revisions against rule conflicts | **Kept unchanged.** |
| **PLAN** — `ArchitectClient.plan`, up to `MAX_PLAN_ATTEMPTS` (3) real attempts, then `TaskGraphValidator`, `BuildFilesInTheJob.expandWriteSets`, `DesignReviewerClient.reviewPlan` | design + scope + repo layout | `TaskGraph` with write sets and criteria | **Kept unchanged.** One request usually plans one task. |
| **TEST_AUTHORING** — `TestAuthorClient.authorTests` into `AcceptanceTestLocation.WRITE_SUBDIR`, then `RedChecker` | task + design + criteria | acceptance tests committed on `swarm/tests/<runId>` | **Kept.** The reproduction goes in the protected `swarm/accept` tree, **not** in the project's own test tree. See §3.2. |
| **EXECUTING → FINAL_INTEGRATION** | | | **Kept entirely unchanged.** |

**The fork point is `RunState.INTAKE`.** Every state from `DESIGN` onward is shared byte-for-byte
with greenfield. That is not a convenience; §22 of `DEVELOPER_CORRECTIONS.md` already paid for the
alternative once, with four workflow classes that renamed a state and reported delivery.

### 1.4 Rules derived from the codebase, not from a document

Greenfield gets its "how this project must be built" from a technical requirements document. A
brownfield target has no such document and its README is a pitch (`Librarian`'s own measurement:
the head of a README is *"a technical thesis"*, cut from the brief for being worthless).

Four things stand in, all read from the repository, none of them a model's opinion:

1. **The build's declared dependencies** — `ManifestParser.parse(repoRoot)` already runs on every
   brief and renders *"Available libraries (exact versions — use these APIs, do not invent)"*.
2. **The module and source-root layout** — `BuildLayout.read`, which is what
   `BuildReachabilityCheck` uses to fail a candidate whose code the build never compiles.
3. **The project's own conventions, as code** — the nearest worked example (§2.3). This is the one
   that matters and it is not prose.
4. **The verification contract** — `.swarmcoder/verify.yaml`, which states the real build and test
   commands. `RulesVersusManifest` keeps its job: if a stated rule names an artifact no module
   declares, the run says so rather than letting every worker rediscover it.

**Two things a person still states, and only two**, on the project screen, free text, both optional:
*"anything a change here must not break"* and *"anything in this repository that is off limits"*.
The second is `protectedPaths`, already enforced twice — by `PathPolicy` at the worker's tools and
again by `FinalIntegrator` on the winning diff.

**No model is asked to summarise the codebase into rules.** A distilled "framework primer" exists
(`KnowledgeCurator.primer`) and is explicitly skipped for the project root: *"primers are for
reference frameworks, not the work repo"*. That stays true.

### 1.5 `ChangeRequestIntake` — the one new front-half class

A third sibling of `RequirementsIntake` and `BacklogPlanning` in `sc-console`, built to the same
shape (its own thread, reporting through `GuidedFlows`, proposals reviewed before anything is
written), and much smaller because it does less.

**Input:** the issue text, the project, the neighbourhood brief (§2.2).

**One model call**, no question round. `RequirementsIntake`'s class doc already argues the case for
one round rather than an interrogation; for a change request even one round is wrong, because the
issue text is fixed and the person who wrote it is not in the room. Anything the analyst cannot
settle becomes a **stated assumption on the requirement**, which is visible and correctable — the
mechanism `RequirementsIntake` already uses for the same situation.

**Output**, as `FlowProposal`s the operator applies:

- one requirement: title, statement phrased as an observable difference ("`Element.text()` returns
  X; it should return Y"), priority, and the assumptions;
- one to three checks, each with the wording of the check and the id of the test that will prove it
  (`pkg.Class#method`) — the same `Check(text, test)` shape `RequirementsIntake` already drafts;
- the story, minted through `BacklogAuthoring.proposeStory`, which already **refuses a delivery
  story that names no real criterion**.

**What it must not do.** It must not invent behaviour the issue does not ask for, and it must not
widen scope. `RunBrief.forKind(BUGFIX, …)` already says *"Change what the fix needs and no more"* to
the design roles; the same sentence belongs in this prompt, because scope creep at intake is not
recoverable downstream.

---

## 2. Understanding before planning

This is the part with no equivalent today, and it is the part the product is for.

### 2.1 What is already there and what it covers

`SemanticIndex` parses every knowledge root with OpenRewrite into a type-attributed tree and answers
structural questions: `resolve`, `implementationsOf`, `usagesOf`, `filesUsingAll`, `typesUsedIn`,
`howCommon`, `idiomOf`, `callChain` (to `MAX_CHAIN_DEPTH` 6), `publicShape`, `dependencyDeclaring`,
and — directly useful here — `namesKnownIn(text)` and `methodNamesKnownIn(text)`, which pull out the
type and method names a piece of prose mentions **that the index actually knows**.

**Confirmed: it covers the target project itself, not only reference folders.** `Librarian`'s
constructor adds `new KnowledgeCurator.Root("project", projectRoot)` **first**, before every context
root, and `KnowledgeCurator.semanticIndex()` builds `SemanticIndex.over(roots, …)` across all of
them. `WorkedExamples` scores over `curator.shapes()`, which is every Java file under every root
including the project. So on a brownfield target with no reference folders at all, the index and the
worked-example chooser are both entirely about the target — which is the correct behaviour and needs
no change.

**It fails open, by design.** An unparseable language, a missing classpath entry, a root too big:
`available()` goes false, every query returns nothing, and callers fall back to the text index
(`ReferenceIndex`, BM25 over outlines and document sections). **Nothing in a brownfield run may
block on the semantic index being available**, because the later targets are on frameworks whose
build OpenRewrite may not manage.

### 2.2 The neighbourhood of the change — one new class

`ChangeNeighbourhood` (new, `sc-knowledge`). Input: the issue text and the semantic index. Output: a
bounded, quotable brief. Every line carries a file and a line number, because
`SemanticIndex.Ref.where()` already renders `path/File.java:42`.

Built in five queries, in this order, each capped:

1. **The types the issue names** — `namesKnownIn(issueText)`, then `resolve` for the
   fully-qualified names. A type the index does not know is dropped silently; naming it would be a
   guess.
2. **The methods the issue names** — `methodNamesKnownIn(issueText)`. Deliberately narrow already:
   a word counts only when written with parentheses or interior capitals.
3. **Where each named type lives, and its public surface** — `publicShape(type)`, capped to the
   two or three most-named types.
4. **What touches them** — `usagesOf` and `implementationsOf`, capped at the index's own
   `MAX_RESULTS` (40) and trimmed to the ten nearest by root and path. Plus `howCommon(type)`,
   which is the honest warning that a change to a type used in 400 places is not a small change.
5. **The tests that already cover them** — `filesUsingAll(types)` filtered to paths under a test
   source root (`BuildLayout` knows which those are). This is the single most valuable line in the
   brief: it tells the architect and, later, the worker where the project asserts this behaviour
   today.

**Where it is used.** Three places, the same text each time:

- in `ChangeRequestIntake`'s prompt, so the drafted checks name real types rather than invented
  ones;
- in `ArchitectClient.design`'s brief, appended to `RunBrief.forKind` output, so the design's
  `ApiContract`s name types that exist;
- in the worker's `KnowledgeBrief`, through `Librarian.assembleBrief`, under its own heading.

**Size.** Capped at **6,000 characters** (~1,500 tokens). That is affordable because on a
brownfield project with no reference folders the two documentation channels are near-empty:
`Librarian`'s `DOC_MAP_CHARS` (3,000) and `DOC_SLICE_CHARS` (3,500) both come from
`KnowledgeCurator.documentationMap`/`relevantDocs` over reference roots, and jsoup has almost no
`docs/` tree. The neighbourhood takes the space the map and slice do not use, and the whole brief
stays under `MAX_BRIEF_CHARS` (18,000) as it does today.

**Measured budget for a brownfield task**, against the derived working context of **51,200 tokens**
(`ServerCapabilities.derivedWorkingContextTokens()` — the KV-cache pool ÷ concurrency × 90%, not a
constant):

| Channel | Chars | ≈ tokens | Share of window |
|---|---|---|---|
| Declared libraries (`ManifestParser`) | ~600 | 150 | 0.3% |
| Project rules / constraints (`StoryScope.constraintBrief`) | ~1,500 | 375 | 0.7% |
| **Neighbourhood of the change** (new) | ≤ 6,000 | 1,500 | 2.9% |
| Worked example (`WORKED_EXAMPLE_CHARS`) | ≤ 14,000 | 3,500 | 6.8% |
| Documentation catalogue beside an example (`DOC_MAP_CHARS_BESIDE_AN_EXAMPLE`) | ≤ 900 | 225 | 0.4% |
| **Brief total, capped by `MAX_BRIEF_CHARS`** | ≤ 18,000 | ≤ 4,500 | ≤ 8.8% |

Plus the system role, workflow rules and task instructions — the prefix the product logs per
dispatch as `prefixTokens=`, measured at 6-7k on the demo project. **A brownfield task's prompt is
the same size as a greenfield one's.** Nothing here needs a bigger window; the change is *what* is
in it.

### 2.3 The existing tests as the worked example

This is the finding the whole product turns on, and it was measured
(`dev/experiment/unseen-code/results.md`, `dev/experiment/plain-loop/comparison.md`):

> Given an unfamiliar framework, prose documentation and a search tool, the model read 43 documents,
> disassembled the jars with `javap` for 106 shell calls, and over three runs and 467 turns wrote
> **zero files**. The same model, same harness, same-shaped task on plain Java it already knew was
> writing at turn 5 and green in ten turns.

And with a worked example plus the product's own read-pause rule (variant v3): **34 turns, 13
minutes, green.** Two levers, and neither substitutes for the other — *"a forcing rule with a bad
brief produces a worker that writes early and writes the wrong API; a good brief with no forcing rule
produces a worker that never writes at all."* SwarmCoder already has the second lever
(`EarlyKillEnforcer`, nudges at 8 and 16 looking-only calls, read pause at 16, `NO_PROGRESS` kill at
24). `WorkedExamples` is the first.

**On a brownfield target the worked example comes from the target's own code, and that is the best
possible case for it.** `WorkedExamples.select` scores every file under every root by shape
agreement against the design's `ApiContract`s — role, form, members, name tokens — then runs a second
pass over *projects* to keep one coherent idiom rather than four. With one root (the target), the
second pass is trivial and every match is a sibling of the file being changed: to fix a jsoup CSS
selector the nearest example is another `Evaluator` subclass; to fix an HTML parser state it is
another state in the same enum.

**Two things must change for it to fire at all.**

1. **`ApiContract`s must name existing types.** Today the architect states contracts for types the
   plan will *create*. `Librarian.workedExample` returns "" when `task.deliveredContracts()` is
   empty, and the whole example channel collapses back to a documentation guess. So
   `ArchitectClient`'s design prompt gains one instruction for brownfield: **state a contract for
   every type the change touches, whether it exists or not**, and mark which. That single sentence
   is what keeps the example channel alive.
2. **The example must not be silently the file being changed.** It often will be, and that is
   fine — a worker reading the current `Evaluator.java` before changing it is right. But it must be
   labelled as the file to change, not as an example to imitate elsewhere. `TaskBrief.render`
   already stamps provenance (`sourceOfTheExample`); it gains one line saying whether the example
   is inside the task's own write set.

**The reference-index and help-desk floor stays.** `ExpertDesk` answers a stuck worker's question
out of the codebase first — real call sites, the nearest example, the task's contracts — and only
escalates what none of those can answer. On a brownfield target every one of those tiers is
answering out of the target's own source, which is exactly the tier the desk was built for. Variant
v7 in the experiment reached green with **no brief at all**, on the help desk alone, in 90 turns;
the brief is what makes it fast, not what makes it possible.

---

## 3. The gate

### 3.1 What the project's own suite gives us

`.swarmcoder/verify.yaml` declares four command lists, run in order, fail-fast, by
`CommandPipelineVerifier`: `compile` → guideline checks → `acceptance` → `existing` → `lint` →
browser. Short-circuit: a compile failure skips everything after; an acceptance failure skips the
existing tests, lint and browser.

For a Maven single-module target, `ToolchainDetector` proposes and `ContractProbe` verifies
something of the shape:

```yaml
toolchain: maven
compile:
  - "mvn -o -q -B -DskipTests compile test-compile"
acceptance:
  - "mvn -o -q -B test -Dtest=swarm/accept/** -Dsurefire.failIfNoSpecifiedTests=false"
existing:
  - "mvn -o -q -B test"
testReports:
  acceptance: ["target/surefire-reports"]
  existing:   ["target/surefire-reports"]
```

Two details that cost a run each when they were got wrong on the demo project and are worth
restating: the surefire selector must be a **path** pattern (`-Dtest=swarm/accept/**`; `swarm.accept.*`
silently selects nothing), and **every** directory that can hold JUnit XML must be listed, because
the dirs are walked, not globbed.

### 3.2 The red-check, and where the reproduction lives

**The reproduction goes in the protected `swarm/accept` tree, not in the project's own test tree.**
`AcceptanceTestLocation.resolve` picks the module whose test classpath sees the most of the build;
jsoup is one module, so the answer is `src/test/java/swarm/accept` at the repository root, which is
what the old constant gave. `PathPolicy` refuses every worker a write there, which is the whole
point: a worker that could edit its own acceptance test can certify itself green.

**This also keeps the harness's oracle independent** (§4). The swarm's reproduction and the
maintainer's test are different files in different trees, so applying one never conflicts with the
other.

**Red means the same thing it means today**, and `RedChecker` needs no change — but a brownfield
change request produces a *different shape* of red, and it is worth being exact about which branch
fires:

| Shape of change | What the acceptance test does on the pre-change tree | `RedChecker` branch | `brokenTypes`? |
|---|---|---|---|
| **Behaviour of an existing type is wrong** (most bug fixes) | compiles, and **fails an assertion** | `failing > 0` → *"N acceptance test(s) fail on the pre-change tree — red state confirmed"* | never populated; `missingTypes` is empty |
| **A new method on an existing type** (most small enhancements) | **does not compile** — "cannot find symbol" | `looksLikeCompileFailure` → red, with `CompileFailureAttribution.missingTypes` | **judged** by `TypeDeliverability` |
| **A new type** (rare here) | does not compile — "package does not exist" | same | judged |

**The new classification applies cleanly to the first shape and needs care on the second.**
`RedChecker.check(target, spec, tasks)` calls `TypeDeliverability.undeliverable(missingTypes, tasks)`
and marks the red state *broken* when nobody in the plan may create a missing symbol. For an
assertion failure `missingTypes` is empty, so `broken()` is false and the classifier is inert — which
is correct.

**One real defect this exposes, and it must be fixed in wave 3.** `TypeDeliverability.mayCreate`
compares a missing type's **package** against the **package tail of each write-set entry**. Write-set
entries may be *"repo-relative dirs or files"* — `ArchitectClient`'s own prompt says so. A write set
naming a **file** (`src/main/java/org/jsoup/select/QueryParser.java`) yields the tail
`org/jsoup/select/QueryParser.java`, which neither equals nor prefixes the missing type's package
`org/jsoup/select`. So a task that names the exact file it will change would have its perfectly
healthy red state classified as a **broken test**, and the run parks. Greenfield rarely hits it
because greenfield write sets are directories for code that does not exist yet; brownfield hits it
constantly, because the file to change is known. **Fix: `packageTail` must drop a trailing
`*.java`-shaped segment before comparing.** One method, one test.

### 3.3 No existing test regresses — and what a full suite costs

Today the `existing` stage runs whatever `verify.yaml` says, **per candidate**, after the acceptance
stage is clean. The arithmetic for jsoup at the harness's own defaults — one task, two workers, and a
repair round of two seeds × two workers if the first round fails — is 2 to 6 full suite runs per
change request, plus one at integration.

That is affordable on jsoup and it is **not** affordable on the later targets, and it is the wrong
signal anyway: the interesting regression is near the change, and a suite-wide run per candidate
delays that answer behind everything unrelated.

**Proposed slice rule** — two stages instead of one:

1. **Per candidate: the near suite.** Every test class in the same package as any file the *task's
   write set* names, plus every test class the semantic index says uses any type the write set
   names (`SemanticIndex.filesUsingAll`, filtered to test source roots). On jsoup a typical change
   touching two or three files in `org.jsoup.select` selects perhaps 40-80 tests instead of ~1,650.
2. **Once, at `FINAL_INTEGRATION`, on the merged tree: the whole suite.** `FinalIntegrator` already
   verifies the merged tree and already parks the run when that verification fails; the full suite
   goes there. That is also where it belongs conceptually — the merged tree is the only tree that
   ships.

**How the slice reaches the command, and the security boundary it must not cross.** `verify.yaml`
commands are executed by the orchestrator **on the host**, unsandboxed, and the file is deliberately
read from the operator's tree (`VerifySpecLoader.loadTrusted`) so a worker cannot edit its own
verification. Substituting a computed test list into one of those commands therefore puts computed
text into a host shell command, and it must be derived from something a worker does not control.

- The list is derived from the **task's write set** (written by the architect at PLAN, before any
  worker runs), **never** from the files a candidate actually touched.
- Each entry is a Java class name and is accepted only if it matches `[A-Za-z0-9_$]+(\.[A-Za-z0-9_$]+)*`.
  Anything else is dropped and the stage falls back to the whole suite, loudly.
- The contract gains one optional field, `existingSlice`, a command containing `{TESTS}`. Absent, or
  with an unusable list, the `existing` stage is exactly what it is today.

**Fail open, always.** A repository whose layout could not be read, a semantic index that is
unavailable, a toolchain with no way to select tests — every one of them means the slice could not be
computed, and an uncomputable slice runs the whole suite. Never the reverse: a slice that silently
narrows to nothing is `Verdicts`' oldest bug (*"an acceptance stage that selected nothing at all read
exactly like one that ran and was green"*) wearing a new hat.

### 3.4 What survival means, unchanged

`Verdicts` stays the single definition, and its rules already do the right thing here: a null report
has failed; a stage that ran and executed **zero** tests is a failure when the task answers to
requirement-checks (which a change-request task always does — §1.2 is why); a candidate whose code the
build never compiles has not survived (`BuildReachabilityCheck`); and a broken declared house rule
counts as a red test. `SelectionLogic` still refuses a judge score of exactly 0.0 as a winner.

---

## 4. Verification against the human fix

**The swarm is never shown the real fix.** The harness case knows the fix commit; the target
worktree is cut from the fix commit's **parent**, and nothing about the commit — not its message, not
its diff, not its test — enters any prompt, any brief, or any file on the tree the workers see. The
issue text is the whole input, and it is the *body of the issue*, not the maintainer's commit
message.

After the run reaches `DELIVERED` and `FinalIntegrator` has merged, the harness runs the oracle in a
**separate scratch worktree** cut from the swarm's integration commit:

1. **Extract the test-only half of the human fix.** `git show <fixSha> -- <test paths>` where the
   test paths are those files of the commit under a test source root (`BuildLayout` knows which).
   Apply with `git apply`.
2. **If it does not apply, the case is `ORACLE_UNAPPLIABLE`, and that is not a pass.** It happens
   when the swarm edited the same test file, or when the maintainer's test depends on a helper the
   swarm's tree does not have. Reported as its own outcome so it is never confused with a fail.
3. **Run only the human test class.** Its pass/fail is the independent oracle.
4. **Run the whole suite** on the same tree, human test included.

**Reported per case, four facts and a diff summary:**

| Line | Meaning |
|---|---|
| `swarm test` | the reproduction the swarm's own test author wrote, on the swarm's tree — passed / failed |
| `human test` | the maintainer's test from the fix commit, on the swarm's tree — passed / failed / could not apply |
| `existing suite` | the project's whole suite on the swarm's tree — green / N failures (naming them) |
| `files` | the source files the swarm touched, beside the source files the human touched, as two lists |

**The pass rule.** A case passes when **all three** hold: the swarm's own test passes, the human test
passes, and the whole suite is green. Anything else is a fail, named by which line broke.

**Files touched is reported and never gated.** There is more than one correct fix for most of these
issues, and a swarm that fixed the same bug one layer up has not failed. The list is there so a
person reading the report can see *how* it was fixed, and so a systematic pattern (always fixing at
the call site instead of the cause) becomes visible across five cases rather than argued from one.

**Why the human test is a real oracle and not a formality.** The swarm wrote its own reproduction
from the same issue text, so "swarm test passes" only proves it satisfied its own reading of the
issue. The maintainer's test was written from the fix and encodes the reading that was actually
right. The two agreeing is the evidence; the swarm's alone is not.

**One selection rule falls straight out of this** and constrains §5's case file: **an issue whose
fix introduces a new public method name is only usable when the issue text names that method**.
Otherwise the human test calls `element.wholeOwnText()` and the swarm, having fixed the same
behaviour under a name of its own choosing, fails an oracle that was never a test of the fix.

---

## 5. The harness

`BrownfieldLoopTest`, `sc-app`, `@RunsWhen({Need.LIVE_MODEL, Need.MAVEN})`, same discipline as
`EndToEndLoopTest`:

- **`[CHAIN ok  ]` / `[CHAIN FAIL]` links** through `ChainLedger`, whose contract is that each link
  records **one observation phrased to be true either way** — a measurement, not a verdict, because
  *"the recurring failure this whole harness exists to catch is a stage that exits 0 having done
  nothing."*
- **Free local endpoint only.** `EndToEndLoopTest.refuseAnythingButAFreeLocalEndpoint` refuses to
  start unless the endpoint is plain HTTP on a private address; the same guard is copied verbatim,
  and the harness never loads the operator's `~/.swarmcoder/config.yaml`.
- **Budget from `HarnessRunBudget`**, unchanged: overhead + build work + repair work, capped by
  `-Dswarmcoder.e2e.maxMinutes`. jsoup's suite is fast, so the wall clock is model turns, not builds.
- **Parameterised by case** (`@ParameterizedTest` over the case file), one case at a time — the GPU
  is shared and the experiment's own instruction stands: *"One at a time."*

### 5.1 The chain

| # | Link |
|---|---|
| 1 | the target repository is checked out at the parent of the known fix, and the fix is not on it |
| 2 | the build and test contract is detected and really builds this repository |
| 3 | the project's own suite is green before anything starts |
| 4 | the change request becomes one agreed requirement carrying checks |
| 5 | the neighbourhood of the change names real types, with files and lines |
| 6 | the architect's design states contracts naming types that exist |
| 7 | every agreed check is claimed by a task, and no task was planned for nothing |
| 8 | every task writes only to a source root the build compiles |
| 9 | the acceptance test lands where the build runs it, and is red on the pre-change tree |
| 10 | the acceptance stage executes a non-zero number of tests |
| 11 | a worker produced a candidate that changes a file |
| 12 | verification reported a real verdict, not an empty pass |
| 13 | a winner was selected, or a clear reason given |
| 14 | the winner is merged |
| 15 | the swarm's own test passes on the merged tree |
| 16 | **the maintainer's test passes on the merged tree** |
| 17 | **the project's whole suite is green on the merged tree** |

Links 1-3 and 15-17 are new; 4-14 are `EndToEndLoopTest`'s links with the front half replaced.

### 5.2 Where the target lives

`~/.swarmcoder/targets/jsoup` — a bare-ish clone, fetched once, never modified. Each case gets a
**git worktree of that clone** at the fix commit's parent, under
`~/.swarmcoder/targets/jsoup-cases/<issue>/`, thrown away after the case.

Not under `C:/work/worktrees/` — that directory is for our own repositories' worktrees. Not inside
the SwarmCoder checkout — a 30k-line third-party tree in the repo would be indexed, walked by
`WorkedExamples`, and committed by accident. Not `@TempDir` — a clone per case is a minute of network
the harness should pay once.

### 5.3 The case file

`dev/brownfield/jsoup-cases.json`. One object per case:

```json
{
  "target": "jsoup",
  "issue": 2246,
  "title": "…",
  "kind": "BUGFIX",
  "issueText": "…the body of the GitHub issue, verbatim…",
  "fixCommit": "…",
  "humanSourceFiles": ["src/main/java/…"],
  "humanTestFiles": ["src/test/java/…"],
  "humanTests": ["org.jsoup.…Test#methodName"],
  "why": "one sentence on why this case qualifies"
}
```

`issueText` is stored in the file rather than fetched at run time: the harness must be runnable
offline and a case must not change because somebody edited a GitHub issue.

### 5.4 The five jsoup cases

Every one verified against the GitHub API: the issue body, the `Fixes #NNNN` commit, the file list,
and the test method names. Four bug fixes, one enhancement, across five different parts of the
library. Every fix commit touches one or two files under `src/main/java`, adds at least one test, and
does nothing else beyond a `CHANGES.md` line.

| # | Title | Kind / area | Fix commit | Source files | Test added |
|---|---|---|---|---|---|
| **2197** | Should parse in Quirks Mode if doctype not set | bug — parser | `8601e85f` (2024-09-10) | `parser/HtmlTreeBuilderState.java` (+3/−2) | `parser/HtmlParserTest#tableInPInQuirksMode` |
| **2187** | `:has()` bug (v1.18.1) | bug — selector | `7e2d5038` (2024-09-10) | `select/StructuralEvaluator.java` (+9/−10) | `select/SelectorTest#divHasSpanPreceding`, `#divHasDivPreceding` |
| **2266** | Setting `charset` on an empty XML doc throws `IndexOutOfBoundsException` | bug — nodes | `5d31cac5` (2025-01-28) | `nodes/Document.java` (+2/−2) | `nodes/DocumentTest#charsetOnEmptyDoc` |
| **2476** | Cleaner may duplicate enforced attrs with preserve-case input | bug — safety | `7c32b7e1` (2026-03-08) | `safety/Cleaner.java` (+5/−1) | `safety/CleanerTest#canonicalizesEnforcedAttributes`, `#canonicalizesNofollowEnforcedAttribute`, `#preservesMatchingSourceNofollowWhenEnforcementSuppressed` |
| **2105** | `text()` is missing a separator space between `<button>` text nodes | enhancement — tag config | `c7db66ea` (2024-07-01) | `parser/Tag.java` (+2/−2) | `nodes/ElementTest#buttonTextHasSpace` |

**Why each qualifies — the issue body carries the whole reproduction.**

- **2197** gives the input HTML (a `<table>` nested inside `<span>` inside `<p>`), the exact wrong
  output (the table foster-parented out of the `<p>`, plus a spurious empty `<p>`), and the note that
  Chrome and Firefox do not do this. One parser-state change, one new parser test.
- **2187** is four lines of Java — HTML string, `parseBodyFragment`,
  `doc.select("div:has(span + a)").size()` — with *"expected = 1, fact = 0"* and the note that 1.17.2
  was right. A regression with a literal expected-versus-actual number.
- **2266** is three lines of Java (a `Document` with the XML namespace, `syntax(xml)`,
  `charset(UTF_8)`) plus the full stack trace naming `Document.ensureMetaCharsetElement`. A guard in
  one method.
- **2476** gives the Java snippet (preserve-case parse of `<a REL='external'>`, a `Safelist` with
  `addEnforcedAttribute`) and writes both HTML strings out: *was* `<a REL="external" rel="external">`,
  *should* `<a rel="external">`. Three new tests including the suppressed-enforcement edge case.
- **2105** is one line of Java, actual `"ReplyReplyToAll"`, expected `"Reply ReplyToAll"`. Labelled
  an improvement rather than a defect, and the fix is a two-token tag-configuration change.

**None of the five introduces a new public method name**, so §4's selection rule is satisfied and the
maintainer's test cannot fail on a naming difference.

**Two reserves**, verified to the same standard, for a case that turns out to be unusable:
**#2079** (`[*]` any-attribute selector, an enhancement — `945b5218`, `QueryParser` + `Selector` +
`SelectorTest`) and **#2356** (clone drops a user-data attribute — `fa61c695`, `Attributes` +
`AttributesTest`).

**Rejected candidates, so the pool is real.** About 22 were examined. #2242 — the reporter says *"I
cannot nail it down to a certain condition"*. #2244 — needs an attached 100 KB HTML file. #1147 — the
reproduction is a dead external URL and a screenshot. #2130 — *"a random document"* with element
counts and no HTML. #2142 — needs a live network fetch. #2245 — a pull request, not an issue, and its
fix spans four source files.

### 5.5 What building and testing jsoup actually costs

Read from the repository, not assumed:

| | |
|---|---|
| Build tool | Maven, **no wrapper** (`mvnw` and `.mvn` are absent) — `mvn` from `PATH` |
| Version | `1.24.1-SNAPSHOT`; no Gradle files |
| Test command | `mvn test` (surefire 3.5.6) |
| Whole verify | `mvn verify` — failsafe 3.5.6 over `*IT.java`, excluding the group `long-running` |
| Single test | `mvn test -Dtest=SelectorTest#divHasSpanPreceding` — **class#method selection works**, which is what §3.3's slice rule needs |
| Test framework | JUnit 5 (`junit-jupiter` 5.14.4), surefire parallelism 8, `same_thread` default mode |
| Test size | 93 `.java` files under `src/test/java` (65 carrying tests; the rest are helpers such as `TestServer`, the Netty route classes, `TextUtil`, `EvaluatorDebug`) plus 6 under `src/test/java11` — about **1,650 `@Test` methods** |
| Main size | 95 `.java` files under `src/main/java` |
| Java | source and target **8** is the baseline; a `multi-release` profile compiles `src/main/java11` at release 11 into a multi-release jar, so building the shipped jar needs JDK 11+. `animal-sniffer` checks against the Java 8 API and Android API level 21. |

**Three consequences for the harness.**

- **`mvn test` runs surefire only**, so the ~1,650 methods are the regression gate and the failsafe
  integration tests are not. That is the right gate: `verify` is slower and its `*IT` tests are not
  what a fix commit's test lives in.
- **Some tests start a local Netty server** (`TestServer`). Candidates verify inside a sandbox with
  `network=NONE`, which still has loopback, so this works — but it must be checked in wave 1, because
  a suite that cannot bind is a red suite for a reason no candidate caused.
- **JDK 11 or later must be on `PATH`** even though the baseline is 8. The harness asserts this at
  link 2 rather than discovering it inside a candidate's build.

### 5.6 Adding a case for a framework-heavy target

Four things, and only the last is new work:

1. **A clone and a base commit.** Same as jsoup.
2. **A verification contract.** `ToolchainDetector` proposes it; `ContractProbe` runs it once; a
   person corrects it. For a Gradle or Node target this is where the effort is, and it is a one-time
   cost per target, not per case.
3. **Reference roots.** A framework the model has not seen needs its own checkout or documentation
   folder as a `contextPath` on the project, exactly as `HarnessReferenceRoot` wires
   `C:/work/zeroz4j` today. This is the lever the unseen-code experiment measured; without it the
   worked example and `lookup_api` have only the target itself.
4. **A case file entry**, identical in shape.

**What must be true of the target for the harness to work at all**: it must have a linked fix commit
per issue, its tests must be selectable by class or path from the command line, and its suite must
finish inside `HarnessRunBudget`. A project whose suite takes an hour needs the slice rule of §3.3
before it can be a target at all.

---

## 6. What stays shared, what forks

**One workflow. The fork is in front of it.**

### Shared, entirely unchanged

`GreenfieldWorkflow` from `DESIGN` onward — every state, every retry constant, every park:
`ArchitectClient` (design, revise, plan), `DesignReviewerClient` (design review, rule review, plan
review), `TaskGraphValidator`, `BuildFilesInTheJob`, `TestAuthorClient`, `AcceptanceTestLocation`,
`RedChecker`, `SwarmEngineImpl` (topological waves, the wave gate, the repair round),
`SwarmDispatcher`, `WorkerLoop`, `WorkerToolbox`, `PathPolicy`, `EarlyKillEnforcer`, `ExpertDesk`,
`Librarian`, `WorkedExamples`, `SemanticIndex`, `ReferenceIndex`, `CommandPipelineVerifier`,
`Verdicts`, `BuildReachabilityCheck`, `AcceptanceFailureAttribution`, `SyntacticClusterer`,
`JudgeClient`, `SelectionLogic`, `FinalIntegrator`, `SecretScanner`, `RunPersister`, `ContextLedger`.

### Changed

| Class | Change |
|---|---|
| `ArchitectClient` | one instruction in the design prompt: for a change against existing code, state an `ApiContract` for **every** type the change touches, existing or new, marked as which. Without this `Librarian.workedExample` returns "" and the example channel dies. |
| `RunBrief` | the `BUGFIX` and `ENHANCEMENT` briefs gain the neighbourhood text and a sentence naming the tests that already cover the area. `REFACTOR`'s is untouched. |
| `Librarian.assembleBrief` | one new channel — the neighbourhood — sized as §2.2 and rendered before the worked example. |
| `TaskBrief.render` | one provenance line: whether the worked example is inside the task's own write set (i.e. it is the file to change, not a pattern to copy elsewhere). |
| `TypeDeliverability.packageTail` | drop a trailing `*.java` segment before comparing (§3.2). This is a bug fix that greenfield also benefits from. |
| `VerifySpec` | one optional field, `existingSlice`, a command carrying `{TESTS}`. Absent ⇒ today's behaviour exactly. |
| `CommandPipelineVerifier` | when `existingSlice` is present and a sanitised class list could be computed from the **task's write set**, run it instead of `existing`; otherwise run `existing`. |
| `FinalIntegrator` | the whole `existing` suite runs on the merged tree (it already verifies that tree; this states which commands). |
| `ToolchainDetector` | nothing structural; the proposed contract gains an `existingSlice` line for Maven and Gradle where a selector syntax is known. |
| `PipelineBoard` | one new card action and one new column caption — §7. |

### New

| Class | Module | What it does |
|---|---|---|
| `ChangeRequestIntake` | `sc-console` | issue text → one agreed requirement with checks → one story → one run. Third sibling of `RequirementsIntake` and `BacklogPlanning`. |
| `ChangeNeighbourhood` | `sc-knowledge` | issue text + `SemanticIndex` → a bounded brief of the types, their usages, and the tests that cover them. |
| `TargetRepository` | `sc-console` | register an existing checkout: detect, probe, render, commit the contract. Wraps `ToolchainDetector` + `ContractProbe`; adds no logic of its own. |
| `ChangeRequestView` | `sc-console-ui` | the screen. |
| `BrownfieldLoopTest` | `sc-app` (test) | §5. |
| `BrownfieldCases` | `sc-app` (test) | reads the case file. |
| `HumanFixOracle` | `sc-app` (test) | applies the maintainer's test to the swarm's tree and runs it. Test-only, on purpose — **the product must never contain code that reads a known-good answer.** |

### The one thing that is deliberately not built

**No `BrownfieldWorkflow` class.** `docs/DEVELOPER_CORRECTIONS.md` §22 records what happened the last
time a run kind got a workflow class of its own: four of them, fifty lines each, every one renaming
the run's state and reporting `DELIVERED` having built nothing, all four on the chat menu and in the
user manual, and no test had ever started one. The delivery path is one path.

---

## 7. Console

Minimal, and written for someone who does not know what a "criterion" is.

### 7.1 Adding an existing codebase

The new-project dialog gains a second choice on its first step:

> **What are we working on?**
> - **Something new** — you have written down what you want. We will build it from your documents.
> - **Code that already exists** — point us at the folder. We will read it and work out how to build
>   and test it.

Choosing the second asks for the folder, then shows one screen:

> **We looked at your code**
>
> This is a **Maven** project. Here is how we think it is built and tested:
>
> ```
> Build it with:   mvn -o -q -B -DskipTests compile test-compile
> Run its tests:   mvn -o -q -B test
> ```
>
> *We worked this out from `pom.xml`. We ran the build command once, and it worked — it took 41
> seconds.*
>
> If either line is wrong, fix it here. Getting it wrong means every change we make gets checked
> with the wrong command, and we will tell you things passed when nobody tried them.
>
> `[ Try it again ]` `[ This is right — save it ]`

The evidence lines and warnings `ToolchainDetector.Detection` carries go under a *"How we worked this
out"* disclosure. Nothing is saved until the operator presses the button; the file is written into
their checkout and committed, because a worker's worktree is cut from a commit and an uncommitted
file is not in one.

### 7.2 Asking for a change

On a project of this kind, the pipeline board's first column gains one button:

> `[ + Ask for a change ]`

which opens:

> **What needs to change?**
>
> **Give it a short name**
> `[ Links with spaces in them are not found                                  ]`
>
> **Paste the bug report, or describe the problem**
> Copy in the whole issue if you have one. Include what happens now and what should happen instead,
> and an example if you have one — that is what we work from.
> ```
> [                                                                            ]
> [                                                                            ]
> ```
>
> **Is this something broken, or something new?**
> ( ) Something is broken and should be fixed
> ( ) This works, but I want it to do something more
>
> `[ Read it ]`

"Read it" runs `ChangeRequestIntake`. It comes back, typically inside a minute on a local model,
with:

> **Here is what we understood**
>
> **The problem:** A CSS selector with a space inside quotes, like `a[href="two words"]`, finds
> nothing. It should find the link.
>
> **We will know it is fixed when:**
> - Looking for `a[href="two words"]` in a page containing that link finds it.
> - Looking for `a[href="two words"]` in a page without it still finds nothing.
>
> **We assumed:** that this applies to all attribute selectors, not only `href`. Change this if it
> is wrong.
>
> **We looked at your code and found:** `org.jsoup.select.QueryParser` (line 214) is where selectors
> are read. 6 files use it. `org.jsoup.select.QueryParserTest` already tests it — 41 tests.
>
> `[ Change something ]`  `[ Start working on it ]`

Pressing **Start working on it** creates the requirement, the story and the run in one go, and the
card appears in **Building** with the badge and sentence `BuildState` already derives.

### 7.3 Watching it

Nothing new. `PipelineBoard`'s five columns and one health badge already say everything: agents at
work, paused, stopped, needs you. The card for a change request reads its title, not a story key.

When the run comes back, the card's *"did it deliver what was asked?"* question is answered with what
the run actually proved:

> **It says it fixed it.** The test we wrote for this now passes, and all 1,647 of your own tests
> still pass. It changed 2 files.
> `[ Look at the change ]` `[ Yes, this is right ]` `[ No — say why ]`

---

## 8. Waves of work

Branch `brownfield`. Each wave is something the owner can try and see work.

### Wave 1 — point it at jsoup and prove the ground is solid

**Try:** run the harness's first three links against jsoup and get a green line for each.

- `BrownfieldCases` + the case file with all five jsoup cases (§5.4).
- Clone and per-case worktree management under `~/.swarmcoder/targets/`.
- `TargetRepository`: detect, probe, render, commit — no UI yet, driven from the test.
- Links 1-3 of the chain: the tree is at the fix's parent, the contract really builds it, the suite
  is green before anything starts.
- **Also lands the `TypeDeliverability.packageTail` fix** (§3.2), because every later wave hits it.

**Done when:** `BrownfieldLoopTest` walks links 1-3 for all five cases and reports how long the
project's own suite takes, per case.

### Wave 2 — the machine says what a change request means

**Try:** paste a jsoup issue in and read back what the machine understood, before it builds anything.

- `ChangeNeighbourhood` and its tests, against the jsoup checkout.
- `ChangeRequestIntake`: one model call, one requirement, one to three checks, one story, one run.
- Links 4-6 of the chain.

**Done when:** for all five cases the machine names the right types with real file:line references
and drafts checks a person recognises as the issue restated.

### Wave 3 — one issue, end to end, against the human's test

**Try:** the whole thing on the easiest of the five cases, and see whether the maintainer's own test
passes on what the swarm produced.

- `HumanFixOracle`, links 15-17.
- The `ArchitectClient` contract instruction, the `RunBrief` change, the `Librarian` neighbourhood
  channel, the `TaskBrief` provenance line.
- Full suite per candidate — no slicing yet, so the slice is never blamed for a failure that is not
  its fault.

**Done when:** one case reaches DELIVERED and the report prints all four oracle lines, whatever they
say. A red oracle here is a result, not a failure of the wave.

### Wave 4 — all five, and the slice

**Try:** run all five cases unattended and read one report.

- `existingSlice` on `VerifySpec`, computed from the task's write set, sanitised, failing open.
- Full suite moved to `FinalIntegrator`'s merged-tree verification.
- The per-case report, and a summary table across the five.

**Done when:** five cases run in one command and the summary says how many passed the human's test,
with the wall clock per case before and after slicing.

### Wave 5 — the console

**Try:** add jsoup as a project in the real console, paste an issue, watch it build, judge it.

- The "code that already exists" branch of the new-project dialog and the contract screen.
- `[ + Ask for a change ]`, `ChangeRequestView`, the understood-it screen, the delivery sentence.

**Done when:** the owner does the whole thing through the browser without touching a test.

### Wave 6 — a second target, on a framework the model does not know

**Try:** the same five-case shape against a mid-size project on an unfamiliar framework.

- A second case file, a second verification contract, reference roots wired as a `contextPath`.
- Whatever the first five cases proved was missing.

**Done when:** the harness runs two targets from one command and the report compares them.

---

## 9. Open decisions

**Decided 2026-09-05 by the owner: every recommendation below is taken as written** — (1a) one agreed
requirement with checks; (2a) `swarm/accept` now, move-at-merge later; (3b) the slice, but not before
wave 4; (4a) never ask, state assumptions; (5) fail for the count, named outcome in the report; (6a)
exclude enhancements whose fix names a public method the issue does not. The owner's words: "do
whatever will make swarmcoder a brilliant coding product."

**1. One agreed requirement per change request, or an ad-hoc story with no checks?**

- **(a) One agreed requirement, one story, checks drafted from the issue** — everything that makes a
  delivery mean something stays switched on: the coverage invariant, `CriterionEvidence`, the
  zero-tests-executed failure rule, the delivery stamp.
- **(b) Reuse `AdHocStory`** — nothing new to build, but the story has no criteria, nothing is
  evidenced, and the acceptance stage running zero tests would pass silently.

**Recommendation: (a).** (b) is free today and it is the version that cannot tell you whether it
worked.

**2. Does the swarm's reproduction go in the protected `swarm/accept` tree, or in the project's own
test tree beside the tests it belongs with?**

- **(a) `swarm/accept`, as today.** `PathPolicy` keeps workers out of it, the maintainer's test never
  collides with it, and nothing in `TestAuthorClient` or `AcceptanceTestLocation` changes.
- **(b) The project's own test tree** — a real maintainer would put it there, and a delivered change
  that leaves a test in a foreign package is not quite a real contribution.

**Recommendation: (a) now, (b) as a later step that moves the file at integration.** Making it (b)
today means either letting workers write in the project's test tree — which is how a candidate
certifies itself green — or building a move-at-merge step before anything else works.

**3. Per-candidate regression: the whole suite, or a slice?**

- **(a) Whole suite every time.** Simple, and jsoup can afford it.
- **(b) Slice per candidate, whole suite once at integration.** Faster, scales to real targets, and
  adds a computed value to a host-executed command — which has to be derived from the architect's
  write set and hard-sanitised.

**Recommendation: (b), but not before wave 4.** Wave 3 should pay the full cost so that a failing
case is never blamed on the slice.

**4. Should the change request be allowed to ask the operator a question when the issue is
ambiguous?**

- **(a) Never ask; state assumptions on the requirement.** The person who wrote the issue is not in
  the room, and a question round on a bug report is a form nobody fills in.
- **(b) One round, up to three questions**, the way `RequirementsIntake` does.

**Recommendation: (a).** It also keeps the harness unattended, which (b) does not.

**5. When the swarm's own test passes but the maintainer's fails, is the case a fail or a finding?**

- **(a) A fail.** The pass rule in §4 is all three lines green.
- **(b) A third outcome — "fixed something, not the thing"** — reported separately, because it is a
  different and more interesting failure than a run that never delivered.

**Recommendation: both — (a) for the pass/fail count, (b) for the report line.** The distinction is
the whole reason the human test is there, and losing it in a boolean throws away the measurement.

**6. Which of the two enhancement-shaped risks do we accept for the first five cases: an enhancement
whose fix names a new public method?**

- **(a) Exclude them unless the issue text names the method.** The oracle is then honest.
- **(b) Include them and accept that the oracle can fail for a naming difference**, reported as
  `ORACLE_UNAPPLIABLE`-adjacent.

**Recommendation: (a).** An oracle that fails on a name teaches nothing about whether the change was
made.
