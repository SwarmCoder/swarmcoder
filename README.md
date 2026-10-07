# SwarmCoder

**An opinionated coding agent that runs a swarm of local models through one fixed software
development lifecycle.**

Most coding agents give you one expensive model and an empty prompt. SwarmCoder gives you a fixed
development process and a swarm of cheap local ones.

It takes your requirements, plans them into slices, writes the tests before the code, dispatches
eight or more local workers at the same task, throws away the ones that fail verification, picks a
winner on evidence, and hands you a commit.

> **Status: 0.1.0, an early first release.** SwarmCoder is experimental. It is not feature
> complete, known issues are open, and nothing about its interfaces or stored data is stable yet.
> It is published so the work can be read, run and discussed, not as a finished product.

---

## How a run works

There is one way a run is built, and every kind of run goes through all of it:

`INTAKE` → `DESIGN` → `DESIGN_REVIEW` → `PLAN` → `TEST_AUTHORING` → `EXECUTING` →
`FINAL_INTEGRATION` → `DELIVERED`

- **An architect designs, and the design is reviewed.**
- **The design is sliced into tasks.**
- **Acceptance tests are written before any code**, and must fail against the code as it stands.
  Workers cannot change them.
- **Parallel workers each attempt a task** in their own git worktree, inside a container.
- **Every attempt is verified by a real build.** Attempts that fail are thrown away.
- **The survivors are judged, the winner is merged, and the result is verified again.**

Nothing reaches `DELIVERED` without a plan behind it and a build that passed.

## The ideas behind it

- **Many cheap attempts beat one expensive one.** A non-frontier model sampled once is mediocre.
  Sampled many times in parallel, its chance of producing at least one correct answer climbs
  steeply. On hardware you own, the extra attempts cost electricity.
- **Generation is cheap. Selection is the product.** Which attempt wins is decided mechanically,
  by tests the workers cannot touch and a build that either passes or does not.
- **Agents learn a project from its syntax tree and object graph, not from raw files.** The target
  project is parsed into a lossless syntax tree and a Java object graph, and every agent role looks
  facts up through deterministic tools. Reading whole files into a model is the last resort.
- **Model-written code runs in containers.** It has no access to your drives, your saved
  credentials or your network.
- **Planning roles may use a paid cloud model; the token-heavy implementation runs locally.** Run
  reports are in tokens per role.

## What you need

To start SwarmCoder:

- Java 21 or newer
- Maven

To build software with it:

- Docker, installed and running
- A model server address: an OpenAI-compatible endpoint, either a model on your own machine or
  network, or a hosted service

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

On Windows, `build.bat` and `run.bat` do the same. The four `--add` options are required by the
storage engine.

SwarmCoder then prints the address of its console, `http://localhost:9090/`, and lists what is
still needed before it can build anything. The [user manual](docs/USER_MANUAL.md) takes it from
there.

To run builds in containers, build the worker image once:

```bash
mvn -q -pl sc-sandbox-action-server -am package -DskipTests
docker build -t swarmcoder-worker:latest sc-sandbox-action-server
```

## Documentation

| document | what it is |
|---|---|
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

## About the Author

SwarmCoder is created by **Franz Schöning**, a Principal Enterprise Architect.

**Enterprise Architecture Consulting**
Are you struggling with complex IT portfolios, legacy modernization, or the need to safely
integrate AI into your enterprise development lifecycle? I help organizations untangle
architectural gridlock and chart a pragmatic, high-ROI path forward.

🔗 **Let's talk about your architecture:** [www.franzschoning.com](https://www.franzschoning.com)

## License

This project is open-source under the [Apache 2.0 License](LICENSE). Anyone is welcome to fork it,
adapt it, and build upon it. See the [NOTICE](NOTICE) file for attribution details.
