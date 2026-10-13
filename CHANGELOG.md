# Changelog

All notable changes to SwarmCoder are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with the pre-1.0 caveat that breaking
changes may land in a minor version while the design settles.

SwarmCoder is experimental. Read each release's **Breaking** section before upgrading.

## [Unreleased]

### Breaking

- **Nothing is sent outside for a lookup unless the settings file says `outsideLookups: true`.**
  Until now a set `CONTEXT7_API_KEY` was enough for library names and question words to go to
  the documentation server, the Researcher could search the web and fetch pages, and a
  documentation site listed among a project's reference folders was fetched. All of that is now
  off until the one line is written, and the log says so at start ("Outside lookups are off:
  ..."). With it off, `sandbox.network: bridge` is not applied either, and a page under test in
  the headless browser loads from this computer only. The table of everything that can leave
  the computer is in the user manual, "What leaves this computer".

- **SwarmCoder now needs Java 25 to run.** It is built on ZeroZ Stack 0.10.0 and its window is
  served by that release's Vidocq server binding in place of Helidon, and the binding needs
  Java 25. The build still compiles for Java 21, the start command and `run.bat` are unchanged,
  and nothing in the settings file or the store changes. To stay on Java 21, build with
  `mvn clean package -DskipTests -Phelidon`: that serves the window with Helidon as before. Use
  `clean` when switching, so `sc-app/target/libs` holds one container's jars. ZeroZ Stack marks
  the Vidocq binding as experimental. The worker container images are not affected: they build
  the target project, not SwarmCoder.

- **MCP tools that change something now need a secret.** `start_run`, `decide_run`,
  `answer_decision` and every new tool that changes something are refused unless the call
  carries `Authorization: Bearer <secret>`. The secret is made the first time the MCP server
  starts and kept in the file `mcp-secret` in the SwarmCoder home folder; it is never logged
  and no tool returns it. Tools that only read need nothing, as before. A client that used the
  three older tools must add the header:
  `claude mcp add --transport http swarmcoder <url> --header "Authorization: Bearer <secret>"`.

### Added

- **The answers `screen` and `journey` for a build that stopped on a failed journey.** When
  the final integration of a story stops on a journey - its author called the journey wrong and
  no correction could be taken, its reviews are used up, or the task's repair round is - the
  question now takes the ruling of whoever answers on which side is wrong, each with a required
  text. `screen`: the journey is right and the screen is wrong; the task whose screen the
  journey uses gets one repair round with the text as its instruction, whatever the journey's
  author answered, and the journey is not sent back to its author again. `journey`: the journey
  is wrong; it goes back to its author with the text, the correction is tried in a browser on
  the tree the run started from and on the merged tree like any correction, and it may ask for
  less than the journey it replaces. Exactly what such a correction drops (steps, fields,
  criteria no longer proved on screen) is written to the run's log, its carried warnings, the
  task's note and the decision log, and the warnings a build carried are now part of a
  delivery's evidence. `screen` is counted with `repair`
  (`-Dswarmcoder.repair.instructedRounds`); `journey` is counted per journey per build, once
  unless `-Dswarmcoder.journey.instructedCorrections` says otherwise (at most 4). The Console's
  answer box takes text that begins with either word the same way.
- **A failed journey delivered with an earlier story names a task where the code shows one.**
  Such a journey is claimed by no task of the build, so nothing could be offered for it. The
  application now reads which task's chosen change wrote a file that holds a name the journey
  looks for, or wrote a screen, and offers `screen` for that task when exactly one can be
  named; the question says which task and why. When none can be named the question says so in
  one sentence, and that only sending the story back remains for that journey.

- **The answer `retest` for a stopped build.** A question from a build that stopped at its
  build stage on one task that has an acceptance test of its own now also takes `retest` with
  a required text. The test goes back to its author with the text as the instruction and with
  what verification recorded; the correction is checked like a first-written test and committed
  on the run's tests branch; the task is verified again from the candidates it already has and
  built again only if none passes. The story is not designed again. It is listed in
  `next_attention` and `decision_text` with the task's name, and the Console's answer box takes
  text that begins with `retest` the same way. It is not offered for a fault of the plan or for
  a failed final integration. Once per task per build unless
  `-Dswarmcoder.retest.instructedRounds` says otherwise (at most 4).

- **The answer `repair` for a stopped build.** A question from a build that stopped on one
  task - a task blocked after its swarm and repair round, or a final integration that failed on
  one task's journey or added code - now also takes `repair` with a required text. The task's
  workers get one more repair round, started from the candidates the task already has, with the
  text as their instruction; then the stage runs again. It is listed in `next_attention` and
  `decision_text` with the task's name, and the Console's answer box takes text that begins
  with `repair` the same way. It is not offered where a repair round could not succeed, such as
  a stop for a fault of the plan. One such round per task per build unless
  `-Dswarmcoder.repair.instructedRounds` says otherwise (at most 4).

- **Supervisor tools for a project's reference folders.** `list_context_folders`,
  `add_context_folder` and `remove_context_folder` let a supervising model give a project the
  source of the libraries it builds on, as the Console's project settings do. The two that change
  something need the MCP secret and are logged in the supervisor's decisions. A build already
  running keeps the folders it started with.

- **Answering a stopped build's question on screen restarts the build.** The Console's answer box
  now goes through the same path as the supervisor's, so the build is handed back to its engine
  as it is for `answer_question`. `answer_decision` therefore restarts a stopped build too.

- **A spending-limit question can be answered `extend` or `stop`.** Over the supervisor
  connection (`answer_question`, and as `next_attention` and `decision_text` list them) and in
  the Console. `extend` raises the limit that stopped the build by the same amount again and runs
  the stage it stopped in again; it is refused, with the reason and nothing recorded, when no
  limit is on record as having stopped that build. `stop` leaves the build stopped and records the
  answer. Both are in the supervisor's decision log.

- **Cloud token counts survive a restart.** The counts per run, story and project, the extensions
  granted and the limit that stopped each waiting run are saved in the store, so a project limit
  holds across restarts and `extend` works on a build stopped before one. A store written earlier
  opens as before and starts with no counts.

- **The change journal says who acted.** Agreeing a requirement, marking a story ready and
  sending a story back through the supervisor tools are written as "supervisor", not "human". A
  person's click still writes "human".


- **Cloud token limits per run, per story and per project.** `budgets` takes
  `maxCloudTokensPerStory`, `maxCloudTokensPerProject` and, for input and output separately,
  `maxCloudInputTokens` / `maxCloudOutputTokens` (each with `perRun`, `perStory`, `perProject`);
  all optional. Passing one parks the run and asks the operator, naming the project, story and
  run, how much input and output was used, and offering to extend by the same amount again or to
  stop. The run report shows the cloud tokens used and the limits in force.

- **An outside model can run a whole build: the supervisor connection.** SwarmCoder's own MCP
  server (`mcpApi.enabled`) gains the tools a supervising model needs to do what a person does
  in the Console. `wait_for_attention` blocks until something needs it and returns one short
  item (what is asked, the answers that will be acted on, the evidence, and the tool call that
  answers it, about 1,500 characters at most); `next_attention` returns the same without
  waiting. The rest wrap the Console's own services: `create_project`, `switch_project`,
  `add_document`, `view_flow`, `start_flow`, `answer_flow_question`, `submit_answers`,
  `apply_proposals`, `agree_requirements`, `list_backlog`, `promote_story`, `start_story`,
  `accept_delivery`, `send_back`, `decision_text`. By the owner's decision the supervisor may
  pass the two gates a person passes: agreeing requirements and accepting a delivery.

- **Answering a stopped build restarts it.** `answer_question` records the answer and hands the
  parked run back to its engine, which runs the stage it stopped in again. Until now only the
  journey harness could do that; a parked run in the product was taken up again only when the
  application started. The harness now uses the product's path. `answer_decision` still only
  records.

- **A decision log for the supervisor.** Every answer a supervisor gives is kept with what was
  asked, what was answered and when, in the project's existing record of decisions taken for
  the operator, marked with the actor "supervisor". `decision_log` reads it.

- **Supervised running.** `overnight.supervised: true` makes the queue start each story in
  turn, as unattended running does, while the application accepts nothing and answers nothing
  itself: every delivery and every question waits for the supervisor. Off by default; with it
  off nothing about the product changes.

- **A check on what a push publishes.** `scripts/pre-push-check.sh` reads the files, the commit
  messages and every line added by the commits about to be pushed, and refuses machine paths,
  user folders, private network addresses, keys and tokens, mail addresses, tracked files that
  `.gitignore` keeps local, added files over 1 MB and Java files without the licence header. A
  maintainer's own private names are read from `~/.swarmcoder/private-terms.txt`. With
  `git config core.hooksPath scripts/hooks` it runs on every `git push`. The routine is in
  [RELEASING.md](RELEASING.md).

- **A journey can choose from a list and read what a control holds.** Two new steps:
  `select` with `value` chooses an option of a drop-down list or combobox by the text it shows
  (or its value), and `expectValue` with `value` checks what a field holds or what a list shows
  as chosen. Before, a journey had to click an option's text, which no browser can do in a
  closed drop-down list, so a story with such a list could not pass. The author's brief, the
  review of a failed journey and both answers of `check_journey` now list every step with what
  it is for.

- **A journey says which of its steps prove which criterion.** A journey file has a third
  key, `proves`: one entry for each criterion of the story, by number, with either
  `steps: "<first>-<last>"` or `notOnScreen: "<why>"`. The test author is asked for it and
  `check_journey` does not keep a journey that leaves a criterion out. When the steps named
  for a criterion use only controls the rest of the journey also uses, the author is asked
  once whether they really exercise it. The entries are written to the run's log and the run
  report, with that note where it applies, so that whoever accepts a story sees what the
  browser actually did for each criterion. Before, a story about editing a record was
  accepted on a journey that never used the screen's edit control. Journey files without
  `proves` are read as before; nothing has to be changed in an existing project.
- **A task that spans several parts of the build is pointed out to the planner.** When the
  types one task delivers lie in more than one source root, `check_plan` and the PLAN log
  name the task and the types on each side, as a proposal to split it. It never sends a plan
  back.

### Fixed

- **The Console loads nothing from the internet.** Tailwind (3.4.17) and the daisyUI stylesheet
  (5.6.14) were loaded by the browser from two public CDNs, the first with no version pinned. Both
  are now files the application serves itself, so the Console works offline and no third-party
  script is fetched into it.

- **The documentation server was asked about the project's own name, and the answer was kept.**
  The background warm-up sent the artifact id of every entry in the root build file, the
  project's own modules included, and a worker's lookup sent the first word of its question as
  a library name. For a project named after an everyday word, the documentation of an unrelated
  library of that name came back and was indexed. A library is now looked up only when the
  build files declare it as a dependency and it is not a module of the build; the server's
  list of candidates is read entry by entry (it used to be passed on whole as if it were one
  library id) and only a candidate that can be tied to the declared name is fetched. Every
  sending is logged with the words that went.
- **Two writers on the documentation index.** The warm-up started a thread per library and each
  opened its own writer on the same index; all but one failed with
  `LockObtainFailedException` and their libraries were missing from that index. The warm-up is
  one thread and the index takes one writer at a time.

- **A repair round left half the model server empty and was stopped while its workers were
  getting somewhere.** A repair round started two workers from each of at most two candidates,
  so a round started from one candidate - after a failure at final integration, or on the
  answer `repair` - ran two workers on a server with four places and nothing else going. And
  every round was stopped after a fixed 30 minutes: on a server that takes three minutes a
  turn that is ten turns, and a round was stopped with both workers still changing files. Now
  a repair round gets as many workers as a first round of the same task, and never fewer than
  before (two for each of at most two candidates) (`swarm.nPerTask`,
  or the project's or story's number, cut to what the server is currently allowed), spread
  over its candidates, no two of one candidate alike. They take places as first-round attempts
  do: the first waits its turn, the others start on places nothing else is waiting for. A
  round is stopped when none of its workers has changed a file or been shown a new result for
  5 turns' worth of time (never under 15 minutes; `-Dswarmcoder.repair.quietMinutes` sets it),
  or at a ceiling of 30 turns' worth (never under 30 minutes;
  `-Dswarmcoder.repair.roundTurns` changes the turns, `-Dswarmcoder.repair.roundMinutes` sets
  the ceiling outright, as before). A turn's length is measured from the build's own workers.
  When the ceiling stops workers that were still making progress, the question says so first -
  "stopped by the time limit after N turns; the workers were still making progress" - and a
  round given on the answer `repair` is then not counted against
  `-Dswarmcoder.repair.instructedRounds`, so the same answer can be given again. The build log
  has one line for every repair round: places free, workers planned and started, turns,
  minutes and why it ended. Section 91 of `docs/DEVELOPER_CORRECTIONS.md`.
