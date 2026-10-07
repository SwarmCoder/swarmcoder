# SwarmCoder: Architecture & Design Document

> ⚠️ **HISTORICAL RATIONALE — NOT AUTHORITATIVE FOR CURRENT STATE.** This is the original
> design rationale (Draft 0.1, unchanged since July 2026). For what the system actually is
> today, read **`docs/DEVELOPER_CORRECTIONS.md`** (the normative correction contract) — it
> supersedes this document and the technical spec wherever they conflict.

**A standalone, opinionated, swarm-based coding agent in Java on Koog, targeting local inference on NVIDIA DGX Spark (GB10)**

Version 0.1 — July 2026 — Status: Draft for review

---

## 1. Vision and thesis

SwarmCoder is a standalone terminal coding agent (in the spirit of Claude Code, OpenCode, and Google Antigravity — no IDE integration) that implements exactly one hard-coded development workflow. It trades generality for reliability: the workflow, the artifact schema, and the model roster are fixed, which allows every stage to be verified by machinery rather than by prompt engineering.

The core bet is a test-time-compute bet: a non-frontier local coding model, sampled N times in parallel on the same task with engineered diversity, plus a strong automated selection mechanism, can match or beat a single pass of a much stronger model on well-scoped tasks — at near-zero marginal cost, because a DGX Spark reads the model weights from memory once per decoding step regardless of how many sequences are in flight.

This bet is well supported by research. "Large Language Monkeys" (Brown et al., 2024) showed SWE-bench Lite coverage of a small model rising from ~16% at one sample to over 50% at hundreds of samples; CodeMonkeys (Stanford, 2025) built a full SWE-bench system on repeated sampling plus selection; and 2026 work on generative Best-of-N selection (GenSelect and successors) confirms both the upside and the catch: **the bottleneck is never generation, it is selection.** The entire architecture below is organized around that fact. Generation is cheap and parallel; selection is verification-first and gets the most engineering investment.

### 1.1 What the hardware actually gives you

The GB10 has ~273 GB/s aggregate memory bandwidth (measured GPU-side figures are ~221 GB/s) against very large FP4/FP8 tensor throughput. Single-stream decoding is bandwidth-bound and mediocre; batched decoding is nearly free up to the compute or KV-cache limit. Community benchmarks confirm this dramatically: models that decode at 6 tok/s single-stream reach ~92 tok/s aggregate at 16 concurrent requests, and small models scale from 26 to ~1,460 tok/s at concurrency 64. A concurrency benchmark specifically on the Spark measured up to ~120x aggregate throughput versus single-stream figures. Your instinct — load weights once, batch many sessions — is exactly how vLLM's continuous batching works and it is the correct exploitation of this hardware.

**Qwen3-Coder-Next is close to an ideal worker model for this box.** It is an ~80B-parameter MoE with only ~3B active parameters per token, 256K native context, released with an NVFP4 quantization tuned specifically for the GB10. Two properties matter enormously for a swarm:

1. At NVFP4 the weights occupy ~43 GiB, leaving roughly 60+ GiB of the 128 GiB unified pool for KV cache.
2. Its hybrid DeltaNet + attention architecture means most layers keep no growing KV cache; decode speed is roughly constant with context length, and per-sequence KV memory is unusually small. This is what makes 10–20 concurrent long-context agent sessions plausible where a dense-attention model would exhaust KV memory.

Reference single-stream throughput on a Spark is ~43 tok/s at FP8. Under a batch of 10–20, expect per-stream throughput to drop but aggregate throughput to multiply several-fold. The unknown to benchmark on day one is prefill (TTFT), which is the real cost of agentic loops (see red team §8.3).

---

## 2. Prior art survey — has anyone done this?

Nobody ships exactly this (N identical local agents on one task, verification-first selection, typed workflow objects, standalone JVM tool), but every ingredient exists somewhere, and you should steal liberally.

**Repeated sampling / Best-of-N research.** Large Language Monkeys (2024) established the log-linear pass@k scaling law for repeated sampling on SWE-bench. CodeMonkeys (2025) is the closest complete system: parallel candidate generation with a small model, candidate testing, and a selection stage; it is effectively the research prototype of your product. GenSelect-line work (2025–26) shows LLM-based selection beats majority voting, that RL-trained small selectors can approach large-model selection quality, and — critically — that selection gains on *code* are weaker than on math unless grounded in execution. Symbolic equivalence partitioning work (2026) and the older CodeT/SRank line show that clustering candidates by behavioral equivalence on generated tests is one of the strongest selection signals available. Sakana AI's AB-MCTS (2025) demonstrates adaptive branching between "sample more candidates" and "refine an existing candidate" — a pattern worth adopting in v2.

**Products with parallel agents.** Cursor's parallel agents and background agents run multiple attempts in git worktrees and let a human pick. Claude Code supports subagents and (2026) agent teams; its plan-mode → implement flow is the best-executed opinionated workflow in the market and worth copying at the UX level. Dagger's `container-use` gives each agent a container plus a git worktree — precisely your isolation model, in Go. Conductor and Vibe Kanban are UIs for herding parallel Claude Code/OpenCode instances. None of these do automated selection; they all punt selection to the human. Your differentiation is automating selection with verification.

**Opinionated workflow systems.** oh-my-opencode (and its slim fork) implements plan-first orchestration: a planner agent (Prometheus) interviews the user and produces persistent plan files, a plan-validator (Momus) critiques, and an orchestrator (Atlas) reads the plan and distributes tasks to background worker agents with non-overlapping write ownership, accumulating per-category learnings in notepad files. OpenSpec formalizes the artifact chain proposal.md → design.md → tasks.md with explicit propose/apply/verify/archive commands. Spec-kit (GitHub) and Kiro (AWS) are the same idea from bigger vendors. Your typed-Java-object version of these artifacts (§4.3) is a genuine improvement: every one of these systems suffers from agents mis-parsing or drifting from freeform markdown plans.

