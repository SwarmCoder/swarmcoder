# Changelog

All notable changes to SwarmCoder are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with the pre-1.0 caveat that breaking
changes may land in a minor version while the design settles.

SwarmCoder is experimental. Read each release's **Breaking** section before upgrading.

## [Unreleased]

### Added

- **A journey can choose from a list and read what a control holds.** Two new steps:
  `select` with `value` chooses an option of a drop-down list or combobox by the text it shows
  (or its value), and `expectValue` with `value` checks what a field holds or what a list shows
  as chosen. Before, a journey had to click an option's text, which no browser can do in a
  closed drop-down list, so a story with such a list could not pass. The author's brief, the
  review of a failed journey and both answers of `check_journey` now list every step with what
  it is for.

### Fixed

- **A corrected journey that gets further is kept.** When a journey fails after the last merge
  and its author corrects it, the correction used to be taken only if it passed. One that
  still fails, but at a later step than the journey it replaces, is now committed as the
  journey, and the run goes on with the new failing step: the task's one worker repair round
  if it is unused, otherwise a second review by the author if one is left, otherwise the run
  stops with the better journey kept. The limits are unchanged: two reviews of a journey and
  one repair round of a task in a run. A second review that changes nothing no longer stops a
  run whose repair round is unused.
- **A journey may not begin by expecting what the application already shows.** What a journey
  expects before its first click, fill, select or key press is tried in a real browser on the
  application as it is before the story. `check_journey` does not keep a draft with such a
  step, and a correction with one is refused. The red check of the journeys logs a note for a
  journey already written. For a draft that begins by looking, this builds and starts the
  application once more while the tests are written.
- **`check_journey` refuses a role written without `role=`**, such as
  `textbox[name="Title"]`, and says how to write it. The browser reads that form as an element
  of that name and finds nothing.
- **A worker is told which names of its journey its code does not hold yet.** Under the
  journey, `acceptance_test` lists the texts and accessible names the journey's selectors use
  that no code of the worker's checkout holds at that moment. It is a note, read from the
  code's texts with no browser.
- **A journey step acts on what a person can see.** `click` and `fill` take the first visible
  match of a selector, and `expectVisible` passes when any match is visible. Before, the first
  match in the document was taken even when nobody could see it, so `expectVisible: "text=..."`
  failed on a row that was there when an option of a list read the same. `expectHidden` now
  passes only when no match is visible; a journey that relied on a hidden first match with a
  visible second one will fail.
- **The page reading at a failed step names each drop-down list**, what it shows as chosen and
  the options it offers.
- **The task that writes a screen is given the architect's findings.** A task now also gets
  the findings about what the tasks it waits for deliver, and is told those tasks' types with
  their members. Before, a task that delivered no contract of its own got none of the findings
  and was told of no type. Such a task keeps the librarian's example as well.
- **`keep_for_workers` no longer keeps the first lines of a long result.** A range longer than
  a finding carries is sent back with what to name instead; before, a whole file was kept as
  its package line and imports.
- **A plan saved before these rules is brought up to date on resume**: its tasks are given
  their findings and types again before the tests are written and before the workers start.
- **A run no longer stops for a "library" that is only a word in a rule.** A rule such as "the
  client only uses TeaVM-compilable classes" stopped a run at PLAN asking you to install
  `TeaVM-compilable`. A name in a rule or technical document now counts as a missing dependency
  only when a build can declare it: a BOM or parent pom the build inherits manages an artifact
  of that name, or the text writes it with its group (`group:artifact`). Such an artifact is
  added to the plan when the local Maven repository holds it, and the run still stops when it
  does not; the message now gives the coordinates and version to install. Any other name is a
  `PLAN: note —` line in the run and nothing else. If a rule names a library that no BOM or
  parent of the build manages, write it as `group:artifact` or it is taken as wording.
- **The design reviewer no longer asks for a test that cannot run.** In a project with a module
  that runs only in a browser, the reviewer is told what the architect is told: acceptance tests
  run on a JVM and cannot execute that module, so a check about a screen is proved at the
  service behind it. It may still object when the goal promises a screen and the design has none.
- **The log says which check sent a draft design or plan back.** Each objection of
  `check_design` and `check_plan` is one log line, with the draft's number and size.