- **A story the existing code already met ended stopped, with no reason and nothing to
  answer.** Its one task was built on code earlier stories had delivered. Every worker changed
  nothing and reported that the acceptance test passed; none had run it, because a worker's
  checkout does not hold that test. Nothing measured the unchanged tree, the task was stopped
  with a question that gave no reason, the question was withdrawn, and the story ended with
  its check unproved. Now:
  - When no worker of a task changes anything, the product runs the task's acceptance tests on
    that unchanged tree itself. Green, and shown to measure the project's code: the task needs
    no change and nothing is chosen for it. Red: the question says so with the first failing
    test and its message, and takes `repair` and `retest`.
  - A task whose tests pass on the tree it starts from, whose contracts are in that tree and
    which claims no journey is not dispatched at all, whether that is found at the red check,
    after the test's author corrected the test, or at the start of a later wave. A corrected
    test that passes on the start tree no longer stops the build when it measures the
    project's code.
  - A build no longer goes on to final integration past a task that stopped with a question.
    It stops behind that question.
  - A story that is not delivered at final integration carries which check has no proof, why
    no result exists for it and what can be done. The supervisor's stopped-story item shows
    that instead of "Its last build ended at the ABORTED stage", and the build asks the same
    as a question.
  - A story whose every check is met with no code change is delivered for a verdict. The
    evidence says no change was needed, how many acceptance tests pass and how many journeys
    were made and failed; accepting it adds the story's tests and journeys to the delivery
    branch. Journeys are now made for such a delivery too.
  See `docs/DEVELOPER_CORRECTIONS.md` section 89.