**Agent frameworks.** Koog 1.0 (May 2026) is stable, has an idiomatic Java API with builder-style construction, graph/functional/planning workflow strategies, persistence/checkpointing, OpenTelemetry observability, history compression, MCP integration, and — directly relevant — **ACP (Agent Client Protocol) support for exposing the agent to standard clients**. This is a sound foundation choice. OpenHands (ex-OpenDevin) is the best open reference for the runtime layer: its sandboxed execution server (agent actions serialized to a REST/WS server inside a Docker container) is the architecture to copy for your worker sandboxes. Mini-SWE-agent is the reference for how little scaffolding a worker loop actually needs (~100 lines: bash tool, loop, done-check) — your workers should be nearly this dumb, with all intelligence in the workflow around them.

**The fast C library: tree-sitter.** Confirmed by the author: the C library is **tree-sitter**, the incremental parser. One clarification matters for the design: tree-sitter *parses*, it does not merge — but it is the foundation on which correct merging and several other high-value services are built, so it earns a first-class component in this architecture (§4.6). It is callable from Java via mature JNI bindings (**tree-sitter-ng** by bonede, or **java-tree-sitter** by the SEART group), with prebuilt grammars for all mainstream languages. For the actual merge step, **Mergiraf** (Rust, built *on* tree-sitter, invoked as a git merge driver — no Java binding needed) provides syntax-aware structural merging with far fewer false conflicts than line-based merge. Orchestration-level git plumbing stays in pure-Java JGit, which is more than fast enough at this scale (see red team §8.5).

---

## 3. System overview

```
┌──────────────────────────────── Workstation (x86/ARM, 64GB+) ────────────────────────────────┐
│                                                                                              │
│  SwarmCoder Orchestrator (Java 21+, Koog 1.0)                                                │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────┐  ┌───────────────┐                    │
│  │ ACP server   │  │ Workflow      │  │ Artifact     │  │ Verification  │                    │
│  │ (terminal/   │  │ state machine │  │ store (typed │  │ harness       │                    │
│  │  ACP clients)│  │ (Koog graph)  │  │ Java objects)│  │ (build+test)  │                    │
│  └──────────────┘  └───────────────┘  └──────────────┘  └───────────────┘                    │
│  ┌────────────────────────────┐  ┌────────────────────────────────────────┐                  │
│  │ Swarm scheduler            │  │ Git service (JGit + Mergiraf driver)   │                  │
│  │ (virtual threads, N=8–20)  │  │ bare repo + per-worker worktrees       │                  │
│  └────────────────────────────┘  └────────────────────────────────────────┘                  │
│         │ docker API                                                                         │
│  ┌──────▼───────────────────────────────────────────────┐                                    │
│  │ Worker sandboxes: docker container per worker,       │                                    │
│  │ git worktree bind-mounted, no network (except LLM),  │                                    │
│  │ action server (exec/read/write/test) — OpenHands-style│                                   │
│  └──────────────────────────────────────────────────────┘                                    │
└───────────────┬───────────────────────────────────────────────────────┬──────────────────────┘
                │ OpenAI-compatible HTTP                                │ HTTPS
     ┌──────────▼─────────────────────────────┐            ┌────────────▼──────────────┐
     │ DGX Spark (GB10, 128GB) — inference ONLY│            │ Frontier API (Anthropic/  │
     │ vLLM: Qwen3-Coder-Next NVFP4 (worker)   │            │ OpenAI/Google) — Architect│
     │ vLLM: small utility model (embeds/haiku-│            │ + Approver roles, low     │
     │ class triage) on second port            │            │ volume                    │
     └─────────────────────────────────────────┘            └───────────────────────────┘
```

**Opinionated decision #1 (confirmed by author): the Spark serves tokens and nothing else.** The single Spark exposes an OpenAI-compatible vLLM endpoint over the LAN; the orchestrator, JVM, Docker sandboxes, and builds run on an Intel workstation (Windows or Linux). Every gigabyte of Spark unified memory spent on anything but weights and KV cache is throughput lost, and build CPU would contend with the Grace cores vLLM needs for scheduling.

*Windows caveat:* Docker on Windows means WSL2, and git worktrees bind-mounted across the Windows↔WSL2 filesystem boundary (9p) are painfully slow for build-heavy workloads. If the workstation runs Windows, keep the bare repo, all worktrees, and the sandboxes entirely inside the WSL2 ext4 filesystem, with the JVM orchestrator either inside WSL2 too or talking to Docker over the local API. Native Linux avoids the issue entirely and is the recommended host OS for anything beyond M1.

**Opinionated decision #2: one workflow, hard-coded.** There is no plugin system, no configurable agent graph, no user-defined roles. The workflow in §5 is compiled in. Configuration is limited to: repo path, model endpoints, N, token budgets, and approval mode.

---

## 4. Core components

### 4.1 Orchestrator

Java 21+ (virtual threads make 20 concurrent blocking agent loops trivial — one virtual thread per worker, no reactive plumbing). Built on Koog 1.0's Java API:

The workflow is a Koog **graph strategy**: nodes for each workflow stage, edges gated on typed verdicts. Koog's persistence feature checkpoints agent state so a crashed run resumes at the last completed stage rather than re-burning tokens — important when a full run is hours of local inference. Koog's OpenTelemetry export goes to a local Langfuse or Jaeger; with 20 concurrent sessions you cannot debug from stdout. Koog's `MultiLLMPromptExecutor` routes roles to endpoints: worker role → Spark vLLM port 8000, utility role → Spark port 8001, architect/approver roles → frontier API. Koog history compression keeps worker contexts inside their token budget on long tasks.

