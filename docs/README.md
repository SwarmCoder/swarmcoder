# SwarmCoder documentation index

**Written 2026-09-05.** There was no index before; the documents had accumulated over three months and
several are superseded by newer ones without saying so. This lists all of them and what each is good
for today.

## Start here

| document | what it is |
|---|---|
| **[`Agent-Process-Flow.md`](Agent-Process-Flow.md)** | **How the system actually works today: orientation, the git topology, every pipeline stage with its gates and parks, and the worker — its tools, its brief, the read-pause, and the exec safety rules. Verified against the code at commit `e5ff802`, 2026-09-05.** |
| **[`Agent-Knowledge-And-Operations.md`](Agent-Knowledge-And-Operations.md)** | **The second half: the knowledge indexes and the help desk, the model runtime and how a worker's context is sized, the two harnesses and the recorded experiments, a problem-handling playbook (symptom → mechanism → class → the run that taught it), and the operating notes.** |
| [`DEVELOPER_CORRECTIONS.md`](DEVELOPER_CORRECTIONS.md) | The correction contract: 39 numbered sections of what was found wrong and what was done about it, each with what was run to prove it. Normative where it speaks — but its **last entry is 2026-08-29**, so it contains nothing from the September work. History, not current state. |
| [`TESTING.md`](TESTING.md) | Which tests run, when, and how you know what did not. Read before running anything: never a bare `mvn test`, one browser test per JVM, and how `@RunsWhen` gating works. |

## Current design contracts

| document | what it is |
|---|---|
| [`plans/brownfield/Design.md`](plans/brownfield/Design.md) | Taking an existing codebase and a change request. Status: design; the work is on branch `brownfield`, not on master. |
| [`plans/brownfield/Ledger.md`](plans/brownfield/Ledger.md) | What the three brownfield waves built and measured. Wave 3's live result is a placeholder — the session stopped. |
| [`CONSOLE_UX_V3.md`](CONSOLE_UX_V3.md) | The operator interface as it is: the pipeline board, the five columns, and the wording rules every enum's `label()` follows. Approved and implemented. |
| [`REQUIREMENTS_AND_BACKLOG_DESIGN.md`](REQUIREMENTS_AND_BACKLOG_DESIGN.md) | The requirements and backlog model — the BRD, checks, stories, and the agreement gate. |
| [`REQUIREMENTS_AT_SCALE_DESIGN.md`](REQUIREMENTS_AT_SCALE_DESIGN.md) | Hierarchy, tree view and search, for thousands of requirements rather than tens. |
| [`GUIDED_FLOWS_DESIGN.md`](GUIDED_FLOWS_DESIGN.md) | The intake and planning wizards' own behaviour. Still authoritative for that; its navigation half is superseded. |
| [`FLOW_INVENTORY.md`](FLOW_INVENTORY.md) | A map of every flow and whether anything tests it, ordered by damage rather than by module. 2026-08-28. |

## Reference and history

| document | what it is |
|---|---|
| [`ADOPT_EXISTING_PROJECT_DESIGN.md`](ADOPT_EXISTING_PROJECT_DESIGN.md) | Pointing SwarmCoder at a project folder it has never seen. Design; partly built. |
| [`USER_MANUAL.md`](USER_MANUAL.md) | Configuring, running and interacting with the application, for an operator rather than a developer. |
| [`OBSERVABILITY_DESIGN.md`](OBSERVABILITY_DESIGN.md) | The tracing and control foundation. Its UI half is superseded. |
| [`CONSOLE_DESIGN_V2.md`](CONSOLE_DESIGN_V2.md) | The previous console design. Partially superseded by UX v3. |
| [`ZEROZ4J_SERIALIZER_SPEC.md`](ZEROZ4J_SERIALIZER_SPEC.md) | A request to the ZeroZ Stack framework. Granted and closed 2026-08-27. |
| [`swarm-coder-architecture.md`](swarm-coder-architecture.md) | The original July 2026 rationale — the swarm thesis, the hardware argument, the prior-art survey. **Historical.** |
| [`swarmcoder-technical-spec.md`](swarmcoder-technical-spec.md) | The original July 2026 specification. **Historical**; predates every decision made during the build. |