- **A journey that fails again at a later step goes back to its author once more.** After the repair round, if the journey now fails further along than before (the earlier step passes), the author reviews it again with what the page showed there, under the same bounds and guards for a correction. A failure at the same or an earlier step is not sent back. At most two author reviews per journey; there is still only one worker repair round, and a second review that does not correct the journey stops the run with both reasons. `check_journey` now says that typing into a search or filter box does not create the record.
- **The requirements graph is readable.** A link between two boxes in one row no longer runs
  through the box between them; it goes round. The relation's name is no longer printed on each
  line (the names overprinted each other where lines crossed); it shows when you hover the line,
  and the legend still explains the colours. The picture now grows to use the panel as well as
  shrinking to fit it, follows the panel when you drag the divider or resize the window, and has
  zoom in, zoom out and Fit buttons beside the mouse wheel. The graph panel opens wider (640 px,
  up to 1400). Links between the same two requirements are drawn side by side instead of on top
  of each other.
- **Final integration can run again for the same run.** After a repair round, a corrected
  journey or a pause for the model server, the second attempt stopped with "a branch named
  'swarm/integration/<run>' already exists". Each attempt now starts clean; the earlier one is
  kept as branch `swarm/integration-attempt/<run>/<number>` so you can see why it failed. Delete
  those branches when you no longer need them.
- **A journey's author who says the journey is wrong is no longer recorded as standing by
  it.** The review of a failed journey now ends on one of two fixed words (`JOURNEY_WRONG`,
  `SCREEN_WRONG`) plus what `check_journey` kept. An author that blames its journey and hands
  in no corrected journey is asked once more in the same conversation. If there is still none,
  or its correction is refused in the browser, the run parks with a plain message and no
  worker is started. Correct the journey by hand on the run's tests branch, or resume to have
  the author asked again.
- **The review of a failed journey is short.** It has 10 turns and 12 lookups of its own
  (`-Dswarmcoder.roles.journeyReviewTurns`, `-Dswarmcoder.roles.journeyReviewLookups`), a
  session of its own, and `texts_of` answers from the code the run built. Before, its lookups
  only saw the project from before the story, where the new screen did not exist.
- **A journey is owned by a task that writes the screen.** A journey written with the tests of
  a task that writes no browser code now belongs to the last task in the plan whose write set
  holds browser code, so its workers are shown the journey and a failed journey is repaired by
  workers who may change the screen. A plan saved by an earlier version is put right when the
  run resumes.
- **`acceptance_test` no longer answers a wrong name with two lines.** Asked with a name
  nothing claimed carries (a module, a class of the code), a worker gets everything its task
  claims, the journey included. Asked for a test class, it is told in one line that the task
  also claims a journey. The repair evidence for a failed journey now holds the journey step
  by step.

### Changed

- **The architect hands what it found to the workers.** While it designs, the architect keeps
  the facts a worker will need about how the project and its framework do things (which
  annotation makes the framework find a service, how a screen is put on the entry page, a few
  lines of real code that do the same kind of thing). It marks them in a lookup it has just made
  with a new tool, `keep_for_workers`; the lines are copied from the lookup, not typed again.
  Each fact goes word for word to the workers and the test author of the tasks that build what
  it is about and of the tasks built on it, plus the project-wide ones, up to 24,000
  characters a task (`-Dswarmcoder.handover.maxChars`); one finding carries up to 40 lines. A task the architect covered is no longer also pasted the
  librarian's example; a task it kept nothing for gets the brief it always got. The run report
  shows, per task, how many findings its workers were given and their size.
- **The task planner only splits the design into tasks and orders them.** It no longer writes
  how to build a task and is no longer sent, or told to fetch, framework documentation and
  example code. A task's instructions now say what the task delivers. Every check of a plan is
  unchanged.
- **The task planner can run on its own model.** New role `roles.taskPlanner` in `config.yaml`
  and in "Models & budgets"; when it is not set the planner runs on the architect's model, as
  it always did. Nothing to do unless you want a smaller model for planning.
- **A task's files are worked out, not guessed, and a worker may take a file nobody else
  holds.** The files a task starts with are computed from the contracts it delivers: where the
  project already has the type, where its package puts a new one, and every existing file that
  stops compiling with the change. During the run, a worker that writes a file outside those:
  is allowed when no other task of the plan holds the file and nothing protects it (recorded on
  the task, shown in the run report as "Files taken beyond the plan"); is refused, with the
  task named, when a task built at the same time holds it; is refused, and the plan is blamed
  rather than the worker, when a task that has not run yet holds it. Acceptance tests,
  journeys, `.swarmcoder/`, `.git/` and locked modules are refused as before. Until now any
  source file outside the planned write set failed the candidate.

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
