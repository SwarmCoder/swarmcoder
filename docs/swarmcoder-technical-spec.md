# SwarmCoder — Technical Design Specification

> ⚠️ **NOT AUTHORITATIVE FOR CURRENT STATE.** This spec (last revised July 2026, unchanged
> since) predates the 2026-07-09 design audit and every decision made during the build. Where
> it conflicts with **`docs/DEVELOPER_CORRECTIONS.md`**, that document wins — it is the
> normative correction contract. Known divergences: MCP + ACP server (§15) descoped; a
> web-touching Researcher role added (§18 amended); the TUI removed in favour of the zeroz4j
> Console; worker roster and context budgets changed. Read `DEVELOPER_CORRECTIONS.md` first.

**Version 1.0 — July 2026**
**Audience: implementing coding agent (Claude Code / Antigravity) and human reviewer.**
This document is self-contained and normative. Where it says MUST/SHOULD, treat it as a requirement. The companion document `swarm-coder-architecture.md` contains rationale and research background; this document contains what to build.

---

## 1. Product summary

SwarmCoder is a **standalone terminal coding agent in pure Java 21+** (no Kotlin source anywhere in the project) built on the **Koog 1.0 Java API**. It implements a small fixed library of hard-coded development workflows. Its differentiating mechanism: for each implementation task it dispatches **N parallel worker agents (6–8 per task group, 2–3 groups concurrently)** against local models served by **vLLM on a single NVIDIA DGX Spark (GB10)**, then selects one winning candidate through a **verification-first pipeline** (compile → tests → lint → behavioral clustering → LLM judge). Cloud models (cheap-frontier class) fill the architect, design-reviewer, test-author, librarian, judge, and approver roles. Local token cost is treated as zero; cloud spend is hard-capped per run. Primary usage mode: **long unsupervised overnight runs** with an async human decision queue. Speed is explicitly a non-goal; autonomy, verifiability, and near-zero marginal cost are the goals.

### 1.1 Non-goals

No IDE integration. No plugin system. No user-defined workflows or agent graphs. No merging of multiple candidate solutions into one (selection picks exactly one). No worker access to the open internet. No SQL database.

---

## 2. Repository layout and build

Maven multi-module project, Java 21 (use `--release 21`; virtual threads are load-bearing). No Lombok; use records and plain Java. Jackson for all JSON. JUnit 5 + AssertJ + Testcontainers for tests.

```
swarmcoder/
├── pom.xml                        (parent; dependencyManagement for all versions)
├── sc-domain/                     Java records: all artifacts, enums, IDs. Zero dependencies except Jackson annotations.
├── sc-store/                      EclipseStore persistence, git meta-branch export, guidelines file mirror.
├── sc-runtime/                    AgentRuntime facade over Koog; ModelProfile registry; prompt assembly.
├── sc-inference/                  vLLM/OpenAI-compatible clients, structured output, admission-control scheduler.
├── sc-syntax/                     SyntaxService over tree-sitter (tree-sitter-ng bindings).
├── sc-git/                        GitService over JGit; worktrees, branches, Mergiraf driver setup, meta-branch.
├── sc-sandbox/                    Docker sandbox manager (docker-java), action-server protocol client, images/.
├── sc-verify/                     Verification harness: toolchain adapters (Gradle/Maven, Node, Playwright, Cargo, Python).
├── sc-knowledge/                  Librarian, Context7/web adapters, docs index, KnowledgeBrief builder;
│                                  Context Ledger: checkpoints, debug fork, compaction, RAG store (Lucene), guidelines.
├── sc-swarm/                      Swarm engine: dispatch, diversity matrix, early-kill, clustering, judging, selection.
├── sc-workflow/                   Workflow state machines (greenfield, bugfix, refactor, docs, analysis); invariants.
├── sc-server/                     MCP server (artifact tools) + ACP server; decision queue API.
├── sc-tui/                        Lanterna-based terminal UI (thin ACP client).
├── sc-app/                        Main entrypoint, config loading, wiring (plain constructor injection; no DI framework).
└── sc-evals/                      Eval harness over archived candidates; M0 benchmark scripts.
```

Key dependencies (pin exact versions in the parent POM at implementation time):
`ai.koog:koog-agents-jvm:1.0.x`, `org.eclipse.store:storage-embedded` (EclipseStore), `org.eclipse.jgit:org.eclipse.jgit`, `io.github.bonede:tree-sitter` + per-language grammars (tree-sitter-ng), `com.github.docker-java:docker-java` (+ httpclient5 transport), `io.modelcontextprotocol.sdk:mcp` (official MCP Java SDK) for the server side, `com.microsoft.playwright:playwright`, `org.apache.lucene:lucene-core` + `lucene-codecs` (HNSW KNN vectors), `com.microsoft.onnxruntime:onnxruntime` (local embeddings), `com.googlecode.lanterna:lanterna`, Jackson, SLF4J + Logback, OpenTelemetry SDK + OTLP exporter.

Rule: **Koog and tree-sitter types MUST NOT appear in any module's public API except `sc-runtime` and `sc-syntax` respectively.** Enforce with ArchUnit tests in each module.

---

## 3. Runtime topology and deployment

### 3.1 Boxes

**Workstation (Intel, Windows or Linux).** Runs everything except inference: the SwarmCoder JVM, Docker sandboxes, git repos, verification builds, Playwright browsers, Lucene index, embeddings (ONNX, CPU). If Windows: the bare repo, all worktrees, sandbox volumes, and the Docker engine MUST live inside the WSL2 ext4 filesystem; the JVM SHOULD run inside WSL2 too. Document this in README; add a startup check that warns if the repo path is on `/mnt/c`.

**DGX Spark (GB10, 128 GB unified).** Inference only. Exposes OpenAI-compatible vLLM endpoints over LAN. No SwarmCoder code runs here; deployment is a documented set of launch scripts in `deploy/spark/`.

### 3.2 Spark serving plan — dual worker models

Two vLLM instances, one per model, separate ports, each pinned to a memory fraction of the unified pool. Target layout (validate and tune in M0):

| Instance | Model | Quant | Weights | Port | `--gpu-memory-utilization` | Purpose |
|---|---|---|---|---|---|---|
| vllm-a | Qwen3-Coder-Next | NVFP4 (GB10-tuned) | ~43 GiB | 8000 | 0.50 (≈64 GiB ⇒ ~20 GiB KV) | Worker family A |
| vllm-b | Qwen3.5-35B-A3B | NVFP4 | ~18 GiB | 8001 | 0.34 (≈43 GiB ⇒ ~25 GiB KV) | Worker family B + utility role (classification, triage, commit messages) |

Remaining ~16% for OS/runtime headroom. Both instances: `--enable-prefix-caching`, `--enable-auto-tool-choice`, `--tool-call-parser qwen3_coder`, `--kv-cache-dtype fp8`, `--max-model-len 65536`, `--max-num-seqs 16`, served via the GB10 community image (SM 12.1 kernels; NVFP4 Marlin backend where available). Provide `deploy/spark/launch-a.sh`, `launch-b.sh`, and a `healthcheck.sh` that the workstation polls at startup.

