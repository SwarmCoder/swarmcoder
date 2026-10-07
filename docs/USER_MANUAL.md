# SwarmCoder User Manual

Welcome to **SwarmCoder**, a highly advanced, multi-agent framework designed to autonomously design, implement, and verify software engineering tasks using Swarm Intelligence. This manual provides a comprehensive guide on how to configure, run, and interact with the application.

## Table of Contents
1. [What you need first](#1-what-you-need-first)
2. [Settings](#2-settings)
3. [Building and starting it](#3-building-and-starting-it)
4. [Your first run](#4-your-first-run)
5. [Using the Console (Web UI)](#5-using-the-console-web-ui)
6. [Understanding Workflows](#6-understanding-workflows)
7. [Testing Without a Live Endpoint](#7-testing-without-a-live-endpoint)

---

## 1. What you need first

To **start** SwarmCoder:

- **Java 21 or newer.** Type `java -version` to check.
- **Maven**, to build it. Type `mvn -version` to check.

To **build software with it**, two more, and SwarmCoder tells you about both when it starts, so
you can leave them until then:

- **Docker Desktop**, installed and running. SwarmCoder runs the commands the models write inside a
  container, so that they cannot touch your files, your saved passwords or your network. Without it
  a build stops rather than running unprotected.
- **A model server address.** Either a service on the internet (an address and a key) or a model
  running on your own machine or network. You set these in the window, and you can change them at
  any time.

You do **not** need any of that to start SwarmCoder for the first time and look around.

---

## 2. Settings

Everything lives in one file: `~/.swarmcoder/config.yaml` — that is `C:\Users\<you>\.swarmcoder\config.yaml`
on Windows.

**You do not have to create it.** The first time SwarmCoder starts it makes the file for you, with
comments explaining each part, and prints where it put it. Everything in it can also be changed
from the window, which is easier. SwarmCoder reads the file once, when it starts, so restart it
after you change anything.

The two settings that matter on day one:

- **`consolePort`** — the number in the address you open in your browser. The file starts with
  `consolePort: 9090`, so the window is at **http://localhost:9090/**. Change the number only if
  something else on your machine already uses it.
- **`repoPath`** — the folder holding the code you want SwarmCoder to work on. The starter file
  leaves this **empty on purpose**: SwarmCoder makes branches and writes files in that folder, so
  it has to be a folder you chose. Set it in the window (Setup, then "Project settings"), or write
  the full path in the file.

A fuller example, once you know what you want:

```yaml
consolePort: 9090
repoPath: /home/you/repos/target-project
roles:
  architect:
    baseUrl: "https://api.example.com"
    apiKey: "your-key-here"
    modelName: "the-model-name"
  workerFamilies:
    - baseUrl: "http://localhost:8000/v1"
      apiKey: ""
      modelName: "the-model-name"
swarm:
  nPerTask: 4                 # attempts each piece of work gets
  maxConcurrentTaskGroups: 2  # pieces of work attempted side by side
  maxConcurrentWorkers: 8     # the most attempts running at once on this computer
  splitAcrossFamilies: true
  tempMin: 0.1
  tempMax: 0.8
```

- **`roles`** — which model does which job. `architect` plans, `workerFamilies` write the code.
  Anything you leave out falls back to one that is set.
- **`nPerTask`** — how many attempts each piece of work gets. Four. More attempts find better
  answers up to a point: with eight, six or seven of them were passing, so the last few were
  proving what the first few had already proved. Leave the whole `swarm:` block out and each piece
  of work gets a single attempt.
- **`maxConcurrentTaskGroups`** — how many pieces of work are attempted side by side inside one
  build. Two. Four attempts on two pieces of work is eight things running, which is exactly what
  eight attempts on one piece of work was: the same load on your computer, twice as much of the
  list getting done.
- **`maxConcurrentWorkers`** — the most attempts running at the same moment on this computer, no
  matter what the two numbers above add up to. Eight. Anything over it waits for a free place
  rather than being skipped, so nothing is lost — it just takes its turn.
- **`splitAcrossFamilies`** — with two or more worker entries, spread the attempts across them, so
  the answers differ more. It does nothing with only one.

### When attempts keep running out of room

Each attempt gets a share of the model server's memory. The share is the server's memory divided
by how many attempts run on it at once: on a server that reports room for 462,103 tokens and runs
8 at a time, each attempt gets about 51,200. Fewer attempts at once means more room each — the two
numbers are one dial.

SwarmCoder turns that dial by itself. If at least half of the attempts a server ran at once died
because their conversation outgrew their room (the build log says "ran out of room"), the next
wave on that server runs half as many attempts, and each gets twice the room. It keeps halving
while that keeps happening, down to one attempt with the whole server to itself. After two clean
waves at the smaller size it steps back up, one step at a time; if stepping up brings the deaths
back, it waits twice as long before trying again.

What it will not do: go above `maxConcurrentWorkers` if you set one, raise a `workingContextTokens`
you wrote into the file yourself, change a wave that is already running, or let one server's
trouble slow another. Every change is one line in the build log with the numbers before and after
and which attempts caused it, and while a server is running smaller than it started, the health
strip and the autonomous-mode status line say so. One attempt that still runs out of room with the
whole server to itself means the piece of work does not fit this server: split it into smaller
pieces, or start the server with more memory.

### Asking one project, or one story, for a different number

The number of attempts can be set in three places, and the most specific one wins:

1. **One story.** Open the story on the Pipeline board and type a number in the **Tries** box. Empty
   means "use whatever the project asks for". This is where a hard story gets eight and an obvious
   one gets two.
2. **One project.** Open the project's settings from the project menu and fill in **Attempts per
   piece of work**. It is written into that repository's own `.swarmcoder/project.yaml`, so it
   travels with the code. Empty means "use the settings file".
3. **Everything.** `swarm.nPerTask` in the settings file above.

Every layer is optional and empty always means inherit. When a build starts, the build log says
which of the three decided the number and what it was — you never have to work out where a number
came from.

---

## 3. Building and starting it

Build it once:

```bash
mvn clean install -DskipTests
```

On Windows, `build.bat` does the same thing.

Then start it:

```bash
# Windows
run.bat

# Mac / Linux
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.time=ALL-UNNAMED \
     -jar sc-app/target/sc-app-0.1.0.jar
```

Those four `--add` options are not optional: the storage engine needs them.

If you want builds to run in the safety box straight away, build the container image once as well.
This needs Docker running:

```bash
mvn -q -pl sc-sandbox-action-server -am package -DskipTests
docker build -t swarmcoder-worker:latest sc-sandbox-action-server
```

SwarmCoder prints those same two commands if it starts and finds Docker missing, so you can leave
this until it asks.

---

## 4. Your first run

The first time you start SwarmCoder on a machine, it has nothing: no settings, no project, no code
to work on. That is the expected state, and it starts anyway.

What happens, in order:

1. It makes the settings file and tells you where:
   `Welcome to SwarmCoder. … a settings file has been made for you`
2. It opens the window and prints the address:
   `SwarmCoder is at http://localhost:9090/ — open that in your browser.`
3. It lists what is still needed before it can build anything — usually two things: point it at
   your code, and tell it which model to think with. Each line says where in the window to do it.

Open the address in your browser. Next to the counts at the top there is a dot: **red and the word
"setup"** until everything is in place, green and the word "ready" afterwards. Hover it for the
reason, or click it. The **Setup** stage lists the three things SwarmCoder needs — a project, a
folder of code, and somewhere to run — ticks the ones you have, explains the ones you do not, and
ends with a **Next:** line naming the single next step.

Do those two steps and the dot turns green. Nothing else in the window changes what it can do, so
there is nothing else to configure first.

Two things you can already do without either of them: write down what you want built (the
Requirements stage) and plan the work (the Pipeline). Both are stored inside SwarmCoder and need no
code folder at all. Only building needs one.

---

## 5. Using the Console (Web UI)
The window in your browser is the only interface; it runs inside the same process as the swarm.
Start SwarmCoder and open **http://localhost:9090/** — or whatever port your settings say, which
SwarmCoder prints when it starts. There is no login and no refresh button; everything updates
itself.

> **Note.** The rest of this section, and sections 6 and 7, describe an older arrangement of the
> screens and several run kinds that no longer exist. Sections 1 to 4 above and the window's own
> Setup stage are current; treat what follows as out of date until it is rewritten.

### The Layout
Left to right: a **project rail** (one icon per project, click to switch), a **sidebar** listing your Chats and Runs plus the views — Requirements, Swarm Board, Approvals, Insights, Prompt Lab, Guidelines, Knowledge — a **Backlog panel** showing the plan you are working through, a **tabbed workspace** in the middle where everything you open lives, and an **Inspector** on the right that fills in when you click something worth drilling into (use the magnifier in the top bar to hide or show it). The dividers are draggable. `Ctrl/⌘+K` opens a command palette that reaches every chat, run, and view.

### Chat: starting work
Create a chat with the **＋** next to "Chats" and describe what you want. The assistant interviews you, researches the repository itself, and then proposes a run goal. Start the run with a slash command — `/run` (or `/greenfield`), `/bugfix`, `/refactor` — followed by the goal, or bare `/run` to accept the goal it just proposed. The run is then narrated back into the same chat: state changes, decisions that need you, and the final approval gate. You can paste images as context and reference files with `@path/to/File.java`.

### The Requirements tab (BRD)
Each project has one living **Business Requirements Document**: a graph of requirement nodes and typed edges (depends-on, refines, conflicts-with, derived-from) rather than a text file. Click a node to edit its title, statement, priority, status and category; add one by hand with **＋**; connect nodes with the edge editor. The clock icon opens the **History** panel, which browses every past revision and restores any of them. The graph is live — it redraws by itself when anything changes it, including the agent below.

### "Analyse documents" — letting an agent write the BRD for you
Upload your requirements documents (below), then press **Analyse documents** in the Requirements toolbar. A wizard walks the whole process: it extracts the distinct requirements, asks you the questions it cannot answer from the documents alone (gaps, conflicts, missing acceptance criteria, priorities), and shows you every proposed requirement for review before anything is written.

The analysis runs on the server, so you can close the wizard, keep working, and re-open it — it lands you exactly where the work has got to. Nothing it writes goes live on its own: requirements land as DRAFT and you promote them to ACTIVE yourself in the Requirements tab.

**Two things stop a vague requirement becoming work.** If the wizard asks you a question and you press Continue without answering it, anything it drafted out of that same sentence comes back **unticked**, with the question quoted, so you have to look at it before it becomes anything. And you cannot agree a requirement at all until at least one of its checks names the test that will prove it — the Agree button stays greyed out and says what is missing. A requirement nothing can ever test cannot be planned, cannot be built and can never be shown to be delivered, so agreeing one only stores up a problem for later.

**On a large project the analyst is shown part of the document, not all of it.** It is told so plainly, and it can look up anything it cannot see. That is what keeps the analysis working at a thousand requirements instead of growing until the model refuses the request.

### Uploading a document instead of pasting it
Drop a file onto the Requirements tab — or onto the chat composer — and SwarmCoder reads it: Markdown, plain text, AsciiDoc, reStructuredText, CSV, PDF, Word `.docx`, and images (PNG, JPG, WebP, GIF). The inbox button in the Requirements toolbar opens a file picker if you would rather browse. A status line tells you what was read and how; then press **Analyse documents** in the Requirements toolbar to turn it into requirements.

Dropping a file on the chat composer only ingests it — it does not send a message.

Images are read by a **vision model**, which you must configure yourself: set `roles.vision` (a base URL and a model name) in Settings. Without it, images are refused with a message telling you exactly that, and you will need to supply the document as text, Markdown, PDF or Word instead. Scanned PDFs with no text layer are refused the same way — re-upload those as images. The limit is 25 MB per file, and uploading the same file twice reuses the first extraction rather than re-reading it.

Whatever the vision model produces is a model's *reading* of the picture, not the picture itself, and SwarmCoder labels it as such wherever it is shown. Check it before you promote requirements out of it.

### "Plan stories" — turning requirements into work
Press **Plan stories** on the Backlog panel or the Backlog board. A wizard walks the whole process, exactly like **Analyse documents** does for requirements: it shows you what it is about to read (how many requirements you have agreed, and which of their acceptance criteria no story yet delivers), asks the questions it cannot decide for you — what comes first, what is out of scope for now, what the first slice must prove — and shows you every proposed story for review before anything reaches the backlog.

A story is a **slice of work**, identified by the acceptance criteria it makes true. Each proposal shows the criteria it claims, the criteria's own wording, and why the planner thinks it is a story of its own. Technical work that satisfies no criterion — a migration, a spike, infrastructure — is proposed as an **enabler** against the requirements it unblocks.

It cannot write requirement content. If something you need is not in the BRD, the planner says so in the rationale and proposes nothing for it; you add the requirement in the Requirements tab — by hand, or with **Analyse documents** — and it becomes plannable on the next run.

The planning runs on the server, so you can close the wizard, keep working, and re-open it — it lands you exactly where the work has got to. Nothing it writes goes live on its own: stories land in **Triage** as DRAFT and you promote them to READY yourself.

### The Backlog panel
The panel between the sidebar and the workspace shows your plan while you work: **Triage** at the top (proposed stories nobody has decided about yet, with a count), then each iteration and the stories in it, then unscheduled stories, then Done. Under each story are its tasks with live state. Drag the divider to resize it; the list icon in the top bar hides and shows it.

Click any story to open it in the Inspector, where the actions are:
- **Promote** — accepts a DRAFT story into the plan. Until you do, it is only a proposal.
- **Start session** — runs a READY story. The run is bound to it, and the Architect designs against exactly that story's requirements and criteria; it cannot invent new ones.
- **Accept** — the definition-of-done gate on a story that reached REVIEW. This is deliberately yours to give: passing tests and "this is what I asked for" are not the same claim. Accepting marks the story done, stamps its criteria with the commit that delivered them, and flips a requirement to IMPLEMENTED once all of its accepted criteria pass.
- **History** — every change to that story, who made it, and when.

A run never marks a story done by itself. When the work verifies it moves to REVIEW and waits for you; when it does not, it moves to BLOCKED.

For a full-width planning view, click the icon in the panel's own header to open the **Backlog board** as a tab: the same stories laid out in columns by state, with the same actions plus creating iterations and moving stories between them.

---

## 6. Understanding Workflows

There is **one** way a run is built, and every kind of run goes through all of it:

`INTAKE` -> `DESIGN` -> `DESIGN_REVIEW` -> `PLAN` -> `TEST_AUTHORING` -> `EXECUTING` ->
`FINAL_INTEGRATION` -> `DELIVERED`

An architect designs and is reviewed; the design is sliced into tasks; the acceptance tests are
written **before** any code and must fail against the code as it stands; then parallel workers each
attempt a task in their own git worktree, every attempt is verified by a real build, the survivors
are judged, and the winner is merged and verified again. Nothing reaches `DELIVERED` without a plan
behind it and a build that passed.

What you choose when you start a run is not a different pipeline — it is what the architect and the
test author are told:

- **`/run`** — a feature. Your goal reaches the architect exactly as you wrote it.
- **`/bugfix`** — a fault. The acceptance test must *reproduce* it: fail against the code as it
  stands today, pass only once the fault is gone. The run changes what the fix needs and no more,
  and every existing test must still pass.
- **`/refactor`** — the same behaviour in a different shape. Nothing about behaviour may change and
  every existing test must still pass unaltered; the acceptance tests assert the new structure, so
  they are false today and true afterwards.

There used to be `/docs` and `/analyze` as well. They have been removed. They started a "run" that
walked through a few state names, designed nothing, planned nothing, wrote no test, started no
worker — and then reported that it had delivered. Neither is a build this system can stand behind:
everything here rests on a check that a test proves, and documentation and analysis produce nothing
of that kind.

---

## 7. Testing Without a Live Endpoint
There is no mock mode in the product. Developers test the full engine — the real agent
runtime, worker loop, verification, clustering, judging, and selection — against **FakeVllm**,
an in-process OpenAI-compatible server with scripted responses used by the test suite
(`SwarmEngineFakeVllmTest` in `sc-swarm`). This exercises the actual code paths
deterministically, offline, including kill paths, instead of simulating them.

Enjoy using SwarmCoder to automate and scale your software engineering capabilities!