- **Correct candidates were failed for a constructor their type cannot have.** A design wrote
  the members of a nested class, its constructors among them, in the contract of the class it
  is declared in. A constructor is named for its type, so no code can make it a member of
  another type, and every candidate that wrote the nested class was failed. When a design is
  accepted, such a contract is now split into the outer type's contract and one for the nested
  type (`Outer.Nested`), and the architect is told in a note. This is done only where the
  project's own code decides it; otherwise the contract goes back to the architect with the
  constructor named. For a task planned before this change, a contract that lists such a
  constructor is met when the nested type of that name declares the members.

- **A blocked task's question gave no reason when its candidates passed every test.** When
  the candidates compiled, passed every acceptance test and were failed by a verification
  objection, the question now quotes the first candidate's objection, and says so when every
  candidate was failed by that same one.

- **A corrected acceptance test that errors in its own set-up was taken as a failing one.** A
  test sent back to its author and corrected is now held to the standard of a first-written
  test: a correction that errors before any code under test runs is a broken test, not a red
  one. It goes back to its author once more with the error and the names of the tests that
  pass, and if it is still broken the build stops with that reason. The same is asked when the
  candidates are verified again against a correction, and when a task is built: where every
  candidate compiled, none of their code ran and the same test errored for each, no repair
  round is started, because no worker may change an acceptance test.