Fallback profile (config-selectable): single-model mode — one instance at 0.90 utilization for maximum KV, used when experimenting with a model too large to pair (e.g. Nemotron 3 Super NVFP4 ~60 GiB).

**Embeddings do NOT run on the Spark.** Use `bge-small-en-v1.5` (or equivalent) via ONNX Runtime on the workstation CPU in-process.

### 3.3 Cross-model swarm policy

When two worker models are live, the swarm for a single task is **split within the task** across families (e.g. N=8 ⇒ 4 from each model), because cross-family diversity decorrelates candidate failures far better than temperature. `SamplingConfig` records the family. The scheduler treats each vLLM instance as an independent capacity pool (see §7.3).

---

## 4. Domain model (`sc-domain`)

All artifacts are immutable Java records with `UUID` ids and a monotonically increasing `long revision`. Every record type carries `@JsonTypeName` for the meta-branch export. Enums and records below are normative names; fields may be extended but not renamed.

```java
// ---- identity & versioning ----
public record ArtifactRef(UUID id, long revision) {}

public enum WorkflowKind { GREENFIELD, ENHANCEMENT, BUGFIX, REFACTOR, DOCS, ANALYSIS }
public enum TaskState { PENDING, READY, DISPATCHED, VERIFYING, JUDGING, SELECTED,
                        INTEGRATED, BLOCKED, CANCELLED, DONE }
public enum RunState { INTAKE, DESIGN, DESIGN_REVIEW, PLAN, TEST_AUTHORING,
                       EXECUTING, FINAL_INTEGRATION, APPROVAL, DELIVERED, ABORTED }

// ---- design & planning ----
public record Requirement(UUID id, String text, Priority priority) {}
public record ApiContract(UUID id, String name, String description, String signatureSketch) {}
public record ArchDecision(UUID id, String decision, String rationale, List<String> alternatives) {}
public record Risk(UUID id, String description, Severity severity, String mitigation) {}

public record DesignDocument(UUID id, long revision, String goal,
    List<Requirement> requirements, List<ArchDecision> decisions,
    List<ApiContract> contracts, List<Risk> risks,
    ReviewVerdict review, Instant createdAt) {}

public record TaskGraph(UUID id, long revision, UUID designId,
    List<Task> tasks, List<TaskEdge> dependencies) {}
public record TaskEdge(UUID from, UUID to) {}     // 'from' must complete before 'to'

public record Task(UUID id, long revision, String title, String instructions,
    Set<String> writeSet,                 // repo-relative paths; exclusive ownership
    Set<String> readSet,
    List<AcceptanceCriterion> criteria,
    String acceptanceTestDir,             // protected path; workers cannot write here
    UUID knowledgeBriefId,                // nullable until Librarian runs
    TokenBudget budget, SwarmPolicy swarmPolicy, TaskState state) {}

public record AcceptanceCriterion(UUID id, String text, String testClassOrFile) {}
public record TokenBudget(long maxPromptTokens, long maxCompletionTokensPerTurn,
                          long maxTotalTokens, int maxToolTurns) {}
public record SwarmPolicy(int n, boolean splitAcrossFamilies,
                          double tempMin, double tempMax, List<String> personaIds) {}

// ---- knowledge ----
public record KnowledgeBrief(UUID id, long revision, UUID taskId,
    List<LibraryDoc> libraries, List<InternalApi> internalApis,
    String renderedMarkdown /* <= 4000 tokens, deterministic */) {}
public record LibraryDoc(String coordinate, String version, String docsExcerpt) {}
public record InternalApi(String file, String signature, String docComment) {}

public record LearnedGuideline(UUID id, long revision, GuidelineScope scope,
    String slug,                          // filename-safe, unique per scope
    String markdownBody,                  // the human-editable content
    Provenance provenance, double confidence, Instant lastUsed, int useCount,
    GuidelineStatus status /* ACTIVE, PROPOSED, RETIRED */) {}
public enum GuidelineScope { GLOBAL, PROJECT, TASK_FAMILY }
public record Provenance(String source /* "extraction" | "human" */, UUID sourceRunId) {}

// ---- swarm & verification ----
public record SamplingConfig(String modelProfileId, double temperature, long seed,
                             String personaId, String contextSliceId) {}

public record CandidateSolution(UUID id, UUID taskId, int workerIndex, String branch,
    SamplingConfig sampling, String diffUnified,
    VerificationReport verification, ClusterId cluster, JudgeScore judge,
    CandidateState state /* RUNNING, KILLED, FAILED, SURVIVED, SELECTED, ARCHIVED */,
    KillReason killReason /* nullable */) {}
public enum KillReason { PARSE_FAIL, TOOLCALL_MALFORMED, BUDGET_EXCEEDED,
                         WRITESET_VIOLATION, COMPILE_FAIL_TWICE, TIMEOUT, SUPERSEDED }

public record VerificationReport(UUID id, boolean parses, boolean compiles,
    TestResults acceptance, TestResults existing, LintResults lint,
    BrowserCheckResults browser /* nullable for non-web tasks */,
    Duration wallTime, String logTail /* last 200 lines */, String fullLogRef /* lazy */) {}
public record TestResults(int passed, int failed, int errored, int skipped,
                          List<TestFailure> failures) {}
public record TestFailure(String testId, String message, String truncatedTrace) {}
public record BrowserCheckResults(List<PageCheck> checks) {}
public record PageCheck(String url, boolean loaded, List<String> consoleErrors,
                        List<AssertionResult> assertions, String screenshotRef) {}

public record ClusterId(String behavioralHash, int clusterSize) {}
public record JudgeScore(double score, String rationale, String judgeModelId) {}

// ---- context ledger ----
public record ContextCheckpoint(UUID id, UUID ownerAgentSessionId, int messageIndex,
                                String prefixHash, Instant at, String label) {}
public record FixSummary(UUID id, UUID taskId, String rootCause, String changeMade,
                         String guidelineCandidate /* nullable */, UUID debugSessionId) {}

// ---- run & decisions ----
public record Run(UUID id, WorkflowKind kind, RunState state, UUID designId, UUID taskGraphId,
                  Budget cloudBudget, Instant startedAt, RunReport report /* nullable */) {}
public record Budget(long maxCloudTokens, long usedCloudTokens,
                     Duration wallClockCeiling, long maxLocalTokensPerTask) {}
public record Decision(UUID id, UUID runId, DecisionKind kind, String briefMarkdown,
                       DecisionState state, String humanResponse, Instant createdAt) {}
public enum DecisionKind { APPROVAL, BLOCKED_TASK, GUIDELINE_REVIEW, BUDGET_EXTENSION }
```

### 4.1 BRD graph — built, previously undocumented

Added by author decision 2026-07-24; shipped and in use by the Console. The project **BRD** is the
durable, human-owned requirement graph — one per `Project`, edited in the Console and versioned —
as opposed to `DesignDocument.requirements`, which the Architect regenerates per run.

These types deviate from the "immutable records" rule above and the deviation is deliberate: they
are **mutable POJOs** with a no-arg + all-args constructor, dual accessors (`x()` and `getX()`),
setters and value `equals`/`hashCode`, annotated `@DataModel` so zeroz4j serializes them straight
over the RMI wire with no DTO (the direct-model pattern, 2026-07-24). EclipseStore stores them by
reference; see §5.1 for the lazy-store consequence.

