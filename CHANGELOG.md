# Changelog

All notable changes to SwarmCoder are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with the pre-1.0 caveat that breaking
changes may land in a minor version while the design settles.

SwarmCoder is experimental. Read each release's **Breaking** section before upgrading.

## [Unreleased]

### Changed

- **A planning role's old lookup results are shortened at the same mark on every server.** They
  used to be left whole until a quarter of the model's room when the server reports prompt-cache
  hits (an architect averaged 45,697 input tokens a call); now the mark is 20,000 tokens (was
  24,000) either way. `swarmcoder.roles.tidyAboveTokens` still overrides it.
- **A plan that orders a task before the task that creates a type it needs is refused.** The plan
  check names the two tasks and the edge to write, so the plan is sent back instead of running a
  task against a type that does not exist yet.
- **No repair round when every candidate wrote a file that belongs to another task.** The task is
  blocked with the pair named, rather than repeating a round that cannot succeed.
- **The analyst is asked which part of the project each rule applies to** (a module or the whole
  project), when the project has parts and no rule of a batch says anything about its part.
- **A worker that changes only existing files is no longer shown a pasted example** of how the
  codebase does it; the files it changes are the example.
- **`texts_of` is available to every role**, and **`outline_of` accepts a path from the
  repository root.**

## [0.1.0] - 2026-10-07

The first public release, under the Apache License 2.0.

SwarmCoder is not feature complete and known issues are open. This release publishes the work as it
stands so it can be read, run and discussed. Nothing about its interfaces, its settings file or its
stored data is stable yet, and a later release may not be able to open a store written by this one.

### What is in it

- **One fixed lifecycle for every run**: intake, design, design review, plan, test authoring,
  execution, final integration, delivery. Features, bug fixes and refactors all go through it.
- **Tests first.** Acceptance tests are written before any code, must fail against the code as it
  stands, and cannot be changed by the workers.
- **A swarm of parallel workers**, each attempting the task in its own git worktree inside a
  container. Every attempt is verified by a real build, the survivors are judged, and the winner is
  merged and verified again.
- **Project knowledge from the syntax tree and the object graph.** The target project is parsed
  into a lossless syntax tree and a Java object graph with a search index, and agent roles look
  facts up through deterministic tools. The Eclipse JDT Language Server is used when installed.
- **Requirements and backlog**: requirements, checks and stories, with document intake from PDF and
  Office files.
- **A browser console** on `http://localhost:9090/` for setup, requirements, the pipeline and run
  reports. Run reports show tokens and lookups per role.
- **Local and cloud models**: any OpenAI-compatible endpoint. Planning roles may run on a paid
  cloud model while implementation runs locally.
- **Adopting an existing project** is partly built.

### Known limits

- Java projects built with Maven are the tested path.
- Docker is required to build anything; without it a run stops rather than running unprotected.
- **The test suite is not green.** Of about 2,500 tests, 28 fail in this release: the workflow
  tests that drive a whole run with a scripted model stop at the planning stage, two browser tests
  of the console expect wording the console no longer uses, one knowledge test and the brownfield
  tests depend on folders on the maintainer's machine. The continuous-integration build is
  therefore expected to be red until they are fixed.
- The documents under `docs/` were written during development. Several are historical, and
  [docs/README.md](docs/README.md) says which.

[Unreleased]: https://github.com/SwarmCoder/swarmcoder/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/SwarmCoder/swarmcoder/releases/tag/v0.1.0