- **A blocked task's question said "0p/0f" and gave no reason when its tests errored.**
  Errored tests are counted wherever a candidate's results are shown (`0p/0f/4e`), and the
  question names the first errored test with its exception and message. When every candidate
  errored on the same test with the same exception and passed nothing, it says that the test,
  not the candidates, is the likely cause.

- **The documentation search tells sibling modules apart.** A question that writes a module's
  name with hyphens or underscores, such as `inventory-crud-server`, now also matches the folder
  of that name as one word. Before, only its parts matched, so the example's client files
  outranked its server files for a question about the server. The search index is rebuilt once
  after the upgrade.
- **The code part of a documentation answer shows the files that match the question.** A source
  file is now ranked by how many of the question's words it matches as well as by how strongly:
  a file that matches one word in its own short name no longer comes before a file that matches
  two or three. A word found in more than half of all source files, such as `main` in every
  path under `src/main/java`, does not count. A file the ranking chose is no longer left out
  "for room" because space was kept for a line listing other files with its name; that line is
  kept for a file the question named and otherwise added only when room is left. When many
  files share one name, the answer now looks further down the ranking for a third file.
- **The application's own settings file no longer blocks accepting a delivery.** Saving a
  project's settings or reference folders writes `.swarmcoder/project.yaml` into the project's
  repository, and accepting was then refused for "changes that are not committed". That file
  and `.swarmcoder/verify.yaml` are the application's own and are no longer counted. A person's
  uncommitted work still blocks, the refusal now names up to five of the files, and it comes
  before the merge and the build instead of after them.