The orchestrator exposes two protocol surfaces, both natively supported by Koog: an **ACP server**, so the standalone TUI (and any ACP-capable client — Zed, Neovim plugins, oh-my-opencode-slim can even mount ACP agents as subagents) can drive it, and an **MCP server** exposing the artifact store (§4.3) as tools/resources, so *any* model in the system reads and writes workflow state through the same typed interface.

### 4.2 Model roster (fixed)

| Role | Model | Where | Count | Job |
|---|---|---|---|---|
| **Architect** | GLM-5.2 (Z.ai) or comparable cheap-frontier | API | 1 | Interview user, write DesignDocument, decompose into TaskGraph, define acceptance criteria and API contracts |
| **Test author** | GLM-5.2 | API | 1 | Write executable acceptance tests per task *before* any worker runs |
| **Librarian** | GLM-5.2 or utility model + tools | API/Spark | 1 | Research: API/library documentation lookup (Context7 MCP, web), produce typed KnowledgeBriefs injected into worker context (§4.8) |
| **Worker** | Pluggable — initial: Qwen3-Coder-Next NVFP4 | Spark, vLLM | 6–8 per task group, 2–3 concurrent groups | Implement one task inside a sandbox; multi-turn tool loop |
| **Selector/Judge** | GLM-5.2 | API | 1 | Rank the verification-surviving candidates; write review notes |
| **Approver** | GLM-5.2 (optionally a different family for de-correlation) | API | 1 | Final review gate over the integrated diff; produce human-readable summary |
| **Utility** | ~3–9B model (e.g. Qwen small) | Spark, 2nd port | 1 | Log triage, commit messages, failure clustering, cheap classification |

The two-model residency trick (big worker + small utility served simultaneously from the same unified pool) is validated practice on the Spark and costs little KV.

**The worker slot is deliberately model-agnostic** (confirmed requirement: different coding models will be experimented with). Every model is described by a `ModelProfile` record — endpoint, context ceiling, tool-call dialect (vLLM parser name, e.g. `qwen3_coder` vs `hermes` vs `glm`), sampling defaults, KV-cost-per-token estimate for the scheduler's admission control, and quirk flags (needs reasoning-parser, supports structured output, etc.). Swapping the worker model is a config change plus a vLLM restart on the Spark; nothing in the orchestrator changes. Combined with the eval harness (§8.9), every archived candidate carries its ModelProfile, so model comparisons on your real task distribution fall out for free — and running the swarm split across two model families (when both fit the pool, or A/B across runs) is the strongest known diversity lever (§8.2).

**Cost model: does the cloud judge negate the local savings? No — by roughly two orders of magnitude.** The economics work because token volume is wildly asymmetric across roles. Implementation is the token-heavy part: N workers × multi-turn tool loops × tool outputs easily runs to millions of tokens per overnight session — all at zero marginal cost on the Spark. The cloud roles see only small, bounded slices: the judge, thanks to verification-first filtering and behavioral clustering, ranks perhaps 3–5 *diffs* (not trajectories) plus test results per task — tens of kilotokens in, a couple out — and the architect/test-author/approver together consume a few hundred kilotokens per project run. At GLM-5.2-class pricing that is cents per task and well under a few dollars per overnight run, versus tens-to-hundreds of dollars if a frontier model ran the implementation loops itself. Cloud spend scales with the *number of tasks*; local spend scales with *implementation tokens*. That asymmetry is the whole business case, and the design protects it structurally: no cloud role ever receives a raw worker trajectory, only typed summaries, diffs, and reports. A hard per-run cloud budget cap in config enforces it mechanically.

**Second worker candidate: Nemotron 3 Super (120B-A12B).** Worth an M0 bake-off against Qwen3-Coder-Next rather than dismissal. For: hybrid Mamba-Transformer MoE, so — like Qwen's DeltaNet — its KV footprint is small and decode is nearly context-length-independent; native NVFP4 pretraining; built-in MTP speculative decoding; 1M context; explicitly RL-trained for agentic/multi-step coding, with strong agentic-coding results (best-open-model scores on agent-as-coder benchmarks, and Spark deployments are well documented — it's NVIDIA's own showcase model for the box). Against: 12.7B active parameters versus Qwen's ~3B means roughly 4× the bandwidth cost per decoded token, and ~60 GiB NVFP4 weights versus ~43 GiB leaves less KV headroom — so it supports a *smaller, smarter* swarm (N≈4–6 per group) where Qwen supports a *wider, cheaper* one (N≈6–8+). General-intelligence indices place it below Qwen3.5-122B, but agentic-coding-specific results are strong, which is the dimension that matters here. This is exactly the experiment ModelProfile exists for; since speed is explicitly a non-goal (overnight runs), the fewer-but-smarter configuration is more attractive than it would be in an interactive tool.

### 4.3 Typed artifact store — the opinionated core

Because the workflow is fixed, its artifacts are **Java records, not markdown files**. This is your genuine architectural edge over oh-my-opencode/OpenSpec, whose agents routinely drift from freeform plan files.

Sketch of the schema:

```java
record DesignDocument(UUID id, String goal, List<Requirement> requirements,
                      List<ArchDecision> decisions, List<ApiContract> contracts,
                      List<Risk> risks, Approval status) {}

record TaskGraph(UUID designId, List<Task> tasks, List<Edge> dependencies) {}

record Task(UUID id, String title, String instructions,
            Set<Path> writeSet,          // exclusive file-ownership — the anti-merge-hell device
            Set<Path> readSet,
            List<AcceptanceCriterion> criteria,
            Path acceptanceTestDir,      // tests are code, workers may not modify
            TokenBudget budget, TaskState state) {}

record CandidateSolution(UUID taskId, int workerIndex, String branch,
                         SamplingConfig sampling, Diff diff,
                         VerificationReport verification, JudgeScore score) {}

record VerificationReport(boolean compiles, TestResults acceptance,
                          TestResults existing, LintResults lint,
                          Duration wallTime, List<String> log) {}
```

