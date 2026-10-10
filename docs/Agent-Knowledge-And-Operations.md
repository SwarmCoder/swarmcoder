# SwarmCoder — knowledge, models, harnesses and operations

**Written 2026-09-05, from the code, after two days of intensive fixes (harness runs 8 to 30).**

The second half of the developer guide. The first half — orientation, the pipeline stage by stage, and
the worker — is **`docs/Agent-Process-Flow.md`**, and this file assumes it.

## Table of contents

- [3. Knowledge and the help desk](#3-knowledge-and-the-help-desk) — the text index, the structural
  index, worked examples, `lookup_api`, `ask_expert`, `request_skeleton`
- [4. Runtime and models](#4-runtime-and-models) — Koog, model shapes, thinking, history trimming, how
  big a worker's room really is, the roles, and why a harness run cannot spend money
- [5. The harnesses](#5-the-harnesses) — the greenfield chain and its 16 links, the brownfield chain and
  its 17, and the two recorded experiments
- [6. Problem-handling playbook](#6-problem-handling-playbook) — a table: symptom → what the system now
  does → which class → the run that taught it
- [7. Operating notes](#7-operating-notes) — the console, the `~/.swarmcoder` layout, building, running,
  testing, and the conventions

---

# 3. Knowledge and the help desk

Everything a worker knows that is not in its weights comes from `sc-knowledge`. There are three
retrieval systems and one escalation path.

## 3.1 `ReferenceIndex` — the text index

`sc-knowledge/src/main/java/com/swarmcoder/knowledge/ReferenceIndex.java` (998 lines,
package-private). Lucene with BM25 over explicitly boosted fields.

**Not every file.** `walk()` keeps only `.java`, `.md`, `.markdown` and `.adoc`, excluding any path
containing `/target/`, `/.git/`, `/node_modules/` or `/build/`; a non-Java file must additionally pass
`KnowledgeCurator.isDocumentation`, because markdown scattered through a source tree is not
documentation.

**Outlines, not bodies.** A `.java` file is indexed as one `src` document holding only its
`JavaOutline` — package, imports, type names, annotations, public signatures, first javadoc sentence.
A document is indexed as one `page` unit plus one `doc` unit per heading-delimited section.

**There is no file-count cap**, but there are byte caps: `MAX_SOURCE_BYTES` and `MAX_DOC_BYTES` are
both 1,000,000, and a longer file is truncated rather than skipped. `CANDIDATES = 60` raw hits are
re-ranked before the cut.

**The version key.** `rootSetHash()` is a SHA-256 over, per root in order, the label, the path and the
root's identity fingerprint (`git describe --tags --always`, else the pom version, else `unknown`,
plus commit and dirtiness), then the kind overrides and the literal index format string. The first 12
hex characters name the directory: `<indexRoot>/<label>-<hash>/`, where `label` is the last root's.
Beside the Lucene files sit `stamps.txt` (per-file size and mtime) and `external.txt`.
`pruneOtherFingerprints()` deletes siblings of the same label with a different hash.

`indexRoot` is `<primerCacheDir>/refs` when a cache directory is supplied — every test supplies one —
and otherwise `~/.swarmcoder/rag/refs`.

`REFRESH_TTL_MS = 60_000` between re-walks; only stamp-changed files are re-indexed. A fresh build
calls `forceMerge(1)` so BM25 collection statistics, and therefore every score, are identical on every
machine.

**Ranking.** Field boosts: sections heading 4.0 / body 1.4; pages title 0.8 / path 2.0 / headings 1.0
/ body 1.4; sources name 8.0 / name-part 3.0 / path 2.0 / outline 1.5. Page and section scores combine
at weights 1.0 and 0.8. Then multipliers: a file kind that is not answerable is weighted 0.01 and
normally dropped; a shelf weight by kind (guides 1.00, reference 0.85, quickstart 0.82, top-level
topical 0.78, README 0.72, else 0.70); a length penalty above 3,500 characters and a `STUB_PENALTY =
0.55` below 400. Scores are rounded to four decimal places and tie-broken by address then heading, so
a group's shared prefix is byte-identical.

**External pages** — a Context7 reply or a crawled web page — go into the **same** index as a guide,
with a `source` field, so a worker never has to know where an answer came from.

## 3.2 `WebDocs` and Context7

**`WebDocs`** crawls a documentation *site*: URLs from `<base>/sitemap.xml`, falling back to a MkDocs
search index, falling back to the base page alone. `MAX_PAGES = 300`, `MAX_PAGE_BYTES = 400_000`,
cached on disk with ETag/Last-Modified so a refresh costs one 304 per page.

**It is never consulted at question time.** `KnowledgeCurator`'s constructor starts a daemon thread
named `docs-site-crawl` when site URLs are configured, and each crawled page is added to the reference
index. It needs the network, in the orchestrator process only — workers never reach the network.

**Context7** (`Context7Client`) is an MCP client for version-accurate published library docs. Two
transports: a local SSE server, and the hosted Streamable-HTTP endpoint reached through a
hand-written JSON-RPC-over-HTTPS client, because the pinned MCP SDK supports neither headers on SSE
nor Streamable HTTP. The key is read from the environment variable `CONTEXT7_API_KEY`, never stored in
the settings file and never logged. Its posture is one of `READY`, `NO_KEY`, `KEY_REJECTED`,
`UNREACHABLE`.

Two distinct predicates matter: `documentationServerConfigured()` is asked *before* anything is
called, and decides whether `library_docs` appears in the expert's tool list;
`documentationServerReachable()` is a round trip.

**`library_docs` is an expert tool only.** A worker has no MCP tool of its own; Context7 reaches a
worker as the last fall-through inside `lookup_api`.

## 3.3 `lookup_api`

Backed by `Librarian.lookupApi(query, alreadyGivenSections, newTerms)`.

**Never the same section twice.** `WorkerToolbox` keeps a per-worker-per-task set of every section it
has been given. After each answer it scrapes **every** `#### ` line — not only the leading one — and
adds it. That set is passed on the next call and filters candidates by
`address + " › " + heading`. **Section identity is in every answer** because the renderer writes
exactly that string as the heading of each block.

**A follow-up's new words are boosted.** `newLookupTerms(query)` is the words of length ≥ 3 in this
question minus every word from every earlier question this task — computed before the current words
are recorded. Those terms multiply their field boost by `DISTINGUISHING_TERM_BOOST = 3f`. It cannot
invent a match BM25 did not already find.

**The "nothing new" message**, fired only when the search *did* match and every match was already
given:

> Every documentation section matching this lives in what you already read: <sections>. There is no
> more documentation on this; write your best attempt, or ask_expert with the exact question.

A genuine miss takes a different path, which names the catalogue and — when the documentation server
is unreachable — adds *"(The documentation server is not running, so published library docs could not
be consulted — do not wait for it and do not retry.)"*

**Budgets.** `LOOKUP_CHARS = 6_000`, documentation half capped at `DOC_CHARS = 3_000`, code half taking
what is left. Those are the figures at the 51,200-token baseline room; since 2026-09-25 both scale with
the worker's own room (30,720 and 15,360 at 262,144 — see §4.6), and a lookup made for the architect or
the expert is sized by that reader's room instead (`lookupApi(query, reader)`). A documentation section
is trimmed at its fair share of the documentation half, never below 3,500, so a scaled answer carries
whole sections rather than two first 3,500s. A question naming two or more types, or having at most one content word, gets one
section at half size; otherwise two sections at full size. Tiers in order: a whole file by path →
documentation and shaped sources together → the older whole-reply `DocsIndex` → a live Context7 fetch,
re-indexed and re-queried. At most two sections from any one page.

The degenerate-search warning and `DOCS_DEAD_END` relabelling are described in §2.5.

## 3.4 `SemanticIndex` — the structural index

`sc-knowledge/.../SemanticIndex.java` (784 lines), built by `LstReader` from **OpenRewrite 8.90.4**
LSTs. It does **not** use `sc-lsp`, and does not use tree-sitter for this path.

**The compact relation store.** OpenRewrite's Apache-licensed library cannot serialise an LST — that is
what Moderne's commercial platform does — so the tree is built once and only the relations are kept.
`SemanticFacts` (format `lst-relations-v2`) holds `TypeFact`, `UseFact`, `CallFact` and `PomFact`
records plus per-file type lists and build statistics.

**The cache path, precisely:** `<base>/<sanitised root label>-<12-hex fingerprint>/semantic.json.gz`
— **one directory per root, not per root set**, because a reference checkout is the same checkout for
every project pointing at it. (The *text* index is the one keyed per root set.) The base is
`~/.swarmcoder/rag/refs` only when no cache directory is supplied.

`BUILD_BUDGET_MILLIS` is ten minutes per root, and whatever was read inside it is kept. `MAX_RESULTS =
40` per query after a deterministic sort and de-duplication. `MAX_CHAIN_DEPTH = 6`. It can be switched
off with `-Dswarmcoder.semanticIndex.off=true`, and it fails open — a `Throwable` during a parse,
including an `OutOfMemoryError`, skips that root.

What it answers:

| method | returns |
|---|---|
| `resolve(name)` | the fully-qualified names a simple or qualified name maps to |
| `implementationsOf(type)` | types transitively assignable to it |
| `usagesOf(typeOrMethod)` | files naming a type; for a method, the calls, each with caller and enclosing type |
| `filesUsingAll(names)` | files whose resolved types contain **all** of them — an intersection, not a score |
| `typesUsedIn(file)` | that file's structural fingerprint |
| `howCommon(type)` | 0–1, the fraction of indexed files using it |
| `idiomOf(package)` | annotations and supertypes the types already in that package carry |
| `typesAnnotatedWith(annotation)` | types carrying it |
| `callChain(fromMethod, toType)` | the shortest breadth-first path over the call graph |
| `publicShape(type)` | kind, supertypes, annotations, one line per member, and `declared in file:line` |
| `dependencyDeclaring(artifact)` | build files declaring or managing it |
| `namesKnownIn(text)` / `methodNamesKnownIn(text)` / `artifactsNamedIn(text)` | which identifiers in free text the index actually knows |

**There is no `declarationOf` method** — the nearest things are `publicShape` and `resolve`.

## 3.5 `WorkedExamples` — the nearest working example

`sc-knowledge/.../WorkedExamples.java` (1,138 lines). This is the single biggest lever on whether a
worker writes anything at all (see §5.3).

**Shape similarity**, `score(Wanted, Shape, taskWords)`, is a weighted sum:

```
0.40 * memberAgreement + 0.20 * formAgreement + 0.15 * roleAgreement
+ 0.15 * nameTokenOverlap + 0.05 * taskWordOverlap + 0.05 * (has members ? 1 : 0)
```

- `memberAgreement` is a greedy best-match where each declared member is consumable once. Per pair:
  same form required (a promised field is satisfied by a no-arg getter) 0.45; equal arity +0.15; same
  type kind +0.25; +0.15 × name-token overlap; capped at 1. Then coverage over the promised count,
  times a surplus penalty.
- **Type kinds fold the file's own name away**, so `List<Book>` in a Book contract and `List<Product>`
  in a Product example both become `collection-of:self`. The buckets are `self`,
  `number-or-flag`, `text`, `void`, `entity`, `any`, and `collection-of:<bucket>`.
- `formAgreement` is 0 when test-vs-non-test disagrees; an all-constants contract scores 1 only for an
  enum; an all-methods contract scores 1 for an interface.
- Token overlap is over the **smaller** set, so a short name is not punished.

**Structural confirmation.** `structuralEvidenceFor(semantic, contract)` takes the names the semantic
index knows from the contract's members, sketch and description, plus the package's own idiom; drops
the contract's own type, anything under `java.lang.`, and anything used in more than
`TOO_COMMON_TO_BE_EVIDENCE = 0.25` of files; and intersects them with `filesUsingAll`, relaxing the
last demand one at a time until something survives.

**Selection is three passes.**

1. Score every shape per contract; keep those ≥ `MIN_SCORE = 0.20`, cut to `SHORTLIST = 250`. Shape
   only — structure is deliberately excluded here.
2. **Choose one project.** Every ancestor path that declares a build is a candidate group. A greedy
   one-to-one contract↔file assignment gives a count and a strength (score cubed per pair), and
   `value = strength × (count / contracts) × (1 + relevance) / (1 + groupSize / 25)`, where
   `relevance` is the task words against the group's vocabulary plus its top-level README prose.
3. Inside the chosen group only, a candidate must clear `WORTH_SHOWING = 0.45` on shape alone; a
   candidate that appears in that contract's structural evidence has its score multiplied by
   `STRUCTURAL_PREFERENCE = 1.6` — **a multiplier, never an override**. Then one file per contract and
   one contract per file, re-sorted into the design's own contract order. Neighbours are chased up to
   `MAX_HOPS = 3` inside a 60,000-byte budget.

### The wave-3 fix (on branch `brownfield`, not on master)

Two shut gates meant no worked example could ever be offered on a single-module repository or for a
type that already exists. Both are fixed on branch `brownfield`, head `e975d91`, +74/−5 lines.

- **Single-module repositories.** `buildsAbove(file, root)` returns every ancestor *below* the root
  that declares a build. On jsoup — one Maven module, one `pom.xml`, at the root — that is an empty
  list for every file, which is not "no preference between projects" but **no projects**, so pass two
  chose no group and no example was ever offered whatever the contracts said. Measured 2026-09-05: the
  log read *"structural evidence from the semantic index for 4 of 4 contract(s)"* and then *"worked
  example: null (value 0.0, of 0 candidate projects)"*. The fix adds the root as the sole group **only
  when nothing below it declared a build**, so multi-module repositories and galleries of examples are
  untouched.
- **Existing types.** `score` returned 0 outright for a file that *is* one of the types the task must
  deliver — correct on a greenfield build, where such a file is a leftover or a coincidence. On a
  change to code that already exists, most contracts name types written years ago, and that file is
  the code the change is about. A new constant `EXISTING_MARKER = "EXISTING:"` on the contract's
  description, written by the architect under `ArchitectClient.EXISTING_CODE_CONTRACT_RULE` (prefix an
  existing type with `EXISTING:` and a new one with `NEW:`), exempts it. An unmarked contract behaves
  exactly as before.

## 3.6 Build knowledge

- **`MavenRecipes`** wraps OpenRewrite's `org.openrewrite.maven.AddDependency`. It parses with
  `MavenParser`, not `XmlParser`, because the recipe reads a resolution marker only `MavenParser`
  attaches — which resolves the parent chain, so this is **orchestrator-only and never sandboxed**. It
  never throws; a failure logs and returns an empty string. A blank version is written as no
  `<version>` element at all. `howToDeclare(...)` produces the prose sentence a worker is given, and
  is appended to a help-desk answer when the type comes from a module the asking build does not
  declare.
- **`DeclarableArtifacts`** answers "what could this build declare today, offline, with nobody choosing
  a version". An artifact qualifies only when **both** its version is managed by an inherited BOM or
  parent **and** a `.pom` (and, unless it is itself a pom, a `.jar`) exists at exactly that version in
  the local repository. It deliberately does not resolve transitives. Its `Catalog` carries a
  `repositoryPresent` flag so "nothing declarable" and "nothing was checked" stay distinct.
- **`OfflineLibraryBrief`** is the second half of the worker brief's library heading: what the build
  does *not* declare and a worker may add. `MAX_LISTED = 25`, filtered to groupIds the build already
  uses or artifactIds the project's own rules name. It prints coordinates with **no versions**, says
  the version is fixed by the BOM so no `<version>` element should be written, and says that anything
  unlisted cannot resolve because the build has no network.
- **`LibraryTypes`** (`sc-knowledge`, harness runs 44/45, 2026-09-27) answers "does this library type
  exist?" from the reference checkouts' source — every knowledge root except the project's, read once
  per process through `Librarian.libraryTypes()` with `ProjectTypes` (about 9 s cold for the 696-file
  zeroz4j checkout). It judges a package only when a checkout declares a type sharing its first three
  segments (`com.zeroz4j.ui` is covered because `com.zeroz4j.ui.component` is there; `org.teavm.jso`,
  whose jar is on the classpath but whose source is nowhere, is not). `nearest(name)` offers the real
  types in that namespace sharing a word of the name — a type of the same simple name alone when there
  is one — main sources only, each with its javadoc's first sentence, generics kept (`JavaOutline`'s own
  first-sentence reader strips `Signal<List<T>>` as if it were an HTML tag). It also answers for the
  JDK (`isJdkPackage`, `jdkDeclares`). There is no index of dependency jars; a library with no checkout
  is never judged. What it feeds is in `Agent-Process-Flow.md` §1.9.
- **`BrowserOnlyCode`** (`sc-verify`, 2026-09-25, harness run 37) answers "which modules of this build
  run only in a browser?". A module is browser-only when its **own** build file declares a browser
  runtime — any `org.teavm` artifact, `com.zeroz4j:zerozstack-client` or `zerozstack-ui-components`,
  GWT (`com.google.gwt`, `org.gwtproject`, `gwt-maven-plugin`), Elemental2 or J2CL — as a dependency in
  any scope but `test`, or as a build plugin. It is never inherited: `bookshelf-demo-server` depends
  on the client only to package its bundle and stays a JVM module. The survey also records every
  module's main packages, so `moduleOwning(name)` can trace a type that does not exist yet to the
  module whose package it sits under (longest prefix on a segment boundary); a split package held by
  a JVM module too is never called browser-only. `reachedIn(TestFailure)` reads a failure the other
  way round. What it feeds is in `Agent-Process-Flow.md` §1.8, §1.10, §1.11 and §1.14.

  **Not surfaced from the ZeroZ Stack reference material, on purpose.** zeroz4j's own
  `docs/guides/testing.md` documents `zerozstack-server-test` (`TestServer`, `TestConnection`) as the
  way to test a ZeroZ Stack server in-process, and says plainly the harness runs no client code. It
  would be the right tool for an acceptance test here, but the Bookshelf fixture's server module does
  not declare it, and the test author cannot add a dependency — a test using it would be refused as
  naming a package the module cannot see. Surfacing it is a decision for the fixture (declare the
  test harness at test scope) before it is one for the librarian.

## 3.7 The help desk — `ExpertDesk`

`sc-knowledge/.../ExpertDesk.java` (1,491 lines). One ranking, not a classifier. The 2026-09-04 change
was exactly this: *"The desk stops classifying questions and starts ranking answers."*

Constants: `ANSWER_CHARS = 4_000` (candidates trimmed at twice that; a free answer trimmed at 8,000, an
escalated one at 4,000 — all at the baseline room, scaled by the asking worker's room through
`ExpertDesk.sizedFor`, §4.6; the coverage score still reads only the first 8,000 characters of a
candidate, because it is the ranking), `MAX_CALL_SITES = 40`, **`COVERAGE_THRESHOLD = 0.5`** (raised from 0.34,
measured 2026-09-03), `FRAMEWORK_COMMON_THRESHOLD = 0.3`, `MIN_FILES_FOR_COMMONALITY = 20`, and the
cache's `OVERLAP_THRESHOLD = 0.5`.

`askExpert(question, whatITried)`, step by step:

1. **Tokenise** — camelCase, snake and dotted splits to lower-case words, length ≥ 3, minus a 60-word
   English stop list.
2. **Run cache first**, before the free tiers. If any question already answered *in this run* overlaps
   by ≥ 0.5, that answer comes straight back, re-labelled *"free: the expert answered this for worker
   N"*.
3. **Gather candidates from five sources into one list** — semantic, named file / named member,
   artifact, call site, documentation, source. Each carries a tier, but the tier is **only a
   tie-break**.
4. **Distinguishing tokens.** From the asked tokens, remove noise words, every word of the worker's own
   brief, and — only when the semantic index has parsed at least 20 files — the tokens of any name used
   in more than 30% of files. What is left is what makes this question different from every other.
5. **Coverage** = shared distinguishing tokens ÷ distinguishing tokens, per candidate. Sort by score
   descending, tier ascending.
6. **Repeat check.** If the best candidate is contained in, or contains, an answer this worker already
   got, escalate: *"escalated: free tier had only what it already gave"*.
7. **Asked-again check.** If any earlier question *this worker* asked overlaps by ≥ 0.5, escalate
   **unconditionally** — even if this question clears the coverage bar — with a reason naming the
   overlap and which ask it matched, and the free material plus every earlier question and answer
   (trimmed to 200 and 600 characters) is handed to the expert as context. Cause: harness run 30,
   2026-09-03 — one task, two workers, **seven questions** about the same thing.
8. **Coverage below 0.5** → escalate, *"escalated: free answer covered 0.2"*, handing the best material
   over as a starting point.
9. **Otherwise the free answer stands**: the best candidate, plus the runner-up when it is of a
   *different kind* and scores at least 0.6 × the best, plus the fixed line *"If this does not settle
   it, write your best attempt and let the build correct you — that is faster and more certain than
   reading further."*
10. Any model-sourced answer is recorded in the run cache.

**A path or a member question returns the source whole.** Four patterns are matched — a slashed path, a
bare `X.java`, `Type#member`, `Type.member(` — and the answer is the whole file (*"The question names
this file outright, so here it is, whole rather than excerpted."*) or the member's full declaration.
These are tier 0 but are ranked by the **same coverage number**: they win because they contain the
asked-for name, not because they are privileged.

**The run-scoped cache** (`RunAnswerCache`) is keyed on question tokens at the same 0.5 bar, records
only genuinely-answered model answers, is synchronized, and never touches a store. `SwarmDispatcher`
holds one per run in an access-ordered map capped at 200 entries and wires it into every worker's desk
along with that worker's brief words.

## 3.8 `ExpertEscalation` — the expert itself

`sc-knowledge/.../ExpertEscalation.java`. A real agent session with read-only tools.

- **`MAX_TURNS = 30`**, temperature 0.2, session name `expert`. The system prompt tells it its turns
  are limited and that the first real example is enough.
- **Thinking is forced on** (`quirks().withThinking(true)`) — the code calls this the one call in the
  system where reasoning is worth paying for. Everything else about the endpoint is the role's own
  configuration.
- It runs on the injected `KoogAgentRuntime` where there is one, so its investigation is traced beside
  the workers' sessions.
- **Budget.** The system prompt and opening are charged before the session opens; each turn's delta is
  charged in a turn guard that returns `BUDGET_EXCEEDED` on exhaustion. A full refund is issued when
  the session dies of an endpoint outage having generated nothing, and on any exception opening it.
  Exhaustion before opening returns *"The expert could not be asked: this run's model budget is spent.
  Write your best attempt and let the build correct you."*
- **Role: `utility`, falling back to `architect`.** With neither configured the escalation is null and
  the desk says so plainly — *"No expert endpoint is configured for this project, so this could not be
  escalated. That is a setup problem, not a sign that your question was wrong."* — logged once at
  startup as a warning.
- The final answer is JSON-decoded when the model returns a quoted string literal, because the answer
  goes straight to a worker as code and a snippet delivered as one line of `\n` escapes is worse than
  useless.
- A failure sentence distinguishes turn cap, outage, budget and other, each with the turn and lookup
  counts, and **none of them ever reads as "there is no answer, stop"**.
- **A countdown rides on the lookup results** (harness run 37, 2026-09-25). The turn guard tells
  `ExpertTools` which turn it is on, and the first lookup result of a turn carries a note: at the
  halfway turn (*"if you have already read real code that does this, answer now"*), on each of the last
  five turns (*"N turns left"*), and on the turn before the last (*"Your NEXT turn is your LAST: call
  report_done"*). One note per turn; the trace shows it as a NUDGE. Before this the expert had no idea
  its turns were finite.
- **A closing turn when it is stopped with material in hand** (harness run 37). On `TURN_CAP`, on a
  conversation that outgrew its room (`BUDGET_EXCEEDED` with the run's budget *not* spent), or on a
  session that ended with nothing to say, a second short session `expert-closing` is opened:
  `CLOSING_SYSTEM`, the question, the desk's material, and `ExpertTools.whatWasFound` — every lookup
  result that carried material, refusals and "nothing matched" left out, duplicates once, file reads and
  public shapes kept first, folder listings last, at most `CLOSING_MATERIAL_CHARS = 24_000` (scaled by
  the expert's room), printed in the order the lookups were made. Its only tool is `report_done`, its
  cap `CLOSING_TURNS = 2`, and it is charged through the cloud gate like any turn. **It stays
  grounded**: it has nothing to reach for that it was not shown, and it is told to name nothing that is
  not in the material and to say so in one line when the material does not answer. The worker gets
  *"The expert ran out of turns (N turn(s), M lookup(s)) before answering, so it was made to answer from
  what its lookups had returned, and nothing else. Check it against the build."* followed by the
  answer, as a `MODEL` answer. When the lookups found nothing, the budget is spent, or the closing turn
  also says nothing, the original failure sentence stands — there is still never an invented answer.

**There is no per-worker cap on escalations.** The counter exists and is never compared to anything.
(A comment in `ProjectContext` claiming the cap "lives on the instance" is stale; what lives per
instance is the question history that drives the repeat and asked-again rules.) The real bounds are
the run's cloud budget and the 30-turn cap, plus at most two closing turns.

**Why `MAX_TURNS` was not raised** after run 37: the expert had read the answer by its fourteenth
lookup. Thirty turns were enough; they went on folder walking and on looking for a better example with
no clock. More turns would have bought more of the same.

### `ExpertTools`

`MAX_TOOL_CHARS = 6_000` (at the baseline room; scaled by the expert's own model's room, §4.6),
`MAX_REFS = 12`. Every tool is read-only; `list_files` and `read_file` are
confined to the roots and refuse `..` **lexically, before touching disk**. Every call is recorded in
order, with what it returned (that record is what the closing turn is written from). The full list:

`find_implementations`, `find_usages`, `files_using`, `types_annotated_with`, `call_chain`,
`public_shape`, `dependency_declaring`, `lookup_docs`, `list_files`, `read_file`, `skeleton_for`,
`library_docs` (**only** when the documentation server is configured), and `report_done` — whose
return value is the whole answer the worker sees.

`skeleton_for` calls the escalation-free half of the skeleton builder, deliberately, so an expert
cannot recurse into itself.

Three changes from harness run 37 (2026-09-25):

- **`list_files` is a tree** (`KnowledgeCurator.listTree`): `TREE_DEPTH = 4` levels, at most
  `TREE_ENTRIES = 200` lines chosen breadth first (so a cut drops the deepest level, never a sibling
  module, and is announced), and every chain of single folders joined into one line —
  `src/main/java/com/example/store/`. The header spells out one file's whole address as an example of
  joining the lines. Run 37 spent fifteen of 31 turns walking folders one per call. The one-level
  `listFolder` is unchanged: the console chat and the architect use it and were not implicated, and
  workers have no `list_files` at all (they use `exec`).
- **`read_file` on a folder answers with the folder's tree** instead of "not a file".
- **`lookup_docs` never hands one session the same documentation section twice**, the mechanism the
  worker's `lookup_api` has had since 2026-09-04 (`Librarian`'s "already given" sections plus the new
  words of a re-worded question). When everything matching was already shown it says so, names the
  sections, and tells the expert to answer from what it read. Run 37 made thirteen `lookup_docs` calls, most
  of them re-wordings of one question, 3,000 to 8,500 characters each.

The log line for each lookup keeps the argument whole up to 200 characters, then its head and its
**tail**. It used to keep the first 80, and run 37's log showed a `read_file` of
`project/bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server` — exactly 80
characters — which looked like a read of a folder. It was a read of a file below it: a folder then
answered "not a file" in under 200 characters, and this read returned 1,181.

The desk's asked-again rule (§3.7 step 7) does not reach inside an expert session — it is about a
worker's questions to the desk — and it was not extended there: the section memory above is the part
of it that applies to one session's own lookups.

### How the harness wires the expert

`EndToEndLoopTest` builds an `ExpertEscalation` on the harness's own recording client — **the same
free local endpoint the workers run on** — instead of a paid utility or architect role, and hands the
desk a factory for it. The comment is explicit that this makes the harness's expert weaker than
production's, never stronger, and that it is what lets a harness run exercise the whole
`ask_expert` / `request_skeleton` path with no paid call.

## 3.9 `request_skeleton`

The tool's own description: *"ASK FOR A STARTING POINT when you do not know how to begin a file… You
get back a version that compiles, with this project's own annotations, imports and injected fields
already on it and every method body a TODO for you to fill in."* It is one of the tools that keeps
working while reading is paused.

Three tiers:

1. **From a contract.** The ask is matched to one of the task's contracts by exact or simple type
   name, else by token overlap against `typeName + description` at **≥ 0.34**. On a hit, the worked
   example selector runs, and `Skeleton.source(...)` derives names and members from the contract and
   annotations, imports and bean convention from the winning example, with every method body a TODO.
   The answer names the exact path to write it to and says: *"The names and members come from this
   task's own contract, so a later task's tests are written against exactly them — do not rename
   anything."* If nothing cleared the bar it says so, and warns that this carries no framework
   annotations.
2. **The nearest existing file**, returned whole minus its licence header: *"No contract of this task
   names that, so here is the closest existing file in this codebase. Copy its shape and put your own
   names in it."*
3. **Escalate** to the expert.

`Skeleton` also adds to the target module's `pom.xml` the dependencies the example's module declares
and the target's does not, because a skeleton carrying the example's annotations without the artifact
they come from does not compile. A contract whose type already exists in the tree is left completely
alone.

**One wiring gap worth knowing:** the per-project desk in `ProjectContext` is built with an **empty
contract list**, because the design's contracts are a run-level artifact that does not reach that
wiring. In that path `request_skeleton` always falls through to tier 2 or 3.

---

# 4. Runtime and models

## 4.1 Koog

`sc-runtime/src/main/java/com/swarmcoder/runtime/KoogAgentRuntime.java` is the only place a model
conversation is driven. It runs on Koog — root `pom.xml` line 19 sets
`<koog.version>1.2.0</koog.version>`, used for `ai.koog:koog-agents-jvm`. Each session is one Koog
functional-strategy `AIAgent<String,String>`, built in the inner class `KoogSession.buildAgent()`,
whose private `loop()` drives `ctx.requestLLM` → `ctx.executeTools` → `ctx.sendToolResults`.

The class javadoc still says "the Koog 1.0 implementation" (line 38). That comment is stale; the
build and the surrounding comments are 1.2.0, and the 1.2.0 upgrade is what fixed the tool-call
double-encoding bug the older code worked around.

## 4.2 Model shapes — `ModelQuirks`

`sc-inference/src/main/java/com/swarmcoder/inference/ModelQuirks.java` is a 13-field record:
`label`, `maxOutputTokens`, `thinking`, `thinkingKwarg`, `noThinkDirective`, `textualToolHistory`,
`jsonResponseFormat`, `http2`, `servedContextTokens`, `workingContextTokens`, `kvBytesPerToken`,
`maxConcurrentSequences`, `verified`.

`ModelShapes.java` holds four named starting profiles:

| shape id | what it is |
|---|---|
| `generic-openai` | fallback; textual tool history, thinking on, unverified |
| `qwen36-27b` | the measured 2026-07 roster; textual tool history, verified |
| `qwen38-flash-next-125b` | the one shape with `textualToolHistory=false` — native tool calls |
| `vision` | no reasoning switch at all; `thinking=false`, no chat-template argument |

**A model is matched to a shape by an operator-set string, not by detection.**
`AgentModelConfig.shape` is looked up with `ModelShapes.get(id)`; an unknown id falls back to
`GENERIC` with a warning label.

`textualToolHistory` is the only thing that changes how tool calls are replayed:
`KoogAgentRuntime.buildAgent()` applies
`MissingToolsConversionStrategy.All(ToolCallDescriber.JSON.INSTANCE)` **only** when it is true. Tool
*definitions* are always sent natively either way.

## 4.3 Thinking

`ModelQuirks.DEFAULTS.thinking = true` — thinking is on by default. It was turned on on 2026-09-04
after the controlled experiment in `dev/experiment/plain-loop-ceiling`: with thinking off the model
could not fix a `NullPointerException` in 110 shell commands; with thinking on it finished in 10
turns.

Per-role override: `AgentModelConfig` carries `@JsonProperty("thinking") Boolean thinking`, nullable,
where null means "inherit the shape". Resolution order is in `ModelQuirksConfig.resolve()`
(sc-app config package): shape → server-discovered `ServerCapabilities` → block overrides → **the
role's own flag last**, so `thinking: false` on a role wins over everything.

## 4.4 Reasoning is not replayed

`KoogAgentRuntime.KoogSession.dropReasoningFromSessionHistory(...)`, with the pure helper
`withoutReasoningOnLastTurn(List<Message>)`, is called from `ask()` immediately after any model
response carrying a `MessagePart.Reasoning`, before compaction and before the next request. It
rewrites Koog's own session `Prompt` so the `Reasoning` part is dropped from the last assistant
message.

The reason, from the code: a reasoning model never rereads its own previous turn's reasoning — only
the answer and the tool calls matter — so replaying `reasoning_content` is pure wasted prefill.
Koog 1.2.0's `AbstractOpenAILLMClient.convertPromptToMessages` would otherwise send it back.
Covered by `KoogAgentRuntimeReasoningTest`.

## 4.5 `HistoryTrim` — bounding the conversation

`sc-runtime/src/main/java/com/swarmcoder/runtime/HistoryTrim.java`. Constants:

```
HIGH_WATER_PERCENT      = 75
LOW_WATER_PERCENT       = 40
PROTECTED_TAIL_MESSAGES = 8      (about four turns)
MAX_DIGEST_CALLS        = 24
CHARS_PER_TOKEN         = 4      (crude estimate; there is no tokenizer)
MESSAGE_OVERHEAD_TOKENS = 4
MAX_ARGS_IN_STUB        = 160
MAX_ARGS_IN_DIGEST      = 100
```

`trim()` does nothing below 75% of the working context. Above it, two passes run oldest-first:

1. `emptyOldToolOutputs` empties old tool-result bodies in place, leaving a
   `[dropped to save room]` stub that names the tool and its (truncated) arguments. Cheap, and
   reversible by re-running the tool.
2. `removeOldestExchanges`, only if pass one did not reach the low-water mark, removes whole
   balanced assistant/tool-result exchanges from the oldest end — never orphaning a call or a
   result — and replaces them with **one fixed-size digest block**
   (`--- EARLIER PART OF THIS CONVERSATION REMOVED ---`, naming up to 24 of the most recently
   removed calls) written into the opening instruction message. Because the block's size never
   grows with turn count, this is a real ceiling rather than a slower leak.

`Budget.forWorkingContext(int)` computes the two water marks (long arithmetic, to avoid int
overflow above ~28M tokens). `floorTokens()` reports the smallest a conversation could ever be
compacted to: system prompt + opening instruction/digest + protected tail.

`KoogAgentRuntime.compactIfNeeded()` kills the worker with `BUDGET_EXCEEDED` when the conversation
is still above the high-water mark after compaction, or after `MAX_CONSECUTIVE_COMPACTIONS = 3`
compactions in a row that did not help.

## 4.6 How big a worker's room actually is

This is the number that decides whether workers die of context, and it is **not** set by
`maxConcurrentWorkers`.

`ServerCapabilities.derivedWorkingContextTokens(int concurrency)`
(`sc-inference/src/main/java/com/swarmcoder/inference/ServerCapabilities.java`):

```
fairShare    = kvCachePoolTokens / concurrency
withHeadroom = fairShare * WORKING_CONTEXT_PERCENT(90) / 100
cap at servedContextTokens when known
round down to the nearest 1024
```

Both `kvCachePoolTokens` (`max_total_num_tokens`) and `concurrency` (`max_running_requests`) are
read from the model server's own `get_server_info` endpoint — an SGLang endpoint. The divisor is the
**server's** concurrency limit, because that is what actually shares the KV cache. SwarmCoder's own
`swarm.maxConcurrentWorkers`, and the harness's `-Dswarmcoder.e2e.workers`, never enter this
arithmetic; they only decide how many already-sized workers run at once (`AdaptiveConcurrency`).

The measured August 2026 numbers, recorded in `ModelShapes`: KV pool 462,103 tokens ÷ 8 concurrent
requests = 57,762, × 90% = 51,985, floored to a multiple of 1024 → **51,200 tokens per worker** —
against a served single-request ceiling of 262,144.

Production reaches this through `DependencyGraph`'s `workerFamilies` loop
(`ServerCapabilities.forEndpoint()` → `AgentModelConfig.resolvedQuirks(caps)` →
`ModelQuirksConfig.resolve(...)`). The harness reaches it through
`sc-app/src/test/java/com/swarmcoder/app/HarnessModelBudget.java` `discover()`. These are the same
code path, not two implementations of the same formula.

Since 2026-09-25 the workers are DeepSeek V4 Flash on the Spark (`ModelShapes`
`deepseek-v4-flash-ds4`): served context 1,048,576, **262,144 tokens per worker** — a chosen room, not
one the server reports, because ds4 has no `get_server_info` page.

### What the room buys — reference material scales with it

**Added 2026-09-25.** Every cap on what the librarian, the help desk, the expert and the architect hand
a model was a constant measured on the Qwen workers at 51,200 tokens. The room grew five-fold and the
material did not, so a DeepSeek worker was handed a brief and lookup answers sized for a model that
could hold a fifth as much. `sc-inference/.../MaterialBudget.java` is now the one rule they all use:

```
chars(baseline) = baseline × clamp(room, 51,200, 262,144) / 51,200
```

- **Linear**, so every measured share holds: the brief ceiling stays 8.8% of the room, a lookup answer
  about 3% of it.
- **Never below today's figures.** 51,200 is the room every constant was measured in. A smaller room
  keeps today's numbers, because the cloud roles (architect, utility) run on the generic 32,768-token
  shape today with exactly these figures, and shrinking them would change a working path with no
  measurement behind it.
- **Ceiling 262,144** (×5.12). The largest room any shape has given a worker; beyond it nothing is
  measured, and the material runs out anyway (the worked example's neighbour walk stops at 60,000
  bytes, zeroz4j's whole catalogue is about 7,000 characters). A 1M-token room scaled linearly would
  prefill a ~92,000-token brief into every worker and decode against it every turn. Watch the Spark's
  decode speed at depth before raising it.

**Whose room.** The reader's. The brief, a worker's `lookup_api` and the desk's answers use the
workers' room — the smallest worker family's, since one brief is shared by every worker of a task
(`ProjectContext.workerRoomOf` → `Librarian.sizedFor`, `ExpertDesk.sizedFor`; the harnesses do the same
from `HarnessModelBudget`). `WorkerToolbox` cuts a `lookup_api` result at each worker's own room
(`WorkerLoop` passes its endpoint's quirks). The architect's reference and research results use the
architect client's own quirks, and the wiring answers its research lookups at that size. The expert's
tool results and the context the desk hands it use the expert role's quirks
(`ExpertEscalation.room()`). The framework primer's input uses the primer model's (utility role's)
room, and its cache key carries the input size when it is not the baseline's.

| cap | where | at 51,200 (and below) | at 262,144 (and above) | sized by |
|---|---|---|---|---|
| `MAX_BRIEF_CHARS` | Librarian | 18,000 | 92,160 | workers |
| `WORKED_EXAMPLE_CHARS` | Librarian | 14,000 | 71,680 | workers |
| `DOC_MAP_CHARS` / `DOC_MAP_CHARS_BESIDE_AN_EXAMPLE` | Librarian | 3,000 / 900 | 15,360 / 4,608 | workers |
| `DOC_SLICE_CHARS` | Librarian | 3,500 | 17,920 | workers |
| `PRIMER_CHARS` | Librarian | 3,000 | 15,360 | workers |
| `SOURCES_CHARS` | Librarian | 6,000 | 30,720 | workers |
| `CURATED_CHARS` (was an unnamed 5,000) | Librarian | 5,000 | 25,600 | workers |
| `LOOKUP_CHARS` / `DOC_CHARS` | Librarian | 6,000 / 3,000 | 30,720 / 15,360 | the reader |
| `MISS_MAP_CHARS` (was an unnamed 1,200) | Librarian | 1,200 | 6,144 | the reader |
| `MAX_TOOL_OUTPUT_CHARS`, `lookup_api` only | WorkerToolbox | 8,000 | 40,960 | that worker |
| `ANSWER_CHARS` | ExpertDesk | 4,000 | 20,480 | workers |
| escalation context (free / sources / docs) | ExpertDesk | 3,000 / 6,000 / 4,000 | 15,360 / 30,720 / 20,480 | expert |
| `MAX_TOOL_CHARS` | ExpertTools | 6,000 | 30,720 | expert |
| `REFERENCE_CHARS` | ArchitectClient | 9,000 | 46,080 | architect |
| `RESEARCH_RESULT_CHARS` | ArchitectClient | 6,000 | 30,720 | architect |
| research `read_file` | ProjectContext | 12,000 | 61,440 | architect |
| `PRIMER_INPUT_CHARS` (per guide / per example) | KnowledgeCurator | 26,000 (6,000 / 4,000) | 133,120 (30,720 / 20,480) | primer model |

Three per-item caps inside the curator became **floors with a fair share**: a documentation section
(`MAX_SECTION_CHARS` 3,500), a source file in `relevantSources` (4,500) and a README in `conventions`
(6,000) may each be as long as their share of the caller's budget, and never shorter than before. At
the baseline every caller's share is below the floor, so nothing changes there; without this the
scaled budgets above could never have been filled.

**Deliberately not scaled:**

- **Counts** — `MAX_LIBRARIES`, `SOURCE_FILES`, `MAX_NAMED_FILES`, `MAX_REFS`, `PRIMER_MAX_FILES` and
  `RESEARCH_ROUNDS`. Rounds bound latency at DESIGN, and each round's room already scales through
  `RESEARCH_RESULT_CHARS`; scaling both would grow the research conversation by the square of the
  factor.
- **Ranking constants** — `REFERENCE_SECTION_CHARS` (the length penalty in `ReferenceIndex`) and the
  8,000-character window `ExpertDesk.coverage` scores. A ranking that depended on who is asking would
  give a worker and the expert different answers to the same question, and would silently re-tune
  orderings measured at these values.
- **Shaping thresholds** — `MIN_SHAPED_CHARS`, `SourceShaper`'s `WHOLE_FILE_CHARS` / `MEMBER_CHARS` /
  `NOTE_CHARS`, `WorkedExamples.PROSE_CHARS` and `NEIGHBOUR_BUDGET_BYTES`: they decide what is worth
  showing, not how much fits.
- **`ChangeNeighbourhood.forWorkingContext`** is unchanged: it already follows the room downward, and
  its content is bounded by counts (six types, two shapes, ten usages, six tests) before 6,000.
- **Anything a human or a log reads** — `MAX_LOGGED_COMMAND_CHARS`, `PendingExecRecorder`, the
  console's snippets, `ReplyBudget`, `RuleConflictFeedback`.
- **`WorkerToolbox` `read` and `exec`** keep the fixed 8,000. Whether a bigger room should see more of
  a file or a build log is a separate decision: the repeat check and the read-pause both count what a
  look returned.
- **The judge** (`JudgeClient`), the chat and analyst roles, the post-run extractors and
  `ProjectRules`: different readers on their own models, outside the worker/architect/expert path.

## 4.7 Roles

Roles are configured in `~/.swarmcoder/config.yaml`, mirrored by `RolesConfig`. Names and jobs only
— the file also holds credentials, which are never quoted anywhere in this repository's docs or
logs.

| role | what it does |
|---|---|
| `architect` | designs, and plans (`ArchitectClient`) |
| `designReviewer` | reviews designs and plans (`DesignReviewerClient`) |
| `testAuthor` | writes and repairs acceptance tests (`TestAuthorClient`) |
| `judge` | scores candidates (`JudgeClient`) |
| `librarian` | backs the worker's search and lookup tools (`Librarian`) |
| `approver` | signs off decisions such as a budget extension |
| `vision` | reads requirements out of uploaded images; **no fallback** — an image upload is refused outright if it is unset, so a text model never "describes" a picture it never saw |
| `utility` | generic fallback; the expert desk's escalation runs here |
| `workerFamilies` | a **list**, not one role: the coding-worker endpoints the swarm dispatches to |
| `chat` | the Console's chat coder; falls back to `utility` |
| `requirementsAnalyst` | writes the requirements graph; falls back to `chat` |
| `storyPlanner` | slices requirements into stories; falls back to `chat` |
| `taskPlanner` | splits an accepted design into tasks and orders them (the plan part of `ArchitectClient`); falls back to `architect`, the model it always ran on |

The last three are absent from the current config file, so they are running on their fallbacks.

## 4.8 `CloudGate`, and why a harness run cannot spend money

`sc-runtime/src/main/java/com/swarmcoder/runtime/CloudGate.java` enforces **spend**, not
destination. It holds `Budget.maxCloudTokens` for the run; `charge(long)` accumulates and throws
`BudgetExhaustedException` past the cap, firing `onExhausted` once (which parks a
`BUDGET_EXTENSION` decision); `refund()` returns a charge for a call that never reached the model,
so outage retries do not eat the budget. A cap of `<= 0` disables enforcement, and says so in the
log. Token counts are estimates (`chars / 4`).

**Destination** is enforced separately and technically, in
`EndToEndLoopTest.refuseAnythingButAFreeLocalEndpoint()`: the endpoint comes from the system
property `swarmcoder.live.baseUrl` (model from `swarmcoder.live.model`), and the test throws an
`AssertionError` unless the scheme is plain `http` **and** the host is loopback or a private range
(`localhost`, `127.*`, `10.*`, `192.168.*`, `172.16–31.*`). It refuses to start rather than risk a
billed call. Nothing about this is advisory.

Test gating generally is opt-**out**, not opt-in: `@RunsWhen` (`sc-testsupport`) replaced
`@EnabledIf…` after thirteen test classes were found to have been silently skipped for weeks. Every
skip is announced in a banner and in `target/tests-not-run.txt`. The paid-cloud need
(`PAID_CLOUD_MODELS`) is switched on only by `SWARMCODER_CONFIG_E2E=true`.

---

# 5. The harnesses

## 5.1 `EndToEndLoopTest` — the greenfield chain

`sc-app/src/test/java/com/swarmcoder/app/EndToEndLoopTest.java` (1,804 lines), with `ChainLedger`,
`BookshelfFixture`, `HarnessReferenceRoot`, `HarnessRunBudget`, `HarnessModelBudget`,
`DeliveredCodeIsWhatPassedCheck`, `PlanTaskLinkageCheck`, `WriteSetLinkageCheck`, and — for saving
and resuming — `DispatchSeam`, `HarnessSnapshot` and `SnapshotStaleness`, and for watching —
`HarnessWindow` beside it.

Gated by `@RunsWhen({Need.LIVE_MODEL, Need.MAVEN})`. A skip is announced on stderr and appended to
`target/tests-not-run.txt`; `Assumptions.assumeTrue` is banned in this codebase.

**The command, from the class javadoc:**

```
mvn -o test -pl sc-app -am -Dtest=EndToEndLoopTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dswarmcoder.live.baseUrl=http://192.168.0.10:8000/v1 -Dswarmcoder.live.model=deepseek-v4-flash \
  -Dswarmcoder.live.shape=deepseek-v4-flash-ds4
```

Since 2026-09-25 the Spark serves DeepSeek V4 Flash (ds4 server, port 8000) instead of Qwen 3.8
on port 8002. `swarmcoder.live.shape` is required for it: without it the harness's roles ask for
`json_object`, which ds4 refuses with HTTP 400. The shape and what was measured are in
`ModelShapes` under `deepseek-v4-flash-ds4`.

`refuseAnythingButAFreeLocalEndpoint()` hard-fails unless that endpoint is plain HTTP on a private
address. The operator's `~/.swarmcoder/config.yaml`, which points seven roles at a billed account, is
never read.

Knobs: `-Dswarmcoder.e2e.workers` (default 2), `.turns`, `.minutes`, `.waves` (1), `.maxMinutes`
(180), `.requirement=<substring>`, `.reference=<path>`, `.fixture=<path>`, `.flowMinutes` (10),
`.saveAtBuild=<dir>`, `.resumeFrom=<dir>`, `.observe` (true), `.consolePort` (9090), `.mcpPort` (8931),
`.holdMinutes` (0) (all below).

### Watching a harness run live (2026-09-25)

Both walks — full and resumed — serve the product's own Console and its own MCP server over the
harness's temp store as soon as `wire(...)` has run (`HarnessWindow`), and print one line each:

```
[E2E] >>> WATCH IN A BROWSER: http://localhost:9090/   (this run's own store; watch-only)
[E2E] >>> WATCH OVER MCP (read-only): http://127.0.0.1:8931/mcp
```

- **On by default**, off with `-Dswarmcoder.e2e.observe=false`. The harness is only ever run on
  purpose, by somebody who then wants to see it; a flag nobody remembers to type is what
  `docs/TESTING.md` was written against. It weakens neither promise: the Console's context has only
  the harness's free local clients, no settings seam and no chat, so no click can reach a billed
  model or the operator's `config.yaml`; everything shown is the temp store; the MCP port is loopback
  only; and nothing else in the ordinary build opens these views.
- **The app's own ports**, `-Dswarmcoder.e2e.consolePort` / `.mcpPort` to move them. The app is never
  running during a harness run (they compete for the model server), and the Claude session's MCP
  client is already pointed at 8931. A taken port prints `[E2E] !!! NO BROWSER VIEW` or
  `NO MCP VIEW` with the port and the property to change, and the walk carries on — observability
  never breaks the chain. (Measured: `Zeroz4jServer.start` does not throw on a taken port; it returns
  a server whose `port()` is -1, so `HarnessWindow` checks that.)
- **Watch-only.** The MCP server is built read-only, so `start_run`, `decide_run` and
  `answer_decision` are not offered. The Console's context is `ConsoleContext.watchOnly(...)`: a
  browser asking to start a build, approve or reject a run, answer a question, run either wizard,
  change a rule, switch on unattended building, or create or delete a project gets the reason on
  screen. The harness drives its run through those same services, so the refusal is by caller: ZeroZ's
  RMI dispatcher puts the WebSocket session on the thread for each browser call
  (`RmiRequestContext`), and the harness's own threads carry none. Editing a requirement or a story by
  hand is not refused.
- **What the browser shows**: the harness's project (now listed in the rail — before this its context
  listed no projects, so readiness was empty and nothing was scoped to it), its requirements and
  stories, the run graph with live workers, and transcripts of finished sessions — the harness's trace
  hub is now built by `DependencyGraph.traceHubOver`, the app's own, so finished sessions reach the
  store and the run's heartbeat is stamped. Payloads above the inline cap go to blobs in the temp
  directory. **Not shown**: Settings and model endpoints (not wired), the chat, and full payloads of
  sessions from a snapshot's saving run (its blobs are not in the snapshot).
- **Until the walk ends**, on a verdict or a break, then both close and the ports are free.
  `-Dswarmcoder.e2e.holdMinutes=N` prints the chain report and keeps them up N more minutes first,
  with the store still open: once the walk ends its temp store is deleted and there is nothing left to
  look at.

### Saving the front half and starting from it (2026-09-25)

On DeepSeek V4 Flash, links 1 to 9 — everything before the workers — take 20 to 40 minutes and come
out nearly the same every run. When the work being iterated on is the workers, pay for them once:

```
# once: a full walk that also saves a snapshot the moment the run is about to dispatch workers
mvn -o test -pl sc-app -am -Dtest=EndToEndLoopTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dswarmcoder.live.baseUrl=... -Dswarmcoder.live.model=... -Dswarmcoder.live.shape=... \
  -Dswarmcoder.e2e.saveAtBuild=G:/snapshots/rate-a-book

# then, as often as needed: links 10 to 16 only, from a fresh copy of that snapshot
mvn ... -Dswarmcoder.e2e.resumeFrom=G:/snapshots/rate-a-book
```

- **Where it is saved.** `DispatchSeam` subclasses `SwarmEngineImpl` and does its work inside
  `executeRun`, before calling the real one. That call is the first thing `GreenfieldWorkflow` does
  in EXECUTING, right after the TEST_AUTHORING → EXECUTING transition was persisted, on the only
  thread driving the run — so nothing moves while the snapshot is taken. A subclass, not a wrapper:
  the workflow checks `instanceof SwarmEngineImpl` to wire the per-wave red check and test-repair
  re-verification. Nothing in the product changed except one method on `ArtifactStore`.
- **What is saved** (`HarnessSnapshot`): `store/` — `ArtifactStore.backupTo`, EclipseStore's own
  full backup of the open store, run on the store's single writer thread so it is queued behind every
  write the run made (a raw copy of files EclipseStore is appending to is not a store); `repo/` — the
  fixture repository with the run's `swarm/tests/<runId>` commit, minus `.git/worktrees`; and
  `manifest.json` — run, project and story ids, SwarmCoder commit plus any uncommitted files, date,
  endpoint, model, shape, worker count, the fixture's origin and base commit, the tests commit, and
  links 1 to 9 with their observations. Links 7 to 9 are measured at the seam by the same code the
  walk uses after the run. Nothing is saved unless all nine held, an existing snapshot is never
  overwritten, and a save writes to `<dir>.partial-*` and renames only when whole. The full walk then
  carries on to link 16. The reference-docs index is not saved; it is rebuilt from the reference
  checkout with no model call.
- **How it resumes.** The snapshot is copied into the test's temp dir (it is only ever read, so
  every resume starts from the same state), the project is repointed at the restored repository,
  everything is wired by the same `wire(...)` method the full walk uses, and the run is handed to
  `RunResumer.resumeAll()` — the app's start-up path — then `reconcileStrandedStories()`. Links 1
  to 9 print `[CHAIN saved]` with the saving run's observation and where it came from; the verdict is
  `CHAIN WHOLE FROM A SNAPSHOT`, never "all 16 links held". Link 10 is walked, not restored: its
  evidence is the candidates' acceptance results, which exist only after the workers run.
- **Worktrees.** Git records linked worktrees by absolute path in both directions. A restored
  repository that kept the saving run's registrations would think it owned the original run's
  checkouts under `~/.swarmcoder/wt`, and the product's `git worktree remove --force` before reusing
  a name would delete them. So registrations are never carried. Checkouts named with this run's id
  whose repository is gone (left by an earlier resume) are deleted, because the product reuses those
  names (`progress-<runId>`, `tests-<runId>`) and `git worktree add` refuses an existing folder. One
  whose repository still exists stops the resume: another copy of the run may be going.
- **When a snapshot is stale.** A resume prints a loud warning — not a refusal — when the checkout
  differs from the snapshot's commit (committed since, uncommitted now, or uncommitted then) in
  anything that shapes links 1 to 9: `sc-console`, `sc-workflow`, `sc-knowledge`, `sc-verify`,
  `sc-domain`, `sc-store`, `sc-inference` main sources, the harness's front-half classes, and the two
  bookshelf documents (`SnapshotStaleness.FRONT_HALF`, deliberately generous; the warning names every
  file). It also warns when the model or shape differs. The plan and tests are then what an older
  product made; save a new snapshot when that matters. A snapshot whose chain names no longer match
  this harness's links, or whose format differs, is refused.
- **Brownfield.** `BrownfieldLoopTest` does not support either property: its 17-link chain, case
  clones and wiring are its own, so it is not a trivial extension.

> **Do not use `dev/e2e-loop.ps1`.** It is stale: it still passes `-Turns 12` and `-Minutes 25` and
> claims the product default is 30. Twelve turns starved every worker on 2026-08-31, and 25 minutes was
> sized for a turn cap that no longer exists. Running the script reproduces the two failures the
> harness was changed to stop having. Use the javadoc command.

### The fixture, and the deliberately missing dependency

`BookshelfFixture.create()` clones `dev/bookshelf-demo` into the temp directory — never the original,
which carries hundreds of `swarm/*` branches. It then deletes every `src/test/java/swarm` tree and the
root aggregator's orphan `src/`, and commits *"Baseline: no acceptance tests"*.

The missing dependency is **`com.zeroz4j:zerozstack-store-eclipsestore`**, absent from the demo's
server pom. The demo repository's own HEAD commit (2026-09-03) is titled *"The server pom no longer
declares the store module by hand: the swarm must declare it itself"* — it reverts the commit that had
added it. Meanwhile `dev/bookshelf-tech-requirements.md` says persistence uses EclipseStore **through
`zerozstack-store-eclipsestore`**. So the machine must plan the enabler that edits the pom, and that is
why the write-set link accepts a module's build file with no source under it.

### Budgets

- **`HarnessReferenceRoot`** builds the harness's `Librarian` with the production constructor but with
  the docs index and primer cache inside the test's temp directory, never `~/.swarmcoder`. The default
  reference root is `C:/work/zeroz4j`. **It is required**: a missing directory throws, and the walk
  reports that as a break at link 1. It exists because run 4's nudge log read *"steering it to start
  (reference material: none)"* on every wave-3 worker — nothing had ever built a `Librarian`, so
  `lookup_api` answered from an unavailable stub and no knowledge brief was attached to any task.
- **`HarnessRunBudget`**: `LINKS_OVERHEAD_MINUTES = 10`, `OBSERVED_SECONDS_PER_TURN = 90`,
  `DEFAULT_MAX_MINUTES = 180`. Build and repair parallelism cancel their worker counts, so each reduces
  to waves × turns × seconds-per-turn. At defaults: 180 + 180 + 10 = 370 minutes, capped to 180.
  `-Dswarmcoder.e2e.minutes` still wins outright. Written against run 12, which fired its 25-minute
  deadline at 1,501 seconds with both workers alive and writing, and run 22, which stopped at 90
  minutes with four repair workers still running.
- **Turn allowance.** There is no `HarnessTurnAllowance` class; the seam is
  `EndToEndLoopTest.resolveTurnAllowance()`, and unset means `TurnAllowance.BUILT_IN_MAX_TOOL_TURNS =
  120`. The harness used to hardcode 24, and run 11 killed two workers at exactly turn 25 with
  `BUDGET_EXCEEDED` and no compaction line at all — which is what split `TURN_CAP` out as its own kill
  reason.
- **`HarnessModelBudget`** runs production's own capability discovery against the live endpoint and
  **throws** if the endpoint answers nothing, rather than falling back to the 32,768-token default.
  Written after run 9, where both workers on the persistence task compacted (26,069 → 13,258 tokens at
  turn 15; 24,695 → 16,904 at turn 17) because a four-argument constructor had silently defaulted the
  window.

### The chain

Grepping for `[CHAIN]` finds nothing — `ChainLedger` prints `[CHAIN ok  ]`, `[CHAIN FAIL]` and, in a walk
resumed from a snapshot, `[CHAIN saved]`; the links are the `L_*` constants. **There are sixteen** (the class javadoc's opening sentence still says
twelve).

| # | link | holds when |
|---|---|---|
| 1 | `L_FIXTURE` | a pristine copy of the demo exists, with a determined layout and compiling modules |
| 2 | `L_INGEST` | both documents ingest and each yields over 200 characters |
| 3 | `L_RULES` | the technical document becomes at least one project rule |
| 4 | `L_REQS` | requirements are drafted and **every one** carries checks |
| 5 | `L_AGREED` | one requirement is agreed, every criterion accepted, and every check names a test |
| 6 | `L_STORIES` | stories cover every agreed criterion, and none claims one from a draft requirement |
| 7 | `L_TASKS` | every agreed check is claimed, and no task was planned for nothing — checked against the **stored** graph, not the one the planner was shown; an enabler claiming nothing is legal if something depends on it |
| 8 | `L_WRITESETS` | every task writes only to a compiled source root, or to the build file of a compiled module |
| 9 | `L_TESTS_WRITTEN` | the run's tests commit holds files under the acceptance write dir, with no strays and nothing loose in the checkout |
| 10 | `L_TESTS_RUN` | the acceptance stage executed a non-zero number of tests |
| 11 | `L_CANDIDATE` | a worker produced a candidate whose diff changes a file |
| 12 | `L_VERIFIED` | at least one candidate carries a verification report — a candidate with none "was never compiled and never tested, and survives verification automatically" |
| 13 | `L_JUDGE` | the judge was told whether the candidate was verified, and did not say "not run" while reports existed |
| 14 | `L_WINNER` | a winner was selected, or a clear reason given |
| 15 | `L_INTEGRATED` | a non-blank integration commit whose merged files include something under `src/main/` |
| 16 | `L_PROVES` | **the story's acceptance test is red on the tree before the delivered commit and green on it** |

`ChainLedger.require(name, held, observation)` takes one observation string deliberately, so it reads
as a measurement either way. A break throws, and every later link prints as `NOT REACHED` — never as a
pass. The three verdicts are `CHAIN BROKE AT`, `CHAIN INCOMPLETE — n of m links walked` and
`CHAIN WHOLE — all 16 links held, document to merged commit`.

### The false pass of 2026-09-05

**This is not in `docs/DEVELOPER_CORRECTIONS.md`** — that document stops at §39, dated 2026-08-29. The
account lives in the javadoc of `DeliveredCodeIsWhatPassedCheck` and in commit `1a1bba0`.

Harness run 30, story "rate a book". Quoting the javadoc: the acceptance test *"wrote its own anonymous
implementation of the `BookService` contract and asserted against that. It failed on both candidates
for a bug in its own field-initialisation order, was 'repaired' by moving three lines, and then passed
for both — with no candidate's code ever executed. At 13:10 the story was stamped delivered on it, and
every link in this harness's chain was green."*

Four things now catch it — three in the product, one in the harness:

1. **At authoring** — `SelfImplementedContract` (§1.11) rejects an anonymous class, lambda, nested
   class, subclass, mock or stub of a contract type; the author is told once how the application
   obtains a real one, and a second miss parks the run.
2. **After a test repair** — the correction is checked the same way, and the red check is re-read: a
   repaired test that is green on the tree before any candidate is a tautology, sent back once and then
   parked (§1.14).
3. **At verification** — an acceptance pass counts only when the same tests were not already green
   without the candidate's diff, reusing the run's own pre-change execution (§1.13). "Green because
   earlier waves delivered it" stays a fact, not a fault, and an enabler is untouched.
4. **In the harness** — link 16. `DeliveredCodeIsWhatPassedCheck.provesTheDeliveredCode` adds a
   detached worktree at the delivered commit's **parent**, writes the run's acceptance files onto it
   from the tests commit, and runs the suite once; the delivered side is free in the ordinary case,
   reused from a winner's own verification. It holds only when the before side is red and the
   delivered side green. Green-on-both produces: *"The acceptance test passes WITHOUT the delivered
   code… A test like that goes green for every candidate and for no candidate alike: it measures
   itself. The story was stamped delivered on nothing."* Red-on-both and neither-nor have their own
   sentences. **Until 2026-09-25 the parent was asked for as `<sha>^`**, and the harness runs git
   through `cmd.exe`, which eats a trailing caret: the "before" tree was the delivered commit itself,
   so a real delivery measured green on both sides and link 16 could not hold. It now asks for
   `<sha>~1`; `HarnessSnapshotTest` pins both answers.
   **Until 2026-09-26 "the winner" meant whichever SELECTED candidate came first by worker index.**
   Run 41 planned four tasks for one story; only the last ('Create persistent
   BookshelfServiceImpl') claimed the story's acceptance criteria, so only its winner had ever been
   handed the story's acceptance test. The other three tasks were enablers, and their winners'
   verification reports correctly said "0 acceptance tests executed" — they were never given the
   file. Link 16 picked the first winner it found regardless of which task it belonged to, landed on
   an enabler, and reported *"0 test(s) executed, 0 failing — NOT GREEN"* for a story that had, in
   fact, been delivered: the real acceptance run — 1 executed, 0 failed — was sitting on the fourth
   task's winner. `DeliveredSideSelection` now finds the delivered side from the SELECTED
   candidate(s) of the task(s) whose `criterionIds()` is non-empty — the story's own criteria, not
   any candidate's — combining more than one such task's report when a story spans several, and,
   when none of them has a matched winner carrying an acceptance report at all, running the suite a
   second time directly on the delivered commit rather than reusing a report that never answered the
   question. The comparison itself is proved without a repository or a model by
   `DeliveredCodeIsWhatPassedCheckTest`; the selection is proved the same way by
   `DeliveredSideSelectionTest`.

## 5.2 `BrownfieldLoopTest` — on branch `brownfield` only

Everything in this section is on branch **`brownfield`**, head `e975d91`, and on no other branch. The
short-lived wave branches (`brownfield-w1-ground`, `-w2-understanding`, `-w3-end-to-end`) no longer
exist; only their commits survive on `brownfield`.

**`brownfield` branched before master's last two commits**, so it does **not** contain the 2026-09-05
delivered-code work: no `SelfImplementedContract`, no `DeliveredCodeIsWhatPassedCheck`, and its
greenfield chain is still 15 links with no `L_PROVES`. That merge is outstanding.

### The plan documents

- `docs/plans/brownfield/Design.md` (954 lines, status *"DESIGN. Nothing here is built"*). Nine
  sections: intake, understanding before planning, the gate, verification against the human fix, the
  harness, what stays shared versus forks, console, six waves of work, and six open decisions. The
  owner's stated reason, quoted in it: *"the product is aimed at understanding and writing corporate
  code bases, not javascript snake games. so the workers have to be able to work on unseen code and
  apis."* Its §0 argues most of it already exists — the delivery half is target-agnostic, `BUGFIX` and
  `ENHANCEMENT` already run through `GreenfieldWorkflow`, and the unknown-repository on-ramp is already
  tested. Three genuine gaps: a change request has no front half, nobody reads the neighbourhood, and
  the regression gate is all-or-nothing.
- `docs/plans/brownfield/Ledger.md` (496 lines on `brownfield`) — branch ledger, per-wave entries, a
  case-results table, and a table of decisions that contradict the design.

### There is no brownfield workflow class

`ChangeRequestIntake` (`sc-console`, 604 lines) turns **one bug report into one agreed requirement**
carrying one to three checks each naming its test, one story, and one run. `MAX_ISSUE_CHARS = 20_000`.
It exists because `RequirementsIntake` wants documents nobody writes for a bug report, and `AdHocStory`
produces a story with no criteria at all — which turns off the coverage invariant, criterion evidence,
the zero-tests-executed rule and delivery stamping any check.

**One model call and never a question**: anything the analyst cannot settle becomes a stated assumption
on the requirement. `read()` makes the call and writes nothing; `start()` writes requirement → checks →
agree → story → run, bound to the story from the instant the engine gets it.

The run is an ordinary `BUGFIX` or `ENHANCEMENT` run created at `INTAKE`, so **from `DESIGN` onward it
is byte-for-byte the greenfield path**.

### The seventeen links

`WHOLE_CHAIN` = three ground links + two understanding links + twelve delivery links.

1. `L_TREE` — the target is checked out at the parent of the known fix, and the fix is not on it
2. `L_CONTRACT` — the build and test contract is detected and really builds this repository
3. `L_SUITE` — the project's own suite is green before anything starts
4. `L_NEIGHBOURHOOD` — the neighbourhood of the change names real types, with files and lines
5. `L_REQUIREMENT` — the change request becomes one agreed requirement carrying checks
6. `L_CONTRACTS` — the architect's design states contracts naming types that exist
7–14. `L_TASKS`, `L_WRITESETS`, `L_TESTS_WRITTEN`, `L_TESTS_RUN`, `L_CANDIDATE`, `L_VERIFIED`,
`L_WINNER`, `L_INTEGRATED`
15. `L_SWARM_TEST` — the swarm's own test passes on the merged tree
16. `L_HUMAN_TEST` — **the maintainer's test passes on the merged tree**
17. `L_WHOLE_SUITE` — the project's whole suite is green on the merged tree

There is no judge link here. Note the neighbourhood is *walked* before the requirement (the analyst is
shown it), while the design document numbers them the other way round.

**Two test methods with very different cost.** `theGroundUnderThisCaseIsSolid` is parameterised over
every case, walks links 1–3, and needs no model (`@RunsWhen(Need.MAVEN)`).
`theWholeChainWalksFromOneIssueToTheMaintainersTest` walks all seventeen for **one** case and needs a
live model.

```
mvn -o test -pl sc-app -am -Dtest=BrownfieldLoopTest#theGroundUnderThisCaseIsSolid
mvn -o test -pl sc-app -am -Dtest=BrownfieldLoopTest \
    -Dswarmcoder.live.baseUrl=http://192.168.0.10:8002/v1 -Dswarmcoder.live.model=qwen3.8-27b
```

Knobs: `-Dswarmcoder.brownfield.case=2187`, `.targets=<dir>`, `.suiteMinutes` (30), plus the
`swarmcoder.e2e.*` budgets.

### The cases and the clone

`BrownfieldCases` reads `dev/brownfield/jsoup-cases.json`. **Issue text is stored, not fetched**, so the
harness runs offline and a case cannot change if someone edits a GitHub issue. `EASIEST_CASE = 2266`,
and the end-to-end walk defaults to it and **throws rather than run more than one**, because the
workers share one GPU.

| issue | kind | file the maintainer changed | the maintainer's test |
|---|---|---|---|
| 2197 | bug | `HtmlTreeBuilderState.java` | `HtmlParserTest#tableInPInQuirksMode` |
| 2187 | bug | `StructuralEvaluator.java` | two `SelectorTest` methods |
| 2266 | bug | `Document.java` | `DocumentTest#charsetOnEmptyDoc` |
| 2476 | bug | `Cleaner.java` | three `CleanerTest` methods |
| 2105 | enhancement | `Tag.java` | `ElementTest#buttonTextHasSpace` |

The case file also carries an `existingCorrection`: the suite is run excluding
`org/jsoup/integration/**`, with the reason stated — measured 2026-09-05 on JDK 25, six integration
tests fail on the untouched tree and the same class passes on recent jsoup master, so this is the age
of the tree against the JDK and not anything a candidate could do. The exclusion must be a **path**
pattern; a dotted package name silently matches nothing. Excluding leaves 1,162 tests green.

**`BrownfieldTarget`** clones jsoup once into `~/.swarmcoder/targets/<name>`, with a per-case tree at
`~/.swarmcoder/targets/<name>-cases/<issue>`. Deliberately not under `C:/work/worktrees` (that is for
our own repositories), not inside the SwarmCoder checkout (a 30,000-line tree would be indexed and
accidentally committed), and not a temp directory (the clone should be paid for once). The clone is
idempotent and full, not shallow, because the cases name commits years apart. **Case trees are clones
of the clone, not linked worktrees** — see the wave-3 finding below. Every case tree is cut at the
fix's **parent**, so neither the fix nor the maintainer's test is on it.

**`TargetRepository`** (`sc-app/src/main/java`, the only production class of wave 1) does four things in
one call: detect a build and test contract from the build files, **actually run the proposed compile
command once**, render commented YAML, and write the contract into the repository **and commit it** —
because an uncommitted contract is in no worker's worktree, so the run loads nothing, every candidate
comes back unverified, and the winner is picked on a reading of the code alone. A red probe writes
nothing. `DEFAULT_PROBE_TIMEOUT_SECONDS = 900`.

**`ChangeNeighbourhood`** (`sc-knowledge`, 626 lines, production) is what a change request is asking
about, read out of the code it is asking about: five queries against the structural index, rendered
with a file and line number on every line, never a model's opinion. `MAX_CHARS = 6_000` (about 1,500
tokens, `SHARE_OF_CONTEXT = 0.029`), `MAX_TYPES = 6`, `MAX_SHAPES = 2`. It fails open — an unavailable
index or an issue naming nothing produces an empty neighbourhood carrying the reason. Two measured
departures from the plain `namesKnownIn`: every dotted segment is resolved (otherwise
`Jsoup.parseBodyFragment(html)` yields only the method name and the type is lost), and the types that
*declare* the methods an issue calls are included (otherwise `doc.select("div:has(span + a)")` names no
project type beyond `Document`).

**`HumanFixOracle`** (575 lines, **test-only on purpose** — "the product must never contain code that
reads a known-good answer"). After the run it cuts a scratch worktree from the swarm's integration
commit, restores the swarm's own acceptance tests, runs them, then applies **only the test-file half**
of the maintainer's fix commit — refusing outright if that restricted diff touches anything else —
runs that class, then runs the whole suite.

**The pass rule: all three must hold** — the swarm's own test passes, the human's test passes, and the
whole suite is green. Outcomes are named: `FIXED_THE_THING`, `FIXED_SOMETHING_NOT_THE_THING`,
`ORACLE_UNAPPLIABLE` (*"this is not a pass"*), `DID_NOT_FIX_IT`, `BROKE_SOMETHING_ELSE`,
`NEVER_DELIVERED`. **Files touched is reported and never gated** — more than one correct fix exists for
most of these issues.

Isolation is proved by `TheFixIsNeverShownToTheSwarmTest`, which searches for "what the maintainer
wrote that nobody has given the swarm" rather than "lines the fix added" — because #2187's fix is a
rearrangement whose seven distinctive added lines already exist verbatim elsewhere in jsoup, and
because maintainers write their tests from the same report the swarm gets. Each case still contributes
8–14 searchable lines.

### What the ledger records

- **Wave 1** (2026-09-05, JDK 25.0.2, Maven 3.9.9): all five cases green on links 1–3. First clone 2
  seconds, 12 MB. Per case, compile probe / suite / test count: #2197 14s/19s/1,159; #2187
  10s/19s/1,160; #2266 11s/20s/1,241; #2476 15s/50s/1,587; #2105 10s/21s/1,138. The finding the design
  did not predict: jsoup's own suite is **not** green out of the box on these trees.
- **Wave 2**: the neighbourhood measured on all five with the model scripted — 1 to 6 types named, 17
  to 22 file:line references, briefs 2,704–4,489 characters. #2266 and #2476 name both the file the
  maintainer changed and their test file; #2187 and #2105 name the test file only; #2197 names neither.
  Live analyst read times ranged from 49 seconds (#2187) to 539 seconds (#2197).
- **Wave 3**: everything is built, but **there is no live result**. The ledger's results section is the
  literal string `RESULTS_PLACEHOLDER` and the case-results table has one empty row — the session
  stopped, as the commit subject says. Its wave-3 header still says "Not yet merged", which is now
  stale.

Wave 3's two findings are the substance:

1. **SwarmCoder cannot be pointed at a checkout that is itself a git worktree, and it fails naming the
   wrong culprit.** JGit resolves the branch name inside a linked worktree but not what it points at,
   so every git answer comes back empty and is swallowed as a warning. The first live run parked at the
   acceptance stage saying *"the test author claims test file(s) the run's tests commit does not hold …
   This is a defect in SwarmCoder's own TEST_AUTHORING stage"* — when the author had written the file
   and the run had committed it. **Wave 3's fix is only in the harness** (case trees became clones);
   making the product work on a worktree is recorded as separate, unstarted work.
2. **The two shut worked-example gates** (§3.5). The ledger ties their size directly to the unseen-code
   experiment: the channel they switch back on is the difference between *467 turns with zero files
   written* and *green in 34*.

Also recorded as not yet true: only one case has ever been run end to end; the whole suite still runs
per candidate; there is no screen; one target, one language, one build tool. And `RunKindsTest` in
`sc-workflow` was already failing on the wave branch before this work — two of five tests time out with
the run stuck at PLAN.

Thirteen members of the greenfield harness went from private to package-private so the brownfield
harness shares the free-endpoint refusal, the run-outcome record and waiting loop, the prompt-recording
client, the budget-stamping architect, the pinned run starter and the small store readers.

## 5.3 The two experiments — why the read-pause exists

Both records are on master, under `dev/experiment/`. They are the measurement behind the read-pause and
the worked example, and they are the most useful hour of reading in the repository.

### `dev/experiment/plain-loop` — the ceiling with no scaffolding

A minimal agent loop: five tools, native tool calls, **no nudges, no kills, no write set, no
compaction**. Model `qwen3.8-27b` at the same free local endpoint (the script asserts the host at
import and refuses any other), temperature 0.2. Task: build the bookshelf feature on the ZeroZ Stack
framework, with the framework's docs and examples readable. Control: the same-shaped task in plain
Java, no unfamiliar framework, no docs folder.

| run | thinking | turns | wall | how it stopped | first write | files | acceptance test |
|---|---|---|---|---|---|---|---|
| run-1 | on | 163 | 185 min | wall cap | **never** | 0 | test file does not exist |
| run-2 | off | 168 | 15 min | context overflow (254,925 + 8,192 > 262,144) | **never** | 0 | test file does not exist |
| run-1-aborted | on | 136 | 58 min | killed | **never** | 0 (185 calls: 89 run, 89 read, 7 list) | — |
| control-1 | on | 10 | 5 min | `done` | **turn 5** | 4 files, 378 lines | **5 tests, 0 failures** |
| control-2 | off | 120 | 15 min | turn cap | turn 5 | 3 files, 422 lines | fails: 8 tests, 1 error |

Run-1 consumed 15,057,591 prompt tokens and 302,056 reasoning tokens; run-2's largest prompt was
252,981 tokens, essentially the whole window. The comparison document says it plainly: *"In the plain
loop the model made 165 tool calls over 163 turns and 3 hours and wrote nothing."*

This is also where thinking-on became the default: with thinking off the model could not fix a
`NullPointerException` in 110 shell commands; with thinking on it finished in 10 turns.

### `dev/experiment/unseen-code` — what makes it write

The same loop plus optional brief injection, a pre-generated skeleton, SwarmCoder's own read-pause and
workflow-rules text, and two help-desk tools backed by the **real** `ExpertDesk`.

| variant | brief | forcing rule | turns | first write | files | test |
|---|---|---|---|---|---|---|
| v1 | worked example only | none | 92 | **never** | 0 | absent |
| v2 | example + skeleton | none | 93 | **never** | 0 | exists, fails — 3 `UnsupportedOperationException: TODO:` |
| v3 | example + skeleton | rules + pause at 16 | **34** | turn 7 | 13 | **passes** |
| v4 | example only | pause buggy | 44 | turn 11 | 1 | defective, stopped by hand |
| v5 | example only | rules + re-arming pause | 49 | turn 11 | 13 | **passes** (946 insertions) |
| v6 | example + skeleton | rules + help desk (0 calls) | 38 | turn 11 | 14 | **passes** |
| v7 | **none** | rules + help desk | 90 | turn 9 | 22 | **passes** (one `ask_expert`, answered "NONE") |

**The finding, corrected.** The common summary — "worked examples plus the read-pause are what make it
write" — is not quite what the record says. `results.md` states the split directly:

> The brief is what makes it fast; the forcing rule is what makes it happen.

A brief without a forcing rule never writes (v1, v2). A forcing rule without any brief does write
(v7) — in 90 turns instead of 34. The diagnosis in the same document: *"Given a page and a compiler it
chose the page, ninety-two times."* The rule text quoted there is the one now in every worker's
workflow rules: *"After sixteen reads without a write, reading pauses until you write … a wrong file
the compiler can correct is worth far more than another page read."*

**Caveats the records themselves state.** The experiment's `ask_expert` runs on the same free local
endpoint as the worker, so nothing here measures the production help desk — the README calls its result
a lower bound. All seven variants use one fixed task at one temperature. One row of the measurement
script is a known false negative (it greps for a pre-0.8 storage idiom), so v3, v5 and v7 report "no"
for code that does use the store. And v7's stats record a turn-37 call to a tool the harness never
defines — a hallucinated tool, not discussed in the prose.

---

# 6. Problem-handling playbook

Every row is a failure that actually happened, and the mechanism that now handles it. "Taught by" cites
the harness run and date the code's own javadoc records. Runs 8 to 30 span 2026-09-02 to 2026-09-05.

## 6.1 Design, plan and test authoring

| symptom | what the system does now | where | taught by |
|---|---|---|---|
| A needed dependency is missing from a pom | The rules and the technical documents are read for artifact names; anything named but not declared becomes an instruction on the task that already writes that module, or a new enabler task whose write set is the build file alone. The instruction says to add groupId and artifactId with no `<version>`. | `RulesVersusManifest` → `BuildFilesInTheJob.declareMissing`, `GreenfieldWorkflow.declareMissingDependencies` | run 10, 2026-09-03 |
| …and the artifact is not in the offline Maven repository | The run parks before dispatch, naming the artifact, the poms checked and the local repository path — candidate builds run with no network, so nothing inside the run could fix it. | `BuildFilesInTheJob.Outcome.parks()` | 2026-09-03 |
| A design breaks a stated project rule | Caught at DESIGN_REVIEW, before PLAN. Free guard first, then one model call; the objection is enriched with the rule in full and the nearest example of doing it right; up to **2** revisions, then park. | `ForbiddenTechGuard`, `DesignReviewerClient.reviewDesign`, `RuleConflictFeedback`, `MAX_DESIGN_RULE_REVISIONS` | run 19, 2026-09-04 |
| **The story itself names a technology the rules forbid** — "save the books and their ratings to localStorage" for a project whose rules mandate EclipseStore on the server, written into the story goal three runs in a row because the story planner was the one agent never shown the rules | The story planner's briefing (and its question round) now ends with the project's rules in force — the same `ConstraintBrief` rendering, over the same definition of "in force" (`ConstraintBrief.inForce`, which `ProjectRules` now also uses) — and the instruction that a story says **what** the user gets, never **how**: no technology, library, storage mechanism or place data is kept unless a rule names it, and never one a rule forbids. No rules in force → the briefing is byte-for-byte what it was. | `BacklogPlanning.rulesSection`, `STORIES_SAY_WHAT_NOT_HOW`, `ConstraintBrief.inForce` | **runs 37–39, 2026-09-25** |
| **The design reviewer objects to a design for following the rules** — "replaces the explicit localStorage persistence with server-side EclipseStore, deviating from the story's requirement" | The rubric review (completeness, testability, partitionability) is now given the rules, ahead of the goal, and told they outrank the goal's wording about how anything is built: completeness is judged by what the user gets, never by the mechanism the goal happened to name, and it must never object to a design for following a rule. No rules → the prompt is unchanged. | `DesignReviewerClient.review(design, rulesBrief)`, `RULES_OUTRANK_THE_GOAL` | **run 39, 2026-09-25** |
| A design names no type for a story that has checks | One mechanical re-ask listing every check with its text and its test, ending "Name the types." Still none → park, staying in DESIGN. | `GreenfieldWorkflow.hasNoTypedContract`, `missingContractsSentence` | run 21, 2026-09-04 |
| A revision comes back saying less than the design it was meant to fix | Discarded; the original is kept. The attempt still counts against the cap. | `ArchitectClient.ReviseAttempt.discarded` | run 21, 2026-09-04 |
| **A revision on a model with no structured-output support (DeepSeek V4 Flash on `ds4`, which also reasons at length before answering) comes back missing sections it was never asked to change** — the reply parses fine but never mentions `requirements` or `contracts`, so it reads exactly like run 21's genuine drop and is discarded, twice running, and design review can never improve the design | `revise`'s wire reply (before it becomes a `DesignDocument`) tells "never mentioned" apart from "stated as empty": a field left `null` because the JSON never had that key is read as a reasoning slip and earns **one** re-ask, naming by text and by name exactly which requirements and contracts (read from the ORIGINAL design, never the bad reply) must come back; a field the model explicitly emptied (`"contracts": []`) is still a deliberate drop refused with no re-ask, and poisons the whole revision back to an immediate discard even when another section was merely forgotten. | `ArchitectClient.revise`, `looksLikeForgottenSection`, `missingSectionsReminder` | **harness runs 41 and 42, 2026-09-26** |
| A plan cannot be produced | **3** real attempts, each carrying the previous reply and the exact objections, then park with the objections and the blob ref. **There is never a fabricated plan.** | `MAX_PLAN_ATTEMPTS`, the removed single-task fallback | 2026-09-03 |
| **The planner trades one objection for another and parks** — attempt 1 rejected for an orphaned service implementation, attempt 2 for two contracts no task delivered, attempt 3 for the orphan again, word for word. The retry only ever showed the previous attempt, and the objection said what was wrong, never what would be right | Three changes. (1) **Every validator objection ends with its remedy.** The orphan one lists the ways out that exist in this plan: drop it (saying who already claims each check), make a checked task depend on it (offered only where that is not a cycle), or move a check onto it. (2) **The retry remembers.** Objections from attempts before the previous one are listed as "must not bring back", and one that comes back is marked `[AGAIN]`. (3) **On the last attempt, a plan whose only fault is tasks that claim no check and that no checked task builds on has those tasks dropped**, with a log line naming them and saying that if the story needs that work it is missing a check. Not dropped (the run parks as before) when anything else is wrong, a check is unclaimed, a dropped task delivers a typed contract, or a kept task uses a type it writes. The PLAN prompt now also states the rule. | `TaskGraphValidator.checkEnablersAreUsed(graph, scope)`, `ArchitectClient.retryFeedback`, `UnusedEnablers`, `GreenfieldWorkflow` PLAN | **run 40, 2026-09-26** |
| The architect lists the acceptance test class as a contract to deliver | Dropped from every design as it returns, and independently rejected by the validator and the PLAN prompt. | `AcceptanceTestContracts`, `TaskGraphValidator.checkEveryContractIsDelivered` | run 19, 2026-09-03 |
| **Brownfield: a design names a type that already exists in the checkout, and PLAN rejects it as undelivered** — target `jsoup`, an existing plain-Java library; the story was a bug fix to `org.jsoup.parser.Parser`, and the design named it and its real `NamespaceXml` constant because that is genuinely what the fix's acceptance test reads. Attempt 1 was rejected with "no task delivers the contract `org.jsoup.parser.Parser{String NamespaceXml; }`" — the VOCABULARY rule built for run 13, where every named type really was new. Attempt 2 worked around it by inventing a task to "declare" code that needed no declaring, burning an attempt on every brownfield run for nothing | The validator is told the target repository's root and, before objecting that nobody delivers a named contract, reads that checkout with `ContractDelivery` — the same scanner `AcceptanceTestVocabulary` and verification already use. An existing type carrying every member the contract names (that the scanner can read) needs no delivering task at all; an existing type missing a named member still needs one, worded around the member rather than the type; a type genuinely absent from the checkout is unchanged. Unparseable member text is never held against a task, the same generosity `ContractDelivery` already applies at verification. Greenfield (no target repository) is unaffected. | `TaskGraphValidator.validate(..., Path repoRoot)`, `TaskGraphValidator.checkEveryContractIsDelivered`, `ContractDelivery.shortfalls` | **run 43, 2026-09-26** |
| **A design contract names a library type that does not exist** — the page contract promised `public com.zeroz4j.ui.ListView<Book> bookList` (ZeroZ Stack has no `ListView`; its list component is `com.zeroz4j.ui.component.KeyedList`) and put `TextField` and `Button` in `com.zeroz4j.ui` (they are in `com.zeroz4j.ui.component`). No worker could deliver the member; the page task burned its turns, one worker scanning jars for 290 s | Checked at DESIGN_REVIEW, free, in the same loop and with the same cap (2 revisions, then park) as a rule conflict. Every fully-qualified name in a contract's members must be planned by the design, in the checkout, in the JDK, or — when a reference checkout covers that namespace — in that checkout. The objection names the type and the member, and offers the real type of the same name ("Its TextField is com.zeroz4j.ui.component.TextField") or up to three nearest by name and javadoc (`KeyedList` first for `ListView`). Left alone: simple names, the project's own namespace (a write set may create it), and libraries with no source checked out. Not at PLAN: the planner cannot change a contract's text. | `ContractsNameRealTypes`, `LibraryTypes`, `Librarian.libraryTypes`, `GreenfieldWorkflow` DESIGN_REVIEW | **runs 44/45, 2026-09-27** |
| **An unchecked task is glued on by an edge no code needs** — the only checked task (the server's `BookListService` implementation) was made to depend on the browser page task, whose types its code never used; the edge satisfied "every enabler is built on", and when the page could not be finished the task that proved the story could never start | For an enabler whose whole output is browser-only `.java` files (read off its write set, in a module `BrowserOnlyCode` marks browser-only), only a task whose code uses one of its types — contract members, sketch or instructions, read as `TypeDependencyOrder.uses` reads them — counts as building on it. So run 44's page task is an enabler nothing builds on; the objection names the edge that did not count and says to remove it, and never offers "add an edge" to a task already waiting. Last attempt: dropped if safe, else (it delivers a typed contract) the run parks at PLAN rather than dispatching the page ahead of the server. JVM enablers keep the old rule (any path counts) because a test may construct them or the stack may wire them at run time. The PLAN prompt says a dependency counts only when the dependent's code uses what the task builds. | `TaskGraphValidator.unusedEnablers(graph, design)`, `new TaskGraphValidator(survey)`, `UnusedEnablers`, `ArchitectClient` PLAN prompt | **runs 44/45, 2026-09-27** |
| **A task that uses another task's types is planned beside it, not after it** — "Create shared BooksService interface" (contract `List<Book> getBooks()`, `void saveRating(Rating rating)`…) with no edge from "Create shared data model classes (Book and Rating)"; both went out in wave 1 and no worker of the interface task could compile a line | **The missing edge is added, deterministically, with a log line** (`PLAN warning: added dependency: …`). Who writes a type is read from `deliversContracts` and from write-set FILE entries (`TypeDeliverability.typeNamed`, the same reading as run 38's fix); who uses one is read from the members and signature sketch of the contracts a task delivers (strong) and, failing that, its instructions (weak). **Sent back to the planner instead** when the contracts show the plan ordered the pair backwards, or when two tasks' contracts name each other's types ("deliver those types in one task"). **Left alone, with a log line**, when only instructions' wording points that way and it cannot be sure of the direction (mutual, or against the planner's own order), when a simple name is ambiguous, and when the contracts say nothing. The PLAN prompt also states the rule. | `TypeDependencyOrder.apply`, called from `TaskGraphValidator.validate` | **run 39, 2026-09-25** |
| …and the worker looks for those types in the wrong package — run 39's went to `…bookshelf.model`, the package the worked example happened to use | Every task that uses a type another task writes gets a block appended to its instructions on the accepted plan: each type's fully-qualified name (with the contract's members when there is one), the task that writes it, and whether that task finishes before this one starts. | `TypeDependencyOrder.annotate`, `GreenfieldWorkflow` PLAN | **run 39, 2026-09-25** |
| Two tasks claim the same check | Normalised: the check stays on the leaf of their dependency chain, ties going to the UI/client task. A violation only when the claimers are not in one connected component. | `TaskGraphValidator.normalizeOneCheckOneTask` | 2026-09-03 |
| An enabler nothing builds on | Violation: every task claiming no check must be transitively reachable from one that does. The objection says how to fix it; on the last attempt, if it is the only fault, the task is dropped (see the run 40 row). | `TaskGraphValidator.checkEnablersAreUsed`, `UnusedEnablers` | 2026-09-03; run 40 |
| A write set names a **file**, not a directory | Accepted. The deliverability check drops a file entry's own basename before looking for a source root, and also matches the file's simple name against the missing type. Before the fix a file entry never matched and a healthy red state was misread as a broken test, parking the run. | `TypeDeliverability.packageTail`, `mayCreate` | 2026-09-05 |
| An acceptance test cannot compile for its own reasons — a client-only package imported into the server module | The test author is given a classpath line naming the acceptance module and exactly the artifact ids that module declares, and told never to import an undeclared module or a client/UI package. A broken test goes back to its author **once**, then the run parks. | `TestAuthorClient`, `BrokenAcceptanceTest.classpathSentence`, `reauthorBrokenAcceptanceTest` | run 26, 2026-09-05 |
| **An acceptance test calls browser-only code** — a JUnit test in the server module calling the TeaVM client (`BookStore.getInstance()`), which throws `UnsatisfiedLinkError` on a native `org.teavm.jso` method for every candidate | Four layers. (1) **Build knowledge:** `BrowserOnlyCode.survey` marks a module browser-only when its *own* build file declares TeaVM, ZeroZ Stack's `zerozstack-client`/`zerozstack-ui-components`, GWT, Elemental2 or J2CL (not test scope, not inherited — the server that packages the bundle stays a JVM module). (2) **Told by name:** the architect at DESIGN (user message) and PLAN (`RepoLayoutBrief`), and the test author before it writes — the client comes off the "you may import" list, and a browser-worded check ("after restarting the browser…") is to be proved where the behaviour lives (the server's stored data, asked again from scratch). (3) **Refused at authoring:** a test importing or naming a class of a browser-only module or a browser runtime package is re-asked **once**, then the run parks. (4) **Refused when it runs:** a red-check at TEST_AUTHORING that errors with `UnsatisfiedLinkError` (or cannot load a browser runtime class) is a broken test, not a red one — one repair call, then park; at wave time it parks before dispatch; at verification, when every verified candidate dies that way, the test goes back to its author (`TestRepairNeeded` with a browser-only reason) instead of a repair round. | `BrowserOnlyCode`, `AcceptanceTestReach`, `TestAuthorClient.repairUnrunnableTest`, `GreenfieldWorkflow.reauthorUnrunnableAcceptanceTest`, `SwarmEngineImpl.testRepairNeededFor` | **run 37, 2026-09-25** |
| A test names a type nothing will ever deliver | The red check separates a **healthy red** (missing contract types) from a **broken test** (anything else) by asking whether any still-to-run task promises or could create that symbol. Fails open everywhere. | `RedChecker` + `TypeDeliverability` | run 30 / run 26 |
| **A test author has no legal type left to construct** — naming the contract interface is refused as self-implementation if it builds one, and naming the concrete class a task is about to write is refused as "nothing in this plan delivers" | A type whose source file sits in some task's write set — read the same way `TypeDeliverability` already reads a write set the other direction, source root + package path + `SimpleName.java` — is now exactly as real as a design contract. `AcceptanceTestVocabulary` accepts a fully-qualified import naming it outright, and a bare simple name when exactly one planned type answers to it (two tasks' write sets sharing a simple name stay unresolved, on purpose); the vocabulary shown to the author lists it by name, with the task that writes it. `SelfImplementedContract`'s wiring sentence points at the same planned class, even in the wave that is about to write it, before the file exists anywhere in the checkout. | `PlannedImplementations`, `TypeDeliverability.typeNamed`, `AcceptanceTestVocabulary`, `SelfImplementedContract.wiring` | **run 38, 2026-09-25** |
| The test author writes the same file path for two tasks | The run parks — the second write silently replaced the first. | `AuthoredTestAudit` one-file-two-tasks guard | 2026-09-02 |
| A check names a test nobody wrote | The audit reads test ids back **out of the files** and parks. It never writes the reference back: a self-satisfying requirement is worse than a hand-typed string. | `AuthoredTestAudit` | 2026-09-02 |
| An enum-constant contract | Handled by the shape matcher, not by a special case: an all-constants contract scores form agreement 1 only against an enum, and constants count as members. A contract that becomes an enum is also handled by the skeleton builder. | `WorkedExamples.score` / `formAgreement`, `Skeleton` | 2026-09-04 |
| **The test author keeps trying to write a module's `pom.xml`** — told only the module's declared artifact ids, it reached for AssertJ's `assertThat` (this project's own convention), found nothing on the classpath sentence naming an assertion library, and tried twice to declare `assertj-core` itself, in a file the write policy silently rejects every time | The classpath sentence now says outright that this role can never add, change or write a build file — a fact that was already mechanically true, just never stated — and, when the declared list names no assertion library, points the author at JUnit 5's own `org.junit.jupiter.api.Assertions` instead of leaving it to guess. | `TestAuthorClient.authorTests` (the classpath sentence) | **run 42, 2026-09-26** |

## 6.2 Tests that prove nothing

| symptom | what the system does now | where | taught by |
|---|---|---|---|
| **A test implements the contract itself** | Rejected at authoring: an anonymous class, subclass, lambda, mock, spy, or a `<C>Stub`/`Fake`/`Mock`/`Double`/`InMemory<C>` type declared in the test file. One re-ask carrying a `wiring` sentence read off the checkout — how the application really obtains one — then park. | `SelfImplementedContract` | **run 30, 2026-09-05** |
| **The lambda check fires on ordinary use of a delivered class** — `Book existing = service.getBooks().stream().filter(b -> "The Hobbit".equals(b.title))...` was read as the test writing `Book`'s own lambda, because the old pattern matched an arrow anywhere between `=` and the next `;`, three method calls into a stream pipeline. Both authoring attempts were rejected and the run parked before a single worker ran | The lambda pattern must now match the WHOLE right-hand side — nothing between `=` and the arrow but a bare identifier or a parenthesised parameter list — and, independently, is skipped altogether for a contract the checkout confirms is a class, enum, record or annotation type: a lambda can never implement one of those, whatever the text looks like. The same audit found `SUBTYPE` misreading a generic type-parameter bound (`<T extends Book>`) and a wildcard bound (`List<? extends Book>`) as a class extending the contract, fixed by skipping a match found inside an unclosed `<...>`. | `SelfImplementedContract.LAMBDA`, `.classContracts`, `.matchSubtype`, `ProjectTypes.isInterface`, `JavaSourceFacts.isInterface` | **run 42, 2026-09-26** |
| **A test is green without the candidate** | An acceptance pass counts only when the same tests were not already green on a tree carrying nothing this run delivered. The wave gate records that fact once per task; the verdict reads it. An enabler is untouched. | `Verdicts.AcceptanceProvenance`, `ChecksAlreadyProved.nothingDeliveredYet` | run 30, 2026-09-05 |
| A repaired test comes back green before any candidate | One further author call with a tautology re-ask, re-checked; a second miss parks. | `GreenfieldWorkflow.repairTautologicalTest` | run 30, 2026-09-05 |
| The whole chain went green on a delivery that ran no candidate code | Harness link 16 re-runs the acceptance suite on the delivered commit's **parent** and requires red-before, green-after. | `DeliveredCodeIsWhatPassedCheck`, `L_PROVES` | run 30, 2026-09-05 |
| **An assertion failure** | Is the test doing its job. Never counted as "inside the test", whatever its frames. | `AcceptanceFailureAttribution` | run 30, 2026-09-05 |
| **A crash inside the test's own setup** | A non-assertion throwable whose every surviving project frame is the failing test's own class. When every verified candidate fails that way, the test goes back to its author **once** per task (durably, via `Task.testRepairAttempted`), the correction is red-checked, and the same candidates are re-verified **once** from their own branches with no new swarm. | `testRepairNeededFor`, `repairFaultyAcceptanceTest`, `resumeAfterTestRepair` | run 23 / run 26, 2026-09-05 |
| **Every candidate dies because the test reached code only a browser can run** | Recognised from the failure itself (`UnsatisfiedLinkError` on the header or a `Caused by:` line, a browser-runtime `(Native Method)` frame, or a browser-runtime class that cannot be loaded). When every candidate that produced a verdict died that way — a candidate killed before verification is not counted — the test goes back to its author once with that reason, not to a repair round; the correction is held to the reach check, red-checked, and the same candidates are re-verified. | `BrowserOnlyCode.reachedIn`, `SwarmEngineImpl.browserOnlyFault`, `repairFaultyAcceptanceTest` | run 37, 2026-09-25 |
| **A test that misuses existing code passes the red check as "not yet implemented"** — brownfield jsoup: line 16 of the acceptance test passed a `Parser` where the existing `Document` constructor takes a `String`; the red check confirmed any compile failure as healthy, both workers wrote the right guard, both candidates failed on that line, four repair workers changed nothing, the task was BLOCKED | The red check now reads every javac error in the acceptance test files. An absence (`cannot find symbol`, `package does not exist`) is healthy only when a still-to-run task could supply it: a missing type by `TypeDeliverability`; a missing member when a contract names the owner type or a write set holds the owner's own file; an unimported name when the plan delivers something of that name. A misuse (incompatible types, wrong arguments, unreported exception, anything else) is healthy only when a contract promises to change that existing member — a write set is not enough, since run 43's task wrote exactly the misused file. A member missing on an existing type nobody writes or contracts, and any JDK member, is broken. Broken → back to the test author **once** with the compiler's `file:line: message` lines and the real public signatures read off the checkout, then park; at wave time park before dispatch. At verification, when every candidate fails on the same misuse in the acceptance test, the test goes to its author instead of a repair round. A `pom.xml` in a write set no longer counts as able to create any type. | `AcceptanceCompileErrors`, `MiscompiledAcceptanceTest`, `GreenfieldWorkflow.reauthorMiscompiledAcceptanceTest`, `SwarmEngineImpl.miscompiledFault`, `TypeDeliverability.mayWrite` | **brownfield run 43, 2026-09-26** |
| An acceptance stage that runs zero tests while the task answers for checks | The candidate fails with `NO_ACCEPTANCE_EVIDENCE` — verification learned nothing, so it may not reach the judge. | `Verdicts.emptyCheckVerdict`, `KillReason.NO_ACCEPTANCE_EVIDENCE` | run 13, 2026-09-03 |
| A dead run's acceptance tests break every later run | Tests live on the run's own ref `swarm/tests/<runId>`, never the delivery branch, and each candidate's tree is **cleared** and reduced to exactly its own task's claimed files. | `AcceptanceOverlay.reduceTo` | 2026-09-02 |

## 6.3 Workers

| symptom | what the system does now | where | taught by |
|---|---|---|---|
| **A worker reads forever** | Steer at 8 and at 16 investigation calls (the 16th adds a last warning); from the **17th**, `read`, `exec` and `lookup_api` return the pause text instead of a result — a build or test command still runs, and so do `ask_expert` and `request_skeleton`. At 24 the worker is killed `NO_PROGRESS`. Six fruitless results in a row also kills. | `EarlyKillEnforcer`, `WorkerToolbox.READ_PAUSE_TEXT` | measured in `dev/experiment/unseen-code` |
| A worker takes the framework apart with `javap` | Refused before any process starts, pointing at `ask_expert`: "more reliable than a disassembly and costs one turn instead of twenty". | `WorkerToolbox.REVERSE_ENGINEERING` | 66 shell commands, 92 turns, nothing written |
| **The documentation search keeps returning the same page** | After 3 *different* questions get the same section, a one-shot orchestrator note says this is a defect in the index and not something asking differently will fix. If the worker is then stopped, the kill is relabelled `DOCS_DEAD_END` and the last 12 questions are recorded on the candidate. Separately, `lookup_api` never returns a section twice, boosts the words a follow-up adds, and says so when there is genuinely nothing new. | `WorkerToolbox.noteDegenerateLookup`, `Librarian.lookupApi`, `KillReason.DOCS_DEAD_END` | 2026-09-05 |
| **A worker asks the help desk the same thing seven times** | An overlap of ≥ 0.5 with any earlier question **this worker** asked escalates unconditionally — even when the free answer would have cleared the coverage bar — and the expert is handed every earlier question and answer. A run-scoped cache also shares one expert answer across every worker of the run. | `ExpertDesk.askedAgainReason`, `RunAnswerCache` | **run 30, 2026-09-03: one task, two workers, seven questions about the same thing** |
| A free answer that does not answer | Coverage below 0.5 over the question's distinguishing tokens escalates, handing the best free material over as a starting point. | `ExpertDesk.COVERAGE_THRESHOLD` | 2026-09-03 |
| **The expert reads the answer and never gives it** — stopped at its turn cap, the worker is handed NONE | Every lookup result counts the turns down (halfway, the last five, "your NEXT turn is your LAST"). A session stopped at its cap, or out of room, with material in hand gets one closing turn with only `report_done`, handed what its lookups returned and nothing else, and the worker gets that answer labelled as written after the turns ran out. No material, no closing turn. `list_files` is a tree with single-folder chains joined; `read_file` on a folder lists it; `lookup_docs` never repeats a section within one session. | `ExpertEscalation.closingAnswer`, `ExpertTools.countdownNote`, `ExpertTools.whatWasFound`, `KnowledgeCurator.listTree` | **harness run 37, 2026-09-25: 31 turns, 53 lookups, the answer read by the 14th, 15 turns spent walking folders one per call** |
| **A worker starts a server in the foreground** | Refused unless backgrounded. On Windows a trailing `&` or `nohup` is honoured for real: the process is launched without `waitFor()`, its log named, its pid remembered and reported, and every remembered pid force-killed in a `finally` at the end of the session. | `WorkerToolbox.FOREGROUND_SERVER`, `LocalProcessExecTarget.startBackground` | two harness runs on one night |
| **A worker command kills the host JVM** | Image-, name-, port- and pipeline-scoped kill commands are refused outright; the three narrow pid forms are refused unless the pid is one this worker started. Independently, every `exec` is written to the store **before** it runs, blocking the worker thread, so the command survives a death that takes the transcript with it. | `WorkerToolbox.ALWAYS_REFUSE_KILL`, `PendingExecRecorder` | **runs 21 and 29, 2026-09-05** |
| A command waits on a console | `\| more`, `\| less`, `less`, `type con`, `pause`, `read -p` refused; stdin comes from `NUL`; `CI=true`, `TERM=dumb`, `PAGER=cat`, `GIT_PAGER=cat` are set. | `WorkerToolbox.CONSOLE_WAIT`, `LocalProcessExecTarget.closeStdin` | 2026-09-05 |
| A worker writes outside its write set | **The change is kept** and recorded once per path when no other task of the plan holds the file (it is then that task's; the selected candidate's are listed in the run report as taken beyond the plan); it is **refused, naming the task**, when a task built at the same time or later holds it (2026-10-08). The model is told which. Only protected places — `.git`, `.swarmcoder`, a locked module, the acceptance tests — are refused, and two of those kill. | `PathPolicy`, `WorkerToolbox.record` | 2026-09-02: a worker died at turn 102 over a data file its own test run wrote |
| A shell command writes where no tool did | After every `exec`, `git status` is read host-side and each changed path put through the same policy; protected ones are reverted with a note. The same audit runs again at integration on the winning diff. | `auditAndRevertStrayWrites`, `FinalIntegrator` | 2026-09-02 |
| **Stray scratch files** | Geometry only — not a build file and not under a conventional source root. The judge's score is capped at **0.5**; the wave integrator and the final integrator delete uncontested strays and commit the removal. | `StrayFileCheck` | run 11, 2026-09-03: a worker wrote a Python helper script and the judge scored it 1.00 |
| A worker writes source the build never compiles | `UNBUILT_FILES` — it compiled, but the build never compiled *it*. Usually means the task's write set was invented rather than read off the real module layout. | `SwarmEngineImpl.killReasonFor`, `BuildReachability` | 2026-09-02 |
| A worker's turns run out versus its conversation not fitting | Two different kill reasons with two different fixes, counted separately by the concurrency governor. | `TURN_CAP` vs `BUDGET_EXCEEDED` | run 11, 2026-09-02 |
| Workers keep dying of context | The divisor is halved (doubling each worker's room) when at least half of the last *c* workers at concurrency *c* died of room; recovery after two clean waves, with the wait doubling on a failed probe. | `AdaptiveConcurrency` | 2026-09-01: twelve workers, four died of room at turns 62–99 |
| The model writes tool calls as prose | Recovered and executed as written, up to 8 per turn, but only for models on textual tool history. | `TextEmittedToolCalls`, `KoogAgentRuntime.recoverTextEmittedCalls` | one run where 27 of 27 workers finished with zero files written |
| **A worker `cd`s using a POSIX-spelled path and every exec fails on Windows** | `exec` on Windows has always gone through `cmd.exe`, consistently — the shell was never what varied. What varied is that a POSIX-emulating tool on the worker's PATH (a Git-for-Windows `pwd`, most likely) translates the real Windows working directory back into its POSIX-mount spelling (`/c/Users/dev/...`) regardless of which shell launched it, so a plain `pwd` answered that way even under `cmd.exe`. A worker that read this as "I am in a POSIX shell" then wrote its next `cd` the same way, and `cmd.exe`'s own `cd` reads a leading `/` as the root of whatever drive it is already on, so it fails with "The system cannot find the path specified." **Fixed two ways:** the shared prompt now says plainly that every `exec` already starts in the checkout root (no `cd` is ever needed) and, on Windows, that a path shown in that spelling names the same place as `C:\Users\...`; separately, `LocalProcessExecTarget` rewrites a leading `cd /x/...` to `cd /d X:/...` before it ever reaches `cmd.exe`, so a worker that `cd`s that way anyway still gets there. | `SwarmDispatcher.buildBundle`, `LocalProcessExecTarget.translateGitBashCdTarget`, `LocalProcessExecTarget.isWindows` | **run 41, 2026-09-26: `cd /c/Users/dev/.swarmcoder/wt/<id> && mvn ...` failed on every retry until the worker was killed `NO_PROGRESS`** |

## 6.4 Malformed and empty model replies, by stage

| stage | what happens |
|---|---|
| **Analyst (requirements)** | Strict parse → **one** retry with the parser's complaint → salvage the retry reply field by field, stopping at the first unreadable thing and keeping everything before it. Nothing is repaired or inferred. Empty salvage fails the flow, and a thin salvage says so in the step label with the blob ref. |
| **Analyst, zero proposals** | One re-ask naming the character count; still empty → fail. |
| **Planner (stories)** | One retry, then fail. **No salvage.** |
| **Architect (design)** | One retry with the complaint fed back; then, unscoped, a minimal design from the goal ("Architect produced no usable design"); scoped, a design carrying the BRD requirements with empty contracts. |
| **Architect (plan)** | Up to 3 attempts, each fed the previous reply and the objections. A reply that parses but names no tasks records "the reply had no tasks". Then park — never a fabricated plan. |
| **Design reviewer** | One retry; a second failure fails **soft** (returns approved with the note in the objections), detected by wording and logged rather than treated as an objection. |
| **Test author** | Three separate one-retry allowances: unparseable reply; a reply with no files while the task claims a check; then the vocabulary and self-implementation re-asks. Each re-ask deletes the first attempt's files before writing the correction. |
| **Judge** | Unreachable or budget-exhausted → a flat score of 0.5 with "unjudged: …". 0.5 is used as no ceiling anywhere, so the two are distinguishable. |
| **Any stage, endpoint down** | `EndpointOutage` is rethrown everywhere and never degrades into an answer. The run is saved with a pause, the state does **not** move, and the same stage is retried indefinitely. The cloud charge is refunded. |

## 6.5 Candidates, waves and runs

| symptom | what the system does now | where | taught by |
|---|---|---|---|
| **No candidate survived** | A repair round: at most 2 seeds × 2 workers, seeded from the failed candidates that at least compiled, each starting from the rejected attempt's own checkout with the failure evidence in its brief. Temperature `0.4 + 0.4 × i`, persona always `defensive-edges`. Still nothing → task BLOCKED plus a decision; the run continues with every task that does not depend on it (see the run-39 row below). A repair worker starts from its seed's checkout, which was cut from the same wave base as the failed attempt, so it cannot see the work of a sibling task in the same wave — that is merged only after the whole wave ends. | `SwarmEngineImpl.repairRound`, `RepairIndex` | run 12 |
| Every judged survivor broke a stated rule | A second, different repair round seeded by judge score, carrying the broken rules verbatim and "Do not work around it with an in-memory or temporary substitute". If the combined set still all break a rule, the **run** parks. | `ruleBreakRepairRound` | 2026-08-28 |
| **A superseded worker** | The first repair to survive calls `supersede()`, and every in-flight sibling's turn guard returns `SUPERSEDED` on its next turn — so the operator reads "another attempt had already won", not a failure. | `GroupSignal`, `WorkerLoop` | 2026-09-02 |
| Every candidate died because the endpoint was down | Not repaired — waited out. A wave lost to an outage is an outage, not a failure. | `failIfLostToOutage`, `KillReason.ENDPOINT_OUTAGE` | 2026-08-29 |
| **A task is dispatched although a task it depends on is BLOCKED** — "Implement server-side BooksService with EclipseStore" started while "Create shared BooksService interface" had just gone BLOCKED, so workers implemented an interface that did not exist | A task is dispatched only when every task it depends on, directly or transitively, has a winner. The others wait, untouched (no workers, nothing archived, state unchanged); tasks that do not depend on the blocked one still run. When the walk is over and anything waited, the run parks at EXECUTING **behind the blocked task's own question** — the reason names the blocked task and the tasks that waited, and no second question is raised. Resuming re-dispatches the blocked task, then the ones that waited; every winner is kept. | `WaitingOnDependencies`, `SwarmEngineImpl.executeRun`, `RunMustPark(brief, true)` | **run 39, 2026-09-25** |
| A later wave cannot see what an earlier wave built | Each wave is cut from `swarm/progress/<runId>`, the base plus every finished winner. | `WaveIntegrator`, `Run.progressCommit` | 2026-09-02 |
| A wave's tests are already green because the waves in front delivered the behaviour | **Recorded, not parked.** The task still runs (its instructions describe work the tests do not measure), its candidates are still verified, and the judge is told the tests separate nothing so it must decide on the diff. | `ChecksAlreadyProved`, `UserFacingWording` | 2026-09-03 |
| A verdict with no reason | A literal fallback naming compile, acceptance counts and claimed checks, and saying this is a bug in SwarmCoder. Pinned by a test. | `SwarmEngineImpl.survivalReason` | 2026-09-05 |
| An enabler judged as unproven because no test ran | The judge's brief says explicitly that a task claiming no check was due none, and a standing enabler rule is in the system prompt. | `JudgeClient.acceptanceLine` | run 13 |
| The judge blames a candidate for a tree that was already broken | A compile failure attributed as pre-existing caps the score at 0.4 (unverified) rather than 0.2 (does not compile). | `JudgeClient`, `CompileFailure` attribution | 2026-09-02 |
| A resumed run's branch already exists | `addOrResumeWorktree` handles it instead of failing. | `GitService` | 2026-08-29 |
| **A parked run** | The stage that could not continue stamps `parkedAt`/`parkReason` and raises a `BLOCKED_TASK` decision with the same brief. The card reads STOPPED immediately, because health checks the park mark before the pause and the heartbeat. | `GreenfieldWorkflow.parkRun`, `BuildHealth.of` | 2026-09-03 |
| **…and how the operator restarts it** | Two different things. **"Build it again"** on the board sets the story READY, clears the delivered and integration commits, and nulls the park marks — **a fresh run**, nothing resumed. **`RunResumer.resumeAll()` at process start** hands the parked run back to its own project's engine, which clears the mark and retries the exact stage it parked in. In autonomous mode a parked story is retried **once**, automatically, and a second park is left for a person. | `BacklogServiceImpl.retryStory`, `RunResumer`, `AutonomousBuild.retryParkedStories` | 2026-09-03 |
| A project is deleted while its runs are unfinished | The project context is torn down first (so no new dispatch), a worktree sweep is launched, and the run rows are removed outright. Orphans left by older deletions are set `ABANDONED` at startup. | `ControlServiceImpl.deleteProject`, `RunResumer.abandonOrphans` | 2026-09-03 |

---

# 7. Operating notes

## 7.1 What the console shows

The console is a TeaVM (Java-to-JS) client served by an embedded ZeroZ Stack server. `MainView` is the
shell, organised around a `Stage` enum rather than a menu: two stages are workspace tabs in the header,
the rest are reached from a health dot, an overflow menu, or a developer toggle. `StageHost` builds each
stage lazily on first visit and keeps it alive-but-hidden afterwards, rebuilding only on a project
switch.

| stage | what it is |
|---|---|
| **REQUIREMENTS** | `RequirementsStage` — two lenses on one server-authoritative document: `RequirementTreeView` (a virtualised tree, built to scale to ~1,000 requirements) and `BrdView` (the graph canvas plus a History panel that can restore any past revision). Beside them `KnowledgeView` (curated conventions, with post-run extraction proposals badged PROPOSED) and the `IntakeWizard`. |
| **PLAN** | `PlanStage` — `PipelineBoard`, the single board for every story from suggestion to delivery, in five columns: *Suggested* ("real work?"), *Ready to build*, *Building*, *Came back* ("judge it — delivered, or stopped short"), *Delivered*. Health is a **badge on the card, never a column**, because an earlier design split planning and building across two boards and the same story could show contradictory status on both. Plus a read-only requirements pane and the `PlanningWizard`. |
| **BUILD** (behind the overflow) | `GuidelinesView` (each rule toggled "Use this" / "Stop using this", and its check command editable) and `InsightsView` (KPI tiles, a temperature-versus-survival scatter, family and kill-reason tables — the eval dataset made visible). |
| **SETUP** | the project's repository pointer, model endpoints and sandbox. Opened from the header health dot; green means nothing needs attention. |
| **PROMPT_LAB**, **COMPONENTS** | developer-only, hidden behind a settings toggle. The prompt lab shows the exact `PromptBundle` a dispatch received, segmented, with per-segment token estimates, and **diffs two dispatches** so you can see which segment changed between a working run and a failing one. |

**Watching a build** is one screen: `RunGraphView` — a phase strip
(`INTAKE, DESIGN, DESIGN_REVIEW, PLAN, TEST_AUTHORING, EXECUTING, FINAL_INTEGRATION`) above a task DAG
above a candidate fan, on a pan/zoom canvas, redrawn from the run's snapshot signal, with a live band
saying who is working on what and a lane timeline of worker sessions. Clicking a candidate opens the
inspector: a property grid of sampling and verdict facts, then one more click to `TranscriptPane` — one
card per trace event, colour-coded, with **`apply_diff` and `write_file` payloads rendered as real
diffs**. That is the only diff surface in the console. A truncated card offers "Load full payload",
which fetches the exact bytes from the blob store.

**There is deliberately no approvals queue.** A build that stopped to ask something shows the question
on the card of the story it belongs to. `PendingDecisions` is the one shared definition of "how many
nobody has answered", and its javadoc says plainly that **answering a decision restarts nothing** — no
thread waits on one. A parked run resumes only via `RunResumer` at process start, or by the operator
pressing "Build it again". Orphaned decisions with no story (a cloud-budget breach with no run attached)
get a banner instead.

The status bar's `HealthStrip` is the one place in the shell that polls: model-server reachability,
Docker sandbox state, workers and runs in flight, pending approvals, cloud-budget burn.

## 7.2 `~/.swarmcoder`

| path | holds | safe to delete? |
|---|---|---|
| `config.yaml` (+ `.bak*`) | console port, model endpoints and roles, budgets. Written with a plain-language template on first run. **Contains credentials.** | It regenerates, but you lose your endpoints and keys. |
| `store/` | **the entire product database** — an embedded EclipseStore object graph holding every project, run, story, decision, BRD, chat and rule. | **No.** This is the one directory whose loss is total. |
| `blobs/` | content-addressed payload bytes referenced by hash from the store: full session-event payloads, uploaded documents. | Not independently of `store/`. Nothing breaks structurally, but "Load full payload" stops working. |
| `rag/history/` | a Lucene index over completed session transcripts | Yes — derived. |
| `rag/docs/` | indexed third-party library documentation | Yes — costs a re-fetch. |
| `rag/refs/` | the reference text index (per root **set**) and the semantic relation store (`<label>-<hash>/semantic.json.gz`, per **root**) | Yes — rebuilt from the checkout; costs indexing time, and up to ten minutes per root for the LST pass. |
| `primers/` | cached model-distilled per-file summaries | Yes — but regenerating them costs model calls. |
| `wt/` | linked git worktrees: one per worker candidate, plus `tests-<runId>`, `progress-<runId>`, `integration-<runId>`, `retest-<runId>-<candidateId>` | Yes **when nothing is building**. A produced diff reaches the store before a worktree is normally removed, so nothing durable is lost — but deleting under a running worker breaks that worker. |
| `mcp-secret` | the secret a caller must send to run any MCP tool that changes something (7.5). Made the first time the MCP server starts with `readOnly` off. Never logged, never returned by a tool. | Yes - a new one is made on the next start, and every client must be given it again. |
| `logs/` | `swarmcoder.log` plus daily-rolled files, 14-day history enforced by logback | Yes, always. Self-pruning. |
| `bin/` | a downloaded `mergiraf` merge-driver binary | Yes — falls back to PATH, then to ordinary git merges. |
| `targets/` | **on branch `brownfield` only** — the jsoup clone and its per-case trees | Yes; costs a re-clone (2 seconds, 12 MB). |

`WorktreeSweeper` already tidies `wt/` at startup: it asks git's own bookkeeping, never touches anything
younger than **6 hours** or more than **200 per start**, and refuses (plain `git worktree remove`, no
`--force`) anything with uncommitted work — except an orphaned worktree whose owning repository no
longer exists, which it deletes outright since nothing could ever reclaim it. 63 had accumulated by
August 2026 before it existed.

**A separate, per-checkout `<repo>/.swarmcoder/`** holds `project.yaml`, `verify.yaml` (the build and
test contract), and `jdtls-data`. It is always protected from worker writes, and every repository scan
excludes it alongside `.git`, `target` and `node_modules`. The `guidelines/` folder under it is stale —
rules became store objects on 2026-09-02 — though the path is still resolved.

## 7.3 Building and running

- **Build:** `build.bat` runs `mvn clean install -DskipTests`. A 20-module reactor, Java 21.
- **Run:** `run.bat` runs `java %JAVA_OPTS% -jar sc-app\target\sc-app-0.2.0-SNAPSHOT.jar`. `JAVA_OPTS`
  carries four `--add-exports`/`--add-opens` flags EclipseStore needs; the same flags are in the root
  pom's surefire `argLine`, with a comment that any launcher script must repeat them. `sc-app` is a
  **thin** jar: the manifest points at `libs/`, into which the dependency plugin copies runtime
  dependencies at package time.
- **`deploy/`** holds only `build-images.sh`, which builds the Docker sandbox image workers run inside.
  It is not application deployment.
- **There is no `-Pproduction` profile** anywhere in this repository, and **no documented jar lock**
  while the app runs. (Both are conventions from other projects; they do not apply here. What is true
  is the ordinary one: a running app holds `sc-app/target/libs/*.jar` open on Windows, so stop it before
  a rebuild if the copy step fails.)
- **There is no CI** — no `.github/workflows`, no pipeline of any kind. Everything the gates cost runs
  in the ordinary build because there is nowhere else for it to run.

## 7.4 Running tests

`docs/TESTING.md` is the contract. Two rules matter every day:

- **Never run a bare `mvn test`.** Name the module and the class: `mvn -pl <module> -am -Dtest=<Class>`.
- **One browser test per JVM.** `sc-console` sets `reuseForks=false`, because shared signals bind to
  whichever server engine started first in a process.

Tests declare what they need with `@RunsWhen(Need.X)` rather than being skipped by an unset flag.
`CHROMIUM`, `DOCKER` and `MAVEN` run whenever the tool is present — nobody opts in, so nobody can
forget. `LIVE_MODEL` (`-Dswarmcoder.live.baseUrl=<url>`), `PAID_CLOUD_MODELS`
(`SWARMCODER_CONFIG_E2E=true`) and `DEMO_REPO` (`-Dswarmcoder.demo.repo=<path>`) are deliberately off,
each for a stated reason — the shared model server, real money, and a repository that is not in this
checkout. Every skip is announced in a banner and in `target/tests-not-run.txt`. The one way to skip a
gated test is `-Dswarmcoder.skipSlowTests=true`.

This inversion exists because thirteen test classes and four further methods — including the six that
boot the real console in a real browser — had been silently skipped for weeks while reading as coverage.

## 7.5 Following a run

`~/.swarmcoder/logs/swarmcoder.log`, console and file both, `com.swarmcoder` at INFO with everything
else quieted. The lines worth grepping:

- `Dispatching task '…' n=… prefixHash=… prefixTokens=… (…)` — the shared prefix and its size
- `exec<ctx>: cwd=… command=…` and `exec<ctx> done: exit=… duration=…s`, where `<ctx>` is
  `[Worker N (task title)]`
- `Session '<role>' turn N: the model wrote K tool call(s) as text …`
- `[orchestrator] …` — every steer, pause and dead-end note the worker was shown
- `[CHAIN ok  ]` / `[CHAIN FAIL]` and the final `CHAIN WHOLE` / `CHAIN BROKE AT` / `CHAIN INCOMPLETE`

For a live view, use the console's run graph rather than the log — a harness run serves its own
(5.1, "Watching a harness run live"); for a post-mortem, the transcript pane
and the pending-exec row are what survive a worker that killed its own JVM.

### The MCP server: watching a build, and running one from outside (2026-10-10)

`mcpApi.enabled: true` opens SwarmCoder's own MCP server on loopback (`SwarmMcpServer`, default port
8931, Streamable HTTP at `/mcp`). It owns no data: every tool is a call into a Console service.

**Who may do what.** Tools that only read need nothing. Every tool that changes something needs
`Authorization: Bearer <secret>`, where the secret is the contents of `~/.swarmcoder/mcp-secret`
(`McpSecret`; made on first start). The check is in the transport (`LoopbackHttpTransport.refusal`):
a `tools/call` naming a guarded tool without the secret gets a JSON-RPC error and the tool is never
run. `SwarmMcpTools.writeToolNames()` is the guarded list, and a test fails if a tool described as
changing something is not on it. `mcpApi.readOnly: true` leaves those tools out altogether.

**Watching** (read): `swarm_status`, `run_diagnosis`, `run_detail`, `list_runs`, `session_events`,
`event_payload`, `session_prompt`, `blob_text`, `run_diff`, `pending_decisions`, `search_history`,
`insights`, `list_projects`. Older tools that change something: `start_run`, `decide_run`,
`answer_decision` (records an answer, restarts nothing).

**Supervising** - an outside model running a whole build the way a person does (`SupervisorMcpTools`
over `SupervisorService`, implemented by `SupervisorDesk`). No tool here calls a model.

| tool | changes something | what it does |
|---|---|---|
| `wait_for_attention` | no | Blocks (100 s at most per call) until something needs the supervisor, then returns that one item. Sleeps on a condition the store signals after each durable write (`ArtifactStore.addWriteListener`); no polling. |
| `next_attention` | no | The same item without waiting. `skip=n` passes over the n most urgent. |
| `view_flow` | no | The analyst's or planner's flow: state, questions and proposals with their ids. |
| `list_backlog` | no | Every story: key, title, state, what it builds on, why it waits. |
| `decision_text` | no | The whole text of a question a build stopped to ask, and the answers it accepts. |
| `decision_log` | no | What the supervisor decided: asked, answered, when. |
| `create_project`, `switch_project` | yes | `ControlService.createProject` / `switchProject`. |
| `add_document` | yes | Pasted text as a document of the analyst's flow; `technical` marks a document about how to build. |
| `start_flow` | yes | Starts the analyst or the planner (reopening a finished or failed flow first). |
| `answer_flow_question`, `submit_answers` | yes | Answers (or skips) one question; submits the round. |
| `apply_proposals` | yes | Accepts all proposals but those in `reject`, and applies them. |
| `agree_requirements` | yes | `BrdService.promoteRequirement` / `promoteAllDrafts`. A gate a person normally passes. |
| `promote_story`, `start_story` | yes | `BacklogService.promoteStory` / `startSession`. |
| `accept_delivery` | yes | Accepts a story back for a verdict; the story records `acceptedBy = supervisor`. A gate a person normally passes. |
| `send_back` | yes | `BacklogService.retryStory` with the note. |
| `answer_question` | yes | Answers a question a build stopped to ask and hands the parked run back to its engine (`DecisionAnswers.answerAndResume`). |

**One item** (`AttentionItem`) carries `kind`, `project`, `story`, `question`, `options`, `evidence`
and `answerWith` (the tool call that answers it, ids filled in). Its texts are cut to 1,300 characters
in total, evidence first, then the end of the question; options and `answerWith` are never cut. Kinds,
most urgent first: `RUN_QUESTION` (a parked run with an unanswered question), `STORY_STOPPED`,
`DELIVERY`, `ANALYST_QUESTION` / `_SUBMIT` / `_PROPOSALS` / `_FAILED`, `AGREE_REQUIREMENTS`, the same
four for `PLANNER_`, `PROMOTE_STORIES`, `START_ANALYST`, `START_PLANNER`, `START_STORY` (not when the
queue starts stories itself), and `FINISHED` when every story is delivered or dropped.

**Answer and resume.** `DecisionAnswers` holds the answers the product acts on per decision kind:
`keep` / `reword` / `allow` / `repair` for a rule question (the first word is what
`RuleQuestions.parse` reads), `retry` with optional text for a blocked task, `note` for a budget or
approval question. It writes the answer through `ControlServiceImpl.resolveDecision` and, when the
decision's run is parked, calls `ConsoleContext.resumeRun`, which sc-app binds to that project's
`WorkflowEngine.advanceAsync`. A run is not handed back sooner than 3 seconds after it parked, and
not twice for the same stop. The journey harness (`CoordinatorAsk`) answers through this same class.

**The record.** Each supervisor act is an `AutonomousDecision` with `actor = "supervisor"` in the
project's existing list (`ArtifactStore.recordAutonomousDecision`), so it also shows where that list
is shown. The per-entity change journal still names "human" for agreeing a requirement, marking a
story ready and sending one back, because those services write that word themselves.

**Supervised running.** `overnight.supervised: true` (`ConsoleContext.supervisedMode`): each tick of
`UnattendedPilot` only starts the next startable READY story. It accepts nothing, and the autonomous
front half, which answers the analyst's questions itself, is not stepped and cannot be switched on.

## 7.6 Development conventions

These are how this repository is worked on, not rules the product enforces:

- **Worktrees go in `C:/work/worktrees/<repo>-<branch>`** — never in `C:/work` directly and never inside
  a project folder. Remove one as soon as its branch is merged. (`~/.swarmcoder/wt` is SwarmCoder's own
  scratch space and is a different thing entirely.)
- **Do not `mvn install` into the shared local repository from a parallel session.** Build with the
  reactor (`-pl <module> -am`); publish only after a merge. Nothing in the code enforces this — the one
  mention in `DEVELOPER_CORRECTIONS.md` §6 says the opposite for a specific historical reason (a stale
  tree-sitter jar was masking a fix in `sc-syntax`).
- **Never point SwarmCoder at a checkout that is itself a git worktree.** JGit resolves the branch name
  inside a linked worktree but not what it points at, so every git answer comes back empty and is
  swallowed as a warning — and the failure surfaces three stages later blaming TEST_AUTHORING. This is
  known, recorded in the brownfield ledger, and **not yet fixed** in the product.
- **A harness run must never reach a paid endpoint.** The address check in
  `EndToEndLoopTest.refuseAnythingButAFreeLocalEndpoint()` enforces it technically; keep it that way.
