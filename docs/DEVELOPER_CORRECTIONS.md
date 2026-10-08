# SwarmCoder — Developer Correction Instructions

**Version 1.0 — 2026-07-09**
**Audience: the implementing coding agent and human reviewer.**
This document is the result of a design audit of the current codebase against
`swarmcoder-technical-spec.md` (normative) and `swarm-coder-architecture.md` (rationale).
It is a *correction contract*: every item here overrides whatever the current code does.
Read it before writing any new code. Where it says MUST, treat it as a requirement.

---

## 1. Audit verdict (context for everything below)

The current build is a **horizontal scaffold**: all 16 modules exist an inch deep, while the
three load-bearing design elements are respectively **absent, wrong, and skipped**:

1. **Verification-first selection is absent.** The pipeline runs dispatch → cluster → judge →
   select with no verify stage. The judge returns a hardcoded score. This is exactly the
   failure mode red-team §8.9 of the architecture doc warns about: a weak verification harness
   makes the judge pick pretty-but-wrong code and the swarm thesis *appears* to fail when
   actually selection failed.
2. **The git topology was wrong.** A single `GitService` pointed at the orchestrator's own
   working directory, with N concurrent workers doing `checkout` on that one shared tree, plus
   a fake zero-byte `mergiraf` binary wired into `.gitattributes` as `* merge=mergiraf`
   (which breaks every future merge in the repo). *Status: defused — see §4.*
3. **Milestone discipline was abandoned.** Spec §21 requires strict milestone order. M0 does
   not exist, M1 acceptance criteria are unmet, yet M2/M3/M4-shaped stubs are spread across
   every module. The largest file in the project is the TUI, which the spec explicitly says
   to keep modest.

**Decision by the author (2026-07-09): the M0 benchmark step is waived.** The pass@k
go/no-go will not be run before building. Consequence: the first real multi-candidate runs
in M2 double as the thesis test — instrument them (per-candidate `SamplingConfig` +
`VerificationReport` archival) so the pass@k data falls out of real usage instead.

---

## 2. Rules that override current code (normative)

R1. **No mock inference in the product.** Delete the `mockInference` flag and every
    `if (mockInference)` branch as the real paths are built. The spec's answer to "test
    without a Spark" is a **FakeVllm test double** (spec §20): an in-process HTTP server with
    scripted completions, used only in tests. A product mock mode that sleeps and emits
    identical dummy diffs exercises nothing and creates false confidence (the user manual
    currently claims it verifies "the core engine works end-to-end" — it does not).

R2. **The selection pipeline order is fixed:** dispatch → **VERIFY ALL** → cluster (survivors
    only) → judge (cluster representatives only) → select. A candidate that has not passed
    verification MUST NOT reach the judge. `Verdicts.survived(report)` in `sc-verify` is the
    single definition of "survived".

R3. **Run state MUST be persisted at every workflow transition.** `GreenfieldWorkflow`
    currently advances a local variable and never writes the `Run` to the store; therefore
    the TUI approval queue (which filters stored runs for `APPROVAL`) is structurally always
    empty, and crash-resume — the precondition for overnight mode — is impossible. Every
    state change goes through `ArtifactStore.append(...)`.

R4. **Koog is IN — decided by the author 2026-07-09.** Agents MUST run through Koog.
    Consequences, in order:
    (a) Rewrite the `KoogFacade` interface to the spec's `AgentRuntime` session contract
    (spec §6.1: `open / next / checkpoint / rollback / fork / compress`) and implement it
    over Koog's agent/session APIs — the current interface is wrong (it mixes in syntax
    concerns like `parseWorkspace`/`indexWorkspace`, which belong to `sc-syntax`, and a
    branch-based "debug fork" that confuses context quarantine with git branching; delete
    those methods).
    (b) Workflow state machines (`sc-workflow`) move onto Koog graph strategies with
    persistence checkpoints — this is also how R3 (crash-resume) gets its mechanism.
    (c) The worker loop, architect interview, judge, and approver sessions all open through
    `AgentRuntime`; no direct `VllmClient` chat calls from role code (the client stays as the
    transport under the facade).
    (d) Koog types stay confined to `sc-runtime` (ArchUnit rule, spec §2).
    (e) Verify early that Koog's Java API and its ACP server role actually meet these needs
    (architecture doc §8.6 warns the Java surface is younger than the Kotlin one) — if a
    Koog gap appears, document it in `sc-runtime/NOTES.md` and work around it behind the
    facade, do not let Koog idioms leak out.

R5. **No new module until an existing one is finished.** Depth before breadth. The definition
    of done for the current phase is the M1 acceptance list (spec §19), minus the waived M0.

R6. **Restore the cheap invariants that are the product's stated edge** (each is <100 lines):
    - PLAN: reject a TaskGraph whose concurrently schedulable tasks have overlapping write
      sets; reject tasks with zero executable acceptance criteria; reject cycles.
    - TEST_AUTHORING: mechanical red-check — acceptance tests MUST fail on the pre-change
      tree before any swarm dispatches.
    - DISPATCH: assert byte-identical shared prompt prefix across a group (log `prefixHash`).
    - CLOUD: one `CloudGate` through which every cloud call passes, enforcing
      `Budget.maxCloudTokens` (currently `Budget` is passed as `null` from `Main`).

R7. **Conventions (spec §21) are not optional:** SLF4J instead of `System.out.println`
    (the stdout-redirect hack in `Main` exists only because the code prints to stdout under
    a TUI — fix the cause, then remove the hack); no `printStackTrace`; no TODO comments in
    delivered code (open questions go to per-module `NOTES.md`); injected `Clock` and seeded
    `RandomGenerator`; ArchUnit boundary rules for Koog (sc-runtime only) and tree-sitter
    (sc-syntax only) in addition to the existing store rule.

R8. **Remove the scaffold generators.** The `scaffold_*.py` files in the repo root generated
    the current skeleton. They must not be run again (they would overwrite corrected code).
    Delete them or move them to `dev/` as historical artifacts.

---

## 3. Spec updates — decisions newer than the docs

The design conversation continued past the version of `swarmcoder-technical-spec.md` in this
repo. The following supersede the spec until it is rewritten:

S1. **The roster is CONFIGURATION, not a fact about the code.** This item used to name two
    specific models and their launch flags. It was wrong twice over by 2026-08: the box holds
    one model, and a different one. Naming models here is what made the mistake possible, so
    the rule now is the rule and the models are an appendix to it.

    **The rule.** Model identity and model BEHAVIOUR both come from config, through the
    `ModelProfile` registry (spec §6.2) and `ModelQuirks` (`sc-inference`). No model name, no
    chat-template argument, no in-prompt directive and no context or memory figure may be a
    literal in code. Adding a model is: pick a named starting shape in Settings, fill in the
    endpoint, correct what differs, measure the two scheduler numbers, mark it measured.

    **What is actually on the box — measured 2026-08-28, and this replaces what was written
    here on 2026-08-27.** The paragraph that stood here said the box held a 125-billion-parameter
    mixture-of-experts model with about 6 billion parameters active per token, that only one
    would fit, and that this was a memory limit. **All of that was wrong.** It was written from
    planning notes, never from the box.

    | | |
    |---|---|
    | Endpoint | `http://192.168.0.10:8002/v1` — **port 8002**. The saved config's 8000 is dead. |
    | Model id | `qwen3.8-27b` — a **27B** model. Not 125B, and not a mixture of experts. |
    | Serving software | **sglang**, not vLLM. Nothing in the code or the rest of these docs said so; every class named `Vllm*` is talking to sglang. |
    | Served context | 262144 tokens |
    | Single-stream speed | 400 tokens in 13.6s ≈ **29 tokens/second** |
    | Reasoning | **ON by default.** `chat_template_kwargs: {"enable_thinking": false}` switches it off and is accepted by this server — verified. Left on, a request for the single word "OK" returned empty content with the whole budget spent deliberating. |
    | Tool calling | works natively, with correct JSON arguments — verified |

    **Consequence for the swarm, and an OWNER DECISION that is open.** The reason
    `swarm.splitAcrossFamilies` was written off is gone: it was written off because only one
    model was believed to fit, and that belief rested on the 125B figure. A 27B model is a
    quarter of that, so **a second model may now fit again.** That matters more than it sounds:
    of the three mechanisms meant to make parallel attempts genuinely differ, two were found on
    2026-08-27 to do nothing at all (the per-worker seed is never sent, and splitting across
    families had nothing to split across), leaving only the temperature spread. A second model
    family is the strongest remaining lever, and it is the one that makes N attempts a real
    portfolio rather than N samples of one distribution.

    **Nothing has been implemented for this and nothing should be until the owner decides.** The
    decision is whether to run a second model on the box, and which — it costs memory that
    concurrency would otherwise use, and the trade between "more workers on one model" and
    "fewer workers across two models" has not been measured.

    **The quirk knowledge is kept as WHY each setting exists.** Every one of these was learned
    the hard way against Qwen 3.6 and is now a field on the model profile rather than a
    constant. Read this as "here is what can go wrong and what to set when it does", not as
    "here is what the model is":
    - *A reasoning switch, and which kind.* Qwen 3.6 defaults to thinking mode and burned the
      whole output budget deliberating; disabling it took a call from 20s to 4.5s. Two
      mechanisms, because the two request paths differ: the role client can send a
      chat-template argument (`enable_thinking: false`), the worker path cannot and uses an
      in-prompt directive (`/no_think`). Both the argument NAME and the directive TEXT are Qwen
      conventions and mean nothing to another model, so both are now configurable text and
      either can be set to "none" for a model that has no such mechanism. Sending an argument a
      chat template does not know is noise at best and an error at worst — which is why the
      vision endpoint was hardcoded to skip it, and is now simply a profile that names none.
    - *Tool-call history as plain text.* vLLM chat templates (Qwen's in particular) iterate
      tool-call `arguments` as a mapping while OpenAI-spec clients send a JSON string, and the
      second turn dies with 400 "Can only get item pairs from a mapping". Textifying the
      history fixes it and tool *definitions* stay native, so the model still calls tools.
      See `sc-runtime/NOTES.md` before ever turning this off for a model — but it is a
      per-model setting now, not something forced on every model that will ever run here.
    - *An output cap.* Without one a rambling generation streams toward the served context
      ceiling; this once stalled TEST_AUTHORING for the full run ceiling. 32k is the default.
      The worker path used to send no cap at all — only role calls were bounded — which is
      fixed.
    - *HTTP/1.1.* Java's default h2c upgrade confused one uvicorn/vLLM build: requests arrived
      with an empty body and failed 400 "body Field required". Pinned to 1.1 by default,
      switchable per model when a server is known good.
    - *JSON response format.* Some endpoints honour `response_format: json_object` and some
      need the schema sent as `guided_json`. Independently of that, local models emit malformed
      JSON leads (Qwen 3.6 produced a doubled opening brace even under json_object), so
      `LlmJson` parses defensively for everyone. A run report full of 0.5 judge scores is the
      signature of a broken judge, not of mediocre candidates.
    - *Served ceiling and working budget.* 64k served is crash-avoidance headroom, not a
      target; 32k is what one session may occupy. Both must match how the server was actually
      started, so both are per-model.
    - *Memory per token and concurrent sequences.* The two figures the scheduler admits work
      against. They were measured on a dense 27B and do not transfer — see §14.

S2. **Context budget:** 32K working context per session, enforced by `TokenBudget`, against a
    64K served ceiling.

S3. **Adaptive N (schedule for M4, record now):** dispatch a first wave of N=2–3 per task and
    widen to the full swarm only when the first wave disagrees or fails verification. Easy
    tasks resolve cheaply; compute concentrates where variance exists.

S4. **Decomposition rule (Architect prompt + PLAN invariant):** decompose down to the
    smallest unit that still has a mechanically verifiable acceptance test and a clean write
    set — and no further.

S5. **M0 waived** (author decision, see §1). Keep `sc-evals` and candidate archival so the
    equivalent data accrues from real runs.

S7. **The two wizard agents get their own role slots** (2026-07-29): `roles.requirementsAnalyst`
    (the BRD author — documents → requirement graph) and `roles.storyPlanner` (agreed checks →
    stories). Both fall back to `roles.chat`, which itself falls back to `roles.utility`, so every
    config written before this keeps behaving identically.
    **Why they lacked slots:** they were the `/brd` and `/backlog` chat modes, so they ran on the
    chat model. When those commands became the "Analyse documents" and "Plan stories" wizards
    (`GUIDED_FLOWS_DESIGN.md` G2), each wizard inherited the chat model with the code. The result was
    that the top of the requirement chain — no swarm behind it, no test in front of it — sat in a
    slot sized for conversation, while the merely mechanical half of intake (`roles.vision`) had its
    own. **The rule this follows:** a role needs frontier capability when nothing downstream can
    mechanically check it. That is true of the analyst, the planner, the architect, the reviewer and
    the test author; it is not true of workers, who get N attempts and a verification gate.
    `RoleForm` labels rows from the role id alone, so both appear in Settings with no UI change.

S6. **LSP servers join the design** (author decision 2026-07-09): language servers
    (Eclipse JDT LS for Java; typescript-language-server, rust-analyzer, pyright for the
    secondary stacks) are added **for the Architect and the Verifier, and optionally for
    workers**. Placement and constraints:
    - *Architect (DESIGN/PLAN):* semantic repo queries — definitions, references, workspace
      symbols, call hierarchies — against **one** server instance on the main repo checkout.
      This grounds API contracts and write-set partitioning in real symbols and complements
      the tree-sitter repo map (tree-sitter stays: it is deterministic and lives in the
      shared prompt prefix; LSP is on-demand and never enters the prefix).
    - *Verifier:* LSP diagnostics as a **cheap pre-compile early-kill signal** between the
      parse gate and the full compile stage — advisory only, never a substitute for the real
      build (LSP misses annotation processors, codegen, resource pipelines). Mind the cost:
      one JDT LS per candidate worktree at N=8 is prohibitive; either share one server and
      feed it changed files via didOpen overlays, or restrict LSP verification to the
      integration workspace.
    - *Workers (optional, off by default):* at most two narrow tools
      (`lsp_diagnostics(file)`, `lsp_signature(symbol)`) with hard-truncated results, so
      tool outputs stay small and per-worker prefix reuse is preserved.
    - *Architecture:* new `LspService` facade in its own module (`sc-lsp`), same confinement
      treatment as Koog/tree-sitter — LSP client types must not leak past the facade
      (ArchUnit rule). Schedule after the worker loop is real (build-order item 4), not
      before: it is an enhancer, not a prerequisite.

---

## 4. What was fixed in this correction pass (2026-07-09)

Done alongside this document — do not regress these:

F1. **`sc-verify` is now a real verification harness** (see §5 for the design). The previous
    `GradleVerifier` printed commands and returned success without executing anything.

F2. **Git stubs defused** in `sc-git`/`sc-app`:
    - `configureMergirafDriver` no longer fabricates an empty `mergiraf` binary (which, wired
      as `* merge=mergiraf`, broke all merges). It now configures the driver only if a real
      binary is found on `PATH` or in `~/.swarmcoder/bin`, writes language-scoped patterns,
      idempotently, to `.git/info/attributes` (not the working tree), and logs a warning and
      does nothing otherwise.
    - `archiveCandidateBranch` uses a proper `RefUpdate` to `refs/swarm-archive/...` plus
      branch delete (the old `branchRename` to a fully qualified ref was broken).
    - Worker branch isolation now goes through **per-candidate git worktrees**
      (`GitService.addWorktree`), not concurrent `checkout` calls on one shared working tree.
      Note: worktree creation shells out to the `git` CLI — JGit cannot create linked
      worktrees; this is a documented deviation from the spec's "pure JGit" line.
    - `GitService` is only enabled when the new top-level `repoPath` config key points at a
      target repository. The old behaviour — operating on `user.dir`, i.e. SwarmCoder's own
      checkout — is gone. With no `repoPath`, git operations are disabled and logged.
    - The hardcoded LAN IP fallback (`192.168.0.10`) in `DependencyGraph` is replaced by
      `http://localhost:8000`.

F3. **Verification wired into the pipeline** per R2: `SwarmEngineImpl` now verifies every
    candidate in its worktree before clustering, and only `SURVIVED` candidates are clustered,
    judged, and selectable.

---

## 5. The verification harness (`sc-verify`) — how it works now

Built 2026-07-09. The author has flagged that this was meant to be hand-built and may be
reworked; treat the *interfaces* as stable and the internals as reviewable.

**Execution abstraction.** `Verifier.verify(ExecTarget, Task, VerifySpec)` runs against an
`ExecTarget`:
- `LocalProcessExecTarget` — runs commands via the platform shell (`cmd.exe /c` on Windows,
  `sh -c` elsewhere) in a workspace directory. Usable today, and used for worktree
  verification until sandboxes are live. Kills the process tree on timeout.
- `ActionServerExecTarget` — HTTP client for the in-sandbox action server (`/exec`, `/read`).
  This is the spec-compliant target once `sc-sandbox` actually launches containers with
  mounted worktrees. Known gap: the action server's `/exec` does not yet accept a timeout;
  add it when hardening `sc-sandbox-action-server`.

**Spec loading.** `VerifySpecLoader` reads `.swarmcoder/verify.yaml` from the workspace
(spec §10.1 format). If the file is absent, verification is skipped **loudly** and the
candidate is marked unverified — this is a temporary M1 allowance; per architecture §8.1,
tasks without executable verification must eventually not swarm at all.

**Pipeline (spec §10.2, fail-fast, cheapest first):**
compile → acceptance tests → existing tests → lint. Compile failure short-circuits
everything; acceptance failure short-circuits existing/lint (the candidate is already dead —
save the compute, the report records what ran). Every stage's output feeds a 200-line
`logTail`.

**Test result parsing is structural, not exit-code guessing:** JUnit XML
(`build/test-results/test` for Gradle, `target/surefire-reports` for Maven, overridable via
the `testReports` block in `verify.yaml`). Report directories are cleared before each test
stage so acceptance results never bleed into existing-test results. Failures carry
`testId` (`Class#method`), message, and a truncated trace — this is the evidence brief for
the repair round and the judge.

**Survival:** `Verdicts.survived(report)` = compiles ∧ no acceptance failures/errors ∧ no
existing-test failures/errors. Lint results are recorded but advisory (not a kill) for now.

**Browser checks (spec §10.3) — implemented** (completion pass, same day): `BrowserVerifier`
runs Playwright headless Chromium on the workstation with a deterministic 1440×900 viewport,
reduced motion, network-idle waits; collects console errors (folded into the page's
`AssertionResult` list under selector `console` when `assertNoConsoleErrors` is set),
visibility assertions, and full-page screenshots into the blob store. Two serve modes:
a `serve` shell command with a `{PORT}` placeholder plus a polled `readyProbe` (requires the
local exec target until the action server gains background exec), and `static:<dir>` which
serves workspace files through a throwaway HTTP server reading via the ExecTarget — the
static mode works against any target, sandboxes included. Browser cleanliness is part of
`Verdicts.survived`. The old `BrowserChecker` stub is deleted.

**Also completed in the same pass:** `RedChecker` — the mechanical TEST_AUTHORING red-check
(acceptance tests must genuinely fail pre-change; infrastructure failures are explicitly NOT
a valid red); structured lint parsing (checkstyle-format XML via `lintReports` in
`verify.yaml`, exit-code fallback otherwise); blob-store wiring (`fullLogRef` and
`screenshotRef` are content hashes via a `BlobSink` adapter over sc-store's BlobStore, wired
in `DependencyGraph`); default JUnit-XML report locations for node/cargo/python (these
toolchains must emit JUnit XML: vitest/jest `--reporter=junit`, pytest `--junitxml`,
cargo-nextest); action-server hardening (`/exec` timeout + capped output + `timedOut` flag,
`/read` maxBytes). Shared test-stage mechanics live in `TestStageRunner`, used by both the
pipeline and the red-check. The end-to-end browser test is no longer opt-in: since 2026-08-28 it
runs in an ordinary build wherever Playwright's Chromium is installed, and announces itself when
it is skipped. See `docs/TESTING.md`.

**Still open in sc-verify** (in priority order): exercise `ActionServerExecTarget`
end-to-end once sandboxes launch for real; background-exec support in the action server so
`serve`-mode browser checks work in-sandbox. **spotbugs-native XML — DONE (2026-07-11):**
`CheckstyleXmlParser.parse` now auto-detects format per document from the root element
(`<BugCollection>` → `SpotBugsXmlParser`, else checkstyle-format), so a run can mix both with
no `verify.yaml` change; priority-1 bugs = errors, else warnings (lint stays advisory).
**LSP pre-compile signal — DONE (2026-07-11):** `CommandPipelineVerifier(BlobSink, LspService)`
runs an advisory `LspPrecheck` over the task's `.java` write-set (resolved via the new
`ExecTarget.localRoot()` seam, capped) before compile, folding a rendered block into the log;
never gates survival. Wired at FINAL_INTEGRATION only (single workspace) via
`FinalIntegrator(..., LspServiceFactory)` — per-candidate LSP is prohibitive, so the shared
per-worktree server is workspace-guarded and no-ops on non-matching roots. Opt-in through the
`swarmcoder.jdtLsHome` system property (default `NONE`).

---

## 6. Corrected build order (replaces the current trajectory)

Work strictly in this order; each item has a mechanical done-condition.

1. ~~Verification harness~~ — **done, including browser checks, red-check, lint XML,
   blob refs** (§5), review internals.
2. ~~Git worktree topology + stub defusal~~ — **done in this pass** (§4/F2).
3. ~~Run-state persistence + resume~~ — **done** (see §8): every transition durable,
   `resumeAll()` at startup, proven by `WorkflowPersistenceTest` across a store reopen.
4. ~~Koog-based worker loop~~ — **done (2026-07-10)**. `KoogFacade` is deleted; the spec §6.1
   `AgentRuntime` facade is implemented over the real Koog 1.0 Java API (`KoogAgentRuntime`
   in sc-runtime: functional-strategy agent, blocking `requestLLM → executeTools →
   sendToolResults` loop, `report_done` as the stop signal, TurnGuard consulted every turn).
   `WorkerLoop` is real: per-candidate worktree, `WorkerToolbox` tools (`exec`, `read`,
   `apply_diff` via `git apply` with mechanical write-set + protected-test-dir enforcement,
   `report_done`), `EarlyKillEnforcer` wired into the guard (malformed-output turns, token
   budget, write-set violations), diff captured from the worktree and committed. Koog
   confinement is enforced by `KoogBoundaryTest` (ArchUnit, whole codebase). Context-Ledger
   session surface (checkpoint/rollback/fork/compress) is declared but deferred to M4 —
   see `sc-runtime/NOTES.md`. Still open within the loop: cross-candidate kill rules
   (COMPILE_FAIL_TWICE relative to siblings, SUPERSEDED) need group-level coordination and
   land with the repair round. **The M1 acceptance criterion (typed Task in → verified green
   diff out on the demo repo) now needs a live vLLM endpoint to validate — first live run.**
5. ~~FakeVllm test double + deletion of `mockInference`~~ — **done (2026-07-10)**.
   `mockInference` is gone from config and every production class. `FakeVllm` (sc-swarm test
   scope) is an in-process OpenAI-compatible server (JSON + SSE) with conversation-routed
   scripted replies; `SwarmEngineFakeVllmTest` drives the REAL stack — KoogAgentRuntime over
   HTTP, worker tool loop, write-set enforcement, verification, clustering, judging (now a
   real schema-constrained LLM call with neutral-score fallback, spec §11.5), selection, and
   candidate archival into `StoreRoot.candidateArchives` (the eval harness dataset, spec §8.9)
   — including the WRITESET_VIOLATION kill path. The old `SandboxProbeExecutor` stub (which
   launched a real Docker container per candidate and returned fake probe strings that would
   have poisoned behavioral hashes) is unwired; probes return in M4.
6. ~~ModelProfile registry + CloudGate + budgets~~ — **done (2026-07-10)**.
   `ModelProfileRegistry` (sc-runtime) is the single source of model identity, populated
   from config in `DependencyGraph`; the dispatcher's diversity matrix and worker endpoints
   come from it — no model-id string literals outside config. `CloudGate` guards every
   cloud call (judge, planner) with estimate-based accounting (chars/4 until clients parse
   usage frames); the first breach parks a `BUDGET_EXTENSION` decision, exactly once, and
   subsequent cloud calls fail fast (judge degrades to unjudged-neutral, planner to its
   offline fallback). NOTE: EclipseStore requires JVM flags
   (`--add-exports java.base/jdk.internal.misc=ALL-UNNAMED` + `--add-opens` for
   java.util/lang/time) — set for surefire in the parent POM; launcher scripts must match.

6b. **Observability foundation — done (2026-07-10), UI designed.** Author requirement:
   real-time swarm graph + full session drill-down + EclipseStore persistence +
   post-analysis. The data foundation is built: `TraceEvent`/`AgentSessionRecord`
   (sc-domain), `TraceHub` (sc-runtime) fed by `KoogAgentRuntime` at every step, complete
   session records persisted to `StoreRoot.agentSessions()` (schema v2), oversized payloads
   in the blob store, live listener API for the observer. Covered by
   `SwarmEngineFakeVllmTest`. The UI is a zeroz4j web app embedded in the orchestrator JVM
   (EclipseStore is single-process) — full design, view specs, service surface, phasing
   (O0 spike → O3), and the GUI-vs-TUI ruling in **docs/OBSERVABILITY_DESIGN.md** —
   amended same day by the author: **the GUI is the complete SwarmCoder Console** (adds
   intake, approvals, decision resolution, settings, guidelines — full TUI parity); the TUI
   is frozen as a legacy convenience and gains nothing new.
7. ~~PromptBundle + prefix discipline~~ — **done (2026-07-10)**. `PromptBundle` (sc-runtime)
   enforces the spec §6.3 segment order (systemRole → workflowRules → guidelines →
   designExcerpt → taskInstructions → knowledgeBrief → repoMap) regardless of builder call
   order; the shared prefix is built ONCE per task group in `SwarmDispatcher` (write sets
   sorted so Set iteration order cannot perturb the bytes), its SHA-256 `prefixHash` is
   logged per dispatch, and per-worker persona is appended strictly AFTER the shared prefix
   (`forWorker`). Byte-identity across the group holds by construction (single build) and is
   unit-tested (`PromptBundleTest`). Guidelines/design/brief/repo-map segments plug into the
   existing builder slots as those features land (M3+).
8. ~~PLAN/TEST_AUTHORING invariants~~ — **done (2026-07-10)**. `TaskGraphValidator`
   (sc-workflow) enforces at PLAN: acyclicity, edge integrity, and disjoint write sets
   among concurrently schedulable tasks (path-prefix aware; overlap is allowed only when a
   dependency path orders the two tasks). GreenfieldWorkflow rejects + regenerates once,
   then uses the valid-by-construction single-task fallback. Missing acceptance criteria is
   a WARNING until the test-author role exists (M3) — enforcing it now would reject every
   graph the current planner can emit. TEST_AUTHORING runs the mechanical red-check when a
   target repo with acceptance commands is configured: acceptance tests that PASS pre-change
   park the run (state persists at TEST_AUTHORING, `BLOCKED_TASK` decision queued with the
   evidence); an empty acceptance suite is the documented M1 allowance.
9. **M2 selection hardening — repair round DONE (2026-07-10).** Per spec §11.5:
   on zero survivors, the engine seeds up to 2 small repair swarms (2 workers each) from the
   most promising FAILED candidates' branches — the worktree starts from the failed branch
   (`GitService.addWorktree` with start point), and the failure evidence (compile state,
   failing tests, verification log tail) is baked into that seed group's shared prefix
   (own `prefixHash`). Repairs are verified AS THEY COMPLETE; the first survivor supersedes
   every other in-flight repair worker (`GroupSignal` → `KillReason.SUPERSEDED` via the
   TurnGuard — the freed-capacity rule of spec §11.3). One round only; total failure queues
   a `BLOCKED_TASK` decision carrying the per-candidate evidence brief. `executeRun` now
   runs tasks in **topological waves** (Kahn levels) instead of all-parallel, so dependents
   never execute before prerequisites. Covered by FakeVllm tests (recovery + blocked paths)
   and `TopologicalWavesTest`.
   Still open in M2 hardening: behavioral clustering probes (M4 by spec), sibling-relative
   COMPILE_FAIL_TWICE (needs per-turn incremental verification inside the worker loop),
   Librarian-refreshed briefs on API_MISUSE classification (needs the Librarian, M3).

10. **M3 front half — Architect, DesignReviewer, TestAuthor DONE (2026-07-10).**
   - `ArchitectClient`: single-pass DesignDocument from the goal (requirements/decisions/
     contracts/risks, typed, persisted to `StoreRoot.designs`), revision against reviewer
     objections (same id, bumped revision), and TaskGraph planning WITH write sets, read
     sets, and acceptance criteria naming test classes — the S4 decomposition rule is in the
     planner prompt. The old criteria-less planner in GreenfieldWorkflow is gone.
   - `DesignReviewerClient`: rubric critique (completeness / testability / write-set
     partitionability); one revision loop then proceed with the verdict + objections
     recorded on the design (`ReviewVerdict`). Fails soft — review unavailability never
     blocks a run.
   - `TestAuthorClient`: writes JUnit 5 acceptance tests per task into the protected dir
     (`src/test/java/swarm`), mechanically confined (traversal/escape writes rejected),
     committed to the target repo before dispatch; the existing red-check then enforces
     that they FAIL pre-change or the run parks.
   - Per-role endpoints from config (`roles.architect/designReviewer/testAuthor`), all
     behind the one CloudGate; `LlmJson` (sc-inference) is the shared defensive parser
     (JudgeClient refactored onto it).
   - Covered by `FrontHalfWorkflowTest` (scripted roles → typed artifacts, review loop,
     plan with write sets/criteria) and `TestAuthorClientTest` (authoring + confinement).
   - Deliberately deferred: the interactive bounded interview (needs the Console intake
     conversation, spec §14) and the Librarian/KnowledgeBrief (Context7 integration).
   **Next candidates:** Librarian (M3 tail), FINAL_INTEGRATION topological merges,
   Console O2, or a live front-half run against the Spark on the demo repo.

11. **FINAL_INTEGRATION + Librarian v1 + Console O2 — DONE (2026-07-11).**
   - `FinalIntegrator` (sc-workflow): winners' branches merged into
     `swarm/integration/<runId>` in a dedicated worktree, TaskGraph topological order,
     git-CLI merges (so the Mergiraf driver applies when configured), **full verification
     after every merge** — a conflict or red verification parks the run at
     FINAL_INTEGRATION with a decision. Tested over a real repo including the conflict path.
   - **Librarian v1** (fully local, spec §13): `ManifestParser` (pom.xml + package.json
     coordinates), REAL tree-sitter signature extraction (`TreeSitterSyntaxService.signatures`
     — Java classes/interfaces/records/methods/constructors as header text; the old stub
     returned nothing), capped `KnowledgeBrief` persisted at PLAN and rendered into the
     shared prefix's `knowledgeBrief` slot at dispatch (initial swarm AND repair waves).
     Context7/web docs remain behind `Context7Client` for later; the internal-API half
     already attacks the "small model invents an API" failure class.
   - **Console O2**: `payloadRef` on trace DTOs, `blobText(ref)` + `recentSessions(max)`
     RMI, kind-specific inspector rendering (chat bubbles for LLM output, code blocks for
     tool calls/results, warning rows for kills/nudges), "Load full payload" from the blob
     store, and persisted-session browsing on the Swarm Board. Browser smoke re-verified.
   Remaining tails: Context7 live fetch + `lookup_api` worker tool, live end-to-end run
   (deferred by author).

13. **M4 Context Ledger (learning loop) — DONE (2026-07-11).**
   - `HistoryRag` (sc-knowledge): completed session transcripts indexed into Lucene, keyword
     `search(query, k)` → the spec §12.3 `search_history` ("have we seen this error before?").
     Sessions index automatically on `sessionEnded` (TraceHub listener); re-index replaces.
     Exposed on `ObserverService.searchHistory` and the Console. NOTE: `lucene-queryparser`
     was skewed at 9.12.0 vs core 9.10.0 (`IntHashSet` NoClassDefFound) — now aligned to
     `${lucene.version}`; keep all Lucene artifacts on one version.
   - `GuidelineExtractor` (sc-knowledge): utility model mines a completed run's sessions
     (failures first) for durable lessons, dedups by normalized-token Jaccard (≥0.6) against
     existing guidelines, and writes genuinely-new ones as **PROPOSED** guideline files
     (front-matter `status: proposed`, `source: extraction`) under `.swarmcoder/guidelines/`.
     They flow through `GuidelineSync` but never touch prompts until promoted — closing the
     learning loop the author asked for ("core instructions saved as guides"). Runs at the
     FINAL_INTEGRATION→APPROVAL transition. Tested with a scripted utility endpoint.
   - `ContextLedger` ties history indexing + extraction together, wired in `DependencyGraph`
     and onto `WorkflowEngine`.
   - **Debug-fork quarantine (§12.2)** is realized by the existing repair round (fresh
     context from a compact failure brief). **Intra-session checkpoint/rollback/compaction
     (§12.1/§12.3)** remain deferred to Koog snapshot integration — see sc-runtime/NOTES.md;
     low priority since swarm worker sessions are short.
   **The build now covers M0(waived)→M4 in substance.** True remaining tails: Context7 live
   docs + `lookup_api` tool; intra-session Koog snapshots; Docker sandbox hardening (WSL2, §7 Q1).

14. **First LIVE full-workflow run (2026-07-11) — pipeline validated end-to-end; thinking-mode
   fix applied.** `LiveEndToEndTest` (sc-workflow, opt-in) drove a greenfield run against the
   Spark (qwen36-27b) on a throwaway repo. It executed the ENTIRE front half correctly:
   DESIGN (3 requirements, 1 API contract, 3 risks) → DESIGN_REVIEW (APPROVED) → PLAN
   (**2-task graph with a dependency edge and DISJOINT write sets** — Calculator.java vs
   CalculatorTest.java — the validator accepted it) → TEST_AUTHORING (committed 2 acceptance
   test files; the multiply test with zero/negative edge cases) → EXECUTING (workers correctly
   added `multiply`, confirmed in worktrees). It parked at EXECUTING only because it hit the
   15-min test ceiling — NOT a failure. Root cause of the slowness, now FIXED:
   - **Qwen 3.6 thinking mode was on** (the design doc's explicit warning; I'd wired the
     tool-history conversion but never the disable flag). Every Spark call generated a
     multi-minute reasoning prefix. Fix, verified live (20s→4.5s per call): `VllmClient` now
     sends `chat_template_kwargs:{enable_thinking:false}` (covers architect/reviewer/
     test-author/judge/planner/guideline-extractor); `KoogAgentRuntime` appends the model's
     in-prompt `/no_think` directive to the worker system prompt (Koog's OpenAI client can't
     send chat_template_kwargs). Appended AFTER the shared prefix so prefix-cache alignment
     holds.
   - `RedChecker` compile-fail semantics — **FIXED**: an acceptance-suite compile failure
     whose output carries compiler signatures (`cannot find symbol`, `compilation failure`,
     Kotlin `unresolved reference`, …) is now a confirmed RED state (the tests reference
     symbols the task will add — TDD), distinguished from genuine infra errors (no such
     markers). Tested.
   **Re-run with thinking disabled REACHED APPROVAL in 198s** — full pipeline green live:
   DESIGN→REVIEW→PLAN→TEST_AUTHORING→EXECUTING→FINAL_INTEGRATION (integrated onto
   `swarm/integration/<runId>`)→APPROVAL. The M0-waived→M4 build is proven end-to-end on the
   Spark.

15. **`lookup_api` worker tool + Context7 hardening — DONE (2026-07-11).** Spec §4.8/§13:
   workers get a fifth tool, `lookup_api(query)`, for API/library questions — served by the
   Librarian from the pre-warmed local docs index, falling through to Context7 once on a
   miss (best-effort). Decoupled via the `ApiLookup` functional interface (sc-runtime),
   threaded engine→dispatcher→worker→toolbox, backed in sc-app by `Librarian.lookupApi`.
   `Context7Client` now connects lazily and fails QUIETLY when the MCP server is down
   (localhost:3000) — lookup_api degrades to a "inspect the source with read/exec" nudge
   rather than erroring. Workers still never touch the open web (no-network sandbox intact).
   Tested.

16. **`sc-lsp` — LSP facade foundation — DONE (2026-07-11).** Spec §S6. New module `sc-lsp`
   with the `LspService` facade (spec §S6), facade-clean domain types (`LspDiagnostic`,
   `LspSeverity`) so no consumer touches LSP4J, and `JdtLanguageServer` — an Eclipse JDT LS
   client over LSP4J (stdio, lazy launch from a configurable product home, per-OS Equinox
   config, async `publishDiagnostics` collected per URI). Every failure mode (home missing,
   launcher absent, process death, timeout) degrades to `isAvailable()==false` + empty
   diagnostics and NEVER throws — LSP is an enhancer, never a prerequisite, and
   `LspService.UNAVAILABLE` is the default no-op. Confinement enforced by `LspBoundaryTest`
   (ArchUnit, whole codebase): `org.eclipse.lsp4j..` may only be referenced from
   `com.swarmcoder.lsp..`. The verifier consumer seam is `LspPrecheck` (sc-verify): an
   **advisory** pre-compile aggregator (errors first, then warnings, capped, rendered into
   the log) that skips cleanly when no server is wired. 12 unit tests (degradation, mapping,
   rendering, aggregation, boundary). **Live-launch caveat:** the JDT LS process path can
   only be validated with a JDT LS distribution installed (`jdtLsHome`); like the Docker
   sandbox, that needs the environment stood up. Tests cover the facade + graceful
   degradation, not a live server. Remaining wiring: architect semantic queries
   (definitions/references), the verifier `LspPrecheck` call site, optional worker tools
   (`lsp_diagnostics`/`lsp_signature`).

   **Update (2026-07-11): verifier integration DONE.** `ExecTarget.localRoot()` seam +
   `CommandPipelineVerifier(BlobSink, LspService)` advisory precheck over the task write-set +
   `FinalIntegrator` per-worktree `LspServiceFactory` wiring + `swarmcoder.jdtLsHome` opt-in —
   all default-off, unit-tested with fakes (live JDT LS still needs a real product install to
   validate). Deferred on purpose: architect semantic queries; optional worker
   `lsp_diagnostics`/`lsp_signature` tools — the spec marks worker LSP "optional, off by
   default", and a shared server can't serve per-worker worktrees while a per-worker server is
   prohibitive at N=8, so that tool would be dead-in-practice plumbing until someone accepts
   the cost. Not built rather than build dead code.

### Genuinely remaining (milestone-level or low-priority; not quick tails)

17. **Docker sandbox — BUILT & VERIFIED (2026-07-12).** Spec §8. Sandboxed per-candidate
   verification now works end-to-end against a live daemon (Docker Desktop on Windows — a
   deliberate deviation from §7 Q1's WSL2-ext4; bind mounts of Windows paths work, just slower
   than ext4). Delivered:
   - **Worker image** (`sc-sandbox-action-server/Dockerfile` → `swarmcoder-worker:latest`,
     726 MB): `eclipse-temurin:25-jdk` + git + Maven 3.9.9 + the action-server fat jar at
     `/opt/action-server.jar` (the module's shade plugin builds it). Verified in a container:
     `/health`, `/exec` (git/mvn present), `/read`, and a real **offline** `mvn -o compile`
     (host `~/.m2` bind-mounted read-only) → `COMPILE_OK`.
   - **`DockerSandboxManager`** hardened: config-driven (image, CPUs, mem, dockerHost, m2), one
     container per worktree with port 8080 published to an **ephemeral loopback** host port,
     `/health` wait, CPU/memory caps, `SandboxHandle`, kill/remove, `isAvailable()`. Proven by
     the opt-in `DockerSandboxLiveTest` (env-gated — see below).
   - **Transport fix:** docker-java's Apache/httpclient5 transport does **not** support Windows
     named pipes (it falls back to `tcp://localhost:2375` → connection refused). Switched
     `sc-sandbox` to `docker-java-transport-zerodep` + `ZerodepDockerHttpClient`, which speaks
     npipe. **If Docker is ever unreachable from Java, check the transport before the daemon.**
   - **Pipeline wiring:** `SwarmEngineImpl.verifyOne` → `verifyWith` launches a sandbox per
     candidate and verifies via `ActionServerExecTarget`, **falling back to local on any sandbox
     error** (a Docker hiccup never blocks a run). Opt-in: `-Dswarmcoder.sandbox.enabled=true`
     (+ optional `swarmcoder.sandbox.image` / `.dockerHost` / `.m2`); default OFF →
     `LocalProcessExecTarget`, so the green pipeline is byte-for-byte unchanged.
   - **Test-infra gotcha:** surefire runs `*Test` not `*IT`; and an empty pom `<properties>`
     default overrides CLI `-D` in the fork, making a gate silently skip. The sandbox live test
     is gated by the **env var** `SWARMCODER_SANDBOX_TESTS=true` (propagates reliably to forks).
   - **Cross-platform (2026-07-12):** works on Windows (Docker Desktop) and Linux (native/WSL2
     Docker). The daemon endpoint auto-detects per OS via the zerodep transport; bind sources are
     passed in their **native** form — do NOT normalize Windows `C:\...` to forward slashes:
     docker-java only recognizes a drive path with backslashes, and `C:/...` makes the drive colon
     look like a host:container separator, breaking container inspect. POSIX paths need no change.
   - **Full E2E VERIFIED (2026-07-12):** `LiveEndToEndTest` with `-Dswarmcoder.sandbox.enabled=true`
     against the Spark reached **APPROVAL**, with a `swarmcoder-worker` container observed live
     during EXECUTING running the candidate's verification. Complete chain proven: LLM pipeline →
     sandboxed per-candidate verify in Docker → APPROVAL.
   - **Prerequisite fix — `max_tokens` (2026-07-12):** the E2E first stalled for the full 15-min
     ceiling at TEST_AUTHORING. Root cause: `VllmClient` sent **no `max_tokens`**, so a rambling
     generation streamed toward the served context (the Spark serves 64k only as crash-avoidance
     headroom, not a target). Added a bounded cap — **default 32k** (`-Dswarmcoder.llm.maxTokens=N`)
     — the by-design ceiling: room for legitimate structured outputs and a reasoning trace, while
     bounding a runaway. General role-call robustness fix, not sandbox-specific.
     **Superseded 2026-08-27 (§14):** the system property is gone; the cap is per model
     (`ModelQuirks.maxOutputTokens`) and now applies to workers too, which had never sent one.
   - **Thinking mode now configurable (2026-07-12):** disabling Qwen thinking was a latency call,
     not a quality one, and it stays **off by default** — but it is no longer hardcoded. VllmClient
     (roles) and KoogAgentRuntime (workers, `/no_think`) both honor `-Dswarmcoder.llm.thinking=true`
     to re-enable reasoning. **Superseded 2026-08-27 (§14):** the system property is gone. Whether
     a model reasons is per profile and per role, and HOW it is switched off — the chat-template
     argument name, the in-prompt directive text, or neither — is per model, because
     `enable_thinking` and `/no_think` are Qwen conventions.
     Rationale: the verification-first swarm (N candidates + tests + judge +
     repair) compensates for per-answer reasoning depth, so workers stay fast; the reasoning-heavy
     roles (architect/planner/judge — few calls, no swarm redundancy) are where thinking-on is worth
     trying. See the thinking/quality discussion in the session notes.
   - **Test logging fixed (2026-07-12):** `sc-workflow` test scope now binds logback
     (`logback-test.xml`, root WARN / `com.swarmcoder` INFO) so the live E2E emits workflow +
     sandbox logs instead of the SLF4J no-op provider.
   - **Remaining (follow-ups, not blockers):** run the workers' own exec/read tools inside the
     sandbox too (currently only *verification* is sandboxed; generation is still local);
     egress hardening (today the container keeps the default bridge network — mount `~/.m2`
     read-only for offline builds instead of opening the web); `sc-web` image for browser
     checks + action-server background-exec; move repos to WSL2 ext4 per §7 Q1 for speed.
- **Intra-session Koog snapshots** (checkpoint/rollback/compress on `AgentSession`) — low
  priority; worker sessions are short. Building blocks named in sc-runtime/NOTES.md.

12. **Guidelines v1 + Console O3 — DONE (2026-07-11).**
   - **Guidelines** (spec §5.3): `GuidelineSync` (sc-knowledge) with markdown files under
     `<repo>/.swarmcoder/guidelines/<scope>/<slug>.md` as the editing surface and the store
     as index. Human edit wins (confidence 1.0, provenance human, never decays); front-matter
     `status:` supports PROPOSED; deleted file RETIRES the entry; ACTIVE guidelines render
     into the shared prefix's `guidelines` slot, ordered by scope specificity then confidence,
     token-capped, deterministic. Reconcile runs at startup and before every prefix assembly
     (wired as the engine's guidelines supplier). Extraction/decay (§12.4) still deferred to
     the Context Ledger. Tested (`GuidelineSyncTest`).
   - **Real tree-sitter `signatures()`** now installed (a stale sc-syntax jar with the empty
     stub had been masking it when a module was built with `-pl` but not `-am`; always run
     `mvn install` or reactor builds after changing sc-syntax). `SignatureExtractionTest`
     guards it.
   - **Console O3**: `InsightsDto` aggregates (runs/candidates/sessions/tokens, survival &
     selection by model family, kill-reason tally — the §8.9 eval dataset visible);
     `settingsYaml()`/`saveSettingsYaml()` edit the same config file the TUI edits (server
     validates it parses as SwarmConfig before writing; restart applies); read-only
     guidelines browser (editing is via the markdown files per §5.3). Three new TeaVM views
     (Insights, Settings, Guidelines) added to the Console; full six-view app re-verified in
     headless Chromium. The Console is now the complete SwarmCoder operator surface.

---

## 7. Author decisions on the open questions (answered 2026-07-09)

Q1. **WSL2 — decided.** Target repos, worktrees, and sandbox volumes move into WSL2 ext4
    before the sandbox milestone. `EnvironmentChecks` (sc-app) now warns at startup when
    running Windows-native or when `repoPath` sits on a Windows filesystem / `/mnt/c`-style
    mount. `LocalProcessExecTarget` keeps Windows-native verification working in the interim.
    **Consequence for the sandbox milestone:** build `sc-sandbox` against Docker-in-WSL2 with
    repos on ext4; do not implement a Windows-native bind-mount path.

Q2. **Demo repository — created at `dev/demo-repo`** (author delegated the choice).
    A minimal Maven project (Calculator + JUnit 5 tests) with a checked-in
    `.swarmcoder/verify.yaml`: acceptance tests live in the protected `swarm.accept` package
    (surefire pattern, skips cleanly while none exist), existing tests are the regression
    stage. It is its own git repo, ignored by the SwarmCoder repo. Validated: the real
    pipeline verifies it end-to-end (`DemoRepoVerificationTest`, opt-in via
    `-Dswarmcoder.demo.repo=<path>`). Point `repoPath` at it for M1 runs — and move it into
    WSL2 ext4 per Q1 when sandbox work starts. Upgrade to a Spring Boot demo (browser block)
    when the served-app browser checks need exercising.

Q3. **Cloud roles: GLM-5.2 + DeepSeek V4 Pro.** Endpoints, API keys, and models are already
    configurable per role via `~/.swarmcoder/config.yaml` and the TUI settings screen; start
    with architect/test-author/librarian on GLM-5.2 and design-reviewer/judge/approver on
    DeepSeek V4 Pro (de-correlation per spec §6.2).

Q4. **Guideline promotion: configurable** — `guidelines.autoPromote` exists in config
    (default false = human review via decision queue/file edit). Guideline extraction (M4)
    must honor it.

Q5. **Budgets: configurable** — `budgets.*` keys in config are the authority; tune before
    the first unattended overnight run.

## 8. Additional bugs found and fixed while building run-state persistence (2026-07-09)

Recorded so nobody reintroduces them:

- **EclipseStore root adoption:** `ArtifactStore` passed a fresh `StoreRoot` to
  `EmbeddedStorage.start(root, dir)` and kept referencing that fresh instance — the persisted
  root was ignored, so every restart silently began from an empty store. Now the store starts
  rootless and adopts the persisted root when one exists.
- **EclipseStore lazy storing:** `storeRoot()` does NOT persist mutations inside
  already-known objects (the root's HashMaps). `ArtifactStore.append` now explicitly
  `storeAll(...)`s the root's containers on every mutation.
- **Unbounded inference client:** `VllmClient` had no connect or response timeout — an
  unreachable or wedged endpoint hung a workflow stage forever (found because something on
  the dev machine listens on port 9). Now: 10s connect timeout, 120s time-to-first-byte
  request timeout.

Run-state persistence itself (build-order item 3) is DONE: `RunPersister` makes every
workflow transition durable in all five workflows, `WorkflowEngine.resumeAll()` (called at
startup) resumes non-terminal runs and leaves APPROVAL runs parked for the TUI queue, and
`WorkflowPersistenceTest` proves a run reaches APPROVAL durably and survives a store reopen.

Found and fixed while making the FakeVllm end-to-end test pass (2026-07-10) — each of these
was invisible until the real stack ran:

- **httpclient5 conflict, two layers deep:** docker-java-transport-httpclient5 pins httpclient5
  5.0.3 AND excludes httpcore5-h2; Koog's ktor-apache5 engine needs httpclient5 ≥5.5 and the
  h2 classes. Fixes: parent `dependencyManagement` pins httpclient5 5.5.2 / httpcore5(+h2)
  5.4.2, and `sc-runtime` declares `httpcore5-h2` directly to defeat the exclusion. Symptom
  if regressed: `NoClassDefFoundError: HostnameVerificationPolicy` or `H2RequestTargetHost`
  at first LLM call.
- **Koog provider routing is identity-based:** `MultiLLMPromptExecutor` looks clients up by
  `LLMProvider` map identity, and the model's provider loses identity somewhere inside Koog.
  `KoogAgentRuntime` registers the client under the provider singleton AND sets the executor
  fallback (resolved eagerly at construction) so every request pins to our client.
- **Custom model ids need explicit capabilities:** Koog's OpenAI client refuses non-OpenAI
  model ids unless the LLModel declares `LLMCapability.Completion` +
  `LLMCapability.OpenAIEndpoint.Completions`.
- **JGit cannot open linked worktrees** (`repository not found` on the gitdir pointer):
  `GitService.commitAll` now shells out to the git CLI, with the commit message passed via
  `-F <file>` because multi-line arguments do not survive Windows process creation.
- **Worker deaths must produce evidence:** `SwarmDispatcher` catches `Throwable` per worker
  and synthesizes a FAILED candidate — an `Error` from the framework stack previously
  vanished inside the executor, silently yielding "0/0 survived".

Found and fixed during the first LIVE run against the Spark (Qwen 3.6 27B, 2026-07-10):

- **vLLM/Qwen chat template rejects native tool-call history** (400 "Can only get item pairs
  from a mapping"): the template iterates tool-call `arguments` as a mapping, OpenAI-spec
  clients send a JSON string. `KoogAgentRuntime` now configures
  `MissingToolsConversionStrategy.All(ToolCallDescriber.JSON)` — tool history goes to the
  model as plain-text JSON; tool *definitions* stay native so tool calling keeps working.
  See sc-runtime/NOTES.md before ever reverting this.
- **Java HttpClient's h2c upgrade confuses uvicorn/vLLM** — hand-rolled `VllmClient` requests
  arrived with an empty body (400 "body Field required"). Client pinned to HTTP/1.1. The
  judge degraded gracefully to its neutral score during the failure, which is exactly the
  designed behavior — but it also means a silently broken judge looks like "all scores 0.5";
  watch for that signature in run reports.
- **Target repos MUST ignore build output** — the first live candidate's diff included
  `target/**` because the demo repo had no `.gitignore` and the worker loop stages with
  `git add -A`. Fixed in the demo repo. A defensive exclusion in the worker's diff capture
  is still worth adding when the repo-map work lands.

- **Local models emit malformed JSON leads** — Qwen 3.6 produced a literal doubled opening
  brace ({{"score": …}) even under `response_format: json_object`. `JudgeClient` parses
  verdicts defensively: balanced string-aware extraction, retried from each successive '{'.
  Neutral-score fallback remains the last resort; a run report full of 0.5 scores is the
  signature of a broken judge, not mediocre candidates.

**M1 ACCEPTANCE MET (2026-07-10):** `LiveM1Test` against the Spark (Qwen 3.6 27B) — typed
Task in, real agent session (Koog over HTTP, tools exec/read/apply_diff/report_done),
verified green diff out on a swarm branch: `multiply` added to the demo Calculator in
8 turns / ~2.6K tokens, compiles, existing tests 3/3 green, candidate SELECTED with a real
judge verdict (score 1.0 and substantive rationale) from the same model.

---

## 9. Conformance audit close-out (2026-07-12)

A full design audit against this document and the spec found the build substantially
conformant; the following non-conformances were found and FIXED the same day:

- **Secret scan wired (spec §18):** `SecretScanner` rebuilt (known token formats + Shannon
  entropy on credential assignments, ADDED diff lines only, redacted findings) and called by
  `FinalIntegrator` on every winning diff before merge — a hit parks the run before APPROVAL.
- **R8:** `scaffold_*.py` moved to `dev/scaffold/` (historical artifacts, never re-run).
- **R7 logging:** sc-app now binds logback (file-only appender →
  `~/.swarmcoder/logs/swarmcoder.log`) — previously the launched app had NO SLF4J provider
  and every log statement was silently dropped. Remaining `System.out`/`printStackTrace`
  in Main/GreenfieldWorkflow/DocsIndex/SyntacticClusterer/DecisionQueueApi replaced with
  SLF4J. The `Main` stdout redirect is narrowed to shielding the TUI from non-SLF4J writers
  (JVM Unsafe warnings) → `~/.swarmcoder/logs/stdio.log`. Exempt: ActionServer (separate
  container process), sc-console-ui (TeaVM: System.out IS the browser console), frozen sc-tui.
- **S1 residue:** `InferenceScheduler`'s hardcoded qwen3-coder-next/qwen35-35b pool ids
  deleted; `DependencyGraph` registers one admission pool per `spark.instances` entry from
  config (maxNumSeqs, kvBytesPerTokenEstimate; ceiling = maxNumSeqs full-context sequences).
- **Q4:** `guidelines.autoPromote` is honored — extraction writes `status: active` when true.
- **§17 dead knobs:** `overnight.enabled` and `telemetry.otlpEndpoint` now produce startup
  warnings stating the features are unimplemented (OTel is superseded in practice by
  TraceHub + Console) instead of being silently ignored.
- **R7 boundaries:** `TreeSitterBoundaryTest` added (org.treesitter confined to sc-syntax),
  completing the Koog/LSP4J/tree-sitter confinement trio.
- **TUI honesty:** the frozen TUI's approval screen no longer renders a fabricated diff; it
  shows run details and points at the integration branch + Console.

**S7. Spec §15 (MCP + ACP server) is DESCOPED — author decision 2026-07-12.** Both of §15's
intended consumers disappeared as the design evolved, and this makes it official:

- *ACP* was to make the TUI a thin client and admit third-party frontends. The 2026-07-10
  amendment made the zeroz4j **Console the complete operator surface** (intake, approvals,
  decisions, settings, guidelines, observability) and froze the TUI — ACP has no remaining
  purpose. Dropped.
- *MCP* was to expose orchestrator tools (`get_task`, `lookup_api`, `search_history`,
  `queue_decision`, …) to agents over a protocol boundary. The Koog worker loop gets its
  tools **in-process** via `WorkerToolbox`, so no internal consumer exists either.
  **Deferred, not dropped**: revisit only when a concrete EXTERNAL consumer appears (e.g.
  driving SwarmCoder from Claude Code, an IDE, or CI). Build it then as a thin MCP-only
  adapter over the existing `ConsoleContext` bridge / `ControlService`+`ObserverService`
  surfaces — do not build it speculatively, and do not build the ACP half at all.

`sc-server` therefore stays a stub (`DecisionQueueApi`) and its emptiness is conformant,
not a gap. Spec §15 and the §16 "the ACP surface is the real interface" line are
superseded by this item and docs/OBSERVABILITY_DESIGN.md.

**S8. Web access for the RESEARCHER role — author decision 2026-07-14 ("security is not a
concern now").** Spec §18's "sandbox egress limited to Spark ports … no live registry
access from sandboxes" and "Librarian fetches docs" line are AMENDED: a new
`ResearcherAgent` (sc-knowledge) may reach the open web (`WebAccess` — DuckDuckGo HTML
search + tag-stripped URL fetch, no API key) and the Context7 MCP docs to gather knowledge,
filing findings as **PROPOSED `KnowledgeDoc`** objects for operator review. Scope of the
grant, precisely:
- The Researcher is the **only web-touching role.** Workers remain fully offline (no-network
  sandbox intact); the chat analyst's `lookup_docs` reaches only the local index → curator
  sources → Context7 (no raw web). This preserves the §18 worker isolation while opening a
  single, operator-triggered, human-reviewed intake path.
- Every web call is best-effort and returns strings the model reacts to, never exceptions;
  the whole mission is CloudGate-budgeted like any other cloud spend.
- The Librarian now also **pre-warms Context7 docs at brief-assembly time** (one background
  fetch per manifest library, once per process) so `lookup_api` is hot before workers run.
- If the security posture tightens again, gate `WebAccess.standard()` behind a config flag
  (e.g. `research.webEnabled`) and swap in a no-op `WebAccess` — the interface is already
  the seam for that.

Known open tails (unchanged): worker exec-in-sandbox, sandbox read-only context mounts,
egress hardening, adaptive N (S3), behavioral probes, intra-session Koog snapshots, JDT LS
live validation.

Known open tails (unchanged): worker exec-in-sandbox, sandbox read-only context mounts,
egress hardening, adaptive N (S3), behavioral probes, intra-session Koog snapshots, JDT LS
live validation.

---

## 10. First real N-worker swarm run + worker reliability fix (2026-07-13)

The swarm config block is now honored end-to-end (`nPerTask`, `splitAcrossFamilies`,
`tempMin/tempMax`, `maxConcurrentTaskGroups`, `dispatch.staggerMs` — all previously parsed
but consumed by nothing; ArchitectClient hardcoded n=1). The first genuine 10-worker run
immediately exposed and fixed the biggest worker-effectiveness bug so far:

- **9/10 workers produced no change.** Qwen emits unified diffs with miscounted `@@` hunk
  headers and drifted context; strict `git apply` rejects them; workers burn turns retrying
  and then the runtime's two-text-turns exit ends the session silently. Fixes: a
  `write_file` tool (the `SamplingConfig` "full-files" output mode, finally implemented,
  same mechanical write-set enforcement), `git apply --recount -C1`, and a prompt rule to
  switch to write_file after a diff rejection. Result: **10/10 workers landed real,
  verified-green changes**; run reached APPROVAL in 677s with a SELECTED candidate
  integrated. The E2E test now REQUIRES a selected non-blank diff (a zero-winner run
  previously "passed" because FinalIntegrator treats no winners as nothing-to-integrate).
- **Concurrency economics measured** (dev/spark_bench-style probe, 2k-token shared prefix,
  256-token completions): 1 request = 2.7s (18.1 tok/s); 10 concurrent = 9.3s wall,
  aggregate 37.7 tok/s. So N=10 costs ~3.5× ONE request's wall time — far from serialized
  (10×) but also not the ideal ~1×; aggregate throughput only 2.1× a single stream. Prime
  suspect: DFlash speculative decoding (`num_speculative_tokens: 12`) was tuned at batch-1
  and eats the batching gain at batch-10 — re-benchmark with spec-decode off/lowered (the
  waived M0 measurement, effectively). Worker turn time at 10-way (~19–28s) is dominated by
  ten CONCURRENT LOCAL `mvn` runs on the workstation, not the Spark — another argument for
  the sandbox pool / WSL2-ext4 tail.
- Per-candidate verification is now parallel (virtual threads; it was sequential, ~1 min of
  dead time at n=10); `BlobStore.storeBlob` made safe for concurrent identical-content
  writers. Known non-fatal degradation: qwen occasionally emits tool calls with missing
  arguments (Koog returns the failure to the model; see sc-runtime/NOTES.md).

## 11. Requirements & backlog: author decisions (2026-07-25)

The BRD shipped 2026-07-24 as a persisted requirement graph with versioning and a live editor,
but nothing consumed it: `DesignDocument.brdRequirementIds` had zero writers, the Architect
invented its own throwaway requirement list from the goal string, and there was no unit of work
between "the BRD" and "a worker-sized task". The following decisions close that gap. The full
design is `docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md`; this section is the normative record of
what was decided and what it overrides.

**The governing principle — overrides any code or doc that implies otherwise:**

> The requirement graph is the **only** source of truth. A story is a *scheduled slice* of it,
> not a second description of it. A story carries no requirement content of its own; if a story
> asserts something the BRD does not, that is a defect — promote it into the BRD or delete it.
> Stories own scheduling and execution state; requirements own meaning.

R1. **Hierarchy is `Requirement -> Story -> Task`.** There is **no `Epic` class**. A coarse
    requirement with `REFINES` children *is* an epic; the backlog panel renders that hierarchy.
    An earlier sketch in the same conversation proposed `Epic -> Story -> Task` and was withdrawn —
    a separate `Epic` class would duplicate the `REFINES` hierarchy and create a second truth to
    hand-maintain.

R2. **A session is not a work item.** A run *executes* a story; a story may have many runs.
    Work-item identity must survive retries and splits, or the requirement-to-commit trace breaks —
    which is the entire point of the feature.

R3. **Acceptance criteria move from `Task` to `BrdRequirement`.** Each criterion binds to a test
    id and records its verification state, last-passing commit and run. A story is defined by
    *selecting criteria*, never by paraphrasing the requirement. This is what makes the BRD
    living documentation (Specification by Example): the spec cannot drift from the system
    because the build fails when it does. Story-local criteria are **forbidden** — they archive
    with the story and leave the requirement unverifiable.

R4. **Functional and non-functional requirements are one type with a `kind` flag.**
    `RequirementKind{FUNCTIONAL, NON_FUNCTIONAL}` plus `NfrCategory`. An NFR's *fitness* criteria
    and a functional requirement's *acceptance* criteria are the same object checked by the same
    harness — the NFR gate is the ordinary mechanism, not a special case. NFRs attach via a new
    `RequirementRelation.GATES` edge and are inherited by every `REFINES`-descendant of the
    gated requirement. A separate NFR register was rejected (breaks traceability); NFRs as
    ordinary stories was rejected (they bloat into unverifiable prose or vanish once "done").

R5. **NFR gate enforcement.** `ACTIVE` NFR with an `ACCEPTED` fitness criterion that fails →
    **blocks final integration**. `ACTIVE` without a criterion → warning + a nag to add one.
    `DRAFT` → advisory only. This gives a deliberate promotion moment for "this is now enforced"
    while stopping half-written NFRs from wedging every run.

R6. **Iterations, not sprints.** An `Iteration` is a named, ordered batch of stories with a
    definition of done. **No dates, no story points, no velocity** — estimation ceremony
    calibrated to human throughput is meaningless when a task completes in minutes, and the
    system already collects real telemetry (tokens, wall-clock, candidate pass rate). Cost is
    measured, never estimated.

R7. **The Architect stops inventing requirements.** `design()` receives the story's BRD
    requirements plus inherited NFR gates; `DesignDocument` holds decisions, contracts and risks
    only, and `brdRequirementIds` is finally written. A missing requirement becomes a `DRAFT`
    triage proposal — silent invention is not permitted.
    **Also fix:** `brdRequirementIds` is omitted from `DesignDocument.equals`/`hashCode`
    (`DesignDocument.java:80`), so two designs differing only in BRD links compare equal — which
    breaks any signal that dedups by `equals()`.

R8. **`plan()` emits `criterionIds` per task**, and `TaskGraphValidator` gains a coverage check:
    every criterion in the story is claimed by at least one task, none from outside the story.
    This is a **violation**, not a warning — an uncovered criterion means the story can never
    legitimately reach `REVIEW`.

R9. **Git linkage is the commit sha; PR is an optional overlay.** `Task.commitSha` +
    `selectedCandidateId` (only the *selected* candidate's commit is meaningful — N workers
    produce N branches), `Story.integrationCommit`/`deliveredCommit`, and `prNumber`/`prUrl`
    only when a remote and `gh` are configured. PR-first was rejected: the swarm never pushes
    today, and offline is the normal operating mode.

R10. **Definition of done is human.** All tasks green + gating NFR criteria passing moves a story
     to `REVIEW`, never straight to `DONE`. The operator accepts; that stamps criteria `PASSING`
     with the commit, and a requirement flips to `IMPLEMENTED` only when **all** its `ACCEPTED`
     criteria pass. It reverts to `ACTIVE` on regression. "Tests pass" and "requirement
     satisfied" are not the same claim, and a BRD that auto-completes starts lying silently.

R11. **Discovery: tasks freely, stories need approval.** The Architect may add tasks inside the
     story it is decomposing. Any new story, requirement or criterion proposed by an agent lands
     as `DRAFT`/`PROPOSED` with `origin = DISCOVERED`, the originating run and a rationale, in a
     **Triage** group — never scheduled until an operator promotes it.

R12. **Freeform chat runs survive but mint an `AD_HOC` story**, so every run the swarm performs
     is visible in the backlog. Backlog-only runs were rejected (every throwaway experiment
     would need ceremony first); untracked freeform runs were rejected (two classes of run, and
     the panel would stop reflecting reality).

R13. **Document intake accepts `.md/.txt/.adoc` (passthrough), `.pdf` (PDFBox), `.docx` (POI) and
     images** via a new `roles.vision` model profile. Upload goes over a plain authenticated
     HTTP endpoint (`POST /api/ingest`), **not** the RMI socket — large blobs would block the
     same connection the live signals use. Every extracted requirement keeps a `SourceRef` back
     to its source document, and vision-extracted text is labelled as such in the UI because it
     is a model's reading, not the document.

R14. **The backlog is reactive, direct-model, and disposed properly.**
     `BacklogSignals.CURRENT` is a `Signals.shared` value signal carrying domain objects with
     **no DTOs** (the direct-model pattern of 2026-07-24). The server must publish a fresh
     `copyOf` on every mutation — `set()` dedups by `equals()`, so republishing the mutated
     canonical instance is a silent no-op. Views bound to it **must** implement `Disposable` and
     dispose their effects; the signal is long-lived and leaked effects accumulate.

**Bug found while specifying this — fix it in increment 2:** `BrdAuthoring` calls the 1-arg
`saveBrd(brd)` at `BrdAuthoring.java:87,115,144`, so **agent-authored BRD edits bump the revision
but write no `BrdRevision` snapshot**. Agent changes are invisible in the history/restore UI, and
the `author = "agent"` value documented at `ArtifactStore.java:240` is never actually produced.
All agent mutations must use the 3-arg `saveBrd(brd, "agent", summary)`.

**Store schema:** `StoreRoot` goes to **v7** — `stories`, `iterations`, `sourceDocuments`, and
`tasks` as a **by-reference index** of the same `Task` instances already reachable through
`TaskGraph.tasks`. EclipseStore persists an object graph by reference, so the index costs one
copy, not two, and existing stores open with no migration.

## 12. Requirements & backlog: implementation close-out (2026-07-25)

The §11 decisions are implemented across five increments (`cd644a5`, `8c74d0f`, `f7956b8`,
`80f884b`, `50f98b7`). Full design and the B-1…B-60 requirement list:
`docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md`.

**What is now true of the code:**

- The BRD is the only requirement source. `ArchitectClient.design(goal, scope)` receives the story's
  BRD requirements *with their own ids* and has no schema field in which to write a requirement;
  what it thinks is missing becomes a DRAFT triage proposal. `DesignDocument.brdRequirementIds` is
  finally written.
- `StoryScope` resolves what a run may build — delivered requirements, the exact criteria, and NFR
  gates inherited by walking `GATES` edges up the `REFINES` hierarchy. It is resolved, never stored,
  so a gate added today applies to the next run of an old story.
- Tasks claim criteria (`R7:C1`); `TaskGraphValidator` enforces coverage as a **violation**.
- `CriterionEvidence` turns a verification report into per-criterion outcomes and gate verdicts. A
  criterion whose test did not run is `UNKNOWN`, never `PASSED`. **This claim was untrue when
  written and was made true on 2026-08-27 — see §15.1.** As shipped in July, a criterion naming a
  test nobody had written came out `PASSED` as soon as any other acceptance test ran green.
- Delivery moves a story to `REVIEW`, never `DONE`. `BacklogService.acceptStory` is the human gate;
  it stamps criteria with the commit **and** the requirement's `contentRevision`, so a later reword
  renders the evidence `STALE`. A requirement flips to `IMPLEMENTED` only once every ACCEPTED
  criterion passes.
- Nothing is deleted. Stories are `CANCELLED`, criteria `RETIRED`, requirements `DEPRECATED`; the
  `ChangeEvent` journal and `CriterionVerification` history are append-only.

**Two deviations from the §11 design, both deliberate — and both CLOSED on 2026-08-27 by the move
to ZeroZ Stack 0.7.0:**

D1. ~~**Upload is a raw-body `POST /api/ingest`, not multipart.**~~ **Closed.** The endpoint took the
    file bytes as the request body with the name in a query parameter, and it was
    **unauthenticated** — anyone who could reach the port could persist bytes into the project.
    0.7.0 ships file upload as a framework feature, so `IngestResource` and the browser-side
    `Uploader` are both deleted. Files now go to the framework's own address, which will not accept
    one without a single-use pass the browser must first obtain over its live, authenticated
    connection. The console gained a progress bar and a cancel button per file at the same time.
    Server side: `DocumentUploadHandler`. Client side: `DocumentUpload` and the `FileUpload`
    component. `DocumentUploadTest` proves the old address no longer answers.

    **One capability was lost.** The whole Requirements canvas and the chat composer used to be drop
    targets for a document. The framework's upload component owns its own drop area and cannot be
    attached to an arbitrary element, so the canvas-wide drop is gone (the wizard one click away now
    has a real drop box) and the chat composer has an "add a document" button opening the same box
    in a window.

D2. ~~**`BacklogTask` is a projection, not the domain `Task`.**~~ **Closed.** The serializer had no
    `Set` tag and `Task` holds three `Set` fields. 0.4.0 added `TAG_SET` — along with `UUID`,
    `Instant` and enum tags — so the projection is deleted and the backlog carries the domain
    `Task`. `Task`, `TokenBudget` and `SwarmPolicy` are now `@DataModel`, and
    `ArtifactStore.copyOf(Task)` supplies the fresh instance the shared signal needs.

**Bugs found and fixed while building this** (all pre-dating it):

- Every workflow state transition rebuilt the `Run` through the 8-arg constructor, which carries
  neither `projectId` nor `storyId`, and every transition is persisted — so a run silently lost its
  project on the first DESIGN transition and vanished from the Console's project-scoped views.
- `equals`/`hashCode` omitted `brdRequirementIds` on `DesignDocument` and `projectId` on `Run`, so
  objects differing only in those compared equal — silently breaking anything that dedups by
  `equals()`, including the reactive signals.
- Agent BRD edits called the one-arg `saveBrd`, bumping the revision but writing no snapshot: agent
  changes were invisible in the history/restore UI.
- `store(x)` stops at already-known referenced instances, which was survivable while a `Brd` was
  only edited by replacing its lists — but a requirement now owns its criteria, so editing one in
  place is ordinary. Aggregates are stored eagerly (`ArtifactStore.storeDeep`).
- Three browser tests each booted an embedded Console in one JVM, but zeroz4j's `Signals.shared`
  binds to the FIRST server engine: the 2nd and 3rd silently tested nothing. Split one per class
  with surefire `reuseForks=false`. (0.7.0 makes the engine's session set static, so a second
  engine's clients do receive broadcasts — but the object mapper is still the first engine's, so
  the fork-per-class stays. See the note in `sc-console/pom.xml`.)
- `ConsoleServerSpikeTest` hardcoded port 8080, so the build failed whenever anything else on the
  machine was listening there.
- The shipped UI had widespread mojibake (UTF-8 read as cp1252) in em-dashes, `·`, `⌘` and `×`.

**NOT YET VERIFIED AGAINST A LIVE MODEL OR A REAL REPOSITORY** — true when written, and no longer
true: see §18, where the whole journey runs against the box's real model and a real repository,
thirteen times. Every increment is covered by unit,
service, browser and scripted-LLM tests; the whole chain from document to `IMPLEMENTED` is pinned by
`RequirementToCommitTraceTest`, but with the model's output written directly rather than generated.
The least-proven piece is `CriterionEvidence`'s matching of a criterion's test reference against the
ids the JUnit runner actually emits — it is heuristic and has only been tested against ids we wrote
ourselves. A live run is the remaining proof.

## 13. Containment: what stops a worker writing where it should not (2026-07-25)

An audit of the isolation model found the enforcement much narrower than it looked. This section is
the normative record of what is now enforced, what is not, and the principle the fixes share.

### 13.1 The principle

> **Policy must be resolved from a tree the restrained party cannot write.**

Three separately exploitable bugs turned out to be the same mistake:

- `verify.yaml` — the commands that decide whether a candidate passed — was read from the workspace
  under test, and the orchestrator runs them ON THE HOST, unsandboxed, at integration. A worker able
  to edit it could certify itself green and execute arbitrary commands on the operator's machine.
  Now loaded from the operator's tree; only the code it runs against is the candidate's.
- In-source lock markers are collected with `git grep` **at HEAD**, not from the working tree. A lock
  a worker can delete in the same commit that violates it is a comment, not a lock.
- Project `protectedPaths` are **additive** to the global ones, never replacing them, because
  `project.yaml` lives inside the repo the swarm writes to — replacement semantics would make
  deleting a line from a tracked file a way to unlock a globally locked module.

Anything added later that reads policy from the candidate's tree reintroduces this class of bug.

### 13.2 What is enforced mechanically

`PathPolicy` (sc-runtime) is the single decision point. It **canonicalises before matching**, which
the previous implementation did not: the write-set check compared the raw string while the escape
check compared the normalised path, so with a write set of `["src"]` the path `src/../pom.xml`
satisfied both and was written outside the write set with the violation counter untouched. The same
traversal reached the protected acceptance-test directory. `apply_diff` had no escape check at all,
and nothing anywhere resolved symlinks — `normalize()` is lexical and follows a link straight out of
the worktree.

Locked paths, in increasing generality:

| Layer | Source | Notes |
|---|---|---|
| `ALWAYS_PROTECTED` | `.swarmcoder/`, `.git/` | unconditional; `verify.yaml` and git hooks are both executable inputs to the host |
| `TRUST_KERNEL` | `PathPolicy`, `VerifySpecLoader`, `SecretScanner`, `FinalIntegrator`, `CriterionEvidence`, `WorkerToolbox` | locked by DEFAULT. A worker modifying these disables the checks that would catch the modification — a silent, self-concealing failure. Released only with `-Dswarmcoder.unlockTrustKernel`, logged loudly. Applied unconditionally: for other projects these paths match nothing. |
| in-source markers | `swarmcoder:locked` in a file, read at HEAD | lock lives next to the code and survives moves |
| `protectedPaths` | `config.yaml` + `project.yaml` (union) | operator-declared modules |
| `acceptanceTestDir` | per task | pre-existing |
| write set | per task | empty means unrestricted, as before |

Enforced at **two** points, and both are needed: the worker's tools, and an audit of the winning
diff's touched paths at FINAL_INTEGRATION. The second exists because tool checks only see what the
TOOLS did — a worker's shell can write anything in the worktree and `git add -A` sweeps it into the
candidate diff. That was the laundering path; `touchedPaths()` already existed and nothing called it.
An out-of-bounds diff now parks the run.

### 13.3 What is NOT enforced

**`exec` has no policy layer.** It is an unfiltered shell: no allowlist, no path interception, no
audit. With the sandbox off — the default — it runs on the workstation with the operator's
privileges. Everything in §13.2 bounds what can REACH the integration branch; none of it bounds what
a shell command can do while the run is in progress: reading credentials, network egress, touching
other repositories, pushing to remotes.

The worker prompt names locked paths. That is a courtesy so the model does not waste turns on
refusals — **it is not enforcement and must never become the only place a path is protected.**

`readSet` remains parsed, copied and consulted by nothing.

---

## 14. Per-model settings, and what has to be measured on the new box (2026-08-27)

Written for whoever runs the first session against the replacement model. §S1 says what the model
is and why each setting exists; this section says how to set one up and which numbers are still
missing.

### 14.1 Where each model-specific behaviour now lives

| Behaviour | Was | Is |
|---|---|---|
| Longest answer per call | `-Dswarmcoder.llm.maxTokens`, JVM-wide, 32768 | `ModelQuirks.maxOutputTokens`, per profile — and now applied to WORKERS too, which previously sent no cap at all |
| Reasoning on/off | `-Dswarmcoder.llm.thinking`, JVM-wide, plus a per-role flag | `ModelQuirks.thinking`, per profile and per role |
| How reasoning is switched off, request side | literal `enable_thinking` in `VllmClient` | `ModelQuirks.thinkingKwarg`; null/"none" sends nothing |
| How reasoning is switched off, worker side | literal `"/no_think"` appended in `KoogAgentRuntime` | `ModelQuirks.noThinkDirective`; null/"none" appends nothing |
| Tool-call history format | `MissingToolsConversionStrategy.All(JSON)` forced for everyone | `ModelQuirks.textualToolHistory` |
| JSON response format | assumed honoured (`useResponseFormat` always true) | `ModelQuirks.jsonResponseFormat`; false sends `guided_json` |
| HTTP version | pinned HTTP/1.1 in `VllmClient` | `ModelQuirks.http2` |
| Served context ceiling | literal `65536` in three places | `ModelQuirks.servedContextTokens` |
| Working context budget | 32k by convention | `ModelQuirks.workingContextTokens` |
| Memory per token | `spark.instances[].kvBytesPerTokenEstimate`, keyed by instance id | `ModelQuirks.kvBytesPerToken`, keyed by PROFILE id — which is what leases are taken against |
| Requests served at once | `spark.instances[].maxNumSeqs`, same key mismatch | `ModelQuirks.maxConcurrentSequences` |
| Vision has no reasoning switch | an `if` and a comment inside the shared client | the `vision` shape names no argument |

`ModelShapes` ships the named starting profiles: `qwen36-27b` (the measured 2026-07 roster, the
only one marked measured), `qwen38-flash-next-125b`, `vision`, and `generic-openai` (sends no
reasoning switch at all, because an unknown chat template may reject an argument it does not know).

**The id `qwen38-flash-next-125b` is a misnomer and is kept only so saved configurations keep
resolving.** It was named when the replacement model was believed to be a 125B mixture-of-experts;
the box actually serves a 27B model called `qwen3.8-27b` (see S1). The shape's own label says so.
Renaming the id is a config migration and has deliberately not been done here.

### 14.2 Adding a model, start to finish

1. Console → project menu → **Settings → Roles**.
2. Pick the role, or **add worker family** for a worker model. Fill in base URL, model name, key.
3. Click **model settings** on that row. Choose the closest **shape** — for the box's model,
   `qwen38-flash-next-125b` (whose id is a misnomer; see the note above).
4. Change only what you actually know differs. Everything left blank falls back to the shape.
5. Leave **"Have these been measured?"** on *no* until §14.3 has been done. The startup log then
   warns once per model per start, which is the point.
6. Save. Settings apply on restart, like every other config change.
7. Read the startup log: it prints one line per model naming every resolved setting, and a
   diversity line saying how much the swarm's worker group will actually differ.

### 14.3 What has to be measured the first time the new box runs

**Do not guess any of these. Every default carried into the new profile is the dense 27B's value
or a conservative constant, and is marked unverified for exactly that reason.**

1. **Requests served at once.** Raise the worker count and watch where added workers stop buying
   throughput and start queueing. This is the ceiling `maxConcurrentSequences` should hold, and it
   is what decides how many workers can attack one task.
2. **Memory per token.** The key/value cache cost of one token, for `kvBytesPerToken`. The 27B's
   1024 bytes is a placeholder carried over from a different model on different serving software,
   and nothing more.
3. **The concurrency curve, from scratch.** §10 records ten concurrent requests costing about 3.5x
   one request's wall time on the 2026-07 model under **vLLM**. The box now runs **sglang**, whose
   batching and scheduling are its own, so that figure is a baseline and not a prediction. Re-run
   `dev/spark_bench.py` against the new endpoint before choosing `swarm.nPerTask`.
4. **How much memory the model actually leaves free.** This is what decides whether a SECOND model
   fits alongside it, which is the open owner decision in S1 and the strongest remaining lever on
   worker diversity.
5. **Whether speculative decoding is on, and whether it helps at batch 10.** It is a serving-side
   setting on the box, not a SwarmCoder one. On the 27B it was tuned at batch 1 and was the prime
   suspect for eating the batching gain at batch 10. Check both states before concluding anything
   about point 3.
6. **That `/no_think` still suppresses reasoning on the worker path**, and whether native
   tool-call history now succeeds (if it does, `textualToolHistory` can be turned off for this
   model — measure the prefix-cache effect before doing it).

**Already measured, 2026-08-28 (see S1):** served context is 262144; single-stream throughput is
about **29 tokens/second**; `enable_thinking: false` is accepted by this server and is needed,
because reasoning is ON by default here and left on it spends the whole output budget before
answering; native tool calling works with correct JSON arguments. What is still unknown is how
throughput behaves under ten requests at once, on sglang rather than vLLM.

### 14.4 Known gaps left open deliberately

- **The working context budget is configurable but still not ENFORCED.** S2 says 32k working
  context is "enforced by `TokenBudget`"; it is not. `TokenBudget.maxPromptTokens` is written and
  read by nothing, and was already inert before this change — making it per-model did not give it
  teeth. What startup DOES now check is the relationship between the three sizes, which is the
  part that actually stalls a stage: it warns when the working budget exceeds the context the
  server was started with, and when one answer is allowed to be larger than the whole working
  budget. Real enforcement needs a token count on the assembled prompt and is a separate job.
- **The per-worker seed is recorded and never sent.** `SamplingConfig` carries a distinct seed per
  worker; neither request path has a seed parameter (the agent framework's `LLMParams` has no such
  field), so it cannot reach the model. It is kept for a complete candidate record and is named as
  not-sent in every place the diversity is described, so nobody counts it as a lever.
- **Koog 1.0's `LLMParams` gained `additionalProperties`, and the OpenAI request model carries it
  through to a field of that name.** Whether it is FLATTENED into the request body — which is what
  would let the worker path send `chat_template_kwargs` and drop the in-prompt directive — is
  unverified, and cannot be verified without a server. Measure it before switching; the note in
  `sc-runtime/NOTES.md` has been updated to say so.

---

## 15. The full-product dress rehearsal, and what it found (2026-08-27)

§12 closed with "NOT YET VERIFIED AGAINST A LIVE MODEL OR A REAL REPOSITORY" and named the weakest
link: `CriterionEvidence`'s matching of a criterion's test reference against the ids the JUnit runner
actually emits. That proof now exists, and it needs no model server.

**`FullProductDressRehearsalTest` (sc-app) walks the entire product**: document intake → the
requirements wizard → the operator's promotion → the planning wizard → a run through every workflow
stage → two workers in real worktrees → verification by a REAL Maven build producing REAL surefire
XML → integration → delivery → the operator's acceptance → the requirement marked IMPLEMENTED with
the commit and the tests attached. The only thing simulated is what the models say (`ScriptedModel`
answering a `FakeVllm` endpoint over real HTTP); every client, parser and decision in between is the
product's own. It runs offline in about 30 seconds, and against a live model on one flag
(`-Dswarmcoder.live.baseUrl`), sharing the repository, the journey and the assertions — so when the
box returns, the model is the only new variable. A third case (`SWARMCODER_SANDBOX_TESTS=true`)
verifies each candidate inside Docker; that path has been run and is green.

It found four defects. Every one of them was invisible to every test that existed.

### 15.1 A test that never ran was recorded as PASSED — FIXED

**§12's claim that "a criterion whose test did not run is UNKNOWN, never PASSED" was false.** The
rule as implemented was "the acceptance suite ran, and nothing among the FAILURES looks like this
criterion's test" — so a criterion naming a test that had never been written came out PASSED the
moment any other acceptance test ran green, and the requirement reached IMPLEMENTED on the strength
of somebody else's test.

The cause was structural: `TestResults` carried counts and failures only, so nothing downstream
could establish whether a particular test had run at all. It now records the ids of tests that
passed and of tests the runner skipped (capped at `MAX_IDS`, with truncation recorded rather than
silent), and `CriterionEvidence` requires a positive match against an id reported as passing before
it may say PASSED. It fails closed in every direction: skipped is not passed, a report from before
ids existed is UNKNOWN, and a truncated id list is UNKNOWN.

Matching is now on whole segments rather than substrings. The old rule asked whether either string
contained the other, so a criterion naming `multiply` was answered by a failure in `multiplyByZero`.

**What a real runner actually emits** — established by running one, not assumed (Maven 3.9.9,
surefire 3.2.5, JUnit 5.10.2). The output is checked in at `sc-workflow/src/test/resources/real-junit/`
together with the source that produced it:

| spelling | reported as | the criterion resolves |
|---|---|---|
| plain method | `pkg.Class#method` | by bare method, `Class#method`, FQCN, or source path |
| nested class | `pkg.Outer$Inner#method` | `Outer$Inner#m`, `Outer.Inner#m`, `Outer#m`, `Inner#m` |
| parameterised | `pkg.Class#method(int)[2]`, once per invocation | `Class#method`; one bad invocation FAILS the criterion |
| ERROR (threw) | an `error` element, not a `failure` | FAILED — the thing it checks did not work |
| `@DisplayName` | the METHOD name; the display name never appears | naming the method works; naming the display name is UNKNOWN |
| `@Disabled` | a `skipped` element, no failure | **UNKNOWN, never PASSED** |
| never written | nothing at all | **UNKNOWN, never PASSED** |

`RealJUnitCriterionMatchingTest` pins every row. An opt-in case (`SWARMCODER_REAL_JUNIT=true`)
re-runs Maven and fails if the toolchain starts spelling ids differently — the drift that would
otherwise break the matcher silently.

### 15.2 The Maven acceptance stage selected no tests — FIXED

Every `verify.yaml` in the project used `-Dtest=swarm.accept.*`. Surefire wants a path pattern, not
a package pattern: **that selector matches nothing.** The acceptance stage had therefore always run
zero tests, always reported no failures, and consequently always "survived" — while leaving every
criterion UNKNOWN. Corrected to `-Dtest=swarm/accept/**` in the checked-in demo-repo template, in
`LiveEndToEndTest` and in `ConfigDrivenE2ETest`. The Gradle form in the spec
(`--tests 'swarm.accept.*'`) is dotted and is correct as it stands.

**`dev/demo-repo/.swarmcoder/verify.yaml` still carries the broken pattern** and is not fixed here:
that repository is its own git repository, gitignored by this one, so it lies outside this
checkout's history. It needs the same one-line change by hand.

The per-candidate verification log now prints the PASSED count as well as failures and errors. An
acceptance stage that selected nothing reports no failures and reads exactly like one that ran and
was green; that is how this went unnoticed for so long.

### 15.3 In a story-scoped run the test author never ran — FIXED

A scoped task references its criteria by id and owns no copies of them — deliberately, so that
requirement wording is not duplicated into an object that is archived with the run. But
`TestAuthorClient` read only `task.criteria()`, so its "no criteria, nothing to write" guard fired
on **every scoped task**. The test author never ran in the one mode the product is built around: no
acceptance test was ever written, the acceptance stage ran nothing, every criterion was UNKNOWN, and
no requirement could reach IMPLEMENTED through a real run. The workflow now resolves the criteria
from the story's scope and hands them to the author; the criteria stay owned by the requirement.

### 15.4 A run started from the backlog lost its story — FIXED

`BacklogServiceImpl.startSession` asked the Console to start the run and only then set the
`storyId`. But the engine begins advancing the run on its own thread the instant it is handed over,
and `transition()` rebuilds the `Run` from its own copy at every state change — so the binding was a
race the caller lost, and its store write was overwritten even when it won. An unbound run invents
its own requirements instead of using the BRD's, plans without criterion refs, never authors tests
for them, and finishes with `run.storyId() == null`, which makes `recordDelivery` report success
without moving the story to REVIEW or stamping a single criterion.

`ConsoleContext.startRun(goal, kind, storyId)` — backed by a `StoryRunStarter` that sc-app wires to
`DependencyGraph.startRun` — attaches the story **before the engine ever sees the run**. The old
two-step path stays as a fallback for a ConsoleContext built without the starter, and now says so
loudly when it cannot bind, instead of doing nothing.

### 15.5 Open — these need an author decision, not a guess

> **Both were decided and built on 2026-08-28 — see §17.1 and §17.2.** Left here as written, because
> the reasoning behind each recommendation is what the decision was made on.

**A candidate whose acceptance stage ran ZERO tests still counts as SURVIVED.** `Verdicts.survived`
is "compiles ∧ no acceptance failures ∧ no existing-test failures", and no tests means no failures.
That was the documented M1 allowance (§5) for repositories with no acceptance suite yet, and it is
what let 15.2 hide: two candidates passed verification without a single acceptance test running.
With criteria in the picture the evidence layer now catches it — every criterion comes out UNKNOWN
and the story is BLOCKED rather than delivered — so nothing ships wrongly. But the swarm still
spends a full judge-and-select round choosing between candidates about which verification learned
nothing at all.

*Recommendation:* when a task claims criteria, an acceptance stage that executed zero tests should
be a verification FAILURE, not a pass; where a task claims none, keep today's allowance. Not changed
here, because it alters what "survived" means for the whole selection pipeline.

**A criterion's test reference is typed by a person and checked against nothing.** The requirements
wizard writes every criterion with `testClassOrFile = null` (`RequirementsIntake` passes null at
both call sites), and nothing writes it back from what the test author actually produced. The one
string on which the entire requirement-to-commit trace depends is hand-entered in the Requirements
editor. Since 15.1 a typo in it yields UNKNOWN rather than a silent pass — but only at the end of a
full run.

*Recommendation,* in order of value: have the intake wizard propose a test reference alongside each
criterion it proposes; and have TEST_AUTHORING report which test it wrote for which criterion, so a
criterion pointing at a test nobody wrote is caught at authoring time rather than at delivery.

---

## 16. The console serves one operator at one window — author decision 2026-08-27

Asked directly, in response to three loose ends left by the zerozstack 0.7.0 upgrade. The answer
is **one window, one person**, and it settles all three. They are recorded as decided, not as
open, so nobody reopens them as defects:

- **Switching project moves every open browser.** "Which project is current" is a single field on
  the server, so a second window follows the first. Correct under this decision. Fixing it would
  mean the server resolving the current project from the asking connection — a change to what the
  orchestrator is, not to how a signal is declared. Not wanted.
- **The chat reply is broadcast to every browser**, up to twelve pushes a second, each carrying the
  whole reply so far. With one window there is one recipient and no waste. 0.6.0's per-browser
  delivery is the fix if this ever changes; it means moving chat off the raw push channel onto the
  framework's event topics, on both sides of the wire.
- **No signal is scoped to one browser, and none should be.** All seven describe one orchestrator
  process — one current project, one requirement graph, one backlog, one analysis running. Those
  cannot differ between two windows onto the same process, and the reason is written on each class.
  The intake and planning wizards look per-browser but are server-side jobs that continue when the
  window closes; a second window seeing them is the point.

**What this decision does NOT license.** It is a statement about how many people watch, not about
security. The upload endpoint was closed because anything reaching the port could write files into
the project, and that stays closed. If the console ever leaves the workstation, all three items
above reopen together with authentication, and §S8's "security is not a concern now" reopens with
them.

---

## 17. The two items §15.5 left open, and a third of the same kind (2026-08-28)

§15.5 recorded two things the dress rehearsal deliberately did not change, because both alter
behaviour the whole pipeline depends on. Both are now decided by the author and built. Auditing for
the same defect class turned up a third instance, which is fixed here too.

### 17.1 An empty check is not a pass — DECIDED AND BUILT

**Author decision: when a task claims requirement-checks and the acceptance stage executed zero
tests, that is a verification FAILURE, not a pass.** Where a task claims none, today's M1 allowance
(§5) stands exactly as it was.

"No tests ran" produced "no failures" produced SURVIVED. That is what let §15.2 hide for weeks: the
acceptance stage had been selecting nothing since the day it was written, always reported clean, and
every candidate sailed through it. Nothing was wrongly *delivered* after §15.1 — the criteria come
out UNKNOWN and the story is BLOCKED — but the swarm still spent a full judge-and-select round on
candidates verification had learned nothing about, and the gate that should have screamed stayed
silent.

**Counts cannot answer this; the stage's own account can.** `TestResults` now records what the stage
DID, beside what it counted:

| outcome | means | may it fail a candidate? |
|---|---|---|
| `SKIPPED` | an earlier stage failed and short-circuited it | no — the earlier stage already lost |
| `NOT_CONFIGURED` | the contract declares no commands for this stage | **yes**, when checks are claimed |
| `EXECUTED` | the commands ran and the result was read — **counts are the truth, zero included** | **yes**, when the count is zero and checks are claimed |
| `INCONCLUSIVE` | the commands ran and nothing readable came back | no |

An absent outcome — every report already in the store — reads as `INCONCLUSIVE`. That is the whole
of the "distinguish it from could-not-tell" requirement: no persisted report, and no unreadable one,
can be mistaken for one that positively established that zero tests ran. A truncated id list cannot
either, because the rule reads counts and never the id list.

**Commands succeeded, report directories empty** is classed as `EXECUTED` with a count of zero, not
as inconclusive. The directories are cleared immediately before the stage and a JUnit runner writes
a report for everything it runs, so an empty directory after a clean exit is the runner saying it
selected nothing. It is also, exactly, the §15.2 shape.

**The failure is legible.** `Verdicts.assess(report, claimedChecks)` takes one readable line per
check the task answers for — its ref, its wording and the test it names — and the reason names every
one of them. `ArtifactStore.describeClaimedChecks(task)` builds those lines; a scoped task owns no
criteria, so the wording is resolved from the requirements document, and an id that resolves to
nothing is listed as the id rather than dropped. `Verdicts.survived(report)` still exists and is
unchanged in meaning: it is `assess` with no claimed checks.

Applied in two places:

- **Per candidate** (`SwarmEngineImpl.verifyOne`). The reason is appended to the report's log tail —
  what the Gallery renders when somebody asks why a candidate died — and the candidate carries the
  new `KillReason.NO_ACCEPTANCE_EVIDENCE`, which exists so the kill is not silent about being a
  statement about the *verification setup* rather than about the code.
- **At FINAL_INTEGRATION** (`FinalIntegrator.verifyIntegration`) — **yes, the same reasoning
  applies, and more strongly.** That method already receives the task, and the report it produces is
  the one `recordDelivery` reads when it stamps each criterion. An integration whose acceptance
  stage executed no test would otherwise merge, deliver, and leave every criterion UNKNOWN at the
  very end of the run. Parking there costs one merge; discovering it at delivery costs the run.

### 17.2 The test a check names: proposed by the wizard, checked when it is written — DECIDED AND BUILT

**Author decision: both halves.**

Every requirement-check carries a reference to the test that proves it, and that reference is the
address the entire requirement-to-commit trace is looked up by. The requirements wizard wrote
`testClassOrFile = null` at both call sites, nothing ever wrote it back, and so the one string the
whole trace hangs on was typed by a person into the Requirements editor and validated against
nothing.

**(a) The wizard proposes a reference alongside each check it proposes.** Acceptance tests always
live in the package `swarm.accept`, so the name is derivable by convention:
`swarm.accept.<Area>Test#<methodName>`. The wizard is asked for it, and it follows the conventions
everything else it proposes follows — nothing reaches the requirements document until the operator
presses Apply, and the proposed name is rendered as its own `Test:` line inside the proposal block
the operator reviews. A name the operator cannot see in the diff is a name they never agreed to, and
this one decides what proves the requirement.

Criteria arriving as bare strings (the shape before this) still parse, so an older prompt or a model
that ignores the new shape still produces a usable requirement.

**Proposed and typed must not look alike.** `AcceptanceCriterion.testRefOrigin` records which:
`PROPOSED` for anything an agent wrote (the intake wizard and the BRD-author tools are the only
paths), `OPERATOR` for the editor. An absent origin reads as `OPERATOR` — everything written before
this was typed by a person, and crediting the wizard with it would tell the operator to re-check
work they had already done. The editor puts a warning under any reference still marked as a
proposal. Changing the reference makes it the operator's; saving the row after editing only the
wording or the agreement does not, or the warning would erase itself.

**(b) TEST_AUTHORING reports which test it wrote for which check.** The author is now told that a
criterion's `(test: ...)` is an address and not a hint — same package, same class, same method — and
is asked to report the mapping. **The report is a report, never evidence.** What a model says it
wrote is another model-written string; what it wrote is on disk. `AuthoredTestAudit` reads the test
ids out of the source that was actually written and matches each check's reference against them with
`CriterionEvidence.namesOneOf` — the *same* matcher the evidence layer uses against a real runner,
because a criterion that matched at authoring time and not at verification time would be worse than
no check at all. The author's own claims are logged beside the facts, and a claim naming a test that
is not in the files is logged as such.

**On a mismatch the run parks. The reference is NOT written back.** Writing it back is the tempting
option and it is the wrong one: it would make the requirement point at a test chosen by the thing
being checked. Any test at all would then "prove" the criterion, and the mismatch — real information
about a disagreement between what was agreed and what was built — would disappear along with the
error. That is strictly worse than the hand-typed string it replaces, because nothing could ever
detect it again. Warning and continuing was the other option, and it is worse than parking: the run
then costs a full swarm, judge and integration cycle and ends in exactly the UNKNOWN §15.5
describes. TEST_AUTHORING is before any worker starts, so parking there costs nothing, and the run
is resumable the moment a person decides which side was wrong.

**It only accuses on positive evidence.** A finding needs test ids actually read out of authored
source. A file that cannot be read, or that yields no ids (a language the reader does not parse),
produces a note and no finding — "we could not tell" must never become "this is wrong", which is
§15.1's rule in the other direction. Id extraction is deliberately generous: each test method is
attributed both to the type declared nearest above it and to the file's first type. Over-generating
can only make the audit accuse less, and this decides whether to stop a run.

A check naming **no** test at all is also a finding, for the same reason and at the same moment:
nothing can ever prove it, and a run built on it can only end BLOCKED.

### 17.3 The third instance: the red-check waved runs through after measuring nothing — FIXED

The audit asked for a further gate that reports clean because it measured nothing, and found one
still live. `RedChecker` reports honestly that no test reports were produced; `GreenfieldWorkflow`
threw that answer away. Its rule was "block only when tests actually ran and passed", so "the stage
ran and executed nothing" fell into the same branch as "there is no acceptance suite yet" and the
run dispatched a whole swarm with no signal — every candidate then dying at verification for exactly
that reason, N workers later.

With the stage outcome recorded the two cases separate cleanly. When the run answers for checks, a
red-check whose acceptance stage RAN and executed nothing parks the run and names the checks nothing
was proved about. Where no acceptance suite exists, the allowance stands.

**Other gates examined and left alone**, so nobody re-opens them as findings:

- **Lint** is recorded and advisory by explicit decision (§5), not a gate that failed to measure.
- **The LSP pre-compile pass** is advisory by design and says so; the real compile is the authority.
- **An ACTIVE NFR with no measurable fitness criterion warns rather than blocks** — deliberate, and
  documented in REQUIREMENTS_AND_BACKLOG_DESIGN §2.6: it would otherwise block for ever.
- **Browser checks** produce one `PageCheck` per configured page on every path, infrastructure
  failures included, so an empty result means the operator configured no pages.

### 17.4 Abandoned worker worktrees are swept at startup

63 worker worktrees from July sessions were still in `~/.swarmcoder/wt` (about 5.7 MB). Runs clean
up after themselves; a run that is *killed* cannot, and nothing had ever swept up after one.

`WorktreeSweeper` asks git rather than recognising shapes: the `.git` pointer file each linked
worktree carries, and `git worktree list --porcelain` answered by the repository that owns it.
Removal goes through `git worktree remove` **without** `--force`, so git itself refuses a checkout
with work in it, and a `git status --porcelain` check before that reports such a worktree and leaves
it alone. A folder that merely looks like a worktree is never touched.

The one case where the folder is deleted directly is a worktree whose repository is gone — git will
not open it, no repository can be asked to remove it, and any commits it held died with the object
database. 44 of the 64 directories now present are that case: worktrees of temporary test
repositories that were deleted at the end of the test. The other 20 belong to a real repository and
are clean. **Not one of them holds uncommitted work.**

Bounded three ways: nothing modified within six hours, at most 200 removals per start, and a
live-or-resumable check `DependencyGraph` supplies from the run graph — `integration-<runId>` by
directory name, `swarm/<taskId>/<n>` by the branch git reports. Anything it cannot identify is kept:
"I do not know whose this is" is not a reason to delete it. It runs on a daemon thread, because a
first sweep over a backlog is a hundred or so git processes and housekeeping is not something to
wait for, and it says what it removed.

### 17.5 Also corrected here

A parked run used to announce **every** reason as a failed red-check: the preamble was hardcoded
into `GreenfieldWorkflow.queueDecision`, so a failed integration and a failed quality gate both
blamed the red-check — the one thing they were not. The brief is now the caller's, in full.

### 17.6 What was run

`EmptyCheckIsNotAPassTest` (new, sc-verify, 10 cases — every way the old rule was wrong and every
way the new one must not become wrong); `AuthoredTestAuditTest` (new, sc-workflow, 7 cases against
real authored source); `WorktreeSweeperTest` (new, sc-git, 8 cases against real repositories and
real linked worktrees); two new cases in `GuidedFlowIntakeTest`. Regressions over what changed:
`CommandPipelineVerifierTest`, `CommandPipelineVerifierLspTest`, `JUnitXmlParserTest`,
`RedCheckerTest`, `DemoRepoVerificationTest`, `RealJUnitCriterionMatchingTest`,
`CriterionEvidenceTest`, `TestAuthorClientTest`, `FrontHalfWorkflowTest`, `DeliveryHandbackTest`,
`SwarmEngineFakeVllmTest`, `GitServiceTest`, `DependencyGraphStartupTest`,
`RequirementToCommitTraceTest`, `BrdIntakeAndBacklogTest`, `ConsoleServicesTest`,
`GuidedFlowPlanningTest`, `RequirementsIntakeRelationshipsTest`, `RequirementRowsTest`. All green.

`FullProductDressRehearsalTest` is the proving ground and passes end to end: the wizard proposes
both test names, one of them deliberately not what the author will write; the operator corrects that
one in the editor and the origins come out `PROPOSED` and `OPERATOR`; TEST_AUTHORING logs the tests
it actually wrote and reports `R1:C1 is proved by …` for each check; the red-check confirms red;
both candidates verify with `claimedChecks=2`; the requirement reaches IMPLEMENTED with its commit.

**Not run against a live model** — none is available. The live and Docker-sandboxed variants of the
dress rehearsal share this journey and these assertions, so they are changed but unverified.

## 18. ZeroZ Stack 0.8.0: the workarounds it deletes, and the one thing it breaks (2026-08-28)

The framework moved to `0.8.0-SNAPSHOT`. Every change in that release exists because this console's
own use exposed a gap, so the upgrade is mostly deletion. One change is not: a dialog is now a real
one, and that took a fix written the day before with it.

### 18.1 Six copies of the same width workaround, deleted

`Dialog` had no way to size the panel you actually see, so eleven call sites in five files reached
past the component for `getElement().getFirstChild()` and appended Tailwind classes to whatever they
found, each hoping the first child was still the panel. Six separate hand-written copies of the same
helper had grown, in `BuildStage`, `IntakeWizard`, `PlanningWizard`, `ProjectMenu`, `PipelineBoard`
and inline in `TranscriptPane`. All eleven are now `dialog.setWidth("48rem")` and the like, and the
six helpers are gone - about 40 lines, and with them the assumption about the component's internal
shape.

### 18.2 A dialog is now a real dialog, and the drill-down had to move

`open()` calls `showModal()`, so the element goes into the browser's **top layer**: Escape closes
it, focus is trapped, the page behind stops responding, and the dimmed area outside closes it.

That killed a fix landed the day before. Clicking a candidate in the run graph - which is itself a
dialog - put the candidate's details in the Inspector, the shell's right-hand panel, and the panel
had been raised over the dialog with `z-[1000]` against daisyUI's `z-index: 999`. **The top layer
beats every stacking number there is.** Nothing drawn in the ordinary page can be clicked, or even
seen, while a native dialog is open, so no number would have worked.

**Decision: a drill-down opened from inside a dialog is drawn as a second dialog over the first.**
It is already this console's idiom for going deeper - both wizards do it, and so does the
full-payload window in `TranscriptPane` - and it is the only option the browser leaves. The
`Inspector` keeps its identity: one stack of levels with one set of breadcrumbs, `open` and `push`
unchanged at every call site. `Inspector.overDialog` records, once when the drill-down opens,
whether anything was already in the top layer, and `MainView.bindInspector` builds the levels once
and places them in the rail or in the dialog. The right-hand rail stands down while the drill-down
is a dialog, so the same thing is never shown twice.

The cost, and it is real: the graph no longer answers while a candidate is open on top of it. One
Escape dismisses the drill-down and the graph is live again. The smoke test does exactly that, and
pins it.

### 18.3 Teardown moved from Close buttons onto addCloseListener

Escape and a click outside never reach a Close button, so anything a Close button did besides
closing was silently skipped from the moment 0.8.0 landed. Five places had such work and all five
now hang off `Dialog.addCloseListener`: the run graph in `BuildStage` and in `PipelineBoard`
(disposing `RunView`, which otherwise keeps redrawing a page nobody is looking at), the three story
dialogs in `PipelineBoard` (dropping the dialog from the document), the discussion window in
`IntakeWizard` (stopping the poll that waits for the model's reply), and the payload window in
`TranscriptPane` (removing itself).

**Every one of those listeners is guarded on identity** - `if (openGraphDialog == dialog)`. A close
listener runs on a green thread, so it arrives after whatever replaced the dialog has been mounted
and opened, and an unguarded listener tears down its own successor. That was reasoned out before it
was written, and the guard is why opening one run graph straight after another still works.

### 18.4 Field captions: three private helpers deleted

`new TextField("x")` sets a placeholder, and a placeholder stops saying what a field is the moment
somebody types in it. Three private helpers had grown here to put a real label above a field -
`Fields`, one in `BrdView`, one in `ProjectMenu`. All three are gone; `withLabel`/`setLabel` on the
framework's own field base does it, on every input type, with the caption tied to the control so
clicking the words focuses the field. Where a helper also took an example, that example is now the
constructor's placeholder, which is what a placeholder is for.

One rough edge worth knowing: a framework caption is a `label` holding the words in one span and a
required marker in a second, and that second span is built whether it is shown or not. A Playwright
`label:text-is('...')` selector therefore never matches a caption; use `label:has-text('...')`.

Nothing in this console uses `Binder`, so the newly visible validation messages cannot be shown
twice here.

### 18.5 Status dots stopped saying DISPATCHED

`StatusDot` took one string for both its colour and its hover text, so every dot in the console
hovered as an internal state name - through both of the sweeps that took those constants off the
visible screens, because nothing could see them. `OperatorWords` walked text nodes only, and a dot
is a coloured circle with no text in it: everything it says is in `title` and `aria-label`.

`OperatorWords` now reads `title`, `aria-label`, `placeholder` and `alt` as well, so a constant
hidden behind a hover fails the same assertion as one printed on a badge. The dots that were leaking
are fixed: the task dot in `PlanView` and the run dot in `RunGraphView` take the wording already
computed beside them, and `CandidateState` gains the `label()`/`labelOf(String)` pair `TaskState`
already had. The candidate panel's `state` row was a bare dot whose only reading was its tooltip; it
now shows the words on the row as well.

### 18.6 The replay timeline shows full names again

`LaneTimeline` used to cut every label at twelve characters into a fixed 90 px column, so
`RunGraphView` had been reduced to passing the worker's role alone - "worker-0 qwen36-27b" arrived
as "worker-0 qw" plus an ellipsis. The column measures itself from the longest name now, up to
260 px, with the full name on hover, so the model is back on the row. Which model wrote an attempt
is most of the point of reading a replay beside the graph. See `console-replay.png`.

### 18.7 The mojibake is fixed upstream, and is now pinned rather than excused

Eight strings shipped triple-encoded in 0.7.0. Checked in the published 0.8.0 jar rather than taken
on trust: the class-file constant behind the "1x" speed button is seven bytes where the 0.7.0 one
was sixty-four. The smoke test's long note explaining the corruption is replaced by an assertion on
the button's real label, which is what fails if a corrupt one ever ships again. Confirmed by eye in
`console-replay.png`. `NoMojibakeTest` guards our own sources and is untouched.

### 18.8 The dim is not doubled - measured, not assumed

The browser paints its own backdrop behind a dialog it owns, on top of the tint daisyUI already
draws on the `.modal` element. Two dims over one page would take everything behind a dialog from
"clearly inactive, still readable" to nearly black. `Dialogs.settled` now composites the two alphas
the way the screen does and fails if they add up to more than one layer's worth. It passes
everywhere, so the dim is single.

`Dialogs.settled` needed no other change: the top layer is a painting order, not a move. The element
stays where it is in the document, `querySelectorAll` still finds it, its `.modal-box` is still its
child, and `getAnimations` still reports the transition.

### 18.9 Left alone, and why

The **command palette** (Ctrl+K) is a hand-built `fixed inset-0 z-50` overlay rather than a
`Dialog`, so it cannot appear over an open dialog. That is not new: daisyUI's modal sat at
`z-index: 999` before any of this, so the palette was already behind one. The top layer neither
caused it nor makes it worse. Making the palette a real dialog is a change worth deciding on its
own.

### 18.10 What was run

All six browser tests in `sc-console`, one JVM each, plus `NoMojibakeTest`:
`ConsoleBrowserSmokeTest`, `ConsoleDialogsBrowserTest`, `ConsoleWizardsBrowserTest`,
`ConsoleBrdBrowserTest`, `ConsoleBacklogBrowserTest`, `ConsoleReadinessBrowserTest`. All green.
The whole reactor compiles clean.

Looked at, not deduced: `console-graph-drilldown-1600/1280/960.png` (new - the drill-down as a
dialog over the run graph), `console-replay.png` (the repaired glyphs and the full lane names),
`console-new-project-1600/1280/960.png` (the framework's captions),
`console-check-suggested-test-1600/960.png` (the requirement editor's captions), and
`console-intake-question-detail-960.png` (two native dialogs stacked).

---

**Not run against a live model** — none was available when this was written. Both variants have
since run against the real model, the Docker one included; §19 has the results and the four defects
they found.

---

## 19. The journey run against a live model, thirteen times (2026-08-28)

§12 said "NOT YET VERIFIED AGAINST A LIVE MODEL", and §17.6 said the live and Docker variants of
the dress rehearsal were "changed but unverified". Both are now out of date. The whole journey —
document in, requirement `IMPLEMENTED` out, with a real build and a real commit — has run against
the box's real model, repeatedly.

### 19.1 Was two requirements right?

**Yes.** The first live attempt failed at intake with `Expected size: 1 but was: 2`, and the
question was whether the analyst had silently turned the document's vague sentence into a second
requirement instead of asking about it — which R11 forbids.

It had not. The analyst asked its question first, every time it produced two requirements, and it
asked about exactly the vague sentence. Verbatim, from a run on 2026-08-28:

> **Q [Range of supported whole numbers]** "The document requires multiplication to 'behave sensibly
> at the edges of the range we support', but does not define what that range is. What is the maximum
> value for the input whole numbers and the resulting product?"

The second requirement, where one appeared, was a competent reading of the second sentence and
nothing more — "Handle multiplication at the boundaries of the supported integer range" against
"Multiplication has to behave sensibly at the edges of the range we support". Both requirements
landed as `DRAFT`, which is what R11 is actually about: nothing an agent writes is in scope until a
person accepts it, and the intake wizard cannot write to the BRD at all until the operator applies.

**The test was over-specified, and it was also blind.** How many requirements a document becomes is
the analyst's judgement — the same document became one requirement in seven runs and two in four.
The assertion demanded one and, worse, threw away every word the analyst had written, so the
question could not be answered from the failure at all. Printing what the analyst produced was the
first change made, before anything else.

### 19.2 What the live journey proves now, and what it no longer insists on

The scripted journey is untouched and keeps every assertion it had. Only the live journey changed.

**Still proved, in both:**

- Everything the analyst wrote arrives as a draft, and nothing reaches the BRD until the operator
  applies it.
- A requirement about the document's subject exists, is agreed by a person, and carries checks.
- Every check names the test that will prove it, and is marked as the wizard's proposal.
- An operator's edit to a test name is distinguishable from the wizard's suggestion — and only a
  real change flips it, so saving a row unchanged does not silently claim the operator's authorship.
- Stories claim the requirement's own criterion ids, not a paraphrase, and between them they cover
  every one — an unclaimed check could never be proved.
- The run reaches `DELIVERED`, the story stops at `REVIEW` for a person, and only acceptance moves
  it to `DONE`.
- The merge really changed product code and really carries acceptance tests where the runner looks.
- Every criterion is `PASSING`, proved by the test IT names, at the commit that delivered it, with
  one journal entry each.
- Document → requirement → check → story → run → commit is walkable end to end.

**No longer insisted on in the live journey, because these are the model's choices and a competent
analyst may make them differently:**

- How many requirements the document becomes (one or two, seen both ways).
- How many checks the requirement carries (one to five, seen).
- Whether the analyst asks a question at all — its own instructions say asking nothing is a correct
  answer, and it exercised that.
- What the acceptance-test class is called, and what the wizard first suggests it should be called.
- How many stories the planner cuts, and which files a worker chooses to write.

### 19.3 Every live run, and the failure rate

Thirteen runs. **Eight passed, five failed.** The five failures produced four defects and one test
limitation — none of them was the model merely having a bad day.

| # | Result | Wall | What happened |
|---|---|---|---|
| 1 | FAIL | 39s | Analyst reply had one stray brace; the whole analysis was discarded. **Defect.** |
| 2 | PASS | 124s | 1 requirement, 4 checks, 2/2 candidates survived |
| 3 | FAIL | 939s | Planner made a task whose write set was the acceptance tests — impossible. **Defect.** |
| 4 | FAIL | 949s | Planner failed; the fallback graph wrote no acceptance tests and parked. **Defect.** |
| 5 | FAIL | 64s | Test author wrote into `swarm`, not `swarm.accept`; no reference matched. **Defect.** |
| 6 | PASS | 150s | 1 requirement, 4 checks, 2/2 survived |
| 7 | PASS | 167s | 1 requirement, 5 checks, 2/2 survived |
| 8 | PASS | 174s | 2 requirements, 5 checks, 2/2 survived |
| 9 | FAIL | 144s | Analyst asked nothing; the test required a question. **Test limitation.** |
| 10 | PASS | 133s | 2 requirements, 4 checks, **1/2 survived** |
| 11 | PASS | 142s | 2 requirements, 1 check, 2/2 survived |
| 12 | PASS | 158s | 1 requirement, 4 checks, 2/2 survived |
| 13 | PASS | 148s | 1 requirement, 4 checks, **1/2 survived** |

**On the fixed build the last three runs passed, in 142-158 seconds each.** A green journey costs
roughly two and a half minutes; a run that parks used to cost fifteen, and now fails immediately
with the reason the product had already written down.

**Two runs had one of the two workers fail verification and the other pass.** That is the first
direct evidence for the bet the whole product rests on: on a task this small, one attempt in five
would have shipped nothing, and the tests caught it without anyone reading the code.

**A fourteenth run verified every candidate inside Docker, live**, and passed in 177 seconds with
both candidates surviving. The sandboxed half of the pipeline had never run against a real model
before. It is reached with `SWARMCODER_SANDBOX_TESTS=true` and the live flags together; the Docker
variant used to be scripted-only, and now uses the live endpoint whenever one is given, because a
scripted run through a container proves the container starts and nothing more. That run also took
the single-task FALLBACK path and still finished — the same path that dead-ended before it was
fixed.

### 19.4 The four defects, all found only by running live

1. **A stray brace cost a whole analysis.** The analyst's reply is one model call with no second
   attempt and no filter behind it. A single extra closing brace three quarters of the way through
   made the whole reply unreadable, and the operator was told their document "may not contain
   requirements" — which was false. The reply is now read up to the break and no further; nothing
   is repaired or inferred.
2. **A task nobody was allowed to do.** The planner produced "implement acceptance tests for
   multiplication" with the acceptance-test file as its write set. Workers may not touch those, so
   the task was impossible by construction, and nothing checked before two workers were dispatched
   at it and the run burned its whole budget. A write set that is entirely protected is now a plan
   violation, and the planner is told plainly.
3. **A safety net that could not work.** When the planner produces nothing usable the run falls
   back to a single task. That fallback left the acceptance-test directory null, and the test author
   returns immediately when it is null — so no tests were written, the red-check found nothing had
   been executed, and the run parked for an operator who was never coming.
4. **One directory named two ways.** The test author was told "package swarm.accept" and "all files
   must live under src/test/java/swarm" — the second repeated as the example path. The model
   followed the path, every test came out named `swarm.MultiplyAcceptTest#…`, and not one
   criterion's agreed reference matched. The protected tree and the write directory are now
   separate names.

Defects 2, 3 and 4 are one chain: each was hidden behind the one before it, and each only appeared
once the previous one was fixed. Nothing but a live run would have found any of them.

### 19.5 What was run

`FullProductDressRehearsalTest` scripted (four times, green each time); the live variant thirteen
times as above; `GuidedFlowIntakeTest` (15 cases, one new for the stray brace);
`TaskGraphValidatorTest` (14 cases, three new for the impossible task); `WorkflowPersistenceTest`
(new assertion on the fallback's acceptance-test directory); `TestAuthorClientTest` (3 cases, one
new for the prompt); and, as regressions over what changed, `AuthoredTestAuditTest`,
`FrontHalfWorkflowTest`, `OutagePauseAndResumeTest`, `ModelQuirksConfigTest`,
`DependencyGraphStartupTest`, `BrdIntakeAndBacklogTest`, `GuidedFlowPlanningTest`,
`RequirementsIntakeRelationshipsTest` and `RequirementToCommitTraceTest`. All green.

`RequirementToCommitTraceTest` was red on master before any of this and is fixed here: it still
expected the summary "R1 ACTIVE → IMPLEMENTED", which the operator now reads as "R1 went from
agreed to delivered".

---

## 20. Vagueness must not become a requirement — author decision 2026-08-28

**The decision, in the author's words:** *"the second requirement that it produced on a vague input
requirement should have been challenged and clarified before adding it. I think we need to err on the
side of caution here."* Asked how far to take it, the answer was **both halves below**, not either.

**What prompted it.** §19 established that when the analyst produced two requirements from the
two-sentence demo document it had asked its question first, about exactly the vague sentence, and that
nothing was written without the operator's approval — so R11 was not violated and the run was correct
by the rules as they stood. That is still true. But the requirement it drafted was
*"Handle multiplication at the boundaries of the supported integer range"*: the vague sentence
restated, with nothing in it a test could ever prove. Asking a good question and then drafting the
vague sentence anyway is the failure, and the rules did not forbid it.

**20.1 The analyst may not draft from a question it has not had answered.** Vagueness stays a visible
open question; no requirement is created from it. Unanswered, it does not silently become scope.

**20.2 Nothing becomes agreed scope without a check that names a test which could prove it.** A hard
gate at the moment a requirement is agreed, not advice to the analyst.

**Why both, and why 20.2 is the load-bearing half.** 20.1 alone leaves the model policing its own
vagueness, which is the one judgement this system cannot rely on — the live runs are the evidence: it
asked well and drafted badly in the same pass. 20.2 is mechanical and cannot be talked past.

**The concept already exists and fires too late.** `BrdView.ReqState.UNPROVABLE` — "agreed scope with
no agreed check on it … This is a defect in the BRD, not a stage of progress" — is derived, coloured
red, and shown only for a requirement that is ALREADY `ACTIVE`. Before agreement a vague requirement
renders as an ordinary draft. The warning arrives after the decision it should have prevented; 20.2
turns that observation into a gate.

**Not yet implemented.** Both halves land in `RequirementsIntake` and the agree path, which are in
flight in another session as this is written. Sequenced deliberately, not forgotten.

---

## 21. The analyst is the only role that does not scale — direction from the author 2026-08-28

Forwarded from a read-only investigation in another session, verified here before recording.

**Two paths put requirements in front of a model and only one of them scales.**

*The build path is already right; do not touch it.* `StoryScope` resolves what a run may build - the
story's requirements, the exact checks it claims, and quality gates inherited by walking GATES edges
up the REFINES hierarchy. Bounded by story size, not by graph size. A thousand requirements changes
nothing for the architect, planner, test author or workers.

*The analyst path sends the entire graph, unbounded.* `RequirementsIntake` renders the whole BRD and
appends it as `CURRENT BRD:` at **three** call sites - the questions pass, the drafting pass, and the
per-question discussion path (`RequirementsIntake.java:260, 380, 402`; the investigation reported two
and missed the third, so discussing one clarification re-sends the whole graph). Nothing bounds it:
`TokenBudget.maxPromptTokens` is written and read by no code (§14.4), so this does not fail cleanly,
it grows until the server refuses.

**It already fails, and not because of a limit.** A comment above `markDuplicates` admits the model
is told not to re-propose existing requirements and does it anyway - with a handful of them. That is a
needle-in-haystack failure, and it arrives long before any ceiling.

**The mechanism to catch it EXISTS and is too weak to work.** `markDuplicates` compares
`normalise(proposal.title())` to `normalise(requirement.title())` for **exact equality**. "Multiply
two whole numbers" and "Multiplication of two integers" are not duplicates to it. So the job is
upgrading string equality to similarity, not building a mechanism - and `GuidelineExtractor`
(sc-knowledge) already dedups by normalised-token Jaccard at >= 0.6, which is this problem solved once
already. Note what it deliberately does NOT do: it flags and un-ticks rather than dropping, because a
genuine near-duplicate is sometimes a real distinction the operator wants. Reclassifying a near-match
as an EDIT is a behaviour change, not merely a better match, and the existing rationale is on the
method.

**The decision: hybrid, split by role, not by preference.**

- **Push, unchanged, for the swarm workers.** N-way dispatch economics depend on all N receiving a
  byte-identical prefix so the server's prefix cache is reused; `SwarmDispatcher` builds it once per
  group and logs `prefixHash` to prove it. A per-worker tool call is divergent by nature. The project
  made this exact call once already: the LSP analyser is deliberately on-demand and never enters the
  prefix (S6). Workers get no requirements tool - their scope is one task, one write set, named
  checks, and they should never need to search.
- **Pull for the analyst.** One instance, few calls, no prefix cache to protect, and the role that
  drowns.

**What to build:** measure at 100 / 1,000 / 5,000 first and stop if the shape differs; move
duplicate and edit detection out of the prompt into code, bounded by the number of proposals rather
than the size of the graph; send a bounded relevant subset and SAY in the prompt that it is a subset,
so an absent requirement is not read as a non-existent one; give the analyst an in-process search tool
(a `ToolBinding` like the workers' six - **not** MCP, descoped by S7); and either make
`maxPromptTokens` real or state plainly that it is still inert.

**Must not change:** the build path; `prefixHash` byte-identity across a task group (assert it); and
R11 with §20 - retrieval must never become a route by which an agent writes.

**Sequenced with §20 deliberately.** Both land in `RequirementsIntake` and the agree path, and they
are the same principle: the model's judgement must not be the only gate on what enters the requirement
graph - §20 applies it to vagueness, this applies it to duplication. One session does both.

---

## 22. Four run kinds that delivered nothing, and a resume that used the wrong project (2026-08-28)

Both defects come from `docs/FLOW_INVENTORY.md` §13, items 1 and 2. They turned out to share a
cause, which is why they were fixed together.

### 22.1 The four kinds that reported success without doing anything

`BugfixWorkflow`, `RefactorWorkflow`, `DocsWorkflow` and `AnalysisWorkflow` were about fifty lines
each. Every one renamed the run's state three or four times, called `swarmEngine.executeRun` with no
task graph for it to execute, logged `No task graph found for run …`, and marked the run
**DELIVERED**. No design, no review, no plan, no acceptance test, no worker, no build, no merge. All
four were on the chat's command menu, all four were described in `docs/USER_MANUAL.md` as working
features with stages of their own, and no test had ever started one.

**The decision is not the same for all four, because they are not the same thing.**

**Bugfix and refactor are real work, and they are the same shape as a feature.** Everything that
makes a delivery mean anything here — the design, the review, the plan, acceptance tests written
first, the red-check that requires those tests to fail before a worker starts, N workers in their
own worktrees, a real build per candidate, the judge, the merge audit — is identical for all three.
What differs is what the design roles are told. So they now run the ONE delivery path
(`GreenfieldWorkflow`) and the difference is a string: `RunBrief`.

That is not a downgrade of "make it real". It *is* the real shape, and the machinery for it already
existed:

- A bugfix's brief says the acceptance test must reproduce the fault — fail against the code exactly
  as it stands, pass only once the fault is gone. The red-check enforces precisely that, before any
  worker starts. The `REPRODUCE` state the old class walked through had nothing behind it; the
  red-check is what "reproduce first" actually means in this system.
- A refactor's brief forbids behaviour change and requires the acceptance criteria to be
  *structural*, so they are false today and true afterwards. "Existing tests stay green" needs no new
  machinery either: the verification pipeline already runs the existing tests on every candidate, and
  a candidate that edits a test to make it pass fails the merge audit.

Building `REPRODUCE` and `CHARACTERIZE` as real stages would have duplicated the front half of the
pipeline for no behaviour the pipeline does not already have.

**Docs and analysis are not builds, and are removed.** Everything downstream of the architect here
works from a check that a named test proves. Documentation and analysis produce nothing of that
kind, so the red-check, the verification pipeline, the judge and the merge audit have nothing to
work with — which is exactly how these two came to report delivery without doing anything. They are
off the chat menu, out of the user manual, and refused by intake. The enum constants stay
(`WorkflowKind.DOCS`, `ANALYSIS`) because runs persisted by older builds carry them; what changed is
`isStartable()`, and a run of a retired kind found in a store is marked **ABORTED**, never delivered.

**And a backstop under all of it, so this cannot come back as a fifth thing.** `RunPersister` — the
single funnel every workflow transition is persisted through — refuses to record a run as DELIVERED
when it has no task graph. A task graph exists only after a design was made and sliced, and nothing
can be built, verified or merged without one, so a run that reaches DELIVERED without one delivered
nothing. It throws rather than downgrading: the transition is abandoned, the run stays at the last
state it honestly reached.

### 22.2 A resumed run adopted the default project

`WorkflowEngine.resumeAll()` took every unfinished run in the store with no project filter, and
`Main` called it on the default project's engine. A run belonging to a second project was therefore
continued against the **first** project's repository, its git service and **its list of locked
modules** — a write to the wrong codebase and a containment failure (§13) at the same time, needing
nobody to press anything. Every persistence test used a single project, where the wrong engine and
the right engine are the same object.

**The cause underneath it.** Every state transition in those four workflows rebuilt the run through
`Run(UUID, WorkflowKind, RunState, UUID, UUID, Budget, Instant, RunReport)` — eight arguments
carrying neither the project nor the story. §12 records this being found and fixed in the *main*
workflow only, by copying the two fields back on afterwards. The other four still dropped them, so
their runs lost the project on their very first transition and had none to be resumed against.

**What stops it coming back.** Not a rule to remember:

1. **The eight-argument constructor is gone.** The only constructor takes the project and the story,
   next to the id. There is no way left to build a run without its project.
2. **Transitions do not rebuild runs at all.** `Run.withState(...)` is one copy, in one place,
   carrying every field including the outage pause. A future field cannot be dropped by a transition
   that forgot to list it.
3. **The project is the routing key, not an argument.** `RunResumer` asks the store which projects
   have unfinished runs, asks the registry for each of those projects' engine, and hands each run
   only to the engine built for it. `DependencyGraph.workflowEngineFor(projectId)` builds a project's
   context on demand. When a project cannot be opened its runs are left where they are — deliberately
   not resumed elsewhere, because a run resumed against the wrong repository is the original defect
   with better manners, while a run left alone loses nothing.

The event logger had the same single-project scoping and is fixed the same way:
`DependencyGraph.setEventLogger` applies to every project's engine, including ones built later.

### 22.3 What was run

New: `RunKindsTest` (5 cases — bugfix and refactor produce a real design and task graph; the kind
brief reaches the model; docs and analysis end ABORTED with nothing planned; only building kinds are
startable; a run with no plan cannot be recorded as delivered) and `TwoProjectResumeTest` (2 cases —
two projects, two repositories, two lock lists, the run under scrutiny belonging to the second, and
an assertion that a project which cannot be opened does not have its runs resumed against another
project's tree).

Regressions over what changed: `WorkflowPersistenceTest`, `StrandedStoryRecoveryTest`,
`OutagePauseAndResumeTest`, `FrontHalfWorkflowTest`, `DeliveryHandbackTest`, `FinalIntegratorTest`
(sc-workflow); `FullProductDressRehearsalTest` (sc-app); `ProjectDeleteStoreTest` (sc-store);
`ConsoleServicesTest`, `BacklogServiceTest`, `StageGuidanceTest`, `RequirementToCommitTraceTest`
(sc-console). All green.

---

## 23. The tests that never ran (2026-08-28)

`FLOW_INVENTORY.md` found that thirteen test classes and four further test methods ran only when
somebody typed a flag, and that nobody ever did. Among them were the six tests that boot the real
console in real headless Chromium — the only thing that had ever driven the operator interface,
covering the shell, the requirements screens, the pipeline board, both wizards, three dialogs and
the empty state. They had caught real defects repeatedly and then sat unrun for weeks while every
report counted them as coverage.

### 23.1 The decision: the default is on, and the reason is that there is no CI

The obvious answer to "these are slow" is a profile that continuous integration runs. **There is
no continuous integration in this repository** — no `.github/workflows`, no pipeline, nothing that
builds this code except a person at this workstation, and `build.bat` passes `-DskipTests`. A
profile CI runs would run nowhere, which is the state we were already in under a different name.

So a test that needs an outside tool now runs whenever that tool is present, with no flag.
`@RunsWhen(Need.CHROMIUM | DOCKER | MAVEN)`, in the new `sc-testsupport` module.

### 23.2 The polarity is the safety property

§17 recorded the trap: an empty `<properties>` block in a pom silently overrides a command-line
`-D` inside the surefire fork, and it had already made one gate skip without saying so. Two gates
were moved to environment variables because of it.

That was right about the trap and wrong about the answer. An environment variable nobody sets
skips exactly as silently as a property that got lost. What actually fixes it is the direction of
the default:

- **before** — a flag that failed to arrive meant the test silently did not run;
- **now** — a property that fails to arrive means the test runs.

The plumbing can still break; it can no longer cost coverage. The tool probes follow the same
rule: a probe that is not sure says the tool is there, so an uncertain probe yields a test that
runs and fails with the real error, never one that quietly disappears.

### 23.3 Four gates stay, and each says why

`LIVE_MODEL` (`-Dswarmcoder.live.baseUrl`) — the model server is shared with other work and must
not become a build dependency. `PAID_CLOUD_MODELS` (`SWARMCODER_CONFIG_E2E`) — it spends real
money; a build must not be able to bill anybody. `DEMO_REPO` (`-Dswarmcoder.demo.repo`) — the
`dev/demo-repo` it wants is not in this checkout.

### 23.4 No skip is silent any more

Every skip prints a banner on standard error and appends a line to
`<module>/target/tests-not-run.txt`. A green build now carries the list of what it did not check.
`Assumptions.assumeTrue` is banned for this purpose — it skips in silence; `NotRun.needed(...)`
does the same job out loud.

### 23.5 What it cost, measured

Paired runs of the same command with and without: `sc-console` +3 min 30 s, `sc-app` +1 min 38 s,
`sc-sandbox` +34 s, `sc-verify` +20 s, `sc-workflow` +8 s. About six minutes across the build.
`-Dswarmcoder.skipSlowTests=true` is the tight-loop escape hatch, and it announces every test it
skips so a build run that way cannot be mistaken for a complete one.

### 23.6 One harness defect, no product defects

Every switched-on test passed, except `ConsoleWizardsBrowserTest`, and that was the harness.
`Dialogs.assertNothingOverflows` measured every direct child of the dialog, including one with
`display: none` — which reports an empty rectangle at the document origin, outside every container
on the page. The check was written for an SVG strip all of whose children are drawn; pointed at a
dialog, it reported the intake wizard as overflowing at every window width, and nothing was wrong
with the wizard. Children that paint nothing are now passed over.

### 23.7 What was run

All six console browser tests (`ConsoleBrowserSmokeTest`, `ConsoleBrdBrowserTest`,
`ConsoleBacklogBrowserTest`, `ConsoleWizardsBrowserTest`, `ConsoleDialogsBrowserTest`,
`ConsoleReadinessBrowserTest`) plus the rest of `sc-console` — 152 tests, all green;
`BrowserVerifierTest` and `DemoRepoVerificationTest`; `DockerSandboxLiveTest` and
`DockerSandboxHardeningTest`; `RealJUnitCriterionMatchingTest` and `LiveEndToEndTest`;
`FullProductDressRehearsalTest` and `ConfigDrivenE2ETest`; `LiveM1Test`. The build was also
deliberately broken — one false assertion in a browser test, no flags — to confirm it goes red
rather than skipping into green. It did.

**Already red on master, untouched here:** `sc-store`'s `BacklogWireRoundTripTest` fails two of
its six cases (`aBrdWithCriteriaAndAnNfrGateRoundTripsWhole`, `aTaskRoundTripsWithAllThreeOfItsSets`)
— a wire round-trip whose object no longer equals itself after the trip. It is not gated and never
was; it is simply failing, and it fails every `-am` build that reaches `sc-store`.

---

## 24. A house rule can prove itself, and the judge is shown the rules (2026-08-28)

§19's investigation established two things about house rules — the markdown files under
`<repo>/.swarmcoder/guidelines/<scope>/<slug>.md`. First, that they **work**: five live attempts
each way, interleaved, and the model declared a Java record 5/5 with the folder empty and 0/5 with
a `no-java-records` rule on disk. Second, that a rule was **never enforced**: a worker was made to
disobey a rule that demonstrably reached its prompt, and the candidate verified green, was judged
0.9, and was SELECTED.

This section is what was decided about the second half, and what was built.

### 24.1 The judge is shown the rules — DECIDED AND BUILT

The judge received the diff, the task and a verification summary. Nothing else. It could not have
penalised a breach if it had wanted to, because it had never been told there was anything to
breach. That was free information being withheld from the one stage whose entire job is judgement.

`JudgeClient` now receives the same rendered ACTIVE guidelines the workers were given, for the same
task, resolved once per task.

Three things about how, each deliberate:

- **Capped at 3 000 characters**, a quarter of the worker's 12 000 and an eighth of the 24 000 the
  diff may occupy. The judge is reading the diff; a long rulebook must never be able to crowd it
  out. The worker's budget is not the judge's, and pretending otherwise would have been the easy
  mistake here.
- **The reply schema is widened additively.** The two fields that were always required — `score`
  and `rationale` — are untouched, and an optional `violations` list is added. Local models emit
  doubled braces and prose-wrapped JSON at this endpoint (which is why `LlmJson` exists), so a
  reply that omits the new field, or gets its type wrong, still parses into a usable score exactly
  as before. Nothing about judging becomes more fragile.
- **A breach is visible in words, not only in a number.** Anything the judge names as a broken rule
  is folded into the `rationale` stored on the candidate, under a `HOUSE RULES BROKEN:` heading —
  and the rationale is what the Gallery renders. A score that dropped for a reason nobody can see
  is not enforcement, it is noise.

**What this does NOT do, stated plainly.** The judge ranks candidates. With one candidate there is
nothing to rank, so a rule with no declared check can still be broken by a candidate that is then
selected — the judge's opinion is the only thing weighing against it, and there is nothing to
prefer instead. That is the honest limit of guidance, it is pinned by a test, and it is precisely
why 21.2 exists.

### 24.2 A rule may declare how to prove itself — DECIDED AND BUILT

Optional front-matter, one shell command:

```yaml
---
status: active
scope: PROJECT
check: mvn -o -q -B checkstyle:check
checkTimeoutSeconds: 120
---
Never declare a Java record; use a final class with explicit accessors.
```

Exit 0 means the rule was obeyed. **A candidate that fails the command does not survive
verification**, so it never reaches the judge and can never be merged. A rule with no `check:` is
unchanged: guidance that reaches the prompt and that the judge now weighs.

`check:` is a single-line shell string, exactly as `verify.yaml`'s commands are. A YAML block
scalar is refused with a warning rather than half-read, because a command silently truncated to a
lone block indicator would exit 0 and pass every time.

**Where in the pipeline, and why there.** compile → **guideline checks** → acceptance → existing →
lint → browser. After the compile, because a rule's command inspects the candidate's source and
source that does not compile is not a candidate at all — failing it on a rule check would bury the
real cause. Before the tests, because a grep-shaped check is milliseconds against minutes for a
suite and the pipeline's whole ordering principle is cheapest-first. That is the slot lint would
occupy if lint decided anything; the difference is that lint is advisory and a declared check is
not. Every declared check runs — the stage does not stop at the first breach — because an operator
fixing a candidate wants the whole list, not the first item of it.

**A kill or a repair seed? A repair seed, and not a new kind of anything.** A broken rule makes the
candidate not survive, exactly as a red test does. It therefore feeds the existing repair round
(whose evidence brief already carries the verification log tail, so the repair worker is told which
rule and what the command said) and, if repair also fails, the existing BLOCKED path. No new
`KillReason`, no new state, no second failure vocabulary.

**`Verdicts.survived` is widened deliberately.** §17 records the last session that changed what
"survived" means and wrote down why; this is the second. The argument is the same one: a rule the
operator wrote, that names a command, that the command says was broken, is evidence of exactly the
kind a red test is, and a candidate carrying it must not reach the judge. A report written before
this existed carries no results at all, and that reads as "no rule declared a check" — never as "a
rule was broken", which is the same fail-open-on-no-evidence rule §17.1 settled.

**What the operator sees.** The verification report gains a `guidelineChecks` list, persisted per
candidate, holding for each rule its slug, scope, the rule's own words, the command, the exit code
and the tail of what it printed. The verdict reason — appended to the report's log tail, which is
what the Gallery shows — names the rule in the operator's own words rather than by slug:

```
this candidate broke 1 house rule that declared how to prove itself:
  - BROKEN: project/no-java-records — "Never declare a Java record. ..."
    proved by: findstr /S /C:"public record" src\main\java\*.java >nul & if errorlevel 1 ...
    exit 1
    src/main/java/com/example/calc/Money.java: public record Money
```

"Guideline check failed" is a red mark nobody can act on. The rule's own sentence is a fix.

**A repository with no `verify.yaml` still gets its rule checks run.** Otherwise the one situation
in which nothing at all is verified would also be the one in which the operator's own rules quietly
stop applying. The candidate stays UNVERIFIED in every other respect, exactly as the M1 allowance
says.

### 24.3 The containment hazard, and what was verified rather than assumed

§13.1: **policy must be resolved from a tree the restrained party cannot write.** This feature adds
a second file naming commands the orchestrator runs, which is the exact shape of the worst of the
three bugs that section records. Four things hold it, and the first three were each checked rather
than reasoned about:

1. **Resolved from the operator's checkout.** `GuidelineSync` reads
   `<projectPath>/.swarmcoder/guidelines`, the main checkout — never a candidate's worktree — the
   same distinction `VerifySpecLoader.loadTrusted` draws. Checks are resolved ONCE per task, before
   any worker runs, so every candidate of a task is measured against the identical rule set.
2. **`.swarmcoder/` is protected at BOTH enforcement points.** Verified with new tests rather than
   inferred from the constant: a worker's `write_file` is refused, a worker's shell command has the
   file it created deleted and the violation counted, and a rule file planted in the winning diff
   parks the run at FINAL_INTEGRATION with the path named. That third one matters most — tool
   checks only see what the TOOLS did, and `git add -A` sweeps a shell's work into the diff.
3. **The check runs where verification runs.** It goes through the same `ExecTarget` as every other
   stage, which is the sandbox's action server whenever the sandbox is on. `GuidelineCheckRunner`
   never reaches for a local process on its own.
4. **Only a human-authored rule may carry a command.** The extractor writes `source: extraction`
   guideline files, and their text is written by a model reading worker transcripts — content the
   swarm produced. A machine-proposed rule keeps its text as guidance and loses its command, with a
   warning. Letting that path end in a command the orchestrator executes would put the restrained
   party back inside the policy tree.

**Deliberately NOT done: final integration does not run rule checks.** `FinalIntegrator` verifies on
the HOST, unsandboxed (§13.3). This feature adds no new host execution. The gate is candidate
verification, which every winner has already passed.

**No path was found by which a worker could introduce or alter a rule's command.**

### 24.4 The four defects — all fixed

The reconcile compared exactly one thing, the body text, and skipped any file whose body it had
seen before. That single line caused three of the four, all silent:

1. **A retired rule could never come back.** Delete a rule file, restore it byte-for-byte, and it
   stayed RETIRED forever — the body had not changed since the deletion, so the file was skipped.
   The only way back was to edit the text.
2. **The documented promotion did nothing.** Flipping `status: proposed` to `active` — the move
   spec §5.3 names — was never read, same cause.
3. **Machine proposals were recorded as human-authored.** `source: extraction` was never parsed, so
   every extracted rule was stored with `provenance = human, confidence = 1.0`: the values reserved
   for a rule a person wrote, which by design never decay. That is what the Console displayed.
4. **Two projects annihilated each other's rules.** One process-wide index keyed by scope + slug
   with no project id, so each project's reconcile retired the other's rules — permanently, given
   defect 1.

The reconcile now derives the WHOLE state a file describes — body, status, provenance, check
command, timeout — and writes a new revision whenever any of it differs from the store, RETIRED
included. A machine proposal enters at confidence 0.5 and keeps whatever decay has since done to
it; a human rule is pinned at 1.0 as before. Every guideline records the project that owns it, and
a reconcile only renders, revives or retires its own. An entry written before ownership existed is
adopted by whichever project still has its file, and is never retired by a project that does not —
it cannot be attributed, and guessing would be how the original defect got in.

### 24.5 Also decided, and why

**Scope now resolves one kind of conflict and still forwards the other.** A global `records.md`
saying "always use a record" and a project `records.md` saying "never use one" were BOTH sent, the
project one merely listed first, and the model was left to work out which the operator meant.
Sharing a slug across scopes is the one contradiction detectable without asking a model, so it is
now resolved: the most specific scope wins, the others are dropped, and the shadowing is logged.
Two rules with DIFFERENT names that contradict each other are still both sent — nothing here can
read English, and pretending otherwise would be worse than saying so.

**`DecisionKind.GUIDELINE_REVIEW` stays deliberately dormant.** It exists as an enum value and a UI
label and nothing creates one. Wiring it was considered and declined for now: the failure it was
meant to compensate for was that file-based promotion did not work, and file-based promotion — the
documented primary path — now does. Adding a second promotion route through the decision queue is a
Console change, and the Console is being changed in parallel. Recorded here so the next reader
knows it is a decision, not an oversight.

**`guidelines.commitToMain` stays absent from config.** Spec §5.3 rule 4 mentions it; `SwarmConfig`
has no such key. It is not added, because the guidelines directory is `ALWAYS_PROTECTED` and the
swarm never commits it under any setting — an inert config key that looks like it controls
something is worse than a documented absence.

### 24.6 What was run

- Live, against `qwen3.8-27b` at `http://192.168.0.10:8002/v1`, interleaved, both arms:
  `LiveGuidelineEnforcementTest`. Same model, task, repository and rule file; the only difference is
  whether the rule reached the worker's prompt. **Rule not in the prompt: 2/2 candidates broke it
  and 2/2 FAILED verification, each naming `no-java-records`. Rule in the prompt: 2/2 obeyed and
  2/2 SURVIVED the very same command.** Both directions measured, so the check is neither failing
  everything nor passing everything.
- End to end with a scripted model, real Maven, real pipeline: `GuidelineEnforcementTest`, both
  halves. Rule with no check — candidate SELECTED at 0.9, and the judge's prompt now contains the
  rule. Rule with a check — 3 candidates (one worker plus the repair round it triggered), none
  selected, the judge never invoked at all.
- Inverted, and green: `GuidelineScopeTest` (4), `GuidelinePromotionTest` (4).
- New: `GuidelineCheckStageTest` (6) — stage ordering, all-breaches-reported, old reports survive.
- Containment: `WorkerToolboxTest` (18, two new), `FinalIntegratorTest` (5, one new).
- Regression over changed files: `GuidelinePromptWiringTest`, `SwarmEngineFakeVllmTest`,
  `SwarmDiversityTest`, `JudgeClientTest` (8, three new), `CommandPipelineVerifierTest`,
  `CommandPipelineVerifierLspTest`, `EmptyCheckIsNotAPassTest`, `RedCheckerTest`,
  `DemoRepoVerificationTest`, `StoreArchitectureTest`, `StoreRootReachabilityTest`,
  `ConsoleServicesTest`, `GuidelineSyncTest`, `GuidelineExtractorTest`. All green.
- `mvn -o -q -DskipTests test-compile` over the whole reactor: exit 0.

`StoreArchitectureTest` caught a real mistake on the way: `GuidelineCheck` was written as a record
in `com.swarmcoder.domain`, where an ArchUnit rule bans them because EclipseStore cannot persist
one. Nothing persists `GuidelineCheck`, but the rule is blanket on purpose — a record in that
package is a trap for whoever later puts it in the store root — so it became a POJO.

---

## 25. The requirements editor: five things the code does that the design forbids (2026-08-28)

Found by driving the requirements screens the way a person uses them (§merged with
`test/requirements-by-hand`). Five faults were fixed there; these five are recorded and NOT fixed,
because each is the code contradicting a written design decision rather than a bug, and one needed
the author.

**25.1 Delete destroys — author decision 2026-08-28: it must retire instead.** The design states
that nothing is ever destroyed: requirements are deprecated, checks retired, stories cancelled, and
the `ChangeEvent` journal and `CriterionVerification` history are append-only (§12, B-56, §13.1). The
code removes. Asked directly, the author chose **retire, never destroy**: a deleted thing is marked
out of scope, stops affecting anything, leaves the normal view, and the requirement-to-commit trail
survives. The reasoning is that the trail is what the product is FOR, and a delete severs it silently
— leaving delivered work with nothing recording why it was built. A separate destructive action was
considered and rejected: it adds a concept to a UI with a deliberate four-noun budget.

**25.2 The surface an operator lands on is read-only.** The list has no "new" and no clickable row;
every edit begins by finding the Graph tab. `REQUIREMENTS_AT_SCALE_DESIGN.md` §9 step 4 specifies row
actions — "Open · Show what surrounds it · Add child requirement" — and only the diagram icon exists.
This is the highest-value of the five: the default surface cannot write and does not say so.

**25.3 Editing a claimed requirement warns nobody.** UX v3 §2.2 requires that editing a requirement
a story has claimed shows its impact ("2 passing checks go stale; S2 will be sent back") and requires
confirmation. On the hand-edit path there is no dialog and no impact line. Verified by causing it: the
passing check correctly went stale and the "needs re-checking" filter found it with honest counts, but
the story card did not change at all and still read "Agreed work. Nothing is building it yet." The
honest half happens automatically; the warning and the send-back do not exist here. The
document-analysis wizard already computes that exact sentence.

**25.4 A retired quality constraint still gates.** `StoryScope.gatesFor` has no status filter, so a
deprecated NFR continues to gate every descendant, and the rows still read "constrains R1" with
nothing saying otherwise. §2.6 says a deprecated constraint is not evaluated.

**25.5 Internal relation names are on screen.** The diagram legend and the edges editor say
*refines*, *gates*, *derived*; history summaries say "added edge R6 refines R1". UX v3 §5 rule 3
forbids it, and `OperatorWords` does not reach the diagram.

**Also unresolved, recorded there:** deleting a check leaves any story claiming it pointing at
nothing, with no warning and no repair; and restoring a revision has no confirmation and no summary
of what it will remove — which, before the restore fix, silently destroyed the document.

**Sequenced.** All five land in the requirements editor and the graph's supporting code, which §20
and §21 are in as this is written.

---

## 26. Starting from nothing: a first run that works (2026-08-28)

`FLOW_INVENTORY.md` §13 item 4, and §10 step 1. A person who cloned this repository, built it and
started it got this and nothing else:

```
14:41:13 INFO  c.s.app.Main - Starting SwarmCoder... (full log: …\.swarmcoder\logs\swarmcoder.log)
14:41:14 ERROR c.s.app.Main - SwarmCoder failed to start: java.io.IOException: Config file not
                              found at …\.swarmcoder\config.yaml
java.io.IOException: Config file not found at …\.swarmcoder\config.yaml
	at com.swarmcoder.app.config.ConfigLoader.loadDefaultConfig(ConfigLoader.java:19)
	at com.swarmcoder.app.DependencyGraph.<init>(DependencyGraph.java:116)
	at com.swarmcoder.app.Main.main(Main.java:46)
```

Exit 1. No hint that a file was wanted, what belongs in it, or that the window it never reached is
where the answer lives. Nobody had met it, because everyone who has ever run SwarmCoder already had
a settings file from an earlier era.

### 26.1 What a fresh machine is missing, and what each one does now

Four things, and the right answer is different for each. They are not interchangeable, and
"write a default and carry on" is only correct for one of them.

| Missing | Decision | Why not the others |
|---|---|---|
| the settings file | **write a starter file and carry on** | Nothing about the file's absence is a decision the operator made. Refusing teaches nothing that carrying on and explaining does not. |
| the window's port | **the starter file sets it (9090)** | This is the one value with a genuinely safe default, and it is load-bearing: with no port there is no interface, so there is nowhere for any other message to appear. |
| the folder of code | **left empty; the window says so** | A guessed `repoPath` is not a convenience. SwarmCoder branches, writes files and commits in that folder. A default here is damage to a folder nobody chose. |
| a model endpoint | **left empty; the window says so** | An invented address buys a connection failure five minutes later in place of a plain sentence now. |
| Docker and the worker image | **start anyway; say what to do** | The container is on by default and required by default, so a first run on a machine with no Docker cannot build — but it can still read documents, write requirements and plan work, all of which live in the store. Refusing to start would take those away to punish a missing dependency they do not use. |

The shape of the answer, in one line: **nothing that damages anything gets a default; everything
else gets one; and whatever is left is said out loud, in the window and in the log.**

### 26.2 Why not a wizard

Because one already exists and is better than anything a fresh screen would be.
`ConsoleReadiness`, `ReadinessPublisher`, `SetupView` and the header's health dot were built to
answer exactly one question — what is the single next thing to do — from facts only the server can
see. A first run is that machinery's own case, not a new one. The work was therefore to get the
process as far as the window; the window already knows what to say.

### 26.3 What was built

`ConfigLoader` writes `starterConfigText()` when the file is absent and loads what it wrote. The
starter file is hand-written text, not a serialised record, because Jackson writes neither comments
nor blank lines and this is a file a person is expected to open and read. It sets `consolePort`,
leaves `repoPath: null` with the reason next to it, and carries commented examples of the model
entries in the shape the settings screen writes.

`EnvironmentChecks.outstandingSetup` returns the list of what is still missing, worst first, and
`logSetupSummary` prints it as the last thing at startup — the line a first-time reader is left
looking at. It returns the sentences rather than only logging them so a test can assert the words;
text a person depends on is the product. Silent when nothing is outstanding.

`DependencyGraph.installConsoleContext()` was split out of `startConsole()`. Installing the context
is what publishes readiness — what the operator is TOLD; binding the port only decides where they
read it. Keeping them together meant the only way to assert the first-run message was to bind the
configured port, which on a shared workstation takes somebody else's.

`DependencyGraph.sandboxBlocksRuns()` names the judgement that was previously inline in the
console's blocker lambda, so the startup summary and the console give the same answer.

### 26.4 The startup text was written for the wrong reader

Every line of it. `EnvironmentChecks` cited "DEVELOPER_CORRECTIONS.md §7 Q1" at somebody who had
just downloaded the program, and described a filesystem mount by its protocol ("9p"). The sandbox
block said `sandbox: {enabled: false}`; the model warning said `roles.workerFamilies`; the vision
warning said `roles.vision` and "BRD". These are operator-facing text and are now held to the same
rule as the screens (CONSOLE_UX_V3.md §5 rule 3): short sentences, everyday words, no internal
names. The sandbox instructions also gained the `mvn package` that has to precede
`docker build`, without which the command that was being recommended fails on a missing jar.

### 26.5 Left deliberately undone

- **The console still auto-creates a project called "default" when no folder is set**, so the setup
  surface ticks its first step ("default is selected") on a machine that has no project in any
  meaningful sense. The second and third steps are correctly unmet with reasons, the health dot is
  red, and the "Next:" line is right — so a newcomer is not misled about what to do, only about
  what they already have. Fixing it means either not building a default context (it is non-null
  everywhere) or changing what `hasProject` means (it gates the authoring surfaces, which genuinely
  work without a repository). Both are the console's decisions, not startup's.
- **The window lands on Requirements rather than Setup** on a first run, by
  `Stages.recommended`, which deliberately stopped sending people to a configuration panel. Whether
  a machine with no folder at all is the exception is a UX decision and was left to that surface's
  owner.
- **`deploy/build-images.sh` still builds an unrelated image** under a different tag
  (`swarmcoder/sc-java:latest`) from the one the application looks for. The startup message now
  carries the correct commands, so nobody is sent to that script, but the script itself is
  untouched (FLOW_INVENTORY §12 item 13).
- **The user manual's sections 5 to 7 still describe screens that no longer exist**
  (FLOW_INVENTORY §12 item 12). Sections 1 to 4 — what you need, settings, building and starting,
  and the first run — were rewritten to be true, and section 5 now opens with a note saying the
  rest is stale. Rewriting the whole manual was not this job.

### 26.6 What was run

New: `FirstRunColdStartTest` (sc-app, 5 cases). It is a real cold start, not a unit test of the
loader: an empty temporary home, a guard that fails if the home is not genuinely empty or is the
developer's own, and the real `DependencyGraph` — the composition root `Main` builds. It asserts the
starter file appears, that it sets a port and does NOT set a folder, the exact sentences the
operator is given, that none of them contains a setting name or a document reference, and what the
console publishes about the machine. One case starts the real console server from a cold home on a
port the operating system picks, to prove the window actually binds.

Regressions over what changed: `DependencyGraphStartupTest` and `ModelQuirksConfigTest` (sc-app).

---

## 27. Vagueness cannot become scope, and the analyst stopped reading the whole document (2026-08-28)

§20 and §21, implemented together because they land in the same file and are the same principle: the
model's judgement must not be the only gate on what enters the requirement graph. §20 applies it to
vagueness, §21 to duplication.

### 27.1 Can a vague requirement still become agreed scope? No.

`AgreementGate` (sc-domain) is one rule: a requirement may not be agreed unless at least one of its
checks — one that will still be a gate afterwards, so not a retired one — names a test. It is asked
at **all three** doors an operator can agree through, because a rule enforced at two of three is not
a rule:

- **the Agree button** (`BrdServiceImpl.promoteRequirement`) — refused, and nothing changes;
- **agree everything** (`promoteAllDrafts`) — agrees what it can and NAMES what it could not, which
  is the only honest answer at a batch. Refusing all ten because one is unprovable is punitive;
  agreeing nine and saying nothing leaves the operator believing they agreed ten;
- **the status dropdown in the editor** (`saveRequirement`) — the same act performed with a
  different control, and the one that would otherwise have been the open door.

**Creation is untouched, deliberately.** Somebody writing a requirement by hand types the title
first and the checks a minute later, and the intake wizard has to be able to hand over a draft to
read. A draft nothing can prove is a perfectly reasonable thing to have on screen; it is only a
defect once somebody calls it scope. Restoring an old revision is also exempt, following what
`RequirementTree` already decided: history is reproduced as it was.

**The gate is where `BrdView.ReqState.UNPROVABLE` was pointing.** That state — "agreed scope with no
agreed check on it … a defect in the BRD, not a stage of progress" — was derived, coloured red, and
shown only once the requirement was already agreed. The warning arrived after the decision it should
have prevented. It stays as a badge for graphs that already contain the shape; nothing new can reach
it through the console.

**What the operator sees.** The Agree button is greyed out rather than hidden, with the reason in
warning colour directly beneath it: *"R1 has no check on it, so nothing could ever show it was met.
Add at least one check — something you could observe being true — and name the test that will prove
it. Until then it stays a draft."* Or, where checks exist but none names a test, *"R1 has 3 checks
but none of them names a test, so nothing would ever run to prove them."* The sentence comes from
`AgreementGate` itself, so the button's explanation and the server's refusal are one derivation. The
fix is in the criteria panel immediately below, which is where the operator already is.

**What the gate does NOT claim.** It cannot read a sentence and judge it vague. What it can do is
refuse the mechanical consequence of vagueness — no check, or a check nothing will ever run — and
that is a bar the drafted example from §19 could not have cleared as scope. Judging the wording
itself is 20.1's job, and 20.1 is where the model is still involved.

### 27.2 The analyst may not draft from a question it has not had answered (§20.1)

Two changes, because one of them is advice and the other is not.

**The prompt stopped inviting it.** An unanswered question used to be rendered to the drafting call
as *"NOT ANSWERED — you must choose the most defensible reading and say so explicitly in the
requirement's ASSUMPTION line."* That is an instruction to do exactly the thing §20.1 forbids. Three
states are now distinguished where there were two: **answered**, **skipped** — the operator read it
and chose not to settle it, which is permission to take the most defensible reading — and **still
unanswered**, which is not the analyst's to resolve at all.

**And a mechanical half, because a prompt is advice.** `markUnansweredEchoes` unticks any ADD that
says back the wording of a question nobody answered, quoting the question in the rationale. It
compares the proposal against the sentence the question QUOTED — the document's own words, the
sharpest signal available — falling back to the question's subject and text when it quoted nothing.

Measured on the real reply from 2026-08-28: the vague restatement *"Handle multiplication at the
boundaries of the supported integer range"* says back **1.00** of the sentence its question quoted;
the requirement the document genuinely stated, from the same reply, says back **0.17**. The
threshold is 0.60.

That measure is one-directional and is not the near-duplicate score, because it is not the same
question. Two requirements are alike when they overlap both ways; a proposal drafted from a question
absorbs the question's wording and adds a good deal of its own, and a two-way measure punishes it
for that.

Flagged, not dropped — the same rationale the duplicate check has carried since it was written. The
operator may look at it and decide it is fine. What they may not do is get it without noticing.

### 27.3 The analyst was sent the entire graph and now is not (§21)

**Step 1, measured before anything was changed.** The shape is exactly as §21 described, so the work
went ahead. Characters of requirement graph in one analyst call:

| requirements | before | after | shown |
|---|---|---|---|
| 100 | 21,442 | 21,481 | all 100 |
| 1,000 | 220,260 | 26,633 | 120 |
| 5,000 | 1,127,860 | 27,316 | 120 |

The whole drafting prompt, as the model receives it: 29,146 / 35,092 / 35,777 characters. Before, at
5,000 requirements, that same prompt was over 1.13 million characters — roughly 280,000 tokens,
which no model this product runs will accept. It did not fail cleanly; it grew until the server
refused.

A project small enough to send whole is sent whole, and 100 requirements costs **39 characters
more** than before — the heading that now states how much was sent.

**Step 2, and it is the important half.** `markDuplicates` compared `normalise(title)` to
`normalise(title)` for exact equality, so "Multiply two whole numbers" and "Multiplication of two
integers" were not duplicates to it — which is the case that actually happens. It is now
`RequirementSimilarity`, and the mechanism was upgraded rather than replaced.

**Lucene's analysis, and none of its index.** `EnglishAnalyzer` for tokenising, stop words and
stemming. No index: thousands of short records already sit in this process so scanning is instant,
whereas an index over a document edited this often goes stale and then answers confidently wrong
with nothing to show that it did.

**Stemming alone does not work, measured.** Lucene's English stemmer takes "multiply" to `multipli`
and "multiplication" to `multipl` — close, not equal. Same for notify/notification
(`notifi`/`notif`), verify/verification and deliver/delivery. So two stems also count as one word
when the shorter is a prefix of the longer and is at least five characters. Five is the floor the
measurements settled on: `multipl`, `notif`, `verif` and `deliv` need it, and four would let "data"
match "database" and "sign" match "signal". Without the prefix rule the motivating pair scores 0.40
and is missed; with it, 0.556 and it is caught.

**The numbers on fourteen hand-labelled pairs** (`RequirementSimilarityTest` prints them):

| | score |
|---|---|
| same title, different case and punctuation | 1.000 |
| verify / verification | 1.000 |
| deliver / delivery | 1.000 |
| guest checkout, reworded | 0.778 |
| **multiply / multiplication — the pair that motivated this** | **0.556** |
| notify / notification | 0.556 |
| refund window, reworded | 0.538 |
| *multiply vs divide — one word apart* | *0.600* |
| *multiply vs add — one word apart* | *0.600* |
| *sign in vs password reset* | *0.571* |
| *cart total vs invoice total* | *0.429* |
| *data vs database — the prefix trap* | *0.167* |
| *checkout vs refunds* | *0.143* |
| *latency vs availability* | *0.000* |

(italic rows are the pairs that genuinely differ)

**No threshold separates these perfectly, and that is the finding.** "Multiply two whole numbers"
against "Divide two whole numbers" shares every word but the verb, and no bag-of-words measure can
rank it below "Multiply two whole numbers" against "Multiplication of two integers". Inverse-document
-frequency weighting was built and measured against these same pairs to try: it did not separate
them better, and it costs something this does not — a score that depends on the rest of the graph
changes when unrelated requirements are added, so the same two sentences would be flagged one day
and not the next, for reasons nobody can see. It was removed. Nothing but the two pieces of text
decides the score.

**So the thresholds follow the cost of being wrong, and there are two of them.**

- **0.50 — flag and untick**, naming the handle. Catches six of seven true pairs. It also catches
  three of the seven that differ, and those three are requirements that really do sit next to each
  other in the same area; telling the operator "this resembles R13" about them is information, and
  it costs one tick to say no. A missed duplicate costs a requirement written twice.
- **0.85 — the same requirement said again.** The proposal is re-cast as an EDIT against the handle
  it repeats, so the operator reads a diff rather than a second copy, and `markImpact` then tells
  them what agreeing that diff would cost in stale evidence. Set well clear of the worst false
  positive measured (0.600), because this tier points at a specific requirement and getting that
  wrong would offer a rewrite of the wrong one.

**Re-casting a near-match as an EDIT is a behaviour change, and it was weighed against the recorded
rationale rather than around it.** The comment on `markDuplicates` says it flags rather than drops
because a genuine near-duplicate is sometimes a real distinction the operator wants. That is still
honoured: nothing is dropped, nothing is ticked, and the operator decides. What changed is what they
are shown — a diff against R500 rather than a second R500 — and the measurement above is what makes
the stricter tier safe to point at a handle at all.

**Step 3, a bounded subset that says it is one.** `BrdSubset` sends at most 120 requirements, ranked
by the same matcher against the documents being read — which is the right ranking for what the
analyst is about to do. The heading states the project total, states how many are shown, and says
plainly that the rest exist and that an absent requirement is not evidence of a non-existent one.
Only edges with both ends inside the subset are drawn, because an edge naming a handle the reader
cannot see reads as a dangling reference.

**All three call sites, including the one the investigation missed.** The questions pass, the
drafting pass, and the per-question discussion — where discussing one clarification re-sent the whole
graph, once per message the operator typed.

**Step 4, the analyst's one tool.** `RequirementSearch`: reply with a single line `SEARCH <words>`
and get back the closest-matching requirements with their handles, up to four times per call. In
process, one Java method over the graph already in memory — **not MCP**, descoped by author decision
S7, and this is one list entry rather than a protocol.

It is offered **only when the graph was trimmed**. On a project small enough to send whole there is
nothing to look up, and adding a tool protocol there would be pure cost: a longer prompt, another
chance to answer with a tool line instead of an answer, and a behaviour change to the only path this
product has ever actually been run on.

**Read-only is a rule, not an implementation detail** (R11, §20). The search takes a `Brd` and
returns a string; there is no store, no project id and no mutation to reach.

**Step 5 — the token budget is still inert, and it now says so where somebody will see it.**
`TokenBudget.maxPromptTokens` is written by configuration and read by nothing, exactly as §14.4
recorded. It stays because it is persisted inside `Task` and older stores carry it, but its javadoc
now states plainly that it is not a limit, and why enforcing it there is not free: the only two
things possible at that boundary are to TRIM a worker's prompt, which breaks the byte-identical
prefix every worker of a group shares and throws away the prefix cache the swarm's economics rest
on, or to REFUSE the dispatch, which stops runs that work today. What IS enforced, and is what was
actually growing without bound, is the analyst's document character budget (refused before the run
starts, with the two numbers and both ways out) and the 120-requirement cap on the graph.

### 27.4 The build path was not touched, and there is a test that says so

Workers still get six tools and none of them reaches the requirement graph; every worker of a group
still shares one prefix, byte for byte, with persona and sampling appended strictly after it
(`WorkerScopeIsUnchangedTest`). The split is by role and for a reason that is money rather than
taste: N-way dispatch pays the shared prefill once, and one worker looking something up would make
every one of them pay full price. The project made this exact call for the code analyser (S6).

### 27.5 What was run

New: `AgreementGateTest` (10 cases — the sentence itself, all three doors, a retired check, and that
writing and editing a draft is untouched); `RequirementSimilarityTest` (9 cases, printing the tables
above); `AnalystAtScaleTest` (8 cases — the measurements at 100/1,000/5,000, and at a thousand
requirements: a restatement becomes a change to the requirement it repeats, a reworded one is
flagged, something genuinely new is still proposed and still lands, a conflict is still raised, the
search reaches a requirement the subset left out, and a small project is not offered a tool
protocol); `UnansweredQuestionsTest` (2 cases, replaying the real 27B reply from 2026-08-28);
`WorkerScopeIsUnchangedTest` (2 cases).

Regressions over what changed: `GuidedFlowIntakeTest`, `RequirementsIntakeRelationshipsTest`,
`BrdIntakeAndBacklogTest`, `ConsoleServicesTest`, `RequirementToCommitTraceTest`,
`BacklogServiceTest`, `RequirementRowsTest`, `StageGuidanceTest`, `ConsoleBrdBrowserTest`,
`ConsoleWizardsBrowserTest` (sc-console); `FullProductDressRehearsalTest` (sc-app).

**Three existing assertions were changed, all because the behaviour deliberately changed.**
`ConsoleServicesTest` and `BrdNodePositionTest` created a requirement straight into agreed scope
with no checks, which is now refused; they create a draft instead, and the refusal is pinned in
`AgreementGateTest`. `GuidedFlowIntakeTest` looked for the literal heading `CURRENT BRD:`, which now
states how much of the graph was sent.

The whole `sc-console` module was run at the end — 181 tests, all six console browser tests
included, all green — plus `StoreArchitectureTest` (sc-store), because a new type in
`com.swarmcoder.domain` is something that guard has an opinion about.

**One new third-party artifact:** `lucene-analysis-common`, at the version `lucene-core` and
`lucene-codecs` are already pinned to. Analysis only.

---

## 28. The requirements editor: nothing is destroyed, and the list can write (2026-08-28)

§25's five faults, implemented together because four of them land in the same three files and the
fifth is the reason the fourth was invisible.

### 28.1 Delete retires. Nothing in the requirements can be destroyed any more.

**Author decision, §25.1.** `deleteRequirement` removed the requirement, every edge touching it and
its hand-placed position; `deleteCriterion` removed a check outright, leaving any story claiming it
pointing at nothing. Both are gone. `retireRequirement` marks the requirement out of scope and
`retireCriterion` takes a check out of the gate; both keep everything, including the evidence that
ties a check to the commit and the test that satisfied it.

**"Stops affecting anything" is enforced from ONE place**, `BrdRequirement.isRetired()`. Everything
downstream asks that rather than testing the status itself:

| what | before | now |
|---|---|---|
| `gatingCriteria()` | every accepted check | none — a retired requirement gates nothing |
| `CheckCounts.of(…)` | counted normally | `EMPTY`, so no coverage figure or roll-up includes it |
| `StoryScope.resolve` | built it | skips it: no run may build a retired requirement |
| `StoryScope.gatesFor` | **no status test at all** (§25.4) | a retired quality bar constrains nothing |
| the list | — | it leaves, counted in the footer, one click to show |
| the diagram | drawn like any other | not drawn, with a line saying how many and offering them |
| a row's relation badges | "constrains R1", for ever | "no longer constrains R1" at both ends |

**"Leaves the normal view" has one deliberate exception, and it is the hierarchy.** A retired
requirement with a live part is still drawn, dimmed and marked as context, because a match may never
be orphaned from its place in the tree (at-scale §3.7, UX v3 rule 1). Retire the part too and both
leave. The footer counts retired requirements SEPARATELY from filtered-out ones: "your filter did
not match this" and "this is not part of the project any more" are different sentences, and one
number for both would make retiring look exactly like deleting.

**Coming back needs no new concept.** The Status picker already offers draft / agreed / delivered /
retired, so setting a retired requirement back to a draft brings it back. A separate "restore"
control would have added a fifth thing to a UI with a four-noun budget, which is the same reason the
author rejected a separate "really destroy".

**A story claiming a retired check keeps its claim, and is told.** The claim is the record of what
the story was undertaken to deliver, and quietly rewriting it would be the same silent severing in a
different place. What changes is that the check stops gating — `StoryScope` has always skipped
retired checks — and the operator is told on the spot, by name: *"That check is retired and kept on
the record. S1 now delivers 1 check."* Or, when it was the last one: *"S1 has nothing left to
deliver — give it another check, or drop it."* A story that can never finish is not something to
discover weeks later.

**What can still be destroyed: a link between two requirements.** `deleteEdge` still removes the
edge. Both requirements survive, nothing claims an edge, no evidence hangs off one, and the removal
is in the journal and in every later revision snapshot. Adding a retired state to `BrdEdge` would put
a fourth status enum into a persisted, append-only type to record something already recorded twice.
Said out loud rather than left to be discovered.

### 28.2 The list an operator lands on can write

**§25.2, the highest-value of the five.** It had no "new", no clickable row and no editor, so every
edit began by finding the Graph tab — and nothing on the screen said so. All three row actions the
at-scale design §9 step 4 specifies are there now: **open it** (the handle and title are the
control), **add a requirement inside it** (`+`), and **show what surrounds it** (the diagram icon,
which was the only one that existed). A "New requirement" button sits in the header and in the empty
state.

**It is the SAME editor, extracted rather than copied.** `RequirementForm` (sc-console-ui) is the
form that used to live inside `BrdView`; both surfaces mount it. The owner supplies four things:
which requirement is being edited, how to read the stored version of it, where to put a line of
feedback, and what to do after a write. The diagram reads its live copy off the graph signal; the
list re-fetches the one requirement through `BrdService.requirement(id)` — one requirement, not the
document, which is the cost the tree exists to avoid.

**Two copies of one form are in the page at once, and that broke three browser tests before it broke
anything else.** Every placeholder and every test id inside the form now matches twice, and
`waitForSelector` takes the FIRST match — which is the hidden one. Each copy carries
`data-form="graph"` or `data-form="list"` and the tests scope to it. This is the same trap the
`text=Close` incident recorded: a selector that does not say which of two identical things it means.

**"Add a part" is one server call**, `addRequirement(requirement, parentId)`, so the tree never
briefly holds a top-level requirement nobody asked for. The link is written after the requirement, so
a rejected shape never costs the operator the text they typed.

### 28.3 Three things that were silent now say what they cost, in the server's own words

`RequirementImpact` (sc-domain) computes one sentence. The document-analysis wizard already had it
written out inside `RequirementsIntake.markImpact`; that method now asks this instead, so there is
one derivation rather than two that drift.

- **Editing a requirement a story is building.** *"Editing R2 affects 2 agreed checks and 1 story. 1
  test that passes today will stop counting and has to be run again against the new wording. S1 will
  be sent back to be built again."* Confirm and it happens, the send-back included — that half did
  not exist at all (§25.3): the check correctly went stale and the story card did not change.
- **Taking a requirement out of scope.** *"Retiring R5 takes it out of the list and out of every
  count. Nothing is deleted: R5 stays on the record with everything that was built for it, and you
  can bring it back by setting it back to a draft."* It always says "nothing is deleted", because the
  control used to say Delete and used to mean it.
- **Restoring an old revision.** *"This puts the requirements back to how they were at revision 14. 2
  requirements written since then leave the list: R6, R7. 2 requirements go back to their older
  wording, checks included. Nothing is deleted: this is saved as a new revision, and restoring a
  later one brings everything back."* Computed on the server, which is the only side that can compare
  the two versions.

**Only a story actually in flight is sent back** — building, back for a verdict, or stopped. A story
nobody has started has nothing to be sent back from, and delivered work is never churned by somebody
typing in a text box. The sentence says nothing about a send-back when there will not be one:
overstating it is the same fault as saying nothing.

### 28.4 The five relation names are off the screen

**§25.5.** The diagram's legend read *depends · refines · conflicts · derived · gates*; its edge rows
badged each link with one of those; its picker offered all five; and the history panel printed *"added
edge R6 refines R1"*. `RequirementRelation` now carries the wording — a `label()` for a legend or a
picker, and `fromPhrase()`/`toPhrase()` because **a relation reads differently from each end**: the
quality requirement *constrains*, the ordinary one is *constrained by*, and one phrase on both rows
would tell the operator something backwards. `RequirementRows` had that direction logic and the words
written out; it keeps the logic and reads the words from the enum.

| internal | what the operator reads | from the other end |
|---|---|---|
| `DEPENDS_ON` | waits for | needed by |
| `REFINES` | part of | contains |
| `CONFLICTS_WITH` | conflicts with | conflicts with |
| `DERIVED_FROM` | drawn from | led to |
| `GATES` | constrains | constrained by |

History now reads *"linked R6: now part of R1"*. `RequirementTree`'s refusal — "use X or Y to express
a looser connection" — reads the labels rather than spelling out two Java constants, which is what it
was doing. The legend's hover text was rewritten out of "the source" and "the target" too.

**`OperatorWords` reaches this now.** The five constants join the forbidden list, and the lower-case
leaks that actually happened (`refines`, `derived from`) join the internal-noun list, matched
case-insensitively. `gates` and `depends on` are deliberately absent: both are ordinary English that
appears in legitimate prose, and the leak they came from is closed by the enum rather than by an
assertion.

### 28.5 What the agreement gate looks like

Never photographed before. `ConsoleAgreementGateBrowserTest` drives the console to both refusals — no
check at all, and checks that name no test — and pins six screenshots at 1600, 1280 and 960:
`agreement-gate-no-check-*.png` and `agreement-gate-no-test-*.png`. At every width it also measures
that the reason sits directly under the button it explains and inside the dialog, because a correct
sentence pushed off the bottom of a narrow window is the defect a text assertion cannot see.

**One thing the screenshots found.** For a draft with no checks, the gate's reason and the checks
panel's "No checks…" warning were two warning-coloured paragraphs in a row saying nearly the same
words — a wall of orange rather than one thing to go and fix. The panel warning is now suppressed for
a draft, where the line above is the one attached to the control the operator would press.

### 28.6 What was run

New: `RequirementImpactTest` (5 cases — the sentence, which story states are sent back, and that a
retired requirement counts nothing while keeping everything); `RequirementEditImpactTest` (4 cases,
through the service — the warning then the send-back, retiring keeping the evidence and being
reversible through the Status picker, retiring a check naming the story, and the restore summary);
`ConsoleAgreementGateBrowserTest` (browser, `@RunsWhen(Need.CHROMIUM)`).

Regressions over what changed: `RequirementTreeTest`, `RequirementRollupTest` (sc-domain);
`StoryScopeTest` (sc-workflow, two new cases — a retired quality bar gates nothing, a retired
requirement is not built); `StoreArchitectureTest` (sc-store, because a new type landed in
`com.swarmcoder.domain`); `RequirementRowsTest`, `RequirementRowsWireTest`, `ConsoleServicesTest`,
`BrdNodePositionTest`, `AgreementGateTest`, `GuidedFlowIntakeTest`,
`RequirementsIntakeRelationshipsTest`, `BrdIntakeAndBacklogTest`, `RequirementToCommitTraceTest`,
`BacklogServiceTest`, `StageGuidanceTest` (sc-console); and all six console browser tests —
`ConsoleRequirementsByHandBrowserTest`, `ConsoleBrdBrowserTest`, `ConsoleWizardsBrowserTest`,
`ConsoleBrowserSmokeTest`, `ConsoleDialogsBrowserTest`, `ConsoleBacklogBrowserTest`. All green. The
whole reactor compiles, tests included.

**Six existing assertions changed, all because the behaviour deliberately changed.**
`ConsoleServicesTest` and `BrdNodePositionTest` asserted that deleting removed a requirement, its
edges and its position; they now assert that retiring keeps all three and that it leaves the list.
`RequirementRowsTest` read `"from R4"` and now reads `"drawn from R4"`. `RequirementTreeTest` and
`ConsoleRequirementsByHandBrowserTest` asserted the refusal names "depends on"; they read
`RequirementRelation.label()`. `ConsoleBrdBrowserTest` asserted the page CONTAINS "refines" — it was
pinning the leak — and now asserts the opposite. And the by-hand test's assertion that the list
"offers no way to write anything" is inverted, which was the point of the exercise.

### 28.7 One thing the component library should provide and does not

**A confirmation dialog.** Three actions needed the same shape — a question, a sentence saying what it
costs, a button labelled with the act itself, and a way out — and the library has `Dialog` but nothing
above it. `ConfirmDialog` (sc-console-ui) is 60 lines and belongs one level down; every application
built on this stack will write it. Nothing else needed an inline one-off style: `withLabel`,
`setWidth`, and closing on Escape and on an outside click all did what 0.8.0 says they do, and the
Escape path is what the browser tests use to close the editor.

---

## 28. A project onboarding wizard — author decision 2026-08-28, sequenced

**The author's request:** a wizard offering three ways in — a new project starting from a full
requirements document; an existing project where the requirements are extracted and matched against
what is already built; and a small bug fix or update that goes straight to the architect without
analysing the project first — *"This will explain the project onboarding to the user and how/what
they can do."*

**Agreed, and cheap: all three routes already exist or are being built.** Route one is the flow
proven live in §19. Route two is `docs/ADOPT_EXISTING_PROJECT_DESIGN.md`, designed and re-costed by
§11 of that document. Route three is the fast on-ramp (§ the on-ramp work, in flight as this is
written). There are two wizards to copy the pattern from (`IntakeWizard`, `PlanningWizard`), a
`SetupStage`/`SetupView` to launch from, and `ConsoleReadiness`/`StageGuidance` whose whole job is
answering "what is the single next thing". This is a front door onto existing capability, not new
capability.

**28.1 It chooses a STARTING POINT, not a project type.** CONSOLE_UX_V3 §2.2 states that the
requirements pool is always open — *"Nothing about being 'in the build phase' closes intake"* — and
§2.1 caps the concept budget at four nouns. A mode that makes a project bug-fix-shaped would gate an
operator out of adding requirements on Tuesday because of what they clicked on Monday, which is
exactly the gating the §21/on-ramp reframe removed. So: it decides what happens in the next ten
minutes, nothing it chooses is irreversible, every route can reach every other, and no screen ever
says "this is a bug-fix project".

**28.2 "Do not analyse the existing code" means do not build a requirement graph — not do not read
the code.** A worker fixing a bug still has to read the code to find the bug. The dial is *how much
ceremony before the first change*, not *whether to look*. Taken literally the other way, it would
make bug fixes worse rather than faster.

**28.3 The explaining surface is the valuable half.** Nothing in the product says what SwarmCoder is
or what an operator can do with it; the user manual's later sections still describe deleted screens
(§26). A newcomer who gets past installation still does not know what it wants from them. The words
are the deliverable here, and they are what the author will judge.

**Sequenced by author decision:** built after the fast on-ramp lands, so it is written against what
exists rather than what is expected.

---

## 29. The on-ramp for an existing project (2026-08-28)

> *"I want to be able to load up an existing project and get SwarmCoder making changes in hours if
> not minutes and not days."*

This is **not** the requirements-extraction feature in `docs/ADOPT_EXISTING_PROJECT_DESIGN.md`, and
the reason is the whole design decision: **the requirement graph is not the on-ramp and must stop
being treated as one.** A requirement written because work was done is real and provable; one mined
from a three-year-old README may describe a system that no longer exists. Requirements accrue from
work; they do not gate it.

### 29.1 What already existed, verified rather than assumed

Three claims were checked before anything was written, and all three held:

- **The freeform path exists.** `ChatOrchestrator` gives a typed-goal run an ENABLER story, origin
  `AD_HOC`, with **no criteria** — it delivers no requirement until somebody triages it.
- **The architect already handles work answering to no requirement**, and says so in two places.
- **Nothing knew how to build an arbitrary project.** `pom.xml`, `build.gradle` and `package.json`
  appear nowhere in `sc-verify` or `sc-app` production code. `VerifySpecLoader` returns empty when
  `.swarmcoder/verify.yaml` is absent, and verification is then skipped.

The third is the whole blocker. With no contract every candidate comes back UNVERIFIED, which
hollows out the swarm entirely: choosing between N attempts **is** testing them, so what is left is
N unchecked guesses and a coin toss.

### 29.2 `ToolchainDetector` — working out how to build a repository nobody has seen

New in `sc-verify`. Reads a repository root and returns a `Detection`: the toolchain, the evidence
in plain English, the questions the operator has to rule on, a proposed `VerifySpec`, and where
TEST_AUTHORING should write. `render` turns it into commented YAML that `VerifySpecLoader.parse`
reads straight back (asserted).

**It proposes; it never decides.** Every detection carries its evidence and its warnings, and the
YAML is meant to be edited. A wrong build command costs a whole run and the detector cannot read a
README.

**It never guesses.** An unrecognised root proposes nothing and says what it can see instead. The
absent-contract behaviour then stands exactly as §5 documents it — skipped loudly, candidate
unverified. A guessed command that exits 0 for the wrong reason is strictly worse than no contract,
because it certifies a candidate nobody checked.

**Where the file goes is the §13.1 boundary**, not a convenience. Nothing in `sc-verify` writes
anything; the callers write into the OPERATOR'S own checkout, which is where
`VerifySpecLoader.loadTrusted` reads it from. A worker able to edit it could rewrite the commands
that judge it.

Per toolchain, and what each got right on real repositories:

| toolchain | proposed | measured against |
|---|---|---|
| maven | wrapper if the platform's own is present, else `mvn`; `test-compile`, `-Dtest=swarm/accept/**`, `test`; per-module report dirs | this checkout (20 modules), a throwaway clone of zeroz4j (58 modules, nested aggregators) — both correct, zeroz4j compiled in 89 s |
| gradle | wrapper if present; `testClasses`, `test --tests 'swarm.accept.*'`; subprojects from the settings file | unit tests only — no Gradle project was to hand |
| node | package manager from the lockfile, `build`/`test`/`lint` scripts read from `package.json`, JUnit reporter chosen from vitest/jest in the dependencies | a real Node project — npm, build, test, vitest all correct |
| cargo | `cargo build --all-targets`, nextest for both test stages, clippy as advisory lint | unit tests only |
| python | `compileall`, pytest with `--junitxml` | a real Python project — correct, and it did NOT fire the "pytest was assumed" warning because the pyproject names pytest |

**Two things it gets right that a person writes wrong.** The Surefire acceptance selector must be a
**path** pattern (`swarm/accept/**`); the dotted package form matches nothing, so the stage runs zero
tests, reports no failures and reads exactly like a green one — that is §15.2, and it had been in
every checked-in contract in this project. And a **multi-module reactor writes its reports per
module**: `ExecTarget.listFiles` walks a directory and does not glob, so the root
`target/surefire-reports` of a reactor build finds nothing at all. The detector enumerates every
module's report directory — 41 of them for this checkout, 117 for zeroz4j. That list is long, and it
is the honest answer; the on-screen summary counts them rather than printing them.

### 29.3 `ContractProbe` — the contract is run once before it is offered

Also new in `sc-verify`. It executes the proposed **compile** stage, once, and reports what it ran,
what came back, how long it took, and the tail of the output.

It deliberately does **not** run the existing-test suite: on a large repository that is half an hour,
and whether the suite is green today is the operator's business, not the detector's.

**A red probe is not a verdict on the detection.** A missing dependency, an unset environment
variable, a generator that has not been run — all of them look identical to a wrong command from
here, and the project not building on a clean checkout is common. The probe says so in those words
and leaves the operator to decide, with the log in front of them. What it will not do is let the
contract be saved silently: the command-line on-ramp refuses `--write` after a failed probe, because
every candidate would then fail identically for a reason that has nothing to do with the candidate.

The probe time is also the most useful number on the screen: **every candidate pays it, in parallel,
and again on every repair round.**

### 29.4 Two ways in, both of which show the operator what will run

- **`swarmcoder onramp <path> [--write] [--no-probe]`** (`OnrampCli`, dispatched from `Main` before
  anything is built). It has to work before the console is up, before a project exists in the store,
  and while the answer is still being argued about.
- **The New-project dialog** grew a "How should SwarmCoder build and test it?" section: one button
  that detects and really runs the build, the evidence and the open questions in prose, and the YAML
  in an editable box. Two buttons, not one, because checking costs minutes and saving costs a file
  write. Saving from here never overwrites an existing contract.

The screen reaches the detector through `ConsoleContext.BuildContracts`, wired by
`BuildContractBridge` in `sc-app` — `sc-console` does not depend on `sc-verify`, the same seam
`ConfigFormsBridge` uses.

### 29.5 The two defects an unscoped run actually hit

Both were found by running it, not by reading it, and **neither was fixed by weakening a gate.**

**The ad-hoc story was attached to a run that had already left.** `ChatOrchestrator` called
`context.startRun(goal, kind)` and minted the story afterwards. `ConsoleContext.startRun(goal, kind,
storyId)` exists precisely to prevent that and says so at length: the engine advances the run on its
own thread the instant it is handed over and rebuilds it from its own copy at every transition, so a
`storyId` attached a line later is written to an object nobody reads again. This was not a race that
sometimes lost — the engine is always faster than the caller's next statement. Every freeform run's
backlog card therefore sat in "building" for ever. Extracted to `AdHocStory`, which creates the work
item first and starts the run already bound to it, and which is now reachable from a test — the one
path an operator takes on their first day was the one path nothing could drive.

**An empty slice was read as a failed delivery.** `CriterionEvidence.allDelivered` returned false for
any empty scope. Right for a story that names requirements and produced no checks; wrong for an
AD_HOC story, whose definition is that it carries no requirement. Measured on 2026-08-28: an unscoped
run designed, planned, had four acceptance tests written, watched them go red, ran two workers,
verified **both candidates green (4 acceptance and 7 existing tests passing each)**, selected a
winner and merged it onto an integration branch — and was then recorded **ABORTED**. The change was
real, verified and already merged; only the run's state disagreed with what it had done. That is the
§22 shape exactly, in the other direction.

**It had been hidden by the first bug.** `recordDelivery` returns true immediately for a run with no
story at all, and the lost binding meant the engine's run had none. Fixing the binding is what made
this visible.

The fix distinguishes "no criteria because this answers to no requirement" from "no criteria though
it should have some": an `AD_HOC` story naming no requirements is deliverable; a story that names
requirements, or one from the backlog, is still blocked exactly as before. The story goes to REVIEW —
the operator's question — never DONE, and the log says in words that no requirement was proved, so
REVIEW cannot be misread as a requirement met.

### 29.6 What did NOT block an unscoped run, checked one at a time

- **§27 `AgreementGate`** (no requirement may be agreed unless a live check names a test) — never
  reached. An ad-hoc run agrees no requirement.
- **`TaskGraphValidator` criterion coverage** — the coverage invariant is only evaluated when the
  scope is non-empty. An empty slice has no criterion to leave unclaimed. Warnings only, correctly.
- **§22 `RunPersister`** (no DELIVERED without a task graph) — an ad-hoc run plans one like any
  other, so it satisfies this honestly.
- **The red-check** — and this was the surprise. Even with no requirement criteria, the ARCHITECT'S
  design gives the task its own criteria, so the test author wrote four real acceptance tests, they
  failed to compile against the pre-change tree, and the red state was confirmed. An unscoped run is
  **not** an unverified one: it has acceptance evidence, it simply does not trace to a requirement.
- **`Verdicts` empty-check gate (§17.1)** — unchanged, and unchanged deliberately. A task claiming no
  requirement-checks keeps the M1 allowance.

None of these was touched.

### 29.7 Measured, on a real repository, against the live model

A single-module Maven project with git history, its checked-in contract deleted first so the
detector had to produce one:

| step | seconds |
|---|---|
| detect the toolchain | 0.1 |
| run the proposed compile command once | 3 |
| DESIGN | 12 |
| DESIGN_REVIEW | 1 |
| PLAN | 5 |
| TEST_AUTHORING (write three acceptance tests, commit, red-check) | 15 |
| EXECUTING (two workers, one produced nothing, the other verified green) | 232 |
| FINAL_INTEGRATION (merge, quality gates, delivery record) | 13 |
| **total, from pointing at the repository to a verified merged change** | **282 s — 4 min 42 s** |

The selected candidate came back with 3 acceptance tests passed and 6 existing tests passed, and
its commit is on the task. The other worker ran sixteen turns and produced no usable change, which
is the swarm doing exactly what it is for.

**What dominates the clock is the workers, and after them the build.** Design through red-check is
about half a minute. Detection is a tenth of a second. On this checkout the compile stage alone is
about 40 seconds and on zeroz4j it is 89 — paid by every candidate, in parallel, and again on every
repair round. On a large repository the build, not the model, is the number to attack, and the
detector's own warning says so and tells the operator to narrow the commands with `-pl`.

**The acceptance test written first is not what costs the time and must not be removed to go
faster.** It is about fifteen seconds of the four and a half minutes, and it is the entire reason
there is any evidence at all that the winner was a winner.

### 29.8 What still needs an insider

- **The goal has to name something concrete.** "Add a percent method that returns 30 for (200, 15)"
  works. A vague goal produces a vague design and there is no requirement graph behind an ad-hoc run
  to sharpen it.
- **A worker's write set comes from the plan, so a first goal that touches an unfamiliar corner of a
  large tree is worth phrasing with the module in it.**
- **Nothing yet narrows the contract automatically.** The detector correctly proposes root commands
  for a reactor and warns that this is the dominant cost, but an operator has to know that `-pl
  <module> -am` is the answer and which module to name.
- **Windows path length.** Cloning a deep repository into a long scratch path fails mid-checkout with
  "Filename too long" and leaves a repository that looks cloned. Not an on-ramp defect, but it is
  what a first attempt hits.

### 29.9 What was run

- `ToolchainDetectorTest` (sc-verify) — 12 tests, all green. Includes the Surefire path-selector
  rule, per-module report enumeration for a nested reactor, the node/cargo/python shapes, the
  unrecognised root, and the render→parse→`loadTrusted` round trip across the §13.1 boundary.
- `AdHocStoryTest` (sc-console) — 5 tests, all green. The load-bearing one asserts the ORDER: the
  work item exists in the store, and the run carries its id, at the instant the engine is handed the
  run.
- `CriterionEvidenceTest` (sc-workflow) — 11 tests, all green (4 new). An ad-hoc story with no
  criteria is deliverable; one that names requirements, or one from the backlog, is still blocked.
- `ExistingProjectOnrampTest#aRepositoryWithNoContractGetsOneThatActuallyBuildsIt` (sc-app) — green.
  Real Maven build, contract deleted first, loaded back across the trusted-root boundary.
- `ExistingProjectOnrampTest#plainEnglishAgainstAnExistingRepositoryProducesAVerifiedChange`
  (sc-app, `@RunsWhen(Need.LIVE_MODEL)`) — the measured run above.
- `ConsoleDialogsBrowserTest` (sc-console, Chromium) — extended to walk the on-ramp section of the
  New-project dialog at all three widths.
- The command-line on-ramp was run against four real repositories: this checkout, a throwaway clone
  of zeroz4j, a Node project and a Python project.

---

## 30. The front door, and what ZeroZ Stack 0.8.0 deleted here (2026-08-28)

Two jobs in one session: take what the framework's new release offers, then build the onboarding
wizard §28 asked for, written against what exists rather than what was expected.

### 30.1 The breaking changes, checked one at a time rather than taken on trust

Four claims were carried into this session. Three held and one did not.

| claim | verdict |
|---|---|
| `Drawer`, `Toast` and `Tooltip` are not used here | **held.** Two files match a grep for those words and both are false positives — a private method called `nodeTooltip` and one called `sparkTooltip`. Nothing constructs any of the three. |
| there is no `onDetach` anywhere | **held.** Zero occurrences in the whole checkout. |
| the dialog changes were adopted this morning | **held** (§18.1–18.3). |
| `Steps` gained item support, so the hand-built list items can go | **wrong.** |

**`Steps` did not gain items.** What it gained is a width cap and, on `add`, a rule letting a long
step name break rather than set the width of the whole trail. There is no `addStep`, no item class,
and no `Steps$Item` in the published jar — checked in the class list of
`zerozstack-ui-components-0.8.0-SNAPSHOT.jar`, not in the source tree, because the two could differ.
`Timeline` is the component that gained events (`Timeline$Item` is in the jar); `Steps` is not.
So `RunGraphView.renderPhaseStrip` still builds its own list items and still has to know daisyUI's
`step`, `step-success` and `step-primary` class names. **The gap stands and is still worth
reporting upstream.**

**One breaking change nobody had listed: `KeyedList` must now be disposed.** It watches its signal
for as long as it exists and never handed anything back to stop it with; in 0.8.0 it implements
`Disposable`. Four call sites here dropped the returned object on the floor — `ChatDock`,
`ChatView`, `KnowledgeView`, `ProjectMenu` — and all four watch signals that outlive the view. The
worst is `ChatView`: the dock swaps it out on every chat switch, so an operator who had opened eight
chats had eight live watchers rebuilding eight dead transcripts. All four now keep the list in the
`disposables` list each class already had.

### 30.2 The text scale: 27 replaced, and the four kinds it cannot express

`sc-console-ui` holds **264** hand-written `text-base-content/NN` strings across **eleven** different
fade values, and 74 uses of `text-[11px]`. That is the drift `TextStyle` and `Emphasis` exist to end.

**Replaced: 27.** The rule was deliberately narrow — a leaf element whose entire style is *one size
plus one base-content fade*, optionally a line height, and nothing else. Those are the ones where
the intent genuinely is "small quiet text" and the scale says it better. They spanned twelve files
and five fade values (`/35`, `/40`, `/50`, `/60`, `/70`); all twenty-seven are now `CAPTION` or
`SECONDARY`, three of them with `Emphasis.FAINT` where the words really are background.

Two visible consequences, both intended by the release and both looked at rather than reasoned
about: text that was 11 px is 12 px, because the smallest named size is 12; and text that was 40%
or 50% of the content colour is now a 70% fade of whatever colour it sits on, because 0.6 is the
floor the scale allows. Quiet text across the console is therefore slightly larger and slightly more
present than it was.

**Left alone, and why. This is the feedback the library's author wants:**

1. **Eleven pixels.** The scale's smallest size is 12 px and there is deliberately no way to say
   "nearly that". 74 places here ask for 11. Most are dense chrome — a build's phase strip, a row of
   counts in a header — where 12 px genuinely changes the layout, and they carry other classes with
   them. No feedback needed: the release already says 10 px was below what anybody should be asked
   to read, and 11 is the same argument. It is recorded so the count is honest.
2. **The small capital label.** `text-[11px] uppercase tracking-wide text-base-content/40` is the
   heading over a group of things, six times over. The scale has no entry for it: `CAPTION` is a
   size and a fade, and it is the uppercase and the letter spacing that make this read as a heading
   rather than as a note. **A sixth name — an eyebrow, or a section label — is the gap.**
3. **A fixed-width value.** `font-mono text-[11px] text-base-content/50` — an id, a path, a commit.
   `TextStyle` names five sizes and no faces, so asking for the size still leaves `font-mono` beside
   it, which is where a second size creeps back in. **The gap is a way to say "this size, in the
   fixed-width face".**
4. **A coloured state in words.** `text-[11px] text-warning`, `text-[11px] text-error`. `Emphasis`
   is explicitly a fade of the inherited colour rather than a colour, which is right, and it means a
   one-line warning still names its own colour class. `Alert` covers the boxed case; nothing covers
   the single line.

A fifth thing is not a gap but is worth saying: **`Emphasis` fades the whole element, not its text.**
`text-base-content/50` on a row holding an icon and a nested full-strength label fades only the
text; `opacity-70` fades the icon too. Every compound row here was therefore left alone. A sweep
that ignored this would have dimmed a great many icons, and nothing would have failed.

### 30.3 Layers, and the one number left by hand

Three hand-picked stacking numbers existed. The two completion panels above the chat composer
carried `z-20` and are now on `Layer.DROPDOWN`, which is what they are — a menu opened from a
control. The replay bar in the run graph carried `z-10` and is now on `Layer.STICKY`. The command
palette's `z-50` is untouched: it is the author's own file, and §18.9 already records that making
the palette a real dialog is a change worth deciding on its own.

### 30.4 `SvgCanvas.fit` is not fixed upstream — keep the workaround

`fit` still measures its own `offsetWidth` and returns silently when it is zero, so a diagram fitted
while its pane is hidden is still drawn at no scale at all. The show-then-fit ordering in
`RequirementsStage` and the note on `BrdView` both stay. **Also still open upstream, and confirmed:
there is no confirmation dialog above a plain one.** The local `ConfirmDialog` stays; a second one
was not built.

### 30.5 Still to do here, deliberately not done in this session

`setInnerHTML("")` is used to empty a container in **15 places across 8 files**, and 0.8.0 makes that
the wrong way — `removeAll()` is what now tells everything leaving that it has left. It cannot be
swept: several of those containers mix components added with `add` and spans appended straight to
the element with `appendChild`, and `removeAll` walks past the second kind, so a blind swap leaves
duplicated headings on every redraw. `SetupView` was converted properly — every child is a component
now — because it was being rewritten anyway. The other seven files need the same treatment, one at a
time, each with a screenshot.

### 30.6 The wizard: what it is, and the two decisions

`OnboardingWizard`, mounted by `SetupStage`, opened from a card at the top of `SetupView` and from a
button in the Setup bar. Four screens: what SwarmCoder does · the four words · where to start · the
route. It makes **no server calls** and does **no work**: every route ends at a button that opens the
surface where the work happens. It reads `ConsoleReadiness` to tick off the steps already done and
never recomputes anything it says (UX v3 §5 rule 2).

**§28.1 — a starting point, not a project type — is honoured structurally, not by a note.** The
chosen route lives in a field on the dialog and is written nowhere else. There is no persisted mode,
nothing downstream can read the choice, and every route page carries a Back button to the other two.
The browser test walks route one, goes back, walks route three, crosses from route three into route
two, and goes back again — because a claim that every route reaches every other is worth a walk
rather than an assertion.

**The route that does not exist is shown, not hidden.** It is on the card list with a "Not available
yet" mark, and opening it says so in its own first sentence, then says what it will do, then names
the two decisions already made about it (evidence only; never a percentage), then says which of the
other two to use instead. Hiding it was the alternative and it is worse: the author asked for three
ways in, and a screen showing two cannot tell him whether the third is missing, hidden, or somewhere
he has not found yet. It is the same argument `SetupView` was built on — absence is explained, never
merely tidy (UX v3 §5 rule 1).

**Stale operator-facing text on the setup panel was fixed while it was open.** It said "the project's
BRD" (an internal name), "the Backlog" (a screen renamed to Pipeline), "run-dependent views"
(nothing a person can look for), "Workers branch from HEAD into their own worktrees" and "only runs
are blocked". §26.4 held the startup text to the plain-English rule and this panel was missed. All of
it is rewritten, and the third prerequisite is now called "Somewhere safe to build" rather than
"Somewhere to run".

### 30.7 What was run

- **`ConsoleOnboardingBrowserTest`** (sc-console, `@RunsWhen(Need.CHROMIUM)`) — new. A real cold
  console with no project at all, entered the way a newcomer enters it: the header's red dot, the
  setup panel, the card. It walks every screen of every route, asserts `OperatorWords` on each one,
  asserts nothing overflows the dialog at 1600, 1280 and 960, and finishes by pressing the button at
  the end of a route and proving the guide closed behind it.
- One defect the test found in itself, worth recording because it will recur: an unquoted Playwright
  `text=` selector is a case-insensitive **substring**, so `text=Open Requirements` matched the
  sentence "Open Requirements and add the file" two steps above the button, clicked the prose, and
  reported a dialog that had refused to close. Quote the selector whenever the button's words also
  appear in prose on the same screen.
- Regressions over the changed screens: the console browser suite.

Looked at, not deduced: `console-onboarding-what-1600/1280/960.png`,
`console-onboarding-words-1600/1280/960.png`, `console-onboarding-where-1600/1280/960.png`,
`console-onboarding-route-document-*.png`, `console-onboarding-route-change-*.png`,
`console-onboarding-route-existing-*.png`, and `console-setup-guide-card.png`.

## 31. The guide nobody could find (2026-08-28)

§30.6 built the onboarding guide, screenshotted it and gave it a browser test that walks every
screen of every route. It passes. The first person other than its author to use the Console still
never saw it: *"I create a new project, no sign of a wizard."*

### 31.1 Why a passing test proved nothing

Both ways into the guide were on the Setup panel — a card at the top of `SetupView` and a button in
the Setup bar. **Nobody goes to Setup.** `Stage.SETUP` is declared with `visible=false` so it has no
tab, and `Stages.recommended` deliberately never returns it: §3.3 demoted Setup to the health mark
in the header on purpose, because landing a first-time operator in a configuration panel put setup
in front of everybody who had come to do something else. That decision is right and stands.

So the guide was wired to the one surface the product's own navigation says nobody is sent to, and
the test agreed with it: `ConsoleOnboardingBrowserTest` starts by clicking the health mark, which
is precisely the thing a newcomer does not know is there. **A test that starts where nobody starts
cannot find this class of fault.** Third time today that something built, tested and photographed
turned out to be unreachable on the path a real person takes.

### 31.2 The fix: the moment, not another card

The guide now opens the instant a project is created — `ProjectMenu.openCreateDialog`, in the
branch where `control.createProject` came back without an error, after `onProjectSwitched.run()`.
That is the one instant at which somebody has committed to the tool and has not yet been told a
single thing about it, and it needs nothing found and nothing clicked.

Three things about how it is wired:

- **`OnboardingWizard` is reused, not forked, and it needed no change to be launched from
  elsewhere.** It is a package-private `Div` wrapping its own dialog, with a package-private
  `open()`, and it reads everything it says from the readiness signal. Nothing in it assumed Setup.
- **Built fresh each time and the previous one disposed.** It binds effects to a process-wide
  signal and `ProjectMenu.mount` empties the host under it, so one instance kept across mounts
  would leave an effect redrawing into a subtree that had been taken out of the page.
- **No "seen it" marker, because the trigger cannot fire twice.** It hangs off one explicit action
  and only that one. Switching to a project you already have runs the same `onProjectSwitched` and
  does NOT open it; a page reload does not either, because nothing on a render path opens it. Both
  are walked in the test rather than argued.

The Setup card and the "How SwarmCoder works" button stay, unchanged, as the deliberate way back in.

### 31.3 One line of operator text fixed while it was open

The guide's own subtitle said "you can close it at any point and open it again from Setup". Setup
is not a tab and not a word on any screen — it is the small `setup` / `ready` label beside the
coloured mark in the top bar. Naming a panel a person cannot point at is the same as saying nothing
(UX v3 §5 rule 3), so it now says where on the screen to click instead of what the panel is called.

### 31.4 What was run

- **`ConsoleNewProjectGuideBrowserTest`** (sc-console, `@RunsWhen(Need.CHROMIUM)`) — new. Walks the
  path a person walks and nothing else: open the list of projects, press the plus, type a folder,
  press Create. Asserts the guide arrives with nothing else pressed, that it stands alone (one
  modal, not three stacked), `OperatorWords` on it, that it fits at 1600/1280/960, that closing it
  closes it, that loading the window again does not start it over, and that switching to a project
  that already exists does not open it.
- Regressions over the two files changed: **`ConsoleDialogsBrowserTest`** (owns the new-project
  form in `ProjectMenu`), **`ConsoleOnboardingBrowserTest`** (owns the guide) and
  **`NoMojibakeTest`**. All pass.
- One defect the test found in itself, worth recording because it will recur: the guide's close
  control is `Button(icon, "Close")`, and since ZeroZ Stack 0.8.0 that constructor puts the label
  in `aria-label` rather than in the button's text. `text="Close"` therefore waits for ever on a
  button that is plainly on the screen. Click an icon button by its `aria-label`.

Looked at, not deduced: `console-new-project-guide-1600/1280/960.png`,
`console-new-project-guide-closed.png` and `console-new-project-guide-reloaded.png`.

---

## 32. An outside agent can now ask what a run is doing (2026-08-28)

**This partially reverses §9 item S7, on the condition S7 itself set.** That item deferred MCP —
"revisit only when a concrete EXTERNAL consumer appears (e.g. driving SwarmCoder from Claude Code,
an IDE, or CI). Build it then as a thin MCP-only adapter over the existing `ConsoleContext` bridge /
`ControlService`+`ObserverService` surfaces — do not build it speculatively, and do not build the
ACP half at all."

The consumer appeared tonight. A nine-story run went wrong and the author asked *"do you have any
insights into what it is doing?"* — and the only way to answer was to read his log file by hand over
his shoulder. What the answer turned out to be is the design brief for the whole thing: ten workers
had each finished without producing any change after N turns, each with the last command it ran on
the line above. **That is one question, and it took a human scrolling a file to answer it.**

Every condition S7 set is honoured. MCP only. **ACP is not built and is still dropped** — the
Console remains the complete operator surface, and nothing here changes that. The adapter owns no
data: it calls `ObserverService`, `GraphService` and `ControlService` and nothing else, so an
outside agent and the Console cannot disagree about what is true. `sc-server`, whose emptiness §9
recorded as *conformant, not a gap*, is now the right home for it and is where it lives.

### 32.1 The tools, in the order they earn their place

Sixteen, and the first two are what tonight needed:

| Tool | What it answers |
|---|---|
| `swarm_status` | What is happening right now: selected project, every unfinished run, how long each has run, **whether anything is actually driving it**, what it cost, and every live worker with the step it is on |
| `run_diagnosis` | Why one run stopped or went wrong: every worker's verdict in plain words, a kill-reason tally, and each worker's last few steps |
| `run_detail` | The whole shape of one run — requirements, task DAG, every candidate with judge score, compiled and verified |
| `list_runs` | Every run in the project, newest first |
| `session_events` | One worker's steps, paged, oldest first, optionally filtered by kind |
| `event_payload` | The full text of one step, a window at a time |
| `session_prompt` | The exact instructions one worker was given |
| `blob_text` | The full text behind a `blob_ref`, a window at a time |
| `run_diff` | The code a run actually produced |
| `pending_decisions` | Every question waiting for an answer |
| `search_history` | Past sessions whose transcripts match a phrase |
| `insights` | Totals: runs, candidates, survival by model family, kill-reason counts |
| `list_projects` | Every project, and which one is selected |
| `start_run` | **Changes something.** Starts a build; returns the run id |
| `decide_run` | **Changes something.** Approve (merges) or reject (stops) a parked run |
| `answer_decision` | **Changes something.** Records an answer to one question |

`mcpApi.readOnly: true` drops the last three entirely — they are not offered, not merely refused.

**What it cannot do, said out loud rather than half-worked:** it cannot pause or resume a run,
because nothing in the engine exposes that; it cannot edit requirements, the backlog, settings or
guidelines; it cannot upload documents; it cannot switch projects, so it reads whichever project the
Console has selected and no other; and it does not stream — every tool is a question and an answer.

### 32.2 Why the run's HEARTBEAT is the headline, not its state

`swarm_status` leads with whether anything is driving each run, because the state alone is a lie of
omission: a run parked at `EXECUTING` with a thread on it and one whose process died look identical.
`RunSummaryDto.heartbeatAtMillis` already existed for exactly this (a story sat reading "building
now" for fifteen hours), and the answer says it in words — *"Nothing has touched this run for 40m.
Its state still says EXECUTING, but that is only the last thing it wrote down."*

`run_diagnosis` translates the same way throughout. A `KillReason` name is not an answer to a
person: `NO_ACCEPTANCE_EVIDENCE` comes back as "it compiled and broke nothing, but no test was
actually run, so there is no evidence it did the job." And **tonight's case has no kill reason at
all** — `WorkerLoop` returns `FAILED` with a null reason when the diff is empty — so that case is
named explicitly rather than left as a blank column: *"ran to the end and changed no code at all —
its diff was empty."*

### 32.3 The 4 MB lesson (§18), applied before it bit twice

An MCP reply carrying a ten-worker run's whole trace has exactly the shape that closed the console's
socket and blanked the screen. Four rules, and every one of them says so in the reply:

- **Rows are paged.** 200 max per listing; `session_events` returns `nextFromSeq`.
- **Payloads are snippets.** 400 characters, with the `blobRef` to fetch the rest — the same
  `payloadRef` discipline the DTOs already use.
- **Long texts are windows.** `blob_text`, `event_payload`, `session_prompt` and `run_diff` return
  20 000 characters with `totalChars` and `nextOffset`, and a note beginning **"CUT."**
- A last-resort clamp at 60 000 characters per reply, whose cut text names the narrower call to
  make. It is meant never to fire; the paging above is what keeps replies small.

The per-candidate temperature scatter in `insights` is deliberately left out: one line per candidate
ever run belongs on a chart, not in a tool reply.

### 32.4 Off by default, loopback only, and no way to say otherwise

Config block `mcpApi` (**not** `mcpServers`, which is the opposite direction — servers SwarmCoder
calls out to, such as Context7):

```yaml
mcpApi:
  enabled: true      # absent or false = no port is opened
  port: 8931
  readOnly: false    # true = the three verbs that change something are not offered
```

Then `claude mcp add --transport sse swarmcoder http://127.0.0.1:8931/sse`.

**There is no host setting, and that is the design.** The transport binds
`InetAddress.getLoopbackAddress()` and nothing else can be asked for. OBSERVABILITY_DESIGN's own
security note says auth is out of scope until the observer leaves the workstation — so it does not
leave the workstation. A port that opens itself changes what this product exposes, which has to be
the operator's decision, made once, in a file. The starter settings file explains it in plain words
and leaves every line commented out.

### 32.5 The transport is hand-written, and why it had to be

The SDK at `mcp.sdk.version` 0.7.0 (still resolves; sources checked) ships two server transports and
neither fits. `StdioServerTransport` requires the client to have LAUNCHED the process — but the
orchestrator is already running and holding a store nobody else may open, which is the entire reason
an outside agent needs a way in. `HttpServletSseServerTransport` requires a servlet container, and
dragging one into the orchestrator to serve two endpoints is not a thin adapter. So
`LoopbackSseTransport` serves the 2024-11-05 HTTP+SSE transport on `com.sun.net.httpserver`, which
`DecisionQueueApi` in this same module already uses.

Two things it does that are worth knowing before anyone touches it:

- **The POST is answered `202 Accepted` and the reply travels back down the client's SSE stream.**
  The SDK's own servlet transport writes the reply into the POST body instead. The reference clients
  ignore that body, so a server built the SDK's way passes every unit test and then hangs on the
  first request a real client makes. This is why the end-to-end test speaks the protocol over a real
  socket rather than mocking it.
- **Replies are routed by JSON-RPC id, not broadcast.** `DefaultMcpSession` hands the transport a
  response with no idea which client asked (its `connect` handler is a `doOnNext` pass-through that
  re-emits the REQUEST and delivers the answer through `sendMessage`). The transport therefore notes
  `request id -> stream` on the way in. Without it, two connected clients each receive the other's
  answers. Notifications, which nobody asked for, still go to everyone.

One SSE stream costs one thread, because a `HttpExchange` closes the moment its handler returns.
Bounded at 8 simultaneous streams, refused with a sentence rather than dropped. That is affordable
for a single-operator tool and would not be for a fleet.

### 32.6 What was run

- **`SwarmMcpToolsTest`** (sc-server, new, 12 tests) — the night that caused this, reconstructed:
  ten candidates `FAILED` with no kill reason and thirty turns each, and the assertion is that the
  summary says *"10 worker(s) ran to the end and changed no code at all"* and that each worker's
  last command is in the same reply. Also: a live run's status sentence; a run with a 40-minute-old
  heartbeat being called stopped rather than building; kill reasons translated out of their enum
  names; an unknown run refused in words; a 60 000-character blob coming back as a window that says
  "CUT." and where to ask for the rest; a 400-event session capped to a page with `nextFromSeq`; a
  50 000-character payload riding along as a 400-character snippet with its `blobRef`; the exact
  tool list; `readOnly` removing all three verbs; and `DOCS` refused as a run kind.
- **`SwarmMcpServerTest`** (sc-server, new, 2 tests) — a real MCP client over a real socket:
  `initialize`, `tools/list`, `tools/call swarm_status`, each reply asserted to arrive on the event
  stream after a `202`; the bound address asserted to be a loopback address; and a POST with no open
  stream refused 404 with a reason.
- **`McpApiConfigTest`** (sc-app, new, 5 tests) — the door is shut in a settings file that says
  nothing, and shut for an empty `mcpApi:` block; the starter file explains it and leaves every line
  commented out.
- **`FirstRunColdStartTest`** (sc-app, existing) — regression over the two files this touched in
  sc-app's config: `SwarmConfig` gained a component and `ConfigLoader.starterConfigText()` gained a
  paragraph. Passes.
- Whole reactor compiles clean.

Not run, and not claimed: nothing here has been driven against a live SwarmCoder process with a real
run in it. The end-to-end test proves the protocol and the tool tests prove the wording and the
caps, but the first real connection from Claude Code to a running orchestrator is still ahead.

---

## 33. Stories that wait for each other, and a night that runs without anybody

**2026-08-28.** Nine stories were planned from one requirements document and started together, to be
left running overnight. Story 1 was building the domain model. Stories 2-9 each independently
invented their own version of a domain model that did not exist yet. The whole night was wasted.

Three separate things were missing, and none of them fixes the failure alone.

### 33.1 A story could not say what it came after

`Story` had no dependency field. It had `order`, a rank inside an iteration, which no scheduler ever
read. Nothing in the system could express "this one comes after that one", so nothing could hold a
story back.

**Now:** `Story.dependsOnStoryIds` — the stories this one builds on. A story does not start while any
of them is unaccepted; `BacklogServiceImpl.startSession` refuses and says which story is in the way,
what it is called, and what is happening to it. `StoryGraph` (sc-domain) does the scheduling and the
validation: it rejects a cycle, an edge to a story the project does not have, an edge to a dropped
story, and a story waiting for itself — the same three questions `TaskGraphValidator` asks of a plan,
for the same reason.

**One scheduler, not two.** `SwarmEngineImpl.topologicalWaves` was the only Kahn levelling in the
product and it worked on `Task`. The algorithm moved verbatim to `Waves` in sc-domain and both
callers now share it. It had to move down rather than be called across, because sc-console owns the
backlog and may not depend on sc-swarm. The "cycle slipped through, flush the rest into one wave
rather than hang" behaviour is preserved as `Waves.Result.stalled()`.

**The planner declares it.** `BacklogPlanning`'s proposal prompt now demands `dependsOn`, naming
other stories in the same reply by their exact title or existing ones by key. It is rendered into the
review block as `Comes after: <title>` — a title, never a code, because the operator reads that list.
Apply runs in two passes: create everything, then link, because half the stories being depended on do
not have ids until the first pass has run. A reference that resolves to nothing is dropped and
reported, never guessed at: a missing edge fails visibly, an invented edge waits for ever.

**Overruling stays possible.** `startSessionAnyway` and its own dialog on the board, which names what
is in the way and says the workers will only see today's code. It is a different button from "Build
this story", never the default, and nothing automatic ever calls it.

### 33.2 Nothing ever merged anything into master

This is the one that made the first fix insufficient on its own. `FinalIntegrator` created
`swarm/integration/<runId>`, merged that run's task winners into it, and stopped. **No code path in
the product merged anything into the branch the operator works on** — accepting a story was a state
change in a database. Every worker worktree is cut from that branch, so a story could not see an
earlier story's work no matter how politely it waited, and the operator's morning job was to find and
merge a pile of integration branches by hand.

**Now:** accepting a story — by a person, or automatically overnight — lands its code on the
project's delivery branch, and the acceptance is abandoned when it cannot. `StoryDelivery`
(sc-workflow) follows `FinalIntegrator`'s own precedent one level up rather than inventing a weaker
merge path:

- **Order** comes free. A story is only accepted once everything it builds on is accepted, and
  acceptance is what lands code — so story merges happen in dependency order by construction.
- **The merge happens in a throwaway worktree** cut from the current branch tip. Nothing the operator
  can see moves until the result is verified.
- **A real git merge** through the CLI, so a configured merge driver applies (JGit cannot invoke one).
- **Full verification of the combined tree after the merge**, through the project's own contract read
  from the trusted root. Two stories can each be green alone and red together, and this is the only
  place that shows up.
- **Red or conflicted stops.** The worktree is thrown away, the branch never moved, the story stays
  in REVIEW, and the reason comes back in words. Overnight, that story is left for the morning and
  the queue carries on with whatever does not depend on it.

Only then is the branch fast-forwarded onto the verified merge commit — arithmetic, not a second
merge. `GitService.fastForwardBranchTo` refuses on a detached head, on the wrong branch, on an
unclean working tree, and on anything that is not a clean fast-forward. This is the only operation
that touches the operator's own checkout and it does nothing clever: a machine overwriting somebody's
unsaved work while they slept would be worse than any scheduling failure it prevented.

**Run base pinning.** `Run.baseRef` / `Run.baseCommit` record the branch a run builds on and the exact
commit it is cut from, resolved once at start. This is not predecessor tracking — it is the same HEAD
as before, resolved once instead of re-read. It matters now that acceptance moves the delivery
branch: with a live `HEAD`, one story being accepted would shift the ground under a build still
going, and the candidates of one task would be built on a different tree from the candidates of the
next. `WorkerLoop` and `FinalIntegrator` both use `run.startPoint()`.

### 33.3 Delivery required a person who was asleep

Section 11 R10 — *definition of done is human* — is why every story stopped at REVIEW. The reasoning
behind it has not stopped being true, and none of it is discarded here.

**Now:** `overnight.enabled` in the config file, which until today was a setting that warned at
startup that it did nothing. Off by default, and it stays off by default. While it is on,
`UnattendedPilot` does two things on a slow timer and nothing else: it accepts a story the machine has
already proved finished, and it starts the next story whose predecessors are all delivered
(`overnight.maxConcurrentStories`, default 1 — two stories building at once are each cut from the
branch as it was before either started, so neither can see the other).

**Auto-acceptance is mechanical and reads evidence that already exists.** A story only reaches REVIEW
when `CriterionEvidence.allDelivered` is true, and section 17 already established that PASSED needs a
positive match against a test id the runner reported as having run and passed — a check whose test did
not run is UNKNOWN, never PASSED, and one UNKNOWN sends the story to BLOCKED instead. The bar is not
lowered here; it is read. `UnattendedAcceptance` then refuses four cases outright:

- **A story that claims no requirement-check.** The freeform escape hatch reaches REVIEW on a vacuous
  truth — every check passed because there were none. Nothing was proved, so there is nothing to
  rubber-stamp. This is exactly the story a person must look at.
- **A story whose run stopped to ask something.** An open `Decision` is the definition of needing a
  person.
- **A story whose run has not finished, or finished without ever being planned.**
- **A story with no finished code recorded**, since there would be nothing to put on the branch.

Everything refused keeps its reason on the card (`Story.waitingReason`), so the morning does not begin
with nine identical "did it deliver what was asked?" cards and no way to tell which of them the
machine could have handled.

**The record stays honest.** `Story.acceptedBy` is `"human"` or `"unattended"`, the change journal
carries the same actor, and the board says on the card: *"Delivered, and accepted overnight without
anyone looking at it because every check it promised passed."* Same precedent as section 24
(machine-vs-human authored guidelines) and section 27 (proposed-vs-operator test references). One
acceptance implementation serves both, because a second one would be a second definition of done.

**When a story fails at 2am: skip it, carry on.** It is left stopped with its reason; nothing retries
it on its behalf, because a retry nobody read the failure for spends a night producing the same error
eight times. Stories that were waiting for it stay waiting and say so. The queue only goes quiet when
nothing startable is left.

### 33.4 The planner's ordering will sometimes be wrong, so the system corrects itself

The planner declares the order before any code exists, from requirements alone. A static graph decided
up front turns one wrong guess into a story that dies at three in the morning.

`MissingPieceDetector` (sc-workflow) reads facts, never opinions: no model is asked anything. Every
worker on a task compiles the same tree, so when **independent workers all fail on the same
unresolved name** — javac's `symbol: class Foo` and `package x does not exist` — that is a mechanical
fact already sitting in the verification reports. One worker agreeing with itself is not agreement
(two or more candidates required, unless there was only one).

What happens then is only what can be defended:

- **Another undelivered story's own wording names the missing thing** — its title, narrative,
  rationale, or the wording of the checks it promises, matched on whole words (`Bookmark` is not
  `Book`). That story is what this one was waiting for. The edge goes on
  `Story.discoveredDependsOnStoryIds`, kept apart from the declared ones because an inference is a
  weaker claim than a decision — the board says *"nobody planned this, a failed build showed it was
  needed"* — and the story returns to READY, where the scheduler holds it until the other lands. Same
  rule as section 11 R11: an agent never silently widens scope.
- **Nothing planned names it.** No edge is invented. The story stops and says which name it could not
  find, which is a failure somebody can act on in one reading.

Bounded at three re-queues (`Story.dependencyRetries`). A story that keeps failing for the same reason
after the thing it waited for has landed stops and says what it was waiting for. A loop that never
converges costs a night and teaches nobody anything.

### 33.5 What was run

New: **`StoryGraphTest`** (sc-domain, 10) — including the nine-story incident as a test, that REVIEW
is not delivered, and that a dropped or self-referential edge is reported but never freezes a story.
**`MissingPieceDetectorTest`** (sc-workflow, 7) — one worker is not agreement, a green run is not this
case, an already-delivered story is not a provider, a substring is not a match.
**`UnattendedRunningTest`** (sc-console, 10) — the four refusals, that an automatic acceptance is not
dressed up as a human one, that a refused delivery blocks the acceptance, that nothing happens while
the mode is off, and that one story failing does not stop an unrelated one.
**`DeliveryBranchTest`** (sc-git, 5) — finished work really reaches the operator's checkout, accepting
twice is not an error, uncommitted work stops it with nothing touched, and a non-fast-forward is
refused rather than forced.
**`ConsoleWaitingStoryBrowserTest`** (sc-console, `@RunsWhen(Need.CHROMIUM)`) — a real browser: the
waiting card names what is in the way in words, the free story still reads as free to go, and
overruling is a differently-named button behind a dialog that says what it is overruling. Looked at,
not deduced: `console-waiting-story.png`, `console-build-anyway-dialog.png`.

**One defect that test found, worth recording because it will recur.**
`ArtifactStore.copyOf(Story)` is a hand-written deep copy that rebuilds a story through its all-args
constructor, and the new fields are not in that constructor — so the copy dropped every one of them.
The copy is what the board is published from. Every unit test passed, the store was correct, and the
browser showed identical "nothing is building it yet" cards on both stories: the exact failure being
fixed, reintroduced by a copy method three modules away. **Anything added to `Story` outside its
constructor does not exist until it is added to `copyOf` as well.** The same trap sits in
`copyOf(Task)` and `copyOf(BrdRequirement)`, both of which already carry a comment about it.

Regressions over the changed files, all passing: `TopologicalWavesTest`, `GitServiceTest`,
`WorktreeSweeperTest`, `TaskGraphValidatorTest`, `StoryScopeTest`, `CriterionEvidenceTest`,
`FinalIntegratorTest`, `DeliveryHandbackTest`, `RunKindsTest`, `BacklogServiceTest`,
`GuidedFlowPlanningTest`, `ConsoleServicesTest`, `NoMojibakeTest`, `ConsoleBacklogBrowserTest`,
`FullProductDressRehearsalTest`.

**Not proved by a run of the real thing.** No overnight session against a live model has been driven
end to end. What is proved is every mechanical part of it in isolation, plus a real git repository for
the merge.

---

## 34. Ten workers, 180,000 tokens, no code — the documentation never reached them (2026-08-28)

A project was pointed at a reference folder holding a real framework whose documentation is
excellent: forty-three documents, about 90 KB, under `docs/`. Ten workers were dispatched on one
story. **Every one of them spent its entire turn budget unpacking the framework's jar and running
`javap` over the class files, and not one wrote a line of code.** From the run log:

```
Worker 6 finished without producing any change after 16 turns / 18515 tokens;
  final: exec "cd /tmp/uijar && javap -p com/zeroz4j/ui/component/Table.class"
Worker 2 ... javap -p com/zeroz4j/ui/component/Component.class
Worker 8 ... jar xf .../zerozstack-ui-components-0.8.0-SNAPSHOT.jar
```

Decompiling was the RATIONAL response to what they were actually given. They were given nothing.

### 34.1 Four faults, each on its own sufficient

**1. The documentation walk could not see documentation.** `KnowledgeCurator.conventions()` used
`Files.list` — non-recursive — and kept only files whose NAME began with "readme". A folder of
forty-three documents yielded one README and nothing else. Now: top-level markdown plus everything
under `docs/`, `doc/` and `documentation/` at any depth, kept in its own inventory with its own cap
so a repository full of Java cannot crowd it out.

**2. Every renderer dropped what did not fit, and nothing ever fit.** Each block was read at a cap
LARGER than the budget it was rendered into — a 6,000-character README into a 3,000-character
section, a 4,500-character source file into `lookup_api`'s 3,000. The first block therefore always
overflowed, and the code said *drop it*. Measured against the real folder before the fix:

| channel | budget | returned |
|---|---|---|
| `conventions(3_000)` | 3,000 | **26 chars** — the words "… (conventions truncated)" |
| `relevantSources(task, 3, 3_000)` | 3,000 | **0 chars** |
| `relevantSources("FormLayout", 2, 3_000)` | 3,000 | **0 chars** |

A block that does not fit is now TRIMMED to the room left. That one change unbricks both channels.

**3. `lookup_api` told them to go and read bytecode.** With every source silent it fell through to
its miss message: *"No documentation found … Inspect the code directly with read/exec."* The tool
issued the instruction the workers followed.

**4. The documentation server had never been connected.** The settings file names no `mcpServers`
section at all, so a hardcoded `http://localhost:3000/sse` was used, where nothing listens.
`Context7Client` is deliberately built to fail quietly, so nothing ever said so.

### 34.2 What the brief carries now, inside the same 16,000 characters

The framework's documentation is 90 KB and the brief is 16 KB. It cannot be pasted in, so the
brief carries a **map** and a **slice**, and the rest is fetched on demand:

- **The map** (1,500 chars) — every document by name with its title. Cheap, and it is what turns
  "I have no idea what this framework offers" into "there is a `UI_COMPONENTS.md` — ask for it".
- **The slice** (3,500 chars) — the markdown SECTIONS this task is about, keyword-scored the same
  way `relevantSources` scores files. Sections, not files: `## FormLayout` with its methods and
  example is a complete answer at a few hundred characters, so several fit where not one document
  would.
- Taken from the primer (5,000 to 3,000; it is a lossy LLM summary of the same material) and from
  the source dump. The whole brief now has a hard 16,000-character ceiling, which it never had.

**On demand** is `lookup_api`, which is not bound by the prefix budget: it answers from the
documentation sections first, then the pre-warmed index, then real sources, then Context7.

### 34.3 Two ranking defects found by measuring, not by reading

- **Substring matching.** A query for "Table" matched `@ClientWritable` — "Wri**table**" — and a
  troubleshooting note outranked the component reference. Terms now have to start a word or a
  camel-case hump, so "layout" still finds `FormLayout` but "table" no longer finds "writable".
- **Scoring the trimmed text.** Sections were trimmed to 3,500 characters BEFORE being scored, so
  a long component reference was cut before the entry being asked about and scored zero for its
  own subject. Sections are now scored whole and trimmed only when rendered.

### 34.4 The prefix stays byte-identical

The brief sits in the shared prompt prefix, and the economics of running N workers depend on the
server computing that prefill once. Every list is sorted by content — score, then address, then
heading — never by walk order; nothing reads the clock. `ReferenceDocsReachWorkersTest` asserts
that a second assembly, and a second `Librarian` with a cold walk, produce the same bytes.

The new "do not decompile, call `lookup_api` first" paragraph is static text in the shared workflow
rules, identical for every worker, so it does not move the hash.

### 34.5 The spiral, bounded but not forbidden

Sometimes taking a compiled library apart IS right: no source, no documentation, nothing else. It
is not forbidden. What is bounded is never stopping. After **eight** tool calls that only look —
`exec`, `read`, `lookup_api` — with nothing written, the worker is told once what documentation it
has, by name, and to start writing. A successful write resets the count.

Eight comes from the run: the ten workers died at 14–22 turns against a 30-turn cap and every one
was still investigating at turn eight, while a healthy worker writes after three to five tool
calls. It separates the two populations without touching the honest one.

The text is appended to the tool result that provoked it, so the conversation stays a valid
tool-call/tool-result sequence — the shape a small model's chat template handles most reliably —
and it is also emitted as a `NUDGE` trace event, which the console's transcript already renders in
warning colour. No UI change was needed.

### 34.6 The startup warning, and the key

Failing quietly per call is right; one dead lookup must not kill a run. **Failing quietly at
startup is not.** `EnvironmentChecks.reportKnowledgeSources` now says, once, when a project opens:
how many documents the reference folders hold (or that they hold none), and which of four states
the documentation server is in — answering, no key, key refused, or nothing there. Three broken
states, three different fixes, and an operator can tell them apart without reading code.

The built-in address is now Context7's hosted service, `https://mcp.context7.com/mcp`, which
replaces the `localhost:3000` default that pointed at nothing. It needs a bearer key.

**The key is read from the environment variable `CONTEXT7_API_KEY` and never from the settings
file.** That file is edited through the Console, read back onto a screen, logged and copied around;
`roles.*.apiKey` already sits in it in plaintext and that is the practice this deliberately does
not extend. The key is never logged, never echoed and never put in an error message — a refusal is
reported as a refusal and nothing more. It is read once, at startup, so a window that was already
open when the variable was set will not see it; the "no key" message says so.

Setting it up:

1. `setx /M CONTEXT7_API_KEY <key>` (or System, Environment Variables, Machine scope).
2. Restart SwarmCoder. Already-running processes cannot see a variable set after they started.
3. No settings change is needed — the hosted address is the default. To point somewhere else, add
   `mcpServers: [{ name: context7, url: <address>, enabled: true }]`.

Two things the pinned MCP SDK (0.7.0) cannot do: set a request header on its SSE transport, and
speak Streamable HTTP at all. Moving the SDK version would disturb `sc-server`, so the hosted path
is a direct JSON-RPC-over-HTTPS client confined to `Context7Client`; a local SSE server still goes
through the SDK.

**No worker-facing MCP tool was added, deliberately.** `lookup_api` already reaches Context7
through the Librarian, which fetches on the worker's behalf. A second path from the worker would be
two overlapping ways to ask one question, and a model that finds two will try both — which is the
turn-burning this whole entry is about. The workers' six tools are unchanged;
`WorkerScopeIsUnchangedTest` still pins that.

**Two live findings while wiring it.** The tool arguments were wrong — `fetchDocs` sent `library`
to a tool whose schema wants `libraryId`, and `resolveLibrary` sent `name` where it wants
`libraryName` — so every call failed schema validation and returned nothing, silently. And a bad
key still completes `initialize` with HTTP 200 and is only refused on a tool call, as a 200 whose
CONTENT says the key is invalid. A posture based on connecting alone reported a wrong key as
"ready", so `posture()` makes one real call and reads the answer.

**Local first, published second.** `lookup_api` asks the reference folders before Context7 and the
startup line says so. Context7 serves a library's published release; a reference folder is the
operator's live checkout, which can be days ahead. Stale documentation applied confidently is its
own failure mode.

### 34.7 What was measured

Live, against `qwen3.8-27b` at `http://192.168.0.10:8002/v1`, interleaved, both arms, seven
attempts each on the same task with the same reference folder. The only difference is the knowledge
channel: the old brief and old `lookup_api` message, versus the new ones.

| | reached into the jar | asked `lookup_api` |
|---|---|---|
| **before** | **10** | 11 |
| **after** | **0** | 46 |

Every reach into a jar, in every run, was in the "before" arm. Not one in "after". And what the
worker gets back is real: `lookup_api("VerticalLayout")` returns the framework's own code example,
`CardTitle` and `mainLayout.add(...)` included; `documentCount()` on that folder went from 0
usable to 43.

**What is NOT claimed: neither arm finished the file, and the reason is not documentation.** After
roughly seven turns this model starts emitting its tool calls as plain TEXT
(`{"tool_name":"exec",…}` in the message body) instead of as tool calls, and the loop stops on two
such turns in a row. Turning off the textual tool-call HISTORY that seems to provoke the imitation
is not available either: this model's chat template rejects native tool-call history outright
(HTTP 400, measured). **That is a separate and larger defect, and it is now the thing blocking
these workers.** It is recorded here rather than fixed because it belongs to the tool-calling path,
not to the knowledge path.

### 34.8 What was run

- **`ReferenceDocsReachWorkersTest`** (sc-knowledge) — new, 9 tests: the recursive walk, what
  counts as documentation, the budget bug in both channels, the map and the slice in the brief,
  byte-identical reassembly, `lookup_api` answering from the documentation, the miss message no
  longer sending anyone to a decompiler, whole-section scoring, headings inside code fences.
- **`InvestigationSpiralTest`** (sc-swarm) — new, 7 tests: the threshold and that it fires once,
  the steer's wording with and without documentation, a write resetting the count, the transcript
  drain, the hint read back out of the brief, and the shared prefix not diverging per worker.
- **`KnowledgeSourceReportTest`** (sc-app) — new, 4 tests: the exact operator words for each of the
  four documentation-server states, and that none of them can carry a credential.
- **`LiveReferenceDocsTest`** (sc-app, needs `-Dswarmcoder.live.baseUrl`) — new. The A/B above.
  Asserts what the change controls: the "before" arm reaches into the jar, the "after" arm never
  does and asks instead. It deliberately does not assert that code was written — see §34.7.
- Regressions over the files changed: **`LibrarianTest`**, **`WorkerToolboxTest`**,
  **`WorkerScopeIsUnchangedTest`**, **`SwarmDiversityTest`**, **`PromptBundleTest`**,
  **`GuidelinePromptWiringTest`**, **`FirstRunColdStartTest`**. All pass.

---

## 35. Two things merged today that had never met a real client (2026-08-29)

Both of these shipped this morning, both passed every test they had, and both were broken the
first time anything real touched them. They are recorded together because they failed the same
way: **the test spoke the same dialect as the code under test.**

### 35.1 The MCP server refused every connection

Claude Code could not connect to the server built in §32. The handshake came back:

```
-32603: Unrecognized field "elicitation" (class McpSchema$ClientCapabilities),
not marked as ignorable (3 known properties: "experimental", "roots", "sampling")
```

The pinned SDK is `io.modelcontextprotocol.sdk:mcp:0.7.0`. Its `ClientCapabilities` record knows
three fields; a current client announces a fourth, `elicitation`. Most records in that schema carry
`@JsonIgnoreProperties(ignoreUnknown = true)` — `ClientCapabilities` and `InitializeRequest` are
among the few that do not. With a default `ObjectMapper` Jackson treats the unknown field as a hard
error, the initialize handler in `McpAsyncServer` propagates it, and the client gets `-32603` on
the very first message. **Nothing is usable before `initialize` completes, so not one of the
sixteen tools was reachable.** Every test passed because every test sent exactly the three fields
the SDK expects.

**The fix is one line of mapper configuration**, on the `ObjectMapper` that
`LoopbackSseTransport` hands to the SDK:

```java
private final ObjectMapper json = JsonMapper.builder()
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .build();
```

That mapper is the one the SDK reads *everything* inbound with — `initialize`, `tools/call`,
`resources/read`, `prompts/get` — so the tolerance is uniform rather than patched per message.

**Why not bump the SDK.** It was investigated and rejected on two grounds.

1. **It fixes today's field and not the next one.** A client newer than the server it is talking to
   is the normal case for a protocol still moving, and MCP's own spec says an unrecognised field is
   to be ignored. Tolerant deserialization is therefore the *correct* behaviour, not a workaround;
   a version bump would leave the next added field to break the handshake in exactly this way.
2. **It is not a version bump, it is a rewrite.** `ServerMcpTransport`, the interface
   `LoopbackSseTransport` implements, does not exist after 0.7.0: 0.10.0 replaced it with
   `McpServerTransportProvider` and a session-per-transport model, and 1.x moved the classes into
   different artifacts again (`mcp-core`, `mcp-json-jackson2`). §32 already established that
   neither SDK transport is usable here — its stdio transport needs the client to have launched the
   process, its servlet transport needs a container — so the hand-written transport stays, and
   rewriting it lands on a module several just-merged features depend on.

**How it was proved, and how the proof was itself checked.** The lesson of §32 is that a unit test
speaking the SDK's own dialect proves nothing about a real client. So:

- `SwarmMcpServerTest` gained
  `aNewerClientAnnouncingCapabilitiesTheSdkNeverHeardOfStillGetsItsTools`, which sends over a real
  socket what a newer client actually sends — an unknown capability, an unknown field nested inside
  a known one, an unknown field beside the known ones, and a later protocol version — and then
  insists `tools/list` and `tools/call` still work, because a handshake that "succeeds" with
  nothing callable is the same outage. Reverting the mapper makes it fail with the same `-32603`.
- And, because a hand-written client can share the author's blind spot, **the fix was run against a
  genuinely independent client**: a throwaway two-JVM harness with the server on `mcp:0.7.0` and
  the client on `mcp-core:0.18.1` — a version whose `ClientCapabilities` really does have
  `elicitation`. Before the fix that client failed with the verbatim production error. After it:
  `INITIALIZE_OK server=swarmcoder protocol=2024-11-05`, sixteen tools listed, `swarm_status`
  called and answered. The harness is scratch, not committed; the committed test is the regression.

What is deliberately NOT relaxed: a body that is not a JSON-RPC message at all is still refused
with 400, asserted by `somethingThatIsNotAJsonRpcMessageIsStillRefused`.

### 35.2 Story dependencies were shredded on commas

The ordering feature from §33 ran once and reported:

> these stories were said to come after something that is not in the plan, so the ordering was left
> off them: "Book records with add" (named by S2), "edit" (named by S2), "remove" (named by S2),
> "validation" (named by S2), "and labelled forms" (named by S2)…

`dependsOn` carries a story **title**, and the prompt asks for the title *exactly as written*.
Titles contain commas. `BacklogPlanning.splitTitles` cut the value on every comma, so a correctly
named story became five fragments, none of which was the name of anything. **Every dependency edge
naming a title with a comma was silently dropped** — and the stories with long enumerating titles
are exactly the ones most likely to be depended on. The feature defeated itself on its first run.

**The representation changed, not the splitting.** `dependsOn` is now a **JSON array of strings**,
one whole title per entry, and the prompt says so and says why. The reply was already JSON; an
array has no delimiter to get wrong. A delimiter no title can contain was rejected as a second
thing to explain to a model that already gets this wrong, and it would not help replies already
written.

A plain string is still read, for replies written before the prompt changed and for a model that
ignores the schema — but it is **matched before it is cut**. `matchAgainstKnown` offers the text,
longest first, to the names that actually exist (titles proposed in the same reply, plus the keys
and titles of stories already in the backlog) and only looks for a comma in what matches nothing.
So `"S1,S3"` still yields two keys, while one title with four commas stays one title. Where nothing
matches, the text is kept **whole** rather than cut into pieces that mean nothing.

**`delivers` and `unblocks` are untouched.** They carry check ids and requirement handles —
`R7:C1,R7:C2`, `R3,R4` — which genuinely are comma-separated lists of tokens that can never contain
a comma. They keep using `split(String)`, and `BacklogServiceTest` still covers the key-list form.

**The failure message now names both halves.** It used to print only the text that failed to
resolve, which for a whole story title typed back by a model is precisely the string the reader
cannot place. It now adds the keys and titles that do exist (capped at twelve), so a near miss
reads as a near miss and an invented name reads as absent.

### 35.3 What was run

- **`SwarmMcpServerTest`** (sc-server) — 4 tests, two of them new: the newer-client handshake, and
  that a non-JSON-RPC body is still refused. Both were confirmed to fail without the fix.
- **`SwarmMcpToolsTest`** (sc-server) — 12 tests, regression over the changed transport.
- **`GuidedFlowPlanningTest`** (sc-console) — 7 tests, three of them new: a title with four commas
  surviving end to end, a legacy one-string value matched against real titles before any comma is
  used, and the improved message for a dependency on nothing. The first two were confirmed to fail
  under the old comma splitting, reproducing the operator's fragment list exactly.
- **`BacklogServiceTest`** (sc-console) — 9 tests, regression over `BacklogAuthoring`.
- **`StoryGraphTest`** (sc-domain) — 10 tests, regression over the ordering these edges feed.
- Full reactor `compile`: clean.

---

## 36. Four attempts, not eight — and the number now says where it came from (2026-08-29)

**The observation, and why it is right.** Up to eight workers were attacking each task and six or
seven of them were passing the build. The swarm exists as a FILTER — a weak model needs several
tries and the tests decide which one survives — and at that hit rate it is barely filtering. Four
attempts against two tasks does twice as much work for the same spend.

**What this does NOT change is the load.** Eight workers on one task and four workers on two tasks
are the same number of concurrent workers, so the model server and the workstation see the same
pressure. §14.3 point 3 records why that is the number to watch: at ten-way concurrency the wall
time was dominated by ten simultaneous local `mvn` runs on this machine, not by the model. It is a
reallocation, not an increase.

### 36.1 Three layers, most specific first

`SwarmSizing.resolve(story, project, global)` — story, then project, then the settings file, then a
built-in of four. Every layer is optional and unset means inherit, so a project that states nothing
behaves exactly like the global default and a story that states nothing behaves exactly like its
project.

| Layer | Lives in | Set from |
|---|---|---|
| Story | `Story.workersPerTask` (null = inherit) | the story's panel on the pipeline board — a **Tries** box, empty = inherit |
| Project | `.swarmcoder/project.yaml` → `swarm.nPerTask` | the per-project settings dialog, beside the role overrides |
| Global | `config.yaml` → `swarm.nPerTask` | the settings file (and whatever writes it) |

**The project layer follows the `roles` precedent deliberately.** Same file, same "absent means
inherit" rule, same dialog. It is not a second override mechanism, and `ProjectConfig` gained one
field rather than a parallel system.

**Only the worker count is per-project.** The other two concurrency numbers protect the
workstation, and `project.yaml` lives inside a repository the swarm writes to — the same reasoning
that makes `protectedPaths` additive: a file a worker could reach may ask for less, never for more
of the machine.

### 36.2 Where the resolution happens, and why in two places

The project and global layers are resolved in `ProjectContext` when a project's engine is built,
because that is the one place both files are in hand. The story layer is applied in
`GreenfieldWorkflow` at PLAN — the first moment in a run at which a story exists — by restamping
every planned task's `SwarmPolicy`. That covers both the planner's graph and the single-task
fallback graph, and it happens before the graph is persisted.

### 36.3 The number carries the reason with it

`SwarmPolicy` gained `nSource`: the plain-English phrase for why this task has this many workers
("story S3 asks for 6", "this project's own settings ask for 4", "the settings file asks for 4").
The run log prints the sentence, and the phrase is persisted on the task graph, so a finished run
still answers "why did this get four attempts?" months later with nobody having kept a console
window open.

**This is not decoration.** A value that silently comes from somewhere is exactly how
`splitAcrossFamilies` spent months reading as switched on while doing nothing at all. A third
overridable layer with no stated origin would have been the same defect, three times over.

### 36.4 Unset had to stay distinguishable from stated

`SwarmEngineConfig` is written back to `config.yaml` whenever the settings screen saves anything.
So the record's own accessors return **exactly what the file said, zero included**, and the defaults
live in four separate methods (`statedNPerTask`, `workersPerTaskOrDefault`, `taskGroupsAtOnce`,
`workerCeiling`). An accessor that quietly substituted a default would have baked that default into
the operator's file on the next unrelated save, turning "nobody stated a number" into "the settings
file states four" and making the run log credit the wrong layer.

The same rule applies downward: clearing a project's number writes **no** `swarm` block, and
clearing a story's number sets the field back to null. A written-down copy of an inherited value
reads as a decision somebody made.

### 36.5 The new defaults, and the arithmetic behind them

| Setting | Was | Now | Why |
|---|---|---|---|
| `swarm.nPerTask` | 8 in practice; 1 if the field was absent | **4** when the block is present. The whole block absent still means 1 | six or seven of eight were passing |
| `swarm.maxConcurrentTaskGroups` | unlimited when unset | **2** | 4 × 2 = 8 workers at once = today's load |
| `swarm.maxConcurrentWorkers` | did not exist | **8** | the ceiling that makes the product safe |

A **negative** value now asks for the old unlimited behaviour explicitly, for both of the last two.
A bare `0` no longer means unlimited, because "the operator left it out" and "the operator wants no
limit at all" were the same value and must not be.

### 36.6 What actually bounds the total, and what happens at the ceiling

Workers per task and tasks at once are both settable, and one of them is now settable per story and
per project — so their product is not controlled by any single setting. With several projects open
it is not even controlled by one run. `WorkerSlots` is therefore a **process-wide** semaphore that
every worker passes through, first wave and repair wave alike, set once at startup from
`swarm.maxConcurrentWorkers`.

**At the ceiling, workers WAIT — they are not dropped and the swarm is not shrunk.** Each holds a
virtual thread, which costs nothing while parked, and starts the moment a place frees. A task
asking for more workers than the machine allows runs them in batches and produces exactly the
candidates it was asked for, a little later. Shrinking a swarm to fit would have changed what the
run is evidence about, which is a worse failure than being slow.

Nothing can deadlock on it: a worker never waits for another worker, so every held place is held by
something making progress or about to fail. The ceiling is process-wide state on purpose — it is a
fact about this workstation, not about a project, and a per-project limit is precisely the thing
that cannot bound a product across projects. Unset means unlimited, which is what every test and
every embedded use gets, and is the behaviour that existed before the class.

Startup now prints the whole multiplication in one line: workers per task × tasks at once × builds
at once, and the ceiling it is capped at.

### 36.7 What was run

- **`SwarmSizingTest`** (sc-domain) — new, 8 tests: each layer winning in turn, the built-in four,
  zero and negatives meaning inherit rather than "no workers", the sentence read cold, the reason
  travelling on the policy, and a story's number surviving `equals`.
- **`SwarmSizingLayersTest`** (sc-app) — new, 9 tests: no `swarm:` block still never swarms, the
  project beating the settings file, an empty block getting four, the shipped defaults multiplying
  out to eight, unset staying distinguishable from stated, and unlimited having to be asked for.
- **`WorkerSlotsTest`** (sc-swarm) — new, 3 tests: the high-water mark never exceeding the ceiling
  under twelve concurrent workers, every one of the twelve still running, and a thrown worker
  handing its place back.
- **`BacklogServiceTest`** (sc-console) — one new test on the per-story setter: setting, clearing
  back to inherit, the change on the audit trail in words, and the typo guard.
- **`DependencyGraphStartupTest`** (sc-app) — extended: the project's number round-trips through
  `project.yaml`, and clearing it writes no `swarm` block at all.
- Regressions over the files changed: **`StoryGraphTest`**, **`BacklogWireRoundTripTest`**,
  **`SwarmDiversityTest`**, **`SwarmEngineFakeVllmTest`**, **`ModelQuirksConfigTest`**,
  **`FullProductDressRehearsalTest`**, **`WireContractTest`**, **`UnattendedRunningTest`**. All
  pass.
- **Not run:** anything against the live model server — the owner was using it for a real build.
  The console browser tests were not run either; the two screens touched (`ProjectMenu`,
  `PipelineBoard`) are **changed, unverified in a browser**.

---

## 37. The guide that told you where to go instead of taking you there (2026-08-29)

Five things, all reported by the author after using the Console on a real project. Every one is a
judgement about how it feels, so every one was fixed and then looked at.

### 37.1 "The wizard should walk the user through the whole process and not delegate to other screens"

The guide explained, and then handed over. Its route pages ended in a button — "Open Requirements",
"Open chat" — that closed the guide and left the person on another screen to work the rest out. The
journey it described (document → requirements → agree → stories → build) has five steps and the
guide performed none of them.

It performs four of them now, in its own window:

| step | what happens in the guide |
|---|---|
| add a document | the **real drop box** (`DocumentUpload.box()` — progress, cancel, size check) and a paste box, both in the dialog |
| have it read | presses `GuidedFlowService.start` and shows the server's own progress |
| agree what it found | lists the requirements nobody has agreed and calls `promoteRequirement` / `promoteAllDrafts` |
| turn them into work | opens the planner |
| build one | explains, then puts the operator on the board — see below |

Nothing about uploading, answering the analyst's questions or reviewing its proposals is written
twice. `IntakeWizard` and `PlanningWizard` already own those and are composed; the guide is the
spine that walks a person from one to the next and says why each one matters.

**The one step that still ends elsewhere hands over FORWARD.** A build is started from the card of
the story it is about, because the confirmation that money is about to be spent belongs beside the
thing being spent on (UX v3 §2.3). So the last step explains what is about to happen and opens the
board with the stories in front of the operator, rather than leaving them on a screen with no idea
what to do next.

### 37.2 A swap, not a stack — measured, in a picture

The first attempt opened the planner **over** the guide, on the reasoning that since ZeroZ Stack
0.8.0 a dialog is a real one in the browser's top layer and the last one opened is on top. The
screenshot said otherwise: the planner came out **behind** the guide, dimmed by the guide's own
backdrop and unusable.

So the guide steps aside — one window at a time — and comes back on the next step when the other
window closes, by its button, by Escape, or by a click outside. `IntakeWizard.onClosed` and
`PlanningWizard.onClosed` exist for that and for nothing else.

### 37.3 "The return-to-wizard link by clicking the 'ready' status in the top right is just ridiculous"

He is right. Every entry point was on the Setup panel, and Setup is reached only through the small
coloured health mark in the header — a pill reading "ready" or "setup". Nobody clicks a health
indicator looking for a guide.

There is a labelled control in the header's companion row now (`data-testid=open-guide`), reading
**Getting started**, and the empty requirements list — the first screen a new project shows anybody
— offers the same walk. Neither is a fifth destination: both open a dialog, and UX v3 §3's two
workspaces plus an overflow are untouched.

**One guide, not three.** It used to be built fresh in `SetupStage`, in `ProjectMenu` and again per
mount. `StageHost` owns the single instance and wires `Nav.openGuide`; the walk therefore survives
being closed, re-opened and moved between workspaces, and `StageHost.reset()` forgets it on a
project switch, because every step was about the old project.

### 37.4 "Add a new requirement document is only available on Graph"

It was in the diagram's own header. The List is the surface an operator lands on
(`REQUIREMENTS_AT_SCALE_DESIGN.md` §3.3), so the primary way of getting requirements in was not on
it at all — and the List's empty state told people to go and find the other tab.

Adding a document acts on the requirement set, not on how you are looking at it, so **Analyse
documents** is above the tabs now, in `RequirementsStage`. **Past versions** moved with it for the
same reason: a revision is a fact about the document, and pressing it switches to the diagram
because that is where a past revision is drawn. **New requirement** deliberately did not move —
each lens has its own editor and both already offer the button, which is the test being applied.

**The wizard had to move with the button.** `RequirementsStage` hides the lens you are not looking
at, and a dialog mounted inside a hidden subtree is not drawn at all — so the button worked from the
Graph and did nothing at all, silently, from the List. `IntakeWizard` is owned by the stage now.

### 37.5 "In the dark theme the colour difference between cards and background is hardly visible"

Measured before anything was touched. daisyUI 5's stock dark theme steps its three base colours by
about **two points of lightness**, and the Console painted a card with `bg-base-100` and the page it
sits on with `bg-base-100` too:

| element | measured colour |
|---|---|
| the page | `oklch(0.2533 0.016 252.42)` |
| a pipeline card | `oklch(0.2533 0.016 252.42)` — identical |
| its border | `oklch(0.2115 0.012 254.09)` — four points away |

A card was exactly the colour of the page behind it. No ramp could have separated them, because both
asked for the same token.

The three colours are given jobs now, in `index.html` — the Console's one stylesheet, applied to the
whole theme rather than typed into a screen:

- `base-200` — **the page**. The deepest surface; nothing is behind it. The `body` carries it.
- `base-100` — **anything that floats on it**: a card, a dialog, the header, a panel's toolbar.
- `base-300` — **every edge**. Borders, dividers, hover, a progress track. Now *lighter* than the
  surfaces, because a line on a dark screen reads by being lighter than what it separates.

Eight points of lightness between the page and a card, ten between a card and its border. Before and
after, same screen and same data: `look-pipeline-BEFORE-1600.png` / `look-pipeline-1600.png`, and
`look-requirements-BEFORE-1600.png` / `look-requirements-1600.png`.

**What the component library should provide and does not.** ZeroZ Stack 0.8.0 has `TextStyle` for
how big text is, `Emphasis` for how loud it is and `Layer` for what floats above what — and no name
at all for how high a **surface** sits. There is nothing to ask for, so every screen asks for a
colour instead, and a screen that wants "raised" and a screen that wants "the page" reach for the
same one. A `Surface` (elevation) vocabulary beside `TextStyle` and `Emphasis` would have made this
defect impossible to write.

### 37.6 "The wizard is not a book to read... It must explain while it is collecting data"

Screens 1 and 2 were two pages of prose — what SwarmCoder is, then the four words it uses — before
anything happened. The writing was good and most of it is kept; it was in the wrong shape.

The four-noun vocabulary (UX v3 §2.1) still lands, in use rather than as a glossary: **Document** on
the step that adds one, **Requirement** and **Check** while the ones just extracted are on screen,
**Story** on the step that plans them, **Build** on the step that starts one. What survives of the
old first page is two paragraphs above the three starting points.

The progress line stopped lying while it was there. It said "Step 1 of 4" before a route had been
picked, and the three routes are six, two and two steps long — so it reads "First: where to start"
until there is a real total to give.

### 37.7 Three traps this hit, all worth remembering

**An unquoted Playwright `text=` selector is a page-wide, case-insensitive SUBSTRING.** The guide is
mounted, closed, on every screen now, so its prose is in the document everywhere. Two tests broke on
it and neither failure looked like what it was:

- `text=Analyse requirement documents` began resolving to the intake dialog's own **title**, in a
  closed dialog earlier in the document, and waited for ever for it to become visible.
- `text=Keep it` matched "proves it works before you keep it" in the guide's first sentence.

Both are fixed by quoting and scoping — `dialog.modal.modal-open >> text="Keep it"` — and by
selecting on names rather than on prose wherever a name exists.

**Two copies of the same dialog need two names.** `IntakeWizard` and `PlanningWizard` take a test id
now, and the guide's copies are `guide-intake-wizard` and `guide-planning-wizard`.

**A flex child carrying `overflow-y-auto` is shrunk to nothing by a flex parent that overflows.**
The guide's body scrolls (its forward button was pushed off the bottom of the window at 960), and
the document list inside it — which had a scroll of its own — simply vanished, with no error and no
gap where it had been.

### 37.8 The camera

`ConsoleLookRunner` is not a test. It boots the Console on the requirements fixture, walks every
screen that changed, and photographs each at 1600, 1280 and 960 — including the dark theme **as it
was**, by restoring the old colours inside the same run, so a before and an after are the same
screen with the same data. Run it with:

```
mvn -o -pl sc-console -am test -Dtest=ConsoleLookRunner -Dswarmcoder.shots=true
```

## 38. Three faults from one restart, none of them what their own message said (2026-08-29)

Twenty workers died in the first second of a resumed run, all logged as `TIMEOUT`. The pipeline
board went blank because an enum read an empty string where a real constant belonged. A
never-populated docs index printed a full stack trace on every worker lookup. None of these were
what they looked like, and two of them were not even where the log line pointed.

### 38.1 A resumed run's branch name always already exists — `GitService.addOrResumeWorktree`

Candidate branches are deterministic: `swarm/<taskId>/<workerIndex>`. When a run is resumed after
its process was killed, the engine dispatches the exact same task at the exact same worker index —
the exact same branch name the dead attempt already created. `addWorktree` refuses an existing
branch outright (`git worktree add -b` exits 255), so on every resume every worker died before
doing anything at all, in under a second, for the entire duration of the run.

**The fix is archive-and-recreate, not force-overwrite.** The old branch may hold real work — a
candidate that finished and committed in the moment before the kill — so it is never simply
deleted. `GitService.addOrResumeWorktree(branchName, worktreePath, startPoint, runId)` checks
whether the branch already exists and, if so, archives it to `refs/swarm-archive/<runId>/...` (the
same retirement a losing candidate gets from `archiveCandidateBranch`) before creating a fresh one
with the same name. If the branch is still checked out in a worktree left over from before the
kill — git refuses to delete a checked-out branch — that worktree is detached first
(`worktree remove --force`) so the archive can proceed; the directory itself is left for
`WorktreeSweeper` to collect on its schedule, since deleting arbitrary folders is not this method's
job. If archiving itself fails for some other reason, it falls back to a plain force-delete so the
attempt can proceed rather than blocking every future resume of the same slot forever.

Safe to call twice: a run killed twice in a row on the same slot finds, on the second resume, the
branch the FIRST resume just recreated, and archives that one too. `WorkerLoop.run()` now calls
`addOrResumeWorktree` (passing the run's id) instead of `addWorktree`, and `sc-git`'s
`GitServiceTest` covers all three shapes against a real repository — branch survives with its
worktree already gone, branch survives with its worktree still checked out, and two resumes in a
row on the same slot — because a fresh-repo test cannot fail on any of these the way the log did,
which is exactly why the bug shipped in the first place.

### 38.2 `TIMEOUT` was never a timeout — `KillReason.WORKER_ERROR`

The git failure above took under a second, and was reported as `TIMEOUT`. It was never alone:
`WorkerLoop.run()`, `SwarmDispatcher.dispatch()` and `KoogAgentRuntime`'s session runner each had
the same shape of catch block — `EndpointOutage.isOutage(e) ? ENDPOINT_OUTAGE : TIMEOUT` — and
`TIMEOUT` was the default for "anything else went wrong," never for anything that actually waited
on a clock. `EndpointOutage.isOutage` already recognises every real elapsed-time transport timeout
by type (socket, HTTP, ktor's own timeout plugin), so everything that reached the `TIMEOUT` branch
was something else entirely: a git worktree failure, a sandbox that could not attach, a
misconfigured worker with no repo or no endpoint (`WorkerLoop`'s OWN early-return branch for that
case also said `TIMEOUT`), or an unexpected exception from the framework plumbing. A wrong reason
sends whoever reads it looking for slowness that was never there.

`KillReason` gained `WORKER_ERROR` — appended at the end, so no existing ordinal moves — and all
four sites now use it as their "not an outage" default instead of `TIMEOUT`. `TIMEOUT` itself is
kept for a genuine elapsed-time timeout, should the codebase ever grow one; nothing currently
produces it. `SwarmMcpTools.killExplained` gained the matching case so an agent asking about a run
sees the real explanation instead of the raw enum name falling through to `default -> reason`.

### 38.3 An empty string where an enum belonged — a same-day field addition, read by a stale reader

`StoryOrigin.valueOf("")` threw with `Enum class ... does not have the  constant` (two spaces — an
empty raw string, not a null). The generated wire code
(`sc-domain/target/generated-sources/annotations/com/swarmcoder/domain/Story_Serializer.java`,
built fresh from `Story.java` by the zeroz4j `@DataModel` annotation processor) is **purely
positional**: every field is written and read in declaration order with no per-field name or tag
to resync on — `BinarySerializer.writeString`/`readString` for a `String`-typed field is a bare
4-byte length prefix (`-1` for null) followed by the UTF-8 bytes, nothing else. Two same-day
commits — `da137f0` (dependency tracking) and `50dcb21` (per-story worker count) — added five new
`Story` fields (`dependsOnStoryIds`, `discoveredDependsOnStoryIds`, `dependencyRetries`,
`waitingReason`, `workersPerTask`), all declared immediately BEFORE `origin`, because that is
where they were added to the class (`Story.java`'s own javadoc explains why they could not go in
the 22-argument constructor).

**The exact mechanism, reconstructed from the generated serializer, not guessed:** a browser bundle
(`sc-console-ui`, compiled to JS by TeaVM against whatever `sc-domain` classes were on its
classpath the moment it was last built) rebuilt between those two commits knows about four of the
five new fields but not the fifth, `workersPerTask`. Reading a story whose `workersPerTask` is null
(the ordinary case — "inherit"), that field's wire encoding is a single `TAG_NULL` byte (`0x00`).
The stale reader, unaware this field exists at all, tries to read `origin` right after
`waitingReason` instead. `readString` consumes the next 4 bytes as a length: byte 1 is
`workersPerTask`'s stray `0x00`, and bytes 2–4 are the first three bytes of `origin`'s OWN
length-prefix — for any real enum name (length ≥ 1, so its 4-byte length is `0x0000000N`), those
three bytes are also `0x00`. The four bytes together read as the 4-byte integer `0`: not `-1`
(null), so `readString` returns `""`. `StoryOrigin.valueOf("")` throws, on the very field one slot
after the one that was actually missing.

**This is a build-consistency fault, not a logic bug**, and nothing in this repository can make a
positional, untagged wire format tolerate a schema the two sides disagree on — that is a property
of the zeroz4j-generated serializer, an external dependency. The actual fix for an incident like
this is rebuilding client and server together so they agree on the current `Story` shape again; a
partial/scoped Maven build that touches `sc-domain` without also rebuilding `sc-console-ui`'s
TeaVM output is exactly the trap `WireContractTest`'s own javadoc already names ("the client half
of the same guarantee is the TeaVM build of sc-console-ui... this test alone would not catch it").

**What was done in this repo:**
- `BacklogWireRoundTripTest` gained `storyRoundTripsTheFieldsAddedAfterTheAllArgsConstructor`,
  which the two existing Story round-trip tests never exercised — both use the pre-existing
  22-argument constructor and leave all five new fields at their Java default. It cannot reproduce
  a cross-build mismatch (writer and reader are always the same generated code in one JVM test) but
  it does pin that the five fields are genuinely on the wire, which nothing previously checked.
- `ArtifactStore.copyOf(Story)` — which silently dropped these same five fields once already today,
  for an unrelated reason (a hand-maintained deep copy that a real field-by-field enumeration, not
  a generated one, can and did forget a field for) — was checked for a recurrence of that specific
  class of fault (something else hand-enumerating `Story`'s fields and missing one). Nothing else
  does: `equals`/`hashCode` already cover all five, and the only other hand-written `Story`
  constructors (`AdHocStory`, `BacklogAuthoring`) build NEW stories rather than copying existing
  ones, so a field left at its default there is the correct, documented behaviour, not a bug.
- **One bad row can still blank the whole board**, and that is architectural, not a bug: `Backlog`
  (`sc-console-api`) is one `@DataModel` aggregate — iterations, stories, tasks and decisions
  together in a single object graph, published as a single `ValueSignal<Backlog>` — because the
  board needs all of them consistent with each other, which is the same reason the aggregate was
  built in the first place (see its own javadoc). A decode failure anywhere in that graph aborts
  the whole read; there is no framework-level mechanism in this repo to resume mid-object or skip
  one element of a `List<Story>` and keep going. What WAS achievable, and is now done:
  `PipelineBoard.requestBacklogPublish` retries once after a transient failure (the same window a
  `DisconnectedException` clears on its own — see §38.4) and, if it still fails, puts a visible,
  dismissible notice on the board saying the data may be out of date, instead of leaving the
  columns silently empty with the only record of the failure a browser console line nobody reads.
  `BrdView` has the identical unguarded catch block and was NOT touched — flagged as a follow-up
  rather than fixed here, to keep this change scoped to the screen the incident actually broke.

### 38.4 Confirmed harmless, not fixed: the reconnect race

Two `DisconnectedException`s ("`ObserverService#listRuns` refused: not connected to the server")
appeared 6 seconds apart, both within the first 10 seconds after this same restart, and never
again. This is a browser tab that was already open reconnecting to a freshly-restarted server — an
ordinary race between the page's reconnect attempt and the WebSocket/RMI channel finishing its
handshake — not, as first suspected, the browser disconnecting during shutdown; the timestamps sit
right after `Console live push connected`, not before a shutdown. It self-resolves and the log
message already says exactly what happened ("stale or empty data"). Nothing was changed.

### 38.5 The Lucene noise was never the history index

Every one of the 49 `IndexNotFoundException` stack traces in the incident log has
`DocsIndex.lookupApi(DocsIndex.java:51)` as its origin — zero come from `HistoryRag`, which
already catches this exact exception and returns quietly (`search()`'s
`catch (IndexNotFoundException e) { return hits; }`, proven by
`HistoryRagTest.emptyIndexAndBlankQueryAreSafe`). The task that first reported this pointed at "the
history index, before anything was written to it" — a plausible-sounding guess from the directory
name in the exception message, and wrong: `DocsIndex` was constructed with `~/.swarmcoder/rag`
itself (`DependencyGraph`), the same PARENT folder `HistoryRag` puts its own index into a
`history` subfolder of — which is exactly why the exception's file listing read `files: [history]`:
that folder is the only thing there, and it belongs to a different index entirely. `DocsIndex` has
never had anything written to it in this deployment (library-docs indexing is switched off with no
`CONTEXT7_API_KEY` configured — logged plainly at startup), so `lookupApi`'s
`DirectoryReader.open` throws on literally every call, and a worker asking for documentation is a
feature that shipped the same day (`561cd5c`, "Workers ask for the documentation instead of taking
the library apart") — so this is not a fresh-install condition that resolves once something is
indexed, it is a standing condition for as long as the key stays unset, logged with a full
Koog/reflection stack trace on every single occurrence.

Confirmed harmless in effect — `lookupApi` already caught the exception and returned
`Optional.empty()`, which is the correct answer to "is anything indexed" when nothing is — but not
harmless in cost: it buried whatever else was in the log behind an 18-line trace, repeatedly, for a
condition that will recur every time this deployment runs without a Context7 key. `DocsIndex`
gained a `catch (IndexNotFoundException e)` ahead of its generic catch, mirroring `HistoryRag`'s
already-correct handling, logged once at `debug` instead of a `warn`-with-stack-trace. Its
directory was also given its own subfolder (`rag/docs`, matching `HistoryRag`'s own `rag/history`
convention) so it no longer shares a Lucene directory with an unrelated index at all — a real,
separate defect uncovered while tracing this one, fixed alongside it because it was a one-line,
clearly-justified change and leaving two independent indexes pointed at the same folder is not a
state worth leaving in place once noticed. Covered by a new `DocsIndexTest`
(`lookupOnAnUnwrittenIndexIsEmptyNotAnException`, `indexedContentIsFoundAfterwards`).

## 39. The outage pause that never engaged, because the exception it needed was not on the list (2026-08-29)

The model server (SGLang) was restarted mid-run. SwarmCoder already knows how to survive that:
`OutagePause` holds the run, waits for the endpoint to come back, and retries the same work,
because — in its own javadoc — "an outage is not evidence". None of that happened. All 43
in-flight worker calls were recorded as genuine failures, each task went `BLOCKED`, and the
operator was shown a "needs you" question asking what they had decided about a network outage.

### 39.1 The classifier had no entry for the one exception a restart actually throws

Every one of the 43 failures arrived in exactly this shape:

```
ai.koog.prompt.executor.clients.LLMClientException: Error from client: OpenAILLMClient
Connection closed by peer
Caused by: org.apache.hc.core5.http.ConnectionClosedException: Connection closed by peer
```

`EndpointOutage.isOutage` decides outage-vs-refusal, and it does so in two passes: `isOutageType`
tests concrete types, and `isOutageTypeByName` matches simple class names for transports this
module deliberately does not depend on (Koog talks through ktor, ktor through Apache httpclient5,
and sc-inference must not take a dependency on either to name an exception).
`org.apache.hc.core5.http.ConnectionClosedException` appeared in neither pass, so `isOutage`
returned false, `OutagePause` never engaged, and the empty socket was read as evidence about the
work. The classifier defaults to "refusal" by design — that is the safe direction for a guess —
and this is what the safe direction costs when the gap is the single most common transport death
in the deployment.

**A detail worth correcting, because the first diagnosis got it wrong and the wrong version sounds
more convincing than the right one:** `ConnectionClosedException` was assumed to have escaped the
type checks because it extends `HttpException` rather than `IOException`. It does not. It extends
plain `java.io.IOException` (verified against `httpcore5-5.4.2.jar`). It escaped because
`isOutageType` names ten *specific* `IOException` subtypes and never plain `IOException` itself —
which is correct and must stay that way, since a JSON parse failure reading a perfectly good
response is also an `IOException`.

### 39.2 What was added, and what was deliberately left out

Three simple names went into `isOutageTypeByName`, all from Apache httpcore5, all extending plain
`IOException`, and none of them able to carry a model refusal or a bad request — a refusal is an
HTTP response, and by the time any of these is thrown there is no response:

- **`ConnectionClosedException`** — the server closed the connection mid-exchange. This is what a
  model-server restart looks like from the client side.
- **`RequestNotExecutedException`** — a subclass of the above; the connection closed before the
  request was sent, so the endpoint provably never saw it. Named separately because the matcher
  compares exact simple names and does not follow a class hierarchy.
- **`NoHttpResponseException`** — the server accepted the connection then dropped it without a
  status line: a box on its way down, or a half-closed pooled socket.

Two candidates were evaluated and NOT added:

- **`ConnectionRequestTimeoutException`** (the connection-pool lease timeout) needs no entry at all.
  It extends `java.io.InterruptedIOException`, which `isOutageType` has always matched. Adding it
  would have been a second, silently redundant spelling of a rule that already worked.
- **`HttpStreamResetException`** and **`StreamClosedException`** were rejected on the merits. An
  HTTP/2 `RST_STREAM` can be the server rejecting *this one request* while serving everything else,
  which is a refusal. Classifying a refusal as an outage is the expensive direction of the two: the
  run would wait for ever for an endpoint that is up and answering.

### 39.3 The test names the class it cannot import

`EndpointOutageTest` gained `aModelServerRestartIsAnOutage_notFortyThreeWorkerFailures`, which
reproduces the real nesting — a Koog-shaped wrapper carrying a `ConnectionClosedException` cause —
plus `theOtherApacheTransportDeathsAreOutagesToo` and the negative,
`anOrdinaryFailureIsStillNotAnOutage`.

sc-inference has no Apache httpcore5 on its classpath, main or test, and one was **not** added. The
cause is a locally declared test class named `ConnectionClosedException` extending `IOException`,
which is not a shortcut around the real type: it is precisely the case the by-name matcher exists
to handle, and a test importing the real class would prove *less*, because it would not prove the
by-name path works.

### 39.4 What was run

`EndpointOutageTest` (sc-inference, 12 tests) and `OutagePauseAndResumeTest` (sc-workflow,
3 tests). Both green.

---

## 40. Rules are judged by evidence and purpose; a unanimous break is a question about the rule (2026-10-01)

Harness runs 53 and 55 (HamBook on ZeroZ Stack 0.9.1) parked hours in with "every candidate broke
the stated rule". One rule could not be met ("all user-visible text lives in resource bundles" —
the stack has no browser-side catalog yet); one was too broad ("every type that crosses the wire is
a `@DataModel`" — the stack serializes enums natively). The rule check (the judge) read each rule's
literal words, and the workers who met the library's real behaviour had no way to say so. Three
changes, all library-agnostic; no up-front docs-based rule check was built (author decision).

1. **`dispute_rule(rule, reason, evidence)`** — a ninth worker tool (`WorkerToolbox`). Evidence is
   required; a file path gets the file's start attached. Recorded on the candidate
   (`CandidateSolution.ruleDisputes`, POJO `RuleDispute`) and shown to the judge next to the code.
   The judge may list a disputed rule under a new optional `disputed` field; it counts only when
   the worker really disputed that rule, and it is then not a violation (`JudgeScore.disputedRules`).
2. **A question about the rule, not a code failure** (`RuleQuestions`). When no candidate kept a
   HARD rule and either ≥2 workers disputed it (asked at once, no repair round) or every candidate
   broke it even after the rule-break repair round, ONE decision is raised:
   `DecisionKind.GUIDELINE_REVIEW` (dormant since §24.5, now used), with keep / reword / allow, a
   suggested rewording drawn from the disputes and the judge's sentences, and the evidence. The run
   parks behind it (`RunMustPark(brief, true)`). On resume the answer is applied through
   `RuleQuestions.Amendments` (over `ProjectRules.reword / allowException / keep`) and the task
   re-selects from its archived candidates — nothing is swarmed again; unanswered, it parks again.
   A reworded rule keeps its id and its first wording (`LearnedGuideline.statedWording`), so
   re-applying the technical document does not resurrect the old words. Unrecognised answers are
   KEEP. Unattended runs set `SwarmEngineImpl.setRulePolicy(REWORD_TO_ALLOW_THE_EVIDENCE)`: the
   rule is reworded on the spot, the decision is recorded RESOLVED with its evidence on the run, and
   the run continues. The live harness sets it; the product's autonomous mode does not yet.
3. **Purpose and strength.** The analyst now gives every CONSTRAINT a one-line `purpose` and a
   `strength` (hard only for MUST / forbidden / fixed stack; anything else or unclear is a
   preference). Stored on `LearnedGuideline` (`purpose`, `hard`), rendered by `ConstraintBrief`
   ("Why it exists", "A HARD rule" / "A preference") so workers and the judge both read them. The
   judge is told to flag a break only when the change causes the problem the rule exists to
   prevent. Its violations are classified in code (`JudgeClient.classify`, `RuleMatch`): only a
   break of a rule recorded HARD lands in `brokenRules` (selection tier, repair round, question);
   a preference break — and any violation not pinned to a rule — goes to `preferenceBreaks` and
   costs score only. With no rule objects wired in, every violation stays hard, as before.

**Deliberate consequences.** Every rule stored before this has `hard = false`, so on an existing
project nothing is hard until its technical document is applied again or an operator answers
"keep". A sibling that kept the rule means no question: the rule-keeper wins, disputes stay on
record. `GuidelineDto` and the Guidelines screen do not show purpose or strength yet.

## 41. A worker's commands run in a container, and its file tools stay inside its roots (2026-10-02)

Owner decision: **no command a model chooses may read or write the workstation's drives.** §13 ended
with "exec has no policy layer"; this closes it for workers.

**What was wrong.** The app already ran worker commands in a container by default
(`sandbox.enabled`, default true). The two live harnesses (`EndToEndLoopTest`, `BrownfieldLoopTest`)
build their engine by hand and never called `setSandbox`, so every worker command ran through
cmd.exe on the workstation as the operator. One worker ran `dir /s /b C:\*ZeroZDbNode*.java`.
And on Windows a contained worker was still told "your exec tool runs cmd.exe", because the prompt
asked the orchestrator's OS instead of asking where the commands run.

**What a worker's container can see** (`DockerSandboxManager.buildHostConfig`, one container per
candidate, removed when the candidate ends):

| Inside the container | What it is | Access |
|---|---|---|
| `/workspace` | the candidate's own checkout | read-write |
| `/workspace/.git` | the checkout's git pointer | read-only (the host runs git here; a rewritten pointer would aim it at another repository) |
| `/reference/<label>` | each reference root, by the label the knowledge brief uses | read-only |
| `/opt/m2-ro/repository` | `~/.m2/repository` only - not `settings.xml` beside it | read-only |
| `/opt/m2-cache` | the artifact cache volume | read-only |
| `/tmp`, `/home/sandbox` | the container's own memory | read-write, gone with it |

No network, non-root, every capability dropped, CPU, memory and process limits (already there).
New: each command is stopped with everything it started when its time is up (it used to be left
running); `MAVEN_ARGS` carries `-o` under `network: none`; containers carry the label
`swarmcoder.sandbox`, are removed by a shutdown hook, stop themselves after six hours
(`-Dswarmcoder.sandbox.maxLifetimeSeconds`), and stopped ones are removed at the next launch.

**Per candidate, not per run**, because two candidates of one task must not see or disturb each
other's checkout, and a per-run container would need every checkout mounted into it.

**The file tools.** `read`, `write_file`, `apply_diff` and the expert's and the roles' `read_file`
and `list_files` run in the orchestrator, on the workstation, whatever the shell is confined to.
`ConfinedPath` (sc-sandbox) is the one check: absolute paths, drive letters, `..` out of the root
and symbolic links out of it are refused with a sentence saying what is allowed. A worker's
absolute path outside its checkout used to be stripped and re-rooted; it is refused now.
`/workspace/...` and `/reference/<label>/...` are accepted, so the shell and the file tool agree.

**No Docker, no run.** The harnesses call `HarnessSandbox.required()` before anything is spent, and
the app's run blocker now also catches "Docker is up but the image is missing"
(`DockerSandboxManager.whyUnusable`).

**Still on the workstation - not closed by this change.** (Closed by §42, except where §42 says
otherwise.) Commands whose TEXT the product fixes
(from the operator's `verify.yaml`) but which EXECUTE model-written code still run on the host
through `LocalProcessExecTarget`: final integration (`FinalIntegrator`), story delivery
(`StoryDelivery`), wave integration (`WaveIntegrator`), every red check and the test author's
`compile_test` (`GreenfieldWorkflow`), and the baseline (`TargetRepository`). A model cannot choose
those commands, but a test it wrote runs inside them with the operator's privileges. Candidate
verification (`SwarmEngineImpl.verifyWorkspace`) does run in a container. Moving the rest is a
design decision, because final integration's browser checks need a browser that can reach the
application, which a network-less container only offers with the UI image.

**Also still open.** `sandbox.enabled: false` and `sandbox.required: false` remain as operator
switches in the app. An engine built with no sandbox at all (unit tests with a scripted model, and
the other live tests: `LiveM1Test`, `LiveEndToEndTest`, `LiveGuideline*Test`, `LiveReferenceDocsTest`,
`ConfigDrivenE2ETest`) still runs commands on the host.

## 42. The product's own builds of model-written code run in a container too, and an engine without one refuses (2026-10-02)

Owner decision, settling the three things §41 left open: **no code written or chosen by a model may
run with access to the PC's drives.**

**What moved into a container.** Everything below used to run through `LocalProcessExecTarget`,
with the operator's command text and a model's tests and build files underneath it:

| What | Where it is decided | Container |
|---|---|---|
| every red check, including the passes with stubs and after a repair | `GreenfieldWorkflow.buildTarget` | one per throwaway tree, reused by every command on it |
| the test author's `compile_test` | the same, on the draft's tree | one for all of a stage's drafts |
| the wave compile check | `WaveIntegrator.compiles` | one per check |
| final integration, after every merge | `FinalIntegrator.verifyIntegration` | one for the whole integration |
| story delivery | `StoryDelivery.verify` | one per delivery |
| the baseline suite, the toolchain check, the maintainer's-fix checks of the brownfield harness | `TargetRepository.runSuite` / `toolchain` | one per call |

`BuildBoxes` (sc-verify) is the one class that decides. It starts the container with the same closed
mount list as a worker's (§41's table: the tree read-write, its git pointer read-only, reference
roots read-only, the Maven repository read-only, no network, not root). `use(tree)` shares one
container per tree until `release(tree)`; the workflow calls `release` in the same place it removes
the tree (`removeTree`, which replaced every `gitService.removeWorktree` in the workflow), so the
container goes on failure too. `open(tree)` is the one-shot form, closed by try-with-resources.

**No container is a stop, not a skip.** The red check used to treat any exception as "an absent
instrument is not a verdict" and carry on. A missing container is now caught first and parks the
run with the reason, as do the wave compile, final integration and story delivery.

**Browser checks.** A served application is started inside the container and looked at by a browser
in the same container (`BrowserVerifier.runInside`, the checker script the UI image already
carried); screenshots come back over the exec channel. Final integration and story delivery start
their container from `swarmcoder-worker-ui:latest` (`-Dswarmcoder.sandbox.uiImage`) when the
contract has a `serve` browser check and the image is on the machine. When it is not, the check
reports that it could not run and names the `docker build` line. It never runs on the host.
A candidate's own verification still declines `serve` checks, as before: that is a decision about
cost (the UI image for every candidate), not about containment.

**Settings.** `sandbox.required: false` is read and ignored, with a warning. `sandbox.enabled: false`
stays as the operator's off-switch: the app then calls `HostExecution.allow(...)` by name, prints
a block saying model code runs on this PC, says so again per project, and the workflow says it on
the Console at every drive of a run.

**An engine with no container refuses.** `SandboxAttach` (worker commands, candidate verification),
`WorkerToolbox.exec` and `BuildBoxes` all refuse when there is no sandbox, unless `HostExecution`
(sc-domain) was switched on by name. There is no system property behind it. Scripted tests opt in
with `@ModelCodeOnThisPc` (sc-testsupport), on the class; the live tests use
`HarnessSandbox.required()`, now in sc-swarm's test jar.

**Still on the workstation.**

- (Done, 2026-10-02 later.) The compile probe when a project is registered (Console build-contract
  screen, `OnrampCli`, `TargetRepository.register` given boxes) runs in a container on a throwaway
  copy: `ProbeOnACopy` makes a detached git worktree of the checkout's HEAD under `~/.swarmcoder/wt`
  (a plain copy when there is no git history), builds it with `ContractProbe.probeCompile(...,
  BuildBoxes)` and removes it. The operator's folder is neither mounted nor written. Uncommitted
  changes are not in the copy, which is also what a candidate's worktree is cut from.
- (Done, 2026-10-02 later.) A `static:<dir>` browser check runs inside the container like a served
  application: a node file server on the container's loopback and the browser of the UI image
  (`BrowserVerifier.runInside`). Final integration and story delivery start the UI image for it
  (`servesABrowserCheck` now includes `static:`). A missing image reports "could not run" with the
  `docker build` line. A candidate's own container, which is not the UI one, declines a static check
  instead of rendering it on the host.
- The advisory language server at final integration (opt-in, `-Dswarmcoder.jdtLsHome`) reads the
  integration tree on the host.
- git itself, in the worktrees. Hooks live in the operator's repository, and the worktree's git
  pointer is mounted read-only in every container.

## 43. A model server's places go to different work first (2026-10-02)

Harness run 66: 45% of the tokens and 51% of the worker minutes went to candidates that were not
selected; the first candidate alone would have delivered four tasks of four; a judge call took 3.7
minutes; workers were sent 53 prompt tokens for each one they wrote. The swarm stays (owner
decision): what changed is who gets a place when there are fewer places than candidates, and
that nothing waits for a duplicate.

**Places.** A server serves a fixed number of requests at once. The first candidate of every ready
task of a wave waits its turn for a place; every further candidate is *spare* and starts only on a
machine place, a server place and a sequence that are all free that instant with nothing queued
(`CandidateGroup`, `WaveBoard`; `WorkerSlots.tryEnter`, `AdaptiveConcurrency.tryHold`,
`InferenceScheduler.tryAcquireLease`). A spare candidate may start late or never.

**A task does not wait for its own duplicate.** Candidates are verified as they end
(`SwarmEngineImpl.firstRound`). Once one has passed, candidates that have not started are
withdrawn. Candidates still in their session run on only while another task of the same wave is
still looking for a passing candidate - the wave could not end anyway. When none is, they are
stopped (`SUPERSEDED`) and wind down behind the task; the run waits for them once, at its end. A
candidate whose session has already ended is always verified and waited for.

**When the first candidate fails** the next one waiting stops being spare and waits its turn like a
first candidate. A spare candidate in its session gives its place up (`KillReason.PLACE_NEEDED`,
appended to the enum) when a first candidate or a repair worker has to wait (`SpareCandidates`).

**Tasks at once.** `swarm.maxConcurrentTaskGroups` left out now means no ceiling of its own: every
ready task of a wave is started and the places decide. This replaces the default of two in §36. A
number written in the file is still a ceiling, and the log says what it costs.

**Requests are counted per server, whoever sends them** (`ServerPlaces`, taken in
`VllmClient` and in `KoogAgentRuntime.ask`). Judge, expert, test author and reviewer requests to
the workers' own server take the same places as the workers' requests. A role on another endpoint
is not registered and never waits. One place is one request, never a session, so nothing holds a
place while it waits for something else.

**Judge.** Why 3.7 minutes: about 3,400 completion tokens a call, nearly all of it reasoning on a
server that cannot switch reasoning off, at that server's shared speed, one call after another.
Now: not called when exactly one candidate passed; identical changes judged once; the rest side by
side (`JudgeClient.judgeAll`); the prompt asks for the verdict and nothing around it. What is
given up with one survivor is the judge's reading of the house rules for that candidate; rules
that declare a check are still enforced at verification.

**No rebuild of a task that has a passing candidate.** Every survivor judged to break a rule: no
repair round any more, straight to the question about the rule (`everySurvivorBrokeARule`).
Unattended, disputes that point at an earlier task's file (§40, harness run 65) no longer rebuild
a task whose candidates passed: the rule stands, the best candidate is delivered, the run carries
a warning naming the file. A task with nothing that passed is still rebuilt with that file.

**Workers reread less.** A worker session uses the expert's trimming (`HistoryTrim.tidy`): results
older than the last four turns are cut to their first 600 characters once they exceed 12,000
tokens, down to 3,000 left whole; superseded results (the same call again, or a later call naming
the same path) go first. `read` takes `path:FROM-TO` and `path#name`. Long command output keeps
its beginning and its end. The system prompt is never rewritten, so the front of the conversation
is byte-identical for the whole session. Whether the ds4 server reuses a cached prefix cannot be
read off the code or the log: the code never asks for cached-token counts and the log has none.
Run 66's timing is consistent with it and does not prove it: 219 worker calls took 4,368 s and
wrote 102,036 tokens, which at the 26-33 tokens a second measured for one stream is 3,100-3,900 s
of writing, leaving two to six seconds a call for reading an average 24,700 prompt tokens. If it
does reuse the prefix, trimming on that server saves tokens far more than time and each rewrite
costs one re-read - which is why it is done in batches either way.

**Test authoring.** The tests of several tasks are written at the same time, as many as the
author's server serves when it is counted, otherwise four
(`-Dswarmcoder.testAuthoring.atOnce`). What is decided from the written tests still happens one
task at a time in plan order.

**Run report.** Section 10: per wave, candidates started first / because earlier ones failed / on
a spare place, never started, stopped, judge calls made and skipped; and prompt tokens per worker
turn.

## 44. Five decisions after the audit of the mechanical checks (2026-10-03)

The audit (see `GateReplayTest`) left five things that needed an owner decision. All five are
decided and built.

**A stopped worker's finished work is verified** (`StoppedWork`, `SwarmEngineImpl.verifyStopped`).
A worker stopped for no progress, at its turn cap, out of room or because its place was needed
(any `KILLED` candidate with a non-empty change) is committed and verified like one that ended by
itself, and can survive and be selected. Not verified: `SUPERSEDED` (its task already has a
passing candidate), `WRITESET_VIOLATION`, `COMPILE_FAIL_TWICE`. One that fails keeps the reason
it was stopped for, with the report beside it. The run's record gets a mark
`stopped candidate verified|<why>|<task>|<worker>|<survived or failed>`; the harness report
counts them in section 10.

**The start tree is looked at once per run, in a container, before the first wave**
(`BaseTreeChecks`, `SwarmEngineImpl.establishOnBaseTree`).

- *A house-rule check command that cannot run* (exit 126/127/9009, or the shell saying "not
  found", a syntax error, "not recognized as an internal or external command") is not a broken
  rule. Attended: the run parks before any worker starts, naming rule, command and reason.
  Unattended opinion policy: the check is skipped for the run with a carried warning. A command
  that runs and fails still fails the candidate.
- *Tests that already fail.* The existing-tests stage is run on the start tree; the failing ids
  go on the run (`Run.baselineFailingTests`), are logged once and carried as a warning (report
  section 6). A candidate, and the final integration, fail that stage only for a test not on
  the list (`VerificationBaseline.discount`). Reused across waves; measured again on the merged
  tree when a winner changed the file of a listed test. A start tree that cannot be measured
  establishes nothing and candidates are judged as before.

**A check the existing code already satisfies is recorded, not parked on** (`AlreadySatisfied`,
`GreenfieldWorkflow.recordAlreadySatisfied`). At TEST_AUTHORING, tests that pass on the start
tree are accepted as proof only when the test file was among the tests reported as passed, makes
an assertion, names a project type outside the test trees, and `SelfImplementedContract` finds
no stand-in; otherwise the run parks as before with the reason added. Accepted: the task gets
`ChecksAlreadyProved` with `beforeTheRun` set (tests, commit). A task with nothing left to prove,
whose contracts are in the tree and which nothing kept builds on, is dropped; its test files move
to `Run.alreadySatisfiedTests` and stay due at final integration, which stamps the criteria.
Open: when EVERY task would be dropped the run parks saying so - whether such a story is
delivered without a build is not decided. A kept task whose checks are already satisfied is
still dispatched, and a worker that finds nothing to change still fails it.

**Forbidden technologies come from the project's rules** (`ForbiddenTechGuard.forbiddenTerms`).
No product name is in product code any more. A name is taken from the list a prohibition governs
("do not use A, B or C", "none of them ...: A; B", "A and B are forbidden"), only when it leads
its list item, never from a remark in brackets, never from a prohibition confined to a place.
Acronyms match only as written. A project with no such rule forbids nothing.

## 45. Where run 74's 182 coding minutes went, and what stops it happening again (2026-10-03)

Live run 74 delivered, in 313 minutes, of which EXECUTING took 182. Ten fixes, as built.

**A plan may not split a change from the existing code it breaks** (`ChangeBreaksExistingCode`,
called by `TaskGraphValidator`). A task that adds an abstract method to an existing interface or
abstract class, or a component to an existing record, must have every existing implementor or
constructor caller in its write set; the objection names the files and says to widen the write
set or merge the tasks. The planner prompt states the rule. Not checked: removed or changed
members and constructor parameters of ordinary classes - a contract does not say them.

**Compile blame is established, not assumed** (`VerificationBaseline.StartCompile`,
`BaseTreeChecks.compile`, `CompileFailureCause.CAUSED_BY_CHANGE`). The compile stage is measured
on the tree each wave's candidates are cut from. An error in a file that was clean there is the
candidate's change, whichever file reports it; the verdict names what was changed and the
untouched file. Unmeasured keeps the old `PRE_EXISTING` reading. Kept per process, measured
again on resume.

**No repair round that cannot succeed** (`RepairCannotHelp`). Every verified candidate failing
its compile in the same file outside the write set: the write set is widened to it once and the
task rebuilt (file of a later task or of none), else the task blocks with a message that blames
the plan.

**Repair safety stops** (`EarlyKillEnforcer.IDLE_TURNS_BEFORE_KILL`, `KillReason.ROUND_TIME_UP`).
Five turns in a row with no file change and no new tool result stop a worker
(`-Dswarmcoder.worker.idleTurnsBeforeStop`); a repair round stops its unfinished workers after 30
minutes (`-Dswarmcoder.repair.roundMinutes`) and what they changed is verified.

**A turn asks only for output the server can write in time** (`ModelQuirks.loadedTokensPerSecond`,
`ModelQuirks.turnOutputTokens`, `RequestTimeouts`). Worker calls are still not streamed. The
client timeout is `-Dswarmcoder.worker.requestTimeoutSeconds` (1800 since 2026-10-03, was 900). The timeout log line no
longer blames a small conversation; a request timeout while nobody else was asking is not an
outage when the server answers a probe. Open: whether the server keeps generating after the
client disconnects was not measured.

**A stopped worker is not waited for** (`AgentSession.cancel`, `GroupSignal.onStop`).

**Source changed outside the write set fails the candidate** (`SourceOutsideWriteSet`), unless
the product widened the write set. Non-source strays are still dropped at integration.

**Where existing code lives is not the design's to fix** (`WhereExistingCodeLives`): carried as
a warning at once, no architect revision.

**Judge calls of run 74 wave 2 did run side by side**: 74 s and 335 s from the same start; the
5.6 minutes were one call. The report's per-task judge line now says so in figures.

**Run report rates** (`RunMeter.Call.failed`, `firstTokenMillis`): calls that gave no answer are
out of every rate and listed with their slot-minutes.

**A story the existing code already satisfies is delivered without building**
(`Run.nothingToBuild`, `FinalIntegrator.verifyTheUnchangedTree`): the unchanged tree is verified
once with the acceptance tests, criteria are stamped from that report, the run ends DELIVERED.

## 46. Run 75: a pin that matched nothing, a rule applied to a story it does not govern, and a deletion nobody asked for (2026-10-03)

Live run 75 delivered in 212 minutes with no park, and delivered the wrong thing three ways.

**A harness pin that matches nothing stops the walk** (`HarnessSnapshot.pin`, called by
`EndToEndLoopTest` before anything is agreed). `-Dswarmcoder.e2e.requirement=sorting` matched no
title that walk's analyst wrote, and the walk silently agreed the smallest requirement instead.
Now: the text is looked for in title, text and checks, any case; no match fails the walk with the
drafted titles in the message; several matches take the first and say so.

**A rubric objection that rests on a standing rule is not sent to the architect**
(`RubricObjections`, `GreenfieldWorkflow.rubricOnly`). The rubric review (completeness,
testability, partitionability) is shown the rules so that it does not object to a design for
following one. It read them as things every design must deliver, and objected that a story about
what the logbook does NOT offer lacked a filtered, sorted list; the architect added one. Whether a
design keeps the rules is the question of the rules check that runs next
(`DesignReviewerClient.reviewDesign`, with lookups), so a rubric objection that says "standing
rule" or quotes a rule's title is set aside with a log line. The rubric prompt says the rules are
not its rubric; the design and plan rules checks are told a rule is in question only where what
is added or changed falls under it, and that existing code the work does not change is not the
work's violation (`ONLY_WHAT_THE_CHANGE_TOUCHES`).

**A story does not remove existing public code unless an agreed check says so**
(`RemovedExistingApi`, `SwarmEngineImpl.removedExistingApi`). Decision: yes, something stops it.
A removal is the one change later work cannot build on, and "X is not offered" read as "delete
what is there" is a reading that destroys; a model does not make it alone. At candidate
verification the public and protected members and public types of main source files are compared,
by name, between the run's start commit and the candidate's tree. Anything gone fails the
candidate with the names, unless one of the story's agreed checks uses a word of removal (remove,
delete, drop, retire, "no longer" and the like), in which case nothing is checked. Not counted: a
changed signature, a type the same change declares in another file, test trees, code the run
itself added, a run with no story or no agreed check. The architect (in the existing-types
briefing) and the planner are told the rule. `-Dswarmcoder.verify.removedExistingApi=off`
switches it off. Limits: the removal words are English, and a story whose check says "delete a
contact" is taken to ask for a removal - both fail towards the old behaviour or the switch. It is
a late stop: a design that plans a removal anyway is caught only when a candidate exists, because
nothing in a design or a plan states a removal in a form a check can read.

**A role's lookup session tidies its old results** (`LookupAgent.sessionOptions`). The expert's
and the workers' sessions already replaced old lookup results by their first lines (§43); the
architect's, planner's, test author's and reviewer's never asked for it, and the architect was
sent 53,000 prompt tokens a call. Now above 24,000 tokens of results older than the last four
turns, down to 8,000 (`-Dswarmcoder.roles.tidyAboveTokens`, `.tidyToTokens`; 0 switches it off).

**Where the planning time went, measured, not fixed.** PLAN's 57 minutes were one attempt and one
session: 18 turns, 43 lookups, two draft checks, the first of which came back with objections
that the next turn fixed; the rules review after it took one minute and nothing was sent back.
The planner wrote 82,354 completion tokens in 20 calls at the server's 22 tokens a second, which
is 61 minutes: the time is the model reasoning, on a server that cannot switch reasoning off
(§43), with turns of six to ten minutes that end in two lookups. DESIGN is the same picture
(27 turns). DESIGN_REVIEW's 39 minutes were 3 for the rubric review, 32 for the revision the two
rule objections caused (including an expert question of 20 turns and 654,000 tokens about those
rules) and 4 for the rules check and the second rubric review; the first fix above removes that
revision's cause. The expert's sessions do tidy and still average 28,000 tokens a call: system
prompt, opening brief and the four protected turns of two or three lookups each. Open: a cap on
what a role may write in one turn, and whether an objection that a coupled change "cannot be
partitioned" should cost a revision when §45 says such a change is one task.

## 47. The live harness can take chosen roles from the operator's config (2026-10-03)

`-Dswarmcoder.e2e.rolesFromConfig=architect,planner` (names: architect, planner, analyst,
testAuthor, designReviewer, judge, expert, librarian) gives those roles the client the product
builds for the matching `config.yaml` entry (its own endpoint, key, model name and
`resolvedQuirks()`), through the product's own `ConfigLoader`. Every other role stays on
`-Dswarmcoder.live.*`. `HarnessRoleServers` holds the mapping, which follows the product's
wiring: analyst = `requirementsAnalyst`, else `chat`, else `utility`; planner = `storyPlanner`, else
`chat`, else `utility` (there is no `planner` key); expert = `utility`, else `architect`; librarian
= `utility` (`roles.librarian` is read by no wiring); the rest use their own key. An unknown name,
or a role with no base URL and model name anywhere in its chain, fails the walk before anything is
spent, naming the role and never the key. One start-up line per role gives host and model only.

The local shape (`-Dswarmcoder.live.shape`) and the discovered local budget are applied to the
local roles only. Only the local server is registered with `ServerPlaces`, and the role sessions'
scheduler pool is the local one only for the local endpoint (`poolOf`), so cloud calls take no
place on the Spark. The run report's section 1 gains a server column when any role ran elsewhere,
and section 7 (in flight, tokens a second) counts local calls only, with the others in a small
table of their own. Left as before: the refusal of non-local `swarmcoder.live.baseUrl` (cloud is
reachable only through this flag), and no `project.yaml` overrides are applied.

## 48. A type's own annotation in a contract is not a member; expert sessions report on the expert's server (2026-10-03)

Run 77: the architect wrote the type's own annotation as the first contract member,
`@com.zeroz4j.api.DataModel LogbookSort;`. The delivery check read it as a member named
`LogbookSort`, matched the constructor, and failed every candidate for the constructor not
carrying the annotation. `ContractMember.typeAnnotations(entry, simpleName)` now recognizes an entry
that is only annotation(s) plus the type's own simple name (modifiers, a `class`/`interface`/`enum`/
`record` keyword and a trailing `;` allowed). `ContractDelivery` checks it against the type
declaration (hand-written source, then generated source or compiled class from the build) and fails
it with "the type does not carry @X"; it is never matched against constructors. `parse(entry,
simpleName)` returns empty for it, which the record-component check in `ChangeBreaksExistingCode`
now uses; `ContractMembersAreDeclarations` accepts it. Other member parsers only match words or
build example shapes and are unaffected. No gate-replay case: that corpus needs a whole recorded
design and build tree.

Harness report: with `expert` taken from config, sessions opened for a planning role are tagged
"expert for architect" / "expert for planner", which did not match the "expert" server entry, and
when both servers serve the same model name the model lookup could not tell them apart, so they were
reported as local. `HarnessRunReport.Servers.labelOf` now maps `expert for X` to `expert`. The
escalation itself was already built from the config client for every asker and its sessions run on a
runtime with no scheduler, so they take no place on the local server.

## 49. What an expert answer costs, and what every role is sent per call (2026-10-04)

Run 77: 26 questions from the architect and the planner, 12 researched, 6.6 million prompt tokens
(301 turns at about 22,000 each; 17 to 32 turns and 31 to 71 lookups per answer). Causes found:

- **Turns, not turn size, were the larger part.** The expert went on wording one search many ways
  for things the project does not hold (`swarm.accept` did not exist yet). Its prompt asked for
  "working Java code for a stuck worker" whatever was asked. `ExpertEscalation.MAX_TURNS` is 12
  (`-Dswarmcoder.expert.maxTurns`), not 30; the closing turn still answers from what was read. The
  system prompt now says who asks, that "it is not there" is an answer, to answer in the form
  asked with `path:LINE` after each fact, and not to repeat a search in other words.
- **Each turn resent the same text.** 252 of 290 searches filled the 6,000-character cap because
  each quoted its two best files again; 96 of 552 lookups repeated an earlier one exactly. A
  session's searches now share a set of files already quoted and name them instead
  (`ExpertSearch.search(..., alreadyQuoted)`); the third identical call is refused
  (`ExpertTools.EXPERT_MAX_REPEATS`). History tidying was in effect all along (56 tidies).
- **Answers reached the asker cut off.** 21 of 26 answers were exactly 4,003 characters: the desk
  cuts at `answerChars()` and the expert was never told. It is now told its length in the opening.
- **The same facts were researched again** (about 1.8M of the 6.6M): where the acceptance tests
  live in four sessions, the shared module's rules in two. The two "identical" questions in the
  report shared only their first 90 characters; reuse was decided by shared words
  (`RunAnswerCache.OVERLAP_THRESHOLD`), which both missed these and handed answers to questions
  they did not answer, bare, so the architect asked again "without reuse".
  - **Two steps.** `ExpertDesk.judgedBy(StoredAnswerJudge)`: before the expert is asked, the
    workers' local model (`LocalAnswerJudge`, one plain call, role "answer check") reads the
    question beside the run's stored answers and the best the project's code offered, and replies
    with a number. With a judge the word count no longer decides reuse. No free worker model
    (`ProjectContext.localAnswerJudgeOf`) means the old behaviour.
  - A stored answer is handed back under the question it was researched for, and every expert
    answer ends with `Read from: <files>` (`ExpertEscalation.readFrom`).
  - The expert's opening carries the run's last four researched answers
    (`ExpertDesk.alreadyResearched`).
- **Asker guidance** said "there is no charge for a question". It now says the expert reads the
  same files the role's own lookups read, and that a search that finds nothing means the project
  does not have it. Roles may still ask as often as they like; nothing counts questions.

Other roles. The architect (27,000 a call) and the planner (25,000) ran in a working context of
about 34,000 tokens: `LookupAgent.TIDY_ABOVE_TOKENS` (24,000 of old results) is never reached in
that room, so the conversation sat at the room's compaction mark (40 compactions, no tidy). The
threshold is now at most a quarter of the role's working context
(`LookupAgent.sessionOptions(upcoming, workingContextTokens)`). The run report's section 1 has a
"prompt tokens per call" column.

Left: cached prompt tokens are not recorded - Koog's `AbstractOpenAILLMClient.createMetaInfo` is
final and drops `prompt_tokens_details.cached_tokens`, so it needs a wrapper at the HTTP client.
The test author's 33,000 a call is on the local server in a 262,144 room, where each lookup may
return 30,720 characters (`MaterialBudget`); not changed. A question waiting on another asker's
research is still matched by shared words (then checked by the judge). The "asked the same thing
again" mark in report section 9 still uses shared words and over-counts.

## 50. A contract on a type the project already has (live run 78)

The design said `HamBookRoot` carries `@DataModel`; the real class carries `@Vetoed`. Nothing
objected at design time; at verification every candidate failed ("the type does not carry
@DataModel ... told to create it"), the repair round ran 30 minutes, the task blocked.

- **Design time.** `ContractsMatchExistingTypes` (in `DesignFactChecks`, so `check_design`, the
  design gate and `GateReplayTest` all run it): for a contract on a type that exists (source,
  generated or compiled, read by `ContractDelivery`), an annotation stated on the type or on an
  existing member that the real declaration lacks is an objection quoting path:line and the real
  type annotations. Members the type merely lacks are NOT objected to (the ordinary "story adds
  members" case). A contract has no "this type is changed by the story" mark and no supertype
  entry, so the check cannot tell a false annotation from an intended one and says so in the
  objection; supertypes are not compared.
- **Verification.** `SwarmEngineImpl.contractShortfall(task, workspace, startFile)`: a shortfall on
  a type whose file is byte-identical to the run's start commit is logged, not failed. A type the
  task changed is still held, with `Shortfall.existedBefore` wording ("already existed ... change
  the existing declaration") instead of "told to create". No start commit: nothing excused.
- **Plan check.** `TaskGraphValidator` already demanded a task for missing members of an existing
  type; for an annotation it now says that is a change to existing code (write set, instructions).
- Left: a marker on contracts for "changes an existing type" would let the design check be exact.

## 51. Why the planner made 102 calls and no plan, and a test every candidate fails the same way (run 79, 2026-10-04)

**The planner.** Section 49 expected 15,000 prompt tokens a call; run 79 gave 26,303 over 102 calls
(architect 25,205 over 15). What the log shows:

- **The room, not the history.** The cloud roles run in the default working context of 32,768
  tokens, so their conversation is compacted at 24,576 (`HistoryTrim.HIGH_WATER_PERCENT`) down to
  13,107. The lowest the planner's conversation ever got after a compaction was 13,874 estimated
  tokens: its system prompt, opening and last four turns alone are as large as the mark it is cut
  to. Tool schemas are sent on top and are not in that estimate (the server counted about 5,000
  more per call than the estimate; not measured separately). So about 14,000 to 19,000 of each
  26,000-token call was fixed, and the architect was stopped at turn 14 because its fixed part
  alone was 24,744.
- **Section 49's threshold did take effect and made it worse.** The tidy fired 7 times and the
  compaction 14 times in 100 turns. Each one emptied every result older than four turns (the
  tidy kept 2,730 tokens whole), so the planner read the same files again: 248 lookups ran, 170
  different, 78 exact repeats, and 62 more were refused as "the same call 4 times" - for files
  whose text had been taken out of its conversation. Twelve files were read four to nine times.
- **No draft.** `check_plan` was never called in 100 turns, so there were no objections and no
  rounds of them (objections are returned all at once; that was never the problem). The session
  did not restart: it ended at turn 100 on a connection reset with nothing handed in, and the
  plan came from the one reply without tools.

Changed:

- `HistoryTrim.lowWaterAbove`: where the opening comes within half the span of the two marks of
  the low one, a compaction cuts to half way between the opening and the high mark, not to a mark
  under the opening. A worker's small opening leaves the configured mark as it was.
- `HistoryTrim.askedAgainCalls`: a result the session asked for a second time is what it works
  from. A tidy leaves it whole and does not count it; a compaction empties it last.
- `LookupAgent.sessionOptions`: in a small room a tidy keeps half its threshold whole, not a third.
- `ArchitectClient.plan`: a planner with lookup tools is no longer sent the framework reference
  and the worked examples (up to 9,000 + 8,000 characters at the baseline room, resent every
  call); `LOOK_IT_UP` says they are one lookup away. The reply without tools still gets both.
  `PLAN_HOW` says to check a first draft early and that a checked draft survives the session.

Not changed: the room itself. A cloud role's `workingContextTokens` is the operator's setting; in
32,768 with a fixed part near 14,000 there is room for about seven files. A larger room means
larger calls and far fewer of them. The refusal of a fourth identical call stays. The test
author's 34,709 a call has a different cause (a 262,144 room, lookups of up to 30,720 characters,
14 calls, local server) and only gets the asked-again rule. A lost HTTP connection still ends a
role's session.

**A test every first candidate fails the same way** (owner decision, 2026-10-04). Run 79: both
candidates compiled and failed only `LogbookTableTest#sortsByAnyColumn` with the same assertion;
the test was wrong, and a four-worker repair round ran 30 minutes to its ceiling before BLOCKED.

- `SameFailureForEveryCandidate` is a fourth shape of `SwarmEngineImpl.testRepairNeededFor`: at
  least two candidates with a verdict, all compile, none breaks an existing test, and all fail
  the same test ids with the same message once UUIDs, timestamps, object identities and long
  numbers are replaced (`normalised`). It raises `TestRepairNeeded.suspect` with each
  candidate's failure and change. This reverses harness run 30's rule that an identical
  assertion failure goes straight to the repair round. One send-back per task
  (`Task.testRepairAttempted`), not per test.
- `GreenfieldWorkflow.repairFaultyAcceptanceTest` asks `TestAuthorClient.reviewSuspectTest` (one
  reply, no lookup session): `testIsWrong` with a corrected file, or the reason the test is
  right. A correction passes every check a repair passes, must be red on the base tree (no
  second go if it is green), and is committed on the run's tests branch; then
  `SwarmEngineImpl.resumeAfterTestReview` re-verifies the candidates already written.
- Anything else - the author stands by the test, gives no usable answer, cannot be reached, or
  its correction is refused - is what happened before: the repair round, then BLOCKED. The
  author's answer is in every repair worker's evidence and in the BLOCKED question, and on the
  task (`AuthoredTests.reviewNote`, a new persisted field), which the run report prints per task.
- A task left BLOCKED by that path is not swarmed again when the workflow re-enters EXECUTING
  (`blockedAfterReview`). The older `resumeAfterTestRepair` BLOCKED path still is; not changed.

Left: the report line has no test of its own. Only the first dispatch is looked at; a repair
round whose workers all fail alike is not sent back.

## 52. The hosted DeepSeek API gets its own room (2026-10-04)

Run 79's cause: a cloud role's config names no `shape`, so it resolved to `generic-openai`
(working room 32,768) and the planner's 14-19k opening filled it.

- `ModelShapes.inferredFor(baseUrl, model)` picks a shape by host + model when none is named
  (a named shape always wins). `api.deepseek.com` + `deepseek-v4-flash` gives
  `deepseek-v4-flash-api`: served 1,048,576, working room 983,040 (raised on the owner's decision, 2026-10-04), 16 at once, JSON response format
  on, no Spark limits. The context figures are CHOSEN, not measured. `AgentModelConfig`
  applies it in both `resolvedQuirks` forms; the roles do not use `/models` discovery, and the
  hosted `/models` reports no context length anyway. Operator `quirks` still beat it.
- Tidy mark: `LookupAgent.TIDY_ABOVE_TOKENS` (24,000 of old results above the last four turns)
  is a fixed figure, shrunk only for rooms under 96,000; at 98,304 and larger it stays 24,000.
  Hard compaction stays at three quarters of the room (73,728 here). So a call is bounded near
  opening + 24k + four turns, not by the room.
- The harness start-up line per role ends with `(working room N tokens)`.

## 53. What one story cost in the planning roles, and the syntax tree made the first way to learn a project (run 80, 2026-10-04)

Run 80, one 783-line story on the hosted DeepSeek API: planner 32 calls / 1,285,971 prompt tokens,
test author 23 / 951,371 (architect in run 79: 15 / 378,078). Rule: CLAUDE.md section 1.

**Measured** (log lines `ExpertTools - <role> <tool>(..) -> N chars`):

- A 40,000-token call is about half fixed and half looked up. Each session resends everything it
  looked up on every later call: planner 699,000 of its 1,286,000 tokens, test author 390,000 of
  951,000 (characters held at each call, at 3.6 per token). The rest - system prompt, tool
  descriptions, opening, own drafts - is about 18,000 a call for the planner and 24,000 for the
  test author. It was never logged; `LookupAgent` now logs it per session.
- Characters looked up, by kind (runs 79 and 80): architect search 121,700 (20 calls), whole files
  43,852 (18), tree 2,210 (2). Planner search 154,176 (13), files 67,883 (25), tree 4,979 (7).
  Test author search 106,041 (8), files 53,670 (19), tree 5,405 (6). Expert search 30,252 (5),
  tree 0. Design reviewer and judge made no lookups. Workers: not recorded anywhere.
- Why search won: it was listed first and described "START HERE", `list_files` as "only when
  search has not found the place", the expert's prompt said "FIRST search"; every search quoted
  two files' bodies, a worked example and documentation, and its size grew with the room (cap
  30,720 characters in the 983,040 room; 7,000 to 20,000 returned). The tree had no query for a
  method body by type name, a module's contents or a build file, so folders were listed (17
  times) and `pom.xml` files read whole. Workers had no tree query at all.
- Same files read separately: 8 of the test author's 18 were files the planner had read; 9 of
  the architect's 15 were read again by the planner or the test author.
- Sessions restart. The plan was rejected once for one sentence (a rule about which tests to
  run); the second planner session made 10 calls and read 6 of the same files again. The test
  author's repair session made 7 calls and re-read 5. About 690,000 of the 2,237,000 tokens.
- Batching already happens (40 tool calls in 22 turns); the runtime runs a turn's calls in order
  and returns them together. No draft/check rounds: one `check_plan` per session, no objections.

**Changed:**

- `TreeQueries` (no model): `body_of` (one method, constructor, field or type by `Type#member`,
  overloads listed when long), `types_in` (a package's, module's or folder's types on one line
  each), `build_of` (a pom's coordinates, modules, properties, dependencies, plugins). Offered
  to every session of `ExpertTools` - roles and the expert - and to workers (`shape_of`,
  `body_of`, `types_in`, `usages_of`, `build_of` through `ApiLookup.tree`). Tree tools are listed
  first; `search` answers with places only and at most `ExpertTools.SEARCH_CHARS` (6,000) in any
  room; `read_file` is described as the last resort and still reads any file whole. The expert's,
  the roles' and the workers' prompts say the same.
- `ProjectMap`: every directory with its types (kind, member count, is-a from the graph), first
  in every role session's opening, the same bytes for every role and story until a file changes;
  coarser, never cut, in a large project. `-Dswarmcoder.roles.projectMap=false` leaves it out.
- `UsageTap` wraps the agent framework's HTTP client: the server's cached prompt tokens
  (`prompt_cache_hit_tokens`, or `prompt_tokens_details.cached_tokens`) and how much of each
  request repeated the previous one go on `RunMeter.Call`; `VllmClient` records the same. Once
  a session's server has reported a cache hit, old results are tidied at a quarter of the room
  instead of 24,000 tokens (`SessionOptions.tidyAboveWhenCachedTokens`): a tidy makes the rest
  of the conversation full price again.
- `LookupMeter` counts every role's and every worker's lookups by kind (tree, search, whole
  file, file part, listing, a worker's `find`/`grep`/`cat`). Run report section 1 has two new
  tables: what the prompt tokens cost (cached, in full, share repeated), and lookups by kind.

**Left:** a rejected plan or a broken test starts a new session that reads everything again -
continuing the same conversation would cost one or two calls. The architect's and test author's
openings still carry the type inventory (`ExistingProjectTypes`, up to 30,720 characters), and
the expert's opening still carries a quoting search: both are prompt-stuffing under CLAUDE.md
section 1. A worker's tree answers show the project as it was when the task started. Gradle
builds are not read by `build_of`. Nothing here was measured in a live run.


## 54. A rejected hand-in goes back to the same conversation; openings lose their inventories (2026-10-04)

The two items section 53 left. Rule: CLAUDE.md section 1. Nothing here was measured in a live run.

**A retry continues the conversation.** A role that handed in keeps its session
(`LookupAgent.Outcome.conversation`, `AgentSession.canContinue`): the runtime keeps the message
history when a run ends on a hand-in or a plain answer (minus the unanswered hand-in call, which no
later user message may follow). When the hand-in is rejected, `LookupAgent.resume` delivers the
objections in full as the next message of that session; the role works on with its lookups
intact and hands in again (`DraftTools` / `TestAuthorTools.nextRound`: new checks, fresh allowance,
the rejected draft forgotten). Covered: the architect (`revise`, keyed by design id), the planner
(`planAttempt` retry with objections, keyed by design id), the test author (every repair through
`repairFailingTest` / `repairWith`, keyed by task id). Held in memory, at most eight per role
(`KeptConversations`). A fresh session opens instead when: nothing is kept (first attempt,
restart or resume from a snapshot, evicted), the session failed or was stopped, the retry has
no objections to send (a plan that did not parse), or the conversation plus the message would pass
75% of the role's working context. Safety stops (turns, lookups, repeats) count across the whole
conversation; the token meter restarts per run. **Not covered:** the design reviewer and the
suspect-test review are single replies with nothing to continue.

**Openings.** The architect's opening carries only the rules about extending existing code
(`ExistingProjectTypes.architectRules`), not the type list; the test author's opening drops the
list; the expert's opening gets the project map instead of a quoting search, and its system
prompt says so. When a role is not an agent (no lookup session) nothing changes. Nothing a
mechanical check reads came from that text: `check_design`, `check_plan` and `compile_test` call
`duplicateObjections`, the validator and the compiler on the draft itself. The desk's own
grounding in the expert's question is still pasted (not touched here).


## 55. The Java language server is installed and every role and worker can ask it (2026-10-04)

Owner's decision: install Eclipse JDT LS and integrate it, so agents find usages, navigate and
refactor through it and the syntax tree, and never load a large file to search it. Rule: CLAUDE.md
section 1. Nothing here was measured in a live run with a model.

**Installed.** `jdt-language-server-1.61.0-202609031315.tar.gz` from
`download.eclipse.org/jdtls/milestones/1.61.0`, SHA-256 checked against the published one, 51 MB
packed, 53 MB in `C:/Users/dev/.swarmcoder/tools/jdtls/1.61.0`. It runs on the JDK that runs
SwarmCoder (25 here; it needs 21). Found without a system property (`JdtLsInstall`):
`-Dswarmcoder.jdtLsHome`, then `tools.jdtLsHome` in `config.yaml`, then the newest version folder
under `~/.swarmcoder/tools/jdtls`. `-Dswarmcoder.jdtLs=off` acts as if none were installed; the
build's unit tests run with it (root pom), `EndToEndLoopTest` switches it on.

**Measured** (`JdtLanguageServerLiveTest`, `LanguageServerOnARealProjectLiveTest`):

| | 4-file Maven fixture | this repository (191 jars) |
|---|---|---|
| process start to workspace read | 2.6 s | 2.8 s |
| first query, start included | 6.3 s | 24.6 s (indexing) |
| later queries | 5-100 ms | 100-700 ms |
| a file's problems | 1.1 s | - |
| rename across files / organize imports | 100-180 ms / 140 ms | - |

A library type's members (40 signatures with a javadoc sentence each) are about 4,000 characters;
60 references are 5,760 (40 shown, the rest counted).

**Containment - where it runs and why.** On the PC, for roles and for workers, and it **never
imports a build**. Importing a Maven project into JDT LS (m2e) loads the build's extensions and
plugins into the server's JVM, runs the plugin goals mapped to "execute", and runs annotation
processors there: project-chosen code on the host. So Maven and Gradle import are off, and
`JdtLanguageServer` writes a plain Eclipse project into the server's own data folder whose source
folders are links to the checkout's (found from each file's package line) and whose libraries are
the jars `ProjectClasspath.jarsOf` resolves offline - the classpath the syntax tree is parsed
with. Annotation processing is disabled, nothing is downloaded, nothing is written into the
checkout (asserted in both live tests). JDT LS's own "invisible project" could not be used: it
refuses a folder with a `pom.xml` above it. A worker's model-written code is therefore only
parsed, never run; a refactoring is written by our client, which refuses any file outside the
checkout (links followed) and holds each file to `PathPolicy` exactly as `write_file` does. The
worker image needs no change. Cost of the choice: no annotation processor, so generated code
(Lombok, mappers) shows as errors in `problems_in` - the tool says the build is the authority.

**Servers.** One per project checkout, read-only, started on the first question, store kept in
`~/.swarmcoder/jdtls-data` (warm next time), stopped by a shutdown hook at JVM exit. One per
worker checkout, only when that worker renames, organizes imports or asks
for problems; stopped with the worker, store deleted. One for the integration worktree (the
advisory precheck, now on whenever a server is installed), store deleted. Up to 1 GB heap each.

**The facade** (`LspService`; LSP4J stays in `sc-lsp`): references, definition, implementations,
supertypes, subtypes, callers, callees, workspace symbols, outline, members of any type
(library jars included, inherited names listed), member names for a mechanical check, hover,
problems, rename, organize imports. Every answer is a sentence plus at most 40 lines of
`path:line  one line`; never file contents. Every method answers "The Java language server is
not available: <why>" instead of throwing.

**One tool per question** (`LanguageQueries`). `public_shape` / a worker's `shape_of`: the tree
when it holds the type, the server only for a type the tree cannot list (a jar's).
`find_usages` / `usages_of` and `find_implementations`: the server when the symbol is declared
in the project's own source (exact bindings, the project as it is on disk), the tree otherwise
(a library symbol is used in the reference checkouts too, which only the tree reads). The server's
own: `supertypes_of`, `callers_of`, `callees_of`, `find_symbol`, `outline_of`, `doc_of`. "Where
is it declared" stays `public_shape`/`body_of`; no second tool.

**Who has what.** Architect, planner, test author and the expert (all through `ExpertTools`):
the six new queries, listed after the tree's and before search and whole files, bound only when a
server is installed. The design reviewer makes no lookups. Workers: `implementations_of`,
`supertypes_of`, `callers_of`, `find_symbol`, `doc_of` about the project as it was when the task
started, and in their own checkout `problems_in`, `rename_symbol`, `organize_imports`. Without a
server none is offered, and `public_shape` on a jar's type says the server is not installed.

**The gap closed.** `LibraryTypes.withJarMembers`: `ContractsAreDeliverable` (DESIGN_REVIEW,
`check_design`, the plan's entry check) now knows a type that exists only in a jar - a contract
for it is an existing type, and one giving it a member it lacks is sent back with the real member
names.

**Run report.** `LookupMeter.Kind.LANGUAGE_SERVER` (appended): its own column per role; the last
column is the share from the tree and the language server together.

**Not done.** Change signature, extract method and move type (JDT LS needs its own
`java/getRefactorEdit` requests with a selection; only rename and organize imports are built).
`check_plan` and `compile_test` do not use the server: a plan names no library members, and the
compiler already rejects an invented method. A source folder that appears after the server
started is not seen until it restarts. Two SwarmCoder processes on one project share a store, and
the second one's server will not start. Gradle projects get no library jars.


## 56. Why whole files still dominated after the tree-first change, and documents and members by name (run 82, 2026-10-04)

Run 82 was the first on sections 53-54 (no language server in that build). It delivered, but
whole files were still most of what every role looked up. Rule: CLAUDE.md section 1. Nothing
below was measured in a live run.

**Established from the log** (`ExpertTools - <role> <tool>(..) -> N chars`; a worker's calls
were not logged at all):

| role | whole files | what they were | why |
|---|---|---|---|
| architect | 24 (119,578 chars) + 1 part | 8 project documents, 82,000 chars (the framework primer 30,531, then 200 of its lines again); 16 small source files, 27,000 | a document had no access but `read_file`, and the tool guide said "read_file, for a document". Sources: read after `body_of`/`public_shape` of the same type, to see whole types |
| planner | 11 (32,895) | 9 project sources, 1 reference example | `public_shape` first, then the file for its bodies; `body_of` never called (0 of 33 calls). A whole type was only available as the file's text |
| test author | 7 (28,427) | a 12,914-char example test, 5 sources, 1 document | the same; plus two `docs_for` answers of 9,000 each |
| worker | 36 (107,143) + 5 parts, 12 shell reads | not logged | **the tree was not wired**: 11 tree calls returned 781 chars = 11 x the 71-character default "The project's syntax tree is not configured here; use read on the file." |
| expert | 3 (27,259) | 2 framework sources, 1 example | bodies of framework types; one `types_in` of a 60-type package was 30,611 |

- The live harness gave its workers `librarian::lookupApi`. A method reference binds
  `ApiLookup.lookup(String)` only; `tree`, `findExample`, the section-exclusion overload and
  the language server stayed at their defaults. Only `ProjectContext` wired them, in an
  anonymous class. The harness also never gave the curator a language server and is run with
  `-Dswarmcoder.jdtLs=off` (the root pom's default), so section 55 would not have reached a
  live run either.
- A worker changes code with `apply_diff` (needs the exact lines around the change; 5 were
  rejected in run 82) or `write_file` (needs the whole file, and the rejection hint said "read
  it first, then write it back"). Both make it read the file. And the tree shows the project
  as it was when the task started, so a worker read its own changed file whole.

**Changed:**

- `DocumentOutline`, `DocumentQueries` (no model; the documents are the reference index's own
  catalogue, the search its own section ranking). Three tools for every `ExpertTools` session
  and for workers: `doc_outline` (empty: the document map - every project document with size
  and outermost sections, each reference root with its document count; a document: every
  heading with number, lines and size; a folder: its documents), `doc_section`
  `<document>#<number or heading>` (one section; a section over 6,000 characters with
  subsections answers its lead and their list), `doc_search` (matching sections as
  `path:line`, number, heading, size - no text). Markdown and AsciiDoc. The project map names
  `doc_outline` and gives each document's size. On the target project the map is about 7,000
  characters for 19 documents (273,000) and a section averages 1,000.
- `body_of`: `Type#first,second` returns several members in one call; `Type` alone returns
  the type without comments, blank lines and imports (`JavaOutline.withoutComments`).
- `read_file` and a worker's `read` still read any file whole, uncut by this. When the file is
  a document of two or more sections or a Java type of two or more members, and the note is
  under a tenth of the file, the answer ends with one line naming the query that returns the
  part (`TreeQueries.wholeFileNote`, `DocumentQueries.wholeFileNote`). Computed from the file,
  nothing is refused.
- `CheckoutCode` (no model) and two worker tools: `replace_member` (`Type#name` or
  `path#name`, the complete new declaration; an overload is chosen by the new text's
  parameters or `:LINE`; leading import lines are merged; text whose braces do not close is
  refused) and `add_member` (before the type's closing brace). Written under the same
  `PathPolicy` as `write_file`. A worker's `body_of` answers from its own checkout as it is
  now and falls back to the tree for a type the checkout does not hold. `rename_symbol`,
  `organize_imports` and `problems_in` (section 55) are unchanged and sit before these.
- `WorkerLookups` (sc-app): the one `ApiLookup` the product and the harness both build;
  `withTheInstalledServer` starts the project's language server as `ProjectContext` does.
  `dev/e2e-loop.ps1` passes `-Dswarmcoder.jdtLs=on`.
- `LookupMeter.Kind.DOCUMENT` (appended); the run report has its column and counts it in the
  structured share. Every worker lookup and every shell read is now one INFO line
  (`worker <tool>('..') -> N chars`), as a role's are.
- Tool descriptions and prompts (`LookupAgent.toolGuide`, the expert's system prompt, the
  worker's static note): documents by section, `read`/`read_file` last, `write_file` for new
  files.

**Left.** What the 12 shell reads were is unknown until the next run logs them. `docs_for` /
`lookup_docs` still answer with section text (9,000 characters a call for the test author).
`types_in` of a large reference package is still one line per type (30,611 for 60). A type
over 6,000 characters of code is answered as a member list, not in parts. Setext headings
(`===` under a line) are not headings. `replace_member` does not replace a whole type or an
enum's constants. CLAUDE.md section 1 does not yet name documents in its lookup order.


## 57. A usage the tree could not see, a section asked for the way the outline prints it, and a review that could not look anything up (run 85, 2026-10-04)

Run 85: every role on the local model, language server on. Delivered (115 lines) for 6.07
million prompt tokens. Rule: CLAUDE.md section 1. Nothing below was measured in a live run.

**Established from the log.**

- **Test author, 46 whole files / 281,362 characters.** 22 reads of the framework checkout's
  Java sources and tests (169,898; six files read twice, 47,093 of it, after a tidy had emptied
  the first read), 5 documents (56,732), 8 build files (37,055), 8 small project sources
  (16,515), 3 `beans.xml` (1,162). Two tool defects drove it:
  - `find_usages('EclipseStoreProducer')` and `find_usages('TenantStorageProvider')` answered
    "nothing in this project's material" (97 and 98 characters). The checkout does use them,
    in `NodeInjectionTest` - `.from(EclipseStoreProducer.class, ...)` and
    `extends TenantStorageProvider` - but that test is in the producer's own package. The tree
    recorded a use only from an import, a `new` or an annotation (`LstReader`), so a class
    literal, a supertype and a field or parameter type in the same package were invisible.
    That one answer was the test's whole problem (below): the author never learned that a
    test lists the store producer as a bean, went on to list folders (8), read build files,
    configuration sources and example tests, and asked the expert (434 s).
  - `doc_section` took a number or a heading, and the outline prints `1.2  The basics`. The
    author asked for `persistence.md#1.2 The basics`, `#1.5 Reading`, `#1.7 Multiple tenants`
    (949, 946 and 955 characters back: "has no section" and the outline again, three times)
    and then read the document whole; by the sizes returned the same happened with
    `10-testing.md` (three times) and the primer's `#8 Testing`.
- **The suspect-test review.** One schema-constrained reply with no lookups
  (`reviewSuspectTest`). It answered `testIsWrong: true` with an empty `files`, saying the
  correction could not be written "without guessing" - true: it had no way to look. The answer
  was thrown away as unusable and the repair round ran. (Its system prompt also read
  `Respond O\nY with JSON`, a damaged "ONLY".)
- **Was the test wrong? Yes.** `LogbookTest` starts `TestServer` with
  `beans(LogbookServiceImpl.class, HamBookDataRootProvider.class)`. The harness has bean
  discovery off and nothing listed produces `ZeroZDbNode`, so no implementation of the task as
  designed can pass (WELD-001408) - the framework's own test lists `EclipseStoreProducer`. The
  candidate that "passed" added a `@Produces ZeroZDbNode` to the data root provider that keeps
  the node in a static field and opens it at `zeroz.store.dir`, default `./data`: it ignores
  the store path the test sets, "survives the restart" only because the static field outlives
  the second `TestServer`, and in the real server it is a second producer of the node beside
  the framework's. The delivered 29 lines satisfy the test and not the requirement.
- **Workers, 22 shell reads / 46,071.** About 12 were `find`/`cat`/`ls` for the acceptance
  test they had been told failed (81, 77, 70, 66, 46, 11, 7 characters back). The prompt said
  "Acceptance tests in <dir> are protected", which reads as "they are there"; they are placed
  in a candidate only when it is verified. The rest: `grep -rl` over the framework for a
  type's users and for `TestServer.builder` (the usage defect above), `find` for example
  tests, `cat` of two example tests (8,186 and 5,210), of three project sources and of a pom
  (8,279). What the 7 searches (45,575) and the 15 language-server queries asked is not known:
  `lookup_api` and the language-server tools were not on the per-call log line.
- **Architect, 43,605 a call.** Fixed per call: system prompt about 1,500 tokens, opening
  about 4,950 (the story, the project's rules, the build's layout; the map is 1,134 of it),
  28 tool descriptions (not measured). Nothing in the opening is an inventory or pasted
  reference. The rest is what it looked up and wrote, resent on every call (50,063 tokens
  held at the first revision): `build_of ''` 12,711 characters - a line for every build file
  of the framework checkout and its examples; an example test whole 13,228 and `find_example`
  9,073; two framework sources whole 18,427 (the same two the test author read twice);
  `AGENTS.md` whole 8,151; four searches 16,678; and its own four drafts given to
  `check_design`, 33,926 characters.
- **Chain link 13.** `judgeSurvivors` has not called the judge for a lone survivor since the
  scheduling change; the link still required a scored candidate.

**Changed.**

- `LstReader.visitIdentifier`: a type named in code is a use (`names`), on the first line that
  names it, when the file has no import, `new` or annotation of it and does not declare it.
  `SemanticFacts.FORMAT` is `lst-relations-v3`, so every cached tree is parsed again once.
- `DocumentOutline.find`: `<number> <heading>` and the outline's whole line name the section
  with that number when the words belong to it; words that do not are still "no section".
- `TreeQueries.buildOf('')`: the project's build files as before; a reference root answers
  with its count and how to ask for one module.
- `TestAuthorClient.reviewInSession`: with a lookup session configured the review runs in the
  conversation that wrote the test when it is kept (section 54), a new session otherwise, with
  every lookup and `compile_test`. Read from what the session did, never from its words: a
  draft given to `compile_test` is the correction (reason = what it gave `report_done`),
  `report_done` with nothing compiled is the author standing by the test, neither is no
  usable answer and says what the session ended on. The correction then meets the same checks
  as before, red on the base tree included. No session at all: the one reply, as it was.
- Workers: the task text says the task's tests are not in the checkout while it works;
  `lookup_api` and every language-server call are logged like the other lookups; the first
  shell read of a worker is answered with one line naming the tree queries for the same
  (`WorkerToolbox.SHELL_READ_NOTE`), nothing refused. The answer of `find_usages` no longer
  ends "read any of these with read_file".
- `JudgeLinkCheck` (harness): link 13 per task. Two or more candidates that passed must all be
  scored and the brief must carry a real verification line; exactly one, with a verification
  report, holds without a judge call; none is a break.

**Left.**

- Whole files are still never refused. A role that wants a whole framework source or example
  test reads it (`body_of <Type>` is the same without comments and is named in the note); a
  file emptied by a tidy and read again is whole again (47,093 here).
- Workers are not shown the acceptance test at all, only its failure. Whether they should be
  is the owner's decision; the hunt for it is only told to stop.
- `build_of` shows no plugin configuration, and resources (`beans.xml`, `META-INF`) have no
  query but `list_files` and `read_file`.
- Run 85's delivered change should not be kept as it is (above).
- A test the author stands by, or whose correction is refused, still goes to the repair round;
  nothing checks whether a candidate passes by supplying what the test should have listed.


## 58. The test author rediscovers how a test is written here, for every story (run 86, 2026-10-04)

Run 86 resumed after the plan: one acceptance test of 114 lines cost the test author 90 calls
and 7,378,107 prompt tokens (81,978 a call, 5,126 s). Rule: CLAUDE.md section 1. Nothing below
was measured in a live run.

**Established from the log.**

- **The 90 calls.** 88 before the first hand-in (178 tool calls), 2 in the correction after the
  send-back (one compile, then hand-in). 166 calls reached a tool and are on the log; 13 more
  were refused as the same call made a 4th to 7th time. 43 of the 166 repeated an earlier call
  exactly (136,699 characters). Seven files were read whole two or three times each: 18 reads,
  183,119 of the 226,518 whole-file characters (`TestServer.java` 3 x 19,281 and three parts
  of it, the example `ChatServiceImplTest` 3 x 13,228 and three parts - it was also in the
  opening - the project's server pom 3 x 8,933, `TenantStorageProvider`, `EclipseStoreProducer`,
  `NodeInjectionTest`, the test module's pom).
- **compile_test, 4 calls.** One draft broken (1,016 characters back; what it said was not
  logged), one refused because the draft was addressed `project/hambook-server/...` as the
  lookups address files, one healthy, and one healthy in the correction. One further turn was
  lost calling `write_file`, a tool the role does not have.
- **Why 82,000 a call.** Fixed: system 2,354 tokens, opening 9,568, 28 tool descriptions. The
  rest is lookup results resent. The tidy is in effect, but the server reports cache hits, and
  then old results are left until they fill a quarter of the room: 65,536 tokens in the
  262,144 room, cut to half. It fired once, at turn 51 (92,989 -> 59,405). After it the
  conversation grew to 123,730 with no second tidy, because a result asked for again is left
  whole and not counted, and most reads after turn 51 were repeats. The correction continued
  that conversation: 2 calls of about 124,000, 3% of the role's tokens. 93% of all prompt
  tokens came from the server's cache; the 5,126 s are the 106,212 completion tokens at 21 a
  second, not the prompt.
- **Why whole framework files.** The tree and the language server do cover the reference
  checkout, its test sources included (`find_usages` found `NodeInjectionTest` by the 12th
  turn; `types_in` listed the test module). Three queries failed:
  `body_of TestServer$Builder`, `TestServer.Builder` (three times) and `TestServer.start` were
  answered "does not declare" / "no such type ... a library jar has no source here", and
  `body_of TestServer` (19,281 characters) is a member list - so the file was read whole;
  `find_symbol ZeroZDbNode` answered "0 type(s) match" (31 characters) seven times for a jar
  type the tree knew by its full name from the code that uses it (`public_shape` of the full
  name worked, 74 minutes in); `find_usages ZeroZDbNode#embedded` found nothing. Poms and
  `beans.xml` have no query (`build_of` is 507 characters for an 8,933-character pom and shows
  no versions or plugin configuration) - 13 reads. A library jar's source is not in the
  material at all.
- **What it was looking for.** By turn 12 it held the framework's own test that lists the
  store beans. The other 76 turns asked whether bean discovery would find them in this
  project (searches for `disableDiscovery`, `weld-se-core version`, `beans.xml`; 23 folder
  listings) - a question a passing test of this project answers, and none was there.
- **The harness removes them.** `BookshelfFixture` deletes every `src/test/java/swarm` tree
  from the clone ("removed 1 leftover file(s)": the `LogbookTest` of the earlier stories), so
  that "the acceptance stage executes a non-zero number of tests" counts only this run's
  tests. Had it stayed, the project map would have named it, but the example in the opening
  and `find_example` would still have come from the reference material: `ExamplesByUse`
  looked at the project's own tree only when no reference file qualified.
- **Nowhere to keep what a role learned.** `RunAnswerCache` holds expert answers for one run.
  `KnowledgeExtractor` writes model-made documents the operator must accept. Nothing
  deterministic is kept per project across stories, and nothing needs to be: the repository's
  own passing tests are the recipe.

**Changed.**

- `ExamplesByUse.find`: when a test is asked for and the project holds a test that uses a
  type the rules name for tests, that test is the example, ahead of the reference material
  (the opening's example and `find_example`). `TaskBrief.renderTest` says it is the project's
  own and that the new test is a new class beside it.
- `TreeQueries.bodyOf`: `Outer.Inner`, `Outer$Inner` and `Type.member` are followed down from
  the outermost type the tree knows (`nestedIn`); a name the type does not declare is answered
  with what it declares.
- `ExpertTools.findSymbol`: when the language server lists nothing, the answer is what the
  tree knows of the name - its full names, declared where or "from a library jar" - and the
  two queries that go on from there.
- `TestAuthorTools`: a draft addressed `project/<path>` is taken at `<path>`; every compile
  logs what the check said (first 600 characters).
- Harness: `-Dswarmcoder.e2e.keepAcceptanceTests=true` leaves the module's earlier acceptance
  tests in the clone. Off by default (owner's decision, below).

**Left.**

- Whether the harness keeps earlier acceptance tests by default. With them kept, the chain
  link about a non-zero number of tests can be met by tests the run did not write.
- `TestAuthorClient.write` writes over any file at the path it is given, and `removeAll`
  deletes it on a superseded attempt. Run 86's test was again named `LogbookTest`: in a real
  repository it would have replaced the earlier stories' acceptance test of that name. The
  example's heading now says not to; nothing enforces it.
- The tidy mark under a caching server is a quarter of the room whatever the room (65,536
  here). Right when cached tokens cost a tenth; wrong if raw prompt tokens are what is
  counted. Not changed.
- No query for a pom's versions and plugin configuration or for resources such as `beans.xml`
  (section 57 left this too); a type over 6,000 characters is still a member list;
  `find_usages Type#member` for a library type's method.
- `ask_expert` answered the one question free, "covered 0.5 of the question", in 0 s; whether
  that answer helped is not on the log.

## 59. An earlier story's test is never destroyed, repeats are one line, resources have a query (run 86 follow-up, 2026-10-04)

Rule: CLAUDE.md section 1. Nothing here was measured in a live run; the causes are section 58's.

**Changed.**

- **A test file the project already has keeps its test methods.** `EarlierAcceptanceTests` takes
  what HEAD holds under the acceptance-test directory when a story's authoring starts.
  `compile_test` (so, before hand-in) compares a draft at such a path with it on the syntax tree
  (`TestMethods`, over `JavaOutline`; comments and whitespace do not count): a test method
  removed or changed sends the draft back with the list, "keep every method and add yours, or
  use a new class name". Methods added are fine. `write` also refuses such a file, and
  `removeAll` puts the earlier file back instead of deleting it. The author's tool descriptions
  say so in two sentences.
- **Red check.** `RedChecker.check(target, spec, earlierTestIds)`: failures of earlier tests are
  not this story's red. Earlier failing and nothing new failing is "not red: earlier tests must
  stay green"; new tests failing is red as before. The ids come from the same baseline, not from
  HEAD of the red-check tree, which can hold this story's own committed tests.
- **Harness keeps earlier acceptance tests by default** (`-Dswarmcoder.e2e.keepAcceptanceTests=false`
  to strip). The fixture counts the test methods the clone holds (`keptTests`); the chain link
  "the acceptance stage executes a non-zero number of tests" now needs more executed tests than
  that. `BrownfieldLoopTest` does not use this fixture and is unchanged.
- **Repeated lookups.** In the runtime's loop, after the turn's compaction and tidy, a result
  whose tool and arguments repeat an earlier call that is still whole in the conversation, and
  whose output is equal to it (countdown note aside), is sent as "unchanged; the full answer is
  already in this conversation, from your call N turns ago." The tool is still run, so a changed
  file or index gives a different output and is sent whole; a tidied or compacted earlier answer
  is not "held" and the output is sent whole. Outputs under 300 characters are sent as they are.
  The tidy no longer counts an "unchanged" call as superseding the earlier one (it would have cut
  the one answer the model has first). All roles, the expert and workers go through this loop.
- **`build_of`** also gives each plugin's configuration (flattened `path=value`), executions and
  own dependencies, and a `test-scope dependencies` line with scope and version taken from
  dependency management and `${property}` values of the module and its parent poms.
- **`resources_of`** (new, offered beside `build_of` to roles, the expert and workers): a module
  lists its resource files with sizes; `<module>/<path>` gives one file whole up to 6,000
  characters, else its outline (XML elements, property keys, YAML top-level keys, line count);
  `#<element, key or L10-40>` gives one part.

**Left.**

- The run report's "characters returned" still counts the full answer of an unchanged repeat; the
  saving shows in prompt tokens only.
- If the process restarts between authoring and a repair, the earlier-test baseline is gone and
  a repair is not held to it (nothing is lost that was not lost before).
- A hand-in whose last draft was refused for dropping tests is not written; the story then has
  no test file instead of a replaced earlier one.
- Parent poms are followed through `relativePath` (default `../pom.xml`) only.

## 60. Workers could not see what the run itself had delivered; the harness took a kept test for a leftover (run 88, 2026-10-05)

Run 88: every role on the local model, language server on, earlier acceptance tests kept.
Delivered (757 lines, 3 tasks) for 4.88 million prompt tokens, 3.55 million of them the workers'
(175 calls, 20,416 a call). Rule: CLAUDE.md section 1. Nothing below was measured in a live run.

**Established from the log** (`worker <tool>('..') -> N chars`; the lines carry no worker or
task, so tasks are told apart by time).

- **The 61 whole files (198,654 characters), by cause.**

  | cause | reads | characters | tasks |
  |---|---|---|---|
  | the four types the first task added (`LogbookScreenDescriptor`, `UiField`, `KeyboardLayout`, `LayoutConstraints`) | 30 | 64,196 | server 25, client 5 |
  | the files the task changes, or an earlier task of the run changed (`LogbookService`, `LogbookServiceImpl`, `LogbookScreen`) | 20 | 92,103 | server 14, client 6 |
  | the earlier story's kept `LogbookTest`, read in place of the task's own test | 4 | 16,892 | server 3, client 1 |
  | other project files (`Qso`, `LogbookTexts` twice, `Ui`, `LogbookFilter`, `HamBookDataRootProvider`, `LogbookScreen` by a server worker) | 7 | 25,463 | shared 1, server 2, client 4 |

  The first task read 1 file. The server task (6 workers) read 44, the client task 16.
- **The tree and the language server answer for the run's base, a worker's checkout is cut from
  the run's progress.** `shape_of` of the four new types answered "No type ... in this project's
  material" 27 times (57 to 73 characters), `find_symbol` "0 type(s) match" 13 times: 40 of the
  workers' 78 tree and language-server queries were dead. Each was followed by `ls` or `find`
  and the file whole. `body_of` already answered from the checkout (section 56) and was called
  twice. The same staleness made a client worker ask the expert why `LogbookServiceImpl`
  imports a type "that does not exist"; it was stopped at 24 calls without a change, 270,932
  prompt tokens.
- **The prompt said to.** The first sentence of the worker's rules was "inspect with read/exec;
  modify with write_file (full file content - reliable) or apply_diff"; the steer after 8 calls
  and the read pause both said "write_file". The paragraphs about the tree and the member edits
  came 40 lines later. The four repair workers each opened by reading both files of the write
  set whole (8 reads, 35,178).
- **Edits.** 4 `apply_diff`, none rejected (they show as `git apply`). `write_file`,
  `replace_member` and `add_member` were not logged: about 40 calls between them (279 tool
  calls less the logged ones - an estimate), and whether a member edit was used or refused is
  not known.
- **The 28 shell reads.** 11 looked for the task's own acceptance test (`find ... UsabilityTest`,
  `ls .../swarm/accept`), which is not in the checkout, and found the kept `LogbookTest`; 9
  listed folders for the types the tree said did not exist; 4 read surefire reports; 2 were
  `find`/`grep` for a type's users and generated sources; 2 were `cat > file <<EOF`, writes
  counted as reads.
- **The repair round.** Both first candidates of the server task compiled and passed 3 of the 4
  acceptance tests. They failed `UsabilityTest#usableFromKeyboard`: "interactive field
  'dateRangeFilter' (the other: 'fromUtc') must carry a keyboard shortcut". No tool failed.
  The rule that every interactive field needs a shortcut is in the test, which a worker is not
  shown (section 57, owner's decision still open); both workers had looked for it.
- **Chain link 9.** `acceptanceFilesOutside(repo, "")` called every `src/test/java/swarm` file
  in the checkout a leftover, and `filesInCommit` counted every such file of the tests commit as
  the run's. Both assumed the folder starts empty; since section 59 it holds `LogbookTest`.
  Link 10 had the matching gap: a walk resumed from a snapshot never made the fixture, so its
  count of kept tests was 0.

**Changed.**

- `CheckoutCode.shapeIfNotAsTheTree`, `notAsTheTreeIn`, `newTypesNamed` (no model, no compiler):
  a worker's `shape_of` of a type whose file in its checkout is new or changed since the tree
  was built is answered from the file as it is now (header and every member's header with its
  lines); `types_in` lists such files after the tree's list, marked new or changed;
  `find_symbol` adds the types of new files that match. A file the tree was built from,
  unchanged, is still the tree's answer. `ApiLookup.sourceInTree` (the file as the project
  holds it; `WorkerLookups` reads it from the project path) is how the toolbox tells.
- Worker prompt: the first sentence is tree queries, then `replace_member`/`add_member`, then
  `write_file` for a new file; a rejected diff goes to the member edits. The steer, the read
  pause and the rejected-diff hint say the same. `apply_diff` is described as what the member
  edits cannot do.
- Every edit is one INFO line: `worker edit <tool>('<target>') -> done|REFUSED: <first line>`,
  `write_file` saying whether the file existed.
- `LookupMeter.ofShellCommand`: a first step that redirects its output to a file is not a read.
- Harness: `AcceptanceFilesLink`. A file is the run's when the run's pinned base commit does
  not hold it or holds other content; link 9 needs at least one such file under the compiled
  directory, none elsewhere, and no test file on disk that the base does not account for (new,
  or a base file changed on disk). Link 10 counts earlier tests from the base commit. Link 16 is
  given the run's files only.
- `TestAuthorClient.agentReply`: a hand-in that would take an earlier test away is sent back to
  the conversation that wrote it (`handInNotTaken`, up to `HAND_IN_REASKS` = 2 times); the
  earlier file is never touched. After that the old path stands: nothing is written.
- `EarlierAcceptanceTests`: no copy of the sources in memory. `pin(tree, commit)` records which
  commit is the base - `GreenfieldWorkflow.step` says it from the persisted run on every step,
  so after a restart too - and `earlier`, `ids`, `objection` and `restore` read that commit
  (`git show`). A tree nobody pinned takes its HEAD when authoring starts, as before.

**Left.**

- `usages_of`, `callers_of`, `implementations_of` and `supertypes_of` still answer for the
  run's base. Pointing a worker's language-server queries at its checkout's own server (the one
  `problems_in` starts) would fix all four; it changes what the reference material is addressed
  as and needs a live run.
- A worker's lookup log line names no worker and no task.
- Whether a worker is shown its task's acceptance test (section 57) is still the owner's
  decision. Run 88's cost of not showing it: the repair round above (4 workers, 1,031,748
  prompt tokens) and 11 shell reads.
- The stop at 24 calls without a change counts a 60-character tree answer like a whole file.
- Two runs on one repository with different base commits share the repository's pin (the last
  one said wins) for trees that are not pinned themselves.
- `BrownfieldLoopTest`'s link 9 still counts every file of the tests commit; it does not use
  the fixture that keeps tests.

## 61. A worker may read the acceptance test its task must satisfy (owner's decision, 2026-10-05)

Until now the test was hidden from a worker's checkout and placed only at verification (sections
57 and 60). Run 88 paid a repair round of 1,031,748 prompt tokens for a rule (every interactive
field needs a keyboard shortcut) that existed only in the hidden test, and 11 shell reads were
workers hunting for the test file. Owner: workers may READ it, read-only.

**Changed.**

- `acceptance_test` (worker tool, no model): the test methods the task claims (from
  `Task.authoredTests`, or every `@Test` method of a claimed file) plus the helpers of their class
  they use (members whose names the shown code uses, followed through, and the
  `@BeforeEach`/`@AfterEach` methods), read from the run's tests commit
  (`SwarmDispatcher.setTestsCommitOf`, set by `SwarmEngineImpl`; `GitService.fileAt`).
  `knowledge/AcceptanceTestRead` does the cutting. Asked with a class or `Class#method` it narrows.
  Never the whole file, never pasted into the prompt. Earlier stories' kept tests are ordinary
  checkout files (`body_of`, `read`).
- Prompt: the sentence saying the test is not in the checkout is replaced by one naming
  `acceptance_test`. The shell-read note names it too.
- Metering: `LookupMeter.Kind.ACCEPTANCE_TEST` (appended; counts as a structured lookup). The run
  report's lookups-by-kind row gets a column "acceptance-test reads" with calls and characters.

**Unchanged on purpose.** Write policy: `PathPolicy` still refuses `write_file`, `apply_diff`,
`replace_member`, `add_member`, rename and (by audit and revert) shell writes under the protected
directory. Verification: `AcceptanceOverlay.reduceTo` clears the protected trees and writes the
claimed files from the run's tests commit; a new test shows a candidate that weakened its test and
added another is verified against the committed one. `TheFixIsNeverShownToTheSwarmTest` is about a
brownfield maintainer's fix, not this, and is untouched.

**Tests changed.** `AWorkerUsesTheLanguageServerTest` (tool order now starts with
`acceptance_test`) and `WorkerWriteReplayTest` (its copy of the prompt line). Nothing else
asserted the test was hidden.

**Left.** Whether to show the helpers of other classes the test imports; whether a repair worker
should be pointed at the failing method first. Not measured in a live run.

## 62. Seven accepted stories whose screens no user could open (2026-10-05)

Seven logbook stories were delivered and accepted (the last was run 88). The owner opened the
application and saw the placeholder pages it started with. The runs had added three screen classes
that nothing references; the routed page still returned its placeholder; no existing source file
had been changed; the acceptance tests called the server's service. Design, plan, test author,
judge and final acceptance passed it: nothing asked whether what was added is connected to
anything. Nothing below was measured in a live run.

**Changed - from the object graph, no model.**

- `knowledge/ReachableCode`: the graph of a tree on disk (`LstReader.readTree`, uncached; the
  resolved types each file uses). A production file is reachable when it is an entry point or a
  reachable file uses a type it declares; test files are not in the walk. Entry points are
  learned from the project: a `main`; a type a build file or a non-Java file of a main source
  tree names in full; and whatever the project's own framework-discovered types carry.
  "Framework-discovered" is a file that was there before the run, that no other such file uses,
  with no `main` and not named in a resource: its type's annotations (else the annotations in
  it, else its supertypes; never `java.*`) are how discovery looks here. On the tree as run 88
  delivered it this learns `@Route` and `@ApplicationScoped` and names the three screens and
  their three text classes; the four shared descriptor types are reached through the server.
- **Plan** (`PlanConnectsWhatItAdds`, in the PLAN attempts and the architect's plan review): a
  plan that writes a new production source file while no task may change a reachable file (or a
  main-tree resource) in that file's source root, or in a root whose code already uses that
  root, is sent back with the files and the places the application is entered today. A new
  type whose task names a discovery annotation is not counted; nor is a module that does not
  exist yet. Build files do not count: every task is given its module's.
- **Candidate** (`SwarmEngineImpl.unreachableAddedCode`): a candidate of a task nothing depends
  on fails verification when files its own change adds are unreachable in its tree - the
  ordinary verdict, so the repair round is told. A task something later builds on is not asked.
- **Run** (`FinalIntegrator.unreachableAddedCode`): after the last merge is green, every file
  the run added must be reachable in the merged tree, or integration fails with the types named.
- `-Dswarmcoder.verify.unreachableAddedCode=off` switches all three off.

**What it cannot see.** Members: a reachable class with an uncalled method, a screen without a
button, a handler that does nothing (the call graph has no receiver for interface dispatch, and
accessors used by serialisation have no caller). A type reachable code names and never really
uses. Dead code that was already there teaches its annotation or supertype as "discovered". A
source root where more than half of the earlier files are unreachable is taken for a library's
surface and not judged; a library with fewer gets a wrong objection (the switch). Names built at
run time. Other languages. A tree parsed only in part, or with no entry point, is not judged.

**Changed - a real browser (the part that needs no model).**

- Established: the contract's `browser` block could declare how to start the application and,
  per page, an address and selectors that must be visible - one page load each, no click.
  The image `swarmcoder-worker-ui:latest` is on this PC (Playwright 1.44.0, Chromium, node);
  final integration and story delivery already start the application and the browser inside
  it. The delivered project's contract has no `browser` block, so nothing ever opened it.
- A check may now carry `steps` (`click`, `fill` + `value`, `press`, `expectVisible`,
  `expectHidden`): a journey. The browser starts at the check's `url` and no step loads an
  address, so a screen is reached through the application's own navigation or not at all. The
  first failing step ends it and is an ordinary failed assertion. `check.js` carries the steps
  out; the verifier sends this build's copy of it with each request (`sc-sandbox` puts it on the
  classpath), so the image needs no rebuild. Outside a container with a browser a journey is
  "could not try", never loaded here.

**Left - who writes the journey (design, not built; nothing to download).**

- The test author, through the acceptance-test path: `<name>.journey.yaml` files (the steps
  above, no address but the entry page) in the protected acceptance directory, in the run's
  tests commit, claimed by a task like a test file, shown to workers by `acceptance_test`. A
  `check_journey` tool parses a draft (no model). The red check requires a journey to fail on
  the start tree.
- Deterministic backstop: a plan that writes into a module the build survey says runs only in a
  browser (`BrowserOnlyCode`) must claim at least one journey.
- Where they run: at final integration always; per candidate only for a task that claims one,
  which then needs the browser image for its container - a cost to decide.
- Needs the owner: a `browser.serve` line in each project's contract (the project's own
  repository; `WebAppDetection` proposes one), and a live run to see what the test author does
  with the tool.
- `callers_of` per member, and a rule for public members nothing calls, are not added.

## 63. The test author writes a journey for a story with a screen (owner's decisions, 2026-10-05)

Section 62 left the journey designed and not built. Built here. Nothing below was measured in a
live run, and no browser was started for it: the journeys' own run in the container is section
62's code, unchanged.

**How "this story has a screen" is known - `verify/ScreenChange`, no model, no wording.** A task
has a screen when its write set names (1) shipped code of a module the build survey calls
browser-only (`BrowserOnlyCode.survey`), or (2) a page file (html, htm, css, js, mjs, jsx, ts,
tsx) in a project whose contract has `browser.serve`. Build files and paths under a test folder
never count. Not seen: a screen drawn by server-side code (a server-side UI framework, a page
built as a string) - its module is a JVM module and its files are ordinary sources.

**Changed.**

- **The file.** `<name>.journey.yaml` in the protected acceptance directory: `journey:` (one
  sentence) and `steps:` (section 62's five). `verify/JourneyFile` reads and checks it: only
  those two keys, one action a step, `value` only with `fill`, the last step an expectation, at
  most 60 steps, no key that loads an address. It starts at the contract's first page check's
  `url`, else `/`.
- **Who writes it.** `JourneysOfAPlan.decide` at the start of TEST_AUTHORING: the screen tasks
  that answer for a check; when none does, every task that does. Their author call gets the
  paragraph `TestAuthorClient.journeyBrief` and, in a session, two more tools:
  `check_journey` (parses a draft, keeps a valid one, refuses a path that holds an earlier
  story's journey) and `texts_of` (below). A hand-in with no journey is asked for it once in the
  same conversation. In one reply the journey is one more entry of `files`.
- **Claimed like a test file, kept apart from them.** `Task.journeyPaths` (new field, beside
  `authoredTestPaths`, so nothing that compiles tests reads a YAML file). Committed with the
  run's tests. `acceptance_test` shows a worker the claimed journeys whole; `PathPolicy` refuses
  every write there, as before.
- **Backstop** (`JourneysOfAPlan.missing`): the plan changes a screen and no task claims a
  journey after authoring - the run parks at TEST_AUTHORING, naming the tasks and paths.
- **No `browser.serve`** (`JourneysOfAPlan.decide`): a story with a screen stops at the start
  of TEST_AUTHORING, before a test is written, with the lines to add.
- **Red check** (`GreenfieldWorkflow.journeyRedCheck`, after the tests' red check): a worktree
  at the run's START point (not the tests commit - the new tests are red and the build would
  stop on them), the contract's `compile` and `existing` commands, the application started, each
  journey made in the browser image. A journey that passes parks the run. So does "the
  application did not start" and "no browser in the container": found before the swarm.
- **Final integration** (`FinalIntegrator.makeJourneys`): once, after the last merge is green
  and the reachability check: every journey the tree holds - this run's and earlier stories'.
  Not per candidate, not per merge (owner). Could-not-run is a failure here, not a pass.
- **A failed journey is repaired once.** `Result.journeyFailure` names the claiming task and
  carries the failing step ("step 2 of 5 failed: `click ...` - ...", the steps done before it).
  `SwarmEngineImpl.repairAfterFailedJourney`: one ordinary repair round seeded from the task's
  chosen candidate with that as its evidence; a survivor is finished as usual, the earlier
  choice is set ARCHIVED, and FINAL_INTEGRATION runs again. `Task.journeyRepairAttempted` bounds
  it; a second failure, or no survivor, parks. Before this a final-integration failure could
  only park.
- **`texts_of`** (`TreeQueries.textsOf`, counted as a tree query): a type's string literals by
  member, with annotation arguments, no code - what a screen shows and what a route is called.
  Offered to the test author of a screen task only.
- **Run report.** `LookupMeter.Kind.JOURNEY_CHECK` (appended): `check_journey` calls, a column
  "journey checks". A worker's journey read is an acceptance-test read.
- `-Dswarmcoder.verify.journeys=off`: nothing is asked for, required or made.
- HamBook's contract copy (`~/.swarmcoder/inputs/hambook-logbook/verify.yaml`) has the
  `browser` block: serve from `hambook-server` on port 8080, ready on `/`. Not started here.

**Tests.** `AJourneyFileIsReadAndJudgedWithNoModelTest`, `TheTextsOfATypeAreAQueryTest`,
`AStoryWithAScreenIsProvedByAJourneyTest`, `TheTestAuthorWritesAJourneyForAScreenTest`,
`AJourneyThatCannotBeMadeStopsTheRunTest`; a case added to
`AWorkerReadsTheAcceptanceTestItMustMeetTest`. Changed for the new kind:
`ARoleAsksTheLanguageServerTest`, `HarnessRunReportTest`.

**What it cannot catch.**

- A screen written in server-side code (above): no journey is required for it.
- A weak journey. The red check only requires failure on the start tree; a journey that opens
  the new screen and looks at its title passes with a button that does nothing.
- The author picks the selectors of elements that do not exist yet. A wrong guess about what
  the framework renders (a label that is not the accessible name) costs the one repair round.
- The repair goes to the task that claims the journey. A fault in another task's file (the menu
  that should link to the screen) is outside its write set.
- Run with nothing to build: the unchanged tree is verified without its journeys.
- The red check builds the start tree with the full `existing` command: minutes per run with a
  screen, no tokens.

**Left.** A live run: what the author does with `check_journey` and `texts_of`, whether the
serve line starts HamBook in the container, what a journey costs in a worker's prompt.

## 64. A story about a screen with no journey, a chain link that counted the wrong tests, and what a worker's call is made of (run 89, 2026-10-07)

Run 89: local models, HamBook, one story ("the filter area shows only the seven filter fields").
DELIVERED: one server file (+2 -45) and one acceptance test. Nothing below was measured in a
live run; the browser part was made by hand in a container.

**1. No journey was asked for.** Section 63 read "has a screen" off the write set. HamBook's
logbook is drawn in the browser from a descriptor the server's service returns, so the plan
wrote `LogbookServiceImpl` only. Three rules now (`JourneysOfAPlan.decide`), none a model's, none
on wording:

- the write set names a screen (section 63, unchanged): a journey, always;
- browser-only code uses what the task writes, from the object graph of the start tree
  (`ReachableCode.Graph.usersThroughItsTypes`): a type the written file declares, or a type one
  of its types IS (the shared service interface the client calls, implemented in the written
  file): a journey, always. On run 89's start tree this answers "LogbookScreen.java uses
  LogbookService, which LogbookServiceImpl is". With no `browser.serve` it stops the run, as a
  screen in the write set does;
- the contract has `browser.serve`: every story is asked for a journey. Only when neither rule
  above holds for any task may the author answer in place of one, through the same path a
  journey takes (`check_journey`, or a `files` entry): the single line
  `noVisibleEffect: <why>` (`JourneyFile.waiverOf`). It is not written to the project; it is on
  the task (`Task.journeyWaiver`) and in the log ("TEST_AUTHORING: NO JOURNEY for task ...").
  Where the graph shows browser code using the change the answer is refused in the session and
  ignored by the backstop. `JourneysOfAPlan.missing` parks a story that has neither.

Why both: the graph rule alone misses a screen with no type in common with the server (a
script calling an address) and code behind the service (a store the service reads); the third
rule misses nothing without a record, and the graph rule is what keeps the recorded answer from
being given for a case like run 89. A server-rendered screen (section 63's gap) falls under the
third rule too.

Cost: every story of a project with `browser.serve` pays the journey paragraph in the author's
prompt (about 600 tokens a call), the journeys' red check (start tree built with `compile` and
`existing`, application started: about 2 minutes on HamBook, no tokens), the run at final
integration, and one repair round when the author's selectors are wrong. The recorded answer is
a model's sentence: a wrong one for code behind the service is not caught, only visible.

**2. Chain link 10 broke on a delivered run.** Not the corrected test and not the first
candidates' errors: `walkTheBuild` asked for more executed tests than the base commit holds
(section 60). A candidate's acceptance stage holds the files its task claims and nothing else
(`AcceptanceOverlay.reduceTo`: "cleared 2, placed 1"), so 3 of 3 ran against a base of 5.
Run 88 passed by having more new tests than old ones. Now
`AcceptanceFilesLink.ownTestsExecuted`: executed tests of the run's own test classes, by test
id; more than zero holds the link.

**3. Worker tokens: 502,575 in 27 calls.** The log has no per-call record, so the split is worked
out from the dispatch line, the tool lines and the server's cache counts:

| what | size | sent | share |
|---|---|---|---|
| opening, every call: project rules 2,883, knowledge brief 1,992, workflow rules 1,390, task 563, role 31 (estimated tokens) and about 30 tool definitions | about 8,500 | 27 times | about 230,000, 46% |
| the acceptance test, read once by each worker (9,755 characters: three long test methods, a nested class, 31 imports - already only the claimed methods and what they use) | about 2,500 | 25 later calls | about 62,000, 12% |
| whole-file reads of the one file being changed (3, 15,712 characters) and a whole-type `body_of` (5,556) | about 5,400 | 4 to 10 later calls each | about 35,000, 7% |
| everything else: build and test output, the tool calls' own arguments (member texts, two diffs), small lookups | | | about 175,000, 35% |

90% of it was served from the model server's cache (451,274 of 502,575).

- Changed: `remove_member` (`CheckoutCode.removeMember`). The story left two helpers unused and
  there was no way to delete a member: each worker replaced them with a stub, read the whole
  file and wrote a diff. That is 2 of the 3 whole-file reads, 3 edits of the unused helpers and 2 diffs - about
  4 to 6 of the 27 calls. The answer names the members of the file that still use the name.
- Not changed: `acceptance_test`. It returned what section 61 says; there was no unused helper
  to leave out.
- Needs the owner: the opening. 38 project rules go to every worker of every task on every
  call; this task touched one server file and was sent the client's rules too. Choosing rules
  per task needs a rule to carry where it applies (a module or path), which the analyst does not
  record today. Second: the workflow rules and the tool descriptions say the same things twice.

**The browser, by hand, no model** (`AJourneyIsMadeOnATreeByHandTest`, off unless given a tree):
HamBook's contract copy starts the application in `swarmcoder-worker-ui:latest`. On a copy of
the delivered tree: build 26 s + 52 s, journey 16 s, PASSED (Logbook menu link, Callsign field
visible, no text box named Export). On the run's start tree the same journey FAILS at its last
step, as a red check needs. Role selectors match what the framework renders.

**Tests.** `BrowserCodeUsingWhatATaskWritesIsFoundOnTheGraphTest` (run 89's shape: server-only
write set, client draws from a shared descriptor), cases added to
`AStoryWithAScreenIsProvedByAJourneyTest`, `TheTestAuthorWritesAJourneyForAScreenTest`,
`AcceptanceFilesLinkTest`, `CheckoutCodeTest`, `AWorkerChangesOneMemberWithoutTheFileTest`;
tool lists in `AWorkerUsesTheLanguageServerTest`, `WorkerScopeIsUnchangedTest`.

**Left.** A live run. The recorded answer is not yet a line of the run report. A per-call record
of what a worker's request held (opening, tool results by kind, its own earlier calls) so the
table above is measured, not worked out.

## 65. A worker is sent the rules for the part of the project it touches (owner's decision, 2026-10-07)

Section 64 measured the worker's opening at about 8,500 tokens on each of 27 calls for a two-line
change: project rules 2,883, knowledge brief 1,992, workflow rules 1,390, task 563, tools about
1,640. All 38 rules went to every worker; the task wrote one server file. Nothing below was
measured in a live run.

**1. A rule records where it applies.** `LearnedGuideline.appliesTo`: folders from the
repository root; a module is its folder, so there is one shape. Empty is the whole project, and
that is what every rule recorded before today is (new optional field, no enum touched). "Covers"
is whole path segments, both ways (`RuleScope`): `client` covers `client/src/...`, and a write
set of `server` is sent the rules of `server/src/main/java/app/store`.

- The analyst records it: `"appliesTo"` on a CONSTRAINT, shown to the operator as an
  `AppliesTo:` line of the proposal block. It is given the project's module folders under one
  heading (`RequirementsIntake.partsOfTheProject`; nothing when there are fewer than two) and
  told to leave it out when the document does not say.
- It is checked against the object graph, no model, no wording (`ProjectParts`): a folder is a
  part of the project when the graph holds a type or a build file in it or under it.
- **An unknown folder is not refused and not narrowed to the rest: the rule is recorded for the
  whole project**, with a warning. Too many readers costs tokens; a rule under a folder nobody
  writes would be told to nobody.
- The same at dispatch: a rule whose folder the graph no longer holds goes to everyone. No graph
  connected: every rule to everyone.

**2. Who is sent what.**

| who | rules | why |
|---|---|---|
| worker, first dispatch and repair | whole-project rules + rules covering the task's write set or the folder of its acceptance tests (`SwarmEngineImpl.rulesForWorkersOf`, `ProjectRules.briefingFor`) | it can write nowhere else |
| a task with no write set | all | it may be anywhere |
| story planner, architect, planner, test author, design reviewer | all, unchanged | they decide which files a piece of work touches; a rule hidden from them is a rule the plan never honours |
| judge | all, unchanged (`setActiveRules`; it already ranks by the diff, `JudgeRules`) | what a result is held to |
| rule check commands in verification | all, unchanged (`activeChecks`) | the same |

So narrowing what a worker is told does not narrow what its result is held to. The cost of a
rule recorded for the wrong part: the worker is not told, the judge still reads it, and a break
of a hard rule becomes a question about the rule (runs 53 and 55) instead of passing.

**3. The workflow rules said what the tool descriptions say.** Six paragraphs repeated, tool by
tool, what each tool returns and takes. They are now four sentences: the order to ask in
(lookup_api, ask_expert, request_skeleton), find_example, dispute_rule, and "not through the
shell". No tool removed or capped; every refusal and the read pause are still stated. The text
went from 4,165 to 2,671 characters before the shell note (counted from the source).

**4. The run report shows it.** Per task: "Rules sent to the workers: 12 of the 38 in force"
and "Worker opening ...: first dispatch about N tokens (per part)", from two spans recorded at
each dispatch (`worker rules|`, `worker opening|`). Estimated at 4 characters a token; the tool
definitions are not in the figure. Two totals that gave prompt and completion as one number now
give both: tokens spent on discarded candidates, and on redundant candidates.

**Expected, on run 89's shape (estimates, not measured).** Workflow rules 1,390 to about 1,015.
Rules 2,883 to whatever the scopes leave: about 1,900 if a third of the 38 are recorded for
another part, about 1,450 if half are. Opening about 8,500 to about 6,700 to 7,150 a call, so
27 calls send about 36,000 to 49,000 fewer prompt tokens of 502,575.
**HamBook's 38 rules have no scope until its technical document is applied again**; until then
only the 375 tokens of item 3 are saved.

**Tests.** `RuleScopeTest`, `ARuleIsRecordedForAPartOfTheProjectTest` (stated, checked, sent,
store reopened, judge and checks not narrowed), `AWorkerIsSentTheRulesForItsPartOfTheProjectTest`
(write set and test folder, the span, the workflow rules), a case in
`StatedRulesBecomeGuidelinesTest`, `HarnessRunReportTest`.

**Left.** A live run after HamBook's document is applied again. A store written before today is
read by the store's own handling of an added field, as `purpose` and `hard` were on 2026-10-01;
no test opens such a store. The Guidelines screen does not show or edit a rule's part. A rule
written by hand or proposed by a model has no part. The tool definitions (about 30) are not
measured in the opening figure.

## 66. A task planned before the type it uses, and four things the run cost for (run 90, 2026-10-07)

Run 90: local models, HamBook, one story ("start and end of a contact are typed as a UTC date
and time"). PARKED and stopped: 4,613,245 input and 249,501 output tokens, nothing delivered.
The assessment of the model as architect and planner is not kept in this repository. Nothing
below was measured in a live run.

**1. The plan ran "use `UtcDateTime` in two screens" before "create `UtcDateTime`".** The
planner wrote both edges the wrong way round (`{"from":"task-3","to":"task-1"}`), while the same
task's read set named `UtcDateTime.java`. `TypeDependencyOrder` logged "read as describing what
a later task builds on its work, which is normal" and kept the order.

- That reading is now taken only when it can be true: the type is in the start tree
  (`TypeDependencyOrder.StartTree`: a write-set file on disk, or a type `ProjectTypes` reads), or
  the later task itself uses a new type of the naming task (run 40's shape). Otherwise it is an
  objection with the pair and the edge to write.
- A read set entry that is the file of a type another task creates counts like a contract: the
  edge is added when nothing orders the two, and it is an objection when the plan orders them
  the other way.
- **Sent back, not turned round.** With no order between the two the edge is added, as before.
  Where the planner ordered them the other way it said two things that cannot both hold, and
  which one is wrong is not a mechanical fact; `check_plan` shows it inside the session.
- The plan's answer format said `"edges":[{"from","to"}]`. It now says which is the task that
  must finish first and which waits.
- The write-set side needed nothing: two tasks that name one file and have no order between
  them are already refused (`checkWriteSetDisjointness`); a test now says so.
- Cost: a prose mention of a type to come, in a task planned first, with nothing the later task
  uses of it, now costs the planner a turn. `PlanObjectionsSayHowTest` had exactly that fixture
  as "left as planned"; it now has both tasks name each other.

**2. Every first candidate failed for writing a file of a task not yet run, and a repair round
followed.** First round 1,970,345 input / 44,758 output tokens; repair round 640,071 / 52,320.
`FileOfATaskNotYetRun` (before the repair round, after `RepairCannotHelp`): every verified
candidate wrote the same source file outside the write set, and a task beside or after this one
owns it - no repair round, BLOCKED with a message that names the pair. One candidate alone
counts only when the task's read set names the file. Not built: reordering at run time or going
back to the planner from EXECUTING. The waves are fixed at the start and the tests are already
claimed by task, so the run still parks; item 1 is what prevents it.

**3. 29 of 29 rules had no part of the project.** Established: the analyst was given the three
module folders (`ProjectParts.of` on the run's start tree gives exactly them; now tested on a
real graph, it had only been tested on a list of file names); the field travels from reply to
stored rule; nothing dropped it. The stored proposals have no `AppliesTo` line: the model left an
optional field out every time. It also left out the required `purpose` on 28 of 29 (run 89: on
none of 38).

- The prompt now asks for the field on every rule, with `[]` for the whole project; an empty
  list is shown and stored as `AppliesTo: the whole project`.
- `RequirementsIntake.askWhichPart`: when the project has two or more module folders and no
  rule of the batch has an `AppliesTo` line, the analyst is asked once, by rule number. Only a
  folder that is exactly one of those given is recorded; anything else stays the whole project.
  One analyst call (about 9,000 input tokens in run 90's size).
- Left: whether this model then answers with parts is not known without a run. No re-ask for a
  missing `purpose`.

**4. The worker's knowledge brief: 4,787 tokens on each of 110 calls.** 19,150 characters:
libraries 760, documentation map 4,804, worked example 13,586 - four files of a reference chat
client, shown as "how to build" two screens the project already had. `Librarian.createsNoFile`:
a task whose write set is only files the tree already holds is shown no example; a short note
names `body_of` and `find_example` in its place (not blank, or the documentation slice, primer
and sources the example displaces would come back). On run 90's task about 13,100 characters
less: about 3,300 tokens a call, 360,000 input tokens over 110 calls (estimate).

- **Needs the owner.** A task that creates a new type still gets its example pasted (2,406
  characters for run 90's first task, up to 16,000). `AWorkerIsShownHowThisCodebaseDoesItTest`
  records why: without the code in front of it a model spent 467 turns disassembling jars and
  wrote nothing. Moving that example behind `find_example` is the rule of CLAUDE.md section 1
  against that measurement; not done.

**5. The architect: 45,697 input tokens a call.** Worked out from the log (lookup size times the
calls that followed it, 4 characters a token), of 1,279,519 input tokens: 12 whole files about
395,000; tree and language-server queries about 150,000; document sections and searches about
204,000; the opening about 183,000; the rest its own replies and the tool definitions. The 12:
both contact screens and the logbook screen (38,332 characters, 214,000 tokens), three
acceptance tests (21,763, 122,000), `AGENTS.md` (8,151), three classes of text constants
(9,110), the service interface (2,062), the verification contract (4,837).

- Tool gaps, fixed: `texts_of` was offered to the test author of a screen task only; every role
  has it now. `outline_of` refused a path from the repository root, which `read_file` takes
  (the test author then read an 11,485-character file whole); it takes both now.
- Not a tool gap: the screens, the tests and the interface are in the tree and `body_of`
  returns them; the expert, the same model, read the same code that way. The architect read
  four files whole in its second and third turn, against its instructions.
- No query: the verification contract (a YAML file at the root). Left.
- **Needs the owner.** Nothing in the architect's conversation was ever shortened: on a server
  that reports cache hits old results are left alone until they fill a quarter of the room
  (65,536 tokens here; section 53), and 88% of its input was cache hits. Counted as raw tokens
  that is the 45,697; counted as a cloud bill it is small. Which to count decides the mark.

**Tests.** `APlanDoesNotRunATaskBeforeTheTypeItUsesExistsTest`,
`NoRepairRoundWhenEveryCandidateWroteAFileOfATaskNotYetRunTest`,
`WhatRun90sRolesCouldNotAskTheTreeTest`; cases in `StatedRulesBecomeGuidelinesTest`,
`ExamplesReachEveryRoleTest`, `AWorkerIsShownHowThisCodebaseDoesItTest`; fixtures in
`PlanObjectionsSayHowTest`, `TheExpertLooksThingsUpItselfTest`.

**Seen in passing, not touched.** Both first candidates' browser stage "could not be attempted:
port 8080 is already in use on this machine". The design's descriptor and service method are
used by no screen; the reachability check cannot see an uncalled member (section 62).

## 67. Old lookup results are shortened at the ordinary threshold on every server (owner's decision, 2026-10-07)

**Reverses the cache exception of section 53.** There, on a server that reports prompt-cache hits,
a role's old lookup results were left whole until they filled a quarter of the model's room
(65,536 tokens in run 90), because a tidy makes the cached head of the conversation full price
again. The owner judges runs by raw input tokens, not by what a cache discounts.

**Measured (run 90).** The architect averaged 45,697 input tokens a call over 28 calls,
1,279,519 input tokens in all; 88% were cache hits, and nothing in its conversation was ever
shortened.

**Changed.**
- `LookupAgent.TIDY_ABOVE_TOKENS` is 20,000 (was 24,000). `swarmcoder.roles.tidyAboveTokens` still
  overrides it; set it to a large value for the old behaviour. `0` switches tidying off.
- `AgentRuntime.SessionOptions` lost `tidyAboveWhenCachedTokens`, and `KoogAgentRuntime.tidyIfAsked`
  no longer looks at `UsageTap.serverCaches()`: one mark for every server.
- The small-room rule stays (a quarter of a room under about 80,000 tokens, section 51); it is
  not about caching.
- Workers never had the cache rule: `WorkerLoop` passes the plain 12,000 mark. Unchanged.

Run reports keep input and output tokens separate.

**Tests.** `ARoleLearnsTheProjectFromItsObjectGraphTest` (the cache case: the options carry the
ordinary 20,000 mark on a server reporting cache hits), `ARolesSessionTidiesItsOldLookupResultsTest`.

## 68. A correct run refused for code "nothing can reach", and first lines nothing ever shortened (run 93, 2026-10-08)

Run 93: the bookshelf demo (three modules, one class each), story "search for a book by title
or author". Design, plan, tests, four tasks, one repair round, then PARKED at FINAL_INTEGRATION.
Nothing below was measured in a live run.

**1. The refusal was a false alarm.** It named `BookServiceImpl` (as `ListAll`, a class nested
in it), `DefaultDataRootProvider` and `DataRoot`. In the kept repository both classes carry the
framework's bean scope annotation, the server's bean archive is scanned for annotated classes
(its descriptor was in the start tree and says so), the service implements the shared service
interface whose generated stub the new screen calls, and the screen is built from the client's
`main`. `DataRoot` is what the provider returns. All of it is wired. `ReachableCode` learns
discovery from unused code that was there before; the server module held one `main` that hands
over to a library, so there was nothing to learn and only the shared module's `@DataModel` was
known. Cost: both first candidates of the last task failed on it, a 30-minute repair round
followed (the winner changed whitespace in the server's `main`, which the plan had put in
that task's write set to be "wired", with nothing there to wire), and the run parked.

- **Changed.** A source root where none of the code that was there before is
  framework-discovered shows nothing to compare with. An added file there that nothing uses and
  whose type carries an annotation (not `java.lang`'s, not one the run added) is taken as found:
  not an orphan, what it uses is reached, and it is named in `Finding.takenAsFound`. Final
  integration sends the sentence to the run's messages (`FinalIntegrator.tellingTheRun`), a
  candidate's goes to the log. The plan check believes a new file in such a root when its task
  names an annotation. No framework name, annotation list or wording is involved. On run 93's
  merged tree: ALL_REACHABLE, both classes named as not established.
- **Chosen against.** Reading annotation-processor output or `META-INF`: the build has not run
  where the plan is checked, and the descriptor that matters here names no type. "Declared in a
  dependency with a scanner": the tree cannot tell a scanner from any other jar. Not judging
  the whole root: a class with no user and no annotation would pass.
- **Still missed.** In such a root any annotation excuses a class nothing uses, one only a
  compiler or generator reads included. A root that already shows one kind of discovery still
  refuses the first class of another kind, and a class found by its supertype alone.
- `ReachableCode` named the first type the tree lists for a file; it now names the outermost.
- **Seen, not changed.** A repair candidate is asked only about files its own change adds, so
  after a real refusal a repair that changes anything else passes the candidate check and the
  run parks at final integration (section 66 "left" still stands: that stage has no repair).

**The journey.** Written (`bookshelf-search.journey.yaml`, fill the search box, expect a title),
checked by `check_journey`, claimed by the last task. Its red check ran in the browser
container and failed on the start tree at step 1, as it must. It was never made on the merged
tree: the reachability refusal comes first. As written it cannot pass there: it looks for a
text box named "Search books by title or author" where the screen sets the placeholder
"Search books...", and it expects a book's title in the list while the store starts empty and
no step adds a book.

**2. The architect: 34,982 input tokens a call, 43 calls, 1,504,262 in and 39,932 out.** The
mark is applied: it was tidied at turn 19 (35,699 to 18,655 estimated) and turn 39 (47,668 to
37,664); the planner once, the test author once. Worked out from the session's log lines
(lookup sizes, the two tidy lines, "39,098 tokens held" at turn 33; 4 characters a token):

| what | a call | all 43 | share |
|---|---|---|---|
| system prompt 1,525 and opening 3,455 | 4,980 | 214,000 | 14% |
| 30 tool definitions (the server's count less the conversation's estimate, so it holds the estimate's error too) | about 6,500 | about 282,000 | 19% |
| lookup results held, whole or as first lines (146 lookups returned 200,878 characters) | about 18,000 | about 774,000 | 51% |
| its own calls and two design drafts, the reviewer's objections | about 5,400 | about 234,000 | 16% |

So it is not opening plus 20,000. The defect: a shortened result keeps 600 characters and a
sentence, about 200 tokens, results under 1,000 characters are never shortened, and nothing
counted or cut either. By the second tidy the 134 results held were about 20,000 tokens in that
form, which is why it could take only 10,004 off.

- **Changed** (`HistoryTrim.tidy`, so workers and the expert too): first lines kept by an
  earlier tidy count toward the mark, and at the next tidy they become one line that names the
  call and its size, before any whole result is touched. Estimated for this session: about
  3,000 fewer input tokens a call; more in a longer one, where the floor kept growing.
- **By design, with what would cut it.** About 11,500 a call is fixed (opening and tool
  definitions): only fewer calls or fewer tools offered to a role cut it. Up to 20,000 of whole
  results, the last four turns and anything asked for twice stay: the workers' figures
  (`-Dswarmcoder.roles.tidyAboveTokens=12000 -Dswarmcoder.roles.tidyToTokens=3000`) would take
  roughly 5,000 to 8,000 a call off. The architect made 150 lookups for a three-class project
  and was stopped four times for making the same call a fourth time.
- **Left.** A per-call record of what a role's request held, so this table is measured.

**3. The demo's contract** had no `browser` block; it has the one proved in the container now
(the demo's own repository). The public repository does not hold the demo: it ignores
`dev/bookshelf-demo/`, ships its two requirement documents and a script that expects it, and
no document says where to get it.

**Tests.** `TheFirstDiscoveredTypeOfAProjectIsNotRefusedTest`; cases in
`ARunThatAddsCodeNothingReachesIsNotDeliveredTest`,
`OldLookupResultsAreReplacedByTheirFirstLinesTest`.

## 69. A failed journey goes back to its author first; a journey must create what it expects (owner's decisions, 2026-10-08)

From run 93 (section 68): the journey looked for a text box named "Search books by title or
author" where the screen set the placeholder "Search books...", and expected a book's title on
a store that starts empty. No worker could repair either. Nothing below was measured in a live
run. The page reading was made once by hand in the browser image on a static page; the rest is
tested with a scripted model and with what a browser would answer given as a function.

**1. Back to the author before any worker** (`FinalIntegrator.JourneySendBack`,
`GreenfieldWorkflow.sendJourneyBackToItsAuthor`, `TestAuthorClient.reviewFailedJourney`).

- The page checker (`check.js`, `pageSeen`) reads the page when a step fails: elements by role
  and accessible name (at most 100), the fields' placeholders with their labels (20), visible
  text (1,200 characters), 4,000 characters in all. No model. It travels as one more entry of
  the page's assertions (`page-seen`, marked passed), so no stored type changed, and is
  `JourneyFile.Result.seen`.
- The author is asked in its own session - the conversation that wrote the journey when it is
  kept - with its lookups, `texts_of` and `check_journey`; without a session, in one reply. Its
  opening holds the journey, the failing step and the page reading: evidence of the failure,
  bounded, not project material. No new tool, so the lookups-by-kind table needs no new kind
  (`check_journey` is a journey check, `texts_of` a tree query, as before).
- What it came to is read from what the session did: a journey `check_journey` called valid
  at the journey's own path is a correction; a hand-in with nothing checked is the author
  standing by its journey; neither is no usable answer.
- A correction is taken only when `JourneysOfAPlan.correctionRefused` finds nothing, cheapest
  check first: well formed; not weaker by its form (`JourneyExpectations.weakened`: at least
  one fill, click or press, and no fewer such steps and no fewer fills than the journey it
  replaces); passes on the merged tree, in the container that still holds it; fails on the
  start tree (`journeysOnTheStartTree`, the red check's own build), and its last expectation
  alone does not already pass on the entry page there. Then it is committed on the run's tests
  ref and the integration is made again from the start. No worker is started.
- Anything else leaves the journey as written, and the repair round of section 63 runs with
  the author's answer added to its evidence (`JourneysOfAPlan.repairEvidence`).
- Once per task (`Task.journeySentBack`); the outcome is `Task.journeyReviewNote`, in the log
  and in the run report under the task, with what the journey ended on before and after.
- The author's endpoint being down pauses the stage, as for a repair round; the task is not
  marked as asked.

**What a weakened journey still gets through with.** As many steps, ending on something the
story adds but the criteria do not ask for (the new screen's heading in place of the search
result): it fails before and passes after. Only the note shows it. Also not checked: an ending
of `expectHidden` on the start tree's entry page (something not there is hidden everywhere);
the application's data between the failed journey and the correction in the same container
(the correction runs on what the first attempt left); a task with two failed journeys is asked
about both in one go and marked once.

**2. A journey must create what it expects** (`JourneyExpectations.unentered`,
`ProjectTexts.heldIn`). An `expectVisible` text (`text=...`, `:has-text("...")`; not a role's
name, not a pattern) that no earlier `fill` value contains or is contained in, and that no
string literal of the project's shipped Java code and no page or message file holds, is either
shown by the new screen itself or is data nobody entered. `check_journey` asks once and does
not keep the draft; the same file given again is the author's answer that the screen shows it,
and is kept (logged). The brief and the tool's VALID answer say the application starts with no
data of its own. The journeys' red check logs a NOTE for such a journey. Cost: a journey that
ends on a new label costs its author one more `check_journey` call. Not seen: data expected
through a role's name (`role=cell[name="..."]`); a journey handed in as a file in one reply is
not asked, only noted at the red check.

**3. Selectors.** `SwarmDispatcher.buildBundle`: a task that claims a journey gets one sentence
- the screen must expose exactly the roles, accessible names and texts the journey's selectors
use. The repair evidence says the same. Journeys are still not made per candidate.

**4. A refusal at final integration returns to the owning task once**
(`FinalIntegrator.OwnedRefusal`, `GreenfieldWorkflow.repairAfterOwnedRefusal`): for added code
nothing can reach, the task whose chosen change added the first file named gets the one repair
round a failed journey gets, with the refusal as evidence (`Task.integrationRepairAttempted`).
A repaired candidate is still only asked about files its own change adds (section 68), so a
repair that does not fix it is refused again at final integration and the run parks. Other
final-integration failures (a merge conflict, a red verification after a merge) name no single
task and still park.

**Not exercised by any test:** the two call sites that need a container with a browser - the
send-back inside `FinalIntegrator.makeJourneys` and `sendJourneyBackToItsAuthor` with its
commit - and `repairAfterOwnedRefusal`. They compile and follow the paths of section 63.

**Tests.** `WhatAJourneyExpectsIsJudgedWithNoModelTest`,
`WhetherTheProjectHoldsATextIsAskedWithNoModelTest`, `AFailedJourneyGoesBackToItsAuthorTest`;
cases in `AWorkerReadsTheAcceptanceTestItMustMeetTest`, `HarnessRunReportTest`.

**Left.** A live run with a screen.

## 70. The first real review of a failed journey: misread, long, sent to the wrong task, and a stage that could not run twice (run 95, 2026-10-08)

Run 95: local models, a small demo with a server module and a browser module, resumed from a
plan and tests saved before the workers. It parked at FINAL_INTEGRATION. Nothing below was
measured in a live run; the review is tested with a scripted model, the integration on a real
git repository.

**What the author did in its 22 calls** (about 55 minutes; 446,265 input tokens, 71,667 output
tokens; 56 lookups, no `check_journey`). Its lookups read the project as it was before the
story. `texts_of` for the screen's type answered "no such type", so by its third call it had
decided the screen was not built. The opening led with the task that claimed the journey, a
server task, so it then set about writing a server test: it read the contract, four build
files, an example project's service, commands, queries and a 19,574-character screen, the
shapes of the store's types, and listed the same folders fourteen times. Lookups by kind: 16
whole files (63,624 characters), 16 listings (5,442), 3 searches (12,516), 9 tree queries
(6,632), 11 language-server queries (3,178). It ended on a paragraph that began "The browser
journey ... was wrong" and described a JUnit test it could not hand in.

**1. The answer was misread.** "Nothing checked" was read as "stands by it", and the workers
were started. Now the review ends on `report_done(verdict, reason)` with verdict
`JOURNEY_WRONG` or `SCREEN_WRONG`. `TestAuthorClient.JourneyVerdict`, decided in
`TestAuthorTools.reviewOutcome` from the verdict and what `check_journey` kept, never from the
reason's words:

- CORRECTED: a kept journey that is not the original again, and the screen not named as wrong;
- STANDS_BY: `SCREEN_WRONG`. The repair round follows, with the reason;
- COULD_NOT_CORRECT: `JOURNEY_WRONG` and nothing kept. Asked once more in the same conversation
  (`journeyCorrectionMissing`) first. Then the run parks (`FinalIntegrator.SentBack.disowned`,
  `JourneysOfAPlan.disowned`) and no worker is started. A correction the browser guard refuses
  parks the same way: before, the first journey "stood" and went to the workers;
- UNANSWERED: the session died or named no side. The journey stands and the repair round
  follows, as before.

A parked task is not marked as asked, so a resume asks the author again.

**2. The review is bounded** (`TestAuthorClient.reviewJourneyInSession`). Ten turns
(`JOURNEY_REVIEW_TURNS`): two of lookups, three of `check_journey`, one to answer, two for the
re-ask, two spare. Twelve lookups, one expert question. No lookup tool was removed. The opening
asks one question, gives the story's criteria and the owning task's write set, and says what
the lookups read. `texts_of` answers from the merged tree (`TreeQueries.textsOfIn`: the file of
that name, no index). **Changed from section 69:** the review no longer continues the
conversation that wrote the journey. That conversation's stops (120 turns) are fixed when it
opens and count across all of it, so a review inside it cannot be bounded.

**3. The journey was claimed by the wrong task.** The only task with a check wrote the server,
so its author wrote the journey and that task claimed it; the screen was another task's.
`JourneysOfAPlan.settleOwners`, from write sets and the build survey only: a journey stays with
the task it was written with when that task's write set names a screen; otherwise it goes to
the last task in the plan's order whose write set does; with no such task it stays. Who is
ASKED for a journey (section 64) is unchanged. Run at TEST_AUTHORING, and once per run at
EXECUTING and FINAL_INTEGRATION, so a plan saved before this rule is put right on resume. Only
the claim moves. Not seen: which of several screen tasks wrote the element a step names.

**4. Final integration could not run twice.** `git worktree add -b` refuses an existing
branch, so every second attempt ended on "Integration setup failed". The return paths of
sections 63 and 69 had never run to their end. `FinalIntegrator.openIntegration`: the earlier
branch is renamed `swarm/integration-attempt/<run>/<n>` (`GitService.setBranchAside`) and the
new attempt is cut from the tests commit. Nothing deletes the kept branches.

**5. `acceptance_test` answered a wrong name with two lines.** A repair worker asked it for its
module and for the implementation's class, then for the test class, and never saw the journey.
A name nothing claimed carries now returns everything claimed; a test class asked by name adds
one line naming the journey; `JourneysOfAPlan.repairEvidence` holds the journey step by step.

**Seen, not changed.** The model server stopped answering once; the run paused and went on by
itself. The page reading at the failing step showed only a loading text: the screen may not
have drawn within the step's 10 seconds. Not looked into.

**Not exercised by any test:** `sendJourneyBackToItsAuthor` (it needs a container with a
browser), so the park on a disowned journey is tested as its parts; `settleJourneyOwnersOnce`
at EXECUTING and FINAL_INTEGRATION; the whole loop of integration, repair round, integration.

**Tests.** `AJourneyIsOwnedByTheTaskThatWritesTheScreenTest` (new); cases in
`AFailedJourneyGoesBackToItsAuthorTest`, `FinalIntegratorTest` (a real repository, the same
run integrated three times; a worktree left by a killed attempt),
`AWorkerReadsTheAcceptanceTestItMustMeetTest`.

**Left.** A live run with a screen.


## 71. A journey that fails again at a later step goes back to its author a second time (run 97, 2026-10-08)

Run 97: a two-step journey (fill a search box, expect a book title) failed at step 1; the page
had stayed on its loading text. The author answered `SCREEN_WRONG`, truthfully. The repair round
fixed the screen, final integration ran again, and the journey failed at step 2: it expects a
book on a store that starts empty, and no step adds one. That is a fault of the journey, but the
task had been asked once (section 69), so the author was not asked and the run parked.

**Rule** (`JourneysOfAPlan.goesToItsAuthor`, decided from step numbers only). The browser's
result carries the failing step's number (`JourneyFile.Result.step`, 0 when the failure is not a
step's). Each review is recorded on the task as `<path>|<step>` (`Task.journeyReviews`). A
journey never reviewed goes back once per task, as before. A journey reviewed before goes back
again only when it now fails at a LATER step than at its last review; a failure at the same or
an earlier step does not, and no journey is reviewed more than twice in a run. The second review
has the same opening, bounds and guards as the first (section 70: verdict, a kept correction
must pass on the merged tree and fail on the start tree, at least as many acting steps).

**What follows.** A correction is committed with the run's tests and the integration is made
again; no worker. The worker repair round stays one per task. If the second review does not
produce a taken correction (`SCREEN_WRONG`, no usable answer, `JOURNEY_WRONG` without a kept
correction, or a correction refused), the run stops without a worker, and the message carries
both reasons (`JourneysOfAPlan.reviewedTwice`). Both reviews are in `Task.journeyReviewNote`
(the second begins "SECOND REVIEW") and so in the run report. A task marked as asked by an
earlier version has no recorded steps and is not asked again.

**The authoring-time check of section 69 cannot tell a record-creating field from a search
field.** In run 97 step 1 typed the word the last step expects, so "typed by an earlier fill"
held. Telling the two apart needs the screen, which the check does not have. Instead the VALID
answer of `check_journey` says that typing a text into a search or filter box does not create it
and that the journey must first add the record through the screen when the store starts empty.

**Not exercised by any test:** `sendJourneyBackToItsAuthor` as a whole (it needs a container
with a browser); the decision, the step number and the stop message are tested on their own.

**Tests.** Cases in `AFailedJourneyGoesBackToItsAuthorTest`.

## 72. A run stopped for a library that was an adjective; what the planning roles' calls were made of (run 98, 2026-10-08)

Run 98 built a small new project from two documents, every role on the local model, and parked
at PLAN: "it uses `TeaVM-compilable` ... Install `TeaVM-compilable` into that repository".

**Cause.** `RulesVersusManifest` takes the token after "through / module / dependency / uses /
via" for a dependency's name. A rule's purpose line read "... and only uses TeaVM-compilable
classes". `BuildFilesInTheJob.declareMissing` parked on every such name the local repository's
catalog did not hold. The name was decided to be a dependency from the wording alone.

**Rule** (`BuildFilesInTheJob.resolvesToAnArtifact`). The extractor is unchanged and only
proposes names. A name is a missing dependency when it resolves to something a build can declare:

| the name | what happens |
|---|---|
| managed by an inherited BOM or parent pom, files in the local repository | work in the plan (as before) |
| managed by an inherited BOM or parent pom, files not there | the run stops; the message gives group, artifact and version (`Catalog.managedNotOnDisk`) |
| written with its group, `group:artifact` (`Finding.group`) | the run stops if it is not declarable; a person can install coordinates |
| anything else | `PLAN: note — ...` in the run's log; nothing added, nothing stopped |

No word list and no test of how a token is spelled. **Still caught:** the case the check was
built for (run `ede2068b`, 2026-09-03, recorded in the javadoc of `RulesVersusManifest` and
`BuildFilesInTheJob`, not in this document: a rule names the persistence artifact, the BOM
manages it, the server pom does not declare it, and no worker could add it), with the files
present or absent. **Let through:** a bare name that no inherited
dependency management knows, including a real library whose BOM is itself not in the local
repository. Nobody can be told what to install for a name with no group, so a stop for it could
not be acted on; the note says to write `group:artifact`.

**Checked against the saved stage** (the run's project files after design, its stored rules,
this machine's Maven repository): two names found, the persistence artifact becomes a
declaration in the server module's pom, the adjective is a note, no park.

**What the calls were made of.** The server's totals per role are exact. The split is an
estimate: output tokens by the time each phase took, at the role's own measured rate; input
tokens by what the conversation held when the drafts were written (about 59,000).

| role, phase | calls | input tokens | output tokens |
|---|---|---|---|
| architect, lookup turns (129 lookups) | 47 | about 1,920,000 | about 32,800 |
| architect, draft 1 (7,516 chars), refused by `check_design` | 1 | | about 7,500 |
| architect, draft 2 (7,674 chars), refused with an answer of the same length | 1 | | about 2,400 |
| architect, draft 3 (7,919 chars), passed | 1 | | about 2,500 |
| architect, hand-in | 1 | | about 2,600 |
| architect, revision after the reviewer (10,050 chars), passed, and hand-in | 2 | | about 4,100 |
| architect, drafts and hand-ins together | 6 | about 350,000 | about 19,100 |
| **architect** | **53** | **2,268,502** | **51,879** |
| planner, story planning before the run | 2 | | about 8,600 |
| planner, lookup turns (78 lookups) | 28 | | about 26,900 |
| planner, the one draft (6,003 chars), passed first time, and hand-in | 2 | | about 8,400 |
| **planner** | **32** | **1,154,233** | **43,936** |

- **Send-backs: two for the architect, none for the planner.** `check_design` refused drafts 1
  and 2 with an answer of the same length, 622 characters; which check spoke was not logged, so
  whether its wording could be acted on cannot be established. Now logged (`DraftTools`). The
  design reviewer sent the design back once, with two objections: the design had no screen
  (right, and the revision added one), and a check about a list on a screen was proved at the
  service (what the architect had been told to do, because no test can execute the browser
  module). The second came back after the revision and was recorded. The reviewer is now told
  the same fact (`AcceptanceTestReach.reviewerBrief`, `DesignReviewerClient.review` with a build
  fact).
- **Most calls were lookups, and that is the job on this model.** A new project on a library the
  model does not know: 34 of the architect's 49 whole-file reads (254,990 of 305,327 characters)
  and 33 of the planner's 44 were the reference project's examples and sources. A lookup turn
  cost about 700 output tokens for the architect and 960 for the planner, nearly all reasoning.
- **Not changed, measured for a decision.** (a) After old results were shortened (section 67),
  the architect made 23 lookups again (147,919 of 403,254 characters returned to it) and the
  planner 9 (43,353 of 212,348); 6 architect calls and 2 planner calls did nothing else. The
  mark is the owner's decision and `-Dswarmcoder.roles.tidyAboveTokens` already sets it per
  run. (b) 31 of the planner's 69 distinct lookups (89,075 of 168,872 characters) were ones the
  architect had made minutes before; nothing carries a lookup from one role to the next. (c) A
  revision writes the whole design again (about 2,800 output tokens here); a partial revision
  needs a patch format for decisions and contracts and was not built. (d) `report_done("")`
  already hands in the checked draft without writing it again; the call itself remains.

**Tests.** `ANameInARuleIsADependencyOnlyWhenABuildCanDeclareItTest`,
`TheDesignReviewerKnowsWhatATestCanExecuteTest`; existing `BuildFilesInTheJobTest`,
`TheSwarmDeclaresItsOwnDependencyTest`, `RulesVersusManifestTest`,
`TheDesignReviewerPutsRulesAboveTheGoalTest`, `AnAcceptanceTestMayNotCallBrowserOnlyCodeTest`.

## 73. The architect hands its findings to the workers, the planner only splits and orders, and a task's files are a computed reservation (owner's decisions, 2026-10-08)

The owner: "The task planner should not write how-tos. That is the architect's job at the high
level and the worker's at the low level. The smart architect provides the information the worker
needs, so the worker's weaker model has a fighting chance; the worker may also look things up,
but it must be primed. And I question whether the task planner wrongly restricts which files
workers may change." On tokens: input is cheap on the local server; output tokens, calls and
repair rounds are the cost; a worker is not to be starved of useful information.

What was true: nothing of the design reached a worker. The planner wrote about 600 tokens of
how-to per task after looking the framework up a second time (run 98: 78 lookups in 28 calls, 31
of 69 distinct ones a repeat of the architect's, 33 of 44 whole-file reads framework examples).
Write sets were the planner model's guess, and a source file outside one failed the candidate
(run 90 lost a build to it). Nothing below was measured in a live run.

### A. The architect's findings

- **A finding** (`DesignFinding`, on `DesignDocument.findings`): what it is about (a contract's
  name or type name, or nothing for the whole project), the lookup it came from, one sentence,
  and lines of that lookup's result.
- **Recorded by marking a lookup, not by typing the fact** (`DraftTools.keepForWorkers`, tool
  `keep_for_workers(about, lookup, lines, note)`, architect only). `lookup` names a lookup made
  in this session (`ExpertTools.resultOf`); `lines` is `first-last` within its result, empty for
  all of a short one, `none` for the sentence alone. The tool copies the lines. **Decision:**
  this over a tool that takes the fact as text. A finding costs the architect about 30 output
  tokens instead of the code a second time, and a finding cannot carry code no lookup returned.
  Cost: the architect has to count lines of a result; a narrower lookup (one member) is the
  way round, and the tool says so when it cuts.
- **Bounds at recording:** 20 lines and 1,600 characters of code, 400 characters of sentence,
  40 findings a design (`swarmcoder.handover.maxFindings`). A lookup that was never made is
  refused with the session's latest lookups listed.
- The design prompt has a step for it (`DESIGN_HOW` 2). A clean `check_design` with nothing kept
  says so once; it is not an objection. A revision keeps every finding and adds the new ones
  (`ArchitectClient.mergedFindings`); findings survive the two places the workflow copies a
  design.
- **Which task gets which** (`ArchitectHandover`, no model, run when the plan is accepted,
  stored on `Task.architectFindings`): findings about a contract the task delivers; about a type
  whose file its write set names; about the whole project; about another contract of the design
  that a member of the task's own contracts names as a type. In that order.
- **Bound per task: 12,000 characters** (`swarmcoder.handover.maxChars`), about 3,000 tokens.
  A finding is never cut. One that does not fit whole is given as its sentence and lookup; one
  that still does not fit is left out and counted. So the least relevant goes first.
- **Where they go.** The worker's opening, under the task and above its files
  (`SwarmDispatcher.buildBundle`), on first dispatch and repair. The test author's authoring
  call and its re-ask (`TestAuthorClient.established`). `designSummary` carries one `FACT` line
  per finding without the code, so the planner and the design reviewer read them.
- **The librarian's brief is the fallback** (`Librarian.coveredByTheArchitect`): where the task
  carries a finding with code and every contract it delivers has a finding, the worked example
  is not pasted (a short note stands in its place, so the channels an example displaces do not
  come back). Otherwise the brief is what it was. Workers keep every lookup tool.
- **Run report:** per task "Architect's findings given to the workers: first dispatch N
  finding(s), C characters, about T tokens", from a span recorded at each dispatch
  (`worker handover|`). The PLAN log says per task how many it was given, how many without
  their lines, how many did not fit, and which subjects no task builds.
- **Not prompt-stuffing.** CLAUDE.md section 1 forbids pasting files and inventories to save a
  lookup. A finding was selected by the architect for this design, names its lookup, is copied
  by a tool, and is bounded twice. CLAUDE.md section 1 now says where that line is.

### B. The planner

- Prompt (`PLAN_SYSTEM_PROMPT`, `PLAN_HOW`, `LOOK_IT_UP`): instructions are one to three
  sentences on what the task delivers; no how-to; it is told the findings reach the workers
  without it and that it need not work out a task's files.
- The planner is sent neither the framework reference nor example code, with tools or without
  (`exampleBlock` and `referenceBlock` are gone from the plan calls).
- **Decision: no lookup tool was taken from the planner.** It is no longer told to read
  documentation and examples. CLAUDE.md section 2 says not to cap what a role may look up, and
  the per-role lookup counts will show whether it still does. Removing the documentation tools
  from its session is one line in `LookupAgent.run` if a live run shows it reading them.
- The contract statement appended to instructions (`withContractBrief`) stays: it says what to
  deliver, not how.
- Every deterministic plan check is unchanged.
- **Who read `Task.instructions` as how-to:** `PlanConnectsWhatItAdds` took "its task says the
  type carries a discovery annotation" from them; it now also reads the findings about the
  task's contracts. The Librarian uses them as search words for a brief that is now the
  fallback. Judge, design reviewer, `ForbiddenTechGuard`, `TypeDependencyOrder`, the Console
  and the run report read them as a statement of the task and needed nothing.
- **The planner's model:** `roles.taskPlanner` (`RolesConfig`, `RoleClients`, the roles form).
  Unset is the architect's client; no default changed. `ArchitectClient.setPlannerClient`
  sends the plan session and the one-reply fallback there.

### C. Files

**Computed** (`ComputedReservation`, before `BuildFilesInTheJob.expandWriteSets`, on every
attempt and on `check_plan` drafts; recorded on `Task.computedReservation`). For each contract a
task delivers: the tree's file for an existing type; `<source root>/<package>/<Type>.java` for a
new one; every existing file `ChangeBreaksExistingCode.brokenBy` says stops compiling (an
abstract method added to an interface or abstract class, a component added to a record). The
source root of a new type is the one a path of the planner's write set lies in, else the one
that holds its package or the nearest package above, else the build's only Java source root;
otherwise nothing is computed and the log says so.

**Reconciling the planner's paths.** Kept, except: a file path named like an existing contract
type where the tree does not have it and no file is, is dropped; a file path named like a new
contract type whose folders are not its package is replaced by the package's path under the same
source root. Nothing is taken from another task: one file computed for two unordered tasks is
the existing disjointness objection; two ordered tasks may share it.

**Extended by rule** (`ReservationBook`, one per run, held by the engine, asked through
`PathPolicy.OtherTasks` at every write of a worker and again at verification):

| the file outside the task's reservation | what happens |
|---|---|
| protected (acceptance tests, journeys, `.swarmcoder/`, `.git/`, locked modules, outside the repository) | refused by `PathPolicy.check` before the plan is asked, counted toward the stop, as before |
| held by no other task | written; it is the task's from then on, so a task built at the same time is refused it; recorded on the candidate; on `Task.takenBeyondPlan` and in the write set when the candidate is selected |
| held by a task of the same wave | refused, the task named; not counted toward the stop |
| held by a task of a later wave | refused, the task named; remembered. When no candidate passes and the worker of every verified candidate was refused the same file (two, or one when the read set names it), `FileOfATaskNotYetRun` blocks the task before the repair round and blames the plan |
| held by a task of an earlier wave | written: that work is merged and the checkout is cut from it |
| a build file, a non-source file | nobody's, as before |

A shell command's change to a held file is put back by the existing audit. At verification
`SourceOutsideWriteSet.objection` fails a candidate only for a source file another task holds.

**Decisions.**

- *Earlier tasks' files are free.* `SiblingDefects` asked for evidence before one widening;
  that path still exists and is reached less. What protects merged work is unchanged: delivered
  contracts are checked, removed public code fails verification, final integration runs every
  test.
- *A later task whose reservation an earlier extension overlaps.* It cannot happen through the
  plan: a file a later task reserved is refused to the earlier one. It can only be the later
  task itself taking a file an earlier task took, and it builds on the merged result. No
  reservation is re-derived during a run.
- *Repair rounds* use the same book: what the first round took stays the task's.
- *Resume from an older snapshot.* Old tasks have the planner's write sets only; they are used
  as they are, the book is rebuilt from the plan on first ask, and nothing is recomputed (the
  tests are already written against that plan). The new fields are null and read as empty.
- *Final integration* audits against write sets, which now hold what winners took.
- *The first candidate to take a free file holds it for its task even if that candidate later
  fails.* A task of the same wave may then be refused a file nobody ends up changing. Chosen
  over a merge conflict between two winners.

**Containment (section 13).** Unchanged: every refusal `PathPolicy` made it still makes, first
and in the same words. The new question is put only for a path that used to be written and
recorded anyway, and is answered from the plan in the engine's memory, never from the worker's
tree. `WorkerToolbox` stays in the trust kernel.

### Not covered by a test

- The whole path in a live engine: a worker taking a file, the selected candidate's files landing
  on the task, two tasks of one wave reaching for one file. The parts are tested
  (`ReservationBook`, the toolbox with a book, `growReservation`, the verdict).
- A shell command changing a held file (the audit branch that puts it back).
- The `worker handover|` span being recorded at dispatch; the report line is tested from a span.
- `roles.taskPlanner` from `config.yaml` to the client (the client switch is tested).
- A store written before today being opened with the new fields absent.
- The architect's session continuing after a send-back with findings from both rounds.

### Left

- Callers of a member that is removed or whose parameters change are not computed: a contract
  does not say what was removed. The extension rule covers them when nobody else holds the file.
  The language server's `callers_of` would compute them; it is not running at PLAN.
- The test author is given a task's findings and also the `FACT` lines of the whole design, so
  a task's own sentences appear twice there.
- The Console shows neither findings nor files taken beyond the plan.
- The harness's role-to-server mapping has no entry for the task planner.
- `ArchitectResearch.examples` and `Librarian.planExamples` have no caller left.

### A live run should be watched for

- Whether the architect calls `keep_for_workers` at all, how many findings, and whether the
  line ranges it names are the lines it meant (the log has one `keep_for_workers:` line each).
- The planner's lookups by kind: whole-file reads of framework examples should be near zero,
  and its output tokens per task lower.
- Worker lookups and output tokens per task against run 98, and whether repair rounds drop.
- "No file was computed for the new type" lines at PLAN, and overlap objections caused by a
  computed file.
- "Files taken beyond the plan" per task, and any refusal naming another task.
- `TypeDependencyOrder` reads type names out of instructions; with shorter instructions watch
  for a missing edge it used to add.

**Tests.** New: `TheArchitectHandsItsFindingsToTheWorkersTest`,
`ThePlannerOnlySplitsAndOrdersTest`, `ATasksFilesAreComputedFromWhatItClaimsTest`,
`AWriteOutsideTheReservationIsDecidedByRuleTest`,
`AWorkerIsOpenedWithWhatTheArchitectEstablishedTest`. Changed to the new rule:
`ACandidateThatChangedSourceAnotherTaskHoldsDoesNotPassTest` (was
`...OutsideItsWriteSet...`), `TheArchitectAndThePlannerLookThingsUpAndCheckTheirDraftsTest`,
`TheTestAuthorIsShownARealTestTest`, `WorkerToolboxTest`; cases added to
`AWorkerIsShownHowThisCodebaseDoesItTest`, `APlanThatCannotConnectWhatItAddsIsSentBackTest`,
`HarnessRunReportTest`.