Persistence is **EclipseStore** (author's choice): the entire artifact object graph — designs, task graphs, candidates, verification reports, learned guidelines (§4.7) — persists natively as Java objects with no ORM mapping layer, which fits a records-based schema perfectly. Design rules that keep EclipseStore pleasant: one `EmbeddedStorageManager` owned by the orchestrator, all mutations funneled through a single-writer artifact-store service (EclipseStore is not a concurrent database — with 20 virtual threads reporting progress, serialize writes through one channel); `Lazy<>` references for the bulky bits (archived candidate diffs, verification logs) so the hot object graph stays small in heap; and a deliberate class-evolution discipline, since every schema change to a record must be handled via Legacy Type Mapping — version the artifact records from day one. Because EclipseStore's storage is binary and not human-inspectable, keep the git meta-branch export: every artifact revision is also serialized (JSON) and committed to a `swarmcoder/meta` branch, so runs stay auditable and diffable with plain git tooling. Models never see or emit these objects as freeform prose: the MCP server exposes typed tools (`get_task`, `submit_design`, `report_progress`, `list_criteria`) with JSON-schema-constrained I/O, and vLLM's structured-output mode enforces the schema on the local model. Parsing failure is impossible by construction, which is the whole point of being opinionated.

**The `writeSet` field is the most important line in the schema.** The Architect must assign disjoint write-ownership across concurrently scheduled tasks (oh-my-opencode's "no overlapping write ownership" rule, made machine-checkable). Sandboxes mount everything read-only except the write set. This turns cross-task integration merges into trivial, conflict-free operations and is worth far more than any fast merge library (§8.5).

### 4.4 Inference layer (the tricks, built in)

vLLM on the Spark (the community GB10 images with SM 12.1 kernels; NVFP4 with the Marlin backend measured ~15% faster than the CUTLASS path for this model's 512 experts). Flags: `--enable-prefix-caching`, tuned `--max-num-seqs` (start ~24: N workers + judge/utility headroom), `--gpu-memory-utilization 0.90`, tool-call parser `qwen3_coder`, KV cache in FP8, and MTP speculative decoding if the current kernels support it for this model — it stacks with batching on bandwidth-bound hardware.

Tricks the scheduler exploits, in rough order of value:

**Prefix-cache alignment.** All N workers on a task share a byte-identical prompt prefix: system prompt, workflow rules, task instructions, repo context. Diversity-inducing content (persona, hints, sampling params) is appended *after* the shared prefix. With prefix caching this means the expensive prefill of the shared context is computed once and reused N times — the single biggest lever on the Spark, where prefill/TTFT, not decode, is the real cost of agentic loops (27s+ TTFTs are reported at long contexts). The orchestrator treats prefix stability as an invariant: context assembly is deterministic and ordered.

**Engineered diversity.** N identical samples from one model fail in correlated ways. Diversity axes, applied as a matrix across the swarm: temperature sweep (e.g. 0.2 → 1.0), distinct seeds, 3–4 short strategy personas appended post-prefix ("minimal diff", "test-driven, implement to the tests literally", "refactor-friendly", "defensive/edge-case-first"), and optionally two different context slices (full-file vs. signatures-only) for half the swarm. Log the sampling config per candidate (`SamplingConfig` above) and, over time, learn which cells of the matrix win — this becomes your tuning dataset.

**Early kill and slot reuse.** Workers stream. The scheduler kills a worker the moment it: fails to produce a parseable tool call twice, exceeds its token budget, edits outside its write set, or its first compile fails twice. Freed batch slots are immediately reused, either for fresh diversity cells or for the repair round. Expected effect: effective N per task rises well above the nominal concurrent N.

**Behavioral clustering before judging.** After verification, cluster surviving candidates by acceptance-test output vectors (which tests pass/fail, plus outputs on judge-generated probe inputs — the CodeT insight). Identical-behavior candidates collapse to one representative with a multiplicity weight (self-consistency signal). The judge then ranks a handful of behaviorally distinct representatives instead of 15 near-duplicates — cheaper, and empirically more accurate than raw Best-of-N judging.

**Repair round (sequential test-time compute).** If zero candidates pass verification, don't re-roll blindly: take the 2–3 closest failures, feed each its own failing test output, and run a smaller repair swarm (N=4–6) seeded from those branches. One repair round only; then the task is marked BLOCKED and escalated to the Architect for re-decomposition. (This is the AB-MCTS wide-vs-deep tradeoff, hard-coded to one level because opinionated.)

**Token budgets as physics.** Every task carries a hard budget. On memory-bandwidth-limited hardware, budgets are the scheduler's admission-control currency: total in-flight KV = Σ active contexts, and the scheduler will not dispatch a swarm whose worst-case KV exceeds the pool. Default per-worker context cap: 32K (the Qwen3-Coder-Next Spark deployments converge on 32K as the everyday sweet spot), with Koog history compression triggered at 75%.

### 4.5 Sandbox and git topology

One bare clone serves as origin. Per worker: a branch `swarm/<taskId>/<workerIdx>` and a **git worktree** (not a full clone — 20 clones of a big repo would murder disk and cache), bind-mounted into a Docker container. Container properties: language toolchain image (JDK/Node/etc. per target repo), read-only rootfs plus the worktree, **no network** except the vLLM endpoint (prompt-injection containment, §8.8), CPU/mem/pids limits, and an OpenHands-style action server inside (a tiny HTTP server executing `run`, `read`, `write`, `test` actions) so the orchestrator talks structured actions rather than raw docker-exec streams. Containers are pooled and recycled per toolchain to amortize startup.

Merging happens at exactly two, deliberately different places:

1. **Candidate selection is not a merge.** The winning candidate's branch is fast-forwarded/squashed onto the task integration branch. Losing candidates are archived (`refs/swarm-archive/…`) with their VerificationReports — valuable eval data — and their worktrees recycled. *Never* attempt to merge multiple candidates for the same task into one artifact; that path produces Frankenstein diffs (§8.5).
2. **Cross-task integration** merges task branches into `swarm/integration` in TaskGraph topological order. Because write sets are disjoint, these are conflict-free by construction; Mergiraf as the merge driver handles the residual structural cases (imports, adjacent-line edits in shared config files, formatting). A full verification run executes after *every* integration merge, not just at the end.

### 4.6 Tree-sitter services

Tree-sitter (via the tree-sitter-ng or SEART java-tree-sitter JNI bindings) backs a single in-process `SyntaxService` used by four parts of the system:

**Repo map for prompt context.** Parse the codebase once (tree-sitter is incremental and fast enough to reparse on change), extract symbol signatures, and build the Aider-style compressed repo map that goes into the shared prompt prefix. This is how workers get whole-repo awareness inside a 32K budget, and because the map is deterministic it preserves prefix-cache alignment (§4.4).

**Edit gate.** Every file a worker writes is parsed before the sandbox accepts the edit. A candidate that doesn't parse never reaches the verification harness — the cheapest possible early-kill signal, ahead of compilation.

**AST-level clustering.** Candidate dedup (§4.4) normalizes diffs by parsing changed regions and comparing structure rather than text, so formatting-only and rename-only variants collapse into one cluster before behavioral clustering runs. This is where tree-sitter directly serves the selection pipeline.

**Write-set enforcement at symbol granularity (v2).** Task write sets are file paths in v1; tree-sitter enables tightening them to symbols (class/function ownership), which allows more tasks to run concurrently in the same files without merge risk.

Merging itself is delegated to Mergiraf as a git merge driver — the same tree-sitter technology, already productized, no need to rebuild it in Java.

### 4.7 The Context Ledger — intelligent context lifecycle

The failure mode the author describes — a session that opens with design and clean implementation, degrades into a long debugging slog, and returns to development with a context poisoned by pages of stack traces — is one of the best-documented pathologies in agentic coding (Cognition's Devin team wrote the canonical essay on it; Anthropic's context-engineering guidance says the same). No shipping system solves all of it, but every piece exists somewhere, and the typed artifact store makes SwarmCoder unusually well placed to combine them. Prior art per mechanism: **MemGPT/Letta** for the memory hierarchy (small curated "core memory" always in context; everything else in searchable archival storage); **Claude Code** for compaction, checkpoints with rewind, and — most importantly — *subagent context quarantine*; **oh-my-opencode's notepads** for accumulated per-category learnings; **LangGraph/Koog checkpointing** for state rollback. SwarmCoder composes these into one mechanism, the Context Ledger:

**Phase-fenced contexts.** No context ever crosses a workflow-stage boundary. Each stage starts fresh from typed artifacts (the Task, the KnowledgeBrief, the DesignDocument excerpt), never from the previous stage's transcript. This is already implicit in the architecture; the ledger makes it explicit and enforced.

**Debug forking (context quarantine).** The author's "fork before debugging starts" intuition is exactly right, and the trigger is mechanically detectable: the moment a worker's flow shifts from implementing to reacting to a failure (first failed verification, first test-failure tool result), the orchestrator checkpoints the context (a `ContextCheckpoint` artifact recording the message index and a KV-prefix hash) and forks a **DebugSession** child context, seeded with only a compact brief: the task, the current diff, the failing output, and relevant code slices — not the implementation history. The debugging thrash happens entirely in the child. On success the child emits a typed `FixSummary` (root cause, change made, guideline candidate); the parent context is rolled back to the checkpoint and the FixSummary is appended. The parent never contains a single stack trace. Two bonuses fall out for free: rollback restores an exact previous token prefix, so vLLM's prefix cache gets a full hit on resume (rollback is literally *cheaper* than continuing on the Spark); and failed DebugSessions can be discarded and re-forked with different seeds — debugging becomes swarmable like everything else.

**Compact-and-archive.** When any context must be compressed (Koog history compression at 75% of budget), the ledger does three things instead of one: produces the summary that stays in context; embeds the *full* discarded transcript and stores it in a local vector index (embeddings served by the utility model on the Spark — also free), exposed to all agents as an MCP `search_history` tool for RAG-style lookup ("have we seen this error before?"); and runs an extraction pass.

**Guideline extraction.** The extraction pass mines discarded context for durable instructions — constraints the user stated, conventions discovered in the repo, root causes from FixSummaries — into typed `LearnedGuideline` objects (scope: project / task-family / global, plus provenance and a confidence field). Guidelines live in EclipseStore, are deduplicated semantically (utility model), decay if repeatedly unused, and the top-scoring ones are rendered into the deterministic shared prompt prefix of future swarms. This is the typed, curated version of CLAUDE.md files and oh-my-opencode notepads — and because guidelines are objects behind MCP, they survive every compaction by construction, which answers the "core instructions forgotten during pruning" problem directly: core instructions are never *in* the prunable context in the first place; they are artifacts rendered into the prefix on every dispatch.

### 4.8 The Librarian — knowledge injection for small models

Small models fail on unfamiliar APIs not because they can't code but because they don't *know* — a knowledge failure, which no amount of swarm sampling fixes (§8.1). The countermeasure is a dedicated research role that front-loads knowledge before workers ever run:

During PLAN, the Librarian inspects each task's read/write sets and the repo's dependency manifests (Gradle/Maven/npm/Cargo), determines which libraries and framework versions the task touches, and researches them — **Context7 MCP** as the primary source for version-accurate library docs, web search and vendor docs as fallback, plus tree-sitter-extracted signatures of the *internal* APIs in the task's read set. The output is a typed `KnowledgeBrief` per task: the exact API signatures, idioms, version caveats, and one or two canonical usage examples, hard-capped in size (~2–4K tokens). The brief is rendered into the swarm's shared prompt prefix — which preserves prefix-cache alignment, since it's assembled deterministically *before* dispatch and shared by all N workers, and its prefill cost is paid once per group.

For mid-task gaps, workers get one narrow tool: `lookup_api(library, symbol)`, served from a local docs index the Librarian pre-warmed (falling through to Context7 on miss). Workers never browse the web — that keeps the no-network sandbox guarantee (§8.8), keeps tool results small and cacheable, and keeps prompt-injection surface minimal. A worker whose failure clusters as "API misuse" (utility-model classification of verification logs) triggers a Librarian refresh of the brief before the repair round — often the actual fix when all N candidates fail the same way, since correlated failure across the swarm is the signature of a knowledge gap rather than a sampling miss.

---

## 5. The workflows (hard-coded, plural)

The system ships a small fixed *library* of workflows — each individually hard-coded with its own stage graph, invariants, and swarm policy; still no user-defined workflows, no plugins. INTAKE classifies the request (utility model + user confirmation) and routes:

| Workflow | Shape | Swarm policy |
|---|---|---|
| **GREENFIELD / ENHANCEMENT** | The full pipeline below | Full swarm per task |
| **BUGFIX** | Reproduce → author red test → swarm on the fix → verify → approve | Swarm; skips DESIGN, requires a mechanical reproduction before any generation |
| **REFACTOR** | Characterization tests first (tests asserting *current* behavior) → swarm → behavior-preservation verification (all green before and after) | Swarm; selection criterion adds "smallest AST distance for same behavior" |
| **DOCS** | Analyze → single worker drafts → approver reviews | No swarm (no executable verification ⇒ swarm buys nothing, §8.1) |
| **ANALYSIS** | Read-only investigation → typed report | No swarm; no write sets at all |

The flagship GREENFIELD pipeline:

```
INTAKE ──► DESIGN ──► DESIGN_REVIEW ──► PLAN (+ Librarian briefs) ──► TEST_AUTHORING ──►
   TASK LOOP (per ready task; 2–3 task groups concurrently, 6–8 workers each):
       DISPATCH SWARM ──► VERIFY ALL ──► CLUSTER ──► JUDGE ──► SELECT
            │ zero survivors                                    │
            ▼                                                   ▼
       REPAIR ROUND (once, Librarian-refreshed) ──► BLOCKED→Architect   INTEGRATE ──► VERIFY
   ──► FINAL_INTEGRATION ──► APPROVAL (model review + human gate) ──► DELIVER (PR/patch)
```

**Overnight unsupervised mode** (a stated primary use case — speed is a non-goal, autonomy is the goal): all human gates become an async decision queue. The run proceeds through everything mechanical; anything requiring judgment (BLOCKED tasks the Architect couldn't re-decompose, approval itself) is parked with a prepared decision brief, and the run continues on unblocked branches of the TaskGraph. Hard caps make unsupervised safe: per-run cloud token budget, per-task local token budget, wall-clock ceiling, and Koog persistence checkpoints so a crash at 3 a.m. resumes rather than restarts. The morning artifact is a single report: per-task leaderboards, the integrated diff, the approver's summary, the decision queue, and total spend.

Stage notes for the flagship pipeline:

**INTAKE.** User states a goal in the TUI. Utility model classifies scope and workflow; trivial requests (typo-class) bypass the swarm entirely and go to a single worker — the workflows are opinionated, not masochistic.

**DESIGN.** Architect (frontier) interviews the user — a bounded, structured interview writing directly into `DesignDocument` fields via MCP tools, not an open chat. Output includes explicit API contracts between components, because contracts are what make parallel task execution safe.

**DESIGN_REVIEW.** A second frontier pass (different model family if configured) critiques the design against a fixed rubric (completeness, testability, write-set partitionability). Loops max twice, then proceeds with recorded objections — the oh-my-opencode Momus pattern with a convergence bound.

**PLAN.** Architect decomposes into TaskGraph. Machine-checked invariants, enforced by the orchestrator (reject + regenerate on violation): concurrently schedulable tasks have disjoint write sets; every task has ≥1 executable acceptance criterion; every task fits its token budget by estimate; graph is acyclic.

**TEST_AUTHORING.** The test author writes runnable acceptance tests per task, committed to a protected path workers cannot write (enforced by the sandbox mount, not by prompt). Tests are verified to fail on the pre-change codebase (red state) before any swarm dispatches. This stage is what makes verification-first selection possible; without it the swarm degenerates to judge-only selection, which the research says is weak for code.

**TASK LOOP.** As in §4.4/§4.5. The human can watch a live per-task leaderboard (candidates, verification state, cluster sizes) in the TUI but intervenes only at BLOCKED escalations.

**APPROVAL.** Approver model reviews the full integrated diff against the DesignDocument with repo-wide checks (regressions in existing tests already gated mechanically; the model looks for semantic drift, security smells, contract violations), writes a human-readable change summary, then the human approves in the TUI. Output is a branch/PR — SwarmCoder never pushes to a main branch.

---

## 6. Technology choices, summarized

| Concern | Choice | Note |
|---|---|---|
| Language / runtime | Java 21+ (virtual threads), pure Java — no Kotlin | Koog consumed strictly through its Java API, behind an internal facade — see §8.6 |
| Agent framework | Koog 1.0 (`koog-agents-jvm`) | Graph strategies, persistence, MCP + ACP, OTel |
| Inference server | vLLM (GB10 community image, SM 12.1 kernels) | prefix caching, continuous batching, structured output, per-model tool parser |
| Worker model | Pluggable via ModelProfile; initial: Qwen3-Coder-Next NVFP4 | ~43 GiB weights, KV-light DeltaNet hybrid, 256K ctx |
| Parsing / repo map / edit gate / AST clustering | tree-sitter via tree-sitter-ng or SEART java-tree-sitter (JNI) | §4.6 |
| Git plumbing | JGit | Sufficient at this scale; pure Java, no native deps |
| Structural merge | Mergiraf as git merge driver | Syntax-aware, tree-sitter based |
| Sandboxing | Docker, worktree bind-mounts, action-server pattern | Copy OpenHands runtime design |
| Artifact store | Java records + EclipseStore (native object-graph persistence) + git meta-branch JSON export | Exposed via MCP tools with JSON-schema I/O; single-writer discipline |
| Context lifecycle | Context Ledger: phase fencing, debug forking, checkpoint rollback, RAG archive, LearnedGuidelines | §4.7; vector index local, embeddings via Spark utility model |
| Observability | OpenTelemetry → Langfuse/Jaeger | Non-negotiable at N=20 |
| UI | Terminal TUI speaking ACP to the orchestrator | Also usable from any ACP client |

---

## 7. Build plan (thin vertical slices)

**M0 — Benchmark before building (1–2 weekends).** Stand up vLLM + Qwen3-Coder-Next NVFP4 on the Spark. Measure, at concurrency 1/4/8/12/16/20 with realistic agentic prompts (8–32K prefill, tool-call turns): aggregate and per-stream decode, TTFT with and without prefix cache hits, KV headroom. This data decides N and whether the whole thesis holds on your unit. Also run a 20-sample pass@k experiment on ~30 SWE-bench-Verified-style tasks in your target stack: if pass@20 does not substantially beat pass@1, stop and rethink (§8.1).

**M1 — Single-worker skeleton.** Koog orchestrator, one sandboxed worker, typed Task in, verified diff out, JGit plumbing, TUI over ACP. This is "a bad Claude Code" and proves the plumbing.

**M2 — Swarm + verification-first selection.** N workers, prefix-aligned prompts, diversity matrix, early kill, verification harness, clustering, judge, select, archive losers. The heart of the system.

**M3 — Workflow front half.** Architect interview → DesignDocument → TaskGraph with write-set invariants → test authoring → multi-task integration merges.

**M4 — Approval, repair round, persistence/resume, leaderboard UX, eval harness over archived candidates.**

Each milestone is independently useful; you can vibe-code M1 with an existing agent, but keep M2's selection machinery hand-reviewed — it is the product.

---

## 8. Red team

**8.1 The swarm may not buy what you think it buys.** Repeated sampling reliably lifts *coverage* (pass@k) — but only realized gains matter, and realized gains equal coverage × selection accuracy. The 2026 selection literature is blunt: on code, judge-based selection without execution grounding underperforms; small-model self-selection is weakest exactly where you need it. Corollaries: (a) tasks without executable acceptance criteria get near-zero benefit from the swarm — the workflow must refuse to swarm them (route to single worker + frontier review instead); (b) if M0's pass@k curve on *your* task distribution is flat (it can be, when failures are knowledge failures rather than sampling variance — e.g. the model simply doesn't know a framework API), 20 correlated wrong answers cost 20x and return 1x. Measure first. Additionally, one strong frontier pass per task via API might simply beat the entire apparatus on quality *and* wall-clock; the Spark's advantage is marginal cost and privacy, not quality. Be honest about which one you're optimizing.

**8.2 Correlated failure is the default, not the exception.** Same weights, same context ⇒ heavily correlated samples; temperature alone decorrelates less than people assume. The diversity matrix (§4.4) is load-bearing, not decorative. Consider in v2 a second worker model family (e.g. a GLM/DeepSeek-class MoE that fits alongside or swaps in) — cross-family diversity is worth more than any temperature sweep. Also beware anti-selection: higher temperature raises both novelty and defect rate; without execution filtering it makes the pool *worse*.

**8.3 Prefill economics — better than feared, still the thing to watch.** The GB10's strength is exactly where agentic loops spend their time: prefill is compute-bound and the chip has FLOPs to spare, so long-context ingestion is genuinely fast on this box; it is *decode* that is bandwidth-limited, and batching amortizes that. Two residual cautions. First, 12–24 workers prefilling simultaneously still queue against finite compute — the scheduler should stagger group dispatches rather than firing everything at t=0. Second, per-worker tool outputs diverge immediately after the shared prefix and re-prefill on every turn, so keep append-only context discipline (never rewrite earlier context, preserving each worker's own turn-to-turn prefix reuse) and truncate tool outputs hard. With speed explicitly a non-goal (overnight runs) this whole concern demotes from "architecture risk" to "efficiency tuning": the author's chosen shape — 6–8 workers per task group, 2–3 groups (12–24 in flight) — sits comfortably inside both the KV pool and the compute envelope for KV-light models. N stays a runtime dial; M0 sets the defaults.

**8.4 Unified memory is one pot and everything eats from it.** Weights (~43 GiB) + KV + vLLM runtime + OS. If you also run Docker sandboxes, JVM, and Gradle builds on the Spark, they eat KV cache and CPU-side scheduling; compilation storms from 20 containers will visibly degrade token throughput. Hence the two-box split (§3). Also: GB10 MoE kernels are immature (no tuned MoE config for this expert count yet; kernels improve monthly — track the NVIDIA Spark forum), ARM64-only images, sm_121 PyTorch quirks. Budget real ops time; this is not a managed endpoint.

**8.5 "Fast C merge library" is solving the wrong problem.** At 20 branches per task, merge throughput is irrelevant — JGit merges these in milliseconds. The dangerous idea hiding here is *merging multiple candidates into one solution*: candidates are alternative implementations of the same intent; line-level or even syntax-level merging of alternatives produces plausible-looking chimeras that no test suite authored per-candidate has ever run. The design therefore forbids it: selection picks exactly one candidate; merging only ever happens *across* disjoint-write-set tasks, where it is trivial. Keep Mergiraf for that residual case and spend the saved effort on the verification harness. (If you later want to *combine* insights across candidates, do it in model space — feed two candidates to a worker as references and generate a fresh third — never in diff space.)

**8.6 Pure-Java + Koog risks.** The pure-Java constraint is confirmed, so plan for its costs. Koog's Java API only stabilized in 2026; the community, examples, and most docs are Kotlin, and you will hit corners (coroutine-backed internals surfacing as futures, DSL-flavored builders, Kotlin-only sample code you must mentally translate) where Java is a second-class citizen. Mitigation is architectural, not linguistic: consume Koog strictly behind an internal facade (`AgentRuntime` interface owned by SwarmCoder) so Koog idioms never leak into the workflow, artifact, or scheduler code — this contains the friction to one module and keeps the framework swappable if Koog's Java story stalls. The same applies to tree-sitter's JNI bindings (native library loading per-platform, grammar packaging) — wrap them once in `SyntaxService` and never call them directly elsewhere. Also verify early that Koog's ACP *server* role (agent-side) matches what your TUI needs, not just ACP client mounting; this is a young protocol.

**8.7 Hard-coded workflow: the strength that bites.** Real work is bimodal — half of it doesn't fit any fixed pipeline (debugging sessions, "why is CI red", exploratory refactors). If SwarmCoder handles only greenfield-feature-shaped work, it becomes a demo. The INTAKE bypass (§5) is the minimum escape hatch; consider a second hard-coded mini-workflow for bugfix-shaped work (reproduce → red test → swarm on the fix) before adding any configurability. Resist the plugin system to the end — the moment workflow becomes configuration, you have rebuilt OpenCode with extra steps and lost the typed-invariant advantage.

**8.8 Security and safety.** Twenty autonomous processes editing code and running builds: repo contents (READMEs, test fixtures, dependencies' install scripts) are untrusted model input — prompt injection can and will redirect a small model. Containment is structural: no-network sandboxes (only the LLM endpoint), read-only mounts outside the write set, protected test paths, mechanical (non-LLM) enforcement of "workers cannot modify tests or CI config", and an integration-time secret/exfil scan. The Approver reviews the *diff*, but diffs hide behavior in dependencies — pin and hash dependencies in sandbox images. Also cap total token spend per run: an early-kill bug plus a retry loop on local hardware is silent, just a warm Spark for six hours.

**8.9 Bootstrap and evaluation honesty.** You intend to vibe-code the tool that replaces vibe coding. Fine — but build the eval harness (M0/M4) first and keep it trusted (human-reviewed): every archived candidate with its VerificationReport is a labeled datapoint, and after ~50 runs you'll have real answers to N, temperature ranges, persona value, and judge accuracy. Without it you will tune 12 hyperparameters by anecdote. The single most likely failure mode of this whole project is not technical: it is shipping M2 with a weak verification harness, watching the judge pick pretty-but-wrong code, and concluding the swarm idea failed when actually selection did.

---

## 9. Decisions confirmed and questions remaining

**Confirmed by the author:** two-box topology — Intel workstation (Windows or Linux) runs all agent code and sandboxes; a single Spark runs inference only behind an OpenAI-compatible vLLM endpoint. Cloud APIs are acceptable; the cheap-frontier roles (Architect, Test author, Judge, Approver, Librarian) default to GLM-5.2, with local token cost treated as zero. Worker models are pluggable and will be benchmarked — Qwen3-Coder-Next NVFP4 vs Nemotron 3 Super NVFP4 is the first bake-off (§4.2). Implementation is pure Java, no Kotlin (§8.6). Tree-sitter serves parsing/repo-map/edit-gate/clustering (§4.6); Mergiraf handles structural merges. Persistence is EclipseStore with a git meta-branch JSON export (§4.3). Swarm shape: 6–8 workers per task group, 2–3 concurrent groups. Speed is a non-goal; long unsupervised overnight runs are a primary use case (§5). Multiple hard-coded workflows (greenfield, bugfix, refactor, docs, analysis) rather than one (§5). Target stacks: enterprise Java + HTML/CSS first-class; Rust, TypeScript, Python as secondary verification harnesses.

**Still open:**

1. **Verification harness order:** Java/Gradle (and Maven?) first is implied — confirm whether HTML/CSS verification means build-only or browser-level checks (Playwright screenshots + assertions), which is meaningfully more machinery.
2. **Guideline curation UX:** LearnedGuidelines (§4.7) accumulate; decide early whether the human reviews/edits them in the TUI or they remain fully automatic with decay. Fully automatic memory that drifts wrong is worse than none.
3. **Judge de-correlation:** all cloud roles on GLM-5.2 means the model reviewing the plan is the model that wrote it. Cheap fix worth considering: a second inexpensive family (e.g. DeepSeek-class) for DESIGN_REVIEW and APPROVAL only.