```java
public class Brd {                      // @DataModel — one per project, keyed by projectId
    UUID id; UUID projectId; long revision; String title;
    List<BrdRequirement> requirements;  // the nodes
    List<BrdEdge> edges;                // the typed relationships
    Instant createdAt; Instant updatedAt;
}

public class BrdRequirement {           // @DataModel — a pure node; edges live on the Brd
    UUID id;
    String handle;                      // stable human label R1, R2, … (used in traceability links)
    String title; String text;
    Priority priority; RequirementStatus status;
    String category;                    // optional grouping / section
}

public class BrdEdge { UUID from; UUID to; RequirementRelation relation; }   // @DataModel

public class BrdRevision {              // @DataModel — append-only history, one per mutation
    UUID id; UUID projectId; long revision; Instant at;
    String author;                      // "human" | "agent" | "extraction" | "restore"
    String summary;                     // "added R3 (Login)", "edited R1", …
    Brd snapshot;                       // deep copy at that revision — history never mutates
}

public enum RequirementRelation { DEPENDS_ON, REFINES, CONFLICTS_WITH, DERIVED_FROM }
public enum RequirementStatus   { DRAFT, ACTIVE, IMPLEMENTED, DEPRECATED }
```

`DesignDocument` carries an added `List<UUID> brdRequirementIds` (null-guarded — reads through
`brdRequirementIds()` return `List.of()` on records written before the field existed) intended to
link a run's design back to the BRD requirements it addresses. It sat unwritten for a long time; the
story-scoped Architect of §14.1 is now its writer, and the field joins `equals`/`hashCode` so a
design republished on a signal is not silently deduped away.

### 4.2 Built (2026-07-25) — requirements, criteria, stories, iterations

**Status: built.** The normative document is **`docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md`** — §2 there
carries the full field tables, lifecycles and rationale, and is authoritative over the summary below.
Recorded here so this section is not silently wrong about the domain model.

Everything below exists and is covered by tests, but none of it has yet been exercised against a live
model or a real repository; the evidence is unit, store round-trip, service, browser and
scripted-LLM tests only.

Governing principle: *the requirement graph is the only source of truth; a story is a scheduled
slice of it, not a second description of it.* A story therefore carries no requirement content.
There is deliberately **no `Epic` type** — `REFINES` edges already form that hierarchy.

Changed types:

| Type | Change |
|---|---|
| `BrdRequirement` | gains `kind : RequirementKind`, `nfrCategory : NfrCategory`, `criteria : List<AcceptanceCriterion>`, `sourceRef : SourceRef`, and `contentRevision : long`. Null `kind` loads as `FUNCTIONAL`. `contentRevision` is bumped whenever the statement is materially edited. |
| `AcceptanceCriterion` | **moves from `Task` to `BrdRequirement`** and gains `status : CriterionStatus`, `verification : CriterionState`, `lastVerifiedRunId`, `lastVerifiedCommit`, `lastVerifiedAt`, and `verifiedAgainstContentRevision : long`. Criteria that outlive the work are what stop the BRD rotting; the revision stamp is what makes a criterion read STALE instead of green once the requirement's wording moves on under it. |
| `Task` | gains `storyId`, `criterionIds : Set<UUID>` (references into `BrdRequirement.criteria` instead of owning copies), `selectedCandidateId`, `commitSha`. `criteria` is retained only for `ENABLER` tasks with no requirement. The set-valued fields are why `Task` cannot travel on the wire — see §4.4 of `docs/CONSOLE_DESIGN_V2.md`. |
| `Run` | gains `storyId : UUID` — the story this run executes. |
| `DesignDocument` | `requirements` is populated *from* the story's BRD requirements rather than invented (§14); `brdRequirementIds` is finally written, and joins `equals`/`hashCode`. |
| `RequirementRelation` | gains `GATES` — an NFR constrains the target and every `REFINES`-descendant of it. |

New types, all mutable `@DataModel` POJOs following the §4.1 convention:

```java
Story           // id, projectId, key ("S1", …), kind, title, narrative, state,
                // requirementIds, criterionIds (the slice), iterationId, order,
                // origin, originRunId, rationale, author, runIds,
                // deliveredCommit, integrationCommit, prNumber, prUrl, createdAt, updatedAt
Iteration       // id, projectId, name, goal, seq, state, createdAt, closedAt
                // (stories point at the iteration; the iteration holds no story list)
SourceDocument  // id, projectId, filename, mediaType, sha256, extractedText,
                // extractedBy ("passthrough"|"pdfbox"|"poi"|"vision:<model>"), byteSize, uploadedAt
SourceRef       // documentId + locator — provenance back to the sentence a requirement came from
ChangeEvent     // id, projectId, at, actor, entityType, entityId, kind, field,
                // before, after, summary, runId — the append-only planning audit journal (§13)
CriterionVerification
                // id, criterionId, requirementId, runId, storyId, taskId, commitSha,
                // result, testRef, at — the append-only pass/fail history behind the
                // criterion's cached lastVerified* fields

enum RequirementKind { FUNCTIONAL, NON_FUNCTIONAL }
enum NfrCategory     { PERFORMANCE, SECURITY, RELIABILITY, USABILITY,
                       MAINTAINABILITY, OPERABILITY, COMPLIANCE, PORTABILITY }
enum CriterionStatus { PROPOSED, ACCEPTED, RETIRED }
enum CriterionState  { UNVERIFIED, PASSING, FAILING }
enum StoryKind       { DELIVERY, ENABLER }
enum StoryState      { DRAFT, READY, RUNNING, REVIEW, DONE, BLOCKED, CANCELLED }
enum StoryOrigin     { BACKLOG, AD_HOC, DISCOVERED }
enum IterationState  { PLANNING, ACTIVE, CLOSED }
enum ChangeEntityType{ REQUIREMENT, CRITERION, STORY, ITERATION, TASK }
enum ChangeKind      { CREATED, UPDATED, STATE_CHANGED, LINKED, UNLINKED,
                       PROMOTED, TOMBSTONED, RESTORED }
enum VerificationResult { PASSING, FAILING }
```

Eleven enums rather than the eight the design table lists: the three history enums arrived with the
audit journal of `REQUIREMENTS_AND_BACKLOG_DESIGN.md` §13, which the §2 field tables predate.

---

## 5. Persistence (`sc-store`)

### 5.1 EclipseStore

Single `EmbeddedStorageManager`, storage directory `~/.swarmcoder/store/` (configurable). Root object:

```java
public final class StoreRoot {
    public final Map<UUID, Run> runs = new HashMap<>();
    public final Map<UUID, DesignDocument> designs = new HashMap<>();
    public final Map<UUID, TaskGraph> taskGraphs = new HashMap<>();
    public final Map<UUID, KnowledgeBrief> briefs = new HashMap<>();
    public final Map<UUID, LearnedGuideline> guidelines = new HashMap<>();
    public final Map<UUID, Decision> decisions = new HashMap<>();
    public final Map<UUID, Lazy<CandidateArchive>> candidateArchives = new HashMap<>(); // bulky
    public long schemaVersion = 1;
}
```

