# Changelog

All notable changes to SwarmCoder are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with the pre-1.0 caveat that breaking
changes may land in a minor version while the design settles.

SwarmCoder is experimental. Read each release's **Breaking** section before upgrading.

## [Unreleased]

### Fixed

- **The requirements graph is readable.** A link between two boxes in one row no longer runs
  through the box between them; it goes round. The relation's name is no longer printed on each
  line (the names overprinted each other where lines crossed); it shows when you hover the line,
  and the legend still explains the colours. The picture now grows to use the panel as well as
  shrinking to fit it, follows the panel when you drag the divider or resize the window, and has
  zoom in, zoom out and Fit buttons beside the mouse wheel. The graph panel opens wider (640 px,
  up to 1400). Links between the same two requirements are drawn side by side instead of on top
  of each other.

### Changed

- **A journey that fails in the browser goes back to its author before any worker repairs
  anything.** The test author is shown the journey, the failing step and what the page showed
  at that step (roles, accessible names, placeholders, visible text, read by the browser). It
  either corrects the journey or says why the screen is wrong; the workers of the repair round
  are then told that reason. A correction is taken only when it still fails on the code the
  run started from and passes on the merged code, and has no fewer fill, click and press steps
  than the journey it replaces. Once per task. Costs one test-author session and, for a
  correction, one more build of the start tree. The run report has the outcome per task.
- **The test author is asked about a text its journey expects and nobody enters.** When
  `check_journey` is given a journey whose `expectVisible` text is typed by no earlier step and
  is in no code of the project, it asks once whether that is data (then the journey must add
  it first: the application starts with no data of its own) or a text the new screen shows by
  itself (then the same file given again is kept). The journeys' red check notes the same.
- **A worker of a task that claims a journey is told** that its screen must expose the roles,
  accessible names and texts the journey's selectors use.
- **A run refused at final integration for added code nothing can reach is sent back to the
  task that added it once**, with the refusal as the reason, before it stops.
- **A project that starts nearly empty is no longer refused its first framework-found class.**
  A run was stopped at final integration with "adds production code that nothing in the
  application can reach" for a service and a store provider the framework finds by their
  annotation. Where none of the code that was already in a part of the project is found by a
  framework, an added class nothing uses that carries an annotation is now accepted, and the
  run's messages say so ("Not established: whether the application reaches ..."). An added class
  with no annotation and no user is still refused. Nothing to do; if you had set
  `-Dswarmcoder.verify.unreachableAddedCode=off` for this, you can take it out.
- **The refusal names the class, not a class nested in it.**
- **A long role session costs less per call.** The first lines kept of a role's old lookup
  results are cut to one line each the next time its conversation is shortened; they used to
  stay for the whole session (about 20,000 tokens after 134 lookups). The log line now reads
  "old lookup result(s) were shortened".
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