- **A finished delivery is no longer described as unfinished.** A story came back for a verdict
  a moment before its build was written as ended, and for minutes when the learning step that
  followed was slow; in that time the supervisor was told "its build has not finished" and
  unattended running left the story for a person. The build's end is now written first, a story
  back for a verdict counts as finished while its build is at that last stage, and the delivery
  is described by what was counted: the commit, the acceptance tests that passed on the merged
  tree, the journeys made in a browser and how many failed, and the build's cloud tokens.

- **A test owned by a task that could never compile it.** A plan gave an acceptance check to a
  task whose test imported types a later task writes. Every candidate compiled, the task's
  protected test did not, and four candidates and four repair workers failed before the build
  stopped with a question that gave no reason. Now the red check asks, for each task, whether
  that task or the tasks it waits for write every type its test needs. If another task does,
  the test and its checks are given to the task that has all of them when it starts, and the
  move is logged. If no task does, the test goes back to its author once with the fact, and
  the run then stops before any worker starts. As a second line, a candidate whose only
  compile failure is in its task's own protected test, on a type a task not yet run writes,
  blocks the task at once as a fault of the plan: other candidates are stopped
  (`KillReason.PLAN_FAULT`, appended) and no repair round starts. The verifier no longer says
  a file "compiled on the tree this candidate started from" when the file was not on that
  tree. A blocked task's question now says, before its list of workers, the first file and
  line that did not compile with the compiler's message, or the first failing acceptance test.
  See section 83 of `docs/DEVELOPER_CORRECTIONS.md`.