**Single-writer rule:** all mutations go through `ArtifactStore` (one class, one internal `ExecutorService` with a single platform thread). Public API is command-shaped (`store.append(run, updatedTask)`), returns the new revision. Reads are lock-free snapshots. Any code path writing to `StoreRoot` outside `ArtifactStore` is a bug; add an ArchUnit rule.

Bulky payloads (full verification logs, archived diffs, screenshots) are stored as files under `~/.swarmcoder/blobs/<sha256>` and referenced by hash (`fullLogRef`, `screenshotRef`); `CandidateArchive` holds refs, wrapped in `Lazy<>`.

Schema evolution: bump `schemaVersion` and register EclipseStore Legacy Type Mapping handlers whenever a record changes shape. Add a CI test that opens a checked-in fixture store from the previous version.

The root has grown past the v1 sketch above; `schemaVersion` is **7** as built. Roots added since
carry a **null-guard accessor** and the field is private, because EclipseStore does **not** run field
initializers when loading a store written before the field existed — it loads as `null`. Never touch
those fields directly:

```java
/** Per-project BRDs (schema v5), keyed by projectId — exactly one living BRD per project. */
private Map<UUID, Brd> brds = new HashMap<>();
/** Append-only BRD revision history (schema v6), keyed by projectId. */
private Map<UUID, List<BrdRevision>> brdRevisions = new HashMap<>();

public synchronized Map<UUID, Brd> brds() {
    if (brds == null) { brds = new HashMap<>(); }      // pre-v5 store: field loads as null
    return brds;
}
```

`ArtifactStore` exposes `getBrd(projectId)`, `saveBrd(brd)`, `saveBrd(brd, author, summary)` (the
history-writing form), `listBrdRevisions(projectId)`, `brdRevisionSnapshot(projectId, revision)` and
a static deep `copyOf(Brd)`. Both new roots join `storeAll(...)`.

> **Lazy-store trap.** These roots hold *mutable* POJOs (§4.1), not immutable records. Calling
> `storeAll(container)` does **not** deep-store an object the store already knows whose collections
> were mutated in place. Every mutation path must `store(...)` the mutated object explicitly.

**Built (2026-07-25) — schema v7.** Per `docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md` §3 and §13, the
root carries **six** further maps, each with the same null-guard accessor, and existing stores open
without migration: `stories : Map<UUID, Story>`, `iterations : Map<UUID, Iteration>`,
`sourceDocuments : Map<UUID, SourceDocument>`, `tasks : Map<UUID, Task>`,
`changeEvents : Map<UUID, List<ChangeEvent>>` (keyed by projectId) and
`criterionVerifications : Map<UUID, List<CriterionVerification>>` (keyed by criterion id). The last
two are the append-only journals of §13 — the planning layer's audit trail and the criterion
pass/fail history — which is why v7 is six maps rather than the four §3 lists. Neither is ever
mutated or pruned. The task map is a **by-reference index, not a copy** — EclipseStore persists the object
graph by reference, so putting the same `Task` instances that already live in `TaskGraph.tasks` into
`tasks` stores one copy. It exists to give tasks stable, directly addressable identity for the
backlog panel and git linkage; `TaskGraph` is unchanged and remains the run's execution DAG.

`ArtifactStore` gained the matching CRUD, `copyOf(Story)`, per-project `S1, S2, …` key minting, and
the journal appenders. It is verified by store round-trip and reachability tests; no live run has
written to a v7 store yet.

### 5.2 Git meta-branch export

Every `ArtifactStore.append(...)` also serializes the changed artifact to pretty JSON at `meta/<type>/<uuid>.json` and commits to branch `swarmcoder/meta` of the target repo (JGit, batched: flush at most every 5 s). This is the human-auditable trail; EclipseStore is the runtime source of truth.

### 5.3 Learned guidelines — human-readable and human-editable (requirement)

Guidelines are dual-homed with **files as the editing surface and the store as the index**:

Each ACTIVE or PROPOSED guideline is mirrored to a markdown file:
`<repo>/.swarmcoder/guidelines/<scope>/<slug>.md`, with YAML front-matter (`id`, `scope`, `status`, `confidence`, `provenance`, `lastUsed`) and `markdownBody` as the content. Rules:

1. On startup and before every prompt-prefix assembly, `GuidelineSync` reconciles files ↔ store. **A human file edit always wins**: changed body ⇒ new revision with `provenance.source = "human"` and confidence pinned to 1.0 (human-authored guidelines never decay). Deleted file ⇒ status RETIRED. New file dropped in the directory ⇒ imported as ACTIVE/human.
2. Machine-extracted guidelines (§12.4) enter as `PROPOSED` and are written to the directory with `status: proposed` front-matter. They are NOT injected into prompts until promoted. Promotion happens either by a human editing status to `active` (or via the TUI decision queue, `DecisionKind.GUIDELINE_REVIEW`) or — if config `guidelines.autoPromote=true` — automatically after appearing in ≥2 distinct successful runs.
3. Prompt injection renders ACTIVE guidelines (sorted by scope specificity then confidence, hard cap configurable, default 3K tokens) into the shared prefix, deterministically.
4. The guidelines directory is committed to the meta branch, not the working branches, unless config `guidelines.commitToMain=true`.

---

## 6. Agent runtime facade (`sc-runtime`)

### 6.1 `AgentRuntime` interface (the only Koog boundary)

```java
public interface AgentRuntime {
    AgentSession open(RoleId role, PromptBundle prefix, List<ToolBinding> tools,
                      SessionOptions opts);           // opts: budget, checkpointing on/off
    // AgentSession: turn-based; every call may stream
    interface AgentSession extends AutoCloseable {
        TurnResult next(TurnInput input);              // appends input, runs until tool call/final
        ContextCheckpoint checkpoint(String label);
        void rollback(ContextCheckpoint cp);           // truncates history to checkpoint
        AgentSession fork(PromptBundle childSeed);     // fresh child session (debug quarantine)
        void compress(CompressionRequest req);         // delegates to Koog history compression;
                                                       // fires ContextLedger hooks (§12.3)
        TokenUsage usage();
    }
}
```

Implementation notes: Koog graph strategies implement the *workflow* level (sc-workflow); `AgentSession` wraps Koog's agent/session objects for the *turn* level. Koog persistence checkpoints are enabled for all cloud-role sessions and for the workflow state machine itself (crash ⇒ resume). One virtual thread per live session. All Koog exceptions are translated to `AgentRuntimeException` at the boundary.

### 6.2 ModelProfile registry

```java
public record ModelProfile(String id, Uri endpoint, String servedModelName,
    ToolDialect toolDialect /* QWEN3_CODER, HERMES, OPENAI, GLM */,
    int contextCeiling, double kvBytesPerTokenEstimate,
    SamplingDefaults defaults, Set<Quirk> quirks /* REASONING_TAGS, STRUCTURED_OUTPUT, ... */,
    CostPerMTokens cost /* zero for local */) {}
```

