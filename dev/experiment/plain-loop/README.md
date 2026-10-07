# The plain-loop ceiling experiment

One question: **can the local model build the bookshelf demo feature when it is given a
plain agent harness with native tool calling and nothing else?**

SwarmCoder drives the same model through Koog with tool calls rendered as text, a ~51k
working window compacted at 75%, and a scaffold of nudges, forced write-pauses and kills.
Workers read for 16-24 calls before writing, invent names, and get killed. If the same
model succeeds here, the model is not the problem.

Nothing in this directory tunes for a result. The harness was written once, run twice with
identical settings, and the measurement is taken by the human harness (not the model) after
each run finishes.

## Requirements

Python 3.10+, standard library plus **`requests`**. Nothing else — no OpenAI SDK, no agent
framework.

## Running it

```
python plain_loop.py --run 1                 # thinking ON  (server default)
python plain_loop.py --run 2                 # thinking ON  (variance check)
python plain_loop.py --run 3 --no-thinking   # thinking OFF (SwarmCoder's setting)
```

Each run clones `C:/work/swarmcoder/dev/bookshelf-demo` at `master` HEAD into
`<scratch>/plain-loop/run-<n>/repo` and works there. The source repository is never touched
(a clone is a read). Run them **one at a time** — the GPU is shared.

## The model endpoint

The only endpoint this script may talk to is the free local one:
`http://192.168.0.10:8002/v1`, model `qwen3.8-27b` (SGLang, OpenAI-compatible, 262k
context). The host is asserted at import time, so the script refuses to start if that
constant is ever edited to point somewhere paid.

Temperature 0.2, `max_tokens` 8192.

**Thinking mode is a controlled variable, and it matters.** This server's default is
thinking **ON** — a plain request comes back with about a thousand characters of
`reasoning_content` before the answer. SwarmCoder's workers run with thinking **OFF** by
design (a per-model default chosen for speed on the older Qwen 3.6). So the plain harness
and SwarmCoder differ here even before the scaffold is counted.

Runs 1 and 2 use the server default (thinking on) — that is what a plain harness gets if
nobody intervenes. Run 3 passes `chat_template_kwargs: {"enable_thinking": false}` on every
request, everything else identical, so the same harness can be seen with and without
thinking. Verified against the endpoint: with the flag, `reasoning_content` is empty,
`reasoning_tokens` is 0, and native tool calling still works.

Every run records, per turn, whether `reasoning_content` came back and how long it was, plus
run totals in `stats.json` (`reasoning_turns`, `reasoning_chars`, `reasoning_tokens`), and
states its thinking mode at the top of `transcript.md` and in `result.md`.

## What the harness is

Deliberately minimal. Five tools and a loop:

| Tool | |
|---|---|
| `list_files(dir)` | directory listing |
| `read_file(path)` | whole file, no cap |
| `write_file(path, content)` | whole file |
| `run(command)` | shell in the repo dir, 10-minute timeout, output truncated to 8k chars |
| `done(summary)` | ends the run |

Tools are sent in the OpenAI `tools` parameter with `tool_choice: auto`. Replies are read
from `tool_calls`, results are sent back as `role: tool` messages with `tool_call_id`.
Native, the way the API defines it.

**What is deliberately absent:** no write set or path policy, no nudges, no read pause, no
kill thresholds, no history compaction, no per-worker persona, no knowledge brief, no
documentation-search tool. Caps are turn 200 and wall clock 3 hours, both far beyond what
the task should need, and both are stated as outcomes if hit. A run with no model reply for
20 minutes is recorded as a stall.

The **one** boundary that is not part of the experiment: `write_file` refuses to write
outside the cloned repository. That is machine safety on a shared workstation, not agent
scaffolding — nothing in the task calls for writing elsewhere. Reads are unrestricted, so
the ZeroZ Stack documentation and examples are reachable.

## What the model is told

System prompt, in full — where the repository is, the two Maven commands, where the docs
and examples are, "read before guessing an API", and "call done when the build is green".
That is all.

User message — the story, then the full text of `dev/bookshelf-requirements.md` and
`dev/bookshelf-tech-requirements.md`. No summarisation, no extraction, no planning stage.

## What each run leaves behind

In `<scratch>/plain-loop/run-<n>/`:

- `transcript.jsonl` — every request and every reply, full JSON
- `transcript.md` — readable: turn, tokens, tool, arguments and results truncated
- `stats.json` — turns, tool-call counts by tool, the turn of the first `write_file`,
  documentation reads, token totals, largest single prompt, wall time, stop reason,
  any tool call repeated three or more times with identical arguments
- `result.md` — the measurement, written after the run by the human harness

## The measurement, identical for both runs

Taken from the repository the model left behind, by running the builds independently:

1. `mvn -o -q -B -DfastCompile compile test-compile` — compiles yes/no, first error
2. `mvn -o -q -B test -Dtest=swarm.accept.BookListTest -Dsurefire.failIfNoSpecifiedTests=false`
   — the acceptance test exists / runs / passes
3. `git diff --stat` — what changed
4. Does the server pom now declare `zerozstack-store-eclipsestore`? (`master` deliberately
   does not; the technical requirements say persistence uses it.)
5. Does persistence actually go through EclipseStore, or through an in-memory collection?
6. Does the client UI use `com.zeroz4j.ui.*` components?
7. Does any forbidden technology appear — Spring, JPA, REST, JSON, JavaScript, Vaadin?
8. Turns before the first `write_file`; documentation reads; any loop.

## The control condition

The ZeroZ runs cannot on their own tell "this model cannot build" apart from "this model
cannot learn ZeroZ Stack from its documentation". The control separates them: same harness,
same model, same endpoint, a task of the same size and shape on **plain Java with nothing
unfamiliar**.

```
python plain_loop.py --run 1 --control                 # thinking ON
python plain_loop.py --run 2 --control --no-thinking   # thinking OFF
```

It starts from `control/seed` - one Maven module, Java 21, junit-jupiter 5.11.4,
surefire 3.2.5, compiler 3.13.0, every version present in the host's local Maven repository
so `mvn -o` resolves offline. The empty project was proved to compile and test green
before the first run. The seed is committed as plain files, not as a nested git repository;
the loop clones it when it is a git repo and otherwise copies it and makes the first commit
itself, so either way the model starts on a clean repository with one commit.

The system prompt is the ZeroZ one with the two stack sentences removed: where the repo is,
the two Maven commands, call done when green. There is no documentation folder to read.

Caps are 120 turns and 2 hours. The measurement is the same, minus the stack-conformance
rows, plus the number of tests surefire actually ran.