- **A story has one build at a time.** Sending a story back, or starting it again, now ends
  its earlier builds: an unfinished one is marked aborted, and every question it still had open
  is closed with the reason written on it. Before, the earlier build kept its stage, was picked
  up again at the next start beside the new build, and wrote its own failure onto the story.
  At start only a story's current build is resumed, and a store from before is put right then.
  A question is not raised a second time when a build is picked up again. The pending-question
  counts, `next_attention` and `list_backlog`'s `questionsFromBuilds` show only open questions of
  current builds (`questionsFromBuilds` used to count every question ever recorded). Answering
  a question of an earlier build is refused with a sentence saying the story has a newer
  build. Changed on purpose: a story's open questions no longer stay open after it is sent
  back.

- **A contract can no longer demand an annotation where it cannot be written.** A design wrote
  an annotation that Java only allows on a type in front of a constructor
  (`@DataModel Book()`). Four candidates put it on the class, which is correct, and all four
  were failed because the constructor "does not carry the annotation"; the task was blocked.
  Where an annotation may be written is now read from the annotation type's own `@Target` (in
  the project's source, a reference checkout or the JDK). When a design is accepted, an
  annotation that cannot be on the member it was written in front of and can be on a type is
  moved to the type and the architect is told in a note; one that can be on neither is sent
  back to the architect. Verification counts such an annotation as delivered when the type
  carries it, and still fails a member that lacks an annotation it can carry. An annotation
  whose declaration cannot be read is treated as before. A contract member written with its
  own semicolon is no longer printed with two.

- **A story that shows things nobody can yet create no longer ends in a contradiction.** A story
  is proven in a browser from the entry page of an empty application, so a story that only
  displays a kind of thing, planned before the story that lets a person add one, could not be
  proven; the build stopped at the end with the journey and the screen each called right. The
  story planner is now told that every story must be provable from an empty application. A
  test author who finds no honest journey says so in a field of its own
  (`nothingCreates: <what>`), and the build stops when the tests are written, before anything
  is built, with a question that names what the story shows, the stories not delivered yet,
  and what to do: merge, reorder, or retry. The finding is kept on the story and shown to the
  planner when its checks are planned again.
- **`maxCloudTokensPerRun` counted the whole process, not a run.** The counter was never reset,
  and the question it raised named no run. It is now kept per run, and the question carries the run.
- **Every journey starts on the application as the tree gives it.** A journey that failed and
  was corrected by its author was made again in the same container and the same tree, so an
  application that keeps its data on disk still showed what the first attempt had saved, and
  a correct application failed the corrected journey (live run 104). Each journey is now made
  on its own copy of the built tree, in a container of its own, and both are thrown away
  afterwards: at final integration, after a review, in `check_journey` and on the tree the
  run started from. One journey no longer sees another's data either. The application is
  started once per journey instead of once per batch.

- **The task planner is no longer sent back for things the plan's own facts decide.** A
  planner on a small model wrote thirteen drafts of one plan and ended by putting six tasks'
  work into one. Four things are now put right by the checks and told to the planner as
  notes it need not answer: a dependency written the wrong way round between a task that
  uses a type and the task that writes it (it is turned round when nothing supports the
  planner's direction); a class declared inside another that no task was said to deliver (it
  goes to the task of the outer class, and its file is the outer class's file, not a folder
  named after it); and a new file the plan's other new files use, which used to be reported
  as unreachable when no existing code used its module yet. A plan is still sent back when
  two tasks' types name each other, when an order contradicts a type through a third task,
  and when a new screen has no existing file to be opened from.
- **A tool argument described as optional may be left out.** A model that omitted the `lines`
  argument of `keep_for_workers`, or the argument of a worker's `acceptance_test`, got an
  internal error in place of the tool's answer, and the architect's findings from that turn
  were lost. The call now runs with the argument empty.
- **A finding kept for the workers carries the imports its lines use.** A worker given a few
  lines from the middle of an example class had to guess where their types come from.

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

- **`accept_delivery` says what it did and how long it took.** Accepting merges the story with
  the delivery branch on a copy and builds and tests the whole project before it answers, which
  takes minutes. The tool's description says so, and its answer names the steps and the seconds.

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
