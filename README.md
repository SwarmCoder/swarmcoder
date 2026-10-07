<div align="center">

# SwarmCoder

### Agentic software development that your organization can actually account for

[![swarmcoder.dev](https://img.shields.io/badge/swarmcoder.dev-1f6feb?style=flat-square&logo=firefoxbrowser&logoColor=white)](https://www.swarmcoder.dev)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-e76f00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square)](LICENSE)
[![Status](https://img.shields.io/badge/status-early%20release-orange?style=flat-square)](CHANGELOG.md)

</div>

---

SwarmCoder is a multi-agent coding system that follows one fixed software development lifecycle
every time it runs. It uses expensive frontier models only where a task genuinely needs that level
of judgment, does the bulk of the work with swarms of cheap local models on your own hardware, and
writes down every decision it makes along the way so that you can go back and check it later.

You make two decisions per story and the system does the rest.

> **Where this is today.** This is version 0.1.0, the first public release, and it is an early one.
> The system is substantially built, but it is not feature complete, there are known issues that
> are still open, and neither its settings file nor its stored data is stable yet. The claim that
> a swarm of small local models beats a single frontier pass on real work in a real repository is
> the thesis this project exists to test. It is well supported by the published research but not
> yet by our own numbers, and when we have those numbers they will be published, including the
> ones that disappoint. The [changelog](CHANGELOG.md) lists what is in this release and what its
> known limits are.

## How a story gets built

Everything happens on one board. A story appears when the planner suggests it, you decide whether
it is real work, the swarm builds it, and then you judge whether the delivery is what you asked
for. Nothing disappears off the board and nothing gets parked somewhere you have to remember to go
and look.

| | step | who |
|---|---|---|
| 1 | **Accept the work.** Is this suggestion real work that you want done, or is it not needed? | you |
| 2 | An architect writes the design and a reviewer critiques it | the machine |
| 3 | The design is planned into slices, each with the files it owns and the checks it claims | the machine |
| 4 | The acceptance tests are written first, in a place the workers cannot touch, and they have to fail against the code as it stands | the machine |
| 5 | Eight or more workers attempt each task in parallel, each in its own git worktree inside a container | the machine |
| 6 | Every attempt is verified by a real build, and the ones that fail are thrown away | the machine |
| 7 | The survivors are grouped by how they actually behave, one winner is selected on the evidence, merged, and verified again | the machine |
| 8 | **Judge the delivery.** Accept the commit, or send it back with a note explaining what is wrong | you |

Features, bug fixes and refactors all go through the same steps, and there is deliberately no way
to configure this into a different process. If the system has a genuine question along the way it
asks you on the story card itself, with the buttons to answer it right there.

## Many cheap attempts generally beat one expensive attempt

A non-frontier model that is sampled once is mediocre. But if you sample it many times in parallel,
with some engineered diversity between the attempts, its chance of producing at least one correct
answer climbs steeply, and this is one of the better established results in the recent research on
coding agents.

On hardware you own, running eight workers on the same task costs roughly the same as running one,
because the model weights are read out of memory once per step regardless of how many sequences
are in flight. So if seven of the eight attempts get thrown away, that is fine, that is basically
how the method is supposed to work. You can only afford to think this way when the marginal cost
of a token is your electricity bill.

## Each agent runs on the model its task actually needs

Which model an agent runs on is essentially a cost decision made per task, so every agent runs on
the cheapest model that can do its particular job well. The rule of thumb is simple enough: you
need a frontier model where there is no test sitting in front of the output to catch a mistake.

| | which agents | what it costs you |
|---|---|---|
| **Frontier, for judgment only** | The requirements analyst, architect, design reviewer and test author. Each of these gets one attempt at an artifact that nothing downstream can check mechanically. | Scales with the number of tasks. The work is kept small and structured, and the spend is capped per run. |
| **Cheap frontier, for constrained judgment** | The story planner, the judge and the reading of uploaded images. | Small, and the judge is the obvious next role to move onto a local model. |
| **Local, for everything else** | The worker swarms, retrieval, chat and the learning that happens after a run. | Scales with tokens. This is over 95% of the token volume and it is priced in electricity. |

The workers get eight attempts and a verification gate, which is exactly why they can run as swarms
of local models. Any OpenAI compatible endpoint works for the local roles, and the worker model is
a configuration choice that is deliberately interchangeable. The website has
[the full list of agents](https://www.swarmcoder.dev/agents.html).

## Generating code is cheap, and choosing the right candidate is the hard part

Most tools that run agents in parallel simply hand you a row of tabs and twenty diffs to read
through yourself. SwarmCoder picks the winner mechanically. The tests are written first in a place
the workers cannot touch, the cheap filters (does it parse, does it compile) run before the
expensive ones, the survivors are grouped by how they actually behave, and only then does a judge
rank a handful of genuinely different diffs against the real test results.

Exactly one candidate wins, and alternatives are never merged together. The losing candidates are
archived along with their evidence rather than deleted, so you can always go back and see what
else was tried.

## Every line of code can be traced back

For any line of code you can see which worker wrote it, which candidate it was part of, which test
proved it and which requirement asked for it, right back to the sentence in the document it came
from. Every step of every agent session is recorded as data rather than as log lines, i.e. the
prompts, the responses, the tool calls and the token usage, and you can still query all of this
months later, in either direction.

The requirements are kept honest in the same way. Every requirement is bound to an executable
check, so it is only marked as implemented when there is evidence (a commit and a passing test)
and not because somebody said so. If you edit the wording of a requirement, the evidence is marked
as stale and you can see that on the board.

## Agents learn your project from its structure and not from raw files

SwarmCoder parses the target project into a lossless syntax tree and a Java object graph (types,
members, relations and usages) and keeps a search index next to it. Every agent gets its knowledge
of the project from those, through ordinary lookup tools that involve no AI at all, e.g. "what does
this type look like", "who calls this method" or "give me the body of this one method". Reading
whole source files into a model's conversation is the last resort and not the default, because
that is usually where a large share of the tokens in an agentic run goes.

## Frontier tokens are currently sold below cost, so plan for the day that stops

The price you pay for frontier tokens today is a market share price and not a cost price, and most
agentic coding platforms are built to consume more and more of them with every release. Prices
that sit below cost eventually have to correct, and at that point the cost of your development
capability is a number that somebody else sets.

SwarmCoder treats token cost as an architectural constraint from the start. Judgment is a few
capped frontier calls per story, and over 95% of the token volume runs locally at the cost of
electricity, so even a 10× repricing of frontier tokens would not change your bill very much. If
frontier pricing stays cheap you have given up very little, and I would suggest you at least keep
this in mind when choosing your tooling. The
[principles page](https://www.swarmcoder.dev/principles.html) has the full argument.

## Who this is for (and who it is not for)

SwarmCoder is for organizations that need proper traceability on their software development, i.e.
one process that is followed every time and leaves a record behind, rather than an agent that
reinvents its way of working on every run. It runs on your own hardware, on your own network and
against your own repository.

If you are a solo developer who is happy with a chat based coding assistant, this is probably not
for you, and you should not change what already works. It is also worth knowing what SwarmCoder is
not before you spend an evening on it:

- **It is not an IDE plugin.** It is a standalone system with its own console in the browser.
- **It is not configurable into a different process.** There are no plugins, no agent graphs and
  no workflow language, and this is deliberate.
- **It is not fast.** It is built for long unsupervised runs, so you start it in the evening and
  judge the deliveries in the morning.
- **It is not a hosted service.** It runs on your hardware and nowhere else.
- **It is not a way to avoid deciding.** It asks you exactly twice per story and it expects real
  answers from you.

Today the tested path is a Java project that is built with Maven.

## What you need

To start SwarmCoder and look around you only need two things:

- Java 21 or newer
- Maven

To build software with it you need two more, and SwarmCoder tells you about both when it starts:

- Docker, installed and running. All code that a model writes runs inside a container, so that it
  cannot touch your files, your saved credentials or your network, and without Docker a build
  stops rather than running unprotected.
- A model server address, which is any OpenAI compatible endpoint. This can be a model on your own
  machine or network, or a hosted service.

The setup it is designed for is two machines, i.e. a DGX Spark class box that serves tokens and
nothing else, and an ordinary workstation that runs the orchestrator, the sandboxes and the builds.

## Build and start

```bash
mvn clean package -DskipTests
```

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.time=ALL-UNNAMED \
     -jar sc-app/target/sc-app-0.2.0-SNAPSHOT.jar
```

On Windows, `build.bat` and `run.bat` do the same thing. The four `--add` options are required by
the storage engine and cannot be left out.

SwarmCoder then prints the address of its console, which is `http://localhost:9090/`, and lists
what is still needed before it can build anything. The [user manual](docs/USER_MANUAL.md) takes it
from there.

To run builds in containers, build the worker image once:

```bash
mvn -q -pl sc-sandbox-action-server -am package -DskipTests
docker build -t swarmcoder-worker:latest sc-sandbox-action-server
```

## Documentation

| document | what it is |
|---|---|
| [swarmcoder.dev](https://www.swarmcoder.dev) | The overview, the agents, the principles and the project status |
| [User manual](docs/USER_MANUAL.md) | Configuring, running and using the application |
| [Agent process flow](docs/Agent-Process-Flow.md) | How the system works: every pipeline stage, its gates, and the worker |
| [Agent knowledge and operations](docs/Agent-Knowledge-And-Operations.md) | The knowledge indexes, the model runtime, the harnesses, and a problem-handling playbook |
| [Testing](docs/TESTING.md) | Which tests run, when, and how you know what did not |
| [Documentation index](docs/README.md) | Every document, and which ones are historical |

## Modules

| module | what it holds |
|---|---|
| `sc-domain` | The domain model |
| `sc-store` | Persistence on EclipseStore |
| `sc-runtime`, `sc-inference` | The model runtime and the model server clients |
| `sc-syntax`, `sc-knowledge`, `sc-lsp` | The syntax tree, the object graph and search index, and the Java language server client |
| `sc-swarm`, `sc-workflow` | The parallel workers and the lifecycle that drives them |
| `sc-git`, `sc-sandbox`, `sc-sandbox-action-server`, `sc-verify` | Worktrees, containers, the server that runs inside them, and verification |
| `sc-server`, `sc-console`, `sc-console-api`, `sc-console-ui` | The server and the browser console, built on [ZeroZ Stack](https://github.com/ZeroZ4j/zerozstack) |
| `sc-app` | The application that ties it together |
| `sc-evals`, `sc-testsupport` | Evaluations and the test harness |

## Contributing and releasing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CHANGELOG.md](CHANGELOG.md) and
[RELEASING.md](RELEASING.md).

## About the author

SwarmCoder is built on [ZeroZ4j](https://www.zeroz4j.com) by **Franz Schöning**, Principal
Enterprise Architect.

In my consulting practice I audit IT landscapes and rationalize technology portfolios for large
organizations. SwarmCoder is a working demonstration of how AI can be brought into the development
lifecycle in a safe and controlled manner, i.e. with one process that is followed every time, the
expensive models used only for judgment, and a full record of what was done and why.

If you are struggling with a complex IT portfolio, a legacy modernization, or the need to bring AI
into your development lifecycle safely, I can help you chart a pragmatic way forward.

🔗 **Let's talk about your architecture:** [www.franzschoning.com](https://www.franzschoning.com)

## License

This project is open source under the [Apache 2.0 License](LICENSE), which is the same license as
ZeroZ4j, the framework it is built on. Anyone is welcome to fork it, adapt it and build upon it.
See the [NOTICE](NOTICE) file for attribution details.