Profiles are pure config (§17). Role→profile mapping is also config, and **design-time and judge-time roles MAY use different model families** (requirement): the default config maps `architect`,`testAuthor`,`librarian` to family X (e.g. GLM-5.2) and `designReviewer`,`judge`,`approver` to family Y (e.g. a DeepSeek-class or other cheap-frontier endpoint) for de-correlation. All cloud calls go through one `CloudGate` that enforces `Budget.maxCloudTokens` (throw `BudgetExhausted` ⇒ run parks a `BUDGET_EXTENSION` decision).

### 6.3 Prompt assembly (prefix discipline)

`PromptBundle` = ordered, deterministic segments: `[systemRole, workflowRules, guidelines, designExcerpt, taskInstructions, knowledgeBrief, repoMap]` followed by per-worker segments `[persona, samplingHints]`. The assembler MUST produce byte-identical shared segments for all workers in a group (assertion: hash logged per dispatch as `prefixHash`). Append-only within a session: earlier messages are never edited (prefix-cache preservation); compaction replaces the tail, not the head.

---

## 7. Inference layer (`sc-inference`)

### 7.1 Client

OpenAI-compatible chat-completions client (Java `HttpClient`), streaming SSE, tool-call parsing per `ToolDialect`, structured output via vLLM `guided_json` (pass JSON schema in `response_format`/extra body per vLLM version). Retries with jitter on 5xx/timeouts; a request is idempotent (temperature+seed fixed per candidate).

### 7.2 Structured outputs

Every non-freeform model output (design fields, task submissions, judge verdicts, classifications) is requested against a JSON schema generated from the target record via a small `SchemaGen` utility (Jackson-based). Parse failures on schema-constrained local calls indicate a serving bug — log loudly, retry once, then kill candidate with `TOOLCALL_MALFORMED`.

### 7.3 Admission-control scheduler

One `InferenceScheduler` per vLLM instance. Tracks: live sequences (cap: instance `--max-num-seqs`), estimated KV in flight (Σ contextTokens × `kvBytesPerTokenEstimate`, cap from config), and a dispatch queue. Group dispatches are staggered (config `dispatch.staggerMs`, default 1500) rather than fired at t=0. The swarm engine requests capacity as `(profileId, estMaxContext)` leases; leases are released on candidate completion/kill. If both worker instances are live, the swarm engine requests leases per family per the split policy (§3.3).

---

## 8. Sandbox subsystem (`sc-sandbox`)

> **Status (2026-07-25): built, and different from the design below in two ways that were forced by
> measurement rather than chosen.** The container transport is the Docker Engine `/exec` API, not an
> HTTP action server on a published port; and `allowedHosts` is parsed but NOT enforced. Both are
> explained in §8.3 and §8.2. Sandboxing is now **enabled by default**.

### 8.1 Model

One Docker container per live worker candidate. Container = toolchain image + bind-mounted git
worktree. Managed via docker-java.

**Enabled by default** (`sandbox.enabled`, default true). It previously defaulted to off, which meant
the shipped behaviour was model-authored shell commands running on the operator's workstation with
the operator's privileges — the wrong default for a tool whose job is running code it did not write.

**A sandbox that will not launch fails the candidate** (`sandbox.required`, default true) rather than
silently falling back to host execution. Three separate sites used to warn and continue on the host,
so a Docker hiccup removed the only isolation layer mid-run with nothing but a log line to show for
it. Startup states the posture explicitly and names both remedies when Docker is unreachable.

Pooling (recycled containers, pool size = max concurrent workers + 2) is **not implemented**; a
container is created and removed per candidate.

### 8.2 Image (`sc-sandbox-action-server/Dockerfile`)

One image today — `swarmcoder-worker:latest`, Temurin 25 + Maven 3.9 + git. The per-language
`sc-web`/`sc-rust`/`sc-python` images are not built.

Hardening, all verified against a real container by `DockerSandboxLiveTest`:

- non-root (`uid 1000`), `no-new-privileges`, **all** capabilities dropped
- read-only rootfs; the only writable places are `/workspace` (the worktree bind), `/tmp` and
  `$HOME`, both tmpfs
- `--network none` by default; `bridge` is the opt-in alternative
- CPU, memory and pids limits from config

**`tmpfs` mounts must spell out `exec`.** Docker applies `noexec` to every `--tmpfs` unless told
otherwise, which breaks Maven's native jansi with `failed to map segment from shared object`. The
live test asserts the resulting *mount* by executing a script from `/tmp`, not the option string —
the option string was the thing that was wrong.

**Maven needs a writable repository even offline.** The host `~/.m2` is mounted **read-only** at
`/opt/m2-ro` and used as a chained repository *tail*, with the writable head on tmpfs
(`-Dmaven.repo.local=/tmp/m2 -Dmaven.repo.local.tail=/opt/m2-ro/repository`). Mounting `~/.m2`
read-write was rejected: a sandbox could then plant artifacts that the operator's own
non-sandboxed builds later resolve and execute, which is a worse hole than the one being closed.
`overlayfs` was rejected because it needs `CAP_SYS_ADMIN`, which contradicts dropping all
capabilities.

**Egress allowlist: `allowedHosts` is parsed, surfaced and WARNed about, but not enforced.** The
allowlist proxy this section originally specified (a dedicated docker network whose only route out is
a filtering proxy container) is not built. The real choice today is `none` (no egress) or `bridge`
(all egress). This is stated in the config javadoc and at startup so the field cannot quietly look
like it is doing something.

### 8.3 Container transport

**Commands travel over the Docker Engine `/exec` API** (`SandboxExecTarget`), not over HTTP to an
action server on a published port.

The HTTP design does not survive `--network none`: Docker reports an empty host binding for a
published port on a container with no network, and an `--internal` bridge behaves the same way, so
the action server would be permanently unreachable. Rather than weaken the network policy to keep
the transport, the transport was changed to one that needs no network at all. The action server and
its protocol remain in the image and stay exercised under `network: bridge`.

```
POST /exec    {cmd, cwd, timeoutSec, env?}      -> {exit, stdoutTail, stderrTail, logRef}
POST /read    {path, maxBytes?}                 -> {content|error}
GET  /health
```

`/write` and `/apply` are **not** used by the orchestrator: file mutation happens host-side in the
worktree (which is the same directory the container has bind-mounted), so the write-set decision
lives in one place — `PathPolicy` — instead of being duplicated in the container. See
DEVELOPER_CORRECTIONS.md §13 for the containment model, including the audit that reverts anything a
shell command writes outside its write set.

### 8.4 Git topology (`sc-git`)


Bare mirror at `~/.swarmcoder/repos/<name>.git`. Branches: `swarm/<taskId>/<idx>` per candidate; `swarm/task/<taskId>` integration per task; `swarm/integration` per run; `swarmcoder/meta`. Worktrees under a fast local path (`~/.swarmcoder/wt/<candidateId>`, inside WSL2 on Windows). Selection = squash the winning branch onto `swarm/task/<taskId>`; losers moved to `refs/swarm-archive/<runId>/...`. Cross-task integration merges in topological order with Mergiraf configured as merge driver (`.gitattributes` + `mergiraf` binary invoked by JGit's external merge driver hook); write-set disjointness makes conflicts exceptional — any conflict ⇒ task marked BLOCKED, decision queued.

---

## 9. SyntaxService (`sc-syntax`)

Wraps tree-sitter-ng. Grammars: java, html, css, rust, typescript, tsx, python, json, yaml. API:

```java
public interface SyntaxService {
    ParseVerdict parse(Language lang, byte[] source);          // errors with ranges
    RepoMap repoMap(Path repoRoot, Set<String> includeGlobs);   // signatures-only compressed map,
                                                                // deterministic ordering, token-capped
    List<SymbolSig> signatures(Path file);
    String normalizeForClustering(Language lang, String diffHunkContext); // strip comments,
                                                                // normalize whitespace/identifier-format
}
```

RepoMap output is markdown, deterministic (sorted paths, stable truncation), token-capped (config, default 6K) — it is part of the shared prefix so determinism is mandatory. Cache keyed by (path, contentHash).

---

## 10. Verification harness (`sc-verify`)

### 10.1 Contract

```java
public interface Verifier {
    VerificationReport verify(SandboxHandle sandbox, Task task, VerifySpec spec);
}
```

`VerifySpec` comes from per-repo config `.swarmcoder/verify.yaml` (checked into the target repo or supplied at intake):

```yaml
toolchain: gradle            # gradle | maven | node | cargo | python
compile:  ["./gradlew compileJava compileTestJava -q"]
acceptance: ["./gradlew test --tests 'swarm.accept.*' -q"]   # runs ONLY the protected acceptance dir
existing:  ["./gradlew test -q -x :acceptanceTests"]
lint:      ["./gradlew checkstyleMain spotbugsMain -q"]
browser:                     # optional block => BrowserCheckResults
  serve: "./gradlew bootRun --args='--server.port={PORT}'"
  readyProbe: "http://localhost:{PORT}/actuator/health"
  checks:
    - url: "/"
      assertNoConsoleErrors: true
      assertVisible: ["#main-nav", "text=Welcome"]
      screenshot: true
    - url: "/login"
      assertVisible: ["form#login"]
```

### 10.2 Pipeline order (fail-fast, cheapest first)

parse (already gated at write time) → compile → acceptance tests → existing tests (regression) → lint → browser checks. Each stage populates the report; any hard failure short-circuits later stages but the report still records what ran. Structured result parsing: JUnit XML for JVM, `--reporter json` equivalents for node/cargo/pytest.

### 10.3 Browser-level checks (requirement)

Playwright-Java runs **on the workstation**, targeting the app served **inside the sandbox** (the sandbox network exposes the app port only to the workstation). Headless Chromium; per-check artifacts: console log, failed-assertion details, PNG screenshot → blob store. Deterministic viewport (1440×900), animations disabled, network idle wait. Browser checks run only for tasks whose write-set intersects configured web roots or whose VerifySpec has a `browser` block. Acceptance-test authoring (§14, TEST_AUTHORING) MAY generate additional Playwright specs into the protected test dir; those run as part of `acceptance`.

### 10.4 Toolchain priority

M1: Gradle + Maven (enterprise Java) and the browser harness (HTML/CSS reachable through any served app, plus a static-site mode that serves `dist/` via a throwaway http server). M3+: Node/TS (vitest/jest), Rust (cargo test), Python (pytest).

---

## 11. Swarm engine (`sc-swarm`)

### 11.1 Dispatch

For a READY task: assemble shared PromptBundle (assert byte-identical `prefixHash` across the group); build the diversity matrix over `SwarmPolicy` — axes: model family (if `splitAcrossFamilies`), temperature (linspace tempMin..tempMax), seed, persona (`sc-swarm/resources/personas/*.md`: `minimal-diff`, `test-literalist`, `defensive-edges`, `refactor-friendly`), context slice (`full-files` vs `signatures-only` for half the group). Acquire leases (§7.3), create branches/worktrees/sandboxes, run each worker loop on its own virtual thread.

### 11.2 Worker loop

Dumb by design (mini-SWE-agent shape): system prefix + task; tools = `exec`, `read`, `apply_diff`, `lookup_api`, `report_done(summaryJson)`. Loop until `report_done`, budget exhaustion, or kill. Every tool result is truncated (config caps). The loop consults the Context Ledger triggers (§12.2) after each verification-flavored failure.

### 11.3 Early-kill rules (evaluated after every turn)

Kill with recorded `KillReason` when: two consecutive malformed tool calls; token budget or turn cap exceeded; second write-set violation; second compile failure *(only when ≥2 other candidates in the group have already compiled — don't kill everyone on a hard task)*; wall-clock timeout; or `SUPERSEDED` (task already SELECTED by an earlier finisher during repair rounds). Freed leases return to the pool; the scheduler MAY backfill an extra diversity cell if group wall-clock budget allows.

### 11.4 Clustering

For SURVIVED candidates: (1) syntactic dedup — `SyntaxService.normalizeForClustering` over the diff, SHA-256 ⇒ identical hash = same cluster; (2) behavioral hash — vector of (acceptance results, existing-test results, probe outputs) where probes are judge-model-generated I/O checks executed in a sandbox (skip probes in M2; add M4). `ClusterId.clusterSize` = multiplicity (self-consistency weight).

### 11.5 Judging & selection

Judge (cloud, family Y) receives: task, criteria, per-cluster one representative diff + verification summary + cluster size. Output (schema-constrained): ranked list with scores and rationale. Selection = argmax(judgeScore, tie-break clusterSize, then smallest diff). If zero SURVIVED: one repair round — pick 2–3 best FAILED candidates (most acceptance tests passed), fork repair swarm (N=4–6) seeded with their branches + their failing output + a Librarian-refreshed brief when failure classification (utility model) says `API_MISUSE`. Zero survivors after repair ⇒ Task BLOCKED, Decision queued with the full evidence brief.

---

## 12. Context Ledger (`sc-knowledge`)

### 12.1 Checkpoints

`AgentSession.checkpoint(label)` is called automatically at: session open (post-prefix), after each successful `report_done`-shaped milestone, and immediately before the first failure-reaction turn (see 12.2). Checkpoints store message index + prefixHash; rollback truncates history (restoring exact prefix ⇒ vLLM prefix-cache hit).

### 12.2 Debug fork (context quarantine)

Trigger: worker receives its first failing verification/test tool result in a session (mechanical detection: tool result classified `FAILURE` by pattern + utility model). Action: checkpoint parent at the pre-failure index; `fork()` a **DebugSession** seeded with a compact brief only (task, current diff, failing output, ≤3 relevant file slices via read-set + stack-trace paths). Child budget: 40% of the task's remaining tokens, max 15 turns. On child success (verification passes in child's sandbox): child emits `FixSummary`; parent rolls back to checkpoint, `FixSummary` is appended as a single message, parent continues (usually straight to `report_done`). On child failure: discard child; either re-fork with a new seed (once) or fail the candidate normally. Parent transcripts MUST NOT contain stack traces — assert in tests.

### 12.3 Compaction hook

When `compress()` fires (75% of budget): (a) Koog produces the in-context summary; (b) the ledger embeds the full discarded span (chunked ~512 tokens, bge-small ONNX) into a Lucene HNSW index at `~/.swarmcoder/rag/` with metadata (runId, taskId, phase, timestamps); (c) guideline extraction (12.4) runs on the discarded span. MCP tool `search_history(query, k, filter?)` exposes the index to all agents.

### 12.4 Guideline extraction

Utility model prompt over discarded spans + every FixSummary: extract candidate durable instructions (user-stated constraints, repo conventions, root-cause lessons). Dedup semantically against existing guidelines (cosine > 0.88 ⇒ merge, bump useCount). New ones enter as PROPOSED per §5.3. Decay: machine-sourced ACTIVE guidelines unused for `guidelines.decayRuns` (default 20) runs drop confidence 10%/run below threshold ⇒ RETIRED. Human-sourced never decay.

---

## 13. Librarian (`sc-knowledge`)

Runs during PLAN, one pass per task: parse dependency manifests (Gradle/Maven POM, package.json, Cargo.toml, pyproject) with exact versions; intersect with the task's read/write sets and instruction text (utility-model relevance pass) to pick ≤6 libraries; fetch docs via **Context7 MCP client** (primary), web search adapter (fallback), pin to the manifest version; extract internal API signatures for the read-set via SyntaxService. Emit `KnowledgeBrief` with `renderedMarkdown` ≤ 4K tokens, deterministic ordering. Also pre-warm the **docs index** (Lucene, separate from RAG index) with the full fetched docs so `lookup_api(library, symbol)` answers locally; on miss, Librarian is called synchronously (rate-limited) and the index updated. Workers never get web tools.

---

## 14. Workflows (`sc-workflow`)

Each workflow is a Koog graph strategy over typed states with mechanical invariants checked at every transition. Shared invariants: cloud budget respected; every artifact append succeeds before transition; crash-resume restores the last state.

**GREENFIELD / ENHANCEMENT:** INTAKE → DESIGN (architect interviews via MCP field-writes; bounded ≤10 questions) → DESIGN_REVIEW (family-Y reviewer, rubric: completeness/testability/write-set partitionability; ≤2 loops then proceed with recorded objections) → PLAN (TaskGraph; reject+regenerate on invariant violation: disjoint write-sets among concurrently schedulable tasks, ≥1 executable criterion per task, acyclic, budget-estimable; Librarian briefs per task) → TEST_AUTHORING (tests into protected dir; MUST fail on pre-change code — "red check" executed mechanically) → EXECUTING (task loop §11; 2–3 groups concurrently per TaskGraph readiness) → FINAL_INTEGRATION (topological merges, full verify after each) → APPROVAL (approver model review of integrated diff vs design; then human, or decision-queue in overnight mode) → DELIVERED (branch + summary; never pushes to main).

**BUGFIX:** INTAKE(repro description) → REPRODUCE (single worker must produce a failing, committed test in protected dir; cannot proceed without mechanical red) → swarm on fix → verify (red→green + no regressions) → APPROVAL.

**REFACTOR:** CHARACTERIZE (test author writes characterization tests asserting current behavior; green check) → swarm → verify (all green, before and after) with extra selection signal: minimal normalized-AST distance for equal behavior → APPROVAL.

**DOCS / ANALYSIS:** no swarm, single worker / read-only session; ANALYSIS has empty write-sets and produces a typed report artifact only.

**Overnight mode** (flag on run start): human gates become `Decision` records; run continues on unblocked TaskGraph branches; terminal output is `RunReport` (leaderboards per task, integrated diff stats, approver summary, decision queue, spend). Hard stops: `Budget` fields; on breach, park and checkpoint.

### 14.1 Built (2026-07-25) — story-scoped DESIGN and PLAN

**Status: built, and unproven in anger.** Normative detail: `docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md`
§6; the domain deltas it depends on are summarised in §4.2 above. The Architect has never run against
a live model or a real repository under this design — the behaviour described here is established by
`ArchitectScopedDesignTest`, `StoryScopeTest` and `TaskGraphValidatorTest`, which drive a scripted
LLM. Read every claim below as "the code does this", not as "we have watched it work".

`ArchitectClient.design(goal)` invents its own requirement list from the goal string, and that list
dies with the run. It survives only as the fallback for an ad-hoc run that answers to no story
(`design(goal, scope)` and `plan(design, goal, scope)` delegate to the unscoped overloads when the
scope is empty). When a run *does* carry a story, the Architect is **story-scoped**:

- `design(goal, StoryScope)` — the scope is the story, its linked `BrdRequirement`s with their
  acceptance criteria, and every **inherited NFR gate** (an NFR reaches the story through a `GATES`
  edge to any requirement the story delivers, or to any `REFINES`-ancestor of one); `StoryScope`
  resolves it from the BRD. The model contributes decisions, contracts and risks **only**:
  `DesignDocument.requirements` is populated *from* the BRD, carrying the BRD requirement ids rather
  than minting parallel ones, and `brdRequirementIds` is written. If the Architect thinks a
  requirement is missing it emits a `DRAFT` proposal to triage — silent invention is not possible.
- `plan(design, goal, StoryScope)` — every task declares the `criterionIds` it satisfies, drawn from
  the story's slice and named in the prompt as `R7:C1`-style refs. Each task's verification gate is
  its criteria's tests plus the story's inherited NFR fitness tests.

`GreenfieldWorkflow` resolves the scope once per phase from `Run.storyId`, records proposals to
triage, and on integration uses `CriterionEvidence` to decide the story's fate: it moves to `REVIEW`
when every criterion in the slice has passing evidence and to `BLOCKED` otherwise. It never moves a
story to `DONE` — that is the operator's acceptance (`BacklogService.acceptStory`), because "the
tests pass" and "this is what I asked for" are different claims.

`TaskGraphValidator` carries a **criterion-coverage check, as a violation rather than a warning**:
every criterion in the story's slice must be claimed by at least one task, and no task may claim a
criterion from outside the story. An uncovered criterion means the story can never reach `REVIEW`, so
the graph is rejected and regenerated like any other invariant breach. The existing acyclicity and
write-set-disjointness checks are unchanged, and the "no executable criteria" *warning* still applies
to unscoped graphs. `GreenfieldWorkflow.fallbackGraph` produces a single task claiming all of the
story's criteria.

---

## 15. MCP + ACP server (`sc-server`)

**MCP server** (official Java SDK, streamable HTTP on localhost): tools — `get_task`, `get_design`, `submit_design_field`, `submit_task_graph`, `report_progress`, `submit_candidate_summary`, `list_criteria`, `get_knowledge_brief`, `lookup_api`, `search_history`, `get_guidelines`, `propose_guideline`, `queue_decision`; resources — read-only JSON views of runs/tasks/decisions. All tool I/O schemas generated from `sc-domain` records via `SchemaGen`; server validates strictly and returns structured errors.

**ACP server** (Koog's ACP support): session lifecycle, streaming progress events (per-task leaderboard deltas, stage transitions, decisions), user prompts (intake, interview answers, approvals). The TUI is a thin ACP client; third-party ACP clients therefore work too.

---

## 16. TUI (`sc-tui`)

Lanterna. Screens: Run dashboard (stage, groups, per-task candidate leaderboard: state/verification/cluster/score), Decision queue (render `briefMarkdown`, accept/reject/free-text), Guidelines browser (list, open-in-$EDITOR the mirrored file, promote/retire), Intake wizard, Report viewer. Keep it modest — the ACP surface is the real interface; TUI ships enough to run unattended sessions and review mornings.

---

## 17. Configuration

Single file `~/.swarmcoder/config.yaml` (+ per-repo `.swarmcoder/verify.yaml`, guidelines dir). Example (normative keys):

```yaml
spark:
  instances:
    - id: qwen3-coder-next
      baseUrl: http://spark.local:8000/v1
      servedModelName: Qwen3-Coder-Next-NVFP4
      toolDialect: QWEN3_CODER
      contextCeiling: 65536
      kvBytesPerTokenEstimate: 900        # measure in M0
      maxNumSeqs: 16
    - id: qwen35-35b
      baseUrl: http://spark.local:8001/v1
      servedModelName: Qwen3.5-35B-A3B-NVFP4
      toolDialect: QWEN3_CODER
      contextCeiling: 65536
      maxNumSeqs: 16
cloud:
  endpoints:
    glm52:      { baseUrl: https://api.z.ai/...,  apiKeyEnv: ZAI_API_KEY }
    deepseek:   { baseUrl: https://api.deepseek..., apiKeyEnv: DEEPSEEK_API_KEY }
roles:
  requirementsAnalyst: glm52   # BRD author: documents -> requirement graph. Unset -> chat -> utility
  storyPlanner: glm52          # agreed checks -> stories.            Unset -> chat -> utility
  architect: glm52
  taskPlanner: glm52           # design -> tasks, in order. Writes no how-to. Unset -> architect
  testAuthor: glm52
  librarian: glm52
  designReviewer: deepseek     # de-correlated from architect
  judge: deepseek
  approver: deepseek
  workerFamilies: [qwen3-coder-next, qwen35-35b]
  utility: qwen35-35b
swarm:
  nPerTask: 4                 # per-task attempts; overridable per project and per story
  maxConcurrentTaskGroups: 2  # tasks of one run dispatched at once
  maxConcurrentWorkers: 8     # hard process-wide ceiling on workers running at once
  splitAcrossFamilies: true
  tempMin: 0.2
  tempMax: 1.0
  dispatch: { staggerMs: 1500 }
budgets:
  maxCloudTokensPerRun: 3_000_000
  maxLocalTokensPerTask: 2_500_000
  wallClockCeilingHours: 10
guidelines: { autoPromote: false, decayRuns: 20, maxPrefixTokens: 3000 }
sandbox: { cpus: 4, memGb: 6, poolExtra: 2 }
overnight: { enabled: true }
telemetry: { otlpEndpoint: http://localhost:4317 }
```

---

## 18. Observability & security

OpenTelemetry spans: run → stage → task → candidate → turn → tool call; attributes include prefixHash, sampling config, token usage, kill reasons. Export OTLP (Langfuse/Jaeger locally). Structured JSON logs per candidate to the blob store.

Security requirements (all mechanical, none prompt-based): sandbox egress limited to Spark ports via the proxy network; protected test/CI paths mounted read-only; write-set enforcement in the action server; dependency resolution only from the pre-primed offline cache volumes (no live registry access from sandboxes — Librarian fetches docs, humans update caches); secret scan (regex + entropy) on every integrated diff before APPROVAL; per-run cloud budget cap; total-wall-clock cap; SwarmCoder never pushes to protected branches.

---

## 19. Milestones with acceptance criteria

**M0 — Benchmarks (scripts in `sc-evals`, no product code path).**
Deliver: `deploy/spark/*` launch scripts for the dual-model layout; a load generator replaying realistic agent transcripts (8–32K prefill, tool turns) at concurrency {1,4,8,12,16,24} split across both instances; a pass@k probe (k=8) over ≥20 tasks in a sample enterprise-Java repo for each worker model.
Accept: report with aggregate/per-stream decode, TTFT (cold vs prefix-hit), measured `kvBytesPerTokenEstimate` per model, dual-model memory headroom confirmation, and pass@1 vs pass@8 per model. Go/no-go: pass@8 must materially exceed pass@1 (target ≥1.5×) for at least one model.

**M1 — Single-worker skeleton.**
Deliver: sc-domain, sc-store (EclipseStore + meta-branch), sc-runtime facade, sc-inference client, sc-git, sc-sandbox (sc-java image + action server), sc-verify (Gradle+Maven + browser block), minimal GREENFIELD without swarm (N=1, no judge), TUI intake + report, config loading.
Accept: end-to-end on a demo Spring Boot repo — typed Task in, verified green diff on a branch out, browser check screenshot in the report, crash mid-run resumes from checkpoint; ArchUnit boundaries pass.

**M2 — Swarm + selection (the product core).**
Deliver: sc-swarm complete (diversity matrix incl. dual-family split, early-kill, syntactic clustering, judge, selection, loser archival), scheduler leases, prefixHash assertion, repair round.
Accept: on 10 seeded tasks, swarm(N=8) beats single-worker success rate; zero write-set violations reach integration; archived candidates queryable in sc-evals; cloud spend per task ≤ configured cap; run report shows leaderboards.

**M3 — Workflow front-half + knowledge.**
Deliver: DESIGN/DESIGN_REVIEW/PLAN/TEST_AUTHORING with mechanical invariants (incl. red-check), Librarian + Context7 + docs index + `lookup_api`, multi-task groups + topological integration with Mergiraf, BUGFIX and REFACTOR workflows.
Accept: full overnight GREENFIELD run on the demo repo completes unattended with a morning report and a populated decision queue; an injected unknown-library task succeeds only when the Librarian brief is enabled (A/B test in sc-evals).

**M4 — Context Ledger + guidelines + polish.**
Deliver: checkpoints/rollback, debug fork, compaction hook + Lucene RAG + `search_history`, guideline extraction + file mirror + TUI review, behavioral probes in clustering, DOCS/ANALYSIS workflows, secret scan, full RunReport.
Accept: a scripted "devolves into debugging" scenario leaves the parent transcript free of stack traces (asserted); editing a guideline file changes the next run's prefix (asserted via prefixHash delta); RAG lookup returns a prior run's fix for a repeated error.

---

## 20. Testing strategy

Unit tests per module (domain invariants, schema round-trips, guideline sync reconciliation table-driven tests). Integration: Testcontainers for sandbox/action-server and JGit topologies; a **FakeVllm** (in-process HTTP) with scripted completions for deterministic swarm-engine tests including kill paths and clustering; Playwright against a fixture site. Store-evolution fixture test (§5.1). ArchUnit: Koog confined to sc-runtime, tree-sitter to sc-syntax, single-writer store rule. One slow nightly e2e against a real Spark endpoint, tagged and excluded from CI.

## 21. Conventions for the implementing agent

Pure Java 21; records for data, sealed interfaces for state hierarchies; no reflection-based DI (manual wiring in sc-app); no static mutable state; virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`; all time via injected `Clock`; all randomness via injected seeded `RandomGenerator`; every public API of every module has Javadoc; TODOs are forbidden in delivered code — open questions go in `NOTES.md` per module. Build must pass `mvn -q verify` including ArchUnit and Spotless (google-java-format). Implement milestones strictly in order; do not start M(n+1) before M(n) acceptance tests pass.
